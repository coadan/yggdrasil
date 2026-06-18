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
                                             :skipped-by-reason []
                                             :diagnostics {:total 0}})
                dependency/package-report (fn [& _]
                                            {:counts {:packages 2
                                                      :versions 3
                                                      :imports-package 1
                                                      :version-conflicts 0
                                                      :declared-without-import-evidence 1
                                                      :unresolved-imports 0}
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
              :unresolved-imports 0}
             (get-in summary [:packages :counts])))
      (is (some #{"agraph packages --project fixture --without-import-evidence --json"}
                (:next summary))))))
