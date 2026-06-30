(ns ygg.vector-store
  "Semantic vector retrieval backends.

  XTDB embedding rows remain the source of truth. Project SQLite stores the
  rebuildable sqlite-vec index when the sqlite-vec extension is available."
  (:require [ygg.env :as env]
            [ygg.hash :as hash]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.sql PreparedStatement]
           [org.sqlite SQLiteConfig]))

(def default-candidate-limit 100)
(def default-embedding-role :content)
(def default-vector-overfetch-factors [4 8 16])
(def default-fts-candidate-limit 100)

(def embedding-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :target-id
   :provider
   :model
   :embedding-role
   :dims
   :input-sha
   :target-kind
   :file-kind
   :path
   :vector
   :created-at-ms
   :active?])

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- timed
  [k f]
  (let [started (now-ns)
        result (f)]
    [result {k (elapsed-ms started)}]))

(defn- magnitude
  [v]
  (let [v (if (vector? v) v (vec v))]
    (loop [idx 0
           total 0.0]
      (if (< idx (count v))
        (let [value (double (nth v idx))]
          (recur (inc idx) (+ total (* value value))))
        (Math/sqrt total)))))

(defn- dot-and-magnitude
  [query-vector vector]
  (let [query-count (count query-vector)
        vector (if (vector? vector) vector (vec vector))]
    (loop [idx 0
           dot-total 0.0
           magnitude-total 0.0]
      (if (< idx (count vector))
        (let [value (double (nth vector idx))
              query-value (if (< idx query-count)
                            (double (nth query-vector idx))
                            0.0)]
          (recur (inc idx)
                 (+ dot-total (* query-value value))
                 (+ magnitude-total (* value value))))
        [dot-total (Math/sqrt magnitude-total)]))))

(defn- cosine01-with-magnitude
  [query-vector query-magnitude vector]
  (let [[dot-product vector-magnitude] (dot-and-magnitude query-vector vector)
        denom (* query-magnitude vector-magnitude)]
    (if (zero? denom)
      0.0
      (/ (+ 1.0 (double (/ dot-product denom))) 2.0))))

(defn configured-mode
  []
  (keyword (or (env/get-env "YGG_VECTOR_STORE") "auto")))

(defn default-index-path
  ([] (default-index-path nil))
  ([project-id]
   (store/project-sqlite-path (or project-id
                                  (env/get-env "YGG_PROJECT_ID")
                                  store/default-project-id))))

(defn configured-index-path
  ([] (configured-index-path nil))
  ([project-id]
   (or (env/get-env "YGG_VECTOR_INDEX_PATH")
       (default-index-path project-id))))

(defn configured-extension-path
  []
  (env/get-env "YGG_SQLITE_VEC_EXTENSION"))

(defn- truthy?
  [value]
  (contains? #{"1" "true" "yes" "on"}
             (str/lower-case (str/trim (str value)))))

(defn configured-fts?
  []
  (truthy? (env/get-env "YGG_SQLITE_FTS")))

(defn- embedding-role
  [row]
  (keyword (or (:embedding-role row) default-embedding-role)))

(defn- requested-embedding-roles
  [{:keys [embedding-role embedding-roles]}]
  (let [roles (or embedding-roles
                  (when embedding-role [embedding-role])
                  [default-embedding-role])]
    (vec (distinct (map #(keyword (or % default-embedding-role)) roles)))))

(defn- sql-value
  [value]
  (when (some? value)
    (if (keyword? value)
      (name value)
      (str value))))

(defn- sqlite-requested?
  [mode]
  (contains? #{:auto :sqlite-vec} mode))

(defn- sqlite-enabled?
  [{:keys [mode extension-path]}]
  (and (sqlite-requested? mode)
       (not (str/blank? (str extension-path)))
       (.isFile (io/file extension-path))))

(defn- ensure-parent!
  [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  path)

(defn- sqlite-connection
  [{:keys [index-path extension-path]}]
  (let [config (doto (SQLiteConfig.)
                 (.enableLoadExtension true))
        conn (.createConnection config (str "jdbc:sqlite:" (ensure-parent! index-path)))]
    (try
      (with-open [statement (.prepareStatement conn "select load_extension(?)")]
        (.setString statement 1 extension-path)
        (.execute statement))
      conn
      (catch Exception e
        (.close conn)
        (throw e)))))

(defn- sqlite-base-connection
  [{:keys [index-path]}]
  (let [config (SQLiteConfig.)]
    (.createConnection config (str "jdbc:sqlite:" (ensure-parent! index-path)))))

(defn- execute!
  [conn sql]
  (with-open [statement (.createStatement conn)]
    (.execute statement sql)))

(defn- table-exists?
  [conn table-name]
  (with-open [statement (.prepareStatement
                         conn
                         "select 1 from sqlite_master where name = ? limit 1")]
    (.setString statement 1 table-name)
    (with-open [rs (.executeQuery statement)]
      (.next rs))))

(defn- table-columns
  [conn table-name]
  (with-open [statement (.createStatement conn)
              rs (.executeQuery statement (str "pragma table_info(" table-name ")"))]
    (loop [columns #{}]
      (if (.next rs)
        (recur (conj columns (.getString rs "name")))
        columns))))

(defn- sqlite-table-names
  [conn prefix]
  (with-open [statement (.prepareStatement
                         conn
                         "select name from sqlite_master where name like ?")]
    (.setString statement 1 (str prefix "%"))
    (with-open [rs (.executeQuery statement)]
      (loop [names []]
        (if (.next rs)
          (recur (conj names (.getString rs 1)))
          names)))))

(defn- drop-table!
  [conn table-name]
  (execute! conn (str "drop table if exists " table-name)))

(defn- rebuild-embedding-sidecar-if-needed!
  [conn]
  (when (and (table-exists? conn "ygg_embedding_metadata")
             (not (every? (table-columns conn "ygg_embedding_metadata")
                          ["embedding_role"
                           "target_kind"
                           "file_kind"
                           "path"
                           "updated_at_ms"])))
    (doseq [table-name (sqlite-table-names conn "ygg_vec_")]
      (drop-table! conn table-name))
    (drop-table! conn "ygg_embedding_metadata")))

(defn- safe-ident
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_]" "_")))

(defn- vector-table
  [provider model dims role]
  (str "ygg_vec_"
       (safe-ident (hash/short-hash [(name provider) model dims (name role)]))))

(defn- vector-json
  [v]
  (str "[" (str/join "," (map #(Double/toString (double %)) v)) "]"))

(defn- ensure-schema!
  [conn table-name dims]
  (rebuild-embedding-sidecar-if-needed! conn)
  (execute! conn
            (str "create table if not exists ygg_embedding_metadata "
                 "(id integer primary key autoincrement, "
                 "table_name text not null, "
                 "project_id text, "
                 "repo_id text, "
                 "provider text not null, "
                 "model text not null, "
                 "embedding_role text not null default 'content', "
                 "target_id text not null, "
                 "input_sha text not null, "
                 "target_kind text, "
                 "file_kind text, "
                 "path text, "
                 "dims integer not null, "
                 "updated_at_ms integer, "
                 "active integer not null default 1, "
                 "unique(provider, model, embedding_role, project_id, repo_id, target_id, input_sha))"))
  (execute! conn
            (str "create unique index if not exists "
                 "ygg_embedding_metadata_current_idx "
                 "on ygg_embedding_metadata(provider, model, embedding_role, "
                 "project_id, repo_id, target_id, input_sha)"))
  (execute! conn
            (str "create virtual table if not exists "
                 table-name
                 " using vec0(embedding_id integer primary key, "
                 "embedding float["
                 (long dims)
                 "] distance_metric=cosine)")))

(defn- set-nullable-string!
  [^PreparedStatement statement idx value]
  (if (nil? value)
    (.setObject statement idx nil)
    (.setString statement idx (str value))))

(defn- metadata-id
  [conn row table-name dims]
  (let [role (embedding-role row)]
    (with-open [statement (.prepareStatement
                           conn
                           (str "insert into ygg_embedding_metadata "
                                "(table_name, project_id, repo_id, provider, model, embedding_role, "
                                "target_id, input_sha, target_kind, file_kind, path, dims, updated_at_ms, active) "
                                "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) "
                                "on conflict(provider, model, embedding_role, project_id, repo_id, target_id, input_sha) "
                                "do update set table_name = excluded.table_name, "
                                "target_kind = excluded.target_kind, "
                                "file_kind = excluded.file_kind, "
                                "path = excluded.path, "
                                "dims = excluded.dims, updated_at_ms = excluded.updated_at_ms, active = 1"))]
      (.setString statement 1 table-name)
      (set-nullable-string! statement 2 (:project-id row))
      (set-nullable-string! statement 3 (:repo-id row))
      (.setString statement 4 (name (:provider row)))
      (.setString statement 5 (str (:model row)))
      (.setString statement 6 (name role))
      (.setString statement 7 (str (:target-id row)))
      (.setString statement 8 (str (:input-sha row)))
      (set-nullable-string! statement 9 (sql-value (:target-kind row)))
      (set-nullable-string! statement 10 (sql-value (:file-kind row)))
      (set-nullable-string! statement 11 (:path row))
      (.setLong statement 12 (long dims))
      (.setLong statement 13 (long (or (:created-at-ms row) (System/currentTimeMillis))))
      (.executeUpdate statement))
    (with-open [statement (.prepareStatement
                           conn
                           (str "select id from ygg_embedding_metadata "
                                "where provider = ? and model = ? and embedding_role = ? "
                                "and (project_id is ? or project_id = ?) "
                                "and (repo_id is ? or repo_id = ?) "
                                "and target_id = ? and input_sha = ?"))]
      (.setString statement 1 (name (:provider row)))
      (.setString statement 2 (str (:model row)))
      (.setString statement 3 (name role))
      (set-nullable-string! statement 4 (:project-id row))
      (set-nullable-string! statement 5 (:project-id row))
      (set-nullable-string! statement 6 (:repo-id row))
      (set-nullable-string! statement 7 (:repo-id row))
      (.setString statement 8 (str (:target-id row)))
      (.setString statement 9 (str (:input-sha row)))
      (with-open [rs (.executeQuery statement)]
        (when (.next rs)
          (.getLong rs 1))))))

(defn- upsert-vector!
  [conn table-name row]
  (let [dims (count (:vector row))
        id (metadata-id conn row table-name dims)]
    (with-open [statement (.prepareStatement
                           conn
                           (str "insert or replace into "
                                table-name
                                "(embedding_id, embedding) values (?, vec_f32(?))"))]
      (.setLong statement 1 id)
      (.setString statement 2 (vector-json (:vector row)))
      (.executeUpdate statement))))

(defn upsert-embeddings!
  "Upsert embedding rows into the project SQLite sqlite-vec index.

  In `auto` mode, missing sqlite-vec configuration is a no-op. XTDB remains the
  durable source of truth, so sqlite-vec failures never make embedding persistence
  fail unless callers set `YGG_VECTOR_STORE=sqlite-vec`."
  ([rows] (upsert-embeddings! rows {}))
  ([rows {:keys [mode index-path extension-path project-id]}]
   (let [mode (keyword mode)
         rows (vec rows)
         mode (or mode (configured-mode))
         project-id (or project-id (:project-id (first rows)))
         index-path (or index-path (configured-index-path project-id))
         extension-path (or extension-path (configured-extension-path))
         enabled? (sqlite-enabled? {:mode mode :extension-path extension-path})]
     (cond
       (empty? rows)
       {:vector-store :sqlite-vec
        :upserted 0
        :status :skipped
        :reason :empty-batch}

       (not enabled?)
       (if (= :sqlite-vec mode)
         (throw (ex-info "sqlite-vec sidecar is not configured."
                         {:vector-store :sqlite-vec
                          :extension-path extension-path}))
         {:vector-store :xtdb-scan
          :upserted 0
          :status :skipped
          :reason :sqlite-vec-unavailable})

       :else
       (try
         (with-open [conn (sqlite-connection {:index-path index-path
                                              :extension-path extension-path})]
           (doseq [[[dims role] rows] (group-by (fn [row]
                                                  [(count (:vector row))
                                                   (embedding-role row)])
                                                rows)
                   :let [first-row (first rows)
                         table-name (vector-table (:provider first-row)
                                                  (:model first-row)
                                                  dims
                                                  role)]]
             (ensure-schema! conn table-name dims)
             (doseq [row rows]
               (upsert-vector! conn table-name row))))
         {:vector-store :sqlite-vec
          :upserted (count rows)
          :status :ok}
         (catch Exception e
           (if (= :sqlite-vec mode)
             (throw e)
             {:vector-store :xtdb-scan
              :upserted 0
              :status :skipped
              :reason :sqlite-vec-error
              :message (.getMessage e)})))))))

(defn- current-inputs
  [docs]
  (into {} (map (juxt :target-id :input-sha)) docs))

(defn- update-role-score
  [state target-id role score]
  (let [score (double score)]
    (-> state
        (update-in [:scores target-id] #(max (double (or % 0.0)) score))
        (assoc-in [:role-scores target-id role] score))))

(defn- role-score-data
  [state]
  {:scores (:scores state {})
   :role-scores (:role-scores state {})})

(defn- current-embeddings-for-docs
  [xtdb docs {:keys [project-id repo-id provider model read-context]}]
  (let [tuples (map #(select-keys % [:target-id :input-sha]) docs)]
    (store/rows-with-field-tuples
     xtdb
     {:table (:embeddings store/tables)
      :tuple-fields [:target-id :input-sha]
      :tuples tuples
      :constraints {:project-id project-id
                    :repo-id repo-id
                    :provider provider
                    :model model
                    :active? true}
      :return-fields embedding-row-query-fields
      :read-context read-context})))

(defn- xtdb-scan-score-data
  [xtdb docs {:keys [provider model project-id repo-id read-context embed-query
                     query-vector query-embedding-ms]
              :as opts}]
  (let [[embeddings load-timing] (timed :load-embeddings-ms
                                        #(vec (current-embeddings-for-docs
                                               xtdb
                                               docs
                                               {:project-id project-id
                                                :repo-id repo-id
                                                :provider provider
                                                :model model
                                                :read-context read-context})))]
    (if (empty? embeddings)
      {:scores {}
       :empty? true
       :timings (merge load-timing
                       {:query-embedding-ms 0
                        :semantic-score-ms 0
                        :vector-search-ms 0})
       :instrumentation {:vector-store :xtdb-scan
                         :vector-store-fallback-reason :none
                         :vector-roles (requested-embedding-roles opts)
                         :vector-candidates 0}}
      (let [[query-vector query-timing] (if (contains? opts :query-vector)
                                          [query-vector
                                           {:query-embedding-ms (long (or query-embedding-ms 0))}]
                                          (timed :query-embedding-ms embed-query))
            query-vector (if (vector? query-vector)
                           query-vector
                           (vec query-vector))
            query-magnitude (magnitude query-vector)
            current (current-inputs docs)
            role-list (requested-embedding-roles opts)
            roles (set role-list)
            [scores score-timing] (timed
                                   :semantic-score-ms
                                   #(role-score-data
                                     (reduce
                                      (fn [state row]
                                        (let [target-id (:target-id row)]
                                          (if (and (= provider (:provider row))
                                                   (= model (:model row))
                                                   (contains? roles (embedding-role row))
                                                   (= (:input-sha row)
                                                      (get current target-id)))
                                            (let [score (cosine01-with-magnitude
                                                         query-vector
                                                         query-magnitude
                                                         (:vector row))]
                                              (if (pos? score)
                                                (update-role-score state
                                                                   target-id
                                                                   (embedding-role row)
                                                                   score)
                                                state))
                                            state)))
                                      {}
                                      embeddings)))]
        {:scores (:scores scores)
         :role-scores (:role-scores scores)
         :empty? (empty? (:scores scores))
         :timings (merge load-timing
                         query-timing
                         score-timing
                         {:vector-search-ms 0})
         :instrumentation {:vector-store :xtdb-scan
                           :vector-store-fallback-reason :none
                           :vector-roles role-list
                           :vector-candidates (count (:scores scores))}}))))

(defn- score-from-distance
  [distance]
  (max 0.0 (- 1.0 (double distance))))

(defn- requested-values
  [opts singular plural]
  (let [values (or (seq (get opts plural))
                   (when-let [value (get opts singular)] [value]))]
    (when values
      (set (map sql-value values)))))

(defn- allowed-value?
  [allowed value]
  (or (nil? allowed)
      (contains? allowed (sql-value value))))

(defn- current-row?
  [current row]
  (= (:input-sha row) (get current (:target-id row))))

(defn- mechanical-row-match?
  [opts row]
  (and (allowed-value? (requested-values opts :target-kind :target-kinds)
                       (:target-kind row))
       (allowed-value? (requested-values opts :file-kind :file-kinds)
                       (:file-kind row))))

(defn- sqlite-query-window!
  [conn table-name query-vector opts role k]
  (with-open [statement (.prepareStatement
                         conn
                         (str "with knn as ("
                              "select embedding_id, distance from "
                              table-name
                              " where embedding match vec_f32(?) and k = ?"
                              ") "
                              "select m.target_id, m.input_sha, m.target_kind, m.file_kind, m.path, knn.distance "
                              "from knn join ygg_embedding_metadata m on m.id = knn.embedding_id "
                              "where m.provider = ? and m.model = ? and m.embedding_role = ? "
                              "and (m.project_id is ? or m.project_id = ?) "
                              "and (m.repo_id is ? or m.repo_id = ?) "
                              "and m.active = 1 "
                              "order by knn.distance"))]
    (.setString statement 1 (vector-json query-vector))
    (.setLong statement 2 k)
    (.setString statement 3 (name (:provider opts)))
    (.setString statement 4 (str (:model opts)))
    (.setString statement 5 (name role))
    (set-nullable-string! statement 6 (:project-id opts))
    (set-nullable-string! statement 7 (:project-id opts))
    (set-nullable-string! statement 8 (:repo-id opts))
    (set-nullable-string! statement 9 (:repo-id opts))
    (with-open [rs (.executeQuery statement)]
      (loop [rows []]
        (if (.next rs)
          (recur (conj rows {:target-id (.getString rs 1)
                             :input-sha (.getString rs 2)
                             :target-kind (.getString rs 3)
                             :file-kind (.getString rs 4)
                             :path (.getString rs 5)
                             :distance (.getDouble rs 6)}))
          rows)))))

(defn- sqlite-query-role!
  [conn table-name query-vector opts current role]
  (let [candidate-limit (long (:candidate-limit opts default-candidate-limit))
        factors (or (:vector-overfetch-factors opts)
                    default-vector-overfetch-factors)]
    (loop [remaining-factors (seq factors)
           best {:state {}
                 :raw-count 0
                 :stale-count 0
                 :filtered-count 0
                 :overfetch-limit 0}]
      (if-let [factor (first remaining-factors)]
        (let [k (long (max candidate-limit (* candidate-limit (long factor))))
              rows (sqlite-query-window! conn table-name query-vector opts role k)
              raw-count (count rows)
              stale-count (count (remove #(current-row? current %) rows))
              filtered-count (count (filter #(and (current-row? current %)
                                                  (not (mechanical-row-match? opts %)))
                                            rows))
              state (reduce
                     (fn [state row]
                       (if (and (current-row? current row)
                                (mechanical-row-match? opts row))
                         (let [score (score-from-distance (:distance row))]
                           (if (pos? score)
                             (update-role-score state
                                                (:target-id row)
                                                role
                                                score)
                             state))
                         state))
                     {}
                     rows)
              result {:state state
                      :raw-count raw-count
                      :stale-count stale-count
                      :filtered-count filtered-count
                      :overfetch-limit k}]
          (if (or (<= candidate-limit (count (:scores state)))
                  (nil? (next remaining-factors)))
            result
            (recur (next remaining-factors) result)))
        best))))

(defn- merge-role-query-result
  [acc {role-state :state
        :keys [raw-count stale-count filtered-count overfetch-limit]}]
  (-> acc
      (update :raw-count + (long raw-count))
      (update :stale-count + (long stale-count))
      (update :filtered-count + (long filtered-count))
      (update :overfetch-limit max (long overfetch-limit))
      (update :score-state
              (fn [score-state]
                (reduce-kv
                 (fn [score-state target-id role-scores]
                   (reduce-kv
                    (fn [score-state role score]
                      (update-role-score score-state target-id role score))
                    score-state
                    role-scores))
                 (or score-state {})
                 (:role-scores role-state))))))

(defn- sqlite-score-data
  [docs {:keys [provider model embed-query] :as opts}]
  (let [[query-vector query-timing] (timed :query-embedding-ms embed-query)
        query-vector (if (vector? query-vector)
                       query-vector
                       (vec query-vector))
        dims (count query-vector)
        role-list (requested-embedding-roles opts)
        current (current-inputs docs)
        sqlite-opts {:index-path (or (:vector-index-path opts)
                                     (configured-index-path (:project-id opts)))
                     :extension-path (or (:sqlite-vec-extension opts)
                                         (configured-extension-path))}]
    (with-open [conn (sqlite-connection sqlite-opts)]
      (let [[query-result vector-timing]
            (timed :vector-search-ms
                   #(reduce
                     (fn [state role]
                       (let [table-name (vector-table provider model dims role)]
                         (ensure-schema! conn table-name dims)
                         (merge-role-query-result
                          state
                          (sqlite-query-role! conn
                                              table-name
                                              query-vector
                                              opts
                                              current
                                              role))))
                     {:score-state {}
                      :raw-count 0
                      :stale-count 0
                      :filtered-count 0
                      :overfetch-limit 0}
                     role-list))
            scores (role-score-data (:score-state query-result))]
        {:scores (:scores scores)
         :role-scores (:role-scores scores)
         :empty? (empty? (:scores scores))
         :timings (merge {:load-embeddings-ms 0
                          :semantic-score-ms 0}
                         query-timing
                         vector-timing)
         :instrumentation {:vector-store :sqlite-vec
                           :vector-store-fallback-reason :none
                           :vector-roles role-list
                           :vector-requested-candidate-limit (long (:candidate-limit opts default-candidate-limit))
                           :vector-overfetch-limit (:overfetch-limit query-result)
                           :vector-raw-candidates (:raw-count query-result)
                           :vector-stale-candidates (:stale-count query-result)
                           :vector-filtered-candidates (:filtered-count query-result)
                           :vector-post-filter-candidates (count (:scores scores))
                           :vector-candidates (count (:scores scores))}
         :query-vector query-vector}))))

(defn- ensure-fts-schema!
  [conn]
  (execute! conn
            (str "create table if not exists ygg_search_doc_metadata "
                 "(id integer primary key autoincrement, "
                 "project_id text, "
                 "repo_id text, "
                 "target_id text not null, "
                 "target_kind text, "
                 "file_kind text, "
                 "input_sha text not null, "
                 "path text, "
                 "title text, "
                 "active integer not null default 1, "
                 "updated_at_ms integer, "
                 "unique(project_id, repo_id, target_id, input_sha))"))
  (execute! conn
            (str "create virtual table if not exists ygg_search_doc_fts "
                 "using fts5(title, body, tokenize='unicode61')")))

(defn- search-doc-metadata-id
  [conn doc]
  (with-open [statement (.prepareStatement
                         conn
                         (str "insert into ygg_search_doc_metadata "
                              "(project_id, repo_id, target_id, target_kind, file_kind, "
                              "input_sha, path, title, active, updated_at_ms) "
                              "values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?) "
                              "on conflict(project_id, repo_id, target_id, input_sha) "
                              "do update set target_kind = excluded.target_kind, "
                              "file_kind = excluded.file_kind, path = excluded.path, "
                              "title = excluded.title, active = 1, "
                              "updated_at_ms = excluded.updated_at_ms"))]
    (set-nullable-string! statement 1 (:project-id doc))
    (set-nullable-string! statement 2 (:repo-id doc))
    (.setString statement 3 (str (:target-id doc)))
    (set-nullable-string! statement 4 (sql-value (:target-kind doc)))
    (set-nullable-string! statement 5 (sql-value (:kind doc)))
    (.setString statement 6 (str (:input-sha doc)))
    (set-nullable-string! statement 7 (:path doc))
    (set-nullable-string! statement 8 (:label doc))
    (.setLong statement 9 (System/currentTimeMillis))
    (.executeUpdate statement))
  (with-open [statement (.prepareStatement
                         conn
                         (str "select id from ygg_search_doc_metadata "
                              "where (project_id is ? or project_id = ?) "
                              "and (repo_id is ? or repo_id = ?) "
                              "and target_id = ? and input_sha = ?"))]
    (set-nullable-string! statement 1 (:project-id doc))
    (set-nullable-string! statement 2 (:project-id doc))
    (set-nullable-string! statement 3 (:repo-id doc))
    (set-nullable-string! statement 4 (:repo-id doc))
    (.setString statement 5 (str (:target-id doc)))
    (.setString statement 6 (str (:input-sha doc)))
    (with-open [rs (.executeQuery statement)]
      (when (.next rs)
        (.getLong rs 1)))))

(defn- current-fts-search-doc-row
  [conn doc]
  (with-open [statement (.prepareStatement
                         conn
                         (str "select m.id, m.target_kind, m.file_kind, "
                              "m.path, m.title, "
                              "exists(select 1 from ygg_search_doc_fts f "
                              "where f.rowid = m.id) "
                              "from ygg_search_doc_metadata m "
                              "where (m.project_id is ? or m.project_id = ?) "
                              "and (m.repo_id is ? or m.repo_id = ?) "
                              "and m.target_id = ? and m.input_sha = ? "
                              "and m.active = 1 "
                              "limit 1"))]
    (set-nullable-string! statement 1 (:project-id doc))
    (set-nullable-string! statement 2 (:project-id doc))
    (set-nullable-string! statement 3 (:repo-id doc))
    (set-nullable-string! statement 4 (:repo-id doc))
    (.setString statement 5 (str (:target-id doc)))
    (.setString statement 6 (str (:input-sha doc)))
    (with-open [rs (.executeQuery statement)]
      (when (.next rs)
        {:id (.getLong rs 1)
         :target-kind (.getString rs 2)
         :file-kind (.getString rs 3)
         :path (.getString rs 4)
         :title (.getString rs 5)
         :fts-row? (pos? (.getLong rs 6))}))))

(defn- fts-search-doc-current?
  [row doc]
  (and row
       (:fts-row? row)
       (= (sql-value (:target-kind doc)) (:target-kind row))
       (= (sql-value (:kind doc)) (:file-kind row))
       (= (sql-value (:path doc)) (:path row))
       (= (sql-value (:label doc)) (:title row))))

(defn- upsert-search-doc!
  [conn doc]
  (if (fts-search-doc-current? (current-fts-search-doc-row conn doc) doc)
    {:status :skipped}
    (let [id (search-doc-metadata-id conn doc)]
      (with-open [delete-statement (.prepareStatement
                                    conn
                                    "delete from ygg_search_doc_fts where rowid = ?")]
        (.setLong delete-statement 1 id)
        (.executeUpdate delete-statement))
      (with-open [statement (.prepareStatement
                             conn
                             "insert into ygg_search_doc_fts(rowid, title, body) values (?, ?, ?)")]
        (.setLong statement 1 id)
        (set-nullable-string! statement 2 (:label doc))
        (set-nullable-string! statement 3 (:text doc))
        (.executeUpdate statement))
      {:status :upserted})))

(defn- upsert-search-doc-batch!
  [conn docs]
  (reduce
   (fn [stats doc]
     (case (:status (upsert-search-doc! conn doc))
       :skipped (update stats :skipped inc)
       :upserted (update stats :upserted inc)
       stats))
   {:upserted 0
    :skipped 0}
   docs))

(defn upsert-search-docs!
  "Upsert search docs into the rebuildable SQLite FTS sidecar."
  ([docs] (upsert-search-docs! docs {}))
  ([docs {:keys [index-path project-id]}]
   (let [docs (vec docs)
         index-path (or index-path (configured-index-path project-id))]
     (if (empty? docs)
       {:fts-store :sqlite-fts
        :upserted 0
        :status :skipped
        :reason :empty-batch}
       (with-open [conn (sqlite-base-connection {:index-path index-path})]
         (ensure-fts-schema! conn)
         (let [stats (upsert-search-doc-batch! conn docs)]
           {:fts-store :sqlite-fts
            :upserted (:upserted stats)
            :skipped (:skipped stats)
            :status :ok}))))))

(defn- fts-requested?
  [opts]
  (cond
    (contains? opts :sqlite-fts?) (boolean (:sqlite-fts? opts))
    (contains? opts :fts?) (boolean (:fts? opts))
    :else (configured-fts?)))

(defn- fts-query-string
  [query-tokens]
  (->> query-tokens
       distinct
       (take 12)
       (map #(str "\"" (str/replace (str %) "\"" "\"\"") "\""))
       (str/join " OR ")))

(defn- normalize-scores
  [scores]
  (let [scores (into {}
                     (keep (fn [[k v]]
                             (when (number? v)
                               [k (double v)])))
                     scores)
        max-score (reduce max 0.0 (vals scores))]
    (if (pos? max-score)
      (into {} (map (fn [[k v]] [k (/ (double v) max-score)])) scores)
      scores)))

(defn- fts-raw-score
  [rank]
  (when (number? rank)
    (let [rank (double rank)]
      (if (neg? rank)
        (- rank)
        (/ 1.0 (+ 1.0 rank))))))

(defn- fts-query!
  [conn docs query-tokens opts]
  (let [query (fts-query-string query-tokens)
        current (current-inputs docs)
        candidate-limit (long (or (:fts-candidate-limit opts)
                                  default-fts-candidate-limit))]
    (if (str/blank? query)
      {:scores {}
       :raw-count 0
       :stale-count 0
       :filtered-count 0}
      (with-open [statement (.prepareStatement
                             conn
                             (str "select m.target_id, m.input_sha, m.target_kind, m.file_kind, "
                                  "m.path, bm25(ygg_search_doc_fts) as rank "
                                  "from ygg_search_doc_fts "
                                  "join ygg_search_doc_metadata m on m.id = ygg_search_doc_fts.rowid "
                                  "where ygg_search_doc_fts match ? "
                                  "and (m.project_id is ? or m.project_id = ?) "
                                  "and (m.repo_id is ? or m.repo_id = ?) "
                                  "and m.active = 1 "
                                  "order by rank limit ?"))]
        (.setString statement 1 query)
        (set-nullable-string! statement 2 (:project-id opts))
        (set-nullable-string! statement 3 (:project-id opts))
        (set-nullable-string! statement 4 (:repo-id opts))
        (set-nullable-string! statement 5 (:repo-id opts))
        (.setLong statement 6 candidate-limit)
        (with-open [rs (.executeQuery statement)]
          (loop [scores {}
                 raw-count 0
                 stale-count 0
                 filtered-count 0
                 null-rank-count 0]
            (if (.next rs)
              (let [row {:target-id (.getString rs 1)
                         :input-sha (.getString rs 2)
                         :target-kind (.getString rs 3)
                         :file-kind (.getString rs 4)
                         :path (.getString rs 5)}
                    score (fts-raw-score (.getObject rs 6))]
                (cond
                  (nil? score)
                  (recur scores (inc raw-count) stale-count filtered-count (inc null-rank-count))

                  (not (current-row? current row))
                  (recur scores (inc raw-count) (inc stale-count) filtered-count null-rank-count)

                  (not (mechanical-row-match? opts row))
                  (recur scores (inc raw-count) stale-count (inc filtered-count) null-rank-count)

                  :else
                  (recur (assoc scores (:target-id row) score)
                         (inc raw-count)
                         stale-count
                         filtered-count
                         null-rank-count)))
              {:scores (normalize-scores scores)
               :raw-count raw-count
               :stale-count stale-count
               :filtered-count filtered-count
               :null-rank-count null-rank-count})))))))

(defn fts-score-data
  "Return SQLite FTS scores for docs when explicitly enabled."
  [docs query-tokens {:keys [project-id vector-index-path] :as opts}]
  (if-not (fts-requested? opts)
    {:scores {}
     :empty? true
     :timings {:fts-index-ms 0
               :fts-search-ms 0}
     :instrumentation {:fts-store :none
                       :fts-status :disabled
                       :fts-candidates 0}}
    (let [docs (vec docs)
          index-path (or vector-index-path
                         (:index-path opts)
                         (configured-index-path project-id))]
      (try
        (with-open [conn (sqlite-base-connection {:index-path index-path})]
          (ensure-fts-schema! conn)
          (let [[index-stats index-timing] (if (:skip-fts-upsert? opts)
                                             [{:upserted 0
                                               :skipped 0}
                                              {:fts-index-ms 0}]
                                             (timed :fts-index-ms
                                                    #(upsert-search-doc-batch!
                                                      conn
                                                      docs)))
                [result search-timing] (timed :fts-search-ms
                                              #(fts-query! conn docs query-tokens opts))]
            {:scores (:scores result)
             :empty? (empty? (:scores result))
             :timings (merge index-timing search-timing)
             :instrumentation {:fts-store :sqlite-fts
                               :fts-status :ok
                               :fts-candidates (count (:scores result))
                               :fts-raw-candidates (:raw-count result)
                               :fts-stale-candidates (:stale-count result)
                               :fts-filtered-candidates (:filtered-count result)
                               :fts-null-rank-candidates (:null-rank-count result)
                               :fts-upserted-search-docs (:upserted index-stats)
                               :fts-skipped-search-docs (:skipped index-stats)
                               :fts-indexed-search-docs (count docs)}}))
        (catch Exception e
          {:scores {}
           :empty? true
           :timings {:fts-index-ms 0
                     :fts-search-ms 0}
           :instrumentation {:fts-store :sqlite-fts
                             :fts-status :error
                             :fts-error-class (.getName (class e))
                             :fts-error (.getMessage e)
                             :fts-candidates 0}})))))

(defn semantic-score-data
  "Return semantic scores and backend instrumentation for current docs."
  [xtdb docs {:keys [mode] :as opts}]
  (let [mode (keyword (or mode (configured-mode)))
        sqlite-enabled? (sqlite-enabled?
                         {:mode mode
                          :extension-path (or (:sqlite-vec-extension opts)
                                              (configured-extension-path))})]
    (cond
      (= :xtdb-scan mode)
      (xtdb-scan-score-data xtdb docs opts)

      sqlite-enabled?
      (try
        (let [data (sqlite-score-data docs opts)]
          (if (:empty? data)
            (-> (xtdb-scan-score-data
                 xtdb
                 docs
                 (assoc opts
                        :query-vector (:query-vector data)
                        :query-embedding-ms (get-in data
                                                    [:timings :query-embedding-ms])))
                (assoc-in [:instrumentation :vector-store-fallback-reason]
                          :sqlite-vec-empty))
            (dissoc data :query-vector)))
        (catch Exception e
          (if (= :sqlite-vec mode)
            (throw e)
            (-> (xtdb-scan-score-data xtdb docs opts)
                (assoc-in [:instrumentation :vector-store-fallback-reason]
                          :sqlite-vec-error)
                (assoc-in [:instrumentation :vector-store-error]
                          (.getMessage e))))))

      (= :sqlite-vec mode)
      (throw (ex-info "sqlite-vec sidecar is not configured."
                      {:vector-store :sqlite-vec
                       :extension-path (configured-extension-path)}))

      :else
      (-> (xtdb-scan-score-data xtdb docs opts)
          (assoc-in [:instrumentation :vector-store-fallback-reason]
                    :sqlite-vec-unavailable)))))
