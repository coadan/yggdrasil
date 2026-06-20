(ns agraph.benchmark-preflight
  (:require [clojure.string :as str]))

(def maintenance-preflight-schema
  "agraph.benchmark.maintenance-preflight/v1")

(def blocking-sync-planes
  #{"dependencies" "activity" "validation-history" "map-overlay"})

(def blocking-sync-statuses
  #{"failed" "missing" "stale" "weak"})

(def pass-statuses
  #{"passed" "not-applicable" "not-configured"})

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn- normalized-name
  [value]
  (when-not (blankish? value)
    (name (keyword value))))

(defn- check
  [status attrs]
  (assoc attrs :status status))

(defn- completed-summary-check
  [summary]
  (if (seq summary)
    (check "passed" {:summary summary})
    (check "not-run" {})))

(defn- expectation-configured?
  [expectations]
  (or (seq (:evidence expectations))
      (seq (:graph-evidence expectations))
      (seq (:graphEvidence expectations))
      (seq (:nodes expectations))
      (seq (:chunks expectations))
      (seq (:edges expectations))
      (seq (:forbidden-nodes expectations))
      (seq (:forbidden-chunks expectations))
      (seq (:forbidden-edges expectations))))

(defn- graph-expectation-check
  [graph-expectations expectations]
  (cond
    graph-expectations
    (check (or (:status graph-expectations) "not-run")
           {:summary (:summary graph-expectations)})

    (expectation-configured? expectations)
    (check "not-run" {})

    :else
    (check "not-configured" {})))

(defn blocking-hint-diagnostic?
  [row]
  (not= "info" (str (:severity row))))

(defn- hint-diagnostic-check
  [hints]
  (if (nil? hints)
    (check "not-run" {})
    (let [rows (vec (:diagnostics hints))
          blocking-rows (filterv blocking-hint-diagnostic? rows)]
      (check (if (seq blocking-rows) "failed" "passed")
             {:rows (count rows)
              :blockingRows (count blocking-rows)
              :blockingKinds (->> blocking-rows
                                  (keep :kind)
                                  distinct
                                  sort
                                  vec)}))))

(defn- validation-gaps
  [hints]
  (vec (get-in hints [:architecture :validationGaps])))

(defn blocking-validation-gap?
  [gap]
  (let [plane (normalized-name (:plane gap))
        status (normalized-name (:status gap))]
    (and (contains? blocking-sync-planes plane)
         (contains? blocking-sync-statuses status))))

(defn- sync-check
  [hints]
  (if (nil? hints)
    (check "not-run" {})
    (let [gaps (validation-gaps hints)
          blocking-gaps (filterv blocking-validation-gap? gaps)]
      (check (if (seq blocking-gaps) "failed" "passed")
             {:validationGaps gaps
              :blockingValidationGaps blocking-gaps}))))

(defn- status-rank
  [status]
  (cond
    (= "failed" status) 0
    (= "not-run" status) 1
    :else 2))

(defn- overall-status
  [checks]
  (let [statuses (map :status (vals checks))]
    (cond
      (some #{"failed"} statuses) "failed"
      (some #{"not-run"} statuses) "not-run"
      :else "passed")))

(defn maintenance-preflight
  [{:keys [index-summary system-summary graph-expectations expectations hints]}]
  (let [checks {:index (completed-summary-check index-summary)
                :infer (completed-summary-check system-summary)
                :graphExpectations (graph-expectation-check graph-expectations
                                                            expectations)
                :hintDiagnostics (hint-diagnostic-check hints)
                :syncCheck (sync-check hints)}]
    {:schema maintenance-preflight-schema
     :status (overall-status checks)
     :checks checks}))

(defn pass-status?
  [status]
  (contains? pass-statuses (str status)))

(defn check-passed?
  [check]
  (pass-status? (:status check)))

(defn check-status-order
  [status]
  (status-rank (str status)))
