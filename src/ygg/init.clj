(ns ygg.init
  "Project config initialization for Yggdrasil onboarding."
  (:require [ygg.agent-install :as agent-install]
            [ygg.command :as command]
            [ygg.fs :as fs]
            [ygg.project-registry :as registry]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
        (prn value)))
    (.getPath file)))

(defn- repos-json-repo-ids
  [workbench-root]
  (let [file (io/file workbench-root "repos.json")]
    (when (.exists file)
      (->> (:repos (json/read-json (slurp file) :key-fn keyword))
           keys
           (map name)
           sort
           vec))))

(defn- repos-json-count
  [workbench-root]
  (count (repos-json-repo-ids workbench-root)))

(defn- existing-dir
  [& paths]
  (some (fn [path]
          (when path
            (let [file (io/file path)]
              (when (.isDirectory file)
                (fs/canonical-path file)))))
        paths))

(defn- workbench-repo-ref-roots
  [workbench-root task]
  (let [root (fs/canonical-path workbench-root)]
    (keep (fn [repo-id]
            (existing-dir
             (when task
               (io/file root ".worktrees" (str task) repo-id))
             (io/file root ".workbench" "repos" repo-id)))
          (repos-json-repo-ids root))))

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

(defn- agent-install-command
  [platform {:keys [hooks? skill? mcp?]}]
  (str "ygg agent install --platform "
       (command/shell-token platform)
       " --project"
       (when hooks? " --hooks")
       (when skill? " --skill")
       (when mcp? " --mcp")))

(defn- service-start-at-login-command
  []
  "ygg service start-at-login enable")

(def ^:private maintenance-kinds
  #{:maintenance-decision :infra-review :dependency-review})

(defn- maintenance-mode
  [opts]
  (some-> (:maintenance opts) str str/lower-case))

(defn- maintenance-schedules
  []
  [{:id "sync"
    :task :sync
    :enabled true
    :every-minutes 10
    :check false
    :enqueue false
    :query-index true}
   {:id "check"
    :task :sync
    :enabled true
    :every-minutes 60
    :check true
    :enqueue true}])

(defn- ygg-home
  []
  (or (not-empty (System/getProperty "ygg.home"))
      (not-empty (System/getenv "YGG_HOME"))
      (fs/canonical-path ".")))

(defn- bundled-maintenance-command
  [filename]
  (.getPath (io/file (ygg-home) "scripts" filename)))

(defn- maintenance-harness-command
  [platform opts]
  (if-let [command (:maintenance-command opts)]
    [command]
    (case (or platform "codex")
      "codex" [(bundled-maintenance-command "ygg-maintenance-codex.sh")]
      (throw (ex-info "No maintenance command is known for harness."
                      {:platform platform
                       :hint "Pass --maintenance-command."})))))

(defn- maintenance-executor
  [mode platform opts]
  (let [reasoning (or (:maintenance-reasoning opts) "medium")]
    (case mode
      "harness"
      {:id (or platform "codex")
       :type :command-harness
       :command (maintenance-harness-command platform opts)
       :reasoning reasoning
       :kinds maintenance-kinds}

      "deepseek"
      {:id "deepseek"
       :type :openai-compatible
       :provider :deepseek
       :model (or (:maintenance-model opts) "deepseek-v4-flash")
       :reasoning reasoning
       :env "YGG_DEEPSEEK_API_KEY"
       :kinds maintenance-kinds}

      "openrouter"
      {:id "openrouter-deepseek-v4"
       :type :openai-compatible
       :provider :openrouter
       :model (or (:maintenance-model opts) "deepseek/deepseek-v4-flash")
       :reasoning reasoning
       :env "YGG_OPENROUTER_API_KEY"
       :kinds maintenance-kinds}

      (throw (ex-info "Unsupported init maintenance mode."
                      {:maintenance mode
                       :supported ["none" "harness" "deepseek" "openrouter"]})))))

(defn- maintenance-config
  [opts platform]
  (let [mode (maintenance-mode opts)]
    (when (and mode (not (#{"none" "off" "false"} mode)))
      {:enabled true
       :work {:max-decisions 8
              :max-decisions-per-kind 4
              :max-infra-reviews 8
              :max-dependency-reviews 8
              :decision-batch-size 8
              :review-batch-size 8}
       :schedules (maintenance-schedules)
       :worker {:enabled true
                :agent-id "ygg-auto"
                :max-items-per-run 1
                :apply {:mode :complete-only}
                :executors [(maintenance-executor mode platform opts)]}})))

(defn- next-actions
  [project-id config-path setup]
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
            :command (agent-install-command
                      "codex"
                      {:hooks? true
                       :skill? true
                       :mcp? true})}
           {:kind :service-start-at-login
            :label "Start Yggdrasil automatically when this user logs in"
            :command (service-start-at-login-command)}]
    (:mcp setup)
    (conj {:kind :mcp
           :label "Configure MCP in your assistant harness"
           :command (get-in setup [:mcp :command])})
    (and (:maintenance setup) config-path)
    (conj {:kind :maintenance-status
           :label "Inspect configured auto maintenance"
           :command (str "ygg maintenance status "
                         (command/shell-token config-path))})
    true vec))

(defn- next-commands
  [actions]
  (mapv :command actions))

(defn- write-project-ref!
  [project-id root]
  (registry/write-project-ref! root project-id))

(defn- project-ref-roots
  [config]
  (let [workbench-root (:workbench-root config)]
    (distinct
     (concat
      (keep (comp existing-dir :root) (:repos config))
      (when workbench-root
        (workbench-repo-ref-roots workbench-root
                                  (:workbench-task config)))))))

(defn- write-project-refs!
  [project-id roots]
  (mapv #(write-project-ref! project-id %) roots))

(defn- maybe-assoc-maintenance
  [config opts]
  (if-let [maintenance (maintenance-config opts (:maintenance-platform opts))]
    (assoc config :maintenance maintenance)
    config))

(defn- requested-harness
  [opts]
  (or (:harness opts)
      (when (or (:hooks? opts) (:skill? opts) (:mcp? opts))
        "auto")
      "none"))

(defn- harness-setup
  [root opts]
  (let [requested (requested-harness opts)
        detect (agent-install/detect-platforms {:root root})
        platform (agent-install/resolve-platform requested {:root root})
        install? (and platform
                      (or (not= "auto" requested)
                          (:hooks? opts)
                          (:skill? opts)
                          (:mcp? opts)))
        install-result (when install?
                         (agent-install/install!
                          platform
                          {:root root
                           :project? true
                           :hooks? (:hooks? opts)
                           :skill? (:skill? opts)
                           :mcp? (:mcp? opts)
                           :force? (:force-agent? opts)}))]
    (cond-> {:requested requested
             :detected (:platforms detect)
             :platform platform
             :installed (boolean install-result)}
      install-result (assoc :install install-result)
      (and (:mcp? opts) platform)
      (assoc :mcp (get install-result :mcp
                       {:command "ygg-mcp --config project.edn"})))))

(defn plain-config
  "Return project config data for a normal repo root."
  [root opts]
  (let [root (repo-root root)
        project-id (or (:project-id opts) (default-project-id root))]
    (maybe-assoc-maintenance
     {:id project-id
      :name (or (:name opts) project-id)
      :repos [{:id "app"
               :root root
               :role :application}]}
     opts)))

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
      (:task opts) (assoc :workbench-task (:task opts))
      true (maybe-assoc-maintenance opts))))

(defn init!
  "Write a project config when requested and register the project for lookup."
  [root {:keys [out force? workbench?] :as opts}]
  (let [config (if workbench?
                 (workbench-config root opts)
                 (plain-config root opts))
        config-path (when out
                      (write-edn! out config force?))
        registry-result (if config-path
                          (registry/register-project-config! config-path)
                          (registry/upsert-project! config))
        init-record (registry/record-init!)
        project-id (:id config)
        root (or (:workbench-root config) (get-in config [:repos 0 :root]))
        project-refs (write-project-refs! project-id (project-ref-roots config))
        project-ref (first project-refs)
        repo-count (if workbench?
                     (+ (count (:repos config))
                        (or (repos-json-count (:workbench-root config)) 0))
                     (count (:repos config)))
        harness (harness-setup root opts)
        maintenance (maintenance-config
                     opts
                     (or (get-in harness [:platform])
                         (:maintenance-platform opts)))
        setup {:harness harness
               :mcp (:mcp harness)
               :maintenance (when maintenance
                              {:mode (maintenance-mode opts)
                               :enabled true
                               :executor (get-in maintenance [:worker :executors 0 :id])})}
        actions (next-actions project-id config-path setup)]
    (cond-> {:schema schema
             :project-id project-id
             :name (:name config)
             :mode (if workbench? "workbench" "repo")
             :root root
             :project-ref project-ref
             :project-refs project-refs
             :repos repo-count
             :registry (:registry init-record)
             :first-init (:first-init init-record)
             :init-count (:init-count init-record)
             :setup setup
             :next (next-commands actions)
             :nextActions actions}
      config-path (assoc :config config-path)
      true (assoc :registry (:registry registry-result)
                  :registered true))))
