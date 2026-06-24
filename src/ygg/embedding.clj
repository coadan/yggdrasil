(ns ygg.embedding
  "Embedding indexing and vector helpers."
  (:require [ygg.hash :as hash]
            [ygg.text :as text]
            [ygg.vector-store :as vector-store]
            [ygg.xtdb :as store]))

(def default-batch-size 64)

(defn now-ms
  []
  (System/currentTimeMillis))

(defn embedding-id
  "Return stable embedding id for target/model/input."
  [target-id provider model input-sha]
  (str "embedding:" (hash/short-hash [target-id provider model input-sha])))

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
  [{:keys [target-id project-id repo-id provider model input-sha vector created-at-ms]}]
  {:xt/id (embedding-id target-id provider model input-sha)
   :project-id project-id
   :repo-id repo-id
   :target-id target-id
   :provider provider
   :model model
   :dims (count vector)
   :input-sha input-sha
   :vector (mapv double vector)
   :created-at-ms (long (or created-at-ms (now-ms)))
   :active? true})

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
  ([xtdb {:keys [project-id repo-id provider model]}]
   (store/constrained-rows xtdb
                           (:embeddings store/tables)
                           {:project-id project-id
                            :repo-id repo-id
                            :provider provider
                            :model model
                            :active? true})))

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

(defn- embedded-key
  [{:keys [target-id provider model input-sha]}]
  [target-id provider model input-sha])

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
  [xtdb {:keys [provider model limit project-id repo-id]}]
  (let [docs (vec (all-search-docs xtdb {:project-id project-id
                                         :repo-id repo-id}))
        done (into #{} (map embedded-key) (current-embeddings-for-docs
                                           xtdb
                                           docs
                                           {:project-id project-id
                                            :repo-id repo-id
                                            :provider provider
                                            :model model}))
        docs (remove #(contains? done [(:target-id %) provider model (:input-sha %)])
                     docs)]
    (if limit
      (take limit docs)
      docs)))

(defn embed-search-docs!
  "Embed pending search docs with client map and persist rows."
  [xtdb {:keys [provider model embed-batch close] :as client} {:keys [batch-size limit project-id repo-id]
                                                               :or {batch-size default-batch-size}}]
  (when-not (and provider model embed-batch)
    (throw (ex-info "Invalid embedding client." {:client (select-keys client [:provider :model])})))
  (try
    (let [scope {:project-id project-id :repo-id repo-id}
          pending (vec (pending-search-docs xtdb (assoc scope
                                                        :provider provider
                                                        :model model
                                                        :limit limit)))
          total-search-docs (search-doc-count xtdb scope)]
      (reduce
       (fn [summary batch]
         (let [vectors (embed-batch (mapv :text batch))]
           (when-not (= (count batch) (count vectors))
             (throw (ex-info "Embedding provider returned wrong vector count."
                             {:expected (count batch)
                              :actual (count vectors)})))
           (let [rows (mapv (fn [doc vector]
                              (embedding-row {:target-id (:target-id doc)
                                              :project-id (:project-id doc)
                                              :repo-id (:repo-id doc)
                                              :provider provider
                                              :model model
                                              :input-sha (:input-sha doc)
                                              :vector vector}))
                            batch
                            vectors)
                 result (store/commit-embeddings! xtdb rows)]
             (vector-store/upsert-embeddings! rows)
             (update summary :embedded + (:embeddings result)))))
       {:provider provider
        :model model
        :search-docs total-search-docs
        :pending (count pending)
        :embedded 0
        :skipped (- total-search-docs (count pending))}
       (partition-all batch-size pending)))
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
