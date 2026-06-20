(ns agraph.benchmark
  "Issue replay benchmarks for AGraph retrieval quality."
  (:require [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-score :as benchmark-score]
            [agraph.context :as context]
            [agraph.fs :as fs]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [agraph.benchmark-expectations :as benchmark-expectations]
            [agraph.benchmark-suite :as benchmark-suite]
            [agraph.benchmark-prepare :as benchmark-prepare]
            [agraph.benchmark-prediction :as benchmark-prediction]
            [agraph.benchmark-hints :as benchmark-hints]
            [agraph.benchmark-agent-score :as benchmark-agent-score]
            [agraph.benchmark-results :as benchmark-results]
            [agraph.benchmark-agent-run :as benchmark-agent-run]
            [agraph.benchmark-agent-packet :as benchmark-agent-packet]
            [agraph.benchmark-local-vector :as benchmark-local-vector]
            [agraph.benchmark-score-artifacts :as benchmark-score-artifacts]
            [agraph.benchmark-agent-baseline :as benchmark-agent-baseline]
            [agraph.benchmark-report :as benchmark-report]
            [agraph.benchmark-check :as benchmark-check]
            [agraph.benchmark-compare :as benchmark-compare]
            [clojure.string :as str]))

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

(declare agent-prompt-profile)

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
   :agentInputFingerprint (:agentInputFingerprint prepared)
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
