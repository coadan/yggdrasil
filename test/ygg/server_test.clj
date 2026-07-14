(ns ygg.server-test
  (:require [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync :as cli-sync]
            [ygg.embedding-client :as embedding-client]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.init :as init]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.queue :as queue]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.server :as server]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(defn- temp-queue-db
  [prefix]
  (.getPath (io/file (temp-dir prefix) "project.sqlite")))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- read-json
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- decision-packet
  []
  (decision-classifier/decision-packet
   {:id "maintenance-decision:test"
    :project-id "demo"
    :kind :orphaned-candidate
    :severity :low
    :target "system:demo:app"
    :reason "Needs review."
    :recommended-actions [:none]}))

(defn- valid-result
  []
  {:schema decision-classifier/schema
   :decisionId "maintenance-decision:test"
   :recommendation "investigate"
   :confidence 0.7
   :reason "Evidence is insufficient for an automatic correction patch."
   :correctionPatch []})

(deftest generic-command-op-dispatches-command-with-warm-node
  (with-redefs [cli/dispatch
                (fn [command args]
                  (store/with-node (store/storage-path)
                    (fn [xtdb]
                      (println (str "command=" command
                                    " args=" (pr-str args)
                                    " xtdb=" xtdb)))))]
    (is (= {:ok true
            :exit 0
            :out "command=packages args=[\"--project\" \"demo\"] xtdb=:xtdb\n"
            :err ""}
           (server/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "packages"
                                   :token "token"
                                   :args ["--project" "demo"]})))))

(deftest help-command-ops-dispatch-canonical-help-through-server
  (with-redefs [cli/dispatch
                (fn [command args]
                  (println (str "command=" command " args=" (pr-str args))))]
    (are [op] (= {:ok true
                  :exit 0
                  :out "command=help args=[]\n"
                  :err ""}
                 (server/handle-request {:xtdb :xtdb
                                         :token "token"
                                         :running (atom true)}
                                        {:op op
                                         :token "token"
                                         :args []}))
      "help"
      "--help"
      "-h")))

(deftest named-command-op-preserves-non-server-storage-paths
  (with-redefs [store/with-node
                (fn [path f]
                  (println (str "cold-path=" path))
                  (f :cold-xtdb))
                cli/dispatch
                (fn [command args]
                  (store/with-node "benchmark/xtdb"
                    (fn [xtdb]
                      (println (str "command=" command
                                    " args=" (pr-str args)
                                    " xtdb=" xtdb)))))]
    (is (= {:ok true
            :exit 0
            :out "cold-path=benchmark/xtdb\ncommand=bench args=[\"agent-report\" \"suite.edn\"] xtdb=:cold-xtdb\n"
            :err ""}
           (server/handle-request {:xtdb {:path "default/xtdb"}
                                   :token "token"
                                   :running (atom true)}
                                  {:op "bench"
                                   :token "token"
                                   :args ["agent-report" "suite.edn"]})))))

(deftest init-sync-runs-through-server-sync-path
  (let [sync-calls (atom [])]
    (with-redefs [init/init!
                  (fn [root opts]
                    (is (= "repo" root))
                    (is (= {:out nil
                            :force? false
                            :project-id "demo"
                            :name nil
                            :workbench? false
                            :task nil
                            :harness nil
                            :hooks? false
                            :skill? false
                            :mcp? false
                            :force-agent? false
                            :maintenance nil
                            :maintenance-model nil
                            :maintenance-reasoning nil
                            :maintenance-command nil}
                           opts))
                    {:schema "ygg.init/v1"
                     :project-id "demo"
                     :config "project.edn"})
                  store/storage-path
                  (fn
                    ([] "/store/default")
                    ([project-id] (str "/store/" project-id)))
                  server/run-sync!
                  (fn [xtdb opts]
                    (swap! sync-calls conj
                           {:xtdb xtdb
                            :opts (select-keys opts [:config-path
                                                     :project-id
                                                     :check?
                                                     :query-index?
                                                     :no-progress?
                                                     :cwd])})
                    {:schema "ygg.sync/v1"
                     :project-id "demo"
                     :index-summary {:repos []}})
                  cli/dispatch
                  (fn [& _]
                    (throw (ex-info "init --sync should not use generic CLI dispatch" {})))]
      (let [response (server/handle-request
                      {:xtdb :xtdb
                       :token "token"
                       :running (atom true)}
                      {:op "init"
                       :token "token"
                       :args ["repo" "--project" "demo" "--sync" "--query-index"]})
            body (json/read-json (:out response) :key-fn keyword)]
        (is (= true (:ok response)))
        (is (= 0 (:exit response)))
        (is (= "ygg.init/v1" (:schema body)))
        (is (str/includes? (:sync-output body) "# Sync"))
        (is (= [{:xtdb :xtdb
                 :opts {:config-path "project.edn"
                        :project-id nil
                        :check? true
                        :query-index? true
                        :no-progress? false
                        :cwd nil}}]
               @sync-calls))))))

(deftest init-request-does-not-resolve-storage-before-project-exists
  (with-redefs [init/init!
                (fn [root opts]
                  (is (= "repo" root))
                  (is (= {:out nil
                          :force? false
                          :project-id nil
                          :name nil
                          :workbench? false
                          :task nil
                          :harness nil
                          :hooks? false
                          :skill? false
                          :mcp? false
                          :force-agent? false
                          :maintenance nil
                          :maintenance-model nil
                          :maintenance-reasoning nil
                          :maintenance-command nil}
                         opts))
                  {:schema "ygg.init/v1"
                   :project-id "generated"
                   :config "project.edn"})
                registry/resolve-project
                (fn [& _]
                  (throw (ex-info "init should not resolve project registry before creation" {})))
                store/storage-path
                (fn [& _]
                  (throw (ex-info "init should not resolve graph storage before creation" {})))
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "init should not use generic CLI dispatch" {})))]
    (let [response (server/handle-request
                    {:xtdb :xtdb
                     :token "token"
                     :running (atom true)}
                    {:op "init"
                     :token "token"
                     :args ["repo"]})
          body (json/read-json (:out response) :key-fn keyword)]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "ygg.init/v1" (:schema body)))
      (is (= "generated" (:project-id body)))
      (is (not (contains? body :sync-output))))))

(deftest query-cache-warmups-are-deduplicated-by-storage-and-scope
  (let [executor (java.util.concurrent.Executors/newSingleThreadExecutor)
        warmups (atom {})
        started (promise)
        release (promise)
        calls (atom 0)
        ctx {:query-warmup-executor executor
             :query-warmups warmups}
        scope {:project-id "fixture"
               :repo-id "app"}]
    (try
      (let [first-operation (#'server/start-query-warmup!
                             ctx
                             "/storage/fixture"
                             scope
                             (fn []
                               (swap! calls inc)
                               (deliver started true)
                               @release))]
        @started
        (let [second-operation (#'server/start-query-warmup!
                                ctx
                                "/storage/fixture"
                                scope
                                #(swap! calls inc))]
          (is (= "query-warmup" (:op first-operation)))
          (is (= (select-keys first-operation [:projectId :repoId :startedAtMs])
                 (select-keys second-operation [:projectId :repoId :startedAtMs])))
          (is (not (neg? (:elapsedMs second-operation))))
          (is (= 1 @calls)))
        (deliver release true)
        (loop [attempts 100]
          (when (and (pos? attempts) (seq @warmups))
            (Thread/sleep 5)
            (recur (dec attempts))))
        (is (empty? @warmups)))
      (finally
        (.shutdownNow executor)
        (.awaitTermination executor 1 java.util.concurrent.TimeUnit/SECONDS)))))

(deftest sync-request-resolves-explicit-project-ref-before-storage
  (let [root (temp-dir "ygg-server-sync-ref")
        checkout (io/file root "checkout")
        registry-path (.getPath (io/file root ".config" "projects.edn"))
        storage-projects (atom [])
        expected-config-path (atom nil)]
    (.mkdirs checkout)
    (with-redefs [registry/registry-path (constantly registry-path)
                  store/storage-path (fn
                                       ([] "/store/default")
                                       ([project-id]
                                        (swap! storage-projects conj project-id)
                                        (str "/store/" project-id)))
                  server/run-sync! (fn [xtdb opts]
                                     (is (= :xtdb xtdb))
                                     (is (= {:config-path @expected-config-path
                                             :project-id nil
                                             :cwd (.getPath checkout)}
                                            (select-keys opts [:config-path
                                                               :project-id
                                                               :cwd])))
                                     {:schema "ygg.sync/v1"
                                      :project-id "fixture"
                                      :index-summary {:repos []}})]
      (registry/upsert-project! {:id "fixture"
                                 :name "Fixture"
                                 :repos [{:id "app"
                                          :root (.getPath checkout)
                                          :role :application}]})
      (let [ref-path (registry/write-project-ref! (.getPath checkout) "fixture")
            _ (reset! expected-config-path ref-path)
            response (server/handle-request
                      {:xtdb :xtdb
                       :token "token"
                       :running (atom true)}
                      {:op "sync"
                       :token "token"
                       :cwd (.getPath checkout)
                       :args [".ygg/project.edn" "--json"]})
            body (json/read-json (:out response) :key-fn keyword)]
        (is (= true (:ok response)))
        (is (= 0 (:exit response)))
        (is (= "fixture" (:project-id body)))
        (is (= ["fixture"] @storage-projects))))))

(deftest run-sync-resolves-explicit-project-ref-config-path
  (let [root (temp-dir "ygg-server-run-sync-ref")
        checkout (io/file root "checkout")
        registry-path (.getPath (io/file root ".config" "projects.edn"))
        calls (atom [])]
    (.mkdirs checkout)
    (with-redefs [registry/registry-path (constantly registry-path)
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [xtdb project]
                                           (swap! calls conj [:infer xtdb (:id project)])
                                           {:project-id (:id project)
                                            :status :completed
                                            :system-evidence 1
                                            :system-nodes 2
                                            :system-edges 3})
                  project/maintain-project (fn [xtdb project opts]
                                             (swap! calls conj [:check xtdb (:id project) opts])
                                             {:project-id (:id project)
                                              :counts {:maintenance-decisions 0}
                                              :decision-queue []})]
      (registry/upsert-project! {:id "fixture"
                                 :name "Fixture"
                                 :repos [{:id "app"
                                          :root (.getPath checkout)
                                          :role :application}]})
      (let [ref-path (registry/write-project-ref! (.getPath checkout) "fixture")
            result (server/run-sync! :xtdb {:config-path ref-path
                                            :cwd (.getPath checkout)
                                            :check? true
                                            :json? true})]
        (is (= "ygg.sync/v1" (:schema result)))
        (is (= "fixture" (:project-id result)))
        (is (= [[:index :xtdb "fixture" {:dry-run? false
                                         :index-profile :graph
                                         :correction-overlay nil}]
                [:infer :xtdb "fixture"]
                [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                         :correction-overlay nil}]]
               @calls))))))

(deftest generic-command-op-routes-project-id-to-warm-node
  (with-redefs [store/storage-path
                (fn
                  ([] "/store/default/xtdb")
                  ([project-id] (str "/store/projects/" project-id "/xtdb")))
                cli/dispatch
                (fn [command args]
                  (store/with-node (store/storage-path)
                    (fn [xtdb]
                      (println (str "command=" command
                                    " args=" (pr-str args)
                                    " xtdb=" xtdb
                                    " storage=" (store/storage-path))))))]
    (is (= {:ok true
            :exit 0
            :out "command=packages args=[\"--project\" \"demo\"] xtdb=:xtdb storage=/store/projects/demo/xtdb\n"
            :err ""}
           (server/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "packages"
                                   :token "token"
                                   :args ["--project" "demo"]})))))

(deftest view-request-routes-through-explicit-server-handler
  (with-redefs [store/storage-path
                (fn
                  ([] "/store/default/xtdb")
                  ([project-id] (str "/store/projects/" project-id "/xtdb")))
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "view should not use generic CLI dispatch" {})))
                cli-query/view!
                (fn [args]
                  (store/with-node (store/storage-path)
                    (fn [xtdb]
                      (println (str "command=view"
                                    " args=" (pr-str args)
                                    " xtdb=" xtdb
                                    " storage=" (store/storage-path))))))]
    (is (= {:ok true
            :exit 0
            :out "command=view args=[\"systems\" \"--project\" \"demo\"] xtdb=:xtdb storage=/store/projects/demo/xtdb\n"
            :err ""}
           (server/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "view"
                                   :token "token"
                                   :args ["systems" "--project" "demo"]})))))

(deftest command-requests-open-project-nodes-on-demand
  (let [opened (atom [])
        node-pool (atom {})
        ctx {:token "token"
             :running (atom true)
             :node-pool node-pool}]
    (with-redefs [store/storage-path
                  (fn
                    ([] "/store/default/xtdb")
                    ([project-id] (str "/store/projects/" project-id "/xtdb")))
                  store/open-node!
                  (fn [path]
                    (swap! opened conj path)
                    {:path path})
                  store/stop-node! (fn [_node] nil)
                  cli/dispatch
                  (fn [command args]
                    (store/with-node (store/storage-path)
                      (fn [xtdb]
                        (println (str "command=" command
                                      " project=" (last args)
                                      " xtdb=" (:path xtdb))))))]
      (let [first-response (server/handle-request
                            ctx
                            {:op "packages"
                             :token "token"
                             :args ["--project" "demo-a"]})
            second-response (server/handle-request
                             ctx
                             {:op "packages"
                              :token "token"
                              :args ["--project" "demo-b"]})]
        (is (= true (:ok first-response)))
        (is (= true (:ok second-response)))
        (is (= ["/store/projects/demo-a/xtdb"
                "/store/projects/demo-b/xtdb"]
               @opened))
        (is (= #{"/store/projects/demo-a/xtdb"
                 "/store/projects/demo-b/xtdb"}
               (set (keys @node-pool))))))))

(deftest report-request-routes-config-path-to-on-demand-project-node
  (let [opened (atom [])
        node-pool (atom {})
        ctx {:token "token"
             :running (atom true)
             :node-pool node-pool}]
    (with-redefs [project/read-project
                  (fn [path]
                    (is (= (canonical-path "new-project.edn") path))
                    {:id "new-project"})
                  store/storage-path
                  (fn
                    ([] "/store/default/xtdb")
                    ([project-id] (str "/store/projects/" project-id "/xtdb")))
                  store/open-node!
                  (fn [path]
                    (swap! opened conj path)
                    {:path path})
                  store/stop-node! (fn [_node] nil)
                  cli/dispatch
                  (fn [& _]
                    (throw (ex-info "report should not use generic CLI dispatch" {})))
                  cli-query/report!
                  (fn [args]
                    (store/with-node (store/storage-path)
                      (fn [xtdb]
                        (println (str "command=report"
                                      " args=" (pr-str args)
                                      " xtdb=" (:path xtdb))))))]
      (let [response (server/handle-request
                      ctx
                      {:op "report"
                       :token "token"
                       :args ["new-project.edn"]})]
        (is (= true (:ok response)))
        (is (= "command=report args=[\"new-project.edn\"] xtdb=/store/projects/new-project/xtdb\n"
               (:out response)))
        (is (= ["/store/projects/new-project/xtdb"] @opened))
        (is (contains? @node-pool "/store/projects/new-project/xtdb"))))))

(deftest removed-public-command-ops-are-not-server-handled
  (with-redefs [cli/dispatch
                (fn [& _]
                  (throw (ex-info "removed command should not dispatch" {})))]
    (doseq [command ["classify"
                     "context"
                     "deps"
                     "docs"
                     "graph"
                     "index"
                     "meta"
                     "path"
                     "project"
                     "queue"
                     "sync-inspect"
                     "systems"
                     "service"
                     "watch"
                     "views"]]
      (is (= {:ok false
              :exit 1
              :out ""
              :err (str "Unknown server op: " command "\n")}
             (server/handle-request {:xtdb :xtdb
                                     :token "token"
                                     :running (atom true)}
                                    {:op command
                                     :token "token"
                                     :args []}))))))

(deftest generic-cli-op-is-not-server-handled
  (is (= {:ok false
          :exit 1
          :out ""
          :err "Unknown server op: cli\n"}
         (server/handle-request {:xtdb :xtdb
                                 :token "token"
                                 :running (atom true)}
                                {:op "cli"
                                 :token "token"
                                 :args ["query" "hello"]}))))

(deftest generic-command-op-is-not-server-handled
  (is (= {:ok false
          :exit 1
          :out ""
          :err "Unknown server op: command\n"}
         (server/handle-request {:xtdb :xtdb
                                 :token "token"
                                 :running (atom true)}
                                {:op "command"
                                 :command "docs"
                                 :token "token"
                                 :args []}))))

(deftest sync-subcommand-args-route-sync-command
  (with-redefs [cli/dispatch
                (fn [command args]
                  (println (str "command=" command " args=" (pr-str args))))]
    (is (= {:ok true
            :exit 0
            :out "command=sync args=[\"inspect\" \"project.edn\" \"--json\"]\n"
            :err ""}
           (server/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "sync"
                                   :token "token"
                                   :args ["inspect" "project.edn" "--json"]})))))

(deftest query-request-returns-command-output
  (with-redefs [cli/dispatch
                (fn [& _]
                  (throw (ex-info "query should not use generic CLI dispatch" {})))
                cli-query/query-with-node!
                (fn [xtdb args]
                  (println (str "xtdb=" xtdb " args=" (pr-str args)))
                  (binding [*out* *err*]
                    (println "query warning")))]
    (is (= {:ok true
            :exit 0
            :out "xtdb=:xtdb args=[\"where\" \"--project\" \"demo\"]\n"
            :err "query warning\n"}
           (server/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "query"
                                   :token "token"
                                   :args ["where" "--project" "demo"]})))))

(deftest command-requests-use-server-owned-semantic-client-pool
  (let [created (atom 0)
        closed (atom 0)
        pool (atom {})]
    (with-redefs [embedding-client/provider-client
                  (fn
                    ([provider model]
                     (swap! created inc)
                     {:provider provider
                      :model model
                      :embed-batch (fn [_] [])
                      :close (fn [] (swap! closed inc))})
                    ([provider model _opts]
                     (swap! created inc)
                     {:provider provider
                      :model model
                      :embed-batch (fn [_] [])
                      :close (fn [] (swap! closed inc))}))
                  cli/dispatch
                  (fn [command args]
                    (let [client (embedding-client/query-embedding-client
                                  :hybrid
                                  :local
                                  "server-model")]
                      (println (str "command=" command
                                    " args=" (pr-str args)
                                    " provider=" (name (:provider client))
                                    " model=" (:model client)))))]
      (let [ctx {:xtdb :xtdb
                 :token "token"
                 :running (atom true)
                 :semantic-client-pool pool}
            request {:op "packages"
                     :token "token"
                     :args ["--project" "demo"]}]
        (is (= "command=packages args=[\"--project\" \"demo\"] provider=local model=server-model\n"
               (:out (server/handle-request ctx request))))
        (is (= "command=packages args=[\"--project\" \"demo\"] provider=local model=server-model\n"
               (:out (server/handle-request ctx request))))
        (is (= 1 @created))
        (embedding-client/close-client-pool! pool)
        (is (= 1 @closed))))))

(deftest sync-request-runs-sync-inside-server
  (with-redefs [project/read-project
                (fn [path]
                  (is (= (canonical-path "project.edn") path))
                  {:id "demo"})
                cli-sync/sync-index-project!
                (fn [xtdb project args deps opts]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn")
                          "--repo" "app" "--check" "--json"]
                         args))
                  (is (map? deps))
                  (is (= {:config-path (canonical-path "project.edn")
                          :repo-id "app"
                          :check? true
                          :json? true}
                         (select-keys opts [:config-path :repo-id :check? :json?])))
                  {:project-id "demo"
                   :repos [{:repo-id "app"
                            :status :completed}]})
                project/infer-project!
                (fn [xtdb project]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  {:system-nodes 1
                   :system-edges 2})
                cli-sync/maintenance-report
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn")
                          "--repo" "app" "--check" "--json"]
                         args))
                  (is (map? deps))
                  {:counts {:maintenance-decisions 3}})
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "unexpected command handler dispatch" {})))]
    (let [response (server/handle-request {:xtdb :xtdb
                                           :token "token"
                                           :running (atom true)}
                                          {:op "sync"
                                           :token "token"
                                           :args ["project.edn" "--repo" "app" "--check" "--json"]})]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "" (:err response)))
      (is (= "ygg.sync/v1" (get-in response [:data :schema])))
      (is (= "demo" (get-in response [:data :project-id])))
      (is (= "app" (get-in response [:data :repo-id])))
      (is (= 3 (get-in response [:data :check-report :counts :maintenance-decisions])))
      (is (re-find #"ygg\.sync" (:out response))))))

(deftest sync-request-emits-server-progress-frames-when-streaming
  (let [frames (atom [])]
    (with-redefs [project/read-project
                  (fn [path]
                    (is (= (canonical-path "project.edn") path))
                    {:id "demo"})
                  cli-sync/sync-index-project!
                  (fn [xtdb project args deps opts]
                    (is (= :xtdb xtdb))
                    (is (= {:id "demo"} project))
                    (is (= [(canonical-path "project.edn")] args))
                    (is (map? deps))
                    (is (fn? (:progress-fn opts)))
                    ((:progress-fn opts) {:phase :scan-complete
                                          :repo-id "app"
                                          :files-scanned 2})
                    {:project-id "demo"
                     :status :completed
                     :repos [{:repo-id "app"
                              :status :completed}]})
                  project/infer-project!
                  (fn [xtdb project]
                    (is (= :xtdb xtdb))
                    (is (= {:id "demo"} project))
                    {:system-nodes 1})
                  cli/dispatch
                  (fn [& _]
                    (throw (ex-info "unexpected command handler dispatch" {})))]
      (let [response (server/handle-request {:xtdb :xtdb
                                             :token "token"
                                             :running (atom true)
                                             :emit-frame! #(swap! frames conj %)}
                                            {:op "sync"
                                             :token "token"
                                             :args ["project.edn"]})]
        (is (= true (:ok response)))
        (is (= 0 (:exit response)))
        (is (= [{:schema server/server-frame-schema
                 :type "progress"
                 :operation "sync"
                 :message "app scanned 2 files"
                 :event {:phase :scan-complete
                         :repo-id "app"
                         :files-scanned 2}}]
               @frames))
        (is (not (str/includes? (:err response) "# Sync Progress")))))))

(deftest sync-with-check-runs-maintenance-inside-server
  (with-redefs [project/read-project
                (fn [path]
                  (is (= (canonical-path "project.edn") path))
                  {:id "demo"})
                cli-sync/sync-index-project!
                (fn [xtdb project args deps opts]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn")
                          "--check" "--enqueue" "--json"]
                         args))
                  (is (map? deps))
                  (is (= {:config-path (canonical-path "project.edn")
                          :check? true
                          :enqueue? true
                          :json? true}
                         (select-keys opts [:config-path :check? :enqueue? :json?])))
                  {:project-id "demo"
                   :repos [{:repo-id "app"
                            :status :completed}]})
                project/infer-project!
                (fn [xtdb project]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  {:system-nodes 1
                   :system-edges 2})
                cli-sync/maintenance-report
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn")
                          "--check" "--enqueue" "--json"]
                         args))
                  (is (map? deps))
                  {:project-id "demo"
                   :decision-queue [{:id "decision-1"
                                     :project-id "demo"
                                     :severity :medium}]})
                cli-sync/enqueue-sync-work!
                (fn [args report deps]
                  (is (= [(canonical-path "project.edn")
                          "--check" "--enqueue" "--json"]
                         args))
                  (is (= "demo" (:project-id report)))
                  (is (map? deps))
                  [{:id "work-1"
                    :kind "maintenance-decision"
                    :status "ready"
                    :enqueue-status "enqueued"}])
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "unexpected command handler dispatch" {})))]
    (let [response (server/handle-request {:xtdb :xtdb
                                           :token "token"
                                           :running (atom true)}
                                          {:op "sync"
                                           :token "token"
                                           :args ["project.edn" "--check" "--enqueue" "--json"]})]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "" (:err response)))
      (is (= "ygg.sync/v1" (get-in response [:data :schema])))
      (is (= "demo" (get-in response [:data :project-id])))
      (is (= "work-1" (get-in response [:data :enqueued 0 :id])))
      (is (= {:items 1
              :enqueued 1
              :existing 0
              :over-emitted 0
              :by-status {"enqueued" 1}
              :by-kind {"maintenance-decision" 1}}
             (get-in response [:data :enqueue-summary])))
      (is (re-find #"ygg\.sync" (:out response))))))

(deftest sync-skips-system-inference-when-index-has-no-changes
  (with-redefs [project/read-project
                (fn [path]
                  (is (= (canonical-path "project.edn") path))
                  {:id "demo"})
                cli-sync/sync-index-project!
                (fn [xtdb project args deps opts]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn") "--check" "--json"]
                         args))
                  (is (map? deps))
                  (is (= {:config-path (canonical-path "project.edn")
                          :check? true
                          :json? true}
                         (select-keys opts [:config-path :check? :json?])))
                  {:project-id "demo"
                   :repos [{:repo-id "app"
                            :status :completed
                            :stats {:files-indexed 0
                                    :files-deleted 0}}]})
                project/infer-project!
                (fn [& _]
                  (throw (ex-info "unchanged index should not rewrite system graph" {})))
                cli-sync/maintenance-report
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= [(canonical-path "project.edn") "--check" "--json"]
                         args))
                  (is (map? deps))
                  {:project-id "demo"
                   :counts {:maintenance-decisions 0}})
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "unexpected command handler dispatch" {})))]
    (let [response (server/handle-request {:xtdb :xtdb
                                           :token "token"
                                           :running (atom true)}
                                          {:op "sync"
                                           :token "token"
                                           :args ["project.edn" "--check" "--json"]})]
      (is (= true (:ok response)))
      (is (= :skipped (get-in response [:data :system-summary :status])))
      (is (= "no-index-changes"
             (get-in response [:data :system-summary :reason])))
      (is (= 0 (get-in response [:data :check-report :counts :maintenance-decisions]))))))

(deftest sync-with-check-enqueue-runs-configured-index-maintenance-worker
  (let [root (temp-dir "ygg-server-index-maintenance")
        repo-root (io/file root "repo")
        project-edn (io/file root "project.edn")
        project-path (.getCanonicalPath project-edn)
        queue-root (temp-queue-db "ygg-server-index-maintenance-queue")
        report-root (.getCanonicalPath (io/file root "reports"))]
    (.mkdirs repo-root)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app"
                            :root "repo"
                            :role :application}]
                   :maintenance
                   {:enabled true
                    :report-dir "reports"
                    :worker {:enabled true
                             :max-items-per-run 5
                             :apply {:mode :complete-only}
                             :executors [{:id "fake-deepseek"
                                          :type :openai-compatible
                                          :provider :deepseek
                                          :model "deepseek-v4-flash"
                                          :env "YGG_DEEPSEEK_API_KEY"
                                          :kinds #{:maintenance-decision}}]}}}))
    (with-redefs [store/project-sqlite-path
                  (fn [project-id]
                    (is (= "demo" project-id))
                    queue-root)
                  cli-sync/sync-index-project!
                  (fn [xtdb project args deps opts]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:id project)))
                    (is (= [project-path
                            "--check"
                            "--enqueue"
                            "--json"]
                           args))
                    (is (map? deps))
                    (is (= {:config-path project-path
                            :check? true
                            :enqueue? true
                            :json? true}
                           (select-keys opts [:config-path
                                              :check?
                                              :enqueue?
                                              :json?])))
                    {:project-id "demo"
                     :repos [{:repo-id "app"
                              :status :completed}]})
                  project/infer-project!
                  (fn [xtdb project]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:id project)))
                    {:system-nodes 1
                     :system-edges 2})
                  cli-sync/maintenance-report
                  (fn [xtdb project args deps]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:id project)))
                    (is (= [project-path
                            "--check"
                            "--enqueue"
                            "--json"]
                           args))
                    (is (map? deps))
                    {:schema "ygg.index-maintenance.report/v1"
                     :project-id "demo"
                     :work [{:kind "maintenance-decision"}]})
                  cli-sync/enqueue-sync-work!
                  (fn [_args report deps]
                    (is (= "demo" (:project-id report)))
                    (is (map? deps))
                    (let [enqueued (queue/enqueue! (decision-packet)
                                                   {:root queue-root
                                                    :kind "maintenance-decision"
                                                    :project-id "demo"})]
                      [(assoc (queue/item-summary enqueued)
                              :enqueue-status "enqueued")]))
                  cli/dispatch
                  (fn [& _]
                    (throw (ex-info "unexpected command handler dispatch" {})))]
      (binding [index-maintenance-worker/*deps*
                {:get-env (fn [k]
                            (when (= "YGG_DEEPSEEK_API_KEY" k)
                              "test-key"))
                 :openai-client (fn [_opts]
                                  {:complete-json (fn [_messages]
                                                    (valid-result))})}]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)}
                                              {:op "sync"
                                               :token "token"
                                               :args [(.getPath project-edn)
                                                      "--check"
                                                      "--enqueue"
                                                      "--json"]})
              worker-run (get-in response [:data :maintenance-worker])
              item (get-in worker-run [:items 0])
              work-path (get-in item [:artifacts :work])
              result-path (get-in item [:artifacts :result])
              queue-id (get-in item [:item :id])]
          (is (= true (:ok response)))
          (is (= "completed" (:status worker-run)))
          (is (= {:claimed 1
                  :completed 1
                  :failed 0
                  :executor-failures 0
                  :validated 1}
                 (:counts worker-run)))
          (is (= "valid" (get-in item [:validation :status])))
          (is (= "done" (get-in (queue/find-item queue-root queue-id)
                                [:item :status])))
          (is (= queue-id (:id (read-json work-path))))
          (is (= (valid-result) (read-json result-path)))
          (is (re-find (re-pattern (java.util.regex.Pattern/quote report-root))
                       result-path)))))))

(deftest status-request-reports-server-health
  (let [response (server/handle-request {:token "token"
                                         :running (atom true)
                                         :node-pool (atom {"/tmp/ygg/xtdb" :node})
                                         :semantic-client-pool (atom {[:local "server-model"] {:provider :local
                                                                                               :model "server-model"}})
                                         :storage-path "/tmp/ygg/default"
                                         :started-at-ms 123}
                                        {:op "status"
                                         :token "token"
                                         :args []})]
    (is (= true (:ok response)))
    (is (= "ygg.server.status/v1" (get-in response [:data :schema])))
    (is (= "running" (get-in response [:data :status])))
    (is (= 1 (get-in response [:data :openNodes])))
    (is (= ["/tmp/ygg/xtdb"] (get-in response [:data :openNodePaths])))
    (is (= 1 (get-in response [:data :semanticClients])))
    (is (= [{:provider "local"
             :model "server-model"}]
           (get-in response [:data :semanticClientKeys])))
    (is (re-find #"# Server" (:out response)))
    (is (re-find #"- status running" (:out response)))
    (is (re-find #"- semantic-clients 1" (:out response)))
    (is (re-find #"  - local:server-model" (:out response)))))

(deftest status-request-reports-busy-operation-lock
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        active-operations (atom {"project:demo" {:schema "ygg.server.active-operation/v1"
                                                 :op "sync"
                                                 :args ["sync" "--project" "demo"]
                                                 :projectId "demo"
                                                 :startedAtMs (System/currentTimeMillis)}})]
    (.lock lock)
    (try
      (let [response (server/handle-request {:token "token"
                                             :running (atom true)
                                             :operation-locks (atom {"project:demo" lock})
                                             :active-operations active-operations
                                             :node-pool (atom {})}
                                            {:op "status"
                                             :token "token"
                                             :args ["--json"]})]
        (is (= true (:ok response)))
        (is (= true (get-in response [:data :busy])))
        (is (= "sync" (get-in response [:data :activeOperation :op])))
        (is (= "project:demo" (get-in response [:data :activeOperation :lockKey])))
        (is (= "demo" (get-in response [:data :activeOperation :projectId])))
        (is (integer? (get-in response [:data :activeOperation :elapsedMs]))))
      (finally
        (.unlock lock)))))

(deftest locked-command-status-reports-active-operation
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        started (promise)
        release (promise)
        handler (future
                  (with-redefs [cli/dispatch
                                (fn [command args]
                                  (deliver started true)
                                  @release
                                  (println (str "command=" command
                                                " args=" (pr-str args))))]
                    (server/handle-request ctx
                                           {:op "packages"
                                            :token "token"
                                            :cwd "/repo"
                                            :args ["--project" "demo"]})))]
    (try
      (is (= true (deref started 5000 :timeout)))
      (let [response (server/handle-request ctx
                                            {:op "status"
                                             :token "token"
                                             :args []})
            operation (get-in response [:data :activeOperation])]
        (is (= true (:ok response)))
        (is (= true (get-in response [:data :busy])))
        (is (= "packages" (:op operation)))
        (is (= "project:demo" (:lockKey operation)))
        (is (= ["packages" "--project" "demo"] (:args operation)))
        (is (= "demo" (:projectId operation)))
        (is (integer? (:elapsedMs operation)))
        (is (re-find #"- active-operation packages" (:out response))))
      (finally
        (deliver release true)
        (is (= true (:ok @handler)))
        (is (empty? @(:active-operations ctx)))))))

(deftest locked-command-does-not-block-other-project
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        alpha-started (promise)
        release-alpha (promise)]
    (with-redefs [cli/dispatch
                  (fn [command args]
                    (let [project-id (second args)]
                      (if (= "alpha" project-id)
                        (do
                          (deliver alpha-started true)
                          @release-alpha
                          (println (str "command=" command
                                        " project=" project-id)))
                        (println (str "command=" command
                                      " project=" project-id)))))]
      (let [alpha (future
                    (server/handle-request ctx
                                           {:op "packages"
                                            :token "token"
                                            :args ["--project" "alpha"]}))]
        (try
          (is (= true (deref alpha-started 5000 :timeout)))
          (let [beta (server/handle-request ctx
                                            {:op "packages"
                                             :token "token"
                                             :args ["--project" "beta"]})]
            (is (= true (:ok beta)))
            (is (= "command=packages project=beta\n" (:out beta))))
          (finally
            (deliver release-alpha true)
            (is (= true (:ok @alpha)))))))))

(deftest read-only-project-commands-run-while-project-operation-is-busy
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        project-started (promise)
        release-project (promise)]
    (with-redefs [cli/dispatch
                  (fn [command args]
                    (case command
                      "packages"
                      (do
                        (deliver project-started true)
                        @release-project
                        (println (str "command=" command
                                      " args=" (pr-str args))))

                      "projects"
                      (println (str "projects args=" (pr-str args)))

                      (throw (ex-info "unexpected command"
                                      {:command command
                                       :args args}))))]
      (let [project (future
                      (server/handle-request ctx
                                             {:op "packages"
                                              :token "token"
                                              :args ["--project" "demo"]}))]
        (try
          (is (= true (deref project-started 5000 :timeout)))
          (doseq [args [["list"] ["show" "demo" "--json"]]]
            (let [response (server/handle-request ctx
                                                  {:op "projects"
                                                   :token "token"
                                                   :args args})]
              (is (= true (:ok response)))
              (is (= (str "projects args=" (pr-str args) "\n")
                     (:out response)))))
          (finally
            (deliver release-project true)
            (is (= true (:ok @project)))))))))

(deftest read-only-maintenance-status-runs-while-project-operation-is-busy
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        project-started (promise)
        release-project (promise)]
    (with-redefs [cli/dispatch
                  (fn [command args]
                    (case command
                      "packages"
                      (do
                        (deliver project-started true)
                        @release-project
                        (println (str "command=" command
                                      " args=" (pr-str args))))

                      "maintenance"
                      (println (str "maintenance args=" (pr-str args)))

                      (throw (ex-info "unexpected command"
                                      {:command command
                                       :args args}))))]
      (let [project (future
                      (server/handle-request ctx
                                             {:op "packages"
                                              :token "token"
                                              :args ["--project" "demo"]}))]
        (try
          (is (= true (deref project-started 5000 :timeout)))
          (let [response (server/handle-request ctx
                                                {:op "maintenance"
                                                 :token "token"
                                                 :args ["status" "--project" "demo"]})]
            (is (= true (:ok response)))
            (is (= "maintenance args=[\"status\" \"--project\" \"demo\"]\n"
                   (:out response))))
          (finally
            (deliver release-project true)
            (is (= true (:ok @project)))))))))

(deftest project-operation-blocks-global-mutation-command
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        project-started (promise)
        release-project (promise)]
    (with-redefs [cli/dispatch
                  (fn [command args]
                    (if (= "packages" command)
                      (do
                        (deliver project-started true)
                        @release-project
                        (println (str "command=" command
                                      " args=" (pr-str args))))
                      (throw (ex-info "global mutation should not run while project is busy"
                                      {:command command
                                       :args args}))))]
      (let [project (future
                      (server/handle-request ctx
                                             {:op "packages"
                                              :token "token"
                                              :args ["--project" "demo"]}))]
        (try
          (is (= true (deref project-started 5000 :timeout)))
          (let [response (server/handle-request ctx
                                                {:op "projects"
                                                 :token "token"
                                                 :args ["register" "project.edn"]})]
            (is (= false (:ok response)))
            (is (= daemon-contract/unavailable-exit (:exit response)))
            (is (= "operation-lock-busy" (get-in response [:data :reason])))
            (is (= "project:demo" (get-in response [:data :lockKey]))))
          (finally
            (deliver release-project true)
            (is (= true (:ok @project)))))))))

(deftest bench-repos-check-runs-while-project-operation-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        called (atom nil)]
    (.lock lock)
    (try
      (with-redefs [cli/dispatch
                    (fn [command args]
                      (reset! called {:command command
                                      :args args})
                      (println "repos-check-ok"))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"project:demo" lock})
                         :active-operations (atom {"project:demo"
                                                   {:schema "ygg.server.active-operation/v1"
                                                    :op "sync"
                                                    :projectId "demo"
                                                    :startedAtMs (System/currentTimeMillis)}})}
                        {:op "bench"
                         :token "token"
                         :args ["repos" "check" "--suite" "benchmarks/task-category-broad.edn"]})]
          (is (= true (:ok response)))
          (is (= "repos-check-ok\n" (:out response)))
          (is (= {:command "bench"
                  :args ["repos" "check" "--suite" "benchmarks/task-category-broad.edn"]}
                 @called))))
      (finally
        (.unlock lock)))))

(deftest bench-agent-baseline-runs-while-project-operation-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        called (atom nil)]
    (.lock lock)
    (try
      (with-redefs [cli/dispatch
                    (fn [command args]
                      (reset! called {:command command
                                      :args args})
                      (println "agent-baseline-ok"))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"project:demo" lock})
                         :active-operations (atom {"project:demo"
                                                   {:schema "ygg.server.active-operation/v1"
                                                    :op "sync"
                                                    :projectId "demo"
                                                    :startedAtMs (System/currentTimeMillis)}})}
                        {:op "bench"
                         :token "token"
                         :args ["agent-baseline"
                                "benchmarks/task-category-broad.edn"
                                "--case"
                                "historical-dapper-prefer-enum-type-handlers"
                                "--out"
                                "/tmp/ygg-bench-out"]})]
          (is (= true (:ok response)))
          (is (= "agent-baseline-ok\n" (:out response)))
          (is (= {:command "bench"
                  :args ["agent-baseline"
                         "benchmarks/task-category-broad.edn"
                         "--case"
                         "historical-dapper-prefer-enum-type-handlers"
                         "--out"
                         "/tmp/ygg-bench-out"]}
                 @called))))
      (finally
        (.unlock lock)))))

(deftest sync-work-list-runs-while-project-operation-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        called (atom nil)]
    (.lock lock)
    (try
      (with-redefs [cli/dispatch
                    (fn [command args]
                      (reset! called {:command command
                                      :args args})
                      (println "work-list-ok"))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"project:demo" lock})
                         :active-operations (atom {"project:demo"
                                                   {:schema "ygg.server.active-operation/v1"
                                                    :op "sync"
                                                    :projectId "demo"
                                                    :startedAtMs (System/currentTimeMillis)}})}
                        {:op "sync"
                         :token "token"
                         :args ["work" "list" "--json"]})]
          (is (= true (:ok response)))
          (is (= "work-list-ok\n" (:out response)))
          (is (= {:command "sync"
                  :args ["work" "list" "--json"]}
                 @called))))
      (finally
        (.unlock lock)))))

(deftest query-request-runs-while-operation-lock-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [cli-query/query-with-node!
                    (fn [xtdb args]
                      (println (str "xtdb=" xtdb " args=" (pr-str args))))]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)
                                               :operation-locks (atom {"project:demo" lock})}
                                              {:op "query"
                                               :token "token"
                                               :args ["needle" "--project" "demo"]})]
          (is (= true (:ok response)))
          (is (= "xtdb=:xtdb args=[\"needle\" \"--project\" \"demo\"]\n"
                 (:out response)))))
      (finally
        (deliver release true)
        @holder))))

(deftest query-request-reports-active-indexing-degradation
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        active-operations (atom {"project:demo" {:schema "ygg.server.active-operation/v1"
                                                 :op "sync"
                                                 :projectId "demo"
                                                 :startedAtMs (System/currentTimeMillis)}})]
    (.lock lock)
    (try
      (with-redefs [cli/query-deps
                    (fn []
                      {:context-packet-options (fn [_ _ opts] opts)})
                    store/storage-path
                    (fn [& _]
                      (throw (ex-info "active query should not resolve graph storage" {})))
                    cli-query/query-with-node!
                    (fn [xtdb args]
                      (is (nil? xtdb))
                      (println
                       (json/write-json-str
                        ((:context-packet-options cli-query/*deps*)
                         xtdb
                         args
                         {:project-id "demo"}))))]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)
                                               :operation-locks (atom {"project:demo" lock})
                                               :active-operations active-operations}
                                              {:op "query"
                                               :token "token"
                                               :args ["needle" "--project" "demo"]})
              parsed (json/read-json (:out response) :key-fn keyword)]
          (is (= true (:ok response)))
          (is (= "sync" (get-in parsed [:active-indexing :op])))
          (is (= "demo" (get-in parsed [:active-indexing :projectId])))
          (is (= "project:demo" (get-in parsed [:active-indexing :lockKey])))
          (is (integer? (get-in parsed [:active-indexing :elapsedMs])))))
      (finally
        (.unlock lock)))))

(deftest query-request-emits-server-progress-frames-when-streaming
  (let [frames (atom [])]
    (with-redefs [cli/query-deps (constantly {})
                  cli-query/query-with-node!
                  (fn [_ _]
                    ((:progress-fn cli-query/*deps*)
                     {:phase :search-corpus-load-start
                      :project-id "demo"
                      :cache-status :miss}))]
      (let [response (server/handle-request {:xtdb :xtdb
                                             :token "token"
                                             :running (atom true)
                                             :emit-frame! #(swap! frames conj %)}
                                            {:op "query"
                                             :token "token"
                                             :args ["needle" "--project" "demo"]})]
        (is (true? (:ok response)))
        (is (= [{:schema server/server-frame-schema
                 :type "progress"
                 :operation "query"
                 :message "demo loading search corpus (cold cache)"
                 :event {:phase :search-corpus-load-start
                         :project-id "demo"
                         :cache-status :miss}}]
               @frames))))))

(deftest locked-command-request-returns-busy-without-waiting
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [cli/dispatch
                    (fn [& _]
                      (throw (ex-info "dispatch should not run while busy" {})))]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)
                                               :operation-locks (atom {"project:demo" lock})}
                                              {:op "view"
                                               :token "token"
                                               :args ["systems" "--project" "demo"]})]
          (is (= false (:ok response)))
          (is (= daemon-contract/unavailable-exit (:exit response)))
          (is (= "operation-lock-busy" (get-in response [:data :reason])))
          (is (= "Yggdrasil server is busy running another operation.\n"
                 (:err response)))))
      (finally
        (deliver release true)
        @holder))))

(deftest locked-sync-request-returns-busy-before-storage-resolution
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [project/read-project
                    (fn [& _]
                      (throw (ex-info "sync should not resolve project while busy" {})))
                    cli-sync/sync-index-project!
                    (fn [& _]
                      (throw (ex-info "sync should not run while busy" {})))]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)
                                               :operation-locks (atom {"global" lock})}
                                              {:op "sync"
                                               :token "token"
                                               :args ["project.edn"]})]
          (is (= false (:ok response)))
          (is (= daemon-contract/unavailable-exit (:exit response)))
          (is (= "operation-lock-busy" (get-in response [:data :reason])))))
      (finally
        (deliver release true)
        @holder))))

(deftest logged-command-request-emits-start-and-finish-lines
  (let [err (java.io.StringWriter.)]
    (with-redefs [cli/dispatch
                  (fn [command args]
                    (println (str "command=" command
                                  " args=" (pr-str args))))]
      (binding [*err* err]
        (let [response (server/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)
                                               :request-counter (java.util.concurrent.atomic.AtomicLong.)}
                                              {:op "current"
                                               :token "token"
                                               :cwd "/repo"
                                               :projectId "demo"
                                               :args ["--json"]})
              logs (->> (str/split-lines (str err))
                        (remove str/blank?)
                        (map #(json/read-json % :key-fn keyword))
                        vec)]
          (is (= true (:ok response)))
          (is (= ["start" "finish"] (mapv :event logs)))
          (is (= [1 1] (mapv :requestId logs)))
          (is (= ["current" "current"] (mapv :op logs)))
          (is (= "ok" (:status (second logs)))))))))

(deftest scheduler-skips-when-operation-lock-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (is (= @#'server/scheduler-busy
             (#'server/run-maintenance-schedule!
              {:operation-locks (atom {"project:demo" lock})}
              {:id "demo"}
              {:id "sync"
               :task :sync})))
      (finally
        (deliver release true)
        @holder))))

(deftest scheduler-runs-sync-with-check-and-enqueue
  (let [calls (atom [])
        worker-calls (atom [])]
    (with-redefs [server/run-sync!
                  (fn [xtdb opts]
                    (swap! calls conj {:xtdb xtdb
                                       :opts opts})
                    {:schema "ygg.sync/v1"
                     :enqueued [{:id "work-1"}]})
                  index-maintenance-worker/run!
                  (fn [project opts]
                    (swap! worker-calls conj {:project project
                                              :opts opts})
                    {:schema "ygg.index-maintenance-worker.run/v1"
                     :status "completed"
                     :counts {:claimed 1
                              :completed 1
                              :failed 0
                              :executor-failures 0
                              :validated 1}})]
      (is (= {:schema "ygg.sync/v1"
              :enqueued [{:id "work-1"}]
              :maintenance-worker {:schema "ygg.index-maintenance-worker.run/v1"
                                   :status "completed"
                                   :counts {:claimed 1
                                            :completed 1
                                            :failed 0
                                            :executor-failures 0
                                            :validated 1}}}
             (#'server/run-maintenance-schedule!
              {:xtdb :xtdb}
              {:id "demo"
               :maintenance {:worker {:enabled true}}}
              {:id "check"
               :task :sync
               :enabled true
               :check true
               :enqueue true})))
      (is (= [{:xtdb :xtdb
               :opts {:project {:id "demo"
                                :maintenance {:worker {:enabled true}}}
                      :project-id "demo"
                      :repo-id nil
                      :check? true
                      :enqueue? true
                      :query-index? nil
                      :index-profile :graph
                      :json? true
                      :no-progress? true
                      :run-worker? false}}]
             @calls))
      (is (= [{:project {:id "demo"
                         :maintenance {:worker {:enabled true}}}
               :opts {}}]
             @worker-calls)))))

(deftest scheduler-records-repo-freshness-from-index-summary
  (let [state (atom {:last-run-ms {}
                     :runs []})
        schedule {:id "latest-main"
                  :task :sync
                  :enabled true
                  :run-on-start true
                  :query-index false}
        project {:id "demo"
                 :maintenance {:enabled true
                               :schedules [schedule]}}
        sync-result {:schema "ygg.sync/v1"
                     :index-summary
                     {:repos [{:repo-id "app"
                               :status :completed
                               :git-sha "local-sha"
                               :git-state {:git-sha "local-sha"
                                           :git-branch "feature"
                                           :git-upstream "origin/feature"
                                           :git-upstream-status :behind
                                           :git-upstream-current? false
                                           :git-ahead 0
                                           :git-behind 1
                                           :git-dirty? false
                                           :git-main-ref "origin/main"
                                           :git-main-sha "main-sha"
                                           :git-main-status :behind
                                           :git-main-current? false
                                           :git-main-ahead 0
                                           :git-main-behind 2
                                           :git-stale-from-main? true}}]}}]
    (with-redefs [registry/read-registry
                  (fn []
                    {:schema registry/schema
                     :projects {"demo" project}})
                  registry/read-project
                  (fn [_registry project-id]
                    (is (= "demo" project-id))
                    project)
                  server/run-sync!
                  (fn [xtdb opts]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:project-id opts)))
                    (is (= :graph (:index-profile opts)))
                    (is (= false (:query-index? opts)))
                    (is (= false (:run-worker? opts)))
                    sync-result)]
      (#'server/scheduler-tick! {:xtdb :xtdb
                                 :scheduler-state state})
      (let [run (first (:runs @state))]
        (is (= "completed" (:status run)))
        (is (= "demo" (:projectId run)))
        (is (= "latest-main" (:scheduleId run)))
        (is (= [{:repoId "app"
                 :indexStatus "completed"
                 :localSha "local-sha"
                 :branch "feature"
                 :upstream "origin/feature"
                 :upstreamStatus "behind"
                 :upstreamCurrent false
                 :upstreamAhead 0
                 :upstreamBehind 1
                 :dirty false
                 :remoteMainRef "origin/main"
                 :remoteMainSha "main-sha"
                 :mainStatus "behind"
                 :mainCurrent false
                 :mainAhead 0
                 :mainBehind 2
                 :staleFromMain true}]
               (:repoFreshness run)))))))

(deftest scheduler-releases-project-lock-before-running-worker
  (let [ctx {:xtdb :xtdb
             :token "token"
             :running (atom true)
             :operation-locks (atom {})
             :active-operations (atom {})
             :node-pool (atom {})}
        worker-started (promise)
        release-worker (promise)]
    (with-redefs [server/run-sync!
                  (fn [xtdb opts]
                    (is (= :xtdb xtdb))
                    (is (= false (:run-worker? opts)))
                    {:schema "ygg.sync/v1"
                     :enqueued [{:id "work-1"}]})
                  index-maintenance-worker/run!
                  (fn [project opts]
                    (is (= "demo" (:id project)))
                    (is (= {} opts))
                    (deliver worker-started true)
                    @release-worker
                    {:schema "ygg.index-maintenance-worker.run/v1"
                     :status "completed"
                     :counts {:claimed 1
                              :completed 1
                              :failed 0
                              :executor-failures 0
                              :validated 1}})
                  cli/dispatch
                  (fn [command args]
                    (println (str "command=" command
                                  " args=" (pr-str args))))]
      (let [schedule-result (future
                              (#'server/run-maintenance-schedule!
                               ctx
                               {:id "demo"
                                :maintenance {:worker {:enabled true}}}
                               {:id "check"
                                :task :sync
                                :enabled true
                                :check true
                                :enqueue true}))]
        (try
          (is (= true (deref worker-started 5000 :timeout)))
          (is (= "maintenance-worker"
                 (get-in @(:active-operations ctx)
                         ["maintenance-worker:demo:check" :op])))
          (is (not (contains? @(:active-operations ctx) "project:demo")))
          (let [response (server/handle-request ctx
                                                {:op "packages"
                                                 :token "token"
                                                 :args ["--project" "demo"]})]
            (is (= true (:ok response)))
            (is (= "command=packages args=[\"--project\" \"demo\"]\n"
                   (:out response))))
          (finally
            (deliver release-worker true)))
        (is (= {:schema "ygg.sync/v1"
                :enqueued [{:id "work-1"}]
                :maintenance-worker {:schema "ygg.index-maintenance-worker.run/v1"
                                     :status "completed"
                                     :counts {:claimed 1
                                              :completed 1
                                              :failed 0
                                              :executor-failures 0
                                              :validated 1}}}
               @schedule-result))))))

(deftest status-request-rereads-project-registry
  (let [registry (atom (registry/empty-registry))
        ctx {:token "token"
             :running (atom true)
             :node-pool (atom {})
             :scheduler-state (atom {:last-run-ms {}
                                     :runs []})}]
    (with-redefs [registry/read-registry (fn [] @registry)
                  registry/read-project
                  (fn [registry project-id]
                    (or (get-in registry [:projects project-id])
                        (throw (ex-info "Project is not registered."
                                        {:project-id project-id}))))
                  embedding-client/provider-api-key (constantly nil)]
      (let [empty-response (server/handle-request ctx
                                                  {:op "status"
                                                   :token "token"
                                                   :args ["--json"]})]
        (is (= true (:ok empty-response)))
        (is (= 0 (get-in empty-response [:data :maintenance :projectCount]))))
      (swap! registry assoc-in
             [:projects "new-project"]
             {:id "new-project"
              :embeddings {:provider :openrouter
                           :model "openai/text-embedding-3-small"}
              :maintenance {:enabled true
                            :schedules []}})
      (let [updated-response (server/handle-request ctx
                                                    {:op "status"
                                                     :token "token"
                                                     :args ["--json"]})]
        (is (= true (:ok updated-response)))
        (is (= 1 (get-in updated-response [:data :maintenance :projectCount])))
        (is (= "new-project"
               (get-in updated-response
                       [:data :maintenance :projects 0 :project-id])))
        (is (= {:schema "ygg.semantic-availability/v1"
                :requested :auto
                :effective :lexical
                :provider :openrouter
                :model "openai/text-embedding-3-small"
                :semanticAvailable false
                :status :lexical-fallback
                :reason :missing-provider-credentials
                :message (str "Missing OpenRouter API key. "
                              "Set YGG_OPENROUTER_API_KEY or OPENROUTER_API_KEY. "
                              "Auto retrieval used lexical fallback.")}
               (get-in updated-response
                       [:data :maintenance :projects 0 :semantic])))))))

(deftest server-status-output-includes-worker-operational-controls
  (let [out (with-out-str
              (#'server/print-server-status
               []
               {:schema "ygg.server.status/v1"
                :status "running"
                :pid 1
                :busy false
                :queuedRequests 0
                :openNodes 0
                :openNodePaths []
                :semanticClients 0
                :semanticClientKeys []
                :maintenance {:scheduler {:running true
                                          :pollMs 5000
                                          :recentRuns [{:status "completed"
                                                        :projectId "demo"
                                                        :scheduleId "latest-main"
                                                        :repoFreshness
                                                        [{:repoId "app"
                                                          :branch "feature"
                                                          :upstream "origin/feature"
                                                          :upstreamStatus "behind"
                                                          :localSha "local-sha"
                                                          :dirty false
                                                          :remoteMainRef "origin/main"
                                                          :remoteMainSha "main-sha"
                                                          :mainStatus "behind"
                                                          :staleFromMain true
                                                          :mainAhead 0
                                                          :mainBehind 2}]}]}
                              :projects [{:project-id "demo"
                                          :enabled true
                                          :semantic {:requested :auto
                                                     :effective :lexical
                                                     :provider :openrouter
                                                     :model "openai/text-embedding-3-small"
                                                     :semanticAvailable false
                                                     :status :lexical-fallback
                                                     :reason :missing-provider-credentials}
                                          :schedules []
                                          :worker {:configured true
                                                   :enabled true
                                                   :maxItemsPerRun 8
                                                   :maxFailuresPerRun 3
                                                   :availableExecutorCount 1
                                                   :executorCount 1}}]
                              :projectCount 1
                              :enabledProjectCount 1
                              :scheduleCount 0}}))]
    (is (str/includes? out "demo enabled schedules=0 worker=enabled executors=1/1 max-items=8 max-failures=3"))
    (is (str/includes? out "semantic effective=lexical status=lexical-fallback available=false provider=openrouter model=openai/text-embedding-3-small reason=missing-provider-credentials"))
    (is (str/includes? out "repo-freshness demo latest-main app branch=feature upstream=origin/feature upstream-status=behind local-sha=local-sha dirty=false remote-main=origin/main remote-main-sha=main-sha main-status=behind stale-from-main=true main-ahead=0 main-behind=2"))))

(deftest mcp-request-handles-tools-list-through-server
  (with-redefs [store/storage-path (fn
                                     ([] "/tmp/ygg/default")
                                     ([project-id] (str "/tmp/ygg/" project-id)))]
    (let [response (server/handle-request
                    {:xtdb :xtdb
                     :token "token"
                     :running (atom true)}
                    {:op "mcp"
                     :token "token"
                     :args []
                     :message {:jsonrpc "2.0"
                               :id 1
                               :method "tools/list"
                               :params {}}})
          tools (->> (get-in response [:data :result :tools])
                     (map :name)
                     set)]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "" (:err response)))
      (is (= "2.0" (get-in response [:data :jsonrpc])))
      (is (= 1 (get-in response [:data :id])))
      (is (contains? tools "ygg_query"))
      (is (contains? tools "ygg_status")))))

(deftest mcp-tools-list-runs-while-operation-lock-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [store/storage-path
                    (fn [& _]
                      (throw (ex-info "tools/list should not resolve storage" {})))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"global" lock})}
                        {:op "mcp"
                         :token "token"
                         :args []
                         :message {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/list"
                                   :params {}}})]
          (is (= true (:ok response)))
          (is (contains? (set (map :name (get-in response [:data :result :tools])))
                         "ygg_query"))))
      (finally
        (deliver release true)
        @holder))))

(deftest read-only-mcp-tool-call-runs-while-operation-lock-is-busy
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [store/storage-path
                    (fn [& _]
                      (throw (ex-info "read-only tool should use explicit request storage" {})))
                    store/project-sqlite-path
                    (fn [project-id]
                      (is (= "fixture" project-id))
                      "/tmp/ygg/project.sqlite")
                    queue/list-summary
                    (fn [root opts]
                      {:schema queue/summary-schema
                       :queue-db root
                       :opts opts
                       :items []})]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"global" lock})}
                        {:op "mcp"
                         :token "token"
                         :storagePath "/tmp/ygg/mcp"
                         :args ["--tools" "all"]
                         :message {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/call"
                                   :params {:name "ygg_work_list"
                                            :arguments {:projectId "fixture"
                                                        :status "ready"}}}})]
          (is (= true (:ok response)))
          (is (= "ygg.queue.summary/v1"
                 (get-in response [:data :result :structuredContent :schema])))
          (is (= "/tmp/ygg/project.sqlite"
                 (get-in response [:data :result :structuredContent :queue-db])))))
      (finally
        (deliver release true)
        @holder))))

(deftest mutating-mcp-tool-call-returns-busy-without-storage-resolution
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        locked (promise)
        release (promise)
        holder (future
                 (.lock lock)
                 (try
                   (deliver locked true)
                   @release
                   (finally
                     (.unlock lock))))]
    @locked
    (try
      (with-redefs [store/storage-path
                    (fn [& _]
                      (throw (ex-info "mutating tool should not resolve storage while busy" {})))
                    queue/claim-next!
                    (fn [& _]
                      (throw (ex-info "mutating tool should not run while busy" {})))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-locks (atom {"global" lock})}
                        {:op "mcp"
                         :token "token"
                         :storagePath "/tmp/ygg/mcp"
                         :args []
                         :message {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/call"
                                   :params {:name "ygg_work_pull"
                                            :arguments {:projectId "fixture"}}}})]
          (is (= false (:ok response)))
          (is (= daemon-contract/unavailable-exit (:exit response)))
          (is (= "operation-lock-busy" (get-in response [:data :reason])))))
      (finally
        (deliver release true)
        @holder))))
