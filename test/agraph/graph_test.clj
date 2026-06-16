(ns agraph.graph-test
  (:require [agraph.graph :as graph]
            [agraph.index :as index]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest builds-graph-views
  (let [xtdb-path (temp-dir "agraph-graph-xtdb")
        out-dir (temp-dir "agraph-graph-html")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (let [overview (graph/overview-graph xtdb {:limit 20})
              deps (graph/deps-graph xtdb "sample.core" {:depth 1 :limit 20})
              query (graph/query-graph xtdb "greeting" {:retriever :lexical :limit 5})
              html-path (graph/write-html! (str out-dir "/graph.html") query)
              json-path (graph/write-canonical! (str out-dir "/graph.json") query)
              exported (json/read-json (slurp json-path) :key-fn keyword)]
          (is (seq (:nodes overview)))
          (is (seq (:edges deps)))
          (is (some #(= "Greeting Flow" (:label %)) (:nodes query)))
          (is (= graph/schema (:schema exported)))
          (is (= (:nodes query) (:nodes exported)))
          (is (= (:edges query) (:edges exported)))
          (is (.exists (io/file html-path)))
          (is (.exists (io/file json-path)))
          (is (re-find #"const graph =" (slurp html-path))))))))
