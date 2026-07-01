(ns ygg.mcp-test
  (:require [ygg.activity :as activity]
            [ygg.context :as context]
            [ygg.corrections :as corrections]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.mcp :as mcp]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.query :as query]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def project-fixture
  {:id "fixture"
   :name "Fixture"
   :path "project.edn"
   :repos [{:id "app"
            :root "/tmp/app"
            :role :application}]})

(deftest direct-clojure-main-requires-server-backed-mcp
  (let [response (mcp/direct-main-response [])]
    (is (= daemon-contract/unavailable-exit (:exit response)))
    (is (= "" (:out response)))
    (is (str/includes? (:err response)
                       "Direct ygg.mcp entrypoint is disabled."))
    (is (str/includes? (:err response) "ygg init"))
    (is (str/includes? (:err response) "ygg start"))
    (is (str/includes? (:err response) "ygg-mcp"))))

(deftest server-context-uses-canonical-config-flag
  (is (= "project.edn"
         (:config-path (mcp/server-context ["--config" "project.edn"]))))
  (is (nil? (:config-path (mcp/server-context ["--project-config" "legacy.edn"]))))
  (is (nil? (:queue-dir (mcp/server-context []))))
  (is (not (contains? (mcp/server-context ["--queue-dir" "/tmp/queue"])
                      :queue-dir)))
  (is (not (contains? (mcp/server-context ["--storage" "/tmp/xtdb"])
                      :storage-path))))

(def plugin-package-fixture
  {:id "datastar-hiccup"
   :version "0.1.0"
   :path ".dev/ygg/plugins/cache/datastar"
   :source {:type :git
            :url "https://example.test/datastar.git"
            :ref "v0.1.0"
            :rev "abc123"
            :subdir "packages/datastar"
            :extra "drop"}
   :visibility :public
   :scope {:kind :base}
   :benchmark-status :unbenchmarked
   :benchmark-cases {:artifacts 1
                     :case-ids ["datastar-hiccup-architecture"]
                     :problem-classes ["architecture-understanding"]}
   :claim-authority {:status :non-authoritative
                     :public-claims? false
                     :review-required? false
                     :blockers [{:code :unbenchmarked
                                 :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}
   :manifest-fingerprint "sha256:manifest"
   :expected-package-id "datastar-hiccup"
   :expected-manifest-fingerprint "sha256:manifest"
   :diagnostic-counts {:total 1
                       :errors 0
                       :warnings 1}
   :warnings ["datastar-hiccup is unbenchmarked"]})

(def compact-plugin-package-fixture
  (update plugin-package-fixture
          :source
          select-keys
          [:type :url :rev :ref :subdir :path]))

(def project-with-plugin-package
  (assoc-in project-fixture [:plugins :packages] [plugin-package-fixture]))

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
        instructions (get-in init [:result :instructions])
        tool-names (mapv :name (get-in listed [:result :tools]))]
    (is (= "2.0" (:jsonrpc init)))
    (is (= "2025-03-26" (get-in init [:result :protocolVersion])))
    (is (= {:name "ygg-mcp"
            :version "0.1.0"}
           (get-in init [:result :serverInfo])))
    (is (str/includes? instructions "Use ygg_query first"))
    (is (str/includes? instructions "evidence.families"))
    (is (str/includes? instructions "evidence.planes"))
    (is (str/includes? instructions "nextActions before trusting missing evidence"))
    (is (str/includes? instructions "systems as the work-area orientation"))
    (is (str/includes? instructions "architecture as auditable evidence"))
    (is (str/includes? instructions "snippets as already-read source context"))
    (is (str/includes? instructions "relationships as nearby mechanical edges"))
    (is (str/includes? instructions "Use ygg_node for one exact"))
    (is (str/includes? instructions "Use ygg_status"))
    (is (str/includes? instructions "evidence-family readiness"))
    (is (str/includes? instructions "query-index readiness"))
    (is (str/includes? instructions "plugin package caveats"))
    (is (str/includes? instructions "skipped unsupported source"))
    (is (str/includes? instructions "registry/list, gap, new"))
    (is (str/includes? instructions "Extractor plugins may enhance core"))
    (is (str/includes? instructions "unbenchmarked or project-local plugin output is non-authoritative"))
    (is (str/includes? instructions "Use ygg_systems"))
    (is (str/includes? instructions "do not infer architecture from names"))
    (is (= ["ygg_query"
            "ygg_node"
            "ygg_systems"
            "ygg_status"]
           tool-names))))

(deftest all-tool-mode-lists-advanced-tools
  (let [listed (mcp/handle-message (mcp/server-context ["--tools" "all"])
                                   (request 1 "tools/list" {}))
        tool-names (mapv :name (get-in listed [:result :tools]))]
    (is (= ["ygg_query"
            "ygg_node"
            "ygg_systems"
            "ygg_sync_inspect"
            "ygg_status"
            "ygg_sync_check"
            "ygg_sync_activity"
            "ygg_work_list"
            "ygg_work_show"
            "ygg_work_pull"
            "ygg_work_heartbeat"
            "ygg_work_complete"
            "ygg_work_release"
            "ygg_work_reject"]
           tool-names))))

(deftest tool-groups-compose-listed-tools
  (let [listed (mcp/handle-message (mcp/server-context ["--tools" "default,sync"])
                                   (request 1 "tools/list" {}))
        tool-names (mapv :name (get-in listed [:result :tools]))]
    (is (= ["ygg_query"
            "ygg_node"
            "ygg_systems"
            "ygg_sync_inspect"
            "ygg_status"
            "ygg_sync_check"
            "ygg_sync_activity"]
           tool-names))))

(deftest default-tool-mode-rejects-hidden-tool-calls
  (let [response (mcp/handle-message (mcp/server-context [])
                                     (tool-call 1 "ygg_sync_inspect" {}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "ygg.mcp.error/v1"
           (get-in response [:error :data :schema])))
    (is (= "tool-not-enabled"
           (get-in response [:error :data :error])))
    (is (= "ygg_sync_inspect"
           (get-in response [:error :data :tool])))
    (is (= ["default"]
           (get-in response [:error :data :enabledGroups])))
    (is (= ["sync"]
           (get-in response [:error :data :requiredGroups])))))

(deftest tool-schemas-stay-narrow-and-explicit
  (let [listed (mcp/handle-message (mcp/server-context ["--tools" "all"])
                                   (request 1 "tools/list" {}))
        schemas (into {} (map (juxt :name :inputSchema)) (get-in listed [:result :tools]))]
    (is (= ["query"] (get-in schemas ["ygg_query" :required])))
    (is (= ["target"] (get-in schemas ["ygg_node" :required])))
    (is (= #{:configPath}
           (set (keys (get-in schemas ["ygg_sync_inspect" :properties])))))
    (is (= #{:configPath}
           (set (keys (get-in schemas ["ygg_status" :properties])))))
    (is (= #{:configPath :projectId}
           (set (keys (get-in schemas ["ygg_sync_activity" :properties])))))
    (is (contains? (set (keys (get-in schemas ["ygg_work_list" :properties])))
                   :projectId))
    (is (= ["workId"]
           (get-in schemas ["ygg_work_show" :required])))
    (is (= ["workId"]
           (get-in schemas ["ygg_work_heartbeat" :required])))
    (is (= ["workId" "result"]
           (get-in schemas ["ygg_work_complete" :required])))
    (is (= ["workId"]
           (get-in schemas ["ygg_work_release" :required])))
    (is (= ["workId" "reason"]
           (get-in schemas ["ygg_work_reject" :required])))
    (is (every? #(= false (:additionalProperties %)) (vals schemas)))))

(deftest tool-effect-metadata-is-internal
  (let [listed (mcp/handle-message (mcp/server-context ["--tools" "all"])
                                   (request 1 "tools/list" {}))]
    (is (true? (mcp/read-only-tool? "ygg_query")))
    (is (true? (mcp/read-only-tool? "ygg_work_list")))
    (is (false? (mcp/read-only-tool? "ygg_sync_activity")))
    (is (false? (mcp/read-only-tool? "ygg_work_pull")))
    (is (every? #(not (contains? % :read-only?))
                (get-in listed [:result :tools])))))

(deftest query-tool-returns-context-packet
  (let [summaries (atom [])]
    (with-redefs [project/read-project (constantly project-with-plugin-package)
                  corrections/overlay (fn [_ _] nil)
                  store/with-node (fn [_ f] (f :xtdb))
                  evidence/summarize (fn [xtdb project opts]
                                       (swap! summaries conj [xtdb project opts])
                                       {:schema evidence/schema
                                        :freshness {:status :current
                                                    :counts {:indexed 4}}})
                  context/context-packet (fn [xtdb query-text opts]
                                           {:schema context/schema
                                            :xtdb xtdb
                                            :query query-text
                                            :project-id (:project-id opts)
                                            :retriever (name (:retriever opts))
                                            :pluginPackages (get-in opts [:plugins :packages])
                                            :freshness (:freshness opts)})]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"
                                           "--tools" "default"])
                      (tool-call 7
                                 "ygg_query"
                                 {:query "where auth"
                                  :retriever "lexical"}))
            packet (get-in response [:result :structuredContent])]
        (is (= context/schema (:schema packet)))
        (is (= "fixture" (:project-id packet)))
        (is (= "where auth" (:query packet)))
        (is (= {:status :current
                :counts {:indexed 4}}
               (:freshness packet)))
        (is (= [plugin-package-fixture]
               (:pluginPackages packet)))
        (is (= [[:xtdb
                 project-with-plugin-package
                 {:correction-overlay nil
                  :config-path "project.edn"
                  :summary? true}]]
               @summaries))
        (is (= context/schema
               (:schema (json/read-json (get-in response [:result :content 0 :text])
                                        :key-fn keyword))))))))

(deftest query-tool-defaults-to-auto-retriever
  (with-redefs [project/read-project (constantly project-with-plugin-package)
                corrections/overlay (fn [_ _] nil)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [_ _ _]
                                     {:schema evidence/schema
                                      :freshness {:status :current}})
                context/context-packet (fn [_ _ opts]
                                         {:schema context/schema
                                          :retriever (:retriever opts)})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 77 "ygg_query" {:query "where auth"}))
          packet (get-in response [:result :structuredContent])]
      (is (= :auto (:retriever packet))))))

(deftest query-tool-returns-primary-context-packet
  (with-redefs [project/read-project (constantly project-with-plugin-package)
                corrections/overlay (fn [_ _] nil)
                store/with-node (fn [_ f] (f :xtdb))
                context/context-packet (fn [xtdb query-text opts]
                                         {:schema context/schema
                                          :xtdb xtdb
                                          :query query-text
                                          :project-id (:project-id opts)
                                          :retriever (name (:retriever opts))
                                          :candidateFiles [{:repo "app"
                                                            :path "src/app.clj"}]
                                          :evidence {:status :usable}
                                          :pluginPackages (get-in opts [:plugins :packages])
                                          :freshness (:freshness opts)
                                          :drilldowns ["ygg query \"where auth\" --project fixture"]})
                evidence/summarize (fn [xtdb project _opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :freshness {:status :stale
                                                  :counts {:indexed 2
                                                           :current 2
                                                           :changed 1
                                                           :missing 0
                                                           :unindexed 0}}
                                      :nextActions [{:kind :freshness
                                                     :label "Refresh indexed graph basis"
                                                     :count 1
                                                     :command "ygg sync project.edn --check"}
                                                    {:kind :query
                                                     :command "ygg query \"where is this handled?\" --project fixture --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 8
                               "ygg_query"
                               {:query "where auth"
                                :retriever "lexical"}))
          packet (get-in response [:result :structuredContent])]
      (is (= context/schema (:schema packet)))
      (is (= "fixture" (:project-id packet)))
      (is (= "where auth" (:query packet)))
      (is (= [{:repo "app"
               :path "src/app.clj"}]
             (:candidateFiles packet)))
      (is (= {:status :usable} (:evidence packet)))
      (is (= [plugin-package-fixture]
             (:pluginPackages packet)))
      (is (= {:status :stale
              :counts {:indexed 2
                       :current 2
                       :changed 1
                       :missing 0
                       :unindexed 0}
              :nextActions [{:kind :freshness
                             :label "Refresh indexed graph basis"
                             :count 1
                             :command "ygg sync project.edn --check"}]}
             (:freshness packet)))
      (is (= ["ygg query \"where auth\" --project fixture"]
             (:drilldowns packet))))))

(deftest node-tool-inspects-exact-file-target
  (let [root (temp-dir "ygg-mcp-node")
        source-file (java.io.File. root "src/app.clj")
        file-row {:xt/id "file:fixture:app:src/app.clj"
                  :project-id "fixture"
                  :repo-id "app"
                  :repo-root root
                  :path "src/app.clj"
                  :ext "clj"
                  :kind :code
                  :content-sha "sha"
                  :mtime-ms 1
                  :size-bytes 24
                  :active? true
                  :run-id "run"}
        node-row {:xt/id "node:app:main"
                  :project-id "fixture"
                  :repo-id "app"
                  :label "app/main"
                  :kind :namespace
                  :file-id (:xt/id file-row)
                  :path "src/app.clj"
                  :source-line 1
                  :active? true
                  :run-id "run"}
        dep-row {:xt/id "node:app:dep"
                 :project-id "fixture"
                 :repo-id "app"
                 :label "app/dep"
                 :kind :namespace
                 :file-id (:xt/id file-row)
                 :path "src/app.clj"
                 :source-line 2
                 :active? true
                 :run-id "run"}
        edge-row {:xt/id "edge:main:dep"
                  :project-id "fixture"
                  :repo-id "app"
                  :source-id (:xt/id node-row)
                  :target-id "node:app:dep"
                  :relation :requires
                  :confidence :high
                  :file-id (:xt/id file-row)
                  :path "src/app.clj"
                  :source-line 2
                  :active? true
                  :run-id "run"}]
    (.mkdirs (.getParentFile source-file))
    (spit source-file "(ns app)\n(defn main [])\n")
    (with-redefs [project/read-project (constantly (assoc-in project-fixture
                                                             [:repos 0 :root]
                                                             root))
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ table]
                                   (if (= table (:files store/tables))
                                     [file-row]
                                     []))
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-labels (fn [_ _ _] [])
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ _ _] [])
                  query/system-evidence-by-ids (fn [_ ids opts]
                                                 (is (= ["app:src/app.clj"] ids))
                                                 (is (= "fixture" (:project-id opts)))
                                                 [])
                  query/all-system-evidence (fn [& _]
                                              (throw (ex-info "broad system evidence read should not be used"
                                                              {})))
                  query/nodes-by-file-ids (fn [_ file-ids opts]
                                            (is (= [(:xt/id file-row)] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [node-row dep-row])
                  query/nodes-by-paths (fn [_ paths opts]
                                         (is (= ["src/app.clj"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/edges-by-file-ids (fn [_ file-ids opts]
                                            (is (= [(:xt/id file-row)] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [edge-row])
                  query/edges-by-paths (fn [_ paths opts]
                                         (is (= ["src/app.clj"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/edges-touching-node-ids (fn [_ ids opts]
                                                  (is (= #{(:xt/id node-row)
                                                           (:xt/id dep-row)}
                                                         (set ids)))
                                                  (is (= {:project-id "fixture"} opts))
                                                  [])
                  query/nodes-by-ids (fn [_ ids opts]
                                       (if (= ["app:src/app.clj"] (vec ids))
                                         []
                                         (do
                                           (is (= #{"node:app:main" "node:app:dep"}
                                                  (set ids)))
                                           (is (= {:project-id "fixture"} opts))
                                           [node-row dep-row])))
                  query/all-edges (fn [& _]
                                    (throw (ex-info "broad edge read should not be used"
                                                    {})))]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 13
                                 "ygg_node"
                                 {:target "app:src/app.clj"
                                  :sourceLines 1}))
            packet (get-in response [:result :structuredContent])]
        (is (= "ygg.node.inspect/v1" (:schema packet)))
        (is (= :found (:status packet)))
        (is (= :file (get-in packet [:match :targetKind])))
        (is (= "src/app.clj" (get-in packet [:file :path])))
        (is (= [{:line 1
                 :text "(ns app)"}]
               (get-in packet [:source :lines])))
        (is (= true (get-in packet [:source :truncated])))
        (is (= ["edge:main:dep"]
               (mapv :xt/id (get-in packet [:relationships :edges]))))))))

(deftest node-tool-returns-choices-for-ambiguous-exact-label
  (let [nodes [{:xt/id "node:one"
                :project-id "fixture"
                :repo-id "app"
                :label "Handler"
                :kind :symbol
                :file-id "file:one"
                :path "src/one.clj"
                :active? true
                :run-id "run"}
               {:xt/id "node:two"
                :project-id "fixture"
                :repo-id "app"
                :label "Handler"
                :kind :symbol
                :file-id "file:two"
                :path "src/two.clj"
                :active? true
                :run-id "run"}]]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-ids (fn [_ _ _] [])
                  query/nodes-by-labels (fn [_ labels opts]
                                          (is (= ["Handler"] labels))
                                          (is (= {:project-id "fixture"} opts))
                                          nodes)
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ _ _] [])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 14 "ygg_node" {:target "Handler"}))
            packet (get-in response [:result :structuredContent])]
        (is (= :ambiguous (:status packet)))
        (is (= ["node:one" "node:two"] (mapv :id (:choices packet))))))))

(deftest node-tool-inspects-exact-node-target-with-source-window
  (let [root (temp-dir "ygg-mcp-node-source")
        source-file (java.io.File. root "src/app.clj")
        file-row {:xt/id "file:fixture:app:src/app.clj"
                  :project-id "fixture"
                  :repo-id "app"
                  :repo-root root
                  :path "src/app.clj"
                  :ext "clj"
                  :kind :code
                  :content-sha "sha"
                  :mtime-ms 1
                  :size-bytes 80
                  :active? true
                  :run-id "run"}
        node-row {:xt/id "node:app:handler"
                  :project-id "fixture"
                  :repo-id "app"
                  :label "app/handler"
                  :kind :symbol
                  :file-id (:xt/id file-row)
                  :path "src/app.clj"
                  :source-line 4
                  :active? true
                  :run-id "run"}]
    (.mkdirs (.getParentFile source-file))
    (spit source-file "(ns app)\n\n(defn helper [])\n(defn handler [])\n(defn tail [])\n")
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ table]
                                   (if (= table (:files store/tables))
                                     [file-row]
                                     []))
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-ids (fn [_ ids opts]
                                       (is (= ["node:app:handler"] (vec ids)))
                                       (is (= {:project-id "fixture"} opts))
                                       [node-row])
                  query/nodes-by-labels (fn [_ _ _] [])
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ _ _] [])
                  query/nodes-by-file-ids (fn [_ _ _] [])
                  query/nodes-by-paths (fn [_ _ _] [])
                  query/edges-by-file-ids (fn [_ _ _] [])
                  query/edges-by-paths (fn [_ _ _] [])
                  query/edges-touching-node-ids (fn [_ _ _] [])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 15
                                 "ygg_node"
                                 {:target "node:app:handler"
                                  :sourceLines 3}))
            packet (get-in response [:result :structuredContent])]
        (is (= :found (:status packet)))
        (is (= :node (get-in packet [:match :targetKind])))
        (is (= 4 (get-in packet [:source :focusLine])))
        (is (= {:start 3
                :end 5}
               (get-in packet [:source :lineRange])))
        (is (= [{:line 3
                 :text "(defn helper [])"}
                {:line 4
                 :text "(defn handler [])"}
                {:line 5
                 :text "(defn tail [])"}]
               (get-in packet [:source :lines])))))))

(deftest node-tool-inspects-exact-correction-system-target
  (let [root (temp-dir "ygg-mcp-correction-system")
        doc-file (java.io.File. root "docs/billing.md")
        overlay {:schema "ygg.correction-overlay/v1"
                 :project "fixture"
                 :systems [{:id "system:billing"
                            :label "Billing"
                            :kind "domain"
                            :status "accepted"
                            :includes [{:repo "app"
                                        :path "src/billing"}]
                            :reason "accepted in review"}]
                 :reject []
                 :edges [{:id "correction-edge:billing-db"
                          :source "system:billing"
                          :target "system:database"
                          :relation "uses"
                          :status "accepted"
                          :reason "reviewed"}]
                 :docs [{:target "system:billing"
                         :role "reference"
                         :source {:repo "app"
                                  :path "docs/billing.md"}
                         :status "accepted"}]
                 :packageImports []
                 :updated-at-ms 1}]
    (.mkdirs (.getParentFile doc-file))
    (spit doc-file "# Billing\n\nContract text.\n")
    (with-redefs [project/read-project (constantly (assoc-in project-fixture
                                                             [:repos 0 :root]
                                                             root))
                  corrections/overlay (fn [_ _] overlay)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [_ _] [])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])
                  graph/system-graph (fn [xtdb project-id opts]
                                       {:schema graph/schema
                                        :xtdb xtdb
                                        :project-id project-id
                                        :basis {:detail (name (:detail opts))}
                                        :nodes [{:id "system:billing"
                                                 :label "Billing"
                                                 :kind "domain"
                                                 :repo "app"
                                                 :pathPrefix "src/billing"}
                                                {:id "system:database"
                                                 :label "Database"
                                                 :kind "service"
                                                 :repo "app"
                                                 :pathPrefix "src/db"}]
                                        :edges [{:id "system-edge:billing-db"
                                                 :source "system:billing"
                                                 :target "system:database"
                                                 :relation "uses"
                                                 :confidence "1.0"}]})]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 15
                                 "ygg_node"
                                 {:target "Billing"}))
            packet (get-in response [:result :structuredContent])]
        (is (= "ygg.node.inspect/v1" (:schema packet)))
        (is (= :found (:status packet)))
        (is (= :system (get-in packet [:match :targetKind])))
        (is (= "system:billing" (get-in packet [:system :id])))
        (is (= [{:id "system:billing"
                 :label "Billing"
                 :kind "domain"
                 :status "accepted"
                 :includes [{:repo "app"
                             :path "src/billing"}]
                 :reason "accepted in review"}]
               (get-in packet [:corrections :systems])))
        (is (= ["docs/billing.md"]
               (mapv #(get-in % [:source :path])
                     (get-in packet [:corrections :docs]))))
        (is (= :available
               (get-in packet [:corrections :docs 0 :sourceWindow :status])))
        (is (= [{:line 1
                 :text "# Billing"}
                {:line 2
                 :text ""}
                {:line 3
                 :text "Contract text."}]
               (get-in packet [:corrections :docs 0 :sourceWindow :lines])))
        (is (= ["correction-edge:billing-db"]
               (mapv :id (get-in packet [:corrections :edges]))))
        (is (= ["system:billing" "system:database"]
               (mapv :id (get-in packet [:systemRelationships :nodes]))))
        (is (= ["system-edge:billing-db"]
               (mapv :id (get-in packet [:systemRelationships :edges]))))))))

(deftest node-tool-returns-choices-for-system-and-node-label-collision
  (let [overlay {:schema "ygg.correction-overlay/v1"
                 :project "fixture"
                 :systems [{:id "system:handler"
                            :label "Handler"
                            :kind "service"
                            :status "accepted"}]
                 :reject []
                 :edges []
                 :docs []
                 :packageImports []
                 :updated-at-ms 1}
        nodes [{:xt/id "node:handler"
                :project-id "fixture"
                :repo-id "app"
                :label "Handler"
                :kind :symbol
                :file-id "file:handler"
                :path "src/handler.clj"
                :active? true
                :run-id "run"}]]
    (with-redefs [project/read-project (constantly project-fixture)
                  corrections/overlay (fn [_ _] overlay)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-ids (fn [_ _ _] [])
                  query/nodes-by-labels (fn [_ labels opts]
                                          (is (= ["Handler"] labels))
                                          (is (= {:project-id "fixture"} opts))
                                          nodes)
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ _ _] [])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 16
                                 "ygg_node"
                                 {:target "Handler"}))
            packet (get-in response [:result :structuredContent])]
        (is (= :ambiguous (:status packet)))
        (is (= [:system :node] (mapv :targetKind (:choices packet))))
        (is (= ["system:handler" "node:handler"] (mapv :id (:choices packet))))))))

(deftest node-tool-inspects-exact-package-target
  (let [package-row {:xt/id "node:pkg:npm:react"
                     :project-id "fixture"
                     :repo-id "app"
                     :label "npm:react"
                     :kind :external-package
                     :file-id "file:package-lock"
                     :path "package-lock.json"
                     :ecosystem :npm
                     :package-name "react"
                     :version-range "^19.0.0"
                     :dependency-scope :dependencies
                     :source-line 12
                     :active? true
                     :run-id "run"}
        source-row {:xt/id "node:src:app"
                    :project-id "fixture"
                    :repo-id "app"
                    :label "src.app"
                    :kind :namespace
                    :file-id "file:src"
                    :path "src/app.ts"
                    :source-line 1
                    :active? true
                    :run-id "run"}
        edge-row {:xt/id "edge:src:react"
                  :project-id "fixture"
                  :repo-id "app"
                  :source-id (:xt/id source-row)
                  :target-id (:xt/id package-row)
                  :relation :imports-package
                  :confidence :high
                  :file-id "file:src"
                  :path "src/app.ts"
                  :source-line 2
                  :active? true
                  :run-id "run"}]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-labels (fn [_ labels opts]
                                          (is (= ["npm:react"] labels))
                                          (is (= {:project-id "fixture"} opts))
                                          [package-row])
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ package-names opts]
                                                 (is (= {:project-id "fixture"}
                                                        opts))
                                                 (case (vec package-names)
                                                   ["npm:react"] []
                                                   ["react"] [package-row]))
                  query/system-evidence-by-ids (fn [_ ids opts]
                                                 (is (= ["npm:react"] ids))
                                                 (is (= {:project-id "fixture"} opts))
                                                 [])
                  query/nodes-by-file-ids (fn [_ file-ids opts]
                                            (is (= ["file:package-lock"] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [package-row])
                  query/nodes-by-paths (fn [_ paths opts]
                                         (is (= ["package-lock.json"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/edges-by-file-ids (fn [_ file-ids opts]
                                            (is (= ["file:package-lock"] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [])
                  query/edges-by-paths (fn [_ paths opts]
                                         (is (= ["package-lock.json"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/edges-touching-node-ids (fn [_ ids opts]
                                                  (is (= #{(:xt/id package-row)}
                                                         (set ids)))
                                                  (is (= {:project-id "fixture"} opts))
                                                  [edge-row])
                  query/nodes-by-ids (fn [_ ids opts]
                                       (if (= ["npm:react"] (vec ids))
                                         []
                                         (do
                                           (is (= #{"node:pkg:npm:react"
                                                    "node:src:app"}
                                                  (set ids)))
                                           (is (= {:project-id "fixture"} opts))
                                           [package-row source-row])))
                  query/all-system-evidence (fn [& _]
                                              (throw (ex-info "broad system evidence read should not be used"
                                                              {})))
                  query/all-edges (fn [& _]
                                    (throw (ex-info "broad edge read should not be used"
                                                    {})))]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 17
                                 "ygg_node"
                                 {:target "npm:react"}))
            packet (get-in response [:result :structuredContent])]
        (is (= :found (:status packet)))
        (is (= :package (get-in packet [:match :targetKind])))
        (is (= "react" (get-in packet [:package :package-name])))
        (is (= :npm (get-in packet [:package :ecosystem])))
        (is (= ["edge:src:react"]
               (mapv :xt/id (get-in packet [:relationships :edges]))))))))

(deftest node-tool-returns-choices-for-ambiguous-package-name
  (let [nodes [{:xt/id "node:pkg:npm:router"
                :project-id "fixture"
                :repo-id "app"
                :label "npm:router"
                :kind :external-package
                :file-id "file:npm"
                :path "package-lock.json"
                :ecosystem :npm
                :package-name "router"
                :active? true
                :run-id "run"}
               {:xt/id "node:pkg:pypi:router"
                :project-id "fixture"
                :repo-id "app"
                :label "pypi:router"
                :kind :external-package
                :file-id "file:pypi"
                :path "requirements.txt"
                :ecosystem :pypi
                :package-name "router"
                :active? true
                :run-id "run"}]]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-ids (fn [_ _ _] [])
                  query/nodes-by-labels (fn [_ _ _] [])
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ package-names opts]
                                                 (is (= ["router"] package-names))
                                                 (is (= {:project-id "fixture"}
                                                        opts))
                                                 nodes)
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 18
                                 "ygg_node"
                                 {:target "router"}))
            packet (get-in response [:result :structuredContent])]
        (is (= :ambiguous (:status packet)))
        (is (= [:package :package] (mapv :targetKind (:choices packet))))
        (is (= ["node:pkg:npm:router" "node:pkg:pypi:router"]
               (mapv :id (:choices packet))))))))

(deftest node-tool-inspects-exact-evidence-target
  (let [root (temp-dir "ygg-mcp-evidence-source")
        source-file (java.io.File. root "src/runtime.edn")
        file-row {:xt/id "file:fixture:app:src/runtime.edn"
                  :project-id "fixture"
                  :repo-id "app"
                  :repo-root root
                  :path "src/runtime.edn"
                  :ext "edn"
                  :kind :config
                  :content-sha "sha"
                  :mtime-ms 1
                  :size-bytes 42
                  :active? true
                  :run-id "run"}
        evidence-row {:xt/id "evidence:runtime-url"
                      :project-id "fixture"
                      :repo-id "app"
                      :system-id "system:runtime"
                      :file-id (:xt/id file-row)
                      :path "src/runtime.edn"
                      :file-kind :config
                      :kind :url
                      :label "https://api.example.test"
                      :normalized-value "https://api.example.test"
                      :source-line 4
                      :confidence 0.9
                      :active? true
                      :run-id "run"}
        edge-row {:xt/id "edge:runtime:source"
                  :project-id "fixture"
                  :repo-id "app"
                  :source-id "node:runtime"
                  :target-id "node:config"
                  :relation :configures
                  :confidence :high
                  :file-id (:xt/id file-row)
                  :path "src/runtime.edn"
                  :source-line 4
                  :active? true
                  :run-id "run"}]
    (.mkdirs (.getParentFile source-file))
    (spit source-file "{:a 1}\n{:b 2}\n{:c 3}\n{:url \"https://api.example.test\"}\n{:d 4}\n")
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ table]
                                   (if (= table (:files store/tables))
                                     [file-row]
                                     []))
                  query/all-nodes (fn [& _]
                                    (throw (ex-info "broad node read should not be used"
                                                    {})))
                  query/nodes-by-labels (fn [_ _ _] [])
                  query/nodes-by-namespaces (fn [_ _ _] [])
                  query/nodes-by-names (fn [_ _ _] [])
                  query/nodes-by-package-names (fn [_ _ _] [])
                  query/system-evidence-by-ids (fn [_ ids opts]
                                                 (is (= ["evidence:runtime-url"] ids))
                                                 (is (= {:project-id "fixture"} opts))
                                                 [evidence-row])
                  query/nodes-by-file-ids (fn [_ file-ids opts]
                                            (is (= [(:xt/id file-row)] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [])
                  query/nodes-by-paths (fn [_ paths opts]
                                         (is (= ["src/runtime.edn"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/edges-by-file-ids (fn [_ file-ids opts]
                                            (is (= [(:xt/id file-row)] file-ids))
                                            (is (= {:project-id "fixture"
                                                    :repo-id "app"}
                                                   opts))
                                            [edge-row])
                  query/edges-by-paths (fn [_ paths opts]
                                         (is (= ["src/runtime.edn"] paths))
                                         (is (= {:project-id "fixture"
                                                 :repo-id "app"}
                                                opts))
                                         [])
                  query/nodes-by-ids (fn [_ ids opts]
                                       (if (= ["evidence:runtime-url"] (vec ids))
                                         []
                                         (do
                                           (is (= #{"node:runtime" "node:config"}
                                                  (set ids)))
                                           (is (= {:project-id "fixture"} opts))
                                           [])))
                  query/all-system-evidence (fn [& _]
                                              (throw (ex-info "broad system evidence read should not be used"
                                                              {})))
                  query/all-edges (fn [& _]
                                    (throw (ex-info "broad edge read should not be used"
                                                    {})))
                  graph/system-graph (fn [xtdb project-id opts]
                                       (is (= :xtdb xtdb))
                                       (is (= "fixture" project-id))
                                       (is (= :expanded (:detail opts)))
                                       {:schema graph/schema
                                        :nodes [{:id "system:runtime"
                                                 :label "Runtime"
                                                 :kind "service"
                                                 :repo "app"
                                                 :pathPrefix "src"}
                                                {:id "system:api"
                                                 :label "API"
                                                 :kind "external"
                                                 :repo "app"}]
                                        :edges [{:id "system-edge:runtime-api"
                                                 :source "system:runtime"
                                                 :target "system:api"
                                                 :relation "uses-runtime-url"
                                                 :confidence "high"}]})]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 19
                                 "ygg_node"
                                 {:target "evidence:runtime-url"
                                  :sourceLines 3}))
            packet (get-in response [:result :structuredContent])]
        (is (= :found (:status packet)))
        (is (= :evidence (get-in packet [:match :targetKind])))
        (is (= "system:runtime" (get-in packet [:match :systemId])))
        (is (= "https://api.example.test"
               (get-in packet [:evidence :normalized-value])))
        (is (= "src/runtime.edn" (get-in packet [:sourceLocation :path])))
        (is (= 4 (get-in packet [:source :focusLine])))
        (is (= [{:line 3
                 :text "{:c 3}"}
                {:line 4
                 :text "{:url \"https://api.example.test\"}"}
                {:line 5
                 :text "{:d 4}"}]
               (get-in packet [:source :lines])))
        (is (= ["system:runtime" "system:api"]
               (mapv :id (get-in packet [:systemRelationships :nodes]))))
        (is (= ["system-edge:runtime-api"]
               (mapv :id (get-in packet [:systemRelationships :edges]))))
        (is (= ["edge:runtime:source"]
               (mapv :xt/id (get-in packet [:relationships :edges]))))))))

(deftest systems-tool-returns-canonical-graph
  (with-redefs [project/read-project (constantly project-fixture)
                corrections/overlay (fn [_ _] nil)
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
                               "ygg_systems"
                               {:detail "expanded"}))
          packet (get-in response [:result :structuredContent])]
      (is (= graph/schema (:schema packet)))
      (is (= "fixture" (:project-id packet)))
      (is (= "expanded" (get-in packet [:basis :detail]))))))

(deftest sync-inspect-tool-returns-project-evidence-surface
  (with-redefs [project/read-project (constantly project-with-plugin-package)
                corrections/overlay (fn [_ _] nil)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :available [:source-graph :docs]
                                      :families [{:family :source-files
                                                  :status :weak
                                                  :counts {:files 2
                                                           :skipped-files 1
                                                           :diagnostics 2}}
                                                 {:family :source-graph
                                                  :status :available
                                                  :counts {:nodes 3
                                                           :edges 4}}]
                                      :freshness {:status :stale
                                                  :counts {:changed 1}}
                                      :counts {:files 2
                                               :nodes 3
                                               :edges 4
                                               :result-schema-mismatch-events 1
                                               :skipped-files 1
                                               :diagnostics 2}
                                      :top-file-kinds [{:kind "clojure"
                                                        :count 2}]
                                      :extractors [{:kind "clojure"
                                                    :files 2}]
                                      :extractor-fingerprints [{:kind "clojure"
                                                                :extractor-version "clojure/v1"
                                                                :extractor-fingerprint "extractor:clj-a"
                                                                :files 2}]
                                      :diagnostics {:total 2
                                                    :by-stage [{:stage "parse"
                                                                :count 2}]}
                                      :nextActions [{:kind :activity
                                                     :label "Inspect result schema mismatch activity"
                                                     :count 1
                                                     :command "ygg sync activity project.edn --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"
                                         "--tools" "default,sync"])
                    (tool-call 11
                               "ygg_sync_inspect"
                               {}))
          packet (get-in response [:result :structuredContent])]
      (is (= "ygg.project.inspect/v1" (:schema packet)))
      (is (= "fixture" (get-in packet [:project :id])))
      (is (= [{:id "app"
               :root "/tmp/app"
               :role :application}]
             (:repos packet)))
      (is (= {:counts {:packages 1
                       :warnings 1
                       :unbenchmarked 1
                       :benchmarked 0
                       :nonAuthoritative 1}
              :packages [compact-plugin-package-fixture]}
             (:pluginPackages packet)))
      (is (= evidence/schema (get-in packet [:evidence :schema])))
      (is (= "project.edn" (get-in packet [:evidence :config-path])))
      (is (= [{:family :source-files
               :status :weak
               :counts {:files 2
                        :skipped-files 1
                        :diagnostics 2}}
              {:family :source-graph
               :status :available
               :counts {:nodes 3
                        :edges 4}}]
             (get-in packet [:evidence :families])))
      (is (= {:counts {:files 2
                       :skippedFiles 1
                       :diagnostics 2}
              :topFileKinds [{:kind "clojure"
                              :count 2}]
              :extractors [{:kind "clojure"
                            :files 2}]
              :extractorFingerprints [{:kind "clojure"
                                       :extractor-version "clojure/v1"
                                       :extractor-fingerprint "extractor:clj-a"
                                       :files 2}]
              :diagnostics {:total 2
                            :by-stage [{:stage "parse"
                                        :count 2}]}}
             (:coverage packet)))
      (is (= {:status :stale
              :counts {:changed 1}}
             (:freshness packet)))
      (is (= [{:kind :activity
               :label "Inspect result schema mismatch activity"
               :count 1
               :command "ygg sync activity project.edn --json"}]
             (:nextActions packet)))
      (is (= 1 (get-in packet [:evidence :counts :result-schema-mismatch-events])))
      (is (= [{:kind :activity
               :label "Inspect result schema mismatch activity"
               :count 1
               :command "ygg sync activity project.edn --json"}]
             (get-in packet [:evidence :nextActions]))))))

(deftest status-tool-returns-project-evidence-surface
  (with-redefs [project/read-project (constantly project-with-plugin-package)
                corrections/overlay (fn [_ _] nil)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :available [:source-graph]
                                      :families [{:family :source-files
                                                  :status :available
                                                  :counts {:files 2
                                                           :skipped-files 0
                                                           :diagnostics 0}}]
                                      :freshness {:status :current
                                                  :basis "indexed-graph"
                                                  :missingQueryIndex false
                                                  :projectConfig "project.edn"
                                                  :counts {:changed 0}}
                                      :counts {:files 2
                                               :nodes 3
                                               :edges 4
                                               :skipped-files 0
                                               :diagnostics 0}
                                      :top-file-kinds [{:kind "clojure"
                                                        :count 2}]
                                      :extractor-fingerprints [{:kind "clojure"
                                                                :extractor-version "clojure/v1"
                                                                :extractor-fingerprint "extractor:clj-a"
                                                                :files 2}]
                                      :skipped-by-extension [{:extension ".bin"
                                                              :count 1}]
                                      :skipped-by-reason [{:reason "binary"
                                                           :count 1}]
                                      :nextActions [{:kind :query
                                                     :command "ygg query \"where is this handled?\" --project fixture --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 12
                               "ygg_status"
                               {}))
          packet (get-in response [:result :structuredContent])]
      (is (= "ygg.project.inspect/v1" (:schema packet)))
      (is (= "fixture" (get-in packet [:project :id])))
      (is (= {:counts {:packages 1
                       :warnings 1
                       :unbenchmarked 1
                       :benchmarked 0
                       :nonAuthoritative 1}
              :packages [compact-plugin-package-fixture]}
             (:pluginPackages packet)))
      (is (= evidence/schema (get-in packet [:evidence :schema])))
      (is (= "project.edn" (get-in packet [:evidence :config-path])))
      (is (= [{:family :source-files
               :status :available
               :counts {:files 2
                        :skipped-files 0
                        :diagnostics 0}}]
             (get-in packet [:evidence :families])))
      (is (= {:counts {:files 2
                       :skippedFiles 0
                       :diagnostics 0}
              :topFileKinds [{:kind "clojure"
                              :count 2}]
              :extractorFingerprints [{:kind "clojure"
                                       :extractor-version "clojure/v1"
                                       :extractor-fingerprint "extractor:clj-a"
                                       :files 2}]
              :skippedByExtension [{:extension ".bin"
                                    :count 1}]
              :skippedByReason [{:reason "binary"
                                 :count 1}]}
             (:coverage packet)))
      (is (= {:status :current
              :basis "indexed-graph"
              :missingQueryIndex false
              :projectConfig "project.edn"
              :counts {:changed 0}}
             (:freshness packet)))
      (is (= [{:kind :query
               :command "ygg query \"where is this handled?\" --project fixture --json"}]
             (:nextActions packet))))))

(deftest missing-project-returns-structured-error
  (let [response (mcp/handle-message
                  (mcp/server-context ["--tools" "sync"])
                  (tool-call 11 "ygg_sync_inspect" {}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "ygg.mcp.error/v1"
           (get-in response [:error :data :schema])))
    (is (= "missing-project-config"
           (get-in response [:error :data :error])))))

(deftest sync-activity-tool-imports-queue-activity
  (let [calls (atom [])
        queue-root ".dev/test-queue"]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/project-sqlite-path (constantly queue-root)
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
                      (mcp/server-context ["--config" "project.edn"
                                           "--tools" "sync"])
                      (tool-call 12
                                 "ygg_sync_activity"
                                 {}))
            packet (get-in response [:result :structuredContent])]
        (is (= activity/sync-schema (:schema packet)))
        (is (= "fixture" (:project-id packet)))
        (is (= queue-root (:queue-root packet)))
        (is (= 1 (get-in packet [:counts :result-schema-mismatch-events])))
        (is (= [[:activity :xtdb "fixture" {:queue-root queue-root}]]
               @calls))))))

(deftest work-complete-rejects-result-without-schema
  (let [root (temp-dir "ygg-mcp-invalid-result-queue")]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 12
                                 "ygg_work_complete"
                                 {:projectId "fixture"
                                  :workId "queue:item"
                                  :result {:ok true}}))]
        (is (= -32602 (get-in response [:error :code])))
        (is (= "invalid-result" (get-in response [:error :data :error])))
        (is (= "result.schema" (get-in response [:error :data :field])))))))

(deftest work-list-requires-project-queue
  (with-redefs [registry/resolve-project (fn [& _]
                                           (throw (ex-info "missing" {})))]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--tools" "work"])
                    (tool-call 13 "ygg_work_list" {}))]
      (is (= -32602 (get-in response [:error :code])))
      (is (= "missing-project-queue"
             (get-in response [:error :data :error]))))))

(deftest work-list-resolves-central-project-queue-from-server-root
  (let [server-root (temp-dir "ygg-mcp-project-root")
        queue-root (temp-dir "ygg-mcp-central-queue")
        calls (atom [])
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root queue-root
                                      :kind "context"
                                      :project-id "fixture"})]
    (with-redefs [registry/resolve-project (fn [opts]
                                             (swap! calls conj opts)
                                             {:project-id "fixture"
                                              :project project-fixture})
                  store/project-sqlite-path (fn [project-id]
                                              (is (= "fixture" project-id))
                                              queue-root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--root" server-root
                                           "--tools" "work"])
                      (tool-call 13
                                 "ygg_work_list"
                                 {:kind "context"}))
            listed (get-in response [:result :structuredContent])]
        (is (= queue/list-schema (:schema listed)))
        (is (= [(:id (:item item))] (mapv :id (:items listed))))
        (is (= [{:project-id nil
                 :cwd (.getPath (.getCanonicalFile (io/file server-root)))}]
               @calls))))))

(deftest work-list-returns-actionable-queue-summaries
  (let [root (temp-dir "ygg-mcp-queue-list")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 13
                                 "ygg_work_list"
                                 {:projectId "fixture"
                                  :kind "context"}))
            listed (get-in response [:result :structuredContent])
            first-item (first (:items listed))]
        (is (= queue/list-schema (:schema listed)))
        (is (= [(:id (:item item))] (mapv :id (:items listed))))
        (is (= "ready" (:status first-item)))
        (is (some #(= :claim (:kind %)) (:actions first-item)))
        (is (= "ready" (get-in (queue/find-item root (:id first-item))
                               [:item :status])))))))

(deftest work-show-returns-summary-and-embedded-item
  (let [root (temp-dir "ygg-mcp-queue-show")
        payload {:schema context/schema
                 :project-id "fixture"
                 :query "where auth"
                 :expectedResultSchema "custom.result/v1"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 14
                                 "ygg_work_show"
                                 {:projectId "fixture"
                                  :workId (:id (:item item))}))
            shown (get-in response [:result :structuredContent])]
        (is (= queue/summary-schema (:schema shown)))
        (is (= (:id (:item item)) (:id shown)))
        (is (= "ready" (:status shown)))
        (is (= "custom.result/v1" (:expected-result-schema shown)))
        (is (= payload (get-in shown [:item :payload])))))))

(deftest work-show-returns-queue-error-for-missing-item
  (let [root (temp-dir "ygg-mcp-queue-show-missing")]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 15
                                 "ygg_work_show"
                                 {:projectId "fixture"
                                  :workId "queue:missing"}))
            shown (get-in response [:result :structuredContent])]
        (is (= "ygg.queue.error/v1" (:schema shown)))
        (is (= "sync work item not found" (:error shown)))
        (is (= "queue:missing" (:id shown)))))))

(deftest work-pull-claims-ready-queue-item
  (let [root (temp-dir "ygg-mcp-queue")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 16
                                 "ygg_work_pull"
                                 {:projectId "fixture"
                                  :agentId "agent"}))
            pulled (get-in response [:result :structuredContent])]
        (is (= (:id (:item item)) (:id pulled)))
        (is (= "claimed" (:status pulled)))
        (is (= "agent" (get-in pulled [:lease :agent-id])))))))

(deftest work-heartbeat-extends-claimed-queue-item
  (let [root (temp-dir "ygg-mcp-queue-heartbeat")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        _ (queue/claim-next! root {:agent-id "agent"
                                   :project-id "fixture"
                                   :lease-ms 1000})]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 17
                                 "ygg_work_heartbeat"
                                 {:projectId "fixture"
                                  :workId item-id
                                  :agentId "agent"
                                  :leaseMinutes 2}))
            summary (get-in response [:result :structuredContent])]
        (is (= item-id (:id summary)))
        (is (= "claimed" (:status summary)))
        (is (= "agent" (get-in summary [:lease :agent-id])))
        (is (integer? (get-in summary [:lease :heartbeat-at-ms])))))))

(deftest work-release-returns-claimed-queue-item-to-ready
  (let [root (temp-dir "ygg-mcp-queue-release")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        _ (queue/claim-next! root {:agent-id "agent"
                                   :project-id "fixture"})]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 18
                                 "ygg_work_release"
                                 {:projectId "fixture"
                                  :workId item-id
                                  :reason "needs another agent"}))
            summary (get-in response [:result :structuredContent])
            found (queue/find-item root item-id)]
        (is (= item-id (:id summary)))
        (is (= "ready" (:status summary)))
        (is (= "ready" (get-in found [:item :status])))
        (is (= "needs another agent"
               (get-in found [:item :release-reason])))))))

(deftest work-reject-records-reason
  (let [root (temp-dir "ygg-mcp-queue-reject")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])]
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--tools" "work"])
                      (tool-call 19
                                 "ygg_work_reject"
                                 {:projectId "fixture"
                                  :workId item-id
                                  :reason "not applicable"}))
            summary (get-in response [:result :structuredContent])
            found (queue/find-item root item-id)]
        (is (= item-id (:id summary)))
        (is (= "rejected" (:status summary)))
        (is (= "rejected" (get-in found [:item :status])))
        (is (= "not applicable" (get-in found [:item :reason])))))))

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
