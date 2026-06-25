(ns ygg.dependency-review
  "Bounded dependency import review packets and result application."
  (:require [ygg.hash :as hash]
            [ygg.corrections-api :as corrections-api]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.queue :as queue]
            [charred.api :as json]
            [clojure.string :as str]))

(def packet-schema
  "ygg.dependency.review-packet/v1")

(def result-schema
  "ygg.dependency.review-result/v1")

(def apply-schema
  "ygg.sync.work.apply/v1")

(def work-kind
  "dependency-review")

(def ^:private default-package-limit
  40)

(def ^:private default-packet-limit
  20)

(def ^:private allowed-recommendations
  #{"add-package-import" "no-change" "needs-human" "needs-scanner"})

(def ^:private review-instructions
  ["Resolve only this one unresolved import review packet."
   "Most packets should be straightforward: add one import-to-package correction, or return no patch when the package evidence is not enough."
   "Choose packages only from facts.packages[], and cite evidence only from facts.evidence[].id."
   "Do not classify the repository, infer broad architecture, edit files, update queue state, or invent ids."
   "Return exactly one JSON object matching expectedResultSchema. If unsure, use no-change, needs-human, or needs-scanner with correctionPatch []."])

(defn- instructions-text
  [instructions]
  (str "Instructions:\n"
       (str/join "\n" (map #(str "- " %) instructions))))

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- lower-string
  [value]
  (some-> value s str/lower-case))

(defn- normalized-token
  [value]
  (some-> value
          lower-string
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"(^-+|-+$)" "")))

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

(defn- evidence-id
  [project-id unresolved]
  (str "dependency-evidence:"
       (hash/short-hash [project-id
                         (:repo-id unresolved)
                         (:path unresolved)
                         (:line unresolved)
                         (:import unresolved)
                         (:source-id unresolved)
                         (:target-id unresolved)])))

(defn- evidence-row
  [project-id unresolved]
  {:id (evidence-id project-id unresolved)
   :kind "unresolved-import"
   :repo (:repo-id unresolved)
   :sourceId (:source-id unresolved)
   :sourceLabel (:source-label unresolved)
   :targetId (:target-id unresolved)
   :import (:import unresolved)
   :path (:path unresolved)
   :line (:line unresolved)
   :fileKind (s (:kind unresolved))})

(defn- package-summary
  [package]
  (cond-> (select-keys package
                       [:id
                        :label
                        :ecosystem
                        :package-name
                        :version-range
                        :dependency-scope
                        :declared-by
                        :resolved-versions
                        :imported-by])
    (pos? (long (or (:candidateScore package) 0)))
    (assoc :candidateScore (:candidateScore package))

    (seq (:candidateSignals package))
    (assoc :candidateSignals (:candidateSignals package))))

(defn- stripped-label
  [{:keys [ecosystem label]}]
  (let [ecosystem (lower-string ecosystem)
        label (s label)
        label-lower (lower-string label)
        prefix (str ecosystem ":")]
    (cond
      (str/blank? label) nil
      (and (seq ecosystem)
           (str/starts-with? label-lower prefix))
      (subs label (count prefix))
      :else label)))

(defn- package-name-values
  [package]
  (->> [(:package-name package)
        (stripped-label package)
        (:label package)]
       (keep s)
       (remove str/blank?)
       distinct
       vec))

(defn- package-name-prefixes
  [package]
  (->> (package-name-values package)
       (mapcat (fn [package-name]
                 (let [package-name (str/lower-case package-name)
                       colon (.indexOf package-name ":")]
                   (cond-> [package-name]
                     (pos? colon) (conj (subs package-name 0 colon))))))
       (remove str/blank?)
       distinct
       vec))

(defn- import-prefix?
  [candidate import-name]
  (let [candidate (lower-string candidate)
        import-name (lower-string import-name)]
    (and (present? candidate)
         (present? import-name)
         (or (= candidate import-name)
             (str/starts-with? import-name (str candidate "."))
             (str/starts-with? import-name (str candidate "::"))
             (str/starts-with? import-name (str candidate "/"))))))

(defn- candidate-signal
  [import-name prefix]
  (let [import-name (lower-string import-name)
        prefix (lower-string prefix)]
    (cond
      (= prefix import-name)
      {:kind "exact-import"
       :value prefix
       :score 100}

      (import-prefix? prefix import-name)
      {:kind "import-prefix"
       :value prefix
       :score 90}

      (= (normalized-token prefix) (normalized-token import-name))
      {:kind "normalized-name"
       :value prefix
       :score 70})))

(defn- distinct-signals
  [signals]
  (->> signals
       (reduce (fn [out signal]
                 (if (contains? (:seen out) [(:kind signal) (:value signal)])
                   out
                   (-> out
                       (update :seen conj [(:kind signal) (:value signal)])
                       (update :signals conj signal))))
               {:seen #{}
                :signals []})
       :signals))

(defn- package-candidate
  [import-name idx package]
  (let [signals (->> (package-name-prefixes package)
                     (keep #(candidate-signal import-name %))
                     distinct-signals)
        score (reduce max 0 (map :score signals))]
    (cond-> (assoc package
                   :candidateScore score
                   ::candidate-index idx)
      (seq signals) (assoc :candidateSignals signals))))

(defn- ranked-package-candidates
  [unresolved packages]
  (->> packages
       (map-indexed #(package-candidate (:import unresolved) %1 %2))
       (sort-by (juxt (comp - :candidateScore) ::candidate-index))
       (mapv #(dissoc % ::candidate-index))))

(defn- package-candidate-selection
  [unresolved packages limit total-packages]
  (let [ranked (ranked-package-candidates unresolved packages)
        selected (vec (take limit ranked))]
    {:packages selected
     :selection {:totalPackages total-packages
                 :includedPackages (count selected)
                 :packageLimit limit
                 :truncated (> total-packages (count selected))
                 :selectionBasis "mechanical-import-package-string-signals"
                 :matchingPackages (count (filter #(pos? (:candidateScore %))
                                                  ranked))}}))

(defn- packet-id
  [project-id basis unresolved package-ids]
  (str "dependency-review:"
       (hash/short-hash [project-id
                         (:repo-id unresolved)
                         (:path unresolved)
                         (:line unresolved)
                         (:import unresolved)
                         package-ids
                         (:hash basis)])))

(defn- review-packet
  [{:keys [project-id basis unresolved packages package-selection]}]
  (let [package-ids (mapv :id packages)
        review-id (packet-id project-id basis unresolved package-ids)
        evidence [(evidence-row project-id unresolved)]
        facts {:unresolvedImport (select-keys unresolved
                                              [:repo-id
                                               :source-id
                                               :source-label
                                               :target-id
                                               :import
                                               :path
                                               :line
                                               :kind])
               :packages (mapv package-summary packages)
               :packageSelection package-selection
               :evidence evidence}
        expected-output {:schema result-schema
                         :reviewId review-id
                         :recommendation "add-package-import|no-change|needs-human|needs-scanner"
                         :confidence 0.0
                         :reason "brief rationale"
                         :correctionPatch []
                         :findings []}]
    {:schema packet-schema
     :reviewId review-id
     :project-id project-id
     :goal "Review one unresolved source import and return explicit JSON only."
     :kind "unresolved-import"
     :question "Does this unresolved import require an accepted import-to-package correction?"
     :basis basis
     :facts facts
     :allowedActions ["add-package-import" "none"]
     :instructions review-instructions
     :expectedResultSchema result-schema
     :expectedOutput expected-output
     :messages [{:role "system"
                 :content (str "You review one Yggdrasil dependency packet. "
                               "Return JSON only. Use only package ids and evidence from the packet. "
                               "Do not infer repository architecture or classify the whole project.")}
                {:role "user"
                 :content (str (instructions-text review-instructions)
                               "\n\nReturn this JSON shape:\n"
                               (json/write-json-str expected-output {:indent-str "  "})
                               "\n\nIf evidence is insufficient, return no correctionPatch and add a finding."
                               "\n\nPacket:\n"
                               (json/write-json-str {:reviewId review-id
                                                     :kind "unresolved-import"
                                                     :instructions review-instructions
                                                     :basis basis
                                                     :facts facts
                                                     :allowedActions ["add-package-import"
                                                                      "none"]}
                                                    {:indent-str "  "}))}]}))

(defn review-packets
  "Return bounded dependency-review packets from a package report."
  [{:keys [project-id basis package-report limit]
    :or {limit default-packet-limit}}]
  (let [all-packages (vec (:packages package-report))
        total-packages (long (or (get-in package-report [:counts :packages])
                                 (count all-packages)))]
    (->> (:unresolved-imports package-report)
         (take limit)
         (mapv (fn [unresolved]
                 (let [{:keys [packages selection]}
                       (package-candidate-selection unresolved
                                                    all-packages
                                                    default-package-limit
                                                    total-packages)]
                   (review-packet {:project-id project-id
                                   :basis basis
                                   :unresolved unresolved
                                   :packages packages
                                   :package-selection selection})))))))

(defn- payload
  [item]
  (:payload item))

(defn- result
  [item]
  (:result item))

(defn- correction-patch
  [result]
  (vec (or (:correctionPatch result) [])))

(defn- patch-evidence
  [patch]
  (vec (or (:evidence patch)
           (:evidenceIds patch)
           (:evidence-ids patch)
           [])))

(defn- packet-evidence-ids
  [packet]
  (set (map :id (get-in packet [:facts :evidence]))))

(defn- packet-package-keys
  [packet]
  (->> (get-in packet [:facts :packages])
       (map (fn [package]
              [(s (:ecosystem package)) (s (:package-name package))]))
       set))

(defn- patch-errors
  [packet patch idx]
  (let [op (s (:op patch))
        unresolved-import (get-in packet [:facts :unresolvedImport :import])
        evidence-ids (packet-evidence-ids packet)
        package-keys (packet-package-keys packet)
        ecosystem (s (:ecosystem patch))
        package (s (:package patch))
        import-name (s (:import patch))
        evidence (patch-evidence patch)]
    (vec
     (remove nil?
             [(when-not (contains? #{"add-package-import" "none"} op)
                {:path [:correctionPatch idx :op]
                 :error "Patch op is not allowed by the packet."
                 :value op})
              (when (and (= "add-package-import" op)
                         (not (import-prefix? import-name unresolved-import)))
                {:path [:correctionPatch idx :import]
                 :error "Patch import must be the unresolved import or a segment prefix of it."
                 :value import-name})
              (when (and (= "add-package-import" op)
                         (not (contains? package-keys [ecosystem package])))
                {:path [:correctionPatch idx :package]
                 :error "Patch package must be one of facts.packages[]."
                 :value {:ecosystem ecosystem
                         :package package}})
              (when (and (= "add-package-import" op)
                         (empty? evidence))
                {:path [:correctionPatch idx :evidence]
                 :error "Package import patch must cite at least one facts.evidence[].id."})
              (when-let [missing (seq (remove evidence-ids evidence))]
                {:path [:correctionPatch idx :evidence]
                 :error "Patch evidence must come from facts.evidence[].id."
                 :value (vec missing)})
              (when (and (= "add-package-import" op)
                         (not (present? (:reason patch))))
                {:path [:correctionPatch idx :reason]
                 :error "Patch reason is required."})
              (when (and (contains? patch :confidence)
                         (not (bounded-confidence? (:confidence patch))))
                {:path [:correctionPatch idx :confidence]
                 :error "Confidence must be between 0 and 1."
                 :value (:confidence patch)})]))))

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
                  :error "Work item payload is not a dependency-review packet."
                  :value (:schema packet)})
               (when-not (= result-schema (:schema result))
                 {:path [:result :schema]
                  :error "Result is not a dependency-review result."
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

(defn- patch->package-import
  [packet result patch]
  (when (= "add-package-import" (s (:op patch)))
    (cond-> {:repo (get-in packet [:facts :unresolvedImport :repo-id])
             :import (s (:import patch))
             :ecosystem (s (:ecosystem patch))
             :package (s (:package patch))
             :evidence (patch-evidence patch)
             :rules (:reviewId packet)
             :reviewId (:reviewId packet)}
      (or (contains? patch :confidence)
          (contains? result :confidence))
      (assoc :confidence (confidence (or (:confidence patch)
                                         (:confidence result))
                                     1.0))
      (:reason patch) (assoc :reason (:reason patch)))))

(defn- apply-patch
  [overlay packet result patch]
  (if-let [package-import (patch->package-import packet result patch)]
    (correction-overlay/add-package-import overlay package-import)
    overlay))

(defn- apply-patches
  [overlay packet result]
  (reduce #(apply-patch %1 packet result %2)
          overlay
          (correction-patch result)))

(defn apply-work-result!
  "Validate and apply a completed dependency-review queue item as correction facts."
  [xtdb root id]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        errors (validate-result item)]
    (if (seq errors)
      (let [failed (queue/fail! root
                                (:id item)
                                (str "Invalid dependency review: "
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
         :patchesApplied (max 0 (- (get-in write-result [:after :packageImports] 0)
                                   (get-in write-result [:before :packageImports] 0)))}))))
