(ns agraph.context-test
  (:require [agraph.context :as context]
            [agraph.activity :as activity]
            [agraph.graph :as graph]
            [agraph.query :as query]
            [clojure.test :refer [deftest is]]))

(deftest inferred-docs-include-source-chunk-for-retrieved-node-result
  (let [inferred-docs @#'context/inferred-docs
        docs (inferred-docs
              ["target"]
              [{:target-id "node:target"
                :target-kind :node
                :path "src/Target.java"
                :label "demo/Target"
                :score 1.0
                :score-components {:exact 0.0}}]
              [{:xt/id "chunk:target"
                :path "src/Target.java"
                :kind :code-definition
                :label "demo/Target"
                :text "class Target {}"
                :tokens ["demo/target" "target"]
                :source-line 1}]
              []
              900)]
    (is (= ["src/Target.java"] (mapv #(get-in % [:source :path]) docs)))
    (is (true? (:retrievedSource (first docs))))))

(deftest diversify-docs-promotes-distinct-source-files
  (let [diversify-docs @#'context/diversify-docs
        docs (diversify-docs
              [{:target "chunk:a1"
                :score 1.0
                :retrievedSource true
                :source {:repo "app"
                         :path "src/A.java"
                         :definitionKind :class}}
               {:target "chunk:a2"
                :score 0.9
                :retrievedSource true
                :source {:repo "app"
                         :path "src/A.java"
                         :definitionKind :method}}
               {:target "chunk:b1"
                :score 0.5
                :retrievedSource true
                :source {:repo "app"
                         :path "src/B.java"
                         :definitionKind :class}}])]
    (is (= ["src/A.java" "src/B.java" "src/A.java"]
           (mapv #(get-in % [:source :path]) docs)))))

(deftest context-packet-includes-search-instrumentation
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "src/auth.clj"
                                                  :score 1.2
                                                  :target-kind :chunk
                                                  :target-id "chunk:auth"
                                                  :label "auth/start"
                                                  :source-line 12}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _]
                                   (throw (ex-info "unexpected broad chunk scan" {})))
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                activity/select-activity (fn [& _] [])
                context/answerability (fn [& _] {:status :ready})]
    (let [packet (context/context-packet :xtdb
                                         "auth"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= "query:test" (get-in packet [:search :query-run-id])))
      (is (= :lexical (get-in packet [:search :retriever-effective])))
      (is (= 1 (get-in packet [:search :instrumentation :search-docs])))
      (is (= 0 (get-in packet [:search :instrumentation :context-chunks])))
      (is (= [{:path "src/auth.clj"
               :rank 1
               :score 1.2
               :targetKind "chunk"
               :label "auth/start"
               :sourceLine 12}]
             (:candidateFiles packet))))))
