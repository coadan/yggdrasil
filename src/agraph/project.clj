(ns agraph.project
  "Project config loading and multi-repo orchestration."
  (:require [charred.api :as json]
            [agraph.fs :as fs]
            [agraph.index :as index]
            [agraph.system :as system]
            [agraph.xtdb :as store]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- config-dir
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))

(defn- resolve-root
  [base root]
  (let [file (io/file root)]
    (fs/canonical-path
     (if (.isAbsolute file)
       file
       (io/file base root)))))

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- infer-role
  [repo-id]
  (cond
    (str/includes? repo-id "cli") :tooling
    (or (str/includes? repo-id "env")
        (str/includes? repo-id "infra")
        (str/includes? repo-id "k8s")) :infrastructure
    :else :application))

(defn- normalize-repo
  [base repo]
  (let [repo-id (some-> (:id repo) str)]
    (when (str/blank? repo-id)
      (throw (ex-info "Project repo is missing :id." {:repo repo})))
    (when-not (:root repo)
      (throw (ex-info "Project repo is missing :root." {:repo repo})))
    {:id repo-id
     :root (resolve-root base (:root repo))
     :role (keyword (or (:role repo) :repository))}))

(defn- existing-dir
  [& paths]
  (some (fn [path]
          (when path
            (let [file (io/file path)]
              (when (.isDirectory file)
                (fs/canonical-path file)))))
        paths))

(defn- workbench-repos
  [base {:keys [workbench-root workbench-task]}]
  (let [root (resolve-root base workbench-root)
        repos-json (io/file root "repos.json")
        repos (-> (read-json-file repos-json) :repos keys sort)]
    (mapv
     (fn [repo-id]
       (let [task-root (when workbench-task
                         (io/file root ".worktrees" (str workbench-task) (name repo-id)))
             cache-root (io/file root ".workbench" "repos" (name repo-id))
             resolved (existing-dir task-root cache-root)]
         (when-not resolved
           (throw (ex-info "Workbench repo root not found."
                           {:workbench-root root
                            :workbench-task workbench-task
                            :repo-id repo-id})))
         {:id (name repo-id)
          :root resolved
          :role (infer-role (name repo-id))}))
     repos)))

(defn- normalize-repos
  [base data]
  (cond
    (seq (:repos data))
    (mapv #(normalize-repo base %) (:repos data))

    (:workbench-root data)
    (workbench-repos base data)

    :else
    (throw (ex-info "Project config is missing :repos or :workbench-root." {}))))

(defn read-project
  "Read and normalize a project.edn file."
  [path]
  (let [base (config-dir path)
        data (edn/read-string (slurp (io/file path)))
        project-id (some-> (:id data) str)]
    (when (str/blank? project-id)
      (throw (ex-info "Project config is missing :id." {:path path})))
    {:id project-id
     :name (str (or (:name data) project-id))
     :path (fs/canonical-path path)
     :repos (normalize-repos base data)}))

(defn- project-row
  [{:keys [id name]} updated-at-ms]
  {:xt/id (str "project:" id)
   :project-id id
   :name name
   :active? true
   :updated-at-ms updated-at-ms})

(defn- repo-row
  [project-id updated-at-ms {:keys [id root role]}]
  {:xt/id (str "repo:" project-id ":" id)
   :project-id project-id
   :repo-id id
   :root root
   :role role
   :active? true
   :updated-at-ms updated-at-ms})

(defn persist-project!
  "Persist project metadata rows."
  [xtdb project]
  (let [updated-at-ms (now-ms)]
    (store/commit-project! xtdb
                           (project-row project updated-at-ms)
                           (mapv #(repo-row (:id project) updated-at-ms %) (:repos project)))))

(defn index-project!
  "Index every repo in project config into XTDB."
  [xtdb project {:keys [dry-run?] :or {dry-run? false}}]
  (if dry-run?
    {:project-id (:id project)
     :status :dry-run
     :repos (mapv (fn [{:keys [id root role]}]
                    (index/index-repo! nil
                                       root
                                       {:dry-run? true
                                        :project-id (:id project)
                                        :repo-id id
                                        :repo-role role}))
                  (:repos project))}
    (do
      (persist-project! xtdb project)
      {:project-id (:id project)
       :status :completed
       :repos (mapv (fn [{:keys [id root role]}]
                      (index/index-repo! xtdb
                                         root
                                         {:project-id (:id project)
                                          :repo-id id
                                          :repo-role role}))
                    (:repos project))})))

(defn infer-project!
  "Infer and persist a derived system graph for project."
  [xtdb project]
  (persist-project! xtdb project)
  (system/infer-project! xtdb project))

(defn maintain-project
  "Return read-only maintenance findings for project."
  [xtdb project opts]
  (system/maintenance-report xtdb (:id project) opts))
