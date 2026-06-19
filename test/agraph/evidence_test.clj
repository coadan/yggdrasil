(ns agraph.evidence-test
  (:require [agraph.activity :as activity]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.evidence :as evidence]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(deftest summarize-exposes-dependency-evidence-plane
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 0}
                                             :files-by-kind []
                                             :extractors []
                                             :skipped-by-extension [{:ext ".wasm"
                                                                     :count 2
                                                                     :samples [{:repo-id "app"
                                                                                :path "web/app.wasm"}]}]
                                             :skipped-by-reason []
                                             :diagnostics {:total 0}})
                dependency/package-report (fn [& _]
                                            {:counts {:packages 2
                                                      :versions 3
                                                      :imports-package 1
                                                      :version-conflicts 0
                                                      :declared-without-import-evidence 1
                                                      :unresolved-imports 1}
                                             :ecosystems [{:ecosystem :npm
                                                           :packages 2
                                                           :versions 3
                                                           :imports 1}]})
                store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :project-id "fixture"
                                                   :active? true}]
                                   []))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (= evidence/schema (:schema summary)))
      (is (contains? (set (:available summary)) :dependencies))
      (is (not (contains? (set (:available summary)) :packages)))
      (is (= 2 (get-in summary [:counts :packages])))
      (is (= 1 (get-in summary [:counts :package-imports])))
      (is (= {:packages 2
              :versions 3
              :imports-package 1
              :version-conflicts 0
              :declared-without-import-evidence 1
              :unresolved-imports 1}
             (get-in summary [:packages :counts])))
      (is (= [{:ext ".wasm"
               :count 2
               :samples [{:repo-id "app"
                          :path "web/app.wasm"}]}]
             (:skipped-by-extension summary)))
      (is (some #{"agraph packages --project fixture --without-import-evidence --json"}
                (:next summary)))
      (is (some #{"agraph sync check <project.edn> --enqueue"}
                (:next summary)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect packages without source import evidence"
                     :count 1
                     :command "agraph packages --project fixture --without-import-evidence --json"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :dependency-review
                     :label "Queue unresolved import review work"
                     :count 1
                     :command "agraph sync check <project.edn> --enqueue"}
                    %)
                (:nextActions summary))))))

(deftest summarize-next-actions-quote-shell-paths
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 0}
                                             :files-by-kind []
                                             :extractors []
                                             :skipped-by-extension []
                                             :skipped-by-reason []
                                             :diagnostics {:total 0}})
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :versions 0
                                                      :imports-package 0
                                                      :version-conflicts 0
                                                      :declared-without-import-evidence 0
                                                      :unresolved-imports 0}
                                             :ecosystems []})
                store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files []
                                   []))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture project"
                                       :repos []}
                                      {:config-path "Project Files/project.edn"
                                       :map-path "Maps/agraph map.json"})]
      (is (some #(= "agraph sync 'Project Files/project.edn' --check --map 'Maps/agraph map.json'"
                    (:command %))
                (:nextActions summary)))
      (is (some #(= "agraph packages --project 'fixture project' --json"
                    (:command %))
                (:nextActions summary))))))

(deftest summarize-surfaces-result-schema-mismatch-activity
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 0}
                                             :files-by-kind []
                                             :extractors []
                                             :skipped-by-extension []
                                             :skipped-by-reason []
                                             :diagnostics {:total 0}})
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :versions 0
                                                      :imports-package 0
                                                      :version-conflicts 0
                                                      :declared-without-import-evidence 0
                                                      :unresolved-imports 0}
                                             :ecosystems []})
                store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :project-id "fixture"
                                                   :active? true}]
                                   []))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _]
                                      [{:event-kind :result-schema-mismatch
                                        :work-id "work-1"}])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (contains? (set (:available summary)) :validation-history))
      (is (= 1 (get-in summary [:counts :result-schema-mismatch-events])))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema mismatch activity"
                     :count 1
                     :command "agraph sync activity <project.edn> --json"}
                    %)
                (:nextActions summary))))))
