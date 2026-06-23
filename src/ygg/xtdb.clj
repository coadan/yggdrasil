(ns ygg.xtdb
  "XTDB storage boundary for Yggdrasil."
  (:require [ygg.metadata :as metadata]
            [ygg.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [java.io Closeable]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.util Date]))

(def default-storage-path ".dev/ygg/xtdb")

(defn storage-path
  "Return configured XTDB storage path."
  []
  (or (System/getenv "YGG_XTDB_PATH")
      default-storage-path))

(defn- ensure-dir!
  [path]
  (let [file (io/file path)]
    (.mkdirs file)
    (.getPath file)))

(defn node-opts
  "Return XTDB local storage node opts."
  [path]
  (let [root (ensure-dir! path)]
    {:log [:local {:path (ensure-dir! (io/file root "log"))}]
     :storage [:local {:path (ensure-dir! (io/file root "storage"))}]}))

(defn- with-storage-lock
  [path f]
  (let [root (ensure-dir! path)
        lock-path (.toPath (io/file root ".ygg.lock"))
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (with-open [channel (FileChannel/open lock-path options)]
      (let [lock (.lock channel)]
        (try
          (f)
          (finally
            (.release lock)))))))

(defn- await-ready!
  [node]
  (xt/status node)
  node)

(defn start-node
  "Start a local XTDB node."
  ([] (start-node (storage-path)))
  ([path]
   {:node (xtn/start-node (node-opts path))
    :path path}))

(defn stop-node!
  "Close a node handle."
  [{:keys [node]}]
  (when (instance? Closeable node)
    (.close ^Closeable node)))

(defn with-node
  "Open XTDB node at path, call f, and close the node."
  [path f]
  (with-storage-lock
    path
    (fn []
      (let [xtdb (start-node path)]
        (try
          (await-ready! (:node xtdb))
          (f xtdb)
          (finally
            (stop-node! xtdb)))))))

(def tables
  {:projects :ygg/projects
   :repos :ygg/repos
   :source-snapshots :ygg/source-snapshots
   :index-runs :ygg/index-runs
   :runs :ygg/runs
   :files :ygg/files
   :nodes :ygg/nodes
   :edges :ygg/edges
   :chunks :ygg/chunks
   :file-facts :ygg/file-facts
   :diagnostics :ygg/index-diagnostics
   :search-docs :ygg/search-docs
   :embeddings :ygg/embeddings
   :query-runs :ygg/query-runs
   :metadata-defs :ygg/metadata-defs
   :metadata :ygg/metadata
   :graph-views :ygg/graph-views
   :graph-cursors :ygg/graph-cursors
   :evidence :ygg/evidence
   :system-evidence :ygg/system-evidence
   :system-nodes :ygg/system-nodes
   :system-edges :ygg/system-edges
   :activity-items :ygg/activity-items
   :activity-events :ygg/activity-events})

(defn instant
  "Return java.util.Date instant value for XTDB temporal options."
  [value]
  (cond
    (nil? value) nil
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (instance? ZonedDateTime value) (Date/from (.toInstant ^ZonedDateTime value))
    (instance? OffsetDateTime value) (Date/from (.toInstant ^OffsetDateTime value))
    (string? value) (Date/from (Instant/parse value))
    :else (throw (ex-info "Unsupported instant value."
                          {:value value
                           :class (some-> value class .getName)}))))

(defn read-context
  "Normalize temporal read options.

  `:valid-at` is source/project valid time and maps to XTDB `:current-time`.
  `:snapshot-token` is the exact XTDB read basis when available. `:known-at` is
  retained as caller intent, but XTDB's Clojure query API needs a snapshot token
  for exact system-time reads."
  ([] (read-context {}))
  ([opts]
   (let [{:keys [valid-at known-at snapshot-token current-time] :as opts} (or opts {})]
     (cond-> (select-keys opts [:project-id :repo-id :known-at])
       valid-at (assoc :valid-at (instant valid-at))
       known-at (assoc :known-at (instant known-at))
       snapshot-token (assoc :snapshot-token snapshot-token)
       current-time (assoc :current-time (instant current-time))))))

(defn- query-options
  [ctx]
  (let [raw-ctx (or ctx {})
        ctx (read-context raw-ctx)
        current-time (or (:current-time ctx) (:valid-at ctx))]
    (cond-> (select-keys raw-ctx [:args
                                  :await-token
                                  :default-tz
                                  :explain?
                                  :key-fn
                                  :tx-timeout])
      current-time (assoc :current-time current-time)
      (:snapshot-token ctx) (assoc :snapshot-token (:snapshot-token ctx)))))

(defn q
  "Run XTQL query."
  ([xtdb query] (q xtdb query {}))
  ([{:keys [node]} query ctx]
   (let [opts (query-options ctx)
         args (:args opts)
         query (if (and (string? query) (seq args))
                 (into [query] args)
                 query)]
     (xt/q node query (dissoc opts :args)))))

(defn all-rows
  "Return all rows from table."
  ([xtdb table] (all-rows xtdb table {}))
  ([xtdb table ctx]
   (q xtdb (list 'from table '[*]) ctx)))

(defn xtdb-handle?
  "Return true when value is a Yggdrasil XTDB handle."
  [xtdb]
  (and (map? xtdb) (contains? xtdb :node)))

(defn- clean-constraints
  [constraints]
  (->> constraints
       (remove (comp nil? val))
       (into {})))

(defn- constraints-match?
  [constraints row]
  (every? (fn [[field value]]
            (= value (get row field)))
          constraints))

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

(declare fallback-constrained-rows rows-by-field)

(def ^:private fallback-miss
  ::fallback-miss)

(defn- try-fallback-read
  [f]
  (try
    (vec (f))
    (catch Exception _
      fallback-miss)))

(defn- fallback-q
  [xtdb query ctx]
  (try
    (q xtdb query ctx)
    (catch clojure.lang.ArityException e
      (if (seq ctx)
        (throw e)
        (q xtdb query)))))

(defn- fallback-rows-by-field
  [xtdb table field value ctx]
  (try
    (rows-by-field xtdb table field value ctx)
    (catch clojure.lang.ArityException e
      (if (seq ctx)
        (throw e)
        (rows-by-field xtdb table field value)))))

(defn- relation-scope-query
  [table scope-field]
  (list 'fn
        ['scope-value 'relation-value]
        (list 'from table [{scope-field 'scope-value
                            :relation 'relation-value}
                           '*])))

(defn- preferred-fallback-scope
  [constraints]
  (some (fn [field]
          (when (contains? constraints field)
            [field (get constraints field)]))
        [:repo-id :project-id :file-id :path :target-id :source-id :xt/id :relation]))

(defn rows-by-fields
  "Return rows from table where every field in constraints equals its value."
  ([xtdb table constraints] (rows-by-fields xtdb table constraints {}))
  ([xtdb table constraints ctx]
   (let [constraints (->> (clean-constraints constraints)
                          (sort-by (comp str key))
                          vec)]
     (if (seq constraints)
       (let [terms (map-indexed (fn [idx [field value]]
                                  [field (symbol (str "v" idx)) value])
                                constraints)
             args (mapv second terms)
             bindings (into {}
                            (map (fn [[field arg _]]
                                   [field arg]))
                            terms)
             values (mapv (fn [[_ _ value]] value) terms)]
         (q xtdb
            (into [(list 'fn args (list 'from table [bindings '*]))]
                  values)
            ctx))
       (all-rows xtdb table ctx)))))

(defn rows-by-field
  "Return rows from table where field equals value."
  ([xtdb table field value] (rows-by-field xtdb table field value {}))
  ([xtdb table field value ctx]
   (rows-by-fields xtdb table {field value} ctx)))

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

(defn- field-values-query
  ([table field values constraints return-fields]
   (field-values-query table field values constraints return-fields nil nil))
  ([table field values constraints return-fields min-field min-value]
   (let [value-rows (mapv (fn [value] {:match-value value}) values)
         constraints (->> (dissoc (clean-constraints constraints) field)
                          (sort-by (comp str key))
                          vec)
         terms (map-indexed (fn [idx [constraint-field value]]
                              [constraint-field (symbol (str "v" idx)) value])
                            constraints)
         args (mapv second terms)
         min-filter? (and min-field (some? min-value))
         min-value-symbol 'min-value
         bound-fields (set (cons field (map first terms)))
         base-bindings (cond-> (into {field 'match-value}
                                     (map (fn [[constraint-field arg _]]
                                            [constraint-field arg]))
                                     terms)
                         (and min-filter?
                              (not (contains? bound-fields min-field)))
                         (assoc min-field 'min-field-value))
         return-fields (vec (distinct return-fields))
         return-bindings (->> return-fields
                              (remove #(contains? base-bindings %))
                              (map-indexed (fn [idx return-field]
                                             [return-field
                                              (symbol (str "ret" idx))]))
                              (into {}))
         bindings (merge base-bindings return-bindings)
         return-projections (into {}
                                  (map (fn [return-field]
                                         [return-field
                                          (get bindings return-field)]))
                                  return-fields)
         values (cond-> (mapv (fn [[_ _ value]] value) terms)
                  min-filter? (conj min-value))
         args (cond-> args
                min-filter? (conj min-value-symbol))
         pipeline (cond-> [(list 'unify
                                 (list 'rel value-rows '[match-value])
                                 (list 'from table [bindings]))]
                    min-filter? (conj (list 'where
                                            (list '>=
                                                  (get bindings min-field)
                                                  min-value-symbol)))
                    true (conj (list 'return return-projections)))]
     (into [(list 'fn
                  args
                  (apply list '-> pipeline))]
           values))))

(defn- tuple-values-query
  [table tuple-fields tuples constraints return-fields]
  (let [match-keys (mapv #(keyword (str "match" %)) (range (count tuple-fields)))
        match-symbols (mapv #(symbol (str "match" %)) (range (count tuple-fields)))
        tuple-rows (mapv (fn [tuple]
                           (into {}
                                 (map (fn [[match-key field]]
                                        [match-key (get tuple field)]))
                                 (map vector match-keys tuple-fields)))
                         tuples)
        constraints (->> (apply dissoc
                                (clean-constraints constraints)
                                tuple-fields)
                         (sort-by (comp str key))
                         vec)
        terms (map-indexed (fn [idx [constraint-field value]]
                             [constraint-field (symbol (str "v" idx)) value])
                           constraints)
        args (mapv second terms)
        base-bindings (into (zipmap tuple-fields match-symbols)
                            (map (fn [[constraint-field arg _]]
                                   [constraint-field arg]))
                            terms)
        return-fields (vec (distinct return-fields))
        return-bindings (->> return-fields
                             (remove #(contains? base-bindings %))
                             (map-indexed (fn [idx return-field]
                                            [return-field
                                             (symbol (str "ret" idx))]))
                             (into {}))
        bindings (merge base-bindings return-bindings)
        return-projections (into {}
                                 (map (fn [return-field]
                                        [return-field
                                         (get bindings return-field)]))
                                 return-fields)
        values (mapv (fn [[_ _ value]] value) terms)]
    (into [(list 'fn
                 args
                 (list '->
                       (list 'unify
                             (list 'rel tuple-rows match-symbols)
                             (list 'from table [bindings]))
                       (list 'return return-projections)))]
          values)))

(defn- normalize-match-tuple
  [tuple-fields tuple]
  (let [tuple (cond
                (map? tuple) tuple
                (sequential? tuple) (zipmap tuple-fields tuple)
                :else nil)
        values (mapv #(get tuple %) tuple-fields)]
    (when-not (or (nil? tuple)
                  (some nil? values))
      (zipmap tuple-fields values))))

(defn rows-with-field-values
  "Return rows where field equals any value.

  Real XTDB handles use XTQL `rel`/`unify` so a bounded value set is pushed into
  one table read. Optional `min-field`/`min-value` adds a numeric lower-bound
  predicate to the same read. `return-fields` must be explicit because XTDB 2.x
  disallows `*` projection inside `unify`."
  [xtdb {:keys [table field values constraints return-fields read-context min-field min-value]
         :or {constraints {}
              read-context {}}}]
  (let [values (->> (seq values) (remove nil?) distinct vec)]
    (cond
      (empty? values)
      []

      (xtdb-handle? xtdb)
      (q xtdb
         (field-values-query table field values constraints return-fields min-field min-value)
         read-context)

      :else
      (let [value-set (set values)]
        (->> (fallback-constrained-rows xtdb table constraints read-context)
             (filter #(contains? value-set (get % field)))
             (filter (fn [row]
                       (or (nil? min-field)
                           (nil? min-value)
                           (<= (double min-value)
                               (double (get row min-field)))))))))))

(defn rows-with-field-tuples
  "Return rows where tuple-fields equal any supplied tuple.

  This is the multi-column form of `rows-with-field-values`, intended for exact
  bounded lookups such as current embedding `(target-id, input-sha)` pairs. Real
  XTDB handles use XTQL `rel`/`unify` with explicit projections."
  [xtdb {:keys [table tuple-fields tuples constraints return-fields read-context]
         :or {constraints {}
              read-context {}}}]
  (let [tuple-fields (vec tuple-fields)
        tuples (->> tuples
                    (keep #(normalize-match-tuple tuple-fields %))
                    distinct
                    vec)
        tuple-key (fn [row] (mapv #(get row %) tuple-fields))]
    (cond
      (or (empty? tuple-fields) (empty? tuples))
      []

      (xtdb-handle? xtdb)
      (q xtdb
         (tuple-values-query table tuple-fields tuples constraints return-fields)
         read-context)

      :else
      (let [tuple-set (set (map tuple-key tuples))]
        (->> (fallback-constrained-rows xtdb table constraints read-context)
             (filter #(contains? tuple-set (tuple-key %))))))))

(defn edge-rows-touching-ids
  "Return source graph edges whose source or target is in ids.

  This is the graph-adjacency read path for search expansion. It performs two
  bounded XTQL reads for real XTDB handles, one for source ids and one for target
  ids, instead of one read per seed id."
  ([xtdb ids constraints] (edge-rows-touching-ids xtdb ids constraints {}))
  ([xtdb ids constraints ctx]
   (->> (concat (rows-with-field-values xtdb
                                        {:table (:edges tables)
                                         :field :source-id
                                         :values ids
                                         :constraints constraints
                                         :return-fields edge-row-query-fields
                                         :read-context ctx})
                (rows-with-field-values xtdb
                                        {:table (:edges tables)
                                         :field :target-id
                                         :values ids
                                         :constraints constraints
                                         :return-fields edge-row-query-fields
                                         :read-context ctx}))
        (distinct-by :xt/id))))

(defn- fallback-all-rows
  [xtdb table ctx]
  (try
    (all-rows xtdb table ctx)
    (catch clojure.lang.ArityException e
      (if (seq ctx)
        (throw e)
        (all-rows xtdb table)))))

(defn- fallback-constrained-rows
  [xtdb table constraints ctx]
  (let [scoped-relation-rows (fn [scope-field scope-value relation]
                               (try-fallback-read
                                #(fallback-q xtdb
                                             [(relation-scope-query table scope-field)
                                              scope-value
                                              relation]
                                             ctx)))
        scoped-rows (fn [field value]
                      (try-fallback-read
                       #(fallback-rows-by-field xtdb table field value ctx)))
        rows (or (when-let [relation (:relation constraints)]
                   (or (when-let [repo-id (:repo-id constraints)]
                         (let [rows (scoped-relation-rows :repo-id repo-id relation)]
                           (when-not (= fallback-miss rows) rows)))
                       (when-let [project-id (:project-id constraints)]
                         (let [rows (scoped-relation-rows :project-id project-id relation)]
                           (when-not (= fallback-miss rows) rows)))))
                 (when-let [[field value] (preferred-fallback-scope constraints)]
                   (let [rows (scoped-rows field value)]
                     (when-not (= fallback-miss rows) rows)))
                 (fallback-all-rows xtdb table ctx))]
    (filter #(constraints-match? constraints %) rows)))

(defn constrained-rows
  "Return rows matching equality constraints.

  Uses XTQL constraints for real XTDB handles. Non-XTDB values are test doubles:
  use narrow legacy stubs where available, then apply the same equality filter."
  ([xtdb table constraints] (constrained-rows xtdb table constraints {}))
  ([xtdb table constraints ctx]
   (let [constraints (clean-constraints constraints)]
     (if (and (seq constraints) (xtdb-handle? xtdb))
       (rows-by-fields xtdb table constraints ctx)
       (fallback-constrained-rows xtdb table constraints ctx)))))

(defn- sql-name-fragment
  [value]
  (-> (str value)
      (str/replace "." "$")
      (str/replace "-" "_")))

(defn- sql-table-name
  [table]
  (if-let [table-ns (namespace table)]
    (str (sql-name-fragment table-ns)
         "."
         (sql-name-fragment (name table)))
    (sql-name-fragment (name table))))

(defn- sql-quote-ident
  [value]
  (str "\""
       (str/replace (str value) "\"" "\"\"")
       "\""))

(defn- sql-column-name
  [field]
  (sql-quote-ident
   (if (= :xt/id field)
     "_id"
     (str (some-> (namespace field)
                  sql-name-fragment
                  (str "$"))
          (sql-name-fragment (name field))))))

(defn- token-like-pattern
  [token]
  (str "%"
       (-> (str token)
           str/lower-case
           (str/replace "\\" "\\\\")
           (str/replace "%" "\\%")
           (str/replace "_" "\\_"))
       "%"))

(defn- prefix-like-pattern
  [prefix]
  (str (-> (str prefix)
           (str/replace "\\" "\\\\")
           (str/replace "%" "\\%")
           (str/replace "_" "\\_"))
       "/%"))

(defn- token-match-row?
  [fields tokens row]
  (let [tokens (set tokens)
        text (->> fields
                  (keep #(get row %))
                  (map str)
                  (str/join "\n")
                  str/lower-case)]
    (some #(str/includes? text %) tokens)))

(defn- string-prefix-match-row?
  [field prefixes row]
  (let [value (some-> (get row field) str)]
    (some (fn [prefix]
            (or (= value prefix)
                (str/starts-with? value (str prefix "/"))))
          prefixes)))

(defn- token-match-sql
  [fields tokens]
  (let [pairs (for [field fields
                    token tokens]
                [field token])]
    {:where (when (seq pairs)
              (str "("
                   (str/join
                    " OR "
                    (map (fn [[field _]]
                           (str "LOWER(CAST("
                                (sql-column-name field)
                                " AS VARCHAR)) LIKE ? ESCAPE '\\\\'"))
                         pairs))
                   ")"))
     :args (mapv (comp token-like-pattern second) pairs)}))

(defn- string-prefix-sql
  [field prefixes]
  (let [prefixes (vec prefixes)
        column (sql-column-name field)]
    {:where (when (seq prefixes)
              (str "("
                   (str/join
                    " OR "
                    (repeat (count prefixes)
                            (str "(" column " = ? OR "
                                 column
                                 " LIKE ? ESCAPE '\\\\')")))
                   ")"))
     :args (into [] (mapcat #(vector % (prefix-like-pattern %))) prefixes)}))

(defn- equality-sql
  [constraints]
  {:where (mapv (fn [[field _]]
                  (str (sql-column-name field) " = ?"))
                constraints)
   :args (mapv val constraints)})

(defn- sql-result-field-name
  [field]
  (cond
    (= :xt/id field) "_id"
    (namespace field) (str (namespace field) "/" (name field))
    :else (name field)))

(defn- select-sql
  [return-fields]
  (if (seq return-fields)
    (->> return-fields
         distinct
         (map (fn [field]
                (str (sql-column-name field)
                     " AS "
                     (sql-quote-ident (sql-result-field-name field)))))
         (str/join ", "))
    "*"))

(defn- normalize-projected-sql-row
  [return-fields row]
  (reduce (fn [row field]
            (let [field-name (sql-result-field-name field)
                  field-key (keyword field-name)
                  missing ::missing
                  value (cond
                          (contains? row field) (get row field)
                          (contains? row field-key) (get row field-key)
                          (contains? row field-name) (get row field-name)
                          (and (= :xt/id field)
                               (contains? row :_id)) (:_id row)
                          (and (= :xt/id field)
                               (contains? row "_id")) (get row "_id")
                          :else missing)]
              (if (= missing value)
                row
                (assoc row field value))))
          row
          return-fields))

(defn- count-row-value
  [row]
  (long (or (:row-count row)
            (:row_count row)
            (get row "row_count")
            (get row "row-count")
            (first (vals row))
            0)))

(defn- order-by-sql
  [order-fields]
  (when (seq order-fields)
    (str " ORDER BY "
         (str/join ", "
                   (map #(str (sql-column-name %) " ASC NULLS FIRST")
                        order-fields)))))

(defn- limited-sql
  [limit]
  (when limit
    " LIMIT ?"))

(defn- limited-args
  [args limit]
  (cond-> (vec args)
    limit (conj (long limit))))

(defn- ordered-query
  [table constraints order-fields limit return-fields]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {where-clauses :where args :args} (equality-sql constraints)]
    {:sql (str "SELECT "
               (select-sql return-fields)
               " FROM "
               (sql-table-name table)
               (when (seq where-clauses)
                 (str " WHERE " (str/join " AND " where-clauses)))
               (order-by-sql order-fields)
               (limited-sql limit))
     :args (limited-args args limit)}))

(defn- min-field-query
  [table field min-value constraints return-fields]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {where-clauses :where args :args} (equality-sql constraints)
        where-clauses (conj where-clauses
                            (str (sql-column-name field) " >= ?"))]
    {:sql (str "SELECT "
               (select-sql return-fields)
               " FROM "
               (sql-table-name table)
               " WHERE "
               (str/join " AND " where-clauses))
     :args (conj (vec args) min-value)}))

(defn- active-unless-false-sql
  []
  (let [field (sql-column-name :active?)]
    (str "(" field " IS NULL OR " field " <> FALSE)")))

(defn- count-query
  [table constraints active-unless-false?]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {constraint-where :where constraint-args :args} (equality-sql constraints)
        where-clauses (cond-> constraint-where
                        active-unless-false? (conj (active-unless-false-sql)))]
    {:sql (str "SELECT COUNT(*) AS row_count FROM "
               (sql-table-name table)
               (when (seq where-clauses)
                 (str " WHERE " (str/join " AND " where-clauses))))
     :args constraint-args}))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn count-rows
  "Return the count of rows matching equality constraints.

  Real XTDB handles use SQL count pushdown so readiness/schema summaries do not
  hydrate every row. Test doubles fall back through constrained row reads."
  ([xtdb table constraints] (count-rows xtdb table constraints {}))
  ([xtdb table constraints ctx]
   (if (xtdb-handle? xtdb)
     (let [{:keys [sql args]} (count-query table constraints false)]
       (count-row-value (first (q xtdb sql (assoc ctx :args args)))))
     (count (constrained-rows xtdb table constraints ctx)))))

(defn ordered-rows
  "Return rows matching equality constraints, ordered and optionally limited.

  Real XTDB handles use SQL `ORDER BY`/`LIMIT` with explicit projections.
  Test doubles fall back through constrained row reads and the same mechanical
  sort/limit."
  [xtdb {:keys [table constraints order-fields limit return-fields read-context]
         :or {constraints {}
              order-fields []
              read-context {}}}]
  (cond
    (and limit (not (pos? (long limit))))
    []

    (xtdb-handle? xtdb)
    (let [{:keys [sql args]} (ordered-query table
                                            constraints
                                            (vec order-fields)
                                            limit
                                            return-fields)]
      (map #(normalize-projected-sql-row return-fields %)
           (q xtdb sql (assoc read-context :args args))))

    :else
    (let [rows (constrained-rows xtdb
                                 table
                                 constraints
                                 read-context)
          rows (if (seq order-fields)
                 (sort-by (apply juxt order-fields) rows)
                 rows)
          rows (if limit
                 (take limit rows)
                 rows)]
      (if (seq return-fields)
        (map #(select-keys % return-fields) rows)
        rows))))

(defn rows-with-min-field-value
  "Return rows matching equality constraints where numeric `field` is >= value.

  Real XTDB handles use SQL comparison pushdown with explicit projections. Test
  doubles fall back through constrained row reads and the same numeric compare."
  [xtdb {:keys [table field min-value constraints return-fields read-context]
         :or {constraints {}
              read-context {}}}]
  (if (xtdb-handle? xtdb)
    (let [{:keys [sql args]} (min-field-query table
                                              field
                                              min-value
                                              constraints
                                              return-fields)]
      (map #(normalize-projected-sql-row return-fields %)
           (q xtdb sql (assoc read-context :args args))))
    (let [rows (->> (constrained-rows xtdb
                                      table
                                      constraints
                                      read-context)
                    (filter #(<= (double min-value)
                                 (double (get % field)))))]
      (if (seq return-fields)
        (map #(select-keys % return-fields) rows)
        rows))))

(defn active-row-count
  "Return count of rows matching constraints where active? is not false."
  ([xtdb table constraints] (active-row-count xtdb table constraints {}))
  ([xtdb table constraints ctx]
   (if (xtdb-handle? xtdb)
     (let [{:keys [sql args]} (count-query table constraints true)]
       (count-row-value (first (q xtdb sql (assoc ctx :args args)))))
     (count (filter active-row?
                    (fallback-constrained-rows xtdb
                                               table
                                               (clean-constraints constraints)
                                               ctx))))))

(defn- count-row-field-value
  ([row] (count-row-field-value row "value"))
  ([row alias]
   (let [alias (str alias)
         alias-key (keyword alias)
         upper-alias (str/upper-case alias)]
     (cond
       (contains? row alias-key) (get row alias-key)
       (contains? row alias) (get row alias)
       (contains? row upper-alias) (get row upper-alias)
       :else nil))))

(defn- field-count-row
  [row]
  {:value (count-row-field-value row)
   :count (count-row-value row)})

(defn- counts-by-field-query
  [table field constraints active-unless-false?]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {constraint-where :where constraint-args :args} (equality-sql constraints)
        where-clauses (cond-> constraint-where
                        active-unless-false? (conj (active-unless-false-sql)))
        field-sql (sql-column-name field)]
    {:sql (str "SELECT "
               field-sql
               " AS value, COUNT(*) AS row_count FROM "
               (sql-table-name table)
               (when (seq where-clauses)
                 (str " WHERE " (str/join " AND " where-clauses)))
               " GROUP BY "
               field-sql)
     :args constraint-args}))

(defn- counts-by-fields-query
  [table fields constraints active-unless-false?]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {constraint-where :where constraint-args :args} (equality-sql constraints)
        where-clauses (cond-> constraint-where
                        active-unless-false? (conj (active-unless-false-sql)))
        field-sqls (mapv sql-column-name fields)
        select-fields (->> field-sqls
                           (map-indexed (fn [idx field-sql]
                                          (str field-sql " AS value" idx)))
                           (str/join ", "))]
    {:sql (str "SELECT "
               select-fields
               ", COUNT(*) AS row_count FROM "
               (sql-table-name table)
               (when (seq where-clauses)
                 (str " WHERE " (str/join " AND " where-clauses)))
               " GROUP BY "
               (str/join ", " field-sqls))
     :args constraint-args}))

(defn- counts-by-any-field-query
  [table fields constraints active-unless-false?]
  (let [constraints (->> (clean-constraints constraints)
                         (sort-by (comp str key))
                         vec)
        {constraint-where :where constraint-args :args} (equality-sql constraints)
        where-clauses (cond-> constraint-where
                        active-unless-false? (conj (active-unless-false-sql)))
        table-sql (sql-table-name table)
        select-sql (fn [field]
                     (str "SELECT "
                          (sql-column-name field)
                          " AS value FROM "
                          table-sql
                          (when (seq where-clauses)
                            (str " WHERE " (str/join " AND " where-clauses)))))]
    {:sql (str "SELECT value, COUNT(*) AS row_count FROM ("
               (str/join " UNION ALL " (map select-sql fields))
               ") AS grouped_values WHERE value IS NOT NULL GROUP BY value")
     :args (vec (mapcat (constantly constraint-args) fields))}))

(defn- sorted-count-rows
  [rows]
  (->> rows
       (sort-by (juxt (comp - long :count)
                      (comp str :value)))
       vec))

(defn active-row-counts-by-field
  "Return active row counts grouped by field.

  Real XTDB handles use SQL GROUP BY so evidence summaries can report top
  mechanical distributions without hydrating every source graph row. Test
  doubles keep the same active-row semantics through constrained row reads."
  ([xtdb table field constraints] (active-row-counts-by-field
                                   xtdb
                                   table
                                   field
                                   constraints
                                   {}))
  ([xtdb table field constraints ctx]
   (let [rows (if (xtdb-handle? xtdb)
                (let [{:keys [sql args]} (counts-by-field-query table
                                                                field
                                                                constraints
                                                                true)]
                  (map field-count-row (q xtdb sql (assoc ctx :args args))))
                (->> (fallback-constrained-rows xtdb
                                                table
                                                (clean-constraints constraints)
                                                ctx)
                     (filter active-row?)
                     (map field)
                     frequencies
                     (map (fn [[value n]]
                            {:value value
                             :count n}))))]
     (sorted-count-rows rows))))

(defn active-row-counts-by-fields
  "Return active row counts grouped by fields.

  The return shape is `{:values {field value} :count n}` so callers can preserve
  mechanical grouped dimensions without hydrating source rows."
  ([xtdb table fields constraints] (active-row-counts-by-fields
                                    xtdb
                                    table
                                    fields
                                    constraints
                                    {}))
  ([xtdb table fields constraints ctx]
   (let [fields (vec (distinct fields))]
     (if (empty? fields)
       []
       (let [rows (if (xtdb-handle? xtdb)
                    (let [{:keys [sql args]} (counts-by-fields-query table
                                                                     fields
                                                                     constraints
                                                                     true)]
                      (map (fn [row]
                             {:values (into {}
                                            (map-indexed
                                             (fn [idx field]
                                               [field
                                                (count-row-field-value
                                                 row
                                                 (str "value" idx))]))
                                            fields)
                              :count (count-row-value row)})
                           (q xtdb sql (assoc ctx :args args))))
                    (->> (fallback-constrained-rows xtdb
                                                    table
                                                    (clean-constraints constraints)
                                                    ctx)
                         (filter active-row?)
                         (map #(select-keys % fields))
                         frequencies
                         (map (fn [[values n]]
                                {:values values
                                 :count n}))))]
         (->> rows
              (sort-by (juxt (comp - long :count)
                             (comp pr-str :values)))
              vec))))))

(defn row-counts-by-any-field
  "Return row counts grouped across values from any supplied field.

  This is useful for edge endpoint degree calculations where `source-id` and
  `target-id` both contribute to one mechanical count. Real XTDB handles use one
  SQL aggregate over a UNION ALL subquery; test doubles preserve the same shape
  through constrained row reads."
  ([xtdb table fields constraints] (row-counts-by-any-field
                                    xtdb
                                    table
                                    fields
                                    constraints
                                    {}))
  ([xtdb table fields constraints ctx]
   (let [fields (vec (distinct fields))]
     (cond
       (empty? fields) []

       (xtdb-handle? xtdb)
       (let [{:keys [sql args]} (counts-by-any-field-query table
                                                           fields
                                                           constraints
                                                           false)]
         (sorted-count-rows
          (map field-count-row (q xtdb sql (assoc ctx :args args)))))

       :else
       (->> (fallback-constrained-rows xtdb
                                       table
                                       (clean-constraints constraints)
                                       ctx)
            (mapcat (fn [row] (keep #(get row %) fields)))
            frequencies
            (map (fn [[value n]]
                   {:value value
                    :count n}))
            sorted-count-rows)))))

(defn active-row-counts-by-any-field
  "Return active row counts grouped across values from any supplied field."
  ([xtdb table fields constraints] (active-row-counts-by-any-field
                                    xtdb
                                    table
                                    fields
                                    constraints
                                    {}))
  ([xtdb table fields constraints ctx]
   (let [fields (vec (distinct fields))]
     (cond
       (empty? fields) []

       (xtdb-handle? xtdb)
       (let [{:keys [sql args]} (counts-by-any-field-query table
                                                           fields
                                                           constraints
                                                           true)]
         (sorted-count-rows
          (map field-count-row (q xtdb sql (assoc ctx :args args)))))

       :else
       (->> (fallback-constrained-rows xtdb
                                       table
                                       (clean-constraints constraints)
                                       ctx)
            (filter active-row?)
            (mapcat (fn [row] (keep #(get row %) fields)))
            frequencies
            (map (fn [[value n]]
                   {:value value
                    :count n}))
            sorted-count-rows)))))

(defn rows-matching-any-token
  "Return rows where any selected field contains any token.

  Real XTDB handles use SQL predicate pushdown with parameterized LIKE clauses.
  Test doubles fall back to constrained rows and the same mechanical substring
  predicate."
  ([xtdb table fields tokens constraints] (rows-matching-any-token
                                           xtdb
                                           table
                                           fields
                                           tokens
                                           constraints
                                           {}))
  ([xtdb table fields tokens constraints ctx]
   (rows-matching-any-token xtdb table fields tokens constraints ctx nil))
  ([xtdb table fields tokens constraints ctx return-fields]
   (let [fields (vec (distinct fields))
         tokens (->> tokens
                     (keep #(some-> % str str/lower-case not-empty))
                     distinct
                     vec)
         constraints (->> (clean-constraints constraints)
                          (sort-by (comp str key))
                          vec)
         return-fields (some-> return-fields distinct vec)]
     (cond
       (or (empty? fields) (empty? tokens))
       []

       (xtdb-handle? xtdb)
       (let [{token-where :where token-args :args} (token-match-sql fields tokens)
             {constraint-where :where constraint-args :args} (equality-sql constraints)
             where-clauses (cond-> constraint-where token-where (conj token-where))
             sql (str "SELECT "
                      (select-sql return-fields)
                      " FROM "
                      (sql-table-name table)
                      (when (seq where-clauses)
                        (str " WHERE "
                             (str/join " AND " where-clauses))))]
         (map #(normalize-projected-sql-row return-fields %)
              (q xtdb sql (assoc ctx :args (vec (concat constraint-args token-args))))))

       :else
       (let [rows (filter #(token-match-row? fields tokens %)
                          (fallback-constrained-rows xtdb table (into {} constraints) ctx))]
         (if (seq return-fields)
           (map #(select-keys % return-fields) rows)
           rows))))))

(defn rows-with-string-prefixes
  "Return rows where field equals a prefix or is under that prefix.

  Real XTDB handles use SQL predicate pushdown with parameterized equality and
  LIKE clauses. Test doubles fall back to constrained rows and the same
  mechanical prefix predicate."
  ([xtdb table field prefixes constraints] (rows-with-string-prefixes
                                            xtdb
                                            table
                                            field
                                            prefixes
                                            constraints
                                            {}))
  ([xtdb table field prefixes constraints ctx]
   (rows-with-string-prefixes xtdb table field prefixes constraints ctx nil))
  ([xtdb table field prefixes constraints ctx return-fields]
   (let [prefixes (->> prefixes
                       (keep #(some-> % str str/trim not-empty))
                       distinct
                       vec)
         constraints (->> (clean-constraints constraints)
                          (sort-by (comp str key))
                          vec)
         return-fields (some-> return-fields distinct vec)]
     (cond
       (empty? prefixes)
       []

       (xtdb-handle? xtdb)
       (let [{prefix-where :where prefix-args :args} (string-prefix-sql field prefixes)
             {constraint-where :where constraint-args :args} (equality-sql constraints)
             where-clauses (cond-> constraint-where prefix-where (conj prefix-where))
             sql (str "SELECT "
                      (select-sql return-fields)
                      " FROM "
                      (sql-table-name table)
                      (when (seq where-clauses)
                        (str " WHERE "
                             (str/join " AND " where-clauses))))]
         (map #(normalize-projected-sql-row return-fields %)
              (q xtdb sql (assoc ctx :args (vec (concat constraint-args prefix-args))))))

       :else
       (let [rows (filter #(string-prefix-match-row? field prefixes %)
                          (fallback-constrained-rows xtdb table (into {} constraints) ctx))]
         (if (seq return-fields)
           (map #(select-keys % return-fields) rows)
           rows))))))

(defn row-by-id
  "Return row by id from table."
  ([xtdb table id] (row-by-id xtdb table id {}))
  ([xtdb table id ctx]
   (first (q xtdb
             [(list 'fn ['doc-id] (list 'from table [{:xt/id 'doc-id} '*])) id]
             ctx))))

(defn- validate-file-row
  [row]
  (schema/assert! schema/file-row row "Invalid file row."))

(defn- validate-project-row
  [row]
  (schema/assert! schema/project-row row "Invalid project row."))

(defn- validate-repo-row
  [row]
  (schema/assert! schema/repo-row row "Invalid repo row."))

(defn- validate-node-row
  [row]
  (schema/assert! schema/node-row row "Invalid node row."))

(defn- validate-edge-row
  [row]
  (schema/assert! schema/edge-row row "Invalid edge row."))

(defn- validate-chunk-row
  [row]
  (schema/assert! schema/chunk-row row "Invalid chunk row."))

(defn- validate-file-fact-row
  [row]
  (schema/assert! schema/file-fact-row row "Invalid file fact row."))

(defn- validate-diagnostic-row
  [row]
  (schema/assert! schema/diagnostic-row row "Invalid diagnostic row."))

(defn- validate-search-doc-row
  [row]
  (schema/assert! schema/search-doc-row row "Invalid search doc row."))

(defn- validate-embedding-row
  [row]
  (schema/assert! schema/embedding-row row "Invalid embedding row."))

(defn- validate-system-evidence-row
  [row]
  (schema/assert! schema/system-evidence-row row "Invalid system evidence row."))

(defn- validate-system-node-row
  [row]
  (schema/assert! schema/system-node-row row "Invalid system node row."))

(defn- validate-system-edge-row
  [row]
  (schema/assert! schema/system-edge-row row "Invalid system edge row."))

(defn- validate-metadata-def-row
  [row]
  (schema/assert! schema/metadata-def-row row "Invalid metadata definition row."))

(defn- validate-metadata-row
  [row]
  (schema/assert! schema/metadata-row row "Invalid metadata row."))

(defn- validate-graph-view-row
  [row]
  (schema/assert! schema/graph-view-row row "Invalid graph view row."))

(defn- validate-graph-cursor-row
  [row]
  (schema/assert! schema/graph-cursor-row row "Invalid graph cursor row."))

(defn put-op
  "Return an XTDB put op, optionally at `:valid-from`."
  ([table row] (put-op table row {}))
  ([table row {:keys [valid-from]}]
   (if valid-from
     [:put-docs {:into table
                 :valid-from (instant valid-from)}
      row]
     [:put-docs table row])))

(defn delete-op
  "Return an XTDB delete op, optionally at `:valid-from`."
  ([table id] (delete-op table id {}))
  ([table id {:keys [valid-from]}]
   (if valid-from
     [:delete-docs {:from table
                    :valid-from (instant valid-from)}
      id]
     [:delete-docs table id])))

(defn execute-tx!
  "Execute XTDB transaction ops and return the XTDB transaction key."
  [xtdb ops]
  (when (seq ops)
    (xt/execute-tx (:node xtdb) (vec ops))))

(defn- compact-doc-ops
  "Collapse same-table XTDB document ops into multi-row ops."
  [ops]
  (let [{:keys [order values]} (reduce
                                (fn [{:keys [values] :as acc} op]
                                  (let [k [(first op) (second op)]]
                                    (-> acc
                                        (cond-> (not (contains? values k))
                                          (update :order conj k))
                                        (update-in [:values k] into (nnext op)))))
                                {:order []
                                 :values {}}
                                ops)]
    (mapv (fn [[op table-or-opts :as k]]
            (into [op table-or-opts] (get values k)))
          order)))

(defn table-ref
  "Return physical XTDB table keyword for logical table key."
  [table]
  (get tables table table))

(defn- row-id
  [row-or-id]
  (if (map? row-or-id)
    (:xt/id row-or-id)
    row-or-id))

(defn temporal-bundle-ops
  "Return XTDB ops for a temporal write bundle.

  Bundle shape:

  {:snapshot {...}
   :run {...}
   :valid-from #inst \"...\"
   :puts {:nodes [...]}
   :deletes {:edges [id-or-row ...]}}"
  [{:keys [snapshot run valid-from puts deletes]}]
  (let [temporal {:valid-from valid-from}]
    (vec
     (concat
      (when snapshot
        [(put-op (table-ref :source-snapshots) snapshot temporal)])
      (when run
        [(put-op (table-ref :index-runs) run temporal)])
      (mapcat (fn [[table rows]]
                (map #(put-op (table-ref table) % temporal) rows))
              puts)
      (mapcat (fn [[table rows-or-ids]]
                (map #(delete-op (table-ref table) (row-id %) temporal) rows-or-ids))
              deletes)))))

(defn commit-temporal-bundle!
  "Commit a temporal write bundle and return the XTDB transaction key."
  [xtdb bundle]
  (execute-tx! xtdb (temporal-bundle-ops bundle)))

(defn metadata-defs
  "Return metadata definitions, including built-in defaults and stored rows."
  ([xtdb] (metadata-defs xtdb {}))
  ([xtdb ctx]
   (->> (concat metadata/default-definitions
                (all-rows xtdb (:metadata-defs tables) ctx))
        (filter #(not= false (:active? %)))
        (map validate-metadata-def-row)
        (map (juxt :key identity))
        (into {}))))

(defn commit-metadata-defs!
  "Persist metadata definition rows."
  ([xtdb rows] (commit-metadata-defs! xtdb rows {}))
  ([xtdb rows {:keys [valid-from]}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         rows (map validate-metadata-def-row rows)
         ops (mapv #(put-op (:metadata-defs tables) % temporal) rows)]
     (execute-tx! xtdb ops)
     {:metadata-defs (count rows)})))

(def ^:private metadata-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :target-id
   :target-kind
   :key
   :value
   :value-type
   :value-text
   :source
   :confidence
   :evidence-ids
   :active?
   :run-id])

(defn metadata-for-targets
  "Return metadata rows for target ids visible in read context."
  ([xtdb target-ids] (metadata-for-targets xtdb target-ids {}))
  ([xtdb target-ids ctx]
   (let [project-id (:project-id ctx)
         repo-id (:repo-id ctx)
         target-ids (vec (distinct (seq target-ids)))]
     (->> (rows-with-field-values
           xtdb
           {:table (:metadata tables)
            :field :target-id
            :values target-ids
            :constraints {:project-id project-id
                          :repo-id repo-id}
            :return-fields metadata-row-query-fields
            :read-context ctx})
          (filter #(not= false (:active? %)))
          (map validate-metadata-row)
          vec))))

(defn- single-cardinality-metadata-row?
  [defs row]
  (= :one (get-in defs [(:key row) :cardinality] :many)))

(defn- matching-metadata-row?
  [row existing]
  (and (= (:target-id row) (:target-id existing))
       (= (:key row) (:key existing))
       (= (:source row) (:source existing))
       (= (:project-id row) (:project-id existing))))

(def ^:private metadata-replacement-row-fields
  [:xt/id
   :project-id
   :target-id
   :key
   :source
   :active?])

(defn- existing-metadata-by-target
  [xtdb rows ctx]
  (let [target-ids (->> rows
                        (keep :target-id)
                        distinct
                        vec)]
    (if (empty? target-ids)
      {}
      (->> (rows-with-field-values
            xtdb
            {:table (:metadata tables)
             :field :target-id
             :values target-ids
             :return-fields metadata-replacement-row-fields
             :read-context ctx})
           (filter #(not= false (:active? %)))
           (group-by :target-id)))))

(defn commit-metadata!
  "Persist metadata rows with metadata definition cardinality."
  ([xtdb rows] (commit-metadata! xtdb rows {}))
  ([xtdb rows {:keys [valid-from] :as opts}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         read-ctx (cond-> (read-context opts)
                    valid-from (assoc :valid-at valid-from))
         rows (mapv validate-metadata-row rows)
         defs (metadata-defs xtdb read-ctx)
         single-cardinality-rows (filterv #(single-cardinality-metadata-row?
                                            defs
                                            %)
                                          rows)
         existing-by-target (existing-metadata-by-target xtdb
                                                         single-cardinality-rows
                                                         read-ctx)
         ops (vec
              (mapcat (fn [row]
                        (let [delete-ops (when (single-cardinality-metadata-row?
                                                defs
                                                row)
                                           (->> (get existing-by-target
                                                     (:target-id row))
                                                (filter #(matching-metadata-row?
                                                          row
                                                          %))
                                                (map #(delete-op
                                                       (:metadata tables)
                                                       (:xt/id %)
                                                       temporal))))]
                          (concat delete-ops [(put-op (:metadata tables) row temporal)])))
                      rows))]
     (execute-tx! xtdb ops)
     {:metadata (count rows)})))

(defn delete-metadata!
  "Delete metadata rows for a target/key/source."
  [xtdb {:keys [target-id key source project-id repo-id valid-from] :as opts}]
  (let [key (metadata/parse-key key)
        source (keyword (or source metadata/default-source))
        temporal (cond-> {}
                   valid-from (assoc :valid-from valid-from))
        read-ctx (cond-> (read-context opts)
                   valid-from (assoc :valid-at valid-from))
        row-ids (mapv :xt/id
                      (ordered-rows xtdb
                                    {:table (:metadata tables)
                                     :constraints {:target-id target-id
                                                   :key key
                                                   :source source
                                                   :project-id project-id
                                                   :repo-id repo-id}
                                     :return-fields [:xt/id]
                                     :read-context read-ctx}))
        ops (mapv #(delete-op (:metadata tables) % temporal) row-ids)]
    (execute-tx! xtdb ops)
    {:metadata-deleted (count row-ids)}))

(defn commit-graph-views!
  "Persist graph view rows."
  ([xtdb rows] (commit-graph-views! xtdb rows {}))
  ([xtdb rows {:keys [valid-from]}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         rows (map validate-graph-view-row rows)
         ops (mapv #(put-op (:graph-views tables) % temporal) rows)]
     (execute-tx! xtdb ops)
     {:graph-views (count rows)})))

(defn commit-derived-dependency-edges!
  "Replace derived import-to-package edges for a project/repo scope."
  [xtdb project-id repo-id rows {:keys [valid-from] :as opts}]
  (let [temporal (cond-> {}
                   valid-from (assoc :valid-from valid-from))
        read-ctx (cond-> (read-context opts)
                   valid-from (assoc :valid-at valid-from))
        existing-ids (mapv :xt/id
                           (ordered-rows xtdb
                                         {:table (:edges tables)
                                          :constraints {:project-id project-id
                                                        :repo-id repo-id
                                                        :relation :imports-package}
                                          :return-fields [:xt/id]
                                          :read-context read-ctx}))
        rows (map validate-edge-row rows)
        ops (vec (concat
                  (map #(delete-op (:edges tables) % temporal) existing-ids)
                  (map #(put-op (:edges tables) % temporal) rows)))]
    (execute-tx! xtdb ops)
    {:dependency-edges (count rows)
     :dependency-edges-deleted (count existing-ids)}))

(defn graph-views
  "Return graph views visible in read context."
  ([xtdb] (graph-views xtdb {}))
  ([xtdb ctx]
   (let [project-id (:project-id ctx)]
     (->> (all-rows xtdb (:graph-views tables) ctx)
          (filter #(or (nil? project-id)
                       (nil? (:project-id %))
                       (= project-id (:project-id %))))
          (filter #(not= false (:active? %)))
          (map validate-graph-view-row)
          vec))))

(defn- visible-graph-view?
  [project-id row]
  (and (or (nil? project-id)
           (nil? (:project-id row))
           (= project-id (:project-id row)))
       (not= false (:active? row))))

(defn- graph-view-by-label
  [xtdb label ctx]
  (let [project-id (:project-id ctx)]
    (some-> (->> (rows-by-field xtdb (:graph-views tables) :label label ctx)
                 (filter #(visible-graph-view? project-id %))
                 (sort-by (fn [row]
                            [(if (= project-id (:project-id row)) 0 1)
                             (:xt/id row)]))
                 first)
            validate-graph-view-row)))

(defn graph-view
  "Return one graph view by id."
  ([xtdb id] (graph-view xtdb id {}))
  ([xtdb id ctx]
   (or (row-by-id xtdb (:graph-views tables) id ctx)
       (graph-view-by-label xtdb id ctx))))

(defn commit-graph-cursor!
  "Persist one graph cursor revision."
  [xtdb row]
  (let [row (validate-graph-cursor-row row)]
    (execute-tx! xtdb [(put-op (:graph-cursors tables) row)])
    row))

(defn graph-cursor
  "Return graph cursor revision by id."
  [xtdb id]
  (some-> (row-by-id xtdb (:graph-cursors tables) id)
          validate-graph-cursor-row))

(defn graph-cursors
  "Return graph cursor revisions, optionally scoped to project."
  ([xtdb] (graph-cursors xtdb {}))
  ([xtdb {:keys [project-id]}]
   (->> (constrained-rows xtdb
                          (:graph-cursors tables)
                          (cond-> {}
                            project-id (assoc :project-id project-id)))
        (filter #(or (nil? project-id) (= project-id (:project-id %))))
        (filter #(not= false (:active? %)))
        (map validate-graph-cursor-row)
        (sort-by (juxt :project-id :created-at-ms :revision))
        vec)))

(defn- latest-source-snapshot-row
  [xtdb project-id]
  (first
   (q xtdb
      (str "SELECT * FROM "
           (sql-table-name (:source-snapshots tables))
           " WHERE "
           (sql-column-name :project-id)
           " = ? ORDER BY "
           (sql-column-name :basis-instant)
           " DESC LIMIT 1")
      {:args [project-id]})))

(defn latest-source-snapshot
  "Return the latest source snapshot row for project, if any."
  [xtdb project-id]
  (if (and project-id (xtdb-handle? xtdb))
    (latest-source-snapshot-row xtdb project-id)
    (->> (rows-by-field xtdb (:source-snapshots tables) :project-id project-id)
         (sort-by :basis-instant)
         last)))

(defn file-row
  "Return stored file row by path."
  [xtdb path]
  (first (rows-by-field xtdb (:files tables) :path path)))

(defn scoped-file-row
  "Return stored file row by project, repo, and path."
  [xtdb project-id repo-id path]
  (some #(when (and (= project-id (:project-id %))
                    (= repo-id (:repo-id %))
                    (= path (:path %)))
           %)
        (constrained-rows xtdb
                          (:files tables)
                          (cond-> {:path path}
                            project-id (assoc :project-id project-id)
                            repo-id (assoc :repo-id repo-id)))))

(def ^:private file-owned-table-keys
  [:nodes :edges :chunks :file-facts :diagnostics :search-docs])

(def ^:private file-owned-row-id-fields
  [:xt/id :file-id])

(defn- empty-file-scoped-rows
  []
  (zipmap file-owned-table-keys (repeat [])))

(defn- file-scoped-rows-for-file-ids
  [xtdb file-ids ctx]
  (let [file-ids (->> (seq file-ids) (remove nil?) distinct vec)
        table-rows (into {}
                         (map (fn [table-key]
                                [table-key
                                 (if (seq file-ids)
                                   (rows-with-field-values
                                    xtdb
                                    {:table (table-ref table-key)
                                     :field :file-id
                                     :values file-ids
                                     :return-fields file-owned-row-id-fields
                                     :read-context ctx})
                                   [])]))
                         file-owned-table-keys)
        rows-by-table-and-file (into {}
                                     (map (fn [[table-key rows]]
                                            [table-key (group-by :file-id rows)]))
                                     table-rows)]
    (into {}
          (map (fn [file-id]
                 [file-id
                  (into {}
                        (map (fn [table-key]
                               [table-key
                                (vec (get-in rows-by-table-and-file
                                             [table-key file-id]
                                             []))]))
                        file-owned-table-keys)]))
          file-ids)))

(defn file-scoped-rows
  "Return existing rows owned by file id."
  ([xtdb file-id] (file-scoped-rows xtdb file-id {}))
  ([xtdb file-id ctx]
   {:nodes (rows-by-field xtdb (:nodes tables) :file-id file-id ctx)
    :edges (rows-by-field xtdb (:edges tables) :file-id file-id ctx)
    :chunks (rows-by-field xtdb (:chunks tables) :file-id file-id ctx)
    :file-facts (rows-by-field xtdb (:file-facts tables) :file-id file-id ctx)
    :diagnostics (rows-by-field xtdb (:diagnostics tables) :file-id file-id ctx)
    :search-docs (rows-by-field xtdb (:search-docs tables) :file-id file-id ctx)}))

(defn file-tx
  "Return replace transaction ops and counts for one file."
  ([xtdb file-row extraction] (file-tx xtdb file-row extraction {:existing? true}))
  ([xtdb file-row extraction {:keys [existing? valid-from existing-rows]
                              :or {existing? true}}]
   (let [file-id (:xt/id file-row)
         temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         read-ctx (cond-> {}
                    valid-from (assoc :valid-at valid-from))
         existing (cond
                    (false? existing?) (empty-file-scoped-rows)
                    existing-rows existing-rows
                    :else (file-scoped-rows xtdb file-id read-ctx))
         delete-ops (concat
                     (map #(delete-op (:nodes tables) (:xt/id %) temporal) (:nodes existing))
                     (map #(delete-op (:edges tables) (:xt/id %) temporal) (:edges existing))
                     (map #(delete-op (:chunks tables) (:xt/id %) temporal) (:chunks existing))
                     (map #(delete-op (:file-facts tables) (:xt/id %) temporal) (:file-facts existing))
                     (map #(delete-op (:diagnostics tables) (:xt/id %) temporal) (:diagnostics existing))
                     (map #(delete-op (:search-docs tables) (:xt/id %) temporal) (:search-docs existing)))
         nodes (map validate-node-row (:nodes extraction))
         edges (map validate-edge-row (:edges extraction))
         chunks (map validate-chunk-row (:chunks extraction))
         file-facts (map validate-file-fact-row (:file-facts extraction))
         diagnostics (map validate-diagnostic-row (:diagnostics extraction))
         search-docs (map validate-search-doc-row (:search-docs extraction))
         put-ops (concat
                  [(put-op (:files tables) (validate-file-row file-row) temporal)]
                  (map #(put-op (:nodes tables) % temporal) nodes)
                  (map #(put-op (:edges tables) % temporal) edges)
                  (map #(put-op (:chunks tables) % temporal) chunks)
                  (map #(put-op (:file-facts tables) % temporal) file-facts)
                  (map #(put-op (:diagnostics tables) % temporal) diagnostics)
                  (map #(put-op (:search-docs tables) % temporal) search-docs))
         tx-ops (vec (concat delete-ops put-ops))]
     {:ops tx-ops
      :delete-ops (vec delete-ops)
      :put-ops (vec put-ops)
      :counts {:nodes (count nodes)
               :edges (count edges)
               :chunks (count chunks)
               :file-facts (count file-facts)
               :diagnostics (count diagnostics)
               :search-docs (count search-docs)}})))

(defn- merge-counts
  [a b]
  (merge-with + a b))

(defn commit-file!
  "Replace graph rows for one file."
  [xtdb file-row extraction]
  (let [{:keys [ops counts]} (file-tx xtdb file-row extraction)]
    (xt/execute-tx (:node xtdb) (compact-doc-ops ops))
    counts))

(defn commit-files!
  "Replace graph rows for file entries in bounded transaction batches."
  ([xtdb entries] (commit-files! xtdb entries {:batch-size 50}))
  ([xtdb entries {:keys [batch-size] :or {batch-size 50}}]
   (reduce
    (fn [summary batch]
      (let [batch (vec batch)
            existing-by-file-and-valid-from
            (into {}
                  (mapcat
                   (fn [[valid-from entries]]
                     (let [read-ctx (cond-> {}
                                      valid-from (assoc :valid-at valid-from))
                           file-ids (map (comp :xt/id :file-row) entries)
                           existing-by-file-id (file-scoped-rows-for-file-ids
                                                xtdb
                                                file-ids
                                                read-ctx)]
                       (map (fn [entry]
                              (let [file-id (get-in entry [:file-row :xt/id])]
                                [[file-id valid-from]
                                 (get existing-by-file-id
                                      file-id
                                      (empty-file-scoped-rows))]))
                            entries))))
                  (group-by :valid-from
                            (filter #(not= false (:existing? %)) batch)))
            txs (map #(let [file-id (get-in % [:file-row :xt/id])
                            valid-from (:valid-from %)]
                        (file-tx xtdb
                                 (:file-row %)
                                 (:extraction %)
                                 {:existing? (:existing? %)
                                  :existing-rows (get existing-by-file-and-valid-from
                                                      [file-id valid-from])
                                  :valid-from valid-from}))
                     batch)
            delete-ops (compact-doc-ops (mapcat :delete-ops txs))
            put-ops (compact-doc-ops (mapcat :put-ops txs))
            ops (vec (concat delete-ops put-ops))
            counts (reduce merge-counts
                           {:nodes 0
                            :edges 0
                            :chunks 0
                            :file-facts 0
                            :diagnostics 0
                            :search-docs 0}
                           (map :counts txs))]
        (when (seq ops)
          (xt/execute-tx (:node xtdb) ops))
        (merge-counts summary counts)))
    {:nodes 0
     :edges 0
     :chunks 0
     :file-facts 0
     :diagnostics 0
     :search-docs 0}
    (partition-all batch-size entries))))

(defn file-delete-tx
  "Return temporal delete ops for one removed file and its owned rows."
  [xtdb file-row {:keys [valid-from existing-rows]}]
  (let [temporal (cond-> {}
                   valid-from (assoc :valid-from valid-from))
        read-ctx (cond-> {}
                   valid-from (assoc :valid-at valid-from))
        existing (or existing-rows
                     (file-scoped-rows xtdb (:xt/id file-row) read-ctx))
        ops (vec
             (concat
              (map #(delete-op (:nodes tables) (:xt/id %) temporal) (:nodes existing))
              (map #(delete-op (:edges tables) (:xt/id %) temporal) (:edges existing))
              (map #(delete-op (:chunks tables) (:xt/id %) temporal) (:chunks existing))
              (map #(delete-op (:file-facts tables) (:xt/id %) temporal) (:file-facts existing))
              (map #(delete-op (:diagnostics tables) (:xt/id %) temporal) (:diagnostics existing))
              (map #(delete-op (:search-docs tables) (:xt/id %) temporal) (:search-docs existing))
              [(delete-op (:files tables) (:xt/id file-row) temporal)]))]
    {:ops ops
     :counts {:files-deleted 1
              :nodes-deleted (count (:nodes existing))
              :edges-deleted (count (:edges existing))
              :chunks-deleted (count (:chunks existing))
              :file-facts-deleted (count (:file-facts existing))
              :diagnostics-deleted (count (:diagnostics existing))
              :search-docs-deleted (count (:search-docs existing))}}))

(defn commit-file-deletes!
  "Delete removed files and their graph rows in bounded transaction batches."
  ([xtdb file-rows opts] (commit-file-deletes! xtdb file-rows opts {:batch-size 50}))
  ([xtdb file-rows opts {:keys [batch-size] :or {batch-size 50}}]
   (reduce
    (fn [summary batch]
      (let [batch (vec batch)
            read-ctx (cond-> {}
                       (:valid-from opts) (assoc :valid-at (:valid-from opts)))
            existing-by-file-id (file-scoped-rows-for-file-ids
                                 xtdb
                                 (map :xt/id batch)
                                 read-ctx)
            txs (map #(file-delete-tx xtdb
                                      %
                                      (assoc opts
                                             :existing-rows
                                             (get existing-by-file-id
                                                  (:xt/id %)
                                                  (empty-file-scoped-rows))))
                     batch)
            ops (compact-doc-ops (mapcat :ops txs))
            counts (reduce merge-counts {} (map :counts txs))]
        (execute-tx! xtdb ops)
        (merge-counts summary counts)))
    {:files-deleted 0
     :nodes-deleted 0
     :edges-deleted 0
     :chunks-deleted 0
     :file-facts-deleted 0
     :diagnostics-deleted 0
     :search-docs-deleted 0}
    (partition-all batch-size file-rows))))

(defn commit-embeddings!
  "Persist embedding rows."
  [xtdb rows]
  (let [validated (map validate-embedding-row rows)
        ops (mapv #(put-op (:embeddings tables) %) validated)]
    (when (seq ops)
      (xt/execute-tx (:node xtdb) (compact-doc-ops ops)))
    {:embeddings (count validated)}))

(defn commit-project!
  "Persist project and repo rows."
  [xtdb project-row repo-rows]
  (let [ops (into [(put-op (:projects tables) (validate-project-row project-row))]
                  (map #(put-op (:repos tables) (validate-repo-row %))
                       repo-rows))]
    (xt/execute-tx (:node xtdb) (compact-doc-ops ops))
    {:project project-row
     :repos (count repo-rows)}))

(defn- project-row-ids
  [xtdb table project-id]
  (mapv :xt/id
        (ordered-rows xtdb
                      {:table table
                       :constraints (cond-> {}
                                      project-id (assoc :project-id project-id))
                       :return-fields [:xt/id]})))

(def ^:private system-search-doc-target-kinds
  [:system-node :system-edge])

(def ^:private system-search-doc-row-fields
  [:xt/id])

(defn- project-system-search-docs
  [xtdb project-id]
  (rows-with-field-values
   xtdb
   {:table (:search-docs tables)
    :field :target-kind
    :values system-search-doc-target-kinds
    :constraints (cond-> {}
                   project-id (assoc :project-id project-id))
    :return-fields system-search-doc-row-fields}))

(defn commit-system-graph!
  "Replace derived system graph rows for project."
  [xtdb project-id {:keys [evidence nodes edges search-docs]}]
  (let [existing-evidence-ids (project-row-ids xtdb (:system-evidence tables) project-id)
        existing-node-ids (project-row-ids xtdb (:system-nodes tables) project-id)
        existing-edge-ids (project-row-ids xtdb (:system-edges tables) project-id)
        existing-search-docs (project-system-search-docs xtdb project-id)
        evidence (map validate-system-evidence-row evidence)
        nodes (map validate-system-node-row nodes)
        edges (map validate-system-edge-row edges)
        search-docs (map validate-search-doc-row search-docs)
        ops (vec
             (concat
              (map #(delete-op (:system-evidence tables) %) existing-evidence-ids)
              (map #(delete-op (:system-nodes tables) %) existing-node-ids)
              (map #(delete-op (:system-edges tables) %) existing-edge-ids)
              (map #(delete-op (:search-docs tables) (:xt/id %)) existing-search-docs)
              (map #(put-op (:system-evidence tables) %) evidence)
              (map #(put-op (:system-nodes tables) %) nodes)
              (map #(put-op (:system-edges tables) %) edges)
              (map #(put-op (:search-docs tables) %) search-docs)))]
    (when (seq ops)
      (xt/execute-tx (:node xtdb) (compact-doc-ops ops)))
    {:system-evidence (count evidence)
     :system-nodes (count nodes)
     :system-edges (count edges)
     :search-docs (count search-docs)}))

(defn commit-run!
  "Persist run row."
  [xtdb run-row]
  (xt/execute-tx (:node xtdb) [(put-op (:runs tables) run-row)])
  run-row)

(defn commit-query-run!
  "Persist query run row."
  [xtdb row]
  (xt/execute-tx (:node xtdb) [(put-op (:query-runs tables) row)])
  row)
