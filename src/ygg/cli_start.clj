(ns ygg.cli-start
  (:require [ygg.activity :as activity]
            [ygg.cli-options :refer [option-value positional-args]]
            [ygg.command :as command]
            [ygg.map :as graph-map]
            [ygg.map-api :as map-api]
            [ygg.map-store :as map-store]
            [ygg.init :as init]
            [ygg.project :as project]
            [ygg.report :as report]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI start dependency." {:dependency k})))
         args))

(defn- print-json
  [data]
  (call-dep :print-json data))

(defn- dispatch
  [command args]
  (call-dep :dispatch command args))

(defn- query-index?
  [args]
  (call-dep :query-index? args))

(defn- enqueue-output?
  [args]
  (call-dep :enqueue-output? args))

(defn- sync-index-project!
  [xtdb project args]
  (call-dep :sync-index-project! xtdb project args))

(defn- maintenance-report
  [xtdb project args]
  (call-dep :maintenance-report xtdb project args))

(defn- enqueue-sync-work!
  [args report]
  (call-dep :enqueue-sync-work! args report))

(defn- queue-root
  [args]
  (call-dep :queue-root args))

(def start-schema
  "ygg.start/v1")
(defn- ensure-init-map!
  [project-id map-path]
  (when (and map-path
             (not (map-store/file-exists? map-path)))
    (map-api/init! map-path project-id)))
(defn- start-map-path
  [args]
  (cond
    (some #{"--no-map"} args) nil
    (option-value args "--map") (option-value args "--map")
    :else graph-map/default-path))
(defn- project-config-exists?
  [path]
  (.exists (io/file path)))
(defn- init-sync-args
  [config-path map-path query-index?]
  (cond-> [config-path "--check" "--no-progress"]
    map-path (into ["--map" map-path])
    query-index? (conj "--query-index")))
(defn init!
  [args deps]
  (binding [*deps* deps]
    (let [workbench-root (option-value args "--workbench")
          root (or workbench-root (first (positional-args args)) ".")
          map-path (option-value args "--map")
          result (init/init! root
                             {:out (option-value args "--out")
                              :force? (boolean (some #{"--force"} args))
                              :project-id (option-value args "--project")
                              :name (option-value args "--name")
                              :workbench? (boolean workbench-root)
                              :task (option-value args "--task")
                              :map-path map-path})]
      (ensure-init-map! (:project-id result) map-path)
      (print-json
       (cond-> result
         (some #{"--sync"} args)
         (assoc :sync-output
                (with-out-str
                  (dispatch "sync"
                            (init-sync-args (:config result)
                                            map-path
                                            (query-index? args))))))))))
(defn- sync-project-result!
  [xtdb project args]
  (let [repo-id (option-value args "--repo")
        check? (or (some #{"--check"} args)
                   (enqueue-output? args))
        index-summary (sync-index-project! xtdb project args)
        system-summary (project/infer-project! xtdb project)
        report (when check?
                 (maintenance-report xtdb project args))
        enqueued (when (and report (enqueue-output? args))
                   (enqueue-sync-work! args report))]
    (cond-> {:schema "ygg.sync/v1"
             :project-id (:id project)
             :repo-id repo-id
             :index-summary index-summary
             :system-summary system-summary}
      report (assoc :check-report report)
      enqueued (assoc :enqueued enqueued))))
(defn- start-project!
  [root config-path map-path args]
  (if (and (project-config-exists? config-path)
           (not (some #{"--force"} args)))
    {:mode "existing"
     :config config-path
     :project (project/read-project config-path)}
    (let [result (init/init! root
                             {:out config-path
                              :force? (boolean (some #{"--force"} args))
                              :project-id (option-value args "--project")
                              :name (option-value args "--name")
                              :workbench? (boolean (option-value args "--workbench"))
                              :task (option-value args "--task")
                              :map-path map-path})]
      {:mode "initialized"
       :config (:config result)
       :init result
       :project (project/read-project (:config result))})))
(defn start-next-actions
  [project-id config-path map-path report-out]
  (cond-> [{:kind :inspect
            :label "Inspect freshness and evidence planes"
            :command (str "ygg sync inspect " (command/shell-token config-path)
                          (when map-path
                            (str " --map " (command/shell-token map-path)))
                          " --json")}
           {:kind :activity
            :label "Import local work and validation activity"
            :command (str "ygg sync activity " (command/shell-token config-path)
                          " --json")}
           {:kind :audit-scope
            :label "Inspect audit scopes and caveats"
            :command (str "ygg audit-scope " (command/shell-token config-path)
                          (when map-path
                            (str " --map " (command/shell-token map-path)))
                          " --json")}
           {:kind :dependencies
            :label "Inspect package dependency evidence"
            :command (str "ygg packages --project "
                          (command/shell-token project-id)
                          " --json")}
           {:kind :query
            :label "Query graph-grounded implementation context"
            :command (str "ygg query \"where is this handled?\" --project "
                          (command/shell-token project-id)
                          " --json")}
           {:kind :systems
            :label "Inspect system graph"
            :command (str "ygg view systems --project "
                          (command/shell-token project-id))}
           {:kind :report
            :label "Open or regenerate local report bundle"
            :command (str "ygg report " (command/shell-token config-path)
                          (when map-path
                            (str " --map " (command/shell-token map-path)))
                          " --out " (command/shell-token report-out))}
           {:kind :agent-install
            :label "Install project-local agent guidance"
            :command "ygg agent install --platform codex --project"}]
    true vec))
(defn start-next-commands
  [actions]
  (mapv :command actions))
(defn- sum-repo-stats
  [sync-result]
  (->> (get-in sync-result [:index-summary :repos])
       (map :stats)
       (apply merge-with +)))
(defn- compact-counts
  [sync-result activity-result]
  (let [repo-stats (sum-repo-stats sync-result)
        system-summary (:system-summary sync-result)
        maintenance-counts (get-in sync-result [:check-report :counts])
        activity-counts (:counts activity-result)]
    {:files {:scanned (:files-scanned repo-stats 0)
             :indexed (:files-indexed repo-stats 0)
             :skipped (:files-skipped repo-stats 0)
             :deleted (:files-deleted repo-stats 0)
             :diagnostics (:diagnostics repo-stats 0)}
     :graph {:nodes (:nodes repo-stats 0)
             :edges (:edges repo-stats 0)
             :file-facts (:file-facts repo-stats 0)
             :chunks (:chunks repo-stats 0)
             :search-docs (:search-docs repo-stats 0)}
     :systems {:nodes (:system-nodes system-summary 0)
               :edges (:system-edges system-summary 0)
               :evidence (:system-evidence system-summary 0)
               :maintenance-decisions (:maintenance-decisions maintenance-counts 0)
               :orphaned-candidates (:orphaned-systems maintenance-counts 0)}
     :activity {:items (:items activity-counts 0)
                :events (:events activity-counts 0)
                :validation-events (:validation-events activity-counts 0)
                :result-schema-mismatch-events (:result-schema-mismatch-events
                                                activity-counts
                                                0)}}))
(defn- compact-report
  [report-result]
  {:out (:out report-result)
   :files (:files report-result)})
(defn- completed-status?
  [value]
  (contains? #{:completed "completed"} value))
(defn- action-by-kind
  [actions kind]
  (some #(when (= kind (:kind %)) %) actions))
(defn- start-readiness
  [sync-result report-result actions]
  (let [sync-completed? (completed-status? (get-in sync-result [:index-summary :status]))
        systems-completed? (completed-status? (get-in sync-result [:system-summary :status]))
        report-written? (boolean (:out report-result))
        evidence-available? (boolean (seq (get-in report-result [:evidence :available])))
        query-action (action-by-kind actions :query)
        agent-action (action-by-kind actions :agent-install)
        ready? (and sync-completed?
                    systems-completed?
                    report-written?
                    query-action)]
    {:status (if ready? :ready :limited)
     :basis :start-run
     :summary (if ready?
                "Ready for query with the graph produced by this start run."
                "Start produced a partial setup; inspect checks and nextActions before using query.")
     :readyFor (cond-> []
                 query-action (conj :query)
                 systems-completed? (conj :systems)
                 report-written? (conj :report))
     :checks {:graph-sync sync-completed?
              :system-inference systems-completed?
              :report-written report-written?
              :evidence-summary evidence-available?}
     :agentGuidance {:status :available
                     :installed false
                     :command (:command agent-action)}}))
(defn- start-result
  [project start-info map-path report-out sync-result activity-result report-result]
  (let [actions (start-next-actions (:id project)
                                    (:config start-info)
                                    map-path
                                    report-out)]
    (cond-> {:schema start-schema
             :project-id (:id project)
             :mode (:mode start-info)
             :config (:config start-info)
             :map map-path
             :report (compact-report report-result)
             :counts (compact-counts sync-result activity-result)
             :evidence (:evidence report-result)
             :readiness (start-readiness sync-result report-result actions)
             :next (start-next-commands actions)
             :nextActions actions}
      (:init start-info) (assoc :initialized true))))
(defn start!
  [args deps]
  (binding [*deps* deps]
    (let [workbench-root (option-value args "--workbench")
          root (or workbench-root (first (positional-args args)) ".")
          config-path (or (option-value args "--out") "project.edn")
          map-path (start-map-path args)
          report-out (or (option-value args "--report-out") report/default-output-dir)
          start-info (start-project! root config-path map-path args)
          project (:project start-info)]
      (ensure-init-map! (:id project) map-path)
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [sync-args (init-sync-args (:config start-info)
                                          map-path
                                          (query-index? args))
                sync-result (sync-project-result! xtdb project sync-args)
                activity-result (activity/sync-queue! xtdb
                                                      project
                                                      {:queue-root (queue-root args)})
                report-result (report/bundle! xtdb
                                              project
                                              {:out report-out
                                               :map-path map-path
                                               :detail (keyword (or (option-value args "--detail")
                                                                    (name report/default-detail)))
                                               :force? (boolean (some #{"--force"} args))})]
            (print-json
             (start-result project
                           start-info
                           map-path
                           report-out
                           sync-result
                           activity-result
                           report-result))))))))
