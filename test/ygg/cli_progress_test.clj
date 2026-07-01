(ns ygg.cli-progress-test
  (:require [ygg.cli-project :as cli-project]
            [ygg.cli-sync :as cli]
            [ygg.progress :as progress]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest sync-progress-line-renders-human-counts
  (is (= "app plan 1 changed file, 2 reused unchanged files, 0 deleted files"
         (progress/sync-progress-message
          {:phase :plan-complete
           :repo-id "app"
           :files-changed 1
           :files-reused 2
           :files-deleted 0})))
  (is (= "- app plan 1 changed file, 2 reused unchanged files, 0 deleted files"
         (progress/sync-progress-line
          {:phase :plan-complete
           :repo-id "app"
           :files-changed 1
           :files-reused 2
           :files-deleted 0})))
  (is (= "- app plan 1 changed file, 2 reused unchanged files, 0 deleted files"
         (progress/sync-progress-line
          {:phase :plan-complete
           :repo-id "app"
           :files-changed 1
           :files-skipped 2
           :files-deleted 0})))
  (is (= "- app committed 3 files, 4 chunks, 5 search docs, 1 diagnostic"
         (progress/sync-progress-line
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

(deftest sync-summaries-render-reused-unchanged-files
  (let [summary {:project-id "fixture"
                 :status :completed
                 :repos [{:repo-id "app"
                          :status :completed
                          :index-profile :query
                          :stats {:files-scanned 3
                                  :files-indexed 1
                                  :files-reused 2
                                  :files-skipped 2}
                          :git-state {:git-branch "main"
                                      :git-upstream "origin/main"
                                      :git-upstream-current? false
                                      :git-upstream-status :behind
                                      :git-ahead 0
                                      :git-behind 3}}]}]
    (is (str/includes? (with-out-str
                         (cli-project/print-project-index-summary summary))
                       "app completed profile=query 3 scanned, 1 indexed, 2 reused unchanged"))
    (is (str/includes? (with-out-str
                         (cli-project/print-project-index-summary summary))
                       "branch=main upstream=origin/main upstream-status=behind upstream-current=false ahead=0 behind=3"))
    (is (str/includes? (with-out-str
                         (cli-project/print-sync-summary
                          {:project-id "fixture"
                           :index-summary summary}))
                       "app completed profile=query 3 scanned, 1 indexed, 2 reused unchanged"))
    (is (str/includes? (with-out-str
                         (cli-project/print-sync-summary
                          {:project-id "fixture"
                           :index-summary summary}))
                       "branch=main upstream=origin/main upstream-status=behind upstream-current=false ahead=0 behind=3"))))
