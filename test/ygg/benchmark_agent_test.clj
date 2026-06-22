(ns ygg.benchmark-agent-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-agent-run :as benchmark-agent-run]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-test-support :refer [commit! git! sh! spit-file! spit-json! temp-dir]]
            [ygg.context :as context]
            [ygg.extract :as extract]
            [ygg.project :as project]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- object-schema?
  [schema]
  (let [schema-type (:type schema)]
    (or (= "object" schema-type)
        (and (vector? schema-type)
             (some #{"object"} schema-type)))))

(defn- strict-schema-required-mismatches
  ([schema]
   (strict-schema-required-mismatches [] schema))
  ([path schema]
   (let [properties (:properties schema)
         required (set (map name (:required schema)))
         missing (when (and (object-schema? schema)
                            (= false (:additionalProperties schema))
                            (map? properties))
                   (->> (keys properties)
                        (remove #(contains? required (name %)))
                        (map name)
                        sort
                        vec))
         self (when (seq missing)
                [{:path path
                  :missing missing}])]
     (vec
      (concat
       self
       (mapcat (fn [[key child]]
                 (strict-schema-required-mismatches (conj path key) child))
               properties)
       (when-let [items (:items schema)]
         (strict-schema-required-mismatches (conj path :items) items)))))))

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
  (let [root (temp-dir "ygg-bench-agent-repo")
        out (temp-dir "ygg-bench-agent-out")
        suite-dir (temp-dir "ygg-bench-agent-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
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
              setup-command (get-in packet [:tools :ygg :setupCommand])]
          (is (= benchmark/agent-packet-schema (:schema packet)))
          (is (= benchmark/agent-result-schema
                 (get-in packet [:task :expectedResultSchema])))
          (is (str/starts-with? (:caseFingerprint packet) "sha256:"))
          (is (str/starts-with? (:agentInputFingerprint packet) "sha256:"))
          (is (not= (:caseFingerprint packet)
                    (:agentInputFingerprint packet)))
          (is (some #(str/includes? % "Only include files likely to require edits in suspectedFiles")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "coverageSourceKinds")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "runtime/config/setup file may need edits")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "exact repo-relative path")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "caseId, caseFingerprint, and agentInputFingerprint")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "do not carry over stale graph-health text")
                    (get-in packet [:task :rules])))
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
          (is (str/includes? setup-command "YGG_PARSER_WORKER='java'"))
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

(deftest agent-baseline-records-maintenance-activity-and-sync-inspect
  (let [root (temp-dir "ygg-bench-baseline-maintenance-repo")
        out (temp-dir "ygg-bench-baseline-maintenance-out")]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")
          suite {:id "suite"
                 :repos [{:id "repo"
                          :root root
                          :role :application}]
                 :cases [{:id "case-1"
                          :repo-id "repo"
                          :base-sha base-sha
                          :fix-sha base-sha
                          :coverage {:source-kinds [:code]}
                          :ground-truth {:localization-files ["src/app.clj"]}
                          :issue {:title "broken app"
                                  :body "Find the broken app function."}}]}
          result (benchmark/agent-baselines! suite {:out out
                                                    :now-ms 1000})
          baseline (first (:baselines result))
          score (json/read-json (slurp (get-in baseline
                                               [:artifacts :agentScorePath]))
                                :key-fn keyword)
          context-packet (json/read-json
                          (slurp (get-in baseline
                                         [:artifacts :contextPacketPath]))
                          :key-fn keyword)
          token-usage (get-in score [:agent :tokenUsage])
          family-by-name (into {}
                               (map (juxt :family identity))
                               (get-in baseline [:syncInspect :families]))]
      (is (= benchmark/agent-baselines-schema (:schema result)))
      (is (= "ygg-baseline-lexical" (:agentId baseline)))
      (is (= {:items 1
              :events 1
              :deleted-items 0
              :deleted-events 0}
             (:benchmarkActivity baseline)))
      (is (= "completed" (get-in score [:syncInspect :status])))
      (is (= :available (get-in family-by-name [:activity :status])))
      (is (= :available (get-in family-by-name [:validation-history :status])))
      (is (= :available (get-in family-by-name [:map-overlay :status])))
      (is (= true (:claimReady score)))
      (is (= true (:claimReady baseline)))
      (is (= "ygg-baseline-compact-surface-estimate"
             (:source token-usage)))
      (is (= (:inputTokens token-usage) (:totalTokens token-usage)))
      (is (= 0 (:outputTokens token-usage)))
      (is (= 0.0 (:costUsd token-usage)))
      (is (< (:inputTokens token-usage)
             (context/estimate-tokens context-packet)))
      (is (= "sync-inspect"
             (get-in baseline
                     [:maintenancePreflight :checks :syncCheck :source]))))))

(deftest benchmark-parser-worker-profile-is-explicit-and-bindable
  (is (= {:mode "java"
          :source "option"}
         (#'benchmark/parser-worker-profile {:parser-worker "Java"})))
  (is (= {:mode "none"
          :source "default"}
         (#'benchmark/agent-score-parser-worker-profile
          {}
          {:parserWorker {:mode "none"
                          :source "unknown"}})))
  (extract/with-parser-worker-mode
    "dotnet"
    (is (= "java"
           (#'benchmark/with-benchmark-parser-worker
            {:parser-worker "java"}
            #(extract/parser-worker-mode))))))
(deftest skip-existing-agent-baselines-match-parser-worker-profile
  (let [out (temp-dir "ygg-bench-parser-worker-skip")
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
               :agentResultContractVersion
               benchmark/agent-result-contract-version
               :case-id "case-1"
               :caseFingerprint (#'benchmark/case-fingerprint suite case)
               :agentInputFingerprint (#'benchmark/agent-input-fingerprint suite case)
               :agent {:agentId "ygg-baseline-lexical"
                       :schema benchmark/agent-result-schema
                       :mode "ygg"
                       :agentInputFingerprint (#'benchmark/agent-input-fingerprint suite case)}
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
                 {:agent-id "ygg-baseline-lexical"
                  :mode "ygg"
                  :result-path result-path})))
    (is (= 1
           (count (#'benchmark/current-agent-score-artifacts
                   suite
                   case
                   dotnet-opts
                   {:agent-id "ygg-baseline-lexical"
                    :mode "ygg"
                    :result-path result-path}))))))
(deftest agent-run-env-carries-explicit-parser-worker-profile
  (let [env (#'benchmark/agent-run-env
             {:suite-id "suite"
              :case-id "case-1"
              :repo-id "repo"
              :project-id "project"
              :mode "ygg"
              :worktreeRoot "/tmp/worktree"
              :artifacts {:packetPath "/tmp/packet.json"
                          :projectConfig "/tmp/project.edn"
                          :xtdbPath "/tmp/xtdb"}}
             "/tmp/result.json"
             "/tmp/prompt.txt"
             "/tmp/schema.json"
             {:agent-id "agent"
              :parser-worker "dotnet"})]
    (is (= "dotnet" (get env "YGG_BENCH_PARSER_WORKER")))
    (is (= "dotnet" (get env "YGG_PARSER_WORKER")))))
(deftest ygg-agent-run-prompt-points-agents-at-architecture-hints
  (let [root (temp-dir "ygg-bench-agent-prompt")
        result-path (.getPath (io/file root "result.json"))
        schema-path (.getPath (io/file root "schema.json"))
        prompt (#'benchmark/agent-run-prompt
                {:suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :project-id "project"
                 :mode "ygg"
                 :worktreeRoot "/tmp/worktree"
                 :task {:objective "Find likely edit locations."}
                 :artifacts {:packetPath "/tmp/packet.json"
                             :projectConfig "/tmp/project.edn"
                             :xtdbPath "/tmp/xtdb"
                             :yggHintsPath "/tmp/hints.json"
                             :yggContextPath "/tmp/context.json"}}
                result-path
                schema-path
                {:agent-id "agent"
                 :prompt-profile "fast"})]
    (is (str/includes? prompt "Yggdrasil hints JSON: /tmp/hints.json"))
    (is (str/includes? prompt "Yggdrasil context JSON: /tmp/context.json"))
    (is (str/includes? prompt "`topFiles`, `architecture`, and `auditScopes`"))
    (is (str/includes? prompt
                       "`evidence`, `sourceCoverage`, and `diagnostics`"))
    (is (str/includes? prompt
                       "`commands` as bounded follow-up checks"))
    (is (str/includes? prompt
                       "`architecture.validationGaps.nextActions`"))
    (is (str/includes? prompt "coverageSourceKinds"))
    (is (str/includes? prompt "runtime/config/setup file may need edits"))
    (is (str/includes? prompt "exact repo-relative path"))
    (is (str/includes? prompt "exact evidence path and label"))
    (is (str/includes? prompt "caseId, caseFingerprint, and agentInputFingerprint"))
    (is (str/includes? prompt "do not carry over stale graph-health text"))))
(deftest ygg-agent-run-builds-context-artifacts-after-indexing
  (let [out (temp-dir "ygg-bench-agent-run-context-order")
        worktree (temp-dir "ygg-bench-agent-run-context-worktree")
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
              :mode "ygg"}]
    (with-redefs [store/with-node (fn [_ f]
                                    (f {:indexed? (atom false)}))
                  project/index-project! (fn [xtdb _project _opts]
                                           (reset! (:indexed? xtdb) true)
                                           {:status "completed"})
                  project/infer-project! (fn [_xtdb _project]
                                           {:status "completed"})
                  context/context-packet (fn [xtdb query-text _opts]
                                           (if @(:indexed? xtdb)
                                             {:schema "ygg.context/v1"
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
                                             {:schema "ygg.context/v1"
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
  (let [root (temp-dir "ygg-bench-agent-run-repo")
        out (temp-dir "ygg-bench-agent-run-out")
        suite-dir (temp-dir "ygg-bench-agent-run-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
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
                   "test -f \"$YGG_BENCH_PACKET\"\n"
                   "test -f \"$YGG_BENCH_PROMPT\"\n"
                   "test -f \"$YGG_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$YGG_BENCH_PROMPT_PROFILE\" = fast\n"
                   "grep -q 'YGG_BENCH_RESULT' \"$YGG_BENCH_PROMPT\"\n"
                   "grep -q 'Fast Localization Profile' \"$YGG_BENCH_PROMPT\"\n"
                   "grep -q 'suspectedFiles' \"$YGG_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$YGG_BENCH_CASE_ID\" = case-1\n"
                   "test -n \"$YGG_BENCH_CASE_FINGERPRINT\"\n"
                   "test -n \"$YGG_BENCH_AGENT_INPUT_FINGERPRINT\"\n"
                   "cat > \"$YGG_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[{\"kind\":\"command\","
                   "\"value\":\"rg broken src/app.clj\"}]}],"
                   "\"warnings\":[\"agent note\"],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"
                   "printf 'ran %s\\n' \"$YGG_BENCH_AGENT_ID\"\n"))
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
                             "YGG_BENCH_OUTPUT_SCHEMA"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "final response"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "Only include files likely to require edits in suspectedFiles"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "coverageSourceKinds"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "runtime/config/setup file may need edits"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "exact repo-relative path"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "caseId, caseFingerprint, and agentInputFingerprint"))
          (let [output-schema (json/read-json
                               (slurp (get-in run [:artifacts :outputSchemaPath]))
                               :key-fn keyword)]
            (is (= ["schema"
                    "caseId"
                    "caseFingerprint"
                    "agentInputFingerprint"
                    "agentId"
                    "mode"
                    "selection"
                    "parserWorker"
                    "suspectedFiles"
                    "suspectedSymbols"
                    "commands"
                    "warnings"
                    "summary"
                    "decision"
                    "tokenUsage"]
                   (:required output-schema)))
            (is (= [] (strict-schema-required-mismatches output-schema))))
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
                  :enum ["ygg" "shell-only" "local-vector" "codebase-memory"]}
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
          (is (str/includes?
               (get-in (json/read-json
                        (slurp (get-in run [:artifacts :outputSchemaPath]))
                        :key-fn keyword)
                       [:properties
                        :suspectedFiles
                        :items
                        :properties
                        :evidence
                        :description])
               "exact repo-relative path"))
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
          (is (= ["object" "null"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :decision :type])))
          (is (= ["kind" "choices" "risks" "followups"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :decision :required])))
          (is (= ["object" "null"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :tokenUsage :type])))
          (let [prompt-text (slurp (get-in run [:artifacts :promptPath]))]
            (is (not (str/includes? prompt-text "changedFiles")))
            (is (str/includes? prompt-text "\"tokenUsage\": null"))
            (is (not (str/includes? prompt-text "\"totalTokens\": 0"))))
          (is (str/includes? (slurp (get-in run [:artifacts :stdoutPath]))
                             "ran script-agent"))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores run) (:scores skipped)))
          (is (= 1 (:runs report)))
          (is (= "script-agent" (get-in report [:results 0 :agent :agentId]))))))))
(deftest external-agent-result-preserves-identity-mismatches
  (let [root (temp-dir "ygg-bench-agent-run-identity-repo")
        out (temp-dir "ygg-bench-agent-run-identity-out")
        suite-dir (temp-dir "ygg-bench-agent-run-identity-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
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
              (str "cat > \"$YGG_BENCH_RESULT\" <<'JSON'\n"
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
                   "\"tokenUsage\":{\"inputTokens\":0,\"outputTokens\":0,"
                   "\"totalTokens\":0,\"costUsd\":0.0,"
                   "\"source\":\"agent-report\"},"
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
                                                          :agent-id "identity-agent"
                                                          :allow-unverified-scores? true})
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
  (let [root (temp-dir "ygg-bench-agent-fail-repo")
        out (temp-dir "ygg-bench-agent-fail-out")
        suite-dir (temp-dir "ygg-bench-agent-fail-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
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

(deftest external-agent-token-usage-sidecar-is-merged-into-result
  (let [root (temp-dir "ygg-bench-agent-token-repo")
        out (temp-dir "ygg-bench-agent-token-out")
        suite-dir (temp-dir "ygg-bench-agent-token-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-token-fixture"
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
              (str "cat > \"$YGG_BENCH_RESULT\" <<JSON\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"caseId\":\"case-1\","
                   "\"caseFingerprint\":\"$YGG_BENCH_CASE_FINGERPRINT\","
                   "\"agentInputFingerprint\":\"$YGG_BENCH_AGENT_INPUT_FINGERPRINT\","
                   "\"agentId\":\"token-agent\","
                   "\"mode\":\"shell-only\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"suspectedSymbols\":[],"
                   "\"commands\":[\"rg broken src/app.clj\"],"
                   "\"warnings\":[],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"
                   "cat > \"$YGG_BENCH_TOKEN_USAGE\" <<'JSON'\n"
                   "{\"input_tokens\":120,\"output_tokens\":30,"
                   "\"cost_usd\":0.01,\"provider\":\"test-provider\","
                   "\"model\":\"test-model\"}\n"
                   "JSON\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "token-agent"
                                                   :mode "shell-only"
                                                   :command (str "sh " script-path)})
              run (first (:runs result))
              score (json/read-json
                     (slurp (get-in run [:artifacts :agentScorePath]))
                     :key-fn keyword)
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "token-agent"})]
          (is (= "passed" (:status run)))
          (is (.isFile (io/file (get-in run [:artifacts :tokenUsagePath]))))
          (is (= {:inputTokens 120
                  :outputTokens 30
                  :totalTokens 150
                  :costUsd 0.01
                  :source "sidecar"
                  :model "test-model"
                  :provider "test-provider"}
                 (get-in score [:agent :tokenUsage])))
          (is (= {:inputTokens 120
                  :outputTokens 30
                  :totalTokens 150
                  :costUsd 0.01}
                 (get-in report [:agentDiagnostics :tokenTelemetry]))))))))
(deftest context-packet-can-be-written-as-agent-result
  (let [root (temp-dir "ygg-bench-context-result")
        _ (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        packet {:query "broken app"
                :drilldowns [{:kind :explore
                              :label "Continue primary graph exploration"
                              :command "ygg ask 'broken app' --project fixture"
                              :mcpTool "ygg_explore"
                              :mcpArgs {:query "broken app"
                                        :projectId "fixture"}}]
                :warnings ["Context warning."]
                :evidence {:nextActions [{:kind :dependencies
                                          :command "ygg packages --project fixture --json"}]}
                :architecture {:validationGaps [{:plane "dependencies"
                                                 :status "missing"
                                                 :nextActions [{:kind :dependency-review
                                                                :command "ygg sync project.edn --check --enqueue"}]}]}
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
                {:agent-id "ygg-baseline-lexical"
                 :case-id "case-1"
                 :root root})]
    (is (= benchmark/agent-result-schema (:schema result)))
    (is (= "case-1" (:caseId result)))
    (is (= "ygg-baseline-lexical" (:agentId result)))
    (is (= "ygg" (:mode result)))
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
             :reason (str "Yggdrasil context doc \"broken\" from src/app.clj lines 2-4 "
                          "with provenance retrieved-doc. Additional Yggdrasil evidence: "
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
             :reason "Yggdrasil graph entity \"db\" references src/db.clj."}]
           (:suspectedFiles result)))
    (is (= [{:name "broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"
             :confidence 1.0
             :reason "Yggdrasil context doc \"broken\" references src/app.clj lines 2-4."
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]}
            {:name "missing"
             :path "src/missing.clj"
             :rank 3
             :kind "unknown"
             :confidence 1.0
             :reason "Yggdrasil context doc \"missing\" references src/missing.clj."
             :evidence ["context-doc:src/missing.clj provenance=retrieved-doc"]}]
           (:suspectedSymbols result)))
    (is (= ["ygg ask 'broken app' --project fixture"
            "ygg packages --project fixture --json"
            "ygg sync project.edn --check --enqueue"]
           (:commands result)))
    (is (= ["Context warning."] (:warnings result)))
    (let [expected-input-tokens (context/estimate-tokens
                                 (dissoc result :tokenUsage))]
      (is (= {:inputTokens expected-input-tokens
              :outputTokens 0
              :totalTokens expected-input-tokens
              :costUsd 0.0
              :source "ygg-baseline-compact-surface-estimate"}
             (:tokenUsage result))))
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
               :reason (str "Yggdrasil context doc \"broken\" from src/app.clj lines 2-4 "
                            "with provenance retrieved-doc. Additional Yggdrasil evidence: "
                            "1 more matching row.")}]
             (:suspectedFiles limited)))
      (is (= {:rawCandidateFiles 2
              :candidateFiles 2
              :coverageFilteredCandidateFiles 0
              :limit 1
              :coverageSourceKinds []}
             (:selection limited)))
      (is (= 2 (count (:suspectedSymbols limited)))))))

(deftest context-packet-agent-result-emits-structural-decision-choices
  (let [root (temp-dir "ygg-bench-context-decision")
        _ (spit-file! root "package.json" "{\"dependencies\":{\"astro\":\"latest\"}}\n")
        _ (spit-file! root "site/astro.config.ts" "import { defineConfig } from 'astro/config'\n")
        packet {:query "plan docs search integration"
                :docs [{:source {:path "package.json"
                                 :heading "dependencies"
                                 :definitionKind :manifest
                                 :lines [1 1]}
                        :score 3.0
                        :snippet "astro dependency"
                        :provenance "retrieved-doc"}]}
        decision-candidates [{:id "plan-config-and-manifest"
                              :kind "change-plan"
                              :paths ["site/astro.config.ts"
                                      "package.json"]}
                             {:id "plan-manifest-only"
                              :kind "change-plan"
                              :paths ["package.json"]}
                             {:id "plan-config-only"
                              :kind "change-plan"
                              :paths ["site/astro.config.ts"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :case-id "case-1"
                 :caseFingerprint "sha256:test-case"
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates})
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["package.json"]
                                :unsupportedGroundTruthFiles []}
                  :decisionCandidates decision-candidates
                  :decisionGroundTruth {:kind "change-plan"
                                        :required ["plan-config-and-manifest"]
                                        :forbidden ["plan-manifest-only"
                                                    "plan-config-only"]}}
        scored (benchmark-agent-score/score-agent-result prepared result)]
    (is (= "change-plan" (get-in result [:decision :kind])))
    (is (= "include" (get-in choices-by-id ["plan-config-and-manifest" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-manifest-only" :status])))
    (is (= "defer" (get-in choices-by-id ["plan-config-only" :status])))
    (is (some #(= "site/astro.config.ts" (:path %))
              (:suspectedFiles result)))
    (is (= 2 (get-in (some #(when (= "site/astro.config.ts" (:path %)) %)
                           (:suspectedFiles result))
                     [:metrics :decisionCandidateCount])))
    (is (nil? (get-in result [:selection :decisionSupportedFileSelected])))
    (is (= ["Ygg baseline ranked file package.json at rank 1."]
           (get-in choices-by-id ["plan-config-and-manifest" :evidence])))
    (is (= 1.0 (get-in scored [:scores :decisionRecall])))
    (is (= 1.0 (get-in scored [:scores :decisionPrecision])))
    (is (= 1.0 (get-in scored [:scores :decisionF1])))
    (is (= 1.0 (get-in scored [:scores :decisionEvidenceCitationRate])))))

(deftest decision-candidate-paths-contribute-to-file-ranking
  (let [root (temp-dir "ygg-bench-decision-candidate-path-rank")
        _ (doseq [path ["src/noise_one.cs"
                        "src/noise_two.cs"
                        "src/noise_three.cs"
                        "src/core.cs"
                        "tests/core_test.cs"]]
            (spit-file! root path "namespace Demo;\n"))
        packet {:query "plan jsonb string handling"
                :candidateFiles [{:path "src/noise_one.cs"
                                  :rank 1
                                  :score 4.3
                                  :targetKind "node"
                                  :label "Demo.NoiseOne.JsonString"
                                  :scoreComponents {:sourceGraph 4.3
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/noise_two.cs"
                                  :rank 2
                                  :score 4.2
                                  :targetKind "node"
                                  :label "Demo.NoiseTwo.JsonString"
                                  :scoreComponents {:sourceGraph 4.2
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/noise_three.cs"
                                  :rank 3
                                  :score 4.1
                                  :targetKind "node"
                                  :label "Demo.NoiseThree.JsonString"
                                  :scoreComponents {:sourceGraph 4.1
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/core.cs"
                                  :rank 15
                                  :score 4.0
                                  :targetKind "node"
                                  :label "Demo.Core.JsonString"
                                  :scoreComponents {:sourceGraph 4.0
                                                    :lexical 0.45
                                                    :graph 0.2}}]}
        decision-candidates [{:id "plan-core-and-tests"
                              :kind "change-plan"
                              :label "Plan core and tests"
                              :paths ["src/core.cs" "tests/core_test.cs"]}
                             {:id "plan-core-only"
                              :kind "change-plan"
                              :label "Plan core only"
                              :paths ["src/core.cs"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates
                 :limit 5})
        files (:suspectedFiles result)
        file-by-path (into {} (map (juxt :path identity)) files)
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))]
    (is (contains? file-by-path "src/core.cs"))
    (is (<= (:rank (get file-by-path "src/core.cs")) 5))
    (is (= 2 (get-in file-by-path ["src/core.cs" :metrics :decisionCandidateCount])))
    (is (= "include" (get-in choices-by-id ["plan-core-and-tests" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-core-only" :status])))))

(deftest fully-supported-decision-candidates-compact-suspected-files
  (let [root (temp-dir "ygg-bench-supported-decision-compact")
        _ (doseq [path ["src/noise.cs" "src/core.cs" "tests/core_test.cs"]]
            (spit-file! root path "namespace Demo;\n"))
        packet {:query "plan jsonb string handling"
                :candidateFiles [{:path "src/noise.cs"
                                  :rank 1
                                  :score 9.0
                                  :targetKind "node"
                                  :label "Demo.Noise.JsonString"}
                                 {:path "src/core.cs"
                                  :rank 7
                                  :score 3.0
                                  :targetKind "node"
                                  :label "Demo.Core.JsonString"}
                                 {:path "tests/core_test.cs"
                                  :rank 8
                                  :score 3.0
                                  :targetKind "node"
                                  :label "Demo.CoreTests.JsonString"}]}
        decision-candidates [{:id "plan-core-and-tests"
                              :kind "change-plan"
                              :label "Plan core and tests"
                              :paths ["src/core.cs" "tests/core_test.cs"]}
                             {:id "plan-core-only"
                              :kind "change-plan"
                              :label "Plan core only"
                              :paths ["src/core.cs"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates
                 :limit 10})
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))]
    (is (= ["src/core.cs" "tests/core_test.cs"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2] (mapv :rank (:suspectedFiles result))))
    (is (= 2 (get-in result [:selection :decisionSupportedFileSelected])))
    (is (nil? (get-in result [:selection :candidateFileOnlyQuota])))
    (is (= "include" (get-in choices-by-id ["plan-core-and-tests" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-core-only" :status])))))

(deftest context-packet-agent-result-respects-declared-source-coverage
  (let [root (temp-dir "ygg-bench-source-coverage")
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
(deftest context-packet-agent-result-filters-single-root-map-by-scanned-kind
  (let [root (temp-dir "ygg-bench-single-root-source-coverage")
        _ (spit-file! root "site/src/pages/index.astro" "---\n---\n<h1>Home</h1>\n")
        _ (spit-file! root "README.md" "# Docs\n")
        packet {:query "docs route"
                :candidateFiles [{:path "site/src/pages/index.astro"
                                  :repo "bootstrap"
                                  :rank 1
                                  :score 1.0
                                  :targetKind :node
                                  :label "/"}
                                 {:path "README.md"
                                  :repo "bootstrap"
                                  :rank 2
                                  :score 1.0
                                  :targetKind :node
                                  :label "Docs"}]}
        result (benchmark/context-packet->agent-result
                packet
                {:roots {"bootstrap" root}
                 :coverage {:declaredSourceKinds ["web-framework"]}})]
    (is (= ["site/src/pages/index.astro"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["web-framework"]}
           (:selection result)))))
(deftest context-packet-agent-result-prioritizes-declared-source-lanes
  (let [root (temp-dir "ygg-bench-source-lane-priority")
        _ (spit-file! root "src/first.clj" "(ns first)\n")
        _ (spit-file! root "src/second.clj" "(ns second)\n")
        _ (spit-file! root "config/.env" "DATABASE_URL=postgres://example\n")
        packet {:query "runtime configuration"
                :docs [{:source {:path "src/first.clj"
                                 :heading "runtime configuration"}
                        :score 4.0
                        :snippet "runtime configuration"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/second.clj"
                                 :heading "configuration helper"}
                        :score 3.5
                        :snippet "runtime configuration"
                        :provenance "retrieved-doc"}]
                :architecture {:runtimeEvidence [{:id "evidence:database-url"
                                                  :path "config/.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :score 1.0}]}}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :coverage {:declaredSourceKinds ["code" "env"]}})]
    (is (= ["src/first.clj" "config/.env" "src/second.clj"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2 3]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 3
            :candidateFiles 3
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds ["code" "env"]}
           (:selection result)))))
(deftest context-packet-agent-hints-respect-declared-source-coverage
  (let [root (temp-dir "ygg-bench-hints-source-coverage")
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
                :sourceCoverage {:schema "ygg.source-coverage.context/v1"
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
  (let [root (temp-dir "ygg-bench-extensionless-coverage")
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

(deftest agent-score-matches-multi-repo-suspected-files-by-repo-id
  (let [provider-root (temp-dir "ygg-agent-score-provider")
        consumer-root (temp-dir "ygg-agent-score-consumer")]
    (spit-file! provider-root "src/contract.clj" "(ns provider.contract)\n")
    (spit-file! consumer-root "src/contract.clj" "(ns consumer.contract)\n")
    (let [prepared {:suite-id "suite"
                    :case-id "case"
                    :repo-id "provider"
                    :project-id "project"
                    :caseFingerprint "sha256:case"
                    :agentInputFingerprint "sha256:input"
                    :baseSha "provider-base"
                    :fixSha "provider-fix"
                    :worktreeRoot provider-root
                    :worktreeRoots {"provider" provider-root
                                    "consumer" consumer-root}
                    :input {:queryText "contract update"}
                    :inputHints {}
                    :coverage {}
                    :groundTruth {:changedFiles [{:repo-id "provider"
                                                  :path "src/contract.clj"}
                                                 {:repo-id "consumer"
                                                  :path "src/contract.clj"}]
                                  :unsupportedGroundTruthFiles []}}
          agent-result {:schema benchmark/agent-result-schema
                        :caseId "case"
                        :caseFingerprint "sha256:case"
                        :agentInputFingerprint "sha256:input"
                        :agentId "codex"
                        :mode "shell-only"
                        :suspectedFiles [{:repoId "consumer"
                                          :path "src/contract.clj"
                                          :rank 1
                                          :confidence 0.9
                                          :reason "consumer side"
                                          :evidence ["consumer src/contract.clj"]}
                                         {:repoId "provider"
                                          :path "src/contract.clj"
                                          :rank 2
                                          :confidence 0.8
                                          :reason "provider side"
                                          :evidence ["provider src/contract.clj"]}]
                        :suspectedSymbols []
                        :commands []
                        :warnings []
                        :summary "ranked both repos"}
          scored (benchmark-agent-score/score-agent-result prepared agent-result)]
      (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
      (is (= [{:repo-id "consumer"
               :path "src/contract.clj"
               :rank 1}
              {:repo-id "provider"
               :path "src/contract.clj"
               :rank 2}]
             (mapv #(select-keys % [:repo-id :path :rank])
                   (get-in scored [:agent :topFiles]))))
      (is (= [{:repo-id "provider"
               :path "src/contract.clj"
               :rank 2
               :found? true}
              {:repo-id "consumer"
               :path "src/contract.clj"
               :rank 1
               :found? true}]
             (mapv #(select-keys % [:repo-id :path :rank :found?])
                   (get-in scored [:groundTruthRanks :files])))))))
(deftest file-ranking-uses-mechanical-query-token-coverage
  (let [root (temp-dir "ygg-bench-token-coverage")
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
  (let [root (temp-dir "ygg-bench-token-cap")
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
  (let [root (temp-dir "ygg-bench-candidate-files")
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
  (let [root (temp-dir "ygg-bench-candidate-file-lines")
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
    (is (= "Yggdrasil retrieved candidate file src/adjacent.clj lines 2-4 from result rank 3."
           (get-in files [0 :reason])))))

(deftest candidate-file-support-labels-contribute-to-file-ranking
  (let [root (temp-dir "ygg-bench-candidate-support-labels")
        _ (spit-file! root "src/candidate.cs" "namespace Demo.Tests;\n")
        packet {:query "type handler regression"
                :candidateFiles [{:path "src/candidate.cs"
                                  :rank 3
                                  :score 0.5
                                  :targetKind "node"
                                  :label "Demo.Tests"
                                  :supportLabels ["Demo.Tests/TypeHandlerTests.Regression"]}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        row (first (:suspectedFiles result))]
    (is (= "src/candidate.cs" (:path row)))
    (is (= 3 (get-in row [:metrics :matchedTokenCount])))
    (is (= 1 (get-in row [:metrics :matchedCompoundTokenPairCount])))
    (is (str/includes? (first (:evidence row))
                       "supportLabels=[\"Demo.Tests/TypeHandlerTests.Regression\"]"))))

(deftest file-ranking-caps-repeated-file-support-bonus
  (let [root (temp-dir "ygg-bench-repeated-file-support")
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
  (let [root (temp-dir "ygg-bench-candidate-graph")
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
  (let [root (temp-dir "ygg-bench-architecture-runtime")
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
    (is (= "Yggdrasil architecture runtimeEvidence row evidence:database-url references config/runtime.env."
           (get-in files [0 :reason])))
    (is (= {:firstSourceRank 701
            :supportCount 1
            :docCount 0
            :entityCount 0
            :candidateFileCount 1
            :retrievedSourceCount 0
            :exactPathSourceCount 0
            :maxConfidence 1.0
            :rankScore 1.2000000000000002
            :matchedTokenCount 3
            :definitionKinds ["env-var"]}
           (get-in files [0 :metrics])))))

(deftest file-ranking-normalizes-architecture-evidence-families
  (let [root (temp-dir "ygg-bench-architecture-evidence-family-score")
        _ (spit-file! root "lib/adapters/http.js" "import proxyFromEnv from 'proxy-from-env';\n")
        _ (spit-file! root "tests/unit/adapters/fetch.test.js" "process.env.SERVER_PORT = '3000';\n")
        packet {:query (str "Locate the boundary between Node native proxy handling and Axios "
                            "proxy rewriting. Return the adapter and tests and cite "
                            "environment-variable evidence.")
                :architecture {:runtimeEvidence [{:id "evidence:server-port"
                                                  :path "tests/unit/adapters/fetch.test.js"
                                                  :kind "env-var"
                                                  :fileKind "javascript"
                                                  :label "SERVER_PORT"
                                                  :normalizedValue "server-port"
                                                  :score 5.35}]
                               :dependencyEvidence [{:id "node:pkg:proxy-from-env:import:lib/adapters/http.js:5"
                                                     :path "lib/adapters/http.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :package "proxy-from-env"
                                                     :label "npm:proxy-from-env"
                                                     :ecosystem "npm"
                                                     :relation "imports-package"
                                                     :sourceLine 5
                                                     :score 1.75}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["lib/adapters/http.js" "tests/unit/adapters/fetch.test.js"]
           (mapv :path files)))
    (is (= ["architecture-evidence:dependencyEvidence:lib/adapters/http.js kind=package-import fileKind=javascript relation=imports-package label=\"npm:proxy-from-env\" score=1.75"]
           (get-in files [0 :evidence])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-architecture-deploy-evidence
  (let [root (temp-dir "ygg-bench-architecture-deploy")
        _ (spit-file! root "tests/docker-compose.yml" "services:\n  db:\n    image: postgres:alpine\n")
        packet {:query "postgres container runtime setup"
                :architecture {:deployEvidence [{:id "evidence:postgres-container"
                                                 :path "tests/docker-compose.yml"
                                                 :kind "container-image-consumer"
                                                 :fileKind "compose"
                                                 :label "postgres:alpine"
                                                 :normalizedValue "container-image:postgres"
                                                 :score 1.4}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/docker-compose.yml"]
           (mapv :path files)))
    (is (= ["architecture-evidence:deployEvidence:tests/docker-compose.yml kind=container-image-consumer fileKind=compose label=\"postgres:alpine\" score=1.4"]
           (get-in files [0 :evidence])))))
(deftest file-ranking-requires-lexical-support-for-graph-neighbor-boost
  (let [root (temp-dir "ygg-bench-candidate-graph-support")
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
  (let [root (temp-dir "ygg-bench-candidate-graph-lexical-support")
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
  (let [root (temp-dir "ygg-bench-candidate-graph-bounds")
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
  (let [root (temp-dir "ygg-bench-candidate-token-pairs")
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
  (let [root (temp-dir "ygg-bench-compound-token-pairs")
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
  (let [root (temp-dir "ygg-bench-identity-compound-token-pairs")
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

(deftest file-ranking-uses-identity-compound-token-spans
  (let [root (temp-dir "ygg-bench-identity-compound-token-spans")
        _ (spit-file! root "src/TargetEngineDescriptor.java" "class TargetEngineDescriptor {}\n")
        _ (spit-file! root "src/GenericDescriptor.java" "class GenericDescriptor {}\n")
        packet {:query "Trace TargetEngineDescriptor ownership"
                :candidateFiles [{:path "src/GenericDescriptor.java"
                                  :rank 1
                                  :score 1.1
                                  :targetKind "node"
                                  :label "GenericDescriptor.prepare"
                                  :supportLabels ["generic engine descriptor"]
                                  :scoreComponents {:lexical 0.6
                                                    :graph 1.0}}
                                 {:path "src/TargetEngineDescriptor.java"
                                  :rank 2
                                  :score 0.9
                                  :targetKind "node"
                                  :label "TargetEngineDescriptor.cleanUp"
                                  :supportLabels ["TargetEngineDescriptor"]
                                  :scoreComponents {:lexical 0.7
                                                    :graph 0.0}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/TargetEngineDescriptor.java" "src/GenericDescriptor.java"]
           (mapv :path files)))
    (is (<= 3 (get-in files [0 :metrics :matchedIdentityCompoundTokenSpanLength])))
    (is (pos? (get-in files [0 :metrics :identityCompoundTokenSpanScore])))))

(deftest file-ranking-uses-doc-identity-compound-token-pairs
  (let [root (temp-dir "ygg-bench-doc-identity-compound-token-pairs")
        _ (spit-file! root "tests/Demo/TypeHandlerTests.cs" "class TypeHandlerTests {}\n")
        _ (spit-file! root "tests/Demo/MiscTests.cs" "class MiscTests {}\n")
        packet {:query "type handler regression tests"
                :docs [{:source {:path "tests/Demo/MiscTests.cs"
                                 :heading "handler regression"}
                        :score 1.4
                        :snippet "handler regression"
                        :provenance "retrieved-doc"}
                       {:source {:path "tests/Demo/TypeHandlerTests.cs"
                                 :heading "RecordingTypeHandler.ParseWasCalled"}
                        :score 0.8
                        :snippet "parse was called"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/Demo/TypeHandlerTests.cs" "tests/Demo/MiscTests.cs"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedIdentityCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-doc-supported-compound-token-pairs
  (let [root (temp-dir "ygg-bench-doc-compound-token-pairs")
        _ (spit-file! root "migrations/schema.sql" "-- schema\n")
        _ (spit-file! root "migrations/db/init-scripts/post-setup.sql" "-- setup\n")
        packet {:query "post setup"
                :docs [{:source {:path "migrations/schema.sql"
                                 :heading "setup"}
                        :score 1.0
                        :snippet "setup"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "migrations/db/init-scripts/post-setup.sql"
                                 :heading "post setup"}
                        :score 0.85
                        :snippet "post setup"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["migrations/db/init-scripts/post-setup.sql"
            "migrations/schema.sql"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-candidate-support-label-density
  (let [root (temp-dir "ygg-bench-candidate-support-label-density")
        _ (spit-file! root "src/high-score.js" "export const value = 1;\n")
        _ (spit-file! root "src/dense-support.js" "export const value = 2;\n")
        packet {:query "adapter lifecycle"
                :candidateFiles [{:path "src/high-score.js"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "node"
                                  :label "adapter lifecycle"
                                  :supportLabels ["support-a"
                                                  "support-b"
                                                  "support-c"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.6}}
                                 {:path "src/dense-support.js"
                                  :rank 2
                                  :score 0.95
                                  :targetKind "node"
                                  :label "adapter lifecycle"
                                  :supportLabels ["support-a"
                                                  "support-b"
                                                  "support-c"
                                                  "support-d"]
                                  :scoreComponents {:sourceGraph 0.95
                                                    :lexical 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/dense-support.js"
            "src/high-score.js"]
           (mapv :path files)))
    (is (= 4 (get-in files [0 :metrics :candidateSupportLabelCount])))
    (is (pos? (get-in files [0 :metrics :candidateSupportLabelScore])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-source-graph-candidate-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-source-graph-candidate-rank")
        _ (spit-file! root "lib/adapters/http.js" "export default function httpAdapter() {}\n")
        _ (spit-file! root "tests/unit/core/AxiosError.test.js" "describe('AxiosError', () => {})\n")
        packet {:query "defer env proxy handling to Node HTTP adapter unit tests"
                :candidateFiles [{:path "tests/unit/adapters/http.test.js"
                                  :rank 1
                                  :score 6.4
                                  :targetKind "node"
                                  :label "tests.unit.adapters.http.test/createHttp2Axios"
                                  :scoreComponents {:sourceGraph 6.4}}
                                 {:path "lib/adapters/http.js"
                                  :rank 2
                                  :score 2.3
                                  :targetKind "file"
                                  :label "lib/adapters/http.js"
                                  :scoreComponents {:sourceGraph 2.3
                                                    :lexical 0.27}}
                                 {:path "lib/adapters/http.js"
                                  :rank 3
                                  :score 2.0
                                  :targetKind "package-import"
                                  :label "npm:proxy-from-env"
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.2}}
                                 {:path "tests/unit/core/AxiosError.test.js"
                                  :rank 40
                                  :score 2.1
                                  :targetKind "file"
                                  :label "tests/unit/core/AxiosError.test.js"
                                  :scoreComponents {:sourceGraph 2.1
                                                    :lexical 0.27}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["lib/adapters/http.js"
            "tests/unit/core/AxiosError.test.js"]
           (mapv :path files)))
    (is (= 2 (get-in files [0 :metrics :candidateSourceRank])))
    (is (> (get-in files [0 :metrics :candidateSourceRankScore])
           5.0))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-diversifies-repeated-root-definition-kind
  (let [root (temp-dir "ygg-bench-root-kind-diversity")
        _ (doseq [path ["consumer/logs.go"
                        "consumer/metrics.go"
                        "connector/connector.go"]]
            (spit-file! root path "package fixture\n"))
        packet {:query "connector consumer interface"
                :docs [{:source {:path "consumer/logs.go"
                                 :definitionKind :interface
                                 :heading "consumer/logs/Logs"}
                        :score 5.0
                        :snippet "consumer logs interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "consumer/metrics.go"
                                 :definitionKind :interface
                                 :heading "consumer/metrics/Metrics"}
                        :score 4.8
                        :snippet "consumer metrics interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "connector/connector.go"
                                 :definitionKind :interface
                                 :heading "connector/connector/Logs"}
                        :score 4.0
                        :snippet "connector interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["consumer/logs.go"
            "connector/connector.go"
            "consumer/metrics.go"]
           (mapv :path files)))))

(deftest file-ranking-keeps-single-row-candidate-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-single-source-graph-candidate-rank")
        _ (spit-file! root "lib/adapters/http.js" "export default function httpAdapter() {}\n")
        packet {:query "defer env proxy handling"
                :candidateFiles [{:path "lib/adapters/http.js"
                                  :rank 2
                                  :score 2.3
                                  :targetKind "file"
                                  :label "lib/adapters/http.js"
                                  :scoreComponents {:sourceGraph 2.3
                                                    :lexical 0.27}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "lib/adapters/http.js" (:path file)))
    (is (= 2 (get-in file [:metrics :candidateSourceRank])))
    (is (<= (get-in file [:metrics :candidateSourceRankScore]) 0.2))
    (is (nil? (get-in file [:metrics :robustCandidateOnlyBoost])))))

(deftest file-ranking-keeps-doc-supported-source-graph-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-doc-source-graph-rank")
        _ (spit-file! root "src/settings.cs" "public static class Settings {}\n")
        packet {:query "prefer enum typehandler setting"
                :docs [{:source {:path "src/settings.cs"
                                 :heading "Settings.Settings"}
                        :score 1.0
                        :snippet "setting"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/settings.cs"
                                  :rank 1
                                  :score 4.0
                                  :targetKind "file"
                                  :label "src/settings.cs"
                                  :scoreComponents {:sourceGraph 4.0
                                                    :lexical 0.4}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "src/settings.cs" (:path file)))
    (is (= 1 (get-in file [:metrics :candidateSourceRank])))
    (is (<= (get-in file [:metrics :candidateSourceRankScore]) 0.2))
    (is (> (get-in file [:metrics :sourceRankScore]) 2.0))))

(deftest limited-agent-result-reserves-candidate-file-only-evidence
  (let [root (temp-dir "ygg-bench-candidate-file-quota")
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
  (let [root (temp-dir "ygg-bench-source-kind-diversity")
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
(deftest inspection-file-result-scope-frontloads-file-and-repo-candidate-lanes
  (let [core-root (temp-dir "ygg-bench-inspection-core")
        contrib-root (temp-dir "ygg-bench-inspection-contrib")
        _ (doseq [[root paths] [[core-root ["connector/logs_router.go"
                                            "connector/metrics_router.go"
                                            "connector/connector.go"
                                            "consumer/consumer.go"
                                            "component/component.go"]]
                                [contrib-root ["connector/routingconnector/factory.go"
                                               "connector/routingconnector/config.go"
                                               "connector/routingconnector/logs.go"
                                               "connector/routingconnector/router.go"]]]]
            (doseq [path paths]
              (spit-file! root path "package fixture\n")))
        packet {:query "connector contract inspection"
                :docs [{:source {:repo "core"
                                 :path "connector/logs_router.go"
                                 :heading "connector/logs_router/LogsRouterAndConsumer"}
                        :score 8.0
                        :snippet "connector contract inspection"
                        :provenance "retrieved-doc"}
                       {:source {:repo "contrib"
                                 :path "connector/routingconnector/logs.go"
                                 :heading "connector/routingconnector/logs/newLogsConnector"}
                        :score 7.5
                        :snippet "connector contract inspection"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:repo "core"
                                  :path "connector/logs_router.go"
                                  :rank 2
                                  :score 2.2
                                  :targetKind "node"
                                  :label "connector/logs_router/LogsRouterAndConsumer"
                                  :scoreComponents {:sourceGraph 2.2
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "core"
                                  :path "component/component.go"
                                  :rank 3
                                  :score 1.3
                                  :targetKind "file"
                                  :label "component/component.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.7}}
                                 {:repo "core"
                                  :path "consumer/consumer.go"
                                  :rank 4
                                  :score 1.3
                                  :targetKind "file"
                                  :label "consumer/consumer.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.7}}
                                 {:repo "core"
                                  :path "connector/metrics_router.go"
                                  :rank 16
                                  :score 1.25
                                  :targetKind "file"
                                  :label "connector/metrics_router.go"
                                  :scoreComponents {:sourceGraph 1.25
                                                    :lexical 0.7
                                                    :graph 0.2}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/config.go"
                                  :rank 11
                                  :score 2.1
                                  :targetKind "node"
                                  :label "connector/routingconnector/config/Config"
                                  :scoreComponents {:sourceGraph 2.1
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/router.go"
                                  :rank 12
                                  :score 2.0
                                  :targetKind "node"
                                  :label "connector/routingconnector/router/router"
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "core"
                                  :path "connector/connector.go"
                                  :rank 15
                                  :score 1.3
                                  :targetKind "file"
                                  :label "connector/connector.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.4}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/factory.go"
                                  :rank 24
                                  :score 1.2
                                  :targetKind "file"
                                  :label "connector/routingconnector/factory.go"
                                  :scoreComponents {:sourceGraph 1.2
                                                    :lexical 0.9}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/logs.go"
                                  :rank 25
                                  :score 1.2
                                  :targetKind "file"
                                  :label "connector/routingconnector/logs.go"
                                  :scoreComponents {:sourceGraph 1.2
                                                    :lexical 0.9}}]}
        result (benchmark/context-packet->agent-result
                packet
                {:roots {"core" core-root
                         "contrib" contrib-root}
                 :limit 5
                 :result-scope :inspection-files})
        files (:suspectedFiles result)]
    (is (= [{:repo-id "core"
             :path "component/component.go"
             :rank 1}
            {:repo-id "core"
             :path "consumer/consumer.go"
             :rank 2}
            {:repo-id "core"
             :path "connector/connector.go"
             :rank 3}
            {:repo-id "contrib"
             :path "connector/routingconnector/factory.go"
             :rank 4}
            {:repo-id "contrib"
             :path "connector/routingconnector/config.go"
             :rank 5}]
           (mapv #(select-keys % [:repo-id :path :rank]) files)))
    (is (= 24 (get-in files [3 :metrics :candidateSourceRank])))
    (is (= {:rawCandidateFiles 9
            :candidateFiles 9
            :coverageFilteredCandidateFiles 0
            :limit 5
            :coverageSourceKinds []
            :inspectionDirectFileSelected 4
            :inspectionRepoCandidateSelected 1
            :inspectionCandidateFillSkipped 4}
           (:selection result)))))
(deftest file-ranking-preserves-early-retrieved-source-order
  (let [root (temp-dir "ygg-bench-retrieved-rank")
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
  (let [repo (temp-dir "ygg-bench-local-vector-repo")
        out (temp-dir "ygg-bench-local-vector-out")
        suite-dir (temp-dir "ygg-bench-local-vector-suite")
        worker-dir (temp-dir "ygg-bench-local-vector-worker")
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
                          "{\"schema\":\"ygg.benchmark.agent-result/v2\","
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
          (is (= "ygg-baseline-local-vector" (:agentId baseline)))
          (is (= "local-vector" (:mode baseline)))
          (is (= "local-vector" (:retriever baseline)))
          (is (= "fake-local-model" (get-in baseline [:localVector :model])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "ygg.benchmark.local-vector-request/v1" (:schema request)))
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
  (let [repo (temp-dir "ygg-bench-local-vector-worker-repo")
        module-dir (temp-dir "ygg-bench-local-vector-worker-module")
        request-dir (temp-dir "ygg-bench-local-vector-worker-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.local-vector-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-local-vector"
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
          (is (= "ygg-baseline-local-vector" (:agentId result)))
          (is (= "local-vector" (:mode result)))
          (is (= ["src/app.clj" "docs/readme.md"]
                 (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (seq (:commands result))))))))

(deftest codebase-memory-baseline-shells-out-and-scores-agent-result
  (let [repo (temp-dir "ygg-bench-codebase-memory-repo")
        out (temp-dir "ygg-bench-codebase-memory-out")
        suite-dir (temp-dir "ygg-bench-codebase-memory-suite")
        worker-dir (temp-dir "ygg-bench-codebase-memory-worker")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        worker-path (spit-file!
                     worker-dir
                     "fake-codebase-memory-worker.sh"
                     (str "#!/bin/sh\n"
                          "case_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseId\"])' \"$1\")\n"
                          "case_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseFingerprint\"])' \"$1\")\n"
                          "agent_input_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentInputFingerprint\"])' \"$1\")\n"
                          "agent_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentId\"])' \"$1\")\n"
                          "cat > \"$2\" <<JSON\n"
                          "{\"schema\":\"ygg.benchmark.agent-result/v2\","
                          "\"caseId\":\"$case_id\","
                          "\"caseFingerprint\":\"$case_fingerprint\","
                          "\"agentInputFingerprint\":\"$agent_input_fingerprint\","
                          "\"agentId\":\"$agent_id\","
                          "\"mode\":\"codebase-memory\","
                          "\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake codebase memory match\","
                          "\"evidence\":[\"fake-codebase-memory path=src/app.clj\"]}],"
                          "\"suspectedSymbols\":[],\"commands\":[\"fake-codebase-memory-worker\"],"
                          "\"warnings\":[],\"summary\":\"fake codebase memory result\","
                          "\"tokenUsage\":{\"inputTokens\":0,\"outputTokens\":0,"
                          "\"totalTokens\":0,\"costUsd\":0.0,"
                          "\"source\":\"codebase-memory-baseline\"}}\n"
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
                       :retriever "codebase-memory"
                       :codebase-memory-command worker-path
                       :codebase-memory-bin "fake-codebase-memory-mcp"})
              baseline (first (:baselines result))
              request (json/read-json
                       (slurp (get-in baseline [:artifacts :codebaseMemoryRequestPath]))
                       :key-fn keyword)
              scored (json/read-json
                      (slurp (get-in baseline [:artifacts :agentScorePath]))
                      :key-fn keyword)
              resumed (benchmark/agent-baselines!
                       suite
                       {:out out
                        :case-id "case-1"
                        :retriever "codebase-memory"
                        :codebase-memory-command "missing-codebase-memory-worker"
                        :codebase-memory-bin "fake-codebase-memory-mcp"
                        :skip-existing? true})
              skipped (first (:baselines resumed))]
          (is (= benchmark/agent-baselines-schema (:schema result)))
          (is (= "ygg-baseline-codebase-memory" (:agentId baseline)))
          (is (= "codebase-memory" (:mode baseline)))
          (is (= "codebase-memory" (:retriever baseline)))
          (is (= "fake-codebase-memory-mcp" (get-in baseline [:codebaseMemory :binary])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "ygg.benchmark.codebase-memory-request/v1" (:schema request)))
          (is (= (:caseFingerprint baseline) (:caseFingerprint request)))
          (is (= (:agentInputFingerprint baseline)
                 (:agentInputFingerprint request)))
          (is (= "fake-codebase-memory-mcp" (get-in request [:codebaseMemory :binary])))
          (is (str/ends-with? (get-in request [:codebaseMemory :cacheDir])
                              "codebase-memory-cache"))
          (is (not (contains? request :groundTruth)))
          (is (= benchmark/agent-result-schema (get-in scored [:agent :schema])))
          (is (= "codebase-memory" (get-in scored [:agent :mode])))
          (is (empty? (get-in scored [:agent :warnings])))
          (is (= 1.0 (get-in scored [:scores :evidenceCitationRate])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))

(deftest codebase-memory-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-codebase-memory-worker-repo")
        bin-dir (temp-dir "ygg-bench-codebase-memory-worker-bin")
        request-dir (temp-dir "ygg-bench-codebase-memory-worker-request")
        binary-path (spit-file!
                     bin-dir
                     "fake-codebase-memory-mcp"
                     (str "#!/usr/bin/env python3\n"
                          "import json, sys\n"
                          "tool = sys.argv[2] if len(sys.argv) > 2 else ''\n"
                          "if tool == 'index_repository':\n"
                          "    print(json.dumps({'indexed': True, 'path': 'src/app.clj'}))\n"
                          "elif tool == 'semantic_query':\n"
                          "    print(json.dumps({'results': [{'path': 'src/app.clj', 'score': 0.9}]}))\n"
                          "elif tool == 'search_code':\n"
                          "    print(json.dumps({'matches': [{'file_path': 'docs/readme.md', 'count': 1}]}))\n"
                          "elif tool == 'get_architecture':\n"
                          "    print(json.dumps({'hotspots': [{'relative_path': 'src/app.clj'}]}))\n"
                          "else:\n"
                          "    print(json.dumps({'error': tool}))\n"
                          "    sys.exit(1)\n"))
        cache-dir (.getPath (io/file request-dir "cache"))
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.codebase-memory-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-codebase-memory"
                       :mode "codebase-memory"
                       :worktreeRoot repo
                       :input {:title "broken app"
                               :body "The app returns the old value."}
                       :limit 2
                       :codebaseMemory {:binary binary-path
                                        :cacheDir cache-dir}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (.setExecutable (io/file binary-path) true)
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! repo "docs/readme.md" "The app behavior is documented here.\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/codebase-memory-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "ygg-baseline-codebase-memory" (:agentId result)))
          (is (= "codebase-memory" (:mode result)))
          (is (= ["src/app.clj" "docs/readme.md"]
                 (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (= "codebase-memory-baseline" (get-in result [:tokenUsage :source])))
          (is (empty? (:warnings result)))
          (is (= 4 (count (:commands result)))))))))

(deftest codebase-memory-worker-emits-warned-empty-result-when-binary-is-missing
  (let [repo (temp-dir "ygg-bench-codebase-memory-missing-repo")
        request-dir (temp-dir "ygg-bench-codebase-memory-missing-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.codebase-memory-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-codebase-memory"
                       :mode "codebase-memory"
                       :worktreeRoot repo
                       :input {:title "broken app"}
                       :limit 2
                       :codebaseMemory {:binary (.getPath
                                                 (io/file request-dir "missing-cbm"))}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/codebase-memory-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "codebase-memory" (:mode result)))
          (is (empty? (:suspectedFiles result)))
          (is (seq (:warnings result)))
          (is (= "codebase-memory-baseline" (get-in result [:tokenUsage :source]))))))))

(deftest deepseek-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-deepseek-worker-repo")
        request-dir (temp-dir "ygg-bench-deepseek-worker-request")
        prompt-path (spit-file! request-dir
                                "prompt.md"
                                "# Prompt\nFind the broken app file.\n")
        context-path (spit-json!
                      request-dir
                      "context.json"
                      {:candidateFiles [{:path "src/app.clj"}]
                       :coverage {:sourceKinds ["code"]}})
        mock-response-path (spit-json!
                            request-dir
                            "deepseek-response.json"
                            {:usage {:input_tokens 11
                                     :output_tokens 7}
                             :stop_reason "tool_use"
                             :content [{:type "tool_use"
                                        :id "toolu_1"
                                        :name "write_result"
                                        :input {:result
                                                {:suspectedFiles
                                                 [{:path "src/app.clj"
                                                   :rank 1
                                                   :confidence 0.9
                                                   :reason "Mock DeepSeek result."
                                                   :evidence ["src/app.clj contains the app entry."]}]
                                                 :summary "Mocked DeepSeek localization."}}}]})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [{:keys [exit err]} (shell/sh "env"
                                       (str "YGG_BENCH_PROMPT=" prompt-path)
                                       (str "YGG_BENCH_RESULT=" result-path)
                                       (str "YGG_BENCH_WORKTREE=" repo)
                                       "YGG_BENCH_CASE_ID=case-1"
                                       "YGG_BENCH_CASE_FINGERPRINT=sha256:case-1"
                                       "YGG_BENCH_AGENT_INPUT_FINGERPRINT=sha256:case-input"
                                       "YGG_BENCH_AGENT_ID=deepseek-test"
                                       "YGG_BENCH_MODE=ygg"
                                       "YGG_BENCH_PARSER_WORKER=dotnet"
                                       (str "YGG_BENCH_YGG_CONTEXT=" context-path)
                                       (str "DEEPSEEK_MOCK_RESPONSE=" mock-response-path)
                                       "python3"
                                       "scripts/deepseek-agent.py")]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "deepseek-test" (:agentId result)))
          (is (= "ygg" (:mode result)))
          (is (= {:rawCandidateFiles 1
                  :candidateFiles 1
                  :coverageFilteredCandidateFiles 1
                  :limit nil
                  :coverageSourceKinds ["code"]}
                 (:selection result)))
          (is (= {:mode "dotnet"
                  :source "env"}
                 (:parserWorker result)))
          (is (= ["src/app.clj"] (mapv :path (:suspectedFiles result))))
          (is (= {:inputTokens 11
                  :outputTokens 7
                  :totalTokens 18
                  :costUsd 0.0
                  :source "deepseek-agent"}
                 (:tokenUsage result))))))))
