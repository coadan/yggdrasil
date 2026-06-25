(ns ygg.daemon-test
  (:require [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync :as cli-sync]
            [ygg.daemon :as daemon]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.project :as project]
            [ygg.queue :as queue]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

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
   :reason "Evidence is insufficient for an automatic map patch."
   :mapPatch []})

(deftest cli-request-dispatches-command-with-warm-node
  (with-redefs [cli/dispatch
                (fn [command args]
                  (store/with-node (store/storage-path)
                    (fn [xtdb]
                      (println (str "command=" command
                                    " args=" (pr-str args)
                                    " xtdb=" xtdb)))))]
    (is (= {:ok true
            :exit 0
            :out "command=view args=[\"systems\" \"--project\" \"demo\"] xtdb=:xtdb\n"
            :err ""}
           (daemon/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "cli"
                                   :token "token"
                                   :args ["view" "systems" "--project" "demo"]})))))

(deftest cli-request-preserves-non-daemon-storage-paths
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
           (daemon/handle-request {:xtdb {:path "default/xtdb"}
                                   :token "token"
                                   :running (atom true)}
                                  {:op "cli"
                                   :token "token"
                                   :args ["bench" "agent-report" "suite.edn"]})))))

(deftest cli-request-routes-project-id-to-warm-node
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
            :out "command=view args=[\"systems\" \"--project\" \"demo\"] xtdb=:xtdb storage=/store/projects/demo/xtdb\n"
            :err ""}
           (daemon/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "cli"
                                   :token "token"
                                   :args ["view" "systems" "--project" "demo"]})))))

(deftest sync-inspect-request-uses-normal-cli-dispatch
  (with-redefs [cli/dispatch
                (fn [command args]
                  (println (str "command=" command " args=" (pr-str args))))]
    (is (= {:ok true
            :exit 0
            :out "command=sync args=[\"inspect\" \"project.edn\" \"--json\"]\n"
            :err ""}
           (daemon/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "sync-inspect"
                                   :token "token"
                                   :args ["project.edn" "--json"]})))))

(deftest query-request-returns-command-output
  (with-redefs [cli-query/query-with-node!
                (fn [xtdb args]
                  (println (str "xtdb=" xtdb " args=" (pr-str args)))
                  (binding [*out* *err*]
                    (println "query warning")))]
    (is (= {:ok true
            :exit 0
            :out "xtdb=:xtdb args=[\"where\" \"--project\" \"demo\"]\n"
            :err "query warning\n"}
           (daemon/handle-request {:xtdb :xtdb
                                   :token "token"
                                   :running (atom true)}
                                  {:op "query"
                                   :token "token"
                                   :args ["where" "--project" "demo"]})))))

(deftest sync-request-runs-sync-inside-daemon
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
                  (throw (ex-info "generic CLI dispatch should not run" {})))]
    (let [response (daemon/handle-request {:xtdb :xtdb
                                           :token "token"
                                           :running (atom true)}
                                          {:op "sync"
                                           :token "token"
                                           :args ["project.edn"
                                                  "--repo"
                                                  "app"
                                                  "--check"
                                                  "--json"]})]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "" (:err response)))
      (is (= "ygg.sync/v1" (get-in response [:data :schema])))
      (is (= "demo" (get-in response [:data :project-id])))
      (is (= "app" (get-in response [:data :repo-id])))
      (is (= 3 (get-in response [:data :check-report :counts :maintenance-decisions])))
      (is (re-find #"ygg\.sync" (:out response))))))

(deftest sync-check-request-runs-maintenance-inside-daemon
  (with-redefs [project/read-project
                (fn [path]
                  (is (= "project.edn" path))
                  {:id "demo"})
                cli-sync/maintenance-report
                (fn [xtdb project args deps]
                  (is (= :xtdb xtdb))
                  (is (= {:id "demo"} project))
                  (is (= ["project.edn" "--enqueue" "--json"] args))
                  (is (map? deps))
                  {:project-id "demo"
                   :decision-queue [{:id "decision-1"
                                     :project-id "demo"
                                     :severity :medium}]})
                cli-sync/enqueue-sync-work!
                (fn [args report deps]
                  (is (= ["project.edn" "--enqueue" "--json"] args))
                  (is (= "demo" (:project-id report)))
                  (is (map? deps))
                  [{:id "work-1"
                    :kind "maintenance-decision"
                    :status "ready"}])
                cli/dispatch
                (fn [& _]
                  (throw (ex-info "generic CLI dispatch should not run" {})))]
    (let [response (daemon/handle-request {:xtdb :xtdb
                                           :token "token"
                                           :running (atom true)}
                                          {:op "sync-check"
                                           :token "token"
                                           :args ["project.edn"
                                                  "--enqueue"
                                                  "--json"]})]
      (is (= true (:ok response)))
      (is (= 0 (:exit response)))
      (is (= "" (:err response)))
      (is (= "ygg.sync.check/v1" (get-in response [:data :schema])))
      (is (= "work-1" (get-in response [:data :enqueued 0 :id])))
      (is (re-find #"ygg\.sync\.check" (:out response))))))

(deftest sync-check-enqueue-runs-configured-index-maintenance-worker
  (let [root (temp-dir "ygg-daemon-index-maintenance")
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
                   :index-maintenance-worker
                   {:enabled true
                    :queue-dir "queue"
                    :report-dir "reports"
                    :max-items-per-run 5
                    :apply {:mode :complete-only}
                    :executors [{:id "fake-deepseek"
                                 :type :openai-compatible
                                 :provider :deepseek
                                 :model "deepseek-v4-flash"
                                 :env "YGG_DEEPSEEK_API_KEY"
                                 :kinds #{:maintenance-decision}}]}}))
    (with-redefs [cli-sync/maintenance-report
                  (fn [xtdb project args deps]
                    (is (= :xtdb xtdb))
                    (is (= "demo" (:id project)))
                    (is (= [(.getPath project-edn)
                            "--queue-dir"
                            queue-root
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
                    (throw (ex-info "generic CLI dispatch should not run" {})))]
      (binding [index-maintenance-worker/*deps*
                {:get-env (fn [k]
                            (when (= "YGG_DEEPSEEK_API_KEY" k)
                              "test-key"))
                 :openai-client (fn [_opts]
                                  {:complete-json (fn [_messages]
                                                    (valid-result))})}]
        (let [response (daemon/handle-request {:xtdb :xtdb
                                               :token "token"
                                               :running (atom true)}
                                              {:op "sync-check"
                                               :token "token"
                                               :args [(.getPath project-edn)
                                                      "--enqueue"
                                                      "--json"]})
              worker-run (get-in response [:data :index-maintenance-worker])
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

(deftest status-request-reports-daemon-health
  (let [response (daemon/handle-request {:token "token"
                                         :running (atom true)
                                         :node-pool (atom {"/tmp/ygg/xtdb" :node})
                                         :storage-path "/tmp/ygg/default"
                                         :started-at-ms 123}
                                        {:op "status"
                                         :token "token"
                                         :args []})]
    (is (= true (:ok response)))
    (is (= "ygg.daemon.status/v1" (get-in response [:data :schema])))
    (is (= "running" (get-in response [:data :status])))
    (is (= 1 (get-in response [:data :openNodes])))
    (is (re-find #"# Daemon" (:out response)))
    (is (re-find #"- status running" (:out response)))))
