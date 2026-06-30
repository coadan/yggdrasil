(ns ygg.retrieval.fusion
  "Pure candidate score fusion helpers.")

(def default-rrf-k 60)

(defn positive-score-map
  [scores]
  (into {}
        (keep (fn [[id score]]
                (let [score (double (or score 0.0))]
                  (when (pos? score)
                    [id score]))))
        scores))

(defn rank-map
  "Return `{id rank}` for positive scores, with rank 1 as best."
  [scores]
  (->> (positive-score-map scores)
       (sort-by (fn [[id score]] [(- score) (str id)]))
       (map-indexed (fn [idx [id _]]
                      [id (inc idx)]))
       (into {})))

(defn rrf-score
  "Return the reciprocal-rank contribution for rank."
  ([rank] (rrf-score rank default-rrf-k))
  ([rank k]
   (if (and rank (pos? (long rank)))
     (/ 1.0 (+ (double k) (double rank)))
     0.0)))

(defn- add-weighted-score
  [scores id score]
  (let [score (double (or score 0.0))]
    (if (pos? score)
      (update scores id (fnil + 0.0) score)
      scores)))

(defn weighted-fuse
  "Fuse named score maps with per-source weights."
  ([sources] (weighted-fuse sources {}))
  ([sources weights]
   (reduce-kv
    (fn [out source scores]
      (let [weight (double (get weights source 1.0))]
        (reduce-kv
         (fn [out id score]
           (add-weighted-score out id (* weight (double (or score 0.0)))))
         out
         (positive-score-map scores))))
    {}
    sources)))

(defn rrf-fuse
  "Fuse named score maps with reciprocal rank fusion.

  Optional source weights multiply each source's reciprocal-rank contribution.
  The input scores are used only to order each source.
  "
  ([sources] (rrf-fuse sources {}))
  ([sources {:keys [k weights]
             :or {k default-rrf-k
                  weights {}}}]
   (reduce-kv
    (fn [out source scores]
      (let [weight (double (get weights source 1.0))]
        (reduce-kv
         (fn [out id rank]
           (add-weighted-score out id (* weight (rrf-score rank k))))
         out
         (rank-map scores))))
    {}
    sources)))

(defn source-counts
  [sources]
  (into (sorted-map)
        (map (fn [[source scores]]
               [source (count (positive-score-map scores))]))
        sources))

(defn overlap-count
  "Return count of ids found by more than one source."
  [sources]
  (let [counts (reduce-kv
                (fn [counts _ scores]
                  (reduce (fn [counts id]
                            (update counts id (fnil inc 0)))
                          counts
                          (keys (positive-score-map scores))))
                {}
                sources)]
    (count (filter #(< 1 %) (vals counts)))))
