(ns agraph.benchmark-test
  (:require [agraph.benchmark :as benchmark]
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

(deftest reports-agent-score-artifacts
  (let [out (temp-dir "agraph-agent-report")
        suite {:id "suite"
               :cases [{:id "case-1"}
                       {:id "case-2"}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "codex"
                         :mode "agraph"}
                 :coverage {:declaredSourceKinds ["code" "python"]
                            :scoreableSourceKinds ["code"]
                            :scoreableFilesByKind [{:kind "code"
                                                    :files 2}]
                            :missingDeclaredSourceKinds ["python"]}
                 :inputHints {:hinted true
                              :mentionedChangedFiles ["src/app.clj"]
                              :mentionedChangedFileCount 1
                              :changedFileCount 3}
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
                         :mode "shell-only"}
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
    (let [report (benchmark/report-agent-suite suite {:out out})]
      (is (= benchmark/agent-report-schema (:schema report)))
      (is (= 2 (:cases report)))
      (is (= 1 (:completed report)))
      (is (= 2 (:runs report)))
      (is (= ["case-2"] (:missing report)))
      (is (= 0.75 (get-in report [:scores :fileRecallAt10])))
      (is (= 6 (get-in report [:scores :changedFiles])))
      (is (= 4 (get-in report [:scores :scoreableChangedFiles])))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (:inputHints report)))
      (is (= {:declaredSourceKinds ["code" "python"]
              :scoreableSourceKinds ["code"]
              :scoreableFilesByKind [{:kind "code"
                                      :cases 1
                                      :scoreableFiles 2}]
              :missingDeclaredSourceKinds [{:case-id "case-1"
                                            :kind "python"}]}
             (:coverage report)))
      (is (= {:inputHintedRuns 1
              :inputHintedCases 1
              :inputHintedCaseIds ["case-1"]}
             (get-in (first (:byMode report)) [:inputHints])))
      (is (= #{"agraph" "shell-only"} (set (map :key (:byMode report)))))
      (is (= ["baseline" "codex"] (mapv :key (:byAgent report)))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :mode "agraph"})]
      (is (= 1 (:runs report)))
      (is (= 1.0 (get-in report [:scores :fileRecallAt10])))
      (is (= ["agraph"] (mapv :key (:byMode report)))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :agent-id "baseline"})]
      (is (= 1 (:runs report)))
      (is (= "baseline" (get-in report [:results 0 :agent :agentId])))
      (is (= ["baseline"] (mapv :key (:byAgent report))))
      (is (= 0.5 (get-in report [:scores :fileRecallAt10]))))))

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
                         :unsupportedGroundTruthFiles 1}
                :inputHints {:inputHintedCases 1}
                :results [{:case-id "case-1"
                           :agent {:agentId "codex"
                                   :mode "agraph"}
                           :scores {:fileRecallAt5 0.5
                                    :fileRecallAt10 0.75
                                    :fileRecallAt20 1.0
                                    :meanReciprocalRankFile 0.5
                                    :noiseRatioAt20 0.75}}]}
        failed (benchmark/check-agent-report
                report
                {:min-cases 3
                 :min-runs 2
                 :min-file-recall-at-10 0.9
                 :min-mrr 0.8
                 :max-noise-at-20 0.5
                 :min-case-file-recall-at-10 0.9
                 :min-case-mrr 0.8
                 :max-case-noise-at-20 0.5
                 :max-input-hinted-cases 0
                 :max-unsupported-ground-truth-files 0})
        passed (benchmark/check-agent-report
                (assoc report :completed 2 :missing [])
                {:allow-missing? true
                 :min-cases 2
                 :min-runs 1
                 :min-file-recall-at-10 0.75
                 :min-mrr 0.5
                 :max-noise-at-20 0.75
                 :min-case-file-recall-at-10 0.75
                 :min-case-mrr 0.5
                 :max-case-noise-at-20 0.75
                 :max-input-hinted-cases 1
                 :max-unsupported-ground-truth-files 1})]
    (is (= benchmark/agent-check-schema (:schema failed)))
    (is (= "failed" (:status failed)))
    (is (= #{"completed"
             "cases"
             "runs"
             "fileRecallAt10"
             "meanReciprocalRankFile"
             "noiseRatioAt20"
             "case.fileRecallAt10"
             "case.meanReciprocalRankFile"
             "case.noiseRatioAt20"
             "inputHintedCases"
             "unsupportedGroundTruthFiles"}
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
             "case.noiseRatioAt20"}
           (set (map :metric (get-in failed [:caseDiagnostics 0 :failures])))))
    (is (= {:fileRecallAt5 0.5
            :fileRecallAt10 0.75
            :fileRecallAt20 1.0
            :meanReciprocalRankFile 0.5
            :noiseRatioAt20 0.75}
           (get-in failed [:caseDiagnostics 0 :scores])))
    (is (= {:case-id "case-2"
            :status "missing"}
           (select-keys (get-in failed [:caseDiagnostics 1])
                        [:case-id :status])))
    (is (= {:requireComplete true
            :allowDuplicateRuns false
            :minCases 3.0
            :minRuns 2.0
            :minFileRecallAt10 0.9
            :minMeanReciprocalRankFile 0.8
            :maxNoiseRatioAt20 0.5
            :minCaseFileRecallAt10 0.9
            :minCaseMeanReciprocalRankFile 0.8
            :maxCaseNoiseRatioAt20 0.5
            :maxInputHintedCases 0.0
            :maxUnsupportedGroundTruthFiles 0.0}
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

(deftest prepares-issue-replay-case-from-git-diff
  (let [root (temp-dir "agraph-bench-repo")
        out (temp-dir "agraph-bench-out")
        suite-dir (temp-dir "agraph-bench-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (spit-file! root "src/new.clj" "(ns new)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :coverage {:source-kinds [:code :python]}
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :ground-truth {:localization-files ["src/app.clj"]}
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
          (is (= #{"src/app.clj" "src/new.clj"}
                 (set (get-in prepared [:groundTruth :changedFiles]))))
          (is (= ["src/app.clj"]
                 (get-in prepared [:groundTruth :localizationFiles])))
          (is (= [{:path "src/new.clj"
                   :reason "missing-at-base"}]
                 (get-in prepared [:groundTruth :unsupportedGroundTruthFiles])))
          (is (= {:hinted true
                  :mentionedChangedFiles ["src/app.clj"]
                  :mentionedChangedFileCount 1
                  :changedFileCount 2}
                 (:inputHints prepared)))
          (is (= {:declaredSourceKinds ["code" "python"]
                  :scoreableSourceKinds ["code"]
                  :scoreableFilesByKind [{:kind "code"
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
                                                                       :case-id "case-1"})))
              project-path (get-in packet [:artifacts :projectConfig])
              packet-path (get-in packet [:artifacts :packetPath])
              project-config (read-string (slurp project-path))
              setup-command (get-in packet [:tools :agraph :setupCommand])]
          (is (= benchmark/agent-packet-schema (:schema packet)))
          (is (= benchmark/agent-result-schema
                 (get-in packet [:task :expectedResultSchema])))
          (is (not (contains? packet :groundTruth)))
          (is (not (contains? packet :inputHints)))
          (is (= (:project-id packet) (:id project-config)))
          (is (= (:worktreeRoot packet) (get-in project-config [:repos 0 :root])))
          (is (str/includes? setup-command "cd "))
          (is (str/includes? setup-command "CLJ_CONFIG="))
          (is (str/includes? setup-command ".cpcache"))
          (is (str/includes? setup-command "bb 'sync'"))
          (is (.isFile (io/file packet-path))))))))

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
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\"}],"
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
              run (first (:runs result))
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
          (is (not (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                                  "changedFiles")))
          (is (str/includes? (slurp (get-in run [:artifacts :stdoutPath]))
                             "ran script-agent"))
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
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 3.0
                       :matchedTokenCount 2
                       :definitionKinds ["function"]}
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
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 0.7
                       :rankScore 0.8099999999999999
                       :matchedTokenCount 0
                       :definitionKinds []}
             :reason "AGraph graph entity \"db\" references src/db.clj."}]
           (:suspectedFiles result)))
    (is (= [{:name "broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"}
            {:name "missing"
             :path "src/missing.clj"
             :rank 3
             :kind nil}]
           (:suspectedSymbols result)))
    (is (= ["agraph ask 'broken app' --project fixture"] (:commands result)))
    (is (= {:candidateFiles 2
            :limit nil}
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
                         :retrievedSourceCount 0
                         :exactPathSourceCount 0
                         :maxConfidence 1.0
                         :rankScore 3.0
                         :matchedTokenCount 2
                         :definitionKinds ["function"]}
               :reason (str "AGraph context doc \"broken\" from src/app.clj lines 2-4 "
                            "with provenance retrieved-doc. Additional AGraph evidence: "
                            "1 more matching row.")}]
             (:suspectedFiles limited)))
      (is (= {:candidateFiles 2
              :limit 1}
             (:selection limited)))
      (is (= 2 (count (:suspectedSymbols limited)))))))

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
                      :key-fn keyword)]
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
                 (mapv :path (get-in scored [:agent :topFiles])))))))))

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
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 2.92
                       :matchedTokenCount 2
                       :definitionKinds ["function"]}
             :reason "AGraph context doc \"app/broken\" from src/app.clj lines 2-4 with provenance retrieved-doc."}]
           (:topFiles hints)))
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
