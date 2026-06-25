(ns ygg.server
  "Local warm server for latency-sensitive Yggdrasil commands."
  (:require [ygg.cli-options :refer [dry-run?
                                     json-output?
                                     option-value
                                     positional-args]]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.progress :as progress]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [integrant.core :as ig])
  (:import [java.io BufferedReader Closeable InputStreamReader OutputStreamWriter
            PrintWriter StringWriter]
           [java.lang ProcessHandle]
           [java.net InetAddress ServerSocket Socket]
           [java.util.concurrent ExecutorService Executors ThreadFactory TimeUnit]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.logging LogManager]))

(def schema
  "ygg.server/v1")

(def server-frame-schema
  "ygg.server.frame/v1")

(def default-host
  "127.0.0.1")

(def default-port
  62121)

(def ^:private sync-subcommands
  #{"inspect"
    "activity"
    "add-repo"
    "coverage"
    "docs"
    "meta"
    "view"
    "work"})

(def ^:private cli-command-ops
  #{"help"
    "init"
    "current"
    "use"
    "projects"
    "audit-scope"
    "maintenance"
    "corrections"
    "memory"
    "affected"
    "packages"
    "plugin"
    "agent"
    "watch"
    "hook"
    "bench"
    "embed"})

(def ^:private cli-query-command-handlers
  {"query" 'ygg.cli-query/query!
   "view" 'ygg.cli-query/view!
   "report" 'ygg.cli-query/report!})

(def ^:private logged-ops
  (set (concat ["sync"]
               (keys cli-query-command-handlers)
               cli-command-ops)))

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- call-var
  [symbol & args]
  (apply (or (requiring-resolve symbol)
             (throw (ex-info "Unable to load server command dependency."
                             {:symbol symbol})))
         args))

(defn- cli-sync-deps
  []
  (call-var 'ygg.cli/sync-deps))

(defn- run-command-handler!
  [command args]
  (call-var 'ygg.cli/dispatch command args))

(defn- pid
  []
  (-> (ProcessHandle/current) .pid))

(defn server-host
  []
  (or (some-> (System/getenv "YGG_SERVER_HOST") str/trim not-empty)
      default-host))

(defn- parse-port
  [raw]
  (try
    (let [port (Long/parseLong (str/trim raw))]
      (if (<= 1 port 65535)
        (int port)
        (throw (ex-info "Invalid YGG_SERVER_PORT."
                        {:env "YGG_SERVER_PORT"
                         :value raw
                         :reason "port-out-of-range"}))))
    (catch NumberFormatException _
      (throw (ex-info "Invalid YGG_SERVER_PORT."
                      {:env "YGG_SERVER_PORT"
                       :value raw
                       :reason "not-an-integer"})))))

(defn server-port
  []
  (if-let [raw (some-> (System/getenv "YGG_SERVER_PORT") str/trim not-empty)]
    (parse-port raw)
    default-port))

(defn server-endpoint
  []
  {:host (server-host)
   :port (server-port)})

(defn- server-token
  []
  (some-> (System/getenv "YGG_SERVER_TOKEN") str/trim not-empty))

(defn- read-json-line
  [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (json/read-json line :key-fn keyword)))

(defn- write-json-line!
  [^PrintWriter writer value]
  (.println writer (json/write-json-str value))
  (.flush writer))

(defn- sync-progress-frame
  [event]
  (when-let [message (progress/sync-progress-message event)]
    {:schema server-frame-schema
     :type "progress"
     :operation "sync"
     :message message
     :event event}))

(defn- sync-server-progress-fn
  [ctx args]
  (when (and (:emit-frame! ctx)
             (not (json-output? args))
             (not (some #{"--no-progress"} args)))
    (fn [event]
      (when-let [frame (sync-progress-frame event)]
        ((:emit-frame! ctx) frame)))))

(defn- error-response
  [^Exception e]
  (let [data (ex-data e)]
    {:ok false
     :exit (long (or (:exit data) 1))
     :out ""
     :err (str (or (ex-message e) (.getMessage e)) "\n")
     :data data}))

(defn- request-id
  [ctx]
  (if-let [^AtomicLong counter (:request-counter ctx)]
    (.incrementAndGet counter)
    0))

(defn- log-request!
  [event row]
  (binding [*out* *err*]
    (println (json/write-json-str
              (assoc row
                     :schema "ygg.server.request-log/v1"
                     :event event)))))

(defn- loggable-request
  [{:keys [op args cwd projectId project-id storagePath storage-path]}]
  (cond-> {:op op
           :args (vec args)}
    cwd (assoc :cwd cwd)
    (or projectId project-id) (assoc :projectId (or projectId project-id))
    (or storagePath storage-path) (assoc :storagePath (or storagePath storage-path))))

(defn- response-status
  [response]
  (if (:ok response) "ok" "error"))

(defn- authorized?
  [{:keys [token]} request]
  (or (str/blank? token)
      (= token (:token request))))

(defn- canonical-path
  [file]
  (try
    (.getCanonicalPath (io/file file))
    (catch Exception _
      (.getAbsolutePath (io/file file)))))

(defn- same-path?
  [left right]
  (and left
       right
       (= (canonical-path (io/file left))
          (canonical-path (io/file right)))))

(defn- absolute-path
  [cwd path]
  (let [file (io/file path)]
    (canonical-path
     (if (.isAbsolute file)
       file
       (io/file (or cwd (System/getProperty "user.dir")) path)))))

(def ^:private path-value-options
  #{"--config" "--out" "--map" "--result" "--queue-dir" "--fixture"
    "--report-out" "--python" "--venv" "--cache-dir" "--shell-report"
    "--ygg-report" "--agent-report" "--baseline-report" "--candidate-report"
    "--markdown-out" "--output-schema"})

(defn- path-like-value?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (str/includes? value "://"))))

(defn- absolutize-path-options
  [cwd args]
  (if (str/blank? (str cwd))
    args
    (loop [remaining (seq args)
           out []]
      (if-not remaining
        out
        (let [arg (first remaining)
              value (second remaining)]
          (if (and (path-value-options arg)
                   (path-like-value? value))
            (recur (nnext remaining)
                   (conj out arg (absolute-path cwd value)))
            (recur (next remaining)
                   (conj out arg))))))))

(defn- explicit-storage-path
  [request]
  (or (:storage-path request)
      (:storagePath request)
      (get-in request [:env :YGG_XTDB_PATH])))

(defn- with-user-dir
  [cwd f]
  (if cwd
    (let [previous (System/getProperty "user.dir")]
      (try
        (System/setProperty "user.dir" cwd)
        (f)
        (finally
          (System/setProperty "user.dir" previous))))
    (f)))

(defn- project-config-candidates
  [cli-args opts]
  (let [positional (positional-args (vec cli-args))]
    (->> (concat [(:config-path opts)
                  (option-value (vec cli-args) "--config")]
                 positional)
         (keep identity)
         (filter #(str/ends-with? (str %) ".edn"))
         distinct
         vec)))

(defn- project-id-from-config
  [cwd config-path]
  (try
    (:id (if cwd
           (with-user-dir cwd #(project/read-project config-path))
           (project/read-project config-path)))
    (catch Exception _
      nil)))

(defn- project-id-from-cli
  [request cli-args opts]
  (or (:project-id request)
      (:projectId request)
      (:project-id opts)
      (option-value (vec cli-args) "--project")
      (some #(project-id-from-config (:cwd request) %)
            (project-config-candidates cli-args opts))
      (try
        (:project-id (registry/resolve-project {:cwd (:cwd request)}))
        (catch Exception _
          nil))))

(defn- request-storage-path
  [request cli-args opts]
  (if-let [path (explicit-storage-path request)]
    (absolute-path (:cwd request) path)
    (store/storage-path (project-id-from-cli request cli-args opts))))

(defn- node-for!
  [{:keys [node-pool xtdb]} storage-path]
  (if xtdb
    xtdb
    (let [path (absolute-path nil storage-path)]
      (locking node-pool
        (or (get @node-pool path)
            (let [node (store/open-node! path)]
              (swap! node-pool assoc path node)
              node))))))

(defn- close-node-pool!
  [node-pool]
  (doseq [node (vals @node-pool)]
    (store/stop-node! node))
  (reset! node-pool {}))

(defn- present-flag
  [args flag value]
  (if value
    (conj args flag)
    args))

(defn- option-flag
  [args flag value]
  (if (some? value)
    (conj args flag (str value))
    args))

(defn sync-options->args
  "Convert server sync options into the normal sync option vector.

   This keeps scheduled/system-event sync calls on the same option semantics as
   the CLI without requiring those callers to shell out."
  [{:keys [config-path project-id repo-id queue-dir check? enqueue? query-index? dry-run?
           json? no-progress? min-confidence]}]
  (let [args (cond
               config-path [config-path]
               project-id ["--project" project-id]
               :else [])]
    (-> args
        (option-flag "--repo" repo-id)
        (option-flag "--queue-dir" queue-dir)
        (option-flag "--min-confidence" min-confidence)
        (present-flag "--check" check?)
        (present-flag "--enqueue" enqueue?)
        (present-flag "--query-index" query-index?)
        (present-flag "--dry-run" dry-run?)
        (present-flag "--json" json?)
        (present-flag "--no-progress" no-progress?))))

(defn- sync-args->options
  [args]
  (let [positional (positional-args args)]
    {:config-path (first positional)
     :project-id (option-value args "--project")
     :repo-id (option-value args "--repo")
     :queue-dir (option-value args "--queue-dir")
     :check? (boolean (some #{"--check"} args))
     :enqueue? (boolean (some #{"--enqueue"} args))
     :query-index? (boolean (some #{"--query-index"} args))
     :dry-run? (dry-run? args)
     :json? (json-output? args)
     :no-progress? (boolean (some #{"--no-progress"} args))
     :min-confidence (option-value args "--min-confidence")}))

(defn- sync-project
  [{:keys [project config-path project-id cwd]}]
  (cond
    project
    project

    config-path
    (project/read-project config-path)

    :else
    (:project (registry/resolve-project {:project-id project-id
                                         :cwd cwd}))))

(defn- default-worker-queue-dir
  [project opts]
  (cond-> opts
    (and (:enqueue? opts)
         (not (:queue-dir opts))
         (get-in project [:maintenance :queue-dir]))
    (assoc :queue-dir (get-in project [:maintenance :queue-dir]))))

(defn- worker-run-options
  [opts]
  (select-keys opts [:queue-dir]))

(defn- run-index-maintenance-worker
  [project opts enqueued]
  (when (and (seq enqueued)
             (get-in project [:maintenance :worker]))
    (index-maintenance-worker/run! project (worker-run-options opts))))

(defn- sync-index-project!
  [xtdb project args sync-deps {:keys [progress-fn]}]
  (if progress-fn
    (call-var 'ygg.cli-sync/sync-index-project!
              xtdb
              project
              args
              sync-deps
              {:progress-fn progress-fn})
    (call-var 'ygg.cli-sync/sync-index-project!
              xtdb
              project
              args
              sync-deps)))

(defn run-sync!
  "Run a regular project sync inside the server JVM and return the result map.

   Callers may pass structured options instead of invoking CLI dispatch. This is
   intended for both server protocol requests and future in-process schedule or
   system-event handlers."
  [xtdb {:keys [repo-id check? enqueue? dry-run?] :as opts}]
  (let [project (sync-project opts)
        opts (default-worker-queue-dir project opts)
        args (sync-options->args opts)
        sync-deps (cli-sync-deps)
        check? (or check? enqueue?)]
    (if dry-run?
      (let [index-summary (sync-index-project! nil project args sync-deps opts)]
        {:schema "ygg.sync/v1"
         :project-id (:id project)
         :repo-id repo-id
         :index-summary index-summary})
      (let [index-summary (sync-index-project! xtdb project args sync-deps opts)
            system-summary (project/infer-project! xtdb project)
            report (when check?
                     (call-var 'ygg.cli-sync/maintenance-report
                               xtdb
                               project
                               args
                               sync-deps))
            enqueued (when (and report enqueue?)
                       (call-var 'ygg.cli-sync/enqueue-sync-work!
                                 args
                                 report
                                 sync-deps))
            worker-run (run-index-maintenance-worker project opts enqueued)]
        (cond-> {:schema "ygg.sync/v1"
                 :project-id (:id project)
                 :repo-id repo-id
                 :index-summary index-summary
                 :system-summary system-summary}
          report (assoc :check-report report)
          enqueued (assoc :enqueued enqueued)
          worker-run (assoc :maintenance-worker worker-run))))))

(defn- print-sync-result!
  [args result]
  (if (json-output? args)
    (println (json/write-json-str result))
    (call-var 'ygg.cli-project/print-sync-summary result)))

(def scheduler-poll-ms
  5000)

(def ^:private scheduler-busy
  ::scheduler-busy)

(def ^:private unlocked-cli-commands
  #{"current"
    "query"
    "help"
    "--help"
    "-h"})

(defn- unlocked-cli-operation?
  [cli-args]
  (let [args (vec cli-args)
        command (first args)
        subcommand (second args)]
    (or (contains? unlocked-cli-commands command)
        (and (= "sync" command)
             (#{"activity" "inspect"} subcommand)))))

(defn- operation-lock
  [ctx]
  (:operation-lock ctx))

(defn- locked-operation
  [ctx on-busy f]
  (if-let [^ReentrantLock lock (operation-lock ctx)]
    (if (.tryLock lock)
      (try
        (f)
        (finally
          (.unlock lock)))
      (on-busy))
    (locking ctx
      (f))))

(defn- operation-lock-busy!
  []
  (throw (ex-info "Yggdrasil server is busy running another operation."
                  {:exit daemon-contract/unavailable-exit
                   :reason "operation-lock-busy"})))

(defn- with-operation-lock
  [ctx f]
  (locked-operation ctx operation-lock-busy! f))

(defn- try-operation-lock
  [ctx f]
  (locked-operation ctx (constantly scheduler-busy) f))

(defn- with-cli-operation
  [ctx cli-args f]
  (if (unlocked-cli-operation? cli-args)
    (f)
    (with-operation-lock ctx f)))

(defn- schedule-key
  [project-id schedule-id]
  (str project-id ":" schedule-id))

(defn- schedule-sync-options
  [project schedule]
  {:project project
   :project-id (:id project)
   :repo-id (:repo-id schedule)
   :check? (:check schedule)
   :enqueue? (:enqueue schedule)
   :query-index? (:query-index schedule)
   :json? true
   :no-progress? true})

(defn- run-maintenance-schedule!
  [ctx project schedule]
  (try-operation-lock
   ctx
   (fn []
     (let [xtdb (node-for! ctx (store/storage-path (:id project)))
           opts (schedule-sync-options project schedule)]
       (case (:task schedule)
         :sync (run-sync! xtdb opts))))))

(defn- due-schedule?
  [state now-ms project-id schedule]
  (let [key (schedule-key project-id (:id schedule))
        last-run-ms (get-in @state [:last-run-ms key])]
    (cond
      (nil? last-run-ms)
      (:run-on-start schedule)

      :else
      (<= (* 60000 (long (:every-minutes schedule)))
          (- now-ms (long last-run-ms))))))

(defn- mark-schedule-seen!
  [state now-ms project-id schedule]
  (swap! state assoc-in [:last-run-ms (schedule-key project-id (:id schedule))] now-ms))

(defn- record-schedule-run!
  [state run]
  (swap! state update :runs
         (fn [runs]
           (->> (conj (vec runs) run)
                (take-last 25)
                vec))))

(defn- registered-projects
  []
  (let [registry (registry/read-registry)]
    (mapv (fn [project-id]
            (try
              {:project (registry/read-project registry project-id)}
              (catch Exception e
                {:project-id project-id
                 :error (or (ex-message e) (.getMessage e))
                 :error-data (ex-data e)})))
          (sort (keys (registry/projects registry))))))

(defn- maintenance-project-status
  [{:keys [project project-id error error-data]}]
  (if project
    (index-maintenance-worker/config-status project)
    {:schema "ygg.maintenance.config-status/v1"
     :project-id project-id
     :configured false
     :enabled false
     :schedules []
     :worker {:configured false
              :enabled false
              :executors []
              :executorCount 0
              :availableExecutorCount 0
              :availableKinds []}
     :error error
     :errorData error-data}))

(defn- scheduler-tick!
  [ctx]
  (let [state (:scheduler-state ctx)
        now-ms (System/currentTimeMillis)]
    (doseq [{:keys [project] :as project-entry} (registered-projects)]
      (if-not project
        (record-schedule-run! state
                              {:status "error"
                               :ranAtMs now-ms
                               :projectId (:project-id project-entry)
                               :error (:error project-entry)})
        (when (get-in project [:maintenance :enabled])
          (doseq [schedule (get-in project [:maintenance :schedules])]
            (when (:enabled schedule)
              (let [key (schedule-key (:id project) (:id schedule))]
                (if (due-schedule? state now-ms (:id project) schedule)
                  (do
                    (mark-schedule-seen! state now-ms (:id project) schedule)
                    (try
                      (let [result (run-maintenance-schedule! ctx project schedule)]
                        (record-schedule-run!
                         state
                         (if (= scheduler-busy result)
                           {:status "skipped-busy"
                            :ranAtMs now-ms
                            :projectId (:id project)
                            :scheduleId (:id schedule)
                            :task (:task schedule)
                            :reason "operator-command-running"}
                           {:status "completed"
                            :ranAtMs now-ms
                            :projectId (:id project)
                            :scheduleId (:id schedule)
                            :task (:task schedule)
                            :resultSchema (:schema result)})))
                      (catch Exception e
                        (record-schedule-run!
                         state
                         {:status "failed"
                          :ranAtMs now-ms
                          :projectId (:id project)
                          :scheduleId (:id schedule)
                          :task (:task schedule)
                          :error (or (ex-message e) (.getMessage e))
                          :errorData (ex-data e)}))))
                  (when-not (get-in @state [:last-run-ms key])
                    (mark-schedule-seen! state now-ms (:id project) schedule)))))))))))

(defn- serve-scheduler!
  [ctx]
  (let [running (:running ctx)]
    (while @running
      (try
        (scheduler-tick! ctx)
        (catch Throwable t
          (record-schedule-run! (:scheduler-state ctx)
                                {:status "failed"
                                 :ranAtMs (System/currentTimeMillis)
                                 :error (or (ex-message t) (.getMessage t))})))
      (try
        (Thread/sleep scheduler-poll-ms)
        (catch InterruptedException e
          (when @running
            (throw e)))))))

(defn- start-scheduler!
  [ctx]
  (let [thread (Thread. #(serve-scheduler! ctx) "ygg-maintenance-scheduler")]
    (.setDaemon thread true)
    (.start thread)
    thread))

(declare capture-response)

(defn- server-status
  [ctx]
  (let [open-node-paths (if-let [node-pool (:node-pool ctx)]
                          (sort (keys @node-pool))
                          [])
        ^ReentrantLock lock (operation-lock ctx)
        maintenance-projects (mapv maintenance-project-status (registered-projects))
        enabled-projects (filterv :enabled maintenance-projects)
        scheduled-count (reduce + (map #(count (:schedules %)) maintenance-projects))
        scheduler-state @(or (:scheduler-state ctx) (atom {}))]
    {:schema "ygg.server.status/v1"
     :status "running"
     :host (:host ctx)
     :port (:port ctx)
     :pid (pid)
     :storagePath (:storage-path ctx)
     :startedAtMs (:started-at-ms ctx)
     :busy (boolean (some-> lock .isLocked))
     :queuedRequests (long (or (some-> lock .getQueueLength) 0))
     :openNodes (count open-node-paths)
     :openNodePaths (vec open-node-paths)
     :maintenance {:scheduler {:running true
                               :pollMs scheduler-poll-ms
                               :recentRuns (vec (:runs scheduler-state))}
                   :projects maintenance-projects
                   :projectCount (count maintenance-projects)
                   :enabledProjectCount (count enabled-projects)
                   :scheduleCount scheduled-count}}))

(defn- print-server-status
  [args status]
  (if (json-output? args)
    (println (json/write-json-str status))
    (do
      (println "# Server")
      (println "- status" (:status status))
      (when (and (:host status) (:port status))
        (println "- endpoint" (str (:host status) ":" (:port status))))
      (println "- pid" (:pid status))
      (when (:storagePath status)
        (println "- storage-path" (:storagePath status)))
      (when (:startedAtMs status)
        (println "- started-at-ms" (:startedAtMs status)))
      (println "- busy" (boolean (:busy status)))
      (println "- queued-requests" (:queuedRequests status 0))
      (println "- open-nodes" (:openNodes status))
      (doseq [path (:openNodePaths status)]
        (println "  -" path))
      (println)
      (println "# Auto Maintenance")
      (println "- scheduler running")
      (println "- projects"
               (get-in status [:maintenance :enabledProjectCount])
               "/"
               (get-in status [:maintenance :projectCount])
               "enabled")
      (println "- schedules" (get-in status [:maintenance :scheduleCount]))
      (doseq [{:keys [project-id enabled schedules worker error]} (get-in status
                                                                          [:maintenance
                                                                           :projects])]
        (println "-"
                 project-id
                 (if enabled "enabled" "disabled")
                 (str "schedules=" (count schedules))
                 (str "worker="
                      (cond
                        error "error"
                        (not (:configured worker)) "not-configured"
                        (:enabled worker) "enabled"
                        :else "disabled"))
                 (str "executors="
                      (:availableExecutorCount worker)
                      "/"
                      (:executorCount worker)))
        (doseq [{:keys [id task enabled every-minutes enqueue check query-index]} schedules]
          (println "  schedule"
                   id
                   (if enabled "enabled" "disabled")
                   (name task)
                   (str "every=" every-minutes "m")
                   (str "check=" check)
                   (str "enqueue=" enqueue)
                   (str "query-index=" query-index))))
      (when-let [runs (seq (get-in status [:maintenance :scheduler :recentRuns]))]
        (println "- recent-runs" (count runs))))))

(defn- status-response
  [ctx request]
  (capture-response
   (fn []
     (let [status (server-status ctx)]
       (print-server-status (vec (:args request)) status)
       status))))

(defn- capture-response
  [f]
  (let [out (StringWriter.)
        err (StringWriter.)]
    (try
      (let [data (binding [*out* out
                           *err* err]
                   (f))]
        (cond-> {:ok true
                 :exit 0
                 :out (str out)
                 :err (str err)}
          (some? data) (assoc :data data)))
      (catch Exception e
        (-> (error-response e)
            (assoc :out (str out))
            (update :err #(str err %)))))))

(defn- sync-response
  [ctx request]
  (let [args (vec (:args request))
        progress-fn (sync-server-progress-fn ctx args)
        opts (merge (sync-args->options args)
                    {:cwd (:cwd request)}
                    (:options request)
                    (when progress-fn
                      {:progress-fn progress-fn}))
        xtdb (node-for! ctx (request-storage-path request args opts))]
    (capture-response
     (fn []
       (with-operation-lock
         ctx
         (fn []
           (with-user-dir (:cwd request)
             (fn []
               (let [result (run-sync! xtdb opts)]
                 (print-sync-result! args result)
                 result)))))))))

(defn- with-request-storage
  [ctx request cli-args f]
  (let [request-project-id (project-id-from-cli request cli-args nil)
        warm-path (request-storage-path request cli-args nil)
        original-storage-path store/storage-path
        original-with-node store/with-node]
    (with-redefs [store/storage-path
                  (fn
                    ([] warm-path)
                    ([project-id]
                     (if (or (nil? project-id)
                             (= (str project-id) (str request-project-id)))
                       warm-path
                       (original-storage-path project-id))))
                  store/with-node
                  (fn [path f]
                    (let [requested-path (or path warm-path)]
                      (if (same-path? warm-path requested-path)
                        (f (node-for! ctx warm-path))
                        (if (:node-pool ctx)
                          (f (node-for! ctx requested-path))
                          (original-with-node path f)))))]
      (f))))

(defn- captured-request-storage-response
  [ctx request cli-args lock! f]
  (capture-response
   (fn []
     (with-user-dir (:cwd request)
       (fn []
         (lock!
          (fn []
            (with-request-storage ctx request cli-args f))))))))

(defn- command-response
  [ctx request command args]
  (let [args (absolutize-path-options (:cwd request) (vec args))
        cli-args (into [command] args)]
    (captured-request-storage-response
     ctx
     request
     cli-args
     #(with-cli-operation ctx cli-args %)
     #(run-command-handler! command args))))

(defn- cli-query-response
  [ctx request command handler-symbol]
  (let [args (absolutize-path-options (:cwd request) (vec (:args request)))
        cli-args (into [command] args)
        query-deps-var (requiring-resolve 'ygg.cli-query/*deps*)]
    (captured-request-storage-response
     ctx
     request
     cli-args
     #(with-cli-operation ctx cli-args %)
     #(with-bindings {query-deps-var (call-var 'ygg.cli/query-deps)}
        (call-var handler-symbol args)))))

(defn- sync-subcommand-response
  [ctx request subcommand args]
  (command-response ctx request "sync" (into [subcommand] args)))

(defn- sync-response-dispatch
  [ctx request]
  (let [[subcommand & args] (vec (:args request))]
    (if (contains? sync-subcommands subcommand)
      (sync-subcommand-response ctx request subcommand args)
      (sync-response ctx request))))

(defn- stop-response
  [{:keys [running server]}]
  (reset! running false)
  (future
    (Thread/sleep 25)
    (when (instance? Closeable server)
      (.close ^Closeable server)))
  {:ok true
   :exit 0
   :out "stopping\n"
   :err ""})

(defn- mcp-response
  [ctx request]
  (let [args (vec (:args request))
        message (:message request)]
    (captured-request-storage-response
     ctx
     request
     args
     #(with-operation-lock ctx %)
     #(call-var 'ygg.mcp/handle-message
                (call-var 'ygg.mcp/server-context args)
                message))))

(defn- handle-authorized-request
  [{:keys [running server] :as ctx} request]
  (let [op (:op request)]
    (cond
      (= "ping" op)
      {:ok true
       :exit 0
       :out "ok\n"
       :err ""}

      (= "status" op)
      (status-response ctx request)

      (= "sync" op)
      (sync-response-dispatch ctx request)

      (contains? cli-query-command-handlers op)
      (cli-query-response ctx request op (get cli-query-command-handlers op))

      (contains? cli-command-ops op)
      (command-response ctx request op (:args request))

      (= "mcp" op)
      (mcp-response ctx request)

      (= "stop" op)
      (stop-response {:running running
                      :server server})

      :else
      {:ok false
       :exit 1
       :out ""
       :err (str "Unknown server op: " op "\n")})))

(defn- handle-logged-request
  [ctx request]
  (let [id (request-id ctx)
        started-ns (System/nanoTime)
        log-row (assoc (loggable-request request) :requestId id)]
    (log-request! "start" log-row)
    (try
      (let [response (handle-authorized-request ctx request)
            elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)]
        (log-request! "finish"
                      (assoc log-row
                             :status (response-status response)
                             :exit (:exit response)
                             :elapsedMs elapsed-ms))
        response)
      (catch Throwable t
        (let [elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)]
          (log-request! "error"
                        (assoc log-row
                               :status "thrown"
                               :elapsedMs elapsed-ms
                               :error (or (ex-message t) (.getMessage t)))))
        (throw t)))))

(defn handle-request
  "Handle one decoded server protocol request."
  [ctx request]
  (if-not (authorized? ctx request)
    {:ok false
     :exit 1
     :out ""
     :err "Unauthorized server request.\n"}
    (if (contains? logged-ops (:op request))
      (handle-logged-request ctx request)
      (handle-authorized-request ctx request))))

(defn- handle-socket!
  [ctx ^Socket socket]
  (with-open [socket socket
              reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
              writer (PrintWriter. (OutputStreamWriter. (.getOutputStream socket)))]
    (let [request (read-json-line reader)
          request-ctx (cond-> ctx
                        (:stream request)
                        (assoc :emit-frame! #(write-json-line! writer %)))]
      (write-json-line! writer (handle-request request-ctx request)))))

(defn- request-executor
  []
  (let [counter (AtomicLong.)]
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ runnable]
         (doto (Thread. runnable
                        (str "ygg-server-request-" (.incrementAndGet counter)))
           (.setDaemon true)))))))

(defn- submit-request!
  [{:keys [request-executor] :as ctx} socket]
  (.execute ^ExecutorService request-executor
            (reify Runnable
              (run [_]
                (try
                  (handle-socket! ctx socket)
                  (catch Throwable t
                    (.printStackTrace t)))))))

(defn- serve-requests!
  [{:keys [running server] :as ctx}]
  (while @running
    (try
      (submit-request! ctx (.accept ^ServerSocket server))
      (catch java.net.SocketException e
        (when @running
          (throw e))))))

(defn server-system-config
  "Returns the Integrant config for the long-lived local Yggdrasil server."
  []
  {:ygg.server/node-pool {}
   :ygg.server/server {:node-pool (ig/ref :ygg.server/node-pool)
                       :storage-path (absolute-path nil (store/storage-path))}})

(defmethod ig/init-key :ygg.server/node-pool
  [_ _]
  (atom {}))

(defmethod ig/halt-key! :ygg.server/node-pool
  [_ node-pool]
  (close-node-pool! node-pool))

(defmethod ig/init-key :ygg.server/server
  [_ {:keys [node-pool storage-path]}]
  (let [{:keys [host port]} (server-endpoint)
        token (server-token)
        started-at-ms (System/currentTimeMillis)
        server (ServerSocket. port 50 (InetAddress/getByName host))
        running (atom true)
        request-executor (request-executor)
        request-counter (AtomicLong.)
        operation-lock (ReentrantLock.)
        scheduler-state (atom {:last-run-ms {}
                               :runs []})
        stopped (promise)
        ctx {:token token
             :running running
             :server server
             :request-executor request-executor
             :request-counter request-counter
             :operation-lock operation-lock
             :node-pool node-pool
             :host host
             :port (.getLocalPort server)
             :storage-path storage-path
             :started-at-ms started-at-ms
             :scheduler-state scheduler-state}
        thread (Thread.
                (fn []
                  (try
                    (serve-requests! ctx)
                    (catch Throwable t
                      (when @running
                        (.printStackTrace t)))
                    (finally
                      (deliver stopped true))))
                "ygg-server-accept")
        scheduler-thread (start-scheduler! ctx)]
    (.setDaemon thread false)
    (.start thread)
    (binding [*out* *err*]
      (println (str "ygg server listening on "
                    host
                    ":"
                    (.getLocalPort server))))
    {:schema schema
     :token token
     :server server
     :running running
     :node-pool node-pool
     :thread thread
     :request-executor request-executor
     :request-counter request-counter
     :operation-lock operation-lock
     :scheduler-thread scheduler-thread
     :scheduler-state scheduler-state
     :host host
     :port (.getLocalPort server)
     :storage-path storage-path
     :started-at-ms started-at-ms
     :stopped stopped}))

(defmethod ig/halt-key! :ygg.server/server
  [_ {:keys [server running thread request-executor scheduler-thread stopped]}]
  (reset! running false)
  (when (instance? Closeable server)
    (.close ^Closeable server))
  (when (and thread
             (not= thread (Thread/currentThread)))
    (.join ^Thread thread 5000))
  (when (and scheduler-thread
             (not= scheduler-thread (Thread/currentThread)))
    (.interrupt ^Thread scheduler-thread)
    (.join ^Thread scheduler-thread 5000))
  (when request-executor
    (.shutdownNow ^ExecutorService request-executor)
    (.awaitTermination ^ExecutorService request-executor 5 TimeUnit/SECONDS))
  (deliver stopped true)
  nil)

(defn start!
  "Starts the Yggdrasil server Integrant system."
  []
  (ig/init (server-system-config)))

(defn stop!
  "Stops a Yggdrasil server Integrant system."
  [system]
  (ig/halt! system))

(defn serve!
  []
  (let [!system (atom (start!))
        halt! (fn []
                (when-let [system @!system]
                  (reset! !system nil)
                  (stop! system)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. halt!))
    (try
      (when-let [system @!system]
        @(get-in system [:ygg.server/server :stopped]))
      (finally
        (halt!)))))

(defn- print-main-usage!
  []
  (binding [*out* *err*]
    (println "Usage: ygg.server start")))

(defn -main
  [& args]
  (try
    (silence-jul!)
    (let [[command & command-args] args]
      (if (and (= "start" command)
               (empty? command-args))
        (serve!)
        (do
          (print-main-usage!)
          (shutdown-agents)
          (System/exit 2))))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (let [data (ex-data e)]
          (println (or (ex-message e) (.getMessage e)))
          (when data
            (pprint/pprint data))))
      (shutdown-agents)
      (System/exit 1))))
