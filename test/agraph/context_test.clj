(ns agraph.context-test
  (:require [agraph.context :as context]
            [agraph.activity :as activity]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.graph :as graph]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.test :refer [deftest is]]))

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

(deftest answerability-warns-when-indexer-diagnostics-exist
  (let [warnings (#'context/answerability-warnings
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

(deftest answerability-warns-when-result-schema-mismatch-activity-exists
  (let [warnings (#'context/answerability-warnings
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

(deftest answerability-warns-when-source-plane-is-empty
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        no-files (#'context/answerability-warnings
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
        no-graph (#'context/answerability-warnings
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

(deftest answerability-next-steps-distinguish-source-file-and-graph-gaps
  (let [retrieval {:requested :lexical
                   :effective :lexical
                   :fallback? false}
        next-steps (fn [counts]
                     (#'context/next-steps
                      (#'context/next-actions counts retrieval "fixture")))
        no-files (next-steps
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
        no-graph (next-steps
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
    (is (some #{"Run agraph sync <project.edn>"} no-files))
    (is (not (some #{"Run agraph sync <project.edn> --check"} no-files)))
    (is (some #{"Run agraph sync <project.edn> --check"} no-graph))))

(deftest answerability-next-actions-quote-shell-sensitive-project-id
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
    (is (some #(= "agraph packages --project 'fixture project' --json"
                  (:command %))
              actions))))

(deftest answerability-next-actions-keep-coverage-when-capped
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
                   :command "agraph sync coverage <project.edn> --json"}
                  %)
              actions))))

(deftest compact-answerability-keeps-bounded-actionable-detail
  (let [compact (#'context/compact-answerability
                 {:status :limited
                  :available [:source-graph]
                  :missing [:docs]
                  :weak [:dependencies]
                  :unsupported [:remote-work]
                  :counts {:unresolved-imports 1}
                  :retrieval {:effective :lexical}
                  :warnings ["one" "two" "three" "four"]
                  :next ["Run agraph packages --project fixture --json"]
                  :nextActions [{:kind :dependencies
                                 :label "Inspect package graph facts"
                                 :command "agraph packages --project fixture --json"}]
                  :extra "drop"})]
    (is (= {:status :limited
            :available [:source-graph]
            :missing [:docs]
            :weak [:dependencies]
            :unsupported [:remote-work]
            :counts {:unresolved-imports 1}
            :retrieval {:effective :lexical}
            :warnings ["one" "two" "three"]
            :next ["Run agraph packages --project fixture --json"]
            :nextActions [{:kind :dependencies
                           :label "Inspect package graph facts"
                           :command "agraph packages --project fixture --json"}]}
           compact))))

(deftest architecture-section-keeps-accepted-systems-auditable
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"
                                       :kind "service"
                                       :repo "app"
                                       :includes [{:repo "app"
                                                   :path "src/billing"}]
                                       :reason "accepted by review"
                                       :evidence ["work:billing"]}]}
                  :entities [{:id "system:billing"
                              :label "Billing"
                              :kind "service"
                              :repo "app"
                              :pathPrefix "src/billing"
                              :source "map-overlay"
                              :score 1.0}
                             {:id "system:candidate"
                              :label "Candidate"
                              :kind "candidate-system"
                              :repo "app"
                              :pathPrefix "src/candidate"
                              :score 0.7
                              :why "graph label match"}]
                  :edges [{:id "edge:billing-candidate"
                           :source "system:billing"
                           :target "system:candidate"
                           :relation "imports-package"
                           :confidence "high"
                           :score 1.0}]
                  :runtime-evidence [{:id "evidence:database-url"
                                      :systemId "system:billing"
                                      :repo "app"
                                      :path "config/runtime.env"
                                      :fileKind "env"
                                      :kind "env-var"
                                      :label "DATABASE_URL"
                                      :normalizedValue "database-url"
                                      :sourceLine 2
                                      :confidence 1.0
                                      :score 1.25}]
                  :docs [{:target "system:billing"
                          :role "overview"
                          :status "accepted"
                          :source {:path "docs/billing.md"}
                          :score 0.9
                          :snippet "long prose omitted"
                          :provenance "map-attachment"}
                         {:target "chunk:local"
                          :role "reference"
                          :status "candidate"
                          :source {:path "src/local.clj"}
                          :score 0.8
                          :snippet "local source"
                          :provenance "retrieved-doc"}]
                  :activity [{:id "activity:billing-review"
                              :kind "maintenance-decision"
                              :status "ready"
                              :source "queue"
                              :sourceId "work:billing"
                              :summary "review billing boundary"
                              :score 1.0}
                             {:id "activity:done"
                              :kind "maintenance-decision"
                              :status "completed"
                              :source "queue"
                              :sourceId "work:done"}]
                  :answerability {:missing [:dependencies]
                                  :weak [:docs]
                                  :unsupported [:remote-work]
                                  :warnings ["Dependency graph is incomplete."]
                                  :nextActions [{:kind :dependencies
                                                 :command "agraph packages --json"}]}})]
    (is (= "mechanical-plus-map" (:basis section)))
    (is (= [{:id "system:billing"
             :label "Billing"
             :status "accepted"
             :kind "service"
             :repo "app"
             :pathPrefix "src/billing"
             :includes [{:repo "app"
                         :path "src/billing"}]
             :reason "accepted by review"
             :evidence ["work:billing"]}]
           (:acceptedSystems section)))
    (is (= [{:id "system:candidate"
             :label "Candidate"
             :kind "candidate-system"
             :status "candidate"
             :basis "system-graph"
             :score 0.7
             :repo "app"
             :pathPrefix "src/candidate"
             :why "graph label match"}]
           (:candidateSystems section)))
    (is (= [{:kind "graph-edge"
             :id "edge:billing-candidate"
             :source "system:billing"
             :target "system:candidate"
             :relation "imports-package"
             :confidence "high"
             :score 1.0}]
           (:dependencyEvidence section)))
    (is (= [{:id "evidence:database-url"
             :systemId "system:billing"
             :repo "app"
             :path "config/runtime.env"
             :fileKind "env"
             :kind "env-var"
             :label "DATABASE_URL"
             :normalizedValue "database-url"
             :sourceLine 2
             :confidence 1.0
             :score 1.25}]
           (:runtimeEvidence section)))
    (is (= [{:target "system:billing"
             :role "overview"
             :status "accepted"
             :source {:path "docs/billing.md"}
             :score 0.9
             :provenance "map-attachment"}]
           (:docs section)))
    (is (= [{:id "activity:billing-review"
             :kind "maintenance-decision"
             :status "ready"
             :source "queue"
             :sourceId "work:billing"
             :summary "review billing boundary"
             :score 1.0}]
           (:openDecisions section)))
    (is (= [{:plane "dependencies"
             :status "missing"}
            {:plane "docs"
             :status "weak"}
            {:plane "remote-work"
             :status "unsupported"}]
           (:validationGaps section)))))

(deftest context-budget-compacts-source-coverage-before-dropping-it
  (let [trim @#'context/trim-optional-context-metadata
        source-coverage {:schema "agraph.source-coverage.context/v1"
                         :basis "indexed-graph"
                         :totals {:indexedFiles 200
                                  :diagnostics 20
                                  :fileKinds 4}
                         :topFileKinds (mapv (fn [idx]
                                               {:kind (str "kind-" idx)
                                                :count idx})
                                             (range 10))
                         :extractors (mapv (fn [idx]
                                             {:kind (str "kind-" idx)
                                              :extractorVersion (str "v" idx)
                                              :files idx})
                                           (range 10))
                         :extractorFingerprints (mapv (fn [idx]
                                                        {:kind "code"
                                                         :extractorFingerprint (str "fp-" idx)
                                                         :files idx})
                                                      (range 10))
                         :diagnostics {:byStage (mapv (fn [idx]
                                                        {:stage (str "stage-" idx)
                                                         :count idx})
                                                      (range 10))
                                       :byExtractor (mapv (fn [idx]
                                                            {:kind "code"
                                                             :extractorVersion "v"
                                                             :stage (str "stage-" idx)
                                                             :count idx})
                                                          (range 10))
                                       :samples (mapv (fn [idx]
                                                        {:file-id (str "file:" idx)
                                                         :path (str "src/file_" idx ".clj")
                                                         :stage :parse
                                                         :message (apply str
                                                                         "diagnostic "
                                                                         (repeat 20 "detail "))})
                                                      (range 8))}}
        packet {:schema context/schema
                :query "broken parser"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 1}
                :entities []
                :edges []
                :activity []
                :candidateFiles []
                :docs []
                :warnings []
                :drilldowns []
                :search {:instrumentation {:search-docs 1
                                           :context-chunks (vec (range 100))}}
                :sourceCoverage source-coverage}
        compacted (-> packet
                      (update-in [:search :instrumentation] dissoc :context-chunks)
                      (update :sourceCoverage @#'context/compact-source-coverage))
        trimmed (trim packet (context/estimate-tokens compacted))]
    (is (contains? trimmed :sourceCoverage))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :topFileKinds]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :extractors]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :extractorFingerprints]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :diagnostics :byStage]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :diagnostics :byExtractor]))))
    (is (= 3 (count (get-in trimmed [:sourceCoverage :diagnostics :samples]))))))

(deftest reviewed-doc-fitting-compacts-source-coverage-before-dropping-it
  (let [add-doc @#'context/add-doc-with-budget
        compact-source @#'context/compact-source-coverage
        source-coverage {:schema "agraph.source-coverage.context/v1"
                         :basis "indexed-graph"
                         :totals {:indexedFiles 200
                                  :diagnostics 20
                                  :fileKinds 4}
                         :topFileKinds (mapv (fn [idx]
                                               {:kind (str "kind-" idx)
                                                :count idx})
                                             (range 12))
                         :extractors (mapv (fn [idx]
                                             {:kind (str "kind-" idx)
                                              :extractorVersion (str "v" idx)
                                              :files idx})
                                           (range 12))
                         :extractorFingerprints (mapv (fn [idx]
                                                        {:kind "code"
                                                         :extractorFingerprint
                                                         (apply str
                                                                "extractor:"
                                                                idx
                                                                ":"
                                                                (repeat 30 "detail"))
                                                         :files idx})
                                                      (range 12))
                         :diagnostics {:byStage []
                                       :byExtractor []
                                       :samples []}}
        packet {:schema context/schema
                :query "reviewed source"
                :graph {:basis {}
                        :counts {:nodes 0
                                 :edges 0
                                 :clusters 0}}
                :budget {:requested 1}
                :entities []
                :edges []
                :activity []
                :candidateFiles []
                :docs []
                :warnings []
                :drilldowns []
                :search {:instrumentation {:context-chunks []}}
                :sourceCoverage source-coverage}
        doc {:target "chunk:reviewed"
             :role "reference"
             :status "accepted"
             :source {:path "src/reviewed.clj"
                      :line 12}
             :score 2.0
             :snippet "reviewed source evidence"
             :provenance "map-attachment"}
        compact-packet (-> packet
                           (update-in [:search :instrumentation]
                                      dissoc
                                      :context-chunks)
                           (update :sourceCoverage compact-source))
        budget (context/estimate-tokens (update compact-packet :docs conj doc))
        fitted (add-doc packet doc budget)]
    (is (> (context/estimate-tokens (update packet :docs conj doc))
           budget))
    (is (= ["chunk:reviewed"] (mapv :target (:docs fitted))))
    (is (contains? fitted :sourceCoverage))
    (is (= 5 (count (get-in fitted [:sourceCoverage :extractorFingerprints]))))
    (is (<= (context/estimate-tokens fitted) budget))))

(deftest answerability-exposes-indexed-dependency-plane
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files []
                                   :agraph/index-diagnostics []
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
    (let [answerability (#'context/answerability
                         :xtdb
                         {}
                         {:project-id "fixture"
                          :repo-id "app"
                          :retriever :lexical}
                         {:entity-count 1
                          :doc-count 0
                          :activity-count 0
                          :validation-count 0})]
      (is (contains? (set (:available answerability)) :dependencies))
      (is (not (contains? (set (:missing answerability)) :dependencies)))
      (is (= 1 (get-in answerability [:counts :external-packages])))
      (is (= 1 (get-in answerability [:counts :package-import-edges]))))))

(deftest answerability-counts-result-schema-mismatch-events
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :active? true}]
                                   :agraph/index-diagnostics []
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
    (let [answerability (#'context/answerability
                         :xtdb
                         {}
                         {:project-id "fixture"
                          :repo-id "app"
                          :retriever :lexical}
                         {:entity-count 1
                          :doc-count 0
                          :activity-count 0
                          :validation-count 0})]
      (is (contains? (set (:available answerability)) :validation-history))
      (is (not (contains? (set (:missing answerability)) :validation-history)))
      (is (= 1 (get-in answerability [:counts :result-schema-mismatch-events])))
      (is (some #{"Completed work has result schema mismatches; inspect activity before trusting prior results."}
                (:warnings answerability)))
      (is (not (some #{"No validation history rows are indexed; validation-history queries are limited."}
                     (:warnings answerability))))
      (is (some #(= {:kind :activity
                     :label "Inspect result schema mismatch activity"
                     :count 1
                     :mcpTool "agraph_sync_activity"
                     :command "agraph sync activity <project.edn> --json"}
                    %)
                (:nextActions answerability))))))

(deftest answerability-reports-missing-dependency-plane
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :active? true}]
                                   :agraph/index-diagnostics []
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
    (let [answerability (#'context/answerability
                         :xtdb
                         {}
                         {:project-id "fixture"
                          :repo-id "app"
                          :retriever :lexical}
                         {:entity-count 1
                          :doc-count 1
                          :activity-count 1
                          :validation-count 1})]
      (is (not (contains? (set (:available answerability)) :dependencies)))
      (is (contains? (set (:missing answerability)) :dependencies))
      (is (some #{"No dependency graph rows are indexed; dependency questions are limited."}
                (:warnings answerability)))
      (is (some #{"Run agraph packages --project fixture --json"}
                (:next answerability))))))

(deftest answerability-surfaces-unresolved-dependency-review-next-step
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :active? true}]
                                   :agraph/index-diagnostics []
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
    (let [answerability (#'context/answerability
                         :xtdb
                         {}
                         {:project-id "fixture"
                          :repo-id "app"
                          :retriever :lexical}
                         {:entity-count 1
                          :doc-count 0
                          :activity-count 0
                          :validation-count 0})]
      (is (contains? (set (:available answerability)) :dependencies))
      (is (contains? (set (:weak answerability)) :dependencies))
      (is (not (contains? (set (:missing answerability)) :dependencies)))
      (is (= 1 (get-in answerability [:counts :unresolved-imports])))
      (is (some #{"Dependency graph has unresolved imports; dependency answers may need package review."}
                (:warnings answerability)))
      (is (some #{"Run agraph packages --project fixture --json"}
                (:next answerability)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect package graph facts"
                     :command "agraph packages --project fixture --json"}
                    %)
                (:nextActions answerability)))
      (is (some #{"Run agraph sync <project.edn> --check --enqueue"}
                (:next answerability)))
      (is (some #(= {:kind :dependency-review
                     :label "Queue unresolved import review work"
                     :count 1
                     :command "agraph sync <project.edn> --check --enqueue"}
                    %)
                (:nextActions answerability))))))

(deftest answerability-surfaces-dependency-evidence-gaps-and-conflicts
  (with-redefs [store/all-rows (fn [_ table _]
                                 (case table
                                   :agraph/files [{:xt/id "file:app"
                                                   :active? true}]
                                   :agraph/index-diagnostics []
                                   []))
                query/all-nodes (fn [& _]
                                  [{:xt/id "package:npm:react"
                                    :kind :external-package
                                    :active? true}])
                query/all-edges (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                query/all-diagnostics (fn [& _] [])
                dependency/package-report (fn [& _]
                                            {:counts {:packages 1
                                                      :imports-package 0
                                                      :unresolved-imports 0
                                                      :declared-without-import-evidence 1
                                                      :version-conflicts 1}})
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])]
    (let [answerability (#'context/answerability
                         :xtdb
                         {}
                         {:project-id "fixture"
                          :repo-id "app"
                          :retriever :lexical}
                         {:entity-count 1
                          :doc-count 0
                          :activity-count 0
                          :validation-count 0})]
      (is (contains? (set (:weak answerability)) :dependencies))
      (is (= 1 (get-in answerability [:counts :package-evidence-gaps])))
      (is (= 1 (get-in answerability [:counts :package-conflicts])))
      (is (some #{"Some declared packages have no source import evidence."}
                (:warnings answerability)))
      (is (some #{"Package version conflicts are present in dependency facts."}
                (:warnings answerability)))
      (is (some #{"Run agraph packages --project fixture --without-import-evidence --json"}
                (:next answerability)))
      (is (some #{"Run agraph packages --project fixture --with-conflicts --json"}
                (:next answerability)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect packages without source import evidence"
                     :count 1
                     :command "agraph packages --project fixture --without-import-evidence --json"}
                    %)
                (:nextActions answerability)))
      (is (some #(= {:kind :dependencies
                     :label "Inspect package version conflicts"
                     :count 1
                     :command "agraph packages --project fixture --with-conflicts --json"}
                    %)
                (:nextActions answerability))))))

(deftest context-packet-includes-search-instrumentation
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:test"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 1
                                                         :returned-count 1}
                                       :results [{:path "src/auth.clj"
                                                  :score 1.2
                                                  :target-kind :chunk
                                                  :target-id "chunk:auth"
                                                  :label "auth/start"
                                                  :source-line 12}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes []
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _]
                                   (throw (ex-info "unexpected broad chunk scan" {})))
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                activity/select-activity (fn [& _] [])
                context/answerability (fn [& _] {:status :ready})
                coverage/context-summary (fn [& _]
                                           {:schema "agraph.source-coverage.context/v1"
                                            :totals {:indexedFiles 1}})]
    (let [packet (context/context-packet :xtdb
                                         "auth"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= "query:test" (get-in packet [:search :query-run-id])))
      (is (= :lexical (get-in packet [:search :retriever-effective])))
      (is (= 1 (get-in packet [:search :instrumentation :search-docs])))
      (is (= 0 (get-in packet [:search :instrumentation :context-chunks])))
      (is (= {:indexedFiles 1}
             (get-in packet [:sourceCoverage :totals])))
      (is (= [{:path "src/auth.clj"
               :rank 1
               :score 1.2
               :targetKind "chunk"
               :label "auth/start"
               :sourceLine 12}]
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
                                               :pathPrefix "src/worker"}]
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
                activity/select-activity (fn [& _]
                                           [{:id "activity:boundary"
                                             :kind "maintenance-decision"
                                             :status "ready"
                                             :source "queue"
                                             :sourceId "work:boundary"
                                             :summary "review billing boundary"
                                             :score 1.0}])
                context/answerability (fn [& _]
                                        {:status :limited
                                         :missing [:dependencies]
                                         :weak [:docs]
                                         :unsupported [:remote-work]
                                         :warnings ["Dependency graph is incomplete."]
                                         :nextActions [{:kind :dependencies
                                                        :command "agraph packages --json"}]})
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
                                                        :path "src/billing"}]}]}})
          architecture (:architecture packet)]
      (is (= "mechanical-plus-map" (:basis architecture)))
      (is (= ["system:billing"]
             (mapv :id (:acceptedSystems architecture))))
      (is (= [{:id "system:worker"
               :label "Worker"
               :kind "candidate-system"
               :status "candidate"
               :basis "system-graph"
               :repo "app"
               :pathPrefix "src/worker"
               :why "retrieval and graph match"}]
             (mapv #(select-keys % [:id
                                    :label
                                    :kind
                                    :status
                                    :basis
                                    :repo
                                    :pathPrefix
                                    :why])
                   (:candidateSystems architecture))))
      (is (= [{:kind "graph-edge"
               :id "edge:billing-worker"
               :source "system:billing"
               :target "system:worker"
               :relation "shares-config"
               :confidence "medium"
               :score 1.0}]
             (:boundaryEvidence architecture)))
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
      (is (< 1.0 (get-in architecture [:runtimeEvidence 0 :score]) 2.0))
      (is (= [{:plane "dependencies"
               :status "missing"}
              {:plane "docs"
               :status "weak"}
              {:plane "remote-work"
               :status "unsupported"}]
             (:validationGaps architecture))))))

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
                activity/select-activity (fn [& _] [])
                context/answerability (fn [& _] {:status :ready})
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
                activity/select-activity (fn [& _] [])
                context/answerability (fn [& _] {:status :ready})
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
                  activity/select-activity (fn [& _] [])
                  context/answerability (fn [& _] {:status :ready})
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
