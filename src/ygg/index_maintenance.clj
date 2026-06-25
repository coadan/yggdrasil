(ns ygg.index-maintenance
  "Deterministic project index maintenance report and work derivation."
  (:require [ygg.dependency-review :as dependency-review]
            [ygg.infra-review :as infra-review]
            [ygg.system.decision-classifier :as decision-classifier]))

(def schema
  "ygg.index-maintenance.report/v1")

(def lane-schema
  "ygg.index-maintenance.lane/v1")

(def work-schema
  "ygg.index-maintenance.work/v1")

(def source-schema
  "ygg.index-maintenance.source/v1")

(def graph-lane
  "graph")

(def producer
  "index-maintenance")

(def decision-batch-size
  8)

(def review-batch-size
  4)

(defn- severity-priority
  [severity]
  ({:high 90 :medium 60 :low 30
    "high" 90 "medium" 60 "low" 30}
   severity
   50))

(defn- source
  [lane]
  {:schema source-schema
   :producer producer
   :lane lane})

(defn- work
  [lane kind project-id priority payload]
  {:schema work-schema
   :lane lane
   :kind kind
   :project-id project-id
   :priority priority
   :source (source lane)
   :payload payload})

(defn- decision-batch-work
  [decisions]
  (work graph-lane
        "maintenance-decision"
        (:project-id (first decisions))
        (apply max (map (comp severity-priority :severity) decisions))
        (decision-classifier/decision-batch-packet decisions)))

(defn- batched-payload
  [packets batch-fn]
  (let [packets (vec packets)]
    (if (= 1 (count packets))
      (first packets)
      (batch-fn packets))))

(defn- infra-review-work
  [packets]
  (work graph-lane
        infra-review/work-kind
        (:project-id (first packets))
        50
        (batched-payload packets infra-review/batch-packet)))

(defn- dependency-review-work
  [packets]
  (work graph-lane
        dependency-review/work-kind
        (:project-id (first packets))
        45
        (batched-payload packets dependency-review/batch-packet)))

(defn- graph-work
  [graph-report]
  (vec
   (concat
    (map decision-batch-work
         (partition-all decision-batch-size (:decision-queue graph-report)))
    (map infra-review-work
         (partition-all review-batch-size (:infra-review-queue graph-report)))
    (map dependency-review-work
         (partition-all review-batch-size (:dependency-review-queue graph-report))))))

(defn from-graph-report
  "Wrap the existing graph maintenance report as the first index-maintenance lane."
  [graph-report]
  (let [lane {:schema lane-schema
              :lane graph-lane
              :producer "ygg.system"
              :project-id (:project-id graph-report)
              :counts (:counts graph-report)
              :report graph-report}
        work (graph-work graph-report)]
    {:schema schema
     :project-id (:project-id graph-report)
     :counts (:counts graph-report)
     :lanes {:graph lane}
     :work work}))

(defn graph-report
  "Return the graph lane report from an index-maintenance report."
  [report]
  (get-in report [:lanes :graph :report]))

(defn work-items
  "Return queue-ready work envelopes from an index-maintenance report."
  [report]
  (vec (:work report)))
