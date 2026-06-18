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
                               "test/fixtures/extractor-repo/db/migration/V1__create_panels.sql")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/db/changelog/db.changelog-master.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/cloudformation.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/ops/Pulumi.yaml")
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
                               "test/fixtures/extractor-repo/.github/dependabot.yml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/.storybook/main.ts")
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
                               "test/fixtures/extractor-repo/infra/chart/Chart.yaml")
               (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/infra/k8s/deployment.yaml")
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

(deftest extracts-rust-modules-definitions-uses-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/rust/lib.rs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "src::rust::lib/Config"))
    (is (contains? labels "src::rust::lib/run"))
    (is (= 1 (get relations :declares-module 0)))
    (is (= 1 (get relations :uses 0)))
    (is (pos? (get relations :defines 0)))))

(deftest extracts-go-packages-definitions-imports-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/internal/cli/flows.go")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :go (:kind file)))
    (is (contains? labels "internal/cli/flows"))
    (is (contains? labels "internal/cli/flows/Client"))
    (is (contains? labels "internal/cli/flows/RunFlow"))
    (is (contains? labels "internal/cli/flows/Client.PublishFlow"))
    (is (= :code-definition (get-in chunks-by-label ["internal/cli/flows/RunFlow" :kind])))
    (is (= :function (get-in chunks-by-label ["internal/cli/flows/RunFlow" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["internal/cli/flows/RunFlow" :text])
                       "flowapi.Start"))
    (is (= :method (get-in chunks-by-label ["internal/cli/flows/Client.PublishFlow" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["internal/cli/flows/Client.PublishFlow" :text])
                       "RunFlow"))
    (is (= 2 (get relations :imports 0)))
    (is (<= 4 (get relations :defines 0)))
    (is (pos? (get relations :calls 0)))))

(deftest extracts-java-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/java/com/example/panels/PanelService.java")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (= :java (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelService"))
    (is (contains? labels "com.example.panels/PanelService.PanelService"))
    (is (contains? labels "com.example.panels/PanelService.loadPanel"))
    (is (contains? labels "com.example.panels/PanelService.audit"))
    (is (contains? labels "com.example.panels/Panel"))
    (is (contains? labels "com.example.panels/PanelClient"))
    (is (contains? labels "com.example.panels/PanelClient.fetch"))
    (is (contains? labels "com.example.panels/PanelStatus"))
    (is (contains? labels "com.example.panels/PanelBinding"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "java.net.URI")))
    (is (contains? import-targets
                   (extract/node-id :namespace "java.util.Objects.requireNonNull")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "com.example.panels/Panel")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "com.example.panels/PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :text])
         "client.fetch"))
    (is (= [:java-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.java"
                                      :path "Broken.java"
                                      :kind :java
                                      :content "package demo;\npublic class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest java-extractor-does-not-treat-field-initializers-as-methods
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Options.java"
                 :path "src/Options.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "import static java.util.Collections.emptyList;\n"
                               "import java.util.List;\n"
                               "class Options {\n"
                               "  private List<String> selectedUniqueIds = emptyList();\n"
                               "  public List<String> getSelectedUniqueIds() {\n"
                               "    return selectedUniqueIds;\n"
                               "  }\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "demo/Options"))
    (is (contains? labels "demo/Options.getSelectedUniqueIds"))
    (is (not (contains? labels "demo/Options.emptyList")))
    (is (empty? (:diagnostics result)))))

(deftest java-reference-extraction-resolves-explicit-imports
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:DiscoveryRequestCreatorTests.java"
                 :path "src/DiscoveryRequestCreatorTests.java"
                 :kind :java
                 :content (str "package org.example.tasks;\n"
                               "import org.example.options.TestDiscoveryOptions;\n"
                               "class DiscoveryRequestCreatorTests {\n"
                               "  private final TestDiscoveryOptions options = new TestDiscoveryOptions();\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id
                    :symbol
                    "org.example.options/TestDiscoveryOptions")))
    (is (not (contains? reference-targets
                        (extract/node-id
                         :symbol
                         "org.example.tasks/TestDiscoveryOptions"))))
    (is (empty? (:diagnostics result)))))

(deftest java-reference-extraction-ignores-comments-and-strings
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Noise.java"
                 :path "src/Noise.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "class Noise {\n"
                               "  String description = \"MentionedType in prose\";\n"
                               "  // CommentType in a line comment\n"
                               "  /* BlockType in a block comment */\n"
                               "  Target make(Param param) {\n"
                               "    String text = \"BodyType in method body\";\n"
                               "    return new Target();\n"
                               "  }\n"
                               "  static class Target {}\n"
                               "  static class Param {}\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Target")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Param")))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/MentionedType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/CommentType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BlockType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BodyType"))))))

(deftest java-reference-extraction-handles-large-method-bodies
  (let [body-lines (apply str
                          (repeat
                           1200
                           "    if (input > 0) { total += helper.compute(input); }\n"))
        result (extract/extract-file
                "run/test"
                {:file-id "file:LargeBody.java"
                 :path "src/LargeBody.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "class LargeBody {\n"
                               "  Result build(Input input) throws BuildException {\n"
                               body-lines
                               "    String ignored = \"BodyMention\";\n"
                               "    return new Result();\n"
                               "  }\n"
                               "  static class Result {}\n"
                               "  static class Input {}\n"
                               "  static class BuildException extends RuntimeException {}\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Result")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Input")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/BuildException")))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BodyMention"))))
    (is (empty? (:diagnostics result)))))

(deftest java-parser-worker-extractor-emits-canonical-rows-when-enabled
  (let [python-bin (or (not-empty (System/getenv "AGRAPH_PARSER_WORKER_PYTHON"))
                       ".dev/parser-worker-venv/bin/python")
        worker-ready? (and (.isFile (io/file python-bin))
                           (zero? (:exit (shell/sh python-bin
                                                   "-c"
                                                   "import tree_sitter_language_pack"))))]
    (when worker-ready?
      (let [result (with-redefs [extract/parser-worker-enabled? (constantly true)
                                 extract/parser-worker-python (constantly python-bin)]
                     (extract/extract-file
                      "run/test"
                      {:file-id "file:WorkerDemo.java"
                       :path "src/WorkerDemo.java"
                       :kind :java
                       :content (str "package demo;\n"
                                     "import java.net.URI;\n"
                                     "class App {\n"
                                     "  Target make(Param p) { return new Target(); }\n"
                                     "  static class Target {}\n"
                                     "  interface Param {}\n"
                                     "}\n")}))
            labels (set (map :label (:nodes result)))
            reference-targets (set (map :target-id
                                        (filter #(= :references (:relation %))
                                                (:edges result))))]
        (is (contains? labels "demo"))
        (is (contains? labels "demo/App"))
        (is (contains? labels "demo/App.make"))
        (is (contains? labels "demo/App.Target"))
        (is (contains? labels "demo/App.Param"))
        (is (contains? reference-targets
                       (extract/node-id :symbol "demo/Target")))
        (is (contains? reference-targets
                       (extract/node-id :symbol "demo/Param")))
        (is (empty? (:diagnostics result)))))))

(deftest extracts-dotnet-namespaces-definitions-and-usings
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/dotnet/PanelService.cs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :dotnet (:kind file)))
    (is (contains? labels "Acme.Panels"))
    (is (contains? labels "Acme.Panels/Panel"))
    (is (contains? labels "Acme.Panels/PanelService"))
    (is (contains? labels "Acme.Panels/PanelService.PanelService"))
    (is (contains? labels "Acme.Panels/PanelService.LoadPanel"))
    (is (contains? labels "Acme.Panels/PanelService.Normalize"))
    (is (contains? labels "Acme.Panels/PanelService.Name"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "System")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme.Contracts")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :definition-kind])))
    (is (= :property
           (get-in chunks-by-label ["Acme.Panels/PanelService.Name" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :text])
         "client.Fetch"))
    (is (= [:dotnet-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.cs"
                                      :path "Broken.cs"
                                      :kind :dotnet
                                      :content "namespace Demo;\npublic class Broken\n{\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces")))
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:BlockScoped.cs"
                 :path "BlockScoped.cs"
                 :kind :dotnet
                 :content (str "namespace Acme.Block {\n"
                               "public interface IPanel {}\n"
                               "public enum PanelKind { Primary }\n"
                               "public struct PanelKey {}\n"
                               "public delegate void PanelLoaded(string id);\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "Acme.Block"))
    (is (contains? labels "Acme.Block/IPanel"))
    (is (contains? labels "Acme.Block/PanelKind"))
    (is (contains? labels "Acme.Block/PanelKey"))
    (is (contains? labels "Acme.Block/PanelLoaded"))
    (is (empty? (:diagnostics result)))))

(deftest dotnet-extractor-ignores-call-expressions-inside-method-bodies
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Noisy.cs"
                 :path "Noisy.cs"
                 :kind :dotnet
                 :content (str "namespace Demo;\n"
                               "public class App {\n"
                               "  public App() {}\n"
                               "  public string Run(object input) {\n"
                               "    var text = Convert.ToString(input);\n"
                               "    throw new ArgumentNullException(nameof(input));\n"
                               "  }\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "Demo/App.App"))
    (is (contains? labels "Demo/App.Run"))
    (is (not (contains? labels "Demo/App.ToString")))
    (is (not (contains? labels "Demo/App.ArgumentNullException")))
    (is (not (contains? labels "Demo/App.nameof")))
    (is (empty? (:diagnostics result)))))

(deftest extracts-kotlin-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/mobile/android/PanelActivity.kt")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :kotlin (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelActivity"))
    (is (contains? labels "com.example.panels/PanelActivity.title"))
    (is (contains? labels "com.example.panels/PanelActivity.loadPanel"))
    (is (contains? labels "com.example.panels/PanelActivity.Companion"))
    (is (contains? labels "com.example.panels/PanelActivity.Companion.routePrefix"))
    (is (contains? labels "com.example.panels/PanelRoutes"))
    (is (contains? labels "com.example.panels/PanelRoutes.route"))
    (is (contains? labels "com.example.panels/PanelMode"))
    (is (contains? labels "com.example.panels/PanelBinding"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "android.app.Activity")))
    (is (contains? import-targets
                   (extract/node-id :namespace "com.example.panels.data.PanelRepository")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelActivity.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["com.example.panels/PanelActivity.loadPanel" :definition-kind])))
    (is (= [:kotlin-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.kt"
                                      :path "Broken.kt"
                                      :kind :kotlin
                                      :content "package demo\nclass Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-swift-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/mobile/ios/PanelViewModel.swift")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :swift (:kind file)))
    (is (contains? labels "mobile.ios.PanelViewModel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.title"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.init"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.loadPanel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelView"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelView.model"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelLoading"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelLoading.loadPanel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelCache"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelCache.count"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.refresh"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Foundation")))
    (is (contains? import-targets
                   (extract/node-id :namespace "SwiftUI")))
    (is (= :code-definition
           (get-in chunks-by-label ["mobile.ios.PanelViewModel/PanelViewModel.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["mobile.ios.PanelViewModel/PanelViewModel.loadPanel" :definition-kind])))
    (is (= [:swift-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.swift"
                                      :path "Broken.swift"
                                      :kind :swift
                                      :content "public class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-ruby-definitions-requires-and-rake-tasks
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/ruby/panel_service.rb")
        rakefile (fs/file-record "test/fixtures/extractor-repo"
                                 "test/fixtures/extractor-repo/src/ruby/Rakefile")
        result (extract/extract-file "run/test" file)
        rake-result (extract/extract-file "run/test" rakefile)
        labels (set (map :label (:nodes result)))
        rake-labels (set (map :label (:nodes rake-result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :ruby (:kind file)))
    (is (= :ruby (:kind rakefile)))
    (is (contains? labels "src.ruby.panel_service"))
    (is (contains? labels "src.ruby.panel_service/Panels"))
    (is (contains? labels "src.ruby.panel_service/Panels::DEFAULT_PANEL"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService#load_panel"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService.audit"))
    (is (contains? rake-labels "src.ruby.Rakefile/build"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "json")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.ruby.panel_service/Panels::PanelService#load_panel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["src.ruby.panel_service/Panels::PanelService#load_panel" :definition-kind])))
    (is (= [:ruby-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-cpp-includes-definitions-and-diagnostics
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/native/panel_service.cpp")
        header (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/native/panel_service.hpp")
        result (extract/extract-file "run/test" file)
        header-result (extract/extract-file "run/test" header)
        labels (set (map :label (:nodes result)))
        header-labels (set (map :label (:nodes header-result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :cpp (:kind file)))
    (is (= :cpp (:kind header)))
    (is (contains? labels "src.native.panel_service"))
    (is (contains? labels "src.native.panel_service/PANEL_SERVICE_VERSION"))
    (is (contains? labels "src.native.panel_service/panels"))
    (is (contains? labels "src.native.panel_service/panels::PanelId"))
    (is (contains? labels "src.native.panel_service/panels::PanelService::load_panel"))
    (is (contains? labels "src.native.panel_service/panels::build_panel"))
    (is (contains? header-labels "src.native.panel_service/PanelName"))
    (is (contains? header-labels "src.native.panel_service/panels"))
    (is (contains? header-labels "src.native.panel_service/panels::PanelService"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_service.hpp")))
    (is (contains? import-targets
                   (extract/node-id :namespace "vector")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.native.panel_service/panels::build_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.native.panel_service/panels::build_panel" :definition-kind])))
    (is (= [:cpp-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.cpp"
                                      :path "broken.cpp"
                                      :kind :cpp
                                      :content "namespace demo {\nint broken() {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-dart-imports-definitions-and-diagnostics
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/flutter/lib/panel_store.dart")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :dart (:kind file)))
    (is (contains? labels "panels.store"))
    (is (contains? labels "panels.store/PanelStore"))
    (is (contains? labels "panels.store/PanelStore.PanelStore"))
    (is (contains? labels "panels.store/PanelStore.client"))
    (is (contains? labels "panels.store/PanelStore.cacheName"))
    (is (contains? labels "panels.store/PanelStore.loadPanel"))
    (is (contains? labels "panels.store/PanelStore.selected"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "package:flutter/widgets.dart")))
    (is (contains? import-targets
                   (extract/node-id :namespace "package:panels/client.dart")))
    (is (= :code-definition
           (get-in chunks-by-label ["panels.store/PanelStore.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["panels.store/PanelStore.loadPanel" :definition-kind])))
    (is (= [:dart-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.dart"
                                      :path "broken.dart"
                                      :kind :dart
                                      :content "class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-scala-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/scala/PanelService.scala")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :scala (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelRepository"))
    (is (contains? labels "com.example.panels/PanelRepository.findPanel"))
    (is (contains? labels "com.example.panels/PanelService"))
    (is (contains? labels "com.example.panels/PanelService.loadPanel"))
    (is (contains? labels "com.example.panels/PanelService.cacheName"))
    (is (contains? labels "com.example.panels/PanelRoutes"))
    (is (contains? labels "com.example.panels/PanelRoutes.route"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "cats.effect.IO")))
    (is (contains? import-targets
                   (extract/node-id :namespace "com.example.panels.client.PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :definition-kind])))
    (is (= [:scala-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.scala"
                                      :path "Broken.scala"
                                      :kind :scala
                                      :content "package demo\nclass Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-elixir-modules-functions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/elixir/panel_service.ex")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :elixir (:kind file)))
    (is (contains? labels "Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.Loader"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme.Panels.PanelClient")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Logger")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel" :definition-kind])))
    (is (= [:elixir-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-erlang-modules-functions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/erlang/panel_service.erl")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :erlang (:kind file)))
    (is (contains? labels "panel_service"))
    (is (contains? labels "panel_service/gen_server"))
    (is (contains? labels "panel_service/panel"))
    (is (contains? labels "panel_service/load_panel"))
    (is (contains? labels "panel_service/load_panel/1"))
    (is (contains? labels "panel_service/normalize_id/1"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel.hrl")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["panel_service/load_panel/1" :kind])))
    (is (= :function
           (get-in chunks-by-label ["panel_service/load_panel/1" :definition-kind])))
    (is (= [:erlang-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-planned-language-variant-declarations
  (let [php-result (extract/extract-file
                    "run/test"
                    {:file-id "file:variants.php"
                     :path "variants.php"
                     :kind :php
                     :content (str "<?php\n"
                                   "namespace Demo;\n"
                                   "interface Port {}\n"
                                   "trait Logs {}\n"
                                   "enum State { case Active; }\n"
                                   "const VERSION = '1';\n")})
        scala-result (extract/extract-file
                      "run/test"
                      {:file-id "file:Variants.scala"
                       :path "Variants.scala"
                       :kind :scala
                       :content (str "package demo\n"
                                     "case class Panel(id: String)\n"
                                     "enum PanelKind { case Active }\n")})
        dart-result (extract/extract-file
                     "run/test"
                     {:file-id "file:variants.dart"
                      :path "variants.dart"
                      :kind :dart
                      :content (str "library demo.variants;\n"
                                    "final String topLevel = 'x';\n"
                                    "enum PanelKind { active }\n"
                                    "mixin Trackable {}\n"
                                    "extension PanelExt on String {\n"
                                    "  String get panelId => this;\n"
                                    "}\n")})
        elixir-result (extract/extract-file
                       "run/test"
                       {:file-id "file:variants.ex"
                        :path "variants.ex"
                        :kind :elixir
                        :content (str "defmodule Demo.Macros do\n"
                                      "  defmacro panel(name) do\n"
                                      "    name\n"
                                      "  end\n"
                                      "end\n")})
        labels (fn [result] (set (map :label (:nodes result))))
        kinds-by-label (fn [result]
                         (into {} (map (juxt :label :kind)) (:nodes result)))]
    (is (contains? (labels php-result) "Demo/Port"))
    (is (= :interface ((kinds-by-label php-result) "Demo/Port")))
    (is (= :trait ((kinds-by-label php-result) "Demo/Logs")))
    (is (= :enum ((kinds-by-label php-result) "Demo/State")))
    (is (= :constant ((kinds-by-label php-result) "Demo/VERSION")))
    (is (= :class ((kinds-by-label scala-result) "demo/Panel")))
    (is (= :enum ((kinds-by-label scala-result) "demo/PanelKind")))
    (is (= :variable ((kinds-by-label dart-result) "demo.variants/topLevel")))
    (is (= :enum ((kinds-by-label dart-result) "demo.variants/PanelKind")))
    (is (= :mixin ((kinds-by-label dart-result) "demo.variants/Trackable")))
    (is (= :extension ((kinds-by-label dart-result) "demo.variants/PanelExt")))
    (is (= :getter ((kinds-by-label dart-result) "demo.variants/PanelExt.panelId")))
    (is (= :macro ((kinds-by-label elixir-result) "Demo.Macros/Demo.Macros.panel")))))

(deftest extracts-lua-requires-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/lua/panel_service.lua")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :lua (:kind file)))
    (is (contains? labels "src.lua.panel_service"))
    (is (contains? labels "src.lua.panel_service/M"))
    (is (contains? labels "src.lua.panel_service/M.load_panel"))
    (is (contains? labels "src.lua.panel_service/normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "json")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.lua.panel_service/M.load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.lua.panel_service/M.load_panel" :definition-kind])))
    (is (= [:lua-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-r-imports-and-functions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/r/panel_service.R")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :r (:kind file)))
    (is (contains? labels "src.r.panel_service"))
    (is (contains? labels "src.r.panel_service/load_panel"))
    (is (contains? labels "src.r.panel_service/.normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "dplyr")))
    (is (contains? import-targets
                   (extract/node-id :namespace "jsonlite")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.r.panel_service/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.r.panel_service/load_panel" :definition-kind])))
    (is (= [:r-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-julia-modules-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/julia/PanelService.jl")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :julia (:kind file)))
    (is (contains? labels "PanelService"))
    (is (contains? labels "PanelService/Panel"))
    (is (contains? labels "PanelService/load_panel"))
    (is (contains? labels "PanelService/normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "DataFrames")))
    (is (contains? import-targets
                   (extract/node-id :namespace "JSON3")))
    (is (= :code-definition
           (get-in chunks-by-label ["PanelService/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["PanelService/load_panel" :definition-kind])))
    (is (= [:julia-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-perl-packages-imports-and-subroutines
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/perl/PanelService.pm")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :perl (:kind file)))
    (is (contains? labels "Acme::Panels::PanelService"))
    (is (contains? labels "Acme::Panels::PanelService/load_panel"))
    (is (contains? labels "Acme::Panels::PanelService/_normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "JSON::MaybeXS")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme::Panels::PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme::Panels::PanelService/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme::Panels::PanelService/load_panel" :definition-kind])))
    (is (= [:perl-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-haskell-modules-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/haskell/PanelService.hs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :haskell (:kind file)))
    (is (contains? labels "Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Panel"))
    (is (contains? labels "Acme.Panels.PanelService/loadPanel"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Data.Text")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Data.Aeson")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels.PanelService/loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme.Panels.PanelService/loadPanel" :definition-kind])))
    (is (= [:haskell-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-zig-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/zig/panel_service.zig")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :zig (:kind file)))
    (is (contains? labels "src.zig.panel_service"))
    (is (contains? labels "src.zig.panel_service/Panel"))
    (is (contains? labels "src.zig.panel_service/loadPanel"))
    (is (contains? labels "src.zig.panel_service/normalizeId"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "std")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client.zig")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.zig.panel_service/loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.zig.panel_service/loadPanel" :definition-kind])))
    (is (= [:zig-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))

(deftest extracts-project-manifest-dependencies-and-references
  (let [pom-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/java/pom.xml"))
        gradle-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/java/build.gradle"))
        csproj-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/src/dotnet/Panels.csproj"))
        sln-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/dotnet/Panels.sln"))
        gemfile-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/src/ruby/Gemfile"))
        gemspec-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/src/ruby/panels.gemspec"))
        pubspec-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/flutter/pubspec.yaml"))
        sbt-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/scala/build.sbt"))
        mix-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/elixir/mix.exs"))
        rebar-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/erlang/rebar.config"))
        r-description-result (extract/extract-file
                              "run/test"
                              (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/src/r/DESCRIPTION"))
        r-namespace-result (extract/extract-file
                            "run/test"
                            (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/NAMESPACE"))
        julia-project-result (extract/extract-file
                              "run/test"
                              (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/src/julia/Project.toml"))
        cpanfile-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/src/perl/cpanfile"))
        cabal-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/haskell/panels.cabal"))
        stack-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/haskell/stack.yaml"))
        pom-labels (set (map :label (:nodes pom-result)))
        gradle-labels (set (map :label (:nodes gradle-result)))
        csproj-labels (set (map :label (:nodes csproj-result)))
        sln-labels (set (map :label (:nodes sln-result)))
        gemfile-labels (set (map :label (:nodes gemfile-result)))
        gemspec-labels (set (map :label (:nodes gemspec-result)))
        pubspec-labels (set (map :label (:nodes pubspec-result)))
        sbt-labels (set (map :label (:nodes sbt-result)))
        mix-labels (set (map :label (:nodes mix-result)))
        rebar-labels (set (map :label (:nodes rebar-result)))
        r-description-labels (set (map :label (:nodes r-description-result)))
        r-namespace-labels (set (map :label (:nodes r-namespace-result)))
        julia-project-labels (set (map :label (:nodes julia-project-result)))
        cpanfile-labels (set (map :label (:nodes cpanfile-result)))
        cabal-labels (set (map :label (:nodes cabal-result)))
        stack-labels (set (map :label (:nodes stack-result)))]
    (is (contains? pom-labels "com.example:panels"))
    (is (contains? pom-labels "maven:org.slf4j:slf4j-api"))
    (is (= 1 (get (frequencies (map :relation (:edges pom-result))) :requires 0)))
    (is (contains? gradle-labels "panels-gradle"))
    (is (contains? gradle-labels "maven:com.fasterxml.jackson.core:jackson-databind"))
    (is (= 1 (get (frequencies (map :relation (:edges gradle-result))) :requires 0)))
    (is (contains? csproj-labels "Acme.Panels"))
    (is (contains? csproj-labels "nuget:Dapper"))
    (is (contains? csproj-labels "../Contracts/Contracts.csproj"))
    (is (contains? csproj-labels "net8.0"))
    (is (= {:requires 1 :references 1 :uses 1}
           (select-keys (frequencies (map :relation (:edges csproj-result)))
                        [:requires :references :uses])))
    (is (contains? sln-labels "src/dotnet/Panels.sln"))
    (is (contains? sln-labels "Panels Panels.csproj"))
    (is (= 1 (get (frequencies (map :relation (:edges sln-result))) :defines 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/ruby/Gemfile"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/ruby/panels.gemspec"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/flutter/pubspec.yaml"))))
    (is (contains? gemfile-labels "src/ruby/Gemfile"))
    (is (contains? gemfile-labels "rubygems:rails"))
    (is (contains? gemfile-labels "rubygems:graphql"))
    (is (= 2 (get (frequencies (map :relation (:edges gemfile-result))) :requires 0)))
    (is (contains? gemspec-labels "panels"))
    (is (contains? gemspec-labels "rubygems:faraday"))
    (is (contains? gemspec-labels "rubygems:rspec"))
    (is (= 2 (get (frequencies (map :relation (:edges gemspec-result))) :requires 0)))
    (is (contains? pubspec-labels "panels_flutter"))
    (is (contains? pubspec-labels "pub:flutter"))
    (is (contains? pubspec-labels "pub:http"))
    (is (contains? pubspec-labels "pub:flutter_test"))
    (is (= 3 (get (frequencies (map :relation (:edges pubspec-result))) :requires 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/scala/build.sbt"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/elixir/mix.exs"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/erlang/rebar.config"))))
    (is (contains? sbt-labels "panels-scala"))
    (is (contains? sbt-labels "maven:org.typelevel:cats-effect"))
    (is (contains? sbt-labels "maven:com.lihaoyi:os-lib"))
    (is (= 2 (get (frequencies (map :relation (:edges sbt-result))) :requires 0)))
    (is (contains? mix-labels "panels"))
    (is (contains? mix-labels "hex:plug"))
    (is (contains? mix-labels "hex:jason"))
    (is (= 2 (get (frequencies (map :relation (:edges mix-result))) :requires 0)))
    (is (contains? rebar-labels "src/erlang/rebar.config"))
    (is (contains? rebar-labels "hex:cowboy"))
    (is (= 1 (get (frequencies (map :relation (:edges rebar-result))) :requires 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/DESCRIPTION"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/NAMESPACE"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/julia/Project.toml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/perl/cpanfile"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/haskell/panels.cabal"))))
    (is (contains? r-description-labels "panels"))
    (is (contains? r-description-labels "cran:dplyr"))
    (is (contains? r-description-labels "cran:jsonlite"))
    (is (contains? r-description-labels "cran:testthat"))
    (is (= 3 (get (frequencies (map :relation (:edges r-description-result))) :requires 0)))
    (is (contains? r-namespace-labels "cran:dplyr"))
    (is (contains? r-namespace-labels "cran:jsonlite"))
    (is (contains? r-namespace-labels "load_panel"))
    (is (contains? julia-project-labels "Panels"))
    (is (contains? julia-project-labels "julia:DataFrames"))
    (is (contains? julia-project-labels "julia:JSON3"))
    (is (= 2 (get (frequencies (map :relation (:edges julia-project-result))) :requires 0)))
    (is (contains? cpanfile-labels "src/perl/cpanfile"))
    (is (contains? cpanfile-labels "cpan:Mojolicious"))
    (is (contains? cpanfile-labels "cpan:JSON::MaybeXS"))
    (is (= 2 (get (frequencies (map :relation (:edges cpanfile-result))) :requires 0)))
    (is (contains? cabal-labels "panels"))
    (is (contains? cabal-labels "hackage:base"))
    (is (contains? cabal-labels "hackage:text"))
    (is (contains? cabal-labels "hackage:aeson"))
    (is (= 3 (get (frequencies (map :relation (:edges cabal-result))) :requires 0)))
    (is (contains? stack-labels "src/haskell/stack.yaml"))
    (is (contains? stack-labels "panels-lib"))
    (is (contains? stack-labels "aeson-extra"))
    (is (= [:manifest-file] (mapv :kind (:chunks pom-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks csproj-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks pubspec-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks sbt-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks cabal-result))))))

(deftest extracts-mobile-app-manifest-facts
  (let [android-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/android/AndroidManifest.xml"))
        plist-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/mobile/ios/Info.plist"))
        podfile-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/ios/Podfile"))
        swiftpm-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/ios/Package.swift"))
        xcode-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/mobile/ios/Panels.xcodeproj/project.pbxproj"))
        expo-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/mobile/expo/app.json"))
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/android/AndroidManifest.xml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/ios/Info.plist"))))
    (is (contains? (labels android-result) "com.example.panels"))
    (is (contains? (labels android-result) "android.permission.INTERNET"))
    (is (contains? (labels android-result) "activity:.MainActivity"))
    (is (contains? (labels android-result) "service:.PanelSyncService"))
    (is (= {:defines 3 :uses 1} (select-keys (relations android-result) [:defines :uses])))
    (is (contains? (labels plist-result) "com.example.panels"))
    (is (contains? (labels plist-result) "Panels"))
    (is (= 2 (get (relations plist-result) :defines 0)))
    (is (contains? (labels podfile-result) "Panels"))
    (is (contains? (labels podfile-result) "cocoapods:Alamofire"))
    (is (contains? (labels podfile-result) "cocoapods:SQLite.swift"))
    (is (= {:defines 1 :requires 2} (select-keys (relations podfile-result)
                                                 [:defines :requires])))
    (is (contains? (labels swiftpm-result) "PanelsKit"))
    (is (contains? (labels swiftpm-result) "PanelsKitTests"))
    (is (contains? (labels swiftpm-result) "swiftpm:https://github.com/apple/swift-collections"))
    (is (= {:defines 2 :requires 1} (select-keys (relations swiftpm-result)
                                                 [:defines :requires])))
    (is (contains? (labels xcode-result) "Panels"))
    (is (contains? (labels xcode-result)
                   "swiftpm:https://github.com/pointfreeco/swift-composable-architecture"))
    (is (= {:defines 1 :requires 1} (select-keys (relations xcode-result)
                                                 [:defines :requires])))
    (is (contains? (labels expo-result) "Panels"))
    (is (contains? (labels expo-result) "com.example.panels"))
    (is (contains? (labels expo-result) "expo-router"))
    (is (contains? (labels expo-result) "expo-build-properties"))
    (is (= {:defines 2 :uses 2} (select-keys (relations expo-result)
                                             [:defines :uses])))
    (is (= [:manifest-file] (mapv :kind (:chunks android-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks expo-result))))))

(deftest extracts-mobile-resource-and-apple-config-facts
  (let [values-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/values/strings.xml"))
        layout-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/layout/activity_panel.xml"))
        navigation-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/mobile/android/res/navigation/panel_graph.xml"))
        entitlements-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/mobile/ios/Panels.entitlements"))
        xcconfig-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/mobile/ios/Debug.xcconfig"))
        labels (fn [result] (set (map :label (:nodes result))))
        relation-targets (fn [result relation]
                           (set (map :target-id
                                     (filter #(= relation (:relation %))
                                             (:edges result)))))]
    (is (= :xml (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/values/strings.xml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/ios/Panels.entitlements"))))
    (is (= :apple-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                "test/fixtures/extractor-repo/mobile/ios/Debug.xcconfig"))))
    (is (contains? (labels values-result) "string#app_name"))
    (is (contains? (labels values-result) "color#brand"))
    (is (contains? (labels layout-result) "LinearLayout#@+id/panel_root"))
    (is (contains? (labels layout-result) "TextView#@+id/title"))
    (is (contains? (labels layout-result) "fragment#@+id/nav_host"))
    (is (contains? (relation-targets layout-result :references)
                   (extract/node-id :xml-reference "@string/app_name")))
    (is (contains? (relation-targets layout-result :references)
                   (extract/node-id :xml-reference "@navigation/panel_graph")))
    (is (contains? (labels navigation-result) "navigation#@+id/panel_graph"))
    (is (contains? (labels navigation-result) "fragment#@+id/panelList"))
    (is (contains? (relation-targets navigation-result :references)
                   (extract/node-id :xml-reference "@id/panelList")))
    (is (contains? (labels entitlements-result) "aps-environment=development"))
    (is (contains? (labels entitlements-result)
                   "com.apple.security.application-groups=group.com.example.panels"))
    (is (= 2 (get (frequencies (map :relation (:edges entitlements-result)))
                  :defines
                  0)))
    (is (contains? (labels xcconfig-result) "PRODUCT_BUNDLE_IDENTIFIER=com.example.panels"))
    (is (contains? (labels xcconfig-result) "SWIFT_VERSION=5.9"))
    (is (contains? (labels xcconfig-result) "CODE_SIGN_ENTITLEMENTS=Panels.entitlements"))
    (is (= 3 (get (frequencies (map :relation (:edges xcconfig-result)))
                  :defines
                  0)))
    (is (= [:xml-file] (mapv :kind (:chunks values-result))))
    (is (= [:apple-config-file] (mapv :kind (:chunks xcconfig-result))))))

(deftest extracts-prisma-schema-facts
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/db/schema.prisma")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :prisma (:kind file)))
    (is (contains? labels "db/schema.prisma"))
    (is (contains? labels "db"))
    (is (contains? labels "client"))
    (is (contains? labels "User"))
    (is (contains? labels "Panel"))
    (is (contains? labels "PanelStatus"))
    (is (= 1 (:prisma-file kinds)))
    (is (= 1 (:prisma-datasource kinds)))
    (is (= 1 (:prisma-generator kinds)))
    (is (= 2 (:prisma-model kinds)))
    (is (= 1 (:prisma-enum kinds)))
    (is (= 5 (get relations :defines 0)))
    (is (contains? reference-targets
                   (extract/node-id :prisma-model "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :prisma-model "User")))
    (is (contains? reference-targets
                   (extract/node-id :prisma-enum "PanelStatus")))
    (is (= :code-definition (get-in chunks-by-label ["Panel" :kind])))
    (is (= :prisma-model (get-in chunks-by-label ["Panel" :definition-kind])))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.prisma"
                                      :path "broken.prisma"
                                      :kind :prisma
                                      :content "model Broken {\n  id String @id\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))

(deftest extracts-db-and-codegen-config-facts
  (let [drizzle-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/db/drizzle.config.ts"))
        flyway-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/db/flyway.conf"))
        liquibase-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/db/liquibase.properties"))
        codegen-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/codegen/graphql-codegen.yml"))
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :db-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/db/drizzle.config.ts"))))
    (is (= :codegen-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                  "test/fixtures/extractor-repo/codegen/graphql-codegen.yml"))))
    (is (contains? (labels drizzle-result) "db/drizzle.config.ts"))
    (is (contains? (labels drizzle-result) "schema=./src/db/schema.ts"))
    (is (contains? (labels drizzle-result) "out=./drizzle"))
    (is (contains? (labels drizzle-result) "dialect=postgresql"))
    (is (contains? (labels drizzle-result) "drizzle-kit"))
    (is (= 3 (get (relations drizzle-result) :defines 0)))
    (is (= 1 (get (relations drizzle-result) :references 0)))
    (is (contains? (labels flyway-result)
                   "flyway.locations=filesystem:db/migration"))
    (is (contains? (labels flyway-result) "flyway.schemas=public"))
    (is (contains? (labels liquibase-result) "changeLogFile=db/changelog.xml"))
    (is (contains? (labels liquibase-result) "defaultSchemaName=public"))
    (is (contains? (labels codegen-result)
                   "schema=./contracts/panels.graphql"))
    (is (contains? (labels codegen-result) "documents=./src/**/*.graphql"))
    (is (contains? (labels codegen-result) "generates"))
    (is (= [:db-config-file] (mapv :kind (:chunks flyway-result))))
    (is (= [:codegen-config-file] (mapv :kind (:chunks codegen-result))))))

(deftest extracts-dbt-project-config-and-sql-references
  (let [project-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/dbt/dbt_project.yml"))
        sql-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/dbt/panel_orders.sql"))
        project-labels (set (map :label (:nodes project-result)))
        project-kinds (frequencies (map :kind (:nodes project-result)))
        project-relations (frequencies (map :relation (:edges project-result)))
        sql-labels (set (map :label (:nodes sql-result)))
        sql-kinds (frequencies (map :kind (:nodes sql-result)))
        sql-relations (frequencies (map :relation (:edges sql-result)))]
    (is (= :dbt (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/dbt/dbt_project.yml"))))
    (is (= :sql (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/dbt/panel_orders.sql"))))
    (is (contains? project-labels "panels"))
    (is (contains? project-labels "panels_profile"))
    (is (contains? project-labels "version=1.0"))
    (is (contains? project-labels "config-version=2"))
    (is (contains? project-labels "model-paths=models"))
    (is (contains? project-labels "seed-paths=seeds"))
    (is (contains? project-labels "snapshot-paths=snapshots"))
    (is (contains? project-labels "macro-paths=macros"))
    (is (contains? project-labels "test-paths=tests"))
    (is (= 1 (:dbt-project project-kinds)))
    (is (= 1 (:dbt-profile project-kinds)))
    (is (= 5 (:dbt-path project-kinds)))
    (is (= 8 (get project-relations :defines 0)))
    (is (= 1 (get project-relations :references 0)))
    (is (= [:dbt-file] (mapv :kind (:chunks project-result))))
    (is (empty? (:diagnostics project-result)))
    (is (contains? sql-labels "dbt/panel_orders.sql"))
    (is (contains? sql-labels "panels"))
    (is (contains? sql-labels "billing.orders"))
    (is (= 1 (:dbt-sql-file sql-kinds)))
    (is (= 1 (:dbt-model sql-kinds)))
    (is (= 1 (:dbt-source sql-kinds)))
    (is (= 2 (get sql-relations :references 0)))
    (is (= [:sql-file] (mapv :kind (:chunks sql-result)))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:dbt_project.yml"
                                      :path "dbt_project.yml"
                                      :kind :dbt
                                      :content "profile: missing_name\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "top-level name"))))

(deftest extracts-format-support-tranche-facts
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
        chunk-kinds (fn [result] (set (map :kind (:chunks result))))
        notebook (result-for "notebooks/panel_analysis.ipynb")
        devcontainer (result-for ".devcontainer/devcontainer.json")
        kustomize (result-for "infra/kustomize/kustomization.yaml")
        pre-commit (result-for ".pre-commit-config.yaml")
        codeowners (result-for ".github/CODEOWNERS")
        taskfile (result-for "tasks/Taskfile.yml")
        justfile (result-for "tasks/justfile")
        starlark (result-for "build/rules/panels.bzl")
        tool-versions (result-for ".tool-versions")
        node-version (result-for ".node-version")
        python-version (result-for ".python-version")
        ruby-version (result-for ".ruby-version")
        mise (result-for "mise.toml")]
    (is (= :notebook (kind-for "notebooks/panel_analysis.ipynb")))
    (is (= :devcontainer (kind-for ".devcontainer/devcontainer.json")))
    (is (= :kustomize (kind-for "infra/kustomize/kustomization.yaml")))
    (is (= :pre-commit-config (kind-for ".pre-commit-config.yaml")))
    (is (= :codeowners (kind-for ".github/CODEOWNERS")))
    (is (= :task-runner (kind-for "tasks/Taskfile.yml")))
    (is (= :task-runner (kind-for "tasks/justfile")))
    (is (= :starlark (kind-for "build/rules/panels.bzl")))
    (is (= :tool-version-config (kind-for ".tool-versions")))
    (is (= :tool-version-config (kind-for ".node-version")))
    (is (= :tool-version-config (kind-for ".python-version")))
    (is (= :tool-version-config (kind-for ".ruby-version")))
    (is (= :tool-version-config (kind-for "mise.toml")))

    (is (contains? (labels notebook) "python3"))
    (is (contains? (labels notebook) "python"))
    (is (= 2 (:notebook-cell (kinds notebook))))
    (is (= #{:notebook-markdown-cell :notebook-code-cell}
           (chunk-kinds notebook)))

    (is (contains? (labels devcontainer)
                   "mcr.microsoft.com/devcontainers/base:ubuntu"))
    (is (contains? (labels devcontainer)
                   "ghcr.io/devcontainers/features/node:1"))
    (is (contains? (labels devcontainer) "3000"))
    (is (contains? (labels devcontainer) "postCreateCommand=bb test"))

    (is (contains? (labels kustomize) "resources=../k8s/deployment.yaml"))
    (is (contains? (labels kustomize)
                   "components=../components/observability"))
    (is (contains? (labels kustomize) "ghcr.io/acme/panels-web"))
    (is (contains? (labels kustomize) "configMapGenerator=panels-config"))

    (is (contains? (labels pre-commit)
                   "https://github.com/pre-commit/pre-commit-hooks"))
    (is (contains? (labels pre-commit) "v4.6.0"))
    (is (contains? (labels pre-commit) "trailing-whitespace"))

    (is (contains? (labels codeowners) "/frontend/"))
    (is (contains? (labels codeowners) "@acme/web"))
    (is (= 4 (:assigns (frequencies (map :relation (:edges codeowners))))))

    (is (contains? (labels taskfile) "build"))
    (is (contains? (labels taskfile) "build->lint"))
    (is (contains? (labels taskfile) "build:bb test"))
    (is (contains? (labels justfile) "test"))
    (is (contains? (labels justfile) "test->lint"))
    (is (contains? (labels justfile) "lint:bb lint"))

    (is (contains? (labels starlark) "@rules_java//java:defs.bzl"))
    (is (contains? (labels starlark) "@rules_java//java:defs.bzl:java_library"))
    (is (contains? (labels starlark) "panel_library"))
    (is (contains? (labels starlark) "panel_rule"))

    (is (contains? (labels tool-versions) "nodejs@20.11.1"))
    (is (contains? (labels tool-versions) "python@3.12.2"))
    (is (contains? (labels node-version) "node@20.11.1"))
    (is (contains? (labels python-version) "python@3.12.2"))
    (is (contains? (labels ruby-version) "ruby@3.3.0"))
    (is (contains? (labels mise) "node@20.11.1"))
    (is (contains? (labels mise) "PANEL_ENV=dev"))
    (is (contains? (labels mise) "build"))))

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
    (is (= 1 (:db-migration flyway-kinds)))
    (is (= 1 (:db-migration-version flyway-kinds)))
    (is (= 1 (:table flyway-kinds)))
    (is (= 1 (:view flyway-kinds)))
    (is (= 3 (get flyway-relations :defines 0)))
    (is (= [:db-migration-file] (mapv :kind (:chunks flyway-result))))
    (is (contains? liquibase-labels "db/changelog/db.changelog-master.yaml"))
    (is (contains? liquibase-labels "create-panel-audit"))
    (is (contains? liquibase-labels "panel_audit"))
    (is (contains? liquibase-labels "idx_panel_audit_panel_id"))
    (is (contains? liquibase-labels "fk_panel_audit_panel"))
    (is (contains? liquibase-labels "db/changelog/extra.yaml"))
    (is (= 1 (:db-migration liquibase-kinds)))
    (is (= 1 (:db-changeset liquibase-kinds)))
    (is (= 2 (:table liquibase-kinds)))
    (is (= 1 (:index liquibase-kinds)))
    (is (= 1 (:constraint liquibase-kinds)))
    (is (= 5 (get liquibase-relations :defines 0)))
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
        ansible-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/ops/playbook.yaml"))
        nginx-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ops/nginx.conf"))
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
                                              "test/fixtures/extractor-repo/ops/playbook.yaml"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/nginx.conf"))))
    (is (= :ops-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/ops/panels.service"))))
    (is (contains? (labels cloudformation-result) "PanelBucket"))
    (is (contains? (labels cloudformation-result) "PanelFunction"))
    (is (contains? (labels cloudformation-result) "AWS::S3::Bucket"))
    (is (= 2 (:cloudformation-resource (kinds cloudformation-result))))
    (is (= 2 (:cloudformation-resource-type (kinds cloudformation-result))))
    (is (= 4 (get (relations cloudformation-result) :defines 0)))
    (is (= 1 (get (relations cloudformation-result) :references 0)))
    (is (contains? (labels pulumi-result) "panels-infra"))
    (is (contains? (labels pulumi-result) "nodejs"))
    (is (= 1 (:pulumi-project (kinds pulumi-result))))
    (is (= 1 (:pulumi-runtime (kinds pulumi-result))))
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
    (is (contains? (labels systemd-result) "panels.service"))
    (is (contains? (labels systemd-result) "/usr/bin/panels-worker"))
    (is (contains? (labels systemd-result) "network.target"))
    (is (= 1 (:systemd-unit (kinds systemd-result))))
    (is (= 3 (:systemd-section (kinds systemd-result))))
    (is (= 1 (:systemd-command (kinds systemd-result))))
    (is (= 2 (:systemd-target (kinds systemd-result))))
    (is (= [:ops-config-file] (mapv :kind (:chunks cloudformation-result))))
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
        storybook-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/.storybook/main.ts"))
        editorconfig-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/.editorconfig"))
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
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/vite.config.ts"))))
    (is (= :test-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/tooling/pytest.ini"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/.github/dependabot.yml"))))
    (is (= :tool-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                               "test/fixtures/extractor-repo/.storybook/main.ts"))))
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
    (is (contains? (labels storybook-result) "stories"))
    (is (contains? (labels editorconfig-result) "indent_style=space"))
    (is (pos? (get (relations jest-result) :defines 0)))
    (is (pos? (get (relations playwright-result) :references 0)))
    (is (= [:test-config-file] (mapv :kind (:chunks jest-result))))
    (is (= [:test-config-file] (mapv :kind (:chunks pytest-result))))
    (is (= [:tool-config-file] (mapv :kind (:chunks tsconfig-result))))
    (is (= [:tool-config-file] (mapv :kind (:chunks prettier-result))))))

(deftest extracts-compose-helm-and-yaml-infra-facts
  (let [compose-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/infra/docker-compose.yml"))
        helm-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/infra/chart/Chart.yaml"))
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
    (is (contains? (labels compose-result) "web"))
    (is (contains? (labels compose-result) "worker"))
    (is (contains? (labels compose-result) "ghcr.io/acme/panels-web:latest"))
    (is (contains? (labels compose-result) "./web"))
    (is (= 2 (:defines (relations compose-result))))
    (is (= 3 (:uses (relations compose-result))))
    (is (contains? (labels helm-result) "panels"))
    (is (contains? (labels helm-result) "0.1.0"))
    (is (= 1 (:helm-chart (kinds helm-result))))
    (is (= 1 (:helm-chart-version (kinds helm-result))))
    (is (= 2 (:defines (relations helm-result))))
    (is (contains? (labels yaml-result) "Deployment/panels-web"))
    (is (contains? (labels yaml-result) "Service/panels-web"))
    (is (= 2 (:k8s-resource (kinds yaml-result))))
    (is (= 2 (:defines (relations yaml-result))))
    (is (= [:compose-file] (mapv :kind (:chunks compose-result))))
    (is (= [:helm-file] (mapv :kind (:chunks helm-result))))
    (is (= [:yaml-file] (mapv :kind (:chunks yaml-result))))))

(deftest extracts-package-and-workspace-manifest-facts
  (let [package-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/workspace/package.json"))
        deno-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/deno.json"))
        composer-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/workspace/composer.json"))
        go-work-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/workspace/go.work"))
        pnpm-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/pnpm-workspace.yaml"))
        turbo-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/workspace/turbo.json"))
        nx-result (extract/extract-file
                   "run/test"
                   (fs/file-record "test/fixtures/extractor-repo"
                                   "test/fixtures/extractor-repo/workspace/nx.json"))
        lerna-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/workspace/lerna.json"))
        rush-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/rush.json"))
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/package.json"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/composer.json"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/go.work"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/pnpm-workspace.yaml"))))
    (is (contains? (labels package-result) "@acme/panels"))
    (is (contains? (labels package-result) "npm:@apollo/client"))
    (is (contains? (labels package-result) "npm:react"))
    (is (contains? (labels package-result) "npm:typescript"))
    (is (contains? (labels package-result) "build"))
    (is (contains? (labels package-result) "test"))
    (is (contains? (labels package-result) "apps/*"))
    (is (contains? (labels package-result) "packages/*"))
    (is (contains? (labels package-result) "pnpm@9.0.0"))
    (is (= {:defines 2 :references 2 :requires 3 :uses 1}
           (select-keys (relations package-result)
                        [:defines :references :requires :uses])))
    (is (contains? (labels deno-result) "deno:@std/path"))
    (is (contains? (labels deno-result) "dev"))
    (is (= {:defines 1 :requires 1}
           (select-keys (relations deno-result) [:defines :requires])))
    (is (contains? (labels composer-result) "acme/panels"))
    (is (contains? (labels composer-result) "composer:php"))
    (is (contains? (labels composer-result) "composer:symfony/console"))
    (is (contains? (labels composer-result) "composer:phpunit/phpunit"))
    (is (= 3 (get (relations composer-result) :requires 0)))
    (is (contains? (labels go-work-result) "./services/api"))
    (is (contains? (labels go-work-result) "./libs/core"))
    (is (= 2 (get (relations go-work-result) :references 0)))
    (is (contains? (labels pnpm-result) "apps/*"))
    (is (contains? (labels pnpm-result) "packages/*"))
    (is (= 2 (get (relations pnpm-result) :references 0)))
    (is (contains? (labels turbo-result) "build"))
    (is (contains? (labels turbo-result) "test"))
    (is (= 2 (get (relations turbo-result) :defines 0)))
    (is (contains? (labels nx-result) "build"))
    (is (contains? (labels nx-result) "test"))
    (is (contains? (labels nx-result) "web"))
    (is (contains? (labels nx-result) "api"))
    (is (= {:defines 2 :references 2}
           (select-keys (relations nx-result) [:defines :references])))
    (is (contains? (labels lerna-result) "packages/*"))
    (is (contains? (labels lerna-result) "pnpm"))
    (is (contains? (labels rush-result) "@acme/panels-web"))
    (is (contains? (labels rush-result) "9.0.0"))
    (is (= [:manifest-file] (mapv :kind (:chunks package-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks composer-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks go-work-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks turbo-result))))))

(deftest extracts-additional-package-manifest-facts
  (let [cargo-result (extract/extract-file
                      "run/test"
                      {:file-id "file:Cargo.toml"
                       :path "Cargo.toml"
                       :kind :manifest
                       :content "[package]\nname = \"demo\"\n\n[dependencies]\nserde = \"1\"\ntokio = { version = \"1.38\" }\n"})
        go-result (extract/extract-file
                   "run/test"
                   {:file-id "file:go.mod"
                    :path "go.mod"
                    :kind :manifest
                    :content "module example.com/app\n\nrequire (\n  github.com/acme/lib v1.2.3\n)\nrequire golang.org/x/sync v0.7.0\n"})
        pyproject-result (extract/extract-file
                          "run/test"
                          {:file-id "file:pyproject.toml"
                           :path "pyproject.toml"
                           :kind :manifest
                           :content (str "[project]\n"
                                         "name = \"demo-py\"\n"
                                         "dependencies = [\"requests>=2\", \"click\"]\n\n"
                                         "[tool.agraph.import-names]\n"
                                         "requests = [\"requests\"]\n")})
        deps-result (extract/extract-file
                     "run/test"
                     {:file-id "file:deps.edn"
                      :path "deps.edn"
                      :kind :manifest
                      :content "{:deps {org.clojure/data.json {:mvn/version \"2.5.1\"}}}\n"})
        labels (fn [result] (set (map :label (:nodes result))))
        package-node (fn [result label]
                       (some #(when (= label (:label %)) %) (:nodes result)))]
    (is (contains? (labels cargo-result) "cargo:serde"))
    (is (contains? (labels cargo-result) "cargo:tokio"))
    (is (= "serde" (:package-name (package-node cargo-result "cargo:serde"))))
    (is (contains? (labels go-result) "go:github.com/acme/lib"))
    (is (contains? (labels go-result) "go:golang.org/x/sync"))
    (is (contains? (labels pyproject-result) "pypi:requests"))
    (is (contains? (labels pyproject-result) "pypi:click"))
    (is (= ["requests"]
           (:import-names (package-node pyproject-result "pypi:requests"))))
    (is (contains? (labels deps-result) "maven:org.clojure/data.json"))))

(deftest extracts-dependency-lock-version-facts
  (let [package-lock-result (extract/extract-file
                             "run/test"
                             {:file-id "file:package-lock.json"
                              :path "package-lock.json"
                              :kind :dependency-lock
                              :content "{\"packages\":{\"node_modules/react\":{\"version\":\"19.1.0\"},\"node_modules/@playwright/test\":{\"version\":\"1.60.0\"}}}"})
        cargo-lock-result (extract/extract-file
                           "run/test"
                           {:file-id "file:Cargo.lock"
                            :path "Cargo.lock"
                            :kind :dependency-lock
                            :content "[[package]]\nname = \"serde\"\nversion = \"1.0.0\"\n"})
        go-sum-result (extract/extract-file
                       "run/test"
                       {:file-id "file:go.sum"
                        :path "go.sum"
                        :kind :dependency-lock
                        :content "github.com/acme/lib v1.2.3 h1:abc\ngithub.com/acme/lib v1.2.3/go.mod h1:def\n"})
        pnpm-result (extract/extract-file
                     "run/test"
                     {:file-id "file:pnpm-lock.yaml"
                      :path "pnpm-lock.yaml"
                      :kind :dependency-lock
                      :content "packages:\n  react@19.1.0:\n    resolution: {integrity: sha512-demo}\n  '@playwright/test@1.60.0':\n    resolution: {integrity: sha512-demo}\n"})
        yarn-result (extract/extract-file
                     "run/test"
                     {:file-id "file:yarn.lock"
                      :path "yarn.lock"
                      :kind :dependency-lock
                      :content "\"react@^19.0.0\":\n  version \"19.1.0\"\n\"@playwright/test@^1.60.0\":\n  version \"1.60.0\"\n"})
        gemfile-result (extract/extract-file
                        "run/test"
                        {:file-id "file:Gemfile.lock"
                         :path "Gemfile.lock"
                         :kind :dependency-lock
                         :content "GEM\n  specs:\n    rails (7.1.0)\n    rack (3.0.0)\n"})
        poetry-result (extract/extract-file
                       "run/test"
                       {:file-id "file:poetry.lock"
                        :path "poetry.lock"
                        :kind :dependency-lock
                        :content "[[package]]\nname = \"requests\"\nversion = \"2.31.0\"\n"})
        uv-result (extract/extract-file
                   "run/test"
                   {:file-id "file:uv.lock"
                    :path "uv.lock"
                    :kind :dependency-lock
                    :content "[[package]]\nname = \"ruff\"\nversion = \"0.5.0\"\n"})
        pubspec-result (extract/extract-file
                        "run/test"
                        {:file-id "file:pubspec.lock"
                         :path "pubspec.lock"
                         :kind :dependency-lock
                         :content "packages:\n  http:\n    dependency: transitive\n    source: hosted\n    version: \"1.2.0\"\n"})
        composer-result (extract/extract-file
                         "run/test"
                         {:file-id "file:composer.lock"
                          :path "composer.lock"
                          :kind :dependency-lock
                          :content "{\"packages\":[{\"name\":\"symfony/console\",\"version\":\"v7.0.0\"}],\"packages-dev\":[{\"name\":\"phpunit/phpunit\",\"version\":\"11.0.0\"}]}"})
        pipfile-result (extract/extract-file
                        "run/test"
                        {:file-id "file:Pipfile.lock"
                         :path "Pipfile.lock"
                         :kind :dependency-lock
                         :content "{\"default\":{\"flask\":{\"version\":\"==3.0.0\"}},\"develop\":{\"pytest\":{\"version\":\"==8.0.0\"}}}"})
        mix-result (extract/extract-file
                    "run/test"
                    {:file-id "file:mix.lock"
                     :path "mix.lock"
                     :kind :dependency-lock
                     :content "%{\n  \"plug\": {:hex, :plug, \"1.15.3\", \"checksum\", [], \"hexpm\", \"checksum\"}\n}\n"})
        requirements-result (extract/extract-file
                             "run/test"
                             {:file-id "file:requirements.txt"
                              :path "requirements.txt"
                              :kind :dependency-lock
                              :content "django==5.0.0\nrequests[security]==2.31.0 ; python_version >= \"3.11\"\n"})
        bun-result (extract/extract-file
                    "run/test"
                    {:file-id "file:bun.lock"
                     :path "bun.lock"
                     :kind :dependency-lock
                     :content "{\"packages\":{\"react\":\"19.1.0\",\"@types/react\":{\"version\":\"19.0.0\"}}}"})
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (doseq [path ["package-lock.json" "Cargo.lock" "go.sum" "pnpm-lock.yaml"
                  "yarn.lock" "Gemfile.lock" "poetry.lock" "uv.lock"
                  "pubspec.lock" "composer.lock" "Pipfile.lock" "mix.lock"
                  "requirements.txt" "bun.lock"]]
      (is (= :dependency-lock (fs/file-kind path))))
    (is (contains? (labels package-lock-result) "npm:react"))
    (is (contains? (labels package-lock-result) "npm:react@19.1.0"))
    (is (contains? (labels package-lock-result) "npm:@playwright/test@1.60.0"))
    (is (= {:resolves 2 :version-of 2}
           (select-keys (relations package-lock-result) [:resolves :version-of])))
    (is (contains? (labels cargo-lock-result) "cargo:serde@1.0.0"))
    (is (contains? (labels go-sum-result) "go:github.com/acme/lib@v1.2.3"))
    (is (contains? (labels pnpm-result) "npm:react@19.1.0"))
    (is (contains? (labels pnpm-result) "npm:@playwright/test@1.60.0"))
    (is (contains? (labels yarn-result) "npm:react@19.1.0"))
    (is (contains? (labels yarn-result) "npm:@playwright/test@1.60.0"))
    (is (contains? (labels gemfile-result) "rubygems:rails@7.1.0"))
    (is (contains? (labels gemfile-result) "rubygems:rack@3.0.0"))
    (is (contains? (labels poetry-result) "pypi:requests@2.31.0"))
    (is (contains? (labels uv-result) "pypi:ruff@0.5.0"))
    (is (contains? (labels pubspec-result) "pub:http@1.2.0"))
    (is (contains? (labels composer-result) "composer:symfony/console@v7.0.0"))
    (is (contains? (labels composer-result) "composer:phpunit/phpunit@11.0.0"))
    (is (contains? (labels pipfile-result) "pypi:flask@3.0.0"))
    (is (contains? (labels pipfile-result) "pypi:pytest@8.0.0"))
    (is (contains? (labels mix-result) "hex:plug@1.15.3"))
    (is (contains? (labels requirements-result) "pypi:django@5.0.0"))
    (is (contains? (labels requirements-result) "pypi:requests@2.31.0"))
    (is (contains? (labels bun-result) "npm:react@19.1.0"))
    (is (contains? (labels bun-result) "npm:@types/react@19.0.0"))))

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
    (is (contains? labels "PanelSummary"))
    (is (= 1 (:graphql-file kinds)))
    (is (= 3 (:graphql-type kinds)))
    (is (= 1 (:graphql-interface kinds)))
    (is (= 1 (:graphql-input kinds)))
    (is (= 1 (:graphql-fragment kinds)))
    (is (pos? (get relations :defines 0)))
    (is (pos? (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Node")))
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
    (is (contains? labels "acme.panels.v1/PanelService"))
    (is (contains? labels "acme.panels.v1/PanelService.GetPanel"))
    (is (= 1 (:namespace kinds)))
    (is (= 3 (:protobuf-message kinds)))
    (is (= 1 (:protobuf-service kinds)))
    (is (= 1 (:protobuf-rpc kinds)))
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
        png-file (io/file root "hero.png")
        font-file (io/file root "brand.ttf")
        mo-file (io/file root "messages.mo")]
    (java.nio.file.Files/write (.toPath png-file)
                               png-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath font-file)
                               font-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath mo-file)
                               mo-bytes
                               (make-array java.nio.file.OpenOption 0))
    (let [png-record (fs/file-record (.getPath root) (.getPath png-file))
          font-record (fs/file-record (.getPath root) (.getPath font-file))
          mo-record (fs/file-record (.getPath root) (.getPath mo-file))
          png-result (extract/extract-file "run/test" png-record)
          font-result (extract/extract-file "run/test" font-record)
          mo-result (extract/extract-file "run/test" mo-record)]
      (is (= :image-asset (:kind png-record)))
      (is (= :font-asset (:kind font-record)))
      (is (= :gettext-binary (:kind mo-record)))
      (is (= "" (:content png-record)))
      (is (:binary? png-record))
      (is (= (str "sha256:" (hash/sha256-bytes-hex png-bytes))
             (:content-sha png-record)))
      (is (= [:image-asset] (mapv :kind (:nodes png-result))))
      (is (= [:font-asset] (mapv :kind (:nodes font-result))))
      (is (= [:gettext-binary] (mapv :kind (:nodes mo-result))))
      (is (empty? (:chunks png-result)))
      (is (empty? (:chunks font-result)))
      (is (empty? (:chunks mo-result))))))

(deftest detects-license-template-and-shebang-files
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "agraph-text-format-coverage"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        license-file (io/file root "LICENSE")
        vendor-license-file (io/file root "cytoscape.LICENSE")
        template-file (doto (io/file root "demo.rb.template")
                        (io/make-parents))
        script-file (doto (io/file root "tool")
                      (io/make-parents))]
    (spit license-file "MIT\n")
    (spit vendor-license-file "Vendor license\n")
    (spit template-file "class Demo < Formula\nend\n")
    (spit script-file "#!/usr/bin/env bash\nexec demo\n")
    (let [coverage (:files (fs/scan-file-coverage (.getPath root)))
          kind-by-path (into {} (map (juxt :path :kind)) coverage)
          script-record (fs/file-record (.getPath root) (.getPath script-file))
          template-result (extract/extract-file
                           "run/test"
                           (fs/file-record (.getPath root)
                                           (.getPath template-file)))
          script-result (extract/extract-file "run/test" script-record)]
      (is (= :doc (fs/file-kind "LICENSE")))
      (is (= :doc (fs/file-kind "cytoscape.LICENSE")))
      (is (= :ruby (fs/file-kind "demo.rb.template")))
      (is (= :shell (:kind script-record)))
      (is (= {"LICENSE" :doc
              "cytoscape.LICENSE" :doc
              "demo.rb.template" :ruby
              "tool" :shell}
             kind-by-path))
      (is (contains? (set (map :label (:nodes template-result))) "demo"))
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
    (is (contains? labels "src.python.app"))
    (is (contains? labels "src.python.app/Service"))
    (is (contains? labels "src.python.app/Service.fetch"))
    (is (contains? labels "src.python.app/build"))
    (is (contains? labels "src.python.app/main"))
    (is (= 4 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.python.local")))
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
    (is (= [:typescript-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-style-as-searchable-source-chunk
  (let [style-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/sample-repo"
                                      "test/fixtures/sample-repo/src/web/theme.scss"))]
    (is (= [:style-file] (mapv :kind (:chunks style-result))))
    (is (empty? (:nodes style-result)))
    (is (empty? (:edges style-result)))))

(deftest extracts-shell-as-searchable-source-chunk
  (let [shell-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/scripts/setup.sh"))]
    (is (= [:shell-file] (mapv :kind (:chunks shell-result))))
    (is (empty? (:nodes shell-result)))
    (is (empty? (:edges shell-result)))))

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
        github-labels (set (map :label (:nodes github-result)))
        github-kinds (frequencies (map :kind (:nodes github-result)))
        github-relations (frequencies (map :relation (:edges github-result)))
        github-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks github-result))
        gitlab-labels (set (map :label (:nodes gitlab-result)))
        gitlab-kinds (frequencies (map :kind (:nodes gitlab-result)))
        gitlab-relations (frequencies (map :relation (:edges gitlab-result)))
        gitlab-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks gitlab-result))]
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml"))))
    (is (contains? github-labels "CI"))
    (is (contains? github-labels "test"))
    (is (contains? github-labels "deploy"))
    (is (contains? github-labels "ubuntu-latest"))
    (is (contains? github-labels "ghcr.io/acme/deploy:latest"))
    (is (contains? github-labels "test:TEST_DB"))
    (is (contains? github-labels "apps/web"))
    (is (= 1 (:ci-runner github-kinds)))
    (is (= 1 (:container-image github-kinds)))
    (is (= 1 (:ci-env-var github-kinds)))
    (is (= 1 (:ci-working-directory github-kinds)))
    (is (= 2 (get github-relations :defines 0)))
    (is (= 1 (get github-relations :requires 0)))
    (is (= 5 (get github-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text github-command-chunks))))
    (is (contains? gitlab-labels "ci/.gitlab-ci.yml"))
    (is (contains? gitlab-labels "test"))
    (is (contains? gitlab-labels "deploy"))
    (is (contains? gitlab-labels "clojure:tools-deps"))
    (is (contains? gitlab-labels "test:TEST_DB"))
    (is (= 1 (:container-image gitlab-kinds)))
    (is (= 1 (:ci-env-var gitlab-kinds)))
    (is (= 2 (get gitlab-relations :defines 0)))
    (is (= 1 (get gitlab-relations :requires 0)))
    (is (= 2 (get gitlab-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text gitlab-command-chunks))))))

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
        make-labels (set (map :label (:nodes make-result)))
        cmake-labels (set (map :label (:nodes cmake-result)))
        cmake-module-labels (set (map :label (:nodes cmake-module-result)))
        bazel-labels (set (map :label (:nodes bazel-result)))
        buck-labels (set (map :label (:nodes buck-result)))
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
    (is (contains? (requires-targets bazel-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/BUCK"))))
    (is (contains? buck-labels "build/BUCK"))
    (is (contains? buck-labels "core"))
    (is (contains? buck-labels "app"))
    (is (contains? (requires-targets buck-result)
                   (extract/node-id :build-target "core")))))

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
    (is (= 1 (:terraform-file kinds)))
    (is (= 2 (:terraform-resource kinds)))
    (is (= 1 (:terraform-module kinds)))
    (is (= 1 (:terraform-provider kinds)))
    (is (pos? (:defines relations)))
    (is (pos? (:references relations)))
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
    (is (contains? labels "/panels"))
    (is (contains? labels "/panels/{id}"))
    (is (contains? labels "GET /panels"))
    (is (contains? labels "POST /panels"))
    (is (contains? labels "GET /panels/{id}"))
    (is (contains? labels "Panel"))
    (is (contains? labels "PanelCreate"))
    (is (= 1 (:api-spec kinds)))
    (is (= 2 (:api-path kinds)))
    (is (= 3 (:api-operation kinds)))
    (is (= 2 (:api-schema kinds)))
    (is (= 7 (:defines relations)))
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
    (is (= 1 (:asyncapi-spec kinds)))
    (is (= 1 (:asyncapi-channel kinds)))
    (is (= 1 (:asyncapi-operation kinds)))
    (is (= 1 (:asyncapi-message kinds)))
    (is (= 1 (:asyncapi-schema kinds)))
    (is (= 4 (:defines relations)))
    (is (= 1 (:references relations)))
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
