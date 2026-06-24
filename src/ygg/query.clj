(ns ygg.query
  "Graph query helpers."
  (:require [ygg.embedding :as embedding]
            [ygg.hash :as hash]
            [ygg.ripgrep :as ripgrep]
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
(def default-same-label-doc-label-fanout-limit 32)
(def default-instrumentation-seed-id-sample-limit 25)
(def default-grep-patterns 4)
(def default-grep-timeout-ms 1500)
(def default-grep-max-stdout-bytes 200000)
(def lexical-graph-weight 0.25)
(def hybrid-graph-weight 0.20)
(def lexical-same-label-weight 0.25)
(def hybrid-same-label-weight 0.20)
(def lexical-grep-weight 0.0)
(def hybrid-grep-weight 0.0)

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
  (let [scope (effective-scope opts)]
    (filter #(scope-match? scope %) rows)))

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
  (loop [remaining (seq coll)
         seen #{}
         out []]
    (if-let [row (first remaining)]
      (let [k (f row)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out row))))
      out)))

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

(defn- scoped-rows-by-id
  [rows opts]
  (let [scope (effective-scope opts)]
    (reduce
     (fn [rows-by-id row]
       (if (scope-match? scope row)
         (assoc rows-by-id (:xt/id row) row)
         rows-by-id))
     {}
     rows)))

(defn- rows-by-ids
  [xtdb table ids opts all-fn return-fields]
  (let [ids (distinct-by identity ids)
        id-set (set ids)]
    (if (empty? ids)
      []
      (let [rows (if (store/xtdb-handle? xtdb)
                   (store/rows-with-field-values
                    xtdb
                    {:table table
                     :field :xt/id
                     :values ids
                     :constraints (scope-constraints opts)
                     :return-fields return-fields
                     :read-context (read-context opts)})
                   (filter #(contains? id-set (:xt/id %)) (all-fn xtdb opts)))]
        (keep (scoped-rows-by-id rows opts) ids)))))

(defn nodes-by-ids
  "Return node rows for concrete node ids within the requested scope."
  [xtdb ids opts]
  (rows-by-ids xtdb (:nodes store/tables) ids opts all-nodes node-row-query-fields))

(defn- scoped-rows-by-field
  [rows field opts]
  (let [scope (effective-scope opts)]
    (reduce
     (fn [rows-by-value row]
       (if (scope-match? scope row)
         (update rows-by-value (get row field) (fnil conj []) row)
         rows-by-value))
     {}
     rows)))

(defn- rows-for-values
  [rows-by-value values]
  (reduce
   (fn [rows value]
     (into rows (get rows-by-value value [])))
   []
   values))

(defn- rows-by-field-values
  [xtdb table field values opts return-fields]
  (let [values (distinct-by identity values)]
    (if (empty? values)
      []
      (let [rows (store/rows-with-field-values
                  xtdb
                  {:table table
                   :field field
                   :values values
                   :constraints (scope-constraints opts)
                   :return-fields return-fields
                   :read-context (read-context opts)})
            rows-by-value (scoped-rows-by-field rows field opts)]
        (rows-for-values rows-by-value values)))))

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

(defn nodes-by-path-prefixes
  "Return active node rows whose paths equal or sit under one of the prefixes."
  [xtdb prefixes opts]
  (let [rows (store/rows-with-string-prefixes
              xtdb
              (:nodes store/tables)
              :path
              prefixes
              (assoc (scope-constraints opts) :active? true)
              (read-context opts)
              node-row-query-fields)]
    (filter-scope rows opts)))

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

(defn all-repos
  ([xtdb] (all-repos xtdb {}))
  ([xtdb opts]
   (scoped-rows xtdb (:repos store/tables) opts {:active? true})))

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
  (rows-by-field-values xtdb
                        (:chunks store/tables)
                        :path
                        paths
                        opts
                        chunk-row-query-fields))

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
        value-set (set values)]
    (if (empty? values)
      []
      (if (store/xtdb-handle? xtdb)
        (store/rows-with-field-values
         xtdb
         {:table (:system-evidence store/tables)
          :field field
          :values values
          :constraints (assoc (scope-constraints opts) :active? true)
          :return-fields system-evidence-row-query-fields
          :read-context (read-context opts)})
        (filter #(contains? value-set (get % field))
                (all-system-evidence xtdb opts))))))

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
  (if (empty? ids)
    []
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
         vec)))

(defn system-edges-touching-ids
  "Return active system edges whose source or target is in ids.

  Real XTDB handles use two bounded `rel`/`unify` reads through the store
  batched-value helper. Non-XTDB tests keep the same visible row semantics via
  the store fallback boundary."
  [xtdb ids opts]
  (if (empty? ids)
    []
    (let [constraints (assoc (scope-constraints opts) :active? true)
          ctx (read-context opts)
          min-confidence (some-> (:min-confidence opts) double)
          confidence-filter (when (some? min-confidence)
                              {:min-field :confidence
                               :min-value min-confidence})
          rows (concat
                (store/rows-with-field-values
                 xtdb
                 (merge {:table (:system-edges store/tables)
                         :field :source-id
                         :values ids
                         :constraints constraints
                         :return-fields system-edge-row-query-fields
                         :read-context ctx}
                        confidence-filter))
                (store/rows-with-field-values
                 xtdb
                 (merge {:table (:system-edges store/tables)
                         :field :target-id
                         :values ids
                         :constraints constraints
                         :return-fields system-edge-row-query-fields
                         :read-context ctx}
                        confidence-filter)))]
      (->> rows
           (filter :active?)
           (#(filter-scope % opts))
           (distinct-by :xt/id)
           vec))))

(defn- system-edges-from-source-ids
  [xtdb ids opts]
  (if (empty? ids)
    []
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
           vec))))

(defn- edges-touching-ids
  [xtdb ids opts]
  (if (empty? ids)
    []
    (vec (store/edge-rows-touching-ids xtdb
                                       ids
                                       (scope-constraints opts)
                                       (read-context opts)))))

(defn edges-touching-node-ids
  "Return source graph edges whose source or target is one of the supplied ids."
  [xtdb ids opts]
  (edges-touching-ids xtdb ids opts))

(defn- bounded-seed-id-sample
  [sample sample-limit sample-entry]
  (if (pos? sample-limit)
    (let [sample (conj sample sample-entry)]
      (if (<= (count sample) sample-limit)
        sample
        (vec (take sample-limit (sort sample)))))
    []))

(defn- sample-seed-ids
  [seed-ids sample-limit]
  (let [[seed-count sample]
        (reduce (fn [[seed-count sample] seed-id]
                  [(inc seed-count)
                   (bounded-seed-id-sample sample
                                           sample-limit
                                           [(str seed-id) seed-count seed-id])])
                [0 []]
                seed-ids)]
    {:seed-count seed-count
     :seed-id-sample (mapv #(nth % 2) (sort sample))}))

(defn- adjacency-query-plan
  [xtdb seed-ids edges edge-load-ms]
  (let [sample-limit default-instrumentation-seed-id-sample-limit
        {:keys [seed-count seed-id-sample]} (sample-seed-ids seed-ids
                                                             sample-limit)
        query-count (if (pos? seed-count) 2 0)]
    {:graph-adjacency-strategy (if (store/xtdb-handle? xtdb)
                                 "xtql-rel-unify"
                                 "fallback-bounded-values")
     :graph-adjacency-ms edge-load-ms
     :graph-adjacency-query-count query-count
     :graph-adjacency-source-query-count (if (pos? seed-count) 1 0)
     :graph-adjacency-target-query-count (if (pos? seed-count) 1 0)
     :graph-adjacency-seed-count seed-count
     :graph-adjacency-seed-id-sample seed-id-sample
     :graph-adjacency-seed-ids-truncated? (< sample-limit seed-count)
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
  ([xtdb table fields token opts all-fn return-fields]
   (substring-candidates xtdb table fields token opts all-fn {} return-fields))
  ([xtdb table fields token opts all-fn constraints return-fields]
   (if (store/xtdb-handle? xtdb)
     (filter-scope
      (store/rows-matching-any-token xtdb
                                     table
                                     fields
                                     [token]
                                     (merge (scope-constraints opts)
                                            constraints)
                                     (read-context opts)
                                     return-fields)
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
                                     all-nodes
                                     node-row-query-fields))))))

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
                                           {:active? true}
                                           system-node-row-query-fields)]
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
  [query-token-set tokens]
  (if (empty? query-token-set)
    {}
    (reduce (fn [freqs token]
              (if (contains? query-token-set token)
                (update freqs token (fnil inc 0))
                freqs))
            {}
            tokens)))

(defn- lexical-score-input
  [query-token-set docs]
  (reduce
   (fn [state doc]
     (let [tokens (:tokens doc)
           token-count (count tokens)
           query-token-frequencies (token-frequencies query-token-set tokens)
           scored-doc? (or (seq query-token-frequencies)
                           (and (zero? token-count)
                                (number? (:score doc))))
           state (update state :token-total + token-count)]
       (cond-> state
         scored-doc?
         (update :doc-stats conj {:doc doc
                                  :token-count token-count
                                  :query-token-frequencies query-token-frequencies})

         (seq query-token-frequencies)
         (update :doc-freqs
                 #(reduce (fn [doc-freqs token]
                            (update doc-freqs token (fnil inc 0)))
                          %
                          (keys query-token-frequencies))))))
   {:token-total 0
    :doc-freqs {}
    :doc-stats []}
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
  [query-tokens idf-by-token avgdl token-count query-token-frequencies]
  (let [k1 1.2
        b 0.75
        dl (max 1 token-count)]
    (reduce
     (fn [score token]
       (let [tf (double (get query-token-frequencies token 0))]
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

(defn- normalize-repo-roots
  [repo-roots]
  (cond
    (map? repo-roots)
    (->> repo-roots
         (keep (fn [[repo-id root]]
                 (when-not (str/blank? (str root))
                   [(str repo-id) (str root)])))
         (into {}))

    (sequential? repo-roots)
    (->> repo-roots
         (keep (fn [{:keys [id repo-id root]}]
                 (when (and (or id repo-id)
                            (not (str/blank? (str root))))
                   [(str (or repo-id id)) (str root)])))
         (into {}))

    :else
    {}))

(defn- stored-repo-roots
  [xtdb scope]
  (->> (all-repos xtdb scope)
       (keep (fn [{:keys [repo-id root]}]
               (when (and (not (str/blank? (str repo-id)))
                          (not (str/blank? (str root))))
                 [(str repo-id) (str root)])))
       (into {})))

(defn- repo-root-data
  [xtdb scope opts]
  (let [explicit-roots (normalize-repo-roots (:repo-roots opts))]
    (try
      {:roots (merge (stored-repo-roots xtdb scope) explicit-roots)
       :diagnostics []}
      (catch Exception e
        {:roots explicit-roots
         :diagnostics [{:kind :repo-root-read-failed
                        :message (.getMessage e)}]}))))

(defn- grep-patterns
  [query-tokens opts]
  (let [limit (long (or (:grep-pattern-limit opts) default-grep-patterns))]
    (->> query-tokens
         (remove #(str/blank? (str %)))
         (filter #(<= 2 (count (str %))))
         distinct
         (take limit)
         vec)))

(defn- grep-repo-ids
  [docs repo-roots repo-id]
  (let [doc-repos (set (keep :repo-id docs))
        root-repos (set (keys repo-roots))
        candidate-repos (cond
                          (not (str/blank? (str repo-id))) [(str repo-id)]
                          (seq doc-repos) (map str doc-repos)
                          :else (seq root-repos))]
    (->> candidate-repos
         distinct
         (filter #(contains? repo-roots %))
         sort
         vec)))

(defn- grep-search-opts
  [opts]
  (cond-> {:timeout-ms (or (:grep-timeout-ms opts) default-grep-timeout-ms)
           :max-stdout-bytes (or (:grep-max-stdout-bytes opts)
                                 default-grep-max-stdout-bytes)
           :max-stderr-bytes 20000
           :hidden? false
           :ignore-case? true}
    (:grep-bin opts)
    (assoc :bin (:grep-bin opts))))

(defn- grep-search-results
  [repo-roots repo-ids patterns opts]
  (let [search-opts (grep-search-opts opts)]
    (vec
     (for [repo-id repo-ids
           :let [root (get repo-roots repo-id)]]
       (assoc (ripgrep/search-counts-many root
                                          patterns
                                          []
                                          search-opts)
              :repo-id repo-id)))))

(defn- grep-match-counts
  [search-results]
  (reduce
   (fn [counts {:keys [repo-id matches]}]
     (reduce (fn [counts {:keys [path count]}]
               (if (str/blank? (str path))
                 counts
                 (update counts [repo-id path] (fnil + 0) (long (or count 1)))))
             counts
             matches))
   {}
   search-results))

(defn- grep-count-for-doc
  [match-counts single-repo-id doc]
  (let [path (:path doc)
        repo-id (:repo-id doc)]
    (long (or (get match-counts [(some-> repo-id str) path])
              (when (and (nil? repo-id) single-repo-id)
                (get match-counts [single-repo-id path]))
              0))))

(defn- grep-score-data-for-docs
  [docs repo-ids match-counts]
  (let [single-repo-id (when (= 1 (count repo-ids)) (first repo-ids))]
    (reduce
     (fn [state doc]
       (let [n (grep-count-for-doc match-counts single-repo-id doc)]
         (if (pos? n)
           (-> state
               (assoc-in [:scores (:target-id doc)] (double n))
               (update :indexed-paths conj [(:repo-id doc) (:path doc)]))
           state)))
     {:scores {}
      :indexed-paths #{}}
     docs)))

(defn- diagnostic-kind-counts
  [diagnostics]
  (->> diagnostics
       (keep :kind)
       frequencies
       (into (sorted-map))))

(defn- grep-skip-data
  [reason diagnostics]
  {:scores {}
   :instrumentation {:grep-enabled? false
                     :grep-status :skipped
                     :grep-skip-reason reason
                     :grep-repos 0
                     :grep-patterns 0
                     :grep-searches 0
                     :grep-raw-matches 0
                     :grep-indexed-paths 0
                     :grep-candidates 0
                     :grep-diagnostic-kinds (diagnostic-kind-counts diagnostics)}})

(defn- literal-grep-data
  [xtdb query-tokens docs scope opts retriever]
  (cond
    (false? (:grep? opts))
    (grep-skip-data :disabled [])

    (= :semantic retriever)
    (grep-skip-data :semantic-retriever [])

    :else
    (let [patterns (grep-patterns query-tokens opts)]
      (if (empty? patterns)
        (grep-skip-data :no-literal-patterns [])
        (let [{:keys [roots diagnostics]} (repo-root-data xtdb scope opts)
              repo-ids (grep-repo-ids docs roots (:repo-id scope))]
          (if (empty? repo-ids)
            (grep-skip-data :missing-repo-roots diagnostics)
            (let [search-results (grep-search-results roots repo-ids patterns opts)
                  all-diagnostics (concat diagnostics
                                          (mapcat :diagnostics search-results))
                  match-counts (grep-match-counts search-results)
                  score-data (grep-score-data-for-docs docs repo-ids match-counts)
                  scores (normalize-scores (:scores score-data))
                  matched-indexed-paths (count (:indexed-paths score-data))]
              {:scores scores
               :instrumentation {:grep-enabled? true
                                 :grep-status (if (seq all-diagnostics) :partial :ok)
                                 :grep-repos (count repo-ids)
                                 :grep-patterns (count patterns)
                                 :grep-searches (count search-results)
                                 :grep-raw-matches (reduce + 0 (map :match-count search-results))
                                 :grep-indexed-paths matched-indexed-paths
                                 :grep-candidates (count scores)
                                 :grep-diagnostic-kinds (diagnostic-kind-counts all-diagnostics)}})))))))

(defn- lexical-scores
  [query-tokens docs]
  (let [doc-count (max 1 (count docs))
        query-token-set (set query-tokens)
        {:keys [doc-stats doc-freqs token-total]} (lexical-score-input
                                                   query-token-set
                                                   docs)
        idf-by-token (inverse-document-frequencies query-tokens doc-freqs doc-count)
        avgdl (max 1.0 (/ (double token-total) doc-count))]
    (->> doc-stats
         (keep (fn [{:keys [doc token-count query-token-frequencies]}]
                 (let [score (if (and (zero? token-count)
                                      (number? (:score doc)))
                               (double (:score doc))
                               (bm25-score query-tokens
                                           idf-by-token
                                           avgdl
                                           token-count
                                           query-token-frequencies))]
                   (when (pos? score)
                     [(:target-id doc) score]))))
         (into {})
         normalize-scores)))

(defn- semantic-scores
  [query-vector docs embeddings provider model]
  (let [current-inputs (into {}
                             (map (juxt :target-id :input-sha))
                             docs)]
    (reduce
     (fn [scores row]
       (let [target-id (:target-id row)]
         (if (and (= provider (:provider row))
                  (= model (:model row))
                  (= (:input-sha row) (get current-inputs target-id)))
           (let [score (embedding/cosine01 query-vector (:vector row))]
             (if (pos? score)
               (assoc scores target-id score)
               scores))
           scores)))
     {}
     embeddings)))

(defn- score-entry-sort-key
  [[_ score]]
  (- score))

(defn- bounded-score-entries
  [entries entry n]
  (let [entries (conj (or entries []) entry)]
    (if (<= (count entries) n)
      entries
      (vec (take n (sort-by score-entry-sort-key entries))))))

(defn- top-ids
  [scores n]
  (if (pos? n)
    (->> scores
         (reduce
          (fn [entries entry]
            (if (pos? (double (val entry)))
              (bounded-score-entries entries entry n)
              entries))
          [])
         (sort-by score-entry-sort-key)
         (mapv key))
    []))

(defn- update-rows-by-kind-counts
  [counts row]
  (update counts (:target-kind row) (fnil inc 0)))

(defn- rows-by-kind
  [rows]
  (reduce update-rows-by-kind-counts (sorted-map) rows))

(defn- candidate-sort-key
  [candidate]
  [(- (:score candidate)) (:label candidate) (:path candidate)])

(defn- bounded-kind-candidates
  [candidates candidate n]
  (let [candidates (conj (or candidates []) candidate)]
    (if (<= (count candidates) n)
      candidates
      (vec (take n (sort-by candidate-sort-key candidates))))))

(defn- top-ids-by-kind
  [scores docs n]
  (if (pos? n)
    (->> docs
         (reduce
          (fn [candidates-by-kind doc]
            (let [score (double (get scores (:target-id doc) 0.0))]
              (if (pos? score)
                (let [candidate {:target-id (:target-id doc)
                                 :score score
                                 :label (:label doc)
                                 :path (:path doc)}
                      group [(:target-kind doc) (or (:kind doc) :unknown)]]
                  (update candidates-by-kind
                          group
                          bounded-kind-candidates
                          candidate
                          n))
                candidates-by-kind)))
          {})
         vals
         (mapcat #(->> %
                       (sort-by candidate-sort-key)
                       (map :target-id)))
         vec)
    []))

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

(defn- path-shaped-query?
  [query]
  (or (str/includes? query "/")
      (str/includes? query ".")))

(defn- exact-path-candidate-ids
  [query query-path-shaped? docs]
  (if query-path-shaped?
    (->> docs
         (filter #(exact-path-mentioned? query (:path %)))
         (mapv :target-id))
    []))

(defn- matching-query-token-count
  [query-token-set text]
  (let [query-token-count (count query-token-set)]
    (if (zero? query-token-count)
      0
      (count
       (reduce
        (fn [matches token]
          (if (contains? query-token-set token)
            (let [matches (conj matches token)]
              (if (= query-token-count (count matches))
                (reduced matches)
                matches))
            matches))
        #{}
        (text/tokenize text))))))

(defn- path-token-sort-key
  [candidate]
  [(- (:matches candidate)) (:path candidate) (:label candidate)])

(defn- bounded-path-token-candidates
  [candidates candidate n]
  (let [candidates (conj (or candidates []) candidate)]
    (if (<= (count candidates) n)
      candidates
      (vec (take n (sort-by path-token-sort-key candidates))))))

(defn- path-token-candidate-data
  [query-token-set docs n]
  (if (empty? query-token-set)
    {:match-counts {}
     :candidate-ids []}
    (let [{:keys [match-counts candidates]} (reduce
                                             (fn [state doc]
                                               (let [match-count (long (matching-query-token-count
                                                                        query-token-set
                                                                        (:path doc)))]
                                                 (if (pos? match-count)
                                                   (let [candidate {:target-id (:target-id doc)
                                                                    :matches match-count
                                                                    :path (:path doc)
                                                                    :label (:label doc)}]
                                                     (cond-> (assoc-in state
                                                                       [:match-counts (:target-id doc)]
                                                                       match-count)
                                                       (pos? n)
                                                       (update :candidates
                                                               bounded-path-token-candidates
                                                               candidate
                                                               n)))
                                                   state)))
                                             {:match-counts {}
                                              :candidates []}
                                             docs)]
      {:match-counts match-counts
       :candidate-ids (->> candidates
                           (sort-by path-token-sort-key)
                           (mapv :target-id))})))

(def relation-graph-weights
  {:references 1.0
   :calls 0.9
   :uses 0.8
   :imports 0.6
   :requires 0.6
   :imports-package 0.5
   :declares-module 0.4
   :defines 0.2})

(defn- update-neighbor-score
  [scores id score]
  (update scores id #(max (double (or % 0.0)) score)))

(defn- graph-neighbor-scores
  [edges seed-ids]
  (let [seeds (set seed-ids)]
    (reduce
     (fn [scores {:keys [source-id target-id relation]}]
       (let [source-seed? (contains? seeds source-id)
             target-seed? (contains? seeds target-id)
             score (double (get relation-graph-weights relation 0.25))]
         (cond-> scores
           (and source-seed? (not target-seed?))
           (update-neighbor-score target-id score)

           (and target-seed? (not source-seed?))
           (update-neighbor-score source-id score))))
     {}
     edges)))

(defn- same-label-expansion
  [docs seed-ids]
  (if (empty? seed-ids)
    {:node-ids #{}
     :doc-scores {}}
    (let [seed-set (set seed-ids)
          seed-data (reduce
                     (fn [state doc]
                       (if (contains? seed-set (:target-id doc))
                         (let [label (:label doc)]
                           (cond-> (update state :seed-keys conj [(:path doc) label])
                             (not (str/blank? (str label)))
                             (update :seed-labels conj label)))
                         state))
                     {:seed-keys #{}
                      :seed-labels #{}}
                     docs)
          {:keys [seed-keys seed-labels]} seed-data]
      (if (and (empty? seed-keys)
               (empty? seed-labels))
        {:node-ids #{}
         :doc-scores {}}
        (let [{:keys [node-ids label-states]} (reduce
                                               (fn [state doc]
                                                 (let [target-id (:target-id doc)
                                                       label (:label doc)
                                                       path (:path doc)]
                                                   (cond-> state
                                                     (and (= :node (:target-kind doc))
                                                          (contains? seed-keys [path label]))
                                                     (update :node-ids conj target-id)

                                                     (contains? seed-labels label)
                                                     (update-in
                                                      [:label-states label]
                                                      (fn [{:keys [count paths target-ids]
                                                            :or {count 0
                                                                 paths #{}
                                                                 target-ids []}}]
                                                        (let [count (inc count)]
                                                          (if (> count
                                                                 default-same-label-doc-label-fanout-limit)
                                                            {:count count
                                                             :paths paths
                                                             :target-ids target-ids}
                                                            (cond-> {:count count
                                                                     :paths (cond-> paths
                                                                              path (conj path))
                                                                     :target-ids target-ids}
                                                              (not (contains? seed-set target-id))
                                                              (update :target-ids conj target-id)))))))))
                                               {:node-ids #{}
                                                :label-states {}}
                                               docs)
              doc-scores (->> label-states
                              vals
                              (filter #(<= (:count %)
                                           default-same-label-doc-label-fanout-limit))
                              (filter #(< 1 (count (:paths %))))
                              (mapcat :target-ids)
                              (map (fn [target-id] [target-id 1.0]))
                              (into {}))]
          {:node-ids node-ids
           :doc-scores doc-scores})))))

(defn- exact-match-boost
  [query query-token-set query-path-shaped? path-token-match-counts doc]
  (let [path-token-matches (long (or (get path-token-match-counts
                                          (:target-id doc))
                                     0))
        label (str/lower-case (:label doc))
        path-token-boost (min 0.15
                              (* 0.05 (double path-token-matches)))]
    (cond
      (and query-path-shaped?
           (exact-path-mentioned? query (:path doc))) 2.0

      (and (not (str/blank? query)) (= query label)) 0.25
      (and (not (str/blank? query)) (str/includes? label query)) 0.15
      (pos? (matching-query-token-count query-token-set (:label doc))) (+ 0.05 path-token-boost)
      :else path-token-boost)))

(defn- ranked-result-sort-key
  [row]
  [(- (:score row)) (:label row) (:path row)])

(defn- bounded-ranked-results
  [results row n]
  (let [results (conj (or results []) row)]
    (if (<= (count results) n)
      results
      (vec (take n (sort-by ranked-result-sort-key results))))))

(defn- add-ranked-result
  [state row n]
  (let [state (update state :ranked-count inc)]
    (if (pos? n)
      (update state :ranked bounded-ranked-results row n)
      state)))

(defn- ranked-candidates
  [{:keys [query-text query-tokens docs lexical semantic grep neighbor-scores same-label-scores retriever limit]}]
  (let [query (str/lower-case (str/trim query-text))
        query-path-shaped? (path-shaped-query? query)
        query-token-set (set query-tokens)
        path-token-data (path-token-candidate-data query-token-set
                                                   docs
                                                   default-path-token-candidates)
        path-token-match-counts (:match-counts path-token-data)
        semantic-candidates (if (not= :lexical retriever)
                              (into (set (top-ids semantic default-semantic-candidates))
                                    (top-ids-by-kind semantic
                                                     docs
                                                     default-kind-candidates))
                              #{})
        lexical-candidates (if (not= :semantic retriever)
                             (into (set (top-ids lexical default-lexical-candidates))
                                   (top-ids-by-kind lexical
                                                    docs
                                                    default-kind-candidates))
                             #{})
        grep-candidates (if (not= :semantic retriever)
                          (set (top-ids grep default-lexical-candidates))
                          #{})
        exact-path-candidates (exact-path-candidate-ids query
                                                        query-path-shaped?
                                                        docs)
        path-token-candidates (:candidate-ids path-token-data)
        same-label-candidates (set (keys same-label-scores))
        candidates (case retriever
                     :semantic (-> semantic-candidates
                                   (into exact-path-candidates)
                                   (into path-token-candidates)
                                   (into same-label-candidates))
                     :lexical (-> lexical-candidates
                                  (into grep-candidates)
                                  (into exact-path-candidates)
                                  (into path-token-candidates)
                                  (into (keys neighbor-scores))
                                  (into same-label-candidates))
                     (-> semantic-candidates
                         (into lexical-candidates)
                         (into grep-candidates)
                         (into exact-path-candidates)
                         (into path-token-candidates)
                         (into (keys neighbor-scores))
                         (into same-label-candidates)))
        rank-limit (long (or limit Long/MAX_VALUE))
        rank-data (reduce
                   (fn [rank-data doc]
                     (if (contains? candidates (:target-id doc))
                       (let [rank-data (-> rank-data
                                           (update :candidate-doc-count inc)
                                           (update :candidates-by-kind
                                                   update-rows-by-kind-counts
                                                   doc))
                             semantic-score (double (get semantic (:target-id doc) 0.0))
                             lexical-score (double (get lexical (:target-id doc) 0.0))
                             grep-score (double (get grep (:target-id doc) 0.0))
                             graph-score (double (get neighbor-scores (:target-id doc) 0.0))
                             same-label-score (double (get same-label-scores (:target-id doc) 0.0))
                             exact-score (exact-match-boost query
                                                            query-token-set
                                                            query-path-shaped?
                                                            path-token-match-counts
                                                            doc)
                             total (+ (case retriever
                                        :semantic semantic-score
                                        :lexical (+ lexical-score
                                                    (* lexical-grep-weight grep-score)
                                                    (* lexical-graph-weight graph-score)
                                                    (* lexical-same-label-weight same-label-score))
                                        (+ (* 0.70 semantic-score)
                                           (* 0.20 lexical-score)
                                           (* hybrid-grep-weight grep-score)
                                           (* hybrid-graph-weight graph-score)
                                           (* hybrid-same-label-weight same-label-score)))
                                      exact-score)]
                         (if (pos? total)
                           (add-ranked-result
                            rank-data
                            (-> doc
                                (assoc :result-kind (:target-kind doc)
                                       :score total
                                       :score-components {:semantic semantic-score
                                                          :lexical lexical-score
                                                          :grep grep-score
                                                          :graph graph-score
                                                          :sameLabel same-label-score
                                                          :exact exact-score}
                                       :reason (cond
                                                 (pos? semantic-score) "embedding match"
                                                 (pos? lexical-score) "lexical match"
                                                 (pos? grep-score) "literal grep match"
                                                 (pos? graph-score) "graph neighbor"
                                                 (pos? same-label-score) "same-label candidate"
                                                 :else "candidate")))
                            rank-limit)
                           rank-data))
                       rank-data))
                   {:ranked []
                    :ranked-count 0
                    :candidate-doc-count 0
                    :candidates-by-kind (sorted-map)}
                   docs)]
    {:semantic-candidates semantic-candidates
     :lexical-candidates lexical-candidates
     :grep-candidates grep-candidates
     :exact-path-candidates (set exact-path-candidates)
     :path-token-candidates (set path-token-candidates)
     :same-label-candidates same-label-candidates
     :candidates candidates
     :candidate-doc-count (:candidate-doc-count rank-data)
     :candidates-by-kind (:candidates-by-kind rank-data)
     :ranked-count (:ranked-count rank-data)
     :ranked (->> (:ranked rank-data)
                  (sort-by ranked-result-sort-key)
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
        initial-retriever (retriever-mode requested-retriever embedding-client)
        scope {:project-id project-id
               :repo-id repo-id
               :read-context (read-context opts)}
        [docs timings] (timed {} :load-search-docs-ms #(vec (all-search-docs xtdb scope)))
        [query-tokens timings] (timed timings :tokenize-ms #(text/tokenize query-text))
        [lexical timings] (timed timings :lexical-score-ms #(lexical-scores query-tokens docs))
        [grep-data timings] (timed timings
                                   :grep-search-ms
                                   #(literal-grep-data xtdb
                                                       query-tokens
                                                       docs
                                                       scope
                                                       opts
                                                       initial-retriever))
        grep (:scores grep-data)
        [embeddings timings] (if (and embedding-client
                                      (#{:hybrid :semantic} initial-retriever))
                               (timed timings
                                      :load-embeddings-ms
                                      #(vec (embedding/current-embeddings-for-docs
                                             xtdb
                                             docs
                                             (assoc scope
                                                    :provider (:provider embedding-client)
                                                    :model (:model embedding-client)))))
                               [[] (assoc timings :load-embeddings-ms 0)])
        retriever (if (and (= :auto requested-retriever)
                           embedding-client
                           (empty? embeddings))
                    :lexical
                    initial-retriever)
        [semantic timings] (if (#{:hybrid :semantic} retriever)
                             (if embedding-client
                               (let [[query-vector timings] (timed timings
                                                                   :query-embedding-ms
                                                                   #(query-vector
                                                                     embedding-client
                                                                     query-text))]
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
                                        :semantic-score-ms 0)])
        [seed-data timings] (timed timings
                                   :seed-selection-ms
                                   #(let [base-seed-ids (into (set (top-ids semantic
                                                                            default-seed-count))
                                                              (top-ids lexical
                                                                       default-seed-count))
                                          grep-seed-ids (if (not= :semantic retriever)
                                                          (set (top-ids grep default-seed-count))
                                                          #{})
                                          retrieval-seed-ids (into base-seed-ids
                                                                   grep-seed-ids)
                                          same-label-data (same-label-expansion
                                                           docs
                                                           retrieval-seed-ids)
                                          same-label-ids (:node-ids same-label-data)
                                          same-label-scores (:doc-scores same-label-data)]
                                      {:base-seed-ids base-seed-ids
                                       :grep-seed-ids grep-seed-ids
                                       :same-label-ids same-label-ids
                                       :same-label-scores same-label-scores
                                       :seed-ids (into retrieval-seed-ids
                                                       same-label-ids)}))
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
                                                          :grep grep
                                                          :neighbor-scores neighbor-scores
                                                          :same-label-scores (:same-label-scores seed-data)
                                                          :retriever retriever
                                                          :limit limit}))
        ranked (:ranked ranked-data)
        instrumentation (merge
                         timings
                         (:instrumentation grep-data)
                         (adjacency-query-plan xtdb
                                               (:seed-ids seed-data)
                                               edges
                                               (:load-edges-ms timings))
                         {:search-total-ms (elapsed-ms started)
                          :search-docs (count docs)
                          :search-docs-by-kind (rows-by-kind docs)
                          :query-tokens (count query-tokens)
                          :lexical-positive (count lexical)
                          :grep-positive (count grep)
                          :semantic-positive (count semantic)
                          :seed-count (count (:seed-ids seed-data))
                          :grep-seed-count (count (:grep-seed-ids seed-data))
                          :same-label-seed-count (count (:same-label-ids seed-data))
                          :same-label-doc-candidates (count (:same-label-scores seed-data))
                          :graph-edges-loaded (count edges)
                          :neighbor-count (count neighbor-scores)
                          :grep-candidates (count (:grep-candidates ranked-data))
                          :exact-path-candidates (count (:exact-path-candidates ranked-data))
                          :path-token-candidates (count (:path-token-candidates ranked-data))
                          :candidate-count (count (:candidates ranked-data))
                          :candidates-by-kind (:candidates-by-kind ranked-data)
                          :ranked-count (:ranked-count ranked-data)
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
