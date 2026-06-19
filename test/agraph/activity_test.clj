(ns agraph.activity-test
  (:require [agraph.activity :as activity]
            [agraph.context :as context]
            [agraph.queue :as queue]
            [agraph.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest sync-queue-imports-durable-activity-and-validation-events
  (let [root (temp-dir "agraph-activity-queue")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "agraph.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :goal "Check knowledge base search work"
                 :expectedResultSchema "agraph.test.result/v1"}
        enqueued (queue/enqueue! payload
                                 {:root root
                                  :project-id "demo"
                                  :kind "test-work"
                                  :now 1000})
        id (get-in enqueued [:item :id])
        xtdb-path (temp-dir "agraph-activity-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "agraph.test.result/v1"
                      :summary "Knowledge base search validation passed"
                      :commands ["bb test"]
                      :scores {:tests 1.0}})
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [result (activity/sync-queue! xtdb project {:queue-root root :now 2000})
              items (activity/all-items xtdb {:project-id "demo"})
              events (activity/all-events xtdb {:project-id "demo"})
              selected (activity/select-activity xtdb
                                                 "knowledge base search validation"
                                                 {:project-id "demo"})]
          (is (= activity/sync-schema (:schema result)))
          (is (= 1 (get-in result [:counts :items])))
          (is (= 3 (get-in result [:counts :events])))
          (is (= 1 (get-in result [:counts :validation-events])))
          (is (= [:done] (mapv :status items)))
          (is (= #{:completed :created :validation}
                 (set (map :event-kind events))))
          (is (= ["system:demo:search"] (:target-ids (first items))))
          (is (= "agraph.test.result/v1" (:expected-result-schema (first items))))
          (is (= "agraph.test.result/v1" (:result-schema (first items))))
          (is (contains? (set (:tokens (first items)))
                         "agraph.test.result/v1"))
          (is (= "agraph.test.result/v1"
                 (:expectedResultSchema (first selected))))
          (is (= "agraph.test.result/v1"
                 (:resultSchema (first selected))))
          (is (= [(get-in enqueued [:item :id])]
                 (mapv :sourceId selected))))))))

(deftest sync-queue-records-result-schema-mismatches
  (let [root (temp-dir "agraph-activity-schema-mismatch")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "agraph.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :expectedResultSchema "agraph.test.expected/v1"}
        enqueued (queue/enqueue! payload
                                 {:root root
                                  :project-id "demo"
                                  :kind "test-work"
                                  :now 1000})
        id (get-in enqueued [:item :id])
        xtdb-path (temp-dir "agraph-activity-schema-mismatch-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "agraph.test.actual/v1"
                      :summary "Wrong schema was returned"})
    (store/with-node xtdb-path
      (fn [xtdb]
        (activity/sync-queue! xtdb project {:queue-root root :now 2000})
        (let [events (activity/all-events xtdb {:project-id "demo"})
              selected (activity/select-activity xtdb
                                                 "result schema mismatch"
                                                 {:project-id "demo"})
              result (activity/sync-queue! xtdb project {:queue-root root :now 3000})
              mismatch (first (filter #(= :result-schema-mismatch (:event-kind %))
                                      events))]
          (is (= 1 (get-in result [:counts :result-schema-mismatch-events])))
          (is (= #{:completed :created :result-schema-mismatch}
                 (set (map :event-kind events))))
          (is (some? mismatch))
          (is (= "result schema mismatch expected agraph.test.expected/v1 actual agraph.test.actual/v1"
                 (:summary mismatch)))
          (is (= [(get-in enqueued [:item :id])]
                 (mapv :sourceId selected)))
          (is (= #{:completed :created :result-schema-mismatch}
                 (set (mapcat #(map :event-kind (:events %)) selected)))))))))

(deftest context-packet-can-answer-from-activity-when-graph-is-empty
  (let [root (temp-dir "agraph-context-activity-queue")
        project {:id "demo" :name "Demo" :repos []}
        payload {:schema "agraph.test.work/v1"
                 :project-id "demo"
                 :target "system:demo:search"
                 :goal "Knowledge base search follow-up"}
        id (get-in (queue/enqueue! payload
                                   {:root root
                                    :project-id "demo"
                                    :kind "test-work"
                                    :now 1000})
                   [:item :id])
        xtdb-path (temp-dir "agraph-context-activity-xtdb")]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (queue/complete! root
                     id
                     {:schema "agraph.test.result/v1"
                      :summary "Knowledge base search validation passed"
                      :commands ["bb test"]})
    (store/with-node xtdb-path
      (fn [xtdb]
        (activity/sync-queue! xtdb project {:queue-root root :now 2000})
        (let [packet (context/context-packet xtdb
                                             "what previous work touched knowledge base search"
                                             {:project-id "demo"
                                              :retriever :lexical
                                              :budget 2000})
              answerability (:answerability packet)]
          (is (= :limited (:status answerability)))
          (is (seq (:activity packet)))
          (is (contains? (set (:available answerability)) :activity))
          (is (contains? (set (:available answerability)) :validation-history))
          (is (contains? (set (:missing answerability)) :source-graph))
          (is (contains? (set (:unsupported answerability)) :remote-work))
          (is (not (contains? (set (:unsupported answerability)) :activity))))))))
