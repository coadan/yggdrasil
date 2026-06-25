(ns ygg.system.decision-classifier
  "Focused classifier for one index maintenance decision at a time."
  (:require [ygg.corrections-api :as corrections-api]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.hash :as hash]
            [ygg.queue :as queue]
            [charred.api :as json]
            [clojure.string :as str]))

(def schema
  "ygg.index-maintenance.classification/v1")

(def packet-schema
  "ygg.frontier.decision/v1")

(def batch-packet-schema
  "ygg.frontier.decision-batch/v1")

(def batch-result-schema
  "ygg.index-maintenance.classification-batch/v1")

(def apply-schema
  "ygg.sync.work.apply/v1")

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(def ^:private allowed-actions
  ["accept-system"
   "reject-system"
   "set-system-kind"
   "add-edge"
   "set-edge-visibility"
   "reject-external-api"
   "none"])

(def ^:private allowed-ops
  (set allowed-actions))

(defn- allowed-actions-for-decision
  [decision]
  (let [recommended (set (map s (:recommended-actions decision)))
        selected (if (seq recommended)
                   (filterv recommended allowed-actions)
                   allowed-actions)]
    (if (seq selected)
      (vec (distinct (conj selected "none")))
      ["none"])))

(defn- packet-allowed-actions
  [packet]
  (let [actions (set (map s (:allowedActions packet)))]
    (if (seq actions)
      actions
      allowed-ops)))

(def ^:private allowed-recommendations
  #{"accept" "reject" "change" "investigate"})

(def ^:private decision-instructions
  ["Resolve only this one maintenance decision."
   "Most decisions should be straightforward: return one small allowed correction patch, or return no patch when the packet evidence is not enough."
   "Use only decision, allowedActions, and ids already present in this packet."
   "Do not classify the repository, infer broad architecture, edit files, update queue state, or invent ids."
   "Return exactly one JSON object matching expectedResultSchema. If unsure, use recommendation investigate with correctionPatch []."])

(def ^:private batch-instructions
  ["Resolve each maintenance decision independently."
   "Return exactly one result per decisionId in the packet."
   "Each result must match the single-decision classification shape."
   "Use only each decision, its allowedActions, and bounded source facts from attached project repo roots when needed."
   "Do not edit files, update queue state, infer broad architecture, or invent ids."
   "Prefer investigate with correctionPatch [] when a decision lacks enough evidence."])

(defn- instructions-text
  [instructions]
  (str "Instructions:\n"
       (str/join "\n" (map #(str "- " %) instructions))))

(defn- expected-output
  [decision-id]
  {:schema schema
   :decisionId decision-id
   :recommendation "accept|reject|change|investigate"
   :confidence 0.0
   :reason "brief rationale"
   :correctionPatch []})

(defn- batch-id
  [decisions]
  (str "maintenance-decision-batch:"
       (hash/short-hash (mapv :id decisions))))

(defn- decision-item
  [decision]
  (let [allowed-actions (allowed-actions-for-decision decision)]
    {:decisionId (:id decision)
     :decision decision
     :allowedActions allowed-actions
     :expectedOutput (expected-output (:id decision))}))

(defn prompt
  "Return OpenAI-compatible chat messages for one index maintenance decision."
  [decision]
  (let [allowed-actions (allowed-actions-for-decision decision)
        expected-output (expected-output (:id decision))]
    [{:role "system"
      :content (str "You resolve one Yggdrasil index maintenance frontier decision. Return JSON only. "
                    "Do not classify a whole repository. Use only the provided decision data. "
                    "Prefer hiding or rejecting noisy references over promoting weak edges.")}
     {:role "user"
      :content (str
                (instructions-text decision-instructions)
                "\n\n"
                "Return this JSON shape:\n"
                (json/write-json-str expected-output {:indent-str "  "})
                "\n\n"
                (json/write-json-str {:decision decision
                                      :instructions decision-instructions
                                      :allowedActions allowed-actions}
                                     {:indent-str "  "}))}]))

(defn classify
  "Classify one index maintenance decision with an OpenAI-compatible JSON client."
  [{:keys [client decision]}]
  (when-not decision
    (throw (ex-info "Missing index maintenance decision." {})))
  (let [response ((:complete-json client) (prompt decision))]
    (assoc response
           :schema (or (:schema response) schema)
           :decisionId (or (:decisionId response) (:id decision)))))

(defn decision-packet
  "Return a provider-agnostic packet for one index maintenance decision.

  The packet includes OpenAI-compatible messages as one possible consumer input,
  but it is plain JSON and can be processed by any human, model, or agent loop."
  [decision]
  (when-not decision
    (throw (ex-info "Missing index maintenance decision." {})))
  (let [allowed-actions (allowed-actions-for-decision decision)]
    {:schema packet-schema
     :decisionId (:id decision)
     :project-id (:project-id decision)
     :goal "Resolve one bounded Yggdrasil index maintenance decision without classifying the whole project."
     :decision decision
     :allowedActions allowed-actions
     :instructions decision-instructions
     :messages (prompt decision)
     :expectedResultSchema schema
     :expectedOutput (expected-output (:id decision))}))

(defn batch-prompt
  "Return OpenAI-compatible chat messages for a bounded batch of decisions."
  [decisions]
  (let [items (mapv decision-item decisions)
        id (batch-id decisions)
        expected-output {:schema batch-result-schema
                         :batchId id
                         :results (mapv :expectedOutput items)}]
    [{:role "system"
      :content (str "You resolve a bounded batch of Yggdrasil index maintenance "
                    "frontier decisions. Return JSON only. Treat decisions "
                    "independently and do not classify a whole repository.")}
     {:role "user"
      :content (str
                (instructions-text batch-instructions)
                "\n\n"
                "Return this JSON shape:\n"
                (json/write-json-str expected-output {:indent-str "  "})
                "\n\n"
                (json/write-json-str {:batchId id
                                      :instructions batch-instructions
                                      :decisions items}
                                     {:indent-str "  "}))}]))

(defn decision-batch-packet
  "Return one provider-agnostic packet for a bounded batch of maintenance decisions."
  [decisions]
  (let [decisions (vec decisions)]
    (when-not (seq decisions)
      (throw (ex-info "Missing index maintenance decisions." {})))
    (let [items (mapv decision-item decisions)
          id (batch-id decisions)]
      {:schema batch-packet-schema
       :batchId id
       :project-id (:project-id (first decisions))
       :goal "Resolve a bounded batch of Yggdrasil index maintenance decisions without classifying the whole project."
       :items items
       :instructions batch-instructions
       :messages (batch-prompt decisions)
       :expectedResultSchema batch-result-schema
       :expectedOutput {:schema batch-result-schema
                        :batchId id
                        :results (mapv :expectedOutput items)}})))

(defn decision-by-id
  "Find a decision by id or by its shortest unique suffix."
  [decisions value]
  (let [value (str value)
        exact (first (filter #(= value (:id %)) decisions))]
    (or exact
        (let [matches (filter #(str/ends-with? (:id %) value) decisions)]
          (when (= 1 (count matches))
            (first matches))))))

(defn- payload
  [item]
  (:payload item))

(defn- result
  [item]
  (:result item))

(defn- present?
  [value]
  (not (str/blank? (str value))))

(defn- confidence
  [value default]
  (cond
    (number? value) (double value)
    (present? value) (Double/parseDouble (str value))
    :else default))

(defn- bounded-confidence?
  [value]
  (try
    (let [value (confidence value 0.0)]
      (and (<= 0.0 value)
           (<= value 1.0)))
    (catch Exception _
      false)))

(defn- correction-patch
  [result]
  (vec (or (:correctionPatch result) [])))

(defn- patch-value
  [patch]
  (when (map? (:value patch))
    (:value patch)))

(defn- patch-target
  [patch]
  (or (:target patch)
      (:target (patch-value patch))))

(defn- patch-source
  [patch]
  (or (:source patch)
      (:source (patch-value patch))))

(defn- patch-relation
  [patch]
  (or (:relation patch)
      (:relation (patch-value patch))))

(defn- patch-visibility
  [patch]
  (or (:visibility patch)
      (:visibility (patch-value patch))))

(defn- patch-kind
  [patch]
  (or (:kind (patch-value patch))
      (:kind patch)
      (when-not (map? (:value patch))
        (:value patch))))

(defn- target-system-id
  [packet]
  (or (get-in packet [:decision :target])
      (get-in packet [:decision :data :system :xt/id])))

(defn- target-system-row
  [packet]
  (let [system (get-in packet [:decision :data :system])
        target (target-system-id packet)]
    (cond-> {:id target
             :label (or (:label system) target)
             :kind (or (some-> (:kind system) s) "system")
             :status "accepted"
             :provenance "index-maintenance-classification"}
      (:repo-id system) (assoc :repo (:repo-id system))
      (:path-prefix system) (assoc :pathPrefix (:path-prefix system)
                                   :includes [{:repo (:repo-id system)
                                               :path (:path-prefix system)}]))))

(defn- target-system?
  [packet target]
  (= (s target) (s (target-system-id packet))))

(defn- patch-errors
  [packet patch idx]
  (let [op (s (:op patch))
        allowed-ops (packet-allowed-actions packet)
        target (patch-target patch)
        source (patch-source patch)
        edge-target (patch-target patch)
        relation (patch-relation patch)
        visibility (patch-visibility patch)
        kind (patch-kind patch)]
    (vec
     (remove nil?
             [(when-not (contains? allowed-ops op)
                {:path [:correctionPatch idx :op]
                 :error "Index maintenance correction patch op is not allowed for this decision."
                 :value op})
              (when (and (#{"accept-system" "reject-system" "set-system-kind"} op)
                         (not (target-system? packet target)))
                {:path [:correctionPatch idx :target]
                 :error "System patch target must match the decision target."
                 :value target})
              (when (and (#{"add-edge" "set-edge-visibility"} op)
                         (not (and (present? source)
                                   (present? edge-target)
                                   (present? relation))))
                {:path [:correctionPatch idx]
                 :error "Edge patch requires source, target, and relation."})
              (when (and (= "set-system-kind" op)
                         (not (present? kind)))
                {:path [:correctionPatch idx :value :kind]
                 :error "set-system-kind requires kind."})
              (when (and (= "set-edge-visibility" op)
                         (not (present? visibility)))
                {:path [:correctionPatch idx :visibility]
                 :error "set-edge-visibility requires visibility."})
              (when (and (contains? patch :confidence)
                         (not (bounded-confidence? (:confidence patch))))
                {:path [:correctionPatch idx :confidence]
                 :error "Confidence must be between 0 and 1."
                 :value (:confidence patch)})
              (when (and (not= "none" op)
                         (not (present? (:reason patch))))
                {:path [:correctionPatch idx :reason]
                 :error "Patch reason is required."})]))))

(defn- validate-single-result
  [item]
  (let [packet (payload item)
        result (result item)
        recommendation (s (:recommendation result))]
    (vec
     (concat
      (remove nil?
              [(when-not (= "done" (queue/status-name (:status item)))
                 {:path [:status]
                  :error "Only done queue items can be applied."
                  :value (:status item)})
               (when-not (= packet-schema (:schema packet))
                 {:path [:payload :schema]
                  :error "Work item payload is not an index maintenance frontier decision packet."
                  :value (:schema packet)})
               (when-not (= schema (:schema result))
                 {:path [:result :schema]
                  :error "Result is not an index maintenance classification."
                  :value (:schema result)})
               (when-not (= (:decisionId packet) (:decisionId result))
                 {:path [:result :decisionId]
                  :error "Result decisionId does not match the packet."
                  :value (:decisionId result)})
               (when-not (contains? allowed-recommendations recommendation)
                 {:path [:result :recommendation]
                  :error "Result recommendation is required and must be supported."
                  :value recommendation})
               (when-not (present? (:reason result))
                 {:path [:result :reason]
                  :error "Result reason is required."})
               (when (and (contains? result :confidence)
                          (not (bounded-confidence? (:confidence result))))
                 {:path [:result :confidence]
                  :error "Result confidence must be between 0 and 1."
                  :value (:confidence result)})])
      (mapcat (fn [[idx patch]]
                (patch-errors packet patch idx))
              (map-indexed vector (correction-patch result)))))))

(defn- single-packet-from-batch-item
  [item]
  {:schema packet-schema
   :decisionId (:decisionId item)
   :project-id (get-in item [:decision :project-id])
   :goal "Resolve one bounded Yggdrasil index maintenance decision without classifying the whole project."
   :decision (:decision item)
   :allowedActions (:allowedActions item)
   :instructions decision-instructions
   :expectedResultSchema schema
   :expectedOutput (expected-output (:decisionId item))})

(defn- result-decision-id
  [result]
  (s (:decisionId result)))

(defn- index-by
  [f xs]
  (into {}
        (keep (fn [x]
                (when-let [k (f x)]
                  [k x])))
        xs))

(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (keep (fn [[value n]]
               (when (< 1 n) value)))
       vec))

(defn- nested-result-error
  [idx error]
  (let [path (:path error)
        path (if (= :result (first path))
               (rest path)
               path)]
    (assoc error :path (into [:result :results idx] path))))

(defn- validate-batch-result
  [item]
  (let [packet (payload item)
        result (result item)
        items (vec (:items packet))
        results (vec (:results result))
        item-ids (mapv (comp s :decisionId) items)
        result-ids (mapv result-decision-id results)
        items-by-id (index-by (comp s :decisionId) items)
        missing (vec (remove (set result-ids) item-ids))
        extra (vec (remove (set item-ids) result-ids))
        duplicates (duplicate-values result-ids)]
    (vec
     (concat
      (remove nil?
              [(when-not (= "done" (queue/status-name (:status item)))
                 {:path [:status]
                  :error "Only done queue items can be applied."
                  :value (:status item)})
               (when-not (= batch-packet-schema (:schema packet))
                 {:path [:payload :schema]
                  :error "Work item payload is not an index maintenance decision batch packet."
                  :value (:schema packet)})
               (when-not (= batch-result-schema (:schema result))
                 {:path [:result :schema]
                  :error "Result is not an index maintenance classification batch."
                  :value (:schema result)})
               (when-not (= (:batchId packet) (:batchId result))
                 {:path [:result :batchId]
                  :error "Result batchId does not match the packet."
                  :value (:batchId result)})
               (when-not (vector? (:results result))
                 {:path [:result :results]
                  :error "Batch result must include a results vector."
                  :value (:results result)})
               (when (seq missing)
                 {:path [:result :results]
                  :error "Batch result is missing decision results."
                  :value missing})
               (when (seq extra)
                 {:path [:result :results]
                  :error "Batch result includes unknown decision ids."
                  :value extra})
               (when (seq duplicates)
                 {:path [:result :results]
                  :error "Batch result includes duplicate decision ids."
                  :value duplicates})])
      (mapcat (fn [[idx result]]
                (if-let [decision-item (get items-by-id (result-decision-id result))]
                  (map #(nested-result-error idx %)
                       (validate-single-result
                        {:status (:status item)
                         :payload (single-packet-from-batch-item decision-item)
                         :result result}))
                  []))
              (map-indexed vector results))))))

(defn validate-result
  "Return validation errors for applying item result, or an empty vector."
  [item]
  (case (get-in item [:payload :schema])
    "ygg.frontier.decision-batch/v1" (validate-batch-result item)
    (validate-single-result item)))

(defn- upsert-system
  [overlay system]
  (let [system-id (:id system)]
    (if (some #(= system-id (:id %)) (:systems overlay))
      (update overlay
              :systems
              (fn [systems]
                (mapv #(if (= system-id (:id %))
                         (merge % system)
                         %)
                      systems)))
      (update overlay :systems (fnil conj []) system))))

(defn- patch->edge
  [patch]
  (let [op (s (:op patch))
        visibility (if (= "set-edge-visibility" op)
                     (patch-visibility patch)
                     (:visibility patch))]
    (cond-> {:source (s (patch-source patch))
             :target (s (patch-target patch))
             :relation (s (patch-relation patch))
             :confidence (confidence (:confidence patch) 1.0)
             :rules "index-maintenance-classification"}
      (:reason patch) (assoc :reason (:reason patch))
      visibility (assoc :visibility (s visibility))
      (:importance patch) (assoc :importance (s (:importance patch))))))

(defn- apply-patch
  [overlay packet patch]
  (let [op (s (:op patch))
        target (s (patch-target patch))
        reason (:reason patch)]
    (case op
      "none"
      overlay

      "accept-system"
      (upsert-system overlay (cond-> (target-system-row packet)
                               true (assoc :status "accepted")
                               reason (assoc :reason reason)))

      "reject-system"
      (correction-overlay/add-reject overlay {:id target} reason)

      "set-system-kind"
      (upsert-system overlay (assoc (cond-> (target-system-row packet)
                                      reason (assoc :reason reason))
                                    :kind (s (patch-kind patch))
                                    :status "accepted"))

      "reject-external-api"
      (correction-overlay/add-reject overlay {:kind "external-api"
                                              :host target}
                                     reason)

      ("add-edge" "set-edge-visibility")
      (correction-overlay/add-edge overlay (patch->edge patch))

      overlay)))

(defn- apply-patches
  [overlay packet result]
  (reduce #(apply-patch %1 packet %2)
          overlay
          (correction-patch result)))

(defn- batch-results-by-id
  [result]
  (index-by result-decision-id (:results result)))

(defn- apply-batch-patches
  [overlay packet result]
  (let [results-by-id (batch-results-by-id result)]
    (reduce (fn [overlay item]
              (let [single-packet (single-packet-from-batch-item item)
                    single-result (get results-by-id (:decisionId item))]
                (apply-patches overlay single-packet single-result)))
            overlay
            (:items packet))))

(defn apply-work-result!
  "Validate and apply a completed index maintenance decision as correction facts."
  [xtdb root id]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        errors (validate-result item)]
    (if (seq errors)
      (let [failed (queue/fail! root
                                (:id item)
                                (str "Invalid index maintenance classification: "
                                     (str/join "; " (map :error errors))))]
        {:schema apply-schema
         :status "failed"
         :errors errors
         :item (queue/item-summary failed)})
      (let [packet (payload item)
            result (result item)
            batch? (= batch-packet-schema (:schema packet))
            write-result (corrections-api/apply-overlay!
                          xtdb
                          (:project-id packet)
                          (if batch?
                            #(apply-batch-patches % packet result)
                            #(apply-patches % packet result))
                          {:source (if batch?
                                     (:batchId packet)
                                     (:decisionId packet))})]
        (cond-> {:schema apply-schema
                 :status "applied"
                 :workId (:id item)
                 :projectId (:project-id packet)
                 :correctionFacts (count (:rows write-result))
                 :patchesApplied (reduce + (map #(max 0
                                                      (- (get-in write-result [:after %] 0)
                                                         (get-in write-result [:before %] 0)))
                                                [:systems :rejects :edges]))}
          batch? (assoc :batchId (:batchId packet)
                        :decisionCount (count (:items packet)))
          (not batch?) (assoc :decisionId (:decisionId packet)))))))
