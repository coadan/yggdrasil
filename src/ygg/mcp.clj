(ns ygg.mcp
  "Minimal MCP stdio server for Yggdrasil packets."
  (:require [ygg.activity :as activity]
            [ygg.context :as context]
            [ygg.corrections :as corrections]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.plugin-package-view :as plugin-package-view]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.query :as query]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:gen-class))

(def protocol-version
  "2025-03-26")

(def server-name
  "ygg-mcp")

(def server-version
  "0.1.0")

(def server-instructions
  (str "Use ygg_query first for structural coding questions when a project "
       "graph exists. Check freshness, evidence.families, evidence.planes, "
       "and nextActions before trusting missing evidence. Treat returned "
       "systems as the work-area orientation, architecture as auditable "
       "evidence, snippets as already-read source context, and relationships "
       "as nearby mechanical edges before broad grep. "
       "Use ygg_node for one exact file, node, package, system, or "
       "evidence target; ambiguous labels return choices. Use ygg_status for "
       "graph freshness, basis, query-index readiness, evidence-family readiness, "
       "coverage, plugin package caveats, and next actions. "
       "When coverage or nextActions show skipped unsupported source, follow "
       "the surfaced plugin workflow commands: registry/list, gap, new, "
       "dry-run, diagnose, and core-check. Extractor plugins may enhance core "
       "rows or add unsupported file-family rows after core extraction; "
       "unbenchmarked or project-local plugin output is non-authoritative "
       "review evidence. "
       "Use ygg_systems for a compact systems view. Treat Yggdrasil output as "
       "mechanical facts plus "
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
      (System/getenv "YGG_MCP_TOOLS")
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
   :project-id (option-value args "--project")
   :queue-dir (or (option-value args "--queue-dir") queue/default-root)
   :storage-path (option-value args "--storage")
   :tool-groups (parse-tool-groups (configured-tool-groups args))})

(defn- json-schema
  [properties required]
  {:type "object"
   :additionalProperties false
   :properties properties
   :required required})

(def tool-definitions
  [{:name "ygg_query"
    :groups #{:default}
    :description "Return the primary one-shot Yggdrasil context packet for an agent question."
    :inputSchema (json-schema
                  {:query {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :retriever {:type "string"
                               :enum ["lexical" "auto" "hybrid" "semantic"]}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["query"])}
   {:name "ygg_node"
    :groups #{:default}
    :description "Inspect one exact graph node or source file target with mechanical neighbors and source context."
    :inputSchema (json-schema
                  {:target {:type "string"}
                   :projectId {:type "string"}
                   :configPath {:type "string"}
                   :limit {:type "integer"
                           :minimum 1}
                   :sourceLines {:type "integer"
                                 :minimum 1}}
                  ["target"])}
   {:name "ygg_systems"
    :groups #{:default}
    :description "Return the canonical ygg.graph/v2 systems graph JSON."
    :inputSchema (json-schema
                  {:projectId {:type "string"}
                   :configPath {:type "string"}
                   :detail {:type "string"
                            :enum ["primary" "expanded" "evidence" "raw"]}
                   :limit {:type "integer"
                           :minimum 1}}
                  [])}
   {:name "ygg_sync_inspect"
    :groups #{:sync}
    :description "Return project config plus the current mechanical evidence surface without syncing."
    :inputSchema (json-schema
                  {:configPath {:type "string"}}
                  [])}
   {:name "ygg_status"
    :groups #{:default}
    :description "Return agent-facing freshness, query-index readiness, evidence surface, coverage, and next actions without syncing."
    :inputSchema (json-schema
                  {:configPath {:type "string"}}
                  [])}
   {:name "ygg_sync_check"
    :groups #{:sync}
    :description "Return the read-only maintenance check report for a project."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :minConfidence {:type "number"}}
                  [])}
   {:name "ygg_sync_activity"
    :groups #{:sync}
    :description "Import filesystem queue lifecycle and result audit facts into local activity rows."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :queueDir {:type "string"}}
                  [])}
   {:name "ygg_work_list"
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
   {:name "ygg_work_show"
    :groups #{:work}
    :description "Return one filesystem queue work item without changing its state."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}}
                  ["workId"])}
   {:name "ygg_work_pull"
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
   {:name "ygg_work_heartbeat"
    :groups #{:work}
    :description "Extend the lease for one claimed filesystem queue item."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :agentId {:type "string"}
                   :leaseMinutes {:type "integer"
                                  :minimum 1}}
                  ["workId"])}
   {:name "ygg_work_complete"
    :groups #{:work}
    :description "Complete a claimed filesystem queue item with a schema-bearing result object."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :result {:type "object"}}
                  ["workId" "result"])}
   {:name "ygg_work_release"
    :groups #{:work}
    :description "Release one claimed filesystem queue item back to ready."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId"])}
   {:name "ygg_work_reject"
    :groups #{:work}
    :description "Reject one filesystem queue item with a reason."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId" "reason"])}])

(defn- tool-visible?
  [groups tool]
  (boolean
   (or (contains? groups :all)
       (seq (set/intersection groups (:groups tool))))))

(def ^:private tool-definitions-by-name
  (into {} (map (juxt :name identity)) tool-definitions))

(defn- tool-definition
  [tool-name]
  (get tool-definitions-by-name tool-name))

(defn- listed-tools
  [ctx]
  (->> tool-definitions
       (filter #(tool-visible? (:tool-groups ctx) %))
       (mapv #(dissoc % :groups))))

(defn- ensure-tool-enabled!
  [ctx tool-name]
  (let [tool (tool-definition tool-name)]
    (when-not tool
      (throw (ex-info "Unknown MCP tool."
                      {:schema "ygg.mcp.error/v1"
                       :error "unknown-tool"
                       :tool tool-name})))
    (when-not (tool-visible? (:tool-groups ctx) tool)
      (throw (ex-info "MCP tool is not enabled."
                      {:schema "ygg.mcp.error/v1"
                       :error "tool-not-enabled"
                       :tool tool-name
                       :enabledGroups (mapv name (sort-by name (:tool-groups ctx)))
                       :requiredGroups (mapv name (sort-by name (:groups tool)))
                       :hint "Start ygg-mcp with --tools default,sync,work or YGG_MCP_TOOLS=all."})))
    tool))

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
    (if-not (str/blank? (str path))
      (project/read-project path)
      (let [project-id (or (:projectId args) (:project-id ctx))]
        (if (str/blank? (str project-id))
          (throw (ex-info "Missing Yggdrasil project config."
                          {:schema "ygg.mcp.error/v1"
                           :error "missing-project-config"
                           :hint "Start ygg-mcp with --config project.edn or pass projectId."}))
          (:project (registry/resolve-project
                     {:project-id project-id
                      :cwd (abs-path (:root ctx))})))))))

(defn- project-id
  [project args]
  (or (:projectId args) (:id project)))

(defn- queue-project-id
  [ctx args]
  (or (:projectId args)
      (:project-id ctx)
      (when-let [path (config-path ctx args)]
        (try
          (:id (project/read-project path))
          (catch Exception _
            nil)))))

(defn- queue-root
  [ctx args]
  (or (:queueDir args)
      (some-> (queue-project-id ctx args) store/project-sqlite-path)
      (:queue-dir ctx)))

(defn- correction-overlay
  [xtdb project args]
  (corrections/overlay xtdb (project-id project args)))

(defn- context-packet-freshness
  [xtdb project ctx args overlay]
  (let [config-path (config-path ctx args)
        summary (evidence/summarize xtdb
                                    project
                                    {:correction-overlay overlay
                                     :config-path (or config-path
                                                      (:path project))
                                     :summary? true})]
    (evidence/packet-freshness summary)))

(defn- with-xtdb
  [ctx f]
  (let [project-id (or (:project-id ctx) (System/getenv "YGG_PROJECT_ID"))]
    (store/with-node (or (:storage-path ctx)
                         (store/storage-path project-id))
      f)))

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
                      {:schema "ygg.mcp.error/v1"
                       :error "invalid-arguments"
                       :field (name key)})))
    value))

(defn- require-result!
  [args]
  (let [result (:result args)]
    (when-not (map? result)
      (throw (ex-info "work_complete result must be a JSON object."
                      {:schema "ygg.mcp.error/v1"
                       :error "invalid-result"
                       :field "result"})))
    (when (str/blank? (str (:schema result)))
      (throw (ex-info "work_complete result must include a schema field."
                      {:schema "ygg.mcp.error/v1"
                       :error "invalid-result"
                       :field "result.schema"})))
    result))

(defn- context-query-packet
  [ctx args]
  (let [query (require-string! args :query "Yggdrasil context query requires query.")
        project (read-project! ctx args)]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      (fn [xtdb]
        (let [overlay (correction-overlay xtdb project args)]
          (context/context-packet xtdb
                                  query
                                  {:project-id (project-id project args)
                                   :retriever (keyword (or (:retriever args) "lexical"))
                                   :correction-overlay overlay
                                   :budget (or (:budget args) context/default-budget)
                                   :plugins (:plugins project)
                                   :freshness (context-packet-freshness xtdb
                                                                        project
                                                                        ctx
                                                                        args
                                                                        overlay)}))))))

(defn- query-context
  [ctx args]
  (context-query-packet ctx args))

(def node-inspect-schema
  "ygg.node.inspect/v1")

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

(defn- compact-package-row
  [node]
  (select-keys node [:xt/id :project-id :repo-id :label :kind :file-id :path
                     :ecosystem :package-name :version-range :resolved-version
                     :dependency-scope :import-names :source-line :run-id]))

(defn- compact-evidence-row
  [evidence]
  (select-keys evidence [:xt/id :project-id :repo-id :system-id :file-id :path
                         :file-kind :kind :url-context :auth-context :label
                         :normalized-value :source-line :confidence :run-id]))

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
    (:ecosystem row) (assoc :ecosystem (:ecosystem row))
    (:package-name row) (assoc :packageName (:package-name row))
    (:system-id row) (assoc :systemId (:system-id row))
    (:source-line row) (assoc :sourceLine (:source-line row))))

(defn- package-node?
  [node]
  (and (seq (:package-name node))
       (:ecosystem node)))

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
       (remove package-node?)
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

(defn- package-targets
  [node]
  (let [ecosystem (some-> (:ecosystem node) name)
        package-name (:package-name node)]
    (remove nil?
            [(:xt/id node)
             (:label node)
             package-name
             (when (and ecosystem package-name)
               (str ecosystem ":" package-name))])))

(defn- exact-package-matches
  [target nodes]
  (->> nodes
       (filter package-node?)
       (keep (fn [node]
               (when-let [matched (some #(when (= target %) %) (package-targets node))]
                 [(cond
                    (= matched (:xt/id node)) :id
                    (= matched (:label node)) :label
                    (= matched (:package-name node)) :package-name
                    :else :ecosystem-package)
                  node])))
       (distinct-by (comp :xt/id second))
       (mapv (fn [[match node]]
               {:target-kind :package
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

(defn- exact-evidence-matches
  [target evidence]
  (->> evidence
       (keep (fn [row]
               (when (= target (:xt/id row))
                 [:id row])))
       (distinct-by (comp :xt/id second))
       (mapv (fn [[match row]]
               {:target-kind :evidence
                :match match
                :row row}))))

(defn- file-rows-by-constraints
  [xtdb project-id constraints]
  (->> (store/constrained-rows xtdb
                               (:files store/tables)
                               (assoc constraints :project-id project-id))
       (filter active-row?)
       vec))

(defn- split-repo-path-target
  [target]
  (when-let [idx (str/index-of target ":")]
    (let [repo-id (subs target 0 idx)
          path (subs target (inc idx))]
      (when (and (not (str/blank? repo-id))
                 (not (str/blank? path)))
        {:repo-id repo-id
         :path path}))))

(defn- target-file-candidates
  [xtdb project-id target]
  (->> (concat (file-rows-by-constraints xtdb project-id {:xt/id target})
               (file-rows-by-constraints xtdb project-id {:path target})
               (when-let [repo-path (split-repo-path-target target)]
                 (file-rows-by-constraints xtdb project-id repo-path)))
       (distinct-by :xt/id)
       vec))

(defn- package-target-parts
  [target]
  (when-let [idx (str/index-of target ":")]
    (let [ecosystem (subs target 0 idx)
          package-name (subs target (inc idx))]
      (when (and (not (str/blank? ecosystem))
                 (not (str/blank? package-name)))
        {:ecosystem ecosystem
         :package-name package-name}))))

(defn- node-target-candidates
  [xtdb project-id target]
  (let [scope {:project-id project-id}
        package-parts (package-target-parts target)]
    (->> (concat (query/nodes-by-ids xtdb [target] scope)
                 (query/nodes-by-labels xtdb [target] scope)
                 (query/nodes-by-namespaces xtdb [target] scope)
                 (query/nodes-by-names xtdb [target] scope)
                 (query/nodes-by-package-names xtdb [target] scope)
                 (when package-parts
                   (->> (query/nodes-by-package-names
                         xtdb
                         [(:package-name package-parts)]
                         scope)
                        (filter #(= (:ecosystem package-parts)
                                    (some-> (:ecosystem %) name))))))
         (filter active-row?)
         (distinct-by :xt/id)
         vec)))

(defn- inspect-matches
  [target files nodes evidence overlay]
  (->> (concat (exact-file-matches target files)
               (exact-evidence-matches target evidence)
               (exact-system-matches target overlay)
               (exact-package-matches target nodes)
               (exact-node-matches target nodes))
       (sort-by (fn [{:keys [target-kind match row]}]
                  [(case target-kind :file 0 :evidence 1 :system 2 :package 3 :node 4 5)
                   (case match
                     :id 0
                     :repo-path 1
                     :ecosystem-package 2
                     :path 3
                     :label 4
                     :package-name 5
                     6)
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

(defn- source-window
  [lines source-lines center-line]
  (let [total (count lines)
        limit (max 1 (long source-lines))
        center-line (some-> center-line long)
        half (quot limit 2)
        start (if center-line
                (max 1 (- center-line half))
                1)
        end (min total (+ start limit -1))
        start (max 1 (- end limit -1))]
    (if (zero? total)
      {:start 1
       :end 0
       :truncated false
       :focus-line center-line
       :lines []}
      {:start start
       :end end
       :truncated (or (> start 1) (< end total))
       :focus-line center-line
       :lines (mapv (fn [line text]
                      {:line line
                       :text text})
                    (range start (inc end))
                    (subvec (vec lines) (dec start) end))})))

(defn- line-numbered-source
  ([project file source-lines]
   (line-numbered-source project file source-lines {}))
  ([project file source-lines {:keys [center-line]}]
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
         (let [window (source-window (str/split-lines (slurp source-file))
                                     source-lines
                                     center-line)]
           (cond-> {:status :available
                    :path path
                    :truncated (:truncated window)
                    :lines (:lines window)
                    :lineRange {:start (:start window)
                                :end (:end window)}}
             (:focus-line window) (assoc :focusLine (:focus-line window))))))
     {:status :unavailable
      :reason "repo-root-missing"})))

(defn- file-for-node
  [files node]
  (or (some #(when (= (:file-id node) (:xt/id %)) %) files)
      (some #(when (and (= (:repo-id node) (:repo-id %))
                        (= (:path node) (:path %)))
               %)
            files)))

(defn- file-for-evidence
  [files evidence]
  (or (some #(when (= (:file-id evidence) (:xt/id %)) %) files)
      (some #(when (and (= (:repo-id evidence) (:repo-id %))
                        (= (:path evidence) (:path %)))
               %)
            files)))

(defn- relationship-row-scope
  [project-id row]
  (cond-> {:project-id project-id}
    (:repo-id row) (assoc :repo-id (:repo-id row))))

(defn- source-file-candidates
  [xtdb project-id row]
  (->> (concat (when-let [file-id (:file-id row)]
                 (file-rows-by-constraints xtdb project-id {:xt/id file-id}))
               (when (and (:repo-id row) (:path row))
                 (file-rows-by-constraints xtdb
                                           project-id
                                           {:repo-id (:repo-id row)
                                            :path (:path row)}))
               (when-let [path (:path row)]
                 (file-rows-by-constraints xtdb project-id {:path path})))
       (distinct-by :xt/id)
       vec))

(defn- selected-source-file
  [xtdb project-id files target-kind row]
  (case target-kind
    :file row
    :evidence (or (file-for-evidence files row)
                  (file-for-evidence (source-file-candidates xtdb project-id row)
                                     row))
    :package (or (file-for-node files row)
                 (file-for-node (source-file-candidates xtdb project-id row)
                                row))
    :node (or (file-for-node files row)
              (file-for-node (source-file-candidates xtdb project-id row)
                             row))
    nil))

(defn- project-scope
  [project-id]
  {:project-id project-id})

(defn- match-file-id
  [{:keys [target-kind row]}]
  (case target-kind
    :file (:xt/id row)
    (:file-id row)))

(defn- relationship-file-nodes
  [xtdb project-id match]
  (let [row (:row match)
        scope (relationship-row-scope project-id row)]
    (->> (concat (when-let [file-id (match-file-id match)]
                   (query/nodes-by-file-ids xtdb [file-id] scope))
                 (when-let [path (:path row)]
                   (query/nodes-by-paths xtdb [path] scope))
                 (when (#{:node :package} (:target-kind match))
                   [row]))
         (distinct-by :xt/id)
         vec)))

(defn- relationship-file-edges
  [xtdb project-id match]
  (let [row (:row match)
        scope (relationship-row-scope project-id row)]
    (->> (concat (when-let [file-id (match-file-id match)]
                   (query/edges-by-file-ids xtdb [file-id] scope))
                 (when-let [path (:path row)]
                   (query/edges-by-paths xtdb [path] scope)))
         (distinct-by :xt/id)
         vec)))

(defn- focus-node-ids
  [match file-id nodes]
  (->> (case (:target-kind match)
         :package [(get-in match [:row :xt/id])]
         :node [(get-in match [:row :xt/id])]
         :file (->> nodes
                    (filter #(= file-id (:file-id %)))
                    (map :xt/id))
         [])
       (remove nil?)
       set))

(defn- incident-graph
  [xtdb project-id match limit]
  (let [file-id (match-file-id match)
        file-nodes (relationship-file-nodes xtdb project-id match)
        focus-node-ids (focus-node-ids match file-id file-nodes)
        edges (->> (concat (relationship-file-edges xtdb project-id match)
                           (when (seq focus-node-ids)
                             (query/edges-touching-node-ids xtdb
                                                            focus-node-ids
                                                            (project-scope project-id))))
                   (distinct-by :xt/id)
                   vec)
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
                                              selected-edges)))
        endpoint-nodes (query/nodes-by-ids xtdb
                                           related-node-ids
                                           (project-scope project-id))
        nodes-by-id (into {} (map (juxt :xt/id identity))
                          (distinct-by :xt/id
                                       (concat file-nodes endpoint-nodes)))]
    {:nodes (->> related-node-ids
                 (keep nodes-by-id)
                 (sort-by (juxt :repo-id :path :source-line :label :xt/id))
                 (take limit)
                 (mapv compact-node-row))
     :edges (mapv #(edge-ref nodes-by-id %) selected-edges)}))

(defn- compact-system-node
  [node]
  (select-keys node [:id :label :kind :repo :repoRole :path :pathPrefix
                     :clusterId :clusterLabel :degree :score :source :reason
                     :tags :metrics]))

(defn- compact-system-edge
  [edge]
  (select-keys edge [:id :source :target :relation :confidence :salience
                     :visibility :rules :evidence :evidenceCounts :relations
                     :salienceReasons]))

(defn- system-relationships
  [xtdb project-id overlay system-id limit]
  (let [data (graph/system-graph xtdb
                                 project-id
                                 {:detail :expanded
                                  :limit graph/default-node-limit
                                  :correction-overlay overlay})
        nodes-by-id (into {} (map (juxt :id identity)) (:nodes data))
        incident-edges (->> (:edges data)
                            (filter #(or (= system-id (:source %))
                                         (= system-id (:target %))))
                            (sort-by (juxt :relation :source :target :id))
                            (take limit)
                            vec)
        related-ids (set (concat [system-id]
                                 (mapcat (juxt :source :target) incident-edges)))]
    {:nodes (->> related-ids
                 (keep nodes-by-id)
                 (sort-by (fn [node]
                            [(if (= system-id (:id node)) 0 1)
                             (:repo node)
                             (:pathPrefix node)
                             (:label node)
                             (:id node)]))
                 (take limit)
                 (mapv compact-system-node))
     :edges (mapv compact-system-edge incident-edges)}))

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
                  (:system-id row)
                  (:namespace row)
                  (:name row)
                  (repo-path row)]))))

(defn- doc-source-row
  [doc]
  (let [source (:source doc)
        repo-id (or (:repo-id source)
                    (:repo source))
        path (:path source)]
    (when (and repo-id path)
      {:repo-id repo-id
       :path path})))

(defn- doc-source-line
  [doc]
  (or (get-in doc [:source :source-line])
      (get-in doc [:source :sourceLine])))

(defn- map-doc-row
  [project source-lines doc]
  (let [source-row (doc-source-row doc)]
    (cond-> (select-keys doc [:target :role :source :status :reason])
      source-row (assoc :sourceWindow
                        (line-numbered-source project
                                              source-row
                                              source-lines
                                              {:center-line (doc-source-line doc)})))))

(defn- correction-attachments
  [project source-lines overlay target match]
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
                  (mapv #(map-doc-row project source-lines %)))
       :edges (->> (:edges overlay)
                   (filter edge-match?)
                   (mapv #(select-keys % [:id :source :target :relation :status
                                          :reason :evidence])))})))

(defn- node-inspect
  [ctx args]
  (let [target (require-string! args :target "ygg_node requires target.")
        project (read-project! ctx args)
        project-id (project-id project args)
        limit (long (or (:limit args) default-node-inspect-limit))
        source-lines (long (or (:sourceLines args) default-node-source-lines))]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      (fn [xtdb]
        (let [overlay (correction-overlay xtdb project args)
              files (target-file-candidates xtdb project-id target)
              nodes (node-target-candidates xtdb project-id target)
              evidence (->> (query/system-evidence-by-ids xtdb
                                                          [target]
                                                          {:project-id project-id})
                            (filter active-row?)
                            vec)
              matches (inspect-matches target files nodes evidence overlay)]
          (cond
            (empty? matches)
            {:schema node-inspect-schema
             :target target
             :project {:id project-id}
             :status :not-found
             :choices []
             :nextActions [{:kind :query
                            :label "Search for a graph context packet"
                            :mcpTool "ygg_query"
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
                  file (selected-source-file xtdb
                                             project-id
                                             files
                                             target-kind
                                             row)
                  related-system-id (case target-kind
                                      :system (:id row)
                                      :evidence (:system-id row)
                                      nil)]
              (cond-> {:schema node-inspect-schema
                       :target target
                       :project {:id project-id}
                       :status :found
                       :match (target-choice target-kind match row)
                       :relationships (incident-graph xtdb
                                                      project-id
                                                      selected
                                                      limit)}
                related-system-id (assoc :systemRelationships
                                         (system-relationships xtdb
                                                               project-id
                                                               overlay
                                                               related-system-id
                                                               limit))
                (= :file target-kind) (assoc :file (compact-file-row row))
                (= :evidence target-kind) (assoc :evidence (compact-evidence-row row))
                (= :system target-kind) (assoc :system (compact-system-row row))
                (= :package target-kind) (assoc :package (compact-package-row row))
                (= :node target-kind) (assoc :node (compact-node-row row))
                file (assoc :sourceLocation (compact-file-row file))
                file (assoc :source
                            (line-numbered-source project
                                                  file
                                                  source-lines
                                                  (if (= :file target-kind)
                                                    {}
                                                    {:center-line (:source-line row)})))
                overlay (assoc :corrections
                               (correction-attachments project
                                                       source-lines
                                                       overlay
                                                       target
                                                       selected))))))))))

(defn- view-systems
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      (fn [xtdb]
        (graph/system-graph xtdb
                            (project-id project args)
                            {:detail (keyword (or (:detail args) "primary"))
                             :limit (or (:limit args) graph/default-node-limit)
                             :correction-overlay (correction-overlay xtdb project args)})))))

(defn- plugin-package-caveats
  [project]
  (plugin-package-view/caveats (project/plugin-packages project)))

(defn- sync-inspect
  [ctx args]
  (let [project (read-project! ctx args)
        config-path (config-path ctx args)]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      (fn [xtdb]
        (let [evidence-summary (evidence/summarize xtdb
                                                   project
                                                   {:correction-overlay (correction-overlay xtdb
                                                                                            project
                                                                                            args)
                                                    :config-path (or config-path
                                                                     (:path project))
                                                    :summary? true})]
          {:schema "ygg.project.inspect/v1"
           :project {:id (:id project)
                     :name (:name project)
                     :config-path (or config-path (:path project))}
           :repos (mapv #(select-keys % [:id :root :role]) (:repos project))
           :pluginPackages (plugin-package-caveats project)
           :freshness (:freshness evidence-summary)
           :coverage (evidence/status-coverage evidence-summary)
           :nextActions (:nextActions evidence-summary)
           :evidence evidence-summary})))))

(defn- sync-check
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      #(project/maintain-project %
                                 project
                                 {:low-confidence-threshold (or (:minConfidence args) 0.60)
                                  :correction-overlay (correction-overlay % project args)}))))

(defn- sync-activity
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      (assoc ctx :project-id (:id project))
      #(activity/sync-queue! %
                             project
                             {:queue-root (queue-root
                                           (assoc ctx :project-id (:id project))
                                           args)}))))

(defn- work-list
  [ctx args]
  (let [root (queue-root ctx args)]
    (queue/list-summary root
                        {:status (:status args)
                         :project-id (:projectId args)
                         :kind (:kind args)
                         :limit (:limit args)})))

(defn- work-show
  [ctx args]
  (let [root (queue-root ctx args)
        work-id (require-string! args :workId "ygg_work_show requires workId.")]
    (if-let [found (queue/find-item root work-id)]
      (assoc (queue/item-summary found) :item (:item found))
      {:schema "ygg.queue.error/v1"
       :error "sync work item not found"
       :id work-id})))

(defn- work-pull
  [ctx args]
  (let [root (queue-root ctx args)
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
  (let [root (queue-root ctx args)
        work-id (require-string! args :workId "ygg_work_complete requires workId.")
        result (require-result! args)]
    (queue/item-summary (queue/complete! root work-id result))))

(defn- work-heartbeat
  [ctx args]
  (let [root (queue-root ctx args)
        work-id (require-string! args :workId "ygg_work_heartbeat requires workId.")]
    (queue/item-summary
     (queue/heartbeat! root
                       work-id
                       {:agent-id (:agentId args)
                        :lease-ms (* 60
                                     1000
                                     (long (or (:leaseMinutes args) 30)))}))))

(defn- work-release
  [ctx args]
  (let [root (queue-root ctx args)
        work-id (require-string! args :workId "ygg_work_release requires workId.")]
    (queue/item-summary
     (queue/release! root
                     work-id
                     (or (:reason args) "released by MCP agent")))))

(defn- work-reject
  [ctx args]
  (let [root (queue-root ctx args)
        work-id (require-string! args :workId "ygg_work_reject requires workId.")
        reason (require-string! args :reason "ygg_work_reject requires reason.")]
    (queue/item-summary (queue/reject! root work-id reason))))

(defn call-tool
  "Call one MCP tool and return its raw Yggdrasil value."
  [ctx name args]
  (ensure-tool-enabled! ctx name)
  (case name
    "ygg_query" (query-context ctx args)
    "ygg_node" (node-inspect ctx args)
    "ygg_systems" (view-systems ctx args)
    "ygg_sync_inspect" (sync-inspect ctx args)
    "ygg_status" (sync-inspect ctx args)
    "ygg_sync_check" (sync-check ctx args)
    "ygg_sync_activity" (sync-activity ctx args)
    "ygg_work_list" (work-list ctx args)
    "ygg_work_show" (work-show ctx args)
    "ygg_work_pull" (work-pull ctx args)
    "ygg_work_heartbeat" (work-heartbeat ctx args)
    "ygg_work_complete" (work-complete ctx args)
    "ygg_work_release" (work-release ctx args)
    "ygg_work_reject" (work-reject ctx args)))

(defn- project-resource
  [file]
  {:uri (str "ygg://project-config?path=" (abs-path file))
   :name (.getName (io/file file))
   :description "Yggdrasil project config"
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
  (when-let [[_ path] (re-matches #"ygg://project-config\?path=(.+)" (str uri))]
    path))

(defn- resources-read
  [_ctx params]
  (let [uri (:uri params)
        path (parse-project-resource-uri uri)]
    (when (str/blank? (str path))
      (throw (ex-info "Unknown resource URI."
                      {:schema "ygg.mcp.error/v1"
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
