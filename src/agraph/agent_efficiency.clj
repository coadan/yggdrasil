(ns agraph.agent-efficiency
  "Compare shell-only and AGraph-assisted agent benchmark reports."
  (:require [agraph.benchmark-classes :as benchmark-classes]
            [agraph.benchmark-targets :as benchmark-targets]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "agraph.agent-efficiency/v1")

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
   {:key :agraphCommandCount
    :label "agraphCommandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :agraphCommandCount]
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

(def ^:private case-score-specs
  (->> metric-specs
       (filter #(= :scores (first (:path %))))
       vec))

(def ^:private group-metric-specs
  (->> metric-specs
       (remove #(= :timing (:category %)))
       vec))

(def ^:private category-order
  (->> metric-specs
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
   [:elapsedMsDelta "elapsedMs delta"]
   [:totalTokensDelta "totalTokens delta"]
   [:costUsdDelta "costUsd delta"]])

(def ^:private claim-requirement-keys
  [:sameSuite
   :sameCases
   :enoughSharedCases
   :agraphImprovedWithoutRegressions
   :directionalMetrics
   :problemClassCoverage
   :architectureClassCoverage
   :evidenceMetrics
   :expectedEvidenceCitationMetrics
   :commandTelemetry
   :shellLaneClaimReady
   :agraphLaneClaimReady])

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
(defn- efficiency-report
  [report]
  (if (contains? report :improvementSummary)
    (assoc report :efficiency {:improvementTargetRuns
                               (benchmark-targets/target-runs report)
                               :improvementTargetRunsByKind
                               (benchmark-targets/target-runs-by-kind report)})
    report))

(defn- improvement-target-metric-specs
  [shell-report agraph-report]
  (->> (concat (keys (get-in shell-report
                             [:efficiency :improvementTargetRunsByKind]))
               (keys (get-in agraph-report
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
  [shell-report agraph-report {:keys [category direction key label path tolerance default]}]
  (let [shell-value (metric-value shell-report path default)
        agraph-value (metric-value agraph-report path default)
        available? (and (some? shell-value)
                        (some? agraph-value))]
    (if-not available?
      {:key key
       :metric label
       :category (name category)
       :direction (name direction)
       :shellOnly shell-value
       :agraph agraph-value
       :available false
       :result "unavailable"}
      (let [delta (- agraph-value shell-value)
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
                 :agraph agraph-value
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
   :improvementSummary (:improvementSummary report)
   :improvementTargetRuns (benchmark-targets/target-runs report)
   :improvementTargetRunsByKind (benchmark-targets/target-runs-by-kind report)
   :tags (:tags report)
   :claimReadiness (:claimReadiness report)
   :timings (:timings report)})

(defn- rows-by-key
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [key (some-> (:key row) str not-empty)]
                 [key row])))
       (into {})))

(defn- comparability
  [shell-report agraph-report]
  (let [shell-cases (set (sorted-case-ids shell-report))
        agraph-cases (set (sorted-case-ids agraph-report))
        shell-only-cases (sort (remove agraph-cases shell-cases))
        agraph-only-cases (sort (remove shell-cases agraph-cases))
        shared-cases (sort (filter agraph-cases shell-cases))
        same-suite? (= (:suite-id shell-report) (:suite-id agraph-report))
        same-cases? (and (empty? shell-only-cases)
                         (empty? agraph-only-cases))]
    {:sameSuite same-suite?
     :sameCases same-cases?
     :sharedCases (count shared-cases)
     :sharedCaseIds (vec shared-cases)
     :shellOnlyCaseIds (vec shell-only-cases)
     :agraphOnlyCaseIds (vec agraph-only-cases)
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
                  (and (pos? improved) (zero? regressed)) "agraph-improved"
                  (and (zero? improved) (pos? regressed)) "agraph-regressed"
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

(defn- tag-comparability
  [shell-report agraph-report]
  (let [shell-tags (set (keys (rows-by-key (:byTag shell-report))))
        agraph-tags (set (keys (rows-by-key (:byTag agraph-report))))
        shell-only-tags (sort (remove agraph-tags shell-tags))
        agraph-only-tags (sort (remove shell-tags agraph-tags))
        shared-tags (sort (filter agraph-tags shell-tags))
        same-tags? (and (empty? shell-only-tags)
                        (empty? agraph-only-tags))]
    {:sameTags same-tags?
     :sharedTags (count shared-tags)
     :sharedTagKeys (vec shared-tags)
     :shellOnlyTagKeys (vec shell-only-tags)
     :agraphOnlyTagKeys (vec agraph-only-tags)
     :warnings (cond-> []
                 (not same-tags?)
                 (conj "Reports do not contain the same completed tag groups.")

                 (empty? shared-tags)
                 (conj "Reports have no shared completed tag groups."))}))

(defn- tag-deltas
  [shell-report agraph-report]
  (let [shell-by-tag (rows-by-key (:byTag shell-report))
        agraph-by-tag (rows-by-key (:byTag agraph-report))
        shared-tags (->> (keys shell-by-tag)
                         (filter #(contains? agraph-by-tag %))
                         sort)]
    (mapv (fn [tag]
            (let [shell-row (get shell-by-tag tag)
                  agraph-row (get agraph-by-tag tag)
                  deltas (mapv #(metric-delta shell-row agraph-row %)
                               group-metric-specs)]
              {:tag tag
               :shellOnly {:cases (long (or (:cases shell-row) 0))
                           :runs (long (or (:runs shell-row) 0))}
               :agraph {:cases (long (or (:cases agraph-row) 0))
                        :runs (long (or (:runs agraph-row) 0))}
               :summary (aggregate-summary deltas {:sharedCases 1})
               :deltas deltas}))
          shared-tags)))

(defn- by-tag-comparison
  [shell-report agraph-report]
  {:comparability (tag-comparability shell-report agraph-report)
   :groups (tag-deltas shell-report agraph-report)})

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
  [shell-report agraph-report]
  (and (map? (:problemClasses shell-report))
       (map? (:problemClasses agraph-report))))

(defn- problem-class-coverage
  [shell-report agraph-report by-tag]
  (let [shared-tags (get-in by-tag [:comparability :sharedTagKeys])
        problem-tags (filterv benchmark-classes/problem-class-tag? shared-tags)
        architecture-tags (filterv benchmark-classes/architecture-class-tag?
                                   shared-tags)
        summary-available? (problem-class-summary-available? shell-report
                                                             agraph-report)
        shell-measured-problem-tags (measured-class-keys shell-report :classes)
        agraph-measured-problem-tags (measured-class-keys agraph-report :classes)
        shared-measured-problem-tags (shared-keys shell-measured-problem-tags
                                                  agraph-measured-problem-tags)
        shell-measured-architecture-tags (measured-class-keys
                                          shell-report
                                          :architectureClasses)
        agraph-measured-architecture-tags (measured-class-keys
                                           agraph-report
                                           :architectureClasses)
        shared-measured-architecture-tags (shared-keys
                                           shell-measured-architecture-tags
                                           agraph-measured-architecture-tags)]
    {:sharedTagKeys (vec shared-tags)
     :problemClassTags problem-tags
     :architectureClassTags architecture-tags
     :problemClassSummaryAvailable summary-available?
     :shellProblemClassTags (class-keys shell-report :classes)
     :agraphProblemClassTags (class-keys agraph-report :classes)
     :shellMeasuredProblemClassTags shell-measured-problem-tags
     :agraphMeasuredProblemClassTags agraph-measured-problem-tags
     :sharedMeasuredProblemClassTags shared-measured-problem-tags
     :shellArchitectureClassTags (class-keys shell-report :architectureClasses)
     :agraphArchitectureClassTags (class-keys agraph-report :architectureClasses)
     :shellMeasuredArchitectureClassTags shell-measured-architecture-tags
     :agraphMeasuredArchitectureClassTags agraph-measured-architecture-tags
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
  (assoc (select-keys group [:tag :shellOnly :agraph :summary])
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
  [shell-report agraph-report]
  (let [shell? (expected-evidence-citation-present? shell-report)
        agraph? (expected-evidence-citation-present? agraph-report)]
    (= shell? agraph?)))

(defn- lane-claim-ready?
  [report]
  (let [claim-readiness (:claimReadiness report)]
    (if (map? claim-readiness)
      (true? (:broadArchitectureClaimSupported claim-readiness))
      true)))

(defn- lane-claim-known?
  [report]
  (map? (:claimReadiness report)))

(defn- claim-readiness
  [summary comparable by-category problem-coverage shell-report agraph-report]
  (let [same-suite? (true? (:sameSuite comparable))
        same-cases? (true? (:sameCases comparable))
        enough-cases? (<= (long (:minSharedCases summary))
                          (long (:sharedCases comparable)))
        improved-without-regressions? (and (= "agraph-improved"
                                              (:signal summary))
                                           (zero? (:regressedMetrics summary)))
        directional-metrics? (pos? (long (+ (:improvedMetrics summary)
                                            (:regressedMetrics summary)
                                            (:unchangedMetrics summary))))
        problem-classes? (true? (:hasMeasuredProblemClasses problem-coverage))
        architecture-classes? (true? (:hasMeasuredArchitectureClasses
                                      problem-coverage))
        evidence-metrics? (category-has-metrics? by-category "evidence")
        expected-evidence-metrics? (expected-evidence-citation-comparable?
                                    shell-report
                                    agraph-report)
        command-telemetry? (category-has-directional-metrics?
                            by-category
                            "command-telemetry")
        token-cost-metrics? (category-has-metrics? by-category "token-cost")
        shell-lane-ready? (lane-claim-ready? shell-report)
        agraph-lane-ready? (lane-claim-ready? agraph-report)
        shell-lane-known? (lane-claim-known? shell-report)
        agraph-lane-known? (lane-claim-known? agraph-report)
        requirements {:sameSuite same-suite?
                      :sameCases same-cases?
                      :enoughSharedCases enough-cases?
                      :agraphImprovedWithoutRegressions
                      improved-without-regressions?
                      :directionalMetrics directional-metrics?
                      :problemClassCoverage problem-classes?
                      :architectureClassCoverage architecture-classes?
                      :evidenceMetrics evidence-metrics?
                      :expectedEvidenceCitationMetrics expected-evidence-metrics?
                      :commandTelemetry command-telemetry?
                      :shellLaneClaimReady shell-lane-ready?
                      :agraphLaneClaimReady agraph-lane-ready?}
        supported? (every? true? (vals requirements))]
    {:status (if supported? "supported" "not-supported")
     :broadEfficiencyClaimSupported supported?
     :sharedCases (long (:sharedCases comparable))
     :minSharedCases (long (:minSharedCases summary))
     :requirements requirements
     :laneClaimReadiness {:shellOnly (:claimReadiness shell-report)
                          :agraph (:claimReadiness agraph-report)}
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
                 (conj "Aggregate metrics do not show an unregressed AGraph improvement; report class-level tradeoffs instead of a broad win.")

                 (not directional-metrics?)
                 (conj "Directional efficiency metrics are unavailable; observed telemetry alone cannot support an improvement claim.")

                 (not evidence-metrics?)
                 (conj "Evidence citation metrics are unavailable; answer quality and citation quality are unproven.")

                 (not expected-evidence-metrics?)
                 (conj "Expected evidence citation metrics are only available in one lane; non-code evidence citation quality is not comparable.")

                 (not command-telemetry?)
                 (conj "Command telemetry is unavailable; CLI search/read-loop savings are unproven.")

                 (not shell-lane-known?)
                 (conj "Shell-only report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim.")

                 (and shell-lane-known? (not shell-lane-ready?))
                 (conj "Shell-only report is not claim-ready; fix that lane before comparing broad efficiency.")

                 (not agraph-lane-known?)
                 (conj "AGraph report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim.")

                 (and agraph-lane-known? (not agraph-lane-ready?))
                 (conj "AGraph report is not claim-ready; fix that lane before comparing broad efficiency.")

                 (not (and problem-classes? architecture-classes?))
                 (into (:warnings problem-coverage)))
     :notes (cond-> []
              (not token-cost-metrics?)
              (conj "Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."))}))

(defn- case-deltas
  [shell-report agraph-report]
  (let [shell-by-case (result-by-case shell-report)
        agraph-by-case (result-by-case agraph-report)
        shared-case-ids (->> (keys shell-by-case)
                             (filter #(contains? agraph-by-case %))
                             sort)]
    (mapv (fn [case-id]
            (let [deltas (mapv #(metric-delta (get shell-by-case case-id)
                                              (get agraph-by-case case-id)
                                              %)
                               case-score-specs)]
              {:caseId case-id
               :summary (aggregate-summary deltas {:sharedCases 1})
               :deltas deltas}))
          shared-case-ids)))

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
                            (map (fn [[metric-key field]]
                                   [field (:delta (get metrics-by-key
                                                       metric-key))]))
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

(defn compare-reports
  "Return a shell-only vs AGraph efficiency comparison from two agent reports."
  ([shell-report agraph-report]
   (compare-reports shell-report agraph-report {}))
  ([shell-report agraph-report {:keys [min-shared-cases]}]
   (let [shell-efficiency-report (efficiency-report shell-report)
         agraph-efficiency-report (efficiency-report agraph-report)
         comparable (comparability shell-report agraph-report)
         deltas (mapv #(metric-delta shell-efficiency-report
                                     agraph-efficiency-report
                                     %)
                      (into metric-specs
                            (improvement-target-metric-specs
                             shell-efficiency-report
                             agraph-efficiency-report)))
         min-shared-cases (long (or min-shared-cases default-min-shared-cases))
         summary (aggregate-summary deltas
                                    comparable
                                    {:min-shared-cases min-shared-cases})
         by-category (category-summaries deltas comparable min-shared-cases)
         by-tag (by-tag-comparison shell-report agraph-report)
         problem-coverage (problem-class-coverage shell-report
                                                  agraph-report
                                                  by-tag)
         claim-readiness-result (claim-readiness summary
                                                 comparable
                                                 by-category
                                                 problem-coverage
                                                 shell-report
                                                 agraph-report)
         headline-metrics (headline-metric-deltas-from-deltas deltas)]
     {:schema schema
      :status (:signal summary)
      :suiteId (or (:suite-id agraph-report)
                   (:suite-id shell-report))
      :summary summary
      :comparability comparable
      :shellOnly (report-summary shell-report)
      :agraph (report-summary agraph-report)
      :deltas deltas
      :headlineMetrics headline-metrics
      :headlineSummary (headline-summary {:summary summary
                                          :comparable comparable
                                          :headline-metrics headline-metrics
                                          :claim-readiness
                                          claim-readiness-result})
      :byCategory by-category
      :byTag by-tag
      :classSignals (class-signals by-tag problem-coverage)
      :problemClassCoverage problem-coverage
      :claimReadiness claim-readiness-result
      :caseDeltas (case-deltas shell-report agraph-report)})))

(defn compare-report-files!
  "Read two agent-report JSON files and optionally write the comparison."
  [shell-report-path agraph-report-path opts]
  (let [comparison (-> (read-json-file shell-report-path)
                       (compare-reports (read-json-file agraph-report-path)
                                        {:min-shared-cases (:min-shared-cases opts)})
                       (assoc-in [:inputs :shellReport] shell-report-path)
                       (assoc-in [:inputs :agraphReport] agraph-report-path))]
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
  [{:keys [tag shellOnly agraph summary]}]
  (str "- " tag ": " (:signal summary)
       " (shell cases: " (:cases shellOnly)
       ", agraph cases: " (:cases agraph) ")"))

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

(defn- format-metric-value
  [value]
  (if (some? value)
    (str value)
    "unavailable"))

(defn- metric-delta-line
  [{:keys [metric result shellOnly agraph delta]}]
  (str "- " metric ": " result
       " (shell: " (format-metric-value shellOnly)
       ", agraph: " (format-metric-value agraph)
       ", delta: " (format-metric-value delta) ")"))

(defn- headline-summary-line
  [summary [key label]]
  (str "- " label ": " (format-metric-value (get summary key))))

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
  "Return a compact Markdown summary for a shell-only versus AGraph comparison."
  [comparison]
  (let [class-summary (get-in comparison [:classSignals :summary])
        inputs (:inputs comparison)
        requirements (get-in comparison [:claimReadiness :requirements])
        warnings (get-in comparison [:claimReadiness :warnings])
        notes (get-in comparison [:claimReadiness :notes])
        headline-summary (:headlineSummary comparison)
        key-deltas (headline-metric-deltas comparison)
        categories (:byCategory comparison)
        case-deltas (:caseDeltas comparison)
        problem-groups (class-tag-groups comparison
                                         benchmark-classes/problem-class-tag?)
        architecture-groups (class-tag-groups
                             comparison
                             benchmark-classes/architecture-class-tag?)]
    (str/join
     "\n"
     (remove nil?
             (concat
              ["# AGraph Agent Efficiency"
               ""
               (str "- Status: " (:status comparison))
               (str "- Suite: " (or (:suiteId comparison) "unknown"))
               (str "- Shared cases: "
                    (get-in comparison [:comparability :sharedCases]))
               (str "- Improved metrics: "
                    (get-in comparison [:summary :improvedMetrics]))
               (str "- Regressed metrics: "
                    (get-in comparison [:summary :regressedMetrics]))
               (str "- Unavailable metrics: "
                    (get-in comparison [:summary :unavailableMetrics]))
               (str "- Claim readiness: "
                    (get-in comparison [:claimReadiness :status]))]
              (when (seq inputs)
                [""
                 "## Inputs"
                 ""
                 (str "- Shell-only report: "
                      (or (:shellReport inputs) "unknown"))
                 (str "- AGraph report: "
                      (or (:agraphReport inputs) "unknown"))])
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
                             headline-summary-lines)
                        [(str "- Unavailable headline metrics: "
                              (if-let [metrics (seq (:unavailableMetrics
                                                     headline-summary))]
                                (str/join ", " (map name metrics))
                                "none"))]))
              (when (seq key-deltas)
                (concat ["" "## Key Metric Deltas" ""]
                        (map metric-delta-line key-deltas)))
              (when (seq requirements)
                (concat ["" "## Claim Readiness Requirements" ""]
                        (map #(claim-requirement-line requirements %)
                             claim-requirement-keys)))
              (when (seq categories)
                (concat ["" "## Category Signals" ""]
                        (map category-line categories)))
              (when (seq case-deltas)
                (concat ["" "## Case Signals" ""]
                        (map case-delta-line case-deltas)))
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
  (str "Usage: bb efficiency <shell-agent-report.json> <agraph-agent-report.json>"
       " [--out report.json] [--markdown-out REPORT.md]"
       " [--json] [--min-shared-cases N]"))

(defn -main
  [& args]
  (let [positions (positional-args args)
        shell-report-path (first positions)
        agraph-report-path (second positions)]
    (when-not (and shell-report-path agraph-report-path)
      (throw (ex-info "Missing report paths." {:usage (usage)})))
    (let [min-shared-cases (some-> (option-value args "--min-shared-cases")
                                   (parse-positive-long "--min-shared-cases"))
          comparison (compare-report-files!
                      shell-report-path
                      agraph-report-path
                      {:out (option-value args "--out")
                       :markdown-out (option-value args "--markdown-out")
                       :min-shared-cases min-shared-cases})]
      (if (flag? args "--json")
        (println (json/write-json-str comparison {:indent-str "  "}))
        (do
          (println (str "Agent efficiency: " (:status comparison)))
          (println (str "Improved metrics: "
                        (get-in comparison [:summary :improvedMetrics])
                        ", regressed metrics: "
                        (get-in comparison [:summary :regressedMetrics])
                        ", unchanged metrics: "
                        (get-in comparison [:summary :unchangedMetrics])
                        (when-let [observed (get-in comparison
                                                    [:summary :observedMetrics])]
                          (str ", observed metrics: " observed))
                        ", unavailable metrics: "
                        (get-in comparison [:summary :unavailableMetrics])))
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
