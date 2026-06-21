(ns agraph.context
  "Token-bounded, graph-grounded context packets for agents."
  (:require [agraph.activity :as activity]
            [agraph.audit-scope :as audit-scope]
            [agraph.command :as command]
            [agraph.context-architecture :as context-architecture]
            [agraph.context-budget :as context-budget]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.plugin-package-view :as plugin-package-view]
            [agraph.query :as query]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "agraph.context/v1")

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

(defn- entity-score
  [query-tokens results node]
  (let [result-score (->> results
                          (filter #(result-matches-node? % node))
                          (map :score)
                          (reduce max 0.0))
        lexical-score (token-score query-tokens
                                   (compact (:label node)
                                            (:kind node)
                                            (:repo node)
                                            (:path node)
                                            (:pathPrefix node)
                                            (pr-str (:attrs node))
                                            (str/join " " (:tags node))
                                            (pr-str (:metrics node))))]
    (+ result-score (* 0.25 lexical-score))))

(defn- select-entities
  [query-tokens results graph-data limit]
  (->> (:nodes graph-data)
       (remove #(= "repo" (:kind %)))
       (map #(assoc % :context-score (entity-score query-tokens results %)))
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
                        :why (if (some #(result-matches-node? % node) results)
                               "retrieval and graph match"
                               "graph label match")}
                 (:attrs node) (assoc :attrs (:attrs node))
                 (seq (:tags node)) (assoc :tags (:tags node))
                 (seq (:candidateTypes node)) (assoc :candidateTypes (:candidateTypes node))
                 (seq (:candidateEvidence node)) (assoc :candidateEvidence
                                                        (:candidateEvidence node))
                 (:metrics node) (assoc :metrics (:metrics node)))))))

(defn- edge-score
  [query-tokens selected-ids edge]
  (+ (cond
       (and (contains? selected-ids (:source edge))
            (contains? selected-ids (:target edge))) 1.0
       (or (contains? selected-ids (:source edge))
           (contains? selected-ids (:target edge))) 0.5
       :else 0.0)
     (* 0.15 (token-score query-tokens (:relation edge)))))

(defn- select-edges
  [query-tokens entities graph-data limit]
  (let [selected-ids (set (map :id entities))]
    (->> (:edges graph-data)
         (map #(assoc % :context-score (edge-score query-tokens selected-ids %)))
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

(defn- result-score-for-chunk
  [result-scores result-scores-by-path-label chunk]
  (or (get result-scores (:xt/id chunk))
      (get result-scores-by-path-label [(:path chunk) (:label chunk)])))

(defn- result-by-target
  [results]
  (into {} (map (juxt :target-id identity)) results))

(defn- chunk-score
  [query-tokens selected-labels result-scores result-scores-by-path-label chunk]
  (+ (double (or (result-score-for-chunk result-scores
                                         result-scores-by-path-label
                                         chunk)
                 0.0))
     (* 0.35 (capped-token-score query-tokens
                                 (compact (:label chunk) (:path chunk) (:text chunk))))
     (* 0.15 (min 1.0
                  (text/token-score (text/tokenize (str/join " " selected-labels))
                                    (:tokens chunk))))))

(defn- inferred-docs
  [query-tokens results chunks entities snippet-chars]
  (let [result-scores (result-score-by-target results)
        result-scores-by-path-label (result-score-by-path-label results)
        results-by-target (result-by-target results)
        selected-labels (map :label entities)]
    (->> chunks
         (filter #(or (= :markdown (:kind %))
                      (result-score-for-chunk result-scores
                                              result-scores-by-path-label
                                              %)))
         (map #(assoc % :context-score (chunk-score query-tokens
                                                    selected-labels
                                                    result-scores
                                                    result-scores-by-path-label
                                                    %)
                      :retrieved? (boolean (result-score-for-chunk
                                            result-scores
                                            result-scores-by-path-label
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

(defn- doc-definition-kind-key
  [doc]
  (let [source (:source doc)]
    [(or (:repo source) :unknown-repo)
     (get-in doc [:source :definitionKind])]))

(defn- doc-novelty-score
  [seen-paths seen-definition-kinds doc]
  (+ (if (contains? seen-paths (doc-path-key doc)) 0 2)
     (if (or (nil? (second (doc-definition-kind-key doc)))
             (contains? seen-definition-kinds (doc-definition-kind-key doc)))
       0
       1)))

(defn- diversify-docs
  [docs]
  (loop [remaining (vec docs)
         seen-paths #{}
         seen-definition-kinds #{}
         out []]
    (if (empty? remaining)
      out
      (let [[idx doc] (->> remaining
                           (map-indexed (fn [idx doc]
                                          [idx
                                           doc
                                           (doc-novelty-score seen-paths
                                                              seen-definition-kinds
                                                              doc)]))
                           (sort-by (juxt (comp doc-priority second)
                                          (comp - #(nth % 2))
                                          first))
                           first)
            definition-key (doc-definition-kind-key doc)]
        (recur (vec (concat (subvec remaining 0 idx)
                            (subvec remaining (inc idx))))
               (conj seen-paths (doc-path-key doc))
               (cond-> seen-definition-kinds
                 (second definition-key) (conj definition-key))
               (conj out doc))))))

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

(defn- select-docs
  [docs results doc-limit]
  (let [doc-limit (long doc-limit)
        docs (->> docs
                  distinct-docs
                  (sort-by (juxt doc-priority
                                 (comp - :score)
                                 :role
                                 #(get-in % [:source :path])))
                  diversify-docs)
        result-paths (ranked-result-paths results
                                          (or (ranked-path-coverage-limit doc-limit)
                                              0))]
    (-> docs
        (ensure-ranked-path-docs result-paths doc-limit)
        vec)))

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

(defn- context-chunks
  [xtdb results attachments opts]
  (let [scope (select-keys opts [:project-id :repo-id :read-context])
        by-id (query/chunks-by-ids xtdb (result-chunk-ids results) scope)
        paths (distinct (concat (result-paths results)
                                (attachment-paths attachments)))
        by-path (query/chunks-by-paths xtdb paths scope)]
    (->> (concat by-id by-path)
         (reduce (fn [by-id chunk]
                   (assoc by-id (:xt/id chunk) chunk))
                 {})
         vals
         vec)))

(defn- graph-summary
  [graph-data]
  {:basis (:basis graph-data)
   :counts {:nodes (count (:nodes graph-data))
            :edges (count (:edges graph-data))
            :clusters (count (:clusters graph-data))}
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

(defn- relationship-groups
  [edges]
  (context-architecture/relationship-groups edges))

(defn- selected-accepted-systems
  [overlay entities selected-sources]
  (context-architecture/selected-accepted-systems overlay entities selected-sources))

(defn- blast-radius
  [entities edges]
  (context-architecture/blast-radius entities edges))

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
  [query-text budget graph-data entities edges activity warnings drilldowns answerability
   search-instrumentation freshness source-coverage architecture systems audit-scopes
   relationships blast-radius candidate-files plugin-packages]
  (cond-> {:schema schema
           :query query-text
           :graph (graph-summary graph-data)
           :budget {:requested budget}
           :entities entities
           :edges edges
           :activity activity
           :candidateFiles candidate-files
           :docs []
           :warnings warnings
           :drilldowns drilldowns}
    answerability (assoc :answerability answerability)
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
    (and path (graph-map/file-exists? path)) (graph-map/read-map path)
    :else (graph-map/empty-map project-id)))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (str/blank? (str project-id)) (= project-id (:project-id row)))
       (or (str/blank? (str repo-id)) (= repo-id (:repo-id row)))))

(defn- scoped-active-count
  [xtdb table {:keys [project-id repo-id read-context]}]
  (->> (store/all-rows xtdb table (store/read-context read-context))
       (filter active-row?)
       (filter #(scope-match? {:project-id project-id :repo-id repo-id} %))
       count))

(defn- overlay-counts
  [overlay]
  {:map-systems (count (:systems overlay))
   :map-docs (count (:docs overlay))
   :map-edges (count (:edges overlay))
   :map-rejects (count (:reject overlay))})

(defn- capability-counts
  [xtdb overlay {:keys [project-id repo-id read-context]}]
  (let [nodes (filter active-row?
                      (query/all-nodes xtdb {:project-id project-id
                                             :repo-id repo-id
                                             :read-context read-context}))
        edges (filter active-row?
                      (query/all-edges xtdb {:project-id project-id
                                             :repo-id repo-id
                                             :read-context read-context}))
        package-report (dependency/package-report xtdb
                                                  {:project-id project-id
                                                   :repo-id repo-id}
                                                  {:limit 0
                                                   :map-overlay overlay})
        package-counts (:counts package-report)
        activity-items (activity/all-items xtdb {:project-id project-id
                                                 :read-context read-context})
        activity-events (activity/all-events xtdb {:project-id project-id
                                                   :read-context read-context})]
    (merge
     {:files (scoped-active-count xtdb (:files store/tables)
                                  {:project-id project-id
                                   :repo-id repo-id
                                   :read-context read-context})
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
      :system-evidence (count (query/all-system-evidence xtdb
                                                         {:project-id project-id
                                                          :repo-id repo-id
                                                          :read-context read-context}))
      :chunks (count (filter active-row?
                             (query/all-chunks xtdb {:project-id project-id
                                                     :repo-id repo-id
                                                     :read-context read-context})))
      :search-docs (count (query/all-search-docs xtdb {:project-id project-id
                                                       :repo-id repo-id
                                                       :read-context read-context}))
      :embeddings (count (query/all-embeddings xtdb {:project-id project-id
                                                     :repo-id repo-id
                                                     :read-context read-context}))
      :system-nodes (count (query/all-system-nodes xtdb {:project-id project-id
                                                         :read-context read-context}))
      :system-edges (count (query/all-system-edges xtdb {:project-id project-id
                                                         :read-context read-context}))
      :activity-items (count activity-items)
      :activity-events (count activity-events)
      :validation-events (count (filter #(= :validation (:event-kind %))
                                        activity-events))
      :result-schema-mismatch-events
      (count (filter #(= :result-schema-mismatch (:event-kind %))
                     activity-events))
      :diagnostics (count (filter active-row?
                                  (query/all-diagnostics xtdb {:project-id project-id
                                                               :repo-id repo-id
                                                               :read-context read-context})))}
     (activity/result-schema-counts activity-items)
     (overlay-counts overlay))))

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

(defn- answerability-warnings
  ([counts retrieval weak] (answerability-warnings counts retrieval weak nil))
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
  (str "agraph packages --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- project-command
  [command-name project-id & args]
  (str "agraph " command-name " --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- explore-command
  [query-text project-id]
  (str "agraph explore "
       (command/shell-token query-text)
       " --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- docs-audit-command
  [project-id map-path]
  (str "agraph sync docs audit --project "
       (command/shell-token (or project-id "<project-id>"))
       " --map "
       (command/shell-token map-path)))

(defn- inspect-command
  [map-path]
  (apply command/command
         (concat ["agraph" "sync" "inspect" "<project.edn>"]
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
                      "agraph_explore"
                      (cond-> {:query query-text}
                        project-id (assoc :projectId project-id)
                        map-path (assoc :mapPath map-path)))
           (drilldown :systems
                      "Inspect project systems graph"
                      (project-command "view systems" project-id)
                      "agraph_systems"
                      (cond-> {}
                        project-id (assoc :projectId project-id)
                        map-path (assoc :mapPath map-path)))
           (drilldown :status
                      "Inspect graph freshness and evidence status"
                      (inspect-command map-path)
                      "agraph_status"
                      (cond-> {}
                        map-path (assoc :mapPath map-path)))]
    map-path (conj (drilldown :docs
                              "Audit accepted documentation attachments"
                              (docs-audit-command project-id map-path)
                              nil
                              nil))))

(defn- sync-command
  [& args]
  (str "agraph sync <project.edn>"
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
  (cond-> {:mcpTool "agraph_status"}
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
                        :command (command/command "agraph" "sync" "coverage" "<project.edn>" "--json")}
                       (status-mcp map-path)))

          (pos? (:skipped-files counts 0))
          (conj (merge {:kind :coverage
                        :label "Inspect skipped source candidates"
                        :count (:skipped-files counts 0)
                        :command (command/command "agraph" "sync" "coverage" "<project.edn>" "--json")
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
                 :command "agraph embed --provider openrouter"})

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
                 :mcpTool "agraph_sync_activity"
                 :command (command/command "agraph" "sync" "activity" "<project.edn>" "--json")})

          (pos? (+ (:result-schema-missing-result-items counts 0)
                   (:result-schema-unexpected-result-items counts 0)))
          (conj {:kind :activity
                 :label "Inspect result schema status activity"
                 :count (+ (:result-schema-missing-result-items counts 0)
                           (:result-schema-unexpected-result-items counts 0))
                 :mcpTool "agraph_sync_activity"
                 :command (command/command "agraph" "sync" "activity" "<project.edn>" "--json")})

          (zero? (+ (:system-nodes counts) (:system-edges counts)))
          (conj {:kind :system-graph
                 :label "Index project graph systems"
                 :command (sync-command)})

          (zero? (+ (:activity-items counts) (:activity-events counts)))
          (conj {:kind :activity
                 :label "Import local activity and work rows"
                 :mcpTool "agraph_sync_activity"
                 :command (command/command "agraph" "sync" "activity" "<project.edn>")}))
        (distinct-by action-distinct-key)
        (sort-by action-priority)
        (take 5)
        vec)))

(defn- next-steps
  [actions]
  (mapv #(str "Run " (:command %)) actions))

(defn- answerability-status
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

(defn- answerability
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
    {:status (answerability-status missing weak retrieval match-counts freshness)
     :available available
     :missing missing
     :weak weak
     :unsupported unsupported-planes
     :planes planes
     :counts counts
     :retrieval retrieval
     :warnings (answerability-warnings counts retrieval weak freshness)
     :next (next-steps actions)
     :nextActions actions}))

(defn context-packet
  "Return compact graph/doc context for an agent query."
  [xtdb query-text {:keys [budget entity-limit edge-limit doc-limit snippet-chars
                           retrieval-limit
                           retriever embedding-client project-id repo-id map-path
                           map-overlay min-confidence read-context freshness plugin-packages]
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
                               {:project-id project-id
                                :repo-id repo-id
                                :read-context read-context})
        activity (activity/select-activity xtdb
                                           query-text
                                           {:project-id project-id
                                            :read-context read-context
                                            :target-ids targets})
        system-evidence (query/all-system-evidence xtdb
                                                   {:project-id project-id
                                                    :repo-id repo-id
                                                    :read-context read-context})
        dependency-report (dependency/package-report xtdb
                                                     {:project-id project-id
                                                      :repo-id repo-id}
                                                     {:map-overlay overlay})
        selected-system-ids (concat (map :id entities)
                                    (map :id accepted-systems))
        runtime-evidence (select-system-evidence query-tokens
                                                 selected-system-ids
                                                 results
                                                 system-evidence
                                                 12)
        warnings (cond-> []
                   (empty? entities)
                   (conj "no graph entities matched the query"))
        drilldowns (context-drilldowns query-text project-id map-path)
        docs (->> (concat (attached-docs overlay chunks snippet-chars targets)
                          (inferred-docs query-tokens results chunks entities snippet-chars))
                  (#(select-docs % results doc-limit)))
        search-context (-> (select-keys search-report
                                        [:schema
                                         :query-run-id
                                         :retriever-requested
                                         :retriever-effective
                                         :instrumentation])
                           (update :instrumentation assoc
                                   :context-chunks (count chunks)))
        answerability (answerability xtdb
                                     overlay
                                     {:project-id project-id
                                      :repo-id repo-id
                                      :read-context read-context
                                      :retriever retriever
                                      :embedding-client embedding-client
                                      :freshness freshness}
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
                                            :edges edges
                                            :accepted-systems accepted-systems
                                            :query-tokens query-tokens
                                            :runtime-evidence runtime-evidence
                                            :dependency-report dependency-report
                                            :docs docs
                                            :activity activity
                                            :answerability answerability
                                            :freshness freshness})
        systems (systems-section architecture)
        audit-scopes (audit-scope/selected-summaries
                      {:boundary-evidence (:boundaryEvidence architecture)
                       :runtime-evidence (:runtimeEvidence architecture)
                       :dependency-evidence (:dependencyEvidence architecture)
                       :rejected-corrections (:rejectedCorrections architecture)
                       :docs (:docs architecture)})
        blast-radius (blast-radius entities edges)]
    (fit-budget (base-packet query-text
                             budget
                             graph-data
                             entities
                             edges
                             activity
                             warnings
                             drilldowns
                             answerability
                             search-context
                             freshness
                             source-coverage
                             architecture
                             systems
                             audit-scopes
                             (relationship-groups edges)
                             blast-radius
                             (candidate-files results)
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
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))]
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
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))]
    {:schema "agraph.docs/v1"
     :target target
     :docs (mapv #(attachment->doc chunks snippet-chars %)
                 (graph-map/docs-for-target overlay target))}))

(defn docs-audit
  "Return maintenance findings for doc attachments and mapped systems."
  [xtdb {:keys [project-id map-path map-overlay read-context]}]
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))
        systems (:systems overlay)
        docs (:docs overlay)
        docs-by-target (group-by :target docs)
        unresolved (->> docs
                        (map #(attachment->doc chunks default-snippet-chars %))
                        (filter #(= "stale" (:status %)))
                        (mapv #(dissoc % :snippet)))
        undocumented (->> systems
                          (filter #(= "accepted" (s (:status %))))
                          (remove #(seq (get docs-by-target (:id %))))
                          (mapv #(select-keys % [:id :label :kind])))]
    {:schema "agraph.docs.audit/v1"
     :project project-id
     :counts {:attachments (count docs)
              :unresolved (count unresolved)
              :undocumentedSystems (count undocumented)}
     :unresolved unresolved
     :undocumentedSystems undocumented}))
