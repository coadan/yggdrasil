(ns agraph.audit-scope-test
  (:require [agraph.audit-scope :as audit-scope]
            [clojure.test :refer [deftest is]]))

(deftest selected-summaries-group-mechanical-architecture-evidence
  (let [summaries (audit-scope/selected-summaries
                   {:runtime-evidence [{:id "evidence:env"
                                        :kind "env-var"
                                        :fileKind "env"
                                        :path "config/runtime.env"
                                        :sourceLine 3}
                                       {:id "evidence:postgres"
                                        :kind "container-image-consumer"
                                        :fileKind "compose"
                                        :path "compose.yml"
                                        :sourceLine 8}
                                       {:id "evidence:port"
                                        :kind "container-port"
                                        :fileKind "docker"
                                        :path "Dockerfile"
                                        :sourceLine 12}]
                    :dependency-evidence [{:id "edge:billing-next"
                                           :source "system:billing"
                                           :target "package:npm:next"
                                           :relation "imports-package"}]
                    :docs [{:target "system:billing"
                            :role "overview"
                            :status "accepted"
                            :source {:path "docs/billing.md"}}]})]
    (is (= ["dependencies" "runtime-config" "containers" "docs"]
           (mapv :kind summaries)))
    (is (= [{:kind "dependencies"
             :basis "selected-architecture-evidence"
             :facts 1
             :topEvidenceTypes [{:kind "imports-package"
                                 :count 1}]
             :samples [{:id "edge:billing-next"
                        :relation "imports-package"
                        :target "package:npm:next"
                        :source "system:billing"
                        :section "dependencyEvidence"}]}
            {:kind "runtime-config"
             :basis "selected-architecture-evidence"
             :facts 2
             :files 2
             :topEvidenceTypes [{:kind "container-port"
                                 :count 1}
                                {:kind "env-var"
                                 :count 1}]
             :samples [{:id "evidence:env"
                        :kind "env-var"
                        :path "config/runtime.env"
                        :sourceLine 3
                        :fileKind "env"
                        :section "runtimeEvidence"}
                       {:id "evidence:port"
                        :kind "container-port"
                        :path "Dockerfile"
                        :sourceLine 12
                        :fileKind "docker"
                        :section "runtimeEvidence"}]}
            {:kind "containers"
             :basis "selected-architecture-evidence"
             :facts 2
             :files 2
             :topEvidenceTypes [{:kind "container-image-consumer"
                                 :count 1}
                                {:kind "container-port"
                                 :count 1}]
             :samples [{:id "evidence:postgres"
                        :kind "container-image-consumer"
                        :path "compose.yml"
                        :sourceLine 8
                        :fileKind "compose"
                        :section "runtimeEvidence"}
                       {:id "evidence:port"
                        :kind "container-port"
                        :path "Dockerfile"
                        :sourceLine 12
                        :fileKind "docker"
                        :section "runtimeEvidence"}]}
            {:kind "docs"
             :basis "selected-architecture-evidence"
             :facts 1
             :files 1
             :topEvidenceTypes [{:kind "overview"
                                 :count 1}]
             :samples [{:target "system:billing"
                        :source {:path "docs/billing.md"}
                        :section "docs"}]}]
           summaries))))

(deftest selected-summaries-ignore-unmapped-source-only-rows
  (is (= []
         (audit-scope/selected-summaries
          {:runtime-evidence [{:id "evidence:source"
                               :kind "function"
                               :fileKind "clojure"
                               :path "src/app.clj"}]
           :dependency-evidence [{:id "edge:call"
                                  :source "node:a"
                                  :target "node:b"
                                  :relation "calls"}]
           :docs []}))))
