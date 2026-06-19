(ns agraph.agent-efficiency
  "Compare shell-only and AGraph-assisted agent benchmark reports."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "agraph.agent-efficiency/v1")

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
   {:key :commandCount
    :label "commandCount"
    :category :command-telemetry
    :path [:agentDiagnostics :commandTelemetry :commandCount]
    :direction :lower}
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

(def ^:private architecture-class-tags
  #{"problem-architecture"
    "architecture-boundary"
    "architecture-runtime-boundary"
    "architecture-dependency-flow"
    "architecture-data-ownership"
    "architecture-cross-system-impact"
    "audit-scope-runtime-config"
    "audit-scope-containers"
    "audit-scope-docs"})

(defn- problem-class-tag?
  [tag]
  (str/starts-with? tag "problem-"))

(defn- architecture-class-tag?
  [tag]
  (contains? architecture-class-tags tag))

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

(defn- metric-value
  [m path]
  (let [value (get-in m path)]
    (when (number? value)
      (double value))))

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
  [shell-report agraph-report {:keys [category direction key label path tolerance]}]
  (let [shell-value (metric-value shell-report path)
        agraph-value (metric-value agraph-report path)
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
                 :result (result-label effective)}
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
         unavailable (count-results deltas "unavailable")
         available (+ improved regressed unchanged)
         min-shared-cases (long (or min-shared-cases 1))
         enough-cases? (<= min-shared-cases (long (:sharedCases comparable)))
         signal (cond
                  (zero? (:sharedCases comparable)) "not-comparable"
                  (not enough-cases?) "insufficient-cases"
                  (zero? available) "metrics-unavailable"
                  (and (pos? improved) (zero? regressed)) "agraph-improved"
                  (and (zero? improved) (pos? regressed)) "agraph-regressed"
                  (and (pos? improved) (pos? regressed)) "mixed"
                  :else "unchanged")]
     {:signal signal
      :minSharedCases min-shared-cases
      :availableMetrics available
      :improvedMetrics improved
      :regressedMetrics regressed
      :unchangedMetrics unchanged
      :unavailableMetrics unavailable})))

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
        problem-tags (filterv problem-class-tag? shared-tags)
        architecture-tags (filterv architecture-class-tag? shared-tags)
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
        problem-classes? (true? (:hasMeasuredProblemClasses problem-coverage))
        architecture-classes? (true? (:hasMeasuredArchitectureClasses
                                      problem-coverage))
        evidence-metrics? (category-has-metrics? by-category "evidence")
        command-telemetry? (category-has-metrics? by-category
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
                      :problemClassCoverage problem-classes?
                      :architectureClassCoverage architecture-classes?
                      :evidenceMetrics evidence-metrics?
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

                 (not evidence-metrics?)
                 (conj "Evidence citation metrics are unavailable; answer quality and citation quality are unproven.")

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

(defn compare-reports
  "Return a shell-only vs AGraph efficiency comparison from two agent reports."
  ([shell-report agraph-report]
   (compare-reports shell-report agraph-report {}))
  ([shell-report agraph-report {:keys [min-shared-cases]}]
   (let [comparable (comparability shell-report agraph-report)
         deltas (mapv #(metric-delta shell-report agraph-report %) metric-specs)
         min-shared-cases (long (or min-shared-cases default-min-shared-cases))
         summary (aggregate-summary deltas
                                    comparable
                                    {:min-shared-cases min-shared-cases})
         by-category (category-summaries deltas comparable min-shared-cases)
         by-tag (by-tag-comparison shell-report agraph-report)
         problem-coverage (problem-class-coverage shell-report
                                                  agraph-report
                                                  by-tag)]
     {:schema schema
      :status (:signal summary)
      :suiteId (or (:suite-id agraph-report)
                   (:suite-id shell-report))
      :summary summary
      :comparability comparable
      :shellOnly (report-summary shell-report)
      :agraph (report-summary agraph-report)
      :deltas deltas
      :byCategory by-category
      :byTag by-tag
      :problemClassCoverage problem-coverage
      :claimReadiness (claim-readiness summary
                                       comparable
                                       by-category
                                       problem-coverage
                                       shell-report
                                       agraph-report)
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

(defn- usage
  []
  (str "Usage: bb efficiency <shell-agent-report.json> <agraph-agent-report.json>"
       " [--out report.json] [--json] [--min-shared-cases N]"))

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
                        ", unavailable metrics: "
                        (get-in comparison [:summary :unavailableMetrics])))
          (println (str "Shared tag groups: "
                        (get-in comparison [:byTag :comparability :sharedTags])))
          (when-let [categories (seq (:byCategory comparison))]
            (println "Category signals:")
            (doseq [{:keys [category summary]} categories]
              (println (str "- " category ": " (:signal summary)))))
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
            (println (str "Wrote " out))))))))
