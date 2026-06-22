(ns ygg.init
  "Project config initialization for Yggdrasil onboarding."
  (:require [ygg.command :as command]
            [ygg.fs :as fs]
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
  [config-path & args]
  (str "ygg sync " (command/shell-token config-path)
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- ask-command
  [project-id]
  (str "ygg ask \"where is this handled?\" --project "
       (command/shell-token project-id)
       " --json"))

(defn- view-systems-command
  [project-id]
  (str "ygg view systems --project " (command/shell-token project-id)))

(defn- next-actions
  [project-id config-path map-path]
  (cond-> [{:kind :sync
            :label "Index and validate project graph"
            :command (str (sync-command config-path "--check")
                          (when map-path
                            (str " --map " (command/shell-token map-path))))}
           {:kind :ask
            :label "Ask a graph-grounded implementation question"
            :command (ask-command project-id)}
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
             :workbench-root root}
      (:task opts) (assoc :workbench-task (:task opts)))))

(defn init!
  "Write a project config and return a compact onboarding summary."
  [root {:keys [out force? map-path workbench?] :as opts}]
  (let [config (if workbench?
                 (workbench-config root opts)
                 (plain-config root opts))
        config-path (write-edn! (or out "project.edn") config force?)
        project-id (:id config)
        repo-count (if workbench?
                     (or (repos-json-count (:workbench-root config)) 0)
                     (count (:repos config)))
        actions (next-actions project-id config-path map-path)]
    {:schema schema
     :project-id project-id
     :name (:name config)
     :config config-path
     :mode (if workbench? "workbench" "repo")
     :root (or (:workbench-root config) (get-in config [:repos 0 :root]))
     :repos repo-count
     :next (next-commands actions)
     :nextActions actions}))
