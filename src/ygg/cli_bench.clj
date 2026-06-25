(ns ygg.cli-bench
  (:require [ygg.agent-efficiency :as agent-efficiency]
            [ygg.benchmark :as benchmark]
            [ygg.benchmark-repos :as benchmark-repos]
            [ygg.cli-options :refer [json-output? option-value option-values parse-case-ids parse-limit parse-optional-double parse-optional-long positional-args]]
            [ygg.queue :as queue]
            [clojure.string :as str]))

(defn- parse-improvement-target-kind-limit
  [value]
  (let [[kind limit extra] (str/split value #"=")]
    (when (or (str/blank? kind)
              (str/blank? limit)
              extra)
      (throw (ex-info "Expected --max-improvement-target-kind-runs as kind=N."
                      {:value value})))
    [kind (Double/parseDouble limit)]))

(defn- improvement-target-kind-limits
  [args]
  (not-empty
   (into (sorted-map)
         (map parse-improvement-target-kind-limit)
         (option-values args "--max-improvement-target-kind-runs"))))

(defn- bench-opts
  [args]
  (cond-> {:case-id (option-value args "--case")
           :out (option-value args "--out")
           :retriever (option-value args "--retriever")
           :parser-worker (option-value args "--parser-worker")
           :mode (option-value args "--mode")
           :result-path (option-value args "--result")
           :command (option-value args "--command")}
    (parse-case-ids args) (assoc :case-ids (parse-case-ids args))
    (option-value args "--agent-report") (assoc :agent-report-path
                                                (option-value args "--agent-report"))
    (option-value args "--baseline-report") (assoc :baseline-report
                                                   (option-value args "--baseline-report"))
    (option-value args "--candidate-report") (assoc :candidate-report
                                                    (option-value args "--candidate-report"))
    (option-value args "--shell-report") (assoc :shell-report
                                                (option-value args
                                                              "--shell-report"))
    (option-value args "--ygg-report") (assoc :ygg-report
                                              (option-value args
                                                            "--ygg-report"))
    (option-value args "--vector-command") (assoc :vector-command
                                                  (option-value args "--vector-command"))
    (option-value args "--vector-model") (assoc :vector-model
                                                (option-value args "--vector-model"))
    (option-value args "--codebase-memory-command") (assoc :codebase-memory-command
                                                           (option-value args
                                                                         "--codebase-memory-command"))
    (option-value args "--codebase-memory-bin") (assoc :codebase-memory-bin
                                                       (option-value args
                                                                     "--codebase-memory-bin"))
    (option-value args "--codebase-memory-cache-dir") (assoc :codebase-memory-cache-dir
                                                             (option-value
                                                              args
                                                              "--codebase-memory-cache-dir"))
    (option-value args "--agent") (assoc :agent-id (option-value args "--agent"))
    (option-value args "--prompt-profile") (assoc :prompt-profile
                                                  (option-value args "--prompt-profile"))
    (parse-limit args) (assoc :limit (parse-limit args))
    (parse-optional-long args "--timeout-ms") (assoc :timeout-ms
                                                     (parse-optional-long args
                                                                          "--timeout-ms"))
    (parse-optional-long args "--index-timeout-ms") (assoc :index-timeout-ms
                                                           (parse-optional-long
                                                            args
                                                            "--index-timeout-ms"))
    (parse-optional-long args "--min-cases") (assoc :min-cases
                                                    (parse-optional-long args
                                                                         "--min-cases"))
    (parse-optional-long args "--min-runs") (assoc :min-runs
                                                   (parse-optional-long args
                                                                        "--min-runs"))
    (parse-optional-long args "--min-shared-cases") (assoc
                                                     :min-shared-cases
                                                     (parse-optional-long
                                                      args
                                                      "--min-shared-cases"))
    (parse-optional-long args "--budget") (assoc :budget (parse-optional-long args "--budget"))
    (parse-optional-long args "--doc-limit") (assoc :doc-limit (parse-optional-long args "--doc-limit"))
    (parse-optional-long args "--retrieval-limit") (assoc :retrieval-limit
                                                          (parse-optional-long args
                                                                               "--retrieval-limit"))
    (parse-optional-long args "--snippet-chars") (assoc :snippet-chars
                                                        (parse-optional-long args
                                                                             "--snippet-chars"))
    (parse-optional-double args "--min-file-recall-at-5") (assoc :min-file-recall-at-5
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-file-recall-at-5"))
    (parse-optional-double args "--min-file-recall-at-10") (assoc :min-file-recall-at-10
                                                                  (parse-optional-double
                                                                   args
                                                                   "--min-file-recall-at-10"))
    (parse-optional-double args "--min-file-recall-at-20") (assoc :min-file-recall-at-20
                                                                  (parse-optional-double
                                                                   args
                                                                   "--min-file-recall-at-20"))
    (parse-optional-double args "--min-mrr") (assoc :min-mrr
                                                    (parse-optional-double args
                                                                           "--min-mrr"))
    (parse-optional-double args "--max-noise-at-20") (assoc :max-noise-at-20
                                                            (parse-optional-double
                                                             args
                                                             "--max-noise-at-20"))
    (parse-optional-double args "--min-evidence-citation-rate") (assoc
                                                                 :min-evidence-citation-rate
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-evidence-citation-rate"))
    (parse-optional-double args "--min-path-evidence-citation-rate") (assoc
                                                                      :min-path-evidence-citation-rate
                                                                      (parse-optional-double
                                                                       args
                                                                       "--min-path-evidence-citation-rate"))
    (parse-optional-double args "--min-decision-f1") (assoc
                                                      :min-decision-f1
                                                      (parse-optional-double
                                                       args
                                                       "--min-decision-f1"))
    (parse-optional-double args "--min-decision-evidence-citation-rate") (assoc
                                                                          :min-decision-evidence-citation-rate
                                                                          (parse-optional-double
                                                                           args
                                                                           "--min-decision-evidence-citation-rate"))
    (parse-optional-double args "--max-total-tokens") (assoc
                                                       :max-total-tokens
                                                       (parse-optional-double
                                                        args
                                                        "--max-total-tokens"))
    (parse-optional-double args "--max-input-tokens") (assoc
                                                       :max-input-tokens
                                                       (parse-optional-double
                                                        args
                                                        "--max-input-tokens"))
    (parse-optional-double args "--max-output-tokens") (assoc
                                                        :max-output-tokens
                                                        (parse-optional-double
                                                         args
                                                         "--max-output-tokens"))
    (parse-optional-double args "--max-cost-usd") (assoc
                                                   :max-cost-usd
                                                   (parse-optional-double
                                                    args
                                                    "--max-cost-usd"))
    (parse-optional-double args "--min-case-file-recall-at-5") (assoc
                                                                :min-case-file-recall-at-5
                                                                (parse-optional-double
                                                                 args
                                                                 "--min-case-file-recall-at-5"))
    (parse-optional-double args "--min-case-file-recall-at-10") (assoc
                                                                 :min-case-file-recall-at-10
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-case-file-recall-at-10"))
    (parse-optional-double args "--min-case-file-recall-at-20") (assoc
                                                                 :min-case-file-recall-at-20
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-case-file-recall-at-20"))
    (parse-optional-double args "--min-case-mrr") (assoc :min-case-mrr
                                                         (parse-optional-double
                                                          args
                                                          "--min-case-mrr"))
    (parse-optional-double args "--min-case-evidence-citation-rate") (assoc
                                                                      :min-case-evidence-citation-rate
                                                                      (parse-optional-double
                                                                       args
                                                                       "--min-case-evidence-citation-rate"))
    (parse-optional-double args "--min-case-path-evidence-citation-rate") (assoc
                                                                           :min-case-path-evidence-citation-rate
                                                                           (parse-optional-double
                                                                            args
                                                                            "--min-case-path-evidence-citation-rate"))
    (parse-optional-double args "--min-case-decision-f1") (assoc
                                                           :min-case-decision-f1
                                                           (parse-optional-double
                                                            args
                                                            "--min-case-decision-f1"))
    (parse-optional-double args "--max-case-total-tokens") (assoc
                                                            :max-case-total-tokens
                                                            (parse-optional-double
                                                             args
                                                             "--max-case-total-tokens"))
    (parse-optional-double args "--max-case-input-tokens") (assoc
                                                            :max-case-input-tokens
                                                            (parse-optional-double
                                                             args
                                                             "--max-case-input-tokens"))
    (parse-optional-double args "--max-case-output-tokens") (assoc
                                                             :max-case-output-tokens
                                                             (parse-optional-double
                                                              args
                                                              "--max-case-output-tokens"))
    (parse-optional-double args "--max-case-cost-usd") (assoc
                                                        :max-case-cost-usd
                                                        (parse-optional-double
                                                         args
                                                         "--max-case-cost-usd"))
    (parse-optional-double args "--max-case-noise-at-20") (assoc
                                                           :max-case-noise-at-20
                                                           (parse-optional-double
                                                            args
                                                            "--max-case-noise-at-20"))
    (parse-optional-double args "--max-input-hinted-cases") (assoc :max-input-hinted-cases
                                                                   (parse-optional-double
                                                                    args
                                                                    "--max-input-hinted-cases"))
    (parse-optional-double args "--max-unsupported-ground-truth-files") (assoc
                                                                         :max-unsupported-ground-truth-files
                                                                         (parse-optional-double
                                                                          args
                                                                          "--max-unsupported-ground-truth-files"))
    (parse-optional-double args "--max-empty-result-runs") (assoc
                                                            :max-empty-result-runs
                                                            (parse-optional-double
                                                             args
                                                             "--max-empty-result-runs"))
    (parse-optional-double args "--max-missing-predicted-file-runs") (assoc
                                                                      :max-missing-predicted-file-runs
                                                                      (parse-optional-double
                                                                       args
                                                                       "--max-missing-predicted-file-runs"))
    (parse-optional-double args "--max-missing-decision-runs") (assoc
                                                                :max-missing-decision-runs
                                                                (parse-optional-double
                                                                 args
                                                                 "--max-missing-decision-runs"))
    (parse-optional-double args "--max-commandless-runs") (assoc
                                                           :max-commandless-runs
                                                           (parse-optional-double
                                                            args
                                                            "--max-commandless-runs"))
    (parse-optional-double args "--max-warning-runs") (assoc
                                                       :max-warning-runs
                                                       (parse-optional-double
                                                        args
                                                        "--max-warning-runs"))
    (parse-optional-double args "--max-hint-diagnostic-runs") (assoc
                                                               :max-hint-diagnostic-runs
                                                               (parse-optional-double
                                                                args
                                                                "--max-hint-diagnostic-runs"))
    (parse-optional-double args "--max-identity-mismatch-runs") (assoc
                                                                 :max-identity-mismatch-runs
                                                                 (parse-optional-double
                                                                  args
                                                                  "--max-identity-mismatch-runs"))
    (parse-optional-double args "--max-unverified-score-runs") (assoc
                                                                :max-unverified-score-runs
                                                                (parse-optional-double
                                                                 args
                                                                 "--max-unverified-score-runs"))
    (parse-optional-double args "--max-graph-expectation-failures") (assoc
                                                                     :max-graph-expectation-failures
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-graph-expectation-failures"))
    (parse-optional-double args "--max-maintenance-preflight-blockers") (assoc
                                                                         :max-maintenance-preflight-blockers
                                                                         (parse-optional-double
                                                                          args
                                                                          "--max-maintenance-preflight-blockers"))
    (parse-optional-double args "--max-missing-declared-source-kind-runs") (assoc
                                                                            :max-missing-declared-source-kind-runs
                                                                            (parse-optional-double
                                                                             args
                                                                             "--max-missing-declared-source-kind-runs"))
    (parse-optional-double args "--max-missed-runs") (assoc
                                                      :max-missed-runs
                                                      (parse-optional-double
                                                       args
                                                       "--max-missed-runs"))
    (parse-optional-double args "--max-context-rank-missing-runs") (assoc
                                                                    :max-context-rank-missing-runs
                                                                    (parse-optional-double
                                                                     args
                                                                     "--max-context-rank-missing-runs"))
    (parse-optional-double args "--max-missed-but-present-in-context-runs") (assoc
                                                                             :max-missed-but-present-in-context-runs
                                                                             (parse-optional-double
                                                                              args
                                                                              "--max-missed-but-present-in-context-runs"))
    (parse-optional-double args "--max-missed-and-absent-from-context-runs") (assoc
                                                                              :max-missed-and-absent-from-context-runs
                                                                              (parse-optional-double
                                                                               args
                                                                               "--max-missed-and-absent-from-context-runs"))
    (parse-optional-double args "--max-ranked-outside-top-5-runs") (assoc
                                                                    :max-ranked-outside-top-5-runs
                                                                    (parse-optional-double
                                                                     args
                                                                     "--max-ranked-outside-top-5-runs"))
    (parse-optional-double args "--max-ranked-outside-top-10-runs") (assoc
                                                                     :max-ranked-outside-top-10-runs
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-ranked-outside-top-10-runs"))
    (parse-optional-double args "--max-ranked-outside-top-20-runs") (assoc
                                                                     :max-ranked-outside-top-20-runs
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-ranked-outside-top-20-runs"))
    (parse-optional-double args "--max-improvement-target-runs") (assoc
                                                                  :max-improvement-target-runs
                                                                  (parse-optional-double
                                                                   args
                                                                   "--max-improvement-target-runs"))
    (improvement-target-kind-limits args) (assoc
                                           :max-improvement-target-kind-runs
                                           (improvement-target-kind-limits args))
    (parse-optional-long args "--max-active-stage-ms") (assoc :max-active-stage-ms
                                                              (parse-optional-long
                                                               args
                                                               "--max-active-stage-ms"))
    (parse-optional-long args "--max-parser-worker-profiles") (assoc
                                                               :max-parser-worker-profiles
                                                               (parse-optional-long
                                                                args
                                                                "--max-parser-worker-profiles"))
    (parse-optional-long args "--min-measured-problem-classes") (assoc
                                                                 :min-measured-problem-classes
                                                                 (parse-optional-long
                                                                  args
                                                                  "--min-measured-problem-classes"))
    (parse-optional-long args "--min-measured-architecture-classes") (assoc
                                                                      :min-measured-architecture-classes
                                                                      (parse-optional-long
                                                                       args
                                                                       "--min-measured-architecture-classes"))
    (option-value args "--require-parser-worker") (assoc :require-parser-worker
                                                         (option-value
                                                          args
                                                          "--require-parser-worker"))
    (parse-optional-double args "--regression-tolerance") (assoc
                                                           :regression-tolerance
                                                           (parse-optional-double
                                                            args
                                                            "--regression-tolerance"))
    (some #{"--skip-existing"} args) (assoc :skip-existing? true)
    (some #{"--allow-missing"} args) (assoc :allow-missing? true)
    (some #{"--allow-duplicate-runs"} args) (assoc :allow-duplicate-runs? true)
    (some #{"--allow-unverified-scores"} args) (assoc :allow-unverified-scores? true)))
(defn- print-benchmark-case-summary
  [case]
  (println "-"
           (:case-id case)
           (:repo-id case)
           "recall@10"
           (format "%.2f" (double (get-in case [:scores :fileRecallAt10] 0.0)))
           "mrr"
           (format "%.2f" (double (get-in case [:scores :meanReciprocalRankFile] 0.0)))))
(defn- parser-worker-summary-label
  [profile]
  (str (:mode profile)
       "/"
       (:source profile)
       ":"
       (:runs profile)))
(defn- print-parser-worker-summary
  [profiles]
  (when (seq profiles)
    (println "- parser-workers"
             (str/join ", " (map parser-worker-summary-label profiles)))))
(defn- print-agent-diagnostic-count
  [diagnostics label count-key case-ids-key & {:keys [extra-key extra-label]}]
  (let [count-value (long (or (get diagnostics count-key) 0))]
    (when (pos? count-value)
      (println
       (str/join " "
                 (cond-> [(str "- " label)
                          (str count-value)]
                   extra-key
                   (conj (str extra-label " " (or (get diagnostics extra-key) 0)))
                   true
                   (conj "cases"
                         (str/join "," (get diagnostics case-ids-key)))))))))
(defn- print-agent-diagnostics-summary
  [diagnostics]
  (when diagnostics
    (print-agent-diagnostic-count diagnostics
                                  "missing-predicted-file-runs"
                                  :missingPredictedFileRuns
                                  :missingPredictedFileCaseIds
                                  :extra-key :missingPredictedFiles
                                  :extra-label "files")
    (print-agent-diagnostic-count diagnostics
                                  "commandless-runs"
                                  :commandlessRuns
                                  :commandlessCaseIds)
    (print-agent-diagnostic-count diagnostics
                                  "warning-runs"
                                  :warningRuns
                                  :warningCaseIds)
    (print-agent-diagnostic-count diagnostics
                                  "hint-diagnostic-runs"
                                  :hintDiagnosticRuns
                                  :hintDiagnosticCaseIds
                                  :extra-key :hintDiagnosticRows
                                  :extra-label "rows")))
(defn- print-decision-diagnostics-summary
  [diagnostics]
  (when diagnostics
    (print-agent-diagnostic-count diagnostics
                                  "missing-decision-runs"
                                  :missingDecisionRuns
                                  :missingDecisionCaseIds)
    (print-agent-diagnostic-count diagnostics
                                  "decision-quality-gap-runs"
                                  :gapRuns
                                  :gapCaseIds)))
(defn- print-artifact-diagnostics-summary
  [diagnostics]
  (when diagnostics
    (print-agent-diagnostic-count diagnostics
                                  "unverified-score-runs"
                                  :unverifiedScoreRuns
                                  :unverifiedScoreCaseIds)
    (let [obsolete-runs (long (or (:obsoleteScoreSchemaRuns diagnostics) 0))]
      (when (pos? obsolete-runs)
        (println "- obsolete-score-schema-runs"
                 obsolete-runs
                 "schemas"
                 (str/join "," (:obsoleteScoreSchemas diagnostics))
                 "expected"
                 (:expectedScoreSchema diagnostics)
                 "cases"
                 (str/join "," (:obsoleteScoreSchemaCaseIds diagnostics)))))
    (let [obsolete-runs (long (or (:obsoleteAgentResultSchemaRuns diagnostics) 0))]
      (when (pos? obsolete-runs)
        (println "- obsolete-agent-result-schema-runs"
                 obsolete-runs
                 "schemas"
                 (str/join "," (:obsoleteAgentResultSchemas diagnostics))
                 "expected"
                 (:expectedAgentResultSchema diagnostics)
                 "cases"
                 (str/join "," (:obsoleteAgentResultSchemaCaseIds diagnostics)))))
    (print-agent-diagnostic-count diagnostics
                                  "stale-score-runs"
                                  :staleScoreRuns
                                  :staleScoreCaseIds)))
(defn- print-maintenance-preflight-summary
  [preflight]
  (when (and preflight (:requiredForClaim preflight))
    (println "- maintenance-preflight"
             (:status preflight)
             "blocked"
             (long (or (:blockedRuns preflight) 0))
             "cases"
             (str/join "," (:blockedCaseIds preflight)))
    (doseq [check (:checks preflight)
            :let [failed-runs (long (or (:failedRuns check) 0))
                  not-run-runs (long (or (:notRunRuns check) 0))
                  case-ids (->> (concat (:failedCaseIds check)
                                        (:notRunCaseIds check))
                                distinct
                                sort)]
            :when (pos? (+ failed-runs not-run-runs))]
      (println "- maintenance-preflight-check"
               (:check check)
               (:status check)
               "failed"
               failed-runs
               "not-run"
               not-run-runs
               "cases"
               (str/join "," case-ids)))))
(defn- print-claim-readiness
  [claim-readiness]
  (when claim-readiness
    (println "- claim-readiness" (:status claim-readiness))
    (when (seq (:measuredProblemClassTags claim-readiness))
      (println "- measured-problem-classes"
               (str/join "," (:measuredProblemClassTags claim-readiness))))
    (when (seq (:measuredArchitectureClassTags claim-readiness))
      (println "- measured-architecture-classes"
               (str/join "," (:measuredArchitectureClassTags claim-readiness))))
    (when (seq (:warnings claim-readiness))
      (println "## Claim Readiness Warnings")
      (doseq [warning (:warnings claim-readiness)]
        (println "-" warning)))))
(defn print-benchmark-summary
  [result]
  (println "# Benchmark")
  (println "- schema" (:schema result))
  (println "- suite" (:suite-id result))
  (cond
    (= benchmark/agent-runs-schema (:schema result))
    (do
      (println "- completed" (:completed result))
      (println "- failed" (:failed result))
      (println "- skipped" (:skipped result 0))
      (when-let [rerun-lane (:rerunLane result)]
        (println "- rerun-selection"
                 (:selection rerun-lane)
                 "cases"
                 (str/join "," (:caseIds rerun-lane))))
      (doseq [run (:runs result)]
        (println "-"
                 (:case-id run)
                 (:repo-id run)
                 "agent"
                 (:agentId run)
                 "status"
                 (:status run)
                 "recall@10"
                 (format "%.2f" (double (get-in run [:scores :fileRecallAt10] 0.0)))
                 "mrr"
                 (format "%.2f" (double (get-in run
                                                [:scores :meanReciprocalRankFile]
                                                0.0))))))

    (:baselines result)
    (do
      (when (contains? result :skipped)
        (println "- skipped" (:skipped result 0)))
      (doseq [baseline (:baselines result)]
        (println "-"
                 (:case-id baseline)
                 (:repo-id baseline)
                 "agent"
                 (:agentId baseline)
                 "status"
                 (or (:status baseline) "ran")
                 "recall@10"
                 (format "%.2f" (double (get-in baseline [:scores :fileRecallAt10] 0.0)))
                 "mrr"
                 (format "%.2f" (double (get-in baseline
                                                [:scores :meanReciprocalRankFile]
                                                0.0))))))

    (:completed result)
    (do
      (println "- cases" (:cases result))
      (println "- completed" (:completed result))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result [:scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result [:scores :meanReciprocalRankFile] 0.0))))
      (println "- evidence-citation"
               (format "%.2f" (double (get-in result [:scores :evidenceCitationRate] 0.0))))
      (print-parser-worker-summary (:parserWorkers result))
      (print-agent-diagnostics-summary (:agentDiagnostics result))
      (print-decision-diagnostics-summary (:decisionDiagnostics result))
      (print-artifact-diagnostics-summary (:artifactDiagnostics result))
      (print-maintenance-preflight-summary (:maintenancePreflightDiagnostics result))
      (print-claim-readiness (:claimReadiness result))
      (when-let [blocker (first (get-in result
                                        [:localizationDiagnostics
                                         :rankedOutsideTop5BlockingFiles]))]
        (println "- top5-blocker"
                 (:path blocker)
                 "occurrences"
                 (:occurrences blocker)
                 "best-rank"
                 (:bestRank blocker)))
      (when (pos? (long (get-in result
                                [:graphExpectationDiagnostics :failedRuns]
                                0)))
        (println "- graph-expectation-failures"
                 (get-in result [:graphExpectationDiagnostics :failedRuns])
                 "cases"
                 (str/join "," (get-in result
                                       [:graphExpectationDiagnostics :failedCaseIds]))))
      (when-let [timings (:timings result)]
        (println "- timing-ms" (:elapsedMs timings)
                 "warm" (:warmElapsedMs timings)
                 "amortized-setup" (:amortizedSetupElapsedMs timings)
                 "agent-ready" (:agentReadyElapsedMs timings)
                 "running" (:runningCases timings)
                 "failed" (:failedCases timings))
        (when-let [slowest (first (:slowestCases timings))]
          (println "- slowest"
                   (:case-id slowest)
                   (:status slowest)
                   (:elapsedMs slowest)
                   "ms")))
      (when (seq (:missing result))
        (println "- missing" (str/join "," (:missing result)))))

    (= benchmark/system-improvement-report-schema (:schema result))
    (do
      (println "- source-runs" (get-in result [:sourceReport :runs]))
      (println "- claim-ready" (:claimReady? result))
      (println "- signals" (count (:systemImprovementSignals result)))
      (doseq [lane (:lanes result)]
        (println "-"
                 (:lane lane)
                 "owner"
                 (:suggestedOwnerArea lane)
                 "cases"
                 (:affectedCases lane)
                 "runs"
                 (:runs lane)
                 "confidence"
                 (:confidence lane))))

    (= benchmark/claim-pack-schema (:schema result))
    (do
      (println "- verdict" (get-in result [:summary :verdict]))
      (println "- claim-readiness" (get-in result [:summary :claimReadiness]))
      (println "- quality-token-tradeoff"
               (or (get-in result
                           [:summary :qualityCostTradeoff :status])
                   "unavailable"))
      (println "- claim-pack" (get-in result [:artifacts :claimPackPath]))
      (println "- markdown" (get-in result
                                    [:artifacts :claimPackMarkdownPath])))

    (:cases result)
    (doseq [case (:cases result)]
      (if (:scores case)
        (print-benchmark-case-summary case)
        (println "-" (:case-id case) (:repo-id case) "prepared")))

    (:packets result)
    (doseq [packet (:packets result)]
      (println "-"
               (:case-id packet)
               (:repo-id packet)
               "mode"
               (:mode packet)
               "packet"
               (get-in packet [:artifacts :packetPath])))

    (= benchmark/agent-check-schema (:schema result))
    (do
      (println "- status" (:status result))
      (println "- completed" (get-in result [:report :completed]) "/" (get-in result [:report :cases]))
      (println "- runs" (get-in result [:report :runs]))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result
                                              [:report :scores :fileRecallAt10]
                                              0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result
                                              [:report :scores :meanReciprocalRankFile]
                                              0.0))))
      (println "- evidence-citation"
               (format "%.2f" (double (get-in result
                                              [:report :scores :evidenceCitationRate]
                                              0.0))))
      (print-parser-worker-summary (get-in result [:report :parserWorkers]))
      (print-agent-diagnostics-summary (get-in result [:report :agentDiagnostics]))
      (print-decision-diagnostics-summary (get-in result [:report :decisionDiagnostics]))
      (print-artifact-diagnostics-summary (get-in result [:report :artifactDiagnostics]))
      (print-maintenance-preflight-summary (get-in result
                                                   [:report
                                                    :maintenancePreflightDiagnostics]))
      (print-claim-readiness (get-in result [:report :claimReadiness]))
      (println "- noise@20"
               (format "%.2f" (double (get-in result
                                              [:report :scores :noiseRatioAt20]
                                              0.0))))
      (when-let [blocker (first (get-in result
                                        [:report
                                         :localizationDiagnostics
                                         :rankedOutsideTop5BlockingFiles]))]
        (println "- top5-blocker"
                 (:path blocker)
                 "occurrences"
                 (:occurrences blocker)
                 "best-rank"
                 (:bestRank blocker)))
      (when (pos? (long (get-in result
                                [:report
                                 :graphExpectationDiagnostics
                                 :failedRuns]
                                0)))
        (println "- graph-expectation-failures"
                 (get-in result [:report :graphExpectationDiagnostics :failedRuns])
                 "cases"
                 (str/join "," (get-in result
                                       [:report
                                        :graphExpectationDiagnostics
                                        :failedCaseIds]))))
      (when (seq (:failures result))
        (println "## Failures")
        (doseq [{:keys [metric operator expected actual message]} (:failures result)]
          (println "-" metric operator expected "actual" actual)
          (when message
            (println " " message)))))

    (= benchmark/agent-compare-schema (:schema result))
    (do
      (println "- status" (:status result))
      (println "- tolerance" (:tolerance result))
      (println "- aggregate-comparable" (:aggregateComparable result))
      (when (seq (:aggregateComparableReasons result))
        (println "- aggregate-comparable-reasons"
                 (str/join "," (:aggregateComparableReasons result))))
      (println "- file-recall@10"
               (format "%.2f -> %.2f"
                       (double (get-in result [:baseline :scores :fileRecallAt10] 0.0))
                       (double (get-in result [:candidate :scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f -> %.2f"
                       (double (get-in result [:baseline :scores :meanReciprocalRankFile] 0.0))
                       (double (get-in result [:candidate :scores :meanReciprocalRankFile] 0.0))))
      (when (seq (:regressions result))
        (println "## Regressions")
        (doseq [{:keys [metric baseline candidate delta]} (:regressions result)]
          (println "-" metric "baseline" baseline "candidate" candidate "delta" delta))))

    (= benchmark/agent-score-schema (:schema result))
    (do
      (println "- case" (:case-id result))
      (println "- agent" (get-in result [:agent :agentId]))
      (println "- mode" (get-in result [:agent :mode]))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result [:scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result [:scores :meanReciprocalRankFile] 0.0)))))))
(defn- enqueue-benchmark-agent-packets
  [args result {:keys [queue-root queue-priority]}]
  (assoc result
         :enqueued
         (mapv (fn [packet]
                 (queue/item-summary
                  (queue/enqueue! packet
                                  {:root (queue-root args)
                                   :kind "benchmark-agent"
                                   :project-id (:project-id packet)
                                   :priority (queue-priority args 50)})))
               (:packets result))))

(defn- print-efficiency-summary
  [comparison]
  (println (str "Agent efficiency: " (:status comparison)))
  (when-let [compact (:compactSummary comparison)]
    (println (str "Verdict: " (:verdict compact)))
    (doseq [reason (:why compact)]
      (println (str "- " reason))))
  (println (str "Shared tag groups: "
                (get-in comparison [:byTag :comparability :sharedTags])))
  (when-let [summary (get-in comparison [:classSignals :summary])]
    (println (str "Problem classes: "
                  (:measuredProblemClasses summary)
                  " architecture classes: "
                  (:measuredArchitectureClasses summary)))))

(defn- bench-efficiency!
  [args {:keys [print-json]}]
  (let [positions (positional-args args)
        shell-report-path (first positions)
        ygg-report-path (second positions)]
    (when-not (and shell-report-path ygg-report-path)
      (throw (ex-info "Missing report paths."
                      {:usage "bench efficiency <shell-agent-report.json> <ygg-agent-report.json> [--out report.json] [--markdown-out REPORT.md] [--json] [--min-shared-cases N]"})))
    (let [comparison (agent-efficiency/compare-report-files!
                      shell-report-path
                      ygg-report-path
                      {:out (option-value args "--out")
                       :markdown-out (option-value args "--markdown-out")
                       :min-shared-cases (parse-optional-long args
                                                              "--min-shared-cases")})]
      (if (json-output? args)
        (print-json comparison)
        (print-efficiency-summary comparison)))))

(defn- bench-repos!
  [args {:keys [print-json]}]
  (let [[command & rest-args] args]
    (case (keyword command)
      :check
      (let [check (benchmark-repos/check-repos
                   {:manifest-path (option-value rest-args "--manifest")
                    :suite-path (option-value rest-args "--suite")
                    :repo-ids (vec (option-values rest-args "--repo"))})]
        (if (json-output? rest-args)
          (print-json check)
          (benchmark-repos/print-human check))
        (when (= "failed" (:status check))
          (throw (ex-info "Benchmark repo preflight failed." check))))

      (throw (ex-info "Unknown benchmark repos command."
                      {:command command
                       :usage "bench repos check [--manifest PATH] [--suite PATH] [--repo ID] [--json]"})))))

(defn bench!
  [args {:keys [usage print-json enqueue-output?] :as deps}]
  (let [action (keyword (first args))
        bench-args (vec (rest args))
        suite-path (first (positional-args bench-args))]
    (case action
      :efficiency
      (bench-efficiency! bench-args deps)

      :repos
      (bench-repos! bench-args deps)

      (do
        (when-not suite-path
          (throw (ex-info "Missing benchmark suite path." {:usage (usage)})))
        (let [suite (benchmark/read-suite suite-path)
              opts (bench-opts bench-args)
              result (case action
                       :prepare (benchmark/prepare-suite! suite opts)
                       :run (benchmark/run-suite! suite opts)
                       :report (benchmark/report-suite suite opts)
                       :agent-report (benchmark/report-agent-suite suite opts)
                       :improve (benchmark/improve-agent-suite suite opts)
                       :agent-baseline (benchmark/agent-baselines! suite opts)
                       :agent-run (benchmark/agent-runs! suite opts)
                       :agent-rerun (benchmark/rerun-agent-lane! suite opts)
                       :agent-check (benchmark/check-agent-suite suite opts)
                       :agent-compare (benchmark/compare-agent-report-files! suite opts)
                       :claim-pack (benchmark/claim-pack! suite opts)
                       :show (benchmark/show-case suite
                                                  (or (:case-id opts)
                                                      (throw (ex-info "Missing --case."
                                                                      {:usage (usage)})))
                                                  opts)
                       :agent-packet (let [result (benchmark/agent-packets! suite opts)]
                                       (if (enqueue-output? bench-args)
                                         (enqueue-benchmark-agent-packets bench-args
                                                                          result
                                                                          deps)
                                         result))
                       :agent-score (benchmark/score-agent-result!
                                     suite
                                     (first (benchmark/selected-cases
                                             suite
                                             (or (:case-id opts)
                                                 (throw (ex-info "Missing --case."
                                                                 {:usage (usage)})))))
                                     opts)
                       (throw (ex-info "Unknown benchmark command."
                                       {:command action
                                        :usage (usage)})))]
          (if (json-output? bench-args)
            (print-json result)
            (print-benchmark-summary result))
          (when (and (or (= benchmark/agent-check-schema (:schema result))
                         (= benchmark/agent-compare-schema (:schema result)))
                     (= "failed" (:status result)))
            (throw (ex-info "Benchmark gate failed."
                            {:schema (:schema result)
                             :suite-id (:suite-id result)
                             :status (:status result)
                             :failures (or (:failures result)
                                           (:regressions result))}))))))))
