(ns ygg.benchmark-check
  (:require [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-report :as benchmark-report]
            [ygg.benchmark-targets :as benchmark-targets]
            [ygg.extract :as extract]))

(def agent-check-schema
  "ygg.benchmark.agent-check/v1")

(defn- threshold
  [opts opt-key artifact-key]
  (when-some [value (get opts opt-key)]
    [artifact-key (double value)]))
(defn- agent-check-thresholds
  [opts]
  (cond-> (into {:requireComplete (not (:allow-missing? opts))
                 :allowDuplicateRuns (boolean (:allow-duplicate-runs? opts))}
                (keep (fn [[opt-key artifact-key]]
                        (threshold opts opt-key artifact-key)))
                [[:min-cases :minCases]
                 [:min-runs :minRuns]
                 [:min-file-recall-at-5 :minFileRecallAt5]
                 [:min-file-recall-at-10 :minFileRecallAt10]
                 [:min-file-recall-at-20 :minFileRecallAt20]
                 [:min-mrr :minMeanReciprocalRankFile]
                 [:max-noise-at-20 :maxNoiseRatioAt20]
                 [:min-evidence-citation-rate :minEvidenceCitationRate]
                 [:min-path-evidence-citation-rate :minPathEvidenceCitationRate]
                 [:min-decision-f1 :minDecisionF1]
                 [:min-decision-evidence-citation-rate
                  :minDecisionEvidenceCitationRate]
                 [:max-total-tokens :maxTotalTokens]
                 [:max-input-tokens :maxInputTokens]
                 [:max-output-tokens :maxOutputTokens]
                 [:max-cost-usd :maxCostUsd]
                 [:min-case-file-recall-at-5 :minCaseFileRecallAt5]
                 [:min-case-file-recall-at-10 :minCaseFileRecallAt10]
                 [:min-case-file-recall-at-20 :minCaseFileRecallAt20]
                 [:min-case-mrr :minCaseMeanReciprocalRankFile]
                 [:min-case-evidence-citation-rate :minCaseEvidenceCitationRate]
                 [:min-case-path-evidence-citation-rate
                  :minCasePathEvidenceCitationRate]
                 [:min-case-decision-f1 :minCaseDecisionF1]
                 [:max-case-total-tokens :maxCaseTotalTokens]
                 [:max-case-input-tokens :maxCaseInputTokens]
                 [:max-case-output-tokens :maxCaseOutputTokens]
                 [:max-case-cost-usd :maxCaseCostUsd]
                 [:max-case-noise-at-20 :maxCaseNoiseRatioAt20]
                 [:max-input-hinted-cases :maxInputHintedCases]
                 [:max-unsupported-ground-truth-files :maxUnsupportedGroundTruthFiles]
                 [:max-empty-result-runs :maxEmptyResultRuns]
                 [:max-missing-predicted-file-runs
                  :maxMissingPredictedFileRuns]
                 [:max-commandless-runs :maxCommandlessRuns]
                 [:max-warning-runs :maxWarningRuns]
                 [:max-missing-decision-runs :maxMissingDecisionRuns]
                 [:max-hint-diagnostic-runs :maxHintDiagnosticRuns]
                 [:max-identity-mismatch-runs :maxIdentityMismatchRuns]
                 [:max-unverified-score-runs :maxUnverifiedScoreRuns]
                 [:max-graph-expectation-failures :maxGraphExpectationFailures]
                 [:max-maintenance-preflight-blockers
                  :maxMaintenancePreflightBlockers]
                 [:max-missing-declared-source-kind-runs :maxMissingDeclaredSourceKindRuns]
                 [:max-missed-runs :maxMissedRuns]
                 [:max-context-rank-missing-runs :maxContextRankMissingRuns]
                 [:max-missed-but-present-in-context-runs
                  :maxMissedButPresentInContextRuns]
                 [:max-missed-and-absent-from-context-runs
                  :maxMissedAndAbsentFromContextRuns]
                 [:max-ranked-outside-top-5-runs :maxRankedOutsideTop5Runs]
                 [:max-ranked-outside-top-10-runs :maxRankedOutsideTop10Runs]
                 [:max-ranked-outside-top-20-runs :maxRankedOutsideTop20Runs]
                 [:max-improvement-target-runs :maxImprovementTargetRuns]
                 [:max-active-stage-ms :maxActiveStageMs]
                 [:max-parser-worker-profiles :maxParserWorkerProfiles]
                 [:min-measured-problem-classes :minMeasuredProblemClasses]
                 [:min-measured-architecture-classes
                  :minMeasuredArchitectureClasses]])
    (seq (:max-improvement-target-kind-runs opts))
    (assoc :maxImprovementTargetKindRuns
           (into (sorted-map)
                 (:max-improvement-target-kind-runs opts)))

    (extract/normalize-parser-worker-mode (:require-parser-worker opts))
    (assoc :requiredParserWorker
           (extract/normalize-parser-worker-mode (:require-parser-worker opts)))))
(defn- metric-failure
  [metric operator expected actual]
  {:metric metric
   :operator operator
   :expected expected
   :actual actual})
(defn- min-failure
  [report threshold-key metric-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report :scores metric-key] 0.0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))
(defn- max-failure
  [report threshold-key metric-path metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report (into [:report] metric-path) 0.0))]
      (when (> actual expected)
        (metric-failure metric-label "<=" expected actual)))))
(defn- max-required-failure
  [report threshold-key metric-path metric-label missing-message]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (if-some [actual-value (get-in report (into [:report] metric-path))]
      (let [actual (double actual-value)]
        (when (> actual expected)
          (metric-failure metric-label "<=" expected actual)))
      (merge (metric-failure metric-label "<=" expected nil)
             {:message missing-message}))))
(defn- min-count-failure
  [report threshold-key report-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report report-key] 0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))
(defn- completeness-failures
  [report]
  (let [artifact-policy (get-in report [:report :artifactPolicy])
        zero-run-failure (cond-> {:metric "runs"
                                  :operator ">"
                                  :expected 0
                                  :actual 0}
                           (pos? (long (:excludedUnverifiedRuns
                                        artifact-policy
                                        0)))
                           (merge
                            {:matchedRuns (:matchedRuns artifact-policy)
                             :excludedRuns (:excludedRuns artifact-policy)
                             :excludedUnverifiedRuns (:excludedUnverifiedRuns
                                                      artifact-policy)
                             :excludedCaseIds (:excludedCaseIds
                                               artifact-policy)
                             :message "No current agent score artifacts matched the selected suite, case, and mode. Matching artifacts were excluded because their score schema, agent result schema, or case fingerprint is stale; regenerate the benchmark scores or pass --allow-unverified-scores for exploratory reporting."})

                           (not (pos? (long (:excludedUnverifiedRuns
                                             artifact-policy
                                             0))))
                           (assoc
                            :message
                            "No agent score artifacts matched the selected suite, case, and mode."))]
    (vec
     (concat
      (keep (fn [[threshold-key report-key metric-label]]
              (min-count-failure report threshold-key report-key metric-label))
            [[:minCases :cases "cases"]
             [:minRuns :runs "runs"]])
      (cond-> []
        (and (nil? (get-in report [:thresholds :minRuns]))
             (zero? (long (get-in report [:report :runs] 0))))
        (conj zero-run-failure)

        (and (get-in report [:thresholds :requireComplete])
             (seq (get-in report [:report :missing])))
        (conj {:metric "completed"
               :operator "="
               :expected (get-in report [:report :cases])
               :actual (get-in report [:report :completed])
               :missing (get-in report [:report :missing])
               :message "Some selected cases do not have matching agent score artifacts."}))))))
(defn- run-identity
  [result]
  {:case-id (:case-id result)
   :agentId (or (get-in result [:agent :agentId]) "unknown")
   :mode (or (get-in result [:agent :mode]) "unknown")})
(defn- duplicate-run-failures
  [check]
  (when-not (get-in check [:thresholds :allowDuplicateRuns])
    (->> (get-in check [:report :results])
         (group-by run-identity)
         (keep (fn [[run-key rows]]
                 (when (> (count rows) 1)
                   (merge (metric-failure "duplicateRuns" "=" 1 (count rows))
                          run-key
                          {:agentResultPaths (mapv :agentResultPath rows)
                           :message "Multiple agent score artifacts matched one case, agent, and mode."}))))
         vec)))
(defn- case-metric-failure
  [result metric operator expected actual]
  (assoc (metric-failure metric operator expected actual)
         :case-id (:case-id result)
         :agentId (get-in result [:agent :agentId])
         :mode (get-in result [:agent :mode])))
(defn- case-min-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (< actual expected)
        (case-metric-failure result metric-label ">=" expected actual)))))
(defn- case-max-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (> actual expected)
        (case-metric-failure result metric-label "<=" expected actual)))))
(defn- result-token-usage
  [result]
  (or (get-in result [:agent :tokenUsage])
      (get-in result [:agentOutput :tokenUsage])))
(defn- case-token-max-failure
  [check result threshold-key token-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (if-some [actual-value (get (result-token-usage result) token-key)]
      (let [actual (double actual-value)]
        (when (> actual expected)
          (case-metric-failure result metric-label "<=" expected actual)))
      (assoc (case-metric-failure result metric-label "<=" expected nil)
             :message
             "Case result is missing token usage required by this token budget gate."))))
(defn- case-threshold-failures
  [check]
  (let [results (get-in check [:report :results])]
    (vec
     (mapcat
      (fn [result]
        (concat
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-min-failure check result threshold-key metric-key metric-label))
               [[:minCaseFileRecallAt5 :fileRecallAt5 "case.fileRecallAt5"]
                [:minCaseFileRecallAt10 :fileRecallAt10 "case.fileRecallAt10"]
                [:minCaseFileRecallAt20 :fileRecallAt20 "case.fileRecallAt20"]
                [:minCaseMeanReciprocalRankFile
                 :meanReciprocalRankFile
                 "case.meanReciprocalRankFile"]
                [:minCaseEvidenceCitationRate
                 :evidenceCitationRate
                 "case.evidenceCitationRate"]
                [:minCasePathEvidenceCitationRate
                 :pathEvidenceCitationRate
                 "case.pathEvidenceCitationRate"]
                [:minCaseDecisionF1
                 :decisionF1
                 "case.decisionF1"]])
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-max-failure check result threshold-key metric-key metric-label))
               [[:maxCaseNoiseRatioAt20 :noiseRatioAt20 "case.noiseRatioAt20"]])
         (keep (fn [[threshold-key token-key metric-label]]
                 (case-token-max-failure check
                                         result
                                         threshold-key
                                         token-key
                                         metric-label))
               [[:maxCaseTotalTokens :totalTokens "case.totalTokens"]
                [:maxCaseInputTokens :inputTokens "case.inputTokens"]
                [:maxCaseOutputTokens :outputTokens "case.outputTokens"]
                [:maxCaseCostUsd :costUsd "case.costUsd"]])))
      results))))
(defn- active-stage-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxActiveStageMs])]
    (->> (get-in check [:report :caseProgress])
         (keep (fn [progress]
                 (let [actual (double (:activeElapsedMs progress 0))]
                   (when (> actual expected)
                     (merge (metric-failure "activeStageElapsedMs"
                                            "<="
                                            expected
                                            actual)
                            (select-keys progress
                                         [:case-id
                                          :repo-id
                                          :status
                                          :activeStage])
                            {:message "Active benchmark stage exceeded the configured duration."})))))
         vec)))
(defn- aggregate-token-failures
  [check]
  (->> [[:maxTotalTokens
         [:agentDiagnostics :tokenTelemetry :totalTokens]
         "totalTokens"]
        [:maxInputTokens
         [:agentDiagnostics :tokenTelemetry :inputTokens]
         "inputTokens"]
        [:maxOutputTokens
         [:agentDiagnostics :tokenTelemetry :outputTokens]
         "outputTokens"]
        [:maxCostUsd
         [:agentDiagnostics :tokenTelemetry :costUsd]
         "costUsd"]]
       (keep (fn [[threshold-key metric-path metric-label]]
               (max-required-failure
                check
                threshold-key
                metric-path
                metric-label
                "Agent report is missing token telemetry required by this token budget gate.")))
       vec))
(defn- empty-result-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxEmptyResultRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :emptyResultRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "emptyResultRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :emptyResultCaseIds])
                 :message "Some agent score artifacts produced no rankable suspected files."})]))))
(defn- warning-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxWarningRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :warningRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "warningRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :warningCaseIds])
                 :message "Some agent score artifacts contain scorer or agent warnings."})]))))
(defn- hint-diagnostic-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxHintDiagnosticRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :hintDiagnosticRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "hintDiagnosticRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :hintDiagnosticCaseIds])
                 :hintDiagnosticsByKind (get-in check
                                                [:report
                                                 :agentDiagnostics
                                                 :hintDiagnosticsByKind])
                 :message "Some Yggdrasil hint artifacts reported help-quality diagnostics."})]))))
(defn- identity-mismatch-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxIdentityMismatchRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :identityMismatchRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "identityMismatchRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :identityMismatchCaseIds])
                 :identityMismatches (get-in check
                                             [:report
                                              :agentDiagnostics
                                              :identityMismatches])
                 :message "Some agent score artifacts reported a schema, case id, or case fingerprint that does not match the prepared case."})]))))
(defn- missing-predicted-file-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxMissingPredictedFileRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :agentDiagnostics
                                  :missingPredictedFileRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "missingPredictedFileRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :missingPredictedFileCaseIds])
                 :missingPredictedFiles (get-in check
                                                [:report
                                                 :agentDiagnostics
                                                 :missingPredictedFiles])
                 :message "Some agent score artifacts predicted paths that do not exist in the base checkout."})]))))
(defn- commandless-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxCommandlessRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :commandlessRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "commandlessRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :commandlessCaseIds])
                 :message "Some agent score artifacts did not cite any commands."})]))))
(defn- missing-decision-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxMissingDecisionRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :decisionDiagnostics
                                  :missingDecisionRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "missingDecisionRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :decisionDiagnostics
                                    :missingDecisionCaseIds])
                 :message "Some decision benchmark cases are missing agent decision output."})]))))
(defn- unverified-score-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxUnverifiedScoreRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :artifactDiagnostics
                                  :unverifiedScoreRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "unverifiedScoreRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :artifactDiagnostics
                                    :unverifiedScoreCaseIds])
                 :obsoleteScoreSchemaCaseIds (get-in check
                                                     [:report
                                                      :artifactDiagnostics
                                                      :obsoleteScoreSchemaCaseIds])
                 :obsoleteScoreSchemas (get-in check
                                               [:report
                                                :artifactDiagnostics
                                                :obsoleteScoreSchemas])
                 :obsoleteAgentResultSchemaCaseIds (get-in check
                                                           [:report
                                                            :artifactDiagnostics
                                                            :obsoleteAgentResultSchemaCaseIds])
                 :obsoleteAgentResultSchemas (get-in check
                                                     [:report
                                                      :artifactDiagnostics
                                                      :obsoleteAgentResultSchemas])
                 :expectedScoreSchema (get-in check
                                              [:report
                                               :artifactDiagnostics
                                               :expectedScoreSchema])
                 :expectedAgentResultSchema (get-in check
                                                    [:report
                                                     :artifactDiagnostics
                                                     :expectedAgentResultSchema])
                 :message "Some agent score artifacts are legacy, use an obsolete score schema, or do not match the current suite case fingerprint."})]))))
(defn- localization-diagnostic-failures
  [check]
  (->> [[:maxMissedRuns
         :missedRuns
         :missedCaseIds
         "missedRuns"
         "Some runs missed at least one scoreable localization file."]
        [:maxContextRankMissingRuns
         :contextRankMissingRuns
         :contextRankMissingCaseIds
         "contextRankMissingRuns"
         "Some Yggdrasil-mode score artifacts did not include context ground-truth ranks."]
        [:maxMissedButPresentInContextRuns
         :missedButPresentInContextRuns
         :missedButPresentInContextCaseIds
         "missedButPresentInContextRuns"
         "Some runs missed scoreable localization files that were present in the Yggdrasil context packet."]
        [:maxMissedAndAbsentFromContextRuns
         :missedAndAbsentFromContextRuns
         :missedAndAbsentFromContextCaseIds
         "missedAndAbsentFromContextRuns"
         "Some runs missed scoreable localization files that were absent from the Yggdrasil context packet."]
        [:maxRankedOutsideTop5Runs
         :rankedOutsideTop5Runs
         :rankedOutsideTop5CaseIds
         "rankedOutsideTop5Runs"
         "Some runs found scoreable localization files only outside the top 5."]
        [:maxRankedOutsideTop10Runs
         :rankedOutsideTop10Runs
         :rankedOutsideTop10CaseIds
         "rankedOutsideTop10Runs"
         "Some runs found scoreable localization files only outside the top 10."]
        [:maxRankedOutsideTop20Runs
         :rankedOutsideTop20Runs
         :rankedOutsideTop20CaseIds
         "rankedOutsideTop20Runs"
         "Some runs found scoreable localization files only outside the top 20."]]
       (keep (fn [[threshold-key actual-key case-ids-key metric message]]
               (when-some [expected (get-in check [:thresholds threshold-key])]
                 (let [actual (double (get-in check
                                              [:report
                                               :localizationDiagnostics
                                               actual-key]
                                              0))]
                   (when (> actual expected)
                     (merge (metric-failure metric "<=" expected actual)
                            {:case-ids (get-in check
                                               [:report
                                                :localizationDiagnostics
                                                case-ids-key])
                             :message message}))))))
       vec))
(defn- expand-case-id-failures
  [failures]
  (mapcat (fn [failure]
            (if (seq (:case-ids failure))
              (map #(-> failure
                        (assoc :case-id %)
                        (dissoc :case-ids))
                   (:case-ids failure))
              [failure]))
          failures))
(defn- coverage-diagnostic-failures
  [check]
  (when-some [expected (get-in check
                               [:thresholds
                                :maxMissingDeclaredSourceKindRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :coverageDiagnostics
                                  :missingDeclaredSourceKindRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "missingDeclaredSourceKindRuns"
                                "<="
                                expected
                                actual)
                {:case-ids (get-in check
                                   [:report
                                    :coverageDiagnostics
                                    :missingDeclaredSourceKindCaseIds])
                 :missingDeclaredSourceKinds (get-in check
                                                     [:report
                                                      :coverageDiagnostics
                                                      :missingDeclaredSourceKinds])
                 :message "Some runs declared source kinds that had no scoreable coverage."})]))))
(defn- improvement-target-case-ids
  [targets]
  (->> targets
       (mapcat :caseIds)
       distinct
       sort
       vec))
(defn- improvement-target-failures
  [check]
  (let [summary (get-in check [:report :improvementSummary])
        runs (benchmark-targets/target-runs (:report check))
        runs-by-kind (benchmark-targets/target-runs-by-kind (:report check))
        aggregate-failure (when-some [expected (get-in check
                                                       [:thresholds
                                                        :maxImprovementTargetRuns])]
                            (let [actual (double runs)]
                              (when (> actual expected)
                                (merge (metric-failure "improvementTargetRuns"
                                                       "<="
                                                       expected
                                                       actual)
                                       {:case-ids (improvement-target-case-ids
                                                   summary)
                                        :improvementTargets summary
                                        :message "Agent report contains more remediation target runs than allowed."}))))
        kind-failures (keep
                       (fn [[kind expected]]
                         (let [actual (double (get runs-by-kind kind 0))
                               targets (filterv #(= kind (:kind %)) summary)]
                           (when (> actual expected)
                             (merge (metric-failure
                                     (str "improvementTargetRuns." kind)
                                     "<="
                                     expected
                                     actual)
                                    {:case-ids (improvement-target-case-ids
                                                targets)
                                     :improvementTargets targets
                                     :message "Agent report contains more remediation target runs for this kind than allowed."}))))
                       (get-in check
                               [:thresholds :maxImprovementTargetKindRuns]))]
    (vec (cond-> kind-failures
           aggregate-failure
           (conj aggregate-failure)))))
(defn- graph-expectation-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxGraphExpectationFailures])]
    (let [failed-results (filter #(= "failed"
                                     (get-in % [:graphExpectations :status]))
                                 (get-in check [:report :results]))
          actual (double (count failed-results))]
      (when (> actual expected)
        (into
         [(merge (metric-failure "graphExpectationFailures" "<=" expected actual)
                 {:case-ids (->> failed-results
                                 (map :case-id)
                                 distinct
                                 sort
                                 vec)
                  :message "Some graph/evidence/chunk benchmark expectations failed."})]
         (map (fn [result]
                (merge (case-metric-failure result
                                            "case.graphExpectations"
                                            "="
                                            "passed"
                                            "failed")
                       {:summary (get-in result [:graphExpectations :summary])
                        :message "Graph/evidence/chunk benchmark expectations failed for this run."}))
              failed-results))))))
(defn- maintenance-preflight-failures
  [check]
  (when-some [expected (get-in check
                               [:thresholds
                                :maxMaintenancePreflightBlockers])]
    (let [diagnostics (get-in check
                              [:report :maintenancePreflightDiagnostics])
          actual (double (:blockedRuns diagnostics 0))]
      (when (> actual expected)
        [(merge (metric-failure "maintenancePreflightBlockers"
                                "<="
                                expected
                                actual)
                {:case-ids (:blockedCaseIds diagnostics)
                 :checks (:checks diagnostics)
                 :message "Some Yggdrasil-mode runs failed maintained-graph preflight checks required before making claims."})]))))
(defn- measured-problem-class-count
  [check class-key]
  (count (filter #(= "measured" (:claimStatus %))
                 (get-in check [:report :problemClasses class-key]))))
(defn- problem-class-claim-failure
  [check threshold-key class-key metric message]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (measured-problem-class-count check class-key))]
      (when (< actual expected)
        (merge (metric-failure metric ">=" expected actual)
               {:classes (get-in check [:report :problemClasses class-key])
                :minimumCasesForClassClaim (get-in check
                                                   [:report
                                                    :problemClasses
                                                    :minimumCasesForClassClaim])
                :message message})))))
(defn- problem-class-claim-failures
  [check]
  (keep identity
        [(problem-class-claim-failure
          check
          :minMeasuredProblemClasses
          :classes
          "measuredProblemClasses"
          "Fewer problem-class groups are measured than required for the benchmark claim.")
         (problem-class-claim-failure
          check
          :minMeasuredArchitectureClasses
          :architectureClasses
          "measuredArchitectureClasses"
          "Fewer architecture-class groups are measured than required for the benchmark claim.")]))
(defn- parser-worker-profile-failures
  [check]
  (let [profiles (vec (get-in check [:report :parserWorkers]))
        failures (cond-> []
                   (when-some [expected (get-in check
                                                [:thresholds
                                                 :maxParserWorkerProfiles])]
                     (> (count profiles) expected))
                   (conj (merge (metric-failure "parserWorkerProfiles"
                                                "<="
                                                (get-in check
                                                        [:thresholds
                                                         :maxParserWorkerProfiles])
                                                (count profiles))
                                {:profiles profiles
                                 :message "Agent report contains more parser-worker profiles than allowed."}))

                   (when-let [expected (get-in check
                                               [:thresholds
                                                :requiredParserWorker])]
                     (seq (remove #(= expected (:mode %)) profiles)))
                   (conj (merge (metric-failure "parserWorker"
                                                "="
                                                (get-in check
                                                        [:thresholds
                                                         :requiredParserWorker])
                                                (->> profiles
                                                     (map :mode)
                                                     distinct
                                                     sort
                                                     vec))
                                {:profiles profiles
                                 :message "Agent report contains parser-worker modes that do not match the required mode."})))]
    failures))
(def ^:private case-diagnostic-score-keys
  [:fileRecallAt5
   :fileRecallAt10
   :fileRecallAt20
   :meanReciprocalRankFile
   :noiseRatioAt20
   :evidenceCitationRate
   :pathEvidenceCitationRate
   :decisionRecall
   :decisionPrecision
   :decisionF1
   :decisionEvidenceCitationRate
   :changedFiles
   :scoreableChangedFiles
   :unsupportedGroundTruthFiles])
(defn- failure-matches-result?
  [failure result]
  (and (= (:case-id failure) (:case-id result))
       (or (nil? (:agentId failure))
           (= (:agentId failure) (get-in result [:agent :agentId])))
       (or (nil? (:mode failure))
           (= (:mode failure) (get-in result [:agent :mode])))))
(defn- result-case-diagnostic
  [failures result]
  (let [result-failures (filterv #(failure-matches-result? % result) failures)]
    (cond-> {:case-id (:case-id result)
             :agentId (get-in result [:agent :agentId])
             :mode (get-in result [:agent :mode])
             :parserWorker (:parserWorker result)
             :agentResultPath (:agentResultPath result)
             :status (if (seq result-failures) "failed" "passed")
             :scores (select-keys (:scores result) case-diagnostic-score-keys)
             :localization (:localization result)
             :failures result-failures}
      (:agentOutput result)
      (assoc :agentOutput (:agentOutput result))

      (result-token-usage result)
      (assoc :tokenUsage (result-token-usage result))

      (:artifact result)
      (assoc :artifact (:artifact result))

      (:progress result)
      (assoc :progress (:progress result)))))
(defn- missing-case-diagnostic
  [failures case-id progress]
  (cond-> {:case-id case-id
           :status "missing"
           :failures (into [{:metric "completed"
                             :operator "="
                             :expected 1
                             :actual 0
                             :message "Selected case does not have a matching agent score artifact."}]
                           (filter #(= case-id (:case-id %)))
                           failures)}
    progress
    (assoc :progress progress)))
(defn- case-diagnostics
  [check failures]
  (let [expanded-failures (vec (expand-case-id-failures failures))
        progress-by-case (into {}
                               (map (juxt :case-id identity))
                               (get-in check [:report :caseProgress]))]
    (vec
     (concat
      (map #(result-case-diagnostic expanded-failures %)
           (get-in check [:report :results]))
      (map #(missing-case-diagnostic expanded-failures
                                     %
                                     (get progress-by-case %))
           (get-in check [:report :missing]))))))
(defn check-agent-report
  "Return a pass/fail check over an agent benchmark report."
  [report opts]
  (let [check-base {:schema agent-check-schema
                    :suite-id (:suite-id report)
                    :thresholds (agent-check-thresholds opts)
                    :report report}
        failures (vec
                  (concat
                   (completeness-failures check-base)
                   (duplicate-run-failures check-base)
                   (keep (fn [[threshold-key metric-key metric-label]]
                           (min-failure check-base
                                        threshold-key
                                        metric-key
                                        metric-label))
                         [[:minFileRecallAt5 :fileRecallAt5 "fileRecallAt5"]
                          [:minFileRecallAt10 :fileRecallAt10 "fileRecallAt10"]
                          [:minFileRecallAt20 :fileRecallAt20 "fileRecallAt20"]
                          [:minMeanReciprocalRankFile
                           :meanReciprocalRankFile
                           "meanReciprocalRankFile"]
                          [:minEvidenceCitationRate
                           :evidenceCitationRate
                           "evidenceCitationRate"]
                          [:minPathEvidenceCitationRate
                           :pathEvidenceCitationRate
                           "pathEvidenceCitationRate"]
                          [:minDecisionF1
                           :decisionF1
                           "decisionF1"]
                          [:minDecisionEvidenceCitationRate
                           :decisionEvidenceCitationRate
                           "decisionEvidenceCitationRate"]])
                   (keep (fn [[threshold-key metric-path metric-label]]
                           (max-failure check-base
                                        threshold-key
                                        metric-path
                                        metric-label))
                         [[:maxNoiseRatioAt20 [:scores :noiseRatioAt20] "noiseRatioAt20"]
                          [:maxInputHintedCases
                           [:inputHints :inputHintedCases]
                           "inputHintedCases"]
                          [:maxUnsupportedGroundTruthFiles
                           [:scores :unsupportedGroundTruthFiles]
                           "unsupportedGroundTruthFiles"]])
                   (aggregate-token-failures check-base)
                   (case-threshold-failures check-base)
                   (empty-result-failures check-base)
                   (missing-predicted-file-failures check-base)
                   (commandless-run-failures check-base)
                   (missing-decision-run-failures check-base)
                   (warning-run-failures check-base)
                   (hint-diagnostic-run-failures check-base)
                   (identity-mismatch-run-failures check-base)
                   (unverified-score-failures check-base)
                   (graph-expectation-failures check-base)
                   (maintenance-preflight-failures check-base)
                   (coverage-diagnostic-failures check-base)
                   (improvement-target-failures check-base)
                   (problem-class-claim-failures check-base)
                   (parser-worker-profile-failures check-base)
                   (localization-diagnostic-failures check-base)
                   (active-stage-failures check-base)))]
    (assoc check-base
           :status (if (seq failures) "failed" "passed")
           :caseDiagnostics (case-diagnostics check-base failures)
           :failures failures)))
(defn check-agent-suite
  "Aggregate existing agent score artifacts and check them against thresholds."
  [suite opts]
  (let [check (check-agent-report (benchmark-report/report-agent-suite suite opts) opts)]
    (benchmark-io/write-json-file! (benchmark-paths/agent-check-path suite opts) check)
    check))
