(ns ygg.daemon
  "Local warm daemon for latency-sensitive Yggdrasil commands."
  (:require [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync-inspect :as sync-inspect]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint])
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
  [server token]
  {:schema schema
   :host "127.0.0.1"
   :port (.getLocalPort ^ServerSocket server)
   :token token
   :pid (pid)
   :storagePath (store/storage-path)
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

(defn- sync-inspect-response
  [xtdb request]
  (let [err (StringWriter.)]
    (try
      (binding [*err* err]
        {:ok true
         :exit 0
         :out (sync-inspect/project-status-output xtdb (:args request))
         :err (str err)})
      (catch Exception e
        (update (error-response e) :err #(str err %))))))

(defn- query-response
  [xtdb request]
  (let [out (StringWriter.)
        err (StringWriter.)]
    (try
      (binding [*out* out
                *err* err
                cli-query/*deps* (cli/query-deps)]
        (cli-query/query-with-node! xtdb (:args request))
        {:ok true
         :exit 0
         :out (str out)
         :err (str err)})
      (catch Exception e
        (-> (error-response e)
            (assoc :out (str out))
            (update :err #(str err %)))))))

(defn handle-request
  "Handle one decoded daemon protocol request."
  [{:keys [xtdb running server] :as ctx} request]
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
      (sync-inspect-response xtdb request)

      "query"
      (query-response xtdb request)

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
  [server xtdb token]
  (let [running (atom true)
        ctx {:xtdb xtdb
             :token token
             :running running
             :server server}]
    (while @running
      (try
        (handle-socket! ctx (.accept ^ServerSocket server))
        (catch java.net.SocketException e
          (when @running
            (throw e)))))))

(defn serve!
  []
  (let [token (str (UUID/randomUUID))]
    (with-open [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [descriptor (descriptor server token)]
            (write-descriptor! descriptor)
            (binding [*out* *err*]
              (println (str "ygg daemon listening on "
                            (:host descriptor)
                            ":"
                            (:port descriptor))))
            (try
              (serve-requests! server xtdb token)
              (finally
                (delete-descriptors!)))))))))

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
                                    :token token})
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
  "Usage: ygg daemon start|status|stop|query <text> ...")

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

        (do
          (println (usage))
          (shutdown-agents)
          (System/exit 1))))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (let [data (ex-data e)]
          (println (or (ex-message e) (.getMessage e)))
          (when data
            (pprint/pprint data))))
      (shutdown-agents)
      (System/exit 1))))
