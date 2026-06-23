(ns ygg.extract.router
  (:require [ygg.extract.api-schema :as extract.api-schema]
            [ygg.extract.assets :as extract.assets]
            [ygg.extract.assets-text :as extract.assets-text]
            [ygg.extract.avro :as extract.avro]
            [ygg.extract.build :as extract.build]
            [ygg.extract.ci :as extract.ci]
            [ygg.extract.codeowners :as extract.codeowners]
            [ygg.extract.compose :as extract.compose]
            [ygg.extract.config-generic :as extract.config-generic]
            [ygg.extract.data-model :as extract.data-model]
            [ygg.extract.data-science :as extract.data-science]
            [ygg.extract.database :as extract.database]
            [ygg.extract.db-config :as extract.db-config]
            [ygg.extract.devcontainer :as extract.devcontainer]
            [ygg.extract.doc :as extract.doc]
            [ygg.extract.docs-config :as extract.docs-config]
            [ygg.extract.governance :as extract.governance]
            [ygg.extract.graphql :as extract.graphql]
            [ygg.extract.infra :as extract.infra]
            [ygg.extract.notebook :as extract.notebook]
            [ygg.extract.observability :as extract.observability]
            [ygg.extract.ops-config :as extract.ops-config]
            [ygg.extract.package :as extract.package]
            [ygg.extract.project-config :as extract.project-config]
            [ygg.extract.protobuf :as extract.protobuf]
            [ygg.extract.runtime :as extract.runtime]
            [ygg.extract.sbom :as extract.sbom]
            [ygg.extract.source-beam :as extract.source-beam]
            [ygg.extract.source-basic :as extract.source-basic]
            [ygg.extract.source-clojure :as extract.source-clojure]
            [ygg.extract.source-dart :as extract.source-dart]
            [ygg.extract.source-dotnet :as extract.source-dotnet]
            [ygg.extract.source-dotnet-worker :as extract.source-dotnet-worker]
            [ygg.extract.source-jvm :as extract.source-jvm]
            [ygg.extract.source-jvm-worker :as extract.source-jvm-worker]
            [ygg.extract.source-js :as extract.source-js]
            [ygg.extract.source-ml :as extract.source-ml]
            [ygg.extract.source-native :as extract.source-native]
            [ygg.extract.source-python :as extract.source-python]
            [ygg.extract.source-scala :as extract.source-scala]
            [ygg.extract.source-scripting :as extract.source-scripting]
            [ygg.extract.source-systems :as extract.source-systems]
            [ygg.extract.starlark :as extract.starlark]
            [ygg.extract.task-config :as extract.task-config]
            [ygg.extract.terraform :as extract.terraform]
            [ygg.extract.text :as extract.text]
            [ygg.extract.tool-config :as extract.tool-config]
            [ygg.extract.web-framework-entry :as extract.web-framework-entry]
            [ygg.extract.web-source :as extract.web-source]
            [ygg.extract.workflow :as extract.workflow]
            [ygg.extract.xml :as extract.xml]
            [ygg.extract.yaml-config :as extract.yaml-config]
            [ygg.extract.yaml-generic :as extract.yaml-generic]))

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
    :javascript (extract.source-js/extract-js-family
                 run-id
                 (cond-> file
                   (parser-worker-enabled? :javascript)
                   (assoc :parser-worker-facts (parser-worker-facts file))))
    :typescript (extract.source-js/extract-js-family
                 run-id
                 (cond-> file
                   (parser-worker-enabled? :typescript)
                   (assoc :parser-worker-facts (parser-worker-facts file))))
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
    :source-map (extract.text/extract-source-map run-id file)
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
