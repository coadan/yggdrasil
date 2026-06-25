(ns ygg.server-test
  (:require [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync :as cli-sync]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
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
                    (is (= "new-project.edn" path))
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
                     "daemon"
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

(deftest sync-request-runs-sync-inside-server
  (with-redefs [project/read-project
                (fn [path]
                  (is (= "project.edn" path))
                  {:id "demo"})
                cli-sync/sync-index-project!
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= ["project.edn" "--repo" "app" "--check" "--json"] args))
                  (is (map? deps))
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
                  (is (= ["project.edn" "--repo" "app" "--check" "--json"] args))
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
                    (is (= "project.edn" path))
                    {:id "demo"})
                  cli-sync/sync-index-project!
                  (fn
                    ([_xtdb _project _args _deps]
                     (throw (ex-info "streaming sync should pass progress options" {})))
                    ([xtdb project args deps opts]
                     (is (= :xtdb xtdb))
                     (is (= {:id "demo"} project))
                     (is (= ["project.edn"] args))
                     (is (map? deps))
                     (is (fn? (:progress-fn opts)))
                     ((:progress-fn opts) {:phase :scan-complete
                                           :repo-id "app"
                                           :files-scanned 2})
                     {:project-id "demo"
                      :status :completed
                      :repos [{:repo-id "app"
                               :status :completed}]}))
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
                  (is (= "project.edn" path))
                  {:id "demo"})
                cli-sync/sync-index-project!
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= ["project.edn" "--check" "--enqueue" "--json"] args))
                  (is (map? deps))
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
                  (is (= ["project.edn" "--check" "--enqueue" "--json"] args))
                  (is (map? deps))
                  {:project-id "demo"
                   :decision-queue [{:id "decision-1"
                                     :project-id "demo"
                                     :severity :medium}]})
                cli-sync/enqueue-sync-work!
                (fn [args report deps]
                  (is (= ["project.edn" "--check" "--enqueue" "--json"] args))
                  (is (= "demo" (:project-id report)))
                  (is (map? deps))
                  [{:id "work-1"
                    :kind "maintenance-decision"
                    :status "ready"}])
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
      (is (re-find #"ygg\.sync" (:out response))))))

(deftest sync-with-check-enqueue-runs-configured-index-maintenance-worker
  (let [root (temp-dir "ygg-server-index-maintenance")
        repo-root (io/file root "repo")
        project-edn (io/file root "project.edn")
        queue-root (.getCanonicalPath (io/file root "queue"))
        report-root (.getCanonicalPath (io/file root "reports"))]
    (.mkdirs repo-root)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app"
                            :root "repo"
                            :role :application}]
                   :maintenance
                   {:enabled true
                    :queue-dir "queue"
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
    (with-redefs [cli-sync/sync-index-project!
                  (fn [xtdb project args deps]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:id project)))
                    (is (= [(.getPath project-edn)
                            "--queue-dir"
                            queue-root
                            "--check"
                            "--enqueue"
                            "--json"]
                           args))
                    (is (map? deps))
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
                    (is (= [(.getPath project-edn)
                            "--queue-dir"
                            queue-root
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
                      [(queue/item-summary enqueued)]))
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
    (is (re-find #"# Server" (:out response)))
    (is (re-find #"- status running" (:out response)))))

(deftest status-request-reports-busy-operation-lock
  (let [lock (java.util.concurrent.locks.ReentrantLock.)]
    (.lock lock)
    (try
      (let [response (server/handle-request {:token "token"
                                             :running (atom true)
                                             :operation-lock lock
                                             :node-pool (atom {})}
                                            {:op "status"
                                             :token "token"
                                             :args ["--json"]})]
        (is (= true (:ok response)))
        (is (= true (get-in response [:data :busy]))))
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
                                               :operation-lock lock}
                                              {:op "query"
                                               :token "token"
                                               :args ["needle" "--project" "demo"]})]
          (is (= true (:ok response)))
          (is (= "xtdb=:xtdb args=[\"needle\" \"--project\" \"demo\"]\n"
                 (:out response)))))
      (finally
        (deliver release true)
        @holder))))

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
                                               :operation-lock lock}
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
                                               :operation-lock lock}
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
              {:operation-lock lock}
              {:id "demo"}
              {:id "sync"
               :task :sync})))
      (finally
        (deliver release true)
        @holder))))

(deftest scheduler-runs-sync-with-check-and-enqueue
  (let [calls (atom [])]
    (with-redefs [server/run-sync!
                  (fn [xtdb opts]
                    (swap! calls conj {:xtdb xtdb
                                       :opts opts})
                    {:schema "ygg.sync/v1"
                     :enqueued [{:id "work-1"}]
                     :maintenance-worker {:schema "ygg.index-maintenance-worker.run/v1"
                                          :status "completed"
                                          :counts {:claimed 1
                                                   :completed 1
                                                   :failed 0
                                                   :executor-failures 0
                                                   :validated 1}}})]
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
                      :json? true
                      :no-progress? true}}]
             @calls)))))

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
                                        {:project-id project-id}))))]
      (let [empty-response (server/handle-request ctx
                                                  {:op "status"
                                                   :token "token"
                                                   :args ["--json"]})]
        (is (= true (:ok empty-response)))
        (is (= 0 (get-in empty-response [:data :maintenance :projectCount]))))
      (swap! registry assoc-in
             [:projects "new-project"]
             {:id "new-project"
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
                       [:data :maintenance :projects 0 :project-id])))))))

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
                         :operation-lock lock}
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

(deftest mcp-tool-call-returns-busy-without-storage-resolution
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
                      (throw (ex-info "tools/call should not resolve storage while busy" {})))]
        (let [response (server/handle-request
                        {:xtdb :xtdb
                         :token "token"
                         :running (atom true)
                         :operation-lock lock}
                        {:op "mcp"
                         :token "token"
                         :args []
                         :message {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/call"
                                   :params {:name "ygg_status"
                                            :arguments {}}}})]
          (is (= false (:ok response)))
          (is (= daemon-contract/unavailable-exit (:exit response)))
          (is (= "operation-lock-busy" (get-in response [:data :reason])))))
      (finally
        (deliver release true)
        @holder))))
