(ns agraph.extract
  "Deterministic extraction from supported source, config, and document files."
  (:require [agraph.extract.common :as extract.common]
            [agraph.hash :as hash]
            [agraph.extract.parser-worker :as extract.parser-worker]
            [agraph.extract.router :as extract.router]))



(def extraction-buckets
  [:nodes :edges :chunks :diagnostics])

(def extractor-contract-version
  "agraph.extract/v2")

(def extractor-versions
  {:code "clojure/v9"
   :go "go/v3"
   :java "java/v2"
   :groovy "groovy/v1"
   :kotlin "kotlin/v1"
   :swift "swift/v1"
   :objective-c "objective-c/v1"
   :dotnet "dotnet/v1"
   :ruby "ruby/v1"
   :cpp "cpp/v1"
   :dart "dart/v1"
   :scala "scala/v1"
   :elixir "elixir/v1"
   :erlang "erlang/v1"
   :lua "lua/v1"
   :r "r/v1"
   :julia "julia/v1"
   :ocaml "ocaml/v1"
   :perl "perl/v1"
   :haskell "haskell/v1"
   :odin "odin/v1"
   :zig "zig/v1"
   :apple-config "apple-config/v1"
   :astro "astro/v1"
   :prisma "prisma/v1"
   :dbt "dbt/v1"
   :notebook "notebook/v1"
   :data-science "data-science/v1"
   :devcontainer "devcontainer/v1"
   :kustomize "kustomize/v1"
   :pre-commit-config "pre-commit-config/v1"
   :codeowners "codeowners/v1"
   :task-runner "task-runner/v1"
   :starlark "starlark/v1"
   :tool-version-config "tool-version-config/v1"
   :storybook "storybook/v1"
   :docs-config "docs-config/v1"
   :editor-config "editor-config/v1"
   :release-config "release-config/v1"
   :web-framework "web-framework/v1"
   :workflow-orchestration "workflow-orchestration/v1"
   :governance "governance/v1"
   :sbom "sbom/v1"
   :observability-config "observability-config/v1"
   :db-config "db-config/v2"
   :db-migration "db-migration/v2"
   :codegen-config "codegen-config/v1"
   :ops-config "ops-config/v1"
   :php "php/v1"
   :vue "vue/v1"
   :svelte "svelte/v1"
   :ci "ci/v1"
   :build "build/v1"
   :test-config "test-config/v1"
   :quality-config "quality-config/v1"
   :tool-config "tool-config/v1"
   :javascript "javascript/v1"
   :typescript "typescript/v1"
   :python "python/v3"
   :rust "rust/v2"
   :style "style/v1"
   :sql "sql/v2"
   :terraform "terraform/v1"
   :openapi "openapi/v1"
   :asyncapi "asyncapi/v1"
   :json-schema "json-schema/v1"
   :avro "avro/v1"
   :graphql "graphql/v2"
   :protobuf "protobuf/v2"
   :gettext "gettext/v1"
   :html "html/v1"
   :svg "svg/v1"
   :xml "xml/v1"
   :env "env/v2"
   :text "text/v1"
   :image-asset "asset/v1"
   :font-asset "asset/v1"
   :media-asset "asset/v1"
   :archive-asset "asset/v1"
   :compiled-artifact "asset/v1"
   :opaque-asset "asset/v1"
   :secret-material "secret-material/v1"
   :gettext-binary "gettext-binary/v1"
   :doc "markdown/v1"
   :edn "edn/v1"
   :config "config/v1"
   :unknown "unknown/v1"
   :yaml "yaml/v1"
   :manifest "manifest/v2"
   :dependency-lock "dependency-lock/v1"
   :docker "docker/v1"
   :procfile "procfile/v1"
   :compose "compose/v1"
   :helm "helm/v1"
   :shell "shell/v3"})


(defn empty-extraction
  "Return an empty canonical extractor result."
  []
  {:nodes []
   :edges []
   :chunks []
   :diagnostics []})

(defn normalize-extraction
  "Return extractor output with the canonical AGraph extraction buckets.

  External parser adapters should return data at this boundary and let AGraph
  own ids, row shape, relation names, diagnostics, and persistence."
  [extraction]
  (let [extraction (or extraction {})]
    (reduce (fn [out k]
              (assoc out k (vec (get extraction k []))))
            {}
            extraction-buckets)))






(defn normalize-parser-worker-mode
  [mode]
  (extract.parser-worker/normalize-parser-worker-mode mode))

(defn parser-worker-mode
  []
  (extract.parser-worker/parser-worker-mode))

(defmacro with-parser-worker-mode
  [mode & body]
  `(extract.parser-worker/with-parser-worker-mode ~mode ~@body))

(defn- parser-worker-fingerprint
  []
  (extract.parser-worker/parser-worker-fingerprint))

(defn parser-worker-enabled?
  [kind]
  (extract.parser-worker/parser-worker-enabled? kind))

(defn parser-worker-python
  []
  (extract.parser-worker/parser-worker-python))

(defn parser-worker-batch-facts
  "Return parser-worker facts by file path for worker-enabled file records."
  [files]
  (extract.parser-worker/parser-worker-batch-facts
   files
   {:enabled? parser-worker-enabled?
    :python parser-worker-python}))

(defn- parser-worker-facts
  [file]
  (extract.parser-worker/parser-worker-facts
   file
   {:enabled? parser-worker-enabled?
    :python parser-worker-python}))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (normalize-extraction
   (extract.router/extract-file
    run-id
    file
    {:parser-worker-enabled? parser-worker-enabled?
     :parser-worker-facts parser-worker-facts})))

(defn extractor-fingerprint
  "Return the stable extractor fingerprint for a file record."
  [file]
  (let [kind (:kind file)]
    (str "extractor:"
         (hash/short-hash [extractor-contract-version
                           kind
                           (get extractor-versions kind "none/v1")
                           (parser-worker-fingerprint)]))))

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (extract.common/node-id kind value))
  ([id-scope kind value]
   (extract.common/node-id id-scope kind value)))

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (extract.common/edge-id source-id target-id relation nil nil))

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (extract.common/chunk-id path label source-line))
  ([id-scope path label source-line]
   (extract.common/chunk-id id-scope path label source-line)))

































(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (extract.common/extract-text-source run-id file chunk-kind))






























































































































































































































































































































































































































































































































































































































































































































































































































































