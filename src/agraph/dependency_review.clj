(ns agraph.dependency-review
  "Bounded dependency import review packets and result application."
  (:require [agraph.hash :as hash]
            [agraph.map :as graph-map]
            [agraph.queue :as queue]
            [charred.api :as json]
            [clojure.string :as str]))

(def packet-schema
  "agraph.dependency.review-packet/v1")

(def result-schema
  "agraph.dependency.review-result/v1")

(def apply-schema
  "agraph.sync.work.apply/v1")

(def work-kind
  "dependency-review")

(def ^:private default-package-limit
  40)

(def ^:private default-packet-limit
  20)

(def ^:private allowed-recommendations
  #{"add-package-import" "no-change" "needs-human" "needs-scanner"})

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
  (select-keys package
               [:id
                :label
                :ecosystem
                :package-name
                :declared-by
                :resolved-versions
                :imported-by]))

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
                         :mapPatch []}]
    {:schema packet-schema
     :reviewId review-id
     :project-id project-id
     :goal "Review one unresolved source import and return explicit JSON only."
     :kind "unresolved-import"
     :question "Does this unresolved import require an accepted import-to-package map correction?"
     :basis basis
     :facts facts
     :allowedActions ["add-package-import" "none"]
     :expectedResultSchema result-schema
     :expectedOutput expected-output
     :messages [{:role "system"
                 :content (str "You review one AGraph dependency packet. "
                               "Return JSON only. Use only package ids and evidence from the packet. "
                               "Do not infer repository architecture or classify the whole project.")}
                {:role "user"
                 :content (str "Return this JSON shape:\n"
                               (json/write-json-str expected-output {:indent-str "  "})
                               "\n\nIf evidence is insufficient, return no mapPatch and add a finding."
                               "\n\nPacket:\n"
                               (json/write-json-str {:reviewId review-id
                                                     :kind "unresolved-import"
                                                     :basis basis
                                                     :facts facts
                                                     :allowedActions ["add-package-import"
                                                                      "none"]}
                                                    {:indent-str "  "}))}]}))

(defn review-packets
  "Return bounded dependency-review packets from a package report."
  [{:keys [project-id basis package-report limit]
    :or {limit default-packet-limit}}]
  (let [total-packages (long (or (get-in package-report [:counts :packages])
                                 (count (:packages package-report))))
        packages (vec (take default-package-limit (:packages package-report)))
        package-selection {:totalPackages total-packages
                           :includedPackages (count packages)
                           :packageLimit default-package-limit
                           :truncated (> total-packages (count packages))}]
    (->> (:unresolved-imports package-report)
         (take limit)
         (mapv #(review-packet {:project-id project-id
                                :basis basis
                                :unresolved %
                                :packages packages
                                :package-selection package-selection})))))

(defn- payload
  [item]
  (:payload item))

(defn- result
  [item]
  (:result item))

(defn- map-patch
  [result]
  (vec (or (:mapPatch result)
           (:map-patch result)
           [])))

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

(defn- import-prefix?
  [candidate import-name]
  (let [candidate (s candidate)
        import-name (s import-name)]
    (and (present? candidate)
         (present? import-name)
         (or (= candidate import-name)
             (str/starts-with? import-name (str candidate "."))
             (str/starts-with? import-name (str candidate "::"))
             (str/starts-with? import-name (str candidate "/"))))))

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
                {:path [:mapPatch idx :op]
                 :error "Patch op is not allowed by the packet."
                 :value op})
              (when (and (= "add-package-import" op)
                         (not (import-prefix? import-name unresolved-import)))
                {:path [:mapPatch idx :import]
                 :error "Patch import must be the unresolved import or a segment prefix of it."
                 :value import-name})
              (when (and (= "add-package-import" op)
                         (not (contains? package-keys [ecosystem package])))
                {:path [:mapPatch idx :package]
                 :error "Patch package must be one of facts.packages[]."
                 :value {:ecosystem ecosystem
                         :package package}})
              (when (and (= "add-package-import" op)
                         (empty? evidence))
                {:path [:mapPatch idx :evidence]
                 :error "Package import patch must cite at least one facts.evidence[].id."})
              (when-let [missing (seq (remove evidence-ids evidence))]
                {:path [:mapPatch idx :evidence]
                 :error "Patch evidence must come from facts.evidence[].id."
                 :value (vec missing)})
              (when (and (= "add-package-import" op)
                         (not (present? (:reason patch))))
                {:path [:mapPatch idx :reason]
                 :error "Patch reason is required."})
              (when (and (contains? patch :confidence)
                         (not (bounded-confidence? (:confidence patch))))
                {:path [:mapPatch idx :confidence]
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
              (map-indexed vector (map-patch result)))))))

(defn- patch->package-import
  [packet patch]
  (when (= "add-package-import" (s (:op patch)))
    (cond-> {:repo (get-in packet [:facts :unresolvedImport :repo-id])
             :import (s (:import patch))
             :ecosystem (s (:ecosystem patch))
             :package (s (:package patch))
             :evidence (patch-evidence patch)}
      (:reason patch) (assoc :reason (:reason patch)))))

(defn- apply-patch
  [overlay packet patch]
  (if-let [package-import (patch->package-import packet patch)]
    (graph-map/add-package-import overlay package-import)
    overlay))

(defn- apply-patches
  [overlay packet result]
  (reduce #(apply-patch %1 packet %2)
          overlay
          (map-patch result)))

(defn- overlay-counts
  [overlay]
  {:packageImports (count (:packageImports overlay))})

(defn apply-work-result!
  "Validate and apply a completed dependency-review queue item to a map file."
  [root id map-path]
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
         :reviewId (:reviewId packet)
         :mapPath written
         :patchesApplied (max 0 (- (:packageImports after)
                                   (:packageImports before)))}))))
