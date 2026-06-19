(ns agraph.cli-progress-test
  (:require [agraph.cli :as cli]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest sync-progress-line-renders-human-counts
  (is (= "- app plan 1 changed file, 2 skipped files, 0 deleted files"
         (#'cli/sync-progress-line
          {:phase :plan-complete
           :repo-id "app"
           :files-changed 1
           :files-skipped 2
           :files-deleted 0})))
  (is (= "- app committed 3 files, 4 chunks, 5 search docs, 1 diagnostic"
         (#'cli/sync-progress-line
          {:phase :commit-complete
           :repo-id "app"
           :files-indexed 3
           :chunks 4
           :search-docs 5
           :diagnostics 1}))))

(deftest sync-progress-fn-writes-to-err-and-honors-json-suppression
  (is (nil? (#'cli/sync-progress-fn ["--json"])))
  (is (nil? (#'cli/sync-progress-fn ["--no-progress"])))
  (let [progress-fn (#'cli/sync-progress-fn [])
        writer (java.io.StringWriter.)]
    (binding [*err* writer]
      (progress-fn {:phase :scan-complete
                    :repo-id "app"
                    :files-scanned 2}))
    (let [out (str writer)]
      (is (str/includes? out "# Sync Progress"))
      (is (str/includes? out "- app scanned 2 files")))))
