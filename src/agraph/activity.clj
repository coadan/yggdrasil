(ns agraph.activity
  "Durable local work/activity facts for AGraph."
  (:require [agraph.hash :as hash]
            [agraph.queue :as queue]
            [agraph.schema :as schema]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def sync-schema
  "agraph.activity.sync/v1")

(def item-schema
  "agraph.activity.item/v1")

(def event-schema
  "agraph.activity.event/v1")

(def default-limit
  6)

(def ^:private target-id-keys
  #{:id :xt/id
    :target :source
    :target-id :source-id :system-id :file-id :work-id
    :targetId :sourceId :systemId :fileId :workId
    :decisionId :reviewId :artifact})

(def ^:private skipped-text-keys
  #{:messages :expectedOutput :expected-output :tools})

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- present?
  [value]
  (not (str/blank? (str value))))

(defn- compact
  [& values]
  (->> values
       flatten
       (keep s)
       (remove str/blank?)
       distinct
       (str/join " ")))

(defn- collect-target-ids
  [value]
  (letfn [(walk [value]
            (cond
              (map? value)
              (mapcat (fn [[k v]]
                        (concat
                         (when (and (contains? target-id-keys k) (present? v))
                           [(s v)])
                         (walk v)))
                      value)

              (sequential? value)
              (mapcat walk value)

              :else []))]
    (->> (walk value)
         (remove str/blank?)
         distinct
         sort
         vec)))

(defn- collect-text
  [value]
  (letfn [(walk [value]
            (cond
              (map? value)
              (mapcat (fn [[k v]]
                        (when-not (contains? skipped-text-keys k)
                          (walk v)))
                      value)

              (sequential? value)
              (mapcat walk value)

              (or (string? value) (keyword? value) (number? value) (boolean? value))
              [(s value)]

              :else []))]
    (->> (walk value)
         (remove str/blank?)
         (take 80)
         vec)))

(defn- result-schema
  [item]
  (get-in item [:result :schema]))

(defn- expected-result-schema
  [item]
  (get-in item [:payload :expectedResultSchema]))

(defn- summary-text
  [item target-ids]
  (compact (:kind item)
           (:status item)
           (:payload-schema item)
           (expected-result-schema item)
           (result-schema item)
           target-ids
           (collect-text (:payload item))
           (collect-text (:result item))))

(defn- activity-item-id
  [source-id]
  (str "activity-item:" (hash/short-hash [source-id])))

(defn- validate-item
  [row]
  (schema/assert! schema/activity-item-row row "Invalid activity item row."))

(defn- validate-event
  [row]
  (schema/assert! schema/activity-event-row row "Invalid activity event row."))

(defn queue-item->row
  "Return one durable activity item row for a filesystem queue item."
  [run-id {:keys [path item]}]
  (let [target-ids (collect-target-ids [(:payload item) (:result item)])
        summary (summary-text item target-ids)]
    (validate-item
     (cond-> {:xt/id (activity-item-id (:id item))
              :schema item-schema
              :source :queue
              :source-id (:id item)
              :kind (s (:kind item))
              :status (keyword (queue/status-name (:status item)))
              :target-ids target-ids
              :summary summary
              :tokens (text/tokenize summary)
              :created-at-ms (long (:created-at-ms item))
              :updated-at-ms (long (:updated-at-ms item))
              :active? true
              :run-id run-id}
       (:project-id item) (assoc :project-id (:project-id item))
       path (assoc :source-path path)
       (:payload-schema item) (assoc :payload-schema (:payload-schema item))
       (expected-result-schema item) (assoc :expected-result-schema
                                            (expected-result-schema item))
       (result-schema item) (assoc :result-schema (result-schema item))
       (:completed-at-ms item) (assoc :completed-at-ms (long (:completed-at-ms item)))))))

(defn- event-row
  [run-id item-row event-kind at-ms summary extra]
  (when at-ms
    (validate-event
     (merge {:xt/id (str "activity-event:"
                         (hash/short-hash [(:source-id item-row) event-kind at-ms summary]))
             :schema event-schema
             :source (:source item-row)
             :source-id (:source-id item-row)
             :item-id (:xt/id item-row)
             :event-kind event-kind
             :status (:status item-row)
             :target-ids (:target-ids item-row)
             :summary summary
             :at-ms (long at-ms)
             :active? true
             :run-id run-id}
            (cond-> {}
              (:project-id item-row) (assoc :project-id (:project-id item-row)))
            extra))))

(defn- validation-result?
  [result]
  (or (seq (:commands result))
      (seq (:scores result))
      (seq (:groundTruthRanks result))
      (seq (:ground-truth-ranks result))))

(defn- result-schema-mismatch?
  [item]
  (let [expected (expected-result-schema item)
        actual (result-schema item)]
    (and (present? expected)
         (present? actual)
         (not= expected actual))))

(defn queue-item->events
  "Return durable lifecycle and validation events for a queue item row."
  [run-id {:keys [item] :as found}]
  (let [item-row (queue-item->row run-id found)
        result (:result item)
        lease (:lease item)]
    (vec
     (remove nil?
             [(event-row run-id
                         item-row
                         :created
                         (:created-at-ms item)
                         (compact "created" (:kind item) (:payload-schema item))
                         {})
              (event-row run-id
                         item-row
                         :claimed
                         (:claimed-at-ms lease)
                         (compact "claimed" (:kind item))
                         (cond-> {}
                           (:agent-id lease) (assoc :agent-id (:agent-id lease))))
              (event-row run-id
                         item-row
                         :completed
                         (:completed-at-ms item)
                         (compact "completed" (result-schema item) (:summary result) (:reason result))
                         {})
              (when (result-schema-mismatch? item)
                (event-row run-id
                           item-row
                           :result-schema-mismatch
                           (or (:completed-at-ms item) (:updated-at-ms item))
                           (compact "result schema mismatch"
                                    "expected"
                                    (expected-result-schema item)
                                    "actual"
                                    (result-schema item))
                           {}))
              (event-row run-id
                         item-row
                         :rejected
                         (:rejected-at-ms item)
                         (compact "rejected" (:reason item))
                         {})
              (event-row run-id
                         item-row
                         :failed
                         (:failed-at-ms item)
                         (compact "failed" (:reason item))
                         {})
              (event-row run-id
                         item-row
                         :released
                         (:released-at-ms item)
                         (compact "released" (:release-reason item))
                         {})
              (when (validation-result? result)
                (event-row run-id
                           item-row
                           :validation
                           (or (:completed-at-ms item) (:updated-at-ms item))
                           (compact "validation" (:commands result) (:scores result))
                           {}))]))))

(defn all-items
  ([xtdb] (all-items xtdb {}))
  ([xtdb {:keys [project-id read-context]}]
   (->> (store/all-rows xtdb (:activity-items store/tables) (store/read-context read-context))
        (filter #(or (str/blank? (str project-id)) (= project-id (:project-id %))))
        (filter #(not= false (:active? %)))
        (map validate-item)
        vec)))

(defn all-events
  ([xtdb] (all-events xtdb {}))
  ([xtdb {:keys [project-id read-context]}]
   (->> (store/all-rows xtdb (:activity-events store/tables) (store/read-context read-context))
        (filter #(or (str/blank? (str project-id)) (= project-id (:project-id %))))
        (filter #(not= false (:active? %)))
        (map validate-event)
        vec)))

(defn- rows-for-source
  [xtdb table project-id source]
  (->> (store/all-rows xtdb table)
       (filter #(= project-id (:project-id %)))
       (filter #(= source (:source %)))
       vec))

(defn commit-activity!
  "Replace durable activity rows for one project/source."
  [xtdb {:keys [project-id source items events]}]
  (let [source (keyword source)
        existing-items (rows-for-source xtdb (:activity-items store/tables) project-id source)
        existing-events (rows-for-source xtdb (:activity-events store/tables) project-id source)
        items (map validate-item items)
        events (map validate-event events)
        ops (vec
             (concat
              (map #(store/delete-op (:activity-items store/tables) (:xt/id %)) existing-items)
              (map #(store/delete-op (:activity-events store/tables) (:xt/id %)) existing-events)
              (map #(store/put-op (:activity-items store/tables) %) items)
              (map #(store/put-op (:activity-events store/tables) %) events)))]
    (store/execute-tx! xtdb ops)
    {:items (count items)
     :events (count events)
     :deleted-items (count existing-items)
     :deleted-events (count existing-events)}))

(defn sync-queue!
  "Import local filesystem queue items as durable activity rows."
  [xtdb project {:keys [queue-root now]}]
  (let [queue-root (or queue-root queue/default-root)
        now (long (or now (now-ms)))
        run-id (str "activity-run:" (hash/short-hash [(:id project) queue-root now]))
        founds (queue/list-items queue-root {:project-id (:id project)})
        items (mapv #(queue-item->row run-id %) founds)
        events (into [] (mapcat #(queue-item->events run-id %)) founds)
        counts (commit-activity! xtdb
                                 {:project-id (:id project)
                                  :source :queue
                                  :items items
                                  :events events})]
    {:schema sync-schema
     :project-id (:id project)
     :source "queue"
     :queue-root queue-root
     :run-id run-id
     :counts (assoc counts
                    :ready (count (filter #(= :ready (:status %)) items))
                    :claimed (count (filter #(= :claimed (:status %)) items))
                    :done (count (filter #(= :done (:status %)) items))
                    :rejected (count (filter #(= :rejected (:status %)) items))
                    :failed (count (filter #(= :failed (:status %)) items))
                    :validation-events (count (filter #(= :validation (:event-kind %))
                                                      events)))}))

(defn- event-summary
  [event]
  (select-keys event [:event-kind :status :agent-id :summary :at-ms]))

(defn- item-score
  [query-tokens selected-targets item]
  (let [lexical (text/token-score query-tokens (:tokens item))
        target-score (if (seq (filter selected-targets (:target-ids item))) 1.0 0.0)]
    (+ lexical (* 0.5 target-score))))

(defn select-activity
  "Return compact activity rows matching query text or selected graph targets."
  [xtdb query-text {:keys [project-id read-context target-ids limit]
                    :or {limit default-limit}}]
  (let [query-tokens (text/tokenize query-text)
        selected-targets (set target-ids)
        events-by-item (group-by :item-id (all-events xtdb {:project-id project-id
                                                            :read-context read-context}))]
    (->> (all-items xtdb {:project-id project-id
                          :read-context read-context})
         (map #(assoc % :score (item-score query-tokens selected-targets %)))
         (filter #(pos? (:score %)))
         (sort-by (juxt (comp - :score)
                        (comp - :updated-at-ms)
                        :kind
                        :source-id))
         (take limit)
         (mapv (fn [item]
                 (let [events (->> (get events-by-item (:xt/id item))
                                   (sort-by :at-ms >)
                                   (take 4)
                                   (mapv event-summary))]
                   (cond-> {:id (:xt/id item)
                            :kind (:kind item)
                            :status (name (:status item))
                            :source (name (:source item))
                            :sourceId (:source-id item)
                            :sourcePath (:source-path item)
                            :payloadSchema (:payload-schema item)
                            :expectedResultSchema (:expected-result-schema item)
                            :resultSchema (:result-schema item)
                            :targetIds (:target-ids item)
                            :summary (:summary item)
                            :score (double (:score item))
                            :createdAtMs (:created-at-ms item)
                            :updatedAtMs (:updated-at-ms item)
                            :events events}
                     (:completed-at-ms item) (assoc :completedAtMs (:completed-at-ms item)))))))))
