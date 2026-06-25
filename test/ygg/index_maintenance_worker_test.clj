(ns ygg.index-maintenance-worker-test
  (:require [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.project :as project]
            [ygg.queue :as queue]
            [ygg.system.decision-classifier :as decision-classifier]
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

(defn- project-config
  [root worker]
  (let [repo (io/file root "repo")]
    (.mkdirs repo)
    (project/normalize-project
     (io/file root)
     {:id "demo"
      :repos [{:id "app"
               :root (.getPath repo)
               :role :application}]
      :index-maintenance-worker worker}
     {:path (.getPath (io/file root "project.edn"))})))

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

(defn- enqueue-decision!
  [queue-root]
  (get-in (queue/enqueue! (decision-packet)
                          {:root queue-root
                           :kind "maintenance-decision"
                           :project-id "demo"})
          [:item :id]))

(defn- valid-result
  []
  {:schema decision-classifier/schema
   :decisionId "maintenance-decision:test"
   :recommendation "investigate"
   :confidence 0.7
   :reason "Evidence is insufficient for an automatic map patch."
   :mapPatch []})

(deftest disabled-worker-does-not-claim-ready-work
  (let [root (temp-dir "ygg-index-maintenance-worker-disabled")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled false
                  :executors [{:id "deepseek"
                               :type :openai-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)
        result (index-maintenance-worker/run! project)]
    (is (= "disabled" (:status result)))
    (is (= "ready" (get-in (queue/find-item queue-root work-id)
                           [:item :status])))))

(deftest openai-compatible-worker-completes-and-validates-work
  (let [root (temp-dir "ygg-index-maintenance-worker-openai")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled true
                  :queue-dir "queue"
                  :report-dir "reports"
                  :apply {:mode :complete-only}
                  :executors [{:id "deepseek"
                               :type :openai-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :env "YGG_DEEPSEEK_API_KEY"
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)
        messages* (atom nil)]
    (binding [index-maintenance-worker/*deps*
              {:get-env (fn [k] (when (= "YGG_DEEPSEEK_API_KEY" k) "test-key"))
               :openai-client (fn [_opts]
                                {:complete-json (fn [messages]
                                                  (reset! messages* messages)
                                                  (valid-result))})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "completed" (:status result)))
        (is (= {:claimed 1 :completed 1 :failed 0 :validated 1}
               (:counts result)))
        (is (seq @messages*))
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))
        (is (= "valid" (get-in result [:items 0 :validation :status])))))))

(deftest anthropic-compatible-worker-completes-work
  (let [root (temp-dir "ygg-index-maintenance-worker-anthropic")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled true
                  :queue-dir "queue"
                  :report-dir "reports"
                  :executors [{:id "deepseek-anthropic"
                               :type :anthropic-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :env "YGG_DEEPSEEK_API_KEY"
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)]
    (binding [index-maintenance-worker/*deps*
              {:get-env (fn [_] "test-key")
               :anthropic-client (fn [_opts]
                                   {:complete-json (fn [_messages]
                                                     (valid-result))})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "completed" (:status result)))
        (is (= "deepseek-anthropic" (get-in result [:items 0 :executor])))
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))))))

(deftest command-harness-worker-uses-work-and-result-files
  (let [root (temp-dir "ygg-index-maintenance-worker-command")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled true
                  :queue-dir "queue"
                  :report-dir "reports"
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)
        argv* (atom nil)]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (reset! argv* argv)
                                 (let [result-path (last argv)]
                                   (spit result-path
                                         (json/write-json-str (valid-result))))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            work-path (get-in result [:items 0 :artifacts :work])
            work-input (read-json work-path)]
        (is (= ["codex-maintenance" "--work" work-path "--result"
                (get-in result [:items 0 :artifacts :result])]
               @argv*))
        (is (= "completed" (:status result)))
        (is (= work-id (:id work-input)))
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))))))

(deftest validate-only-fails-invalid-completed-result
  (let [root (temp-dir "ygg-index-maintenance-worker-invalid")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled true
                  :queue-dir "queue"
                  :report-dir "reports"
                  :apply {:mode :validate-only}
                  :executors [{:id "deepseek"
                               :type :openai-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :env "YGG_DEEPSEEK_API_KEY"
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)]
    (binding [index-maintenance-worker/*deps*
              {:get-env (constantly "test-key")
               :openai-client (fn [_opts]
                                {:complete-json (fn [_messages]
                                                  {:schema decision-classifier/schema
                                                   :decisionId "wrong"
                                                   :recommendation "investigate"
                                                   :reason "Wrong id."
                                                   :mapPatch []})})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "completed" (:status result)))
        (is (= {:claimed 1 :completed 0 :failed 1 :validated 1}
               (:counts result)))
        (is (= "failed" (get-in (queue/find-item queue-root work-id)
                                [:item :status])))
        (is (= "invalid" (get-in result [:items 0 :validation :status])))))))

(deftest missing-api-key-leaves-ready-work-unclaimed
  (let [root (temp-dir "ygg-index-maintenance-worker-no-key")
        queue-root (.getPath (io/file root "queue"))
        project (project-config
                 root
                 {:enabled true
                  :queue-dir "queue"
                  :report-dir "reports"
                  :executors [{:id "deepseek"
                               :type :openai-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :env "YGG_DEEPSEEK_API_KEY"
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)]
    (binding [index-maintenance-worker/*deps* {:get-env (constantly nil)}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "no-executor" (:status result)))
        (is (= "ready" (get-in (queue/find-item queue-root work-id)
                               [:item :status])))))))
