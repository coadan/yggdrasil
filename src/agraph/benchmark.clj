(ns agraph.benchmark
  "Issue replay benchmarks for AGraph retrieval quality."
  (:require [agraph.benchmark-classes :as benchmark-classes]
            [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-progress :as benchmark-progress]
            [agraph.benchmark-score :as benchmark-score]
            [agraph.context :as context]
            [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [agraph.benchmark-expectations :as benchmark-expectations]
            [agraph.benchmark-suite :as benchmark-suite]
            [agraph.benchmark-prepare :as benchmark-prepare]
            [agraph.benchmark-prediction :as benchmark-prediction]
            [agraph.benchmark-hints :as benchmark-hints]
            [agraph.benchmark-agent-score :as benchmark-agent-score]
            [agraph.benchmark-results :as benchmark-results]
            [agraph.benchmark-command-telemetry :as benchmark-command-telemetry]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def suite-schema
  "agraph.benchmark.suite/v1")

(def prepared-case-schema
  "agraph.benchmark.prepared-case/v1")

(def result-schema
  "agraph.benchmark.result/v1")

(def agent-packet-schema
  "agraph.benchmark.agent-packet/v1")

(def agent-hints-schema
  "agraph.benchmark.agent-hints/v1")

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def agent-score-schema
  "agraph.benchmark.agent-score/v3")

(def ^:private agent-run-modes
  ["agraph" "shell-only"])

(def ^:private agent-run-mode-set
  (set agent-run-modes))

(def ^:private agent-result-modes
  ["agraph" "shell-only" "local-vector"])

(def agent-report-schema
  "agraph.benchmark.agent-report/v1")

(def agent-check-schema
  "agraph.benchmark.agent-check/v1")

(def graph-expectations-schema
  "agraph.benchmark.graph-expectations/v1")

(def agent-compare-schema
  "agraph.benchmark.agent-compare/v1")

(def agent-baseline-schema
  "agraph.benchmark.agent-baseline/v1")

(def agent-baselines-schema
  "agraph.benchmark.agent-baselines/v1")

(def agent-run-schema
  "agraph.benchmark.agent-run/v1")

(def agent-runs-schema
  "agraph.benchmark.agent-runs/v1")

(def report-schema
  "agraph.benchmark.report/v1")

(def ^:private suspected-files-scope-rule
  "Only include files likely to require edits in suspectedFiles; cite comparison, example, generated, or read-only support files as evidence instead.")

(def default-limit
  50)

(def default-agent-baseline-budget
  24000)

(def default-agent-baseline-doc-limit
  20)

(def default-agent-baseline-retrieval-limit
  300)

(def default-agent-baseline-suspect-limit
  20)

(def ^:private problem-class-minimum-cases
  2)

(def default-local-vector-model
  "sentence-transformers/all-MiniLM-L6-v2")

(def default-local-vector-command
  "python3 scripts/local-vector-baseline.py")

(def ^:private rank-blocker-limit
  5)

(def ^:private aggregate-rank-blocker-limit
  20)

(def ^:private aggregate-ranked-file-diagnostic-limit
  20)

(def default-agent-run-timeout-ms
  600000)

(def default-index-timeout-ms
  600000)

(def supported-agent-prompt-profiles
  ["standard" "fast"])

(declare agent-baseline-suspect-limit agent-prompt-profile)

(defn- index-timeout-ms
  [opts]
  (let [configured (get opts :index-timeout-ms ::default)]
    (cond
      (= ::default configured) default-index-timeout-ms
      (and configured (pos? (long configured))) (long configured)
      :else nil)))

(defn- benchmark-index-options
  [opts]
  (cond-> {:index-profile :query}
    (some? (index-timeout-ms opts)) (assoc :index-timeout-ms (index-timeout-ms opts))))

(defn- parser-worker-option
  [opts]
  (extract/normalize-parser-worker-mode (:parser-worker opts)))

(defn- parser-worker-profile
  [opts]
  (let [option-mode (parser-worker-option opts)
        env-mode (extract/normalize-parser-worker-mode
                  (System/getenv "AGRAPH_PARSER_WORKER"))]
    {:mode (or option-mode env-mode "none")
     :source (cond
               option-mode "option"
               env-mode "env"
               :else "default")}))

(defn- normalize-parser-worker-profile
  [profile]
  (let [mode (extract/normalize-parser-worker-mode (:mode profile))
        source (some-> (:source profile) str str/trim not-empty)]
    (when mode
      {:mode mode
       :source (or source "unknown")})))

(defn- agent-score-parser-worker-profile
  [opts agent-result]
  (or (when (parser-worker-option opts)
        (parser-worker-profile opts))
      (normalize-parser-worker-profile (:parserWorker agent-result))
      (parser-worker-profile opts)))

(defn- with-benchmark-parser-worker
  [opts f]
  (extract/with-parser-worker-mode (parser-worker-option opts)
    (f)))

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn read-suite
  "Read and normalize a benchmark suite EDN file."
  [path]
  (benchmark-suite/read-suite path))

(defn selected-cases
  "Return suite cases, optionally narrowed to one or more case ids."
  [suite selector]
  (benchmark-suite/selected-cases suite selector))

(defn- repo-by-id
  [suite]
  (benchmark-suite/repo-by-id suite))

(defn- case-selector
  [opts]
  (benchmark-suite/case-selector opts))

(defn- normalize-case-tag
  [tag]
  (benchmark-suite/normalize-case-tag tag))

(defn- case-tags
  [case]
  (benchmark-suite/case-tags case))

(defn- case-expectations
  [case]
  (benchmark-suite/case-expectations case))

(defn- agent-baseline-result-path
  [suite case opts]
  (benchmark-paths/agent-baseline-result-path suite case opts))

(defn- agent-score-path
  [suite case opts result-file]
  (benchmark-paths/agent-score-path suite case opts result-file))

(defn- normalize-source-kind
  [value]
  (benchmark-prepare/normalize-source-kind value))

(defn- path-source-kind
  ([path]
   (benchmark-prepare/path-source-kind path))
  ([kind-by-path path]
   (benchmark-prepare/path-source-kind kind-by-path path)))

(defn- scanned-path-kinds
  [root]
  (benchmark-prepare/scanned-path-kinds root))

(defn issue-text
  "Return the fair issue text used as benchmark query input."
  [case]
  (benchmark-prepare/issue-text case))

(defn- case-fingerprint
  [suite case]
  (benchmark-prepare/case-fingerprint suite case))

(defn prepare-case!
  "Prepare one benchmark case and write its prepared JSON artifact."
  [suite case opts]
  (benchmark-prepare/prepare-case! suite case opts))

(defn prepare-suite!
  "Prepare selected benchmark cases."
  [suite opts]
  (benchmark-prepare/prepare-suite! suite opts))

(defn score-result
  "Return mechanical localization scores for a benchmark result shape."
  [result]
  (benchmark-score/score-result result))

(defn- expected-row-results
  [rows expectations summarize]
  (benchmark-expectations/expected-row-results rows expectations summarize))

(defn- forbidden-row-results
  [rows expectations summarize]
  (benchmark-expectations/forbidden-row-results rows expectations summarize))

(defn- evaluate-graph-expectations
  [xtdb prepared]
  (benchmark-expectations/evaluate-graph-expectations xtdb prepared))

(defn- run-query!
  [xtdb prepared opts]
  (query/semantic-query xtdb
                        (get-in prepared [:input :queryText])
                        {:project-id (:project-id prepared)
                         :repo-id (:repo-id prepared)
                         :retriever (keyword (or (:retriever opts) :lexical))
                         :limit (long (or (:limit opts) default-limit))}))

(defn run-case!
  "Run one benchmark case and write its scored result artifact."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        repo {:id (:repo-id prepared)
              :root (:worktreeRoot prepared)
              :role :application}
        bench-project {:id (:project-id prepared)
                       :name (:case-id prepared)
                       :repos [repo]}]
    (store/with-node (benchmark-paths/xtdb-dir suite case opts)
      (fn [xtdb]
        (let [index-summary (with-benchmark-parser-worker
                              opts
                              #(project/index-project! xtdb
                                                       bench-project
                                                       (benchmark-index-options opts)))
              system-summary (project/infer-project! xtdb bench-project)
              graph-expectations (evaluate-graph-expectations xtdb prepared)
              ranked (run-query! xtdb prepared opts)
              result-base
              {:schema result-schema
               :suite-id (:id suite)
               :case-id (:id case)
               :repo-id (:repo-id prepared)
               :project-id (:project-id prepared)
               :caseFingerprint (:caseFingerprint prepared)
               :tags (:tags prepared)
               :expectations (:expectations prepared)
               :baseSha (:baseSha prepared)
               :fixSha (:fixSha prepared)
               :input (:input prepared)
               :inputHints (:inputHints prepared)
               :coverage (:coverage prepared)
               :groundTruth (:groundTruth prepared)
               :agraph {:retriever (name (keyword (or (:retriever opts) :lexical)))
                        :parserWorker (parser-worker-profile opts)
                        :limit (long (or (:limit opts) default-limit))
                        :indexSummary index-summary
                        :systemSummary system-summary
                        :topFiles (benchmark-score/top-files ranked)
                        :topNodes (benchmark-score/top-nodes ranked)
                        :topSystems (benchmark-score/top-systems ranked)
                        :warnings []}
               :graphExpectations graph-expectations}
              result-without-scores
              (assoc result-base
                     :groundTruthRanks
                     {:files (benchmark-score/ground-truth-file-ranks
                              (benchmark-score/scoreable-changed-files
                               (get-in result-base [:groundTruth]))
                              (get-in result-base [:agraph :topFiles]))})
              result (assoc result-without-scores
                            :scores (benchmark-score/score-result result-without-scores))]
          (benchmark-io/write-json-file! (benchmark-paths/result-path suite case opts) result)
          result)))))

(defn run-suite!
  "Run selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.run/v1"
   :suite-id (:id suite)
   :cases (mapv #(run-case! suite % opts)
                (selected-cases suite (case-selector opts)))})

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- agraph-command-root
  []
  (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(defn- agent-clj-config-dir
  [prepared]
  (fs/canonical-path (io/file (:worktreeRoot prepared) ".cpcache" "clj-config")))

(defn- env-command
  [xtdb-path clj-config-dir & args]
  (let [[extra-env args] (if (map? (first args))
                           [(first args) (rest args)]
                           [{} args])
        [command & command-args] args
        env-vars (merge {"CLJ_CONFIG" clj-config-dir
                         "AGRAPH_XTDB_PATH" xtdb-path}
                        extra-env)]
    (str "mkdir -p "
         (shell-quote clj-config-dir)
         " && cd "
         (shell-quote (agraph-command-root))
         " && "
         (str/join " "
                   (map (fn [[k v]]
                          (str k "=" (shell-quote v)))
                        env-vars))
         " "
         (str/join " " (cons command (map shell-quote command-args))))))

(defn- agent-mode
  [opts]
  (let [mode (name (keyword (or (:mode opts) :agraph)))]
    (when-not (contains? agent-run-mode-set mode)
      (throw (ex-info "Unknown benchmark agent mode."
                      {:mode mode
                       :supported agent-run-modes})))
    mode))

(defn- score-json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))

(defn- current-agent-score-artifacts
  [suite case opts {:keys [agent-id mode result-path]}]
  (let [dir (benchmark-paths/agent-score-dir suite case opts)
        expected-fingerprint (case-fingerprint suite case)
        expected-result-path (some-> result-path fs/canonical-path)
        expected-parser-worker-mode (:mode (parser-worker-profile opts))
        parser-worker-match? (fn [score]
                               (or (not= "agraph" mode)
                                   (= expected-parser-worker-mode
                                      (get-in score [:parserWorker :mode]))))]
    (if-not (.isDirectory dir)
      []
      (->> (file-seq dir)
           (filter score-json-file?)
           (keep (fn [file]
                   (let [score (try
                                 (benchmark-io/read-json-file file)
                                 (catch Exception _
                                   nil))]
                     (when (and score
                                (= agent-score-schema (:schema score))
                                (= agent-result-schema (get-in score [:agent :schema]))
                                (= (:id case) (:case-id score))
                                (= expected-fingerprint (:caseFingerprint score))
                                (= agent-id (get-in score [:agent :agentId]))
                                (= mode (get-in score [:agent :mode]))
                                (= expected-result-path (:agentResultPath score))
                                (parser-worker-match? score))
                       (assoc score :agentScorePath (fs/canonical-path file))))))
           vec))))

(defn- reusable-agent-score
  [suite case opts match]
  (let [matches (current-agent-score-artifacts suite case opts match)]
    (when (= 1 (count matches))
      (first matches))))

(defn- agent-baseline-mode
  [opts]
  (if (= :local-vector (keyword (or (:retriever opts) :lexical)))
    "local-vector"
    "agraph"))

(defn- skipped-agent-baseline
  [suite case opts score]
  {:schema agent-baseline-schema
   :suite-id (:suite-id score)
   :case-id (:case-id score)
   :repo-id (:repo-id score)
   :project-id (:project-id score)
   :caseFingerprint (:caseFingerprint score)
   :agentId (get-in score [:agent :agentId])
   :mode (get-in score [:agent :mode])
   :retriever (name (keyword (or (:retriever opts) :lexical)))
   :parserWorker (:parserWorker score)
   :suspectLimit (agent-baseline-suspect-limit opts)
   :status "skipped"
   :skipReason "current-score-artifact"
   :artifacts (cond-> {:agentResultPath (:agentResultPath score)
                       :agentScorePath (:agentScorePath score)
                       :progressPath (fs/canonical-path (benchmark-paths/progress-path suite case opts))}
                (= "local-vector" (get-in score [:agent :mode]))
                (assoc :localVectorRequestPath
                       (fs/canonical-path (benchmark-paths/local-vector-request-path suite case opts))))
   :scores (:scores score)})

(defn- skipped-agent-run
  [suite case opts score]
  {:schema agent-run-schema
   :suite-id (:suite-id score)
   :case-id (:case-id score)
   :repo-id (:repo-id score)
   :project-id (:project-id score)
   :caseFingerprint (:caseFingerprint score)
   :agentId (get-in score [:agent :agentId])
   :mode (get-in score [:agent :mode])
   :promptProfile (agent-prompt-profile opts)
   :status "skipped"
   :skipReason "current-score-artifact"
   :artifacts {:agentResultPath (:agentResultPath score)
               :agentScorePath (:agentScorePath score)
               :agentRunPath (fs/canonical-path (benchmark-paths/agent-run-path suite case opts))}
   :scores (:scores score)
   :warnings (get-in score [:agent :warnings])})

(defn- agent-project
  [prepared]
  {:id (:project-id prepared)
   :name (:case-id prepared)
   :repos [{:id (:repo-id prepared)
            :root (:worktreeRoot prepared)
            :role :application}]})

(defn- parser-worker-command-env
  [opts]
  (if-let [mode (parser-worker-option opts)]
    {"AGRAPH_PARSER_WORKER" mode}
    {}))

(defn- agent-command-hints
  [prepared project-path xtdb-path mode opts]
  (let [clj-config-dir (agent-clj-config-dir prepared)
        parser-env (parser-worker-command-env opts)]
    (cond-> {:shell ["Inspect the checkout with ordinary local commands such as git, rg, find, sed, and tests."
                     "Do not read the fixing diff, PR, post-fix commits, or ground-truth artifacts."]}
      (= "agraph" mode)
      (assoc :agraph
             {:projectConfig project-path
              :xtdbPath xtdb-path
              :cljConfigDir clj-config-dir
              :setupCommand (env-command xtdb-path
                                         clj-config-dir
                                         parser-env
                                         "bb"
                                         "sync"
                                         project-path)
              :askCommand (env-command xtdb-path
                                       clj-config-dir
                                       parser-env
                                       "bb"
                                       "ask"
                                       (get-in prepared [:input :queryText])
                                       "--project"
                                       (:project-id prepared)
                                       "--json")
              :exploreCommand (env-command xtdb-path
                                           clj-config-dir
                                           parser-env
                                           "bb"
                                           "explore"
                                           "create"
                                           (get-in prepared [:input :queryText])
                                           "--project"
                                           (:project-id prepared)
                                           "--json")}))))

(defn- agent-result-contract
  []
  {:schema agent-result-schema
   :caseId "case id from the packet"
   :caseFingerprint "case fingerprint from the packet"
   :agentId "stable id for the agent run"
   :mode "agraph, shell-only, or local-vector"
   :selection {:rawCandidateFiles 0
               :candidateFiles 0
               :coverageFilteredCandidateFiles 0
               :limit 20
               :coverageSourceKinds []}
   :parserWorker {:mode "none|java|dotnet|all"
                  :source "option|env|default|agent-result|unknown"}
   :suspectedFiles [{:path "repo-relative/path.ext"
                     :rank 1
                     :confidence 0.0
                     :reason "short evidence-based reason"
                     :evidence []}]
   :suspectedSymbols []
   :commands []
   :warnings []
   :summary "brief rationale"})

(defn- agent-packet-from-prepared!
  [suite case prepared opts]
  (let [mode (agent-mode opts)
        project-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))
        xtdb-path (fs/canonical-path (benchmark-paths/xtdb-dir suite case opts))
        packet-path (fs/canonical-path (benchmark-paths/agent-packet-path suite case opts))
        project-config (agent-project prepared)
        artifacts (cond-> {:projectConfig project-path
                           :packetPath packet-path
                           :xtdbPath xtdb-path}
                    (:agraph-context-path opts)
                    (assoc :agraphContextPath (:agraph-context-path opts))
                    (:agraph-hints-path opts)
                    (assoc :agraphHintsPath (:agraph-hints-path opts)))
        packet {:schema agent-packet-schema
                :suite-id (:suite-id prepared)
                :case-id (:case-id prepared)
                :caseFingerprint (:caseFingerprint prepared)
                :repo-id (:repo-id prepared)
                :project-id (:project-id prepared)
                :mode mode
                :parserWorker (parser-worker-profile opts)
                :baseSha (:baseSha prepared)
                :worktreeRoot (:worktreeRoot prepared)
                :input (:input prepared)
                :task {:kind "issue-localization"
                       :objective (str "Identify the repo-relative files and optional symbols most likely "
                                       "needed to fix the issue from the base checkout.")
                       :rules ["Use only the base checkout and issue text in this packet."
                               "Return ranked suspected files before attempting a patch."
                               suspected-files-scope-rule
                               "Keep reasoning evidence-based and cite commands or graph context used."
                               "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]
                       :expectedResultSchema agent-result-schema
                       :resultContract (agent-result-contract)}
                :tools (agent-command-hints prepared project-path xtdb-path mode opts)
                :artifacts artifacts
                :fairness {:allowedInput ["issue title"
                                          "issue body"
                                          "pre-fix issue comments"
                                          "base checkout"
                                          "AGraph output generated from the base checkout"]
                           :forbiddenInput ["fix diff"
                                            "PR title or body"
                                            "post-fix issue comments"
                                            "post-fix commits"
                                            "ground-truth benchmark artifacts"]}}]
    (benchmark-io/write-edn-file! project-path project-config)
    (benchmark-io/write-json-file! packet-path packet)
    packet))

(defn agent-packet!
  "Prepare one case and write a provider-neutral agent localization packet."
  [suite case opts]
  (agent-packet-from-prepared! suite case (prepare-case! suite case opts) opts))

(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.agent-packets/v1"
   :suite-id (:id suite)
   :packets (mapv #(agent-packet! suite % opts)
                  (selected-cases suite (case-selector opts)))})

(declare score-agent-result)

(defn- agent-result-json-schema
  []
  (benchmark-agent-score/agent-result-json-schema))

(defn score-agent-result
  "Score an agent localization result against a prepared case artifact."
  [prepared agent-result]
  (benchmark-agent-score/score-agent-result prepared agent-result))

(defn context-packet->agent-result
  "Convert one AGraph context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (benchmark-prediction/context-packet->agent-result packet))
  ([packet opts]
   (benchmark-prediction/context-packet->agent-result packet opts)))

(defn- packet-commands
  [packet]
  (benchmark-prediction/packet-commands packet))

(defn- context-ground-truth-ranks
  [prepared packet]
  (let [context-result (context-packet->agent-result
                        packet
                        {:root (:worktreeRoot prepared)
                         :coverage (:coverage prepared)})]
    {:files (benchmark-score/ground-truth-file-ranks
             (benchmark-score/scoreable-changed-files (:groundTruth prepared))
             (:suspectedFiles context-result))
     :selection (:selection context-result)}))

(defn- context-ground-truth-ranks-from-path
  [prepared path]
  (when (and (not (blankish? path))
             (.isFile (io/file path)))
    (context-ground-truth-ranks prepared (benchmark-io/read-json-file path))))

(defn- agent-baseline-context-options
  [prepared opts]
  {:project-id (:project-id prepared)
   :repo-id (:repo-id prepared)
   :retriever (keyword (or (:retriever opts) :lexical))
   :budget (long (or (:budget opts) default-agent-baseline-budget))
   :doc-limit (long (or (:doc-limit opts) default-agent-baseline-doc-limit))
   :retrieval-limit (long (or (:retrieval-limit opts) default-agent-baseline-retrieval-limit))
   :snippet-chars (long (or (:snippet-chars opts) context/default-snippet-chars))})

(defn- agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one AGraph context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (benchmark-hints/context-packet->agent-hints prepared packet opts))

(defn agent-baseline!
  "Generate, write, and score one deterministic AGraph agent-result artifact."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        bench-project (agent-project prepared)
        project-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        context-path (benchmark-paths/agent-baseline-context-path suite case opts)
        progress-path (benchmark-paths/progress-path suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)]
    (benchmark-progress/progress-stage!
     suite
     case
     opts
     :write-agent-project
     #(benchmark-io/write-edn-file! project-path bench-project)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (store/with-node (benchmark-paths/xtdb-dir suite case opts)
      (fn [xtdb]
        (let [parser-worker (parser-worker-profile opts)
              index-summary (benchmark-progress/progress-stage!
                             suite
                             case
                             opts
                             :index-project
                             #(with-benchmark-parser-worker
                                opts
                                (fn []
                                  (project/index-project! xtdb
                                                          bench-project
                                                          (benchmark-index-options opts))))
                             #(select-keys % [:files :repos :rows :extractors]))
              system-summary (benchmark-progress/progress-stage!
                              suite
                              case
                              opts
                              :infer-project
                              #(project/infer-project! xtdb bench-project)
                              #(select-keys % [:systems :candidates :edges]))
              graph-expectations (evaluate-graph-expectations xtdb prepared)
              packet (benchmark-progress/progress-stage!
                      suite
                      case
                      opts
                      :context-packet
                      #(context/context-packet
                        xtdb
                        (get-in prepared [:input :queryText])
                        (agent-baseline-context-options prepared opts))
                      (fn [packet]
                        {:docs (count (:docs packet))
                         :entities (count (:entities packet))
                         :edges (count (:edges packet))
                         :warnings (count (:warnings packet))}))
              agent-result (benchmark-progress/progress-stage!
                            suite
                            case
                            opts
                            :agent-result
                            #(context-packet->agent-result
                              packet
                              {:agent-id agent-id
                               :mode "agraph"
                               :case-id (:case-id prepared)
                               :caseFingerprint (:caseFingerprint prepared)
                               :root (:worktreeRoot prepared)
                               :coverage (:coverage prepared)
                               :limit (agent-baseline-suspect-limit opts)})
                            (fn [result]
                              {:suspectedFiles (count (:suspectedFiles result))
                               :suspectedSymbols (count (:suspectedSymbols result))}))
              score-path (benchmark-paths/agent-score-path suite case opts result-path)
              scored (benchmark-progress/progress-stage!
                      suite
                      case
                      opts
                      :score-agent-result
                      #(cond-> (assoc (score-agent-result prepared agent-result)
                                      :agentResultPath (fs/canonical-path result-path)
                                      :contextPacketPath (fs/canonical-path context-path)
                                      :parserWorker parser-worker
                                      :contextGroundTruthRanks (context-ground-truth-ranks
                                                                prepared
                                                                packet))
                         graph-expectations
                         (assoc :graphExpectations graph-expectations))
                      (fn [scored]
                        (select-keys (:scores scored)
                                     [:fileRecallAt5
                                      :fileRecallAt10
                                      :fileRecallAt20
                                      :meanReciprocalRankFile
                                      :noiseRatioAt20
                                      :evidenceCitationRate])))
              baseline {:schema agent-baseline-schema
                        :suite-id (:suite-id prepared)
                        :case-id (:case-id prepared)
                        :repo-id (:repo-id prepared)
                        :project-id (:project-id prepared)
                        :caseFingerprint (:caseFingerprint prepared)
                        :agentId agent-id
                        :mode "agraph"
                        :retriever (name (keyword (or (:retriever opts)
                                                      :lexical)))
                        :parserWorker parser-worker
                        :suspectLimit (agent-baseline-suspect-limit opts)
                        :artifacts {:projectConfig project-path
                                    :agentResultPath (fs/canonical-path result-path)
                                    :contextPacketPath (fs/canonical-path context-path)
                                    :agentScorePath (fs/canonical-path score-path)
                                    :progressPath (fs/canonical-path progress-path)}
                        :agraph {:indexSummary index-summary
                                 :systemSummary system-summary}
                        :graphExpectations graph-expectations
                        :scores (:scores scored)}]
          (benchmark-progress/progress-stage!
           suite
           case
           opts
           :write-agent-artifacts
           (fn []
             (benchmark-io/write-json-file! context-path packet)
             (benchmark-io/write-json-file! result-path agent-result)
             (benchmark-io/write-json-file! score-path scored)
             {:contextPath context-path
              :agentResultPath result-path
              :agentScorePath score-path})
           (fn [paths]
             {:contextPacketPath (fs/canonical-path (:contextPath paths))
              :agentResultPath (fs/canonical-path (:agentResultPath paths))
              :agentScorePath (fs/canonical-path (:agentScorePath paths))}))
          baseline)))))

(defn- local-vector-model
  [opts]
  (str (or (:vector-model opts) default-local-vector-model)))

(defn- local-vector-command
  [opts]
  (str (or (:vector-command opts) default-local-vector-command)))

(defn- local-vector-request
  [prepared opts agent-id]
  {:schema "agraph.benchmark.local-vector-request/v1"
   :caseId (:case-id prepared)
   :caseFingerprint (:caseFingerprint prepared)
   :agentId agent-id
   :mode "local-vector"
   :worktreeRoot (:worktreeRoot prepared)
   :input (:input prepared)
   :task {:kind "issue-localization"
          :objective "Rank repo-relative files by local semantic vector similarity to the issue text."
          :rules ["Use only the base checkout and issue text in this request."
                  "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]}
   :limit (agent-baseline-suspect-limit opts)
   :model (local-vector-model opts)})

(defn- run-local-vector-command!
  [request-path result-path opts]
  (let [command (local-vector-command opts)
        model (local-vector-model opts)
        cmdline (str command
                     " "
                     (shell-quote (fs/canonical-path request-path))
                     " "
                     (shell-quote (fs/canonical-path result-path))
                     " "
                     (shell-quote model))
        {:keys [exit out err] :as process} (shell/sh "sh" "-c" cmdline)]
    (when-not (zero? exit)
      (throw (ex-info "Local vector benchmark worker failed."
                      {:command command
                       :exit exit
                       :out out
                       :err err})))
    (select-keys process [:exit :out :err])))

(defn- assoc-if-missing
  [m k v]
  (if (contains? m k)
    m
    (assoc m k v)))

(defn- normalize-agent-result-identity
  [agent-result prepared]
  (-> agent-result
      (assoc-if-missing :schema agent-result-schema)
      (assoc-if-missing :caseId (:case-id prepared))
      (assoc-if-missing :caseFingerprint (:caseFingerprint prepared))))

(defn- normalize-local-vector-result
  [agent-result prepared agent-id]
  (assoc (normalize-agent-result-identity agent-result prepared)
         :agentId agent-id
         :mode "local-vector"))

(defn local-vector-baseline!
  "Generate, write, and score one local semantic-vector benchmark baseline.

  This is an optional benchmark control. It shells out to a local worker and
  keeps vector dependencies outside AGraph core."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)
        request-path (benchmark-paths/local-vector-request-path suite case opts)
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        score-path (benchmark-paths/agent-score-path suite case opts result-path)
        progress-path (benchmark-paths/progress-path suite case opts)
        request (local-vector-request prepared opts agent-id)]
    (benchmark-progress/progress-stage!
     suite
     case
     opts
     :write-local-vector-request
     #(benchmark-io/write-json-file! request-path request)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (let [process (benchmark-progress/progress-stage!
                   suite
                   case
                   opts
                   :local-vector-worker
                   #(run-local-vector-command! request-path result-path opts)
                   #(select-keys % [:exit]))
          agent-result (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :local-vector-result
                        #(normalize-local-vector-result
                          (benchmark-io/read-json-file result-path)
                          prepared
                          agent-id)
                        (fn [result]
                          {:suspectedFiles (count (:suspectedFiles result))
                           :suspectedSymbols (count (:suspectedSymbols result))}))
          scored (benchmark-progress/progress-stage!
                  suite
                  case
                  opts
                  :score-agent-result
                  #(assoc (score-agent-result prepared agent-result)
                          :agentResultPath (fs/canonical-path result-path)
                          :localVectorRequestPath (fs/canonical-path request-path))
                  (fn [scored]
                    (select-keys (:scores scored)
                                 [:fileRecallAt5
                                  :fileRecallAt10
                                  :fileRecallAt20
                                  :meanReciprocalRankFile
                                  :noiseRatioAt20
                                  :evidenceCitationRate])))
          baseline {:schema agent-baseline-schema
                    :suite-id (:suite-id prepared)
                    :case-id (:case-id prepared)
                    :repo-id (:repo-id prepared)
                    :project-id (:project-id prepared)
                    :caseFingerprint (:caseFingerprint prepared)
                    :agentId agent-id
                    :mode "local-vector"
                    :retriever "local-vector"
                    :suspectLimit (agent-baseline-suspect-limit opts)
                    :artifacts {:localVectorRequestPath (fs/canonical-path request-path)
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :progressPath (fs/canonical-path progress-path)}
                    :localVector {:command (local-vector-command opts)
                                  :model (local-vector-model opts)
                                  :process process}
                    :scores (:scores scored)}]
      (benchmark-progress/progress-stage!
       suite
       case
       opts
       :write-agent-artifacts
       (fn []
         (benchmark-io/write-json-file! result-path agent-result)
         (benchmark-io/write-json-file! score-path scored)
         {:agentResultPath result-path
          :agentScorePath score-path})
       (fn [paths]
         {:agentResultPath (fs/canonical-path (:agentResultPath paths))
          :agentScorePath (fs/canonical-path (:agentScorePath paths))}))
      baseline)))

(defn agent-baselines!
  "Generate deterministic AGraph agent-result artifacts for selected cases."
  [suite opts]
  (let [baseline-for-case (fn [case]
                            (or (when (:skip-existing? opts)
                                  (some->> {:agent-id (benchmark-paths/agent-baseline-id opts)
                                            :mode (agent-baseline-mode opts)
                                            :result-path (benchmark-paths/agent-baseline-result-path
                                                          suite
                                                          case
                                                          opts)}
                                           (reusable-agent-score suite case opts)
                                           (skipped-agent-baseline suite case opts)))
                                (if (= :local-vector (keyword (or (:retriever opts)
                                                                  :lexical)))
                                  (local-vector-baseline! suite case opts)
                                  (agent-baseline! suite case opts))))
        baselines (mapv baseline-for-case
                        (selected-cases suite (case-selector opts)))]
    {:schema agent-baselines-schema
     :suite-id (:id suite)
     :baselines baselines
     :completed (count baselines)
     :skipped (count (filter #(= "skipped" (:status %)) baselines))}))

(defn- agent-run-command
  [opts]
  (or (some-> (:command opts) not-empty)
      (throw (ex-info "Missing agent command." {:option "--command"}))))

(defn- agent-run-timeout-ms
  [opts]
  (long (or (:timeout-ms opts) default-agent-run-timeout-ms)))

(defn- agent-prompt-profile
  [opts]
  (let [profile (name (keyword (or (:prompt-profile opts) :standard)))]
    (when-not ((set supported-agent-prompt-profiles) profile)
      (throw (ex-info "Unknown benchmark agent prompt profile."
                      {:prompt-profile profile
                       :supported supported-agent-prompt-profiles})))
    profile))

(defn- ensure-agent-run-id!
  [opts]
  (when (blankish? (:agent-id opts))
    (throw (ex-info "Missing benchmark agent id." {:option "--agent"}))))

(defn- process-output-future
  [stream]
  (future (slurp stream)))

(defn- wait-for-process
  [process timeout-ms]
  (let [finished? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      {:exit (.exitValue process)
       :timedOut false}
      (do
        (.destroyForcibly process)
        {:exit -1
         :timedOut true}))))

(defn- run-process!
  [command cwd env timeout-ms]
  (let [started-at (System/currentTimeMillis)
        process-builder (ProcessBuilder. ["sh" "-lc" command])
        process-env (.environment process-builder)]
    (.directory process-builder (io/file cwd))
    (doseq [[k v] env]
      (.put process-env k (str v)))
    (let [process (.start process-builder)
          out-future (process-output-future (.getInputStream process))
          err-future (process-output-future (.getErrorStream process))
          result (wait-for-process process timeout-ms)]
      (assoc result
             :durationMs (- (System/currentTimeMillis) started-at)
             :stdout @out-future
             :stderr @err-future))))

(defn- write-agent-run-logs!
  [suite case opts process-result]
  (let [stdout-path (benchmark-paths/agent-run-log-path suite case opts "stdout")
        stderr-path (benchmark-paths/agent-run-log-path suite case opts "stderr")]
    (benchmark-io/write-text-file! stdout-path (:stdout process-result))
    (benchmark-io/write-text-file! stderr-path (:stderr process-result))
    {:stdoutPath (fs/canonical-path stdout-path)
     :stderrPath (fs/canonical-path stderr-path)}))

(defn- json-example
  [value]
  (json/write-json-str value {:indent-str "  "}))

(defn- write-agent-output-schema!
  [suite case opts]
  (let [schema-path (benchmark-paths/agent-run-output-schema-path suite case opts)]
    (benchmark-io/write-json-file! schema-path (agent-result-json-schema))
    (fs/canonical-path schema-path)))

(defn- agent-prompt-profile-lines
  [profile]
  (case profile
    "fast" ["## Fast Localization Profile"
            "- Localization only. Do not patch files."
            "- Do not run full test/build suites."
            "- Use at most 8 local shell commands."
            "- Inspect at most 12 files or snippets."
            "- Prefer `rg`, focused `sed`, and packet-provided AGraph ask/explore commands."
            "- Return the best 1-5 suspected files as soon as evidence is sufficient."
            (str "- " suspected-files-scope-rule)
            "- If structured output is active, make the final response the result JSON."
            "- Otherwise write JSON to `AGRAPH_BENCH_RESULT`; do not include prose outside JSON."
            ""]
    []))

(defn- agent-run-prompt
  [packet result-path output-schema-path opts]
  (let [profile (agent-prompt-profile opts)]
    (str/join
     "\n"
     (concat
      ["# AGraph Issue Localization Benchmark"
       ""
       "You are evaluating a coding-agent workflow against one real issue."
       "Use only the base checkout, the issue text in the packet, and AGraph output generated from that base checkout."
       "Do not inspect the fixing diff, PR body, post-fix commits, or benchmark ground-truth artifacts."
       ""
       "## Files"
       (str "- Packet JSON: " (get-in packet [:artifacts :packetPath]))
       (str "- Result JSON to write: " (fs/canonical-path result-path))
       (str "- Output JSON Schema: " output-schema-path)
       (str "- Worktree: " (:worktreeRoot packet))
       (str "- Project config: " (get-in packet [:artifacts :projectConfig]))
       (str "- XTDB path: " (get-in packet [:artifacts :xtdbPath]))
       (when-let [hints-path (get-in packet [:artifacts :agraphHintsPath])]
         (str "- AGraph hints JSON: " hints-path))
       (when-let [context-path (get-in packet [:artifacts :agraphContextPath])]
         (str "- AGraph context JSON: " context-path))
       ""
       "## Environment"
       "- `AGRAPH_BENCH_PACKET` points to the packet JSON."
       "- `AGRAPH_BENCH_AGRAPH_HINTS` points to compact AGraph hints when available."
       "- `AGRAPH_BENCH_AGRAPH_CONTEXT` points to precomputed AGraph context when available."
       "- `AGRAPH_BENCH_OUTPUT_SCHEMA` points to the JSON Schema for the result."
       "- `AGRAPH_BENCH_PROMPT_PROFILE` identifies the prompt profile for this run."
       "- `AGRAPH_BENCH_RESULT` is the only result file scored by the benchmark."
       "- `AGRAPH_BENCH_WORKTREE` is the base checkout."
       "- `AGRAPH_BENCH_PROJECT` is the generated AGraph project config."
       "- `AGRAPH_BENCH_XTDB_PATH` and `AGRAPH_XTDB_PATH` point to the graph store."
       ""]
      (agent-prompt-profile-lines profile)
      ["## Task"
       (get-in packet [:task :objective])
       ""
       "Read the packet, inspect the checkout, and write the ranked localization result JSON."
       "Return files before proposing or applying a patch."
       suspected-files-scope-rule
       ""
       "## Result Contract"
       (str "Write JSON with schema `" agent-result-schema "` to `AGRAPH_BENCH_RESULT`.")
       "When your agent runner supports structured output, use `AGRAPH_BENCH_OUTPUT_SCHEMA`."
       "For structured-output runners that capture the final response, return only the JSON result as the final response and do not also shell-write the result file."
       "For plain shell runners, write the JSON result directly to `AGRAPH_BENCH_RESULT`."
       "Use repo-relative file paths from the base checkout."
       ""
       "```json"
       (json-example (agent-result-contract))
       "```"
       ""
       "## AGraph Mode"
       (if (= "agraph" (:mode packet))
         (str "AGraph is available and has already been prepared for this run. "
              "Read `AGRAPH_BENCH_AGRAPH_HINTS` first when it is set; use "
              "`AGRAPH_BENCH_AGRAPH_CONTEXT` for supporting snippets. In the "
              "hints, prefer `topFiles`, `architecture`, and `auditScopes` "
              "before broad shell search; treat `answerability`, "
              "`sourceCoverage`, and `diagnostics` as trust boundaries. Use "
              "`commands` as bounded follow-up checks, especially commands "
              "copied from `architecture.validationGaps.nextActions` when a "
              "plane is missing or weak. Use live ask/explore commands only if "
              "the context artifact is missing or insufficient; run setup only "
              "if graph commands report missing data.")
         "AGraph is not part of this run. Use ordinary local shell inspection only.")
       ""
       "## Run Metadata"
       (str "- Suite: " (:suite-id packet))
       (str "- Case: " (:case-id packet))
       (str "- Repo: " (:repo-id packet))
       (str "- Project: " (:project-id packet))
       (str "- Agent: " (:agent-id opts))
       (str "- Mode: " (:mode packet))
       (str "- Prompt profile: " profile)
       ""]))))

(defn- write-agent-run-prompt!
  [suite case packet result-path output-schema-path opts]
  (let [prompt-path (benchmark-paths/agent-run-prompt-path suite case opts)]
    (benchmark-io/write-text-file! prompt-path
                                   (agent-run-prompt packet
                                                     result-path
                                                     output-schema-path
                                                     opts))
    (fs/canonical-path prompt-path)))

(defn- normalize-agent-run-result
  [prepared agent-result opts]
  (assoc (normalize-agent-result-identity agent-result prepared)
         :agentId (:agent-id opts)
         :mode (agent-mode opts)
         :suspectedSymbols (vec (or (:suspectedSymbols agent-result)
                                    (:suspected-symbols agent-result)
                                    []))
         :commands (vec (or (:commands agent-result) []))
         :warnings (vec (or (:warnings agent-result) []))
         :summary (or (:summary agent-result) "")))

(defn- failure-agent-result
  [prepared opts warning]
  {:schema agent-result-schema
   :caseId (:case-id prepared)
   :caseFingerprint (:caseFingerprint prepared)
   :agentId (:agent-id opts)
   :mode (agent-mode opts)
   :suspectedFiles []
   :suspectedSymbols []
   :commands [(agent-run-command opts)]
   :warnings [warning]
   :summary warning})

(defn- read-agent-run-result
  [prepared result-path opts process-result]
  (cond
    (not (zero? (:exit process-result)))
    {:agent-result (failure-agent-result
                    prepared
                    opts
                    (if (:timedOut process-result)
                      (str "Agent command timed out after "
                           (agent-run-timeout-ms opts)
                           " ms.")
                      (str "Agent command exited with status " (:exit process-result) ".")))
     :artifact-ok? false}

    (not (.isFile (io/file result-path)))
    {:agent-result (failure-agent-result
                    prepared
                    opts
                    "Agent command completed but did not write the result JSON artifact.")
     :artifact-ok? false}

    :else
    (try
      {:agent-result (normalize-agent-run-result prepared (benchmark-io/read-json-file result-path) opts)
       :artifact-ok? true}
      (catch Exception e
        {:agent-result (failure-agent-result
                        prepared
                        opts
                        (str "Agent command wrote unreadable result JSON: " (.getMessage e)))
         :artifact-ok? false}))))

(defn- agent-run-env
  [packet result-path prompt-path output-schema-path opts]
  (cond-> {"AGRAPH_BENCH_SUITE_ID" (:suite-id packet)
           "AGRAPH_BENCH_CASE_ID" (:case-id packet)
           "AGRAPH_BENCH_CASE_FINGERPRINT" (:caseFingerprint packet)
           "AGRAPH_BENCH_REPO_ID" (:repo-id packet)
           "AGRAPH_BENCH_PROJECT_ID" (:project-id packet)
           "AGRAPH_BENCH_MODE" (:mode packet)
           "AGRAPH_BENCH_AGENT_ID" (:agent-id opts)
           "AGRAPH_BENCH_PACKET" (get-in packet [:artifacts :packetPath])
           "AGRAPH_BENCH_PROMPT" prompt-path
           "AGRAPH_BENCH_PROMPT_PROFILE" (agent-prompt-profile opts)
           "AGRAPH_BENCH_OUTPUT_SCHEMA" output-schema-path
           "AGRAPH_BENCH_RESULT" (fs/canonical-path result-path)
           "AGRAPH_BENCH_WORKTREE" (:worktreeRoot packet)
           "AGRAPH_BENCH_PROJECT" (get-in packet [:artifacts :projectConfig])
           "AGRAPH_BENCH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])
           "AGRAPH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])}
    (parser-worker-option opts)
    (assoc "AGRAPH_BENCH_PARSER_WORKER" (parser-worker-option opts)
           "AGRAPH_PARSER_WORKER" (parser-worker-option opts))
    (get-in packet [:artifacts :agraphContextPath])
    (assoc "AGRAPH_BENCH_AGRAPH_CONTEXT"
           (get-in packet [:artifacts :agraphContextPath]))
    (get-in packet [:artifacts :agraphHintsPath])
    (assoc "AGRAPH_BENCH_AGRAPH_HINTS"
           (get-in packet [:artifacts :agraphHintsPath]))))

(defn- prepare-agent-graph-and-artifacts!
  [suite case prepared opts]
  (when (= "agraph" (agent-mode opts))
    (let [context-path (benchmark-paths/agent-run-context-path suite case opts)
          hints-path (benchmark-paths/agent-run-hints-path suite case opts)]
      (store/with-node (benchmark-paths/xtdb-dir suite case opts)
        (fn [xtdb]
          (let [bench-project (agent-project prepared)
                summary {:indexSummary (with-benchmark-parser-worker
                                         opts
                                         #(project/index-project! xtdb
                                                                  bench-project
                                                                  (benchmark-index-options opts)))
                         :systemSummary (project/infer-project! xtdb bench-project)
                         :graphExpectations (evaluate-graph-expectations xtdb
                                                                         prepared)}
                packet (context/context-packet xtdb
                                               (get-in prepared [:input :queryText])
                                               (agent-baseline-context-options
                                                prepared
                                                opts))
                hints (context-packet->agent-hints prepared packet opts)]
            (benchmark-io/write-json-file! context-path packet)
            (benchmark-io/write-json-file! hints-path hints)
            {:summary summary
             :artifacts {:context-path (fs/canonical-path context-path)
                         :hints-path (fs/canonical-path hints-path)}}))))))

(defn- read-agent-hints-diagnostics
  [hints-path]
  (when (and (not (blankish? hints-path))
             (.isFile (io/file hints-path)))
    (let [hints (benchmark-io/read-json-file hints-path)]
      (not-empty (vec (:diagnostics hints))))))

(defn agent-run!
  "Run one external agent command against a benchmark packet, then score it."
  [suite case opts]
  (ensure-agent-run-id! opts)
  (let [prepared (prepare-case! suite case opts)
        agraph-prep (prepare-agent-graph-and-artifacts! suite case prepared opts)
        agraph-summary (:summary agraph-prep)
        agraph-artifacts (:artifacts agraph-prep)
        packet (agent-packet-from-prepared! suite
                                            case
                                            prepared
                                            (cond-> opts
                                              (:context-path agraph-artifacts)
                                              (assoc :agraph-context-path
                                                     (:context-path agraph-artifacts))
                                              (:hints-path agraph-artifacts)
                                              (assoc :agraph-hints-path
                                                     (:hints-path agraph-artifacts))))
        result-path (benchmark-paths/agent-run-result-path suite case opts)
        run-path (benchmark-paths/agent-run-path suite case opts)
        score-path (benchmark-paths/agent-score-path suite case opts result-path)
        output-schema-path (write-agent-output-schema! suite case opts)
        prompt-path (write-agent-run-prompt! suite
                                             case
                                             packet
                                             result-path
                                             output-schema-path
                                             opts)
        command (agent-run-command opts)
        timeout-ms (agent-run-timeout-ms opts)
        _ (benchmark-io/ensure-parent! result-path)
        process-result (run-process! command
                                     (:worktreeRoot prepared)
                                     (agent-run-env packet
                                                    result-path
                                                    prompt-path
                                                    output-schema-path
                                                    opts)
                                     timeout-ms)
        logs (write-agent-run-logs! suite case opts process-result)
        read-result (read-agent-run-result prepared result-path opts process-result)
        agent-result (:agent-result read-result)
        _ (benchmark-io/write-json-file! result-path agent-result)
        context-ranks (context-ground-truth-ranks-from-path
                       prepared
                       (get-in packet [:artifacts :agraphContextPath]))
        hint-diagnostics (read-agent-hints-diagnostics
                          (get-in packet [:artifacts :agraphHintsPath]))
        scored (cond-> (assoc (score-agent-result prepared agent-result)
                              :agentResultPath (fs/canonical-path result-path)
                              :parserWorker (parser-worker-profile opts))
                 context-ranks
                 (assoc :contextGroundTruthRanks context-ranks)
                 hint-diagnostics
                 (assoc :agraphHints {:diagnostics hint-diagnostics})
                 (:graphExpectations agraph-summary)
                 (assoc :graphExpectations (:graphExpectations agraph-summary)))
        status (if (:artifact-ok? read-result)
                 "passed"
                 "failed")
        run {:schema agent-run-schema
             :suite-id (:suite-id prepared)
             :case-id (:case-id prepared)
             :repo-id (:repo-id prepared)
             :project-id (:project-id prepared)
             :agentId (:agent-id opts)
             :mode (agent-mode opts)
             :promptProfile (agent-prompt-profile opts)
             :status status
             :command command
             :timeoutMs timeout-ms
             :process (select-keys process-result [:exit :timedOut :durationMs])
             :artifacts (merge {:packetPath (get-in packet [:artifacts :packetPath])
                                :promptPath prompt-path
                                :outputSchemaPath output-schema-path
                                :agraphHintsPath (get-in packet
                                                         [:artifacts :agraphHintsPath])
                                :agraphContextPath (get-in packet
                                                           [:artifacts :agraphContextPath])
                                :projectConfig (get-in packet [:artifacts :projectConfig])
                                :xtdbPath (get-in packet [:artifacts :xtdbPath])
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :agentRunPath (fs/canonical-path run-path)}
                               logs)
             :agraph agraph-summary
             :graphExpectations (:graphExpectations agraph-summary)
             :scores (:scores scored)
             :warnings (get-in scored [:agent :warnings])}]
    (benchmark-io/write-json-file! score-path scored)
    (benchmark-io/write-json-file! run-path run)
    run))

(defn agent-runs!
  "Run an external agent command for selected benchmark cases."
  [suite opts]
  (ensure-agent-run-id! opts)
  (let [run-for-case (fn [case]
                       (or (when (:skip-existing? opts)
                             (some->> {:agent-id (:agent-id opts)
                                       :mode (agent-mode opts)
                                       :result-path (benchmark-paths/agent-run-result-path suite case opts)}
                                      (reusable-agent-score suite case opts)
                                      (skipped-agent-run suite case opts)))
                           (agent-run! suite case opts)))
        runs (mapv run-for-case
                   (selected-cases suite (case-selector opts)))]
    {:schema agent-runs-schema
     :suite-id (:id suite)
     :runs runs
     :completed (count runs)
     :failed (count (filter #(= "failed" (:status %)) runs))
     :skipped (count (filter #(= "skipped" (:status %)) runs))}))

(defn score-agent-result!
  "Read, score, and write one agent localization result artifact."
  [suite case opts]
  (let [result-file (or (:result-path opts)
                        (throw (ex-info "Missing agent result path."
                                        {:case-id (:id case)})))
        prepared (prepare-case! suite case opts)
        agent-result (benchmark-io/read-json-file result-file)
        scored (assoc (score-agent-result prepared agent-result)
                      :parserWorker (agent-score-parser-worker-profile
                                     opts
                                     agent-result)
                      :agentResultPath (fs/canonical-path result-file))]
    (benchmark-io/write-json-file! (benchmark-paths/agent-score-path suite case opts result-file) scored)
    scored))

(defn- progress-summary
  [suite case opts]
  (benchmark-results/progress-summary suite case opts))

(defn- aggregate-progress
  [summaries]
  (benchmark-results/aggregate-progress summaries))

(defn case-result
  "Read one case result when it exists."
  [suite case opts]
  (benchmark-results/case-result suite case opts))

(defn show-case
  "Return one case result, or its prepared artifact when no result exists."
  [suite case-id opts]
  (benchmark-results/show-case suite case-id opts))

(defn- json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))

(defn- agent-score-files
  [suite case opts]
  (let [dir (benchmark-paths/agent-score-dir suite case opts)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter json-file?)
           (sort-by #(.getPath %))
           vec))))

(defn- agent-score-results
  [suite case opts]
  (let [expected-parser-worker-mode (parser-worker-option opts)
        parser-worker-match? (fn [score]
                               (or (blankish? expected-parser-worker-mode)
                                   (= expected-parser-worker-mode
                                      (get-in score [:parserWorker :mode]))))]
    (->> (agent-score-files suite case opts)
         (map benchmark-io/read-json-file)
         (filter #(or (blankish? (:mode opts))
                      (= (:mode opts) (get-in % [:agent :mode]))))
         (filter #(or (blankish? (:agent-id opts))
                      (= (:agent-id opts) (get-in % [:agent :agentId]))))
         (filter parser-worker-match?)
         vec)))

(defn- average
  [values]
  (if (seq values)
    (/ (double (reduce + values)) (double (count values)))
    0.0))

(defn- aggregate-scores
  [results]
  (let [score-keys [:fileRecallAt5
                    :fileRecallAt10
                    :fileRecallAt20
                    :meanReciprocalRankFile
                    :noiseRatioAt20
                    :evidenceCitationRate
                    :pathEvidenceCitationRate]]
    (into {}
          (map (fn [k]
                 [k (average (keep #(get-in % [:scores k]) results))]))
          score-keys)))

(defn- sum-score
  [results k]
  (reduce + 0 (keep #(get-in % [:scores k]) results)))

(defn- aggregate-agent-scores
  [results]
  (assoc (aggregate-scores results)
         :changedFiles (sum-score results :changedFiles)
         :scoreableChangedFiles (sum-score results :scoreableChangedFiles)
         :unsupportedGroundTruthFiles (sum-score results :unsupportedGroundTruthFiles)
         :coverageExcludedGroundTruthFiles (sum-score results
                                                      :coverageExcludedGroundTruthFiles)))

(defn- input-hint-summary
  [results]
  (let [hinted (filter #(get-in % [:inputHints :hinted]) results)
        hinted-cases (->> hinted
                          (map :case-id)
                          set
                          sort
                          vec)]
    {:inputHintedRuns (count hinted)
     :inputHintedCases (count hinted-cases)
     :inputHintedCaseIds hinted-cases}))

(defn- command-telemetry
  [commands]
  (benchmark-command-telemetry/command-telemetry commands))

(defn- aggregate-command-telemetry
  [diagnostics]
  (benchmark-command-telemetry/aggregate-command-telemetry diagnostics))

(defn- agent-output-diagnostic
  [result]
  (let [top-files (get-in result [:agent :topFiles])
        warnings (vec (get-in result [:agent :warnings]))
        hint-diagnostics (vec (get-in result [:agraphHints :diagnostics]))
        identity-warnings (filterv #(or (str/starts-with? % "agent result schema ")
                                        (str/starts-with? % "agent result caseId ")
                                        (str/starts-with? % "agent result caseFingerprint "))
                                   warnings)
        missing-files (vec (get-in result [:agent :missingPredictedFiles]))
        commands (vec (get-in result [:agent :commands]))
        selection (get-in result [:agent :selection])
        raw-count (long (or (get-in result [:agent :rawSuspectedFileCount])
                            (count top-files)))
        ranked-count (long (count top-files))
        command-telemetry (command-telemetry commands)
        command-count (:commandCount command-telemetry)
        candidate-count (:candidateFiles selection)
        filtered-count (long (or (:coverageFilteredCandidateFiles selection) 0))]
    (cond-> {:rawSuspectedFiles raw-count
             :rankedFiles ranked-count
             :commandCount command-count
             :commandTelemetry command-telemetry
             :missingPredictedFiles missing-files
             :warnings warnings
             :warningCount (count warnings)
             :hasWarnings (boolean (seq warnings))
             :hintDiagnostics hint-diagnostics
             :hintDiagnosticCount (count hint-diagnostics)
             :hasHintDiagnostics (boolean (seq hint-diagnostics))
             :identityWarnings identity-warnings
             :hasIdentityMismatch (boolean (seq identity-warnings))
             :emptyResult (zero? ranked-count)
             :commandless (:commandless command-telemetry)
             :noRawSuspectedFiles (zero? raw-count)}
      selection
      (assoc :selection selection)

      (some? candidate-count)
      (assoc :zeroCandidateFiles (zero? (long candidate-count)))

      (pos? filtered-count)
      (assoc :coverageFilteredCandidateFiles filtered-count))))

(defn- aggregate-hint-diagnostics
  [result-pairs]
  (let [rows (->> result-pairs
                  (mapcat (fn [[result diagnostic]]
                            (map #(assoc % :case-id (:case-id result))
                                 (:hintDiagnostics diagnostic)))))]
    {:hintDiagnosticRows (count rows)
     :hintDiagnosticRuns (count (filter (comp :hasHintDiagnostics second)
                                        result-pairs))
     :hintDiagnosticCaseIds (->> result-pairs
                                 (filter (comp :hasHintDiagnostics second))
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec)
     :hintDiagnosticsByKind (->> rows
                                 (group-by :kind)
                                 (map (fn [[kind kind-rows]]
                                        {:kind kind
                                         :runs (count kind-rows)
                                         :cases (count (set (map :case-id
                                                                 kind-rows)))
                                         :caseIds (->> kind-rows
                                                       (map :case-id)
                                                       distinct
                                                       sort
                                                       vec)}))
                                 (sort-by :kind)
                                 vec)}))

(defn- aggregate-agent-diagnostics
  [results]
  (let [diagnostics (map agent-output-diagnostic results)
        result-pairs (map vector results diagnostics)
        empty-results (filter (fn [[_ diagnostic]]
                                (:emptyResult diagnostic))
                              result-pairs)
        zero-candidates (filter (fn [[_ diagnostic]]
                                  (:zeroCandidateFiles diagnostic))
                                result-pairs)
        coverage-filtered (filter (fn [[_ diagnostic]]
                                    (pos? (long (or (:coverageFilteredCandidateFiles diagnostic)
                                                    0))))
                                  result-pairs)
        missing-predicted (filter (fn [[_ diagnostic]]
                                    (seq (:missingPredictedFiles diagnostic)))
                                  result-pairs)
        commandless-results (filter (fn [[_ diagnostic]]
                                      (:commandless diagnostic))
                                    result-pairs)
        warning-results (filter (fn [[_ diagnostic]]
                                  (:hasWarnings diagnostic))
                                result-pairs)
        identity-mismatch-results (filter (fn [[_ diagnostic]]
                                            (:hasIdentityMismatch diagnostic))
                                          result-pairs)]
    (merge
     {:emptyResultRuns (count empty-results)
      :emptyResultCaseIds (->> empty-results
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec)
      :zeroCandidateRuns (count zero-candidates)
      :zeroCandidateCaseIds (->> zero-candidates
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec)
      :coverageFilteredRuns (count coverage-filtered)
      :coverageFilteredCaseIds (->> coverage-filtered
                                    (map (comp :case-id first))
                                    distinct
                                    sort
                                    vec)
      :coverageFilteredCandidateFiles (reduce + 0
                                              (map (comp #(long (or % 0))
                                                         :coverageFilteredCandidateFiles
                                                         second)
                                                   coverage-filtered))
      :missingPredictedFileRuns (count missing-predicted)
      :missingPredictedFileCaseIds (->> missing-predicted
                                        (map (comp :case-id first))
                                        distinct
                                        sort
                                        vec)
      :missingPredictedFiles (reduce + 0
                                     (map (comp count
                                                :missingPredictedFiles
                                                second)
                                          missing-predicted))
      :commandlessRuns (count commandless-results)
      :commandlessCaseIds (->> commandless-results
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec)
      :warningRuns (count warning-results)
      :warningCaseIds (->> warning-results
                           (map (comp :case-id first))
                           distinct
                           sort
                           vec)
      :identityMismatchRuns (count identity-mismatch-results)
      :identityMismatchCaseIds (->> identity-mismatch-results
                                    (map (comp :case-id first))
                                    distinct
                                    sort
                                    vec)
      :identityMismatches (reduce + 0
                                  (map (comp count :identityWarnings second)
                                       identity-mismatch-results))
      :commandTelemetry (aggregate-command-telemetry diagnostics)
      :warnings (reduce + 0
                        (map (comp count :warnings second)
                             warning-results))}
     (aggregate-hint-diagnostics result-pairs))))

(defn- parser-worker-result-profile
  [result]
  (let [profile (:parserWorker result)
        mode (some-> (:mode profile) str not-empty)
        source (some-> (:source profile) str not-empty)]
    {:mode (or mode "unknown")
     :source (or source (if mode "unknown" "missing"))}))

(defn- parser-worker-profile-key
  [profile]
  [(:mode profile) (:source profile)])

(defn- aggregate-parser-worker-profiles
  [results]
  (->> results
       (group-by (comp parser-worker-profile-key parser-worker-result-profile))
       (map (fn [[[_mode _source] rows]]
              (let [profile (parser-worker-result-profile (first rows))]
                (assoc profile
                       :runs (count rows)
                       :cases (count (set (map :case-id rows)))
                       :caseIds (->> rows
                                     (map :case-id)
                                     distinct
                                     sort
                                     vec)))))
       (sort-by (juxt :mode :source))
       vec))

(defn- artifact-diagnostic
  [expected-fingerprints result]
  (let [expected (get expected-fingerprints (:case-id result))
        actual (:caseFingerprint result)
        actual-schema (:schema result)
        schema-current? (= agent-score-schema actual-schema)
        agent-result-schema-value (get-in result [:agent :schema])
        agent-result-schema-current? (= agent-result-schema
                                        agent-result-schema-value)
        status (cond
                 (not schema-current?) "legacy"
                 (not agent-result-schema-current?) "legacy"
                 (blankish? actual) "legacy"
                 (= actual expected) "current"
                 :else "stale")]
    (cond-> {:fingerprintStatus status
             :scoreSchemaStatus (if schema-current? "current" "legacy")
             :expectedScoreSchema agent-score-schema
             :agentResultSchemaStatus (if agent-result-schema-current?
                                        "current"
                                        "legacy")
             :expectedAgentResultSchema agent-result-schema}
      actual-schema (assoc :scoreSchema actual-schema)
      agent-result-schema-value (assoc :agentResultSchema agent-result-schema-value)
      actual (assoc :caseFingerprint actual)
      expected (assoc :expectedCaseFingerprint expected))))

(defn- aggregate-artifact-diagnostics
  [expected-fingerprints results]
  (let [result-pairs (map (fn [result]
                            [result (artifact-diagnostic expected-fingerprints result)])
                          results)
        by-status (group-by (comp :fingerprintStatus second) result-pairs)
        by-schema-status (group-by (comp :scoreSchemaStatus second) result-pairs)
        by-agent-result-schema-status (group-by (comp :agentResultSchemaStatus second)
                                                result-pairs)
        case-ids (fn [status]
                   (->> (get by-status status)
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))
        schema-case-ids (fn [status]
                          (->> (get by-schema-status status)
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec))
        schemas (->> (get by-schema-status "legacy")
                     (map (comp :scoreSchema second))
                     (filter some?)
                     distinct
                     sort
                     vec)
        agent-result-schema-case-ids (fn [status]
                                       (->> (get by-agent-result-schema-status status)
                                            (map (comp :case-id first))
                                            distinct
                                            sort
                                            vec))
        agent-result-schemas (->> (get by-agent-result-schema-status "legacy")
                                  (map (comp :agentResultSchema second))
                                  (filter some?)
                                  distinct
                                  sort
                                  vec)]
    {:currentScoreRuns (count (get by-status "current"))
     :legacyScoreRuns (count (get by-status "legacy"))
     :legacyScoreCaseIds (case-ids "legacy")
     :obsoleteScoreSchemaRuns (count (get by-schema-status "legacy"))
     :obsoleteScoreSchemaCaseIds (schema-case-ids "legacy")
     :obsoleteScoreSchemas schemas
     :expectedScoreSchema agent-score-schema
     :obsoleteAgentResultSchemaRuns (count (get by-agent-result-schema-status
                                                "legacy"))
     :obsoleteAgentResultSchemaCaseIds (agent-result-schema-case-ids "legacy")
     :obsoleteAgentResultSchemas agent-result-schemas
     :expectedAgentResultSchema agent-result-schema
     :staleScoreRuns (count (get by-status "stale"))
     :staleScoreCaseIds (case-ids "stale")
     :unverifiedScoreRuns (+ (count (get by-status "legacy"))
                             (count (get by-status "stale")))
     :unverifiedScoreCaseIds (vec (sort (set/union
                                         (set (case-ids "legacy"))
                                         (set (case-ids "stale")))))}))

(defn- current-score-artifact?
  [expected-fingerprints result]
  (= "current" (:fingerprintStatus (artifact-diagnostic expected-fingerprints result))))

(defn- ranked-outside-blockers
  [ranks blockers-before n]
  (->> ranks
       (filter #(and (:found? %)
                     (> (long (:rank %)) n)))
       (mapv (fn [ranked-file]
               (let [blocking-files (blockers-before (:rank ranked-file))]
                 {:path (:path ranked-file)
                  :rank (:rank ranked-file)
                  :blockingFileCount (count blocking-files)
                  :blockingFiles blocking-files})))))

(defn- artifact-policy
  [expected-fingerprints raw-results included-results allow-unverified?]
  (let [included (set included-results)
        excluded (remove included raw-results)]
    {:allowUnverifiedScores (boolean allow-unverified?)
     :matchedRuns (count raw-results)
     :includedRuns (count included-results)
     :excludedRuns (count excluded)
     :excludedCaseIds (->> excluded
                           (map :case-id)
                           distinct
                           sort
                           vec)
     :excludedUnverifiedRuns (count (remove #(current-score-artifact?
                                              expected-fingerprints
                                              %)
                                            raw-results))}))

(defn- expectation-configured?
  [result]
  (let [expectations (:expectations result)]
    (or (seq (:evidence expectations))
        (seq (:nodes expectations))
        (seq (:chunks expectations))
        (seq (:edges expectations))
        (seq (:forbidden-nodes expectations))
        (seq (:forbidden-chunks expectations))
        (seq (:forbidden-edges expectations)))))

(defn- graph-expectation-diagnostic
  [result]
  (let [graph-expectations (:graphExpectations result)]
    (cond
      graph-expectations
      {:status (:status graph-expectations)
       :summary (:summary graph-expectations)}

      (expectation-configured? result)
      {:status "not-run"
       :summary {:expectedEvidence (count (get-in result [:expectations :evidence]))
                 :expectedNodes (count (get-in result [:expectations :nodes]))
                 :expectedChunks (count (get-in result [:expectations :chunks]))
                 :expectedEdges (count (get-in result [:expectations :edges]))
                 :forbiddenNodes (count (get-in result [:expectations :forbidden-nodes]))
                 :forbiddenChunks (count (get-in result [:expectations :forbidden-chunks]))
                 :forbiddenEdges (count (get-in result [:expectations :forbidden-edges]))}}

      :else nil)))

(defn- aggregate-graph-expectation-diagnostics
  [results]
  (let [pairs (->> results
                   (keep (fn [result]
                           (when-let [diagnostic (graph-expectation-diagnostic result)]
                             [result diagnostic]))))
        by-status (group-by (comp :status second) pairs)
        case-ids (fn [status]
                   (->> (get by-status status)
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))]
    {:configuredRuns (count pairs)
     :passedRuns (count (get by-status "passed"))
     :passedCaseIds (case-ids "passed")
     :failedRuns (count (get by-status "failed"))
     :failedCaseIds (case-ids "failed")
     :notRunRuns (count (get by-status "not-run"))
     :notRunCaseIds (case-ids "not-run")}))

(declare aggregate-localization-diagnostics aggregate-coverage-diagnostics)

(defn- group-agent-scores
  [expected-fingerprints results key-path]
  (->> results
       (group-by #(or (get-in % key-path) "unknown"))
       (map (fn [[k rows]]
              {:key k
               :runs (count rows)
               :scores (aggregate-agent-scores rows)
               :inputHints (input-hint-summary rows)
               :agentDiagnostics (aggregate-agent-diagnostics rows)
               :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics rows)
               :localizationDiagnostics (aggregate-localization-diagnostics rows)
               :coverageDiagnostics (aggregate-coverage-diagnostics rows)
               :artifactDiagnostics (aggregate-artifact-diagnostics
                                     expected-fingerprints
                                     rows)}))
       (sort-by :key)
       vec))

(defn- result-tags
  [result]
  (->> (:tags result)
       (keep normalize-case-tag)
       distinct
       sort
       vec))

(defn- group-agent-scores-by-filtered-tag
  [expected-fingerprints results include-tag?]
  (->> results
       (mapcat (fn [result]
                 (map (fn [tag] [tag result])
                      (filter include-tag? (result-tags result)))))
       (group-by first)
       (map (fn [[tag pairs]]
              (let [rows (mapv second pairs)]
                {:key tag
                 :cases (count (set (map :case-id rows)))
                 :runs (count rows)
                 :scores (aggregate-agent-scores rows)
                 :inputHints (input-hint-summary rows)
                 :agentDiagnostics (aggregate-agent-diagnostics rows)
                 :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics rows)
                 :localizationDiagnostics (aggregate-localization-diagnostics rows)
                 :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                 :artifactDiagnostics (aggregate-artifact-diagnostics
                                       expected-fingerprints
                                       rows)})))
       (sort-by :key)
       vec))

(defn- group-agent-scores-by-tag
  [expected-fingerprints results]
  (group-agent-scores-by-filtered-tag expected-fingerprints
                                      results
                                      (constantly true)))

(defn- add-problem-class-claim-status
  [row]
  (assoc row
         :minimumCases problem-class-minimum-cases
         :claimStatus (if (<= problem-class-minimum-cases (:cases row))
                        "measured"
                        "insufficient-cases")))

(defn- problem-class-summary
  [expected-fingerprints results]
  {:minimumCasesForClassClaim problem-class-minimum-cases
   :classes (mapv add-problem-class-claim-status
                  (group-agent-scores-by-filtered-tag expected-fingerprints
                                                      results
                                                      benchmark-classes/problem-class-tag?))
   :architectureClasses (mapv add-problem-class-claim-status
                              (group-agent-scores-by-filtered-tag
                               expected-fingerprints
                               results
                               benchmark-classes/architecture-class-tag?))})

(defn- measured-class-tags
  [problem-classes class-key]
  (->> (get problem-classes class-key)
       (filter #(= "measured" (:claimStatus %)))
       (keep #(some-> (:key %) str))
       sort
       vec))

(defn- report-claim-readiness
  [report]
  (let [problem-classes (:problemClasses report)
        measured-problem-tags (measured-class-tags problem-classes :classes)
        measured-architecture-tags (measured-class-tags problem-classes
                                                        :architectureClasses)
        completed? (and (pos? (long (:cases report)))
                        (= (long (:cases report))
                           (long (:completed report))))
        has-runs? (pos? (long (:runs report)))
        evidence-metrics? (and has-runs?
                               (number? (get-in report
                                                [:scores
                                                 :evidenceCitationRate]))
                               (number? (get-in report
                                                [:scores
                                                 :pathEvidenceCitationRate])))
        command-telemetry? (and has-runs?
                                (map? (get-in report
                                              [:agentDiagnostics
                                               :commandTelemetry])))
        requirements {:completedCases completed?
                      :hasRuns has-runs?
                      :measuredProblemClasses (boolean (seq measured-problem-tags))
                      :measuredArchitectureClasses (boolean
                                                    (seq measured-architecture-tags))
                      :evidenceCitationMetrics evidence-metrics?
                      :commandTelemetry command-telemetry?}
        supported? (every? true? (vals requirements))]
    {:status (if supported? "supported" "not-supported")
     :broadArchitectureClaimSupported supported?
     :measuredProblemClassTags measured-problem-tags
     :measuredArchitectureClassTags measured-architecture-tags
     :requirements requirements
     :warnings (cond-> []
                 (not completed?)
                 (conj "Not all selected cases completed; do not use this report for broad benchmark claims.")

                 (not has-runs?)
                 (conj "No agent score runs are included in this report.")

                 (empty? measured-problem-tags)
                 (conj "No measured problem-class groups; include enough cases per class before claiming representative gains.")

                 (empty? measured-architecture-tags)
                 (conj "No measured architecture-class groups; architecture tags are present only below the class-claim threshold or absent.")

                 (not evidence-metrics?)
                 (conj "Evidence citation metrics are unavailable; citation quality is unproven.")

                 (not command-telemetry?)
                 (conj "Command telemetry is unavailable; shell/search/read-loop costs are unproven."))}))

(defn- group-agent-scores-by-parser-worker
  [expected-fingerprints results]
  (->> results
       (group-by (comp parser-worker-profile-key parser-worker-result-profile))
       (map (fn [[[_mode _source] rows]]
              (let [profile (parser-worker-result-profile (first rows))]
                (assoc profile
                       :key (str (:mode profile) "/" (:source profile))
                       :runs (count rows)
                       :cases (count (set (map :case-id rows)))
                       :scores (aggregate-agent-scores rows)
                       :inputHints (input-hint-summary rows)
                       :agentDiagnostics (aggregate-agent-diagnostics rows)
                       :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics
                                                     rows)
                       :localizationDiagnostics (aggregate-localization-diagnostics rows)
                       :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                       :artifactDiagnostics (aggregate-artifact-diagnostics
                                             expected-fingerprints
                                             rows)))))
       (sort-by (juxt :mode :source))
       vec))

(defn- aggregate-case-tags
  [cases]
  (let [pairs (mapcat (fn [case]
                        (map (fn [tag] [tag (:id case)])
                             (case-tags case)))
                      cases)]
    {:tags (->> pairs (map first) distinct sort vec)
     :casesByTag (->> pairs
                      (group-by first)
                      (map (fn [[tag rows]]
                             {:tag tag
                              :cases (count (set (map second rows)))
                              :caseIds (->> rows
                                            (map second)
                                            distinct
                                            sort
                                            vec)}))
                      (sort-by :tag)
                      vec)}))

(defn- coverage-cases
  [results]
  (->> results
       (group-by :case-id)
       vals
       (keep first)))

(defn- aggregate-coverage
  [results]
  (let [cases (coverage-cases results)
        source-rows (mapcat #(get-in % [:coverage :scoreableFilesByKind]) cases)
        declared (->> cases
                      (mapcat #(get-in % [:coverage :declaredSourceKinds]))
                      distinct
                      sort
                      vec)
        missing (->> cases
                     (mapcat (fn [case]
                               (map (fn [kind]
                                      {:case-id (:case-id case)
                                       :kind kind})
                                    (get-in case
                                            [:coverage :missingDeclaredSourceKinds]))))
                     (sort-by (juxt :kind :case-id))
                     vec)
        case-kinds (->> cases
                        (mapcat (fn [case]
                                  (map (fn [kind]
                                         [kind (:case-id case)])
                                       (get-in case [:coverage :scoreableSourceKinds]))))
                        (group-by first))
        files-by-kind (->> source-rows
                           (group-by :kind)
                           (map (fn [[kind rows]]
                                  {:kind kind
                                   :cases (count (set (map second
                                                           (get case-kinds kind))))
                                   :scoreableFiles (reduce + (map :files rows))}))
                           (sort-by :kind)
                           vec)]
    {:declaredSourceKinds declared
     :scoreableSourceKinds (vec (sort (map :kind files-by-kind)))
     :scoreableFilesByKind files-by-kind
     :missingDeclaredSourceKinds missing}))

(defn- aggregate-kind-case-rows
  [rows]
  (->> rows
       (group-by :kind)
       (map (fn [[kind kind-rows]]
              {:kind kind
               :runs (count kind-rows)
               :cases (count (set (map :case-id kind-rows)))
               :caseIds (->> kind-rows
                             (map :case-id)
                             distinct
                             sort
                             vec)}))
       (sort-by :kind)
       vec))

(defn- aggregate-coverage-diagnostics
  [results]
  (let [missing-source-kind-rows (->> results
                                      (mapcat
                                       (fn [result]
                                         (map (fn [kind]
                                                {:case-id (:case-id result)
                                                 :kind kind})
                                              (get-in result
                                                      [:coverage :missingDeclaredSourceKinds])))))
        coverage-excluded (filter #(seq (get-in % [:groundTruth :coverageExcludedFiles]))
                                  results)
        unsupported (filter #(seq (get-in % [:groundTruth :unsupportedGroundTruthFiles]))
                            results)]
    {:runs (count results)
     :missingDeclaredSourceKindRuns (count missing-source-kind-rows)
     :missingDeclaredSourceKindCaseIds (->> missing-source-kind-rows
                                            (map :case-id)
                                            distinct
                                            sort
                                            vec)
     :missingDeclaredSourceKinds (aggregate-kind-case-rows
                                  missing-source-kind-rows)
     :coverageExcludedGroundTruthRuns (count coverage-excluded)
     :coverageExcludedGroundTruthCaseIds (->> coverage-excluded
                                              (map :case-id)
                                              distinct
                                              sort
                                              vec)
     :coverageExcludedGroundTruthFiles (reduce + 0
                                               (map #(count (get-in %
                                                                    [:groundTruth
                                                                     :coverageExcludedFiles]))
                                                    coverage-excluded))
     :unsupportedGroundTruthRuns (count unsupported)
     :unsupportedGroundTruthCaseIds (->> unsupported
                                         (map :case-id)
                                         distinct
                                         sort
                                         vec)
     :unsupportedGroundTruthFiles (reduce + 0
                                          (map #(count (get-in %
                                                               [:groundTruth
                                                                :unsupportedGroundTruthFiles]))
                                               unsupported))}))

(defn- localization-diagnostic
  [result]
  (let [ground-truth (:groundTruth result)
        ranks (get-in result [:groundTruthRanks :files])
        context-ranks (get-in result [:contextGroundTruthRanks :files])
        context-rank-by-path (into {} (map (juxt :path identity)) context-ranks)
        top-files (get-in result [:agent :topFiles])
        scoreable-file-set (set (benchmark-score/scoreable-changed-files ground-truth))
        uncited-ranked-files (->> top-files
                                  (remove benchmark-score/evidence-cited?)
                                  (mapv #(select-keys % [:path :rank])))
        path-uncited-ranked-files (->> top-files
                                       (remove benchmark-score/path-evidence-cited?)
                                       (mapv #(select-keys % [:path :rank])))
        missed (->> ranks
                    (remove :found?)
                    (mapv #(select-keys % [:path])))
        missed-present-in-context (->> missed
                                       (keep (fn [{:keys [path]}]
                                               (when-let [row (get context-rank-by-path path)]
                                                 (when (:found? row)
                                                   (select-keys row [:path :rank])))))
                                       vec)
        missed-absent-from-context (when (seq context-ranks)
                                     (->> missed
                                          (remove #(get-in context-rank-by-path
                                                           [(:path %) :found?]))
                                          (mapv #(select-keys % [:path]))))
        blocker-summary (fn [row]
                          (select-keys row
                                       [:path
                                        :rank
                                        :confidence
                                        :metrics]))
        blockers-before (fn [rank]
                          (->> top-files
                               (filter #(and (:rank %)
                                             (< (long (:rank %)) (long rank))))
                               (remove #(contains? scoreable-file-set (:path %)))
                               (sort-by (juxt :rank :path))
                               (take rank-blocker-limit)
                               (mapv blocker-summary)))
        ranked-outside (fn [n]
                         (->> ranks
                              (filter #(and (:found? %)
                                            (> (long (:rank %)) n)))
                              (mapv #(select-keys % [:path :rank]))))
        diagnostic {:scoreableFiles (benchmark-score/scoreable-changed-files ground-truth)
                    :coverageExcludedFiles (vec (:coverageExcludedFiles ground-truth))
                    :unsupportedGroundTruthFiles (vec (:unsupportedGroundTruthFiles ground-truth))
                    :ranks ranks
                    :uncitedRankedFiles uncited-ranked-files
                    :pathUncitedRankedFiles path-uncited-ranked-files
                    :missedFiles missed
                    :rankedOutsideTop5 (ranked-outside 5)
                    :rankedOutsideTop10 (ranked-outside 10)
                    :rankedOutsideTop20 (ranked-outside 20)
                    :rankedOutsideTop5Blockers (ranked-outside-blockers
                                                ranks
                                                blockers-before
                                                5)
                    :rankedOutsideTop10Blockers (ranked-outside-blockers
                                                 ranks
                                                 blockers-before
                                                 10)
                    :rankedOutsideTop20Blockers (ranked-outside-blockers
                                                 ranks
                                                 blockers-before
                                                 20)}]
    (if (seq context-ranks)
      (assoc diagnostic
             :contextRanks context-ranks
             :missedFilesPresentInContext missed-present-in-context
             :missedFilesAbsentFromContext missed-absent-from-context)
      diagnostic)))

(defn- aggregate-rank-blockers
  [result-pairs blocker-key]
  (let [rows (mapcat
              (fn [[result diagnostic]]
                (map (fn [blocking-file]
                       (assoc blocking-file :case-id (:case-id result)))
                     (mapcat :blockingFiles (get diagnostic blocker-key))))
              result-pairs)]
    (->> rows
         (group-by :path)
         (map (fn [[path path-rows]]
                (cond-> {:path path
                         :occurrences (count path-rows)
                         :runs (count (set (map :case-id path-rows)))
                         :bestRank (apply min (map :rank path-rows))}
                  (:metrics (first path-rows))
                  (assoc :metrics (:metrics (first path-rows))))))
         (sort-by (juxt (comp - :occurrences)
                        :bestRank
                        :path))
         (take aggregate-rank-blocker-limit)
         vec)))

(defn- aggregate-ranked-file-diagnostics
  [result-pairs diagnostic-key]
  (let [rows (mapcat
              (fn [[result diagnostic]]
                (map (fn [ranked-file]
                       (assoc ranked-file :case-id (:case-id result)))
                     (get diagnostic diagnostic-key)))
              result-pairs)]
    (->> rows
         (group-by :path)
         (map (fn [[path path-rows]]
                (let [ranks (keep :rank path-rows)]
                  (cond-> {:path path
                           :occurrences (count path-rows)
                           :runs (count (set (map :case-id path-rows)))
                           :caseIds (->> path-rows
                                         (map :case-id)
                                         set
                                         sort
                                         vec)}
                    (seq ranks) (assoc :bestRank (apply min ranks))))))
         (sort-by (juxt (comp - :occurrences)
                        #(or (:bestRank %) Long/MAX_VALUE)
                        :path))
         (take aggregate-ranked-file-diagnostic-limit)
         vec)))

(defn- aggregate-localization-diagnostics
  [results]
  (let [diagnostics (map localization-diagnostic results)
        result-pairs (map vector results diagnostics)
        case-ids (fn [pairs]
                   (->> pairs
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))
        all-found (filter (fn [[_ diagnostic]]
                            (empty? (:missedFiles diagnostic)))
                          result-pairs)
        missed (filter (fn [[_ diagnostic]]
                         (seq (:missedFiles diagnostic)))
                       result-pairs)
        outside-top5 (filter (fn [[_ diagnostic]]
                               (seq (:rankedOutsideTop5 diagnostic)))
                             result-pairs)
        outside-top10 (filter (fn [[_ diagnostic]]
                                (seq (:rankedOutsideTop10 diagnostic)))
                              result-pairs)
        outside-top20 (filter (fn [[_ diagnostic]]
                                (seq (:rankedOutsideTop20 diagnostic)))
                              result-pairs)
        path-uncited (filter (fn [[_ diagnostic]]
                               (seq (:pathUncitedRankedFiles diagnostic)))
                             result-pairs)
        missed-present-in-context (filter (fn [[_ diagnostic]]
                                            (seq (:missedFilesPresentInContext diagnostic)))
                                          result-pairs)
        missed-absent-from-context (filter (fn [[_ diagnostic]]
                                             (seq (:missedFilesAbsentFromContext diagnostic)))
                                           result-pairs)]
    {:runs (count results)
     :allScoreableFoundRuns (count all-found)
     :allScoreableFoundCaseIds (case-ids all-found)
     :missedRuns (count missed)
     :missedCaseIds (case-ids missed)
     :missedButPresentInContextRuns (count missed-present-in-context)
     :missedButPresentInContextCaseIds (case-ids missed-present-in-context)
     :missedAndAbsentFromContextRuns (count missed-absent-from-context)
     :missedAndAbsentFromContextCaseIds (case-ids missed-absent-from-context)
     :rankedOutsideTop5Runs (count outside-top5)
     :rankedOutsideTop5CaseIds (case-ids outside-top5)
     :rankedOutsideTop10Runs (count outside-top10)
     :rankedOutsideTop10CaseIds (case-ids outside-top10)
     :rankedOutsideTop20Runs (count outside-top20)
     :rankedOutsideTop20CaseIds (case-ids outside-top20)
     :pathUncitedRuns (count path-uncited)
     :pathUncitedCaseIds (case-ids path-uncited)
     :pathUncitedRankedFiles (aggregate-ranked-file-diagnostics
                              result-pairs
                              :pathUncitedRankedFiles)
     :rankedOutsideTop5BlockingFiles (aggregate-rank-blockers
                                      result-pairs
                                      :rankedOutsideTop5Blockers)
     :rankedOutsideTop10BlockingFiles (aggregate-rank-blockers
                                       result-pairs
                                       :rankedOutsideTop10Blockers)
     :rankedOutsideTop20BlockingFiles (aggregate-rank-blockers
                                       result-pairs
                                       :rankedOutsideTop20Blockers)}))

(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (let [cases (selected-cases suite (case-selector opts))
        expected-fingerprints (into {}
                                    (map (fn [case]
                                           [(:id case) (case-fingerprint suite case)]))
                                    cases)
        progress (->> cases
                      (keep #(progress-summary suite % opts))
                      vec)
        progress-by-case (into {} (map (juxt :case-id identity)) progress)
        raw-results (vec
                     (mapcat (fn [case]
                               (let [case-progress (get progress-by-case (:id case))]
                                 (map #(cond-> %
                                         case-progress (assoc :progress case-progress))
                                      (agent-score-results suite case opts))))
                             cases))
        allow-unverified? (:allow-unverified-scores? opts)
        results (if allow-unverified?
                  raw-results
                  (filter #(current-score-artifact? expected-fingerprints %)
                          raw-results))
        completed-cases (set (map :case-id results))
        missing (->> cases
                     (remove #(contains? completed-cases (:id %)))
                     (mapv :id))
        report-base {:schema agent-report-schema
                     :suite-id (:id suite)
                     :cases (count cases)
                     :completed (count completed-cases)
                     :runs (count results)
                     :missing missing
                     :scores (aggregate-agent-scores results)
                     :parserWorkers (aggregate-parser-worker-profiles results)
                     :inputHints (input-hint-summary results)
                     :agentDiagnostics (aggregate-agent-diagnostics results)
                     :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics
                                                   results)
                     :localizationDiagnostics (aggregate-localization-diagnostics results)
                     :coverageDiagnostics (aggregate-coverage-diagnostics results)
                     :artifactDiagnostics (aggregate-artifact-diagnostics
                                           expected-fingerprints
                                           raw-results)
                     :artifactPolicy (artifact-policy expected-fingerprints
                                                      raw-results
                                                      results
                                                      allow-unverified?)
                     :coverage (aggregate-coverage results)
                     :tags (aggregate-case-tags cases)
                     :problemClasses (problem-class-summary expected-fingerprints
                                                            results)
                     :timings (aggregate-progress progress)
                     :caseProgress progress
                     :byMode (group-agent-scores expected-fingerprints
                                                 results
                                                 [:agent :mode])
                     :byAgent (group-agent-scores expected-fingerprints
                                                  results
                                                  [:agent :agentId])
                     :byParserWorker (group-agent-scores-by-parser-worker
                                      expected-fingerprints
                                      results)
                     :byTag (group-agent-scores-by-tag expected-fingerprints
                                                       results)
                     :results (mapv #(assoc (select-keys % [:case-id
                                                            :repo-id
                                                            :baseSha
                                                            :fixSha
                                                            :caseFingerprint
                                                            :tags
                                                            :expectations
                                                            :graphExpectations
                                                            :inputHints
                                                            :coverage
                                                            :agentResultPath
                                                            :parserWorker
                                                            :agent
                                                            :progress
                                                            :scores])
                                            :localization
                                            (localization-diagnostic %)
                                            :agentOutput
                                            (agent-output-diagnostic %)
                                            :artifact
                                            (artifact-diagnostic expected-fingerprints %))
                                    results)}
        report (assoc report-base
                      :claimReadiness (report-claim-readiness report-base))]
    (benchmark-io/write-json-file! (benchmark-paths/agent-report-path suite opts) report)
    report))

(defn- threshold
  [opts opt-key artifact-key]
  (when-some [value (get opts opt-key)]
    [artifact-key (double value)]))

(defn- agent-check-thresholds
  [opts]
  (cond-> (into {:requireComplete (not (:allow-missing? opts))
                 :allowDuplicateRuns (boolean (:allow-duplicate-runs? opts))}
                (keep (fn [[opt-key artifact-key]]
                        (threshold opts opt-key artifact-key)))
                [[:min-cases :minCases]
                 [:min-runs :minRuns]
                 [:min-file-recall-at-5 :minFileRecallAt5]
                 [:min-file-recall-at-10 :minFileRecallAt10]
                 [:min-file-recall-at-20 :minFileRecallAt20]
                 [:min-mrr :minMeanReciprocalRankFile]
                 [:max-noise-at-20 :maxNoiseRatioAt20]
                 [:min-evidence-citation-rate :minEvidenceCitationRate]
                 [:min-path-evidence-citation-rate :minPathEvidenceCitationRate]
                 [:min-case-file-recall-at-5 :minCaseFileRecallAt5]
                 [:min-case-file-recall-at-10 :minCaseFileRecallAt10]
                 [:min-case-file-recall-at-20 :minCaseFileRecallAt20]
                 [:min-case-mrr :minCaseMeanReciprocalRankFile]
                 [:min-case-evidence-citation-rate :minCaseEvidenceCitationRate]
                 [:min-case-path-evidence-citation-rate
                  :minCasePathEvidenceCitationRate]
                 [:max-case-noise-at-20 :maxCaseNoiseRatioAt20]
                 [:max-input-hinted-cases :maxInputHintedCases]
                 [:max-unsupported-ground-truth-files :maxUnsupportedGroundTruthFiles]
                 [:max-empty-result-runs :maxEmptyResultRuns]
                 [:max-missing-predicted-file-runs
                  :maxMissingPredictedFileRuns]
                 [:max-commandless-runs :maxCommandlessRuns]
                 [:max-warning-runs :maxWarningRuns]
                 [:max-hint-diagnostic-runs :maxHintDiagnosticRuns]
                 [:max-identity-mismatch-runs :maxIdentityMismatchRuns]
                 [:max-unverified-score-runs :maxUnverifiedScoreRuns]
                 [:max-graph-expectation-failures :maxGraphExpectationFailures]
                 [:max-missing-declared-source-kind-runs :maxMissingDeclaredSourceKindRuns]
                 [:max-missed-runs :maxMissedRuns]
                 [:max-missed-but-present-in-context-runs
                  :maxMissedButPresentInContextRuns]
                 [:max-missed-and-absent-from-context-runs
                  :maxMissedAndAbsentFromContextRuns]
                 [:max-ranked-outside-top-5-runs :maxRankedOutsideTop5Runs]
                 [:max-ranked-outside-top-10-runs :maxRankedOutsideTop10Runs]
                 [:max-ranked-outside-top-20-runs :maxRankedOutsideTop20Runs]
                 [:max-active-stage-ms :maxActiveStageMs]
                 [:max-parser-worker-profiles :maxParserWorkerProfiles]
                 [:min-measured-problem-classes :minMeasuredProblemClasses]
                 [:min-measured-architecture-classes
                  :minMeasuredArchitectureClasses]])
    (extract/normalize-parser-worker-mode (:require-parser-worker opts))
    (assoc :requiredParserWorker
           (extract/normalize-parser-worker-mode (:require-parser-worker opts)))))

(defn- metric-failure
  [metric operator expected actual]
  {:metric metric
   :operator operator
   :expected expected
   :actual actual})

(defn- min-failure
  [report threshold-key metric-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report :scores metric-key] 0.0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))

(defn- max-failure
  [report threshold-key metric-path metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report (into [:report] metric-path) 0.0))]
      (when (> actual expected)
        (metric-failure metric-label "<=" expected actual)))))

(defn- min-count-failure
  [report threshold-key report-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report report-key] 0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))

(defn- completeness-failures
  [report]
  (let [artifact-policy (get-in report [:report :artifactPolicy])
        zero-run-failure (cond-> {:metric "runs"
                                  :operator ">"
                                  :expected 0
                                  :actual 0}
                           (pos? (long (:excludedUnverifiedRuns
                                        artifact-policy
                                        0)))
                           (merge
                            {:matchedRuns (:matchedRuns artifact-policy)
                             :excludedRuns (:excludedRuns artifact-policy)
                             :excludedUnverifiedRuns (:excludedUnverifiedRuns
                                                      artifact-policy)
                             :excludedCaseIds (:excludedCaseIds
                                               artifact-policy)
                             :message "No current agent score artifacts matched the selected suite, case, and mode. Matching artifacts were excluded because their score schema, agent result schema, or case fingerprint is stale; regenerate the benchmark scores or pass --allow-unverified-scores for exploratory reporting."})

                           (not (pos? (long (:excludedUnverifiedRuns
                                             artifact-policy
                                             0))))
                           (assoc
                            :message
                            "No agent score artifacts matched the selected suite, case, and mode."))]
    (vec
     (concat
      (keep (fn [[threshold-key report-key metric-label]]
              (min-count-failure report threshold-key report-key metric-label))
            [[:minCases :cases "cases"]
             [:minRuns :runs "runs"]])
      (cond-> []
        (and (nil? (get-in report [:thresholds :minRuns]))
             (zero? (long (get-in report [:report :runs] 0))))
        (conj zero-run-failure)

        (and (get-in report [:thresholds :requireComplete])
             (seq (get-in report [:report :missing])))
        (conj {:metric "completed"
               :operator "="
               :expected (get-in report [:report :cases])
               :actual (get-in report [:report :completed])
               :missing (get-in report [:report :missing])
               :message "Some selected cases do not have matching agent score artifacts."}))))))

(defn- run-identity
  [result]
  {:case-id (:case-id result)
   :agentId (or (get-in result [:agent :agentId]) "unknown")
   :mode (or (get-in result [:agent :mode]) "unknown")})

(defn- duplicate-run-failures
  [check]
  (when-not (get-in check [:thresholds :allowDuplicateRuns])
    (->> (get-in check [:report :results])
         (group-by run-identity)
         (keep (fn [[run-key rows]]
                 (when (> (count rows) 1)
                   (merge (metric-failure "duplicateRuns" "=" 1 (count rows))
                          run-key
                          {:agentResultPaths (mapv :agentResultPath rows)
                           :message "Multiple agent score artifacts matched one case, agent, and mode."}))))
         vec)))

(defn- case-metric-failure
  [result metric operator expected actual]
  (assoc (metric-failure metric operator expected actual)
         :case-id (:case-id result)
         :agentId (get-in result [:agent :agentId])
         :mode (get-in result [:agent :mode])))

(defn- case-min-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (< actual expected)
        (case-metric-failure result metric-label ">=" expected actual)))))

(defn- case-max-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (> actual expected)
        (case-metric-failure result metric-label "<=" expected actual)))))

(defn- case-threshold-failures
  [check]
  (let [results (get-in check [:report :results])]
    (vec
     (mapcat
      (fn [result]
        (concat
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-min-failure check result threshold-key metric-key metric-label))
               [[:minCaseFileRecallAt5 :fileRecallAt5 "case.fileRecallAt5"]
                [:minCaseFileRecallAt10 :fileRecallAt10 "case.fileRecallAt10"]
                [:minCaseFileRecallAt20 :fileRecallAt20 "case.fileRecallAt20"]
                [:minCaseMeanReciprocalRankFile
                 :meanReciprocalRankFile
                 "case.meanReciprocalRankFile"]
                [:minCaseEvidenceCitationRate
                 :evidenceCitationRate
                 "case.evidenceCitationRate"]
                [:minCasePathEvidenceCitationRate
                 :pathEvidenceCitationRate
                 "case.pathEvidenceCitationRate"]])
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-max-failure check result threshold-key metric-key metric-label))
               [[:maxCaseNoiseRatioAt20 :noiseRatioAt20 "case.noiseRatioAt20"]])))
      results))))

(defn- active-stage-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxActiveStageMs])]
    (->> (get-in check [:report :caseProgress])
         (keep (fn [progress]
                 (let [actual (double (:activeElapsedMs progress 0))]
                   (when (> actual expected)
                     (merge (metric-failure "activeStageElapsedMs"
                                            "<="
                                            expected
                                            actual)
                            (select-keys progress
                                         [:case-id
                                          :repo-id
                                          :status
                                          :activeStage])
                            {:message "Active benchmark stage exceeded the configured duration."})))))
         vec)))

(defn- empty-result-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxEmptyResultRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :emptyResultRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "emptyResultRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :emptyResultCaseIds])
                 :message "Some agent score artifacts produced no rankable suspected files."})]))))

(defn- warning-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxWarningRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :warningRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "warningRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :warningCaseIds])
                 :message "Some agent score artifacts contain scorer or agent warnings."})]))))

(defn- hint-diagnostic-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxHintDiagnosticRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :hintDiagnosticRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "hintDiagnosticRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :hintDiagnosticCaseIds])
                 :hintDiagnosticsByKind (get-in check
                                                [:report
                                                 :agentDiagnostics
                                                 :hintDiagnosticsByKind])
                 :message "Some AGraph hint artifacts reported help-quality diagnostics."})]))))

(defn- identity-mismatch-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxIdentityMismatchRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :identityMismatchRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "identityMismatchRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :identityMismatchCaseIds])
                 :identityMismatches (get-in check
                                             [:report
                                              :agentDiagnostics
                                              :identityMismatches])
                 :message "Some agent score artifacts reported a schema, case id, or case fingerprint that does not match the prepared case."})]))))

(defn- missing-predicted-file-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxMissingPredictedFileRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :agentDiagnostics
                                  :missingPredictedFileRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "missingPredictedFileRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :missingPredictedFileCaseIds])
                 :missingPredictedFiles (get-in check
                                                [:report
                                                 :agentDiagnostics
                                                 :missingPredictedFiles])
                 :message "Some agent score artifacts predicted paths that do not exist in the base checkout."})]))))

(defn- commandless-run-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxCommandlessRuns])]
    (let [actual (double (get-in check
                                 [:report :agentDiagnostics :commandlessRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "commandlessRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :agentDiagnostics
                                    :commandlessCaseIds])
                 :message "Some agent score artifacts did not cite any commands."})]))))

(defn- unverified-score-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxUnverifiedScoreRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :artifactDiagnostics
                                  :unverifiedScoreRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "unverifiedScoreRuns" "<=" expected actual)
                {:case-ids (get-in check
                                   [:report
                                    :artifactDiagnostics
                                    :unverifiedScoreCaseIds])
                 :obsoleteScoreSchemaCaseIds (get-in check
                                                     [:report
                                                      :artifactDiagnostics
                                                      :obsoleteScoreSchemaCaseIds])
                 :obsoleteScoreSchemas (get-in check
                                               [:report
                                                :artifactDiagnostics
                                                :obsoleteScoreSchemas])
                 :obsoleteAgentResultSchemaCaseIds (get-in check
                                                           [:report
                                                            :artifactDiagnostics
                                                            :obsoleteAgentResultSchemaCaseIds])
                 :obsoleteAgentResultSchemas (get-in check
                                                     [:report
                                                      :artifactDiagnostics
                                                      :obsoleteAgentResultSchemas])
                 :expectedScoreSchema (get-in check
                                              [:report
                                               :artifactDiagnostics
                                               :expectedScoreSchema])
                 :expectedAgentResultSchema (get-in check
                                                    [:report
                                                     :artifactDiagnostics
                                                     :expectedAgentResultSchema])
                 :message "Some agent score artifacts are legacy, use an obsolete score schema, or do not match the current suite case fingerprint."})]))))

(defn- localization-diagnostic-failures
  [check]
  (->> [[:maxMissedRuns
         :missedRuns
         :missedCaseIds
         "missedRuns"
         "Some runs missed at least one scoreable localization file."]
        [:maxMissedButPresentInContextRuns
         :missedButPresentInContextRuns
         :missedButPresentInContextCaseIds
         "missedButPresentInContextRuns"
         "Some runs missed scoreable localization files that were present in the AGraph context packet."]
        [:maxMissedAndAbsentFromContextRuns
         :missedAndAbsentFromContextRuns
         :missedAndAbsentFromContextCaseIds
         "missedAndAbsentFromContextRuns"
         "Some runs missed scoreable localization files that were absent from the AGraph context packet."]
        [:maxRankedOutsideTop5Runs
         :rankedOutsideTop5Runs
         :rankedOutsideTop5CaseIds
         "rankedOutsideTop5Runs"
         "Some runs found scoreable localization files only outside the top 5."]
        [:maxRankedOutsideTop10Runs
         :rankedOutsideTop10Runs
         :rankedOutsideTop10CaseIds
         "rankedOutsideTop10Runs"
         "Some runs found scoreable localization files only outside the top 10."]
        [:maxRankedOutsideTop20Runs
         :rankedOutsideTop20Runs
         :rankedOutsideTop20CaseIds
         "rankedOutsideTop20Runs"
         "Some runs found scoreable localization files only outside the top 20."]]
       (keep (fn [[threshold-key actual-key case-ids-key metric message]]
               (when-some [expected (get-in check [:thresholds threshold-key])]
                 (let [actual (double (get-in check
                                              [:report
                                               :localizationDiagnostics
                                               actual-key]
                                              0))]
                   (when (> actual expected)
                     (merge (metric-failure metric "<=" expected actual)
                            {:case-ids (get-in check
                                               [:report
                                                :localizationDiagnostics
                                                case-ids-key])
                             :message message}))))))
       vec))

(defn- expand-case-id-failures
  [failures]
  (mapcat (fn [failure]
            (if (seq (:case-ids failure))
              (map #(-> failure
                        (assoc :case-id %)
                        (dissoc :case-ids))
                   (:case-ids failure))
              [failure]))
          failures))

(defn- coverage-diagnostic-failures
  [check]
  (when-some [expected (get-in check
                               [:thresholds
                                :maxMissingDeclaredSourceKindRuns])]
    (let [actual (double (get-in check
                                 [:report
                                  :coverageDiagnostics
                                  :missingDeclaredSourceKindRuns]
                                 0))]
      (when (> actual expected)
        [(merge (metric-failure "missingDeclaredSourceKindRuns"
                                "<="
                                expected
                                actual)
                {:case-ids (get-in check
                                   [:report
                                    :coverageDiagnostics
                                    :missingDeclaredSourceKindCaseIds])
                 :missingDeclaredSourceKinds (get-in check
                                                     [:report
                                                      :coverageDiagnostics
                                                      :missingDeclaredSourceKinds])
                 :message "Some runs declared source kinds that had no scoreable coverage."})]))))

(defn- graph-expectation-failures
  [check]
  (when-some [expected (get-in check [:thresholds :maxGraphExpectationFailures])]
    (let [failed-results (filter #(= "failed"
                                     (get-in % [:graphExpectations :status]))
                                 (get-in check [:report :results]))
          actual (double (count failed-results))]
      (when (> actual expected)
        (into
         [(merge (metric-failure "graphExpectationFailures" "<=" expected actual)
                 {:case-ids (->> failed-results
                                 (map :case-id)
                                 distinct
                                 sort
                                 vec)
                  :message "Some graph/evidence/chunk benchmark expectations failed."})]
         (map (fn [result]
                (merge (case-metric-failure result
                                            "case.graphExpectations"
                                            "="
                                            "passed"
                                            "failed")
                       {:summary (get-in result [:graphExpectations :summary])
                        :message "Graph/evidence/chunk benchmark expectations failed for this run."}))
              failed-results))))))

(defn- measured-problem-class-count
  [check class-key]
  (count (filter #(= "measured" (:claimStatus %))
                 (get-in check [:report :problemClasses class-key]))))

(defn- problem-class-claim-failure
  [check threshold-key class-key metric message]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (measured-problem-class-count check class-key))]
      (when (< actual expected)
        (merge (metric-failure metric ">=" expected actual)
               {:classes (get-in check [:report :problemClasses class-key])
                :minimumCasesForClassClaim (get-in check
                                                   [:report
                                                    :problemClasses
                                                    :minimumCasesForClassClaim])
                :message message})))))

(defn- problem-class-claim-failures
  [check]
  (keep identity
        [(problem-class-claim-failure
          check
          :minMeasuredProblemClasses
          :classes
          "measuredProblemClasses"
          "Fewer problem-class groups are measured than required for the benchmark claim.")
         (problem-class-claim-failure
          check
          :minMeasuredArchitectureClasses
          :architectureClasses
          "measuredArchitectureClasses"
          "Fewer architecture-class groups are measured than required for the benchmark claim.")]))

(defn- parser-worker-profile-failures
  [check]
  (let [profiles (vec (get-in check [:report :parserWorkers]))
        failures (cond-> []
                   (when-some [expected (get-in check
                                                [:thresholds
                                                 :maxParserWorkerProfiles])]
                     (> (count profiles) expected))
                   (conj (merge (metric-failure "parserWorkerProfiles"
                                                "<="
                                                (get-in check
                                                        [:thresholds
                                                         :maxParserWorkerProfiles])
                                                (count profiles))
                                {:profiles profiles
                                 :message "Agent report contains more parser-worker profiles than allowed."}))

                   (when-let [expected (get-in check
                                               [:thresholds
                                                :requiredParserWorker])]
                     (seq (remove #(= expected (:mode %)) profiles)))
                   (conj (merge (metric-failure "parserWorker"
                                                "="
                                                (get-in check
                                                        [:thresholds
                                                         :requiredParserWorker])
                                                (->> profiles
                                                     (map :mode)
                                                     distinct
                                                     sort
                                                     vec))
                                {:profiles profiles
                                 :message "Agent report contains parser-worker modes that do not match the required mode."})))]
    failures))

(def ^:private case-diagnostic-score-keys
  [:fileRecallAt5
   :fileRecallAt10
   :fileRecallAt20
   :meanReciprocalRankFile
   :noiseRatioAt20
   :evidenceCitationRate
   :pathEvidenceCitationRate
   :changedFiles
   :scoreableChangedFiles
   :unsupportedGroundTruthFiles])

(defn- failure-matches-result?
  [failure result]
  (and (= (:case-id failure) (:case-id result))
       (or (nil? (:agentId failure))
           (= (:agentId failure) (get-in result [:agent :agentId])))
       (or (nil? (:mode failure))
           (= (:mode failure) (get-in result [:agent :mode])))))

(defn- result-case-diagnostic
  [failures result]
  (let [result-failures (filterv #(failure-matches-result? % result) failures)]
    (cond-> {:case-id (:case-id result)
             :agentId (get-in result [:agent :agentId])
             :mode (get-in result [:agent :mode])
             :parserWorker (:parserWorker result)
             :agentResultPath (:agentResultPath result)
             :status (if (seq result-failures) "failed" "passed")
             :scores (select-keys (:scores result) case-diagnostic-score-keys)
             :localization (:localization result)
             :failures result-failures}
      (:agentOutput result)
      (assoc :agentOutput (:agentOutput result))

      (:artifact result)
      (assoc :artifact (:artifact result))

      (:progress result)
      (assoc :progress (:progress result)))))

(defn- missing-case-diagnostic
  [failures case-id progress]
  (cond-> {:case-id case-id
           :status "missing"
           :failures (into [{:metric "completed"
                             :operator "="
                             :expected 1
                             :actual 0
                             :message "Selected case does not have a matching agent score artifact."}]
                           (filter #(= case-id (:case-id %)))
                           failures)}
    progress
    (assoc :progress progress)))

(defn- case-diagnostics
  [check failures]
  (let [expanded-failures (vec (expand-case-id-failures failures))
        progress-by-case (into {}
                               (map (juxt :case-id identity))
                               (get-in check [:report :caseProgress]))]
    (vec
     (concat
      (map #(result-case-diagnostic expanded-failures %)
           (get-in check [:report :results]))
      (map #(missing-case-diagnostic expanded-failures
                                     %
                                     (get progress-by-case %))
           (get-in check [:report :missing]))))))

(defn check-agent-report
  "Return a pass/fail check over an agent benchmark report."
  [report opts]
  (let [check-base {:schema agent-check-schema
                    :suite-id (:suite-id report)
                    :thresholds (agent-check-thresholds opts)
                    :report report}
        failures (vec
                  (concat
                   (completeness-failures check-base)
                   (duplicate-run-failures check-base)
                   (keep (fn [[threshold-key metric-key metric-label]]
                           (min-failure check-base
                                        threshold-key
                                        metric-key
                                        metric-label))
                         [[:minFileRecallAt5 :fileRecallAt5 "fileRecallAt5"]
                          [:minFileRecallAt10 :fileRecallAt10 "fileRecallAt10"]
                          [:minFileRecallAt20 :fileRecallAt20 "fileRecallAt20"]
                          [:minMeanReciprocalRankFile
                           :meanReciprocalRankFile
                           "meanReciprocalRankFile"]
                          [:minEvidenceCitationRate
                           :evidenceCitationRate
                           "evidenceCitationRate"]
                          [:minPathEvidenceCitationRate
                           :pathEvidenceCitationRate
                           "pathEvidenceCitationRate"]])
                   (keep (fn [[threshold-key metric-path metric-label]]
                           (max-failure check-base
                                        threshold-key
                                        metric-path
                                        metric-label))
                         [[:maxNoiseRatioAt20 [:scores :noiseRatioAt20] "noiseRatioAt20"]
                          [:maxInputHintedCases
                           [:inputHints :inputHintedCases]
                           "inputHintedCases"]
                          [:maxUnsupportedGroundTruthFiles
                           [:scores :unsupportedGroundTruthFiles]
                           "unsupportedGroundTruthFiles"]])
                   (case-threshold-failures check-base)
                   (empty-result-failures check-base)
                   (missing-predicted-file-failures check-base)
                   (commandless-run-failures check-base)
                   (warning-run-failures check-base)
                   (hint-diagnostic-run-failures check-base)
                   (identity-mismatch-run-failures check-base)
                   (unverified-score-failures check-base)
                   (graph-expectation-failures check-base)
                   (coverage-diagnostic-failures check-base)
                   (problem-class-claim-failures check-base)
                   (parser-worker-profile-failures check-base)
                   (localization-diagnostic-failures check-base)
                   (active-stage-failures check-base)))]
    (assoc check-base
           :status (if (seq failures) "failed" "passed")
           :caseDiagnostics (case-diagnostics check-base failures)
           :failures failures)))

(defn check-agent-suite
  "Aggregate existing agent score artifacts and check them against thresholds."
  [suite opts]
  (let [check (check-agent-report (report-agent-suite suite opts) opts)]
    (benchmark-io/write-json-file! (benchmark-paths/agent-check-path suite opts) check)
    check))

(def ^:private comparison-score-specs
  [{:key :fileRecallAt5
    :label "fileRecallAt5"
    :direction :higher}
   {:key :fileRecallAt10
    :label "fileRecallAt10"
    :direction :higher}
   {:key :fileRecallAt20
    :label "fileRecallAt20"
    :direction :higher}
   {:key :meanReciprocalRankFile
    :label "meanReciprocalRankFile"
    :direction :higher}
   {:key :noiseRatioAt20
    :label "noiseRatioAt20"
    :direction :lower}
   {:key :evidenceCitationRate
    :label "evidenceCitationRate"
    :direction :higher}
   {:key :pathEvidenceCitationRate
    :label "pathEvidenceCitationRate"
    :direction :higher}])

(def ^:private comparison-report-specs
  [{:path [:agentDiagnostics :warningRuns]
    :label "warningRuns"
    :direction :lower}
   {:path [:agentDiagnostics :missingPredictedFileRuns]
    :label "missingPredictedFileRuns"
    :direction :lower}
   {:path [:agentDiagnostics :commandlessRuns]
    :label "commandlessRuns"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :searchCommandCount]
    :label "searchCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :fileReadCommandCount]
    :label "fileReadCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :commandTelemetry :shellCommandCount]
    :label "shellCommandCount"
    :direction :lower}
   {:path [:agentDiagnostics :hintDiagnosticRuns]
    :label "hintDiagnosticRuns"
    :direction :lower}
   {:path [:agentDiagnostics :identityMismatchRuns]
    :label "identityMismatchRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :unverifiedScoreRuns]
    :label "unverifiedScoreRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :obsoleteScoreSchemaRuns]
    :label "obsoleteScoreSchemaRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :obsoleteAgentResultSchemaRuns]
    :label "obsoleteAgentResultSchemaRuns"
    :direction :lower}
   {:path [:artifactDiagnostics :staleScoreRuns]
    :label "staleScoreRuns"
    :direction :lower}
   {:path [:coverageDiagnostics :missingDeclaredSourceKindRuns]
    :label "missingDeclaredSourceKindRuns"
    :direction :lower}
   {:path [:coverageDiagnostics :coverageExcludedGroundTruthFiles]
    :label "coverageExcludedGroundTruthFiles"
    :direction :lower}
   {:path [:coverageDiagnostics :unsupportedGroundTruthFiles]
    :label "unsupportedGroundTruthFiles"
    :direction :lower}])

(defn- comparison-delta
  [baseline candidate {:keys [path key label direction]} tolerance]
  (let [value-path (or path [key])
        before (double (get-in baseline value-path 0.0))
        after (double (get-in candidate value-path 0.0))
        delta (- after before)
        regression? (case direction
                      :higher (< delta (- tolerance))
                      :lower (> delta tolerance))]
    (cond-> {:metric label
             :direction (name direction)
             :baseline before
             :candidate after
             :delta delta}
      regression? (assoc :regression? true))))

(defn- score-deltas
  [baseline candidate tolerance]
  (mapv #(comparison-delta baseline candidate % tolerance)
        comparison-score-specs))

(defn- report-deltas
  [baseline-report candidate-report tolerance]
  (mapv #(comparison-delta baseline-report candidate-report % tolerance)
        comparison-report-specs))

(defn- report-result-by-case
  [report]
  (->> (:results report)
       (map (juxt :case-id identity))
       (into {})))

(defn- same-case-set?
  [baseline-by-case candidate-by-case]
  (= (set (keys baseline-by-case))
     (set (keys candidate-by-case))))

(defn- report-parser-worker-profiles
  [report]
  (let [profiles (seq (:parserWorkers report))]
    (cond
      profiles
      (->> profiles
           (map #(select-keys % [:mode :source]))
           (sort-by (juxt :mode :source))
           vec)

      (seq (:results report))
      (->> (:results report)
           aggregate-parser-worker-profiles
           (map #(select-keys % [:mode :source]))
           (sort-by (juxt :mode :source))
           vec)

      (pos? (long (:runs report 0)))
      [{:mode "unknown"
        :source "missing"}]

      :else [])))

(defn- same-parser-worker-profiles?
  [baseline-report candidate-report]
  (= (report-parser-worker-profiles baseline-report)
     (report-parser-worker-profiles candidate-report)))

(defn- aggregate-comparable-reasons
  [baseline-report candidate-report baseline-by-case candidate-by-case]
  (cond-> []
    (not (same-case-set? baseline-by-case candidate-by-case))
    (conj "case-set-changed")

    (not (same-parser-worker-profiles? baseline-report candidate-report))
    (conj "parser-worker-profile-changed")))

(defn- mark-aggregate-deltas-not-comparable
  [deltas reasons]
  (mapv #(cond-> (dissoc % :regression?)
           (:regression? %) (assoc :ignored? true
                                   :reason (first reasons)
                                   :reasons reasons))
        deltas))

(defn- case-comparison
  [baseline-by-case candidate-by-case case-id tolerance]
  (let [baseline (get baseline-by-case case-id)
        candidate (get candidate-by-case case-id)]
    (cond
      (nil? candidate)
      {:case-id case-id
       :status "missing"
       :regressions [{:metric "case.present"
                      :baseline true
                      :candidate false
                      :regression? true}]}

      (nil? baseline)
      {:case-id case-id
       :status "added"
       :regressions []}

      :else
      (let [deltas (mapv #(update % :metric (fn [metric]
                                              (str "case." metric)))
                         (score-deltas (:scores baseline)
                                       (:scores candidate)
                                       tolerance))
            regressions (filterv :regression? deltas)]
        {:case-id case-id
         :status (if (seq regressions) "regressed" "ok")
         :deltas deltas
         :regressions regressions}))))

(defn compare-agent-reports
  "Compare two agent reports and mark metric regressions.

  Higher is better for recall and MRR; lower is better for noise. The optional
  `:regression-tolerance` allows tiny floating-point or sampling drift without
  hiding meaningful regressions."
  [baseline-report candidate-report opts]
  (let [tolerance (double (or (:regression-tolerance opts) 0.0))
        baseline-by-case (report-result-by-case baseline-report)
        candidate-by-case (report-result-by-case candidate-report)
        case-ids (->> (concat (keys baseline-by-case)
                              (keys candidate-by-case))
                      distinct
                      sort
                      vec)
        aggregate-comparable-reasons (aggregate-comparable-reasons
                                      baseline-report
                                      candidate-report
                                      baseline-by-case
                                      candidate-by-case)
        aggregate-comparable? (empty? aggregate-comparable-reasons)
        raw-aggregate-deltas (vec (concat
                                   (score-deltas (:scores baseline-report)
                                                 (:scores candidate-report)
                                                 tolerance)
                                   (report-deltas baseline-report
                                                  candidate-report
                                                  tolerance)))
        aggregate-deltas (if aggregate-comparable?
                           raw-aggregate-deltas
                           (mark-aggregate-deltas-not-comparable
                            raw-aggregate-deltas
                            aggregate-comparable-reasons))
        aggregate-regressions (if aggregate-comparable?
                                (filterv :regression? aggregate-deltas)
                                [])
        cases (mapv #(case-comparison baseline-by-case
                                      candidate-by-case
                                      %
                                      tolerance)
                    case-ids)
        case-regressions (vec (mapcat :regressions cases))
        regressions (vec (concat aggregate-regressions case-regressions))]
    {:schema agent-compare-schema
     :suite-id (or (:suite-id candidate-report)
                   (:suite-id baseline-report))
     :status (if (seq regressions) "failed" "passed")
     :tolerance tolerance
     :aggregateComparable aggregate-comparable?
     :aggregateComparableReasons aggregate-comparable-reasons
     :baseline {:cases (:cases baseline-report)
                :completed (:completed baseline-report)
                :runs (:runs baseline-report)
                :parserWorkers (report-parser-worker-profiles baseline-report)
                :agentDiagnostics (:agentDiagnostics baseline-report)
                :coverageDiagnostics (:coverageDiagnostics baseline-report)
                :scores (:scores baseline-report)}
     :candidate {:cases (:cases candidate-report)
                 :completed (:completed candidate-report)
                 :runs (:runs candidate-report)
                 :parserWorkers (report-parser-worker-profiles candidate-report)
                 :agentDiagnostics (:agentDiagnostics candidate-report)
                 :coverageDiagnostics (:coverageDiagnostics candidate-report)
                 :scores (:scores candidate-report)}
     :aggregateDeltas aggregate-deltas
     :caseDeltas cases
     :regressions regressions}))

(defn compare-agent-report-files!
  "Read, compare, and write an agent report comparison artifact."
  [suite opts]
  (let [baseline-path (or (:baseline-report opts)
                          (throw (ex-info "Missing --baseline-report."
                                          {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        candidate-path (or (:candidate-report opts)
                           (throw (ex-info "Missing --candidate-report."
                                           {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        comparison (compare-agent-reports (benchmark-io/read-json-file baseline-path)
                                          (benchmark-io/read-json-file candidate-path)
                                          opts)]
    (benchmark-io/write-json-file! (benchmark-paths/agent-compare-path suite opts) comparison)
    comparison))

(defn report-suite
  "Aggregate existing benchmark result artifacts."
  [suite opts]
  (let [cases (selected-cases suite (case-selector opts))
        results (keep #(case-result suite % opts) cases)
        missing (->> cases
                     (remove #(case-result suite % opts))
                     (mapv :id))
        report {:schema report-schema
                :suite-id (:id suite)
                :cases (count cases)
                :completed (count results)
                :missing missing
                :scores (aggregate-scores results)
                :results (mapv #(select-keys % [:case-id
                                                :repo-id
                                                :baseSha
                                                :fixSha
                                                :scores])
                               results)}]
    (benchmark-io/write-json-file! (benchmark-paths/report-path suite opts) report)
    report))
