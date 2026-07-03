(ns ygg.server
  "Local warm server for latency-sensitive Yggdrasil commands."
  (:require [ygg.cli-options :refer [dry-run?
                                     json-output?
                                     option-value
                                     positional-args]]
            [ygg.cli :as cli]
            [ygg.cli-project :as cli-project]
            [ygg.cli-query :as cli-query]
            [ygg.cli-start :as cli-start]
            [ygg.cli-sync :as cli-sync]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.embedding-client :as embedding-client]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.mcp :as mcp]
            [ygg.progress :as progress]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
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

(def ^:private cli-command-names
  ["help"
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
   "hook"
   "bench"
   "embed"])

(def ^:private cli-command-dispatch
  (assoc (zipmap cli-command-names cli-command-names)
         "--help" "help"
         "-h" "help"))

(def ^:private cli-query-command-handlers
  {"query" #'cli-query/query!
   "view" #'cli-query/view!
   "report" #'cli-query/report!})

(def ^:private logged-ops
  (set (concat ["sync" "init"]
               (keys cli-query-command-handlers)
               (keys cli-command-dispatch))))

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- cli-sync-deps
  []
  (cli/sync-deps))

(defn- run-command-handler!
  [command args]
  (cli/dispatch command args))

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

(defn- log-lifecycle!
  [event row]
  (binding [*out* *err*]
    (println (json/write-json-str
              (assoc row
                     :schema "ygg.server.lifecycle/v1"
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

(defn- operation-project-id
  [request cli-args]
  (or (:projectId request)
      (:project-id request)
      (option-value (vec cli-args) "--project")))

(defn- operation-storage-path
  [request]
  (or (:storagePath request)
      (:storage-path request)))

(defn- benchmark-lock-key
  [cli-args]
  (let [args (vec cli-args)
        command (first args)
        subcommand (second args)
        suite-path (nth args 2 nil)
        out-path (option-value args "--out")
        case-id (option-value args "--case")
        cases (option-value args "--cases")]
    (when (and (= "bench" command)
               (#{"agent-baseline" "agent-run" "agent-check"} subcommand))
      (str "bench:"
           subcommand
           ":"
           (or out-path suite-path "default")
           (when case-id
             (str ":case:" case-id))
           (when cases
             (str ":cases:" cases))))))

(defn- request-operation
  ([request cli-args]
   (request-operation request cli-args nil))
  ([request cli-args extra]
   (let [cli-args (vec cli-args)
         project-id (operation-project-id request cli-args)
         storage-path (operation-storage-path request)
         lock-key (or (:lockKey extra)
                      (:lock-key extra)
                      (benchmark-lock-key cli-args))]
     (cond-> {:schema "ygg.server.active-operation/v1"
              :op (or (:op request) (first cli-args))
              :args cli-args}
       (:cwd request) (assoc :cwd (:cwd request))
       project-id (assoc :projectId project-id)
       storage-path (assoc :storagePath storage-path)
       lock-key (assoc :lockKey lock-key)
       extra (merge extra)))))

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
  #{"--config" "--out" "--result" "--queue-dir" "--fixture"
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
    (:project-id
     (if cwd
       (with-user-dir cwd
         #(registry/resolve-project {:config-path config-path
                                     :cwd cwd}))
       (registry/resolve-project {:config-path config-path
                                  :cwd (System/getProperty "user.dir")})))
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
  [{:keys [config-path project-id repo-id check? enqueue? query-index? dry-run?
           json? no-progress? min-confidence]}]
  (let [args (cond
               config-path [config-path]
               project-id ["--project" project-id]
               :else [])]
    (-> args
        (option-flag "--repo" repo-id)
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
     :check? (boolean (some #{"--check"} args))
     :enqueue? (boolean (some #{"--enqueue"} args))
     :query-index? (boolean (some #{"--query-index"} args))
     :dry-run? (dry-run? args)
     :json? (json-output? args)
     :no-progress? (boolean (some #{"--no-progress"} args))
     :min-confidence (option-value args "--min-confidence")}))

(defn- normalize-sync-request-options
  [request opts]
  (cond-> opts
    (:config-path opts)
    (update :config-path #(absolute-path (:cwd request) %))))

(defn- sync-project
  [{:keys [project config-path project-id cwd]}]
  (cond
    project
    project

    :else
    (:project (registry/resolve-project {:project-id project-id
                                         :config-path config-path
                                         :cwd cwd}))))

(defn- run-index-maintenance-worker
  [project _opts enqueued]
  (when (and (seq enqueued)
             (get-in project [:maintenance :worker]))
    (index-maintenance-worker/run! project {})))

(defn- attach-index-maintenance-worker
  [project opts result]
  (if-let [worker-run (run-index-maintenance-worker project opts (:enqueued result))]
    (assoc result :maintenance-worker worker-run)
    result))

(defn- scheduled-worker-run?
  [project result]
  (and (seq (:enqueued result))
       (get-in project [:maintenance :worker])))

(defn- repo-index-change-count
  [repo]
  (when-let [stats (:stats repo)]
    (+ (long (or (:files-indexed stats) 0))
       (long (or (:files-deleted stats) 0)))))

(defn- index-summary-unchanged?
  [index-summary]
  (let [counts (mapv repo-index-change-count (:repos index-summary))]
    (and (seq counts)
         (every? some? counts)
         (every? zero? counts))))

(defn- skipped-system-summary
  [project-id]
  {:project-id project-id
   :status :skipped
   :reason "no-index-changes"})

(defn run-sync!
  "Run a regular project sync inside the server JVM and return the result map.

   Callers may pass structured options instead of invoking CLI dispatch. This is
   intended for both server protocol requests and future in-process schedule or
   system-event handlers."
  [xtdb {:keys [repo-id check? enqueue? dry-run?] :as opts}]
  (let [project (sync-project opts)
        args (sync-options->args opts)
        sync-deps (cli-sync-deps)
        run-index! (fn [index-xtdb]
                     (cli-sync/sync-index-project!
                      index-xtdb
                      project
                      args
                      sync-deps
                      opts))
        check? (or check? enqueue?)]
    (if dry-run?
      (let [index-summary (run-index! nil)]
        {:schema "ygg.sync/v1"
         :project-id (:id project)
         :repo-id repo-id
         :index-summary index-summary})
      (let [index-summary (run-index! xtdb)
            system-summary (if (index-summary-unchanged? index-summary)
                             (skipped-system-summary (:id project))
                             (project/infer-project! xtdb project))
            report (when check?
                     (cli-sync/maintenance-report
                      xtdb
                      project
                      args
                      sync-deps))
            enqueued (when (and report enqueue?)
                       (cli-sync/enqueue-sync-work!
                        args
                        report
                        sync-deps))
            worker-run (when (not= false (:run-worker? opts))
                         (run-index-maintenance-worker project opts enqueued))]
        (cond-> {:schema "ygg.sync/v1"
                 :project-id (:id project)
                 :repo-id repo-id
                 :index-summary index-summary
                 :system-summary system-summary}
          report (assoc :check-report report)
          enqueued (assoc :enqueued enqueued
                          :enqueue-summary (cli-sync/enqueue-summary enqueued))
          worker-run (assoc :maintenance-worker worker-run))))))

(defn- print-sync-result!
  [args result]
  (if (json-output? args)
    (println (json/write-json-str result))
    (cli-project/print-sync-summary result)))

(def scheduler-poll-ms
  5000)

(def ^:private scheduler-busy
  ::scheduler-busy)

(def ^:private global-operation-lock-key
  "global")

(def ^:private unlocked-cli-commands
  #{"current"
    "query"
    "help"})

(def ^:private unlocked-projects-subcommands
  #{"list"
    "show"})

(def ^:private unlocked-maintenance-subcommands
  #{"status"})

(def ^:private unlocked-sync-work-subcommands
  #{"auto"
    "complete"
    "heartbeat"
    "list"
    "pull"
    "reject"
    "release"
    "release-expired"
    "show"
    "validate"})

(defn- unlocked-cli-operation?
  [cli-args]
  (let [args (vec cli-args)
        command (first args)
        subcommand (second args)
        nested-subcommand (nth args 2 nil)]
    (or (contains? unlocked-cli-commands command)
        (and (= "projects" command)
             (or (nil? subcommand)
                 (contains? unlocked-projects-subcommands subcommand)))
        (and (= "maintenance" command)
             (or (nil? subcommand)
                 (contains? unlocked-maintenance-subcommands subcommand)))
        (and (= "bench" command)
             (= "repos" subcommand)
             (= "check" nested-subcommand))
        (and (= "sync" command)
             (or (#{"activity" "inspect"} subcommand)
                 (and (= "work" subcommand)
                      (contains? unlocked-sync-work-subcommands nested-subcommand)))))))

(defn- operation-lock-key
  [operation]
  (if-let [lock-key (:lockKey operation)]
    lock-key
    (if-let [project-id (:projectId operation)]
      (str "project:" project-id)
      global-operation-lock-key)))

(defn- global-operation-lock?
  [lock-key]
  (= global-operation-lock-key lock-key))

(defn- operation-lock
  [ctx lock-key]
  (when-let [locks (:operation-locks ctx)]
    (locking locks
      (or (get @locks lock-key)
          (let [lock (ReentrantLock.)]
            (swap! locks assoc lock-key lock)
            lock)))))

(defn- active-operation-status
  [operation]
  (assoc operation
         :elapsedMs (- (System/currentTimeMillis)
                       (long (:startedAtMs operation)))))

(defn- active-operation-statuses
  [ctx]
  (if-let [active-operations (:active-operations ctx)]
    (->> @active-operations
         (mapv (fn [[lock-key operation]]
                 (active-operation-status (assoc operation :lockKey lock-key))))
         (sort-by :lockKey)
         vec)
    []))

(defn- active-operation-for-lock
  [ctx lock-key]
  (when-let [active-operations (:active-operations ctx)]
    (when-let [operation (get @active-operations lock-key)]
      (active-operation-status (assoc operation :lockKey lock-key)))))

(defn- first-active-operation
  [ctx]
  (first (active-operation-statuses ctx)))

(defn- operation-lock-status
  [ctx]
  (let [locks (vals (or (some-> ctx :operation-locks deref) {}))
        active-operations (active-operation-statuses ctx)]
    {:busy (boolean (or (seq active-operations)
                        (some (fn [^ReentrantLock lock] (.isLocked lock)) locks)))
     :activeOperation (first active-operations)
     :activeOperations active-operations
     :queuedRequests (reduce + 0 (map (fn [^ReentrantLock lock]
                                        (.getQueueLength lock))
                                      locks))}))

(def ^:private indexing-operation-ops
  #{"embed"
    "init"
    "sync"})

(defn- indexing-operation?
  [{:keys [op task]}]
  (or (contains? indexing-operation-ops (str op))
      (and (= "maintenance-schedule" (str op))
           (= "sync" (str task)))))

(defn- active-indexing-operation-for-query
  [ctx cli-args]
  (let [project-id (operation-project-id {} cli-args)
        project-lock-key (when-not (str/blank? (str project-id))
                           (str "project:" project-id))]
    (->> [project-lock-key global-operation-lock-key]
         (keep #(when % (active-operation-for-lock ctx %)))
         (filter indexing-operation?)
         first)))

(defn- start-active-operation!
  [ctx lock-key operation]
  (when-let [active-operations (:active-operations ctx)]
    (let [operation (assoc (or operation
                               {:schema "ygg.server.active-operation/v1"})
                           :lockKey lock-key
                           :startedAtMs (System/currentTimeMillis))]
      (swap! active-operations assoc lock-key operation)
      operation)))

(defn- clear-active-operation!
  [ctx lock-key operation]
  (when-let [active-operations (:active-operations ctx)]
    (swap! active-operations
           (fn [operations]
             (if (= operation (get operations lock-key))
               (dissoc operations lock-key)
               operations)))))

(defn- with-active-operation
  [ctx operation-key operation f]
  (let [active-operation (start-active-operation! ctx operation-key operation)]
    (try
      (f)
      (finally
        (clear-active-operation! ctx operation-key active-operation)))))

(defn- locked-operation
  ([ctx on-busy f]
   (locked-operation ctx nil on-busy f))
  ([ctx operation on-busy f]
   (let [lock-key (operation-lock-key operation)]
     (letfn [(run-locked []
               (let [active-operation (start-active-operation! ctx lock-key operation)]
                 (try
                   (f)
                   (finally
                     (clear-active-operation! ctx lock-key active-operation)))))]
       (if-let [^ReentrantLock lock (operation-lock ctx lock-key)]
         (if (global-operation-lock? lock-key)
           (if (.tryLock lock)
             (try
               (if-let [active-operation (first-active-operation ctx)]
                 (on-busy (:lockKey active-operation))
                 (run-locked))
               (finally
                 (.unlock lock)))
             (on-busy lock-key))
           (let [^ReentrantLock global-lock (operation-lock ctx global-operation-lock-key)]
             (if (and global-lock (.isLocked global-lock))
               (on-busy global-operation-lock-key)
               (if (.tryLock lock)
                 (try
                   (if (and global-lock (.isLocked global-lock))
                     (on-busy global-operation-lock-key)
                     (run-locked))
                   (finally
                     (.unlock lock)))
                 (on-busy lock-key)))))
         (locking ctx
           (run-locked)))))))

(defn- operation-lock-busy!
  [ctx lock-key]
  (let [active-operation (active-operation-for-lock ctx lock-key)]
    (throw (ex-info "Yggdrasil server is busy running another operation."
                    (cond-> {:exit daemon-contract/unavailable-exit
                             :reason "operation-lock-busy"
                             :lockKey lock-key}
                      active-operation
                      (assoc :activeOperation active-operation))))))

(defn- with-operation-lock
  ([ctx f]
   (with-operation-lock ctx nil f))
  ([ctx operation f]
   (locked-operation ctx operation #(operation-lock-busy! ctx %) f)))

(defn- try-operation-lock
  ([ctx f]
   (try-operation-lock ctx nil f))
  ([ctx operation f]
   (locked-operation ctx operation (constantly scheduler-busy) f)))

(defn- with-cli-operation
  ([ctx cli-args f]
   (with-cli-operation ctx cli-args nil f))
  ([ctx cli-args operation f]
   (if (unlocked-cli-operation? cli-args)
     (f)
     (with-operation-lock ctx operation f))))

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
   :index-profile (if (:query-index schedule) :query :graph)
   :json? true
   :no-progress? true})

(defn- run-maintenance-schedule!
  [ctx project schedule]
  (let [opts (schedule-sync-options project schedule)
        result (try-operation-lock
                ctx
                {:schema "ygg.server.active-operation/v1"
                 :op "maintenance-schedule"
                 :projectId (:id project)
                 :scheduleId (:id schedule)
                 :task (some-> (:task schedule) name)}
                (fn []
                  (let [xtdb (node-for! ctx (store/storage-path (:id project)))]
                    (case (:task schedule)
                      :sync (run-sync! xtdb (assoc opts :run-worker? false))))))]
    (if (= scheduler-busy result)
      result
      (if (scheduled-worker-run? project result)
        (with-active-operation
          ctx
          (str "maintenance-worker:" (:id project) ":" (:id schedule))
          {:schema "ygg.server.active-operation/v1"
           :op "maintenance-worker"
           :projectId (:id project)
           :scheduleId (:id schedule)
           :task (some-> (:task schedule) name)}
          #(attach-index-maintenance-worker project opts result))
        result))))

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

(defn- value-name
  [v]
  (cond
    (keyword? v) (name v)
    (some? v) (str v)))

(defn- repo-freshness-summary
  [{:keys [repo-id status git-sha git-state]}]
  (let [git-state (or git-state {})
        local-sha (or git-sha (:git-sha git-state))]
    (cond-> {:repoId repo-id}
      status (assoc :indexStatus (value-name status))
      local-sha (assoc :localSha local-sha)
      (:git-branch git-state) (assoc :branch (:git-branch git-state))
      (:git-upstream git-state) (assoc :upstream (:git-upstream git-state))
      (:git-upstream-status git-state) (assoc :upstreamStatus
                                              (value-name (:git-upstream-status
                                                           git-state)))
      (contains? git-state :git-upstream-current?) (assoc :upstreamCurrent
                                                          (boolean
                                                           (:git-upstream-current?
                                                            git-state)))
      (contains? git-state :git-ahead) (assoc :upstreamAhead (:git-ahead git-state))
      (contains? git-state :git-behind) (assoc :upstreamBehind (:git-behind git-state))
      (contains? git-state :git-dirty?) (assoc :dirty (boolean (:git-dirty?
                                                                git-state)))
      (:git-main-ref git-state) (assoc :remoteMainRef (:git-main-ref git-state))
      (:git-main-sha git-state) (assoc :remoteMainSha (:git-main-sha git-state))
      (:git-main-status git-state) (assoc :mainStatus
                                          (value-name (:git-main-status git-state)))
      (contains? git-state :git-main-current?) (assoc :mainCurrent
                                                      (boolean (:git-main-current?
                                                                git-state)))
      (contains? git-state :git-main-ahead) (assoc :mainAhead (:git-main-ahead
                                                               git-state))
      (contains? git-state :git-main-behind) (assoc :mainBehind (:git-main-behind
                                                                 git-state))
      (some? (:git-stale-from-main? git-state)) (assoc :staleFromMain
                                                       (:git-stale-from-main?
                                                        git-state)))))

(defn- repo-freshness-summaries
  [result]
  (mapv repo-freshness-summary (get-in result [:index-summary :repos])))

(defn- repo-freshness-fragments
  [{:keys [branch upstream upstreamStatus localSha dirty remoteMainRef remoteMainSha
           mainStatus staleFromMain mainAhead mainBehind]}]
  (remove nil?
          [(when branch (str "branch=" branch))
           (when upstream (str "upstream=" upstream))
           (when upstreamStatus (str "upstream-status=" upstreamStatus))
           (when localSha (str "local-sha=" localSha))
           (when (some? dirty) (str "dirty=" (boolean dirty)))
           (when remoteMainRef (str "remote-main=" remoteMainRef))
           (when remoteMainSha (str "remote-main-sha=" remoteMainSha))
           (when mainStatus (str "main-status=" mainStatus))
           (when (some? staleFromMain) (str "stale-from-main="
                                            (boolean staleFromMain)))
           (when (some? mainAhead) (str "main-ahead=" mainAhead))
           (when (some? mainBehind) (str "main-behind=" mainBehind))]))

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
                      (let [result (run-maintenance-schedule! ctx project schedule)
                            repo-freshness (repo-freshness-summaries result)]
                        (record-schedule-run!
                         state
                         (if (= scheduler-busy result)
                           {:status "skipped-busy"
                            :ranAtMs now-ms
                            :projectId (:id project)
                            :scheduleId (:id schedule)
                            :task (:task schedule)
                            :reason "operator-command-running"}
                           (cond-> {:status "completed"
                                    :ranAtMs now-ms
                                    :projectId (:id project)
                                    :scheduleId (:id schedule)
                                    :task (:task schedule)
                                    :resultSchema (:schema result)}
                             (seq repo-freshness)
                             (assoc :repoFreshness repo-freshness)
                             (:maintenance-worker result)
                             (assoc :workerStatus (get-in result [:maintenance-worker :status])
                                    :workerCounts (get-in result [:maintenance-worker :counts]))))))
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

(defn- semantic-client-key-summary
  [[provider model]]
  {:provider (name (keyword provider))
   :model (str model)})

(defn- semantic-client-summaries
  [semantic-client-pool]
  (->> (keys @(or semantic-client-pool (atom {})))
       (map semantic-client-key-summary)
       (sort-by (juxt :provider :model))
       vec))

(defn- server-status
  [ctx]
  (let [open-node-paths (if-let [node-pool (:node-pool ctx)]
                          (sort (keys @node-pool))
                          [])
        semantic-client-keys (semantic-client-summaries (:semantic-client-pool ctx))
        maintenance-projects (mapv maintenance-project-status (registered-projects))
        enabled-projects (filterv :enabled maintenance-projects)
        scheduled-count (reduce + (map #(count (:schedules %)) maintenance-projects))
        scheduler-state @(or (:scheduler-state ctx) (atom {}))
        lock-status (operation-lock-status ctx)]
    {:schema "ygg.server.status/v1"
     :status "running"
     :host (:host ctx)
     :port (:port ctx)
     :pid (pid)
     :storagePath (:storage-path ctx)
     :startedAtMs (:started-at-ms ctx)
     :busy (:busy lock-status)
     :activeOperation (:activeOperation lock-status)
     :activeOperations (:activeOperations lock-status)
     :queuedRequests (long (:queuedRequests lock-status))
     :openNodes (count open-node-paths)
     :openNodePaths (vec open-node-paths)
     :semanticClients (count semantic-client-keys)
     :semanticClientKeys semantic-client-keys
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
      (doseq [operation (:activeOperations status)]
        (println "- active-operation"
                 (str/join " "
                           (cond-> [(:op operation)
                                    (str "elapsed=" (:elapsedMs operation) "ms")]
                             (:lockKey operation)
                             (conj (str "lock=" (:lockKey operation)))
                             (:projectId operation)
                             (conj (str "project=" (:projectId operation)))
                             (:scheduleId operation)
                             (conj (str "schedule=" (:scheduleId operation)))
                             (:task operation)
                             (conj (str "task=" (:task operation)))
                             (:toolName operation)
                             (conj (str "tool=" (:toolName operation)))))))
      (println "- queued-requests" (:queuedRequests status 0))
      (println "- open-nodes" (:openNodes status))
      (println "- semantic-clients" (:semanticClients status 0))
      (doseq [{:keys [provider model]} (:semanticClientKeys status)]
        (println "  -" (str provider ":" model)))
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
      (doseq [{:keys [project-id enabled schedules worker work error]} (get-in status
                                                                               [:maintenance
                                                                                :projects])]
        (apply println
               (cond-> ["-"
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
                             (:executorCount worker))]
                 (:configured worker)
                 (conj (str "max-items=" (:maxItemsPerRun worker))
                       (str "max-failures=" (:maxFailuresPerRun worker)))))
        (when work
          (println "  work"
                   (str "decisions=" (:max-decisions work))
                   (str "per-kind=" (:max-decisions-per-kind work))
                   (str "infra=" (:max-infra-reviews work))
                   (str "dependency=" (:max-dependency-reviews work))
                   (str "decision-batch=" (:decision-batch-size work))
                   (str "review-batch=" (:review-batch-size work))))
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
        (println "- recent-runs" (count runs))
        (doseq [run (take-last 5 runs)
                repo (:repoFreshness run)]
          (apply println
                 "  repo-freshness"
                 (:projectId run)
                 (:scheduleId run)
                 (:repoId repo)
                 (repo-freshness-fragments repo)))))))

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
        opts (normalize-sync-request-options
              request
              (merge (sync-args->options args)
                     {:cwd (:cwd request)}
                     (:options request)
                     (when progress-fn
                       {:progress-fn progress-fn})))
        resolved-project-id (project-id-from-cli request args opts)]
    (capture-response
     (fn []
       (with-operation-lock
         ctx
         (request-operation request (into ["sync"] args)
                            (when-let [project-id resolved-project-id]
                              {:projectId project-id}))
         (fn []
           (with-user-dir (:cwd request)
             (fn []
               (let [xtdb (node-for! ctx (request-storage-path request args opts))
                     result (run-sync! xtdb opts)]
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
     #(with-cli-operation ctx cli-args (request-operation request cli-args) %)
     #(run-command-handler! command args))))

(defn- cli-query-response
  [ctx request command handler]
  (let [args (absolutize-path-options (:cwd request) (vec (:args request)))
        cli-args (into [command] args)
        active-indexing (active-indexing-operation-for-query ctx cli-args)
        query-deps (cond-> (cli/query-deps)
                     active-indexing
                     (assoc :active-indexing active-indexing)

                     active-indexing
                     (update :context-packet-options
                             (fn [context-packet-options]
                               (fn [xtdb args opts]
                                 (context-packet-options
                                  xtdb
                                  args
                                  (assoc opts :active-indexing active-indexing))))))]
    (captured-request-storage-response
     ctx
     request
     cli-args
     #(with-cli-operation ctx cli-args (request-operation request cli-args) %)
     #(with-bindings {#'cli-query/*deps* query-deps}
        (handler args)))))

(defn- sync-subcommand-response
  [ctx request subcommand args]
  (command-response ctx request "sync" (into [subcommand] args)))

(defn- sync-response-dispatch
  [ctx request]
  (let [[subcommand & args] (vec (:args request))]
    (if (contains? sync-subcommands subcommand)
      (sync-subcommand-response ctx request subcommand args)
      (sync-response ctx request))))

(defn- server-print-json!
  [value]
  (println (json/write-json-str value {:indent-str "  "})))

(defn- server-query-index?
  [args]
  (boolean (some #{"--query-index"} args)))

(defn- init-sync-dispatch!
  [ctx request command args]
  (if (= "sync" command)
    (let [args (vec args)
          progress-fn (sync-server-progress-fn ctx args)
          opts (merge (sync-args->options args)
                      {:cwd (:cwd request)}
                      (when progress-fn
                        {:progress-fn progress-fn}))
          xtdb (node-for! ctx (request-storage-path request args opts))
          result (run-sync! xtdb opts)]
      (print-sync-result! args result))
    (run-command-handler! command args)))

(defn- init-response
  [ctx request]
  (let [args (absolutize-path-options (:cwd request) (vec (:args request)))
        cli-args (into ["init"] args)]
    (capture-response
     (fn []
       (with-user-dir (:cwd request)
         (fn []
           (with-cli-operation
             ctx
             cli-args
             (request-operation request cli-args)
             #(cli-start/init!
               args
               {:print-json server-print-json!
                :dispatch (partial init-sync-dispatch! ctx request)
                :query-index? server-query-index?}))))))))

(defn- stop-response
  [{:keys [running server]}]
  (log-lifecycle! "stop-request" {:pid (pid)})
  (reset! running false)
  (future
    (Thread/sleep 25)
    (when (instance? Closeable server)
      (.close ^Closeable server)))
  {:ok true
   :exit 0
   :out "stopping\n"
   :err ""})

(defn- mcp-tool-call?
  [message]
  (= "tools/call" (:method message)))

(defn- mcp-tool-name
  [message]
  (get-in message [:params :name]))

(defn- read-only-mcp-tool-call?
  [message]
  (mcp/read-only-tool? (mcp-tool-name message)))

(defn- without-operation-lock
  [f]
  (f))

(defn- mcp-tool-lock
  [ctx request message]
  (if (read-only-mcp-tool-call? message)
    without-operation-lock
    #(with-operation-lock
       ctx
       (request-operation request ["mcp"] {:toolName (mcp-tool-name message)})
       %)))

(defn- handle-mcp-message
  [args message]
  (mcp/handle-message
   (mcp/server-context args)
   message))

(defn- mcp-response
  [ctx request]
  (let [args (vec (:args request))
        message (:message request)]
    (if (mcp-tool-call? message)
      (captured-request-storage-response
       ctx
       request
       args
       (mcp-tool-lock ctx request message)
       #(handle-mcp-message args message))
      (capture-response
       #(with-user-dir (:cwd request)
          (fn []
            (handle-mcp-message args message)))))))

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

      (= "init" op)
      (init-response ctx request)

      (contains? cli-query-command-handlers op)
      (cli-query-response ctx request op (get cli-query-command-handlers op))

      (contains? cli-command-dispatch op)
      (command-response ctx request (get cli-command-dispatch op) (:args request))

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
  (binding [embedding-client/*client-pool* (:semantic-client-pool ctx)]
    (if-not (authorized? ctx request)
      {:ok false
       :exit 1
       :out ""
       :err "Unauthorized server request.\n"}
      (if (contains? logged-ops (:op request))
        (handle-logged-request ctx request)
        (handle-authorized-request ctx request)))))

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
   :ygg.server/semantic-client-pool {}
   :ygg.server/server {:node-pool (ig/ref :ygg.server/node-pool)
                       :semantic-client-pool (ig/ref :ygg.server/semantic-client-pool)
                       :storage-path (absolute-path nil (store/storage-path))}})

(defmethod ig/init-key :ygg.server/node-pool
  [_ _]
  (atom {}))

(defmethod ig/halt-key! :ygg.server/node-pool
  [_ node-pool]
  (close-node-pool! node-pool))

(defmethod ig/init-key :ygg.server/semantic-client-pool
  [_ _]
  (atom {}))

(defmethod ig/halt-key! :ygg.server/semantic-client-pool
  [_ semantic-client-pool]
  (embedding-client/close-client-pool! semantic-client-pool))

(defmethod ig/init-key :ygg.server/server
  [_ {:keys [node-pool semantic-client-pool storage-path]}]
  (let [{:keys [host port]} (server-endpoint)
        token (server-token)
        started-at-ms (System/currentTimeMillis)
        server (ServerSocket. port 50 (InetAddress/getByName host))
        running (atom true)
        request-executor (request-executor)
        request-counter (AtomicLong.)
        operation-locks (atom {})
        active-operations (atom {})
        scheduler-state (atom {:last-run-ms {}
                               :runs []})
        stopped (promise)
        ctx {:token token
             :running running
             :server server
             :request-executor request-executor
             :request-counter request-counter
             :operation-locks operation-locks
             :active-operations active-operations
             :node-pool node-pool
             :semantic-client-pool semantic-client-pool
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
                      (log-lifecycle! "accept-thread-stopped"
                                      {:pid (pid)
                                       :running @running
                                       :serverClosed (.isClosed server)})
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
     :operation-locks operation-locks
     :active-operations active-operations
     :scheduler-thread scheduler-thread
     :scheduler-state scheduler-state
     :semantic-client-pool semantic-client-pool
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
            (prn data))))
      (shutdown-agents)
      (System/exit 1))))
