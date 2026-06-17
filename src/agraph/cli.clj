(ns agraph.cli
  "Command line interface."
  (:require [agraph.context :as context]
            [agraph.cursor :as cursor]
            [agraph.embedding :as embedding]
            [agraph.embedding.openai :as openai]
            [agraph.embedding.openrouter :as openrouter]
            [agraph.graph :as graph]
            [agraph.hash :as hash]
            [agraph.index :as index]
            [agraph.llm.openai-compatible :as llm]
            [agraph.map :as graph-map]
            [agraph.metadata :as metadata]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.system :as system]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.util.logging LogManager]))

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- parse-limit
  [args]
  (let [idx (.indexOf args "--limit")]
    (when-not (neg? idx)
      (Long/parseLong (nth args (inc idx))))))

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn- parse-long-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Long/parseLong value)
    default))

(defn- parse-optional-long
  [args flag]
  (some-> (option-value args flag) Long/parseLong))

(defn- parse-depth
  [args]
  (parse-long-option args "--depth" graph/default-depth))

(defn- parse-double-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Double/parseDouble value)
    default))

(def value-options
  #{"--limit" "--retriever" "--model" "--batch-size" "--provider" "--depth" "--out"
    "--project" "--repo" "--min-confidence" "--map" "--reason" "--budget"
    "--entity-limit" "--edge-limit" "--doc-limit" "--snippet-chars" "--role"
    "--heading" "--start-line" "--end-line" "--valid-at" "--known-at"
    "--snapshot-token" "--type" "--source" "--confidence" "--view" "--query"
    "--relation" "--base-url" "--detail"})

(def boolean-options
  #{"--dry-run" "--systems" "--no-map" "--json" "--index" "--infer"})

(defn- positional-args
  [args]
  (loop [remaining args
         out []]
    (if-let [arg (first remaining)]
      (if (contains? value-options arg)
        (recur (nnext remaining) out)
        (if (contains? boolean-options arg)
          (recur (next remaining) out)
          (recur (next remaining) (conj out arg))))
      out)))

(defn- dry-run?
  [args]
  (some #{"--dry-run"} args))

(defn- system-path?
  [args]
  (some #{"--systems"} args))

(defn- project-scope
  [args]
  {:project-id (option-value args "--project")
   :repo-id (option-value args "--repo")})

(defn- temporal-options
  [args]
  (cond-> {}
    (option-value args "--valid-at") (assoc :valid-at (option-value args "--valid-at"))
    (option-value args "--known-at") (assoc :known-at (option-value args "--known-at"))
    (option-value args "--snapshot-token") (assoc :snapshot-token
                                                  (option-value args "--snapshot-token"))))

(defn- default-map-path
  [args]
  (cond
    (some #{"--no-map"} args) nil
    (option-value args "--map") (option-value args "--map")
    (graph-map/file-exists? graph-map/default-path) graph-map/default-path
    :else nil))

(defn- print-query-result
  [result]
  (println (format "%.2f  %s  %s"
                   (:score result)
                   (name (:result-kind result))
                   (or (:label result) (:path result))))
  (println "      " (:path result) (when-let [line (:source-line result)] (str "L" line)))
  (when-let [{:keys [semantic lexical graph exact]} (:score-components result)]
    (println "       "
             (format "semantic %.2f lexical %.2f graph %.2f exact %.2f"
                     (double semantic)
                     (double lexical)
                     (double graph)
                     (double exact))))
  (when-let [reason (:reason result)]
    (println "       " reason)))

(defn- print-embed-summary
  [{:keys [provider model search-docs pending embedded skipped]}]
  (println "# Embeddings")
  (println "- provider" (name provider))
  (println "- model" model)
  (println "- search-docs" search-docs)
  (println "- pending" pending)
  (println "- embedded" embedded)
  (println "- skipped" skipped))

(defn- print-project-inspect
  [{:keys [id repos path] project-name :name}]
  (println "# Project")
  (println "- id" id)
  (println "- name" project-name)
  (when path
    (println "- config" path))
  (println)
  (println "## Repos")
  (doseq [{:keys [id root role]} repos]
    (println "-" id (clojure.core/name role) root)))

(defn- print-project-index-summary
  [{:keys [project-id status repos]}]
  (println "# Project Index")
  (println "- project" project-id)
  (println "- status" (name status))
  (doseq [{:keys [repo-id status stats]} repos]
    (println "-"
             repo-id
             (name status)
             (:files-scanned stats)
             "scanned,"
             (:files-indexed stats)
             "indexed,"
             (:files-skipped stats)
             "skipped")))

(defn- print-project-add-repo-summary
  [{:keys [project repo index-summary system-summary next]}]
  (println "# Project Repo Added")
  (println "- project" (:id project))
  (println "- repo" (:id repo))
  (println "- root" (:root repo))
  (println "- role" (name (:role repo)))
  (when index-summary
    (println "- indexed" (:files-indexed (:stats index-summary)) "files"))
  (when system-summary
    (println "- inferred" (:system-nodes system-summary) "system nodes"))
  (when (seq next)
    (println)
    (println "## Next")
    (doseq [command next]
      (println "-" command))))

(defn- print-system-summary
  [{:keys [project-id status system-evidence system-nodes system-edges search-docs]}]
  (println "# System Graph")
  (println "- project" project-id)
  (println "- status" (name status))
  (println "- evidence" system-evidence)
  (println "- nodes" system-nodes)
  (println "- edges" system-edges)
  (println "- search-docs" search-docs))

(defn- print-map-summary
  [path {:keys [schema project systems reject edges]}]
  (println "# Graph Map")
  (println "- schema" schema)
  (println "- project" project)
  (println "- systems" (count systems))
  (println "- rejects" (count reject))
  (println "- edges" (count edges))
  (println "- output" path))

(defn- print-map-system
  [system]
  (if-not system
    (println "System not found.")
    (do
      (println "# Map System")
      (doseq [k [:id :label :kind :status :provenance :repo :pathPrefix :reason]]
        (when-let [value (get system k)]
          (println "-" (name k) value)))
      (when (seq (:aliases system))
        (println "- aliases" (str/join ", " (:aliases system))))
      (when (seq (:includes system))
        (println)
        (println "## Includes")
        (doseq [include (:includes system)]
          (println "-" (:repo include) (:path include)))))))

(defn- print-json
  [value]
  (println (json/write-json-str value {:indent-str "  "})))

(defn- target-kind
  [target-id]
  (cond
    (str/starts-with? target-id "file:") :file
    (str/starts-with? target-id "node:") :node
    (str/starts-with? target-id "edge:") :edge
    (str/starts-with? target-id "chunk:") :chunk
    (str/starts-with? target-id "system:") :system-node
    (str/starts-with? target-id "system-node:") :system-node
    (str/starts-with? target-id "system-edge:") :system-edge
    :else :target))

(defn- metadata-row-from-cli
  [args target key value]
  (let [{:keys [project-id repo-id]} (project-scope args)]
    (metadata/row {:project-id project-id
                   :repo-id repo-id
                   :target-id target
                   :target-kind (target-kind target)
                   :key (metadata/parse-key key)
                   :value value
                   :value-type (some-> (option-value args "--type") keyword)
                   :source (keyword (or (option-value args "--source")
                                        (name metadata/default-source)))
                   :confidence (some-> (option-value args "--confidence")
                                       Double/parseDouble)})))

(defn- print-meta-summary
  [summary]
  (println "# Metadata")
  (doseq [[k v] summary]
    (println "-" (name k) v)))

(defn- print-views-summary
  [views]
  (println "# Graph Views")
  (if (seq views)
    (doseq [view views]
      (println "-" (:xt/id view) (:label view)))
    (println "- none")))

(defn- active-project-systems
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-nodes store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn- active-project-system-edges
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-edges store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn- parse-include
  [value]
  (let [idx (.indexOf value ":")]
    (when (neg? idx)
      (throw (ex-info "Include must use repo:path." {:value value})))
    {:repo (subs value 0 idx)
     :path (subs value (inc idx))}))

(defn- parse-source
  [value]
  (parse-include value))

(defn- reject-match
  [kind value]
  (case kind
    "external-api" {:kind "external-api" :host value}
    {:kind kind :label value}))

(defn- print-node-finding
  [{:keys [label kind repo-id path-prefix]}]
  (println (str "- " label
                " [" (name kind) "]"
                " repo " repo-id
                (when path-prefix (str " path " path-prefix)))))

(defn- print-edge-finding
  [{:keys [relation confidence source-id target-id]}]
  (println "-" (name relation) (format "%.2f" (double confidence)) source-id "->" target-id))

(defn- json-output?
  [args]
  (some #{"--json"} args))

(defn- print-maintenance-report
  [{:keys [project-id graph-basis map counts scale fold-in orphaned-systems
           dangling-edges low-confidence-edges
           decision-queue]}]
  (println "# Maintain")
  (println "- project" project-id)
  (when graph-basis
    (println "- graph-basis" (:hash graph-basis)))
  (when map
    (println "- map-rejects" (:rejects map))
    (println "- map-rejected-systems" (:rejected-systems map)))
  (doseq [[k v] counts]
    (println "-" (name k) v))
  (when scale
    (println "- scale" (name (:tier scale)))
    (println "- noise-ratio" (format "%.2f" (double (get-in scale [:ratios :noise]))))
    (println "- orphan-ratio" (format "%.2f" (double (get-in scale [:ratios :orphaned])))))
  (when (seq (:top-hubs scale))
    (println)
    (println "## Top Hubs")
    (doseq [{:keys [label kind repo-id degree]} (take 10 (:top-hubs scale))]
      (println "-" label "[" (name kind) "]" "repo" repo-id "degree" degree)))
  (when (seq orphaned-systems)
    (println)
    (println "## Orphaned Systems")
    (doseq [node (take 20 orphaned-systems)]
      (print-node-finding node)))
  (when (seq dangling-edges)
    (println)
    (println "## Dangling Edges")
    (doseq [edge (take 20 dangling-edges)]
      (print-edge-finding edge)))
  (when (seq low-confidence-edges)
    (println)
    (println "## Low Confidence Edges")
    (doseq [edge (take 20 low-confidence-edges)]
      (print-edge-finding edge)))
  (when (seq decision-queue)
    (println)
    (println "## Decision Queue")
    (doseq [{:keys [id kind severity target reason]} (take 20 decision-queue)]
      (println "-" (name severity) (name kind) target "-" reason)
      (println " " id)))
  (when (seq (:actions fold-in))
    (println)
    (println "## Fold In")
    (doseq [{:keys [kind command reason]} (:actions fold-in)]
      (println "-" (name kind) command)
      (println " " reason))))

(defn- candidate-system?
  [system]
  (contains? #{:candidate-system :repo-boundary} (:kind system)))

(defn- print-candidate-systems
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

(defn- default-provider
  []
  (if (openrouter/api-key)
    :openrouter
    :openai))

(defn- provider-option
  [args]
  (keyword (or (option-value args "--provider")
               (name (default-provider)))))

(defn- default-model
  [provider]
  (case provider
    :openrouter openrouter/default-model
    :openai openai/default-model
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:openrouter :openai]}))))

(defn- provider-api-key
  [provider]
  (case provider
    :openrouter (openrouter/api-key)
    :openai (openai/api-key)
    nil))

(defn- provider-client
  [provider model]
  (case provider
    :openrouter (openrouter/client {:model model})
    :openai (openai/client {:model model})
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:openrouter :openai]}))))

(defn- missing-key-message
  [provider]
  (case provider
    :openrouter "Missing OpenRouter API key. Set AGRAPH_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
    :openai "Missing OpenAI API key. Set AGRAPH_OPENAI_API_KEY or OPENAI_API_KEY."
    "Missing embedding provider API key."))

(defn- llm-provider-option
  [args]
  (keyword (or (option-value args "--provider") "openrouter")))

(defn- llm-model
  [provider args]
  (or (option-value args "--model")
      (llm/default-model provider)))

(defn- llm-client
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

(defn- print-deps
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

(defn- print-path
  [nodes]
  (if (seq nodes)
    (doseq [[idx node] (map-indexed vector nodes)]
      (println (str idx ".") (:label node)))
    (println "No path found.")))

(defn- print-report
  [{:keys [counts top-nodes diagnostics]}]
  (println "# AGraph Report")
  (println)
  (println "## Counts")
  (doseq [[k v] counts]
    (println "-" (name k) v))
  (println)
  (println "## High-Degree Nodes")
  (doseq [{:keys [node degree]} top-nodes]
    (println "-" (:label node) degree))
  (when (seq diagnostics)
    (println)
    (println "## Diagnostics")
    (doseq [diag diagnostics]
      (println "-" (:path diag) (name (:stage diag)) (:message diag)))))

(defn- default-graph-out
  [mode value]
  (str ".dev/reports/"
       (name mode)
       "-"
       (hash/short-hash [(name mode) value (System/currentTimeMillis)])
       ".html"))

(defn- graph-output
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

(defn- graph-json-output
  [args mode value]
  (or (option-value args "--out")
      (default-graph-json-out mode value)))

(defn- query-embedding-client
  [retriever provider model]
  (cond
    (= :lexical retriever) nil
    (provider-api-key provider) (provider-client provider model)
    (= :auto retriever) nil
    :else (throw (ex-info (missing-key-message provider)
                          {:retriever retriever
                           :provider provider}))))

(declare usage)

(defn- print-graph-output
  [path data]
  (println "# Graph")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))

(defn- print-canonical-output
  [path data]
  (println "# Graph Export")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))

(defn- graph-output-value
  [mode graph-args]
  (case mode
    :overview "overview"
    :deps (first (positional-args graph-args))
    :query (str/join " " (positional-args graph-args))
    :systems (option-value graph-args "--project")
    :clusters (option-value graph-args "--project")
    :cluster (str (option-value graph-args "--project") ":" (first (positional-args graph-args)))
    "graph"))

(defn- graph-data-for-mode
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

(defn usage
  []
  (str/join
   "\n"
   ["Usage:"
    "  index <repo-path> [--dry-run]"
    "  project inspect|index|infer|maintain <project.edn> [--dry-run]"
    "  project add-repo <project.edn> <repo-path> [--repo ID] [--role ROLE] [--index] [--infer]"
    "  systems candidates --project ID [--limit N]"
    "  classify decision <decision-id-or-suffix> --project ID [--provider deepseek|openrouter|openai] [--model MODEL]"
    "  map init|propose <project.edn> [--out agraph.map.json]"
    "  map explain <agraph.map.json> <system-id-or-label>"
    "  map set-kind <agraph.map.json> <system-id-or-label> <kind>"
    "  map include <agraph.map.json> <system-id-or-label> <repo>:<path>"
    "  map reject <agraph.map.json> external-api <host> [--reason TEXT]"
    "  docs candidates <target> [--project ID] [--limit N] [--snippet-chars N]"
    "  docs attach <agraph.map.json> <target> <repo>:<path> [--role ROLE] [--heading HEADING] [--start-line N] [--end-line N] [--reason TEXT]"
    "  docs for <target> [--project ID] [--map PATH] [--snippet-chars N]"
    "  docs audit [--project ID] [--map PATH]"
    "  meta defs [--project ID]"
    "  meta set <target-id> <key> <value> [--type string|keyword|boolean|integer|number|tag] [--source SOURCE] [--confidence N] [--project ID] [--repo ID] [--valid-at INSTANT]"
    "  meta get <target-id> [--project ID] [--repo ID] [--valid-at INSTANT]"
    "  meta unset <target-id> <key> [--source SOURCE] [--project ID] [--repo ID] [--valid-at INSTANT]"
    "  views list [--project ID] [--valid-at INSTANT]"
    "  views show <view-id> [--project ID] [--valid-at INSTANT]"
    "  context <text> [--project ID] [--budget N] [--entity-limit N] [--edge-limit N] [--doc-limit N] [--snippet-chars N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--no-map] [--valid-at INSTANT]"
    "  cursor create [query text] --project ID [--budget N] [--limit N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--no-map] [--view ID] [--valid-at INSTANT]"
    "  cursor show <cursor-id> [--budget N]"
    "  cursor open <cursor-id> <target-id-or-label> [--budget N]"
    "  cursor expand <cursor-id> <target-id-or-label> [--relation REL] [--limit N] [--budget N]"
    "  cursor docs <cursor-id> <target-id-or-label> [--budget N]"
    "  cursor search <cursor-id> <text> [--limit N] [--budget N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL]"
    "  embed [--provider openrouter|openai] [--model MODEL] [--batch-size N] [--limit N]"
    "  query <text> [--project ID] [--repo ID] [--limit N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--valid-at INSTANT]"
    "  graph overview|deps|query|systems|clusters|cluster [args] [--project ID] [--repo ID] [--depth N] [--limit N] [--detail primary|expanded|evidence|raw] [--map PATH] [--no-map] [--view ID] [--out PATH] [--valid-at INSTANT]"
    "  graph export overview|deps|query|systems|clusters|cluster [args] [--project ID] [--repo ID] [--depth N] [--limit N] [--detail primary|expanded|evidence|raw] [--map PATH] [--no-map] [--view ID] [--out PATH] [--valid-at INSTANT]"
    "  deps <node-or-namespace> [--project ID] [--repo ID] [--valid-at INSTANT]"
    "  path <source> <target> [--project ID] [--repo ID] [--systems] [--valid-at INSTANT]"
    "  report [--project ID] [--repo ID] [--valid-at INSTANT]"
    "  mcp"]))

(defn- print-mcp-placeholder
  []
  (println "# AGraph MCP")
  (println "- status not-implemented")
  (println "- message MCP is the planned structured agent interface; use the CLI commands for now."))

(defn dispatch
  [command args]
  (case command
    ("help" "--help" "-h")
    (println (usage))

    "index"
    (let [root (first args)]
      (when-not root
        (throw (ex-info "Missing repo path." {:usage (usage)})))
      (if (dry-run? args)
        (pprint/pprint (index/index-repo! nil root {:dry-run? true}))
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (pprint/pprint (index/index-repo! xtdb root {}))))))

    "systems"
    (let [action (keyword (first args))
          system-args (vec (rest args))]
      (case action
        :candidates
        (let [{:keys [project-id]} (project-scope system-args)
              limit (or (parse-limit system-args) 50)]
          (when (str/blank? (str project-id))
            (throw (ex-info "Missing --project for system candidates." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-candidate-systems
               (->> (active-project-systems xtdb project-id)
                    (filter candidate-system?)
                    (sort-by (juxt :repo-id :label))
                    vec)
               limit))))

        (throw (ex-info "Unknown systems command." {:command action
                                                    :usage (usage)}))))

    "classify"
    (let [action (keyword (first args))
          classify-args (vec (rest args))]
      (case action
        :decision
        (let [decision-id (first (positional-args classify-args))
              {:keys [project-id]} (project-scope classify-args)
              provider (llm-provider-option classify-args)
              model (llm-model provider classify-args)]
          (when-not decision-id
            (throw (ex-info "Missing decision id." {:usage (usage)})))
          (when (str/blank? (str project-id))
            (throw (ex-info "Missing --project for decision classification."
                            {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (let [report (system/maintenance-report
                            xtdb
                            project-id
                            {:low-confidence-threshold
                             (parse-double-option classify-args "--min-confidence" 0.60)})
                    decision (decision-classifier/decision-by-id
                              (:decision-queue report)
                              decision-id)]
                (when-not decision
                  (throw (ex-info "Maintenance decision not found."
                                  {:decision-id decision-id
                                   :project-id project-id})))
                (print-json
                 (decision-classifier/classify
                  {:client (llm-client provider model classify-args)
                   :decision decision}))))))

        (throw (ex-info "Unknown classify command." {:command action
                                                     :usage (usage)}))))

    "map"
    (let [action (keyword (first args))
          map-args (vec (rest args))]
      (case action
        :init
        (let [config-path (first (positional-args map-args))
              out (or (option-value map-args "--out") graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)
                data (graph-map/empty-map (:id project))]
            (print-map-summary (graph-map/write-map! out data) data)))

        :propose
        (let [config-path (first (positional-args map-args))
              out (or (option-value map-args "--out") graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)]
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (let [systems (active-project-systems xtdb (:id project))
                      edges (active-project-system-edges xtdb (:id project))
                      data (graph-map/propose-map (:id project) systems edges)]
                  (print-map-summary (graph-map/write-map! out data) data))))))

        :explain
        (let [[map-path value] (positional-args map-args)]
          (when-not (and map-path value)
            (throw (ex-info "Missing map path or system id/label." {:usage (usage)})))
          (print-map-system (graph-map/system-by-id-or-label (graph-map/read-map map-path) value)))

        :set-kind
        (let [[map-path value kind] (positional-args map-args)]
          (when-not (and map-path value kind)
            (throw (ex-info "Missing map path, system id/label, or kind." {:usage (usage)})))
          (let [data (graph-map/set-kind (graph-map/read-map map-path) value kind)]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        :include
        (let [[map-path value include] (positional-args map-args)]
          (when-not (and map-path value include)
            (throw (ex-info "Missing map path, system id/label, or repo:path include."
                            {:usage (usage)})))
          (let [data (graph-map/add-include (graph-map/read-map map-path)
                                            value
                                            (parse-include include))]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        :reject
        (let [[map-path kind value] (positional-args map-args)
              reason (option-value map-args "--reason")]
          (when-not (and map-path kind value)
            (throw (ex-info "Missing map path, reject kind, or reject value."
                            {:usage (usage)})))
          (let [data (graph-map/add-reject (graph-map/read-map map-path)
                                           (reject-match kind value)
                                           reason)]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        (throw (ex-info "Unknown map command." {:command action
                                                :usage (usage)}))))

    "docs"
    (let [action (keyword (first args))
          docs-args (vec (rest args))
          positional (positional-args docs-args)
          {:keys [project-id]} (project-scope docs-args)]
      (case action
        :candidates
        (let [target (first positional)
              limit (or (parse-limit docs-args) context/default-doc-limit)
              snippet-chars (parse-long-option docs-args
                                               "--snippet-chars"
                                               context/default-snippet-chars)]
          (when-not target
            (throw (ex-info "Missing docs target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/doc-candidates xtdb
                                                  target
                                                  {:project-id project-id
                                                   :limit limit
                                                   :snippet-chars snippet-chars})))))

        :attach
        (let [[map-path target source-value] positional
              _ (when-not (and map-path target source-value)
                  (throw (ex-info "Missing map path, target, or repo:path source."
                                  {:usage (usage)})))
              source (parse-source source-value)
              data (graph-map/add-doc (graph-map/read-map map-path)
                                      target
                                      source
                                      {:role (option-value docs-args "--role")
                                       :heading (option-value docs-args "--heading")
                                       :start-line (parse-optional-long docs-args "--start-line")
                                       :end-line (parse-optional-long docs-args "--end-line")
                                       :reason (option-value docs-args "--reason")})]
          (print-map-summary (graph-map/write-map! map-path data) data))

        :for
        (let [target (first positional)
              map-path (default-map-path docs-args)
              snippet-chars (parse-long-option docs-args
                                               "--snippet-chars"
                                               context/default-snippet-chars)]
          (when-not target
            (throw (ex-info "Missing docs target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/docs-for xtdb
                                            target
                                            {:project-id project-id
                                             :map-path map-path
                                             :snippet-chars snippet-chars})))))

        :audit
        (let [map-path (default-map-path docs-args)]
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/docs-audit xtdb
                                              {:project-id project-id
                                               :map-path map-path})))))

        (throw (ex-info "Unknown docs command." {:command action
                                                 :usage (usage)}))))

    "meta"
    (let [action (keyword (first args))
          meta-args (vec (rest args))
          positional (positional-args meta-args)
          scope (merge (project-scope meta-args) (temporal-options meta-args))]
      (case action
        :defs
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-json (->> (vals (store/metadata-defs xtdb scope))
                             (sort-by (comp metadata/key-name :key))
                             (mapv metadata/export-definition)))))

        :set
        (let [[target key value] positional]
          (when-not (and target key value)
            (throw (ex-info "Missing metadata target, key, or value."
                            {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-meta-summary
               (store/commit-metadata! xtdb
                                       [(metadata-row-from-cli meta-args target key value)]
                                       {:valid-from (:valid-at (temporal-options meta-args))})))))

        :get
        (let [target (first positional)]
          (when-not target
            (throw (ex-info "Missing metadata target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (store/metadata-for-targets xtdb [target] scope)))))

        :unset
        (let [[target key] positional]
          (when-not (and target key)
            (throw (ex-info "Missing metadata target or key." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-meta-summary
               (store/delete-metadata! xtdb
                                       (merge scope
                                              {:target-id target
                                               :key key
                                               :source (keyword (or (option-value meta-args "--source")
                                                                    (name metadata/default-source)))
                                               :valid-from (:valid-at (temporal-options meta-args))}))))))

        (throw (ex-info "Unknown meta command." {:command action
                                                 :usage (usage)}))))

    "views"
    (let [action (keyword (first args))
          view-args (vec (rest args))
          positional (positional-args view-args)
          scope (merge (project-scope view-args) (temporal-options view-args))]
      (case action
        :list
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-views-summary (store/graph-views xtdb scope))))

        :show
        (let [view-id (first positional)]
          (when-not view-id
            (throw (ex-info "Missing view id." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (or (store/graph-view xtdb view-id scope)
                              {:error "view not found"
                               :view view-id})))))

        (throw (ex-info "Unknown views command." {:command action
                                                  :usage (usage)}))))

    "context"
    (let [query-text (str/join " " (positional-args args))
          retriever (keyword (or (option-value args "--retriever") "auto"))
          provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
          {:keys [project-id]} (project-scope args)
          temporal (temporal-options args)]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-json
           (context/context-packet xtdb
                                   query-text
                                   {:project-id project-id
                                    :retriever retriever
                                    :embedding-client embedding-client
                                    :map-path (default-map-path args)
                                    :read-context temporal
                                    :budget (parse-long-option args
                                                               "--budget"
                                                               context/default-budget)
                                    :entity-limit (parse-long-option args
                                                                     "--entity-limit"
                                                                     context/default-entity-limit)
                                    :edge-limit (parse-long-option args
                                                                   "--edge-limit"
                                                                   context/default-edge-limit)
                                    :doc-limit (parse-long-option args
                                                                  "--doc-limit"
                                                                  context/default-doc-limit)
                                    :snippet-chars (parse-long-option args
                                                                      "--snippet-chars"
                                                                      context/default-snippet-chars)
                                    :min-confidence (parse-double-option args
                                                                         "--min-confidence"
                                                                         0.55)})))))

    "cursor"
    (let [action (keyword (first args))
          cursor-args (vec (rest args))
          positional (positional-args cursor-args)
          retriever (keyword (or (option-value cursor-args "--retriever") "auto"))
          provider (provider-option cursor-args)
          model (or (option-value cursor-args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
          budget (parse-optional-long cursor-args "--budget")]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (case action
            :create
            (let [query-text (or (option-value cursor-args "--query")
                                 (not-empty (str/trim (str/join " " positional))))
                  {:keys [project-id]} (project-scope cursor-args)]
              (print-json
               (cursor/create! xtdb
                               {:project-id project-id
                                :query-text query-text
                                :retriever retriever
                                :embedding-client embedding-client
                                :map-path (default-map-path cursor-args)
                                :view-id (option-value cursor-args "--view")
                                :read-context (temporal-options cursor-args)
                                :budget (or budget context/default-budget)
                                :node-limit (or (parse-limit cursor-args)
                                                (parse-optional-long cursor-args
                                                                     "--entity-limit")
                                                context/default-entity-limit)
                                :edge-limit (parse-long-option cursor-args
                                                               "--edge-limit"
                                                               context/default-edge-limit)
                                :doc-limit (parse-long-option cursor-args
                                                              "--doc-limit"
                                                              context/default-doc-limit)
                                :snippet-chars (parse-long-option cursor-args
                                                                  "--snippet-chars"
                                                                  context/default-snippet-chars)
                                :min-confidence (parse-double-option cursor-args
                                                                     "--min-confidence"
                                                                     0.55)})))

            :show
            (let [cursor-id (first positional)]
              (when-not cursor-id
                (throw (ex-info "Missing cursor id." {:usage (usage)})))
              (print-json (cursor/show xtdb cursor-id {:budget budget})))

            :open
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (print-json (cursor/open! xtdb cursor-id target {:budget budget})))

            :expand
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (print-json (cursor/expand! xtdb
                                          cursor-id
                                          target
                                          {:budget budget
                                           :relation (option-value cursor-args "--relation")
                                           :limit (parse-limit cursor-args)})))

            :docs
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (print-json (cursor/docs! xtdb cursor-id target {:budget budget})))

            :search
            (let [[cursor-id & query-parts] positional
                  query-text (or (option-value cursor-args "--query")
                                 (str/join " " query-parts))]
              (when-not cursor-id
                (throw (ex-info "Missing cursor id." {:usage (usage)})))
              (print-json (cursor/search! xtdb
                                          cursor-id
                                          query-text
                                          {:budget budget
                                           :retriever retriever
                                           :embedding-client embedding-client
                                           :limit (parse-limit cursor-args)})))

            (throw (ex-info "Unknown cursor command." {:command action
                                                       :usage (usage)}))))))

    "embed"
    (let [provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          batch-size (parse-long-option args "--batch-size" embedding/default-batch-size)
          limit (parse-limit args)
          {:keys [project-id repo-id]} (project-scope args)]
      (when-not (provider-api-key provider)
        (throw (ex-info (missing-key-message provider)
                        {:provider provider})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-embed-summary
           (embedding/embed-search-docs! xtdb
                                         (provider-client provider model)
                                         {:batch-size batch-size
                                          :limit limit
                                          :project-id project-id
                                          :repo-id repo-id})))))

    "query"
    (let [query-text (str/join " " (positional-args args))
          retriever (keyword (or (option-value args "--retriever") "auto"))
          provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          limit (or (parse-limit args) 10)
          embedding-client (query-embedding-client retriever provider model)
          {:keys [project-id repo-id]} (project-scope args)
          temporal (temporal-options args)]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (when (str/blank? query-text)
        (throw (ex-info "Missing query text." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (doseq [result (query/semantic-query xtdb
                                               query-text
                                               {:limit limit
                                                :retriever retriever
                                                :embedding-client embedding-client
                                                :project-id project-id
                                                :repo-id repo-id
                                                :read-context temporal})]
            (print-query-result result)))))

    "project"
    (let [action (keyword (first args))
          project-args (vec (rest args))
          config-path (first (positional-args project-args))]
      (when-not config-path
        (throw (ex-info "Missing project config path." {:usage (usage)})))
      (case action
        :add-repo
        (let [[_ repo-root] (positional-args project-args)]
          (when-not repo-root
            (throw (ex-info "Missing repo path for project add-repo."
                            {:usage (usage)})))
          (let [project (project/add-repo-to-config!
                         config-path
                         repo-root
                         {:repo-id (option-value project-args "--repo")
                          :role (some-> (option-value project-args "--role") keyword)})
                repo (last (:repos project))
                index? (or (some #{"--index"} project-args)
                           (some #{"--infer"} project-args))
                infer? (some #{"--infer"} project-args)
                next-commands (cond-> []
                                (not index?)
                                (conj (str "agraph project index " config-path))
                                (not infer?)
                                (conj (str "agraph project infer " config-path))
                                true
                                (conj (str "agraph project maintain " config-path)))]
            (if index?
              (store/with-node (store/storage-path)
                (fn [xtdb]
                  (let [index-summary (project/index-project-repo! xtdb
                                                                   project
                                                                   (:id repo)
                                                                   {})
                        system-summary (when infer?
                                         (project/infer-project! xtdb project))]
                    (print-project-add-repo-summary
                     {:project project
                      :repo repo
                      :index-summary index-summary
                      :system-summary system-summary
                      :next next-commands}))))
              (print-project-add-repo-summary
               {:project project
                :repo repo
                :next next-commands}))))

        (let [project (project/read-project config-path)]
          (case action
            :inspect
            (print-project-inspect project)

            :index
            (if (dry-run? project-args)
              (print-project-index-summary (project/index-project! nil project {:dry-run? true}))
              (store/with-node (store/storage-path)
                (fn [xtdb]
                  (print-project-index-summary (project/index-project! xtdb project {})))))

            :infer
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (print-system-summary (project/infer-project! xtdb project))))

            :maintain
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (let [map-path (default-map-path project-args)
                      report (project/maintain-project
                              xtdb
                              project
                              {:low-confidence-threshold
                               (parse-double-option project-args
                                                    "--min-confidence"
                                                    0.60)
                               :map-overlay (when map-path
                                              (graph-map/read-map map-path))})]
                  (if (json-output? project-args)
                    (print-json report)
                    (print-maintenance-report report)))))

            (throw (ex-info "Unknown project command." {:command action
                                                        :usage (usage)}))))))

    "graph"
    (let [raw-mode (keyword (first args))
          raw-graph-args (vec (rest args))
          export? (= :export raw-mode)
          mode (if export?
                 (keyword (first raw-graph-args))
                 raw-mode)
          graph-args (if export?
                       (vec (rest raw-graph-args))
                       raw-graph-args)
          limit (or (parse-limit graph-args)
                    (case mode :query 40 graph/default-node-limit))
          depth (parse-depth graph-args)
          value (graph-output-value mode graph-args)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [data (graph-data-for-mode xtdb mode graph-args limit depth)]
            (if export?
              (print-canonical-output
               (graph/write-canonical! (graph-json-output graph-args mode value) data)
               data)
              (print-graph-output
               (graph/write-html! (graph-output graph-args mode value) data)
               data))))))

    "deps"
    (let [value (first (positional-args args))
          scope (assoc (project-scope args) :read-context (temporal-options args))]
      (when-not value
        (throw (ex-info "Missing node query." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-deps (query/deps xtdb value scope)))))

    "path"
    (let [[source target] (positional-args args)
          scope (assoc (project-scope args) :read-context (temporal-options args))]
      (when-not (and source target)
        (throw (ex-info "Missing source or target." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-path (if (system-path? args)
                        (query/system-path xtdb source target scope)
                        (query/graph-path xtdb source target scope))))))

    "report"
    (let [scope (assoc (project-scope args) :read-context (temporal-options args))]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-report (query/report xtdb scope)))))

    "mcp"
    (print-mcp-placeholder)

    (throw (ex-info "Unknown command." {:command command
                                        :usage (usage)}))))

(defn -main
  [& args]
  (try
    (silence-jul!)
    (if-let [command (first args)]
      (dispatch command (vec (rest args)))
      (println (usage)))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (let [data (ex-data e)]
          (if (= cursor/error-schema (:schema data))
            (print-json data)
            (do
              (println (ex-message e))
              (when data
                (pprint/pprint data))))))
      (shutdown-agents)
      (System/exit 1))))
