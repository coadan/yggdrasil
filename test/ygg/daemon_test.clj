(ns ygg.daemon-test
  (:require [ygg.cli-query :as cli-query]
            [ygg.cli-sync-inspect :as sync-inspect]
            [ygg.daemon :as daemon]
            [clojure.test :refer [deftest is]]))

(deftest sync-inspect-request-returns-command-output
  (with-redefs [sync-inspect/project-status-output
                (fn [xtdb args]
                  (str "xtdb=" xtdb " args=" (pr-str args) "\n"))]
    (is (= {:ok true
            :exit 0
            :out "xtdb=:xtdb args=[\"project.edn\" \"--json\"]\n"
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
