(ns ygg.init
  "Project config initialization for Yggdrasil onboarding."
  (:require [ygg.command :as command]
            [ygg.fs :as fs]
            [ygg.project-registry :as registry]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def schema
  "ygg.init/v1")

(defn- slug
  [value]
  (-> (str value)
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-._]+|[-._]+$)" "")
      not-empty
      (or "project")))

(defn- repo-root
  [root]
  (let [root-file (io/file root)
        {:keys [exit out]} (shell/sh "git"
                                     "-C"
                                     (.getPath root-file)
                                     "rev-parse"
                                     "--show-toplevel")]
    (if (zero? exit)
      (fs/canonical-path (str/trim out))
      (fs/canonical-path root-file))))

(defn- default-project-id
  [root]
  (slug (.getName (io/file root))))

(defn- write-edn!
  [path value force?]
  (let [file (io/file path)]
    (when (and (.exists file) (not force?))
      (throw (ex-info "Project config already exists. Pass --force to replace it."
                      {:path (.getPath file)})))
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (with-open [writer (io/writer file)]
      (binding [*out* writer
                *print-namespace-maps* false]
        (pprint/pprint value)))
    (.getPath file)))

(defn- repos-json-count
  [workbench-root]
  (let [file (io/file workbench-root "repos.json")]
    (when (.exists file)
      (count (:repos (json/read-json (slurp file) :key-fn keyword))))))

(defn- sync-command
  [project-id config-path & args]
  (str "ygg sync "
       (if config-path
         (command/shell-token config-path)
         (str "--project " (command/shell-token project-id)))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- query-command
  [project-id]
  (str "ygg query \"where is this handled?\" --project "
       (command/shell-token project-id)
       " --json"))

(defn- view-systems-command
  [project-id]
  (str "ygg view systems --project " (command/shell-token project-id)))

(defn- next-actions
  [project-id config-path]
  (cond-> [{:kind :sync
            :label "Index and validate project graph"
            :command (sync-command project-id config-path "--check")}
           {:kind :query
            :label "Query graph-grounded implementation context"
            :command (query-command project-id)}
           {:kind :systems
            :label "Inspect system graph"
            :command (view-systems-command project-id)}
           {:kind :agent-install
            :label "Install project-local agent guidance"
            :command "ygg agent install --platform codex --project"}]
    true vec))

(defn- next-commands
  [actions]
  (mapv :command actions))

(defn- write-project-ref!
  [project-id root]
  (registry/write-project-ref! root project-id))

(defn plain-config
  "Return project config data for a normal repo root."
  [root opts]
  (let [root (repo-root root)
        project-id (or (:project-id opts) (default-project-id root))]
    {:id project-id
     :name (or (:name opts) project-id)
     :repos [{:id "app"
              :root root
              :role :application}]}))

(defn workbench-config
  "Return project config data for a workbench root."
  [root opts]
  (let [root (fs/canonical-path root)
        project-id (or (:project-id opts) (default-project-id root))]
    (cond-> {:id project-id
             :name (or (:name opts) project-id)
             :workbench-root root
             :repos [{:id "workbench"
                      :root root
                      :role :tooling}]}
      (:task opts) (assoc :workbench-task (:task opts)))))

(defn init!
  "Register a project or write a portable project config when :out is provided."
  [root {:keys [out force? workbench?] :as opts}]
  (let [config (if workbench?
                 (workbench-config root opts)
                 (plain-config root opts))
        registry-mode? (nil? out)
        config-path (when out
                      (write-edn! out config force?))
        registry-result (when registry-mode?
                          (registry/upsert-project! config))
        project-id (:id config)
        project-ref (write-project-ref! project-id
                                        (or (:workbench-root config)
                                            (get-in config [:repos 0 :root])))
        repo-count (if workbench?
                     (+ (count (:repos config))
                        (or (repos-json-count (:workbench-root config)) 0))
                     (count (:repos config)))
        actions (next-actions project-id config-path)]
    (cond-> {:schema schema
             :project-id project-id
             :name (:name config)
             :mode (if workbench? "workbench" "repo")
             :root (or (:workbench-root config) (get-in config [:repos 0 :root]))
             :project-ref project-ref
             :repos repo-count
             :next (next-commands actions)
             :nextActions actions}
      config-path (assoc :config config-path)
      registry-mode? (assoc :registry (:registry registry-result)
                            :registered true))))
