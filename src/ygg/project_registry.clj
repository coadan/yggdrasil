(ns ygg.project-registry
  "User-level project registry and project resolution."
  (:require [ygg.fs :as fs]
            [ygg.project :as project]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def schema
  "ygg.projects/v1")

(def project-ref-schema
  "ygg.project-ref/v1")

(defn config-home
  "Return the user-level Yggdrasil config directory."
  []
  (or (System/getenv "YGG_CONFIG_HOME")
      (str (io/file (or (System/getenv "XDG_CONFIG_HOME")
                        (str (io/file (System/getProperty "user.home") ".config")))
                    "ygg"))))

(defn registry-path
  "Return the user-level project registry path."
  []
  (or (System/getenv "YGG_PROJECTS_FILE")
      (str (io/file (config-home) "projects.edn"))))

(defn empty-registry
  []
  {:schema schema
   :projects {}
   :active-by-dir {}})

(defn read-registry
  "Read the user-level project registry, returning an empty registry if missing."
  ([] (read-registry (registry-path)))
  ([path]
   (let [file (io/file path)]
     (if (.isFile file)
       (merge (empty-registry) (edn/read-string (slurp file)))
       (empty-registry)))))

(defn write-registry!
  "Write the user-level project registry and return the normalized value."
  ([registry] (write-registry! (registry-path) registry))
  ([path registry]
   (let [file (io/file path)
         registry (merge (empty-registry) registry)]
     (when-let [parent (.getParentFile file)]
       (.mkdirs parent))
     (with-open [writer (io/writer file)]
       (binding [*out* writer
                 *print-namespace-maps* false]
         (pprint/pprint registry)))
     registry)))

(defn- registry-base
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))

(defn- project-entry
  [id data]
  (assoc data :id (str (or (:id data) id))))

(defn projects
  "Return registry project definitions keyed by project id."
  ([registry] (:projects registry))
  ([] (projects (read-registry))))

(defn project-config
  "Return raw project config data for id from registry."
  ([project-id] (project-config (read-registry) project-id))
  ([registry project-id]
   (when-let [data (get (:projects registry) (str project-id))]
     (project-entry project-id data))))

(defn- normalize-project-entry
  [_registry project-id data]
  (if-let [config-path (:config-path data)]
    (let [project (project/read-project config-path)]
      (when-not (= (str project-id) (:id project))
        (throw (ex-info "Registered project config id does not match registry id."
                        {:project-id (str project-id)
                         :config-path config-path
                         :config-project-id (:id project)})))
      project)
    (project/normalize-project (registry-base (registry-path))
                               (project-entry project-id data)
                               {:path (str (registry-path) "#" project-id)})))

(defn read-project
  "Return a normalized project by id from the user registry."
  ([project-id] (read-project (read-registry) project-id))
  ([registry project-id]
   (if-let [data (project-config registry project-id)]
     (normalize-project-entry registry project-id data)
     (throw (ex-info "Project is not registered."
                     {:project-id project-id
                      :registry (registry-path)})))))

(defn upsert-project!
  "Insert or replace a project definition in the user registry."
  [project-config]
  (let [project-id (str (:id project-config))]
    (when (str/blank? project-id)
      (throw (ex-info "Cannot register project without :id." {:project project-config})))
    (let [registry (read-registry)
          stored (assoc project-config :id project-id)
          updated (assoc-in registry [:projects project-id] stored)]
      (write-registry! updated)
      {:schema "ygg.project.registry.upsert/v1"
       :project-id project-id
       :registry (registry-path)
       :project (read-project updated project-id)})))

(defn register-project-config!
  "Register an existing project config by reference and return a summary."
  [config-path]
  (let [config-path (fs/canonical-path config-path)
        project (project/read-project config-path)
        project-id (:id project)
        result (upsert-project! {:id project-id
                                 :config-path config-path})]
    (assoc result
           :schema "ygg.project.registry.register/v1"
           :config-path config-path)))

(defn remove-project!
  "Remove project id from registry and return a compact summary."
  [project-id]
  (let [project-id (str project-id)
        registry (read-registry)
        updated (-> registry
                    (update :projects dissoc project-id)
                    (update :active-by-dir
                            (fn [bindings]
                              (into {}
                                    (remove (fn [[_ id]]
                                              (= project-id (str id))))
                                    bindings))))]
    (write-registry! updated)
    {:schema "ygg.project.registry.remove/v1"
     :project-id project-id
     :removed? (contains? (:projects registry) project-id)}))

(defn- canonical-dir
  [path]
  (fs/canonical-path (or path ".")))

(defn project-ref-path
  "Return the repo-local Yggdrasil project reference path below root."
  [root]
  (str (io/file (canonical-dir root) ".ygg" "project.edn")))

(defn write-project-ref!
  "Write a repo-local project reference and return its path."
  [root project-id]
  (let [path (project-ref-path root)
        file (io/file path)
        value {:schema project-ref-schema
               :project-id (str project-id)}]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (with-open [writer (io/writer file)]
      (binding [*out* writer
                *print-namespace-maps* false]
        (pprint/pprint value)))
    path))

(defn- parent-dirs
  [path]
  (->> (iterate #(.getParentFile ^java.io.File %) (.getCanonicalFile (io/file path)))
       (take-while some?)
       (map #(.getPath ^java.io.File %))))

(defn- read-project-ref
  [root]
  (let [path (project-ref-path root)
        file (io/file path)]
    (when (.isFile file)
      (let [data (edn/read-string (slurp file))]
        (when-let [project-id (or (:project-id data) (:projectId data))]
          {:project-id (str project-id)
           :root (canonical-dir root)
           :path path})))))

(defn project-ref-for-cwd
  "Return the nearest repo-local project reference for cwd, if present."
  [cwd]
  (first (keep read-project-ref (parent-dirs (or cwd ".")))))

(defn- descendant-or-same?
  [root path]
  (let [root (canonical-dir root)
        path (canonical-dir path)]
    (or (= root path)
        (str/starts-with? path (str root java.io.File/separator)))))

(defn- repo-root-matches
  [cwd [project-id data]]
  (try
    (let [project (normalize-project-entry (read-registry) project-id data)]
      (keep (fn [{:keys [root]}]
              (when (descendant-or-same? root cwd)
                {:project-id (str project-id)
                 :root (canonical-dir root)
                 :project project}))
            (:repos project)))
    (catch Exception _
      [])))

(defn- best-matches
  [matches]
  (let [max-length (apply max (map (comp count :root) matches))]
    (filterv #(= max-length (count (:root %))) matches)))

(defn project-for-cwd
  "Resolve a project by matching cwd against registered repo roots."
  ([cwd] (project-for-cwd (read-registry) cwd))
  ([registry cwd]
   (let [matches (vec (mapcat #(repo-root-matches cwd %) (:projects registry)))]
     (when (seq matches)
       (let [best (best-matches matches)
             project-ids (distinct (map :project-id best))]
         (if (= 1 (count project-ids))
           (assoc (first best)
                  :source :cwd
                  :registry (registry-path))
           (throw (ex-info "Current directory matches multiple registered projects."
                           {:cwd (canonical-dir cwd)
                            :project-ids project-ids
                            :matches (mapv #(select-keys % [:project-id :root]) best)}))))))))

(defn active-project
  "Return active project binding for cwd, if any."
  ([cwd] (active-project (read-registry) cwd))
  ([registry cwd]
   (let [bindings (:active-by-dir registry)
         matches (keep (fn [[root project-id]]
                         (when (descendant-or-same? root cwd)
                           {:root (canonical-dir root)
                            :project-id (str project-id)}))
                       bindings)]
     (when (seq matches)
       (let [{:keys [project-id root]} (first (sort-by (comp - count :root) matches))]
         {:project-id project-id
          :root root
          :project (read-project registry project-id)
          :source :active-dir
          :registry (registry-path)})))))

(defn referenced-project
  "Resolve the nearest repo-local project reference for cwd through the registry."
  [registry cwd]
  (when-let [{:keys [project-id root path]} (project-ref-for-cwd cwd)]
    {:project-id project-id
     :root root
     :project (read-project registry project-id)
     :source :project-ref
     :project-ref path
     :registry (registry-path)}))

(defn use-project!
  "Bind cwd to project-id in the user registry."
  [project-id cwd]
  (let [project-id (str project-id)
        registry (read-registry)
        project (read-project registry project-id)
        root (canonical-dir cwd)
        project-ref (write-project-ref! root project-id)
        updated (assoc-in registry [:active-by-dir root] project-id)]
    (write-registry! updated)
    {:schema "ygg.project.use/v1"
     :project-id project-id
     :root root
     :project-ref project-ref
     :registry (registry-path)
     :project project}))

(defn resolve-project
  "Resolve a project from explicit ids, cwd, active bindings, or config path."
  [{:keys [project-id cwd config-path env-project-id]}]
  (let [registry (read-registry)
        cwd (or cwd (System/getProperty "user.dir"))
        id (or project-id env-project-id (System/getenv "YGG_PROJECT_ID"))]
    (cond
      (not (str/blank? (str id)))
      {:project-id (str id)
       :project (read-project registry id)
       :source (if project-id :project-option :project-env)
       :registry (registry-path)}

      config-path
      (let [project (project/read-project config-path)]
        {:project-id (:id project)
         :project project
         :config-path config-path
         :source :config-path})

      :else
      (or (referenced-project registry cwd)
          (project-for-cwd registry cwd)
          (active-project registry cwd)
          (throw (ex-info "Could not resolve Yggdrasil project."
                          {:cwd (canonical-dir cwd)
                           :registry (registry-path)
                           :hint "Run ygg init --project <id> <repo-root>, ygg use <project-id>, or pass --project."}))))))
