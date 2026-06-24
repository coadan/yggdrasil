(ns ygg.daemon
  "Local warm daemon for latency-sensitive Yggdrasil commands."
  (:require [ygg.cli :as cli]
            [ygg.cli-options :refer [dry-run?
                                     json-output?
                                     option-value
                                     positional-args]]
            [ygg.cli-project :as cli-project]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync :as cli-sync]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io BufferedReader Closeable InputStreamReader OutputStreamWriter
            PrintWriter StringWriter]
           [java.lang ProcessHandle]
           [java.net InetAddress InetSocketAddress ServerSocket Socket]
           [java.util UUID]
           [java.util.logging LogManager]))

(def schema
  "ygg.daemon/v1")

(def unavailable-exit
  75)

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- daemon-dir
  []
  (or (System/getenv "YGG_DAEMON_DIR") ".dev/ygg"))

(defn descriptor-path
  []
  (or (System/getenv "YGG_DAEMON_JSON")
      (str (daemon-dir) "/daemon.json")))

(defn- descriptor-edn-path
  []
  (or (System/getenv "YGG_DAEMON_EDN")
      (str (daemon-dir) "/daemon.edn")))

(defn- pid
  []
  (-> (ProcessHandle/current) .pid))

(defn- write-descriptor!
  [descriptor]
  (doseq [path [(descriptor-path) (descriptor-edn-path)]]
    (let [file (io/file path)]
      (.mkdirs (.getParentFile file))
      (spit file
            (if (.endsWith path ".json")
              (str (json/write-json-str descriptor {:indent-str "  "}) "\n")
              (with-out-str (pprint/pprint descriptor)))))))

(defn- delete-descriptors!
  []
  (doseq [path [(descriptor-path) (descriptor-edn-path)]]
    (let [file (io/file path)]
      (when (.isFile file)
        (.delete file)))))

(defn- descriptor
  [server token storage-path]
  {:schema schema
   :host "127.0.0.1"
   :port (.getLocalPort ^ServerSocket server)
   :token token
   :pid (pid)
   :storagePath storage-path
   :startedAtMs (System/currentTimeMillis)})

(defn- read-json-line
  [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (json/read-json line :key-fn keyword)))

(defn- write-json-line!
  [^PrintWriter writer value]
  (.println writer (json/write-json-str value))
  (.flush writer))

(defn- error-response
  [^Exception e]
  {:ok false
   :exit 1
   :out ""
   :err (str (or (ex-message e) (.getMessage e)) "\n")
   :data (ex-data e)})

(defn- authorized?
  [{:keys [token]} request]
  (= token (:token request)))

(defn- same-path?
  [left right]
  (and left
       right
       (= (.getAbsolutePath (io/file left))
          (.getAbsolutePath (io/file right)))))

(defn- absolute-path
  [cwd path]
  (let [file (io/file path)]
    (.getAbsolutePath
     (if (.isAbsolute file)
       file
       (io/file (or cwd (System/getProperty "user.dir")) path)))))

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
  "Convert daemon sync options into the normal sync option vector.

   This keeps scheduled/system-event sync calls on the same option semantics as
   the CLI without requiring those callers to shell out."
  [{:keys [config-path project-id repo-id map-path check? enqueue? query-index? dry-run?
           json? no-progress? min-confidence]}]
  (cond-> (cond
            config-path [config-path]
            project-id ["--project" project-id]
            :else [])
    repo-id (option-flag "--repo" repo-id)
    map-path (option-flag "--map" map-path)
    min-confidence (option-flag "--min-confidence" min-confidence)
    check? (present-flag "--check" true)
    enqueue? (present-flag "--enqueue" true)
    query-index? (present-flag "--query-index" true)
    dry-run? (present-flag "--dry-run" true)
    json? (present-flag "--json" true)
    no-progress? (present-flag "--no-progress" true)))

(defn- sync-args->options
  [args]
  (let [positional (positional-args args)]
    {:config-path (first positional)
     :project-id (option-value args "--project")
     :repo-id (option-value args "--repo")
     :map-path (option-value args "--map")
     :check? (boolean (some #{"--check"} args))
     :enqueue? (boolean (some #{"--enqueue"} args))
     :query-index? (boolean (some #{"--query-index"} args))
     :dry-run? (dry-run? args)
     :json? (json-output? args)
     :no-progress? (boolean (some #{"--no-progress"} args))
     :min-confidence (option-value args "--min-confidence")}))

(defn- sync-project
  [{:keys [config-path project-id cwd]}]
  (if config-path
    (project/read-project config-path)
    (:project (registry/resolve-project {:project-id project-id
                                         :cwd cwd}))))

(defn run-sync!
  "Run a regular project sync inside the daemon JVM and return the result map.

   Callers may pass structured options instead of invoking CLI dispatch. This is
   intended for both daemon protocol requests and future in-process schedule or
   system-event handlers."
  [xtdb {:keys [repo-id check? enqueue? dry-run?] :as opts}]
  (let [args (sync-options->args opts)
        project (sync-project opts)
        check? (or check? enqueue?)]
    (if dry-run?
      (let [index-summary (cli-sync/sync-index-project! nil project args (cli/sync-deps))]
        {:schema "ygg.sync/v1"
         :project-id (:id project)
         :repo-id repo-id
         :index-summary index-summary})
      (let [index-summary (cli-sync/sync-index-project! xtdb project args (cli/sync-deps))
            system-summary (project/infer-project! xtdb project)
            report (when check?
                     (cli-sync/maintenance-report xtdb project args (cli/sync-deps)))
            enqueued (when (and report enqueue?)
                       (cli-sync/enqueue-sync-work! args report (cli/sync-deps)))]
        (cond-> {:schema "ygg.sync/v1"
                 :project-id (:id project)
                 :repo-id repo-id
                 :index-summary index-summary
                 :system-summary system-summary}
          report (assoc :check-report report)
          enqueued (assoc :enqueued enqueued))))))

(defn run-sync-check!
  "Run maintenance checks inside the daemon JVM and return the result map."
  [xtdb {:keys [enqueue?] :as opts}]
  (let [args (sync-options->args opts)
        project (sync-project opts)
        report (cli-sync/maintenance-report xtdb project args (cli/sync-deps))
        enqueued (when enqueue?
                   (cli-sync/enqueue-sync-work! args report (cli/sync-deps)))]
    (cond-> {:schema "ygg.sync.check/v1"
             :report report}
      enqueued (assoc :enqueued enqueued))))

(defn- print-sync-result!
  [args result]
  (if (json-output? args)
    (println (json/write-json-str result))
    (cli-project/print-sync-summary result)))

(defn- print-sync-check-result!
  [args result]
  (if (json-output? args)
    (println (json/write-json-str result))
    (do
      (cli-sync/print-maintenance-report (:report result))
      (when-let [enqueued (:enqueued result)]
        (println)
        (println "## Enqueued")
        (doseq [item enqueued]
          (println "-" (:id item) (:kind item) (:status item)))))))

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
        opts (merge (sync-args->options args)
                    {:cwd (:cwd request)}
                    (:options request))
        xtdb (node-for! ctx (request-storage-path request args opts))]
    (capture-response
     (fn []
       (with-user-dir (:cwd request)
         (fn []
           (let [result (run-sync! xtdb opts)]
             (print-sync-result! args result)
             result)))))))

(defn- sync-check-response
  [ctx request]
  (let [args (vec (:args request))
        opts (merge (sync-args->options args)
                    {:cwd (:cwd request)}
                    (:options request))
        xtdb (node-for! ctx (request-storage-path request args opts))]
    (capture-response
     (fn []
       (with-user-dir (:cwd request)
         (fn []
           (let [result (run-sync-check! xtdb opts)]
             (print-sync-check-result! args result)
             result)))))))

(defn- cli-response
  [ctx request cli-args]
  (let [request-project-id (project-id-from-cli request cli-args nil)
        warm-path (request-storage-path request cli-args nil)
        original-storage-path store/storage-path
        original-with-node store/with-node]
    (capture-response
     (fn []
       (with-user-dir (:cwd request)
         (fn []
           (with-redefs [store/storage-path (fn
                                              ([] warm-path)
                                              ([project-id]
                                               (if (or (nil? project-id)
                                                       (= (str project-id)
                                                          (str request-project-id)))
                                                 warm-path
                                                 (original-storage-path project-id))))
                         store/with-node (fn [path f]
                                           (let [requested-path (or path warm-path)]
                                             (if (same-path? warm-path requested-path)
                                               (f (node-for! ctx warm-path))
                                               (if (:node-pool ctx)
                                                 (f (node-for! ctx requested-path))
                                                 (original-with-node path f)))))]
             (if-let [command (first cli-args)]
               (cli/dispatch command (vec (rest cli-args)))
               (println (cli/usage))))))))))

(defn handle-request
  "Handle one decoded daemon protocol request."
  [{:keys [running server] :as ctx} request]
  (if-not (authorized? ctx request)
    {:ok false
     :exit 1
     :out ""
     :err "Unauthorized daemon request.\n"}
    (case (:op request)
      "ping"
      {:ok true
       :exit 0
       :out "ok\n"
       :err ""}

      "sync-inspect"
      (cli-response ctx request (into ["sync" "inspect"] (:args request)))

      "sync"
      (sync-response ctx request)

      "sync-check"
      (sync-check-response ctx request)

      "query"
      (binding [cli-query/*deps* (cli/query-deps)]
        (cli-response ctx request (into ["query"] (:args request))))

      "cli"
      (cli-response ctx request (:args request))

      "stop"
      (do
        (reset! running false)
        (future
          (Thread/sleep 25)
          (when (instance? Closeable server)
            (.close ^Closeable server)))
        {:ok true
         :exit 0
         :out "stopping\n"
         :err ""})

      {:ok false
       :exit 1
       :out ""
       :err (str "Unknown daemon op: " (:op request) "\n")})))

(defn- handle-socket!
  [ctx ^Socket socket]
  (with-open [socket socket
              reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
              writer (PrintWriter. (OutputStreamWriter. (.getOutputStream socket)))]
    (write-json-line! writer (handle-request ctx (read-json-line reader)))))

(defn- serve-requests!
  [server token node-pool]
  (let [running (atom true)
        ctx {:token token
             :running running
             :server server
             :node-pool node-pool}]
    (while @running
      (try
        (handle-socket! ctx (.accept ^ServerSocket server))
        (catch java.net.SocketException e
          (when @running
            (throw e)))))))

(defn serve!
  []
  (let [token (str (UUID/randomUUID))
        node-pool (atom {})
        default-storage-path (absolute-path nil (store/storage-path))]
    (with-open [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (try
        (let [descriptor (descriptor server token default-storage-path)]
          (write-descriptor! descriptor)
          (binding [*out* *err*]
            (println (str "ygg daemon listening on "
                          (:host descriptor)
                          ":"
                          (:port descriptor))))
          (serve-requests! server token node-pool))
        (finally
          (delete-descriptors!)
          (close-node-pool! node-pool))))))

(defn- read-descriptor
  []
  (let [file (io/file (descriptor-path))]
    (when (.isFile file)
      (json/read-json (slurp file) :key-fn keyword))))

(defn request
  [op args]
  (if-let [{:keys [host port token]} (read-descriptor)]
    (try
      (let [socket (Socket.)]
        (.connect socket (InetSocketAddress. (or host "127.0.0.1") (int port)) 250)
        (with-open [socket socket
                    reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
                    writer (PrintWriter. (OutputStreamWriter. (.getOutputStream socket)))]
          (write-json-line! writer {:op op
                                    :args (vec args)
                                    :token token
                                    :cwd (System/getProperty "user.dir")
                                    :projectId (System/getenv "YGG_PROJECT_ID")
                                    :storagePath (System/getenv "YGG_XTDB_PATH")})
          (read-json-line reader)))
      (catch Exception _
        {:ok false
         :exit unavailable-exit
         :out ""
         :err "daemon not running\n"}))
    {:ok false
     :exit unavailable-exit
     :out ""
     :err "daemon not running\n"}))

(defn- print-response!
  [{:keys [exit out err]}]
  (when (seq out)
    (print out))
  (when (seq err)
    (binding [*out* *err*]
      (print err)))
  (flush)
  (binding [*out* *err*]
    (flush))
  (shutdown-agents)
  (System/exit (int (or exit 1))))

(defn- usage
  []
  "Usage: ygg daemon start|status|stop|<cli-command> ...")

(defn -main
  [& args]
  (try
    (silence-jul!)
    (let [[command & command-args] args]
      (case command
        "start"
        (serve!)

        "status"
        (print-response! (request "ping" command-args))

        "stop"
        (print-response! (request "stop" command-args))

        "query"
        (print-response! (request "query" command-args))

        "sync-inspect"
        (print-response! (request "sync-inspect" command-args))

        "cli"
        (print-response! (request "cli" command-args))

        (print-response! (request "cli" args))))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (let [data (ex-data e)]
          (println (or (ex-message e) (.getMessage e)))
          (when data
            (pprint/pprint data))))
      (shutdown-agents)
      (System/exit 1))))
