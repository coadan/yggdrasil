(ns ygg.cli-query
  (:require [ygg.cli-options :refer [json-output?
                                     option-value
                                     parse-depth
                                     parse-double-option
                                     parse-limit
                                     parse-optional-double
                                     positional-args
                                     project-scope
                                     query-input-options
                                     remove-option]]
            [ygg.context :as context]
            [ygg.embedding-client :as embedding-client]
            [ygg.embedding :as embedding]
            [ygg.filesystem-query :as filesystem-query]
            [ygg.graph :as graph]
            [ygg.hash :as hash]
            [ygg.llm.openai-compatible :as llm]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.progress :as progress]
            [ygg.query :as query]
            [ygg.report :as report]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def ^:dynamic *deps* {})

(def filesystem-handoff-schema
  "ygg.query.filesystem-handoff/v1")

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI query dependency." {:dependency k})))
         args))

(defn- usage [] (call-dep :usage))
(defn- print-json [data] (call-dep :print-json data))
(defn- temporal-options [args] (call-dep :temporal-options args))
(defn- context-packet-options [xtdb args opts] (call-dep :context-packet-options xtdb args opts))

(defn- optional-dep
  [k]
  (get *deps* k))

(defn- query-progress-fn
  [args]
  (when-not (some #{"--no-progress"} args)
    (let [printed-header? (atom false)]
      (fn [event]
        (when-let [line (progress/query-progress-line event)]
          (binding [*out* *err*]
            (when (compare-and-set! printed-header? false true)
              (println "# Query Progress"))
            (println line)
            (flush)))))))

(defn print-query-result
  [result]
  (println (format "%.2f  %s  %s"
                   (:score result)
                   (name (:result-kind result))
                   (or (:label result) (:path result))))
  (println "      " (:path result) (when-let [line (:source-line result)] (str "L" line)))
  (when-let [{:keys [semantic lexical fts grep graph exact]} (:score-components result)]
    (println "       "
             (format "semantic %.2f lexical %.2f fts %.2f grep %.2f graph %.2f exact %.2f"
                     (double (or semantic 0.0))
                     (double (or lexical 0.0))
                     (double (or fts 0.0))
                     (double (or grep 0.0))
                     (double (or graph 0.0))
                     (double (or exact 0.0)))))
  (when-let [reason (:reason result)]
    (println "       " reason)))
(defn- next-action-command
  [action]
  (when-let [command (some-> (:command action) str/trim not-empty)]
    (str "Run " command)))

(defn- print-query-evidence-warning
  [packet]
  (let [evidence (:evidence packet)
        missing (map name (:missing evidence))
        weak (map name (:weak evidence))
        unsupported (map name (:unsupported evidence))
        warning-limit 3
        next-limit 4
        warnings (take warning-limit (:warnings evidence))
        all-next-steps (vec (keep next-action-command (:nextActions evidence)))
        next-steps (take next-limit all-next-steps)
        extra-warnings (max 0 (- (count (:warnings evidence)) warning-limit))
        extra-next (max 0 (- (count all-next-steps) next-limit))]
    (binding [*out* *err*]
      (println "No query results.")
      (println "Evidence" (name (:status evidence)))
      (when (seq missing)
        (println "Missing evidence:" (str/join ", " missing)))
      (when (seq weak)
        (println "Weak evidence:" (str/join ", " weak)))
      (when (seq unsupported)
        (println "Unsupported evidence:" (str/join ", " unsupported)))
      (doseq [warning warnings]
        (println "Warning:" warning))
      (when (pos? extra-warnings)
        (println "Warning:" extra-warnings "more warnings in --json output."))
      (when (seq next-steps)
        (println "Next:" (str/join " | " next-steps))
        (when (pos? extra-next)
          (println "Next:" extra-next "more commands in --json output."))))))
(defn- print-query-no-results
  [xtdb query-text {:keys [project-id repo-id retriever embedding-client temporal args progress-fn]}]
  (if (str/blank? (str project-id))
    (binding [*out* *err*]
      (println "No query results.")
      (println "Use --project to include evidence details."))
    (print-query-evidence-warning
     (context/context-packet xtdb
                             query-text
                             (assoc (context-packet-options xtdb
                                                            args
                                                            {:project-id project-id
                                                             :repo-id repo-id
                                                             :retriever retriever
                                                             :embedding-client embedding-client
                                                             :read-context temporal
                                                             :progress-fn progress-fn})
                                    :output :full)))))

(defn- active-indexing
  []
  (not-empty (select-keys (:active-indexing *deps*)
                          [:schema
                           :op
                           :projectId
                           :lockKey
                           :scheduleId
                           :task
                           :startedAtMs
                           :elapsedMs])))

(defn print-embed-summary
  [{:keys [provider model search-docs pending embedded skipped]}]
  (println "# Embeddings")
  (println "- provider" (name provider))
  (println "- model" model)
  (println "- search-docs" search-docs)
  (println "- pending" pending)
  (println "- embedded" embedded)
  (println "- skipped" skipped))
(defn candidate-system?
  [system]
  (contains? #{:candidate-system :repo-boundary} (:kind system)))
(defn print-candidate-systems
  [systems limit]
  (println "# System Candidates")
  (println "- count" (count systems))
  (doseq [system (take limit systems)]
    (println (str "- "
                  (:label system)
                  " [" (name (:kind system)) "]"
                  " repo " (:repo-id system)
                  " types " (str/join "," (map name (:candidate-types system)))
                  " files " (get-in system [:metrics :file-count] 0)
                  " nodes " (get-in system [:metrics :node-count] 0)
                  (when-let [path (:path-prefix system)]
                    (str " path " path))))))
(defn default-provider
  []
  (embedding-client/default-provider))
(defn- explicit-config-selection?
  [args]
  (option-value args "--config"))

(defn- resolved-project
  [args]
  (try
    (if-let [config-path (option-value args "--config")]
      (let [resolved (project/read-project config-path)
            requested-id (option-value args "--project")]
        (when (and requested-id
                   (not= (str requested-id) (str (:id resolved))))
          (throw (ex-info "--config project id does not match --project."
                          {:config config-path
                           :config-project-id (:id resolved)
                           :project-id requested-id})))
        resolved)
      (:project (registry/resolve-project {:project-id (option-value args "--project")
                                           :cwd (System/getProperty "user.dir")})))
    (catch Exception e
      (when (explicit-config-selection? args)
        (throw e))
      nil)))

(defn project-embedding-options
  [args]
  (:embeddings (resolved-project args)))

(defn embedding-options
  [args]
  (let [project-embeddings (project-embedding-options args)
        provider (keyword (or (option-value args "--provider")
                              (:provider project-embeddings)
                              (name (default-provider))))
        model (or (option-value args "--model")
                  (:model project-embeddings)
                  (embedding-client/default-model provider))]
    (cond-> {:provider provider
             :model model}
      (:request-timeout-ms project-embeddings)
      (assoc :request-timeout-ms (:request-timeout-ms project-embeddings))

      (:max-retries project-embeddings)
      (assoc :max-retries (:max-retries project-embeddings)))))

(defn provider-option
  [args]
  (:provider (embedding-options args)))
(defn default-model
  [provider]
  (embedding-client/default-model provider))
(defn provider-api-key
  [provider]
  (embedding-client/provider-api-key provider))
(defn provider-client
  ([provider model]
   (embedding-client/client provider model))
  ([provider model opts]
   (embedding-client/client provider
                            model
                            (select-keys opts [:request-timeout-ms
                                               :max-retries]))))
(defn missing-key-message
  [provider]
  (embedding-client/missing-key-message provider))
(defn llm-provider-option
  [args]
  (keyword (or (option-value args "--provider") "openrouter")))
(defn llm-model
  [provider args]
  (or (option-value args "--model")
      (llm/default-model provider)))
(defn llm-client
  [provider model args]
  (llm/client {:provider provider
               :model model
               :base-url (option-value args "--base-url")}))
(defn- print-edge-target
  [edge]
  (println "-" (name (:relation edge)) "->" (or (get-in edge [:target :label])
                                                (query/display-id (:target-id edge)))))
(defn- print-edge-source
  [edge]
  (println "-" (name (:relation edge)) "<-" (or (get-in edge [:source :label])
                                                (query/display-id (:source-id edge)))))
(defn print-deps
  [{:keys [node incoming outgoing definitions defined-by]}]
  (if-not node
    (println "Node not found.")
    (do
      (println "# Dependencies:" (:label node))
      (println)
      (println "Outgoing:")
      (if (seq outgoing)
        (doseq [edge outgoing]
          (print-edge-target edge))
        (println "- none"))
      (println)
      (println "Incoming:")
      (if (seq incoming)
        (doseq [edge incoming]
          (print-edge-source edge))
        (println "- none"))
      (when (seq definitions)
        (println)
        (println "Definitions:" (count definitions))
        (doseq [edge (take 20 definitions)]
          (print-edge-target edge))
        (when (< 20 (count definitions))
          (println "-" (- (count definitions) 20) "more definitions")))
      (when (seq defined-by)
        (println)
        (println "Defined by:")
        (doseq [edge defined-by]
          (print-edge-source edge))))))
(defn- print-package-source
  [source]
  (println (str "  - " (:path source)
                (when-let [line (:line source)]
                  (str ":" line))
                (when-let [version-range (:version-range source)]
                  (str " " version-range))
                (when-let [scope (:dependency-scope source)]
                  (str " [" (name scope) "]")))))
(defn- print-package-entry
  [entry]
  (println "-" (:label entry)
           (str "(declared " (count (:declared-by entry))
                ", versions " (count (:resolved-versions entry))
                ", imports " (count (:imported-by entry)) ")")))
(defn print-package-report
  [{:keys [project-id repo-id counts ecosystems packages
           declared-without-import-evidence unresolved-imports version-conflicts
           nextActions]}]
  (println "# Packages")
  (when project-id
    (println "- project" project-id))
  (when repo-id
    (println "- repo" repo-id))
  (println "- packages" (:packages counts))
  (println "- versions" (:versions counts))
  (println "- import evidence" (:imports-package counts))
  (println "- source import candidates" (:source-import-candidates counts))
  (println "- unresolved imports" (:unresolved-imports counts))
  (println "- declared without import evidence"
           (:declared-without-import-evidence counts))
  (println "- version conflicts" (:version-conflicts counts))
  (when (seq ecosystems)
    (println)
    (println "Ecosystems:")
    (doseq [{:keys [ecosystem packages versions imports]} ecosystems]
      (println (str "- " (name ecosystem)
                    ": packages " packages
                    ", versions " versions
                    ", imports " imports))))
  (when (seq version-conflicts)
    (println)
    (println "Version conflicts:")
    (doseq [{:keys [label versions]} version-conflicts]
      (println "-" label (str/join ", " versions))))
  (when (seq declared-without-import-evidence)
    (println)
    (println "Declared without import evidence:")
    (doseq [entry declared-without-import-evidence]
      (print-package-entry entry)
      (doseq [source (take 3 (:declared-by entry))]
        (print-package-source source))))
  (when (seq unresolved-imports)
    (println)
    (println "Unresolved imports:")
    (doseq [{:keys [path line import kind]} unresolved-imports]
      (println (str "- " path
                    (when line (str ":" line))
                    " " import
                    (when kind (str " [" (name kind) "]"))))))
  (when (seq nextActions)
    (println)
    (println "Next:")
    (doseq [{:keys [label command]} nextActions]
      (println "-" label)
      (println " " command)))
  (when (seq packages)
    (println)
    (println "Packages:")
    (doseq [entry packages]
      (print-package-entry entry))))
(defn print-path
  [nodes]
  (if (seq nodes)
    (doseq [[idx node] (map-indexed vector nodes)]
      (println (str idx ".") (:label node)))
    (println "No path found.")))
(defn- default-graph-out
  [mode value]
  (str ".dev/reports/"
       (name mode)
       "-"
       (hash/short-hash [(name mode) value (System/currentTimeMillis)])
       ".html"))
(defn graph-output
  [args mode value]
  (or (option-value args "--out")
      (default-graph-out mode value)))
(defn- default-graph-json-out
  [mode value]
  (str ".dev/reports/"
       (name mode)
       "-"
       (hash/short-hash [(name mode) value (System/currentTimeMillis)])
       ".json"))
(defn graph-json-output
  [args mode value]
  (or (option-value args "--out")
      (default-graph-json-out mode value)))
(defn query-embedding-client
  ([retriever provider model]
   (embedding-client/query-embedding-client retriever provider model))
  ([retriever opts]
   (embedding-client/configured-query-client retriever opts)))
(defn semantic-availability
  [retriever opts]
  (embedding-client/semantic-availability retriever opts))
(defn print-graph-output
  [path data]
  (println "# Graph")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))
(defn print-canonical-output
  [path data]
  (println "# Graph Export")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))
(defn graph-output-value
  [mode graph-args]
  (case mode
    :overview "overview"
    :deps (first (positional-args graph-args))
    :query (str/join " " (positional-args graph-args))
    :systems (option-value graph-args "--project")
    :clusters (option-value graph-args "--project")
    :cluster (str (option-value graph-args "--project") ":" (first (positional-args graph-args)))
    "graph"))
(defn graph-data-for-mode
  [xtdb mode graph-args limit depth]
  (case mode
    :overview
    (let [{:keys [project-id repo-id]} (project-scope graph-args)
          temporal (temporal-options graph-args)]
      (graph/overview-graph xtdb {:limit limit
                                  :project-id project-id
                                  :repo-id repo-id
                                  :read-context temporal
                                  :view-id (option-value graph-args "--view")}))

    :deps
    (let [value (first (positional-args graph-args))]
      (when-not value
        (throw (ex-info "Missing deps graph node query." {:usage (usage)})))
      (let [{:keys [project-id repo-id]} (project-scope graph-args)
            temporal (temporal-options graph-args)]
        (graph/deps-graph xtdb value {:depth depth
                                      :limit limit
                                      :project-id project-id
                                      :repo-id repo-id
                                      :read-context temporal
                                      :view-id (option-value graph-args "--view")})))

    :query
    (let [query-text (str/join " " (positional-args graph-args))
          retriever (keyword (or (option-value graph-args "--retriever") "auto"))
          embedding-opts (embedding-options graph-args)
          embedding-client (query-embedding-client retriever embedding-opts)
          {:keys [project-id repo-id]} (project-scope graph-args)
          temporal (temporal-options graph-args)]
      (when (str/blank? query-text)
        (throw (ex-info "Missing graph query text." {:usage (usage)})))
      (graph/query-graph xtdb query-text {:depth depth
                                          :limit limit
                                          :retriever retriever
                                          :embedding-client embedding-client
                                          :project-id project-id
                                          :repo-id repo-id
                                          :read-context temporal
                                          :view-id (option-value graph-args "--view")}))

    :systems
    (let [project-id (option-value graph-args "--project")]
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for systems graph." {:usage (usage)})))
      (let [min-confidence (parse-double-option graph-args "--min-confidence" 0.55)]
        (graph/system-graph xtdb project-id {:limit limit
                                             :min-confidence min-confidence
                                             :detail (keyword (or (option-value graph-args "--detail")
                                                                  "primary"))
                                             :read-context (temporal-options graph-args)
                                             :view-id (option-value graph-args "--view")})))

    :clusters
    (let [project-id (option-value graph-args "--project")]
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for clusters graph." {:usage (usage)})))
      (graph/system-graph xtdb project-id {:limit limit
                                           :detail (keyword (or (option-value graph-args "--detail")
                                                                "primary"))
                                           :read-context (temporal-options graph-args)
                                           :view-id (option-value graph-args "--view")}))

    :cluster
    (let [cluster-id (first (positional-args graph-args))
          project-id (option-value graph-args "--project")]
      (when-not cluster-id
        (throw (ex-info "Missing cluster id." {:usage (usage)})))
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for cluster graph." {:usage (usage)})))
      (graph/cluster-graph xtdb
                           project-id
                           cluster-id
                           {:limit limit
                            :detail (keyword (or (option-value graph-args "--detail")
                                                 "expanded"))
                            :read-context (temporal-options graph-args)
                            :view-id (option-value graph-args "--view")}))

    (throw (ex-info "Unknown graph mode." {:mode mode
                                           :usage (usage)}))))
(defn- retrieval-options
  [args]
  (let [retriever (keyword (or (option-value args "--retriever") "auto"))
        {:keys [provider model] :as embedding-opts} (embedding-options args)
        semantic-status (semantic-availability retriever embedding-opts)
        embedding-client (query-embedding-client retriever embedding-opts)]
    (when (and (= :auto retriever)
               (= :lexical-fallback (:status semantic-status)))
      (binding [*out* *err*]
        (println (:message semantic-status))))
    {:retriever retriever
     :provider provider
     :model model
     :embedding-client embedding-client
     :semantic-status semantic-status
     :fts-weight (parse-optional-double args "--fts-weight")}))

(defn- fallback-operation
  [operation]
  (not-empty (select-keys operation
                          [:schema
                           :op
                           :projectId
                           :lockKey
                           :scheduleId
                           :task
                           :startedAtMs
                           :elapsedMs])))

(defn- active-fallback-status
  [operation]
  (if (= "embed" (str (:op operation)))
    {:reason :active-embedding
     :message (str "Embedding is active; search is using bounded filesystem evidence. "
                   "Semantic and graph-enriched results will become available automatically.")}
    {:reason :active-indexing
     :message (str "Indexing is active; search is using bounded filesystem evidence. "
                   "Graph-enriched results will become available automatically.")}))

(def ^:private index-unavailable-fallback-status
  {:reason :index-unavailable
   :message (str "The query index is not ready; search is using bounded filesystem evidence. "
                 "Graph-enriched results will become available automatically.")})

(def ^:private cache-warming-fallback-status
  {:reason :cache-warming
   :message (str "Enriched query caches are warming; search is using bounded filesystem evidence. "
                 "Graph-enriched results will become available automatically.")})

(defn- query-index-ready?
  [xtdb project-id repo-id]
  (if-not (store/xtdb-handle? xtdb)
    true
    (try
      (pos? (embedding/search-doc-count xtdb
                                        {:project-id project-id
                                         :repo-id repo-id}))
      (catch Exception _
        false))))

(defn- cwd-fallback-project
  [project-id repo-id]
  {:id (or project-id "workspace")
   :repos [{:id (or repo-id "workspace")
            :root (System/getProperty "user.dir")}]})

(defn- cache-warmup-scope
  [project-id repo-id]
  {:project-id project-id
   :repo-id repo-id})

(defn- active-cache-warmup
  [scope]
  (when-let [active-warmup (optional-dep :active-cache-warmup)]
    (active-warmup scope)))

(defn- cache-warming-required?
  [xtdb scope]
  (and (optional-dep :start-cache-warmup!)
       (not (query/search-corpus-cache-ready? xtdb scope))))

(defn- warm-query-caches!
  [xtdb query-text {:keys [project-id repo-id]}]
  (context/context-packet xtdb
                          query-text
                          {:project-id project-id
                           :repo-id repo-id
                           :retriever :lexical
                           :output :compact
                           :persist-query-run? false}))

(defn- start-cache-warmup
  [xtdb query-text scope]
  (when-let [start-warmup (optional-dep :start-cache-warmup!)]
    (start-warmup scope #(warm-query-caches! xtdb query-text scope))))

(defn- attach-fallback-operation
  [fallback operation]
  (if operation
    (assoc-in fallback [:packet :degradation :operation]
              (fallback-operation operation))
    fallback))

(defn- filesystem-fallback
  [xtdb args query-text project-id repo-id]
  (let [indexing-operation (active-indexing)
        scope (cache-warmup-scope project-id repo-id)
        index-ready? (or indexing-operation
                         (query-index-ready? xtdb project-id repo-id))
        warmup-operation (when (and (not indexing-operation) index-ready?)
                           (active-cache-warmup scope))
        cache-cold? (and (not indexing-operation)
                         index-ready?
                         (not warmup-operation)
                         (cache-warming-required? xtdb scope))
        operation (or indexing-operation warmup-operation)
        status (cond
                 indexing-operation
                 (active-fallback-status indexing-operation)

                 (not index-ready?)
                 index-unavailable-fallback-status

                 (or warmup-operation cache-cold?)
                 cache-warming-fallback-status)]
    (when status
      (let [{:keys [reason message]} status
            input (query-input-options args)
            project (or (resolved-project args)
                        (cwd-fallback-project project-id repo-id))]
        (assoc
         (filesystem-query/search-project
          project
          query-text
          {:repo-id repo-id
           :retriever (keyword (or (option-value args "--retriever") "auto"))
           :query-input input
           :literals (:literals input)
           :symbols (:symbols input)
           :limit (or (parse-limit args) query/default-limit)
           :reason reason
           :message message
           :operation (fallback-operation operation)})
         :start-cache-warmup? cache-cold?
         :cache-warmup-scope scope)))))

(defn- print-filesystem-fallback
  [{:keys [rows packet]}]
  (let [warnings (or (seq (:warnings packet))
                     [(get-in packet [:degradation :message])])]
    (binding [*out* *err*]
      (doseq [warning (distinct (remove nil? warnings))]
        (println "Warning:" warning))))
  (if (seq rows)
    (doseq [{:keys [score path count]} rows]
      (println (format "%.2f  file  %s" score path))
      (println "      " path)
      (println "       " (str "filesystem fixed-string match (" count
                              (if (= 1 count) " match)" " matches)"))))
    (println "No filesystem query results.")))

(defn query-with-node!
  [xtdb args]
  (let [query-text (str/join " " (positional-args args))
        {:keys [project-id repo-id]} (project-scope args)]
    (when (str/blank? query-text)
      (throw (ex-info "Missing query text." {:usage (usage)})))
    (if-let [fallback (filesystem-fallback xtdb args query-text project-id repo-id)]
      (let [fallback (if (:start-cache-warmup? fallback)
                       (attach-fallback-operation
                        fallback
                        (start-cache-warmup xtdb
                                            query-text
                                            (:cache-warmup-scope fallback)))
                       fallback)]
        (if (json-output? args)
          (print-json (:packet fallback))
          (print-filesystem-fallback fallback)))
      (let [{:keys [retriever embedding-client semantic-status fts-weight]}
            (retrieval-options args)
            temporal (temporal-options args)
            progress-fn (or (:progress-fn *deps*) (query-progress-fn args))]
        (if (json-output? args)
          (print-json
           (context/context-packet xtdb
                                   query-text
                                   (context-packet-options xtdb
                                                           args
                                                           {:project-id project-id
                                                            :repo-id repo-id
                                                            :retriever retriever
                                                            :embedding-client embedding-client
                                                            :semantic-status semantic-status
                                                            :fts-weight fts-weight
                                                            :read-context temporal
                                                            :progress-fn progress-fn})))
          (let [results (query/semantic-query xtdb
                                              query-text
                                              {:limit (or (parse-limit args) 10)
                                               :retriever retriever
                                               :embedding-client embedding-client
                                               :semantic-status semantic-status
                                               :fts-weight fts-weight
                                               :project-id project-id
                                               :repo-id repo-id
                                               :read-context temporal
                                               :progress-fn progress-fn})]
            (if (seq results)
              (doseq [result results]
                (print-query-result result))
              (print-query-no-results xtdb
                                      query-text
                                      {:project-id project-id
                                       :repo-id repo-id
                                       :retriever retriever
                                       :embedding-client embedding-client
                                       :semantic-status semantic-status
                                       :fts-weight fts-weight
                                       :temporal temporal
                                       :args args
                                       :progress-fn progress-fn}))))))))

(defn- args-with-project
  [args]
  (if (option-value args "--project")
    args
    (let [{:keys [project-id]} (registry/resolve-project
                                {:config-path (option-value args "--config")
                                 :cwd (System/getProperty "user.dir")})]
      (conj args "--project" project-id))))

(defn active-query-fallback!
  "Run the active-work filesystem query path without opening graph storage.

  The caller must bind `:active-indexing` in `*deps*`; this entrypoint exists so
  the server can preserve query availability before a project node is ready."
  [args]
  (when-not (active-indexing)
    (throw (ex-info "Active query fallback requires an active indexing operation."
                    {})))
  (query-with-node! nil args))

(defn active-query-filesystem-handoff
  "Return registered repository roots for a client-owned active-indexing fallback.

  This path does not open graph storage or start filesystem processes. The caller
  must bind `:active-indexing` in `*deps*`."
  [args]
  (let [operation (active-indexing)]
    (when-not operation
      (throw (ex-info "Active query handoff requires an active indexing operation."
                      {})))
    (let [{:keys [project-id repo-id]} (project-scope args)
          project (or (resolved-project args)
                      (cwd-fallback-project project-id repo-id))
          {:keys [reason message]} (active-fallback-status operation)
          repos (cond->> (:repos project)
                  repo-id (filter #(= (str repo-id) (str (:id %)))))]
      {:schema filesystem-handoff-schema
       :reason reason
       :fallback :filesystem
       :message message
       :projectId (:id project)
       :repos (mapv #(select-keys % [:id :root]) repos)
       :operation operation})))

(defn query!
  [args]
  (let [args (args-with-project args)
        project-id (option-value args "--project")]
    (store/with-node (store/storage-path project-id)
      (fn [xtdb]
        (query-with-node! xtdb args)))))
(defn view!
  [args]
  (let [format (keyword (or (option-value args "--format") "html"))
        view-args (args-with-project (remove-option args "--format"))
        mode (keyword (first view-args))
        graph-args (vec (rest view-args))
        limit (or (parse-limit graph-args)
                  (case mode :query 40 graph/default-node-limit))
        depth (parse-depth graph-args)
        value (graph-output-value mode graph-args)]
    (case format
      :html
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [data (graph-data-for-mode xtdb mode graph-args limit depth)]
            (print-graph-output
             (report/write-graph-viewer! (graph-output graph-args mode value) data)
             data))))

      :json
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [data (graph-data-for-mode xtdb mode graph-args limit depth)]
            (print-canonical-output
             (graph/write-canonical! (graph-json-output graph-args mode value) data)
             data))))

      (throw (ex-info "Unknown view format."
                      {:format format
                       :supported [:html :json]
                       :usage (usage)})))))
(defn report!
  [args]
  (let [config-path (first (positional-args args))
        {:keys [project]} (if (str/blank? (str config-path))
                            (registry/resolve-project
                             {:project-id (option-value args "--project")
                              :cwd (System/getProperty "user.dir")})
                            {:project (project/read-project config-path)
                             :config-path config-path})]
    (store/with-node (store/storage-path (:id project))
      (fn [xtdb]
        (print-json
         (report/bundle! xtdb
                         project
                         {:out (or (option-value args "--out")
                                   report/default-output-dir)
                          :detail (keyword (or (option-value args "--detail")
                                               (name report/default-detail)))
                          :force? (boolean (some #{"--force"} args))}))))))
