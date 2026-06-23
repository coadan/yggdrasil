(ns ygg.benchmark-codebase-memory
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

(def default-codebase-memory-command
  "python3 scripts/codebase-memory-baseline.py")

(def default-codebase-memory-bin
  "codebase-memory-mcp")

(defn- agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

(defn- codebase-memory-command
  [opts]
  (str (or (:codebase-memory-command opts) default-codebase-memory-command)))

(defn- codebase-memory-bin
  [opts]
  (str (or (:codebase-memory-bin opts)
           (System/getenv "CODEBASE_MEMORY_MCP_BIN")
           default-codebase-memory-bin)))

(defn- codebase-memory-cache-dir
  [suite case opts]
  (fs/canonical-path
   (or (:codebase-memory-cache-dir opts)
       (benchmark-paths/codebase-memory-cache-dir suite case opts))))

(defn- codebase-memory-request
  [suite case prepared opts agent-id]
  {:schema "ygg.benchmark.codebase-memory-request/v1"
   :caseId (:case-id prepared)
   :caseFingerprint (:caseFingerprint prepared)
   :agentInputFingerprint (:agentInputFingerprint prepared)
   :agentId agent-id
   :mode "codebase-memory"
   :worktreeRoot (:worktreeRoot prepared)
   :input (:input prepared)
   :task {:kind "issue-localization"
          :objective "Rank repo-relative files using Codebase Memory MCP structural and search tools."
          :rules ["Use only the base checkout and issue text in this request."
                  "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]}
   :limit (agent-baseline-suspect-limit opts)
   :codebaseMemory {:binary (codebase-memory-bin opts)
                    :cacheDir (codebase-memory-cache-dir suite case opts)}})

(defn- run-codebase-memory-command!
  [request-path result-path opts]
  (let [command (codebase-memory-command opts)
        cmdline (str command
                     " "
                     (benchmark-agent-packet/shell-quote (fs/canonical-path request-path))
                     " "
                     (benchmark-agent-packet/shell-quote (fs/canonical-path result-path)))
        {:keys [exit out err] :as process} (shell/sh "sh" "-c" cmdline)]
    (when-not (zero? exit)
      (throw (ex-info "Codebase Memory benchmark worker failed."
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
     :source "codebase-memory-result-surface-estimate"}))

(defn- normalize-codebase-memory-result
  [agent-result prepared agent-id]
  (let [result (assoc (benchmark-local-vector/normalize-agent-result-identity agent-result prepared)
                      :agentId agent-id
                      :mode "codebase-memory")]
    (cond-> result
      (not (valid-token-usage? (:tokenUsage result)))
      (assoc :tokenUsage (result-surface-token-usage result)))))

(defn codebase-memory-baseline!
  "Generate, write, and score one Codebase Memory MCP benchmark baseline.

  This is an optional benchmark control. It shells out to a local worker and
  keeps Codebase Memory MCP outside Yggdrasil core."
  [suite case opts]
  (let [prepared (benchmark-prepare/prepare-case! suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)
        request-path (benchmark-paths/codebase-memory-request-path suite case opts)
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        score-path (benchmark-paths/agent-score-path suite case opts result-path)
        progress-path (benchmark-paths/progress-path suite case opts)
        cache-dir (codebase-memory-cache-dir suite case opts)
        request (codebase-memory-request suite case prepared opts agent-id)]
    (benchmark-progress/progress-stage!
     suite
     case
     opts
     :write-codebase-memory-request
     #(benchmark-io/write-json-file! request-path request)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (let [process (benchmark-progress/progress-stage!
                   suite
                   case
                   opts
                   :codebase-memory-worker
                   #(run-codebase-memory-command! request-path result-path opts)
                   #(select-keys % [:exit]))
          agent-result (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :codebase-memory-result
                        #(normalize-codebase-memory-result
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
                          :codebaseMemoryRequestPath (fs/canonical-path request-path))
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
                    :mode "codebase-memory"
                    :retriever "codebase-memory"
                    :suspectLimit (agent-baseline-suspect-limit opts)
                    :artifacts {:codebaseMemoryRequestPath (fs/canonical-path request-path)
                                :codebaseMemoryCacheDir cache-dir
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :progressPath (fs/canonical-path progress-path)}
                    :codebaseMemory {:command (codebase-memory-command opts)
                                     :binary (codebase-memory-bin opts)
                                     :cacheDir cache-dir
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
