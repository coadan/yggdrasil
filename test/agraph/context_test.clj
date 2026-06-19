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

(deftest context-drilldowns-use-primary-agent-commands
  (is (= ["agraph explore 'billing flow' --project 'fixture project' --json"
          "agraph view systems --project 'fixture project'"
          "agraph status --project 'fixture project' --json"
          "agraph sync docs audit --project 'fixture project' --map 'maps/agraph map.json'"]
         (#'context/context-drilldowns
          "billing flow"
          "fixture project"
          "maps/agraph map.json"))))

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
                  :planes [{:plane :source-files
                            :status :available
                            :counts {:files 2}}
                           {:plane :dependencies
                            :status :weak
                            :counts {:unresolved-imports 1}}
                           {:plane :remote-work
                            :status :unsupported}]
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
            :planes [{:plane :source-files
                      :status :available
                      :counts {:files 2}}
                     {:plane :dependencies
                      :status :weak
                      :counts {:unresolved-imports 1}}
                     {:plane :remote-work
                      :status :unsupported}]
            :counts {:unresolved-imports 1}
            :retrieval {:effective :lexical}
            :warnings ["one" "two" "three"]
            :next ["Run agraph packages --project fixture --json"]
            :nextActions [{:kind :dependencies
                           :label "Inspect package graph facts"
                           :command "agraph packages --project fixture --json"}]}
           compact))))

(deftest compact-freshness-keeps-bounded-mechanical-detail
  (let [compact (#'context/compact-freshness
                 {:status :stale
                  :counts {:indexed 5
                           :current 5
                           :changed 4
                           :missing 0
                           :unindexed 0}
                  :warnings ["one" "two" "three" "four"]
                  :nextActions [{:kind :freshness
                                 :command "agraph sync project.edn --check"}
                                {:kind :docs
                                 :command "agraph sync project.edn --query-index"}
                                {:kind :coverage
                                 :command "agraph sync coverage project.edn --json"}
                                {:kind :ask
                                 :command "agraph ask \"where is this handled?\" --project fixture --json"}]
                  :repos [{:repo-id "app"
                           :root "/repo"
                           :status :stale
                           :counts {:indexed 5}
                           :samples {:changed [{:repo-id "app"
                                                :path "a.clj"}
                                               {:repo-id "app"
                                                :path "b.clj"}
                                               {:repo-id "app"
                                                :path "c.clj"}
                                               {:repo-id "app"
                                                :path "d.clj"}]}}]
                  :extra "drop"})]
    (is (= {:status :stale
            :counts {:indexed 5
                     :current 5
                     :changed 4
                     :missing 0
                     :unindexed 0}
            :warnings ["one" "two" "three"]
            :nextActions [{:kind :freshness
                           :command "agraph sync project.edn --check"}
                          {:kind :docs
                           :command "agraph sync project.edn --query-index"}
                          {:kind :coverage
                           :command "agraph sync coverage project.edn --json"}]
            :repos [{:repo-id "app"
                     :root "/repo"
                     :status :stale
                     :counts {:indexed 5}
                     :samples {:changed [{:repo-id "app"
                                          :path "a.clj"}
                                         {:repo-id "app"
                                          :path "b.clj"}
                                         {:repo-id "app"
                                          :path "c.clj"}]}}]}
           compact))))

(deftest compact-relationships-keeps-bounded-targets
  (let [packet {:relationships [{:relation "requires"
                                 :source "node:caller"
                                 :count 5
                                 :targets (mapv (fn [idx]
                                                  {:id (str "edge:" idx)
                                                   :target (str "node:" idx)})
                                                (range 5))}
                                {:relation "imports"
                                 :source "node:other"
                                 :count 1
                                 :targets [{:id "edge:other"
                                            :target "node:dep"}]}]}
        compact (#'context/compact-relationships-in-packet packet)]
    (is (= 2 (count (:relationships compact))))
    (is (= 4 (count (get-in compact [:relationships 0 :targets]))))))

(deftest blast-radius-keeps-only-crossing-mechanical-edges
  (let [blast-radius (#'context/blast-radius
                      [{:id "system:api"}]
                      [{:id "edge:api-db"
                        :source "system:api"
                        :target "system:db"
                        :relation "uses"
                        :confidence "medium"
                        :score 1.0}
                       {:id "edge:client-api"
                        :source "system:client"
                        :target "system:api"
                        :relation "calls"
                        :score 0.8}
                       {:id "edge:internal"
                        :source "system:api"
                        :target "system:api"
                        :relation "self"
                        :score 0.7}])]
    (is (= {:basis "selected-mechanical-edges"
            :downstream {:count 1
                         :targets [{:id "edge:api-db"
                                    :relation "uses"
                                    :source "system:api"
                                    :target "system:db"
                                    :neighbor "system:db"
                                    :confidence "medium"
                                    :score 1.0}]}
            :upstream {:count 1
                       :targets [{:id "edge:client-api"
                                  :relation "calls"
                                  :source "system:client"
                                  :target "system:api"
                                  :neighbor "system:client"
                                  :score 0.8}]}}
           blast-radius))))

(deftest compact-blast-radius-keeps-bounded-targets
  (let [packet {:blastRadius {:basis "selected-mechanical-edges"
                              :downstream {:count 5
                                           :targets (mapv (fn [idx]
                                                            {:id (str "edge:d" idx)
                                                             :target (str "node:d" idx)})
                                                          (range 5))}
                              :upstream {:count 5
                                         :targets (mapv (fn [idx]
                                                          {:id (str "edge:u" idx)
                                                           :source (str "node:u" idx)})
                                                        (range 5))}}}
        compact (#'context/compact-blast-radius-in-packet packet)]
    (is (= 4 (count (get-in compact [:blastRadius :downstream :targets]))))
    (is (= 4 (count (get-in compact [:blastRadius :upstream :targets]))))))

(deftest compact-systems-keeps-bounded-orientation
  (let [packet {:systems {:basis "mechanical-plus-map"
                          :accepted (mapv (fn [idx]
                                            {:id (str "system:a" idx)})
                                          (range 6))
                          :candidates (mapv (fn [idx]
                                              {:id (str "system:c" idx)})
                                            (range 6))
                          :counts {:accepted 6
                                   :candidates 6}}}
        compact (#'context/compact-systems-in-packet packet)]
    (is (= "mechanical-plus-map" (get-in compact [:systems :basis])))
    (is (= {:accepted 6
            :candidates 6}
           (get-in compact [:systems :counts])))
    (is (= 4 (count (get-in compact [:systems :accepted]))))
    (is (= 4 (count (get-in compact [:systems :candidates]))))))

(deftest compact-snippets-keeps-bounded-files-and-items
  (let [packet {:snippets (mapv (fn [file-idx]
                                  {:path (str "src/file_" file-idx ".clj")
                                   :items (mapv (fn [item-idx]
                                                  {:target (str "chunk:" file-idx ":" item-idx)
                                                   :text "snippet"})
                                                (range 4))})
                                (range 6))}
        compact (#'context/compact-snippets-in-packet packet)]
    (is (= 4 (count (:snippets compact))))
    (is (every? #(= 2 (count (:items %))) (:snippets compact)))))

(deftest architecture-section-keeps-accepted-systems-auditable
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"
                                       :kind "service"
                                       :repo "app"
                                       :includes [{:repo "app"
                                                   :path "src/billing"}]
                                       :reason "accepted by review"
                                       :evidence ["work:billing"]}]
                            :edges [{:id "map-edge:billing-worker"
                                     :source "system:billing"
                                     :target "system:candidate"
                                     :relation "shares-runtime-config"
                                     :status "accepted"
                                     :reason "reviewed boundary evidence"
                                     :evidence ["work:edge"]}
                                    {:id "map-edge:rejected"
                                     :source "system:billing"
                                     :target "system:ignored"
                                     :relation "uses"
                                     :status "rejected"}]}
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
                                      :score 1.25}
                                     {:id "evidence:billing-image"
                                      :systemId "system:billing"
                                      :repo "app"
                                      :path "deploy/compose.yml"
                                      :fileKind "compose"
                                      :kind "container-image-consumer"
                                      :label "billing image"
                                      :normalizedValue "container-image:billing"
                                      :sourceLine 7
                                      :confidence 1.0
                                      :score 1.1}]
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
    (is (= [{:kind "map-edge"
             :id "map-edge:billing-worker"
             :source "system:billing"
             :target "system:candidate"
             :relation "shares-runtime-config"
             :status "accepted"
             :provenance "map-overlay"
             :reason "reviewed boundary evidence"
             :evidence ["work:edge"]}
            {:kind "graph-edge"
             :id "edge:billing-candidate"
             :source "system:billing"
             :target "system:candidate"
             :relation "imports-package"
             :confidence "high"
             :score 1.0}]
           (:boundaryEvidence section)))
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
             :score 1.25}
            {:id "evidence:billing-image"
             :systemId "system:billing"
             :repo "app"
             :path "deploy/compose.yml"
             :fileKind "compose"
             :kind "container-image-consumer"
             :label "billing image"
             :normalizedValue "container-image:billing"
             :sourceLine 7
             :confidence 1.0
             :score 1.1}]
           (:runtimeEvidence section)))
    (is (= [{:id "evidence:billing-image"
             :systemId "system:billing"
             :repo "app"
             :path "deploy/compose.yml"
             :fileKind "compose"
             :kind "container-image-consumer"
             :label "billing image"
             :normalizedValue "container-image:billing"
             :sourceLine 7
             :confidence 1.0
             :score 1.1}]
           (:deployEvidence section)))
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
    (is (= [{:family "source-structure"
             :status "available"
             :rowCount 4
             :sourceCounts [{:key "acceptedSystems"
                             :count 1}
                            {:key "candidateSystems"
                             :count 1}
                            {:key "boundaryEvidence"
                             :count 2}]}
            {:family "dependency-flow"
             :status "available"
             :rowCount 1
             :sourceCounts [{:key "dependencyEvidence"
                             :count 1}]
             :planes [{:plane "dependencies"
                       :status "missing"}]}
            {:family "runtime-config"
             :status "available"
             :rowCount 2
             :sourceCounts [{:key "runtimeEvidence"
                             :count 2}]}
            {:family "deploy-topology"
             :status "available"
             :rowCount 1
             :sourceCounts [{:key "deployEvidence"
                             :count 1}]}
            {:family "docs-contracts"
             :status "available"
             :rowCount 1
             :sourceCounts [{:key "docs"
                             :count 1}]
             :planes [{:plane "docs"
                       :status "weak"}]}
            {:family "map-corrections"
             :status "available"
             :rowCount 2
             :sourceCounts [{:key "acceptedSystems"
                             :count 1}
                            {:key "mapEdges"
                             :count 1}]}
            {:family "maintenance"
             :status "available"
             :rowCount 1
             :sourceCounts [{:key "openDecisions"
                             :count 1}]}]
           (:evidenceFamilies section)))
    (is (= [{:plane "dependencies"
             :status "missing"
             :nextActions [{:kind :dependencies
                            :command "agraph packages --json"}]}
            {:plane "docs"
             :status "weak"}
            {:plane "remote-work"
             :status "unsupported"}]
           (:validationGaps section)))
    (is (= [{:kind :inspect
             :label "Inspect accepted system Billing"
             :target "system:billing"
             :mcpTool "agraph_node"
             :mcpArgs {:target "system:billing"}
             :reason "Open accepted map system with graph neighbors and attached evidence"}
            {:kind :inspect
             :label "Inspect candidate system Candidate"
             :target "system:candidate"
             :mcpTool "agraph_node"
             :mcpArgs {:target "system:candidate"}
             :reason "Open candidate system with mechanical graph neighbors"}
            {:kind :inspect
             :label "Inspect evidence DATABASE_URL"
             :target "evidence:database-url"
             :mcpTool "agraph_node"
             :mcpArgs {:target "evidence:database-url"}
             :reason "Open exact evidence row and source window"}
            {:kind :inspect
             :label "Inspect evidence billing image"
             :target "evidence:billing-image"
             :mcpTool "agraph_node"
             :mcpArgs {:target "evidence:billing-image"}
             :reason "Open exact evidence row and source window"}
            {:kind :inspect
             :label "Inspect dependency imports-package target system:candidate"
             :target "system:candidate"
             :mcpTool "agraph_node"
             :mcpArgs {:target "system:candidate"}
             :reason "Open dependency endpoint with incident graph evidence"}
            {:kind :inspect
             :label "Inspect architecture doc docs/billing.md"
             :target "docs/billing.md"
             :mcpTool "agraph_node"
             :mcpArgs {:target "docs/billing.md"}
             :reason "Open accepted architecture doc source and attached map evidence"}]
           (:nextActions section)))))

(deftest architecture-section-selects-accepted-systems-by-map-include-path
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"
                                       :repo "app"
                                       :includes [{:repo "app"
                                                   :path "src/billing"}]}
                                      {:id "system:other"
                                       :label "Other"
                                       :repo "app"
                                       :includes [{:repo "app"
                                                   :path "src/other"}]}
                                      {:id "system:admin"
                                       :label "Admin"
                                       :repo "admin"
                                       :includes [{:repo "admin"
                                                   :path "src/billing"}]}]}
                  :entities []
                  :results [{:repo-id "app"
                             :path "src/billing/api.clj"
                             :score 1.0}]
                  :edges []
                  :runtime-evidence []
                  :docs []
                  :activity []
                  :answerability {}})]
    (is (= [{:id "system:billing"
             :label "Billing"
             :status "accepted"
             :repo "app"
             :pathPrefix "src/billing"
             :includes [{:repo "app"
                         :path "src/billing"}]}]
           (:acceptedSystems section)))
    (is (= ["system:billing"]
           (mapv :target (:nextActions section))))))

(deftest architecture-section-reports-missing-evidence-families
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"}]}
                  :entities [{:id "system:billing"
                              :label "Billing"
                              :kind "system"}]
                  :edges []
                  :runtime-evidence []
                  :docs []
                  :activity []
                  :answerability {:available [:source-graph
                                              :system-graph
                                              :map-overlay]
                                  :missing [:dependencies
                                            :system-evidence
                                            :docs
                                            :activity]
                                  :weak []
                                  :unsupported []}})]
    (is (= {"source-structure" "available"
            "dependency-flow" "missing"
            "runtime-config" "missing"
            "deploy-topology" "missing"
            "docs-contracts" "missing"
            "map-corrections" "available"
            "maintenance" "missing"}
           (into {}
                 (map (juxt :family :status))
                 (:evidenceFamilies section))))))

(deftest architecture-validation-gaps-include-bounded-plane-actions
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"}]}
                  :entities [{:id "system:billing"
                              :label "Billing"
                              :kind "system"}]
                  :edges []
                  :runtime-evidence []
                  :docs []
                  :activity []
                  :answerability {:missing [:dependencies]
                                  :weak [:docs]
                                  :unsupported []
                                  :nextActions [{:kind :dependencies
                                                 :label "Inspect package graph facts"
                                                 :command "agraph packages --json"}
                                                {:kind :dependency-review
                                                 :label "Queue unresolved import review work"
                                                 :command "agraph sync <project.edn> --check --enqueue"}
                                                {:kind :dependencies
                                                 :label "Inspect package version conflicts"
                                                 :command "agraph packages --with-conflicts --json"}
                                                {:kind :docs
                                                 :label "Build query index"
                                                 :command "agraph sync <project.edn> --query-index"}]}})]
    (is (= [{:plane "dependencies"
             :status "missing"
             :nextActions [{:kind :dependencies
                            :label "Inspect package graph facts"
                            :command "agraph packages --json"}
                           {:kind :dependency-review
                            :label "Queue unresolved import review work"
                            :command "agraph sync <project.edn> --check --enqueue"}]}
            {:plane "docs"
             :status "weak"
             :nextActions [{:kind :docs
                            :label "Build query index"
                            :command "agraph sync <project.edn> --query-index"}]}]
           (:validationGaps section)))))

(deftest architecture-section-reports-stale-accepted-docs-as-validation-gap
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"}]}
                  :entities [{:id "system:billing"
                              :label "Billing"
                              :kind "system"}]
                  :edges []
                  :runtime-evidence []
                  :docs [{:target "system:billing"
                          :role "contract"
                          :status "stale"
                          :source {:path "docs/billing.md"
                                   :contentSha "sha256:old"}
                          :score 2.35
                          :provenance "map-attachment"
                          :reason "accepted by architecture review"
                          :warning "attached doc source not found"}]
                  :activity []
                  :answerability {:available [:docs]
                                  :missing []
                                  :weak []
                                  :unsupported []}})]
    (is (= [{:target "system:billing"
             :role "contract"
             :status "stale"
             :source {:path "docs/billing.md"
                      :contentSha "sha256:old"}
             :score 2.35
             :provenance "map-attachment"
             :reason "accepted by architecture review"
             :warning "attached doc source not found"}]
           (:docs section)))
    (is (= [{:plane "docs-contracts"
             :status "stale"
             :count 1
             :samples [{:target "system:billing"
                        :role "contract"
                        :source {:path "docs/billing.md"
                                 :contentSha "sha256:old"}
                        :warning "attached doc source not found"
                        :reason "accepted by architecture review"
                        :provenance "map-attachment"}]}]
           (:validationGaps section)))))

(deftest architecture-section-reports-stale-graph-basis-validation-gap
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:billing"
                                       :label "Billing"}]}
                  :entities [{:id "system:billing"
                              :label "Billing"
                              :kind "system"}]
                  :edges []
                  :runtime-evidence []
                  :docs []
                  :activity []
                  :answerability {:missing [:dependencies]
                                  :weak []
                                  :unsupported []}
                  :freshness {:status :stale
                              :counts {:changed 2
                                       :missing 1}
                              :warnings ["Graph basis is stale."
                                         "Another warning."
                                         "Third warning."
                                         "Dropped warning."]
                              :nextActions [{:kind :freshness
                                             :label "Refresh indexed graph basis"
                                             :command "agraph sync project.edn --check"}
                                            {:kind :coverage
                                             :label "Inspect extractor diagnostics"
                                             :command "agraph sync coverage project.edn --json"}]}})]
    (is (= [{:plane "graph-basis"
             :status "stale"
             :counts {:changed 2
                      :missing 1}
             :warnings ["Graph basis is stale."
                        "Another warning."
                        "Third warning."]
             :nextActions [{:kind :freshness
                            :label "Refresh indexed graph basis"
                            :command "agraph sync project.edn --check"}
                           {:kind :coverage
                            :label "Inspect extractor diagnostics"
                            :command "agraph sync coverage project.edn --json"}]}
            {:plane "dependencies"
             :status "missing"}]
           (:validationGaps section)))
    (is (= ["Graph basis is stale."
            "Another warning."
            "Third warning."
            "Dropped warning."]
           (:warnings section)))
    (is (= [{:kind :inspect
             :label "Inspect accepted system Billing"
             :target "system:billing"
             :mcpTool "agraph_node"
             :mcpArgs {:target "system:billing"}
             :reason "Open accepted map system with graph neighbors and attached evidence"}
            {:kind :freshness
             :label "Refresh indexed graph basis"
             :command "agraph sync project.edn --check"}
            {:kind :coverage
             :label "Inspect extractor diagnostics"
             :command "agraph sync coverage project.edn --json"}]
           (:nextActions section)))))

(deftest architecture-section-ranks-boundary-evidence-by-mechanical-support
  (let [section (#'context/architecture-section
                 {:overlay {:systems [{:id "system:alpha"
                                       :label "Alpha"}]
                            :edges [{:id "map-edge:alpha-beta"
                                     :source "system:alpha"
                                     :target "system:beta"
                                     :relation "reviewed-boundary"
                                     :status "accepted"
                                     :evidence ["work:accepted-boundary"]}]}
                  :entities [{:id "system:alpha"
                              :label "Alpha"
                              :kind "system"
                              :score 1.0}
                             {:id "system:beta"
                              :label "Beta"
                              :kind "system"
                              :score 0.8}]
                  :edges [{:id "edge:weak"
                           :source "system:alpha"
                           :target "package:weak"
                           :relation "imports-package"
                           :score 10.0}
                          {:id "edge:dense"
                           :source "system:alpha"
                           :target "system:beta"
                           :relation "imports-package"
                           :evidenceCounts {:imports 3
                                            :manifests 1}
                           :score 0.2}
                          {:id "edge:medium"
                           :source "system:alpha"
                           :target "system:beta"
                           :relation "uses"
                           :evidenceCounts {:runtime 1}
                           :score 0.3}]
                  :runtime-evidence []
                  :docs []
                  :activity []
                  :answerability {}})]
    (is (= ["map-edge:alpha-beta"
            "edge:dense"
            "edge:medium"
            "edge:weak"]
           (mapv :id (:boundaryEvidence section))))
    (is (= ["edge:dense"
            "edge:weak"]
           (mapv :id (:dependencyEvidence section))))))

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
                         :nextActions (mapv (fn [idx]
                                              {:kind :coverage
                                               :label (str "Inspect coverage " idx)
                                               :command (str "agraph sync coverage "
                                                             idx)})
                                            (range 5))
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
    (is (= 3 (count (get-in trimmed [:sourceCoverage :nextActions]))))
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
      (is (= {:plane :dependencies
              :status :available
              :counts {:external-packages 1
                       :package-import-edges 1
                       :declared-packages 1
                       :unresolved-imports 0
                       :package-evidence-gaps 0
                       :package-conflicts 0}}
             (some #(when (= :dependencies (:plane %)) %)
                   (:planes answerability))))
      (is (= 1 (get-in answerability [:counts :external-packages])))
      (is (= 1 (get-in answerability [:counts :package-import-edges]))))))

(deftest answerability-exposes-system-evidence-plane
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
    (let [answerability (#'context/answerability
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
      (is (contains? (set (:available answerability)) :system-evidence))
      (is (contains? (set (:weak answerability)) :system-evidence))
      (is (not (contains? (set (:missing answerability)) :system-evidence)))
      (is (= {:plane :system-evidence
              :status :weak
              :counts {:system-evidence 1}}
             (some #(when (= :system-evidence (:plane %)) %)
                   (:planes answerability))))
      (is (= 1 (get-in answerability [:counts :system-evidence])))
      (is (some #{"Runtime/config evidence rows are indexed, but no runtime/config evidence matched this query."}
                (:warnings answerability))))))

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
      (is (= {:plane :validation-history
              :status :weak
              :counts {:validation-events 0
                       :result-schema-mismatch-events 1}}
             (some #(when (= :validation-history (:plane %)) %)
                   (:planes answerability))))
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
                                          :retriever :lexical
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
      (is (= {:indexedFiles 1}
             (get-in packet [:sourceCoverage :totals])))
      (is (not (contains? packet :auditScopes)))
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
      (is (= [{:kind "source-structure"
               :basis "selected-architecture-evidence"
               :facts 1
               :topEvidenceTypes [{:kind "shares-config"
                                   :count 1}]}
              {:kind "runtime-config"
               :basis "selected-architecture-evidence"
               :facts 1
               :files 1
               :topEvidenceTypes [{:kind "env-var"
                                   :count 1}]}]
             (mapv #(dissoc % :samples)
                   (:auditScopes packet))))
      (is (= [{:id "edge:billing-worker"
               :kind "graph-edge"
               :relation "shares-config"
               :target "system:worker"
               :source "system:billing"
               :section "boundaryEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 0 :samples]))))
      (is (= [{:id "evidence:billing-env"
               :kind "env-var"
               :path "src/billing/api.clj"
               :repo "app"
               :sourceLine 4
               :fileKind "clojure"
               :section "runtimeEvidence"}]
             (mapv #(dissoc % :score)
                   (get-in packet [:auditScopes 1 :samples]))))
      (is (> (get-in architecture [:runtimeEvidence 0 :score]) 2.0))
      (is (= [{:plane "dependencies"
               :status "missing"
               :nextActions [{:kind :dependencies
                              :command "agraph packages --json"}]}
              {:plane "docs"
               :status "weak"}
              {:plane "remote-work"
               :status "unsupported"}]
             (:validationGaps architecture)))
      (is (= ["system:billing" "system:worker" "evidence:billing-env"]
             (mapv :target (take 3 (:nextActions architecture)))))
      (is (= ["agraph_node" "agraph_node" "agraph_node"]
             (mapv :mcpTool (take 3 (:nextActions architecture))))))))

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
                activity/select-activity (fn [_ _ opts]
                                           (is (contains? (:target-ids opts)
                                                          "system:billing"))
                                           [])
                context/answerability (fn [& _] {:status :ready})
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
