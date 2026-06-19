(ns agraph.evidence-test
  (:require [agraph.activity :as activity]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.evidence :as evidence]
            [agraph.fs :as fs]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      prefix
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (.getPath dir)))

(deftest packet-freshness-keeps-basis-repair-actions
  (is (= {:status :stale
          :counts {:changed 1}
          :nextActions [{:kind :freshness
                         :command "agraph sync project.edn --check"}
                        {:kind :docs
                         :command "agraph sync project.edn --query-index"}
                        {:kind :coverage
                         :command "agraph sync coverage project.edn --json"}]}
         (evidence/packet-freshness
          {:freshness {:status :stale
                       :counts {:changed 1}}
           :nextActions [{:kind :freshness
                          :command "agraph sync project.edn --check"}
                         {:kind :docs
                          :command "agraph sync project.edn --query-index"}
                         {:kind :systems
                          :command "agraph view systems --project fixture"}
                         {:kind :coverage
                          :command "agraph sync coverage project.edn --json"}
                         {:kind :ask
                          :command "agraph ask \"where is this handled?\" --project fixture --json"}]}))))

(deftest summarize-exposes-dependency-evidence-plane
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 2}
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
                query/all-system-evidence (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (= evidence/schema (:schema summary)))
      (is (contains? (set (:available summary)) :dependencies))
      (is (not (contains? (set (:available summary)) :packages)))
      (is (= {:plane :dependencies
              :status :weak
              :counts {:packages 2
                       :package-imports 1
                       :package-evidence-gaps 1
                       :package-conflicts 0
                       :unresolved-imports 1}}
             (some #(when (= :dependencies (:plane %)) %)
                   (:planes summary))))
      (is (= {:plane :source-files
              :status :weak
              :counts {:files 1
                       :skipped-files 2
                       :diagnostics 0}}
             (some #(when (= :source-files (:plane %)) %)
                   (:planes summary))))
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
                (:nextActions summary)))
      (is (some #(= {:kind :coverage
                     :label "Inspect skipped source candidates"
                     :count 2
                     :command "agraph sync coverage <project.edn> --json"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :audit-scope
                     :label "Inspect project audit scopes"
                     :command "agraph audit-scope <project.edn> --json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-exposes-runtime-config-evidence-plane
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
                query/all-system-evidence (fn [& _]
                                            [{:xt/id "evidence:database-url"
                                              :project-id "fixture"
                                              :repo-id "app"
                                              :system-id "system:billing"
                                              :path "config/runtime.env"
                                              :kind :env-var
                                              :label "DATABASE_URL"
                                              :normalized-value "database-url"
                                              :active? true}])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (contains? (set (:available summary)) :runtime-config))
      (is (= {:plane :runtime-config
              :status :available
              :counts {:system-evidence 1}}
             (some #(when (= :runtime-config (:plane %)) %)
                   (:planes summary))))
      (is (= 1 (get-in summary [:counts :system-evidence]))))))

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
                query/all-system-evidence (fn [& _] [])
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
                (:nextActions summary)))
      (is (some #(= {:kind :runtime-config
                     :label "Inspect runtime/config evidence coverage"
                     :command "agraph sync coverage 'Project Files/project.edn' --json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-audit-scope-next-action-quotes-shell-paths
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
                                                   :project-id "fixture project"
                                                   :active? true}]
                                   []))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture project"
                                       :repos []}
                                      {:config-path "Project Files/project.edn"
                                       :map-path "Maps/agraph map.json"})]
      (is (some #(= {:kind :audit-scope
                     :label "Inspect project audit scopes"
                     :command "agraph audit-scope 'Project Files/project.edn' --map 'Maps/agraph map.json' --json"}
                    %)
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
                query/all-system-evidence (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _]
                                      [{:event-kind :result-schema-mismatch
                                        :work-id "work-1"}])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (contains? (set (:available summary)) :validation-history))
      (is (= {:plane :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-mismatch-events 1}}
             (some #(when (= :validation-history (:plane %)) %)
                   (:planes summary))))
      (is (= 1 (get-in summary [:counts :result-schema-mismatch-events])))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema mismatch activity"
                     :count 1
                     :mcpTool "agraph_sync_activity"
                     :command "agraph sync activity <project.edn> --json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-reports-mechanical-freshness
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
                fs/scan-files (fn [root]
                                (is (= "/repo" root))
                                [{:path "src/changed.clj"
                                  :content-sha "sha256:new"}
                                 {:path "src/new.clj"
                                  :content-sha "sha256:new-file"}])
                store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:changed"
                                                   :project-id "fixture"
                                                   :repo-id "app"
                                                   :path "src/changed.clj"
                                                   :content-sha "sha256:old"
                                                   :active? true}
                                                  {:xt/id "file:deleted"
                                                   :project-id "fixture"
                                                   :repo-id "app"
                                                   :path "src/deleted.clj"
                                                   :content-sha "sha256:deleted"
                                                   :active? true}]
                                   []))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos [{:id "app"
                                                :root "/repo"}]}
                                      {:config-path "project.edn"
                                       :map-path "agraph.map.json"})]
      (is (= :stale (get-in summary [:freshness :status])))
      (is (= {:indexed 2
              :current 2
              :changed 1
              :missing 1
              :unindexed 1}
             (get-in summary [:freshness :counts])))
      (is (= [{:repo-id "app"
               :path "src/changed.clj"}]
             (get-in summary [:freshness :repos 0 :samples :changed])))
      (is (= [{:repo-id "app"
               :path "src/deleted.clj"}]
             (get-in summary [:freshness :repos 0 :samples :missing])))
      (is (= [{:repo-id "app"
               :path "src/new.clj"}]
             (get-in summary [:freshness :repos 0 :samples :unindexed])))
      (is (some #(= {:kind :freshness
                     :label "Refresh indexed graph basis"
                     :count 3
                     :command "agraph sync project.edn --check --map agraph.map.json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-marks-current-graph-without-query-index-partial
  (let [root (temp-dir "agraph-freshness-query-index")
        map-path (.getPath (io/file root "agraph.map.json"))]
    (spit map-path "{}\n")
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
                  fs/scan-files (fn [scan-root]
                                  (is (= root scan-root))
                                  [{:path "src/app.clj"
                                    :content-sha "sha256:app"}])
                  store/all-rows (fn [_ table _]
                                   (case table
                                     :agraph/files [{:xt/id "file:app"
                                                     :project-id "fixture"
                                                     :repo-id "app"
                                                     :path "src/app.clj"
                                                     :content-sha "sha256:app"
                                                     :active? true}]
                                     []))
                  query/all-nodes (fn [& _] [])
                  query/all-edges (fn [& _] [])
                  query/all-chunks (fn [& _] [])
                  query/all-search-docs (fn [& _] [])
                  query/all-embeddings (fn [& _] [])
                  query/all-system-nodes (fn [& _] [])
                  query/all-system-edges (fn [& _] [])
                  query/all-system-evidence (fn [& _] [])
                  activity/all-items (fn [& _] [])
                  activity/all-events (fn [& _] [])]
      (let [summary (evidence/summarize :xtdb
                                        {:id "fixture"
                                         :repos [{:id "app"
                                                  :root root}]}
                                        {:config-path "project.edn"
                                         :map-path map-path})]
        (is (= :partial (get-in summary [:freshness :status])))
        (is (= "indexed-graph" (get-in summary [:freshness :basis])))
        (is (= true (get-in summary [:freshness :missingQueryIndex])))
        (is (= "project.edn" (get-in summary [:freshness :projectConfig])))
        (is (= map-path (get-in summary [:freshness :map])))
        (is (= true (get-in summary [:freshness :mapExists])))
        (is (some #(= {:kind :docs
                       :label "Build query index"
                       :command "agraph sync project.edn --query-index"}
                      %)
                  (:nextActions summary)))))))
