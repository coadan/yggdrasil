(ns agraph.graph-test
  (:require [agraph.graph :as graph]
            [agraph.index :as index]
            [agraph.map :as graph-map]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest system-node-rows-keep-candidate-provenance
  (let [row (#'graph/node-row
             {}
             {}
             {:xt/id "system:fixture:__external:external-api/api.example.test"
              :label "api.example.test"
              :kind :external-api
              :repo-id "__external"
              :candidate-types [:runtime-url-host]
              :evidence [{:type :runtime-url-host
                          :host "api.example.test"}]})]
    (is (= {:candidateTypes ["runtime-url-host"]
            :candidateEvidence [{:type "runtime-url-host"
                                 :host "api.example.test"}]}
           (select-keys row [:candidateTypes :candidateEvidence])))))

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
              json-path (graph/write-canonical! (str out-dir "/graph.json") query)
              exported (json/read-json (slurp json-path) :key-fn keyword)]
          (is (seq (:nodes overview)))
          (is (seq (:edges deps)))
          (is (some #(= "Greeting Flow" (:label %)) (:nodes query)))
          (is (= graph/schema (:schema exported)))
          (is (= (:nodes query) (:nodes exported)))
          (is (= (:edges query) (:edges exported)))
          (is (.exists (io/file json-path))))))))

(deftest map-overlay-noise-edges-hide-rendered-edges
  (let [graph-data {:schema graph/schema
                    :nodes [{:id "system:fixture:app:path/api"
                             :label "api"
                             :kind "candidate-system"}
                            {:id "system:fixture:app:path/config"
                             :label "config"
                             :kind "candidate-system"}]
                    :edges [{:id "edge:api-config"
                             :source "system:fixture:app:path/api"
                             :target "system:fixture:app:path/config"
                             :relation "shares-config"
                             :visibility "supporting"}]}
        overlay {:schema graph-map/schema
                 :project "fixture"
                 :systems []
                 :reject []
                 :edges [{:source "system:fixture:app:path/api"
                          :target "system:fixture:app:path/config"
                          :relation "shares-config"
                          :visibility "noise"
                          :reason "Reviewed low-confidence fanout."}]
                 :docs []}
        updated (graph-map/apply-overlay graph-data overlay)]
    (is (empty? (:edges updated)))
    (is (= 1 (get-in updated [:map :edges])))))
