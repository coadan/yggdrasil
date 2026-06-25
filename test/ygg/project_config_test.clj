(ns ygg.project-config-test
  (:require [ygg.project :as project]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(deftest project-config-normalizes-index-maintenance-worker
  (let [root (temp-dir "ygg-project-index-maintenance-worker")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo" :role :application}]
                   :index-maintenance-worker
                   {:enabled true
                    :queue-dir "queue"
                    :report-dir "reports"
                    :executors [{:id "openrouter"
                                 :type :openai-compatible
                                 :provider :openrouter
                                 :model "deepseek/deepseek-v4-flash"
                                 :env "YGG_OPENROUTER_API_KEY"
                                 :kinds #{:maintenance-decision}}
                                {:id "codex"
                                 :type :command-harness
                                 :command ["codex-maintenance"]
                                 :kinds #{:dependency-review}}]}}))
    (let [worker (:index-maintenance-worker (project/read-project (.getPath project-edn)))]
      (is (= true (:enabled worker)))
      (is (= (.getCanonicalPath (io/file root "queue")) (:queue-dir worker)))
      (is (= (.getCanonicalPath (io/file root "reports")) (:report-dir worker)))
      (is (= :complete-only (get-in worker [:apply :mode])))
      (is (= #{"maintenance-decision"}
             (get-in worker [:executors 0 :kinds])))
      (is (= :openai-compatible (get-in worker [:executors 0 :type])))
      (is (= :openrouter (get-in worker [:executors 0 :provider])))
      (is (= ["codex-maintenance"] (get-in worker [:executors 1 :command]))))))

(deftest index-maintenance-worker-rejects-non-deepseek-api-provider
  (let [root (temp-dir "ygg-project-maintenance-provider")
        repo (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo)
    (spit project-edn
          (pr-str {:id "demo"
                   :repos [{:id "app" :root "repo"}]
                   :index-maintenance-worker
                   {:enabled true
                    :executors [{:id "openai"
                                 :type :openai-compatible
                                 :provider :openai
                                 :model "gpt-4.1-mini"
                                 :kinds #{:maintenance-decision}}]}}))
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
                   :index-maintenance-worker
                   {:enabled true
                    :executors [{:id "deepseek"
                                 :type :anthropic-compatible
                                 :provider :deepseek
                                 :model "deepseek-chat"
                                 :kinds #{:maintenance-decision}}]}}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"DeepSeek V4 or newer"
                          (project/read-project (.getPath project-edn))))))
