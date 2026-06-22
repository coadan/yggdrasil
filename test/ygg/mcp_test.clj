(ns ygg.mcp-test
  (:require [ygg.activity :as activity]
            [ygg.context :as context]
            [ygg.cursor :as cursor]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.mcp :as mcp]
            [ygg.project :as project]
            [ygg.query :as query]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
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
    (is (str/includes? instructions "Use ygg_explore first"))
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
    (is (= ["ygg_explore"
            "ygg_node"
            "ygg_systems"
            "ygg_status"]
           tool-names))))

(deftest all-tool-mode-lists-advanced-tools
  (let [listed (mcp/handle-message (mcp/server-context ["--tools" "all"])
                                   (request 1 "tools/list" {}))
        tool-names (mapv :name (get-in listed [:result :tools]))]
    (is (= ["ygg_explore"
            "ygg_node"
            "ygg_ask"
            "ygg_explore_create"
            "ygg_explore_open"
            "ygg_explore_expand"
            "ygg_explore_docs"
            "ygg_explore_search"
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
    (is (= ["ygg_explore"
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
    (is (= ["query"] (get-in schemas ["ygg_explore" :required])))
    (is (= ["target"] (get-in schemas ["ygg_node" :required])))
    (is (= ["query"] (get-in schemas ["ygg_ask" :required])))
    (is (= ["cursorId" "target"]
           (get-in schemas ["ygg_explore_open" :required])))
    (is (= ["cursorId" "target"]
           (get-in schemas ["ygg_explore_docs" :required])))
    (is (= ["cursorId" "query"]
           (get-in schemas ["ygg_explore_search" :required])))
    (is (contains? (set (keys (get-in schemas ["ygg_sync_inspect" :properties])))
                   :mapPath))
    (is (contains? (set (keys (get-in schemas ["ygg_status" :properties])))
                   :mapPath))
    (is (contains? (set (keys (get-in schemas ["ygg_sync_activity" :properties])))
                   :queueDir))
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

(deftest ask-tool-returns-context-packet
  (let [summaries (atom [])]
    (with-redefs [project/read-project (constantly project-with-plugin-package)
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
                                           "--tools" "default,ask"])
                      (tool-call 7
                                 "ygg_ask"
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
                 {:map-overlay nil
                  :config-path "project.edn"
                  :map-path nil}]]
               @summaries))
        (is (= context/schema
               (:schema (json/read-json (get-in response [:result :content 0 :text])
                                        :key-fn keyword))))))))

(deftest explore-tool-returns-primary-context-packet
  (with-redefs [project/read-project (constantly project-with-plugin-package)
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
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :map-path (:map-path opts)
                                      :freshness {:status :stale
                                                  :counts {:indexed 2
                                                           :current 2
                                                           :changed 1
                                                           :missing 0
                                                           :unindexed 0}}
                                      :nextActions [{:kind :freshness
                                                     :label "Refresh indexed graph basis"
                                                     :count 1
                                                     :command "ygg sync project.edn --check --map ygg.map.json"}
                                                    {:kind :ask
                                                     :command "ygg ask \"where is this handled?\" --project fixture --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 8
                               "ygg_explore"
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
                             :command "ygg sync project.edn --check --map ygg.map.json"}]}
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
                  query/all-nodes (fn [_ opts]
                                    (is (= "fixture" (:project-id opts)))
                                    [node-row {:xt/id "node:app:dep"
                                               :project-id "fixture"
                                               :repo-id "app"
                                               :label "app/dep"
                                               :kind :namespace
                                               :file-id (:xt/id file-row)
                                               :path "src/app.clj"
                                               :source-line 2
                                               :active? true
                                               :run-id "run"}])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ opts]
                                    (is (= "fixture" (:project-id opts)))
                                    [edge-row])]
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
                  query/all-nodes (fn [_ _] nodes)
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
                  query/all-nodes (fn [_ _] [node-row])
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

(deftest node-tool-inspects-exact-map-system-target
  (let [root (temp-dir "ygg-mcp-map-system")
        map-path (str root "/ygg.map.json")
        doc-file (java.io.File. root "docs/billing.md")
        overlay {:schema "ygg.map/v1"
                 :project "fixture"
                 :systems [{:id "system:billing"
                            :label "Billing"
                            :kind "domain"
                            :status "accepted"
                            :includes [{:repo "app"
                                        :path "src/billing"}]
                            :reason "accepted in review"}]
                 :reject []
                 :edges [{:id "map-edge:billing-db"
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
    (spit map-path (json/write-json-str overlay))
    (with-redefs [project/read-project (constantly (assoc-in project-fixture
                                                             [:repos 0 :root]
                                                             root))
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
                                 {:target "Billing"
                                  :mapPath map-path}))
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
               (get-in packet [:map :systems])))
        (is (= ["docs/billing.md"]
               (mapv #(get-in % [:source :path])
                     (get-in packet [:map :docs]))))
        (is (= :available
               (get-in packet [:map :docs 0 :sourceWindow :status])))
        (is (= [{:line 1
                 :text "# Billing"}
                {:line 2
                 :text ""}
                {:line 3
                 :text "Contract text."}]
               (get-in packet [:map :docs 0 :sourceWindow :lines])))
        (is (= ["map-edge:billing-db"]
               (mapv :id (get-in packet [:map :edges]))))
        (is (= ["system:billing" "system:database"]
               (mapv :id (get-in packet [:systemRelationships :nodes]))))
        (is (= ["system-edge:billing-db"]
               (mapv :id (get-in packet [:systemRelationships :edges]))))))))

(deftest node-tool-returns-choices-for-system-and-node-label-collision
  (let [root (temp-dir "ygg-mcp-map-ambiguous")
        map-path (str root "/ygg.map.json")
        overlay {:schema "ygg.map/v1"
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
    (spit map-path (json/write-json-str overlay))
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  store/all-rows (fn [_ _] [])
                  query/all-nodes (fn [_ _] nodes)
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [])]
      (let [response (mcp/handle-message
                      (mcp/server-context ["--config" "project.edn"])
                      (tool-call 16
                                 "ygg_node"
                                 {:target "Handler"
                                  :mapPath map-path}))
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
                  query/all-nodes (fn [_ _] [package-row source-row])
                  query/all-system-evidence (fn [_ _] [])
                  query/all-edges (fn [_ _] [edge-row])]
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
                  query/all-nodes (fn [_ _] nodes)
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
                  query/all-nodes (fn [_ _] [])
                  query/all-system-evidence (fn [_ _] [evidence-row])
                  query/all-edges (fn [_ _] [edge-row])
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
                    (mcp/server-context ["--tools" "cursor"])
                    (tool-call 9
                               "ygg_explore_docs"
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
                    (mcp/server-context ["--tools" "cursor"])
                    (tool-call 10
                               "ygg_explore_search"
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
  (with-redefs [project/read-project (constantly project-with-plugin-package)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :map-path (:map-path opts)
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
                               {:mapPath "ygg.map.json"}))
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
      (is (= "ygg.map.json" (get-in packet [:evidence :map-path])))
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
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :map-path (:map-path opts)
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
                                                  :map "ygg.map.json"
                                                  :mapExists true
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
                                      :nextActions [{:kind :ask
                                                     :command "ygg ask \"where is this handled?\" --project fixture --json"}]})]
    (let [response (mcp/handle-message
                    (mcp/server-context ["--config" "project.edn"])
                    (tool-call 12
                               "ygg_status"
                               {:mapPath "ygg.map.json"}))
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
      (is (= "ygg.map.json" (get-in packet [:evidence :map-path])))
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
              :map "ygg.map.json"
              :mapExists true
              :counts {:changed 0}}
             (:freshness packet)))
      (is (= [{:kind :ask
               :command "ygg ask \"where is this handled?\" --project fixture --json"}]
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
                      (mcp/server-context ["--config" "project.edn"
                                           "--tools" "sync"])
                      (tool-call 12
                                 "ygg_sync_activity"
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
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 12
                             "ygg_work_complete"
                             {:workId "queue:item"
                              :result {:ok true}}))]
    (is (= -32602 (get-in response [:error :code])))
    (is (= "invalid-result" (get-in response [:error :data :error])))
    (is (= "result.schema" (get-in response [:error :data :field])))))

(deftest work-list-returns-actionable-queue-summaries
  (let [root (temp-dir "ygg-mcp-queue-list")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 13
                             "ygg_work_list"
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
  (let [root (temp-dir "ygg-mcp-queue-show")
        payload {:schema context/schema
                 :project-id "fixture"
                 :query "where auth"
                 :expectedResultSchema "custom.result/v1"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 14
                             "ygg_work_show"
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
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 15
                             "ygg_work_show"
                             {:queueDir (temp-dir "ygg-mcp-queue-show-missing")
                              :workId "queue:missing"}))
        shown (get-in response [:result :structuredContent])]
    (is (= "ygg.queue.error/v1" (:schema shown)))
    (is (= "sync work item not found" (:error shown)))
    (is (= "queue:missing" (:id shown)))))

(deftest work-pull-claims-ready-queue-item
  (let [root (temp-dir "ygg-mcp-queue")
        payload {:schema context/schema
                 :project-id "fixture"}
        item (queue/enqueue! payload {:root root
                                      :kind "context"
                                      :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 16
                             "ygg_work_pull"
                             {:queueDir root
                              :projectId "fixture"
                              :agentId "agent"}))
        pulled (get-in response [:result :structuredContent])]
    (is (= (:id (:item item)) (:id pulled)))
    (is (= "claimed" (:status pulled)))
    (is (= "agent" (get-in pulled [:lease :agent-id])))))

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
                                   :lease-ms 1000})
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 17
                             "ygg_work_heartbeat"
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
  (let [root (temp-dir "ygg-mcp-queue-release")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        _ (queue/claim-next! root {:agent-id "agent"
                                   :project-id "fixture"})
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 18
                             "ygg_work_release"
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
  (let [root (temp-dir "ygg-mcp-queue-reject")
        item-id (get-in (queue/enqueue! {:schema context/schema
                                         :project-id "fixture"}
                                        {:root root
                                         :kind "context"
                                         :project-id "fixture"})
                        [:item :id])
        response (mcp/handle-message
                  (mcp/server-context ["--tools" "work"])
                  (tool-call 19
                             "ygg_work_reject"
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
