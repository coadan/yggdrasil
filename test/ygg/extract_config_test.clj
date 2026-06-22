(ns ygg.extract-config-test
  (:require [ygg.extract :as extract]
            [ygg.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest extracts-database-migration-facts
  (let [flyway-result (extract/extract-file
                       "run/test"
                       (fs/file-record
                        "test/fixtures/extractor-repo"
                        "test/fixtures/extractor-repo/db/migration/V1__create_panels.sql"))
        liquibase-result (extract/extract-file
                          "run/test"
                          (fs/file-record
                           "test/fixtures/extractor-repo"
                           "test/fixtures/extractor-repo/db/changelog/db.changelog-master.yaml"))
        flyway-labels (set (map :label (:nodes flyway-result)))
        flyway-kinds (frequencies (map :kind (:nodes flyway-result)))
        flyway-relations (frequencies (map :relation (:edges flyway-result)))
        liquibase-labels (set (map :label (:nodes liquibase-result)))
        liquibase-kinds (frequencies (map :kind (:nodes liquibase-result)))
        liquibase-relations (frequencies (map :relation (:edges liquibase-result)))]
    (is (= :db-migration
           (:kind (fs/file-record
                   "test/fixtures/extractor-repo"
                   "test/fixtures/extractor-repo/db/migration/V1__create_panels.sql"))))
    (is (= :db-migration
           (:kind (fs/file-record
                   "test/fixtures/extractor-repo"
                   "test/fixtures/extractor-repo/db/changelog/db.changelog-master.yaml"))))
    (is (contains? flyway-labels "db/migration/V1__create_panels.sql"))
    (is (contains? flyway-labels "V1__create_panels"))
    (is (contains? flyway-labels "panels"))
    (is (contains? flyway-labels "active_panels"))
    (is (contains? flyway-labels "idx_panels_owner_id"))
    (is (contains? flyway-labels "fk_panels_owner"))
    (is (contains? flyway-labels "users"))
    (is (= 1 (:db-migration flyway-kinds)))
    (is (= 1 (:db-migration-version flyway-kinds)))
    (is (= 2 (:table flyway-kinds)))
    (is (= 1 (:view flyway-kinds)))
    (is (= 1 (:index flyway-kinds)))
    (is (= 1 (:constraint flyway-kinds)))
    (is (= 5 (get flyway-relations :defines 0)))
    (is (= 2 (get flyway-relations :references 0)))
    (is (= [:db-migration-file] (mapv :kind (:chunks flyway-result))))
    (is (contains? liquibase-labels "db/changelog/db.changelog-master.yaml"))
    (is (contains? liquibase-labels "create-panel-audit"))
    (is (contains? liquibase-labels "ygg"))
    (is (contains? liquibase-labels "createTable"))
    (is (contains? liquibase-labels "createIndex"))
    (is (contains? liquibase-labels "addForeignKeyConstraint"))
    (is (contains? liquibase-labels "dropTable"))
    (is (contains? liquibase-labels "rollback"))
    (is (contains? liquibase-labels "panel_audit"))
    (is (contains? liquibase-labels "idx_panel_audit_panel_id"))
    (is (contains? liquibase-labels "fk_panel_audit_panel"))
    (is (contains? liquibase-labels "db/changelog/extra.yaml"))
    (is (= 1 (:db-migration liquibase-kinds)))
    (is (= 1 (:db-changeset liquibase-kinds)))
    (is (= 1 (:db-changeset-author liquibase-kinds)))
    (is (= 4 (:db-change-operation liquibase-kinds)))
    (is (= 1 (:db-rollback liquibase-kinds)))
    (is (= 1 (:table liquibase-kinds)))
    (is (= 1 (:index liquibase-kinds)))
    (is (= 1 (:constraint liquibase-kinds)))
    (is (= 8 (get liquibase-relations :defines 0)))
    (is (= 4 (get liquibase-relations :uses 0)))
    (is (= 1 (get liquibase-relations :references 0)))
    (is (= [:db-migration-file] (mapv :kind (:chunks liquibase-result))))))
(deftest extracts-operational-config-facts
  (let [cloudformation-result (extract/extract-file
                               "run/test"
                               (fs/file-record
                                "test/fixtures/extractor-repo"
                                "test/fixtures/extractor-repo/ops/cloudformation.yaml"))
        pulumi-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/ops/Pulumi.yaml"))
        pulumi-stack-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/ops/Pulumi.dev.yaml"))
        serverless-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/ops/serverless.yml"))
        sam-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/ops/template.yaml"))
        cdk-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/ops/cdk.json"))
        ansible-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/ops/playbook.yaml"))
        nginx-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ops/nginx.conf"))
        caddy-result (extract/extract-file
                      "run/test"
                      {:file-id "file:ops/Caddyfile"
                       :id-scope "fixture"
                       :path "ops/Caddyfile"
                       :kind (fs/file-kind "ops/Caddyfile")
                       :content ":8080\nrespond \"ok\"\n"})
        systemd-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/ops/panels.service"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/cloudformation.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/Pulumi.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/Pulumi.dev.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/serverless.yml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/template.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/cdk.json"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/playbook.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/nginx.conf"))))
    (is (= :ops-config (fs/file-kind "ops/Caddyfile")))
    (is (= :ops-config (fs/file-kind "ops/sudoers")))
    (is (= :ops-config (fs/file-kind "ops/apt.sources")))
    (is (true? (fs/supported-path? "ops/Caddyfile")))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/panels.service"))))
    (is (contains? (labels cloudformation-result) "PanelBucket"))
    (is (contains? (labels cloudformation-result) "PanelFunction"))
    (is (contains? (labels cloudformation-result) "AWS::S3::Bucket"))
    (is (contains? (labels cloudformation-result) "StageName"))
    (is (contains? (labels cloudformation-result) "RegionMap"))
    (is (contains? (labels cloudformation-result) "IsProd"))
    (is (contains? (labels cloudformation-result) "PanelBucketName"))
    (is (= 2 (:cloudformation-resource (kinds cloudformation-result))))
    (is (= 2 (:cloudformation-resource-type (kinds cloudformation-result))))
    (is (= 1 (:cloudformation-parameter (kinds cloudformation-result))))
    (is (= 1 (:cloudformation-mapping (kinds cloudformation-result))))
    (is (= 1 (:cloudformation-condition (kinds cloudformation-result))))
    (is (= 1 (:cloudformation-output (kinds cloudformation-result))))
    (is (= 8 (get (relations cloudformation-result) :defines 0)))
    (is (= 6 (get (relations cloudformation-result) :references 0)))
    (is (contains? (labels pulumi-result) "panels-infra"))
    (is (contains? (labels pulumi-result) "nodejs"))
    (is (contains? (labels pulumi-result) "aws:region"))
    (is (contains? (labels pulumi-result) "aws:region=us-east-1"))
    (is (= 1 (:pulumi-project (kinds pulumi-result))))
    (is (= 1 (:pulumi-runtime (kinds pulumi-result))))
    (is (= 1 (:pulumi-config-key (kinds pulumi-result))))
    (is (= 1 (:pulumi-config-value (kinds pulumi-result))))
    (is (contains? (labels pulumi-stack-result) "dev"))
    (is (contains? (labels pulumi-stack-result) "awskms://alias/pulumi"))
    (is (contains? (labels pulumi-stack-result) "panels:replicas=2"))
    (is (contains? (labels pulumi-stack-result) "panels:apiToken"))
    (is (not (contains? (labels pulumi-stack-result) "panels:apiToken={secure: ciphertext}")))
    (is (= 1 (:pulumi-stack (kinds pulumi-stack-result))))
    (is (= 1 (:pulumi-secrets-provider (kinds pulumi-stack-result))))
    (is (= 3 (:pulumi-config-key (kinds pulumi-stack-result))))
    (is (= 2 (:pulumi-config-value (kinds pulumi-stack-result))))
    (is (= 1 (:pulumi-secret-config (kinds pulumi-stack-result))))
    (is (contains? (labels serverless-result) "panels-api"))
    (is (contains? (labels serverless-result) "listPanels"))
    (is (contains? (labels serverless-result) "src/handlers/list.main"))
    (is (contains? (labels serverless-result) "listPanels:httpApi"))
    (is (contains? (labels serverless-result) "listPanels:/panels"))
    (is (contains? (labels serverless-result) "listPanels:GET"))
    (is (contains? (labels serverless-result) "PanelLambdaRole"))
    (is (contains? (labels serverless-result) "PanelTableName"))
    (is (= 1 (:serverless-service (kinds serverless-result))))
    (is (= 1 (:serverless-function (kinds serverless-result))))
    (is (= 1 (:serverless-provider (kinds serverless-result))))
    (is (= 2 (:serverless-resource (kinds serverless-result))))
    (is (= 1 (:serverless-output (kinds serverless-result))))
    (is (= 3 (get (relations serverless-result) :references 0)))
    (is (contains? (labels sam-result) "PanelFunction"))
    (is (contains? (labels sam-result) "PanelFunctionRole"))
    (is (contains? (labels sam-result) "app.handler"))
    (is (contains? (labels sam-result) "python3.12"))
    (is (contains? (labels sam-result) "PanelApi"))
    (is (contains? (labels sam-result) "PanelFunctionArn"))
    (is (= 1 (:sam-function (kinds sam-result))))
    (is (= 2 (:sam-resource (kinds sam-result))))
    (is (= 1 (:sam-event (kinds sam-result))))
    (is (= 1 (:sam-output (kinds sam-result))))
    (is (= 3 (get (relations sam-result) :references 0)))
    (is (contains? (labels cdk-result) "npx ts-node --prefer-ts-exts bin/panels.ts"))
    (is (contains? (labels cdk-result) "@aws-cdk/core:newStyleStackSynthesis"))
    (is (contains? (labels cdk-result) "panels:stage=dev"))
    (is (contains? (labels cdk-result) "lib/**/*.ts"))
    (is (= 1 (:cdk-app (kinds cdk-result))))
    (is (= 2 (:cdk-context-key (kinds cdk-result))))
    (is (= 2 (:cdk-context-setting (kinds cdk-result))))
    (is (= 1 (:cdk-watch-include (kinds cdk-result))))
    (is (= 1 (:cdk-watch-exclude (kinds cdk-result))))
    (is (contains? (labels ansible-result) "hosts=web"))
    (is (contains? (labels ansible-result) "Install nginx"))
    (is (contains? (labels ansible-result) "ansible.builtin.package"))
    (is (= 1 (:ansible-play (kinds ansible-result))))
    (is (= 2 (:ansible-task (kinds ansible-result))))
    (is (= 2 (:ansible-module (kinds ansible-result))))
    (is (contains? (labels nginx-result) "8080"))
    (is (contains? (labels nginx-result) "/api"))
    (is (contains? (labels nginx-result) "http://panel_api"))
    (is (= 1 (:ops-port (kinds nginx-result))))
    (is (= 1 (:ops-route (kinds nginx-result))))
    (is (= 1 (:config-reference (kinds nginx-result))))
    (is (contains? (labels caddy-result) "ops/Caddyfile"))
    (is (= [:ops-config-file] (mapv :kind (:chunks caddy-result))))
    (is (contains? (labels systemd-result) "panels.service"))
    (is (contains? (labels systemd-result) "/usr/bin/panels-worker"))
    (is (contains? (labels systemd-result) "network.target"))
    (is (= 1 (:systemd-unit (kinds systemd-result))))
    (is (= 3 (:systemd-section (kinds systemd-result))))
    (is (= 1 (:systemd-command (kinds systemd-result))))
    (is (= 2 (:systemd-target (kinds systemd-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks cloudformation-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks serverless-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks sam-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks cdk-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks systemd-result))))))
(deftest extracts-test-and-tool-config-facts
  (let [jest-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/tooling/jest.config.js"))
        playwright-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/tooling/playwright.config.ts"))
        eslint-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/tooling/eslint.config.js"))
        prettier-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/.prettierrc"))
        stylelint-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/tooling/stylelint.config.js"))
        tsconfig-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/tooling/tsconfig.json"))
        vite-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/tooling/vite.config.ts"))
        pytest-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/tooling/pytest.ini"))
        dependabot-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/.github/dependabot.yml"))
        renovate-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/tooling/renovate.json"))
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :test-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/jest.config.js"))))
    (is (= :test-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/playwright.config.ts"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/eslint.config.js"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/.prettierrc"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/tsconfig.json"))))
    (is (= :web-framework (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/tooling/vite.config.ts"))))
    (is (= :test-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/pytest.ini"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/.github/dependabot.yml"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/renovate.json"))))
    (is (contains? (labels jest-result) "tooling/jest.config.js"))
    (is (contains? (labels jest-result) "testEnvironment=jsdom"))
    (is (contains? (labels playwright-result) "@playwright/test"))
    (is (contains? (labels playwright-result) "testDir=./e2e"))
    (is (contains? (labels playwright-result) "reporter=dot"))
    (is (contains? (labels eslint-result) "@eslint/js"))
    (is (contains? (labels eslint-result) "rules"))
    (is (contains? (labels eslint-result) "semi=error"))
    (is (contains? (labels prettier-result) "semi=false"))
    (is (contains? (labels prettier-result) "singleQuote=true"))
    (is (contains? (labels stylelint-result) "stylelint-config-standard"))
    (is (contains? (labels stylelint-result) "rules"))
    (is (contains? (labels tsconfig-result) "extends=./tsconfig.base.json"))
    (is (contains? (labels vite-result) "@vitejs/plugin-react"))
    (is (contains? (labels pytest-result) "testpaths=tests"))
    (is (contains? (labels dependabot-result) "package-ecosystem=npm"))
    (is (contains? (labels dependabot-result) "npm:/"))
    (is (contains? (labels dependabot-result) "npm:/:interval=weekly"))
    (is (contains? (labels dependabot-result) "ui:@vitejs/*"))
    (is (contains? (labels renovate-result) "config:recommended"))
    (is (contains? (labels renovate-result) "ui"))
    (is (contains? (labels renovate-result) "ui:^@vitejs/"))
    (is (contains? (labels renovate-result) "npm"))
    (is (pos? (get (relations jest-result) :defines 0)))
    (is (pos? (get (relations playwright-result) :references 0)))
    (is (pos? (get (relations dependabot-result) :updates 0)))
    (is (pos? (get (relations renovate-result) :applies-to 0)))
    (is (= [:test-config-file] (mapv :kind (:chunks jest-result))))
    (is (= [:test-config-file] (mapv :kind (:chunks pytest-result))))
    (is (= [:tool-config-file] (mapv :kind (:chunks tsconfig-result))))
    (is (= [:tool-config-file] (mapv :kind (:chunks prettier-result))))))
(deftest extracts-typescript-module-test-and-tool-configs
  (let [root (doto (java.io.File/createTempFile "ygg-module-configs" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        jest-source (io/file root "jest.config.mts")
        playwright-source (io/file root "playwright.config.cts")
        eslint-source (io/file root "eslint.config.mts")
        tailwind-source (io/file root "tailwind.config.cts")
        tsconfig-source (io/file root "tsconfig.json")
        _ (spit jest-source "import base from 'jest-config';\nexport default {\n  testEnvironment: 'node',\n  reporters: ['default'],\n};\n")
        _ (spit playwright-source "import { defineConfig } from '@playwright/test';\nexport default defineConfig({\n  testDir: './e2e',\n});\n")
        _ (spit eslint-source "import js from '@eslint/js';\nexport default [\n  { rules: { semi: 'error' } },\n];\n")
        _ (spit tailwind-source "export default {\n  content: ['./src/**/*.tsx'],\n  theme: {},\n};\n")
        _ (spit tsconfig-source "{\"compilerOptions\":{\"paths\":{\"@libs/*\":[\"src/libs/*\"],\"@components/*\":[\"src/components/*\"]}}}\n")
        result-for (fn [source]
                     (extract/extract-file "run/test"
                                           (fs/file-record (.getPath root)
                                                           (.getPath source))))
        kind-for (fn [source]
                   (:kind (fs/file-record (.getPath root)
                                          (.getPath source))))
        labels (fn [result] (set (map :label (:nodes result))))
        jest-labels (labels (result-for jest-source))
        playwright-labels (labels (result-for playwright-source))
        eslint-labels (labels (result-for eslint-source))
        tailwind-labels (labels (result-for tailwind-source))
        tsconfig-result (result-for tsconfig-source)
        tsconfig-labels (labels tsconfig-result)
        tsconfig-kinds (frequencies (map :kind (:nodes tsconfig-result)))]
    (is (= :test-config (kind-for jest-source)))
    (is (= :test-config (kind-for playwright-source)))
    (is (= :tool-config (kind-for eslint-source)))
    (is (= :tool-config (kind-for tailwind-source)))
    (is (contains? jest-labels "jest-config"))
    (is (contains? jest-labels "testEnvironment=node"))
    (is (contains? playwright-labels "@playwright/test"))
    (is (contains? playwright-labels "testDir=./e2e"))
    (is (contains? eslint-labels "@eslint/js"))
    (is (contains? tailwind-labels "content"))
    (is (contains? tailwind-labels "theme"))
    (is (= 2 (:module-path-alias tsconfig-kinds)))
    (is (contains? tsconfig-labels "@libs/*=src/libs/*"))
    (is (contains? tsconfig-labels "@components/*=src/components/*"))))
(deftest extracts-editor-dev-environment-facts
  (let [editorconfig-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/.editorconfig"))
        settings-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/.vscode/settings.json"))
        extensions-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/.vscode/extensions.json"))
        tasks-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/.vscode/tasks.json"))
        workspace-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/panels.code-workspace"))
        vimrc-result (extract/extract-file
                      "run/test"
                      {:file-id "file:vimrc"
                       :id-scope "fixture"
                       :path "vimrc"
                       :kind (fs/file-kind "vimrc")
                       :content "set number\n"})
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :editor-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/.editorconfig"))))
    (is (= :editor-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/.vscode/settings.json"))))
    (is (= :editor-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/.vscode/tasks.json"))))
    (is (= :editor-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/.vscode/extensions.json"))))
    (is (= :editor-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/panels.code-workspace"))))
    (is (= :editor-config (fs/file-kind "vimrc")))
    (is (true? (fs/supported-path? "vimrc")))
    (is (contains? (labels editorconfig-result) "root=true"))
    (is (contains? (labels editorconfig-result) "*"))
    (is (contains? (labels editorconfig-result) "*:indent_style=space"))
    (is (contains? (labels settings-result) "editor.formatOnSave=true"))
    (is (contains? (labels settings-result) "files.trimTrailingWhitespace=true"))
    (is (contains? (labels settings-result) "java.configuration.updateBuildConfiguration=interactive"))
    (is (contains? (labels extensions-result) "ms-vscode.cpptools"))
    (is (contains? (labels extensions-result) "redhat.java"))
    (is (contains? (labels extensions-result) "example.legacy-extension"))
    (is (contains? (labels tasks-result) "lint"))
    (is (contains? (labels tasks-result) "test"))
    (is (contains? (labels tasks-result) "lint:bb lint"))
    (is (contains? (labels tasks-result) "test:bb test"))
    (is (contains? (labels tasks-result) "lint:shell"))
    (is (contains? (labels tasks-result) "lint:$eslint-stylish"))
    (is (contains? (labels workspace-result) "."))
    (is (contains? (labels workspace-result) "frontend"))
    (is (contains? (labels workspace-result) "files.eol=\n"))
    (is (contains? (labels workspace-result) "esbenp.prettier-vscode"))
    (is (contains? (labels workspace-result) "workspace-build"))
    (is (= 1 (:editor-profile (kinds editorconfig-result))))
    (is (= 3 (:editor-setting (kinds settings-result))))
    (is (= 2 (:editor-extension (kinds extensions-result))))
    (is (= 1 (:editor-extension-block (kinds extensions-result))))
    (is (= 2 (:editor-task (kinds tasks-result))))
    (is (= 2 (:editor-task-command (kinds tasks-result))))
    (is (= 2 (:editor-task-type (kinds tasks-result))))
    (is (= 1 (:editor-problem-matcher (kinds tasks-result))))
    (is (= 2 (:workspace-folder (kinds workspace-result))))
    (is (contains? (labels vimrc-result) "vimrc"))
    (is (pos? (get (relations settings-result) :defines 0)))
    (is (pos? (get (relations extensions-result) :references 0)))
    (is (= 1 (get (relations tasks-result) :depends-on 0)))
    (is (= [:editor-config-file] (mapv :kind (:chunks settings-result))))
    (is (= [:editor-config-file :editor-task :editor-task]
           (mapv :kind (:chunks tasks-result))))
    (is (= [:editor-config-file :editor-task]
           (mapv :kind (:chunks workspace-result))))
    (is (= [:editor-config-file] (mapv :kind (:chunks vimrc-result))))))
(deftest extracts-release-change-management-facts
  (let [changeset-config-result (extract/extract-file
                                 "run/test"
                                 (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/.changeset/config.json"))
        changeset-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/.changeset/bright-panels.md"))
        release-please-result (extract/extract-file
                               "run/test"
                               (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/release-please-config.json"))
        manifest-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/.release-please-manifest.json"))
        semantic-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/.releaserc.json"))
        semantic-yaml-result (extract/extract-file
                              "run/test"
                              (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/.releaserc.yaml"))
        standard-version-result (extract/extract-file
                                 "run/test"
                                 (fs/file-record "test/fixtures/extractor-repo"
                                                 "test/fixtures/extractor-repo/standard-version.json"))
        versionrc-yaml-result (extract/extract-file
                               "run/test"
                               (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/.versionrc.yml"))
        changelog-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/CHANGELOG.md"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (doseq [path [".changeset/config.json"
                  ".changeset/bright-panels.md"
                  "release-please-config.json"
                  ".release-please-manifest.json"
                  ".releaserc.json"
                  ".releaserc.yaml"
                  "standard-version.json"
                  ".versionrc.yml"
                  "CHANGELOG.md"]]
      (is (= :release-config
             (:kind (fs/file-record "test/fixtures/extractor-repo"
                                    (str "test/fixtures/extractor-repo/" path))))))
    (is (contains? (labels changeset-config-result) "main"))
    (is (contains? (labels changeset-config-result) "@changesets/cli/changelog"))
    (is (contains? (labels changeset-result) "@acme/panels"))
    (is (contains? (labels changeset-result) "@acme/panels:minor"))
    (is (contains? (labels changeset-result) "@acme/theme:patch"))
    (is (contains? (labels release-please-result) "."))
    (is (contains? (labels release-please-result) "packages/theme"))
    (is (contains? (labels release-please-result) ".:panels"))
    (is (contains? (labels release-please-result) "packages/theme:@acme/theme"))
    (is (contains? (labels release-please-result) ".:CHANGELOG.md"))
    (is (contains? (labels manifest-result) ".=1.4.0"))
    (is (contains? (labels manifest-result) "packages/theme=0.7.2"))
    (is (contains? (labels semantic-result) "main"))
    (is (contains? (labels semantic-result) "next"))
    (is (contains? (labels semantic-result) "@semantic-release/commit-analyzer"))
    (is (contains? (labels semantic-result) "@semantic-release/changelog"))
    (is (contains? (labels semantic-yaml-result) "main"))
    (is (contains? (labels semantic-yaml-result) "next"))
    (is (contains? (labels semantic-yaml-result) "@semantic-release/commit-analyzer"))
    (is (contains? (labels semantic-yaml-result) "@semantic-release/changelog"))
    (is (contains? (labels standard-version-result) "v"))
    (is (contains? (labels standard-version-result) "feat"))
    (is (contains? (labels standard-version-result) "Features"))
    (is (contains? (labels versionrc-yaml-result) "v"))
    (is (contains? (labels versionrc-yaml-result) "fix"))
    (is (contains? (labels versionrc-yaml-result) "Bug Fixes"))
    (is (contains? (labels changelog-result) "Changelog"))
    (is (contains? (labels changelog-result) "1.4.0"))
    (is (contains? (labels changelog-result) "Bug Fixes"))
    (is (= 1 (:release-branch (kinds changeset-config-result))))
    (is (= 2 (:release-version-change (kinds changeset-result))))
    (is (= 2 (:release-package (kinds release-please-result))))
    (is (= 2 (:release-version (kinds manifest-result))))
    (is (= 2 (:release-plugin (kinds semantic-result))))
    (is (= 2 (:release-plugin (kinds semantic-yaml-result))))
    (is (= 2 (:release-type (kinds standard-version-result))))
    (is (= 1 (:release-type (kinds versionrc-yaml-result))))
    (is (= 5 (:changelog-section (kinds changelog-result))))
    (is (pos? (get (relations release-please-result) :references 0)))
    (is (pos? (get (relations semantic-result) :uses 0)))
    (is (= [:release-config-file :release-change]
           (mapv :kind (:chunks changeset-result))))
    (is (= [:release-config-file :changelog-section :changelog-section
            :changelog-section :changelog-section :changelog-section]
           (mapv :kind (:chunks changelog-result))))))
(deftest extracts-compose-helm-and-yaml-infra-facts
  (let [compose-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/infra/docker-compose.yml"))
        helm-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/infra/chart/Chart.yaml"))
        helm-values-result (extract/extract-file
                            "run/test"
                            (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/infra/chart/values.yaml"))
        yaml-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/infra/k8s/deployment.yaml"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :compose (:kind (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/infra/docker-compose.yml"))))
    (is (= :helm (:kind (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/infra/chart/Chart.yaml"))))
    (is (= :yaml (:kind (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/infra/k8s/deployment.yaml"))))
    (is (= 2 (:compose-service (kinds compose-result))))
    (is (= 2 (:container-image (kinds compose-result))))
    (is (= 1 (:build-reference (kinds compose-result))))
    (is (= 1 (:container-port (kinds compose-result))))
    (is (= 1 (:runtime-volume (kinds compose-result))))
    (is (= 1 (:compose-network (kinds compose-result))))
    (is (= 3 (:runtime-env-var (kinds compose-result))))
    (is (contains? (labels compose-result) "web"))
    (is (contains? (labels compose-result) "worker"))
    (is (contains? (labels compose-result) "ghcr.io/acme/panels-web:latest"))
    (is (contains? (labels compose-result) "./web"))
    (is (contains? (labels compose-result) "8080:8080"))
    (is (contains? (labels compose-result) "./web/config:/app/config:ro"))
    (is (contains? (labels compose-result) "frontend"))
    (is (contains? (labels compose-result) "web:PANEL_ENV"))
    (is (contains? (labels compose-result) "web:PANEL_TOKEN"))
    (is (contains? (labels compose-result) "worker:PANEL_QUEUE"))
    (is (not (contains? (labels compose-result) "condition")))
    (is (not (contains? (labels compose-result) "panels-web")))
    (is (= 6 (:defines (relations compose-result))))
    (is (= 5 (:uses (relations compose-result))))
    (is (= 1 (:requires (relations compose-result))))
    (is (= 1 (:references (relations compose-result))))
    (is (contains? (labels helm-result) "panels"))
    (is (contains? (labels helm-result) "0.1.0"))
    (is (contains? (labels helm-result) "1.2.3"))
    (is (contains? (labels helm-result) "redis"))
    (is (= 1 (:helm-chart (kinds helm-result))))
    (is (= 1 (:helm-chart-version (kinds helm-result))))
    (is (= 1 (:helm-app-version (kinds helm-result))))
    (is (= 1 (:helm-dependency (kinds helm-result))))
    (is (= 3 (:defines (relations helm-result))))
    (is (= 1 (:references (relations helm-result))))
    (is (contains? (labels helm-values-result) "repository=ghcr.io/acme/panels-web"))
    (is (contains? (labels helm-values-result) "tag=1.2.3"))
    (is (contains? (labels helm-values-result) "pullPolicy=IfNotPresent"))
    (is (= 3 (:helm-value (kinds helm-values-result))))
    (is (contains? (labels yaml-result) "Deployment/panels-web"))
    (is (contains? (labels yaml-result) "Service/panels-web"))
    (is (contains? (labels yaml-result) "Deployment/panels-web:apps/v1"))
    (is (contains? (labels yaml-result) "Service/panels-web:v1"))
    (is (contains? (labels yaml-result) "Deployment/panels-web:panels"))
    (is (contains? (labels yaml-result) "Service/panels-web:panels"))
    (is (contains? (labels yaml-result) "ghcr.io/acme/panels-web:1.2.3"))
    (is (= 2 (:k8s-resource (kinds yaml-result))))
    (is (= 2 (:k8s-api-version (kinds yaml-result))))
    (is (= 2 (:k8s-namespace (kinds yaml-result))))
    (is (= 1 (:container-image (kinds yaml-result))))
    (is (= 6 (:defines (relations yaml-result))))
    (is (= 1 (:uses (relations yaml-result))))
    (is (= [:compose-file] (mapv :kind (:chunks compose-result))))
    (is (= [:helm-file] (mapv :kind (:chunks helm-result))))
    (is (= [:helm-file] (mapv :kind (:chunks helm-values-result))))
    (is (= [:yaml-file] (mapv :kind (:chunks yaml-result))))))
(deftest extracts-container-runtime-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        docker (result-for "runtime/Dockerfile")
        containerfile (result-for "runtime/Containerfile")
        docker-variant (extract/extract-file
                        "run/test"
                        {:file-id "file:runtime/Dockerfile.backend"
                         :id-scope "fixture"
                         :path "runtime/Dockerfile.backend"
                         :kind (fs/file-kind "runtime/Dockerfile.backend")
                         :content "FROM alpine:3.20 AS backend\nCMD [\"backend\"]\n"})
        procfile (result-for "runtime/Procfile")]
    (is (= :docker (kind-for "runtime/Dockerfile")))
    (is (= :docker (kind-for "runtime/Containerfile")))
    (is (= :docker (fs/file-kind "runtime/Dockerfile.backend")))
    (is (true? (fs/supported-path? "runtime/Dockerfile.backend")))
    (is (= :procfile (kind-for "runtime/Procfile")))
    (is (contains? (labels docker) "deps"))
    (is (contains? (labels docker) "build"))
    (is (contains? (labels docker) "runtime"))
    (is (contains? (labels docker) "eclipse-temurin:21-jdk"))
    (is (contains? (labels docker) "eclipse-temurin:21-jre"))
    (is (contains? (labels docker) "/workspace"))
    (is (contains? (labels docker) "runtime:PANEL_ENV"))
    (is (contains? (labels docker) "runtime:PANEL_DEBUG"))
    (is (contains? (labels docker) "8080"))
    (is (contains? (labels docker) "CMD [\"/app/bin/panels\"]"))
    (is (contains? (labels docker) "build.gradle"))
    (is (= 3 (:docker-stage (kinds docker))))
    (is (= 2 (:container-image (kinds docker))))
    (is (= 2 (:docker-workdir (kinds docker))))
    (is (= 2 (:runtime-env-var (kinds docker))))
    (is (= 1 (:container-port (kinds docker))))
    (is (= 3 (:runtime-command (kinds docker))))
    (is (= 4 (:docker-copy-source (kinds docker))))
    (is (= 11 (:defines (relations docker))))
    (is (= 2 (:uses (relations docker))))
    (is (= 1 (:depends-on (relations docker))))
    (is (= 4 (:references (relations docker))))
    (is (= [:docker-file :docker-stage :docker-stage :docker-stage]
           (mapv :kind (:chunks docker))))
    (is (contains? (labels containerfile) "alpine:3.20"))
    (is (= 1 (:docker-stage (kinds containerfile))))
    (is (= 1 (:container-image (kinds containerfile))))
    (is (= 2 (:runtime-command (kinds containerfile))))
    (is (contains? (labels docker-variant) "backend"))
    (is (contains? (labels docker-variant) "CMD [\"backend\"]"))
    (is (= [:docker-file :docker-stage]
           (mapv :kind (:chunks docker-variant))))
    (is (contains? (labels procfile) "web"))
    (is (contains? (labels procfile) "worker"))
    (is (contains? (labels procfile) "web:bin/panels-web --port $PORT"))
    (is (contains? (labels procfile) "worker:bin/panels-worker"))
    (is (= 2 (:runtime-process (kinds procfile))))
    (is (= 2 (:runtime-command (kinds procfile))))
    (is (= 4 (:defines (relations procfile))))
    (is (= [:procfile :runtime-process :runtime-process]
           (mapv :kind (:chunks procfile))))))

(deftest runtime-config-extractors-redact-sensitive-assignment-values
  (let [extract-inline (fn [path kind content]
                         (extract/extract-file
                          "run/test"
                          {:file-id (str "file:" path)
                           :id-scope "fixture"
                           :path path
                           :kind kind
                           :content content}))
        docker (extract-inline "runtime/Dockerfile"
                               :docker
                               (str "FROM alpine:3.20\n"
                                    "ENV SERVICE_ACCT=checkout-runtime-secret\n"
                                    "CMD SERVICE_ACCT=checkout-runtime-secret bin/start\n"))
        procfile (extract-inline "runtime/Procfile"
                                 :procfile
                                 "web: CLIENT_SECRET=client-secret-value bin/web\n")
        compose (extract-inline "compose.yaml"
                                :compose
                                (str "services:\n"
                                     "  api:\n"
                                     "    image: demo:latest\n"
                                     "    environment:\n"
                                     "      SERVICE_ACCT: checkout-runtime-secret\n"
                                     "      API_TOKEN: token-value\n"))
        exposed (pr-str (for [result [docker procfile compose]]
                          {:labels (mapv :label (:nodes result))
                           :chunks (mapv :text (:chunks result))}))]
    (is (re-find #"SERVICE_ACCT|CLIENT_SECRET|API_TOKEN" exposed))
    (is (not (re-find #"checkout-runtime-secret|client-secret-value|token-value" exposed)))
    (is (re-find #"SERVICE_ACCT=<redacted>" exposed))
    (is (re-find #"CLIENT_SECRET=<redacted>" exposed))
    (is (re-find #"API_TOKEN: <redacted>" exposed))))

(deftest extracts-cloud-iac-and-framework-route-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        cfn-json (result-for "infra/cloudformation.json")
        crd (result-for "infra/k8s/crd.yaml")
        crossplane (result-for "infra/k8s/crossplane.yaml")
        argocd (result-for "infra/k8s/argocd-application.yaml")
        helm-template (result-for "infra/chart/templates/deployment.yaml")
        symfony-routes (result-for "framework/config/routes.yaml")
        php-routes (result-for "framework/routes/web.php")]
    (is (= :ops-config (kind-for "infra/cloudformation.json")))
    (is (= :helm (kind-for "infra/chart/templates/deployment.yaml")))
    (is (contains? (labels cfn-json) "PanelQueue"))
    (is (contains? (labels cfn-json) "AWS::SQS::Queue"))
    (is (= 1 (get (relations cfn-json) :references 0)))
    (is (contains? (labels crd) "CustomResourceDefinition/panels.example.com"))
    (is (contains? (labels crd) "example.com"))
    (is (contains? (labels crd) "Panel"))
    (is (contains? (labels crd) "v1alpha1"))
    (is (= 1 (:k8s-crd-kind (kinds crd))))
    (is (contains? (labels crossplane) "Bucket/panel-assets"))
    (is (contains? (labels crossplane) "aws-prod"))
    (is (= 1 (:crossplane-resource (kinds crossplane))))
    (is (contains? (labels argocd) "panels"))
    (is (contains? (labels argocd) "https://github.com/acme/panels"))
    (is (contains? (labels argocd) "deploy/panels"))
    (is (contains? (labels argocd) "https://kubernetes.default.svc"))
    (is (= 1 (:argocd-application (kinds argocd))))
    (is (contains? (labels helm-template) "Deployment/{{ include \"panels.fullname\" . }}"))
    (is (contains? (labels helm-template) "Deployment/{{ include \"panels.fullname\" . }}:apps/v1"))
    (is (contains? (labels helm-template) "{{ .Values.image.repository }}:{{ .Values.image.tag }}"))
    (is (contains? (labels symfony-routes) "/panels/{id}"))
    (is (contains? (labels symfony-routes) "App\\Controller\\PanelController::show"))
    (is (contains? (labels php-routes) "GET /panels"))
    (is (contains? (labels php-routes) "POST /panels/{id}"))
    (is (contains? (labels php-routes) "/admin/panels"))))
(deftest extracts-web-framework-config-and-route-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        next-config (result-for "web-frameworks/next/next.config.mjs")
        next-index (result-for "web-frameworks/next/app/page.tsx")
        next-panel (result-for "web-frameworks/next/app/panels/[id]/page.tsx")
        svelte-config (result-for "web-frameworks/svelte/svelte.config.js")
        svelte-index (result-for "web-frameworks/svelte/src/routes/+page.svelte")
        svelte-panel (result-for "web-frameworks/svelte/src/routes/panels/[id]/+page.svelte")
        nuxt-config (result-for "web-frameworks/nuxt/nuxt.config.ts")
        nuxt-panel (result-for "web-frameworks/nuxt/pages/panels/[id].vue")
        astro-config (result-for "web-frameworks/astro/astro.config.mjs")
        astro-panel (result-for "web-frameworks/astro/src/pages/blog/[slug].astro")
        angular-config (result-for "web-frameworks/angular/angular.json")
        angular-routes (result-for "web-frameworks/angular/src/app/app.routes.ts")
        remix-config (result-for "web-frameworks/remix/remix.config.mjs")
        remix-index (result-for "web-frameworks/remix/app/routes/_index.tsx")
        remix-panel (result-for "web-frameworks/remix/app/routes/panels.$id.tsx")
        ember-router (result-for "web-frameworks/ember/app/router.js")
        ember-config (result-for "web-frameworks/ember/config/environment.js")
        vite-config (result-for "web-frameworks/vite/vite.config.ts")]
    (doseq [path ["web-frameworks/next/next.config.mjs"
                  "web-frameworks/next/app/page.tsx"
                  "web-frameworks/next/app/panels/[id]/page.tsx"
                  "web-frameworks/svelte/svelte.config.js"
                  "web-frameworks/svelte/src/routes/+page.svelte"
                  "web-frameworks/svelte/src/routes/panels/[id]/+page.svelte"
                  "web-frameworks/nuxt/nuxt.config.ts"
                  "web-frameworks/nuxt/pages/panels/[id].vue"
                  "web-frameworks/astro/astro.config.mjs"
                  "web-frameworks/astro/src/pages/blog/[slug].astro"
                  "web-frameworks/angular/angular.json"
                  "web-frameworks/angular/src/app/app.routes.ts"
                  "web-frameworks/remix/remix.config.mjs"
                  "web-frameworks/remix/app/routes/_index.tsx"
                  "web-frameworks/remix/app/routes/panels.$id.tsx"
                  "web-frameworks/ember/app/router.js"
                  "web-frameworks/ember/config/environment.js"
                  "web-frameworks/vite/vite.config.ts"]]
      (is (= :web-framework (kind-for path))))
    (is (contains? (labels next-config) "next"))
    (is (contains? (labels next-config) "@next/bundle-analyzer"))
    (is (contains? (labels next-config) "/panels"))
    (is (contains? (labels next-config) "/assets"))
    (is (contains? (labels next-index) "/"))
    (is (contains? (labels next-index) "/:page"))
    (is (contains? (labels next-panel) "/panels/{id}"))
    (is (contains? (labels next-panel) "/panels/{id}:page"))
    (is (contains? (labels next-panel) "web_frameworks.next.components.PanelDetails"))
    (is (contains? (labels svelte-config) "sveltekit"))
    (is (contains? (labels svelte-config) "@sveltejs/adapter-auto"))
    (is (contains? (labels svelte-index) "/"))
    (is (contains? (labels svelte-panel) "/panels/{id}"))
    (is (contains? (labels svelte-panel) "$lib/PanelDetails.svelte"))
    (is (contains? (labels nuxt-config) "nuxt"))
    (is (contains? (labels nuxt-config) "@nuxt/image"))
    (is (contains? (labels nuxt-config) "@pinia/nuxt"))
    (is (contains? (labels nuxt-panel) "/panels/{id}"))
    (is (contains? (labels astro-config) "astro"))
    (is (contains? (labels astro-config) "@astrojs/node"))
    (is (contains? (labels astro-config) "/astro-panels"))
    (is (contains? (labels astro-panel) "/blog/{slug}"))
    (is (contains? (labels astro-panel) "web_frameworks.astro.src.components.BlogPost"))
    (is (contains? (labels angular-config) "angular"))
    (is (contains? (labels angular-config) "panels-web"))
    (is (contains? (labels angular-config) "panels-web:projects/panels-web"))
    (is (contains? (labels angular-config)
                   "panels-web:build:@angular-devkit/build-angular:browser"))
    (is (contains? (labels angular-routes) "/"))
    (is (contains? (labels angular-routes) "/panels/{id}"))
    (is (contains? (labels angular-routes) "/reports"))
    (is (contains? (labels angular-routes) "/old-panels:/panels"))
    (is (contains? (labels angular-routes) "/panels/{id}:PanelDetailsComponent"))
    (is (contains? (labels angular-routes)
                   "web_frameworks.angular.src.app.reports.reports.routes"))
    (is (contains? (labels remix-config) "remix"))
    (is (contains? (labels remix-config) "@remix-run/dev"))
    (is (contains? (labels remix-index) "/"))
    (is (contains? (labels remix-index) "/:route-module"))
    (is (contains? (labels remix-index) "/:loader"))
    (is (contains? (labels remix-panel) "/panels/{id}"))
    (is (contains? (labels remix-panel) "/panels/{id}:route-module"))
    (is (contains? (labels remix-panel) "/panels/{id}:loader"))
    (is (contains? (labels remix-panel) "/panels/{id}:action"))
    (is (contains? (labels ember-router) "ember"))
    (is (contains? (labels ember-router) "/panels"))
    (is (contains? (labels ember-router) "/panels/{id}"))
    (is (contains? (labels ember-router) "@ember/routing/router"))
    (is (contains? (labels ember-config) "ember"))
    (is (contains? (labels ember-config) "panels-web"))
    (is (contains? (labels ember-config) "/"))
    (is (contains? (labels ember-config) "locationType:history"))
    (is (contains? (labels vite-config) "vite"))
    (is (contains? (labels vite-config) "@vitejs/plugin-react"))
    (is (contains? (labels vite-config) "/vite-panels"))
    (is (= 1 (:web-framework-plugin (kinds next-config))))
    (is (= 1 (:web-framework-page (kinds next-panel))))
    (is (= 1 (:web-framework-page (kinds svelte-panel))))
    (is (= 2 (:web-framework-module (kinds nuxt-config))))
    (is (= 1 (:web-framework-project (kinds angular-config))))
    (is (= 4 (:web-framework-route (kinds angular-routes))))
    (is (= 2 (:web-framework-component (kinds angular-routes))))
    (is (= 1 (:web-framework-route-redirect (kinds angular-routes))))
    (is (= 1 (:web-framework-loader (kinds remix-index))))
    (is (= 1 (:web-framework-action (kinds remix-panel))))
    (is (= 2 (:web-framework-route (kinds ember-router))))
    (is (= 1 (:web-framework-setting (kinds ember-config))))
    (is (pos? (get (relations next-panel) :imports 0)))
    (is (pos? (get (relations angular-config) :uses 0)))
    (is (pos? (get (relations angular-routes) :imports 0)))
    (is (some #(= :typescript-file (:kind %)) (:chunks next-panel)))
    (is (some #(= :typescript-file (:kind %)) (:chunks angular-routes)))
    (is (some #(= :javascript-file (:kind %)) (:chunks ember-router)))
    (is (some #(= :web-framework-file (:kind %)) (:chunks next-panel)))
    (is (some #(= :web-framework-file (:kind %)) (:chunks remix-panel)))
    (is (some #(= :astro-file (:kind %)) (:chunks astro-panel)))))
(deftest extracts-typescript-commonjs-web-route-facts
  (let [root (doto (java.io.File/createTempFile "ygg-route" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        source (io/file root "app/panels/[id]/route.cts")
        _ (.mkdirs (.getParentFile source))
        _ (spit source "import { loadPanel } from '../../data';\nexport const GET = loadPanel;\n")
        file (fs/file-record (.getPath root) (.getPath source))
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))]
    (is (= :web-framework (:kind file)))
    (is (contains? labels "/panels/{id}"))
    (is (contains? labels "/panels/{id}:route"))
    (is (contains? labels "app.data"))
    (is (some #(= :typescript-file (:kind %)) (:chunks result)))
    (is (empty? (:diagnostics result)))))
(deftest extracts-typescript-module-web-framework-configs
  (let [root (doto (java.io.File/createTempFile "ygg-web-config" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        next-source (io/file root "next.config.mts")
        vite-source (io/file root "vite.config.cts")
        _ (spit next-source "import analyzer from '@next/bundle-analyzer';\nexport default { basePath: '/panels' };\n")
        _ (spit vite-source "import react from '@vitejs/plugin-react';\nexport default { base: '/vite-panels', plugins: [react()] };\n")
        result-for (fn [source]
                     (extract/extract-file "run/test"
                                           (fs/file-record (.getPath root)
                                                           (.getPath source))))
        next-file (fs/file-record (.getPath root) (.getPath next-source))
        vite-file (fs/file-record (.getPath root) (.getPath vite-source))
        next-labels (set (map :label (:nodes (result-for next-source))))
        vite-labels (set (map :label (:nodes (result-for vite-source))))]
    (is (= :web-framework (:kind next-file)))
    (is (= :web-framework (:kind vite-file)))
    (is (contains? next-labels "next"))
    (is (contains? next-labels "@next/bundle-analyzer"))
    (is (contains? next-labels "/panels"))
    (is (contains? vite-labels "vite"))
    (is (contains? vite-labels "@vitejs/plugin-react"))
    (is (contains? vite-labels "/vite-panels"))))
(deftest extracts-workflow-orchestration-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        edge-pairs (fn [result relation]
                     (set (map (fn [{:keys [source-id target-id]}]
                                 [source-id target-id])
                               (filter #(= relation (:relation %))
                                       (:edges result)))))
        airflow (result-for "workflows/airflow/panel_dag.py")
        dagster-source (result-for "workflows/dagster/assets.py")
        dagster-config (result-for "workflows/dagster/dagster.yaml")
        prefect-source (result-for "workflows/prefect/flows.py")
        prefect-config (result-for "workflows/prefect/prefect.yaml")
        temporal (result-for "workflows/temporal/workflow.ts")
        argo (result-for "workflows/argo/workflow.yaml")
        tekton (result-for "workflows/tekton/pipeline.yaml")]
    (doseq [path ["workflows/airflow/panel_dag.py"
                  "workflows/dagster/assets.py"
                  "workflows/dagster/dagster.yaml"
                  "workflows/prefect/flows.py"
                  "workflows/prefect/prefect.yaml"
                  "workflows/temporal/workflow.ts"
                  "workflows/argo/workflow.yaml"
                  "workflows/tekton/pipeline.yaml"]]
      (is (= :workflow-orchestration (kind-for path))))
    (is (contains? (labels airflow) "airflow"))
    (is (contains? (labels airflow) "panel_refresh"))
    (is (contains? (labels airflow) "extract"))
    (is (contains? (labels airflow) "transform"))
    (is (contains? (labels airflow) "schedule_interval:0 2 * * *"))
    (is (contains? (edge-pairs airflow :precedes)
                   ["node:workflow-task:extract"
                    "node:workflow-task:transform"]))
    (is (contains? (labels dagster-source) "dagster"))
    (is (contains? (labels dagster-source) "panel_asset"))
    (is (contains? (labels dagster-source) "load_panel"))
    (is (contains? (labels dagster-source) "panel_job"))
    (is (contains? (labels dagster-source) "panel_schedule"))
    (is (contains? (labels dagster-config) "panels.assets"))
    (is (contains? (labels prefect-source) "prefect"))
    (is (contains? (labels prefect-source) "refresh_panels"))
    (is (contains? (labels prefect-source) "extract"))
    (is (contains? (labels prefect-config) "panels-prefect"))
    (is (contains? (labels prefect-config) "refresh"))
    (is (contains? (labels prefect-config) "refresh:flows.py:refresh_panels"))
    (is (contains? (labels prefect-config) "refresh:0 3 * * *"))
    (is (contains? (labels temporal) "temporal"))
    (is (contains? (labels temporal) "@temporalio/workflow"))
    (is (contains? (labels temporal) "panelWorkflow"))
    (is (contains? (labels argo) "argo-workflows"))
    (is (contains? (labels argo) "Workflow/panel-refresh"))
    (is (contains? (labels argo) "alpine:3.20"))
    (is (contains? (edge-pairs argo :requires)
                   ["node:workflow-task:transform"
                    "node:workflow-task:extract"]))
    (is (contains? (labels tekton) "tekton"))
    (is (contains? (labels tekton) "Pipeline/panel-pipeline"))
    (is (contains? (labels tekton) "extract:extract-task"))
    (is (contains? (labels tekton) "transform:transform-task"))
    (is (contains? (edge-pairs tekton :requires)
                   ["node:workflow-task:transform"
                    "node:workflow-task:extract"]))
    (is (= 2 (:workflow-task (kinds airflow))))
    (is (= 1 (:workflow-asset (kinds dagster-source))))
    (is (= 1 (:workflow-deployment (kinds prefect-config))))
    (is (= 1 (:workflow-activity (kinds temporal))))
    (is (pos? (get (relations airflow) :imports 0)))
    (is (some #(= :python-file (:kind %)) (:chunks airflow)))
    (is (some #(= :workflow-file (:kind %)) (:chunks airflow)))
    (is (= [:workflow-file] (mapv :kind (:chunks argo))))))
(deftest extracts-data-science-and-ml-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        edge-pairs (fn [result relation]
                     (set (map (fn [{:keys [source-id target-id]}]
                                 [source-id target-id])
                               (filter #(= relation (:relation %))
                                       (:edges result)))))
        dvc (result-for "ml/dvc/dvc.yaml")
        dvc-lock (result-for "ml/dvc/dvc.lock")
        dvc-file (result-for "ml/data/raw.csv.dvc")
        mlproject (result-for "ml/mlflow/MLproject")
        conda-env (result-for "ml/env/environment.yml")
        model-card (result-for "ml/cards/model-card.md")
        data-card (result-for "ml/cards/data-card.md")]
    (doseq [path ["ml/dvc/dvc.yaml"
                  "ml/dvc/dvc.lock"
                  "ml/data/raw.csv.dvc"
                  "ml/mlflow/MLproject"
                  "ml/env/environment.yml"
                  "ml/cards/model-card.md"
                  "ml/cards/data-card.md"]]
      (is (= :data-science (kind-for path))))
    (is (contains? (labels dvc) "dvc"))
    (is (contains? (labels dvc) "prepare"))
    (is (contains? (labels dvc) "train"))
    (is (contains? (labels dvc) "prepare:python prepare.py"))
    (is (contains? (labels dvc) "data/raw.csv"))
    (is (contains? (labels dvc) "data/prepared.csv"))
    (is (contains? (labels dvc) "metrics.json"))
    (is (contains? (labels dvc) "train.epochs"))
    (is (contains? (labels dvc-lock) "models/panel.pkl"))
    (is (contains? (labels dvc-file) "ml/data/raw.csv.dvc"))
    (is (contains? (labels dvc-file) "raw.csv"))
    (is (contains? (labels mlproject) "mlflow"))
    (is (contains? (labels mlproject) "panels-ml"))
    (is (contains? (labels mlproject) "conda.yaml"))
    (is (contains? (labels mlproject) "train"))
    (is (contains? (labels mlproject) "train:python train.py --epochs {epochs}"))
    (is (contains? (labels mlproject) "train:epochs"))
    (is (contains? (labels conda-env) "panel-lab"))
    (is (contains? (labels conda-env) "conda-forge"))
    (is (contains? (labels conda-env) "python=3.11"))
    (is (contains? (labels conda-env) "mlflow==2.12.1"))
    (is (contains? (labels model-card) "ml/cards/model-card.md"))
    (is (contains? (labels model-card) "panel-forecast"))
    (is (contains? (labels model-card) "panel_orders"))
    (is (contains? (labels model-card) "pipeline_tag:tabular-classification"))
    (is (contains? (labels data-card) "ml/cards/data-card.md"))
    (is (contains? (labels data-card) "dataset_name:panel_orders"))
    (is (contains? (labels data-card) "schema:schemas/panel_orders.json"))
    (is (contains? (edge-pairs dvc :produces)
                   ["node:ml-pipeline-stage:train"
                    "node:data-artifact:models/panel.pkl"]))
    (is (contains? (edge-pairs mlproject :uses)
                   ["node:mlflow-entry-point:train"
                    "node:pipeline-command:train:python train.py --epochs {epochs}"]))
    (is (contains? (edge-pairs conda-env :uses)
                   ["node:ml-environment:panel-lab"
                    "node:environment-dependency:pandas>=2"]))
    (is (contains? (edge-pairs model-card :defines)
                   ["node:model-card:ml/cards/model-card.md"
                    "node:ml-model:panel-forecast"]))
    (is (contains? (edge-pairs data-card :references)
                   ["node:data-card:ml/cards/data-card.md"
                    "node:data-artifact:panel_orders"]))
    (is (= 2 (:ml-pipeline-stage (kinds dvc))))
    (is (= 1 (:mlflow-project (kinds mlproject))))
    (is (= 1 (:mlflow-entry-point (kinds mlproject))))
    (is (= 4 (:environment-dependency (kinds conda-env))))
    (is (= 1 (:model-card (kinds model-card))))
    (is (= 1 (:data-card (kinds data-card))))
    (is (pos? (get (relations dvc) :produces 0)))
    (is (some #(= :markdown (:kind %)) (:chunks model-card)))
    (is (= [:data-science-file] (mapv :kind (:chunks dvc))))))
(deftest extracts-observability-config-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        edge-pairs (fn [result relation]
                     (set (map (fn [{:keys [source-id target-id]}]
                                 [source-id target-id])
                               (filter #(= relation (:relation %))
                                       (:edges result)))))
        otel (result-for "observability/otel/otelcol.yaml")
        prometheus (result-for "observability/prometheus/prometheus.yml")
        rules (result-for "observability/prometheus/rules.yaml")
        alertmanager (result-for "observability/prometheus/alertmanager.yml")
        datasources (result-for "observability/grafana/datasources.yaml")
        dashboard (result-for "observability/grafana/dashboard.json")
        vector (result-for "observability/logs/vector.yaml")]
    (doseq [path ["observability/otel/otelcol.yaml"
                  "observability/prometheus/prometheus.yml"
                  "observability/prometheus/rules.yaml"
                  "observability/prometheus/alertmanager.yml"
                  "observability/grafana/datasources.yaml"
                  "observability/grafana/dashboard.json"
                  "observability/logs/vector.yaml"]]
      (is (= :observability-config (kind-for path))))
    (is (contains? (labels otel) "otel-collector"))
    (is (contains? (labels otel) "otlp"))
    (is (contains? (labels otel) "batch"))
    (is (contains? (labels otel) "logging"))
    (is (contains? (labels otel) "traces"))
    (is (contains? (edge-pairs otel :uses)
                   ["node:otel-pipeline:traces" "node:otel-receiver:otlp"]))
    (is (contains? (labels prometheus) "prometheus"))
    (is (contains? (labels prometheus) "panels"))
    (is (contains? (labels prometheus) "panels:/metrics"))
    (is (contains? (labels prometheus) "panels:localhost:9090"))
    (is (contains? (edge-pairs prometheus :scrapes)
                   ["node:prometheus-scrape-job:panels"
                    "node:prometheus-target:panels:localhost:9090"]))
    (is (contains? (labels rules) "PanelLatencyHigh"))
    (is (contains? (labels alertmanager) "alertmanager"))
    (is (contains? (labels alertmanager) "team-default"))
    (is (contains? (labels datasources) "grafana"))
    (is (contains? (labels datasources) "Prometheus"))
    (is (contains? (labels datasources) "Prometheus:prometheus"))
    (is (contains? (labels datasources) "Prometheus:http://prometheus:9090"))
    (is (contains? (labels dashboard) "Panels"))
    (is (contains? (labels dashboard) "Panels:Latency"))
    (is (contains? (labels dashboard) "prometheus"))
    (is (contains? (edge-pairs dashboard :references)
                   ["node:grafana-panel:Panels:Latency"
                    "node:grafana-datasource:prometheus"]))
    (is (contains? (labels vector) "vector"))
    (is (contains? (labels vector) "app"))
    (is (contains? (labels vector) "parse"))
    (is (contains? (labels vector) "stdout"))
    (is (contains? (edge-pairs vector :uses)
                   ["node:log-sink:stdout" "node:log-source:parse"]))
    (is (= 1 (:otel-pipeline (kinds otel))))
    (is (= 1 (:prometheus-alert-rule (kinds rules))))
    (is (= 1 (:grafana-panel (kinds dashboard))))
    (is (= [:observability-file] (mapv :kind (:chunks otel))))))
(deftest extracts-quality-config-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        coverage (result-for "quality/.coveragerc")
        mypy (result-for "quality/mypy.ini")
        ruff (result-for "quality/ruff.toml")
        sonar (result-for "quality/sonar-project.properties")
        checkstyle (result-for "quality/checkstyle.xml")
        pmd (result-for "quality/pmd.xml")
        spotbugs (result-for "quality/spotbugs-exclude.xml")
        phpstan (result-for "quality/phpstan.neon")
        psalm (result-for "quality/psalm.xml")
        rubocop (result-for "quality/.rubocop.yml")
        swiftlint (result-for "quality/.swiftlint.yml")
        detekt (result-for "quality/detekt.yml")]
    (doseq [path ["quality/.coveragerc"
                  "quality/mypy.ini"
                  "quality/ruff.toml"
                  "quality/sonar-project.properties"
                  "quality/checkstyle.xml"
                  "quality/pmd.xml"
                  "quality/spotbugs-exclude.xml"
                  "quality/phpstan.neon"
                  "quality/psalm.xml"
                  "quality/.rubocop.yml"
                  "quality/.swiftlint.yml"
                  "quality/detekt.yml"]]
      (is (= :quality-config (kind-for path))))
    (is (contains? (labels coverage) "coverage.py"))
    (is (contains? (labels coverage) "branch=True"))
    (is (contains? (labels mypy) "mypy"))
    (is (contains? (labels mypy) "strict=True"))
    (is (contains? (labels ruff) "ruff"))
    (is (contains? (labels ruff) "line-length=100"))
    (is (contains? (labels sonar) "sonar"))
    (is (contains? (labels sonar) "sonar.projectKey=panels"))
    (is (contains? (labels checkstyle) "checkstyle"))
    (is (contains? (labels checkstyle) "AvoidStarImport"))
    (is (contains? (labels pmd) "category/java/bestpractices.xml/UnusedPrivateMethod"))
    (is (contains? (labels spotbugs) "EI_EXPOSE_REP"))
    (is (contains? (labels phpstan) "phpstan"))
    (is (contains? (labels phpstan) "includes=vendor/phpstan/phpstan-strict-rules/rules.neon"))
    (is (contains? (labels phpstan) "paths=src"))
    (is (contains? (labels psalm) "src"))
    (is (contains? (labels rubocop) "rubocop"))
    (is (contains? (labels rubocop) "inherit_from=.rubocop_todo.yml"))
    (is (contains? (labels rubocop) "require=rubocop-performance"))
    (is (contains? (labels rubocop) "Style/FrozenStringLiteralComment"))
    (is (contains? (labels swiftlint) "included=Sources"))
    (is (contains? (labels swiftlint) "opt_in_rules=explicit_init"))
    (is (contains? (labels swiftlint) "disabled_rules=force_cast"))
    (is (contains? (labels detekt) "detekt"))
    (is (contains? (labels detekt) "maxIssues=0"))
    (is (= 1 (:quality-tool (kinds sonar))))
    (is (pos? (:quality-setting (kinds coverage))))
    (is (= [:quality-config-file] (mapv :kind (:chunks phpstan))))))
