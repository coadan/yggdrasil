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
            [clojure.string :as str]))



(def extraction-buckets
  [:nodes :edges :chunks :diagnostics])

(declare extract-format-facts
         read-json-map
         strip-yaml-scalar
         yaml-key-line
         yaml-section-items
         leading-spaces
         source-definition-chunk
         docs-config-array-property-values
         docs-config-property-values
         extract-astro
         extract-sfc
         json-label
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





(defn- balanced-form
  [content start]
  (let [length (count content)]
    (loop [idx start
           depth 0
           in-string? false
           escaped? false]
      (when (< idx length)
        (let [ch (.charAt content idx)
              escaped-next? (and in-string? (not escaped?) (= \\ ch))
              in-string-next? (if (or escaped? escaped-next?)
                                in-string?
                                (if (= \" ch) (not in-string?) in-string?))
              depth-next (cond
                           in-string-next? depth
                           (= \( ch) (inc depth)
                           (= \) ch) (dec depth)
                           :else depth)]
          (if (and (not in-string-next?) (zero? depth-next) (pos? depth))
            (subs content start (inc idx))
            (recur (inc idx)
                   depth-next
                   in-string-next?
                   escaped-next?)))))))

(defn- bounded-lines
  [text line-limit]
  (str/join "\n" (take line-limit (str/split-lines (or text "")))))

(defn- line-start-offsets
  [content]
  (loop [idx 0
         starts [0]]
    (if-let [newline-idx (str/index-of content "\n" idx)]
      (recur (inc newline-idx) (conj starts (inc newline-idx)))
      starts)))

(defn- balanced-curly-block
  [^String content ^long start]
  (let [content (or content "")
        length (count content)
        open-idx (str/index-of content "{" start)]
    (when open-idx
      (loop [idx open-idx
             depth 0
             in-string? false
             string-delim nil
             escaped? false]
        (when (< idx length)
          (let [ch (.charAt content (int idx))
                escaped-next? (and in-string?
                                   (not escaped?)
                                   (= \\ ch)
                                   (not= \` string-delim))
                closing-string? (and in-string?
                                     (not escaped?)
                                     (not escaped-next?)
                                     (= ch string-delim))
                opening-string? (and (not in-string?)
                                     (or (= \" ch) (= \' ch) (= \` ch)))
                in-string-next? (cond
                                  closing-string? false
                                  escaped-next? true
                                  opening-string? true
                                  :else in-string?)
                string-delim-next (cond
                                    closing-string? nil
                                    opening-string? ch
                                    :else string-delim)
                depth-next (cond
                             in-string-next? depth
                             (= \{ ch) (inc depth)
                             (= \} ch) (dec depth)
                             :else depth)]
            (if (and (not in-string-next?) (zero? depth-next) (pos? depth))
              (subs content start (inc idx))
              (recur (inc idx)
                     depth-next
                     in-string-next?
                     string-delim-next
                     escaped-next?))))))))








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








(def ^:private jvm-family-file-chunk-lines 100)
(def ^:private jvm-family-definition-chunk-lines 120)

(defn- source-text-chunk
  [run-id id-scope file-id path chunk-kind label content line-limit]
  (let [chunk-text (bounded-lines content line-limit)]
    {:xt/id (chunk-id id-scope path label 1)
     :file-id file-id
     :path path
     :kind chunk-kind
     :label label
     :text chunk-text
     :tokens (text/tokenize (str label "\n" chunk-text))
     :source-line 1
     :active? true
     :run-id run-id}))

(defn- source-definition-chunk
  [run-id id-scope file-id path label definition-kind source-line text]
  (let [chunk-text (bounded-lines text jvm-family-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind definition-kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn- source-range-text
  [content source-line end-line]
  (let [lines (vec (str/split-lines (or content "")))
        source-line (max 1 (long (or source-line 1)))
        end-line (max source-line (long (or end-line source-line)))
        start-idx (dec source-line)
        end-idx (min (count lines) end-line)]
    (if (< start-idx end-idx)
      (str/join "\n" (subvec lines start-idx end-idx))
      "")))

(defn- curly-depth-delta
  [line]
  (- (count (re-seq #"\{" line))
     (count (re-seq #"\}" line))))

(defn- pop-closed-type-scopes
  [type-stack depth]
  (->> type-stack
       (remove #(> (:end-depth %) depth))
       vec))

(defn- curly-balance-diagnostics
  [run-id file-id path content language]
  (let [balance (reduce + (map curly-depth-delta (str/split-lines content)))]
    (if (zero? balance)
      []
      [(diagnostic-row run-id
                       file-id
                       path
                       "parse"
                       1
                       (str language
                            " extractor found unbalanced curly braces."))])))

(defn- java-worker-definition-kind
  [kind]
  (case (keyword kind)
    :class :class
    :interface :interface
    :enum :enum
    :record :record
    :annotation :annotation
    :constructor :constructor
    :method :method
    :symbol))

(defn- java-worker-source-name
  [source definitions]
  (let [source (str source)
        names (set (map :name definitions))]
    (or (some #(when (or (= source %)
                         (str/ends-with? % (str "." source)))
                 %)
              names)
        source)))

(defn- java-worker-reference-target-name
  [target]
  (some-> target
          str
          (str/replace #"<.*$" "")
          (str/replace #"\[\]$" "")
          (str/split #"\.")
          last
          not-empty))

(defn extract-java-worker
  "Extract Java facts through the optional parser worker."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (parser-worker-facts file)
        module-name (or (not-empty (str (:package facts)))
                        (extract.source-jvm/java-module-name path content))
        ns-node (namespace-node run-id id-scope file-id path module-name)
        definitions (vec (:definitions facts))
        imports (vec (:imports facts))
        import-symbols (extract.source-jvm/java-import-symbols-by-simple-name imports)
        defs (mapv (fn [{:keys [kind name line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind (java-worker-definition-kind kind)
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? true
                        :source-line (or line 1)
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   definitions)
        defined-names (set (map :name definitions))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (->> imports
                          (keep (fn [{:keys [target line]}]
                                  (when-not (str/blank? (str target))
                                    (edge-row run-id file-id path
                                              (:xt/id ns-node)
                                              (node-id id-scope
                                                       :namespace
                                                       (str target))
                                              :imports
                                              :extracted
                                              (or line 1)))))
                          vec)
        reference-edges (->> (:references facts)
                             (keep (fn [{:keys [source target line]}]
                                     (let [source-name (java-worker-source-name
                                                        source
                                                        definitions)
                                           target-name (java-worker-reference-target-name
                                                        target)]
                                       (when (and (seq source-name)
                                                  (seq target-name)
                                                  (contains? defined-names
                                                             source-name)
                                                  (not= (first (str/split source-name #"\."))
                                                        target-name))
                                         (edge-row
                                          run-id
                                          file-id
                                          path
                                          (node-id id-scope
                                                   :symbol
                                                   (str module-name "/" source-name))
                                          (node-id id-scope
                                                   :symbol
                                                   (extract.source-jvm/java-reference-target-label
                                                    module-name
                                                    import-symbols
                                                    target-name))
                                          :references
                                          :extracted
                                          (or line 1))))))
                             distinct
                             vec)
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :java-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name line endLine]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   (java-worker-definition-kind kind)
                                   (or line 1)
                                   (source-range-text content
                                                      (or line 1)
                                                      (or endLine line))))
                                definitions)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges reference-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))





































(defn- dotnet-worker-definition-kind
  [kind]
  (case (keyword kind)
    :class :class
    :interface :interface
    :enum :enum
    :record :record
    :struct :struct
    :delegate :delegate
    :constructor :constructor
    :method :method
    :property :property
    :symbol))

(defn- dotnet-worker-source-name
  [source definitions]
  (let [source (str source)
        names (set (map :name definitions))]
    (or (some #(when (or (= source %)
                         (str/ends-with? % (str "." source)))
                 %)
              names)
        source)))

(defn- dotnet-worker-reference-target-name
  [target]
  (some-> target
          str
          (str/replace #"<.*$" "")
          (str/replace #"\[\]$" "")
          (str/split #"\.")
          last
          not-empty))

(defn extract-dotnet-worker
  "Extract .NET facts through the optional parser worker."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (parser-worker-facts file)
        module-name (or (not-empty (str (:namespace facts)))
                        (extract.source-dotnet/dotnet-module-name path content))
        ns-node (namespace-node run-id id-scope file-id path module-name)
        definitions (vec (:definitions facts))
        imports (vec (:imports facts))
        import-symbols (extract.source-jvm/java-import-symbols-by-simple-name imports)
        defs (mapv (fn [{:keys [kind name line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind (dotnet-worker-definition-kind kind)
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? true
                        :source-line (or line 1)
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   definitions)
        defined-names (set (map :name definitions))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (->> imports
                          (keep (fn [{:keys [target line]}]
                                  (when-not (str/blank? (str target))
                                    (edge-row run-id file-id path
                                              (:xt/id ns-node)
                                              (node-id id-scope
                                                       :namespace
                                                       (str target))
                                              :imports
                                              :extracted
                                              (or line 1)))))
                          vec)
        reference-edges (->> (:references facts)
                             (keep (fn [{:keys [source target line]}]
                                     (let [source-name (dotnet-worker-source-name
                                                        source
                                                        definitions)
                                           target-name (dotnet-worker-reference-target-name
                                                        target)]
                                       (when (and (seq source-name)
                                                  (seq target-name)
                                                  (contains? defined-names source-name)
                                                  (not= (first (str/split source-name #"\."))
                                                        target-name))
                                         (edge-row
                                          run-id
                                          file-id
                                          path
                                          (node-id id-scope
                                                   :symbol
                                                   (str module-name "/" source-name))
                                          (node-id id-scope
                                                   :symbol
                                                   (extract.source-jvm/java-reference-target-label
                                                    module-name
                                                    import-symbols
                                                    target-name))
                                          :references
                                          :extracted
                                          (or line 1))))))
                             distinct
                             vec)
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :dotnet-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name line endLine]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   (dotnet-worker-definition-kind kind)
                                   (or line 1)
                                   (source-range-text content
                                                      (or line 1)
                                                      (or endLine line))))
                                definitions)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges reference-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))





















































































(defn- manifest-name
  [path]
  (str/lower-case (last (str/split path #"/"))))


























(defn- yaml-top-level-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name ":\\s*([^#\\n]+)\\s*$"))
                   content)
          second
          str/trim
          (str/replace #"^['\"]|['\"]$" "")))




















(defn- read-json-map
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn- read-json-value
  [content]
  (try
    (json/read-json content :key-fn keyword)
    (catch Exception _
      nil)))


(defn- json-key-label
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (string? k) k
    :else (str k)))























(defn- toml-array-strings
  [value]
  (->> (re-seq #"\"([^\"]+)\"" (str value))
       (map second)
       (remove str/blank?)
       distinct
       vec))





























































































(defn- properties-assignment-lines
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ key value]
                          (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*[=:]\s*(.*?)\s*$"
                                      line)]
                 (when-not (or (str/blank? key)
                               (str/starts-with? (str/trim line) "#"))
                   {:key key
                    :value (str/trim value)
                    :source-line (inc idx)}))))))




(defn- extract-config-facts
  [run-id {:keys [id-scope file-id path] :as file} root-kind chunk-kind]
  (let [config-node (generic-node run-id id-scope file-id path root-kind path 1)
        facts (extract.common/config-facts file)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id config-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file chunk-kind)]
    {:nodes (into [config-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))


(declare yaml-scalar-list-values
         yaml-top-section-blocks
         block-key-values)










(defn extract-codegen-config
  "Extract bounded GraphQL/codegen configuration facts."
  [run-id file]
  (extract-config-facts run-id file :codegen-config :codegen-config-file))

(defn extract-test-config
  "Extract bounded test runner configuration facts."
  [run-id file]
  (extract-config-facts run-id file :test-config :test-config-file))

















































































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
  (let [web-result (extract-format-facts run-id
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










































(defn- extract-format-facts
  [run-id {:keys [id-scope file-id path] :as file} root-kind chunk-kind facts]
  (let [root-node (generic-node run-id id-scope file-id path root-kind path 1)
        facts (vec (distinct facts))
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file chunk-kind)]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- json-label
  [value]
  (cond
    (keyword? value) (json-key-label value)
    (string? value) value
    :else (str value)))









(defn- yaml-section-items
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
          (let [[_ next-section inline-value]
                (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)" line)
                inline-values (yaml-scalar-list-values inline-value)]
            (recur (rest remaining)
                   (when (contains? sections next-section) next-section)
                   (into out
                         (map (fn [value]
                                {:section next-section
                                 :value value
                                 :source-line (inc idx)}))
                         (when (contains? sections next-section)
                           inline-values))))

          (and section
               (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (re-matches #"^\s*-\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s*-\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (re-matches #"^\s+name:\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s+name:\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (not (str/blank? (str/trim line)))
               (zero? (leading-spaces line)))
          (recur (rest remaining) nil out)

          :else
          (recur (rest remaining) section out))
        out))))















































































(defn- leading-spaces
  [line]
  (count (take-while #(= \space %) line)))

(defn- yaml-key-line
  [idx line]
  (when-let [[_ indent key value]
             (re-matches #"^(\s*)(?:-\s*)?([A-Za-z0-9_.-]+):(?:\s*(.*))?$" line)]
    {:indent (count indent)
     :key key
     :value (str/trim (or value ""))
     :source-line (inc idx)}))

(defn- strip-yaml-scalar
  [value]
  (-> (str value)
      (str/replace #"^\s*['\"]|['\"]\s*$" "")
      str/trim))

(defn- yaml-scalar-list-values
  [value]
  (let [value (str/trim (or value ""))]
    (cond
      (str/blank? value) []
      (and (str/starts-with? value "[")
           (str/ends-with? value "]"))
      (->> (subs value 1 (dec (count value)))
           (#(str/split % #","))
           (map strip-yaml-scalar)
           (remove str/blank?)
           vec)

      :else
      [(strip-yaml-scalar value)])))

(defn- yaml-top-section-blocks
  [lines section-name]
  (loop [remaining (map-indexed vector lines)
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) nil out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          (and in-section?
               entry
               (= (:indent entry) (+ section-indent 2)))
          (recur (rest remaining)
                 true
                 section-indent
                 {:label (:key entry)
                  :source-line (:source-line entry)
                  :lines [[idx line]]}
                 (cond-> out current (conj current)))

          (and in-section? current)
          (recur (rest remaining)
                 true
                 section-indent
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) in-section? section-indent current out)))
      (cond-> out current (conj current)))))

(defn- block-key-values
  [block]
  (->> (:lines block)
       (keep (fn [[_idx line]]
               (when-let [{:keys [key value]} (yaml-key-line 0 line)]
                 (when (seq value)
                   [key (strip-yaml-scalar value)]))))
       (into {})))





















































































(defn- framework-yaml-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ route] (re-matches #"^\s*path:\s*['\"]?(/[^'\"\s#]+).*" line)]
               [{:kind :framework-route
                 :label route
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ controller] (re-matches #"^\s*controller:\s*['\"]?([^'\"\s#]+).*" line)]
               [{:kind :framework-controller
                 :label controller
                 :source-line source-line
                 :relation :references}])))))
       distinct
       vec))

(defn extract-yaml
  "Extract generic YAML files and explicit Kubernetes resource declarations."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [yaml-node (generic-node run-id id-scope file-id path :yaml-file path 1)
        resource-facts (vec (concat (extract.infra/k8s-resource-facts content)
                                    (framework-yaml-facts content)))
        resource-nodes (mapv (fn [{:keys [kind label source-line]}]
                               (generic-node run-id id-scope file-id path kind label source-line))
                             resource-facts)
        resource-edges (mapv (fn [{:keys [kind label source-line relation]}]
                               (edge-row run-id
                                         file-id
                                         path
                                         (:xt/id yaml-node)
                                         (node-id id-scope kind label)
                                         (or relation :defines)
                                         :extracted
                                         source-line))
                             resource-facts)
        chunk-result (extract-text-source run-id file :yaml-file)]
    {:nodes (into [yaml-node] resource-nodes)
     :edges resource-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
























































































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
             (extract-java-worker run-id file)
             (extract.source-jvm/extract-java run-id file))
     :groovy (extract.source-jvm/extract-groovy run-id file)
     :kotlin (extract.source-jvm/extract-kotlin run-id file)
     :swift (extract.source-jvm/extract-swift run-id file)
     :dotnet (if (parser-worker-enabled? :dotnet)
               (extract-dotnet-worker run-id file)
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
     :codegen-config (extract-codegen-config run-id file)
     :test-config (extract-test-config run-id file)
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
     :yaml (extract-yaml run-id file)
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
