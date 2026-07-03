(ns ygg.benchmark-graphify
  (:require [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-local-vector :as benchmark-local-vector]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.context :as context]
            [ygg.fs :as fs]
            [clojure.java.shell :as shell]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def agent-baseline-schema
  "ygg.benchmark.agent-baseline/v1")

(def default-agent-baseline-suspect-limit
  20)

(def default-graphify-command
  "python3 scripts/graphify-baseline.py")

(def default-graphify-bin
  "uvx --from graphifyy graphify")

(def default-graphify-query-budget
  4000)

(defn- agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

(defn- graphify-command
  [opts]
  (str (or (:graphify-command opts) default-graphify-command)))

(defn- graphify-bin
  [opts]
  (str (or (:graphify-bin opts)
           (System/getenv "GRAPHIFY_BENCH_CMD")
           default-graphify-bin)))

(defn- graphify-output-dir
  [suite case opts]
  (fs/canonical-path
   (or (:graphify-output-dir opts)
       (benchmark-paths/graphify-output-dir suite case opts))))

(defn- graphify-query-budget
  [opts]
  (long (or (:graphify-query-budget opts) default-graphify-query-budget)))

(defn- graphify-request
  [suite case prepared opts agent-id]
  {:schema "ygg.benchmark.graphify-request/v1"
   :caseId (:case-id prepared)
   :caseFingerprint (:caseFingerprint prepared)
   :agentInputFingerprint (:agentInputFingerprint prepared)
   :agentId agent-id
   :mode "graphify"
   :worktreeRoot (:worktreeRoot prepared)
   :input (:input prepared)
   :task {:kind "issue-localization"
          :objective "Rank repo-relative files using Graphify graph extraction and query output."
          :rules ["Use only the base checkout and issue text in this request."
                  "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."
                  "Default to code-only Graphify extraction so the lane is replayable without LLM API keys."]}
   :limit (agent-baseline-suspect-limit opts)
   :graphify (cond-> {:command (graphify-bin opts)
                      :outputDir (graphify-output-dir suite case opts)
                      :codeOnly (not (:graphify-include-non-code? opts))
                      :queryBudget (graphify-query-budget opts)}
               (:graphify-max-workers opts)
               (assoc :maxWorkers (:graphify-max-workers opts)))})

(defn- run-graphify-command!
  [request-path result-path opts]
  (let [command (graphify-command opts)
        cmdline (str command
                     " "
                     (benchmark-agent-packet/shell-quote (fs/canonical-path request-path))
                     " "
                     (benchmark-agent-packet/shell-quote (fs/canonical-path result-path)))
        {:keys [exit out err] :as process} (shell/sh "sh" "-c" cmdline)]
    (when-not (zero? exit)
      (throw (ex-info "Graphify benchmark worker failed."
                      {:command command
                       :exit exit
                       :out out
                       :err err})))
    (select-keys process [:exit :out :err])))

(defn- valid-token-usage?
  [usage]
  (and (map? usage)
       (pos? (long (or (:totalTokens usage) 0)))))

(defn- result-surface-token-usage
  [agent-result]
  (let [input-tokens (context/estimate-tokens (dissoc agent-result :tokenUsage))]
    {:inputTokens input-tokens
     :outputTokens 0
     :totalTokens input-tokens
     :costUsd 0.0
     :source "graphify-result-surface-estimate"}))

(defn- normalize-graphify-result
  [agent-result prepared agent-id]
  (let [result (assoc (benchmark-local-vector/normalize-agent-result-identity
                       agent-result
                       prepared)
                      :agentId agent-id
                      :mode "graphify")]
    (cond-> result
      (not (valid-token-usage? (:tokenUsage result)))
      (assoc :tokenUsage (result-surface-token-usage result)))))

(defn graphify-baseline!
  "Generate, write, and score one Graphify benchmark baseline.

  This is an optional comparison lane. It shells out to a local worker and keeps
  Graphify extraction/query behavior outside Yggdrasil core."
  [suite case opts]
  (let [prepared (benchmark-prepare/prepare-case! suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)
        request-path (benchmark-paths/graphify-request-path suite case opts)
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        score-path (benchmark-paths/agent-score-path suite case opts result-path)
        progress-path (benchmark-paths/progress-path suite case opts)
        output-dir (graphify-output-dir suite case opts)
        request (graphify-request suite case prepared opts agent-id)]
    (benchmark-progress/reset-progress! suite case opts)
    (benchmark-progress/progress-stage!
     suite
     case
     opts
     :write-graphify-request
     #(benchmark-io/write-json-file! request-path request)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (let [process (benchmark-progress/progress-stage!
                   suite
                   case
                   opts
                   :graphify-worker
                   #(run-graphify-command! request-path result-path opts)
                   #(select-keys % [:exit]))
          agent-result (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :graphify-result
                        #(normalize-graphify-result
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
                  #(assoc (benchmark-agent-score/score-agent-result prepared agent-result)
                          :agentResultPath (fs/canonical-path result-path)
                          :graphifyRequestPath (fs/canonical-path request-path))
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
                    :agentInputFingerprint (:agentInputFingerprint prepared)
                    :agentId agent-id
                    :mode "graphify"
                    :retriever "graphify"
                    :suspectLimit (agent-baseline-suspect-limit opts)
                    :artifacts {:graphifyRequestPath (fs/canonical-path request-path)
                                :graphifyOutputDir output-dir
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :progressPath (fs/canonical-path progress-path)}
                    :graphify {:command (graphify-command opts)
                               :binary (graphify-bin opts)
                               :outputDir output-dir
                               :codeOnly (not (:graphify-include-non-code? opts))
                               :queryBudget (graphify-query-budget opts)
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
