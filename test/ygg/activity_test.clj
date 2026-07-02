(ns ygg.activity-test
  (:require [ygg.activity :as activity]
            [ygg.context :as context]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- temp-queue-db
  [prefix]
  (str (java.io.File. (temp-dir prefix) "project.sqlite")))

(deftest result-schema-counts-summarize-mechanical-item-statuses
  (is (= {:result-schema-statuses {:matching 1
                                   :mismatch 1
                                   :missing-result 1
                                   :unexpected-result 1}
          :result-schema-status-items 4
          :result-schema-matching-items 1
          :result-schema-mismatch-items 1
          :result-schema-missing-result-items 1
          :result-schema-unexpected-result-items 1}
         (activity/result-schema-counts
          [{:expected-result-schema "ok/v1"
            :result-schema "ok/v1"}
           {:expected-result-schema "expected/v1"
            :result-schema "actual/v1"}
           {:expected-result-schema "missing/v1"}
           {:result-schema "unexpected/v1"}
           {}]))))

(deftest scoped-activity-reads-use-constrained-rows
  (let [calls (atom [])
        item {:xt/id "activity-item:demo"
              :schema activity/item-schema
              :project-id "demo"
              :source :queue
              :source-id "queue:demo"
              :kind "test-work"
              :status :done
              :target-ids []
              :summary "done"
              :tokens ["done"]
              :created-at-ms 1
              :updated-at-ms 2
              :active? true
              :run-id "run"}
        event {:xt/id "activity-event:demo"
               :schema activity/event-schema
               :project-id "demo"
               :source :queue
               :source-id "queue:demo"
               :item-id "activity-item:demo"
               :event-kind :completed
               :target-ids []
               :summary "completed"
               :at-ms 2
               :active? true
               :run-id "run"}]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints _]
                    (swap! calls conj [table constraints])
                    (case table
                      :ygg/activity-items [item]
                      :ygg/activity-events [event]))]
      (is (= ["activity-item:demo"]
             (mapv :xt/id (activity/all-items :xtdb {:project-id "demo"}))))
      (is (= ["activity-event:demo"]
             (mapv :xt/id (activity/all-events :xtdb {:project-id "demo"})))))
    (is (= [[(:activity-items store/tables) {:project-id "demo" :active? true}]
            [(:activity-events store/tables) {:project-id "demo" :active? true}]]
           @calls))))

(deftest sync-queue-imports-durable-activity-and-validation-events
  (let [root (temp-queue-db "ygg-activity-queue")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "ygg.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :goal "Check knowledge base search work"
                 :expectedResultSchema "ygg.test.result/v1"}
        enqueued (queue/enqueue! payload
                                 {:root root
                                  :project-id "demo"
                                  :kind "test-work"
                                  :now 1000})
        id (get-in enqueued [:item :id])
        xtdb-path (temp-dir "ygg-activity-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "ygg.test.result/v1"
                      :summary "Knowledge base search validation passed"
                      :commands ["bb test"]
                      :scores {:tests 1.0}})
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [result (activity/sync-queue! xtdb project {:queue-db root :now 2000})
              items (activity/all-items xtdb {:project-id "demo"})
              events (activity/all-events xtdb {:project-id "demo"})
              selected (activity/select-activity xtdb
                                                 "knowledge base search validation"
                                                 {:project-id "demo"})]
          (is (= activity/sync-schema (:schema result)))
          (is (= 1 (get-in result [:counts :items])))
          (is (= 3 (get-in result [:counts :events])))
          (is (= 1 (get-in result [:counts :validation-events])))
          (is (= {:matching 1}
                 (get-in result [:counts :result-schema-statuses])))
          (is (= [:done] (mapv :status items)))
          (is (= #{:completed :created :validation}
                 (set (map :event-kind events))))
          (is (= ["system:demo:search"] (:target-ids (first items))))
          (is (= "ygg.test.result/v1" (:expected-result-schema (first items))))
          (is (= "ygg.test.result/v1" (:result-schema (first items))))
          (is (contains? (set (:tokens (first items)))
                         "ygg.test.result/v1"))
          (is (= "ygg.test.result/v1"
                 (:expectedResultSchema (first selected))))
          (is (= "ygg.test.result/v1"
                 (:resultSchema (first selected))))
          (is (= "matching"
                 (:resultSchemaStatus (first selected))))
          (is (= [(get-in enqueued [:item :id])]
                 (mapv :sourceId selected))))))))

(deftest sync-queue-records-result-schema-mismatches
  (let [root (temp-queue-db "ygg-activity-schema-mismatch")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "ygg.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :expectedResultSchema "ygg.test.expected/v1"}
        enqueued (queue/enqueue! payload
                                 {:root root
                                  :project-id "demo"
                                  :kind "test-work"
                                  :now 1000})
        id (get-in enqueued [:item :id])
        xtdb-path (temp-dir "ygg-activity-schema-mismatch-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "ygg.test.actual/v1"
                      :summary "Wrong schema was returned"})
    (store/with-node xtdb-path
      (fn [xtdb]
        (activity/sync-queue! xtdb project {:queue-db root :now 2000})
        (let [events (activity/all-events xtdb {:project-id "demo"})
              selected (activity/select-activity xtdb
                                                 "result schema mismatch"
                                                 {:project-id "demo"})
              result (activity/sync-queue! xtdb project {:queue-db root :now 3000})
              mismatch (first (filter #(= :result-schema-mismatch (:event-kind %))
                                      events))
              sample (first (:result-schema-mismatches result))]
          (is (= 1 (get-in result [:counts :result-schema-mismatch-events])))
          (is (= {:mismatch 1}
                 (get-in result [:counts :result-schema-statuses])))
          (is (= (get-in enqueued [:item :id]) (:sourceId sample)))
          (is (= "test-work" (:kind sample)))
          (is (= "done" (:status sample)))
          (is (= "ygg.test.expected/v1" (:expectedResultSchema sample)))
          (is (= "ygg.test.actual/v1" (:resultSchema sample)))
          (is (= ["mismatch"]
                 (mapv :resultSchemaStatus selected)))
          (is (str/includes? (:summary sample) "result schema mismatch"))
          (is (integer? (:updatedAtMs sample)))
          (is (integer? (:completedAtMs sample)))
          (is (= #{:completed :created :result-schema-mismatch}
                 (set (map :event-kind events))))
          (is (some? mismatch))
          (is (= "result schema mismatch expected ygg.test.expected/v1 actual ygg.test.actual/v1"
                 (:summary mismatch)))
          (is (= [(get-in enqueued [:item :id])]
                 (mapv :sourceId selected)))
          (is (= #{:completed :created :result-schema-mismatch}
                 (set (mapcat #(map :event-kind (:events %)) selected)))))))))

(deftest context-packet-can-answer-from-activity-when-graph-is-empty
  (let [root (temp-queue-db "ygg-context-activity-queue")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "ygg.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :goal "Knowledge base search follow-up"}
        id (get-in (queue/enqueue! payload
                                   {:root root
                                    :project-id "demo"
                                    :kind "test-work"
                                    :now 1000})
                   [:item :id])
        xtdb-path (temp-dir "ygg-context-activity-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "ygg.test.result/v1"
                      :summary "Knowledge base search validation passed"
                      :commands ["bb test"]})
    (store/with-node xtdb-path
      (fn [xtdb]
        (activity/sync-queue! xtdb project {:queue-db root :now 2000})
        (let [packet (context/context-packet xtdb
                                             "what previous work touched knowledge base search"
                                             {:project-id "demo"
                                              :retriever :lexical
                                              :budget 2000})
              evidence (:evidence packet)]
          (is (= :limited (:status evidence)))
          (is (seq (:activity packet)))
          (is (contains? (set (:available evidence)) :activity))
          (is (contains? (set (:available evidence)) :validation-history))
          (is (contains? (set (:missing evidence)) :source-graph))
          (is (contains? (set (:unsupported evidence)) :remote-work))
          (is (not (contains? (set (:unsupported evidence)) :activity))))))))
