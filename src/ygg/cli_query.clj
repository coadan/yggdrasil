(ns ygg.cli-query
  (:require [ygg.cli-options :refer [json-output? option-value parse-double-option parse-limit positional-args project-scope remove-option]]
            [ygg.context :as context]
            [ygg.embedding.local :as local]
            [ygg.embedding.openai :as openai]
            [ygg.embedding.openrouter :as openrouter]
            [ygg.graph :as graph]
            [ygg.hash :as hash]
            [ygg.llm.openai-compatible :as llm]
            [ygg.project :as project]
            [ygg.query :as query]
            [ygg.report :as report]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI query dependency." {:dependency k})))
         args))

(defn- usage [] (call-dep :usage))
(defn- print-json [data] (call-dep :print-json data))
(defn- default-map-path [args] (call-dep :default-map-path args))
(defn- temporal-options [args] (call-dep :temporal-options args))
(defn- context-packet-options [xtdb args opts] (call-dep :context-packet-options xtdb args opts))
(defn- dispatch [command args] (call-dep :dispatch command args))

(defn print-query-result
  [result]
  (println (format "%.2f  %s  %s"
                   (:score result)
                   (name (:result-kind result))
                   (or (:label result) (:path result))))
  (println "      " (:path result) (when-let [line (:source-line result)] (str "L" line)))
  (when-let [{:keys [semantic lexical grep graph exact]} (:score-components result)]
    (println "       "
             (format "semantic %.2f lexical %.2f grep %.2f graph %.2f exact %.2f"
                     (double (or semantic 0.0))
                     (double (or lexical 0.0))
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
  [xtdb query-text {:keys [project-id repo-id retriever embedding-client temporal args]}]
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
                                                             :read-context temporal})
                                    :output :full)))))
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
  :local)
(defn provider-option
  [args]
  (keyword (or (option-value args "--provider")
               (name (default-provider)))))
(defn default-model
  [provider]
  (case provider
    :local (local/configured-model)
    :openrouter openrouter/default-model
    :openai openai/default-model
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:local :openrouter :openai]}))))
(defn provider-api-key
  [provider]
  (case provider
    :local true
    :openrouter (openrouter/api-key)
    :openai (openai/api-key)
    nil))
(defn provider-client
  [provider model]
  (case provider
    :local (local/client {:model model})
    :openrouter (openrouter/client {:model model})
    :openai (openai/client {:model model})
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:local :openrouter :openai]}))))
(defn missing-key-message
  [provider]
  (case provider
    :local (str "Local embeddings require sentence-transformers. "
                "Run `ygg embed setup`, or set YGG_LOCAL_EMBEDDING_COMMAND "
                "to a custom worker command.")
    :openrouter "Missing OpenRouter API key. Set YGG_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
    :openai "Missing OpenAI API key. Set YGG_OPENAI_API_KEY or OPENAI_API_KEY."
    "Missing embedding provider API key."))
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
  [retriever provider model]
  (cond
    (= :lexical retriever) nil
    (provider-api-key provider) (provider-client provider model)
    (= :auto retriever) nil
    :else (throw (ex-info (missing-key-message provider)
                          {:retriever retriever
                           :provider provider}))))
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
          provider (provider-option graph-args)
          model (or (option-value graph-args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
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
                                             :map-path (default-map-path graph-args)
                                             :read-context (temporal-options graph-args)
                                             :view-id (option-value graph-args "--view")})))

    :clusters
    (let [project-id (option-value graph-args "--project")]
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for clusters graph." {:usage (usage)})))
      (graph/system-graph xtdb project-id {:limit limit
                                           :detail (keyword (or (option-value graph-args "--detail")
                                                                "primary"))
                                           :map-path (default-map-path graph-args)
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
                            :map-path (default-map-path graph-args)
                            :read-context (temporal-options graph-args)
                            :view-id (option-value graph-args "--view")}))

    (throw (ex-info "Unknown graph mode." {:mode mode
                                           :usage (usage)}))))
(defn- retrieval-options
  [args]
  (let [retriever (keyword (or (option-value args "--retriever") "auto"))
        provider (provider-option args)
        model (or (option-value args "--model") (default-model provider))
        embedding-client (query-embedding-client retriever provider model)]
    (when (and (= :auto retriever) (nil? embedding-client))
      (binding [*out* *err*]
        (println (str (missing-key-message provider) " Using lexical retrieval."))))
    {:retriever retriever
     :provider provider
     :model model
     :embedding-client embedding-client}))
(defn query-with-node!
  [xtdb args]
  (let [query-text (str/join " " (positional-args args))
        {:keys [retriever embedding-client]} (retrieval-options args)
        {:keys [project-id repo-id]} (project-scope args)
        temporal (temporal-options args)]
    (when (str/blank? query-text)
      (throw (ex-info "Missing query text." {:usage (usage)})))
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
                                                        :read-context temporal})))
      (let [results (query/semantic-query xtdb
                                          query-text
                                          {:limit (or (parse-limit args) 10)
                                           :retriever retriever
                                           :embedding-client embedding-client
                                           :project-id project-id
                                           :repo-id repo-id
                                           :read-context temporal})]
        (if (seq results)
          (doseq [result results]
            (print-query-result result))
          (print-query-no-results xtdb
                                  query-text
                                  {:project-id project-id
                                   :repo-id repo-id
                                   :retriever retriever
                                   :embedding-client embedding-client
                                   :temporal temporal
                                   :args args}))))))

(defn query!
  [args]
  (store/with-node (store/storage-path)
    (fn [xtdb]
      (query-with-node! xtdb args))))
(defn view!
  [args]
  (let [format (keyword (or (option-value args "--format") "html"))
        view-args (remove-option args "--format")]
    (case format
      :html (dispatch "graph" view-args)
      :json (dispatch "graph" (into ["export"] view-args))
      (throw (ex-info "Unknown view format."
                      {:format format
                       :supported [:html :json]
                       :usage (usage)})))))
(defn report!
  [args]
  (let [config-path (first (positional-args args))]
    (when (str/blank? (str config-path))
      (throw (ex-info "Missing project config." {:usage (usage)})))
    (let [project (project/read-project config-path)
          map-path (default-map-path args)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-json
           (report/bundle! xtdb
                           project
                           {:out (or (option-value args "--out")
                                     report/default-output-dir)
                            :map-path map-path
                            :detail (keyword (or (option-value args "--detail")
                                                 (name report/default-detail)))
                            :force? (boolean (some #{"--force"} args))})))))))
