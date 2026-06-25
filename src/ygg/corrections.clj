(ns ygg.corrections
  "Canonical accepted correction facts and query-time projections."
  (:require [ygg.hash :as hash]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def fact-schema
  "ygg.correction/v1")

(def status-schema
  "ygg.corrections.status/v1")

(def review-schema
  "ygg.corrections.review/v1")

(def operation-kinds
  {:accept-system :system-acceptance
   :set-system-kind :system-kind
   :add-system-include :system-include
   :reject-system :system-rejection
   :add-edge :edge-addition
   :set-edge-visibility :edge-visibility
   :reject-edge :edge-rejection
   :attach-doc :doc-attachment
   :add-package-import :package-import})

(def statuses
  #{:accepted :superseded :rejected})

(def ^:private fact-fields
  [:xt/id
   :schema
   :project-id
   :kind
   :status
   :target-id
   :operation
   :payload
   :reason
   :evidence-ids
   :source
   :confidence
   :created-at-ms
   :updated-at-ms
   :active?])

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- normalize-operation
  [operation]
  (let [operation (keyword operation)]
    (when-not (contains? operation-kinds operation)
      (throw (ex-info "Unsupported correction operation."
                      {:operation operation
                       :supported (sort (keys operation-kinds))})))
    operation))

(defn- normalize-status
  [status]
  (let [status (keyword (or status :accepted))]
    (when-not (contains? statuses status)
      (throw (ex-info "Unsupported correction status."
                      {:status status
                       :supported (sort statuses)})))
    status))

(defn- normalize-source
  [source]
  (cond
    (nil? source)
    {:kind :human}

    (map? source)
    (cond-> source
      (:kind source) (update :kind keyword))

    (= "manual" (str source))
    {:kind :human}

    (keyword? source)
    {:kind source}

    :else
    {:kind :agent
     :id (str source)}))

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn required-reason!
  [reason action]
  (when (blankish? reason)
    (throw (ex-info "Correction requires --reason."
                    {:action action}))))

(defn- required-value!
  [field value]
  (when (blankish? value)
    (throw (ex-info "Correction fact requires a value."
                    {:field field}))))

(defn- canonical-key
  [k]
  (cond
    (keyword? k) (name k)
    (nil? k) ""
    :else (str k)))

(defn- canonical-value
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (canonical-key %1) (canonical-key %2)))
          (map (fn [[k v]] [k (canonical-value v)]))
          value)

    (vector? value)
    (mapv canonical-value value)

    (sequential? value)
    (mapv canonical-value value)

    (set? value)
    (->> value
         (map canonical-value)
         (sort-by pr-str)
         vec)

    :else value))

(defn- row-hash
  [project-id operation payload opts]
  (hash/short-hash
   (pr-str
    (canonical-value
     {:project-id project-id
      :operation operation
      :payload payload
      :reason (:reason opts)
      :evidence-ids (:evidence-ids opts)
      :source (:source opts)}))))

(defn row-id
  [project-id operation payload opts]
  (str "correction:" project-id ":" (row-hash project-id operation payload opts)))

(defn- edge-target-id
  [payload]
  (or (:target-id payload)
      (:id payload)
      (:target payload)
      (when (and (:source payload) (:relation payload) (:target payload))
        (str (:source payload) "|" (s (:relation payload)) "|" (:target payload)))))

(defn- target-id
  [operation payload]
  (case operation
    (:accept-system :set-system-kind :add-system-include :reject-system :attach-doc)
    (or (:target payload)
        (:id payload)
        (get-in payload [:match :id])
        (get-in payload [:match :label])
        (get-in payload [:match :host]))

    (:add-edge :set-edge-visibility :reject-edge)
    (edge-target-id payload)

    :add-package-import
    (or (:target-id payload) (:import payload))

    nil))

(defn fact-row
  ([project-id operation payload] (fact-row project-id operation payload {}))
  ([project-id operation payload {:keys [reason evidence-ids source confidence now status]
                                  :or {source {:kind :human}
                                       status :accepted}}]
   (let [operation (normalize-operation operation)
         payload (or payload {})
         source (normalize-source source)
         status (normalize-status status)
         reason (some-> reason str)
         evidence-ids (mapv str (remove nil? evidence-ids))
         now (long (or now (now-ms)))
         opts {:reason reason
               :evidence-ids evidence-ids
               :source source}]
     (required-value! :project-id project-id)
     (required-reason! reason operation)
     (cond-> {:xt/id (row-id project-id operation payload opts)
              :schema fact-schema
              :project-id project-id
              :kind (get operation-kinds operation)
              :operation operation
              :target-id (target-id operation payload)
              :payload payload
              :reason reason
              :evidence-ids evidence-ids
              :source source
              :confidence (double (or confidence 1.0))
              :status status
              :active? true
              :created-at-ms now
              :updated-at-ms now}))))

(defn- validate-fact-row!
  [row]
  (doseq [field [:xt/id :schema :project-id :kind :status :operation :payload :reason :source]]
    (required-value! field (get row field)))
  (when-not (= fact-schema (:schema row))
    (throw (ex-info "Invalid correction fact schema."
                    {:schema (:schema row)
                     :expected fact-schema})))
  (normalize-operation (:operation row))
  (normalize-status (:status row))
  row)

(defn- sort-facts
  [rows]
  (->> rows
       (sort-by (juxt #(long (or (:created-at-ms %) 0))
                      :xt/id))
       vec))

(defn put-facts!
  "Persist accepted correction fact rows. Returns the rows."
  [xtdb rows]
  (let [rows (mapv validate-fact-row! rows)]
    (store/execute-tx!
     xtdb
     (mapv #(store/put-op (:correction-facts store/tables) %) rows))
    rows))

(defn add!
  "Persist one accepted correction fact and return it."
  ([xtdb project-id operation payload] (add! xtdb project-id operation payload {}))
  ([xtdb project-id operation payload opts]
   (first (put-facts! xtdb [(fact-row project-id operation payload opts)]))))

(defn facts
  "Return active accepted correction fact rows for project-id."
  [xtdb project-id]
  (sort-facts
   (store/constrained-rows xtdb
                           (:correction-facts store/tables)
                           {:project-id project-id
                            :status :accepted
                            :active? true})))

(defn facts-for-targets
  "Return active accepted correction facts for target-ids in one bounded read."
  [xtdb project-id target-ids]
  (sort-facts
   (store/rows-with-field-values
    xtdb
    {:table (:correction-facts store/tables)
     :field :target-id
     :values (mapv str (remove blankish? target-ids))
     :constraints {:project-id project-id
                   :status :accepted
                   :active? true}
     :return-fields fact-fields})))

(defn- merge-system
  [old new]
  (let [includes (vec (distinct (concat (:includes old) (:includes new))))]
    (cond-> (merge old new)
      (seq includes) (assoc :includes includes))))

(defn- system-match?
  [target system]
  (let [target (s target)]
    (or (= target (s (:id system)))
        (= target (s (:label system))))))

(defn- upsert-system
  [overlay system]
  (let [system-id (:id system)]
    (update overlay
            :systems
            (fn [systems]
              (let [systems (vec systems)]
                (if-let [idx (first (keep-indexed (fn [idx candidate]
                                                    (when (or (system-match? system-id candidate)
                                                              (system-match? (:label system) candidate))
                                                      idx))
                                                  systems))]
                  (update systems idx merge-system system)
                  (conj systems system)))))))

(defn- accepted-system
  [payload fact]
  (cond-> (assoc payload
                 :id (or (:id payload) (:target payload))
                 :label (or (:label payload) (:id payload) (:target payload))
                 :kind (or (some-> (:kind payload) s) "system")
                 :status "accepted")
    (:reason fact) (assoc :reason (:reason fact))))

(defn- add-system-include
  [overlay payload fact]
  (let [target (or (:target payload) (:id payload))
        include (:include payload)
        system (cond-> {:id target
                        :label target
                        :kind "system"
                        :status "accepted"}
                 include (assoc :includes [include])
                 (:reason fact) (assoc :reason (:reason fact)))]
    (upsert-system overlay system)))

(defn- set-system-kind
  [overlay payload fact]
  (let [target (or (:target payload) (:id payload))
        system (cond-> {:id target
                        :label target
                        :kind (s (:kind payload))
                        :status "accepted"}
                 (:reason fact) (assoc :reason (:reason fact)))]
    (upsert-system overlay system)))

(defn- source-label
  [fact]
  (let [source (:source fact)]
    (cond
      (map? source) (or (some-> (:kind source) s) (some-> (:id source) s))
      :else (s source))))

(defn- attach-doc
  [overlay payload fact]
  (correction-overlay/add-doc overlay
                     (:target payload)
                     (:source payload)
                     (assoc (select-keys payload [:role :heading :start-line :end-line])
                            :reason (:reason fact))))

(defn- add-edge
  [overlay payload fact]
  (correction-overlay/add-edge overlay
                      (cond-> payload
                        (:reason fact) (assoc :reason (:reason fact))
                        (seq (:evidence-ids fact)) (assoc :evidence (:evidence-ids fact))
                        (source-label fact) (assoc :rules (source-label fact)))))

(defn- add-package-import
  [overlay payload fact]
  (correction-overlay/add-package-import overlay
                                (cond-> payload
                                  (:reason fact) (assoc :reason (:reason fact))
                                  (seq (:evidence-ids fact)) (assoc :evidence (:evidence-ids fact))
                                  (and (not (:rules payload))
                                       (source-label fact)) (assoc :rules (source-label fact))
                                  (:confidence fact) (assoc :confidence (:confidence fact)))))

(defn- payload-key
  [k]
  (let [k (if (keyword? k) (name k) (str k))]
    (case k
      "reviewid" :reviewId
      "reviewId" :reviewId
      (keyword k))))

(defn- keywordize-map-keys
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [(payload-key k) (keywordize-map-keys v)]))
          value)

    (vector? value)
    (mapv keywordize-map-keys value)

    (sequential? value)
    (mapv keywordize-map-keys value)

    :else value))

(defn apply-fact
  "Apply one accepted correction fact to an overlay projection."
  [overlay fact]
  (let [{:keys [operation payload reason] :as fact} (-> fact
                                                        (update :payload keywordize-map-keys)
                                                        (update :source keywordize-map-keys))]
    (case (keyword operation)
      :accept-system
      (upsert-system overlay (accepted-system payload fact))

      :set-system-kind
      (set-system-kind overlay payload fact)

      :add-system-include
      (add-system-include overlay payload fact)

      :reject-system
      (correction-overlay/add-reject overlay (:match payload) reason)

      :add-edge
      (add-edge overlay payload fact)

      :set-edge-visibility
      (add-edge overlay payload fact)

      :reject-edge
      (add-edge overlay (assoc payload :visibility "noise") fact)

      :attach-doc
      (attach-doc overlay payload fact)

      :add-package-import
      (add-package-import overlay payload fact)

      overlay)))

(defn overlay-from-facts
  [project-id rows]
  (correction-overlay/normalize-overlay
   (reduce apply-fact
           (correction-overlay/empty-overlay project-id)
           rows)))

(defn overlay
  "Project active accepted correction facts into the graph overlay shape."
  [xtdb project-id]
  (overlay-from-facts project-id (facts xtdb project-id)))

(defn counts
  [xtdb project-id]
  (correction-overlay/overlay-counts (overlay xtdb project-id)))

(defn status
  [xtdb project-id]
  (let [rows (facts xtdb project-id)
        overlay (overlay-from-facts project-id rows)]
    {:schema status-schema
     :action "status"
     :project-id project-id
     :facts (count rows)
     :corrections (correction-overlay/overlay-counts overlay)}))

(defn- overlay-facts
  [project-id overlay {:keys [source now]}]
  (let [overlay (correction-overlay/normalize-overlay (assoc overlay :project project-id))
        row (fn [operation payload opts]
              (fact-row project-id
                        operation
                        payload
                        (merge {:source source
                                :now now}
                               opts)))]
    (vec
     (concat
      (map (fn [system]
             (row :accept-system
                  system
                  {:reason (:reason system)}))
           (:systems overlay))
      (map (fn [reject]
             (row :reject-system
                  {:match (:match reject)}
                  {:reason (:reason reject)}))
           (:reject overlay))
      (map (fn [edge]
             (row (if (= "noise" (s (:visibility edge)))
                    :reject-edge
                    :add-edge)
                  edge
                  {:reason (:reason edge)
                   :evidence-ids (:evidence edge)
                   :confidence (:confidence edge)}))
           (:edges overlay))
      (map (fn [doc]
             (row :attach-doc
                  doc
                  {:reason (:reason doc)}))
           (:docs overlay))
      (map (fn [package-import]
             (row :add-package-import
                  package-import
                  {:reason (:reason package-import)
                   :evidence-ids (:evidence package-import)
                   :confidence (:confidence package-import)}))
           (:packageImports overlay))))))

(defn import-overlay!
  "Persist every accepted entry in overlay as typed correction facts."
  ([xtdb project-id overlay] (import-overlay! xtdb project-id overlay {}))
  ([xtdb project-id overlay opts]
   (put-facts! xtdb (overlay-facts project-id overlay opts))))

(defn apply-overlay!
  "Apply f to the current projection and persist the resulting accepted entries
  as typed correction facts. This is a translation boundary for packet results
  that still describe patches in overlay terms."
  [xtdb project-id f {:keys [source] :as opts}]
  (let [before (overlay xtdb project-id)
        after (f before)
        rows (import-overlay! xtdb
                              project-id
                              after
                              (merge {:source (or source "correction-apply")}
                                     opts))]
    {:schema status-schema
     :action "write"
     :project-id project-id
     :rows rows
     :before (correction-overlay/overlay-counts before)
     :after (correction-overlay/overlay-counts (overlay xtdb project-id))}))
