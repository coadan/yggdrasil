(ns agraph.benchmark-audit-scope-test
  (:require [agraph.benchmark-audit-scope :as benchmark-audit-scope]
            [clojure.test :refer [deftest is]]))

(defn- result
  [& {:as opts}]
  (merge {:case-id "case-1"
          :repo-id "repo"
          :groundTruth {:changedFiles ["src/app.clj"
                                       "src/missing.clj"
                                       "CHANGELOG.md"]
                        :localizationFiles ["src/app.clj"
                                            "src/missing.clj"
                                            "CHANGELOG.md"]
                        :scoreableFiles ["src/app.clj" "src/missing.clj"]
                        :coverageExcludedFiles [{:path "CHANGELOG.md" :kind "doc"}]
                        :unsupportedGroundTruthFiles []}
          :groundTruthRanks {:files [{:path "src/app.clj"
                                      :rank 3
                                      :found? true}
                                     {:path "src/missing.clj"
                                      :found? false}
                                     {:path "CHANGELOG.md"
                                      :found? false}]}
          :contextGroundTruthRanks {:files [{:path "src/app.clj"
                                             :rank 3
                                             :found? true}
                                            {:path "src/missing.clj"
                                             :rank 8
                                             :found? true}
                                            {:path "CHANGELOG.md"
                                             :found? false}]}
          :agent {:topFiles [{:path "src/other.clj"
                              :rank 1
                              :evidence ["grep match in src/other.clj"]}
                             {:path "src/app.clj"
                              :rank 3
                              :evidence ["graph edge to src/app.clj"]}
                             {:path "lib/util.clj"
                              :rank 5
                              :evidence [""]}]}
          :graphExpectations {:summary {:expectedEvidence 2
                                        :foundEvidence 1
                                        :missingEvidence 1
                                        :expectedNodes 0
                                        :foundNodes 0
                                        :missingNodes 0
                                        :expectedEdges 0
                                        :foundEdges 0
                                        :missingEdges 0}
                              :expectedEvidence [{:expectation {:kind "env-var"
                                                                :path "src/app.clj"
                                                                :label "DATABASE_URL"}
                                                  :found? true
                                                  :matchCount 1
                                                  :matches [{:kind "env-var"
                                                             :path "src/app.clj"
                                                             :label "DATABASE_URL"}]}
                                                 {:expectation {:kind "sql-security"
                                                                :path "src/missing.clj"
                                                                :label "SECURITY DEFINER"}
                                                  :found? false
                                                  :matchCount 0
                                                  :matches []}]}}
         opts))

(deftest case-audit-scope-returns-nil-for-non-map
  (is (nil? (benchmark-audit-scope/case-audit-scope nil)))
  (is (nil? (benchmark-audit-scope/case-audit-scope "not-a-map"))))

(deftest case-audit-scope-surfaces-ground-truth-files-with-rank-and-context
  (let [scope (benchmark-audit-scope/case-audit-scope (result))]
    (is (= "agraph.benchmark.audit-scope/v1" (:schema scope)))
    (is (= "case-1" (:caseId scope)))
    (let [gt-files (:groundTruthFiles scope)
          app-row (first (filter #(= "src/app.clj" (:path %)) gt-files))
          missing-row (first (filter #(= "src/missing.clj" (:path %)) gt-files))
          excluded-row (first (filter #(= "CHANGELOG.md" (:path %)) gt-files))]
      (is (= 3 (count gt-files)))
      (is (:found? app-row))
      (is (= 3 (:rank app-row)))
      (is (= 3 (:contextRank app-row)))
      (is (:evidenceCited? app-row))
      (is (:pathEvidenceCited? app-row))
      (is (not (:found? missing-row)))
      (is (:contextFound? missing-row))
      (is (= 8 (:contextRank missing-row)))
      (is (not (:scoreable? excluded-row))))))

(deftest case-audit-scope-summarizes-ground-truth-coverage
  (let [summary (:groundTruthSummary (benchmark-audit-scope/case-audit-scope (result)))]
    (is (= 3 (:total summary)))
    (is (= 1 (:found summary)))
    (is (= 2 (:missed summary)))
    (is (= 1 (:presentInContextButMissed summary)))))

(deftest case-audit-scope-surfaces-expected-evidence-found-and-missing
  (let [scope (benchmark-audit-scope/case-audit-scope (result))
        evidence (:expectedEvidence scope)
        ev-rows (:evidence evidence)]
    (is (= 2 (count ev-rows)))
    (is (:found? (first ev-rows)))
    (is (= 1 (:matchCount (first ev-rows))))
    (is (= "env-var" (-> ev-rows first :topMatch :kind)))
    (is (not (:found? (second ev-rows))))
    (is (zero? (:matchCount (second ev-rows))))))

(deftest case-audit-scope-surfaces-top-ranked-files-with-ground-truth-flag
  (let [scope (benchmark-audit-scope/case-audit-scope (result))
        top-files (:topRankedFiles scope)
        other-row (first (filter #(= "src/other.clj" (:path %)) top-files))
        app-row (first (filter #(= "src/app.clj" (:path %)) top-files))
        util-row (first (filter #(= "lib/util.clj" (:path %)) top-files))]
    (is (= 3 (count top-files)))
    (is (= 1 (:rank other-row)))
    (is (not (:isGroundTruth? other-row)))
    (is (:evidenceCited? other-row))
    (is (:pathEvidenceCited? other-row))
    (is (:isGroundTruth? app-row))
    (is (:evidenceCited? app-row))
    (is (not (:evidenceCited? util-row)))))

(deftest case-audit-scope-rejects-near-miss-path-evidence
  (let [scope (benchmark-audit-scope/case-audit-scope
               (result :agent {:topFiles [{:path "src/app.clj"
                                           :rank 1
                                           :evidence ["near miss src/app.cljs"
                                                      "prefixed xsrc/app.clj"]}]}))
        app-ground-truth (first (filter #(= "src/app.clj" (:path %))
                                        (:groundTruthFiles scope)))
        app-top-file (first (:topRankedFiles scope))]
    (is (:evidenceCited? app-ground-truth))
    (is (not (:pathEvidenceCited? app-ground-truth)))
    (is (:evidenceCited? app-top-file))
    (is (not (:pathEvidenceCited? app-top-file)))))

(deftest case-audit-scope-includes-graph-expectation-summary
  (let [summary (:graphExpectationSummary
                 (benchmark-audit-scope/case-audit-scope (result)))]
    (is (= 2 (:expectedEvidence summary)))
    (is (= 1 (:foundEvidence summary)))
    (is (= 1 (:missingEvidence summary)))))

(deftest case-audit-scope-handles-missing-graph-expectations
  (let [scope (benchmark-audit-scope/case-audit-scope
               (result :graphExpectations nil))]
    (is (= {} (:expectedEvidence scope)))
    (is (= 0 (:expectedEvidence (:graphExpectationSummary scope))))))

(deftest case-audit-scope-handles-missing-context-ranks
  (let [scope (benchmark-audit-scope/case-audit-scope
               (result :contextGroundTruthRanks nil))
        gt-files (:groundTruthFiles scope)
        missing-row (first (filter #(= "src/missing.clj" (:path %)) gt-files))]
    (is (nil? (:contextFound? missing-row)))
    (is (nil? (:contextRank missing-row)))))

(deftest case-audit-scope-handles-empty-top-files
  (let [scope (benchmark-audit-scope/case-audit-scope
               (result :agent {:topFiles []}
                       :groundTruthRanks {:files [{:path "src/app.clj"
                                                   :found? false}
                                                  {:path "src/missing.clj"
                                                   :found? false}
                                                  {:path "CHANGELOG.md"
                                                   :found? false}]}))
        top-files (:topRankedFiles scope)]
    (is (empty? top-files))
    (is (= 0 (:found (:groundTruthSummary scope))))))
