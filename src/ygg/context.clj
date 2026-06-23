(ns ygg.context
  "Token-bounded, graph-grounded context packets for agents."
  (:require [ygg.activity :as activity]
            [ygg.audit-scope :as audit-scope]
            [ygg.command :as command]
            [ygg.context-architecture :as context-architecture]
            [ygg.context-budget :as context-budget]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.graph :as graph]
            [ygg.map :as graph-map]
            [ygg.map-store :as map-store]
            [ygg.plugin-package-view :as plugin-package-view]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "ygg.context/v1")

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

(def ^:private source-graph-candidate-limit
  40)

(def ^:private source-graph-file-candidate-limit
  80)

(def ^:private source-graph-neighbor-scan-limit
  160)

(def ^:private source-graph-neighbor-seed-limit
  96)

(def ^:private source-graph-neighbor-candidate-limit
  40)

(def ^:private source-graph-neighbor-kind-path-limit
  4)

(def ^:private source-graph-neighbor-support-boost
  1.0)

(def ^:private doc-diversify-candidate-limit-min
  80)

(def ^:private doc-diversify-candidate-limit-multiplier
  12)

(def ^:private source-graph-neighbor-score-cap
  4.8)

(defn- active-row?
  [row]
  (not= false (:active? row)))

(def ^:private candidate-input-root-diversity-limit
  8)

(def ^:private source-graph-candidate-min-score
  1.0)

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
   :activity
   :validation-history
   :map-overlay
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
   :activity [:activity-items :activity-events]
   :validation-history [:validation-events
                        :result-schema-status-items
                        :result-schema-matching-items
                        :result-schema-mismatch-items
                        :result-schema-missing-result-items
                        :result-schema-unexpected-result-items
                        :result-schema-mismatch-events]
   :map-overlay [:map-systems :map-docs :map-edges :map-rejects]})

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
   result-scores
   result-scores-by-path-label
   result-scores-by-file-path
   chunk]
  (+ (double (or (result-score-for-chunk result-scores
                                         result-scores-by-path-label
                                         result-scores-by-file-path
                                         chunk)
                 0.0))
     (* 0.35 (capped-candidate-token-score query-tokens
                                           (chunk-search-tokens chunk)))
     (* 0.45 (capped-token-score query-tokens
                                 (display-name (:definition-kind chunk))))
     (* 0.15 (min 1.0
                  (text/token-score selected-label-tokens (:tokens chunk))))))

(defn- inferred-docs
  [query-tokens results chunks entities snippet-chars]
  (let [result-scores (result-score-by-target results)
        result-scores-by-path-label (result-score-by-path-label results)
        result-scores-by-file-path (result-score-by-file-path results)
        results-by-target (result-by-target results)
        selected-label-tokens (text/tokenize (str/join " " (map :label entities)))]
    (->> chunks
         (filter #(or (= :markdown (:kind %))
                      (result-score-for-chunk result-scores
                                              result-scores-by-path-label
                                              result-scores-by-file-path
                                              %)))
         (map #(assoc % :context-score (chunk-score query-tokens
                                                    selected-label-tokens
                                                    result-scores
                                                    result-scores-by-path-label
                                                    result-scores-by-file-path
                                                    %)
                      :retrieved? (boolean (result-score-for-chunk
                                            result-scores
                                            result-scores-by-path-label
                                            result-scores-by-file-path
                                            %))
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

(defn- distinct-docs
  [docs]
  (loop [remaining (seq docs)
         seen #{}
         out []]
    (if-let [doc (first remaining)]
      (let [k [(get-in doc [:source :repo])
               (get-in doc [:source :path])
               (get-in doc [:source :lines])]]
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

(defn- doc-definition-kind-key
  [doc]
  (let [source (:source doc)]
    [(or (:repo source) :unknown-repo)
     (get-in doc [:source :definitionKind])]))

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
    (distinct-docs (concat (take candidate-limit docs)
                           (keep representatives result-paths)))))

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
   :mapSystems (long (or (:map-systems counts) 0))
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
        support-labels (fn [candidate]
                         (let [label (not-empty (str (:label candidate)))]
                           (cond-> []
                             (and label (:sourceLine candidate))
                             (conj label)

                             (seq (:supportLabels candidate))
                             (into (:supportLabels candidate)))))
        merged-support-labels (fn [primary-label candidates]
                                (->> candidates
                                     (mapcat support-labels)
                                     (remove #(= primary-label %))
                                     distinct
                                     (take support-label-limit)
                                     vec))
        max-components (fn [a b]
                         (merge-with #(max (double (or %1 0.0))
                                           (double (or %2 0.0)))
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
                                                                [earlier later])]
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

(defn- source-graph-candidate-score
  [query-tokens row]
  (text/token-score query-tokens
                    (text/tokenize-all
                     (compact (:path row)
                              (:label row)
                              (:name row)
                              (:kind row)))))

(defn- row-id
  [row]
  (or (:xt/id row)
      (:id row)
      (:_id row)
      (get row "_id")))

(defn- source-graph-candidate-row
  [query-tokens row]
  (let [path (not-empty (str (:path row)))
        score (source-graph-candidate-score query-tokens row)]
    (when (and path
               (<= source-graph-candidate-min-score score))
      (let [file-row? (nil? (:label row))]
        (cond-> {:path path
                 :rank 0
                 :score score
                 :target-kind (if file-row? :file :node)
                 :target-id (row-id row)
                 :label (or (:label row) path)
                 :kind (:kind row)
                 :result-kind (if file-row? :file :node)
                 :reason "query-matched source row"
                 :score-components {:sourceGraph score}}
          (:repo-id row) (assoc :repo-id (:repo-id row)
                                :repo (:repo-id row))
          (:source-line row) (assoc :source-line (:source-line row))
          (:end-line row) (assoc :end-line (:end-line row)))))))

(defn- ranked-source-graph-candidates
  [query-tokens rows limit]
  (->> rows
       (keep #(source-graph-candidate-row query-tokens %))
       (sort-by (juxt (comp - :score)
                      :repo
                      :path
                      :label))
       (take limit)
       (map-indexed #(assoc %2 :rank (inc %1)))
       vec))

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
                              (remove str/blank?)
                              distinct
                              (take 4)
                              vec)]
      (cond-> {:path path
               :rank 0
               :score score
               :target-kind :node
               :target-id (:xt/id node)
               :label (or (:label node) path)
               :kind (:kind node)
               :result-kind :node
               :reason "graph-neighbor source row"
               :score-components {:sourceGraph score}}
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

(defn- source-graph-neighbor-kind-path-rows
  [rows]
  (loop [remaining (seq rows)
         counts {}
         selected-paths #{}
         selected []]
    (if-let [row (first remaining)]
      (let [kind-key (source-graph-neighbor-kind-key row)
            path-key (source-graph-neighbor-path-key row)
            kind-count (long (or (get counts kind-key) 0))]
        (if (or (contains? selected-paths path-key)
                (<= source-graph-neighbor-kind-path-limit kind-count))
          (recur (next remaining) counts selected-paths selected)
          (recur (next remaining)
                 (update counts kind-key (fnil inc 0))
                 (conj selected-paths path-key)
                 (conj selected row))))
      selected)))

(defn- select-source-graph-neighbor-rows
  [rows]
  (let [kind-path-rows (source-graph-neighbor-kind-path-rows rows)
        selected-paths (set (map source-graph-neighbor-path-key kind-path-rows))]
    (->> (concat kind-path-rows
                 (remove #(contains? selected-paths
                                     (source-graph-neighbor-path-key %))
                         rows))
         (take source-graph-neighbor-candidate-limit)
         vec)))

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
                                         (:label seed))))
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
                                ::neighbor-seed-rank
                                :repo-id
                                :path
                                :label))
                 select-source-graph-neighbor-rows
                 (map #(dissoc % ::neighbor-seed-rank))
                 (map-indexed #(assoc %2 :rank (inc %1)))
                 vec)))))))

(def ^:private source-graph-node-token-fields
  [:path :label :name :kind])

(def ^:private source-graph-file-token-fields
  [:path :kind])

(defn- source-graph-scope-constraints
  [project-id repo-id]
  (cond-> {:project-id project-id}
    repo-id (assoc :repo-id repo-id)))

(defn- source-graph-candidates
  [xtdb query-tokens {:keys [project-id repo-id read-context]}]
  (if-not (store/xtdb-handle? xtdb)
    []
    (let [constraints (source-graph-scope-constraints project-id repo-id)
          matched-node-candidates (ranked-source-graph-candidates
                                   query-tokens
                                   (store/rows-matching-any-token
                                    xtdb
                                    (:nodes store/tables)
                                    source-graph-node-token-fields
                                    query-tokens
                                    constraints
                                    read-context)
                                   source-graph-neighbor-scan-limit)
          node-candidates (vec (take source-graph-candidate-limit
                                     matched-node-candidates))
          scope {:project-id project-id
                 :repo-id repo-id
                 :read-context read-context}]
      (vec
       (concat
        node-candidates
        (source-graph-neighbor-candidates xtdb matched-node-candidates scope)
        (ranked-source-graph-candidates query-tokens
                                        (store/rows-matching-any-token
                                         xtdb
                                         (:files store/tables)
                                         source-graph-file-token-fields
                                         query-tokens
                                         (assoc constraints :active? true)
                                         read-context)
                                        source-graph-file-candidate-limit))))))

(defn- candidate-input-score
  [row]
  (double (or (:score row) 0.0)))

(defn- candidate-input-root
  [row]
  (let [path (str (:path row))]
    (or (first (str/split path #"/"))
        path)))

(defn- preserve-candidate-input-root-diversity
  [rows]
  (let [selected (->> rows
                      (reduce (fn [{:keys [seen selected] :as state} row]
                                (let [root (candidate-input-root row)]
                                  (if (or (contains? seen root)
                                          (<= candidate-input-root-diversity-limit
                                              (count selected)))
                                    state
                                    {:seen (conj seen root)
                                     :selected (conj selected row)})))
                              {:seen #{}
                               :selected []})
                      :selected)
        selected-indexes (set (map ::candidate-input-index selected))]
    (concat selected
            (remove #(contains? selected-indexes
                                (::candidate-input-index %))
                    rows))))

(defn- ranked-candidate-inputs
  [results source-candidates]
  (if-not (seq source-candidates)
    results
    (->> (concat results source-candidates)
         (map-indexed (fn [idx row]
                        (assoc row ::candidate-input-index idx)))
         (sort-by (juxt (comp - candidate-input-score)
                        #(or (:repo-id %) (:repo %) "")
                        #(or (:path %) "")
                        #(or (:label %) "")
                        ::candidate-input-index))
         preserve-candidate-input-root-diversity
         (map #(dissoc % ::candidate-input-index)))))

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
  [query-text budget graph-data entities edges activity warnings drilldowns evidence
   search-instrumentation freshness source-coverage architecture systems audit-scopes
   relationships blast-radius candidate-files plugin-packages]
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
    evidence (assoc :evidence evidence)
    search-instrumentation (assoc :search search-instrumentation)
    freshness (assoc :freshness freshness)
    architecture (assoc :architecture architecture)
    systems (assoc :systems systems)
    (seq audit-scopes) (assoc :auditScopes audit-scopes)
    (seq plugin-packages) (assoc :pluginPackages (plugin-package-view/caveats plugin-packages))
    (seq relationships) (assoc :relationships relationships)
    blast-radius (assoc :blastRadius blast-radius)
    source-coverage (assoc :sourceCoverage source-coverage)))

(defn- fit-budget
  [packet docs budget]
  (context-budget/fit-budget packet docs budget))

(defn- resolve-map-overlay
  [path overlay project-id]
  (cond
    overlay overlay
    (and path (map-store/file-exists? path)) (map-store/read-map path)
    :else (graph-map/empty-map project-id)))

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
  {:map-systems (count (:systems overlay))
   :map-docs (count (:docs overlay))
   :map-edges (count (:edges overlay))
   :map-rejects (count (:reject overlay))})

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
                                           :map-overlay overlay}))))

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
      :skipped-files (coverage/index-run-skipped-files
                      xtdb
                      {:project-id project-id
                       :repo-id repo-id
                       :read-context read-context})
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
      :skipped-files (coverage/index-run-skipped-files
                      xtdb
                      {:project-id project-id
                       :repo-id repo-id
                       :read-context read-context})
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
     (overlay-counts overlay))))

(defn- capability-counts
  [xtdb overlay opts]
  (if (store/xtdb-handle? xtdb)
    (xtdb-capability-counts xtdb overlay opts)
    (fallback-capability-counts xtdb overlay opts)))

(defn- validation-history-count
  [counts]
  (+ (:validation-events counts 0)
     (:result-schema-status-items counts 0)
     (:result-schema-mismatch-events counts 0)))

(defn- retrieval-summary
  [{:keys [retriever embedding-client]}]
  (let [requested (keyword (or retriever :auto))
        effective (case requested
                    :auto (if embedding-client :hybrid :lexical)
                    requested)
        fallback? (and (= :auto requested) (= :lexical effective))]
    (cond-> {:requested requested
             :effective effective
             :fallback? fallback?}
      fallback? (assoc :reason "No embedding client was available."))))

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
      (pos? (+ (:activity-items counts) (:activity-events counts))) (conj :activity)
      (pos? validation-history-events) (conj :validation-history)
      (pos? (+ (:map-systems counts)
               (:map-docs counts)
               (:map-edges counts)
               (:map-rejects counts))) (conj :map-overlay))))

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
  [counts {:keys [entity-count doc-count activity-count validation-count runtime-count]}]
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

(defn- evidence-warnings
  ([counts retrieval weak] (evidence-warnings counts retrieval weak nil))
  ([counts retrieval weak freshness]
   (cond-> []
     (freshness-warning freshness)
     (conj (freshness-warning freshness))

     (and (= :auto (:requested retrieval)) (:fallback? retrieval))
     (conj "No embedding client was available; retrieval used lexical fallback.")

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
     (conj "Some files were skipped by the latest index run; inspect source coverage before treating missing facts as absent.")

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

(defn- explore-command
  [query-text project-id]
  (str "ygg explore "
       (command/shell-token query-text)
       " --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- docs-audit-command
  [project-id map-path]
  (str "ygg sync docs audit --project "
       (command/shell-token (or project-id "<project-id>"))
       " --map "
       (command/shell-token map-path)))

(defn- inspect-command
  [map-path]
  (apply command/command
         (concat ["ygg" "sync" "inspect" "<project.edn>"]
                 (when map-path
                   ["--map" map-path])
                 ["--json"])))

(defn- drilldown
  [kind label command mcp-tool mcp-args]
  (cond-> {:kind kind
           :label label
           :command command}
    mcp-tool (assoc :mcpTool mcp-tool)
    (seq mcp-args) (assoc :mcpArgs mcp-args)))

(defn- context-drilldowns
  [query-text project-id map-path]
  (cond-> [(drilldown :explore
                      "Continue primary graph exploration"
                      (explore-command query-text project-id)
                      "ygg_explore"
                      (cond-> {:query query-text}
                        project-id (assoc :projectId project-id)
                        map-path (assoc :mapPath map-path)))
           (drilldown :systems
                      "Inspect project systems graph"
                      (project-command "view systems" project-id)
                      "ygg_systems"
                      (cond-> {}
                        project-id (assoc :projectId project-id)
                        map-path (assoc :mapPath map-path)))
           (drilldown :status
                      "Inspect graph freshness and evidence status"
                      (inspect-command map-path)
                      "ygg_status"
                      (cond-> {}
                        map-path (assoc :mapPath map-path)))]
    map-path (conj (drilldown :docs
                              "Audit accepted documentation attachments"
                              (docs-audit-command project-id map-path)
                              nil
                              nil))))

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
  [map-path]
  (cond-> {:mcpTool "ygg_status"}
    map-path (assoc :mcpArgs {:mapPath map-path})))

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
  ([counts retrieval project-id] (next-actions counts retrieval project-id nil nil))
  ([counts retrieval project-id freshness] (next-actions counts retrieval project-id freshness nil))
  ([counts retrieval project-id freshness map-path]
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
                       (status-mcp map-path)))

          (pos? (:skipped-files counts 0))
          (conj (merge {:kind :coverage
                        :label "Inspect skipped source candidates"
                        :count (:skipped-files counts 0)
                        :command (command/command "ygg" "sync" "coverage" "<project.edn>" "--json")
                        :pluginRegistryCommand (extractor-plugin-registry-command)
                        :pluginScaffoldCommand (extractor-plugin-scaffold-command)
                        :pluginGapCommand (extractor-plugin-gap-command)}
                       (status-mcp map-path)))

          (zero? (:search-docs counts))
          (conj {:kind :docs
                 :label "Build query index"
                 :command (sync-command "--query-index")})

          (and (= :auto (:requested retrieval)) (:fallback? retrieval))
          (conj {:kind :embeddings
                 :label "Index local graph embeddings"
                 :command "ygg embed --provider openrouter"})

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
  [missing weak retrieval {:keys [entity-count doc-count activity-count]} freshness]
  (let [core-missing? (some #{:source-files :source-graph :docs :system-graph} missing)
        core-weak? (some #{:source-files :system-graph :docs} weak)]
    (cond
      (and (zero? entity-count) (zero? doc-count) (zero? activity-count)) :empty
      (or (:fallback? retrieval)
          core-weak?
          core-missing?
          (freshness-problem? freshness)) :limited
      :else :ready)))

(defn- query-evidence
  [xtdb overlay opts match-counts]
  (let [counts (capability-counts xtdb overlay opts)
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
        actions (next-actions counts retrieval (:project-id opts) freshness (:map-path opts))]
    {:basis "query-scoped-mechanical-readiness"
     :status (evidence-readiness-status missing weak retrieval match-counts freshness)
     :available available
     :missing missing
     :weak weak
     :unsupported unsupported-planes
     :planes planes
     :counts counts
     :retrieval retrieval
     :warnings (evidence-warnings counts retrieval weak freshness)
     :nextActions actions}))

(defn context-packet
  "Return compact graph/doc context for an agent query."
  [xtdb query-text {:keys [budget entity-limit edge-limit doc-limit snippet-chars
                           retrieval-limit
                           retriever embedding-client project-id repo-id map-path
                           map-overlay min-confidence read-context freshness plugins]
                    :or {budget default-budget
                         entity-limit default-entity-limit
                         edge-limit default-edge-limit
                         doc-limit default-doc-limit
                         snippet-chars default-snippet-chars
                         retrieval-limit default-retrieval-limit
                         retriever :auto
                         min-confidence 0.55}}]
  (when (str/blank? (str query-text))
    (throw (ex-info "Missing context query text." {})))
  (when (str/blank? (str project-id))
    (throw (ex-info "Context query requires --project." {})))
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        query-tokens (text/tokenize query-text)
        search-report (query/search-report xtdb
                                           query-text
                                           {:limit retrieval-limit
                                            :retriever retriever
                                            :embedding-client embedding-client
                                            :project-id project-id
                                            :repo-id repo-id
                                            :read-context read-context})
        results (:results search-report)
        source-candidates (source-graph-candidates
                           xtdb
                           query-tokens
                           {:project-id project-id
                            :repo-id repo-id
                            :read-context read-context})
        candidate-inputs (ranked-candidate-inputs results source-candidates)
        graph-data (graph/system-graph xtdb
                                       project-id
                                       {:limit graph/default-node-limit
                                        :min-confidence min-confidence
                                        :map-path map-path
                                        :map-overlay map-overlay
                                        :read-context read-context})
        entities (select-entities query-tokens results graph-data entity-limit)
        edges (select-edges query-tokens entities graph-data edge-limit)
        accepted-systems (selected-accepted-systems overlay entities results)
        targets (set/union (selected-targets entities edges)
                           (system-targets accepted-systems))
        attachments (attachment-docs-for-targets overlay targets)
        chunks (context-chunks xtdb
                               results
                               attachments
                               candidate-inputs
                               {:project-id project-id
                                :repo-id repo-id
                                :read-context read-context})
        activity (activity/select-activity xtdb
                                           query-text
                                           {:project-id project-id
                                            :read-context read-context
                                            :target-ids targets})
        selected-system-ids (concat (map :id entities)
                                    (map :id accepted-systems))
        system-evidence (selected-system-evidence
                         xtdb
                         selected-system-ids
                         candidate-inputs
                         {:project-id project-id
                          :repo-id repo-id
                          :read-context read-context})
        dependency-report (dependency/package-report xtdb
                                                     {:project-id project-id
                                                      :repo-id repo-id}
                                                     {:map-overlay overlay})
        runtime-evidence (select-system-evidence query-tokens
                                                 selected-system-ids
                                                 candidate-inputs
                                                 system-evidence
                                                 12)
        warnings (cond-> []
                   (empty? entities)
                   (conj "no graph entities matched the query"))
        drilldowns (context-drilldowns query-text project-id map-path)
        docs (->> (concat (attached-docs overlay chunks snippet-chars targets)
                          (inferred-docs query-tokens
                                         candidate-inputs
                                         chunks
                                         entities
                                         snippet-chars))
                  (#(select-docs % results doc-limit query-tokens)))
        search-context (-> (select-keys search-report
                                        [:schema
                                         :query-run-id
                                         :retriever-requested
                                         :retriever-effective
                                         :instrumentation])
                           (update :instrumentation assoc
                                   :context-chunks (count chunks)))
        evidence (query-evidence xtdb
                                 overlay
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :read-context read-context
                                  :retriever retriever
                                  :embedding-client embedding-client
                                  :freshness freshness
                                  :dependency-counts (:counts dependency-report)
                                  :system-evidence-count (count system-evidence)
                                  :search-doc-count (get-in search-report
                                                            [:instrumentation
                                                             :search-docs])}
                                 {:entity-count (count entities)
                                  :doc-count (count docs)
                                  :activity-count (count activity)
                                  :runtime-count (count runtime-evidence)
                                  :validation-count (count (filter #(some (fn [event]
                                                                            (= :validation (:event-kind event)))
                                                                          (:events %))
                                                                   activity))})
        source-coverage (coverage/context-summary xtdb
                                                  {:project-id project-id
                                                   :repo-id repo-id
                                                   :read-context read-context})
        architecture (architecture-section {:overlay overlay
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
                                            :freshness freshness})
        systems (systems-section architecture)
        audit-scopes (audit-scope/selected-summaries
                      {:boundary-evidence (:boundaryEvidence architecture)
                       :runtime-evidence (:runtimeEvidence architecture)
                       :dependency-evidence (:dependencyEvidence architecture)
                       :rejected-corrections (:rejectedCorrections architecture)
                       :docs (:docs architecture)})
        plugin-packages (get plugins :packages)
        blast-radius (blast-radius entities edges)]
    (fit-budget (base-packet query-text
                             budget
                             graph-data
                             entities
                             edges
                             activity
                             warnings
                             drilldowns
                             evidence
                             search-context
                             freshness
                             source-coverage
                             architecture
                             systems
                             audit-scopes
                             (relationship-groups edges)
                             blast-radius
                             (candidate-files
                              candidate-inputs)
                             plugin-packages)
                docs
                budget)))

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
  [xtdb target {:keys [project-id map-path map-overlay snippet-chars read-context]
                :or {snippet-chars default-snippet-chars}}]
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        attachments (graph-map/docs-for-target overlay target)
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
  [xtdb {:keys [project-id map-path map-overlay read-context]}]
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
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
