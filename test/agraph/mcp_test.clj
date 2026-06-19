(ns agraph.mcp-test
  (:require [agraph.activity :as activity]
            [agraph.context :as context]
            [agraph.cursor :as cursor]
            [agraph.evidence :as evidence]
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
            "agraph_explore_docs"
            "agraph_explore_search"
            "agraph_view_systems"
            "agraph_sync_inspect"
            "agraph_status"
            "agraph_sync_check"
            "agraph_sync_activity"
            "agraph_work_list"
            "agraph_work_show"
            "agraph_work_pull"
            "agraph_work_heartbeat"
            "agraph_work_complete"
            "agraph_work_release"
            "agraph_work_reject"]
           tool-names))))

(deftest tool-schemas-stay-narrow-and-explicit
  (let [listed (mcp/handle-message (mcp/server-context [])
                                   (request 1 "tools/list" {}))
        schemas (into {} (map (juxt :name :inputSchema)) (get-in listed [:result :tools]))]
    (is (= ["query"] (get-in schemas ["agraph_ask" :required])))
    (is (= ["cursorId" "target"]
           (get-in schemas ["agraph_explore_open" :required])))
    (is (= ["cursorId" "target"]
           (get-in schemas ["agraph_explore_docs" :required])))
    (is (= ["cursorId" "query"]
           (get-in schemas ["agraph_explore_search" :required])))
    (is (contains? (set (keys (get-in schemas ["agraph_sync_inspect" :properties])))
                   :mapPath))
    (is (contains? (set (keys (get-in schemas ["agraph_status" :properties])))
                   :mapPath))
    (is (contains? (set (keys (get-in schemas ["agraph_sync_activity" :properties])))
                   :queueDir))
    (is (= ["workId"]
           (get-in schemas ["agraph_work_show" :required])))
    (is (= ["workId"]
           (get-in schemas ["agraph_work_heartbeat" :required])))
    (is (= ["workId" "result"]
           (get-in schemas ["agraph_work_complete" :required])))
    (is (= ["workId"]
           (get-in schemas ["agraph_work_release" :required])))
    (is (= ["workId" "reason"]
           (get-in schemas ["agraph_work_reject" :required])))
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

(deftest explore-docs-tool-calls-cursor-docs
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                cursor/docs! (fn [xtdb cursor-id target opts]
                               {:schema cursor/packet-schema
                                :xtdb xtdb
                                :cursor {:id cursor-id}
                                :operation :docs
                                :target target
                                :budget (:budget opts)})]
    (let [response (mcp/handle-message
                    (mcp/server-context [])
                    (tool-call 9
                               "agraph_explore_docs"
                               {:cursorId "cursor:abc"
                                :target "API Service"
                                :budget 2000}))
          packet (get-in response [:result :structuredContent])]
      (is (= cursor/packet-schema (:schema packet)))
      (is (= "cursor:abc" (get-in packet [:cursor :id])))
      (is (= "API Service" (:target packet)))
      (is (= 2000 (:budget packet))))))

(deftest explore-search-tool-calls-cursor-search
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                cursor/search! (fn [xtdb cursor-id query opts]
                                 {:schema cursor/packet-schema
                                  :xtdb xtdb
                                  :cursor {:id cursor-id}
                                  :operation :search
                                  :query query
                                  :retriever (:retriever opts)
                                  :limit (:limit opts)})]
    (let [response (mcp/handle-message
                    (mcp/server-context [])
                    (tool-call 10
                               "agraph_explore_search"
                               {:cursorId "cursor:abc"
                                :query "gateway routes"
                                :retriever "lexical"
                                :limit 7}))
          packet (get-in response [:result :structuredContent])]
      (is (= cursor/packet-schema (:schema packet)))
      (is (= "cursor:abc" (get-in packet [:cursor :id])))
      (is (= "gateway routes" (:query packet)))
      (is (= :lexical (:retriever packet)))
      (is (= 7 (:limit packet))))))

(deftest sync-inspect-tool-returns-project-evidence-surface
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :map-path (:map-path opts)
                                      :available [:source-graph :docs]
                                      :counts {:files 2
                                               :nodes 3
                                               :edges 4
                                               :result-schema-mismatch-events 1}
                                      :nextActions [{:kind :activity
                                                     :label "Inspect result schema mismatch activity"
                                                     :count 1
                                                     :command "agraph sync activity project.edn --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 11
                               "agraph_sync_inspect"
                               {:mapPath "agraph.map.json"}))
          packet (get-in response [:result :structuredContent])]
      (is (= "agraph.project.inspect/v1" (:schema packet)))
      (is (= "fixture" (get-in packet [:project :id])))
      (is (= [{:id "app"
               :root "/tmp/app"
               :role :application}]
             (:repos packet)))
      (is (= evidence/schema (get-in packet [:evidence :schema])))
      (is (= "project.edn" (get-in packet [:evidence :config-path])))
      (is (= "agraph.map.json" (get-in packet [:evidence :map-path])))
      (is (= 1 (get-in packet [:evidence :counts :result-schema-mismatch-events])))
      (is (= [{:kind :activity
               :label "Inspect result schema mismatch activity"
               :count 1
               :command "agraph sync activity project.edn --json"}]
             (get-in packet [:evidence :nextActions]))))))

(deftest status-tool-returns-project-evidence-surface
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :map-path (:map-path opts)
                                      :available [:source-graph]
                                      :counts {:files 2
                                               :nodes 3
                                               :edges 4}
                                      :nextActions []})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 12
                               "agraph_status"
                               {:mapPath "agraph.map.json"}))
          packet (get-in response [:result :structuredContent])]
      (is (= "agraph.project.inspect/v1" (:schema packet)))
      (is (= "fixture" (get-in packet [:project :id])))
      (is (= evidence/schema (get-in packet [:evidence :schema])))
      (is (= "project.edn" (get-in packet [:evidence :config-path])))
      (is (= "agraph.map.json" (get-in packet [:evidence :map-path]))))))

(deftest missing-project-returns-structured-error
  (let [response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 11 "agraph_sync_inspect" {}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "agraph.mcp.error/v1"
           (get-in response [:error :data :schema])))
    (is (= "missing-project-config"
           (get-in response [:error :data :error])))))

(deftest sync-activity-tool-imports-queue-activity
  (let [calls (atom [])]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  activity/sync-queue! (fn [xtdb project opts]
                                         (swap! calls conj [:activity xtdb (:id project) opts])
                                         {:schema activity/sync-schema
                                          :project-id (:id project)
                                          :queue-root (:queue-root opts)
                                          :counts {:items 1
                                                   :events 2
                                                   :validation-events 1
                                                   :result-schema-mismatch-events 1}})]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 12
                                 "agraph_sync_activity"
                                 {:queueDir ".dev/test-queue"}))
            packet (get-in response [:result :structuredContent])]
        (is (= activity/sync-schema (:schema packet)))
        (is (= "fixture" (:project-id packet)))
        (is (= ".dev/test-queue" (:queue-root packet)))
        (is (= 1 (get-in packet [:counts :result-schema-mismatch-events])))
        (is (= [[:activity :xtdb "fixture" {:queue-root ".dev/test-queue"}]]
               @calls))))))

(deftest work-complete-rejects-result-without-schema
  (let [response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 12
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
                  (tool-call 13
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

(deftest work-show-returns-summary-and-embedded-item
  (let [root (temp-dir "agraph-mcp-queue-show")
        payload {:schema context/schema
                 :project-id "fixture"
                 :query "where auth"
                 :expectedResultSchema "custom.result/v1"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 14
                             "agraph_work_show"
                             {:queueDir root
                              :workId (:id (:item item))}))
        shown (get-in response [:result :structuredContent])]
    (is (= queue/summary-schema (:schema shown)))
    (is (= (:id (:item item)) (:id shown)))
    (is (= "ready" (:status shown)))
    (is (= "custom.result/v1" (:expected-result-schema shown)))
    (is (= payload (get-in shown [:item :payload])))))

(deftest work-show-returns-queue-error-for-missing-item
  (let [response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 15
                             "agraph_work_show"
                             {:queueDir (temp-dir "agraph-mcp-queue-show-missing")
                              :workId "queue:missing"}))
        shown (get-in response [:result :structuredContent])]
    (is (= "agraph.queue.error/v1" (:schema shown)))
    (is (= "sync work item not found" (:error shown)))
    (is (= "queue:missing" (:id shown)))))

(deftest work-pull-claims-ready-queue-item
  (let [root (temp-dir "agraph-mcp-queue")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 16
                             "agraph_work_pull"
                             {:queueDir root
                              :projectId "fixture"
                              :agentId "agent"}))
        pulled (get-in response [:result :structuredContent])]
    (is (= (:id (:item item)) (:id pulled)))
    (is (= "claimed" (:status pulled)))
    (is (= "agent" (get-in pulled [:lease :agent-id])))))

(deftest work-heartbeat-extends-claimed-queue-item
  (let [root (temp-dir "agraph-mcp-queue-heartbeat")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        _ (queue/claim-next! root {:agent-id "agent"
                                   :project-id "fixture"
                                   :lease-ms 1000})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 17
                             "agraph_work_heartbeat"
                             {:queueDir root
                              :workId item-id
                              :agentId "agent"
                              :leaseMinutes 2}))
        summary (get-in response [:result :structuredContent])]
    (is (= item-id (:id summary)))
    (is (= "claimed" (:status summary)))
    (is (= "agent" (get-in summary [:lease :agent-id])))
    (is (integer? (get-in summary [:lease :heartbeat-at-ms])))))

(deftest work-release-returns-claimed-queue-item-to-ready
  (let [root (temp-dir "agraph-mcp-queue-release")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        _ (queue/claim-next! root {:agent-id "agent"
                                   :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 18
                             "agraph_work_release"
                             {:queueDir root
                              :workId item-id
                              :reason "needs another agent"}))
        summary (get-in response [:result :structuredContent])
        found (queue/find-item root item-id)]
    (is (= item-id (:id summary)))
    (is (= "ready" (:status summary)))
    (is (= "ready" (get-in found [:item :status])))
    (is (= "needs another agent" (get-in found [:item :release-reason])))))

(deftest work-reject-records-reason
  (let [root (temp-dir "agraph-mcp-queue-reject")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        response (mcp/handle-message
                  (mcp/server-context [])
                  (tool-call 19
                             "agraph_work_reject"
                             {:queueDir root
                              :workId item-id
                              :reason "not applicable"}))
        summary (get-in response [:result :structuredContent])
        found (queue/find-item root item-id)]
    (is (= item-id (:id summary)))
    (is (= "rejected" (:status summary)))
    (is (= "rejected" (get-in found [:item :status])))
    (is (= "not applicable" (get-in found [:item :reason])))))

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
