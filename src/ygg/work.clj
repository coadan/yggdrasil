(ns ygg.work
  "Reusable sync-work validation and application boundary."
  (:require [ygg.dependency-review :as dependency-review]
            [ygg.infra-review :as infra-review]
            [ygg.queue :as queue]
            [ygg.system.decision-classifier :as decision-classifier]
            [clojure.string :as str]))

(def validation-schema
  "ygg.sync.work.validation/v1")

(def apply-schema
  "ygg.sync.work.apply/v1")

(defn- apply-result-without-validation!
  [root id map-path payload-schema]
  (case payload-schema
    "ygg.infra.review-packet/v1"
    (infra-review/apply-work-result! root id map-path)

    "ygg.dependency.review-packet/v1"
    (dependency-review/apply-work-result! root id map-path)

    "ygg.frontier.decision/v1"
    (decision-classifier/apply-work-result! root id map-path)

    {:schema apply-schema
     :status "failed"
     :errors [{:path [:payload :schema]
               :error "No apply handler for work item payload schema."
               :value payload-schema}]}))

(defn apply-schema-for
  [payload-schema]
  (case payload-schema
    "ygg.infra.review-packet/v1" infra-review/apply-schema
    "ygg.dependency.review-packet/v1" dependency-review/apply-schema
    "ygg.frontier.decision/v1" decision-classifier/apply-schema
    apply-schema))

(defn validate-result
  "Validate a completed queue item result without applying it."
  [root id]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        payload-schema (get-in item [:payload :schema])
        result-schema (get-in item [:result :schema])
        status (queue/status-name (:status item))
        expected-result-schema (get-in item [:payload :expectedResultSchema])
        raw-errors (case payload-schema
                     "ygg.infra.review-packet/v1"
                     (infra-review/validate-result item)

                     "ygg.dependency.review-packet/v1"
                     (dependency-review/validate-result item)

                     "ygg.frontier.decision/v1"
                     (decision-classifier/validate-result item)

                     nil)
        errors (when (= "done" status)
                 raw-errors)]
    (cond-> {:schema validation-schema
             :status (cond
                       (not= "done" status) "not-done"
                       (nil? raw-errors) "unsupported"
                       (seq errors) "invalid"
                       :else "valid")
             :payload-schema payload-schema
             :item (queue/item-summary found)}
      result-schema (assoc :result-schema result-schema)
      expected-result-schema (assoc :expected-result-schema expected-result-schema)
      (seq errors) (assoc :errors errors)
      (and (= "done" status)
           (nil? raw-errors))
      (assoc :errors [{:path [:payload :schema]
                       :error "No validation handler for work item payload schema."
                       :value payload-schema}]))))

(defn validation-apply-errors
  [validation]
  (or (seq (:errors validation))
      [{:path [:validation :status]
        :error "Work result must validate before apply."
        :value (:status validation)}]))

(defn fail-invalid-result!
  "Move an invalid completed item to failed and return an apply-style failure."
  [root id validation]
  (let [errors (vec (validation-apply-errors validation))
        done? (= "done" (get-in validation [:item :status]))
        failed (when done?
                 (queue/fail! root
                              id
                              (str "Invalid sync work result: "
                                   (str/join "; " (map :error errors)))))]
    {:schema (apply-schema-for (:payload-schema validation))
     :status "failed"
     :errors errors
     :validation validation
     :item (if failed
             (queue/item-summary failed)
             (:item validation))}))

(defn apply-result!
  "Validate and apply a completed queue item result to a map file."
  [root id map-path]
  (let [validation (validate-result root id)]
    (if (= "valid" (:status validation))
      (assoc (apply-result-without-validation! root
                                               id
                                               map-path
                                               (:payload-schema validation))
             :validation validation)
      (fail-invalid-result! root id validation))))
