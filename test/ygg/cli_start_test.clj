(ns ygg.cli-start-test
  (:require [ygg.cli-start :as cli-start]
            [ygg.init :as init]
            [clojure.test :refer [deftest is]]))

(deftest init-sync-dispatches-through-explicit-deps
  (let [printed (atom nil)
        calls (atom [])]
    (with-redefs [init/init! (fn [root opts]
                               (swap! calls conj [:init root opts])
                               {:schema "ygg.init/v1"
                                :project-id "demo"
                                :config "project.edn"})]
      (cli-start/init! ["repo" "--project" "demo" "--sync" "--query-index"]
                       {:print-json #(reset! printed %)
                        :dispatch (fn [command args]
                                    (swap! calls conj [:dispatch command args])
                                    (println "synced"))
                        :query-index? (fn [args]
                                        (swap! calls conj [:query-index args])
                                        true)}))
    (is (= [[:init "repo"
             {:out nil
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
              :maintenance-command nil}]
            [:query-index ["repo" "--project" "demo" "--sync" "--query-index"]]
            [:dispatch "sync" ["project.edn" "--check" "--no-progress" "--query-index"]]]
           @calls))
    (is (= {:schema "ygg.init/v1"
            :project-id "demo"
            :config "project.edn"
            :sync-output "synced\n"}
           @printed))))

(deftest init-sync-falls-back-to-project-id-when-config-is-absent
  (let [dispatched (atom nil)]
    (with-redefs [init/init! (fn [_root _opts]
                               {:schema "ygg.init/v1"
                                :project-id "registered"})]
      (cli-start/init! ["repo" "--sync"]
                       {:print-json (constantly nil)
                        :dispatch (fn [command args]
                                    (reset! dispatched [command args]))
                        :query-index? (constantly false)}))
    (is (= ["sync" ["--project" "registered" "--check" "--no-progress"]]
           @dispatched))))

(deftest plain-init-only-requires-json-printer
  (let [printed (atom nil)]
    (with-redefs [init/init! (fn [root opts]
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
                                :project-id "demo"})]
      (cli-start/init! ["repo"] {:print-json #(reset! printed %)}))
    (is (= {:schema "ygg.init/v1"
            :project-id "demo"}
           @printed))))

(deftest init-reports-missing-dependency-key
  (try
    (cli-start/init! ["repo"] {})
    (is false "expected missing dependency failure")
    (catch clojure.lang.ExceptionInfo ex
      (is (= {:dependency :print-json} (ex-data ex))))))
