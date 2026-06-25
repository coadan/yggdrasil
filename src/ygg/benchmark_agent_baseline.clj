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
            [ygg.embedding :as embedding]
            [ygg.embedding-client :as embedding-client]
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
(def default-agent-baseline-compact-result-limit
  7)
(def default-agent-baseline-embed-batch-size
  64)

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
                         :coverage (:coverage prepared)
                         :result-scope (:resultScope prepared)})]
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
  (let [retriever (keyword (or (:retriever opts) :lexical))]
    (cond-> {:project-id (:project-id prepared)
             :retriever retriever
             :embedding-client (:embedding-client opts)
             :budget (long (or (:budget opts) default-agent-baseline-budget))
             :doc-limit (long (or (:doc-limit opts) default-agent-baseline-doc-limit))
             :retrieval-limit (long (or (:retrieval-limit opts) default-agent-baseline-retrieval-limit))
             :snippet-chars (long (or (:snippet-chars opts) context/default-snippet-chars))}
      (nil? (:embedding-client opts)) (dissoc :embedding-client)
      (= 1 (count (:repos prepared)))
      (assoc :repo-id (:repo-id prepared)))))
(defn- agent-baseline-embedding-client
  [opts]
  (embedding-client/configured-query-client
   (keyword (or (:retriever opts) :lexical))
   {:provider (:provider opts)
    :model (:model opts)}))
(defn- agent-baseline-embedding-options
  [prepared opts]
  (cond-> {:batch-size (long (or (:batch-size opts)
                                 default-agent-baseline-embed-batch-size))
           :project-id (:project-id prepared)}
    (= 1 (count (:repos prepared)))
    (assoc :repo-id (:repo-id prepared))))
(defn- embed-search-docs-for-agent-baseline!
  [xtdb prepared opts]
  (when-let [embedding-client (:embedding-client opts)]
    (embedding/embed-search-docs!
     xtdb
     (dissoc embedding-client :close)
     (agent-baseline-embedding-options prepared opts))))
(defn agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))
(defn agent-baseline!
  "Generate, write, and score one deterministic Yggdrasil agent-result artifact."
  [suite case opts]
  (let [prepared (benchmark-prepare/prepare-case! suite case opts)
        embedding-client (agent-baseline-embedding-client opts)
        opts (cond-> opts
               embedding-client
               (assoc :embedding-client embedding-client))
        bench-project (benchmark-agent-packet/agent-project prepared)
        project-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))
        result-path (benchmark-paths/agent-baseline-result-path suite case opts)
        context-path (benchmark-paths/agent-baseline-context-path suite case opts)
        progress-path (benchmark-paths/progress-path suite case opts)
        agent-id (benchmark-paths/agent-baseline-id opts)]
    (try
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
                correction-overlay (benchmark-maintenance/prepare-agent-overlay!
                                    xtdb
                                    case
                                    prepared
                                    opts)
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
                                                                   :correction-overlay correction-overlay))))
                               #(select-keys % [:files :repos :rows :extractors]))
                embedding-summary (benchmark-progress/progress-stage!
                                   suite
                                   case
                                   opts
                                   :embed-search-docs
                                   #(embed-search-docs-for-agent-baseline!
                                     xtdb
                                     prepared
                                     opts)
                                   #(select-keys %
                                                 [:provider
                                                  :model
                                                  :search-docs
                                                  :pending
                                                  :embedded
                                                  :skipped]))
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
                                 :correction-overlay correction-overlay))
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
                                 :result-scope (:resultScope prepared)
                                 :decision-candidates (:decisionCandidates prepared)
                                 :decision-kind (get-in prepared [:decisionGroundTruth :kind])
                                 :compact-result? true
                                 :compact-result-limit default-agent-baseline-compact-result-limit
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
                                      :agentResultPath (fs/canonical-path result-path)
                                      :contextPacketPath (fs/canonical-path context-path)
                                      :agentScorePath (fs/canonical-path score-path)
                                      :progressPath (fs/canonical-path progress-path)}
                          :ygg {:indexSummary index-summary
                                :embeddingSummary embedding-summary
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
            baseline)))
      (finally
        (embedding-client/close-client! embedding-client)))))
