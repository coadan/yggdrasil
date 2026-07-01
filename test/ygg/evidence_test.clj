(ns ygg.evidence-test
  (:require [ygg.activity :as activity]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.evidence :as evidence]
            [ygg.fs :as fs]
            [ygg.memory :as memory]
            [ygg.query :as query]
            [ygg.xtdb :as store]
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
                         :command "ygg sync project.edn --check"}
                        {:kind :docs
                         :command "ygg sync project.edn --query-index"}
                        {:kind :coverage
                         :command "ygg sync coverage project.edn --json"}]}
         (evidence/packet-freshness
          {:freshness {:status :stale
                       :counts {:changed 1}}
           :nextActions [{:kind :freshness
                          :command "ygg sync project.edn --check"}
                         {:kind :docs
                          :command "ygg sync project.edn --query-index"}
                         {:kind :systems
                          :command "ygg view systems --project fixture"}
                         {:kind :coverage
                          :command "ygg sync coverage project.edn --json"}
                         {:kind :query
                          :command "ygg query \"where is this handled?\" --project fixture --json"}]}))))

(deftest status-coverage-includes-compact-indexed-connectivity
  (is (= {:counts {:files 4
                   :skippedFiles 1
                   :diagnostics 0}
          :connectivity {:indexedFiles 4
                         :nodes 6
                         :edges 3
                         :connectedFiles 3
                         :crossFileConnectedFiles 2
                         :isolatedFiles 1
                         :byKind [{:kind "code"
                                   :indexedFiles 2}
                                  {:kind "doc"
                                   :indexedFiles 1}
                                  {:kind "java"
                                   :indexedFiles 1}
                                  {:kind "python"
                                   :indexedFiles 1}
                                  {:kind "ruby"
                                   :indexedFiles 1}]}}
         (evidence/status-coverage
          {:counts {:files 4
                    :skipped-files 1
                    :diagnostics 0}
           :indexedConnectivity {:indexedFiles 4
                                 :nodes 6
                                 :edges 3
                                 :connectedFiles 3
                                 :crossFileConnectedFiles 2
                                 :isolatedFiles 1
                                 :byKind [{:kind "code"
                                           :indexedFiles 2}
                                          {:kind "doc"
                                           :indexedFiles 1}
                                          {:kind "java"
                                           :indexedFiles 1}
                                          {:kind "python"
                                           :indexedFiles 1}
                                          {:kind "ruby"
                                           :indexedFiles 1}
                                          {:kind "typescript"
                                           :indexedFiles 1}]}}))))

(deftest status-coverage-keeps-bounded-diagnostic-samples
  (let [samples (mapv (fn [idx]
                        {:path (str "src/file" idx ".clj")
                         :stage "parse"
                         :message (str "diagnostic " idx)})
                      (range 7))
        coverage (evidence/status-coverage
                  {:counts {:files 7
                            :skipped-files 0
                            :diagnostics 7}
                   :diagnostics {:total 7
                                 :by-stage [{:stage "parse"
                                             :count 7}]
                                 :samples samples}})]
    (is (= {:total 7
            :by-stage [{:stage "parse"
                        :count 7}]
            :samples (vec (take 5 samples))}
           (:diagnostics coverage)))))

(deftest status-coverage-keeps-bounded-extractor-fingerprints
  (let [fingerprints (mapv (fn [idx]
                             {:kind "clojure"
                              :extractor-version "clojure/v1"
                              :extractor-fingerprint (str "extractor:" idx)
                              :files (inc idx)})
                           (range 7))
        coverage (evidence/status-coverage
                  {:counts {:files 7
                            :skipped-files 0
                            :diagnostics 0}
                   :extractor-fingerprints fingerprints})]
    (is (= (vec (take 5 fingerprints))
           (:extractorFingerprints coverage)))))

(deftest status-coverage-keeps-bounded-skipped-breakdowns
  (let [by-extension (mapv (fn [idx]
                             {:extension (str ".skip" idx)
                              :count (inc idx)})
                           (range 7))
        by-reason (mapv (fn [idx]
                          {:reason (str "reason-" idx)
                           :count (inc idx)})
                        (range 7))
        coverage (evidence/status-coverage
                  {:counts {:files 7
                            :skipped-files 7
                            :diagnostics 0}
                   :skipped-by-extension by-extension
                   :skipped-by-reason by-reason})]
    (is (= (vec (take 5 by-extension))
           (:skippedByExtension coverage)))
    (is (= (vec (take 5 by-reason))
           (:skippedByReason coverage)))))

(deftest summarize-exposes-dependency-evidence-plane
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 2}
                                             :files-by-kind []
                                             :extractors []
                                             :indexedConnectivity {:indexedFiles 1
                                                                   :nodes 0
                                                                   :edges 0
                                                                   :connectedFiles 0
                                                                   :crossFileConnectedFiles 0
                                                                   :isolatedFiles 1
                                                                   :byKind [{:kind "code"
                                                                             :indexedFiles 1
                                                                             :connectedFiles 0
                                                                             :crossFileConnectedFiles 0
                                                                             :isolatedFiles 1}]}
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
                                   :ygg/files [{:xt/id "file:app"
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
      (let [dependency-family (some #(when (= :dependencies (:family %)) %)
                                    (:families summary))]
        (is (= {:family :dependencies
                :status :weak
                :counts {:packages 2
                         :package-imports 1
                         :source-import-candidates 0
                         :package-evidence-gaps 1
                         :package-conflicts 0
                         :unresolved-imports 1}}
               (select-keys dependency-family [:family :status :counts])))
        (is (= [{:reason :candidate-unresolved
                 :count 1
                 :blocking true
                 :message "Source import candidates were extracted, but some did not resolve to package facts."}
                {:reason :package-without-import-evidence
                 :count 1
                 :blocking false
                 :message "Declared package facts exist without matching source import evidence."}]
               (:diagnostics dependency-family))))
      (is (= {:family :source-files
              :status :weak
              :counts {:files 1
                       :skipped-files 2
                       :diagnostics 0}}
             (some #(when (= :source-files (:family %)) %)
                   (:families summary))))
      (is (= 2 (get-in summary [:counts :packages])))
      (is (= 1 (get-in summary [:counts :package-imports])))
      (is (= {:indexedFiles 1
              :nodes 0
              :edges 0
              :connectedFiles 0
              :crossFileConnectedFiles 0
              :isolatedFiles 1
              :byKind [{:kind "code"
                        :indexedFiles 1
                        :connectedFiles 0
                        :crossFileConnectedFiles 0
                        :isolatedFiles 1}]}
             (:indexedConnectivity summary)))
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
      (is (some #{"ygg packages --project fixture --without-import-evidence --json"}
                (:next summary)))
      (is (some #{"ygg sync <project.edn> --check --enqueue"}
                (:next summary)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect packages without source import evidence"
                     :count 1
                     :command "ygg packages --project fixture --without-import-evidence --json"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :dependency-review
                     :label "Queue unresolved import review work"
                     :count 1
                     :command "ygg sync <project.edn> --check --enqueue"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :coverage
                     :label "Inspect skipped source candidates"
                     :count 2
                     :command "ygg sync coverage <project.edn> --json"
                     :mcpTool "ygg_status"
                     :pluginRegistryCommand "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>"
                     :pluginScaffoldCommand "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>"
                     :pluginGapCommand "bb plugin gap extractor <package-dir> <repo-root> <file> --json"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :audit-scope
                     :label "Inspect project audit scopes"
                     :command "ygg audit-scope <project.edn> --json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-exposes-memory-evidence-family
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
                store/all-rows (fn [& _] [])
                memory/counts (fn [_ scope]
                                (is (= "fixture" (:project-id scope)))
                                {:memories 2
                                 :memory-statuses {:reviewed 1
                                                   :suggested 1}
                                 :suggested-memories 1
                                 :observed-memories 0
                                 :reviewed-memories 1})
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
                                      {})
          memory-family (some #(when (= :memory (:family %)) %)
                              (:families summary))]
      (is (contains? (set (:available summary)) :memory))
      (is (= {:family :memory
              :status :available
              :counts {:memories 2
                       :suggested-memories 1
                       :observed-memories 0
                       :reviewed-memories 1}}
             memory-family))
      (is (= {:reviewed 1
              :suggested 1}
             (get-in summary [:counts :memory-statuses]))))))

(deftest summarize-marks-dependencies-not-applicable-without-package-inputs
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 0}
                                             :files-by-kind []
                                             :extractors []
                                             :indexedConnectivity {:indexedFiles 0
                                                                   :nodes 0
                                                                   :edges 0
                                                                   :connectedFiles 0
                                                                   :crossFileConnectedFiles 0
                                                                   :isolatedFiles 0
                                                                   :byKind []}
                                             :skipped-by-extension []
                                             :skipped-by-reason []
                                             :diagnostics {:total 0}})
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :versions 0
                                                      :imports-package 0
                                                      :source-import-candidates 0
                                                      :version-conflicts 0
                                                      :declared-without-import-evidence 0
                                                      :unresolved-imports 0}
                                             :ecosystems []})
                store/all-rows (fn [& _] [])
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
                                      {:config-path "project.edn"})]
      (is (= {:family :dependencies
              :status :not-applicable
              :counts {:packages 0
                       :package-imports 0
                       :source-import-candidates 0
                       :package-evidence-gaps 0
                       :package-conflicts 0
                       :unresolved-imports 0}}
             (some #(when (= :dependencies (:family %)) %)
                   (:families summary))))
      (is (some #(= {:kind :dependencies
                     :label "No package manifests or source package imports found"
                     :command "ygg sync coverage project.edn --json"}
                    %)
                (:nextActions summary))))))

(deftest dependency-advisory-diagnostics-do-not-make-family-weak
  (let [counts {:packages 2
                :package-imports 1
                :source-import-candidates 1
                :package-evidence-gaps 1
                :package-conflicts 1
                :unresolved-imports 0}]
    (is (false? (#'evidence/dependencies-weak? counts)))
    (is (= [{:reason :package-without-import-evidence
             :count 1
             :blocking false
             :message "Declared package facts exist without matching source import evidence."}
            {:reason :package-version-conflict
             :count 1
             :blocking false
             :message "Package version conflicts are present in indexed dependency facts."}]
           (#'evidence/dependency-diagnostics counts)))))

(deftest summarize-exposes-system-evidence-family-and-fact-kinds
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
                                   :ygg/files [{:xt/id "file:app"
                                                :project-id "fixture"
                                                :active? true}]
                                   :ygg/file-facts [{:xt/id "fact:url"
                                                     :project-id "fixture"
                                                     :repo-id "app"
                                                     :file-id "file:app"
                                                     :path "config/runtime.env"
                                                     :kind :url
                                                     :label "https://api.example.test"
                                                     :normalized-value "api-example-test"
                                                     :active? true}]
                                   :ygg/system-evidence [{:xt/id "evidence:database-url"
                                                          :project-id "fixture"
                                                          :repo-id "app"
                                                          :system-id "system:billing"
                                                          :file-id "file:app"
                                                          :path "config/runtime.env"
                                                          :kind :env-var
                                                          :label "DATABASE_URL"
                                                          :normalized-value "database-url"
                                                          :source-line 1
                                                          :confidence 1.0
                                                          :active? true
                                                          :run-id "run:system"}]
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
      (is (contains? (set (:available summary)) :file-facts))
      (is (contains? (set (:available summary)) :system-evidence))
      (is (contains? (set (:available summary)) :runtime-config))
      (is (= {:family :file-facts
              :status :available
              :counts {:file-facts 1}}
             (some #(when (= :file-facts (:family %)) %)
                   (:families summary))))
      (is (= {:family :runtime-config
              :status :available
              :counts {:runtime-config-evidence 1}}
             (some #(when (= :runtime-config (:family %)) %)
                   (:families summary))))
      (is (= {:family :system-evidence
              :status :available
              :counts {:system-evidence 1}}
             (some #(when (= :system-evidence (:family %)) %)
                   (:families summary))))
      (is (= [{:kind :url :count 1}]
             (get-in summary [:kinds :file-facts])))
      (is (= [{:kind :env-var :count 1}]
             (get-in summary [:kinds :system-evidence])))
      (is (= 1 (get-in summary [:counts :file-facts])))
      (is (= 1 (get-in summary [:counts :system-evidence])))
      (is (= 1 (get-in summary [:counts :runtime-config-evidence]))))))

(deftest summarize-next-actions-quote-shell-paths
  (with-redefs [coverage/project-coverage (fn [& _]
                                            {:totals {:skipped 0}
                                             :files-by-kind []
                                             :extractors []
                                             :skipped-by-extension []
                                             :skipped-by-reason []
                                             :diagnostics {:total 2}})
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
                                   :ygg/files []
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
                                      {:config-path "Project Files/project.edn"})]
      (is (some #(= "ygg sync 'Project Files/project.edn' --check"
                    (:command %))
                (:nextActions summary)))
      (is (some #(= {:kind :dependencies
                     :label "No package manifests or source package imports found"
                     :command "ygg sync coverage 'Project Files/project.edn' --json"}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :validation-history
                     :label "Run sync work validation loop"
                     :command "ygg sync 'Project Files/project.edn' --check --enqueue"
                     :commands ["ygg sync 'Project Files/project.edn' --check --enqueue"
                                "ygg sync work list --project 'fixture project' --status ready"
                                "ygg sync work pull --project 'fixture project' --agent <agent-id>"
                                "ygg sync work complete <work-id> --result result.json"
                                "ygg sync work validate <work-id>"
                                "ygg sync work apply <work-id>"
                                "ygg sync activity 'Project Files/project.edn' --json"]}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :system-evidence
                     :label "Inspect system evidence coverage"
                     :command "ygg sync coverage 'Project Files/project.edn' --json"
                     :mcpTool "ygg_status"
                     :mcpArgs {:configPath "Project Files/project.edn"}}
                    %)
                (:nextActions summary)))
      (is (some #(= {:kind :coverage
                     :label "Inspect extractor diagnostics"
                     :count 2
                     :command "ygg sync coverage 'Project Files/project.edn' --json"
                     :mcpTool "ygg_status"
                     :mcpArgs {:configPath "Project Files/project.edn"}}
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
                                   :ygg/files [{:xt/id "file:app"
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
                                      {:config-path "Project Files/project.edn"})]
      (is (some #(= {:kind :audit-scope
                     :label "Inspect project audit scopes"
                     :command "ygg audit-scope 'Project Files/project.edn' --json"}
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
                                   :ygg/files [{:xt/id "file:app"
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
      (is (= {:family :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-status-items 0
                       :result-schema-matching-items 0
                       :result-schema-mismatch-items 0
                       :result-schema-missing-result-items 0
                       :result-schema-unexpected-result-items 0
                       :result-schema-mismatch-events 1}}
             (some #(when (= :validation-history (:family %)) %)
                   (:families summary))))
      (is (= 1 (get-in summary [:counts :result-schema-mismatch-events])))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema mismatch activity"
                     :count 1
                     :mcpTool "ygg_sync_activity"
                     :command "ygg sync activity <project.edn> --json"}
                    %)
                (:nextActions summary))))))

(deftest summarize-counts-result-schema-status-activity
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
                                   :ygg/files [{:xt/id "file:app"
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
                activity/all-items (fn [& _]
                                     [{:xt/id "work:ok"
                                       :expected-result-schema "ygg.result/v1"
                                       :result-schema "ygg.result/v1"}])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (contains? (set (:available summary)) :validation-history))
      (is (= {:family :validation-history
              :status :available
              :counts {:validation-events 0
                       :result-schema-status-items 1
                       :result-schema-matching-items 1
                       :result-schema-mismatch-items 0
                       :result-schema-missing-result-items 0
                       :result-schema-unexpected-result-items 0
                       :result-schema-mismatch-events 0}}
             (some #(when (= :validation-history (:family %)) %)
                   (:families summary))))
      (is (= {:matching 1}
             (get-in summary [:counts :result-schema-statuses])))
      (is (= 1 (get-in summary [:counts :result-schema-status-items])))
      (is (not (some #(= "ygg sync activity <project.edn> --json" (:command %))
                     (:nextActions summary)))))))

(deftest summarize-actions-result-schema-status-gaps
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
                                   :ygg/files [{:xt/id "file:app"
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
                activity/all-items (fn [& _]
                                     [{:xt/id "work:missing"
                                       :expected-result-schema "ygg.result/v1"}])
                activity/all-events (fn [& _] [])]
    (let [summary (evidence/summarize :xtdb
                                      {:id "fixture"
                                       :repos []}
                                      {})]
      (is (= {:family :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-status-items 1
                       :result-schema-matching-items 0
                       :result-schema-mismatch-items 0
                       :result-schema-missing-result-items 1
                       :result-schema-unexpected-result-items 0
                       :result-schema-mismatch-events 0}}
             (some #(when (= :validation-history (:family %)) %)
                   (:families summary))))
      (is (= {:missing-result 1}
             (get-in summary [:counts :result-schema-statuses])))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema status activity"
                     :count 1
                     :mcpTool "ygg_sync_activity"
                     :command "ygg sync activity <project.edn> --json"}
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
                                   :ygg/files [{:xt/id "file:changed"
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
                                   :ygg/source-snapshots [{:xt/id "snapshot:fixture:app:head"
                                                           :project-id "fixture"
                                                           :repo-id "app"
                                                           :basis-instant #inst "2026-01-01T00:00:00.000Z"
                                                           :git-branch "main"
                                                           :git-upstream "origin/main"
                                                           :git-upstream-sha "sha-upstream"
                                                           :git-upstream-current? false
                                                           :git-upstream-status :behind
                                                           :git-ahead 0
                                                           :git-behind 2}]
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
                                      {:config-path "project.edn"})]
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
      (is (= {:git-branch "main"
              :git-upstream "origin/main"
              :git-upstream-sha "sha-upstream"
              :git-upstream-current? false
              :git-upstream-status :behind
              :git-ahead 0
              :git-behind 2}
             (get-in summary [:freshness :repos 0 :git-state])))
      (is (some #(= {:kind :freshness
                     :label "Refresh indexed graph basis"
                     :count 3
                     :command "ygg sync project.edn --check"}
                    %)
                (:nextActions summary))))))

(deftest summarize-marks-current-graph-without-query-index-partial
  (let [root (temp-dir "ygg-freshness-query-index")]
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
                                     :ygg/files [{:xt/id "file:app"
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
                                         :correction-overlay {:systems [{:id "system:fixture:app"}]}})]
        (is (= :partial (get-in summary [:freshness :status])))
        (is (= "indexed-graph" (get-in summary [:freshness :basis])))
        (is (= true (get-in summary [:freshness :missingQueryIndex])))
        (is (= "project.edn" (get-in summary [:freshness :projectConfig])))
        (is (= {:family :corrections
                :status :available
                :counts {:systems 1
                         :docs 0
                         :edges 0
                         :rejects 0
                         :package-imports 0}}
               (some #(when (= :corrections (:family %)) %)
                     (:families summary))))
        (is (not-any? #(= :corrections (:kind %))
                      (:nextActions summary)))
        (is (some #(= {:kind :docs
                       :label "Build query index"
                       :command "ygg sync project.edn --query-index"}
                      %)
                  (:nextActions summary)))))))
