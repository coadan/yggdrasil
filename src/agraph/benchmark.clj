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
            [agraph.benchmark-agent-run :as benchmark-agent-run]
            [agraph.benchmark-agent-packet :as benchmark-agent-packet]
            [agraph.benchmark-local-vector :as benchmark-local-vector]
            [agraph.benchmark-score-artifacts :as benchmark-score-artifacts]
            [agraph.benchmark-agent-baseline :as benchmark-agent-baseline]
            [agraph.benchmark-report :as benchmark-report]
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

(declare agent-baseline-suspect-limit agent-prompt-profile agent-result-contract)

(defn- parser-worker-option
  [opts]
  (benchmark-agent-packet/parser-worker-option opts))

(defn- parser-worker-profile
  [opts]
  (benchmark-agent-packet/parser-worker-profile opts))

(defn- normalize-parser-worker-profile
  [profile]
  (benchmark-agent-packet/normalize-parser-worker-profile profile))

(defn- agent-score-parser-worker-profile
  [opts agent-result]
  (benchmark-agent-packet/agent-score-parser-worker-profile opts agent-result))

(defn- with-benchmark-parser-worker
  [opts f]
  (benchmark-agent-packet/with-benchmark-parser-worker opts f))

(defn- shell-quote
  [value]
  (benchmark-agent-packet/shell-quote value))

(defn- agent-mode
  [opts]
  (benchmark-agent-packet/agent-mode opts))

(defn- agent-project
  [prepared]
  (benchmark-agent-packet/agent-project prepared))

(defn- agent-packet-from-prepared!
  [suite case prepared opts]
  (benchmark-agent-packet/agent-packet-from-prepared! suite case prepared opts))

(defn agent-packet!
  "Prepare one case and write a provider-neutral agent localization packet."
  [suite case opts]
  (benchmark-agent-packet/agent-packet! suite case opts))

(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  (benchmark-agent-packet/agent-packets! suite opts))

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

(defn- benchmark-index-options
  [opts]
  (benchmark-agent-baseline/benchmark-index-options opts))

(defn- context-ground-truth-ranks
  [prepared packet]
  (benchmark-agent-baseline/context-ground-truth-ranks prepared packet))

(defn- context-ground-truth-ranks-from-path
  [prepared path]
  (benchmark-agent-baseline/context-ground-truth-ranks-from-path prepared path))

(defn- agent-baseline-context-options
  [prepared opts]
  (benchmark-agent-baseline/agent-baseline-context-options prepared opts))

(defn- agent-baseline-suspect-limit
  [opts]
  (benchmark-agent-baseline/agent-baseline-suspect-limit opts))

(defn agent-baseline!
  "Generate, write, and score one deterministic AGraph agent-result artifact."
  [suite case opts]
  (benchmark-agent-baseline/agent-baseline! suite case opts))

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

(defn- agent-baseline-mode
  [opts]
  (if (= :local-vector (keyword (or (:retriever opts) :lexical)))
    "local-vector"
    "agraph"))

(defn- current-agent-score-artifacts
  [suite case opts match]
  (benchmark-score-artifacts/current-agent-score-artifacts suite case opts match))

(defn- reusable-agent-score
  [suite case opts match]
  (benchmark-score-artifacts/reusable-agent-score suite case opts match))

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

(defn- normalize-agent-result-identity
  [agent-result prepared]
  (benchmark-local-vector/normalize-agent-result-identity agent-result prepared))

(defn local-vector-baseline!
  "Generate, write, and score one local semantic-vector benchmark baseline.

  This is an optional benchmark control. It shells out to a local worker and
  keeps vector dependencies outside AGraph core."
  [suite case opts]
  (benchmark-local-vector/local-vector-baseline! suite case opts))

(defn- agent-run-command
  [opts]
  (benchmark-agent-run/agent-run-command opts))

(defn- agent-run-timeout-ms
  [opts]
  (benchmark-agent-run/agent-run-timeout-ms opts))

(defn- agent-prompt-profile
  [opts]
  (benchmark-agent-run/agent-prompt-profile opts))

(defn- ensure-agent-run-id!
  [opts]
  (benchmark-agent-run/ensure-agent-run-id! opts))

(defn- run-process!
  [command cwd env timeout-ms]
  (benchmark-agent-run/run-process! command cwd env timeout-ms))

(defn- write-agent-run-logs!
  [suite case opts process-result]
  (benchmark-agent-run/write-agent-run-logs! suite case opts process-result))

(defn- agent-result-contract
  []
  (benchmark-agent-run/agent-result-contract))

(defn- write-agent-output-schema!
  [suite case opts]
  (benchmark-agent-run/write-agent-output-schema! suite case opts))

(defn- agent-run-prompt
  [packet result-path output-schema-path opts]
  (benchmark-agent-run/agent-run-prompt packet result-path output-schema-path opts))

(defn- write-agent-run-prompt!
  [suite case packet result-path output-schema-path opts]
  (benchmark-agent-run/write-agent-run-prompt! suite case packet result-path output-schema-path opts))

(defn- agent-run-env
  [packet result-path prompt-path output-schema-path opts]
  (benchmark-agent-run/agent-run-env packet result-path prompt-path output-schema-path opts))

(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one AGraph context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (benchmark-hints/context-packet->agent-hints prepared packet opts))

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

(defn- agent-score-results
  [suite case opts]
  (benchmark-score-artifacts/agent-score-results suite case opts))

(defn- agent-output-diagnostic
  [result]
  (benchmark-report/agent-output-diagnostic result))

(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (benchmark-report/report-agent-suite suite opts))

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
           benchmark-report/aggregate-parser-worker-profiles
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
                :scores (benchmark-report/aggregate-scores results)
                :results (mapv #(select-keys % [:case-id
                                                :repo-id
                                                :baseSha
                                                :fixSha
                                                :scores])
                               results)}]
    (benchmark-io/write-json-file! (benchmark-paths/report-path suite opts) report)
    report))
