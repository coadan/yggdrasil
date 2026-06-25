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

(def default-work-controls
  {:max-decisions 8
   :max-decisions-per-kind 4
   :max-infra-reviews 8
   :max-dependency-reviews 8
   :decision-batch-size 8
   :review-batch-size 8})

(def decision-batch-size
  (:decision-batch-size default-work-controls))

(def review-batch-size
  (:review-batch-size default-work-controls))

(defn- long-value
  [value default]
  (cond
    (integer? value) (long value)
    (number? value) (long value)
    (some? value) (Long/parseLong (str value))
    :else default))

(defn- non-negative-long
  [value default]
  (max 0 (long-value value default)))

(defn- positive-long
  [value default]
  (max 1 (long-value value default)))

(defn normalize-work-controls
  "Return canonical index-maintenance work emission controls."
  [controls]
  (let [controls (or controls {})]
    {:max-decisions (non-negative-long (:max-decisions controls)
                                       (:max-decisions default-work-controls))
     :max-decisions-per-kind (non-negative-long (:max-decisions-per-kind controls)
                                                (:max-decisions-per-kind default-work-controls))
     :max-infra-reviews (non-negative-long (:max-infra-reviews controls)
                                           (:max-infra-reviews default-work-controls))
     :max-dependency-reviews (non-negative-long (:max-dependency-reviews controls)
                                                (:max-dependency-reviews default-work-controls))
     :decision-batch-size (positive-long (:decision-batch-size controls)
                                         (:decision-batch-size default-work-controls))
     :review-batch-size (positive-long (:review-batch-size controls)
                                       (:review-batch-size default-work-controls))}))

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

(defn- capped
  [items limit]
  (vec (take limit (or items []))))

(defn- omitted
  [items selected]
  (max 0 (- (count (or items [])) (count selected))))

(defn- graph-work
  [graph-report controls]
  (let [controls (normalize-work-controls controls)
        decisions (capped (:decision-queue graph-report)
                          (:max-decisions controls))
        infra-reviews (capped (:infra-review-queue graph-report)
                              (:max-infra-reviews controls))
        dependency-reviews (capped (:dependency-review-queue graph-report)
                                   (:max-dependency-reviews controls))
        work (vec
              (concat
               (map decision-batch-work
                    (partition-all (:decision-batch-size controls) decisions))
               (map infra-review-work
                    (partition-all (:review-batch-size controls) infra-reviews))
               (map dependency-review-work
                    (partition-all (:review-batch-size controls) dependency-reviews))))]
    {:work work
     :counts {:items (count work)
              :decisions (count decisions)
              :infra-reviews (count infra-reviews)
              :dependency-reviews (count dependency-reviews)
              :decisions-omitted (omitted (:decision-queue graph-report) decisions)
              :infra-reviews-omitted (omitted (:infra-review-queue graph-report)
                                              infra-reviews)
              :dependency-reviews-omitted (omitted (:dependency-review-queue graph-report)
                                                   dependency-reviews)}}))

(defn from-graph-report
  "Wrap the existing graph maintenance report as the first index-maintenance lane."
  ([graph-report] (from-graph-report graph-report {}))
  ([graph-report controls]
   (let [controls (normalize-work-controls controls)
         lane {:schema lane-schema
               :lane graph-lane
               :producer "ygg.system"
               :project-id (:project-id graph-report)
               :counts (:counts graph-report)
               :report graph-report}
         graph-work-result (graph-work graph-report controls)]
     {:schema schema
      :project-id (:project-id graph-report)
      :counts (:counts graph-report)
      :work-controls controls
      :work-counts (:counts graph-work-result)
      :lanes {:graph lane}
      :work (:work graph-work-result)})))

(defn graph-report
  "Return the graph lane report from an index-maintenance report."
  [report]
  (get-in report [:lanes :graph :report]))

(defn work-items
  "Return queue-ready work envelopes from an index-maintenance report."
  [report]
  (vec (:work report)))
