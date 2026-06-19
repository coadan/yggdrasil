(ns agraph.mcp
  "Minimal MCP stdio server for AGraph packets."
  (:require [agraph.activity :as activity]
            [agraph.context :as context]
            [agraph.cursor :as cursor]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.queue :as queue]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:gen-class))

(def protocol-version
  "2025-03-26")

(def server-name
  "agraph-mcp")

(def server-version
  "0.1.0")

(def server-instructions
  (str "Use agraph_explore first for structural coding questions when a project "
       "graph exists. Check freshness and answerability before trusting missing "
       "evidence. Use agraph_node for one exact file, node, package, system, or "
       "evidence target; ambiguous labels return choices. Use agraph_status for "
       "graph freshness, coverage, and next actions. Use agraph_systems for a "
       "compact systems view. Treat AGraph output as mechanical facts plus "
       "accepted map/metadata corrections; do not infer architecture from names "
       "or path vocabulary."))

(def default-root
  ".")

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(def default-tool-groups
  "default")

(defn- configured-tool-groups
  [args]
  (or (option-value args "--tools")
      (System/getenv "AGRAPH_MCP_TOOLS")
      default-tool-groups))

(defn- parse-tool-groups
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       (map keyword)
       set))

(defn server-context
  "Return immutable server context from CLI args."
  [args]
  {:root (or (option-value args "--root") default-root)
   :config-path (or (option-value args "--config")
                    (option-value args "--project-config"))
   :map-path (option-value args "--map")
   :queue-dir (or (option-value args "--queue-dir") queue/default-root)
   :storage-path (or (option-value args "--storage") (store/storage-path))
   :tool-groups (parse-tool-groups (configured-tool-groups args))})

(defn- json-schema
  [properties required]
  {:type "object"
   :additionalProperties false
   :properties properties
   :required required})

(def tool-definitions
  [{:name "agraph_explore"
    :groups #{:default}
    :description "Return the primary one-shot AGraph orientation packet for an agent question."
    :inputSchema (json-schema
                  {:query {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :mapPath {:type "string"}
                   :retriever {:type "string"
                               :enum ["lexical" "auto" "hybrid" "semantic"]}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["query"])}
   {:name "agraph_node"
    :groups #{:default}
    :description "Inspect one exact graph node or source file target with mechanical neighbors and source context."
    :inputSchema (json-schema
                  {:target {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :mapPath {:type "string"}
                   :limit {:type "integer"
                           :minimum 1}
                   :sourceLines {:type "integer"
                                 :minimum 1}}
                  ["target"])}
   {:name "agraph_ask"
    :groups #{:ask}
    :description "Return an AGraph context packet for one graph-grounded question."
    :inputSchema (json-schema
                  {:query {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :mapPath {:type "string"}
                   :retriever {:type "string"
                               :enum ["lexical" "auto" "hybrid" "semantic"]}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["query"])}
   {:name "agraph_explore_create"
    :groups #{:cursor}
    :description "Create a persistent graph exploration cursor packet."
    :inputSchema (json-schema
                  {:query {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :mapPath {:type "string"}
                   :budget {:type "integer"
                            :minimum 1000}}
                  [])}
   {:name "agraph_explore_open"
    :groups #{:cursor}
    :description "Open one target in an existing graph exploration cursor."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :target {:type "string"}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "target"])}
   {:name "agraph_explore_expand"
    :groups #{:cursor}
    :description "Expand an existing graph exploration cursor around one target."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :target {:type "string"}
                   :relation {:type "string"}
                   :limit {:type "integer"
                           :minimum 1}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "target"])}
   {:name "agraph_explore_docs"
    :groups #{:cursor}
    :description "Open documentation and source snippets for one cursor target."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :target {:type "string"}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "target"])}
   {:name "agraph_explore_search"
    :groups #{:cursor}
    :description "Search within an existing graph exploration cursor basis."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :query {:type "string"}
                   :retriever {:type "string"
                               :enum ["lexical" "auto" "hybrid" "semantic"]}
                   :limit {:type "integer"
                           :minimum 1}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "query"])}
   {:name "agraph_systems"
    :groups #{:default}
    :description "Return the canonical agraph.graph/v2 systems graph JSON."
    :inputSchema (json-schema
                  {:projectId {:type "string"}
                   :configPath {:type "string"}
                   :mapPath {:type "string"}
                   :detail {:type "string"
                            :enum ["primary" "expanded" "evidence" "raw"]}
                   :limit {:type "integer"
                           :minimum 1}}
                  [])}
   {:name "agraph_sync_inspect"
    :groups #{:sync}
    :description "Return project config plus the current mechanical evidence surface without syncing."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :mapPath {:type "string"}}
                  [])}
   {:name "agraph_status"
    :groups #{:default}
    :description "Return the agent-facing project status, evidence surface, and next actions without syncing."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :mapPath {:type "string"}}
                  [])}
   {:name "agraph_sync_check"
    :groups #{:sync}
    :description "Return the read-only maintenance check report for a project."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :mapPath {:type "string"}
                   :minConfidence {:type "number"}}
                  [])}
   {:name "agraph_sync_activity"
    :groups #{:sync}
    :description "Import filesystem queue lifecycle and result audit facts into local activity rows."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :queueDir {:type "string"}}
                  [])}
   {:name "agraph_work_list"
    :groups #{:work}
    :description "List filesystem queue work items without claiming them."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :projectId {:type "string"}
                   :kind {:type "string"}
                   :status {:type "string"}
                   :limit {:type "integer"
                           :minimum 1}}
                  [])}
   {:name "agraph_work_show"
    :groups #{:work}
    :description "Return one filesystem queue work item without changing its state."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}}
                  ["workId"])}
   {:name "agraph_work_pull"
    :groups #{:work}
    :description "Claim one ready filesystem queue item for an agent."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :projectId {:type "string"}
                   :kind {:type "string"}
                   :agentId {:type "string"}
                   :leaseMinutes {:type "integer"
                                  :minimum 1}}
                  [])}
   {:name "agraph_work_heartbeat"
    :groups #{:work}
    :description "Extend the lease for one claimed filesystem queue item."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :agentId {:type "string"}
                   :leaseMinutes {:type "integer"
                                  :minimum 1}}
                  ["workId"])}
   {:name "agraph_work_complete"
    :groups #{:work}
    :description "Complete a claimed filesystem queue item with a schema-bearing result object."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :result {:type "object"}}
                  ["workId" "result"])}
   {:name "agraph_work_release"
    :groups #{:work}
    :description "Release one claimed filesystem queue item back to ready."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId"])}
   {:name "agraph_work_reject"
    :groups #{:work}
    :description "Reject one filesystem queue item with a reason."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId" "reason"])}])

(defn- tool-visible?
  [groups tool]
  (or (contains? groups :all)
      (seq (set/intersection groups (:groups tool)))))

(defn- listed-tools
  [ctx]
  (->> tool-definitions
       (filter #(tool-visible? (:tool-groups ctx) %))
       (mapv #(dissoc % :groups))))

(defn- abs-path
  [path]
  (.getPath (.getCanonicalFile (io/file path))))

(defn- config-path
  [ctx args]
  (or (:configPath args)
      (:config-path ctx)
      (let [candidate (io/file (:root ctx) "project.edn")]
        (when (.isFile candidate)
          (.getPath candidate)))))

(defn- read-project!
  [ctx args]
  (let [path (config-path ctx args)]
    (when (str/blank? (str path))
      (throw (ex-info "Missing project config path."
                      {:schema "agraph.mcp.error/v1"
                       :error "missing-project-config"
                       :hint "Pass --config to agraph-mcp or configPath to the tool."})))
    (project/read-project path)))

(defn- project-id
  [project args]
  (or (:projectId args) (:id project)))

(defn- map-overlay
  [ctx args]
  (let [path (or (:mapPath args) (:map-path ctx))]
    (when (and path (graph-map/file-exists? path))
      (graph-map/read-map path))))

(defn- with-xtdb
  [ctx f]
  (store/with-node (:storage-path ctx) f))

(defn- tool-packet
  [value]
  {:content [{:type "text"
              :text (json/write-json-str value)}]
   :structuredContent value})

(defn- require-string!
  [args key message]
  (let [value (get args key)]
    (when (str/blank? (str value))
      (throw (ex-info message
                      {:schema "agraph.mcp.error/v1"
                       :error "invalid-arguments"
                       :field (name key)})))
    value))

(defn- require-result!
  [args]
  (let [result (:result args)]
    (when-not (map? result)
      (throw (ex-info "work_complete result must be a JSON object."
                      {:schema "agraph.mcp.error/v1"
                       :error "invalid-result"
                       :field "result"})))
    (when (str/blank? (str (:schema result)))
      (throw (ex-info "work_complete result must include a schema field."
                      {:schema "agraph.mcp.error/v1"
                       :error "invalid-result"
                       :field "result.schema"})))
    result))

(defn- context-query-packet
  [ctx args]
  (let [query (require-string! args :query "AGraph context query requires query.")
        project (read-project! ctx args)
        overlay (map-overlay ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        (context/context-packet xtdb
                                query
                                {:project-id (project-id project args)
                                 :retriever (keyword (or (:retriever args) "lexical"))
                                 :map-overlay overlay
                                 :budget (or (:budget args) context/default-budget)})))))

(defn- ask
  [ctx args]
  (context-query-packet ctx args))

(defn- explore
  [ctx args]
  (let [project (read-project! ctx args)
        overlay (map-overlay ctx args)
        config-path (config-path ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        (assoc (context/context-packet xtdb
                                       (require-string! args
                                                        :query
                                                        "agraph_explore requires query.")
                                       {:project-id (project-id project args)
                                        :retriever (keyword (or (:retriever args) "lexical"))
                                        :map-overlay overlay
                                        :budget (or (:budget args) context/default-budget)})
               :freshness
               (:freshness (evidence/summarize xtdb
                                               project
                                               {:map-overlay overlay
                                                :config-path (or config-path
                                                                 (:path project))
                                                :map-path (or (:mapPath args)
                                                              (:map-path ctx))})))))))

(def node-inspect-schema
  "agraph.node.inspect/v1")

(def default-node-inspect-limit
  40)

(def default-node-source-lines
  160)

(def max-node-source-bytes
  (* 256 1024))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn- distinct-by
  [f coll]
  (loop [remaining (seq coll)
         seen #{}
         out []]
    (if-let [item (first remaining)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out item))))
      out)))

(defn- compact-file-row
  [file]
  (select-keys file [:xt/id :project-id :repo-id :repo-root :repo-role :path :ext
                     :kind :content-sha :extractor-fingerprint :mtime-ms
                     :size-bytes :run-id]))

(defn- compact-node-row
  [node]
  (select-keys node [:xt/id :project-id :repo-id :label :kind :file-id :path
                     :ecosystem :package-name :version-range :resolved-version
                     :dependency-scope :import-names :namespace :name :public?
                     :source-line :run-id]))

(defn- compact-system-row
  [system]
  (select-keys system [:id :label :kind :status :includes :aliases :tags
                       :lifecycle :clusterHint :reason]))

(defn- node-ref
  [node]
  (when node
    (select-keys node [:xt/id :repo-id :label :kind :path :source-line])))

(defn- edge-ref
  [nodes-by-id edge]
  (cond-> (select-keys edge [:xt/id :project-id :repo-id :relation :confidence
                             :file-id :path :source-line :import-name
                             :resolution-source :run-id])
    (:source-id edge) (assoc :source (or (node-ref (get nodes-by-id (:source-id edge)))
                                         {:xt/id (:source-id edge)}))
    (:target-id edge) (assoc :target (or (node-ref (get nodes-by-id (:target-id edge)))
                                         {:xt/id (:target-id edge)}))))

(defn- repo-path
  [row]
  (when (and (:repo-id row) (:path row))
    (str (:repo-id row) ":" (:path row))))

(defn- target-choice
  [target-kind match row]
  (cond-> {:targetKind target-kind
           :match match
           :id (or (:xt/id row) (:id row))
           :repo (:repo-id row)}
    (:label row) (assoc :label (:label row))
    (:kind row) (assoc :kind (:kind row))
    (:path row) (assoc :path (:path row))
    (:source-line row) (assoc :sourceLine (:source-line row))))

(defn- exact-file-matches
  [target files]
  (->> files
       (keep (fn [file]
               (cond
                 (= target (:xt/id file)) [:id file]
                 (= target (:path file)) [:path file]
                 (= target (repo-path file)) [:repo-path file]
                 :else nil)))
       (distinct-by (comp :xt/id second))
       (mapv (fn [[match file]]
               {:target-kind :file
                :match match
                :row file}))))

(defn- exact-node-matches
  [target nodes]
  (->> nodes
       (keep (fn [node]
               (cond
                 (= target (:xt/id node)) [:id node]
                 (= target (:label node)) [:label node]
                 (= target (:namespace node)) [:namespace node]
                 (= target (:name node)) [:name node]
                 :else nil)))
       (distinct-by (comp :xt/id second))
       (mapv (fn [[match node]]
               {:target-kind :node
                :match match
                :row node}))))

(defn- exact-system-matches
  [target overlay]
  (->> (:systems overlay)
       (remove #(= "rejected" (str (:status %))))
       (keep (fn [system]
               (cond
                 (= target (str (:id system))) [:id system]
                 (= target (str (:label system))) [:label system]
                 :else nil)))
       (distinct-by (comp :id second))
       (mapv (fn [[match system]]
               {:target-kind :system
                :match match
                :row system}))))

(defn- scoped-files
  [xtdb project-id]
  (->> (store/all-rows xtdb (:files store/tables))
       (filter active-row?)
       (filter #(or (nil? project-id) (= project-id (:project-id %))))
       vec))

(defn- inspect-matches
  [target files nodes overlay]
  (->> (concat (exact-file-matches target files)
               (exact-system-matches target overlay)
               (exact-node-matches target nodes))
       (sort-by (fn [{:keys [target-kind match row]}]
                  [(case target-kind :file 0 :system 1 :node 2 3)
                   (case match :id 0 :repo-path 1 :path 2 :label 3 4)
                   (:repo-id row)
                   (:path row)
                   (:label row)
                   (:xt/id row)]))
       vec))

(defn- repo-roots
  [project]
  (into {} (map (juxt :id :root)) (:repos project)))

(defn- file-absolute-path
  [project file]
  (when-let [root (or (:repo-root file)
                      (get (repo-roots project) (:repo-id file)))]
    (.getPath (io/file root (:path file)))))

(defn- line-numbered-source
  [project file source-lines]
  (if-let [path (file-absolute-path project file)]
    (let [source-file (io/file path)]
      (cond
        (not (.isFile source-file))
        {:status :unavailable
         :reason "file-not-found"
         :path path}

        (> (.length source-file) max-node-source-bytes)
        {:status :unavailable
         :reason "file-too-large"
         :path path
         :sizeBytes (.length source-file)
         :maxBytes max-node-source-bytes}

        :else
        (let [lines (str/split-lines (slurp source-file))
              selected (take source-lines lines)]
          {:status :available
           :path path
           :truncated (> (count lines) source-lines)
           :lines (mapv (fn [line text]
                          {:line line
                           :text text})
                        (range 1 (inc (count selected)))
                        selected)})))
    {:status :unavailable
     :reason "repo-root-missing"}))

(defn- file-for-node
  [files node]
  (or (some #(when (= (:file-id node) (:xt/id %)) %) files)
      (some #(when (and (= (:repo-id node) (:repo-id %))
                        (= (:path node) (:path %)))
               %)
            files)))

(defn- incident-graph
  [match nodes edges limit]
  (let [nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
        file-id (case (:target-kind match)
                  :file (get-in match [:row :xt/id])
                  :node (get-in match [:row :file-id])
                  nil)
        focus-node-ids (case (:target-kind match)
                         :node #{(get-in match [:row :xt/id])}
                         :file (->> nodes
                                    (filter #(= file-id (:file-id %)))
                                    (map :xt/id)
                                    set)
                         #{})
        incident? (fn [edge]
                    (or (= file-id (:file-id edge))
                        (contains? focus-node-ids (:source-id edge))
                        (contains? focus-node-ids (:target-id edge))))
        selected-edges (->> edges
                            (filter active-row?)
                            (filter incident?)
                            (sort-by (juxt :repo-id :path :source-line :relation
                                           :source-id :target-id))
                            (take limit)
                            vec)
        related-node-ids (set (concat focus-node-ids
                                      (mapcat (juxt :source-id :target-id)
                                              selected-edges)))]
    {:nodes (->> related-node-ids
                 (keep nodes-by-id)
                 (sort-by (juxt :repo-id :path :source-line :label :xt/id))
                 (take limit)
                 (mapv compact-node-row))
     :edges (mapv #(edge-ref nodes-by-id %) selected-edges)}))

(defn- include-matches-file?
  [file include]
  (and (= (some-> (:repo include) str) (some-> (:repo-id file) str))
       (= (some-> (:path include) str) (some-> (:path file) str))))

(defn- target-values
  [target match]
  (let [row (:row match)]
    (set (remove nil?
                 [target
                  (:xt/id row)
                  (:id row)
                  (:label row)
                  (:namespace row)
                  (:name row)
                  (repo-path row)]))))

(defn- map-attachments
  [overlay target match]
  (let [values (target-values target match)
        row (:row match)
        file? (= :file (:target-kind match))
        system-match? (fn [system]
                        (or (contains? values (str (:id system)))
                            (contains? values (str (:label system)))
                            (and file?
                                 (some #(include-matches-file? row %)
                                       (:includes system)))))
        doc-match? (fn [doc]
                     (or (contains? values (str (:target doc)))
                         (and file?
                              (= (:path row)
                                 (some-> doc :source :path str)))))
        edge-match? (fn [edge]
                      (or (contains? values (str (:source edge)))
                          (contains? values (str (:target edge)))))]
    (when overlay
      {:systems (->> (:systems overlay)
                     (filter system-match?)
                     (mapv #(select-keys % [:id :label :kind :status :includes
                                            :reason :tags])))
       :docs (->> (:docs overlay)
                  (filter doc-match?)
                  (mapv #(select-keys % [:target :role :source :status :reason])))
       :edges (->> (:edges overlay)
                   (filter edge-match?)
                   (mapv #(select-keys % [:id :source :target :relation :status
                                          :reason :evidence])))})))

(defn- node-inspect
  [ctx args]
  (let [target (require-string! args :target "agraph_node requires target.")
        project (read-project! ctx args)
        project-id (project-id project args)
        overlay (map-overlay ctx args)
        limit (long (or (:limit args) default-node-inspect-limit))
        source-lines (long (or (:sourceLines args) default-node-source-lines))]
    (with-xtdb
      ctx
      (fn [xtdb]
        (let [files (scoped-files xtdb project-id)
              nodes (->> (query/all-nodes xtdb {:project-id project-id})
                         (filter active-row?)
                         vec)
              edges (->> (query/all-edges xtdb {:project-id project-id})
                         (filter active-row?)
                         vec)
              matches (inspect-matches target files nodes overlay)]
          (cond
            (empty? matches)
            {:schema node-inspect-schema
             :target target
             :project {:id project-id}
             :status :not-found
             :choices []
             :nextActions [{:kind :explore
                            :label "Search for a graph context packet"
                            :mcpTool "agraph_explore"
                            :mcpArgs {:query target
                                      :projectId project-id}}]}

            (> (count matches) 1)
            {:schema node-inspect-schema
             :target target
             :project {:id project-id}
             :status :ambiguous
             :choices (mapv #(target-choice (:target-kind %)
                                            (:match %)
                                            (:row %))
                            (take limit matches))}

            :else
            (let [{:keys [target-kind match row] :as selected} (first matches)
                  file (case target-kind
                         :file row
                         :node (file-for-node files row)
                         nil)]
              (cond-> {:schema node-inspect-schema
                       :target target
                       :project {:id project-id}
                       :status :found
                       :match (target-choice target-kind match row)
                       :relationships (incident-graph selected nodes edges limit)}
                (= :file target-kind) (assoc :file (compact-file-row row))
                (= :system target-kind) (assoc :system (compact-system-row row))
                (= :node target-kind) (assoc :node (compact-node-row row))
                file (assoc :sourceLocation (compact-file-row file))
                (= :file target-kind) (assoc :source
                                             (line-numbered-source project
                                                                   row
                                                                   source-lines))
                overlay (assoc :map (map-attachments overlay target selected))))))))))

(defn- explore-create
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        (cursor/create! xtdb
                        {:project-id (project-id project args)
                         :query-text (:query args)
                         :map-path (or (:mapPath args) (:map-path ctx))
                         :budget (or (:budget args) context/default-budget)
                         :retriever :lexical})))))

(defn- explore-open
  [ctx args]
  (let [cursor-id (require-string! args :cursorId "agraph_explore_open requires cursorId.")
        target (require-string! args :target "agraph_explore_open requires target.")]
    (with-xtdb ctx #(cursor/open! % cursor-id target {:budget (:budget args)}))))

(defn- explore-expand
  [ctx args]
  (let [cursor-id (require-string! args :cursorId "agraph_explore_expand requires cursorId.")
        target (require-string! args :target "agraph_explore_expand requires target.")]
    (with-xtdb
      ctx
      #(cursor/expand! %
                       cursor-id
                       target
                       {:budget (:budget args)
                        :relation (:relation args)
                        :limit (:limit args)}))))

(defn- explore-docs
  [ctx args]
  (let [cursor-id (require-string! args :cursorId "agraph_explore_docs requires cursorId.")
        target (require-string! args :target "agraph_explore_docs requires target.")]
    (with-xtdb ctx #(cursor/docs! % cursor-id target {:budget (:budget args)}))))

(defn- explore-search
  [ctx args]
  (let [cursor-id (require-string! args :cursorId "agraph_explore_search requires cursorId.")
        query (require-string! args :query "agraph_explore_search requires query.")]
    (with-xtdb
      ctx
      #(cursor/search! %
                       cursor-id
                       query
                       {:budget (:budget args)
                        :retriever (some-> (:retriever args) keyword)
                        :limit (:limit args)}))))

(defn- view-systems
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        (graph/system-graph xtdb
                            (project-id project args)
                            {:detail (keyword (or (:detail args) "primary"))
                             :limit (or (:limit args) graph/default-node-limit)
                             :map-overlay (map-overlay ctx args)})))))

(defn- sync-inspect
  [ctx args]
  (let [project (read-project! ctx args)
        config-path (config-path ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        {:schema "agraph.project.inspect/v1"
         :project {:id (:id project)
                   :name (:name project)
                   :config-path (or config-path (:path project))}
         :repos (mapv #(select-keys % [:id :root :role]) (:repos project))
         :evidence (evidence/summarize xtdb
                                       project
                                       {:map-overlay (map-overlay ctx args)
                                        :config-path (or config-path (:path project))
                                        :map-path (or (:mapPath args)
                                                      (:map-path ctx))})}))))

(defn- sync-check
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      ctx
      #(project/maintain-project %
                                 project
                                 {:low-confidence-threshold (or (:minConfidence args) 0.60)
                                  :map-overlay (map-overlay ctx args)}))))

(defn- sync-activity
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      ctx
      #(activity/sync-queue! %
                             project
                             {:queue-root (or (:queueDir args)
                                              (:queue-dir ctx))}))))

(defn- work-list
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))]
    (queue/list-summary root
                        {:status (:status args)
                         :project-id (:projectId args)
                         :kind (:kind args)
                         :limit (:limit args)})))

(defn- work-show
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        work-id (require-string! args :workId "agraph_work_show requires workId.")]
    (if-let [found (queue/find-item root work-id)]
      (assoc (queue/item-summary found) :item (:item found))
      {:schema "agraph.queue.error/v1"
       :error "sync work item not found"
       :id work-id})))

(defn- work-pull
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        found (queue/claim-next! root
                                 {:agent-id (or (:agentId args)
                                                (System/getProperty "user.name")
                                                "agent")
                                  :lease-ms (* 60
                                               1000
                                               (long (or (:leaseMinutes args) 30)))
                                  :project-id (:projectId args)
                                  :kind (:kind args)})]
    (or (some-> found queue/item-summary)
        {:schema queue/summary-schema
         :status "empty"
         :root root})))

(defn- work-complete
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        work-id (require-string! args :workId "agraph_work_complete requires workId.")
        result (require-result! args)]
    (queue/item-summary (queue/complete! root work-id result))))

(defn- work-heartbeat
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        work-id (require-string! args :workId "agraph_work_heartbeat requires workId.")]
    (queue/item-summary
     (queue/heartbeat! root
                       work-id
                       {:agent-id (:agentId args)
                        :lease-ms (* 60
                                     1000
                                     (long (or (:leaseMinutes args) 30)))}))))

(defn- work-release
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        work-id (require-string! args :workId "agraph_work_release requires workId.")]
    (queue/item-summary
     (queue/release! root
                     work-id
                     (or (:reason args) "released by MCP agent")))))

(defn- work-reject
  [ctx args]
  (let [root (or (:queueDir args) (:queue-dir ctx))
        work-id (require-string! args :workId "agraph_work_reject requires workId.")
        reason (require-string! args :reason "agraph_work_reject requires reason.")]
    (queue/item-summary (queue/reject! root work-id reason))))

(defn call-tool
  "Call one MCP tool and return its raw AGraph value."
  [ctx name args]
  (case name
    "agraph_explore" (explore ctx args)
    "agraph_node" (node-inspect ctx args)
    "agraph_ask" (ask ctx args)
    "agraph_explore_create" (explore-create ctx args)
    "agraph_explore_open" (explore-open ctx args)
    "agraph_explore_expand" (explore-expand ctx args)
    "agraph_explore_docs" (explore-docs ctx args)
    "agraph_explore_search" (explore-search ctx args)
    "agraph_systems" (view-systems ctx args)
    "agraph_sync_inspect" (sync-inspect ctx args)
    "agraph_status" (sync-inspect ctx args)
    "agraph_sync_check" (sync-check ctx args)
    "agraph_sync_activity" (sync-activity ctx args)
    "agraph_work_list" (work-list ctx args)
    "agraph_work_show" (work-show ctx args)
    "agraph_work_pull" (work-pull ctx args)
    "agraph_work_heartbeat" (work-heartbeat ctx args)
    "agraph_work_complete" (work-complete ctx args)
    "agraph_work_release" (work-release ctx args)
    "agraph_work_reject" (work-reject ctx args)
    (throw (ex-info "Unknown MCP tool."
                    {:schema "agraph.mcp.error/v1"
                     :error "unknown-tool"
                     :tool name}))))

(defn- project-resource
  [file]
  {:uri (str "agraph://project-config?path=" (abs-path file))
   :name (.getName (io/file file))
   :description "AGraph project config"
   :mimeType "application/edn"})

(defn- discover-project-configs
  [root]
  (let [root-file (io/file root)]
    (->> (file-seq root-file)
         (remove #(some #{"node_modules" ".git" ".dev"}
                        (str/split (.getPath %) #"/")))
         (filter #(and (.isFile %)
                       (= "project.edn" (.getName %))))
         (take 20)
         (mapv project-resource))))

(defn- resources-list
  [ctx]
  {:resources (discover-project-configs (:root ctx))})

(defn- parse-project-resource-uri
  [uri]
  (when-let [[_ path] (re-matches #"agraph://project-config\?path=(.+)" (str uri))]
    path))

(defn- resources-read
  [_ctx params]
  (let [uri (:uri params)
        path (parse-project-resource-uri uri)]
    (when (str/blank? (str path))
      (throw (ex-info "Unknown resource URI."
                      {:schema "agraph.mcp.error/v1"
                       :error "unknown-resource"
                       :uri uri})))
    {:contents [{:uri uri
                 :mimeType "application/edn"
                 :text (slurp (io/file path))}]}))

(defn- initialize-result
  [params]
  {:protocolVersion (or (:protocolVersion params) protocol-version)
   :capabilities {:tools {}
                  :resources {}}
   :serverInfo {:name server-name
                :version server-version}
   :instructions server-instructions})

(defn- json-rpc-error
  ([id code message] (json-rpc-error id code message nil))
  ([id code message data]
   (cond-> {:jsonrpc "2.0"
            :id id
            :error {:code code
                    :message message}}
     data (assoc-in [:error :data] data))))

(defn- response
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn handle-message
  "Handle one JSON-RPC MCP message. Notifications return nil."
  [ctx message]
  (let [id (:id message)
        method (:method message)
        params (:params message)]
    (try
      (if (and (nil? id) method)
        nil
        (case method
          "initialize"
          (response id (initialize-result params))

          "ping"
          (response id {})

          "tools/list"
          (response id {:tools (listed-tools ctx)})

          "tools/call"
          (let [name (:name params)
                args (or (:arguments params) {})]
            (response id (tool-packet (call-tool ctx name args))))

          "resources/list"
          (response id (resources-list ctx))

          "resources/read"
          (response id (resources-read ctx params))

          (json-rpc-error id -32601 "Method not found." {:method method})))
      (catch clojure.lang.ExceptionInfo e
        (json-rpc-error id -32602 (ex-message e) (ex-data e)))
      (catch Exception e
        (json-rpc-error id -32603 (ex-message e))))))

(defn- read-json-line
  [line]
  (json/read-json line :key-fn keyword))

(defn- write-message!
  [writer message]
  (when message
    (.write writer (json/write-json-str message))
    (.write writer "\n")
    (.flush writer)))

(defn run-stdio!
  "Run the newline-delimited JSON-RPC MCP stdio transport."
  ([ctx] (run-stdio! ctx *in* *out*))
  ([ctx in out]
   (let [reader (io/reader in)
         writer (io/writer out)]
     (doseq [line (line-seq reader)
             :when (not (str/blank? line))]
       (write-message!
        writer
        (try
          (handle-message ctx (read-json-line line))
          (catch Exception e
            (json-rpc-error nil -32700 "Parse error." {:message (ex-message e)}))))))))

(defn -main
  [& args]
  (run-stdio! (server-context (vec args))))
