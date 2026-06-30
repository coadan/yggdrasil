(ns ygg.context-test
  (:require [ygg.context :as context]
            [ygg.context-architecture :as context-architecture]
            [ygg.activity :as activity]
            [ygg.coverage :as coverage]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.corrections :as corrections]
            [ygg.dependency :as dependency]
            [ygg.graph :as graph]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.string :as str]
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

(deftest architecture-token-score-lowercases-text-once
  (let [token-score @#'context-architecture/token-score
        lower-case str/lower-case
        calls (atom 0)]
    (with-redefs [str/lower-case (fn [value]
                                   (swap! calls inc)
                                   (lower-case value))]
      (is (= 2 (token-score ["proxy" "env" "missing"] "Proxy ENV"))))
    (is (= 1 @calls))))

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

(deftest inferred-docs-tokenizes-selected-labels-once
  (let [inferred-docs @#'context/inferred-docs
        tokenize text/tokenize
        selected-label-text "Selected Entity One Selected Entity Two"
        selected-label-tokenizations (atom 0)
        chunks (mapv (fn [idx]
                       {:xt/id (str "chunk:" idx)
                        :path (str "docs/" idx ".md")
                        :kind :markdown
                        :label (str "Doc " idx)
                        :text "target details"
                        :tokens ["selected" "entity" "target"]
                        :source-line 1})
                     (range 6))]
    (with-redefs [text/tokenize (fn [value]
                                  (when (= selected-label-text (str value))
                                    (swap! selected-label-tokenizations inc))
                                  (tokenize value))]
      (is (= (count chunks)
             (count (inferred-docs
                     ["target"]
                     []
                     chunks
                     [{:label "Selected Entity One"}
                      {:label "Selected Entity Two"}]
                     900)))))
    (is (= 1 @selected-label-tokenizations))))

(deftest inferred-docs-reuses-stored-chunk-tokens
  (let [inferred-docs @#'context/inferred-docs
        tokenize text/tokenize
        body-text "target details with a large body that should already be tokenized"
        body-tokenizations (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (when (.contains (str value) body-text)
                                    (swap! body-tokenizations inc))
                                  (tokenize value))]
      (is (= ["docs/target.md"]
             (mapv #(get-in % [:source :path])
                   (inferred-docs
                    ["target"]
                    []
                    [{:xt/id "chunk:target"
                      :path "docs/target.md"
                      :kind :markdown
                      :label "Target notes"
                      :text body-text
                      :tokens ["target" "details" "large" "body"]
                      :source-line 1}]
                    []
                    900)))))
    (is (zero? @body-tokenizations))))

(deftest inferred-docs-tokenizes-definition-kinds-once-per-kind
  (let [inferred-docs @#'context/inferred-docs
        tokenize text/tokenize
        definition-kind-tokenizations (atom 0)
        chunks (mapv (fn [idx]
                       {:xt/id (str "chunk:" idx)
                        :path (str "docs/" idx ".md")
                        :kind :markdown
                        :definition-kind (if (even? idx) :interface :function)
                        :label (str "Doc " idx)
                        :text "target details"
                        :tokens ["target"]
                        :source-line 1})
                     (range 6))]
    (with-redefs [text/tokenize (fn [value]
                                  (when (#{"interface" "function"} (str value))
                                    (swap! definition-kind-tokenizations inc))
                                  (tokenize value))]
      (is (= (count chunks)
             (count (inferred-docs
                     ["interface" "target"]
                     []
                     chunks
                     []
                     900)))))
    (is (= 2 @definition-kind-tokenizations))))

(deftest chunk-score-uses-precomputed-retrieval-score
  (let [chunk-score @#'context/chunk-score
        score (chunk-score
               ["target"]
               []
               {}
               {:xt/id "chunk:target"
                :path "docs/target.md"
                :kind :markdown
                :label "Target"
                :text "target details"
                :tokens ["target"]
                :retrieval-score 2.5})]
    (is (<= 2.5 score))))

(deftest select-entities-reuses-node-result-match
  (let [select-entities @#'context/select-entities
        match-calls (atom 0)]
    (with-redefs-fn {#'context/result-matches-node? (fn [_result _node]
                                                      (swap! match-calls inc)
                                                      true)}
      (fn []
        (let [entities (select-entities
                        ["target"]
                        [{:target-id "node:target"
                          :score 1.0}]
                        {:nodes [{:id "node:target"
                                  :label "Target"
                                  :kind "candidate-system"}]}
                        5)]
          (is (= ["retrieval and graph match"] (mapv :why entities)))
          (is (= 1 @match-calls)))))))

(deftest source-graph-local-importer-candidates-use-module-alias-facts
  (let [local-importer-candidates @#'context/source-graph-local-importer-candidates
        requested-labels (atom nil)
        aliases [{:kind :module-path-alias
                  :path "site/tsconfig.json"
                  :label "@components/*=src/components/*"
                  :active? true}
                 {:kind :module-path-alias
                  :path "site/tsconfig.json"
                  :label "@layouts/*=src/layouts/*"
                  :active? true}]
        import-nodes [{:xt/id "node:import:themes"
                       :kind :web-framework-import
                       :repo-id "repo"
                       :path "site/src/pages/index.astro"
                       :label "@components/home/Themes.astro"
                       :source-line 10
                       :active? true}
                      {:xt/id "node:import:bs-themes"
                       :kind :web-framework-import
                       :repo-id "repo"
                       :path "site/src/pages/docs/[version]/examples/index.astro"
                       :label "@layouts/partials/BsThemes.astro"
                       :source-line 4
                       :active? true}]
        seeds [{:rank 7
                :target-kind :node
                :target-id "node:theme"
                :path "site/src/components/home/Themes.astro"
                :label "site.src.components.home.Themes"
                :score 1.0}
               {:rank 36
                :target-kind :node
                :target-id "node:bs-theme"
                :path "site/src/layouts/partials/BsThemes.astro"
                :label "site.src.layouts.partials.BsThemes"
                :score 0.9}]]
    (with-redefs [store/xtdb-handle? (constantly true)
                  store/constrained-rows (fn [_xtdb table constraints read-context]
                                           (is (= (:nodes store/tables) table))
                                           (is (= {:project-id "project"
                                                   :repo-id "repo"
                                                   :kind :module-path-alias
                                                   :active? true}
                                                  constraints))
                                           (is (= {:valid-at :now} read-context))
                                           aliases)
                  query/nodes-by-labels (fn [_xtdb labels scope]
                                          (reset! requested-labels (set labels))
                                          (is (= {:project-id "project"
                                                  :repo-id "repo"
                                                  :read-context {:valid-at :now}}
                                                 scope))
                                          import-nodes)]
      (let [rows (local-importer-candidates
                  {:node :xtdb}
                  seeds
                  {:project-id "project"
                   :repo-id "repo"
                   :read-context {:valid-at :now}})]
        (is (= #{"@components/home/Themes.astro"
                 "@layouts/partials/BsThemes.astro"}
               @requested-labels))
        (is (= ["site/src/pages/index.astro"
                "site/src/pages/docs/[version]/examples/index.astro"]
               (mapv :path rows)))
        (is (= [:web-framework-import :web-framework-import]
               (mapv :kind rows)))
        (is (every? #(= :node (:target-kind %)) rows))
        (is (every? #(= :node (:result-kind %)) rows))))))

(deftest select-edges-tokenizes-each-relation-once
  (let [select-edges @#'context/select-edges
        tokenize text/tokenize
        relation-tokenizations (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (when (= "depends-on" (str value))
                                    (swap! relation-tokenizations inc))
                                  (tokenize value))]
      (let [edges (select-edges
                   ["depends"]
                   [{:id "node:a"}]
                   {:edges [{:id "edge:1"
                             :source "node:a"
                             :target "node:b"
                             :relation "depends-on"}
                            {:id "edge:2"
                             :source "node:a"
                             :target "node:c"
                             :relation "depends-on"}]}
                   5)]
        (is (= ["edge:1" "edge:2"] (mapv :id edges)))
        (is (= 1 @relation-tokenizations))))))

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

(deftest diversify-docs-tokenizes-definition-kind-once-per-kind
  (let [diversify-docs @#'context/diversify-docs
        tokenize text/tokenize
        calls (atom 0)
        query-tokens (tokenize "component type")
        docs (mapv (fn [idx]
                     {:target (str "chunk:" idx)
                      :score (- 10 idx)
                      :retrievedSource true
                      :source {:repo "app"
                               :path (str "src/" idx ".clj")
                               :definitionKind (if (even? idx)
                                                 :type
                                                 :function)}})
                   (range 8))]
    (with-redefs [text/tokenize (fn [value]
                                  (swap! calls inc)
                                  (tokenize value))]
      (is (= (count docs)
             (count (diversify-docs query-tokens docs)))))
    (is (= 2 @calls))))

(deftest diversify-doc-row-reuses-root-key
  (let [diversify-doc-row @#'context/diversify-doc-row
        root-key-calls (atom 0)
        doc {:target "chunk:target"
             :source {:repo "app"
                      :path "src/target.clj"
                      :definitionKind :function}}]
    (with-redefs-fn {#'context/doc-root-key (fn [_doc]
                                              (swap! root-key-calls inc)
                                              ["app" "src"])}
      (fn []
        (let [row (diversify-doc-row {:function 0.75} doc)]
          (is (= ["app" "src"] (:root-key row)))
          (is (= [["app" "src"] :function]
                 (:root-definition-kind-key row)))
          (is (= 1 @root-key-calls)))))))

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

(deftest source-graph-candidate-score-does-not-retokenize-token-vector
  (let [source-graph-candidate-score @#'context/source-graph-candidate-score
        tokenize text/tokenize
        tokenize-calls (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (swap! tokenize-calls inc)
                                  (tokenize value))]
      (is (pos? (source-graph-candidate-score
                 ["router" "proxy"]
                 {:path "src/router/proxy.clj"
                  :label "router proxy"
                  :kind :namespace}))))
    (is (zero? @tokenize-calls))))

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
  (is (= [{:kind :query
           :label "Continue graph query"
           :command "ygg query 'billing flow' --project 'fixture project' --json"
           :mcpTool "ygg_query"
           :mcpArgs {:query "billing flow"
                     :projectId "fixture project"}}
          {:kind :systems
           :label "Inspect project systems graph"
           :command "ygg view systems --project 'fixture project'"
           :mcpTool "ygg_systems"
           :mcpArgs {:projectId "fixture project"}}
          {:kind :status
           :label "Inspect graph freshness and evidence status"
           :command "ygg sync inspect <project.edn> --json"
           :mcpTool "ygg_status"}
          {:kind :docs
           :label "Audit accepted documentation attachments"
           :command "ygg sync docs audit --project 'fixture project'"}]
         (#'context/context-drilldowns
          "billing flow"
          "fixture project"))))

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

(deftest retrieval-summary-uses-search-effective-mode
  (is (= {:requested :auto
          :effective :lexical
          :fallback? false
          :autoLexicalShortCircuit true
          :autoLexicalShortCircuitReason :exact-path-candidates}
         (#'context/retrieval-summary
          {:retriever :auto
           :retriever-effective :lexical
           :embedding-client {:provider :fake}
           :auto-lexical-short-circuit? true
           :auto-lexical-short-circuit-reason :exact-path-candidates})))
  (is (= {:requested :auto
          :effective :lexical
          :fallback? true
          :reason "No embedding client was available."}
         (#'context/retrieval-summary
          {:retriever :auto
           :retriever-effective :lexical}))))

(deftest evidence-coverage-actions-include-status-mcp
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
                 nil)]
    (is (= [{:kind :coverage
             :label "Inspect extractor diagnostics"
             :count 2
             :command "ygg sync coverage <project.edn> --json"
             :mcpTool "ygg_status"}
            {:kind :coverage
             :label "Inspect skipped source candidates"
             :count 3
             :command "ygg sync coverage <project.edn> --json"
             :pluginRegistryCommand "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>"
             :pluginScaffoldCommand "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>"
             :pluginGapCommand "bb plugin gap extractor <package-dir> <repo-root> <file> --json"
             :mcpTool "ygg_status"}]
           (filterv #(= :coverage (:kind %)) actions)))))

(deftest evidence-surfaces-stale-freshness
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        freshness {:status :stale
                   :nextActions [{:kind :freshness
                                  :label "Refresh indexed graph basis"
                                  :count 2
                                  :command "ygg sync project.edn --check"}]}
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

(deftest evidence-surfaces-active-indexing-degradation
  (let [retrieval {:requested :auto
                   :effective :hybrid
                   :fallback? false}
        degradation (#'context/indexing-degradation
                     {:schema "ygg.server.active-operation/v1"
                      :op "sync"
                      :projectId "fixture"
                      :lockKey "project:fixture"
                      :elapsedMs 250})
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
        warnings (#'context/evidence-warnings counts retrieval [] nil degradation)]
    (is (= :active-indexing (:reason degradation)))
    (is (= :limited
           (#'context/evidence-readiness-status
            []
            []
            retrieval
            {:entity-count 1
             :doc-count 1
             :activity-count 1}
            nil
            degradation)))
    (is (some #{"Query results are degraded because indexing is still running; rerun after the active operation finishes for complete evidence."}
              warnings))))

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
  (let [search-opts (atom nil)]
    (with-redefs [query/search-report (fn [_ query-text opts]
                                        (reset! search-opts opts)
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
                                                     :correction-systems 1
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
                                            :fusion-strategy :rrf
                                            :sqlite-fts? true
                                            :diversity-rerank-limit 5
                                            :fts-candidate-limit 80
                                            :fts-weight 0.1
                                            :embedding-role :content
                                            :plugins {:packages [plugin-package-fixture]}
                                            :freshness {:status :current
                                                        :counts {:indexed 1
                                                                 :current 1
                                                                 :changed 0}}})]
        (is (= "query:test" (get-in packet [:search :query-run-id])))
        (is (= {:limit context/default-retrieval-limit
                :retriever :lexical
                :project-id "fixture"
                :repo-id nil
                :read-context nil
                :fusion-strategy :rrf
                :sqlite-fts? true
                :diversity-rerank-limit 5
                :fts-candidate-limit 80
                :fts-weight 0.1
                :embedding-role :content
                :embedding-roles nil}
               (dissoc @search-opts :embedding-client)))
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
                            :correctionSystems 1
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
               (:candidateFiles packet)))))))

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
                                               :source "correction-overlay"}
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
                   :correction-overlay {:systems [{:id "system:billing"
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
      (is (= [{:kind "correction-reject"
               :id "correction-reject:1"
               :status "rejected"
               :provenance "correction-overlay"
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
               :topEvidenceTypes [{:kind "correction-reject"
                                   :count 1}]}
              {:kind "dependencies"
               :basis "selected-architecture-evidence"
               :facts 2
               :files 2
               :topEvidenceTypes [{:kind "imports-package"
                                   :count 1}
                                  {:kind "unresolved-import"
                                   :count 1}]}
              {:kind "dependency-runtime"
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
      (is (= [{:id "correction-reject:1"
               :kind "correction-reject"
               :status "rejected"
               :provenance "correction-overlay"
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
                   :correction-overlay {:systems [{:id "system:billing"
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
      (is (= ["src/billing/api.clj" "config/runtime.env"]
             (mapv :path (:candidateFiles packet)))))))

(deftest context-packet-loads-runtime-evidence-from-bounded-keys
  (let [calls (atom [])]
    (with-redefs [query/search-report (fn [_ query-text opts]
                                        {:schema query/search-report-schema
                                         :query-run-id "query:bounded-runtime"
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
                  query/chunks-by-paths (fn [& _] [])
                  query/system-evidence-by-system-ids
                  (fn [_ system-ids opts]
                    (swap! calls conj [:system-ids system-ids opts])
                    [{:xt/id "evidence:billing-system"
                      :system-id "system:billing"
                      :repo-id "app"
                      :path "config/runtime.env"
                      :file-kind :env
                      :kind :env-var
                      :label "DATABASE_URL"
                      :normalized-value "database-url"
                      :source-line 3
                      :confidence 1.0
                      :active? true}])
                  query/system-evidence-by-paths
                  (fn [_ paths opts]
                    (swap! calls conj [:paths paths opts])
                    [{:xt/id "evidence:billing-path"
                      :system-id "system:path"
                      :repo-id "app"
                      :path "src/billing/api.clj"
                      :file-kind :clojure
                      :kind :url
                      :label "billing api endpoint"
                      :source-line 5
                      :confidence 0.8
                      :active? true}])
                  query/all-system-evidence
                  (fn [& _]
                    (throw (ex-info "all-system-evidence should not be used"
                                    {})))
                  dependency/package-report (fn [& _] (empty-dependency-report))
                  activity/select-activity (fn [& _] [])
                  context/query-evidence (fn [& _] {:status :ready})
                  coverage/context-summary (fn [& _] nil)]
      (let [packet (context/context-packet
                    :xtdb
                    "billing database api"
                    {:project-id "fixture"
                     :repo-id "app"
                     :retriever :lexical
                     :correction-overlay {:systems [{:id "system:billing"
                                                     :label "Billing"
                                                     :repo "app"
                                                     :includes [{:repo "app"
                                                                 :path "src/billing"}]}]}})]
        (is (= [[:system-ids ["system:billing"]]
                [:paths ["src/billing/api.clj"]]]
               (mapv (fn [[kind values _opts]] [kind values]) @calls)))
        (is (= [{:project-id "fixture"
                 :repo-id "app"
                 :read-context nil}
                {:project-id "fixture"
                 :repo-id "app"
                 :read-context nil}]
               (mapv (fn [[_kind _values opts]] opts) @calls)))
        (is (= ["evidence:billing-path" "evidence:billing-system"]
               (mapv :id (get-in packet [:architecture :runtimeEvidence]))))))))

(deftest selected-system-evidence-loads-candidate-input-paths
  (let [calls (atom [])]
    (with-redefs [query/system-evidence-by-system-ids
                  (fn [_ system-ids opts]
                    (swap! calls conj [:system-ids system-ids opts])
                    [])
                  query/system-evidence-by-paths
                  (fn [_ paths opts]
                    (swap! calls conj [:paths paths opts])
                    [{:xt/id "evidence:source-candidate"
                      :path "src/source-candidate.clj"
                      :active? true}])
                  query/all-system-evidence
                  (fn [& _]
                    (throw (ex-info "all-system-evidence should not be used"
                                    {})))]
      (let [rows (#'context/selected-system-evidence
                  :xtdb
                  []
                  [{:path "src/retrieved.clj"}
                   {:path "src/source-candidate.clj"}
                   {:path ""}
                   {}]
                  {:project-id "fixture"
                   :repo-id "app"})]
        (is (= [[:paths
                 ["src/retrieved.clj" "src/source-candidate.clj"]
                 {:project-id "fixture"
                  :repo-id "app"}]]
               @calls))
        (is (= ["evidence:source-candidate"]
               (mapv :xt/id rows)))))))

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

(deftest architecture-evidence-candidate-file-rows-carry-runtime-config-paths
  (let [rows (#'context/architecture-candidate-file-rows
              {:runtimeEvidence [{:id "evidence:postgres-db"
                                  :repo "dapper"
                                  :path "tests/docker-compose.yml"
                                  :fileKind "compose"
                                  :kind "env-var"
                                  :label "POSTGRES_DB"
                                  :normalizedValue "postgres-db"
                                  :sourceLine 19
                                  :score 1.4}]
               :deployEvidence [{:id "evidence:postgres-image"
                                 :repo "dapper"
                                 :path "tests/docker-compose.yml"
                                 :fileKind "compose"
                                 :kind "container-image-consumer"
                                 :label "postgres:alpine"
                                 :normalizedValue "container-image:postgres"
                                 :sourceLine 12
                                 :score 5.15}]
               :dependencyEvidence [{:id "evidence:npgsql"
                                     :repo "dapper"
                                     :path "tests/Dapper.Tests/Dapper.Tests.csproj"
                                     :fileKind "manifest"
                                     :kind "external-package"
                                     :label "nuget:Npgsql"
                                     :sourceLine 1
                                     :score 3.2}]
               :boundaryEvidence [{:id "evidence:ignored"
                                   :path "tests/ignored.yml"
                                   :fileKind "compose"
                                   :kind "container-image-consumer"
                                   :label "ignored"
                                   :score 0.0}]}
              10)
        by-path (into {} (map (juxt :path identity)) rows)
        compose-row (get by-path "tests/docker-compose.yml")
        package-row (get by-path "tests/Dapper.Tests/Dapper.Tests.csproj")]
    (is (= ["tests/docker-compose.yml"
            "tests/docker-compose.yml"
            "tests/Dapper.Tests/Dapper.Tests.csproj"]
           (mapv :path rows)))
    (is (= "compose" (:kind compose-row)))
    (is (= "file" (:targetKind compose-row)))
    (is (true? (:architectureEvidence compose-row)))
    (is (= "deployEvidence" (:architectureSection compose-row)))
    (is (= "container-image-consumer" (:architectureKind compose-row)))
    (is (= 5.15 (get-in compose-row [:scoreComponents :sourceGraph])))
    (is (some #{"container-image-consumer"} (:supportLabels compose-row)))
    (is (= "manifest" (:kind package-row)))
    (is (not (contains? by-path "tests/ignored.yml")))))

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

(deftest runtime-evidence-preserves-lower-ranked-candidate-path-coverage
  (let [noise-results (mapv (fn [idx]
                              {:repo-id "app"
                               :path (str "noise/path-" idx ".sql")
                               :score (- 10.0 idx)})
                            (range 1 25))
        runtime-evidence (#'context/select-system-evidence
                          ["trigger" "owner"]
                          []
                          (conj noise-results
                                {:repo-id "app"
                                 :path "db/init.sql"
                                 :score 1.0})
                          [{:xt/id "evidence:noise-a"
                            :system-id "system:noise"
                            :repo-id "app"
                            :path "noise/a.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "trigger owner setup"
                            :normalized-value "trigger-owner-setup"
                            :source-line 10
                            :confidence 0.68}
                           {:xt/id "evidence:noise-b"
                            :system-id "system:noise"
                            :repo-id "app"
                            :path "noise/b.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "trigger owner helper"
                            :normalized-value "trigger-owner-helper"
                            :source-line 11
                            :confidence 0.68}
                           {:xt/id "evidence:init-security"
                            :system-id "system:db"
                            :repo-id "app"
                            :path "db/init.sql"
                            :file-kind :sql
                            :kind :sql-security
                            :label "SECURITY DEFINER"
                            :normalized-value "security-definer"
                            :source-line 12
                            :confidence 0.68}]
                          2)]
    (is (contains? (set (map :id runtime-evidence))
                   "evidence:init-security"))))

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

(deftest runtime-evidence-preserves-rare-query-matched-facts
  (let [results [{:repo-id "app"
                  :path "tests/unit/axiosHeaders.test.js"
                  :score 2.0}
                 {:repo-id "app"
                  :path "docs/site.webmanifest"
                  :score 1.9}
                 {:repo-id "app"
                  :path "package.json"
                  :score 1.8}]
        runtime-evidence (#'context/select-system-evidence
                          ["axios" "native" "proxy" "environment" "variable"]
                          ["system:repo"]
                          results
                          [{:xt/id "evidence:axios-issue"
                            :system-id "system:repo"
                            :repo-id "app"
                            :path "tests/unit/axiosHeaders.test.js"
                            :file-kind :javascript
                            :kind :url
                            :label "https://github.com/axios/axios/issues/10849"
                            :normalized-value "https-github-com-axios-axios-issues-10849"
                            :source-line 220
                            :confidence 0.7}
                           {:xt/id "evidence:axios-package"
                            :system-id "system:repo"
                            :repo-id "app"
                            :path "package.json"
                            :file-kind :json
                            :kind :url
                            :label "https://github.com/axios/axios.git"
                            :normalized-value "https-github-com-axios-axios-git"
                            :source-line 64
                            :confidence 0.7}
                           {:xt/id "evidence:axios-manifest"
                            :system-id "system:repo"
                            :repo-id "app"
                            :path "docs/site.webmanifest"
                            :file-kind :json
                            :kind :route
                            :label "/android-chrome-192x192.png"
                            :normalized-value "android-chrome-192x192-png"
                            :source-line 6
                            :confidence 0.7}
                           {:xt/id "evidence:proxy-port"
                            :system-id "system:repo"
                            :repo-id "app"
                            :path "tests/unit/adapters/http.test.js"
                            :file-kind :javascript
                            :kind :env-var
                            :label "PROXY_PORT"
                            :normalized-value "proxy-port"
                            :source-line 37
                            :confidence 0.7}]
                          3)
        scores-by-id (into {} (map (juxt :id :score) runtime-evidence))]
    (is (contains? (set (map :id runtime-evidence))
                   "evidence:proxy-port"))
    (is (> (get scores-by-id "evidence:proxy-port")
           (get scores-by-id "evidence:axios-manifest")))))

(deftest dependency-evidence-includes-query-matched-package-import-sources
  (let [dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 [{:id "node:pkg:proxy-from-env"
                                   :repo-id "app"
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
             :repo "app"
             :path "lib/adapters/http.js"
             :sourceLine 5
             :fileKind "javascript"
             :importName "proxy-from-env"
             :resolutionSource "declared"}]
           (mapv #(dissoc % :score) rows)))
    (is (< 1.0 (:score (first rows))))))

(deftest dependency-evidence-includes-query-matched-package-declarations
  (let [dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 [{:id "node:pkg:astro"
                                   :repo-id "app"
                                   :package-name "astro"
                                   :label "npm:astro"
                                   :ecosystem :npm
                                   :declared-by [{:id "node:manifest:package"
                                                  :path "package.json"
                                                  :line 42
                                                  :repo-id "app"
                                                  :dependency-scope :dev}]}
                                  {:id "node:pkg:noise"
                                   :repo-id "app"
                                   :package-name "noise"
                                   :label "npm:noise"
                                   :ecosystem :npm
                                   :declared-by [{:id "node:manifest:package"
                                                  :path "package.json"
                                                  :line 43
                                                  :repo-id "app"}]}])
        rows (#'context-architecture/dependency-report-evidence
              ["astro" "docs"]
              [{:path "package.json"
                :repo "app"}]
              []
              []
              dependency-report)]
    (is (= [{:kind "declared-package"
             :id "node:pkg:astro:declared:package.json:42"
             :packageId "node:pkg:astro"
             :package "astro"
             :label "npm:astro"
             :candidateLabel "npm:astro"
             :ecosystem "npm"
             :relation "declares-package"
             :path "package.json"
             :sourceLine 42
             :repo "app"
             :dependencyScope "dev"}]
           (mapv #(dissoc % :score) rows)))
    (is (< 0.75 (:score (first rows))))))

(deftest dependency-report-evidence-normalizes-query-tokens-once
  (let [dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 [{:id "node:pkg:proxy-from-env"
                                   :package-name "proxy-from-env"
                                   :label "npm:proxy-from-env"
                                   :ecosystem :npm
                                   :imported-by [{:path "lib/adapters/http.js"
                                                  :line 5
                                                  :kind :javascript
                                                  :import-name "proxy-from-env"}]}])
        distinct-query-tokens @#'context-architecture/distinct-query-tokens
        calls (atom 0)]
    (with-redefs-fn {#'context-architecture/distinct-query-tokens
                     (fn [query-tokens]
                       (swap! calls inc)
                       (distinct-query-tokens query-tokens))}
      (fn []
        (let [rows (#'context-architecture/dependency-report-evidence
                    ["proxy" "proxy" "env"]
                    []
                    []
                    []
                    dependency-report)]
          (is (= 1 @calls))
          (is (= ["lib/adapters/http.js"] (mapv :path rows)))
          (is (= 1.5 (:score (first rows)))))))))

(deftest dependency-evidence-preserves-selected-package-import-source
  (let [dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 [{:id "node:pkg:proxy-lib"
                                   :package-name "proxy-lib"
                                   :label "npm:proxy-lib"
                                   :ecosystem :npm
                                   :imported-by (vec
                                                 (concat
                                                  [{:path "src/core.js"
                                                    :line 5
                                                    :kind :javascript
                                                    :import-name "proxy-lib"}]
                                                  (for [idx (range 1 6)]
                                                    {:path (str "tests/proxy-test-"
                                                                idx
                                                                ".js")
                                                     :line idx
                                                     :kind :javascript
                                                     :import-name "proxy-lib"})))}])
        rows (#'context-architecture/dependency-report-evidence
              ["proxy" "test"]
              [{:path "src/core.js"}]
              []
              []
              dependency-report)]
    (is (= 4 (count rows)))
    (is (some #{"src/core.js"} (map :path rows)))))

(deftest architecture-dependency-evidence-preserves-selected-path-through-limit
  (let [target-package {:id "node:pkg:proxy-lib"
                        :package-name "proxy-lib"
                        :label "npm:proxy-lib"
                        :ecosystem :npm
                        :imported-by [{:path "src/core.js"
                                       :line 5
                                       :kind :javascript
                                       :import-name "proxy-test-lib"}
                                      {:path "tests/proxy-test-target-1.js"
                                       :line 1
                                       :kind :javascript
                                       :import-name "proxy-lib"}
                                      {:path "tests/proxy-test-target-2.js"
                                       :line 2
                                       :kind :javascript
                                       :import-name "proxy-lib"}
                                      {:path "tests/proxy-test-target-3.js"
                                       :line 3
                                       :kind :javascript
                                       :import-name "proxy-lib"}]}
        noise-packages (mapv
                        (fn [pkg-idx]
                          {:id (str "node:pkg:proxy-test-lib-" pkg-idx)
                           :package-name (str "proxy-test-lib-" pkg-idx)
                           :label (str "npm:proxy-test-lib-" pkg-idx)
                           :ecosystem :npm
                           :imported-by (mapv
                                         (fn [source-idx]
                                           {:path (str "tests/proxy-test-"
                                                       pkg-idx
                                                       "-"
                                                       source-idx
                                                       ".js")
                                            :line source-idx
                                            :kind :javascript
                                            :import-name (str "proxy-test-lib-"
                                                              pkg-idx)})
                                         (range 1 5))})
                        (range 1 4))
        dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 (vec (cons target-package noise-packages)))
        section (context-architecture/architecture-section
                 {:overlay {}
                  :entities []
                  :results []
                  :candidate-inputs [{:path "src/core.js"}]
                  :edges []
                  :runtime-evidence []
                  :dependency-report dependency-report
                  :docs []
                  :activity []
                  :evidence {:warnings []}
                  :freshness {:warnings []}
                  :accepted-systems []
                  :query-tokens ["proxy" "test"]})]
    (is (= 8 (count (:dependencyEvidence section))))
    (is (some #{"src/core.js"} (map :path (:dependencyEvidence section))))))

(deftest architecture-dependency-evidence-preserves-query-package-identity-through-limit
  (let [target-package {:id "node:pkg:proxy-from-env"
                        :package-name "proxy-from-env"
                        :label "npm:proxy-from-env"
                        :ecosystem :npm
                        :imported-by [{:path "lib/adapters/http.js"
                                       :line 5
                                       :kind :javascript
                                       :import-name "proxy-from-env"}]}
        noise-packages (mapv
                        (fn [idx]
                          (let [path (str "tests/proxy-env-noise-"
                                          idx
                                          ".test.js")]
                            {:id (str "node:pkg:noise-" idx)
                             :package-name (str "noise-" idx)
                             :label (str "npm:noise-" idx)
                             :ecosystem :npm
                             :imported-by [{:path path
                                            :line idx
                                            :kind :javascript
                                            :import-name (str "noise-" idx)}]}))
                        (range 1 12))
        dependency-report (assoc (empty-dependency-report)
                                 :packages
                                 (vec (cons target-package noise-packages)))
        section (context-architecture/architecture-section
                 {:overlay {}
                  :entities []
                  :results []
                  :candidate-inputs (mapv (fn [idx]
                                            {:path (str "tests/proxy-env-noise-"
                                                        idx
                                                        ".test.js")})
                                          (range 1 12))
                  :edges []
                  :runtime-evidence []
                  :dependency-report dependency-report
                  :docs []
                  :activity []
                  :evidence {:warnings []}
                  :freshness {:warnings []}
                  :accepted-systems []
                  :query-tokens ["proxy" "env" "test"]})
        dependency-evidence (:dependencyEvidence section)]
    (is (= 8 (count dependency-evidence)))
    (is (some #(and (= "lib/adapters/http.js" (:path %))
                    (= "proxy-from-env" (:package %))
                    (= "proxy-from-env" (:importName %)))
              dependency-evidence))))

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

(deftest candidate-files-merge-nested-score-components
  (let [rows (#'context/candidate-files
              [{:path "src/caller.clj"
                :rank 1
                :score 0.8
                :target-kind :node
                :label "demo/caller"
                :score-components {:semantic 0.5
                                   :semanticRoles {:content 0.4}}}
               {:path "src/caller.clj"
                :rank 2
                :score 0.7
                :target-kind :node
                :label "demo/caller.helper"
                :score-components {:semantic 0.7
                                   :semanticRoles {:content 0.2
                                                   :symbol 0.9}}}])]
    (is (= [{:path "src/caller.clj"
             :rank 1
             :score 0.8
             :targetKind "node"
             :label "demo/caller"
             :scoreComponents {:semantic 0.7
                               :semanticRoles {:content 0.4
                                               :symbol 0.9}}}]
           rows))))

(deftest candidate-files-prioritize-duplicate-primary-labels-before-support-labels
  (let [rows (#'context/candidate-files
              [{:path "package.json"
                :rank 1
                :score 0.9
                :target-kind :node
                :label "bootstrap"
                :kind :manifest
                :source-line 1
                :supportLabels ["docs-serve=npm run astro-dev"
                                "docs-build=npm run astro-build"
                                "astro-dev"
                                "astro-build"]}
               {:path "package.json"
                :rank 8
                :score 0.6
                :target-kind :node
                :label "npm:astro"
                :kind :external-package
                :source-line 42
                :supportLabels ["declared-package"
                                "declares-package"]}])]
    (is (= [{:path "package.json"
             :rank 1
             :score 0.9
             :targetKind "node"
             :label "bootstrap"
             :kind "manifest"
             :sourceLine 1
             :supportLabels ["npm:astro"
                             "docs-serve=npm run astro-dev"
                             "docs-build=npm run astro-build"
                             "astro-dev"]}]
           rows))))

(deftest candidate-files-expand-indexed-same-stem-siblings
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table fields tokens constraints ctx]
                  (is (= :ygg/files table))
                  (is (= [:path] fields))
                  (is (contains? (set tokens) "sqlmapper"))
                  (is (= {:project-id "fixture"
                          :repo-id "dapper"
                          :active? true}
                         constraints))
                  (is (= {:valid-at "t"} ctx))
                  [{:xt/id "file:mapper"
                    :project-id "fixture"
                    :repo-id "dapper"
                    :path "Dapper/SqlMapper.cs"
                    :kind :dotnet
                    :active? true}
                   {:xt/id "file:mapper-settings"
                    :project-id "fixture"
                    :repo-id "dapper"
                    :path "Dapper/SqlMapper.Settings.cs"
                    :kind :dotnet
                    :active? true}
                   {:xt/id "file:mapper-handler"
                    :project-id "fixture"
                    :repo-id "dapper"
                    :path "Dapper/SqlMapper.ITypeHandler.cs"
                    :kind :dotnet
                    :active? true}
                   {:xt/id "file:other"
                    :project-id "fixture"
                    :repo-id "dapper"
                    :path "Dapper/Other.Settings.cs"
                    :kind :dotnet
                    :active? true}])]
    (let [rows (#'context/expand-candidate-file-siblings
                :xtdb
                (text/tokenize-all "settings")
                [{:path "Dapper/SqlMapper.cs"
                  :rank 1
                  :score 1.0
                  :targetKind "chunk"
                  :label "Dapper/SqlMapper.ResetTypeHandlers"
                  :repo "dapper"
                  :supportLabels ["Dapper/SqlMapper.AddTypeHandlerCore"
                                  "Dapper/SqlMapper.HasTypeHandler"]
                  :scoreComponents {:sourceGraph 0.6
                                    :lexical 0.9}}
                 {:path "Dapper/Noise.cs"
                  :rank 2
                  :score 0.8
                  :targetKind "file"
                  :label "Dapper/Noise"
                  :repo "dapper"}]
                {:project-id "fixture"
                 :repo-id "dapper"
                 :read-context {:valid-at "t"}})
          by-path (into {} (map (juxt :path identity)) rows)
          settings (get by-path "Dapper/SqlMapper.Settings.cs")]
      (is (= ["Dapper/SqlMapper.cs"
              "Dapper/SqlMapper.Settings.cs"
              "Dapper/SqlMapper.ITypeHandler.cs"
              "Dapper/Noise.cs"]
             (mapv :path rows)))
      (is (= [1 2 3 4] (mapv :rank rows)))
      (is (= "file" (:targetKind settings)))
      (is (= "dotnet" (:kind settings)))
      (is (= ["Dapper/SqlMapper.cs"
              "Dapper/SqlMapper.ResetTypeHandlers"
              "Dapper/SqlMapper.AddTypeHandlerCore"
              "Dapper/SqlMapper.HasTypeHandler"]
             (:supportLabels settings)))
      (is (= {:sourceGraph 0.6} (:scoreComponents settings)))
      (is (str/includes? (:reason settings)
                         "same-stem indexed file sibling")))))

(deftest context-packet-compact-output-uses-path-dictionary
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1
                                                         :grep-status :ok
                                                         :grep-candidates 1}
                                       :results [{:path "src/caller.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:caller"
                                                  :label "demo/caller"
                                                  :source-line 7
                                                  :reason "literal grep match"
                                                  :score-components {:grep 1.0
                                                                     :graph 0.25}}]})
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
                                          :retriever :lexical
                                          :output :compact
                                          :query-input {:task :locate
                                                        :anchors ["src/caller.clj:7"]
                                                        :changed-only? false}})]
      (is (= context/compact-schema (:schema packet)))
      (is (= {:task :locate
              :anchors ["src/caller.clj:7"]}
             (:input packet)))
      (is (= {"p1" "src/caller.clj"} (:paths packet)))
      (is (= [{:path "p1"
               :resolvedPath "src/caller.clj"
               :rank 1
               :sourceRank 1
               :line 7
               :score 0.8
               :kind "node"
               :label "demo/caller"
               :why [:grep :graph]
               :reason "literal grep match"}]
             (:results packet)))
      (is (= {:requested :auto
              :mode :lexical
              :used [:grep :graph]}
             (:lanes packet)))
      (is (not (contains? packet :actions)))
      (is (not (contains? packet :candidateFiles)))
      (is (not (contains? packet :docs))))))

(deftest context-packet-compact-output-ranks-results-by-visible-score
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 2
                                                         :returned-count 2}
                                       :results [{:path "docs/backlog.md"
                                                  :score 0.2
                                                  :target-kind :chunk
                                                  :target-id "chunk:backlog"
                                                  :label "backlog note"
                                                  :reason "lexical match"
                                                  :score-components {:lexical 0.2}}
                                                 {:path "src/runtime.clj"
                                                  :score 1.4
                                                  :target-kind :node
                                                  :target-id "node:runtime"
                                                  :label "demo.runtime/start"
                                                  :source-line 12
                                                  :reason "literal grep match"
                                                  :score-components {:grep 1.0
                                                                     :exact 0.4}}]})
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
                                         "runtime"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (= ["src/runtime.clj" "docs/backlog.md"]
             (mapv :resolvedPath (:results packet))))
      (is (= [1 2] (mapv :rank (:results packet))))
      (is (= [2 1] (mapv :sourceRank (:results packet)))))))

(deftest context-packet-compact-output-reserves-grep-backed-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 12
                                                         :returned-count 12}
                                       :results (concat
                                                 (mapv (fn [idx]
                                                         {:path (format "docs/noise_%02d.md" idx)
                                                          :score (- 2.0 (* idx 0.01))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:noise:" idx)
                                                          :label (str "noise " idx)
                                                          :score-components {:lexical 1.0}})
                                                       (range 12))
                                                 [{:path "src/exact.clj"
                                                   :score 0.4
                                                   :target-kind :chunk
                                                   :target-id "chunk:exact"
                                                   :label "exact literal"
                                                   :score-components {:grep 1.0}}])})
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
                                         "exact"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (some #{"src/exact.clj"} (map :resolvedPath (:results packet))))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-reserves-lower-ranked-grep-backed-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 16
                                                         :returned-count 16}
                                       :results (concat
                                                 (mapv (fn [idx]
                                                         {:path (format "docs/noise_%02d.md" idx)
                                                          :score (- 2.0 (* idx 0.01))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:noise:" idx)
                                                          :label (str "noise " idx)
                                                          :score-components {:lexical 1.0}})
                                                       (range 12))
                                                 (mapv (fn [idx]
                                                         {:path (format "src/literal_%02d.clj" idx)
                                                          :score (- 0.4 (* idx 0.05))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:literal:" idx)
                                                          :label (str "literal " idx)
                                                          :score-components {:grep (- 1.0 (* idx 0.1))}})
                                                       (range 4)))})
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
                                         "literal"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (some #{"src/literal_03.clj"} (map :resolvedPath (:results packet))))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-reserves-query-matched-path-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 12
                                                         :returned-count 12}
                                       :results (concat
                                                 (mapv (fn [idx]
                                                         {:path (format "docs/noise_%02d.md" idx)
                                                          :score (- 2.0 (* idx 0.01))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:noise:" idx)
                                                          :label (str "noise " idx)
                                                          :score-components {:lexical 1.0}})
                                                       (range 12))
                                                 [{:path "src/login.html"
                                                   :score 0.4
                                                   :target-kind :chunk
                                                   :target-id "chunk:login"
                                                   :label "login template"
                                                   :score-components {:sourceGraph 0.4}}])})
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
                                         "login view styles"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (some #{"src/login.html"} (map :resolvedPath (:results packet))))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-reserves-shaped-label-token-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 12
                                                         :returned-count 12}
                                       :results (concat
                                                 (mapv (fn [idx]
                                                         {:path (format "docs/noise_%02d.md" idx)
                                                          :score (- 2.0 (* idx 0.01))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:noise:" idx)
                                                          :label (str "noise " idx)
                                                          :score-components {:lexical 1.0}})
                                                       (range 12))
                                                 [{:path "src/homology.py"
                                                   :score 0.4
                                                   :target-kind :node
                                                   :target-id "node:betti"
                                                   :label "pytop.homology"
                                                   :supportLabels ["pytop.homology/betti_numbers"]
                                                   :score-components {:sourceGraph 0.4}}])})
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
                                         "betti_numbers return type"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (some #{"src/homology.py"} (map :resolvedPath (:results packet))))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-reserves-exact-label-token-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 12
                                                         :returned-count 12}
                                       :results (concat
                                                 (mapv (fn [idx]
                                                         {:path (format "docs/noise_%02d.md" idx)
                                                          :score (- 2.0 (* idx 0.01))
                                                          :target-kind :chunk
                                                          :target-id (str "chunk:noise:" idx)
                                                          :label (str "noise " idx)
                                                          :score-components {:lexical 1.0}})
                                                       (range 12))
                                                 [{:path "src/pytop/__init__.py"
                                                   :score 0.4
                                                   :target-kind :node
                                                   :target-id "node:pytop"
                                                   :label "pytop"
                                                   :score-components {:sourceGraph 0.4}}])})
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
                                         "python -m pytop --version"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})]
      (is (some #{"src/pytop/__init__.py"} (map :resolvedPath (:results packet))))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-boosts-structured-exact-label-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 2
                                                         :returned-count 2}
                                       :results [{:path "docs/version.md"
                                                  :score 1.2
                                                  :target-kind :chunk
                                                  :target-id "chunk:version"
                                                  :label "version banner notes"
                                                  :score-components {:lexical 1.2}}
                                                 {:path "src/pytop/__init__.py"
                                                  :score 0.4
                                                  :target-kind :node
                                                  :target-id "node:pytop"
                                                  :label "pytop"
                                                  :score-components {:sourceGraph 0.4}}]})
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
                                         "python -m pytop --version"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})
          first-result (first (:results packet))]
      (is (= "src/pytop/__init__.py" (:resolvedPath first-result)))
      (is (= [:source-graph :query-label] (:why first-result)))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-boosts-structured-shaped-label-results
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 2
                                                         :returned-count 2}
                                       :results [{:path "docs/persistence.md"
                                                  :score 1.1
                                                  :target-kind :chunk
                                                  :target-id "chunk:persistence"
                                                  :label "persistence entropy notes"
                                                  :score-components {:lexical 1.1}}
                                                 {:path "src/pytop/persistence_distances.py"
                                                  :score 0.4
                                                  :target-kind :node
                                                  :target-id "node:entropy"
                                                  :label "pytop.persistence_distances/persistence_entropy"
                                                  :score-components {:sourceGraph 0.4}}]})
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
                                         "persistence_entropy regression"
                                         {:project-id "fixture"
                                          :retriever :lexical
                                          :output :compact})
          first-result (first (:results packet))]
      (is (= "src/pytop/persistence_distances.py" (:resolvedPath first-result)))
      (is (= [:source-graph :query-label] (:why first-result)))
      (is (= (sort > (map :score (:results packet)))
             (map :score (:results packet)))))))

(deftest context-packet-compact-output-preserves-results-before-full-budget-trimming
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :ranked-count 1
                                                         :returned-count 1}
                                       :results [{:path "src/caller.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:caller"
                                                  :label "demo/caller"
                                                  :source-line 7
                                                  :reason "literal grep match"
                                                  :score-components {:grep 1.0}}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes (mapv (fn [idx]
                                                     {:id (str "system:" idx)
                                                      :label (apply str
                                                                    "Verbose system "
                                                                    idx
                                                                    " "
                                                                    (repeat 200 "x"))
                                                      :kind :candidate-system})
                                                   (range 80))
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
                                          :retriever :lexical
                                          :output :compact
                                          :budget 200})]
      (is (= 1 (get-in packet [:search :instrumentation :returned-count])))
      (is (= ["src/caller.clj"] (mapv :resolvedPath (:results packet)))))))

(deftest context-packet-proof-commands-are-opt-in-grep-actions
  (with-redefs [query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :retriever-requested :auto
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "src/caller.clj"
                                                  :score 0.8
                                                  :target-kind :node
                                                  :target-id "node:caller"
                                                  :label "demo/caller"
                                                  :source-line 7
                                                  :score-components {:grep 1.0}}]})
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
                                          :retriever :lexical
                                          :output :compact
                                          :proof-commands? true})]
      (is (= [{:kind :grep
               :cmd "rg -n --fixed-strings -e demo/caller -e caller -- src/caller.clj"}]
             (:actions packet))))))

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
                        :active? true}])

                    :ygg/file-facts
                    (do
                      (is (= [:path :file-kind :kind :label :normalized-value]
                             fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"
                              :active? true}
                             constraints))
                      [])))
                query/edges-touching-node-ids (fn [& _] [])]
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

(deftest source-graph-candidates-surface-query-matched-file-facts
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table fields tokens constraints ctx]
                  (is (= {:valid-at "t"} ctx))
                  (is (= #{"reference" "package" "dependency" "dapper"}
                         (set tokens)))
                  (case table
                    :ygg/nodes
                    (do
                      (is (= [:path :label :name :kind] fields))
                      [])

                    :ygg/files
                    (do
                      (is (= [:path :kind] fields))
                      [])

                    :ygg/file-facts
                    (do
                      (is (= [:path :file-kind :kind :label :normalized-value]
                             fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"
                              :active? true}
                             constraints))
                      [{:xt/id "fact:central-package"
                        :project-id "fixture"
                        :repo-id "app"
                        :file-id "file:directory-packages"
                        :path "Directory.Packages.props"
                        :file-kind :manifest
                        :kind :package-version
                        :label "PackageVersion Dapper.Contrib"
                        :normalized-value "dapper-contrib"
                        :source-line 20
                        :active? true}
                       {:xt/id "fact:performance-package"
                        :project-id "fixture"
                        :repo-id "app"
                        :file-id "file:performance"
                        :path "benchmarks/Dapper.Tests.Performance/Dapper.Tests.Performance.csproj"
                        :file-kind :manifest
                        :kind :package-dependency
                        :label "PackageReference BenchmarkDotNet"
                        :normalized-value "benchmarkdotnet"
                        :source-line 15
                        :active? true}])))
                query/edges-touching-node-ids (fn [& _] [])]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "reference package dependency dapper")
                {:project-id "fixture"
                 :repo-id "app"
                 :read-context {:valid-at "t"}})
          candidates (->> rows
                          (filter #(= :file-fact (:target-kind %)))
                          (map #(select-keys % [:path
                                                :target-kind
                                                :result-kind
                                                :label
                                                :kind
                                                :reason
                                                :source-line
                                                :repo-id
                                                :repo]))
                          vec)]
      (is (= #{{:path "Directory.Packages.props"
                :target-kind :file-fact
                :result-kind :file
                :label "PackageVersion Dapper.Contrib"
                :kind :package-version
                :reason "query-matched file fact"
                :source-line 20
                :repo-id "app"
                :repo "app"}
               {:path "benchmarks/Dapper.Tests.Performance/Dapper.Tests.Performance.csproj"
                :target-kind :file-fact
                :result-kind :file
                :label "PackageReference BenchmarkDotNet"
                :kind :package-dependency
                :reason "query-matched file fact"
                :source-line 15
                :repo-id "app"
                :repo "app"}}
             (set candidates))))))

(deftest source-graph-candidates-preserve-rare-query-token-source-rows
  (let [generic-row (fn [idx]
                      {:xt/id (str "node:generic:" idx)
                       :project-id "fixture"
                       :repo-id "app"
                       :path (format "src/flask/generic_%02d.py" idx)
                       :kind :python-module
                       :label "flask python framework runtime file case behavior"
                       :name (str "generic_" idx)
                       :source-line 1})
        target-path "src/flask/sansio/app.py"
        target-row {:xt/id "node:select-autoescape"
                    :project-id "fixture"
                    :repo-id "app"
                    :path target-path
                    :kind :method
                    :label "flask.sansio.app/App.select_jinja_autoescape"
                    :name "App.select_jinja_autoescape"
                    :source-line 533}]
    (with-redefs [context/source-graph-candidate-limit 3
                  context/source-graph-neighbor-scan-limit 3
                  store/xtdb-handle? (constantly true)
                  store/rows-matching-any-token
                  (fn [_ table fields tokens constraints ctx]
                    (is (= {:valid-at "t"} ctx))
                    (is (= #{"autoescape"
                             "selection"
                             "case"
                             "insensitive"
                             "flask"
                             "python"
                             "framework"
                             "runtime"
                             "file"
                             "behavior"}
                           (set tokens)))
                    (case table
                      :ygg/nodes
                      (do
                        (is (= [:path :label :name :kind] fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"}
                               constraints))
                        (conj (mapv generic-row (range 40)) target-row))

                      :ygg/files
                      (do
                        (is (= [:path :kind] fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"
                                :active? true}
                               constraints))
                        [])

                      :ygg/file-facts
                      (do
                        (is (= [:path :file-kind :kind :label :normalized-value]
                               fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"
                                :active? true}
                               constraints))
                        [])))
                  query/edges-touching-node-ids (fn [& _] [])]
      (let [rows (#'context/source-graph-candidates
                  :xtdb
                  (text/tokenize-all
                   "autoescape selection case insensitive flask python framework runtime file behavior")
                  {:project-id "fixture"
                   :repo-id "app"
                   :read-context {:valid-at "t"}})
            target (first (filter #(= target-path (:path %)) rows))]
        (is target)
        (is (= "flask.sansio.app/App.select_jinja_autoescape"
               (:label target)))
        (is (<= (:rank target) 3))))))

(deftest source-graph-candidates-reserve-query-matched-path-self-identity
  (let [noise-rows (mapv (fn [idx]
                           {:xt/id (str "node:noise:" idx)
                            :path (format "impl/noise_%02d.go" idx)
                            :kind :function
                            :label (str "impl/noise_" idx
                                        "/consumer component connector contracts")
                            :source-line 1})
                         (range 12))
        ranked (#'context/ranked-source-graph-candidates
                ["consumer" "component" "contracts"]
                (concat noise-rows
                        [{:xt/id "node:consumer"
                          :path "consumer/consumer.go"
                          :kind :type
                          :label "consumer/consumer/Option"
                          :source-line 18}
                         {:xt/id "node:component"
                          :path "component/component.go"
                          :kind :interface
                          :label "component/component/Component"
                          :source-line 25}])
                2)]
    (is (= ["component/component.go" "consumer/consumer.go"]
           (sort (map :path ranked))))))

(deftest source-graph-candidates-include-bounded-neighbor-endpoint-files
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table fields tokens constraints ctx]
                  (is (= {:valid-at "t"} ctx))
                  (is (= #{"theme" "docs" "route"} (set tokens)))
                  (case table
                    :ygg/nodes
                    (do
                      (is (= [:path :label :name :kind] fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"}
                             constraints))
                      [{:xt/id "node:import:theme"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "site/src/layouts/partials/BsThemes.astro"
                        :kind :web-framework-import
                        :label "@layouts/partials/BsThemes.astro"
                        :source-line 4}])

                    :ygg/files
                    (do
                      (is (= [:path :kind] fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"
                              :active? true}
                             constraints))
                      [])

                    :ygg/file-facts
                    (do
                      (is (= [:path :file-kind :kind :label :normalized-value]
                             fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"
                              :active? true}
                             constraints))
                      [])))
                query/edges-touching-node-ids
                (fn [_ ids opts]
                  (is (= #{"node:import:theme"} (set ids)))
                  (is (= {:project-id "fixture"
                          :repo-id "app"
                          :read-context {:valid-at "t"}}
                         opts))
                  [{:xt/id "edge:theme-route"
                    :project-id "fixture"
                    :repo-id "app"
                    :source-id "node:import:theme"
                    :target-id "node:route:examples"
                    :relation :imports
                    :active? true}])
                query/nodes-by-ids
                (fn [_ ids opts]
                  (is (= #{"node:route:examples"} (set ids)))
                  (is (= {:project-id "fixture"
                          :repo-id "app"
                          :read-context {:valid-at "t"}}
                         opts))
                  [{:xt/id "node:route:examples"
                    :project-id "fixture"
                    :repo-id "app"
                    :path "site/src/pages/docs/[version]/examples/index.astro"
                    :kind :web-framework-route
                    :label "/docs/{version}/examples"
                    :source-line 1
                    :active? true}])]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "theme docs route")
                {:project-id "fixture"
                 :repo-id "app"
                 :read-context {:valid-at "t"}})
          neighbor (some #(when (= "site/src/pages/docs/[version]/examples/index.astro"
                                   (:path %))
                            %)
                         rows)]
      (is (= {:path "site/src/pages/docs/[version]/examples/index.astro"
              :target-kind :node
              :target-id "node:route:examples"
              :label "/docs/{version}/examples"
              :kind :web-framework-route
              :result-kind :node
              :reason "graph-neighbor source row"
              :repo-id "app"
              :repo "app"
              :source-line 1
              :supportLabels ["@layouts/partials/BsThemes.astro"]}
             (select-keys neighbor
                          [:path
                           :target-kind
                           :target-id
                           :label
                           :kind
                           :result-kind
                           :reason
                           :repo-id
                           :repo
                           :source-line
                           :supportLabels])))
      (is (pos? (:score neighbor)))
      (is (= (:score neighbor)
             (get-in neighbor [:score-components :sourceGraph]))))))

(deftest source-graph-candidates-preserve-multiple-neighbor-declarations-per-file
  (with-redefs [store/xtdb-handle? (constantly true)
                store/rows-matching-any-token
                (fn [_ table fields tokens constraints ctx]
                  (is (= {:valid-at "t"} ctx))
                  (is (= #{"flow" "log" "resource"} (set tokens)))
                  (case table
                    :ygg/nodes
                    (do
                      (is (= [:path :label :name :kind] fields))
                      (is (= {:project-id "fixture"
                              :repo-id "app"}
                             constraints))
                      [{:xt/id "node:file:flow-log"
                        :project-id "fixture"
                        :repo-id "app"
                        :path "vpc-flow-logs.tf"
                        :kind :terraform-file
                        :label "flow log resource"
                        :source-line 1}])

                    :ygg/files
                    []

                    :ygg/file-facts
                    []))
                query/edges-touching-node-ids
                (fn [_ ids opts]
                  (is (= #{"node:file:flow-log"} (set ids)))
                  (is (= {:project-id "fixture"
                          :repo-id "app"
                          :read-context {:valid-at "t"}}
                         opts))
                  [{:xt/id "edge:file-flow"
                    :project-id "fixture"
                    :repo-id "app"
                    :source-id "node:file:flow-log"
                    :target-id "node:resource:flow"
                    :relation :defines
                    :active? true}
                   {:xt/id "edge:file-role"
                    :project-id "fixture"
                    :repo-id "app"
                    :source-id "node:file:flow-log"
                    :target-id "node:resource:role"
                    :relation :defines
                    :active? true}])
                query/nodes-by-ids
                (fn [_ ids opts]
                  (is (= #{"node:resource:flow" "node:resource:role"} (set ids)))
                  (is (= {:project-id "fixture"
                          :repo-id "app"
                          :read-context {:valid-at "t"}}
                         opts))
                  [{:xt/id "node:resource:flow"
                    :project-id "fixture"
                    :repo-id "app"
                    :path "vpc-flow-logs.tf"
                    :kind :terraform-resource
                    :label "aws_flow_log.this"
                    :source-line 34
                    :active? true}
                   {:xt/id "node:resource:role"
                    :project-id "fixture"
                    :repo-id "app"
                    :path "vpc-flow-logs.tf"
                    :kind :terraform-resource
                    :label "aws_iam_role.vpc_flow_log_cloudwatch"
                    :source-line 79
                    :active? true}])]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "flow log resource")
                {:project-id "fixture"
                 :repo-id "app"
                 :read-context {:valid-at "t"}})]
      (is (= ["aws_flow_log.this"
              "aws_iam_role.vpc_flow_log_cloudwatch"]
             (->> rows
                  (filter #(= "vpc-flow-logs.tf" (:path %)))
                  (filter #(= "graph-neighbor source row" (:reason %)))
                  (map :label)
                  sort
                  vec))))))

(deftest source-graph-declarations-preserve-path-local-candidate-declarations
  (let [candidate-files [{:path "variables.tf"
                          :rank 1
                          :repo "app"}
                         {:path "vpc-flow-logs.tf"
                          :rank 2
                          :repo "app"}
                         {:path "main.tf"
                          :rank 3
                          :repo "app"}]]
    (with-redefs [query/nodes-by-paths
                  (fn [_ paths opts]
                    (is (= ["variables.tf" "vpc-flow-logs.tf" "main.tf"] paths))
                    (is (= {:project-id "fixture"
                            :repo-id "app"}
                           opts))
                    [{:xt/id "node:var:role-name"
                      :repo-id "app"
                      :path "variables.tf"
                      :kind :terraform-variable
                      :label "var.vpc_flow_log_iam_role_name"
                      :source-line 1511
                      :active? true}
                     {:xt/id "node:var:role-path"
                      :repo-id "app"
                      :path "variables.tf"
                      :kind :terraform-variable
                      :label "var.vpc_flow_log_iam_role_path"
                      :source-line 1517
                      :active? true}
                     {:xt/id "node:resource:role"
                      :repo-id "app"
                      :path "vpc-flow-logs.tf"
                      :kind :terraform-resource
                      :label "aws_iam_role.vpc_flow_log_cloudwatch"
                      :source-line 79
                      :active? true}
                     {:xt/id "node:resource:attachment"
                      :repo-id "app"
                      :path "vpc-flow-logs.tf"
                      :kind :terraform-resource
                      :label "aws_iam_role_policy_attachment.vpc_flow_log_cloudwatch"
                      :source-line 118
                      :active? true}
                     {:xt/id "node:resource:flow"
                      :repo-id "app"
                      :path "vpc-flow-logs.tf"
                      :kind :terraform-resource
                      :label "aws_flow_log.this"
                      :source-line 34
                      :active? true}
                     {:xt/id "node:resource:vpc"
                      :repo-id "app"
                      :path "main.tf"
                      :kind :terraform-resource
                      :label "aws_vpc.this"
                      :source-line 28
                      :active? true}])]
      (let [declarations (#'context/source-graph-declarations
                          {:node :stub}
                          (text/tokenize-all
                           "trace vpc flow log data ownership iam roles log destinations terraform resource")
                          []
                          candidate-files
                          {:project-id "fixture"
                           :repo-id "app"})
            top-labels (set (map :label (take 6 declarations)))
            flow-log (some #(when (= "aws_flow_log.this" (:label %)) %)
                           declarations)]
        (is (contains? top-labels "aws_flow_log.this"))
        (is (= {:path "vpc-flow-logs.tf"
                :kind "terraform-resource"
                :sourceLine 34
                :repo "app"
                :repoId "app"}
               (select-keys flow-log [:path
                                      :kind
                                      :sourceLine
                                      :repo
                                      :repoId])))))))

(deftest source-graph-candidates-preserve-neighbor-kind-path-diversity
  (let [doc-count 48
        doc-seeds (mapv (fn [idx]
                          {:xt/id (str "node:seed:doc:" idx)
                           :project-id "fixture"
                           :repo-id "app"
                           :path (format "docs/component-%02d.md" idx)
                           :kind :doc-file
                           :label (format "theme docs route component %02d" idx)
                           :source-line 1})
                        (range doc-count))
        route-path "site/src/pages/docs/[version]/examples/index.astro"
        route-seed {:xt/id "node:seed:route"
                    :project-id "fixture"
                    :repo-id "app"
                    :path route-path
                    :kind :web-framework-import
                    :label "@layouts/partials/BsThemes.astro theme docs route"
                    :source-line 4}
        doc-neighbor (fn [idx]
                       {:xt/id (str "node:neighbor:doc:" idx)
                        :project-id "fixture"
                        :repo-id "app"
                        :path (format "docs/neighbor-%02d.md" idx)
                        :kind :doc-heading
                        :label (format "Neighbor %02d" idx)
                        :source-line 3
                        :active? true})
        route-neighbor {:xt/id "node:neighbor:route-file"
                        :project-id "fixture"
                        :repo-id "app"
                        :path route-path
                        :kind :web-framework-file
                        :label route-path
                        :source-line 1
                        :active? true}]
    (with-redefs [store/xtdb-handle? (constantly true)
                  store/rows-matching-any-token
                  (fn [_ table fields tokens constraints ctx]
                    (is (= {:valid-at "t"} ctx))
                    (is (= #{"theme" "docs" "route"} (set tokens)))
                    (case table
                      :ygg/nodes
                      (do
                        (is (= [:path :label :name :kind] fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"}
                               constraints))
                        (conj doc-seeds route-seed))

                      :ygg/files
                      (do
                        (is (= [:path :kind] fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"
                                :active? true}
                               constraints))
                        [])

                      :ygg/file-facts
                      (do
                        (is (= [:path :file-kind :kind :label :normalized-value]
                               fields))
                        (is (= {:project-id "fixture"
                                :repo-id "app"
                                :active? true}
                               constraints))
                        [])))
                  query/edges-touching-node-ids
                  (fn [_ ids opts]
                    (is (contains? (set ids) "node:seed:route"))
                    (is (= {:project-id "fixture"
                            :repo-id "app"
                            :read-context {:valid-at "t"}}
                           opts))
                    (vec
                     (concat
                      (map (fn [idx]
                             {:xt/id (str "edge:doc:" idx)
                              :project-id "fixture"
                              :repo-id "app"
                              :source-id (str "node:seed:doc:" idx)
                              :target-id (str "node:neighbor:doc:" idx)
                              :relation :defines
                              :active? true})
                           (range doc-count))
                      [{:xt/id "edge:route"
                        :project-id "fixture"
                        :repo-id "app"
                        :source-id "node:seed:route"
                        :target-id "node:neighbor:route-file"
                        :relation :imports
                        :active? true}])))
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (is (contains? (set ids) "node:neighbor:route-file"))
                    (is (= {:project-id "fixture"
                            :repo-id "app"
                            :read-context {:valid-at "t"}}
                           opts))
                    (let [nodes-by-id (into {"node:neighbor:route-file" route-neighbor}
                                            (map (fn [idx]
                                                   [(str "node:neighbor:doc:" idx)
                                                    (doc-neighbor idx)]))
                                            (range doc-count))]
                      (keep nodes-by-id ids)))]
      (let [rows (#'context/source-graph-candidates
                  :xtdb
                  (text/tokenize-all "theme docs route")
                  {:project-id "fixture"
                   :repo-id "app"
                   :read-context {:valid-at "t"}})
            neighbor (some #(when (and (= route-path (:path %))
                                       (= "graph-neighbor source row"
                                          (:reason %)))
                              %)
                           rows)]
        (is (= {:path route-path
                :target-kind :node
                :target-id "node:neighbor:route-file"
                :label route-path
                :kind :web-framework-file
                :result-kind :node
                :reason "graph-neighbor source row"
                :repo-id "app"
                :repo "app"
                :source-line 1
                :supportLabels ["@layouts/partials/BsThemes.astro theme docs route"]}
               (select-keys neighbor
                            [:path
                             :target-kind
                             :target-id
                             :label
                             :kind
                             :result-kind
                             :reason
                             :repo-id
                             :repo
                             :source-line
                             :supportLabels])))))))

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
                      :active? true}]

                    :ygg/file-facts
                    []))
                query/edges-touching-node-ids (fn [& _] [])]
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
                          (range 45))

                    :ygg/file-facts
                    []))
                query/edges-touching-node-ids (fn [& _] [])]
    (let [rows (#'context/source-graph-candidates
                :xtdb
                (text/tokenize-all "http adapter")
                {:project-id "fixture"
                 :repo-id "app"})]
      (is (= 45 (count (filter #(= :file (:target-kind %)) rows)))))))

(deftest context-packet-loads-docs-for-source-graph-file-candidates
  (with-redefs [store/xtdb-handle? (constantly true)
                corrections/overlay (fn [_ project-id]
                                      (correction-overlay/empty-overlay project-id))
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
                      :active? true}]
                    :ygg/file-facts
                    []))
                query/edges-touching-node-ids (fn [& _] [])
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
                query/system-evidence-by-system-ids (fn [& _] [])
                query/system-evidence-by-paths (fn [& _] [])
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
  (with-redefs [context/candidate-input-retrieval-prefix-limit 1]
    (let [ranked (#'context/ranked-candidate-inputs
                  [{:path "tests/first.js" :score 9.0}
                   {:path "tests/second.js" :score 8.0}]
                  [{:path "lib/adapter.js" :score 3.0}
                   {:path "docs/guide.md" :score 2.0}])]
      (is (= ["tests/first.js"
              "lib/adapter.js"
              "docs/guide.md"
              "tests/second.js"]
             (mapv :path ranked))))))

(deftest candidate-input-ranking-preserves-retrieval-path-prefix
  (with-redefs [context/candidate-input-retrieval-prefix-limit 2]
    (let [ranked (#'context/ranked-candidate-inputs
                  [{:path "tests/first.js" :score 0.9}
                   {:path "tests/second.js" :score 0.8}
                   {:path "tests/third.js" :score 0.7}]
                  [{:path "src/source-graph.js" :score 9.0}])]
      (is (= ["tests/first.js"
              "tests/second.js"
              "src/source-graph.js"
              "tests/third.js"]
             (mapv :path ranked))))))

(deftest candidate-input-ranking-reserves-source-graph-declaration-paths
  (with-redefs [context/candidate-input-retrieval-prefix-limit 2
                context/candidate-input-source-declaration-prefix-limit 3]
    (let [ranked (#'context/ranked-candidate-inputs
                  [{:path "site/src/components/home/Themes.astro"
                    :score 3.0
                    :label "site.src.components.home.Themes"}
                   {:path "site/src/components/header/Navigation.astro"
                    :score 2.9
                    :label "site.src.components.header.Navigation"}]
                  [{:path "site/src/pages/index.astro"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :kind :web-framework-import
                    :label "@components/home/Themes.astro"
                    :source-line 10}
                   {:path "site/src/components/home/Themes.astro"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :kind :namespace
                    :label "site.src.components.home.Themes"
                    :source-line 1}
                   {:path "site/src/pages/docs/[version]/examples/index.astro"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :kind :web-framework-import
                    :label "@layouts/partials/BsThemes.astro"
                    :source-line 4}])]
      (is (= ["site/src/pages/index.astro"
              "site/src/pages/docs/[version]/examples/index.astro"
              "site/src/components/home/Themes.astro"
              "site/src/components/header/Navigation.astro"]
             (->> ranked
                  (map :path)
                  (take 4)
                  vec))))))

(deftest candidate-input-ranking-does-not-bury-source-graph-after-long-prefix
  (let [results (mapv (fn [idx]
                        {:path (format "src/noise_%02d.py" idx)
                         :score (- 0.9 (* idx 0.01))})
                      (range 30))
        ranked (#'context/ranked-candidate-inputs
                results
                [{:path "src/flask/sansio/app.py"
                  :score 10.1
                  :label "flask.sansio.app/App.select_jinja_autoescape"}])
        paths (mapv :path ranked)]
    (is (= "src/noise_00.py" (first paths)))
    (is (< (.indexOf paths "src/flask/sansio/app.py") 15))))

(deftest candidate-input-ranking-reserves-query-matched-path-self-identity
  (with-redefs [context/candidate-input-retrieval-prefix-limit 1]
    (let [results (mapv (fn [idx]
                          {:path (format "docs/noise_%02d.go" idx)
                           :score (- 9.0 (* idx 0.01))
                           :label "consumer component connector contracts"})
                        (range 12))
          ranked (#'context/ranked-candidate-inputs
                  ["consumer" "component" "contracts"]
                  results
                  [{:path "pkg/noise.go"
                    :score 8.5
                    :label "consumer component connector contracts"}
                   {:path "consumer/consumer.go"
                    :score 1.0
                    :label "consumer/consumer/Option"}
                   {:path "component/component.go"
                    :score 1.1
                    :label "component/component/Component"}])]
      (is (= ["docs/noise_00.go"
              "component/component.go"
              "consumer/consumer.go"]
             (->> ranked
                  (map :path)
                  (take 3)
                  vec))))))

(deftest context-packet-ranks-source-graph-candidates-before-low-score-results
  (with-redefs [context/candidate-input-retrieval-prefix-limit 0
                store/xtdb-handle? (constantly true)
                corrections/overlay (fn [_ project-id]
                                      (correction-overlay/empty-overlay project-id))
                query/search-report (fn [_ _ _]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "site/src/pages/docs/index.astro"
                                                  :score 0.4
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
                      :path "site/runtime/a.ts"
                      :kind :web-framework-plugin
                      :label "bootstrap setup"
                      :source-line 12}]
                    :ygg/files []
                    :ygg/file-facts []))
                query/edges-touching-node-ids (fn [& _] [])
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                query/system-evidence-by-system-ids (fn [& _] [])
                query/system-evidence-by-paths
                (fn [_ paths _]
                  (is (= #{"site/runtime/a.ts"
                           "site/src/pages/docs/index.astro"}
                         (set paths)))
                  [{:xt/id "evidence:source-runtime"
                    :system-id "system:runtime"
                    :repo-id "app"
                    :path "site/runtime/a.ts"
                    :file-kind :env
                    :kind :env-var
                    :label "DATABASE_URL"
                    :normalized-value "database-url"
                    :source-line 4
                    :confidence 0.8
                    :active? true}])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                context/query-evidence (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _] nil)]
    (let [packet (context/context-packet :xtdb
                                         "bootstrap setup"
                                         {:project-id "fixture"
                                          :repo-id "app"
                                          :retriever :lexical
                                          :budget 100000})]
      (is (= {:path "site/runtime/a.ts"
              :rank 1
              :targetKind "node"
              :label "bootstrap setup"
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
                     [:scoreComponents :sourceGraph])))
      (is (= [{:rank 1
               :sourceRank 1
               :path "site/runtime/a.ts"
               :label "bootstrap setup"
               :kind "web-framework-plugin"
               :targetKind "node"
               :resultKind "node"
               :sourceLine 12
               :repo "app"
               :repoId "app"}]
             (mapv #(select-keys % [:rank
                                    :sourceRank
                                    :path
                                    :label
                                    :kind
                                    :targetKind
                                    :resultKind
                                    :sourceLine
                                    :repo
                                    :repoId])
                   (:sourceDeclarations packet))))
      (is (= ["evidence:source-runtime"]
             (mapv :id (get-in packet [:architecture :runtimeEvidence])))))))

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

(deftest fit-budget-preserves-source-file-candidate-reserve
  (let [fit-budget @#'context/fit-budget
        packet {:schema context/schema
                :query "find query dispatch"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 1250}
                :entities []
                :edges []
                :activity []
                :warnings []
                :drilldowns []
                :candidateFiles (vec
                                 (concat
                                  (map (fn [idx]
                                         {:path (str "docs/candidate_" idx ".md")
                                          :rank (inc idx)
                                          :kind :markdown
                                          :score 2.0
                                          :targetKind "chunk"
                                          :label (apply str
                                                        "verbose docs "
                                                        (repeat 60
                                                                (str "token" idx " ")))
                                          :scoreComponents {:lexical 1.0}})
                                       (range 25))
                                  [{:path "docs/reference.md"
                                    :rank 26
                                    :kind :doc-file
                                    :score 0.2
                                    :targetKind "chunk"
                                    :label "reference doc"
                                    :scoreComponents {:lexical 1.0}}
                                   {:path "src/query.py"
                                    :rank 27
                                    :kind :python-file
                                    :score 0.1
                                    :targetKind "chunk"
                                    :label "query source"
                                    :scoreComponents {:lexical 0.5}}
                                   {:path "src/source_graph.py"
                                    :rank 28
                                    :kind :python
                                    :score 0.1
                                    :targetKind "file"
                                    :label "source graph file"
                                    :scoreComponents {:sourceGraph 0.6}}
                                   {:path "src/source_graph_node.py"
                                    :rank 29
                                    :kind :namespace
                                    :score 0.1
                                    :targetKind "node"
                                    :resultKind "node"
                                    :label "source.graph.node"
                                    :sourceLine 1
                                    :scoreComponents {:sourceGraph 0.6}}
                                   {:path "docs/source_graph_doc.md"
                                    :rank 30
                                    :kind :doc-heading
                                    :score 0.1
                                    :targetKind "node"
                                    :resultKind "node"
                                    :label "source graph doc"
                                    :sourceLine 1
                                    :scoreComponents {:sourceGraph 0.6}}]))
                :docs []}
        fitted (fit-budget packet [] 1250)
        paths (set (map :path (:candidateFiles fitted)))]
    (is (contains? paths "src/query.py"))
    (is (contains? paths "src/source_graph.py"))
    (is (contains? paths "src/source_graph_node.py"))
    (is (not (contains? paths "docs/reference.md")))
    (is (not (contains? paths "docs/source_graph_doc.md")))
    (is (< (count (:candidateFiles fitted))
           (count (:candidateFiles packet))))
    (is (<= (context/estimate-tokens fitted) 1250))))

(deftest fit-budget-preserves-late-architecture-evidence-candidate-reserve
  (let [fit-budget @#'context/fit-budget
        packet {:schema context/schema
                :query "jsonb test stack"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 1250}
                :entities []
                :edges []
                :activity []
                :warnings []
                :drilldowns []
                :candidateFiles (vec
                                 (concat
                                  (map (fn [idx]
                                         {:path (str "src/noise_" idx ".cs")
                                          :rank (inc idx)
                                          :kind :code
                                          :score 1.0
                                          :targetKind "chunk"
                                          :label (apply str
                                                        "verbose candidate "
                                                        (repeat 60
                                                                (str "token" idx " ")))
                                          :scoreComponents {:sourceGraph 0.8}})
                                       (range 25))
                                  [{:path "tests/docker-compose.yml"
                                    :rank 99
                                    :kind "compose"
                                    :score 5.15
                                    :targetKind "file"
                                    :label "postgres:alpine"
                                    :supportLabels ["deployEvidence"
                                                    "container-image-consumer"
                                                    "postgres:alpine"
                                                    "container-image:postgres"]
                                    :architectureEvidence true
                                    :architectureSection "deployEvidence"
                                    :architectureKind "container-image-consumer"
                                    :scoreComponents {:sourceGraph 5.15}}]))
                :docs []}
        fitted (fit-budget packet [] 1250)
        by-path (into {} (map (juxt :path identity)) (:candidateFiles fitted))
        compose-row (get by-path "tests/docker-compose.yml")]
    (is (some? compose-row))
    (is (true? (:architectureEvidence compose-row)))
    (is (= "deployEvidence" (:architectureSection compose-row)))
    (is (< (count (:candidateFiles fitted))
           (count (:candidateFiles packet))))
    (is (<= (context/estimate-tokens fitted) 1250))))

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
