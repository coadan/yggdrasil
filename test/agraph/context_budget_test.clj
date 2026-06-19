(ns agraph.context-budget-test
  (:require [agraph.context :as context]
            [clojure.test :refer [deftest is]]))

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
(deftest minimal-answerability-keeps-result-schema-status-summary
  (let [minimal (#'context/minimal-answerability
                 {:status :limited
                  :available [:activity :validation-history]
                  :missing [:docs]
                  :weak [:validation-history]
                  :unsupported [:remote-work]
                  :counts {:files 99
                           :nodes 88
                           :result-schema-statuses {:matching 3
                                                    :missing-result 1}
                           :result-schema-status-items 4
                           :result-schema-matching-items 3
                           :result-schema-mismatch-items 0
                           :result-schema-missing-result-items 1
                           :result-schema-unexpected-result-items 0
                           :result-schema-mismatch-events 0}})]
    (is (= {:status :limited
            :available [:activity :validation-history]
            :missing [:docs]
            :weak [:validation-history]
            :unsupported [:remote-work]
            :counts {:result-schema-statuses {:matching 3
                                              :missing-result 1}
                     :result-schema-status-items 4
                     :result-schema-matching-items 3
                     :result-schema-mismatch-items 0
                     :result-schema-missing-result-items 1
                     :result-schema-unexpected-result-items 0
                     :result-schema-mismatch-events 0}}
           minimal))))
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
(deftest packet-trimming-keeps-architecture-summary-before-dropping-section
  (let [trim @#'context/trim-optional-context-metadata
        summary {:counts {:acceptedSystems 12
                          :candidateSystems 10
                          :boundaryEvidence 40
                          :validationGaps 3
                          :warnings 2
                          :nextActions 4}
                 :evidenceFamilyStatuses {"available" 4
                                          "missing" 2}
                 :evidenceFamilyStatusByFamily {"dependency-flow" "missing"
                                                "docs-contracts" "available"
                                                "maintenance" "missing"
                                                "map-corrections" "available"
                                                "runtime-config" "available"
                                                "source-structure" "available"}
                 :validationGapStatuses {"missing" 2
                                         "weak" 1}
                 :validationGapStatusByPlane {"dependencies" "missing"
                                              "docs" "weak"
                                              "system-evidence" "missing"}
                 :validationGapSamples [{:plane "dependencies"
                                         :status "missing"}
                                        {:plane "docs"
                                         :status "weak"}
                                        {:plane "system-evidence"
                                         :status "missing"}]
                 :nextActionSamples [{:kind :inspect
                                      :target "system:billing"
                                      :mcpTool "agraph_node"
                                      :mcpArgs {:target "system:billing"}}
                                     {:kind :inspect
                                      :target "system:worker"
                                      :mcpTool "agraph_node"
                                      :mcpArgs {:target "system:worker"}}
                                     {:kind :dependencies
                                      :command "agraph packages --json"}]
                 :nextActionKinds {:inspect 3
                                   :dependencies 1}}
        architecture {:basis "mechanical-plus-map"
                      :summary summary
                      :acceptedSystems (mapv (fn [idx]
                                               {:id (str "system:accepted:" idx)
                                                :label (apply str
                                                              "Accepted "
                                                              (repeat 80
                                                                      "billing "))})
                                             (range 12))
                      :candidateSystems (mapv (fn [idx]
                                                {:id (str "system:candidate:" idx)
                                                 :why (apply str
                                                             "mechanical evidence "
                                                             (repeat 80
                                                                     "relation "))})
                                              (range 10))
                      :boundaryEvidence (mapv (fn [idx]
                                                {:id (str "edge:" idx)
                                                 :relation "imports-package"
                                                 :reason (apply str
                                                                "bounded evidence "
                                                                (repeat 80
                                                                        "fact "))})
                                              (range 40))
                      :validationGaps [{:plane "dependencies"
                                        :status "missing"}
                                       {:plane "docs"
                                        :status "weak"}
                                       {:plane "system-evidence"
                                        :status "missing"}]}
        packet {:schema context/schema
                :query "billing architecture"
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
                :answerability {:status :ready}
                :architecture architecture}
        minimal-architecture {:basis "mechanical-plus-map"
                              :summary summary}
        budget 500
        trimmed (trim packet budget)]
    (is (> (context/estimate-tokens packet) budget))
    (is (<= (context/estimate-tokens (assoc packet
                                            :architecture
                                            minimal-architecture))
            budget))
    (is (= minimal-architecture (:architecture trimmed)))
    (is (<= (context/estimate-tokens trimmed) budget))))
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
                              :expectedResultSchema "agraph.work.result/v1"
                              :resultSchema "agraph.work.result/v0"
                              :resultSchemaStatus "mismatch"
                              :summary "review billing boundary"
                              :score 1.0
                              :createdAtMs 10
                              :updatedAtMs 20
                              :events [{:event-kind :claim
                                        :status "claimed"
                                        :agent-id "codex"
                                        :summary "claimed for review"
                                        :at-ms 15}]}
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
             :expectedResultSchema "agraph.work.result/v1"
             :resultSchema "agraph.work.result/v0"
             :resultSchemaStatus "mismatch"
             :summary "review billing boundary"
             :score 1.0
             :createdAtMs 10
             :events [{:event-kind :claim
                       :status "claimed"
                       :agent-id "codex"
                       :summary "claimed for review"
                       :at-ms 15}]
             :updatedAtMs 20}]
           (:openDecisions section)))
    (is (= {:counts {:acceptedSystems 1
                     :candidateSystems 1
                     :boundaryEvidence 2
                     :runtimeEvidence 2
                     :deployEvidence 1
                     :dependencyEvidence 1
                     :docs 1
                     :openDecisions 1
                     :validationGaps 3
                     :warnings 1
                     :nextActions 6}
            :evidenceFamilyStatuses {"available" 7}
            :evidenceFamilyStatusByFamily {"dependency-flow" "available"
                                           "deploy-topology" "available"
                                           "docs-contracts" "available"
                                           "maintenance" "available"
                                           "map-corrections" "available"
                                           "runtime-config" "available"
                                           "source-structure" "available"}
            :validationGapStatuses {"missing" 1
                                    "unsupported" 1
                                    "weak" 1}
            :validationGapStatusByPlane {"dependencies" "missing"
                                         "docs" "weak"
                                         "remote-work" "unsupported"}
            :validationGapSamples [{:plane "dependencies"
                                    :status "missing"
                                    :nextActions [{:kind :dependencies
                                                   :command "agraph packages --json"}]}
                                   {:plane "docs"
                                    :status "weak"}
                                   {:plane "remote-work"
                                    :status "unsupported"}]
            :nextActionSamples [{:kind :inspect
                                 :label "Inspect accepted system Billing"
                                 :target "system:billing"
                                 :mcpTool "agraph_node"
                                 :mcpArgs {:target "system:billing"}}
                                {:kind :inspect
                                 :label "Inspect candidate system Candidate"
                                 :target "system:candidate"
                                 :mcpTool "agraph_node"
                                 :mcpArgs {:target "system:candidate"}}
                                {:kind :inspect
                                 :label "Inspect evidence DATABASE_URL"
                                 :target "evidence:database-url"
                                 :mcpTool "agraph_node"
                                 :mcpArgs {:target "evidence:database-url"}}]
            :nextActionKinds {:inspect 5
                              :work-review 1}}
           (:summary section)))
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
            {:kind :work-review
             :label "Inspect open decision work:billing"
             :target "work:billing"
             :command "agraph sync work show work:billing"
             :mcpTool "agraph_work_show"
             :mcpArgs {:workId "work:billing"}
             :reason "Open queued review packet before accepting or rejecting architecture evidence"}]
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
                         :indexedConnectivity {:indexedFiles 200
                                               :nodes 300
                                               :edges 150
                                               :connectedFiles 120
                                               :crossFileConnectedFiles 80
                                               :isolatedFiles 80
                                               :byKind (mapv (fn [idx]
                                                               {:kind (str "kind-" idx)
                                                                :indexedFiles idx
                                                                :connectedFiles idx
                                                                :crossFileConnectedFiles idx
                                                                :isolatedFiles idx})
                                                             (range 10))}
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
    (is (= {:indexedFiles 200
            :nodes 300
            :edges 150
            :connectedFiles 120
            :crossFileConnectedFiles 80
            :isolatedFiles 80}
           (dissoc (get-in trimmed [:sourceCoverage :indexedConnectivity])
                   :byKind)))
    (is (= 5 (count (get-in trimmed
                            [:sourceCoverage :indexedConnectivity :byKind]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :topFileKinds]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :extractors]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :extractorFingerprints]))))
    (is (= 3 (count (get-in trimmed [:sourceCoverage :nextActions]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :diagnostics :byStage]))))
    (is (= 5 (count (get-in trimmed [:sourceCoverage :diagnostics :byExtractor]))))
    (is (= 3 (count (get-in trimmed [:sourceCoverage :diagnostics :samples]))))))

(deftest compact-audit-scopes-preserves-trust-boundary-counts
  (let [trim @#'context/trim-optional-context-metadata
        scope {:kind "unclassified-extractor"
               :basis "indexed-graph"
               :supportedFiles 2
               :skippedFiles 1
               :facts 4
               :diagnostics 3
               :overlayCount 1
               :topEvidenceTypes (mapv (fn [idx]
                                         {:kind (str "kind-" idx)
                                          :count idx})
                                       (range 6))
               :samples (mapv (fn [idx]
                                {:path (str "file-" idx ".edn")
                                 :section "files"})
                              (range 5))
               :extra "drop"}
        packet {:schema context/schema
                :query "audit scope"
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
                :search {:instrumentation {:context-chunks (vec (range 100))}}
                :auditScopes [scope]}
        compacted (-> packet
                      (update-in [:search :instrumentation] dissoc :context-chunks)
                      (assoc :auditScopes
                             [{:kind "unclassified-extractor"
                               :basis "indexed-graph"
                               :supportedFiles 2
                               :skippedFiles 1
                               :facts 4
                               :diagnostics 3
                               :overlayCount 1
                               :topEvidenceTypes [{:kind "kind-0"
                                                   :count 0}
                                                  {:kind "kind-1"
                                                   :count 1}
                                                  {:kind "kind-2"
                                                   :count 2}]
                               :samples [{:path "file-0.edn"
                                          :section "files"}
                                         {:path "file-1.edn"
                                          :section "files"}]}]))
        trimmed (trim packet (context/estimate-tokens compacted))]
    (is (= {:kind "unclassified-extractor"
            :basis "indexed-graph"
            :supportedFiles 2
            :skippedFiles 1
            :facts 4
            :diagnostics 3
            :overlayCount 1
            :topEvidenceTypes [{:kind "kind-0"
                                :count 0}
                               {:kind "kind-1"
                                :count 1}
                               {:kind "kind-2"
                                :count 2}]
            :samples [{:path "file-0.edn"
                       :section "files"}
                      {:path "file-1.edn"
                       :section "files"}]}
           (first (:auditScopes trimmed))))))
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
