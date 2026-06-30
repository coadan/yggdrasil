(ns ygg.embedding
  "Embedding indexing and vector helpers."
  (:require [ygg.hash :as hash]
            [ygg.text :as text]
            [ygg.vector-store :as vector-store]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def default-batch-size 64)

(def default-provider-input-max-chars
  6000)

(def default-embedding-role :content)

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

(defn- cache-lookup
  [cache key]
  (when cache
    (get @cache key)))

(defn- cache-store!
  [cache key vector]
  (when cache
    (swap! cache assoc key vector)))

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
          pending-with-cache (mapv (fn [doc]
                                     (let [key (embedding-cache-key doc
                                                                    provider
                                                                    model
                                                                    embedding-role
                                                                    input-max-chars)]
                                       {:doc doc
                                        :cache-key key
                                        :cached-vector (cache-lookup embedding-cache key)}))
                                   pending)
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
          total-search-docs (search-doc-count xtdb scope)
          batches (vec (partition-all batch-size pending))
          batch-count (count batches)]
      (reduce
       (fn [summary [batch-idx batch]]
         (let [docs (mapv :doc batch)
               vectors (embed-batch (mapv #(provider-input-text % input-max-chars) docs))]
           (when-not (= (count batch) (count vectors))
             (throw (ex-info "Embedding provider returned wrong vector count."
                             {:expected (count batch)
                              :actual (count vectors)})))
           (doseq [[{:keys [cache-key]} vector] (map vector batch vectors)]
             (cache-store! embedding-cache cache-key vector))
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
