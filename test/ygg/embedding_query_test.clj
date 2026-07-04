(ns ygg.embedding-query-test
  (:require [ygg.embedding :as embedding]
            [ygg.embedding.local :as local]
            [ygg.embedding.openrouter :as openrouter]
            [ygg.env :as env]
            [ygg.index :as index]
            [ygg.query :as query]
            [ygg.vector-store :as vector-store]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- missing-extension-path
  []
  (.getPath (io/file (temp-dir "ygg-missing-sqlite-vec") "missing-vec0")))

(def fake-client
  (embedding/fake-client
   {:dimensions 3
    :dictionary {"uppercase" [1.0 0.0 0.0]
                 "touppercase" [1.0 0.0 0.0]
                 "shout" [1.0 0.0 0.0]
                 "greeting" [0.0 1.0 0.0]
                 "greet" [0.0 1.0 0.0]
                 "flow" [0.0 1.0 0.0]}}))

(deftest vector-scoring
  (is (= 1.0 (embedding/cosine [1 0] [1 0])))
  (is (= 0.0 (embedding/cosine [1 0] [0 1])))
  (is (= 1.0 (embedding/cosine01 [1 0] [1 0]))))

(deftest vector-store-xtdb-scan-scores-current-doc-embeddings
  (let [docs [{:target-id "target:one"
               :input-sha "sha:one"}
              {:target-id "target:two"
               :input-sha "sha:two"}]
        calls (atom [])]
    (with-redefs [store/rows-with-field-tuples
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:target-id "target:one"
                      :project-id "project-a"
                      :repo-id "app"
                      :provider :fake
                      :model "fake-model"
                      :input-sha "sha:one"
                      :vector [1.0 0.0]}
                     {:target-id "target:two"
                      :project-id "project-a"
                      :repo-id "app"
                      :provider :fake
                      :model "fake-model"
                      :input-sha "stale"
                      :vector [0.0 1.0]}])]
      (let [result (vector-store/semantic-score-data
                    :xtdb
                    docs
                    {:mode :xtdb-scan
                     :provider :fake
                     :model "fake-model"
                     :project-id "project-a"
                     :repo-id "app"
                     :embed-query (fn [] [1.0 0.0])})]
        (is (= {"target:one" 1.0} (:scores result)))
        (is (= :xtdb-scan (get-in result [:instrumentation :vector-store])))
        (is (= :none (get-in result [:instrumentation
                                     :vector-store-fallback-reason])))
        (is (= 1 (count @calls)))
        (is (= {:table (:embeddings store/tables)
                :tuple-fields [:target-id :input-sha]
                :tuples [{:target-id "target:one" :input-sha "sha:one"}
                         {:target-id "target:two" :input-sha "sha:two"}]
                :constraints {:project-id "project-a"
                              :repo-id "app"
                              :provider :fake
                              :model "fake-model"
                              :active? true}}
               (select-keys (first @calls)
                            [:table :tuple-fields :tuples :constraints])))))))

(deftest vector-store-auto-falls-back-when-sqlite-vec-is-unavailable
  (let [embed-called? (atom false)]
    (with-redefs [store/rows-with-field-tuples (fn [& _] [])]
      (let [result (vector-store/semantic-score-data
                    :xtdb
                    [{:target-id "target:one" :input-sha "sha:one"}]
                    {:mode :auto
                     :provider :fake
                     :model "fake-model"
                     :sqlite-vec-extension (missing-extension-path)
                     :embed-query (fn []
                                    (reset! embed-called? true)
                                    [1.0 0.0])})]
        (is (= {} (:scores result)))
        (is (true? (:empty? result)))
        (is (false? @embed-called?))
        (is (= :xtdb-scan (get-in result [:instrumentation :vector-store])))
        (is (= :sqlite-vec-unavailable
               (get-in result [:instrumentation
                               :vector-store-fallback-reason])))))))

(deftest vector-store-aggregates-multiple-embedding-roles-per-target
  (let [docs [{:target-id "target:one"
               :input-sha "sha:one"}]]
    (with-redefs [store/rows-with-field-tuples
                  (fn [_ _]
                    [{:target-id "target:one"
                      :project-id "project-a"
                      :repo-id "app"
                      :provider :fake
                      :model "fake-model"
                      :embedding-role :content
                      :input-sha "sha:one"
                      :vector [1.0 0.0]}
                     {:target-id "target:one"
                      :project-id "project-a"
                      :repo-id "app"
                      :provider :fake
                      :model "fake-model"
                      :embedding-role :symbol
                      :input-sha "sha:one"
                      :vector [0.0 1.0]}])]
      (let [result (vector-store/semantic-score-data
                    :xtdb
                    docs
                    {:mode :xtdb-scan
                     :provider :fake
                     :model "fake-model"
                     :project-id "project-a"
                     :repo-id "app"
                     :embedding-roles [:content :symbol]
                     :embed-query (fn [] [0.0 1.0])})]
        (is (= {"target:one" 1.0} (:scores result)))
        (is (= {:content 0.5
                :symbol 1.0}
               (get-in result [:role-scores "target:one"])))))))

(deftest vector-store-upsert-is-noop-in-auto-without-sqlite-vec
  (with-redefs [env/get-env (fn [& _] nil)]
    (is (= {:vector-store :xtdb-scan
            :upserted 0
            :status :skipped
            :reason :sqlite-vec-unavailable}
           (vector-store/upsert-embeddings!
            [{:provider :fake
              :model "fake-model"
              :target-id "target:one"
              :input-sha "sha:one"
              :vector [1.0 0.0]}]
            {:extension-path (missing-extension-path)})))))

(deftest configured-extension-path-discovers-central-sqlite-vec
  (let [storage-root (temp-dir "ygg-sqlite-vec-storage")]
    (with-redefs [env/get-env (fn [& _] nil)
                  store/storage-root (constantly storage-root)]
      (let [extension-path (io/file (vector-store/default-extension-path))]
        (.mkdirs (.getParentFile extension-path))
        (spit extension-path "")
        (is (= (.getPath extension-path)
               (vector-store/configured-extension-path)))))))

(deftest sqlite-fts-scores-search-docs-without-sqlite-vec-extension
  (let [index-path (.getPath (io/file (temp-dir "ygg-fts-index") "project.sqlite"))
        docs [{:project-id "project-a"
               :repo-id "app"
               :target-id "node:auth"
               :target-kind :node
               :kind :clojure
               :input-sha "sha:auth"
               :path "src/auth.clj"
               :label "demo/auth"
               :text "auth login token"}
              {:project-id "project-a"
               :repo-id "app"
               :target-id "node:billing"
               :target-kind :node
               :kind :clojure
               :input-sha "sha:billing"
               :path "src/billing.clj"
               :label "demo/billing"
               :text "invoice payment"}]
        result (vector-store/fts-score-data
                docs
                ["auth" "login"]
                {:sqlite-fts? true
                 :vector-index-path index-path
                 :project-id "project-a"
                 :repo-id "app"})
        cached-result (vector-store/fts-score-data
                       docs
                       ["auth" "login"]
                       {:sqlite-fts? true
                        :vector-index-path index-path
                        :project-id "project-a"
                        :repo-id "app"})]
    (is (= #{"node:auth"} (set (keys (:scores result)))))
    (is (= :sqlite-fts (get-in result [:instrumentation :fts-store])))
    (is (= :ok (get-in result [:instrumentation :fts-status])))
    (is (= 2 (get-in result [:instrumentation :fts-indexed-search-docs])))
    (is (= 2 (get-in result [:instrumentation :fts-upserted-search-docs])))
    (is (= 0 (get-in result [:instrumentation :fts-skipped-search-docs])))
    (is (= #{"node:auth"} (set (keys (:scores cached-result)))))
    (is (= 0 (get-in cached-result [:instrumentation :fts-upserted-search-docs])))
    (is (= 2 (get-in cached-result [:instrumentation :fts-skipped-search-docs])))))

(deftest sqlite-fts-applies-mechanical-target-kind-filter
  (let [index-path (.getPath (io/file (temp-dir "ygg-fts-kind-index") "project.sqlite"))
        docs [{:project-id "project-a"
               :repo-id "app"
               :target-id "chunk:auth"
               :target-kind :chunk
               :kind :markdown
               :input-sha "sha:chunk"
               :path "docs/auth.md"
               :label "auth docs"
               :text "auth login token"}
              {:project-id "project-a"
               :repo-id "app"
               :target-id "node:auth"
               :target-kind :node
               :kind :clojure
               :input-sha "sha:node"
               :path "src/auth.clj"
               :label "demo/auth"
               :text "auth login token"}]
        result (vector-store/fts-score-data
                docs
                ["auth"]
                {:sqlite-fts? true
                 :vector-index-path index-path
                 :project-id "project-a"
                 :repo-id "app"
                 :target-kind :node})]
    (is (= #{"node:auth"} (set (keys (:scores result)))))
    (is (= 1 (get-in result [:instrumentation :fts-filtered-candidates])))))

(deftest sqlite-fts-reports-stale-sidecar-rows
  (let [index-path (.getPath (io/file (temp-dir "ygg-fts-stale-index") "project.sqlite"))
        old-doc {:project-id "project-a"
                 :repo-id "app"
                 :target-id "node:auth"
                 :target-kind :node
                 :kind :clojure
                 :input-sha "sha:old"
                 :path "src/auth.clj"
                 :label "demo/auth"
                 :text "auth login token"}
        current-doc (assoc old-doc :input-sha "sha:new")]
    (vector-store/upsert-search-docs!
     [old-doc]
     {:index-path index-path
      :project-id "project-a"})
    (let [result (vector-store/fts-score-data
                  [current-doc]
                  ["auth"]
                  {:sqlite-fts? true
                   :skip-fts-upsert? true
                   :vector-index-path index-path
                   :project-id "project-a"
                   :repo-id "app"})]
      (is (empty? (:scores result)))
      (is (= 1 (get-in result [:instrumentation :fts-stale-candidates]))))))

(deftest pending-search-docs-use-scoped-embedding-key-rows
  (let [calls (atom [])
        docs [{:xt/id "search-doc:done"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:done"
               :input-sha "sha:done"
               :active? true}
              {:xt/id "search-doc:pending"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:pending"
               :input-sha "sha:pending"
               :active? true}]
        pending (with-redefs [embedding/all-search-docs
                              (fn [_ scope]
                                (is (= {:project-id "project-a"
                                        :repo-id "app"}
                                       scope))
                                docs)
                              embedding/all-embeddings
                              (fn [& _]
                                (throw (ex-info "all embeddings should not be loaded"
                                                {})))
                              store/rows-with-field-tuples
                              (fn [& _]
                                (throw (ex-info "tuple embedding lookup should not be used"
                                                {})))
                              store/ordered-rows
                              (fn [_ request]
                                (swap! calls conj request)
                                [{:xt/id "embedding:done"
                                  :project-id "project-a"
                                  :repo-id "app"
                                  :target-id "target:done"
                                  :provider :fake
                                  :model "fake-model"
                                  :embedding-role :content
                                  :input-sha "sha:done"
                                  :active? true}])]
                  (vec (embedding/pending-search-docs
                        :xtdb
                        {:project-id "project-a"
                         :repo-id "app"
                         :provider :fake
                         :model "fake-model"})))]
    (is (= ["target:pending"] (mapv :target-id pending)))
    (is (= 1 (count @calls)))
    (is (= {:table (:embeddings store/tables)
            :constraints {:project-id "project-a"
                          :repo-id "app"
                          :provider :fake
                          :model "fake-model"
                          :embedding-role :content
                          :active? true}}
           (select-keys (first @calls)
                        [:table :constraints])))
    (is (= embedding/embedding-key-query-fields
           (:return-fields (first @calls))))))

(deftest embeds-search-docs-incrementally
  (let [xtdb-path (temp-dir "ygg-embed-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (let [first-summary (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})
              second-summary (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})]
          (is (pos? (:search-docs first-summary)))
          (is (= (:search-docs first-summary) (:embedded first-summary)))
          (is (zero? (:embedded second-summary)))
          (is (= (:search-docs second-summary) (:skipped second-summary))))))))

(deftest embed-search-docs-counts-total-with-count-query
  (let [search-doc-calls (atom 0)
        count-calls (atom [])
        committed (atom [])
        docs [{:xt/id "search-doc:pending"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:pending"
               :input-sha "sha:pending"
               :text "uppercase greeting"
               :active? true}]]
    (with-redefs [embedding/all-search-docs
                  (fn [_ scope]
                    (swap! search-doc-calls inc)
                    (is (= {:project-id "project-a"
                            :repo-id "app"}
                           scope))
                    docs)
                  store/rows-with-field-tuples
                  (fn [& _] [])
                  store/ordered-rows
                  (fn [& _] [])
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! count-calls conj {:table table
                                             :constraints constraints
                                             :ctx ctx})
                    3)
                  store/commit-embeddings!
                  (fn [_ rows]
                    (swap! committed conj rows)
                    {:embeddings (count rows)})]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     fake-client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 4})]
        (is (= 1 @search-doc-calls))
        (is (= [{:table (:search-docs store/tables)
                 :constraints {:project-id "project-a"
                               :repo-id "app"
                               :active? true}
                 :ctx {}}]
               @count-calls))
        (is (= 1 (count @committed)))
        (is (= {:provider :fake
                :model "fake-embedding"
                :search-docs 3
                :pending 1
                :provider-pending 1
                :provider-skipped 0
                :embedded 1
                :skipped 2}
               summary))))))

(deftest embed-search-docs-bounds-provider-work-to-targets
  (let [seen-inputs (atom [])
        committed (atom [])
        docs [{:xt/id "search-doc:a"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:a"
               :target-kind :chunk
               :path "src/a.clj"
               :kind :clojure
               :input-sha "sha:a"
               :text "alpha doc"
               :active? true}
              {:xt/id "search-doc:b"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:b"
               :target-kind :chunk
               :path "src/b.clj"
               :kind :clojure
               :input-sha "sha:b"
               :text "beta doc"
               :active? true}
              {:xt/id "search-doc:c"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:c"
               :target-kind :chunk
               :path "src/c.clj"
               :kind :clojure
               :input-sha "sha:c"
               :text "gamma doc"
               :active? true}]
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (swap! seen-inputs into inputs)
                               (mapv (constantly [0.25 0.75]) inputs))}]
    (with-redefs [embedding/all-search-docs (fn [& _] docs)
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count docs))
                  store/commit-embeddings!
                  (fn [_ rows]
                    (swap! committed conj rows)
                    {:embeddings (count rows)})
                  vector-store/upsert-embeddings! (fn [_] nil)]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 4
                      :provider-target-ids ["target:b"]
                      :max-provider-docs 1})]
        (is (= ["beta doc"] @seen-inputs))
        (is (= ["target:b"] (mapv :target-id (first @committed))))
        (is (= {:provider :fake
                :model "fake-model"
                :search-docs 3
                :pending 3
                :provider-pending 1
                :provider-skipped 2
                :embedded 1
                :skipped 0}
               summary))))))

(deftest embed-search-docs-reuses-cache-for-case-scoped-targets
  (let [embedding-cache (atom {})
        embed-calls (atom 0)
        docs-a [{:xt/id "search-doc:a"
                 :project-id "project-a"
                 :repo-id "app"
                 :target-id "target:project-a:app"
                 :target-kind :chunk
                 :path "src/app.clj"
                 :kind :clojure
                 :input-sha "sha:app"
                 :text "shared source doc"
                 :active? true}]
        docs-b [{:xt/id "search-doc:b"
                 :project-id "project-b"
                 :repo-id "app"
                 :target-id "target:project-b:app"
                 :target-kind :chunk
                 :path "src/app.clj"
                 :kind :clojure
                 :input-sha "sha:app"
                 :text "shared source doc"
                 :active? true}]
        docs (atom docs-a)
        committed (atom [])
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (swap! embed-calls inc)
                               (is (= ["shared source doc"] inputs))
                               [[0.25 0.75]])}]
    (with-redefs [embedding/all-search-docs (fn [& _] @docs)
                  store/rows-with-field-tuples (fn [& _] [])
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count @docs))
                  store/commit-embeddings!
                  (fn [_ rows]
                    (when (seq rows)
                      (swap! committed conj rows))
                    {:embeddings (count rows)})
                  vector-store/upsert-embeddings! (fn [_] nil)]
      (let [first-summary (embedding/embed-search-docs!
                           :xtdb
                           client
                           {:project-id "project-a"
                            :repo-id "app"
                            :embedding-cache embedding-cache})
            _ (reset! docs docs-b)
            second-summary (embedding/embed-search-docs!
                            :xtdb
                            client
                            {:project-id "project-b"
                             :repo-id "app"
                             :embedding-cache embedding-cache})]
        (is (= 1 @embed-calls))
        (is (= 1 (:embedded first-summary)))
        (is (zero? (:cache-hits first-summary)))
        (is (= 1 (:embedded second-summary)))
        (is (= 1 (:cache-hits second-summary)))
        (is (= ["target:project-a:app" "target:project-b:app"]
               (mapv (comp :target-id first) @committed)))
        (is (= ["project-a" "project-b"]
               (mapv (comp :project-id first) @committed)))
        (is (= [[0.25 0.75] [0.25 0.75]]
               (mapv (comp :vector first) @committed)))))))

(deftest embed-search-docs-reuses-sqlite-cache-across-cache-descriptors
  (let [cache-path (.getPath (io/file (temp-dir "ygg-embedding-cache") "cache.sqlite"))
        embed-calls (atom 0)
        docs-a [{:xt/id "search-doc:a"
                 :project-id "project-a"
                 :repo-id "app"
                 :target-id "target:project-a:app"
                 :target-kind :chunk
                 :path "src/app.clj"
                 :kind :clojure
                 :input-sha "sha:app"
                 :text "shared source doc"
                 :active? true}]
        docs-b [{:xt/id "search-doc:b"
                 :project-id "project-b"
                 :repo-id "app"
                 :target-id "target:project-b:app"
                 :target-kind :chunk
                 :path "src/app.clj"
                 :kind :clojure
                 :input-sha "sha:app"
                 :text "shared source doc"
                 :active? true}]
        docs (atom docs-a)
        committed (atom [])
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (swap! embed-calls inc)
                               (is (= ["shared source doc"] inputs))
                               [[0.25 0.75]])}]
    (with-redefs [embedding/all-search-docs (fn [& _] @docs)
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count @docs))
                  store/commit-embeddings!
                  (fn [_ rows]
                    (when (seq rows)
                      (swap! committed conj rows))
                    {:embeddings (count rows)})
                  vector-store/upsert-embeddings! (fn [_] nil)]
      (let [first-cache (embedding/sqlite-cache cache-path)
            first-summary (try
                            (embedding/embed-search-docs!
                             :xtdb
                             client
                             {:project-id "project-a"
                              :repo-id "app"
                              :embedding-cache first-cache})
                            (finally
                              (embedding/close-cache! first-cache)))
            _ (reset! docs docs-b)
            second-cache (embedding/sqlite-cache cache-path)
            second-summary (try
                             (embedding/embed-search-docs!
                              :xtdb
                              client
                              {:project-id "project-b"
                               :repo-id "app"
                               :embedding-cache second-cache})
                             (finally
                               (embedding/close-cache! second-cache)))]
        (is (= 1 @embed-calls))
        (is (= 1 (:embedded first-summary)))
        (is (zero? (:cache-hits first-summary)))
        (is (= 1 (:embedded second-summary)))
        (is (= 1 (:cache-hits second-summary)))
        (is (= ["target:project-a:app" "target:project-b:app"]
               (mapv (comp :target-id first) @committed)))
        (is (= ["project-a" "project-b"]
               (mapv (comp :project-id first) @committed)))))))

(deftest embed-search-docs-normalizes-blank-provider-inputs
  (let [seen-inputs (atom nil)
        committed (atom [])
        docs [{:xt/id "search-doc:blank"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:blank"
               :input-sha "sha:blank"
               :text ""
               :active? true}
              {:xt/id "search-doc:nil"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:nil"
               :input-sha "sha:nil"
               :active? true}
              {:xt/id "search-doc:text"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:text"
               :input-sha "sha:text"
               :text "alpha"
               :active? true}]
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (reset! seen-inputs inputs)
                               (vec (repeat (count inputs) [1.0 0.0])))}]
    (with-redefs [embedding/all-search-docs (fn [& _] docs)
                  store/rows-with-field-tuples (fn [& _] [])
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count docs))
                  store/commit-embeddings!
                  (fn [_ rows]
                    (reset! committed rows)
                    {:embeddings (count rows)})]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 8})]
        (is (= ["[blank search document]"
                "[blank search document]"
                "alpha"]
               @seen-inputs))
        (is (= 3 (:embedded summary)))
        (is (= ["target:blank" "target:nil" "target:text"]
               (mapv :target-id @committed)))))))

(deftest embed-search-docs-bounds-provider-inputs
  (let [seen-inputs (atom nil)
        docs [{:xt/id "search-doc:long"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:long"
               :input-sha "sha:long"
               :text "abcdefghijklmnopqrstuvwxyz"
               :active? true}]
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (reset! seen-inputs inputs)
                               [[1.0 0.0]])}]
    (with-redefs [embedding/all-search-docs (fn [& _] docs)
                  store/rows-with-field-tuples (fn [& _] [])
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count docs))
                  store/commit-embeddings! (fn [_ rows]
                                             {:embeddings (count rows)})
                  vector-store/upsert-embeddings! (fn [_] nil)]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 8
                      :input-max-chars 5})]
        (is (= ["abcde"] @seen-inputs))
        (is (= 1 (:embedded summary)))))))

(deftest embed-search-docs-reports-batch-progress
  (let [progress (atom [])
        docs [{:xt/id "search-doc:one"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:one"
               :input-sha "sha:one"
               :text "one"
               :active? true}
              {:xt/id "search-doc:two"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:two"
               :input-sha "sha:two"
               :text "two"
               :active? true}
              {:xt/id "search-doc:three"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:three"
               :input-sha "sha:three"
               :text "three"
               :active? true}]
        client {:provider :fake
                :model "fake-model"
                :embed-batch (fn [inputs]
                               (vec (repeat (count inputs) [1.0 0.0])))}]
    (with-redefs [embedding/all-search-docs (fn [& _] docs)
                  store/rows-with-field-tuples (fn [& _] [])
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] (count docs))
                  store/commit-embeddings! (fn [_ rows]
                                             {:embeddings (count rows)})
                  vector-store/upsert-embeddings! (fn [_] nil)]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 2
                      :on-progress #(swap! progress conj
                                           (select-keys %
                                                        [:search-docs
                                                         :pending
                                                         :provider-pending
                                                         :provider-skipped
                                                         :embedded
                                                         :skipped
                                                         :batch
                                                         :batches
                                                         :batch-size
                                                         :batch-embedded]))})]
        (is (= 3 (:embedded summary)))
        (is (= [{:search-docs 3
                 :pending 3
                 :provider-pending 3
                 :provider-skipped 0
                 :embedded 2
                 :skipped 0
                 :batch 1
                 :batches 2
                 :batch-size 2
                 :batch-embedded 2}
                {:search-docs 3
                 :pending 3
                 :provider-pending 3
                 :provider-skipped 0
                 :embedded 3
                 :skipped 0
                 :batch 2
                 :batches 2
                 :batch-size 1
                 :batch-embedded 1}]
               @progress))))))

(deftest openrouter-embedding-data-rejects-2xx-error-payload
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"OpenRouter embeddings request failed"
       (#'openrouter/embedding-data
        (json/write-json-str {"error" {"message" "HTTP 400: invalid input"
                                       "code" 400}})))))

(deftest embed-search-docs-closes-closeable-client
  (let [closed? (atom false)]
    (with-redefs [embedding/all-search-docs (fn [& _] [])
                  store/rows-with-field-tuples (fn [& _] [])
                  store/ordered-rows (fn [& _] [])
                  store/count-rows (fn [& _] 0)]
      (is (= {:provider :closeable
              :model "test-model"
              :search-docs 0
              :pending 0
              :provider-pending 0
              :provider-skipped 0
              :embedded 0
              :skipped 0}
             (embedding/embed-search-docs!
              :xtdb
              {:provider :closeable
               :model "test-model"
               :embed-batch (fn [_] [])
               :close (fn [] (reset! closed? true))}
              {})))
      (is @closed?))))

(deftest local-embedding-client-speaks-jsonl-worker-protocol
  (let [root (temp-dir "ygg-local-embedding-worker")
        worker (io/file root "worker.py")]
    (spit worker
          (str "import json, sys\n"
               "for line in sys.stdin:\n"
               "    req = json.loads(line)\n"
               "    vectors = []\n"
               "    for text in req['inputs']:\n"
               "        vectors.append([float(len(text)), 1.0])\n"
               "    print(json.dumps({'vectors': vectors}), flush=True)\n"))
    (let [client (local/client {:command (str "python3 " (.getPath worker))
                                :model "fake-local"})]
      (try
        (is (= :local (:provider client)))
        (is (= "fake-local" (:model client)))
        (is (= [[5.0 1.0] [4.0 1.0]]
               ((:embed-batch client) ["alpha" "beta"])))
        (finally
          ((:close client)))))))

(deftest local-default-command-uses-configured-python-and-bundled-worker
  (with-redefs [local/default-python (fn [] "/tmp/ygg venv/bin/python")
                local/default-worker-path (fn [] "/tmp/ygg home/scripts/local-embedding-worker.py")]
    (is (= "'/tmp/ygg venv/bin/python' '/tmp/ygg home/scripts/local-embedding-worker.py'"
           (local/default-command)))))

(deftest local-default-python-detects-conventional-project-venv
  (let [root (temp-dir "ygg-local-embedding-venv")
        python (io/file root "python")]
    (spit python "")
    (with-redefs [env/get-env (fn [_] nil)
                  local/default-venv-python-path (fn [] (.getPath python))]
      (is (= (.getPath python) (local/default-python))))))

(deftest local-embedding-setup-uses-bundled-requirements
  (let [root (temp-dir "ygg-local-embedding-setup")
        requirements (io/file root "requirements.txt")
        calls (atom [])]
    (spit requirements "sentence-transformers\n")
    (with-redefs [local/default-requirements-path (fn [] (.getPath requirements))
                  shell/sh (fn [& args]
                             (swap! calls conj (vec args))
                             {:exit 0 :out "" :err ""})]
      (is (= {:provider :local
              :venv "embedding-venv"
              :python (local/venv-python-path "embedding-venv")
              :requirements (.getPath requirements)
              :created? true
              :default? false}
             (local/setup-venv! {:venv-path "embedding-venv"
                                 :python "python3.12"})))
      (is (= [["python3.12" "-m" "venv" "embedding-venv"]
              [(local/venv-python-path "embedding-venv")
               "-m"
               "pip"
               "install"
               "-r"
               (.getPath requirements)]]
             @calls)))))

(deftest bundled-local-embedding-worker-emits-jsonl
  (let [module-dir (temp-dir "ygg-local-embedding-module")
        package-dir (io/file module-dir "sentence_transformers")]
    (.mkdirs package-dir)
    (spit (io/file package-dir "__init__.py")
          (str "class SentenceTransformer:\n"
               "    def __init__(self, name):\n"
               "        self.name = name\n"
               "    def encode(self, texts, batch_size=None, normalize_embeddings=True, show_progress_bar=False):\n"
               "        return [[float(len(text)), 1.0] for text in texts]\n"))
    (let [{:keys [exit out err]} (shell/sh "env"
                                           (str "PYTHONPATH=" module-dir)
                                           "python3"
                                           "scripts/local-embedding-worker.py"
                                           "fake-model"
                                           :in "{\"inputs\":[\"alpha\",\"beta\"]}\n")
          response (json/read-json out :key-fn keyword)]
      (is (zero? exit) err)
      (is (= [[5.0 1.0] [4.0 1.0]]
             (:vectors response))))))

(deftest hybrid-query-ranks-semantic-matches
  (let [xtdb-path (temp-dir "ygg-hybrid-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})
        (let [results (query/semantic-query xtdb
                                            "uppercase greeting"
                                            {:retriever :hybrid
                                             :embedding-client fake-client
                                             :limit 20})
              labels (set (map :label results))]
          (is (seq results))
          (is (contains? labels "sample.util/shout"))
          (is (contains? labels "Greeting Flow"))
          (is (every? :score-components results)))))))

(deftest auto-query-falls-back-before-local-worker-when-no-embeddings-exist
  (let [xtdb-path (temp-dir "ygg-auto-no-embeddings-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))
        embed-called? (atom false)
        client {:provider :local
                :model "fake-local"
                :embed-batch (fn [_]
                               (reset! embed-called? true)
                               (throw (ex-info "worker should not be called" {})))}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (let [report (query/search-report xtdb
                                          "uppercase greeting"
                                          {:retriever :auto
                                           :embedding-client client
                                           :limit 20})]
          (is (= :auto (:retriever-requested report)))
          (is (= :lexical (:retriever-effective report)))
          (is (seq (:results report)))
          (is (false? @embed-called?)))))))
