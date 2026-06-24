(ns ygg.daemon-test
  (:require [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync :as cli-sync]
            [ygg.daemon :as daemon]
            [ygg.project :as project]
            [ygg.xtdb :as store]
            [clojure.test :refer [deftest is]]))

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
