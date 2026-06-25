(ns ygg.index-maintenance-test
  (:require [ygg.dependency-review :as dependency-review]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.infra-review :as infra-review]
            [ygg.system.decision-classifier :as decision-classifier]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- user-message
  [packet]
  (->> (:messages packet)
       (filter #(= "user" (:role %)))
       first
       :content))

(defn- has-instruction?
  [packet text]
  (boolean (some #(str/includes? % text) (:instructions packet))))

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
    (is (= [decision-classifier/batch-packet-schema
            infra-review/packet-schema
            dependency-review/packet-schema]
           (mapv (comp :schema :payload) work)))
    (is (= ["maintenance-decision:test"]
           (mapv :decisionId (get-in work [0 :payload :items]))))
    (is (= [{:schema index-maintenance/source-schema
             :producer index-maintenance/producer
             :lane index-maintenance/graph-lane}]
           (distinct (mapv :source work))))))

(deftest maintenance-decisions-are-batched-before-enqueue
  (let [decisions (mapv (fn [idx]
                          {:id (str "maintenance-decision:" idx)
                           :project-id "demo"
                           :kind :orphaned-candidate
                           :severity (if (zero? idx) :high :low)
                           :target (str "system:demo:app" idx)
                           :reason "Needs review."
                           :recommended-actions [:none]})
                        (range (inc index-maintenance/decision-batch-size)))
        report (index-maintenance/from-graph-report
                {:project-id "demo"
                 :counts {:maintenance-decisions (count decisions)}
                 :decision-queue decisions})
        work (index-maintenance/work-items report)]
    (is (= 2 (count work)))
    (is (= ["maintenance-decision" "maintenance-decision"]
           (mapv :kind work)))
    (is (= [decision-classifier/batch-packet-schema
            decision-classifier/batch-packet-schema]
           (mapv (comp :schema :payload) work)))
    (is (= index-maintenance/decision-batch-size
           (count (get-in work [0 :payload :items]))))
    (is (= 1 (count (get-in work [1 :payload :items]))))
    (is (= [90 30] (mapv :priority work)))))

(deftest maintenance-work-packets-include-clear-bounded-instructions
  (let [decision-packet
        (decision-classifier/decision-packet
         {:id "maintenance-decision:test"
          :project-id "demo"
          :kind :orphaned-candidate
          :severity :low
          :target "system:demo:app"
          :reason "Needs review."
          :recommended-actions [:none]})
        infra-packet
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
                                :node-count 4}}
                     {:xt/id "system:demo:deploy"
                      :label "deploy"
                      :kind :system
                      :repo-id "app"
                      :path-prefix "deploy"
                      :metrics {:file-count 1
                                :node-count 1}}]
           :evidence [{:xt/id "infra-evidence:image"
                       :kind :container-image-producer
                       :system-id "system:demo:app"
                       :repo-id "app"
                       :path "Dockerfile"
                       :source-line 1
                       :label "image"
                       :normalized-value "registry.example/app:latest"
                       :confidence 1.0}]}))
        dependency-packet
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
                                                  :kind :javascript}]}}))]
    (doseq [packet [decision-packet infra-packet dependency-packet]]
      (is (seq (:instructions packet)))
      (is (has-instruction? packet "Most"))
      (is (has-instruction? packet "Return exactly one JSON object"))
      (is (:expectedResultSchema packet))
      (is (str/includes? (user-message packet) "Instructions:"))
      (is (str/includes? (user-message packet) "Return exactly one JSON object")))))
