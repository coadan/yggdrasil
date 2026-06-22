(ns ygg.embedding-query-test
  (:require [ygg.embedding :as embedding]
            [ygg.index :as index]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

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

(deftest pending-search-docs-use-current-doc-embedding-tuples
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
                              (fn [_ request]
                                (swap! calls conj request)
                                [{:xt/id "embedding:done"
                                  :project-id "project-a"
                                  :repo-id "app"
                                  :target-id "target:done"
                                  :provider :fake
                                  :model "fake-model"
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
            :tuple-fields [:target-id :input-sha]
            :tuples [{:target-id "target:done" :input-sha "sha:done"}
                     {:target-id "target:pending" :input-sha "sha:pending"}]
            :constraints {:project-id "project-a"
                          :repo-id "app"
                          :provider :fake
                          :model "fake-model"
                          :active? true}}
           (select-keys (first @calls)
                        [:table :tuple-fields :tuples :constraints])))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

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
