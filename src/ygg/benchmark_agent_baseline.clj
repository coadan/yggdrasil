(ns ygg.benchmark-agent-baseline
  (:require [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-expectations :as benchmark-expectations]
            [ygg.benchmark-hints :as benchmark-hints]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-readiness :as benchmark-readiness]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-preflight :as benchmark-preflight]
            [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-results :as benchmark-results]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.context :as context]
            [ygg.embedding :as embedding]
            [ygg.embedding-client :as embedding-client]
            [ygg.fs :as fs]
            [ygg.hash :as hash]
            [ygg.project :as project]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]))

(def agent-baseline-schema
  "ygg.benchmark.agent-baseline/v1")

(def agent-baseline-context-schema
  "ygg.benchmark.agent-baseline-context/v1")

(def default-agent-baseline-budget
  24000)

(def default-agent-baseline-doc-limit
  20)

(def default-agent-baseline-retrieval-limit
  300)

(def default-agent-baseline-provider-embed-limit
  default-agent-baseline-retrieval-limit)

(def default-agent-baseline-suspect-limit
  20)
(def default-agent-baseline-compact-result-limit
  10)
(def default-agent-baseline-embed-batch-size
  64)
(def default-agent-baseline-embedding-input-max-chars
  context/default-snippet-chars)
(def default-agent-baseline-embedding-request-timeout-ms
  30000)
(def default-agent-baseline-embedding-max-retries
  1)
(def default-agent-baseline-retriever
  :auto)

(def default-index-timeout-ms
  600000)

(def default-benchmark-extract-parallelism
  4)

(defn- index-timeout-ms
  [opts]
  (let [configured (get opts :index-timeout-ms ::default)]
    (cond
      (= ::default configured) default-index-timeout-ms
      (and configured (pos? (long configured))) (long configured)
      :else nil)))

(defn- extract-parallelism
  [opts]
  (max 1 (long (or (:extract-parallelism opts)
                   default-benchmark-extract-parallelism))))

(defn benchmark-index-options
  [opts]
  (cond-> {:index-profile :query
           :extract-parallelism (extract-parallelism opts)}
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
  (let [retriever (keyword (or (:retriever opts)
                               default-agent-baseline-retriever))]
    (cond-> {:project-id (:project-id prepared)
             :retriever retriever
             :embedding-client (:embedding-client opts)
             :budget (long (or (:budget opts) default-agent-baseline-budget))
             :doc-limit (long (or (:doc-limit opts) default-agent-baseline-doc-limit))
             :retrieval-limit (long (or (:retrieval-limit opts) default-agent-baseline-retrieval-limit))
             :snippet-chars (long (or (:snippet-chars opts) context/default-snippet-chars))}
      (nil? (:embedding-client opts)) (dissoc :embedding-client)
      (:fusion-strategy opts) (assoc :fusion-strategy (:fusion-strategy opts))
      (:sqlite-fts? opts) (assoc :sqlite-fts? (:sqlite-fts? opts))
      (:diversity-rerank-limit opts) (assoc :diversity-rerank-limit
                                            (:diversity-rerank-limit opts))
      (:fts-candidate-limit opts) (assoc :fts-candidate-limit
                                         (:fts-candidate-limit opts))
      (:fts-weight opts) (assoc :fts-weight (:fts-weight opts))
      (= 1 (count (:repos prepared)))
      (assoc :repo-id (:repo-id prepared)))))
(defn- agent-baseline-embedding-client
  [opts]
  (embedding-client/configured-query-client
   (keyword (or (:retriever opts)
                default-agent-baseline-retriever))
   {:provider (:provider opts)
    :model (:model opts)
    :request-timeout-ms (long (or (:embedding-request-timeout-ms opts)
                                  default-agent-baseline-embedding-request-timeout-ms))
    :max-retries (long (or (:embedding-max-retries opts)
                           default-agent-baseline-embedding-max-retries))}))
(defn- agent-baseline-embedding-options
  [prepared opts]
  (cond-> {:batch-size (long (or (:batch-size opts)
                                 default-agent-baseline-embed-batch-size))
           :input-max-chars (long (or (:embedding-input-max-chars opts)
                                      default-agent-baseline-embedding-input-max-chars))
           :project-id (:project-id prepared)}
    (:embedding-cache opts)
    (assoc :embedding-cache (:embedding-cache opts))

    (:embedding-role opts)
    (assoc :embedding-role (:embedding-role opts))

    (:embedding-provider-target-ids opts)
    (assoc :provider-target-ids (:embedding-provider-target-ids opts))

    (contains? opts :embedding-provider-limit)
    (assoc :max-provider-docs (:embedding-provider-limit opts))

    (= 1 (count (:repos prepared)))
    (assoc :repo-id (:repo-id prepared))))
(defn- agent-baseline-provider-embed-limit
  [opts]
  (long (or (:embedding-provider-limit opts)
            default-agent-baseline-provider-embed-limit)))
(defn- provider-embedding-targets
  [xtdb prepared opts]
  (let [limit (agent-baseline-provider-embed-limit opts)
        query-text (get-in prepared [:input :queryText])]
    (if (pos? limit)
      (let [report (query/search-report
                    xtdb
                    query-text
                    (cond-> {:limit limit
                             :retriever :lexical
                             :project-id (:project-id prepared)
                             :read-context (:read-context opts)
                             :fusion-strategy (:fusion-strategy opts)
                             :sqlite-fts? (:sqlite-fts? opts)
                             :diversity-rerank-limit (:diversity-rerank-limit opts)
                             :fts-candidate-limit (:fts-candidate-limit opts)
                             :fts-weight (:fts-weight opts)}
                      (= 1 (count (:repos prepared)))
                      (assoc :repo-id (:repo-id prepared))))
            target-ids (->> (:results report)
                            (keep :target-id)
                            distinct
                            (take limit)
                            vec)]
        {:target-ids target-ids
         :count (count target-ids)
         :limit limit
         :search {:retriever-effective (:retriever-effective report)
                  :instrumentation (select-keys
                                    (:instrumentation report)
                                    [:search-docs
                                     :durable-search-docs
                                     :lexical-positive
                                     :grep-positive
                                     :fts-positive
                                     :candidate-count
                                     :returned-count
                                     :search-total-ms])}})
      {:target-ids []
       :count 0
       :limit limit
       :search {:retriever-effective :none}})))
(defn- embed-search-docs-for-agent-baseline!
  [xtdb suite case prepared opts]
  (when-let [embedding-client (:embedding-client opts)]
    (embedding/embed-search-docs!
     xtdb
     (dissoc embedding-client :close)
     (assoc (agent-baseline-embedding-options prepared opts)
            :on-progress
            (fn [summary]
              (benchmark-progress/progress-update!
               suite
               case
               opts
               :embed-search-docs
               (select-keys summary
                            [:provider
                             :model
                             :search-docs
                             :pending
                             :provider-pending
                             :provider-skipped
                             :embedded
                             :cache-hits
                             :skipped
                             :batch
                             :batches
                             :batch-size
                             :batch-embedded])))))))
(defn agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

(defn- baseline-retriever-name
  [opts]
  (name (keyword (or (:retriever opts)
                     default-agent-baseline-retriever))))

(defn- json-stable-value
  [value]
  (cond
    (keyword? value)
    (name value)

    (map? value)
    (into {}
          (map (fn [[k v]]
                 [k (json-stable-value v)]))
          value)

    (sequential? value)
    (mapv json-stable-value value)

    (set? value)
    (->> value
         (map json-stable-value)
         sort
         vec)

    :else
    value))

(defn- embedding-client-summary
  [embedding-client]
  (some-> embedding-client
          (select-keys [:provider :model])
          json-stable-value))

(defn- clojure-source-file?
  [file]
  (and (.isFile file)
       (.endsWith (.getName file) ".clj")))

(defn- implementation-source-entries
  []
  (let [root (io/file "src" "ygg")]
    (if (.isDirectory root)
      (->> (file-seq root)
           (filter clojure-source-file?)
           (map (fn [file]
                  [(-> (.toPath root)
                       (.relativize (.toPath file))
                       str)
                   (slurp file)]))
           (sort-by first)
           vec)
      [])))

(defn- compute-context-implementation-fingerprint
  []
  (str "sha256:"
       (hash/sha256-hex
        (json/write-json-str
         {:sources (implementation-source-entries)}))))

(def ^:private context-implementation-fingerprint-cache
  (delay (compute-context-implementation-fingerprint)))

(defn- context-implementation-fingerprint
  []
  @context-implementation-fingerprint-cache)

(defn- baseline-context-cache-key
  [prepared opts embedding-client]
  {:parserWorker (benchmark-agent-packet/parser-worker-profile opts)
   :contextImplementationFingerprint (context-implementation-fingerprint)
   :contextOptions (-> (agent-baseline-context-options
                        prepared
                        (cond-> opts
                          embedding-client
                          (assoc :embedding-client embedding-client)))
                       (dissoc :embedding-client :correction-overlay)
                       json-stable-value)
   :embeddingClient (embedding-client-summary embedding-client)
   :embeddingOptions (-> (agent-baseline-embedding-options prepared opts)
                         (dissoc :embedding-cache)
                         json-stable-value)
   :embeddingProviderLimit (agent-baseline-provider-embed-limit opts)
   :indexOptions (json-stable-value (benchmark-index-options opts))})

(defn- read-json-artifact
  [path]
  (when (and path (.isFile (io/file path)))
    (try
      (benchmark-io/read-json-file path)
      (catch Exception _
        nil))))

(defn- readable-artifact-path?
  [path]
  (and (not (benchmark-util/blankish? path))
       (.isFile (io/file path))))

(defn- compatible-baseline-context?
  [suite case prepared opts embedding-client manifest]
  (let [artifacts (:artifacts manifest)
        expected-context-path (fs/canonical-path
                               (benchmark-paths/agent-baseline-context-path
                                suite
                                case
                                opts))]
    (and (= agent-baseline-context-schema (:schema manifest))
         (= "ygg" (str (:mode manifest)))
         (= (benchmark-paths/agent-baseline-id opts) (str (:agentId manifest)))
         (= (:case-id prepared) (:case-id manifest))
         (= (:caseFingerprint prepared) (:caseFingerprint manifest))
         (= (:agentInputFingerprint prepared)
            (:agentInputFingerprint manifest))
         (= (benchmark-agent-packet/parser-worker-profile opts)
            (:parserWorker manifest))
         (= (baseline-context-cache-key prepared opts embedding-client)
            (:contextKey manifest))
         (= expected-context-path (:contextPacketPath artifacts))
         (readable-artifact-path? (:contextPacketPath artifacts)))))

(defn- reusable-baseline-context
  [suite case prepared opts embedding-client]
  (let [manifest-path (benchmark-paths/agent-baseline-context-manifest-path
                       suite
                       case
                       opts)]
    (when-let [manifest (read-json-artifact manifest-path)]
      (when (compatible-baseline-context? suite
                                          case
                                          prepared
                                          opts
                                          embedding-client
                                          manifest)
        (let [context-path (get-in manifest [:artifacts :contextPacketPath])]
          {:packet (benchmark-io/read-json-file context-path)
           :summary (:ygg manifest)
           :graph-expectations (:graphExpectations manifest)
           :benchmark-activity (:benchmarkActivity manifest)
           :sync-inspect (or (:syncInspect manifest)
                             (get-in manifest [:ygg :syncInspect]))
           :manifest-path (fs/canonical-path manifest-path)
           :context-path context-path
           :prepared-at (:preparedAt manifest)})))))

(defn- reuse-baseline-context?
  [opts]
  (or (:skip-existing? opts)
      (:reuse-context? opts)))

(defn- write-baseline-context-manifest!
  [suite case prepared opts embedding-client artifacts summary graph-expectations
   benchmark-activity sync-inspect benchmark-preflight]
  (let [manifest-path (benchmark-paths/agent-baseline-context-manifest-path
                       suite
                       case
                       opts)
        manifest {:schema agent-baseline-context-schema
                  :suite-id (:suite-id prepared)
                  :case-id (:case-id prepared)
                  :repo-id (:repo-id prepared)
                  :repoIds (:repoIds prepared)
                  :project-id (:project-id prepared)
                  :caseFingerprint (:caseFingerprint prepared)
                  :agentInputFingerprint (:agentInputFingerprint prepared)
                  :agentId (benchmark-paths/agent-baseline-id opts)
                  :mode "ygg"
                  :retriever (baseline-retriever-name opts)
                  :parserWorker (benchmark-agent-packet/parser-worker-profile opts)
                  :contextKey (baseline-context-cache-key prepared
                                                          opts
                                                          embedding-client)
                  :preparedAt (benchmark-progress/now-string)
                  :artifacts artifacts
                  :ygg summary
                  :benchmarkActivity benchmark-activity
                  :syncInspect sync-inspect
                  :benchmarkPreflight benchmark-preflight
                  :graphExpectations graph-expectations}]
    (benchmark-io/write-json-file! manifest-path manifest)))

(defn- baseline-agent-result
  [suite case prepared opts packet agent-id]
  (benchmark-progress/progress-stage!
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
      :suspectedSymbols (count (:suspectedSymbols result))})))

(defn- score-and-write-baseline!
  [suite case prepared opts embedding-client paths packet agent-result summary
   graph-expectations benchmark-activity sync-inspect context-reuse]
  (let [{:keys [project-path result-path context-path score-path progress-path]}
        paths
        parser-worker (benchmark-agent-packet/parser-worker-profile opts)
        agent-id (benchmark-paths/agent-baseline-id opts)
        hints (benchmark-hints/context-packet->agent-hints prepared packet opts)
        hint-diagnostics (not-empty (vec (:diagnostics hints)))
        benchmark-preflight (benchmark-preflight/benchmark-preflight
                             {:index-summary (:indexSummary summary)
                              :system-summary (:systemSummary summary)
                              :graph-expectations graph-expectations
                              :expectations (:expectations prepared)
                              :hints hints
                              :sync-inspect sync-inspect})
        scored (benchmark-progress/progress-stage!
                suite
                case
                opts
                :score-agent-result
                #(cond-> (-> (benchmark-agent-score/score-agent-result prepared
                                                                       agent-result)
                             (assoc :agentResultPath (fs/canonical-path result-path)
                                    :contextPacketPath (fs/canonical-path context-path)
                                    :parserWorker parser-worker
                                    :contextGroundTruthRanks (context-ground-truth-ranks
                                                              prepared
                                                              packet))
                             (benchmark-preflight/assoc-run-preflight
                              benchmark-preflight))
                   benchmark-activity
                   (assoc :benchmarkActivity benchmark-activity)
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
        ygg-summary (cond-> summary
                      context-reuse
                      (assoc :contextReuse context-reuse))
        artifacts (cond-> {:projectConfig project-path
                           :agentResultPath (fs/canonical-path result-path)
                           :contextPacketPath (fs/canonical-path context-path)
                           :contextManifestPath (fs/canonical-path
                                                 (benchmark-paths/agent-baseline-context-manifest-path
                                                  suite
                                                  case
                                                  opts))
                           :agentScorePath (fs/canonical-path score-path)
                           :progressPath (fs/canonical-path progress-path)}
                    context-reuse
                    (assoc :reusedContextManifestPath (:manifestPath context-reuse)))
        baseline {:schema agent-baseline-schema
                  :suite-id (:suite-id prepared)
                  :case-id (:case-id prepared)
                  :repo-id (:repo-id prepared)
                  :repoIds (:repoIds prepared)
                  :project-id (:project-id prepared)
                  :caseFingerprint (:caseFingerprint prepared)
                  :agentInputFingerprint (:agentInputFingerprint prepared)
                  :agentId agent-id
                  :mode "ygg"
                  :retriever (baseline-retriever-name opts)
                  :fusionStrategy (some-> (:fusion-strategy opts) name)
                  :sqliteFts (boolean (:sqlite-fts? opts))
                  :diversityRerankLimit (:diversity-rerank-limit opts)
                  :ftsCandidateLimit (:fts-candidate-limit opts)
                  :ftsWeight (:fts-weight opts)
                  :parserWorker parser-worker
                  :suspectLimit (agent-baseline-suspect-limit opts)
                  :artifacts artifacts
                  :ygg ygg-summary
                  :benchmarkActivity benchmark-activity
                  :syncInspect sync-inspect
                  :benchmarkPreflight benchmark-preflight
                  :claimReady (benchmark-preflight/claim-ready?
                               benchmark-preflight)
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
       (write-baseline-context-manifest! suite
                                         case
                                         prepared
                                         opts
                                         embedding-client
                                         (select-keys artifacts
                                                      [:projectConfig
                                                       :contextPacketPath
                                                       :agentResultPath
                                                       :agentScorePath
                                                       :progressPath])
                                         summary
                                         graph-expectations
                                         benchmark-activity
                                         sync-inspect
                                         benchmark-preflight)
       {:contextPath context-path
        :agentResultPath result-path
        :agentScorePath score-path
        :contextManifestPath (:contextManifestPath artifacts)})
     (fn [paths]
       {:contextPacketPath (fs/canonical-path (:contextPath paths))
        :agentResultPath (fs/canonical-path (:agentResultPath paths))
        :agentScorePath (fs/canonical-path (:agentScorePath paths))
        :contextManifestPath (fs/canonical-path (:contextManifestPath paths))}))
    (assoc baseline
           :stageProfile
           (select-keys (benchmark-results/progress-summary suite case opts)
                        [:status
                         :elapsedMs
                         :warmElapsedMs
                         :agentReadyElapsedMs
                         :amortizedSetupElapsedMs
                         :graphSetupElapsedMs
                         :caseSetupElapsedMs
                         :agentPreparationElapsedMs
                         :embeddingElapsedMs
                         :scoringElapsedMs
                         :stageClassElapsedMs
                         :stageElapsedMs
                         :stageTiming]))))
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
        score-path (benchmark-paths/agent-score-path suite case opts result-path)
        progress-path (benchmark-paths/progress-path suite case opts)
        paths {:project-path project-path
               :result-path result-path
               :context-path context-path
               :score-path score-path
               :progress-path progress-path}
        agent-id (benchmark-paths/agent-baseline-id opts)]
    (try
      (benchmark-progress/reset-progress! suite case opts)
      (benchmark-progress/progress-stage!
       suite
       case
       opts
       :write-agent-project
       #(benchmark-io/write-edn-file! project-path bench-project)
       (fn [path]
         {:path (fs/canonical-path path)}))
      (if-let [reused (when (reuse-baseline-context? opts)
                        (reusable-baseline-context suite
                                                   case
                                                   prepared
                                                   opts
                                                   embedding-client))]
        (let [context-reuse (benchmark-progress/progress-stage!
                             suite
                             case
                             opts
                             :reuse-agent-baseline-context
                             (constantly {:status "reused"
                                          :manifestPath (:manifest-path reused)
                                          :contextPacketPath (:context-path reused)
                                          :preparedAt (:prepared-at reused)})
                             #(select-keys %
                                           [:status
                                            :manifestPath
                                            :contextPacketPath
                                            :preparedAt]))
              summary (:summary reused)]
          (score-and-write-baseline! suite
                                     case
                                     prepared
                                     opts
                                     embedding-client
                                     paths
                                     (:packet reused)
                                     (baseline-agent-result suite
                                                            case
                                                            prepared
                                                            opts
                                                            (:packet reused)
                                                            agent-id)
                                     summary
                                     (:graph-expectations reused)
                                     (:benchmark-activity reused)
                                     (:sync-inspect reused)
                                     context-reuse))
        (store/with-node (benchmark-paths/xtdb-dir suite case opts)
          (fn [xtdb]
            (let [correction-overlay (benchmark-readiness/prepare-agent-overlay!
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
                  embedding-targets (if embedding-client
                                      (benchmark-progress/progress-stage!
                                       suite
                                       case
                                       opts
                                       :embedding-provider-targets
                                       #(provider-embedding-targets xtdb prepared opts)
                                       #(select-keys %
                                                     [:count
                                                      :limit
                                                      :search]))
                                      {:target-ids []
                                       :count 0
                                       :limit 0
                                       :skipped true})
                  embedding-summary (benchmark-progress/progress-stage!
                                     suite
                                     case
                                     opts
                                     :embed-search-docs
                                     #(embed-search-docs-for-agent-baseline!
                                       xtdb
                                       suite
                                       case
                                       prepared
                                       (assoc opts
                                              :embedding-provider-target-ids
                                              (:target-ids embedding-targets)))
                                     #(select-keys %
                                                   [:provider
                                                    :model
                                                    :search-docs
                                                    :pending
                                                    :provider-pending
                                                    :provider-skipped
                                                    :embedded
                                                    :cache-hits
                                                    :skipped]))
                  system-summary (benchmark-progress/progress-stage!
                                  suite
                                  case
                                  opts
                                  :infer-project
                                  #(project/infer-project-after-index! xtdb
                                                                       bench-project
                                                                       index-summary)
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
                          context/context-progress-summary)
                  agent-result (baseline-agent-result suite
                                                      case
                                                      prepared
                                                      opts
                                                      packet
                                                      agent-id)
                  benchmark-activity (benchmark-readiness/record-benchmark-agent-activity!
                                      xtdb
                                      suite
                                      case
                                      prepared
                                      opts
                                      agent-result
                                      result-path
                                      "passed")
                  sync-inspect (:syncInspect benchmark-activity)
                  summary {:indexSummary index-summary
                           :embeddingTargets (dissoc embedding-targets :target-ids)
                           :embeddingSummary embedding-summary
                           :systemSummary system-summary
                           :syncInspect sync-inspect}]
              (score-and-write-baseline! suite
                                         case
                                         prepared
                                         opts
                                         embedding-client
                                         paths
                                         packet
                                         agent-result
                                         summary
                                         graph-expectations
                                         (:activity benchmark-activity)
                                         sync-inspect
                                         nil)))))
      (finally
        (embedding-client/close-client! embedding-client)))))
