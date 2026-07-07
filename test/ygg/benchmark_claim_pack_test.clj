(ns ygg.benchmark-claim-pack-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-claim-pack :as benchmark-claim-pack]
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

(defn- source-kind-quality
  [{:keys [measured underpowered below-floor]}]
  {:minimumCasesForSourceKindQuality 2
   :minimumMeasuredSourceKindQualityGroupsForBroadClaim 2
   :measuredSourceKindKeys measured
   :underpoweredSourceKindKeys underpowered
   :lowQualitySourceKindKeys below-floor
   :lowQualitySourceKinds []
   :rows (vec
          (concat
           (map (fn [kind]
                  {:kind kind
                   :runs 2
                   :cases 2
                   :caseIds ["case-1" "case-2"]
                   :fileRecallAt10 0.9
                   :meanReciprocalRankFile 0.75
                   :status "meets-floor"})
                measured)
           (map (fn [kind]
                  {:kind kind
                   :runs 1
                   :cases 1
                   :caseIds ["case-1"]
                   :fileRecallAt10 0.9
                   :meanReciprocalRankFile 0.75
                   :status "insufficient-cases"})
                underpowered)
           (map (fn [kind]
                  {:kind kind
                   :runs 2
                   :cases 2
                   :caseIds ["case-1" "case-2"]
                   :fileRecallAt10 0.5
                   :meanReciprocalRankFile 0.25
                   :status "below-floor"})
                below-floor)))})

(defn- docs-claim-readiness
  [{:keys [status supported? doc-cases source-kinds]}]
  {:status status
   :docsHandlingClaimSupported supported?
   :docSourceKindCases doc-cases
   :sourceKindKeys source-kinds
   :requirements {:docSourceKindCoverage supported?}
   :warnings (if supported?
               []
               ["Doc source-kind coverage is under the docs claim floor."])})

(defn- report
  [{:keys [mode recall tokens context-artifacts source-kind-quality
           docs-claim-readiness]}]
  (cond-> {:schema benchmark/agent-report-schema
           :suite-id "suite"
           :cases 2
           :completed 2
           :runs 2
           :missing []
           :scores {:fileRecallAt10 recall
                    :meanReciprocalRankFile recall
                    :noiseRatioAt20 (- 1.0 recall)
                    :evidenceCitationRate recall
                    :pathEvidenceCitationRate recall
                    :decisionRecall recall
                    :decisionPrecision recall
                    :decisionF1 recall
                    :decisionEvidenceCitationRate recall}
           :agentDiagnostics {:commandTelemetry {:commandCount 2
                                                 :searchCommandCount 1
                                                 :fileReadCommandCount 1
                                                 :shellCommandCount 0
                                                 :yggCommandCount 0}
                              :tokenTelemetry tokens
                              :contextArtifactTelemetry context-artifacts}
           :localizationDiagnostics {:missedRuns 0
                                     :rankedOutsideTop5Runs 0
                                     :rankedOutsideTop10Runs 0
                                     :missedButPresentInContextRuns 0
                                     :missedButPresentInContextCaseIds []
                                     :missedAndAbsentFromContextRuns 0
                                     :missedAndAbsentFromContextCaseIds []
                                     :contextRankMissingRuns 0
                                     :contextRankMissingCaseIds []}
           :decisionDiagnostics {:configuredRuns 2
                                 :gapRuns 0
                                 :gapCaseIds []}
           :claimReadiness {:status "supported"
                            :broadArchitectureClaimSupported true}
           :results (mapv (fn [case-id]
                            {:case-id case-id
                             :tags [:problem-architecture
                                    :architecture-dependency-flow]
                             :agent {:agentId "codex"
                                     :mode mode
                                     :tokenUsage tokens}
                             :scores {:fileRecallAt10 recall
                                      :meanReciprocalRankFile recall
                                      :noiseRatioAt20 (- 1.0 recall)
                                      :evidenceCitationRate recall
                                      :pathEvidenceCitationRate recall
                                      :decisionRecall recall
                                      :decisionPrecision recall
                                      :decisionF1 recall
                                      :decisionEvidenceCitationRate recall}})
                          ["case-1" "case-2"])
           :byTag [{:key "problem-architecture"
                    :cases 2
                    :runs 2
                    :scores {:fileRecallAt10 recall
                             :noiseRatioAt20 (- 1.0 recall)}}
                   {:key "architecture-dependency-flow"
                    :cases 2
                    :runs 2
                    :scores {:fileRecallAt10 recall
                             :noiseRatioAt20 (- 1.0 recall)}}]
           :problemClasses {:minimumCasesForClassClaim 2
                            :classes [{:key "problem-architecture"
                                       :cases 2
                                       :runs 2
                                       :claimStatus "measured"}]
                            :architectureClasses [{:key "architecture-dependency-flow"
                                                   :cases 2
                                                   :runs 2
                                                   :claimStatus "measured"}]}}
    source-kind-quality
    (assoc :sourceKindQuality source-kind-quality)

    docs-claim-readiness
    (assoc :docsClaimReadiness docs-claim-readiness)))

(deftest writes-benchmark-claim-pack
  (let [root (temp-dir "ygg-claim-pack")
        shell-path (spit-json! root
                               "shell/agent-report.json"
                               (report {:mode "shell-only"
                                        :recall 0.5
                                        :tokens {:inputTokens 1000
                                                 :outputTokens 500
                                                 :totalTokens 1500
                                                 :costUsd 0.1}
                                        :source-kind-quality
                                        (source-kind-quality
                                         {:measured ["doc"]
                                          :underpowered ["ci"]
                                          :below-floor ["python"]})
                                        :docs-claim-readiness
                                        (docs-claim-readiness
                                         {:status "not-supported"
                                          :supported? false
                                          :doc-cases 1
                                          :source-kinds ["doc"]})}))
        ygg-path (spit-json! root
                             "ygg/agent-report.json"
                             (-> (report {:mode "ygg"
                                          :recall 0.75
                                          :tokens {:inputTokens 800
                                                   :outputTokens 300
                                                   :totalTokens 1100
                                                   :costUsd 0.07}
                                          :context-artifacts
                                          {:runs 2
                                           :caseIds ["case-1" "case-2"]
                                           :totals {:compactHintsBytes 4000
                                                    :fullHintsBytes 16000
                                                    :hintSavingsBytes 12000
                                                    :frontloadBytes 6000
                                                    :expansionBytes 40000}
                                           :hintSavingsRatio 0.75}})
                                 (assoc
                                  :sourceKindQuality
                                  (source-kind-quality
                                   {:measured ["doc" "javascript"]
                                    :underpowered []
                                    :below-floor []})
                                  :docsClaimReadiness
                                  (docs-claim-readiness
                                   {:status "supported"
                                    :supported? true
                                    :doc-cases 4
                                    :source-kinds ["ci" "doc"]}))
                                 (assoc :decisionDiagnostics
                                        {:configuredRuns 2
                                         :gapRuns 1
                                         :gapCaseIds ["case-1"]
                                         :choiceGaps [{:kind "missed"
                                                       :id "candidate"
                                                       :runs 1
                                                       :caseIds ["case-1"]}]})))
        pack (benchmark-claim-pack/write-claim-pack!
              {:id "suite"}
              {:out root
               :shell-report shell-path
               :ygg-report ygg-path})
        artifacts (:artifacts pack)
        markdown (slurp (:claimPackMarkdownPath artifacts))]
    (is (= benchmark/claim-pack-schema (:schema pack)))
    (is (= "inconclusive" (get-in pack [:summary :verdict])))
    (is (= "mixed" (get-in pack [:summary :status])))
    (is (= "not-supported"
           (get-in pack [:summary :claimReadinessDetails :status])))
    (is (= false
           (get-in pack
                   [:summary
                    :claimReadinessDetails
                    :broadEfficiencyClaimSupported])))
    (is (= [:yggImprovedWithoutRegressions
            :problemClassCoverage
            :architectureClassCoverage]
           (get-in pack [:summary :failedRequirements])))
    (is (= [:yggImprovedWithoutRegressions]
           (get-in pack
                   [:summary :measuredSliceClaim :failedRequirements])))
    (is (= "better-quality-lower-token-cost"
           (get-in pack [:summary :qualityCostTradeoff :status])))
    (is (= ["doc" "javascript"]
           (get-in pack
                   [:summary
                    :sourceKindQualityByLane
                    :ygg
                    :measuredSourceKindKeys])))
    (is (= ["ci"]
           (get-in pack
                   [:summary
                    :sourceKindQualityByLane
                    :shellOnly
                    :underpoweredSourceKindKeys])))
    (is (= true
           (get-in pack
                   [:summary
                    :docsClaimReadinessByLane
                    :ygg
                    :docsHandlingClaimSupported])))
    (is (= false
           (get-in pack
                   [:summary
                    :docsClaimReadinessByLane
                    :shellOnly
                    :docsHandlingClaimSupported])))
    (is (= ["ci"]
           (get-in pack
                   [:lanes
                    :shellOnly
                    :sourceKindQuality
                    :underpoweredSourceKindKeys])))
    (is (= "supported"
           (get-in pack [:lanes :ygg :docsClaimReadiness :status])))
    (is (= true
           (get-in pack [:lanes :shellOnly :tokenTelemetry :telemetryPresent])))
    (is (= 2
           (get-in pack [:lanes :ygg :tokenTelemetry :tokenUsageRuns])))
    (is (= 12000
           (get-in pack
                   [:lanes
                    :ygg
                    :contextArtifactTelemetry
                    :totals
                    :hintSavingsBytes])))
    (is (= 0.75
           (get-in pack
                   [:summary
                    :contextArtifacts
                    :progressiveDisclosure
                    :hintSavingsRatio])))
    (is (= ["decision-quality-gap"]
           (mapv :lane (get-in pack [:systemImprovement :lanes]))))
    (doseq [path (vals artifacts)]
      (is (.isFile (io/file path))))
    (is (str/includes? markdown "# Yggdrasil Benchmark Claim Pack"))
    (is (str/includes? markdown "- Verdict: inconclusive"))
    (is (str/includes? markdown "## Claim Readiness Warnings"))
    (is (str/includes?
         markdown
         "Aggregate metrics do not show an unregressed Yggdrasil improvement"))
    (is (str/includes? markdown "## Source-Kind Quality"))
    (is (str/includes?
         markdown
         "- shellOnly source-kind quality: min-cases 2, min-measured 2, measured doc, underpowered ci, below-floor python"))
    (is (str/includes?
         markdown
         "- ygg source-kind quality: min-cases 2, min-measured 2, measured doc,javascript, underpowered none, below-floor none"))
    (is (str/includes? markdown "## Docs Claim Readiness"))
    (is (str/includes?
         markdown
         "- shellOnly docs claim readiness: not-supported, supported false, doc cases 1, source kinds doc"))
    (is (str/includes?
         markdown
         "- ygg docs claim readiness: supported, supported true, doc cases 4, source kinds ci,doc"))
    (is (str/includes? markdown "- Yggdrasil context artifact telemetry: present"))
    (is (str/includes? markdown "decision-quality-gap"))))
