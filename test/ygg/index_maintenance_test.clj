(ns ygg.index-maintenance-test
  (:require [ygg.dependency-review :as dependency-review]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.infra-review :as infra-review]
            [ygg.system.decision-classifier :as decision-classifier]
            [clojure.test :refer [deftest is]]))

(deftest graph-report-becomes-index-maintenance-lane
  (let [decision {:id "maintenance-decision:test"
                  :project-id "demo"
                  :kind :unclustered-system
                  :severity :medium
                  :target "system:demo:api"
                  :reason "Needs review."
                  :recommended-actions [:accept-system]}
        infra-packet {:schema infra-review/packet-schema
                      :reviewId "infra-review:test"
                      :project-id "demo"}
        dependency-packet {:schema dependency-review/packet-schema
                           :reviewId "dependency-review:test"
                           :project-id "demo"}
        graph-report {:project-id "demo"
                      :counts {:maintenance-decisions 1
                               :infra-review-items 1
                               :dependency-review-items 1}
                      :decision-queue [decision]
                      :infra-review-queue [infra-packet]
                      :dependency-review-queue [dependency-packet]}
        report (index-maintenance/from-graph-report graph-report)
        work (index-maintenance/work-items report)]
    (is (= index-maintenance/schema (:schema report)))
    (is (= graph-report (index-maintenance/graph-report report)))
    (is (= "graph" (get-in report [:lanes :graph :lane])))
    (is (= ["maintenance-decision" infra-review/work-kind dependency-review/work-kind]
           (mapv :kind work)))
    (is (= ["graph" "graph" "graph"] (mapv :lane work)))
    (is (= [60 50 45] (mapv :priority work)))
    (is (= [decision-classifier/packet-schema
            infra-review/packet-schema
            dependency-review/packet-schema]
           (mapv (comp :schema :payload) work)))
    (is (= [{:schema index-maintenance/source-schema
             :producer index-maintenance/producer
             :lane index-maintenance/graph-lane}]
           (distinct (mapv :source work))))))
