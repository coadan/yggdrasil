(ns ygg.benchmark-prediction-test
  (:require [ygg.benchmark-prediction :as benchmark-prediction]
            [clojure.test :refer [deftest is]]))

(deftest compact-output-keeps-wide-ranked-surface
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat
                [(row "core-a.go"
                      1
                      {:docCount 1
                       :candidateFileCount 1
                       :retrievedSourceCount 2
                       :repeatedRetrievedSourceBoost 0.5
                       :rankScore 20.0})
                 (row "core-b.go"
                      2
                      {:docCount 1
                       :candidateFileCount 1
                       :fileIdentitySupportLabelCount 4
                       :matchedTokenCount 5
                       :rankScore 18.0})
                 (row "core-c.go"
                      3
                      {:docCount 1
                       :candidateFileCount 1
                       :fileIdentitySupportLabelCount 4
                       :matchedTokenCount 5
                       :rankScore 17.0})]
                (mapv (fn [rank]
                        (row (str "fill-" rank ".go")
                             rank
                             {:docCount 1
                              :candidateFileCount 1
                              :matchedTokenCount 2
                              :rankScore (- 17.0 rank)}))
                      (range 4 20))
                [(row "late-source.go"
                      20
                      {:candidateFileCount 1
                       :candidateSourceRank 16
                       :matchedTokenCount 2
                       :sourceGraphCandidateEvidenceScore 0.36
                       :rankScore 1.03})
                 (row "outside-limit.go"
                      21
                      {:candidateFileCount 1
                       :matchedTokenCount 1
                       :rankScore 0.5})]))
        narrow-paths (mapv :path (compact-output files 7 nil))
        wide-paths (mapv :path (compact-output files 20 nil))]
    (is (= ["core-a.go" "core-b.go" "core-c.go"]
           narrow-paths))
    (is (= 20 (count wide-paths)))
    (is (= "late-source.go" (last wide-paths)))))
