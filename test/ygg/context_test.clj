(ns ygg.context-test
  (:require [ygg.context :as context]
            [ygg.context-architecture :as context-architecture]
            [ygg.activity :as activity]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.graph :as graph]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(defn- empty-dependency-report
  []
  {:schema "ygg.dependency.report/v1"
   :counts {:packages 0
            :versions 0
            :requires 0
            :resolves 0
            :imports-package 0
            :unresolved-imports 0
            :declared-without-import-evidence 0
            :version-conflicts 0}
   :packages []
   :declared-without-import-evidence []
   :unresolved-imports []
   :version-conflicts []})

(def plugin-package-fixture
  {:id "datastar-hiccup"
   :version "0.1.0"
   :path ".dev/ygg/plugins/cache/datastar"
   :source {:type :git
            :url "https://example.test/datastar.git"
            :ref "v0.1.0"
            :rev "abc123"
            :subdir "packages/datastar"
            :extra "drop"}
   :visibility :public
   :scope {:kind :base}
   :benchmark-status :unbenchmarked
   :benchmark-cases {:artifacts 1
                     :case-ids ["datastar-hiccup-architecture"]}
   :claim-authority {:status :non-authoritative}
   :manifest-fingerprint "sha256:manifest"
   :expected-package-id "datastar-hiccup"
   :expected-manifest-fingerprint "sha256:manifest"
   :diagnostic-counts {:total 1
                       :errors 0
                       :warnings 1}
   :warnings ["datastar-hiccup is unbenchmarked"]})

(def compact-plugin-package-fixture
  (update plugin-package-fixture
          :source
          select-keys
          [:type :url :rev :ref :subdir :path]))

(deftest inferred-docs-include-source-chunk-for-retrieved-node-result
  (let [inferred-docs @#'context/inferred-docs
        docs (inferred-docs
              ["target"]
              [{:target-id "node:target"
                :target-kind :node
                :path "src/Target.java"
                :label "demo/Target"
                :score 1.0
                :score-components {:exact 0.0}}]
              [{:xt/id "chunk:target"
                :path "src/Target.java"
                :kind :code-definition
                :label "demo/Target"
                :text "class Target {}"
                :tokens ["demo/target" "target"]
                :source-line 1}]
              []
              900)]
    (is (= ["src/Target.java"] (mapv #(get-in % [:source :path]) docs)))
    (is (true? (:retrievedSource (first docs))))))

(deftest diversify-docs-promotes-distinct-source-files
  (let [diversify-docs @#'context/diversify-docs
        docs (diversify-docs
              [{:target "chunk:a1"
                :score 1.0
                :retrievedSource true
                :source {:repo "app"
                         :path "src/A.java"
                         :definitionKind :class}}
               {:target "chunk:a2"
                :score 0.9
                :retrievedSource true
                :source {:repo "app"
                         :path "src/A.java"
                         :definitionKind :method}}
               {:target "chunk:b1"
                :score 0.5
                :retrievedSource true
                :source {:repo "app"
                         :path "src/B.java"
                         :definitionKind :class}}])]
    (is (= ["src/A.java" "src/B.java" "src/A.java"]
           (mapv #(get-in % [:source :path]) docs)))))

(deftest diversify-docs-prefers-query-matched-definition-kind-within-new-root
  (let [diversify-docs @#'context/diversify-docs
        docs (diversify-docs
              (text/tokenize "component interface")
              [{:target "chunk:connector"
                :score 5.0
                :retrievedSource true
                :source {:repo "app"
                         :path "connector/connector.go"
                         :definitionKind :function}}
               {:target "chunk:component-test"
                :score 4.0
                :retrievedSource true
                :source {:repo "app"
                         :path "component/package_test.go"
                         :definitionKind :test}}
               {:target "chunk:component-interface"
                :score 3.4
                :retrievedSource true
                :source {:repo "app"
                         :path "component/component.go"
                         :definitionKind :interface}}])]
    (is (= ["component/component.go"
            "connector/connector.go"
            "component/package_test.go"]
           (mapv #(get-in % [:source :path]) docs)))))

(deftest diversify-docs-promotes-query-matched-definition-kind-within-seen-root
  (let [diversify-docs @#'context/diversify-docs
        docs (diversify-docs
              (text/tokenize "consumer type")
              [{:target "chunk:consumer-interface"
                :score 5.0
                :retrievedSource true
                :source {:repo "app"
                         :path "consumer/logs.go"
                         :definitionKind :interface}}
               {:target "chunk:component-type"
                :score 4.0
                :retrievedSource true
                :source {:repo "app"
                         :path "component/component.go"
                         :definitionKind :type}}
               {:target "chunk:consumer-type"
                :score 3.6
                :retrievedSource true
                :source {:repo "app"
                         :path "consumer/consumer.go"
                         :definitionKind :type}}
               {:target "chunk:other"
                :score 3.5
                :retrievedSource true
                :source {:repo "app"
                         :path "other/package_test.go"
                         :definitionKind :test}}])]
    (is (= ["component/component.go"
            "consumer/consumer.go"
            "other/package_test.go"
            "consumer/logs.go"]
           (mapv #(get-in % [:source :path]) docs)))))

(deftest select-docs-preserves-top-retrieved-path-coverage
  (let [select-docs @#'context/select-docs
        crowded-docs (for [idx (range 20)]
                       {:target (str "chunk:p" idx)
                        :role "reference"
                        :status "candidate"
                        :source {:repo "app"
                                 :path (str "p" idx ".clj")
                                 :definitionKind :fn}
                        :score (- 1.0 (* idx 0.01))
                        :snippet (str "p" idx)
                        :provenance "retrieved-doc"
                        :retrievedSource true})
        important-doc {:target "chunk:important"
                       :role "reference"
                       :status "candidate"
                       :source {:repo "app"
                                :path "important.clj"
                                :definitionKind :fn}
                       :score 0.01
                       :snippet "important"
                       :provenance "retrieved-doc"
                       :retrievedSource true}
        results (concat
                 (for [idx (range 8)]
                   {:path (str "p" idx ".clj")
                    :target-id (str "chunk:p" idx)
                    :target-kind :chunk
                    :score (- 1.0 (* idx 0.01))})
                 [{:path "important.clj"
                   :target-id "chunk:important"
                   :target-kind :chunk
                   :score 0.01}])
        selected (select-docs (concat crowded-docs [important-doc])
                              results
                              20)]
    (is (= 20 (count selected)))
    (is (some #{"important.clj"}
              (map #(get-in % [:source :path]) selected)))))

(deftest evidence-warns-when-indexer-diagnostics-exist
  (let [warnings (#'context/evidence-warnings
                  {:search-docs 1
                   :diagnostics 2
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 0
                   :validation-events 1
                   :embeddings 1}
                  {:requested :lexical
                   :effective :lexical
                   :fallback? false}
                  [])]
    (is (some #{"Indexer diagnostics are present; inspect source coverage before relying on missing facts."}
              warnings))))

(deftest evidence-surfaces-skipped-source-files-as-weak-coverage
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        counts {:files 4
                :skipped-files 3
                :nodes 2
                :edges 1
                :search-docs 2
                :external-packages 1
                :package-import-edges 1
                :unresolved-imports 0
                :package-evidence-gaps 0
                :package-conflicts 0
                :system-evidence 1
                :system-nodes 1
                :system-edges 1
                :activity-items 1
                :activity-events 1
                :validation-events 1
                :embeddings 1
                :diagnostics 0}
        match-counts {:entity-count 1
                      :doc-count 1
                      :activity-count 1
                      :validation-count 1
                      :runtime-count 1}
        weak (#'context/weak-planes counts match-counts)
        warnings (#'context/evidence-warnings counts retrieval weak)
        actions (#'context/next-actions counts retrieval "fixture")]
    (is (= [:source-files] weak))
    (is (some #{"Some files were skipped by the latest index run; inspect source coverage before treating missing facts as absent."}
              warnings))
    (is (some #(= {:kind :coverage
                   :label "Inspect skipped source candidates"
                   :count 3
                   :command "ygg sync coverage <project.edn> --json"
                   :pluginRegistryCommand "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>"
                   :pluginScaffoldCommand "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>"
                   :pluginGapCommand "bb plugin gap extractor <package-dir> <repo-root> <file> --json"
                   :mcpTool "ygg_status"}
                  %)
              actions))))

(deftest evidence-warns-when-result-schema-mismatch-activity-exists
  (let [warnings (#'context/evidence-warnings
                  {:files 1
                   :nodes 1
                   :edges 1
                   :search-docs 1
                   :diagnostics 0
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 2
                   :validation-events 1
                   :result-schema-mismatch-events 1
                   :embeddings 1}
                  {:requested :lexical
                   :effective :lexical
                   :fallback? false}
                  [])]
    (is (some #{"Completed work has result schema mismatches; inspect activity before trusting prior results."}
              warnings))))

(deftest evidence-warns-when-source-plane-is-empty
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        no-files (#'context/evidence-warnings
                  {:files 0
                   :nodes 0
                   :edges 0
                   :search-docs 1
                   :diagnostics 0
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 0
                   :validation-events 1
                   :embeddings 1}
                  retrieval
                  [])
        no-graph (#'context/evidence-warnings
                  {:files 2
                   :nodes 0
                   :edges 0
                   :search-docs 1
                   :diagnostics 0
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 0
                   :validation-events 1
                   :embeddings 1}
                  retrieval
                  [])]
    (is (some #{"No source files are indexed for this project."} no-files))
    (is (some #{"Source files are indexed, but no source graph rows are indexed."}
              no-graph))))

(deftest evidence-next-steps-distinguish-source-file-and-graph-gaps
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        commands (fn [counts]
                   (mapv :command
                         (#'context/next-actions counts retrieval "fixture")))
        no-files (commands
                  {:files 0
                   :nodes 0
                   :edges 0
                   :search-docs 1
                   :external-packages 1
                   :package-import-edges 1
                   :unresolved-imports 0
                   :package-evidence-gaps 0
                   :package-conflicts 0
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 0
                   :diagnostics 0})
        no-graph (commands
                  {:files 2
                   :nodes 0
                   :edges 0
                   :search-docs 1
                   :external-packages 1
                   :package-import-edges 1
                   :unresolved-imports 0
                   :package-evidence-gaps 0
                   :package-conflicts 0
                   :system-nodes 1
                   :system-edges 0
                   :activity-items 1
                   :activity-events 0
                   :diagnostics 0})]
    (is (some #{"ygg sync <project.edn>"} no-files))
    (is (not (some #{"ygg sync <project.edn> --check"} no-files)))
    (is (some #{"ygg sync <project.edn> --check"} no-graph))))

(deftest evidence-next-actions-quote-shell-sensitive-project-id
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        actions (#'context/next-actions
                 {:files 1
                  :nodes 1
                  :edges 0
                  :search-docs 1
                  :external-packages 0
                  :package-import-edges 0
                  :unresolved-imports 1
                  :package-evidence-gaps 0
                  :package-conflicts 0
                  :system-nodes 1
                  :system-edges 0
                  :activity-items 1
                  :activity-events 0
                  :diagnostics 0}
                 retrieval
                 "fixture project")]
    (is (some #(= "ygg packages --project 'fixture project' --json"
                  (:command %))
              actions))))

(deftest context-drilldowns-use-primary-agent-commands
  (is (= [{:kind :explore
           :label "Continue primary graph exploration"
           :command "ygg explore 'billing flow' --project 'fixture project' --json"
           :mcpTool "ygg_explore"
           :mcpArgs {:query "billing flow"
                     :projectId "fixture project"
                     :mapPath "maps/ygg map.json"}}
          {:kind :systems
           :label "Inspect project systems graph"
           :command "ygg view systems --project 'fixture project'"
           :mcpTool "ygg_systems"
           :mcpArgs {:projectId "fixture project"
                     :mapPath "maps/ygg map.json"}}
          {:kind :status
           :label "Inspect graph freshness and evidence status"
           :command "ygg sync inspect <project.edn> --map 'maps/ygg map.json' --json"
           :mcpTool "ygg_status"
           :mcpArgs {:mapPath "maps/ygg map.json"}}
          {:kind :docs
           :label "Audit accepted documentation attachments"
           :command "ygg sync docs audit --project 'fixture project' --map 'maps/ygg map.json'"}]
         (#'context/context-drilldowns
          "billing flow"
          "fixture project"
          "maps/ygg map.json"))))

(deftest evidence-next-actions-keep-coverage-when-capped
  (let [actions (#'context/next-actions
                 {:files 0
                  :nodes 0
                  :edges 0
                  :search-docs 0
                  :external-packages 0
                  :package-import-edges 0
                  :unresolved-imports 0
                  :package-evidence-gaps 0
                  :package-conflicts 0
                  :system-nodes 0
                  :system-edges 0
                  :activity-items 0
                  :activity-events 0
                  :diagnostics 3}
                 {:requested :auto
                  :effective :lexical
                  :fallback? true}
                 "fixture")]
    (is (= 5 (count actions)))
    (is (some #(= {:kind :coverage
                   :label "Inspect extractor diagnostics"
                   :count 3
                   :command "ygg sync coverage <project.edn> --json"
                   :mcpTool "ygg_status"}
                  %)
              actions))))

(deftest evidence-coverage-actions-include-status-mcp-when-map-path-is-known
  (let [actions (#'context/next-actions
                 {:files 1
                  :nodes 1
                  :edges 1
                  :search-docs 1
                  :external-packages 1
                  :package-import-edges 1
                  :unresolved-imports 0
                  :package-evidence-gaps 0
                  :package-conflicts 0
                  :system-nodes 1
                  :system-edges 1
                  :activity-items 1
                  :activity-events 1
                  :diagnostics 2
                  :skipped-files 3}
                 {:requested :lexical
                  :effective :lexical
                  :fallback? false}
                 "fixture"
                 nil
                 "ygg.map.json")]
    (is (= [{:kind :coverage
             :label "Inspect extractor diagnostics"
             :count 2
             :command "ygg sync coverage <project.edn> --json"
             :mcpTool "ygg_status"
             :mcpArgs {:mapPath "ygg.map.json"}}
            {:kind :coverage
             :label "Inspect skipped source candidates"
             :count 3
             :command "ygg sync coverage <project.edn> --json"
             :pluginRegistryCommand "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>"
             :pluginScaffoldCommand "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>"
             :pluginGapCommand "bb plugin gap extractor <package-dir> <repo-root> <file> --json"
             :mcpTool "ygg_status"
             :mcpArgs {:mapPath "ygg.map.json"}}]
           (filterv #(= :coverage (:kind %)) actions)))))

(deftest evidence-surfaces-stale-freshness
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        freshness {:status :stale
                   :nextActions [{:kind :freshness
                                  :label "Refresh indexed graph basis"
                                  :count 2
                                  :command "ygg sync project.edn --check --map ygg.map.json"}]}
        counts {:files 1
                :nodes 1
                :edges 1
                :search-docs 1
                :external-packages 1
                :package-import-edges 1
                :unresolved-imports 0
                :package-evidence-gaps 0
                :package-conflicts 0
                :system-nodes 1
                :system-edges 1
                :activity-items 1
                :activity-events 1
                :diagnostics 0
                :embeddings 1}
        warnings (#'context/evidence-warnings counts retrieval [] freshness)
        actions (#'context/next-actions counts retrieval "fixture" freshness)]
    (is (= :limited
           (#'context/evidence-readiness-status
            []
            []
            retrieval
            {:entity-count 1
             :doc-count 1
             :activity-count 1}
            freshness)))
    (is (some #{"Graph basis is stale; follow freshness next actions before trusting absence of evidence."}
              warnings))
    (is (= (:nextActions freshness)
           (filterv #(= :freshness (:kind %)) actions)))))

(deftest evidence-surfaces-skipped-index-run-files
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :project-id "fixture"
                                                :repo-id "app"
                                                :active? true}]
                                   :ygg/index-runs [{:xt/id "run:app"
                                                     :project-id "fixture"
                                                     :repo-id "app"
                                                     :active? true
                                                     :status :completed
                                                     :finished-at-ms 100
                                                     :stats {:files-skipped 3}}]
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}
                                   {:xt/id "package:npm:react"
                                    :kind :external-package
                                    :active? true}])
                query/all-edges (fn [& _]
                                  [{:xt/id "edge:src.app-react"
                                    :relation :imports-package
                                    :active? true}])
                query/all-chunks (fn [& _]
                                   [{:xt/id "chunk:app"
                                     :active? true}])
                query/all-search-docs (fn [& _]
                                        [{:xt/id "search:app"}])
                query/all-embeddings (fn [& _]
                                       [{:xt/id "embedding:app"}])
                query/all-system-nodes (fn [& _]
                                         [{:xt/id "system:app"}])
                query/all-system-edges (fn [& _]
                                         [{:xt/id "system-edge:app"}])
                query/all-system-evidence (fn [& _]
                                            [{:xt/id "evidence:app"
                                              :active? true}])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 1
                                                      :imports-package 1
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _]
                                     [{:xt/id "work:app"}])
                activity/all-events (fn [& _]
                                      [{:xt/id "event:validation"
                                        :event-kind :validation}])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 1
                     :activity-count 1
                     :runtime-count 1
                     :validation-count 1})]
      (is (= :limited (:status evidence)))
      (is (contains? (set (:weak evidence)) :source-files))
      (is (= {:plane :source-files
              :status :weak
              :counts {:files 1
                       :skipped-files 3
                       :diagnostics 0}}
             (some #(when (= :source-files (:plane %)) %)
                   (:planes evidence))))
      (is (some #{"Some files were skipped by the latest index run; inspect source coverage before treating missing facts as absent."}
                (:warnings evidence)))
      (is (some #(= {:kind :coverage
                     :label "Inspect skipped source candidates"
                     :count 3
                     :command "ygg sync coverage <project.edn> --json"
                     :pluginRegistryCommand "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>"
                     :pluginScaffoldCommand "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>"
                     :pluginGapCommand "bb plugin gap extractor <package-dir> <repo-root> <file> --json"
                     :mcpTool "ygg_status"}
                    %)
                (:nextActions evidence))))))

(deftest evidence-exposes-indexed-dependency-plane
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files []
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "package:npm:react"
                                    :kind :external-package
                                    :active? true}
                                   {:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _]
                                  [{:xt/id "edge:src.app-react"
                                    :relation :imports-package
                                    :active? true}
                                   {:xt/id "edge:other"
                                    :relation :imports
                                    :active? true}])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 1
                                                      :imports-package 1
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :validation-count 0})]
      (is (contains? (set (:available evidence)) :dependencies))
      (is (not (contains? (set (:missing evidence)) :dependencies)))
      (is (= {:plane :dependencies
              :status :available
              :counts {:external-packages 1
                       :package-import-edges 1
                       :declared-packages 1
                       :source-import-candidates 0
                       :unresolved-imports 0
                       :package-evidence-gaps 0
                       :package-conflicts 0}}
             (some #(when (= :dependencies (:plane %)) %)
                   (:planes evidence))))
      (is (= 1 (get-in evidence [:counts :external-packages])))
      (is (= 1 (get-in evidence [:counts :package-import-edges]))))))

(deftest evidence-exposes-system-evidence-plane
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _]
                                         [{:xt/id "system:app"}])
                query/all-system-edges (fn [& _] [])
                query/all-system-evidence (fn [& _]
                                            [{:xt/id "evidence:database-url"
                                              :kind :env-var
                                              :active? true}])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :imports-package 0
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :runtime-count 0
                     :validation-count 0})]
      (is (contains? (set (:available evidence)) :system-evidence))
      (is (contains? (set (:weak evidence)) :system-evidence))
      (is (not (contains? (set (:missing evidence)) :system-evidence)))
      (is (= {:plane :system-evidence
              :status :weak
              :counts {:system-evidence 1}}
             (some #(when (= :system-evidence (:plane %)) %)
                   (:planes evidence))))
      (is (= 1 (get-in evidence [:counts :system-evidence])))
      (is (some #{"Runtime/config evidence rows are indexed, but no runtime/config evidence matched this query."}
                (:warnings evidence))))))

(deftest evidence-counts-result-schema-mismatch-events
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :imports-package 0
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _]
                                     [{:xt/id "work:app"}])
                activity/all-events (fn [& _]
                                      [{:xt/id "event:mismatch"
                                        :event-kind :result-schema-mismatch}])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :validation-count 0})]
      (is (contains? (set (:available evidence)) :validation-history))
      (is (not (contains? (set (:missing evidence)) :validation-history)))
      (is (= {:plane :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-status-items 0
                       :result-schema-matching-items 0
                       :result-schema-mismatch-items 0
                       :result-schema-missing-result-items 0
                       :result-schema-unexpected-result-items 0
                       :result-schema-mismatch-events 1}}
             (some #(when (= :validation-history (:plane %)) %)
                   (:planes evidence))))
      (is (= 1 (get-in evidence [:counts :result-schema-mismatch-events])))
      (is (some #{"Completed work has result schema mismatches; inspect activity before trusting prior results."}
                (:warnings evidence)))
      (is (not (some #{"No validation history rows are indexed; validation-history queries are limited."}
                     (:warnings evidence))))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema mismatch activity"
                     :count 1
                     :mcpTool "ygg_sync_activity"
                     :command "ygg sync activity <project.edn> --json"}
                    %)
                (:nextActions evidence))))))

(deftest evidence-counts-result-schema-status-items
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :imports-package 0
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _]
                                     [{:xt/id "work:ok"
                                       :expected-result-schema "ygg.result/v1"
                                       :result-schema "ygg.result/v1"}])
                activity/all-events (fn [& _] [])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :validation-count 0})]
      (is (= {:matching 1}
             (get-in evidence [:counts :result-schema-statuses])))
      (is (= 1 (get-in evidence [:counts :result-schema-status-items])))
      (is (contains? (set (:available evidence)) :validation-history))
      (is (not (contains? (set (:missing evidence)) :validation-history)))
      (is (= {:plane :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-status-items 1
                       :result-schema-matching-items 1
                       :result-schema-mismatch-items 0
                       :result-schema-missing-result-items 0
                       :result-schema-unexpected-result-items 0
                       :result-schema-mismatch-events 0}}
             (some #(when (= :validation-history (:plane %)) %)
                   (:planes evidence))))
      (is (not (some #{"No validation history rows are indexed; validation-history queries are limited."}
                     (:warnings evidence)))))))

(deftest evidence-reports-missing-dependency-plane
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _]
                                   [{:xt/id "chunk:app"
                                     :active? true}])
                query/all-search-docs (fn [& _]
                                        [{:xt/id "search:app"}])
                query/all-embeddings (fn [& _]
                                       [{:xt/id "embedding:app"}])
                query/all-system-nodes (fn [& _]
                                         [{:xt/id "system:app"}])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :imports-package 0
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _]
                                     [{:xt/id "work:app"}])
                activity/all-events (fn [& _]
                                      [{:xt/id "event:app"
                                        :event-kind :validation}])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 1
                     :activity-count 1
                     :validation-count 1})]
      (is (not (contains? (set (:available evidence)) :dependencies)))
      (is (contains? (set (:missing evidence)) :dependencies))
      (is (some #{"No dependency graph rows are indexed; dependency questions are limited."}
                (:warnings evidence)))
      (is (some #(= "ygg packages --project fixture --json" (:command %))
                (:nextActions evidence))))))

(deftest evidence-surfaces-unresolved-dependency-review-next-step
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "node:src.app"
                                    :kind :namespace
                                    :active? true}])
                query/all-edges (fn [& _]
                                  [{:xt/id "edge:src.app-left-pad"
                                    :relation :imports
                                    :active? true}])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 0
                                                      :imports-package 0
                                                      :unresolved-imports 1
                                                      :declared-without-import-evidence 0
                                                      :version-conflicts 0}})
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :validation-count 0})]
      (is (contains? (set (:available evidence)) :dependencies))
      (is (contains? (set (:weak evidence)) :dependencies))
      (is (not (contains? (set (:missing evidence)) :dependencies)))
      (is (= 1 (get-in evidence [:counts :unresolved-imports])))
      (is (some #{"Dependency graph has unresolved imports; dependency answers may need package review."}
                (:warnings evidence)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect package graph facts"
                     :command "ygg packages --project fixture --json"}
                    %)
                (:nextActions evidence)))
      (is (some #(= {:kind :dependency-review
                     :label "Queue unresolved import review work"
                     :count 1
                     :command "ygg sync <project.edn> --check --enqueue"}
                    %)
                (:nextActions evidence))))))

(deftest evidence-surfaces-dependency-evidence-gaps-and-conflicts
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :ygg/files [{:xt/id "file:app"
                                                :active? true}]
                                   :ygg/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "package:npm:react"
                                    :kind :external-package
                                    :active? true}])
                query/all-edges (fn [& _]
                                  [{:xt/id "edge:src.react"
                                    :relation :imports-package
                                    :active? true}])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 1
                                                      :imports-package 0
                                                      :source-import-candidates 1
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 1
                                                      :version-conflicts 1}})
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical}
                    {:entity-count 1
                     :doc-count 0
                     :activity-count 0
                     :validation-count 0})]
      (is (not (contains? (set (:weak evidence)) :dependencies)))
      (is (contains? (set (:available evidence)) :dependencies))
      (is (= 1 (get-in evidence [:counts :package-evidence-gaps])))
      (is (= 1 (get-in evidence [:counts :package-conflicts])))
      (is (some #{"Some declared packages have no source import evidence."}
                (:warnings evidence)))
      (is (some #{"Package version conflicts are present in dependency facts."}
                (:warnings evidence)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect packages without source import evidence"
                     :count 1
                     :command "ygg packages --project fixture --without-import-evidence --json"}
                    %)
                (:nextActions evidence)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect package version conflicts"
                     :count 1
                     :command "ygg packages --project fixture --with-conflicts --json"}
                    %)
                (:nextActions evidence))))))

(deftest context-packet-includes-search-instrumentation
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :seed-count 2
                                                         :graph-edges-loaded 3
                                                         :graph-adjacency-strategy "xtql-rel-unify"
                                                         :graph-adjacency-query-count 2
                                                         :graph-adjacency-source-query-count 1
                                                         :graph-adjacency-target-query-count 1
                                                         :graph-adjacency-seed-count 2
                                                         :graph-adjacency-loaded-rows 3
                                                         :returned-count 1}
                                       :results [{:path "src/auth.clj"
                                                  :score 1.2
                                                  :target-kind :chunk
                                                  :target-id "chunk:auth"
                                                  :label "auth/start"
                                                  :source-line 12
                                                  :end-line 18}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes [{:id "system:alpha"
                                               :label "Alpha"
                                               :kind :accepted-system
                                               :degree 3}
                                              {:id "system:beta"
                                               :label "Beta"
                                               :kind :candidate-system
                                               :degree 1}]
                                      :edges [{:id "edge:alpha-beta"
                                               :source "system:alpha"
                                               :target "system:beta"
                                               :relation :depends-on}]
                                      :clusters []})
                query/all-chunks (fn [& _]
                                   (throw (ex-info "unexpected broad chunk scan" {})))
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _]
                                         {:status :ready
                                          :counts {:files 4
                                                   :nodes 7
                                                   :edges 9
                                                   :system-nodes 2
                                                   :system-edges 1
                                                   :search-docs 1
                                                   :chunks 3
                                                   :embeddings 0
                                                   :map-systems 1
                                                   :activity-items 0}
                                          :missing [:embeddings]
                                          :weak []})
                coverage/context-summary (fn [& _]
                                           {:schema "ygg.source-coverage.context/v1"
                                            :totals {:indexedFiles 1}})]
    (let [packet (context/context-packet :xtdb
                                         "auth"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :plugins {:packages [plugin-package-fixture]}
                                          :freshness {:status :current
                                                      :counts {:indexed 1
                                                               :current 1
                                                               :changed 0}}})]
      (is (= "query:test" (get-in packet [:search :query-run-id])))
      (is (= :lexical (get-in packet [:search :retriever-effective])))
      (is (= 1 (get-in packet [:search :instrumentation :search-docs])))
      (is (= 0 (get-in packet [:search :instrumentation :context-chunks])))
      (is (= {:status :current
              :counts {:indexed 1
                       :current 1
                       :changed 0}}
             (:freshness packet)))
      (is (= {:schema "ygg.graph-readiness/v1"
              :status :ready
              :rowCounts {:sourceFiles 4
                          :sourceNodes 7
                          :sourceEdges 9
                          :systemNodes 2
                          :systemEdges 1
                          :searchDocs 1
                          :chunks 3
                          :embeddings 0
                          :mapSystems 1
                          :activityItems 0}
              :systemGraph {:nodeKinds [{:value "accepted-system"
                                         :count 1}
                                        {:value "candidate-system"
                                         :count 1}]
                            :relations [{:value "depends-on"
                                         :count 1}]
                            :representativeNodes [{:id "system:alpha"
                                                   :label "Alpha"
                                                   :kind :accepted-system
                                                   :degree 3}
                                                  {:id "system:beta"
                                                   :label "Beta"
                                                   :kind :candidate-system
                                                   :degree 1}]}
              :retrieval {:search-docs 1
                          :seed-count 2
                          :graph-edges-loaded 3
                          :graph-adjacency-strategy "xtql-rel-unify"
                          :graph-adjacency-query-count 2
                          :graph-adjacency-source-query-count 1
                          :graph-adjacency-target-query-count 1
                          :graph-adjacency-seed-count 2
                          :graph-adjacency-loaded-rows 3
                          :returned-count 1
                          :context-chunks 0}
              :missingPlanes [:embeddings]
              :weakPlanes []}
             (get-in packet [:graph :readiness])))
      (is (= {:indexedFiles 1}
             (get-in packet [:sourceCoverage :totals])))
      (is (= {:counts {:packages 1
                       :warnings 1
                       :unbenchmarked 1
                       :benchmarked 0
                       :nonAuthoritative 1}
              :packages [compact-plugin-package-fixture]}
             (:pluginPackages packet)))
      (is (not (contains? packet :auditScopes)))
      (is (= [{:path "src/auth.clj"
               :rank 1
               :score 1.2
               :targetKind "chunk"
               :label "auth/start"
               :sourceLine 12
               :endLine 18}]
             (:candidateFiles packet))))))

(deftest context-packet-includes-architecture-section-for-selected-systems
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:architecture"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 2
                                                         :returned-count 2}
                                       :results [{:path "src/billing/api.clj"
                                                  :score 1.2
                                                  :target-kind :node
                                                  :target-id "system:billing"
                                                  :label "Billing"
                                                  :source-line 1}
                                                 {:path "src/worker/job.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "system:worker"
                                                  :label "Worker"
                                                  :source-line 1}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes [{:id "system:billing"
                                               :label "Billing"
                                               :kind "service"
                                               :repo "app"
                                               :pathPrefix "src/billing"
                                               :source "map-overlay"}
                                              {:id "system:worker"
                                               :label "Worker"
                                               :kind "candidate-system"
                                               :repo "app"
                                               :pathPrefix "src/worker"
                                               :candidateTypes ["runtime-url-host"]
                                               :candidateEvidence [{:type "runtime-url-host"
                                                                    :host "worker.example.test"}]}]
                                      :edges [{:id "edge:billing-worker"
                                               :source "system:billing"
                                               :target "system:worker"
                                               :relation "shares-config"
                                               :confidence "medium"}]
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _]
                                            [{:xt/id "evidence:billing-env"
                                              :system-id "system:billing"
                                              :repo-id "app"
                                              :path "src/billing/api.clj"
                                              :file-kind :clojure
                                              :kind :env-var
                                              :label "DATABASE_URL"
                                              :normalized-value "database-url"
                                              :source-line 4
                                              :confidence 1.0
                                              :active? true}])
                dependency/package-report (fn [& _]
                                            (assoc (empty-dependency-report)
                                                   :counts {:packages 2
                                                            :versions 0
                                                            :requires 2
                                                            :resolves 0
                                                            :imports-package 1
                                                            :unresolved-imports 2
                                                            :declared-without-import-evidence 1
                                                            :version-conflicts 1}
                                                   :packages [{:id "node:pkg:stripe"
                                                               :label "stripe"
                                                               :ecosystem :npm
                                                               :package-name "stripe"
                                                               :declared-by [{:id "node:manifest:package"
                                                                              :path "package.json"
                                                                              :line 12}]
                                                               :resolved-versions []
                                                               :imported-by [{:id "node:billing-api"
                                                                              :label "billing.api"
                                                                              :path "src/billing/api.clj"
                                                                              :line 9
                                                                              :kind :clojure
                                                                              :import-name "stripe"
                                                                              :resolution-source :declared}]}
                                                              {:id "node:pkg:noise"
                                                               :label "noise"
                                                               :ecosystem :npm
                                                               :package-name "noise"
                                                               :declared-by [{:id "node:manifest:other"
                                                                              :path "other/package.json"
                                                                              :line 4}]
                                                               :resolved-versions []
                                                               :imported-by []}]
                                                   :unresolved-imports [{:source-id "node:worker-job"
                                                                         :source-label "worker.job"
                                                                         :target-id "node:namespace:jobs.queue"
                                                                         :import "jobs.queue"
                                                                         :repo-id "app"
                                                                         :path "src/worker/job.clj"
                                                                         :line 7
                                                                         :kind :clojure}
                                                                        {:source-id "node:other-job"
                                                                         :source-label "other.job"
                                                                         :target-id "node:namespace:other.queue"
                                                                         :import "other.queue"
                                                                         :repo-id "app"
                                                                         :path "other/job.clj"
                                                                         :line 11
                                                                         :kind :clojure}]
                                                   :declared-without-import-evidence [{:id "node:pkg:noise"
                                                                                       :label "noise"
                                                                                       :ecosystem :npm
                                                                                       :package-name "noise"
                                                                                       :declared-by [{:id "node:manifest:other"
                                                                                                      :path "other/package.json"
                                                                                                      :line 4}]
                                                                                       :resolved-versions []
                                                                                       :imported-by []}]
                                                   :version-conflicts [{:id "node:pkg:noise"
                                                                        :label "noise"
                                                                        :ecosystem :npm
                                                                        :package-name "noise"
                                                                        :versions ["1.0.0" "2.0.0"]}]))
                activity/select-activity (fn [& _]
                                           [{:id "activity:boundary"
                                             :kind "maintenance-decision"
                                             :status "ready"
                                             :source "queue"
                                             :sourceId "work:boundary"
                                             :summary "review billing boundary"
                                             :score 1.0}])
                context/query-evidence (fn [& _]
                                         {:status :limited
                                          :missing [:dependencies]
                                          :weak [:docs]
                                          :unsupported [:remote-work]
                                          :warnings ["Dependency graph is incomplete."]
                                          :nextActions [{:kind :dependencies
                                                         :command "ygg packages --json"}]})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet
                  :xtdb
                  "billing worker boundary"
                  {:project-id "fixture"
                   :retriever :lexical
                   :map-overlay {:systems [{:id "system:billing"
                                            :label "Billing"
                                            :kind "service"
                                            :repo "app"
                                            :includes [{:repo "app"
                                                        :path "src/billing"}]}]
                                 :reject [{:match {:repo "app"
                                                   :path "src/worker"
                                                   :kind "candidate-system"}
                                           :reason "worker boundary was rejected"}]}})
          architecture (:architecture packet)]
      (is (= "mechanical-plus-map" (:basis architecture)))
      (is (= {:basis "mechanical-plus-map"
              :accepted [{:id "system:billing"
                          :label "Billing"
                          :kind "service"
                          :status "accepted"
                          :repo "app"
                          :pathPrefix "src/billing"}]
              :candidates [{:id "system:worker"
                            :label "Worker"
                            :kind "candidate-system"
                            :status "candidate"
                            :basis "system-graph"
                            :repo "app"
                            :pathPrefix "src/worker"
                            :candidateTypes ["runtime-url-host"]
                            :score 1.08125
                            :why "retrieval and graph match"}]
              :counts {:accepted 1
                       :candidates 1}}
             (:systems packet)))
      (is (= ["system:billing"]
             (mapv :id (:acceptedSystems architecture))))
      (is (= [{:id "system:worker"
               :label "Worker"
               :kind "candidate-system"
               :status "candidate"
               :basis "system-graph"
               :repo "app"
               :pathPrefix "src/worker"
               :candidateTypes ["runtime-url-host"]
               :candidateEvidence [{:type "runtime-url-host"
                                    :host "worker.example.test"}]
               :why "retrieval and graph match"}]
             (mapv #(select-keys % [:id
                                    :label
                                    :kind
                                    :status
                                    :basis
                                    :repo
                                    :pathPrefix
                                    :candidateTypes
                                    :candidateEvidence
                                    :why])
                   (:candidateSystems architecture))))
      (is (= [{:kind "map-reject"
               :id "map-reject:1"
               :status "rejected"
               :provenance "map-overlay"
               :match {:repo "app"
                       :kind "candidate-system"
                       :path "src/worker"}
               :reason "worker boundary was rejected"}]
             (:rejectedCorrections architecture)))
      (is (= [{:kind "graph-edge"
               :id "edge:billing-worker"
               :source "system:billing"
               :target "system:worker"
               :relation "shares-config"
               :confidence "medium"
               :score 1.0}]
             (:boundaryEvidence architecture)))
      (is (= [{:relation "shares-config"
               :source "system:billing"
               :count 1
               :targets [{:id "edge:billing-worker"
                          :target "system:worker"
                          :confidence "medium"
                          :score 1.0}]}]
             (:relationships packet)))
      (is (= [{:id "evidence:billing-env"
               :systemId "system:billing"
               :repo "app"
               :path "src/billing/api.clj"
               :kind "env-var"
               :label "DATABASE_URL"
               :normalizedValue "database-url"
               :sourceLine 4
               :confidence 1.0
               :fileKind "clojure"}]
             (mapv #(dissoc % :score) (:runtimeEvidence architecture))))
      (is (= [{:kind "unresolved-import"
               :id "unresolved-import:node:worker-job:node:namespace:jobs.queue:src/worker/job.clj:7"
               :source "node:worker-job"
               :target "node:namespace:jobs.queue"
               :import "jobs.queue"
               :repo "app"
               :path "src/worker/job.clj"
               :sourceLine 7
               :relation "unresolved-import"
               :sourceLabel "worker.job"
               :fileKind "clojure"}
              {:kind "package-import"
               :id "node:pkg:stripe:import:src/billing/api.clj:9"
               :packageId "node:pkg:stripe"
               :package "stripe"
               :label "stripe"
               :ecosystem "npm"
               :relation "imports-package"
               :path "src/billing/api.clj"
               :sourceLine 9
               :fileKind "clojure"
               :importName "stripe"
               :resolutionSource "declared"}]
             (mapv #(dissoc % :score)
                   (:dependencyEvidence architecture))))
      (is (= [{:kind "source-structure"
               :basis "selected-architecture-evidence"
               :facts 1
               :topEvidenceTypes [{:kind "shares-config"
                                   :count 1}]}
              {:kind "map-corrections"
               :basis "selected-architecture-evidence"
               :facts 1
               :topEvidenceTypes [{:kind "map-reject"
                                   :count 1}]}
              {:kind "dependencies"
               :basis "selected-architecture-evidence"
               :facts 2
               :files 2
               :topEvidenceTypes [{:kind "imports-package"
                                   :count 1}
                                  {:kind "unresolved-import"
                                   :count 1}]}
              {:kind "dependency-auth-runtime"
               :basis "selected-architecture-evidence"
               :facts 3
               :files 2
               :topEvidenceTypes [{:kind "env-var"
                                   :count 1}
                                  {:kind "imports-package"
                                   :count 1}
                                  {:kind "unresolved-import"
                                   :count 1}]}
              {:kind "runtime-config"
               :basis "selected-architecture-evidence"
               :facts 1
               :files 1
               :topEvidenceTypes [{:kind "env-var"
                                   :count 1}]}]
             (mapv #(dissoc % :samples)
                   (:auditScopes packet))))
      (is (= [{:id "activity:boundary"
               :kind "maintenance-decision"
               :status "ready"
               :source "queue"
               :sourceId "work:boundary"
               :summary "review billing boundary"
               :score 1.0}]
             (:openDecisions architecture)))
      (is (= [{:id "edge:billing-worker"
               :kind "graph-edge"
               :relation "shares-config"
               :target "system:worker"
               :source "system:billing"
               :section "boundaryEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 0 :samples]))))
      (is (= [{:id "map-reject:1"
               :kind "map-reject"
               :status "rejected"
               :provenance "map-overlay"
               :match {:repo "app"
                       :kind "candidate-system"
                       :path "src/worker"}
               :reason "worker boundary was rejected"
               :section "rejectedCorrections"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 1 :samples]))))
      (is (= [{:kind "unresolved-import"
               :relation "unresolved-import"
               :path "src/worker/job.clj"
               :target "node:namespace:jobs.queue"
               :source "node:worker-job"
               :repo "app"
               :id "unresolved-import:node:worker-job:node:namespace:jobs.queue:src/worker/job.clj:7"
               :sourceLine 7
               :fileKind "clojure"
               :section "dependencyEvidence"}
              {:kind "package-import"
               :relation "imports-package"
               :path "src/billing/api.clj"
               :id "node:pkg:stripe:import:src/billing/api.clj:9"
               :sourceLine 9
               :fileKind "clojure"
               :section "dependencyEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 2 :samples]))))
      (is (= [{:id "evidence:billing-env"
               :kind "env-var"
               :path "src/billing/api.clj"
               :repo "app"
               :sourceLine 4
               :fileKind "clojure"
               :section "runtimeEvidence"}
              {:kind "unresolved-import"
               :relation "unresolved-import"
               :path "src/worker/job.clj"
               :target "node:namespace:jobs.queue"
               :source "node:worker-job"
               :repo "app"
               :id "unresolved-import:node:worker-job:node:namespace:jobs.queue:src/worker/job.clj:7"
               :sourceLine 7
               :fileKind "clojure"
               :section "dependencyEvidence"}
              {:kind "package-import"
               :relation "imports-package"
               :path "src/billing/api.clj"
               :id "node:pkg:stripe:import:src/billing/api.clj:9"
               :sourceLine 9
               :fileKind "clojure"
               :section "dependencyEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 3 :samples]))))
      (is (= [{:id "evidence:billing-env"
               :kind "env-var"
               :path "src/billing/api.clj"
               :repo "app"
               :sourceLine 4
               :fileKind "clojure"
               :section "runtimeEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 4 :samples]))))
      (is (> (get-in architecture [:runtimeEvidence 0 :score]) 2.0))
      (is (= [{:plane "dependencies"
               :status "missing"
               :nextActions [{:kind :dependencies
                              :command "ygg packages --json"}]}
              {:plane "docs"
               :status "weak"}
              {:plane "remote-work"
               :status "unsupported"}]
             (:validationGaps architecture)))
      (is (= ["system:billing" "system:worker" "evidence:billing-env"]
             (mapv :target (take 3 (:nextActions architecture)))))
      (is (= ["ygg_node" "ygg_node" "ygg_node"]
             (mapv :mcpTool (take 3 (:nextActions architecture)))))
      (is (= ["node:namespace:jobs.queue" "node:pkg:stripe"]
             (mapv :target (take 2 (drop 3 (:nextActions architecture))))))
      (is (= {:kind :work-review
              :target "work:boundary"
              :command "ygg sync work show work:boundary"
              :mcpTool "ygg_work_show"
              :mcpArgs {:workId "work:boundary"}}
             (select-keys (nth (:nextActions architecture) 5)
                          [:kind :target :command :mcpTool :mcpArgs]))))))

(deftest context-packet-selects-accepted-system-from-result-path
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:billing-path"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "src/billing/api.clj"
                                                  :repo-id "app"
                                                  :score 1.0
                                                  :target-kind :chunk
                                                  :target-id "chunk:billing-api"
                                                  :label "billing api"}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [_ paths _]
                                        (is (some #{"docs/billing.md"} paths))
                                        [{:xt/id "chunk:billing-doc"
                                          :repo-id "app"
                                          :path "docs/billing.md"
                                          :kind :markdown
                                          :label "Billing contract"
                                          :heading-path ["Billing contract"]
                                          :text "Billing contract terms."
                                          :source-line 1
                                          :end-line 2
                                          :content-sha "doc-sha"}])
                query/all-system-evidence (fn [& _]
                                            [{:xt/id "evidence:billing-port"
                                              :system-id "system:billing"
                                              :repo-id "app"
                                              :path "config/runtime.env"
                                              :file-kind :env
                                              :kind :port
                                              :label "PORT"
                                              :normalized-value "8080"
                                              :source-line 3
                                              :confidence 0.8
                                              :active? true}
                                             {:xt/id "evidence:other-port"
                                              :system-id "system:other"
                                              :repo-id "app"
                                              :path "config/other.env"
                                              :file-kind :env
                                              :kind :port
                                              :label "PORT"
                                              :normalized-value "9090"
                                              :source-line 4
                                              :confidence 0.8
                                              :active? true}])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [_ _ opts]
                                           (is (contains? (:target-ids opts)
                                                          "system:billing"))
                                           [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet
                  :xtdb
                  "billing api"
                  {:project-id "fixture"
                   :retriever :lexical
                   :map-overlay {:systems [{:id "system:billing"
                                            :label "Billing"
                                            :repo "app"
                                            :includes [{:repo "app"
                                                        :path "src/billing"}]}
                                           {:id "system:other"
                                            :label "Other"
                                            :repo "app"
                                            :includes [{:repo "app"
                                                        :path "src/other"}]}]
                                 :docs [{:target "system:billing"
                                         :role "contract"
                                         :status "accepted"
                                         :source {:repo "app"
                                                  :path "docs/billing.md"}}]}})]
      (is (= ["system:billing"]
             (mapv :id (get-in packet [:architecture :acceptedSystems]))))
      (is (= ["docs/billing.md"]
             (mapv #(get-in % [:source :path])
                   (get-in packet [:architecture :docs]))))
      (is (= ["evidence:billing-port"]
             (mapv :id (get-in packet [:architecture :runtimeEvidence]))))
      (is (= ["system:billing" "evidence:billing-port" "docs/billing.md"]
             (mapv :target (get-in packet [:architecture :nextActions]))))
      (is (= ["src/billing/api.clj"]
             (mapv :path (:candidateFiles packet)))))))

(deftest runtime-evidence-keeps-selected-system-diversity
  (let [runtime-evidence (#'context/select-system-evidence
                          ["container"]
                          ["system:tests" "system:stack"]
                          [{:repo-id "app"
                            :path "tests/Alpha.cs"
                            :score 2.0}
                           {:repo-id "app"
                            :path "tests/Beta.cs"
                            :score 1.9}
                           {:repo-id "app"
                            :path "tests/Gamma.cs"
                            :score 1.8}]
                          [{:xt/id "evidence:tests-alpha"
                            :system-id "system:tests"
                            :repo-id "app"
                            :path "tests/Alpha.cs"
                            :file-kind :dotnet
                            :kind :url
                            :label "container setup note"
                            :normalized-value "container-setup-note"
                            :source-line 10
                            :confidence 0.7}
                           {:xt/id "evidence:tests-beta"
                            :system-id "system:tests"
                            :repo-id "app"
                            :path "tests/Beta.cs"
                            :file-kind :dotnet
                            :kind :url
                            :label "container troubleshooting note"
                            :normalized-value "container-troubleshooting-note"
                            :source-line 11
                            :confidence 0.7}
                           {:xt/id "evidence:tests-gamma"
                            :system-id "system:tests"
                            :repo-id "app"
                            :path "tests/Gamma.cs"
                            :file-kind :dotnet
                            :kind :url
                            :label "container reference note"
                            :normalized-value "container-reference-note"
                            :source-line 12
                            :confidence 0.7}
                           {:xt/id "evidence:stack-postgres"
                            :system-id "system:stack"
                            :repo-id "app"
                            :path "tests/docker-compose.yml"
                            :file-kind :compose
                            :kind :container-image-consumer
                            :label "postgres:alpine"
                            :normalized-value "container-image:postgres"
                            :source-line 20
                            :confidence 0.74}]
                          4)]
    (is (= ["evidence:tests-alpha"
            "evidence:tests-beta"
            "evidence:stack-postgres"
            "evidence:tests-gamma"]
           (mapv :id runtime-evidence)))
    (is (> (:score (last runtime-evidence))
           (:score (nth runtime-evidence 2))))))

(deftest runtime-evidence-keeps-file-kind-diversity
  (let [runtime-evidence (#'context/select-system-evidence
                          ["database" "runtime" "config" "env"]
                          ["system:db"]
                          []
                          [{:xt/id "evidence:sql-security-1"
                            :system-id "system:db"
                            :repo-id "app"
                            :path "db/schema.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "SECURITY DEFINER"
                            :normalized-value "security-definer"
                            :source-line 10
                            :confidence 0.65}
                           {:xt/id "evidence:sql-security-2"
                            :system-id "system:db"
                            :repo-id "app"
                            :path "db/schema.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "SECURITY DEFINER"
                            :normalized-value "security-definer"
                            :source-line 11
                            :confidence 0.65}
                           {:xt/id "evidence:env-database-url"
                            :system-id "system:db"
                            :repo-id "app"
                            :path "migrations/.env"
                            :file-kind :env
                            :kind :env-var
                            :label "DATABASE_URL"
                            :normalized-value "database-url"
                            :source-line 2
                            :confidence 0.65}]
                          2)]
    (is (= ["evidence:env-database-url"
            "evidence:sql-security-1"]
           (mapv :id runtime-evidence)))))

(deftest runtime-evidence-keeps-ranked-result-path-coverage
  (let [runtime-evidence (#'context/select-system-evidence
                          ["trigger" "owner" "security" "setup"]
                          ["system:ops"]
                          [{:repo-id "app"
                            :path "db/init.sql"
                            :score 1.0}
                           {:repo-id "app"
                            :path "ops/auth.sql"
                            :score 0.9}]
                          [{:xt/id "evidence:ops-security"
                            :system-id "system:ops"
                            :repo-id "app"
                            :path "ops/auth.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "SECURITY DEFINER owner trigger setup"
                            :normalized-value "security-definer"
                            :source-line 10
                            :confidence 0.68}
                           {:xt/id "evidence:ops-route"
                            :system-id "system:ops"
                            :repo-id "app"
                            :path "ops/route.txt"
                            :file-kind :text
                            :kind :route
                            :label "trigger owner security setup route"
                            :normalized-value "trigger-owner-security-setup-route"
                            :source-line 11
                            :confidence 0.55}
                           {:xt/id "evidence:ops-trace"
                            :system-id "system:ops"
                            :repo-id "app"
                            :path "ops/trace.txt"
                            :file-kind :text
                            :kind :route
                            :label "trigger owner security setup trace"
                            :normalized-value "trigger-owner-security-setup-trace"
                            :source-line 12
                            :confidence 0.55}
                           {:xt/id "evidence:init-security"
                            :system-id "system:db"
                            :repo-id "app"
                            :path "db/init.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "SECURITY DEFINER"
                            :normalized-value "security-definer"
                            :source-line 13
                            :confidence 0.68}]
                          3)]
    (is (contains? (set (map :id runtime-evidence))
                   "evidence:ops-security"))
    (is (contains? (set (map :id runtime-evidence))
                   "evidence:init-security"))
    (is (= #{"db/init.sql" "ops/auth.sql"}
           (->> runtime-evidence
                (map :path)
                (filter #(#{"db/init.sql" "ops/auth.sql"} %))
                set)))))

(deftest runtime-evidence-boosts-files-sharing-selected-fact-values
  (let [runtime-evidence (#'context/select-system-evidence
                          ["database" "runtime" "config" "env"]
                          []
                          [{:repo-id "app"
                            :path "scripts/migrate.sh"
                            :score 2.0}]
                          [{:xt/id "evidence:runner-database-url"
                            :system-id "system:runner"
                            :repo-id "app"
                            :path "scripts/migrate.sh"
                            :file-kind :shell
                            :kind :env-var
                            :label "DATABASE_URL"
                            :normalized-value "database-url"
                            :source-line 12
                            :confidence 0.65}
                           {:xt/id "evidence:matched-env-database-url"
                            :system-id "system:env"
                            :repo-id "app"
                            :path "config/.env"
                            :file-kind :env
                            :kind :env-var
                            :label "DATABASE_URL"
                            :normalized-value "database-url"
                            :source-line 1
                            :confidence 0.65}
                           {:xt/id "evidence:other-env-host"
                            :system-id "system:other-env"
                            :repo-id "app"
                            :path "tests/.env"
                            :file-kind :env
                            :kind :env-var
                            :label "POSTGRES_HOST"
                            :normalized-value "postgres-host"
                            :source-line 1
                            :confidence 0.65}]
                          3)
        scores-by-id (into {} (map (juxt :id :score) runtime-evidence))]
    (is (= ["evidence:matched-env-database-url"
            "evidence:runner-database-url"
            "evidence:other-env-host"]
           (mapv :id runtime-evidence)))
    (is (> (get scores-by-id "evidence:matched-env-database-url")
           (get scores-by-id "evidence:other-env-host")))))

(deftest dependency-evidence-includes-query-matched-package-import-sources
  (let [dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 [{:id "node:pkg:proxy-from-env"
                                   :package-name "proxy-from-env"
                                   :label "npm:proxy-from-env"
                                   :ecosystem :npm
                                   :imported-by [{:path "lib/adapters/http.js"
                                                  :line 5
                                                  :kind :javascript
                                                  :import-name "proxy-from-env"
                                                  :resolution-source :declared}]}])
        rows (#'context-architecture/dependency-report-evidence
              ["proxy" "env"]
              []
              []
              []
              dependency-report)]
    (is (= [{:kind "package-import"
             :id "node:pkg:proxy-from-env:import:lib/adapters/http.js:5"
             :packageId "node:pkg:proxy-from-env"
             :package "proxy-from-env"
             :label "npm:proxy-from-env"
             :ecosystem "npm"
             :relation "imports-package"
             :path "lib/adapters/http.js"
             :sourceLine 5
             :fileKind "javascript"
             :importName "proxy-from-env"
             :resolutionSource "declared"}]
           (mapv #(dissoc % :score) rows)))
    (is (< 1.0 (:score (first rows))))))

(deftest context-packet-scores-dependencies-before-display-limiting
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:dependencies"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 0
                                                         :returned-count 0}
                                       :results []})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report
                (fn [_ _ opts]
                  (if (contains? opts :limit)
                    (assoc (empty-dependency-report)
                           :packages
                           [{:id "node:pkg:axios"
                             :package-name "axios"
                             :label "npm:axios"
                             :ecosystem :npm
                             :imported-by [{:path "tests/smoke/deno/tests/cancel.smoke.test.ts"
                                            :line 2
                                            :kind :typescript
                                            :resolution-source :deno-import-map}]}])
                    (assoc (empty-dependency-report)
                           :packages
                           [{:id "node:pkg:proxy-from-env"
                             :package-name "proxy-from-env"
                             :label "npm:proxy-from-env"
                             :ecosystem :npm
                             :imported-by [{:path "lib/adapters/http.js"
                                            :line 5
                                            :kind :javascript
                                            :import-name "proxy-from-env"
                                            :resolution-source :declared}]}])))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "proxy env"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= ["lib/adapters/http.js"]
             (mapv :path (get-in packet [:architecture :dependencyEvidence])))))))

(deftest candidate-files-preserve-query-score-components
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "src/caller.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:caller"
                                                  :label "demo/caller"
                                                  :reason "graph neighbor"
                                                  :score-components {:lexical 0.2
                                                                     :graph 0.0}}
                                                 {:path "src/caller.clj"
                                                  :score 0.4
                                                  :target-kind :node
                                                  :target-id "node:caller-ns"
                                                  :label "demo"
                                                  :reason "graph neighbor"
                                                  :score-components {:lexical 0.1
                                                                     :graph 0.6}}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "caller"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= [{:path "src/caller.clj"
               :rank 1
               :score 0.8
               :targetKind "node"
               :label "demo/caller"
               :reason "graph neighbor"
               :scoreComponents {:lexical 0.2
                                 :graph 0.6}}]
             (:candidateFiles packet))))))

(deftest source-graph-candidates-surface-query-matched-source-rows
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table fields tokens constraints ctx]
                  (is (= {:valid-at "t"} ctx))
                  (is (= #{"astro" "config" "bootstrap"} (set tokens)))
                  (case table
                    :ygg/nodes
                    (do
                      (is (= [:path :label :name :kind] fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"}
                             constraints))
                      [{:xt/id "node:config"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "site/astro.config.ts"
                        :kind :web-framework-plugin
                        :label "bootstrap"
                        :source-line 12}
                       {:xt/id "node:unrelated"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "src/other.clj"
                        :kind :namespace
                        :label "unrelated"}])

                    :ygg/files
                    (do
                      (is (= [:path :kind] fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"
                              :active? true}
                             constraints))
                      [{:xt/id "file:astro"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "site/astro.config.ts"
                        :kind :javascript
                        :active? true}
                       {:xt/id "file:other"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "src/other.clj"
                        :kind :clojure
                        :active? true}])))]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "Astro config bootstrap")
                {:project-id "fixture"
                 :repo-id "app"
                 :read-context {:valid-at "t"}})
          by-kind (group-by :target-kind rows)]
      (is (= [{:path "site/astro.config.ts"
               :target-kind :node
               :label "bootstrap"
               :kind :web-framework-plugin
               :result-kind :node
               :reason "query-matched source row"
               :repo-id "app"
               :repo "app"
               :source-line 12}]
             (mapv #(select-keys % [:path
                                    :target-kind
                                    :label
                                    :kind
                                    :result-kind
                                    :reason
                                    :repo-id
                                    :repo
                                    :source-line])
                   (:node by-kind))))
      (is (= [{:path "site/astro.config.ts"
               :target-kind :file
               :label "site/astro.config.ts"
               :kind :javascript
               :result-kind :file
               :reason "query-matched source row"
               :repo-id "app"
               :repo "app"}]
             (mapv #(select-keys % [:path
                                    :target-kind
                                    :label
                                    :kind
                                    :result-kind
                                    :reason
                                    :repo-id
                                    :repo])
                   (:file by-kind)))))))

(deftest source-graph-candidates-preserve-file-lane-when-nodes-dominate
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table _ _ _ _]
                  (case table
                    :ygg/nodes
                    (mapv (fn [idx]
                            {:xt/id (str "node:test:" idx)
                             :project-id "fixture"
                             :repo-id "app"
                             :path (str "tests/unit/adapters/http"
                                        idx
                                        ".test.js")
                             :kind :function
                             :label (str "tests.unit.adapters.http"
                                         idx
                                         ".test/createHttp2Axios")})
                          (range 45))

                    :ygg/files
                    [{:xt/id "file:adapter"
                      :project-id "fixture"
                      :repo-id "app"
                      :path "lib/adapters/http.js"
                      :kind :javascript
                      :active? true}]))]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "http adapter")
                {:project-id "fixture"
                 :repo-id "app"})
          files (filter #(= :file (:target-kind %)) rows)]
      (is (= ["lib/adapters/http.js"]
             (mapv :path files))))))

(deftest source-graph-candidates-use-wider-file-lane
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table _ _ _ _]
                  (case table
                    :ygg/nodes []
                    :ygg/files
                    (mapv (fn [idx]
                            {:xt/id (str "file:adapter:" idx)
                             :project-id "fixture"
                             :repo-id "app"
                             :path (format "lib/adapters/http_%02d.js"
                                           idx)
                             :kind :javascript
                             :active? true})
                          (range 45))))]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "http adapter")
                {:project-id "fixture"
                 :repo-id "app"})]
      (is (= 45 (count (filter #(= :file (:target-kind %)) rows)))))))

(deftest context-packet-loads-docs-for-source-graph-file-candidates
  (with-redefs [store/xtdb-handle? (constantly true)
                query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 0
                                                         :returned-count 0}
                                       :results []})
                store/rows-matching-any-token
                (fn [_ table _ _ _ _]
                  (case table
                    :ygg/nodes []
                    :ygg/files
                    [{:xt/id "file:contract"
                      :project-id "fixture"
                      :repo-id "app"
                      :path "src/contract.go"
                      :kind :go
                      :active? true}]))
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [_ paths _]
                                        (is (= ["src/contract.go"]
                                               (vec paths)))
                                        [{:xt/id "chunk:contract"
                                          :repo-id "app"
                                          :path "src/contract.go"
                                          :kind :go
                                          :definition-kind :interface
                                          :label "contract/Service"
                                          :source-line 3
                                          :end-line 8
                                          :text "type Service interface { Start() }"
                                          :tokens ["service" "interface"]}])
                query/all-chunks (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "contract interface"
                                         {:project-id "fixture"
                                          :repo-id "app"
                                          :budget 12000
                                          :retriever :lexical})]
      (is (= ["src/contract.go"]
             (mapv #(get-in % [:source :path]) (:docs packet))))
      (is (= ["chunk:contract"]
             (mapv :target (:docs packet))))
      (is (= [:interface]
             (mapv #(get-in % [:source :definitionKind]) (:docs packet))))
      (is (= ["type Service interface { Start() }"]
             (mapv :snippet (:docs packet))))
      (is (= [true]
             (mapv :retrievedSource (:docs packet)))))))

(deftest inferred-docs-rank-query-matched-definition-kinds
  (let [docs (#'context/inferred-docs
              (text/tokenize "contract interface")
              [{:path "src/package_test.go"
                :target-kind :file
                :score 1.2
                :label "src/package_test.go"}
               {:path "src/component.go"
                :target-kind :file
                :score 1.0
                :label "src/component.go"}]
              [{:xt/id "chunk:test"
                :repo-id "app"
                :path "src/package_test.go"
                :kind :go
                :definition-kind :test
                :label "contract package"
                :source-line 1
                :text "package fixture"
                :tokens ["contract" "package"]}
               {:xt/id "chunk:interface"
                :repo-id "app"
                :path "src/component.go"
                :kind :go
                :definition-kind :interface
                :label "contract Component"
                :source-line 4
                :text "type Component interface { Start() }"
                :tokens ["contract" "component" "interface"]}]
              []
              200)]
    (is (= ["src/component.go" "src/package_test.go"]
           (mapv #(get-in % [:source :path]) docs)))))

(deftest candidate-input-ranking-preserves-root-diversity
  (let [ranked (#'context/ranked-candidate-inputs
                [{:path "tests/first.js" :score 9.0}
                 {:path "tests/second.js" :score 8.0}]
                [{:path "lib/adapter.js" :score 3.0}
                 {:path "docs/guide.md" :score 2.0}])]
    (is (= ["tests/first.js"
            "lib/adapter.js"
            "docs/guide.md"
            "tests/second.js"]
           (mapv :path ranked)))))

(deftest context-packet-ranks-source-graph-candidates-before-low-score-results
  (with-redefs [store/xtdb-handle? (constantly true)
                query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "site/src/pages/docs/index.astro"
                                                  :score 0.9
                                                  :target-kind :node
                                                  :target-id "node:docs"
                                                  :label "site.src.pages.docs.index"}]})
                store/rows-matching-any-token
                (fn [_ table _ _ _ _]
                  (case table
                    :ygg/nodes
                    [{:xt/id "node:config"
                      :project-id "fixture"
                      :repo-id "app"
                      :path "site/astro.config.ts"
                      :kind :web-framework-plugin
                      :label "bootstrap"
                      :source-line 12}]
                    :ygg/files []))
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "Astro config bootstrap"
                                         {:project-id "fixture"
                                          :repo-id "app"
                                          :retriever :lexical})]
      (is (= {:path "site/astro.config.ts"
              :rank 1
              :targetKind "node"
              :label "bootstrap"
              :kind "web-framework-plugin"
              :resultKind "node"
              :reason "query-matched source row"
              :repo "app"
              :sourceLine 12}
             (select-keys (first (:candidateFiles packet))
                          [:path
                           :rank
                           :targetKind
                           :label
                           :kind
                           :resultKind
                           :reason
                           :repo
                           :sourceLine])))
      (is (= (:score (first (:candidateFiles packet)))
             (get-in (first (:candidateFiles packet))
                     [:scoreComponents :sourceGraph]))))))

(deftest candidate-files-merge-line-range-from-duplicate-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 2
                                                         :returned-count 2}
                                       :results [{:path "src/caller.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:caller"
                                                  :label "demo/caller"}
                                                 {:path "src/caller.clj"
                                                  :score 0.4
                                                  :target-kind :chunk
                                                  :target-id "chunk:caller"
                                                  :label "demo/caller body"
                                                  :source-line 12
                                                  :end-line 18}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "caller"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= [{:path "src/caller.clj"
               :rank 1
               :score 0.8
               :targetKind "node"
               :label "demo/caller"
               :supportLabels ["demo/caller body"]
               :sourceLine 12
               :endLine 18}]
             (:candidateFiles packet))))))

(deftest candidate-files-are-scoped-by-repo-and-path
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 3
                                                         :returned-count 3}
                                       :results [{:repo-id "api"
                                                  :path "src/app.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:api.app"
                                                  :label "api.app"}
                                                 {:repo-id "worker"
                                                  :path "src/app.clj"
                                                  :score 0.7
                                                  :target-kind :node
                                                  :target-id "node:worker.app"
                                                  :label "worker.app"}
                                                 {:repo-id "api"
                                                  :path "src/app.clj"
                                                  :score 0.4
                                                  :target-kind :chunk
                                                  :target-id "chunk:api.app"
                                                  :label "api.app/start"}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "app"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= [{:path "src/app.clj"
               :rank 1
               :score 0.8
               :targetKind "node"
               :label "api.app"
               :repo "api"}
              {:path "src/app.clj"
               :rank 2
               :score 0.7
               :targetKind "node"
               :label "worker.app"
               :repo "worker"}]
             (:candidateFiles packet))))))

(deftest candidate-files-trim-to-fit-budget
  (let [candidate-files (mapv (fn [idx]
                                {:path (str "src/file_" idx ".clj")
                                 :repo-id "app"
                                 :rank (inc idx)
                                 :score 0.9
                                 :target-kind :chunk
                                 :label (apply str
                                               "open page root "
                                               (repeat 80 (str "token" idx " ")))
                                 :reason (apply str
                                                "verbose provenance "
                                                (repeat 80 (str "reason" idx " ")))
                                 :score-components {:lexical 0.9
                                                    :graph 0.1}})
                              (range 25))]
    (with-redefs [query/search-report (fn [_ _ _]
                                        {:schema query/search-report-schema
                                         :query-run-id "query:test"
                                         :instrumentation {:search-docs 25
                                                           :returned-count 25}
                                         :results candidate-files})
                  graph/system-graph (fn [_ project-id _]
                                       {:basis {:project-id project-id}
                                        :nodes []
                                        :edges []
                                        :clusters []})
                  query/all-chunks (fn [& _] [])
                  query/chunks-by-ids (fn [& _] [])
                  query/chunks-by-paths (fn [& _] [])
                  query/all-system-evidence (fn [& _] [])
                  dependency/package-report (fn [& _] (empty-dependency-report))
                  activity/select-activity (fn [& _] [])
                  context/query-evidence (fn [& _] {:status :ready})
                  coverage/context-summary (fn [& _] nil)]
      (let [packet (context/context-packet :xtdb
                                           "open page root"
                                           {:project-id "fixture"
                                            :budget 1200
                                            :retriever :lexical})
            files (:candidateFiles packet)]
        (is (seq files))
        (is (< (count files) (count candidate-files)))
        (is (= "src/file_0.clj" (:path (first files))))
        (is (= "app" (:repo (first files))))
        (is (contains? (first files) :scoreComponents))
        (is (not (contains? (first files) :reason)))
        (is (some #(re-find #"candidate files trimmed" %)
                  (:warnings packet)))
        (is (<= (context/estimate-tokens packet) 1200))))))

(deftest candidate-files-do-not-crowd-out-docs
  (let [fit-budget @#'context/fit-budget
        packet {:schema context/schema
                :query "open page root"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 900}
                :entities []
                :edges []
                :activity []
                :warnings []
                :drilldowns []
                :candidateFiles (mapv (fn [idx]
                                        {:path (str "src/candidate_" idx ".clj")
                                         :rank (inc idx)
                                         :score 0.9
                                         :targetKind "chunk"
                                         :label (apply str
                                                       "open page root "
                                                       (repeat 80 (str "token" idx " ")))
                                         :reason (apply str
                                                        "verbose provenance "
                                                        (repeat 80 (str "reason" idx " ")))})
                                      (range 25))
                :docs []}
        docs [{:target "chunk:doc"
               :role "reference"
               :status "candidate"
               :source {:path "src/doc.clj"
                        :heading "open-page"}
               :score 1.0
               :snippet "open page root create board append child"
               :provenance "retrieved-doc"}]
        fitted (fit-budget packet docs 900)]
    (is (= ["src/doc.clj"]
           (mapv #(get-in % [:source :path]) (:docs fitted))))
    (is (seq (:candidateFiles fitted)))
    (is (< (count (:candidateFiles fitted)) 25))
    (is (<= (context/estimate-tokens fitted) 900))))

(deftest fit-budget-groups-retained-doc-snippets-by-file
  (let [fit-budget @#'context/fit-budget
        packet {:schema context/schema
                :query "source docs"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 3000}
                :entities []
                :edges []
                :activity []
                :warnings []
                :drilldowns []
                :candidateFiles []
                :docs []}
        docs [{:target "chunk:a"
               :role "reference"
               :status "candidate"
               :source {:repo "app"
                        :path "src/a.clj"
                        :lines [10 14]
                        :heading "a"}
               :score 1.0
               :snippet "alpha"
               :provenance "retrieved-doc"}
              {:target "chunk:b"
               :role "reference"
               :status "candidate"
               :source {:repo "app"
                        :path "src/a.clj"
                        :lines [20 21]}
               :score 0.8
               :snippet "beta"
               :provenance "retrieved-doc"}
              {:target "chunk:no-snippet"
               :role "reference"
               :status "candidate"
               :source {:repo "app"
                        :path "src/b.clj"}
               :score 0.2
               :snippetOmitted true
               :provenance "retrieved-doc"}]
        fitted (fit-budget packet docs 3000)]
    (is (= [{:path "src/a.clj"
             :repo "app"
             :items [{:target "chunk:a"
                      :text "alpha"
                      :role "reference"
                      :status "candidate"
                      :score 1.0
                      :provenance "retrieved-doc"
                      :lines [10 14]
                      :heading "a"}
                     {:target "chunk:b"
                      :text "beta"
                      :role "reference"
                      :status "candidate"
                      :score 0.8
                      :provenance "retrieved-doc"
                      :lines [20 21]}]}]
           (:snippets fitted)))))

(deftest fit-budget-reserves-candidate-file-budget-when-entities-consume-budget
  (let [fit-budget @#'context/fit-budget
        big-entity {:id "system:large"
                    :label (apply str (repeat 500 "entity-token "))
                    :kind "candidate-system"
                    :path "src/main"
                    :why "retrieval match"
                    :candidateEvidence [{:type "path-cluster"
                                         :path "src/main/file.clj"
                                         :label (apply str (repeat 100 "evidence "))}]}
        packet {:schema context/schema
                :query "find test engine"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 4000}
                :entities [big-entity big-entity big-entity]
                :edges []
                :activity []
                :warnings []
                :drilldowns []
                :candidateFiles (mapv (fn [idx]
                                        {:path (str "src/file_" idx ".clj")
                                         :rank (inc idx)
                                         :score 0.9
                                         :targetKind "chunk"
                                         :label (str "file " idx)})
                                      (range 10))
                :docs []}
        fitted (fit-budget packet [] 4000)]
    (is (seq (:candidateFiles fitted))
        "candidate files should survive even when entities consume most of the budget")
    (is (= "src/file_0.clj" (:path (first (:candidateFiles fitted)))))
    (is (<= (context/estimate-tokens fitted) 4000))))
