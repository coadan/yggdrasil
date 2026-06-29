(ns ygg.benchmark-prediction-test
  (:require [ygg.benchmark-prediction :as benchmark-prediction]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- index-of
  [xs value]
  (.indexOf (vec xs) value))

(deftest file-ranking-boosts-direct-file-in-evidence-dense-directory
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank evidence-score tokens]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :confidence 1.0
                   :matched-tokens tokens
                   :definition-kind "chunk"})
        direct-row (fn [path source-rank tokens]
                     {:path path
                      :source-rank source-rank
                      :evidence-score 0.36
                      :evidence-kind :candidate-file
                      :confidence 0.6
                      :candidate-source-rank source-rank
                      :direct-file-candidate? true
                      :matched-tokens tokens
                      :definition-kind "file"})
        files (rank-files [(doc-row "feature/vars.tf" 1 10.0 ["flow" "log"])
                           (doc-row "feature/resource.tf" 2 9.0 ["flow"])
                           (direct-row "feature/main.tf" 14 ["flow"])
                           (doc-row "other/main.tf" 3 3.0 ["flow"])])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path ["feature/main.tf"
                               :metrics
                               :directoryEvidenceBoost])))
    (is (< (:rank (get by-path "feature/main.tf"))
           (:rank (get by-path "other/main.tf"))))))

(deftest file-ranking-boosts-directory-root-candidate-near-doc-evidence
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank evidence-score tokens]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :confidence 1.0
                   :matched-tokens tokens
                   :definition-kind "chunk"})
        candidate-row (fn [path source-rank query-self?]
                        {:path path
                         :source-rank source-rank
                         :evidence-score 0.36
                         :evidence-kind :candidate-file
                         :confidence 0.6
                         :candidate-source-rank source-rank
                         :query-matched-path-self-identity? query-self?
                         :matched-tokens ["pkg" "contract"]
                         :definition-kind "node"})
        files (rank-files [(doc-row "pkg/detail.go" 1 8.0 ["pkg" "contract"])
                           (candidate-row "pkg/helper.go" 10 true)
                           (candidate-row "pkg/pkg.go" 12 true)
                           (candidate-row "other/other.go" 8 true)])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path ["pkg/pkg.go"
                               :metrics
                               :directoryEvidenceBoost])))
    (is (nil? (get-in by-path ["pkg/helper.go"
                               :metrics
                               :directoryEvidenceBoost])))
    (is (nil? (get-in by-path ["other/other.go"
                               :metrics
                               :directoryEvidenceBoost])))
    (is (< (:rank (get by-path "pkg/pkg.go"))
           (:rank (get by-path "pkg/helper.go"))))))

(deftest file-ranking-keeps-early-candidate-only-graph-head-visible
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank evidence-score tokens]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :confidence 1.0
                   :matched-tokens tokens
                   :definition-kind "chunk"})
        graph-row {:path "main.tf"
                   :source-rank 502
                   :evidence-score 0.55
                   :evidence-kind :candidate-file
                   :confidence 0.9
                   :candidate-source-rank 2
                   :graph-neighbor-score 1.0
                   :candidate-file-count 1
                   :matched-tokens ["vpc" "flow" "log" "data" "ownership" "destination"]
                   :matched-token-pairs [["flow" "log"]]
                   :definition-kind "node"}
        files (rank-files [(doc-row "modules/flow-log/main.tf"
                                    1
                                    1.5
                                    ["flow" "log" "destination" "role"])
                           (doc-row "examples/flow-log/main.tf"
                                    2
                                    1.3
                                    ["flow" "log" "destination"])
                           graph-row])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path ["main.tf"
                               :metrics
                               :candidateOnlySourceGraphHeadBoost])))
    (is (< (:rank (get by-path "main.tf"))
           (:rank (get by-path "examples/flow-log/main.tf"))))))

(deftest single-source-coverage-keeps-retrieved-contract-files-before-candidate-bypass
  (let [root (temp-dir "ygg-bench-otel-contract")
        paths ["connector/connector.go"
               "consumer/traces.go"
               "component/component.go"
               "service/internal/graph/connector.go"
               "connector/traces_router.go"
               "connector/connectortest/connector.go"
               "connector/connector_test.go"]]
    (doseq [path paths]
      (write-file! root path "package fixture\n"))
    (let [packet {:query (str "Trace connector interface changes through "
                              "consumer.Traces and component.Component\n\n"
                              "The connector package defines the Traces "
                              "interface that feeds consumer.Traces and extends "
                              "component.Component. Identify the connector "
                              "interface file, consumer.Traces interface file, "
                              "and component.Component interface file.")
                  :docs [{:source {:path "connector/connector.go"
                                   :heading "connector/connector"
                                   :definitionKind :interface}
                          :score 1.48
                          :snippet "connector Traces consumer component interface"
                          :retrievedSource true
                          :provenance "retrieved-doc"}
                         {:source {:path "consumer/traces.go"
                                   :heading "consumer/traces/Traces"
                                   :definitionKind :interface}
                          :score 1.81
                          :snippet "consumer Traces interface"
                          :retrievedSource true
                          :provenance "retrieved-doc"}
                         {:source {:path "service/internal/graph/connector.go"
                                   :heading "service/internal/graph/connector/buildTraces"
                                   :definitionKind :method}
                          :score 1.44
                          :snippet "connector graph consumer traces"
                          :retrievedSource true
                          :provenance "retrieved-doc"}
                         {:source {:path "connector/traces_router.go"
                                   :heading "connector/traces_router/TracesRouter"
                                   :definitionKind :interface}
                          :score 1.83
                          :snippet "connector traces router consumer"
                          :retrievedSource true
                          :provenance "retrieved-doc"}]
                  :entities []
                  :candidateFiles [{:rank 11
                                    :path "connector/connectortest/connector.go"
                                    :label "connector/connectortest/connector"
                                    :targetKind "node"
                                    :score 0.90
                                    :scoreComponents {:sourceGraph 0.6
                                                      :lexical 0.62
                                                      :grep 0.25
                                                      :graph 0.2}
                                    :supportLabels ["connector/connectortest/createTracesToMetrics"
                                                    "connector/connectortest/createMetricsToTraces"
                                                    "connector/connectortest/createProfilesToTraces"
                                                    "connector/connectortest/createTracesToLogs"]}
                                   {:rank 12
                                    :path "connector/connector_test.go"
                                    :label "connector/connector_test"
                                    :targetKind "node"
                                    :score 0.89
                                    :scoreComponents {:sourceGraph 0.6
                                                      :lexical 0.56
                                                      :grep 0.21
                                                      :graph 0.2}
                                    :supportLabels ["connector/connector_test/createTracesToMetrics"
                                                    "connector/connector_test/createMetricsToTraces"
                                                    "connector/connector_test/createTracesToTraces"
                                                    "connector/connector_test/createLogsToTraces"]}
                                   {:rank 13
                                    :path "component/component.go"
                                    :label "component/component"
                                    :targetKind "node"
                                    :score 0.6
                                    :scoreComponents {:sourceGraph 0.6}
                                    :supportLabels ["component/component/Component"]}]}
          result (benchmark-prediction/context-packet->agent-result
                  packet
                  {:root root
                   :coverage {:source-kinds [:go]}
                   :limit 20})
          result-paths (mapv :path (:suspectedFiles result))]
      (is (every? #(< (index-of result-paths %) 5)
                  ["connector/connector.go"
                   "consumer/traces.go"
                   "component/component.go"]))
      (is (< (index-of result-paths "component/component.go")
             (index-of result-paths "connector/connectortest/connector.go")))
      (is (< (index-of result-paths "consumer/traces.go")
             (index-of result-paths "connector/connector_test.go"))))))

(deftest compact-output-anchors-directory-evidence-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat
                [(row "alpha/type.go" 1 {:docCount 1
                                         :matchedTokenCount 5
                                         :rankScore 20.0})
                 (row "pkg/detail.go" 2 {:docCount 1
                                         :matchedTokenCount 4
                                         :rankScore 12.0})]
                (mapv (fn [rank]
                        (row (str "noise-" rank ".go")
                             rank
                             {:docCount 1
                              :matchedTokenCount 3
                              :rankScore (- 12 rank)}))
                      (range 3 11))
                [(row "pkg/pkg.go" 17 {:candidateFileCount 1
                                       :docCount 0
                                       :entityCount 0
                                       :directoryEvidenceBoost 6.0
                                       :rankScore 8.0})]))
        selected (compact-output files 10 nil)]
    (is (= ["alpha/type.go" "pkg/detail.go" "pkg/pkg.go"]
           (subvec (mapv :path selected) 0 3)))))

(deftest compact-output-reserves-candidate-path-self-identity
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "connector/connector.go"
                    1
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 10
                     :rankScore 20.0})
               (row "support/doc-supported.go"
                    2
                    {:docCount 1
                     :matchedTokenCount 9
                     :rankScore 12.0})
               (row "support/retrieved-label.go"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 8
                     :retrievedSupportLabelCount 2
                     :sourceGraphCandidateEvidenceScore 0.6
                     :rankScore 11.0})
               (row "support/query-doc.go"
                    4
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 7
                     :matchedTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.6
                     :rankScore 10.0})
               (row "component/component.go"
                    5
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 13
                     :candidatePathSelfIdentityBoost 12.0
                     :matchedTokenCount 2
                     :rankScore 13.0})
               (row "support/retrieved-path.go"
                    6
                    {:docCount 1
                     :retrievedSourceCount 1
                     :matchedPathQueryTokenCount 2
                     :firstSourceRank 6
                     :matchedTokenCount 6
                     :rankScore 9.0})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (some #{"component/component.go"} (take 5 paths)))))

(deftest compact-output-keeps-preserved-head-before-derived-reserves
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "main.tf" 1 {:rankScore 18.0})
              (row "variables.tf" 2 {:rankScore 17.0})
              (row "modules/flow-log/variables.tf" 3 {:rankScore 16.0})
              (row "vpc-flow-logs.tf" 4 {:rankScore 15.0})
              (row "docs/reserved.md"
                   5
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSupportLabelCount 2
                    :matchedTokenCount 4
                    :sourceGraphCandidateEvidenceScore 0.6
                    :rankScore 14.0})
              (row "tail.tf" 6 {:rankScore 1.0})]
        selected (compact-output rows 20 nil)]
    (is (= ["main.tf"
            "variables.tf"
            "modules/flow-log/variables.tf"
            "vpc-flow-logs.tf"]
           (->> selected
                (map :path)
                (take 4)
                vec)))))

(deftest diversity-preserves-saturated-doc-supported-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        row (fn [path rank rank-score]
              {:path path
               :rank rank
               :metrics {:docCount 1
                         :candidateFileCount 1
                         :matchedTokenCount 7
                         :sourceGraphCandidateEvidenceScore 0.8
                         :rankScore rank-score
                         :definitionKinds ["class"]}})
        rows [(row "module/src/A.java" 1 30.0)
              (row "module/src/B.java" 2 24.0)
              (row "module/src/C.java" 3 18.0)
              (row "other/src/D.java" 4 8.0)
              (row "another/src/E.java" 5 7.0)]
        diversified (diversify rows)]
    (is (= ["module/src/A.java"
            "module/src/B.java"
            "module/src/C.java"]
           (subvec (mapv :path diversified) 0 3)))))

(deftest compact-output-keeps-wide-ranked-surface
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat
                [(row "core-a.go"
                      1
                      {:docCount 1
                       :candidateFileCount 1
                       :retrievedSourceCount 2
                       :repeatedRetrievedSourceBoost 0.5
                       :rankScore 20.0})
                 (row "core-b.go"
                      2
                      {:docCount 1
                       :candidateFileCount 1
                       :fileIdentitySupportLabelCount 4
                       :matchedTokenCount 5
                       :rankScore 18.0})
                 (row "core-c.go"
                      3
                      {:docCount 1
                       :candidateFileCount 1
                       :fileIdentitySupportLabelCount 4
                       :matchedTokenCount 5
                       :rankScore 17.0})]
                (mapv (fn [rank]
                        (row (str "fill-" rank ".go")
                             rank
                             {:docCount 1
                              :candidateFileCount 1
                              :matchedTokenCount 2
                              :rankScore (- 17.0 rank)}))
                      (range 4 20))
                [(row "late-source.go"
                      20
                      {:candidateFileCount 1
                       :candidateSourceRank 16
                       :matchedTokenCount 2
                       :sourceGraphCandidateEvidenceScore 0.36
                       :rankScore 1.03})
                 (row "outside-limit.go"
                      21
                      {:candidateFileCount 1
                       :matchedTokenCount 1
                       :rankScore 0.5})]))
        narrow-paths (mapv :path (compact-output files 7 nil))
        wide-paths (mapv :path (compact-output files 20 nil))]
    (is (= ["core-a.go" "core-b.go" "core-c.go"]
           narrow-paths))
    (is (= 20 (count wide-paths)))
    (is (= "late-source.go" (last wide-paths)))))

(deftest compact-output-anchors-repeated-label-source-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/http.test.js"
                    1
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 9
                     :sourceGraphCandidateEvidenceScore 0.63
                     :rankScore 14.0})
               (row "tests/unit/adapters/fetch.test.js"
                    2
                    {:candidateFileCount 1
                     :matchedTokenCount 8
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.39
                     :rankScore 13.0})
               (row "tests/unit/axios.test.js"
                    3
                    {:candidateFileCount 1
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.59
                     :candidateGrepScore 0.1
                     :rankScore 12.0})
               (row "tests/unit/helpers/isAxiosError.test.js"
                    4
                    {:candidateFileCount 1
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.1
                     :rankScore 11.0})
               (row "tests/unit/core/AxiosError.test.js"
                    5
                    {:candidateFileCount 1
                     :matchedTokenCount 6
                     :sourceGraphCandidateEvidenceScore 0.60
                     :candidateGrepScore 0.07
                     :rankScore 8.0})
               (row "lib/adapters/http.js"
                    6
                    {:candidateFileCount 1
                     :candidateSourceRank 69
                     :matchedTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.53
                     :candidateGrepScore 0.32
                     :retrievedSupportLabelCount 3
                     :rankScore 10.0})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (some #{"lib/adapters/http.js"} (take 5 selected-paths)))))

(deftest compact-output-prefers-repeated-label-doc-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/http.test.js"
                    1
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSupportLabelCount 1
                     :matchedTokenCount 9
                     :sourceGraphCandidateEvidenceScore 0.63
                     :candidateGrepScore 0.90
                     :rankScore 14.0})
               (row "tests/unit/adapters/fetch.test.js"
                    2
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSupportLabelCount 1
                     :matchedTokenCount 8
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.39
                     :rankScore 13.0})
               (row "tests/unit/axios.test.js"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSupportLabelCount 1
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.59
                     :candidateGrepScore 0.1
                     :rankScore 12.0})
               (row "tests/unit/helpers/isAxiosError.test.js"
                    4
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSupportLabelCount 1
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.1
                     :rankScore 11.0})
               (row "tests/unit/core/AxiosError.test.js"
                    5
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSupportLabelCount 1
                     :matchedTokenCount 6
                     :sourceGraphCandidateEvidenceScore 0.60
                     :candidateGrepScore 0.07
                     :rankScore 8.0})
               (row "lib/adapters/http.js"
                    6
                    {:docCount 2
                     :candidateFileCount 1
                     :candidateSourceRank 69
                     :retrievedSourceCount 2
                     :retrievedSupportLabelCount 3
                     :matchedTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.53
                     :candidateGrepScore 0.32
                     :rankScore 10.0})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (some #{"lib/adapters/http.js"} (take 5 selected-paths)))))

(deftest compact-output-frontloads-query-evidence-doc-row
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/docker-compose.yml"
                    1
                    {:candidateFileCount 3
                     :docCount 0
                     :entityCount 0
                     :architectureSupportBoost 2.0
                     :matchedTokenCount 4
                     :matchedTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 4.89
                     :rankScore 11.94})
               (row "Dapper/SqlMapper.cs"
                    2
                    {:docCount 1
                     :candidateFileCount 22
                     :matchedTokenCount 7
                     :matchedTokenPairCount 1
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :retrievedSupportLabelCount 4
                     :sourceGraphCandidateEvidenceScore 0.66
                     :candidateGrepScore 1.0
                     :rankScore 23.1})
               (row "tests/Dapper.Tests/MiscTests.cs"
                    3
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedTokenCount 9
                     :matchedTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.63
                     :candidateGrepScore 0.86
                     :rankScore 17.9})
               (row "Dapper/SqlMapper.TypeHandler.cs"
                    4
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :directFileCandidateCount 1
                     :candidateSourceRank 2
                     :matchedTokenCount 5
                     :matchedTokenPairCount 1
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.56
                     :retrievedSupportLabelCount 2
                     :rankScore 17.6})
               (row "Dapper/SqlMapper.TypeDeserializerCache.cs"
                    7
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :directFileCandidateCount 1
                     :candidateSourceRank 19
                     :matchedTokenCount 3
                     :sourceGraphCandidateEvidenceScore 0.54
                     :retrievedSupportLabelCount 3
                     :rankScore 14.5})
               (row "tests/Dapper.Tests/TypeHandlerTests.cs"
                    9
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :retrievedSupportLabelCount 2
                     :sourceGraphCandidateEvidenceScore 0.59
                     :candidateGrepScore 0.73
                     :rankScore 11.3})]
        kind-by-path {"tests/docker-compose.yml" "compose"
                      "Dapper/SqlMapper.cs" "dotnet"
                      "tests/Dapper.Tests/MiscTests.cs" "dotnet"
                      "Dapper/SqlMapper.TypeHandler.cs" "dotnet"
                      "Dapper/SqlMapper.TypeDeserializerCache.cs" "dotnet"
                      "tests/Dapper.Tests/TypeHandlerTests.cs" "dotnet"}
        selected-paths (mapv :path (compact-output files
                                                   20
                                                   "edit-files"
                                                   ["compose" "dotnet"]
                                                   kind-by-path))]
    (is (some #{"tests/Dapper.Tests/TypeHandlerTests.cs"}
              (take 5 selected-paths)))
    (is (< (.indexOf selected-paths "tests/Dapper.Tests/TypeHandlerTests.cs")
           (.indexOf selected-paths
                     "Dapper/SqlMapper.TypeDeserializerCache.cs")))))
