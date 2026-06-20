(ns agraph.benchmark-report-test
  (:require [agraph.benchmark :as benchmark]
            [agraph.benchmark-command-telemetry :as benchmark-command-telemetry]
            [agraph.benchmark-preflight :as benchmark-preflight]
            [agraph.benchmark-report :as benchmark-report]
            [agraph.benchmark-test-support :refer [spit-json! temp-dir]]
            [clojure.test :refer [deftest is]]))

(def passing-maintenance-preflight
  {:schema benchmark-preflight/maintenance-preflight-schema
   :status "passed"
   :checks {:index {:status "passed"
                    :summary {:files 1}}
            :infer {:status "passed"
                    :summary {:systems 1}}
            :graphExpectations {:status "not-configured"}
            :hintDiagnostics {:status "passed"
                              :rows 0
                              :blockingRows 0
                              :blockingKinds []}
            :syncCheck {:status "passed"
                        :validationGaps []
                        :blockingValidationGaps []}}})

(def failing-sync-maintenance-preflight
  (assoc-in
   (assoc passing-maintenance-preflight :status "failed")
   [:checks :syncCheck]
   {:status "failed"
    :validationGaps [{:plane "dependencies"
                      :status "weak"
                      :diagnostics [{:reason :candidate-unresolved
                                     :count 2
                                     :blocking true
                                     :message "Source import candidates were extracted, but some did not resolve to package facts."}
                                    {:reason :package-version-conflict
                                     :count 9
                                     :blocking false
                                     :message "Package version conflicts are present in indexed dependency facts."}]}]
    :blockingValidationGaps [{:plane "dependencies"
                              :status "weak"
                              :diagnostics [{:reason :candidate-unresolved
                                             :count 2
                                             :blocking true
                                             :message "Source import candidates were extracted, but some did not resolve to package facts."}
                                            {:reason :package-version-conflict
                                             :count 9
                                             :blocking false
                                             :message "Package version conflicts are present in indexed dependency facts."}]}]}))

(deftest reports-agent-score-artifacts
  (let [out (temp-dir "agraph-agent-report")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :tags [:runtime-config :auth]}
                       {:id "case-2"
                        :tags [:dependency]}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :tags ["auth" "runtime-config"]
                 :parserWorker {:mode "all"
                                :source "option"}
                 :expectations {:evidence [{:kind "auth-reference"
                                            :path "src/app.clj"}]
                                :forbidden-edges ["shares-config"]}
                 :graphExpectations {:schema benchmark/graph-expectations-schema
                                     :status "failed"
                                     :summary {:expectedEvidence 1
                                               :foundEvidence 1
                                               :missingEvidence 0
                                               :expectedEdges 0
                                               :foundEdges 0
                                               :missingEdges 0
                                               :forbiddenEdges 1
                                               :forbiddenEdgeViolations 1}}
                 :agent {:agentId "codex"
                         :mode "agraph"
                         :topFiles [{:path "src/other.clj"
                                     :rank 1
                                     :metrics {:supportCount 2
                                               :docCount 1}}
                                    {:path "src/app.clj"
                                     :rank 7}]
                         :warnings ["agent result suspectedFiles row 1 missing evidence"]
                         :commands ["rg broken src"
                                    "sed -n '1,40p' src/app.clj"
                                    "agraph ask broken --project fixture"
                                    "npm test"]
                         :selection {:rawCandidateFiles 3
                                     :candidateFiles 2
                                     :coverageFilteredCandidateFiles 1
                                     :limit 20
                                     :coverageSourceKinds ["code"]}}
                 :agraphHints {:diagnostics [{:kind "coverage-filtered-candidate-files"
                                              :severity "info"
                                              :message "Declared source coverage filtered candidate files out of the agent shortlist."
                                              :filteredCandidateFiles 1}
                                             {:kind "source-skipped-files"
                                              :severity "info"
                                              :message "Indexed source coverage contains skipped files; inspect sourceCoverage skipped breakdowns before treating missing facts as absent."
                                              :skippedFiles 2}
                                             {:kind "audit-scope-trust-boundary"
                                              :severity "warning"
                                              :message "Audit scope contains skipped files, extractor diagnostics, or unclassified extractor rows."
                                              :scope "unclassified-extractor"
                                              :supportedFiles 1
                                              :skippedFiles 1
                                              :diagnostics 1
                                              :facts 2}]}
                 :coverage {:declaredSourceKinds ["code" "python"]
                            :scoreableSourceKinds ["code"]
                            :scoreableFilesByKind [{:kind "code"
                                                    :files 2}]
                            :missingDeclaredSourceKinds ["python"]}
                 :inputHints {:hinted true
                              :mentionedChangedFiles ["src/app.clj"]
                              :mentionedChangedFileCount 1
                              :changedFileCount 3}
                 :groundTruth {:changedFiles ["CHANGELOG.md"
                                              "src/app.clj"
                                              "src/missing.clj"]
                               :localizationFiles ["CHANGELOG.md"
                                                   "src/app.clj"
                                                   "src/missing.clj"]
                               :scoreableFiles ["src/app.clj"
                                                "src/missing.clj"]
                               :coverageExcludedFiles [{:path "CHANGELOG.md"
                                                        :kind "doc"}]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :rank 7
                                             :found? true}
                                            {:path "src/missing.clj"
                                             :found? false}]}
                 :contextGroundTruthRanks {:files [{:path "src/app.clj"
                                                    :rank 7
                                                    :found? true}
                                                   {:path "src/missing.clj"
                                                    :rank 12
                                                    :found? true}]
                                           :selection {:rawCandidateFiles 4
                                                       :candidateFiles 3
                                                       :coverageFilteredCandidateFiles 1
                                                       :limit nil
                                                       :coverageSourceKinds ["code"]}}
                 :scores {:fileRecallAt5 1.0
                          :fileRecallAt10 1.0
                          :fileRecallAt20 1.0
                          :meanReciprocalRankFile 1.0
                          :noiseRatioAt20 0.5
                          :expectedEvidenceCitationRate 0.0
                          :expectedEvidenceCitations 0
                          :expectedEvidenceCitationTargets 1
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-2.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "baseline"
                         :mode "shell-only"
                         :topFiles []
                         :rawSuspectedFileCount 0}
                 :groundTruth {:changedFiles ["src/app.clj"]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :found? false}]}
                 :coverage {:declaredSourceKinds ["code" "python"]
                            :scoreableSourceKinds ["code"]
                            :scoreableFilesByKind [{:kind "code"
                                                    :files 2}]
                            :missingDeclaredSourceKinds ["python"]}
                 :scores {:fileRecallAt5 0.0
                          :fileRecallAt10 0.5
                          :fileRecallAt20 0.5
                          :meanReciprocalRankFile 0.0
                          :noiseRatioAt20 1.0
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (spit-json! out
                "suite/cases/case-1/progress.json"
                {:schema "agraph.benchmark.case-progress/v1"
                 :suite-id "suite"
                 :case-id "case-1"
                 :events [{:stage "index-project"
                           :status "started"
                           :at "2026-06-18T00:00:00Z"}
                          {:stage "index-project"
                           :status "completed"
                           :at "2026-06-18T00:00:02Z"
                           :elapsedMs 2000}
                          {:stage "context-packet"
                           :status "started"
                           :at "2026-06-18T00:00:02Z"}]})
    (spit-json! out
                "suite/cases/case-2/progress.json"
                {:schema "agraph.benchmark.case-progress/v1"
                 :suite-id "suite"
                 :case-id "case-2"
                 :events [{:stage "index-project"
                           :status "started"
                           :at "2026-06-18T00:00:00Z"}
                          {:stage "index-project"
                           :status "failed"
                           :at "2026-06-18T00:00:03Z"
                           :elapsedMs 3000}]})
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :allow-unverified-scores? true})]
      (is (= benchmark/agent-report-schema (:schema report)))
      (is (= 2 (:cases report)))
      (is (= 1 (:completed report)))
      (is (= 2 (:runs report)))
      (is (= ["case-2"] (:missing report)))
      (is (= 0.75 (get-in report [:scores :fileRecallAt10])))
      (is (= 6 (get-in report [:scores :changedFiles])))
      (is (= 4 (get-in report [:scores :scoreableChangedFiles])))
      (is (= [{:mode "all"
               :source "option"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}
              {:mode "unknown"
               :source "missing"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (:parserWorkers report)))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (:inputHints report)))
      (is (= {:emptyResultRuns 1
              :emptyResultCaseIds ["case-1"]
              :zeroCandidateRuns 0
              :zeroCandidateCaseIds []
              :coverageFilteredRuns 1
              :coverageFilteredCaseIds ["case-1"]
              :coverageFilteredCandidateFiles 1
              :missingPredictedFileRuns 0
              :missingPredictedFileCaseIds []
              :missingPredictedFiles 0
              :commandlessRuns 1
              :commandlessCaseIds ["case-1"]
              :warningRuns 1
              :warningCaseIds ["case-1"]
              :identityMismatchRuns 0
              :identityMismatchCaseIds []
              :identityMismatches 0
              :commandTelemetry {:commandCount 4
                                 :agraphCommandCount 1
                                 :searchCommandCount 1
                                 :fileReadCommandCount 1
                                 :shellCommandCount 1}
              :hintDiagnosticRows 3
              :hintDiagnosticRuns 1
              :hintDiagnosticCaseIds ["case-1"]
              :hintDiagnosticsByKind [{:kind "audit-scope-trust-boundary"
                                       :runs 1
                                       :cases 1
                                       :caseIds ["case-1"]}
                                      {:kind "coverage-filtered-candidate-files"
                                       :runs 1
                                       :cases 1
                                       :caseIds ["case-1"]}
                                      {:kind "source-skipped-files"
                                       :runs 1
                                       :cases 1
                                       :caseIds ["case-1"]}]
              :blockingHintDiagnosticRows 1
              :blockingHintDiagnosticRuns 1
              :blockingHintDiagnosticCaseIds ["case-1"]
              :blockingHintDiagnosticsByKind [{:kind "audit-scope-trust-boundary"
                                               :runs 1
                                               :cases 1
                                               :caseIds ["case-1"]}]
              :warnings 1}
             (:agentDiagnostics report)))
      (is (= {:runs 2
              :expectedEvidenceRuns 1
              :expectedEvidenceCaseIds ["case-1"]
              :expectedEvidenceTargets 1
              :expectedEvidenceCitationMetricRuns 1
              :expectedEvidenceCitationMetricCaseIds ["case-1"]
              :missingExpectedEvidenceCitationMetricRuns 0
              :missingExpectedEvidenceCitationMetricCaseIds []}
             (:expectationDiagnostics report)))
      (is (= ["missed-files-present-in-context"
              "ranked-outside-top5"
              "path-citation-gaps"
              "missing-declared-source-kinds"
              "coverage-excluded-ground-truth"
              "hint-diagnostics"
              "audit-scope-trust-boundary"
              "source-skipped-files"
              "graph-expectation-failures"
              "maintenance-preflight"
              "commandless-runs"
              "warning-runs"
              "unverified-score-artifacts"]
             (mapv :kind (:improvementSummary report))))
      (is (= [{:kind "audit-scope-trust-boundary"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (->> (:improvementSummary report)
                  (filter #(= "hint-diagnostics" (:kind %)))
                  first
                  :details)))
      (is (= [{:kind "audit-scope-trust-boundary"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (->> (:improvementSummary report)
                  (filter #(= "audit-scope-trust-boundary" (:kind %)))
                  first
                  :details)))
      (is (= [{:kind "source-skipped-files"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (->> (:improvementSummary report)
                  (filter #(= "source-skipped-files" (:kind %)))
                  first
                  :details)))
      (is (= "retrieval-gap"
             (->> (:systemImprovementSignals report)
                  (filter #(= "missed-files-present-in-context" (:kind %)))
                  first
                  :lane)))
      (is (= "extractor-gap"
             (->> (:systemImprovementSignals report)
                  (filter #(= "source-skipped-files" (:kind %)))
                  first
                  :lane)))
      (is (= "agent-protocol-gap"
             (->> (:systemImprovementSignals report)
                  (filter #(= "unverified-score-artifacts" (:kind %)))
                  first
                  :lane)))
      (is (= {:configuredRuns 1
              :passedRuns 0
              :passedCaseIds []
              :failedRuns 1
              :failedCaseIds ["case-1"]
              :notRunRuns 0
              :notRunCaseIds []}
             (:graphExpectationDiagnostics report)))
      (is (= {:runs 2
              :allScoreableFoundRuns 0
              :allScoreableFoundCaseIds []
              :missedRuns 2
              :missedCaseIds ["case-1"]
              :contextRankMissingRuns 0
              :contextRankMissingCaseIds []
              :missedButPresentInContextRuns 1
              :missedButPresentInContextCaseIds ["case-1"]
              :missedAndAbsentFromContextRuns 0
              :missedAndAbsentFromContextCaseIds []
              :rankedOutsideTop5Runs 1
              :rankedOutsideTop5CaseIds ["case-1"]
              :rankedOutsideTop10Runs 0
              :rankedOutsideTop10CaseIds []
              :rankedOutsideTop20Runs 0
              :rankedOutsideTop20CaseIds []
              :pathUncitedRuns 1
              :pathUncitedCaseIds ["case-1"]
              :pathUncitedRankedFiles [{:path "src/other.clj"
                                        :occurrences 1
                                        :runs 1
                                        :caseIds ["case-1"]
                                        :bestRank 1}
                                       {:path "src/app.clj"
                                        :occurrences 1
                                        :runs 1
                                        :caseIds ["case-1"]
                                        :bestRank 7}]
              :rankedOutsideTop5BlockingFiles [{:path "src/other.clj"
                                                :occurrences 1
                                                :runs 1
                                                :bestRank 1
                                                :metrics {:supportCount 2
                                                          :docCount 1}}]
              :rankedOutsideTop10BlockingFiles []
              :rankedOutsideTop20BlockingFiles []}
             (:localizationDiagnostics report)))
      (is (= {:currentScoreRuns 0
              :legacyScoreRuns 2
              :legacyScoreCaseIds ["case-1"]
              :obsoleteScoreSchemaRuns 0
              :obsoleteScoreSchemaCaseIds []
              :obsoleteScoreSchemas []
              :expectedScoreSchema benchmark/agent-score-schema
              :obsoleteAgentResultSchemaRuns 2
              :obsoleteAgentResultSchemaCaseIds ["case-1"]
              :obsoleteAgentResultSchemas []
              :expectedAgentResultSchema benchmark/agent-result-schema
              :obsoleteAgentResultContractRuns 2
              :obsoleteAgentResultContractCaseIds ["case-1"]
              :obsoleteAgentResultContractVersions []
              :expectedAgentResultContractVersion benchmark/agent-result-contract-version
              :staleAgentInputRuns 0
              :staleAgentInputCaseIds []
              :staleScoreRuns 0
              :staleScoreCaseIds []
              :unverifiedScoreRuns 2
              :unverifiedScoreCaseIds ["case-1"]}
             (:artifactDiagnostics report)))
      (is (= {:allowUnverifiedScores true
              :matchedRuns 2
              :includedRuns 2
              :excludedRuns 0
              :excludedCaseIds []
              :excludedUnverifiedRuns 2}
             (:artifactPolicy report)))
      (is (= {:declaredSourceKinds ["code" "python"]
              :scoreableSourceKinds ["code"]
              :scoreableFilesByKind [{:kind "code"
                                      :cases 1
                                      :scoreableFiles 2}]
              :missingDeclaredSourceKinds [{:case-id "case-1"
                                            :kind "python"}]}
             (:coverage report)))
      (is (= {:runs 2
              :missingDeclaredSourceKindRuns 2
              :missingDeclaredSourceKindCaseIds ["case-1"]
              :missingDeclaredSourceKinds [{:kind "python"
                                            :runs 2
                                            :cases 1
                                            :caseIds ["case-1"]}]
              :coverageExcludedGroundTruthRuns 1
              :coverageExcludedGroundTruthCaseIds ["case-1"]
              :coverageExcludedGroundTruthFiles 1
              :unsupportedGroundTruthRuns 0
              :unsupportedGroundTruthCaseIds []
              :unsupportedGroundTruthFiles 0}
             (:coverageDiagnostics report)))
      (is (= {:tags ["auth" "dependency" "runtime-config"]
              :casesByTag [{:tag "auth"
                            :cases 1
                            :caseIds ["case-1"]}
                           {:tag "dependency"
                            :cases 1
                            :caseIds ["case-2"]}
                           {:tag "runtime-config"
                            :cases 1
                            :caseIds ["case-1"]}]}
             (:tags report)))
      (is (= {:cases 2
              :runningCases 1
              :failedCases 1
              :elapsedMs 8000
              :stageElapsedMs [{:stage "context-packet"
                                :elapsedMs 3000}
                               {:stage "index-project"
                                :elapsedMs 5000}]
              :slowestCases [{:case-id "case-1"
                              :repo-id nil
                              :status "running"
                              :activeStage "context-packet"
                              :activeElapsedMs 3000
                              :elapsedMs 5000}
                             {:case-id "case-2"
                              :repo-id nil
                              :status "failed"
                              :elapsedMs 3000
                              :failedStage "index-project"}]}
             (:timings report)))
      (is (= "running" (get-in report [:results 0 :progress :status])))
      (is (= {:scoreableFiles ["src/app.clj" "src/missing.clj"]
              :coverageExcludedFiles [{:path "CHANGELOG.md"
                                       :kind "doc"}]
              :unsupportedGroundTruthFiles []
              :ranks [{:path "src/app.clj"
                       :rank 7
                       :found? true}
                      {:path "src/missing.clj"
                       :found? false}]
              :uncitedRankedFiles [{:path "src/other.clj"
                                    :rank 1}
                                   {:path "src/app.clj"
                                    :rank 7}]
              :pathUncitedRankedFiles [{:path "src/other.clj"
                                        :rank 1}
                                       {:path "src/app.clj"
                                        :rank 7}]
              :contextRanks [{:path "src/app.clj"
                              :rank 7
                              :found? true}
                             {:path "src/missing.clj"
                              :rank 12
                              :found? true}]
              :missedFiles [{:path "src/missing.clj"}]
              :missedFilesPresentInContext [{:path "src/missing.clj"
                                             :rank 12}]
              :missedFilesAbsentFromContext []
              :rankedOutsideTop5 [{:path "src/app.clj"
                                   :rank 7}]
              :rankedOutsideTop10 []
              :rankedOutsideTop20 []
              :rankedOutsideTop5Blockers [{:path "src/app.clj"
                                           :rank 7
                                           :blockingFileCount 1
                                           :blockingFiles [{:path "src/other.clj"
                                                            :rank 1
                                                            :metrics {:supportCount 2
                                                                      :docCount 1}}]}]
              :rankedOutsideTop10Blockers []
              :rankedOutsideTop20Blockers []}
             (get-in report [:results 0 :localization])))
      (is (= ["auth" "runtime-config"]
             (get-in report [:results 0 :tags])))
      (is (= {:mode "all"
              :source "option"}
             (get-in report [:results 0 :parserWorker])))
      (is (= "failed"
             (get-in report [:results 0 :graphExpectations :status])))
      (is (= {:rawSuspectedFiles 2
              :rankedFiles 2
              :commandCount 4
              :commandTelemetry {:commandCount 4
                                 :agraphCommandCount 1
                                 :searchCommandCount 1
                                 :fileReadCommandCount 1
                                 :shellCommandCount 1
                                 :commandless false}
              :missingPredictedFiles []
              :warnings ["agent result suspectedFiles row 1 missing evidence"]
              :warningCount 1
              :hasWarnings true
              :hintDiagnostics [{:kind "coverage-filtered-candidate-files"
                                 :severity "info"
                                 :message "Declared source coverage filtered candidate files out of the agent shortlist."
                                 :filteredCandidateFiles 1}
                                {:kind "source-skipped-files"
                                 :severity "info"
                                 :message "Indexed source coverage contains skipped files; inspect sourceCoverage skipped breakdowns before treating missing facts as absent."
                                 :skippedFiles 2}
                                {:kind "audit-scope-trust-boundary"
                                 :severity "warning"
                                 :message "Audit scope contains skipped files, extractor diagnostics, or unclassified extractor rows."
                                 :scope "unclassified-extractor"
                                 :supportedFiles 1
                                 :skippedFiles 1
                                 :diagnostics 1
                                 :facts 2}]
              :hintDiagnosticCount 3
              :hasHintDiagnostics true
              :identityWarnings []
              :hasIdentityMismatch false
              :emptyResult false
              :commandless false
              :noRawSuspectedFiles false
              :selection {:rawCandidateFiles 3
                          :candidateFiles 2
                          :coverageFilteredCandidateFiles 1
                          :limit 20
                          :coverageSourceKinds ["code"]}
              :zeroCandidateFiles false
              :coverageFilteredCandidateFiles 1}
             (get-in report [:results 0 :agentOutput])))
      (is (= "legacy"
             (get-in report [:results 0 :artifact :fingerprintStatus])))
      (is (= #{"running" "failed"}
             (set (map :status (:caseProgress report)))))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (get-in (first (:byMode report)) [:inputHints])))
      (is (= {:runs 1
              :expectedEvidenceRuns 1
              :expectedEvidenceCaseIds ["case-1"]
              :expectedEvidenceTargets 1
              :expectedEvidenceCitationMetricRuns 1
              :expectedEvidenceCitationMetricCaseIds ["case-1"]
              :missingExpectedEvidenceCitationMetricRuns 0
              :missingExpectedEvidenceCitationMetricCaseIds []}
             (get-in (first (:byMode report)) [:expectationDiagnostics])))
      (is (= {:runs 1
              :missingDeclaredSourceKindRuns 1
              :missingDeclaredSourceKindCaseIds ["case-1"]
              :missingDeclaredSourceKinds [{:kind "python"
                                            :runs 1
                                            :cases 1
                                            :caseIds ["case-1"]}]
              :coverageExcludedGroundTruthRuns 1
              :coverageExcludedGroundTruthCaseIds ["case-1"]
              :coverageExcludedGroundTruthFiles 1
              :unsupportedGroundTruthRuns 0
              :unsupportedGroundTruthCaseIds []
              :unsupportedGroundTruthFiles 0}
             (get-in (first (:byMode report)) [:coverageDiagnostics])))
      (is (= ["missed-files-present-in-context"
              "ranked-outside-top5"
              "path-citation-gaps"
              "missing-declared-source-kinds"
              "coverage-excluded-ground-truth"
              "hint-diagnostics"
              "audit-scope-trust-boundary"
              "source-skipped-files"
              "graph-expectation-failures"
              "maintenance-preflight"
              "warning-runs"
              "unverified-score-artifacts"]
             (mapv :kind
                   (get-in (first (:byMode report)) [:improvementSummary]))))
      (is (= #{"agraph" "shell-only"} (set (map :key (:byMode report)))))
      (is (= ["baseline" "codex"] (mapv :key (:byAgent report))))
      (is (= ["all/option" "unknown/missing"]
             (mapv :key (:byParserWorker report))))
      (is (= 1.0
             (get-in (first (:byParserWorker report))
                     [:scores :fileRecallAt10])))
      (is (= ["auth" "runtime-config"] (mapv :key (:byTag report))))
      (is (= 1
             (get-in (first (:byTag report))
                     [:graphExpectationDiagnostics :failedRuns]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :mode "agraph"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= 1.0 (get-in report [:scores :fileRecallAt10])))
      (is (= ["agraph"] (mapv :key (:byMode report)))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :parser-worker "all"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= [{:mode "all"
               :source "option"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (:parserWorkers report)))
      (is (= {:mode "all"
              :source "option"}
             (get-in report [:results 0 :parserWorker]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :parser-worker "java"
                                                      :allow-unverified-scores? true})]
      (is (= 0 (:runs report)))
      (is (= 0 (:completed report)))
      (is (= [] (:parserWorkers report)))
      (is (= ["case-1" "case-2"] (:missing report))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :agent-id "baseline"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= "baseline" (get-in report [:results 0 :agent :agentId])))
      (is (= ["baseline"] (mapv :key (:byAgent report))))
      (is (= 0.5 (get-in report [:scores :fileRecallAt10]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"})]
      (is (= 0 (:runs report)))
      (is (= 0 (:completed report)))
      (is (= ["case-1" "case-2"] (:missing report)))
      (is (= {:allowUnverifiedScores false
              :matchedRuns 2
              :includedRuns 0
              :excludedRuns 2
              :excludedCaseIds ["case-1"]
              :excludedUnverifiedRuns 2}
             (:artifactPolicy report)))
      (is (= [{:metric "runs"
               :operator ">"
               :expected 0
               :actual 0
               :matchedRuns 2
               :excludedRuns 2
               :excludedUnverifiedRuns 2
               :excludedCaseIds ["case-1"]
               :message "No current agent score artifacts matched the selected suite, case, and mode. Matching artifacts were excluded because their score schema, agent result schema, or case fingerprint is stale; regenerate the benchmark scores or pass --allow-unverified-scores for exploratory reporting."}]
             (:failures
              (benchmark/check-agent-report report {:allow-missing? true})))))))

(deftest improvement-summary-flags-missing-agraph-context-ranks
  (let [results [{:case-id "case-1"
                  :agent {:mode "agraph"
                          :topFiles []}
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}
                  :groundTruthRanks {:files [{:path "src/app.clj"
                                              :found? false}]}}
                 {:case-id "case-2"
                  :agent {:mode "shell-only"
                          :topFiles []}
                  :groundTruth {:changedFiles ["src/other.clj"]
                                :unsupportedGroundTruthFiles []}
                  :groundTruthRanks {:files [{:path "src/other.clj"
                                              :found? false}]}}]
        diagnostics (#'benchmark-report/aggregate-localization-diagnostics
                     results)
        summary (#'benchmark-report/report-improvement-summary
                 {:localizationDiagnostics diagnostics})]
    (is (= 1 (:contextRankMissingRuns diagnostics)))
    (is (= ["case-1"] (:contextRankMissingCaseIds diagnostics)))
    (is (= {:kind "missing-context-ranks"
            :area "benchmark-hygiene"
            :runs 1
            :caseIds ["case-1"]
            :message "AGraph-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."}
           (->> summary
                (filter #(= "missing-context-ranks" (:kind %)))
                first)))))

(deftest agent-output-diagnostic-normalizes-command-telemetry
  (let [diagnostic (#'benchmark/agent-output-diagnostic
                    {:agent {:commands ["rg broken src"
                                        "git grep broken -- src"
                                        "env FOO=bar sed -n '1,20p' src/app.clj"
                                        "bb explore create 'broken app' --project fixture"
                                        "npm test"]
                             :topFiles [{:path "src/app.clj"}]}})]
    (is (= {:commandCount 5
            :agraphCommandCount 1
            :searchCommandCount 2
            :fileReadCommandCount 1
            :shellCommandCount 1
            :commandless false}
           (:commandTelemetry diagnostic)))))

(deftest agent-output-diagnostic-counts-compound-command-segments
  (let [diagnostic (#'benchmark/agent-output-diagnostic
                    {:agent {:commands ["cat src/app.clj | rg broken"
                                        "sed -n '1,20p' src/app.clj; npm test"]
                             :topFiles [{:path "src/app.clj"}]}})]
    (is (= {:commandCount 2
            :agraphCommandCount 0
            :searchCommandCount 1
            :fileReadCommandCount 1
            :shellCommandCount 0
            :commandless false
            :segmentCount 4
            :agraphSegmentCount 0
            :searchSegmentCount 1
            :fileReadSegmentCount 2
            :shellSegmentCount 1}
           (:commandTelemetry diagnostic)))))

(deftest aggregate-command-telemetry-falls-back-to-command-counts-for-single-segment-runs
  (let [summary (benchmark-command-telemetry/aggregate-command-telemetry
                 [{:commandTelemetry {:commandCount 1
                                      :agraphCommandCount 1
                                      :searchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}
                  {:commandTelemetry {:commandCount 1
                                      :agraphCommandCount 0
                                      :searchCommandCount 1
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0
                                      :segmentCount 2
                                      :agraphSegmentCount 0
                                      :searchSegmentCount 1
                                      :fileReadSegmentCount 1
                                      :shellSegmentCount 0}}])]
    (is (= {:commandCount 2
            :agraphCommandCount 1
            :searchCommandCount 1
            :fileReadCommandCount 0
            :shellCommandCount 0
            :segmentCount 3
            :agraphSegmentCount 1
            :searchSegmentCount 1
            :fileReadSegmentCount 1
            :shellSegmentCount 0}
           summary))))

(deftest reports-problem-class-summaries
  (let [out (temp-dir "agraph-agent-report-problem-classes")
        suite {:id "suite"
               :cases [{:id "arch-runtime"
                        :tags [:problem-architecture
                               :architecture-runtime-boundary]}
                       {:id "arch-deps"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs"
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "localization"
                        :tags [:problem-localization]}]}
        write-score! (fn [case-id tags recall]
                       (spit-json!
                        out
                        (str "suite/cases/" case-id "/agent-scores/run.score.json")
                        {:schema benchmark/agent-score-schema
                         :suite-id "suite"
                         :case-id case-id
                         :repo-id "repo"
                         :tags tags
                         :maintenancePreflight passing-maintenance-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "agraph"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["candidate-file"]}]
                                 :commands ["agraph explore"]}
                         :groundTruth {:changedFiles [(str case-id ".clj")]
                                       :scoreableFiles [(str case-id ".clj")]
                                       :unsupportedGroundTruthFiles []}
                         :groundTruthRanks {:files [{:path (str case-id ".clj")
                                                     :rank 1
                                                     :found? true}]}
                         :scores {:fileRecallAt5 recall
                                  :fileRecallAt10 recall
                                  :fileRecallAt20 recall
                                  :meanReciprocalRankFile recall
                                  :noiseRatioAt20 (- 1.0 recall)
                                  :evidenceCitationRate 1.0
                                  :pathEvidenceCitationRate 1.0
                                  :expectedEvidenceCitationRate 1.0
                                  :expectedEvidenceCitations 1
                                  :expectedEvidenceCitationTargets 1
                                  :changedFiles 1
                                  :scoreableChangedFiles 1
                                  :unsupportedGroundTruthFiles 0}}))]
    (write-score! "arch-runtime"
                  ["problem-architecture" "architecture-runtime-boundary"]
                  1.0)
    (write-score! "arch-deps"
                  ["problem-architecture" "architecture-dependency-flow"]
                  0.5)
    (write-score! "audit-docs"
                  ["problem-architecture" "audit-scope-docs"]
                  0.75)
    (write-score! "localization"
                  ["problem-localization"]
                  0.25)
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})
          problem-classes (get-in report [:problemClasses :classes])
          architecture-classes (get-in report [:problemClasses :architectureClasses])]
      (is (= 2 (get-in report [:problemClasses :minimumCasesForClassClaim])))
      (is (= [{:key "problem-architecture"
               :cases 3
               :runs 3
               :claimStatus "measured"
               :minimumCases 2}
              {:key "problem-localization"
               :cases 1
               :runs 1
               :claimStatus "insufficient-cases"
               :minimumCases 2}]
             (mapv #(select-keys % [:key
                                    :cases
                                    :runs
                                    :claimStatus
                                    :minimumCases])
                   problem-classes)))
      (is (= 0.75
             (get-in (first problem-classes) [:scores :fileRecallAt10])))
      (is (= [{:key "architecture-dependency-flow"
               :cases 1
               :runs 1
               :claimStatus "insufficient-cases"
               :minimumCases 2}
              {:key "architecture-runtime-boundary"
               :cases 1
               :runs 1
               :claimStatus "insufficient-cases"
               :minimumCases 2}
              {:key "audit-scope-docs"
               :cases 1
               :runs 1
               :claimStatus "insufficient-cases"
               :minimumCases 2}]
             (mapv #(select-keys % [:key
                                    :cases
                                    :runs
                                    :claimStatus
                                    :minimumCases])
                   architecture-classes)))
      (is (= {:status "not-supported"
              :broadArchitectureClaimSupported false
              :measuredProblemClassTags ["problem-architecture"]
              :measuredArchitectureClassTags []
              :requirements {:completedCases true
                             :hasRuns true
                             :measuredProblemClasses true
                             :measuredArchitectureClasses false
                             :evidenceCitationMetrics true
                             :expectedEvidenceCitationMetrics true
                             :commandTelemetry true
                             :maintenancePreflight true}
              :warnings ["No measured architecture-class groups; architecture tags are present only below the class-claim threshold or absent."]}
             (:claimReadiness report))))))
(deftest agent-report-claim-readiness-recognizes-measured-architecture-coverage
  (let [out (temp-dir "agraph-agent-report-claim-readiness")
        suite {:id "suite"
               :cases [{:id "arch-deps-1"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "arch-deps-2"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs-1"
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "audit-docs-2"
                        :tags [:problem-architecture
                               :audit-scope-docs]}]}
        write-score! (fn [case-id tags recall]
                       (spit-json!
                        out
                        (str "suite/cases/" case-id "/agent-scores/run.score.json")
                        {:schema benchmark/agent-score-schema
                         :suite-id "suite"
                         :case-id case-id
                         :repo-id "repo"
                         :tags tags
                         :maintenancePreflight passing-maintenance-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "agraph"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["architecture-evidence"]}]
                                 :commands ["bb ask architecture --project fixture"]}
                         :groundTruth {:changedFiles [(str case-id ".clj")]
                                       :scoreableFiles [(str case-id ".clj")]
                                       :unsupportedGroundTruthFiles []}
                         :groundTruthRanks {:files [{:path (str case-id ".clj")
                                                     :rank 1
                                                     :found? true}]}
                         :scores {:fileRecallAt5 recall
                                  :fileRecallAt10 recall
                                  :fileRecallAt20 recall
                                  :meanReciprocalRankFile recall
                                  :noiseRatioAt20 (- 1.0 recall)
                                  :evidenceCitationRate 1.0
                                  :pathEvidenceCitationRate 1.0
                                  :expectedEvidenceCitationRate 1.0
                                  :expectedEvidenceCitations 1
                                  :expectedEvidenceCitationTargets 1
                                  :changedFiles 1
                                  :scoreableChangedFiles 1
                                  :unsupportedGroundTruthFiles 0}}))]
    (write-score! "arch-deps-1"
                  ["problem-architecture" "architecture-dependency-flow"]
                  1.0)
    (write-score! "arch-deps-2"
                  ["problem-architecture" "architecture-dependency-flow"]
                  0.75)
    (write-score! "audit-docs-1"
                  ["problem-architecture" "audit-scope-docs"]
                  1.0)
    (write-score! "audit-docs-2"
                  ["problem-architecture" "audit-scope-docs"]
                  0.75)
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "supported" (get-in report [:claimReadiness :status])))
      (is (= true
             (get-in report
                     [:claimReadiness :broadArchitectureClaimSupported])))
      (is (= ["problem-architecture"]
             (get-in report [:claimReadiness :measuredProblemClassTags])))
      (is (= ["architecture-dependency-flow" "audit-scope-docs"]
             (get-in report [:claimReadiness :measuredArchitectureClassTags])))
      (is (= []
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-claim-readiness-requires-maintenance-preflight
  (let [out (temp-dir "agraph-agent-report-maintenance-preflight")
        suite {:id "suite"
               :cases [{:id "arch-deps-1"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "arch-deps-2"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs-1"
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "audit-docs-2"
                        :tags [:problem-architecture
                               :audit-scope-docs]}]}
        write-score! (fn [case-id tags maintenance-preflight]
                       (spit-json!
                        out
                        (str "suite/cases/" case-id "/agent-scores/run.score.json")
                        {:schema benchmark/agent-score-schema
                         :suite-id "suite"
                         :case-id case-id
                         :repo-id "repo"
                         :tags tags
                         :maintenancePreflight maintenance-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "agraph"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["architecture-evidence"]}]
                                 :commands ["bb ask architecture --project fixture"]}
                         :groundTruth {:changedFiles [(str case-id ".clj")]
                                       :scoreableFiles [(str case-id ".clj")]
                                       :unsupportedGroundTruthFiles []}
                         :groundTruthRanks {:files [{:path (str case-id ".clj")
                                                     :rank 1
                                                     :found? true}]}
                         :scores {:fileRecallAt5 1.0
                                  :fileRecallAt10 1.0
                                  :fileRecallAt20 1.0
                                  :meanReciprocalRankFile 1.0
                                  :noiseRatioAt20 0.0
                                  :evidenceCitationRate 1.0
                                  :pathEvidenceCitationRate 1.0
                                  :expectedEvidenceCitationRate 1.0
                                  :expectedEvidenceCitations 1
                                  :expectedEvidenceCitationTargets 1
                                  :changedFiles 1
                                  :scoreableChangedFiles 1
                                  :unsupportedGroundTruthFiles 0}}))]
    (write-score! "arch-deps-1"
                  ["problem-architecture" "architecture-dependency-flow"]
                  failing-sync-maintenance-preflight)
    (write-score! "arch-deps-2"
                  ["problem-architecture" "architecture-dependency-flow"]
                  passing-maintenance-preflight)
    (write-score! "audit-docs-1"
                  ["problem-architecture" "audit-scope-docs"]
                  passing-maintenance-preflight)
    (write-score! "audit-docs-2"
                  ["problem-architecture" "audit-scope-docs"]
                  passing-maintenance-preflight)
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :requirements :maintenancePreflight])))
      (is (= "failed" (get-in report [:maintenancePreflightDiagnostics :status])))
      (is (= ["arch-deps-1"]
             (get-in report [:maintenancePreflightDiagnostics :failedCaseIds])))
      (is (= ["AGraph maintenance preflight did not pass; index, inference, graph expectations, hint diagnostics, and sync/check-equivalent status must pass before making maintained-graph claims."]
             (get-in report [:claimReadiness :warnings])))
      (is (= {:kind "sync-check-gaps"
              :area "graph-maintenance"
              :runs 1
              :caseIds ["arch-deps-1"]
              :message "AGraph sync/check-equivalent validation gaps block maintained-graph claims."
              :details [{:check "syncCheck"
                         :status "failed"
                         :failedRuns 1
                         :failedCaseIds ["arch-deps-1"]
                         :blockingReasons [{:plane "dependencies"
                                            :status "weak"
                                            :reason "candidate-unresolved"
                                            :runs 1
                                            :caseIds ["arch-deps-1"]
                                            :message "Source import candidates were extracted, but some did not resolve to package facts."
                                            :count 2}]}]}
             (->> (:improvementSummary report)
                  (filter #(= "sync-check-gaps" (:kind %)))
                  first))))))

(deftest agent-report-claim-readiness-requires-scored-expected-evidence
  (let [out (temp-dir "agraph-agent-report-expected-evidence-readiness")
        suite {:id "suite"
               :cases [{:id "arch-deps-1"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "arch-deps-2"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs-1"
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "audit-docs-2"
                        :tags [:problem-architecture
                               :audit-scope-docs]}]}
        write-score! (fn [case-id tags]
                       (spit-json!
                        out
                        (str "suite/cases/" case-id "/agent-scores/run.score.json")
                        {:schema benchmark/agent-score-schema
                         :suite-id "suite"
                         :case-id case-id
                         :repo-id "repo"
                         :tags tags
                         :maintenancePreflight passing-maintenance-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "agraph"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["architecture-evidence"]}]
                                 :commands ["bb ask architecture --project fixture"]}
                         :groundTruth {:changedFiles [(str case-id ".clj")]
                                       :scoreableFiles [(str case-id ".clj")]
                                       :unsupportedGroundTruthFiles []}
                         :groundTruthRanks {:files [{:path (str case-id ".clj")
                                                     :rank 1
                                                     :found? true}]}
                         :scores {:fileRecallAt5 1.0
                                  :fileRecallAt10 1.0
                                  :fileRecallAt20 1.0
                                  :meanReciprocalRankFile 1.0
                                  :noiseRatioAt20 0.0
                                  :evidenceCitationRate 1.0
                                  :pathEvidenceCitationRate 1.0
                                  :changedFiles 1
                                  :scoreableChangedFiles 1
                                  :unsupportedGroundTruthFiles 0}}))]
    (write-score! "arch-deps-1"
                  ["problem-architecture" "architecture-dependency-flow"])
    (write-score! "arch-deps-2"
                  ["problem-architecture" "architecture-dependency-flow"])
    (write-score! "audit-docs-1"
                  ["problem-architecture" "audit-scope-docs"])
    (write-score! "audit-docs-2"
                  ["problem-architecture" "audit-scope-docs"])
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :expectedEvidenceCitationMetrics])))
      (is (= ["Expected-evidence citation metrics are unavailable or incomplete; non-code help quality is unproven."]
             (get-in report [:claimReadiness :warnings])))
      (is (= {:kind "expected-evidence-citation-metric-gaps"
              :area "benchmark-hygiene"
              :runs 4
              :caseIds ["arch-deps-1"
                        "arch-deps-2"
                        "audit-docs-1"
                        "audit-docs-2"]
              :message "Benchmark cases declared expected evidence without scored expected-evidence citation metrics."}
             (->> (:improvementSummary report)
                  (filter #(= "expected-evidence-citation-metric-gaps"
                              (:kind %)))
                  first))))))
(deftest reports-exclude-obsolete-agent-score-schema
  (let [out (temp-dir "agraph-agent-report-obsolete-score-schema")
        case {:id "case-1"
              :repo-id "repo"
              :base-sha "base"
              :fix-sha "fix"
              :issue {:title "broken app"}
              :ground-truth {:changedFiles ["src/app.clj"]}}
        suite {:id "suite"
               :cases [case]}
        fingerprint (#'benchmark/case-fingerprint suite case)]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema "agraph.benchmark.agent-score/v1"
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :caseFingerprint fingerprint
                 :agent {:agentId "codex"
                         :mode "agraph"
                         :topFiles [{:path "src/app.clj"
                                     :rank 1}]}
                 :groundTruth {:changedFiles ["src/app.clj"]}
                 :scores {:fileRecallAt5 1.0
                          :fileRecallAt10 1.0
                          :fileRecallAt20 1.0
                          :meanReciprocalRankFile 1.0
                          :noiseRatioAt20 0.0
                          :changedFiles 1
                          :scoreableChangedFiles 1}})
    (let [report (benchmark/report-agent-suite suite {:out out})]
      (is (= 0 (:runs report)))
      (is (= ["case-1"] (:missing report)))
      (is (= {:currentScoreRuns 0
              :legacyScoreRuns 1
              :legacyScoreCaseIds ["case-1"]
              :obsoleteScoreSchemaRuns 1
              :obsoleteScoreSchemaCaseIds ["case-1"]
              :obsoleteScoreSchemas ["agraph.benchmark.agent-score/v1"]
              :expectedScoreSchema benchmark/agent-score-schema
              :obsoleteAgentResultSchemaRuns 1
              :obsoleteAgentResultSchemaCaseIds ["case-1"]
              :obsoleteAgentResultSchemas []
              :expectedAgentResultSchema benchmark/agent-result-schema
              :obsoleteAgentResultContractRuns 1
              :obsoleteAgentResultContractCaseIds ["case-1"]
              :obsoleteAgentResultContractVersions []
              :expectedAgentResultContractVersion benchmark/agent-result-contract-version
              :staleAgentInputRuns 0
              :staleAgentInputCaseIds []
              :staleScoreRuns 0
              :staleScoreCaseIds []
              :unverifiedScoreRuns 1
              :unverifiedScoreCaseIds ["case-1"]}
             (:artifactDiagnostics report)))
      (is (= {:allowUnverifiedScores false
              :matchedRuns 1
              :includedRuns 0
              :excludedRuns 1
              :excludedCaseIds ["case-1"]
              :excludedUnverifiedRuns 1}
             (:artifactPolicy report))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= "legacy"
             (get-in report [:results 0 :artifact :fingerprintStatus])))
      (is (= "legacy"
             (get-in report [:results 0 :artifact :scoreSchemaStatus])))
      (is (= "agraph.benchmark.agent-score/v1"
             (get-in report [:results 0 :artifact :scoreSchema])))
      (is (= benchmark/agent-score-schema
             (get-in report [:results 0 :artifact :expectedScoreSchema]))))))

(deftest improvement-summary-flags-missing-context-ranks
  (let [report {:localizationDiagnostics {:contextRankMissingRuns 2
                                          :contextRankMissingCaseIds ["case-a"
                                                                      "case-b"]}}
        row (->> (#'benchmark-report/report-improvement-summary report)
                 (filter #(= "missing-context-ranks" (:kind %)))
                 first)]
    (is (= {:kind "missing-context-ranks"
            :area "benchmark-hygiene"
            :runs 2
            :caseIds ["case-a" "case-b"]
            :message "AGraph-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."}
           row))))

(deftest checks-agent-report-thresholds
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 2
                :completed 1
                :runs 1
                :missing ["case-2"]
                :scores {:fileRecallAt5 0.5
                         :fileRecallAt10 0.75
                         :fileRecallAt20 1.0
                         :meanReciprocalRankFile 0.5
                         :noiseRatioAt20 0.75
                         :evidenceCitationRate 0.25
                         :pathEvidenceCitationRate 0.0
                         :unsupportedGroundTruthFiles 1}
                :parserWorkers [{:mode "all"
                                 :source "option"
                                 :runs 1}
                                {:mode "unknown"
                                 :source "missing"
                                 :runs 1}]
                :inputHints {:inputHintedCases 1}
                :agentDiagnostics {:emptyResultRuns 1
                                   :emptyResultCaseIds ["case-1"]
                                   :missingPredictedFileRuns 1
                                   :missingPredictedFileCaseIds ["case-1"]
                                   :missingPredictedFiles 2
                                   :commandlessRuns 1
                                   :commandlessCaseIds ["case-1"]
                                   :warningRuns 1
                                   :warningCaseIds ["case-1"]
                                   :hintDiagnosticRuns 1
                                   :hintDiagnosticCaseIds ["case-1"]
                                   :hintDiagnosticsByKind [{:kind "coverage-filtered-candidate-files"
                                                            :runs 1
                                                            :cases 1
                                                            :caseIds ["case-1"]}]
                                   :identityMismatchRuns 1
                                   :identityMismatchCaseIds ["case-1"]
                                   :identityMismatches 2}
                :artifactDiagnostics {:unverifiedScoreRuns 1
                                      :unverifiedScoreCaseIds ["case-1"]}
                :coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                      :missingDeclaredSourceKindCaseIds ["case-1"]
                                      :missingDeclaredSourceKinds [{:kind "python"
                                                                    :runs 1
                                                                    :cases 1
                                                                    :caseIds ["case-1"]}]}
                :improvementSummary [{:kind "source-skipped-files"
                                      :area "source-coverage-quality"
                                      :runs 1
                                      :caseIds ["case-1"]}]
                :graphExpectationDiagnostics {:configuredRuns 1
                                              :passedRuns 0
                                              :passedCaseIds []
                                              :failedRuns 1
                                              :failedCaseIds ["case-1"]
                                              :notRunRuns 0
                                              :notRunCaseIds []}
                :localizationDiagnostics {:missedRuns 1
                                          :missedCaseIds ["case-1"]
                                          :contextRankMissingRuns 1
                                          :contextRankMissingCaseIds ["case-1"]
                                          :missedButPresentInContextRuns 1
                                          :missedButPresentInContextCaseIds ["case-1"]
                                          :missedAndAbsentFromContextRuns 1
                                          :missedAndAbsentFromContextCaseIds ["case-1"]
                                          :rankedOutsideTop5Runs 1
                                          :rankedOutsideTop5CaseIds ["case-1"]
                                          :rankedOutsideTop10Runs 1
                                          :rankedOutsideTop10CaseIds ["case-1"]
                                          :rankedOutsideTop20Runs 1
                                          :rankedOutsideTop20CaseIds ["case-1"]}
                :problemClasses {:minimumCasesForClassClaim 2
                                 :classes [{:key "problem-architecture"
                                            :cases 1
                                            :runs 1
                                            :claimStatus "insufficient-cases"
                                            :minimumCases 2}]
                                 :architectureClasses []}
                :caseProgress [{:case-id "case-2"
                                :repo-id "repo"
                                :status "running"
                                :activeStage "context-packet"
                                :activeElapsedMs 1500}]
                :results [{:case-id "case-1"
                           :parserWorker {:mode "all"
                                          :source "option"}
                           :agent {:agentId "codex"
                                   :mode "agraph"}
                           :agentOutput {:missingPredictedFiles ["src/ghost.clj"
                                                                 "src/gone.clj"]
                                         :commandless true
                                         :warnings ["shape warning"]}
                           :artifact {:fingerprintStatus "stale"
                                      :caseFingerprint "sha256:old"
                                      :expectedCaseFingerprint "sha256:new"}
                           :progress {:case-id "case-1"
                                      :status "completed"
                                      :completedStages 4}
                           :graphExpectations {:status "failed"
                                               :summary {:expectedEvidence 1
                                                         :foundEvidence 0}}
                           :localization {:missedFiles [{:path "src/missing.clj"}]
                                          :missedFilesPresentInContext [{:path "src/missing.clj"
                                                                         :rank 12}]
                                          :missedFilesAbsentFromContext []}
                           :scores {:fileRecallAt5 0.5
                                    :fileRecallAt10 0.75
                                    :fileRecallAt20 1.0
                                    :meanReciprocalRankFile 0.5
                                    :noiseRatioAt20 0.75
                                    :evidenceCitationRate 0.25
                                    :pathEvidenceCitationRate 0.0}}]}
        failed (benchmark/check-agent-report
                report
                {:min-cases 3
                 :min-runs 2
                 :min-file-recall-at-10 0.9
                 :min-mrr 0.8
                 :max-noise-at-20 0.5
                 :min-evidence-citation-rate 0.9
                 :min-path-evidence-citation-rate 0.9
                 :min-case-file-recall-at-10 0.9
                 :min-case-mrr 0.8
                 :min-case-evidence-citation-rate 0.9
                 :min-case-path-evidence-citation-rate 0.9
                 :max-case-noise-at-20 0.5
                 :max-input-hinted-cases 0
                 :max-unsupported-ground-truth-files 0
                 :max-empty-result-runs 0
                 :max-missing-predicted-file-runs 0
                 :max-commandless-runs 0
                 :max-warning-runs 0
                 :max-hint-diagnostic-runs 0
                 :max-identity-mismatch-runs 0
                 :max-unverified-score-runs 0
                 :max-graph-expectation-failures 0
                 :max-missing-declared-source-kind-runs 0
                 :max-missed-runs 0
                 :max-context-rank-missing-runs 0
                 :max-missed-but-present-in-context-runs 0
                 :max-missed-and-absent-from-context-runs 0
                 :max-ranked-outside-top-5-runs 0
                 :max-ranked-outside-top-10-runs 0
                 :max-ranked-outside-top-20-runs 0
                 :max-improvement-target-runs 0
                 :max-improvement-target-kind-runs {"source-skipped-files" 0}
                 :max-active-stage-ms 1000
                 :max-parser-worker-profiles 1
                 :min-measured-problem-classes 1
                 :min-measured-architecture-classes 1
                 :require-parser-worker "all"})
        passed (benchmark/check-agent-report
                (assoc report
                       :completed 2
                       :missing []
                       :parserWorkers [{:mode "all"
                                        :source "option"
                                        :runs 1}]
                       :localizationDiagnostics {:missedRuns 1
                                                 :missedCaseIds ["case-1"]
                                                 :contextRankMissingRuns 0
                                                 :contextRankMissingCaseIds []
                                                 :missedButPresentInContextRuns 1
                                                 :missedButPresentInContextCaseIds ["case-1"]
                                                 :missedAndAbsentFromContextRuns 0
                                                 :missedAndAbsentFromContextCaseIds []
                                                 :rankedOutsideTop5Runs 1
                                                 :rankedOutsideTop5CaseIds ["case-1"]
                                                 :rankedOutsideTop10Runs 1
                                                 :rankedOutsideTop10CaseIds ["case-1"]
                                                 :rankedOutsideTop20Runs 1
                                                 :rankedOutsideTop20CaseIds ["case-1"]}
                       :problemClasses {:minimumCasesForClassClaim 2
                                        :classes [{:key "problem-architecture"
                                                   :cases 2
                                                   :runs 2
                                                   :claimStatus "measured"
                                                   :minimumCases 2}]
                                        :architectureClasses [{:key "architecture-dependency-flow"
                                                               :cases 2
                                                               :runs 2
                                                               :claimStatus "measured"
                                                               :minimumCases 2}]})
                {:allow-missing? true
                 :min-cases 2
                 :min-runs 1
                 :min-file-recall-at-10 0.75
                 :min-mrr 0.5
                 :max-noise-at-20 0.75
                 :min-evidence-citation-rate 0.25
                 :min-path-evidence-citation-rate 0.0
                 :min-case-file-recall-at-10 0.75
                 :min-case-mrr 0.5
                 :min-case-evidence-citation-rate 0.25
                 :min-case-path-evidence-citation-rate 0.0
                 :max-case-noise-at-20 0.75
                 :max-input-hinted-cases 1
                 :max-unsupported-ground-truth-files 1
                 :max-empty-result-runs 1
                 :max-missing-predicted-file-runs 1
                 :max-commandless-runs 1
                 :max-warning-runs 1
                 :max-hint-diagnostic-runs 1
                 :max-identity-mismatch-runs 1
                 :max-unverified-score-runs 1
                 :max-graph-expectation-failures 1
                 :max-missing-declared-source-kind-runs 1
                 :max-missed-runs 1
                 :max-context-rank-missing-runs 0
                 :max-missed-but-present-in-context-runs 1
                 :max-missed-and-absent-from-context-runs 0
                 :max-ranked-outside-top-5-runs 1
                 :max-ranked-outside-top-10-runs 1
                 :max-ranked-outside-top-20-runs 1
                 :max-active-stage-ms 1500
                 :max-parser-worker-profiles 1
                 :min-measured-problem-classes 1
                 :min-measured-architecture-classes 1
                 :require-parser-worker "all"})]
    (is (= benchmark/agent-check-schema (:schema failed)))
    (is (= "failed" (:status failed)))
    (is (= #{"completed"
             "cases"
             "runs"
             "fileRecallAt10"
             "meanReciprocalRankFile"
             "noiseRatioAt20"
             "evidenceCitationRate"
             "pathEvidenceCitationRate"
             "case.fileRecallAt10"
             "case.meanReciprocalRankFile"
             "case.evidenceCitationRate"
             "case.pathEvidenceCitationRate"
             "case.noiseRatioAt20"
             "inputHintedCases"
             "unsupportedGroundTruthFiles"
             "emptyResultRuns"
             "missingPredictedFileRuns"
             "commandlessRuns"
             "warningRuns"
             "hintDiagnosticRuns"
             "identityMismatchRuns"
             "unverifiedScoreRuns"
             "graphExpectationFailures"
             "missingDeclaredSourceKindRuns"
             "case.graphExpectations"
             "missedRuns"
             "contextRankMissingRuns"
             "missedButPresentInContextRuns"
             "missedAndAbsentFromContextRuns"
             "rankedOutsideTop5Runs"
             "rankedOutsideTop10Runs"
             "rankedOutsideTop20Runs"
             "improvementTargetRuns"
             "improvementTargetRuns.source-skipped-files"
             "parserWorkerProfiles"
             "parserWorker"
             "activeStageElapsedMs"
             "measuredProblemClasses"
             "measuredArchitectureClasses"}
           (set (map :metric (:failures failed)))))
    (is (= {:case-id "case-1"
            :agentId "codex"
            :mode "agraph"}
           (select-keys (first (filter #(= "case.fileRecallAt10" (:metric %))
                                       (:failures failed)))
                        [:case-id :agentId :mode])))
    (is (= ["case-1" "case-2"]
           (mapv :case-id (:caseDiagnostics failed))))
    (is (= "failed" (get-in failed [:caseDiagnostics 0 :status])))
    (let [case-1-failures (set (map :metric
                                    (get-in failed
                                            [:caseDiagnostics 0 :failures])))]
      (is (every? case-1-failures
                  ["case.fileRecallAt10"
                   "case.meanReciprocalRankFile"
                   "case.evidenceCitationRate"
                   "case.pathEvidenceCitationRate"
                   "case.noiseRatioAt20"
                   "case.graphExpectations"
                   "missingPredictedFileRuns"
                   "hintDiagnosticRuns"
                   "contextRankMissingRuns"
                   "missedButPresentInContextRuns"
                   "missedAndAbsentFromContextRuns"
                   "improvementTargetRuns"
                   "improvementTargetRuns.source-skipped-files"]))
      (is (not (contains? case-1-failures "activeStageElapsedMs"))))
    (is (= {:mode "all"
            :source "option"}
           (get-in failed [:caseDiagnostics 0 :parserWorker])))
    (is (= {:missingPredictedFiles ["src/ghost.clj" "src/gone.clj"]
            :commandless true
            :warnings ["shape warning"]}
           (get-in failed [:caseDiagnostics 0 :agentOutput])))
    (is (= {:fingerprintStatus "stale"
            :caseFingerprint "sha256:old"
            :expectedCaseFingerprint "sha256:new"}
           (get-in failed [:caseDiagnostics 0 :artifact])))
    (is (= {:case-id "case-1"
            :status "completed"
            :completedStages 4}
           (get-in failed [:caseDiagnostics 0 :progress])))
    (is (= {:missedFiles [{:path "src/missing.clj"}]
            :missedFilesPresentInContext [{:path "src/missing.clj"
                                           :rank 12}]
            :missedFilesAbsentFromContext []}
           (get-in failed [:caseDiagnostics 0 :localization])))
    (is (= {:fileRecallAt5 0.5
            :fileRecallAt10 0.75
            :fileRecallAt20 1.0
            :meanReciprocalRankFile 0.5
            :noiseRatioAt20 0.75
            :evidenceCitationRate 0.25
            :pathEvidenceCitationRate 0.0}
           (get-in failed [:caseDiagnostics 0 :scores])))
    (is (= {:case-id "case-2"
            :status "missing"}
           (select-keys (get-in failed [:caseDiagnostics 1])
                        [:case-id :status])))
    (is (= {:case-id "case-2"
            :repo-id "repo"
            :status "running"
            :activeStage "context-packet"
            :activeElapsedMs 1500}
           (get-in failed [:caseDiagnostics 1 :progress])))
    (is (= #{"completed" "activeStageElapsedMs"}
           (set (map :metric (get-in failed [:caseDiagnostics 1 :failures])))))
    (is (= {:requireComplete true
            :allowDuplicateRuns false
            :minCases 3.0
            :minRuns 2.0
            :minFileRecallAt10 0.9
            :minMeanReciprocalRankFile 0.8
            :maxNoiseRatioAt20 0.5
            :minEvidenceCitationRate 0.9
            :minPathEvidenceCitationRate 0.9
            :minCaseFileRecallAt10 0.9
            :minCaseMeanReciprocalRankFile 0.8
            :minCaseEvidenceCitationRate 0.9
            :minCasePathEvidenceCitationRate 0.9
            :maxCaseNoiseRatioAt20 0.5
            :maxInputHintedCases 0.0
            :maxUnsupportedGroundTruthFiles 0.0
            :maxEmptyResultRuns 0.0
            :maxMissingPredictedFileRuns 0.0
            :maxCommandlessRuns 0.0
            :maxWarningRuns 0.0
            :maxHintDiagnosticRuns 0.0
            :maxIdentityMismatchRuns 0.0
            :maxUnverifiedScoreRuns 0.0
            :maxGraphExpectationFailures 0.0
            :maxMissingDeclaredSourceKindRuns 0.0
            :maxMissedRuns 0.0
            :maxContextRankMissingRuns 0.0
            :maxMissedButPresentInContextRuns 0.0
            :maxMissedAndAbsentFromContextRuns 0.0
            :maxRankedOutsideTop5Runs 0.0
            :maxRankedOutsideTop10Runs 0.0
            :maxRankedOutsideTop20Runs 0.0
            :maxImprovementTargetRuns 0.0
            :maxImprovementTargetKindRuns {"source-skipped-files" 0}
            :maxActiveStageMs 1000.0
            :maxParserWorkerProfiles 1.0
            :minMeasuredProblemClasses 1.0
            :minMeasuredArchitectureClasses 1.0
            :requiredParserWorker "all"}
           (:thresholds failed)))
    (is (= "passed" (:status passed)))
    (is (empty? (:failures passed)))
    (is (= ["passed"] (mapv :status (:caseDiagnostics passed))))
    (is (= false (get-in passed [:thresholds :requireComplete])))
    (is (= false (get-in passed [:thresholds :allowDuplicateRuns])))))
(deftest checks-agent-report-duplicate-runs
  (let [result {:case-id "case-1"
                :agentResultPath "/tmp/run-1.json"
                :agent {:agentId "codex"
                        :mode "agraph"}
                :scores {:fileRecallAt10 1.0
                         :meanReciprocalRankFile 1.0
                         :noiseRatioAt20 0.0}}
        duplicate (assoc result :agentResultPath "/tmp/run-2.json")
        report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 2
                :missing []
                :scores {:fileRecallAt10 1.0
                         :meanReciprocalRankFile 1.0
                         :noiseRatioAt20 0.0}
                :inputHints {:inputHintedCases 0}
                :results [result duplicate]}
        failed (benchmark/check-agent-report report {})
        passed (benchmark/check-agent-report report {:allow-duplicate-runs? true})]
    (is (= "failed" (:status failed)))
    (is (= [{:metric "duplicateRuns"
             :operator "="
             :expected 1
             :actual 2
             :case-id "case-1"
             :agentId "codex"
             :mode "agraph"
             :agentResultPaths ["/tmp/run-1.json" "/tmp/run-2.json"]
             :message "Multiple agent score artifacts matched one case, agent, and mode."}]
           (:failures failed)))
    (is (= ["failed" "failed"]
           (mapv :status (:caseDiagnostics failed))))
    (is (= ["duplicateRuns"]
           (->> failed
                :caseDiagnostics
                first
                :failures
                (mapv :metric))))
    (is (= "passed" (:status passed)))
    (is (empty? (:failures passed)))
    (is (= true (get-in passed [:thresholds :allowDuplicateRuns])))))
(deftest compares-agent-reports-for-regressions
  (let [baseline {:schema benchmark/agent-report-schema
                  :suite-id "suite"
                  :cases 2
                  :completed 2
                  :runs 2
                  :parserWorkers [{:mode "all"
                                   :source "option"
                                   :runs 2}]
                  :agentDiagnostics {:warningRuns 0
                                     :missingPredictedFileRuns 0
                                     :hintDiagnosticRuns 0
                                     :identityMismatchRuns 0
                                     :commandTelemetry {:commandCount 8
                                                        :agraphCommandCount 2
                                                        :searchCommandCount 4
                                                        :fileReadCommandCount 2
                                                        :shellCommandCount 2}}
                  :artifactDiagnostics {:unverifiedScoreRuns 0
                                        :obsoleteScoreSchemaRuns 0
                                        :obsoleteAgentResultSchemaRuns 0
                                        :staleScoreRuns 0}
                  :coverageDiagnostics {:missingDeclaredSourceKindRuns 0
                                        :coverageExcludedGroundTruthFiles 0
                                        :unsupportedGroundTruthFiles 1}
                  :improvementSummary []
                  :scores {:fileRecallAt5 0.5
                           :fileRecallAt10 0.75
                           :fileRecallAt20 1.0
                           :meanReciprocalRankFile 0.5
                           :noiseRatioAt20 0.75}
                  :results [{:case-id "case-1"
                             :scores {:fileRecallAt5 0.5
                                      :fileRecallAt10 1.0
                                      :fileRecallAt20 1.0
                                      :meanReciprocalRankFile 0.5
                                      :noiseRatioAt20 0.5}}
                            {:case-id "case-2"
                             :scores {:fileRecallAt5 0.5
                                      :fileRecallAt10 0.5
                                      :fileRecallAt20 1.0
                                      :meanReciprocalRankFile 0.5
                                      :noiseRatioAt20 1.0}}]}
        candidate {:schema benchmark/agent-report-schema
                   :suite-id "suite"
                   :cases 2
                   :completed 2
                   :runs 2
                   :parserWorkers [{:mode "all"
                                    :source "option"
                                    :runs 2}]
                   :agentDiagnostics {:warningRuns 1
                                      :missingPredictedFileRuns 1
                                      :hintDiagnosticRuns 1
                                      :identityMismatchRuns 1
                                      :commandTelemetry {:commandCount 11
                                                         :agraphCommandCount 2
                                                         :searchCommandCount 5
                                                         :fileReadCommandCount 3
                                                         :shellCommandCount 3}}
                   :artifactDiagnostics {:unverifiedScoreRuns 2
                                         :obsoleteScoreSchemaRuns 1
                                         :obsoleteAgentResultSchemaRuns 1
                                         :staleScoreRuns 1}
                   :coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                         :coverageExcludedGroundTruthFiles 1
                                         :unsupportedGroundTruthFiles 2}
                   :improvementSummary [{:kind "hint-diagnostics"
                                         :area "agent-context-quality"
                                         :runs 2
                                         :caseIds ["case-1"]
                                         :message "AGraph hints contained diagnostics."}]
                   :scores {:fileRecallAt5 0.5
                            :fileRecallAt10 0.7
                            :fileRecallAt20 1.0
                            :meanReciprocalRankFile 0.6
                            :noiseRatioAt20 0.8}
                   :results [{:case-id "case-1"
                              :scores {:fileRecallAt5 0.5
                                       :fileRecallAt10 0.75
                                       :fileRecallAt20 1.0
                                       :meanReciprocalRankFile 0.5
                                       :noiseRatioAt20 0.5}}
                             {:case-id "case-2"
                              :scores {:fileRecallAt5 0.5
                                       :fileRecallAt10 0.65
                                       :fileRecallAt20 1.0
                                       :meanReciprocalRankFile 0.7
                                       :noiseRatioAt20 0.9}}]}
        failed (benchmark/compare-agent-reports baseline candidate {})
        passed (benchmark/compare-agent-reports baseline
                                                (assoc candidate
                                                       :agentDiagnostics
                                                       (:agentDiagnostics baseline)
                                                       :artifactDiagnostics
                                                       (:artifactDiagnostics baseline)
                                                       :coverageDiagnostics
                                                       (:coverageDiagnostics baseline)
                                                       :improvementSummary
                                                       (:improvementSummary baseline)
                                                       :scores (assoc (:scores baseline)
                                                                      :meanReciprocalRankFile
                                                                      0.6)
                                                       :results (:results baseline))
                                                {})
        expanded (benchmark/compare-agent-reports baseline
                                                  (-> baseline
                                                      (assoc :cases 3
                                                             :completed 3
                                                             :runs 3
                                                             :scores {:fileRecallAt5 0.4
                                                                      :fileRecallAt10 0.5
                                                                      :fileRecallAt20 0.75
                                                                      :meanReciprocalRankFile 0.4
                                                                      :noiseRatioAt20 0.9})
                                                      (update :results conj
                                                              {:case-id "case-3"
                                                               :scores {:fileRecallAt5 0.0
                                                                        :fileRecallAt10 0.0
                                                                        :fileRecallAt20 0.0
                                                                        :meanReciprocalRankFile 0.0
                                                                        :noiseRatioAt20 1.0}}))
                                                  {})
        shifted-target (benchmark/compare-agent-reports
                        (assoc baseline
                               :improvementSummary [{:kind "coverage-filtered-candidates"
                                                     :area "agent-context-quality"
                                                     :runs 2
                                                     :caseIds ["case-1"]}])
                        (assoc baseline
                               :improvementSummary [{:kind "audit-scope-trust-boundary"
                                                     :area "audit-scope-quality"
                                                     :runs 2
                                                     :caseIds ["case-2"]}])
                        {})
        shifted-source-target (benchmark/compare-agent-reports
                               (assoc baseline
                                      :improvementSummary [{:kind "coverage-filtered-candidates"
                                                            :area "agent-context-quality"
                                                            :runs 2
                                                            :caseIds ["case-1"]}])
                               (assoc baseline
                                      :improvementSummary [{:kind "source-skipped-files"
                                                            :area "source-coverage-quality"
                                                            :runs 2
                                                            :caseIds ["case-2"]}])
                               {})
        different-parser-worker (benchmark/compare-agent-reports
                                 baseline
                                 (assoc candidate
                                        :parserWorkers [{:mode "java"
                                                         :source "option"
                                                         :runs 2}]
                                        :results (:results baseline))
                                 {})]
    (is (= benchmark/agent-compare-schema (:schema failed)))
    (is (= "failed" (:status failed)))
    (is (= #{"fileRecallAt10"
             "noiseRatioAt20"
             "warningRuns"
             "missingPredictedFileRuns"
             "searchCommandCount"
             "fileReadCommandCount"
             "shellCommandCount"
             "hintDiagnosticRuns"
             "identityMismatchRuns"
             "unverifiedScoreRuns"
             "obsoleteScoreSchemaRuns"
             "obsoleteAgentResultSchemaRuns"
             "staleScoreRuns"
             "missingDeclaredSourceKindRuns"
             "coverageExcludedGroundTruthFiles"
             "unsupportedGroundTruthFiles"
             "improvementTargetRuns"
             "improvementTargetRuns.hint-diagnostics"
             "case.fileRecallAt10"}
           (set (map :metric (:regressions failed)))))
    (is (= 2
           (get-in failed [:candidate :improvementTargetRuns])))
    (is (= {"hint-diagnostics" 2}
           (get-in failed [:candidate :improvementTargetRunsByKind])))
    (is (= "regressed"
           (get-in (first (filter #(= "case-1" (:case-id %))
                                  (:caseDeltas failed)))
                   [:status])))
    (is (= "passed" (:status passed)))
    (is (empty? (:regressions passed)))
    (is (= "passed" (:status expanded)))
    (is (false? (:aggregateComparable expanded)))
    (is (= ["case-set-changed"]
           (:aggregateComparableReasons expanded)))
    (is (some #(= "added" (:status %)) (:caseDeltas expanded)))
    (is (empty? (:regressions expanded)))
    (is (= "failed" (:status shifted-target)))
    (is (= #{"improvementTargetRuns.audit-scope-trust-boundary"}
           (set (map :metric (:regressions shifted-target)))))
    (is (= 0.0
           (:delta (first (filter #(= "improvementTargetRuns" (:metric %))
                                  (:aggregateDeltas shifted-target))))))
    (is (= "failed" (:status shifted-source-target)))
    (is (= #{"improvementTargetRuns.source-skipped-files"}
           (set (map :metric (:regressions shifted-source-target)))))
    (is (= 0.0
           (:delta (first (filter #(= "improvementTargetRuns" (:metric %))
                                  (:aggregateDeltas shifted-source-target))))))
    (is (= "passed" (:status different-parser-worker)))
    (is (false? (:aggregateComparable different-parser-worker)))
    (is (= ["parser-worker-profile-changed"]
           (:aggregateComparableReasons different-parser-worker)))
    (is (= ["parser-worker-profile-changed"]
           (->> (:aggregateDeltas different-parser-worker)
                (filter :ignored?)
                (map :reason)
                distinct
                vec)))
    (is (= [{:mode "all" :source "option"}]
           (get-in different-parser-worker [:baseline :parserWorkers])))
    (is (= [{:mode "java" :source "option"}]
           (get-in different-parser-worker [:candidate :parserWorkers])))
    (is (= (:coverageDiagnostics baseline)
           (get-in failed [:baseline :coverageDiagnostics])))
    (is (= (:coverageDiagnostics candidate)
           (get-in failed [:candidate :coverageDiagnostics])))))
