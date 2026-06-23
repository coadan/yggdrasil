(ns ygg.extract-test
  (:require [ygg.extract :as extract]
            [ygg.fs :as fs]
            [ygg.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def canonical-buckets
  #{:nodes :edges :chunks :diagnostics})

(deftest extract-file-returns-canonical-buckets
  (let [files [(fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/sample/core.clj")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/internal/cli/flows.go")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/java/com/example/panels/PanelService.java")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/groovy/PanelService.groovy")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/dotnet/PanelService.cs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/dotnet/Panels.csproj")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/android/AndroidManifest.xml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/android/PanelActivity.kt")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/android/res/layout/activity_panel.xml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/ios/Info.plist")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/ios/PanelViewModel.swift")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/ios/PanelService.m")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/ios/Panels.entitlements")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/ios/Debug.xcconfig")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mobile/expo/app.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/contracts/panels.graphql")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/contracts/panels.proto")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/contracts/events.asyncapi.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/contracts/panel.schema.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/contracts/panel.avsc")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/db/schema.prisma")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/dbt/dbt_project.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/dbt/panel_orders.sql")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/dbt/packages.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/dbt/profiles.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/dbt/models/schema.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/db/migration/V1__create_panels.sql")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/db/changelog/db.changelog-master.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/cloudformation.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/cloudformation.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/Pulumi.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/Pulumi.dev.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/serverless.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/template.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/cdk.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/playbook.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/nginx.conf")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/panels.service")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/db/drizzle.config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/codegen/graphql-codegen.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/package.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/composer.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/go.work")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/pnpm-workspace.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/.yarnrc.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/workspace/turbo.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/ruby/panel_service.rb")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/ruby/Gemfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/native/panel_service.cpp")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/flutter/lib/panel_store.dart")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/flutter/pubspec.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/scala/PanelService.scala")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/scala/build.sbt")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/elixir/panel_service.ex")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/elixir/mix.exs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/erlang/panel_service.erl")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/erlang/rebar.config")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/lua/panel_service.lua")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/r/panel_service.R")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/r/DESCRIPTION")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/julia/PanelService.jl")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/ocaml/panel_service.ml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/ocaml/panel_service.mli")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/julia/Project.toml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/perl/PanelService.pm")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/perl/cpanfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/haskell/PanelService.hs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/haskell/panels.cabal")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/odin/panel_service.odin")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/odin/ols.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/zig/panel_service.zig")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tooling/jest.config.js")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tooling/eslint.config.js")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tooling/tsconfig.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tooling/vite.config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tooling/pytest.ini")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.editorconfig")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.vscode/settings.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.vscode/tasks.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.vscode/extensions.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/panels.code-workspace")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.changeset/config.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.changeset/bright-panels.md")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/release-please-config.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.release-please-manifest.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.releaserc.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.releaserc.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/standard-version.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.versionrc.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/CHANGELOG.md")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.github/dependabot.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.storybook/main.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/docusaurus.config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/src/content.config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/.vitepress/config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/sphinx/conf.py")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/sphinx/index.rst")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/nextra/next.config.mjs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/docs/nextra/content/_meta.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/pages/index.astro")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/plugin/bobr-wordpress-connector.php")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/languages/messages.po")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/assets/logo.svg")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/config/wrangler.jsonc")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/infra/support.env.example")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/public/index.html")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/bobr/plugin/readme.txt")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/frontend/components/PanelList.vue")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/frontend/components/PanelDetails.svelte")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/build/Makefile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/build/toolchain.cmake")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/build/BUCK")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/docker-compose.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/runtime/Dockerfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/runtime/Containerfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/runtime/Procfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/chart/Chart.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/k8s/deployment.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/k8s/crd.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/k8s/crossplane.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/k8s/argocd-application.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/chart/templates/deployment.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/framework/config/routes.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/framework/routes/web.php")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/python/Pipfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/python/setup.cfg")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/python/setup.py")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.github/ISSUE_TEMPLATE/bug_report.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.github/PULL_REQUEST_TEMPLATE.md")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.github/FUNDING.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/SECURITY.md")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/CONTRIBUTING.md")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/LICENSE")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/NOTICE")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/sbom/cyclonedx.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/sbom/spdx.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/notebooks/panel_analysis.ipynb")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.devcontainer/devcontainer.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/kustomize/kustomization.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.pre-commit-config.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.github/CODEOWNERS")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tasks/Taskfile.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/tasks/justfile")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/build/rules/panels.bzl")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.tool-versions")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.node-version")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.python-version")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.ruby-version")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/mise.toml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/next/next.config.mjs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/next/app/page.tsx")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/next/app/panels/[id]/page.tsx")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/svelte/svelte.config.js")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/svelte/src/routes/+page.svelte")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/svelte/src/routes/panels/[id]/+page.svelte")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/nuxt/nuxt.config.ts")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/nuxt/pages/index.vue")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/nuxt/pages/panels/[id].vue")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/astro/astro.config.mjs")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/astro/src/pages/index.astro")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/astro/src/pages/blog/[slug].astro")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/angular/angular.json")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/web-frameworks/vite/vite.config.ts")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/web/app.ts")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/web/theme.scss")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/db/schema.sql")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/infra/main.tf")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/api/openapi.yaml")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/python/app.py")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/rust/lib.rs")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/docs/overview.md")
               {:file-id "file:config.yaml"
                :path "config.yaml"
                :kind :yaml
                :content ""}]]
    (doseq [file files
            :let [result (extract/extract-file "run/test" file)]]
      (is (= canonical-buckets (set (keys result))))
      (is (every? vector? (vals result)))
      (is (every? #(keyword? (:confidence %)) (:edges result))
          (str "extracted edge confidence must be canonical for " (:path file)))
      (doseq [edge (:edges result)]
        (is (= edge (schema/assert! schema/edge-row edge "Invalid edge row."))
            (str "extracted edge row must match sync schema for " (:path file)))))))

(deftest extracts-template-text-files-as-searchable-chunks
  (let [file {:file-id "file:template"
              :id-scope "fixture"
              :path "templates/service.go.tmpl"
              :kind :text
              :content "package {{ .Package }}\nfunc main() {}\n"}
        result (extract/extract-file "run/test" file)]
    (is (= [] (:nodes result)))
    (is (= [] (:edges result)))
    (is (= [] (:diagnostics result)))
    (is (= [{:path "templates/service.go.tmpl"
             :kind :text-file
             :file-kind :text
             :label "templates/service.go.tmpl"
             :source-line 1}]
           (mapv #(select-keys % [:path :kind :file-kind :label :source-line])
                 (:chunks result))))
    (is (str/includes? (-> result :chunks first :text) "{{ .Package }}"))))

(deftest extracts-clojure-namespace-definitions-and-requires
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/sample/core.clj")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "sample.core"))
    (is (contains? labels "sample.core/greet"))
    (is (contains? labels "sample.core/helper"))
    (is (= 1 (:requires relations)))
    (is (<= 2 (get relations :defines 0)))))

(deftest extracts-qualified-clojure-definition-macros
  (let [file {:file-id "file:menu"
              :id-scope "fixture"
              :path "frontend/src/app/main/ui/workspace/main_menu.cljs"
              :kind :code
              :content "(ns app.main.ui.workspace.main-menu)\n\n(mf/defc mcp-menu*\n  [{:keys [on-close]}]\n  [:div])\n"}
        result (extract/extract-file "run/test" file)
        node (some #(when (= "app.main.ui.workspace.main-menu/mcp-menu*"
                             (:label %))
                      %)
                   (:nodes result))
        chunk (some #(when (= "app.main.ui.workspace.main-menu/mcp-menu*"
                              (:label %))
                       %)
                    (:chunks result))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :component (:kind node)))
    (is (= "mcp-menu*" (:name node)))
    (is (= 3 (:source-line node)))
    (is (= :code-definition (:kind chunk)))
    (is (= :component (:definition-kind chunk)))
    (is (= 3 (:source-line chunk)))
    (is (= 5 (:end-line chunk)))
    (is (str/includes? (:text chunk) "on-close"))
    (is (pos? (get relations :defines 0)))))

(deftest extracts-clojure-definitions-with-reader-metadata
  (let [file {:file-id "file:diagnostics"
              :id-scope "fixture"
              :path "lib/src/clojure_lsp/feature/diagnostics/built_in.clj"
              :kind :code
              :content "(ns clojure-lsp.feature.diagnostics.built-in)\n\n(defn ^:private cyclic-dependencies\n  [db]\n  (:dep-graph db))\n\n(def ^{:private true} ignores-keywords #{:clj-kondo/ignore})\n"}
        result (extract/extract-file "run/test" file)
        node-by-label (into {} (map (juxt :label identity)) (:nodes result))
        chunk-by-label (into {} (map (juxt :label identity)) (:chunks result))
        cyclic-label "clojure-lsp.feature.diagnostics.built-in/cyclic-dependencies"
        ignores-label "clojure-lsp.feature.diagnostics.built-in/ignores-keywords"]
    (is (= :var (get-in node-by-label [cyclic-label :kind])))
    (is (false? (get-in node-by-label [cyclic-label :public?])))
    (is (= :code-definition (get-in chunk-by-label [cyclic-label :kind])))
    (is (= :var (get-in chunk-by-label [cyclic-label :definition-kind])))
    (is (str/includes? (get-in chunk-by-label [cyclic-label :text]) ":dep-graph"))
    (is (= :var (get-in node-by-label [ignores-label :kind])))
    (is (false? (get-in node-by-label [ignores-label :public?])))
    (is (= :code-definition (get-in chunk-by-label [ignores-label :kind])))
    (is (= :var (get-in chunk-by-label [ignores-label :definition-kind])))))

(deftest extracts-markdown-heading-chunks
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/docs/overview.md")
        result (extract/extract-file "run/test" file)
        chunk (first (:chunks result))]
    (is (= ["Greeting Flow"] (mapv :label (:chunks result))))
    (is (= ["Greeting Flow"] (:heading-path chunk)))
    (is (= 1 (:source-line chunk)))
    (is (= 3 (:end-line chunk)))
    (is (string? (:content-sha chunk)))
    (is (empty? (:diagnostics result)))))

(deftest extracts-pip-install-package-facts-from-docs
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:testinfra/README.md"
                 :id-scope "fixture"
                 :path "testinfra/README.md"
                 :kind :doc
                 :content (str "# Testinfra\n\n"
                               "pip3 install boto3 \"boto3-stubs[essential]\" "
                               "docker ec2instanceconnectcli pytest "
                               "\"pytest-testinfra[paramiko,docker]\" requests\n")})
        package-nodes (->> (:nodes result)
                           (filter #(= :external-package (:kind %))))
        package-labels (set (map :label package-nodes))
        boto3-node (some #(when (= "pypi:boto3" (:label %)) %) package-nodes)
        boto3-edge (some #(when (= (extract/node-id "fixture"
                                                    :external-package
                                                    "pypi:boto3")
                                   (:target-id %))
                            %)
                         (:edges result))]
    (is (= #{"pypi:boto3"
             "pypi:boto3-stubs"
             "pypi:docker"
             "pypi:ec2instanceconnectcli"
             "pypi:pytest"
             "pypi:pytest-testinfra"
             "pypi:requests"}
           package-labels))
    (is (= :pypi (:ecosystem boto3-node)))
    (is (= "boto3" (:package-name boto3-node)))
    (is (= "pip-install-command" (:dependency-scope boto3-node)))
    (is (= :requires (:relation boto3-edge)))
    (is (= :pypi (:ecosystem boto3-edge)))
    (is (= "boto3" (:package-name boto3-edge)))))

(deftest extracts-large-markdown-sections-as-bounded-chunks
  (let [body (str/join "\n" (map #(str "line " %) (range 1 251)))
        result (extract/extract-file
                "run/test"
                {:file-id "file:docs/large.md"
                 :path "docs/large.md"
                 :kind :doc
                 :content (str "# Large Guide\n" body "\n")})
        chunks (:chunks result)]
    (is (= ["Large Guide" "Large Guide" "Large Guide"]
           (mapv :label chunks)))
    (is (= [1 121 241] (mapv :source-line chunks)))
    (is (= [120 240 251] (mapv :end-line chunks)))
    (is (every? #(<= (count (str/split-lines (:text %))) 120) chunks))
    (is (every? string? (map :content-sha chunks)))
    (is (empty? (:diagnostics result)))))

(deftest extracts-mdx-and-storybook-facts
  (let [mdx-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/docs/components.mdx"))
        storybook-config-result (extract/extract-file
                                 "run/test"
                                 (fs/file-record
                                  "test/fixtures/extractor-repo"
                                  "test/fixtures/extractor-repo/.storybook/main.ts"))
        story-result (extract/extract-file
                      "run/test"
                      (fs/file-record
                       "test/fixtures/extractor-repo"
                       "test/fixtures/extractor-repo/src/ui/Button.stories.tsx"))
        docusaurus-result (extract/extract-file
                           "run/test"
                           (fs/file-record
                            "test/fixtures/extractor-repo"
                            "test/fixtures/extractor-repo/docs/docusaurus.config.ts"))
        sidebar-result (extract/extract-file
                        "run/test"
                        (fs/file-record
                         "test/fixtures/extractor-repo"
                         "test/fixtures/extractor-repo/docs/sidebars.ts"))
        mkdocs-result (extract/extract-file
                       "run/test"
                       (fs/file-record
                        "test/fixtures/extractor-repo"
                        "test/fixtures/extractor-repo/docs/mkdocs.yml"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :doc (:kind (fs/file-record
                        "test/fixtures/extractor-repo"
                        "test/fixtures/extractor-repo/docs/components.mdx"))))
    (is (= :storybook (:kind (fs/file-record
                              "test/fixtures/extractor-repo"
                              "test/fixtures/extractor-repo/.storybook/main.ts"))))
    (is (= :storybook (:kind (fs/file-record
                              "test/fixtures/extractor-repo"
                              "test/fixtures/extractor-repo/src/ui/Button.stories.tsx"))))
    (is (= :docs-config (:kind (fs/file-record
                                "test/fixtures/extractor-repo"
                                "test/fixtures/extractor-repo/docs/docusaurus.config.ts"))))
    (is (= :docs-config (:kind (fs/file-record
                                "test/fixtures/extractor-repo"
                                "test/fixtures/extractor-repo/docs/sidebars.ts"))))
    (is (= :docs-config (:kind (fs/file-record
                                "test/fixtures/extractor-repo"
                                "test/fixtures/extractor-repo/docs/mkdocs.yml"))))
    (is (contains? (labels mdx-result) "Components"))
    (is (contains? (labels mdx-result) "./panels.md"))
    (is (contains? (labels mdx-result) "src.ui.Button"))
    (is (= [:mdx :mdx] (mapv :kind (:chunks mdx-result))))
    (is (pos? (get (relations mdx-result) :references 0)))
    (is (contains? (labels storybook-config-result)
                   "../src/**/*.stories.tsx"))
    (is (contains? (labels storybook-config-result)
                   "@storybook/addon-essentials"))
    (is (contains? (labels storybook-config-result)
                   "@storybook/react-vite"))
    (is (contains? (labels story-result) "@storybook/react"))
    (is (contains? (labels story-result) "src.ui.Button"))
    (is (contains? (labels story-result) "Components/Button"))
    (is (contains? (labels story-result) "Button"))
    (is (contains? (labels story-result) "autodocs"))
    (is (contains? (labels story-result) "Primary"))
    (is (pos? (get (relations story-result) :references 0)))
    (is (= [:storybook-file] (mapv :kind (:chunks storybook-config-result))))
    (is (= [:storybook-file] (mapv :kind (:chunks story-result))))
    (is (contains? (labels docusaurus-result) "Panels Docs"))
    (is (contains? (labels docusaurus-result) "/panels/"))
    (is (contains? (labels docusaurus-result) "/docs/intro"))
    (is (contains? (labels docusaurus-result) "https://example.com/support"))
    (is (contains? (labels docusaurus-result) "./sidebars.ts"))
    (is (= 1 (get (kinds docusaurus-result) :docs-title 0)))
    (is (pos? (get (relations docusaurus-result) :references 0)))
    (is (contains? (labels sidebar-result) "Guides"))
    (is (contains? (labels sidebar-result) "intro"))
    (is (contains? (labels sidebar-result) "guides/install"))
    (is (= 1 (get (kinds sidebar-result) :docs-sidebar-entry 0)))
    (is (contains? (labels mkdocs-result) "Panels Docs"))
    (is (contains? (labels mkdocs-result) "Home"))
    (is (contains? (labels mkdocs-result) "index.md"))
    (is (contains? (labels mkdocs-result) "Install"))
    (is (contains? (labels mkdocs-result) "guides/install.md"))
    (is (contains? (labels mkdocs-result) "search"))
    (is (contains? (labels mkdocs-result) "material"))
    (is (= 1 (get (kinds mkdocs-result) :docs-title 0)))
    (is (= 3 (get (kinds mkdocs-result) :docs-nav-entry 0)))
    (is (= [:docs-config-file] (mapv :kind (:chunks docusaurus-result))))
    (is (= [:docs-config-file] (mapv :kind (:chunks sidebar-result))))
    (is (= [:docs-config-file] (mapv :kind (:chunks mkdocs-result))))))

(deftest extracts-astro-content-collection-config
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/docs/src/content.config.ts")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :docs-config (:kind file)))
    (is (contains? labels "astro:content"))
    (is (contains? labels "astro/loaders"))
    (is (contains? labels "astro/zod"))
    (is (contains? labels "blog"))
    (is (contains? labels "authors"))
    (is (contains? labels "blog:glob"))
    (is (contains? labels "authors:file"))
    (is (contains? labels "./src/content/blog"))
    (is (contains? labels "**/*.{md,mdx}"))
    (is (contains? labels "./src/content/authors.json"))
    (is (contains? labels "blog.title"))
    (is (contains? labels "blog.description"))
    (is (contains? labels "blog.pubDate"))
    (is (contains? labels "authors.name"))
    (is (contains? labels "authors.website"))
    (is (= 2 (get kinds :content-collection 0)))
    (is (= 2 (get kinds :content-loader 0)))
    (is (= 5 (get kinds :content-schema-field 0)))
    (is (= 3 (get relations :imports 0)))
    (is (= 7 (get relations :defines 0)))
    (is (= 3 (get relations :references 0)))
    (is (= 2 (get relations :uses 0)))
    (is (= [:docs-config-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-vitepress-docs-config
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/docs/.vitepress/config.ts")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :docs-config (:kind file)))
    (is (= :docs-config (fs/file-kind "docs/.vitepress/config/index.mts")))
    (is (contains? labels "vitepress"))
    (is (contains? labels "Panels Handbook"))
    (is (contains? labels "Guide"))
    (is (contains? labels "API"))
    (is (contains? labels "Introduction"))
    (is (contains? labels "Configuration"))
    (is (contains? labels "/handbook/"))
    (is (contains? labels "/guide/"))
    (is (contains? labels "/api/"))
    (is (contains? labels "/guide/introduction"))
    (is (contains? labels "/reference/configuration"))
    (is (contains? labels "local"))
    (is (= 1 (get kinds :docs-title 0)))
    (is (= 4 (get kinds :docs-nav-entry 0)))
    (is (= 5 (get kinds :docs-route 0)))
    (is (= 1 (get kinds :docs-search-provider 0)))
    (is (= 1 (get relations :imports 0)))
    (is (= 5 (get relations :defines 0)))
    (is (= 5 (get relations :references 0)))
    (is (= 1 (get relations :uses 0)))
    (is (= [:docs-config-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-sphinx-docs-config-and-toctree
  (let [config-file (fs/file-record
                     "test/fixtures/extractor-repo"
                     "test/fixtures/extractor-repo/docs/sphinx/conf.py")
        config-result (extract/extract-file "run/test" config-file)
        rst-file (fs/file-record
                  "test/fixtures/extractor-repo"
                  "test/fixtures/extractor-repo/docs/sphinx/index.rst")
        rst-result (extract/extract-file "run/test" rst-file)
        config-labels (set (map :label (:nodes config-result)))
        rst-labels (set (map :label (:nodes rst-result)))
        config-kinds (frequencies (map :kind (:nodes config-result)))
        rst-kinds (frequencies (map :kind (:nodes rst-result)))
        config-relations (frequencies (map :relation (:edges config-result)))
        rst-relations (frequencies (map :relation (:edges rst-result)))]
    (is (= :docs-config (:kind config-file)))
    (is (= :doc (:kind rst-file)))
    (is (contains? config-labels "Panels Manual"))
    (is (contains? config-labels "sphinx.ext.autodoc"))
    (is (contains? config-labels "myst_parser"))
    (is (contains? config-labels "furo"))
    (is (contains? config-labels "index"))
    (is (contains? config-labels "_templates"))
    (is (contains? config-labels "_static"))
    (is (= 1 (get config-kinds :docs-title 0)))
    (is (= 2 (get config-kinds :docs-extension 0)))
    (is (= 1 (get config-kinds :docs-theme 0)))
    (is (= 1 (get config-kinds :docs-route 0)))
    (is (= 2 (get config-kinds :docs-config-reference 0)))
    (is (= 1 (get config-relations :defines 0)))
    (is (= 3 (get config-relations :uses 0)))
    (is (= 3 (get config-relations :references 0)))
    (is (= [:docs-config-file] (mapv :kind (:chunks config-result))))
    (is (contains? rst-labels "toctree@4"))
    (is (contains? rst-labels ":maxdepth: 2"))
    (is (contains? rst-labels ":caption: Contents"))
    (is (contains? rst-labels "guide/intro"))
    (is (contains? rst-labels "reference/api"))
    (is (= 1 (get rst-kinds :docs-toctree 0)))
    (is (= 2 (get rst-kinds :docs-toctree-option 0)))
    (is (= 2 (get rst-kinds :docs-route 0)))
    (is (= 1 (get rst-relations :defines 0)))
    (is (= 2 (get rst-relations :uses 0)))
    (is (= 2 (get rst-relations :references 0)))
    (is (= [:markdown] (mapv :kind (:chunks rst-result))))
    (is (empty? (:diagnostics config-result)))
    (is (empty? (:diagnostics rst-result)))))

(deftest extracts-nextra-docs-config
  (let [next-file (fs/file-record
                   "test/fixtures/extractor-repo"
                   "test/fixtures/extractor-repo/docs/nextra/next.config.mjs")
        next-result (extract/extract-file "run/test" next-file)
        meta-file (fs/file-record
                   "test/fixtures/extractor-repo"
                   "test/fixtures/extractor-repo/docs/nextra/content/_meta.ts")
        meta-result (extract/extract-file "run/test" meta-file)
        next-labels (set (map :label (:nodes next-result)))
        meta-labels (set (map :label (:nodes meta-result)))
        next-kinds (frequencies (map :kind (:nodes next-result)))
        meta-kinds (frequencies (map :kind (:nodes meta-result)))
        next-relations (frequencies (map :relation (:edges next-result)))
        meta-relations (frequencies (map :relation (:edges meta-result)))]
    (is (= :docs-config (:kind next-file)))
    (is (= :docs-config (:kind meta-file)))
    (is (contains? next-labels "nextra"))
    (is (contains? next-labels "/handbook"))
    (is (contains? next-labels "en"))
    (is (contains? next-labels "de"))
    (is (= 1 (get next-kinds :docs-plugin 0)))
    (is (= 1 (get next-kinds :docs-route 0)))
    (is (= 2 (get next-kinds :docs-locale 0)))
    (is (= 1 (get next-kinds :docs-locale-default 0)))
    (is (= 1 (get next-relations :imports 0)))
    (is (= 1 (get next-relations :uses 0)))
    (is (= 3 (get next-relations :defines 0)))
    (is (= 1 (get next-relations :references 0)))
    (is (contains? meta-labels "index"))
    (is (contains? meta-labels "guide"))
    (is (contains? meta-labels "api"))
    (is (contains? meta-labels "github_link"))
    (is (contains? meta-labels "Overview"))
    (is (contains? meta-labels "Guide"))
    (is (contains? meta-labels "API Reference"))
    (is (contains? meta-labels "GitHub"))
    (is (contains? meta-labels "https://github.com/example/panels"))
    (is (contains? meta-labels "guide:page"))
    (is (contains? meta-labels "api:children"))
    (is (= 4 (get meta-kinds :docs-meta-entry 0)))
    (is (= 4 (get meta-kinds :docs-sidebar-entry 0)))
    (is (= 1 (get meta-kinds :docs-route 0)))
    (is (= 1 (get meta-kinds :docs-meta-type 0)))
    (is (= 1 (get meta-kinds :docs-meta-display 0)))
    (is (= 8 (get meta-relations :defines 0)))
    (is (= 2 (get meta-relations :uses 0)))
    (is (= 1 (get meta-relations :references 0)))
    (is (= [:docs-config-file] (mapv :kind (:chunks next-result))))
    (is (= [:docs-config-file] (mapv :kind (:chunks meta-result))))
    (is (empty? (:diagnostics next-result)))
    (is (empty? (:diagnostics meta-result)))))

(deftest extracts-typescript-module-docs-configs
  (let [root (doto (java.io.File/createTempFile "ygg-module-docs-configs" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        write! (fn [path content]
                 (let [file (io/file root path)]
                   (io/make-parents file)
                   (spit file content)
                   file))
        vitepress-source (write! "docs/.vitepress/config.cts"
                                 (slurp "test/fixtures/extractor-repo/docs/.vitepress/config.ts"))
        vitepress-index-source (write! "docs/.vitepress/config/index.cts"
                                       (slurp "test/fixtures/extractor-repo/docs/.vitepress/config.ts"))
        astro-source (write! "docs/src/content/config.cts"
                             (slurp "test/fixtures/extractor-repo/docs/src/content.config.ts"))
        astro-root-source (write! "docs/content.config.mts"
                                  (slurp "test/fixtures/extractor-repo/docs/src/content.config.ts"))
        nextra-source (write! "docs/nextra/next.config.cts"
                              "import nextra from 'nextra';\nexport default nextra({ contentDirBasePath: '/handbook', locales: ['en'], defaultLocale: 'en' });\n")
        meta-source (write! "docs/nextra/content/_meta.cts"
                            (slurp "test/fixtures/extractor-repo/docs/nextra/content/_meta.ts"))
        docusaurus-source (write! "docs/docusaurus.config.mts"
                                  "export default { title: 'Panels Docs', baseUrl: '/docs/', presets: [{ name: 'classic' }], plugins: [{ name: 'plugin-panels' }] };\n")
        sidebar-source (write! "docs/sidebars.cts"
                               "export default { docs: [{ type: 'doc', id: 'intro', label: 'Intro' }] };\n")
        result-for (fn [source]
                     (extract/extract-file "run/test"
                                           (fs/file-record (.getPath root)
                                                           (.getPath source))))
        kind-for (fn [source]
                   (:kind (fs/file-record (.getPath root)
                                          (.getPath source))))
        labels (fn [result] (set (map :label (:nodes result))))
        vitepress-labels (labels (result-for vitepress-source))
        vitepress-index-labels (labels (result-for vitepress-index-source))
        astro-labels (labels (result-for astro-source))
        astro-root-labels (labels (result-for astro-root-source))
        nextra-labels (labels (result-for nextra-source))
        meta-labels (labels (result-for meta-source))
        docusaurus-labels (labels (result-for docusaurus-source))
        sidebar-labels (labels (result-for sidebar-source))]
    (is (= :docs-config (kind-for vitepress-source)))
    (is (= :docs-config (kind-for vitepress-index-source)))
    (is (= :docs-config (kind-for astro-source)))
    (is (= :docs-config (kind-for astro-root-source)))
    (is (= :docs-config (kind-for nextra-source)))
    (is (= :docs-config (kind-for meta-source)))
    (is (= :docs-config (kind-for docusaurus-source)))
    (is (= :docs-config (kind-for sidebar-source)))
    (is (contains? vitepress-labels "Panels Handbook"))
    (is (contains? vitepress-labels "local"))
    (is (contains? vitepress-index-labels "/guide/"))
    (is (contains? astro-labels "astro:content"))
    (is (contains? astro-labels "blog"))
    (is (contains? astro-root-labels "authors"))
    (is (contains? nextra-labels "nextra"))
    (is (contains? nextra-labels "/handbook"))
    (is (contains? meta-labels "API Reference"))
    (is (contains? docusaurus-labels "Panels Docs"))
    (is (contains? docusaurus-labels "/docs/"))
    (is (contains? docusaurus-labels "classic"))
    (is (contains? sidebar-labels "Intro"))
    (is (contains? sidebar-labels "intro"))))

(deftest extracts-frontend-single-file-components
  (let [vue-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/frontend/components/PanelList.vue"))
        svelte-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/frontend/components/PanelDetails.svelte"))
        vue-labels (set (map :label (:nodes vue-result)))
        svelte-labels (set (map :label (:nodes svelte-result)))
        vue-imports (set (map :target-id
                              (filter #(= :imports (:relation %))
                                      (:edges vue-result))))
        svelte-imports (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges svelte-result))))
        vue-relations (frequencies (map :relation (:edges vue-result)))
        svelte-relations (frequencies (map :relation (:edges svelte-result)))
        vue-chunk-kinds (set (map :kind (:chunks vue-result)))
        svelte-chunk-kinds (set (map :kind (:chunks svelte-result)))]
    (is (= :vue (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/frontend/components/PanelList.vue"))))
    (is (= :svelte (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/frontend/components/PanelDetails.svelte"))))
    (is (contains? vue-labels "frontend.components.PanelList"))
    (is (contains? vue-labels "frontend.components.PanelList/selected"))
    (is (contains? vue-labels "frontend.components.PanelList/refresh"))
    (is (contains? vue-imports
                   (extract/node-id :namespace "frontend.components.PanelCard")))
    (is (contains? vue-imports
                   (extract/node-id :namespace "frontend.lib.panels")))
    (is (= 3 (get vue-relations :defines 0)))
    (is (contains? vue-chunk-kinds :vue-file))
    (is (contains? vue-chunk-kinds :vue-script))
    (is (contains? vue-chunk-kinds :vue-template))
    (is (contains? svelte-labels "frontend.components.PanelDetails"))
    (is (contains? svelte-labels "frontend.components.PanelDetails/panelId"))
    (is (contains? svelte-labels "frontend.components.PanelDetails/selected"))
    (is (contains? svelte-labels "frontend.components.PanelDetails/refresh"))
    (is (contains? svelte-imports
                   (extract/node-id :namespace "frontend.components.PanelCard")))
    (is (contains? svelte-imports
                   (extract/node-id :namespace "frontend.lib.panels")))
    (is (= 4 (get svelte-relations :defines 0)))
    (is (contains? svelte-chunk-kinds :svelte-file))
    (is (contains? svelte-chunk-kinds :svelte-script))
    (is (contains? svelte-chunk-kinds :svelte-template))))

(deftest extracts-python-modules-definitions-imports-and-diagnostics
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/python/app.py")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :python (:kind file)))
    (is (contains? labels "python.app"))
    (is (contains? labels "python.app/Service"))
    (is (contains? labels "python.app/Service.fetch"))
    (is (contains? labels "python.app/build"))
    (is (contains? labels "python.app/main"))
    (is (= 4 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "python.local")))
    (is (<= 5 (get relations :defines 0)))
    (is (= [:python-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result))))
  (let [root (doto (java.io.File/createTempFile "ygg-python-extract" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        py (doto (io/file root "broken.py")
             (.deleteOnExit))]
    (spit py "def broken(:\n    pass\n")
    (let [result (extract/extract-file "run/test"
                                       (fs/file-record (.getPath root)
                                                       (.getPath py)))
          diagnostic (first (:diagnostics result))]
      (is (= :parse (:stage diagnostic)))
      (is (= [:python-file] (mapv :kind (:chunks result)))))))

(deftest extract-python-uses-parser-worker-facts-when-present
  (let [file (assoc (fs/file-record "test/fixtures/sample-repo"
                                    "test/fixtures/sample-repo/src/python/app.py")
                    :content "def broken(:\n    pass\n"
                    :parser-worker-facts
                    {:definitions [{:kind "function"
                                    :name "from_worker"
                                    :line 1}]
                     :imports [{:target "worker.dep"
                                :line 1}]
                     :references []
                     :diagnostics []})
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (contains? labels "python.app/from_worker"))
    (is (contains? import-targets
                   (extract/node-id :namespace "worker.dep")))
    (is (empty? (:diagnostics result)))))

(deftest extracts-continuation-chunks-for-long-clojure-definitions
  (let [root (doto (java.io.File/createTempFile "ygg-long-cljs" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        source (io/file root "src/demo/large.cljs")
        filler (str/join "\n" (repeat 130 "  (identity :filler)"))
        content (str "(ns demo.large)\n\n"
                     "(defn create-context []\n"
                     filler "\n"
                     "  (identity :openPage)\n"
                     "  (identity :createBoard))\n")]
    (.mkdirs (.getParentFile source))
    (spit source content)
    (let [result (extract/extract-file "run/test"
                                       (fs/file-record (.getPath root)
                                                       (.getPath source)))
          fragment (some #(when (= :code-definition-fragment (:kind %)) %)
                         (:chunks result))]
      (is fragment)
      (is (str/includes? (:text fragment) ":openPage"))
      (is (str/includes? (:text fragment) ":createBoard")))))

(deftest extracts-typescript-modules-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/web/app.ts")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-edges (filter #(= :imports (:relation %)) (:edges result))
        import-targets (set (map :target-id
                                 import-edges))
        types-import-edge (some #(when (= (extract/node-id :namespace "src.web.types")
                                          (:target-id %))
                                   %)
                                import-edges)]
    (is (= :typescript (:kind file)))
    (is (contains? labels "src.web.app"))
    (is (contains? labels "src.web.app/helper"))
    (is (contains? labels "src.web.app/Panel"))
    (is (contains? labels "src.web.app/loadPanel"))
    (is (contains? labels "src.web.app/route"))
    (is (= 5 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.types")))
    (is (contains? import-targets
                   (extract/node-id :namespace "react")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.theme")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.loader")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.data")))
    (is (= :type (:import-kind types-import-edge)))
    (is (= 4 (get relations :defines 0)))
    (is (= [:typescript-file
            :code-definition
            :code-definition
            :code-definition
            :code-definition]
           (mapv :kind (:chunks result))))
    (is (some #(and (= "src.web.app/helper" (:label %))
                    (str/includes? (:text %) "const helper"))
              (:chunks result)))
    (is (empty? (:diagnostics result)))))

(deftest extracts-static-js-imports-with-resource-query
  (let [file {:file-id "file:src/assets/stackblitz.js"
              :path "src/assets/stackblitz.js"
              :kind :javascript
              :content (str "import snippetsContent from './partials/snippets.js?raw'\n"
                            "const code = \"require('${runnerPath}')\"\n")}
        result (extract/extract-file "run/test" file)
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= #{(extract/node-id :namespace "src.assets.partials.snippets")}
           import-targets))))

(deftest extracts-javascript-parser-worker-facts-when-present
  (let [file {:file-id "file:src/adapters/http.js"
              :path "src/adapters/http.js"
              :kind :javascript
              :content (str "import base from './base.js';\n"
                            "import type Agent from 'https-proxy-agent';\n"
                            "class Adapter {\n"
                            "  request(config) {\n"
                            "    return base(config);\n"
                            "  }\n"
                            "}\n"
                            "export function createAdapter() {\n"
                            "  return new Adapter();\n"
                            "}\n")
              :parser-worker-facts
              {:definitions [{:kind "class"
                              :name "Adapter"
                              :line 3
                              :endLine 7}
                             {:kind "method"
                              :name "Adapter.request"
                              :line 4
                              :endLine 6}
                             {:kind "function"
                              :name "createAdapter"
                              :line 8
                              :endLine 10}]
               :imports [{:target "./base.js" :line 1}
                         {:target "https-proxy-agent"
                          :line 2
                          :importKind "type"}]
               :references [{:source "createAdapter"
                             :target "Adapter"
                             :kind "symbol"
                             :line 9}]
               :diagnostics []}}
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        type-import-edge (some #(when (= (extract/node-id
                                          :namespace
                                          "https-proxy-agent")
                                         (:target-id %))
                                  %)
                               (:edges result))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (contains? labels "src.adapters.http/Adapter"))
    (is (contains? labels "src.adapters.http/Adapter.request"))
    (is (contains? labels "src.adapters.http/createAdapter"))
    (is (= 3 (get relations :defines 0)))
    (is (= 2 (get relations :imports 0)))
    (is (= 1 (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :symbol "src.adapters.http/Adapter")))
    (is (= :type (:import-kind type-import-edge)))
    (is (str/includes?
         (get-in chunks-by-label ["src.adapters.http/Adapter.request" :text])
         "request(config)"))
    (is (empty? (:diagnostics result)))))

(deftest javascript-parser-worker-failure-preserves-fallback-extraction
  (let [file {:file-id "file:src/routes.js"
              :path "src/routes.js"
              :kind :javascript
              :content "export const route = '/panels';\n"
              :parser-worker-facts
              {:definitions []
               :imports []
               :references []
               :diagnostics [{:stage "parser-worker"
                              :line nil
                              :message "javascript parser unavailable"}]}}
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        diagnostic (first (:diagnostics result))]
    (is (contains? labels "src.routes/route"))
    (is (= :parser-worker (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "javascript parser unavailable"))))

(deftest extracts-typescript-commonjs-module-files
  (let [file {:file-id "file:scripts/panel.cts"
              :path "scripts/panel.cts"
              :kind (fs/file-kind "scripts/panel.cts")
              :content (str "import { readFileSync } from \"fs\";\n"
                            "export function loadPanel(id: string) {\n"
                            "  return readFileSync(id, \"utf8\");\n"
                            "}\n")}
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :typescript (:kind file)))
    (is (contains? labels "scripts.panel"))
    (is (contains? labels "scripts.panel/loadPanel"))
    (is (not (contains? labels "scripts.panel.cts")))
    (is (= [:typescript-file
            :code-definition]
           (mapv :kind (:chunks result))))
    (is (= 1 (get relations :imports 0)))
    (is (= 1 (get relations :defines 0)))
    (is (empty? (:diagnostics result)))))

(deftest extracts-typescript-esm-module-files-with-clean-labels
  (let [file {:file-id "file:scripts/panel.mts"
              :path "scripts/panel.mts"
              :kind (fs/file-kind "scripts/panel.mts")
              :content "export const route = '/panels';\n"}
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))]
    (is (= :typescript (:kind file)))
    (is (contains? labels "scripts.panel"))
    (is (contains? labels "scripts.panel/route"))
    (is (not (contains? labels "scripts.panel.mts")))
    (is (= [:typescript-file
            :code-definition]
           (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-typescript-declaration-member-chunks
  (let [root (doto (java.io.File/createTempFile "ygg-types" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        source (io/file root "src/plugin-types/index.d.ts")
        cjs-source (io/file root "src/plugin-types/node.d.cts")
        esm-source (io/file root "src/plugin-types/browser.d.mts")
        content "export interface Context {\n  /**\n   * Opens a page in the current file.\n   * @example\n   * ```js\n   * penpot.openPage(page);\n   * ```\n   */\n  openPage(page: Page | string): void;\n  /**\n   * Creates a board and then appends a child.\n   * @example\n   * ```js\n   * const board = penpot.createBoard();\n   * board.appendChild(shape);\n   * ```\n   */\n  createBoard(): Board;\n}\nexport interface Board {\n  appendChild(child: Shape): void;\n}\nexport interface Page {\n  readonly root: Shape;\n}\n"
        _ (.mkdirs (.getParentFile source))
        _ (spit source content)
        _ (spit cjs-source "export interface NodeContext {\n  resolve(id: string): string;\n}\n")
        _ (spit esm-source "export interface BrowserContext {\n  readonly origin: string;\n}\n")
        result (extract/extract-file "run/test"
                                     (fs/file-record (.getPath root)
                                                     (.getPath source)))
        cjs-result (extract/extract-file "run/test"
                                         (fs/file-record (.getPath root)
                                                         (.getPath cjs-source)))
        esm-result (extract/extract-file "run/test"
                                         (fs/file-record (.getPath root)
                                                         (.getPath esm-source)))
        chunks-by-label (group-by :label (:chunks result))]
    (is (some? (get chunks-by-label "src.plugin_types.index/openPage")))
    (is (some? (get chunks-by-label "src.plugin_types.index/createBoard")))
    (is (some? (get chunks-by-label "src.plugin_types.index/appendChild")))
    (is (some? (get chunks-by-label "src.plugin_types.index/root")))
    (is (some #(and (= :method (:definition-kind %))
                    (str/includes? (:text %) "penpot.openPage(page)"))
              (get chunks-by-label "src.plugin_types.index/openPage")))
    (is (some #(and (= :property (:definition-kind %))
                    (str/includes? (:text %) "readonly root"))
              (get chunks-by-label "src.plugin_types.index/root")))
    (is (some #(= "src.plugin_types.node/resolve" (:label %))
              (:chunks cjs-result)))
    (is (some #(= "src.plugin_types.browser/origin" (:label %))
              (:chunks esm-result)))))

(deftest extracts-style-as-searchable-source-chunk
  (let [style-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/sample-repo"
                                      "test/fixtures/sample-repo/src/web/theme.scss"))
        chunks-by-label (group-by :label (:chunks style-result))
        labels (set (map :label (:nodes style-result)))
        relations (frequencies (map :relation (:edges style-result)))]
    (is (= [:style-file
            :style-section
            :style-rule
            :style-variable
            :style-variable
            :style-variable]
           (mapv :kind (:chunks style-result))))
    (is (contains? labels "src/web/theme.scss"))
    (is (contains? labels "$panel-padding"))
    (is (contains? labels "$panel-radius"))
    (is (contains? labels "panel-variables"))
    (is (contains? labels ".panel"))
    (is (= 5 (get relations :defines 0)))
    (is (str/includes? (:text (first (get chunks-by-label "panel-variables")))
                       "$panel-radius-sm"))
    (is (str/includes? (:text (first (get chunks-by-label ".panel")))
                       "border-radius: $panel-radius"))
    (is (str/includes? (:text (first (get chunks-by-label "$panel-radius")))
                       "$panel-radius: .25rem"))))

(deftest caps-style-rule-fanout-with-diagnostic
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-style-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        source (doto (io/file root "theme.scss")
                 (spit (str "$theme-color: red;\n"
                            "// scss-docs-start theme-docs\n"
                            ".theme-docs { color: $theme-color; }\n"
                            "// scss-docs-end theme-docs\n"
                            (str/join
                             "\n"
                             (map (fn [idx]
                                    (str ".rule-" idx " { color: red; }"))
                                  (range 300)))
                            "\n")))
        result (extract/extract-file "run/test"
                                     (fs/file-record (.getPath root)
                                                     (.getPath source)))
        chunk-kinds (frequencies (map :kind (:chunks result)))
        labels (set (map :label (:nodes result)))
        diagnostic (first (:diagnostics result))]
    (is (= 1 (:style-file chunk-kinds)))
    (is (= 1 (:style-section chunk-kinds)))
    (is (= 1 (:style-variable chunk-kinds)))
    (is (= 128 (:style-rule chunk-kinds)))
    (is (contains? labels "$theme-color"))
    (is (contains? labels "theme-docs"))
    (is (= :style-rule-limit (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "retained 128"))
    (is (str/includes? (:message diagnostic) "omitted"))))

(deftest bounds-large-style-file-chunk-text
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-style-file-chunk"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        content (str ".head { color: red; }\n"
                     (apply str (repeat 60000 "x"))
                     "\n.tail { color: blue; }\n")
        source (doto (io/file root "large.css")
                 (spit content))
        result (extract/extract-file "run/test"
                                     (fs/file-record (.getPath root)
                                                     (.getPath source)))
        style-file-chunk (first (filter #(= :style-file (:kind %))
                                        (:chunks result)))]
    (is (< (count (:text style-file-chunk)) (count content)))
    (is (str/includes? (:text style-file-chunk) ".head"))
    (is (str/includes? (:text style-file-chunk) ".tail"))))

(deftest extracts-source-map-as-bounded-source-references
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-source-map-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        sources (mapv #(str "../src/module-" % ".js") (range 260))
        source-json (fn [value] (str "\"" value "\""))
        source-map-content (str "{\"version\":3,"
                                "\"sources\":["
                                (str/join "," (map source-json sources))
                                "],"
                                "\"sourcesContent\":[\"large generated body\"],"
                                "\"sourceRoot\":\"webpack://app\","
                                "\"file\":\"bundle\\\"prod.js\"}")
        source (doto (io/file root "bundle.js.map")
                 (spit source-map-content))
        file (fs/file-record (.getPath root) (.getPath source))
        result (extract/extract-file "run/test" file)
        node-kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        chunk (first (:chunks result))
        diagnostic (first (:diagnostics result))]
    (is (= :source-map (:kind file)))
    (is (= :source-map (fs/file-kind "bundle.js.map")))
    (is (= 1 (:source-map-file node-kinds)))
    (is (= 256 (:source-map-source node-kinds)))
    (is (= 256 (:references relations)))
    (is (= [:source-map-file] (mapv :kind (:chunks result))))
    (is (str/includes? (:text chunk) "bundle\"prod.js"))
    (is (str/includes? (:text chunk) "webpack://app"))
    (is (str/includes? (:text chunk) "../src/module-0.js"))
    (is (not (str/includes? (:text chunk) "large generated body")))
    (is (= :source-map-source-limit (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "retained 256"))))

(deftest extracts-shell-as-searchable-source-chunk
  (let [shell-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/scripts/setup.sh"))]
    (is (= [:shell-file] (mapv :kind (:chunks shell-result))))
    (is (= [:shell-file] (mapv :kind (:nodes shell-result))))))

(deftest extracts-shell-functions-as-source-definitions
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-shell-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (doto (io/file root "tool.sh")
               (spit (str "#!/usr/bin/env bash\n"
                          "nvm_remote_version() {\n"
                          "  echo \"$1\"\n"
                          "}\n\n"
                          "function install_lts {\n"
                          "  nvm_remote_version \"$1\"\n"
                          "}\n")))
        record (fs/file-record (.getPath root) (.getPath file))
        result (extract/extract-file "run/test" record)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        call-targets (set (map :target-id
                               (filter #(= :calls (:relation %))
                                       (:edges result))))
        chunks-by-label (group-by :label (:chunks result))]
    (is (= :shell (:kind record)))
    (is (= #{:shell-file :code-definition}
           (set (map :kind (:chunks result)))))
    (is (contains? labels "tool.sh"))
    (is (contains? labels "tool.sh/nvm_remote_version"))
    (is (contains? labels "tool.sh/install_lts"))
    (is (= 2 (get relations :defines 0)))
    (is (= 1 (get relations :calls 0)))
    (is (contains? call-targets
                   (extract/node-id :shell-function "nvm_remote_version")))
    (is (= :function
           (get-in chunks-by-label ["tool.sh/nvm_remote_version" 0 :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["tool.sh/nvm_remote_version" 0 :text])
         "echo \"$1\""))))

(deftest extracts-env-files-with-sanitized-variable-chunks
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-env-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (doto (io/file root ".env")
               (spit (str "OPENAI_API_KEY=sk-live-secret\n"
                          "API_URL=https://api.example.test\n"
                          "export CLIENT_SECRET=client-secret-value\n")))
        record (fs/file-record (.getPath root) (.getPath file))
        result (extract/extract-file "run/test" record)
        labels (set (map :label (:nodes result)))
        chunk (first (:chunks result))]
    (is (= :env (:kind record)))
    (is (contains? labels ".env"))
    (is (contains? labels "OPENAI_API_KEY"))
    (is (contains? labels "API_URL"))
    (is (contains? labels "CLIENT_SECRET"))
    (is (= [:env-file] (mapv :kind (:chunks result))))
    (is (str/includes? (:text chunk) "OPENAI_API_KEY"))
    (is (not (re-find #"sk-live-secret|client-secret-value|https://api.example.test"
                      (:text chunk))))))

(deftest extracts-unknown-text-as-searchable-source-chunk
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-unknown-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (doto (io/file root "facts.ini")
               (spit "owner=postgres\n"))
        record (fs/file-record (.getPath root) (.getPath file))
        result (extract/extract-file "run/test" record)]
    (is (= :unknown (:kind record)))
    (is (= [:unknown-file] (mapv :kind (:chunks result))))
    (is (empty? (:nodes result)))
    (is (empty? (:edges result)))))
