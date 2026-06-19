(ns agraph.extract
  "Deterministic extraction from supported source, config, and document files."
  (:require [agraph.extract.assets :as extract.assets]
            [agraph.extract.assets-text :as extract.assets-text]
            [agraph.extract.codeowners :as extract.codeowners]
            [agraph.extract.compose :as extract.compose]
            [agraph.extract.common :as extract.common]
            [agraph.extract.devcontainer :as extract.devcontainer]
            [agraph.extract.governance :as extract.governance]
            [agraph.extract.graphql :as extract.graphql]
            [agraph.extract.infra :as extract.infra]
            [agraph.extract.notebook :as extract.notebook]
            [agraph.extract.runtime :as extract.runtime]
            [agraph.extract.sbom :as extract.sbom]
            [agraph.extract.starlark :as extract.starlark]
            [agraph.extract.task-config :as extract.task-config]
            [agraph.extract.text :as extract.text]
            [agraph.extract.xml :as extract.xml]
            [agraph.extract.yaml-config :as extract.yaml-config]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.edn :as edn]
            [agraph.extract.protobuf :as extract.protobuf]
            [agraph.extract.avro :as extract.avro]
            [agraph.extract.api-schema :as extract.api-schema]
            [agraph.extract.terraform :as extract.terraform]
            [agraph.extract.ci :as extract.ci]
            [agraph.extract.build :as extract.build]
            [agraph.extract.database :as extract.database]
            [agraph.extract.observability :as extract.observability]
            [agraph.extract.package :as extract.package]
            [agraph.extract.doc :as extract.doc]
            [agraph.extract.data-science :as extract.data-science]
            [agraph.extract.ops-config :as extract.ops-config]
            [agraph.extract.source-basic :as extract.source-basic]
            [agraph.extract.source-js :as extract.source-js]
            [agraph.extract.source-native :as extract.source-native]
            [agraph.extract.source-misc :as extract.source-misc]
            [agraph.extract.source-jvm :as extract.source-jvm]
            [agraph.extract.source-dotnet :as extract.source-dotnet]
            [agraph.extract.source-python :as extract.source-python]
            [agraph.extract.data-model :as extract.data-model]
            [agraph.extract.docs-config :as extract.docs-config]
            [agraph.extract.web-framework :as extract.web-framework]
            [agraph.extract.tool-config :as extract.tool-config]
            [agraph.extract.project-config :as extract.project-config]
            [agraph.extract.web-source :as extract.web-source]
            [agraph.extract.workflow :as extract.workflow]
            [agraph.extract.source-clojure :as extract.source-clojure]
            [agraph.extract.db-config :as extract.db-config]
            [agraph.extract.parser-worker :as extract.parser-worker]
            [agraph.extract.source-jvm-worker :as extract.source-jvm-worker]
            [agraph.extract.source-dotnet-worker :as extract.source-dotnet-worker]
            [agraph.extract.yaml-generic :as extract.yaml-generic]
            [agraph.extract.config-generic :as extract.config-generic]
            [clojure.string :as str]))



(def extraction-buckets
  [:nodes :edges :chunks :diagnostics])

(declare docs-config-array-property-values
         docs-config-property-values
         extract-astro
         extract-sfc
         js-import-targets
         json-ref-tail
         json-ref-values)

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
  ([kind value] (node-id nil kind value))
  ([id-scope kind value]
   (str (when (seq id-scope) (str id-scope ":"))
        "node:" (name kind) ":" value)))

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (str "edge:" (hash/short-hash [source-id relation target-id])))

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (chunk-id nil path label source-line))
  ([id-scope path label source-line]
   (str "chunk:" (hash/short-hash [id-scope path label source-line]))))












(defn- namespace-node
  [run-id id-scope file-id path ns-name]
  {:xt/id (node-id id-scope :namespace ns-name)
   :label ns-name
   :kind :namespace
   :file-id file-id
   :path path
   :namespace ns-name
   :name ns-name
   :public? true
   :source-line 1
   :tokens (text/tokenize ns-name)
   :active? true
   :run-id run-id})

(defn- edge-row
  [run-id file-id path source-id target-id relation confidence source-line]
  {:xt/id (edge-id source-id target-id relation path source-line)
   :source-id source-id
   :target-id target-id
   :relation relation
   :confidence confidence
   :file-id file-id
   :path path
   :source-line (or source-line 1)
   :active? true
   :run-id run-id})

(defn- generic-node
  [run-id id-scope file-id path kind label source-line]
  {:xt/id (node-id id-scope kind label)
   :label label
   :kind kind
   :file-id file-id
   :path path
   :name label
   :source-line (or source-line 1)
   :tokens (text/tokenize label)
   :active? true
   :run-id run-id})

(defn- package-label
  [ecosystem package-name]
  (str (name ecosystem) ":" package-name))

(defn- package-fact
  [{:keys [ecosystem package-name version-range dependency-scope import-names
           source-line relation]
    :or {relation :requires}}]
  (when (and ecosystem (seq package-name))
    (cond-> {:kind :external-package
             :label (package-label ecosystem package-name)
             :ecosystem ecosystem
             :package-name package-name
             :source-line (or source-line 1)
             :relation relation}
      version-range (assoc :version-range version-range)
      dependency-scope (assoc :dependency-scope dependency-scope)
      (seq import-names) (assoc :import-names (vec import-names)))))

(defn- package-version-fact
  [{:keys [ecosystem package-name resolved-version source-line relation]
    :or {relation :resolves}}]
  (when (and ecosystem (seq package-name) (seq resolved-version))
    {:kind :external-package-version
     :label (str (package-label ecosystem package-name) "@" resolved-version)
     :ecosystem ecosystem
     :package-name package-name
     :resolved-version resolved-version
     :source-line (or source-line 1)
     :relation relation}))

(defn- fact-node
  [run-id id-scope file-id path {:keys [kind label source-line] :as fact}]
  (cond-> (generic-node run-id id-scope file-id path kind label source-line)
    (:ecosystem fact) (assoc :ecosystem (:ecosystem fact))
    (:package-name fact) (assoc :package-name (:package-name fact))
    (:version-range fact) (assoc :version-range (:version-range fact))
    (:resolved-version fact) (assoc :resolved-version (:resolved-version fact))
    (:dependency-scope fact) (assoc :dependency-scope (:dependency-scope fact))
    (seq (:import-names fact)) (assoc :import-names (vec (:import-names fact)))))

(defn- fact-edge-row
  [run-id file-id path source-id id-scope {:keys [kind label source-line relation] :as fact}]
  (cond-> (edge-row run-id
                    file-id
                    path
                    source-id
                    (node-id id-scope kind label)
                    relation
                    :extracted
                    source-line)
    (:ecosystem fact) (assoc :ecosystem (:ecosystem fact))
    (:package-name fact) (assoc :package-name (:package-name fact))
    (:version-range fact) (assoc :version-range (:version-range fact))
    (:resolved-version fact) (assoc :resolved-version (:resolved-version fact))
    (:dependency-scope fact) (assoc :dependency-scope (:dependency-scope fact))
    (seq (:import-names fact)) (assoc :import-names (vec (:import-names fact)))))














(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (extract.common/extract-text-source run-id file chunk-kind))




















































(defn- bounded-message
  [message]
  (let [message (str (or message ""))]
    (subs message 0 (min 500 (count message)))))

(defn- diagnostic-row
  [run-id file-id path stage line message]
  {:xt/id (str "diagnostic:"
               (hash/short-hash [file-id stage line message]))
   :file-id file-id
   :path path
   :stage (keyword (str (or stage "extract")))
   :message (bounded-message message)
   :source-line (or line 1)
   :active? true
   :run-id run-id})











(defn- source-module-name
  [path]
  (-> path
      (str/replace #"\.d\.(?:ts|mts|cts)$" "")
      (str/replace #"\.rb\.template$" "")
      (str/replace #"\.(astro|c|cc|cpp|cxx|dart|erl|ex|exs|groovy|h|hh|hpp|hrl|hs|html|hxx|jl|kt|kts|lua|m|ml|mli|mm|mts|cts|mjs|cjs|jsx|js|odin|pl|pm|r|R|rake|rb|scala|tsx|ts|php|scss|css|sql|svelte|swift|svg|vue|zig)$" "")
      (str/replace #"/" ".")
      (str/replace #"-" "_")))




(defn- normalize-module-path-part
  [value]
  (str/replace value #"-" "_"))

(defn- drop-source-extension
  [value]
  (-> value
      (str/replace #"\.d\.ts$" "")
      (str/replace #"\.(astro|mjs|cjs|jsx|js|tsx|ts|scss|css|json|svelte|vue)$" "")))

(defn- resolve-relative-source-target
  [path target]
  (let [base (->> (str/split path #"/")
                  drop-last
                  vec)]
    (loop [parts (concat base (str/split target #"/"))
           out []]
      (if-let [part (first parts)]
        (case part
          "." (recur (rest parts) out)
          "" (recur (rest parts) out)
          ".." (recur (rest parts) (vec (drop-last out)))
          (recur (rest parts) (conj out (normalize-module-path-part part))))
        (->> out
             (str/join ".")
             drop-source-extension)))))

(defn- js-import-target
  [path target]
  (let [target (str target)]
    (if (str/starts-with? target ".")
      (resolve-relative-source-target path target)
      target)))

(defn- js-import-targets
  [idx path line]
  (let [patterns [#"^\s*import\s+(?:type\s+)?(?:[^\"']+\s+from\s+)?[\"']([^\"']+)[\"'].*"
                  #"^\s*export\s+(?:type\s+)?[^\"']+\s+from\s+[\"']([^\"']+)[\"'].*"
                  #"\brequire\s*\(\s*[\"']([^\"']+)[\"']\s*\)"
                  #"\bimport\s*\(\s*[\"']([^\"']+)[\"']\s*\)"]]
    (->> patterns
         (mapcat #(re-seq % line))
         (map second)
         (remove str/blank?)
         (map #(js-import-target path %))
         (map (fn [target]
                {:target target
                 :source-line (inc idx)}))
         distinct)))


































































































































































(defn- yaml-top-level-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name ":\\s*([^#\\n]+)\\s*$"))
                   content)
          second
          str/trim
          (str/replace #"^['\"]|['\"]$" "")))












































































































































































































































(defn- web-framework-base-kind
  [path]
  (case (fs/extension path)
    (".ts" ".tsx" ".mts" ".cts") :typescript
    (".js" ".jsx" ".mjs" ".cjs") :javascript
    ".svelte" :svelte
    ".astro" :astro
    ".vue" :vue
    nil))

(defn- web-framework-base-result
  [run-id {:keys [path content] :as file}]
  (when (or (extract.web-framework/web-route-info path)
            (extract.web-framework/angular-router-source? content)
            (extract.web-framework/ember-router-source? content)
            (extract.web-framework/ember-config-source? content))
    (case (web-framework-base-kind path)
      :typescript (extract.source-js/extract-js-family run-id (assoc file :kind :typescript))
      :javascript (extract.source-js/extract-js-family run-id (assoc file :kind :javascript))
      :svelte (extract.web-source/extract-sfc run-id (assoc file :kind :svelte))
      :astro (extract.web-source/extract-astro run-id (assoc file :kind :astro))
      :vue (extract.web-source/extract-sfc run-id (assoc file :kind :vue))
      nil)))

(defn extract-web-framework
  "Extract deterministic web framework config and file-backed route facts."
  [run-id file]
  (let [web-result (extract.common/extract-format-facts run-id
                                                        file
                                                        :web-framework-file
                                                        :web-framework-file
                                                        (extract.web-framework/web-framework-facts file))
        base-result (web-framework-base-result run-id file)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result) (:nodes web-result))))
       :edges (vec (distinct (concat (:edges base-result) (:edges web-result))))
       :chunks (vec (distinct (concat (:chunks base-result) (:chunks web-result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics web-result)))}
      web-result)))






















































































































































































































































































































(def ^:private json-ref-key
  (keyword "$ref"))

(def ^:private json-id-key
  (keyword "$id"))

(def ^:private json-defs-key
  (keyword "$defs"))

(defn- json-ref-values
  [value]
  (cond
    (map? value)
    (let [self-ref (get value json-ref-key)]
      (cond-> (mapcat json-ref-values (vals value))
        (string? self-ref) (conj self-ref)))

    (vector? value)
    (mapcat json-ref-values value)

    :else
    []))


(defn- json-ref-tail
  [prefix ref]
  (when (and (string? ref) (str/starts-with? ref prefix))
    (subs ref (count prefix))))












(declare avro-type-references)



































(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (normalize-extraction
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
     :dart (extract.source-misc/extract-dart run-id file)
     :scala (extract.source-misc/extract-scala run-id file)
     :elixir (extract.source-misc/extract-elixir run-id file)
     :erlang (extract.source-misc/extract-erlang run-id file)
     :lua (extract.source-misc/extract-lua run-id file)
     :r (extract.source-misc/extract-r run-id file)
     :julia (extract.source-misc/extract-julia run-id file)
     :ocaml (extract.source-misc/extract-ocaml run-id file)
     :perl (extract.source-misc/extract-perl run-id file)
     :haskell (extract.source-misc/extract-haskell run-id file)
     :odin (extract.source-misc/extract-odin run-id file)
     :zig (extract.source-misc/extract-zig run-id file)
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
     :web-framework (extract-web-framework run-id file)
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
     (empty-extraction))))
