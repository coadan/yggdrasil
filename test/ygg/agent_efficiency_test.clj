(ns ygg.agent-efficiency-test
  (:require [ygg.agent-efficiency :as agent-efficiency]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- spit-json!
  [root path value]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-json-str value {:indent-str "  "}))
    (.getPath file)))

(defn- read-json
  [path]
  (json/read-json (slurp path) :key-fn keyword))

(defn- report
  [{:keys [mode recall5 recall10 recall20 mrr noise evidence path-evidence
           expected-evidence
           decision-recall decision-precision decision-f1 decision-evidence
           missing-decision decision-gaps
           missed outside5 outside10 missing-predicted empty commandless warnings
           command-count search-command-count file-read-command-count
           shell-command-count ygg-command-count
           segment-count search-segment-count file-read-segment-count
           shell-segment-count ygg-segment-count
           elapsed failed running case-ids]}]
  (let [scores (cond-> {:fileRecallAt5 recall5
                        :fileRecallAt10 recall10
                        :fileRecallAt20 recall20
                        :meanReciprocalRankFile mrr
                        :noiseRatioAt20 noise
                        :evidenceCitationRate evidence
                        :pathEvidenceCitationRate path-evidence}
                 (some? expected-evidence)
                 (assoc :expectedEvidenceCitationRate expected-evidence)
                 (some? decision-recall)
                 (assoc :decisionRecall decision-recall
                        :decisionPrecision decision-precision
                        :decisionF1 decision-f1
                        :decisionEvidenceCitationRate decision-evidence))]
    (cond-> {:schema "ygg.benchmark.agent-report/v1"
             :suite-id "suite"
             :cases (count case-ids)
             :completed (count case-ids)
             :runs (count case-ids)
             :missing []
             :scores scores
             :localizationDiagnostics {:missedRuns missed
                                       :rankedOutsideTop5Runs outside5
                                       :rankedOutsideTop10Runs outside10}
             :agentDiagnostics {:missingPredictedFileRuns missing-predicted
                                :emptyResultRuns empty
                                :commandlessRuns commandless
                                :warningRuns warnings
                                :commandTelemetry (cond-> {:commandCount command-count
                                                           :yggCommandCount ygg-command-count
                                                           :searchCommandCount search-command-count
                                                           :fileReadCommandCount file-read-command-count
                                                           :shellCommandCount shell-command-count}
                                                    (some? segment-count)
                                                    (assoc :segmentCount segment-count
                                                           :yggSegmentCount ygg-segment-count
                                                           :searchSegmentCount search-segment-count
                                                           :fileReadSegmentCount file-read-segment-count
                                                           :shellSegmentCount shell-segment-count))}
             :improvementSummary []
             :timings {:elapsedMs elapsed
                       :failedCases failed
                       :runningCases running}
             :results (mapv (fn [case-id]
                              {:case-id case-id
                               :agent {:mode mode
                                       :agentId "codex"}
                               :scores scores})
                            case-ids)}
      (some? decision-f1)
      (assoc :decisionDiagnostics {:configuredRuns (count case-ids)
                                   :configuredCaseIds case-ids
                                   :gapRuns (long (or decision-gaps 0))
                                   :gapCaseIds (if (pos? (long (or decision-gaps 0)))
                                                 case-ids
                                                 [])
                                   :missingDecisionRuns (long (or missing-decision 0))
                                   :missingDecisionCaseIds (if (pos? (long (or missing-decision 0)))
                                                             case-ids
                                                             [])}))))

(defn- tag-row
  [key {:keys [cases runs recall10 noise]}]
  {:key key
   :cases cases
   :runs runs
   :scores {:fileRecallAt10 recall10
            :noiseRatioAt20 noise}})

(defn- with-problem-classes
  ([report]
   (with-problem-classes report {}))
  ([report {:keys [problem-status architecture-status]
            :or {problem-status "measured"
                 architecture-status "measured"}}]
   (assoc report
          :problemClasses
          {:minimumCasesForClassClaim 2
           :classes [{:key "problem-architecture"
                      :cases 2
                      :runs 2
                      :claimStatus problem-status
                      :minimumCases 2}]
           :architectureClasses [{:key "architecture-dependency-flow"
                                  :cases 2
                                  :runs 2
                                  :claimStatus architecture-status
                                  :minimumCases 2}]})))

(defn- with-claim-readiness
  ([report]
   (with-claim-readiness report true))
  ([report supported?]
   (assoc report
          :claimReadiness
          {:status (if supported? "supported" "not-supported")
           :broadArchitectureClaimSupported supported?
           :measuredProblemClassTags ["problem-architecture"]
           :measuredArchitectureClassTags ["architecture-dependency-flow"]
           :warnings (if supported?
                       []
                       ["Lane is not representative enough."])})))

(defn- token-usage
  [total-tokens]
  {:inputTokens total-tokens
   :outputTokens 0
   :totalTokens total-tokens
   :costUsd 0.0
   :source "test-token-usage"})

(defn- with-case-token-usage
  [report totals-by-case]
  (update report
          :results
          (fn [results]
            (mapv (fn [result]
                    (if-let [total-tokens (get totals-by-case (:case-id result))]
                      (assoc-in result
                                [:agent :tokenUsage]
                                (token-usage total-tokens))
                      result))
                  results))))

(defn- with-reduced-ygg-token-usage
  [shell ygg]
  [(with-case-token-usage shell {"case-1" 1000
                                 "case-2" 1000})
   (with-case-token-usage ygg {"case-1" 700
                               "case-2" 700})])

(def shell-report
  (report {:mode "shell-only"
           :recall5 0.5
           :recall10 0.5
           :recall20 0.5
           :mrr 0.4
           :noise 0.5
           :evidence 0.25
           :path-evidence 0.1
           :missed 1
           :outside5 1
           :outside10 1
           :missing-predicted 1
           :empty 1
           :commandless 2
           :warnings 1
           :command-count 9
           :search-command-count 4
           :file-read-command-count 2
           :shell-command-count 3
           :ygg-command-count 0
           :elapsed 1000
           :failed 1
           :running 0
           :case-ids ["case-1" "case-2"]}))

(def ygg-report
  (report {:mode "ygg"
           :recall5 0.75
           :recall10 0.75
           :recall20 0.75
           :mrr 0.6
           :noise 0.25
           :evidence 0.5
           :path-evidence 0.4
           :missed 0
           :outside5 0
           :outside10 0
           :missing-predicted 0
           :empty 0
           :commandless 1
           :warnings 0
           :command-count 5
           :search-command-count 1
           :file-read-command-count 1
           :shell-command-count 1
           :ygg-command-count 2
           :elapsed 900
           :failed 0
           :running 0
           :case-ids ["case-1" "case-2"]}))

(deftest compares-shell-only-and-ygg-agent-reports
  (let [comparison (agent-efficiency/compare-reports shell-report ygg-report)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))
        headline-by-key (into {} (map (juxt :key identity))
                              (:headlineMetrics comparison))
        categories-by-key (into {} (map (juxt :category identity)) (:byCategory comparison))]
    (is (= "ygg.agent-efficiency/v1" (:schema comparison)))
    (is (= "ygg-improved" (:status comparison)))
    (is (= {:verdict "inconclusive"
            :status "ygg-improved"
            :claimStatus "not-supported"
            :sharedCases 2
            :minSharedCases 2
            :improvedMetrics 18
            :regressedMetrics 0
            :unavailableMetrics 5
            :why ["Yggdrasil total token usage must be measured lower for every shared case."
                  "18 directional metric(s) improved."
                  "5 metric(s) were unavailable."
                  "Claim readiness is not supported; use the warnings before making the benchmark claim."]}
           (:compactSummary comparison)))
    (is (= [:fileRecallAt10
            :noiseRatioAt20
            :evidenceCitationRate
            :pathEvidenceCitationRate
            :commandCount
            :searchCommandCount
            :fileReadCommandCount
            :elapsedMs
            :totalTokens
            :costUsd]
           (mapv :key (:headlineMetrics comparison))))
    (is (= {:status "ygg-improved"
            :claimStatus "not-supported"
            :broadEfficiencyClaimSupported false
            :sharedCases 2
            :minSharedCases 2
            :failedRequirements [:problemClassCoverage
                                 :architectureClassCoverage
                                 :perCaseTokenReduction]
            :fileRecallAt10Delta 0.25
            :noiseRatioAt20Delta -0.25
            :evidenceCitationRateDelta 0.25
            :pathEvidenceCitationRateDelta 0.30000000000000004
            :toolCallDelta -4.0
            :searchCommandDelta -3.0
            :fileReadDelta -1.0
            :elapsedMsDelta -100.0
            :totalTokensDelta nil
            :costUsdDelta nil
            :unavailableMetrics [:totalTokens :costUsd]}
           (:headlineSummary comparison)))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 23
            :improvedMetrics 20
            :regressedMetrics 0
            :unchangedMetrics 2
            :observedMetrics 1
            :unavailableMetrics 5}
           (:summary comparison)))
    (is (= {:sameSuite true
            :sameCases true
            :sharedCases 2
            :sharedCaseIds ["case-1" "case-2"]
            :shellOnlyCaseIds []
            :yggOnlyCaseIds []
            :warnings []}
           (:comparability comparison)))
    (is (= {:shellOnly 0.5
            :ygg 0.75
            :delta 0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:fileRecallAt10 deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.5
            :ygg 0.25
            :delta -0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:noiseRatioAt20 deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 4.0
            :ygg 1.0
            :delta -3.0
            :effect 3.0
            :result "improved"}
           (select-keys (:searchCommandCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly nil
            :ygg nil
            :result "unavailable"}
           (select-keys (:totalTokens headline-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly nil
            :ygg nil
            :result "unavailable"}
           (select-keys (:costUsd headline-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.0
            :ygg 2.0
            :delta 2.0
            :effect 0.0
            :result "observed"}
           (select-keys (:yggCommandCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 5
            :improvedMetrics 4
            :regressedMetrics 0
            :unchangedMetrics 0
            :observedMetrics 1
            :unavailableMetrics 0}
           (get-in categories-by-key ["command-telemetry" :summary])))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 4
            :improvedMetrics 3
            :regressedMetrics 0
            :unchangedMetrics 1
            :unavailableMetrics 0}
           (get-in categories-by-key ["result-health" :summary])))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 3
            :improvedMetrics 2
            :regressedMetrics 0
            :unchangedMetrics 1
            :unavailableMetrics 0}
           (get-in categories-by-key ["timing" :summary])))
    (is (= {:signal "metrics-unavailable"
            :minSharedCases 2
            :availableMetrics 0
            :improvedMetrics 0
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 4}
           (get-in categories-by-key ["token-cost" :summary])))
    (is (= {:status "not-supported"
            :broadEfficiencyClaimSupported false
            :sharedCases 2
            :minSharedCases 2
            :requirements {:sameSuite true
                           :sameCases true
                           :enoughSharedCases true
                           :yggImprovedWithoutRegressions true
                           :directionalMetrics true
                           :problemClassCoverage false
                           :architectureClassCoverage false
                           :evidenceMetrics true
                           :expectedEvidenceCitationMetrics true
                           :decisionQualityMetrics true
                           :commandTelemetry true
                           :perCaseTokenReduction false
                           :shellLaneClaimReady true
                           :yggLaneClaimReady true}
            :laneClaimReadiness {:shellOnly nil
                                 :ygg nil}
            :optionalTelemetry {:tokenCostMetrics false}
            :warnings ["Yggdrasil total token usage is not measured lower for every shared case; token-reduction claims are unproven or regressed."
                       "Shell-only report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim."
                       "Yggdrasil report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim."
                       "No shared problem-class tags; do not use this report for broad efficiency claims."
                       "No shared architecture-class tags; do not use this report to claim representative architecture-task gains."
                       "Problem-class measurement summaries are unavailable; regenerate agent reports before claiming broad efficiency."]
            :notes ["Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."]}
           (:claimReadiness comparison)))
    (is (= ["case-1" "case-2"]
           (mapv :caseId (:caseDeltas comparison))))
    (is (= {"shell-only" 2}
           (get-in comparison [:shellOnly :modes])))
    (is (= {"ygg" 2}
           (get-in comparison [:ygg :modes])))))

(deftest compares-decision-quality-when-present
  (let [shell (report {:mode "shell-only"
                       :recall5 0.5
                       :recall10 0.5
                       :recall20 0.5
                       :mrr 0.4
                       :noise 0.5
                       :evidence 0.25
                       :path-evidence 0.1
                       :decision-recall 0.5
                       :decision-precision 0.5
                       :decision-f1 0.5
                       :decision-evidence 0.25
                       :missing-decision 1
                       :decision-gaps 1
                       :missed 1
                       :outside5 1
                       :outside10 1
                       :missing-predicted 1
                       :empty 1
                       :commandless 2
                       :warnings 1
                       :command-count 9
                       :search-command-count 4
                       :file-read-command-count 2
                       :shell-command-count 3
                       :ygg-command-count 0
                       :elapsed 1000
                       :failed 0
                       :running 0
                       :case-ids ["case-1" "case-2"]})
        ygg (report {:mode "ygg"
                     :recall5 0.75
                     :recall10 0.75
                     :recall20 0.75
                     :mrr 0.6
                     :noise 0.25
                     :evidence 0.5
                     :path-evidence 0.4
                     :decision-recall 0.75
                     :decision-precision 1.0
                     :decision-f1 0.8571428571428571
                     :decision-evidence 1.0
                     :missing-decision 0
                     :decision-gaps 0
                     :missed 0
                     :outside5 0
                     :outside10 0
                     :missing-predicted 0
                     :empty 0
                     :commandless 1
                     :warnings 0
                     :command-count 5
                     :search-command-count 1
                     :file-read-command-count 1
                     :shell-command-count 1
                     :ygg-command-count 2
                     :elapsed 900
                     :failed 0
                     :running 0
                     :case-ids ["case-1" "case-2"]})
        comparison (agent-efficiency/compare-reports shell ygg)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))
        categories-by-key (into {} (map (juxt :category identity)) (:byCategory comparison))]
    (is (= {:shellOnly 0.5
            :ygg 0.8571428571428571
            :delta 0.3571428571428571
            :effect 0.3571428571428571
            :result "improved"}
           (select-keys (:decisionF1 deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 6
            :improvedMetrics 6
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 0}
           (get-in categories-by-key ["decision-quality" :summary])))
    (is (= 0.3571428571428571
           (get-in comparison [:headlineSummary :decisionF1Delta])))
    (is (= 0.75
           (get-in comparison
                   [:headlineSummary :decisionEvidenceCitationRateDelta])))
    (is (= true
           (get-in comparison
                   [:claimReadiness :requirements :decisionQualityMetrics])))
    (is (= ["decisionRecall"
            "decisionPrecision"
            "decisionF1"
            "decisionEvidenceCitationRate"]
           (->> (get-in comparison [:caseDeltas 0 :deltas])
                (filter #(str/starts-with? (:metric %) "decision"))
                (mapv :metric))))))

(deftest compares-segment-command-telemetry-when-available
  (let [shell (update-in shell-report
                         [:agentDiagnostics :commandTelemetry]
                         merge
                         {:segmentCount 12
                          :yggSegmentCount 0
                          :searchSegmentCount 5
                          :fileReadSegmentCount 3
                          :shellSegmentCount 4})
        ygg (update-in ygg-report
                       [:agentDiagnostics :commandTelemetry]
                       merge
                       {:segmentCount 7
                        :yggSegmentCount 4
                        :searchSegmentCount 1
                        :fileReadSegmentCount 1
                        :shellSegmentCount 1})
        comparison (agent-efficiency/compare-reports shell ygg)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= [:fileRecallAt10
            :noiseRatioAt20
            :evidenceCitationRate
            :pathEvidenceCitationRate
            :commandCount
            :searchCommandCount
            :fileReadCommandCount
            :segmentCount
            :searchSegmentCount
            :fileReadSegmentCount
            :elapsedMs
            :totalTokens
            :costUsd]
           (mapv :key (:headlineMetrics comparison))))
    (is (= {:shellOnly 12.0
            :ygg 7.0
            :delta -5.0
            :effect 5.0
            :result "improved"}
           (select-keys (:segmentCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 5.0
            :ygg 1.0
            :delta -4.0
            :effect 4.0
            :result "improved"}
           (select-keys (:searchSegmentCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 3.0
            :ygg 1.0
            :delta -2.0
            :effect 2.0
            :result "improved"}
           (select-keys (:fileReadSegmentCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.0
            :ygg 4.0
            :delta 4.0
            :effect 0.0
            :result "observed"}
           (select-keys (:yggSegmentCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))))

(deftest compares-token-cost-telemetry-when-available
  (let [shell (assoc-in shell-report
                        [:agentDiagnostics :tokenTelemetry]
                        {:totalTokens 12000
                         :inputTokens 9000
                         :outputTokens 3000
                         :costUsd 0.6})
        ygg (assoc-in ygg-report
                      [:agentDiagnostics :tokenTelemetry]
                      {:totalTokens 7000
                       :inputTokens 5500
                       :outputTokens 1500
                       :costUsd 0.35})
        comparison (agent-efficiency/compare-reports shell ygg)
        markdown (agent-efficiency/markdown-report comparison)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))
        headline-by-key (into {} (map (juxt :key identity))
                              (:headlineMetrics comparison))
        categories-by-key (into {} (map (juxt :category identity)) (:byCategory comparison))]
    (is (= {:shellOnly 12000.0
            :ygg 7000.0
            :delta -5000.0
            :effect 5000.0
            :result "improved"}
           (select-keys (:totalTokens deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.6
            :ygg 0.35
            :delta -0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:costUsd deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 12000.0
            :ygg 7000.0
            :delta -5000.0
            :effect 5000.0
            :result "improved"}
           (select-keys (:totalTokens headline-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.6
            :ygg 0.35
            :delta -0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:costUsd headline-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:totalTokensDelta -5000.0
            :costUsdDelta -0.25
            :failedRequirements [:problemClassCoverage
                                 :architectureClassCoverage
                                 :perCaseTokenReduction]
            :unavailableMetrics []}
           (select-keys (:headlineSummary comparison)
                        [:totalTokensDelta
                         :costUsdDelta
                         :failedRequirements
                         :unavailableMetrics])))
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :availableMetrics 4
            :improvedMetrics 4
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 0}
           (get-in categories-by-key ["token-cost" :summary])))
    (is (= "better-quality-lower-token-cost"
           (get-in comparison [:qualityCostTradeoff :status])))
    (is (= {:availableMetrics 7
            :improvedMetrics 7
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 1}
           (select-keys (get-in comparison [:qualityCostTradeoff :quality])
                        [:availableMetrics
                         :improvedMetrics
                         :regressedMetrics
                         :unchangedMetrics
                         :unavailableMetrics])))
    (is (= {:availableMetrics 4
            :improvedMetrics 4
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 0}
           (select-keys (get-in comparison [:qualityCostTradeoff :tokenCost])
                        [:availableMetrics
                         :improvedMetrics
                         :regressedMetrics
                         :unchangedMetrics
                         :unavailableMetrics])))
    (is (= {:fileRecallAt10 0.25
            :totalTokens -5000.0
            :costUsd -0.25}
           (select-keys (get-in comparison
                                [:qualityCostTradeoff :headlineDeltas])
                        [:fileRecallAt10 :totalTokens :costUsd])))
    (is (.contains markdown "## Quality/Token Tradeoff"))
    (is (.contains markdown "- Status: better-quality-lower-token-cost"))
    (is (.contains markdown "- totalTokens: improved (shell: 12000.0, ygg: 7000.0, delta: -5000.0)"))
    (is (.contains markdown "- costUsd: improved (shell: 0.6, ygg: 0.35, delta: -0.25)"))))

(deftest reports-context-artifact-telemetry-for-progressive-disclosure
  (let [ygg (assoc-in ygg-report
                      [:agentDiagnostics :contextArtifactTelemetry]
                      {:runs 2
                       :caseIds ["case-1" "case-2"]
                       :totals {:promptBytes 2000
                                :compactHintsBytes 4000
                                :fullHintsBytes 16000
                                :contextBytes 24000
                                :frontloadBytes 6000
                                :expansionBytes 40000
                                :fullAvailableBytes 46000
                                :hintSavingsBytes 12000}
                       :hintSavingsRatio 0.75
                       :frontloadToExpansionRatio 0.15})
        comparison (agent-efficiency/compare-reports shell-report ygg)
        markdown (agent-efficiency/markdown-report comparison)]
    (is (= {:runs 2
            :caseIds ["case-1" "case-2"]
            :promptBytes 2000
            :compactHintsBytes 4000
            :fullHintsBytes 16000
            :contextBytes 24000
            :frontloadBytes 6000
            :expansionBytes 40000
            :fullAvailableBytes 46000
            :hintSavingsBytes 12000
            :hintSavingsRatio 0.75
            :frontloadToExpansionRatio 0.15}
           (get-in comparison [:contextArtifacts :progressiveDisclosure])))
    (is (= 12000
           (get-in comparison
                   [:contextArtifacts :ygg :totals :hintSavingsBytes])))
    (is (.contains markdown "## Context Artifact Telemetry"))
    (is (.contains markdown
                   "- Yggdrasil: frontload 6000, compact hints 4000, full hints 16000, context 24000, hint savings 12000 (ratio 0.75)"))
    (is (.contains markdown
                   "- Ygg progressive disclosure: compact hints 4000 vs full hints 16000, saved 12000 (ratio 0.75), frontload 6000, expansion available 40000"))))

(deftest compares-task-token-usage-per-shared-case
  (let [shell (-> shell-report
                  (assoc-in [:results 0 :agent :tokenUsage]
                            {:inputTokens 1000
                             :outputTokens 500
                             :totalTokens 1500
                             :costUsd 0.10
                             :source "codex-json-events"})
                  (assoc-in [:results 1 :agent :tokenUsage]
                            {:inputTokens 500
                             :outputTokens 100
                             :totalTokens 600
                             :costUsd 0.04
                             :source "codex-json-events"}))
        ygg (-> ygg-report
                (assoc-in [:results 0 :agent :tokenUsage]
                          {:inputTokens 700
                           :outputTokens 200
                           :totalTokens 900
                           :costUsd 0.06
                           :source "codex-json-events"})
                (assoc-in [:results 1 :agent :tokenUsage]
                          {:inputTokens 800
                           :outputTokens 200
                           :totalTokens 1000
                           :costUsd 0.07
                           :source "codex-json-events"}))
        comparison (agent-efficiency/compare-reports shell ygg)
        markdown (agent-efficiency/markdown-report comparison)
        case-by-id (into {} (map (juxt :caseId identity))
                         (:caseDeltas comparison))
        case-1-token-by-key (into {} (map (juxt :key identity))
                                  (get-in case-by-id
                                          ["case-1" :taskTokenDeltas]))
        case-2-token-by-key (into {} (map (juxt :key identity))
                                  (get-in case-by-id
                                          ["case-2" :taskTokenDeltas]))]
    (is (= {:shellOnly 1500.0
            :ygg 900.0
            :delta -600.0
            :effect 600.0
            :result "improved"}
           (select-keys (:taskTotalTokens case-1-token-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 600.0
            :ygg 1000.0
            :delta 400.0
            :effect -400.0
            :result "regressed"}
           (select-keys (:taskTotalTokens case-2-token-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 1000.0
            :ygg 700.0
            :delta -300.0
            :effect 300.0
            :result "improved"}
           (select-keys (:taskInputTokens case-1-token-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (.contains markdown "## Task Token Deltas"))
    (is (= {:sharedCases 2
            :measuredCases 2
            :improvedCases 1
            :regressedCases 1
            :unchangedCases 0
            :unavailableCases 0
            :invalidCases 0
            :allSharedCasesMeasured true
            :allSharedCasesReduced false
            :improvedCaseIds ["case-1"]
            :regressedCaseIds ["case-2"]
            :unchangedCaseIds []
            :unavailableCaseIds []
            :invalidCaseIds []}
           (select-keys (:perCaseTokenReduction comparison)
                        [:sharedCases
                         :measuredCases
                         :improvedCases
                         :regressedCases
                         :unchangedCases
                         :unavailableCases
                         :invalidCases
                         :allSharedCasesMeasured
                         :allSharedCasesReduced
                         :improvedCaseIds
                         :regressedCaseIds
                         :unchangedCaseIds
                         :unavailableCaseIds
                         :invalidCaseIds])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :perCaseTokenReduction])))
    (is (.contains markdown "- case-1: improved (shell: 1500.0, ygg: 900.0, delta: -600.0)"))
    (is (.contains markdown "- case-2: regressed (shell: 600.0, ygg: 1000.0, delta: 400.0)"))))

(deftest zero-or-missing-token-usage-does-not-support-token-reduction
  (let [shell (-> shell-report
                  (assoc-in [:results 0 :agent :tokenUsage]
                            {:inputTokens 0
                             :outputTokens 0
                             :totalTokens 0
                             :costUsd 0.0
                             :source "placeholder"}))
        ygg (-> ygg-report
                (assoc-in [:results 0 :agent :tokenUsage]
                          {:inputTokens 50
                           :outputTokens 0
                           :totalTokens 50
                           :costUsd 0.0
                           :source "codex-json-events"})
                (assoc-in [:results 1 :agent :tokenUsage]
                          {:inputTokens 40
                           :outputTokens 0
                           :totalTokens 40
                           :costUsd 0.0
                           :source "codex-json-events"}))
        comparison (agent-efficiency/compare-reports shell ygg)
        markdown (agent-efficiency/markdown-report comparison)
        token-reduction (:perCaseTokenReduction comparison)
        case-by-id (into {} (map (juxt :caseId identity))
                         (:caseDeltas comparison))
        case-1-token-by-key (into {} (map (juxt :key identity))
                                  (get-in case-by-id
                                          ["case-1" :taskTokenDeltas]))
        case-2-token-by-key (into {} (map (juxt :key identity))
                                  (get-in case-by-id
                                          ["case-2" :taskTokenDeltas]))]
    (is (= {:sharedCases 2
            :measuredCases 0
            :improvedCases 0
            :regressedCases 0
            :unchangedCases 0
            :unavailableCases 1
            :invalidCases 1
            :allSharedCasesMeasured false
            :allSharedCasesReduced false
            :unavailableCaseIds ["case-2"]
            :invalidCaseIds ["case-1"]}
           (select-keys token-reduction
                        [:sharedCases
                         :measuredCases
                         :improvedCases
                         :regressedCases
                         :unchangedCases
                         :unavailableCases
                         :invalidCases
                         :allSharedCasesMeasured
                         :allSharedCasesReduced
                         :unavailableCaseIds
                         :invalidCaseIds])))
    (is (= ["Task total token telemetry is missing for at least one shared case."
            "Task total token telemetry contains zero or non-positive placeholder values."]
           (:warnings token-reduction)))
    (is (= {:shellOnly 0.0
            :ygg 50.0
            :available false
            :result "invalid"
            :reason "non-positive-total-tokens"}
           (select-keys (:taskTotalTokens case-1-token-by-key)
                        [:shellOnly :ygg :available :result :reason])))
    (is (= {:shellOnly 0.0
            :ygg 50.0
            :available false
            :result "invalid"
            :reason "non-positive-total-tokens"}
           (select-keys (:taskInputTokens case-1-token-by-key)
                        [:shellOnly :ygg :available :result :reason])))
    (is (= {:shellOnly nil
            :ygg 40.0
            :available false
            :result "unavailable"}
           (select-keys (:taskTotalTokens case-2-token-by-key)
                        [:shellOnly :ygg :available :result])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :perCaseTokenReduction])))
    (is (.contains markdown "- case-1: invalid (shell: 0.0, ygg: 50.0, delta: unavailable)"))
    (is (.contains markdown "- case-2: unavailable (shell: unavailable, ygg: 40.0, delta: unavailable)"))))

(deftest improvement-targets-count-against-efficiency-claims
  (let [ygg (assoc ygg-report
                   :improvementSummary [{:kind "hint-diagnostics"
                                         :area "agent-context-quality"
                                         :runs 2
                                         :caseIds ["case-1"]
                                         :message "Yggdrasil hints contained diagnostics."}])
        shifted-shell (assoc shell-report
                             :improvementSummary [{:kind "coverage-filtered-candidates"
                                                   :area "agent-context-quality"
                                                   :runs 2
                                                   :caseIds ["case-1"]}])
        shifted-ygg (assoc ygg-report
                           :improvementSummary [{:kind "audit-scope-trust-boundary"
                                                 :area "audit-scope-quality"
                                                 :runs 2
                                                 :caseIds ["case-2"]}])
        comparison (agent-efficiency/compare-reports shell-report ygg)
        shifted-comparison (agent-efficiency/compare-reports shifted-shell
                                                             shifted-ygg)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= {:shellOnly 0.0
            :ygg 2.0
            :delta 2.0
            :effect -2.0
            :result "regressed"}
           (select-keys (:improvementTargetRuns deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:shellOnly 0.0
            :ygg 2.0
            :delta 2.0
            :effect -2.0
            :result "regressed"}
           (select-keys (get deltas-by-key
                             "improvementTargetRuns.hint-diagnostics")
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {"hint-diagnostics" 2}
           (get-in comparison [:ygg :improvementTargetRunsByKind])))
    (is (= "mixed" (:status comparison)))
    (is (= false
           (get-in comparison
                   [:claimReadiness
                    :requirements
                    :yggImprovedWithoutRegressions])))
    (is (= "mixed" (:status shifted-comparison)))
    (is (= 0.0
           (:delta (first (filter #(= :improvementTargetRuns (:key %))
                                  (:deltas shifted-comparison))))))
    (is (= "regressed"
           (:result (first (filter #(= "improvementTargetRuns.audit-scope-trust-boundary"
                                       (:key %))
                                   (:deltas shifted-comparison))))))))

(deftest compares-shell-only-and-ygg-by-tag-groups
  (let [shell (assoc shell-report
                     :byTag [(tag-row "problem-localization"
                                      {:cases 1
                                       :runs 1
                                       :recall10 0.5
                                       :noise 0.5})
                             (tag-row "problem-cross-file"
                                      {:cases 1
                                       :runs 1
                                       :recall10 0.5
                                       :noise 0.75})])
        ygg (assoc ygg-report
                   :byTag [(tag-row "problem-localization"
                                    {:cases 1
                                     :runs 1
                                     :recall10 1.0
                                     :noise 0.25})
                           (tag-row "problem-cross-file"
                                    {:cases 1
                                     :runs 1
                                     :recall10 0.25
                                     :noise 0.5})])
        comparison (agent-efficiency/compare-reports shell ygg)
        groups-by-tag (into {} (map (juxt :tag identity)) (get-in comparison
                                                                  [:byTag :groups]))]
    (is (= {:sameTags true
            :sharedTags 2
            :sharedTagKeys ["problem-cross-file" "problem-localization"]
            :shellOnlyTagKeys []
            :yggOnlyTagKeys []
            :warnings []}
           (get-in comparison [:byTag :comparability])))
    (is (= {:sharedTagKeys ["problem-cross-file" "problem-localization"]
            :problemClassTags ["problem-cross-file" "problem-localization"]
            :architectureClassTags []
            :problemClassSummaryAvailable false
            :hasProblemClasses true
            :hasArchitectureClasses false
            :hasMeasuredProblemClasses false
            :hasMeasuredArchitectureClasses false
            :broadEfficiencyClaimSupported false}
           (select-keys (:problemClassCoverage comparison)
                        [:sharedTagKeys
                         :problemClassTags
                         :architectureClassTags
                         :problemClassSummaryAvailable
                         :hasProblemClasses
                         :hasArchitectureClasses
                         :hasMeasuredProblemClasses
                         :hasMeasuredArchitectureClasses
                         :broadEfficiencyClaimSupported])))
    (is (= ["No shared architecture-class tags; do not use this report to claim representative architecture-task gains."
            "Problem-class measurement summaries are unavailable; regenerate agent reports before claiming broad efficiency."]
           (get-in comparison [:problemClassCoverage :warnings])))
    (is (= "ygg-improved"
           (get-in groups-by-tag ["problem-localization" :summary :signal])))
    (is (= "mixed"
           (get-in groups-by-tag ["problem-cross-file" :summary :signal])))
    (is (= {:shellOnly 0.5
            :ygg 1.0
            :delta 0.5
            :effect 0.5
            :result "improved"}
           (->> (get-in groups-by-tag ["problem-localization" :deltas])
                (filter #(= :fileRecallAt10 (:key %)))
                first
                (#(select-keys % [:shellOnly :ygg :delta :effect :result])))))))

(deftest problem-class-coverage-recognizes-measured-architecture-classes
  (let [shell (-> shell-report
                  with-problem-classes
                  with-claim-readiness
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "architecture-dependency-flow"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                with-problem-classes
                with-claim-readiness
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})
                               (tag-row "architecture-dependency-flow"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})]))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        comparison (agent-efficiency/compare-reports shell ygg)
        class-summary (get-in comparison [:classSignals :summary])
        problem-signals (get-in comparison [:classSignals :problemClasses])
        architecture-signals (get-in comparison [:classSignals :architectureClasses])
        architecture-by-tag (into {} (map (juxt :tag identity)) architecture-signals)]
    (is (= {:sharedTagKeys ["architecture-dependency-flow" "problem-architecture"]
            :problemClassTags ["problem-architecture"]
            :architectureClassTags ["architecture-dependency-flow"]
            :problemClassSummaryAvailable true
            :sharedMeasuredProblemClassTags ["problem-architecture"]
            :sharedMeasuredArchitectureClassTags ["architecture-dependency-flow"]
            :hasProblemClasses true
            :hasArchitectureClasses true
            :hasMeasuredProblemClasses true
            :hasMeasuredArchitectureClasses true
            :broadEfficiencyClaimSupported true
            :warnings []}
           (select-keys (:problemClassCoverage comparison)
                        [:sharedTagKeys
                         :problemClassTags
                         :architectureClassTags
                         :problemClassSummaryAvailable
                         :sharedMeasuredProblemClassTags
                         :sharedMeasuredArchitectureClassTags
                         :hasProblemClasses
                         :hasArchitectureClasses
                         :hasMeasuredProblemClasses
                         :hasMeasuredArchitectureClasses
                         :broadEfficiencyClaimSupported
                         :warnings])))
    (is (= {:problemClasses 1
            :measuredProblemClasses 1
            :architectureClasses 1
            :measuredArchitectureClasses 1}
           class-summary))
    (is (= ["problem-architecture"] (mapv :tag problem-signals)))
    (is (= ["architecture-dependency-flow"]
           (mapv :tag architecture-signals)))
    (is (= {:shellOnly {:cases 2 :runs 2}
            :ygg {:cases 2 :runs 2}
            :signal "ygg-improved"
            :measured true}
           {:shellOnly (:shellOnly (first problem-signals))
            :ygg (:ygg (first problem-signals))
            :signal (get-in (first problem-signals) [:summary :signal])
            :measured (:measured (first problem-signals))}))
    (is (= "ygg-improved"
           (get-in architecture-by-tag
                   ["architecture-dependency-flow" :summary :signal])))
    (is (= true
           (get-in architecture-by-tag
                   ["architecture-dependency-flow" :measured])))
    (is (= {:status "supported"
            :broadEfficiencyClaimSupported true
            :sharedCases 2
            :minSharedCases 2
            :requirements {:sameSuite true
                           :sameCases true
                           :enoughSharedCases true
                           :yggImprovedWithoutRegressions true
                           :directionalMetrics true
                           :problemClassCoverage true
                           :architectureClassCoverage true
                           :evidenceMetrics true
                           :expectedEvidenceCitationMetrics true
                           :decisionQualityMetrics true
                           :commandTelemetry true
                           :perCaseTokenReduction true
                           :shellLaneClaimReady true
                           :yggLaneClaimReady true}
            :laneClaimReadiness {:shellOnly (:claimReadiness shell)
                                 :ygg (:claimReadiness ygg)}
            :optionalTelemetry {:tokenCostMetrics false}
            :warnings []
            :notes ["Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."]}
           (:claimReadiness comparison)))))

(deftest problem-class-coverage-recognizes-audit-scope-architecture-classes
  (let [problem-classes {:minimumCasesForClassClaim 2
                         :classes [{:key "problem-architecture"
                                    :cases 2
                                    :runs 2
                                    :claimStatus "measured"
                                    :minimumCases 2}]
                         :architectureClasses [{:key "audit-scope-docs"
                                                :cases 2
                                                :runs 2
                                                :claimStatus "measured"
                                                :minimumCases 2}]}
        shell (-> shell-report
                  (assoc :problemClasses problem-classes)
                  with-claim-readiness
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "audit-scope-docs"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                (assoc :problemClasses problem-classes)
                with-claim-readiness
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})
                               (tag-row "audit-scope-docs"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})]))
        comparison (agent-efficiency/compare-reports shell ygg)
        architecture-signals (get-in comparison [:classSignals :architectureClasses])
        audit-scope-signal (first architecture-signals)]
    (is (= ["audit-scope-docs"]
           (get-in comparison
                   [:problemClassCoverage
                    :sharedMeasuredArchitectureClassTags])))
    (is (= ["audit-scope-docs"]
           (mapv :tag architecture-signals)))
    (is (= {:tag "audit-scope-docs"
            :measured true
            :shellOnly {:cases 2 :runs 2}
            :ygg {:cases 2 :runs 2}
            :signal "ygg-improved"}
           {:tag (:tag audit-scope-signal)
            :measured (:measured audit-scope-signal)
            :shellOnly (:shellOnly audit-scope-signal)
            :ygg (:ygg audit-scope-signal)
            :signal (get-in audit-scope-signal [:summary :signal])}))))

(deftest measured-class-coverage-refuses-insufficient-architecture-classes
  (let [shell (-> shell-report
                  (with-problem-classes {:architecture-status
                                         "insufficient-cases"})
                  with-claim-readiness
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 1
                                           :runs 1
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "architecture-dependency-flow"
                                          {:cases 1
                                           :runs 1
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                (with-problem-classes {:architecture-status
                                       "insufficient-cases"})
                with-claim-readiness
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 1
                                         :runs 1
                                         :recall10 1.0
                                         :noise 0.25})
                               (tag-row "architecture-dependency-flow"
                                        {:cases 1
                                         :runs 1
                                         :recall10 1.0
                                         :noise 0.25})]))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        comparison (agent-efficiency/compare-reports shell ygg)]
    (is (= true (get-in comparison
                        [:problemClassCoverage
                         :hasMeasuredProblemClasses])))
    (is (= false (get-in comparison
                         [:problemClassCoverage
                          :hasMeasuredArchitectureClasses])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :broadEfficiencyClaimSupported])))
    (is (= false
           (get-in comparison
                   [:claimReadiness
                    :requirements
                    :architectureClassCoverage])))
    (is (= ["No shared measured architecture-class groups; architecture tags are present but below the benchmark claim threshold in at least one lane."]
           (get-in comparison [:claimReadiness :warnings])))))

(deftest refuses-broad-efficiency-when-source-lane-is-not-claim-ready
  (let [shell (-> shell-report
                  with-problem-classes
                  (with-claim-readiness false)
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "architecture-dependency-flow"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                with-problem-classes
                with-claim-readiness
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})
                               (tag-row "architecture-dependency-flow"
                                        {:cases 2
                                         :runs 2
                                         :recall10 1.0
                                         :noise 0.25})]))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        comparison (agent-efficiency/compare-reports shell ygg)]
    (is (= false
           (get-in comparison
                   [:claimReadiness :broadEfficiencyClaimSupported])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :shellLaneClaimReady])))
    (is (= true
           (get-in comparison
                   [:claimReadiness :requirements :yggLaneClaimReady])))
    (is (= ["Shell-only report is not claim-ready; fix that lane before comparing broad efficiency."]
           (get-in comparison [:claimReadiness :warnings])))))

(deftest ignores-small-report-timing-jitter
  (let [shell (report {:mode "shell-only"
                       :recall5 1.0
                       :recall10 1.0
                       :recall20 1.0
                       :mrr 1.0
                       :noise 0.0
                       :evidence 1.0
                       :path-evidence 1.0
                       :missed 0
                       :outside5 0
                       :outside10 0
                       :missing-predicted 0
                       :empty 0
                       :commandless 0
                       :warnings 0
                       :elapsed 1000
                       :failed 0
                       :running 0
                       :case-ids ["case-1"]})
        ygg (assoc-in shell [:timings :elapsedMs] 1025)
        comparison (agent-efficiency/compare-reports shell
                                                     ygg
                                                     {:min-shared-cases 1})
        elapsed-delta (->> (:deltas comparison)
                           (filter #(= :elapsedMs (:key %)))
                           first)]
    (is (= "unchanged" (:status comparison)))
    (is (= {:shellOnly 1000.0
            :ygg 1025.0
            :delta 25.0
            :rawEffect -25.0
            :effect 0.0
            :tolerance 50.0
            :result "unchanged"}
           (select-keys elapsed-delta
                        [:shellOnly
                         :ygg
                         :delta
                         :rawEffect
                         :effect
                         :tolerance
                         :result])))))

(deftest timing-regressions-do-not-block-non-performance-claim
  (let [shell (-> shell-report
                  with-problem-classes
                  with-claim-readiness
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "architecture-dependency-flow"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                with-problem-classes
                with-claim-readiness
                (assoc-in [:timings :elapsedMs] 3000)
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 2
                                         :runs 2
                                         :recall10 0.75
                                         :noise 0.25})
                               (tag-row "architecture-dependency-flow"
                                        {:cases 2
                                         :runs 2
                                         :recall10 0.75
                                         :noise 0.25})]))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        comparison (agent-efficiency/compare-reports shell ygg)
        markdown (agent-efficiency/markdown-report comparison)
        deltas-by-key (into {} (map (juxt :key identity))
                            (:deltas comparison))
        categories-by-key (into {} (map (juxt :category identity))
                                (:byCategory comparison))]
    (is (= "ygg-improved" (:status comparison)))
    (is (= "mixed" (get-in comparison [:summary :signal])))
    (is (= "ygg-improved" (get-in comparison [:claimSummary :signal])))
    (is (= "mixed" (get-in categories-by-key ["timing" :summary :signal])))
    (is (= {:delta 2000.0
            :rawEffect -2000.0
            :effect -2000.0
            :result "regressed"}
           (select-keys (:elapsedMs deltas-by-key)
                        [:delta :rawEffect :effect :result])))
    (is (= "supported" (get-in comparison [:claimReadiness :status])))
    (is (true? (get-in comparison
                       [:claimReadiness :broadEfficiencyClaimSupported])))
    (is (= [] (get-in comparison [:claimReadiness :warnings])))
    (is (= "helped" (get-in comparison [:compactSummary :verdict])))
    (is (= 0 (get-in comparison [:compactSummary :regressedMetrics])))
    (is (.contains markdown "- Regressed metrics: 0"))
    (is (.contains markdown "- Overall metrics including timing:"))
    (is (.contains markdown "regressed 1"))))

(deftest reports-comparability-warnings-for-different-case-sets
  (let [ygg-partial (assoc ygg-report
                           :results [(first (:results ygg-report))])
        comparison (agent-efficiency/compare-reports shell-report ygg-partial)]
    (is (= false (get-in comparison [:comparability :sameCases])))
    (is (= ["case-2"]
           (get-in comparison [:comparability :shellOnlyCaseIds])))
    (is (= ["Reports do not contain the same completed case ids."]
           (get-in comparison [:comparability :warnings])))))

(deftest refuses-broad-efficiency-signal-below-minimum-shared-cases
  (let [shell (report {:mode "shell-only"
                       :recall5 1.0
                       :recall10 1.0
                       :recall20 1.0
                       :mrr 1.0
                       :noise 0.0
                       :evidence 1.0
                       :path-evidence 1.0
                       :missed 0
                       :outside5 0
                       :outside10 0
                       :missing-predicted 0
                       :empty 0
                       :commandless 0
                       :warnings 0
                       :elapsed 1000
                       :failed 0
                       :running 0
                       :case-ids ["case-1"]})
        ygg (assoc-in shell [:scores :fileRecallAt10] 1.0)
        comparison (agent-efficiency/compare-reports shell ygg)]
    (is (= "insufficient-cases" (:status comparison)))
    (is (= 1 (get-in comparison [:comparability :sharedCases])))
    (is (= 2 (get-in comparison [:summary :minSharedCases])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :broadEfficiencyClaimSupported])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :enoughSharedCases])))))

(deftest reports-missing-metrics-as-unavailable
  (let [shell (update shell-report :scores dissoc :evidenceCitationRate)
        ygg (update ygg-report :timings dissoc :elapsedMs)
        comparison (agent-efficiency/compare-reports shell ygg)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= {:signal "ygg-improved"
            :minSharedCases 2
            :improvedMetrics 18
            :regressedMetrics 0
            :unchangedMetrics 2
            :observedMetrics 1
            :availableMetrics 21
            :unavailableMetrics 7}
           (:summary comparison)))
    (is (= {:shellOnly nil
            :ygg 0.5
            :available false
            :result "unavailable"}
           (select-keys (:evidenceCitationRate deltas-by-key)
                        [:shellOnly :ygg :available :result])))
    (is (= {:shellOnly 1000.0
            :ygg nil
            :available false
            :result "unavailable"}
           (select-keys (:elapsedMs deltas-by-key)
                        [:shellOnly :ygg :available :result])))
    (is (not (contains? (:elapsedMs deltas-by-key) :delta)))
    (is (not (contains? (:evidenceCitationRate deltas-by-key) :effect)))))

(deftest claim-readiness-requires-comparable-expected-evidence-citation
  (let [shell (with-claim-readiness (with-problem-classes shell-report))
        ygg (-> ygg-report
                with-problem-classes
                with-claim-readiness
                (assoc-in [:scores :expectedEvidenceCitationRate] 0.8))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        comparison (agent-efficiency/compare-reports shell ygg)]
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :expectedEvidenceCitationMetrics])))
    (is (= [:expectedEvidenceCitationMetrics]
           (get-in comparison [:headlineSummary :failedRequirements])))
    (is (some #(= "Expected evidence citation metrics are only available in one lane; non-code evidence citation quality is not comparable."
                  %)
              (get-in comparison [:claimReadiness :warnings])))))

(deftest reports-all-missing-metrics-as-unavailable-signal
  (let [strip-metrics (fn [report]
                        (-> report
                            (dissoc :scores
                                    :localizationDiagnostics
                                    :agentDiagnostics
                                    :timings
                                    :improvementSummary)
                            (assoc :results (mapv #(dissoc % :scores)
                                                  (:results report)))))
        comparison (agent-efficiency/compare-reports
                    (strip-metrics shell-report)
                    (strip-metrics ygg-report))]
    (is (= "metrics-unavailable" (:status comparison)))
    (is (= {:signal "metrics-unavailable"
            :minSharedCases 2
            :availableMetrics 0
            :improvedMetrics 0
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 28}
           (:summary comparison)))
    (is (every? #(= "unavailable" (:result %))
                (:deltas comparison)))))

(deftest reports-observed-only-telemetry-separately-from-missing-metrics
  (let [strip-to-observed (fn [report observed-count]
                            (-> report
                                (dissoc :scores
                                        :localizationDiagnostics
                                        :agentDiagnostics
                                        :timings
                                        :improvementSummary)
                                (assoc :agentDiagnostics
                                       {:commandTelemetry
                                        {:yggCommandCount observed-count}})
                                (assoc :results (mapv #(dissoc % :scores)
                                                      (:results report)))))
        comparison (agent-efficiency/compare-reports
                    (strip-to-observed shell-report 0)
                    (strip-to-observed ygg-report 3))
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= "observed-only" (:status comparison)))
    (is (= {:signal "observed-only"
            :minSharedCases 2
            :availableMetrics 1
            :improvedMetrics 0
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 27
            :observedMetrics 1}
           (:summary comparison)))
    (is (= {:shellOnly 0.0
            :ygg 3.0
            :delta 3.0
            :effect 0.0
            :result "observed"}
           (select-keys (:yggCommandCount deltas-by-key)
                        [:shellOnly :ygg :delta :effect :result])))
    (is (= {:directionalMetrics false
            :commandTelemetry false}
           (select-keys (get-in comparison [:claimReadiness :requirements])
                        [:directionalMetrics :commandTelemetry])))
    (is (some #(= "Directional efficiency metrics are unavailable; observed telemetry alone cannot support an improvement claim."
                  %)
              (get-in comparison [:claimReadiness :warnings])))))

(deftest writes-comparison-from-agent-report-files
  (let [root (temp-dir "ygg-agent-efficiency")
        shell-path (spit-json! root "shell/agent-report.json" shell-report)
        ygg-path (spit-json! root "ygg/agent-report.json" ygg-report)
        out-path (.getPath (io/file root "summary.json"))
        markdown-path (.getPath (io/file root "REPORT.md"))
        comparison (agent-efficiency/compare-report-files!
                    shell-path
                    ygg-path
                    {:out out-path
                     :markdown-out markdown-path})
        written (read-json out-path)
        markdown (slurp markdown-path)]
    (is (= "ygg-improved" (:status comparison)))
    (is (= "ygg-improved" (:status written)))
    (is (= shell-path (get-in written [:inputs :shellReport])))
    (is (= ygg-path (get-in written [:inputs :yggReport])))
    (is (.contains markdown "# Yggdrasil Agent Efficiency"))
    (is (.contains markdown "- Status: ygg-improved"))
    (is (.contains markdown "- Verdict: inconclusive"))
    (is (.contains markdown "## Compact Verdict"))
    (is (.contains markdown "Claim readiness is not supported"))
    (is (.contains markdown "## Inputs"))
    (is (.contains markdown (str "- Shell-only report: " shell-path)))
    (is (.contains markdown (str "- Yggdrasil report: " ygg-path)))
    (is (.contains markdown "## Headline Summary"))
    (is (.contains markdown "- Broad efficiency claim supported: false"))
    (is (.contains markdown "- Shared cases: 2/2"))
    (is (.contains markdown "- Failed claim requirements: problemClassCoverage, architectureClassCoverage"))
    (is (.contains markdown "- tool call delta: -4.0"))
    (is (.contains markdown "- totalTokens delta: unavailable"))
    (is (.contains markdown "- Unavailable headline metrics: totalTokens, costUsd"))
    (is (.contains markdown "## Key Metric Deltas"))
    (is (.contains markdown "- commandCount: improved"))
    (is (.contains markdown "- searchCommandCount: improved"))
    (is (.contains markdown "- elapsedMs: improved"))
    (is (.contains markdown "- totalTokens: unavailable"))
    (is (.contains markdown "## Claim Readiness Requirements"))
    (is (.contains markdown "- sameSuite: pass"))
    (is (.contains markdown "- problemClassCoverage: fail"))
    (is (.contains markdown "## Category Signals"))
    (is (.contains markdown "- command-telemetry: ygg-improved"))
    (is (.contains markdown "## Case Signals"))
    (is (.contains markdown "- case-1: ygg-improved"))
    (is (.contains markdown "- Claim readiness: not-supported"))))

(deftest plain-output-shows-category-and-class-signals
  (let [root (temp-dir "ygg-agent-efficiency-plain")
        shell (-> shell-report
                  with-problem-classes
                  with-claim-readiness
                  (assoc :byTag [(tag-row "problem-architecture"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})
                                 (tag-row "architecture-dependency-flow"
                                          {:cases 2
                                           :runs 2
                                           :recall10 0.5
                                           :noise 0.5})]))
        ygg (-> ygg-report
                with-problem-classes
                with-claim-readiness
                (assoc :byTag [(tag-row "problem-architecture"
                                        {:cases 2
                                         :runs 2
                                         :recall10 0.75
                                         :noise 0.25})
                               (tag-row "architecture-dependency-flow"
                                        {:cases 2
                                         :runs 2
                                         :recall10 0.75
                                         :noise 0.25})]))
        [shell ygg] (with-reduced-ygg-token-usage shell ygg)
        shell-path (spit-json! root "shell/agent-report.json" shell)
        ygg-path (spit-json! root "ygg/agent-report.json" ygg)
        out (with-out-str
              (agent-efficiency/-main shell-path
                                      ygg-path
                                      "--markdown-out"
                                      (.getPath (io/file root "REPORT.md"))))]
    (is (.contains out "Verdict: helped"))
    (is (.contains out "Compared lanes are claim-ready for the measured architecture slice."))
    (is (.contains out "Category signals:"))
    (is (.contains out "observed metrics: 1"))
    (is (.contains out
                   "Class signal summary: problem 1/1 measured, architecture 1/1 measured"))
    (is (.contains out
                   "- command-telemetry: ygg-improved (observed metrics: 1)"))
    (is (.contains out "Problem-class signals:"))
    (is (.contains out
                   "- problem-architecture: ygg-improved (shell cases: 2, ygg cases: 2)"))
    (is (.contains out "Architecture-class signals:"))
    (is (.contains out
                   "- architecture-dependency-flow: ygg-improved (shell cases: 2, ygg cases: 2)"))
    (is (.contains out "Claim readiness: supported"))
    (is (.contains out "Claim readiness notes:"))
    (is (.contains out "Wrote "))))

(deftest case-deltas-include-audit-scope-summaries-when-present
  (let [shell (assoc-in shell-report
                        [:results 0 :auditScope]
                        {:groundTruthSummary {:total 3
                                              :found 2
                                              :missed 1
                                              :presentInContextButMissed 0}
                         :graphExpectationSummary {:expectedEvidence 2
                                                   :foundEvidence 2}})
        ygg (assoc-in ygg-report
                      [:results 0 :auditScope]
                      {:groundTruthSummary {:total 3
                                            :found 3
                                            :missed 0
                                            :presentInContextButMissed 0}
                       :graphExpectationSummary {:expectedEvidence 2
                                                 :foundEvidence 2}})
        comparison (agent-efficiency/compare-reports shell ygg)
        case-1 (first (:caseDeltas comparison))]
    (is (= "case-1" (:caseId case-1)))
    (is (= {:groundTruth {:total 3
                          :found 2
                          :missed 1
                          :presentInContextButMissed 0}
            :graphExpectations {:expectedEvidence 2
                                :foundEvidence 2}}
           (:shellOnlyAuditScope case-1)))
    (is (= {:groundTruth {:total 3
                          :found 3
                          :missed 0
                          :presentInContextButMissed 0}
            :graphExpectations {:expectedEvidence 2
                                :foundEvidence 2}}
           (:yggAuditScope case-1)))))

(deftest case-deltas-omit-audit-scope-when-absent
  (let [comparison (agent-efficiency/compare-reports shell-report ygg-report)
        case-1 (first (:caseDeltas comparison))]
    (is (nil? (:shellOnlyAuditScope case-1)))
    (is (nil? (:yggAuditScope case-1)))))

(deftest markdown-report-includes-case-audit-scopes-when-present
  (let [shell (assoc-in shell-report
                        [:results 0 :auditScope]
                        {:groundTruthSummary {:total 3
                                              :found 2
                                              :missed 1
                                              :presentInContextButMissed 1}
                         :graphExpectationSummary {:expectedEvidence 2
                                                   :foundEvidence 1}})
        ygg (assoc-in ygg-report
                      [:results 0 :auditScope]
                      {:groundTruthSummary {:total 3
                                            :found 3
                                            :missed 0
                                            :presentInContextButMissed 0}
                       :graphExpectationSummary {:expectedEvidence 2
                                                 :foundEvidence 2}})
        comparison (agent-efficiency/compare-reports shell ygg)
        markdown (agent-efficiency/markdown-report comparison)]
    (is (.contains markdown "## Case Audit Scopes"))
    (is (.contains markdown "case-1:"))
    (is (.contains markdown "shell: ground-truth 2/3 found, 1 present-in-context, graph-evidence 1/2"))
    (is (.contains markdown "ygg: ground-truth 3/3 found, graph-evidence 2/2"))))

(deftest markdown-report-omits-case-audit-scopes-when-absent
  (let [comparison (agent-efficiency/compare-reports shell-report ygg-report)
        markdown (agent-efficiency/markdown-report comparison)]
    (is (not (.contains markdown "## Case Audit Scopes")))))
