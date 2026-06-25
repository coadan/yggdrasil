(ns ygg.index-maintenance-worker
  "Project-configured worker for completing queued index maintenance work."
  (:refer-clojure :exclude [run!])
  (:require [ygg.env :as env]
            [ygg.hash :as hash]
            [ygg.llm.anthropic-compatible :as anthropic-compatible]
            [ygg.llm.openai-compatible :as openai-compatible]
            [ygg.queue :as queue]
            [ygg.work :as work]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def schema
  "ygg.index-maintenance-worker.run/v1")

(def ^:dynamic *deps* {})

(defn- dep
  [k default]
  (get *deps* k default))

(defn- now-ms
  []
  ((dep :now-ms queue/now-ms)))

(defn- get-env
  [k]
  ((dep :get-env env/get-env) k))

(defn- write-json-file!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str value {:indent-str "  "}) "\n"))
    (.getPath file)))

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- present?
  [value]
  (not (str/blank? (str value))))

(defn- api-executor?
  [executor]
  (contains? #{:openai-compatible :anthropic-compatible} (:type executor)))

(defn- executor-available?
  [executor]
  (or (not (api-executor? executor))
      (present? (get-env (:env executor)))))

(defn- available-executors
  [config]
  (filterv executor-available? (:executors config)))

(defn- executor-for-kind
  [executors kind]
  (first (filter #(contains? (:kinds %) (str kind)) executors)))

(defn- available-kinds
  [executors]
  (set (mapcat :kinds executors)))

(defn- next-ready-kind
  [root project-id kinds]
  (some (fn [{:keys [item]}]
          (when (contains? kinds (:kind item))
            (:kind item)))
        (queue/list-items root {:status "ready"
                                :project-id project-id})))

(defn- run-dir
  [config work-id now]
  (io/file (:report-dir config)
           (str now "-" (hash/short-hash [work-id now]))))

(defn- openai-client
  [executor]
  ((dep :openai-client openai-compatible/client)
   (cond-> (select-keys executor [:provider :model :base-url :max-retries])
     (:env executor) (assoc :api-key (get-env (:env executor))))))

(defn- anthropic-client
  [executor]
  ((dep :anthropic-client anthropic-compatible/client)
   (cond-> (select-keys executor
                        [:provider :model :base-url :max-retries :max-tokens
                         :temperature :anthropic-version])
     (:env executor) (assoc :api-key (get-env (:env executor))))))

(defn- complete-with-api!
  [executor messages]
  (let [client (case (:type executor)
                 :openai-compatible (openai-client executor)
                 :anthropic-compatible (anthropic-client executor))]
    ((:complete-json client) messages)))

(defn- slurp-stream-async
  [stream]
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (deliver result (slurp stream))
                            (catch Exception e
                              (deliver result (or (ex-message e) (str e)))))))]
    (.setDaemon thread true)
    (.start thread)
    result))

(defn- command-result!
  [{:keys [argv timeout-ms cwd]}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str argv))
        _ (when (present? cwd)
            (.directory builder (io/file cwd)))
        process (.start builder)
        out-result (slurp-stream-async (.getInputStream process))
        err-result (slurp-stream-async (.getErrorStream process))]
    (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
      {:exit (.exitValue process)
       :out @out-result
       :err @err-result}
      (do
        (.destroyForcibly process)
        {:timeout? true
         :out (deref out-result 100 "")
         :err (deref err-result 100 "")}))))

(defn- command-failure-reason
  [{:keys [exit err out timeout?]}]
  (cond
    timeout? "Command index maintenance executor timed out."
    (not (zero? (long (or exit 1))))
    (str "Command index maintenance executor exited "
         exit
         (when-let [detail (not-empty (str/trim (or err out "")))]
           (str ": " detail)))
    :else nil))

(defn- complete-with-command!
  [config executor input-path result-path]
  (let [argv (vec (concat (:command executor)
                          ["--work" input-path "--result" result-path]))
        runner (dep :command-runner command-result!)
        process (runner {:argv argv
                         :timeout-ms (:timeout-ms executor)
                         :cwd (:base-dir config)
                         :executor executor})]
    (if-let [reason (command-failure-reason process)]
      {:error reason
       :process (select-keys process [:exit :timeout?])}
      {:result (read-json-file result-path)
       :process (select-keys process [:exit :timeout?])})))

(defn- failure!
  [root id reason extra]
  (let [failed (queue/fail! root id reason)]
    (merge {:status "failed"
            :reason reason
            :item (queue/item-summary failed)}
           extra)))

(defn- complete-result!
  [root id result]
  (queue/complete! root id result))

(defn- validate-completed!
  [config root id]
  (let [validation (work/validate-result root id)
        mode (get-in config [:apply :mode])]
    (if (and (= :validate-only mode)
             (not= "valid" (:status validation)))
      {:validation validation
       :failure (work/fail-invalid-result! root id validation)}
      {:validation validation})))

(defn- process-claimed!
  [config executor claimed]
  (let [root (:queue-dir config)
        item (:item claimed)
        work-id (:id item)
        now (now-ms)
        dir (run-dir config work-id now)
        input-path (.getPath (io/file dir "work.json"))
        result-path (.getPath (io/file dir "result.json"))
        messages (get-in item [:payload :messages])]
    (write-json-file! input-path item)
    (try
      (let [{:keys [result error process]}
            (case (:type executor)
              (:openai-compatible :anthropic-compatible)
              {:result (complete-with-api! executor messages)}

              :command-harness
              (complete-with-command! config executor input-path result-path))]
        (if error
          (failure! root work-id error {:executor (:id executor)
                                        :artifacts {:work input-path
                                                    :result result-path}
                                        :process process})
          (let [completed (complete-result! root work-id result)
                {:keys [validation failure]} (validate-completed! config root work-id)]
            (write-json-file! result-path result)
            {:status (if failure "failed" "completed")
             :executor (:id executor)
             :item (queue/item-summary (if failure
                                         (queue/find-item root work-id)
                                         completed))
             :validation validation
             :failure failure
             :artifacts {:work input-path
                         :result result-path}
             :process process})))
      (catch Exception e
        (failure! root
                  work-id
                  (str "Index maintenance executor failed: "
                       (or (ex-message e) (.getMessage e)))
                  {:executor (:id executor)
                   :artifacts {:work input-path
                               :result result-path}
                   :error-data (ex-data e)})))))

(defn process-next!
  "Claim and process one ready queue item for project/config. Return nil when empty."
  [project config]
  (let [root (:queue-dir config)
        executors (available-executors config)
        kinds (available-kinds executors)]
    (when (seq executors)
      (when-let [kind (next-ready-kind root (:id project) kinds)]
        (when-let [claimed (queue/claim-next! root
                                              {:agent-id (:agent-id config)
                                               :lease-ms (* 60 1000 (:lease-minutes config))
                                               :project-id (:id project)
                                               :kind kind})]
          (let [executor (executor-for-kind executors (get-in claimed [:item :kind]))]
            (process-claimed! config executor claimed)))))))

(defn- run-status
  [config results exhausted?]
  (cond
    (not (:enabled config)) "disabled"
    (empty? (available-executors config)) "no-executor"
    (seq results) "completed"
    exhausted? "limit-reached"
    :else "empty"))

(defn- result-counts
  [results]
  (let [by-status (frequencies (map :status results))]
    {:claimed (count results)
     :completed (long (or (get by-status "completed") 0))
     :failed (long (or (get by-status "failed") 0))
     :validated (count (filter :validation results))}))

(defn run!
  "Run the project-configured index maintenance worker for up to max-items-per-run items."
  ([project] (run! project {}))
  ([project opts]
   (let [config (merge (:index-maintenance-worker project) opts)
         max-items (long (or (:max-items-per-run config) 0))]
     (if-not (:enabled config)
       {:schema schema
        :project-id (:id project)
        :status "disabled"
        :counts (result-counts [])}
       (let [results (loop [remaining max-items
                            out []]
                       (if (not (pos? remaining))
                         out
                         (if-let [result (process-next! project config)]
                           (recur (dec remaining) (conj out result))
                           out)))
             exhausted? (and (pos? max-items)
                             (= max-items (count results)))]
         {:schema schema
          :project-id (:id project)
          :status (run-status config results exhausted?)
          :queue-root (:queue-dir config)
          :report-dir (:report-dir config)
          :counts (result-counts results)
          :items (mapv #(select-keys % [:status :executor :item :validation :failure
                                        :artifacts :process])
                       results)})))))
