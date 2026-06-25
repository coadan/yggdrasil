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

(def command-work-schema
  "ygg.index-maintenance.command-work/v1")

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

(defn- executor-status
  [executor]
  (let [api? (api-executor? executor)
        available? (executor-available? executor)]
    (cond-> {:id (:id executor)
             :type (:type executor)
             :kinds (sort (:kinds executor))
             :reasoning (:reasoning executor)
             :available available?}
      (:provider executor) (assoc :provider (:provider executor))
      (:model executor) (assoc :model (:model executor))
      (:env executor) (assoc :env (:env executor))
      (and api? (not available?)) (assoc :missing-env (:env executor)))))

(defn- worker-config
  ([project] (worker-config project {}))
  ([project opts]
   (when-let [worker (get-in project [:maintenance :worker])]
     (merge (select-keys (:maintenance project) [:queue-dir :report-dir])
            worker
            opts))))

(defn config-status
  "Return a compact status map for a project's normalized maintenance config."
  [project]
  (let [maintenance (:maintenance project)
        config (worker-config project)
        executors (:executors config)
        executor-statuses (mapv executor-status executors)
        available-executors (filterv :available executor-statuses)]
    {:schema "ygg.maintenance.config-status/v1"
     :project-id (:id project)
     :configured (boolean maintenance)
     :enabled (boolean (:enabled maintenance))
     :schedules (vec (:schedules maintenance))
     :queueRoot (:queue-dir maintenance)
     :reportDir (:report-dir maintenance)
     :worker {:configured (boolean config)
              :enabled (boolean (:enabled config))
              :apply (:apply config)
              :executors executor-statuses
              :executorCount (count executor-statuses)
              :availableExecutorCount (count available-executors)
              :availableKinds (sort (available-kinds
                                     (filterv executor-available? executors)))}}))

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

(defn- repo-summary
  [repo]
  (select-keys repo [:id :root :role]))

(defn- bounded-vec
  [limit xs]
  (let [items (vec (take limit (or xs [])))
        total (count (or xs []))]
    (cond-> {:items items
             :count total}
      (< limit total) (assoc :truncated true
                             :omitted (- total limit)))))

(defn- compact-system
  [system]
  (cond-> (select-keys system [:xt/id :label :kind :repo-id :path-prefix])
    (:metrics system)
    (assoc :metrics (select-keys (:metrics system)
                                 [:file-count
                                  :node-count
                                  :internal-code-edge-count
                                  :incoming-code-edge-count
                                  :outgoing-code-edge-count]))))

(defn- compact-target
  [target]
  (select-keys target [:xt/id :label :kind :repo-id :path-prefix :edge-count
                       :evidence-count :relations :visibilities]))

(defn- compact-decision-data
  [data]
  (cond-> (select-keys data [:id :project-id :relation :visibility :direction
                             :target-count :edge-count :evidence-count])
    (:peer data)
    (assoc :peer (compact-system (:peer data)))

    (:targets data)
    (assoc :targets (update (bounded-vec 24 (map compact-target (:targets data)))
                            :items vec))

    (seq (:edges data))
    (assoc-in [:omitted :edges] (count (:edges data)))

    (seq (:evidence-ids data))
    (assoc-in [:omitted :evidence-ids] (count (:evidence-ids data)))

    (seq (:correctionPatch data))
    (assoc-in [:omitted :correctionPatch] (count (:correctionPatch data)))))

(defn- compact-decision
  [decision]
  (cond-> (select-keys decision [:id :kind :severity :target :reason :status
                                 :project-id :scope :recommended-actions])
    (:basis decision)
    (assoc :basis (select-keys (:basis decision) [:schema :project-id :hash :counts]))

    (seq (:evidence-ids decision))
    (assoc :evidence-count (count (:evidence-ids decision)))

    (:data decision)
    (assoc :data (compact-decision-data (:data decision)))))

(defn- compact-decision-item
  [item]
  (cond-> (select-keys item [:decisionId :allowedActions :expectedOutput])
    (:decision item)
    (assoc :decision (compact-decision (:decision item)))))

(defn- mark-messages-omitted
  [out payload]
  (if-let [messages (seq (:messages payload))]
    (assoc-in out [:omitted :messages] (count messages))
    out))

(defn- compact-payload
  [payload]
  (case (:schema payload)
    "ygg.frontier.decision/v1"
    (mark-messages-omitted
     {:schema (:schema payload)
      :decisionId (:decisionId payload)
      :project-id (:project-id payload)
      :goal (:goal payload)
      :allowedActions (:allowedActions payload)
      :instructions (:instructions payload)
      :expectedResultSchema (:expectedResultSchema payload)
      :expectedOutput (:expectedOutput payload)
      :decision (compact-decision (:decision payload))}
     payload)

    "ygg.frontier.decision-batch/v1"
    (mark-messages-omitted
     {:schema (:schema payload)
      :batchId (:batchId payload)
      :project-id (:project-id payload)
      :goal (:goal payload)
      :instructions (:instructions payload)
      :expectedResultSchema (:expectedResultSchema payload)
      :expectedOutput (:expectedOutput payload)
      :decisions (update (bounded-vec 12 (map compact-decision-item (:items payload)))
                         :items vec)}
     payload)

    (mark-messages-omitted (dissoc payload :messages) payload)))

(defn- command-work-input
  [project found dir]
  (let [item (:item found)
        payload (:payload item)]
    {:schema command-work-schema
     :workItem (queue/item-summary found)
     :project {:id (:id project)
               :repos (mapv repo-summary (:repos project))}
     :artifactDir (.getPath (io/file dir))
     :payload (compact-payload payload)
     :fullPayload {:storedInQueue true
                   :reason "Queue payload remains durable for validation and audit; command harness input is compact so repo-aware agents inspect attached source directories directly."}}))

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
  [{:keys [argv timeout-ms cwd env]}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str argv))
        environment (.environment builder)
        _ (when (present? cwd)
            (.directory builder (io/file cwd)))
        _ (doseq [[k v] env
                  :when (some? v)]
            (.put environment (str k) (str v)))
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
                         :env {"YGG_MAINTENANCE_REASONING" (:reasoning executor)}
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
  [project config executor claimed]
  (let [root (:queue-dir config)
        item (:item claimed)
        work-id (:id item)
        now (now-ms)
        dir (run-dir config work-id now)
        input-path (.getPath (io/file dir "work.json"))
        result-path (.getPath (io/file dir "result.json"))
        messages (get-in item [:payload :messages])]
    (write-json-file! input-path
                      (if (= :command-harness (:type executor))
                        (command-work-input project claimed dir)
                        item))
    (try
      (let [{:keys [result error process]}
            (case (:type executor)
              (:openai-compatible :anthropic-compatible)
              {:result (complete-with-api! executor messages)}

              :command-harness
              (complete-with-command! config executor input-path result-path))]
        (if error
          (failure! root work-id error {:executor (:id executor)
                                        :failure-kind "executor"
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
                   :failure-kind "executor"
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
            (process-claimed! project config executor claimed)))))))

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
     :executor-failures (count (filter #(= "executor" (:failure-kind %)) results))
     :validated (count (filter :validation results))}))

(defn- backoff-reached?
  [config executor-failures]
  (let [max-failures (long (or (:max-failures-per-run config) 0))]
    (and (pos? max-failures)
         (<= max-failures (long executor-failures)))))

(defn- executor-failure?
  [result]
  (= "executor" (:failure-kind result)))

(defn run!
  "Run the project-configured index maintenance worker for up to max-items-per-run items."
  ([project] (run! project {}))
  ([project opts]
   (let [maintenance-enabled? (get-in project [:maintenance :enabled])
         config (worker-config project opts)
         max-items (long (or (:max-items-per-run config) 0))]
     (cond
       (not config)
       {:schema schema
        :project-id (:id project)
        :status "not-configured"
        :counts (result-counts [])}

       (not (and maintenance-enabled? (:enabled config)))
       {:schema schema
        :project-id (:id project)
        :status "disabled"
        :counts (result-counts [])}

       :else
       (let [{:keys [results stop-reason]}
             (loop [remaining max-items
                    executor-failures 0
                    out []]
               (cond
                 (backoff-reached? config executor-failures)
                 {:results out
                  :stop-reason :backoff}

                 (not (pos? remaining))
                 {:results out
                  :stop-reason :limit-reached}

                 :else
                 (if-let [result (process-next! project config)]
                   (recur (dec remaining)
                          (cond-> executor-failures
                            (executor-failure? result) inc)
                          (conj out result))
                   {:results out})))
             exhausted? (and (pos? max-items)
                             (= max-items (count results)))
             backoff? (= :backoff stop-reason)]
         (cond-> {:schema schema
                  :project-id (:id project)
                  :status (cond
                            backoff? "backoff"
                            exhausted? "limit-reached"
                            :else (run-status config results exhausted?))
                  :queue-root (:queue-dir config)
                  :report-dir (:report-dir config)
                  :counts (result-counts results)
                  :items (mapv #(select-keys % [:status :executor :item :validation :failure
                                                :failure-kind :artifacts :process])
                               results)}
           backoff?
           (assoc :backoff {:reason "executor-failures"
                            :max-failures-per-run (:max-failures-per-run config)})))))))
