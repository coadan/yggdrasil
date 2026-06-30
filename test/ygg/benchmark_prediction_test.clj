(ns ygg.benchmark-prediction-test
  (:require [ygg.benchmark-prediction :as benchmark-prediction]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest context-packet-agent-result-resolves-keywordized-multi-root-repos
  (let [root-a (temp-dir "ygg-bench-pred-root-a")
        root-b (temp-dir "ygg-bench-pred-root-b")
        _ (write-file! root-a "connector/connector.go" "package connector\n")
        _ (write-file! root-b
                       "connector/routingconnector/factory.go"
                       "package routingconnector\n")
        packet {:query "routing connector feature gate default error mode ignore"
                :candidateFiles [{:path "connector/routingconnector/factory.go"
                                  :repo "contrib"
                                  :rank 1
                                  :score 0.9
                                  :targetKind "node"
                                  :label "connector/routingconnector/factory"
                                  :scoreComponents {:sourceGraph 0.6
                                                    :lexical 0.9
                                                    :grep 0.7}
                                  :supportLabels ["connector/routingconnector/factory/createDefaultConfig"]}
                                 {:path "connector/connector.go"
                                  :repo "core"
                                  :rank 2
                                  :score 0.6
                                  :targetKind "node"
                                  :label "connector/connector"
                                  :scoreComponents {:lexical 0.2}}]}
        result (benchmark-prediction/context-packet->agent-result
                packet
                {:root root-a
                 :roots {:core root-a
                         :contrib root-b}
                 :coverage {:declaredSourceKinds ["go"]}
                 :limit 10})
        paths (mapv (juxt :repo-id :path) (:suspectedFiles result))]
    (is (some #{["contrib" "connector/routingconnector/factory.go"]} paths))
    (is (some #{["core" "connector/connector.go"]} paths))
    (is (zero? (get-in result [:selection :coverageFilteredCandidateFiles])))))

(deftest context-packet-agent-result-expands-local-importers-from-candidate-files
  (let [root (temp-dir "ygg-bench-pred-importer")
        _ (write-file! root
                       "src/components/theme/Panel.astro"
                       "---\nconst label = \"Theme\"\n---\n<section>{label}</section>\n")
        _ (write-file! root
                       "src/pages/index.astro"
                       "---\nimport Panel from '@components/theme/Panel.astro'\n---\n<Panel />\n")
        packet {:query "theme component route impact"
                :candidateFiles [{:path "src/components/theme/Panel.astro"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "node"
                                  :kind "namespace"
                                  :label "src.components.theme.Panel"
                                  :supportLabels ["src/components/theme/Panel.astro"]
                                  :scoreComponents {:sourceGraph 0.6
                                                    :lexical 1.0
                                                    :grep 0.5}}]}
        result (benchmark-prediction/context-packet->agent-result
                packet
                {:root root
                 :coverage {:declaredSourceKinds ["astro" "web-framework"]}
                 :limit 10})
        by-path (into {} (map (juxt :path identity)) (:suspectedFiles result))]
    (is (contains? by-path "src/pages/index.astro"))
    (is (some #(str/starts-with? % "candidate-file-local-importer:")
              (get-in by-path ["src/pages/index.astro" :evidence])))))

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

(deftest file-ranking-boosts-early-source-graph-candidate-head
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        candidate-row {:path "main.tf"
                       :source-rank 502
                       :evidence-score 0.55
                       :evidence-kind :candidate-file
                       :confidence 0.9
                       :candidate-source-rank 2
                       :source-graph-candidate-evidence-score 0.55
                       :candidate-file-count 1
                       :matched-tokens ["vpc" "flow" "log" "data" "ownership" "destination"]
                       :matched-token-pairs [["flow" "log"]]
                       :definition-kind "node"}
        files (rank-files [{:path "noise.tf"
                            :source-rank 1
                            :evidence-score 1.2
                            :evidence-kind :doc
                            :confidence 1.0
                            :matched-tokens ["flow" "log"]
                            :definition-kind "chunk"}
                           candidate-row])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path ["main.tf"
                               :metrics
                               :candidateOnlySourceGraphHeadBoost])))
    (is (< (:rank (get by-path "main.tf"))
           (:rank (get by-path "noise.tf"))))))

(deftest file-ranking-preserves-support-owner-evidence
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        files (rank-files [{:path "src/app.py"
                            :source-rank 508
                            :evidence-score 0.62
                            :evidence-kind :candidate-file
                            :confidence 1.0
                            :candidate-source-rank 8
                            :direct-file-candidate? true
                            :support-owner-evidence? true
                            :support-owner-primary-label-query-token-count 1
                            :support-owner-primary-label-specific-token-count 1
                            :matched-tokens ["autoescape" "selection" "case"]
                            :retrieved-support-label-count 6
                            :definition-kind "file"}])
        metrics (get-in (first files) [:metrics])]
    (is (= 1 (:supportOwnerEvidenceCount metrics)))
    (is (= 0.62 (:supportOwnerEvidenceScore metrics)))
    (is (= 1 (:supportOwnerPrimaryLabelMatchedTokenCount metrics)))
    (is (= 1 (:supportOwnerPrimaryLabelSpecificTokenCount metrics)))))

(deftest query-matched-exported-support-labels-use-qualified-segments
  (let [match-count @#'benchmark-prediction/query-matched-exported-support-label-count
        query-tokens ["consumer.traces" "consumer" "component.component" "component"]]
    (is (= 1 (match-count query-tokens ["component/component/Component"])))
    (is (= 1 (match-count query-tokens ["consumer/traces/Traces"])))
    (is (zero? (match-count
                query-tokens
                ["internal/fanoutconsumer/traces/tracesConsumer.ConsumeTraces"])))
    (is (zero? (match-count
                query-tokens
                ["receiver/receivertest/contract_checker_test/exampleReceiver.ReceiveTraces"])))))

(deftest file-ranking-boosts-exact-retrieved-query-file-identities
  (let [identity-matches @#'benchmark-prediction/query-file-identity-matches
        rank-files @#'benchmark-prediction/ranked-file-predictions
        query-tokens ["jupitertestengine"
                      "jupiterengineexecutioncontext"
                      "jupiterenginedescriptor"
                      "jupiter"
                      "engine"
                      "execution"
                      "context"
                      "descriptor"]
        target-paths ["junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/JupiterTestEngine.java"
                      "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/execution/JupiterEngineExecutionContext.java"
                      "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/descriptor/JupiterEngineDescriptor.java"]
        doc-row (fn [path source-rank identity-count]
                  {:path path
                   :source-rank source-rank
                   :evidence-score 2.0
                   :evidence-kind :doc
                   :retrieved-source? true
                   :confidence 0.8
                   :matched-tokens ["jupiter" "engine"]
                   :query-matched-file-identity-count identity-count
                   :query-matched-file-identity-max-length
                   (if (pos? identity-count) 18 0)
                   :definition-kind "class"})
        candidate-row (fn [idx score]
                        {:path (str "noise/Candidate" idx ".java")
                         :source-rank idx
                         :candidate-source-rank (+ 30 idx)
                         :evidence-score score
                         :evidence-kind :candidate-file
                         :confidence 0.8
                         :matched-tokens ["jupiter" "engine"]
                         :definition-kind "class"})
        test-context-path "jupiter-tests/src/test/java/org/junit/jupiter/engine/execution/JupiterEngineExecutionContextTests.java"
        files (rank-files (concat
                           (map-indexed (fn [idx path]
                                          (doc-row path (+ 30 idx) 1))
                                        target-paths)
                           [(doc-row test-context-path 33 0)]
                           (map-indexed (fn [idx score]
                                          (candidate-row (inc idx) score))
                                        [13.0 12.8 12.6 12.4 12.2])))
        by-path (into {} (map (juxt :path identity)) files)]
    (is (= #{"jupitertestengine"}
           (identity-matches
            query-tokens
            "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/JupiterTestEngine.java")))
    (is (empty?
         (identity-matches query-tokens test-context-path)))
    (is (every? #(pos? (get-in by-path [% :metrics :retrievedQueryFileIdentityBoost]))
                target-paths))
    (is (nil? (get-in by-path [test-context-path
                               :metrics
                               :retrievedQueryFileIdentityBoost])))
    (is (every? #(<= (:rank (get by-path %)) 5) target-paths))
    (is (< (:rank (get by-path
                       "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/execution/JupiterEngineExecutionContext.java"))
           (:rank (get by-path test-context-path))))))

(deftest file-ranking-boosts-source-graph-query-evidence
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        tokens ["fix" "prefer" "typehandlers" "enum" "settings" "mapping" "code"]
        pairs [["fix" "prefer"]
               ["typehandlers" "enum"]
               ["mapping" "settings"]]
        doc-row (fn [path source-rank evidence-score tokens token-pairs]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :retrieved-source? true
                   :confidence 0.8
                   :matched-tokens tokens
                   :matched-token-pairs token-pairs
                   :definition-kind "class"})
        candidate-row (fn [path source-rank candidate-rank tokens token-pairs identity-count source-score]
                        {:path path
                         :source-rank source-rank
                         :candidate-source-rank candidate-rank
                         :evidence-score source-score
                         :evidence-kind :candidate-file
                         :confidence 0.8
                         :matched-tokens tokens
                         :matched-token-pairs token-pairs
                         :file-identity-support-label-count identity-count
                         :definition-kind "file"})
        settings-path "Dapper/SqlMapper.Settings.cs"
        mapper-path "Dapper/SqlMapper.cs"
        noise-path "tests/Dapper.Tests/ParameterTests.cs"
        files (rank-files [(doc-row noise-path
                                    1
                                    9.0
                                    tokens
                                    pairs)
                           (candidate-row noise-path
                                          101
                                          7
                                          tokens
                                          pairs
                                          1
                                          0.60)
                           (candidate-row settings-path
                                          102
                                          11
                                          tokens
                                          pairs
                                          2
                                          0.51)
                           (doc-row mapper-path
                                    3
                                    1.2
                                    tokens
                                    pairs)
                           (candidate-row mapper-path
                                          103
                                          9
                                          tokens
                                          pairs
                                          3
                                          0.59)
                           (candidate-row "benchmarks/Dapper.Tests.Performance/Benchmarks.EntityFrameworkCore.cs"
                                          104
                                          2
                                          ["dapper" "tests" "core" "enum"]
                                          [["dapper" "tests"]]
                                          3
                                          0.58)])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (nil? (get-in by-path [noise-path
                               :metrics
                               :sourceGraphQueryEvidenceBoost])))
    (is (pos? (get-in by-path [settings-path
                               :metrics
                               :sourceGraphQueryEvidenceBoost])))
    (is (pos? (get-in by-path [mapper-path
                               :metrics
                               :sourceGraphQueryEvidenceBoost])))
    (is (every? #(<= (:rank (get by-path %)) 5)
                [settings-path mapper-path]))))

(deftest file-ranking-boosts-doc-backed-directory-cluster-evidence
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank tokens]
                  {:path path
                   :repo-id "contrib"
                   :source-rank source-rank
                   :evidence-score 0.75
                   :evidence-kind :doc
                   :retrieved-source? true
                   :confidence 0.8
                   :matched-tokens tokens
                   :matched-path-query-token-count 2
                   :definition-kind "chunk"})
        candidate-row (fn [path source-rank candidate-rank source-score tokens]
                        {:path path
                         :repo-id "contrib"
                         :source-rank source-rank
                         :candidate-source-rank candidate-rank
                         :evidence-score source-score
                         :evidence-kind :candidate-file
                         :confidence 0.7
                         :matched-tokens tokens
                         :matched-path-query-token-count 2
                         :candidate-grep-score 0.2
                         :candidate-lexical-score 0.4
                         :definition-kind "chunk"})
        core-path "connector/connector.go"
        schema-path "connector/routingconnector/config.schema.yaml"
        files (rank-files
               [{:path core-path
                 :repo-id "core"
                 :source-rank 1
                 :evidence-score 0.9
                 :evidence-kind :doc
                 :retrieved-source? true
                 :confidence 0.9
                 :matched-tokens ["connector" "default"]
                 :query-matched-file-identity-count 1
                 :definition-kind "function"}
                (doc-row "connector/routingconnector/metadata.yaml"
                         2
                         ["routing" "connector" "metadata"])
                (candidate-row "connector/routingconnector/metadata.yaml"
                               102
                               4
                               0.55
                               ["routing" "connector" "metadata"])
                (doc-row "connector/routingconnector/factory.go"
                         3
                         ["routing" "connector" "factory"])
                (candidate-row "connector/routingconnector/factory.go"
                               103
                               5
                               0.55
                               ["routing" "connector" "factory"])
                (doc-row "connector/routingconnector/config.go"
                         4
                         ["routing" "connector" "config"])
                (candidate-row "connector/routingconnector/config.go"
                               104
                               6
                               0.55
                               ["routing" "connector" "config"])
                (doc-row schema-path
                         9
                         ["routing" "connector" "schema"])
                (candidate-row schema-path
                               109
                               8
                               0.48
                               ["routing" "connector" "schema"])])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path [schema-path :metrics :directoryEvidenceBoost])))
    (is (< (:rank (get by-path schema-path))
           (:rank (get by-path core-path))))))

(deftest retrieved-path-query-token-boost-grows-with-token-alignment
  (let [boost @#'benchmark-prediction/retrieved-path-query-token-boost]
    (is (= 6.0 (boost 1 1 2)))
    (is (< (boost 1 1 2)
           (boost 1 1 3)))
    (is (= 8.5 (boost 1 1 6)))))

(deftest source-graph-query-evidence-boost-accepts-identity-path-alignment
  (let [boost @#'benchmark-prediction/source-graph-query-evidence-boost]
    (is (zero? (boost 19 20 0.51 3 0 3 0 2)))
    (is (pos? (boost 20 20 0.51 3 0 3 0 2)))
    (is (pos? (boost 1 20 0.51 1 1 6 0 2)))
    (is (zero? (boost 1 20 0.51 0 1 6 0 2)))
    (is (zero? (boost 1 20 0.51 1 0 6 0 2)))
    (is (zero? (boost 1 21 0.51 1 1 6 0 2)))))

(deftest doc-supported-source-graph-query-boost-requires-doc-evidence
  (let [boost @#'benchmark-prediction/doc-supported-source-graph-query-boost]
    (is (zero? (boost 0 9.0)))
    (is (zero? (boost 1 0.0)))
    (is (pos? (boost 1 9.0)))))

(deftest retrieved-path-grep-evidence-boost-requires-strong-mechanical-match
  (let [boost @#'benchmark-prediction/retrieved-path-grep-evidence-boost]
    (is (zero? (boost 1 1 1 2 0.9 0.6)))
    (is (zero? (boost 1 1 1 3 0.6 0.6)))
    (is (zero? (boost 1 1 1 3 0.9 0.4)))
    (is (pos? (boost 1 1 1 3 0.9 0.6)))))

(deftest file-ranking-does-not-cross-repo-boost-single-repo-doc-cluster
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank tokens]
                  {:path path
                   :repo-id "dapper"
                   :source-rank source-rank
                   :evidence-score 0.75
                   :evidence-kind :doc
                   :retrieved-source? true
                   :confidence 0.8
                   :matched-tokens tokens
                   :matched-path-query-token-count 2
                   :definition-kind "chunk"})
        candidate-row (fn [path source-rank candidate-rank tokens]
                        {:path path
                         :repo-id "dapper"
                         :source-rank source-rank
                         :candidate-source-rank candidate-rank
                         :evidence-score 0.55
                         :evidence-kind :candidate-file
                         :confidence 0.7
                         :matched-tokens tokens
                         :matched-path-query-token-count 2
                         :definition-kind "chunk"})
        target-path "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
        files (rank-files
               [(doc-row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                         1
                         ["dapper" "tests"])
                (candidate-row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                               101
                               8
                               ["dapper" "tests"])
                (doc-row "benchmarks/Dapper.Tests.Performance/SqlDataReaderHelper.cs"
                         2
                         ["dapper" "tests"])
                (candidate-row "benchmarks/Dapper.Tests.Performance/SqlDataReaderHelper.cs"
                               102
                               9
                               ["dapper" "tests"])
                (doc-row "benchmarks/Dapper.Tests.Performance/Benchmarks.ServiceStack.cs"
                         3
                         ["dapper" "tests"])
                (candidate-row "benchmarks/Dapper.Tests.Performance/Benchmarks.ServiceStack.cs"
                               103
                               10
                               ["dapper" "tests"])])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (nil? (get-in by-path [target-path
                               :metrics
                               :directoryEvidenceBoost])))))

(deftest file-ranking-boosts-query-matched-exported-support-label
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank evidence-score tokens]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :confidence 1.0
                   :matched-tokens tokens
                   :definition-kind "chunk"})
        candidate-row (fn [path candidate-source-rank support-terminal query-match-count]
                        {:path path
                         :source-rank (+ 500 candidate-source-rank)
                         :evidence-score 0.36
                         :evidence-kind :candidate-file
                         :confidence 0.6
                         :candidate-source-rank candidate-source-rank
                         :file-identity-support-label-count 1
                         :exported-support-label-count 1
                         :query-matched-exported-support-label-count query-match-count
                         :candidate-support-label-signature [(str path "/" support-terminal)]
                         :matched-tokens [(first (str/split path #"/"))
                                          support-terminal]
                         :definition-kind "node"})
        files (rank-files [(doc-row "connector/connector.go"
                                    1
                                    7.0
                                    ["connector" "traces"])
                           (doc-row "connector/traces_router.go"
                                    2
                                    6.0
                                    ["connector" "traces"])
                           (doc-row "service/internal/graph/connector.go"
                                    3
                                    5.0
                                    ["connector" "traces"])
                           (doc-row "consumer/traces.go"
                                    4
                                    4.0
                                    ["consumer" "traces"])
                           (candidate-row "component/component.go"
                                          19
                                          "Component"
                                          1)
                           (candidate-row "consumer/consumer.go"
                                          20
                                          "Option"
                                          0)])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path ["component/component.go"
                               :metrics
                               :candidateOnlyExportedSupportBoost])))
    (is (nil? (get-in by-path ["consumer/consumer.go"
                               :metrics
                               :candidateOnlyExportedSupportBoost])))
    (is (<= (:rank (get by-path "component/component.go")) 5))
    (is (< (:rank (get by-path "component/component.go"))
           (:rank (get by-path "consumer/consumer.go"))))))

(deftest file-ranking-boosts-doc-backed-query-matched-exported-support-label
  (let [rank-files @#'benchmark-prediction/ranked-file-predictions
        doc-row (fn [path source-rank evidence-score tokens]
                  {:path path
                   :source-rank source-rank
                   :evidence-score evidence-score
                   :evidence-kind :doc
                   :retrieved-source? true
                   :confidence 0.9
                   :matched-tokens tokens
                   :matched-path-query-token-count 2
                   :definition-kind "chunk"})
        candidate-row (fn [path source-rank source-score support-terminal query-match-count tokens]
                        {:path path
                         :source-rank source-rank
                         :evidence-score source-score
                         :evidence-kind :candidate-file
                         :confidence 0.7
                         :candidate-source-rank (- source-rank 500)
                         :file-identity-support-label-count 1
                         :exported-support-label-count 1
                         :query-matched-exported-support-label-count query-match-count
                         :candidate-support-label-signature [(str path "/" support-terminal)]
                         :matched-tokens tokens
                         :matched-path-query-token-count 2
                         :definition-kind "node"})
        target-path "consumer/traces.go"
        files (rank-files [(doc-row "connector/connectortest/connector.go"
                                    1
                                    8.0
                                    ["connector" "traces" "contract"])
                           (doc-row target-path
                                    7
                                    4.0
                                    ["consumer" "traces" "interface" "contract" "feeds"])
                           (candidate-row target-path
                                          524
                                          0.54
                                          "Traces"
                                          1
                                          ["consumer" "traces" "interface" "contract" "feeds"])
                           (candidate-row "consumer/consumer.go"
                                          520
                                          0.54
                                          "Option"
                                          0
                                          ["consumer" "contract"])])
        by-path (into {} (map (juxt :path identity)) files)]
    (is (pos? (get-in by-path [target-path
                               :metrics
                               :docSupportedExportedSupportBoost])))
    (is (< (:rank (get by-path target-path))
           (:rank (get by-path "connector/connectortest/connector.go"))))))

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

(deftest compact-output-preserves-late-path-self-identity-candidate
  (let [select-files @#'benchmark-prediction/select-limited-suspected-files
        compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "receiver/receivertest/contract_checker.go"
                   1
                   {:candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :candidateSourceRank 4
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 5
                    :sourceGraphCandidateEvidenceScore 0.53
                    :candidateGrepScore 0.47
                    :rankScore 17.8})
              (row "service/internal/graph/connector.go"
                   2
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :retrievedSupportLabelCount 2
                    :candidateSourceRank 2
                    :matchedPathQueryTokenCount 2
                    :matchedTokenCount 9
                    :sourceGraphCandidateEvidenceScore 0.61
                    :candidateGrepScore 0.59
                    :rankScore 15.6})
              (row "consumer/traces.go"
                   3
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :retrievedSupportLabelCount 2
                    :candidateSourceRank 24
                    :matchedPathQueryTokenCount 2
                    :matchedTokenCount 7
                    :sourceGraphCandidateEvidenceScore 0.52
                    :candidateGrepScore 0.08
                    :rankScore 20.8})
              (row "connector/connector.go"
                   4
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :retrievedSupportLabelCount 2
                    :queryMatchedPathSelfIdentity true
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 10
                    :candidateGrepScore 0.74
                    :rankScore 19.8})
              (row "connector/traces_router.go"
                   5
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :retrievedSupportLabelCount 3
                    :candidateSourceRank 6
                    :matchedPathQueryTokenCount 2
                    :matchedTokenCount 8
                    :sourceGraphCandidateEvidenceScore 0.62
                    :candidateGrepScore 0.32
                    :rankScore 15.5})
              (row "internal/fanoutconsumer/traces.go"
                   6
                   {:candidateFileCount 2
                    :docCount 0
                    :entityCount 0
                    :retrievedSupportLabelCount 3
                    :candidateSourceRank 15
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 5
                    :sourceGraphCandidateEvidenceScore 0.58
                    :candidateGrepScore 0.40
                    :rankScore 13.8})
              (row "receiver/receivertest/contract_checker_test.go"
                   23
                   {:candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :candidateSourceRank 23
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 4
                    :sourceGraphCandidateEvidenceScore 0.54
                    :candidateGrepScore 0.44
                    :rankScore 3.8})
              (row "internal/fanoutconsumer/traces_test.go"
                   24
                   {:candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :candidateSourceRank 73
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 4
                    :sourceGraphCandidateEvidenceScore 0.56
                    :candidateGrepScore 0.47
                    :rankScore 5.8})
              (row "confmap/resolver.go"
                   25
                   {:candidateFileCount 2
                    :docCount 0
                    :entityCount 0
                    :candidateSourceRank 161
                    :matchedTokenCount 3
                    :sourceGraphCandidateEvidenceScore 2.15
                    :rankScore 6.0})
              (row "cmd/otelcorecol/main_windows.go"
                   26
                   {:candidateFileCount 2
                    :docCount 0
                    :entityCount 0
                    :candidateSourceRank 166
                    :matchedTokenCount 3
                    :sourceGraphCandidateEvidenceScore 2.63
                    :rankScore 6.2})
              (row "component/component.go"
                   41
                   {:candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :queryMatchedPathSelfIdentity true
                    :matchedPathQueryTokenCount 1
                    :matchedTokenCount 2
                    :candidateGrepScore 0.33
                    :rankScore 1.8})]
        filler (mapv (fn [rank]
                       (row (str "noise/file_" rank ".go")
                            rank
                            {:docCount 1
                             :matchedTokenCount 4
                             :rankScore (- 14.0 rank)}))
                     (range 7 23))
        selected (:files (select-files (vec (sort-by :rank
                                                     (concat rows filler)))
                                       20))
        selected-paths (mapv :path selected)
        compact-paths (mapv :path (compact-output selected 20 nil))]
    (is (some #{"component/component.go"} selected-paths))
    (is (every? #(< (index-of compact-paths %) 5)
                ["connector/connector.go"
                 "consumer/traces.go"
                 "component/component.go"]))
    (is (< (index-of compact-paths "connector/connector.go")
           (index-of compact-paths
                     "receiver/receivertest/contract_checker_test.go")))))

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
    (is (some #{"pkg/pkg.go"} (take 5 (map :path selected))))
    (is (< (index-of (mapv :path selected) "pkg/pkg.go")
           (index-of (mapv :path selected) "noise-5.go")))))

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

(deftest compact-output-reserves-query-matched-exported-support
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
               (row "connector/traces_router.go"
                    2
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 8
                     :retrievedSupportLabelCount 3
                     :sourceGraphCandidateEvidenceScore 0.62
                     :rankScore 15.6})
               (row "service/internal/graph/connector.go"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 9
                     :sourceGraphCandidateEvidenceScore 0.61
                     :rankScore 15.5})
               (row "consumer/traces.go"
                    4
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 7
                     :matchedTokenPairCount 2
                     :sourceGraphCandidateEvidenceScore 0.54
                     :rankScore 15.1})
               (row "component/component.go"
                    5
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 19
                     :matchedTokenCount 2
                     :queryMatchedExportedSupportLabelCount 1
                     :candidateOnlyExportedSupportBoost 12.0
                     :rankScore 13.0})
               (row "internal/fanoutconsumer/traces.go"
                    6
                    {:candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 15
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.58
                     :retrievedSupportLabelCount 3
                     :rankScore 12.6})
               (row "connector/xconnector/connector.go"
                    7
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 10
                     :sourceGraphCandidateEvidenceScore 0.6
                     :rankScore 8.3})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (= "component/component.go" (nth paths 4)))
    (is (< (index-of paths "component/component.go")
           (index-of paths "internal/fanoutconsumer/traces.go")))))

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
              (row "modules/flow-log/identity.tf"
                   6
                   {:candidateFileCount 1
                    :directFileCandidateCount 1
                    :fileIdentitySupportLabelCount 4
                    :matchedTokenCount 4
                    :rankScore 13.0})
              (row "tail.tf" 7 {:rankScore 1.0})]
        selected (compact-output rows 20 nil)]
    (is (= ["main.tf"
            "variables.tf"
            "modules/flow-log/variables.tf"
            "vpc-flow-logs.tf"]
           (->> selected
                (map :path)
                (take 4)
                vec)))))

(deftest compact-output-keeps-small-candidate-only-surfaces
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank rank-score]
              {:path path
               :rank rank
               :metrics {:candidateFileCount 1
                         :matchedTokenCount 3
                         :sourceGraphCandidateEvidenceScore 0.6
                         :rankScore rank-score}})
        rows [(row "one.clj" 1 9.6)
              (row "two.clj" 2 9.1)
              (row "three.clj" 3 6.6)
              (row "four.clj" 4 5.1)
              (row "five.clj" 5 3.1)
              (row "six.clj" 6 2.6)]
        selected (compact-output rows 20 nil)]
    (is (= ["one.clj"
            "two.clj"
            "three.clj"
            "four.clj"
            "five.clj"
            "six.clj"]
           (mapv :path selected)))))

(deftest compact-output-frontloads-retrieved-path-grep-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "one.clj" 1 {:rankScore 30.0})
              (row "two.clj" 2 {:rankScore 29.0})
              (row "three.clj" 3 {:rankScore 28.0})
              (row "four.clj" 4 {:rankScore 27.0})
              (row "source-graph.clj"
                   5
                   {:candidateFileCount 2
                    :sourceGraphQueryEvidenceBoost 8.0
                    :rankScore 26.0})
              (row "target.clj"
                   6
                   {:docCount 1
                    :retrievedSourceCount 1
                    :matchedPathQueryTokenCount 2
                    :retrievedPathQueryTokenBoost 6.0
                    :retrievedPathGrepEvidenceBoost 4.0
                    :rankScore 25.0})]
        selected (compact-output rows 20 nil)]
    (is (= ["one.clj" "two.clj" "three.clj" "four.clj" "target.clj"]
           (->> selected
                (map :path)
                (take 5)
                vec)))))

(deftest compact-output-frontloads-candidate-source-graph-head
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "variables.tf" 1 {:rankScore 39.0})
              (row "modules/flow-log/variables.tf" 2 {:rankScore 38.0})
              (row "modules/flow-log/main.tf" 3 {:rankScore 30.0})
              (row "vpc-flow-logs.tf" 4 {:rankScore 29.0})
              (row "outputs.tf"
                   5
                   {:candidateFileCount 1
                    :candidateGrepScore 0.6
                    :candidateSourceRank 5
                    :docCount 1
                    :matchedTokenCount 8
                    :rankScore 28.0
                    :retrievedSourceCount 1
                    :sourceGraphCandidateEvidenceScore 0.54})
              (row "examples/flow-log/variables.tf" 6 {:rankScore 27.0})
              (row "main.tf"
                   7
                   {:candidateFileCount 1
                    :docCount 0
                    :candidateOnlySourceGraphHeadBoost 8.0
                    :candidateSourceRank 2
                    :matchedTokenCount 8
                    :sourceGraphCandidateEvidenceScore 0.55
                    :rankScore 17.0})]
        selected-paths (mapv :path (compact-output rows 20 nil))]
    (is (= "main.tf" (nth selected-paths 4)))))

(deftest compact-output-frontloads-support-owner-source-graph-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "src/flask/config.py" 1 {:docCount 1
                                             :rankScore 16.0})
               (row "src/flask/json/tag.py" 2 {:candidateFileCount 1
                                               :docCount 0
                                               :directFileCandidateCount 1
                                               :matchedTokenCount 2
                                               :rankScore 9.1})
               (row "src/flask/helpers.py" 3 {:docCount 1
                                              :rankScore 8.7})
               (row "src/flask/debughelpers.py" 4 {:docCount 1
                                                   :rankScore 8.3})
               (row "src/flask/blueprints.py" 5 {:docCount 1
                                                 :rankScore 7.4})
               (row "src/flask/sansio/scaffold.py"
                    8
                    {:candidateFileCount 1
                     :candidateSourceRank 7
                     :directFileCandidateCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 4
                     :rankScore 6.4
                     :retrievedSupportLabelCount 6
                     :sourceGraphCandidateEvidenceScore 0.61
                     :supportOwnerEvidenceCount 1
                     :supportOwnerPrimaryLabelMatchedTokenCount 2
                     :supportOwnerPrimaryLabelSpecificTokenCount 0})
               (row "tests/test_request.py" 9 {:docCount 1
                                               :rankScore 6.7})
               (row "src/flask/signals.py" 10 {:docCount 1
                                               :rankScore 6.6})
               (row "src/flask/__init__.py" 11 {:docCount 1
                                                :rankScore 6.3})
               (row "src/flask/sansio/app.py"
                    19
                    {:candidateFileCount 1
                     :candidateSourceRank 8
                     :directFileCandidateCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 3
                     :rankScore 4.9
                     :retrievedSupportLabelCount 6
                     :sourceGraphCandidateEvidenceScore 0.61
                     :supportOwnerEvidenceCount 1
                     :supportOwnerPrimaryLabelMatchedTokenCount 2
                     :supportOwnerPrimaryLabelSpecificTokenCount 1})
               (row "tests/test_json_tag.py" 20 {:candidateFileCount 1
                                                 :docCount 0
                                                 :matchedTokenCount 2
                                                 :rankScore 1.8})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (= "src/flask/sansio/app.py" (nth selected-paths 4)))
    (is (< (index-of selected-paths "src/flask/sansio/app.py")
           (index-of selected-paths "tests/test_json_tag.py")))))

(deftest compact-output-frontloads-doc-source-graph-grep-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "a/core.clj" 1 {:rankScore 40.0})
              (row "a/globals.clj" 2 {:rankScore 35.0})
              (row "a/shell.clj" 3 {:rankScore 30.0})
              (row "a/cli.clj" 4 {:rankScore 25.0})
              (row "a/weak-candidate.clj"
                   5
                   {:candidateFileCount 1
                    :candidateOnlySourceGraphHeadBoost 6.0
                    :candidateSourceRank 4
                    :docCount 0
                    :matchedTokenCount 9
                    :rankScore 21.0
                    :sourceGraphCandidateEvidenceScore 0.44})
              (row "a/debug.clj" 6 {:rankScore 20.0})
              (row "a/context.clj"
                   8
                   {:candidateFileCount 1
                    :candidateGrepScore 0.62
                    :candidateSourceRank 5
                    :docCount 1
                    :matchedTokenCount 11
                    :rankScore 9.0
                    :retrievedSourceCount 1
                    :sourceGraphCandidateEvidenceScore 0.54})]
        selected-paths (mapv :path (compact-output rows 20 nil))]
    (is (= "a/context.clj" (nth selected-paths 4)))))

(deftest compact-output-frontloads-source-graph-query-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/Dapper.Tests/ParameterTests.cs"
                    1
                    {:docCount 1
                     :rankScore 25.9})
               (row "Dapper/SqlMapper.Settings.cs"
                    2
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 7
                     :matchedTokenPairCount 3
                     :sourceGraphQueryEvidenceBoost 9.0
                     :sourceGraphCandidateEvidenceScore 0.51
                     :rankScore 22.5})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.EntityFrameworkCore.cs"
                    3
                    {:docCount 1
                     :rankScore 21.0})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Mighty.cs"
                    4
                    {:docCount 1
                     :rankScore 19.4})
               (row "benchmarks/Dapper.Tests.Performance/LegacyTests.cs"
                    5
                    {:docCount 1
                     :retrievedSourceCount 1
                     :matchedPathQueryTokenCount 2
                     :rankScore 15.5})
               (row "Dapper/SqlMapper.cs"
                    6
                    {:docCount 1
                     :candidateFileCount 5
                     :matchedTokenCount 9
                     :matchedTokenPairCount 3
                     :sourceGraphQueryEvidenceBoost 9.0
                     :sourceGraphCandidateEvidenceScore 0.59
                     :rankScore 19.45})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (every? #(< (index-of paths %) 5)
                ["Dapper/SqlMapper.Settings.cs"
                 "Dapper/SqlMapper.cs"]))
    (is (< (index-of paths "Dapper/SqlMapper.cs")
           (index-of paths
                     "benchmarks/Dapper.Tests.Performance/LegacyTests.cs")))))

(deftest compact-output-frontloads-source-graph-query-evidence-pair
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/Dapper.Tests/ParameterTests.cs"
                    1
                    {:docCount 1
                     :candidateFileCount 3
                     :matchedTokenCount 8
                     :matchedTokenPairCount 3
                     :rankScore 25.9})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                    2
                    {:candidateFileCount 2
                     :candidateOnlySourceGraphHeadBoost 9.0
                     :matchedTokenCount 5
                     :rankScore 23.1
                     :sourceGraphCandidateEvidenceScore 0.56})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                    3
                    {:docCount 1
                     :candidateFileCount 15
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 6
                     :matchedTokenPairCount 2
                     :rankScore 14.6
                     :sourceGraphCandidateEvidenceScore 0.50})
               (row "tests/Dapper.Tests/EnumTests.cs"
                    4
                    {:docCount 3
                     :candidateFileCount 1
                     :matchedTokenCount 4
                     :rankScore 24.3
                     :retrievedPathGrepEvidenceBoost 4.0})
               (row "Dapper/SqlMapper.Settings.cs"
                    5
                    {:candidateFileCount 1
                     :directFileCandidateCount 1
                     :directoryEvidenceBoost 6.7
                     :fileIdentitySupportLabelCount 2
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :matchedTokenCount 7
                     :matchedTokenPairCount 3
                     :rankScore 22.7
                     :sourceGraphCandidateEvidenceScore 0.50
                     :sourceGraphQueryEvidenceBoost 9.0})
               (row "Dapper/SqlMapper.cs"
                    6
                    {:candidateFileCount 5
                     :candidateGrepScore 0.86
                     :docCount 1
                     :docSupportedSourceGraphQueryBoost 2.0
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :matchedTokenCount 9
                     :matchedTokenPairCount 4
                     :rankScore 21.4
                     :sourceGraphCandidateEvidenceScore 0.59
                     :sourceGraphQueryEvidenceBoost 9.0})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Dapper.cs"
                    20
                    {:candidateFileCount 1
                     :candidateSourceRank 4
                     :directFileCandidateCount 1
                     :directoryEvidenceBoost 8.0
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 3
                     :rankScore 12.6
                     :sourceGraphCandidateEvidenceScore 0.47})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (every? #(< (index-of paths %) 5)
                ["Dapper/SqlMapper.Settings.cs"
                 "Dapper/SqlMapper.cs"]))
    (is (< (index-of paths "Dapper/SqlMapper.cs")
           (index-of paths "tests/Dapper.Tests/EnumTests.cs")))
    (is (< (index-of paths "Dapper/SqlMapper.Settings.cs")
           (index-of paths
                     "benchmarks/Dapper.Tests.Performance/Benchmarks.Dapper.cs")))))

(deftest compact-output-frontloads-doc-directory-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path repo-id rank metrics]
              {:path path
               :repo-id repo-id
               :rank rank
               :metrics metrics})
        files [(row "connector/routingconnector/metadata.yaml"
                    "contrib"
                    1
                    {:docCount 1
                     :directoryEvidenceBoost 8.0
                     :rankScore 32.0})
               (row "connector/routingconnector/factory_test.go"
                    "contrib"
                    2
                    {:docCount 2
                     :directoryEvidenceBoost 8.0
                     :rankScore 30.0})
               (row "connector/routingconnector/factory.go"
                    "contrib"
                    3
                    {:docCount 1
                     :directoryEvidenceBoost 8.0
                     :rankScore 28.0})
               (row "connector/routingconnector/config.go"
                    "contrib"
                    4
                    {:docCount 3
                     :directoryEvidenceBoost 8.0
                     :rankScore 25.0})
               (row "connector/connector.go"
                    "core"
                    5
                    {:docCount 1
                     :retrievedSourceCount 1
                     :queryMatchedPathSelfIdentity true
                     :retrievedQueryFileIdentityBoost 12.0
                     :rankScore 18.0})
               (row "connector/routingconnector/config.schema.yaml"
                    "contrib"
                    6
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.47
                     :directoryEvidenceBoost 8.0
                     :rankScore 20.0})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (< (index-of paths "connector/routingconnector/config.schema.yaml")
           (index-of paths "connector/connector.go")))
    (is (some #{"connector/routingconnector/config.schema.yaml"}
              (take 5 paths)))))

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

(deftest diversity-keeps-exact-retrieved-identities-before-protected-candidate-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        exact-row (fn [path rank rank-score]
                    {:path path
                     :rank rank
                     :metrics {:docCount 1
                               :retrievedSourceCount 1
                               :retrievedQueryFileIdentityBoost 12.0
                               :queryMatchedFileIdentityMaxLength 18
                               :rankScore rank-score
                               :definitionKinds ["class"]}})
        candidate-head-row (fn [path rank rank-score]
                             {:path path
                              :rank rank
                              :metrics {:candidateFileCount 1
                                        :docCount 0
                                        :candidateOnlySourceGraphHeadBoost 9.0
                                        :rankScore rank-score
                                        :definitionKinds ["class"]}})
        rows [(exact-row "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/descriptor/JupiterEngineDescriptor.java"
                         1
                         35.0)
              (exact-row "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/JupiterTestEngine.java"
                         2
                         34.0)
              (exact-row "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/execution/JupiterEngineExecutionContext.java"
                         3
                         33.0)
              (candidate-head-row "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/execution/LauncherStoreFacade.java"
                                  4
                                  31.0)
              (candidate-head-row "jupiter-tests/src/test/java/org/junit/jupiter/engine/descriptor/TestFactoryTestDescriptorTests.java"
                                  5
                                  29.0)]
        diversified (diversify rows)]
    (is (= ["junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/descriptor/JupiterEngineDescriptor.java"
            "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/JupiterTestEngine.java"
            "junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/execution/JupiterEngineExecutionContext.java"]
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

(deftest compact-output-prefers-file-owned-retrieved-label-doc-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/adapters.test.js"
                    1
                    {:docCount 1
                     :candidateFileCount 3
                     :retrievedSourceCount 1
                     :retrievedSupportLabelCount 2
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.44
                     :rankScore 20.2})
               (row "tests/unit/adapters/http.test.js"
                    2
                    {:docCount 1
                     :candidateFileCount 4
                     :retrievedSourceCount 1
                     :retrievedSupportLabelCount 4
                     :matchedTokenCount 11
                     :sourceGraphCandidateEvidenceScore 0.59
                     :candidateGrepScore 0.90
                     :rankScore 20.1})
               (row "tests/smoke/cjs/tests/files.smoke.test.cjs"
                    3
                    {:docCount 1
                     :candidateFileCount 2
                     :retrievedSourceCount 1
                     :retrievedSupportLabelCount 2
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.55
                     :candidateGrepScore 0.05
                     :rankScore 19.2})
               (row "tests/smoke/esm/tests/files.smoke.test.js"
                    4
                    {:docCount 1
                     :candidateFileCount 2
                     :retrievedSourceCount 1
                     :retrievedSupportLabelCount 2
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.55
                     :candidateGrepScore 0.05
                     :rankScore 18.2})
               (row "lib/core/AxiosError.js"
                    5
                    {:docCount 1
                     :candidateFileCount 2
                     :retrievedSourceCount 1
                     :retrievedSupportLabelCount 5
                     :matchedTokenCount 8
                     :sourceGraphCandidateEvidenceScore 0.56
                     :candidateGrepScore 0.05
                     :rankScore 7.0})
               (row "tests/unit/adapters/fetch.test.js"
                    6
                    {:docCount 1
                     :candidateFileCount 3
                     :retrievedSourceCount 1
                     :fileIdentitySupportLabelCount 3
                     :retrievedSupportLabelCount 4
                     :matchedTokenCount 10
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.39
                     :rankScore 14.1})
               (row "lib/adapters/http.js"
                    7
                    {:docCount 2
                     :candidateFileCount 3
                     :retrievedSourceCount 2
                     :fileIdentitySupportLabelCount 4
                     :retrievedSupportLabelCount 5
                     :matchedTokenCount 6
                     :sourceGraphCandidateEvidenceScore 0.53
                     :candidateGrepScore 0.32
                     :rankScore 13.0})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (= "lib/adapters/http.js" (nth selected-paths 4)))
    (is (< (.indexOf selected-paths "lib/adapters/http.js")
           (.indexOf selected-paths "lib/core/AxiosError.js")))))

(deftest compact-output-frontloads-repeated-retrieved-doc-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/smoke/esm/tests/files.smoke.test.js"
                    1
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 7
                     :rankScore 14.0})
               (row "tests/smoke/cjs/tests/files.smoke.test.cjs"
                    2
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 7
                     :rankScore 13.8})
               (row "tests/unit/adapters/http.test.js"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 6
                     :rankScore 13.6})
               (row "tests/unit/headers.test.js"
                    4
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 5
                     :rankScore 13.4})
               (row "tests/unit/errorDetails.test.js"
                    5
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 7
                     :rankScore 13.2})
               (row "lib/core/Error.js"
                    6
                    {:docCount 1
                     :candidateFileCount 3
                     :retrievedSourceCount 1
                     :candidateSupportLabelSignatures [["shared-source"]]
                     :matchedTokenCount 8
                     :rankScore 12.2})
               (row "lib/adapters/http.js"
                    13
                    {:docCount 2
                     :candidateFileCount 2
                     :retrievedSourceCount 2
                     :candidateSupportLabelSignatures [["shared-source"]]
                     :matchedTokenCount 4
                     :matchedTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.52
                     :rankScore 11.0})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (= "lib/adapters/http.js" (nth selected-paths 4)))))

(deftest compact-output-keeps-repeated-retrieved-doc-before-query-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/http.test.js"
                    1
                    {:docCount 1
                     :candidateFileCount 3
                     :retrievedSourceCount 1
                     :matchedTokenCount 10
                     :sourceGraphCandidateEvidenceScore 0.63
                     :candidateGrepScore 0.91
                     :rankScore 20.39})
               (row "tests/unit/adapters/adapters.test.js"
                    2
                    {:docCount 1
                     :candidateFileCount 3
                     :retrievedSourceCount 1
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.58
                     :candidateGrepScore 0.44
                     :rankScore 19.89})
               (row "tests/unit/adapters/fetch.test.js"
                    3
                    {:docCount 1
                     :candidateFileCount 4
                     :retrievedSourceCount 1
                     :matchedTokenCount 9
                     :matchedTokenPairCount 2
                     :sourceGraphCandidateEvidenceScore 0.57
                     :candidateGrepScore 0.39
                     :rankScore 19.53})
               (row "tests/unit/core/AxiosError.test.js"
                    4
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.56
                     :candidateGrepScore 0.32
                     :rankScore 13.82})
               (row "lib/env/data.js"
                    5
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 4
                     :rankScore 13.78})
               (row "lib/adapters/http.js"
                    8
                    {:docCount 2
                     :candidateFileCount 2
                     :retrievedSourceCount 2
                     :repeatedRetrievedSourceBoost 3.2
                     :matchedTokenCount 7
                     :matchedTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.48
                     :retrievedSupportLabelCount 2
                     :rankScore 11.70})
               (row "lib/axios.js"
                    14
                    {:candidateFileCount 3
                     :candidateSourceRank 18
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.52
                     :candidateGrepScore 0.44
                     :rankScore 5.92})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (= "lib/adapters/http.js" (nth selected-paths 4)))
    (is (< (.indexOf selected-paths "lib/adapters/http.js")
           (.indexOf selected-paths "lib/axios.js")))))

(deftest compact-output-frontloads-strong-doc-supported-query-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/docker-compose.yml"
                    1
                    {:candidateFileCount 3
                     :directFileCandidateCount 1
                     :matchedTokenCount 4
                     :rankScore 11.8})
               (row "Dapper/SqlMapper.cs"
                    2
                    {:candidateFileCount 25
                     :matchedTokenCount 7
                     :rankScore 19.4})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.ServiceStack.cs"
                    3
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedPathQueryTokenCount 3
                     :matchedTokenCount 8
                     :matchedTokenPairCount 1
                     :rankScore 21.2})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.EntityFrameworkCore.cs"
                    4
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedPathQueryTokenCount 3
                     :matchedTokenCount 8
                     :matchedTokenPairCount 1
                     :rankScore 20.5})
               (row "tests/Dapper.Tests/Providers/EntityFrameworkTests.cs"
                    5
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedPathQueryTokenCount 2
                     :matchedTokenCount 9
                     :matchedTokenPairCount 2
                     :rankScore 13.7
                     :retrievedSourceCount 1
                     :sourceGraphCandidateEvidenceScore 0.58})
               (row "tests/Dapper.Tests/TypeHandlerTests.cs"
                    6
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedPathQueryTokenCount 4
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :rankScore 18.2
                     :retrievedSourceCount 1
                     :sourceGraphCandidateEvidenceScore 0.51})
               (row "Dapper/SqlMapper.ITypeHandler.cs"
                    8
                    {:docCount 1
                     :candidateFileCount 1
                     :firstSourceRank 1
                     :matchedPathQueryTokenCount 4
                     :matchedTokenCount 7
                     :matchedTokenPairCount 1
                     :queryMatchedPathSelfIdentity true
                     :rankScore 17.9
                     :retrievedSourceCount 1})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (= "tests/Dapper.Tests/TypeHandlerTests.cs" (nth selected-paths 4)))
    (is (< (index-of selected-paths "tests/Dapper.Tests/TypeHandlerTests.cs")
           (index-of selected-paths "Dapper/SqlMapper.ITypeHandler.cs")))))

(deftest compact-output-frontloads-doc-supported-source-graph-query-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/Dapper.Tests/ParameterTests.cs"
                    1
                    {:docCount 1
                     :candidateFileCount 3
                     :matchedTokenCount 8
                     :rankScore 25.9})
               (row "tests/Dapper.Tests/EnumTests.cs"
                    2
                    {:docCount 3
                     :candidateFileCount 1
                     :matchedTokenCount 4
                     :rankScore 24.3})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                    3
                    {:candidateFileCount 2
                     :candidateOnlySourceGraphHeadBoost 9.0
                     :matchedTokenCount 5
                     :rankScore 23.1})
               (row "Dapper/SqlMapper.Settings.cs"
                    4
                    {:candidateFileCount 1
                     :directFileCandidateCount 1
                     :matchedTokenCount 7
                     :sourceGraphQueryEvidenceBoost 9.0
                     :rankScore 22.8})
               (row "Dapper/SqlMapper.cs"
                    5
                    {:docCount 1
                     :candidateFileCount 5
                     :docSupportedSourceGraphQueryBoost 2.0
                     :matchedTokenCount 9
                     :rankScore 21.4
                     :sourceGraphQueryEvidenceBoost 9.0})
               (row "tests/Dapper.Tests/TypeHandlerTests.cs"
                    7
                    {:docCount 3
                     :candidateFileCount 1
                     :matchedPathQueryTokenCount 4
                     :matchedTokenCount 8
                     :rankScore 20.7
                     :repeatedRetrievedSourceBoost 3.2
                     :retrievedSourceCount 3})]
        selected-paths (mapv :path (compact-output files 20 nil))]
    (is (every? #(< (index-of selected-paths %) 5)
                ["Dapper/SqlMapper.Settings.cs"
                 "Dapper/SqlMapper.cs"]))
    (is (< (index-of selected-paths "Dapper/SqlMapper.cs")
           (index-of selected-paths "tests/Dapper.Tests/TypeHandlerTests.cs")))))

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
