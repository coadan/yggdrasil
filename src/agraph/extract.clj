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
            [clojure.java.shell :as shell]
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
            [clojure.string :as str]))

(def definition-symbols
  '#{def defonce defn defn- defmacro defmulti defmethod defprotocol defrecord
     deftype deftest})

(def public-definition-symbols
  '#{def defonce defn defmacro defmulti defmethod defprotocol defrecord deftype})

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

(def ^:private python-ast-helper
  (str
   "import ast, json, sys\n"
   "path = sys.argv[1]\n"
   "def emit(value):\n"
   "    sys.stdout.write(json.dumps(value, sort_keys=True))\n"
   "def import_targets(node):\n"
   "    if isinstance(node, ast.Import):\n"
   "        return [{'target': alias.name, 'line': node.lineno} for alias in node.names]\n"
   "    if isinstance(node, ast.ImportFrom):\n"
   "        prefix = '.' * node.level\n"
   "        if node.module:\n"
   "            return [{'target': prefix + node.module, 'line': node.lineno}]\n"
   "        return [{'target': prefix + alias.name, 'line': node.lineno} for alias in node.names]\n"
   "    return []\n"
   "try:\n"
   "    with open(path, 'r', encoding='utf-8', errors='replace') as f:\n"
   "        source = f.read()\n"
   "    tree = ast.parse(source, filename=path)\n"
   "    definitions = []\n"
   "    imports = []\n"
   "    for node in tree.body:\n"
   "        if isinstance(node, (ast.Import, ast.ImportFrom)):\n"
   "            imports.extend(import_targets(node))\n"
   "        elif isinstance(node, ast.ClassDef):\n"
   "            definitions.append({'kind': 'class', 'name': node.name, 'line': node.lineno})\n"
   "            for child in node.body:\n"
   "                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):\n"
   "                    kind = 'async-method' if isinstance(child, ast.AsyncFunctionDef) else 'method'\n"
   "                    definitions.append({'kind': kind, 'name': node.name + '.' + child.name, 'line': child.lineno})\n"
   "        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):\n"
   "            kind = 'async-function' if isinstance(node, ast.AsyncFunctionDef) else 'function'\n"
   "            definitions.append({'kind': kind, 'name': node.name, 'line': node.lineno})\n"
   "    emit({'definitions': definitions, 'imports': imports, 'diagnostics': []})\n"
   "except SyntaxError as e:\n"
   "    emit({'definitions': [], 'imports': [], 'diagnostics': [{'stage': 'parse', 'line': e.lineno, 'message': e.msg}]})\n"
   "except Exception as e:\n"
   "    emit({'definitions': [], 'imports': [], 'diagnostics': [{'stage': 'python-helper', 'line': None, 'message': str(e)}]})\n"))

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

(def ^:dynamic *parser-worker-mode*
  nil)

(defn normalize-parser-worker-mode
  [mode]
  (some-> mode str str/lower-case str/trim not-empty))

(defn parser-worker-mode
  []
  (or (normalize-parser-worker-mode *parser-worker-mode*)
      (normalize-parser-worker-mode (System/getenv "AGRAPH_PARSER_WORKER"))))

(defmacro with-parser-worker-mode
  [mode & body]
  `(binding [*parser-worker-mode* (normalize-parser-worker-mode ~mode)]
     ~@body))

(defn- parser-worker-fingerprint
  []
  (or (parser-worker-mode) "none"))

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

(def clojure-symbol-pattern
  "[A-Za-z0-9_.*+!?<>=/$%&-]+")

(def clojure-symbol-segment-pattern
  "[A-Za-z0-9_.*+!?<>=$%&-]+")

(def clojure-metadata-prefix-pattern
  "(?:\\^(?:\\{[^}]*\\}|\\S+)\\s+)*")

(def ^:private code-file-chunk-lines 80)
(def ^:private code-definition-chunk-lines 120)

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

(defn- ns-form-text
  [content]
  (when-let [match (re-find #"(?m)\(ns\s+" content)]
    (let [start (str/index-of content match)]
      (balanced-form content start))))

(defn- file-ns-name
  [ns-text]
  (when ns-text
    (some-> (re-find (re-pattern (str "\\(ns\\s+(" clojure-symbol-pattern ")")) ns-text)
            second)))

(defn- require-clause-text
  [ns-text]
  (when-let [match (re-find #"\(:require\b" ns-text)]
    (let [start (str/index-of ns-text match)]
      (balanced-form ns-text start))))

(defn- requires-from-ns-form
  [ns-text]
  (let [clause (or (some-> ns-text require-clause-text) "")]
    (->> (re-seq (re-pattern (str "\\[(" clojure-symbol-pattern ")"
                                  "(?:[^\\]]*?:as\\s+(" clojure-symbol-pattern "))?"
                                  "[^\\]]*\\]"))
                 clause)
         (keep (fn [[_ target alias]]
                 (when-not (str/starts-with? target ":")
                   {:target target
                    :alias alias})))
         vec)))

(defn- definition-kind
  [sym]
  (case (symbol (name sym))
    defn :var
    defn- :var
    defc :component
    defmacro :macro
    defmulti :multimethod
    defmethod :method
    defprotocol :protocol
    defrecord :record
    deftype :type
    deftest :test
    :var))

(defn- definition-public?
  [sym]
  (contains? public-definition-symbols (symbol (name sym))))

(defn- definition-node
  [run-id id-scope file-id path ns-name {:keys [def-sym name line private?]}]
  (let [def-sym (symbol def-sym)
        full-name (if ns-name (str ns-name "/" name) name)
        id (node-id id-scope :symbol full-name)]
    {:xt/id id
     :label full-name
     :kind (definition-kind def-sym)
     :file-id file-id
     :path path
     :namespace ns-name
     :name name
     :public? (and (not private?) (definition-public? def-sym))
     :source-line (or line 1)
     :tokens (text/tokenize full-name)
     :active? true
     :run-id run-id}))

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

(defn- definition-forms
  [content]
  (let [definition-names (->> (conj definition-symbols 'defc)
                              (map name)
                              (sort-by count >)
                              (str/join "|"))
        pattern (re-pattern (str "^\\s*\\("
                                 "(?:" clojure-symbol-segment-pattern "\\/)?"
                                 "(" definition-names ")\\s+("
                                 clojure-metadata-prefix-pattern
                                 ")("
                                 clojure-symbol-pattern
                                 ")"))
        line-starts (line-start-offsets content)]
    (->> (str/split-lines content)
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ def-sym metadata-prefixes name] (re-find pattern line)]
                   (let [form-start (+ (get line-starts idx 0)
                                       (or (str/index-of line "(") 0))
                         form-text (balanced-form content form-start)]
                     {:def-sym def-sym
                      :name name
                      :line (inc idx)
                      :private? (str/includes? metadata-prefixes ":private")
                      :text form-text}))))
         vec)))

(defn- code-definition-chunk
  [run-id id-scope file-id path ns-name {:keys [def-sym name line text]}]
  (let [full-name (if ns-name (str ns-name "/" name) name)
        def-sym (symbol def-sym)
        chunk-text (bounded-lines text code-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path full-name line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind (definition-kind def-sym)
             :label full-name
             :text chunk-text
             :tokens (text/tokenize (str full-name "\n" chunk-text))
             :source-line (or line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn- code-definition-fragment-chunks
  [run-id id-scope file-id path ns-name {:keys [def-sym name line text]}]
  (let [full-name (if ns-name (str ns-name "/" name) name)
        def-sym (symbol def-sym)
        source-line (or line 1)
        lines (vec (str/split-lines (or text "")))]
    (->> lines
         (drop code-definition-chunk-lines)
         (partition-all code-definition-chunk-lines)
         (map-indexed
          (fn [idx part]
            (let [offset (* (inc idx) code-definition-chunk-lines)
                  part (vec part)
                  fragment-line (+ source-line offset)
                  end-line (+ fragment-line (count part) -1)
                  label (str full-name "#lines-" fragment-line "-" end-line)
                  chunk-text (str/join "\n" part)]
              (cond-> {:xt/id (chunk-id id-scope path label fragment-line)
                       :file-id file-id
                       :path path
                       :kind :code-definition-fragment
                       :definition-kind (definition-kind def-sym)
                       :label label
                       :text chunk-text
                       :tokens (text/tokenize (str full-name "\n" label "\n" chunk-text))
                       :source-line fragment-line
                       :end-line end-line
                       :active? true
                       :run-id run-id}
                (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))))))

(defn extract-code
  "Extract graph rows from a Clojure source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (try
    (let [ns-text (ns-form-text content)
          ns-name (or (file-ns-name ns-text) (str/replace path #"\.(clj|cljc|cljs)$" ""))
          ns-node (namespace-node run-id id-scope file-id path ns-name)
          require-specs (requires-from-ns-form ns-text)
          def-forms (definition-forms content)
          defs (mapv #(definition-node run-id id-scope file-id path ns-name %) def-forms)
          contains-edges (mapv #(edge-row run-id file-id path
                                          (:xt/id ns-node) (:xt/id %)
                                          :defines :extracted (:source-line %))
                               defs)
          require-edges (mapv #(edge-row run-id file-id path
                                         (:xt/id ns-node)
                                         (node-id id-scope :namespace (:target %))
                                         :requires :extracted 1)
                              require-specs)
          chunk-text (bounded-lines content code-file-chunk-lines)
          chunk {:xt/id (chunk-id id-scope path ns-name 1)
                 :file-id file-id
                 :path path
                 :kind :code-file
                 :label ns-name
                 :text chunk-text
                 :tokens (text/tokenize (str ns-name "\n" chunk-text))
                 :source-line 1
                 :active? true
                 :run-id run-id}
          definition-chunks (mapv #(code-definition-chunk run-id
                                                          id-scope
                                                          file-id
                                                          path
                                                          ns-name
                                                          %)
                                  def-forms)
          definition-fragment-chunks (mapcat #(code-definition-fragment-chunks run-id
                                                                               id-scope
                                                                               file-id
                                                                               path
                                                                               ns-name
                                                                               %)
                                             def-forms)]
      {:nodes (into [ns-node] defs)
       :edges (vec (concat contains-edges require-edges))
       :chunks (vec (concat [chunk] definition-chunks definition-fragment-chunks))
       :diagnostics []})
    (catch Exception e
      {:nodes []
       :edges []
       :chunks []
       :diagnostics [{:xt/id (str "diagnostic:" (hash/short-hash [file-id :parse (ex-message e)]))
                      :file-id file-id
                      :path path
                      :stage :parse
                      :message (str (ex-message e)
                                    " at "
                                    (some-> e .getStackTrace first str))
                      :active? true
                      :run-id run-id}]})))










(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (extract.common/extract-text-source run-id file chunk-kind))














































(defn- python-module-name
  [path]
  (let [path (str/replace (str path) #"^src/" "")
        module (-> path
                   (str/replace #"\.py$" "")
                   (str/replace #"/" ".")
                   (str/replace #"-" "_"))]
    (if (str/ends-with? module ".__init__")
      (let [trimmed (subs module 0 (- (count module) (count ".__init__")))]
        (if (seq trimmed) trimmed "__init__"))
      module)))

(defn- python-definition-kind
  [kind]
  (case kind
    "class" :class
    "function" :function
    "async-function" :function
    "method" :method
    "async-method" :method
    :python-symbol))

(defn- python-public?
  [name]
  (let [local-name (last (str/split (str name) #"\."))]
    (not (str/starts-with? local-name "_"))))

(defn- split-module
  [module-name]
  (->> (str/split (str module-name) #"\.")
       (remove str/blank?)
       vec))

(defn- python-package-parts
  [path module-name]
  (let [parts (split-module module-name)]
    (if (str/ends-with? path "__init__.py")
      parts
      (vec (drop-last parts)))))

(defn- python-import-target
  [path module-name target]
  (let [target (str target)]
    (if-not (str/starts-with? target ".")
      target
      (let [level (count (take-while #(= \. %) target))
            tail (subs target level)
            package-parts (python-package-parts path module-name)
            base-count (max 0 (- (count package-parts) (dec level)))
            base-parts (subvec package-parts 0 base-count)
            tail-parts (split-module tail)]
        (str/join "." (concat base-parts tail-parts))))))

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

(defn- parser-worker-enabled?
  [kind]
  (let [mode (parser-worker-mode)]
    (and kind
         (or (= "all" mode)
             (= (name kind) mode)))))

(defn- parser-worker-python
  []
  (or (not-empty (System/getenv "AGRAPH_PARSER_WORKER_PYTHON"))
      "python3"))

(defn- parser-worker-failure
  [stage message]
  {:definitions []
   :imports []
   :references []
   :diagnostics [{:stage stage
                  :line nil
                  :message message}]})

(defn- parser-worker-request
  [{:keys [kind path absolute-path content]}]
  (cond-> {:schema "agraph.parser.request/v1"
           :id path
           :kind (name kind)
           :path (or absolute-path path)}
    (nil? absolute-path) (assoc :content content)))

(defn- parser-worker-response->facts
  [response]
  (or (:facts response)
      (parser-worker-failure
       "parser-worker"
       "Parser worker response did not include facts.")))

(defn parser-worker-batch-facts
  "Return parser-worker facts by file path for worker-enabled file records."
  [files]
  (let [files (vec (filter #(parser-worker-enabled? (:kind %)) files))]
    (if (empty? files)
      {}
      (try
        (let [input (str (str/join "\n"
                                   (map #(json/write-json-str
                                          (parser-worker-request %)
                                          {:escape-slash false})
                                        files))
                         "\n")
              {:keys [exit out err]} (shell/sh (parser-worker-python)
                                               "scripts/parser-worker.py"
                                               :in input)]
          (if (zero? exit)
            (try
              (let [responses (->> (str/split-lines out)
                                   (remove str/blank?)
                                   (map #(json/read-json % :key-fn keyword)))
                    by-id (->> responses
                               (map (fn [response]
                                      [(:id response)
                                       (parser-worker-response->facts response)]))
                               (into {}))]
                (->> files
                     (map (fn [{:keys [path]}]
                            [path
                             (or (get by-id path)
                                 (parser-worker-failure
                                  "parser-worker"
                                  "Parser worker did not return a response for this file."))]))
                     (into {})))
              (catch Exception e
                (let [failure (parser-worker-failure
                               "parser-worker"
                               (str "Parser worker returned unreadable JSON: "
                                    (ex-message e)))]
                  (into {} (map (juxt :path (constantly failure)) files)))))
            (let [failure (parser-worker-failure
                           "parser-worker"
                           (str "parser worker exited " exit ": "
                                (or (not-empty err) out)))]
              (into {} (map (juxt :path (constantly failure)) files)))))
        (catch Exception e
          (let [failure (parser-worker-failure
                         "parser-worker"
                         (str "Could not run parser worker: " (ex-message e)))]
            (into {} (map (juxt :path (constantly failure)) files))))))))

(defn- parser-worker-facts
  [{:keys [path] :as file}]
  (if (contains? file :parser-worker-facts)
    (:parser-worker-facts file)
    (get (parser-worker-batch-facts [file])
         path
         (parser-worker-failure
          "parser-worker"
          "Parser worker did not return facts for this file."))))

(defn- python-helper-failure
  [message]
  {:definitions []
   :imports []
   :diagnostics [{:stage "python-helper"
                  :line nil
                  :message message}]})

(defn- python-facts
  [absolute-path]
  (try
    (let [{:keys [exit out err]} (shell/sh "python3"
                                           "-"
                                           absolute-path
                                           :in
                                           python-ast-helper)]
      (if (zero? exit)
        (try
          (json/read-json out :key-fn keyword)
          (catch Exception e
            (python-helper-failure
             (str "Python AST helper returned unreadable JSON: " (ex-message e)))))
        (python-helper-failure
         (str "python3 exited " exit ": " (or (not-empty err) out)))))
    (catch Exception e
      (python-helper-failure (str "Could not run python3 AST helper: "
                                  (ex-message e))))))

(defn extract-python
  "Extract graph rows from a Python source file record using Python's stdlib AST."
  [run-id {:keys [id-scope file-id path absolute-path content] :as file}]
  (let [module-name (python-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        facts (if (contains? file :parser-worker-facts)
                (parser-worker-facts file)
                (python-facts absolute-path))
        defs (->> (:definitions facts)
                  (mapv (fn [{:keys [kind name line]}]
                          (let [label (str module-name "/" name)]
                            (cond-> {:xt/id (node-id id-scope :symbol label)
                                     :label label
                                     :kind (python-definition-kind kind)
                                     :file-id file-id
                                     :path path
                                     :namespace module-name
                                     :name name
                                     :public? (python-public? name)
                                     :source-line (or line 1)
                                     :tokens (text/tokenize label)
                                     :active? true
                                     :run-id run-id}
                              (contains? #{"async-function" "async-method"} kind)
                              (assoc :async? true))))))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (->> (:imports facts)
                          (keep (fn [{:keys [target line]}]
                                  (let [target (python-import-target path
                                                                     module-name
                                                                     target)]
                                    (when-not (str/blank? (str target))
                                      (edge-row run-id file-id path
                                                (:xt/id ns-node)
                                                (node-id id-scope :namespace target)
                                                :imports
                                                :extracted
                                                (or line 1))))))
                          vec)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))
        chunk-text (str/join "\n" (take 100 (str/split-lines content)))
        chunk {:xt/id (chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind :python-file
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks [chunk]
     :diagnostics diagnostics}))

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

(defn- groovy-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- groovy-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+(?:static\s+)?([A-Za-z_][A-Za-z0-9_.*]*)\s*$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- groovy-type-line
  [current-type idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(public|private|protected)\s+)?(?:(?:abstract|final|static)\s+)*(class|interface|enum|trait|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "class" :class
             "interface" :interface
             "enum" :enum
             "trait" :trait
             "@interface" :annotation)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))

(def ^:private groovy-method-exclusions
  #{"catch" "for" "if" "return" "switch" "while"})

(defn- groovy-member-line
  [current-type idx line]
  (or (when-let [[_ visibility name]
                 (re-matches
                  #"^\s*(?:(public|private|protected)\s+)?(?:(?:static|final|abstract|synchronized)\s+)*(?:def|[A-Za-z_][A-Za-z0-9_<>,?.\[\]]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:\{.*)?"
                  line)]
        (when-not (contains? groovy-method-exclusions name)
          {:kind :method
           :name (if current-type
                   (str (:name current-type) "." name)
                   name)
           :public? (not= "private" visibility)
           :source-line (inc idx)}))
      (when-let [[_ visibility name]
                 (re-matches
                  #"^\s*(?:(public|private|protected)\s+)?(?:(?:static|final|volatile|transient)\s+)*(?:def|[A-Za-z_][A-Za-z0-9_<>,?.\[\]]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=.*)?"
                  line)]
        (when-not (contains? groovy-method-exclusions name)
          {:kind :property
           :name (if current-type
                   (str (:name current-type) "." name)
                   name)
           :public? (not= "private" visibility)
           :source-line (inc idx)}))))

(defn- groovy-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              type-form (groovy-type-line current-type idx line)
              member-form (when-not type-form
                            (groovy-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))

(defn extract-groovy
  "Extract bounded package, import, and declaration facts from Groovy source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (groovy-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (groovy-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (groovy-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :groovy-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "Groovy")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- kotlin-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- kotlin-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+([A-Za-z_][A-Za-z0-9_.*]*)\s*$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- kotlin-type-line
  [current-type idx line]
  (or (when-let [[_ visibility name]
                 (re-matches
                  #"^\s*(?:(public|private|protected|internal)\s+)?companion\s+object(?:\s+([A-Za-z_][A-Za-z0-9_]*))?\b.*"
                  line)]
        {:kind :object
         :name (let [object-name (or name "Companion")]
                 (if current-type
                   (str (:name current-type) "." object-name)
                   object-name))
         :public? (not= "private" visibility)
         :source-line (inc idx)})
      (when-let [[_ visibility kind name]
                 (re-matches
                  #"^\s*(?:(public|private|protected|internal)\s+)?(?:(?:data|sealed|open|abstract|final|value|inner)\s+)*(class|interface|object|enum\s+class|annotation\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                  line)]
        {:kind (case kind
                 "class" :class
                 "interface" :interface
                 "object" :object
                 "enum class" :enum
                 "annotation class" :annotation)
         :name (if current-type
                 (str (:name current-type) "." name)
                 name)
         :public? (not= "private" visibility)
         :source-line (inc idx)})))

(defn- kotlin-member-line
  [current-type idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(public|private|protected|internal)\s+)?(?:(?:const|suspend|inline|operator|override|open|private|public|protected|internal)\s+)*(fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "fun" :function
             "val" :property
             "var" :property)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))

(defn- kotlin-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              type-form (kotlin-type-line current-type idx line)
              member-form (when-not type-form
                            (kotlin-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))

(defn extract-kotlin
  "Extract bounded package, import, and declaration facts from Kotlin source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (kotlin-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (kotlin-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (kotlin-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :kotlin-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "Kotlin")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- swift-module-name
  [path]
  (source-module-name path))

(defn- swift-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+(?:struct|class|enum|protocol|func|var)?\s*([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- swift-type-line
  [idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(open|public|internal|fileprivate|private)\s+)?(?:(?:final|indirect)\s+)*(class|struct|enum|protocol|actor|extension)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "class" :class
             "struct" :struct
             "enum" :enum
             "protocol" :protocol
             "actor" :actor
             "extension" :extension)
     :name name
     :public? (contains? #{"open" "public"} visibility)
     :source-line (inc idx)}))

(defn- swift-member-line
  [current-type idx line]
  (or (when current-type
        (when-let [[_ visibility]
                   (re-matches
                    #"^\s*(?:(open|public|internal|fileprivate|private)\s+)?(?:convenience\s+|required\s+|override\s+)*init\s*\(.*"
                    line)]
          {:kind :initializer
           :name (str (:name current-type) ".init")
           :public? (contains? #{"open" "public"} visibility)
           :source-line (inc idx)}))
      (when-let [[_ visibility kind name]
                 (re-matches
                  #"^\s*(?:(open|public|internal|fileprivate|private)\s+)?(?:(?:static|class|mutating|nonmutating|override|async|throws)\s+)*(func|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                  line)]
        {:kind (case kind
                 "func" :function
                 "let" :property
                 "var" :property)
         :name (if current-type
                 (str (:name current-type) "." name)
                 name)
         :public? (contains? #{"open" "public"} visibility)
         :source-line (inc idx)})))

(defn- swift-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              type-form (swift-type-line idx line)
              member-form (when-not type-form
                            (swift-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))

(defn extract-swift
  "Extract bounded import and declaration facts from Swift source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (swift-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (swift-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (swift-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :swift-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "Swift")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- dotnet-module-name
  [path content]
  (or (some (fn [line]
              (or (second (re-matches #"^\s*namespace\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                                      line))
                  (second (re-matches #"^\s*namespace\s+([A-Za-z_][A-Za-z0-9_.]*)\s*\{\s*$"
                                      line))
                  (second (re-matches #"^\s*Namespace\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                      line))
                  (second (re-matches #"^\s*namespace\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                      line))
                  (second (re-matches #"^\s*module\s+([A-Za-z_][A-Za-z0-9_.]*)\s*=?.*"
                                      line))))
            (str/split-lines content))
      (source-module-name path)))

(defn- dotnet-source-language
  [path]
  (case (str/lower-case (or (fs/extension path) ""))
    ".fs" :fsharp
    ".fsx" :fsharp
    ".vb" :visual-basic
    :csharp))

(defn- dotnet-using-target
  [line]
  (or (second (re-matches #"^\s*using\s+static\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                          line))
      (second (re-matches #"^\s*using\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                          line))
      (second (re-matches #"^\s*using\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                          line))
      (second (re-matches #"^\s*open\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                          line))
      (second (re-matches #"^\s*Imports\s+(?:[A-Za-z_][A-Za-z0-9_]*\s*=\s*)?([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                          line))))

(defn- dotnet-usings
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [target (dotnet-using-target line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- dotnet-type-kind
  [kind]
  (case kind
    "class" :class
    "interface" :interface
    "enum" :enum
    "record" :record
    "struct" :struct
    "delegate" :delegate
    :dotnet-symbol))

(defn- dotnet-type-line
  [idx line]
  (or (when-let [[_ visibility name]
                 (re-matches
                  #"^\s*(?:(public|protected|private|internal)\s+)?(?:(?:static|abstract|sealed|partial|readonly|ref|unsafe)\s+)*delegate\s+[A-Za-z_][A-Za-z0-9_<>,?.\[\]]*\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                  line)]
        {:kind :delegate
         :name name
         :public? (= "public" visibility)
         :source-line (inc idx)})
      (when-let [[_ visibility kind name]
                 (re-matches
                  #"^\s*(?:(public|protected|private|internal)\s+)?(?:(?:static|abstract|sealed|partial|readonly|ref|unsafe)\s+)*(class|interface|enum|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                  line)]
        {:kind (dotnet-type-kind kind)
         :name name
         :public? (= "public" visibility)
         :source-line (inc idx)})))

(def ^:private dotnet-member-exclusions
  #{"catch" "for" "foreach" "if" "lock" "return" "switch" "using" "while"})

(defn- dotnet-property-line
  [current-type idx line]
  (when current-type
    (when-let [[_ visibility name]
               (re-matches
                #"^\s*(?:(public|protected|private|internal)\s+)?(?:(?:static|virtual|override|abstract|sealed|readonly|required|init|new)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,?.\[\]]+\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*\b(?:get|set|init)\b.*"
                line)]
      {:kind :property
       :name (str (:name current-type) "." name)
       :public? (= "public" visibility)
       :source-line (inc idx)})))

(defn- dotnet-method-line
  [current-type idx line]
  (when current-type
    (when-let [[_ visibility _modifiers return-type name]
               (re-matches
                #"^\s*(?:(public|protected|private|internal)\s+)?((?:(?:static|virtual|override|abstract|sealed|async|extern|unsafe|new)\s+)*)?(?:([^();={}]+?)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:where\b[^{]*)?(?:=>.*|;|\{.*)?\s*$"
                line)]
      (when-not (contains? dotnet-member-exclusions name)
        (let [constructor? (= name (:name current-type))
              explicit-member? (or visibility
                                   (seq (str/trim (or _modifiers "")))
                                   return-type
                                   constructor?)]
          (when explicit-member?
            {:kind (if constructor? :constructor :method)
             :name (str (:name current-type) "." name)
             :public? (= "public" visibility)
             :source-line (inc idx)}))))))

(defn- dotnet-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              direct-member? (and current-type
                                  (= depth (:end-depth current-type)))
              type-form (dotnet-type-line idx line)
              property-form (when (and direct-member? (not type-form))
                              (dotnet-property-line current-type idx line))
              method-form (when (and direct-member? (not type-form))
                            (dotnet-method-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      property-form (conj property-form)
                      method-form (conj method-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))

(defn- fsharp-definition-line
  [idx line current-module current-type]
  (or (when-let [[_ name]
                 (re-matches #"^\s*module\s+([A-Za-z_][A-Za-z0-9_]*)\s*=?.*"
                             line)]
        {:kind :module
         :name name
         :public? true
         :container name
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        (let [qualified (if current-module
                          (str current-module "." name)
                          name)]
          {:kind :type
           :name qualified
           :public? true
           :container {:name qualified
                       :indent (count (take-while #(= \space %) line))}
           :source-line (inc idx)
           :text line}))
      (when-let [[_ visibility name]
                 (re-matches #"^\s*let\s+(?:(private|internal)\s+)?(?:rec\s+)?([A-Za-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        (let [container (or (:name current-type) current-module)
              qualified (if container (str container "." name) name)]
          {:kind :function
           :name qualified
           :public? (not (#{"private" "internal"} visibility))
           :source-line (inc idx)
           :text line}))
      (when-let [[_ name]
                 (re-matches #"^\s*member\s+(?:private\s+|internal\s+)?(?:[A-Za-z_][A-Za-z0-9_']*|\([^)]+\))\.([A-Za-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        (let [container (or (:name current-type) current-module)
              qualified (if container (str container "." name) name)]
          {:kind :method
           :name qualified
           :public? true
           :source-line (inc idx)
           :text line}))))

(defn- fsharp-definition-forms
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         current-module nil
         current-type nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [indent (count (take-while #(= \space %) line))
            current-type (if (and current-type
                                  (not (str/blank? line))
                                  (<= indent (:indent current-type)))
                           nil
                           current-type)
            form (fsharp-definition-line idx line current-module current-type)
            current-module* (or (:container (when (= :module (:kind form)) form))
                                current-module)
            current-type* (or (:container (when (= :type (:kind form)) form))
                              current-type)]
        (recur (rest remaining)
               current-module*
               current-type*
               (cond-> out
                 form (conj (dissoc form :container)))))
      out)))

(defn- visual-basic-type-line
  [idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(Public|Private|Protected|Friend|Partial|NotInheritable|MustInherit)\s+)*(Class|Interface|Enum|Structure|Module)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "Class" :class
             "Interface" :interface
             "Enum" :enum
             "Structure" :struct
             "Module" :module)
     :name name
     :public? (= "Public" visibility)
     :source-line (inc idx)
     :text line}))

(defn- visual-basic-member-line
  [current-type idx line]
  (when current-type
    (or (when-let [[_ visibility _kind name]
                   (re-matches
                    #"^\s*(?:(Public|Private|Protected|Friend|Shared|Overrides|Overridable|MustOverride|Async)\s+)*(Sub|Function)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                    line)]
          {:kind :method
           :name (str (:name current-type) "." name)
           :public? (= "Public" visibility)
           :source-line (inc idx)
           :text line})
        (when-let [[_ visibility name]
                   (re-matches
                    #"^\s*(?:(Public|Private|Protected|Friend|Shared|ReadOnly|WriteOnly|Overrides|Overridable)\s+)*Property\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                    line)]
          {:kind :property
           :name (str (:name current-type) "." name)
           :public? (= "Public" visibility)
           :source-line (inc idx)
           :text line}))))

(defn- visual-basic-definition-forms
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         type-stack []
         out []]
    (if-let [[idx line] (first remaining)]
      (let [end-type? (boolean (re-matches #"^\s*End\s+(Class|Interface|Enum|Structure|Module)\s*$"
                                           line))
            type-stack (if end-type? (pop type-stack) type-stack)
            current-type (peek type-stack)
            type-form (when-not end-type? (visual-basic-type-line idx line))
            member-form (when-not type-form
                          (visual-basic-member-line current-type idx line))
            type-stack* (cond-> type-stack
                          type-form (conj type-form))]
        (recur (rest remaining)
               type-stack*
               (cond-> out
                 type-form (conj type-form)
                 member-form (conj member-form))))
      out)))

(defn- dotnet-language-definition-forms
  [path content]
  (case (dotnet-source-language path)
    :fsharp (fsharp-definition-forms content)
    :visual-basic (visual-basic-definition-forms content)
    (dotnet-definition-forms content)))

(defn extract-dotnet
  "Extract bounded namespace, using, and declaration facts from .NET source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (dotnet-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (dotnet-language-definition-forms path content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        using-edges (mapv #(edge-row run-id file-id path
                                     (:xt/id ns-node)
                                     (node-id id-scope :namespace (:target %))
                                     :imports
                                     :extracted
                                     (:source-line %))
                          (dotnet-usings lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :dotnet-file
                                 module-name
                                 content
                                 jvm-family-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               ".NET")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges using-edges))
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
                        (dotnet-module-name path content))
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













































































(defn- prisma-block-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(datasource|generator|model|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*"
                         line)]
    {:kind (case kind
             "datasource" :prisma-datasource
             "generator" :prisma-generator
             "model" :prisma-model
             "enum" :prisma-enum)
     :name name
     :source-line (inc idx)}))

(defn- prisma-blocks
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           depth 0
           out []]
      (if-let [[idx line] (first remaining)]
        (let [block (when-not current
                      (prisma-block-line idx line))
              current* (or current
                           (when block
                             (assoc block :lines [])))
              current** (cond-> current*
                          current* (update :lines conj [idx line]))
              depth* (if current* (+ depth (curly-depth-delta line)) depth)]
          (cond
            (and current** (<= depth* 0) (pos? depth))
            (recur (rest remaining) nil 0 (conj out current**))

            current**
            (recur (rest remaining) current** depth* out)

            :else
            (recur (rest remaining) nil 0 out)))
        (cond-> out current (conj current))))))

(def prisma-scalar-types
  #{"BigInt" "Boolean" "Bytes" "DateTime" "Decimal" "Float" "Int" "Json"
    "String" "Unsupported"})

(defn- prisma-field-references
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (keep (fn [[idx line]]
                 (when-let [[_ _field field-type]
                            (when-not (str/includes? line "{")
                              (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\[\])?(?:\?|)\b.*"
                                          line))]
                   (when-not (contains? prisma-scalar-types field-type)
                     {:target field-type
                      :source-line (inc idx)}))))
         distinct
         vec)))

(defn- prisma-assignment-facts
  [block]
  (let [block-name (:name block)]
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ value]
                          (re-matches #"^\s*provider\s*=\s*\"([^\"]+)\".*$"
                                      line)]
                 [{:kind (case (:kind block)
                           :prisma-datasource :prisma-datasource-provider
                           :prisma-generator :prisma-generator-provider
                           :prisma-config-provider)
                   :label (str block-name ":" value)
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ key]
                          (re-matches #"^\s*url\s*=\s*env\(\"([^\"]+)\"\).*$"
                                      line)]
                 [{:kind :prisma-env-key
                   :label (str block-name ":" key)
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ value]
                          (re-matches #"^\s*output\s*=\s*\"([^\"]+)\".*$"
                                      line)]
                 [{:kind :prisma-generator-output
                   :label (str block-name ":" value)
                   :source-line source-line
                   :relation :references}])))))
         distinct
         vec)))

(defn- prisma-model-field-facts
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (when-let [[_ field-name field-type _array _optional attributes]
                       (when-not (str/includes? line "{")
                         (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)(\[\])?(\?)?\s*(.*?)\s*$"
                                     line))]
              (let [source-line (inc idx)
                    label-prefix (str (:name block) "." field-name)
                    relation-field? (not (contains? prisma-scalar-types field-type))]
                (remove nil?
                        [{:kind (if relation-field?
                                  :prisma-relation-field
                                  :prisma-field)
                          :label (str label-prefix ":" field-type)
                          :source-line source-line
                          :relation :defines}
                         (when (str/includes? (str attributes) "@id")
                           {:kind :prisma-id-field
                            :label label-prefix
                            :source-line source-line
                            :relation :defines})
                         (when-let [[_ mapped]
                                    (re-find #"@map\(\"([^\"]+)\"\)" (str attributes))]
                           {:kind :prisma-column-map
                            :label (str label-prefix "=" mapped)
                            :source-line source-line
                            :relation :defines})])))))
         distinct
         vec)))

(defn- prisma-model-attribute-facts
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ mapped]
                          (re-matches #"^\s*@@map\(\"([^\"]+)\"\).*$" line)]
                 [{:kind :prisma-table-map
                   :label (str (:name block) "=" mapped)
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ fields]
                          (re-matches #"^\s*@@index\(\[([^\]]+)\].*$" line)]
                 [{:kind :prisma-index
                   :label (str (:name block) ":"
                               (str/replace fields #"\s+" ""))
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ fields]
                          (re-matches #"^\s*@@unique\(\[([^\]]+)\].*$" line)]
                 [{:kind :prisma-unique
                   :label (str (:name block) ":"
                               (str/replace fields #"\s+" ""))
                   :source-line source-line
                   :relation :defines}])))))
         distinct
         vec)))

(defn- prisma-block-facts
  [blocks]
  (->> blocks
       (mapcat (fn [block]
                 (map #(assoc % :block-kind (:kind block) :block-name (:name block))
                      (concat (prisma-assignment-facts block)
                              (prisma-model-field-facts block)
                              (prisma-model-attribute-facts block)))))
       distinct
       vec))

(defn- prisma-reference-edges
  [run-id id-scope file-id path blocks]
  (let [block-kind-by-label (into {} (map (juxt :name :kind)) blocks)]
    (->> blocks
         (mapcat
          (fn [{:keys [name kind] :as block}]
            (map (fn [{:keys [target source-line]}]
                   (edge-row run-id
                             file-id
                             path
                             (node-id id-scope kind name)
                             (node-id id-scope
                                      (get block-kind-by-label target :prisma-reference)
                                      target)
                             :references
                             :extracted
                             source-line))
                 (prisma-field-references block))))
         distinct
         vec)))

(defn extract-prisma
  "Extract bounded Prisma datasource, generator, model, enum, and relation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [schema-node (generic-node run-id id-scope file-id path :prisma-file path 1)
        blocks (prisma-blocks content)
        block-nodes (mapv (fn [{:keys [kind name source-line]}]
                            (generic-node run-id id-scope file-id path kind name source-line))
                          blocks)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id schema-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           block-nodes)
        fact-rows (prisma-block-facts blocks)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         kind label source-line))
                         fact-rows)
        fact-edges (mapv (fn [{:keys [block-kind block-name kind label
                                      source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (node-id id-scope block-kind block-name)
                                     (node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         fact-rows)
        reference-edges (prisma-reference-edges run-id id-scope file-id path blocks)
        chunk-result (extract-text-source run-id file :prisma-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line lines]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   name
                                   kind
                                   source-line
                                   (str/join "\n" (map second lines))))
                                blocks)
        diagnostics (curly-balance-diagnostics run-id file-id path content "Prisma")]
    {:nodes (vec (concat [schema-node] block-nodes fact-nodes))
     :edges (vec (concat define-edges fact-edges reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))







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

(defn- comma-separated-values
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- flyway-config-facts
  [content]
  (->> (properties-assignment-lines content)
       (mapcat
        (fn [{:keys [key value source-line]}]
          (cond
            (= "flyway.locations" key)
            (map (fn [entry]
                   {:kind :flyway-location
                    :label entry
                    :source-line source-line
                    :relation :references})
                 (comma-separated-values value))

            (= "flyway.schemas" key)
            (map (fn [entry]
                   {:kind :db-schema
                    :label entry
                    :source-line source-line
                    :relation :defines})
                 (comma-separated-values value))

            (str/starts-with? key "flyway.placeholders.")
            [{:kind :flyway-placeholder
              :label (subs key (count "flyway.placeholders."))
              :source-line source-line
              :relation :defines}]

            (contains? #{"flyway.baselineOnMigrate"
                         "flyway.baselineVersion"
                         "flyway.baselineDescription"}
                       key)
            [{:kind :flyway-baseline-flag
              :label (str key "=" value)
              :source-line source-line
              :relation :defines}]

            :else [])))
       distinct
       vec))

(defn- db-config-facts
  [{:keys [path content] :as file}]
  (let [filename (manifest-name path)]
    (vec (concat (extract.common/config-facts file)
                 (case filename
                   "flyway.conf" (flyway-config-facts content)
                   [])))))

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

(defn extract-db-config
  "Extract bounded database migration/tool configuration facts."
  [run-id file]
  (let [facts (db-config-facts file)
        config-node (generic-node run-id
                                  (:id-scope file)
                                  (:file-id file)
                                  (:path file)
                                  :db-config
                                  (:path file)
                                  1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id
                                         (:id-scope file)
                                         (:file-id file)
                                         (:path file)
                                         kind
                                         label
                                         source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     (:file-id file)
                                     (:path file)
                                     (:xt/id config-node)
                                     (node-id (:id-scope file) kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file :db-config-file)]
    {:nodes (into [config-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(declare yaml-scalar-list-values
         yaml-top-section-blocks
         block-key-values)

(def dbt-path-sections
  #{"analysis-paths" "macro-paths" "model-paths" "seed-paths"
    "snapshot-paths" "test-paths"})

(defn- yaml-list-values
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (str/blank? (str/trim line))
          (recur (rest remaining) section out)

          (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
          (let [[_ next-section inline-value]
                (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)" line)
                values (->> (re-seq #"['\"]?([^,'\"\[\]\s]+)['\"]?" inline-value)
                            (map second)
                            (remove str/blank?))]
            (recur (rest remaining)
                   (when (contains? sections next-section) next-section)
                   (into out
                         (map (fn [value]
                                {:section next-section
                                 :value value
                                 :source-line (inc idx)}))
                         (when (contains? sections next-section)
                           values))))

          (and section
               (re-matches #"^\s*-\s+(.+?)\s*$" line))
          (let [value (-> (second (re-matches #"^\s*-\s+(.+?)\s*$" line))
                          str/trim
                          (str/replace #"^['\"]|['\"]$" ""))]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value value
                              :source-line (inc idx)})))

          :else
          (recur (rest remaining) section out))
        out))))

(defn- dbt-project-facts
  [content path]
  (let [project-name (yaml-top-level-value content "name")
        profile-name (yaml-top-level-value content "profile")
        version (yaml-top-level-value content "version")
        config-version (yaml-top-level-value content "config-version")
        path-facts (->> (yaml-list-values content dbt-path-sections)
                        (mapv (fn [{:keys [section value source-line]}]
                                {:kind :dbt-path
                                 :label (str section "=" value)
                                 :source-line source-line
                                 :relation :defines})))]
    {:project-label (or project-name path)
     :facts (vec (concat
                  (cond-> []
                    project-name (conj {:kind :dbt-project-name
                                        :label project-name
                                        :source-line 1
                                        :relation :defines})
                    profile-name (conj {:kind :dbt-profile
                                        :label profile-name
                                        :source-line 1
                                        :relation :references})
                    version (conj {:kind :dbt-config
                                   :label (str "version=" version)
                                   :source-line 1
                                   :relation :defines})
                    config-version (conj {:kind :dbt-config
                                          :label (str "config-version="
                                                      config-version)
                                          :source-line 1
                                          :relation :defines}))
                  path-facts))
     :diagnostics (when-not project-name
                    [{:stage "parse"
                      :line 1
                      :message "dbt project file did not declare a top-level name."}])}))

(defn- dbt-package-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (->> lines
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ package-name]
                            (or (re-matches #"^\s*-\s+package:\s+(.+?)\s*$" line)
                                (re-matches #"^\s*-\s+git:\s+(.+?)\s*$" line))]
                   (package-fact {:ecosystem :dbt
                                  :package-name (strip-yaml-scalar package-name)
                                  :source-line (inc idx)}))))
         distinct
         vec)))

(defn- dbt-profile-facts
  [content path]
  (let [lines (vec (str/split-lines content))
        reserved #{"config" "target" "outputs"}
        profiles (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ key]
                                         (re-matches #"^([A-Za-z0-9_-]+):\s*$" line)]
                                (when-not (contains? reserved key)
                                  {:kind :dbt-profile
                                   :label key
                                   :source-line (inc idx)
                                   :relation :defines}))))
                      distinct)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target]
                                        (re-matches #"^\s*target:\s+(.+?)\s*$" line)]
                               {:kind :dbt-target
                                :label (strip-yaml-scalar target)
                                :source-line (inc idx)
                                :relation :references})))
                     distinct)
        outputs (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ output]
                                        (re-matches #"^\s{4}([A-Za-z0-9_-]+):\s*$" line)]
                               {:kind :dbt-output
                                :label output
                                :source-line (inc idx)
                                :relation :defines})))
                     distinct)
        adapters (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ adapter]
                                         (re-matches #"^\s*type:\s+(.+?)\s*$" line)]
                                {:kind :dbt-adapter
                                 :label (strip-yaml-scalar adapter)
                                 :source-line (inc idx)
                                 :relation :uses})))
                      distinct)]
    {:project-label path
     :facts (vec (concat profiles targets outputs adapters))
     :diagnostics []}))

(def dbt-schema-sections
  {"models" :dbt-model
   "sources" :dbt-source
   "exposures" :dbt-exposure
   "metrics" :dbt-metric
   "semantic_models" :dbt-semantic-model
   "tests" :dbt-test})

(defn- dbt-schema-facts
  [content path]
  (let [lines (vec (str/split-lines content))]
    {:project-label path
     :facts
     (loop [remaining (map-indexed vector lines)
            section nil
            out []]
       (if-let [[idx line] (first remaining)]
         (cond
           (when-let [[_ key] (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*$" line)]
             (contains? dbt-schema-sections key))
           (let [[_ key] (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*$" line)]
             (recur (rest remaining) key out))

           (and section
                (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line))
           (let [[_ value] (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line)
                 kind (get dbt-schema-sections section)]
             (recur (rest remaining)
                    section
                    (conj out {:kind kind
                               :label (strip-yaml-scalar value)
                               :source-line (inc idx)
                               :relation :defines})))

           (and section
                (= "tests" section)
                (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)(?:\s*:.*)?\s*$" line))
           (let [[_ value] (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)(?:\s*:.*)?\s*$" line)]
             (recur (rest remaining)
                    section
                    (conj out {:kind :dbt-test
                               :label value
                               :source-line (inc idx)
                               :relation :defines})))

           (and (not (str/blank? (str/trim line)))
                (zero? (leading-spaces line)))
           (recur (rest remaining) nil out)

           :else
           (recur (rest remaining) section out))
         (vec (distinct out))))
     :diagnostics []}))

(defn- dbt-config-kind
  [path content]
  (let [filename (manifest-name path)]
    (cond
      (contains? #{"dbt_project.yml" "dbt_project.yaml"} filename) :project
      (contains? #{"profiles.yml" "profiles.yaml"} filename) :profile
      (contains? #{"packages.yml" "packages.yaml"} filename) :packages
      (re-find #"(?m)^(?:models|sources|exposures|metrics|semantic_models|tests):\s*$"
               content) :schema
      :else :project)))

(defn extract-dbt
  "Extract bounded dbt project configuration facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [{:keys [project-label facts diagnostics]}
        (case (dbt-config-kind path content)
          :profile (dbt-profile-facts content path)
          :packages {:project-label path
                     :facts (dbt-package-facts content)
                     :diagnostics []}
          :schema (dbt-schema-facts content path)
          (dbt-project-facts content path))
        project-node (generic-node run-id id-scope file-id path
                                   :dbt-project project-label 1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id project-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file :dbt-file)
        diagnostic-rows (mapv #(diagnostic-row run-id
                                               file-id
                                               path
                                               (:stage %)
                                               (:line %)
                                               (:message %))
                              diagnostics)]
    {:nodes (into [project-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics diagnostic-rows}))

(defn extract-codegen-config
  "Extract bounded GraphQL/codegen configuration facts."
  [run-id file]
  (extract-config-facts run-id file :codegen-config :codegen-config-file))

(defn extract-test-config
  "Extract bounded test runner configuration facts."
  [run-id file]
  (extract-config-facts run-id file :test-config :test-config-file))

(defn- quality-tool
  [filename]
  (cond
    (= ".coveragerc" filename) "coverage.py"
    (= "coverage.toml" filename) "coverage.py"
    (contains? #{"mypy.ini" ".mypy.ini"} filename) "mypy"
    (contains? #{"ruff.toml" ".ruff.toml"} filename) "ruff"
    (= "sonar-project.properties" filename) "sonar"
    (= "checkstyle.xml" filename) "checkstyle"
    (= "pmd.xml" filename) "pmd"
    (= "spotbugs-exclude.xml" filename) "spotbugs"
    (contains? #{"phpstan.neon" "phpstan.neon.dist"} filename) "phpstan"
    (= "psalm.xml" filename) "psalm"
    (contains? #{".rubocop.yml" ".rubocop.yaml"} filename) "rubocop"
    (contains? #{".swiftlint.yml" ".swiftlint.yaml"} filename) "swiftlint"
    (contains? #{"detekt.yml" "detekt.yaml"} filename) "detekt"
    :else filename))

(defn- quality-assignment-facts
  [content]
  (->> (properties-assignment-lines content)
       (map (fn [{:keys [key value source-line]}]
              {:kind :quality-setting
               :label (str key "=" (strip-yaml-scalar value))
               :source-line source-line
               :relation :defines}))
       distinct
       vec))

(defn- quality-yaml-list-facts
  [content section-names kind relation]
  (->> (yaml-section-items content section-names)
       (map (fn [{:keys [section value source-line]}]
              {:kind kind
               :label (str section "=" value)
               :source-line source-line
               :relation relation}))
       distinct
       vec))

(defn- quality-yaml-rule-facts
  [content]
  (vec
   (distinct
    (concat
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [[_ key value]
                             (re-matches #"^([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+):\s*(.*?)\s*$"
                                         line)]
                    {:kind :quality-rule
                     :label (if (seq value)
                              (str key "=" (strip-yaml-scalar value))
                              key)
                     :source-line (inc idx)
                     :relation :defines}))))
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [{:keys [indent key value source-line]} (yaml-key-line idx line)]
                    (when (and (zero? indent)
                               (re-find #"/" key)
                               (not= "Enabled" key))
                      {:kind :quality-rule
                       :label (if (seq value)
                                (str key "=" (strip-yaml-scalar value))
                                key)
                       :source-line source-line
                       :relation :defines})))))))))

(defn- quality-neon-facts
  [content]
  (let [section-list-values (fn [section-name]
                              (loop [remaining (map-indexed vector (str/split-lines content))
                                     active? false
                                     section-indent nil
                                     out []]
                                (if-let [[idx line] (first remaining)]
                                  (let [entry (yaml-key-line idx line)
                                        active* (cond
                                                  (and entry
                                                       (= section-name (:key entry))
                                                       (str/blank? (:value entry)))
                                                  true

                                                  (and active?
                                                       entry
                                                       (<= (:indent entry) section-indent))
                                                  false

                                                  :else active?)
                                        section-indent* (cond
                                                          (and entry
                                                               (= section-name (:key entry))
                                                               (str/blank? (:value entry)))
                                                          (:indent entry)

                                                          (not active*) nil
                                                          :else section-indent)
                                        list-value (when (and active*
                                                              (> (leading-spaces line)
                                                                 section-indent*))
                                                     (some->> (re-matches #"^\s*-\s+(.+?)\s*$"
                                                                          line)
                                                              second
                                                              strip-yaml-scalar))]
                                    (recur (rest remaining)
                                           active*
                                           section-indent*
                                           (cond-> out
                                             list-value (conj {:value list-value
                                                               :source-line (inc idx)}))))
                                  (vec (distinct out)))))]
    (vec
     (concat
      (map (fn [{:keys [value source-line]}]
             {:kind :quality-include
              :label (str "includes=" value)
              :source-line source-line
              :relation :references})
           (section-list-values "includes"))
      (map (fn [{:keys [value source-line]}]
             {:kind :quality-path
              :label (str "paths=" value)
              :source-line source-line
              :relation :references})
           (section-list-values "paths"))
      (quality-assignment-facts content)))))

(defn- quality-xml-facts
  [content filename]
  (let [checkstyle-rules (when (= "checkstyle.xml" filename)
                           (->> (re-seq #"<module\s+[^>]*name=\"([^\"]+)\"" content)
                                (map second)
                                (map (fn [rule]
                                       {:kind :quality-rule
                                        :label rule
                                        :source-line 1
                                        :relation :defines}))))
        pmd-rules (when (= "pmd.xml" filename)
                    (->> (re-seq #"<rule\s+[^>]*ref=\"([^\"]+)\"" content)
                         (map second)
                         (map (fn [rule]
                                {:kind :quality-rule
                                 :label rule
                                 :source-line 1
                                 :relation :references}))))
        spotbugs (when (= "spotbugs-exclude.xml" filename)
                   (->> (re-seq #"<(?:Class|Bug)\s+[^>]*(?:name|pattern)=\"([^\"]+)\""
                                content)
                        (map second)
                        (map (fn [rule]
                               {:kind :quality-rule
                                :label rule
                                :source-line 1
                                :relation :defines}))))
        psalm-paths (when (= "psalm.xml" filename)
                      (->> (re-seq #"<directory\s+[^>]*name=\"([^\"]+)\"" content)
                           (map second)
                           (map (fn [path]
                                  {:kind :quality-path
                                   :label path
                                   :source-line 1
                                   :relation :references}))))]
    (vec (distinct (concat checkstyle-rules pmd-rules spotbugs psalm-paths)))))

(defn- quality-config-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)
        tool (quality-tool filename)]
    (vec
     (distinct
      (concat
       [{:kind :quality-tool
         :label tool
         :source-line 1
         :relation :uses}]
       (cond
         (contains? #{"checkstyle.xml" "pmd.xml" "spotbugs-exclude.xml" "psalm.xml"}
                    filename)
         (quality-xml-facts content filename)

         (contains? #{"phpstan.neon" "phpstan.neon.dist"} filename)
         (quality-neon-facts content)

         (contains? #{".rubocop.yml" ".rubocop.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"inherit_from"} :quality-include :references)
          (quality-yaml-list-facts content #{"require"} :quality-plugin :uses)
          (quality-yaml-rule-facts content))

         (contains? #{".swiftlint.yml" ".swiftlint.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"included" "excluded"} :quality-path :references)
          (quality-yaml-list-facts content #{"opt_in_rules" "disabled_rules"} :quality-rule :defines))

         (contains? #{"detekt.yml" "detekt.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"config"} :quality-include :references)
          (quality-assignment-facts content))

         :else
         (quality-assignment-facts content)))))))

(defn extract-quality-config
  "Extract bounded coverage and static-analysis configuration facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :quality-config
                        :quality-config-file
                        (quality-config-facts file)))

(defn- dependabot-update-blocks
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if (re-matches #"^\s*-\s+package-ecosystem:\s+.+?\s*$" line)
        (recur (rest remaining)
               {:source-line (inc idx)
                :lines [[idx line]]}
               (cond-> out current (conj current)))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))

(defn- yaml-block-scalar
  [lines key-name]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*(?:-\\s*)?"
                                                  key-name
                                                  ":\\s+(.+?)\\s*$"))
                                 line)]
            {:value (strip-yaml-scalar value)
             :source-line (inc idx)}))
        lines))

(defn- dependabot-group-pattern-facts
  [lines]
  (loop [remaining lines
         in-groups? false
         groups-indent nil
         current-group nil
         in-patterns? false
         patterns-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)
            indent (leading-spaces line)
            groups-start? (re-matches #"^groups:\s*$" trimmed)
            patterns-start? (and current-group
                                 (re-matches #"^patterns:\s*$" trimmed))
            group-entry (when (and in-groups?
                                   (not in-patterns?)
                                   groups-indent
                                   (> indent groups-indent))
                          (when-let [[_ label] (re-matches #"^([A-Za-z0-9_.-]+):\s*$"
                                                           trimmed)]
                            {:label label
                             :source-line (inc idx)}))
            pattern-value (when (and current-group in-patterns?)
                            (when-let [[_ value] (re-matches #"^-\s+(.+?)\s*$"
                                                             trimmed)]
                              (strip-yaml-scalar value)))]
        (cond
          groups-start?
          (recur (rest remaining) true indent nil false nil out)

          (and in-groups?
               groups-indent
               (<= indent groups-indent)
               (seq trimmed))
          (recur (rest remaining) false nil nil false nil out)

          patterns-start?
          (recur (rest remaining) true groups-indent current-group true indent out)

          group-entry
          (recur (rest remaining)
                 true
                 groups-indent
                 (:label group-entry)
                 false
                 nil
                 (conj out {:kind :dependency-update-group
                            :label (:label group-entry)
                            :source-line (:source-line group-entry)
                            :relation :defines}))

          (and in-patterns? pattern-value)
          (recur (rest remaining)
                 true
                 groups-indent
                 current-group
                 true
                 patterns-indent
                 (conj out {:kind :dependency-update-pattern
                            :label (str current-group ":" pattern-value)
                            :source-line (inc idx)
                            :relation :applies-to}))

          :else
          (recur (rest remaining)
                 in-groups?
                 groups-indent
                 current-group
                 (and in-patterns?
                      patterns-indent
                      (or (str/blank? trimmed)
                          (> indent patterns-indent)))
                 patterns-indent
                 out)))
      out)))

(defn- dependabot-update-facts
  [{:keys [lines source-line]}]
  (let [ecosystem (yaml-block-scalar lines "package-ecosystem")
        directory (yaml-block-scalar lines "directory")
        interval (yaml-block-scalar lines "interval")
        day (yaml-block-scalar lines "day")
        update-label (str (:value ecosystem) ":" (:value directory))]
    (vec (concat
          (when (and (:value ecosystem) (:value directory))
            [{:kind :dependency-update
              :label update-label
              :source-line source-line
              :relation :updates}])
          (when (:value ecosystem)
            [{:kind :dependency-update-ecosystem
              :label (:value ecosystem)
              :source-line (:source-line ecosystem)
              :relation :updates}])
          (when (:value directory)
            [{:kind :dependency-update-directory
              :label (:value directory)
              :source-line (:source-line directory)
              :relation :applies-to}])
          (when (:value interval)
            [{:kind :dependency-update-schedule
              :label (str update-label ":interval=" (:value interval))
              :source-line (:source-line interval)
              :relation :defines}])
          (when (:value day)
            [{:kind :dependency-update-schedule
              :label (str update-label ":day=" (:value day))
              :source-line (:source-line day)
              :relation :defines}])
          (dependabot-group-pattern-facts lines)))))

(defn- dependabot-facts
  [content]
  (->> (dependabot-update-blocks content)
       (mapcat dependabot-update-facts)
       distinct
       vec))

(defn- renovate-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))

(defn- renovate-package-rule-facts
  [rule]
  (let [group-name (:groupName rule)]
    (vec (concat
          (when (string? group-name)
            [{:kind :dependency-update-group
              :label group-name
              :source-line 1
              :relation :defines}])
          (map (fn [manager]
                 {:kind :dependency-update-manager
                  :label (json-label manager)
                  :source-line 1
                  :relation :updates})
               (renovate-array-values (:matchManagers rule)))
          (map (fn [pattern]
                 {:kind :dependency-update-pattern
                  :label (if (string? group-name)
                           (str group-name ":" (json-label pattern))
                           (json-label pattern))
                  :source-line 1
                  :relation :applies-to})
               (concat (renovate-array-values (:matchPackagePatterns rule))
                       (renovate-array-values (:matchPackageNames rule))))))))

(defn- renovate-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat
          (map (fn [preset]
                 {:kind :dependency-update-preset
                  :label (json-label preset)
                  :source-line 1
                  :relation :references})
               (renovate-array-values (:extends m)))
          (mapcat renovate-package-rule-facts
                  (filter map? (renovate-array-values (:packageRules m))))))
    []))

(defn- tool-config-facts
  [{:keys [path content] :as file}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)
        special-facts (cond
                        (re-find #"(^|/)\.github/dependabot\.ya?ml$" path-lower)
                        (dependabot-facts content)

                        (contains? #{"renovate.json" ".renovaterc" ".renovaterc.json"}
                                   filename)
                        (renovate-facts content)

                        :else
                        [])]
    (vec (concat (extract.common/config-facts file) special-facts))))

(defn- editor-json-scalar?
  [value]
  (or (string? value)
      (number? value)
      (boolean? value)))

(defn- editor-json-setting-facts
  [m]
  (->> m
       (keep (fn [[k v]]
               (when (editor-json-scalar? v)
                 {:kind :editor-setting
                  :label (str (name k) "=" (json-label v))
                  :source-line 1
                  :relation :defines})))
       vec))

(defn- editor-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))

(defn- editor-extension-facts
  [extensions]
  (let [extensions (if (map? extensions) extensions {})]
    (vec (concat
          (map (fn [extension]
                 {:kind :editor-extension
                  :label (json-label extension)
                  :source-line 1
                  :relation :references})
               (editor-array-values (:recommendations extensions)))
          (map (fn [extension]
                 {:kind :editor-extension-block
                  :label (json-label extension)
                  :source-line 1
                  :relation :references})
               (editor-array-values (:unwantedRecommendations extensions)))))))

(defn- editor-task-label
  [idx task]
  (let [label (:label task)]
    (if (and (string? label) (seq label))
      label
      (str "task-" (inc idx)))))

(defn- editor-task-command
  [task]
  (let [command (:command task)]
    (when (editor-json-scalar? command)
      (json-label command))))

(defn- editor-task-dependencies
  [task]
  (editor-array-values (:dependsOn task)))

(defn- editor-problem-matchers
  [task]
  (editor-array-values (:problemMatcher task)))

(defn- editor-task-facts
  [tasks]
  (->> tasks
       (keep-indexed
        (fn [idx task]
          (when (map? task)
            (let [label (editor-task-label idx task)
                  command (editor-task-command task)
                  type (:type task)]
              (concat
               [{:kind :editor-task
                 :label label
                 :source-line 1
                 :relation :defines}]
               (when command
                 [{:kind :editor-task-command
                   :label (str label ":" command)
                   :source-line 1
                   :relation :defines}])
               (when (editor-json-scalar? type)
                 [{:kind :editor-task-type
                   :label (str label ":" (json-label type))
                   :source-line 1
                   :relation :defines}])
               (map (fn [matcher]
                      {:kind :editor-problem-matcher
                       :label (str label ":" (json-label matcher))
                       :source-line 1
                       :relation :references})
                    (editor-problem-matchers task))
               (map (fn [dependency]
                      {:kind :editor-task
                       :label (json-label dependency)
                       :source-line 1
                       :relation :references})
                    (editor-task-dependencies task)))))))
       (mapcat identity)
       distinct
       vec))

(defn- editorconfig-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)
              source-line (inc idx)]
          (cond
            (or (str/blank? trimmed)
                (str/starts-with? trimmed "#")
                (str/starts-with? trimmed ";"))
            (recur (rest remaining) section out)

            (re-matches #"^\[.+\]$" trimmed)
            (let [section* (subs trimmed 1 (dec (count trimmed)))]
              (recur (rest remaining)
                     section*
                     (conj out {:kind :editor-profile
                                :label section*
                                :source-line source-line
                                :relation :defines})))

            :else
            (if-let [[_ key value] (re-matches #"^\s*([^:=]+?)\s*[=:]\s*(.*?)\s*$" line)]
              (let [key (str/trim key)
                    value (str/trim value)]
                (recur (rest remaining)
                       section
                       (conj out {:kind :editor-setting
                                  :label (if (seq section)
                                           (str section ":" key "=" value)
                                           (str key "=" value))
                                  :source-line source-line
                                  :relation :defines})))
              (recur (rest remaining) section out))))
        (vec (distinct out))))))

(defn- vscode-settings-facts
  [content]
  (if-let [settings (read-json-map content)]
    (editor-json-setting-facts settings)
    []))

(defn- vscode-extensions-facts
  [content]
  (if-let [extensions (read-json-map content)]
    (editor-extension-facts extensions)
    []))

(defn- vscode-tasks
  [content]
  (let [parsed (read-json-map content)]
    (if (vector? (:tasks parsed)) (:tasks parsed) [])))

(defn- vscode-tasks-facts
  [content]
  (editor-task-facts (vscode-tasks content)))

(defn- workspace-facts
  [content]
  (if-let [workspace (read-json-map content)]
    (vec (concat
          (map (fn [folder]
                 {:kind :workspace-folder
                  :label (json-label (or (:name folder) (:path folder)))
                  :source-line 1
                  :relation :references})
               (filter #(or (:name %) (:path %))
                       (filter map? (editor-array-values (:folders workspace)))))
          (when (map? (:settings workspace))
            (editor-json-setting-facts (:settings workspace)))
          (when (map? (:extensions workspace))
            (editor-extension-facts (:extensions workspace)))
          (when (vector? (:tasks workspace))
            (editor-task-facts (:tasks workspace)))))
    []))

(defn- editor-config-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)]
    (cond
      (= ".editorconfig" filename) (editorconfig-facts content)
      (re-find #"(^|/)\.vscode/settings\.json$" path-lower) (vscode-settings-facts content)
      (re-find #"(^|/)\.vscode/extensions\.json$" path-lower) (vscode-extensions-facts content)
      (re-find #"(^|/)\.vscode/tasks\.json$" path-lower) (vscode-tasks-facts content)
      (str/ends-with? filename ".code-workspace") (workspace-facts content)
      :else [])))

(defn- editor-task-chunks
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)
        parsed (when (or (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                         (str/ends-with? filename ".code-workspace"))
                 (read-json-map content))
        tasks (cond
                (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                (str/ends-with? filename ".code-workspace")
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                :else [])]
    (->> tasks
         (keep-indexed
          (fn [idx task]
            (when (map? task)
              (let [label (editor-task-label idx task)
                    text (or (editor-task-command task)
                             (json/write-json-str task))]
                {:xt/id (chunk-id id-scope path label 1)
                 :file-id file-id
                 :path path
                 :kind :editor-task
                 :label label
                 :text text
                 :tokens (text/tokenize (str label "\n" text))
                 :source-line 1
                 :content-sha (hash/sha256-hex text)
                 :active? true
                 :run-id run-id}))))
         vec)))

(defn- editor-task-dependency-edges
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)
        parsed (when (or (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                         (str/ends-with? filename ".code-workspace"))
                 (read-json-map content))
        tasks (cond
                (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                (str/ends-with? filename ".code-workspace")
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                :else [])]
    (->> tasks
         (map-indexed vector)
         (mapcat
          (fn [[idx task]]
            (when (map? task)
              (let [task-label (editor-task-label idx task)]
                (map (fn [dependency]
                       (edge-row run-id
                                 file-id
                                 path
                                 (node-id id-scope :editor-task task-label)
                                 (node-id id-scope :editor-task (json-label dependency))
                                 :depends-on
                                 :extracted
                                 1))
                     (editor-task-dependencies task))))))
         (remove nil?)
         distinct
         vec)))

(defn extract-editor-config
  "Extract deterministic editor and local development environment facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [root-node (generic-node run-id id-scope file-id path :editor-config-file path 1)
        facts (editor-config-facts file)
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
        chunk-result (extract-text-source run-id file :editor-config-file)]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct (concat fact-edges
                                   (editor-task-dependency-edges run-id file))))
     :chunks (vec (distinct (concat (:chunks chunk-result)
                                    (editor-task-chunks run-id file))))
     :diagnostics []}))

(defn- release-json-scalar-map-facts
  [m kind relation]
  (->> m
       (keep (fn [[k v]]
               (when (editor-json-scalar? v)
                 {:kind kind
                  :label (str (json-key-label k) "=" (json-label v))
                  :source-line 1
                  :relation relation})))
       vec))

(defn- release-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))

(defn- release-branch-facts
  [branches]
  (->> (release-array-values branches)
       (keep (fn [branch]
               (let [label (cond
                             (string? branch) branch
                             (map? branch) (:name branch)
                             :else nil)]
                 (when (seq label)
                   {:kind :release-branch
                    :label (json-label label)
                    :source-line 1
                    :relation :references}))))
       distinct
       vec))

(defn- release-plugin-label
  [plugin]
  (cond
    (string? plugin) plugin
    (vector? plugin) (first plugin)
    :else nil))

(defn- release-plugin-facts
  [plugins]
  (->> (release-array-values plugins)
       (keep release-plugin-label)
       (map (fn [plugin]
              {:kind :release-plugin
               :label (json-label plugin)
               :source-line 1
               :relation :uses}))
       distinct
       vec))

(defn- release-please-package-facts
  [packages]
  (->> packages
       (mapcat
        (fn [[package-path cfg]]
          (let [path-label (json-key-label package-path)
                package-name (when (map? cfg) (:package-name cfg))
                release-type (when (map? cfg) (:release-type cfg))
                changelog-path (when (map? cfg) (:changelog-path cfg))]
            (concat
             [{:kind :release-package
               :label path-label
               :source-line 1
               :relation :references}]
             (when (seq package-name)
               [{:kind :release-package-name
                 :label (str path-label ":" package-name)
                 :source-line 1
                 :relation :defines}])
             (when (seq release-type)
               [{:kind :release-type
                 :label (str path-label ":" release-type)
                 :source-line 1
                 :relation :defines}])
             (when (seq changelog-path)
               [{:kind :release-changelog
                 :label (str path-label ":" changelog-path)
                 :source-line 1
                 :relation :references}])))))
       distinct
       vec))

(defn- release-please-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat
          (when (map? (:packages m))
            (release-please-package-facts (:packages m)))
          (release-json-scalar-map-facts (select-keys m [:release-type :changelog-path])
                                         :release-setting
                                         :defines)))
    []))

(defn- release-manifest-facts
  [content]
  (if-let [m (read-json-map content)]
    (release-json-scalar-map-facts m :release-version :defines)
    []))

(defn- semantic-release-text-facts
  [content]
  (let [plugins (map second
                     (re-seq #"['\"](@semantic-release/[^'\"]+|semantic-release-[^'\"]+)['\"]"
                             content))
        branches (if-let [[_ body] (re-find #"(?s)\bbranches\s*:\s*\[(.*?)\]" content)]
                   (map second (re-seq #"['\"]([^'\"]+)['\"]" body))
                   [])]
    (vec (concat
          (map (fn [plugin]
                 {:kind :release-plugin
                  :label plugin
                  :source-line 1
                  :relation :uses})
               plugins)
          (map (fn [branch]
                 {:kind :release-branch
                  :label branch
                  :source-line 1
                  :relation :references})
               branches)))))

(defn- release-yaml-list-value-label
  [value map-key]
  (let [value (-> value
                  strip-yaml-scalar
                  (str/replace #"^-\s+" "")
                  strip-yaml-scalar)]
    (if-let [[_ label] (and map-key
                            (re-matches (re-pattern (str "^" map-key ":\\s*(.+?)\\s*$"))
                                        value))]
      (strip-yaml-scalar label)
      value)))

(defn- semantic-release-yaml-facts
  [content]
  (let [items (yaml-list-values content ["branches" "plugins"])]
    (vec
     (concat
      (->> items
           (filter #(= "branches" (:section %)))
           (keep (fn [{:keys [value source-line]}]
                   (let [label (release-yaml-list-value-label value "name")]
                     (when (seq label)
                       {:kind :release-branch
                        :label label
                        :source-line source-line
                        :relation :references}))))
           distinct)
      (->> items
           (filter #(= "plugins" (:section %)))
           (keep (fn [{:keys [value source-line]}]
                   (let [label (release-yaml-list-value-label value nil)]
                     (when (seq label)
                       {:kind :release-plugin
                        :label label
                        :source-line source-line
                        :relation :uses}))))
           distinct)))))

(defn- semantic-release-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat (release-branch-facts (:branches m))
                 (release-plugin-facts (:plugins m))))
    (semantic-release-text-facts content)))

(defn- standard-version-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat
          (when (string? (:tagPrefix m))
            [{:kind :release-tag-pattern
              :label (:tagPrefix m)
              :source-line 1
              :relation :defines}])
          (mapcat (fn [type]
                    (when (map? type)
                      (concat
                       (when (string? (:type type))
                         [{:kind :release-type
                           :label (:type type)
                           :source-line 1
                           :relation :defines}])
                       (when (string? (:section type))
                         [{:kind :changelog-section
                           :label (:section type)
                           :source-line 1
                           :relation :defines}]))))
                  (release-array-values (:types m)))))
    []))

(defn- standard-version-yaml-facts
  [content]
  (vec
   (concat
    (when-let [tag-prefix (yaml-top-level-value content "tagPrefix")]
      [{:kind :release-tag-pattern
        :label tag-prefix
        :source-line 1
        :relation :defines}])
    (mapcat
     (fn [block]
       (let [values (block-key-values block)]
         (concat
          (when-let [type (get values "type")]
            [{:kind :release-type
              :label type
              :source-line (:source-line block)
              :relation :defines}])
          (when-let [section (get values "section")]
            [{:kind :changelog-section
              :label section
              :source-line (:source-line block)
              :relation :defines}]))))
     (yaml-top-section-blocks (str/split-lines content) "types")))))

(defn- changeset-front-matter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (first lines))
      (let [end-idx (->> (map-indexed vector (rest lines))
                         (some (fn [[idx line]]
                                 (when (= "---" line)
                                   (inc idx)))))]
        (when end-idx
          {:lines (subvec lines 1 end-idx)
           :body (str/join "\n" (subvec lines (inc end-idx)))})))))

(defn- changeset-facts
  [content]
  (if-let [{:keys [lines]} (changeset-front-matter content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (when-let [[_ package bump]
                       (re-matches #"^\s*['\"]?([^'\"]+?)['\"]?\s*:\s*(major|minor|patch|none)\s*$"
                                   line)]
              [{:kind :release-package
                :label package
                :source-line (+ 2 idx)
                :relation :references}
               {:kind :release-version-change
                :label (str package ":" bump)
                :source-line (+ 2 idx)
                :relation :defines}])))
         (remove nil?)
         distinct
         vec)
    []))

(defn- changeset-config-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat
          (when (string? (:baseBranch m))
            [{:kind :release-branch
              :label (:baseBranch m)
              :source-line 1
              :relation :references}])
          (when (string? (:changelog m))
            [{:kind :release-changelog
              :label (:changelog m)
              :source-line 1
              :relation :references}])
          (release-json-scalar-map-facts (select-keys m [:commit :fixed :linked])
                                         :release-setting
                                         :defines)))
    []))

(defn- changelog-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ heading] (re-matches #"^#{1,3}\s+(.+?)\s*$" line)]
                 {:kind :changelog-section
                  :label heading
                  :source-line (inc idx)
                  :relation :defines})))
       distinct
       vec))

(defn- release-config-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)]
    (cond
      (re-find #"(^|/)\.changeset/config\.json$" path-lower) (changeset-config-facts content)
      (re-find #"(^|/)\.changeset/[^/]+\.md$" path-lower) (changeset-facts content)
      (= "release-please-config.json" filename) (release-please-facts content)
      (= ".release-please-manifest.json" filename) (release-manifest-facts content)
      (contains? #{".releaserc" ".releaserc.json" ".releaserc.yaml" ".releaserc.yml"
                   "semantic-release.config.js" "semantic-release.config.cjs"
                   "semantic-release.config.mjs" "semantic-release.config.ts"}
                 filename) (if (contains? #{".releaserc.yaml" ".releaserc.yml"} filename)
                             (semantic-release-yaml-facts content)
                             (semantic-release-facts content))
      (contains? #{"standard-version.json" ".versionrc" ".versionrc.json"
                   ".versionrc.yaml" ".versionrc.yml"}
                 filename) (if (contains? #{".versionrc.yaml" ".versionrc.yml"} filename)
                             (standard-version-yaml-facts content)
                             (standard-version-facts content))
      (contains? #{"changelog.md" "changes.md"} filename) (changelog-facts content)
      :else [])))

(defn- release-chunks
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)]
    (cond
      (re-find #"(^|/)\.changeset/[^/]+\.md$" path-lower)
      (let [body (or (:body (changeset-front-matter content)) content)
            text (str/trim body)]
        (when (seq text)
          [{:xt/id (chunk-id id-scope path path 1)
            :file-id file-id
            :path path
            :kind :release-change
            :label path
            :text text
            :tokens (text/tokenize text)
            :source-line 1
            :content-sha (hash/sha256-hex text)
            :active? true
            :run-id run-id}]))

      (contains? #{"changelog.md" "changes.md"} filename)
      (let [lines (vec (str/split-lines content))]
        (->> lines
             (map-indexed vector)
             (keep (fn [[idx line]]
                     (when-let [[_ heading] (re-matches #"^#{1,3}\s+(.+?)\s*$" line)]
                       {:label heading
                        :source-line (inc idx)})))
             (map (fn [{:keys [label source-line]}]
                    {:xt/id (chunk-id id-scope path label source-line)
                     :file-id file-id
                     :path path
                     :kind :changelog-section
                     :label label
                     :text label
                     :tokens (text/tokenize label)
                     :source-line source-line
                     :content-sha (hash/sha256-hex label)
                     :active? true
                     :run-id run-id}))
             vec))

      :else [])))

(defn extract-release-config
  "Extract deterministic release/versioning/changelog facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [root-node (generic-node run-id id-scope file-id path :release-config-file path 1)
        facts (release-config-facts file)
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
        chunk-result (extract-text-source run-id file :release-config-file)]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct fact-edges))
     :chunks (vec (distinct (concat (:chunks chunk-result)
                                    (release-chunks run-id file))))
     :diagnostics []}))

(defn- web-framework-config-kind
  [filename]
  (cond
    (re-matches #"next\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "next"
    (re-matches #"remix\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "remix"
    (re-matches #"vite\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "vite"
    (re-matches #"svelte\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "sveltekit"
    (re-matches #"nuxt\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "nuxt"
    (re-matches #"astro\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "astro"
    (= "angular.json" filename) "angular"
    :else nil))

(defn- package-reference?
  [value]
  (and (string? value)
       (not (str/starts-with? value "."))
       (not (str/starts-with? value "/"))))

(defn- web-config-import-facts
  [path content]
  (let [imports (->> (str/split-lines content)
                     (map-indexed #(js-import-targets %1 path %2))
                     (mapcat identity)
                     distinct
                     vec)]
    (vec
     (concat
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-import
              :label target
              :source-line source-line
              :relation :imports})
           imports)
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-plugin
              :label target
              :source-line source-line
              :relation :uses})
           (filter #(package-reference? (:target %)) imports))))))

(defn- web-config-string-array-facts
  [content property-name kind relation]
  (mapv (fn [value]
          {:kind kind
           :label value
           :source-line 1
           :relation relation})
        (docs-config-array-property-values content property-name)))

(defn- angular-project-facts
  [content]
  (if-let [m (read-json-map content)]
    (let [projects (:projects m)]
      (if (map? projects)
        (->> projects
             (mapcat
              (fn [[project-name project]]
                (let [label (json-key-label project-name)
                      architect (when (map? project) (:architect project))]
                  (concat
                   [{:kind :web-framework-project
                     :label label
                     :source-line 1
                     :relation :defines}]
                   (when (string? (:root project))
                     [{:kind :web-framework-root
                       :label (str label ":" (:root project))
                       :source-line 1
                       :relation :references}])
                   (when (string? (:sourceRoot project))
                     [{:kind :web-framework-source-root
                       :label (str label ":" (:sourceRoot project))
                       :source-line 1
                       :relation :references}])
                   (when (map? architect)
                     (mapcat (fn [[target-name target]]
                               (when (and (map? target) (string? (:builder target)))
                                 [{:kind :web-framework-builder
                                   :label (str label ":" (json-key-label target-name) ":" (:builder target))
                                   :source-line 1
                                   :relation :uses}]))
                             architect))))))
             distinct
             vec)
        []))
    []))

(defn- web-config-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)
        framework (web-framework-config-kind filename)]
    (vec
     (concat
      (when framework
        [{:kind :web-framework
          :label framework
          :source-line 1
          :relation :defines}])
      (if (= "angular" framework)
        (angular-project-facts content)
        (web-config-import-facts path content))
      (case framework
        "next"
        (map (fn [value]
               {:kind :web-framework-route
                :label value
                :source-line 1
                :relation :references})
             (distinct (concat (docs-config-property-values content "basePath")
                               (docs-config-property-values content "assetPrefix"))))

        "vite"
        (map (fn [value]
               {:kind :web-framework-route
                :label value
                :source-line 1
                :relation :references})
             (docs-config-property-values content "base"))

        "sveltekit"
        (map (fn [value]
               {:kind :web-framework-adapter
                :label value
                :source-line 1
                :relation :uses})
             (docs-config-property-values content "adapter"))

        "nuxt"
        (web-config-string-array-facts content "modules" :web-framework-module :uses)

        "astro"
        (vec (concat
              (web-config-string-array-facts content "integrations" :web-framework-plugin :uses)
              (map (fn [value]
                     {:kind :web-framework-route
                      :label value
                      :source-line 1
                      :relation :references})
                   (docs-config-property-values content "base"))))

        [])))))

(defn- strip-route-extension
  [value]
  (str/replace value #"\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts|svelte|vue|astro)$" ""))

(defn- route-segment-label
  [segment]
  (cond
    (= "index" segment) nil
    (or (str/starts-with? segment "(")
        (str/starts-with? segment "@")) nil
    (re-matches #"\[\[\.\.\..+\]\]" segment)
    (str "{..." (subs segment 5 (- (count segment) 2)) "}")
    (re-matches #"\[\.\.\..+\]" segment)
    (str "{..." (subs segment 4 (dec (count segment))) "}")
    (re-matches #"\[.+\]" segment)
    (str "{" (subs segment 1 (dec (count segment))) "}")
    :else segment))

(defn- route-path-from-segments
  [segments]
  (let [segments (->> segments
                      (map route-segment-label)
                      (remove str/blank?)
                      vec)]
    (if (seq segments)
      (str "/" (str/join "/" segments))
      "/")))

(defn- remix-route-segment-label
  [segment]
  (cond
    (or (= "_index" segment)
        (str/blank? segment)) nil
    (= "$" segment) "{...splat}"
    (str/starts-with? segment "$") (str "{" (subs segment 1) "}")
    (str/starts-with? segment "_") nil
    (str/ends-with? segment "_") (subs segment 0 (dec (count segment)))
    :else segment))

(defn- remix-route-path
  [route-part]
  (let [segments (->> (str/split route-part #"\.")
                      (map remix-route-segment-label)
                      (remove str/blank?)
                      vec)]
    (if (seq segments)
      (str "/" (str/join "/" segments))
      "/")))

(defn- angular-router-source?
  [content]
  (and (re-find #"(?m)^\s*import\s+\{?[^;\n]*\bRoutes\b[^;\n]*\}?\s+from\s+['\"]@angular/router['\"]" content)
       (or (re-find #"(?m)\bRoutes\s*=" content)
           (re-find #"(?m)\bprovideRouter\s*\(" content))
       (re-find #"(?m)\bpath\s*:\s*['\"]" content)))

(defn- angular-route-label
  [path-value]
  (let [path-value (str/replace (str/trim (str path-value)) #"^/+" "")]
    (cond
      (str/blank? path-value) "/"
      (= "**" path-value) "/{...wildcard}"
      :else (str "/"
                 (->> (str/split path-value #"/")
                      (map (fn [segment]
                             (if (str/starts-with? segment ":")
                               (str "{" (subs segment 1) "}")
                               segment)))
                      (str/join "/"))))))

(defn- char-count
  [s ch]
  (count (filter #(= ch %) s)))

(defn- angular-route-blocks
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [starts-route? (and (nil? current)
                                 (re-find #"\bpath\s*:\s*['\"]" line))
              current* (cond
                         starts-route?
                         {:source-line (inc idx)
                          :depth (let [delta (- (char-count line \{)
                                                (char-count line \}))]
                                   (if (and (zero? delta)
                                            (not (str/includes? line "}")))
                                     1
                                     delta))
                          :lines [line]}

                         current
                         (-> current
                             (update :depth + (- (char-count line \{)
                                                 (char-count line \})))
                             (update :lines conj line))

                         :else nil)]
          (if (and current* (<= (:depth current*) 0))
            (recur (rest remaining) nil (conj out current*))
            (recur (rest remaining) current* out)))
        (cond-> out current (conj current))))))

(defn- angular-route-facts
  [content path]
  (when (angular-router-source? content)
    (vec
     (concat
      [{:kind :web-framework
        :label "angular"
        :source-line 1
        :relation :defines}]
      (mapcat
       (fn [{:keys [source-line lines]}]
         (let [block (str/join "\n" lines)
               route-label (some->> (re-find #"\bpath\s*:\s*['\"]([^'\"]*)['\"]" block)
                                    second
                                    angular-route-label)
               component (some->> (re-find #"\bcomponent\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)" block)
                                  second)
               redirect (some->> (re-find #"\bredirectTo\s*:\s*['\"]([^'\"]+)['\"]" block)
                                 second)
               imports (->> lines
                            (map-indexed #(js-import-targets (+ source-line %1 -1) path %2))
                            (mapcat identity)
                            distinct)]
           (when route-label
             (concat
              [{:kind :web-framework-route
                :label route-label
                :source-line source-line
                :relation :defines}]
              (when component
                [{:kind :web-framework-component
                  :label (str route-label ":" component)
                  :source-line source-line
                  :relation :references}])
              (when redirect
                [{:kind :web-framework-route-redirect
                  :label (str route-label ":" (angular-route-label redirect))
                  :source-line source-line
                  :relation :references}])
              (map (fn [{:keys [target source-line]}]
                     {:kind :web-framework-import
                      :label target
                      :source-line source-line
                      :relation :imports})
                   imports)))))
       (angular-route-blocks content))))))

(defn- ember-router-source?
  [content]
  (and (re-find #"(?m)^\s*import\s+.+\s+from\s+['\"]@ember/routing/router['\"]" content)
       (re-find #"(?m)\bRouter\.map\s*\(" content)
       (re-find #"(?m)\bthis\.route\s*\(" content)))

(defn- ember-config-source?
  [content]
  (and (re-find #"(?m)\bmodulePrefix\s*:" content)
       (re-find #"(?m)\brootURL\s*:" content)
       (re-find #"(?m)\blocationType\s*:" content)))

(defn- ember-route-facts
  [content path]
  (when (ember-router-source? content)
    (let [lines (str/split-lines content)]
      (vec
       (concat
        [{:kind :web-framework
          :label "ember"
          :source-line 1
          :relation :defines}]
        (->> lines
             (map-indexed vector)
             (keep
              (fn [[idx line]]
                (when-let [[_ route-name path-option]
                           (re-find #"\bthis\.route\s*\(\s*['\"]([^'\"]+)['\"](?:\s*,\s*\{[^}]*\bpath\s*:\s*['\"]([^'\"]+)['\"])?"
                                    line)]
                  {:kind :web-framework-route
                   :label (angular-route-label (or path-option route-name))
                   :source-line (inc idx)
                   :relation :defines})))
             distinct)
        (->> lines
             (map-indexed #(js-import-targets %1 path %2))
             (mapcat identity)
             (map (fn [{:keys [target source-line]}]
                    {:kind :web-framework-import
                     :label target
                     :source-line source-line
                     :relation :imports}))
             distinct))))))

(defn- ember-config-facts
  [content]
  (when (ember-config-source? content)
    (vec
     (concat
      [{:kind :web-framework
        :label "ember"
        :source-line 1
        :relation :defines}]
      (map (fn [value]
             {:kind :web-framework-project
              :label value
              :source-line 1
              :relation :defines})
           (docs-config-property-values content "modulePrefix"))
      (map (fn [value]
             {:kind :web-framework-route
              :label value
              :source-line 1
              :relation :references})
           (docs-config-property-values content "rootURL"))
      (map (fn [value]
             {:kind :web-framework-setting
              :label (str "locationType:" value)
              :source-line 1
              :relation :defines})
           (docs-config-property-values content "locationType"))))))

(defn- web-route-info
  [path]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (re-find #"(?:^|/)app/(?:.+/)?(?:page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
               path-lower)
      (let [[_ route-part file-role] (re-find #"(?:^|/)app/(.*)/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                              path-lower)
            [_ file-role-root] (re-find #"(?:^|/)app/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                        path-lower)
            file-role (or file-role file-role-root)
            route-part (or route-part "")
            segments (if (seq route-part) (str/split route-part #"/") [])]
        {:framework "next"
         :route (route-path-from-segments segments)
         :role file-role})

      (re-find #"(?:^|/)pages/.+\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)pages/(.+)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                    path-lower)]
        {:framework "next"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)src/routes/(?:.+/)?\+(?:page|layout|server)\.svelte$" path-lower)
      (let [[_ route-part file-role] (re-find #"(?:^|/)src/routes/(.*)/\+(page|layout|server)\.svelte$"
                                              path-lower)
            [_ file-role-root] (re-find #"(?:^|/)src/routes/\+(page|layout|server)\.svelte$"
                                        path-lower)
            file-role (or file-role file-role-root)
            route-part (or route-part "")
            segments (if (seq route-part) (str/split route-part #"/") [])]
        {:framework "sveltekit"
         :route (route-path-from-segments segments)
         :role file-role})

      (re-find #"(?:^|/)pages/.+\.vue$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)pages/(.+)\.vue$" path-lower)]
        {:framework "nuxt"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)src/pages/.+\.astro$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)src/pages/(.+)\.astro$" path-lower)]
        {:framework "astro"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)app/routes/.+\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)app/routes/(.+)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                    path-lower)]
        {:framework "remix"
         :route (remix-route-path (strip-route-extension route-part))
         :role "route-module"})

      :else nil)))

(defn- web-route-facts
  [{:keys [path content]}]
  (if-let [{:keys [framework route role]} (web-route-info path)]
    (vec
     (concat
      [{:kind :web-framework
        :label framework
        :source-line 1
        :relation :defines}
       {:kind :web-framework-route
        :label route
        :source-line 1
        :relation :defines}
       {:kind (case role
                "layout" :web-framework-layout
                "route" :web-framework-route-handler
                "server" :web-framework-route-handler
                "route-module" :web-framework-page
                :web-framework-page)
        :label (str route ":" role)
        :source-line 1
        :relation :defines}]
      (when (= "remix" framework)
        (->> (str/split-lines content)
             (map-indexed vector)
             (mapcat
              (fn [[idx line]]
                (cond
                  (re-find #"^\s*export\s+(?:async\s+)?function\s+loader\b|^\s*export\s+const\s+loader\b"
                           line)
                  [{:kind :web-framework-loader
                    :label (str route ":loader")
                    :source-line (inc idx)
                    :relation :defines}]

                  (re-find #"^\s*export\s+(?:async\s+)?function\s+action\b|^\s*export\s+const\s+action\b"
                           line)
                  [{:kind :web-framework-action
                    :label (str route ":action")
                    :source-line (inc idx)
                    :relation :defines}]

                  :else [])))))
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-import
              :label target
              :source-line source-line
              :relation :imports})
           (->> (str/split-lines content)
                (map-indexed #(js-import-targets %1 path %2))
                (mapcat identity)
                distinct))))
    []))

(defn- web-framework-facts
  [{:keys [path content] :as file}]
  (cond
    (web-route-info path) (web-route-facts file)
    (angular-router-source? content) (angular-route-facts content path)
    (ember-router-source? content) (ember-route-facts content path)
    (ember-config-source? content) (ember-config-facts content)
    :else (web-config-facts file)))

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
  (when (or (web-route-info path)
            (angular-router-source? content)
            (ember-router-source? content)
            (ember-config-source? content))
    (case (web-framework-base-kind path)
      :typescript (extract.source-js/extract-js-family run-id (assoc file :kind :typescript))
      :javascript (extract.source-js/extract-js-family run-id (assoc file :kind :javascript))
      :svelte (extract-sfc run-id (assoc file :kind :svelte))
      :astro (extract-astro run-id (assoc file :kind :astro))
      :vue (extract-sfc run-id (assoc file :kind :vue))
      nil)))

(defn extract-web-framework
  "Extract deterministic web framework config and file-backed route facts."
  [run-id file]
  (let [web-result (extract-format-facts run-id
                                         file
                                         :web-framework-file
                                         :web-framework-file
                                         (web-framework-facts file))
        base-result (web-framework-base-result run-id file)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result) (:nodes web-result))))
       :edges (vec (distinct (concat (:edges base-result) (:edges web-result))))
       :chunks (vec (distinct (concat (:chunks base-result) (:chunks web-result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics web-result)))}
      web-result)))

(defn extract-tool-config
  "Extract bounded lint/format/tool configuration facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :tool-config
                        :tool-config-file
                        (tool-config-facts file)))

(defn- storybook-quoted-values
  [content]
  (mapv second (re-seq #"['\"]([^'\"]+)['\"]" content)))

(defn- storybook-array-values
  [content key-name]
  (if-let [[_ body] (re-find (re-pattern (str "(?s)" key-name "\\s*:\\s*\\[(.*?)\\]"))
                             content)]
    (storybook-quoted-values body)
    []))

(defn- storybook-main-facts
  [content]
  (let [story-patterns (storybook-array-values content "stories")
        addons (storybook-array-values content "addons")
        framework (or (some->> (re-find #"framework\s*:\s*['\"]([^'\"]+)['\"]"
                                        content)
                               second)
                      (some->> (re-find #"framework\s*:\s*\{[^}]*name\s*:\s*['\"]([^'\"]+)['\"]"
                                        content)
                               second))]
    (vec (concat
          (map (fn [pattern]
                 {:kind :storybook-story-pattern
                  :label pattern
                  :source-line 1
                  :relation :references})
               story-patterns)
          (map (fn [addon]
                 {:kind :storybook-addon
                  :label addon
                  :source-line 1
                  :relation :references})
               addons)
          (when framework
            [{:kind :storybook-framework
              :label framework
              :source-line 1
              :relation :uses}])))))

(defn- storybook-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :storybook-import
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))

(defn- storybook-story-facts
  [path content]
  (let [title (some->> (re-find #"title\s*:\s*['\"]([^'\"]+)['\"]" content)
                       second)
        component (some->> (re-find #"component\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)"
                                    content)
                           second)
        tags (storybook-array-values content "tags")
        stories (mapv second
                      (re-seq #"(?m)^\s*export\s+const\s+([A-Za-z_$][A-Za-z0-9_$]*)\b"
                              content))]
    (vec (concat
          (storybook-import-facts path content)
          (when title
            [{:kind :storybook-title
              :label title
              :source-line 1
              :relation :defines}])
          (when component
            [{:kind :storybook-component
              :label component
              :source-line 1
              :relation :references}])
          (map (fn [tag]
                 {:kind :storybook-tag
                  :label tag
                  :source-line 1
                  :relation :defines})
               tags)
          (map (fn [story]
                 {:kind :storybook-story
                  :label story
                  :source-line 1
                  :relation :defines})
               stories)))))

(defn- storybook-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (manifest-name path)]
    (cond
      (re-find #"(^|/)\.storybook/(?:main|preview|manager)\.(?:js|cjs|mjs|ts|tsx)$"
               path-lower)
      (storybook-main-facts content)

      (re-matches #".+\.stories\.(?:js|jsx|ts|tsx|mdx)$" filename)
      (storybook-story-facts path content)

      :else [])))

(defn extract-storybook
  "Extract bounded Storybook config and story facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :storybook-file
                        :storybook-file
                        (storybook-facts file)))

(defn- docs-config-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "\\b"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*:\\s*['\"]([^'\"]+)['\"]"))
               content)
       (map second)
       (remove str/blank?)
       distinct
       vec))

(defn- docs-config-title-facts
  [content]
  (mapv (fn [value]
          {:kind :docs-title
           :label value
           :source-line 1
           :relation :defines})
        (docs-config-property-values content "title")))

(defn- docs-config-route-facts
  [content]
  (->> (concat (docs-config-property-values content "to")
               (docs-config-property-values content "href")
               (docs-config-property-values content "baseUrl")
               (docs-config-property-values content "id"))
       distinct
       (mapv (fn [value]
               {:kind :docs-route
                :label value
                :source-line 1
                :relation :references}))))

(defn- docs-config-reference-facts
  [content]
  (mapv (fn [value]
          {:kind :docs-config-reference
           :label value
           :source-line 1
           :relation :references})
        (docs-config-property-values content "sidebarPath")))

(defn- docs-config-plugin-facts
  [content]
  (->> (concat (docs-config-property-values content "preset")
               (docs-config-property-values content "plugin")
               (docs-config-property-values content "name"))
       distinct
       (mapv (fn [value]
               {:kind :docs-plugin
                :label value
                :source-line 1
                :relation :uses}))))

(defn- docs-config-array-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "\\b"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*:\\s*\\[(.*?)\\]"))
               content)
       (mapcat (fn [[_ body]] (storybook-quoted-values body)))
       (remove str/blank?)
       distinct
       vec))

(defn- content-config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (mapv (fn [{:keys [target source-line]}]
               {:kind :content-import
                :label target
                :source-line source-line
                :relation :imports}))
       distinct
       vec))

(defn- content-config-export-body
  [content]
  (some->> (re-find #"(?s)export\s+const\s+collections\s*=\s*\{(.*?)\}\s*;?"
                    content)
           second))

(defn- content-config-export-collections
  [content]
  (if-let [body (content-config-export-body content)]
    (let [segments (->> (str/split body #",")
                        (map str/trim)
                        (remove str/blank?))]
      (->> segments
           (keep (fn [segment]
                   (let [segment (str/replace segment #"\s+" " ")]
                     (cond
                       (re-find #":" segment)
                       (some->> (re-find #"^['\"]?([A-Za-z0-9_-]+)['\"]?\s*:"
                                         segment)
                                second)

                       (re-matches (re-pattern (extract.common/js-identifier)) segment)
                       segment

                       :else nil))))
           distinct
           vec))
    []))

(defn- content-config-define-collection-forms
  [content]
  (let [line-starts (line-start-offsets content)]
    (->> (re-seq (re-pattern (str "\\b(?:const|let|var)\\s+("
                                  (extract.common/js-identifier)
                                  ")\\s*=\\s*defineCollection\\s*\\("))
                 content)
         (keep (fn [[match collection-name]]
                 (when-let [match-idx (str/index-of content match)]
                   (let [start (+ match-idx (str/index-of match "defineCollection"))
                         line (count (take-while #(<= % match-idx)
                                                 line-starts))]
                     {:collection collection-name
                      :source-line line
                      :form (or (balanced-form content start) "")}))))
         vec)))

(defn- content-config-loader-facts
  [{:keys [collection source-line form]}]
  (let [loader (some->> (re-find #"\bloader\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\("
                                 form)
                        second)
        direct-loader-source (some->> (re-find #"\bloader\s*:\s*[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*['\"]([^'\"]+)['\"]"
                                               form)
                                      second)]
    (vec
     (concat
      (when loader
        [{:kind :content-loader
          :label (str collection ":" loader)
          :source-line source-line
          :relation :uses}])
      (map (fn [value]
             {:kind :content-source
              :label value
              :source-line source-line
              :relation :references})
           (distinct
            (concat (docs-config-property-values form "base")
                    (docs-config-property-values form "pattern")
                    (when direct-loader-source [direct-loader-source]))))))))

(defn- content-config-schema-field-facts
  [{:keys [collection source-line form]}]
  (if-let [body (some->> (re-find #"(?s)\bschema\s*:\s*z\.object\s*\(\s*\{(.*?)\}\s*\)"
                                  form)
                         second)]
    (->> (re-seq #"(?m)^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:" body)
         (map second)
         distinct
         (mapv (fn [field-name]
                 {:kind :content-schema-field
                  :label (str collection "." field-name)
                  :source-line source-line
                  :relation :defines})))
    []))

(defn- astro-content-config-facts
  [{:keys [path content]}]
  (let [forms (content-config-define-collection-forms content)
        declared-collections (set (map :collection forms))
        exported-collections (content-config-export-collections content)
        collection-labels (->> (concat declared-collections exported-collections)
                               distinct
                               sort)]
    (vec
     (concat
      (content-config-import-facts path content)
      (map (fn [collection]
             {:kind :content-collection
              :label collection
              :source-line 1
              :relation :defines})
           collection-labels)
      (mapcat content-config-loader-facts forms)
      (mapcat content-config-schema-field-facts forms)))))

(defn- vitepress-config-path?
  [path]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (boolean
     (or (re-find #"(^|/)\.vitepress/config\.(?:js|mjs|mts|cts|ts)$" path-lower)
         (re-find #"(^|/)\.vitepress/config/index\.(?:js|mjs|mts|cts|ts)$"
                  path-lower)))))

(defn- vitepress-config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (mapv (fn [{:keys [target source-line]}]
               {:kind :docs-config-import
                :label target
                :source-line source-line
                :relation :imports}))
       distinct
       vec))

(defn- vitepress-config-facts
  [{:keys [path content]}]
  (vec
   (concat
    (vitepress-config-import-facts path content)
    (docs-config-title-facts content)
    (map (fn [value]
           {:kind :docs-nav-entry
            :label value
            :source-line 1
            :relation :defines})
         (docs-config-property-values content "text"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (docs-config-property-values content "base")
                           (docs-config-property-values content "link"))))
    (map (fn [value]
           {:kind :docs-search-provider
            :label value
            :source-line 1
            :relation :uses})
         (docs-config-property-values content "provider")))))

(defn- python-config-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "(?m)^\\s*"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*=\\s*['\"]([^'\"]+)['\"]"))
               content)
       (map second)
       (remove str/blank?)
       distinct
       vec))

(defn- python-config-array-values
  [content property-name]
  (if-let [[_ body] (re-find (re-pattern (str "(?ms)^\\s*"
                                              (java.util.regex.Pattern/quote property-name)
                                              "\\s*=\\s*\\[(.*?)\\]"))
                             content)]
    (->> (storybook-quoted-values body)
         (remove str/blank?)
         distinct
         vec)
    []))

(defn- sphinx-config-facts
  [content]
  (vec
   (concat
    (map (fn [value]
           {:kind :docs-title
            :label value
            :source-line 1
            :relation :defines})
         (python-config-property-values content "project"))
    (map (fn [value]
           {:kind :docs-extension
            :label value
            :source-line 1
            :relation :uses})
         (python-config-array-values content "extensions"))
    (map (fn [value]
           {:kind :docs-theme
            :label value
            :source-line 1
            :relation :uses})
         (python-config-property-values content "html_theme"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (python-config-property-values content "root_doc")
                           (python-config-property-values content "master_doc"))))
    (map (fn [value]
           {:kind :docs-config-reference
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (python-config-array-values content "templates_path")
                           (python-config-array-values content "html_static_path")))))))

(defn- nextra-next-config-facts
  [path content]
  (let [imports (vitepress-config-import-facts path content)
        uses-nextra? (or (some #(= "nextra" (:label %)) imports)
                         (re-find #"(?m)\bnextra\s*\(" content))]
    (vec
     (concat
      imports
      (when uses-nextra?
        [{:kind :docs-plugin
          :label "nextra"
          :source-line 1
          :relation :uses}])
      (map (fn [value]
             {:kind :docs-route
              :label value
              :source-line 1
              :relation :references})
           (docs-config-property-values content "contentDirBasePath"))
      (map (fn [value]
             {:kind :docs-locale
              :label value
              :source-line 1
              :relation :defines})
           (storybook-array-values content "locales"))
      (map (fn [value]
             {:kind :docs-locale-default
              :label value
              :source-line 1
              :relation :defines})
           (docs-config-property-values content "defaultLocale"))))))

(defn- nextra-meta-object-entry-facts
  [key body source-line]
  (vec
   (concat
    [{:kind :docs-meta-entry
      :label key
      :source-line source-line
      :relation :defines}]
    (map (fn [value]
           {:kind :docs-sidebar-entry
            :label value
            :source-line source-line
            :relation :defines})
         (docs-config-property-values body "title"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line source-line
            :relation :references})
         (docs-config-property-values body "href"))
    (map (fn [value]
           {:kind :docs-meta-type
            :label (str key ":" value)
            :source-line source-line
            :relation :uses})
         (docs-config-property-values body "type"))
    (map (fn [value]
           {:kind :docs-meta-display
            :label (str key ":" value)
            :source-line source-line
            :relation :uses})
         (docs-config-property-values body "display")))))

(defn- nextra-meta-facts
  [content]
  (let [scalar-entries (->> (re-seq #"(?m)^\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*:\s*['\"]([^'\"]+)['\"]"
                                    content)
                            (remove (fn [[_ key _]]
                                      (contains? #{"display" "href" "theme" "title" "type"}
                                                 key)))
                            (mapcat (fn [[_ key title]]
                                      [{:kind :docs-meta-entry
                                        :label key
                                        :source-line 1
                                        :relation :defines}
                                       {:kind :docs-sidebar-entry
                                        :label title
                                        :source-line 1
                                        :relation :defines}]))
                            vec)
        object-entries (->> (re-seq #"(?ms)^\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*:\s*\{(.*?)^\s*\},?"
                                    content)
                            (mapcat (fn [[_ key body]]
                                      (nextra-meta-object-entry-facts key body 1)))
                            vec)]
    (vec (distinct (concat scalar-entries object-entries)))))

(defn- docs-sidebar-facts
  [content]
  (vec
   (concat
    (map (fn [value]
           {:kind :docs-sidebar-entry
            :label value
            :source-line 1
            :relation :defines})
         (docs-config-property-values content "label"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (docs-config-property-values content "id"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (docs-config-array-property-values content "items")))))

(defn- mkdocs-line-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            section* (cond
                       (and entry (zero? (:indent entry)) (str/blank? (:value entry)))
                       (:key entry)

                       (and entry (zero? (:indent entry)))
                       nil

                       :else section)
            site-name (when (and entry
                                 (zero? (:indent entry))
                                 (= "site_name" (:key entry))
                                 (seq (:value entry)))
                        {:kind :docs-title
                         :label (strip-yaml-scalar (:value entry))
                         :source-line (:source-line entry)
                         :relation :defines})
            nav-entry (when (and (= "nav" section)
                                 (re-matches #"^\s*-\s+[^:]+:.*$" line))
                        (let [[_ label route]
                              (re-matches #"^\s*-\s+([^:]+):\s*(.*?)\s*$" line)]
                          [{:kind :docs-nav-entry
                            :label (strip-yaml-scalar label)
                            :source-line (inc idx)
                            :relation :defines}
                           (when (seq route)
                             {:kind :docs-route
                              :label (strip-yaml-scalar route)
                              :source-line (inc idx)
                              :relation :references})]))
            plugin-entry (when (and (= "plugins" section)
                                    (re-matches #"^\s*-\s+.+$" line))
                           {:kind :docs-plugin
                            :label (-> line
                                       (str/replace #"^\s*-\s+" "")
                                       strip-yaml-scalar)
                            :source-line (inc idx)
                            :relation :uses})
            theme-entry (when (and (= "theme" section)
                                   entry
                                   (= "name" (:key entry))
                                   (seq (:value entry)))
                          {:kind :docs-theme
                           :label (strip-yaml-scalar (:value entry))
                           :source-line (:source-line entry)
                           :relation :uses})]
        (recur (rest remaining)
               section*
               (cond-> out
                 site-name (conj site-name)
                 nav-entry (into (remove nil? nav-entry))
                 plugin-entry (conj plugin-entry)
                 theme-entry (conj theme-entry))))
      (vec (distinct out)))))

(def ^:private nextra-next-config-filenames
  #{"next.config.cjs" "next.config.js" "next.config.mjs"
    "next.config.mts" "next.config.cts" "next.config.ts"})

(def ^:private nextra-meta-filenames
  #{"_meta.js" "_meta.jsx" "_meta.mjs" "_meta.mts" "_meta.cts"
    "_meta.ts" "_meta.tsx"})

(def ^:private js-docs-config-filenames
  #{"config.js" "config.mjs" "config.mts" "config.cts" "config.ts"
    "index.js" "index.mjs" "index.mts" "index.cts" "index.ts"})

(def ^:private astro-content-config-filenames
  #{"content.config.js" "content.config.mjs" "content.config.mts"
    "content.config.cts" "content.config.ts"})

(def ^:private docusaurus-config-filenames
  #{"docusaurus.config.js" "docusaurus.config.cjs" "docusaurus.config.mjs"
    "docusaurus.config.mts" "docusaurus.config.cts" "docusaurus.config.ts"})

(def ^:private sidebar-config-filenames
  #{"sidebars.js" "sidebars.mjs" "sidebars.mts" "sidebars.cts"
    "sidebars.ts"})

(defn- astro-content-config-path?
  [path]
  (boolean
   (re-find #"(^|/)src/content/config\.(?:js|mjs|mts|cts|ts)$"
            (str/replace (str/lower-case (str path)) "\\" "/"))))

(defn- docs-config-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (cond
      (contains? nextra-next-config-filenames filename)
      (nextra-next-config-facts path content)

      (contains? nextra-meta-filenames filename)
      (nextra-meta-facts content)

      (= "conf.py" filename)
      (sphinx-config-facts content)

      (contains? js-docs-config-filenames filename)
      (cond
        (vitepress-config-path? path)
        (vitepress-config-facts {:path path :content content})

        (astro-content-config-path? path)
        (astro-content-config-facts {:path path :content content})

        :else [])

      (contains? astro-content-config-filenames filename)
      (astro-content-config-facts {:path path :content content})

      (contains? docusaurus-config-filenames filename)
      (vec (concat (docs-config-title-facts content)
                   (docs-config-route-facts content)
                   (docs-config-reference-facts content)
                   (docs-config-plugin-facts content)))

      (contains? sidebar-config-filenames filename)
      (docs-sidebar-facts content)

      (contains? #{"mkdocs.yml" "mkdocs.yaml"} filename)
      (mkdocs-line-facts content)

      :else [])))

(defn extract-docs-config
  "Extract deterministic docs/content-system configuration facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :docs-config
                        :docs-config-file
                        (docs-config-facts file)))

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





























































(defn- astro-frontmatter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (str/trim (first lines)))
      (let [end-idx (->> lines
                         (map-indexed vector)
                         (drop 1)
                         (some (fn [[idx line]]
                                 (when (= "---" (str/trim line))
                                   idx))))]
        (when end-idx
          (str/join "\n" (subvec lines 1 end-idx)))))))

(defn extract-astro
  "Extract bounded component and frontmatter import facts from an Astro file."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [module-name (source-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        frontmatter (or (astro-frontmatter content) "")
        imports (->> (str/split-lines frontmatter)
                     (map-indexed #(js-import-targets %1 path %2))
                     (mapcat identity)
                     distinct
                     vec)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           imports)
        chunk-result (extract-text-source run-id file :astro-file)]
    {:nodes [ns-node]
     :edges import-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- sfc-start-tag
  [tag idx line]
  (when-let [[_ attrs tail] (re-matches (re-pattern (str "(?i)^\\s*<"
                                                         tag
                                                         "\\b([^>]*)>(.*)$"))
                                        line)]
    {:attrs (str/trim attrs)
     :tail tail
     :source-line (inc idx)}))

(defn- sfc-end-tag-pattern
  [tag]
  (re-pattern (str "(?i)</" tag ">")))

(defn- sfc-blocks
  [tag content]
  (let [lines (vec (str/split-lines content))
        end-pattern (sfc-end-tag-pattern tag)]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (if current
          (let [[before close?] (if-let [match (re-find end-pattern line)]
                                  [(subs line 0 (str/index-of line match)) true]
                                  [line false])
                current* (update current :lines conj before)]
            (if close?
              (recur (rest remaining) nil (conj out current*))
              (recur (rest remaining) current* out)))
          (if-let [{:keys [attrs tail source-line]} (sfc-start-tag tag idx line)]
            (if-let [match (re-find end-pattern tail)]
              (let [text (subs tail 0 (str/index-of tail match))]
                (recur (rest remaining)
                       nil
                       (conj out {:attrs attrs
                                  :source-line source-line
                                  :lines [text]})))
              (recur (rest remaining)
                     {:attrs attrs
                      :source-line source-line
                      :lines [tail]}
                     out))
            (recur (rest remaining) nil out)))
        (cond-> out current (conj current))))))

(defn- sfc-script-imports
  [path scripts]
  (->> scripts
       (mapcat (fn [{:keys [source-line lines]}]
                 (map-indexed
                  (fn [idx line]
                    (js-import-targets (+ (dec source-line) idx) path line))
                  lines)))
       (mapcat identity)
       distinct
       vec))

(defn- sfc-script-definitions
  [scripts]
  (->> scripts
       (mapcat (fn [{:keys [source-line lines]}]
                 (keep-indexed
                  (fn [idx line]
                    (extract.common/js-definition-line (+ (dec source-line) idx) line))
                  lines)))
       vec))

(defn- sfc-block-chunks
  [run-id id-scope file-id path chunk-kind label-prefix blocks]
  (mapv (fn [{:keys [source-line lines]}]
          (let [block-text (str/join "\n" lines)
                label (str label-prefix " " source-line)
                line-count (count (str/split-lines block-text))]
            (cond-> {:xt/id (chunk-id id-scope path label source-line)
                     :file-id file-id
                     :path path
                     :kind chunk-kind
                     :label label
                     :text block-text
                     :tokens (text/tokenize (str label "\n" block-text))
                     :source-line source-line
                     :active? true
                     :run-id run-id}
              (pos? line-count) (assoc :end-line (+ source-line line-count -1))
              (seq block-text) (assoc :content-sha (hash/sha256-hex block-text)))))
        blocks))

(defn extract-sfc
  "Extract bounded component, script import, and script declaration facts."
  [run-id {:keys [id-scope file-id path content kind] :as file}]
  (let [module-name (source-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        component-node (generic-node run-id id-scope file-id path :component module-name 1)
        scripts (sfc-blocks "script" content)
        templates (sfc-blocks "template" content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   (sfc-script-definitions scripts))
        component-edge (edge-row run-id
                                 file-id
                                 path
                                 (:xt/id ns-node)
                                 (:xt/id component-node)
                                 :defines
                                 :extracted
                                 1)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id component-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (sfc-script-imports path scripts))
        chunk-kind (case kind
                     :vue :vue-file
                     :svelte-file)
        script-chunk-kind (case kind
                            :vue :vue-script
                            :svelte-script)
        template-chunk-kind (case kind
                              :vue :vue-template
                              :svelte-template)
        file-chunks (:chunks (extract-text-source run-id file chunk-kind))
        script-chunks (sfc-block-chunks run-id
                                        id-scope
                                        file-id
                                        path
                                        script-chunk-kind
                                        (str module-name " script")
                                        scripts)
        template-chunks (sfc-block-chunks run-id
                                          id-scope
                                          file-id
                                          path
                                          template-chunk-kind
                                          (str module-name " template")
                                          templates)]
    {:nodes (vec (concat [ns-node component-node] defs))
     :edges (vec (concat [component-edge] define-edges import-edges))
     :chunks (vec (concat file-chunks script-chunks template-chunks))
     :diagnostics []}))

(defn- php-namespace-name
  [path content]
  (let [namespace (some (fn [line]
                          (second
                           (re-matches #"^\s*namespace\s+([A-Za-z_\\][A-Za-z0-9_\\]*)\s*;\s*$"
                                       line)))
                        (str/split-lines content))]
    (if namespace
      (str/replace namespace "\\" ".")
      (source-module-name path))))

(defn- php-use-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*use\s+([A-Za-z_\\][A-Za-z0-9_\\]*)\s*(?:as\s+[A-Za-z_][A-Za-z0-9_]*)?\s*;\s*$"
                                      line)]
                 {:target (str/replace target "\\" ".")
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- php-include-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-find #"\b(?:require|require_once|include|include_once)\b.*['\"]([^'\"]+)['\"]"
                                   line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- php-route-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ method route]
                        (re-find #"Route::(get|post|put|patch|delete|options|any)\s*\(\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :framework-route
                 :label (str (str/upper-case method) " " route)
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ method route]
                        (re-find #"\$routes->(get|post|put|patch|delete|options|add)\s*\(\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :framework-route
                 :label (str (str/upper-case method) " " route)
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ route]
                        (re-find #"#\[Route\(\s*['\"]([^'\"]+)['\"]" line)]
               [{:kind :framework-route
                 :label route
                 :source-line source-line
                 :relation :defines}])))))
       distinct
       vec))

(defn- php-type-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(?:final\s+|abstract\s+)?(class|interface|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                         line)]
    {:kind (case kind
             "class" :class
             "interface" :interface
             "trait" :trait
             "enum" :enum)
     :name name
     :public? true
     :source-line (inc idx)}))

(defn- php-function-line
  [current-type idx line]
  (when-let [[_ visibility name]
             (re-matches #"^\s*(?:(public|protected|private)\s+)?(?:static\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                         line)]
    {:kind (if current-type :method :function)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))

(defn- php-constant-line
  [current-type idx line]
  (when-let [[_ name]
             (re-matches #"^\s*(?:(?:public|protected|private)\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
    {:kind :constant
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? true
     :source-line (inc idx)}))

(defn- php-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              type-form (php-type-line idx line)
              function-form (when-not type-form
                              (php-function-line current-type idx line))
              constant-form (when-not (or type-form function-form)
                              (php-constant-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      function-form (conj function-form)
                      constant-form (conj constant-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))

(defn extract-php
  "Extract bounded namespace, use/include, and declaration facts from PHP."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [namespace-name (php-namespace-name path content)
        ns-node (namespace-node run-id id-scope file-id path namespace-name)
        lines (vec (str/split-lines content))
        def-forms (php-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str namespace-name "/" name)]
                       {:xt/id (node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace namespace-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        use-edges (mapv #(edge-row run-id file-id path
                                   (:xt/id ns-node)
                                   (node-id id-scope :namespace (:target %))
                                   :imports
                                   :extracted
                                   (:source-line %))
                        (php-use-targets lines))
        include-edges (mapv #(edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (node-id id-scope :file (:target %))
                                       :uses
                                       :extracted
                                       (:source-line %))
                            (php-include-targets lines))
        route-facts (php-route-facts lines)
        route-nodes (mapv (fn [{:keys [kind label source-line]}]
                            (generic-node run-id id-scope file-id path
                                          kind label source-line))
                          route-facts)
        route-edges (mapv (fn [{:keys [kind label source-line relation]}]
                            (edge-row run-id
                                      file-id
                                      path
                                      (:xt/id ns-node)
                                      (node-id id-scope kind label)
                                      relation
                                      :extracted
                                      source-line))
                          route-facts)
        chunk-result (extract-text-source run-id file :php-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str namespace-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "PHP")]
    {:nodes (vec (concat [ns-node] defs route-nodes))
     :edges (vec (concat define-edges use-edges include-edges route-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))

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

























(defn- workflow-source-import-targets
  [path content]
  (let [lines (str/split-lines content)
        js? (contains? #{".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts"}
                       (fs/extension path))]
    (if js?
      (->> lines
           (map-indexed #(js-import-targets %1 path %2))
           (mapcat identity)
           vec)
      (->> lines
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (concat
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*).*$"
                                      line)]
                 [{:target target
                   :source-line (inc idx)}])
               (when-let [[_ target]
                          (re-matches #"^\s*from\s+([A-Za-z_][A-Za-z0-9_.]*)\s+import\s+.+$"
                                      line)]
                 [{:target target
                   :source-line (inc idx)}]))))
           distinct
           vec))))

(defn- workflow-framework-for-import
  [target]
  (let [target (str target)]
    (cond
      (or (= "airflow" target) (str/starts-with? target "airflow."))
      "airflow"

      (or (= "dagster" target) (str/starts-with? target "dagster."))
      "dagster"

      (or (= "prefect" target) (str/starts-with? target "prefect."))
      "prefect"

      (or (= "temporalio" target)
          (str/starts-with? target "temporalio.")
          (str/starts-with? target "@temporalio/"))
      "temporal"

      :else nil)))

(defn- workflow-import-facts
  [path content]
  (let [imports (workflow-source-import-targets path content)]
    (vec
     (concat
      (->> imports
           (keep (fn [{:keys [target source-line]}]
                   (when-let [framework (workflow-framework-for-import target)]
                     {:kind :workflow-framework
                      :label framework
                      :source-line source-line
                      :relation :uses}))))
      (->> imports
           (filter #(package-reference? (:target %)))
           (map (fn [{:keys [target source-line]}]
                  {:kind :workflow-package
                   :label target
                   :source-line source-line
                   :relation :imports})))))))

(defn- workflow-py-assignment-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ value]
                        (or (re-find #"\bDAG\s*\(\s*['\"]([^'\"]+)['\"]" line)
                            (re-find #"\bdag_id\s*=\s*['\"]([^'\"]+)['\"]" line))]
               [{:kind :workflow-dag
                 :label value
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ value]
                        (re-find #"\btask_id\s*=\s*['\"]([^'\"]+)['\"]" line)]
               [{:kind :workflow-task
                 :label value
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ key value]
                        (re-find #"\b(schedule_interval|schedule)\s*=\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :workflow-schedule
                 :label (str key ":" value)
                 :source-line source-line
                 :relation :uses}])))))
       distinct
       vec))

(defn- workflow-decorated-python-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         pending nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [decorator (cond
                        (re-find #"^\s*@dagster\.asset\b|^\s*@asset\b" line)
                        {:kind :workflow-asset :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.op\b|^\s*@op\b" line)
                        {:kind :workflow-task :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.job\b|^\s*@job\b" line)
                        {:kind :workflow-job :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.schedule\b|^\s*@schedule\b" line)
                        {:kind :workflow-schedule :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.sensor\b|^\s*@sensor\b" line)
                        {:kind :workflow-sensor :source-line (inc idx)}

                        (re-find #"^\s*@prefect\.flow\b|^\s*@flow\b" line)
                        {:kind :workflow :source-line (inc idx)}

                        (re-find #"^\s*@prefect\.task\b|^\s*@task\b" line)
                        {:kind :workflow-task :source-line (inc idx)}

                        (re-find #"^\s*@workflow\.defn\b" line)
                        {:kind :workflow :source-line (inc idx)}

                        (re-find #"^\s*@activity\.defn\b" line)
                        {:kind :workflow-activity :source-line (inc idx)}

                        :else nil)
            definition (or (some->> (re-matches #"^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                                                line)
                                    second)
                           (some->> (re-matches #"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                                                line)
                                    second))]
        (cond
          decorator
          (recur (rest remaining) decorator out)

          (and pending definition)
          (recur (rest remaining)
                 nil
                 (conj out (assoc pending
                                  :label definition
                                  :relation :defines)))

          :else
          (recur (rest remaining) pending out)))
      (vec (distinct out)))))

(defn- workflow-js-facts
  [content]
  (let [temporal-workflow? (re-find #"['\"]@temporalio/workflow['\"]" content)]
    (when temporal-workflow?
      (->> (str/split-lines content)
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (let [source-line (inc idx)]
                (concat
                 (when-let [[_ name]
                            (re-matches #"^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][A-Za-z0-9_$]*)\b.*"
                                        line)]
                   [{:kind :workflow
                     :label name
                     :source-line source-line
                     :relation :defines}])
                 (when (re-find #"\bproxyActivities\s*<|\bproxyActivities\s*\(" line)
                   [{:kind :workflow-activity
                     :label (str "proxyActivities@" source-line)
                     :source-line source-line
                     :relation :references}])))))
           distinct
           vec))))

(defn- workflow-source-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (concat
           (map (fn [[_ source target]]
                  {:source source
                   :target target
                   :source-line (inc idx)
                   :relation :precedes})
                (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*>>\s*([A-Za-z_][A-Za-z0-9_]*)\b"
                        line))
           (map (fn [[_ source target]]
                  {:source target
                   :target source
                   :source-line (inc idx)
                   :relation :precedes})
                (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*<<\s*([A-Za-z_][A-Za-z0-9_]*)\b"
                        line)))))
       distinct
       vec))

(defn- workflow-python-facts
  [content]
  (vec (distinct (concat (workflow-py-assignment-facts content)
                         (workflow-decorated-python-facts content)))))

(defn- workflow-doc-framework
  [doc]
  (let [api-version (some-> (extract.infra/yaml-doc-value doc "apiVersion") :value)
        kind (some-> (extract.infra/yaml-doc-value doc "kind") :value)]
    (cond
      (and (some-> api-version (str/includes? "argoproj.io"))
           (contains? #{"Workflow" "CronWorkflow" "WorkflowTemplate"} kind))
      "argo-workflows"

      (and (some-> api-version (str/includes? "tekton.dev"))
           (contains? #{"Pipeline" "Task" "PipelineRun"} kind))
      "tekton"

      :else nil)))

(defn- workflow-doc-resource-label
  [doc]
  (let [kind (some-> (extract.infra/yaml-doc-value doc "kind") :value)
        name (some-> (extract.infra/yaml-doc-metadata-name doc) :value)]
    (when (and kind name)
      (str kind "/" name))))

(defn- workflow-yaml-name-blocks
  [doc section-name]
  (loop [remaining doc
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            top-exit? (and in-section?
                           entry
                           (<= (:indent entry) section-indent)
                           (not= section-name (:key entry)))
            section-start? (and entry (= section-name (:key entry)))
            name-start (when (and in-section?
                                  (re-matches #"^\s*-\s*name:\s*.+$" line))
                         {:label (strip-yaml-scalar
                                  (second (re-matches #"^\s*-\s*name:\s*(.+?)\s*$" line)))
                          :source-line (inc idx)
                          :lines [[idx line]]})]
        (cond
          section-start?
          (recur (rest remaining) true (:indent entry) nil out)

          top-exit?
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          name-start
          (recur (rest remaining)
                 true
                 section-indent
                 name-start
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

(defn- workflow-block-list-values
  [block key-name]
  (loop [remaining (:lines block)
         list-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            indent (leading-spaces line)
            inline-values (when (and entry
                                     (= key-name (:key entry))
                                     (seq (:value entry)))
                            (map (fn [value]
                                   {:value value
                                    :source-line (:source-line entry)})
                                 (yaml-scalar-list-values (:value entry))))
            list-indent* (cond
                           (and entry
                                (= key-name (:key entry))
                                (str/blank? (:value entry)))
                           (:indent entry)

                           (and list-indent
                                entry
                                (<= (:indent entry) list-indent))
                           nil

                           :else list-indent)
            list-value (when (and list-indent*
                                  (> indent list-indent*))
                         (some->> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                  second
                                  strip-yaml-scalar))]
        (recur (rest remaining)
               list-indent*
               (cond-> out
                 inline-values (into inline-values)
                 list-value (conj {:value list-value
                                   :source-line (inc idx)}))))
      (vec (distinct out)))))

(defn- workflow-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))

(defn- workflow-block-nested-scalar
  [block parent-key child-key]
  (loop [remaining (:lines block)
         parent-indent nil]
    (when-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            parent-indent* (cond
                             (and entry
                                  (= parent-key (:key entry))
                                  (str/blank? (:value entry)))
                             (:indent entry)

                             (and parent-indent
                                  entry
                                  (<= (:indent entry) parent-indent))
                             nil

                             :else parent-indent)]
        (if (and parent-indent*
                 entry
                 (> (:indent entry) parent-indent*)
                 (= child-key (:key entry))
                 (seq (:value entry)))
          {:value (strip-yaml-scalar (:value entry))
           :source-line (:source-line entry)}
          (recur (rest remaining) parent-indent*))))))

(defn- workflow-doc-task-facts
  [framework resource-label doc]
  (let [section-name (case framework
                       "argo-workflows" "templates"
                       "tekton" "tasks"
                       nil)]
    (when section-name
      (->> (workflow-yaml-name-blocks doc section-name)
           (mapcat
            (fn [{:keys [label source-line] :as block}]
              (concat
               [{:kind :workflow-task
                 :label label
                 :source-line source-line
                 :relation :defines}]
               (when-let [{task-ref :value ref-line :source-line}
                          (or (workflow-block-scalar block "taskRef")
                              (workflow-block-nested-scalar block "taskRef" "name"))]
                 [{:kind :workflow-template
                   :label (str label ":" task-ref)
                   :source-line ref-line
                   :relation :references}])
               (->> (workflow-block-list-values block "dependencies")
                    (map (fn [{:keys [value source-line]}]
                           {:kind :workflow-task-dependency
                            :label (str label ":" value)
                            :source-line source-line
                            :source label
                            :target value
                            :relation :requires})))
               (->> (workflow-block-list-values block "runAfter")
                    (map (fn [{:keys [value source-line]}]
                           {:kind :workflow-task-dependency
                            :label (str label ":" value)
                            :source-line source-line
                            :source label
                            :target value
                            :relation :requires})))
               (when-let [{image :value image-line :source-line}
                          (workflow-block-scalar block "image")]
                 [{:kind :container-image
                   :label image
                   :source-line image-line
                   :relation :uses
                   :source label}]))))
           (map #(cond-> %
                   resource-label (assoc :resource-label resource-label)))
           distinct
           vec))))

(defn- workflow-manifest-facts
  [content]
  (->> (extract.infra/yaml-documents content)
       (mapcat
        (fn [doc]
          (let [framework (workflow-doc-framework doc)
                resource-label (workflow-doc-resource-label doc)
                kind-value (some-> (extract.infra/yaml-doc-value doc "kind") :value)
                kind-line (or (some-> (extract.infra/yaml-doc-value doc "kind") :source-line) 1)]
            (when framework
              (concat
               [{:kind :workflow-framework
                 :label framework
                 :source-line 1
                 :relation :uses}]
               (when resource-label
                 [{:kind (if (= "CronWorkflow" kind-value)
                           :workflow-schedule
                           :workflow)
                   :label resource-label
                   :source-line kind-line
                   :relation :defines}])
               (workflow-doc-task-facts framework resource-label doc))))))
       distinct
       vec))

(defn- workflow-prefect-yaml-facts
  [content]
  (let [project-name (yaml-top-level-value content "name")
        deployments (yaml-top-section-blocks (str/split-lines content)
                                             "deployments")]
    (vec
     (concat
      [{:kind :workflow-framework
        :label "prefect"
        :source-line 1
        :relation :uses}]
      (when project-name
        [{:kind :workflow
          :label project-name
          :source-line 1
          :relation :defines}])
      (mapcat
       (fn [{:keys [label source-line] :as block}]
         (concat
          [{:kind :workflow-deployment
            :label label
            :source-line source-line
            :relation :defines}]
          (when-let [{entrypoint :value entrypoint-line :source-line}
                     (workflow-block-scalar block "entrypoint")]
            [{:kind :workflow-entrypoint
              :label (str label ":" entrypoint)
              :source-line entrypoint-line
              :relation :references}])
          (when-let [{cron :value cron-line :source-line}
                     (workflow-block-scalar block "cron")]
            [{:kind :workflow-schedule
              :label (str label ":" cron)
              :source-line cron-line
              :relation :uses}])
          (when-let [{pool :value pool-line :source-line}
                     (workflow-block-scalar block "work_pool")]
            [{:kind :workflow-worker-pool
              :label (str label ":" pool)
              :source-line pool-line
              :relation :uses}])))
       deployments)))))

(defn- workflow-dagster-yaml-facts
  [content]
  (vec
   (concat
    [{:kind :workflow-framework
      :label "dagster"
      :source-line 1
      :relation :uses}]
    (when-let [module-name (yaml-top-level-value content "module_name")]
      [{:kind :workflow-module
        :label module-name
        :source-line 1
        :relation :references}])
    (when-let [package-name (yaml-top-level-value content "package_name")]
      [{:kind :workflow-package
        :label package-name
        :source-line 1
        :relation :references}]))))

(defn- workflow-config-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (vec
     (distinct
      (concat
       (case filename
         ("prefect.yaml" "prefect.yml") (workflow-prefect-yaml-facts content)
         ("dagster.yaml" "dagster.yml") (workflow-dagster-yaml-facts content)
         [])
       (workflow-manifest-facts content))))))

(defn- workflow-source-facts
  [{:keys [path content]}]
  (vec
   (distinct
    (concat
     (workflow-import-facts path content)
     (case (fs/extension path)
       ".py" (workflow-python-facts content)
       (".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts") (workflow-js-facts content)
       [])))))

(defn- workflow-facts
  [{:keys [path] :as file}]
  (if (contains? #{".py" ".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts"}
                 (fs/extension path))
    (workflow-source-facts file)
    (workflow-config-facts file)))

(defn- workflow-base-result
  [run-id {:keys [path] :as file}]
  (case (fs/extension path)
    ".py" (extract-python run-id (assoc file :kind :python))
    (".ts" ".tsx" ".mts" ".cts") (extract.source-js/extract-js-family run-id (assoc file :kind :typescript))
    (".js" ".jsx" ".mjs" ".cjs") (extract.source-js/extract-js-family run-id (assoc file :kind :javascript))
    nil))

(defn- workflow-dependency-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source target source-line relation]}]
               (when (and (seq source) (seq target))
                 (edge-row run-id
                           file-id
                           path
                           (node-id id-scope :workflow-task source)
                           (node-id id-scope :workflow-task target)
                           (or relation :requires)
                           :extracted
                           source-line))))
       distinct
       vec))

(defn extract-workflow-orchestration
  "Extract bounded workflow orchestration declarations and dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (workflow-facts file)
        workflow-result (extract-format-facts run-id
                                              file
                                              :workflow-file
                                              :workflow-file
                                              facts)
        source-dependencies (when (= ".py" (fs/extension path))
                              (workflow-source-dependencies content))
        dependency-edges (workflow-dependency-edges run-id
                                                    id-scope
                                                    file-id
                                                    path
                                                    (concat facts
                                                            source-dependencies))
        base-result (workflow-base-result run-id file)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result)
                                     (:nodes workflow-result))))
       :edges (vec (distinct (concat (:edges base-result)
                                     (:edges workflow-result)
                                     dependency-edges)))
       :chunks (vec (distinct (concat (:chunks base-result)
                                      (:chunks workflow-result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics workflow-result)))}
      (update workflow-result :edges #(vec (distinct (concat % dependency-edges)))))))





































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
     :code (extract-code run-id file)
     :go (extract.source-basic/extract-go run-id file)
     :java (if (parser-worker-enabled? :java)
             (extract-java-worker run-id file)
             (extract.source-jvm/extract-java run-id file))
     :groovy (extract-groovy run-id file)
     :kotlin (extract-kotlin run-id file)
     :swift (extract-swift run-id file)
     :dotnet (if (parser-worker-enabled? :dotnet)
               (extract-dotnet-worker run-id file)
               (extract-dotnet run-id file))
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
     :prisma (extract-prisma run-id file)
     :dbt (extract-dbt run-id file)
     :data-science (extract.data-science/extract-data-science run-id file)
     :db-config (extract-db-config run-id file)
     :codegen-config (extract-codegen-config run-id file)
     :test-config (extract-test-config run-id file)
     :quality-config (extract-quality-config run-id file)
     :editor-config (extract-editor-config run-id file)
     :release-config (extract-release-config run-id file)
     :web-framework (extract-web-framework run-id file)
     :workflow-orchestration (extract-workflow-orchestration run-id file)
     :observability-config (extract.observability/extract-observability-config run-id file)
     :tool-config (extract-tool-config run-id file)
     :ops-config (extract.ops-config/extract-ops-config run-id file)
     :astro (extract-astro run-id file)
     :php (extract-php run-id file)
     :notebook (extract.notebook/extract-notebook run-id file)
     :devcontainer (extract.devcontainer/extract-devcontainer run-id file)
     :kustomize (extract.yaml-config/extract-kustomize run-id file)
     :pre-commit-config (extract.yaml-config/extract-pre-commit-config run-id file)
     :codeowners (extract.codeowners/extract-codeowners run-id file)
     :task-runner (extract.task-config/extract-task-runner run-id file)
     :starlark (extract.starlark/extract-starlark run-id file)
     :tool-version-config (extract.task-config/extract-tool-version-config run-id file)
     :storybook (extract-storybook run-id file)
     :docs-config (extract-docs-config run-id file)
     :governance (extract.governance/extract-governance run-id file)
     :sbom (extract.sbom/extract-sbom run-id file)
     :vue (extract-sfc run-id file)
     :svelte (extract-sfc run-id file)
     :ci (extract.ci/extract-ci run-id file)
     :build (extract.build/extract-build run-id file)
     :javascript (extract.source-js/extract-js-family run-id file)
     :typescript (extract.source-js/extract-js-family run-id file)
     :python (extract-python run-id file)
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
