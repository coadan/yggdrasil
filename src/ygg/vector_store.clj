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

(def embedding-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :target-id
   :provider
   :model
   :dims
   :input-sha
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

(defn- dot
  [a b]
  (reduce + 0.0 (map * a b)))

(defn- magnitude
  [v]
  (Math/sqrt (double (dot v v))))

(defn- cosine01
  [a b]
  (let [denom (* (magnitude a) (magnitude b))]
    (if (zero? denom)
      0.0
      (/ (+ 1.0 (double (/ (dot a b) denom))) 2.0))))

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

(defn- execute!
  [conn sql]
  (with-open [statement (.createStatement conn)]
    (.execute statement sql)))

(defn- safe-ident
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_]" "_")))

(defn- vector-table
  [provider model dims]
  (str "ygg_vec_"
       (safe-ident (hash/short-hash [(name provider) model dims]))))

(defn- vector-json
  [v]
  (str "[" (str/join "," (map #(Double/toString (double %)) v)) "]"))

(defn- ensure-schema!
  [conn table-name dims]
  (execute! conn
            (str "create table if not exists ygg_embedding_metadata "
                 "(id integer primary key autoincrement, "
                 "table_name text not null, "
                 "project_id text, "
                 "repo_id text, "
                 "provider text not null, "
                 "model text not null, "
                 "target_id text not null, "
                 "input_sha text not null, "
                 "dims integer not null, "
                 "active integer not null default 1, "
                 "unique(provider, model, project_id, repo_id, target_id, input_sha))"))
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
  (with-open [statement (.prepareStatement
                         conn
                         (str "insert into ygg_embedding_metadata "
                              "(table_name, project_id, repo_id, provider, model, target_id, input_sha, dims, active) "
                              "values (?, ?, ?, ?, ?, ?, ?, ?, 1) "
                              "on conflict(provider, model, project_id, repo_id, target_id, input_sha) "
                              "do update set table_name = excluded.table_name, "
                              "dims = excluded.dims, active = 1"))]
    (.setString statement 1 table-name)
    (set-nullable-string! statement 2 (:project-id row))
    (set-nullable-string! statement 3 (:repo-id row))
    (.setString statement 4 (name (:provider row)))
    (.setString statement 5 (str (:model row)))
    (.setString statement 6 (str (:target-id row)))
    (.setString statement 7 (str (:input-sha row)))
    (.setLong statement 8 (long dims))
    (.executeUpdate statement))
  (with-open [statement (.prepareStatement
                         conn
                         (str "select id from ygg_embedding_metadata "
                              "where provider = ? and model = ? "
                              "and (project_id is ? or project_id = ?) "
                              "and (repo_id is ? or repo_id = ?) "
                              "and target_id = ? and input_sha = ?"))]
    (.setString statement 1 (name (:provider row)))
    (.setString statement 2 (str (:model row)))
    (set-nullable-string! statement 3 (:project-id row))
    (set-nullable-string! statement 4 (:project-id row))
    (set-nullable-string! statement 5 (:repo-id row))
    (set-nullable-string! statement 6 (:repo-id row))
    (.setString statement 7 (str (:target-id row)))
    (.setString statement 8 (str (:input-sha row)))
    (with-open [rs (.executeQuery statement)]
      (when (.next rs)
        (.getLong rs 1)))))

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
           (doseq [[dims rows] (group-by (comp count :vector) rows)
                   :let [first-row (first rows)
                         table-name (vector-table (:provider first-row)
                                                  (:model first-row)
                                                  dims)]]
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
                         :vector-candidates 0}}
      (let [[query-vector query-timing] (if (contains? opts :query-vector)
                                          [query-vector
                                           {:query-embedding-ms (long (or query-embedding-ms 0))}]
                                          (timed :query-embedding-ms embed-query))
            current (current-inputs docs)
            [scores score-timing] (timed
                                   :semantic-score-ms
                                   #(reduce
                                     (fn [scores row]
                                       (let [target-id (:target-id row)]
                                         (if (and (= provider (:provider row))
                                                  (= model (:model row))
                                                  (= (:input-sha row)
                                                     (get current target-id)))
                                           (let [score (cosine01 query-vector
                                                                 (:vector row))]
                                             (if (pos? score)
                                               (assoc scores target-id score)
                                               scores))
                                           scores)))
                                     {}
                                     embeddings))]
        {:scores scores
         :empty? (empty? scores)
         :timings (merge load-timing
                         query-timing
                         score-timing
                         {:vector-search-ms 0})
         :instrumentation {:vector-store :xtdb-scan
                           :vector-store-fallback-reason :none
                           :vector-candidates (count scores)}}))))

(defn- score-from-distance
  [distance]
  (max 0.0 (- 1.0 (double distance))))

(defn- sqlite-query!
  [conn table-name query-vector opts current]
  (let [k (long (max (:candidate-limit opts default-candidate-limit)
                     (* 4 (:candidate-limit opts default-candidate-limit))))]
    (with-open [statement (.prepareStatement
                           conn
                           (str "with knn as ("
                                "select embedding_id, distance from "
                                table-name
                                " where embedding match vec_f32(?) and k = ?"
                                ") "
                                "select m.target_id, m.input_sha, knn.distance "
                                "from knn join ygg_embedding_metadata m on m.id = knn.embedding_id "
                                "where m.provider = ? and m.model = ? "
                                "and (m.project_id is ? or m.project_id = ?) "
                                "and (m.repo_id is ? or m.repo_id = ?) "
                                "and m.active = 1 "
                                "order by knn.distance"))]
      (.setString statement 1 (vector-json query-vector))
      (.setLong statement 2 k)
      (.setString statement 3 (name (:provider opts)))
      (.setString statement 4 (str (:model opts)))
      (set-nullable-string! statement 5 (:project-id opts))
      (set-nullable-string! statement 6 (:project-id opts))
      (set-nullable-string! statement 7 (:repo-id opts))
      (set-nullable-string! statement 8 (:repo-id opts))
      (with-open [rs (.executeQuery statement)]
        (loop [scores {}
               seen 0]
          (if (and (.next rs) (< seen (:candidate-limit opts default-candidate-limit)))
            (let [target-id (.getString rs 1)
                  input-sha (.getString rs 2)
                  distance (.getDouble rs 3)]
              (if (= input-sha (get current target-id))
                (recur (assoc scores target-id (score-from-distance distance))
                       (inc seen))
                (recur scores seen)))
            scores))))))

(defn- sqlite-score-data
  [docs {:keys [provider model embed-query] :as opts}]
  (let [[query-vector query-timing] (timed :query-embedding-ms embed-query)
        dims (count query-vector)
        table-name (vector-table provider model dims)
        current (current-inputs docs)
	        sqlite-opts {:index-path (or (:vector-index-path opts)
	                                     (configured-index-path (:project-id opts)))
	                     :extension-path (or (:sqlite-vec-extension opts)
	                                         (configured-extension-path))}]
    (with-open [conn (sqlite-connection sqlite-opts)]
      (ensure-schema! conn table-name dims)
      (let [[scores vector-timing] (timed :vector-search-ms
                                          #(sqlite-query!
                                            conn
                                            table-name
                                            query-vector
                                            opts
                                            current))]
        {:scores scores
         :empty? (empty? scores)
         :timings (merge {:load-embeddings-ms 0
                          :semantic-score-ms 0}
                         query-timing
                         vector-timing)
         :instrumentation {:vector-store :sqlite-vec
                           :vector-store-fallback-reason :none
                           :vector-candidates (count scores)}
         :query-vector query-vector}))))

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
