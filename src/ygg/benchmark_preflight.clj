(ns ygg.benchmark-preflight
  (:require [ygg.benchmark-util :as benchmark-util]))

(def benchmark-preflight-schema
  "ygg.benchmark.preflight/v1")

(def blocking-sync-planes
  #{"dependencies" "activity" "validation-history" "correction-overlay" "freshness"})

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
    "correction-overlay" #{"correction-overlay"}
    "freshness" #{"freshness" "source-files"}
    #{}))

(defn- matching-family-actions
  [sync-inspect family]
  (let [kinds (family-action-kinds family)]
    (->> (:nextActions sync-inspect)
         (filter #(contains? kinds (normalized-name (:kind %))))
         (take 2)
         vec)))

(defn- count-value
  [counts k]
  (long (or (get counts k)
            (get counts (name k))
            0)))

(defn- family-validation-gap
  [sync-inspect {:keys [family status counts diagnostics]}]
  (let [actions (matching-family-actions sync-inspect family)]
    (cond-> {:plane (normalized-name family)
             :status (normalized-name status)}
      (seq counts) (assoc :counts counts)
      (seq diagnostics) (assoc :diagnostics diagnostics)
      (seq actions) (assoc :nextActions actions))))

(defn- freshness-validation-gap
  [sync-inspect]
  (let [freshness (:freshness sync-inspect)]
    (when (and (blocking-sync-plane? :freshness)
               (blocking-sync-status? (:status freshness)))
      (cond-> {:plane "freshness"
               :status (normalized-name (:status freshness))}
        (:counts freshness) (assoc :counts (:counts freshness))
        (seq (:repos freshness)) (assoc :repos (:repos freshness))
        (seq (matching-family-actions sync-inspect :freshness))
        (assoc :nextActions
               (matching-family-actions sync-inspect :freshness))))))

(defn- patch-change-freshness?
  [freshness]
  (let [counts (:counts freshness)]
    (and (= "stale" (normalized-name (:status freshness)))
         (pos? (count-value counts :changed))
         (zero? (count-value counts :missing))
         (zero? (count-value counts :unindexed))
         (zero? (count-value counts :upstream-stale)))))

(defn- sync-inspect-validation-gaps
  [sync-inspect {:keys [allow-patch-freshness?]}]
  (let [family-gaps (->> (:families sync-inspect)
                         (filter #(and (blocking-sync-plane? (:family %))
                                       (blocking-sync-status? (:status %))))
                         (mapv #(family-validation-gap sync-inspect %)))
        freshness (:freshness sync-inspect)
        freshness-gap (freshness-validation-gap sync-inspect)
        allowed-freshness? (and allow-patch-freshness?
                                (patch-change-freshness? freshness))]
    (cond-> family-gaps
      (and freshness-gap (not allowed-freshness?)) (conj freshness-gap))))

(defn- sync-check-from-inspect
  [sync-inspect opts]
  (let [blocking-gaps (sync-inspect-validation-gaps sync-inspect opts)
        freshness-gap (freshness-validation-gap sync-inspect)
        patch-freshness-allowed? (and (:allow-patch-freshness? opts)
                                      (patch-change-freshness?
                                       (:freshness sync-inspect)))]
    (check (if (seq blocking-gaps) "failed" "passed")
           (cond-> {:source "sync-inspect"
                    :freshness (:freshness sync-inspect)
                    :families (->> (:families sync-inspect)
                                   (filter #(blocking-sync-plane? (:family %)))
                                   (mapv #(select-keys % [:family
                                                          :status
                                                          :counts
                                                          :diagnostics])))
                    :validationGaps blocking-gaps
                    :blockingValidationGaps blocking-gaps}
             patch-freshness-allowed?
             (assoc :patchFreshnessAllowed true
                    :allowedValidationGaps [freshness-gap])))))

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
  [hints sync-inspect opts]
  (cond
    (= "failed" (normalized-name (:status sync-inspect)))
    (check "failed" (cond-> {:source "sync-inspect"
                             :validationGaps []
                             :blockingValidationGaps []}
                      (:error sync-inspect) (assoc :error (:error sync-inspect))))

    (or (seq (:families sync-inspect))
        (:freshness sync-inspect))
    (sync-check-from-inspect sync-inspect opts)

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

(defn benchmark-preflight
  [{:keys [index-summary system-summary graph-expectations expectations hints sync-inspect
           allow-patch-freshness?]}]
  (let [checks {:index (completed-summary-check index-summary)
                :infer (completed-summary-check system-summary)
                :graphExpectations (graph-expectation-check graph-expectations
                                                            expectations)
                :hintDiagnostics (hint-diagnostic-check hints)
                :syncCheck (sync-check hints
                                       sync-inspect
                                       {:allow-patch-freshness?
                                        allow-patch-freshness?})}]
    {:schema benchmark-preflight-schema
     :status (overall-status checks)
     :checks checks}))

(defn claim-ready?
  "Return true when a run can support benchmark claims."
  [benchmark-preflight]
  (= "passed" (normalized-name (:status benchmark-preflight))))

(defn assoc-run-preflight
  "Attach benchmark preflight and derived claim readiness to a run or score map."
  [result benchmark-preflight]
  (cond-> result
    benchmark-preflight
    (assoc :benchmarkPreflight benchmark-preflight
           :claimReady (claim-ready? benchmark-preflight))))

(defn pass-status?
  [status]
  (contains? pass-statuses (str status)))

(defn check-passed?
  [check]
  (pass-status? (:status check)))

(defn check-status-order
  [status]
  (status-rank (str status)))
