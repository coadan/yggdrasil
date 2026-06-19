(ns agraph.queue-test
  (:require [agraph.queue :as queue]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest queue-items-claim-and-complete-through-status-dirs
  (let [root (temp-dir "agraph-queue")
        payload {:schema "agraph.context/v1"
                 :query "projection boundary"
                 :basis {:project-id "fixture"}}
        enqueued (queue/enqueue! payload {:root root
                                          :project-id "fixture"
                                          :priority 80})
        id (get-in enqueued [:item :id])]
    (is (= "ready" (get-in enqueued [:item :status])))
    (is (= "context" (get-in enqueued [:item :kind])))
    (is (.isFile (io/file (:path enqueued))))
    (is (= [id] (mapv (comp :id :item)
                      (queue/list-items root {:status "ready"}))))
    (testing "claim moves the item into claimed and records the lease"
      (let [claimed (queue/claim-next! root {:agent-id "codex"
                                             :lease-ms 60000
                                             :project-id "fixture"})]
        (is (= id (get-in claimed [:item :id])))
        (is (= "claimed" (get-in claimed [:item :status])))
        (is (= "codex" (get-in claimed [:item :lease :agent-id])))
        (is (nil? (seq (queue/list-items root {:status "ready"}))))
        (is (= id (get-in (queue/find-item root (subs id 6)) [:item :id])))))
    (testing "complete stores the result and moves the item into done"
      (let [done (queue/complete! root id {:schema "agraph.test.result/v1"
                                           :ok true})]
        (is (= "done" (get-in done [:item :status])))
        (is (= true (get-in done [:item :result :ok])))
        (is (= [id] (mapv (comp :id :item)
                          (queue/list-items root {:status "done"}))))))))

(deftest claimed-items-can-be-released-or-expired
  (let [root (temp-dir "agraph-queue-release")
        payload {:schema "agraph.cursor.packet/v1"
                 :basis {:project-id "fixture"}}
        id (get-in (queue/enqueue! payload {:root root}) [:item :id])]
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (let [released (queue/release! root id "needs larger scope")]
      (is (= "ready" (get-in released [:item :status])))
      (is (= "needs larger scope" (get-in released [:item :release-reason]))))
    (queue/claim-next! root {:agent-id "codex" :lease-ms 60000})
    (is (= 1 (queue/release-expired! root Long/MAX_VALUE)))
    (is (= "ready" (get-in (queue/find-item root id) [:item :status])))))

(deftest complete-requires-claimed-item
  (let [root (temp-dir "agraph-queue-complete")
        id (get-in (queue/enqueue! {:schema "custom.packet/v1"} {:root root})
                   [:item :id])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Only claimed queue items can be completed"
                          (queue/complete! root id {:ok true})))))

(deftest queue-list-summary-is-compact
  (let [root (temp-dir "agraph-queue-summary")
        id (get-in (queue/enqueue! {:schema "custom.packet/v1"
                                    :project-id "demo"}
                                   {:root root
                                    :kind "custom"
                                    :priority 10})
                   [:item :id])
        summary (queue/list-summary root {:project-id "demo"})]
    (is (= queue/list-schema (:schema summary)))
    (is (= [id] (mapv :id (:items summary))))
    (is (= [queue/summary-schema] (mapv :schema (:items summary))))
    (is (not (contains? (first (:items summary)) :payload)))))

(deftest queue-summary-includes-payload-target-context
  (let [root (temp-dir "agraph-queue-payload-summary")
        infra-id (get-in (queue/enqueue! {:schema "agraph.infra.review-packet/v1"
                                          :reviewId "infra-review:test"
                                          :project-id "demo"
                                          :kind "container-image-consumer-without-producer"
                                          :artifact "container-image:api"}
                                         {:root root
                                          :kind "infra-review"
                                          :project-id "demo"})
                         [:item :id])
        decision-id (get-in (queue/enqueue! {:schema "agraph.maintenance.decision-packet/v1"
                                             :decisionId "maintenance-decision:test"
                                             :project-id "demo"
                                             :decision {:kind "unclustered-system"
                                                        :severity "low"
                                                        :target "system:demo:api"
                                                        :reason "Needs review."
                                                        :basis {:hash "basis123"}}
                                             :allowedActions ["accept-system" "none"]}
                                            {:root root
                                             :kind "maintenance-decision"
                                             :project-id "demo"})
                            [:item :id])
        by-id (->> (:items (queue/list-summary root {:project-id "demo"}))
                   (map (juxt :id identity))
                   (into {}))]
    (is (= {:id "infra-review:test"
            :kind "container-image-consumer-without-producer"
            :artifact "container-image:api"}
           (:payload-summary (get by-id infra-id))))
    (is (= {:id "maintenance-decision:test"
            :kind "unclustered-system"
            :severity "low"
            :target "system:demo:api"
            :reason "Needs review."
            :basisHash "basis123"
            :allowedActions ["accept-system" "none"]}
           (:payload-summary (get by-id decision-id))))))

(deftest queue-summary-includes-state-specific-actions
  (let [root (str (temp-dir "agraph-queue actions") "/queue root")
        ready-id (get-in (queue/enqueue! {:schema "custom.packet/v1"
                                          :project-id "demo"}
                                         {:root root
                                          :kind "custom"
                                          :project-id "demo"})
                         [:item :id])
        ready-summary (queue/item-summary (queue/find-item root ready-id))
        claimed (queue/claim-next! root {:agent-id "codex"
                                         :project-id "demo"
                                         :kind "custom"})
        claimed-summary (queue/item-summary claimed)
        done-summary (queue/item-summary
                      (queue/complete! root
                                       ready-id
                                       {:schema "custom.result/v1"
                                        :ok true}))]
    (is (= [{:kind :claim
             :label "Claim next matching work item"
             :command (str "agraph sync work pull --project demo --kind custom "
                           "--agent <agent-id> --queue-dir "
                           "'" root "'")}]
           (:actions ready-summary)))
    (is (= #{:complete :release :reject}
           (set (map :kind (:actions claimed-summary)))))
    (is (some #(= (str "agraph sync work complete "
                       ready-id
                       " --result result.json --queue-dir "
                       "'" root "'")
                  (:command %))
              (:actions claimed-summary)))
    (is (= [{:kind :apply
             :label "Apply completed work result to map"
             :command (str "agraph sync work apply "
                           ready-id
                           " --map agraph.map.json --queue-dir "
                           "'" root "'")}]
           (:actions done-summary)))))

(deftest queue-list-and-claim-can-filter-by-kind
  (let [root (temp-dir "agraph-queue-kind-filter")
        infra-id (get-in (queue/enqueue! {:schema "agraph.infra.review-packet/v1"
                                          :reviewId "infra-review:test"
                                          :project-id "demo"}
                                         {:root root
                                          :kind "infra-review"
                                          :project-id "demo"
                                          :priority 90})
                         [:item :id])
        decision-id (get-in (queue/enqueue! {:schema "agraph.maintenance.decision-packet/v1"
                                             :decisionId "maintenance-decision:test"
                                             :project-id "demo"}
                                            {:root root
                                             :kind "maintenance-decision"
                                             :project-id "demo"
                                             :priority 10})
                            [:item :id])]
    (is (= [decision-id]
           (mapv (comp :id :item)
                 (queue/list-items root {:status "ready"
                                         :project-id "demo"
                                         :kind "maintenance-decision"}))))
    (let [claimed (queue/claim-next! root {:agent-id "codex"
                                           :project-id "demo"
                                           :kind "maintenance-decision"})]
      (is (= decision-id (get-in claimed [:item :id])))
      (is (= [infra-id]
             (mapv (comp :id :item)
                   (queue/list-items root {:status "ready"
                                           :project-id "demo"})))))))
