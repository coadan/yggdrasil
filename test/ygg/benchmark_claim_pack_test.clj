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

(defn- report
  [{:keys [mode recall tokens context-artifacts]}]
  {:schema benchmark/agent-report-schema
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
                                           :claimStatus "measured"}]}})

(deftest writes-benchmark-claim-pack
  (let [root (temp-dir "ygg-claim-pack")
        shell-path (spit-json! root
                               "shell/agent-report.json"
                               (report {:mode "shell-only"
                                        :recall 0.5
                                        :tokens {:inputTokens 1000
                                                 :outputTokens 500
                                                 :totalTokens 1500
                                                 :costUsd 0.1}}))
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
    (is (= "better-quality-lower-token-cost"
           (get-in pack [:summary :qualityCostTradeoff :status])))
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
    (is (str/includes? markdown "- Yggdrasil context artifact telemetry: present"))
    (is (str/includes? markdown "decision-quality-gap"))))
