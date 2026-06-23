(ns ygg.benchmark-score
  (:require [ygg.benchmark-util :as benchmark-util]
            [clojure.string :as str]))

(def recall-limits
  [5 10 20])

(defn target-ground-truth-files
  [truth]
  (or (:localizationFiles truth) (:changedFiles truth)))

(defn file-path
  [file]
  (cond
    (string? file) file
    (map? file) (:path file)
    :else nil))

(defn file-repo-id
  [file]
  (when (map? file)
    (or (:repo-id file)
        (:repoId file)
        (:repo file))))

(defn file-key
  [file]
  [(file-repo-id file) (file-path file)])

(defn same-file?
  [expected actual]
  (let [expected-repo-id (file-repo-id expected)
        actual-repo-id (file-repo-id actual)]
    (and (= (file-path expected) (file-path actual))
         (or (nil? expected-repo-id)
             (= expected-repo-id actual-repo-id)))))

(defn file-display
  [file]
  (let [repo-id (file-repo-id file)
        path (file-path file)]
    (if repo-id
      (str repo-id ":" path)
      path)))

(defn file-row-fields
  [file]
  (cond-> {:path (file-path file)}
    (file-repo-id file) (assoc :repo-id (file-repo-id file))))

(defn- file-row
  [rank result]
  (when (and (#{:node :chunk} (:target-kind result))
             (not (benchmark-util/blankish? (:path result))))
    (merge (file-row-fields {:repo-id (:repo-id result)
                             :path (:path result)})
           {:rank rank
            :score (:score result)
            :target-id (:target-id result)
            :target-kind (name (:target-kind result))
            :label (:label result)
            :source-line (:source-line result)})))
(defn top-files
  [ranked]
  (->> ranked
       (map-indexed (fn [idx result] (file-row (inc idx) result)))
       (keep identity)
       (reduce (fn [best row]
                 (let [key (file-key row)
                       existing (get best key)]
                   (if (or (nil? existing)
                           (< (:rank row) (:rank existing)))
                     (assoc best key row)
                     best)))
               {})
       vals
       (sort-by (juxt :rank :repo-id :path))
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
  (let [file-by-key (into {} (map (juxt file-key identity)) top-files)]
    (mapv (fn [file]
            (if-let [row (or (get file-by-key (file-key file))
                             (when-not (file-repo-id file)
                               (some #(when (same-file? file %) %)
                                     top-files)))]
              (assoc (select-keys row [:path
                                       :repo-id
                                       :rank
                                       :score
                                       :target-id
                                       :target-kind
                                       :label
                                       :source-line])
                     :found? true)
              (assoc (file-row-fields file) :found? false)))
          changed-files)))
(defn- unsupported-ground-truth-paths
  [ground-truth]
  (set (map file-key (:unsupportedGroundTruthFiles ground-truth))))
(defn scoreable-changed-files
  [ground-truth]
  (or (:scoreableFiles ground-truth)
      (let [unsupported (unsupported-ground-truth-paths ground-truth)]
        (->> (target-ground-truth-files ground-truth)
             (remove #(contains? unsupported (file-key %)))
             vec))))
(defn- recall-at
  [truth top-files k]
  (let [predicted (take k top-files)]
    (if (seq truth)
      (/ (double (count (filter (fn [file]
                                  (some #(same-file? file %) predicted))
                                truth)))
         (double (count truth)))
      0.0)))
(defn- mean-reciprocal-rank-file
  [truth top-files]
  (or (some (fn [{:keys [rank] :as top-file}]
              (when (some #(same-file? % top-file) truth)
                (/ 1.0 (double rank))))
            top-files)
      0.0))
(defn- noise-ratio-at
  [truth top-files k]
  (let [predicted (take k top-files)]
    (if (seq predicted)
      (/ (double (count (remove (fn [top-file]
                                  (some #(same-file? % top-file) truth))
                                predicted)))
         (double (count predicted)))
      0.0)))
(defn evidence-cited?
  [row]
  (->> (:evidence row)
       (some #(not (benchmark-util/blankish? %)))
       boolean))
(defn- evidence-citation-rate
  [top-files]
  (if (seq top-files)
    (/ (double (count (filter evidence-cited? top-files)))
       (double (count top-files)))
    0.0))

(def ^:private path-token-chars
  "A-Za-z0-9_./@+~=-")

(defn- path-citation-pattern
  [path]
  (re-pattern (str "(^|[^" path-token-chars "])"
                   (java.util.regex.Pattern/quote path)
                   "($|[^" path-token-chars "])")))

(defn- path-cited-in-text?
  [path text]
  (boolean
   (and path
        (re-find (path-citation-pattern path) (str text)))))

(defn path-evidence-cited?
  [row]
  (let [path (not-empty (str (:path row)))]
    (boolean
     (and path
          (some #(path-cited-in-text? path %)
                (:evidence row))))))
(defn- path-evidence-citation-rate
  [top-files]
  (if (seq top-files)
    (/ (double (count (filter path-evidence-cited? top-files)))
       (double (count top-files)))
    0.0))
(defn- evidence-strings
  [top-files]
  (->> top-files
       (mapcat :evidence)
       (remove benchmark-util/blankish?)
       (map str)
       vec))
(defn- expected-evidence-cited?
  [evidence expected]
  (let [path (some-> (:path expected) str not-empty)
        label (some-> (:label expected) str not-empty)]
    (boolean
     (or (and path
              (some #(str/includes? % path) evidence))
         (and (nil? path)
              label
              (some #(str/includes? % label) evidence))))))
(defn- citation-evidence-expectations
  [expectations]
  (vec (or (seq (:citation-evidence expectations))
           (seq (:citationEvidence expectations))
           (:evidence expectations))))
(defn- expected-evidence-citation-metrics
  [expectations top-files]
  (let [expected (citation-evidence-expectations expectations)]
    (when (seq expected)
      (let [evidence (evidence-strings top-files)
            cited (count (filter #(expected-evidence-cited? evidence %) expected))
            total (count expected)]
        {:expectedEvidenceCitationRate (/ (double cited) (double total))
         :expectedEvidenceCitations cited
         :expectedEvidenceCitationTargets total}))))
(defn score-result
  "Return mechanical localization scores for a benchmark result shape."
  [{:keys [groundTruth ygg expectations]}]
  (let [changed-files (:changedFiles groundTruth)
        scoreable-files (scoreable-changed-files groundTruth)]
    (merge
     (into {}
           (map (fn [k]
                  [(keyword (str "fileRecallAt" k))
                   (recall-at scoreable-files (:topFiles ygg) k)]))
           recall-limits)
     {:meanReciprocalRankFile (mean-reciprocal-rank-file scoreable-files
                                                         (:topFiles ygg))
      :noiseRatioAt20 (noise-ratio-at scoreable-files (:topFiles ygg) 20)
      :evidenceCitationRate (evidence-citation-rate (:topFiles ygg))
      :pathEvidenceCitationRate (path-evidence-citation-rate (:topFiles ygg))
      :changedFiles (count changed-files)
      :localizationFiles (count (or (:localizationFiles groundTruth)
                                    changed-files))
      :scoreableChangedFiles (count scoreable-files)
      :unsupportedGroundTruthFiles (count (:unsupportedGroundTruthFiles groundTruth))
      :coverageExcludedGroundTruthFiles (count (:coverageExcludedFiles groundTruth))}
     (expected-evidence-citation-metrics expectations (:topFiles ygg)))))
