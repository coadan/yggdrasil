(ns agraph.mcp-test
  (:require [agraph.context :as context]
            [agraph.graph :as graph]
            [agraph.mcp :as mcp]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def project-fixture
  {:id "fixture"
   :name "Fixture"
   :path "project.edn"
   :repos [{:id "app"
            :root "/tmp/app"
            :role :application}]})

(defn- request
  [id method params]
  {:jsonrpc "2.0"
   :id id
   :method method
   :params params})

(defn- tool-call
  [id name arguments]
  (request id "tools/call" {:name name
                            :arguments arguments}))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest initialize-and-list-tools-use-mcp-envelope
  (let [ctx (mcp/server-context [])
        init (mcp/handle-message ctx
                                 (request 1
                                          "initialize"
                                          {:protocolVersion "2025-03-26"}))
        listed (mcp/handle-message ctx (request 2 "tools/list" {}))
        tool-names (mapv :name (get-in listed [:result :tools]))]
    (is (= "2.0" (:jsonrpc init)))
    (is (= "2025-03-26" (get-in init [:result :protocolVersion])))
    (is (= {:name "agraph-mcp"
            :version "0.1.0"}
           (get-in init [:result :serverInfo])))
    (is (= ["agraph_ask"
            "agraph_explore_create"
            "agraph_explore_open"
            "agraph_explore_expand"
            "agraph_view_systems"
            "agraph_sync_inspect"
            "agraph_sync_check"
            "agraph_work_list"
            "agraph_work_pull"
            "agraph_work_complete"]
           tool-names))))

(deftest tool-schemas-stay-narrow-and-explicit
  (let [listed (mcp/handle-message (mcp/server-context [])
                                   (request 1 "tools/list" {}))
        schemas (into {} (map (juxt :name :inputSchema)) (get-in listed [:result :tools]))]
    (is (= ["query"] (get-in schemas ["agraph_ask" :required])))
    (is (= ["cursorId" "target"]
           (get-in schemas ["agraph_explore_open" :required])))
    (is (= ["workId" "result"]
           (get-in schemas ["agraph_work_complete" :required])))
    (is (every? #(= false (:additionalProperties %)) (vals schemas)))))

(deftest ask-tool-returns-context-packet
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                context/context-packet (fn [xtdb query-text opts]
                                         {:schema context/schema
                                          :xtdb xtdb
                                          :query query-text
                                          :project-id (:project-id opts)
                                          :retriever (name (:retriever opts))})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 7
                               "agraph_ask"
                               {:query "where auth"
                                :retriever "lexical"}))
          packet (get-in response [:result :structuredContent])]
      (is (= context/schema (:schema packet)))
      (is (= "fixture" (:project-id packet)))
      (is (= "where auth" (:query packet)))
      (is (= context/schema
             (:schema (json/read-json (get-in response [:result :content 0 :text])
                                      :key-fn keyword)))))))

(deftest view-systems-tool-returns-canonical-graph
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                graph/system-graph (fn [xtdb project-id opts]
                                     {:schema graph/schema
                                      :xtdb xtdb
                                      :project-id project-id
                                      :basis {:detail (name (:detail opts))}
                                      :nodes []
                                      :edges []})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 8
                               "agraph_view_systems"
                               {:detail "expanded"}))
          packet (get-in response [:result :structuredContent])]
      (is (= graph/schema (:schema packet)))
      (is (= "fixture" (:project-id packet)))
      (is (= "expanded" (get-in packet [:basis :detail]))))))

(deftest missing-project-returns-structured-error
  (let [response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 9 "agraph_sync_inspect" {}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "agraph.mcp.error/v1"
           (get-in response [:error :data :schema])))
    (is (= "missing-project-config"
           (get-in response [:error :data :error])))))

(deftest work-complete-rejects-result-without-schema
  (let [response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 10
                             "agraph_work_complete"
                             {:workId "queue:item"
                              :result {:ok true}}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "invalid-result" (get-in response [:error :data :error])))
    (is (= "result.schema" (get-in response [:error :data :field])))))

(deftest work-list-returns-actionable-queue-summaries
  (let [root (temp-dir "agraph-mcp-queue-list")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 11
                             "agraph_work_list"
                             {:queueDir root
                              :projectId "fixture"
                              :kind "context"}))
        listed (get-in response [:result :structuredContent])
        first-item (first (:items listed))]
    (is (= queue/list-schema (:schema listed)))
    (is (= [(:id (:item item))] (mapv :id (:items listed))))
    (is (= "ready" (:status first-item)))
    (is (some #(= :claim (:kind %)) (:actions first-item)))
    (is (= "ready" (get-in (queue/find-item root (:id first-item))
                           [:item :status])))))

(deftest work-pull-claims-ready-queue-item
  (let [root (temp-dir "agraph-mcp-queue")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 12
                             "agraph_work_pull"
                             {:queueDir root
                              :projectId "fixture"
                              :agentId "agent"}))
        pulled (get-in response [:result :structuredContent])]
    (is (= (:id (:item item)) (:id pulled)))
    (is (= "claimed" (:status pulled)))
    (is (= "agent" (get-in pulled [:lease :agent-id])))))

(deftest stdio-loop-reads-and-writes-newline-delimited-json
  (let [in (java.io.StringReader.
            (str (json/write-json-str (request 1 "tools/list" {})) "\n"))
        out (java.io.StringWriter.)]
    (mcp/run-stdio! (mcp/server-context []) in out)
    (let [lines (str/split-lines (str out))
          parsed (json/read-json (first lines) :key-fn keyword)]
      (is (= 1 (count lines)))
      (is (= 1 (:id parsed)))
      (is (seq (get-in parsed [:result :tools]))))))
