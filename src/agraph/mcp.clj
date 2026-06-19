(ns agraph.mcp
  "Minimal MCP stdio server for AGraph packets."
  (:require [agraph.context :as context]
            [agraph.cursor :as cursor]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(def protocol-version
  "2025-03-26")

(def server-name
  "agraph-mcp")

(def server-version
  "0.1.0")

(def default-root
  ".")

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn server-context
  "Return immutable server context from CLI args."
  [args]
  {:root (or (option-value args "--root") default-root)
   :config-path (or (option-value args "--config")
                    (option-value args "--project-config"))
   :map-path (option-value args "--map")
   :queue-dir (or (option-value args "--queue-dir") queue/default-root)
   :storage-path (or (option-value args "--storage") (store/storage-path))})

(defn- json-schema
  [properties required]
  {:type "object"
   :additionalProperties false
   :properties properties
   :required required})

(def tool-definitions
  [{:name "agraph_ask"
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
    :description "Open one target in an existing graph exploration cursor."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :target {:type "string"}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "target"])}
   {:name "agraph_explore_expand"
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
    :description "Open documentation and source snippets for one cursor target."
    :inputSchema (json-schema
                  {:cursorId {:type "string"}
                   :target {:type "string"}
                   :budget {:type "integer"
                            :minimum 1000}}
                  ["cursorId" "target"])}
   {:name "agraph_explore_search"
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
   {:name "agraph_view_systems"
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
    :description "Read and normalize a project config without syncing."
    :inputSchema (json-schema
                  {:configPath {:type "string"}}
                  [])}
   {:name "agraph_sync_check"
    :description "Return the read-only maintenance check report for a project."
    :inputSchema (json-schema
                  {:configPath {:type "string"}
                   :mapPath {:type "string"}
                   :minConfidence {:type "number"}}
                  [])}
   {:name "agraph_work_list"
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
    :description "Return one filesystem queue work item without changing its state."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}}
                  ["workId"])}
   {:name "agraph_work_pull"
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
    :description "Extend the lease for one claimed filesystem queue item."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :agentId {:type "string"}
                   :leaseMinutes {:type "integer"
                                  :minimum 1}}
                  ["workId"])}
   {:name "agraph_work_complete"
    :description "Complete a claimed filesystem queue item with a schema-bearing result object."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :result {:type "object"}}
                  ["workId" "result"])}
   {:name "agraph_work_release"
    :description "Release one claimed filesystem queue item back to ready."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId"])}
   {:name "agraph_work_reject"
    :description "Reject one filesystem queue item with a reason."
    :inputSchema (json-schema
                  {:queueDir {:type "string"}
                   :workId {:type "string"}
                   :reason {:type "string"}}
                  ["workId" "reason"])}])

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

(defn- ask
  [ctx args]
  (let [query (require-string! args :query "agraph_ask requires query.")
        project (read-project! ctx args)]
    (with-xtdb
      ctx
      (fn [xtdb]
        (context/context-packet xtdb
                                query
                                {:project-id (project-id project args)
                                 :retriever (keyword (or (:retriever args) "lexical"))
                                 :map-overlay (map-overlay ctx args)
                                 :budget (or (:budget args) context/default-budget)})))))

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
  (read-project! ctx args))

(defn- sync-check
  [ctx args]
  (let [project (read-project! ctx args)]
    (with-xtdb
      ctx
      #(project/maintain-project %
                                 project
                                 {:low-confidence-threshold (or (:minConfidence args) 0.60)
                                  :map-overlay (map-overlay ctx args)}))))

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
    "agraph_ask" (ask ctx args)
    "agraph_explore_create" (explore-create ctx args)
    "agraph_explore_open" (explore-open ctx args)
    "agraph_explore_expand" (explore-expand ctx args)
    "agraph_explore_docs" (explore-docs ctx args)
    "agraph_explore_search" (explore-search ctx args)
    "agraph_view_systems" (view-systems ctx args)
    "agraph_sync_inspect" (sync-inspect ctx args)
    "agraph_sync_check" (sync-check ctx args)
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
                :version server-version}})

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
          (response id {:tools tool-definitions})

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
