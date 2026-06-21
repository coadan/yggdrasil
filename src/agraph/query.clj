(ns agraph.query
  "Graph query helpers."
  (:require [agraph.embedding :as embedding]
            [agraph.embedding.openai :as openai]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def default-limit 10)
(def default-retriever :auto)
(def default-semantic-candidates 100)
(def default-lexical-candidates 100)
(def default-kind-candidates 50)
(def default-path-token-candidates 100)
(def default-seed-count 20)
(def lexical-graph-weight 0.25)
(def hybrid-graph-weight 0.20)

(def search-report-schema "agraph.search.report/v1")

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

(defn all-nodes
  ([xtdb] (all-nodes xtdb {}))
  ([xtdb opts]
   (filter-scope (store/all-rows xtdb (:nodes store/tables) (read-context opts)) opts)))

(defn all-edges
  ([xtdb] (all-edges xtdb {}))
  ([xtdb opts]
   (filter-scope (store/all-rows xtdb (:edges store/tables) (read-context opts)) opts)))

(defn all-chunks
  ([xtdb] (all-chunks xtdb {}))
  ([xtdb opts]
   (filter-scope (store/all-rows xtdb (:chunks store/tables) (read-context opts)) opts)))

(defn chunks-by-ids
  "Return chunk rows for concrete chunk ids within the requested scope."
  [xtdb ids opts]
  (let [ctx (read-context opts)]
    (->> ids
         distinct
         (keep #(store/row-by-id xtdb (:chunks store/tables) % ctx))
         (#(filter-scope % opts)))))

(defn chunks-by-paths
  "Return chunk rows for concrete file paths within the requested scope."
  [xtdb paths opts]
  (let [ctx (read-context opts)]
    (->> paths
         distinct
         (mapcat #(store/rows-by-field xtdb (:chunks store/tables) :path % ctx))
         (#(filter-scope % opts)))))

(defn all-diagnostics
  ([xtdb] (all-diagnostics xtdb {}))
  ([xtdb opts]
   (filter-scope (store/all-rows xtdb (:diagnostics store/tables) (read-context opts))
                 opts)))

(defn all-search-docs
  ([xtdb] (all-search-docs xtdb {}))
  ([xtdb opts]
   (filter-scope (filter :active? (store/all-rows xtdb
                                                  (:search-docs store/tables)
                                                  (read-context opts)))
                 opts)))

(defn all-embeddings
  ([xtdb] (all-embeddings xtdb {}))
  ([xtdb opts]
   (filter-scope (filter :active? (store/all-rows xtdb
                                                  (:embeddings store/tables)
                                                  (read-context opts)))
                 opts)))

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
   (filter-scope (filter :active? (store/all-rows xtdb
                                                  (:system-nodes store/tables)
                                                  (read-context opts)))
                 opts)))

(defn all-system-edges
  ([xtdb] (all-system-edges xtdb {}))
  ([xtdb opts]
   (filter-scope (filter :active? (store/all-rows xtdb
                                                  (:system-edges store/tables)
                                                  (read-context opts)))
                 opts)))

(defn all-system-evidence
  ([xtdb] (all-system-evidence xtdb {}))
  ([xtdb opts]
   (filter-scope (filter :active? (store/all-rows xtdb
                                                  (:system-evidence store/tables)
                                                  (read-context opts)))
                 opts)))

(defn find-node
  "Find node by exact id, label, namespace, name, or substring."
  ([xtdb value] (find-node xtdb value {}))
  ([xtdb value opts]
   (let [needle (str/lower-case (str value))
         nodes (all-nodes xtdb opts)]
     (or (some #(when (or (= value (:xt/id %))
                          (= value (:label %)))
                  %)
               nodes)
         (some #(when (and (= :namespace (:kind %))
                           (or (= value (:namespace %))
                               (= value (:name %))))
                  %)
               nodes)
         (some #(when (= value (:name %)) %) nodes)
         (some #(when (str/includes? (str/lower-case (:label %)) needle) %)
               nodes)))))

(defn find-system-node
  "Find system node by exact id, label, system key, or substring."
  ([xtdb value] (find-system-node xtdb value {}))
  ([xtdb value opts]
   (let [needle (str/lower-case (str value))
         nodes (all-system-nodes xtdb opts)]
     (or (some #(when (or (= value (:xt/id %))
                          (= value (:label %))
                          (= value (:system-key %)))
                  %)
               nodes)
         (some #(when (str/includes? (str/lower-case (:label %)) needle) %)
               nodes)
         (some #(when (str/includes? (str/lower-case (:system-key %)) needle) %)
               nodes)))))

(defn deps
  "Return incoming/outgoing dependencies for node."
  ([xtdb value] (deps xtdb value {}))
  ([xtdb value opts]
   (let [node (find-node xtdb value opts)
         id (:xt/id node)
         nodes-by-id (into {} (map (juxt :xt/id identity)) (all-nodes xtdb opts))
         edges (all-edges xtdb opts)
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

(defn- bm25-score
  [query-tokens docs doc-freqs avgdl doc]
  (let [k1 1.2
        b 0.75
        n (max 1 (count docs))
        freqs (token-frequencies (:tokens doc))
        dl (max 1 (count (:tokens doc)))]
    (reduce
     (fn [score token]
       (let [tf (double (get freqs token 0))]
         (if (zero? tf)
           score
           (let [df (double (get doc-freqs token 0))
                 idf (Math/log (+ 1.0 (/ (+ (- n df) 0.5)
                                         (+ df 0.5))))
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
  (let [doc-freqs (document-frequencies docs)
        avgdl (max 1.0
                   (/ (double (reduce + 0 (map #(count (:tokens %)) docs)))
                      (max 1 (count docs))))]
    (->> docs
         (map (fn [doc]
                [(:target-id doc)
                 (if (and (empty? (:tokens doc))
                          (number? (:score doc)))
                   (double (:score doc))
                   (bm25-score query-tokens docs doc-freqs avgdl doc))]))
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
  [query-tokens text]
  (let [query-token-set (set query-tokens)
        text-token-set (set (text/tokenize text))]
    (count (filter text-token-set query-token-set))))

(defn- path-token-candidate-ids
  [query-tokens docs n]
  (->> docs
       (keep (fn [doc]
               (let [matches (matching-query-token-count query-tokens (:path doc))]
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
  [query-text query-tokens doc]
  (let [query (str/lower-case (str/trim query-text))
        label (str/lower-case (:label doc))
        label-token-match? (pos? (matching-query-token-count query-tokens (:label doc)))
        path-token-boost (min 0.15
                              (* 0.05
                                 (double (matching-query-token-count query-tokens
                                                                     (:path doc)))))]
    (cond
      (exact-path-mentioned? query (:path doc)) 2.0

      (and (not (str/blank? query)) (= query label)) 0.25
      (and (not (str/blank? query)) (str/includes? label query)) 0.15
      label-token-match? (+ 0.05 path-token-boost)
      :else path-token-boost)))

(defn- ranked-candidates
  [{:keys [query-text query-tokens docs lexical semantic neighbor-scores retriever]}]
  (let [docs-by-target (into {} (map (juxt :target-id identity)) docs)
        semantic-candidates (concat (top-ids semantic default-semantic-candidates)
                                    (top-ids-by-kind semantic
                                                     docs
                                                     default-kind-candidates))
        lexical-candidates (concat (top-ids lexical default-lexical-candidates)
                                   (top-ids-by-kind lexical
                                                    docs
                                                    default-kind-candidates))
        exact-path-candidates (exact-path-candidate-ids query-text docs)
        path-token-candidates (path-token-candidate-ids query-tokens
                                                        docs
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
                               exact-score (exact-match-boost query-text query-tokens doc)
                               total (+ (case retriever
                                          :semantic semantic-score
                                          :lexical (+ lexical-score
                                                      (* lexical-graph-weight graph-score))
                                          (+ (* 0.70 semantic-score)
                                             (* 0.20 lexical-score)
                                             (* hybrid-graph-weight graph-score)))
                                        exact-score)]
                           (assoc doc
                                  :result-kind (:target-kind doc)
                                  :score total
                                  :score-components {:semantic semantic-score
                                                     :lexical lexical-score
                                                     :graph graph-score
                                                     :exact exact-score}
                                  :reason (cond
                                            (pos? semantic-score) "embedding match"
                                            (pos? lexical-score) "lexical match"
                                            (pos? graph-score) "graph neighbor"
                                            :else "candidate")))))
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
                                                                 #(vec (all-embeddings xtdb scope)))]
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
        [edges timings] (timed timings :load-edges-ms #(vec (all-edges xtdb scope)))
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
        instrumentation (assoc timings
                               :search-total-ms (elapsed-ms started)
                               :search-docs (count docs)
                               :search-docs-by-kind (rows-by-kind docs)
                               :query-tokens (count query-tokens)
                               :lexical-positive (positive-count lexical)
                               :semantic-positive (positive-count semantic)
                               :seed-count (count (:seed-ids seed-data))
                               :same-label-seed-count (count (:same-label-ids seed-data))
                               :neighbor-count (count neighbor-scores)
                               :exact-path-candidates (count (:exact-path-candidates ranked-data))
                               :path-token-candidates (count (:path-token-candidates ranked-data))
                               :candidate-count (count (:candidates ranked-data))
                               :candidates-by-kind (rows-by-kind candidate-docs)
                               :ranked-count (count ranked-all)
                               :returned-count (count ranked))
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

(defn- shortest-directed-path
  [source-id target-id nodes-by-id edges]
  (let [outgoing (group-by :source-id edges)]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [source-id])
           seen #{source-id}]
      (when-let [path (peek queue)]
        (let [current (peek path)]
          (if (= current target-id)
            (mapv #(get nodes-by-id %) path)
            (let [neighbors (->> (get outgoing current)
                                 (map :target-id)
                                 (remove seen))
                  next-paths (map #(conj path %) neighbors)]
              (recur (into (pop queue) next-paths)
                     (into seen neighbors)))))))))

(defn graph-path
  "Return shortest directed path between two node queries."
  ([xtdb source-value target-value] (graph-path xtdb source-value target-value {}))
  ([xtdb source-value target-value opts]
   (let [source (find-node xtdb source-value opts)
         target (find-node xtdb target-value opts)
         target-id (:xt/id target)
         nodes-by-id (into {} (map (juxt :xt/id identity)) (all-nodes xtdb opts))
         edges (all-edges xtdb opts)]
     (when (and source target)
       (shortest-directed-path (:xt/id source) target-id nodes-by-id edges)))))

(defn system-path
  "Return shortest directed path between two system queries."
  ([xtdb source-value target-value] (system-path xtdb source-value target-value {}))
  ([xtdb source-value target-value opts]
   (let [source (find-system-node xtdb source-value opts)
         target (find-system-node xtdb target-value opts)
         target-id (:xt/id target)
         nodes-by-id (into {} (map (juxt :xt/id identity)) (all-system-nodes xtdb opts))
         edges (all-system-edges xtdb opts)]
     (when (and source target)
       (shortest-directed-path (:xt/id source) target-id nodes-by-id edges)))))

(defn report
  "Return aggregate report data."
  ([xtdb] (report xtdb {}))
  ([xtdb opts]
   (let [nodes (all-nodes xtdb opts)
         edges (all-edges xtdb opts)
         chunks (all-chunks xtdb opts)
         search-docs (all-search-docs xtdb opts)
         embeddings (all-embeddings xtdb opts)
         diagnostics (all-diagnostics xtdb opts)
         degree (frequencies (mapcat (juxt :source-id :target-id) edges))
         nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
         top-nodes (->> degree
                        (sort-by (comp - val))
                        (take 10)
                        (mapv (fn [[id n]]
                                {:node (get nodes-by-id id {:xt/id id :label (display-id id)})
                                 :degree n})))]
     {:counts {:nodes (count nodes)
               :edges (count edges)
               :chunks (count chunks)
               :search-docs (count search-docs)
               :embeddings (count embeddings)
               :diagnostics (count diagnostics)}
      :top-nodes top-nodes
      :diagnostics diagnostics})))
