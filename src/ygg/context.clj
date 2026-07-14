(ns ygg.context
  "Token-bounded, graph-grounded context packets for agents."
  (:require [ygg.activity :as activity]
            [ygg.audit-scope :as audit-scope]
            [ygg.command :as command]
            [ygg.context-architecture :as context-architecture]
            [ygg.context-budget :as context-budget]
            [ygg.corrections :as corrections]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.graph :as graph]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.memory :as memory]
            [ygg.plugin-package-view :as plugin-package-view]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "ygg.context/v1")

(def compact-schema
  "ygg.query/v2")

(def default-budget
  4000)

(def default-entity-limit
  8)

(def default-edge-limit
  12)

(def default-doc-limit
  6)

(def default-snippet-chars
  900)

(def default-retrieval-limit
  40)

(def default-output
  :full)

(def compact-result-limit
  11)

(def compact-evidence-limit
  10)

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (System/nanoTime) started-ns) 1000000)))

(defn- timed-context-step
  [timings stage f]
  (let [started (now-ns)
        value (f)]
    [value (assoc timings stage (elapsed-ms started))]))

(defn- timed-context-task
  [f]
  (future
    (let [started (now-ns)]
      (try
        {:value (f)
         :elapsed-ms (elapsed-ms started)}
        (catch Throwable error
          {:error error
           :elapsed-ms (elapsed-ms started)})))))

(defn- await-context-task
  [timings stage task]
  (let [{:keys [value error elapsed-ms]} @task]
    (when error
      (throw error))
    [value (assoc timings stage elapsed-ms)]))

(def ^:private compact-grep-result-reserve-limit
  4)

(def ^:private compact-path-token-result-reserve-limit
  1)

(def ^:private compact-label-token-result-reserve-limit
  2)

(def ^:private compact-exact-label-result-reserve-limit
  1)

(def ^:private compact-exact-label-score-boost
  1.25)

(def ^:private compact-shaped-label-score-boost
  0.75)

(def proof-command-path-limit
  8)

(def proof-command-pattern-limit
  4)

(def ^:private source-graph-candidate-limit
  40)

(def ^:private source-graph-file-candidate-limit
  80)

(def ^:private candidate-file-sibling-query-limit
  32)

(def ^:private candidate-file-sibling-token-limit
  96)

(def ^:private candidate-file-sibling-limit
  32)

(def ^:private candidate-file-sibling-per-candidate-limit
  4)

(def ^:private candidate-file-support-owner-query-limit
  48)

(def ^:private candidate-file-support-owner-label-limit
  256)

(def ^:private candidate-file-support-owner-limit
  32)

(def ^:private candidate-file-support-owner-per-candidate-limit
  4)

(def ^:private candidate-file-support-owner-specific-token-min-length
  7)

(def ^:private source-graph-neighbor-scan-limit
  160)

(def ^:private source-graph-neighbor-seed-limit
  96)

(def ^:private source-graph-neighbor-candidate-limit
  40)

(def ^:private source-graph-second-hop-neighbor-seed-limit
  24)

(def ^:private source-graph-second-hop-neighbor-candidate-limit
  40)

(def ^:private source-graph-second-hop-neighbor-token-min
  2)

(def ^:private source-graph-second-hop-score-decay
  0.65)

(def ^:private source-graph-local-importer-seed-limit
  160)

(def ^:private source-graph-local-importer-label-limit
  256)

(def ^:private source-graph-local-importer-candidate-limit
  40)

(def ^:private source-graph-neighbor-kind-path-limit
  4)

(def ^:private source-graph-neighbor-path-limit
  4)

(def ^:private source-graph-neighbor-support-boost
  1.0)

(def ^:private doc-diversify-candidate-limit-min
  80)

(def ^:private doc-diversify-candidate-limit-multiplier
  12)

(def ^:private source-graph-neighbor-score-cap
  4.8)

(def ^:private source-graph-visible-score-cap
  0.6)

(defn- active-row?
  [row]
  (not= false (:active? row)))

(def ^:private candidate-input-root-diversity-limit
  8)

(def ^:private candidate-input-retrieval-prefix-limit
  12)

(def ^:private candidate-input-query-source-prefix-limit
  4)

(def ^:private candidate-input-supported-source-prefix-limit
  4)

(def ^:private candidate-input-supported-source-prefix-token-min
  2)

(def ^:private source-graph-candidate-min-score
  1.0)

(def ^:private source-graph-query-token-limit
  32)

(def ^:private source-graph-query-token-max-length
  48)

(def ^:private source-graph-rare-token-weight
  2.0)

(def ^:private source-graph-rare-token-cap
  8.0)

(def ^:private path-self-identity-reserve-limit
  6)

(def ^:private source-graph-declaration-limit
  64)

(def ^:private source-graph-declaration-path-limit
  16)

(def ^:private source-graph-declaration-path-diversity-limit
  12)

(def ^:private source-graph-declaration-path-local-limit
  6)

(def ^:private source-graph-non-declaration-kinds
  #{:doc-file
    :doc-heading
    :doc-link})

(def ^:private supported-source-graph-prefix-excluded-kinds
  (conj source-graph-non-declaration-kinds
        :markdown
        :url
        :changelog-section))

(def unsupported-planes
  [:remote-work :session-history])

(def ^:private plane-order
  [:source-files
   :source-graph
   :dependencies
   :docs
   :embeddings
   :system-evidence
   :system-graph
   :memory
   :activity
   :validation-history
   :correction-overlay
   :remote-work
   :session-history])

(def ^:private plane-count-keys
  {:source-files [:files :skipped-files :diagnostics]
   :source-graph [:nodes :edges]
   :dependencies [:external-packages
                  :package-import-edges
                  :declared-packages
                  :source-import-candidates
                  :unresolved-imports
                  :package-evidence-gaps
                  :package-conflicts]
   :docs [:chunks :search-docs]
   :embeddings [:embeddings]
   :system-evidence [:system-evidence]
   :system-graph [:system-nodes :system-edges]
   :memory [:memories
            :suggested-memories
            :observed-memories
            :reviewed-memories]
   :activity [:activity-items :activity-events]
   :validation-history [:validation-events
                        :result-schema-status-items
                        :result-schema-matching-items
                        :result-schema-mismatch-items
                        :result-schema-missing-result-items
                        :result-schema-unexpected-result-items
                        :result-schema-mismatch-events]
   :correction-overlay [:correction-systems :correction-docs :correction-edges :correction-rejects]})

(def role-weight
  {"overview" 0.35
   "contract" 0.35
   "runbook" 0.30
   "troubleshooting" 0.30
   "rationale" 0.20
   "warning" 0.20
   "reference" 0.10
   "example" 0.05})

(defn estimate-tokens
  "Return a cheap token estimate for value."
  [value]
  (long (Math/ceil (/ (count (json/write-json-str value)) 4.0))))

(defn- s
  [value]
  (some-> value str))

(defn- display-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- path-under?
  [path prefix]
  (and (seq path)
       (seq prefix)
       (or (= path prefix)
           (str/starts-with? path (str prefix "/")))))

(defn- node-path
  [node]
  (or (:pathPrefix node)
      (:path-prefix node)
      (:path node)))

(defn- compact
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join " ")))

(defn- token-score
  [query-tokens value]
  (text/token-score query-tokens (text/tokenize value)))

(defn- capped-token-score
  [query-tokens value]
  (min 1.0 (token-score query-tokens value)))

(defn- result-matches-node?
  [result node]
  (or (= (:target-id result) (:id node))
      (and (:path result)
           (node-path node)
           (path-under? (:path result) (node-path node))
           (or (nil? (:repo node))
               (nil? (:repo-id result))
               (= (:repo node) (:repo-id result))))))

(defn- node-result-match
  [results node]
  (reduce (fn [match result]
            (if (result-matches-node? result node)
              {:matched? true
               :score (max (double (:score match))
                           (double (or (:score result) 0.0)))}
              match))
          {:matched? false
           :score 0.0}
          results))

(defn- entity-score
  [query-tokens result-match node]
  (let [lexical-score (token-score query-tokens
                                   (compact (:label node)
                                            (:kind node)
                                            (:repo node)
                                            (:path node)
                                            (:pathPrefix node)
                                            (pr-str (:attrs node))
                                            (str/join " " (:tags node))
                                            (pr-str (:metrics node))))]
    (+ (:score result-match) (* 0.25 lexical-score))))

(defn- select-entities
  [query-tokens results graph-data limit]
  (->> (:nodes graph-data)
       (remove #(= "repo" (:kind %)))
       (map (fn [node]
              (let [result-match (node-result-match results node)]
                (assoc node
                       :context-score (entity-score query-tokens result-match node)
                       :result-match? (:matched? result-match)))))
       (filter #(pos? (:context-score %)))
       (sort-by (juxt (comp - :context-score) :label))
       (take limit)
       (mapv (fn [node]
               (cond-> {:id (:id node)
                        :label (:label node)
                        :kind (:kind node)
                        :repo (:repo node)
                        :path (:path node)
                        :pathPrefix (:pathPrefix node)
                        :clusterId (:clusterId node)
                        :clusterLabel (:clusterLabel node)
                        :score (double (:context-score node))
                        :why (if (:result-match? node)
                               "retrieval and graph match"
                               "graph label match")}
                 (:attrs node) (assoc :attrs (:attrs node))
                 (seq (:tags node)) (assoc :tags (:tags node))
                 (seq (:candidateTypes node)) (assoc :candidateTypes (:candidateTypes node))
                 (seq (:candidateEvidence node)) (assoc :candidateEvidence
                                                        (:candidateEvidence node))
                 (:metrics node) (assoc :metrics (:metrics node)))))))

(defn- edge-relation-scores
  [query-tokens edges]
  (into {}
        (map (fn [relation]
               [relation (token-score query-tokens relation)]))
        (distinct (map :relation edges))))

(defn- edge-score
  [selected-ids relation-scores edge]
  (+ (cond
       (and (contains? selected-ids (:source edge))
            (contains? selected-ids (:target edge))) 1.0
       (or (contains? selected-ids (:source edge))
           (contains? selected-ids (:target edge))) 0.5
       :else 0.0)
     (* 0.15 (double (get relation-scores (:relation edge) 0.0)))))

(defn- select-edges
  [query-tokens entities graph-data limit]
  (let [selected-ids (set (map :id entities))
        edges (:edges graph-data)
        relation-scores (edge-relation-scores query-tokens edges)]
    (->> edges
         (map #(assoc % :context-score (edge-score selected-ids relation-scores %)))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt (comp - :context-score) :relation :source :target))
         (take limit)
         (mapv (fn [edge]
                 (cond-> {:id (:id edge)
                          :source (:source edge)
                          :target (:target edge)
                          :relation (:relation edge)
                          :confidence (:confidence edge)
                          :salience (:salience edge)
                          :visibility (:visibility edge)
                          :score (double (:context-score edge))}
                   (:attrs edge) (assoc :attrs (:attrs edge))
                   (seq (:tags edge)) (assoc :tags (:tags edge))
                   (:metrics edge) (assoc :metrics (:metrics edge))))))))

(defn- truncate
  [value n]
  (let [value (str value)]
    (if (<= (count value) n)
      value
      (str (subs value 0 (max 0 (- n 12))) "\n[truncated]"))))

(defn- lines
  [chunk]
  (when (:source-line chunk)
    (cond-> [(long (:source-line chunk))]
      (:end-line chunk) (conj (long (:end-line chunk))))))

(defn- chunk-source
  [chunk]
  {:repo (:repo-id chunk)
   :path (:path chunk)
   :kind (:kind chunk)
   :definitionKind (:definition-kind chunk)
   :heading (:label chunk)
   :headingPath (:heading-path chunk)
   :lines (lines chunk)
   :contentSha (:content-sha chunk)})

(defn- source-line
  [source]
  (or (:startLine source)
      (:start-line source)))

(defn- source-end-line
  [source]
  (or (:endLine source)
      (:end-line source)))

(defn- source-heading-path
  [source]
  (or (:headingPath source)
      (:heading-path source)))

(defn- source-matches-chunk?
  [source chunk]
  (and (= (s (:repo source)) (s (:repo-id chunk)))
       (= (s (:path source)) (s (:path chunk)))
       (or (nil? (:heading source))
           (= (s (:heading source)) (s (:label chunk))))
       (or (nil? (source-heading-path source))
           (= (mapv s (source-heading-path source))
              (mapv s (:heading-path chunk))))
       (or (nil? (source-line source))
           (= (long (source-line source)) (long (:source-line chunk))))))

(defn- find-source-chunk
  [chunks source]
  (first (filter #(source-matches-chunk? source %) chunks)))

(defn- attachment->doc
  [chunks snippet-chars attachment]
  (let [source (:source attachment)
        chunk (find-source-chunk chunks source)
        content-sha (or (:contentSha source) (:content-sha source))
        stale? (or (nil? chunk)
                   (and content-sha
                        (:content-sha chunk)
                        (not= (s content-sha) (s (:content-sha chunk))))
                   (and (source-end-line source)
                        (:end-line chunk)
                        (not= (long (source-end-line source))
                              (long (:end-line chunk)))))]
    (cond-> {:target (:target attachment)
             :role (or (:role attachment) "reference")
             :status (if stale? "stale" "accepted")
             :source (if chunk (chunk-source chunk) source)
             :score (+ 2.0 (get role-weight (s (:role attachment)) 0.0))
             :provenance "map-attachment"}
      (:reason attachment) (assoc :reason (:reason attachment))
      chunk (assoc :snippet (truncate (:text chunk) snippet-chars))
      (nil? chunk) (assoc :warning "attached doc source not found"))))

(defn- result-score-by-target
  [results]
  (into {} (map (juxt :target-id :score)) results))

(defn- result-score-by-path-label
  [results]
  (reduce (fn [scores {:keys [path label score]}]
            (if (and (not (str/blank? (str path)))
                     (not (str/blank? (str label))))
              (update scores [path label] #(max (double (or % 0.0))
                                                (double score)))
              scores))
          {}
          results))

(defn- file-result-row?
  [result]
  (contains? #{"file"}
             (display-name (or (:target-kind result)
                               (:targetKind result)
                               (:result-kind result)
                               (:resultKind result)))))

(defn- result-score-by-file-path
  [results]
  (reduce (fn [scores {:keys [path score] :as result}]
            (if (and (file-result-row? result)
                     (not (str/blank? (str path))))
              (update scores path #(max (double (or % 0.0))
                                        (double (or score 0.0))))
              scores))
          {}
          results))

(defn- result-score-for-chunk
  [result-scores result-scores-by-path-label result-scores-by-file-path chunk]
  (or (get result-scores (:xt/id chunk))
      (get result-scores-by-path-label [(:path chunk) (:label chunk)])
      (get result-scores-by-file-path (:path chunk))))

(defn- result-by-target
  [results]
  (into {} (map (juxt :target-id identity)) results))

(defn- chunk-search-tokens
  [chunk]
  (if (seq (:tokens chunk))
    (concat (text/tokenize (compact (:label chunk) (:path chunk)))
            (:tokens chunk))
    (text/tokenize (compact (:label chunk) (:path chunk) (:text chunk)))))

(defn- capped-candidate-token-score
  [query-tokens candidate-tokens]
  (min 1.0 (text/token-score query-tokens candidate-tokens)))

(defn- chunk-score
  [query-tokens
   selected-label-tokens
   definition-kind-scores
   chunk]
  (+ (double (or (:retrieval-score chunk) 0.0))
     (* 0.35 (capped-candidate-token-score query-tokens
                                           (chunk-search-tokens chunk)))
     (* 0.45 (double (get definition-kind-scores
                          (:definition-kind chunk)
                          0.0)))
     (* 0.15 (min 1.0
                  (text/token-score selected-label-tokens (:tokens chunk))))))

(defn- chunk-definition-kind-scores
  [query-tokens chunks]
  (into {}
        (map (fn [definition-kind]
               [definition-kind
                (capped-token-score query-tokens
                                    (display-name definition-kind))]))
        (distinct (map :definition-kind chunks))))

(defn- chunk-with-retrieval-score
  [result-scores result-scores-by-path-label result-scores-by-file-path chunk]
  (assoc chunk
         :retrieval-score (result-score-for-chunk result-scores
                                                  result-scores-by-path-label
                                                  result-scores-by-file-path
                                                  chunk)))

(defn- inferred-docs
  [query-tokens results chunks entities snippet-chars]
  (let [result-scores (result-score-by-target results)
        result-scores-by-path-label (result-score-by-path-label results)
        result-scores-by-file-path (result-score-by-file-path results)
        results-by-target (result-by-target results)
        definition-kind-scores (chunk-definition-kind-scores query-tokens chunks)
        selected-label-tokens (text/tokenize (str/join " " (map :label entities)))]
    (->> chunks
         (map #(chunk-with-retrieval-score result-scores
                                           result-scores-by-path-label
                                           result-scores-by-file-path
                                           %))
         (filter #(or (= :markdown (:kind %))
                      (:retrieval-score %)))
         (map #(assoc % :context-score (chunk-score query-tokens
                                                    selected-label-tokens
                                                    definition-kind-scores
                                                    %)
                      :retrieved? (boolean (:retrieval-score %))
                      :exact-path? (>= (double (get-in results-by-target
                                                       [(:xt/id %) :score-components :exact]
                                                       0.0))
                                       2.0)))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt #(cond
                           (and (:exact-path? %)
                                (not= :markdown (:kind %))) 0
                           (and (:retrieved? %)
                                (not= :markdown (:kind %))) 1
                           :else 2)
                        (comp - :context-score)
                        :path
                        :source-line))
         (mapv (fn [chunk]
                 (cond-> {:target (:xt/id chunk)
                          :role "reference"
                          :status "candidate"
                          :source (chunk-source chunk)
                          :score (double (:context-score chunk))
                          :snippet (truncate (:text chunk) snippet-chars)
                          :provenance "retrieved-doc"}
                   (and (:retrieved? chunk)
                        (not= :markdown (:kind chunk)))
                   (assoc :retrievedSource true)

                   (and (:exact-path? chunk)
                        (not= :markdown (:kind chunk)))
                   (assoc :exactPathSource true)))))))

(defn- distinct-doc-key
  [doc]
  [(get-in doc [:source :repo])
   (get-in doc [:source :path])
   (get-in doc [:source :lines])])

(defn- distinct-docs
  [docs]
  (loop [remaining (seq docs)
         seen #{}
         out []]
    (if-let [doc (first remaining)]
      (let [k (distinct-doc-key doc)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out doc))))
      out)))

(defn- selected-targets
  [entities edges]
  (set (concat (map :id entities)
               (map :label entities)
               (map :id edges))))

(defn- system-targets
  [systems]
  (->> systems
       (mapcat (juxt :id :label))
       (remove nil?)
       set))

(defn- doc-priority
  [doc]
  (cond
    (= "map-attachment" (:provenance doc)) 0
    (:exactPathSource doc) 1
    (:retrievedSource doc) 2
    :else 3))

(defn- doc-path-key
  [doc]
  (let [source (:source doc)]
    [(or (:repo source) :unknown-repo)
     (or (:path source)
         (:heading source)
         (:target doc)
         :other)]))

(defn- doc-root-key
  [doc]
  (let [source (:source doc)
        path (:path source)
        root (when-not (str/blank? (str path))
               (or (first (remove str/blank? (str/split (str path) #"/")))
                   path))]
    [(or (:repo source) :unknown-repo)
     (or root
         (:heading source)
         (:target doc)
         :other)]))

(defn- definition-kind-query-score
  [query-tokens definition-kind]
  (if (seq query-tokens)
    (capped-token-score query-tokens
                        (display-name definition-kind))
    0.0))

(defn- diversify-doc-row
  [definition-kind-scores doc]
  (let [source (:source doc)
        repo (or (:repo source) :unknown-repo)
        definition-kind (:definitionKind source)
        root-key (doc-root-key doc)
        definition-kind-key [repo definition-kind]
        root-definition-kind-key [root-key definition-kind]]
    {:doc doc
     :priority (doc-priority doc)
     :root-key root-key
     :path-key (doc-path-key doc)
     :definition-kind-key definition-kind-key
     :root-definition-kind-key root-definition-kind-key
     :definition-kind-query-score (get definition-kind-scores
                                       definition-kind
                                       0.0)}))

(defn- row-novelty-score
  [seen-paths seen-definition-kinds row]
  (+ (if (contains? seen-paths (:path-key row)) 0 2)
     (if (or (nil? (second (:definition-kind-key row)))
             (contains? seen-definition-kinds (:definition-kind-key row)))
       0
       1)))

(defn- row-root-novelty-score
  [seen-roots row]
  (if (contains? seen-roots (:root-key row)) 0 1))

(defn- row-root-definition-kind-novelty-score
  [seen-root-definition-kinds row]
  (let [[_ definition-kind :as k] (:root-definition-kind-key row)]
    (if (or (nil? definition-kind)
            (contains? seen-root-definition-kinds k))
      0
      1)))

(defn- row-selection-key
  [seen-roots seen-paths seen-definition-kinds seen-root-definition-kinds idx row]
  (let [definition-kind-score (:definition-kind-query-score row)]
    [(:priority row)
     (- definition-kind-score)
     (- (row-root-novelty-score seen-roots row))
     (- (if (pos? definition-kind-score)
          (row-root-definition-kind-novelty-score seen-root-definition-kinds row)
          0))
     (- (row-novelty-score seen-paths seen-definition-kinds row))
     idx]))

(defn- selected-diversify-row
  [remaining seen-roots seen-paths seen-definition-kinds seen-root-definition-kinds]
  (loop [idx 0
         rows (seq remaining)
         selected nil]
    (if-let [row (first rows)]
      (let [candidate [idx row (row-selection-key seen-roots
                                                  seen-paths
                                                  seen-definition-kinds
                                                  seen-root-definition-kinds
                                                  idx
                                                  row)]]
        (recur (inc idx)
               (next rows)
               (if (or (nil? selected)
                       (neg? (compare (nth candidate 2)
                                      (nth selected 2))))
                 candidate
                 selected)))
      selected)))

(defn- diversify-docs
  ([docs] (diversify-docs [] docs))
  ([query-tokens docs]
   (let [definition-kind-scores (into {}
                                      (map (fn [definition-kind]
                                             [definition-kind
                                              (definition-kind-query-score
                                                query-tokens
                                                definition-kind)]))
                                      (distinct (map #(get-in % [:source :definitionKind])
                                                     docs)))]
     (loop [remaining (mapv #(diversify-doc-row definition-kind-scores %) docs)
            seen-roots #{}
            seen-paths #{}
            seen-definition-kinds #{}
            seen-root-definition-kinds #{}
            out []]
       (if (empty? remaining)
         out
         (let [[idx row] (selected-diversify-row remaining
                                                 seen-roots
                                                 seen-paths
                                                 seen-definition-kinds
                                                 seen-root-definition-kinds)
               doc (:doc row)
               definition-key (:definition-kind-key row)
               root-definition-kind-key (:root-definition-kind-key row)]
           (recur (vec (concat (subvec remaining 0 idx)
                               (subvec remaining (inc idx))))
                  (conj seen-roots (:root-key row))
                  (conj seen-paths (:path-key row))
                  (cond-> seen-definition-kinds
                    (second definition-key) (conj definition-key))
                  (cond-> seen-root-definition-kinds
                    (second root-definition-kind-key)
                    (conj root-definition-kind-key))
                  (conj out doc))))))))

(defn- doc-source-path
  [doc]
  (get-in doc [:source :path]))

(defn- ranked-result-paths
  [results limit]
  (->> results
       (keep :path)
       (remove #(str/blank? (str %)))
       distinct
       (take limit)
       vec))

(defn- ranked-path-coverage-limit
  [doc-limit]
  (when (pos? (long doc-limit))
    (min (long doc-limit)
         (max 1 (quot (long doc-limit) 2)))))

(defn- docs-by-path
  [docs]
  (reduce (fn [by-path doc]
            (if-let [path (doc-source-path doc)]
              (update by-path path #(or % doc))
              by-path))
          {}
          docs))

(defn- insert-doc-at
  [docs idx doc]
  (let [idx (min (max 0 (long idx)) (count docs))]
    (vec (concat (subvec docs 0 idx)
                 [doc]
                 (subvec docs idx)))))

(defn- remove-doc-at
  [docs idx]
  (vec (concat (subvec docs 0 idx)
               (subvec docs (inc idx)))))

(defn- replaceable-doc-index
  [docs protected-paths]
  (or (->> docs
           (map-indexed vector)
           (filter (fn [[_ doc]]
                     (and (not= "map-attachment" (:provenance doc))
                          (not (contains? protected-paths
                                          (doc-source-path doc))))))
           last
           first)
      (when (seq docs)
        (dec (count docs)))))

(defn- trim-docs-to-limit
  [docs limit protected-paths]
  (loop [docs (vec docs)]
    (if (<= (count docs) limit)
      docs
      (if-let [idx (replaceable-doc-index docs protected-paths)]
        (recur (remove-doc-at docs idx))
        docs))))

(defn- ensure-ranked-path-docs
  [docs result-paths limit]
  (let [limit (long limit)
        docs (vec docs)
        representatives (docs-by-path docs)
        protected-paths (set result-paths)]
    (loop [selected (vec (take limit docs))
           wanted (map-indexed vector result-paths)]
      (if-let [[idx path] (first wanted)]
        (let [represented? (some #(= path (doc-source-path %)) selected)
              representative (get representatives path)]
          (if (or represented? (nil? representative))
            (recur selected (next wanted))
            (recur (-> selected
                       (insert-doc-at idx representative)
                       (trim-docs-to-limit limit protected-paths))
                   (next wanted))))
        selected))))

(defn- doc-diversify-candidate-limit
  [doc-limit result-path-count]
  (max doc-diversify-candidate-limit-min
       (* doc-diversify-candidate-limit-multiplier
          (max 1 (long doc-limit)))
       (long result-path-count)))

(defn- doc-diversify-input
  [docs result-paths doc-limit]
  (let [candidate-limit (doc-diversify-candidate-limit doc-limit
                                                       (count result-paths))
        representatives (docs-by-path docs)]
    (loop [remaining (seq docs)
           taken 0
           result-paths (seq result-paths)
           seen #{}
           out []]
      (let [doc (cond
                  (and (< taken candidate-limit) remaining)
                  (first remaining)

                  result-paths
                  (get representatives (first result-paths)))]
        (cond
          (and (< taken candidate-limit) remaining)
          (let [k (distinct-doc-key doc)]
            (if (contains? seen k)
              (recur (next remaining) (inc taken) result-paths seen out)
              (recur (next remaining) (inc taken) result-paths (conj seen k) (conj out doc))))

          result-paths
          (if doc
            (let [k (distinct-doc-key doc)]
              (if (contains? seen k)
                (recur remaining taken (next result-paths) seen out)
                (recur remaining taken (next result-paths) (conj seen k) (conj out doc))))
            (recur remaining taken (next result-paths) seen out))

          :else out)))))

(defn- select-docs
  ([docs results doc-limit]
   (select-docs docs results doc-limit []))
  ([docs results doc-limit query-tokens]
   (let [doc-limit (long doc-limit)
         result-paths (ranked-result-paths results
                                           (or (ranked-path-coverage-limit doc-limit)
                                               0))
         docs (->> docs
                   distinct-docs
                   (sort-by (juxt doc-priority
                                  (comp - :score)
                                  :role
                                  #(get-in % [:source :path])))
                   vec)
         docs (-> docs
                  (doc-diversify-input result-paths doc-limit)
                  (#(diversify-docs query-tokens %)))]
     (-> docs
         (ensure-ranked-path-docs result-paths doc-limit)
         vec))))

(defn- attached-docs
  [overlay chunks snippet-chars targets]
  (let [system-label-by-id (into {}
                                 (keep (fn [{:keys [id label]}]
                                         (when (and id label)
                                           [id label])))
                                 (:systems overlay))]
    (->> (:docs overlay)
         (filter (fn [{:keys [target]}]
                   (or (contains? targets target)
                       (contains? targets (get system-label-by-id target)))))
         (filter #(not= "rejected" (s (:status %))))
         (map #(attachment->doc chunks snippet-chars %))
         vec)))

(defn- attachment-docs-for-targets
  [overlay targets]
  (let [system-label-by-id (into {}
                                 (keep (fn [{:keys [id label]}]
                                         (when (and id label)
                                           [id label])))
                                 (:systems overlay))]
    (->> (:docs overlay)
         (filter (fn [{:keys [target]}]
                   (or (contains? targets target)
                       (contains? targets (get system-label-by-id target)))))
         (filter #(not= "rejected" (s (:status %))))
         vec)))

(defn- result-chunk-ids
  [results]
  (->> results
       (filter #(= :chunk (:target-kind %)))
       (keep :target-id)
       distinct
       vec))

(defn- result-paths
  [results]
  (->> results
       (keep :path)
       (remove #(str/blank? (str %)))
       distinct
       vec))

(defn- attachment-paths
  [attachments]
  (->> attachments
       (keep (comp :path :source))
       (remove #(str/blank? (str %)))
       distinct
       vec))

(defn- attachment-chunks
  [xtdb attachments {:keys [project-id repo-id read-context]}]
  (query/chunks-by-paths
   xtdb
   (attachment-paths attachments)
   {:project-id project-id
    :repo-id repo-id
    :read-context read-context}))

(defn- context-chunks
  [xtdb results attachments candidate-inputs opts]
  (let [scope (select-keys opts [:project-id :repo-id :read-context])
        by-id (query/chunks-by-ids xtdb (result-chunk-ids results) scope)
        paths (distinct (concat (result-paths results)
                                (attachment-paths attachments)
                                (result-paths candidate-inputs)))
        by-path (query/chunks-by-paths xtdb paths scope)]
    (->> (concat by-id by-path)
         (reduce (fn [by-id chunk]
                   (assoc by-id (:xt/id chunk) chunk))
                 {})
         vals
         vec)))

(def ^:private doc-candidate-token-fields
  [:label :path :text])

(def ^:private doc-candidate-return-fields
  [:xt/id
   :project-id
   :repo-id
   :path
   :kind
   :definition-kind
   :label
   :text
   :heading-path
   :content-sha
   :source-line
   :end-line
   :active?])

(defn- doc-candidate-chunks
  [xtdb target-tokens {:keys [project-id read-context]}]
  (cond
    (empty? target-tokens)
    []

    (store/xtdb-handle? xtdb)
    (store/rows-matching-any-token
     xtdb
     (:chunks store/tables)
     doc-candidate-token-fields
     target-tokens
     {:project-id project-id
      :kind :markdown}
     (store/read-context read-context)
     doc-candidate-return-fields)

    :else
    (query/all-chunks xtdb {:project-id project-id
                            :read-context read-context})))

(defn- frequency-summary
  [f rows limit]
  (->> rows
       (keep f)
       frequencies
       (sort-by (juxt (comp - val) (comp str key)))
       (take limit)
       (mapv (fn [[value n]]
               {:value (display-name value)
                :count n}))))

(defn- representative-graph-nodes
  [nodes limit]
  (->> nodes
       (sort-by (juxt #(- (long (or (:degree %) 0)))
                      #(or (:label %) "")
                      #(or (:id %) "")))
       (take limit)
       (mapv (fn [node]
               (cond-> {:id (:id node)
                        :label (:label node)
                        :kind (:kind node)}
                 (:repo node) (assoc :repo (:repo node))
                 (:path node) (assoc :path (:path node))
                 (:pathPrefix node) (assoc :pathPrefix (:pathPrefix node))
                 (:degree node) (assoc :degree (:degree node)))))))

(defn- readiness-row-counts
  [counts graph-data]
  {:sourceFiles (long (or (:files counts) 0))
   :sourceNodes (long (or (:nodes counts) 0))
   :sourceEdges (long (or (:edges counts) 0))
   :systemNodes (long (or (:system-nodes counts)
                          (count (:nodes graph-data))))
   :systemEdges (long (or (:system-edges counts)
                          (count (:edges graph-data))))
   :searchDocs (long (or (:search-docs counts) 0))
   :chunks (long (or (:chunks counts) 0))
   :embeddings (long (or (:embeddings counts) 0))
   :correctionSystems (long (or (:correction-systems counts) 0))
   :activityItems (long (or (:activity-items counts) 0))})

(defn- retrieval-shape
  [search-context]
  (let [instrumentation (:instrumentation search-context)]
    (select-keys instrumentation
                 [:search-docs
                  :seed-count
                  :same-label-seed-count
                  :graph-edges-loaded
                  :graph-adjacency-strategy
                  :graph-adjacency-query-count
                  :graph-adjacency-source-query-count
                  :graph-adjacency-target-query-count
                  :graph-adjacency-seed-count
                  :graph-adjacency-loaded-rows
                  :neighbor-count
                  :candidate-count
                  :ranked-count
                  :returned-count
                  :context-chunks])))

(defn- graph-readiness
  [graph-data evidence search-context]
  (let [counts (:counts evidence)]
    {:schema "ygg.graph-readiness/v1"
     :status (:status evidence)
     :rowCounts (readiness-row-counts counts graph-data)
     :systemGraph {:nodeKinds (frequency-summary :kind (:nodes graph-data) 8)
                   :relations (frequency-summary :relation (:edges graph-data) 8)
                   :representativeNodes (representative-graph-nodes
                                         (:nodes graph-data)
                                         5)}
     :retrieval (retrieval-shape search-context)
     :missingPlanes (vec (:missing evidence))
     :weakPlanes (vec (:weak evidence))}))

(defn- graph-summary
  [graph-data evidence search-context]
  {:basis (:basis graph-data)
   :counts {:nodes (count (:nodes graph-data))
            :edges (count (:edges graph-data))
            :clusters (count (:clusters graph-data))}
   :readiness (graph-readiness graph-data evidence search-context)
   :defaultDetail "primary"})

(defn- candidate-files
  [results]
  (let [support-label-limit 4
        add-support-label (fn [state label]
                            (if (or (nil? label)
                                    (= (:primary-label state) label)
                                    (contains? (:seen state) label)
                                    (<= support-label-limit
                                        (count (:labels state))))
                              state
                              (-> state
                                  (update :seen conj label)
                                  (update :labels conj label))))
        add-candidate-primary-label (fn [state candidate]
                                      (let [label (not-empty (str (:label candidate)))]
                                        (cond-> state
                                          (and label (:sourceLine candidate))
                                          (add-support-label label))))
        add-candidate-support-labels (fn [state candidate]
                                       (reduce add-support-label
                                               state
                                               (:supportLabels candidate)))
        merged-support-labels (fn [primary-label earlier later]
                                (:labels
                                 (let [candidates [earlier later]]
                                   (-> (reduce add-candidate-primary-label
                                               {:primary-label primary-label
                                                :seen #{}
                                                :labels []}
                                               candidates)
                                       (as-> state
                                             (reduce add-candidate-support-labels
                                                     state
                                                     candidates))))))
        max-component-value (fn max-component-value [a b]
                              (cond
                                (and (number? a) (number? b))
                                (max (double a) (double b))

                                (number? a)
                                (double a)

                                (number? b)
                                (double b)

                                (and (map? a) (map? b))
                                (merge-with max-component-value a b)

                                (map? a)
                                a

                                (map? b)
                                b

                                :else
                                (or b a)))
        max-components (fn [a b]
                         (merge-with max-component-value
                                     (or a {})
                                     (or b {})))
        candidate-key (fn [row]
                        [(:repo row) (:path row)])
        merge-row (fn [existing row]
                    (let [earlier (if (< (:rank row) (:rank existing))
                                    row
                                    existing)
                          later (if (identical? earlier row) existing row)
                          support-labels (merged-support-labels (:label earlier)
                                                                earlier
                                                                later)]
                      (cond-> (assoc earlier
                                     :score (max (double (or (:score existing) 0.0))
                                                 (double (or (:score row) 0.0))))
                        (and (nil? (:sourceLine earlier))
                             (:sourceLine later))
                        (assoc :sourceLine (:sourceLine later))

                        (and (nil? (:endLine earlier))
                             (:endLine later))
                        (assoc :endLine (:endLine later))

                        (or (:scoreComponents existing)
                            (:scoreComponents row))
                        (assoc :scoreComponents
                               (max-components (:scoreComponents existing)
                                               (:scoreComponents row)))

                        (seq support-labels)
                        (assoc :supportLabels support-labels))))]
    (->> results
         (map-indexed
          (fn [idx result]
            (when-not (str/blank? (str (:path result)))
              (cond-> {:path (:path result)
                       :rank (inc idx)
                       :score (double (or (:score result) 0.0))
                       :targetKind (some-> (:target-kind result) name)
                       :label (:label result)}
                (:repo-id result) (assoc :repo (:repo-id result))
                (:repo result) (assoc :repo (:repo result))
                (:kind result) (assoc :kind (display-name (:kind result)))
                (:source-line result) (assoc :sourceLine (:source-line result))
                (:end-line result) (assoc :endLine (:end-line result))
                (:result-kind result) (assoc :resultKind (name (:result-kind result)))
                (:reason result) (assoc :reason (:reason result))
                (:supportLabels result) (assoc :supportLabels (:supportLabels result))
                (:score-components result) (assoc :scoreComponents
                                                  (:score-components result))))))
         (keep identity)
         (reduce (fn [best row]
                   (update best (candidate-key row) #(if %
                                                       (merge-row % row)
                                                       row)))
                 {})
         vals
         (sort-by (juxt :rank :repo :path))
         vec)))

(def ^:private architecture-candidate-file-limit
  32)

(def ^:private architecture-candidate-sections
  [[:runtimeEvidence "runtimeEvidence"]
   [:deployEvidence "deployEvidence"]
   [:dependencyEvidence "dependencyEvidence"]
   [:boundaryEvidence "boundaryEvidence"]])

(defn- architecture-candidate-label
  [row]
  (or (:candidateLabel row)
      (:candidate-label row)
      (compact (:kind row)
               (:fileKind row)
               (:file-kind row)
               (:label row)
               (:normalizedValue row)
               (:normalized-value row)
               (:package row)
               (:import row)
               (:importName row)
               (:relation row))))

(defn- architecture-candidate-kind
  [row]
  (or (:fileKind row)
      (:file-kind row)
      (:sourceKind row)
      (:source-kind row)
      (:kind row)))

(defn- architecture-candidate-support-labels
  [section row]
  (->> [(architecture-candidate-label row)
        section
        (:kind row)
        (:label row)
        (:normalizedValue row)
        (:normalized-value row)
        (:package row)
        (:import row)
        (:importName row)
        (:relation row)]
       (remove #(str/blank? (str %)))
       distinct
       vec))

(defn- architecture-candidate-row
  [base-rank section idx row]
  (let [path (not-empty (str (:path row)))
        score (double (or (:score row) 0.0))]
    (when (and path (pos? score))
      (cond-> {:path path
               :rank (+ base-rank idx 1)
               :score score
               :targetKind "file"
               :resultKind "file"
               :architectureEvidence true
               :architectureSection section
               :architectureKind (some-> (:kind row) display-name)
               :label (or (not-empty (architecture-candidate-label row))
                          path)
               :kind (some-> (architecture-candidate-kind row) display-name)
               :sourceLine (:sourceLine row)
               :supportLabels (architecture-candidate-support-labels section row)
               :reason (str "selected architecture " section " evidence")
               :scoreComponents {:sourceGraph score}}
        (:repo row) (assoc :repo (:repo row))
        (:repo-id row) (assoc :repo (:repo-id row))))))

(defn- architecture-candidate-file-rows
  [architecture base-rank]
  (->> architecture-candidate-sections
       (mapcat (fn [[section-key section]]
                 (keep-indexed #(architecture-candidate-row base-rank
                                                            section
                                                            %1
                                                            %2)
                               (get architecture section-key))))
       (take architecture-candidate-file-limit)
       vec))

(defn- source-graph-candidate-tokens
  [row]
  (text/tokenize-all
   (compact (:path row)
            (:label row)
            (:name row)
            (:kind row)
            (:file-kind row)
            (:normalized-value row))))

(defn- hex-like-token?
  [token]
  (boolean (re-matches #"[a-f0-9]{12,}" (str token))))

(defn- duplicate-terminal-punctuation-token?
  [token token-set]
  (let [token (str token)]
    (and (< 1 (count token))
         (contains? #{\. \, \; \: \! \?} (last token))
         (contains? token-set (subs token 0 (dec (count token)))))))

(defn- source-graph-query-token?
  [token token-set]
  (let [token (str token)]
    (and (not (str/blank? token))
         (<= (count token) source-graph-query-token-max-length)
         (not (hex-like-token? token))
         (not (duplicate-terminal-punctuation-token? token token-set)))))

(defn- source-graph-query-tokens
  [query-tokens]
  (let [tokens (vec (distinct query-tokens))
        token-set (set tokens)]
    (->> tokens
         (filter #(source-graph-query-token? % token-set))
         (take source-graph-query-token-limit)
         vec)))

(defn- matched-source-graph-query-tokens
  [distinct-query-tokens tokens]
  (let [row-token-set (set tokens)]
    (filterv row-token-set distinct-query-tokens)))

(defn- source-graph-token-data
  [distinct-query-tokens row]
  (let [tokens (source-graph-candidate-tokens row)]
    {:row row
     :tokens tokens
     :matched-tokens (matched-source-graph-query-tokens distinct-query-tokens
                                                        tokens)}))

(defn- source-graph-token-row-counts
  [token-data]
  (reduce
   (fn [counts {:keys [matched-tokens]}]
     (reduce #(update %1 %2 (fnil inc 0)) counts matched-tokens))
   {}
   token-data))

(defn- source-graph-rare-token-score
  [row-count token-row-counts matched-tokens]
  (->> matched-tokens
       (map (fn [token]
              (* source-graph-rare-token-weight
                 (Math/log1p (/ (double row-count)
                                (max 1.0
                                     (double (get token-row-counts token 1))))))))
       (reduce max 0.0)
       (min source-graph-rare-token-cap)))

(defn- source-graph-candidate-score
  ([query-tokens row]
   (text/token-score query-tokens (source-graph-candidate-tokens row)))
  ([query-tokens row-count token-row-counts row]
   (let [tokens (source-graph-candidate-tokens row)
         distinct-query-tokens (vec (distinct query-tokens))
         matched-tokens (matched-source-graph-query-tokens distinct-query-tokens
                                                           tokens)]
     (+ (text/token-score query-tokens tokens)
        (source-graph-rare-token-score row-count
                                       token-row-counts
                                       matched-tokens)))))

(defn- source-graph-candidate-score-from-token-data
  [query-tokens row-count token-row-counts {:keys [tokens matched-tokens]}]
  (+ (text/token-score query-tokens tokens)
     (source-graph-rare-token-score row-count
                                    token-row-counts
                                    matched-tokens)))

(defn- source-graph-visible-score
  [score]
  (min source-graph-visible-score-cap
       (double (or score 0.0))))

(defn- row-id
  [row]
  (or (:xt/id row)
      (:id row)
      (:_id row)
      (get row "_id")))

(defn- source-graph-candidate-row-from-token-data
  [query-tokens row-count token-row-counts {:keys [row] :as token-data}]
  (let [path (not-empty (str (:path row)))
        score (source-graph-candidate-score-from-token-data query-tokens
                                                            row-count
                                                            token-row-counts
                                                            token-data)]
    (when (and path
               (<= source-graph-candidate-min-score score))
      (let [file-row? (nil? (:label row))
            target-kind (or (::source-graph-target-kind row)
                            (if file-row? :file :node))
            result-kind (or (::source-graph-result-kind row)
                            (if file-row? :file :node))]
        (cond-> {:path path
                 :rank 0
                 :score (source-graph-visible-score score)
                 ::source-graph-raw-score score
                 :target-kind target-kind
                 :target-id (row-id row)
                 :label (or (:label row) path)
                 :kind (:kind row)
                 :result-kind result-kind
                 :reason (or (::source-graph-reason row)
                             "query-matched source row")
                 :score-components {:sourceGraph (source-graph-visible-score
                                                  score)}}
          (:repo-id row) (assoc :repo-id (:repo-id row)
                                :repo (:repo-id row))
          (:source-line row) (assoc :source-line (:source-line row))
          (:end-line row) (assoc :end-line (:end-line row)))))))

(defn- path-file-stem
  [file-name]
  (let [file-name (str file-name)
        idx (.lastIndexOf file-name ".")]
    (if (pos? idx)
      (subs file-name 0 idx)
      file-name)))

(defn- path-self-identity-token
  [path]
  (let [parts (->> (str/split (str path) #"/")
                   (remove str/blank?)
                   vec)]
    (when (<= 2 (count parts))
      (let [parent (str/lower-case (nth parts (- (count parts) 2)))
            stem (str/lower-case (path-file-stem (peek parts)))]
        (when (and (not (str/blank? parent))
                   (= parent stem))
          stem)))))

(defn- path-self-identity-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])

(defn- query-matched-path-self-identity-row?
  [query-token-set row]
  (when-let [token (path-self-identity-token (:path row))]
    (contains? query-token-set token)))

(defn- reserve-query-matched-path-self-identity
  [query-token-set rows]
  (if (empty? query-token-set)
    rows
    (let [{:keys [selected selected-keys]}
          (loop [remaining (seq rows)
                 selected []
                 selected-keys #{}]
            (if (or (nil? remaining)
                    (<= path-self-identity-reserve-limit
                        (count selected)))
              {:selected selected
               :selected-keys selected-keys}
              (let [row (first remaining)
                    row-key (path-self-identity-key row)]
                (if (and (not (contains? selected-keys row-key))
                         (query-matched-path-self-identity-row?
                          query-token-set
                          row))
                  (recur (next remaining)
                         (conj selected row)
                         (conj selected-keys row-key))
                  (recur (next remaining) selected selected-keys)))))]
      (concat selected
              (remove #(contains? selected-keys
                                  (path-self-identity-key %))
                      rows)))))

(defn- ranked-source-graph-candidates
  [query-tokens rows limit]
  (let [distinct-query-tokens (vec (distinct query-tokens))
        token-data (mapv #(source-graph-token-data distinct-query-tokens %) rows)
        row-count (count token-data)
        token-row-counts (source-graph-token-row-counts token-data)
        query-token-set (set distinct-query-tokens)]
    (->> token-data
         (keep #(source-graph-candidate-row-from-token-data query-tokens
                                                            row-count
                                                            token-row-counts
                                                            %))
         (sort-by (juxt (comp - ::source-graph-raw-score)
                        :repo
                        :path
                        :label))
         (reserve-query-matched-path-self-identity query-token-set)
         (take limit)
         (map-indexed #(assoc %2 :rank (inc %1)))
         vec)))

(defn- source-graph-neighbor-id
  [seed-ids {:keys [source-id target-id]}]
  (cond
    (and (contains? seed-ids source-id)
         (not (contains? seed-ids target-id)))
    target-id

    (and (contains? seed-ids target-id)
         (not (contains? seed-ids source-id)))
    source-id))

(defn- source-graph-neighbor-score
  [seed edge]
  (min source-graph-neighbor-score-cap
       (+ (double (or (:score seed) 0.0))
          source-graph-neighbor-support-boost
          (double (get query/relation-graph-weights (:relation edge) 0.25)))))

(defn- source-graph-neighbor-row
  [node score support-labels seed-rank]
  (when-let [path (not-empty (str (:path node)))]
    (let [support-labels (->> support-labels
                              (map (fn [entry]
                                     (if (map? entry)
                                       {:rank (or (:rank entry) Long/MAX_VALUE)
                                        :label (:label entry)}
                                       {:rank Long/MAX_VALUE
                                        :label entry})))
                              (remove #(str/blank? (str (:label %))))
                              (sort-by (juxt :rank :label))
                              (map :label)
                              distinct
                              (take 4)
                              vec)]
      (cond-> {:path path
               :rank 0
               :score (source-graph-visible-score score)
               ::source-graph-raw-score score
               :target-kind :node
               :target-id (:xt/id node)
               :label (or (:label node) path)
               :kind (:kind node)
               :result-kind :node
               :reason "graph-neighbor source row"
               :score-components {:sourceGraph (source-graph-visible-score
                                                score)}}
        (:repo-id node) (assoc :repo-id (:repo-id node)
                               :repo (:repo-id node))
        seed-rank (assoc ::neighbor-seed-rank seed-rank)
        (:source-line node) (assoc :source-line (:source-line node))
        (:end-line node) (assoc :end-line (:end-line node))
        (seq support-labels) (assoc :supportLabels support-labels)))))

(defn- source-graph-neighbor-path-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])

(defn- source-graph-neighbor-kind-key
  [row]
  (or (:kind row) :unknown))

(defn- source-graph-kind-path-reserve
  [rows]
  (loop [remaining (seq rows)
         kind-counts {}
         path-counts {}
         selected []]
    (if-let [row (first remaining)]
      (let [kind-key (source-graph-neighbor-kind-key row)
            path-key (source-graph-neighbor-path-key row)
            kind-count (long (or (get kind-counts kind-key) 0))
            path-count (long (or (get path-counts path-key) 0))]
        (if (or (<= source-graph-neighbor-kind-path-limit kind-count)
                (<= source-graph-neighbor-path-limit path-count))
          (recur (next remaining) kind-counts path-counts selected)
          (recur (next remaining)
                 (update kind-counts kind-key (fnil inc 0))
                 (update path-counts path-key (fnil inc 0))
                 (conj selected row))))
      selected)))

(defn- select-source-graph-kind-path-diversity
  [rows limit]
  (let [kind-path-rows (source-graph-kind-path-reserve rows)
        selected-paths (set (map source-graph-neighbor-path-key kind-path-rows))]
    (->> (concat kind-path-rows
                 (remove #(contains? selected-paths
                                     (source-graph-neighbor-path-key %))
                         rows))
         (take limit)
         vec)))

(defn- select-source-graph-neighbor-rows
  [rows]
  (select-source-graph-kind-path-diversity
   rows
   source-graph-neighbor-candidate-limit))

(defn- source-graph-row-query-token-count
  [query-tokens row]
  (count
   (matched-source-graph-query-tokens
    (vec (distinct query-tokens))
    (text/tokenize-all (compact (:path row)
                                (:label row)
                                (:kind row)
                                (:supportLabels row))))))

(defn- source-graph-second-hop-row?
  [query-tokens row]
  (<= source-graph-second-hop-neighbor-token-min
      (source-graph-row-query-token-count query-tokens row)))

(defn- source-graph-second-hop-support-labels
  [row]
  (let [rank (long (or (:rank row) Long/MAX_VALUE))]
    (->> (concat [{:rank rank
                   :label (:label row)}]
                 (map-indexed (fn [idx label]
                                {:rank (+ rank (/ (inc idx) 10.0))
                                 :label label})
                              (:supportLabels row)))
         (remove #(str/blank? (str (:label %))))
         vec)))

(defn- source-graph-second-hop-score
  [seed edge]
  (* source-graph-second-hop-score-decay
     (source-graph-neighbor-score seed edge)))

(defn- source-graph-second-hop-neighbor-candidates
  [xtdb query-tokens seed-candidates first-hop-rows scope]
  (let [original-seed-ids (set (keep :target-id seed-candidates))
        seeds (->> first-hop-rows
                   (filter #(= :node (:target-kind %)))
                   (filter :target-id)
                   (take source-graph-second-hop-neighbor-seed-limit)
                   vec)
        seed-by-id (into {} (map (juxt :target-id identity)) seeds)
        seed-ids (set (keys seed-by-id))]
    (if (empty? seed-ids)
      []
      (let [edges (->> (query/edges-touching-node-ids xtdb seed-ids scope)
                       (filter active-row?)
                       vec)
            candidate-state
            (reduce (fn [out edge]
                      (if-let [neighbor-id (source-graph-neighbor-id seed-ids edge)]
                        (if (or (contains? original-seed-ids neighbor-id)
                                (contains? seed-ids neighbor-id))
                          out
                          (let [seed-id (if (contains? seed-ids (:source-id edge))
                                          (:source-id edge)
                                          (:target-id edge))
                                seed (get seed-by-id seed-id)
                                score (source-graph-second-hop-score seed edge)]
                            (-> out
                                (update-in [neighbor-id :score]
                                           #(max (double (or % 0.0)) score))
                                (update-in [neighbor-id :seed-rank]
                                           #(min (long (or % Long/MAX_VALUE))
                                                 (long (or (:rank seed)
                                                           Long/MAX_VALUE))))
                                (update-in [neighbor-id :support-labels]
                                           (fnil into [])
                                           (source-graph-second-hop-support-labels
                                            seed)))))
                        out))
                    {}
                    edges)]
        (if-not (seq candidate-state)
          []
          (let [nodes-by-id (into {}
                                  (map (juxt :xt/id identity))
                                  (query/nodes-by-ids xtdb
                                                      (keys candidate-state)
                                                      scope))]
            (->> candidate-state
                 (keep (fn [[node-id {:keys [score seed-rank support-labels]}]]
                         (when-let [node (get nodes-by-id node-id)]
                           (source-graph-neighbor-row node
                                                      score
                                                      support-labels
                                                      seed-rank))))
                 (filter #(source-graph-second-hop-row? query-tokens %))
                 (sort-by (juxt (comp - :score)
                                (comp - ::source-graph-raw-score)
                                ::neighbor-seed-rank
                                :repo-id
                                :path
                                :label))
                 select-source-graph-neighbor-rows
                 (take source-graph-second-hop-neighbor-candidate-limit)
                 (map #(dissoc % ::neighbor-seed-rank ::source-graph-raw-score))
                 (map-indexed #(assoc %2 :rank (inc %1)))
                 vec)))))))

(defn- source-graph-neighbor-candidates
  [xtdb seed-candidates scope]
  (let [seeds (->> seed-candidates
                   (filter #(= :node (:target-kind %)))
                   (filter :target-id)
                   (take source-graph-neighbor-seed-limit)
                   vec)
        seed-by-id (into {} (map (juxt :target-id identity)) seeds)
        seed-ids (set (keys seed-by-id))]
    (if (empty? seed-ids)
      []
      (let [edges (->> (query/edges-touching-node-ids xtdb seed-ids scope)
                       (filter active-row?)
                       vec)
            candidate-state
            (reduce (fn [out edge]
                      (if-let [neighbor-id (source-graph-neighbor-id seed-ids edge)]
                        (let [seed-id (if (contains? seed-ids (:source-id edge))
                                        (:source-id edge)
                                        (:target-id edge))
                              seed (get seed-by-id seed-id)
                              score (source-graph-neighbor-score seed edge)]
                          (-> out
                              (update-in [neighbor-id :score]
                                         #(max (double (or % 0.0)) score))
                              (update-in [neighbor-id :seed-rank]
                                         #(min (long (or % Long/MAX_VALUE))
                                               (long (or (:rank seed)
                                                         Long/MAX_VALUE))))
                              (update-in [neighbor-id :support-labels]
                                         (fnil conj [])
                                         {:rank (:rank seed)
                                          :label (:label seed)})))
                        out))
                    {}
                    edges)]
        (if-not (seq candidate-state)
          []
          (let [nodes-by-id (into {}
                                  (map (juxt :xt/id identity))
                                  (query/nodes-by-ids xtdb
                                                      (keys candidate-state)
                                                      scope))]
            (->> candidate-state
                 (keep (fn [[node-id {:keys [score seed-rank support-labels]}]]
                         (when-let [node (get nodes-by-id node-id)]
                           (source-graph-neighbor-row node
                                                      score
                                                      support-labels
                                                      seed-rank))))
                 (sort-by (juxt (comp - :score)
                                (comp - ::source-graph-raw-score)
                                ::neighbor-seed-rank
                                :repo-id
                                :path
                                :label))
                 select-source-graph-neighbor-rows
                 (map #(dissoc % ::neighbor-seed-rank ::source-graph-raw-score))
                 (map-indexed #(assoc %2 :rank (inc %1)))
                 vec)))))))

(defn- source-graph-scope-constraints
  [project-id repo-id]
  (cond-> {:project-id project-id}
    repo-id (assoc :repo-id repo-id)))

(defn- search-result-source-row
  [row]
  (let [target-kind (:target-kind row)]
    (when (#{:file :file-fact :node} target-kind)
      (assoc row
             :xt/id (:target-id row)
             ::source-graph-target-kind target-kind
             ::source-graph-result-kind (if (= :file-fact target-kind)
                                          :file
                                          target-kind)
             ::source-graph-reason (if (= :file-fact target-kind)
                                     "query-matched file fact"
                                     "query-matched source row")))))

(defn- source-graph-dirname
  [path]
  (let [path (str path)
        idx (.lastIndexOf path "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn- source-graph-pattern-prefix
  [pattern]
  (let [pattern (str pattern)]
    (if-let [idx (str/index-of pattern "*")]
      (subs pattern 0 idx)
      pattern)))

(defn- source-graph-alias-pattern
  [alias-row]
  (when-let [[_ source target]
             (re-matches #"^(.+?)=(.+)$" (str (:label alias-row)))]
    {:source source
     :target target
     :base-dir (or (source-graph-dirname (:path alias-row)) "")}))

(defn- source-graph-relative-path
  [base-dir path]
  (let [path (str path)
        base-dir (str base-dir)]
    (cond
      (str/blank? base-dir) path
      (= path base-dir) ""
      (str/starts-with? path (str base-dir "/")) (subs path (inc (count base-dir)))
      :else nil)))

(defn- source-graph-replace-star
  [pattern suffix]
  (let [pattern (str pattern)
        suffix (str suffix)]
    (if-let [idx (str/index-of pattern "*")]
      (str (subs pattern 0 idx)
           suffix
           (subs pattern (inc idx)))
      pattern)))

(defn- source-graph-alias-import-label
  [path {:keys [source target base-dir]}]
  (when-let [relative-path (source-graph-relative-path base-dir path)]
    (let [target-prefix (source-graph-pattern-prefix target)]
      (when (or (= relative-path target-prefix)
                (str/starts-with? relative-path target-prefix))
        (let [suffix (subs relative-path
                           (min (count relative-path)
                                (count target-prefix)))]
          (source-graph-replace-star source suffix))))))

(defn- source-graph-local-import-labels
  [alias-patterns path]
  (->> alias-patterns
       (keep #(source-graph-alias-import-label path %))
       (remove str/blank?)
       distinct))

(defn- source-graph-local-import-seeds
  [seed-candidates]
  (->> seed-candidates
       (filter #(= :node (:target-kind %)))
       (filter :path)
       (take source-graph-local-importer-seed-limit)))

(defn- source-graph-local-import-label-seeds
  [alias-patterns seed-candidates]
  (let [prefer-seed (fn [existing seed]
                      (if (or (nil? existing)
                              (< (long (or (:rank seed) Long/MAX_VALUE))
                                 (long (or (:rank existing) Long/MAX_VALUE))))
                        seed
                        existing))]
    (->> (source-graph-local-import-seeds seed-candidates)
         (reduce (fn [label-seeds seed]
                   (reduce (fn [label-seeds label]
                             (update label-seeds label prefer-seed seed))
                           label-seeds
                           (source-graph-local-import-labels alias-patterns
                                                             (:path seed))))
                 {})
         (take source-graph-local-importer-label-limit)
         (into {}))))

(defn- source-graph-local-importer-support-labels
  [seed import-node]
  (->> [(:label seed)
        (:path seed)
        (:label import-node)]
       (remove str/blank?)
       distinct
       (take 4)
       vec))

(defn- source-graph-local-importer-row
  [seed import-node]
  (when-let [path (not-empty (str (:path import-node)))]
    (let [score (source-graph-neighbor-score seed {:relation :imports})
          visible-score (source-graph-visible-score score)]
      (cond-> {:path path
               :rank (long (or (:rank seed) Long/MAX_VALUE))
               :score visible-score
               :target-kind :node
               :target-id (:xt/id import-node)
               :label (or (:label import-node) path)
               :kind (:kind import-node)
               :result-kind :node
               :reason "local import alias source row"
               :score-components {:sourceGraph visible-score}
               :supportLabels (source-graph-local-importer-support-labels
                               seed
                               import-node)}
        (:repo-id import-node) (assoc :repo-id (:repo-id import-node)
                                      :repo (:repo-id import-node))
        (:source-line import-node) (assoc :source-line (:source-line import-node))
        (:end-line import-node) (assoc :end-line (:end-line import-node))))))

(defn- source-graph-local-importer-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row) (:label row) (:source-line row)])

(defn- source-graph-local-importer-candidates
  [xtdb seed-candidates {:keys [project-id repo-id read-context] :as scope}]
  (if-not (and (store/xtdb-handle? xtdb)
               (map? xtdb))
    []
    (let [read-context (if (map? read-context) read-context {})
          alias-patterns (->> (store/constrained-rows
                               xtdb
                               (:nodes store/tables)
                               (assoc (source-graph-scope-constraints project-id repo-id)
                                      :kind :module-path-alias
                                      :active? true)
                               read-context)
                              (keep source-graph-alias-pattern)
                              vec)
          label-seeds (source-graph-local-import-label-seeds alias-patterns
                                                             seed-candidates)
          labels (vec (keys label-seeds))]
      (if (or (empty? alias-patterns)
              (empty? labels))
        []
        (->> (query/nodes-by-labels xtdb labels scope)
             (filter active-row?)
             (keep (fn [import-node]
                     (when-let [seed (get label-seeds (:label import-node))]
                       (source-graph-local-importer-row seed import-node))))
             (reduce (fn [rows row]
                       (update rows (source-graph-local-importer-key row)
                               #(if (or (nil? %)
                                        (< (long (:rank row))
                                           (long (:rank %))))
                                  row
                                  %)))
                     {})
             vals
             (sort-by (juxt :rank :repo-id :path :source-line :label))
             (take source-graph-local-importer-candidate-limit)
             (map-indexed #(assoc %2 :rank (inc %1)))
             vec)))))

(defn expanded-source-graph-candidates
  "Return opt-in multi-hop source candidates for graph retrieval ablations."
  [xtdb query-tokens seed-candidates scope]
  (let [query-tokens (source-graph-query-tokens query-tokens)
        neighbor-candidates (source-graph-neighbor-candidates
                             xtdb
                             seed-candidates
                             scope)]
    (vec
     (concat
      neighbor-candidates
      (source-graph-second-hop-neighbor-candidates
       xtdb
       query-tokens
       seed-candidates
       neighbor-candidates
       scope)
      (source-graph-local-importer-candidates xtdb
                                              seed-candidates
                                              scope)))))

(defn- source-graph-candidates
  [xtdb query-tokens search-results _scope]
  (if-not (store/xtdb-handle? xtdb)
    {:strategy :ranked-search-results
     :graph-expansion :search-report
     :search-result-count (count search-results)
     :seed-count 0
     :node-seed-count 0
     :file-seed-count 0
     :candidates []}
    (let [source-rows (keep search-result-source-row search-results)
          node-rows (filter #(= :node (::source-graph-target-kind %))
                            source-rows)
          file-rows (filter #(#{:file :file-fact}
                              (::source-graph-target-kind %))
                            source-rows)
          matched-node-candidates (ranked-source-graph-candidates
                                   query-tokens
                                   node-rows
                                   source-graph-neighbor-scan-limit)
          node-candidates (select-source-graph-kind-path-diversity
                           matched-node-candidates
                           source-graph-candidate-limit)
          candidates (vec
                      (concat
                       node-candidates
                       (ranked-source-graph-candidates
                        query-tokens
                        file-rows
                        source-graph-file-candidate-limit)))]
      {:strategy :ranked-search-results
       :graph-expansion :search-report
       :search-result-count (count search-results)
       :seed-count (count source-rows)
       :node-seed-count (count node-rows)
       :file-seed-count (count file-rows)
       :candidates candidates})))

(defn- candidate-file-name
  [path]
  (let [path (str path)
        idx (.lastIndexOf path "/")]
    (if (neg? idx)
      path
      (subs path (inc idx)))))

(defn- candidate-file-directory
  [path]
  (let [path (str path)
        idx (.lastIndexOf path "/")]
    (if (neg? idx)
      ""
      (subs path 0 idx))))

(defn- candidate-file-family-parts
  [path]
  (let [filename (candidate-file-name path)
        idx (.lastIndexOf filename ".")]
    (when (pos? idx)
      (let [stem (subs filename 0 idx)
            ext (subs filename idx)
            family (first (str/split stem #"\."))]
        (when-not (or (str/blank? family)
                      (str/blank? ext))
          {:dir (candidate-file-directory path)
           :filename filename
           :stem stem
           :family family
           :family-key (str/lower-case family)
           :ext (str/lower-case ext)})))))

(defn- candidate-file-row-repo
  [row]
  (some-> (or (:repo row) (:repo-id row)) str not-empty))

(defn- candidate-file-row-key
  [row]
  [(candidate-file-row-repo row) (:path row)])

(defn- candidate-file-family-key
  [repo parts]
  [repo (:dir parts) (:ext parts) (:family-key parts)])

(defn- candidate-file-sibling-search-tokens
  [candidate-file-rows]
  (->> candidate-file-rows
       (take candidate-file-sibling-query-limit)
       (keep (comp :family candidate-file-family-parts :path))
       (mapcat text/tokenize-all)
       distinct
       (take candidate-file-sibling-token-limit)
       vec))

(defn- indexed-candidate-file-family-rows
  [xtdb candidate-file-rows {:keys [project-id repo-id read-context]}]
  (let [tokens (candidate-file-sibling-search-tokens candidate-file-rows)]
    (if (empty? tokens)
      []
      (store/rows-matching-any-token
       xtdb
       (:files store/tables)
       [:path]
       tokens
       (assoc (source-graph-scope-constraints project-id repo-id)
              :active? true)
       read-context))))

(defn- indexed-candidate-file-families
  [indexed-file-rows]
  (->> indexed-file-rows
       (keep (fn [row]
               (when-let [parts (candidate-file-family-parts (:path row))]
                 [(candidate-file-family-key (candidate-file-row-repo row)
                                             parts)
                  row])))
       (group-by first)
       (map (fn [[k rows]]
              [k (->> rows
                      (map second)
                      (sort-by :path)
                      vec)]))
       (into {})))

(defn- candidate-file-sibling-support-labels
  [candidate]
  (->> (concat [(:path candidate) (:label candidate)]
               (:supportLabels candidate))
       (remove #(str/blank? (str %)))
       distinct
       (take 4)
       vec))

(defn- candidate-file-sibling-source-score
  [candidate]
  (let [components (:scoreComponents candidate)
        source-score (or (:sourceGraph components)
                         (:score candidate)
                         0.0)]
    (min source-graph-visible-score-cap
         (double source-score))))

(defn- candidate-file-sibling-row
  [candidate indexed-file-row]
  (let [path (:path indexed-file-row)
        score (* 0.85 (double (or (:score candidate) 0.0)))
        source-score (candidate-file-sibling-source-score candidate)
        repo (or (candidate-file-row-repo indexed-file-row)
                 (candidate-file-row-repo candidate))]
    (cond-> {:path path
             :rank (:rank candidate)
             :score score
             :targetKind "file"
             :resultKind "file"
             :label path
             :reason (str "same-stem indexed file sibling of "
                          (:path candidate))
             :supportLabels (candidate-file-sibling-support-labels candidate)
             :scoreComponents {:sourceGraph source-score}}
      repo (assoc :repo repo)
      (:kind indexed-file-row) (assoc :kind (display-name
                                             (:kind indexed-file-row))))))

(defn- candidate-file-sibling-path-score
  [query-tokens row]
  (text/token-score query-tokens (text/tokenize-all (:path row))))

(defn- candidate-file-sibling-sort-key
  [query-tokens row]
  [(- (candidate-file-sibling-path-score query-tokens row))
   (:path row)])

(defn- candidate-file-sibling-rows
  [query-tokens indexed-families seen candidate]
  (when-let [parts (candidate-file-family-parts (:path candidate))]
    (let [family-key (candidate-file-family-key (candidate-file-row-repo
                                                 candidate)
                                                parts)
          family-rows (get indexed-families family-key)]
      (when (seq family-rows)
        (->> family-rows
             (remove #(contains? seen (candidate-file-row-key %)))
             (remove #(= (:path candidate) (:path %)))
             (sort-by #(candidate-file-sibling-sort-key query-tokens %))
             (take candidate-file-sibling-per-candidate-limit)
             (map #(candidate-file-sibling-row candidate %)))))))

(defn- rerank-candidate-files
  [rows]
  (->> rows
       (map-indexed #(assoc %2 :rank (inc %1)))
       vec))

(def ^:private support-owner-source-node-kinds
  #{"namespace"
    "class"
    "function"
    "interface"
    "method"
    "module"
    "protocol"
    "record"
    "type"
    "var"})

(defn- candidate-file-support-owner-seed?
  [candidate]
  (and (= "node" (display-name (:targetKind candidate)))
       (= "node" (display-name (:resultKind candidate)))
       (:sourceLine candidate)
       (contains? support-owner-source-node-kinds
                  (display-name (:kind candidate)))
       (pos? (double (or (get-in candidate [:scoreComponents :sourceGraph])
                         0.0)))))

(defn- candidate-file-support-owner-labels
  [candidate-file-rows]
  (->> candidate-file-rows
       (take candidate-file-support-owner-query-limit)
       (filter candidate-file-support-owner-seed?)
       (mapcat :supportLabels)
       (remove #(str/blank? (str %)))
       distinct
       (take candidate-file-support-owner-label-limit)
       vec))

(defn- support-owner-query-score
  [query-tokens label node]
  (text/token-score query-tokens
                    (text/tokenize-all (compact label
                                                (:path node)
                                                (:name node)
                                                (:namespace node)))))

(defn- support-owner-label-query-score
  [query-tokens label]
  (text/token-score query-tokens (text/tokenize-all label)))

(defn- support-owner-label-specific-query-token-count
  [query-tokens label]
  (let [query-token-set (set query-tokens)
        label-token-set (set (text/tokenize-all label))]
    (->> (set/intersection query-token-set label-token-set)
         (filter #(<= candidate-file-support-owner-specific-token-min-length
                      (count %)))
         count)))

(defn- support-owner-candidates-by-path
  [query-tokens support-labels nodes-by-label]
  (->> support-labels
       (mapcat (fn [label]
                 (let [label-query-score (support-owner-label-query-score
                                          query-tokens
                                          label)
                       label-specific-token-count
                       (support-owner-label-specific-query-token-count
                        query-tokens
                        label)]
                   (map (fn [node]
                          {:label label
                           :node node
                           :label-specific-token-count
                           label-specific-token-count
                           :label-query-score label-query-score
                           :query-score (support-owner-query-score query-tokens
                                                                   label
                                                                   node)})
                        (get nodes-by-label label [])))))
       (filter #(pos? (long (:label-specific-token-count %))))
       (filter #(pos? (double (:label-query-score %))))
       (filter #(pos? (double (:query-score %))))
       (filter (comp active-row? :node))
       (filter (comp not-empty str :path :node))
       (reduce (fn [best {:keys [node query-score] :as candidate}]
                 (let [k [(candidate-file-row-repo node) (:path node)]
                       existing (get best k)
                       candidate-key [(- (double query-score))
                                      (:label candidate)]
                       existing-key (when existing
                                      [(- (double (:query-score existing)))
                                       (:label existing)])]
                   (if (or (nil? existing)
                           (neg? (compare candidate-key existing-key)))
                     (assoc best k candidate)
                     best)))
               {})
       vals
       (sort-by (juxt (comp - :query-score) (comp :path :node) :label))
       vec))

(defn- candidate-file-support-owner-support-labels
  [candidate owner-label]
  (->> (concat [owner-label (:path candidate) (:label candidate)]
               (:supportLabels candidate))
       (remove #(str/blank? (str %)))
       distinct
       (take 6)
       vec))

(defn- candidate-file-support-owner-row
  [candidate owner-label owner-node]
  (let [path (:path owner-node)
        score (* 0.9 (double (or (:score candidate) 0.0)))
        source-score (candidate-file-sibling-source-score candidate)
        repo (or (candidate-file-row-repo owner-node)
                 (candidate-file-row-repo candidate))]
    (cond-> {:path path
             :rank (:rank candidate)
             :score score
             :targetKind "file"
             :resultKind "file"
             :label path
             :supportOwnerEvidence true
             :reason (str "indexed support label owner of "
                          (:path candidate))
             :supportLabels (candidate-file-support-owner-support-labels
                             candidate
                             owner-label)
             :scoreComponents {:sourceGraph source-score}}
      repo (assoc :repo repo)
      (:kind owner-node) (assoc :kind (display-name (:kind owner-node)))
      (:source-line owner-node) (assoc :sourceLine (:source-line owner-node))
      (:end-line owner-node) (assoc :endLine (:end-line owner-node)))))

(defn- candidate-file-support-owner-rows
  [query-tokens nodes-by-label seen candidate]
  (let [support-labels (->> (:supportLabels candidate)
                            (remove #(str/blank? (str %)))
                            distinct
                            vec)]
    (->> (support-owner-candidates-by-path query-tokens
                                           support-labels
                                           nodes-by-label)
         (remove (fn [{:keys [node]}]
                   (let [k (candidate-file-row-key node)]
                     (contains? seen k))))
         (take candidate-file-support-owner-per-candidate-limit)
         (map (fn [{:keys [label node]}]
                (candidate-file-support-owner-row candidate label node))))))

(defn- expand-candidate-file-support-owners
  [xtdb query-tokens candidate-file-rows scope]
  (if (or (empty? candidate-file-rows)
          (not (store/xtdb-handle? xtdb)))
    candidate-file-rows
    (let [support-labels (candidate-file-support-owner-labels candidate-file-rows)
          nodes-by-label (when (seq support-labels)
                           (->> (query/nodes-by-labels xtdb support-labels scope)
                                (filter active-row?)
                                (group-by :label)))]
      (if (empty? nodes-by-label)
        candidate-file-rows
        (loop [remaining (seq candidate-file-rows)
               seen #{}
               selected []
               added 0]
          (if-let [candidate (first remaining)]
            (let [candidate-key (candidate-file-row-key candidate)]
              (if (contains? seen candidate-key)
                (recur (next remaining) seen selected added)
                (let [seen (conj seen candidate-key)
                      owners (if (and (< added candidate-file-support-owner-limit)
                                      (candidate-file-support-owner-seed?
                                       candidate))
                               (vec (candidate-file-support-owner-rows
                                     query-tokens
                                     nodes-by-label
                                     seen
                                     candidate))
                               [])
                      owner-count (min (count owners)
                                       (- candidate-file-support-owner-limit
                                          added))
                      owners (subvec owners 0 owner-count)]
                  (recur (next remaining)
                         (into seen (map candidate-file-row-key owners))
                         (into (conj selected candidate) owners)
                         (+ added owner-count)))))
            (if (pos? added)
              (rerank-candidate-files selected)
              candidate-file-rows)))))))

(defn- expand-candidate-file-siblings
  [xtdb query-tokens candidate-file-rows scope]
  (if (or (empty? candidate-file-rows)
          (not (store/xtdb-handle? xtdb)))
    candidate-file-rows
    (let [indexed-families (indexed-candidate-file-families
                            (indexed-candidate-file-family-rows
                             xtdb
                             candidate-file-rows
                             scope))]
      (if (empty? indexed-families)
        candidate-file-rows
        (loop [remaining (seq candidate-file-rows)
               seen #{}
               selected []
               added 0]
          (if-let [candidate (first remaining)]
            (let [candidate-key (candidate-file-row-key candidate)]
              (if (contains? seen candidate-key)
                (recur (next remaining) seen selected added)
                (let [seen (conj seen candidate-key)
                      siblings (if (< added candidate-file-sibling-limit)
                                 (vec (candidate-file-sibling-rows
                                       query-tokens
                                       indexed-families
                                       seen
                                       candidate))
                                 [])
                      sibling-count (min (count siblings)
                                         (- candidate-file-sibling-limit added))
                      siblings (subvec siblings 0 sibling-count)]
                  (recur (next remaining)
                         (into seen (map candidate-file-row-key siblings))
                         (into (conj selected candidate) siblings)
                         (+ added sibling-count)))))
            (if (pos? added)
              (rerank-candidate-files selected)
              candidate-file-rows)))))))

(defn- candidate-input-score
  [row]
  (double (or (::source-graph-raw-score row)
              (:score row)
              0.0)))

(defn- candidate-input-path-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])

(defn- candidate-input-root
  [row]
  (let [path (str (:path row))]
    (or (first (str/split path #"/"))
        path)))

(defn- candidate-input-path-depth
  [row]
  (->> (str/split (str (:path row)) #"/")
       (remove str/blank?)
       count))

(defn- source-graph-declaration-row?
  [row]
  (let [path (not-empty (str (:path row)))
        label (not-empty (str (:label row)))
        target-kind (:target-kind row)
        result-kind (:result-kind row)
        kind (:kind row)]
    (and path
         label
         (:source-line row)
         (not= :file target-kind)
         (not= :file result-kind)
         (not (contains? source-graph-non-declaration-kinds kind))
         (not= label path))))

(defn- query-source-prefix-sort-key
  [query-tokens row]
  [(- (source-graph-row-query-token-count query-tokens row))
   (- (candidate-input-score row))
   (or (:repo-id row) (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- query-source-prefix-candidate-inputs
  [query-tokens source-candidates]
  (loop [remaining (seq (sort-by #(query-source-prefix-sort-key
                                   query-tokens
                                   %)
                                 source-candidates))
         seen #{}
         selected []]
    (if (or (nil? remaining)
            (<= candidate-input-query-source-prefix-limit
                (count selected)))
      selected
      (let [row (first remaining)
            k (candidate-input-path-key row)]
        (if (and (source-graph-declaration-row? row)
                 (pos? (source-graph-row-query-token-count query-tokens row))
                 (not (contains? seen k)))
          (recur (next remaining) (conj seen k) (conj selected row))
          (recur (next remaining) seen selected))))))

(defn- supported-source-graph-prefix-row?
  [query-tokens row]
  (and (seq (:supportLabels row))
       (not= :file (:target-kind row))
       (not= :file (:result-kind row))
       (not (contains? supported-source-graph-prefix-excluded-kinds
                       (:kind row)))
       (not (str/blank? (str (:path row))))
       (<= candidate-input-supported-source-prefix-token-min
           (source-graph-row-query-token-count query-tokens row))))

(defn- supported-source-graph-prefix-sort-key
  [query-tokens row]
  [(- (source-graph-row-query-token-count query-tokens row))
   (candidate-input-path-depth row)
   (- (candidate-input-score row))
   (or (:repo-id row) (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- supported-source-graph-prefix-candidate-inputs
  [query-tokens source-candidates initial-seen]
  (loop [remaining (seq (sort-by #(supported-source-graph-prefix-sort-key
                                   query-tokens
                                   %)
                                 source-candidates))
         seen (set initial-seen)
         selected []]
    (if (or (nil? remaining)
            (<= candidate-input-supported-source-prefix-limit
                (count selected)))
      selected
      (let [row (first remaining)
            k (candidate-input-path-key row)]
        (if (and (supported-source-graph-prefix-row? query-tokens row)
                 (not (contains? seen k)))
          (recur (next remaining) (conj seen k) (conj selected row))
          (recur (next remaining) seen selected))))))

(defn- retrieval-prefix-candidate-inputs
  [results]
  (loop [remaining (seq results)
         seen #{}
         selected []]
    (if (or (nil? remaining)
            (<= candidate-input-retrieval-prefix-limit (count selected)))
      selected
      (let [row (first remaining)
            path (not-empty (str (:path row)))
            k (candidate-input-path-key row)]
        (if (and path (not (contains? seen k)))
          (recur (next remaining) (conj seen k) (conj selected row))
          (recur (next remaining) seen selected))))))

(defn- preserve-candidate-input-root-diversity
  ([rows]
   (preserve-candidate-input-root-diversity rows #{}))
  ([rows initial-seen]
   (let [selected (->> rows
                       (reduce (fn [{:keys [seen selected] :as state} row]
                                 (let [root (candidate-input-root row)]
                                   (if (or (contains? seen root)
                                           (<= candidate-input-root-diversity-limit
                                               (count selected)))
                                     state
                                     {:seen (conj seen root)
                                      :selected (conj selected row)})))
                               {:seen (set initial-seen)
                                :selected []})
                       :selected)
         selected-indexes (set (map ::candidate-input-index selected))]
     (concat selected
             (remove #(contains? selected-indexes
                                 (::candidate-input-index %))
                     rows)))))

(defn- ranked-candidate-inputs
  ([results source-candidates]
   (ranked-candidate-inputs [] results source-candidates))
  ([query-tokens results source-candidates]
   (if-not (seq source-candidates)
     results
     (let [indexed-results (map-indexed (fn [idx row]
                                          (assoc row ::candidate-input-index idx))
                                        results)
           indexed-source-candidates (map-indexed
                                      (fn [idx row]
                                        (assoc row
                                               ::candidate-input-index
                                               (+ (count results) idx)))
                                      source-candidates)
           prefix (retrieval-prefix-candidate-inputs indexed-results)
           source-prefix (query-source-prefix-candidate-inputs
                          query-tokens
                          indexed-source-candidates)
           supported-source-prefix
           (supported-source-graph-prefix-candidate-inputs
            query-tokens
            indexed-source-candidates
            (set (map candidate-input-path-key source-prefix)))
           prefix-indexes (set (map ::candidate-input-index
                                    (concat prefix
                                            source-prefix
                                            supported-source-prefix)))
           query-token-set (set (distinct query-tokens))]
       (->> (concat indexed-results indexed-source-candidates)
            (remove #(contains? prefix-indexes (::candidate-input-index %)))
            (sort-by (juxt (comp - candidate-input-score)
                           #(or (:repo-id %) (:repo %) "")
                           #(or (:path %) "")
                           #(or (:label %) "")
                           ::candidate-input-index))
            (#(preserve-candidate-input-root-diversity
               %
               (set (map candidate-input-root prefix))))
            (reserve-query-matched-path-self-identity query-token-set)
            (concat source-prefix supported-source-prefix prefix)
            (map #(dissoc % ::candidate-input-index)))))))

(defn- source-graph-declaration-row
  [idx row]
  (cond-> {:rank (inc idx)
           :sourceRank (:rank row)
           :path (:path row)
           :label (:label row)
           :kind (some-> (:kind row) display-name)
           :targetKind (some-> (:target-kind row) name)
           :resultKind (some-> (:result-kind row) name)
           :score (:score row)
           :sourceLine (:source-line row)}
    (:repo-id row) (assoc :repo (:repo-id row)
                          :repoId (:repo-id row))
    (:repo row) (assoc :repo (:repo row))
    (:end-line row) (assoc :endLine (:end-line row))
    (:supportLabels row) (assoc :supportLabels (:supportLabels row))
    (:score-components row) (assoc :scoreComponents (:score-components row))))

(defn- source-graph-path-declaration-row
  [query-tokens path-ranks row]
  (when (source-graph-declaration-row? (assoc row
                                              :target-kind :node
                                              :result-kind :node))
    (let [score (source-graph-candidate-score query-tokens row)
          path-rank (get path-ranks (:path row))]
      (cond-> {:path (:path row)
               :rank (long (or path-rank Long/MAX_VALUE))
               :score score
               :target-kind :node
               :target-id (row-id row)
               :label (:label row)
               :kind (:kind row)
               :result-kind :node
               :reason "candidate-file source declaration"
               :source-line (:source-line row)
               :score-components {:sourceGraph score}}
        (:repo-id row) (assoc :repo-id (:repo-id row)
                              :repo (:repo-id row))
        (:end-line row) (assoc :end-line (:end-line row))))))

(defn- source-graph-path-ranks
  [candidate-file-rows]
  (->> candidate-file-rows
       (keep (fn [row]
               (when-let [path (not-empty (str (:path row)))]
                 [path (long (or (:rank row) Long/MAX_VALUE))])))
       (reduce (fn [path-ranks [path rank]]
                 (update path-ranks path #(min (long (or % Long/MAX_VALUE))
                                               rank)))
               {})))

(defn- source-graph-file-declarations
  [xtdb query-tokens candidate-file-rows scope]
  (when (and (map? xtdb)
             (contains? xtdb :node))
    (let [path-ranks (source-graph-path-ranks candidate-file-rows)
          paths (->> candidate-file-rows
                     (keep #(not-empty (str (:path %))))
                     distinct
                     (take source-graph-declaration-path-limit)
                     vec)]
      (when (seq paths)
        (->> (query/nodes-by-paths xtdb paths scope)
             (filter active-row?)
             (keep #(source-graph-path-declaration-row query-tokens path-ranks %)))))))

(defn- source-graph-declaration-key
  [row]
  (or (:target-id row)
      [(:repo-id row) (:repo row) (:path row) (:kind row) (:label row) (:source-line row)]))

(defn- source-graph-declaration-path-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])

(defn- distinct-source-graph-declarations
  [rows]
  (loop [remaining (seq rows)
         seen #{}
         out []]
    (if-let [row (first remaining)]
      (let [row-key (source-graph-declaration-key row)]
        (if (contains? seen row-key)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen row-key) (conj out row))))
      out)))

(defn- source-graph-declaration-sort-key
  [row]
  [(- (double (or (:score row) 0.0)))
   (long (or (:rank row) Long/MAX_VALUE))
   (or (:repo-id row) (:repo row) "")
   (or (:path row) "")
   (long (or (:source-line row) Long/MAX_VALUE))
   (or (:label row) "")])

(defn- source-graph-declaration-path-head-keys
  [rows]
  (loop [remaining (seq rows)
         seen #{}
         path-keys []]
    (if-let [row (first remaining)]
      (let [path-key (source-graph-declaration-path-key row)]
        (if (or (contains? seen path-key)
                (<= source-graph-declaration-path-diversity-limit
                    (count path-keys)))
          (recur (next remaining) seen path-keys)
          (recur (next remaining) (conj seen path-key) (conj path-keys path-key))))
      path-keys)))

(defn- add-source-graph-path-local-row
  [path-key-set rows-by-path row]
  (let [path-key (source-graph-declaration-path-key row)]
    (if (contains? path-key-set path-key)
      (update rows-by-path
              path-key
              (fn [path-rows]
                (let [path-rows (or path-rows [])]
                  (if (< (count path-rows)
                         source-graph-declaration-path-local-limit)
                    (conj path-rows row)
                    path-rows))))
      rows-by-path)))

(defn- source-graph-declaration-path-local-rows
  [path-keys rows]
  (let [path-key-set (set path-keys)
        rows-by-path (reduce (partial add-source-graph-path-local-row
                                      path-key-set)
                             {}
                             rows)]
    (mapcat #(get rows-by-path % []) path-keys)))

(defn- diversify-source-graph-declarations
  [rows]
  (let [path-keys (source-graph-declaration-path-head-keys rows)
        path-local (source-graph-declaration-path-local-rows path-keys rows)
        selected-keys (set (map source-graph-declaration-key path-local))]
    (concat path-local
            (remove #(contains? selected-keys
                                (source-graph-declaration-key %))
                    rows))))

(defn- source-graph-declarations
  [xtdb query-tokens source-candidates candidate-file-rows scope]
  (->> (concat (filter source-graph-declaration-row? source-candidates)
               (source-graph-file-declarations xtdb query-tokens candidate-file-rows scope))
       distinct-source-graph-declarations
       (sort-by source-graph-declaration-sort-key)
       diversify-source-graph-declarations
       (take source-graph-declaration-limit)
       (map-indexed source-graph-declaration-row)
       vec))

(defn- relationship-groups
  [edges]
  (context-architecture/relationship-groups edges))

(defn- selected-accepted-systems
  [overlay entities selected-sources]
  (context-architecture/selected-accepted-systems overlay entities selected-sources))

(defn- blast-radius
  [entities edges]
  (context-architecture/blast-radius entities edges))

(defn- distinct-evidence-rows
  [rows]
  (loop [remaining (seq rows)
         seen #{}
         out []]
    (if-let [row (first remaining)]
      (let [row-key (or (:xt/id row) row)]
        (if (contains? seen row-key)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen row-key) (conj out row))))
      out)))

(defn- selected-system-evidence
  [xtdb selected-system-ids candidate-inputs scope]
  (let [selected-system-ids (->> selected-system-ids
                                 (remove str/blank?)
                                 distinct
                                 vec)
        paths (result-paths candidate-inputs)]
    (if (or (seq selected-system-ids)
            (seq paths))
      (distinct-evidence-rows
       (concat (when (seq selected-system-ids)
                 (query/system-evidence-by-system-ids xtdb
                                                      selected-system-ids
                                                      scope))
               (when (seq paths)
                 (query/system-evidence-by-paths xtdb paths scope))))
      (query/all-system-evidence xtdb scope))))

(defn- select-system-evidence
  [query-tokens selected-system-ids results evidence limit]
  (context-architecture/select-system-evidence query-tokens
                                               selected-system-ids
                                               results
                                               evidence
                                               limit))

(defn- architecture-section
  [opts]
  (context-architecture/architecture-section opts))

(defn- systems-section
  [architecture]
  (context-architecture/systems-section architecture))

(defn- base-packet
  [{:keys [query-text budget graph-data entities edges activity warnings drilldowns evidence
           search-instrumentation freshness source-coverage architecture systems audit-scopes
           relationships blast-radius candidate-files source-declarations plugin-packages memories
           degradation]}]
  (cond-> {:schema schema
           :query query-text
           :graph (graph-summary graph-data evidence search-instrumentation)
           :budget {:requested budget}
           :entities entities
           :edges edges
           :activity activity
           :candidateFiles candidate-files
           :docs []
           :warnings warnings
           :drilldowns drilldowns}
    (seq source-declarations) (assoc :sourceDeclarations source-declarations)
    evidence (assoc :evidence evidence)
    search-instrumentation (assoc :search search-instrumentation)
    freshness (assoc :freshness freshness)
    degradation (assoc :degradation degradation)
    architecture (assoc :architecture architecture)
    systems (assoc :systems systems)
    (seq audit-scopes) (assoc :auditScopes audit-scopes)
    (seq plugin-packages) (assoc :pluginPackages (plugin-package-view/caveats plugin-packages))
    (seq memories) (assoc :memories memories)
    (seq relationships) (assoc :relationships relationships)
    blast-radius (assoc :blastRadius blast-radius)
    source-coverage (assoc :sourceCoverage source-coverage)))

(defn- fit-budget
  [packet docs budget]
  (context-budget/fit-budget packet docs budget))

(defn- non-empty-value?
  [value]
  (cond
    (nil? value) false
    (and (coll? value) (empty? value)) false
    :else true))

(defn- compact-map
  [m]
  (->> m
       (filter (comp non-empty-value? val))
       (into {})))

(defn- compact-query-input
  [query-input]
  (let [query-input (or query-input {})]
    (compact-map
     (cond-> query-input
       (= :auto (:task query-input)) (dissoc :task)
       (false? (:changed-only? query-input)) (dissoc :changed-only?)))))

(declare distinct-by)

(defn- score-lanes
  [score-components]
  (->> [[:grep :grep]
        [:semantic :semantic]
        [:lexical :lexical]
        [:fts :fts]
        [:graph :graph]
        [:sourceGraph :source-graph]
        [:queryLabel :query-label]
        [:exact :exact]]
       (keep (fn [[field lane]]
               (when (pos? (double (or (get score-components field) 0.0)))
                 lane)))
       vec))

(defn- candidate-path-rows
  [packet]
  (->> (:candidateFiles packet)
       (keep (fn [{:keys [repo path]}]
               (when-not (str/blank? (str path))
                 {:repo repo
                  :path path})))
       (distinct-by (juxt :repo :path))
       vec))

(defn- path-index
  [packet]
  (let [rows (candidate-path-rows packet)
        id-rows (map-indexed (fn [idx row]
                               [(str "p" (inc idx)) row])
                             rows)]
    {:paths (into {}
                  (map (fn [[id {:keys [path]}]]
                         [id path]))
                  id-rows)
     :path-ids (into {}
                     (map (fn [[id row]]
                            [[(:repo row) (:path row)] id]))
                     id-rows)}))

(defn- compact-result-row
  [path-ids row]
  (let [path-id (get path-ids [(:repo row) (:path row)])
        lanes (score-lanes (:scoreComponents row))]
    (compact-map
     {:path path-id
      :resolvedPath (:path row)
      :repo (:repo row)
      :rank (:rank row)
      :sourceRank (:sourceRank row)
      :line (:sourceLine row)
      :endLine (:endLine row)
      :score (:score row)
      :kind (or (:resultKind row) (:targetKind row) (:kind row))
      :label (:label row)
      :why lanes
      :reason (:reason row)})))

(defn- compact-evidence-row
  [path-ids row]
  (compact-map
   {:kind :candidate
    :path (get path-ids [(:repo row) (:path row)])
    :resolvedPath (:path row)
    :repo (:repo row)
    :line (:sourceLine row)
    :endLine (:endLine row)
    :label (:label row)
    :why (score-lanes (:scoreComponents row))}))

(defn- compact-result-sort-key
  [row]
  [(- (double (or (:score row) 0.0)))
   (long (or (:rank row) Long/MAX_VALUE))
   (or (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- compact-grep-result-sort-key
  [row]
  [(- (double (get-in row [:scoreComponents :grep] 0.0)))
   (- (double (or (:score row) 0.0)))
   (long (or (:rank row) Long/MAX_VALUE))
   (or (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- matching-query-token-count
  [query-token-set text]
  (if (empty? query-token-set)
    0
    (count (set/intersection query-token-set
                             (set (text/tokenize text))))))

(defn- query-shaped-tokens
  [query-token-set]
  (->> query-token-set
       (filter #(re-find #"[._/-]" (str %)))
       set))

(defn- earliest-query-token-index
  [query-tokens text]
  (let [tokens (set (text/tokenize text))]
    (or (first (keep-indexed (fn [idx token]
                               (when (contains? tokens token)
                                 idx))
                             query-tokens))
        Long/MAX_VALUE)))

(defn- path-file-name
  [path]
  (last (str/split (str path) #"/")))

(defn- matching-path-basename-token-count
  [query-token-set row]
  (matching-query-token-count query-token-set
                              (path-file-name (:path row))))

(defn- matching-label-shaped-token-count
  [query-token-set row]
  (let [label (str/lower-case
               (str/join "\n"
                         (remove str/blank?
                                 (cons (:label row)
                                       (:supportLabels row)))))]
    (count (filter #(str/includes? label (str/lower-case (str %)))
                   (query-shaped-tokens query-token-set)))))

(defn- exact-label-query-token-index
  [query-tokens row]
  (let [label (str/lower-case (str (:label row)))]
    (or (first (keep-indexed (fn [idx token]
                               (when (= label (str/lower-case (str token)))
                                 idx))
                             query-tokens))
        Long/MAX_VALUE)))

(defn- compact-path-token-result-sort-key
  [query-tokens query-token-set row]
  [(- (double (matching-path-basename-token-count query-token-set row)))
   (earliest-query-token-index query-tokens (path-file-name (:path row)))
   (count (str (:path row)))
   (- (double (or (:score row) 0.0)))
   (long (or (:rank row) Long/MAX_VALUE))
   (or (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- query-matched-path-token-row?
  [query-token-set row]
  (pos? (matching-path-basename-token-count query-token-set row)))

(defn- compact-label-token-result-sort-key
  [query-tokens query-token-set row]
  [(- (double (matching-label-shaped-token-count query-token-set row)))
   (earliest-query-token-index query-tokens
                               (str/join "\n"
                                         (remove str/blank?
                                                 (cons (:label row)
                                                       (:supportLabels row)))))
   (count (str (:label row)))
   (count (str (:path row)))
   (- (double (or (:score row) 0.0)))
   (long (or (:rank row) Long/MAX_VALUE))
   (or (:repo row) "")
   (or (:path row) "")])

(defn- query-matched-label-token-row?
  [query-token-set row]
  (pos? (matching-label-shaped-token-count query-token-set row)))

(defn- compact-exact-label-result-sort-key
  [query-tokens row]
  [(long (or (:rank row) Long/MAX_VALUE))
   (exact-label-query-token-index query-tokens row)
   (- (double (or (:score row) 0.0)))
   (or (:repo row) "")
   (or (:path row) "")
   (or (:label row) "")])

(defn- query-matched-exact-label-row?
  [query-tokens row]
  (not= Long/MAX_VALUE (exact-label-query-token-index query-tokens row)))

(defn- row-target-kind
  [row]
  (some-> (or (:targetKind row)
              (:target-kind row)
              (:resultKind row)
              (:result-kind row))
          str/lower-case))

(defn- structured-node-row?
  [row]
  (= "node" (row-target-kind row)))

(defn- primary-label-differs-from-path?
  [row]
  (let [label (str/lower-case (str (:label row)))
        path (str/lower-case (str (:path row)))]
    (and (not (str/blank? label))
         (not (str/blank? path))
         (not= label path))))

(defn- compact-query-label-score-boost
  [query-tokens query-token-set row]
  (if-not (and (structured-node-row? row)
               (primary-label-differs-from-path? row))
    0.0
    (let [exact-label? (query-matched-exact-label-row? query-tokens row)
          shaped-label? (query-matched-label-token-row? query-token-set row)]
      (cond
        exact-label? compact-exact-label-score-boost
        shaped-label? compact-shaped-label-score-boost
        :else 0.0))))

(defn- add-compact-query-label-score
  [query-tokens query-token-set row]
  (let [boost (compact-query-label-score-boost query-tokens query-token-set row)]
    (if (pos? boost)
      (-> row
          (update :score #(+ (double (or % 0.0)) boost))
          (assoc-in [:scoreComponents :queryLabel] boost))
      row)))

(defn- compact-result-rows
  [packet]
  (let [query-tokens (text/tokenize (:query packet))
        query-token-set (set query-tokens)
        rows (mapv #(add-compact-query-label-score query-tokens
                                                   query-token-set
                                                   %)
                   (:candidateFiles packet))
        sorted (sort-by compact-result-sort-key rows)
        grep-reserve-limit (min compact-grep-result-reserve-limit
                                compact-result-limit)
        path-reserve-limit (min compact-path-token-result-reserve-limit
                                (max 0 (- compact-result-limit
                                          grep-reserve-limit)))
        label-reserve-limit (min compact-label-token-result-reserve-limit
                                 (max 0 (- compact-result-limit
                                           grep-reserve-limit
                                           path-reserve-limit)))
        exact-label-reserve-limit (min compact-exact-label-result-reserve-limit
                                       (max 0 (- compact-result-limit
                                                 grep-reserve-limit
                                                 path-reserve-limit
                                                 label-reserve-limit)))
        primary-limit (max 0 (- compact-result-limit
                                grep-reserve-limit
                                path-reserve-limit
                                label-reserve-limit
                                exact-label-reserve-limit))
        primary (vec (take primary-limit sorted))
        primary-keys (set (map (juxt :repo :path) primary))
        grep-reserve (->> sorted
                          (remove #(contains? primary-keys [(:repo %) (:path %)]))
                          (filter #(pos? (double (get-in % [:scoreComponents :grep] 0.0))))
                          (sort-by compact-grep-result-sort-key)
                          (take grep-reserve-limit)
                          vec)
        grep-reserve-keys (set (map (juxt :repo :path) (concat primary grep-reserve)))
        path-reserve (->> sorted
                          (remove #(contains? grep-reserve-keys [(:repo %) (:path %)]))
                          (filter #(query-matched-path-token-row? query-token-set %))
                          (sort-by (partial compact-path-token-result-sort-key
                                            query-tokens
                                            query-token-set))
                          (take path-reserve-limit)
                          vec)
        path-reserve-keys (set (map (juxt :repo :path)
                                    (concat primary grep-reserve path-reserve)))
        label-reserve (->> sorted
                           (remove #(contains? path-reserve-keys [(:repo %) (:path %)]))
                           (filter #(query-matched-label-token-row? query-token-set %))
                           (sort-by (partial compact-label-token-result-sort-key
                                             query-tokens
                                             query-token-set))
                           (take label-reserve-limit)
                           vec)
        label-reserve-keys (set (map (juxt :repo :path)
                                     (concat primary
                                             grep-reserve
                                             path-reserve
                                             label-reserve)))
        exact-label-reserve (->> sorted
                                 (remove #(contains? label-reserve-keys [(:repo %) (:path %)]))
                                 (filter #(query-matched-exact-label-row? query-tokens %))
                                 (sort-by (partial compact-exact-label-result-sort-key
                                                   query-tokens))
                                 (take exact-label-reserve-limit)
                                 vec)
        selected-keys (set (map (juxt :repo :path)
                                (concat primary
                                        grep-reserve
                                        path-reserve
                                        label-reserve
                                        exact-label-reserve)))
        fill (->> sorted
                  (remove #(contains? selected-keys [(:repo %) (:path %)]))
                  (take (max 0 (- compact-result-limit
                                  (count primary)
                                  (count grep-reserve)
                                  (count path-reserve)
                                  (count label-reserve)
                                  (count exact-label-reserve)))))
        selected (->> (concat primary
                              grep-reserve
                              path-reserve
                              label-reserve
                              exact-label-reserve
                              fill)
                      (sort-by compact-result-sort-key))]
    (->> selected
         (map-indexed (fn [idx row]
                        (assoc row
                               :rank (inc idx)
                               :sourceRank (:rank row))))
         vec)))

(defn- compact-lanes
  [packet results]
  (let [search (:search packet)
        used (->> results (mapcat :why) distinct vec)]
    (compact-map
     {:requested (:retriever-requested search)
      :mode (:retriever-effective search)
      :used used})))

(defn- compact-search
  [packet]
  (let [instrumentation (get-in packet [:search :instrumentation])]
    (compact-map
     {:queryRunId (get-in packet [:search :query-run-id])
      :instrumentation (select-keys instrumentation
                                    [:search-docs
                                     :candidate-count
                                     :ranked-count
                                     :returned-count
                                     :grep-status
                                     :grep-search-ms
                                     :grep-raw-matches
                                     :grep-indexed-paths
                                     :grep-candidates
                                     :grep-diagnostic-kinds
                                     :auto-lexical-short-circuit?
                                     :auto-lexical-short-circuit-reason
                                     :semantic-positive
                                     :vector-store
                                     :vector-store-fallback-reason
                                     :vector-candidates
                                     :vector-requested-candidate-limit
                                     :vector-overfetch-limit
                                     :vector-raw-candidates
                                     :vector-stale-candidates
                                     :vector-filtered-candidates
                                     :vector-post-filter-candidates
                                     :load-embeddings-ms
                                     :query-embedding-ms
                                     :semantic-score-ms
                                     :vector-search-ms
                                     :graph-adjacency-query-count
                                     :context-chunks])
      :contextTimingsMs (:context-timings-ms instrumentation)})))

(def ^:private context-progress-slowest-limit
  8)

(defn- context-timing-row
  [[step elapsed-ms]]
  {:step (name step)
   :elapsedMs elapsed-ms})

(defn context-progress-summary
  "Return the bounded benchmark progress summary for a context packet."
  [packet]
  (let [timings (get-in packet [:search :instrumentation :context-timings-ms])
        slowest-steps (->> timings
                           (remove (fn [[step _]]
                                     (= :total step)))
                           (sort-by (comp - long val))
                           (take context-progress-slowest-limit)
                           (mapv context-timing-row))]
    (cond-> {:docs (count (:docs packet))
             :entities (count (:entities packet))
             :edges (count (:edges packet))
             :warnings (count (:warnings packet))}
      (seq timings)
      (assoc :contextTimingsMs timings)
      (seq slowest-steps)
      (assoc :slowestContextSteps slowest-steps))))

(defn- compact-basis
  [packet]
  (compact-map
   {:status (get-in packet [:evidence :status])
    :freshness (get-in packet [:freshness :status])
    :degraded (boolean (:degradation packet))
    :graph (get-in packet [:graph :basis])}))

(defn- proof-patterns
  [query-text results]
  (->> (concat (map :label results)
               (text/tokenize query-text))
       (remove str/blank?)
       distinct
       (take proof-command-pattern-limit)
       vec))

(defn- proof-grep-action
  [query-text packet]
  (let [paths (->> (:candidateFiles packet)
                   (map :path)
                   (remove str/blank?)
                   distinct
                   (take proof-command-path-limit)
                   vec)
        patterns (proof-patterns query-text (:candidateFiles packet))]
    (when (and (seq paths) (seq patterns))
      {:kind :grep
       :cmd (apply command/command
                   (concat ["rg" "-n" "--fixed-strings"]
                           (mapcat (fn [pattern] ["-e" pattern]) patterns)
                           ["--"]
                           paths))})))

(defn- compact-packet
  [packet query-text proof-commands?]
  (let [{:keys [paths path-ids]} (path-index packet)
        result-rows (compact-result-rows packet)
        results (mapv #(compact-result-row path-ids %) result-rows)
        evidence (->> result-rows
                      (take compact-evidence-limit)
                      (mapv #(compact-evidence-row path-ids %)))
        action (when proof-commands? (proof-grep-action query-text packet))]
    (compact-map
     {:schema compact-schema
      :query (:query packet)
      :input (compact-query-input (:input packet))
      :basis (compact-basis packet)
      :paths paths
      :lanes (compact-lanes packet results)
      :retrieval (get-in packet [:evidence :retrieval])
      :results results
      :evidence evidence
      :memories (:memories packet)
      :warnings (:warnings packet)
      :degradation (:degradation packet)
      :search (compact-search packet)
      :actions (when action [action])})))

(defn- packet-output
  [packet query-text output proof-commands?]
  (case (keyword (or output default-output))
    :compact (compact-packet packet query-text proof-commands?)
    :snippets (assoc (compact-packet packet query-text proof-commands?)
                     :docs (:docs packet))
    :evidence (assoc (compact-packet packet query-text proof-commands?)
                     :evidenceDetails (:evidence packet))
    :full packet
    (throw (ex-info "Unsupported query output mode."
                    {:output output
                     :supported [:compact :snippets :evidence :full]}))))

(defn- resolve-correction-overlay
  [xtdb _path overlay project-id]
  (cond
    overlay overlay
    (and (store/xtdb-handle? xtdb)
         (not (str/blank? (str project-id)))) (corrections/overlay xtdb project-id)
    :else (correction-overlay/empty-overlay project-id)))

(defn- count-scope-constraints
  [{:keys [project-id repo-id]}]
  (cond-> {}
    (not (str/blank? (str project-id))) (assoc :project-id project-id)
    (not (str/blank? (str repo-id))) (assoc :repo-id repo-id)))

(defn- scoped-count
  ([xtdb table opts] (scoped-count xtdb table opts {}))
  ([xtdb table {:keys [read-context] :as opts} constraints]
   (store/count-rows xtdb
                     table
                     (merge (count-scope-constraints opts)
                            constraints)
                     (store/read-context read-context))))

(defn- scoped-active-count
  ([xtdb table opts] (scoped-active-count xtdb table opts {}))
  ([xtdb table {:keys [read-context] :as opts} constraints]
   (store/active-row-count xtdb
                           table
                           (merge (count-scope-constraints opts)
                                  constraints)
                           (store/read-context read-context))))

(defn- overlay-counts
  [overlay]
  {:correction-systems (count (:systems overlay))
   :correction-docs (count (:docs overlay))
   :correction-edges (count (:edges overlay))
   :correction-rejects (count (:reject overlay))})

(defn- precomputed-count
  [value fallback-fn]
  (if (some? value)
    (long value)
    (long (or (fallback-fn) 0))))

(defn- dependency-counts
  [xtdb overlay {:keys [project-id repo-id dependency-counts]}]
  (or dependency-counts
      (:counts (dependency/package-report xtdb
                                          {:project-id project-id
                                           :repo-id repo-id}
                                          {:limit 0
                                           :correction-overlay overlay}))))

(def empty-memory-counts
  {:memories 0
   :memory-statuses {}
   :suggested-memories 0
   :observed-memories 0
   :reviewed-memories 0})

(defn- memory-counts
  [xtdb scope {:keys [memory-counts]}]
  (if (some? memory-counts)
    memory-counts
    (memory/counts xtdb scope)))

(defn- fallback-capability-counts
  [xtdb overlay {:keys [project-id repo-id read-context
                        system-evidence-count search-doc-count]
                 :as opts}]
  (let [scope {:project-id project-id
               :repo-id repo-id
               :read-context read-context}
        project-scope {:project-id project-id
                       :read-context read-context}
        package-counts (dependency-counts xtdb overlay opts)
        nodes (filter active-row? (query/all-nodes xtdb scope))
        edges (filter active-row? (query/all-edges xtdb scope))
        activity-items (activity/all-items xtdb project-scope)
        activity-events (activity/all-events xtdb project-scope)]
    (merge
     {:files (scoped-active-count xtdb (:files store/tables) scope)
      :skipped-files 0
      :nodes (count nodes)
      :edges (count edges)
      :external-packages (count (filter #(= :external-package (:kind %)) nodes))
      :package-import-edges (count (filter #(= :imports-package (:relation %)) edges))
      :declared-packages (get package-counts :packages 0)
      :source-import-candidates (get package-counts :source-import-candidates 0)
      :unresolved-imports (get package-counts :unresolved-imports 0)
      :package-evidence-gaps (get package-counts :declared-without-import-evidence 0)
      :package-conflicts (get package-counts :version-conflicts 0)
      :system-evidence (precomputed-count
                        system-evidence-count
                        #(count (query/all-system-evidence xtdb scope)))
      :chunks (count (filter active-row? (query/all-chunks xtdb scope)))
      :search-docs (precomputed-count
                    search-doc-count
                    #(count (query/all-search-docs xtdb scope)))
      :embeddings (count (query/all-embeddings xtdb scope))
      :system-nodes (count (query/all-system-nodes xtdb project-scope))
      :system-edges (count (query/all-system-edges xtdb project-scope))
      :activity-items (count activity-items)
      :activity-events (count activity-events)
      :validation-events (count (filter #(= :validation (:event-kind %))
                                        activity-events))
      :result-schema-mismatch-events
      (count (filter #(= :result-schema-mismatch (:event-kind %))
                     activity-events))
      :diagnostics (scoped-active-count xtdb (:diagnostics store/tables) scope)}
     (activity/result-schema-counts activity-items)
     (memory-counts xtdb scope opts)
     (overlay-counts overlay))))

(defn- xtdb-capability-counts
  [xtdb overlay {:keys [project-id repo-id read-context
                        system-evidence-count search-doc-count]
                 :as opts}]
  (let [scope {:project-id project-id
               :repo-id repo-id
               :read-context read-context}
        project-scope {:project-id project-id
                       :read-context read-context}
        package-counts (dependency-counts xtdb overlay opts)
        activity-item-count (scoped-count xtdb
                                          (:activity-items store/tables)
                                          project-scope
                                          {:active? true})
        activity-schema-counts (activity/result-schema-counts-for-scope xtdb
                                                                        project-scope)
        activity-event-count (scoped-count xtdb
                                           (:activity-events store/tables)
                                           project-scope
                                           {:active? true})
        validation-event-count (scoped-count xtdb
                                             (:activity-events store/tables)
                                             project-scope
                                             {:active? true
                                              :event-kind :validation})
        result-schema-mismatch-event-count
        (scoped-count xtdb
                      (:activity-events store/tables)
                      project-scope
                      {:active? true
                       :event-kind :result-schema-mismatch})]
    (merge
     {:files (scoped-active-count xtdb (:files store/tables) scope)
      :skipped-files 0
      :nodes (scoped-active-count xtdb (:nodes store/tables) scope)
      :edges (scoped-active-count xtdb (:edges store/tables) scope)
      :external-packages (scoped-active-count xtdb
                                              (:nodes store/tables)
                                              scope
                                              {:kind :external-package})
      :package-import-edges (scoped-active-count xtdb
                                                 (:edges store/tables)
                                                 scope
                                                 {:relation :imports-package})
      :declared-packages (get package-counts :packages 0)
      :source-import-candidates (get package-counts :source-import-candidates 0)
      :unresolved-imports (get package-counts :unresolved-imports 0)
      :package-evidence-gaps (get package-counts :declared-without-import-evidence 0)
      :package-conflicts (get package-counts :version-conflicts 0)
      :system-evidence (precomputed-count
                        system-evidence-count
                        #(scoped-count xtdb
                                       (:system-evidence store/tables)
                                       scope
                                       {:active? true}))
      :chunks (scoped-active-count xtdb (:chunks store/tables) scope)
      :search-docs (precomputed-count
                    search-doc-count
                    #(scoped-count xtdb
                                   (:search-docs store/tables)
                                   scope
                                   {:active? true}))
      :embeddings (scoped-count xtdb
                                (:embeddings store/tables)
                                scope
                                {:active? true})
      :system-nodes (scoped-count xtdb
                                  (:system-nodes store/tables)
                                  project-scope
                                  {:active? true})
      :system-edges (scoped-count xtdb
                                  (:system-edges store/tables)
                                  project-scope
                                  {:active? true})
      :activity-items activity-item-count
      :activity-events activity-event-count
      :validation-events validation-event-count
      :result-schema-mismatch-events result-schema-mismatch-event-count
      :diagnostics (scoped-active-count xtdb (:diagnostics store/tables) scope)}
     activity-schema-counts
     (memory-counts xtdb scope opts)
     (overlay-counts overlay))))

(def ^:private max-capability-count-cache-entries
  32)

(def ^:private capability-temporal-keys
  [:valid-at :known-at :snapshot-token :current-time])

(defn- capability-count-cache-key
  [overlay {:keys [project-id repo-id read-context]}]
  (when-not (some #(some? (get read-context %)) capability-temporal-keys)
    [:capability-counts
     project-id
     repo-id
     (hash (dissoc overlay :updated-at-ms))]))

(defn- evict-capability-count-cache-entry
  [entries]
  (if (<= (count entries) max-capability-count-cache-entries)
    entries
    (dissoc entries
            (key (apply min-key
                        (fn [[_ {:keys [last-used]}]] last-used)
                        entries)))))

(defn- uncached-capability-counts
  [xtdb overlay opts]
  (if (store/xtdb-handle? xtdb)
    (xtdb-capability-counts xtdb overlay opts)
    (fallback-capability-counts xtdb overlay opts)))

(defn- capability-count-data
  [xtdb overlay opts]
  (let [cache (:read-model-cache xtdb)
        cache-key (capability-count-cache-key overlay opts)]
    (if (and cache cache-key)
      (locking cache
        (let [{:keys [generation clock entries]} @cache
              next-clock (inc (long clock))]
          (if-let [entry (get entries cache-key)]
            (do
              (swap! cache assoc
                     :clock next-clock
                     :entries (assoc entries cache-key (assoc entry :last-used next-clock)))
              {:counts (:counts entry)
               :cache-status :hit
               :cache-generation generation})
            (let [counts (uncached-capability-counts xtdb overlay opts)
                  loaded-generation (:generation @cache)]
              (when (= generation loaded-generation)
                (swap! cache
                       (fn [state]
                         (if (= generation (:generation state))
                           (assoc state
                                  :clock next-clock
                                  :entries (evict-capability-count-cache-entry
                                            (assoc (:entries state)
                                                   cache-key
                                                   {:counts counts
                                                    :last-used next-clock})))
                           state))))
              {:counts counts
               :cache-status :miss
               :cache-generation loaded-generation}))))
      {:counts (uncached-capability-counts xtdb overlay opts)
       :cache-status :bypass
       :cache-generation nil})))

(defn- validation-history-count
  [counts]
  (+ (:validation-events counts 0)
     (:result-schema-status-items counts 0)
     (:result-schema-mismatch-events counts 0)))

(defn- retrieval-summary
  [{:keys [retriever retriever-effective embedding-client semantic-status
           auto-lexical-short-circuit? auto-lexical-short-circuit-reason]}]
  (let [requested (keyword (or (:requested semantic-status) retriever :auto))
        semantic-available? (if (and semantic-status
                                     (contains? semantic-status :semanticAvailable))
                              (boolean (:semanticAvailable semantic-status))
                              (boolean embedding-client))
        predicted-effective (case requested
                              :auto (if semantic-available? :hybrid :lexical)
                              requested)
        configured-effective (keyword (or (:effective semantic-status)
                                          predicted-effective))
        effective (keyword (or retriever-effective configured-effective))
        auto-lexical? (and (= :auto requested)
                           (= :lexical effective)
                           (true? auto-lexical-short-circuit?))
        fallback? (and (= :auto requested)
                       (= :lexical effective)
                       (not auto-lexical?))
        fallback-reason (cond
                          (and fallback? (:message semantic-status))
                          (:message semantic-status)

                          (and fallback? (nil? embedding-client))
                          "No embedding client was available."

                          fallback?
                          "No current embeddings were available for this query scope.")]
    (cond-> {:requested requested
             :effective effective
             :fallback? fallback?}
      semantic-status (assoc :semanticAvailable semantic-available?
                             :semanticStatus (:status semantic-status))
      (:provider semantic-status) (assoc :provider (:provider semantic-status))
      (:model semantic-status) (assoc :model (:model semantic-status))
      (:reason semantic-status) (assoc :reasonCode (:reason semantic-status))
      (and fallback? (:message semantic-status))
      (assoc :message (:message semantic-status))
      fallback-reason (assoc :reason fallback-reason)
      auto-lexical? (assoc :autoLexicalShortCircuit true
                           :autoLexicalShortCircuitReason
                           auto-lexical-short-circuit-reason))))

(defn- available-planes
  [counts]
  (let [validation-history-events (validation-history-count counts)]
    (cond-> []
      (pos? (+ (:nodes counts) (:edges counts))) (conj :source-graph)
      (pos? (+ (:external-packages counts 0)
               (:package-import-edges counts 0)
               (:unresolved-imports counts 0))) (conj :dependencies)
      (pos? (:system-evidence counts 0)) (conj :system-evidence)
      (pos? (+ (:chunks counts) (:search-docs counts))) (conj :docs)
      (pos? (:embeddings counts)) (conj :embeddings)
      (pos? (+ (:system-nodes counts) (:system-edges counts))) (conj :system-graph)
      (pos? (:memories counts 0)) (conj :memory)
      (pos? (+ (:activity-items counts) (:activity-events counts))) (conj :activity)
      (pos? validation-history-events) (conj :validation-history)
      (pos? (+ (:correction-systems counts)
               (:correction-docs counts)
               (:correction-edges counts)
               (:correction-rejects counts))) (conj :correction-overlay))))

(defn- missing-planes
  [counts]
  (let [validation-history-events (validation-history-count counts)]
    (cond-> []
      (zero? (:files counts)) (conj :source-files)
      (zero? (+ (:nodes counts) (:edges counts))) (conj :source-graph)
      (zero? (+ (:external-packages counts 0)
                (:package-import-edges counts 0)
                (:unresolved-imports counts 0))) (conj :dependencies)
      (zero? (:system-evidence counts 0)) (conj :system-evidence)
      (zero? (+ (:chunks counts) (:search-docs counts))) (conj :docs)
      (zero? (:embeddings counts)) (conj :embeddings)
      (zero? (+ (:system-nodes counts) (:system-edges counts))) (conj :system-graph)
      (zero? (+ (:activity-items counts) (:activity-events counts))) (conj :activity)
      (zero? validation-history-events) (conj :validation-history))))

(defn- weak-planes
  [counts {:keys [entity-count doc-count activity-count validation-count runtime-count
                  memory-count]}]
  (let [validation-history-events (validation-history-count counts)]
    (cond-> []
      (or (pos? (:skipped-files counts 0))
          (pos? (:diagnostics counts 0)))
      (conj :source-files)

      (or (pos? (:unresolved-imports counts 0))
          (and (pos? (:source-import-candidates counts 0))
               (zero? (:declared-packages counts 0)))
          (and (pos? (:declared-packages counts 0))
               (zero? (:package-import-edges counts 0))
               (zero? (:source-import-candidates counts 0))))
      (conj :dependencies)

      (and (pos? (+ (:system-nodes counts) (:system-edges counts)))
           (zero? entity-count))
      (conj :system-graph)

      (and (pos? (:memories counts 0))
           (zero? memory-count))
      (conj :memory)

      (and (pos? (:system-evidence counts 0))
           (zero? runtime-count))
      (conj :system-evidence)

      (and (pos? (:search-docs counts))
           (zero? doc-count))
      (conj :docs)

      (and (pos? (+ (:activity-items counts) (:activity-events counts)))
           (zero? activity-count))
      (conj :activity)

      (and (pos? validation-history-events)
           (zero? validation-count))
      (conj :validation-history))))

(defn- plane-status
  [{:keys [available missing weak unsupported counts]} plane]
  (cond
    (some #{plane} unsupported) :unsupported
    (some #{plane} weak) :weak
    (some #{plane} missing) :missing
    (some #{plane} available) :available
    (and (= :source-files plane)
         (pos? (:files counts 0))) :available
    :else :missing))

(defn- plane-counts
  [counts plane]
  (when-let [ks (seq (get plane-count-keys plane))]
    (into {}
          (map (fn [k] [k (long (or (get counts k) 0))]))
          ks)))

(defn- evidence-planes
  "Return compact mechanical evidence-plane statuses for agent packets."
  [{:keys [available missing weak unsupported counts] :as status}]
  (let [status (assoc status
                      :available (set available)
                      :missing (set missing)
                      :weak (set weak)
                      :unsupported (set unsupported))]
    (mapv (fn [plane]
            (let [plane-counts (plane-counts counts plane)]
              (cond-> {:plane plane
                       :status (plane-status status plane)}
                (seq plane-counts) (assoc :counts plane-counts))))
          plane-order)))

(defn- freshness-problem?
  [freshness]
  (contains? #{:partial :stale :unknown :unsynced}
             (:status freshness)))

(defn- freshness-warning
  [freshness]
  (when (freshness-problem? freshness)
    (str "Graph basis is " (name (:status freshness))
         "; follow freshness next actions before trusting absence of evidence.")))

(defn- freshness-actions
  [freshness]
  (if (freshness-problem? freshness)
    (vec (:nextActions freshness))
    []))

(defn- active-indexing-operation
  [active-indexing]
  (not-empty (select-keys active-indexing
                          [:schema
                           :op
                           :projectId
                           :lockKey
                           :scheduleId
                           :task
                           :startedAtMs
                           :elapsedMs])))

(def indexing-degradation-message
  "Query results are degraded because indexing is still running; rerun after the active operation finishes for complete evidence.")

(defn- indexing-degradation
  [active-indexing]
  (when-let [operation (active-indexing-operation active-indexing)]
    {:schema "ygg.context.degradation/v1"
     :status :degraded
     :reason :active-indexing
     :message indexing-degradation-message
     :activeOperation operation}))

(defn- evidence-warnings
  ([counts retrieval weak] (evidence-warnings counts retrieval weak nil))
  ([counts retrieval weak freshness] (evidence-warnings counts retrieval weak freshness nil))
  ([counts retrieval weak freshness degradation]
   (cond-> []
     (:message degradation)
     (conj (:message degradation))

     (freshness-warning freshness)
     (conj (freshness-warning freshness))

     (and (= :auto (:requested retrieval)) (:fallback? retrieval))
     (conj (or (:message retrieval)
               (str (:reason retrieval "Auto retrieval used lexical fallback.")
                    " Auto retrieval used lexical fallback.")))

     (zero? (:files counts 0))
     (conj "No source files are indexed for this project.")

     (and (pos? (:files counts 0))
          (zero? (+ (:nodes counts) (:edges counts))))
     (conj "Source files are indexed, but no source graph rows are indexed.")

     (zero? (:search-docs counts))
     (conj "No search docs are indexed; context retrieval is limited.")

     (pos? (:diagnostics counts))
     (conj "Indexer diagnostics are present; inspect source coverage before relying on missing facts.")

     (pos? (:skipped-files counts 0))
     (conj "Some source candidates were skipped; inspect source coverage before treating missing facts as absent.")

     (zero? (+ (:external-packages counts 0)
               (:package-import-edges counts 0)
               (:unresolved-imports counts 0)))
     (conj "No dependency graph rows are indexed; dependency questions are limited.")

     (zero? (:system-evidence counts 0))
     (conj "No runtime/config evidence rows are indexed; runtime/config questions are limited.")

     (some #{:system-evidence} weak)
     (conj "Runtime/config evidence rows are indexed, but no runtime/config evidence matched this query.")

     (pos? (:unresolved-imports counts 0))
     (conj "Dependency graph has unresolved imports; dependency answers may need package review.")

     (pos? (:package-evidence-gaps counts 0))
     (conj "Some declared packages have no source import evidence.")

     (pos? (:package-conflicts counts 0))
     (conj "Package version conflicts are present in dependency facts.")

     (zero? (+ (:system-nodes counts) (:system-edges counts)))
     (conj "No system graph rows are indexed for this project.")

     (some #{:system-graph} weak)
     (conj "System graph rows are indexed, but no graph entities matched this query.")

     (some #{:memory} weak)
     (conj "Memory rows are indexed, but no memory matched this query.")

     (some #{:docs} weak)
     (conj "Search docs are indexed, but no docs matched this query.")

     (zero? (+ (:activity-items counts) (:activity-events counts)))
     (conj "No activity/work rows are indexed; prior work queries are limited.")

     (some #{:activity} weak)
     (conj "Activity/work rows are indexed, but no activity matched this query.")

     (zero? (validation-history-count counts))
     (conj "No validation history rows are indexed; validation-history queries are limited.")

     (some #{:validation-history} weak)
     (conj "Validation history rows are indexed, but no validation events matched this query.")

     (pos? (:result-schema-mismatch-events counts 0))
     (conj "Completed work has result schema mismatches; inspect activity before trusting prior results.")

     (pos? (:result-schema-missing-result-items counts 0))
     (conj "Some work declares expected result schemas but has no result schema yet; inspect activity before reusing prior work.")

     (pos? (:result-schema-unexpected-result-items counts 0))
     (conj "Some completed work returned result schemas without expected schemas; inspect activity before treating schema validation as complete.")

     (zero? (:embeddings counts))
     (conj "No embeddings are indexed for this project.")

     true
     (conj "Remote work items and session history are not modeled in the current graph."))))

(defn- distinct-by
  [f coll]
  (loop [remaining (seq coll)
         seen #{}
         out []]
    (if-let [item (first remaining)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out item))))
      out)))

(defn- package-command
  [project-id & args]
  (str "ygg packages --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- project-command
  [command-name project-id & args]
  (str "ygg " command-name " --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- query-command
  [query-text project-id]
  (str "ygg query "
       (command/shell-token query-text)
       " --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- docs-audit-command
  [project-id]
  (str "ygg sync docs audit --project "
       (command/shell-token (or project-id "<project-id>"))))

(defn- inspect-command
  []
  (command/command "ygg" "sync" "inspect" "<project.edn>" "--json"))

(defn- drilldown
  [kind label command mcp-tool mcp-args]
  (cond-> {:kind kind
           :label label
           :command command}
    mcp-tool (assoc :mcpTool mcp-tool)
    (seq mcp-args) (assoc :mcpArgs mcp-args)))

(defn- context-drilldowns
  [query-text project-id]
  [(drilldown :query
              "Continue graph query"
              (query-command query-text project-id)
              "ygg_query"
              (cond-> {:query query-text}
                project-id (assoc :projectId project-id)))
   (drilldown :systems
              "Inspect project systems graph"
              (project-command "view systems" project-id)
              "ygg_systems"
              (cond-> {}
                project-id (assoc :projectId project-id)))
   (drilldown :status
              "Inspect graph freshness and evidence status"
              (inspect-command)
              "ygg_status"
              nil)
   (drilldown :docs
              "Audit accepted documentation attachments"
              (docs-audit-command project-id)
              nil
              nil)])

(defn- sync-command
  [& args]
  (str "ygg sync <project.edn>"
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- action-distinct-key
  [action]
  (if (= :coverage (:kind action))
    [(:kind action) (:label action) (:command action)]
    [(:command action)]))

(defn- action-priority
  [{:keys [kind label]}]
  (case [kind label]
    [:freshness "Refresh indexed graph basis"] 0
    [:dependencies "Inspect packages without source import evidence"] 10
    [:dependencies "Inspect package version conflicts"] 10
    [:dependency-review "Queue unresolved import review work"] 15
    [:coverage "Inspect extractor diagnostics"] 20
    [:coverage "Inspect skipped source candidates"] 20
    [:dependencies "Inspect package graph facts"] 30
    50))

(defn- status-mcp
  []
  {:mcpTool "ygg_status"})

(defn- extractor-plugin-registry-command
  []
  "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>")

(defn- extractor-plugin-scaffold-command
  []
  "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>")

(defn- extractor-plugin-gap-command
  []
  "bb plugin gap extractor <package-dir> <repo-root> <file> --json")

(defn- next-actions
  ([counts retrieval project-id] (next-actions counts retrieval project-id nil))
  ([counts retrieval project-id freshness]
   (->> (cond-> (freshness-actions freshness)
          (zero? (:files counts 0))
          (conj {:kind :source-files
                 :label "Index project source files"
                 :command (sync-command)})

          (and (pos? (:files counts 0))
               (zero? (+ (:nodes counts) (:edges counts))))
          (conj {:kind :source-graph
                 :label "Validate indexed source graph rows"
                 :command (sync-command "--check")})

          (pos? (:diagnostics counts))
          (conj (merge {:kind :coverage
                        :label "Inspect extractor diagnostics"
                        :count (:diagnostics counts)
                        :command (command/command "ygg" "sync" "coverage" "<project.edn>" "--json")}
                       (status-mcp)))

          (pos? (:skipped-files counts 0))
          (conj (merge {:kind :coverage
                        :label "Inspect skipped source candidates"
                        :count (:skipped-files counts 0)
                        :command (command/command "ygg" "sync" "coverage" "<project.edn>" "--json")
                        :pluginRegistryCommand (extractor-plugin-registry-command)
                        :pluginScaffoldCommand (extractor-plugin-scaffold-command)
                        :pluginGapCommand (extractor-plugin-gap-command)}
                       (status-mcp)))

          (zero? (:search-docs counts))
          (conj {:kind :docs
                 :label "Build query index"
                 :command (sync-command "--query-index")})

          (or (and (= :auto (:requested retrieval)) (:fallback? retrieval))
              (and (pos? (:search-docs counts 0))
                   (zero? (:embeddings counts 0))))
          (conj {:kind :embeddings
                 :label "Index local graph embeddings"
                 :command "ygg embed"})

          (or (zero? (+ (:external-packages counts 0)
                        (:package-import-edges counts 0)
                        (:unresolved-imports counts 0)))
              (pos? (:unresolved-imports counts 0))
              (pos? (:package-evidence-gaps counts 0))
              (pos? (:package-conflicts counts 0)))
          (conj {:kind :dependencies
                 :label "Inspect package graph facts"
                 :command (package-command project-id "--json")})

          (zero? (:system-evidence counts 0))
          (conj {:kind :system-evidence
                 :label "Index system evidence rows"
                 :command (sync-command)})

          (pos? (:package-evidence-gaps counts 0))
          (conj {:kind :dependencies
                 :label "Inspect packages without source import evidence"
                 :count (:package-evidence-gaps counts 0)
                 :command (package-command project-id
                                           "--without-import-evidence"
                                           "--json")})

          (pos? (:package-conflicts counts 0))
          (conj {:kind :dependencies
                 :label "Inspect package version conflicts"
                 :count (:package-conflicts counts 0)
                 :command (package-command project-id "--with-conflicts" "--json")})

          (pos? (:unresolved-imports counts 0))
          (conj {:kind :dependency-review
                 :label "Queue unresolved import review work"
                 :count (:unresolved-imports counts 0)
                 :command (sync-command "--check" "--enqueue")})

          (pos? (:result-schema-mismatch-events counts 0))
          (conj {:kind :activity
                 :label "Inspect result schema mismatch activity"
                 :count (:result-schema-mismatch-events counts 0)
                 :mcpTool "ygg_sync_activity"
                 :command (command/command "ygg" "sync" "activity" "<project.edn>" "--json")})

          (pos? (+ (:result-schema-missing-result-items counts 0)
                   (:result-schema-unexpected-result-items counts 0)))
          (conj {:kind :activity
                 :label "Inspect result schema status activity"
                 :count (+ (:result-schema-missing-result-items counts 0)
                           (:result-schema-unexpected-result-items counts 0))
                 :mcpTool "ygg_sync_activity"
                 :command (command/command "ygg" "sync" "activity" "<project.edn>" "--json")})

          (zero? (+ (:system-nodes counts) (:system-edges counts)))
          (conj {:kind :system-graph
                 :label "Index project graph systems"
                 :command (sync-command)})

          (zero? (+ (:activity-items counts) (:activity-events counts)))
          (conj {:kind :activity
                 :label "Import local activity and work rows"
                 :mcpTool "ygg_sync_activity"
                 :command (command/command "ygg" "sync" "activity" "<project.edn>")}))
        (distinct-by action-distinct-key)
        (sort-by action-priority)
        (take 5)
        vec)))

(defn- evidence-readiness-status
  ([missing weak retrieval match-counts freshness]
   (evidence-readiness-status missing weak retrieval match-counts freshness nil))
  ([missing weak retrieval {:keys [entity-count doc-count activity-count]} freshness degradation]
   (let [core-missing? (some #{:source-files :source-graph :docs :system-graph} missing)
         core-weak? (some #{:source-files :system-graph :docs} weak)]
     (cond
       degradation :limited
       (and (zero? entity-count) (zero? doc-count) (zero? activity-count)) :empty
       (or (:fallback? retrieval)
           core-weak?
           core-missing?
           (freshness-problem? freshness)) :limited
       :else :ready))))

(defn- query-evidence
  [xtdb overlay opts match-counts]
  (let [capability-data (capability-count-data xtdb overlay opts)
        counts (:counts capability-data)
        retrieval (retrieval-summary opts)
        missing (missing-planes counts)
        weak (weak-planes counts match-counts)
        available (available-planes counts)
        planes (evidence-planes {:available available
                                 :missing missing
                                 :weak weak
                                 :unsupported unsupported-planes
                                 :counts counts})
        freshness (:freshness opts)
        degradation (:degradation opts)
        actions (next-actions counts retrieval (:project-id opts) freshness)]
    (cond-> {:basis "query-scoped-mechanical-readiness"
             :status (evidence-readiness-status missing
                                                weak
                                                retrieval
                                                match-counts
                                                freshness
                                                degradation)
             :available available
             :missing missing
             :weak weak
             :unsupported unsupported-planes
             :planes planes
             :counts counts
             :countsCache (:cache-status capability-data)
             :countsCacheGeneration (:cache-generation capability-data)
             :retrieval retrieval
             :warnings (evidence-warnings counts retrieval weak freshness degradation)
             :nextActions actions}
      degradation (assoc :degradation degradation))))

(defn context-packet
  "Return compact graph/doc context for an agent query."
  [xtdb query-text {:keys [budget entity-limit edge-limit doc-limit snippet-chars
                           retrieval-limit
                           retriever embedding-client semantic-status project-id repo-id
                           correction-overlay min-confidence read-context freshness plugins
                           output proof-commands? query-input memory-owner
                           fusion-strategy sqlite-fts? diversity-rerank-limit
                           fts-candidate-limit fts-weight embedding-role embedding-roles
                           exclude-private-memory? active-indexing progress-fn
                           persist-query-run?]
                    :or {budget default-budget
                         entity-limit default-entity-limit
                         edge-limit default-edge-limit
                         doc-limit default-doc-limit
                         snippet-chars default-snippet-chars
                         retrieval-limit default-retrieval-limit
                         retriever :auto
                         output default-output
                         min-confidence 0.55}}]
  (when (str/blank? (str query-text))
    (throw (ex-info "Missing context query text." {})))
  (when (str/blank? (str project-id))
    (throw (ex-info "Context query requires --project." {})))
  (let [context-started (now-ns)
        _ (when progress-fn
            (progress-fn {:phase :context-start
                          :project-id project-id
                          :repo-id repo-id}))
        overlay (resolve-correction-overlay xtdb nil correction-overlay project-id)
        query-tokens (text/tokenize query-text)
        graph-task (timed-context-task
                    #(graph/system-graph xtdb
                                         project-id
                                         {:limit graph/default-node-limit
                                          :min-confidence min-confidence
                                          :correction-overlay correction-overlay
                                          :read-context read-context}))
        dependency-task (timed-context-task
                         #(dependency/package-report
                           xtdb
                           {:project-id project-id
                            :repo-id repo-id}
                           {:correction-overlay overlay}))
        coverage-task (timed-context-task
                       #(coverage/context-summary
                         xtdb
                         {:project-id project-id
                          :repo-id repo-id
                          :read-context read-context}))
        [search-report timings] (timed-context-step
                                 {}
                                 :search
                                 #(query/search-report xtdb
                                                       query-text
                                                       (cond->
                                                        {:limit retrieval-limit
                                                         :retriever retriever
                                                         :embedding-client embedding-client
                                                         :project-id project-id
                                                         :repo-id repo-id
                                                         :read-context read-context
                                                         :fusion-strategy fusion-strategy
                                                         :sqlite-fts? sqlite-fts?
                                                         :diversity-rerank-limit diversity-rerank-limit
                                                         :fts-candidate-limit fts-candidate-limit
                                                         :fts-weight fts-weight
                                                         :embedding-role embedding-role
                                                         :embedding-roles embedding-roles
                                                         :progress-fn progress-fn}
                                                         (some? persist-query-run?)
                                                         (assoc :persist-query-run?
                                                                persist-query-run?))))
        results (:results search-report)
        [source-candidate-data timings] (timed-context-step
                                         timings
                                         :source-candidates
                                         #(source-graph-candidates
                                           xtdb
                                           query-tokens
                                           results
                                           {:project-id project-id
                                            :repo-id repo-id
                                            :read-context read-context}))
        source-candidates (:candidates source-candidate-data)
        candidate-inputs (ranked-candidate-inputs query-tokens
                                                  results
                                                  source-candidates)
        base-candidate-file-rows (candidate-files candidate-inputs)
        [sibling-candidate-file-rows timings] (timed-context-step
                                               timings
                                               :expand-candidate-siblings
                                               #(expand-candidate-file-siblings
                                                 xtdb
                                                 query-tokens
                                                 base-candidate-file-rows
                                                 {:project-id project-id
                                                  :repo-id repo-id
                                                  :read-context read-context}))
        [candidate-file-rows timings] (timed-context-step
                                       timings
                                       :expand-candidate-support-owners
                                       #(expand-candidate-file-support-owners
                                         xtdb
                                         query-tokens
                                         sibling-candidate-file-rows
                                         {:project-id project-id
                                          :repo-id repo-id
                                          :read-context read-context}))
        source-declaration-task (timed-context-task
                                 #(source-graph-declarations
                                   xtdb
                                   query-tokens
                                   source-candidates
                                   candidate-file-rows
                                   {:project-id project-id
                                    :repo-id repo-id
                                    :read-context read-context}))
        [graph-data timings] (await-context-task timings
                                                 :system-graph
                                                 graph-task)
        entities (select-entities query-tokens results graph-data entity-limit)
        edges (select-edges query-tokens entities graph-data edge-limit)
        _ (when progress-fn
            (progress-fn {:phase :context-graph-complete
                          :project-id project-id
                          :repo-id repo-id
                          :entity-count (count entities)
                          :edge-count (count edges)}))
        accepted-systems (selected-accepted-systems overlay entities results)
        targets (set/union (selected-targets entities edges)
                           (system-targets accepted-systems))
        attachments (attachment-docs-for-targets overlay targets)
        selected-system-ids (concat (map :id entities)
                                    (map :id accepted-systems))
        chunks-task (timed-context-task
                     #(context-chunks xtdb
                                      results
                                      attachments
                                      candidate-inputs
                                      {:project-id project-id
                                       :repo-id repo-id
                                       :read-context read-context}))
        activity-task (timed-context-task
                       #(activity/select-activity xtdb
                                                  query-text
                                                  {:project-id project-id
                                                   :read-context read-context
                                                   :target-ids targets}))
        memory-task (timed-context-task
                     #(memory/context-memories xtdb
                                               query-text
                                               {:project-id project-id
                                                :repo-id repo-id
                                                :read-context read-context
                                                :target-ids targets
                                                :owner memory-owner
                                                :exclude-private?
                                                exclude-private-memory?}))
        system-evidence-task (timed-context-task
                              #(selected-system-evidence
                                xtdb
                                selected-system-ids
                                candidate-inputs
                                {:project-id project-id
                                 :repo-id repo-id
                                 :read-context read-context}))
        [chunks timings] (await-context-task timings :context-chunks chunks-task)
        [activity timings] (await-context-task timings :activity activity-task)
        [memories timings] (await-context-task timings :memories memory-task)
        [system-evidence timings] (await-context-task timings
                                                      :system-evidence
                                                      system-evidence-task)
        [dependency-report timings] (await-context-task timings
                                                        :package-report
                                                        dependency-task)
        runtime-evidence (select-system-evidence query-tokens
                                                 selected-system-ids
                                                 candidate-inputs
                                                 system-evidence
                                                 12)
        degradation (indexing-degradation active-indexing)
        warnings (cond-> []
                   degradation
                   (conj (:message degradation))

                   (empty? entities)
                   (conj "no graph entities matched the query"))
        drilldowns (context-drilldowns query-text project-id)
        [docs timings] (timed-context-step
                        timings
                        :docs
                        (fn []
                          (select-docs
                           (concat (attached-docs overlay chunks snippet-chars targets)
                                   (inferred-docs query-tokens
                                                  candidate-inputs
                                                  chunks
                                                  entities
                                                  snippet-chars))
                           results
                           doc-limit
                           query-tokens)))
        search-context (-> (select-keys search-report
                                        [:schema
                                         :query-run-id
                                         :retriever-requested
                                         :retriever-effective
                                         :instrumentation])
                           (update :instrumentation
                                   merge
                                   {:context-chunks (count chunks)
                                    :parallel-context-steps
                                    [:system-graph
                                     :package-report
                                     :source-coverage]
                                    :source-candidate-strategy
                                    (:strategy source-candidate-data)
                                    :source-candidate-graph-expansion
                                    (:graph-expansion source-candidate-data)
                                    :source-candidate-search-results
                                    (:search-result-count source-candidate-data)
                                    :source-candidate-seeds
                                    (:seed-count source-candidate-data)
                                    :source-candidate-node-seeds
                                    (:node-seed-count source-candidate-data)
                                    :source-candidate-file-seeds
                                    (:file-seed-count source-candidate-data)}))
        [evidence timings] (timed-context-step
                            timings
                            :evidence
                            #(query-evidence xtdb
                                             overlay
                                             {:project-id project-id
                                              :repo-id repo-id
                                              :read-context read-context
                                              :retriever retriever
                                              :retriever-effective (:retriever-effective
                                                                    search-report)
                                              :semantic-status semantic-status
                                              :auto-lexical-short-circuit?
                                              (get-in search-report
                                                      [:instrumentation
                                                       :auto-lexical-short-circuit?])
                                              :auto-lexical-short-circuit-reason
                                              (get-in search-report
                                                      [:instrumentation
                                                       :auto-lexical-short-circuit-reason])
                                              :embedding-client embedding-client
                                              :freshness freshness
                                              :degradation degradation
                                              :dependency-counts (:counts dependency-report)
                                              :system-evidence-count (count system-evidence)
                                              :search-doc-count (get-in search-report
                                                                        [:instrumentation
                                                                         :durable-search-docs])}
                                             {:entity-count (count entities)
                                              :doc-count (count docs)
                                              :memory-count (count memories)
                                              :activity-count (count activity)
                                              :runtime-count (count runtime-evidence)
                                              :validation-count (count
                                                                 (filter
                                                                  (fn [item]
                                                                    (some (fn [event]
                                                                            (= :validation
                                                                               (:event-kind event)))
                                                                          (:events item)))
                                                                  activity))}))
        [source-coverage timings] (await-context-task timings
                                                      :source-coverage
                                                      coverage-task)
        [source-declarations timings] (await-context-task timings
                                                          :source-declarations
                                                          source-declaration-task)
        [architecture timings] (timed-context-step
                                timings
                                :architecture
                                #(architecture-section {:overlay overlay
                                                        :entities entities
                                                        :results results
                                                        :candidate-inputs candidate-inputs
                                                        :edges edges
                                                        :accepted-systems accepted-systems
                                                        :query-tokens query-tokens
                                                        :runtime-evidence runtime-evidence
                                                        :dependency-report dependency-report
                                                        :docs docs
                                                        :activity activity
                                                        :evidence evidence
                                                        :freshness freshness}))
        [candidate-file-rows timings] (timed-context-step
                                       timings
                                       :architecture-candidates
                                       #(vec
                                         (concat candidate-file-rows
                                                 (architecture-candidate-file-rows
                                                  architecture
                                                  (count candidate-file-rows)))))
        systems (systems-section architecture)
        [audit-scopes timings] (timed-context-step
                                timings
                                :audit-scopes
                                #(audit-scope/selected-summaries
                                  {:boundary-evidence (:boundaryEvidence architecture)
                                   :runtime-evidence (:runtimeEvidence architecture)
                                   :dependency-evidence (:dependencyEvidence architecture)
                                   :rejected-corrections (:rejectedCorrections architecture)
                                   :docs (:docs architecture)}))
        plugin-packages (get plugins :packages)
        blast-radius (blast-radius entities edges)
        output-mode (keyword (or output default-output))
        [packet timings] (timed-context-step
                          timings
                          :base-packet
                          #(cond-> (base-packet {:query-text query-text
                                                 :budget budget
                                                 :graph-data graph-data
                                                 :entities entities
                                                 :edges edges
                                                 :activity activity
                                                 :warnings warnings
                                                 :drilldowns drilldowns
                                                 :evidence evidence
                                                 :search-instrumentation search-context
                                                 :freshness freshness
                                                 :source-coverage source-coverage
                                                 :architecture architecture
                                                 :systems systems
                                                 :audit-scopes audit-scopes
                                                 :relationships (relationship-groups edges)
                                                 :blast-radius blast-radius
                                                 :candidate-files candidate-file-rows
                                                 :source-declarations source-declarations
                                                 :plugin-packages plugin-packages
                                                 :memories memories
                                                 :degradation degradation})
                             (seq (compact-query-input query-input))
                             (assoc :input query-input)))
        timings (assoc timings :total (elapsed-ms context-started))
        packet (update-in packet
                          [:search :instrumentation]
                          assoc
                          :context-timings-ms
                          timings)
        packet (if (= :compact output-mode)
                 packet
                 (fit-budget packet docs budget))
        output-packet (packet-output packet
                                     query-text
                                     output-mode
                                     proof-commands?)]
    (when progress-fn
      (progress-fn {:phase :context-complete
                    :project-id project-id
                    :repo-id repo-id
                    :elapsed-ms (:total timings)}))
    output-packet))

(defn doc-candidates
  "Return compact doc candidates for a graph target."
  [xtdb target {:keys [project-id limit snippet-chars read-context]
                :or {limit default-doc-limit
                     snippet-chars default-snippet-chars}}]
  (let [target-text (str target)
        target-tokens (text/tokenize target-text)
        chunks (doc-candidate-chunks xtdb
                                     target-tokens
                                     {:project-id project-id
                                      :read-context read-context})]
    (->> chunks
         (filter #(= :markdown (:kind %)))
         (map #(assoc % :context-score (token-score target-tokens
                                                    (compact (:label %) (:path %) (:text %)))))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt (comp - :context-score) :path :source-line))
         (take limit)
         (mapv (fn [chunk]
                 {:target target
                  :role "reference"
                  :status "candidate"
                  :source (chunk-source chunk)
                  :score (double (:context-score chunk))
                  :snippet (truncate (:text chunk) snippet-chars)})))))

(defn docs-for
  "Return attached docs for target with resolved snippets where possible."
  [xtdb target {:keys [project-id correction-overlay snippet-chars read-context]
                :or {snippet-chars default-snippet-chars}}]
  (let [overlay (resolve-correction-overlay xtdb nil correction-overlay project-id)
        attachments (correction-overlay/docs-for-target overlay target)
        chunks (attachment-chunks xtdb
                                  attachments
                                  {:project-id project-id
                                   :read-context read-context})]
    {:schema "ygg.docs/v1"
     :target target
     :docs (mapv #(attachment->doc chunks snippet-chars %)
                 attachments)}))

(defn docs-audit
  "Return maintenance findings for doc attachments and mapped systems."
  [xtdb {:keys [project-id correction-overlay read-context]}]
  (let [overlay (resolve-correction-overlay xtdb nil correction-overlay project-id)
        systems (:systems overlay)
        docs (:docs overlay)
        chunks (attachment-chunks xtdb
                                  docs
                                  {:project-id project-id
                                   :read-context read-context})
        docs-by-target (group-by :target docs)
        unresolved (->> docs
                        (map #(attachment->doc chunks default-snippet-chars %))
                        (filter #(= "stale" (:status %)))
                        (mapv #(dissoc % :snippet)))
        undocumented (->> systems
                          (filter #(= "accepted" (s (:status %))))
                          (remove #(seq (get docs-by-target (:id %))))
                          (mapv #(select-keys % [:id :label :kind])))]
    {:schema "ygg.docs.audit/v1"
     :project project-id
     :counts {:attachments (count docs)
              :unresolved (count unresolved)
              :undocumentedSystems (count undocumented)}
     :unresolved unresolved
     :undocumentedSystems undocumented}))
