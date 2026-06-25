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

(defn- decision-work
  [decision]
  (work graph-lane
        "maintenance-decision"
        (:project-id decision)
        (severity-priority (:severity decision))
        (decision-classifier/decision-packet decision)))

(defn- infra-review-work
  [packet]
  (work graph-lane
        infra-review/work-kind
        (:project-id packet)
        50
        packet))

(defn- dependency-review-work
  [packet]
  (work graph-lane
        dependency-review/work-kind
        (:project-id packet)
        45
        packet))

(defn- graph-work
  [graph-report]
  (vec
   (concat
    (map decision-work (:decision-queue graph-report))
    (map infra-review-work (:infra-review-queue graph-report))
    (map dependency-review-work (:dependency-review-queue graph-report)))))

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
