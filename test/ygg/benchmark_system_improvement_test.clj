(ns ygg.benchmark-system-improvement-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-system-improvement :as benchmark-system-improvement]
            [ygg.benchmark-test-support :refer [spit-json! temp-dir]]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- signal-by-kind
  [signals]
  (into {} (map (juxt :kind identity)) signals))

(deftest classifies-benchmark-diagnostics-into-system-improvement-lanes
  (let [base-report {:suite-id "suite"
                     :results [{:case-id "case-1"
                                :tags ["problem-architecture"
                                       "architecture-runtime-boundary"]}
                               {:case-id "case-2"
                                :tags ["problem-localization"]}]
                     :agentDiagnostics {:commandlessRuns 1
                                        :commandlessCaseIds ["case-1"]
                                        :missingTokenUsageRuns 1
                                        :missingTokenUsageCaseIds ["case-1"]
                                        :hintDiagnosticsByKind []}
                     :artifactDiagnostics {:unverifiedScoreRuns 1
                                           :unverifiedScoreCaseIds ["case-2"]}
                     :coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                           :missingDeclaredSourceKindCaseIds ["case-1"]
                                           :missingDeclaredSourceKinds [{:kind "code"
                                                                         :runs 1
                                                                         :cases 1
                                                                         :caseIds ["case-1"]}]}
                     :graphExpectationDiagnostics {:failedRuns 1
                                                   :failedCaseIds ["case-1"]}
                     :localizationDiagnostics {:missedAndAbsentFromContextRuns 1
                                               :missedAndAbsentFromContextCaseIds ["case-1"]
                                               :missedButPresentInContextRuns 1
                                               :missedButPresentInContextCaseIds ["case-2"]
                                               :rankedOutsideTop5Runs 1
                                               :rankedOutsideTop5CaseIds ["case-2"]
                                               :contextRankMissingRuns 1
                                               :contextRankMissingCaseIds ["case-1"]}
                     :decisionDiagnostics {:gapRuns 1
                                           :gapCaseIds ["case-1"]
                                           :choiceGaps [{:kind "missed"
                                                         :id "plan-runtime"
                                                         :runs 1
                                                         :caseIds ["case-1"]}]}
                     :maintenancePreflightDiagnostics {:blockedRuns 1
                                                       :blockedCaseIds ["case-1"]}
                     :problemClasses {:classes []
                                      :architectureClasses []}}
        signals (signal-by-kind
                 (benchmark-system-improvement/report->system-improvement-signals
                  base-report))]
    (is (= "indexing-gap"
           (get-in signals ["graph-expectation-failures" :lane])))
    (is (= "maintenance-emitter-gap"
           (get-in signals ["maintenance-preflight-gaps" :lane])))
    (is (= "indexing-gap"
           (get-in signals ["missed-files-absent-from-context" :lane])))
    (is (= "retrieval-gap"
           (get-in signals ["missed-files-present-in-context" :lane])))
    (is (= "retrieval-gap"
           (get-in signals ["ranked-outside-top5" :lane])))
    (is (= "agent-protocol-gap"
           (get-in signals ["missing-context-ranks" :lane])))
    (is (= "benchmark-suite-gap"
           (get-in signals ["missing-declared-source-kinds" :lane])))
    (is (= "agent-protocol-gap"
           (get-in signals ["commandless-runs" :lane])))
    (is (= "token-telemetry-gap"
           (get-in signals ["missing-token-usage" :lane])))
    (is (= "decision-quality-gap"
           (get-in signals ["decision-quality-gaps" :lane])))
    (is (= [{:kind "missed"
             :id "plan-runtime"
             :runs 1
             :caseIds ["case-1"]}]
           (get-in signals ["decision-quality-gaps" :evidence])))
    (is (= "agent-protocol-gap"
           (get-in signals ["unverified-score-artifacts" :lane])))))

(deftest classifies-agent-contract-artifact-blockers
  (let [signals (signal-by-kind
                 (benchmark-system-improvement/report->system-improvement-signals
                  {:artifactDiagnostics
                   {:obsoleteAgentResultContractRuns 2
                    :obsoleteAgentResultContractCaseIds ["case-a" "case-b"]
                    :obsoleteAgentResultContractVersions ["old-contract"]
                    :expectedAgentResultContractVersion "current-contract"
                    :staleAgentInputRuns 1
                    :staleAgentInputCaseIds ["case-b"]
                    :unverifiedScoreRuns 2
                    :unverifiedScoreCaseIds ["case-a" "case-b"]}}))]
    (is (= "agent-protocol-gap"
           (get-in signals ["obsolete-agent-result-contract" :lane])))
    (is (= [{:expectedAgentResultContractVersion "current-contract"
             :obsoleteAgentResultContractVersions ["old-contract"]}]
           (get-in signals ["obsolete-agent-result-contract" :evidence])))
    (is (= "agent-protocol-gap"
           (get-in signals ["stale-agent-input-fingerprints" :lane])))
    (is (= [{:staleAgentInputRuns 1
             :staleAgentInputCaseIds ["case-b"]}]
           (get-in signals ["stale-agent-input-fingerprints" :evidence])))
    (is (= [{:obsoleteAgentResultContractRuns 2
             :staleAgentInputRuns 1}]
           (get-in signals ["unverified-score-artifacts" :evidence])))))

(deftest source-diagnostics-shift-missing-source-kinds-to-extractor-gap
  (let [signals (signal-by-kind
                 (benchmark-system-improvement/report->system-improvement-signals
                  {:coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                         :missingDeclaredSourceKindCaseIds ["case-1"]
                                         :missingDeclaredSourceKinds [{:kind "python"
                                                                       :runs 1
                                                                       :cases 1
                                                                       :caseIds ["case-1"]}]}
                   :agentDiagnostics {:hintDiagnosticsByKind [{:kind "source-extraction-diagnostics"
                                                               :runs 1
                                                               :cases 1
                                                               :caseIds ["case-1"]}]}}))]
    (is (= "extractor-gap"
           (get-in signals ["missing-declared-source-kinds" :lane])))
    (is (= "extractor-gap"
           (get-in signals ["source-extraction-diagnostics" :lane])))))

(deftest aggregates-system-improvement-lanes-by-declared-classes
  (let [report (benchmark-system-improvement/system-improvement-report
                {:schema benchmark/agent-report-schema
                 :suite-id "suite"
                 :cases 2
                 :completed 2
                 :runs 2
                 :claimReadiness {:status "not-supported"}
                 :results [{:case-id "case-1"
                            :tags ["problem-architecture"
                                   "architecture-runtime-boundary"]}
                           {:case-id "case-2"
                            :tags ["problem-architecture"
                                   "architecture-runtime-boundary"]}]
                 :systemImprovementSignals [{:kind "ranked-outside-top5"
                                             :lane "retrieval-gap"
                                             :runs 2
                                             :caseIds ["case-1" "case-2"]
                                             :confidence "high"}
                                            {:kind "commandless-runs"
                                             :lane "agent-protocol-gap"
                                             :runs 1
                                             :caseIds ["case-2"]
                                             :confidence "medium"}]})]
    (is (= benchmark/system-improvement-report-schema (:schema report)))
    (is (= false (:claimReady? report)))
    (is (= [{:lane "retrieval-gap"
             :suggestedOwnerArea "retrieval"
             :affectedCases 2
             :runs 2
             :confidence "high"
             :signalKinds ["ranked-outside-top5"]}
            {:lane "agent-protocol-gap"
             :suggestedOwnerArea "agent-protocol"
             :affectedCases 1
             :runs 1
             :confidence "medium"
             :signalKinds ["commandless-runs"]}]
           (get-in report [:summary :lanes])))
    (is (= [{:class "problem-architecture"
             :affectedCases 2
             :affectedCaseIds ["case-1" "case-2"]
             :lanes ["agent-protocol-gap" "retrieval-gap"]
             :signalKinds ["commandless-runs" "ranked-outside-top5"]
             :runs 3}]
           (get-in report [:summary :problemClasses])))
    (is (= [{:class "architecture-runtime-boundary"
             :affectedCases 2
             :affectedCaseIds ["case-1" "case-2"]
             :lanes ["agent-protocol-gap" "retrieval-gap"]
             :signalKinds ["commandless-runs" "ranked-outside-top5"]
             :runs 3}]
           (get-in report [:summary :architectureClasses])))))

(deftest improve-command-writes-report-without-sidecar-artifacts
  (let [out (temp-dir "ygg-bench-improve")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :repo-id "repo"
                        :tags ["problem-localization"]}]}
        sentinel-path (io/file out "sentinel.json")
        queue-dir (io/file out "queue")]
    (spit sentinel-path "{\"stable\":true}\n")
    (spit-json! out
                "suite/cases/case-1/agent-scores/run.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "codex"
                         :mode "ygg"
                         :topFiles []
                         :commands []}
                 :groundTruth {:changedFiles ["src/app.clj"]
                               :scoreableFiles ["src/app.clj"]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :found? false}]}
                 :scores {:fileRecallAt5 0.0
                          :fileRecallAt10 0.0
                          :fileRecallAt20 0.0
                          :meanReciprocalRankFile 0.0
                          :noiseRatioAt20 0.0
                          :changedFiles 1
                          :scoreableChangedFiles 1
                          :unsupportedGroundTruthFiles 0}})
    (let [report (benchmark/improve-agent-suite suite {:out out
                                                       :allow-unverified-scores? true})]
      (is (= benchmark/system-improvement-report-schema (:schema report)))
      (is (.isFile (io/file out
                            "suite"
                            "system-improvement-report.json")))
      (is (= "{\"stable\":true}\n" (slurp sentinel-path)))
      (is (not (.exists queue-dir))))))
