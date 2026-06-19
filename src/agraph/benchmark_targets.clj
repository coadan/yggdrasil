(ns agraph.benchmark-targets)

(defn target-runs
  [report]
  (reduce + 0 (map #(long (or (:runs %) 0))
                   (:improvementSummary report))))

(defn target-runs-by-kind
  [report]
  (->> (:improvementSummary report)
       (reduce (fn [runs-by-kind {:keys [kind runs]}]
                 (if-let [kind (some-> kind str not-empty)]
                   (update runs-by-kind kind (fnil + 0) (long (or runs 0)))
                   runs-by-kind))
               (sorted-map))))
