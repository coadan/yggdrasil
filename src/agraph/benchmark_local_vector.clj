(ns agraph.benchmark-local-vector
  (:require [agraph.benchmark-agent-packet :as benchmark-agent-packet]
            [agraph.benchmark-agent-score :as benchmark-agent-score]
            [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-prepare :as benchmark-prepare]
            [agraph.benchmark-progress :as benchmark-progress]
            [agraph.fs :as fs]
            [clojure.java.shell :as shell]))

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def agent-baseline-schema
  "agraph.benchmark.agent-baseline/v1")

(def default-agent-baseline-suspect-limit
  20)

(def default-local-vector-model
  "sentence-transformers/all-MiniLM-L6-v2")

(def default-local-vector-command
  "python3 scripts/local-vector-baseline.py")

(defn- agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

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
                     (benchmark-agent-packet/shell-quote (fs/canonical-path request-path))
                     " "
                     (benchmark-agent-packet/shell-quote (fs/canonical-path result-path))
                     " "
                     (benchmark-agent-packet/shell-quote model))
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
(defn normalize-agent-result-identity
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
  (let [prepared (benchmark-prepare/prepare-case! suite case opts)
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
                  #(assoc (benchmark-agent-score/score-agent-result prepared agent-result)
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
