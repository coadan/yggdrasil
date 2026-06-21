(ns agraph.benchmark-audit-scope
  "Per-case audit-scope views for benchmark reports.

  Surfaces ground-truth file ranks, expected-evidence found/missing status,
  and top-ranked file citations so operators can read each case as an
  audit scope instead of a flat diagnostic."
  (:require [agraph.benchmark-score :as benchmark-score]
            [agraph.benchmark-util :as benchmark-util]))

(def audit-scope-schema
  "agraph.benchmark.audit-scope/v1")

(def ^:private top-ranked-limit
  10)

(defn- evidence-cited?
  [row]
  (->> (:evidence row)
       (some #(not (benchmark-util/blankish? %)))
       boolean))

(defn- ground-truth-file-row
  [rank-row context-rank-by-path top-file-by-path scoreable-file-set]
  (let [path (:path rank-row)
        found? (boolean (:found? rank-row))
        top-file (get top-file-by-path path)
        context-rank-row (get context-rank-by-path path)
        context-found? (boolean (:found? context-rank-row))]
    (cond-> {:path path
             :scoreable? (contains? scoreable-file-set path)
             :found? found?}
      found?
      (assoc :rank (:rank rank-row))

      context-found?
      (assoc :contextRank (:rank context-rank-row))

      (and (not found?) context-found?)
      (assoc :contextFound? true)

      top-file
      (assoc :evidenceCited? (evidence-cited? top-file)
             :pathEvidenceCited? (benchmark-score/path-evidence-cited? top-file)))))

(defn- ground-truth-files
  [result]
  (let [ground-truth (:groundTruth result)
        ranks (get-in result [:groundTruthRanks :files])
        context-ranks (get-in result [:contextGroundTruthRanks :files])
        context-rank-by-path (into {} (map (juxt :path identity)) context-ranks)
        top-files (get-in result [:agent :topFiles])
        top-file-by-path (into {} (map (juxt :path identity)) top-files)
        scoreable-file-set (set (benchmark-score/scoreable-changed-files ground-truth))]
    (mapv #(ground-truth-file-row % context-rank-by-path top-file-by-path scoreable-file-set)
          ranks)))

(defn- expected-evidence-row
  [expected]
  (let [matches (:matches expected)]
    (cond-> {:found? (boolean (:found? expected))
             :matchCount (long (:matchCount expected 0))
             :expectation (:expectation expected)}
      (seq matches)
      (assoc :topMatch (first matches)))))

(defn- expected-evidence
  [result]
  (let [expected-evidence-rows (get-in result [:graphExpectations :expectedEvidence])
        expected-nodes (get-in result [:graphExpectations :expectedNodes])
        expected-edges (get-in result [:graphExpectations :expectedEdges])
        expected-chunks (get-in result [:graphExpectations :expectedChunks])]
    (cond-> {}
      (seq expected-evidence-rows)
      (assoc :evidence (mapv expected-evidence-row expected-evidence-rows))

      (seq expected-nodes)
      (assoc :nodes (mapv expected-evidence-row expected-nodes))

      (seq expected-edges)
      (assoc :edges (mapv expected-evidence-row expected-edges))

      (seq expected-chunks)
      (assoc :chunks (mapv expected-evidence-row expected-chunks)))))

(defn- top-ranked-file-row
  [ranked-file scoreable-file-set]
  (let [path (:path ranked-file)]
    {:path path
     :rank (:rank ranked-file)
     :isGroundTruth? (contains? scoreable-file-set path)
     :evidenceCited? (evidence-cited? ranked-file)
     :pathEvidenceCited? (benchmark-score/path-evidence-cited? ranked-file)}))

(defn- top-ranked-files
  [result]
  (let [ground-truth (:groundTruth result)
        top-files (get-in result [:agent :topFiles])
        scoreable-file-set (set (benchmark-score/scoreable-changed-files ground-truth))]
    (->> top-files
         (take top-ranked-limit)
         (mapv #(top-ranked-file-row % scoreable-file-set)))))

(defn- graph-expectation-summary
  [result]
  (or (get-in result [:graphExpectations :summary])
      {:expectedEvidence 0
       :foundEvidence 0
       :missingEvidence 0
       :expectedNodes 0
       :foundNodes 0
       :missingNodes 0
       :expectedEdges 0
       :foundEdges 0
       :missingEdges 0
       :expectedChunks 0
       :foundChunks 0
       :missingChunks 0}))

(defn case-audit-scope
  "Return a per-case audit-scope view for one benchmark result.

  Surfaces:
  - :groundTruthFiles — each localization file with found/rank/contextRank/
    evidenceCited status
  - :expectedEvidence — each expected evidence/node/edge/chunk row with
    found/matchCount/topMatch
  - :topRankedFiles — top-N agent-ranked files with isGroundTruth?/
    evidenceCited status
  - :graphExpectationSummary — found/missing counts for all expectation types"
  [result]
  (when (map? result)
    (let [gt-files (ground-truth-files result)
          found-count (count (filter :found? gt-files))
          missed-count (count (remove :found? gt-files))
          present-in-context-count (count (filter :contextFound? gt-files))]
      {:schema audit-scope-schema
       :caseId (:case-id result)
       :repoId (:repo-id result)
       :groundTruthFiles gt-files
       :groundTruthSummary {:total (count gt-files)
                            :found found-count
                            :missed missed-count
                            :presentInContextButMissed present-in-context-count}
       :expectedEvidence (expected-evidence result)
       :topRankedFiles (top-ranked-files result)
       :graphExpectationSummary (graph-expectation-summary result)})))
