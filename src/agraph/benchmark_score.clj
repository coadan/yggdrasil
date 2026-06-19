(ns agraph.benchmark-score
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def recall-limits
  [5 10 20])

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn target-ground-truth-files
  [truth]
  (or (:localizationFiles truth) (:changedFiles truth)))
(defn- file-row
  [rank result]
  (when (and (#{:node :chunk} (:target-kind result))
             (not (blankish? (:path result))))
    {:path (:path result)
     :rank rank
     :score (:score result)
     :target-id (:target-id result)
     :target-kind (name (:target-kind result))
     :label (:label result)
     :source-line (:source-line result)}))
(defn top-files
  [ranked]
  (->> ranked
       (map-indexed (fn [idx result] (file-row (inc idx) result)))
       (keep identity)
       (reduce (fn [best row]
                 (let [existing (get best (:path row))]
                   (if (or (nil? existing)
                           (< (:rank row) (:rank existing)))
                     (assoc best (:path row) row)
                     best)))
               {})
       vals
       (sort-by (juxt :rank :path))
       vec))
(defn top-nodes
  [ranked]
  (->> ranked
       (map-indexed vector)
       (keep (fn [[idx result]]
               (when (= :node (:target-kind result))
                 {:id (:target-id result)
                  :rank (inc idx)
                  :score (:score result)
                  :path (:path result)
                  :label (:label result)
                  :kind (some-> (:kind result) name)
                  :source-line (:source-line result)})))
       vec))
(defn top-systems
  [ranked]
  (->> ranked
       (map-indexed vector)
       (keep (fn [[idx result]]
               (when (= :system-node (:target-kind result))
                 {:id (:target-id result)
                  :rank (inc idx)
                  :score (:score result)
                  :label (:label result)
                  :kind (some-> (:kind result) name)})))
       vec))
(defn ground-truth-file-ranks
  [changed-files top-files]
  (let [file-by-path (into {} (map (juxt :path identity)) top-files)]
    (mapv (fn [path]
            (if-let [row (get file-by-path path)]
              (assoc (select-keys row [:path
                                       :rank
                                       :score
                                       :target-id
                                       :target-kind
                                       :label
                                       :source-line])
                     :found? true)
              {:path path
               :found? false}))
          changed-files)))
(defn- unsupported-ground-truth-paths
  [ground-truth]
  (set (map :path (:unsupportedGroundTruthFiles ground-truth))))
(defn scoreable-changed-files
  [ground-truth]
  (or (:scoreableFiles ground-truth)
      (let [unsupported (unsupported-ground-truth-paths ground-truth)]
        (->> (target-ground-truth-files ground-truth)
             (remove unsupported)
             vec))))
(defn- recall-at
  [truth paths k]
  (let [truth (set truth)
        predicted (set (take k paths))]
    (if (seq truth)
      (/ (double (count (set/intersection truth predicted)))
         (double (count truth)))
      0.0)))
(defn- mean-reciprocal-rank-file
  [truth top-files]
  (let [truth (set truth)]
    (or (some (fn [{:keys [path rank]}]
                (when (contains? truth path)
                  (/ 1.0 (double rank))))
              top-files)
        0.0)))
(defn- noise-ratio-at
  [truth paths k]
  (let [truth (set truth)
        predicted (take k paths)]
    (if (seq predicted)
      (/ (double (count (remove truth predicted)))
         (double (count predicted)))
      0.0)))
(defn evidence-cited?
  [row]
  (->> (:evidence row)
       (some #(not (blankish? %)))
       boolean))
(defn- evidence-citation-rate
  [top-files]
  (if (seq top-files)
    (/ (double (count (filter evidence-cited? top-files)))
       (double (count top-files)))
    0.0))
(defn path-evidence-cited?
  [row]
  (let [path (not-empty (str (:path row)))]
    (boolean
     (and path
          (some #(str/includes? (str %) path)
                (:evidence row))))))
(defn- path-evidence-citation-rate
  [top-files]
  (if (seq top-files)
    (/ (double (count (filter path-evidence-cited? top-files)))
       (double (count top-files)))
    0.0))
(defn score-result
  "Return mechanical localization scores for a benchmark result shape."
  [{:keys [groundTruth agraph]}]
  (let [changed-files (:changedFiles groundTruth)
        scoreable-files (scoreable-changed-files groundTruth)
        paths (mapv :path (:topFiles agraph))]
    (merge
     (into {}
           (map (fn [k]
                  [(keyword (str "fileRecallAt" k))
                   (recall-at scoreable-files paths k)]))
           recall-limits)
     {:meanReciprocalRankFile (mean-reciprocal-rank-file scoreable-files
                                                         (:topFiles agraph))
      :noiseRatioAt20 (noise-ratio-at scoreable-files paths 20)
      :evidenceCitationRate (evidence-citation-rate (:topFiles agraph))
      :pathEvidenceCitationRate (path-evidence-citation-rate (:topFiles agraph))
      :changedFiles (count changed-files)
      :localizationFiles (count (or (:localizationFiles groundTruth)
                                    changed-files))
      :scoreableChangedFiles (count scoreable-files)
      :unsupportedGroundTruthFiles (count (:unsupportedGroundTruthFiles groundTruth))
      :coverageExcludedGroundTruthFiles (count (:coverageExcludedFiles groundTruth))})))
