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

(def command-work-batch-schema
  "ygg.index-maintenance.command-work-batch/v1")

(def command-work-result-batch-schema
  "ygg.index-maintenance.command-work-result-batch/v1")

(def ^:private decision-target-limit
  12)

(def ^:private decision-correction-patch-limit
  12)

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

(defn- executor-kinds
  [executor]
  (set (map str (:kinds executor))))

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
     (merge (select-keys (:maintenance project) [:queue-db :report-dir])
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
     :work (:work maintenance)
     :queueDb (:queue-db maintenance)
     :reportDir (:report-dir maintenance)
     :worker {:configured (boolean config)
              :enabled (boolean (:enabled config))
              :leaseMinutes (:lease-minutes config)
              :maxItemsPerRun (:max-items-per-run config)
              :maxFailuresPerRun (:max-failures-per-run config)
              :apply (:apply config)
              :queueDb (:queue-db config)
              :reportDir (:report-dir config)
              :executors executor-statuses
              :executorCount (count executor-statuses)
              :availableExecutorCount (count available-executors)
              :availableKinds (sort (available-kinds
                                     (filterv executor-available? executors)))}}))

(defn- next-ready-kind
  [queue-db project-id kinds]
  (some (fn [{:keys [item]}]
          (when (contains? kinds (:kind item))
            (:kind item)))
        (queue/list-items queue-db {:status "ready"
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
    (assoc :targets (update (bounded-vec decision-target-limit
                                         (map compact-target (:targets data)))
                            :items vec))

    (:correctionPatch data)
    (assoc :correctionPatch (bounded-vec decision-correction-patch-limit
                                         (:correctionPatch data)))

    (seq (:edges data))
    (assoc-in [:omitted :edges] (count (:edges data)))

    (seq (:evidence-ids data))
    (assoc-in [:omitted :evidence-ids] (count (:evidence-ids data)))))

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

(defn- compact-infra-system
  [system]
  (cond-> (select-keys system [:id :label :kind :repo :path :repoRole])
    (:metrics system)
    (assoc :metrics (select-keys (:metrics system)
                                 [:file-count
                                  :node-count
                                  :incoming-code-edge-count
                                  :outgoing-code-edge-count]))))

(defn- compact-infra-evidence
  [evidence]
  (select-keys evidence [:id :kind :systemId :repo :path :line :label
                         :normalizedValue :confidence]))

(defn- compact-infra-facts
  [facts]
  (cond-> (select-keys facts [:artifact :producerSystemIds :consumerSystemIds])
    (:systems facts)
    (assoc :systems (update (bounded-vec 12 (map compact-infra-system
                                                 (:systems facts)))
                            :items vec))

    (:evidence facts)
    (assoc :evidence (update (bounded-vec 16 (map compact-infra-evidence
                                                  (:evidence facts)))
                             :items vec))))

(defn- compact-infra-packet
  [packet]
  (cond-> (select-keys packet [:schema :reviewId :project-id :goal :kind
                               :question :artifact :allowedActions
                               :instructions :expectedResultSchema
                               :expectedOutput])
    (:basis packet)
    (assoc :basis (select-keys (:basis packet) [:schema :project-id :hash :counts]))

    (:facts packet)
    (assoc :facts (compact-infra-facts (:facts packet)))))

(defn- compact-dependency-package
  [package]
  (select-keys package [:id :label :ecosystem :package-name :candidateScore
                        :candidateSignals]))

(defn- compact-dependency-evidence
  [evidence]
  (select-keys evidence [:id :kind :repo :sourceId :sourceLabel :targetId
                         :import :path :line :fileKind]))

(defn- compact-dependency-facts
  [facts]
  (cond-> (select-keys facts [:unresolvedImport :packageSelection])
    (:packages facts)
    (assoc :packages (update (bounded-vec 12 (map compact-dependency-package
                                                  (:packages facts)))
                             :items vec))

    (:evidence facts)
    (assoc :evidence (update (bounded-vec 8 (map compact-dependency-evidence
                                                 (:evidence facts)))
                             :items vec))))

(defn- compact-dependency-packet
  [packet]
  (cond-> (select-keys packet [:schema :reviewId :project-id :goal :kind
                               :question :allowedActions :instructions
                               :expectedResultSchema :expectedOutput])
    (:basis packet)
    (assoc :basis (select-keys (:basis packet) [:schema :project-id :hash :counts]))

    (:facts packet)
    (assoc :facts (compact-dependency-facts (:facts packet)))))

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

    "ygg.infra.review-packet/v1"
    (mark-messages-omitted (compact-infra-packet payload) payload)

    "ygg.infra.review-batch-packet/v1"
    (mark-messages-omitted
     {:schema (:schema payload)
      :batchId (:batchId payload)
      :project-id (:project-id payload)
      :goal (:goal payload)
      :instructions (:instructions payload)
      :expectedResultSchema (:expectedResultSchema payload)
      :expectedOutput (:expectedOutput payload)
      :reviews (update (bounded-vec 8 (map compact-infra-packet (:items payload)))
                       :items vec)}
     payload)

    "ygg.dependency.review-packet/v1"
    (mark-messages-omitted (compact-dependency-packet payload) payload)

    "ygg.dependency.review-batch-packet/v1"
    (mark-messages-omitted
     {:schema (:schema payload)
      :batchId (:batchId payload)
      :project-id (:project-id payload)
      :goal (:goal payload)
      :instructions (:instructions payload)
      :expectedResultSchema (:expectedResultSchema payload)
      :expectedOutput (:expectedOutput payload)
      :reviews (update (bounded-vec 8 (map compact-dependency-packet
                                           (:items payload)))
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

(defn- command-work-batch-input
  [project claimed-items dir]
  {:schema command-work-batch-schema
   :workItems (mapv queue/item-summary claimed-items)
   :project {:id (:id project)
             :repos (mapv repo-summary (:repos project))}
   :artifactDir (.getPath (io/file dir))
   :items (mapv (fn [claimed]
                  (let [item (:item claimed)]
                    {:workItem (queue/item-summary claimed)
                     :payload (compact-payload (:payload item))
                     :fullPayload {:storedInQueue true
                                   :reason "Queue payload remains durable for validation and audit; command harness input is compact so repo-aware agents inspect attached source directories directly."}}))
                claimed-items)})

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
  [queue-db id reason extra]
  (let [failed (queue/fail! queue-db id reason)]
    (merge {:status "failed"
            :reason reason
            :item (queue/item-summary failed)}
           extra)))

(defn- complete-result!
  [queue-db id result]
  (queue/complete! queue-db id result))

(defn- validate-completed!
  [config queue-db id]
  (let [validation (work/validate-result queue-db id)
        mode (get-in config [:apply :mode])]
    (if (and (= :validate-only mode)
             (not= "valid" (:status validation)))
      {:validation validation
       :failure (work/fail-invalid-result! queue-db id validation)}
      {:validation validation})))

(defn- complete-and-validate!
  [config queue-db claimed result]
  (let [work-id (get-in claimed [:item :id])
        completed (complete-result! queue-db work-id result)
        {:keys [validation failure]} (validate-completed! config queue-db work-id)]
    {:status (if failure "failed" "completed")
     :item (queue/item-summary (if failure
                                 (queue/find-item queue-db work-id)
                                 completed))
     :validation validation
     :failure failure}))

(defn- process-claimed!
  [project config executor claimed]
  (let [queue-db (:queue-db config)
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
          (failure! queue-db work-id error {:executor (:id executor)
                                            :failure-kind "executor"
                                            :artifacts {:work input-path
                                                        :result result-path}
                                            :process process})
          (let [{:keys [status item validation failure]}
                (complete-and-validate! config queue-db claimed result)]
            (write-json-file! result-path result)
            {:status status
             :executor (:id executor)
             :item item
             :validation validation
             :failure failure
             :artifacts {:work input-path
                         :result result-path}
             :process process})))
      (catch Exception e
        (failure! queue-db
                  work-id
                  (str "Index maintenance executor failed: "
                       (or (ex-message e) (.getMessage e)))
                  {:executor (:id executor)
                   :failure-kind "executor"
                   :artifacts {:work input-path
                               :result result-path}
                   :error-data (ex-data e)})))))

(defn- batch-id
  [claimed-items now]
  (str "batch-" (hash/short-hash [(mapv (comp :id :item) claimed-items) now])))

(defn- batch-run-dir
  [config claimed-items now]
  (io/file (:report-dir config)
           (str now "-" (hash/short-hash [(batch-id claimed-items now) now]))))

(defn- batch-result-rows
  [result]
  (when-not (= command-work-result-batch-schema (:schema result))
    (throw (ex-info "Command index maintenance batch result has the wrong schema."
                    {:expected command-work-result-batch-schema
                     :actual (:schema result)})))
  (when-not (vector? (:results result))
    (throw (ex-info "Command index maintenance batch result must include a results vector."
                    {:schema (:schema result)
                     :value (:results result)})))
  (:results result))

(defn- batch-result-row-id
  [row]
  (:workItemId row))

(defn- batch-results-by-work-id
  [result]
  (reduce (fn [out row]
            (let [work-id (batch-result-row-id row)]
              (when (str/blank? (str work-id))
                (throw (ex-info "Command index maintenance batch result row is missing workItemId."
                                {:row (dissoc row :result)})))
              (when-not (map? (:result row))
                (throw (ex-info "Command index maintenance batch result row must include result object."
                                {:workItemId work-id})))
              (if (contains? out work-id)
                (throw (ex-info "Command index maintenance batch result contains duplicate workItemId."
                                {:workItemId work-id}))
                (assoc out work-id (:result row)))))
          {}
          (batch-result-rows result)))

(defn- process-command-batch!
  [project config executor claimed-items]
  (let [queue-db (:queue-db config)
        now (now-ms)
        dir (batch-run-dir config claimed-items now)
        input-path (.getPath (io/file dir "work.json"))
        result-path (.getPath (io/file dir "result.json"))]
    (write-json-file! input-path
                      (command-work-batch-input project claimed-items dir))
    (try
      (let [{:keys [result error process]}
            (complete-with-command! config executor input-path result-path)]
        (if error
          (mapv (fn [claimed]
                  (failure! queue-db
                            (get-in claimed [:item :id])
                            error
                            {:executor (:id executor)
                             :failure-kind "executor"
                             :artifacts {:work input-path
                                         :result result-path}
                             :process process}))
                claimed-items)
          (let [results-by-work-id (batch-results-by-work-id result)]
            (write-json-file! result-path result)
            (mapv (fn [claimed]
                    (let [work-id (get-in claimed [:item :id])]
                      (if-let [item-result (get results-by-work-id work-id)]
                        (let [{:keys [status item validation failure]}
                              (complete-and-validate! config queue-db claimed item-result)]
                          {:status status
                           :executor (:id executor)
                           :item item
                           :validation validation
                           :failure failure
                           :artifacts {:work input-path
                                       :result result-path}
                           :process process})
                        (failure! queue-db
                                  work-id
                                  "Command index maintenance batch result omitted work item."
                                  {:executor (:id executor)
                                   :failure-kind "executor"
                                   :artifacts {:work input-path
                                               :result result-path}
                                   :process process}))))
                  claimed-items))))
      (catch Exception e
        (mapv (fn [claimed]
                (failure! queue-db
                          (get-in claimed [:item :id])
                          (str "Index maintenance executor failed: "
                               (or (ex-message e) (.getMessage e)))
                          {:executor (:id executor)
                           :failure-kind "executor"
                           :artifacts {:work input-path
                                       :result result-path}
                           :error-data (ex-data e)}))
              claimed-items)))))

(defn- claim-next-for-kinds!
  [project config kinds]
  (when-let [kind (next-ready-kind (:queue-db config) (:id project) kinds)]
    (queue/claim-next! (:queue-db config)
                       {:agent-id (:agent-id config)
                        :lease-ms (* 60 1000 (:lease-minutes config))
                        :project-id (:id project)
                        :kind kind})))

(defn- claim-more-for-executor!
  [project config executor limit]
  (loop [remaining limit
         claimed []]
    (if (pos? remaining)
      (if-let [next-claimed (claim-next-for-kinds! project
                                                   config
                                                   (executor-kinds executor))]
        (recur (dec remaining) (conj claimed next-claimed))
        claimed)
      claimed)))

(defn process-next!
  "Claim and process ready queue items for project/config. Return nil when empty."
  [project config limit]
  (let [queue-db (:queue-db config)
        executors (available-executors config)
        kinds (available-kinds executors)]
    (when (seq executors)
      (when-let [kind (next-ready-kind queue-db (:id project) kinds)]
        (when-let [claimed (queue/claim-next! queue-db
                                              {:agent-id (:agent-id config)
                                               :lease-ms (* 60 1000 (:lease-minutes config))
                                               :project-id (:id project)
                                               :kind kind})]
          (let [executor (executor-for-kind executors (get-in claimed [:item :kind]))]
            (if (and (= :command-harness (:type executor))
                     (< 1 (long limit)))
              (let [claimed-items (into [claimed]
                                        (claim-more-for-executor! project
                                                                  config
                                                                  executor
                                                                  (dec (long limit))))]
                (if (< 1 (count claimed-items))
                  (process-command-batch! project config executor claimed-items)
                  [(process-claimed! project config executor claimed)]))
              [(process-claimed! project config executor claimed)])))))))

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

(defn- ready-work-remains?
  [project config]
  (let [executors (available-executors config)]
    (boolean
     (and (seq executors)
          (next-ready-kind (:queue-db config)
                           (:id project)
                           (available-kinds executors))))))

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
                 (let [max-failures (long (or (:max-failures-per-run config) 0))
                       failure-budget (if (pos? max-failures)
                                        (max 1 (- max-failures executor-failures))
                                        remaining)
                       claim-limit (min remaining failure-budget)]
                   (if-let [results (seq (process-next! project config claim-limit))]
                     (recur (- remaining (count results))
                            (+ executor-failures
                               (count (filter executor-failure? results)))
                            (into out results))
                     {:results out}))))
             exhausted? (and (pos? max-items)
                             (= max-items (count results))
                             (ready-work-remains? project config))
             backoff? (= :backoff stop-reason)]
         (cond-> {:schema schema
                  :project-id (:id project)
                  :status (cond
                            backoff? "backoff"
                            exhausted? "limit-reached"
                            :else (run-status config results exhausted?))
                  :queue-db (:queue-db config)
                  :report-dir (:report-dir config)
                  :counts (result-counts results)
                  :items (mapv #(select-keys % [:status :executor :item :validation :failure
                                                :failure-kind :artifacts :process])
                               results)}
           backoff?
           (assoc :backoff {:reason "executor-failures"
                            :max-failures-per-run (:max-failures-per-run config)})))))))
