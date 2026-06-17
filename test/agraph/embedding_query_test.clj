(ns agraph.embedding-query-test
  (:require [agraph.embedding :as embedding]
            [agraph.index :as index]
            [agraph.query :as query]
            [agraph.xtdb :as store]
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

(deftest embeds-search-docs-incrementally
  (let [xtdb-path (temp-dir "agraph-embed-xtdb")
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
  (let [xtdb-path (temp-dir "agraph-hybrid-xtdb")
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
          (is (contains? labels "sample.util"))
          (is (contains? labels "Greeting Flow"))
          (is (every? :score-components results)))))))
