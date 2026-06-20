(ns agraph.benchmark-agent-test
  (:require [agraph.benchmark :as benchmark]
            [agraph.benchmark-agent-run :as benchmark-agent-run]
            [agraph.benchmark-test-support :refer [commit! git! sh! spit-file! spit-json! temp-dir]]
            [agraph.context :as context]
            [agraph.extract :as extract]
            [agraph.project :as project]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest agent-run-timeout-kills-child-processes
  (let [started-at (System/currentTimeMillis)
        result (benchmark-agent-run/run-process!
                "sleep 10"
                "."
                {}
                100)
        elapsed (- (System/currentTimeMillis) started-at)]
    (is (:timedOut result))
    (is (= -1 (:exit result)))
    (is (< elapsed 3000))))

(deftest agent-run-closes-child-stdin
  (let [started-at (System/currentTimeMillis)
        result (benchmark-agent-run/run-process!
                "cat >/dev/null"
                "."
                {}
                3000)
        elapsed (- (System/currentTimeMillis) started-at)]
    (is (false? (:timedOut result)))
    (is (= 0 (:exit result)))
    (is (< elapsed 1000))))

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
          (is (str/starts-with? (:caseFingerprint packet) "sha256:"))
          (is (str/starts-with? (:agentInputFingerprint packet) "sha256:"))
          (is (not= (:caseFingerprint packet)
                    (:agentInputFingerprint packet)))
          (is (contains? (set (get-in packet [:task :rules]))
                         "Only include files likely to require edits in suspectedFiles; cite comparison, example, generated, or read-only support files as evidence instead."))
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
        score {:schema benchmark/agent-score-schema
               :case-id "case-1"
               :caseFingerprint (#'benchmark/case-fingerprint suite case)
               :agent {:agentId "agraph-baseline-lexical"
                       :schema benchmark/agent-result-schema
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
(deftest agraph-agent-run-prompt-points-agents-at-architecture-hints
  (let [root (temp-dir "agraph-bench-agent-prompt")
        result-path (.getPath (io/file root "result.json"))
        schema-path (.getPath (io/file root "schema.json"))
        prompt (#'benchmark/agent-run-prompt
                {:suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :project-id "project"
                 :mode "agraph"
                 :worktreeRoot "/tmp/worktree"
                 :task {:objective "Find likely edit locations."}
                 :artifacts {:packetPath "/tmp/packet.json"
                             :projectConfig "/tmp/project.edn"
                             :xtdbPath "/tmp/xtdb"
                             :agraphHintsPath "/tmp/hints.json"
                             :agraphContextPath "/tmp/context.json"}}
                result-path
                schema-path
                {:agent-id "agent"
                 :prompt-profile "fast"})]
    (is (str/includes? prompt "AGraph hints JSON: /tmp/hints.json"))
    (is (str/includes? prompt "AGraph context JSON: /tmp/context.json"))
    (is (str/includes? prompt "`topFiles`, `architecture`, and `auditScopes`"))
    (is (str/includes? prompt
                       "`answerability`, `sourceCoverage`, and `diagnostics`"))
    (is (str/includes? prompt
                       "`commands` as bounded follow-up checks"))
    (is (str/includes? prompt
                       "`architecture.validationGaps.nextActions`"))))
(deftest agraph-agent-run-builds-context-artifacts-after-indexing
  (let [out (temp-dir "agraph-bench-agent-run-context-order")
        worktree (temp-dir "agraph-bench-agent-run-context-worktree")
        suite {:id "suite"}
        case {:id "case-1"}
        prepared {:case-id "case-1"
                  :project-id "project"
                  :repo-id "repo"
                  :worktreeRoot worktree
                  :input {:queryText "broken app"}
                  :coverage {:declaredSourceKinds []}}
        opts {:out out
              :agent-id "agent"
              :mode "agraph"}]
    (with-redefs [store/with-node (fn [_ f]
                                    (f {:indexed? (atom false)}))
                  project/index-project! (fn [xtdb _project _opts]
                                           (reset! (:indexed? xtdb) true)
                                           {:status "completed"})
                  project/infer-project! (fn [_xtdb _project]
                                           {:status "completed"})
                  context/context-packet (fn [xtdb query-text _opts]
                                           (if @(:indexed? xtdb)
                                             {:schema "agraph.context/v1"
                                              :query query-text
                                              :docs []
                                              :entities []
                                              :edges []
                                              :warnings []
                                              :search {:instrumentation {:search-docs 1}}
                                              :candidateFiles [{:path "src/app.clj"
                                                                :rank 1
                                                                :score 1.0
                                                                :targetKind "chunk"
                                                                :label "app/broken"}]}
                                             {:schema "agraph.context/v1"
                                              :query query-text
                                              :docs []
                                              :entities []
                                              :edges []
                                              :warnings []
                                              :search {:instrumentation {:search-docs 0}}
                                              :candidateFiles []}))
                  benchmark/context-packet->agent-hints (fn [_prepared packet _opts]
                                                          {:schema benchmark/agent-hints-schema
                                                           :selection {:candidateFiles (count (:candidateFiles packet))}
                                                           :topFiles (:candidateFiles packet)})]
      (let [result (#'benchmark/prepare-agent-graph-and-artifacts! suite
                                                                   case
                                                                   prepared
                                                                   opts)
            context-json (json/read-json
                          (slurp (get-in result [:artifacts :context-path]))
                          :key-fn keyword)
            hints-json (json/read-json
                        (slurp (get-in result [:artifacts :hints-path]))
                        :key-fn keyword)]
        (is (= {:status "completed"} (get-in result [:summary :indexSummary])))
        (is (= 1 (get-in context-json [:search :instrumentation :search-docs])))
        (is (= [{:path "src/app.clj"
                 :rank 1
                 :score 1.0
                 :targetKind "chunk"
                 :label "app/broken"}]
               (:candidateFiles context-json)))
        (is (= 1 (get-in hints-json [:selection :candidateFiles])))))))
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
                   "test -n \"$AGRAPH_BENCH_CASE_FINGERPRINT\"\n"
                   "test -n \"$AGRAPH_BENCH_AGENT_INPUT_FINGERPRINT\"\n"
                   "cat > \"$AGRAPH_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[{\"kind\":\"command\","
                   "\"value\":\"rg broken src/app.clj\"}]}],"
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
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "Only include files likely to require edits in suspectedFiles"))
          (is (= ["schema"
                  "caseId"
                  "caseFingerprint"
                  "agentId"
                  "mode"
                  "selection"
                  "parserWorker"
                  "suspectedFiles"
                  "suspectedSymbols"
                  "commands"
                  "warnings"
                  "summary"]
                 (get (json/read-json
                       (slurp (get-in run [:artifacts :outputSchemaPath]))
                       :key-fn keyword)
                      :required)))
          (is (= {:type "string"}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :caseFingerprint])))
          (is (= {:type "string"}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :agentInputFingerprint])))
          (is (= {:type "string"
                  :enum ["agraph" "shell-only" "local-vector"]}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :mode])))
          (is (= {:type "object"
                  :additionalProperties false
                  :required ["mode" "source"]
                  :properties {:mode {:type "string"}
                               :source {:type "string"}}}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :parserWorker])))
          (is (not (contains?
                    (get-in (json/read-json
                             (slurp (get-in run [:artifacts :outputSchemaPath]))
                             :key-fn keyword)
                            [:properties
                             :suspectedFiles
                             :items
                             :properties])
                    :metrics)))
          (let [selection-schema (get-in (json/read-json
                                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                                          :key-fn keyword)
                                         [:properties :selection])]
            (is (= false (:additionalProperties selection-schema)))
            (is (= ["rawCandidateFiles"
                    "candidateFiles"
                    "coverageFilteredCandidateFiles"
                    "limit"
                    "coverageSourceKinds"]
                   (:required selection-schema)))
            (is (= {:type "integer"
                    :minimum 0}
                   (get-in selection-schema [:properties :rawCandidateFiles])))
            (is (= {:type ["integer" "null"]
                    :minimum 0}
                   (get-in selection-schema [:properties :limit]))))
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
(deftest external-agent-result-preserves-identity-mismatches
  (let [root (temp-dir "agraph-bench-agent-run-identity-repo")
        out (temp-dir "agraph-bench-agent-run-identity-out")
        suite-dir (temp-dir "agraph-bench-agent-run-identity-suite")
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
              (pr-str {:id "agent-run-identity-fixture"
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
              (str "cat > \"$AGRAPH_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"caseId\":\"case-2\","
                   "\"caseFingerprint\":\"sha256:old-case\","
                   "\"agentId\":\"identity-agent\","
                   "\"mode\":\"shell-only\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"suspectedSymbols\":[],"
                   "\"commands\":[\"rg broken src/app.clj\"],"
                   "\"warnings\":[],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "identity-agent"
                                                   :mode "shell-only"
                                                   :prompt-profile "fast"
                                                   :command (str "sh " script-path)})
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "identity-agent"})
              warnings (get-in report [:results 0 :agentOutput :identityWarnings])]
          (is (= 1 (:completed result)))
          (is (= ["agent result caseId case-2 does not match expected case case-1"
                  (str "agent result caseFingerprint sha256:old-case does not match expected case fingerprint "
                       (get-in report [:results 0 :caseFingerprint]))]
                 warnings))
          (is (= {:identityMismatchRuns 1
                  :identityMismatchCaseIds ["case-1"]
                  :identityMismatches 2}
                 (select-keys (:agentDiagnostics report)
                              [:identityMismatchRuns
                               :identityMismatchCaseIds
                               :identityMismatches]))))))))
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
                :drilldowns [{:kind :explore
                              :label "Continue primary graph exploration"
                              :command "agraph ask 'broken app' --project fixture"
                              :mcpTool "agraph_explore"
                              :mcpArgs {:query "broken app"
                                        :projectId "fixture"}}]
                :warnings ["Context warning."]
                :answerability {:next ["Run agraph packages --project fixture --json"]
                                :nextActions [{:kind :dependencies
                                               :command "agraph packages --project fixture --json"}]}
                :architecture {:validationGaps [{:plane "dependencies"
                                                 :status "missing"
                                                 :nextActions [{:kind :dependency-review
                                                                :command "agraph sync project.edn --check --enqueue"}]}]}
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
    (is (= ["agraph ask 'broken app' --project fixture"
            "Run agraph packages --project fixture --json"
            "agraph packages --project fixture --json"
            "agraph sync project.edn --check --enqueue"]
           (:commands result)))
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
(deftest context-packet-agent-hints-respect-declared-source-coverage
  (let [root (temp-dir "agraph-bench-hints-source-coverage")
        _ (spit-file! root ".github/ISSUE_TEMPLATE/bug_report.md" "bug report\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "project"
                  :worktreeRoot root
                  :coverage {:declaredSourceKinds ["code" "python"]
                             :missingDeclaredSourceKinds ["python"]}}
        packet {:query "bug report app"
                :sourceCoverage {:schema "agraph.source-coverage.context/v1"
                                 :totals {:indexedFiles 2
                                          :skippedFiles 2
                                          :diagnostics 2}
                                 :skippedByExtension [{:extension ".bin"
                                                       :count 1}]
                                 :skippedByReason [{:reason "binary"
                                                    :count 1}]
                                 :indexedConnectivity {:indexedFiles 2
                                                       :nodes 1
                                                       :edges 0
                                                       :connectedFiles 0
                                                       :crossFileConnectedFiles 0
                                                       :isolatedFiles 2}}
                :auditScopes [{:kind "unclassified-extractor"
                               :basis "indexed-graph"
                               :supportedFiles 1
                               :skippedFiles 1
                               :facts 2
                               :diagnostics 1
                               :topEvidenceTypes [{:kind "panel"
                                                   :count 1}]
                               :samples [{:path "flows/home.panel"
                                          :section "files"}]}]
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
        hints (benchmark/context-packet->agent-hints prepared packet {})]
    (is (= ["src/app.clj"]
           (mapv :path (:topFiles hints))))
    (is (= [1]
           (mapv :rank (:topFiles hints))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit 20
            :coverageSourceKinds ["code" "python"]}
           (:selection hints)))
    (is (= [{:kind "coverage-filtered-candidate-files"
             :severity "info"
             :message "Declared source coverage filtered candidate files out of the agent shortlist."
             :coverageSourceKinds ["code" "python"]
             :rawCandidateFiles 2
             :candidateFiles 1
             :filteredCandidateFiles 1}
            {:kind "missing-declared-source-kinds"
             :severity "warning"
             :message "The benchmark case declares source kinds with no scoreable indexed files."
             :sourceKinds ["python"]}
            {:kind "source-extraction-diagnostics"
             :severity "warning"
             :message "Indexed source coverage contains extraction diagnostics; inspect sourceCoverage.diagnostics.samples."
             :diagnostics 2}
            {:kind "source-skipped-files"
             :severity "info"
             :message "Indexed source coverage contains skipped files; inspect sourceCoverage skipped breakdowns before treating missing facts as absent."
             :skippedFiles 2
             :skippedByExtension [{:extension ".bin"
                                   :count 1}]
             :skippedByReason [{:reason "binary"
                                :count 1}]}
            {:kind "isolated-indexed-files"
             :severity "info"
             :message "Indexed source coverage contains files without active graph edges; inspect sourceCoverage.indexedConnectivity."
             :isolatedFiles 2
             :connectedFiles 0
             :crossFileConnectedFiles 0}
            {:kind "audit-scope-trust-boundary"
             :severity "warning"
             :message "Audit scope contains skipped files, extractor diagnostics, or unclassified extractor rows."
             :scope "unclassified-extractor"
             :supportedFiles 1
             :skippedFiles 1
             :diagnostics 1
             :facts 2}]
           (:diagnostics hints)))
    (is (= [{:kind "unclassified-extractor"
             :basis "indexed-graph"
             :supportedFiles 1
             :skippedFiles 1
             :facts 2
             :diagnostics 1
             :topEvidenceTypes [{:kind "panel"
                                 :count 1}]
             :samples [{:path "flows/home.panel"
                        :section "files"}]}]
           (:auditScopes hints)))
    (is (not (contains? hints :groundTruth)))
    (is (not (contains? hints :inputHints)))))
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

(deftest candidate-file-evidence-preserves-line-ranges
  (let [root (temp-dir "agraph-bench-candidate-file-lines")
        _ (spit-file! root "src/adjacent.clj" "(ns adjacent)\n(defn open-root [] nil)\n")
        packet {:query "open root"
                :candidateFiles [{:path "src/adjacent.clj"
                                  :rank 3
                                  :score 0.7
                                  :targetKind "chunk"
                                  :label "open root"
                                  :sourceLine 2
                                  :endLine 4}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["candidate-file:src/adjacent.clj rank=3 lines 2-4 targetKind=chunk label=\"open root\" score=0.7"]
           (get-in files [0 :evidence])))
    (is (= "AGraph retrieved candidate file src/adjacent.clj lines 2-4 from result rank 3."
           (get-in files [0 :reason])))))

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
    (is (not (contains? (first files) :graph-neighbor-score)))
    (is (= 0.6 (get-in files [0 :metrics :graphNeighborScore])))
    (is (= 2 (get-in files [0 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
(deftest file-ranking-uses-architecture-runtime-evidence
  (let [root (temp-dir "agraph-bench-architecture-runtime")
        _ (spit-file! root "config/runtime.env" "DATABASE_URL=postgres://example\n")
        packet {:query "database runtime config"
                :architecture {:runtimeEvidence [{:id "evidence:database-url"
                                                  :path "config/runtime.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :normalizedValue "database-url"
                                                  :score 1.2}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["config/runtime.env"]
           (mapv :path files)))
    (is (= ["architecture-evidence:runtimeEvidence:config/runtime.env kind=env-var fileKind=env label=\"DATABASE_URL\" score=1.2"]
           (get-in files [0 :evidence])))
    (is (= "AGraph architecture runtimeEvidence row evidence:database-url references config/runtime.env."
           (get-in files [0 :reason])))
    (is (= {:firstSourceRank 701
            :supportCount 1
            :docCount 0
            :entityCount 0
            :candidateFileCount 1
            :retrievedSourceCount 0
            :exactPathSourceCount 0
            :maxConfidence 1.0
            :rankScore 1.62
            :matchedTokenCount 3
            :definitionKinds ["env-var"]}
           (get-in files [0 :metrics])))))
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
(deftest file-ranking-uses-mechanical-graph-and-lexical-score-support
  (let [root (temp-dir "agraph-bench-candidate-graph-lexical-support")
        _ (spit-file! root "src/plain.js" "export const plain = true;\n")
        _ (spit-file! root "src/graph.js" "export const graph = true;\n")
        packet {:query "native proxy boundary"
                :candidateFiles [{:path "src/plain.js"
                                  :rank 1
                                  :score 0.9
                                  :targetKind "node"
                                  :label "plain"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.0}}
                                 {:path "src/graph.js"
                                  :rank 50
                                  :score 0.65
                                  :targetKind "node"
                                  :label "graph edge"
                                  :scoreComponents {:lexical 0.45
                                                    :graph 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/graph.js" "src/plain.js"]
           (mapv :path files)))
    (is (= 0.6 (get-in files [0 :metrics :graphNeighborScore])))
    (is (pos? (get-in files [0 :metrics :graphNeighborBoost])))))
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
(deftest file-ranking-uses-identity-compound-token-pairs
  (let [root (temp-dir "agraph-bench-identity-compound-token-pairs")
        _ (spit-file! root "scss/forms/_input-group.scss" ".input-group {}\n")
        _ (spit-file! root "scss/forms/_form-select.scss" ".form-select {}\n")
        packet {:query "form select border radius"
                :candidateFiles [{:path "scss/forms/_input-group.scss"
                                  :rank 1
                                  :score 1.4
                                  :targetKind "chunk"
                                  :label "form select small large border radius"}
                                 {:path "scss/forms/_form-select.scss"
                                  :rank 2
                                  :score 0.9
                                  :targetKind "chunk"
                                  :label ".form-select"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["scss/forms/_form-select.scss" "scss/forms/_input-group.scss"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedIdentityCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
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
(deftest limited-agent-result-preserves-declared-source-kind-diversity
  (let [root (temp-dir "agraph-bench-source-kind-diversity")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/other.clj" "(ns other)\n")
        _ (spit-file! root "config/.env" "DATABASE_URL=postgres://example\n")
        packet {:query "runtime configuration"
                :candidateFiles [{:path "src/app.clj"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "chunk"
                                  :label "runtime configuration"}
                                 {:path "src/other.clj"
                                  :rank 2
                                  :score 0.95
                                  :targetKind "chunk"
                                  :label "configuration helper"}
                                 {:path "config/.env"
                                  :rank 3
                                  :score 0.1
                                  :targetKind "node"
                                  :label "DATABASE_URL"}]}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :limit 2
                 :coverage {:declaredSourceKinds ["code" "env"]}})]
    (is (= ["src/app.clj" "config/.env"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 3
            :candidateFiles 3
            :coverageFilteredCandidateFiles 0
            :limit 2
            :coverageSourceKinds ["code" "env"]
            :candidateFileOnlyQuota 2
            :candidateFileOnlySelected 2}
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
                          "case_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseId\"])' \"$1\")\n"
                          "case_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseFingerprint\"])' \"$1\")\n"
                          "agent_input_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentInputFingerprint\"])' \"$1\")\n"
                          "agent_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentId\"])' \"$1\")\n"
                          "cat > \"$2\" <<JSON\n"
                          "{\"schema\":\"agraph.benchmark.agent-result/v2\","
                          "\"caseId\":\"$case_id\","
                          "\"caseFingerprint\":\"$case_fingerprint\","
                          "\"agentInputFingerprint\":\"$agent_input_fingerprint\","
                          "\"agentId\":\"$agent_id\","
                          "\"mode\":\"local-vector\","
                          "\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake local vector match\","
                          "\"evidence\":[\"fake-vector:src/app.clj\"]}],"
                          "\"suspectedSymbols\":[],\"commands\":[\"fake-vector-worker\"],"
                          "\"warnings\":[],\"summary\":\"fake vector result\"}\n"
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
          (is (= (:caseFingerprint baseline) (:caseFingerprint request)))
          (is (= (:agentInputFingerprint baseline)
                 (:agentInputFingerprint request)))
          (is (= "fake-local-model" (:model request)))
          (is (not (contains? request :groundTruth)))
          (is (= benchmark/agent-result-schema (get-in scored [:agent :schema])))
          (is (= "local-vector" (get-in scored [:agent :mode])))
          (is (empty? (get-in scored [:agent :warnings])))
          (is (= 1.0 (get-in scored [:scores :evidenceCitationRate])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))
(deftest local-vector-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "agraph-bench-local-vector-worker-repo")
        module-dir (temp-dir "agraph-bench-local-vector-worker-module")
        request-dir (temp-dir "agraph-bench-local-vector-worker-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "agraph.benchmark.local-vector-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "agraph-baseline-local-vector"
                       :mode "local-vector"
                       :worktreeRoot repo
                       :input {:title "broken app"
                               :body "The app returns the old value."}
                       :limit 2
                       :model "fake-local-model"})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! repo "docs/readme.md" "Quiet documentation.\n")
    (spit-file!
     module-dir
     "sentence_transformers/__init__.py"
     (str "class SentenceTransformer:\n"
          "    def __init__(self, name):\n"
          "        self.name = name\n"
          "    def encode(self, texts, batch_size=None, normalize_embeddings=True, show_progress_bar=False):\n"
          "        vectors = []\n"
          "        for text in texts:\n"
          "            if 'broken' in text.lower():\n"
          "                vectors.append([1.0, 0.0])\n"
          "            else:\n"
          "                vectors.append([0.0, 1.0])\n"
          "        return vectors\n"))
    (let [{:keys [exit err]} (shell/sh "env"
                                       (str "PYTHONPATH=" module-dir)
                                       "python3"
                                       "scripts/local-vector-baseline.py"
                                       request-path
                                       result-path
                                       "fake-local-model")]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "agraph-baseline-local-vector" (:agentId result)))
          (is (= "local-vector" (:mode result)))
          (is (= ["src/app.clj" "docs/readme.md"]
                 (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (seq (:commands result))))))))
