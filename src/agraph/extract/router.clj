(ns agraph.extract.router
  (:require [agraph.extract.api-schema :as extract.api-schema]
            [agraph.extract.assets :as extract.assets]
            [agraph.extract.assets-text :as extract.assets-text]
            [agraph.extract.avro :as extract.avro]
            [agraph.extract.build :as extract.build]
            [agraph.extract.ci :as extract.ci]
            [agraph.extract.codeowners :as extract.codeowners]
            [agraph.extract.compose :as extract.compose]
            [agraph.extract.config-generic :as extract.config-generic]
            [agraph.extract.data-model :as extract.data-model]
            [agraph.extract.data-science :as extract.data-science]
            [agraph.extract.database :as extract.database]
            [agraph.extract.db-config :as extract.db-config]
            [agraph.extract.devcontainer :as extract.devcontainer]
            [agraph.extract.doc :as extract.doc]
            [agraph.extract.docs-config :as extract.docs-config]
            [agraph.extract.governance :as extract.governance]
            [agraph.extract.graphql :as extract.graphql]
            [agraph.extract.infra :as extract.infra]
            [agraph.extract.notebook :as extract.notebook]
            [agraph.extract.observability :as extract.observability]
            [agraph.extract.ops-config :as extract.ops-config]
            [agraph.extract.package :as extract.package]
            [agraph.extract.project-config :as extract.project-config]
            [agraph.extract.protobuf :as extract.protobuf]
            [agraph.extract.runtime :as extract.runtime]
            [agraph.extract.sbom :as extract.sbom]
            [agraph.extract.source-beam :as extract.source-beam]
            [agraph.extract.source-basic :as extract.source-basic]
            [agraph.extract.source-clojure :as extract.source-clojure]
            [agraph.extract.source-dart :as extract.source-dart]
            [agraph.extract.source-dotnet :as extract.source-dotnet]
            [agraph.extract.source-dotnet-worker :as extract.source-dotnet-worker]
            [agraph.extract.source-jvm :as extract.source-jvm]
            [agraph.extract.source-jvm-worker :as extract.source-jvm-worker]
            [agraph.extract.source-js :as extract.source-js]
            [agraph.extract.source-ml :as extract.source-ml]
            [agraph.extract.source-native :as extract.source-native]
            [agraph.extract.source-python :as extract.source-python]
            [agraph.extract.source-scala :as extract.source-scala]
            [agraph.extract.source-scripting :as extract.source-scripting]
            [agraph.extract.source-systems :as extract.source-systems]
            [agraph.extract.starlark :as extract.starlark]
            [agraph.extract.task-config :as extract.task-config]
            [agraph.extract.terraform :as extract.terraform]
            [agraph.extract.text :as extract.text]
            [agraph.extract.tool-config :as extract.tool-config]
            [agraph.extract.web-framework-entry :as extract.web-framework-entry]
            [agraph.extract.web-source :as extract.web-source]
            [agraph.extract.workflow :as extract.workflow]
            [agraph.extract.xml :as extract.xml]
            [agraph.extract.yaml-config :as extract.yaml-config]
            [agraph.extract.yaml-generic :as extract.yaml-generic]))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file {:keys [parser-worker-enabled? parser-worker-facts]
                :or {parser-worker-enabled? (constantly false)
                     parser-worker-facts (constantly nil)}}]
  (case (:kind file)
    :code (extract.source-clojure/extract-code run-id file)
    :go (extract.source-basic/extract-go run-id file)
    :java (if (parser-worker-enabled? :java)
            (extract.source-jvm-worker/extract-java-worker
             run-id
             file
             (parser-worker-facts file))
            (extract.source-jvm/extract-java run-id file))
    :groovy (extract.source-jvm/extract-groovy run-id file)
    :kotlin (extract.source-jvm/extract-kotlin run-id file)
    :swift (extract.source-jvm/extract-swift run-id file)
    :dotnet (if (parser-worker-enabled? :dotnet)
              (extract.source-dotnet-worker/extract-dotnet-worker
               run-id
               file
               (parser-worker-facts file))
              (extract.source-dotnet/extract-dotnet run-id file))
    :ruby (extract.source-native/extract-ruby run-id file)
    :cpp (extract.source-native/extract-cpp run-id file)
    :objective-c (extract.source-native/extract-objective-c run-id file)
    :dart (extract.source-dart/extract-dart run-id file)
    :scala (extract.source-scala/extract-scala run-id file)
    :elixir (extract.source-beam/extract-elixir run-id file)
    :erlang (extract.source-beam/extract-erlang run-id file)
    :lua (extract.source-scripting/extract-lua run-id file)
    :r (extract.source-scripting/extract-r run-id file)
    :julia (extract.source-scripting/extract-julia run-id file)
    :ocaml (extract.source-ml/extract-ocaml run-id file)
    :perl (extract.source-ml/extract-perl run-id file)
    :haskell (extract.source-ml/extract-haskell run-id file)
    :odin (extract.source-systems/extract-odin run-id file)
    :zig (extract.source-systems/extract-zig run-id file)
    :apple-config (extract.xml/extract-apple-config run-id file)
    :prisma (extract.data-model/extract-prisma run-id file)
    :dbt (extract.data-model/extract-dbt run-id file)
    :data-science (extract.data-science/extract-data-science run-id file)
    :db-config (extract.db-config/extract-db-config run-id file)
    :codegen-config (extract.config-generic/extract-codegen-config run-id file)
    :test-config (extract.config-generic/extract-test-config run-id file)
    :quality-config (extract.tool-config/extract-quality-config run-id file)
    :editor-config (extract.project-config/extract-editor-config run-id file)
    :release-config (extract.project-config/extract-release-config run-id file)
    :web-framework (extract.web-framework-entry/extract-web-framework run-id file)
    :workflow-orchestration (extract.workflow/extract-workflow-orchestration run-id file)
    :observability-config (extract.observability/extract-observability-config run-id file)
    :tool-config (extract.tool-config/extract-tool-config run-id file)
    :ops-config (extract.ops-config/extract-ops-config run-id file)
    :astro (extract.web-source/extract-astro run-id file)
    :php (extract.web-source/extract-php run-id file)
    :notebook (extract.notebook/extract-notebook run-id file)
    :devcontainer (extract.devcontainer/extract-devcontainer run-id file)
    :kustomize (extract.yaml-config/extract-kustomize run-id file)
    :pre-commit-config (extract.yaml-config/extract-pre-commit-config run-id file)
    :codeowners (extract.codeowners/extract-codeowners run-id file)
    :task-runner (extract.task-config/extract-task-runner run-id file)
    :starlark (extract.starlark/extract-starlark run-id file)
    :tool-version-config (extract.task-config/extract-tool-version-config run-id file)
    :storybook (extract.docs-config/extract-storybook run-id file)
    :docs-config (extract.docs-config/extract-docs-config run-id file)
    :governance (extract.governance/extract-governance run-id file)
    :sbom (extract.sbom/extract-sbom run-id file)
    :vue (extract.web-source/extract-sfc run-id file)
    :svelte (extract.web-source/extract-sfc run-id file)
    :ci (extract.ci/extract-ci run-id file)
    :build (extract.build/extract-build run-id file)
    :javascript (extract.source-js/extract-js-family run-id file)
    :typescript (extract.source-js/extract-js-family run-id file)
    :python (extract.source-python/extract-python run-id file)
    :rust (extract.source-basic/extract-rust run-id file)
    :style (extract.text/extract-style run-id file)
    :shell (extract.text/extract-shell run-id file)
    :sql (extract.database/extract-sql run-id file)
    :db-migration (extract.database/extract-db-migration run-id file)
    :terraform (extract.terraform/extract-terraform run-id file)
    :openapi (extract.api-schema/extract-openapi run-id file)
    :asyncapi (extract.api-schema/extract-asyncapi run-id file)
    :json-schema (extract.api-schema/extract-json-schema run-id file)
    :avro (extract.avro/extract-avro run-id file)
    :graphql (extract.graphql/extract-graphql run-id file)
    :protobuf (extract.protobuf/extract-protobuf run-id file)
    :yaml (extract.yaml-generic/extract-yaml run-id file)
    :docker (extract.runtime/extract-docker run-id file)
    :procfile (extract.runtime/extract-procfile run-id file)
    :compose (extract.compose/extract-compose run-id file)
    :helm (extract.infra/extract-helm run-id file)
    :gettext (extract.assets-text/extract-gettext run-id file)
    :svg (extract.assets-text/extract-svg run-id file)
    :xml (extract.xml/extract-xml run-id file)
    :html (extract.text/extract-text-source run-id file :html-file)
    :env (extract.text/extract-env run-id file)
    :text (extract.text/extract-text-source run-id file :text-file)
    :unknown (extract.text/extract-text-source run-id file :unknown-file)
    (:archive-asset :compiled-artifact :font-asset :gettext-binary :image-asset :media-asset :opaque-asset :secret-material)
    (extract.assets/extract-binary-asset run-id file)
    :doc (extract.doc/extract-doc run-id file)
    :edn (extract.text/extract-edn run-id file)
    :config (extract.text/extract-edn run-id file)
    :manifest (extract.package/extract-manifest run-id file)
    :dependency-lock (extract.package/extract-dependency-lock run-id file)
    {}))
