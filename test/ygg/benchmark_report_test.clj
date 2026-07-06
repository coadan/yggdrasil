(ns ygg.benchmark-report-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-command-telemetry :as benchmark-command-telemetry]
            [ygg.benchmark-preflight :as benchmark-preflight]
            [ygg.benchmark-report :as benchmark-report]
            [ygg.benchmark-test-support :refer [spit-file! spit-json! temp-dir]]
            [ygg.context :as context]
            [clojure.test :refer [deftest is]]))

(def passing-benchmark-preflight
  {:schema benchmark-preflight/benchmark-preflight-schema
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

(def failing-sync-benchmark-preflight
  (assoc-in
   (assoc passing-benchmark-preflight :status "failed")
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

(def ^:private broad-claim-readiness-cases
  [{:id "arch-deps-1"
    :repo-id "repo-a"
    :coverage {:source-kinds [:javascript]}
    :tags [:problem-architecture
           :problem-cross-file
           :architecture-dependency-flow]}
   {:id "arch-deps-2"
    :repo-id "repo-a"
    :coverage {:source-kinds [:javascript]}
    :tags [:problem-architecture
           :problem-cross-file
           :architecture-dependency-flow]}
   {:id "audit-docs-1"
    :repo-id "repo-b"
    :coverage {:source-kinds [:doc]}
    :tags [:problem-architecture
           :problem-docs-config-coupling
           :audit-scope-docs]}
   {:id "audit-docs-2"
    :repo-id "repo-b"
    :coverage {:source-kinds [:doc]}
    :tags [:problem-architecture
           :problem-docs-config-coupling
           :audit-scope-docs]}
   {:id "runtime-python"
    :repo-id "repo-c"
    :coverage {:source-kinds [:python]}
    :tags [:problem-architecture
           :problem-runtime-config
           :architecture-runtime-boundary]}
   {:id "runtime-dotnet"
    :repo-id "repo-d"
    :coverage {:source-kinds [:dotnet]}
    :tags [:problem-architecture
           :problem-runtime-config
           :architecture-runtime-boundary]}
   {:id "data-terraform"
    :repo-id "repo-e"
    :coverage {:source-kinds [:terraform]}
    :tags [:problem-architecture
           :problem-api-contract
           :architecture-data-ownership]}
   {:id "data-sql-text"
    :repo-id "repo-f"
    :coverage {:source-kinds [:sql :text]}
    :tags [:problem-architecture
           :problem-api-contract
           :architecture-data-ownership]}])

(defn- source-kind-names
  [case]
  (mapv name (get-in case [:coverage :source-kinds])))

(defn- score-coverage
  [case]
  (let [source-kinds (source-kind-names case)]
    {:declaredSourceKinds source-kinds
     :scoreableSourceKinds source-kinds
     :scoreableFilesByKind (mapv (fn [source-kind]
                                   {:kind source-kind
                                    :files 1})
                                 source-kinds)
     :missingDeclaredSourceKinds []}))

(defn- broad-claim-score-map
  [recall expected-evidence? expected-evidence-rate]
  (cond-> {:fileRecallAt5 recall
           :fileRecallAt10 recall
           :fileRecallAt20 recall
           :meanReciprocalRankFile recall
           :noiseRatioAt20 (- 1.0 recall)
           :evidenceCitationRate 1.0
           :pathEvidenceCitationRate 1.0
           :changedFiles 1
           :scoreableChangedFiles 1
           :unsupportedGroundTruthFiles 0}
    expected-evidence?
    (assoc :expectedEvidenceCitationRate expected-evidence-rate
           :expectedEvidenceCitations 1
           :expectedEvidenceCitationTargets 1)))

(defn- write-broad-claim-score!
  ([out case]
   (write-broad-claim-score! out case {}))
  ([out case {:keys [benchmark-preflight
                     coverage
                     expected-evidence?
                     expected-evidence-rate
                     recall]
              :or {benchmark-preflight passing-benchmark-preflight
                   expected-evidence? true
                   expected-evidence-rate 1.0
                   recall 1.0}}]
   (let [case-id (:id case)
         coverage (or coverage (score-coverage case))]
     (spit-json!
      out
      (str "suite/cases/" case-id "/agent-scores/run.score.json")
      {:schema benchmark/agent-score-schema
       :suite-id "suite"
       :case-id case-id
       :repo-id (:repo-id case)
       :tags (mapv name (:tags case))
       :coverage coverage
       :benchmarkPreflight benchmark-preflight
       :expectations {:evidence [{:kind "architecture-reference"
                                  :path (str case-id ".clj")}]}
       :agent {:agentId "codex"
               :mode "ygg"
               :topFiles [{:path (str case-id ".clj")
                           :rank 1
                           :evidence ["architecture-evidence"]}]
               :commands ["bb query architecture --project fixture"]}
       :groundTruth {:changedFiles [(str case-id ".clj")]
                     :scoreableFiles [(str case-id ".clj")]
                     :unsupportedGroundTruthFiles []}
       :groundTruthRanks {:files [{:path (str case-id ".clj")
                                   :rank 1
                                   :found? true}]}
       :scores (broad-claim-score-map recall
                                      expected-evidence?
                                      expected-evidence-rate)}))))

(deftest packet-evidence-only-results-are-not-commandless
  (let [top-files [{:path "src/app.clj"
                    :evidence ["prepared-declaration:src/app.clj lines 10 label=\"app/start\""]}
                   {:path "src/context.clj"
                    :evidence ["context-doc:src/context.clj lines 10-20 provenance=retrieved-doc"]}
                   {:path "src/candidate.clj"
                    :evidence ["Prepared summary context 2: candidate-file:src/candidate.clj rank=2"]}
                   {:path "src/neighbor.clj"
                    :evidence ["source-graph: src/app.clj imports src/neighbor.clj line 7"]}]
        issue-only-files [{:path "src/app.clj"
                           :evidence ["src/app.clj is implied by the issue title."]}]
        ygg-diagnostic (benchmark-report/agent-output-diagnostic
                        {:agent {:mode "ygg"
                                 :topFiles top-files
                                 :commands []}
                         :agentPreparation {:status "reused"}})
        issue-only-diagnostic (benchmark-report/agent-output-diagnostic
                               {:agent {:mode "ygg"
                                        :topFiles issue-only-files
                                        :commands []}
                                :agentPreparation {:status "reused"}})
        shell-diagnostic (benchmark-report/agent-output-diagnostic
                          {:agent {:mode "shell-only"
                                   :topFiles top-files
                                   :commands []}})]
    (is (:packetEvidenceOnly ygg-diagnostic))
    (is (false? (:commandless ygg-diagnostic)))
    (is (not (:packetEvidenceOnly issue-only-diagnostic)))
    (is (:commandless issue-only-diagnostic))
    (is (not (:packetEvidenceOnly shell-diagnostic)))
    (is (:commandless shell-diagnostic))))

(deftest localization-diagnostics-ignore-unavoidable-top5-overflow
  (let [aggregate @#'benchmark-report/aggregate-localization-diagnostics
        scoreable-files (mapv #(str "src/file" % ".clj") (range 1 8))
        result (fn [case-id top-files ranks]
                 {:case-id case-id
                  :agent {:mode "ygg"
                          :topFiles top-files}
                  :groundTruth {:scoreableFiles scoreable-files
                                :changedFiles scoreable-files
                                :unsupportedGroundTruthFiles []}
                  :groundTruthRanks {:files ranks}})
        rank-row (fn [path rank]
                   {:path path
                    :rank rank
                    :found? true})
        saturated-ranks (mapv rank-row scoreable-files (range 1 8))
        saturated-result (result
                          "saturated"
                          (mapv (fn [path rank]
                                  {:path path
                                   :rank rank})
                                scoreable-files
                                (range 1 8))
                          saturated-ranks)
        blocked-ranks [(rank-row "src/file1.clj" 1)
                       (rank-row "src/file2.clj" 2)
                       (rank-row "src/file3.clj" 4)
                       (rank-row "src/file4.clj" 5)
                       (rank-row "src/file5.clj" 6)
                       (rank-row "src/file6.clj" 7)
                       (rank-row "src/file7.clj" 8)]
        blocked-result (result
                        "blocked"
                        [{:path "src/file1.clj" :rank 1}
                         {:path "src/file2.clj" :rank 2}
                         {:path "src/blocker.clj" :rank 3}
                         {:path "src/file3.clj" :rank 4}
                         {:path "src/file4.clj" :rank 5}
                         {:path "src/file5.clj" :rank 6}
                         {:path "src/file6.clj" :rank 7}
                         {:path "src/file7.clj" :rank 8}]
                        blocked-ranks)]
    (is (= {:rankedOutsideTop5Runs 0
            :rankedOutsideTop5CaseIds []}
           (select-keys (aggregate [saturated-result])
                        [:rankedOutsideTop5Runs
                         :rankedOutsideTop5CaseIds])))
    (is (= {:foundOutsideTop5Runs 1
            :foundOutsideTop5CaseIds ["saturated"]
            :foundOutsideTop5Files [{:path "src/file6.clj"
                                     :occurrences 1
                                     :runs 1
                                     :caseIds ["saturated"]
                                     :bestRank 6}
                                    {:path "src/file7.clj"
                                     :occurrences 1
                                     :runs 1
                                     :caseIds ["saturated"]
                                     :bestRank 7}]}
           (select-keys (aggregate [saturated-result])
                        [:foundOutsideTop5Runs
                         :foundOutsideTop5CaseIds
                         :foundOutsideTop5Files])))
    (is (= {:rankedOutsideTop5Runs 1
            :rankedOutsideTop5CaseIds ["blocked"]}
           (select-keys (aggregate [blocked-result])
                        [:rankedOutsideTop5Runs
                         :rankedOutsideTop5CaseIds])))))

(deftest reports-agent-score-artifacts
  (let [out (temp-dir "ygg-agent-report")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :tags [:runtime-config]}
                       {:id "case-2"
                        :tags [:dependency]}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :tags ["runtime-config"]
                 :parserWorker {:mode "all"
                                :source "option"}
                 :expectations {:evidence [{:kind "env-var"
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
                 :agentPreparation {:status "reused"
                                    :path "/tmp/ygg-preparation.json"
                                    :preparedAt "2026-06-18T00:00:01Z"}
                 :agent {:agentId "codex"
                         :mode "ygg"
                         :topFiles [{:path "src/other.clj"
                                     :rank 1
                                     :metrics {:supportCount 2
                                               :docCount 1}}
                                    {:path "src/app.clj"
                                     :rank 7}]
                         :warnings ["agent result suspectedFiles row 1 missing evidence"]
                         :commands ["rg broken src"
                                    "sed -n '1,40p' src/app.clj"
                                    "ygg query broken --project fixture"
                                    "npm test"]
                         :tokenUsage {:inputTokens 100
                                      :outputTokens 40
                                      :totalTokens 140
                                      :costUsd 0.01
                                      :source "fixture-agent"}
                         :selection {:rawCandidateFiles 3
                                     :candidateFiles 2
                                     :coverageFilteredCandidateFiles 1
                                     :limit 20
                                     :coverageSourceKinds ["code"]}}
                 :yggHints {:diagnostics [{:kind "coverage-filtered-candidate-files"
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
                {:schema "ygg.benchmark.case-progress/v1"
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
                {:schema "ygg.benchmark.case-progress/v1"
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
                                                      :allow-unverified-scores? true})
          ygg-mode (first (filter #(= "ygg" (:key %)) (:byMode report)))]
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
      (is (= {:runs 1
              :caseIds ["case-1"]
              :statusCounts {"reused" 1}
              :reusedRuns 1
              :reusedCaseIds ["case-1"]
              :preparedDuringAgentRuns 0
              :preparedDuringAgentCaseIds []
              :missingRuns 0
              :missingCaseIds []
              :otherStatusRuns 0
              :otherStatusCaseIds []
              :otherStatuses []
              :allRunsReadyBeforeAgent true
              :strictWarmBenchmark true
              :primaryElapsedMetric "warmElapsedMs"
              :excludedFromPrimaryElapsed ["graph-setup" "agent-preparation"]
              :setupCostPolicy "strict warm: XTDB graph DB, context packet, and compact hints were already prepared for the agent and reused before the measured agent process; setup cost is not counted in warmElapsedMs."
              :basis "reused means the XTDB graph DB, context packet, and compact hints were prepared before the measured agent process started; prepared means the same agent-run command created them and warmElapsedMs only amortizes that setup cost."
              :warnings []}
             (:agentPreparation report)))
      (is (= (:agentPreparation report)
             (get-in ygg-mode [:agentPreparation])))
      (is (= "reused"
             (get-in report [:results 0 :agentPreparation :status])))
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
                                 :yggCommandCount 1
                                 :searchCommandCount 1
                                 :broadSearchCommandCount 1
                                 :scopedSearchCommandCount 0
                                 :exactFileSearchCommandCount 0
                                 :fileReadCommandCount 1
                                 :shellCommandCount 1}
              :tokenUsageRuns 1
              :tokenUsageCaseIds ["case-1"]
              :missingTokenUsageRuns 1
              :missingTokenUsageCaseIds ["case-1"]
              :invalidTokenUsageRuns 0
              :invalidTokenUsageCaseIds []
              :tokenTelemetry {:inputTokens 100
                               :outputTokens 40
                               :totalTokens 140
                               :costUsd 0.01}
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
              "benchmark-preflight"
              "commandless-runs"
              "missing-token-usage"
              "warning-runs"
              "obsolete-agent-result-contract"
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
                  (filter #(= "obsolete-agent-result-contract" (:kind %)))
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
              :foundOutsideTop5Runs 1
              :foundOutsideTop5CaseIds ["case-1"]
              :foundOutsideTop10Runs 0
              :foundOutsideTop10CaseIds []
              :foundOutsideTop20Runs 0
              :foundOutsideTop20CaseIds []
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
              :foundOutsideTop5Files [{:path "src/app.clj"
                                       :occurrences 1
                                       :runs 1
                                       :caseIds ["case-1"]
                                       :bestRank 7}]
              :foundOutsideTop10Files []
              :foundOutsideTop20Files []
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
      (is (= {:tags ["dependency" "runtime-config"]
              :casesByTag [{:tag "dependency"
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
              :warmElapsedMs 0
              :agentReadyElapsedMs 0
              :amortizedSetupElapsedMs 8000
              :graphSetupElapsedMs 5000
              :caseSetupElapsedMs 0
              :agentPreparationElapsedMs 3000
              :embeddingElapsedMs 0
              :scoringElapsedMs 0
              :stageClassElapsedMs [{:class "graph-setup"
                                     :elapsedMs 5000}
                                    {:class "agent-preparation"
                                     :elapsedMs 3000}]
              :stageTiming {:basis "warmElapsedMs assumes a prepared-agent run: the Yggdrasil XTDB graph DB and agent context are already prepared, so graph setup and agent preparation are reported as amortized setup instead of counted in the primary elapsed metric."
                            :primaryElapsedMetric "warmElapsedMs"
                            :agentReadyElapsedMetric "agentReadyElapsedMs"
                            :amortizedStageClasses ["graph-setup" "agent-preparation"]
                            :classes [{:stage "context-packet"
                                       :class "agent-preparation"
                                       :elapsedMs 3000}
                                      {:stage "index-project"
                                       :class "graph-setup"
                                       :elapsedMs 5000}]}
              :stageElapsedMs [{:stage "context-packet"
                                :elapsedMs 3000}
                               {:stage "index-project"
                                :elapsedMs 5000}]
              :slowestCases [{:case-id "case-1"
                              :repo-id nil
                              :status "running"
                              :activeStage "context-packet"
                              :activeElapsedMs 3000
                              :warmElapsedMs 0
                              :agentReadyElapsedMs 0
                              :amortizedSetupElapsedMs 5000
                              :elapsedMs 5000}
                             {:case-id "case-2"
                              :repo-id nil
                              :status "failed"
                              :warmElapsedMs 0
                              :agentReadyElapsedMs 0
                              :amortizedSetupElapsedMs 3000
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
      (is (= ["runtime-config"]
             (get-in report [:results 0 :tags])))
      (is (= {:mode "all"
              :source "option"}
             (get-in report [:results 0 :parserWorker])))
      (is (= "failed"
             (get-in report [:results 0 :graphExpectations :status])))
      (is (= {:total 2
              :found 1
              :missed 1
              :presentInContextButMissed 1}
             (get-in report [:results 0 :auditScope :groundTruthSummary])))
      (is (= [{:path "src/app.clj"
               :scoreable? true
               :found? true
               :rank 7
               :contextRank 7
               :evidenceCited? false
               :pathEvidenceCited? false}
              {:path "src/missing.clj"
               :scoreable? true
               :found? false
               :contextRank 12
               :contextFound? true}]
             (get-in report [:results 0 :auditScope :groundTruthFiles])))
      (is (= {:expectedEvidence 1
              :foundEvidence 1
              :missingEvidence 0
              :expectedEdges 0
              :foundEdges 0
              :missingEdges 0
              :forbiddenEdges 1
              :forbiddenEdgeViolations 1}
             (get-in report
                     [:results 0 :auditScope :graphExpectationSummary])))
      (is (= {:rawSuspectedFiles 2
              :rankedFiles 2
              :commandCount 4
              :commandTelemetry {:commandCount 4
                                 :yggCommandCount 1
                                 :searchCommandCount 1
                                 :broadSearchCommandCount 1
                                 :scopedSearchCommandCount 0
                                 :exactFileSearchCommandCount 0
                                 :fileReadCommandCount 1
                                 :shellCommandCount 1
                                 :commandless false}
              :tokenUsage {:inputTokens 100
                           :outputTokens 40
                           :totalTokens 140
                           :costUsd 0.01
                           :source "fixture-agent"}
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
             (get-in ygg-mode [:inputHints])))
      (is (= {:runs 1
              :expectedEvidenceRuns 1
              :expectedEvidenceCaseIds ["case-1"]
              :expectedEvidenceTargets 1
              :expectedEvidenceCitationMetricRuns 1
              :expectedEvidenceCitationMetricCaseIds ["case-1"]
              :missingExpectedEvidenceCitationMetricRuns 0
              :missingExpectedEvidenceCitationMetricCaseIds []}
             (get-in ygg-mode [:expectationDiagnostics])))
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
             (get-in ygg-mode [:coverageDiagnostics])))
      (is (= ["missed-files-present-in-context"
              "ranked-outside-top5"
              "path-citation-gaps"
              "missing-declared-source-kinds"
              "coverage-excluded-ground-truth"
              "hint-diagnostics"
              "audit-scope-trust-boundary"
              "source-skipped-files"
              "graph-expectation-failures"
              "benchmark-preflight"
              "warning-runs"
              "obsolete-agent-result-contract"
              "unverified-score-artifacts"]
             (mapv :kind
                   (get-in ygg-mode [:improvementSummary]))))
      (is (= #{"ygg" "shell-only"} (set (map :key (:byMode report)))))
      (is (= ["baseline" "codex"] (mapv :key (:byAgent report))))
      (is (= ["all/option" "unknown/missing"]
             (mapv :key (:byParserWorker report))))
      (is (= 1.0
             (get-in (first (:byParserWorker report))
                     [:scores :fileRecallAt10])))
      (is (= ["runtime-config"] (mapv :key (:byTag report))))
      (is (= 1
             (get-in (first (:byTag report))
                     [:graphExpectationDiagnostics :failedRuns]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :mode "ygg"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= 1.0 (get-in report [:scores :fileRecallAt10])))
      (is (= ["ygg"] (mapv :key (:byMode report)))))
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

(deftest report-backfills-token-usage-from-existing-agent-prompt
  (let [out (temp-dir "ygg-agent-report-token-backfill")
        suite {:id "suite"
               :cases [{:id "case-1"}]}
        prompt "Investigate the regression with shell commands and return JSON.\n"
        expected-tokens (context/estimate-tokens prompt)
        stale-result-path "/tmp/old-ygg/suite/cases/case-1/agent-results/codex.json"]
    (spit-file! out
                "suite/cases/case-1/agent-prompts/codex.md"
                prompt)
    (spit-json! out
                "suite/cases/case-1/agent-scores/codex.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agentResultPath stale-result-path
                 :agent {:agentId "codex"
                         :mode "shell-only"
                         :topFiles []
                         :rawSuspectedFileCount 0
                         :commands ["rg regression src"]}
                 :groundTruth {:changedFiles ["src/app.clj"]
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
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})
          expected-usage {:inputTokens expected-tokens
                          :outputTokens 0
                          :totalTokens expected-tokens
                          :costUsd 0.0
                          :source "benchmark-prompt-estimate"}]
      (is (= expected-usage
             (get-in report [:results 0 :agent :tokenUsage])))
      (is (= {:tokenUsageRuns 1
              :tokenUsageCaseIds ["case-1"]
              :missingTokenUsageRuns 0
              :missingTokenUsageCaseIds []
              :invalidTokenUsageRuns 0
              :invalidTokenUsageCaseIds []}
             (select-keys (:agentDiagnostics report)
                          [:tokenUsageRuns
                           :tokenUsageCaseIds
                           :missingTokenUsageRuns
                           :missingTokenUsageCaseIds
                           :invalidTokenUsageRuns
                           :invalidTokenUsageCaseIds])))
      (is (= {:inputTokens expected-tokens
              :outputTokens 0
              :totalTokens expected-tokens
              :costUsd 0.0}
             (get-in report [:agentDiagnostics :tokenTelemetry]))))))

(deftest report-replaces-placeholder-token-usage-from-existing-agent-prompt
  (let [out (temp-dir "ygg-agent-report-token-placeholder-backfill")
        suite {:id "suite"
               :cases [{:id "case-1"}]}
        prompt "Inspect the graph packet and return compact JSON.\n"
        expected-tokens (context/estimate-tokens prompt)
        stale-result-path "/tmp/old-ygg/suite/cases/case-1/agent-results/codex.json"]
    (spit-file! out
                "suite/cases/case-1/agent-prompts/codex.md"
                prompt)
    (spit-json! out
                "suite/cases/case-1/agent-scores/codex.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agentResultPath stale-result-path
                 :agent {:agentId "codex"
                         :mode "ygg"
                         :topFiles []
                         :rawSuspectedFileCount 0
                         :commands ["ygg query regression --project fixture"]
                         :tokenUsage {:inputTokens 0
                                      :outputTokens 0
                                      :totalTokens 0
                                      :costUsd 0.0
                                      :source "placeholder"}}
                 :groundTruth {:changedFiles ["src/app.clj"]
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
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})
          expected-usage {:inputTokens expected-tokens
                          :outputTokens 0
                          :totalTokens expected-tokens
                          :costUsd 0.0
                          :source "benchmark-prompt-estimate"}]
      (is (= expected-usage
             (get-in report [:results 0 :agent :tokenUsage])))
      (is (= {:tokenUsageRuns 1
              :missingTokenUsageRuns 0
              :invalidTokenUsageRuns 0}
             (select-keys (:agentDiagnostics report)
                          [:tokenUsageRuns
                           :missingTokenUsageRuns
                           :invalidTokenUsageRuns])))
      (is (= {:inputTokens expected-tokens
              :outputTokens 0
              :totalTokens expected-tokens
              :costUsd 0.0}
             (get-in report [:agentDiagnostics :tokenTelemetry]))))))

(deftest report-replaces-codebase-memory-placeholder-token-usage-from-result-surface
  (let [out (temp-dir "ygg-agent-report-codebase-memory-token-backfill")
        suite {:id "suite"
               :cases [{:id "case-1"}]}
        agent {:agentId "ygg-baseline-codebase-memory"
               :mode "codebase-memory"
               :topFiles [{:path "src/app.clj"
                           :rank 1
                           :confidence 1.0
                           :reason "Codebase Memory MCP returned this exact path."
                           :evidence ["codebase-memory:search_code path=src/app.clj"]}]
               :rawSuspectedFileCount 1
               :commands ["codebase-memory-mcp cli search_code"]
               :warnings []
               :summary "Codebase Memory MCP baseline ranked 1 files."
               :tokenUsage {:inputTokens 0
                            :outputTokens 0
                            :totalTokens 0
                            :costUsd 0.0
                            :source "codebase-memory-baseline"}}
        expected-tokens (context/estimate-tokens (dissoc agent :tokenUsage))]
    (spit-json! out
                "suite/cases/case-1/agent-scores/ygg-baseline-codebase-memory.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agentResultPath "/tmp/old-ygg/suite/cases/case-1/agent-results/ygg-baseline-codebase-memory.json"
                 :agent agent
                 :groundTruth {:changedFiles ["src/app.clj"]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :found? true
                                             :rank 1}]}
                 :scores {:fileRecallAt5 1.0
                          :fileRecallAt10 1.0
                          :fileRecallAt20 1.0
                          :meanReciprocalRankFile 1.0
                          :noiseRatioAt20 0.0
                          :changedFiles 1
                          :scoreableChangedFiles 1
                          :unsupportedGroundTruthFiles 0}})
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})
          expected-usage {:inputTokens expected-tokens
                          :outputTokens 0
                          :totalTokens expected-tokens
                          :costUsd 0.0
                          :source "codebase-memory-result-surface-estimate"}]
      (is (= expected-usage
             (get-in report [:results 0 :agent :tokenUsage])))
      (is (= {:tokenUsageRuns 1
              :missingTokenUsageRuns 0
              :invalidTokenUsageRuns 0}
             (select-keys (:agentDiagnostics report)
                          [:tokenUsageRuns
                           :missingTokenUsageRuns
                           :invalidTokenUsageRuns])))
      (is (= {:inputTokens expected-tokens
              :outputTokens 0
              :totalTokens expected-tokens
              :costUsd 0.0}
             (get-in report [:agentDiagnostics :tokenTelemetry]))))))

(deftest reports-decision-quality-diagnostics
  (let [out (temp-dir "ygg-agent-report-decision")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :tags [:problem-architecture]}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "codex"
                         :mode "ygg"
                         :topFiles []}
                 :groundTruth {:changedFiles []
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files []}
                 :decisionScoring {:configured true
                                   :kind "architecture-choice"
                                   :candidateIds ["a" "b" "c"]
                                   :requiredChoiceIds ["a" "b"]
                                   :forbiddenChoiceIds ["c"]
                                   :includedChoiceIds ["a" "c"]
                                   :excludedChoiceIds []
                                   :deferredChoiceIds ["b"]
                                   :unknownChoiceIds ["unknown"]
                                   :matchedRequiredChoiceIds ["a"]
                                   :missedChoiceIds ["b"]
                                   :wrongIncludedChoiceIds ["c"]
                                   :deferredRequiredChoiceIds ["b"]
                                   :uncitedChoiceIds ["c"]
                                   :missingDecision false}
                 :scores {:fileRecallAt5 0.0
                          :fileRecallAt10 0.0
                          :fileRecallAt20 0.0
                          :meanReciprocalRankFile 0.0
                          :noiseRatioAt20 0.0
                          :evidenceCitationRate 0.0
                          :pathEvidenceCitationRate 0.0
                          :decisionRecall 0.5
                          :decisionPrecision 0.5
                          :decisionF1 0.5
                          :decisionEvidenceCitationRate 0.5}})
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= {:configuredRuns 1
              :configuredCaseIds ["case-1"]
              :gapRuns 1
              :gapCaseIds ["case-1"]
              :missingDecisionRuns 0
              :missingDecisionCaseIds []
              :missedDecisionRuns 1
              :missedDecisionCaseIds ["case-1"]
              :wrongDecisionRuns 1
              :wrongDecisionCaseIds ["case-1"]
              :uncitedDecisionRuns 1
              :uncitedDecisionCaseIds ["case-1"]
              :unknownDecisionChoiceRuns 1
              :unknownDecisionChoiceCaseIds ["case-1"]
              :choiceGaps [{:kind "missed"
                            :id "b"
                            :runs 1
                            :caseIds ["case-1"]}
                           {:kind "wrong-included"
                            :id "c"
                            :runs 1
                            :caseIds ["case-1"]}
                           {:kind "uncited"
                            :id "c"
                            :runs 1
                            :caseIds ["case-1"]}
                           {:kind "unknown"
                            :id "unknown"
                            :runs 1
                            :caseIds ["case-1"]}]}
             (:decisionDiagnostics report)))
      (is (= 0.5 (get-in report [:scores :decisionF1])))
      (is (= ["decision-quality-gaps"]
             (->> (:improvementSummary report)
                  (filter #(= "decision-quality-gaps" (:kind %)))
                  (mapv :kind))))
      (is (= ["c"]
             (get-in report [:results 0 :decision :uncitedChoiceIds]))))))

(deftest improvement-summary-flags-missing-ygg-context-ranks
  (let [results [{:case-id "case-1"
                  :agent {:mode "ygg"
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
            :message "Yggdrasil-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."}
           (->> summary
                (filter #(= "missing-context-ranks" (:kind %)))
                first)))))

(deftest agent-output-diagnostic-normalizes-command-telemetry
  (let [diagnostic (#'benchmark/agent-output-diagnostic
                    {:agent {:commands ["rg broken src"
                                        "git grep broken -- src"
                                        "env FOO=bar sed -n '1,20p' src/app.clj"
                                        "bb query 'broken app' --project fixture"
                                        "npm test"]
                             :topFiles [{:path "src/app.clj"}]}})]
    (is (= {:commandCount 5
            :yggCommandCount 1
            :searchCommandCount 2
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 1
            :exactFileSearchCommandCount 0
            :fileReadCommandCount 1
            :shellCommandCount 1
            :commandless false}
           (:commandTelemetry diagnostic)))))

(deftest agent-output-diagnostic-keeps-internal-ripgrep-out-of-shell-search-counts
  (let [diagnostic (#'benchmark/agent-output-diagnostic
                    {:agent {:commands []
                             :topFiles [{:path "src/app.clj"}]}
                     :yggHints {:search {:instrumentation {:grep-searches 2
                                                           :grep-search-ms 31
                                                           :grep-raw-matches 7}}}})]
    (is (= {:commandCount 0
            :searchCommandCount 0
            :internalRipgrepSearchCount 2
            :internalRipgrepElapsedMs 31
            :internalRipgrepMatchCount 7}
           (select-keys (:commandTelemetry diagnostic)
                        [:commandCount
                         :searchCommandCount
                         :internalRipgrepSearchCount
                         :internalRipgrepElapsedMs
                         :internalRipgrepMatchCount])))))

(deftest agent-output-diagnostic-counts-compound-command-segments
  (let [diagnostic (#'benchmark/agent-output-diagnostic
                    {:agent {:commands ["cat src/app.clj | rg broken"
                                        "sed -n '1,20p' src/app.clj; npm test"]
                             :topFiles [{:path "src/app.clj"}]}})]
    (is (= {:commandCount 2
            :yggCommandCount 0
            :searchCommandCount 1
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 0
            :exactFileSearchCommandCount 0
            :fileReadCommandCount 1
            :shellCommandCount 0
            :commandless false
            :segmentCount 4
            :yggSegmentCount 0
            :searchSegmentCount 1
            :broadSearchSegmentCount 1
            :scopedSearchSegmentCount 0
            :exactFileSearchSegmentCount 0
            :fileReadSegmentCount 2
            :shellSegmentCount 1}
           (:commandTelemetry diagnostic)))))

(deftest aggregate-command-telemetry-falls-back-to-command-counts-for-single-segment-runs
  (let [summary (benchmark-command-telemetry/aggregate-command-telemetry
                 [{:commandTelemetry {:commandCount 1
                                      :yggCommandCount 1
                                      :searchCommandCount 0
                                      :broadSearchCommandCount 0
                                      :scopedSearchCommandCount 0
                                      :exactFileSearchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}
                  {:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 1
                                      :broadSearchCommandCount 1
                                      :scopedSearchCommandCount 0
                                      :exactFileSearchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0
                                      :segmentCount 2
                                      :yggSegmentCount 0
                                      :searchSegmentCount 1
                                      :broadSearchSegmentCount 1
                                      :scopedSearchSegmentCount 0
                                      :exactFileSearchSegmentCount 0
                                      :fileReadSegmentCount 1
                                      :shellSegmentCount 0}}])]
    (is (= {:commandCount 2
            :yggCommandCount 1
            :searchCommandCount 1
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 0
            :exactFileSearchCommandCount 0
            :fileReadCommandCount 0
            :shellCommandCount 0
            :segmentCount 3
            :yggSegmentCount 1
            :searchSegmentCount 1
            :broadSearchSegmentCount 1
            :scopedSearchSegmentCount 0
            :exactFileSearchSegmentCount 0
            :fileReadSegmentCount 1
            :shellSegmentCount 0}
           summary))))

(deftest aggregate-token-telemetry-sums-across-diagnostics
  (let [summary (benchmark-command-telemetry/aggregate-token-telemetry
                 [{:tokenUsage {:inputTokens 1000
                                :outputTokens 500
                                :totalTokens 1500
                                :costUsd 0.05
                                :source "deepseek-agent"}}
                  {:tokenUsage {:inputTokens 2000
                                :outputTokens 800
                                :totalTokens 2800
                                :costUsd 0.07
                                :source "deepseek-agent"}}
                  {}])]
    (is (= {:inputTokens 3000
            :outputTokens 1300
            :totalTokens 4300}
           (dissoc summary :costUsd)))
    (is (< (abs (- 0.12 (:costUsd summary))) 0.0001))))

(deftest aggregate-token-telemetry-returns-nil-when-no-usage
  (is (nil? (benchmark-command-telemetry/aggregate-token-telemetry [{} {}])))
  (is (nil? (benchmark-command-telemetry/aggregate-token-telemetry []))))

(deftest agent-output-diagnostic-includes-token-usage-when-present
  (let [diagnostic (#'benchmark-report/agent-output-diagnostic
                    {:agent {:commands ["rg broken src"]
                             :topFiles [{:path "src/app.clj"}]
                             :tokenUsage {:inputTokens 5000
                                          :outputTokens 1200
                                          :totalTokens 6200
                                          :costUsd 0.15
                                          :source "deepseek-agent"}}})]
    (is (= {:inputTokens 5000
            :outputTokens 1200
            :totalTokens 6200
            :costUsd 0.15
            :source "deepseek-agent"}
           (:tokenUsage diagnostic)))))

(deftest agent-output-diagnostic-omits-token-usage-when-absent
  (let [diagnostic (#'benchmark-report/agent-output-diagnostic
                    {:agent {:commands ["rg broken src"]
                             :topFiles [{:path "src/app.clj"}]}})]
    (is (not (contains? diagnostic :tokenUsage)))))

(deftest command-telemetry-classifies-bb-packages-as-ygg
  (let [telemetry (benchmark-command-telemetry/command-telemetry
                   ["bb packages --project fixture --json"])]
    (is (= 1 (:yggCommandCount telemetry)))))

(deftest reports-problem-class-summaries
  (let [out (temp-dir "ygg-agent-report-problem-classes")
        suite {:id "suite"
               :cases [{:id "arch-runtime"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :architecture-runtime-boundary]}
                       {:id "arch-deps"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs"
                        :repo-id "repo-b"
                        :coverage {:source-kinds [:doc]}
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "localization"
                        :repo-id "repo-b"
                        :coverage {:source-kinds [:doc]}
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
                         :benchmarkPreflight passing-benchmark-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "ygg"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["candidate-file"]}]
                                 :commands ["ygg query"]}
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
              :repoIds ["repo-a" "repo-b"]
              :sourceKindKeys ["doc" "javascript"]
              :minimumReposForBroadClaim 6
              :minimumSourceKindsForBroadClaim 7
              :minimumMeasuredProblemClassesForBroadClaim 3
              :minimumMeasuredArchitectureClassesForBroadClaim 3
              :minimumExpectedEvidenceCitationRateForClaim 0.8
              :minimumCaseExpectedEvidenceCitationRateForClaim 0.5
              :requirements {:completedCases true
                             :hasRuns true
                             :nonSyntheticCases true
                             :repoBreadth false
                             :sourceKindBreadth false
                             :declaredSourceKindCoverage true
                             :measuredProblemClasses false
                             :measuredNonSyntheticProblemClasses false
                             :measuredArchitectureClasses false
                             :measuredNonSyntheticArchitectureClasses false
                             :evidenceCitationMetrics true
                             :expectedEvidenceCitationMetrics true
                             :expectedEvidenceCitationQuality true
                             :caseExpectedEvidenceCitationQuality true
                             :decisionQualityMetrics true
                             :commandTelemetry true
                             :benchmarkPreflight true}
              :warnings ["Only 2 benchmark repo(s); broad real-world claims require at least 6."
                         "Only 2 declared source-kind group(s); broad real-world claims require at least 7."
                         "Only 1 measured problem-class group(s); broad real-world claims require at least 3."
                         "Only 1 measured non-synthetic problem-class group(s); broad real-world claims require at least 3."
                         "No measured architecture-class groups; architecture tags are present only below the class-claim threshold or absent."
                         "No measured non-synthetic architecture-class groups; broad real-world claims need replay-backed architecture coverage."]}
             (:claimReadiness report))))))
(deftest agent-report-claim-readiness-requires-measured-non-synthetic-classes
  (let [out (temp-dir "ygg-agent-report-mixed-synthetic-readiness")
        suite {:id "suite"
               :cases (mapv (fn [case]
                              (if (= "arch-deps-1" (:id case))
                                case
                                (update case :tags into [:synthetic])))
                            broad-claim-readiness-cases)}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score! out case))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= true
             (get-in report
                     [:claimReadiness :requirements :nonSyntheticCases])))
      (is (= true
             (get-in report
                     [:claimReadiness
                      :requirements
                      :measuredProblemClasses])))
      (is (= true
             (get-in report
                     [:claimReadiness
                      :requirements
                      :measuredArchitectureClasses])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :measuredNonSyntheticProblemClasses])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :measuredNonSyntheticArchitectureClasses])))
      (is (= ["No measured non-synthetic problem-class groups; broad real-world claims need replay-backed problem coverage."
              "No measured non-synthetic architecture-class groups; broad real-world claims need replay-backed architecture coverage."]
             (get-in report [:claimReadiness :warnings]))))))
(deftest agent-report-claim-readiness-requires-non-synthetic-cases
  (let [out (temp-dir "ygg-agent-report-synthetic-only-readiness")
        suite {:id "suite"
               :cases (mapv #(update % :tags into [:synthetic])
                            broad-claim-readiness-cases)}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score! out case))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :broadArchitectureClaimSupported])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :nonSyntheticCases])))
      (is (= ["No non-synthetic replay cases are included; broad real-world claims are unproven."
              "Selected benchmark cases are all synthetic; restrict broad efficiency claims or add non-synthetic replay cases."]
             (get-in report [:claimReadiness :warnings]))))))
(deftest agent-report-claim-readiness-recognizes-measured-architecture-coverage
  (let [out (temp-dir "ygg-agent-report-claim-readiness")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score! out
                                case
                                {:recall (if (#{"arch-deps-2" "audit-docs-2"}
                                              (:id case))
                                           0.75
                                           1.0)}))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "supported" (get-in report [:claimReadiness :status])))
      (is (= true
             (get-in report
                     [:claimReadiness :broadArchitectureClaimSupported])))
      (is (= ["problem-api-contract"
              "problem-architecture"
              "problem-cross-file"
              "problem-docs-config-coupling"
              "problem-runtime-config"]
             (get-in report [:claimReadiness :measuredProblemClassTags])))
      (is (= ["architecture-data-ownership"
              "architecture-dependency-flow"
              "architecture-runtime-boundary"
              "audit-scope-docs"]
             (get-in report [:claimReadiness :measuredArchitectureClassTags])))
      (is (= 6 (get-in report [:claimReadiness :minimumReposForBroadClaim])))
      (is (= 7
             (get-in report
                     [:claimReadiness :minimumSourceKindsForBroadClaim])))
      (is (= 3
             (get-in report
                     [:claimReadiness
                      :minimumMeasuredProblemClassesForBroadClaim])))
      (is (= 3
             (get-in report
                     [:claimReadiness
                      :minimumMeasuredArchitectureClassesForBroadClaim])))
      (is (= []
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-docs-claim-readiness-supports-docs-lane
  (let [out (temp-dir "ygg-agent-report-docs-claim-readiness")
        suite {:id "suite"
               :cases [{:id "docs-a"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:doc]}
                        :tags [:problem-docs-config-coupling
                               :audit-scope-docs]}
                       {:id "docs-b"
                        :repo-id "repo-b"
                        :coverage {:source-kinds [:doc]}
                        :tags [:problem-docs-config-coupling
                               :audit-scope-docs]}
                       {:id "docs-c"
                        :repo-id "repo-b"
                        :coverage {:source-kinds [:doc]}
                        :tags [:problem-docs-config-coupling
                               :audit-scope-docs]}
                       {:id "docs-d"
                        :repo-id "repo-c"
                        :coverage {:source-kinds [:doc]}
                        :tags [:problem-docs-config-coupling]}
                       {:id "docs-config-ci"
                        :repo-id "repo-c"
                        :coverage {:source-kinds [:ci]}
                        :tags [:problem-docs-config-coupling]}]}
        write-score! (fn [case]
                       (let [case-id (:id case)
                             source-kinds (mapv name
                                                (get-in case
                                                        [:coverage
                                                         :source-kinds]))
                             path (str case-id
                                       (if (= ["ci"] source-kinds)
                                         ".yml"
                                         ".md"))]
                         (spit-json!
                          out
                          (str "suite/cases/" case-id "/agent-scores/run.score.json")
                          {:schema benchmark/agent-score-schema
                           :suite-id "suite"
                           :case-id case-id
                           :repo-id (:repo-id case)
                           :tags (mapv name (:tags case))
                           :benchmarkPreflight passing-benchmark-preflight
                           :coverage {:declaredSourceKinds source-kinds
                                      :scoreableSourceKinds source-kinds
                                      :scoreableFilesByKind [{:kind (first source-kinds)
                                                              :files 1}]
                                      :missingDeclaredSourceKinds []}
                           :expectations {:evidence [{:kind "doc-heading"
                                                      :path path}]}
                           :agent {:agentId "codex"
                                   :mode "ygg"
                                   :topFiles [{:path path
                                               :rank 1
                                               :evidence ["context-doc"]}]
                                   :commands ["ygg query docs --project fixture"]}
                           :groundTruth {:changedFiles [path]
                                         :scoreableFiles [path]
                                         :unsupportedGroundTruthFiles []}
                           :groundTruthRanks {:files [{:path path
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
                                    :unsupportedGroundTruthFiles 0}})))]
    (doseq [case (:cases suite)]
      (write-score! case))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :requirements :sourceKindBreadth])))
      (is (= "supported" (get-in report [:docsClaimReadiness :status])))
      (is (= true
             (get-in report
                     [:docsClaimReadiness :docsHandlingClaimSupported])))
      (is (= ["problem-docs-config-coupling"]
             (get-in report
                     [:docsClaimReadiness :measuredDocsProblemClassTags])))
      (is (= ["audit-scope-docs"]
             (get-in report
                     [:docsClaimReadiness :measuredDocsArchitectureClassTags])))
      (is (= ["repo-a" "repo-b" "repo-c"]
             (get-in report [:docsClaimReadiness :repoIds])))
      (is (= ["ci" "doc"]
             (get-in report [:docsClaimReadiness :sourceKindKeys])))
      (is (= 4 (get-in report [:docsClaimReadiness :docSourceKindCases])))
      (is (= 2
             (get-in report
                     [:docsClaimReadiness
                      :minimumSourceKindGroupsForDocsClaim])))
      (is (= {:completedCases true
              :hasRuns true
              :nonSyntheticCases true
              :repoBreadth true
              :docSourceKindCoverage true
              :docsClaimSourceKindBreadth true
              :declaredSourceKindCoverage true
              :measuredDocsProblemClasses true
              :measuredNonSyntheticDocsProblemClasses true
              :measuredDocsArchitectureClasses true
              :measuredNonSyntheticDocsArchitectureClasses true
              :evidenceCitationMetrics true
              :expectedEvidenceCitationMetrics true
              :expectedEvidenceCitationQuality true
              :caseExpectedEvidenceCitationQuality true
              :decisionQualityMetrics true
              :commandTelemetry true
              :benchmarkPreflight true}
             (get-in report [:docsClaimReadiness :requirements])))
      (is (= [] (get-in report [:docsClaimReadiness :warnings]))))))

(deftest agent-report-claim-readiness-requires-repo-and-source-kind-breadth
  (let [out (temp-dir "ygg-agent-report-claim-readiness-breadth")
        suite {:id "suite"
               :cases [{:id "arch-deps-1"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "arch-deps-2"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}
                       {:id "audit-docs-1"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :audit-scope-docs]}
                       {:id "audit-docs-2"
                        :repo-id "repo-a"
                        :coverage {:source-kinds [:javascript]}
                        :tags [:problem-architecture
                               :audit-scope-docs]}]}
        write-score! (fn [case-id tags]
                       (spit-json!
                        out
                        (str "suite/cases/" case-id "/agent-scores/run.score.json")
                        {:schema benchmark/agent-score-schema
                         :suite-id "suite"
                         :case-id case-id
                         :repo-id "repo-a"
                         :tags tags
                         :benchmarkPreflight passing-benchmark-preflight
                         :expectations {:evidence [{:kind "architecture-reference"
                                                    :path (str case-id ".clj")}]}
                         :agent {:agentId "codex"
                                 :mode "ygg"
                                 :topFiles [{:path (str case-id ".clj")
                                             :rank 1
                                             :evidence ["architecture-evidence"]}]
                                 :commands ["bb query architecture --project fixture"]}
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
    (doseq [case (:cases suite)]
      (write-score! (:id case) (mapv name (:tags case))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :broadArchitectureClaimSupported])))
      (is (= false
             (get-in report [:claimReadiness :requirements :repoBreadth])))
      (is (= false
             (get-in report
                     [:claimReadiness :requirements :sourceKindBreadth])))
      (is (= ["repo-a"]
             (get-in report [:claimReadiness :repoIds])))
      (is (= ["javascript"]
             (get-in report [:claimReadiness :sourceKindKeys])))
      (is (= ["Only 1 benchmark repo(s); broad real-world claims require at least 6."
              "Only 1 declared source-kind group(s); broad real-world claims require at least 7."
              "Only 1 measured problem-class group(s); broad real-world claims require at least 3."
              "Only 1 measured non-synthetic problem-class group(s); broad real-world claims require at least 3."
              "Only 2 measured architecture-class group(s); broad real-world claims require at least 3."
              "Only 2 measured non-synthetic architecture-class group(s); broad real-world claims require at least 3."]
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-claim-readiness-requires-declared-source-kind-coverage
  (let [out (temp-dir "ygg-agent-report-claim-readiness-source-kind-coverage")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score!
       out
       case
       (if (= "audit-docs-1" (:id case))
         {:coverage {:declaredSourceKinds ["doc"]
                     :missingDeclaredSourceKinds ["doc"]}}
         {})))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :broadArchitectureClaimSupported])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :declaredSourceKindCoverage])))
      (is (= true
             (get-in report [:claimReadiness :requirements :repoBreadth])))
      (is (= true
             (get-in report
                     [:claimReadiness :requirements :sourceKindBreadth])))
      (is (= {:runs 8
              :missingDeclaredSourceKindRuns 1
              :missingDeclaredSourceKindCaseIds ["audit-docs-1"]
              :missingDeclaredSourceKinds [{:kind "doc"
                                            :runs 1
                                            :cases 1
                                            :caseIds ["audit-docs-1"]}]
              :coverageExcludedGroundTruthRuns 0
              :coverageExcludedGroundTruthCaseIds []
              :coverageExcludedGroundTruthFiles 0
              :unsupportedGroundTruthRuns 0
              :unsupportedGroundTruthCaseIds []
              :unsupportedGroundTruthFiles 0}
             (:coverageDiagnostics report)))
      (is (= ["Declared source-kind coverage is incomplete; every declared source-kind group needs scoreable indexed files before broad real-world claims."]
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-claim-readiness-requires-benchmark-preflight
  (let [out (temp-dir "ygg-agent-report-benchmark-preflight")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score!
       out
       case
       {:benchmark-preflight (if (= "arch-deps-1" (:id case))
                               failing-sync-benchmark-preflight
                               passing-benchmark-preflight)}))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness :requirements :benchmarkPreflight])))
      (is (= "failed" (get-in report [:benchmarkPreflightDiagnostics :status])))
      (is (= ["arch-deps-1"]
             (get-in report [:benchmarkPreflightDiagnostics :failedCaseIds])))
      (is (= ["Yggdrasil benchmark preflight did not pass; index, inference, graph expectations, hint diagnostics, and sync/check-equivalent status must pass before making benchmark claims."]
             (get-in report [:claimReadiness :warnings])))
      (is (= {:kind "sync-check-gaps"
              :area "benchmark-readiness"
              :runs 1
              :caseIds ["arch-deps-1"]
              :message "Yggdrasil sync/check-equivalent validation gaps block benchmark claims."
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
  (let [out (temp-dir "ygg-agent-report-expected-evidence-readiness")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score! out case {:expected-evidence? false}))
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
              :runs 8
              :caseIds ["arch-deps-1"
                        "arch-deps-2"
                        "audit-docs-1"
                        "audit-docs-2"
                        "data-sql-text"
                        "data-terraform"
                        "runtime-dotnet"
                        "runtime-python"]
              :message "Benchmark cases declared expected evidence without scored expected-evidence citation metrics."}
             (->> (:improvementSummary report)
                  (filter #(= "expected-evidence-citation-metric-gaps"
                              (:kind %)))
                  first))))))

(deftest agent-report-claim-readiness-requires-aggregate-expected-evidence-quality
  (let [out (temp-dir "ygg-agent-report-expected-evidence-quality")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score! out case {:expected-evidence-rate 0.75}))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :expectedEvidenceCitationQuality])))
      (is (= true
             (get-in report
                     [:claimReadiness
                      :requirements
                      :caseExpectedEvidenceCitationQuality])))
      (is (= 0.8
             (get-in report
                     [:claimReadiness
                      :minimumExpectedEvidenceCitationRateForClaim])))
      (is (= ["Expected-evidence citation rate 0.75 is below the broad real-world claim floor 0.80."]
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-claim-readiness-requires-case-expected-evidence-quality
  (let [out (temp-dir "ygg-agent-report-case-expected-evidence-quality")
        suite {:id "suite"
               :cases broad-claim-readiness-cases}]
    (doseq [case (:cases suite)]
      (write-broad-claim-score!
       out
       case
       {:expected-evidence-rate (if (= "arch-deps-1" (:id case))
                                  0.25
                                  1.0)}))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= "not-supported" (get-in report [:claimReadiness :status])))
      (is (= true
             (get-in report
                     [:claimReadiness
                      :requirements
                      :expectedEvidenceCitationQuality])))
      (is (= false
             (get-in report
                     [:claimReadiness
                      :requirements
                      :caseExpectedEvidenceCitationQuality])))
      (is (= 0.5
             (get-in report
                     [:claimReadiness
                      :minimumCaseExpectedEvidenceCitationRateForClaim])))
      (is (= ["Some case expected-evidence citation rates are below the broad real-world claim floor 0.50: arch-deps-1."]
             (get-in report [:claimReadiness :warnings]))))))

(deftest agent-report-counts-citation-only-expected-evidence-for-readiness
  (let [out (temp-dir "ygg-agent-report-citation-evidence-readiness")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :tags [:problem-architecture
                               :architecture-dependency-flow]}]}]
    (spit-json!
     out
     "suite/cases/case-1/agent-scores/run.score.json"
     {:schema benchmark/agent-score-schema
      :suite-id "suite"
      :case-id "case-1"
      :repo-id "repo"
      :tags ["problem-architecture" "architecture-dependency-flow"]
      :benchmarkPreflight passing-benchmark-preflight
      :expectations {:citation-evidence [{:kind "runtime-config"
                                          :path "config/runtime.env"}]}
      :agent {:agentId "codex"
              :mode "ygg"
              :topFiles [{:path "src/app.clj"
                          :rank 1
                          :evidence ["context-doc:config/runtime.env"]}]
              :commands ["ygg query"]}
      :groundTruth {:changedFiles ["src/app.clj"]
                    :scoreableFiles ["src/app.clj"]
                    :unsupportedGroundTruthFiles []}
      :groundTruthRanks {:files [{:path "src/app.clj"
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
               :unsupportedGroundTruthFiles 0}})
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :allow-unverified-scores? true})]
      (is (= {:runs 1
              :expectedEvidenceRuns 1
              :expectedEvidenceCaseIds ["case-1"]
              :expectedEvidenceTargets 1
              :expectedEvidenceCitationMetricRuns 0
              :expectedEvidenceCitationMetricCaseIds []
              :missingExpectedEvidenceCitationMetricRuns 1
              :missingExpectedEvidenceCitationMetricCaseIds ["case-1"]}
             (:expectationDiagnostics report))))))
(deftest reports-exclude-obsolete-agent-score-schema
  (let [out (temp-dir "ygg-agent-report-obsolete-score-schema")
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
                {:schema "ygg.benchmark.agent-score/v1"
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :caseFingerprint fingerprint
                 :agent {:agentId "codex"
                         :mode "ygg"
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
              :obsoleteScoreSchemas ["ygg.benchmark.agent-score/v1"]
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
      (is (= "ygg.benchmark.agent-score/v1"
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
            :message "Yggdrasil-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."}
           row))))

(deftest improvement-summary-flags-agent-contract-artifact-blockers
  (let [report {:artifactDiagnostics
                {:obsoleteAgentResultContractRuns 2
                 :obsoleteAgentResultContractCaseIds ["case-a" "case-b"]
                 :obsoleteAgentResultContractVersions ["old-contract"]
                 :expectedAgentResultContractVersion "current-contract"
                 :staleAgentInputRuns 1
                 :staleAgentInputCaseIds ["case-b"]}}
        rows (into {}
                   (map (juxt :kind identity))
                   (#'benchmark-report/report-improvement-summary report))]
    (is (= {:kind "obsolete-agent-result-contract"
            :area "agent-protocol"
            :runs 2
            :caseIds ["case-a" "case-b"]
            :message "Score artifacts were produced before the current agent-result contract; use `bench agent-rerun` to rerun and rescore affected cases before making benchmark claims."
            :details [{:expectedAgentResultContractVersion "current-contract"
                       :obsoleteAgentResultContractVersions ["old-contract"]}]}
           (get rows "obsolete-agent-result-contract")))
    (is (= {:kind "stale-agent-input-fingerprints"
            :area "agent-protocol"
            :runs 1
            :caseIds ["case-b"]
            :message "Score artifacts do not match the current agent input fingerprints; use `bench agent-rerun` to rerun affected cases before making benchmark claims."}
           (get rows "stale-agent-input-fingerprints")))))

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
                         :expectedEvidenceCitationRate 0.0
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
                                   :blockingHintDiagnosticRuns 1
                                   :blockingHintDiagnosticCaseIds ["case-1"]
                                   :blockingHintDiagnosticsByKind [{:kind "source-skipped-files"
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
                :benchmarkPreflightDiagnostics {:status "failed"
                                                :runs 1
                                                :passedRuns 0
                                                :failedRuns 1
                                                :blockedRuns 1
                                                :blockedCaseIds ["case-1"]
                                                :checks [{:check "syncCheck"
                                                          :status "failed"
                                                          :failedRuns 1
                                                          :failedCaseIds ["case-1"]}]}
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
                :timings {:elapsedMs 12000
                          :warmElapsedMs 3000
                          :amortizedSetupElapsedMs 9000
                          :agentReadyElapsedMs 250}
                :results [{:case-id "case-1"
                           :parserWorker {:mode "all"
                                          :source "option"}
                           :agent {:agentId "codex"
                                   :mode "ygg"}
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
                                    :pathEvidenceCitationRate 0.0
                                    :expectedEvidenceCitationRate 0.0}}]}
        failed (benchmark/check-agent-report
                report
                {:min-cases 3
                 :min-runs 2
                 :min-file-recall-at-10 0.9
                 :min-mrr 0.8
                 :max-noise-at-20 0.5
                 :min-evidence-citation-rate 0.9
                 :min-path-evidence-citation-rate 0.9
                 :min-expected-evidence-citation-rate 0.9
                 :min-case-file-recall-at-10 0.9
                 :min-case-mrr 0.8
                 :min-case-evidence-citation-rate 0.9
                 :min-case-path-evidence-citation-rate 0.9
                 :min-case-expected-evidence-citation-rate 0.9
                 :max-case-noise-at-20 0.5
                 :max-input-hinted-cases 0
                 :max-unsupported-ground-truth-files 0
                 :max-empty-result-runs 0
                 :max-missing-predicted-file-runs 0
                 :max-commandless-runs 0
                 :max-warning-runs 0
                 :max-hint-diagnostic-runs 0
                 :max-blocking-hint-diagnostic-runs 0
                 :max-identity-mismatch-runs 0
                 :max-unverified-score-runs 0
                 :max-graph-expectation-failures 0
                 :max-benchmark-preflight-blockers 0
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
                 :min-expected-evidence-citation-rate 0.0
                 :min-case-file-recall-at-10 0.75
                 :min-case-mrr 0.5
                 :min-case-evidence-citation-rate 0.25
                 :min-case-path-evidence-citation-rate 0.0
                 :min-case-expected-evidence-citation-rate 0.0
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
                 :max-benchmark-preflight-blockers 1
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
    (is (= (:timings report) (:timings failed)))
    (is (= (:timings report) (:timings passed)))
    (is (= "failed" (:status failed)))
    (is (= #{"completed"
             "cases"
             "runs"
             "fileRecallAt10"
             "meanReciprocalRankFile"
             "noiseRatioAt20"
             "evidenceCitationRate"
             "pathEvidenceCitationRate"
             "expectedEvidenceCitationRate"
             "case.fileRecallAt10"
             "case.meanReciprocalRankFile"
             "case.evidenceCitationRate"
             "case.pathEvidenceCitationRate"
             "case.expectedEvidenceCitationRate"
             "case.noiseRatioAt20"
             "inputHintedCases"
             "unsupportedGroundTruthFiles"
             "emptyResultRuns"
             "missingPredictedFileRuns"
             "commandlessRuns"
             "warningRuns"
             "hintDiagnosticRuns"
             "blockingHintDiagnosticRuns"
             "identityMismatchRuns"
             "unverifiedScoreRuns"
             "graphExpectationFailures"
             "benchmarkPreflightBlockers"
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
            :mode "ygg"}
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
                   "case.expectedEvidenceCitationRate"
                   "case.noiseRatioAt20"
                   "case.graphExpectations"
                   "benchmarkPreflightBlockers"
                   "missingPredictedFileRuns"
                   "hintDiagnosticRuns"
                   "blockingHintDiagnosticRuns"
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
            :pathEvidenceCitationRate 0.0
            :expectedEvidenceCitationRate 0.0}
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
            :requireBroadClaimReadiness false
            :requireDocsClaimReadiness false
            :minCases 3.0
            :minRuns 2.0
            :minFileRecallAt10 0.9
            :minMeanReciprocalRankFile 0.8
            :maxNoiseRatioAt20 0.5
            :minEvidenceCitationRate 0.9
            :minPathEvidenceCitationRate 0.9
            :minExpectedEvidenceCitationRate 0.9
            :minCaseFileRecallAt10 0.9
            :minCaseMeanReciprocalRankFile 0.8
            :minCaseEvidenceCitationRate 0.9
            :minCasePathEvidenceCitationRate 0.9
            :minCaseExpectedEvidenceCitationRate 0.9
            :maxCaseNoiseRatioAt20 0.5
            :maxInputHintedCases 0.0
            :maxUnsupportedGroundTruthFiles 0.0
            :maxEmptyResultRuns 0.0
            :maxMissingPredictedFileRuns 0.0
            :maxCommandlessRuns 0.0
            :maxWarningRuns 0.0
            :maxHintDiagnosticRuns 0.0
            :maxBlockingHintDiagnosticRuns 0.0
            :maxIdentityMismatchRuns 0.0
            :maxUnverifiedScoreRuns 0.0
            :maxGraphExpectationFailures 0.0
            :maxBenchmarkPreflightBlockers 0.0
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

(deftest agent-check-enforces-repo-and-source-kind-coverage-thresholds
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 3
                :completed 3
                :runs 3
                :missing []
                :scores {}
                :coverage {:scoreableFilesByKind [{:kind "code"
                                                   :cases 1
                                                   :scoreableFiles 4}
                                                  {:kind "doc"
                                                   :cases 2
                                                   :scoreableFiles 5}]}
                :claimReadiness {:status "not-supported"
                                 :broadArchitectureClaimSupported false
                                 :repoIds ["repo-a" "repo-b"]
                                 :sourceKindKeys ["doc"]
                                 :measuredProblemClassTags ["problem-docs"]
                                 :measuredArchitectureClassTags ["audit-docs"]
                                 :requirements {:repoBreadth true
                                                :sourceKindBreadth false}
                                 :warnings ["Only one broad source-kind group is measured."]}
                :results [{:case-id "case-1"
                           :repo-id "repo-a"}
                          {:case-id "case-2"
                           :repo-id "repo-b"}
                          {:case-id "case-3"
                           :repo-id "repo-b"}]}
        failed (benchmark/check-agent-report
                report
                {:min-repos 3
                 :min-source-kind-cases {"doc" 3
                                         "sql" 1}})
        passed (benchmark/check-agent-report
                report
                {:min-repos 2
                 :min-source-kind-cases {"code" 1
                                         "doc" 2}})]
    (is (= "failed" (:status failed)))
    (is (= #{"repos" "sourceKindCases.doc" "sourceKindCases.sql"}
           (set (map :metric (:failures failed)))))
    (is (= {:metric "repos"
            :operator ">="
            :expected 3.0
            :actual 2.0
            :repoIds ["repo-a" "repo-b"]}
           (select-keys (first (filter #(= "repos" (:metric %))
                                       (:failures failed)))
                        [:metric :operator :expected :actual :repoIds])))
    (is (= {:metric "sourceKindCases.doc"
            :operator ">="
            :expected 3.0
            :actual 2.0
            :kind "doc"}
           (select-keys (first (filter #(= "sourceKindCases.doc" (:metric %))
                                       (:failures failed)))
                        [:metric :operator :expected :actual :kind])))
    (is (= {:metric "sourceKindCases.sql"
            :operator ">="
            :expected 1.0
            :actual 0.0
            :kind "sql"}
           (select-keys (first (filter #(= "sourceKindCases.sql" (:metric %))
                                       (:failures failed)))
                        [:metric :operator :expected :actual :kind])))
    (is (= "failed" (get-in failed [:thresholdGate :status])))
    (is (= ["repos" "sourceKindCases.doc" "sourceKindCases.sql"]
           (get-in failed [:thresholdGate :failedMetrics])))
    (is (= {"code" 1
            "doc" 2}
           (get-in failed [:thresholdGate :evidence :sourceKindCases])))
    (is (= {:status "not-supported"
            :supported false
            :failedRequirements [:sourceKindBreadth]
            :warnings ["Only one broad source-kind group is measured."]
            :broadArchitectureClaimSupported false
            :repoIds ["repo-a" "repo-b"]
            :sourceKindKeys ["doc"]
            :measuredProblemClassTags ["problem-docs"]
            :measuredArchitectureClassTags ["audit-docs"]}
           (get-in failed [:thresholdGate :broadClaimReadiness])))
    (is (= "passed" (:status passed)))
    (is (= "passed" (get-in passed [:thresholdGate :status])))
    (is (= [] (get-in passed [:thresholdGate :failedMetrics])))
    (is (= "not-supported"
           (get-in passed
                   [:thresholdGate :broadClaimReadiness :status])))
    (is (empty? (:failures passed)))))

(deftest agent-check-can-require-broad-claim-readiness
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 1
                :missing []
                :scores {}
                :claimReadiness {:status "not-supported"
                                 :broadArchitectureClaimSupported false
                                 :requirements {:sourceKindBreadth false}
                                 :warnings ["Only one source-kind group."]}
                :results [{:case-id "case-1"
                           :repo-id "repo-a"}]}
        failed (benchmark/check-agent-report
                report
                {:require-broad-claim-readiness? true})
        passed (benchmark/check-agent-report
                (assoc report
                       :claimReadiness {:status "supported"
                                        :broadArchitectureClaimSupported true
                                        :requirements {:sourceKindBreadth true}
                                        :warnings []})
                {:require-broad-claim-readiness? true})
        missing (benchmark/check-agent-report
                 (dissoc report :claimReadiness)
                 {:require-broad-claim-readiness? true})]
    (is (= "failed" (:status failed)))
    (is (= ["broadClaimReadiness"]
           (mapv :metric (:failures failed))))
    (is (= {:status "not-supported"
            :supported false
            :failedRequirements [:sourceKindBreadth]
            :warnings ["Only one source-kind group."]
            :broadArchitectureClaimSupported false}
           (get-in failed [:failures 0 :broadClaimReadiness])))
    (is (= true (get-in failed [:thresholds :requireBroadClaimReadiness])))
    (is (= "passed" (:status passed)))
    (is (= [] (:failures passed)))
    (is (= "failed" (:status missing)))
    (is (= "unknown"
           (get-in missing
                   [:failures 0 :broadClaimReadiness :status])))))

(deftest agent-check-can-require-docs-claim-readiness
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 1
                :missing []
                :scores {}
                :docsClaimReadiness {:status "not-supported"
                                     :docsHandlingClaimSupported false
                                     :requirements {:docSourceKindCoverage false}
                                     :warnings ["Only one docs case."]}
                :results [{:case-id "case-1"
                           :repo-id "repo-a"}]}
        failed (benchmark/check-agent-report
                report
                {:require-docs-claim-readiness? true})
        passed (benchmark/check-agent-report
                (assoc report
                       :docsClaimReadiness
                       {:status "supported"
                        :docsHandlingClaimSupported true
                        :requirements {:docSourceKindCoverage true}
                        :warnings []})
                {:require-docs-claim-readiness? true})
        missing (benchmark/check-agent-report
                 (dissoc report :docsClaimReadiness)
                 {:require-docs-claim-readiness? true})]
    (is (= "failed" (:status failed)))
    (is (= ["docsClaimReadiness"]
           (mapv :metric (:failures failed))))
    (is (= {:status "not-supported"
            :supported false
            :failedRequirements [:docSourceKindCoverage]
            :warnings ["Only one docs case."]
            :docsHandlingClaimSupported false}
           (get-in failed [:failures 0 :docsClaimReadiness])))
    (is (= true (get-in failed [:thresholds :requireDocsClaimReadiness])))
    (is (= "failed" (get-in failed [:thresholdGate :status])))
    (is (= "not-supported"
           (get-in failed
                   [:thresholdGate :docsClaimReadiness :status])))
    (is (= "passed" (:status passed)))
    (is (= [] (:failures passed)))
    (is (= "failed" (:status missing)))
    (is (= "unknown"
           (get-in missing
                   [:failures 0 :docsClaimReadiness :status])))))

(deftest checks-agent-report-decision-thresholds
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 1
                :missing []
                :scores {:decisionF1 0.4
                         :decisionEvidenceCitationRate 0.25}
                :decisionDiagnostics {:configuredRuns 1
                                      :configuredCaseIds ["case-1"]
                                      :gapRuns 1
                                      :gapCaseIds ["case-1"]
                                      :missingDecisionRuns 1
                                      :missingDecisionCaseIds ["case-1"]}
                :results [{:case-id "case-1"
                           :agent {:agentId "codex"
                                   :mode "ygg"}
                           :scores {:decisionF1 0.4
                                    :decisionEvidenceCitationRate 0.25}
                           :decision {:missingDecision true}}]}
        failed (benchmark/check-agent-report
                report
                {:min-decision-f1 0.8
                 :min-decision-evidence-citation-rate 0.75
                 :min-case-decision-f1 0.9
                 :max-missing-decision-runs 0})]
    (is (= "failed" (:status failed)))
    (is (= #{"decisionF1"
             "decisionEvidenceCitationRate"
             "case.decisionF1"
             "missingDecisionRuns"}
           (set (map :metric (:failures failed)))))
    (is (= {:decisionF1 0.4
            :decisionEvidenceCitationRate 0.25}
           (get-in failed [:caseDiagnostics 0 :scores])))
    (is (= ["case-1"]
           (->> (:failures failed)
                (filter #(= "missingDecisionRuns" (:metric %)))
                first
                :case-ids)))))

(deftest checks-agent-report-token-thresholds
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 2
                :completed 2
                :runs 2
                :missing []
                :scores {}
                :agentDiagnostics {:tokenTelemetry {:inputTokens 1700
                                                    :outputTokens 700
                                                    :totalTokens 2400
                                                    :costUsd 0.12}}
                :results [{:case-id "case-1"
                           :agent {:agentId "codex"
                                   :mode "ygg"
                                   :tokenUsage {:inputTokens 1000
                                                :outputTokens 500
                                                :totalTokens 1500
                                                :costUsd 0.08}}
                           :scores {}}
                          {:case-id "case-2"
                           :agent {:agentId "codex"
                                   :mode "ygg"
                                   :tokenUsage {:inputTokens 700
                                                :outputTokens 200
                                                :totalTokens 900
                                                :costUsd 0.04}}
                           :scores {}}]}
        failed (benchmark/check-agent-report
                report
                {:max-total-tokens 2000
                 :max-input-tokens 1500
                 :max-output-tokens 600
                 :max-cost-usd 0.1
                 :max-case-total-tokens 1000
                 :max-case-input-tokens 900
                 :max-case-output-tokens 400
                 :max-case-cost-usd 0.05})
        passed (benchmark/check-agent-report
                report
                {:max-total-tokens 2400
                 :max-input-tokens 1700
                 :max-output-tokens 700
                 :max-cost-usd 0.12
                 :max-case-total-tokens 1500
                 :max-case-input-tokens 1000
                 :max-case-output-tokens 500
                 :max-case-cost-usd 0.08})
        missing (benchmark/check-agent-report
                 (-> report
                     (dissoc :agentDiagnostics)
                     (assoc :results [{:case-id "case-1"
                                       :agent {:agentId "codex"
                                               :mode "ygg"}
                                       :scores {}}]))
                 {:max-total-tokens 1
                  :max-case-total-tokens 1})]
    (is (= "failed" (:status failed)))
    (is (= #{"totalTokens"
             "inputTokens"
             "outputTokens"
             "costUsd"
             "case.totalTokens"
             "case.inputTokens"
             "case.outputTokens"
             "case.costUsd"}
           (set (map :metric (:failures failed)))))
    (is (= {:inputTokens 1000
            :outputTokens 500
            :totalTokens 1500
            :costUsd 0.08}
           (get-in failed [:caseDiagnostics 0 :tokenUsage])))
    (is (= #{"case.totalTokens"
             "case.inputTokens"
             "case.outputTokens"
             "case.costUsd"}
           (set (map :metric
                     (get-in failed [:caseDiagnostics 0 :failures])))))
    (is (= [] (get-in failed [:caseDiagnostics 1 :failures])))
    (is (= {:requireComplete true
            :allowDuplicateRuns false
            :requireBroadClaimReadiness false
            :requireDocsClaimReadiness false
            :maxTotalTokens 2000.0
            :maxInputTokens 1500.0
            :maxOutputTokens 600.0
            :maxCostUsd 0.1
            :maxCaseTotalTokens 1000.0
            :maxCaseInputTokens 900.0
            :maxCaseOutputTokens 400.0
            :maxCaseCostUsd 0.05}
           (:thresholds failed)))
    (is (= "passed" (:status passed)))
    (is (= "failed" (:status missing)))
    (is (= #{"totalTokens" "case.totalTokens"}
           (set (map :metric (:failures missing)))))
    (is (every? #(re-find #"missing token" (:message %))
                (:failures missing)))))

(deftest checks-agent-report-duplicate-runs
  (let [result {:case-id "case-1"
                :agentResultPath "/tmp/run-1.json"
                :agent {:agentId "codex"
                        :mode "ygg"}
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
             :mode "ygg"
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
                                                        :yggCommandCount 2
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
                                                         :yggCommandCount 2
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
                                         :message "Yggdrasil hints contained diagnostics."}]
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
