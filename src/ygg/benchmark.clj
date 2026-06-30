(ns ygg.benchmark
  "Issue replay benchmarks for Yggdrasil retrieval quality."
  (:require [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-preflight :as benchmark-preflight]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.context :as context]
            [ygg.fs :as fs]
            [ygg.project :as project]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [ygg.benchmark-expectations :as benchmark-expectations]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.benchmark-hints :as benchmark-hints]
            [ygg.benchmark-hints-progressive :as benchmark-hints-progressive]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-results :as benchmark-results]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-agent-run :as benchmark-agent-run]
            [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-local-vector :as benchmark-local-vector]
            [ygg.benchmark-codebase-memory :as benchmark-codebase-memory]
            [ygg.benchmark-graphify :as benchmark-graphify]
            [ygg.benchmark-maintenance :as benchmark-maintenance]
            [ygg.benchmark-score-artifacts :as benchmark-score-artifacts]
            [ygg.benchmark-context-artifacts :as benchmark-context-artifacts]
            [ygg.benchmark-agent-baseline :as benchmark-agent-baseline]
            [ygg.benchmark-report :as benchmark-report]
            [ygg.benchmark-check :as benchmark-check]
            [ygg.benchmark-compare :as benchmark-compare]
            [ygg.benchmark-claim-pack :as benchmark-claim-pack]
            [ygg.benchmark-system-improvement :as benchmark-system-improvement]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.dependency.imports.common :as import-common]
            [ygg.embedding :as embedding]
            [clojure.string :as str]))

(def suite-schema
  "ygg.benchmark.suite/v1")

(def prepared-case-schema
  "ygg.benchmark.prepared-case/v1")

(def result-schema
  "ygg.benchmark.result/v1")

(def agent-packet-schema
  "ygg.benchmark.agent-packet/v1")

(def agent-hints-schema
  "ygg.benchmark.agent-hints/v1")

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def agent-score-schema
  "ygg.benchmark.agent-score/v3")

(def agent-result-contract-version
  benchmark-agent-score/agent-result-contract-version)

(def agent-report-schema
  "ygg.benchmark.agent-report/v1")

(def agent-check-schema
  "ygg.benchmark.agent-check/v1")

(def graph-expectations-schema
  "ygg.benchmark.graph-expectations/v1")

(def agent-compare-schema
  "ygg.benchmark.agent-compare/v1")

(def system-improvement-report-schema
  "ygg.benchmark.system-improvement-report/v1")

(def claim-pack-schema
  "ygg.benchmark.claim-pack/v1")

(def agent-baseline-schema
  "ygg.benchmark.agent-baseline/v1")

(def agent-baselines-schema
  "ygg.benchmark.agent-baselines/v1")

(def agent-run-schema
  "ygg.benchmark.agent-run/v1")

(def agent-preparation-schema
  "ygg.benchmark.agent-preparation/v1")

(def agent-runs-schema
  "ygg.benchmark.agent-runs/v1")

(def report-schema
  "ygg.benchmark.report/v1")

(def ^:private agent-rerun-improvement-kinds
  #{"obsolete-agent-result-contract"
    "stale-agent-input-fingerprints"
    "unverified-score-artifacts"})

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

(def default-local-vector-model
  "sentence-transformers/all-MiniLM-L6-v2")

(def default-local-vector-command
  "python3 scripts/local-vector-baseline.py")

(def default-agent-run-timeout-ms
  600000)

(def default-index-timeout-ms
  600000)

(def supported-agent-prompt-profiles
  ["standard" "fast"])

(declare agent-prompt-profile
         case-selector
         prepare-case!
         prepare-or-reuse-agent-graph-and-artifacts!
         selected-cases)

(defn- parser-worker-profile
  [opts]
  (benchmark-agent-packet/parser-worker-profile opts))

(defn- agent-score-parser-worker-profile
  [opts agent-result]
  (benchmark-agent-packet/agent-score-parser-worker-profile opts agent-result))

(defn- with-benchmark-parser-worker
  [opts f]
  (benchmark-agent-packet/with-benchmark-parser-worker opts f))

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
  (let [prepared (prepare-case! suite case opts)
        ygg-prep (prepare-or-reuse-agent-graph-and-artifacts! suite
                                                              case
                                                              prepared
                                                              opts)
        ygg-artifacts (:artifacts ygg-prep)]
    (agent-packet-from-prepared! suite
                                 case
                                 prepared
                                 (cond-> opts
                                   (:preparation ygg-prep)
                                   (assoc :agent-preparation
                                          (:preparation ygg-prep))
                                   (:context-path ygg-artifacts)
                                   (assoc :ygg-context-path
                                          (:context-path ygg-artifacts))
                                   (:hints-path ygg-artifacts)
                                   (assoc :ygg-hints-path
                                          (:hints-path ygg-artifacts))
                                   (:full-hints-path ygg-artifacts)
                                   (assoc :ygg-full-hints-path
                                          (:full-hints-path ygg-artifacts))))))

(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  {:schema "ygg.benchmark.agent-packets/v1"
   :suite-id (:id suite)
   :packets (mapv #(agent-packet! suite % opts)
                  (selected-cases suite (case-selector opts)))})

(defn read-suite
  "Read and normalize a benchmark suite EDN file."
  [path]
  (benchmark-suite/read-suite path))

(defn selected-cases
  "Return suite cases, optionally narrowed to one or more case ids."
  [suite selector]
  (benchmark-suite/selected-cases suite selector))

(defn- case-selector
  [opts]
  (benchmark-suite/case-selector opts))

(defn- agent-baseline-result-path
  [suite case opts]
  (benchmark-paths/agent-baseline-result-path suite case opts))

(defn- agent-score-path
  [suite case opts result-file]
  (benchmark-paths/agent-score-path suite case opts result-file))

(defn issue-text
  "Return the fair issue text used as benchmark query input."
  [case]
  (benchmark-prepare/issue-text case))

(defn case-fingerprint
  [suite case]
  (benchmark-prepare/case-fingerprint suite case))

(defn agent-input-fingerprint
  [suite case]
  (benchmark-prepare/agent-input-fingerprint suite case))

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

(defn expected-row-results
  [rows expectations summarize]
  (benchmark-expectations/expected-row-results rows expectations summarize))

(defn forbidden-row-results
  [rows expectations summarize]
  (benchmark-expectations/forbidden-row-results rows expectations summarize))

(defn- evaluate-graph-expectations
  [xtdb prepared]
  (benchmark-expectations/evaluate-graph-expectations xtdb prepared))

(defn- benchmark-index-options
  [opts]
  (benchmark-agent-baseline/benchmark-index-options opts))

(defn context-ground-truth-ranks
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
  "Generate, write, and score one deterministic Yggdrasil agent-result artifact."
  [suite case opts]
  (benchmark-agent-baseline/agent-baseline! suite case opts))

(defn- run-query!
  [xtdb prepared opts]
  (query/semantic-query xtdb
                        (get-in prepared [:input :queryText])
                        (cond-> {:project-id (:project-id prepared)
                                 :retriever (keyword (or (:retriever opts)
                                                         benchmark-agent-baseline/default-agent-baseline-retriever))
                                 :limit (long (or (:limit opts) default-limit))}
                          (= 1 (count (:repos prepared)))
                          (assoc :repo-id (:repo-id prepared)))))

(defn run-case!
  "Run one benchmark case and write its scored result artifact."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        bench-project (agent-project prepared)]
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
               :ygg {:retriever (name (keyword (or (:retriever opts)
                                                   benchmark-agent-baseline/default-agent-baseline-retriever)))
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
                              (get-in result-base [:ygg :topFiles]))})
              result (assoc result-without-scores
                            :scores (benchmark-score/score-result result-without-scores))]
          (benchmark-io/write-json-file! (benchmark-paths/result-path suite case opts) result)
          result)))))

(defn run-suite!
  "Run selected benchmark cases."
  [suite opts]
  {:schema "ygg.benchmark.run/v1"
   :suite-id (:id suite)
   :cases (mapv #(run-case! suite % opts)
                (selected-cases suite (case-selector opts)))})

(defn- agent-baseline-mode
  [opts]
  (case (keyword (or (:retriever opts)
                     benchmark-agent-baseline/default-agent-baseline-retriever))
    :local-vector "local-vector"
    :codebase-memory "codebase-memory"
    :graphify "graphify"
    "ygg"))

(defn current-agent-score-artifacts
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
   :retriever (name (keyword (or (:retriever opts)
                                 benchmark-agent-baseline/default-agent-baseline-retriever)))
   :parserWorker (:parserWorker score)
   :suspectLimit (agent-baseline-suspect-limit opts)
   :status "skipped"
   :skipReason "current-score-artifact"
   :artifacts (cond-> {:agentResultPath (:agentResultPath score)
                       :agentScorePath (:agentScorePath score)
                       :progressPath (fs/canonical-path (benchmark-paths/progress-path suite case opts))}
                (= "local-vector" (get-in score [:agent :mode]))
                (assoc :localVectorRequestPath
                       (fs/canonical-path (benchmark-paths/local-vector-request-path suite case opts)))
                (= "codebase-memory" (get-in score [:agent :mode]))
                (assoc :codebaseMemoryRequestPath
                       (fs/canonical-path (benchmark-paths/codebase-memory-request-path suite case opts))
                       :codebaseMemoryCacheDir
                       (fs/canonical-path (or (:codebase-memory-cache-dir opts)
                                              (benchmark-paths/codebase-memory-cache-dir
                                               suite
                                               case
                                               opts))))
                (= "graphify" (get-in score [:agent :mode]))
                (assoc :graphifyRequestPath
                       (fs/canonical-path (benchmark-paths/graphify-request-path suite case opts))
                       :graphifyOutputDir
                       (fs/canonical-path (or (:graphify-output-dir opts)
                                              (benchmark-paths/graphify-output-dir
                                               suite
                                               case
                                               opts)))))
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

(defn score-agent-result
  "Score an agent localization result against a prepared case artifact."
  [prepared agent-result]
  (benchmark-agent-score/score-agent-result prepared agent-result))

(defn context-packet->agent-result
  "Convert one Yggdrasil context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (benchmark-prediction/context-packet->agent-result packet))
  ([packet opts]
   (benchmark-prediction/context-packet->agent-result packet opts)))

(defn- normalize-agent-result-identity
  [agent-result prepared]
  (benchmark-local-vector/normalize-agent-result-identity agent-result prepared))

(defn local-vector-baseline!
  "Generate, write, and score one local semantic-vector benchmark baseline.

  This is an optional benchmark control. It shells out to a local worker and
  keeps vector dependencies outside Yggdrasil core."
  [suite case opts]
  (benchmark-local-vector/local-vector-baseline! suite case opts))

(defn codebase-memory-baseline!
  "Generate, write, and score one Codebase Memory MCP benchmark baseline."
  [suite case opts]
  (benchmark-codebase-memory/codebase-memory-baseline! suite case opts))

(defn graphify-baseline!
  "Generate, write, and score one Graphify benchmark baseline."
  [suite case opts]
  (benchmark-graphify/graphify-baseline! suite case opts))

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

(defn- write-agent-output-schema!
  [suite case opts]
  (benchmark-agent-run/write-agent-output-schema! suite case opts))

(defn agent-run-prompt
  [packet result-path output-schema-path opts]
  (benchmark-agent-run/agent-run-prompt packet result-path output-schema-path opts))

(defn- write-agent-run-prompt!
  [suite case packet result-path output-schema-path opts]
  (benchmark-agent-run/write-agent-run-prompt! suite case packet result-path output-schema-path opts))

(defn- agent-run-env
  [packet result-path prompt-path output-schema-path opts]
  (benchmark-agent-run/agent-run-env packet result-path prompt-path output-schema-path opts))

(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one Yggdrasil context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (benchmark-hints/context-packet->agent-hints prepared packet opts))

(defn- compact-agent-hints
  [hints paths]
  (benchmark-hints-progressive/compact-agent-hints hints paths))

(defn agent-baselines!
  "Generate deterministic Yggdrasil agent-result artifacts for selected cases."
  [suite opts]
  (let [default-cache? (not (contains? opts :embedding-cache))
        embedding-cache (when default-cache?
                          (embedding/sqlite-cache))
        opts (cond-> opts
               default-cache?
               (assoc :embedding-cache embedding-cache))]
    (try
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
                                    (let [retriever (keyword
                                                     (or (:retriever opts)
                                                         benchmark-agent-baseline/default-agent-baseline-retriever))]
                                      (cond
                                        (= :local-vector retriever)
                                        (local-vector-baseline! suite case opts)

                                        (= :codebase-memory retriever)
                                        (codebase-memory-baseline! suite case opts)

                                        (= :graphify retriever)
                                        (graphify-baseline! suite case opts)

                                        :else
                                        (agent-baseline! suite case opts)))))
            baselines (mapv baseline-for-case
                            (selected-cases suite (case-selector opts)))]
        {:schema agent-baselines-schema
         :suite-id (:id suite)
         :baselines baselines
         :completed (count baselines)
         :skipped (count (filter #(= "skipped" (:status %)) baselines))})
      (finally
        (when default-cache?
          (embedding/close-cache! embedding-cache))))))

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
   :agentInputFingerprint (:agentInputFingerprint prepared)
   :agentId (:agent-id opts)
   :mode (agent-mode opts)
   :suspectedFiles []
   :suspectedSymbols []
   :commands [(agent-run-command opts)]
   :warnings [warning]
   :summary warning})

(defn- token-usage-value
  [usage & keys]
  (some #(get usage %) keys))

(defn- long-token-value
  [value]
  (long (or value 0)))

(defn- double-token-value
  [value]
  (double (or value 0.0)))

(defn- normalize-token-usage
  [usage]
  (when (map? usage)
    (let [usage (or (:tokenUsage usage) usage)
          input (long-token-value
                 (token-usage-value usage
                                    :inputTokens
                                    :input_tokens
                                    :promptTokens
                                    :prompt_tokens))
          output (long-token-value
                  (token-usage-value usage
                                     :outputTokens
                                     :output_tokens
                                     :completionTokens
                                     :completion_tokens))
          total (long-token-value
                 (or (token-usage-value usage :totalTokens :total_tokens)
                     (+ input output)))]
      (cond-> {:inputTokens input
               :outputTokens output
               :totalTokens total
               :costUsd (double-token-value
                         (token-usage-value usage :costUsd :cost_usd))
               :source (str (or (:source usage) "sidecar"))}
        (:model usage)
        (assoc :model (:model usage))

        (:provider usage)
        (assoc :provider (:provider usage))))))

(defn- result-path-stem
  [result-path]
  (let [name (.getName (io/file result-path))]
    (if (str/ends-with? name ".json")
      (subs name 0 (- (count name) (count ".json")))
      name)))

(defn- token-usage-sidecar-path
  [opts result-path]
  (or (:token-usage-path opts)
      (when result-path
        (io/file (.getParentFile (io/file result-path))
                 (str (result-path-stem result-path) ".token-usage.json")))))

(defn- prompt-path-from-result-path
  [result-path]
  (when result-path
    (let [result-file (io/file result-path)
          result-dir (.getParentFile result-file)
          case-dir (some-> result-dir .getParentFile)]
      (when (and result-dir
                 case-dir
                 (= "agent-results" (.getName result-dir)))
        (io/file case-dir
                 "agent-prompts"
                 (str (result-path-stem result-path) ".md"))))))

(defn- agent-prompt-path
  [opts result-path]
  (or (:prompt-path opts)
      (prompt-path-from-result-path result-path)))

(defn- read-token-usage-sidecar
  [opts result-path]
  (when-let [path (token-usage-sidecar-path opts result-path)]
    (when (.isFile (io/file path))
      (try
        (normalize-token-usage (benchmark-io/read-json-file path))
        (catch Exception _
          nil)))))

(defn- prompt-token-usage-estimate
  [opts result-path]
  (when-let [path (agent-prompt-path opts result-path)]
    (when (.isFile (io/file path))
      (try
        (let [input-tokens (context/estimate-tokens (slurp path))]
          {:inputTokens input-tokens
           :outputTokens 0
           :totalTokens input-tokens
           :costUsd 0.0
           :source "benchmark-prompt-estimate"})
        (catch Exception _
          nil)))))

(defn- merge-token-usage-artifacts
  [agent-result opts result-path]
  (if-let [token-usage (read-token-usage-sidecar opts result-path)]
    (assoc agent-result :tokenUsage token-usage)
    (if (:tokenUsage agent-result)
      agent-result
      (if-let [token-usage (prompt-token-usage-estimate opts result-path)]
        (assoc agent-result :tokenUsage token-usage)
        agent-result))))

(defn- read-agent-run-result
  [prepared result-path opts process-result]
  (let [result (cond
                 (not (zero? (:exit process-result)))
                 {:agent-result (failure-agent-result
                                 prepared
                                 opts
                                 (if (:timedOut process-result)
                                   (str "Agent command timed out after "
                                        (agent-run-timeout-ms opts)
                                        " ms.")
                                   (str "Agent command exited with status "
                                        (:exit process-result)
                                        ".")))
                  :artifact-ok? false}

                 (not (.isFile (io/file result-path)))
                 {:agent-result (failure-agent-result
                                 prepared
                                 opts
                                 "Agent command completed but did not write the result JSON artifact.")
                  :artifact-ok? false}

                 :else
                 (try
                   {:agent-result (normalize-agent-run-result
                                   prepared
                                   (benchmark-io/read-json-file result-path)
                                   opts)
                    :artifact-ok? true}
                   (catch Exception e
                     {:agent-result (failure-agent-result
                                     prepared
                                     opts
                                     (str "Agent command wrote unreadable result JSON: "
                                          (.getMessage e)))
                      :artifact-ok? false})))]
    (update result :agent-result merge-token-usage-artifacts opts result-path)))

(defn- read-json-artifact
  [path]
  (when (and path (.isFile (io/file path)))
    (try
      (benchmark-io/read-json-file path)
      (catch Exception _
        nil))))

(defn- ygg-agent-artifact-paths
  [suite case opts]
  (let [context-path (benchmark-paths/agent-run-context-path suite case opts)
        hints-path (benchmark-paths/agent-run-hints-path suite case opts)]
    {:context-path context-path
     :hints-path hints-path
     :full-hints-path (benchmark-hints-progressive/full-hints-path hints-path)
     :preparation-path (benchmark-paths/agent-preparation-path suite case opts)}))

(defn- canonical-ygg-agent-artifacts
  [paths]
  {:context-path (fs/canonical-path (:context-path paths))
   :hints-path (fs/canonical-path (:hints-path paths))
   :full-hints-path (fs/canonical-path (:full-hints-path paths))})

(defn- preparation-agent-id
  [opts]
  (benchmark-paths/agent-run-id opts))

(defn- prepared-artifact-readable?
  [path]
  (and (not (benchmark-util/blankish? path))
       (.isFile (io/file path))))

(defn- prepared-artifacts-readable?
  [artifacts]
  (every? prepared-artifact-readable?
          [(:contextPath artifacts)
           (:hintsPath artifacts)
           (:fullHintsPath artifacts)]))

(defn- compatible-agent-preparation?
  [prepared opts manifest]
  (let [artifacts (:artifacts manifest)]
    (and (= agent-preparation-schema (:schema manifest))
         (= "ygg" (str (:mode manifest)))
         (= (preparation-agent-id opts) (str (:agentId manifest)))
         (= (:case-id prepared) (:case-id manifest))
         (= (:caseFingerprint prepared) (:caseFingerprint manifest))
         (= (:agentInputFingerprint prepared)
            (:agentInputFingerprint manifest))
         (= (parser-worker-profile opts) (:parserWorker manifest))
         (prepared-artifacts-readable? artifacts))))

(defn- reusable-agent-preparation
  [suite case prepared opts]
  (let [manifest-path (:preparation-path (ygg-agent-artifact-paths suite
                                                                   case
                                                                   opts))]
    (when-let [manifest (read-json-artifact manifest-path)]
      (when (compatible-agent-preparation? prepared opts manifest)
        {:summary (:ygg manifest)
         :artifacts {:context-path (get-in manifest [:artifacts :contextPath])
                     :hints-path (get-in manifest [:artifacts :hintsPath])
                     :full-hints-path (get-in manifest
                                              [:artifacts :fullHintsPath])}
         :preparation {:status "reused"
                       :path (fs/canonical-path manifest-path)
                       :preparedAt (:preparedAt manifest)}}))))

(defn- write-agent-preparation-manifest!
  [suite case prepared opts summary artifacts]
  (let [manifest-path (:preparation-path (ygg-agent-artifact-paths suite
                                                                   case
                                                                   opts))
        manifest {:schema agent-preparation-schema
                  :suite-id (:suite-id prepared)
                  :case-id (:case-id prepared)
                  :caseFingerprint (:caseFingerprint prepared)
                  :agentInputFingerprint (:agentInputFingerprint prepared)
                  :repo-id (:repo-id prepared)
                  :project-id (:project-id prepared)
                  :mode "ygg"
                  :agentId (preparation-agent-id opts)
                  :parserWorker (parser-worker-profile opts)
                  :preparedAt (benchmark-progress/now-string)
                  :artifacts {:contextPath (:context-path artifacts)
                              :hintsPath (:hints-path artifacts)
                              :fullHintsPath (:full-hints-path artifacts)}
                  :ygg summary}]
    (benchmark-io/write-json-file! manifest-path manifest)
    (assoc manifest :path (fs/canonical-path manifest-path))))

(def ^:private related-file-seed-limit 8)
(def ^:private related-file-limit 12)
(def ^:private related-file-package-limit 5)
(def ^:private import-package-limit 8)
(def ^:private import-package-file-limit 12)

(defn- dirname
  [path]
  (let [path (str path)
        idx (.lastIndexOf path "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn- ancestor-manifest-paths
  [path filename]
  (let [dir (dirname path)
        dirs (loop [dir dir
                    out []]
               (if (str/blank? dir)
                 out
                 (recur (dirname dir) (conj out dir))))]
    (vec (concat (map #(str % "/" filename) dirs)
                 [filename]))))

(defn- ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(defn- nearest-manifest-node
  [manifest-nodes source-path]
  (->> manifest-nodes
       (filter #(ancestor-dir? (dirname (:path %)) source-path))
       (sort-by (fn [node]
                  (- (count (or (dirname (:path node)) "")))))
       first))

(defn- module-package-suffix
  [module-label target]
  (let [module-label (str module-label)
        target (str target)
        prefix (str module-label "/")]
    (cond
      (= module-label target)
      ""

      (str/starts-with? target prefix)
      (subs target (count prefix)))))

(defn- module-package-path-prefix
  [manifest target]
  (when-some [package-suffix (module-package-suffix (:label manifest) target)]
    (let [manifest-dir (not-empty (dirname (:path manifest)))
          package-suffix (not-empty package-suffix)]
      (not-empty (str/join "/" (cond-> []
                                 manifest-dir (conj manifest-dir)
                                 package-suffix (conj package-suffix)))))))

(defn- related-file-scope
  [prepared opts]
  (merge (select-keys opts [:valid-at :known-at :snapshot-token :current-time])
         {:project-id (:project-id prepared)
          :repo-id (:repo-id prepared)}))

(defn- candidate-file-seeds
  [packet]
  (->> (:candidateFiles packet)
       (keep (fn [{:keys [rank path repoId repo-id repo] :as row}]
               (when (not (benchmark-util/blankish? path))
                 {:rank (long (or rank Long/MAX_VALUE))
                  :path path
                  :repo-id (or repo-id repoId repo)
                  :row row})))
       (sort-by (juxt :rank :path))
       (take related-file-seed-limit)
       vec))

(defn- seed-nodes-by-path
  [xtdb seeds scope]
  (->> (query/nodes-by-paths xtdb (map :path seeds) scope)
       (group-by :path)))

(defn- seed-file-ids
  [nodes-by-path seeds]
  (->> seeds
       (mapcat #(get nodes-by-path (:path %)))
       (keep :file-id)
       distinct
       vec))

(defn- go-import-edge?
  [edge]
  (and (= :imports (:relation edge))
       (some? (import-common/namespace-target (:target-id edge)))))

(defn- manifest-nodes-for-seeds
  [xtdb seeds scope]
  (->> (mapcat #(ancestor-manifest-paths (:path %) "go.mod") seeds)
       distinct
       (#(query/nodes-by-paths xtdb % scope))
       (filter #(and (= :manifest (:kind %))
                     (str/ends-with? (str (:path %)) "go.mod")
                     (seq (:label %))))
       vec))

(defn- manifest-node?
  [row]
  (and (= :manifest (:kind row))
       (str/ends-with? (str (:path row)) "go.mod")
       (seq (:label row))))

(defn- target-module-label-candidates
  [target]
  (let [parts (str/split (str target) #"/")]
    (->> (range (count parts) 0 -1)
         (map #(str/join "/" (take % parts)))
         distinct
         vec)))

(defn- manifest-nodes-for-imports
  [xtdb targets scope]
  (->> targets
       (mapcat target-module-label-candidates)
       distinct
       (#(query/nodes-by-labels xtdb % scope))
       (filter manifest-node?)
       vec))

(defn- matching-import-manifest
  [manifest-nodes target]
  (->> manifest-nodes
       (filter #(module-package-path-prefix % target))
       (sort-by (fn [manifest]
                  (- (count (str (:label manifest))))))
       first))

(defn- import-package-prefixes
  [manifest-nodes seed-by-path edge]
  (when-let [seed (get seed-by-path (:path edge))]
    (when-let [target (import-common/namespace-target (:target-id edge))]
      (when-let [manifest (or (matching-import-manifest manifest-nodes target)
                              (nearest-manifest-node manifest-nodes
                                                     (:path seed)))]
        (when-let [prefix (module-package-path-prefix manifest target)]
          [{:seed seed
            :edge edge
            :manifest manifest
            :target target
            :package-prefix prefix}])))))

(defn- namespace-file-node?
  [row]
  (and (= :namespace (:kind row))
       (not (benchmark-util/blankish? (:path row)))))

(defn- package-node-sort-key
  [package-prefix node]
  (let [path (str (:path node))
        prefix (str package-prefix)
        suffix (cond
                 (= path prefix) ""
                 (str/starts-with? path (str prefix "/")) (subs path
                                                                (inc (count prefix)))
                 :else path)
        parts (if (str/blank? suffix)
                []
                (str/split suffix #"/"))
        file-name (or (last parts) "")
        package-name (last (str/split prefix #"/"))]
    [(count parts)
     (if (= file-name (str package-name ".go")) 0 1)
     (if (str/ends-with? file-name "_test.go") 1 0)
     path]))

(defn- node-package-prefix
  [package-prefixes node]
  (some (fn [prefix]
          (let [path (str (:path node))]
            (when (or (= path prefix)
                      (str/starts-with? path (str prefix "/")))
              prefix)))
        package-prefixes))

(defn- cap-package-prefix-nodes
  [nodes-by-prefix]
  (reduce-kv (fn [acc prefix nodes]
               (assoc acc
                      prefix
                      (->> nodes
                           (sort-by #(package-node-sort-key prefix %))
                           (take related-file-package-limit)
                           vec)))
             {}
             nodes-by-prefix))

(defn- related-file-evidence
  [{:keys [seed edge manifest target package-prefix]}]
  (str "source-graph: "
       (:path seed)
       " imports "
       target
       " line "
       (or (:source-line edge) "?")
       " via module "
       (:label manifest)
       " -> package "
       package-prefix))

(defn- package-file-row
  [node]
  (cond-> {:path (:path node)}
    (or (:repoId node) (:repo-id node))
    (assoc :repoId (or (:repoId node) (:repo-id node)))

    (:repo node)
    (assoc :repo (:repo node))

    (:kind node)
    (assoc :kind (:kind node))))

(defn- import-package-candidate-key
  [{:keys [prefix matches nodes]}]
  [(or (->> matches
            (keep (comp :rank :seed))
            seq
            (apply min))
       Long/MAX_VALUE)
   (- (count (distinct (map (comp :path :seed) matches))))
   (- (count nodes))
   prefix])

(defn- import-package-row
  [rank prefix prefix-matches nodes]
  (let [first-match (first prefix-matches)]
    {:rank rank
     :packagePrefix prefix
     :target (:target first-match)
     :relation "imports-package"
     :seedPaths (->> prefix-matches
                     (map (comp :path :seed))
                     distinct
                     vec)
     :evidence [(related-file-evidence first-match)]
     :files (->> nodes
                 (sort-by #(package-node-sort-key prefix %))
                 (take import-package-file-limit)
                 (mapv package-file-row))}))

(defn- related-file-row
  [prefix-match node]
  (let [{:keys [seed edge target package-prefix]} prefix-match]
    {:path (:path node)
     :repoId (or (:repo-id node) (:repo-id seed))
     :sourceLine 1
     :relation "imports-package"
     :reason "Graph import edge resolves to a local package directory under the nearest module manifest."
     :evidence [(related-file-evidence prefix-match)]
     :via [{:seedPath (:path seed)
            :seedRank (:rank seed)
            :relation (name (:relation edge))
            :sourceLine (:source-line edge)
            :target target
            :packagePrefix package-prefix}]}))

(defn- merge-related-file-rows
  [rows]
  (->> rows
       (group-by (juxt :repoId :path))
       (map (fn [[_ grouped]]
              (let [first-row (first grouped)]
                (-> first-row
                    (assoc :evidence (->> grouped
                                          (mapcat :evidence)
                                          distinct
                                          vec)
                           :via (->> grouped
                                     (mapcat :via)
                                     distinct
                                     vec))))))
       (sort-by (juxt (comp #(or % Long/MAX_VALUE) :seedRank first :via)
                      :path))
       (map-indexed (fn [idx row] (assoc row :rank (inc idx))))
       (take related-file-limit)
       vec))

(defn- graph-related-artifacts
  [xtdb prepared packet opts]
  (let [seeds (candidate-file-seeds packet)
        scope (related-file-scope prepared opts)
        seed-paths (set (map :path seeds))
        seed-by-path (into {} (map (juxt :path identity)) seeds)
        nodes-by-path (seed-nodes-by-path xtdb seeds scope)
        file-ids (seed-file-ids nodes-by-path seeds)
        import-edges (->> (query/edges-by-file-ids xtdb file-ids scope)
                          (filter go-import-edge?)
                          vec)
        import-targets (->> import-edges
                            (keep #(import-common/namespace-target
                                    (:target-id %)))
                            distinct
                            vec)
        manifest-nodes (->> (concat (manifest-nodes-for-seeds xtdb
                                                              seeds
                                                              scope)
                                    (manifest-nodes-for-imports xtdb
                                                                import-targets
                                                                scope))
                            (distinct)
                            vec)
        prefix-matches (mapcat #(import-package-prefixes manifest-nodes
                                                         seed-by-path
                                                         %)
                               import-edges)
        package-prefixes (->> prefix-matches
                              (map :package-prefix)
                              distinct
                              vec)
        nodes-by-prefix (->> (query/nodes-by-path-prefixes xtdb
                                                           package-prefixes
                                                           scope)
                             (filter namespace-file-node?)
                             (remove #(contains? seed-paths (:path %)))
                             (group-by #(node-package-prefix package-prefixes %))
                             (#(dissoc % nil))
                             cap-package-prefix-nodes)
        matches-by-prefix (group-by :package-prefix prefix-matches)
        related-files (merge-related-file-rows
                       (mapcat (fn [{:keys [package-prefix] :as prefix-match}]
                                 (map #(related-file-row prefix-match %)
                                      (get nodes-by-prefix package-prefix)))
                               prefix-matches))
        import-packages (->> package-prefixes
                             (keep (fn [prefix]
                                     (when-let [nodes (seq (get nodes-by-prefix
                                                                prefix))]
                                       {:prefix prefix
                                        :matches (get matches-by-prefix prefix)
                                        :nodes nodes})))
                             (sort-by import-package-candidate-key)
                             (take import-package-limit)
                             (map-indexed (fn [idx {:keys [prefix matches nodes]}]
                                            (import-package-row
                                             (inc idx)
                                             prefix
                                             matches
                                             nodes)))
                             vec)]
    {:related-files related-files
     :import-packages import-packages}))

(defn- prepare-agent-graph-and-artifacts!
  [suite case prepared opts]
  (when (= "ygg" (agent-mode opts))
    (let [paths (ygg-agent-artifact-paths suite case opts)
          context-path (:context-path paths)
          hints-path (:hints-path paths)
          full-hints-path (:full-hints-path paths)
          project-path (benchmark-paths/agent-project-path suite case opts)
          bench-project (agent-project prepared)]
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
          (let [correction-overlay (benchmark-maintenance/prepare-agent-overlay!
                                    xtdb
                                    case
                                    prepared
                                    opts)
                index-summary (benchmark-progress/progress-stage!
                               suite
                               case
                               opts
                               :index-project
                               #(with-benchmark-parser-worker
                                  opts
                                  (fn []
                                    (project/index-project!
                                     xtdb
                                     bench-project
                                     (assoc (benchmark-index-options opts)
                                            :correction-overlay correction-overlay))))
                               #(select-keys % [:files :repos :rows :extractors]))
                system-summary (benchmark-progress/progress-stage!
                                suite
                                case
                                opts
                                :infer-project
                                #(project/infer-project! xtdb bench-project)
                                #(select-keys % [:systems :candidates :edges]))
                summary {:indexSummary index-summary
                         :systemSummary system-summary
                         :graphExpectations (evaluate-graph-expectations xtdb
                                                                         prepared)
                         :syncInspect (benchmark-maintenance/sync-inspect-summary
                                       xtdb
                                       suite
                                       case
                                       prepared
                                       opts)}
                packet (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :context-packet
                        #(context/context-packet
                          xtdb
                          (get-in prepared [:input :queryText])
                          (assoc (agent-baseline-context-options
                                  prepared
                                  opts)
                                 :correction-overlay correction-overlay))
                        (fn [packet]
                          {:docs (count (:docs packet))
                           :entities (count (:entities packet))
                           :edges (count (:edges packet))
                           :warnings (count (:warnings packet))}))
                related-artifacts (benchmark-progress/progress-stage!
                                   suite
                                   case
                                   opts
                                   :context-related-files
                                   #(graph-related-artifacts xtdb prepared packet opts)
                                   (fn [artifacts]
                                     {:relatedFiles (count (:related-files artifacts))
                                      :importPackages (count (:import-packages artifacts))}))
                related-files (:related-files related-artifacts)
                import-packages (:import-packages related-artifacts)
                packet (cond-> packet
                         (seq related-files)
                         (assoc :relatedFiles related-files)
                         (seq import-packages)
                         (assoc :importPackages import-packages))
                artifacts (canonical-ygg-agent-artifacts paths)]
            (benchmark-progress/progress-stage!
             suite
             case
             opts
             :write-agent-artifacts
             (fn []
               (let [full-hints (context-packet->agent-hints prepared
                                                             packet
                                                             opts)
                     hints (compact-agent-hints
                            full-hints
                            {:context-path context-path
                             :full-hints-path full-hints-path
                             :root (:worktreeRoot prepared)
                             :roots (:worktreeRoots prepared)})]
                 (benchmark-io/write-json-file! context-path packet)
                 (benchmark-io/write-json-file! full-hints-path full-hints)
                 (benchmark-io/write-json-file! hints-path hints)
                 (write-agent-preparation-manifest! suite
                                                    case
                                                    prepared
                                                    opts
                                                    summary
                                                    artifacts)
                 {:full-hints full-hints
                  :hints hints}))
             (fn [{:keys [full-hints hints]}]
               {:contextPath (:context-path artifacts)
                :hintsPath (:hints-path artifacts)
                :fullHintsPath (:full-hints-path artifacts)
                :topFiles (count (:topFiles hints))
                :fullTopFiles (count (:topFiles full-hints))
                :diagnostics (count (:diagnostics hints))}))
            {:summary summary
             :artifacts artifacts
             :preparation {:status "prepared"
                           :path (fs/canonical-path
                                  (:preparation-path paths))}}))))))

(defn prepare-or-reuse-agent-graph-and-artifacts!
  [suite case prepared opts]
  (when (= "ygg" (agent-mode opts))
    (or (when-let [reused (reusable-agent-preparation suite
                                                      case
                                                      prepared
                                                      opts)]
          (benchmark-progress/progress-stage!
           suite
           case
           opts
           :reuse-agent-artifacts
           (constantly reused)
           (fn [result]
             (select-keys (:preparation result) [:status :path :preparedAt]))))
        (prepare-agent-graph-and-artifacts! suite case prepared opts))))

(defn- read-agent-hints
  [hints-path]
  (when (and (not (benchmark-util/blankish? hints-path))
             (.isFile (io/file hints-path)))
    (benchmark-io/read-json-file hints-path)))

(defn- context-artifact-telemetry
  [paths]
  (benchmark-context-artifacts/context-artifact-telemetry paths))

(defn- refresh-agent-hints-from-context!
  [suite case opts prepared]
  (let [context-path (benchmark-paths/agent-run-context-path suite case opts)
        hints-path (benchmark-paths/agent-run-hints-path suite case opts)
        full-hints-path (benchmark-hints-progressive/full-hints-path hints-path)]
    (if-let [packet (read-json-artifact context-path)]
      (let [full-hints (context-packet->agent-hints prepared packet opts)
            hints (compact-agent-hints
                   full-hints
                   {:context-path context-path
                    :full-hints-path full-hints-path
                    :root (:worktreeRoot prepared)
                    :roots (:worktreeRoots prepared)})]
        (benchmark-io/write-json-file! full-hints-path full-hints)
        (benchmark-io/write-json-file! hints-path hints)
        hints)
      (read-agent-hints hints-path))))

(defn- result-file-run-id
  [result-file]
  (some-> result-file
          io/file
          .getName
          (str/replace #"\.json$" "")
          benchmark-paths/safe-id))

(defn- scoring-artifact-opts
  [opts agent-result result-file]
  (assoc opts
         :agent-id
         (or (:agent-id opts)
             (some-> (:agentId agent-result) benchmark-paths/safe-id)
             (result-file-run-id result-file))))

(defn- same-file?
  [a b]
  (and (not (benchmark-util/blankish? a))
       (not (benchmark-util/blankish? b))
       (= (fs/canonical-path a) (fs/canonical-path b))))

(defn- compatible-agent-run?
  [prepared agent-result result-file run]
  (and (= "ygg" (str (:mode run)))
       (= (:case-id prepared) (:case-id run))
       (or (benchmark-util/blankish? (:agentId run))
           (benchmark-util/blankish? (:agentId agent-result))
           (= (str (:agentId agent-result)) (str (:agentId run))))
       (or (benchmark-util/blankish? (get-in run [:artifacts :agentResultPath]))
           (same-file? result-file (get-in run [:artifacts :agentResultPath])))))

(defn- score-agent-result-graph-expectations
  [suite case prepared opts]
  (let [xtdb-dir (benchmark-paths/xtdb-dir suite case opts)]
    (when (.exists (io/file xtdb-dir))
      (try
        (store/with-node xtdb-dir
          (fn [xtdb]
            (evaluate-graph-expectations xtdb prepared)))
        (catch Exception _
          nil)))))

(defn- score-agent-result-sync-inspect
  [suite case prepared opts]
  (let [xtdb-dir (benchmark-paths/xtdb-dir suite case opts)]
    (when (.exists (io/file xtdb-dir))
      (try
        (store/with-node xtdb-dir
          (fn [xtdb]
            (benchmark-maintenance/prepare-agent-corrections! xtdb
                                                              case
                                                              prepared
                                                              opts)
            (benchmark-maintenance/sync-inspect-summary xtdb
                                                        suite
                                                        case
                                                        prepared
                                                        opts)))
        (catch Exception _
          nil)))))

(defn- score-agent-result-run
  [suite case prepared agent-result result-file opts]
  (let [run (read-json-artifact (benchmark-paths/agent-run-path suite case opts))]
    (when (compatible-agent-run? prepared agent-result result-file run)
      run)))

(defn- result-file-context-path
  [result-file]
  (when-not (benchmark-util/blankish? result-file)
    (let [file (io/file result-file)
          name (.getName file)]
      (when (str/ends-with? name ".json")
        (io/file (.getParentFile file)
                 (str/replace name #"\.json$" ".context.json"))))))

(defn- readable-artifact-path
  [path]
  (when (and path (.isFile (io/file path)))
    (fs/canonical-path path)))

(defn- first-readable-artifact-path
  [paths]
  (some readable-artifact-path paths))

(defn- score-agent-result-context-path
  [suite case opts result-file existing-score]
  (first-readable-artifact-path
   [(benchmark-paths/agent-run-context-path suite case opts)
    (:contextPacketPath existing-score)
    (result-file-context-path result-file)
    (benchmark-paths/agent-baseline-context-path suite case opts)]))

(defn- preflight-status
  [maintenance-preflight]
  (str (or (:status maintenance-preflight) "not-run")))

(defn- richer-existing-preflight
  [existing-score computed-preflight]
  (let [existing-preflight (:maintenancePreflight existing-score)]
    (cond
      (nil? existing-preflight)
      computed-preflight

      (nil? computed-preflight)
      existing-preflight

      (and (= "not-run" (preflight-status computed-preflight))
           (not= "not-run" (preflight-status existing-preflight)))
      existing-preflight

      :else
      computed-preflight)))

(defn- augment-ygg-score-from-artifacts
  [suite case opts prepared agent-result result-file existing-score scored]
  (if (= "ygg" (str (get-in scored [:agent :mode])))
    (let [artifact-opts (scoring-artifact-opts opts agent-result result-file)
          run (score-agent-result-run suite
                                      case
                                      prepared
                                      agent-result
                                      result-file
                                      artifact-opts)
          run-summary (:ygg run)
          run-context-path (benchmark-paths/agent-run-context-path suite
                                                                   case
                                                                   artifact-opts)
          context-path (score-agent-result-context-path suite
                                                        case
                                                        artifact-opts
                                                        result-file
                                                        existing-score)
          context-ranks (or (context-ground-truth-ranks-from-path prepared
                                                                  context-path)
                            (:contextGroundTruthRanks existing-score))
          hints (refresh-agent-hints-from-context! suite case artifact-opts prepared)
          context-artifacts (context-artifact-telemetry
                             {:prompt (benchmark-paths/agent-run-prompt-path
                                       suite
                                       case
                                       artifact-opts)
                              :yggHints (benchmark-paths/agent-run-hints-path
                                         suite
                                         case
                                         artifact-opts)
                              :yggFullHints (benchmark-hints-progressive/full-hints-path
                                             (benchmark-paths/agent-run-hints-path
                                              suite
                                              case
                                              artifact-opts))
                              :yggContext (or context-path run-context-path)})
          hint-diagnostics (not-empty (vec (:diagnostics hints)))
          graph-expectations (score-agent-result-graph-expectations suite
                                                                    case
                                                                    prepared
                                                                    artifact-opts)
          benchmark-activity (benchmark-maintenance/record-benchmark-agent-activity-from-artifacts!
                              suite
                              case
                              prepared
                              artifact-opts
                              agent-result
                              result-file
                              "passed")
          sync-inspect (or (:syncInspect benchmark-activity)
                           (score-agent-result-sync-inspect suite
                                                            case
                                                            prepared
                                                            artifact-opts)
                           (:syncInspect run-summary))
          maintenance-preflight (when (or run-summary
                                          context-ranks
                                          hints
                                          graph-expectations
                                          sync-inspect)
                                  (benchmark-preflight/maintenance-preflight
                                   {:index-summary (:indexSummary run-summary)
                                    :system-summary (:systemSummary run-summary)
                                    :graph-expectations graph-expectations
                                    :expectations (:expectations prepared)
                                    :hints hints
                                    :sync-inspect sync-inspect}))
          maintenance-preflight (richer-existing-preflight existing-score
                                                           maintenance-preflight)]
      (-> (cond-> scored
            context-ranks
            (assoc :contextGroundTruthRanks context-ranks)
            context-artifacts
            (assoc :contextArtifacts context-artifacts)
            hint-diagnostics
            (assoc :yggHints {:diagnostics hint-diagnostics})
            graph-expectations
            (assoc :graphExpectations graph-expectations)
            benchmark-activity
            (assoc :benchmarkActivity (:activity benchmark-activity))
            sync-inspect
            (assoc :syncInspect sync-inspect)
            (:agentPreparation run)
            (assoc :agentPreparation (:agentPreparation run)))
          (benchmark-preflight/assoc-run-preflight maintenance-preflight)))
    scored))

(defn agent-run!
  "Run one external agent command against a benchmark packet, then score it."
  [suite case opts]
  (ensure-agent-run-id! opts)
  (let [prepared (prepare-case! suite case opts)
        ygg-prep (prepare-or-reuse-agent-graph-and-artifacts! suite
                                                              case
                                                              prepared
                                                              opts)
        ygg-summary (:summary ygg-prep)
        ygg-artifacts (:artifacts ygg-prep)
        packet (agent-packet-from-prepared! suite
                                            case
                                            prepared
                                            (cond-> opts
                                              (:preparation ygg-prep)
                                              (assoc :agent-preparation
                                                     (:preparation ygg-prep))
                                              (:context-path ygg-artifacts)
                                              (assoc :ygg-context-path
                                                     (:context-path ygg-artifacts))
                                              (:hints-path ygg-artifacts)
                                              (assoc :ygg-hints-path
                                                     (:hints-path ygg-artifacts))
                                              (:full-hints-path ygg-artifacts)
                                              (assoc :ygg-full-hints-path
                                                     (:full-hints-path ygg-artifacts))))
        result-path (benchmark-paths/agent-run-result-path suite case opts)
        token-usage-path (benchmark-paths/agent-run-token-usage-path suite
                                                                     case
                                                                     opts)
        run-opts (assoc opts
                        :token-usage-path
                        (fs/canonical-path token-usage-path))
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
        process-result (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :agent-result
                        #(run-process! command
                                       (:worktreeRoot prepared)
                                       (agent-run-env packet
                                                      result-path
                                                      prompt-path
                                                      output-schema-path
                                                      run-opts)
                                       timeout-ms)
                        #(select-keys % [:exit :timedOut :durationMs]))
        logs (write-agent-run-logs! suite case opts process-result)
        read-result (read-agent-run-result prepared
                                           result-path
                                           run-opts
                                           process-result)
        agent-result (:agent-result read-result)
        _ (benchmark-io/write-json-file! result-path agent-result)
        context-artifacts (context-artifact-telemetry
                           {:prompt prompt-path
                            :yggHints (get-in packet [:artifacts :yggHintsPath])
                            :yggFullHints (get-in packet
                                                  [:artifacts :yggFullHintsPath])
                            :yggContext (get-in packet
                                                [:artifacts :yggContextPath])})
        context-ranks (context-ground-truth-ranks-from-path
                       prepared
                       (get-in packet [:artifacts :yggContextPath]))
        hints (read-agent-hints (get-in packet [:artifacts :yggHintsPath]))
        hint-diagnostics (not-empty (vec (:diagnostics hints)))
        status (if (:artifact-ok? read-result)
                 "passed"
                 "failed")
        benchmark-activity (when ygg-summary
                             (benchmark-maintenance/record-benchmark-agent-activity-from-artifacts!
                              suite
                              case
                              prepared
                              opts
                              agent-result
                              result-path
                              status))
        sync-inspect (or (:syncInspect benchmark-activity)
                         (:syncInspect ygg-summary))
        ygg-summary (cond-> ygg-summary
                      sync-inspect
                      (assoc :syncInspect sync-inspect))
        maintenance-preflight (when ygg-summary
                                (benchmark-preflight/maintenance-preflight
                                 {:index-summary (:indexSummary ygg-summary)
                                  :system-summary (:systemSummary ygg-summary)
                                  :graph-expectations (:graphExpectations ygg-summary)
                                  :expectations (:expectations prepared)
                                  :hints hints
                                  :sync-inspect sync-inspect}))
        scored (benchmark-progress/progress-stage!
                suite
                case
                opts
                :score-agent-result
                (fn []
                  (cond-> (benchmark-preflight/assoc-run-preflight
                           (assoc (score-agent-result prepared agent-result)
                                  :agentResultPath (fs/canonical-path result-path)
                                  :parserWorker (parser-worker-profile opts))
                           maintenance-preflight)
                    (:preparation ygg-prep)
                    (assoc :agentPreparation (:preparation ygg-prep))
                    context-ranks
                    (assoc :contextGroundTruthRanks context-ranks)
                    context-artifacts
                    (assoc :contextArtifacts context-artifacts)
                    hint-diagnostics
                    (assoc :yggHints {:diagnostics hint-diagnostics})
                    benchmark-activity
                    (assoc :benchmarkActivity (:activity benchmark-activity))
                    sync-inspect
                    (assoc :syncInspect sync-inspect)
                    (:graphExpectations ygg-summary)
                    (assoc :graphExpectations (:graphExpectations ygg-summary))))
                (fn [score]
                  {:fileRecallAt10 (get-in score [:scores :fileRecallAt10])
                   :meanReciprocalRankFile (get-in score
                                                   [:scores
                                                    :meanReciprocalRankFile])}))
        run (cond-> {:schema agent-run-schema
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
                     :process (select-keys process-result
                                           [:exit :timedOut :durationMs])
                     :artifacts (merge
                                 {:packetPath (get-in packet
                                                      [:artifacts :packetPath])
                                  :promptPath prompt-path
                                  :outputSchemaPath output-schema-path
                                  :yggHintsPath (get-in packet
                                                        [:artifacts
                                                         :yggHintsPath])
                                  :yggFullHintsPath (get-in packet
                                                            [:artifacts
                                                             :yggFullHintsPath])
                                  :yggContextPath (get-in packet
                                                          [:artifacts
                                                           :yggContextPath])
                                  :projectConfig (get-in packet
                                                         [:artifacts
                                                          :projectConfig])
                                  :xtdbPath (get-in packet
                                                    [:artifacts :xtdbPath])
                                  :agentResultPath (fs/canonical-path
                                                    result-path)
                                  :tokenUsagePath (fs/canonical-path
                                                   token-usage-path)
                                  :agentScorePath (fs/canonical-path
                                                   score-path)
                                  :agentRunPath (fs/canonical-path run-path)}
                                 logs)
                     :ygg ygg-summary
                     :benchmarkActivity (:activity benchmark-activity)
                     :syncInspect sync-inspect
                     :contextArtifacts context-artifacts
                     :maintenancePreflight maintenance-preflight
                     :claimReady (benchmark-preflight/claim-ready?
                                  maintenance-preflight)
                     :graphExpectations (:graphExpectations ygg-summary)
                     :scores (:scores scored)
                     :warnings (get-in scored [:agent :warnings])}
              (:preparation ygg-prep)
              (assoc :agentPreparation (:preparation ygg-prep)))]
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

(defn- agent-rerun-report-path
  [suite opts]
  (fs/canonical-path
   (or (:agent-report-path opts)
       (benchmark-paths/agent-report-path suite opts))))

(defn- agent-rerun-improvement-rows
  [report]
  (->> (:improvementSummary report)
       (filter #(contains? agent-rerun-improvement-kinds (:kind %)))
       vec))

(defn- explicit-agent-rerun-case-ids
  [opts]
  (cond
    (seq (:case-ids opts)) (vec (:case-ids opts))
    (:case-id opts) [(:case-id opts)]))

(defn- agent-rerun-case-ids
  [rows]
  (->> rows
       (mapcat :caseIds)
       distinct
       sort
       vec))

(defn rerun-agent-lane!
  "Rerun and rescore agent-lane cases flagged by an existing agent report."
  [suite opts]
  (ensure-agent-run-id! opts)
  (let [report-path (agent-rerun-report-path suite opts)
        explicit-case-ids (explicit-agent-rerun-case-ids opts)
        rows (if (seq explicit-case-ids)
               []
               (agent-rerun-improvement-rows
                (benchmark-io/read-json-file report-path)))
        case-ids (or (not-empty explicit-case-ids)
                     (agent-rerun-case-ids rows))
        run-opts (-> opts
                     (assoc :case-ids case-ids)
                     (dissoc :case-id
                             :agent-report-path
                             :skip-existing?))
        result (if (seq case-ids)
                 (agent-runs! suite run-opts)
                 {:schema agent-runs-schema
                  :suite-id (:id suite)
                  :runs []
                  :completed 0
                  :failed 0
                  :skipped 0})]
    (assoc result
           :rerunLane {:sourceReportPath report-path
                       :selection (if (seq explicit-case-ids)
                                    "explicit"
                                    "improvement-summary")
                       :sourceKinds (->> rows
                                         (map :kind)
                                         distinct
                                         sort
                                         vec)
                       :caseIds (vec case-ids)})))

(defn score-agent-result!
  "Read, score, and write one agent localization result artifact."
  [suite case opts]
  (let [result-file (or (:result-path opts)
                        (throw (ex-info "Missing agent result path."
                                        {:case-id (:id case)})))
        prepared (prepare-case! suite case opts)
        agent-result (merge-token-usage-artifacts
                      (benchmark-io/read-json-file result-file)
                      opts
                      result-file)
        score-path (benchmark-paths/agent-score-path suite case opts result-file)
        existing-score (read-json-artifact score-path)
        scored (->> (assoc (score-agent-result prepared agent-result)
                           :parserWorker (agent-score-parser-worker-profile
                                          opts
                                          agent-result)
                           :agentResultPath (fs/canonical-path result-file))
                    (augment-ygg-score-from-artifacts suite
                                                      case
                                                      opts
                                                      prepared
                                                      agent-result
                                                      result-file
                                                      existing-score))]
    (benchmark-io/write-json-file! score-path scored)
    scored))

(defn case-result
  "Read one case result when it exists."
  [suite case opts]
  (benchmark-results/case-result suite case opts))

(defn show-case
  "Return one case result, or its prepared artifact when no result exists."
  [suite case-id opts]
  (benchmark-results/show-case suite case-id opts))

(defn agent-output-diagnostic
  [result]
  (benchmark-report/agent-output-diagnostic result))

(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (benchmark-report/report-agent-suite suite opts))

(defn improve-agent-suite
  "Analyze benchmark diagnostics as dev-time system improvement guidance."
  [suite opts]
  (benchmark-system-improvement/write-system-improvement-report!
   suite
   opts
   (report-agent-suite suite opts)))

(defn check-agent-report
  "Return a pass/fail check over an agent benchmark report."
  [report opts]
  (benchmark-check/check-agent-report report opts))

(defn check-agent-suite
  "Aggregate existing agent score artifacts and check them against thresholds."
  [suite opts]
  (benchmark-check/check-agent-suite suite opts))

(defn compare-agent-reports
  "Compare two agent reports and mark metric regressions.

  Higher is better for recall and MRR; lower is better for noise. The optional
  `:regression-tolerance` allows tiny floating-point or sampling drift without
  hiding meaningful regressions."
  [baseline-report candidate-report opts]
  (benchmark-compare/compare-agent-reports baseline-report candidate-report opts))

(defn compare-agent-report-files!
  "Read, compare, and write an agent report comparison artifact."
  [suite opts]
  (benchmark-compare/compare-agent-report-files! suite opts))

(defn claim-pack!
  "Write a benchmark claim pack from shell-only and Yggdrasil agent reports."
  [suite opts]
  (benchmark-claim-pack/write-claim-pack! suite opts))

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
