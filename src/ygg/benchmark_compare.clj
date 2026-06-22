(ns ygg.benchmark-compare
  (:require [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-report :as benchmark-report]
            [ygg.benchmark-targets :as benchmark-targets]))

(def agent-compare-schema
  "ygg.benchmark.agent-compare/v1")

(def ^:private comparison-score-specs
  [{:key :fileRecallAt5
    :label "fileRecallAt5"
    :direction :higher}
   {:key :fileRecallAt10
    :label "fileRecallAt10"
    :direction :higher}
   {:key :fileRecallAt20
    :label "fileRecallAt20"
    :direction :higher}
   {:key :meanReciprocalRankFile
    :label "meanReciprocalRankFile"
    :direction :higher}
   {:key :noiseRatioAt20
    :label "noiseRatioAt20"
    :direction :lower}
   {:key :evidenceCitationRate
    :label "evidenceCitationRate"
    :direction :higher}
   {:key :pathEvidenceCitationRate
    :label "pathEvidenceCitationRate"
    :direction :higher}
   {:key :expectedEvidenceCitationRate
    :label "expectedEvidenceCitationRate"
    :direction :higher}])
(def ^:private comparison-report-specs
  [{:path [:agentDiagnostics :warningRuns]
    :label "warningRuns"
    :direction :lower}
   {:path [:agentDiagnostics :missingPredictedFileRuns]
    :label "missingPredictedFileRuns"
    :direction :lower}
   {:path [:agentDiagnostics :commandlessRuns]
    :label "commandlessRuns"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :searchCommandCount]
    :label "searchCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :fileReadCommandCount]
    :label "fileReadCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :shellCommandCount]
    :label "shellCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :hintDiagnosticRuns]
    :label "hintDiagnosticRuns"
    :direction :lower}
   {:path [:agentDiagnostics :identityMismatchRuns]
    :label "identityMismatchRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :unverifiedScoreRuns]
    :label "unverifiedScoreRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :obsoleteScoreSchemaRuns]
    :label "obsoleteScoreSchemaRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :obsoleteAgentResultSchemaRuns]
    :label "obsoleteAgentResultSchemaRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :staleScoreRuns]
    :label "staleScoreRuns"
    :direction :lower}
   {:path [:coverageDiagnostics :missingDeclaredSourceKindRuns]
    :label "missingDeclaredSourceKindRuns"
    :direction :lower}
   {:path [:coverageDiagnostics :coverageExcludedGroundTruthFiles]
    :label "coverageExcludedGroundTruthFiles"
    :direction :lower}
   {:path [:coverageDiagnostics :unsupportedGroundTruthFiles]
    :label "unsupportedGroundTruthFiles"
    :direction :lower}
   {:path [:comparison :improvementTargetRuns]
    :label "improvementTargetRuns"
    :direction :lower}])
(defn- improvement-target-specs
  [baseline-report candidate-report]
  (->> (concat (keys (get-in baseline-report
                             [:comparison :improvementTargetRunsByKind]))
               (keys (get-in candidate-report
                             [:comparison :improvementTargetRunsByKind])))
       set
       sort
       (mapv (fn [kind]
               {:path [:comparison :improvementTargetRunsByKind kind]
                :label (str "improvementTargetRuns." kind)
                :direction :lower}))))
(defn- comparison-report
  [report]
  (assoc report
         :comparison {:improvementTargetRuns (benchmark-targets/target-runs report)
                      :improvementTargetRunsByKind (benchmark-targets/target-runs-by-kind
                                                    report)}))
(defn- comparison-delta
  [baseline candidate {:keys [path key label direction]} tolerance]
  (let [value-path (or path [key])
        before (double (get-in baseline value-path 0.0))
        after (double (get-in candidate value-path 0.0))
        delta (- after before)
        regression? (case direction
                      :higher (< delta (- tolerance))
                      :lower (> delta tolerance))]
    (cond-> {:metric label
             :direction (name direction)
             :baseline before
             :candidate after
             :delta delta}
      regression? (assoc :regression? true))))
(defn- score-deltas
  [baseline candidate tolerance]
  (mapv #(comparison-delta baseline candidate % tolerance)
        comparison-score-specs))
(defn- report-deltas
  [baseline-report candidate-report tolerance]
  (mapv #(comparison-delta baseline-report candidate-report % tolerance)
        comparison-report-specs))
(defn- report-result-by-case
  [report]
  (->> (:results report)
       (map (juxt :case-id identity))
       (into {})))
(defn- same-case-set?
  [baseline-by-case candidate-by-case]
  (= (set (keys baseline-by-case))
     (set (keys candidate-by-case))))
(defn- report-parser-worker-profiles
  [report]
  (let [profiles (seq (:parserWorkers report))]
    (cond
      profiles
      (->> profiles
           (map #(select-keys % [:mode :source]))
           (sort-by (juxt :mode :source))
           vec)

      (seq (:results report))
      (->> (:results report)
           benchmark-report/aggregate-parser-worker-profiles
           (map #(select-keys % [:mode :source]))
           (sort-by (juxt :mode :source))
           vec)

      (pos? (long (:runs report 0)))
      [{:mode "unknown"
        :source "missing"}]

      :else [])))
(defn- same-parser-worker-profiles?
  [baseline-report candidate-report]
  (= (report-parser-worker-profiles baseline-report)
     (report-parser-worker-profiles candidate-report)))
(defn- aggregate-comparable-reasons
  [baseline-report candidate-report baseline-by-case candidate-by-case]
  (cond-> []
    (not (same-case-set? baseline-by-case candidate-by-case))
    (conj "case-set-changed")

    (not (same-parser-worker-profiles? baseline-report candidate-report))
    (conj "parser-worker-profile-changed")))
(defn- mark-aggregate-deltas-not-comparable
  [deltas reasons]
  (mapv #(cond-> (dissoc % :regression?)
           (:regression? %) (assoc :ignored? true
                                   :reason (first reasons)
                                   :reasons reasons))
        deltas))
(defn- case-comparison
  [baseline-by-case candidate-by-case case-id tolerance]
  (let [baseline (get baseline-by-case case-id)
        candidate (get candidate-by-case case-id)]
    (cond
      (nil? candidate)
      {:case-id case-id
       :status "missing"
       :regressions [{:metric "case.present"
                      :baseline true
                      :candidate false
                      :regression? true}]}

      (nil? baseline)
      {:case-id case-id
       :status "added"
       :regressions []}

      :else
      (let [deltas (mapv #(update % :metric (fn [metric]
                                              (str "case." metric)))
                         (score-deltas (:scores baseline)
                                       (:scores candidate)
                                       tolerance))
            regressions (filterv :regression? deltas)]
        {:case-id case-id
         :status (if (seq regressions) "regressed" "ok")
         :deltas deltas
         :regressions regressions}))))
(defn compare-agent-reports
  "Compare two agent reports and mark metric regressions.

  Higher is better for recall and MRR; lower is better for noise. The optional
  `:regression-tolerance` allows tiny floating-point or sampling drift without
  hiding meaningful regressions."
  [baseline-report candidate-report opts]
  (let [tolerance (double (or (:regression-tolerance opts) 0.0))
        baseline-comparison-report (comparison-report baseline-report)
        candidate-comparison-report (comparison-report candidate-report)
        baseline-by-case (report-result-by-case baseline-report)
        candidate-by-case (report-result-by-case candidate-report)
        case-ids (->> (concat (keys baseline-by-case)
                              (keys candidate-by-case))
                      distinct
                      sort
                      vec)
        aggregate-comparable-reasons (aggregate-comparable-reasons
                                      baseline-report
                                      candidate-report
                                      baseline-by-case
                                      candidate-by-case)
        aggregate-comparable? (empty? aggregate-comparable-reasons)
        raw-aggregate-deltas (vec (concat
                                   (score-deltas (:scores baseline-report)
                                                 (:scores candidate-report)
                                                 tolerance)
                                   (report-deltas baseline-comparison-report
                                                  candidate-comparison-report
                                                  tolerance)
                                   (mapv #(comparison-delta baseline-comparison-report
                                                            candidate-comparison-report
                                                            %
                                                            tolerance)
                                         (improvement-target-specs
                                          baseline-comparison-report
                                          candidate-comparison-report))))
        aggregate-deltas (if aggregate-comparable?
                           raw-aggregate-deltas
                           (mark-aggregate-deltas-not-comparable
                            raw-aggregate-deltas
                            aggregate-comparable-reasons))
        aggregate-regressions (if aggregate-comparable?
                                (filterv :regression? aggregate-deltas)
                                [])
        cases (mapv #(case-comparison baseline-by-case
                                      candidate-by-case
                                      %
                                      tolerance)
                    case-ids)
        case-regressions (vec (mapcat :regressions cases))
        regressions (vec (concat aggregate-regressions case-regressions))]
    {:schema agent-compare-schema
     :suite-id (or (:suite-id candidate-report)
                   (:suite-id baseline-report))
     :status (if (seq regressions) "failed" "passed")
     :tolerance tolerance
     :aggregateComparable aggregate-comparable?
     :aggregateComparableReasons aggregate-comparable-reasons
     :baseline {:cases (:cases baseline-report)
                :completed (:completed baseline-report)
                :runs (:runs baseline-report)
                :parserWorkers (report-parser-worker-profiles baseline-report)
                :agentDiagnostics (:agentDiagnostics baseline-report)
                :coverageDiagnostics (:coverageDiagnostics baseline-report)
                :improvementSummary (:improvementSummary baseline-report)
                :improvementTargetRuns (benchmark-targets/target-runs baseline-report)
                :improvementTargetRunsByKind (benchmark-targets/target-runs-by-kind
                                              baseline-report)
                :scores (:scores baseline-report)}
     :candidate {:cases (:cases candidate-report)
                 :completed (:completed candidate-report)
                 :runs (:runs candidate-report)
                 :parserWorkers (report-parser-worker-profiles candidate-report)
                 :agentDiagnostics (:agentDiagnostics candidate-report)
                 :coverageDiagnostics (:coverageDiagnostics candidate-report)
                 :improvementSummary (:improvementSummary candidate-report)
                 :improvementTargetRuns (benchmark-targets/target-runs candidate-report)
                 :improvementTargetRunsByKind (benchmark-targets/target-runs-by-kind
                                               candidate-report)
                 :scores (:scores candidate-report)}
     :aggregateDeltas aggregate-deltas
     :caseDeltas cases
     :regressions regressions}))
(defn compare-agent-report-files!
  "Read, compare, and write an agent report comparison artifact."
  [suite opts]
  (let [baseline-path (or (:baseline-report opts)
                          (throw (ex-info "Missing --baseline-report."
                                          {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        candidate-path (or (:candidate-report opts)
                           (throw (ex-info "Missing --candidate-report."
                                           {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        comparison (compare-agent-reports (benchmark-io/read-json-file baseline-path)
                                          (benchmark-io/read-json-file candidate-path)
                                          opts)]
    (benchmark-io/write-json-file! (benchmark-paths/agent-compare-path suite opts) comparison)
    comparison))
