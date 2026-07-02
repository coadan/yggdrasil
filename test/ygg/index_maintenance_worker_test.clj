(ns ygg.index-maintenance-worker-test
  (:require [ygg.dependency-review :as dependency-review]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.infra-review :as infra-review]
            [ygg.project :as project]
            [ygg.queue :as queue]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(defn- read-json
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- project-queue-root
  [root]
  (.getPath (io/file root "project.sqlite")))

(defn- project-config
  [root worker]
  (let [repo (io/file root "repo")]
    (.mkdirs repo)
    (with-redefs [store/project-sqlite-path
                  (fn [project-id]
                    (is (= "demo" project-id))
                    (project-queue-root root))]
      (project/normalize-project
       (io/file root)
       (let [maintenance (cond-> {:enabled true
                                  :worker (dissoc worker :queue-dir :report-dir)}
                           (:report-dir worker) (assoc :report-dir (:report-dir worker)))]
         {:id "demo"
          :repos [{:id "app"
                   :root (.getPath repo)
                   :role :application}]
          :maintenance maintenance})
       {:path (.getPath (io/file root "project.edn"))}))))

(defn- decision-packet
  ([] (decision-packet "test"))
  ([suffix]
   (decision-classifier/decision-packet
    {:id (str "maintenance-decision:" suffix)
     :project-id "demo"
     :kind :orphaned-candidate
     :severity :low
     :target "system:demo:app"
     :reason "Needs review."
     :recommended-actions [:none]})))

(defn- enqueue-decision!
  ([queue-root] (enqueue-decision! queue-root "test"))
  ([queue-root suffix]
   (get-in (queue/enqueue! (decision-packet suffix)
                           {:root queue-root
                            :kind "maintenance-decision"
                            :project-id "demo"})
           [:item :id])))

(defn- valid-result-for
  [decision-id]
  {:schema decision-classifier/schema
   :decisionId decision-id
   :recommendation "investigate"
   :confidence 0.7
   :reason "Evidence is insufficient for an automatic correction patch."
   :correctionPatch []})

(defn- valid-result
  []
  (valid-result-for "maintenance-decision:test"))

(defn- valid-batch-result
  [payload]
  (let [items (or (:items payload)
                  (get-in payload [:decisions :items]))]
    {:schema decision-classifier/batch-result-schema
     :batchId (:batchId payload)
     :results (mapv (fn [item]
                      {:schema decision-classifier/schema
                       :decisionId (:decisionId item)
                       :recommendation "investigate"
                       :confidence 0.7
                       :reason "Evidence is insufficient for an automatic correction patch."
                       :correctionPatch []})
                    items)}))

(defn- infra-packet
  []
  (first
   (infra-review/review-packets
    {:project-id "demo"
     :basis {:hash "basis"}
     :systems [{:xt/id "system:demo:app"
                :label "app"
                :kind :system
                :repo-id "app"
                :path-prefix ""
                :metrics {:file-count 2
                          :node-count 4}}]
     :evidence [{:xt/id "infra-evidence:image"
                 :kind :container-image-producer
                 :system-id "system:demo:app"
                 :repo-id "app"
                 :path "Dockerfile"
                 :source-line 1
                 :label "image"
                 :normalized-value "container-image:demo"
                 :confidence 1.0}]})))

(defn- dependency-packet
  []
  (first
   (dependency-review/review-packets
    {:project-id "demo"
     :basis {:hash "basis"}
     :package-report {:packages [{:id "package:npm:left-pad"
                                  :label "npm:left-pad"
                                  :ecosystem "npm"
                                  :package-name "left-pad"}]
                      :unresolved-imports [{:repo-id "app"
                                            :source-id "node:app"
                                            :source-label "app"
                                            :target-id "node:left_pad"
                                            :import "left_pad"
                                            :path "src/app.js"
                                            :line 1
                                            :kind :javascript}]}})))

(defn- valid-review-result
  [payload]
  {:schema (:expectedResultSchema payload)
   :reviewId (:reviewId payload)
   :recommendation "no-change"
   :confidence 0.7
   :reason "Evidence is insufficient for an automatic correction patch."
   :correctionPatch []
   :findings []})

(defn- valid-review-batch-result
  [payload]
  (let [items (or (:items payload)
                  (get-in payload [:reviews :items]))]
    {:schema (:expectedResultSchema payload)
     :batchId (:batchId payload)
     :results (mapv valid-review-result items)}))

(deftest config-status-exposes-worker-operational-controls
  (let [root (temp-dir "ygg-index-maintenance-worker-status")
        project (project-config
                 root
                 {:enabled true
                  :lease-minutes 30
                  :max-items-per-run 8
                  :max-failures-per-run 2
                  :report-dir "reports"
                  :apply {:mode :complete-only}
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex"]
                               :kinds #{:maintenance-decision}}]})
        status (index-maintenance-worker/config-status project)]
    (is (= 30 (get-in status [:worker :leaseMinutes])))
    (is (= 8 (get-in status [:worker :maxItemsPerRun])))
    (is (= 2 (get-in status [:worker :maxFailuresPerRun])))
    (is (= {:mode :complete-only}
           (get-in status [:worker :apply])))
    (is (= (project-queue-root root)
           (get-in status [:worker :queueDb])))
    (is (some? (get-in status [:worker :reportDir])))))

(deftest disabled-worker-does-not-claim-ready-work
  (let [root (temp-dir "ygg-index-maintenance-worker-disabled")
        queue-root (project-queue-root root)
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
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
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
        (is (= {:claimed 1
                :completed 1
                :failed 0
                :executor-failures 0
                :validated 1}
               (:counts result)))
        (is (seq @messages*))
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))
        (is (= "valid" (get-in result [:items 0 :validation :status])))))))

(deftest anthropic-compatible-worker-completes-work
  (let [root (temp-dir "ygg-index-maintenance-worker-anthropic")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
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
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        work-id (enqueue-decision! queue-root)
        argv* (atom nil)
        env* (atom nil)]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv env]}]
                                 (reset! argv* argv)
                                 (reset! env* env)
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
        (is (= "medium" (get @env* "YGG_MAINTENANCE_REASONING")))
        (is (= "completed" (:status result)))
        (is (= index-maintenance-worker/command-work-schema (:schema work-input)))
        (is (= work-id (get-in work-input [:workItem :id])))
        (is (= "maintenance-decision:test"
               (get-in work-input [:payload :decisionId])))
        (is (= [{:id "app"
                 :root (.getCanonicalPath (io/file root "repo"))
                 :role "application"}]
               (get-in work-input [:project :repos])))
        (is (nil? (get-in work-input [:payload :messages])))
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))))))

(deftest command-harness-worker-batches-ready-work-items
  (let [root (temp-dir "ygg-index-maintenance-worker-command-batch")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :max-items-per-run 3
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        work-ids [(enqueue-decision! queue-root "first")
                  (enqueue-decision! queue-root "second")
                  (enqueue-decision! queue-root "third")]
        invocations* (atom [])]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (let [work-input (read-json (nth argv (- (count argv) 3)))
                                       result-path (last argv)]
                                   (swap! invocations* conj {:argv argv
                                                             :work-input work-input})
                                   (spit result-path
                                         (json/write-json-str
                                          {:schema index-maintenance-worker/command-work-result-batch-schema
                                           :results (mapv (fn [item]
                                                            {:workItemId (get-in item [:workItem :id])
                                                             :result (valid-result-for
                                                                      (get-in item [:payload :decisionId]))})
                                                          (:items work-input))})))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            invocation (first @invocations*)
            work-input (:work-input invocation)
            artifact-paths (set (map #(get-in % [:artifacts :work])
                                     (:items result)))]
        (is (= 1 (count @invocations*)))
        (is (= index-maintenance-worker/command-work-batch-schema
               (:schema work-input)))
        (is (= work-ids
               (mapv #(get-in % [:workItem :id]) (:items work-input))))
        (is (= ["codex-maintenance" "--work"
                (nth (:argv invocation) 2)
                "--result"
                (last (:argv invocation))]
               (:argv invocation)))
        (is (= "completed" (:status result)))
        (is (= {:claimed 3
                :completed 3
                :failed 0
                :executor-failures 0
                :validated 3}
               (:counts result)))
        (is (= 1 (count artifact-paths)))
        (doseq [work-id work-ids]
          (is (= "done" (get-in (queue/find-item queue-root work-id)
                                [:item :status]))))
        (is (= ["valid" "valid" "valid"]
               (mapv #(get-in % [:validation :status]) (:items result))))))))

(deftest codex-maintenance-script-supports-empty-bash-arrays
  (let [root (temp-dir "ygg-codex-maintenance-script")
        bin-dir (io/file root "bin")
        work-path (.getPath (io/file root "work.json"))
        result-path (.getPath (io/file root "result.json"))
        codex-path (io/file bin-dir "codex")]
    (.mkdirs bin-dir)
    (spit work-path
          (json/write-json-str {:schema "test"
                                :project {:repos []}}))
    (spit codex-path
          (str "#!/usr/bin/env bash\n"
               "set -euo pipefail\n"
               "prompt=\"$(cat)\"\n"
               "result=\"$(printf '%s\\n' \"$prompt\" | awk '/^Required result JSON path:$/ { getline; print; exit }')\"\n"
               "if [[ -z \"$result\" ]]; then echo 'missing result path' >&2; exit 2; fi\n"
               "cat > \"$result\" <<'JSON'\n"
               "{\"schema\":\"fake.result/v1\"}\n"
               "JSON\n"))
    (.setExecutable codex-path true)
    (let [result (shell/sh "env"
                           (str "PATH=" (.getPath bin-dir) ":" (System/getenv "PATH"))
                           "bash"
                           "scripts/ygg-maintenance-codex.sh"
                           "--work"
                           work-path
                           "--result"
                           result-path)]
      (is (= 0 (:exit result)) (:err result))
      (is (= {:schema "fake.result/v1"} (read-json result-path))))))

(deftest command-harness-work-input-compacts-large-frontier-decisions
  (let [root (temp-dir "ygg-index-maintenance-worker-command-compact")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        payload (decision-classifier/decision-packet
                 {:id "maintenance-decision:large"
                  :project-id "demo"
                  :kind :external-api-review-group
                  :severity :medium
                  :target "external-api-review:large"
                  :reason "Review grouped outbound external APIs."
                  :recommended-actions [:reject-external-api :none]
                  :data {:id "external-api-review:large"
                         :project-id "demo"
                         :relation :calls-external-api
                         :direction :outbound
                         :visibility :secondary
                         :peer {:xt/id "system:demo:app:path/src"
                                :label "src"
                                :kind :candidate-system
                                :repo-id "app"
                                :path-prefix "src"
                                :metrics {:file-count 42
                                          :node-count 100
                                          :extra-large "omitted"}}
                         :targets (mapv (fn [idx]
                                          {:xt/id (str "system:demo:__external:external-api/api" idx)
                                           :label (str "api" idx ".example.test")
                                           :edge-count 1
                                           :evidence-count 1
                                           :relations [:calls-external-api]
                                           :visibilities [:secondary]})
                                        (range 30))
                         :edges (mapv (fn [idx]
                                        {:xt/id (str "edge:" idx)
                                         :source {:large (apply str (repeat 200 "x"))}
                                         :target {:large (apply str (repeat 200 "y"))}
                                         :evidence-ids [(str "evidence:" idx)]})
                                      (range 30))
                         :evidence-ids (mapv #(str "evidence:" %) (range 30))
                         :correctionPatch (mapv (fn [idx]
                                                  {:op "reject-external-api"
                                                   :target (str "api" idx ".example.test")
                                                   :reason "candidate"})
                                                (range 30))}})
        work-id (get-in (queue/enqueue! payload
                                        {:root queue-root
                                         :kind "maintenance-decision"
                                         :project-id "demo"})
                        [:item :id])]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (let [result-path (last argv)]
                                   (spit result-path
                                         (json/write-json-str
                                          (assoc (valid-result)
                                                 :decisionId "maintenance-decision:large"))))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            work-input (read-json (get-in result [:items 0 :artifacts :work]))
            decision (get-in work-input [:payload :decision])]
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))
        (is (= {:edges 30
                :evidence-ids 30}
               (get-in decision [:data :omitted])))
        (is (= 30 (get-in decision [:data :targets :count])))
        (is (= true (get-in decision [:data :targets :truncated])))
        (is (= 12 (count (get-in decision [:data :targets :items]))))
        (is (= 30 (get-in decision [:data :correctionPatch :count])))
        (is (= true (get-in decision [:data :correctionPatch :truncated])))
        (is (= 12 (count (get-in decision [:data :correctionPatch :items]))))
        (is (nil? (get-in decision [:data :edges])))
        (is (nil? (get-in work-input [:payload :messages])))))))

(deftest command-harness-work-input-compacts-review-packets
  (let [root (temp-dir "ygg-index-maintenance-worker-command-review-compact")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :max-items-per-run 2
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:infra-review :dependency-review}}]})
        infra (infra-packet)
        dependency (dependency-packet)
        infra-id (get-in (queue/enqueue! infra
                                         {:root queue-root
                                          :kind infra-review/work-kind
                                          :project-id "demo"})
                         [:item :id])
        dependency-id (get-in (queue/enqueue! dependency
                                              {:root queue-root
                                               :kind dependency-review/work-kind
                                               :project-id "demo"})
                              [:item :id])
        work-inputs* (atom [])]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (let [work-input (read-json (nth argv (- (count argv) 3)))
                                       result-path (last argv)]
                                   (swap! work-inputs* conj work-input)
                                   (spit result-path
                                         (json/write-json-str
                                          {:schema index-maintenance-worker/command-work-result-batch-schema
                                           :results (mapv (fn [item]
                                                            {:workItemId (get-in item [:workItem :id])
                                                             :result (valid-review-result (:payload item))})
                                                          (:items work-input))})))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            work-input (first @work-inputs*)
            by-kind (into {}
                          (map (juxt #(get-in % [:workItem :kind]) identity))
                          (:items work-input))]
        (is (= 1 (count @work-inputs*)))
        (is (= index-maintenance-worker/command-work-batch-schema
               (:schema work-input)))
        (is (= "completed" (:status result)))
        (is (= "done" (get-in (queue/find-item queue-root infra-id)
                              [:item :status])))
        (is (= "done" (get-in (queue/find-item queue-root dependency-id)
                              [:item :status])))
        (doseq [kind [infra-review/work-kind dependency-review/work-kind]]
          (let [batch-item (get by-kind kind)]
            (is (nil? (get-in batch-item [:payload :messages])))
            (is (= 2 (get-in batch-item [:payload :omitted :messages])))
            (is (= true (get-in batch-item [:fullPayload :storedInQueue])))))
        (is (= infra-review/packet-schema
               (get-in by-kind [infra-review/work-kind :payload :schema])))
        (is (= dependency-review/packet-schema
               (get-in by-kind [dependency-review/work-kind :payload :schema])))))))

(deftest command-harness-work-input-compacts-review-batches
  (let [root (temp-dir "ygg-index-maintenance-worker-command-review-batch")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:infra-review}}]})
        packet (infra-review/batch-packet
                [(assoc (infra-packet)
                        :reviewId "infra-review:first"
                        :expectedOutput {:schema infra-review/result-schema
                                         :reviewId "infra-review:first"})
                 (assoc (infra-packet)
                        :reviewId "infra-review:second"
                        :expectedOutput {:schema infra-review/result-schema
                                         :reviewId "infra-review:second"})])
        work-id (get-in (queue/enqueue! packet
                                        {:root queue-root
                                         :kind infra-review/work-kind
                                         :project-id "demo"})
                        [:item :id])]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (let [work-input (read-json (nth argv (- (count argv) 3)))
                                       result-path (last argv)]
                                   (spit result-path
                                         (json/write-json-str
                                          (valid-review-batch-result
                                           (:payload work-input)))))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            work-input (read-json (get-in result [:items 0 :artifacts :work]))]
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))
        (is (= infra-review/batch-packet-schema
               (get-in work-input [:payload :schema])))
        (is (= 2 (get-in work-input [:payload :reviews :count])))
        (is (= 2 (count (get-in work-input [:payload :reviews :items]))))
        (is (nil? (get-in work-input [:payload :items])))
        (is (nil? (get-in work-input [:payload :messages])))
        (is (= 2 (get-in work-input [:payload :omitted :messages])))))))

(deftest command-harness-work-input-compacts-decision-batches
  (let [root (temp-dir "ygg-index-maintenance-worker-command-batch-compact")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        payload (decision-classifier/decision-batch-packet
                 [(-> (decision-packet "first")
                      :decision
                      (assoc :data {:targets (mapv (fn [idx]
                                                     {:xt/id (str "target:" idx)
                                                      :label (str "target-" idx)
                                                      :edge-count 1
                                                      :evidence-count 1})
                                                   (range 20))
                                    :edges (mapv (fn [idx] {:xt/id (str "edge:" idx)})
                                                 (range 20))
                                    :correctionPatch (mapv (fn [idx]
                                                             {:source "source"
                                                              :target (str "target:" idx)
                                                              :relation "shares-config"
                                                              :visibility "noise"})
                                                           (range 20))}))
                  (-> (decision-packet "second")
                      :decision)])
        work-id (get-in (queue/enqueue! payload
                                        {:root queue-root
                                         :kind "maintenance-decision"
                                         :project-id "demo"})
                        [:item :id])]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [{:keys [argv]}]
                                 (let [work-input (read-json (nth argv (- (count argv) 3)))
                                       result-path (last argv)]
                                   (spit result-path
                                         (json/write-json-str
                                          (valid-batch-result (:payload work-input)))))
                                 {:exit 0 :out "" :err ""})}]
      (let [result (index-maintenance-worker/run! project)
            work-input (read-json (get-in result [:items 0 :artifacts :work]))]
        (is (= "done" (get-in (queue/find-item queue-root work-id)
                              [:item :status])))
        (is (= decision-classifier/batch-packet-schema
               (get-in work-input [:payload :schema])))
        (is (= 2 (get-in work-input [:payload :decisions :count])))
        (is (nil? (get-in work-input [:payload :items])))
        (is (nil? (get-in work-input [:payload :messages])))
        (is (= 2 (get-in work-input [:payload :omitted :messages])))
        (is (= 20 (get-in work-input
                          [:payload :decisions :items 0 :decision :data :targets :count])))
        (is (= 20 (get-in work-input
                          [:payload :decisions :items 0 :decision :data
                           :correctionPatch :count])))
        (is (= 20 (get-in work-input
                          [:payload :decisions :items 0 :decision :data :omitted :edges])))))))

(deftest validate-only-fails-invalid-completed-result
  (let [root (temp-dir "ygg-index-maintenance-worker-invalid")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
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
                                                   :correctionPatch []})})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "completed" (:status result)))
        (is (= {:claimed 1
                :completed 0
                :failed 1
                :executor-failures 0
                :validated 1}
               (:counts result)))
        (is (= "failed" (get-in (queue/find-item queue-root work-id)
                                [:item :status])))
        (is (= "invalid" (get-in result [:items 0 :validation :status])))))))

(deftest worker-backs-off-after-repeated-executor-failures
  (let [root (temp-dir "ygg-index-maintenance-worker-backoff")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :max-items-per-run 5
                  :max-failures-per-run 1
                  :executors [{:id "codex"
                               :type :command-harness
                               :command ["codex-maintenance"]
                               :kinds #{:maintenance-decision}}]})
        first-id (enqueue-decision! queue-root "first")
        second-id (enqueue-decision! queue-root "second")]
    (binding [index-maintenance-worker/*deps*
              {:command-runner (fn [_]
                                 {:exit 1
                                  :out ""
                                  :err "executor failed"})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "backoff" (:status result)))
        (is (= {:claimed 1
                :completed 0
                :failed 1
                :executor-failures 1
                :validated 0}
               (:counts result)))
        (is (= {:reason "executor-failures"
                :max-failures-per-run 1}
               (:backoff result)))
        (is (= "failed" (get-in (queue/find-item queue-root first-id)
                                [:item :status])))
        (is (= "ready" (get-in (queue/find-item queue-root second-id)
                               [:item :status])))))))

(deftest worker-respects-per-run-item-cap
  (let [root (temp-dir "ygg-index-maintenance-worker-cap")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
                  :report-dir "reports"
                  :max-items-per-run 1
                  :executors [{:id "deepseek"
                               :type :openai-compatible
                               :provider :deepseek
                               :model "deepseek-v4-flash"
                               :env "YGG_DEEPSEEK_API_KEY"
                               :kinds #{:maintenance-decision}}]})
        first-id (enqueue-decision! queue-root)
        second-id (enqueue-decision! queue-root "second")]
    (binding [index-maintenance-worker/*deps*
              {:get-env (constantly "test-key")
               :openai-client (fn [_opts]
                                {:complete-json (fn [_messages]
                                                  (valid-result))})}]
      (let [result (index-maintenance-worker/run! project)]
        (is (= "limit-reached" (:status result)))
        (is (= {:claimed 1
                :completed 1
                :failed 0
                :executor-failures 0
                :validated 1}
               (:counts result)))
        (is (= "done" (get-in (queue/find-item queue-root first-id)
                              [:item :status])))
        (is (= "ready" (get-in (queue/find-item queue-root second-id)
                               [:item :status])))))))

(deftest missing-api-key-leaves-ready-work-unclaimed
  (let [root (temp-dir "ygg-index-maintenance-worker-no-key")
        queue-root (project-queue-root root)
        project (project-config
                 root
                 {:enabled true
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
