(ns ygg.daemon-test
  (:require [ygg.cli-sync-inspect :as sync-inspect]
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
