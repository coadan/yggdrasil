(ns ygg.project-config-test
  (:require [ygg.project :as project]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(deftest project-config-normalizes-embedding-defaults
  (let [root (temp-dir "ygg-project-embeddings")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app"
                            :root "repo"}]
                   :embeddings {:provider "openrouter"
                                :model "openai/text-embedding-3-small"
                                :request-timeout-ms "45000"
                                :max-retries 2}}))
    (is (= {:provider :openrouter
            :model "openai/text-embedding-3-small"
            :request-timeout-ms 45000
            :max-retries 2}
           (:embeddings (project/read-project (.getPath project-edn)))))))

(deftest project-config-normalizes-maintenance-worker
  (let [root (temp-dir "ygg-project-index-maintenance-worker")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app"
                            :root "repo"
                            :role :application
                            :ignore-paths ["reports/**" "tmp/**"]}]
                   :maintenance
                   {:enabled true
                    :work {:max-decisions 12
                           :max-decisions-per-kind 4
                           :max-infra-reviews 20
                           :max-dependency-reviews 16
                           :decision-batch-size 6
                           :review-batch-size 8}
                    :report-dir "reports"
                    :schedules [{:id "sync"
                                 :task :sync
                                 :enabled true
                                 :every-minutes 15
                                 :check false
                                 :enqueue false}
                                {:id "check"
                                 :task :sync
                                 :enabled true
                                 :every-minutes 60}]
                    :worker {:enabled true
                             :executors [{:id "openrouter"
                                          :type :openai-compatible
                                          :provider :openrouter
                                          :model "deepseek/deepseek-v4-flash"
                                          :reasoning :high
                                          :env "YGG_OPENROUTER_API_KEY"
                                          :kinds #{:maintenance-decision}}
                                         {:id "codex"
                                          :type :command-harness
                                          :command ["codex-maintenance"]
                                          :kinds #{:dependency-review}}]}}}))
    (let [maintenance (:maintenance (project/read-project (.getPath project-edn)))
          worker (:worker maintenance)]
      (is (= true (:enabled maintenance)))
      (is (= {:max-decisions 12
              :max-decisions-per-kind 4
              :max-infra-reviews 20
              :max-dependency-reviews 16
              :decision-batch-size 6
              :review-batch-size 8}
             (:work maintenance)))
      (is (= ["reports/**" "tmp/**"]
             (get-in (project/read-project (.getPath project-edn))
                     [:repos 0 :ignore-paths])))
      (is (= true (:enabled worker)))
      (is (= (store/project-sqlite-path "demo") (:queue-dir maintenance)))
      (is (= (.getCanonicalPath (io/file root "reports")) (:report-dir maintenance)))
      (is (= (:queue-dir maintenance) (:queue-dir worker)))
      (is (= [{:id "sync"
               :task :sync
               :enabled true
               :every-minutes 15
               :enqueue false
               :check false
               :query-index false
               :run-on-start false}
              {:id "check"
               :task :sync
               :enabled true
               :every-minutes 60
               :enqueue true
               :check true
               :query-index false
               :run-on-start false}]
             (:schedules maintenance)))
      (is (= :complete-only (get-in worker [:apply :mode])))
      (is (= 1 (:max-items-per-run worker)))
      (is (= 3 (:max-failures-per-run worker)))
      (is (= #{"maintenance-decision"}
             (get-in worker [:executors 0 :kinds])))
      (is (= :openai-compatible (get-in worker [:executors 0 :type])))
      (is (= :openrouter (get-in worker [:executors 0 :provider])))
      (is (= "high" (get-in worker [:executors 0 :reasoning])))
      (is (= "medium" (get-in worker [:executors 1 :reasoning])))
      (is (= ["codex-maintenance"] (get-in worker [:executors 1 :command]))))))

(deftest project-config-rejects-configured-maintenance-queue-dir
  (let [root (temp-dir "ygg-project-index-maintenance-queue-dir")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :queue-dir "queue"}}))
    (try
      (project/read-project (.getPath project-edn))
      (is false "Expected configured maintenance queue directory to be rejected.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Maintenance queue storage is central and cannot be configured."
               (ex-message e)))
        (is (= {:project-id "demo"
                :key :queue-dir}
               (ex-data e)))))))

(deftest project-config-defaults-maintenance-to-central-project-state
  (let [root (temp-dir "ygg-project-index-maintenance-worker-defaults")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :worker {:enabled true
                             :executors [{:id "codex"
                                          :type :command-harness
                                          :command ["codex-maintenance"]
                                          :kinds #{:maintenance-decision}}]}}}))
    (let [maintenance (:maintenance (project/read-project (.getPath project-edn)))
          worker (:worker maintenance)]
      (is (= (store/project-sqlite-path "demo")
             (:queue-dir maintenance)))
      (is (= (store/project-data-path "demo" "reports" "maintenance")
             (:report-dir maintenance)))
      (is (= {:max-decisions 8
              :max-decisions-per-kind 4
              :max-infra-reviews 8
              :max-dependency-reviews 8
              :decision-batch-size 8
              :review-batch-size 8}
             (:work maintenance)))
      (is (= (:queue-dir maintenance) (:queue-dir worker))))))

(deftest project-config-rejects-legacy-sync-check-schedule-task
  (let [root (temp-dir "ygg-project-maintenance-schedule-task")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :schedules [{:id "check"
                                 :task :sync-check
                                 :enabled true}]}}))
    (try
      (project/read-project (.getPath project-edn))
      (is false "Expected legacy sync-check schedule task to be rejected.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Maintenance schedule task is not supported."
               (ex-message e)))
        (is (= {:schedule-id "check"
                :task :sync-check
                :supported [:sync]}
               (ex-data e)))))))

(deftest index-maintenance-worker-rejects-non-deepseek-api-provider
  (let [root (temp-dir "ygg-project-maintenance-provider")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :worker {:enabled true
                             :executors [{:id "openai"
                                          :type :openai-compatible
                                          :provider :openai
                                          :model "gpt-4.1-mini"
                                          :kinds #{:maintenance-decision}}]}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"only supports DeepSeek or OpenRouter"
                          (project/read-project (.getPath project-edn))))))

(deftest index-maintenance-worker-rejects-pre-v4-deepseek-model
  (let [root (temp-dir "ygg-project-maintenance-model")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :worker {:enabled true
                             :executors [{:id "deepseek"
                                          :type :anthropic-compatible
                                          :provider :deepseek
                                          :model "deepseek-chat"
                                          :kinds #{:maintenance-decision}}]}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"DeepSeek V4 or newer"
                          (project/read-project (.getPath project-edn))))))

(deftest index-maintenance-worker-rejects-unsupported-reasoning-level
  (let [root (temp-dir "ygg-project-maintenance-reasoning")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :maintenance
                   {:enabled true
                    :worker {:enabled true
                             :executors [{:id "codex"
                                          :type :command-harness
                                          :command ["codex-maintenance"]
                                          :reasoning :extreme
                                          :kinds #{:maintenance-decision}}]}}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"reasoning level is not supported"
                          (project/read-project (.getPath project-edn))))))
