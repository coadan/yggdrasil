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
           elapsed failed running case-ids]}]
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
                      :warningRuns warnings}
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
           :elapsed 900
           :failed 0
           :running 0
           :case-ids ["case-1" "case-2"]}))

(deftest compares-shell-only-and-agraph-agent-reports
  (let [comparison (agent-efficiency/compare-reports shell-report agraph-report)
        deltas-by-key (into {} (map (juxt :key identity)) (:deltas comparison))]
    (is (= "agraph.agent-efficiency/v1" (:schema comparison)))
    (is (= "agraph-improved" (:status comparison)))
    (is (= {:signal "agraph-improved"
            :improvedMetrics 16
            :regressedMetrics 0
            :unchangedMetrics 1}
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
    (is (= ["case-1" "case-2"]
           (mapv :caseId (:caseDeltas comparison))))
    (is (= {"shell-only" 2}
           (get-in comparison [:shellOnly :modes])))
    (is (= {"agraph" 2}
           (get-in comparison [:agraph :modes])))))

(deftest reports-comparability-warnings-for-different-case-sets
  (let [agraph-partial (assoc agraph-report
                              :results [(first (:results agraph-report))])
        comparison (agent-efficiency/compare-reports shell-report agraph-partial)]
    (is (= false (get-in comparison [:comparability :sameCases])))
    (is (= ["case-2"]
           (get-in comparison [:comparability :shellOnlyCaseIds])))
    (is (= ["Reports do not contain the same completed case ids."]
           (get-in comparison [:comparability :warnings])))))

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
