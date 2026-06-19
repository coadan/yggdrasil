(ns agraph.agent-efficiency-test
  (:require [agraph.agent-efficiency :as agent-efficiency]
            [charred.api :as json]
            [clojure.java.io :as io]
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
           missed outside5 outside10 missing-predicted empty commandless warnings
           command-count search-command-count file-read-command-count
           shell-command-count elapsed failed running case-ids]}]
  {:schema "agraph.benchmark.agent-report/v1"
   :suite-id "suite"
   :cases (count case-ids)
   :completed (count case-ids)
   :runs (count case-ids)
   :missing []
   :scores {:fileRecallAt5 recall5
            :fileRecallAt10 recall10
            :fileRecallAt20 recall20
            :meanReciprocalRankFile mrr
            :noiseRatioAt20 noise
            :evidenceCitationRate evidence
            :pathEvidenceCitationRate path-evidence}
   :localizationDiagnostics {:missedRuns missed
                             :rankedOutsideTop5Runs outside5
                             :rankedOutsideTop10Runs outside10}
   :agentDiagnostics {:missingPredictedFileRuns missing-predicted
                      :emptyResultRuns empty
                      :commandlessRuns commandless
                      :warningRuns warnings
                      :commandTelemetry {:commandCount command-count
                                         :searchCommandCount search-command-count
                                         :fileReadCommandCount file-read-command-count
                                         :shellCommandCount shell-command-count}}
   :timings {:elapsedMs elapsed
             :failedCases failed
             :runningCases running}
   :results (mapv (fn [case-id]
                    {:case-id case-id
                     :agent {:mode mode
                             :agentId "codex"}
                     :scores {:fileRecallAt5 recall5
                              :fileRecallAt10 recall10
                              :fileRecallAt20 recall20
                              :meanReciprocalRankFile mrr
                              :noiseRatioAt20 noise
                              :evidenceCitationRate evidence
                              :pathEvidenceCitationRate path-evidence}})
                  case-ids)})

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
           :elapsed 1000
           :failed 1
           :running 0
           :case-ids ["case-1" "case-2"]}))

(def agraph-report
  (report {:mode "agraph"
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
           :elapsed 900
           :failed 0
           :running 0
           :case-ids ["case-1" "case-2"]}))

(deftest compares-shell-only-and-agraph-agent-reports
  (let [comparison (agent-efficiency/compare-reports shell-report agraph-report)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))
        categories-by-key (into {} (map (juxt :category identity)) (:byCategory comparison))]
    (is (= "agraph.agent-efficiency/v1" (:schema comparison)))
    (is (= "agraph-improved" (:status comparison)))
    (is (= {:signal "agraph-improved"
            :minSharedCases 2
            :availableMetrics 21
            :improvedMetrics 20
            :regressedMetrics 0
            :unchangedMetrics 1
            :unavailableMetrics 4}
           (:summary comparison)))
    (is (= {:sameSuite true
            :sameCases true
            :sharedCases 2
            :sharedCaseIds ["case-1" "case-2"]
            :shellOnlyCaseIds []
            :agraphOnlyCaseIds []
            :warnings []}
           (:comparability comparison)))
    (is (= {:shellOnly 0.5
            :agraph 0.75
            :delta 0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:fileRecallAt10 deltas-by-key)
                        [:shellOnly :agraph :delta :effect :result])))
    (is (= {:shellOnly 0.5
            :agraph 0.25
            :delta -0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:noiseRatioAt20 deltas-by-key)
                        [:shellOnly :agraph :delta :effect :result])))
    (is (= {:shellOnly 4.0
            :agraph 1.0
            :delta -3.0
            :effect 3.0
            :result "improved"}
           (select-keys (:searchCommandCount deltas-by-key)
                        [:shellOnly :agraph :delta :effect :result])))
    (is (= {:signal "agraph-improved"
            :minSharedCases 2
            :availableMetrics 4
            :improvedMetrics 4
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 0}
           (get-in categories-by-key ["command-telemetry" :summary])))
    (is (= {:signal "agraph-improved"
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
                           :agraphImprovedWithoutRegressions true
                           :problemClassCoverage false
                           :architectureClassCoverage false
                           :evidenceMetrics true
                           :commandTelemetry true
                           :shellLaneClaimReady true
                           :agraphLaneClaimReady true}
            :laneClaimReadiness {:shellOnly nil
                                 :agraph nil}
            :optionalTelemetry {:tokenCostMetrics false}
            :warnings ["Shell-only report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim."
                       "AGraph report has no claimReadiness field; regenerate the lane report before using this comparison for a broad claim."
                       "No shared problem-class tags; do not use this report for broad efficiency claims."
                       "No shared architecture-class tags; do not use this report to claim representative architecture-task gains."
                       "Problem-class measurement summaries are unavailable; regenerate agent reports before claiming broad efficiency."]
            :notes ["Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."]}
           (:claimReadiness comparison)))
    (is (= ["case-1" "case-2"]
           (mapv :caseId (:caseDeltas comparison))))
    (is (= {"shell-only" 2}
           (get-in comparison [:shellOnly :modes])))
    (is (= {"agraph" 2}
           (get-in comparison [:agraph :modes])))))

(deftest compares-token-cost-telemetry-when-available
  (let [shell (assoc-in shell-report
                        [:agentDiagnostics :tokenTelemetry]
                        {:totalTokens 12000
                         :inputTokens 9000
                         :outputTokens 3000
                         :costUsd 0.6})
        agraph (assoc-in agraph-report
                         [:agentDiagnostics :tokenTelemetry]
                         {:totalTokens 7000
                          :inputTokens 5500
                          :outputTokens 1500
                          :costUsd 0.35})
        comparison (agent-efficiency/compare-reports shell agraph)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))
        categories-by-key (into {} (map (juxt :category identity)) (:byCategory comparison))]
    (is (= {:shellOnly 12000.0
            :agraph 7000.0
            :delta -5000.0
            :effect 5000.0
            :result "improved"}
           (select-keys (:totalTokens deltas-by-key)
                        [:shellOnly :agraph :delta :effect :result])))
    (is (= {:shellOnly 0.6
            :agraph 0.35
            :delta -0.25
            :effect 0.25
            :result "improved"}
           (select-keys (:costUsd deltas-by-key)
                        [:shellOnly :agraph :delta :effect :result])))
    (is (= {:signal "agraph-improved"
            :minSharedCases 2
            :availableMetrics 4
            :improvedMetrics 4
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 0}
           (get-in categories-by-key ["token-cost" :summary])))))

(deftest compares-shell-only-and-agraph-by-tag-groups
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
        agraph (assoc agraph-report
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
        comparison (agent-efficiency/compare-reports shell agraph)
        groups-by-tag (into {} (map (juxt :tag identity)) (get-in comparison
                                                                  [:byTag :groups]))]
    (is (= {:sameTags true
            :sharedTags 2
            :sharedTagKeys ["problem-cross-file" "problem-localization"]
            :shellOnlyTagKeys []
            :agraphOnlyTagKeys []
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
    (is (= "agraph-improved"
           (get-in groups-by-tag ["problem-localization" :summary :signal])))
    (is (= "mixed"
           (get-in groups-by-tag ["problem-cross-file" :summary :signal])))
    (is (= {:shellOnly 0.5
            :agraph 1.0
            :delta 0.5
            :effect 0.5
            :result "improved"}
           (->> (get-in groups-by-tag ["problem-localization" :deltas])
                (filter #(= :fileRecallAt10 (:key %)))
                first
                (#(select-keys % [:shellOnly :agraph :delta :effect :result])))))))

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
        agraph (-> agraph-report
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
        comparison (agent-efficiency/compare-reports shell agraph)]
    (is (= {:sharedTagKeys ["architecture-dependency-flow" "problem-architecture"]
            :problemClassTags ["problem-architecture"]
            :architectureClassTags ["architecture-dependency-flow" "problem-architecture"]
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
    (is (= {:status "supported"
            :broadEfficiencyClaimSupported true
            :sharedCases 2
            :minSharedCases 2
            :requirements {:sameSuite true
                           :sameCases true
                           :enoughSharedCases true
                           :agraphImprovedWithoutRegressions true
                           :problemClassCoverage true
                           :architectureClassCoverage true
                           :evidenceMetrics true
                           :commandTelemetry true
                           :shellLaneClaimReady true
                           :agraphLaneClaimReady true}
            :laneClaimReadiness {:shellOnly (:claimReadiness shell)
                                 :agraph (:claimReadiness agraph)}
            :optionalTelemetry {:tokenCostMetrics false}
            :warnings []
            :notes ["Token/cost telemetry is unavailable; token and cost deltas are not part of this claim."]}
           (:claimReadiness comparison)))))

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
        agraph (-> agraph-report
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
        comparison (agent-efficiency/compare-reports shell agraph)]
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
        agraph (-> agraph-report
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
        comparison (agent-efficiency/compare-reports shell agraph)]
    (is (= false
           (get-in comparison
                   [:claimReadiness :broadEfficiencyClaimSupported])))
    (is (= false
           (get-in comparison
                   [:claimReadiness :requirements :shellLaneClaimReady])))
    (is (= true
           (get-in comparison
                   [:claimReadiness :requirements :agraphLaneClaimReady])))
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
        agraph (assoc-in shell [:timings :elapsedMs] 1025)
        comparison (agent-efficiency/compare-reports shell
                                                     agraph
                                                     {:min-shared-cases 1})
        elapsed-delta (->> (:deltas comparison)
                           (filter #(= :elapsedMs (:key %)))
                           first)]
    (is (= "unchanged" (:status comparison)))
    (is (= {:shellOnly 1000.0
            :agraph 1025.0
            :delta 25.0
            :rawEffect -25.0
            :effect 0.0
            :tolerance 50.0
            :result "unchanged"}
           (select-keys elapsed-delta
                        [:shellOnly
                         :agraph
                         :delta
                         :rawEffect
                         :effect
                         :tolerance
                         :result])))))

(deftest reports-comparability-warnings-for-different-case-sets
  (let [agraph-partial (assoc agraph-report
                              :results [(first (:results agraph-report))])
        comparison (agent-efficiency/compare-reports shell-report agraph-partial)]
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
        agraph (assoc-in shell [:scores :fileRecallAt10] 1.0)
        comparison (agent-efficiency/compare-reports shell agraph)]
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
        agraph (update agraph-report :timings dissoc :elapsedMs)
        comparison (agent-efficiency/compare-reports shell agraph)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= {:signal "agraph-improved"
            :minSharedCases 2
            :availableMetrics 19
            :improvedMetrics 18
            :regressedMetrics 0
            :unchangedMetrics 1
            :unavailableMetrics 6}
           (:summary comparison)))
    (is (= {:shellOnly nil
            :agraph 0.5
            :available false
            :result "unavailable"}
           (select-keys (:evidenceCitationRate deltas-by-key)
                        [:shellOnly :agraph :available :result])))
    (is (= {:shellOnly 1000.0
            :agraph nil
            :available false
            :result "unavailable"}
           (select-keys (:elapsedMs deltas-by-key)
                        [:shellOnly :agraph :available :result])))
    (is (not (contains? (:elapsedMs deltas-by-key) :delta)))
    (is (not (contains? (:evidenceCitationRate deltas-by-key) :effect)))))

(deftest reports-all-missing-metrics-as-unavailable-signal
  (let [strip-metrics (fn [report]
                        (-> report
                            (dissoc :scores
                                    :localizationDiagnostics
                                    :agentDiagnostics
                                    :timings)
                            (assoc :results (mapv #(dissoc % :scores)
                                                  (:results report)))))
        comparison (agent-efficiency/compare-reports
                    (strip-metrics shell-report)
                    (strip-metrics agraph-report))]
    (is (= "metrics-unavailable" (:status comparison)))
    (is (= {:signal "metrics-unavailable"
            :minSharedCases 2
            :availableMetrics 0
            :improvedMetrics 0
            :regressedMetrics 0
            :unchangedMetrics 0
            :unavailableMetrics 25}
           (:summary comparison)))
    (is (every? #(= "unavailable" (:result %))
                (:deltas comparison)))))

(deftest writes-comparison-from-agent-report-files
  (let [root (temp-dir "agraph-agent-efficiency")
        shell-path (spit-json! root "shell/agent-report.json" shell-report)
        agraph-path (spit-json! root "agraph/agent-report.json" agraph-report)
        out-path (.getPath (io/file root "summary.json"))
        comparison (agent-efficiency/compare-report-files!
                    shell-path
                    agraph-path
                    {:out out-path})
        written (read-json out-path)]
    (is (= "agraph-improved" (:status comparison)))
    (is (= "agraph-improved" (:status written)))
    (is (= shell-path (get-in written [:inputs :shellReport])))
    (is (= agraph-path (get-in written [:inputs :agraphReport])))))

(deftest plain-output-warns-when-problem-class-coverage-is-missing
  (let [root (temp-dir "agraph-agent-efficiency-plain")
        shell-path (spit-json! root "shell/agent-report.json" shell-report)
        agraph-path (spit-json! root "agraph/agent-report.json" agraph-report)
        out (with-out-str
              (agent-efficiency/-main shell-path agraph-path))]
    (is (.contains out "Category signals:"))
    (is (.contains out "- command-telemetry: agraph-improved"))
    (is (.contains out "Claim readiness: not-supported"))
    (is (.contains out "Claim readiness warnings:"))
    (is (.contains out "No shared problem-class tags"))
    (is (.contains out "No shared architecture-class tags"))
    (is (.contains out "Claim readiness notes:"))))
