(ns agraph.system.decision-classifier
  "Focused classifier for one maintenance decision at a time."
  (:require [agraph.map :as graph-map]
            [agraph.queue :as queue]
            [charred.api :as json]
            [clojure.string :as str]))

(def schema
  "agraph.maintenance.classification/v1")

(def packet-schema
  "agraph.maintenance.decision-packet/v1")

(def apply-schema
  "agraph.sync.work.apply/v1")

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

(defn prompt
  "Return OpenAI-compatible chat messages for one maintenance decision."
  [decision]
  (let [allowed-actions (allowed-actions-for-decision decision)]
    [{:role "system"
      :content (str "You classify one AGraph maintenance decision. Return JSON only. "
                    "Do not classify a whole repository. Use only the provided decision data. "
                    "Prefer hiding or rejecting noisy references over promoting weak edges.")}
     {:role "user"
      :content (str
                "Return this JSON shape:\n"
                "{"
                "\"schema\":\"" schema "\","
                "\"decisionId\":\"...\","
                "\"recommendation\":\"accept|reject|change|investigate\","
                "\"confidence\":0.0,"
                "\"reason\":\"brief rationale\","
                "\"mapPatch\":[{\"op\":\"" (str/join "|" allowed-actions) "\","
                "\"target\":\"id or label\",\"value\":{},\"reason\":\"brief rationale\"}]"
                "}\n\n"
                (json/write-json-str {:decision decision
                                      :allowedActions allowed-actions}
                                     {:indent-str "  "}))}]))

(defn classify
  "Classify one maintenance decision with an OpenAI-compatible JSON client."
  [{:keys [client decision]}]
  (when-not decision
    (throw (ex-info "Missing maintenance decision." {})))
  (let [response ((:complete-json client) (prompt decision))]
    (assoc response
           :schema (or (:schema response) schema)
           :decisionId (or (:decisionId response) (:id decision)))))

(defn decision-packet
  "Return a provider-agnostic packet for one maintenance decision.

  The packet includes OpenAI-compatible messages as one possible consumer input,
  but it is plain JSON and can be processed by any human, model, or agent loop."
  [decision]
  (when-not decision
    (throw (ex-info "Missing maintenance decision." {})))
  (let [allowed-actions (allowed-actions-for-decision decision)]
    {:schema packet-schema
     :decisionId (:id decision)
     :project-id (:project-id decision)
     :goal "Resolve one bounded AGraph maintenance decision without classifying the whole graph."
     :decision decision
     :allowedActions allowed-actions
     :messages (prompt decision)
     :expectedResultSchema schema
     :expectedOutput {:schema schema
                      :decisionId (:id decision)
                      :recommendation "accept|reject|change|investigate"
                      :confidence 0.0
                      :reason "brief rationale"
                      :mapPatch []}}))

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

(defn- map-patch
  [result]
  (vec (or (:mapPatch result)
           (:map-patch result)
           [])))

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
             :provenance "maintenance-classification"}
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
                {:path [:mapPatch idx :op]
                 :error "Maintenance map patch op is not allowed for this decision."
                 :value op})
              (when (and (#{"accept-system" "reject-system" "set-system-kind"} op)
                         (not (target-system? packet target)))
                {:path [:mapPatch idx :target]
                 :error "System patch target must match the decision target."
                 :value target})
              (when (and (#{"add-edge" "set-edge-visibility"} op)
                         (not (and (present? source)
                                   (present? edge-target)
                                   (present? relation))))
                {:path [:mapPatch idx]
                 :error "Edge patch requires source, target, and relation."})
              (when (and (= "set-system-kind" op)
                         (not (present? kind)))
                {:path [:mapPatch idx :value :kind]
                 :error "set-system-kind requires kind."})
              (when (and (= "set-edge-visibility" op)
                         (not (present? visibility)))
                {:path [:mapPatch idx :visibility]
                 :error "set-edge-visibility requires visibility."})
              (when (and (contains? patch :confidence)
                         (not (bounded-confidence? (:confidence patch))))
                {:path [:mapPatch idx :confidence]
                 :error "Confidence must be between 0 and 1."
                 :value (:confidence patch)})
              (when (and (not= "none" op)
                         (not (present? (:reason patch))))
                {:path [:mapPatch idx :reason]
                 :error "Patch reason is required."})]))))

(defn validate-result
  "Return validation errors for applying item result, or an empty vector."
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
                  :error "Work item payload is not a maintenance decision packet."
                  :value (:schema packet)})
               (when-not (= schema (:schema result))
                 {:path [:result :schema]
                  :error "Result is not a maintenance classification."
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
              (map-indexed vector (map-patch result)))))))

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
             :rules "maintenance-classification"}
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
                               reason (assoc :reason reason)))

      "reject-system"
      (graph-map/add-reject overlay {:id target} reason)

      "set-system-kind"
      (upsert-system overlay (assoc (cond-> (target-system-row packet)
                                      reason (assoc :reason reason))
                                    :kind (s (patch-kind patch))
                                    :status "accepted"))

      "reject-external-api"
      (graph-map/add-reject overlay {:kind "external-api"
                                     :host target}
                            reason)

      ("add-edge" "set-edge-visibility")
      (graph-map/add-edge overlay (patch->edge patch))

      overlay)))

(defn- apply-patches
  [overlay packet result]
  (reduce #(apply-patch %1 packet %2)
          overlay
          (map-patch result)))

(defn- overlay-counts
  [overlay]
  {:systems (count (:systems overlay))
   :reject (count (:reject overlay))
   :edges (count (:edges overlay))})

(defn apply-work-result!
  "Validate and apply a completed maintenance-decision queue item to a map file."
  [root id map-path]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        errors (validate-result item)]
    (if (seq errors)
      (let [failed (queue/fail! root
                                (:id item)
                                (str "Invalid maintenance classification: "
                                     (str/join "; " (map :error errors))))]
        {:schema apply-schema
         :status "failed"
         :errors errors
         :item (queue/item-summary failed)})
      (let [packet (payload item)
            result (result item)
            overlay (if (graph-map/file-exists? map-path)
                      (graph-map/read-map map-path)
                      (graph-map/empty-map (:project-id packet)))
            before (overlay-counts overlay)
            updated (apply-patches overlay packet result)
            after (overlay-counts updated)
            written (graph-map/write-map! map-path updated)]
        {:schema apply-schema
         :status "applied"
         :workId (:id item)
         :decisionId (:decisionId packet)
         :mapPath written
         :patchesApplied (reduce + (map #(max 0 (- (get after %) (get before %)))
                                        [:systems :reject :edges]))}))))
