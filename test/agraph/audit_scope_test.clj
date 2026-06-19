(ns agraph.audit-scope-test
  (:require [agraph.audit-scope :as audit-scope]
            [clojure.test :refer [deftest is]]))

(deftest selected-summaries-group-mechanical-architecture-evidence
  (let [summaries (audit-scope/selected-summaries
                   {:source-evidence [{:id "chunk:billing-service"
                                       :kind "code-definition"
                                       :fileKind "clojure"
                                       :path "src/billing/service.clj"
                                       :sourceLine 12}
                                      {:id "chunk:billing-api"
                                       :kind "code-definition"
                                       :fileKind "clojure"
                                       :path "src/billing/api.clj"
                                       :sourceLine 4}]
                    :boundary-evidence [{:id "edge:billing-worker"
                                         :source "system:billing"
                                         :target "system:worker"
                                         :relation "shares-config"}]
                    :runtime-evidence [{:id "evidence:env"
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
    (is (= ["source-structure" "dependencies" "runtime-config" "containers" "docs"]
           (mapv :kind summaries)))
    (is (= [{:kind "source-structure"
             :basis "selected-architecture-evidence"
             :facts 3
             :files 2
             :topEvidenceTypes [{:kind "code-definition"
                                 :count 2}
                                {:kind "shares-config"
                                 :count 1}]
             :samples [{:id "chunk:billing-service"
                        :kind "code-definition"
                        :path "src/billing/service.clj"
                        :sourceLine 12
                        :fileKind "clojure"
                        :section "sourceEvidence"}
                       {:id "chunk:billing-api"
                        :kind "code-definition"
                        :path "src/billing/api.clj"
                        :sourceLine 4
                        :fileKind "clojure"
                        :section "sourceEvidence"}
                       {:id "edge:billing-worker"
                        :relation "shares-config"
                        :target "system:worker"
                        :source "system:billing"
                        :section "boundaryEvidence"}]}
            {:kind "dependencies"
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

(deftest report-from-rows-summarizes-indexed-audit-scopes
  (let [report (audit-scope/report-from-rows
                {:project {:id "fixture"}
                 :config-path "project.edn"
                 :coverage-report {:totals {:files 7
                                            :supported 6
                                            :skipped 1}
                                   :diagnostics {:total 1}
                                   :skipped-by-reason [{:reason "unsupported-extension"
                                                        :count 1
                                                        :samples [{:repo-id "app"
                                                                   :path "assets/app.wasm"}]}]}
                 :map-overlay {:docs [{:target "system:billing"
                                       :role "overview"
                                       :source {:path "docs/billing.md"}}]}
                 :rows {:files [{:xt/id "file:src"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "src/app.clj"
                                 :kind :code
                                 :active? true}
                                {:xt/id "file:docs"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "docs/billing.md"
                                 :kind :doc
                                 :active? true}
                                {:xt/id "file:manifest"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "package.json"
                                 :kind :manifest
                                 :active? true}
                                {:xt/id "file:env"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path ".env"
                                 :kind :env
                                 :active? true}
                                {:xt/id "file:docker"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "Dockerfile"
                                 :kind :docker
                                 :active? true}
                                {:xt/id "file:asset"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "assets/logo.png"
                                 :kind :image-asset
                                 :active? true}
                                {:xt/id "file:panel"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :path "flows/home.panel"
                                 :kind :panel
                                 :active? true}]
                        :nodes []
                        :edges [{:xt/id "edge:next"
                                 :project-id "fixture"
                                 :repo-id "app"
                                 :file-id "file:src"
                                 :path "src/app.clj"
                                 :source-id "node:app"
                                 :target-id "package:npm:next"
                                 :relation :imports-package
                                 :active? true}]
                        :chunks [{:xt/id "chunk:billing"
                                  :project-id "fixture"
                                  :repo-id "app"
                                  :file-id "file:docs"
                                  :path "docs/billing.md"
                                  :kind :markdown-heading
                                  :label "Billing"
                                  :active? true}]
                        :file-facts [{:xt/id "fact:env"
                                      :project-id "fixture"
                                      :repo-id "app"
                                      :file-id "file:env"
                                      :path ".env"
                                      :file-kind :env
                                      :kind :env-var
                                      :label "DATABASE_URL"
                                      :normalized-value "database-url"
                                      :source-line 1
                                      :active? true}]
                        :system-evidence [{:xt/id "evidence:image"
                                           :project-id "fixture"
                                           :repo-id "app"
                                           :file-id "file:docker"
                                           :path "Dockerfile"
                                           :file-kind :docker
                                           :kind :container-image
                                           :label "alpine:3.20"
                                           :normalized-value "container-image:alpine"
                                           :source-line 1
                                           :active? true}]
                        :diagnostics [{:xt/id "diagnostic:panel"
                                       :project-id "fixture"
                                       :repo-id "app"
                                       :file-id "file:panel"
                                       :path "flows/home.panel"
                                       :stage :parse
                                       :message "unsupported panel"
                                       :active? true}]}})
        scopes-by-kind (into {} (map (juxt :kind identity)) (:scopes report))]
    (is (= audit-scope/report-schema (:schema report)))
    (is (= ["source"
            "docs"
            "dependencies"
            "runtime-config"
            "containers"
            "assets"
            "unclassified-extractor"]
           (mapv :kind (:scopes report))))
    (is (= {:files 7
            :supportedFiles 6
            :skippedFiles 1
            :diagnostics 1}
           (:coverage report)))
    (is (= 1 (get-in scopes-by-kind ["source" :supportedFiles])))
    (is (= 1 (get-in scopes-by-kind ["docs" :facts])))
    (is (= 1 (get-in scopes-by-kind ["docs" :overlayCount])))
    (is (= 1 (get-in scopes-by-kind ["dependencies" :facts])))
    (is (= 1 (get-in scopes-by-kind ["runtime-config" :facts])))
    (is (= 1 (get-in scopes-by-kind ["containers" :facts])))
    (is (= 1 (get-in scopes-by-kind ["assets" :supportedFiles])))
    (is (= 1 (get-in scopes-by-kind ["unclassified-extractor" :supportedFiles])))
    (is (= 1 (get-in scopes-by-kind ["unclassified-extractor" :skippedFiles])))
    (is (= 1 (get-in scopes-by-kind ["unclassified-extractor" :diagnostics])))
    (is (= [{:scope "unclassified-extractor"
             :section "coverage"
             :evidenceType "skipped:unsupported-extension"
             :rows 1}
            {:scope "unclassified-extractor"
             :section "diagnostics"
             :evidenceType "diagnostic:parse"
             :rows 1}
            {:scope "unclassified-extractor"
             :section "files"
             :evidenceType "panel"
             :rows 1}]
           (mapv #(select-keys % [:scope :section :evidenceType :rows])
                 (:registryDiagnostics report))))
    (is (= #{"agraph packages --project fixture --json"
             "agraph sync coverage project.edn --json"}
           (set (map :command (:nextActions report)))))))
