(ns agraph.extract-test
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
    (doseq [result (map #(extract/extract-file "run/test" %) files)]
      (is (= canonical-buckets (set (keys result))))
      (is (every? vector? (vals result))))))

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
  (let [root (doto (java.io.File/createTempFile "agraph-module-docs-configs" "")
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
    (is (contains? liquibase-labels "agraph"))
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
  (let [root (doto (java.io.File/createTempFile "agraph-module-configs" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        jest-source (io/file root "jest.config.mts")
        playwright-source (io/file root "playwright.config.cts")
        eslint-source (io/file root "eslint.config.mts")
        tailwind-source (io/file root "tailwind.config.cts")
        _ (spit jest-source "import base from 'jest-config';\nexport default {\n  testEnvironment: 'node',\n  reporters: ['default'],\n};\n")
        _ (spit playwright-source "import { defineConfig } from '@playwright/test';\nexport default defineConfig({\n  testDir: './e2e',\n});\n")
        _ (spit eslint-source "import js from '@eslint/js';\nexport default [\n  { rules: { semi: 'error' } },\n];\n")
        _ (spit tailwind-source "export default {\n  content: ['./src/**/*.tsx'],\n  theme: {},\n};\n")
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
        tailwind-labels (labels (result-for tailwind-source))]
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
    (is (contains? tailwind-labels "theme"))))

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
  (let [root (doto (java.io.File/createTempFile "agraph-route" "")
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
  (let [root (doto (java.io.File/createTempFile "agraph-web-config" "")
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


(deftest extracts-graphql-schema-operations-and-references
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/contracts/panels.graphql")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :graphql (:kind file)))
    (is (contains? labels "contracts/panels.graphql"))
    (is (contains? labels "schema"))
    (is (contains? labels "Query"))
    (is (contains? labels "Mutation"))
    (is (contains? labels "Panel"))
    (is (contains? labels "Node"))
    (is (contains? labels "PanelInput"))
    (is (contains? labels "PanelFilter"))
    (is (contains? labels "PanelStatus"))
    (is (contains? labels "Subscription.panelChanged"))
    (is (contains? labels "Panel.status"))
    (is (contains? labels "PanelStatus.DRAFT"))
    (is (contains? labels "PanelSummary"))
    (is (= 1 (:graphql-file kinds)))
    (is (= 4 (:graphql-type kinds)))
    (is (= 1 (:graphql-interface kinds)))
    (is (= 2 (:graphql-input kinds)))
    (is (= 1 (:graphql-enum kinds)))
    (is (= 12 (:graphql-field kinds)))
    (is (= 2 (:graphql-enum-value kinds)))
    (is (= 1 (:graphql-fragment kinds)))
    (is (pos? (get relations :defines 0)))
    (is (pos? (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Node")))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "PanelStatus")))
    (is (= :code-definition (get-in chunks-by-label ["Panel" :kind])))
    (is (= :graphql-type (get-in chunks-by-label ["Panel" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["Panel" :text]) "owner: User"))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.graphql"
                                      :path "broken.graphql"
                                      :kind :graphql
                                      :content "type Broken {\n  id: ID!\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-protobuf-packages-messages-services-and-references
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/contracts/panels.proto")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :protobuf (:kind file)))
    (is (contains? labels "acme.panels.v1"))
    (is (contains? labels "acme.panels.v1/Panel"))
    (is (contains? labels "acme.panels.v1/User"))
    (is (contains? labels "acme.panels.v1/GetPanelRequest"))
    (is (contains? labels "acme.panels.v1/PanelStatus"))
    (is (contains? labels "acme.panels.v1/PanelService"))
    (is (contains? labels "acme.panels.v1/PanelService.GetPanel"))
    (is (contains? labels "acme.panels.v1/Panel.owner"))
    (is (contains? labels "acme.panels.v1/Panel.status"))
    (is (contains? labels "acme.panels.v1/Panel.reviewers"))
    (is (contains? labels "acme.panels.v1/PanelStatus.PANEL_STATUS_DRAFT"))
    (is (= 1 (:namespace kinds)))
    (is (= 3 (:protobuf-message kinds)))
    (is (= 1 (:protobuf-enum kinds)))
    (is (= 1 (:protobuf-service kinds)))
    (is (= 1 (:protobuf-rpc kinds)))
    (is (= 7 (:protobuf-field kinds)))
    (is (= 3 (:protobuf-enum-value kinds)))
    (is (= 1 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "google/protobuf/timestamp.proto")))
    (is (pos? (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "User")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "GetPanelRequest")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "PanelStatus")))
    (is (= :code-definition
           (get-in chunks-by-label ["acme.panels.v1/Panel" :kind])))
    (is (= :protobuf-message
           (get-in chunks-by-label ["acme.panels.v1/Panel" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["acme.panels.v1/Panel" :text])
         "google.protobuf.Timestamp"))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.proto"
                                      :path "broken.proto"
                                      :kind :protobuf
                                      :content "syntax = \"proto3\";\nmessage Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-bobr-web-and-wordpress-formats
  (let [astro-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/bobr/pages/index.astro"))
        php-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/plugin/bobr-wordpress-connector.php"))
        gettext-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/bobr/languages/messages.po"))
        svg-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/assets/logo.svg"))
        config-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/bobr/config/wrangler.jsonc"))
        env-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/infra/support.env.example"))
        html-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/bobr/public/index.html"))
        text-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/bobr/plugin/readme.txt"))
        astro-labels (set (map :label (:nodes astro-result)))
        astro-imports (set (map :target-id
                                (filter #(= :imports (:relation %))
                                        (:edges astro-result))))
        php-labels (set (map :label (:nodes php-result)))
        php-relations (frequencies (map :relation (:edges php-result)))
        gettext-labels (set (map :label (:nodes gettext-result)))
        svg-labels (set (map :label (:nodes svg-result)))]
    (is (= ["astro-file"] (mapv (comp name :kind) (:chunks astro-result))))
    (is (contains? astro-labels "bobr.pages.index"))
    (is (contains? astro-imports
                   (extract/node-id :namespace "bobr.components.SiteHeader")))
    (is (contains? astro-imports
                   (extract/node-id :namespace "bobr.lib.offers")))
    (is (contains? php-labels "Bobr.WordPress"))
    (is (contains? php-labels "Bobr.WordPress/Connector"))
    (is (contains? php-labels "Bobr.WordPress/Connector.DEFAULT_HOOK"))
    (is (contains? php-labels "Bobr.WordPress/Connector.register"))
    (is (contains? php-labels "Bobr.WordPress/Connector.boot"))
    (is (contains? php-labels "Bobr.WordPress/bobr_connector"))
    (is (= 1 (get php-relations :imports 0)))
    (is (= 1 (get php-relations :uses 0)))
    (is (contains? gettext-labels "Connect Bobr"))
    (is (contains? gettext-labels "Open settings"))
    (is (= [:gettext-file] (mapv :kind (:chunks gettext-result))))
    (is (contains? svg-labels "bobr/assets/logo.svg"))
    (is (contains? svg-labels "svg#logo-root"))
    (is (contains? svg-labels "symbol#logo-mark"))
    (is (contains? svg-labels "path#logo-path"))
    (is (= [:svg-file] (mapv :kind (:chunks svg-result))))
    (is (= :config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/bobr/config/wrangler.jsonc"))))
    (is (= [:config] (mapv :kind (:chunks config-result))))
    (is (= :env (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/bobr/infra/support.env.example"))))
    (is (= [:env-file] (mapv :kind (:chunks env-result))))
    (is (= [:html-file] (mapv :kind (:chunks html-result))))
    (is (= [:text-file] (mapv :kind (:chunks text-result))))))

(deftest extracts-binary-asset-metadata-without-text-chunks
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "agraph-binary-assets"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        png-bytes (byte-array [1 2 3 4])
        font-bytes (byte-array [5 6 7 8])
        mo-bytes (byte-array [9 10 11 12])
        key-bytes (byte-array [13 14 15 16])
        video-bytes (byte-array [17 18 19 20])
        archive-bytes (byte-array [21 22 23 24])
        jar-bytes (byte-array [25 26 27 28])
        class-bytes (byte-array [29 30 31 32])
        opaque-bytes (byte-array [33 34 35 36])
        png-file (io/file root "hero.png")
        font-file (io/file root "brand.ttf")
        mo-file (io/file root "messages.mo")
        key-file (io/file root "dev.key")
        video-file (io/file root "clip.mp4")
        archive-file (io/file root "trace.svg.gz")
        jar-file (io/file root "plugin.jar")
        class-file (io/file root "App.class")
        opaque-file (io/file root "template.penpot")]
    (java.nio.file.Files/write (.toPath png-file)
                               png-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath font-file)
                               font-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath mo-file)
                               mo-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath key-file)
                               key-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath video-file)
                               video-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath archive-file)
                               archive-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath jar-file)
                               jar-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath class-file)
                               class-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath opaque-file)
                               opaque-bytes
                               (make-array java.nio.file.OpenOption 0))
    (let [png-record (fs/file-record (.getPath root) (.getPath png-file))
          font-record (fs/file-record (.getPath root) (.getPath font-file))
          mo-record (fs/file-record (.getPath root) (.getPath mo-file))
          key-record (fs/file-record (.getPath root) (.getPath key-file))
          video-record (fs/file-record (.getPath root) (.getPath video-file))
          archive-record (fs/file-record (.getPath root) (.getPath archive-file))
          jar-record (fs/file-record (.getPath root) (.getPath jar-file))
          class-record (fs/file-record (.getPath root) (.getPath class-file))
          opaque-record (fs/file-record (.getPath root) (.getPath opaque-file))
          png-result (extract/extract-file "run/test" png-record)
          font-result (extract/extract-file "run/test" font-record)
          mo-result (extract/extract-file "run/test" mo-record)
          key-result (extract/extract-file "run/test" key-record)
          video-result (extract/extract-file "run/test" video-record)
          archive-result (extract/extract-file "run/test" archive-record)
          jar-result (extract/extract-file "run/test" jar-record)
          class-result (extract/extract-file "run/test" class-record)
          opaque-result (extract/extract-file "run/test" opaque-record)]
      (is (= :image-asset (:kind png-record)))
      (is (= :font-asset (:kind font-record)))
      (is (= :gettext-binary (:kind mo-record)))
      (is (= :secret-material (:kind key-record)))
      (is (= :media-asset (:kind video-record)))
      (is (= :archive-asset (:kind archive-record)))
      (is (= :archive-asset (:kind jar-record)))
      (is (= :compiled-artifact (:kind class-record)))
      (is (= :opaque-asset (:kind opaque-record)))
      (is (= "" (:content png-record)))
      (is (= "" (:content key-record)))
      (is (= "" (:content video-record)))
      (is (= "" (:content archive-record)))
      (is (= "" (:content jar-record)))
      (is (= "" (:content class-record)))
      (is (= "" (:content opaque-record)))
      (is (:binary? png-record))
      (is (:binary? key-record))
      (is (:binary? video-record))
      (is (:binary? archive-record))
      (is (:binary? jar-record))
      (is (:binary? class-record))
      (is (:binary? opaque-record))
      (is (= (str "sha256:" (hash/sha256-bytes-hex png-bytes))
             (:content-sha png-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex key-bytes))
             (:content-sha key-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex video-bytes))
             (:content-sha video-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex archive-bytes))
             (:content-sha archive-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex jar-bytes))
             (:content-sha jar-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex class-bytes))
             (:content-sha class-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex opaque-bytes))
             (:content-sha opaque-record)))
      (is (= [:image-asset] (mapv :kind (:nodes png-result))))
      (is (= [:font-asset] (mapv :kind (:nodes font-result))))
      (is (= [:gettext-binary] (mapv :kind (:nodes mo-result))))
      (is (= [:secret-material] (mapv :kind (:nodes key-result))))
      (is (= [:media-asset] (mapv :kind (:nodes video-result))))
      (is (= [:archive-asset] (mapv :kind (:nodes archive-result))))
      (is (= [:archive-asset] (mapv :kind (:nodes jar-result))))
      (is (= [:compiled-artifact] (mapv :kind (:nodes class-result))))
      (is (= [:opaque-asset] (mapv :kind (:nodes opaque-result))))
      (is (empty? (:chunks png-result)))
      (is (empty? (:chunks font-result)))
      (is (empty? (:chunks mo-result)))
      (is (empty? (:chunks key-result)))
      (is (empty? (:chunks video-result)))
      (is (empty? (:chunks archive-result)))
      (is (empty? (:chunks jar-result)))
      (is (empty? (:chunks class-result)))
      (is (empty? (:chunks opaque-result))))))

(deftest detects-license-template-and-shebang-files
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "agraph-text-format-coverage"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        license-file (io/file root "LICENSE")
        vendor-license-file (io/file root "cytoscape.LICENSE")
        template-file (doto (io/file root "demo.rb.template")
                        (io/make-parents))
        redirects-file (io/file root "_redirects")
        proc-file (doto (io/file root "fixtures/memory.max")
                    (io/make-parents))
        wasm-file (doto (io/file root "assets/module.wasm")
                    (io/make-parents))
        script-file (doto (io/file root "tool")
                      (io/make-parents))]
    (spit license-file "MIT\n")
    (spit vendor-license-file "Vendor license\n")
    (spit template-file "class Demo < Formula\nend\n")
    (spit redirects-file "/old /new 301\n")
    (spit proc-file "max\n")
    (spit wasm-file "wasm\n")
    (spit script-file "#!/usr/bin/env bash\nexec demo\n")
    (let [coverage (:files (fs/scan-file-coverage (.getPath root)))
          kind-by-path (into {} (keep #(when (:supported? %)
                                         [(:path %) (:kind %)])
                                      coverage))
          skipped-by-path (into {} (keep #(when-not (:supported? %)
                                            [(:path %) (:skip-reason %)])
                                         coverage))
          script-record (fs/file-record (.getPath root) (.getPath script-file))
          redirects-record (fs/file-record (.getPath root) (.getPath redirects-file))
          proc-record (fs/file-record (.getPath root) (.getPath proc-file))
          template-result (extract/extract-file
                           "run/test"
                           (fs/file-record (.getPath root)
                                           (.getPath template-file)))
          redirects-result (extract/extract-file "run/test" redirects-record)
          proc-result (extract/extract-file "run/test" proc-record)
          script-result (extract/extract-file "run/test" script-record)]
      (is (= :governance (fs/file-kind "LICENSE")))
      (is (= :doc (fs/file-kind "cytoscape.LICENSE")))
      (is (= :ruby (fs/file-kind "demo.rb.template")))
      (is (= :shell (:kind script-record)))
      (is (= :unknown (:kind redirects-record)))
      (is (= :unknown (:kind proc-record)))
      (is (= {"LICENSE" :governance
              "cytoscape.LICENSE" :doc
              "demo.rb.template" :ruby
              "_redirects" :unknown
              "fixtures/memory.max" :unknown
              "tool" :shell}
             kind-by-path))
      (is (= {"assets/module.wasm" :unsupported-extension}
             skipped-by-path))
      (is (contains? (set (map :label (:nodes template-result))) "demo"))
      (is (= [:unknown-file] (mapv :kind (:chunks redirects-result))))
      (is (= [:unknown-file] (mapv :kind (:chunks proc-result))))
      (is (= [:shell-file] (mapv :kind (:chunks script-result)))))))

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
  (let [root (doto (java.io.File/createTempFile "agraph-python-extract" "")
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
  (let [root (doto (java.io.File/createTempFile "agraph-long-cljs" "")
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
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
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
  (let [root (doto (java.io.File/createTempFile "agraph-types" "")
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

(deftest extracts-shell-as-searchable-source-chunk
  (let [shell-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/scripts/setup.sh"))]
    (is (= [:shell-file] (mapv :kind (:chunks shell-result))))
    (is (= [:shell-file] (mapv :kind (:nodes shell-result))))))

(deftest extracts-shell-functions-as-source-definitions
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "agraph-shell-extract"
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
                       "agraph-env-extract"
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
                       "agraph-unknown-extract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (doto (io/file root "facts.ini")
               (spit "owner=postgres\n"))
        record (fs/file-record (.getPath root) (.getPath file))
        result (extract/extract-file "run/test" record)]
    (is (= :unknown (:kind record)))
    (is (= [:unknown-file] (mapv :kind (:chunks result))))
    (is (empty? (:nodes result)))
    (is (empty? (:edges result)))))

(deftest extracts-ci-workflows-jobs-needs-and-command-chunks
  (let [github-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml"))
        gitlab-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/ci/.gitlab-ci.yml"))
        jenkins-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/ci/Jenkinsfile"))
        azure-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/azure-pipelines.yml"))
        circleci-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/ci/.circleci/config.yml"))
        buildkite-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/ci/.buildkite/pipeline.yml"))
        drone-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.drone.yml"))
        woodpecker-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/ci/.woodpecker.yml"))
        github-labels (set (map :label (:nodes github-result)))
        github-kinds (frequencies (map :kind (:nodes github-result)))
        github-relations (frequencies (map :relation (:edges github-result)))
        github-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks github-result))
        gitlab-labels (set (map :label (:nodes gitlab-result)))
        gitlab-kinds (frequencies (map :kind (:nodes gitlab-result)))
        gitlab-relations (frequencies (map :relation (:edges gitlab-result)))
        gitlab-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks gitlab-result))
        jenkins-labels (set (map :label (:nodes jenkins-result)))
        jenkins-kinds (frequencies (map :kind (:nodes jenkins-result)))
        jenkins-relations (frequencies (map :relation (:edges jenkins-result)))
        jenkins-command-chunks (filter #(= :ci-command (:kind %))
                                       (:chunks jenkins-result))
        azure-labels (set (map :label (:nodes azure-result)))
        azure-kinds (frequencies (map :kind (:nodes azure-result)))
        azure-relations (frequencies (map :relation (:edges azure-result)))
        azure-command-chunks (filter #(= :ci-command (:kind %))
                                     (:chunks azure-result))
        circleci-labels (set (map :label (:nodes circleci-result)))
        circleci-kinds (frequencies (map :kind (:nodes circleci-result)))
        circleci-relations (frequencies (map :relation (:edges circleci-result)))
        circleci-command-chunks (filter #(= :ci-command (:kind %))
                                        (:chunks circleci-result))
        buildkite-labels (set (map :label (:nodes buildkite-result)))
        buildkite-kinds (frequencies (map :kind (:nodes buildkite-result)))
        buildkite-relations (frequencies (map :relation (:edges buildkite-result)))
        buildkite-command-chunks (filter #(= :ci-command (:kind %))
                                         (:chunks buildkite-result))
        drone-labels (set (map :label (:nodes drone-result)))
        drone-kinds (frequencies (map :kind (:nodes drone-result)))
        drone-relations (frequencies (map :relation (:edges drone-result)))
        drone-command-chunks (filter #(= :ci-command (:kind %))
                                     (:chunks drone-result))
        woodpecker-labels (set (map :label (:nodes woodpecker-result)))
        woodpecker-kinds (frequencies (map :kind (:nodes woodpecker-result)))
        woodpecker-relations (frequencies (map :relation (:edges woodpecker-result)))
        woodpecker-command-chunks (filter #(= :ci-command (:kind %))
                                          (:chunks woodpecker-result))]
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/Jenkinsfile"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/azure-pipelines.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.circleci/config.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.buildkite/pipeline.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.drone.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.woodpecker.yml"))))
    (is (contains? github-labels "CI"))
    (is (contains? github-labels "push"))
    (is (contains? github-labels "pull_request"))
    (is (contains? github-labels "test"))
    (is (contains? github-labels "deploy"))
    (is (contains? github-labels "ubuntu-latest"))
    (is (contains? github-labels "ghcr.io/acme/deploy:latest"))
    (is (contains? github-labels "actions/checkout@v4"))
    (is (contains? github-labels "actions/cache@v4"))
    (is (contains? github-labels "actions/upload-artifact@v4"))
    (is (contains? github-labels "test:TEST_DB"))
    (is (contains? github-labels "apps/web"))
    (is (= 2 (:ci-trigger github-kinds)))
    (is (= 3 (:ci-action github-kinds)))
    (is (= 1 (:ci-runner github-kinds)))
    (is (= 1 (:container-image github-kinds)))
    (is (= 1 (:ci-env-var github-kinds)))
    (is (= 1 (:ci-working-directory github-kinds)))
    (is (= 2 (get github-relations :defines 0)))
    (is (= 1 (get github-relations :requires 0)))
    (is (= 10 (get github-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text github-command-chunks))))
    (is (contains? gitlab-labels "ci/.gitlab-ci.yml"))
    (is (contains? gitlab-labels "test"))
    (is (contains? gitlab-labels "deploy"))
    (is (contains? gitlab-labels "clojure:tools-deps"))
    (is (contains? gitlab-labels "test:TEST_DB"))
    (is (contains? gitlab-labels "test:cache"))
    (is (contains? gitlab-labels "deploy:artifacts"))
    (is (contains? gitlab-labels "local:ci/common.yml"))
    (is (contains? gitlab-labels "template:Security/SAST.gitlab-ci.yml"))
    (is (= 1 (:container-image gitlab-kinds)))
    (is (= 2 (:ci-template gitlab-kinds)))
    (is (= 1 (:ci-env-var gitlab-kinds)))
    (is (= 1 (:ci-cache gitlab-kinds)))
    (is (= 1 (:ci-artifact gitlab-kinds)))
    (is (= 2 (get gitlab-relations :defines 0)))
    (is (= 1 (get gitlab-relations :requires 0)))
    (is (= 6 (get gitlab-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text gitlab-command-chunks))))
    (is (contains? jenkins-labels "ci/Jenkinsfile"))
    (is (contains? jenkins-labels "Test"))
    (is (contains? jenkins-labels "Publish"))
    (is (contains? jenkins-labels "cron:H 4 * * *"))
    (is (contains? jenkins-labels "any"))
    (is (contains? jenkins-labels "Test:TEST_DB"))
    (is (contains? jenkins-labels "jdk:temurin-21"))
    (is (contains? jenkins-labels "Publish:target/report"))
    (is (= 2 (:ci-stage jenkins-kinds)))
    (is (= 1 (:ci-trigger jenkins-kinds)))
    (is (= 1 (:ci-runner jenkins-kinds)))
    (is (= 1 (:ci-env-var jenkins-kinds)))
    (is (= 1 (:ci-tool jenkins-kinds)))
    (is (= 1 (:ci-artifact jenkins-kinds)))
    (is (= 2 (get jenkins-relations :defines 0)))
    (is (= 5 (get jenkins-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text jenkins-command-chunks))))
    (is (contains? azure-labels "ci/azure-pipelines.yml"))
    (is (contains? azure-labels "trigger:main"))
    (is (contains? azure-labels "pr:main"))
    (is (contains? azure-labels "Test"))
    (is (contains? azure-labels "Publish"))
    (is (contains? azure-labels "unit"))
    (is (contains? azure-labels "deploy"))
    (is (contains? azure-labels "ubuntu-latest"))
    (is (contains? azure-labels "unit:TEST_DB"))
    (is (contains? azure-labels "checkout:self"))
    (is (contains? azure-labels "PublishPipelineArtifact@1"))
    (is (contains? azure-labels "report"))
    (is (contains? azure-labels "templates/base.yml"))
    (is (= 2 (:ci-stage azure-kinds)))
    (is (= 2 (:ci-job azure-kinds)))
    (is (= 2 (:ci-trigger azure-kinds)))
    (is (= 1 (:ci-runner azure-kinds)))
    (is (= 1 (:ci-env-var azure-kinds)))
    (is (= 2 (:ci-action azure-kinds)))
    (is (= 1 (:ci-template azure-kinds)))
    (is (= 1 (:ci-artifact azure-kinds)))
    (is (= 4 (get azure-relations :defines 0)))
    (is (= 1 (get azure-relations :requires 0)))
    (is (= 8 (get azure-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text azure-command-chunks))))
    (is (contains? circleci-labels "ci/.circleci/config.yml"))
    (is (contains? circleci-labels "build"))
    (is (contains? circleci-labels "test"))
    (is (contains? circleci-labels "deploy"))
    (is (contains? circleci-labels "clojure"))
    (is (contains? circleci-labels "checkout"))
    (is (contains? circleci-labels "test:cache"))
    (is (contains? circleci-labels "target/report"))
    (is (contains? circleci-labels "circleci/node@5.1.0"))
    (is (= 2 (:ci-job circleci-kinds)))
    (is (= 1 (:ci-workflow-entry circleci-kinds)))
    (is (= 1 (:ci-template circleci-kinds)))
    (is (pos? (get circleci-relations :requires 0)))
    (is (pos? (get circleci-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text circleci-command-chunks))))
    (is (contains? buildkite-labels "ci/.buildkite/pipeline.yml"))
    (is (contains? buildkite-labels "test"))
    (is (contains? buildkite-labels "deploy"))
    (is (contains? buildkite-labels "default"))
    (is (contains? buildkite-labels "docker#v5.9.0"))
    (is (contains? buildkite-labels "clojure:tools-deps"))
    (is (contains? buildkite-labels "target/report"))
    (is (= 2 (:ci-job buildkite-kinds)))
    (is (= 1 (:ci-plugin buildkite-kinds)))
    (is (pos? (get buildkite-relations :requires 0)))
    (is (pos? (get buildkite-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text buildkite-command-chunks))))
    (is (contains? drone-labels "default"))
    (is (contains? drone-labels "push"))
    (is (contains? drone-labels "test"))
    (is (contains? drone-labels "deploy"))
    (is (contains? drone-labels "clojure:tools-deps"))
    (is (contains? drone-labels "alpine:3.20"))
    (is (contains? drone-labels "test:TEST_DB"))
    (is (= 1 (:ci-trigger drone-kinds)))
    (is (= 2 (:ci-job drone-kinds)))
    (is (= 2 (:container-image drone-kinds)))
    (is (= 1 (:ci-env-var drone-kinds)))
    (is (= 2 (get drone-relations :defines 0)))
    (is (= 1 (get drone-relations :requires 0)))
    (is (= 4 (get drone-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text drone-command-chunks))))
    (is (contains? woodpecker-labels "ci/.woodpecker.yml"))
    (is (contains? woodpecker-labels "push"))
    (is (contains? woodpecker-labels "test"))
    (is (contains? woodpecker-labels "deploy"))
    (is (contains? woodpecker-labels "clojure:tools-deps"))
    (is (contains? woodpecker-labels "alpine:3.20"))
    (is (contains? woodpecker-labels "test:TEST_DB"))
    (is (= 1 (:ci-trigger woodpecker-kinds)))
    (is (= 2 (:ci-job woodpecker-kinds)))
    (is (= 2 (:container-image woodpecker-kinds)))
    (is (= 1 (:ci-env-var woodpecker-kinds)))
    (is (= 2 (get woodpecker-relations :defines 0)))
    (is (= 1 (get woodpecker-relations :requires 0)))
    (is (= 4 (get woodpecker-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"}
           (set (map :text woodpecker-command-chunks))))))

(deftest extracts-build-targets-and-dependencies
  (let [make-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/build/Makefile"))
        cmake-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/CMakeLists.txt"))
        cmake-module-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/build/toolchain.cmake"))
        bazel-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/BUILD.bazel"))
        buck-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/build/BUCK"))
        pants-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/pants/BUILD"))
        pants-toml-result (extract/extract-file
                           "run/test"
                           (fs/file-record
                            "test/fixtures/extractor-repo"
                            "test/fixtures/extractor-repo/build/pants.toml"))
        make-labels (set (map :label (:nodes make-result)))
        cmake-labels (set (map :label (:nodes cmake-result)))
        cmake-module-labels (set (map :label (:nodes cmake-module-result)))
        bazel-labels (set (map :label (:nodes bazel-result)))
        buck-labels (set (map :label (:nodes buck-result)))
        pants-labels (set (map :label (:nodes pants-result)))
        pants-toml-labels (set (map :label (:nodes pants-toml-result)))
        bazel-kinds (frequencies (map :kind (:nodes bazel-result)))
        buck-kinds (frequencies (map :kind (:nodes buck-result)))
        pants-kinds (frequencies (map :kind (:nodes pants-result)))
        pants-toml-kinds (frequencies (map :kind (:nodes pants-toml-result)))
        bazel-relations (frequencies (map :relation (:edges bazel-result)))
        buck-relations (frequencies (map :relation (:edges buck-result)))
        pants-relations (frequencies (map :relation (:edges pants-result)))
        pants-toml-relations (frequencies (map :relation (:edges pants-toml-result)))
        requires-targets (fn [result]
                           (set (map :target-id
                                     (filter #(= :requires (:relation %))
                                             (:edges result)))))]
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/Makefile"))))
    (is (contains? make-labels "build/Makefile"))
    (is (contains? make-labels "test"))
    (is (contains? make-labels "deps"))
    (is (contains? (requires-targets make-result)
                   (extract/node-id :build-target "deps")))
    (is (contains? cmake-labels "build/CMakeLists.txt"))
    (is (contains? cmake-labels "core"))
    (is (contains? cmake-labels "app"))
    (is (contains? (requires-targets cmake-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/toolchain.cmake"))))
    (is (contains? cmake-module-labels "build/toolchain.cmake"))
    (is (contains? cmake-module-labels "shared"))
    (is (contains? (requires-targets cmake-module-result)
                   (extract/node-id :build-target "shared")))
    (is (contains? bazel-labels "build/BUILD.bazel"))
    (is (contains? bazel-labels "core"))
    (is (contains? bazel-labels "app"))
    (is (contains? bazel-labels "core:cc_library"))
    (is (contains? bazel-labels "app:cc_binary"))
    (is (contains? bazel-labels "core.cc"))
    (is (contains? bazel-labels "app.cc"))
    (is (contains? bazel-labels "//assets:panels"))
    (is (contains? bazel-labels "//visibility:public"))
    (is (contains? bazel-labels "@rules_cc//cc:defs"))
    (is (= 2 (:build-rule bazel-kinds)))
    (is (= 2 (:build-source bazel-kinds)))
    (is (= 1 (:build-data bazel-kinds)))
    (is (= 1 (:build-visibility bazel-kinds)))
    (is (= 1 (:build-reference bazel-kinds)))
    (is (= {:defines 2 :uses 3 :references 3 :requires 2}
           (select-keys bazel-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets bazel-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/BUCK"))))
    (is (contains? buck-labels "build/BUCK"))
    (is (contains? buck-labels "core"))
    (is (contains? buck-labels "app"))
    (is (contains? buck-labels "core:cxx_library"))
    (is (contains? buck-labels "app:cxx_binary"))
    (is (contains? buck-labels "core.cpp"))
    (is (contains? buck-labels "app.cpp"))
    (is (contains? buck-labels "//assets:panels"))
    (is (contains? buck-labels "PUBLIC"))
    (is (contains? buck-labels "//third_party/fmt:fmt"))
    (is (= 2 (:build-rule buck-kinds)))
    (is (= 2 (:build-source buck-kinds)))
    (is (= 1 (:build-data buck-kinds)))
    (is (= 1 (:build-visibility buck-kinds)))
    (is (= 1 (:build-reference buck-kinds)))
    (is (= {:defines 2 :uses 3 :references 3 :requires 2}
           (select-keys buck-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets buck-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/pants/BUILD"))))
    (is (contains? pants-labels "build/pants/BUILD"))
    (is (contains? pants-labels "lib"))
    (is (contains? pants-labels "resources"))
    (is (contains? pants-labels "tests"))
    (is (contains? pants-labels "lib:python_sources"))
    (is (contains? pants-labels "resources:resources"))
    (is (contains? pants-labels "tests:python_tests"))
    (is (contains? pants-labels "*.py"))
    (is (contains? pants-labels "templates/*.html"))
    (is (contains? pants-labels "3rdparty/python#requests"))
    (is (= 3 (:build-target pants-kinds)))
    (is (= 3 (:build-rule pants-kinds)))
    (is (= 2 (:build-source pants-kinds)))
    (is (= 1 (:build-reference pants-kinds)))
    (is (= {:defines 3 :uses 3 :references 2 :requires 3}
           (select-keys pants-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets pants-result)
                   (extract/node-id :build-target "resources")))
    (is (contains? (requires-targets pants-result)
                   (extract/node-id :build-target "lib")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/pants.toml"))))
    (is (contains? pants-toml-labels "build/pants.toml"))
    (is (contains? pants-toml-labels "pants.backend.python"))
    (is (contains? pants-toml-labels "pants.backend.python.lint.ruff"))
    (is (contains? pants-toml-labels "/src/python"))
    (is (contains? pants-toml-labels "/tests/python"))
    (is (contains? pants-toml-labels "GLOBAL.pants_version=2.24.0"))
    (is (contains? pants-toml-labels
                   "python.interpreter_constraints=CPython>=3.11,<3.13"))
    (is (= 2 (:build-plugin pants-toml-kinds)))
    (is (= 2 (:build-source-root pants-toml-kinds)))
    (is (= 2 (:build-setting pants-toml-kinds)))
    (is (= {:uses 2 :references 2 :defines 2}
           (select-keys pants-toml-relations [:uses :references :defines])))))

(deftest extracts-sql-tables-views-and-foreign-keys
  (let [result (extract/extract-file
                "run/test"
                (fs/file-record "test/fixtures/sample-repo"
                                "test/fixtures/sample-repo/db/schema.sql"))
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        references (filter #(= :references (:relation %)) (:edges result))]
    (is (= [:sql-file] (mapv :kind (:chunks result))))
    (is (contains? labels "panels"))
    (is (contains? labels "panel_events"))
    (is (contains? labels "active_panels"))
    (is (= 2 (:table kinds)))
    (is (= 1 (:view kinds)))
    (is (= 1 (count references)))
    (is (= (extract/node-id :table "panels") (:target-id (first references))))))

(deftest extracts-terraform-blocks-and-explicit-references
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/infra/main.tf")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :terraform (:kind file)))
    (is (contains? labels "infra/main.tf"))
    (is (contains? labels "aws_s3_bucket.assets"))
    (is (contains? labels "aws_s3_bucket_policy.assets"))
    (is (contains? labels "module.cdn"))
    (is (contains? labels "provider.aws"))
    (is (contains? labels "provider.aws.edge"))
    (is (contains? labels "data.aws_caller_identity.current"))
    (is (contains? labels "var.region"))
    (is (contains? labels "output.bucket_name"))
    (is (contains? labels "./modules/cdn"))
    (is (= 1 (:terraform-file kinds)))
    (is (= 2 (:terraform-resource kinds)))
    (is (= 1 (:terraform-data-source kinds)))
    (is (= 1 (:terraform-module kinds)))
    (is (= 1 (:terraform-provider kinds)))
    (is (= 1 (:terraform-variable kinds)))
    (is (= 1 (:terraform-output kinds)))
    (is (= 1 (:terraform-provider-alias kinds)))
    (is (= 1 (:terraform-module-source kinds)))
    (is (= 8 (:defines relations)))
    (is (= 5 (:references relations)))
    (is (= 2 (:uses relations)))
    (is (some #(= :terraform-block (:kind %)) (:chunks result)))))

(deftest extracts-openapi-paths-operations-and-schemas
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/api/openapi.yaml")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :openapi (:kind file)))
    (is (contains? labels "api/openapi.yaml"))
    (is (contains? labels "https://api.example.test"))
    (is (contains? labels "/panels"))
    (is (contains? labels "/panels/{id}"))
    (is (contains? labels "GET /panels listPanels"))
    (is (contains? labels "POST /panels createPanel"))
    (is (contains? labels "GET /panels/{id} getPanel"))
    (is (contains? labels "Panel"))
    (is (contains? labels "PanelCreate"))
    (is (= 1 (:api-spec kinds)))
    (is (= 1 (:api-server kinds)))
    (is (= 2 (:api-path kinds)))
    (is (= 3 (:api-operation kinds)))
    (is (= 2 (:api-schema kinds)))
    (is (= 8 (:defines relations)))
    (is (= 4 (:references relations)))
    (is (= [:openapi-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-asyncapi-channels-operations-messages-and-schemas
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/events.asyncapi.json")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :asyncapi (:kind file)))
    (is (contains? labels "contracts/events.asyncapi.json"))
    (is (contains? labels "panel.created"))
    (is (contains? labels "publishPanelCreated"))
    (is (contains? labels "PanelCreated"))
    (is (contains? labels "Panel"))
    (is (contains? labels "EventHeaders"))
    (is (contains? labels "production"))
    (is (contains? labels "panel.created:amqp"))
    (is (contains? labels "PanelCreated:amqp"))
    (is (contains? labels "secured"))
    (is (contains? labels "PanelCreated"))
    (is (contains? labels "PanelCreated:$message.header#/correlationId"))
    (is (= 1 (:asyncapi-spec kinds)))
    (is (= 1 (:asyncapi-server kinds)))
    (is (= 1 (:asyncapi-channel kinds)))
    (is (= 1 (:asyncapi-operation kinds)))
    (is (= 1 (:asyncapi-message kinds)))
    (is (= 2 (:asyncapi-schema kinds)))
    (is (= 1 (:asyncapi-operation-trait kinds)))
    (is (= 2 (:asyncapi-binding kinds)))
    (is (= 1 (:asyncapi-header kinds)))
    (is (= 1 (:asyncapi-correlation-id kinds)))
    (is (= 11 (:defines relations)))
    (is (= 4 (:references relations)))
    (is (= [:asyncapi-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-json-schema-definitions-properties-and-refs
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/panel.schema.json")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :json-schema (:kind file)))
    (is (contains? labels "https://example.com/schemas/panel"))
    (is (contains? labels "User"))
    (is (contains? labels "id"))
    (is (contains? labels "owner"))
    (is (contains? labels "#/$defs/User"))
    (is (= 1 (:json-schema kinds)))
    (is (= 1 (:json-schema-definition kinds)))
    (is (= 2 (:json-schema-property kinds)))
    (is (= 1 (:json-schema-reference kinds)))
    (is (= 3 (:defines relations)))
    (is (= 1 (:references relations)))
    (is (= [:json-schema-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-avro-schemas-fields-and-type-references
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/panel.avsc")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :avro (:kind file)))
    (is (contains? labels "contracts/panel.avsc"))
    (is (contains? labels "acme.panels.PanelEvent"))
    (is (contains? labels "acme.panels.PanelEvent.id"))
    (is (contains? labels "acme.panels.PanelEvent.owner"))
    (is (contains? labels "User"))
    (is (= 1 (:avro-file kinds)))
    (is (= 1 (:avro-record kinds)))
    (is (= 2 (:avro-field kinds)))
    (is (= 1 (:avro-reference kinds)))
    (is (= 3 (:defines relations)))
    (is (= 1 (:references relations)))
    (is (= [:avro-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))
