(ns ygg.infra-review
  "Bounded infrastructure graph review packets and result application."
  (:require [ygg.hash :as hash]
            [ygg.corrections-api :as corrections-api]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.queue :as queue]
            [charred.api :as json]
            [clojure.string :as str]))

(def packet-schema
  "ygg.infra.review-packet/v1")

(def result-schema
  "ygg.infra.review-result/v1")

(def apply-schema
  "ygg.sync.work.apply/v1")

(def work-kind
  "infra-review")

(def ^:private container-image-kinds
  #{:container-image-producer :container-image-consumer})

(def ^:private default-system-limit
  30)

(def ^:private allowed-relations
  #{"deploys"
    "calls-http"
    "calls-external-api"
    "references"
    "shares-config"
    "code-depends-on"})

(def ^:private allowed-visibilities
  #{"primary" "secondary" "noise"})

(def ^:private allowed-recommendations
  #{"add-correction-edge" "no-change" "needs-human" "needs-scanner"})

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

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

(defn- system-summary
  [system]
  (cond-> {:id (:xt/id system)
           :label (:label system)
           :kind (s (:kind system))
           :repo (:repo-id system)
           :path (:path-prefix system)
           :metrics (:metrics system)}
    (:repo-role system) (assoc :repoRole (s (:repo-role system)))))

(defn- evidence-summary
  [row]
  {:id (:xt/id row)
   :kind (s (:kind row))
   :systemId (:system-id row)
   :repo (:repo-id row)
   :path (:path row)
   :line (:source-line row)
   :label (:label row)
   :normalizedValue (:normalized-value row)
   :confidence (:confidence row)})

(defn- metric
  [system k]
  (long (or (get-in system [:metrics k]) 0)))

(defn- packet-systems
  [systems artifact-rows]
  (let [artifact-system-ids (set (map :system-id artifact-rows))]
    (->> systems
         (sort-by (fn [system]
                    [(if (contains? artifact-system-ids (:xt/id system)) 0 1)
                     (- (metric system :file-count))
                     (- (metric system :node-count))
                     (:repo-id system)
                     (:label system)]))
         (take default-system-limit)
         (mapv system-summary))))

(defn- packet-id
  [project-id basis kind artifact evidence-ids]
  (str "infra-review:"
       (hash/short-hash [project-id kind artifact evidence-ids (:hash basis)])))

(defn- unmatched-kind
  [producers consumers]
  (cond
    (seq producers) "container-image-producer-without-consumer"
    (seq consumers) "container-image-consumer-without-producer"
    :else "container-image-unmatched"))

(defn- review-packet
  [{:keys [project-id basis systems artifact producers consumers]}]
  (let [rows (vec (concat producers consumers))
        evidence-ids (mapv :xt/id rows)
        kind (unmatched-kind producers consumers)
        review-id (packet-id project-id basis kind artifact evidence-ids)
        facts {:artifact {:key artifact
                          :producerCount (count producers)
                          :consumerCount (count consumers)}
               :systems (packet-systems systems rows)
               :producerSystemIds (->> producers (map :system-id) distinct sort vec)
               :consumerSystemIds (->> consumers (map :system-id) distinct sort vec)
               :evidence (mapv evidence-summary rows)}
        expected-output {:schema result-schema
                         :reviewId review-id
                         :recommendation "add-correction-edge|no-change|needs-human|needs-scanner"
                         :confidence 0.0
                         :reason "brief rationale"
                         :correctionPatch []
                         :findings []}]
    {:schema packet-schema
     :reviewId review-id
     :project-id project-id
     :goal "Review one bounded infrastructure fact gap and return explicit JSON only."
     :kind kind
     :question "Do these infrastructure facts imply an accepted project-level graph correction?"
     :artifact artifact
     :basis basis
     :facts facts
     :allowedActions ["add-edge" "set-edge-visibility" "reject-edge" "none"]
     :expectedResultSchema result-schema
     :expectedOutput expected-output
     :messages [{:role "system"
                 :content (str "You review one Yggdrasil infrastructure packet. "
                               "Return JSON only. Use only ids and evidence from the packet. "
                               "Do not classify the whole repository.")}
                {:role "user"
                 :content (str "Return this JSON shape:\n"
                               (json/write-json-str expected-output {:indent-str "  "})
                               "\n\nIf evidence is insufficient, return no correctionPatch and add a finding."
                               "\n\nPacket:\n"
                               (json/write-json-str {:reviewId review-id
                                                     :kind kind
                                                     :artifact artifact
                                                     :basis basis
                                                     :facts facts
                                                     :allowedActions ["add-edge"
                                                                      "set-edge-visibility"
                                                                      "reject-edge"
                                                                      "none"]}
                                                    {:indent-str "  "}))}]}))

(defn review-packets
  "Return bounded infra-review packets from mechanical infrastructure evidence."
  [{:keys [project-id basis systems evidence]}]
  (let [by-artifact (->> evidence
                         (filter #(contains? container-image-kinds (:kind %)))
                         (group-by :normalized-value))]
    (->> by-artifact
         (keep (fn [[artifact rows]]
                 (let [producers (filterv #(= :container-image-producer (:kind %)) rows)
                       consumers (filterv #(= :container-image-consumer (:kind %)) rows)]
                   (when (not= (boolean (seq producers)) (boolean (seq consumers)))
                     (review-packet {:project-id project-id
                                     :basis basis
                                     :systems systems
                                     :artifact artifact
                                     :producers producers
                                     :consumers consumers})))))
         (sort-by (juxt :kind :artifact :reviewId))
         vec)))

(defn- payload
  [item]
  (:payload item))

(defn- result
  [item]
  (:result item))

(defn- packet-evidence-ids
  [packet]
  (set (map :id (get-in packet [:facts :evidence]))))

(defn- packet-system-ids
  [packet]
  (set (map :id (get-in packet [:facts :systems]))))

(defn- allowed-actions
  [packet]
  (set (map s (:allowedActions packet))))

(defn- correction-patch
  [result]
  (vec (or (:correctionPatch result) [])))

(defn- patch-evidence
  [patch]
  (vec (or (:evidence patch)
           (:evidenceIds patch)
           (:evidence-ids patch)
           [])))

(defn- patch-errors
  [packet patch idx]
  (let [op (s (:op patch))
        actions (allowed-actions packet)
        system-ids (packet-system-ids packet)
        evidence-ids (packet-evidence-ids packet)
        source (s (:source patch))
        target (s (:target patch))
        relation (s (:relation patch))
        visibility (s (:visibility patch))
        evidence (patch-evidence patch)]
    (vec
     (remove nil?
             [(when-not (contains? actions op)
                {:path [:correctionPatch idx :op]
                 :error "Patch op is not allowed by the packet."
                 :value op})
              (when (and (not= "none" op)
                         (not (contains? #{"add-edge" "set-edge-visibility" "reject-edge"} op)))
                {:path [:correctionPatch idx :op]
                 :error "Unsupported patch op."
                 :value op})
              (when (and (#{"add-edge" "set-edge-visibility" "reject-edge"} op)
                         (not (contains? system-ids source)))
                {:path [:correctionPatch idx :source]
                 :error "Patch source must be one of facts.systems[].id."
                 :value source})
              (when (and (#{"add-edge" "set-edge-visibility" "reject-edge"} op)
                         (not (contains? system-ids target)))
                {:path [:correctionPatch idx :target]
                 :error "Patch target must be one of facts.systems[].id."
                 :value target})
              (when (and (#{"add-edge" "set-edge-visibility" "reject-edge"} op)
                         (= source target))
                {:path [:correctionPatch idx]
                 :error "Patch source and target must differ."})
              (when (and (#{"add-edge" "set-edge-visibility" "reject-edge"} op)
                         (not (contains? allowed-relations relation)))
                {:path [:correctionPatch idx :relation]
                 :error "Unsupported relation for infra-review correction patch."
                 :value relation})
              (when (and (= "set-edge-visibility" op)
                         (not (contains? allowed-visibilities visibility)))
                {:path [:correctionPatch idx :visibility]
                 :error "Unsupported edge visibility."
                 :value visibility})
              (when (and (contains? patch :confidence)
                         (not (bounded-confidence? (:confidence patch))))
                {:path [:correctionPatch idx :confidence]
                 :error "Confidence must be between 0 and 1."
                 :value (:confidence patch)})
              (when (and (#{"add-edge" "set-edge-visibility" "reject-edge"} op)
                         (empty? evidence))
                {:path [:correctionPatch idx :evidence]
                 :error "Edge patch must cite at least one facts.evidence[].id."})
              (when-let [missing (seq (remove evidence-ids evidence))]
                {:path [:correctionPatch idx :evidence]
                 :error "Patch evidence must come from facts.evidence[].id."
                 :value (vec missing)})]))))

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
                  :error "Work item payload is not an infra-review packet."
                  :value (:schema packet)})
               (when-not (= result-schema (:schema result))
                 {:path [:result :schema]
                  :error "Result is not an infra-review result."
                  :value (:schema result)})
               (when-not (= (:reviewId packet) (:reviewId result))
                 {:path [:result :reviewId]
                  :error "Result reviewId does not match the packet."
                  :value (:reviewId result)})
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

(defn- patch->edge
  [packet patch]
  (let [op (s (:op patch))]
    (when (contains? #{"add-edge" "set-edge-visibility" "reject-edge"} op)
      (let [visibility (case op
                         "reject-edge" "noise"
                         "set-edge-visibility" (s (:visibility patch))
                         nil)]
        (cond-> {:source (s (:source patch))
                 :target (s (:target patch))
                 :relation (s (:relation patch))
                 :confidence (confidence (:confidence patch) 1.0)
                 :rules (:reviewId packet)
                 :evidence (patch-evidence patch)}
          (:reason patch) (assoc :reason (:reason patch))
          visibility (assoc :visibility visibility)
          (:importance patch) (assoc :importance (s (:importance patch)))
          (:salience patch) (assoc :salience (:salience patch))
          (seq (:tags patch)) (assoc :tags (mapv s (:tags patch))))))))

(defn- apply-patches
  [overlay packet result]
  (reduce (fn [overlay patch]
            (if-let [edge (patch->edge packet patch)]
              (correction-overlay/add-edge overlay edge)
              overlay))
          overlay
          (correction-patch result)))

(defn apply-work-result!
  "Validate and apply a completed infra-review queue item as correction facts."
  [xtdb root id]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        errors (validate-result item)]
    (if (seq errors)
      (let [failed (queue/fail! root
                                (:id item)
                                (str "Invalid infra-review result: "
                                     (str/join "; " (map :error errors))))]
        {:schema apply-schema
         :status "failed"
         :errors errors
         :item (queue/item-summary failed)})
      (let [packet (payload item)
            result (result item)
            write-result (corrections-api/apply-overlay!
                          xtdb
                          (:project-id packet)
                          #(apply-patches % packet result)
                          {:source (:reviewId packet)})]
        {:schema apply-schema
         :status "applied"
         :workId (:id item)
         :reviewId (:reviewId packet)
         :projectId (:project-id packet)
         :correctionFacts (count (:rows write-result))
         :patchesApplied (max 0 (- (get-in write-result [:after :edges] 0)
                                   (get-in write-result [:before :edges] 0)))}))))
