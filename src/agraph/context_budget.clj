(ns agraph.context-budget
  (:require [charred.api :as json]
            [clojure.string :as str]))

(defn estimate-tokens
  "Return a cheap token estimate for value."
  [value]
  (long (Math/ceil (/ (count (json/write-json-str value)) 4.0))))

(defn- add-warning-with-budget
  [packet warning budget]
  (let [with-warning (update packet :warnings conj warning)]
    (if (<= (estimate-tokens with-warning) budget)
      with-warning
      packet)))
(defn compact-answerability
  [answerability]
  (when answerability
    (cond-> (select-keys answerability
                         [:status
                          :available
                          :missing
                          :weak
                          :unsupported
                          :planes
                          :counts
                          :retrieval
                          :next
                          :nextActions])
      (seq (:warnings answerability))
      (assoc :warnings (vec (take 3 (:warnings answerability)))))))
(def ^:private result-schema-count-keys
  [:result-schema-statuses
   :result-schema-status-items
   :result-schema-matching-items
   :result-schema-mismatch-items
   :result-schema-missing-result-items
   :result-schema-unexpected-result-items
   :result-schema-mismatch-events])
(defn- compact-result-schema-counts
  [counts]
  (let [schema-counts (select-keys counts result-schema-count-keys)]
    (when (or (seq (:result-schema-statuses schema-counts))
              (some pos? (map #(long (or (get schema-counts %) 0))
                              (remove #{:result-schema-statuses}
                                      result-schema-count-keys))))
      schema-counts)))
(defn minimal-answerability
  [answerability]
  (when answerability
    (let [schema-counts (compact-result-schema-counts (:counts answerability))]
      (cond-> (select-keys answerability [:status
                                          :available
                                          :missing
                                          :weak
                                          :unsupported])
        schema-counts (assoc :counts schema-counts)))))
(defn- compact-freshness-samples
  [samples]
  (into {}
        (map (fn [[k paths]]
               [k (vec (take 3 paths))]))
        samples))
(defn- compact-freshness-repo
  [repo]
  (cond-> (select-keys repo [:repo-id :root :status :counts :error])
    (seq (:samples repo)) (assoc :samples
                                 (compact-freshness-samples (:samples repo)))))
(defn compact-freshness
  [freshness]
  (when freshness
    (cond-> (select-keys freshness [:status :basis :counts :warnings :nextActions])
      (seq (:repos freshness)) (assoc :repos
                                      (mapv compact-freshness-repo
                                            (take 3 (:repos freshness))))
      (seq (:warnings freshness)) (assoc :warnings
                                         (vec (take 3 (:warnings freshness))))
      (seq (:nextActions freshness)) (assoc :nextActions
                                            (vec (take 3 (:nextActions freshness)))))))
(defn- compact-freshness-in-packet
  [packet]
  (if (contains? packet :freshness)
    (update packet :freshness compact-freshness)
    packet))
(defn compact-source-coverage
  [source-coverage]
  (when source-coverage
    (cond-> (select-keys source-coverage [:schema :basis :totals])
      (:indexedConnectivity source-coverage)
      (assoc :indexedConnectivity
             (cond-> (select-keys (:indexedConnectivity source-coverage)
                                  [:indexedFiles
                                   :nodes
                                   :edges
                                   :connectedFiles
                                   :crossFileConnectedFiles
                                   :isolatedFiles])
               (seq (get-in source-coverage [:indexedConnectivity :byKind]))
               (assoc :byKind
                      (vec (take 5
                                 (get-in source-coverage
                                         [:indexedConnectivity :byKind]))))))

      (seq (:topFileKinds source-coverage))
      (assoc :topFileKinds (vec (take 5 (:topFileKinds source-coverage))))

      (seq (:extractors source-coverage))
      (assoc :extractors (vec (take 5 (:extractors source-coverage))))

      (seq (:extractorFingerprints source-coverage))
      (assoc :extractorFingerprints
             (vec (take 5 (:extractorFingerprints source-coverage))))

      (seq (:nextActions source-coverage))
      (assoc :nextActions (vec (take 3 (:nextActions source-coverage))))

      (seq (get-in source-coverage [:diagnostics :byStage]))
      (assoc-in [:diagnostics :byStage]
                (vec (take 5 (get-in source-coverage [:diagnostics :byStage]))))

      (seq (get-in source-coverage [:diagnostics :byExtractor]))
      (assoc-in [:diagnostics :byExtractor]
                (vec (take 5 (get-in source-coverage [:diagnostics :byExtractor]))))

      (seq (get-in source-coverage [:diagnostics :samples]))
      (assoc-in [:diagnostics :samples]
                (vec (take 3 (get-in source-coverage [:diagnostics :samples])))))))
(defn- compact-source-coverage-in-packet
  [packet]
  (if (contains? packet :sourceCoverage)
    (update packet :sourceCoverage compact-source-coverage)
    packet))
(defn- compact-architecture
  [architecture]
  (when architecture
    (-> architecture
        (update :acceptedSystems #(vec (take 5 %)))
        (update :candidateSystems #(vec (take 5 %)))
        (update :boundaryEvidence #(vec (take 8 %)))
        (update :dependencyEvidence #(vec (take 5 %)))
        (update :deployEvidence #(vec (take 5 %)))
        (update :evidenceFamilies #(vec (take 8 %)))
        (update :docs #(vec (take 5 %)))
        (update :openDecisions #(vec (take 3 %)))
        (update :validationGaps #(vec (take 6 %)))
        (update :warnings #(vec (take 3 %)))
        (update :nextActions #(vec (take 3 %))))))
(defn- compact-architecture-in-packet
  [packet]
  (if (contains? packet :architecture)
    (update packet :architecture compact-architecture)
    packet))
(defn- minimal-architecture
  [architecture]
  (when architecture
    (select-keys architecture [:basis :summary])))
(defn- minimal-architecture-in-packet
  [packet]
  (if (contains? packet :architecture)
    (update packet :architecture minimal-architecture)
    packet))
(defn- compact-systems
  [systems]
  (when systems
    (-> systems
        (update :accepted #(vec (take 4 %)))
        (update :candidates #(vec (take 4 %))))))
(defn compact-systems-in-packet
  [packet]
  (if (contains? packet :systems)
    (update packet :systems compact-systems)
    packet))
(defn- compact-audit-scope
  [scope]
  (cond-> (select-keys scope [:kind :basis :facts :files])
    (seq (:topEvidenceTypes scope)) (assoc :topEvidenceTypes
                                           (vec (take 3 (:topEvidenceTypes scope))))
    (seq (:samples scope)) (assoc :samples
                                  (vec (take 2 (:samples scope))))))
(defn- compact-audit-scopes-in-packet
  [packet]
  (if (contains? packet :auditScopes)
    (update packet :auditScopes #(mapv compact-audit-scope (take 4 %)))
    packet))
(defn- compact-relationship-group
  [group]
  (update group :targets #(vec (take 4 %))))
(defn compact-relationships-in-packet
  [packet]
  (if (contains? packet :relationships)
    (update packet :relationships #(mapv compact-relationship-group (take 5 %)))
    packet))
(defn- compact-blast-radius-side
  [side]
  (update side :targets #(vec (take 4 %))))
(defn- compact-blast-radius
  [blast-radius]
  (-> blast-radius
      (update :downstream compact-blast-radius-side)
      (update :upstream compact-blast-radius-side)))
(defn compact-blast-radius-in-packet
  [packet]
  (if (contains? packet :blastRadius)
    (update packet :blastRadius compact-blast-radius)
    packet))
(defn- compact-snippet-file
  [file]
  (update file :items #(vec (take 2 %))))
(defn compact-snippets-in-packet
  [packet]
  (if (contains? packet :snippets)
    (update packet :snippets #(mapv compact-snippet-file (take 4 %)))
    packet))
(defn trim-optional-context-metadata
  [packet budget]
  (let [trim-steps [#(update-in % [:search :instrumentation] dissoc :context-chunks)
                    compact-freshness-in-packet
                    compact-source-coverage-in-packet
                    #(dissoc % :sourceCoverage)
                    compact-architecture-in-packet
                    compact-systems-in-packet
                    compact-audit-scopes-in-packet
                    compact-relationships-in-packet
                    compact-blast-radius-in-packet
                    compact-snippets-in-packet
                    #(update % :answerability compact-answerability)
                    #(dissoc % :snippets)
                    #(dissoc % :relationships)
                    #(dissoc % :blastRadius)
                    #(dissoc % :auditScopes)
                    minimal-architecture-in-packet
                    #(dissoc % :architecture)
                    #(dissoc % :systems)
                    #(assoc % :warnings [])
                    #(assoc % :drilldowns [])]]
    (reduce (fn [packet trim-step]
              (if (<= (estimate-tokens packet) budget)
                (reduced packet)
                (trim-step packet)))
            packet
            trim-steps)))
(defn- finalize-budget
  [packet budget truncated?]
  (let [packet (assoc packet
                      :budget (assoc (:budget packet)
                                     :truncated truncated?))
        packet (trim-optional-context-metadata packet budget)
        with-estimate (assoc-in packet [:budget :estimated] (estimate-tokens packet))]
    (if (<= (estimate-tokens with-estimate) budget)
      with-estimate
      packet)))
(defn- reviewed-doc?
  [doc]
  (or (some? (:role doc))
      (some? (:status doc))))
(defn- reviewed-doc-packet-variants
  [packet]
  (reductions (fn [packet trim-step]
                (trim-step packet))
              packet
              [#(update-in % [:search :instrumentation] dissoc :context-chunks)
               compact-source-coverage-in-packet
               #(dissoc % :sourceCoverage)
               compact-architecture-in-packet
               compact-systems-in-packet
               compact-audit-scopes-in-packet
               compact-relationships-in-packet
               compact-blast-radius-in-packet
               compact-snippets-in-packet
               #(update % :answerability compact-answerability)
               #(update % :answerability minimal-answerability)
               #(dissoc % :snippets)
               #(dissoc % :relationships)
               #(dissoc % :blastRadius)
               #(dissoc % :auditScopes)
               #(dissoc % :architecture)
               #(dissoc % :systems)
               #(assoc % :warnings [])
               #(assoc % :drilldowns [])
               #(assoc % :activity [])
               #(assoc % :edges [])]))
(defn- first-fitting-doc-packet
  [packet doc budget]
  (->> (if (reviewed-doc? doc)
         (reviewed-doc-packet-variants packet)
         [packet])
       (filter #(<= (estimate-tokens (update % :docs conj doc)) budget))
       first))
(defn add-doc-with-budget
  [packet doc budget]
  (let [packet (or (first-fitting-doc-packet packet doc budget)
                   packet)
        with-snippet (update packet :docs conj doc)]
    (if (<= (estimate-tokens with-snippet) budget)
      with-snippet
      (let [ref-doc (-> doc
                        (dissoc :snippet)
                        (assoc :snippetOmitted true))
            packet (or (first-fitting-doc-packet packet ref-doc budget)
                       packet)
            with-ref (update packet :docs conj ref-doc)]
        (if (<= (estimate-tokens with-ref) budget)
          (add-warning-with-budget with-ref
                                   "snippet omitted to fit context budget"
                                   budget)
          (add-warning-with-budget packet
                                   "doc omitted to fit context budget"
                                   budget))))))
(defn- snippet-item
  [doc]
  (when-let [snippet (:snippet doc)]
    (cond-> {:target (:target doc)
             :text snippet}
      (:role doc) (assoc :role (:role doc))
      (:status doc) (assoc :status (:status doc))
      (:score doc) (assoc :score (:score doc))
      (:provenance doc) (assoc :provenance (:provenance doc))
      (get-in doc [:source :lines]) (assoc :lines (get-in doc [:source :lines]))
      (get-in doc [:source :heading]) (assoc :heading (get-in doc [:source :heading]))
      (get-in doc [:source :headingPath]) (assoc :headingPath
                                                 (get-in doc [:source :headingPath])))))
(defn- snippet-file-key
  [doc]
  [(get-in doc [:source :repo]) (get-in doc [:source :path])])
(defn- snippet-file-row
  [[[repo path] docs]]
  (cond-> {:path path
           :items (mapv snippet-item docs)}
    repo (assoc :repo repo)))
(defn- packet-snippets
  [docs]
  (->> docs
       (filter #(and (:snippet %)
                     (not (str/blank? (str (get-in % [:source :path]))))))
       (group-by snippet-file-key)
       (sort-by (fn [[[repo path] _]] [(or repo "") path]))
       (mapv snippet-file-row)))
(defn- compact-candidate-file
  [candidate]
  (select-keys candidate
               [:path
                :repo
                :rank
                :score
                :targetKind
                :label
                :sourceLine
                :resultKind
                :scoreComponents]))
(defn- fitting-candidate-prefix
  [packet candidates budget]
  (loop [lo 0
         hi (count candidates)
         best 0]
    (if (> lo hi)
      best
      (let [mid (quot (+ lo hi) 2)
            trimmed (assoc packet :candidateFiles (subvec candidates 0 mid))]
        (if (<= (estimate-tokens trimmed) budget)
          (recur (inc mid) hi mid)
          (recur lo (dec mid) best))))))
(defn- fit-candidate-files
  [packet budget]
  (let [candidates (vec (:candidateFiles packet))
        total (count candidates)]
    (cond
      (or (zero? total)
          (<= (estimate-tokens packet) budget))
      packet

      :else
      (let [compact-candidates (mapv compact-candidate-file candidates)
            keep-count (fitting-candidate-prefix packet compact-candidates budget)]
        (if (pos? keep-count)
          (-> packet
              (assoc :candidateFiles (subvec compact-candidates 0 keep-count))
              (add-warning-with-budget
               (if (< keep-count total)
                 (str "candidate files trimmed to "
                      keep-count
                      " of "
                      total
                      " to fit context budget")
                 "candidate file details compacted to fit context budget")
               budget))
          (-> packet
              (assoc :candidateFiles [])
              (add-warning-with-budget
               "candidate files omitted to fit context budget"
               budget)))))))
(defn fit-budget
  [packet docs budget]
  (let [candidate-files (vec (:candidateFiles packet))
        packet (-> packet
                   (assoc :candidateFiles [])
                   (trim-optional-context-metadata budget))
        packet (reduce #(add-doc-with-budget %1 %2 budget) packet docs)
        packet (fit-candidate-files (assoc packet :candidateFiles candidate-files)
                                    budget)
        packet (cond-> packet
                 (seq (packet-snippets (:docs packet)))
                 (assoc :snippets (packet-snippets (:docs packet))))
        truncated? (< (count (:docs packet)) (count docs))]
    (finalize-budget packet budget truncated?)))
