(ns agraph.benchmark-test
  (:require [agraph.benchmark :as benchmark]
            [agraph.extract :as extract]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- sh!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed."
                      {:args args
                       :exit exit
                       :out out
                       :err err})))
    out))

(defn- git!
  [repo & args]
  (apply sh! "git" "-C" repo args))

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(defn- spit-json!
  [root path value]
  (spit-file! root path (json/write-json-str value {:indent-str "  "})))

(defn- commit!
  [repo message]
  (git! repo "add" ".")
  (git! repo "commit" "-m" message)
  (str/trim (git! repo "rev-parse" "HEAD")))

(deftest selects-one-or-more-benchmark-cases
  (let [suite {:id "suite"
               :cases [{:id "case-1"}
                       {:id "case-2"}
                       {:id "case-3"}]}]
    (is (= ["case-1" "case-2" "case-3"]
           (mapv :id (benchmark/selected-cases suite nil))))
    (is (= ["case-2"]
           (mapv :id (benchmark/selected-cases suite "case-2"))))
    (is (= ["case-1" "case-3"]
           (mapv :id (benchmark/selected-cases suite ["case-3" "case-1"]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Benchmark case not found"
                          (benchmark/selected-cases suite ["case-1" "missing"])))))

(deftest scores-file-localization
  (let [result {:groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                              :unsupportedGroundTruthFiles []}
                :agraph {:topFiles [{:path "src/other.clj" :rank 1}
                                    {:path "src/app.clj" :rank 2}
                                    {:path "src/more.clj" :rank 3}
                                    {:path "src/db.clj" :rank 4}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 0.5 (:meanReciprocalRankFile scores)))
    (is (= 0.5 (:noiseRatioAt20 scores)))
    (is (= 0 (:unsupportedGroundTruthFiles scores)))))

(deftest scores-agent-evidence-citation-rate
  (let [result {:groundTruth {:changedFiles ["src/app.clj"]
                              :unsupportedGroundTruthFiles []}
                :agraph {:topFiles [{:path "src/app.clj"
                                     :rank 1
                                     :evidence ["rg broken"]}
                                    {:path "src/other.clj"
                                     :rank 2
                                     :evidence []}
                                    {:path "src/more.clj"
                                     :rank 3}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= (/ 1.0 3.0) (:evidenceCitationRate scores)))))

(deftest scores-only-base-visible-ground-truth-files
  (let [result {:groundTruth {:changedFiles [".chloggen/fix.yaml"
                                             "src/app.clj"
                                             "src/app_test.clj"]
                              :unsupportedGroundTruthFiles [{:path ".chloggen/fix.yaml"
                                                             :reason "missing-at-base"}]}
                :agraph {:topFiles [{:path "src/app.clj" :rank 1}
                                    {:path "src/app_test.clj" :rank 2}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 1.0 (:meanReciprocalRankFile scores)))
    (is (= 0.0 (:noiseRatioAt20 scores)))
    (is (= 3 (:changedFiles scores)))
    (is (= 2 (:scoreableChangedFiles scores)))
    (is (= 1 (:unsupportedGroundTruthFiles scores)))))

(deftest scores-explicit-scoreable-ground-truth-files
  (let [result {:groundTruth {:changedFiles ["CHANGELOG.md"
                                             "src/app.js"
                                             "test/app.test.js"]
                              :localizationFiles ["CHANGELOG.md"
                                                  "src/app.js"
                                                  "test/app.test.js"]
                              :scoreableFiles ["src/app.js"
                                               "test/app.test.js"]
                              :coverageExcludedFiles [{:path "CHANGELOG.md"
                                                       :kind "doc"}]
                              :unsupportedGroundTruthFiles []}
                :agraph {:topFiles [{:path "src/app.js" :rank 1}
                                    {:path "test/app.test.js" :rank 2}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 2 (:scoreableChangedFiles scores)))
    (is (= 1 (:coverageExcludedGroundTruthFiles scores)))))

(deftest scores-explicit-localization-files-when-present
  (let [result {:groundTruth {:changedFiles ["docs/release-notes.md"
                                             "src/app.clj"
                                             "test/app_test.clj"]
                              :localizationFiles ["src/app.clj"
                                                  "test/app_test.clj"]
                              :unsupportedGroundTruthFiles []}
                :agraph {:topFiles [{:path "docs/release-notes.md" :rank 1}
                                    {:path "src/app.clj" :rank 2}
                                    {:path "test/app_test.clj" :rank 3}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 0.5 (:meanReciprocalRankFile scores)))
    (is (= 3 (:changedFiles scores)))
    (is (= 2 (:localizationFiles scores)))
    (is (= 2 (:scoreableChangedFiles scores)))))

(deftest matches-graph-expectation-rows-with-explicit-fields
  (let [evidence [{:kind :auth-reference
                   :path "config/app.yml"
                   :auth-context :bearer}]
        chunks [{:kind :code-definition
                 :path "scripts/nvm.sh"
                 :definition-kind :function
                 :label "nvm_remote_version"}]
        edges [{:relation :calls-external-api
                :source-id "src/app.clj"
                :target-id "external-api:auth.example.test"}]
        expected (#'benchmark/expected-row-results
                  evidence
                  [{:kind "auth-reference"
                    :path "config/app.yml"
                    :authContext "bearer"}]
                  identity)
        unsupported (#'benchmark/expected-row-results
                     evidence
                     [{:semantic-label "auth config"}]
                     identity)
        expected-chunk (#'benchmark/expected-row-results
                        chunks
                        [{:kind "code-definition"
                          :path "scripts/nvm.sh"
                          :definitionKind "function"
                          :label "nvm_remote_version"}]
                        identity)
        forbidden (#'benchmark/forbidden-row-results
                   edges
                   [:calls-external-api]
                   identity)]
    (is (= true (get-in expected [0 :found?])))
    (is (= {:auth-context "bearer"
            :kind "auth-reference"
            :path "config/app.yml"}
           (get-in expected [0 :expectation])))
    (is (= false (get-in unsupported [0 :found?])))
    (is (= true (get-in expected-chunk [0 :found?])))
    (is (= {:definition-kind "function"
            :kind "code-definition"
            :label "nvm_remote_version"
            :path "scripts/nvm.sh"}
           (get-in expected-chunk [0 :expectation])))
    (is (= true (get-in forbidden [0 :violated?])))))

(deftest evaluates-source-chunk-graph-expectations
  (let [prepared {:project-id "fixture"
                  :expectations {:nodes [{:kind :web-framework-plugin
                                          :path "site/astro.config.ts"
                                          :label "astro/config"}]
                                 :chunks [{:kind :code-definition
                                           :path "scripts/nvm.sh"
                                           :definitionKind :function
                                           :label "nvm_remote_version"}]
                                 :forbidden-nodes [{:kind :web-framework-plugin
                                                    :path "site/astro.config.ts"
                                                    :label "missing-plugin"}]
                                 :forbidden-chunks [{:kind :code-definition
                                                     :path "scripts/nvm.sh"
                                                     :label "legacy_function"}]}}
        node {:xt/id "node:astro-plugin"
              :kind :web-framework-plugin
              :path "site/astro.config.ts"
              :label "astro/config"
              :source-line 1
              :project-id "fixture"}
        chunk {:xt/id "chunk:shell-function"
               :kind :code-definition
               :path "scripts/nvm.sh"
               :definition-kind :function
               :label "nvm_remote_version"
               :source-line 12
               :end-line 18
               :project-id "fixture"}]
    (with-redefs [store/rows-by-field
                  (fn [_ table _field _value]
                    (cond
                      (= table (:chunks store/tables)) [chunk]
                      (= table (:nodes store/tables)) [node]
                      (= table (:system-evidence store/tables)) []
                      (= table (:system-edges store/tables)) []
                      :else []))]
      (let [result (#'benchmark/evaluate-graph-expectations nil prepared)]
        (is (= benchmark/graph-expectations-schema (:schema result)))
        (is (= "passed" (:status result)))
        (is (= {:expectedEvidence 0
                :foundEvidence 0
                :missingEvidence 0
                :expectedNodes 1
                :foundNodes 1
                :missingNodes 0
                :expectedChunks 1
                :foundChunks 1
                :missingChunks 0
                :expectedEdges 0
                :foundEdges 0
                :missingEdges 0
                :forbiddenNodes 1
                :forbiddenNodeViolations 0
                :forbiddenChunks 1
                :forbiddenChunkViolations 0
                :forbiddenEdges 0
                :forbiddenEdgeViolations 0}
               (:summary result)))
        (is (= true (get-in result [:expectedNodes 0 :found?])))
        (is (= true (get-in result [:expectedChunks 0 :found?])))
        (is (= false (get-in result [:forbiddenNodes 0 :violated?])))
        (is (= false (get-in result [:forbiddenChunks 0 :violated?])))
        (is (= [{:xt/id "node:astro-plugin"
                 :kind :web-framework-plugin
                 :path "site/astro.config.ts"
                 :label "astro/config"
                 :source-line 1}]
               (get-in result [:expectedNodes 0 :matches])))
        (is (= [{:xt/id "chunk:shell-function"
                 :kind :code-definition
                 :path "scripts/nvm.sh"
                 :definition-kind :function
                 :label "nvm_remote_version"
                 :source-line 12
                 :end-line 18}]
               (get-in result [:expectedChunks 0 :matches])))))))

(deftest reports-agent-score-artifacts
  (let [out (temp-dir "agraph-agent-report")
        suite {:id "suite"
               :cases [{:id "case-1"
                        :tags [:runtime-config :auth]}
                       {:id "case-2"
                        :tags [:dependency]}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :tags ["auth" "runtime-config"]
                 :parserWorker {:mode "all"
                                :source "option"}
                 :expectations {:evidence [{:kind "auth-reference"
                                            :path "src/app.clj"}]
                                :forbidden-edges ["shares-config"]}
                 :graphExpectations {:schema benchmark/graph-expectations-schema
                                     :status "failed"
                                     :summary {:expectedEvidence 1
                                               :foundEvidence 1
                                               :missingEvidence 0
                                               :expectedEdges 0
                                               :foundEdges 0
                                               :missingEdges 0
                                               :forbiddenEdges 1
                                               :forbiddenEdgeViolations 1}}
                 :agent {:agentId "codex"
                         :mode "agraph"
                         :topFiles [{:path "src/other.clj"
                                     :rank 1
                                     :metrics {:supportCount 2
                                               :docCount 1}}
                                    {:path "src/app.clj"
                                     :rank 7}]
                         :warnings ["agent result suspectedFiles row 1 missing evidence"]
                         :selection {:rawCandidateFiles 3
                                     :candidateFiles 2
                                     :coverageFilteredCandidateFiles 1
                                     :limit 20
                                     :coverageSourceKinds ["code"]}}
                 :coverage {:declaredSourceKinds ["code" "python"]
                            :scoreableSourceKinds ["code"]
                            :scoreableFilesByKind [{:kind "code"
                                                    :files 2}]
                            :missingDeclaredSourceKinds ["python"]}
                 :inputHints {:hinted true
                              :mentionedChangedFiles ["src/app.clj"]
                              :mentionedChangedFileCount 1
                              :changedFileCount 3}
                 :groundTruth {:changedFiles ["CHANGELOG.md"
                                              "src/app.clj"
                                              "src/missing.clj"]
                               :localizationFiles ["CHANGELOG.md"
                                                   "src/app.clj"
                                                   "src/missing.clj"]
                               :scoreableFiles ["src/app.clj"
                                                "src/missing.clj"]
                               :coverageExcludedFiles [{:path "CHANGELOG.md"
                                                        :kind "doc"}]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :rank 7
                                             :found? true}
                                            {:path "src/missing.clj"
                                             :found? false}]}
                 :contextGroundTruthRanks {:files [{:path "src/app.clj"
                                                    :rank 7
                                                    :found? true}
                                                   {:path "src/missing.clj"
                                                    :rank 12
                                                    :found? true}]
                                           :selection {:rawCandidateFiles 4
                                                       :candidateFiles 3
                                                       :coverageFilteredCandidateFiles 1
                                                       :limit nil
                                                       :coverageSourceKinds ["code"]}}
                 :scores {:fileRecallAt5 1.0
                          :fileRecallAt10 1.0
                          :fileRecallAt20 1.0
                          :meanReciprocalRankFile 1.0
                          :noiseRatioAt20 0.5
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-2.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "baseline"
                         :mode "shell-only"
                         :topFiles []
                         :rawSuspectedFileCount 0}
                 :groundTruth {:changedFiles ["src/app.clj"]
                               :unsupportedGroundTruthFiles []}
                 :groundTruthRanks {:files [{:path "src/app.clj"
                                             :found? false}]}
                 :coverage {:declaredSourceKinds ["code" "python"]
                            :scoreableSourceKinds ["code"]
                            :scoreableFilesByKind [{:kind "code"
                                                    :files 2}]
                            :missingDeclaredSourceKinds ["python"]}
                 :scores {:fileRecallAt5 0.0
                          :fileRecallAt10 0.5
                          :fileRecallAt20 0.5
                          :meanReciprocalRankFile 0.0
                          :noiseRatioAt20 1.0
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (spit-json! out
                "suite/cases/case-1/progress.json"
                {:schema "agraph.benchmark.case-progress/v1"
                 :suite-id "suite"
                 :case-id "case-1"
                 :events [{:stage "index-project"
                           :status "started"
                           :at "2026-06-18T00:00:00Z"}
                          {:stage "index-project"
                           :status "completed"
                           :at "2026-06-18T00:00:02Z"
                           :elapsedMs 2000}
                          {:stage "context-packet"
                           :status "started"
                           :at "2026-06-18T00:00:02Z"}]})
    (spit-json! out
                "suite/cases/case-2/progress.json"
                {:schema "agraph.benchmark.case-progress/v1"
                 :suite-id "suite"
                 :case-id "case-2"
                 :events [{:stage "index-project"
                           :status "started"
                           :at "2026-06-18T00:00:00Z"}
                          {:stage "index-project"
                           :status "failed"
                           :at "2026-06-18T00:00:03Z"
                           :elapsedMs 3000}]})
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :allow-unverified-scores? true})]
      (is (= benchmark/agent-report-schema (:schema report)))
      (is (= 2 (:cases report)))
      (is (= 1 (:completed report)))
      (is (= 2 (:runs report)))
      (is (= ["case-2"] (:missing report)))
      (is (= 0.75 (get-in report [:scores :fileRecallAt10])))
      (is (= 6 (get-in report [:scores :changedFiles])))
      (is (= 4 (get-in report [:scores :scoreableChangedFiles])))
      (is (= [{:mode "all"
               :source "option"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}
              {:mode "unknown"
               :source "missing"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (:parserWorkers report)))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (:inputHints report)))
      (is (= {:emptyResultRuns 1
              :emptyResultCaseIds ["case-1"]
              :zeroCandidateRuns 0
              :zeroCandidateCaseIds []
              :coverageFilteredRuns 1
              :coverageFilteredCaseIds ["case-1"]
              :missingPredictedFileRuns 0
              :missingPredictedFiles 0
              :warningRuns 1
              :warningCaseIds ["case-1"]
              :warnings 1}
             (:agentDiagnostics report)))
      (is (= {:configuredRuns 1
              :passedRuns 0
              :passedCaseIds []
              :failedRuns 1
              :failedCaseIds ["case-1"]
              :notRunRuns 0
              :notRunCaseIds []}
             (:graphExpectationDiagnostics report)))
      (is (= {:runs 2
              :allScoreableFoundRuns 0
              :allScoreableFoundCaseIds []
              :missedRuns 2
              :missedCaseIds ["case-1"]
              :missedButPresentInContextRuns 1
              :missedButPresentInContextCaseIds ["case-1"]
              :missedAndAbsentFromContextRuns 0
              :missedAndAbsentFromContextCaseIds []
              :rankedOutsideTop5Runs 1
              :rankedOutsideTop5CaseIds ["case-1"]
              :rankedOutsideTop10Runs 0
              :rankedOutsideTop10CaseIds []
              :rankedOutsideTop20Runs 0
              :rankedOutsideTop20CaseIds []
              :rankedOutsideTop5BlockingFiles [{:path "src/other.clj"
                                                :occurrences 1
                                                :runs 1
                                                :bestRank 1
                                                :metrics {:supportCount 2
                                                          :docCount 1}}]
              :rankedOutsideTop10BlockingFiles []
              :rankedOutsideTop20BlockingFiles []}
             (:localizationDiagnostics report)))
      (is (= {:currentScoreRuns 0
              :legacyScoreRuns 2
              :legacyScoreCaseIds ["case-1"]
              :staleScoreRuns 0
              :staleScoreCaseIds []
              :unverifiedScoreRuns 2
              :unverifiedScoreCaseIds ["case-1"]}
             (:artifactDiagnostics report)))
      (is (= {:allowUnverifiedScores true
              :matchedRuns 2
              :includedRuns 2
              :excludedRuns 0
              :excludedCaseIds []
              :excludedUnverifiedRuns 2}
             (:artifactPolicy report)))
      (is (= {:declaredSourceKinds ["code" "python"]
              :scoreableSourceKinds ["code"]
              :scoreableFilesByKind [{:kind "code"
                                      :cases 1
                                      :scoreableFiles 2}]
              :missingDeclaredSourceKinds [{:case-id "case-1"
                                            :kind "python"}]}
             (:coverage report)))
      (is (= {:runs 2
              :missingDeclaredSourceKindRuns 2
              :missingDeclaredSourceKindCaseIds ["case-1"]
              :missingDeclaredSourceKinds [{:kind "python"
                                            :runs 2
                                            :cases 1
                                            :caseIds ["case-1"]}]
              :coverageExcludedGroundTruthRuns 1
              :coverageExcludedGroundTruthCaseIds ["case-1"]
              :coverageExcludedGroundTruthFiles 1
              :unsupportedGroundTruthRuns 0
              :unsupportedGroundTruthCaseIds []
              :unsupportedGroundTruthFiles 0}
             (:coverageDiagnostics report)))
      (is (= {:tags ["auth" "dependency" "runtime-config"]
              :casesByTag [{:tag "auth"
                            :cases 1
                            :caseIds ["case-1"]}
                           {:tag "dependency"
                            :cases 1
                            :caseIds ["case-2"]}
                           {:tag "runtime-config"
                            :cases 1
                            :caseIds ["case-1"]}]}
             (:tags report)))
      (is (= {:cases 2
              :runningCases 1
              :failedCases 1
              :elapsedMs 8000
              :stageElapsedMs [{:stage "context-packet"
                                :elapsedMs 3000}
                               {:stage "index-project"
                                :elapsedMs 5000}]
              :slowestCases [{:case-id "case-1"
                              :repo-id nil
                              :status "running"
                              :activeStage "context-packet"
                              :activeElapsedMs 3000
                              :elapsedMs 5000}
                             {:case-id "case-2"
                              :repo-id nil
                              :status "failed"
                              :elapsedMs 3000
                              :failedStage "index-project"}]}
             (:timings report)))
      (is (= "running" (get-in report [:results 0 :progress :status])))
      (is (= {:scoreableFiles ["src/app.clj" "src/missing.clj"]
              :coverageExcludedFiles [{:path "CHANGELOG.md"
                                       :kind "doc"}]
              :unsupportedGroundTruthFiles []
              :ranks [{:path "src/app.clj"
                       :rank 7
                       :found? true}
                      {:path "src/missing.clj"
                       :found? false}]
              :uncitedRankedFiles [{:path "src/other.clj"
                                    :rank 1}
                                   {:path "src/app.clj"
                                    :rank 7}]
              :contextRanks [{:path "src/app.clj"
                              :rank 7
                              :found? true}
                             {:path "src/missing.clj"
                              :rank 12
                              :found? true}]
              :missedFiles [{:path "src/missing.clj"}]
              :missedFilesPresentInContext [{:path "src/missing.clj"
                                             :rank 12}]
              :missedFilesAbsentFromContext []
              :rankedOutsideTop5 [{:path "src/app.clj"
                                   :rank 7}]
              :rankedOutsideTop10 []
              :rankedOutsideTop20 []
              :rankedOutsideTop5Blockers [{:path "src/app.clj"
                                           :rank 7
                                           :blockingFileCount 1
                                           :blockingFiles [{:path "src/other.clj"
                                                            :rank 1
                                                            :metrics {:supportCount 2
                                                                      :docCount 1}}]}]
              :rankedOutsideTop10Blockers []
              :rankedOutsideTop20Blockers []}
             (get-in report [:results 0 :localization])))
      (is (= ["auth" "runtime-config"]
             (get-in report [:results 0 :tags])))
      (is (= {:mode "all"
              :source "option"}
             (get-in report [:results 0 :parserWorker])))
      (is (= "failed"
             (get-in report [:results 0 :graphExpectations :status])))
      (is (= {:rawSuspectedFiles 2
              :rankedFiles 2
              :missingPredictedFiles []
              :warnings ["agent result suspectedFiles row 1 missing evidence"]
              :warningCount 1
              :hasWarnings true
              :emptyResult false
              :noRawSuspectedFiles false
              :selection {:rawCandidateFiles 3
                          :candidateFiles 2
                          :coverageFilteredCandidateFiles 1
                          :limit 20
                          :coverageSourceKinds ["code"]}
              :zeroCandidateFiles false
              :coverageFilteredCandidateFiles 1}
             (get-in report [:results 0 :agentOutput])))
      (is (= "legacy"
             (get-in report [:results 0 :artifact :fingerprintStatus])))
      (is (= #{"running" "failed"}
             (set (map :status (:caseProgress report)))))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (get-in (first (:byMode report)) [:inputHints])))
      (is (= {:runs 1
              :missingDeclaredSourceKindRuns 1
              :missingDeclaredSourceKindCaseIds ["case-1"]
              :missingDeclaredSourceKinds [{:kind "python"
                                            :runs 1
                                            :cases 1
                                            :caseIds ["case-1"]}]
              :coverageExcludedGroundTruthRuns 1
              :coverageExcludedGroundTruthCaseIds ["case-1"]
              :coverageExcludedGroundTruthFiles 1
              :unsupportedGroundTruthRuns 0
              :unsupportedGroundTruthCaseIds []
              :unsupportedGroundTruthFiles 0}
             (get-in (first (:byMode report)) [:coverageDiagnostics])))
      (is (= #{"agraph" "shell-only"} (set (map :key (:byMode report)))))
      (is (= ["baseline" "codex"] (mapv :key (:byAgent report))))
      (is (= ["all/option" "unknown/missing"]
             (mapv :key (:byParserWorker report))))
      (is (= 1.0
             (get-in (first (:byParserWorker report))
                     [:scores :fileRecallAt10])))
      (is (= ["auth" "runtime-config"] (mapv :key (:byTag report))))
      (is (= 1
             (get-in (first (:byTag report))
                     [:graphExpectationDiagnostics :failedRuns]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :mode "agraph"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= 1.0 (get-in report [:scores :fileRecallAt10])))
      (is (= ["agraph"] (mapv :key (:byMode report)))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :parser-worker "all"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= [{:mode "all"
               :source "option"
               :runs 1
               :cases 1
               :caseIds ["case-1"]}]
             (:parserWorkers report)))
      (is (= {:mode "all"
              :source "option"}
             (get-in report [:results 0 :parserWorker]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :parser-worker "java"
                                                      :allow-unverified-scores? true})]
      (is (= 0 (:runs report)))
      (is (= 0 (:completed report)))
      (is (= [] (:parserWorkers report)))
      (is (= ["case-1" "case-2"] (:missing report))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"
                                                      :agent-id "baseline"
                                                      :allow-unverified-scores? true})]
      (is (= 1 (:runs report)))
      (is (= "baseline" (get-in report [:results 0 :agent :agentId])))
      (is (= ["baseline"] (mapv :key (:byAgent report))))
      (is (= 0.5 (get-in report [:scores :fileRecallAt10]))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :now "2026-06-18T00:00:05Z"})]
      (is (= 0 (:runs report)))
      (is (= 0 (:completed report)))
      (is (= ["case-1" "case-2"] (:missing report)))
      (is (= {:allowUnverifiedScores false
              :matchedRuns 2
              :includedRuns 0
              :excludedRuns 2
              :excludedCaseIds ["case-1"]
              :excludedUnverifiedRuns 2}
             (:artifactPolicy report))))))

(deftest checks-agent-report-thresholds
  (let [report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 2
                :completed 1
                :runs 1
                :missing ["case-2"]
                :scores {:fileRecallAt5 0.5
                         :fileRecallAt10 0.75
                         :fileRecallAt20 1.0
                         :meanReciprocalRankFile 0.5
                         :noiseRatioAt20 0.75
                         :evidenceCitationRate 0.25
                         :unsupportedGroundTruthFiles 1}
                :parserWorkers [{:mode "all"
                                 :source "option"
                                 :runs 1}
                                {:mode "unknown"
                                 :source "missing"
                                 :runs 1}]
                :inputHints {:inputHintedCases 1}
                :agentDiagnostics {:emptyResultRuns 1
                                   :emptyResultCaseIds ["case-1"]
                                   :warningRuns 1
                                   :warningCaseIds ["case-1"]}
                :artifactDiagnostics {:unverifiedScoreRuns 1
                                      :unverifiedScoreCaseIds ["case-1"]}
                :coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                      :missingDeclaredSourceKindCaseIds ["case-1"]
                                      :missingDeclaredSourceKinds [{:kind "python"
                                                                    :runs 1
                                                                    :cases 1
                                                                    :caseIds ["case-1"]}]}
                :graphExpectationDiagnostics {:configuredRuns 1
                                              :passedRuns 0
                                              :passedCaseIds []
                                              :failedRuns 1
                                              :failedCaseIds ["case-1"]
                                              :notRunRuns 0
                                              :notRunCaseIds []}
                :localizationDiagnostics {:missedRuns 1
                                          :missedCaseIds ["case-1"]
                                          :rankedOutsideTop5Runs 1
                                          :rankedOutsideTop5CaseIds ["case-1"]
                                          :rankedOutsideTop10Runs 1
                                          :rankedOutsideTop10CaseIds ["case-1"]
                                          :rankedOutsideTop20Runs 1
                                          :rankedOutsideTop20CaseIds ["case-1"]}
                :caseProgress [{:case-id "case-2"
                                :repo-id "repo"
                                :status "running"
                                :activeStage "context-packet"
                                :activeElapsedMs 1500}]
                :results [{:case-id "case-1"
                           :parserWorker {:mode "all"
                                          :source "option"}
                           :agent {:agentId "codex"
                                   :mode "agraph"}
                           :graphExpectations {:status "failed"
                                               :summary {:expectedEvidence 1
                                                         :foundEvidence 0}}
                           :scores {:fileRecallAt5 0.5
                                    :fileRecallAt10 0.75
                                    :fileRecallAt20 1.0
                                    :meanReciprocalRankFile 0.5
                                    :noiseRatioAt20 0.75
                                    :evidenceCitationRate 0.25}}]}
        failed (benchmark/check-agent-report
                report
                {:min-cases 3
                 :min-runs 2
                 :min-file-recall-at-10 0.9
                 :min-mrr 0.8
                 :max-noise-at-20 0.5
                 :min-evidence-citation-rate 0.9
                 :min-case-file-recall-at-10 0.9
                 :min-case-mrr 0.8
                 :min-case-evidence-citation-rate 0.9
                 :max-case-noise-at-20 0.5
                 :max-input-hinted-cases 0
                 :max-unsupported-ground-truth-files 0
                 :max-empty-result-runs 0
                 :max-warning-runs 0
                 :max-unverified-score-runs 0
                 :max-graph-expectation-failures 0
                 :max-missing-declared-source-kind-runs 0
                 :max-missed-runs 0
                 :max-ranked-outside-top-5-runs 0
                 :max-ranked-outside-top-10-runs 0
                 :max-ranked-outside-top-20-runs 0
                 :max-active-stage-ms 1000
                 :max-parser-worker-profiles 1
                 :require-parser-worker "all"})
        passed (benchmark/check-agent-report
                (assoc report
                       :completed 2
                       :missing []
                       :parserWorkers [{:mode "all"
                                        :source "option"
                                        :runs 1}]
                       :localizationDiagnostics {:missedRuns 1
                                                 :missedCaseIds ["case-1"]
                                                 :rankedOutsideTop5Runs 1
                                                 :rankedOutsideTop5CaseIds ["case-1"]
                                                 :rankedOutsideTop10Runs 1
                                                 :rankedOutsideTop10CaseIds ["case-1"]
                                                 :rankedOutsideTop20Runs 1
                                                 :rankedOutsideTop20CaseIds ["case-1"]})
                {:allow-missing? true
                 :min-cases 2
                 :min-runs 1
                 :min-file-recall-at-10 0.75
                 :min-mrr 0.5
                 :max-noise-at-20 0.75
                 :min-evidence-citation-rate 0.25
                 :min-case-file-recall-at-10 0.75
                 :min-case-mrr 0.5
                 :min-case-evidence-citation-rate 0.25
                 :max-case-noise-at-20 0.75
                 :max-input-hinted-cases 1
                 :max-unsupported-ground-truth-files 1
                 :max-empty-result-runs 1
                 :max-warning-runs 1
                 :max-unverified-score-runs 1
                 :max-graph-expectation-failures 1
                 :max-missing-declared-source-kind-runs 1
                 :max-missed-runs 1
                 :max-ranked-outside-top-5-runs 1
                 :max-ranked-outside-top-10-runs 1
                 :max-ranked-outside-top-20-runs 1
                 :max-active-stage-ms 1500
                 :max-parser-worker-profiles 1
                 :require-parser-worker "all"})]
    (is (= benchmark/agent-check-schema (:schema failed)))
    (is (= "failed" (:status failed)))
    (is (= #{"completed"
             "cases"
             "runs"
             "fileRecallAt10"
             "meanReciprocalRankFile"
             "noiseRatioAt20"
             "evidenceCitationRate"
             "case.fileRecallAt10"
             "case.meanReciprocalRankFile"
             "case.evidenceCitationRate"
             "case.noiseRatioAt20"
             "inputHintedCases"
             "unsupportedGroundTruthFiles"
             "emptyResultRuns"
             "warningRuns"
             "unverifiedScoreRuns"
             "graphExpectationFailures"
             "missingDeclaredSourceKindRuns"
             "case.graphExpectations"
             "missedRuns"
             "rankedOutsideTop5Runs"
             "rankedOutsideTop10Runs"
             "rankedOutsideTop20Runs"
             "parserWorkerProfiles"
             "parserWorker"
             "activeStageElapsedMs"}
           (set (map :metric (:failures failed)))))
    (is (= {:case-id "case-1"
            :agentId "codex"
            :mode "agraph"}
           (select-keys (first (filter #(= "case.fileRecallAt10" (:metric %))
                                       (:failures failed)))
                        [:case-id :agentId :mode])))
    (is (= ["case-1" "case-2"]
           (mapv :case-id (:caseDiagnostics failed))))
    (is (= "failed" (get-in failed [:caseDiagnostics 0 :status])))
    (is (= #{"case.fileRecallAt10"
             "case.meanReciprocalRankFile"
             "case.evidenceCitationRate"
             "case.noiseRatioAt20"
             "case.graphExpectations"}
           (set (map :metric (get-in failed [:caseDiagnostics 0 :failures])))))
    (is (= {:mode "all"
            :source "option"}
           (get-in failed [:caseDiagnostics 0 :parserWorker])))
    (is (= {:fileRecallAt5 0.5
            :fileRecallAt10 0.75
            :fileRecallAt20 1.0
            :meanReciprocalRankFile 0.5
            :noiseRatioAt20 0.75
            :evidenceCitationRate 0.25}
           (get-in failed [:caseDiagnostics 0 :scores])))
    (is (= {:case-id "case-2"
            :status "missing"}
           (select-keys (get-in failed [:caseDiagnostics 1])
                        [:case-id :status])))
    (is (= #{"completed" "activeStageElapsedMs"}
           (set (map :metric (get-in failed [:caseDiagnostics 1 :failures])))))
    (is (= {:requireComplete true
            :allowDuplicateRuns false
            :minCases 3.0
            :minRuns 2.0
            :minFileRecallAt10 0.9
            :minMeanReciprocalRankFile 0.8
            :maxNoiseRatioAt20 0.5
            :minEvidenceCitationRate 0.9
            :minCaseFileRecallAt10 0.9
            :minCaseMeanReciprocalRankFile 0.8
            :minCaseEvidenceCitationRate 0.9
            :maxCaseNoiseRatioAt20 0.5
            :maxInputHintedCases 0.0
            :maxUnsupportedGroundTruthFiles 0.0
            :maxEmptyResultRuns 0.0
            :maxWarningRuns 0.0
            :maxUnverifiedScoreRuns 0.0
            :maxGraphExpectationFailures 0.0
            :maxMissingDeclaredSourceKindRuns 0.0
            :maxMissedRuns 0.0
            :maxRankedOutsideTop5Runs 0.0
            :maxRankedOutsideTop10Runs 0.0
            :maxRankedOutsideTop20Runs 0.0
            :maxActiveStageMs 1000.0
            :maxParserWorkerProfiles 1.0
            :requiredParserWorker "all"}
           (:thresholds failed)))
    (is (= "passed" (:status passed)))
    (is (empty? (:failures passed)))
    (is (= ["passed"] (mapv :status (:caseDiagnostics passed))))
    (is (= false (get-in passed [:thresholds :requireComplete])))
    (is (= false (get-in passed [:thresholds :allowDuplicateRuns])))))

(deftest checks-agent-report-duplicate-runs
  (let [result {:case-id "case-1"
                :agentResultPath "/tmp/run-1.json"
                :agent {:agentId "codex"
                        :mode "agraph"}
                :scores {:fileRecallAt10 1.0
                         :meanReciprocalRankFile 1.0
                         :noiseRatioAt20 0.0}}
        duplicate (assoc result :agentResultPath "/tmp/run-2.json")
        report {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 2
                :missing []
                :scores {:fileRecallAt10 1.0
                         :meanReciprocalRankFile 1.0
                         :noiseRatioAt20 0.0}
                :inputHints {:inputHintedCases 0}
                :results [result duplicate]}
        failed (benchmark/check-agent-report report {})
        passed (benchmark/check-agent-report report {:allow-duplicate-runs? true})]
    (is (= "failed" (:status failed)))
    (is (= [{:metric "duplicateRuns"
             :operator "="
             :expected 1
             :actual 2
             :case-id "case-1"
             :agentId "codex"
             :mode "agraph"
             :agentResultPaths ["/tmp/run-1.json" "/tmp/run-2.json"]
             :message "Multiple agent score artifacts matched one case, agent, and mode."}]
           (:failures failed)))
    (is (= ["failed" "failed"]
           (mapv :status (:caseDiagnostics failed))))
    (is (= ["duplicateRuns"]
           (->> failed
                :caseDiagnostics
                first
                :failures
                (mapv :metric))))
    (is (= "passed" (:status passed)))
    (is (empty? (:failures passed)))
    (is (= true (get-in passed [:thresholds :allowDuplicateRuns])))))

(deftest compares-agent-reports-for-regressions
  (let [baseline {:schema benchmark/agent-report-schema
                  :suite-id "suite"
                  :cases 2
                  :completed 2
                  :runs 2
                  :parserWorkers [{:mode "all"
                                   :source "option"
                                   :runs 2}]
                  :agentDiagnostics {:warningRuns 0}
                  :coverageDiagnostics {:missingDeclaredSourceKindRuns 0
                                        :coverageExcludedGroundTruthFiles 0
                                        :unsupportedGroundTruthFiles 1}
                  :scores {:fileRecallAt5 0.5
                           :fileRecallAt10 0.75
                           :fileRecallAt20 1.0
                           :meanReciprocalRankFile 0.5
                           :noiseRatioAt20 0.75}
                  :results [{:case-id "case-1"
                             :scores {:fileRecallAt5 0.5
                                      :fileRecallAt10 1.0
                                      :fileRecallAt20 1.0
                                      :meanReciprocalRankFile 0.5
                                      :noiseRatioAt20 0.5}}
                            {:case-id "case-2"
                             :scores {:fileRecallAt5 0.5
                                      :fileRecallAt10 0.5
                                      :fileRecallAt20 1.0
                                      :meanReciprocalRankFile 0.5
                                      :noiseRatioAt20 1.0}}]}
        candidate {:schema benchmark/agent-report-schema
                   :suite-id "suite"
                   :cases 2
                   :completed 2
                   :runs 2
                   :parserWorkers [{:mode "all"
                                    :source "option"
                                    :runs 2}]
                   :agentDiagnostics {:warningRuns 1}
                   :coverageDiagnostics {:missingDeclaredSourceKindRuns 1
                                         :coverageExcludedGroundTruthFiles 1
                                         :unsupportedGroundTruthFiles 2}
                   :scores {:fileRecallAt5 0.5
                            :fileRecallAt10 0.7
                            :fileRecallAt20 1.0
                            :meanReciprocalRankFile 0.6
                            :noiseRatioAt20 0.8}
                   :results [{:case-id "case-1"
                              :scores {:fileRecallAt5 0.5
                                       :fileRecallAt10 0.75
                                       :fileRecallAt20 1.0
                                       :meanReciprocalRankFile 0.5
                                       :noiseRatioAt20 0.5}}
                             {:case-id "case-2"
                              :scores {:fileRecallAt5 0.5
                                       :fileRecallAt10 0.65
                                       :fileRecallAt20 1.0
                                       :meanReciprocalRankFile 0.7
                                       :noiseRatioAt20 0.9}}]}
        failed (benchmark/compare-agent-reports baseline candidate {})
        passed (benchmark/compare-agent-reports baseline
                                                (assoc candidate
                                                       :agentDiagnostics
                                                       (:agentDiagnostics baseline)
                                                       :coverageDiagnostics
                                                       (:coverageDiagnostics baseline)
                                                       :scores (assoc (:scores baseline)
                                                                      :meanReciprocalRankFile
                                                                      0.6)
                                                       :results (:results baseline))
                                                {})
        expanded (benchmark/compare-agent-reports baseline
                                                  (-> baseline
                                                      (assoc :cases 3
                                                             :completed 3
                                                             :runs 3
                                                             :scores {:fileRecallAt5 0.4
                                                                      :fileRecallAt10 0.5
                                                                      :fileRecallAt20 0.75
                                                                      :meanReciprocalRankFile 0.4
                                                                      :noiseRatioAt20 0.9})
                                                      (update :results conj
                                                              {:case-id "case-3"
                                                               :scores {:fileRecallAt5 0.0
                                                                        :fileRecallAt10 0.0
                                                                        :fileRecallAt20 0.0
                                                                        :meanReciprocalRankFile 0.0
                                                                        :noiseRatioAt20 1.0}}))
                                                  {})
        different-parser-worker (benchmark/compare-agent-reports
                                 baseline
                                 (assoc candidate
                                        :parserWorkers [{:mode "java"
                                                         :source "option"
                                                         :runs 2}]
                                        :results (:results baseline))
                                 {})]
    (is (= benchmark/agent-compare-schema (:schema failed)))
    (is (= "failed" (:status failed)))
    (is (= #{"fileRecallAt10"
             "noiseRatioAt20"
             "warningRuns"
             "missingDeclaredSourceKindRuns"
             "coverageExcludedGroundTruthFiles"
             "unsupportedGroundTruthFiles"
             "case.fileRecallAt10"}
           (set (map :metric (:regressions failed)))))
    (is (= "regressed"
           (get-in (first (filter #(= "case-1" (:case-id %))
                                  (:caseDeltas failed)))
                   [:status])))
    (is (= "passed" (:status passed)))
    (is (empty? (:regressions passed)))
    (is (= "passed" (:status expanded)))
    (is (false? (:aggregateComparable expanded)))
    (is (= ["case-set-changed"]
           (:aggregateComparableReasons expanded)))
    (is (some #(= "added" (:status %)) (:caseDeltas expanded)))
    (is (empty? (:regressions expanded)))
    (is (= "passed" (:status different-parser-worker)))
    (is (false? (:aggregateComparable different-parser-worker)))
    (is (= ["parser-worker-profile-changed"]
           (:aggregateComparableReasons different-parser-worker)))
    (is (= ["parser-worker-profile-changed"]
           (->> (:aggregateDeltas different-parser-worker)
                (filter :ignored?)
                (map :reason)
                distinct
                vec)))
    (is (= [{:mode "all" :source "option"}]
           (get-in different-parser-worker [:baseline :parserWorkers])))
    (is (= [{:mode "java" :source "option"}]
           (get-in different-parser-worker [:candidate :parserWorkers])))
    (is (= (:coverageDiagnostics baseline)
           (get-in failed [:baseline :coverageDiagnostics])))
    (is (= (:coverageDiagnostics candidate)
           (get-in failed [:candidate :coverageDiagnostics])))))

(deftest prepares-issue-replay-case-from-git-diff
  (let [root (temp-dir "agraph-bench-repo")
        out (temp-dir "agraph-bench-out")
        suite-dir (temp-dir "agraph-bench-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! root "docs/release.md" "old app\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (spit-file! root "docs/release.md" "fixed app\n")
      (spit-file! root "src/new.clj" "(ns new)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :tags [:runtime-config :auth]
                                :expectations {:evidence [{:kind :auth-reference
                                                           :path "src/app.clj"}]
                                               :forbidden-edges [:shares-config]}
                                :coverage {:source-kinds [:code :python]}
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :ground-truth {:localization-files ["docs/release.md"
                                                                    "src/app.clj"]}
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value in src/app.clj."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              prepared (first (:cases (benchmark/prepare-suite! suite {:out out})))
              progress-path (io/file out
                                     "fixture"
                                     "cases"
                                     "case-1"
                                     "progress.json")
              progress (json/read-json (slurp progress-path) :key-fn keyword)]
          (is (= benchmark/prepared-case-schema (:schema prepared)))
          (is (str/starts-with? (:caseFingerprint prepared) "sha256:"))
          (is (= ["auth" "runtime-config"] (:tags prepared)))
          (is (= {:evidence [{:kind :auth-reference
                              :path "src/app.clj"}]
                  :forbidden-edges [:shares-config]}
                 (:expectations prepared)))
          (is (= #{"docs/release.md" "src/app.clj" "src/new.clj"}
                 (set (get-in prepared [:groundTruth :changedFiles]))))
          (is (= ["docs/release.md" "src/app.clj"]
                 (get-in prepared [:groundTruth :localizationFiles])))
          (is (= ["src/app.clj"]
                 (get-in prepared [:groundTruth :scoreableFiles])))
          (is (= [{:path "docs/release.md"
                   :kind "doc"}]
                 (get-in prepared [:groundTruth :coverageExcludedFiles])))
          (is (= [{:path "src/new.clj"
                   :reason "missing-at-base"}]
                 (get-in prepared [:groundTruth :unsupportedGroundTruthFiles])))
          (is (= {:hinted true
                  :mentionedChangedFiles ["src/app.clj"]
                  :mentionedChangedFileCount 1
                  :changedFileCount 3}
                 (:inputHints prepared)))
          (is (= {:declaredSourceKinds ["code" "python"]
                  :scoreableSourceKinds ["code" "doc"]
                  :scoreableFilesByKind [{:kind "code"
                                          :files 1}
                                         {:kind "doc"
                                          :files 1}]
                  :missingDeclaredSourceKinds ["python"]}
                 (:coverage prepared)))
          (is (.isDirectory (io/file (:worktreeRoot prepared))))
          (is (= "agraph.benchmark.case-progress/v1" (:schema progress)))
          (is (= #{"prepare-worktree"
                   "prepare-ground-truth"
                   "write-prepared-case"}
                 (set (map :stage (:events progress)))))
          (is (every? pos?
                      (keep :elapsedMs
                            (filter #(= "completed" (:status %))
                                    (:events progress))))))))))

(deftest progress-stage-records-shutdown-interruption
  (let [out (temp-dir "agraph-bench-progress-shutdown")
        expr (pr-str
              `(do
                 (require 'agraph.benchmark)
                 ((var agraph.benchmark/progress-stage!)
                  {:id "fixture"}
                  {:id "case-1" :repo-id "repo"}
                  {:out ~out}
                  :index-project
                  (fn []
                    (System/exit 42)))))
        process (shell/sh "clojure" "-M" "-e" expr)
        progress-path (io/file out
                               "fixture"
                               "cases"
                               "case-1"
                               "progress.json")
        progress (json/read-json (slurp progress-path) :key-fn keyword)
        events (:events progress)
        last-event (last events)]
    (is (= 42 (:exit process)))
    (is (= ["started" "failed"] (mapv :status events)))
    (is (= "index-project" (:stage last-event)))
    (is (= true (:interrupted last-event)))
    (is (= "Benchmark JVM shut down before stage completed."
           (get-in last-event [:error :message])))
    (is (pos? (:elapsedMs last-event)))))

(deftest progress-stage-records-bounded-ex-data
  (let [out (temp-dir "agraph-bench-progress-ex-data")
        progress-path (io/file out
                               "fixture"
                               "cases"
                               "case-1"
                               "progress.json")]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Index deadline exceeded"
      ((var benchmark/progress-stage!)
       {:id "fixture"}
       {:id "case-1" :repo-id "repo"}
       {:out out}
       :index-project
       (fn []
         (throw (ex-info "Index deadline exceeded."
                         {:phase :extract
                          :project-id "project"
                          :repo-id "repo"
                          :files-changed 12
                          :path "src/app.clj"
                          :ignored-object (Object.)}))))))
    (let [progress (json/read-json (slurp progress-path) :key-fn keyword)
          failed (last (:events progress))]
      (is (= "failed" (:status failed)))
      (is (= "index-project" (:stage failed)))
      (is (= {:phase "extract"
              :project-id "project"
              :repo-id "repo"
              :files-changed 12
              :path "src/app.clj"}
             (get-in failed [:error :data]))))))

(deftest writes-agent-packet-without-ground-truth
  (let [root (temp-dir "agraph-bench-agent-repo")
        out (temp-dir "agraph-bench-agent-out")
        suite-dir (temp-dir "agraph-bench-agent-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              packet (first (:packets (benchmark/agent-packets! suite {:out out
                                                                       :case-id "case-1"
                                                                       :parser-worker "java"})))
              project-path (get-in packet [:artifacts :projectConfig])
              packet-path (get-in packet [:artifacts :packetPath])
              project-config (read-string (slurp project-path))
              setup-command (get-in packet [:tools :agraph :setupCommand])]
          (is (= benchmark/agent-packet-schema (:schema packet)))
          (is (= benchmark/agent-result-schema
                 (get-in packet [:task :expectedResultSchema])))
          (is (= {:mode "none|java|dotnet|all"
                  :source "option|env|default|agent-result|unknown"}
                 (get-in packet [:task :resultContract :parserWorker])))
          (is (not (contains? packet :groundTruth)))
          (is (not (contains? packet :inputHints)))
          (is (= {:mode "java"
                  :source "option"}
                 (:parserWorker packet)))
          (is (= (:project-id packet) (:id project-config)))
          (is (= (:worktreeRoot packet) (get-in project-config [:repos 0 :root])))
          (is (str/includes? setup-command "cd "))
          (is (str/includes? setup-command "CLJ_CONFIG="))
          (is (str/includes? setup-command "AGRAPH_PARSER_WORKER='java'"))
          (is (str/includes? setup-command ".cpcache"))
          (is (str/includes? setup-command "bb 'sync'"))
          (is (.isFile (io/file packet-path))))))))

(deftest agent-baseline-context-options-use-wide-candidate-pool-by-default
  (let [prepared {:project-id "project"
                  :repo-id "repo"}
        defaults (#'benchmark/agent-baseline-context-options prepared {})
        override (#'benchmark/agent-baseline-context-options
                  prepared
                  {:budget 12000
                   :retrieval-limit 42})]
    (is (= 24000 (:budget defaults)))
    (is (= 12000 (:budget override)))
    (is (= 300 (:retrieval-limit defaults)))
    (is (= 42 (:retrieval-limit override)))))

(deftest benchmark-index-options-are-bounded-by-default
  (is (= {:index-profile :query
          :index-timeout-ms 600000}
         (#'benchmark/benchmark-index-options {})))
  (is (= {:index-profile :query
          :index-timeout-ms 1234}
         (#'benchmark/benchmark-index-options {:index-timeout-ms 1234})))
  (is (= {:index-profile :query}
         (#'benchmark/benchmark-index-options {:index-timeout-ms 0}))))

(deftest benchmark-parser-worker-profile-is-explicit-and-bindable
  (is (= {:mode "java"
          :source "option"}
         (#'benchmark/parser-worker-profile {:parser-worker "Java"})))
  (extract/with-parser-worker-mode
    "dotnet"
    (is (= "java"
           (#'benchmark/with-benchmark-parser-worker
            {:parser-worker "java"}
            #(extract/parser-worker-mode))))))

(deftest skip-existing-agent-baselines-match-parser-worker-profile
  (let [out (temp-dir "agraph-bench-parser-worker-skip")
        suite {:id "suite"}
        case {:id "case-1"
              :repo-id "repo"
              :base-sha "base"
              :fix-sha "fix"
              :issue {:title "broken app"
                      :body "The app is broken."}}
        java-opts {:out out
                   :parser-worker "java"}
        dotnet-opts {:out out
                     :parser-worker "dotnet"}
        result-path (#'benchmark/agent-baseline-result-path suite case java-opts)
        score-path (#'benchmark/agent-score-path suite case java-opts result-path)
        score {:case-id "case-1"
               :caseFingerprint (#'benchmark/case-fingerprint suite case)
               :agent {:agentId "agraph-baseline-lexical"
                       :mode "agraph"}
               :agentResultPath (.getCanonicalPath (io/file result-path))
               :parserWorker {:mode "dotnet"
                              :source "option"}
               :scores {:fileRecallAt10 1.0}}]
    (.mkdirs (.getParentFile score-path))
    (spit score-path (json/write-json-str score))
    (is (empty? (#'benchmark/current-agent-score-artifacts
                 suite
                 case
                 java-opts
                 {:agent-id "agraph-baseline-lexical"
                  :mode "agraph"
                  :result-path result-path})))
    (is (= 1
           (count (#'benchmark/current-agent-score-artifacts
                   suite
                   case
                   dotnet-opts
                   {:agent-id "agraph-baseline-lexical"
                    :mode "agraph"
                    :result-path result-path}))))))

(deftest agent-run-env-carries-explicit-parser-worker-profile
  (let [env (#'benchmark/agent-run-env
             {:suite-id "suite"
              :case-id "case-1"
              :repo-id "repo"
              :project-id "project"
              :mode "agraph"
              :worktreeRoot "/tmp/worktree"
              :artifacts {:packetPath "/tmp/packet.json"
                          :projectConfig "/tmp/project.edn"
                          :xtdbPath "/tmp/xtdb"}}
             "/tmp/result.json"
             "/tmp/prompt.txt"
             "/tmp/schema.json"
             {:agent-id "agent"
              :parser-worker "dotnet"})]
    (is (= "dotnet" (get env "AGRAPH_BENCH_PARSER_WORKER")))
    (is (= "dotnet" (get env "AGRAPH_PARSER_WORKER")))))

(deftest runs-external-agent-command-and-scores-result
  (let [root (temp-dir "agraph-bench-agent-run-repo")
        out (temp-dir "agraph-bench-agent-run-out")
        suite-dir (temp-dir "agraph-bench-agent-run-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-run-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (spit script-path
              (str "test -f src/app.clj\n"
                   "test -f \"$AGRAPH_BENCH_PACKET\"\n"
                   "test -f \"$AGRAPH_BENCH_PROMPT\"\n"
                   "test -f \"$AGRAPH_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$AGRAPH_BENCH_PROMPT_PROFILE\" = fast\n"
                   "grep -q 'AGRAPH_BENCH_RESULT' \"$AGRAPH_BENCH_PROMPT\"\n"
                   "grep -q 'Fast Localization Profile' \"$AGRAPH_BENCH_PROMPT\"\n"
                   "grep -q 'suspectedFiles' \"$AGRAPH_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$AGRAPH_BENCH_CASE_ID\" = case-1\n"
                   "cat > \"$AGRAPH_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"warnings\":[\"agent note\"],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"
                   "printf 'ran %s\\n' \"$AGRAPH_BENCH_AGENT_ID\"\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "script-agent"
                                                   :mode "shell-only"
                                                   :prompt-profile "fast"
                                                   :command (str "sh " script-path)})
              resumed (benchmark/agent-runs! suite {:out out
                                                    :case-id "case-1"
                                                    :agent-id "script-agent"
                                                    :mode "shell-only"
                                                    :prompt-profile "fast"
                                                    :command "exit 99"
                                                    :skip-existing? true})
              run (first (:runs result))
              skipped (first (:runs resumed))
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "script-agent"})]
          (is (= benchmark/agent-runs-schema (:schema result)))
          (is (= 1 (:completed result)))
          (is (= 0 (:failed result)))
          (is (= benchmark/agent-run-schema (:schema run)))
          (is (= "passed" (:status run)))
          (is (= "fast" (:promptProfile run)))
          (is (= ["agent note"] (:warnings run)))
          (is (= 0 (get-in run [:process :exit])))
          (is (= 1.0 (get-in run [:scores :fileRecallAt10])))
          (is (= 1.0 (get-in run [:scores :meanReciprocalRankFile])))
          (is (.isFile (io/file (get-in run [:artifacts :agentResultPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :agentScorePath]))))
          (is (.isFile (io/file (get-in run [:artifacts :promptPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :outputSchemaPath]))))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             benchmark/agent-result-schema))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "AGRAPH_BENCH_OUTPUT_SCHEMA"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "final response"))
          (is (= ["schema"
                  "caseId"
                  "agentId"
                  "mode"
                  "suspectedFiles"
                  "suspectedSymbols"
                  "commands"
                  "warnings"
                  "summary"]
                 (get (json/read-json
                       (slurp (get-in run [:artifacts :outputSchemaPath]))
                       :key-fn keyword)
                      :required)))
          (is (= {:type "object"
                  :additionalProperties false
                  :properties {:mode {:type "string"}
                               :source {:type "string"}}}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :parserWorker])))
          (is (not (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                                  "changedFiles")))
          (is (str/includes? (slurp (get-in run [:artifacts :stdoutPath]))
                             "ran script-agent"))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores run) (:scores skipped)))
          (is (= 1 (:runs report)))
          (is (= "script-agent" (get-in report [:results 0 :agent :agentId]))))))))

(deftest failed-external-agent-command-is-scored-as-empty-result
  (let [root (temp-dir "agraph-bench-agent-fail-repo")
        out (temp-dir "agraph-bench-agent-fail-out")
        suite-dir (temp-dir "agraph-bench-agent-fail-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-fail-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "failing-agent"
                                                   :mode "shell-only"
                                                   :command "exit 7"})
              run (first (:runs result))
              score (json/read-json
                     (slurp (get-in run [:artifacts :agentScorePath]))
                     :key-fn keyword)]
          (is (= 1 (:failed result)))
          (is (= "failed" (:status run)))
          (is (= 7 (get-in run [:process :exit])))
          (is (.isFile (io/file (get-in run [:artifacts :promptPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :outputSchemaPath]))))
          (is (= 0.0 (get-in run [:scores :fileRecallAt10])))
          (is (= ["Agent command exited with status 7."
                  "agent result did not contain suspected files"]
                 (get-in score [:agent :warnings]))))))))

(deftest context-packet-can-be-written-as-agent-result
  (let [root (temp-dir "agraph-bench-context-result")
        _ (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        packet {:query "broken app"
                :drilldowns ["agraph ask 'broken app' --project fixture"]
                :warnings ["Context warning."]
                :docs [{:source {:path "src/app.clj"
                                 :heading "broken"
                                 :definitionKind :function
                                 :lines [2 4]}
                        :score 2.4
                        :provenance "retrieved-doc"}
                       {:source {:path "src/app.clj"
                                 :heading "broken"}
                        :score 1.5
                        :provenance "retrieved-doc"}
                       {:source {:path "src/missing.clj"
                                 :heading "missing"}
                        :score 1.0
                        :provenance "retrieved-doc"}]
                :entities [{:label "db"
                            :path "src/db.clj"
                            :score 0.7}]}
        result (benchmark/context-packet->agent-result
                packet
                {:agent-id "agraph-baseline-lexical"
                 :case-id "case-1"
                 :root root})]
    (is (= benchmark/agent-result-schema (:schema result)))
    (is (= "case-1" (:caseId result)))
    (is (= "agraph-baseline-lexical" (:agentId result)))
    (is (= "agraph" (:mode result)))
    (is (= [{:path "src/app.clj"
             :rank 1
             :confidence 1.0
             :metrics {:firstSourceRank 1
                       :supportCount 2
                       :docCount 2
                       :entityCount 0
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 3.0
                       :matchedTokenCount 2
                       :definitionKinds ["function"]}
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"
                        "context-doc:src/app.clj provenance=retrieved-doc"]
             :reason (str "AGraph context doc \"broken\" from src/app.clj lines 2-4 "
                          "with provenance retrieved-doc. Additional AGraph evidence: "
                          "1 more matching row.")}
            {:path "src/db.clj"
             :rank 2
             :confidence 0.7
             :metrics {:firstSourceRank 1001
                       :supportCount 1
                       :docCount 0
                       :entityCount 1
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 0.7
                       :rankScore 0.8099999999999999
                       :matchedTokenCount 0
                       :definitionKinds []}
             :evidence ["graph-entity:db path=src/db.clj"]
             :reason "AGraph graph entity \"db\" references src/db.clj."}]
           (:suspectedFiles result)))
    (is (= [{:name "broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"
             :confidence 1.0
             :reason "AGraph context doc \"broken\" references src/app.clj lines 2-4."
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]}
            {:name "missing"
             :path "src/missing.clj"
             :rank 3
             :kind "unknown"
             :confidence 1.0
             :reason "AGraph context doc \"missing\" references src/missing.clj."
             :evidence ["context-doc:src/missing.clj provenance=retrieved-doc"]}]
           (:suspectedSymbols result)))
    (is (= ["agraph ask 'broken app' --project fixture"] (:commands result)))
    (is (= ["Context warning."] (:warnings result)))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 2
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds []}
           (:selection result)))
    (is (not (contains? result :groundTruth)))
    (is (not (contains? result :inputHints)))
    (let [limited (benchmark/context-packet->agent-result packet {:root root
                                                                  :limit 1})]
      (is (= [{:path "src/app.clj"
               :rank 1
               :confidence 1.0
               :metrics {:firstSourceRank 1
                         :supportCount 2
                         :docCount 2
                         :entityCount 0
                         :candidateFileCount 0
                         :retrievedSourceCount 0
                         :exactPathSourceCount 0
                         :maxConfidence 1.0
                         :rankScore 3.0
                         :matchedTokenCount 2
                         :definitionKinds ["function"]}
               :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"
                          "context-doc:src/app.clj provenance=retrieved-doc"]
               :reason (str "AGraph context doc \"broken\" from src/app.clj lines 2-4 "
                            "with provenance retrieved-doc. Additional AGraph evidence: "
                            "1 more matching row.")}]
             (:suspectedFiles limited)))
      (is (= {:rawCandidateFiles 2
              :candidateFiles 2
              :coverageFilteredCandidateFiles 0
              :limit 1
              :coverageSourceKinds []}
             (:selection limited)))
      (is (= 2 (count (:suspectedSymbols limited)))))))

(deftest context-packet-agent-result-respects-declared-source-coverage
  (let [root (temp-dir "agraph-bench-source-coverage")
        _ (spit-file! root ".github/ISSUE_TEMPLATE/bug_report.md" "bug report\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        packet {:query "bug report app"
                :docs [{:source {:path ".github/ISSUE_TEMPLATE/bug_report.md"
                                 :heading "bug report"}
                        :score 10.0
                        :snippet "bug report app"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/app.clj"
                                 :heading "app"}
                        :score 1.0
                        :snippet "app"
                        :provenance "retrieved-doc"}]}
        unfiltered (benchmark/context-packet->agent-result packet {:root root})
        filtered (benchmark/context-packet->agent-result
                  packet
                  {:root root
                   :coverage {:declaredSourceKinds ["code"]}})]
    (is (= [".github/ISSUE_TEMPLATE/bug_report.md" "src/app.clj"]
           (mapv :path (:suspectedFiles unfiltered))))
    (is (= ["src/app.clj"]
           (mapv :path (:suspectedFiles filtered))))
    (is (= [1]
           (mapv :rank (:suspectedFiles filtered))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["code"]}
           (:selection filtered)))))

(deftest context-packet-agent-result-uses-scanned-kind-for-extensionless-files
  (let [root (temp-dir "agraph-bench-extensionless-coverage")
        _ (spit-file! root "test/fast/Unit tests/nvm_remote_version"
                      "#!/usr/bin/env bash\necho ok\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        packet {:query "remote version app"
                :candidateFiles [{:path "test/fast/Unit tests/nvm_remote_version"
                                  :rank 1
                                  :score 1.0
                                  :targetKind :chunk
                                  :label "nvm_remote_version"}
                                 {:path "src/app.clj"
                                  :rank 2
                                  :score 1.0
                                  :targetKind :chunk
                                  :label "app"}]}
        filtered (benchmark/context-packet->agent-result
                  packet
                  {:root root
                   :coverage {:declaredSourceKinds ["shell"]}})]
    (is (= ["test/fast/Unit tests/nvm_remote_version"]
           (mapv :path (:suspectedFiles filtered))))
    (is (= [1]
           (mapv :rank (:suspectedFiles filtered))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["shell"]}
           (:selection filtered)))))

(deftest file-ranking-uses-mechanical-query-token-coverage
  (let [root (temp-dir "agraph-bench-token-coverage")
        _ (spit-file! root "src/early.clj" "(ns early)\n")
        _ (spit-file! root "src/later.clj" "(ns later)\n")
        packet {:query "open page root"
                :docs [{:source {:path "src/early.clj"
                                 :heading "open-handler"}
                        :score 2.0
                        :snippet "open"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/later.clj"
                                 :heading "page-root-handler"}
                        :score 1.7
                        :snippet "open page root"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/later.clj" "src/early.clj"]
           (mapv :path files)))
    (is (= 3 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 1 (get-in files [1 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-caps-query-token-support
  (let [root (temp-dir "agraph-bench-token-cap")
        _ (spit-file! root "src/broad.clj" "(ns broad)\n")
        _ (spit-file! root "src/strong.clj" "(ns strong)\n")
        packet {:query "one two three four five six seven eight nine ten"
                :docs [{:source {:path "src/broad.clj"
                                 :heading "one two three four five six seven eight nine ten"}
                        :score 1.0
                        :snippet "one two three four five six seven eight nine ten"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/strong.clj"
                                 :heading "one two three four five"}
                        :score 1.8
                        :snippet "one two three four five"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/strong.clj" "src/broad.clj"]
           (mapv :path files)))
    (is (= 5 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 10 (get-in files [1 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-retrieved-candidate-file-support
  (let [root (temp-dir "agraph-bench-candidate-files")
        _ (spit-file! root "src/seed.clj" "(ns seed)\n")
        _ (spit-file! root "src/adjacent.clj" "(ns adjacent)\n")
        packet {:query "open page root"
                :docs [{:source {:path "src/seed.clj"
                                 :heading "open-page"}
                        :score 0.5
                        :snippet "open page"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/adjacent.clj"
                                  :rank 30
                                  :score 0.7
                                  :targetKind "chunk"
                                  :label "page root"}
                                 {:path "src/adjacent.clj"
                                  :rank 38
                                  :score 0.6
                                  :targetKind "node"
                                  :label "open root"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/adjacent.clj" "src/seed.clj"]
           (mapv :path files)))
    (is (= 2 (get-in files [0 :metrics :candidateFileCount])))
    (is (= 3 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 1 (get-in files [0 :metrics :matchedTokenPairCount])))))

(deftest file-ranking-caps-repeated-file-support-bonus
  (let [root (temp-dir "agraph-bench-repeated-file-support")
        _ (spit-file! root "src/early.tf" "resource \"demo\" \"early\" {}\n")
        _ (spit-file! root "src/repeated.tf" "variable \"demo\" {}\n")
        packet {:query "flow log policy stream"
                :docs (concat
                       [{:source {:path "src/early.tf"
                                  :heading "src/early.tf"}
                         :score 1.0
                         :snippet "flow log policy stream"
                         :retrievedSource true
                         :provenance "retrieved-doc"}]
                       (for [idx (range 7)]
                         {:source {:path "src/repeated.tf"
                                   :heading (str "var.repeated_" idx)}
                          :score 0.95
                          :snippet "flow log policy"
                          :retrievedSource true
                          :provenance "retrieved-doc"}))}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/early.tf" "src/repeated.tf"]
           (mapv :path files)))
    (is (= 7 (get-in files [1 :metrics :docCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-mechanical-graph-neighbor-evidence
  (let [root (temp-dir "agraph-bench-candidate-graph")
        _ (spit-file! root "src/direct.clj" "(ns direct)\n")
        _ (spit-file! root "src/importing.clj" "(ns importing)\n")
        packet {:query "stream context"
                :candidateFiles [{:path "src/direct.clj"
                                  :rank 5
                                  :score 0.7
                                  :targetKind "node"
                                  :label "direct stream context"
                                  :scoreComponents {:lexical 0.7
                                                    :graph 0.0}}
                                 {:path "src/importing.clj"
                                  :rank 50
                                  :score 0.35
                                  :targetKind "node"
                                  :label "importing stream context"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/importing.clj" "src/direct.clj"]
           (mapv :path files)))
    (is (= ["candidate-file:src/importing.clj rank=50 targetKind=node label=\"importing stream context\" score=0.35 components=graph:0.6,lexical:0.2"]
           (get-in files [0 :evidence])))
    (is (= 0.6 (get-in files [0 :metrics :graphNeighborScore])))
    (is (= 2 (get-in files [0 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-requires-lexical-support-for-graph-neighbor-boost
  (let [root (temp-dir "agraph-bench-candidate-graph-support")
        _ (spit-file! root "src/thin.clj" "(ns thin)\n")
        _ (spit-file! root "src/supported.clj" "(ns supported)\n")
        packet {:query "open page root"
                :candidateFiles [{:path "src/thin.clj"
                                  :rank 1
                                  :score 0.7
                                  :targetKind "node"
                                  :label "open"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.8}}
                                 {:path "src/supported.clj"
                                  :rank 2
                                  :score 0.6
                                  :targetKind "node"
                                  :label "open page"
                                  :scoreComponents {:lexical 0.3
                                                    :graph 0.5}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/supported.clj" "src/thin.clj"]
           (mapv :path files)))
    (is (nil? (get-in files [1 :metrics :graphNeighborScore])))
    (is (= 0.5 (get-in files [0 :metrics :graphNeighborScore])))))

(deftest file-ranking-bounds-candidate-only-graph-boost-by-evidence
  (let [root (temp-dir "agraph-bench-candidate-graph-bounds")
        _ (spit-file! root "src/direct.clj" "(ns direct)\n")
        _ (spit-file! root "src/late-neighbor.clj" "(ns late-neighbor)\n")
        packet {:query "console unique id selector"
                :docs [{:source {:path "src/direct.clj"
                                 :heading "direct unique id selector"}
                        :score 1.0
                        :snippet "unique id selector"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/late-neighbor.clj"
                                  :rank 100
                                  :score 0.35
                                  :targetKind "node"
                                  :label "console selector"
                                  :scoreComponents {:lexical 0.05
                                                    :graph 1.0}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/direct.clj" "src/late-neighbor.clj"]
           (mapv :path files)))
    (is (= 1.0 (get-in files [1 :metrics :graphNeighborScore])))
    (is (< (get-in files [1 :metrics :graphNeighborBoost])
           (get-in files [1 :metrics :graphNeighborScore])))))

(deftest file-ranking-uses-ordered-query-token-pairs-in-candidate-labels
  (let [root (temp-dir "agraph-bench-candidate-token-pairs")
        _ (spit-file! root "src/scattered.clj" "(ns scattered)\n")
        _ (spit-file! root "src/phrase.clj" "(ns phrase)\n")
        packet {:query "remote version descriptions"
                :candidateFiles [{:path "src/scattered.clj"
                                  :rank 1
                                  :score 0.8
                                  :targetKind "chunk"
                                  :label "remote docs version"}
                                 {:path "src/phrase.clj"
                                  :rank 2
                                  :score 0.55
                                  :targetKind "chunk"
                                  :label "remote version"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/phrase.clj" "src/scattered.clj"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedTokenPairCount])))
    (is (nil? (get-in files [1 :metrics :matchedTokenPairCount])))))

(deftest file-ranking-uses-compound-identifier-query-token-pairs
  (let [root (temp-dir "agraph-bench-compound-token-pairs")
        target "test/fast/Unit tests/nvm_remote_version"
        noisy-paths (mapv #(str "test/fast/Running 'nvm use "
                                %
                                "' should not create the current symlink")
                          (range 24))
        _ (doseq [path (cons target noisy-paths)]
            (spit-file! root path "#!/bin/sh\n"))
        packet {:query "nvm install argon should not remote version"
                :candidateFiles (vec
                                 (concat
                                  (map-indexed
                                   (fn [idx path]
                                     {:path path
                                      :rank (inc idx)
                                      :score 0.42
                                      :targetKind "chunk"
                                      :label path})
                                   noisy-paths)
                                  [{:path target
                                    :rank 99
                                    :score 0.40
                                    :targetKind "chunk"
                                    :label "nvm_remote_version"}]))}
        result (benchmark/context-packet->agent-result packet {:root root
                                                               :limit 20})
        files (:suspectedFiles result)
        target-row (some #(when (= target (:path %)) %) files)]
    (is (some? target-row))
    (is (= 1 (get-in target-row [:metrics :matchedCompoundTokenPairCount])))
    (is (<= (:rank target-row) 20))))

(deftest limited-agent-result-reserves-candidate-file-only-evidence
  (let [root (temp-dir "agraph-bench-candidate-file-quota")
        _ (doseq [path ["src/doc-1.clj" "src/doc-2.clj" "src/doc-3.clj"
                        "src/doc-4.clj" "src/doc-5.clj" "src/candidate.clj"]]
            (spit-file! root path "(ns fixture)\n"))
        packet {:query "remote version"
                :docs (mapv (fn [idx]
                              {:source {:path (str "src/doc-" idx ".clj")
                                        :heading (str "doc " idx)}
                               :score (- 2.0 (* 0.1 idx))
                               :snippet "remote"
                               :provenance "retrieved-doc"})
                            (range 1 6))
                :candidateFiles [{:path "src/candidate.clj"
                                  :rank 99
                                  :score 0.4
                                  :targetKind "chunk"
                                  :label "remote version"}]}
        result (benchmark/context-packet->agent-result packet {:root root
                                                               :limit 5})
        files (:suspectedFiles result)]
    (is (= ["src/doc-1.clj"
            "src/doc-2.clj"
            "src/doc-3.clj"
            "src/doc-4.clj"
            "src/candidate.clj"]
           (mapv :path files)))
    (is (= 1 (get-in files [4 :metrics :matchedTokenPairCount])))
    (is (= {:rawCandidateFiles 6
            :candidateFiles 6
            :coverageFilteredCandidateFiles 0
            :limit 5
            :coverageSourceKinds []
            :candidateFileOnlyQuota 5
            :candidateFileOnlySelected 1}
           (:selection result)))))

(deftest file-ranking-preserves-early-retrieved-source-order
  (let [root (temp-dir "agraph-bench-retrieved-rank")
        _ (spit-file! root "src/early.clj" "(ns early)\n")
        _ (spit-file! root "src/later.clj" "(ns later)\n")
        packet {:query "json handler conversion"
                :docs [{:source {:path "src/unrelated-1.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/early.clj"
                                 :heading "handler"}
                        :score 1.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/unrelated-2.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/unrelated-3.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/later.clj"
                                 :heading "handler"}
                        :score 1.05
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/early.clj" "src/later.clj"]
           (mapv :path files)))
    (is (> (get-in files [0 :metrics :sourceRankScore])
           (get-in files [1 :metrics :sourceRankScore])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest local-vector-baseline-shells-out-and-scores-agent-result
  (let [repo (temp-dir "agraph-bench-local-vector-repo")
        out (temp-dir "agraph-bench-local-vector-out")
        suite-dir (temp-dir "agraph-bench-local-vector-suite")
        worker-dir (temp-dir "agraph-bench-local-vector-worker")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        worker-path (spit-file!
                     worker-dir
                     "fake-vector-worker.sh"
                     (str "#!/bin/sh\n"
                          "cat > \"$2\" <<'JSON'\n"
                          "{\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake local vector match\"}],"
                          "\"suspectedSymbols\":[],\"commands\":[],\"warnings\":[]}\n"
                          "JSON\n"))]
    (.setExecutable (io/file worker-path) true)
    (sh! "git" "init" repo)
    (git! repo "config" "user.email" "bench@example.test")
    (git! repo "config" "user.name" "Benchmark Test")
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base (commit! repo "base")]
      (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :new)\n")
      (let [fix (commit! repo "fix")]
        (spit-file!
         suite-dir
         "benchmark.edn"
         (pr-str {:id "fixture"
                  :repos [{:id "repo" :root repo}]
                  :cases [{:id "case-1"
                           :repo-id "repo"
                           :base-sha base
                           :fix-sha fix
                           :issue {:id 1
                                   :title "broken app"
                                   :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-baselines!
                      suite
                      {:out out
                       :case-id "case-1"
                       :retriever "local-vector"
                       :vector-command worker-path
                       :vector-model "fake-local-model"})
              baseline (first (:baselines result))
              request (json/read-json
                       (slurp (get-in baseline [:artifacts :localVectorRequestPath]))
                       :key-fn keyword)
              scored (json/read-json
                      (slurp (get-in baseline [:artifacts :agentScorePath]))
                      :key-fn keyword)
              resumed (benchmark/agent-baselines!
                       suite
                       {:out out
                        :case-id "case-1"
                        :retriever "local-vector"
                        :vector-command "missing-vector-worker"
                        :vector-model "fake-local-model"
                        :skip-existing? true})
              skipped (first (:baselines resumed))]
          (is (= benchmark/agent-baselines-schema (:schema result)))
          (is (= "agraph-baseline-local-vector" (:agentId baseline)))
          (is (= "local-vector" (:mode baseline)))
          (is (= "local-vector" (:retriever baseline)))
          (is (= "fake-local-model" (get-in baseline [:localVector :model])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "agraph.benchmark.local-vector-request/v1" (:schema request)))
          (is (= "fake-local-model" (:model request)))
          (is (not (contains? request :groundTruth)))
          (is (= "local-vector" (get-in scored [:agent :mode])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))

(deftest context-packet-can-be-written-as-agent-hints
  (let [root (temp-dir "agraph-bench-context-hints")
        _ (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "project"
                  :worktreeRoot root}
        packet {:query "broken app"
                :drilldowns ["agraph ask 'broken app' --project project"]
                :warnings []
                :answerability {:status :ok}
                :sourceCoverage {:schema "agraph.source-coverage.context/v1"
                                 :basis "indexed-graph"
                                 :totals {:indexedFiles 2
                                          :diagnostics 0
                                          :fileKinds 1}
                                 :topFileKinds [{:kind "code"
                                                 :count 2}]
                                 :extractors [{:kind "code"
                                               :extractorVersion "clojure/v9"
                                               :files 2}]
                                 :diagnostics {:byStage []
                                               :byExtractor []}}
                :docs [{:source {:repo "repo"
                                 :path "src/app.clj"
                                 :kind "code-definition"
                                 :heading "app/broken"
                                 :definitionKind :function
                                 :lines [2 4]}
                        :score 2.4
                        :snippet "(defn broken [] :old)"
                        :provenance "retrieved-doc"}
                       {:source {:repo "repo"
                                 :path "src/missing.clj"
                                 :heading "missing"}
                        :score 1.0
                        :snippet "missing"
                        :provenance "retrieved-doc"}]
                :entities [{:id "system:repo:path/src"
                            :label "src"
                            :path "src"
                            :repo "repo"
                            :score 0.7
                            :why "retrieval and graph match"
                            :metrics {:file-count 2}}]}
        hints (benchmark/context-packet->agent-hints prepared packet {:limit 1})]
    (is (= benchmark/agent-hints-schema (:schema hints)))
    (is (= "case-1" (:case-id hints)))
    (is (= "broken app" (:query hints)))
    (is (= [{:path "src/app.clj"
             :rank 1
             :confidence 1.0
             :metrics {:firstSourceRank 1
                       :supportCount 1
                       :docCount 1
                       :entityCount 0
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 2.92
                       :matchedTokenCount 2
                       :definitionKinds ["function"]}
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]
             :reason "AGraph context doc \"app/broken\" from src/app.clj lines 2-4 with provenance retrieved-doc."}]
           (:topFiles hints)))
    (is (= [{:name "app/broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"
             :confidence 1.0
             :reason "AGraph context doc \"app/broken\" references src/app.clj lines 2-4."
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]}
            {:name "missing"
             :path "src/missing.clj"
             :rank 2
             :kind "unknown"
             :confidence 1.0
             :reason "AGraph context doc \"missing\" references src/missing.clj."
             :evidence ["context-doc:src/missing.clj provenance=retrieved-doc"]}]
           (:topSymbols hints)))
    (is (= [{:rank 1
             :path "src/app.clj"
             :heading "app/broken"
             :kind "code-definition"
             :definitionKind :function
             :score 2.4
             :provenance "retrieved-doc"
             :snippet "(defn broken [] :old)"
             :repo "repo"
             :lines {:start 2
                     :end 4}}
            {:rank 2
             :path "src/missing.clj"
             :heading "missing"
             :kind nil
             :definitionKind nil
             :score 1.0
             :provenance "retrieved-doc"
             :snippet "missing"
             :repo "repo"}]
           (:topDocs hints)))
    (is (= [{:rank 1
             :id "system:repo:path/src"
             :repo "repo"
             :path "src"
             :label "src"
             :score 0.7
             :why "retrieval and graph match"
             :metrics {:file-count 2}}]
           (:candidateSystems hints)))
    (is (= ["agraph ask 'broken app' --project project"] (:commands hints)))
    (is (= {:indexedFiles 2
            :diagnostics 0
            :fileKinds 1}
           (get-in hints [:sourceCoverage :totals])))
    (is (not (contains? hints :groundTruth)))
    (is (not (contains? hints :inputHints)))))

(deftest scores-agent-localization-result
  (let [root (temp-dir "agraph-bench-agent-score")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :input {:title "broken app"}
                  :inputHints {:hinted true
                               :mentionedChangedFiles ["src/app.clj"]
                               :mentionedChangedFileCount 1
                               :changedFileCount 2}
                  :groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "agraph"
                      :suspectedFiles [{:path "src/other.clj"
                                        :rank 1
                                        :confidence 0.9}
                                       {:path "src/app.clj"
                                        :rank 2
                                        :confidence 0.8
                                        :metrics {:supportCount 2
                                                  :retrievedSourceCount 1}}
                                       {:path "src/db.clj"
                                        :rank 3
                                        :confidence 0.7}]}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= benchmark/agent-score-schema (:schema scored)))
    (is (= "sha256:test-case" (:caseFingerprint scored)))
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= 0.5 (get-in scored [:scores :meanReciprocalRankFile])))
    (is (= (:inputHints prepared) (:inputHints scored)))
    (is (= ["src/other.clj"] (get-in scored [:agent :missingPredictedFiles])))
    (is (= {:supportCount 2
            :retrievedSourceCount 1}
           (get-in scored [:agent :topFiles 1 :metrics])))
    (is (= [{:path "src/app.clj"
             :rank 2
             :found? true}
            {:path "src/db.clj"
             :rank 3
             :found? true}]
           (get-in scored [:groundTruthRanks :files])))))

(deftest score-agent-result-warns-on-malformed-agent-output
  (let [root (temp-dir "agraph-bench-agent-score-shape")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8}
                                       "README.md"]
                      :suspectedSymbols [{:name "broken"
                                          :path "src/app.clj"
                                          :rank 1}]}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= ["agent result missing required field caseId"
            "agent result missing required field agentId"
            "agent result missing required field mode"
            "agent result missing required field commands"
            "agent result missing required field warnings"
            "agent result missing required field summary"
            "agent result suspectedFiles row 1 path src/app.clj missing reason"
            "agent result suspectedFiles row 1 path src/app.clj missing evidence"
            "agent result suspectedFiles row 2 is not an object"
            "agent result suspectedSymbols row 1 path src/app.clj missing kind"
            "agent result suspectedSymbols row 1 path src/app.clj missing confidence"
            "agent result suspectedSymbols row 1 path src/app.clj missing reason"
            "agent result suspectedSymbols row 1 path src/app.clj missing evidence"
            "agent result referenced files missing from the base checkout"]
           (get-in scored [:agent :warnings])))))

(deftest score-agent-result-writes-parser-worker-provenance
  (let [root (temp-dir "agraph-bench-agent-score-worker")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "agraph"
                      :parserWorker {:mode "dotnet"
                                     :source "agent-result"}
                      :suspectedFiles [{:path "src/app.clj"}]}
        suite {:id "suite"}
        case {:id "case-1"}
        result-path (.getPath (io/file root "agent-result.json"))
        score-path (.getPath (io/file root
                                      "suite"
                                      "cases"
                                      "case-1"
                                      "agent-scores"
                                      "agent-result.score.json"))]
    (spit-json! root "agent-result.json" agent-result)
    (with-redefs [benchmark/prepare-case! (fn [_suite _case _opts] prepared)]
      (let [scored (benchmark/score-agent-result! suite
                                                  case
                                                  {:out root
                                                   :result-path result-path
                                                   :parser-worker "all"})]
        (is (= {:mode "all"
                :source "option"}
               (:parserWorker scored)))
        (is (= (:parserWorker scored)
               (:parserWorker (json/read-json (slurp score-path) :key-fn keyword))))))
    (with-redefs [benchmark/prepare-case! (fn [_suite _case _opts] prepared)]
      (let [scored (benchmark/score-agent-result! suite
                                                  case
                                                  {:out root
                                                   :result-path result-path})]
        (is (= {:mode "dotnet"
                :source "agent-result"}
               (:parserWorker scored)))))))

(deftest context-ground-truth-ranks-show-context-misses-separately
  (let [root (temp-dir "agraph-bench-context-ground-truth")
        _ (spit-file! root "src/visible.clj" "(ns visible)\n")
        _ (spit-file! root "src/below-limit.clj" "(ns below-limit)\n")
        prepared {:worktreeRoot root
                  :groundTruth {:changedFiles ["src/below-limit.clj"]
                                :unsupportedGroundTruthFiles []}}
        packet {:query "visible below limit"
                :docs [{:source {:path "src/visible.clj"
                                 :heading "visible"}
                        :score 2.0
                        :snippet "visible"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/below-limit.clj"
                                  :rank 99
                                  :score 0.1
                                  :targetKind :chunk
                                  :label "below limit"}]}
        ranks (#'benchmark/context-ground-truth-ranks prepared packet)]
    (is (= [{:path "src/below-limit.clj"
             :rank 2
             :found? true}]
           (get-in ranks [:files])))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 2
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds []}
           (:selection ranks)))))
