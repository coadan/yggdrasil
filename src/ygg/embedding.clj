(ns ygg.embedding
  "Embedding indexing and vector helpers."
  (:require [charred.api :as json]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [ygg.vector-store :as vector-store]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.sql DriverManager PreparedStatement]))

(def default-batch-size 64)

(def default-provider-input-max-chars
  6000)

(def default-embedding-role :content)

(def embedding-cache-schema-version
  1)

(defn default-cache-path
  "Return the central reusable embedding cache path."
  []
  (or (System/getenv "YGG_EMBEDDING_CACHE_PATH")
      (str (io/file (store/storage-root) "embedding-cache.sqlite"))))

(defn sqlite-cache
  "Return a lazy SQLite-backed embedding cache descriptor.

  The connection is opened only when lookup or store work happens, so callers
  can create a cache descriptor for benchmark runs that may not need embeddings."
  ([] (sqlite-cache (default-cache-path)))
  ([path]
   {:type :sqlite
    :path (str path)
    :memory (atom {})
    :connection (atom nil)}))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn embedding-id
  "Return stable embedding id for target/model/input."
  ([target-id provider model input-sha]
   (embedding-id target-id provider model input-sha default-embedding-role))
  ([target-id provider model input-sha embedding-role]
   (let [embedding-role (keyword (or embedding-role default-embedding-role))
         basis (if (= default-embedding-role embedding-role)
                 [target-id provider model input-sha]
                 [target-id provider model input-sha embedding-role])]
     (str "embedding:" (hash/short-hash basis)))))

(defn dot
  "Return dot product of two numeric vectors."
  [a b]
  (reduce + 0.0 (map * a b)))

(defn magnitude
  [v]
  (Math/sqrt (double (dot v v))))

(defn cosine
  "Return cosine similarity for two numeric vectors."
  [a b]
  (let [denom (* (magnitude a) (magnitude b))]
    (if (zero? denom)
      0.0
      (double (/ (dot a b) denom)))))

(defn cosine01
  "Return cosine normalized from [-1, 1] to [0, 1]."
  [a b]
  (/ (+ 1.0 (cosine a b)) 2.0))

(defn embedding-row
  "Return an embedding row."
  [{:keys [target-id project-id repo-id provider model input-sha vector created-at-ms
           embedding-role target-kind file-kind path]}]
  (let [embedding-role (keyword (or embedding-role default-embedding-role))]
    (cond-> {:xt/id (embedding-id target-id provider model input-sha embedding-role)
             :target-id target-id
             :provider provider
             :model model
             :embedding-role embedding-role
             :dims (count vector)
             :input-sha input-sha
             :vector (mapv double vector)
             :created-at-ms (long (or created-at-ms (now-ms)))
             :active? true}
      project-id (assoc :project-id project-id)
      repo-id (assoc :repo-id repo-id)
      target-kind (assoc :target-kind target-kind)
      file-kind (assoc :file-kind file-kind)
      path (assoc :path path))))

(defn all-search-docs
  ([xtdb] (all-search-docs xtdb {}))
  ([xtdb {:keys [project-id repo-id]}]
   (store/constrained-rows xtdb
                           (:search-docs store/tables)
                           {:project-id project-id
                            :repo-id repo-id
                            :active? true})))

(defn search-doc-count
  ([xtdb] (search-doc-count xtdb {}))
  ([xtdb {:keys [project-id repo-id]}]
   (store/count-rows xtdb
                     (:search-docs store/tables)
                     {:project-id project-id
                      :repo-id repo-id
                      :active? true}
                     {})))

(defn all-embeddings
  ([xtdb] (all-embeddings xtdb {}))
  ([xtdb {:keys [project-id repo-id provider model embedding-role]}]
   (store/constrained-rows xtdb
                           (:embeddings store/tables)
                           (cond-> {:project-id project-id
                                    :repo-id repo-id
                                    :provider provider
                                    :model model
                                    :active? true}
                             embedding-role (assoc :embedding-role (keyword embedding-role))))))

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

(def embedding-key-query-fields
  [:xt/id
   :project-id
   :repo-id
   :target-id
   :provider
   :model
   :embedding-role
   :input-sha
   :active?])

(defn- embedded-key
  [{:keys [target-id provider model input-sha embedding-role]}]
  [target-id provider model (keyword (or embedding-role default-embedding-role)) input-sha])

(defn- embedding-key-rows
  [xtdb {:keys [project-id repo-id provider model embedding-role read-context]}]
  (store/ordered-rows
   xtdb
   {:table (:embeddings store/tables)
    :constraints (cond-> {:project-id project-id
                          :repo-id repo-id
                          :provider provider
                          :model model
                          :active? true}
                   embedding-role
                   (assoc :embedding-role (keyword embedding-role)))
    :return-fields embedding-key-query-fields
    :read-context read-context}))

(defn current-embeddings-for-docs
  "Return active embedding rows that exactly match docs' target/input tuples."
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

(defn pending-search-docs
  "Return search docs without a current embedding for provider/model/input."
  [xtdb {:keys [provider model limit project-id repo-id embedding-role read-context]}]
  (let [docs (vec (all-search-docs xtdb {:project-id project-id
                                         :repo-id repo-id}))
        embedding-role (keyword (or embedding-role default-embedding-role))
        doc-keys (into #{}
                       (map #(vector (:target-id %)
                                     provider
                                     model
                                     embedding-role
                                     (:input-sha %)))
                       docs)
        done (into #{}
                   (comp (map embedded-key)
                         (filter doc-keys))
                   (embedding-key-rows xtdb
                                       {:project-id project-id
                                        :repo-id repo-id
                                        :provider provider
                                        :model model
                                        :embedding-role embedding-role
                                        :read-context read-context}))
        docs (remove #(contains? done [(:target-id %) provider model embedding-role (:input-sha %)])
                     docs)]
    (if limit
      (take limit docs)
      docs)))

(defn- provider-input-text
  [doc max-chars]
  (let [text (str (:text doc))
        text (if (str/blank? text)
               "[blank search document]"
               text)]
    (if (and max-chars (< (long max-chars) (count text)))
      (subs text 0 (long max-chars))
      text)))

(defn- embedding-cache-key
  [doc provider model embedding-role input-max-chars]
  [provider
   model
   (keyword (or embedding-role default-embedding-role))
   (long input-max-chars)
   (:repo-id doc)
   (:target-kind doc)
   (:path doc)
   (:kind doc)
   (:input-sha doc)])

(defn- sqlite-cache?
  [cache]
  (= :sqlite (:type cache)))

(defn- ensure-parent-dir!
  [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  path)

(defn- sqlite-cache-schema!
  [conn]
  (with-open [statement (.createStatement conn)]
    (.execute statement
              (str "create table if not exists ygg_embedding_cache "
                   "(cache_key text primary key, "
                   "schema_version integer not null, "
                   "provider text not null, "
                   "model text not null, "
                   "embedding_role text not null, "
                   "input_max_chars integer not null, "
                   "repo_id text, "
                   "target_kind text, "
                   "path text, "
                   "file_kind text, "
                   "input_sha text not null, "
                   "dims integer not null, "
                   "vector_json text not null, "
                   "updated_at_ms integer not null)"))))

(defn- sqlite-cache-connection!
  [cache]
  (let [holder (:connection cache)]
    (or @holder
        (locking holder
          (or @holder
              (let [conn (DriverManager/getConnection
                          (str "jdbc:sqlite:"
                               (ensure-parent-dir! (:path cache))))]
                (sqlite-cache-schema! conn)
                (reset! holder conn)
                conn))))))

(defn close-cache!
  "Close a cache descriptor created by `sqlite-cache`."
  [cache]
  (when (sqlite-cache? cache)
    (let [holder (:connection cache)]
      (when holder
        (locking holder
          (when-let [conn @holder]
            (.close conn)
            (reset! holder nil)))))))

(defn- cache-key-id
  [key]
  (hash/sha256-hex (pr-str key)))

(defn- vector-json
  [vector]
  (json/write-json-str (mapv double vector)))

(defn- parse-vector-json
  [value]
  (mapv double (json/read-json value)))

(defn- nullable-cache-value
  [value]
  (when (some? value)
    (if (keyword? value)
      (name value)
      (str value))))

(defn- named-cache-value
  [value]
  (if (keyword? value)
    (name value)
    (str value)))

(defn- set-nullable-string!
  [^PreparedStatement statement idx value]
  (if (nil? value)
    (.setObject statement idx nil)
    (.setString statement idx (str value))))

(defn- key-cache-row
  [key vector]
  (let [[provider model embedding-role input-max-chars repo-id target-kind path file-kind input-sha] key]
    {:cache-key (cache-key-id key)
     :schema-version embedding-cache-schema-version
     :provider (named-cache-value provider)
     :model (str model)
     :embedding-role (named-cache-value (or embedding-role default-embedding-role))
     :input-max-chars (long input-max-chars)
     :repo-id (nullable-cache-value repo-id)
     :target-kind (nullable-cache-value target-kind)
     :path (nullable-cache-value path)
     :file-kind (nullable-cache-value file-kind)
     :input-sha (str input-sha)
     :dims (count vector)
     :vector-json (vector-json vector)
     :updated-at-ms (now-ms)}))

(defn- sqlite-cache-lookup-many
  [cache cache-keys]
  (let [memory (:memory cache)
        cached @memory
        misses (remove #(contains? cached %) cache-keys)]
    (if (empty? misses)
      (select-keys cached cache-keys)
      (let [conn (sqlite-cache-connection! cache)
            hash->key (into {} (map (juxt cache-key-id identity)) misses)
            loaded (locking conn
                     (reduce
                      (fn [acc batch]
                        (let [placeholders (str/join "," (repeat (count batch) "?"))
                              sql (str "select cache_key, vector_json "
                                       "from ygg_embedding_cache "
                                       "where cache_key in (" placeholders ")")]
                          (with-open [statement (.prepareStatement conn sql)]
                            (doseq [[idx cache-key] (map-indexed vector batch)]
                              (.setString statement (inc idx) cache-key))
                            (with-open [rs (.executeQuery statement)]
                              (loop [acc acc]
                                (if (.next rs)
                                  (let [key (get hash->key (.getString rs "cache_key"))]
                                    (recur (assoc acc
                                                  key
                                                  (parse-vector-json
                                                   (.getString rs "vector_json")))))
                                  acc))))))
                      {}
                      (partition-all 500 (clojure.core/keys hash->key))))]
        (when (seq loaded)
          (swap! memory merge loaded))
        (merge (select-keys cached cache-keys)
               loaded)))))

(defn- cache-lookup-many
  [cache cache-keys]
  (cond
    (nil? cache)
    {}

    (sqlite-cache? cache)
    (sqlite-cache-lookup-many cache cache-keys)

    :else
    (select-keys @cache cache-keys)))

(defn- sqlite-cache-store-many!
  [cache entries]
  (when (seq entries)
    (swap! (:memory cache) merge (into {} entries))
    (let [conn (sqlite-cache-connection! cache)]
      (locking conn
        (let [auto-commit? (.getAutoCommit conn)]
          (try
            (.setAutoCommit conn false)
            (with-open [statement (.prepareStatement
                                   conn
                                   (str "insert into ygg_embedding_cache "
                                        "(cache_key, schema_version, provider, model, embedding_role, "
                                        "input_max_chars, repo_id, target_kind, path, file_kind, "
                                        "input_sha, dims, vector_json, updated_at_ms) "
                                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                        "on conflict(cache_key) do update set "
                                        "schema_version = excluded.schema_version, "
                                        "provider = excluded.provider, "
                                        "model = excluded.model, "
                                        "embedding_role = excluded.embedding_role, "
                                        "input_max_chars = excluded.input_max_chars, "
                                        "repo_id = excluded.repo_id, "
                                        "target_kind = excluded.target_kind, "
                                        "path = excluded.path, "
                                        "file_kind = excluded.file_kind, "
                                        "input_sha = excluded.input_sha, "
                                        "dims = excluded.dims, "
                                        "vector_json = excluded.vector_json, "
                                        "updated_at_ms = excluded.updated_at_ms"))]
              (doseq [[key vector] entries
                      :let [row (key-cache-row key vector)]]
                (.setString statement 1 (:cache-key row))
                (.setLong statement 2 (:schema-version row))
                (.setString statement 3 (:provider row))
                (.setString statement 4 (:model row))
                (.setString statement 5 (:embedding-role row))
                (.setLong statement 6 (:input-max-chars row))
                (set-nullable-string! statement 7 (:repo-id row))
                (set-nullable-string! statement 8 (:target-kind row))
                (set-nullable-string! statement 9 (:path row))
                (set-nullable-string! statement 10 (:file-kind row))
                (.setString statement 11 (:input-sha row))
                (.setLong statement 12 (:dims row))
                (.setString statement 13 (:vector-json row))
                (.setLong statement 14 (:updated-at-ms row))
                (.addBatch statement))
              (.executeBatch statement))
            (.commit conn)
            (catch Throwable t
              (.rollback conn)
              (throw t))
            (finally
              (.setAutoCommit conn auto-commit?))))))))

(defn- cache-store-many!
  [cache entries]
  (cond
    (nil? cache)
    nil

    (sqlite-cache? cache)
    (sqlite-cache-store-many! cache entries)

    :else
    (swap! cache merge (into {} entries))))

(defn- doc-embedding-row
  [doc provider model embedding-role vector]
  (embedding-row {:target-id (:target-id doc)
                  :project-id (:project-id doc)
                  :repo-id (:repo-id doc)
                  :provider provider
                  :model model
                  :input-sha (:input-sha doc)
                  :embedding-role embedding-role
                  :target-kind (:target-kind doc)
                  :file-kind (:kind doc)
                  :path (:path doc)
                  :vector vector}))

(defn embed-search-docs!
  "Embed pending search docs with client map and persist rows."
  [xtdb {:keys [provider model embed-batch close] :as client} {:keys [batch-size limit project-id repo-id
                                                                      input-max-chars
                                                                      embedding-role
                                                                      embedding-cache
                                                                      provider-target-ids
                                                                      max-provider-docs
                                                                      on-progress]
                                                               :or {batch-size default-batch-size
                                                                    input-max-chars default-provider-input-max-chars}}]
  (when-not (and provider model embed-batch)
    (throw (ex-info "Invalid embedding client." {:client (select-keys client [:provider :model])})))
  (try
    (let [scope {:project-id project-id :repo-id repo-id}
          embedding-role (keyword (or embedding-role default-embedding-role))
          pending (vec (pending-search-docs xtdb (assoc scope
                                                        :provider provider
                                                        :model model
                                                        :embedding-role embedding-role
                                                        :limit limit)))
          pending-with-keys (mapv (fn [doc]
                                    {:doc doc
                                     :cache-key (embedding-cache-key doc
                                                                     provider
                                                                     model
                                                                     embedding-role
                                                                     input-max-chars)})
                                  pending)
          cached-by-key (cache-lookup-many embedding-cache
                                           (mapv :cache-key pending-with-keys))
          pending-with-cache (mapv (fn [{:keys [cache-key] :as item}]
                                     (assoc item
                                            :cached-vector
                                            (get cached-by-key cache-key)))
                                   pending-with-keys)
          cached-rows (->> pending-with-cache
                           (keep (fn [{:keys [doc cached-vector]}]
                                   (when cached-vector
                                     (doc-embedding-row doc
                                                        provider
                                                        model
                                                        embedding-role
                                                        cached-vector))))
                           vec)
          cache-result (if (seq cached-rows)
                         (let [result (store/commit-embeddings! xtdb cached-rows)]
                           (vector-store/upsert-embeddings! cached-rows)
                           result)
                         {:embeddings 0})
          cache-hits (long (:embeddings cache-result))
          pending (->> pending-with-cache
                       (remove :cached-vector)
                       (mapv #(select-keys % [:doc :cache-key])))
          provider-target-ids (some->> provider-target-ids seq (into #{}))
          provider-pending (cond->> pending
                             provider-target-ids
                             (filter #(contains? provider-target-ids
                                                 (get-in % [:doc :target-id]))))
          provider-pending (cond->> provider-pending
                             (some? max-provider-docs)
                             (take (max 0 (long max-provider-docs))))
          provider-pending (vec provider-pending)
          provider-skipped (- (count pending) (count provider-pending))
          total-search-docs (search-doc-count xtdb scope)
          batches (vec (partition-all batch-size provider-pending))
          batch-count (count batches)]
      (reduce
       (fn [summary [batch-idx batch]]
         (let [docs (mapv :doc batch)
               vectors (embed-batch (mapv #(provider-input-text % input-max-chars) docs))]
           (when-not (= (count batch) (count vectors))
             (throw (ex-info "Embedding provider returned wrong vector count."
                             {:expected (count batch)
                              :actual (count vectors)})))
           (cache-store-many! embedding-cache
                              (mapv (fn [{:keys [cache-key]} vector]
                                      [cache-key vector])
                                    batch
                                    vectors))
           (let [rows (mapv (fn [doc vector]
                              (doc-embedding-row doc
                                                 provider
                                                 model
                                                 embedding-role
                                                 vector))
                            docs
                            vectors)
                 result (store/commit-embeddings! xtdb rows)]
             (vector-store/upsert-embeddings! rows)
             (let [summary (update summary :embedded + (:embeddings result))]
               (when on-progress
                 (on-progress (assoc summary
                                     :batch (inc batch-idx)
                                     :batches batch-count
                                     :batch-size (count batch)
                                     :batch-embedded (:embeddings result))))
               summary))))
       (cond-> {:provider provider
                :model model
                :search-docs total-search-docs
                :pending (count pending)
                :provider-pending (count provider-pending)
                :provider-skipped provider-skipped
                :embedded cache-hits
                :skipped (- total-search-docs (count pending) cache-hits)}
         embedding-cache
         (assoc :cache-hits cache-hits))
       (map-indexed vector batches)))
    (finally
      (when close
        (close)))))

(defn- add-vectors
  [a b]
  (mapv + a b))

(defn- zero-vector
  [dimensions]
  (vec (repeat dimensions 0.0)))

(defn fake-client
  "Return deterministic embedding client for tests."
  [{:keys [model dimensions dictionary]
    :or {model "fake-embedding"
         dimensions 4
         dictionary {}}}]
  (let [fallback (fn [token]
                   (let [idx (mod (clojure.core/hash token) dimensions)]
                     (assoc (zero-vector dimensions) idx 1.0)))
        token-vector (fn [token]
                       (or (get dictionary token)
                           (fallback token)))
        embed-one (fn [s]
                    (let [vectors (map token-vector (text/tokenize s))]
                      (if (seq vectors)
                        (reduce add-vectors (zero-vector dimensions) vectors)
                        (zero-vector dimensions))))]
    {:provider :fake
     :model model
     :embed-batch (fn [inputs] (mapv embed-one inputs))}))
