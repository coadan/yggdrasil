(ns agraph.benchmark-preflight
  (:require [agraph.benchmark-util :as benchmark-util]))

(def maintenance-preflight-schema
  "agraph.benchmark.maintenance-preflight/v1")

(def blocking-sync-planes
  #{"dependencies" "activity" "validation-history" "map-overlay"})

(def blocking-sync-statuses
  #{"failed" "missing" "stale" "weak"})

(def pass-statuses
  #{"passed" "not-applicable" "not-configured"})

(defn- normalized-name
  [value]
  (when-not (benchmark-util/blankish? value)
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

(defn- blocking-sync-plane?
  [plane]
  (contains? blocking-sync-planes (normalized-name plane)))

(defn- blocking-sync-status?
  [status]
  (contains? blocking-sync-statuses (normalized-name status)))

(defn blocking-validation-gap?
  [gap]
  (let [plane (normalized-name (:plane gap))
        status (normalized-name (:status gap))]
    (and (blocking-sync-plane? plane)
         (blocking-sync-status? status))))

(defn- family-action-kinds
  [family]
  (case (normalized-name family)
    "dependencies" #{"dependencies" "dependency-review" "dependency-correction"}
    "activity" #{"activity"}
    "validation-history" #{"activity" "validation-history"}
    "map-overlay" #{"map-overlay"}
    #{}))

(defn- matching-family-actions
  [sync-inspect family]
  (let [kinds (family-action-kinds family)]
    (->> (:nextActions sync-inspect)
         (filter #(contains? kinds (normalized-name (:kind %))))
         (take 2)
         vec)))

(defn- family-validation-gap
  [sync-inspect {:keys [family status counts diagnostics]}]
  (let [actions (matching-family-actions sync-inspect family)]
    (cond-> {:plane (normalized-name family)
             :status (normalized-name status)}
      (seq counts) (assoc :counts counts)
      (seq diagnostics) (assoc :diagnostics diagnostics)
      (seq actions) (assoc :nextActions actions))))

(defn- sync-inspect-validation-gaps
  [sync-inspect]
  (->> (:families sync-inspect)
       (filter #(and (blocking-sync-plane? (:family %))
                     (blocking-sync-status? (:status %))))
       (mapv #(family-validation-gap sync-inspect %))))

(defn- sync-check-from-inspect
  [sync-inspect]
  (let [blocking-gaps (sync-inspect-validation-gaps sync-inspect)]
    (check (if (seq blocking-gaps) "failed" "passed")
           {:source "sync-inspect"
            :families (->> (:families sync-inspect)
                           (filter #(blocking-sync-plane? (:family %)))
                           (mapv #(select-keys % [:family
                                                  :status
                                                  :counts
                                                  :diagnostics])))
            :validationGaps blocking-gaps
            :blockingValidationGaps blocking-gaps})))

(defn- sync-check-from-hints
  [hints]
  (if (nil? hints)
    (check "not-run" {})
    (let [gaps (validation-gaps hints)
          blocking-gaps (filterv blocking-validation-gap? gaps)]
      (check (if (seq blocking-gaps) "failed" "passed")
             {:source "context-hints"
              :validationGaps gaps
              :blockingValidationGaps blocking-gaps}))))

(defn- sync-check
  [hints sync-inspect]
  (cond
    (= "failed" (normalized-name (:status sync-inspect)))
    (check "failed" (cond-> {:source "sync-inspect"
                             :validationGaps []
                             :blockingValidationGaps []}
                      (:error sync-inspect) (assoc :error (:error sync-inspect))))

    (seq (:families sync-inspect))
    (sync-check-from-inspect sync-inspect)

    :else
    (sync-check-from-hints hints)))

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
  [{:keys [index-summary system-summary graph-expectations expectations hints sync-inspect]}]
  (let [checks {:index (completed-summary-check index-summary)
                :infer (completed-summary-check system-summary)
                :graphExpectations (graph-expectation-check graph-expectations
                                                            expectations)
                :hintDiagnostics (hint-diagnostic-check hints)
                :syncCheck (sync-check hints sync-inspect)}]
    {:schema maintenance-preflight-schema
     :status (overall-status checks)
     :checks checks}))

(defn claim-ready?
  "Return true when a run can support maintained-graph benchmark claims."
  [maintenance-preflight]
  (= "passed" (normalized-name (:status maintenance-preflight))))

(defn assoc-run-preflight
  "Attach maintenance preflight and derived claim readiness to a run or score map."
  [result maintenance-preflight]
  (cond-> result
    maintenance-preflight
    (assoc :maintenancePreflight maintenance-preflight
           :claimReady (claim-ready? maintenance-preflight))))

(defn pass-status?
  [status]
  (contains? pass-statuses (str status)))

(defn check-passed?
  [check]
  (pass-status? (:status check)))

(defn check-status-order
  [status]
  (status-rank (str status)))
