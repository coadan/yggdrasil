(ns ygg.agent-efficiency
  "Compare shell-only and Yggdrasil-assisted agent benchmark reports."
  (:require [ygg.benchmark-classes :as benchmark-classes]
            [ygg.benchmark-targets :as benchmark-targets]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "ygg.agent-efficiency/v1")

(declare markdown-report)

(def default-min-shared-cases
  2)

(def ^:private metric-specs
  [{:key :fileRecallAt5
    :label "fileRecallAt5"
    :category :localization
    :path [:scores :fileRecallAt5]
    :direction :higher}
   {:key :fileRecallAt10
    :label "fileRecallAt10"
    :category :localization
    :path [:scores :fileRecallAt10]
    :direction :higher}
   {:key :fileRecallAt20
    :label "fileRecallAt20"
    :category :localization
    :path [:scores :fileRecallAt20]
    :direction :higher}
   {:key :meanReciprocalRankFile
    :label "meanReciprocalRankFile"
    :category :localization
    :path [:scores :meanReciprocalRankFile]
    :direction :higher}
   {:key :missedRuns
    :label "missedRuns"
    :category :localization
    :path [:localizationDiagnostics :missedRuns]
    :direction :lower}
   {:key :rankedOutsideTop5Runs
    :label "rankedOutsideTop5Runs"
    :category :localization
    :path [:localizationDiagnostics :rankedOutsideTop5Runs]
    :direction :lower}
   {:key :rankedOutsideTop10Runs
    :label "rankedOutsideTop10Runs"
    :category :localization
    :path [:localizationDiagnostics :rankedOutsideTop10Runs]
    :direction :lower}
   {:key :noiseRatioAt20
    :label "noiseRatioAt20"
    :category :noise
    :path [:scores :noiseRatioAt20]
    :direction :lower}
   {:key :missingPredictedFileRuns
    :label "missingPredictedFileRuns"
    :category :noise
    :path [:agentDiagnostics :missingPredictedFileRuns]
    :direction :lower}
   {:key :evidenceCitationRate
    :label "evidenceCitationRate"
    :category :evidence
    :path [:scores :evidenceCitationRate]
    :direction :higher}
   {:key :pathEvidenceCitationRate
    :label "pathEvidenceCitationRate"
    :category :evidence
    :path [:scores :pathEvidenceCitationRate]
    :direction :higher}
   {:key :expectedEvidenceCitationRate
    :label "expectedEvidenceCitationRate"
    :category :evidence
    :path [:scores :expectedEvidenceCitationRate]
    :direction :higher}
   {:key :emptyResultRuns
    :label "emptyResultRuns"
    :category :result-health
    :path [:agentDiagnostics :emptyResultRuns]
    :direction :lower}
   {:key :commandlessRuns
    :label "commandlessRuns"
    :category :result-health
    :path [:agentDiagnostics :commandlessRuns]
    :direction :lower}
   {:key :warningRuns
    :label "warningRuns"
    :category :result-health
    :path [:agentDiagnostics :warningRuns]
    :direction :lower}
   {:key :improvementTargetRuns
    :label "improvementTargetRuns"
    :category :result-health
    :path [:efficiency :improvementTargetRuns]
    :direction :lower}
   {:key :commandCount
    :label "commandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :commandCount]
    :direction :lower}
   {:key :yggCommandCount
    :label "yggCommandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :yggCommandCount]
    :direction :observe}
   {:key :searchCommandCount
    :label "searchCommandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :searchCommandCount]
    :direction :lower}
   {:key :fileReadCommandCount
    :label "fileReadCommandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :fileReadCommandCount]
    :direction :lower}
   {:key :shellCommandCount
    :label "shellCommandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :shellCommandCount]
    :direction :lower}
   {:key :totalTokens
    :label "totalTokens"
    :category :token-cost
    :path [:agentDiagnostics :tokenTelemetry :totalTokens]
    :direction :lower}
   {:key :inputTokens
    :label "inputTokens"
    :category :token-cost
    :path [:agentDiagnostics :tokenTelemetry :inputTokens]
    :direction :lower}
   {:key :outputTokens
    :label "outputTokens"
    :category :token-cost
    :path [:agentDiagnostics :tokenTelemetry :outputTokens]
    :direction :lower}
   {:key :costUsd
    :label "costUsd"
    :category :token-cost
    :path [:agentDiagnostics :tokenTelemetry :costUsd]
    :direction :lower}
   {:key :elapsedMs
    :label "elapsedMs"
    :category :timing
    :path [:timings :elapsedMs]
    :direction :observe}
   {:key :warmElapsedMs
    :label "warmElapsedMs"
    :category :timing
    :path [:timings :warmElapsedMs]
    :direction :lower
    :tolerance 50.0}
   {:key :failedCases
    :label "failedCases"
    :category :timing
    :path [:timings :failedCases]
    :direction :lower}
   {:key :runningCases
    :label "runningCases"
    :category :timing
    :path [:timings :runningCases]
    :direction :lower}])

(def ^:private decision-metric-specs
  [{:key :decisionRecall
    :label "decisionRecall"
    :category :decision-quality
    :path [:scores :decisionRecall]
    :direction :higher}
   {:key :decisionPrecision
    :label "decisionPrecision"
    :category :decision-quality
    :path [:scores :decisionPrecision]
    :direction :higher}
   {:key :decisionF1
    :label "decisionF1"
    :category :decision-quality
    :path [:scores :decisionF1]
    :direction :higher}
   {:key :decisionEvidenceCitationRate
    :label "decisionEvidenceCitationRate"
    :category :decision-quality
    :path [:scores :decisionEvidenceCitationRate]
    :direction :higher}
   {:key :missingDecisionRuns
    :label "missingDecisionRuns"
    :category :decision-quality
    :path [:decisionDiagnostics :missingDecisionRuns]
    :direction :lower}
   {:key :decisionQualityGapRuns
    :label "decisionQualityGapRuns"
    :category :decision-quality
    :path [:decisionDiagnostics :gapRuns]
    :direction :lower}])

(def ^:private case-score-specs
  (->> metric-specs
       (filter #(= :scores (first (:path %))))
       vec))

(def ^:private group-metric-specs
  (->> metric-specs
       (remove #(= :timing (:category %)))
       vec))

(def ^:private claim-excluded-categories
  #{"timing"})

(def ^:private category-order
  (->> (concat metric-specs decision-metric-specs)
       (map (comp name :category))
       distinct
       vec))

(def ^:private headline-metric-keys
  [:fileRecallAt10
   :noiseRatioAt20
   :evidenceCitationRate
   :pathEvidenceCitationRate
   :commandCount
   :searchCommandCount
   :fileReadCommandCount
   :segmentCount
   :searchSegmentCount
   :fileReadSegmentCount
   :decisionF1
   :decisionEvidenceCitationRate
   :warmElapsedMs
   :elapsedMs
   :totalTokens
   :costUsd])

(def ^:private headline-summary-fields
  [[:fileRecallAt10 :fileRecallAt10Delta]
   [:noiseRatioAt20 :noiseRatioAt20Delta]
   [:evidenceCitationRate :evidenceCitationRateDelta]
   [:pathEvidenceCitationRate :pathEvidenceCitationRateDelta]
   [:commandCount :toolCallDelta]
   [:searchCommandCount :searchCommandDelta]
   [:fileReadCommandCount :fileReadDelta]
   [:decisionF1 :decisionF1Delta]
   [:decisionEvidenceCitationRate :decisionEvidenceCitationRateDelta]
   [:warmElapsedMs :warmElapsedMsDelta]
   [:elapsedMs :elapsedMsDelta]
   [:totalTokens :totalTokensDelta]
   [:costUsd :costUsdDelta]])

(def ^:private headline-summary-lines
  [[:fileRecallAt10Delta "fileRecallAt10 delta"]
   [:noiseRatioAt20Delta "noiseRatioAt20 delta"]
   [:evidenceCitationRateDelta "evidenceCitationRate delta"]
   [:pathEvidenceCitationRateDelta "pathEvidenceCitationRate delta"]
   [:toolCallDelta "tool call delta"]
   [:searchCommandDelta "search command delta"]
   [:fileReadDelta "file read delta"]
   [:decisionF1Delta "decisionF1 delta"]
   [:decisionEvidenceCitationRateDelta "decision evidence citation delta"]
   [:warmElapsedMsDelta "warmElapsedMs delta"]
   [:elapsedMsDelta "raw elapsedMs delta"]
   [:totalTokensDelta "totalTokens delta"]
   [:costUsdDelta "costUsd delta"]])

(def ^:private claim-requirement-keys
  [:sameSuite
   :sameCases
   :enoughSharedCases
   :yggImprovedWithoutRegressions
   :directionalMetrics
   :problemClassCoverage
   :architectureClassCoverage
   :evidenceMetrics
   :expectedEvidenceCitationMetrics
   :decisionQualityMetrics
   :commandTelemetry
   :perCaseTokenReduction
   :shellLaneClaimReady
   :yggLaneClaimReady])

(def ^:private measured-slice-requirement-keys
  [:sameSuite
   :sameCases
   :enoughSharedCases
   :yggImprovedWithoutRegressions
   :directionalMetrics
   :evidenceMetrics
   :expectedEvidenceCitationMetrics
   :decisionQualityMetrics
   :commandTelemetry
   :perCaseTokenReduction])

(def ^:private measured-slice-claim-requirement-keys
  (conj measured-slice-requirement-keys
        :shellLaneMeasuredSliceReady
        :yggLaneMeasuredSliceReady))

(def ^:private lane-coverage-requirement-keys
  #{:measuredProblemClasses
    :measuredArchitectureClasses})

(def ^:private lane-readiness-requirement-keys
  [:completedCases
   :hasRuns
   :evidenceCitationMetrics
   :expectedEvidenceCitationMetrics
   :decisionQualityMetrics
   :commandTelemetry
   :maintenancePreflight
   :measuredProblemClasses
   :measuredArchitectureClasses])

(def ^:private quality-tradeoff-metric-keys
  #{:fileRecallAt5
    :fileRecallAt10
    :fileRecallAt20
    :meanReciprocalRankFile
    :noiseRatioAt20
    :evidenceCitationRate
    :pathEvidenceCitationRate
    :expectedEvidenceCitationRate
    :decisionRecall
    :decisionPrecision
    :decisionF1
    :decisionEvidenceCitationRate})

(def ^:private token-tradeoff-metric-keys
  #{:totalTokens
    :inputTokens
    :outputTokens
    :costUsd})

(def ^:private case-token-metric-specs
  [{:key :taskTotalTokens
    :label "taskTotalTokens"
    :category :token-cost
    :path [:agent :tokenUsage :totalTokens]
    :direction :lower
    :requires-valid-token-usage? true}
   {:key :taskInputTokens
    :label "taskInputTokens"
    :category :token-cost
    :path [:agent :tokenUsage :inputTokens]
    :direction :lower
    :requires-valid-token-usage? true}
   {:key :taskOutputTokens
    :label "taskOutputTokens"
    :category :token-cost
    :path [:agent :tokenUsage :outputTokens]
    :direction :lower
    :requires-valid-token-usage? true}
   {:key :taskCostUsd
    :label "taskCostUsd"
    :category :token-cost
    :path [:agent :tokenUsage :costUsd]
    :direction :lower
    :requires-valid-token-usage? true}])

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- write-json-file!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str value {:indent-str "  "}) "\n"))
    (.getPath file)))

(defn- write-text-file!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str value "\n"))
    (.getPath file)))

(defn- metric-value
  [m path default]
  (let [value (get-in m path)]
    (if (number? value)
      (double value)
      default)))

(defn- positive-number?
  [value]
  (and (number? value)
       (pos? (double value))))

(defn- invalid-token-usage?
  [usage]
  (and (map? usage)
       (not (positive-number? (:totalTokens usage)))))

(defn- efficiency-report
  [report]
  (if (contains? report :improvementSummary)
    (assoc report :efficiency {:improvementTargetRuns
                               (benchmark-targets/target-runs report)
                               :improvementTargetRunsByKind
                               (benchmark-targets/target-runs-by-kind report)})
    report))

(defn- improvement-target-metric-specs
  [shell-report ygg-report]
  (->> (concat (keys (get-in shell-report
                             [:efficiency :improvementTargetRunsByKind]))
               (keys (get-in ygg-report
                             [:efficiency :improvementTargetRunsByKind])))
       set
       sort
       (mapv (fn [kind]
               (let [key (str "improvementTargetRuns." kind)]
                 {:key key
                  :label key
                  :category :result-health
                  :path [:efficiency :improvementTargetRunsByKind kind]
                  :default 0.0
                  :direction :lower})))))

(def ^:private segment-metric-specs
  [{:key :segmentCount
    :label "segmentCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :segmentCount]
    :direction :lower}
   {:key :yggSegmentCount
    :label "yggSegmentCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :yggSegmentCount]
    :direction :observe}
   {:key :searchSegmentCount
    :label "searchSegmentCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :searchSegmentCount]
    :direction :lower}
   {:key :fileReadSegmentCount
    :label "fileReadSegmentCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :fileReadSegmentCount]
    :direction :lower}
   {:key :shellSegmentCount
    :label "shellSegmentCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :shellSegmentCount]
    :direction :lower}])

(defn- segment-metric-specs-for
  [shell-report ygg-report]
  (if (or (some? (get-in shell-report [:agentDiagnostics :commandTelemetry :segmentCount]))
          (some? (get-in ygg-report [:agentDiagnostics :commandTelemetry :segmentCount])))
    segment-metric-specs
    []))

(defn- decision-quality-present?
  [report]
  (or (number? (get-in report [:scores :decisionF1]))
      (pos? (long (get-in report
                          [:decisionDiagnostics :configuredRuns]
                          0)))))

(defn- decision-metric-specs-for
  [shell-report ygg-report]
  (if (or (decision-quality-present? shell-report)
          (decision-quality-present? ygg-report))
    decision-metric-specs
    []))

(defn- case-token-usage-present?
  [report]
  (boolean
   (some #(map? (get-in % [:agent :tokenUsage]))
         (:results report))))

(defn- case-token-metric-specs-for
  [shell-report ygg-report]
  (if (or (case-token-usage-present? shell-report)
          (case-token-usage-present? ygg-report))
    case-token-metric-specs
    []))

(defn- case-score-specs-for
  [shell-report ygg-report]
  (->> (concat case-score-specs
               (filter #(= :scores (first (:path %)))
                       (decision-metric-specs-for shell-report ygg-report))
               (case-token-metric-specs-for shell-report ygg-report))
       vec))

(defn- result-label
  [effect]
  (cond
    (pos? effect) "improved"
    (neg? effect) "regressed"
    :else "unchanged"))

(defn- effective-effect
  [effect tolerance]
  (if (<= (abs effect) (double (or tolerance 0.0)))
    0.0
    effect))

(defn- metric-delta
  [shell-report ygg-report {:keys [category direction key label path tolerance default
                                   requires-valid-token-usage?]}]
  (let [shell-value (metric-value shell-report path default)
        ygg-value (metric-value ygg-report path default)
        available? (and (some? shell-value)
                        (some? ygg-value))
        invalid-token-usage-row? (and requires-valid-token-usage?
                                      (or (invalid-token-usage?
                                           (get-in shell-report [:agent :tokenUsage]))
                                          (invalid-token-usage?
                                           (get-in ygg-report [:agent :tokenUsage]))))]
    (cond
      (not available?)
      {:key key
       :metric label
       :category (name category)
       :direction (name direction)
       :shellOnly shell-value
       :ygg ygg-value
       :available false
       :result "unavailable"}

      invalid-token-usage-row?
      {:key key
       :metric label
       :category (name category)
       :direction (name direction)
       :shellOnly shell-value
       :ygg ygg-value
       :available false
       :result "invalid"
       :reason "non-positive-total-tokens"}

      :else
      (let [delta (- ygg-value shell-value)
            effect (case direction
                     :observe 0.0
                     :higher delta
                     :lower (- delta))
            effective (effective-effect effect tolerance)]
        (cond-> {:key key
                 :metric label
                 :category (name category)
                 :direction (name direction)
                 :shellOnly shell-value
                 :ygg ygg-value
                 :available true
                 :delta delta
                 :effect effective
                 :result (if (= :observe direction)
                           "observed"
                           (result-label effective))}
          tolerance (assoc :rawEffect effect
                           :tolerance tolerance))))))

(defn- result-by-case
  [report]
  (->> (:results report)
       (map (juxt :case-id identity))
       (into {})))

(defn- sorted-case-ids
  [report]
  (->> (:results report)
       (map :case-id)
       set
       sort
       vec))

(defn- count-results
  [deltas result]
  (count (filter #(= result (:result %)) deltas)))

(defn- report-modes
  [report]
  (->> (:results report)
       (map #(get-in % [:agent :mode]))
       (remove nil?)
       frequencies
       (into (sorted-map))))

(defn- report-agents
  [report]
  (->> (:results report)
       (map #(get-in % [:agent :agentId]))
       (remove nil?)
       frequencies
       (into (sorted-map))))

(defn- report-summary
  [report]
  {:schema (:schema report)
   :suiteId (:suite-id report)
   :cases (:cases report)
   :completed (:completed report)
   :runs (:runs report)
   :missing (:missing report)
   :modes (report-modes report)
   :agents (report-agents report)
   :scores (:scores report)
   :localizationDiagnostics (:localizationDiagnostics report)
   :agentDiagnostics (:agentDiagnostics report)
   :decisionDiagnostics (:decisionDiagnostics report)
   :improvementSummary (:improvementSummary report)
   :improvementTargetRuns (benchmark-targets/target-runs report)
   :improvementTargetRunsByKind (benchmark-targets/target-runs-by-kind report)
   :tags (:tags report)
   :claimReadiness (:claimReadiness report)
   :agentPreparation (:agentPreparation report)
   :timings (:timings report)})

(defn- rows-by-key
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [key (some-> (:key row) str not-empty)]
                 [key row])))
       (into {})))

(defn- comparability
  [shell-report ygg-report]
  (let [shell-cases (set (sorted-case-ids shell-report))
        ygg-cases (set (sorted-case-ids ygg-report))
        shell-only-cases (sort (remove ygg-cases shell-cases))
        ygg-only-cases (sort (remove shell-cases ygg-cases))
        shared-cases (sort (filter ygg-cases shell-cases))
        same-suite? (= (:suite-id shell-report) (:suite-id ygg-report))
        same-cases? (and (empty? shell-only-cases)
                         (empty? ygg-only-cases))]
    {:sameSuite same-suite?
     :sameCases same-cases?
     :sharedCases (count shared-cases)
     :sharedCaseIds (vec shared-cases)
     :shellOnlyCaseIds (vec shell-only-cases)
     :yggOnlyCaseIds (vec ygg-only-cases)
     :warnings (cond-> []
                 (not same-suite?)
                 (conj "Reports use different suite ids.")

                 (not same-cases?)
                 (conj "Reports do not contain the same completed case ids.")

                 (empty? shared-cases)
                 (conj "Reports have no shared completed cases."))}))

(defn- aggregate-summary
  ([deltas comparable]
   (aggregate-summary deltas comparable {}))
  ([deltas comparable {:keys [min-shared-cases]}]
   (let [improved (count-results deltas "improved")
         regressed (count-results deltas "regressed")
         unchanged (count-results deltas "unchanged")
         observed (count-results deltas "observed")
         unavailable (count-results deltas "unavailable")
         available (+ improved regressed unchanged observed)
         directional-available (+ improved regressed unchanged)
         min-shared-cases (long (or min-shared-cases 1))
         enough-cases? (<= min-shared-cases (long (:sharedCases comparable)))
         signal (cond
                  (zero? (:sharedCases comparable)) "not-comparable"
                  (not enough-cases?) "insufficient-cases"
                  (and (zero? directional-available) (pos? observed)) "observed-only"
                  (zero? directional-available) "metrics-unavailable"
                  (and (pos? improved) (zero? regressed)) "ygg-improved"
                  (and (zero? improved) (pos? regressed)) "ygg-regressed"
                  (and (pos? improved) (pos? regressed)) "mixed"
                  :else "unchanged")]
     (cond-> {:signal signal
              :minSharedCases min-shared-cases
              :availableMetrics available
              :improvedMetrics improved
              :regressedMetrics regressed
              :unchangedMetrics unchanged
              :unavailableMetrics unavailable}
       (pos? observed) (assoc :observedMetrics observed)))))

(defn- tradeoff-rows
  [deltas metric-keys]
  (let [metric-keys (set metric-keys)]
    (filterv #(contains? metric-keys (:key %)) deltas)))

(defn- claim-deltas
  [deltas]
  (filterv #(not (contains? claim-excluded-categories (:category %)))
           deltas))

(defn- tradeoff-counts
  [rows]
  {:availableMetrics (count (filter :available rows))
   :improvedMetrics (count-results rows "improved")
   :regressedMetrics (count-results rows "regressed")
   :unchangedMetrics (count-results rows "unchanged")
   :unavailableMetrics (count-results rows "unavailable")})

(defn- compact-tradeoff-delta
  [row]
  (select-keys row [:key :metric :shellOnly :ygg :delta :result]))

(defn- tradeoff-deltas-by-key
  [rows]
  (into {}
        (keep (fn [{:keys [key delta available]}]
                (when available
                  [key delta])))
        rows))

(defn- quality-token-tradeoff
  [deltas]
  (let [quality-rows (tradeoff-rows deltas quality-tradeoff-metric-keys)
        token-rows (tradeoff-rows deltas token-tradeoff-metric-keys)
        token-present? (some #(or (some? (:shellOnly %))
                                  (some? (:ygg %)))
                             token-rows)
        quality-counts (tradeoff-counts quality-rows)
        token-counts (tradeoff-counts token-rows)
        quality-improved? (pos? (:improvedMetrics quality-counts))
        quality-regressed? (pos? (:regressedMetrics quality-counts))
        token-improved? (pos? (:improvedMetrics token-counts))
        token-regressed? (pos? (:regressedMetrics token-counts))
        token-incomplete? (and token-present?
                               (pos? (:unavailableMetrics token-counts)))
        status (cond
                 (not token-present?) nil
                 token-incomplete? "token-metrics-incomplete"
                 (zero? (:availableMetrics quality-counts))
                 "quality-metrics-unavailable"
                 (and quality-improved? (not quality-regressed?)
                      token-improved? (not token-regressed?))
                 "better-quality-lower-token-cost"
                 (and quality-improved? token-regressed?)
                 "better-quality-higher-token-cost"
                 (and quality-regressed? token-improved?
                      (not token-regressed?))
                 "lower-quality-lower-token-cost"
                 (and quality-regressed? token-regressed?)
                 "lower-quality-higher-token-cost"
                 (and (not quality-improved?) (not quality-regressed?)
                      token-improved? (not token-regressed?))
                 "same-quality-lower-token-cost"
                 (and (not quality-improved?) (not quality-regressed?)
                      token-regressed?)
                 "same-quality-higher-token-cost"
                 :else "mixed-quality-token-tradeoff")]
    (when status
      {:status status
       :quality (assoc quality-counts
                       :metrics (mapv compact-tradeoff-delta quality-rows))
       :tokenCost (assoc token-counts
                         :metrics (mapv compact-tradeoff-delta token-rows))
       :headlineDeltas (merge (tradeoff-deltas-by-key quality-rows)
                              (tradeoff-deltas-by-key token-rows))})))

(defn- context-artifact-telemetry
  [report]
  (get-in report [:agentDiagnostics :contextArtifactTelemetry]))

(defn- progressive-disclosure-summary
  [telemetry]
  (when telemetry
    (merge (select-keys telemetry [:runs
                                   :caseIds
                                   :hintSavingsRatio
                                   :frontloadToExpansionRatio])
           (select-keys (:totals telemetry)
                        [:promptBytes
                         :compactHintsBytes
                         :fullHintsBytes
                         :contextBytes
                         :frontloadBytes
                         :expansionBytes
                         :fullAvailableBytes
                         :hintSavingsBytes
                         :readPlanSnippetCount
                         :readPlanSnippetBytes]))))

(defn- context-artifact-comparison
  [shell-report ygg-report]
  (let [shell-telemetry (context-artifact-telemetry shell-report)
        ygg-telemetry (context-artifact-telemetry ygg-report)]
    (when (or shell-telemetry ygg-telemetry)
      (cond-> {}
        shell-telemetry
        (assoc :shellOnly shell-telemetry)
        ygg-telemetry
        (assoc :ygg ygg-telemetry
               :progressiveDisclosure
               (progressive-disclosure-summary ygg-telemetry))))))

(defn- prepared-agent-status
  [summary]
  (cond
    (nil? summary)
    "unavailable"

    (true? (:allRunsReadyBeforeAgent summary))
    "ready-before-agent"

    (pos? (long (or (:preparedDuringAgentRuns summary) 0)))
    "prepared-during-agent-run"

    (pos? (long (or (:missingRuns summary) 0)))
    "missing-preparation-evidence"

    :else
    "mixed"))

(defn- prepared-agent-evidence
  [shell-report ygg-report]
  (let [shell-preparation (:agentPreparation shell-report)
        ygg-preparation (:agentPreparation ygg-report)]
    (when (or shell-preparation ygg-preparation)
      {:status (prepared-agent-status ygg-preparation)
       :shellOnly shell-preparation
       :ygg ygg-preparation
       :primaryWarmBasis "Yggdrasil warmElapsedMs is strongest when the Ygg lane reports allRunsReadyBeforeAgent=true; otherwise setup was only amortized or preparation evidence is missing."})))

(defn- tag-comparability
  [shell-report ygg-report]
  (let [shell-tags (set (keys (rows-by-key (:byTag shell-report))))
        ygg-tags (set (keys (rows-by-key (:byTag ygg-report))))
        shell-only-tags (sort (remove ygg-tags shell-tags))
        ygg-only-tags (sort (remove shell-tags ygg-tags))
        shared-tags (sort (filter ygg-tags shell-tags))
        same-tags? (and (empty? shell-only-tags)
                        (empty? ygg-only-tags))]
    {:sameTags same-tags?
     :sharedTags (count shared-tags)
     :sharedTagKeys (vec shared-tags)
     :shellOnlyTagKeys (vec shell-only-tags)
     :yggOnlyTagKeys (vec ygg-only-tags)
     :warnings (cond-> []
                 (not same-tags?)
                 (conj "Reports do not contain the same completed tag groups.")

                 (empty? shared-tags)
                 (conj "Reports have no shared completed tag groups."))}))

(defn- tag-deltas
  [shell-report ygg-report]
  (let [shell-by-tag (rows-by-key (:byTag shell-report))
        ygg-by-tag (rows-by-key (:byTag ygg-report))
        shared-tags (->> (keys shell-by-tag)
                         (filter #(contains? ygg-by-tag %))
                         sort)]
    (mapv (fn [tag]
            (let [shell-row (get shell-by-tag tag)
                  ygg-row (get ygg-by-tag tag)
                  deltas (mapv #(metric-delta shell-row ygg-row %)
                               group-metric-specs)]
              {:tag tag
               :shellOnly {:cases (long (or (:cases shell-row) 0))
                           :runs (long (or (:runs shell-row) 0))}
               :ygg {:cases (long (or (:cases ygg-row) 0))
                     :runs (long (or (:runs ygg-row) 0))}
               :summary (aggregate-summary deltas {:sharedCases 1})
               :deltas deltas}))
          shared-tags)))

(defn- by-tag-comparison
  [shell-report ygg-report]
  {:comparability (tag-comparability shell-report ygg-report)
   :groups (tag-deltas shell-report ygg-report)})

(defn- class-keys
  [report class-key]
  (->> (get-in report [:problemClasses class-key])
       (keep #(some-> (:key %) str))
       sort
       vec))

(defn- measured-class-keys
  [report class-key]
  (->> (get-in report [:problemClasses class-key])
       (filter #(= "measured" (:claimStatus %)))
       (keep #(some-> (:key %) str))
       sort
       vec))

(defn- shared-keys
  [left right]
  (let [right-set (set right)]
    (->> left
         (filter right-set)
         sort
         vec)))

(defn- problem-class-summary-available?
  [shell-report ygg-report]
  (and (map? (:problemClasses shell-report))
       (map? (:problemClasses ygg-report))))

(defn- problem-class-coverage
  [shell-report ygg-report by-tag]
  (let [shared-tags (get-in by-tag [:comparability :sharedTagKeys])
        problem-tags (filterv benchmark-classes/problem-class-tag? shared-tags)
        architecture-tags (filterv benchmark-classes/architecture-class-tag?
                                   shared-tags)
        summary-available? (problem-class-summary-available? shell-report
                                                             ygg-report)
        shell-measured-problem-tags (measured-class-keys shell-report :classes)
        ygg-measured-problem-tags (measured-class-keys ygg-report :classes)
        shared-measured-problem-tags (shared-keys shell-measured-problem-tags
                                                  ygg-measured-problem-tags)
        shell-measured-architecture-tags (measured-class-keys
                                          shell-report
                                          :architectureClasses)
        ygg-measured-architecture-tags (measured-class-keys
                                        ygg-report
                                        :architectureClasses)
        shared-measured-architecture-tags (shared-keys
                                           shell-measured-architecture-tags
                                           ygg-measured-architecture-tags)]
    {:sharedTagKeys (vec shared-tags)
     :problemClassTags problem-tags
     :architectureClassTags architecture-tags
     :problemClassSummaryAvailable summary-available?
     :shellProblemClassTags (class-keys shell-report :classes)
     :yggProblemClassTags (class-keys ygg-report :classes)
     :shellMeasuredProblemClassTags shell-measured-problem-tags
     :yggMeasuredProblemClassTags ygg-measured-problem-tags
     :sharedMeasuredProblemClassTags shared-measured-problem-tags
     :shellArchitectureClassTags (class-keys shell-report :architectureClasses)
     :yggArchitectureClassTags (class-keys ygg-report :architectureClasses)
     :shellMeasuredArchitectureClassTags shell-measured-architecture-tags
     :yggMeasuredArchitectureClassTags ygg-measured-architecture-tags
     :sharedMeasuredArchitectureClassTags shared-measured-architecture-tags
     :hasProblemClasses (boolean (seq problem-tags))
     :hasArchitectureClasses (boolean (seq architecture-tags))
     :hasMeasuredProblemClasses (boolean (seq shared-measured-problem-tags))
     :hasMeasuredArchitectureClasses (boolean (seq shared-measured-architecture-tags))
     :broadEfficiencyClaimSupported (boolean
                                     (and summary-available?
                                          (seq shared-measured-problem-tags)
                                          (seq shared-measured-architecture-tags)))
     :warnings (cond-> []
                 (empty? problem-tags)
                 (conj "No shared problem-class tags; do not use this report for broad efficiency claims.")

                 (empty? architecture-tags)
                 (conj "No shared architecture-class tags; do not use this report to claim representative architecture-task gains.")

                 (not summary-available?)
                 (conj "Problem-class measurement summaries are unavailable; regenerate agent reports before claiming broad efficiency.")

                 (and summary-available? (empty? shared-measured-problem-tags))
                 (conj "No shared measured problem-class groups; class tags are present but below the benchmark claim threshold in at least one lane.")

                 (and summary-available? (empty? shared-measured-architecture-tags))
                 (conj "No shared measured architecture-class groups; architecture tags are present but below the benchmark claim threshold in at least one lane."))}))

(defn- class-signal-row
  [measured-tags group]
  (assoc (select-keys group [:tag :shellOnly :ygg :summary])
         :measured (contains? measured-tags (:tag group))))

(defn- class-signals-summary
  [problem-classes architecture-classes]
  {:problemClasses (count problem-classes)
   :measuredProblemClasses (count (filter :measured problem-classes))
   :architectureClasses (count architecture-classes)
   :measuredArchitectureClasses (count (filter :measured
                                               architecture-classes))})

(defn- class-signals
  [by-tag problem-coverage]
  (let [groups (:groups by-tag)
        measured-problem-tags (set (:sharedMeasuredProblemClassTags
                                    problem-coverage))
        measured-architecture-tags (set (:sharedMeasuredArchitectureClassTags
                                         problem-coverage))
        problem-classes (->> groups
                             (filter #(benchmark-classes/problem-class-tag?
                                       (:tag %)))
                             (mapv #(class-signal-row measured-problem-tags %)))
        architecture-classes (->> groups
                                  (filter #(benchmark-classes/architecture-class-tag?
                                            (:tag %)))
                                  (mapv #(class-signal-row
                                          measured-architecture-tags
                                          %)))]
    {:summary (class-signals-summary problem-classes architecture-classes)
     :problemClasses problem-classes
     :architectureClasses architecture-classes}))

(defn- category-summaries
  [deltas comparable min-shared-cases]
  (let [by-category (group-by :category deltas)]
    (->> category-order
         (keep (fn [category]
                 (when-let [category-deltas (seq (get by-category category))]
                   {:category category
                    :metrics (mapv :metric category-deltas)
                    :summary (aggregate-summary
                              category-deltas
                              comparable
                              {:min-shared-cases min-shared-cases})})))
         vec)))

(defn- category-summary
  [by-category category]
  (some-> (first (filter #(= category (:category %)) by-category))
          :summary))

(defn- category-has-metrics?
  [by-category category]
  (pos? (long (or (:availableMetrics (category-summary by-category category))
                  0))))

(defn- category-has-directional-metrics?
  [by-category category]
  (let [summary (category-summary by-category category)]
    (pos? (long (+ (or (:improvedMetrics summary) 0)
                   (or (:regressedMetrics summary) 0)
                   (or (:unchangedMetrics summary) 0))))))

(defn- expected-evidence-citation-present?
  [report]
  (number? (get-in report [:scores :expectedEvidenceCitationRate])))

(defn- expected-evidence-citation-comparable?
  [shell-report ygg-report]
  (let [shell? (expected-evidence-citation-present? shell-report)
        ygg? (expected-evidence-citation-present? ygg-report)]
    (= shell? ygg?)))

(defn- decision-score-present?
  [report]
  (and (number? (get-in report [:scores :decisionF1]))
       (number? (get-in report [:scores :decisionEvidenceCitationRate]))))

(defn- decision-quality-comparable?
  [shell-report ygg-report]
  (or (not (or (decision-quality-present? shell-report)
               (decision-quality-present? ygg-report)))
      (and (decision-score-present? shell-report)
           (decision-score-present? ygg-report))))

(defn- lane-claim-ready?
  [report]
  (let [claim-readiness (:claimReadiness report)]
    (if (map? claim-readiness)
      (true? (:broadArchitectureClaimSupported claim-readiness))
      true)))

(defn- lane-claim-known?
  [report]
  (map? (:claimReadiness report)))

(defn- failed-requirement-keys
  [ordered-keys requirements]
  (->> ordered-keys
       (remove #(true? (get requirements %)))
       vec))

(defn- failed-map-keys
  [ordered-keys requirements]
  (let [ordered-set (set ordered-keys)
        remaining-keys (->> (keys requirements)
                            (remove ordered-set)
                            (sort-by name))]
    (->> (concat ordered-keys remaining-keys)
         (filter #(contains? requirements %))
         (remove #(true? (get requirements %)))
         vec)))

(defn- lane-measured-slice-readiness
  [report]
  (let [claim-readiness (:claimReadiness report)
        requirements (:requirements claim-readiness)
        failed (when (map? requirements)
                 (failed-map-keys lane-readiness-requirement-keys
                                  requirements))
        non-coverage-failed (filterv (complement lane-coverage-requirement-keys)
                                     failed)
        coverage-failed (filterv lane-coverage-requirement-keys failed)
        ready? (cond
                 (not (map? claim-readiness))
                 false

                 (true? (:broadArchitectureClaimSupported claim-readiness))
                 true

                 (seq failed)
                 (empty? non-coverage-failed)

                 :else
                 false)]
    {:status (if ready? "supported" "not-supported")
     :ready ready?
     :broadClaimStatus (:status claim-readiness)
     :broadArchitectureClaimSupported
     (boolean (:broadArchitectureClaimSupported claim-readiness))
     :coverageFailedRequirements coverage-failed
     :failedRequirements non-coverage-failed}))

(defn- measured-slice-claim
  [claim-readiness shell-report ygg-report]
  (let [requirements (:requirements claim-readiness)
        shell-lane (lane-measured-slice-readiness shell-report)
        ygg-lane (lane-measured-slice-readiness ygg-report)
        measured-requirements (assoc
                               (select-keys requirements
                                            measured-slice-requirement-keys)
                               :shellLaneMeasuredSliceReady
                               (:ready shell-lane)
                               :yggLaneMeasuredSliceReady
                               (:ready ygg-lane))
        failed (failed-requirement-keys measured-slice-claim-requirement-keys
                                        measured-requirements)
        supported? (empty? failed)]
    {:status (if supported? "supported" "not-supported")
     :measuredSliceClaimSupported supported?
     :scope "shared-case-metrics"
     :requirements measured-requirements
     :failedRequirements failed
     :laneReadiness {:shellOnly shell-lane
                     :ygg ygg-lane}
     :notes (cond-> []
              (and supported?
                   (not (true?
                         (:broadEfficiencyClaimSupported claim-readiness))))
              (conj "Measured shared cases support the efficiency claim, but broad class coverage remains incomplete."))}))

(defn- claim-readiness
  [summary claim-summary comparable by-category problem-coverage shell-report ygg-report
   per-case-token-reduction]
  (let [same-suite? (true? (:sameSuite comparable))
        same-cases? (true? (:sameCases comparable))
        enough-cases? (<= (long (:minSharedCases summary))
                          (long (:sharedCases comparable)))
        improved-without-regressions? (and (= "ygg-improved"
                                              (:signal claim-summary))
                                           (zero? (:regressedMetrics
                                                   claim-summary)))
        directional-metrics? (pos? (long (+ (:improvedMetrics claim-summary)
                                            (:regressedMetrics claim-summary)
                                            (:unchangedMetrics claim-summary))))
        problem-classes? (true? (:hasMeasuredProblemClasses problem-coverage))
        architecture-classes? (true? (:hasMeasuredArchitectureClasses
                                      problem-coverage))
        evidence-metrics? (category-has-metrics? by-category "evidence")
        expected-evidence-metrics? (expected-evidence-citation-comparable?
                                    shell-report
                                    ygg-report)
        decision-quality-metrics? (decision-quality-comparable?
                                   shell-report
                                   ygg-report)
        command-telemetry? (category-has-directional-metrics?
                            by-category
                            "command-telemetry")
        token-cost-metrics? (category-has-metrics? by-category "token-cost")
        per-case-token-reduction? (true? (:allSharedCasesReduced
                                          per-case-token-reduction))
        shell-lane-ready? (lane-claim-ready? shell-report)
        ygg-lane-ready? (lane-claim-ready? ygg-report)
        shell-lane-known? (lane-claim-known? shell-report)
        ygg-lane-known? (lane-claim-known? ygg-report)
        requirements {:sameSuite same-suite?
                      :sameCases same-cases?
                      :enoughSharedCases enough-cases?
                      :yggImprovedWithoutRegressions
                      improved-without-regressions?
                      :directionalMetrics directional-metrics?
                      :problemClassCoverage problem-classes?
                      :architectureClassCoverage architecture-classes?
                      :evidenceMetrics evidence-metrics?
                      :expectedEvidenceCitationMetrics expected-evidence-metrics?
                      :decisionQualityMetrics decision-quality-metrics?
                      :commandTelemetry command-telemetry?
                      :perCaseTokenReduction per-case-token-reduction?
                      :shellLaneClaimReady shell-lane-ready?
                      :yggLaneClaimReady ygg-lane-ready?}
        supported? (every? true? (vals requirements))]
    {:status (if supported? "supported" "not-supported")
     :broadEfficiencyClaimSupported supported?
     :sharedCases (long (:sharedCases comparable))
     :minSharedCases (long (:minSharedCases summary))
     :requirements requirements
     :laneClaimReadiness {:shellOnly (:claimReadiness shell-report)
                          :ygg (:claimReadiness ygg-report)}
     :optionalTelemetry {:tokenCostMetrics token-cost-metrics?}
     :warnings (cond-> []
                 (not same-suite?)
                 (conj "Reports use different suite ids; rerun both lanes on the same suite before claiming efficiency.")

                 (not same-cases?)
                 (conj "Reports do not contain the same completed case ids; compare matched lanes before claiming efficiency.")

                 (not enough-cases?)
                 (conj (str "Only " (:sharedCases comparable)
                            " shared completed case(s); require at least "
                            (:minSharedCases summary)
                            " before claiming efficiency."))

                 (not improved-without-regressions?)
                 (conj "Aggregate metrics do not show an unregressed Yggdrasil improvement; report class-level tradeoffs instead of a broad win.")

                 (not directional-metrics?)
                 (conj "Directional efficiency metrics are unavailable; observed telemetry alone cannot support an improvement claim.")

                 (not evidence-metrics?)
                 (conj "Evidence citation metrics are unavailable; answer quality and citation quality are unproven.")

                 (not expected-evidence-metrics?)
                 (conj "Expected evidence citation metrics are only available in one lane; non-code evidence citation quality is not comparable.")

                 (not decision-quality-metrics?)
                 (conj "Decision-quality metrics are configured in at least one lane but are not comparable; complex-system decision quality is unproven.")

                 (not command-telemetry?)
                 (conj "Command telemetry is unavailable; CLI search/read-loop savings are unproven.")

                 (not per-case-token-reduction?)
                 (conj "Yggdrasil total token usage is not measured lower for every shared case; token-reduction claims are unproven or regressed.")

                 (not shell-lane-known?)
                 (conj "Shell-only report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim.")

                 (and shell-lane-known? (not shell-lane-ready?))
                 (conj "Shell-only report is not claim-ready; fix that lane before comparing broad efficiency.")

                 (not ygg-lane-known?)
                 (conj "Yggdrasil report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim.")

                 (and ygg-lane-known? (not ygg-lane-ready?))
                 (conj "Yggdrasil report is not claim-ready; fix that lane before comparing broad efficiency.")

                 (not (and problem-classes? architecture-classes?))
                 (into (:warnings problem-coverage)))
     :notes (cond-> []
              (not token-cost-metrics?)
              (conj "Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."))}))

(defn- case-audit-scope-summary
  "Extract a compact audit-scope summary from one case result."
  [result]
  (let [audit-scope (:auditScope result)]
    (when (map? audit-scope)
      (let [gt (:groundTruthSummary audit-scope)
            ge (:graphExpectationSummary audit-scope)]
        (not-empty
         (cond-> {}
           gt (assoc :groundTruth gt)
           ge (assoc :graphExpectations ge)))))))

(defn- task-token-deltas
  [deltas]
  (->> deltas
       (filter #(= "token-cost" (:category %)))
       vec))

(defn- case-deltas
  [shell-report ygg-report score-specs]
  (let [shell-by-case (result-by-case shell-report)
        ygg-by-case (result-by-case ygg-report)
        shared-case-ids (->> (keys shell-by-case)
                             (filter #(contains? ygg-by-case %))
                             sort)]
    (mapv (fn [case-id]
            (let [shell-result (get shell-by-case case-id)
                  ygg-result (get ygg-by-case case-id)
                  deltas (mapv #(metric-delta shell-result
                                              ygg-result
                                              %)
                               score-specs)
                  token-deltas (task-token-deltas deltas)
                  shell-audit (case-audit-scope-summary shell-result)
                  ygg-audit (case-audit-scope-summary ygg-result)]
              (cond-> {:caseId case-id
                       :summary (aggregate-summary deltas {:sharedCases 1})
                       :deltas deltas}
                (seq token-deltas) (assoc :taskTokenDeltas token-deltas)
                shell-audit (assoc :shellOnlyAuditScope shell-audit)
                ygg-audit (assoc :yggAuditScope ygg-audit))))
          shared-case-ids)))

(defn- task-total-token-delta
  [case-delta]
  (some #(when (= :taskTotalTokens (:key %)) %)
        (:taskTokenDeltas case-delta)))

(defn- case-token-reduction-row
  [case-deltas-by-id case-id]
  (let [row (task-total-token-delta (get case-deltas-by-id case-id))
        shell-value (:shellOnly row)
        ygg-value (:ygg row)
        measured? (and (positive-number? shell-value)
                       (positive-number? ygg-value))
        status (cond
                 (nil? row) "unavailable"
                 (not (and (number? shell-value)
                           (number? ygg-value))) "unavailable"
                 (not measured?) "invalid"
                 (< (double ygg-value) (double shell-value)) "improved"
                 (> (double ygg-value) (double shell-value)) "regressed"
                 :else "unchanged")]
    (cond-> {:caseId case-id
             :status status
             :measured measured?}
      (some? shell-value) (assoc :shellOnly shell-value)
      (some? ygg-value) (assoc :ygg ygg-value)
      (and measured? (some? (:delta row))) (assoc :delta (:delta row)))))

(defn- per-case-token-reduction
  [comparable case-deltas]
  (let [case-deltas-by-id (into {} (map (juxt :caseId identity)) case-deltas)
        rows (mapv #(case-token-reduction-row case-deltas-by-id %)
                   (:sharedCaseIds comparable))
        case-ids-by-status (fn [status]
                             (->> rows
                                  (filter #(= status (:status %)))
                                  (mapv :caseId)))
        shared-count (long (count rows))
        measured-count (long (count (filter :measured rows)))
        improved-case-ids (case-ids-by-status "improved")
        regressed-case-ids (case-ids-by-status "regressed")
        unchanged-case-ids (case-ids-by-status "unchanged")
        unavailable-case-ids (case-ids-by-status "unavailable")
        invalid-case-ids (case-ids-by-status "invalid")
        all-measured? (and (pos? shared-count)
                           (= shared-count measured-count))
        all-reduced? (and all-measured?
                          (= shared-count (count improved-case-ids)))]
    {:sharedCases shared-count
     :measuredCases measured-count
     :improvedCases (count improved-case-ids)
     :regressedCases (count regressed-case-ids)
     :unchangedCases (count unchanged-case-ids)
     :unavailableCases (count unavailable-case-ids)
     :invalidCases (count invalid-case-ids)
     :allSharedCasesMeasured all-measured?
     :allSharedCasesReduced all-reduced?
     :improvedCaseIds improved-case-ids
     :regressedCaseIds regressed-case-ids
     :unchangedCaseIds unchanged-case-ids
     :unavailableCaseIds unavailable-case-ids
     :invalidCaseIds invalid-case-ids
     :cases rows
     :warnings (cond-> []
                 (seq unavailable-case-ids)
                 (conj "Task total token telemetry is missing for at least one shared case.")

                 (seq invalid-case-ids)
                 (conj "Task total token telemetry contains zero or non-positive placeholder values.")

                 (seq regressed-case-ids)
                 (conj "At least one shared case used more Yggdrasil tokens than shell-only.")

                 (seq unchanged-case-ids)
                 (conj "At least one shared case did not reduce total token usage."))}))

(defn- headline-metric-deltas-from-deltas
  [deltas]
  (let [deltas-by-key (into {} (map (juxt :key identity)) deltas)]
    (->> headline-metric-keys
         (keep deltas-by-key)
         vec)))

(defn- headline-summary
  [{:keys [summary comparable headline-metrics claim-readiness]}]
  (let [metrics-by-key (into {} (map (juxt :key identity)) headline-metrics)
        requirements (:requirements claim-readiness)
        metric-fields (into {}
                            (keep (fn [[metric-key field]]
                                    (when (contains? metrics-by-key metric-key)
                                      [field (:delta (get metrics-by-key
                                                          metric-key))])))
                            headline-summary-fields)]
    (merge {:status (:signal summary)
            :claimStatus (:status claim-readiness)
            :broadEfficiencyClaimSupported
            (:broadEfficiencyClaimSupported claim-readiness)
            :sharedCases (long (:sharedCases comparable))
            :minSharedCases (long (:minSharedCases summary))
            :failedRequirements (->> claim-requirement-keys
                                     (remove #(true? (get requirements %)))
                                     vec)
            :unavailableMetrics (->> headline-metrics
                                     (filter #(= "unavailable" (:result %)))
                                     (mapv :key))}
           metric-fields)))

(defn- compact-summary
  [{:keys [summary claim-summary comparable claim-readiness
           measured-slice-claim]}]
  (let [requirements (:requirements claim-readiness)
        claim-summary (or claim-summary summary)
        shared-cases (long (:sharedCases comparable))
        min-shared-cases (long (:minSharedCases summary))
        improved (long (:improvedMetrics claim-summary))
        regressed (long (:regressedMetrics claim-summary))
        unavailable (long (:unavailableMetrics claim-summary))
        enough-cases? (true? (:enoughSharedCases requirements))
        comparable? (and (true? (:sameSuite requirements))
                         (true? (:sameCases requirements))
                         (pos? shared-cases))
        directional? (true? (:directionalMetrics requirements))
        claim-ready? (= "supported" (:status claim-readiness))
        measured-slice-ready? (= "supported" (:status measured-slice-claim))
        verdict (cond
                  (not comparable?) "inconclusive"
                  (not enough-cases?) "inconclusive"
                  (not directional?) "inconclusive"
                  (and (pos? regressed) (zero? improved)) "regressed"
                  (and (pos? improved)
                       (zero? regressed)
                       (or claim-ready? measured-slice-ready?)) "helped"
                  :else "inconclusive")
        why (cond-> []
              (not comparable?)
              (conj "Reports must use the same suite and completed case ids.")

              (and comparable? (not enough-cases?))
              (conj (str "Only " shared-cases
                         " shared case(s); require at least "
                         min-shared-cases "."))

              (not directional?)
              (conj "Directional metrics are unavailable.")

              (not (true? (:perCaseTokenReduction requirements)))
              (conj "Yggdrasil total token usage must be measured lower for every shared case.")

              (pos? improved)
              (conj (str improved " directional metric(s) improved."))

              (pos? regressed)
              (conj (str regressed " directional metric(s) regressed."))

              (pos? unavailable)
              (conj (str unavailable " metric(s) were unavailable."))

              (and (= "helped" verdict) claim-ready?)
              (conj "Compared lanes are claim-ready for the measured architecture slice.")

              (and (= "helped" verdict)
                   (not claim-ready?)
                   measured-slice-ready?)
              (conj "Yggdrasil helped on the measured shared cases; broad efficiency claim still needs class coverage.")

              (and (= "inconclusive" verdict) (not claim-ready?))
              (conj "Claim readiness is not supported; use the warnings before making the benchmark claim."))]
    {:verdict verdict
     :status (:signal claim-summary)
     :claimStatus (:status claim-readiness)
     :sharedCases shared-cases
     :minSharedCases min-shared-cases
     :improvedMetrics improved
     :regressedMetrics regressed
     :unavailableMetrics unavailable
     :why why}))

(defn compare-reports
  "Return a shell-only vs Yggdrasil efficiency comparison from two agent reports."
  ([shell-report ygg-report]
   (compare-reports shell-report ygg-report {}))
  ([shell-report ygg-report {:keys [min-shared-cases]}]
   (let [shell-efficiency-report (efficiency-report shell-report)
         ygg-efficiency-report (efficiency-report ygg-report)
         comparable (comparability shell-report ygg-report)
         decision-specs (decision-metric-specs-for
                         shell-efficiency-report
                         ygg-efficiency-report)
         deltas (mapv #(metric-delta shell-efficiency-report
                                     ygg-efficiency-report
                                     %)
                      (into metric-specs
                            (concat
                             (segment-metric-specs-for
                              shell-efficiency-report
                              ygg-efficiency-report)
                             decision-specs
                             (improvement-target-metric-specs
                              shell-efficiency-report
                              ygg-efficiency-report))))
         min-shared-cases (long (or min-shared-cases default-min-shared-cases))
         summary (aggregate-summary deltas
                                    comparable
                                    {:min-shared-cases min-shared-cases})
         claim-summary (aggregate-summary (claim-deltas deltas)
                                          comparable
                                          {:min-shared-cases min-shared-cases})
         by-category (category-summaries deltas comparable min-shared-cases)
         by-tag (by-tag-comparison shell-report ygg-report)
         problem-coverage (problem-class-coverage shell-report
                                                  ygg-report
                                                  by-tag)
         case-deltas-result (case-deltas shell-report
                                         ygg-report
                                         (case-score-specs-for
                                          shell-efficiency-report
                                          ygg-efficiency-report))
         per-case-token-reduction-result (per-case-token-reduction
                                          comparable
                                          case-deltas-result)
         claim-readiness-result (claim-readiness summary
                                                 claim-summary
                                                 comparable
                                                 by-category
                                                 problem-coverage
                                                 shell-report
                                                 ygg-report
                                                 per-case-token-reduction-result)
         measured-slice-claim-result (measured-slice-claim
                                      claim-readiness-result
                                      shell-report
                                      ygg-report)
         headline-metrics (headline-metric-deltas-from-deltas deltas)
         quality-cost-tradeoff (quality-token-tradeoff deltas)
         context-artifacts (context-artifact-comparison shell-report ygg-report)
         prepared-agent-evidence-result (prepared-agent-evidence shell-report
                                                                 ygg-report)
         compact-summary-result (compact-summary
                                 {:summary summary
                                  :claim-summary claim-summary
                                  :comparable comparable
                                  :claim-readiness claim-readiness-result
                                  :measured-slice-claim
                                  measured-slice-claim-result})]
     (cond-> {:schema schema
              :status (:signal claim-summary)
              :suiteId (or (:suite-id ygg-report)
                           (:suite-id shell-report))
              :compactSummary compact-summary-result
              :summary summary
              :claimSummary claim-summary
              :comparability comparable
              :shellOnly (report-summary shell-report)
              :ygg (report-summary ygg-report)
              :deltas deltas
              :headlineMetrics headline-metrics
              :headlineSummary (headline-summary {:summary claim-summary
                                                  :comparable comparable
                                                  :headline-metrics headline-metrics
                                                  :claim-readiness
                                                  claim-readiness-result})
              :byCategory by-category
              :byTag by-tag
              :classSignals (class-signals by-tag problem-coverage)
              :problemClassCoverage problem-coverage
              :perCaseTokenReduction per-case-token-reduction-result
              :measuredSliceClaim measured-slice-claim-result
              :claimReadiness claim-readiness-result
              :caseDeltas case-deltas-result}
       context-artifacts
       (assoc :contextArtifacts context-artifacts)
       prepared-agent-evidence-result
       (assoc :preparedAgentEvidence prepared-agent-evidence-result)
       quality-cost-tradeoff
       (assoc :qualityCostTradeoff quality-cost-tradeoff)))))

(defn compare-report-files!
  "Read two agent-report JSON files and optionally write the comparison."
  [shell-report-path ygg-report-path opts]
  (let [comparison (-> (read-json-file shell-report-path)
                       (compare-reports (read-json-file ygg-report-path)
                                        {:min-shared-cases (:min-shared-cases opts)})
                       (assoc-in [:inputs :shellReport] shell-report-path)
                       (assoc-in [:inputs :yggReport] ygg-report-path))]
    (when-let [out (:out opts)]
      (write-json-file! out comparison))
    (when-let [out (:markdown-out opts)]
      (write-text-file! out (markdown-report comparison)))
    comparison))

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn- flag?
  [args flag]
  (some #{flag} args))

(defn- parse-positive-long
  [value flag]
  (try
    (let [n (Long/parseLong (str value))]
      (when-not (pos? n)
        (throw (ex-info "Option must be positive."
                        {:flag flag
                         :value value})))
      n)
    (catch NumberFormatException _
      (throw (ex-info "Option must be an integer."
                      {:flag flag
                       :value value})))))

(defn- positional-args
  [args]
  (vec (take-while #(not (str/starts-with? % "--")) args)))

(defn- category-line
  [{:keys [category summary]}]
  (str "- " category ": " (:signal summary)
       (when-let [observed (:observedMetrics summary)]
         (str " (observed metrics: " observed ")"))))

(defn- tag-group-line
  [{:keys [tag shellOnly ygg summary]}]
  (str "- " tag ": " (:signal summary)
       " (shell cases: " (:cases shellOnly)
       ", ygg cases: " (:cases ygg) ")"))

(defn- class-summary-line
  [{:keys [problemClasses
           measuredProblemClasses
           architectureClasses
           measuredArchitectureClasses]}]
  (str "Class signal summary: problem "
       measuredProblemClasses
       "/"
       problemClasses
       " measured, architecture "
       measuredArchitectureClasses
       "/"
       architectureClasses
       " measured"))

(defn- case-delta-line
  [{:keys [caseId summary]}]
  (str "- " caseId ": " (:signal summary)
       " (improved: " (:improvedMetrics summary)
       ", regressed: " (:regressedMetrics summary)
       ", unavailable: " (:unavailableMetrics summary) ")"))

(defn- audit-scope-line
  [label summary]
  (let [gt (:groundTruth summary)
        ge (:graphExpectations summary)]
    (str label ": "
         (when gt
           (str "ground-truth " (:found gt 0) "/" (:total gt 0)
                " found"
                (when (pos? (long (:presentInContextButMissed gt 0)))
                  (str ", " (:presentInContextButMissed gt 0)
                       " present-in-context"))))
         (when ge
           (str (when gt ", ")
                "graph-evidence " (:foundEvidence ge 0) "/" (:expectedEvidence ge 0))))))

(defn- case-audit-scope-line
  [case-delta]
  (let [shell-audit (:shellOnlyAuditScope case-delta)
        ygg-audit (:yggAuditScope case-delta)]
    (when (or shell-audit ygg-audit)
      (str "- " (:caseId case-delta) ":\n"
           "  - " (if shell-audit
                    (audit-scope-line "shell" shell-audit)
                    "shell: no audit scope")
           "\n  - " (if ygg-audit
                      (audit-scope-line "ygg" ygg-audit)
                      "ygg: no audit scope")))))

(defn- format-metric-value
  [value]
  (if (some? value)
    (str value)
    "unavailable"))

(defn- metric-delta-line
  [{:keys [metric result shellOnly ygg delta]}]
  (str "- " metric ": " result
       " (shell: " (format-metric-value shellOnly)
       ", ygg: " (format-metric-value ygg)
       ", delta: " (format-metric-value delta) ")"))

(defn- task-token-delta-line
  [{:keys [caseId taskTokenDeltas]}]
  (when-let [total-row (some #(when (= :taskTotalTokens (:key %)) %)
                             taskTokenDeltas)]
    (str "- " caseId ": " (:result total-row)
         " (shell: " (format-metric-value (:shellOnly total-row))
         ", ygg: " (format-metric-value (:ygg total-row))
         ", delta: " (format-metric-value (:delta total-row)) ")")))

(defn- token-reduction-summary-line
  [summary]
  (str "- Reduced cases: "
       (:improvedCases summary)
       "/"
       (:sharedCases summary)
       ", measured "
       (:measuredCases summary)
       "/"
       (:sharedCases summary)
       ", regressed "
       (:regressedCases summary)
       ", unchanged "
       (:unchangedCases summary)
       ", unavailable "
       (:unavailableCases summary)
       ", invalid "
       (:invalidCases summary)))

(defn- headline-summary-line
  [summary [key label]]
  (str "- " label ": " (format-metric-value (get summary key))))

(defn- tradeoff-summary-line
  [label summary]
  (str "- " label ": improved " (:improvedMetrics summary)
       ", regressed " (:regressedMetrics summary)
       ", unchanged " (:unchangedMetrics summary)
       ", unavailable " (:unavailableMetrics summary)))

(defn- summary-counts-line
  [label summary]
  (str label
       " improved "
       (:improvedMetrics summary)
       ", regressed "
       (:regressedMetrics summary)
       ", unchanged "
       (:unchangedMetrics summary)
       (when-let [observed (:observedMetrics summary)]
         (str ", observed " observed))
       ", unavailable "
       (:unavailableMetrics summary)))

(defn- same-summary-counts?
  [left right]
  (= (select-keys left [:improvedMetrics
                        :regressedMetrics
                        :unchangedMetrics
                        :observedMetrics
                        :unavailableMetrics])
     (select-keys right [:improvedMetrics
                         :regressedMetrics
                         :unchangedMetrics
                         :observedMetrics
                         :unavailableMetrics])))

(defn- context-artifact-line
  [label telemetry]
  (let [totals (:totals telemetry)]
    (str "- " label
         ": frontload "
         (format-metric-value (:frontloadBytes totals))
         ", compact hints "
         (format-metric-value (:compactHintsBytes totals))
         ", full hints "
         (format-metric-value (:fullHintsBytes totals))
         ", context "
         (format-metric-value (:contextBytes totals))
         ", hint savings "
         (format-metric-value (:hintSavingsBytes totals))
         (when-let [ratio (:hintSavingsRatio telemetry)]
           (str " (ratio " ratio ")"))
         (when-let [snippet-count (:readPlanSnippetCount totals)]
           (str ", readPlan snippets "
                (format-metric-value snippet-count)
                " ("
                (format-metric-value (:readPlanSnippetBytes totals))
                " bytes)")))))

(defn- progressive-disclosure-line
  [summary]
  (str "- Ygg progressive disclosure: compact hints "
       (format-metric-value (:compactHintsBytes summary))
       " vs full hints "
       (format-metric-value (:fullHintsBytes summary))
       ", saved "
       (format-metric-value (:hintSavingsBytes summary))
       (when-let [ratio (:hintSavingsRatio summary)]
         (str " (ratio " ratio ")"))
       ", frontload "
       (format-metric-value (:frontloadBytes summary))
       ", expansion available "
       (format-metric-value (:expansionBytes summary))
       (when-let [snippet-count (:readPlanSnippetCount summary)]
         (str ", readPlan snippets "
              (format-metric-value snippet-count)
              " ("
              (format-metric-value (:readPlanSnippetBytes summary))
              " bytes)"))))

(defn- timing-basis-line
  [label timings]
  (str "- " label
       ": raw "
       (format-metric-value (:elapsedMs timings))
       ", warm "
       (format-metric-value (:warmElapsedMs timings))
       ", amortized setup "
       (format-metric-value (:amortizedSetupElapsedMs timings))
       ", agent preparation "
       (format-metric-value (:agentPreparationElapsedMs timings))
       ", agent-ready "
       (format-metric-value (:agentReadyElapsedMs timings))))

(defn- timing-basis-note
  [comparison]
  (or (get-in comparison [:ygg :timings :stageTiming :basis])
      (get-in comparison [:shellOnly :timings :stageTiming :basis])
      "warmElapsedMs assumes a prepared-agent run: the Yggdrasil graph DB and agent context are already prepared, so graph setup and agent preparation are reported as amortized setup instead of counted in the primary elapsed metric."))

(defn- preparation-evidence-line
  [label summary]
  (str "- " label
       ": reused "
       (format-metric-value (:reusedRuns summary))
       "/"
       (format-metric-value (:runs summary))
       ", prepared during agent-run "
       (format-metric-value (:preparedDuringAgentRuns summary))
       ", missing evidence "
       (format-metric-value (:missingRuns summary))
       ", ready before agent "
       (format-metric-value (:allRunsReadyBeforeAgent summary))))

(defn- preparation-warning-lines
  [summary]
  (map #(str "- Warning: " %) (:warnings summary)))

(defn- claim-requirement-line
  [requirements key]
  (str "- " (name key) ": " (if (true? (get requirements key))
                              "pass"
                              "fail")))

(defn- class-tag-groups
  [comparison pred]
  (->> (get-in comparison [:byTag :groups])
       (filter #(pred (:tag %)))
       vec))

(defn- headline-metric-deltas
  [comparison]
  (headline-metric-deltas-from-deltas (:deltas comparison)))

(defn markdown-report
  "Return a compact Markdown summary for a shell-only versus Yggdrasil comparison."
  [comparison]
  (let [class-summary (get-in comparison [:classSignals :summary])
        inputs (:inputs comparison)
        compact-summary (:compactSummary comparison)
        summary (:summary comparison)
        claim-summary (or (:claimSummary comparison) summary)
        measured-slice-claim (:measuredSliceClaim comparison)
        requirements (get-in comparison [:claimReadiness :requirements])
        warnings (get-in comparison [:claimReadiness :warnings])
        notes (get-in comparison [:claimReadiness :notes])
        headline-summary (:headlineSummary comparison)
        context-artifacts (:contextArtifacts comparison)
        prepared-agent-evidence (:preparedAgentEvidence comparison)
        shell-timings (get-in comparison [:shellOnly :timings])
        ygg-timings (get-in comparison [:ygg :timings])
        key-deltas (headline-metric-deltas comparison)
        categories (:byCategory comparison)
        tradeoff (:qualityCostTradeoff comparison)
        case-deltas (:caseDeltas comparison)
        token-reduction (:perCaseTokenReduction comparison)
        problem-groups (class-tag-groups comparison
                                         benchmark-classes/problem-class-tag?)
        architecture-groups (class-tag-groups
                             comparison
                             benchmark-classes/architecture-class-tag?)]
    (str/join
     "\n"
     (remove nil?
             (concat
              ["# Yggdrasil Agent Efficiency"
               ""
               (str "- Status: " (:status comparison))
               (str "- Verdict: " (:verdict compact-summary))
               (str "- Suite: " (or (:suiteId comparison) "unknown"))
               (str "- Shared cases: "
                    (get-in comparison [:comparability :sharedCases]))
               (str "- Improved metrics: "
                    (:improvedMetrics claim-summary))
               (str "- Regressed metrics: "
                    (:regressedMetrics claim-summary))
               (str "- Unavailable metrics: "
                    (:unavailableMetrics claim-summary))
               (when-not (same-summary-counts? summary claim-summary)
                 (summary-counts-line
                  "- Overall metrics including timing:"
                  summary))
               (when measured-slice-claim
                 (str "- Measured-slice claim: "
                      (:status measured-slice-claim)))
               (str "- Claim readiness: "
                    (get-in comparison [:claimReadiness :status]))]
              (when (seq (:why compact-summary))
                (concat ["" "## Compact Verdict" ""]
                        (map #(str "- " %) (:why compact-summary))))
              (when (seq inputs)
                [""
                 "## Inputs"
                 ""
                 (str "- Shell-only report: "
                      (or (:shellReport inputs) "unknown"))
                 (str "- Yggdrasil report: "
                      (or (:yggReport inputs) "unknown"))])
              (when class-summary
                [""
                 "## Class Signals"
                 ""
                 (str "- Problem classes measured: "
                      (:measuredProblemClasses class-summary)
                      "/"
                      (:problemClasses class-summary))
                 (str "- Architecture classes measured: "
                      (:measuredArchitectureClasses class-summary)
                      "/"
                      (:architectureClasses class-summary))])
              (when headline-summary
                (concat ["" "## Headline Summary" ""
                         (str "- Status: " (:status headline-summary))
                         (str "- Claim readiness: "
                              (:claimStatus headline-summary))
                         (str "- Broad efficiency claim supported: "
                              (:broadEfficiencyClaimSupported
                               headline-summary))
                         (str "- Shared cases: "
                              (:sharedCases headline-summary)
                              "/"
                              (:minSharedCases headline-summary))
                         (str "- Failed claim requirements: "
                              (if-let [requirements
                                       (seq (:failedRequirements
                                             headline-summary))]
                                (str/join ", " (map name requirements))
                                "none"))]
                        (map #(headline-summary-line headline-summary %)
                             (filter #(contains? headline-summary (first %))
                                     headline-summary-lines))
                        [(str "- Unavailable headline metrics: "
                              (if-let [metrics (seq (:unavailableMetrics
                                                     headline-summary))]
                                (str/join ", " (map name metrics))
                                "none"))]))
              (when tradeoff
                [""
                 "## Quality/Token Tradeoff"
                 ""
                 (str "- Status: " (:status tradeoff))
                 (tradeoff-summary-line "Quality metrics" (:quality tradeoff))
                 (tradeoff-summary-line "Token/cost metrics" (:tokenCost tradeoff))])
              (when context-artifacts
                (concat
                 [""
                  "## Context Artifact Telemetry"
                  ""]
                 (keep identity
                       [(when-let [shell-telemetry (:shellOnly context-artifacts)]
                          (context-artifact-line "Shell-only" shell-telemetry))
                        (when-let [ygg-telemetry (:ygg context-artifacts)]
                          (context-artifact-line "Yggdrasil" ygg-telemetry))
                        (when-let [summary (:progressiveDisclosure
                                            context-artifacts)]
                          (progressive-disclosure-line summary))])))
              (when (or shell-timings ygg-timings)
                (concat
                 [""
                  "## Timing Basis"
                  ""
                  "- Primary elapsed metric: warmElapsedMs"
                  (str "- Basis: " (timing-basis-note comparison))]
                 (keep identity
                       [(when shell-timings
                          (timing-basis-line "Shell-only" shell-timings))
                        (when ygg-timings
                          (timing-basis-line "Yggdrasil" ygg-timings))])))
              (when prepared-agent-evidence
                (concat
                 [""
                  "## Prepared-Agent Evidence"
                  ""
                  (str "- Status: " (:status prepared-agent-evidence))
                  (str "- Basis: "
                       (:primaryWarmBasis prepared-agent-evidence))]
                 (keep identity
                       [(when-let [shell-preparation
                                   (:shellOnly prepared-agent-evidence)]
                          (preparation-evidence-line "Shell-only"
                                                     shell-preparation))
                        (when-let [ygg-preparation
                                   (:ygg prepared-agent-evidence)]
                          (preparation-evidence-line "Yggdrasil"
                                                     ygg-preparation))])
                 (mapcat preparation-warning-lines
                         (keep identity
                               [(:shellOnly prepared-agent-evidence)
                                (:ygg prepared-agent-evidence)]))))
              (when (seq key-deltas)
                (concat ["" "## Key Metric Deltas" ""]
                        (map metric-delta-line key-deltas)))
              (when (seq requirements)
                (concat ["" "## Claim Readiness Requirements" ""]
                        (map #(claim-requirement-line requirements %)
                             claim-requirement-keys)))
              (when measured-slice-claim
                (concat
                 ["" "## Measured-Slice Claim" ""
                  (str "- Status: " (:status measured-slice-claim))
                  (str "- Scope: " (:scope measured-slice-claim))
                  (str "- Failed requirements: "
                       (if-let [requirements
                                (seq (:failedRequirements
                                      measured-slice-claim))]
                         (str/join ", " (map name requirements))
                         "none"))]
                 (map #(str "- " %) (:notes measured-slice-claim))))
              (when (seq categories)
                (concat ["" "## Category Signals" ""]
                        (map category-line categories)))
              (when (seq case-deltas)
                (concat ["" "## Case Signals" ""]
                        (map case-delta-line case-deltas)))
              (when-let [token-lines (seq (keep task-token-delta-line
                                                case-deltas))]
                (concat ["" "## Task Token Deltas" ""]
                        (when token-reduction
                          [(token-reduction-summary-line token-reduction)])
                        (map #(str "- " %) (:warnings token-reduction))
                        token-lines))
              (when-let [audit-lines (seq (keep case-audit-scope-line case-deltas))]
                (concat ["" "## Case Audit Scopes" ""]
                        audit-lines))
              (when (seq problem-groups)
                (concat ["" "## Problem-Class Signals" ""]
                        (map tag-group-line problem-groups)))
              (when (seq architecture-groups)
                (concat ["" "## Architecture-Class Signals" ""]
                        (map tag-group-line architecture-groups)))
              (when (seq warnings)
                (concat ["" "## Claim Readiness Warnings" ""]
                        (map #(str "- " %) warnings)))
              (when (seq notes)
                (concat ["" "## Claim Readiness Notes" ""]
                        (map #(str "- " %) notes))))))))

(defn- usage
  []
  (str "Usage: bb efficiency <shell-agent-report.json> <ygg-agent-report.json>"
       " [--out report.json] [--markdown-out REPORT.md]"
       " [--json] [--min-shared-cases N]"))

(defn -main
  [& args]
  (let [positions (positional-args args)
        shell-report-path (first positions)
        ygg-report-path (second positions)]
    (when-not (and shell-report-path ygg-report-path)
      (throw (ex-info "Missing report paths." {:usage (usage)})))
    (let [min-shared-cases (some-> (option-value args "--min-shared-cases")
                                   (parse-positive-long "--min-shared-cases"))
          comparison (compare-report-files!
                      shell-report-path
                      ygg-report-path
                      {:out (option-value args "--out")
                       :markdown-out (option-value args "--markdown-out")
                       :min-shared-cases min-shared-cases})]
      (if (flag? args "--json")
        (println (json/write-json-str comparison {:indent-str "  "}))
        (do
          (let [summary (:summary comparison)
                claim-summary (or (:claimSummary comparison) summary)]
            (println (str "Agent efficiency: " (:status comparison)))
            (when-let [compact (:compactSummary comparison)]
              (println (str "Verdict: " (:verdict compact)))
              (doseq [reason (:why compact)]
                (println (str "- " reason))))
            (println (summary-counts-line "Claim metrics:" claim-summary))
            (when-not (same-summary-counts? summary claim-summary)
              (println (summary-counts-line
                        "Overall metrics including timing:"
                        summary))))
          (println (str "Shared tag groups: "
                        (get-in comparison [:byTag :comparability :sharedTags])))
          (when-let [summary (get-in comparison [:classSignals :summary])]
            (println (class-summary-line summary)))
          (when-let [categories (seq (:byCategory comparison))]
            (println "Category signals:")
            (doseq [category categories]
              (println (category-line category))))
          (when-let [groups (seq (class-tag-groups comparison
                                                   benchmark-classes/problem-class-tag?))]
            (println "Problem-class signals:")
            (doseq [group groups]
              (println (tag-group-line group))))
          (when-let [groups (seq (class-tag-groups comparison
                                                   benchmark-classes/architecture-class-tag?))]
            (println "Architecture-class signals:")
            (doseq [group groups]
              (println (tag-group-line group))))
          (println (str "Claim readiness: "
                        (get-in comparison [:claimReadiness :status])))
          (when-let [measured-slice-claim (:measuredSliceClaim comparison)]
            (println (str "Measured-slice claim: "
                          (:status measured-slice-claim))))
          (when-let [warnings (seq (get-in comparison
                                           [:claimReadiness :warnings]))]
            (println "Claim readiness warnings:")
            (doseq [warning warnings]
              (println (str "- " warning))))
          (when-let [notes (seq (get-in comparison [:claimReadiness :notes]))]
            (println "Claim readiness notes:")
            (doseq [note notes]
              (println (str "- " note))))
          (when-let [out (option-value args "--out")]
            (println (str "Wrote " out)))
          (when-let [out (option-value args "--markdown-out")]
            (println (str "Wrote " out))))))))
