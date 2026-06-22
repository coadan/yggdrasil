(ns ygg.benchmark-agent-baseline
  (:require [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-expectations :as benchmark-expectations]
            [ygg.benchmark-hints :as benchmark-hints]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-maintenance :as benchmark-maintenance]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-preflight :as benchmark-preflight]
            [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.context :as context]
            [ygg.fs :as fs]
            [ygg.project :as project]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]))

(def agent-baseline-schema
  "ygg.benchmark.agent-baseline/v1")

(def default-agent-baseline-budget
  24000)

(def default-agent-baseline-doc-limit
  20)

(def default-agent-baseline-retrieval-limit
  300)

(def default-agent-baseline-suspect-limit
  20)

(def default-index-timeout-ms
  600000)

(defn- index-timeout-ms
  [opts]
  (let [configured (get opts :index-timeout-ms ::default)]
    (cond
      (= ::default configured) default-index-timeout-ms
      (and configured (pos? (long configured))) (long configured)
      :else nil)))
(defn benchmark-index-options
  [opts]
  (cond-> {:index-profile :query}
    (some? (index-timeout-ms opts)) (assoc :index-timeout-ms (index-timeout-ms opts))))
(defn context-ground-truth-ranks
  [prepared packet]
  (let [context-result (benchmark-prediction/context-packet->agent-result
                        packet
                        {:root (:worktreeRoot prepared)
                         :roots (:worktreeRoots prepared)
                         :coverage (:coverage prepared)})]
    {:files (benchmark-score/ground-truth-file-ranks
             (benchmark-score/scoreable-changed-files (:groundTruth prepared))
             (:suspectedFiles context-result))
     :selection (:selection context-result)}))
(defn context-ground-truth-ranks-from-path
  [prepared path]
  (when (and (not (benchmark-util/blankish? path))
             (.isFile (io/file path)))
    (context-ground-truth-ranks prepared (benchmark-io/read-json-file path))))
(defn agent-baseline-context-options
  [prepared opts]
  (cond-> {:project-id (:project-id prepared)
           :retriever (keyword (or (:retriever opts) :lexical))
           :budget (long (or (:budget opts) default-agent-baseline-budget))
           :doc-limit (long (or (:doc-limit opts) default-agent-baseline-doc-limit))
           :retrieval-limit (long (or (:retrieval-limit opts) default-agent-baseline-retrieval-limit))
           :snippet-chars (long (or (:snippet-chars opts) context/default-snippet-chars))}
    (= 1 (count (:repos prepared)))
    (assoc :repo-id (:repo-id prepared))))
(defn agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))
(defn agent-baseline!
  "Generate, write, and score one deterministic Yggdrasil agent-result artifact."
  [suite case opts]
  (let [prepared (benchmark-prepare/prepare-case! suite case opts)
        bench-project (benchmark-agent-packet/agent-project prepared)
        project-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        context-path (benchmark-paths/agent-baseline-context-path suite case opts)
        progress-path (benchmark-paths/progress-path suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)
        map-path (benchmark-maintenance/prepare-agent-map! suite case prepared opts)
        map-overlay (benchmark-maintenance/agent-map-overlay map-path)]
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
        (let [parser-worker (benchmark-agent-packet/parser-worker-profile opts)
              index-summary (benchmark-progress/progress-stage!
                             suite
                             case
                             opts
                             :index-project
                             #(benchmark-agent-packet/with-benchmark-parser-worker
                                opts
                                (fn []
                                  (project/index-project! xtdb
                                                          bench-project
                                                          (assoc (benchmark-index-options opts)
                                                                 :map-overlay map-overlay))))
                             #(select-keys % [:files :repos :rows :extractors]))
              system-summary (benchmark-progress/progress-stage!
                              suite
                              case
                              opts
                              :infer-project
                              #(project/infer-project! xtdb bench-project)
                              #(select-keys % [:systems :candidates :edges]))
              graph-expectations (benchmark-expectations/evaluate-graph-expectations xtdb prepared)
              packet (benchmark-progress/progress-stage!
                      suite
                      case
                      opts
                      :context-packet
                      #(context/context-packet
                        xtdb
                        (get-in prepared [:input :queryText])
                        (assoc (agent-baseline-context-options prepared opts)
                               :map-path map-path
                               :map-overlay map-overlay))
                      (fn [packet]
                        {:docs (count (:docs packet))
                         :entities (count (:entities packet))
                         :edges (count (:edges packet))
                         :warnings (count (:warnings packet))}))
              hints (benchmark-hints/context-packet->agent-hints prepared packet opts)
              hint-diagnostics (not-empty (vec (:diagnostics hints)))
              agent-result (benchmark-progress/progress-stage!
                            suite
                            case
                            opts
                            :agent-result
                            #(benchmark-prediction/context-packet->agent-result
                              packet
                              {:agent-id agent-id
                               :mode "ygg"
                               :case-id (:case-id prepared)
                               :caseFingerprint (:caseFingerprint prepared)
                               :agentInputFingerprint (:agentInputFingerprint prepared)
                               :root (:worktreeRoot prepared)
                               :roots (:worktreeRoots prepared)
                               :coverage (:coverage prepared)
                               :limit (agent-baseline-suspect-limit opts)})
                            (fn [result]
                              {:suspectedFiles (count (:suspectedFiles result))
                               :suspectedSymbols (count (:suspectedSymbols result))}))
              score-path (benchmark-paths/agent-score-path suite case opts result-path)
              benchmark-activity (benchmark-maintenance/record-benchmark-agent-activity!
                                  xtdb
                                  suite
                                  case
                                  prepared
                                  opts
                                  agent-result
                                  result-path
                                  "passed")
              sync-inspect (:syncInspect benchmark-activity)
              maintenance-preflight (benchmark-preflight/maintenance-preflight
                                     {:index-summary index-summary
                                      :system-summary system-summary
                                      :graph-expectations graph-expectations
                                      :expectations (:expectations prepared)
                                      :hints hints
                                      :sync-inspect sync-inspect})
              scored (benchmark-progress/progress-stage!
                      suite
                      case
                      opts
                      :score-agent-result
                      #(cond-> (-> (benchmark-agent-score/score-agent-result prepared agent-result)
                                   (assoc :agentResultPath (fs/canonical-path result-path)
                                          :contextPacketPath (fs/canonical-path context-path)
                                          :parserWorker parser-worker
                                          :contextGroundTruthRanks (context-ground-truth-ranks
                                                                    prepared
                                                                    packet))
                                   (benchmark-preflight/assoc-run-preflight
                                    maintenance-preflight))
                         benchmark-activity
                         (assoc :benchmarkActivity (:activity benchmark-activity))
                         sync-inspect
                         (assoc :syncInspect sync-inspect)
                         hint-diagnostics
                         (assoc :yggHints {:diagnostics hint-diagnostics})
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
                        :agentInputFingerprint (:agentInputFingerprint prepared)
                        :agentId agent-id
                        :mode "ygg"
                        :retriever (name (keyword (or (:retriever opts)
                                                      :lexical)))
                        :parserWorker parser-worker
                        :suspectLimit (agent-baseline-suspect-limit opts)
                        :artifacts {:projectConfig project-path
                                    :mapPath map-path
                                    :agentResultPath (fs/canonical-path result-path)
                                    :contextPacketPath (fs/canonical-path context-path)
                                    :agentScorePath (fs/canonical-path score-path)
                                    :progressPath (fs/canonical-path progress-path)}
                        :ygg {:indexSummary index-summary
                              :systemSummary system-summary
                              :syncInspect sync-inspect}
                        :benchmarkActivity (:activity benchmark-activity)
                        :syncInspect sync-inspect
                        :maintenancePreflight maintenance-preflight
                        :claimReady (benchmark-preflight/claim-ready?
                                     maintenance-preflight)
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
