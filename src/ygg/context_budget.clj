(ns ygg.context-budget
  (:require [charred.api :as json]
            [ygg.system.candidate :as system-candidate]
            [clojure.string :as str]))

(defn estimate-tokens
  "Return a cheap token estimate for value."
  [value]
  (long (Math/ceil (/ (count (json/write-json-str value)) 4.0))))

(defn- remove-budget-diagnostics
  [packet]
  (update-in packet
             [:search :instrumentation]
             dissoc
             :context-chunks
             :context-timings-ms))

(defn- add-warning-with-budget
  [packet warning budget]
  (let [with-warning (update packet :warnings conj warning)
        compact-packet (remove-budget-diagnostics packet)
        compact-with-warning (update compact-packet :warnings conj warning)]
    (cond
      (<= (estimate-tokens with-warning) budget)
      with-warning

      (<= (estimate-tokens compact-with-warning) budget)
      compact-with-warning

      :else
      packet)))
(defn compact-evidence-readiness
  [evidence]
  (when evidence
    (cond-> (select-keys evidence
                         [:basis
                          :status
                          :available
                          :missing
                          :weak
                          :unsupported
                          :planes
                          :counts
                          :retrieval
                          :nextActions])
      (seq (:warnings evidence))
      (assoc :warnings (vec (take 3 (:warnings evidence)))))))
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
(defn minimal-evidence-readiness
  [evidence]
  (when evidence
    (let [schema-counts (compact-result-schema-counts (:counts evidence))]
      (cond-> (select-keys evidence [:basis
                                     :status
                                     :available
                                     :missing
                                     :weak
                                     :unsupported])
        (seq (:warnings evidence))
        (assoc :warnings (vec (take 3 (:warnings evidence))))
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
        (update :rejectedCorrections #(vec (take 5 %)))
        (update :boundaryEvidence #(vec (take 8 %)))
        (update :dependencyEvidence #(vec (take 8 %)))
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
(def ^:private compact-architecture-evidence-keys
  [:id
   :kind
   :path
   :fileKind
   :file-kind
   :sourceKind
   :source-kind
   :relation
   :label
   :normalizedValue
   :source
   :target
   :score
   :package
   :import])
(defn- compact-architecture-evidence-row
  [row]
  (select-keys row compact-architecture-evidence-keys))
(defn- compact-architecture-evidence
  [rows n]
  (mapv compact-architecture-evidence-row (take n rows)))
(defn- evidence-architecture
  [architecture]
  (when architecture
    (cond-> (select-keys architecture [:basis :summary])
      (seq (:runtimeEvidence architecture))
      (assoc :runtimeEvidence
             (compact-architecture-evidence (:runtimeEvidence architecture) 12))

      (seq (:dependencyEvidence architecture))
      (assoc :dependencyEvidence
             (compact-architecture-evidence (:dependencyEvidence architecture) 12))

      (seq (:boundaryEvidence architecture))
      (assoc :boundaryEvidence
             (compact-architecture-evidence (:boundaryEvidence architecture) 8))

      (seq (:deployEvidence architecture))
      (assoc :deployEvidence
             (compact-architecture-evidence (:deployEvidence architecture) 5)))))
(defn- evidence-architecture-in-packet
  [packet]
  (if (contains? packet :architecture)
    (update packet :architecture evidence-architecture)
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
(def ^:private compact-entity-label-chars
  160)

(defn- compact-string
  [value limit]
  (when (some? value)
    (let [s (str value)]
      (if (< limit (count s))
        (str (subs s 0 limit) "...")
        s))))

(defn- compact-entity
  [entity]
  (cond-> (select-keys entity [:id
                               :kind
                               :path
                               :repo
                               :status
                               :rank
                               :score
                               :attrs
                               :metrics])
    (contains? entity :label)
    (assoc :label (compact-string (:label entity)
                                  compact-entity-label-chars))))
(defn- compact-entities-in-packet
  [packet]
  (if (contains? packet :entities)
    (update packet :entities #(mapv compact-entity %))
    packet))
(defn- compact-audit-scope
  [scope]
  (cond-> (select-keys scope [:kind
                              :basis
                              :facts
                              :files
                              :supportedFiles
                              :skippedFiles
                              :diagnostics
                              :overlayCount])
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
(defn- compact-source-declaration
  [row]
  (-> row
      (select-keys [:rank
                    :sourceRank
                    :path
                    :repo
                    :repoId
                    :label
                    :kind
                    :targetKind
                    :resultKind
                    :score
                    :sourceLine
                    :endLine
                    :supportLabels
                    :scoreComponents])
      (update :supportLabels #(vec (take 4 %)))))
(defn compact-source-declarations-in-packet
  [packet]
  (if (contains? packet :sourceDeclarations)
    (update packet :sourceDeclarations
            #(mapv compact-source-declaration (take 32 %)))
    packet))
(defn- compact-memory
  [memory]
  (select-keys memory
               [:id
                :kind
                :scope
                :visibility
                :status
                :summary
                :targetIds
                :score
                :basis]))
(defn compact-memories-in-packet
  [packet]
  (if (contains? packet :memories)
    (update packet :memories #(mapv compact-memory (take 4 %)))
    packet))
(defn- minimal-graph-in-packet
  [packet]
  (if (contains? packet :graph)
    (update packet :graph select-keys [:basis :counts :defaultDetail])
    packet))
(defn trim-optional-context-metadata
  [packet budget]
  (let [trim-steps [remove-budget-diagnostics
                    compact-freshness-in-packet
                    compact-source-coverage-in-packet
                    #(dissoc % :sourceCoverage)
                    compact-architecture-in-packet
                    compact-systems-in-packet
                    compact-entities-in-packet
                    compact-audit-scopes-in-packet
                    compact-relationships-in-packet
                    compact-blast-radius-in-packet
                    compact-source-declarations-in-packet
                    compact-memories-in-packet
                    compact-snippets-in-packet
                    #(update % :evidence compact-evidence-readiness)
                    #(dissoc % :snippets)
                    #(dissoc % :sourceDeclarations)
                    #(dissoc % :memories)
                    #(dissoc % :relationships)
                    #(dissoc % :blastRadius)
                    #(dissoc % :auditScopes)
                    evidence-architecture-in-packet
                    minimal-architecture-in-packet
                    #(dissoc % :architecture)
                    minimal-graph-in-packet
                    #(dissoc % :systems)
                    #(assoc % :warnings [])
                    #(assoc % :drilldowns [])]]
    (reduce (fn [packet trim-step]
              (if (<= (estimate-tokens packet) budget)
                (reduced packet)
                (trim-step packet)))
            packet
            trim-steps)))
(declare fit-doc-snippets)

(defn- finalize-budget
  [packet budget truncated?]
  (let [packet (assoc packet
                      :budget (assoc (:budget packet)
                                     :truncated truncated?))
        packet (trim-optional-context-metadata packet budget)
        packet (fit-doc-snippets packet budget)
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
              [remove-budget-diagnostics
               compact-source-coverage-in-packet
               #(dissoc % :sourceCoverage)
               compact-architecture-in-packet
               compact-systems-in-packet
               compact-audit-scopes-in-packet
               compact-relationships-in-packet
               compact-blast-radius-in-packet
               compact-source-declarations-in-packet
               compact-memories-in-packet
               compact-snippets-in-packet
               #(update % :evidence compact-evidence-readiness)
               #(update % :evidence minimal-evidence-readiness)
               #(dissoc % :snippets)
               #(dissoc % :sourceDeclarations)
               #(dissoc % :memories)
               #(dissoc % :relationships)
               #(dissoc % :blastRadius)
               #(dissoc % :auditScopes)
               evidence-architecture-in-packet
               #(dissoc % :architecture)
               #(dissoc % :systems)
               minimal-graph-in-packet
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
  [[[repo path] items]]
  (cond-> {:path path
           :items items}
    repo (assoc :repo repo)))
(defn- add-snippet-file-item
  [files doc]
  (if (and (:snippet doc)
           (not (str/blank? (str (get-in doc [:source :path])))))
    (update files (snippet-file-key doc) (fnil conj []) (snippet-item doc))
    files))
(defn- packet-snippets
  [docs]
  (->> docs
       (reduce add-snippet-file-item {})
       (sort-by (fn [[[repo path] _]] [(or repo "") path]))
       (mapv snippet-file-row)))

(defn- with-doc-snippet-limit
  [packet limit]
  (let [packet (update packet
                       :docs
                       (fn [docs]
                         (mapv (fn [doc]
                                 (if-let [snippet (:snippet doc)]
                                   (assoc doc
                                          :snippet
                                          (compact-string snippet limit))
                                   doc))
                               docs)))]
    (if (contains? packet :snippets)
      (let [snippets (packet-snippets (:docs packet))]
        (if (seq snippets)
          (assoc packet :snippets snippets)
          (dissoc packet :snippets)))
      packet)))

(defn- omit-doc-snippets
  [packet]
  (-> packet
      (update :docs
              (fn [docs]
                (mapv (fn [doc]
                        (if (:snippet doc)
                          (-> doc
                              (dissoc :snippet)
                              (assoc :snippetOmitted true))
                          doc))
                      docs)))
      (dissoc :snippets)))

(defn- fit-doc-snippets
  [packet budget]
  (if (<= (estimate-tokens packet) budget)
    packet
    (let [max-snippet-chars (reduce max
                                    0
                                    (keep (comp count :snippet) (:docs packet)))]
      (if (zero? max-snippet-chars)
        packet
        (loop [lo 0
               hi max-snippet-chars
               best nil]
          (if (> lo hi)
            (or best (omit-doc-snippets packet))
            (let [mid (quot (+ lo hi) 2)
                  trimmed (with-doc-snippet-limit packet mid)]
              (if (<= (estimate-tokens trimmed) budget)
                (recur (inc mid) hi trimmed)
                (recur lo (dec mid) best)))))))))

(defn- compact-candidate-file
  [candidate]
  (select-keys candidate
               [:path
                :repo
                :kind
                :rank
                :score
                :targetKind
                :label
                :supportLabels
                :sourceLine
                :resultKind
                :supportOwnerEvidence
                :architectureEvidence
                :architectureSection
                :architectureKind
                :scoreComponents]))
(def ^:private candidate-file-source-reserve-limit
  16)

(def ^:private candidate-file-architecture-reserve-limit
  8)

(defn- source-file-base-kind
  [kind]
  (when kind
    (let [kind-name (name kind)
          suffix "-file"]
      (when (str/ends-with? kind-name suffix)
        (keyword (subs kind-name
                       0
                       (- (count kind-name) (count suffix))))))))

(defn- source-like-kind?
  [kind]
  (when kind
    (contains? system-candidate/source-like-kinds
               (keyword (name kind)))))

(defn- source-file-kind?
  [kind]
  (boolean
   (or (source-like-kind? kind)
       (some->> kind
                source-file-base-kind
                (contains? system-candidate/source-like-kinds)))))

(defn- value-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(def ^:private non-source-graph-node-kinds
  #{"doc-file"
    "doc-heading"
    "doc-link"
    "markdown"})

(defn- source-graph-node-candidate?
  [candidate]
  (and (= "node" (value-name (:targetKind candidate)))
       (= "node" (value-name (:resultKind candidate)))
       (:sourceLine candidate)
       (not (contains? non-source-graph-node-kinds
                       (value-name (:kind candidate))))
       (pos? (double (or (get-in candidate [:scoreComponents :sourceGraph])
                         0.0)))))

(defn- source-file-candidate-evidence?
  [candidate]
  (let [score-components (:scoreComponents candidate)]
    (pos? (+ (double (or (:grep score-components) 0.0))
             (double (or (:lexical score-components) 0.0))
             (double (or (:exact score-components) 0.0))
             (double (or (:sourceGraph score-components) 0.0))))))

(defn- source-file-candidate?
  [candidate]
  (and (or (source-file-kind? (:kind candidate))
           (true? (:supportOwnerEvidence candidate))
           (source-graph-node-candidate? candidate))
       (source-file-candidate-evidence? candidate)))

(defn- candidate-file-key
  [candidate]
  [(or (:repo candidate) (:repo-id candidate)) (:path candidate)])

(defn- candidate-source-reserve-sort-key
  [candidate]
  (let [score-components (:scoreComponents candidate)]
    [(- (double (or (:grep score-components) 0.0)))
     (- (double (or (:lexical score-components) 0.0)))
     (- (double (or (:sourceGraph score-components) 0.0)))
     (- (double (or (:semantic score-components) 0.0)))
     (- (double (or (:score candidate) 0.0)))
     (:rank candidate)
     (:path candidate)]))

(defn- architecture-candidate?
  [candidate]
  (and (true? (:architectureEvidence candidate))
       (source-file-candidate-evidence? candidate)))

(defn- architecture-candidate-reserve
  [candidates]
  (->> candidates
       (filter architecture-candidate?)
       (sort-by candidate-source-reserve-sort-key)
       (reduce (fn [{:keys [seen selected] :as acc} candidate]
                 (let [k (candidate-file-key candidate)]
                   (if (or (contains? seen k)
                           (<= candidate-file-architecture-reserve-limit
                               (count selected)))
                     acc
                     {:seen (conj seen k)
                      :selected (conj selected candidate)})))
               {:seen #{}
                :selected []})
       :selected
       vec))

(defn- candidate-source-reserve
  [candidates]
  (letfn [(take-source-candidates [rows selected-keys n]
            (loop [remaining (seq rows)
                   seen selected-keys
                   selected []]
              (if (or (nil? remaining)
                      (<= n (count selected)))
                selected
                (let [candidate (first remaining)
                      k (candidate-file-key candidate)]
                  (if (or (contains? seen k)
                          (not (source-file-candidate? candidate)))
                    (recur (next remaining) seen selected)
                    (recur (next remaining)
                           (conj seen k)
                           (conj selected candidate)))))))]
    (let [ranked-head (vec (take-source-candidates
                            candidates
                            #{}
                            candidate-file-source-reserve-limit))
          selected-keys (set (map candidate-file-key ranked-head))
          remaining (max 0
                         (- candidate-file-source-reserve-limit
                            (count ranked-head)))]
      (vec (concat ranked-head
                   (take-source-candidates
                    (sort-by candidate-source-reserve-sort-key candidates)
                    selected-keys
                    remaining))))))

(defn- candidate-selection
  [candidates prefix-count reserve]
  (let [prefix (subvec candidates 0 prefix-count)]
    (->> (concat prefix reserve)
         (reduce (fn [{:keys [seen rows] :as acc} candidate]
                   (let [k (candidate-file-key candidate)]
                     (if (contains? seen k)
                       acc
                       {:seen (conj seen k)
                        :rows (conj rows candidate)})))
                 {:seen #{}
                  :rows []})
         :rows
         (sort-by #(or (:rank %) Long/MAX_VALUE))
         vec)))

(defn- fitting-reserve-selection
  [packet reserve budget]
  (let [reserve (vec reserve)]
    (loop [lo 0
           hi (count reserve)
           best []]
      (if (> lo hi)
        best
        (let [mid (quot (+ lo hi) 2)
              selected (candidate-selection reserve mid [])
              trimmed (assoc packet :candidateFiles selected)]
          (if (<= (estimate-tokens trimmed) budget)
            (recur (inc mid) hi selected)
            (recur lo (dec mid) best)))))))

(defn- fitting-candidate-selection
  [packet candidates reserve budget]
  (loop [lo 0
         hi (count candidates)
         best nil]
    (if (> lo hi)
      (or best (fitting-reserve-selection packet reserve budget))
      (let [mid (quot (+ lo hi) 2)
            selected (candidate-selection candidates mid reserve)
            trimmed (assoc packet :candidateFiles selected)]
        (if (<= (estimate-tokens trimmed) budget)
          (recur (inc mid) hi selected)
          (recur lo (dec mid) best))))))
(def ^:private candidate-file-reserve-fraction
  0.25)

(defn- candidate-file-reserve
  [budget candidate-count]
  (if (pos? (long candidate-count))
    (long (* (double budget) candidate-file-reserve-fraction))
    0))

(defn- fit-candidate-files
  [packet budget]
  (let [candidates (vec (:candidateFiles packet))
        total (count candidates)]
    (cond
      (or (zero? total)
          (<= (estimate-tokens packet) budget))
      packet

      :else
      (let [reserve (candidate-file-reserve budget total)
            candidate-context-budget (long (- budget reserve))
            packet (-> packet
                       (assoc :candidateFiles [])
                       (trim-optional-context-metadata candidate-context-budget)
                       (assoc :candidateFiles candidates))
            compact-candidates (mapv compact-candidate-file candidates)
            selected-candidates (fitting-candidate-selection
                                 packet
                                 compact-candidates
                                 (concat
                                  (architecture-candidate-reserve compact-candidates)
                                  (candidate-source-reserve compact-candidates))
                                 budget)]
        (if (seq selected-candidates)
          (-> packet
              (assoc :candidateFiles selected-candidates)
              (add-warning-with-budget
               (if (< (count selected-candidates) total)
                 (str "candidate files trimmed to "
                      (count selected-candidates)
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
        snippets (packet-snippets (:docs packet))
        packet (cond-> packet
                 (seq snippets)
                 (assoc :snippets snippets))
        truncated? (< (count (:docs packet)) (count docs))]
    (finalize-budget packet budget truncated?)))
