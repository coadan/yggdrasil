(ns ygg.query
  "Graph query helpers."
  (:require [ygg.hash :as hash]
            [ygg.ripgrep :as ripgrep]
            [ygg.retrieval.fusion :as fusion]
            [ygg.system.candidate :as system-candidate]
            [ygg.text :as text]
            [ygg.vector-store :as vector-store]
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
(def default-grep-patterns 6)
(def default-grep-timeout-ms 1500)
(def default-grep-max-stdout-bytes 200000)
(def default-transient-file-candidates 80)
(def default-grep-reserve-candidates 12)
(def default-source-file-reserve-candidates 12)
(def default-result-kind-reserve-candidates 1)
(def default-specific-grep-patterns 3)
(def ^:private path-token-identity-min-length 9)
(def lexical-graph-weight 0.25)
(def hybrid-graph-weight 0.20)
(def lexical-same-label-weight 0.25)
(def hybrid-same-label-weight 0.20)
(def lexical-grep-weight 0.75)
(def hybrid-grep-weight 0.20)
(def lexical-fts-weight 0.75)
(def hybrid-fts-weight 0.20)
(def default-fusion-strategy :weighted)
(def default-diversity-rerank-limit 0)

(def search-report-schema "ygg.search.report/v1")

(def ^:private grep-ignore-globs
  (vec (mapcat (fn [dir]
                 [(str dir "/**")
                  (str "**/" dir "/**")])
               [".git" ".dev" ".ygg" ".cpcache" ".clj-kondo" "target"
                "node_modules" ".shadow-cljs" ".calva" ".idea" "ygg-out"])))

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

(declare all-nodes all-edges all-system-nodes concrete-path? path-shaped-query? exact-path-mentioned?)

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
   :label
   :normalized-value
   :source-line
   :confidence
   :active?
   :run-id])

(def ^:private file-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :repo-root
   :repo-role
   :path
   :ext
   :kind
   :content-sha
   :mtime-ms
   :size-bytes
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

(def ^:private search-doc-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :target-id
   :target-kind
   :file-id
   :path
   :kind
   :label
   :text
   :tokens
   :input-sha
   :source-line
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

(defn- files-by-paths
  [xtdb paths opts]
  (rows-by-field-values xtdb
                        (:files store/tables)
                        :path
                        paths
                        (assoc opts :active? true)
                        file-row-query-fields))

(defn- files-matching-path-tokens
  [xtdb tokens opts]
  (let [rows (store/rows-matching-any-token
              xtdb
              (:files store/tables)
              [:path]
              tokens
              (assoc (scope-constraints opts) :active? true)
              (read-context opts)
              file-row-query-fields)]
    (filter-scope rows opts)))

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
   (let [constraints (merge (scope-constraints opts) {:active? true})
         rows (store/ordered-rows xtdb
                                  {:table (:search-docs store/tables)
                                   :constraints constraints
                                   :return-fields search-doc-row-query-fields
                                   :read-context (read-context opts)})]
     (filter-scope rows opts))))

(def ^:private max-search-corpus-cache-entries
  32)

(def ^:private temporal-read-context-keys
  [:valid-at :known-at :snapshot-token :current-time])

(defn- current-search-corpus-cache-key
  [{:keys [project-id repo-id read-context]}]
  (when-not (some #(get read-context %) temporal-read-context-keys)
    [project-id repo-id]))

(defn- evict-search-corpus-entry
  [entries]
  (if (<= (count entries) max-search-corpus-cache-entries)
    entries
    (dissoc entries
            (key (apply min-key
                        (fn [[_ {:keys [last-used]}]] last-used)
                        entries)))))

(declare normalize-grep-token grep-doc-structured-tokens)

(defn- search-corpus-stats
  [docs]
  (reduce
   (fn [stats doc]
     (let [tokens (->> (:tokens doc)
                       (keep normalize-grep-token)
                       set)
           structured-tokens (grep-doc-structured-tokens doc)]
       (-> stats
           (update :grep-token-frequencies
                   (fn [frequencies]
                     (reduce #(update %1 %2 (fnil inc 0)) frequencies tokens)))
           (update :grep-structured-token-frequencies
                   (fn [frequencies]
                     (reduce #(update %1 %2 (fnil inc 0))
                             frequencies
                             structured-tokens))))))
   {:grep-token-frequencies {}
    :grep-structured-token-frequencies {}}
   docs))

(defn- emit-progress!
  [progress-fn event]
  (when progress-fn
    (progress-fn event)))

(defn- cached-search-docs
  [xtdb scope progress-fn]
  (let [cache (:search-corpus-cache xtdb)
        cache-key (current-search-corpus-cache-key scope)]
    (if (and cache cache-key)
      (locking cache
        (let [{:keys [generation clock entries]} @cache
              next-clock (inc (long clock))]
          (if-let [entry (get entries cache-key)]
            (do
              (swap! cache assoc
                     :clock next-clock
                     :entries (assoc entries cache-key (assoc entry :last-used next-clock)))
              {:docs (:docs entry)
               :stats (:stats entry)
               :cache-status :hit
               :cache-generation generation})
            (let [load-started (now-ns)
                  _ (emit-progress! progress-fn
                                    (merge (select-keys scope [:project-id :repo-id])
                                           {:phase :search-corpus-load-start
                                            :cache-status :miss}))
                  docs (vec (all-search-docs xtdb scope))
                  stats (search-corpus-stats docs)
                  loaded-generation (:generation @cache)]
              (emit-progress! progress-fn
                              (merge (select-keys scope [:project-id :repo-id])
                                     {:phase :search-corpus-load-complete
                                      :cache-status :miss
                                      :search-docs (count docs)
                                      :elapsed-ms (elapsed-ms load-started)}))
              (when (= generation loaded-generation)
                (swap! cache
                       (fn [state]
                         (if (= generation (:generation state))
                           (assoc state
                                  :clock next-clock
                                  :entries (evict-search-corpus-entry
                                            (assoc (:entries state)
                                                   cache-key
                                                   {:docs docs
                                                    :stats stats
                                                    :last-used next-clock})))
                           state))))
              {:docs docs
               :stats stats
               :cache-status :miss
               :cache-generation loaded-generation}))))
      (let [load-started (now-ns)
            _ (emit-progress! progress-fn
                              (merge (select-keys scope [:project-id :repo-id])
                                     {:phase :search-corpus-load-start
                                      :cache-status :bypass}))
            docs (vec (all-search-docs xtdb scope))
            stats (search-corpus-stats docs)]
        (emit-progress! progress-fn
                        (merge (select-keys scope [:project-id :repo-id])
                               {:phase :search-corpus-load-complete
                                :cache-status :bypass
                                :search-docs (count docs)
                                :elapsed-ms (elapsed-ms load-started)}))
        {:docs docs
         :stats stats
         :cache-status :bypass
         :cache-generation nil}))))

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

(defn- normalize-grep-token
  [token]
  (some-> (str/lower-case (str token))
          (str/replace #"^[^a-z0-9_./-]+" "")
          (str/replace #"[^a-z0-9_./-]+$" "")
          (str/replace #"[.]+$" "")
          not-empty))

(defn- hash-like-grep-token?
  [token]
  (boolean (re-matches #"[0-9a-f]{16,}" (str token))))

(defn- grep-token-candidate?
  [token]
  (and (<= 2 (count (re-seq #"[a-z0-9]" (str token))))
       (not (hash-like-grep-token? token))))

(defn- compound-grep-token-fragments
  [token]
  (let [token (str token)
        separators (re-seq #"[._/-]" token)
        parts (str/split token #"[._/-]+")]
    (when (and (seq separators)
               (<= 2 (count parts)))
      (->> (map vector parts (rest parts) separators)
           (map (fn [[left right separator]]
                  (str left separator right)))
           (remove #(= token %))))))

(defn- grep-token-candidate-values
  [token]
  (when-let [token (normalize-grep-token token)]
    (->> (cons token (compound-grep-token-fragments token))
         (keep normalize-grep-token)
         distinct)))

(defn- grep-token-candidates
  [query-tokens]
  (->> query-tokens
       (map-indexed (fn [idx token]
                      (map (fn [token]
                             {:token token
                              :idx idx})
                           (grep-token-candidate-values token))))
       (mapcat identity)
       (filter #(grep-token-candidate? (:token %)))
       (reduce (fn [state {:keys [token] :as candidate}]
                 (if (contains? (:seen state) token)
                   (update-in state [:candidate-by-token token :frequency]
                              (fnil inc 1))
                   (-> state
                       (update :seen conj token)
                       (assoc-in [:candidate-by-token token]
                                 (assoc candidate :frequency 1)))))
               {:seen #{}
                :candidate-by-token {}})
       :candidate-by-token
       vals))

(defn- grep-doc-token-frequencies
  [tokens docs]
  (let [token-set (set tokens)]
    (reduce
     (fn [freqs doc]
       (reduce (fn [freqs token]
                 (if (contains? token-set token)
                   (update freqs token (fnil inc 0))
                   freqs))
               freqs
               (->> (:tokens doc)
                    (keep normalize-grep-token)
                    set)))
     {}
     docs)))

(defn- grep-doc-structured-tokens
  [doc]
  (->> [(:path doc)
        (:label doc)
        (:kind doc)
        (:definition-kind doc)
        (:heading-path doc)]
       (remove nil?)
       (str/join "\n")
       text/tokenize-all
       (keep normalize-grep-token)
       set))

(defn- grep-doc-structured-token-frequencies
  [tokens docs]
  (let [token-set (set tokens)]
    (reduce
     (fn [freqs doc]
       (reduce (fn [freqs token]
                 (if (contains? token-set token)
                   (update freqs token (fnil inc 0))
                   freqs))
               freqs
               (grep-doc-structured-tokens doc)))
     {}
     docs)))

(defn- shaped-grep-token?
  [token]
  (boolean (re-find #"[._/-]" (str token))))

(defn- grep-token-alnum-count
  [token]
  (count (re-seq #"[a-z0-9]" (str token))))

(defn- corpus-grep-pattern-key
  [doc-count doc-frequencies structured-frequencies {:keys [token idx frequency]}]
  (let [df (double (get doc-frequencies token 0.0))
        structured-df (double (get structured-frequencies token 0.0))
        idf (Math/log1p (/ (double (max 1 doc-count))
                           (max 1.0 df)))
        structured-idf (Math/log1p (/ (double (max 1 doc-count))
                                      (max 1.0 structured-df)))]
    [(if (pos? structured-df) 0 1)
     (- (min 64.0 structured-df))
     (- (long (or frequency 1)))
     (- structured-idf)
     (- idf)
     (if (shaped-grep-token? token) 0 1)
     (- (grep-token-alnum-count token))
     idx
     token]))

(defn- fallback-grep-pattern-key
  [{:keys [token idx frequency]}]
  [(- (long (or frequency 1)))
   (if (shaped-grep-token? token) 0 1)
   (- (grep-token-alnum-count token))
   idx
   token])

(defn- specific-grep-pattern?
  [{:keys [token]}]
  (shaped-grep-token? token))

(defn- ordered-distinct-tokens
  [candidates]
  (->> candidates
       (reduce (fn [state {:keys [token]}]
                 (if (contains? (:seen state) token)
                   state
                   (-> state
                       (update :seen conj token)
                       (update :tokens conj token))))
               {:seen #{}
                :tokens []})
       :tokens))

(defn- grep-patterns
  ([query-tokens opts]
   (grep-patterns query-tokens [] opts))
  ([query-tokens docs opts]
   (let [limit (long (or (:grep-pattern-limit opts) default-grep-patterns))
         specific-limit (long (or (:specific-grep-pattern-limit opts)
                                  default-specific-grep-patterns))
         candidates (grep-token-candidates query-tokens)
         corpus-stats (:search-corpus-stats opts)
         doc-frequencies (or (:grep-token-frequencies corpus-stats)
                             (grep-doc-token-frequencies (map :token candidates) docs))
         structured-frequencies (or (:grep-structured-token-frequencies corpus-stats)
                                    (grep-doc-structured-token-frequencies
                                     (map :token candidates)
                                     docs))
         doc-count (count docs)
         corpus-backed (->> candidates
                            (filter #(pos? (long (get doc-frequencies (:token %) 0))))
                            (sort-by #(corpus-grep-pattern-key doc-count
                                                               doc-frequencies
                                                               structured-frequencies
                                                               %)))
         fallback (sort-by fallback-grep-pattern-key candidates)
         specific-fallback (->> fallback
                                (filter specific-grep-pattern?)
                                (take (min specific-limit limit))
                                vec)
         primary-limit (max 0 (- limit (count specific-fallback)))
         primary (take primary-limit corpus-backed)]
     (->> (concat corpus-backed fallback)
          (concat primary specific-fallback)
          ordered-distinct-tokens
          (take limit)
          vec))))

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
           :hidden? true
           :ignore-globs grep-ignore-globs
           :ignore-case? true}
    (:grep-bin opts)
    (assoc :bin (:grep-bin opts))))

(defn- grep-pattern-weight
  [pattern]
  (let [pattern (str pattern)
        alnum-count (count (re-seq #"[A-Za-z0-9]" pattern))
        shaped? (boolean (re-find #"[._/-]" pattern))]
    (+ 1.0
       (min 3.0 (/ (double alnum-count) 8.0))
       (if shaped? 3.0 0.0))))

(defn- grep-search-results
  [repo-roots repo-ids patterns opts]
  (let [search-opts (grep-search-opts opts)]
    (vec
     (for [repo-id repo-ids
           :let [root (get repo-roots repo-id)
                 pattern-results (mapv (fn [pattern]
                                         (let [weight (grep-pattern-weight pattern)]
                                           (assoc (ripgrep/search-counts-many
                                                   root
                                                   [pattern]
                                                   []
                                                   search-opts)
                                                  :pattern pattern
                                                  :pattern-weight weight)))
                                       patterns)
                 matches-by-path (->> pattern-results
                                      (mapcat (fn [{:keys [pattern-weight matches]}]
                                                (map #(assoc %
                                                             :weighted-count
                                                             (* (double pattern-weight)
                                                                (Math/log1p
                                                                 (double (or (:count %) 1)))))
                                                     matches)))
                                      (group-by :path)
                                      (mapv (fn [[path matches]]
                                              {:path path
                                               :count (reduce + 0 (map :count matches))
                                               :weighted-count (reduce + 0.0 (map :weighted-count matches))})))]]
       {:repo-id repo-id
        :matches matches-by-path
        :match-count (reduce + 0 (map :match-count pattern-results))
        :file-count (count matches-by-path)
        :elapsed-ms (reduce + 0 (map :elapsed-ms pattern-results))
        :search-count (count pattern-results)
        :diagnostics (mapcat :diagnostics pattern-results)}))))

(defn- grep-match-counts
  [search-results]
  (reduce
   (fn [counts {:keys [repo-id matches]}]
     (reduce (fn [counts {:keys [path count weighted-count]}]
               (if (str/blank? (str path))
                 counts
                 (update counts
                         [repo-id path]
                         (fnil + 0.0)
                         (double (or weighted-count count 1)))))
             counts
             matches))
   {}
   search-results))

(defn- grep-count-for-doc
  [match-counts single-repo-id doc]
  (let [path (:path doc)
        repo-id (:repo-id doc)]
    (double (or (get match-counts [(some-> repo-id str) path])
                (when (and (nil? repo-id) single-repo-id)
                  (get match-counts [single-repo-id path]))
                0.0))))

(defn- grep-score-data-for-docs
  [docs repo-ids match-counts]
  (let [single-repo-id (when (= 1 (count repo-ids)) (first repo-ids))]
    (reduce
     (fn [state doc]
       (let [n (grep-count-for-doc match-counts single-repo-id doc)]
         (if (pos? n)
           (-> state
               (assoc-in [:scores (:target-id doc)] n)
               (update :indexed-paths conj [(:repo-id doc) (:path doc)]))
           state)))
     {:scores {}
      :indexed-paths #{}}
     docs)))

(defn- rescore-grep-data
  [grep-data docs]
  (if (get-in grep-data [:instrumentation :grep-enabled?])
    (let [score-data (grep-score-data-for-docs docs
                                               (:repo-ids grep-data)
                                               (:match-counts grep-data))
          scores (normalize-scores (:scores score-data))]
      (-> grep-data
          (assoc :scores scores)
          (assoc-in [:instrumentation :grep-indexed-paths]
                    (count (:indexed-paths score-data)))
          (assoc-in [:instrumentation :grep-candidates]
                    (count scores))))
    grep-data))

(defn- trim-path-mention
  [value]
  (some-> value
          str
          str/trim
          (str/replace #"^[^A-Za-z0-9._/~+\-]+" "")
          (str/replace #"[^A-Za-z0-9._/~+\-]+$" "")))

(defn- path-mention?
  [value]
  (and (<= 3 (count (str value)))
       (boolean (re-find #"[A-Za-z0-9]" (str value)))
       (concrete-path? value)))

(defn- path-mentions
  [query-text]
  (->> (str/split (str query-text) #"\s+")
       (map trim-path-mention)
       (filter path-mention?)
       distinct
       vec))

(defn- match-count-path-rows
  [match-counts]
  (->> match-counts
       (map (fn [[[repo-id path] n]]
              {:repo-id repo-id
               :path path
               :count (long n)}))
       (remove #(str/blank? (str (:path %))))
       (sort-by (fn [{:keys [count repo-id path]}]
                  [(- count) (str repo-id) path]))))

(defn- indexed-doc-path-keys
  [docs repo-ids]
  (let [single-repo-id (when (= 1 (count repo-ids)) (first repo-ids))]
    (reduce
     (fn [keys {:keys [repo-id path]}]
       (if (str/blank? (str path))
         keys
         (cond-> (conj keys [(some-> repo-id str) path])
           (and (nil? repo-id) single-repo-id)
           (conj [single-repo-id path]))))
     #{}
     docs)))

(defn- indexed-doc-path?
  [indexed-paths single-repo-id {:keys [repo-id path]}]
  (or (contains? indexed-paths [(some-> repo-id str) path])
      (contains? indexed-paths [nil path])
      (and single-repo-id
           (nil? repo-id)
           (contains? indexed-paths [single-repo-id path]))))

(defn- file-row->search-doc
  [file]
  (let [path (:path file)
        input-text (str/join "\n"
                             (remove str/blank?
                                     [(str path)
                                      (some-> (:kind file) name)
                                      (:ext file)]))]
    {:xt/id (str "search-doc:transient-file:"
                 (hash/short-hash [(:xt/id file) path]))
     :project-id (:project-id file)
     :repo-id (:repo-id file)
     :target-id (:xt/id file)
     :target-kind :file
     :file-id (:xt/id file)
     :path path
     :kind (:kind file)
     :label path
     :text input-text
     :tokens (text/tokenize-all input-text)
     :input-sha (hash/sha256-hex input-text)
     :source-line 1
     :active? true
     :run-id (:run-id file)
     :transient? true}))

(defn- file-candidate-sort-key
  [query match-counts single-repo-id mentions file]
  (let [path (:path file)
        grep-count (grep-count-for-doc match-counts single-repo-id file)
        exact? (and (path-shaped-query? query)
                    (exact-path-mentioned? query path))
        mention-match? (boolean
                        (some #(str/includes? (str/lower-case (str path))
                                              (str/lower-case (str %)))
                              mentions))]
    [(if exact? 0 1)
     (if mention-match? 0 1)
     (- grep-count)
     (count (str path))
     (str (:repo-id file))
     path]))

(defn- transient-file-candidate-data
  [xtdb query-text docs grep-data scope]
  (let [query (str/lower-case (str/trim query-text))
        mentions (path-mentions query-text)
        match-counts (:match-counts grep-data)
        repo-ids (:repo-ids grep-data)
        single-repo-id (when (= 1 (count repo-ids)) (first repo-ids))
        grep-paths (->> (match-count-path-rows match-counts)
                        (take default-transient-file-candidates)
                        (mapv :path))
        path-values (->> (concat mentions grep-paths)
                         (remove str/blank?)
                         distinct
                         vec)
        exact-rows (when (seq path-values)
                     (files-by-paths xtdb path-values scope))
        mention-rows (when (seq mentions)
                       (files-matching-path-tokens xtdb mentions scope))
        indexed-paths (indexed-doc-path-keys docs repo-ids)
        rows (->> (concat exact-rows mention-rows)
                  (filter :active?)
                  (filter #(or (pos? (grep-count-for-doc match-counts
                                                         single-repo-id
                                                         %))
                               (some (fn [mention]
                                       (str/includes?
                                        (str/lower-case (str (:path %)))
                                        (str/lower-case (str mention))))
                                     mentions)))
                  (remove #(indexed-doc-path? indexed-paths single-repo-id %))
                  (distinct-by :xt/id)
                  (sort-by (partial file-candidate-sort-key
                                    query
                                    match-counts
                                    single-repo-id
                                    mentions))
                  (take default-transient-file-candidates)
                  vec)
        docs (mapv file-row->search-doc rows)]
    {:docs docs
     :instrumentation {:path-mentions (count mentions)
                       :transient-file-candidates (count docs)}}))

(defn- diagnostic-kind-counts
  [diagnostics]
  (->> diagnostics
       (keep :kind)
       frequencies
       (into (sorted-map))))

(defn- grep-skip-data
  [reason diagnostics]
  {:scores {}
   :match-counts {}
   :repo-ids []
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
    (let [patterns (grep-patterns query-tokens docs opts)]
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
               :match-counts match-counts
               :repo-ids repo-ids
               :instrumentation {:grep-enabled? true
                                 :grep-status (if (seq all-diagnostics) :partial :ok)
                                 :grep-repos (count repo-ids)
                                 :grep-patterns (count patterns)
                                 :grep-pattern-values patterns
                                 :grep-searches (reduce + 0 (map #(or (:search-count %) 1)
                                                                 search-results))
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

(defn- score-entry-sort-key
  [[_ score]]
  (- score))

(defn- worse-entry-index
  [entries sort-key]
  (when (seq entries)
    (loop [idx 1
           worst-idx 0
           worst-key (sort-key (nth entries 0))]
      (if (< idx (count entries))
        (let [entry-key (sort-key (nth entries idx))]
          (if (pos? (compare entry-key worst-key))
            (recur (inc idx) idx entry-key)
            (recur (inc idx) worst-idx worst-key)))
        worst-idx))))

(defn- bounded-sort-key-entries
  [entries entry n sort-key]
  (let [entries (or entries [])]
    (cond
      (< (count entries) n)
      (conj entries entry)

      :else
      (if-let [idx (worse-entry-index entries sort-key)]
        (if (neg? (compare (sort-key entry)
                           (sort-key (nth entries idx))))
          (assoc entries idx entry)
          entries)
        entries))))

(defn- bounded-score-entries
  [entries entry n]
  (bounded-sort-key-entries entries entry n score-entry-sort-key))

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
  (bounded-sort-key-entries candidates candidate n candidate-sort-key))

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

(defn- path-tail
  [path]
  (let [value (str path)
        tail (or (last (remove str/blank? (str/split value #"/")))
                 value)]
    (str/replace tail #"\.[A-Za-z0-9]+$" "")))

(defn- compact-path-identity
  [value]
  (some-> value
          str
          str/lower-case
          (str/replace #"[^a-z0-9]+" "")
          not-empty))

(defn- compact-path-identity-token
  [value]
  (when-let [identity (compact-path-identity value)]
    (when (<= path-token-identity-min-length (count identity))
      identity)))

(defn- path-token-identity-match?
  [query-token-set doc]
  (boolean
   (some #(contains? query-token-set %)
         (keep compact-path-identity-token
               [(path-tail (:path doc))
                (path-tail (:label doc))]))))

(defn- path-token-sort-key
  [candidate]
  [(if (:identity-match? candidate) 0 1)
   (- (:matches candidate))
   (:path candidate)
   (:label candidate)])

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
                                                                    :identity-match?
                                                                    (path-token-identity-match?
                                                                     query-token-set
                                                                     doc)
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
      (path-token-identity-match? query-token-set doc) (+ 0.35 path-token-boost)
      (pos? (matching-query-token-count query-token-set (:label doc))) (+ 0.05 path-token-boost)
      :else path-token-boost)))

(defn- exact-score-map
  [query query-token-set query-path-shaped? path-token-match-counts docs]
  (into {}
        (keep (fn [doc]
                (let [score (exact-match-boost query
                                               query-token-set
                                               query-path-shaped?
                                               path-token-match-counts
                                               doc)]
                  (when (pos? score)
                    [(:target-id doc) score]))))
        docs))

(defn- normalized-fusion-strategy
  [strategy]
  (case (keyword (or strategy default-fusion-strategy))
    :rrf :rrf
    :weighted))

(defn- source-weight
  [override default]
  (double (or override default)))

(defn- fusion-weights
  [retriever {:keys [fts-weight]}]
  (case retriever
    :semantic {:semantic 1.0
               :exact 1.0
               :same-label hybrid-same-label-weight}
    :lexical {:lexical 1.0
              :fts (source-weight fts-weight lexical-fts-weight)
              :grep lexical-grep-weight
              :graph lexical-graph-weight
              :same-label lexical-same-label-weight
              :exact 1.0}
    {:semantic 0.70
     :lexical 0.20
     :fts (source-weight fts-weight hybrid-fts-weight)
     :grep hybrid-grep-weight
     :graph hybrid-graph-weight
     :same-label hybrid-same-label-weight
     :exact 1.0}))

(defn- source-maps-for-retriever
  [retriever sources]
  (select-keys sources
               (case retriever
                 :semantic [:semantic :same-label :exact]
                 :lexical [:lexical :fts :grep :graph :same-label :exact]
                 [:semantic :lexical :fts :grep :graph :same-label :exact])))

(defn- fuse-score-maps
  [strategy source-maps weights fusion-k]
  (case strategy
    :rrf (fusion/rrf-fuse source-maps {:k fusion-k
                                       :weights weights})
    (fusion/weighted-fuse source-maps weights)))

(defn- source-memberships
  [source-maps]
  (reduce-kv
   (fn [memberships source scores]
     (reduce-kv
      (fn [memberships target-id score]
        (if (pos? (double (or score 0.0)))
          (update memberships target-id (fnil conj #{}) source)
          memberships))
      memberships
      scores))
   {}
   source-maps))

(defn- facet-key
  [value]
  (cond
    (nil? value) "none"
    (keyword? value) (name value)
    :else (str value)))

(defn- facet-key-compare
  [left right]
  (let [order (compare (facet-key left) (facet-key right))]
    (if (zero? order)
      (compare (pr-str left) (pr-str right))
      order)))

(defn- facet-map
  []
  (sorted-map-by facet-key-compare))

(defn- candidate-facets
  [docs candidates memberships]
  (let [candidate? (set candidates)]
    (reduce
     (fn [facets doc]
       (if (contains? candidate? (:target-id doc))
         (let [target-id (:target-id doc)]
           (-> facets
               (update-in [:by-target-kind (:target-kind doc)] (fnil inc 0))
               (update-in [:by-repo (or (:repo-id doc) :none)] (fnil inc 0))
               (update-in [:by-file-kind (or (:kind doc) :none)] (fnil inc 0))
               (update :by-source
                       (fn [by-source]
                         (reduce (fn [by-source source]
                                   (update by-source source (fnil inc 0)))
                                 (or by-source (facet-map))
                                 (get memberships target-id #{}))))))
         facets))
     {:by-target-kind (facet-map)
      :by-repo (facet-map)
      :by-file-kind (facet-map)
      :by-source (facet-map)}
     docs)))

(defn- ranked-result-sort-key
  [row]
  [(- (:score row)) (:label row) (:path row)])

(defn- add-ranked-result
  [state row n]
  (let [state (update state :ranked-count inc)]
    (if (pos? n)
      (update state :ranked conj row)
      state)))

(defn- grep-reserve-sort-key
  [row]
  [(- (double (get-in row [:score-components :grep] 0.0)))
   (- (double (or (:score row) 0.0)))
   (:path row)
   (:label row)])

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

(defn- source-file-kind-row?
  [row]
  (boolean
   (let [kind (:kind row)]
     (or (source-like-kind? kind)
         (some->> kind
                  source-file-base-kind
                  (contains? system-candidate/source-like-kinds))))))

(defn- source-file-reserve-evidence?
  [row]
  (pos? (+ (double (get-in row [:score-components :grep] 0.0))
           (double (get-in row [:score-components :lexical] 0.0))
           (double (get-in row [:score-components :exact] 0.0))
           (double (get-in row [:score-components :sourceGraph] 0.0)))))

(defn- source-file-reserve-sort-key
  [row]
  [(- (double (get-in row [:score-components :grep] 0.0)))
   (- (double (get-in row [:score-components :lexical] 0.0)))
   (- (double (get-in row [:score-components :sourceGraph] 0.0)))
   (- (double (get-in row [:score-components :semantic] 0.0)))
   (- (double (or (:score row) 0.0)))
   (:path row)
   (:label row)])

(defn- path-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])

(defn- ranked-grep-reserve-rows
  [rows selected-ids n]
  (loop [remaining (seq (sort-by grep-reserve-sort-key rows))
         seen-paths #{}
         selected []]
    (if (or (nil? remaining)
            (<= n (count selected)))
      selected
      (let [row (first remaining)
            row-id (:target-id row)
            path-key (path-key row)]
        (if (or (contains? selected-ids row-id)
                (contains? seen-paths path-key)
                (not (pos? (double (get-in row [:score-components :grep] 0.0)))))
          (recur (next remaining) seen-paths selected)
          (recur (next remaining)
                 (conj seen-paths path-key)
                 (conj selected row)))))))

(defn- source-file-reserve-row?
  [row]
  (and (source-file-kind-row? row)
       (source-file-reserve-evidence? row)))

(defn- take-source-file-reserve-rows
  [rows selected-ids n]
  (loop [remaining (seq rows)
         seen-paths #{}
         selected []]
    (if (or (nil? remaining)
            (<= n (count selected)))
      selected
      (let [row (first remaining)
            row-id (:target-id row)
            path-key (path-key row)]
        (if (or (contains? selected-ids row-id)
                (contains? seen-paths path-key)
                (not (source-file-reserve-row? row)))
          (recur (next remaining) seen-paths selected)
          (recur (next remaining)
                 (conj seen-paths path-key)
                 (conj selected row)))))))

(defn- ranked-source-file-reserve-rows
  [rows selected-ids n]
  (let [ranked-head (vec (take-source-file-reserve-rows rows selected-ids n))
        selected-ids (into selected-ids (map :target-id ranked-head))
        remaining (max 0 (- n (count ranked-head)))]
    (vec (concat ranked-head
                 (take-source-file-reserve-rows
                  (sort-by source-file-reserve-sort-key rows)
                  selected-ids
                  remaining)))))

(defn- ranked-result-kind-reserve-rows
  [rows selected-ids selected-kinds n]
  (loop [remaining (seq rows)
         selected-kinds selected-kinds
         selected []]
    (if (or (nil? remaining)
            (<= n (count selected)))
      selected
      (let [row (first remaining)
            row-id (:target-id row)
            result-kind (:result-kind row)]
        (if (or (nil? result-kind)
                (contains? selected-ids row-id)
                (contains? selected-kinds result-kind))
          (recur (next remaining) selected-kinds selected)
          (recur (next remaining)
                 (conj selected-kinds result-kind)
                 (conj selected row)))))))

(defn- select-ranked-results
  [rows limit]
  (let [rows (sort-by ranked-result-sort-key rows)]
    (if (or (= Long/MAX_VALUE limit)
            (<= (count rows) limit))
      (vec rows)
      (let [reserve-limit (min default-grep-reserve-candidates
                               (max 0 (quot limit 4)))
            source-reserve-limit (min default-source-file-reserve-candidates
                                      (max 0 (quot limit 8)))
            kind-reserve-limit (if (<= limit default-limit)
                                 (min default-result-kind-reserve-candidates
                                      (max 0 (- limit
                                                reserve-limit
                                                source-reserve-limit)))
                                 0)
            primary-limit (max 0 (- limit
                                    reserve-limit
                                    source-reserve-limit
                                    kind-reserve-limit))
            primary (vec (take primary-limit rows))
            primary-ids (set (map :target-id primary))
            reserve (ranked-grep-reserve-rows rows primary-ids reserve-limit)
            selected-ids (set (map :target-id (concat primary reserve)))
            source-reserve (ranked-source-file-reserve-rows
                            rows
                            selected-ids
                            source-reserve-limit)
            selected-ids (set (map :target-id
                                   (concat primary reserve source-reserve)))
            kind-reserve (ranked-result-kind-reserve-rows
                          rows
                          selected-ids
                          (set (keep :result-kind
                                     (concat primary reserve source-reserve)))
                          kind-reserve-limit)
            selected-ids (set (map :target-id
                                   (concat primary
                                           reserve
                                           source-reserve
                                           kind-reserve)))
            fill (->> rows
                      (remove #(contains? selected-ids (:target-id %)))
                      (take (max 0 (- limit
                                      (count primary)
                                      (count reserve)
                                      (count source-reserve)
                                      (count kind-reserve)))))]
        (->> (concat primary reserve source-reserve kind-reserve fill)
             (sort-by ranked-result-sort-key)
             vec)))))

(defn- diversity-key
  [row]
  [(or (:path row) (:target-id row))
   (:target-kind row)
   (:result-kind row)])

(defn- select-next-diverse-row
  [rows seen]
  (or (first (keep-indexed (fn [idx row]
                             (when-not (contains? seen (diversity-key row))
                               [idx row]))
                           rows))
      (when (seq rows)
        [0 (first rows)])))

(defn- remove-at
  [rows idx]
  (vec (concat (subvec rows 0 idx)
               (subvec rows (inc idx)))))

(defn- diversity-rerank-results
  [rows n]
  (let [limit (long (or n 0))]
    (if (or (<= limit 1) (<= (count rows) 1))
      rows
      (let [rows (vec (map-indexed (fn [idx row]
                                     (assoc row
                                            :pre-diversity-rank (inc idx)
                                            :pre-diversity-score (:score row)))
                                   rows))
            head (first rows)]
        (loop [remaining (subvec rows 1)
               seen #{(diversity-key head)}
               out [head]]
          (if (or (empty? remaining)
                  (<= (min limit (count rows)) (count out)))
            (vec (concat out remaining))
            (let [[idx row] (select-next-diverse-row remaining seen)]
              (recur (remove-at remaining idx)
                     (conj seen (diversity-key row))
                     (conj out row)))))))))

(defn- ranked-candidates
  [{:keys [query-text query-tokens docs lexical semantic semantic-role-scores fts grep neighbor-scores same-label-scores
           retriever limit fusion-strategy fusion-k diversity-rerank-limit fts-weight]}]
  (let [query (str/lower-case (str/trim query-text))
        query-path-shaped? (path-shaped-query? query)
        query-token-set (set query-tokens)
        path-token-data (path-token-candidate-data query-token-set
                                                   docs
                                                   default-path-token-candidates)
        path-token-match-counts (:match-counts path-token-data)
        exact (exact-score-map query
                               query-token-set
                               query-path-shaped?
                               path-token-match-counts
                               docs)
        fts (or fts {})
        grep (or grep {})
        neighbor-scores (or neighbor-scores {})
        same-label-scores (or same-label-scores {})
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
        fts-candidates (if (not= :semantic retriever)
                         (into (set (top-ids fts default-lexical-candidates))
                               (top-ids-by-kind fts
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
        source-maps (source-maps-for-retriever
                     retriever
                     {:semantic semantic
                      :lexical lexical
                      :fts fts
                      :grep grep
                      :graph neighbor-scores
                      :same-label same-label-scores
                      :exact exact})
        fusion-strategy (normalized-fusion-strategy fusion-strategy)
        fusion-k (long (or fusion-k fusion/default-rrf-k))
        weights (fusion-weights retriever {:fts-weight fts-weight})
        fused-scores (fuse-score-maps fusion-strategy
                                      source-maps
                                      weights
                                      fusion-k)
        memberships (source-memberships source-maps)
        candidates (case retriever
                     :semantic (-> semantic-candidates
                                   (into exact-path-candidates)
                                   (into path-token-candidates)
                                   (into same-label-candidates))
                     :lexical (-> lexical-candidates
                                  (into fts-candidates)
                                  (into grep-candidates)
                                  (into exact-path-candidates)
                                  (into path-token-candidates)
                                  (into (keys neighbor-scores))
                                  (into same-label-candidates))
                     (-> semantic-candidates
                         (into lexical-candidates)
                         (into fts-candidates)
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
                             fts-score (double (get fts (:target-id doc) 0.0))
                             grep-score (double (get grep (:target-id doc) 0.0))
                             graph-score (double (get neighbor-scores (:target-id doc) 0.0))
                             same-label-score (double (get same-label-scores (:target-id doc) 0.0))
                             exact-score (double (get exact (:target-id doc) 0.0))
                             total (double (get fused-scores (:target-id doc) 0.0))
                             components (cond-> {:semantic semantic-score
                                                 :lexical lexical-score
                                                 :fts fts-score
                                                 :grep grep-score
                                                 :graph graph-score
                                                 :sameLabel same-label-score
                                                 :exact exact-score}
                                          (seq (get semantic-role-scores (:target-id doc)))
                                          (assoc :semanticRoles
                                                 (get semantic-role-scores (:target-id doc))))]
                         (if (pos? total)
                           (add-ranked-result
                            rank-data
                            (-> doc
                                (assoc :result-kind (:target-kind doc)
                                       :score total
                                       :score-components components
                                       :retrieval-sources (vec (sort (get memberships (:target-id doc) #{})))
                                       :reason (cond
                                                 (pos? semantic-score) "embedding match"
                                                 (pos? lexical-score) "lexical match"
                                                 (pos? fts-score) "sqlite fts match"
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
                   docs)
        selected-ranked (select-ranked-results (:ranked rank-data) rank-limit)
        diversity-limit (long (or diversity-rerank-limit default-diversity-rerank-limit))
        ranked (if (pos? diversity-limit)
                 (diversity-rerank-results selected-ranked diversity-limit)
                 selected-ranked)]
    {:semantic-candidates semantic-candidates
     :lexical-candidates lexical-candidates
     :fts-candidates fts-candidates
     :grep-candidates grep-candidates
     :exact-path-candidates (set exact-path-candidates)
     :path-token-candidates (set path-token-candidates)
     :same-label-candidates same-label-candidates
     :candidates candidates
     :fusion-strategy fusion-strategy
     :fusion-source-counts (fusion/source-counts source-maps)
     :fusion-overlap-count (fusion/overlap-count source-maps)
     :fusion-source-weights weights
     :fts-weight (:fts weights)
     :fusion-k (when (= :rrf fusion-strategy) fusion-k)
     :candidate-facets (candidate-facets docs candidates memberships)
     :candidate-doc-count (:candidate-doc-count rank-data)
     :candidates-by-kind (:candidates-by-kind rank-data)
     :ranked-count (:ranked-count rank-data)
     :diversity-rerank? (pos? diversity-limit)
     :diversity-rerank-limit diversity-limit
     :ranked ranked}))

(defn- auto-lexical-short-circuit-reason
  [query-text docs]
  (let [query (str/lower-case (str/trim query-text))]
    (when (and (path-shaped-query? query)
               (some #(exact-path-mentioned? query (:path %)) docs))
      :exact-path-candidates)))

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
        progress-fn (:progress-fn opts)
        [corpus-data timings] (timed {}
                                     :load-search-docs-ms
                                     #(cached-search-docs xtdb scope progress-fn))
        base-docs (:docs corpus-data)
        [query-tokens timings] (timed timings :tokenize-ms #(text/tokenize query-text))
        [initial-grep-data timings] (timed timings
                                           :grep-search-ms
                                           #(literal-grep-data xtdb
                                                               query-tokens
                                                               base-docs
                                                               scope
                                                               (assoc opts
                                                                      :search-corpus-stats
                                                                      (:stats corpus-data))
                                                               initial-retriever))
        [transient-file-data timings] (timed timings
                                             :transient-file-candidates-ms
                                             #(transient-file-candidate-data
                                               xtdb
                                               query-text
                                               base-docs
                                               initial-grep-data
                                               scope))
        docs (into base-docs (:docs transient-file-data))
        [lexical timings] (timed timings :lexical-score-ms #(lexical-scores query-tokens docs))
        [fts-data timings] (let [data (vector-store/fts-score-data
                                       base-docs
                                       query-tokens
                                       (cond-> scope
                                         (:vector-index-path opts)
                                         (assoc :vector-index-path (:vector-index-path opts)
                                                :index-path (:vector-index-path opts))
                                         (contains? opts :sqlite-fts?)
                                         (assoc :sqlite-fts? (:sqlite-fts? opts))
                                         (contains? opts :fts?)
                                         (assoc :fts? (:fts? opts))
                                         (:target-kind opts)
                                         (assoc :target-kind (:target-kind opts))
                                         (:target-kinds opts)
                                         (assoc :target-kinds (:target-kinds opts))
                                         (:file-kind opts)
                                         (assoc :file-kind (:file-kind opts))
                                         (:file-kinds opts)
                                         (assoc :file-kinds (:file-kinds opts))
                                         (:fts-candidate-limit opts)
                                         (assoc :fts-candidate-limit (:fts-candidate-limit opts))
                                         progress-fn
                                         (assoc :progress-fn progress-fn)))]
                             [data (merge timings (:timings data))])
        fts (:scores fts-data)
        grep-data (rescore-grep-data initial-grep-data docs)
        grep (:scores grep-data)
        auto-semantic-eligible? (and (= :auto requested-retriever)
                                     embedding-client
                                     (#{:hybrid :semantic} initial-retriever))
        [auto-short-circuit-reason timings]
        (if auto-semantic-eligible?
          (timed timings
                 :exact-path-detection-ms
                 #(auto-lexical-short-circuit-reason query-text docs))
          [nil (assoc timings :exact-path-detection-ms 0)])
        auto-short-circuit? (boolean auto-short-circuit-reason)
        semantic-retriever? (and (#{:hybrid :semantic} initial-retriever)
                                 (not auto-short-circuit?))
        _ (when semantic-retriever?
            (emit-progress! progress-fn
                            (merge (select-keys scope [:project-id :repo-id])
                                   {:phase :semantic-search-start})))
        [semantic-data timings] (if semantic-retriever?
                                  (if embedding-client
                                    (let [data (vector-store/semantic-score-data
                                                xtdb
                                                docs
                                                (assoc scope
                                                       :provider (:provider embedding-client)
                                                       :model (:model embedding-client)
                                                       :candidate-limit default-semantic-candidates
                                                       :mode (:vector-store opts)
                                                       :vector-index-path (:vector-index-path opts)
                                                       :sqlite-vec-extension (:sqlite-vec-extension opts)
                                                       :embedding-role (:embedding-role opts)
                                                       :embedding-roles (:embedding-roles opts)
                                                       :target-kind (:target-kind opts)
                                                       :target-kinds (:target-kinds opts)
                                                       :file-kind (:file-kind opts)
                                                       :file-kinds (:file-kinds opts)
                                                       :vector-overfetch-factors (:vector-overfetch-factors opts)
                                                       :embed-query #(query-vector
                                                                      embedding-client
                                                                      query-text)))]
                                      [data (merge timings (:timings data))])
                                    (throw (ex-info "Semantic retrieval requires an embedding client."
                                                    {:retriever initial-retriever})))
                                  [{:scores {}
                                    :empty? true
                                    :instrumentation {:vector-store :none
                                                      :vector-store-fallback-reason :not-requested
                                                      :vector-candidates 0}}
                                   (assoc timings
                                          :load-embeddings-ms 0
                                          :query-embedding-ms 0
                                          :semantic-score-ms 0
                                          :vector-search-ms 0)])
        retriever (cond
                    auto-short-circuit? :lexical
                    (and (= :auto requested-retriever)
                         embedding-client
                         (:empty? semantic-data)) :lexical
                    :else initial-retriever)
        semantic (if (#{:hybrid :semantic} retriever)
                   (:scores semantic-data)
                   {})
        semantic-role-scores (if (#{:hybrid :semantic} retriever)
                               (:role-scores semantic-data)
                               {})
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
                                                                   (concat grep-seed-ids
                                                                           (top-ids fts default-seed-count)))
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
                                                          :semantic-role-scores semantic-role-scores
                                                          :fts fts
                                                          :grep grep
                                                          :neighbor-scores neighbor-scores
                                                          :same-label-scores (:same-label-scores seed-data)
                                                          :retriever retriever
                                                          :fusion-strategy (:fusion-strategy opts)
                                                          :fusion-k (:fusion-k opts)
                                                          :fts-weight (:fts-weight opts)
                                                          :diversity-rerank-limit (:diversity-rerank-limit opts)
                                                          :limit limit}))
        ranked (:ranked ranked-data)
        instrumentation (merge
                         timings
                         (:instrumentation grep-data)
                         (:instrumentation transient-file-data)
                         (:instrumentation fts-data)
                         (:instrumentation semantic-data)
                         (adjacency-query-plan xtdb
                                               (:seed-ids seed-data)
                                               edges
                                               (:load-edges-ms timings))
                         {:search-total-ms (elapsed-ms started)
                          :search-corpus-cache (:cache-status corpus-data)
                          :search-corpus-generation (:cache-generation corpus-data)
                          :search-docs (count docs)
                          :durable-search-docs (count base-docs)
                          :search-docs-by-kind (rows-by-kind docs)
                          :auto-lexical-short-circuit? auto-short-circuit?
                          :auto-lexical-short-circuit-reason (or auto-short-circuit-reason
                                                                 :none)
                          :query-tokens (count query-tokens)
                          :lexical-positive (count lexical)
                          :fts-positive (count fts)
                          :grep-positive (count grep)
                          :semantic-positive (count semantic)
                          :seed-count (count (:seed-ids seed-data))
                          :grep-seed-count (count (:grep-seed-ids seed-data))
                          :fts-seed-count (count (top-ids fts default-seed-count))
                          :same-label-seed-count (count (:same-label-ids seed-data))
                          :same-label-doc-candidates (count (:same-label-scores seed-data))
                          :graph-edges-loaded (count edges)
                          :neighbor-count (count neighbor-scores)
                          :grep-candidates (count (:grep-candidates ranked-data))
                          :fts-candidates (count (:fts-candidates ranked-data))
                          :exact-path-candidates (count (:exact-path-candidates ranked-data))
                          :path-token-candidates (count (:path-token-candidates ranked-data))
                          :candidate-count (count (:candidates ranked-data))
                          :candidates-by-kind (:candidates-by-kind ranked-data)
                          :candidate-facets (:candidate-facets ranked-data)
                          :retrieval-diagnostics {:durable-search-docs (count base-docs)
                                                  :fts-indexed-search-docs (long (or (get-in fts-data
                                                                                             [:instrumentation
                                                                                              :fts-indexed-search-docs])
                                                                                     0))
                                                  :fts-missing-search-docs (max 0
                                                                                (- (count base-docs)
                                                                                   (long (or (get-in fts-data
                                                                                                     [:instrumentation
                                                                                                      :fts-indexed-search-docs])
                                                                                             0))))
                                                  :fts-stale-candidates (long (or (get-in fts-data
                                                                                          [:instrumentation
                                                                                           :fts-stale-candidates])
                                                                                  0))
                                                  :vector-stale-candidates (long (or (get-in semantic-data
                                                                                             [:instrumentation
                                                                                              :vector-stale-candidates])
                                                                                     0))
                                                  :vector-post-filter-candidates (long (or (get-in semantic-data
                                                                                                   [:instrumentation
                                                                                                    :vector-post-filter-candidates])
                                                                                           (count semantic)))}
                          :fusion-strategy (:fusion-strategy ranked-data)
                          :fusion-source-counts (:fusion-source-counts ranked-data)
                          :fusion-source-weights (:fusion-source-weights ranked-data)
                          :fusion-overlap-count (:fusion-overlap-count ranked-data)
                          :fts-weight (:fts-weight ranked-data)
                          :fusion-k (or (:fusion-k ranked-data) :none)
                          :diversity-rerank? (:diversity-rerank? ranked-data)
                          :diversity-rerank-limit (:diversity-rerank-limit ranked-data)
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
    (emit-progress! progress-fn
                    (merge (select-keys scope [:project-id :repo-id])
                           {:phase :search-complete
                            :cache-status (:cache-status corpus-data)
                            :result-count (count ranked)
                            :elapsed-ms (:search-total-ms instrumentation)}))
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
