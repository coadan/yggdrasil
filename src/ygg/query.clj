(ns ygg.query
  "Graph query helpers."
  (:require [ygg.embedding :as embedding]
            [ygg.embedding.openai :as openai]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def default-limit 10)
(def default-retriever :auto)
(def default-semantic-candidates 100)
(def default-lexical-candidates 100)
(def default-kind-candidates 50)
(def default-path-token-candidates 100)
(def default-seed-count 20)
(def default-instrumentation-seed-id-sample-limit 25)
(def lexical-graph-weight 0.25)
(def hybrid-graph-weight 0.20)

(def search-report-schema "ygg.search.report/v1")

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- timed
  [timings k f]
  (let [started (now-ns)
        result (f)]
    [result (assoc timings k (elapsed-ms started))]))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (nil? project-id) (= project-id (:project-id row)))
       (or (nil? repo-id) (= repo-id (:repo-id row)))))

(defn- effective-scope
  [opts]
  (merge (select-keys (:read-context opts) [:project-id :repo-id])
         (select-keys opts [:project-id :repo-id])))

(defn- read-context
  [opts]
  (store/read-context (merge (:read-context opts)
                             (select-keys opts [:valid-at
                                                :known-at
                                                :snapshot-token
                                                :current-time]))))

(defn- filter-scope
  [rows opts]
  (filter #(scope-match? (effective-scope opts) %) rows))

(defn- scope-constraints
  [opts]
  (->> (select-keys (effective-scope opts) [:project-id :repo-id])
       (remove (comp nil? val))
       (into {})))

(defn- scoped-rows
  ([xtdb table opts] (scoped-rows xtdb table opts {}))
  ([xtdb table opts constraints]
   (let [constraints (merge (scope-constraints opts)
                            (->> constraints
                                 (remove (comp nil? val))
                                 (into {})))
         ctx (read-context opts)]
     (filter-scope (store/constrained-rows xtdb table constraints ctx) opts))))

(defn- scoped-row-count
  ([xtdb table opts] (scoped-row-count xtdb table opts {}))
  ([xtdb table opts constraints]
   (store/count-rows xtdb
                     table
                     (merge (scope-constraints opts)
                            (->> constraints
                                 (remove (comp nil? val))
                                 (into {})))
                     (read-context opts))))

(defn- distinct-by
  [f coll]
  (first
   (reduce (fn [[rows seen] row]
             (let [k (f row)]
               (if (contains? seen k)
                 [rows seen]
                 [(conj rows row) (conj seen k)])))
           [[] #{}]
           coll)))

(declare all-nodes all-edges all-system-nodes)

(def ^:private node-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :label
   :kind
   :file-id
   :path
   :ecosystem
   :package-name
   :version-range
   :resolved-version
   :dependency-scope
   :import-names
   :namespace
   :name
   :public?
   :source-line
   :tokens
   :active?
   :run-id])

(def ^:private system-node-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :system-key
   :label
   :kind
   :path
   :path-prefix
   :source
   :candidate-types
   :evidence
   :metrics
   :repo-role
   :aliases
   :active?
   :run-id])

(def ^:private system-edge-row-query-fields
  [:xt/id
   :project-id
   :source-id
   :target-id
   :relation
   :confidence
   :evidence-ids
   :rules
   :active?
   :run-id])

(def ^:private system-evidence-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :system-id
   :file-id
   :path
   :file-kind
   :kind
   :url-context
   :auth-context
   :label
   :normalized-value
   :source-line
   :confidence
   :active?
   :run-id])

(def ^:private path-edge-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :source-id
   :target-id
   :relation
   :active?
   :run-id])

(def ^:private edge-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :source-id
   :target-id
   :relation
   :confidence
   :ecosystem
   :package-name
   :version-range
   :resolved-version
   :dependency-scope
   :import-name
   :import-kind
   :resolution-source
   :source-kind
   :file-id
   :path
   :source-line
   :active?
   :run-id])

(def ^:private path-system-edge-row-query-fields
  [:xt/id
   :project-id
   :source-id
   :target-id
   :relation
   :active?
   :run-id])

(defn- scoped-system-path-edge-row-query-fields
  [opts]
  (cond-> path-system-edge-row-query-fields
    (:repo-id (effective-scope opts)) (conj :repo-id)))

(def ^:private chunk-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :file-id
   :path
   :kind
   :definition-kind
   :label
   :text
   :tokens
   :heading-path
   :content-sha
   :source-line
   :end-line
   :active?
   :run-id])

(defn- rows-by-ids
  [xtdb table ids opts all-fn return-fields]
  (let [ids (distinct-by identity ids)
        id-set (set ids)
        rows (if (store/xtdb-handle? xtdb)
               (store/rows-with-field-values
                xtdb
                {:table table
                 :field :xt/id
                 :values ids
                 :constraints (scope-constraints opts)
                 :return-fields return-fields
                 :read-context (read-context opts)})
               (filter #(contains? id-set (:xt/id %)) (all-fn xtdb opts)))]
    (filter-scope (keep (into {} (map (juxt :xt/id identity)) rows) ids) opts)))

(defn nodes-by-ids
  "Return node rows for concrete node ids within the requested scope."
  [xtdb ids opts]
  (rows-by-ids xtdb (:nodes store/tables) ids opts all-nodes node-row-query-fields))

(defn- rows-by-field-values
  [xtdb table field values opts return-fields]
  (let [values (distinct-by identity values)
        rows (store/rows-with-field-values
              xtdb
              {:table table
               :field field
               :values values
               :constraints (scope-constraints opts)
               :return-fields return-fields
               :read-context (read-context opts)})
        rows-by-value (group-by field (filter-scope rows opts))]
    (mapcat #(get rows-by-value % []) values)))

(defn nodes-by-file-ids
  "Return node rows for concrete file ids within the requested scope."
  [xtdb file-ids opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :file-id
                        file-ids
                        opts
                        node-row-query-fields))

(defn nodes-by-paths
  "Return node rows for concrete file paths within the requested scope."
  [xtdb paths opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :path
                        paths
                        opts
                        node-row-query-fields))

(defn nodes-by-labels
  "Return node rows for concrete labels within the requested scope."
  [xtdb labels opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :label
                        labels
                        opts
                        node-row-query-fields))

(defn nodes-by-namespaces
  "Return node rows for concrete namespaces within the requested scope."
  [xtdb namespaces opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :namespace
                        namespaces
                        opts
                        node-row-query-fields))

(defn nodes-by-names
  "Return node rows for concrete names within the requested scope."
  [xtdb names opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :name
                        names
                        opts
                        node-row-query-fields))

(defn nodes-by-package-names
  "Return node rows for concrete package names within the requested scope."
  [xtdb package-names opts]
  (rows-by-field-values xtdb
                        (:nodes store/tables)
                        :package-name
                        package-names
                        opts
                        node-row-query-fields))

(defn system-nodes-by-ids
  "Return active system node rows for concrete ids within the requested scope."
  [xtdb ids opts]
  (filter :active?
          (rows-by-ids xtdb
                       (:system-nodes store/tables)
                       ids
                       opts
                       all-system-nodes
                       system-node-row-query-fields)))

(defn- first-scoped-row-by-id
  [xtdb table id opts all-fn return-fields]
  (when id
    (first (rows-by-ids xtdb table [id] opts all-fn return-fields))))

(defn all-nodes
  ([xtdb] (all-nodes xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:nodes store/tables) opts)))

(defn active-nodes
  ([xtdb] (active-nodes xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:nodes store/tables) opts {:active? true})))

(defn all-files
  ([xtdb] (all-files xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:files store/tables) opts {:active? true})))

(defn all-edges
  ([xtdb] (all-edges xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:edges store/tables) opts)))

(defn active-edges
  ([xtdb] (active-edges xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:edges store/tables) opts {:active? true})))

(defn edges-by-file-ids
  "Return source graph edge rows for concrete file ids within scope."
  [xtdb file-ids opts]
  (rows-by-field-values xtdb
                        (:edges store/tables)
                        :file-id
                        file-ids
                        opts
                        edge-row-query-fields))

(defn edges-by-paths
  "Return source graph edge rows for concrete file paths within scope."
  [xtdb paths opts]
  (rows-by-field-values xtdb
                        (:edges store/tables)
                        :path
                        paths
                        opts
                        edge-row-query-fields))

(defn all-chunks
  ([xtdb] (all-chunks xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:chunks store/tables) opts)))

(defn chunks-by-ids
  "Return chunk rows for concrete chunk ids within the requested scope."
  [xtdb ids opts]
  (rows-by-ids xtdb
               (:chunks store/tables)
               ids
               opts
               all-chunks
               chunk-row-query-fields))

(defn chunks-by-paths
  "Return chunk rows for concrete file paths within the requested scope."
  [xtdb paths opts]
  (let [paths (distinct-by identity paths)
        rows (store/rows-with-field-values
              xtdb
              {:table (:chunks store/tables)
               :field :path
               :values paths
               :constraints (scope-constraints opts)
               :return-fields chunk-row-query-fields
               :read-context (read-context opts)})
        rows-by-path (group-by :path (filter-scope rows opts))]
    (mapcat #(get rows-by-path % []) paths)))

(defn all-diagnostics
  ([xtdb] (all-diagnostics xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:diagnostics store/tables) opts)))

(defn all-search-docs
  ([xtdb] (all-search-docs xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:search-docs store/tables) opts {:active? true})))

(defn all-embeddings
  ([xtdb] (all-embeddings xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:embeddings store/tables) opts {:active? true})))

(defn display-id
  "Return a readable label for an id when the target row is missing."
  [id]
  (let [id (str id)]
    (or (second (re-matches #".*:node:namespace:(.+)" id))
        (second (re-matches #".*:node:symbol:(.+)" id))
        (second (re-matches #"node:namespace:(.+)" id))
        (second (re-matches #"node:symbol:(.+)" id))
        id)))

(defn all-system-nodes
  ([xtdb] (all-system-nodes xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:system-nodes store/tables) opts {:active? true})))

(defn all-system-edges
  ([xtdb] (all-system-edges xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:system-edges store/tables) opts {:active? true})))

(defn all-system-evidence
  ([xtdb] (all-system-evidence xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:system-evidence store/tables) opts {:active? true})))

(defn- system-evidence-by-field-values
  [xtdb field values opts]
  (let [values (distinct-by identity values)
        value-set (set values)
        rows (if (store/xtdb-handle? xtdb)
               (store/rows-with-field-values
                xtdb
                {:table (:system-evidence store/tables)
                 :field field
                 :values values
                 :constraints (assoc (scope-constraints opts) :active? true)
                 :return-fields system-evidence-row-query-fields
                 :read-context (read-context opts)})
               (filter #(contains? value-set (get % field))
                       (all-system-evidence xtdb opts)))]
    rows))

(defn system-evidence-by-ids
  "Return active system evidence rows for concrete ids within the requested scope."
  [xtdb ids opts]
  (let [ids (distinct-by identity ids)
        rows (system-evidence-by-field-values xtdb :xt/id ids opts)]
    (keep (into {} (map (juxt :xt/id identity)) rows) ids)))

(defn system-evidence-by-system-ids
  "Return active system evidence rows for concrete system ids within scope."
  [xtdb system-ids opts]
  (system-evidence-by-field-values xtdb :system-id system-ids opts))

(defn system-evidence-by-paths
  "Return active system evidence rows for concrete file paths within scope."
  [xtdb paths opts]
  (system-evidence-by-field-values xtdb :path paths opts))

(defn- edges-from-source-ids
  [xtdb ids opts]
  (->> (store/rows-with-field-values
        xtdb
        {:table (:edges store/tables)
         :field :source-id
         :values ids
         :constraints (scope-constraints opts)
         :return-fields path-edge-row-query-fields
         :read-context (read-context opts)})
       (#(filter-scope % opts))
       (distinct-by :xt/id)
       vec))

(defn system-edges-touching-ids
  "Return active system edges whose source or target is in ids.

  Real XTDB handles use two bounded `rel`/`unify` reads through the store
  batched-value helper. Non-XTDB tests keep the same visible row semantics via
  the store fallback boundary."
  [xtdb ids opts]
  (let [constraints (assoc (scope-constraints opts) :active? true)
        ctx (read-context opts)
        rows (concat
              (store/rows-with-field-values
               xtdb
               {:table (:system-edges store/tables)
                :field :source-id
                :values ids
                :constraints constraints
                :return-fields system-edge-row-query-fields
                :read-context ctx})
              (store/rows-with-field-values
               xtdb
               {:table (:system-edges store/tables)
                :field :target-id
                :values ids
                :constraints constraints
                :return-fields system-edge-row-query-fields
                :read-context ctx}))]
    (->> rows
         (filter :active?)
         (#(filter-scope % opts))
         (distinct-by :xt/id)
         vec)))

(defn- system-edges-from-source-ids
  [xtdb ids opts]
  (let [constraints (assoc (scope-constraints opts) :active? true)]
    (->> (store/rows-with-field-values
          xtdb
          {:table (:system-edges store/tables)
           :field :source-id
           :values ids
           :constraints constraints
           :return-fields (scoped-system-path-edge-row-query-fields opts)
           :read-context (read-context opts)})
         (filter :active?)
         (#(filter-scope % opts))
         (distinct-by :xt/id)
         vec)))

(defn- edges-touching-ids
  [xtdb ids opts]
  (vec (store/edge-rows-touching-ids xtdb
                                     ids
                                     (scope-constraints opts)
                                     (read-context opts))))

(defn edges-touching-node-ids
  "Return source graph edges whose source or target is one of the supplied ids."
  [xtdb ids opts]
  (edges-touching-ids xtdb ids opts))

(defn- adjacency-query-plan
  [xtdb seed-ids edges edge-load-ms]
  (let [seed-ids (sort-by str seed-ids)
        seed-count (count seed-ids)
        query-count (if (pos? seed-count) 2 0)]
    {:graph-adjacency-strategy (if (store/xtdb-handle? xtdb)
                                 "xtql-rel-unify"
                                 "fallback-bounded-values")
     :graph-adjacency-ms edge-load-ms
     :graph-adjacency-query-count query-count
     :graph-adjacency-source-query-count (if (pos? seed-count) 1 0)
     :graph-adjacency-target-query-count (if (pos? seed-count) 1 0)
     :graph-adjacency-seed-count seed-count
     :graph-adjacency-seed-id-sample (vec (take default-instrumentation-seed-id-sample-limit
                                                seed-ids))
     :graph-adjacency-seed-ids-truncated? (< default-instrumentation-seed-id-sample-limit
                                             seed-count)
     :graph-adjacency-loaded-rows (count edges)}))

(defn system-neighbor-ids
  "Return ordered distinct system ids adjacent to seed ids."
  [xtdb ids {:keys [relation min-confidence] :as opts}]
  (let [seed-ids (set ids)
        relation (some-> relation keyword)
        min-confidence (some-> min-confidence double)]
    (->> (system-edges-touching-ids xtdb ids opts)
         (filter #(or (nil? relation) (= relation (:relation %))))
         (filter #(or (nil? min-confidence)
                      (<= min-confidence (double (:confidence %)))))
         (mapcat (juxt :source-id :target-id))
         (remove seed-ids)
         (distinct-by identity)
         vec)))

(def ^:private node-substring-fields
  [:label])

(def ^:private system-node-substring-fields
  [:label :system-key])

(defn- substring-candidates
  ([xtdb table fields token opts all-fn]
   (substring-candidates xtdb table fields token opts all-fn {}))
  ([xtdb table fields token opts all-fn constraints]
   (if (store/xtdb-handle? xtdb)
     (filter-scope
      (store/rows-matching-any-token xtdb
                                     table
                                     fields
                                     [token]
                                     (merge (scope-constraints opts)
                                            constraints)
                                     (read-context opts))
      opts)
     (all-fn xtdb opts))))

(defn find-node
  "Find node by exact id, label, namespace, name, or substring."
  ([xtdb value] (find-node xtdb value {}))
  ([xtdb value opts]
   (let [needle (str/lower-case (str value))]
     (or (first-scoped-row-by-id xtdb
                                 (:nodes store/tables)
                                 value
                                 opts
                                 all-nodes
                                 node-row-query-fields)
         (first (scoped-rows xtdb
                             (:nodes store/tables)
                             opts
                             {:label value}))
         (first (scoped-rows xtdb
                             (:nodes store/tables)
                             opts
                             {:kind :namespace
                              :namespace value}))
         (first (scoped-rows xtdb
                             (:nodes store/tables)
                             opts
                             {:kind :namespace
                              :name value}))
         (first (scoped-rows xtdb
                             (:nodes store/tables)
                             opts
                             {:name value}))
         (some #(when (str/includes? (str/lower-case (:label %)) needle) %)
               (substring-candidates xtdb
                                     (:nodes store/tables)
                                     node-substring-fields
                                     needle
                                     opts
                                     all-nodes))))))

(defn find-system-node
  "Find system node by exact id, label, system key, or substring."
  ([xtdb value] (find-system-node xtdb value {}))
  ([xtdb value opts]
   (let [needle (str/lower-case (str value))]
     (or (when-let [exact-id-row (first-scoped-row-by-id xtdb
                                                         (:system-nodes store/tables)
                                                         value
                                                         opts
                                                         all-system-nodes
                                                         system-node-row-query-fields)]
           (when (:active? exact-id-row)
             exact-id-row))
         (first (scoped-rows xtdb
                             (:system-nodes store/tables)
                             opts
                             {:active? true
                              :label value}))
         (first (scoped-rows xtdb
                             (:system-nodes store/tables)
                             opts
                             {:active? true
                              :system-key value}))
         (let [nodes (substring-candidates xtdb
                                           (:system-nodes store/tables)
                                           system-node-substring-fields
                                           needle
                                           opts
                                           all-system-nodes
                                           {:active? true})]
           (or (some #(when (str/includes? (str/lower-case (:label %)) needle) %)
                     nodes)
               (some #(when (str/includes? (str/lower-case (:system-key %)) needle) %)
                     nodes)))))))

(defn deps
  "Return incoming/outgoing dependencies for node."
  ([xtdb value] (deps xtdb value {}))
  ([xtdb value opts]
   (let [node (find-node xtdb value opts)
         id (:xt/id node)
         edges (if id
                 (edges-touching-ids xtdb [id] opts)
                 [])
         endpoint-ids (mapcat (juxt :source-id :target-id) edges)
         nodes-by-id (into {} (map (juxt :xt/id identity)) (nodes-by-ids xtdb
                                                                         endpoint-ids
                                                                         opts))
         with-target #(assoc % :target (get nodes-by-id (:target-id %)))
         with-source #(assoc % :source (get nodes-by-id (:source-id %)))]
     {:node node
      :outgoing (->> edges
                     (filter #(and (= id (:source-id %))
                                   (not= :defines (:relation %))))
                     (map with-target)
                     vec)
      :incoming (->> edges
                     (filter #(and (= id (:target-id %))
                                   (not= :defines (:relation %))))
                     (map with-source)
                     vec)
      :definitions (->> edges
                        (filter #(and (= id (:source-id %))
                                      (= :defines (:relation %))))
                        (map with-target)
                        vec)
      :defined-by (->> edges
                       (filter #(and (= id (:target-id %))
                                     (= :defines (:relation %))))
                       (map with-source)
                       vec)})))

(defn- token-frequencies
  [tokens]
  (frequencies tokens))

(defn- document-frequencies
  [docs]
  (reduce
   (fn [acc doc]
     (reduce #(update %1 %2 (fnil inc 0)) acc (set (:tokens doc))))
   {}
   docs))

(defn- inverse-document-frequencies
  [query-tokens doc-freqs doc-count]
  (into {}
        (map (fn [token]
               (let [df (double (get doc-freqs token 0))]
                 [token (Math/log (+ 1.0
                                     (/ (+ (- doc-count df) 0.5)
                                        (+ df 0.5))))])))
        query-tokens))

(defn- bm25-score
  [query-tokens idf-by-token avgdl doc]
  (let [k1 1.2
        b 0.75
        freqs (token-frequencies (:tokens doc))
        dl (max 1 (count (:tokens doc)))]
    (reduce
     (fn [score token]
       (let [tf (double (get freqs token 0))]
         (if (zero? tf)
           score
           (let [idf (double (get idf-by-token token 0.0))
                 denom (+ tf (* k1 (+ (- 1.0 b) (* b (/ dl avgdl)))))]
             (+ score (* idf (/ (* tf (+ k1 1.0)) denom)))))))
     0.0
     query-tokens)))

(defn- normalize-scores
  [scores]
  (let [max-score (reduce max 0.0 (vals scores))]
    (if (zero? max-score)
      scores
      (update-vals scores #(/ % max-score)))))

(defn- lexical-scores
  [query-tokens docs]
  (let [doc-count (max 1 (count docs))
        doc-freqs (document-frequencies docs)
        idf-by-token (inverse-document-frequencies query-tokens doc-freqs doc-count)
        avgdl (max 1.0
                   (/ (double (reduce + 0 (map #(count (:tokens %)) docs)))
                      doc-count))]
    (->> docs
         (map (fn [doc]
                [(:target-id doc)
                 (if (and (empty? (:tokens doc))
                          (number? (:score doc)))
                   (double (:score doc))
                   (bm25-score query-tokens idf-by-token avgdl doc))]))
         (into {})
         normalize-scores)))

(defn- current-embeddings-by-target
  [docs embeddings provider model]
  (let [current-inputs (into {}
                             (map (juxt :target-id :input-sha))
                             docs)]
    (->> embeddings
         (filter #(and (= provider (:provider %))
                       (= model (:model %))
                       (= (:input-sha %) (get current-inputs (:target-id %)))))
         (map (juxt :target-id identity))
         (into {}))))

(defn- semantic-scores
  [query-vector docs embeddings provider model]
  (let [embeddings-by-target (current-embeddings-by-target docs embeddings provider model)]
    (->> docs
         (keep (fn [doc]
                 (when-let [row (get embeddings-by-target (:target-id doc))]
                   [(:target-id doc)
                    (embedding/cosine01 query-vector (:vector row))])))
         (into {}))))

(defn- top-ids
  [scores n]
  (->> scores
       (filter (comp pos? val))
       (sort-by (comp - val))
       (take n)
       (mapv key)))

(defn- positive-count
  [scores]
  (count (filter (comp pos? val) scores)))

(defn- rows-by-kind
  [rows]
  (->> rows
       (group-by :target-kind)
       (map (fn [[kind rows]]
              [kind (count rows)]))
       (into (sorted-map))))

(defn- top-ids-by-kind
  [scores docs n]
  (->> docs
       (group-by (juxt :target-kind #(or (:kind %) :unknown)))
       vals
       (mapcat (fn [kind-docs]
                 (->> kind-docs
                      (keep (fn [doc]
                              (let [score (double (get scores (:target-id doc) 0.0))]
                                (when (pos? score)
                                  {:target-id (:target-id doc)
                                   :score score
                                   :label (:label doc)
                                   :path (:path doc)}))))
                      (sort-by (juxt (comp - :score) :label :path))
                      (take n)
                      (map :target-id))))
       vec))

(defn- concrete-path?
  [path]
  (and (not (str/blank? path))
       (or (str/includes? path "/")
           (str/includes? path "."))))

(defn- exact-path-mentioned?
  [query path]
  (let [path (str/lower-case (str path))]
    (and (concrete-path? path)
         (str/includes? query path))))

(defn- exact-path-candidate-ids
  [query-text docs]
  (let [query (str/lower-case (str query-text))]
    (->> docs
         (filter #(exact-path-mentioned? query (:path %)))
         (mapv :target-id))))

(defn- matching-query-token-count
  [query-token-set text]
  (let [text-token-set (set (text/tokenize text))]
    (count (filter text-token-set query-token-set))))

(def ^:private path-token-match-key
  ::path-token-matches)

(defn- add-path-token-match-counts
  [query-token-set docs]
  (mapv (fn [doc]
          (assoc doc
                 path-token-match-key
                 (matching-query-token-count query-token-set (:path doc))))
        docs))

(defn- path-token-candidate-ids
  [docs n]
  (->> docs
       (keep (fn [doc]
               (let [matches (long (or (get doc path-token-match-key) 0))]
                 (when (pos? matches)
                   {:target-id (:target-id doc)
                    :matches matches
                    :path (:path doc)
                    :label (:label doc)}))))
       (sort-by (juxt (comp - :matches) :path :label))
       (take n)
       (mapv :target-id)))

(def relation-graph-weights
  {:references 1.0
   :calls 0.9
   :uses 0.8
   :imports 0.6
   :requires 0.6
   :imports-package 0.5
   :declares-module 0.4
   :defines 0.2})

(defn- graph-neighbor-scores
  [edges seed-ids]
  (let [seeds (set seed-ids)]
    (->> edges
         (filter #(or (contains? seeds (:source-id %))
                      (contains? seeds (:target-id %))))
         (mapcat (fn [{:keys [source-id target-id relation]}]
                   (let [score (double (get relation-graph-weights relation 0.25))]
                     (cond-> []
                       (and (contains? seeds source-id)
                            (not (contains? seeds target-id)))
                       (conj [target-id score])

                       (and (contains? seeds target-id)
                            (not (contains? seeds source-id)))
                       (conj [source-id score])))))
         (reduce (fn [scores [id score]]
                   (update scores id #(max (double (or % 0.0)) score)))
                 {}))))

(defn- same-label-node-ids
  [docs seed-ids]
  (let [seed-set (set seed-ids)
        node-ids-by-file-label (->> docs
                                    (filter #(= :node (:target-kind %)))
                                    (group-by (juxt :path :label))
                                    (map (fn [[k rows]]
                                           [k (set (map :target-id rows))]))
                                    (into {}))]
    (->> docs
         (filter #(contains? seed-set (:target-id %)))
         (mapcat #(get node-ids-by-file-label [(:path %) (:label %)]))
         set)))

(defn- exact-match-boost
  [query query-token-set doc]
  (let [path-token-matches (long (or (get doc path-token-match-key) 0))
        label (str/lower-case (:label doc))
        label-token-match? (pos? (matching-query-token-count query-token-set (:label doc)))
        path-token-boost (min 0.15
                              (* 0.05 (double path-token-matches)))]
    (cond
      (exact-path-mentioned? query (:path doc)) 2.0

      (and (not (str/blank? query)) (= query label)) 0.25
      (and (not (str/blank? query)) (str/includes? label query)) 0.15
      label-token-match? (+ 0.05 path-token-boost)
      :else path-token-boost)))

(defn- ranked-candidates
  [{:keys [query-text query-tokens docs lexical semantic neighbor-scores retriever]}]
  (let [query (str/lower-case (str/trim query-text))
        query-token-set (set query-tokens)
        docs (add-path-token-match-counts query-token-set docs)
        docs-by-target (into {} (map (juxt :target-id identity)) docs)
        semantic-candidates (when (not= :lexical retriever)
                              (concat (top-ids semantic default-semantic-candidates)
                                      (top-ids-by-kind semantic
                                                       docs
                                                       default-kind-candidates)))
        lexical-candidates (when (not= :semantic retriever)
                             (concat (top-ids lexical default-lexical-candidates)
                                     (top-ids-by-kind lexical
                                                      docs
                                                      default-kind-candidates)))
        exact-path-candidates (exact-path-candidate-ids query-text docs)
        path-token-candidates (path-token-candidate-ids docs
                                                        default-path-token-candidates)
        candidates (case retriever
                     :semantic (set (concat semantic-candidates
                                            exact-path-candidates
                                            path-token-candidates))
                     :lexical (set (concat lexical-candidates
                                           exact-path-candidates
                                           path-token-candidates
                                           (keys neighbor-scores)))
                     (set (concat semantic-candidates
                                  lexical-candidates
                                  exact-path-candidates
                                  path-token-candidates
                                  (keys neighbor-scores))))]
    {:semantic-candidates (set semantic-candidates)
     :lexical-candidates (set lexical-candidates)
     :exact-path-candidates (set exact-path-candidates)
     :path-token-candidates (set path-token-candidates)
     :candidates (set candidates)
     :ranked (->> candidates
                  (keep docs-by-target)
                  (map (fn [doc]
                         (let [semantic-score (double (get semantic (:target-id doc) 0.0))
                               lexical-score (double (get lexical (:target-id doc) 0.0))
                               graph-score (double (get neighbor-scores (:target-id doc) 0.0))
                               exact-score (exact-match-boost query query-token-set doc)
                               total (+ (case retriever
                                          :semantic semantic-score
                                          :lexical (+ lexical-score
                                                      (* lexical-graph-weight graph-score))
                                          (+ (* 0.70 semantic-score)
                                             (* 0.20 lexical-score)
                                             (* hybrid-graph-weight graph-score)))
                                        exact-score)]
                           (-> doc
                               (dissoc path-token-match-key)
                               (assoc :result-kind (:target-kind doc)
                                      :score total
                                      :score-components {:semantic semantic-score
                                                         :lexical lexical-score
                                                         :graph graph-score
                                                         :exact exact-score}
                                      :reason (cond
                                                (pos? semantic-score) "embedding match"
                                                (pos? lexical-score) "lexical match"
                                                (pos? graph-score) "graph neighbor"
                                                :else "candidate"))))))
                  (filter #(pos? (:score %)))
                  (sort-by (juxt (comp - :score) :label :path))
                  vec)}))

(defn- query-vector
  [embedding-client query-text]
  (first ((:embed-batch embedding-client) [query-text])))

(defn- retriever-mode
  [requested embedding-client]
  (case requested
    :auto (if embedding-client :hybrid :lexical)
    requested))

(defn search-report
  "Return search results and deterministic instrumentation for query-text.

  Persists the query run with instrumentation and returns a report map. Use
  `semantic-query` when only the ranked result rows are needed."
  [xtdb query-text {:keys [limit retriever embedding-client project-id repo-id]
                    :as opts
                    :or {limit default-limit
                         retriever default-retriever}}]
  (let [started (now-ns)
        requested-retriever (keyword retriever)
        retriever (retriever-mode requested-retriever embedding-client)
        scope {:project-id project-id
               :repo-id repo-id
               :read-context (read-context opts)}
        [docs timings] (timed {} :load-search-docs-ms #(vec (all-search-docs xtdb scope)))
        [query-tokens timings] (timed timings :tokenize-ms #(text/tokenize query-text))
        [lexical timings] (timed timings :lexical-score-ms #(lexical-scores query-tokens docs))
        [semantic timings] (if (#{:hybrid :semantic} retriever)
                             (if embedding-client
                               (let [[query-vector timings] (timed timings
                                                                   :query-embedding-ms
                                                                   #(query-vector
                                                                     embedding-client
                                                                     query-text))
                                     [embeddings timings] (timed timings
                                                                 :load-embeddings-ms
                                                                 #(vec (embedding/current-embeddings-for-docs
                                                                        xtdb
                                                                        docs
                                                                        (assoc scope
                                                                               :provider (:provider embedding-client)
                                                                               :model (:model embedding-client)))))]
                                 (timed timings
                                        :semantic-score-ms
                                        #(semantic-scores query-vector
                                                          docs
                                                          embeddings
                                                          (:provider embedding-client)
                                                          (:model embedding-client))))
                               (throw (ex-info "Semantic retrieval requires an embedding client."
                                               {:retriever retriever})))
                             [{} (assoc timings
                                        :query-embedding-ms 0
                                        :load-embeddings-ms 0
                                        :semantic-score-ms 0)])
        [seed-data timings] (timed timings
                                   :seed-selection-ms
                                   #(let [base-seed-ids (set (concat
                                                              (top-ids semantic
                                                                       default-seed-count)
                                                              (top-ids lexical
                                                                       default-seed-count)))
                                          same-label-ids (same-label-node-ids docs
                                                                              base-seed-ids)]
                                      {:base-seed-ids base-seed-ids
                                       :same-label-ids same-label-ids
                                       :seed-ids (set (concat base-seed-ids
                                                              same-label-ids))}))
        [edges timings] (timed timings
                               :load-edges-ms
                               #(edges-touching-ids xtdb (:seed-ids seed-data) scope))
        [neighbor-scores timings] (timed timings
                                         :graph-expansion-ms
                                         #(graph-neighbor-scores edges (:seed-ids seed-data)))
        [ranked-data timings] (timed timings
                                     :rank-ms
                                     #(ranked-candidates {:query-text query-text
                                                          :query-tokens query-tokens
                                                          :docs docs
                                                          :lexical lexical
                                                          :semantic semantic
                                                          :neighbor-scores neighbor-scores
                                                          :retriever retriever
                                                          :limit limit}))
        ranked-all (:ranked ranked-data)
        ranked (vec (take limit ranked-all))
        candidate-docs (keep (into {} (map (juxt :target-id identity)) docs)
                             (:candidates ranked-data))
        instrumentation (merge
                         timings
                         (adjacency-query-plan xtdb
                                               (:seed-ids seed-data)
                                               edges
                                               (:load-edges-ms timings))
                         {:search-total-ms (elapsed-ms started)
                          :search-docs (count docs)
                          :search-docs-by-kind (rows-by-kind docs)
                          :query-tokens (count query-tokens)
                          :lexical-positive (positive-count lexical)
                          :semantic-positive (positive-count semantic)
                          :seed-count (count (:seed-ids seed-data))
                          :same-label-seed-count (count (:same-label-ids seed-data))
                          :graph-edges-loaded (count edges)
                          :neighbor-count (count neighbor-scores)
                          :exact-path-candidates (count (:exact-path-candidates ranked-data))
                          :path-token-candidates (count (:path-token-candidates ranked-data))
                          :candidate-count (count (:candidates ranked-data))
                          :candidates-by-kind (rows-by-kind candidate-docs)
                          :ranked-count (count ranked-all)
                          :returned-count (count ranked)})
        created-at-ms (System/currentTimeMillis)
        query-row {:xt/id (str "query:" (hash/short-hash [query-text created-at-ms]))
                   :query-text query-text
                   :retriever retriever
                   :retriever-requested requested-retriever
                   :provider (:provider embedding-client)
                   :model (:model embedding-client)
                   :project-id project-id
                   :repo-id repo-id
                   :created-at-ms created-at-ms
                   :result-ids (mapv :target-id ranked)
                   :scores (mapv :score ranked)
                   :score-components (mapv :score-components ranked)
                   :instrumentation instrumentation}
        report {:schema search-report-schema
                :query-run-id (:xt/id query-row)
                :query-text query-text
                :retriever-requested requested-retriever
                :retriever-effective retriever
                :project-id project-id
                :repo-id repo-id
                :instrumentation instrumentation
                :results ranked}]
    (store/commit-query-run! xtdb query-row)
    report))

(defn semantic-query
  "Hybrid semantic query over search docs with graph expansion."
  [xtdb query-text opts]
  (:results (search-report xtdb query-text opts)))

(defn openai-query-client
  "Return OpenAI query embedding client for CLI use."
  [model]
  (openai/client {:model (or model openai/default-model)}))

(defn- shortest-directed-path-ids
  [source-id target-id outgoing-edges-for-source-ids]
  (letfn [(targets-by-source [edges]
            (-> (reduce (fn [acc {:keys [source-id target-id]}]
                          (if target-id
                            (update acc source-id (fnil conj []) target-id)
                            acc))
                        {}
                        edges)
                (update-vals #(->> %
                                   (distinct-by identity)
                                   (sort-by str)
                                   vec))))
          (next-frontier-paths [paths targets seen]
            (first
             (reduce
              (fn [[frontier layer-seen] path]
                (reduce
                 (fn [[frontier layer-seen] target-id]
                   (if (or (contains? seen target-id)
                           (contains? layer-seen target-id))
                     [frontier layer-seen]
                     [(conj frontier (conj path target-id))
                      (conj layer-seen target-id)]))
                 [frontier layer-seen]
                 (get targets (peek path) [])))
              [[] #{}]
              paths)))]
    (cond
      (nil? source-id) nil
      (= source-id target-id) [source-id]
      :else
      (loop [frontier [[source-id]]
             seen #{source-id}]
        (when (seq frontier)
          (let [source-ids (mapv peek frontier)
                targets (targets-by-source
                         (outgoing-edges-for-source-ids source-ids))
                next-frontier (next-frontier-paths frontier targets seen)
                found (some #(when (= target-id (peek %)) %) next-frontier)]
            (if found
              found
              (recur next-frontier
                     (into seen (map peek next-frontier))))))))))

(defn- path-rows
  [xtdb path-ids opts nodes-by-id]
  (let [missing-ids (remove (set (keys nodes-by-id)) path-ids)
        nodes-by-id (into nodes-by-id
                          (map (juxt :xt/id identity))
                          (nodes-by-ids xtdb missing-ids opts))]
    (mapv #(get nodes-by-id %) path-ids)))

(defn graph-path
  "Return shortest directed path between two node queries."
  ([xtdb source-value target-value] (graph-path xtdb source-value target-value {}))
  ([xtdb source-value target-value opts]
   (let [source (find-node xtdb source-value opts)
         target (find-node xtdb target-value opts)
         target-id (:xt/id target)]
     (when (and source target)
       (when-let [path-ids (shortest-directed-path-ids
                            (:xt/id source)
                            target-id
                            #(edges-from-source-ids xtdb % opts))]
         (path-rows xtdb
                    path-ids
                    opts
                    {(:xt/id source) source
                     target-id target}))))))

(defn system-path
  "Return shortest directed path between two system queries."
  ([xtdb source-value target-value] (system-path xtdb source-value target-value {}))
  ([xtdb source-value target-value opts]
   (let [source (find-system-node xtdb source-value opts)
         target (find-system-node xtdb target-value opts)
         target-id (:xt/id target)]
     (when (and source target)
       (when-let [path-ids (shortest-directed-path-ids
                            (:xt/id source)
                            target-id
                            #(system-edges-from-source-ids xtdb % opts))]
         (let [known-nodes {(:xt/id source) source
                            target-id target}
               missing-ids (remove (set (keys known-nodes)) path-ids)
               nodes-by-id (into known-nodes
                                 (map (juxt :xt/id identity))
                                 (system-nodes-by-ids xtdb missing-ids opts))]
           (mapv #(get nodes-by-id %) path-ids)))))))

(defn report
  "Return aggregate report data."
  ([xtdb] (report xtdb {}))
  ([xtdb opts]
   (let [node-count (scoped-row-count xtdb (:nodes store/tables) opts)
         edge-count (scoped-row-count xtdb (:edges store/tables) opts)
         chunk-count (scoped-row-count xtdb (:chunks store/tables) opts)
         search-doc-count (scoped-row-count xtdb
                                            (:search-docs store/tables)
                                            opts
                                            {:active? true})
         embedding-count (scoped-row-count xtdb
                                           (:embeddings store/tables)
                                           opts
                                           {:active? true})
         diagnostics (all-diagnostics xtdb opts)
         degree-counts (store/row-counts-by-any-field
                        xtdb
                        (:edges store/tables)
                        [:source-id :target-id]
                        (scope-constraints opts)
                        (read-context opts))
         top-degree-counts (vec (take 10 degree-counts))
         top-node-ids (mapv :value top-degree-counts)
         nodes-by-id (into {}
                           (map (juxt :xt/id identity))
                           (nodes-by-ids xtdb top-node-ids opts))
         top-nodes (mapv (fn [{:keys [value count]}]
                           {:node (get nodes-by-id value
                                       {:xt/id value
                                        :label (display-id value)})
                            :degree count})
                         top-degree-counts)]
     {:counts {:nodes node-count
               :edges edge-count
               :chunks chunk-count
               :search-docs search-doc-count
               :embeddings embedding-count
               :diagnostics (count diagnostics)}
      :top-nodes top-nodes
      :diagnostics diagnostics})))
