(ns agraph.extract
  "Deterministic extraction from supported source, config, and document files."
  (:require [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
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

(defn- parser-worker-mode
  []
  (some-> (System/getenv "AGRAPH_PARSER_WORKER") str/lower-case not-empty))

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
(def ^:private rust-file-chunk-lines 80)
(def ^:private rust-definition-chunk-lines 120)
(def ^:private go-file-chunk-lines 100)
(def ^:private go-definition-chunk-lines 120)

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

(def ^:private markdown-chunk-lines 120)

(defn- markdown-heading-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ _ heading] (re-matches #"^(#{1,6})\s+(.+?)\s*$"
                                                    line)]
                 {:kind :doc-heading
                  :label heading
                  :source-line (inc idx)
                  :relation :defines})))
       distinct
       vec))

(defn- markdown-link-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (map (fn [[_ target]]
                        {:kind :doc-link
                         :label target
                         :source-line (inc idx)
                         :relation :references})
                      (re-seq #"\[[^\]]+\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)"
                              line))))
       distinct
       vec))

(defn- mdx-import-facts
  [path lines]
  (->> lines
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :doc-import
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))

(defn- rst-toctree-facts
  [lines]
  (let [close-tree (fn [out current]
                     (if current
                       (let [source-line (:source-line current)
                             entries (->> (:entries current)
                                          distinct
                                          (mapv (fn [entry]
                                                  {:kind :docs-route
                                                   :label entry
                                                   :source-line source-line
                                                   :relation :references})))
                             options (->> (:options current)
                                          distinct
                                          (mapv (fn [option]
                                                  {:kind :docs-toctree-option
                                                   :label option
                                                   :source-line source-line
                                                   :relation :uses})))]
                         (into out
                               (concat [{:kind :docs-toctree
                                         :label (str "toctree@" source-line)
                                         :source-line source-line
                                         :relation :defines}]
                                       entries
                                       options)))
                       out))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (re-matches #"^\s*\.\.\s+toctree::\s*$" line)
          (recur (rest remaining)
                 {:source-line (inc idx)
                  :entries []
                  :options []}
                 (close-tree out current))

          current
          (let [trimmed (str/trim line)]
            (cond
              (str/blank? line)
              (recur (rest remaining) current out)

              (not (re-matches #"^\s+.+$" line))
              (recur remaining nil (close-tree out current))

              (str/starts-with? trimmed ":")
              (recur (rest remaining)
                     (update current :options conj trimmed)
                     out)

              :else
              (recur (rest remaining)
                     (update current :entries conj trimmed)
                     out)))

          :else
          (recur (rest remaining) nil out))
        (vec (distinct (close-tree out current)))))))

(defn- doc-structure-facts
  [path lines]
  (vec (concat (markdown-heading-facts lines)
               (markdown-link-facts lines)
               (when (= ".rst" (fs/extension path))
                 (rst-toctree-facts lines))
               (when (= ".mdx" (fs/extension path))
                 (mdx-import-facts path lines)))))

(defn- split-doc-chunk
  [{:keys [start-line lines] :as chunk}]
  (->> (partition-all markdown-chunk-lines lines)
       (map-indexed (fn [idx part]
                      (let [part (vec part)
                            start (+ start-line (* idx markdown-chunk-lines))]
                        (assoc chunk
                               :start-line start
                               :end-line (+ start (count part) -1)
                               :lines part))))))

(defn extract-doc
  "Extract Markdown chunks."
  [run-id {:keys [id-scope file-id path content]}]
  (let [lines (str/split-lines content)
        total-lines (count lines)
        mdx? (= ".mdx" (fs/extension path))
        doc-node (generic-node run-id id-scope file-id path :doc-file path 1)
        facts (doc-structure-facts path lines)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id doc-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        close-chunk (fn [out current end-line]
                      (if (seq (:lines current))
                        (into out (split-doc-chunk
                                   (assoc current :end-line end-line)))
                        out))
        chunks (loop [remaining (map-indexed vector lines)
                      heading-stack []
                      current {:label path
                               :start-line 1
                               :heading-path []
                               :lines []}
                      out []]
                 (if-let [[idx line] (first remaining)]
                   (if-let [[_ marker heading] (re-matches #"^(#{1,6})\s+(.+?)\s*$" line)]
                     (let [level (count marker)
                           stack (->> heading-stack
                                      (remove #(<= level (:level %)))
                                      vec)
                           stack (conj stack {:level level :label heading})]
                       (recur (rest remaining)
                              stack
                              {:label heading
                               :start-line (inc idx)
                               :heading-path (mapv :label stack)
                               :lines [line]}
                              (close-chunk out current idx)))
                     (recur (rest remaining)
                            heading-stack
                            (update current :lines conj line)
                            out))
                   (close-chunk out current total-lines)))]
    {:nodes (into [doc-node] fact-nodes)
     :edges fact-edges
     :chunks (mapv (fn [{:keys [label start-line lines] :as chunk}]
                     (let [text (str/join "\n" lines)]
                       {:xt/id (chunk-id id-scope path label start-line)
                        :file-id file-id
                        :path path
                        :kind (if mdx? :mdx :markdown)
                        :label label
                        :text text
                        :tokens (text/tokenize (str label "\n" text))
                        :heading-path (:heading-path chunk)
                        :content-sha (hash/sha256-hex text)
                        :source-line start-line
                        :end-line (:end-line chunk)
                        :active? true
                        :run-id run-id}))
                   chunks)
     :diagnostics []}))

(defn extract-edn
  "Extract EDN as a searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]}]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind (or kind :edn)
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]} chunk-kind]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind chunk-kind
             :file-kind kind
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(def ^:private shell-function-name-pattern
  "[A-Za-z_][A-Za-z0-9_:-]*")

(def ^:private shell-function-with-parens-pattern
  (re-pattern (str "^\\s*(" shell-function-name-pattern ")\\s*\\(\\s*\\)\\s*(?:\\{.*)?$")))

(def ^:private shell-function-keyword-pattern
  (re-pattern (str "^\\s*function\\s+(" shell-function-name-pattern ")"
                   "(?:\\s*\\(\\s*\\))?\\s*(?:\\{.*)?$")))

(defn- shell-function-name
  [line]
  (or (some-> (re-matches shell-function-with-parens-pattern line) second)
      (some-> (re-matches shell-function-keyword-pattern line) second)))

(defn- next-nonblank-line
  [lines idx]
  (->> lines
       (drop (inc idx))
       (drop-while str/blank?)
       first))

(defn- shell-function-start?
  [lines idx line]
  (and (shell-function-name line)
       (or (str/includes? line "{")
           (= "{" (some-> (next-nonblank-line lines idx) str/trim)))))

(defn- shell-function-facts
  [content]
  (let [lines (vec (str/split-lines (or content "")))
        offsets (vec (line-start-offsets (or content "")))]
    (->> lines
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when (shell-function-start? lines idx line)
                   (let [source-line (inc idx)
                         text (or (balanced-curly-block content (nth offsets idx))
                                  line)
                         line-count (count (str/split-lines text))]
                     {:name (shell-function-name line)
                      :source-line source-line
                      :end-line (+ source-line line-count -1)
                      :text text}))))
         vec)))

(def ^:private shell-command-pattern
  (re-pattern (str "(?:^|[;&|({]|\\$\\()\\s*(" shell-function-name-pattern ")\\b")))

(def ^:private shell-syntax-commands
  #{"case" "do" "done" "echo" "elif" "else" "esac" "exit" "export" "fi" "for" "function" "if" "in"
    "return" "set" "then" "unset" "until" "while"})

(defn- shell-command-names
  [line]
  (when-not (str/starts-with? (str/trim (or line "")) "#")
    (->> (re-seq shell-command-pattern (or line ""))
         (map second)
         (remove shell-syntax-commands)
         distinct
         vec)))

(defn- shell-call-facts
  [content]
  (let [lines (vec (str/split-lines (or content "")))]
    (->> lines
         (map-indexed vector)
         (mapcat (fn [[idx line]]
                   (when-not (shell-function-start? lines idx line)
                     (map (fn [name]
                            {:name name
                             :source-line (inc idx)})
                          (shell-command-names line)))))
         distinct
         vec)))

(defn extract-shell
  "Extract shell scripts as file chunks plus function definitions."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [file-node (generic-node run-id id-scope file-id path :shell-file path 1)
        file-chunk-result (extract-text-source run-id file :shell-file)
        functions (shell-function-facts content)
        calls (shell-call-facts content)
        function-label (fn [{:keys [name]}]
                         (str path "/" name))
        function-nodes (mapv (fn [{:keys [source-line] :as function}]
                               (generic-node run-id
                                             id-scope
                                             file-id
                                             path
                                             :shell-function
                                             (function-label function)
                                             source-line))
                             functions)
        function-edges (mapv (fn [{:keys [source-line] :as function}]
                               (edge-row run-id
                                         file-id
                                         path
                                         (:xt/id file-node)
                                         (node-id id-scope
                                                  :shell-function
                                                  (function-label function))
                                         :defines
                                         :extracted
                                         source-line))
                             functions)
        call-edges (mapv (fn [{:keys [name source-line]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id file-node)
                                     (node-id id-scope :shell-function name)
                                     :calls
                                     :extracted
                                     source-line))
                         calls)
        function-chunks (mapv (fn [{:keys [source-line text] :as function}]
                                (source-definition-chunk run-id
                                                         id-scope
                                                         file-id
                                                         path
                                                         (function-label function)
                                                         :function
                                                         source-line
                                                         text))
                              functions)]
    {:nodes (into [file-node] function-nodes)
     :edges (vec (concat function-edges call-edges))
     :chunks (vec (concat (:chunks file-chunk-result) function-chunks))
     :diagnostics []}))

(def ^:private style-section-chunk-lines
  120)

(defn- style-variable-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ name]
                          (re-matches #"^\s*(\$[A-Za-z0-9_-]+)\s*:.*"
                                      line)]
                 {:kind :style-variable
                  :label name
                  :source-line (inc idx)
                  :line line})))
       vec))

(defn- style-section-ranges
  [lines]
  (let [close-section (fn [out current end-line]
                        (if current
                          (conj out (assoc current :end-line end-line))
                          out))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (if-let [[_ label]
                 (re-matches #"^\s*//\s*scss-docs-start\s+(\S+)\s*$"
                             line)]
          (recur (rest remaining)
                 {:kind :style-section
                  :label label
                  :source-line (inc idx)}
                 (close-section out current idx))
          (if (and current
                   (re-matches #"^\s*//\s*scss-docs-end\s+\S+\s*$" line))
            (recur (rest remaining)
                   nil
                   (close-section out current (inc idx)))
            (recur (rest remaining) current out)))
        (close-section out current (count lines))))))

(defn- style-selector-line
  [idx line]
  (let [trimmed (str/trim line)]
    (when (and (str/includes? trimmed "{")
               (not (str/starts-with? trimmed "@"))
               (not (str/starts-with? trimmed "}"))
               (not (str/starts-with? trimmed "//")))
      (let [selector (-> trimmed
                         (str/split #"\{" 2)
                         first
                         str/trim)]
        (when-not (str/blank? selector)
          {:kind :style-rule
           :label selector
           :source-line (inc idx)})))))

(defn- style-brace-delta
  [line]
  (- (count (filter #(= \{ %) line))
     (count (filter #(= \} %) line))))

(defn- style-rule-ranges
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (if current
        (let [next-depth (+ depth (style-brace-delta line))]
          (if (not (pos? next-depth))
            (recur (rest remaining)
                   nil
                   0
                   (conj out (assoc current :end-line (inc idx))))
            (recur (rest remaining) current next-depth out)))
        (if-let [rule (style-selector-line idx line)]
          (let [next-depth (style-brace-delta line)]
            (if (pos? next-depth)
              (recur (rest remaining) rule next-depth out)
              (recur (rest remaining) nil 0 (conj out (assoc rule :end-line (inc idx))))))
          (recur (rest remaining) nil 0 out)))
      out)))

(defn- style-section-chunk
  [kind run-id id-scope file-id path lines {:keys [label source-line end-line]}]
  (let [line-count (max 0 (inc (- (long end-line) (long source-line))))
        text-lines (->> lines
                        (drop (dec (long source-line)))
                        (take (min line-count style-section-chunk-lines)))
        chunk-text (str/join "\n" text-lines)
        actual-end-line (+ (long source-line) (count text-lines) -1)]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind kind
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :active? true
             :run-id run-id}
      (seq text-lines) (assoc :end-line actual-end-line)
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn- style-variable-chunk
  [run-id id-scope file-id path {:keys [label source-line line]}]
  (let [chunk-text (str line)]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :style-variable
             :definition-kind :style-variable
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :end-line source-line
             :active? true
             :run-id run-id}
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn extract-style
  "Extract CSS/SCSS as searchable style chunks plus mechanical SCSS structure."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [base-result (extract-text-source run-id file :style-file)
        lines (str/split-lines content)
        root-node (generic-node run-id id-scope file-id path :style-file path 1)
        variable-facts (style-variable-facts lines)
        section-ranges (style-section-ranges lines)
        rule-ranges (style-rule-ranges lines)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         kind label source-line))
                         (concat section-ranges rule-ranges variable-facts))
        fact-edges (mapv (fn [{:keys [kind label source-line]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (node-id id-scope kind label)
                                     :defines
                                     :extracted
                                     source-line))
                         (concat section-ranges rule-ranges variable-facts))
        section-chunks (mapv #(style-section-chunk :style-section
                                                   run-id
                                                   id-scope
                                                   file-id
                                                   path
                                                   lines
                                                   %)
                             section-ranges)
        rule-chunks (mapv #(style-section-chunk :style-rule
                                                run-id
                                                id-scope
                                                file-id
                                                path
                                                lines
                                                %)
                          rule-ranges)
        variable-chunks (mapv #(style-variable-chunk run-id
                                                     id-scope
                                                     file-id
                                                     path
                                                     %)
                              variable-facts)]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks (vec (concat (:chunks base-result)
                          section-chunks
                          rule-chunks
                          variable-chunks))
     :diagnostics []}))

(defn- env-var-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ key]
                              (re-matches #"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|:).*$"
                                          trimmed)]
                     {:kind :env-var
                      :label key
                      :source-line (inc idx)
                      :relation :defines})))))
       distinct
       vec))

(defn extract-env
  "Extract dotenv-style files without storing assigned values as searchable text."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [root-node (generic-node run-id id-scope file-id path :env-file path 1)
        facts (env-var-facts content)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        sanitized-text (str/join "\n" (cons path (map :label facts)))]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks [{:xt/id (chunk-id id-scope path path 1)
               :file-id file-id
               :path path
               :kind :env-file
               :file-kind kind
               :label path
               :text sanitized-text
               :tokens (text/tokenize sanitized-text)
               :content-sha (hash/sha256-hex sanitized-text)
               :source-line 1
               :active? true
               :run-id run-id}]
     :diagnostics []}))

(defn- rust-module-name
  [path]
  (-> path
      (str/replace #"\.rs$" "")
      (str/replace #"/src/(lib|main)$" "")
      (str/replace #"/src/" "::")
      (str/replace #"/" "::")
      (str/replace #"-" "_")))

(defn- rust-declared-module-name
  [path module-name]
  (let [current (rust-module-name path)
        segments (str/split current #"::")
        file-module (last segments)
        parent (if (#{"lib" "main" "mod"} file-module)
                 (butlast segments)
                 segments)]
    (str/join "::" (concat parent [module-name]))))

(defn- rust-definition-kind
  [kind]
  (case kind
    "fn" :function
    "struct" :struct
    "enum" :enum
    "trait" :trait
    "impl" :impl
    :rust-symbol))

(defn- rust-definition-line
  [idx line]
  (or (when-let [[_ public? async? kind name]
                 (re-matches #"^\s*(pub(?:\([^)]*\))?\s+)?(async\s+)?(fn|struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
        {:kind (rust-definition-kind kind)
         :name name
         :public? (boolean public?)
         :source-line (inc idx)
         :async? (boolean async?)})
      (when-let [[_ target]
                 (re-matches #"^\s*impl(?:<[^>]+>)?\s+([A-Za-z_][A-Za-z0-9_:<>]*)\b.*" line)]
        {:kind :impl
         :name (str "impl:" target)
         :public? true
         :source-line (inc idx)})))

(defn- rust-module-edge
  [run-id id-scope file-id path ns-id idx line]
  (when-let [[_ module-name]
             (re-matches #"^\s*(?:pub\s+)?mod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;.*" line)]
    (edge-row run-id file-id path
              ns-id
              (node-id id-scope :namespace (rust-declared-module-name path module-name))
              :declares-module
              :extracted
              (inc idx))))

(defn- rust-use-edge
  [run-id id-scope file-id path ns-id idx line]
  (when-let [[_ target]
             (re-matches #"^\s*use\s+([^;]+);.*" line)]
    (let [clean-target (-> target
                           (str/replace #"\s+" "")
                           (str/replace #"\{.*" ""))]
      (edge-row run-id file-id path
                ns-id
                (node-id id-scope :namespace clean-target)
                :uses
                :extracted
                (inc idx)))))

(defn- rust-call-edges
  [run-id file-id path defs content]
  (let [defs-by-name (into {} (map (fn [node] [(:name node) (:xt/id node)])) defs)
        function-defs (filter #(= :function (:kind %)) defs)]
    (->> function-defs
         (mapcat
          (fn [source]
            (let [line-text (->> (str/split-lines content)
                                 (drop (dec (:source-line source)))
                                 (take 80)
                                 (str/join "\n"))]
              (->> (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(" line-text)
                   (map second)
                   (keep (fn [callee]
                           (when-let [target-id (get defs-by-name callee)]
                             (when (not= target-id (:xt/id source))
                               (edge-row run-id file-id path
                                         (:xt/id source)
                                         target-id
                                         :calls
                                         :inferred
                                         (:source-line source))))))))))
         distinct
         vec)))

(defn- rust-definition-text
  [lines current-def next-def]
  (let [source-line (:source-line current-def)
        end-line (or (some-> next-def :source-line dec)
                     (count lines))]
    (->> lines
         (drop (dec source-line))
         (take (inc (- end-line source-line)))
         (take rust-definition-chunk-lines)
         (str/join "\n"))))

(defn- rust-definition-chunk
  [run-id id-scope file-id path module-name {:keys [kind name source-line text]}]
  (let [label (str module-name "/" name)
        chunk-text (bounded-lines text rust-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :rust-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ source-line line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn extract-rust
  "Extract graph rows from a Rust source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (rust-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        raw-defs (->> lines
                      (map-indexed rust-definition-line)
                      (keep identity)
                      vec)
        defs-with-text (mapv (fn [current next-def]
                               (assoc current :text (rust-definition-text lines
                                                                          current
                                                                          next-def)))
                             raw-defs
                             (concat (rest raw-defs) [nil]))
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
                   defs-with-text)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        module-edges (keep-indexed #(rust-module-edge run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      (:xt/id ns-node)
                                                      %1
                                                      %2)
                                   lines)
        use-edges (keep-indexed #(rust-use-edge run-id
                                                id-scope
                                                file-id
                                                path
                                                (:xt/id ns-node)
                                                %1
                                                %2)
                                lines)
        call-edges (rust-call-edges run-id file-id path defs content)
        chunk-text (str/join "\n" (take rust-file-chunk-lines lines))
        chunk {:xt/id (chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind :rust-file
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(rust-definition-chunk run-id
                                                        id-scope
                                                        file-id
                                                        path
                                                        module-name
                                                        %)
                                defs-with-text)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges module-edges use-edges call-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- go-namespace-name
  [path _content]
  (str/replace path #"\.go$" ""))

(defn- go-public?
  [name]
  (boolean (re-matches #"[A-Z].*" (str name))))

(defn- go-receiver-type
  [receiver]
  (some-> receiver
          str/trim
          (str/split #"\s+")
          last
          (str/replace #"^\*" "")
          (str/replace #"\[.*\]$" "")))

(defn- go-definition-kind
  [path kind name receiver-type]
  (cond
    receiver-type :method
    (= "func" kind) (if (and (str/ends-with? path "_test.go")
                             (re-matches #"(Test|Benchmark|Fuzz).+" name))
                      :test
                      :function)
    (= "struct" kind) :struct
    (= "interface" kind) :interface
    (= "const" kind) :constant
    (= "var" kind) :var
    :else :type))

(defn- go-definition-line
  [path content line-starts idx line]
  (or (when-let [[_ receiver name]
                 (re-matches #"^\s*func\s+(?:\(([^)]*)\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                             line)]
        (let [receiver-type (go-receiver-type receiver)
              start (+ (get line-starts idx 0)
                       (or (str/index-of line "func") 0))]
          {:kind (go-definition-kind path "func" name receiver-type)
           :name (if receiver-type
                   (str receiver-type "." name)
                   name)
           :public? (go-public? name)
           :source-line (inc idx)
           :text (or (balanced-curly-block content start) line)}))
      (when-let [[_ name kind]
                 (re-matches #"^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)?.*"
                             line)]
        (let [start (+ (get line-starts idx 0)
                       (or (str/index-of line "type") 0))]
          {:kind (go-definition-kind path kind name nil)
           :name name
           :public? (go-public? name)
           :source-line (inc idx)
           :text (or (balanced-curly-block content start) line)}))
      (when-let [[_ kind name]
                 (re-matches #"^\s*(const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind (go-definition-kind path kind name nil)
         :name name
         :public? (go-public? name)
         :source-line (inc idx)
         :text line})))

(defn- go-definition-chunk
  [run-id id-scope file-id path namespace-name {:keys [kind name source-line text]}]
  (let [label (str namespace-name "/" name)
        chunk-text (bounded-lines text go-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn- go-import-target
  [line prefixed?]
  (let [pattern (if prefixed?
                  #"^\s*import\s+(?:(?:[A-Za-z_][A-Za-z0-9_]*|\.)\s+)?\"([^\"]+)\".*"
                  #"^\s*(?:(?:[A-Za-z_][A-Za-z0-9_]*|\.)\s+)?\"([^\"]+)\".*")]
    (some-> (re-matches pattern line) second)))

(defn- go-imports
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        in-block?
        (if (re-matches #"^\s*\)\s*$" line)
          (recur (rest remaining) false out)
          (recur (rest remaining)
                 true
                 (cond-> out
                   (go-import-target line false)
                   (conj {:target (go-import-target line false)
                          :source-line (inc idx)}))))

        (re-matches #"^\s*import\s+\(\s*$" line)
        (recur (rest remaining) true out)

        (go-import-target line true)
        (recur (rest remaining)
               false
               (conj out {:target (go-import-target line true)
                          :source-line (inc idx)}))

        :else
        (recur (rest remaining) false out))
      out)))

(def go-call-exclusions
  #{"append" "cap" "close" "complex" "copy" "delete" "defer" "func" "go"
    "if" "imag" "len" "make" "new" "panic" "print" "println" "real"
    "recover" "return" "select" "switch"})

(defn- go-call-name-keys
  [node]
  (let [node-name (:name node)]
    (cond-> [node-name]
      (str/includes? node-name ".")
      (conj (last (str/split node-name #"\."))))))

(defn- go-call-edges
  [run-id file-id path defs content]
  (let [defs-by-name (into {}
                           (mapcat (fn [node]
                                     (map (fn [name] [name (:xt/id node)])
                                          (go-call-name-keys node))))
                           defs)
        callable-defs (filter #(contains? #{:function :method :test} (:kind %))
                              defs)
        lines (vec (str/split-lines content))]
    (->> callable-defs
         (mapcat
          (fn [source]
            (let [line-text (->> lines
                                 (drop (dec (:source-line source)))
                                 (take 120)
                                 (str/join "\n"))]
              (->> (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(" line-text)
                   (map second)
                   (remove go-call-exclusions)
                   (keep (fn [callee]
                           (when-let [target-id (get defs-by-name callee)]
                             (when (not= target-id (:xt/id source))
                               (edge-row run-id file-id path
                                         (:xt/id source)
                                         target-id
                                         :calls
                                         :inferred
                                         (:source-line source))))))))))
         distinct
         vec)))

(defn extract-go
  "Extract graph rows from a Go source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [namespace-name (go-namespace-name path content)
        ns-node (namespace-node run-id id-scope file-id path namespace-name)
        lines (str/split-lines content)
        line-starts (line-start-offsets content)
        def-forms (->> lines
                       (map-indexed #(go-definition-line path content line-starts %1 %2))
                       (keep identity)
                       vec)
        defs (->> def-forms
                  (mapv (fn [{:keys [kind name public? source-line]}]
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
                             :run-id run-id}))))
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
                           (go-imports lines))
        call-edges (go-call-edges run-id file-id path defs content)
        chunk-text (str/join "\n" (take go-file-chunk-lines lines))
        chunk {:xt/id (chunk-id id-scope path namespace-name 1)
               :file-id file-id
               :path path
               :kind :go-file
               :label namespace-name
               :text chunk-text
               :tokens (text/tokenize (str namespace-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(go-definition-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      namespace-name
                                                      %)
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges call-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

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
      (str/replace #"\.d\.ts$" "")
      (str/replace #"\.rb\.template$" "")
      (str/replace #"\.(astro|c|cc|cpp|cxx|dart|erl|ex|exs|groovy|h|hh|hpp|hrl|hs|html|hxx|jl|kt|kts|lua|m|ml|mli|mm|mjs|cjs|jsx|js|odin|pl|pm|r|R|rake|rb|scala|tsx|ts|php|scss|css|sql|svelte|swift|svg|vue|zig)$" "")
      (str/replace #"/" ".")
      (str/replace #"-" "_")))

(defn- js-identifier
  []
  "[A-Za-z_$][A-Za-z0-9_$]*")

(defn- js-public-line?
  [line]
  (boolean (re-find #"^\s*export\b" line)))

(defn- js-definition-line
  [idx line]
  (let [identifier (js-identifier)
        public? (js-public-line? line)]
    (or (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:default\\s+)?"
                          "(?:async\\s+)?function\\s+(" identifier ")\\b.*"))
                    line)]
          {:kind :function
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+("
                          identifier
                          ")\\b.*"))
                    line)]
          {:kind :class
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:const|let|var)\\s+("
                          identifier
                          ")\\s*(?::[^=]+)?=\\s*(?:async\\s*)?"
                          "(?:\\([^)]*\\)|" identifier ")\\s*=>.*"))
                    line)]
          {:kind :function
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*export\\s+(?:const|let|var)\\s+("
                          identifier
                          ")\\b.*"))
                    line)]
          {:kind :var
           :name name
           :public? true
           :source-line (inc idx)}))))

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

(def ^:private js-definition-chunk-lines 80)
(def ^:private typescript-declaration-member-before-lines 32)
(def ^:private typescript-declaration-member-after-lines 8)
(def ^:private typescript-declaration-member-limit 300)

(defn- js-definition-chunk
  [run-id id-scope file-id path lines {:keys [label kind source-line]}]
  (let [chunk-text (->> lines
                        (drop (dec (or source-line 1)))
                        (take js-definition-chunk-lines)
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn- typescript-declaration-path?
  [path]
  (str/ends-with? (str path) ".d.ts"))

(defn- typescript-declaration-member-line
  [idx line]
  (let [identifier (js-identifier)]
    (when-let [[_ name marker]
               (re-matches
                (re-pattern
                 (str "^\\s*(?:" "readonly" "\\s+)?("
                      identifier
                      ")\\??\\s*(:|\\(|<).*$"))
                line)]
      (let [line (str/trim line)]
        (when-not (or (str/starts-with? line "export ")
                      (str/starts-with? line "import ")
                      (str/starts-with? line "interface ")
                      (str/starts-with? line "type "))
          {:kind (if (and (= ":" marker)
                          (not (str/includes? line "=>"))
                          (not (str/includes? line "(")))
                   :property
                   :method)
           :name name
           :source-line (inc idx)})))))

(defn- nearest-jsdoc-start
  [lines start-idx member-idx]
  (or (->> (range member-idx (dec start-idx) -1)
           (some (fn [idx]
                   (when (re-find #"^\s*/\*\*" (nth lines idx))
                     idx))))
      start-idx))

(defn- typescript-declaration-member-chunk
  [run-id id-scope file-id path module-name lines {:keys [kind name source-line]}]
  (let [member-idx (max 0 (dec (or source-line 1)))
        start-idx (nearest-jsdoc-start
                   lines
                   (max 0 (- member-idx typescript-declaration-member-before-lines))
                   member-idx)
        end-idx (min (count lines)
                     (+ member-idx typescript-declaration-member-after-lines 1))
        chunk-text (->> lines
                        (drop start-idx)
                        (take (- end-idx start-idx))
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))
        label (str module-name "/" name)]
    (cond-> {:xt/id (chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (inc start-idx)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (inc start-idx) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn extract-js-family
  "Extract bounded module facts from JavaScript and TypeScript source files."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [module-name (source-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        defs (->> lines
                  (map-indexed js-definition-line)
                  (keep identity)
                  (mapv (fn [{:keys [kind name public? source-line]}]
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
                             :run-id run-id}))))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (->> lines
                          (map-indexed #(js-import-targets %1 path %2))
                          (mapcat identity)
                          (mapv #(edge-row run-id file-id path
                                           (:xt/id ns-node)
                                           (node-id id-scope :namespace (:target %))
                                           :imports
                                           :extracted
                                           (:source-line %))))
        chunk-text (str/join "\n" (take 100 lines))
        chunk-kind (case kind
                     :typescript :typescript-file
                     :javascript-file)
        chunk {:xt/id (chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind chunk-kind
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(js-definition-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      lines
                                                      %)
                                defs)
        declaration-member-chunks (if (and (= :typescript kind)
                                           (typescript-declaration-path? path))
                                    (->> lines
                                         (map-indexed typescript-declaration-member-line)
                                         (keep identity)
                                         (take typescript-declaration-member-limit)
                                         (mapv #(typescript-declaration-member-chunk
                                                 run-id
                                                 id-scope
                                                 file-id
                                                 path
                                                 module-name
                                                 lines
                                                 %)))
                                    [])]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] (concat definition-chunks
                                   declaration-member-chunks))
     :diagnostics []}))

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

(defn- java-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_$][A-Za-z0-9_$.]*)\s*;\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- java-type-kind
  [kind]
  (case kind
    "class" :class
    "interface" :interface
    "enum" :enum
    "record" :record
    "@interface" :annotation
    :java-symbol))

(defn- java-type-line
  [idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(public|protected|private)\s+)?(?:(?:abstract|final|sealed|non-sealed|static|strictfp)\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b.*"
              line)]
    {:kind (java-type-kind kind)
     :name name
     :public? (= "public" visibility)
     :source-line (inc idx)}))

(def ^:private java-method-exclusions
  #{"catch" "do" "else" "for" "if" "new" "return" "switch" "try" "while"})

(defn- java-method-line
  [current-type idx line]
  (when current-type
    (when-let [[_ visibility _modifiers return-type name]
               (re-matches
                #"^\s*(?:(public|protected|private)\s+)?((?:(?:static|final|abstract|synchronized|native|strictfp|default)\s+)*)?(?:<[^>]+>\s*)?(?:(\S(?:.*\S)?)\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^;{}]*\)\s*(?:throws\b[^{;]*)?(?:\{|;)?\s*$"
                line)]
      (when-not (contains? java-method-exclusions name)
        (let [constructor? (= name (:name current-type))
              explicit-member? (or visibility
                                   (seq (str/trim (or _modifiers "")))
                                   return-type
                                   constructor?)
              call-prefix (subs line 0 (or (str/index-of line (str name "("))
                                           0))]
          (when (and explicit-member?
                     (not (str/includes? call-prefix "=")))
            {:kind (if constructor? :constructor :method)
             :name (str (:name current-type) "." name)
             :public? (= "public" visibility)
             :source-line (inc idx)}))))))

(defn- java-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ static? target]
                          (re-matches
                           #"^\s*import\s+(static\s+)?([A-Za-z_$][A-Za-z0-9_$.*]*)\s*;\s*$"
                           line)]
                 {:target target
                  :static? (boolean static?)
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- java-import-symbol-label
  [{:keys [target static? static]}]
  (let [target (str target)]
    (when (and (not (or static? static))
               (not (str/ends-with? target ".*"))
               (str/includes? target "."))
      (let [parts (str/split target #"\.")
            type-name (peek parts)
            namespace-name (str/join "." (pop parts))]
        (when (and (seq namespace-name) (seq type-name))
          (str namespace-name "/" type-name))))))

(defn- java-import-symbols-by-simple-name
  [imports]
  (->> imports
       (keep (fn [import]
               (when-let [label (java-import-symbol-label import)]
                 [(last (str/split label #"/")) label])))
       (group-by first)
       (keep (fn [[simple-name rows]]
               (let [labels (set (map second rows))]
                 (when (= 1 (count labels))
                   [simple-name (first labels)]))))
       (into {})))

(defn- java-reference-target-label
  [module-name import-symbols target-name]
  (or (get import-symbols target-name)
      (str module-name "/" target-name)))

(def ^:private java-type-reference-pattern
  #"\b[A-Z][A-Za-z0-9_$]*\b")

(defn- java-type-reference-names
  [text]
  (->> (re-seq java-type-reference-pattern (or text ""))
       distinct
       vec))

(defn- java-starts-with-at?
  [^String text ^long idx ^String prefix]
  (.startsWith text prefix (int idx)))

(defn- java-mask-char!
  [^StringBuilder sb ch]
  (if (= \newline ch)
    (.append sb ch)
    (.append sb \space)))

(defn- java-code-without-comments-or-strings
  [text]
  (let [^String text (or text "")
        length (count text)
        sb (StringBuilder. length)]
    (loop [idx 0
           state :code
           string-delim nil
           escaped? false]
      (if (>= idx length)
        (str sb)
        (let [ch (.charAt text idx)]
          (case state
            :code
            (cond
              (java-starts-with-at? text idx "//")
              (do (.append sb "  ")
                  (recur (+ idx 2) :line-comment nil false))

              (java-starts-with-at? text idx "/*")
              (do (.append sb "  ")
                  (recur (+ idx 2) :block-comment nil false))

              (java-starts-with-at? text idx "\"\"\"")
              (do (.append sb "   ")
                  (recur (+ idx 3) :text-block nil false))

              (or (= \" ch) (= \' ch))
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :string ch false))

              :else
              (do (.append sb ch)
                  (recur (inc idx) :code nil false)))

            :line-comment
            (if (= \newline ch)
              (do (.append sb ch)
                  (recur (inc idx) :code nil false))
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :line-comment nil false)))

            :block-comment
            (if (java-starts-with-at? text idx "*/")
              (do (.append sb "  ")
                  (recur (+ idx 2) :code nil false))
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :block-comment nil false)))

            :string
            (cond
              (= \newline ch)
              (do (.append sb ch)
                  (recur (inc idx) :code nil false))

              escaped?
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :string string-delim false))

              (= \\ ch)
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :string string-delim true))

              (= string-delim ch)
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :code nil false))

              :else
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :string string-delim false)))

            :text-block
            (if (java-starts-with-at? text idx "\"\"\"")
              (do (.append sb "   ")
                  (recur (+ idx 3) :code nil false))
              (do (java-mask-char! sb ch)
                  (recur (inc idx) :text-block nil false)))))))))

(def ^:private java-reference-line-char-limit 4096)

(def ^:private java-inheritance-line-pattern
  #"\b(?:extends|implements|throws)\s+([^;{]+)")

(def ^:private java-new-line-pattern
  #"\bnew\s+([A-Z][A-Za-z0-9_$.]*)\s*(?:<[^>]*>)?\s*[\(\{]")

(def ^:private java-method-return-line-pattern
  #"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default)\s+)*([A-Za-z_$][A-Za-z0-9_$.?<>,\s\[\]]*)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*\(")

(def ^:private java-constructor-params-line-pattern
  #"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default|transient|volatile)\s+)*(?:[A-Za-z0-9_$.?<>,\s\[\]]+\s+)?[A-Za-z_$][A-Za-z0-9_$]*\s*\(([^)]*)\)")

(def ^:private java-field-type-line-pattern
  #"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|static|final|transient|volatile)\s+)*([A-Za-z_$][A-Za-z0-9_$.?<>,\s\[\]]*)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*(?:=|;|,)")

(def ^:private java-body-control-line-prefixes
  ["if " "if(" "for " "for(" "while " "while(" "switch " "switch("
   "catch " "catch(" "return " "throw " "else" "do " "try " "case "
   "default:"])

(defn- java-reference-scan-line
  [line]
  (let [line (or line "")]
    (if (> (count line) java-reference-line-char-limit)
      (subs line 0 java-reference-line-char-limit)
      line)))

(defn- java-declaration-like-line?
  [line]
  (let [trimmed (str/triml (or line ""))]
    (not-any? #(str/starts-with? trimmed %)
              java-body-control-line-prefixes)))

(defn- java-capture-segments
  [pattern line]
  (map second (re-seq pattern line)))

(defn- java-type-position-line-segments
  [line]
  (let [line (java-reference-scan-line line)
        declaration-like? (java-declaration-like-line? line)]
    (->> (concat
          (when (or (str/includes? line "extends")
                    (str/includes? line "implements")
                    (str/includes? line "throws"))
            (java-capture-segments java-inheritance-line-pattern line))
          (when (str/includes? line "new ")
            (java-capture-segments java-new-line-pattern line))
          (when (and declaration-like?
                     (str/includes? line "("))
            (concat
             (java-capture-segments java-method-return-line-pattern line)
             (java-capture-segments java-constructor-params-line-pattern line)))
          (when (and declaration-like?
                     (or (str/includes? line "=")
                         (str/includes? line ";")
                         (str/includes? line ",")))
            (java-capture-segments java-field-type-line-pattern line)))
         (remove str/blank?))))

(defn- java-type-position-reference-names
  [text]
  (let [code (java-code-without-comments-or-strings text)
        segments (mapcat java-type-position-line-segments
                         (str/split-lines code))]
    (->> segments
         (mapcat java-type-reference-names)
         distinct
         vec)))

(defn- java-definition-forms
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
              type-form (java-type-line idx line)
              method-form (when-not type-form
                            (java-method-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      method-form (conj method-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (get line-starts idx 0)]
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

(defn extract-java
  "Extract bounded package, import, and declaration facts from Java source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (java-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        imports (java-imports lines)
        import-symbols (java-import-symbols-by-simple-name imports)
        def-forms (java-definition-forms content)
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
                           imports)
        reference-edges (->> def-forms
                             (mapcat
                              (fn [{:keys [name source-line text]}]
                                (let [source-id (node-id id-scope
                                                         :symbol
                                                         (str module-name "/" name))
                                      source-type (first (str/split name #"\."))
                                      target-names (java-type-position-reference-names text)]
                                  (->> target-names
                                       (remove #(= source-type %))
                                       (map (fn [target-name]
                                              (edge-row
                                               run-id
                                               file-id
                                               path
                                               source-id
                                               (node-id id-scope
                                                        :symbol
                                                        (java-reference-target-label
                                                         module-name
                                                         import-symbols
                                                         target-name))
                                               :references
                                               :extracted
                                               source-line)))))))
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
                                               "Java")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges reference-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

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
                        (java-module-name path content))
        ns-node (namespace-node run-id id-scope file-id path module-name)
        definitions (vec (:definitions facts))
        imports (vec (:imports facts))
        import-symbols (java-import-symbols-by-simple-name imports)
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
                                                   (java-reference-target-label
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
        import-symbols (java-import-symbols-by-simple-name imports)
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
                                                   (java-reference-target-label
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

(defn- ruby-module-name
  [path]
  (source-module-name path))

(defn- ruby-requires
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*require(?:_relative)?\s+['\"]([^'\"]+)['\"].*"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- ruby-type-line
  [current-types idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(module|class)\s+([A-Z][A-Za-z0-9_:]*)\b.*" line)]
    (let [full-name (if (or (str/includes? name "::") (empty? current-types))
                      name
                      (str (str/join "::" current-types) "::" name))]
      {:kind (case kind
               "module" :module
               "class" :class)
       :name full-name
       :scope-name name
       :public? true
       :source-line (inc idx)})))

(defn- ruby-method-line
  [current-types idx line]
  (when-let [[_ self? name]
             (re-matches #"^\s*def\s+(self\.)?([A-Za-z_][A-Za-z0-9_!?=]*)\b.*" line)]
    {:kind :method
     :name (if (seq current-types)
             (str (str/join "::" current-types)
                  (if self? "." "#")
                  name)
             name)
     :public? (not (str/starts-with? name "_"))
     :source-line (inc idx)}))

(defn- ruby-constant-line
  [current-types idx line]
  (when-let [[_ name]
             (re-matches #"^\s*([A-Z][A-Za-z0-9_]*)\s*=.*" line)]
    {:kind :constant
     :name (if (seq current-types)
             (str (str/join "::" current-types) "::" name)
             name)
     :public? true
     :source-line (inc idx)}))

(defn- ruby-task-line
  [idx line]
  (when-let [[_ keyword-name string-name]
             (re-matches #"^\s*task\s+(?::([A-Za-z_][A-Za-z0-9_-]*)|['\"]([^'\"]+)['\"]).*" line)]
    {:kind :task
     :name (or keyword-name string-name)
     :public? true
     :source-line (inc idx)}))

(defn- ruby-block-start?
  [line]
  (or (re-find #"\b(if|unless|case|begin|for|while|until)\b" line)
      (re-find #"\bdo\b" line)))

(defn- ruby-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           stack []
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)
              stack* (if (and (= "end" trimmed) (seq stack))
                       (pop stack)
                       stack)
              current-types (->> stack*
                                 (filter #(= :type (:kind %)))
                                 (map :scope-name)
                                 vec)
              type-form (ruby-type-line current-types idx line)
              method-form (when-not type-form
                            (ruby-method-line current-types idx line))
              constant-form (when-not (or type-form method-form)
                              (ruby-constant-line current-types idx line))
              task-form (when-not (or type-form method-form constant-form)
                          (ruby-task-line idx line))
              form (or type-form method-form constant-form task-form)
              block-kind (cond
                           type-form :type
                           method-form :block
                           task-form :block
                           (ruby-block-start? line) :block)
              stack** (cond-> stack*
                        block-kind (conj (cond-> {:kind block-kind}
                                           type-form
                                           (assoc :scope-name (:scope-name type-form)))))]
          (recur (rest remaining)
                 stack**
                 (cond-> out
                   form (conj (assoc form :text line)))))
        out))))

(defn extract-ruby
  "Extract bounded require and declaration facts from Ruby source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (ruby-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (ruby-definition-forms content)
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
        require-edges (mapv #(edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (node-id id-scope :namespace (:target %))
                                       :imports
                                       :extracted
                                       (:source-line %))
                            (ruby-requires lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :ruby-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges require-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- cpp-module-name
  [path]
  (source-module-name path))

(defn- cpp-includes
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*#\s*include\s*[<\"]([^>\"]+)[>\"].*"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- cpp-definition-kind
  [kind]
  (case kind
    "class" :class
    "struct" :struct
    "enum" :enum
    :function))

(def cpp-function-exclusions
  #{"if" "for" "while" "switch" "catch" "return" "sizeof"})

(defn- cpp-namespace-line
  [idx line]
  (when-let [[_ name]
             (re-matches #"^\s*namespace\s+([A-Za-z_][A-Za-z0-9_:]*)\s*\{.*" line)]
    {:kind :namespace
     :name name
     :source-line (inc idx)}))

(defn- cpp-type-line
  [namespaces idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(class|struct|enum(?:\s+class)?)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
    {:kind (cpp-definition-kind (first (str/split kind #"\s+")))
     :name (if (seq namespaces)
             (str (str/join "::" namespaces) "::" name)
             name)
     :public? true
     :source-line (inc idx)}))

(defn- cpp-macro-line
  [idx line]
  (when-let [[_ name]
             (re-matches #"^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
    {:kind :macro
     :name name
     :public? true
     :source-line (inc idx)}))

(defn- cpp-typedef-line
  [namespaces idx line]
  (when-let [[_ name]
             (or (re-matches #"^\s*typedef\b.*\s+([A-Za-z_][A-Za-z0-9_]*)\s*;\s*$" line)
                 (re-matches #"^\s*using\s+([A-Za-z_][A-Za-z0-9_]*)\s*=.*;\s*$" line))]
    {:kind :typedef
     :name (if (seq namespaces)
             (str (str/join "::" namespaces) "::" name)
             name)
     :public? true
     :source-line (inc idx)}))

(defn- cpp-function-line
  [namespaces idx line]
  (when-let [[_ name]
             (re-matches
              #"^\s*(?:template\s*<[^>]+>\s*)?(?:(?:inline|static|constexpr|virtual|extern|friend|explicit)\s+)*(?:[A-Za-z_][A-Za-z0-9_:<>,*&\s~]*\s+)?([A-Za-z_~][A-Za-z0-9_:~]*)\s*\([^;]*\)\s*(?:const\s*)?(?:noexcept\s*)?(?:->\s*[A-Za-z_][A-Za-z0-9_:<>,*&\s]*)?\{.*"
              line)]
    (when-not (contains? cpp-function-exclusions name)
      {:kind :function
       :name (if (seq namespaces)
               (let [prefix (str (str/join "::" namespaces) "::")]
                 (if (str/starts-with? name prefix)
                   name
                   (str prefix name)))
               name)
       :public? true
       :source-line (inc idx)})))

(defn- cpp-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           namespace-stack []
           out []]
      (if-let [[idx line] (first remaining)]
        (let [namespace-stack (pop-closed-type-scopes namespace-stack depth)
              namespaces (mapv :name namespace-stack)
              namespace-form (cpp-namespace-line idx line)
              type-form (cpp-type-line namespaces idx line)
              function-form (when-not type-form
                              (cpp-function-line namespaces idx line))
              macro-form (when-not (or type-form function-form)
                           (cpp-macro-line idx line))
              typedef-form (when-not (or type-form function-form macro-form)
                             (cpp-typedef-line namespaces idx line))
              forms (cond-> []
                      namespace-form (conj namespace-form)
                      type-form (conj type-form)
                      function-form (conj function-form)
                      macro-form (conj macro-form)
                      typedef-form (conj typedef-form))
              forms (mapv (fn [{:keys [name] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (str/index-of line name) 0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line))))
                          forms)
              delta (curly-depth-delta line)
              depth* (+ depth delta)
              namespace-stack* (cond-> namespace-stack
                                 (and namespace-form (pos? delta))
                                 (conj {:name (:name namespace-form)
                                        :end-depth depth*}))]
          (recur (rest remaining)
                 depth*
                 namespace-stack*
                 (into out forms)))
        out))))

(defn extract-cpp
  "Extract bounded include and declaration facts from C and C++ source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (cpp-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (cpp-definition-forms content)
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
        include-edges (mapv #(edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (node-id id-scope :namespace (:target %))
                                       :imports
                                       :extracted
                                       (:source-line %))
                            (cpp-includes lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :cpp-file
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
                                               "C/C++")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges include-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- objective-c-module-name
  [path]
  (source-module-name path))

(defn- objective-c-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (concat
           (map (fn [[_ target]]
                  {:target target
                   :source-line (inc idx)})
                (re-seq #"^\s*#\s*import\s*[<\"]([^>\"]+)[>\"].*" line))
           (map (fn [[_ target]]
                  {:target target
                   :source-line (inc idx)})
                (re-seq #"^\s*@import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;.*" line)))))
       distinct
       vec))

(defn- objective-c-definition-kind
  [kind]
  (case kind
    "interface" :interface
    "implementation" :implementation
    "protocol" :protocol
    "category" :category
    "enum" :enum
    "class" :forward-class
    :objective-c-symbol))

(defn- objective-c-type-line
  [idx line]
  (or (when-let [[_ kind name category]
                 (re-matches
                  #"^\s*@(interface|implementation)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\))?.*"
                  line)]
        (let [name (if (str/blank? category)
                     name
                     (str name "." category))]
          {:kind (objective-c-definition-kind (if (str/blank? category)
                                                kind
                                                "category"))
           :name (if (and (= "implementation" kind)
                          (str/blank? category))
                   (str name ".implementation")
                   name)
           :container name
           :public? true
           :source-line (inc idx)
           :text line}))
      (when-let [[_ name]
                 (re-matches #"^\s*@protocol\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :protocol
         :name name
         :container name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ names]
                 (re-matches #"^\s*@class\s+([^;]+);.*" line)]
        {:kind :forward-class
         :name (-> names
                   (str/split #",")
                   first
                   str/trim)
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches
                  #"^\s*typedef\s+NS_(?:CLOSED_)?(?:ENUM|OPTIONS)\s*\([^,]+,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\).*"
                  line)]
        {:kind :enum
         :name name
         :public? true
         :source-line (inc idx)
         :text line})))

(defn- objective-c-method-line
  [current-type idx line content line-starts]
  (when current-type
    (when-let [[_ marker name]
               (re-matches #"^\s*([-+])\s*\([^)]*\)\s*([A-Za-z_][A-Za-z0-9_]*)\b.*"
                           line)]
      (let [start (+ (get line-starts idx 0)
                     (or (str/index-of line marker) 0))]
        {:kind (if (= "+" marker) :class-method :method)
         :name (str (:name current-type) "." name)
         :public? true
         :source-line (inc idx)
         :text (or (balanced-curly-block content start) line)}))))

(defn- objective-c-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           type-stack []
           out []]
      (if-let [[idx line] (first remaining)]
        (let [end-type? (boolean (re-matches #"^\s*@end\b.*" line))
              type-stack (if end-type? (pop type-stack) type-stack)
              current-type (peek type-stack)
              type-form (when-not end-type?
                          (objective-c-type-line idx line))
              method-form (when-not type-form
                            (objective-c-method-line current-type
                                                     idx
                                                     line
                                                     content
                                                     line-starts))
              type-stack* (cond-> type-stack
                            (:container type-form)
                            (conj {:name (:container type-form)}))]
          (recur (rest remaining)
                 type-stack*
                 (cond-> out
                   type-form (conj (dissoc type-form :container))
                   method-form (conj method-form))))
        out))))

(defn extract-objective-c
  "Extract bounded import and declaration facts from Objective-C source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (objective-c-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (objective-c-definition-forms content)
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
                           (objective-c-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :objective-c-file
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
                                               "Objective-C")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- dart-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*library\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- dart-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*(?:import|export|part)\s+['\"]([^'\"]+)['\"].*"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- dart-public?
  [name]
  (not (str/starts-with? (str name) "_")))

(defn- dart-type-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches
              #"^\s*(?:(?:abstract|base|final|interface|sealed)\s+)*(class|enum|mixin|extension)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "class" :class
             "enum" :enum
             "mixin" :mixin
             "extension" :extension)
     :name name
     :public? (dart-public? name)
     :source-line (inc idx)}))

(defn- dart-member-line
  [current-type idx line]
  (or (when (and current-type
                 (re-matches (re-pattern (str "^\\s*" (:name current-type)
                                              "\\s*\\(.*"))
                             line))
        {:kind :constructor
         :name (str (:name current-type) "." (:name current-type))
         :public? true
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches
                  #"^\s*(?:(?:static|external)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,?.\[\]\s]*\s+)?get\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                  line)]
        {:kind :getter
         :name (if current-type
                 (str (:name current-type) "." name)
                 name)
         :public? (dart-public? name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches
                  #"^\s*(?:(?:static|external)\s+)*set\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                  line)]
        {:kind :setter
         :name (if current-type
                 (str (:name current-type) "." name)
                 name)
         :public? (dart-public? name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches
                  #"^\s*(?:(?:static|external|factory|late|final|const|async)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,?.\[\]\s]*\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*(?:async\s*)?\{?.*"
                  line)]
        (when-not (contains? #{"if" "for" "while" "switch" "catch"} name)
          {:kind :function
           :name (if current-type
                   (str (:name current-type) "." name)
                   name)
           :public? (dart-public? name)
           :source-line (inc idx)}))
      (when-let [[_ name]
                 (re-matches
                  #"^\s*(?:(?:static|late|final|const)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,?.\[\]\s]*\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=.*)?;\s*$"
                  line)]
        {:kind :variable
         :name (if current-type
                 (str (:name current-type) "." name)
                 name)
         :public? (dart-public? name)
         :source-line (inc idx)})))

(defn- dart-definition-forms
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
              type-form (dart-type-line idx line)
              member-form (when-not type-form
                            (dart-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [name] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (str/index-of line name) 0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line))))
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

(defn extract-dart
  "Extract bounded import and declaration facts from Dart source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (dart-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (dart-definition-forms content)
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
                           (dart-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :dart-file
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
                                               "Dart")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- scala-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- scala-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+([A-Za-z_][A-Za-z0-9_.*{},\s]+)\s*$"
                                      line)]
                 {:target (-> target
                              (str/replace #"\s+" "")
                              (str/replace #"\{.*" ""))
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- scala-type-line
  [idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(private|protected)\s+)?(?:(?:case|sealed|abstract|final|implicit|lazy)\s+)*(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "class" :class
             "trait" :trait
             "object" :object
             "enum" :enum)
     :name name
     :public? (not= "private" visibility)
     :source-line (inc idx)}))

(defn- scala-member-line
  [current-type idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(private|protected)\s+)?(?:(?:override|inline|implicit|lazy|final)\s+)*(def|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "def" :function
             "val" :property
             "var" :property)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))

(defn- scala-definition-forms
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
              type-form (scala-type-line idx line)
              member-form (when-not type-form
                            (scala-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [name] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (str/index-of line name) 0))]
                              (assoc form
                                     :text (or (balanced-curly-block content start)
                                               line))))
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

(defn extract-scala
  "Extract bounded package, import, and declaration facts from Scala source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (scala-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (scala-definition-forms content)
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
                           (scala-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :scala-file
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
                                               "Scala")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- elixir-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*defmodule\s+([A-Za-z_][A-Za-z0-9_.]*)\s+do\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- elixir-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*(?:alias|import|require|use)\s+([A-Z][A-Za-z0-9_.]*)(?:[,\s].*)?$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- elixir-definition-line
  [module-name idx line]
  (or (when-let [[_ name]
                 (re-matches #"^\s*defmodule\s+([A-Za-z_][A-Za-z0-9_.]*)\s+do\s*$"
                             line)]
        {:kind :module
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ kind name]
                 (re-matches #"^\s*(defp?|defmacro)\s+([A-Za-z_][A-Za-z0-9_!?]*)\b.*"
                             line)]
        {:kind (if (= "defmacro" kind) :macro :function)
         :name (str module-name "." name)
         :public? (not= "defp" kind)
         :source-line (inc idx)
         :text line})
      (when-let [[_ target]
                 (re-matches #"^\s*@behaviour\s+([A-Z][A-Za-z0-9_.]*)\s*$" line)]
        {:kind :behaviour
         :name target
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*@callback\s+([A-Za-z_][A-Za-z0-9_!?]*)\b.*" line)]
        {:kind :callback
         :name (str module-name "." name)
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*(?:Record\.)?defrecord\s*\(?\s*:([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
        {:kind :record
         :name (str module-name "." name)
         :public? true
         :source-line (inc idx)
         :text line})))

(defn extract-elixir
  "Extract bounded module, import, and function facts from Elixir source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (elixir-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed #(elixir-definition-line module-name %1 %2))
                       (keep identity)
                       vec)
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
                           (elixir-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :elixir-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- erlang-module-name
  [path content]
  (or (some-> (re-find #"(?m)^\s*-module\(([A-Za-z_][A-Za-z0-9_]*)\)\." content)
              second)
      (source-module-name path)))

(defn- erlang-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ target]
                              (re-matches #"^\s*-include(?:_lib)?\(\"([^\"]+)\"\)\..*" line)]
                     {:target target
                      :source-line (inc idx)})
                   (when-let [[_ target]
                              (re-matches #"^\s*-import\(([A-Za-z_][A-Za-z0-9_]*),\s*\[.*" line)]
                     {:target target
                      :source-line (inc idx)}))))
       distinct
       vec))

(defn- erlang-definition-line
  [idx line]
  (or (when-let [[_ name arity-args]
                 (re-matches #"^\s*([a-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:when\s+.*?)?->.*"
                             line)]
        (let [arity (if (str/blank? (str/trim arity-args))
                      0
                      (count (str/split arity-args #",")))]
          {:kind :function
           :name (str name "/" arity)
           :public? true
           :source-line (inc idx)
           :text line}))
      (when-let [[_ name]
                 (re-matches #"^\s*-behaviou?r\(([a-z_][A-Za-z0-9_]*)\)\..*" line)]
        {:kind :behaviour
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*-record\(([a-z_][A-Za-z0-9_]*)\s*,.*" line)]
        {:kind :record
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*-callback\s+([a-z_][A-Za-z0-9_]*)\s*\(.*" line)]
        {:kind :callback
         :name name
         :public? true
         :source-line (inc idx)
         :text line})))

(defn extract-erlang
  "Extract bounded module, include/import, and function facts from Erlang source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (erlang-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed erlang-definition-line)
                       (keep identity)
                       vec)
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
                           (erlang-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :erlang-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- lua-module-name
  [path]
  (source-module-name path))

(defn- lua-requires
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #".*\brequire\s*\(?\s*['\"]([^'\"]+)['\"].*" line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- lua-definition-line
  [idx line]
  (or (when-let [[_ table-name function-name]
                 (re-matches #"^\s*function\s+([A-Za-z_][A-Za-z0-9_.:]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)]
        {:kind :function
         :name (str table-name "." function-name)
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ function-name]
                 (re-matches #"^\s*(?:local\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)]
        {:kind :function
         :name function-name
         :public? (not (str/starts-with? function-name "_"))
         :source-line (inc idx)
         :text line})
      (when-let [[_ table-name]
                 (re-matches #"^\s*(?:local\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\{\s*\}.*" line)]
        {:kind :module
         :name table-name
         :public? true
         :source-line (inc idx)
         :text line})))

(defn extract-lua
  "Extract bounded require and declaration facts from Lua source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (lua-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed lua-definition-line)
                       (keep identity)
                       vec)
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
        require-edges (mapv #(edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (node-id id-scope :namespace (:target %))
                                       :imports
                                       :extracted
                                       (:source-line %))
                            (lua-requires lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :lua-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges require-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- r-module-name
  [path]
  (source-module-name path))

(defn- r-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #".*\b(?:library|require)\s*\(\s*['\"]?([A-Za-z_][A-Za-z0-9_.]*)['\"]?.*" line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- r-definition-line
  [idx line]
  (when-let [[_ name]
             (re-matches #"^\s*([A-Za-z_.][A-Za-z0-9_.]*)\s*(?:<-|=)\s*function\s*\(.*" line)]
    {:kind :function
     :name name
     :public? (not (str/starts-with? name "."))
     :source-line (inc idx)
     :text line}))

(defn extract-r
  "Extract bounded package import and function facts from R source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (r-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed r-definition-line)
                       (keep identity)
                       vec)
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
                           (r-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :r-file
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
        diagnostics (curly-balance-diagnostics run-id file-id path content "R")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- julia-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*module\s+([A-Z][A-Za-z0-9_]*)\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- julia-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*(?:using|import)\s+([A-Za-z_][A-Za-z0-9_.:]*).*$" line)]
                 {:target (str/replace target #":.*" "")
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- julia-definition-line
  [idx line]
  (or (when-let [[_ kind name]
                 (re-matches #"^\s*(mutable\s+struct|struct|abstract\s+type|primitive\s+type)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
        {:kind (case kind
                 "struct" :struct
                 "mutable struct" :struct
                 "abstract type" :type
                 "primitive type" :type)
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*function\s+([A-Za-z_][A-Za-z0-9_!.]*)\s*\(.*" line)]
        {:kind :function
         :name name
         :public? (not (str/starts-with? name "_"))
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_!.]*)\s*\([^)]*\)\s*=.*" line)]
        {:kind :function
         :name name
         :public? (not (str/starts-with? name "_"))
         :source-line (inc idx)
         :text line})))

(defn extract-julia
  "Extract bounded module, import, type, and function facts from Julia source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (julia-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed julia-definition-line)
                       (keep identity)
                       vec)
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
                           (julia-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :julia-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- ocaml-module-name
  [path]
  (source-module-name path))

(defn- ocaml-import-targets
  [idx line]
  (->> (concat
        (map second (re-seq #"^\s*(?:open|include)\s+([A-Z][A-Za-z0-9_.]*)\b.*" line))
        (map second (re-seq #"^\s*module\s+[A-Z][A-Za-z0-9_']*\s*=\s*([A-Z][A-Za-z0-9_.]*)\b.*" line)))
       (map (fn [target]
              {:target target
               :source-line (inc idx)}))
       distinct))

(defn- ocaml-public?
  [name]
  (not (str/starts-with? (str name) "_")))

(defn- ocaml-definition-line
  [idx line]
  (or (when-let [[_ name]
                 (re-matches #"^\s*module\s+type\s+([A-Z][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :module-type
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*module\s+([A-Z][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :module
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*type\s+(?:nonrec\s+)?(?:'?[A-Za-z_][A-Za-z0-9_']*\s+)?([a-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :type
         :name name
         :public? (ocaml-public? name)
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*exception\s+([A-Z][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :exception
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*class\s+(?:virtual\s+)?([a-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :class
         :name name
         :public? (ocaml-public? name)
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*external\s+([a-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :external
         :name name
         :public? (ocaml-public? name)
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*val\s+([a-z_][A-Za-z0-9_']*)\s*:.*"
                             line)]
        {:kind :value
         :name name
         :public? (ocaml-public? name)
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*let\s+(?:rec\s+)?(?:@[A-Za-z0-9_.]+\s+)*([a-z_][A-Za-z0-9_']*)\b.*"
                             line)]
        {:kind :function
         :name name
         :public? (ocaml-public? name)
         :source-line (inc idx)
         :text line})))

(defn extract-ocaml
  "Extract bounded module, import, type, value, and function facts from OCaml source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (ocaml-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed ocaml-definition-line)
                       (keep identity)
                       vec)
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
                           (->> lines
                                (map-indexed ocaml-import-targets)
                                (mapcat identity)
                                distinct))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :ocaml-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- perl-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_:]*)\s*;.*"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- perl-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*(?:use|require)\s+([A-Za-z_][A-Za-z0-9_:]*).*$" line)]
                 (when-not (contains? #{"strict" "warnings" "feature"} target)
                   {:target target
                    :source-line (inc idx)}))))
       distinct
       vec))

(defn- perl-definition-line
  [idx line]
  (when-let [[_ name]
             (re-matches #"^\s*sub\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
    {:kind :function
     :name name
     :public? (not (str/starts-with? name "_"))
     :source-line (inc idx)
     :text line}))

(defn extract-perl
  "Extract bounded package, use/require, and subroutine facts from Perl source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (perl-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed perl-definition-line)
                       (keep identity)
                       vec)
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
                           (perl-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :perl-file
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
        diagnostics (curly-balance-diagnostics run-id file-id path content "Perl")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- haskell-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*module\s+([A-Z][A-Za-z0-9_.]*)\s+where\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- haskell-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+(?:qualified\s+)?([A-Z][A-Za-z0-9_.]*).*$" line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- haskell-definition-line
  [idx line]
  (or (when-let [[_ kind name]
                 (re-matches #"^\s*(data|newtype|type|class)\s+([A-Z][A-Za-z0-9_]*)\b.*" line)]
        {:kind (case kind
                 "data" :type
                 "newtype" :type
                 "type" :type
                 "class" :class)
         :name name
         :public? true
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^([a-z_][A-Za-z0-9_']*)\s*::\s*.*" line)]
        {:kind :function
         :name name
         :public? (not (str/starts-with? name "_"))
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^([a-z_][A-Za-z0-9_']*)\s+.*=\s*.*" line)]
        {:kind :function
         :name name
         :public? (not (str/starts-with? name "_"))
         :source-line (inc idx)
         :text line})))

(defn extract-haskell
  "Extract bounded module, import, type, and function facts from Haskell source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (haskell-module-name path content)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed haskell-definition-line)
                       (keep identity)
                       distinct
                       vec)
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
                           (haskell-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :haskell-file
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
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))

(defn- odin-source-file?
  [path]
  (= ".odin" (str/lower-case (or (fs/extension path) ""))))

(defn- odin-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)\s*$"
                                  line)))
            (str/split-lines content))
      (source-module-name path)))

(defn- odin-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ alias target]
                          (re-matches #"^\s*import\s+(?:(?:([A-Za-z_][A-Za-z0-9_]*)\s+)?\"([^\"]+)\"|\(\s*)\s*$"
                                      line)]
                 (when target
                   {:target target
                    :alias alias
                    :source-line (inc idx)}))))
       distinct
       vec))

(defn- odin-definition-line
  [idx line]
  (or (when-let [[_ public? name]
                 (re-matches #"^\s*(pub\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*::\s*proc\b.*"
                             line)]
        {:kind :function
         :name name
         :public? (or (boolean public?) (not (str/starts-with? name "_")))
         :source-line (inc idx)
         :text line})
      (when-let [[_ public? name kind]
                 (re-matches #"^\s*(pub\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*::\s*(struct|enum|union)\b.*"
                             line)]
        {:kind (case kind
                 "struct" :struct
                 "enum" :enum
                 "union" :union)
         :name name
         :public? (or (boolean public?) (not (str/starts-with? name "_")))
         :source-line (inc idx)
         :text line})
      (when-let [[_ public? name]
                 (re-matches #"^\s*(pub\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*::\s*(?!proc\b|struct\b|enum\b|union\b).+"
                             line)]
        {:kind :constant
         :name name
         :public? (or (boolean public?) (not (str/starts-with? name "_")))
         :source-line (inc idx)
         :text line})
      (when-let [[_ name]
                 (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:=\s*.+"
                             line)]
        {:kind :var
         :name name
         :public? (not (str/starts-with? name "_"))
         :source-line (inc idx)
         :text line})))

(defn- odin-foreign-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ name target]
                          (re-matches #"^\s*foreign\s+import\s+([A-Za-z_][A-Za-z0-9_]*)\s+\"([^\"]+)\".*"
                                      line)]
                 {:kind :foreign-import
                  :name name
                  :target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- odin-config-facts
  [content path]
  (let [parsed (read-json-map content)
        scalar-fact (fn [kind label]
                      (when (seq (str label))
                        {:kind kind
                         :label (str label)
                         :source-line 1
                         :relation :defines}))]
    (if (map? parsed)
      (vec
       (remove nil?
               (concat
                [(scalar-fact :odin-config path)
                 (scalar-fact :odin-config-name (or (:name parsed) (:project parsed)))
                 (scalar-fact :odin-collection (some-> (:collections parsed) keys first name))
                 (scalar-fact :odin-checker-path (:checkerPath parsed))]
                (map (fn [[k v]]
                       (scalar-fact :odin-collection
                                    (str (name k) "="
                                         (if (string? v)
                                           v
                                           (json/write-json-str v)))))
                     (when (map? (:collections parsed))
                       (:collections parsed)))
                (map #(scalar-fact :odin-package-path %)
                     (filter string? (:packagePaths parsed))))))
      [{:kind :odin-config
        :label path
        :source-line 1
        :relation :defines}])))

(defn extract-odin
  "Extract bounded package, import, declaration, and config facts from Odin."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (if-not (odin-source-file? path)
    (extract-format-facts run-id file :odin-config-file :odin-config-file
                          (odin-config-facts content path))
    (let [module-name (odin-module-name path content)
          ns-node (namespace-node run-id id-scope file-id path module-name)
          package-node (generic-node run-id id-scope file-id path
                                     :odin-package module-name 1)
          lines (vec (str/split-lines content))
          def-forms (->> lines
                         (map-indexed odin-definition-line)
                         (keep identity)
                         vec)
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
          foreign-imports (odin-foreign-imports lines)
          foreign-nodes (mapv (fn [{:keys [name target source-line]}]
                                (generic-node run-id
                                              id-scope
                                              file-id
                                              path
                                              :foreign-import
                                              (str name ":" target)
                                              source-line))
                              foreign-imports)
          define-edges (mapv #(edge-row run-id file-id path
                                        (:xt/id ns-node)
                                        (:xt/id %)
                                        :defines
                                        :extracted
                                        (:source-line %))
                             (concat [package-node] defs foreign-nodes))
          import-edges (mapv #(edge-row run-id file-id path
                                        (:xt/id ns-node)
                                        (node-id id-scope :namespace (:target %))
                                        :imports
                                        :extracted
                                        (:source-line %))
                             (odin-imports lines))
          foreign-edges (mapv (fn [{:keys [name target source-line]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (node-id id-scope :foreign-import (str name ":" target))
                                          (node-id id-scope :namespace target)
                                          :imports
                                          :extracted
                                          source-line))
                              foreign-imports)
          chunk (source-text-chunk run-id
                                   id-scope
                                   file-id
                                   path
                                   :odin-file
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
          diagnostics (curly-balance-diagnostics run-id file-id path content "Odin")]
      {:nodes (vec (concat [ns-node package-node] defs foreign-nodes))
       :edges (vec (concat define-edges import-edges foreign-edges))
       :chunks (into [chunk] definition-chunks)
       :diagnostics diagnostics})))

(defn- zig-module-name
  [path]
  (source-module-name path))

(defn- zig-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #".*@import\(\"([^\"]+)\"\).*" line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- zig-definition-line
  [idx line]
  (or (when-let [[_ public? name]
                 (re-matches #"^\s*(pub\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)]
        {:kind :function
         :name name
         :public? (boolean public?)
         :source-line (inc idx)
         :text line})
      (when-let [[_ public? name kind]
                 (re-matches #"^\s*(pub\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(struct|enum|union)\b.*" line)]
        {:kind (case kind
                 "struct" :struct
                 "enum" :enum
                 "union" :union)
         :name name
         :public? (boolean public?)
         :source-line (inc idx)
         :text line})))

(defn extract-zig
  "Extract bounded import and declaration facts from Zig source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (zig-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed zig-definition-line)
                       (keep identity)
                       vec)
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
                           (zig-imports lines))
        chunk (source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :zig-file
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
        diagnostics (curly-balance-diagnostics run-id file-id path content "Zig")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))

(defn- manifest-name
  [path]
  (str/lower-case (last (str/split path #"/"))))

(defn- xml-tag-values
  [content tag]
  (let [pattern (re-pattern (str "(?is)<" tag "\\b[^>]*>(.*?)</" tag ">"))]
    (->> (re-seq pattern content)
         (map second)
         (map str/trim)
         (remove str/blank?)
         distinct
         vec)))

(defn- xml-attr-value
  [element attr]
  (or (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*\"([^\"]+)\""))
                       element))
      (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*'([^']+)'"))
                       element))))

(defn- maven-coordinates
  [content]
  (let [project-content (str/replace content
                                     #"(?is)<parent\b[^>]*>.*?</parent>"
                                     "")
        group-id (first (xml-tag-values project-content "groupId"))
        artifact-id (first (xml-tag-values project-content "artifactId"))]
    (when artifact-id
      (if group-id
        (str group-id ":" artifact-id)
        artifact-id))))

(defn- maven-dependencies
  [content]
  (->> (re-seq #"(?is)<dependency\b[^>]*>(.*?)</dependency>" content)
       (map second)
       (keep (fn [dependency]
               (let [group-id (first (xml-tag-values dependency "groupId"))
                     artifact-id (first (xml-tag-values dependency "artifactId"))
                     version (first (xml-tag-values dependency "version"))
                     scope (first (xml-tag-values dependency "scope"))]
                 (when (and group-id artifact-id)
                   (package-fact {:ecosystem :maven
                                  :package-name (str group-id ":" artifact-id)
                                  :version-range version
                                  :dependency-scope scope
                                  :source-line 1})))))
       distinct
       vec))

(defn- maven-coordinate-fact
  [kind relation block]
  (let [group-id (first (xml-tag-values block "groupId"))
        artifact-id (first (xml-tag-values block "artifactId"))]
    (when (and group-id artifact-id)
      {:kind kind
       :label (str group-id ":" artifact-id)
       :source-line 1
       :relation relation})))

(defn- maven-module-facts
  [content]
  (->> (xml-tag-values content "module")
       (map (fn [module]
              {:kind :maven-module
               :label module
               :source-line 1
               :relation :defines}))
       distinct
       vec))

(defn- maven-plugin-facts
  [content]
  (->> (re-seq #"(?is)<plugin\b[^>]*>(.*?)</plugin>" content)
       (map second)
       (keep #(maven-coordinate-fact :maven-plugin :uses %))
       distinct
       vec))

(defn- maven-profile-facts
  [content]
  (->> (re-seq #"(?is)<profile\b[^>]*>(.*?)</profile>" content)
       (map second)
       (keep (fn [profile]
               (when-let [profile-id (first (xml-tag-values profile "id"))]
                 {:kind :maven-profile
                  :label profile-id
                  :source-line 1
                  :relation :defines})))
       distinct
       vec))

(defn- maven-repository-facts
  [content]
  (->> (re-seq #"(?is)<repository\b[^>]*>(.*?)</repository>" content)
       (map second)
       (mapcat (fn [repository]
                 (let [repo-id (first (xml-tag-values repository "id"))
                       url (first (xml-tag-values repository "url"))]
                   (remove nil?
                           [(when repo-id
                              {:kind :maven-repository
                               :label repo-id
                               :source-line 1
                               :relation :references})
                            (when url
                              {:kind :maven-repository
                               :label url
                               :source-line 1
                               :relation :references})]))))
       distinct
       vec))

(defn- maven-build-facts
  [content]
  (vec (remove nil?
               [(when-let [packaging (first (xml-tag-values content "packaging"))]
                  {:kind :maven-packaging
                   :label packaging
                   :source-line 1
                   :relation :defines})
                (when-let [directory (first (xml-tag-values content "directory"))]
                  {:kind :maven-build-output
                   :label directory
                   :source-line 1
                   :relation :defines})
                (when-let [final-name (first (xml-tag-values content "finalName"))]
                  {:kind :maven-build-output
                   :label final-name
                   :source-line 1
                   :relation :defines})])))

(defn- maven-facts
  [content]
  (vec (concat
        (maven-dependencies content)
        (maven-module-facts content)
        (when-let [parent (some->> (re-find #"(?is)<parent\b[^>]*>(.*?)</parent>"
                                            content)
                                   second
                                   (maven-coordinate-fact :maven-parent
                                                          :references))]
          [parent])
        (maven-plugin-facts content)
        (maven-profile-facts content)
        (maven-repository-facts content)
        (maven-build-facts content))))

(defn- gradle-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*rootProject\.name\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      (some-> (re-find #"(?m)^\s*name\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      path))

(defn- gradle-line-facts
  [content fact-fn]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (fact-fn (inc idx) line)))
       (remove nil?)
       distinct
       vec))

(defn- gradle-dependencies
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (let [scope-pattern #"(api|compileOnly|implementation|runtimeOnly|testImplementation|testRuntimeOnly|testFixturesImplementation|annotationProcessor|kapt|classpath)"
           string-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                   "\\s*(?:\\(?\\s*)['\"]([^:'\"]+):([^:'\"]+)(?::([^'\"]+))?['\"].*$"))
                                  line)
           map-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                "\\s*(?:\\(?\\s*)group\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*:\\s*['\"]([^'\"]+)['\"])?\\s*\\)?.*$"))
                               line)
           project-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                    "\\s*(?:\\(?\\s*)project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\).*$"))
                                   line)]
       (cond
         string-dep
         (let [[_ scope group-id artifact-id version] string-dep]
           [(package-fact {:ecosystem :maven
                           :package-name (str group-id ":" artifact-id)
                           :version-range version
                           :dependency-scope scope
                           :source-line source-line})])

         map-dep
         (let [[_ scope group-id artifact-id version] map-dep]
           [(package-fact {:ecosystem :maven
                           :package-name (str group-id ":" artifact-id)
                           :version-range version
                           :dependency-scope scope
                           :source-line source-line})])

         project-dep
         (let [[_ scope project-path] project-dep]
           [{:kind :gradle-project-dependency
             :label project-path
             :dependency-scope scope
             :source-line source-line
             :relation :requires}])

         :else [])))))

(defn- gradle-plugins
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*id\s+['\"]([^'\"]+)['\"].*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*id\s+['\"]([^'\"]+)['\"].*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*id\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*id\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*alias\s*\(\s*([A-Za-z0-9_.-]+)\s*\).*$" line)
       (let [[_ plugin-ref] (re-matches #"^\s*alias\s*\(\s*([A-Za-z0-9_.-]+)\s*\).*$" line)]
         [{:kind :gradle-plugin
           :label (str "alias:" plugin-ref)
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*apply\s+plugin:\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*apply\s+plugin:\s*['\"]([^'\"]+)['\"].*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       :else []))))

(defn- gradle-repositories
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*(mavenCentral|google|gradlePluginPortal)\s*\(\s*\).*$" line)
       (let [[_ repository] (re-matches #"^\s*(mavenCentral|google|gradlePluginPortal)\s*\(\s*\).*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*maven\s*\{\s*url\s+['\"]([^'\"]+)['\"]\s*\}.*$" line)
       (let [[_ repository] (re-matches #"^\s*maven\s*\{\s*url\s+['\"]([^'\"]+)['\"]\s*\}.*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*url\s*(?:=)?\s*(?:uri\s*\()?\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ repository] (re-matches #"^\s*url\s*(?:=)?\s*(?:uri\s*\()?\s*['\"]([^'\"]+)['\"].*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       :else []))))

(defn- gradle-command-label
  [value]
  (->> (str/split (str value) #",")
       (map strip-yaml-scalar)
       (remove str/blank?)
       (str/join " ")))

(defn- gradle-modules
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (let [include-values (when-let [[_ values]
                                     (or (re-matches #"^\s*include\s+(.+?)\s*$" line)
                                         (re-matches #"^\s*include\s*\((.+?)\)\s*$" line))]
                            (->> (str/split values #",")
                                 (map #(str/replace % #"^[\s'\"]+|[\s'\"]+$" ""))
                                 (remove str/blank?)))
           project-dir (when-let [[_ module dir]
                                  (re-matches #"^\s*project\s*\(\s*['\"]([^'\"]+)['\"]\s*\)\.projectDir\s*=\s*file\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                              line)]
                         {:module module
                          :dir dir})]
       (cond
         (seq include-values)
         (map (fn [module-name]
                {:kind :gradle-module
                 :label module-name
                 :source-line source-line
                 :relation :defines})
              include-values)

         project-dir
         [{:kind :gradle-module-path
           :label (str (:module project-dir) "=" (:dir project-dir))
           :source-line source-line
           :relation :references}]

         :else [])))))

(defn- gradle-tasks
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*tasks\.(?:register|create)\s*\(\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ task-name] (re-matches #"^\s*tasks\.(?:register|create)\s*\(\s*['\"]([^'\"]+)['\"].*$"
                                       line)]
         [{:kind :task
           :label task-name
           :source-line source-line
           :relation :defines}])

       (re-matches #"^\s*task\s+([A-Za-z0-9_.-]+)\b.*$" line)
       (let [[_ task-name] (re-matches #"^\s*task\s+([A-Za-z0-9_.-]+)\b.*$" line)]
         [{:kind :task
           :label task-name
           :source-line source-line
           :relation :defines}])

       (re-matches #"^\s*commandLine\s+(.+?)\s*$" line)
       (let [[_ command] (re-matches #"^\s*commandLine\s+(.+?)\s*$" line)]
         [{:kind :task-command
           :label (gradle-command-label command)
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*dependsOn\s*\(?\s*['\"]([^'\"]+)['\"]\s*\)?.*$" line)
       (let [[_ task-name] (re-matches #"^\s*dependsOn\s*\(?\s*['\"]([^'\"]+)['\"]\s*\)?.*$"
                                       line)]
         [{:kind :task-dependency
           :label (str "dependsOn:" task-name)
           :source-line source-line
           :relation :requires}])

       :else []))))

(defn- gradle-facts
  [content]
  (vec (distinct (concat (gradle-dependencies content)
                         (gradle-plugins content)
                         (gradle-repositories content)
                         (gradle-modules content)
                         (gradle-tasks content)))))

(defn- dotnet-package-references
  [content]
  (->> (re-seq #"(?is)<PackageReference\b[^>]*(?:/>|>.*?</PackageReference>)"
               content)
       (keep (fn [element]
               (when-let [package (xml-attr-value element "Include")]
                 (package-fact {:ecosystem :nuget
                                :package-name package
                                :version-range (xml-attr-value element "Version")
                                :source-line 1}))))
       distinct
       vec))

(defn- dotnet-project-references
  [content]
  (->> (re-seq #"(?is)<ProjectReference\b[^>]*(?:/>|>.*?</ProjectReference>)"
               content)
       (keep (fn [element]
               (when-let [project-path (xml-attr-value element "Include")]
                 {:kind :project-reference
                  :label (str/replace project-path "\\" "/")
                  :source-line 1
                  :relation :references})))
       distinct
       vec))

(defn- dotnet-target-frameworks
  [content]
  (let [single (xml-tag-values content "TargetFramework")
        multiple (->> (xml-tag-values content "TargetFrameworks")
                      (mapcat #(str/split % #";"))
                      (map str/trim)
                      (remove str/blank?))]
    (->> (concat single multiple)
         distinct
         (mapv (fn [framework]
                 {:kind :runtime
                  :label framework
                  :source-line 1
                  :relation :uses})))))

(defn- ruby-gem-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ gem-name]
                          (re-matches
                           #"^\s*(?:gem|spec\.add_(?:runtime_)?dependency|spec\.add_development_dependency)\s+['\"]([^'\"]+)['\"].*"
                           line)]
                 (package-fact {:ecosystem :rubygems
                                :package-name gem-name
                                :source-line (inc idx)}))))
       distinct
       vec))

(defn- gemspec-name
  [content]
  (some-> (re-find #"(?m)^\s*[^#\n]*\.name\s*=\s*['\"]([^'\"]+)['\"]" content)
          second))

(defn- yaml-top-level-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name ":\\s*([^#\\n]+)\\s*$"))
                   content)
          second
          str/trim
          (str/replace #"^['\"]|['\"]$" "")))

(defn- yaml-section-keys
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) section out)

            (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
            (let [next-section (second (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*.*"
                                                   line))]
              (recur (rest remaining)
                     (when (contains? sections next-section) next-section)
                     out))

            section
            (let [entry (second (re-matches #"^\s{2}([A-Za-z_][A-Za-z0-9_.-]*)\s*:.*"
                                            line))]
              (recur (rest remaining)
                     section
                     (cond-> out
                       entry
                       (conj {:section section
                              :label entry
                              :source-line (inc idx)}))))

            :else
            (recur (rest remaining) section out)))
        out))))

(defn- pubspec-dependencies
  [content]
  (->> (yaml-section-keys content ["dependencies" "dev_dependencies"])
       (mapv (fn [{:keys [label source-line]}]
               (package-fact {:ecosystem :pub
                              :package-name label
                              :source-line source-line})))))

(defn- sbt-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*name\s*:=\s*\"([^\"]+)\"" content)
              second)
      path))

(defn- sbt-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ group artifact]
                          (re-find #"\"([^\"]+)\"\s*%%?\s*\"([^\"]+)\"\s*%" line)]
                 (package-fact {:ecosystem :maven
                                :package-name (str group ":" artifact)
                                :source-line (inc idx)}))))
       distinct
       vec))

(defn- mix-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*app:\s*:([A-Za-z_][A-Za-z0-9_]*)" content)
              second
              (str/replace "_" "-"))
      path))

(defn- mix-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ dep-name]
                          (re-find #"\{\s*:([A-Za-z_][A-Za-z0-9_]*)\s*,\s*\"" line)]
                 (package-fact {:ecosystem :hex
                                :package-name dep-name
                                :source-line (inc idx)}))))
       distinct
       vec))

(defn- rebar-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ dep-name]
                          (re-find #"\{\s*([a-z_][A-Za-z0-9_]*)\s*,\s*\{" line)]
                 (package-fact {:ecosystem :hex
                                :package-name dep-name
                                :source-line (inc idx)}))))
       distinct
       vec))

(defn- field-value
  [content field-name]
  (some-> (re-find (re-pattern (str "(?m)^" field-name ":\\s*(.+)$")) content)
          second
          str/trim))

(defn- comma-package-names
  [value]
  (->> (str/split (str value) #",")
       (map #(-> %
                 (str/replace #"\([^)]*\)" "")
                 str/trim))
       (remove str/blank?)
       vec))

(defn- r-description-package-name
  [content path]
  (or (field-value content "Package") path))

(defn- r-description-dependencies
  [content]
  (->> ["Depends" "Imports" "Suggests" "LinkingTo"]
       (mapcat (fn [field-name]
                 (let [dependency-scope (keyword (str/lower-case field-name))]
                   (->> (comma-package-names (field-value content field-name))
                        (remove #{"R"})
                        (map (fn [package-name]
                               (package-fact {:ecosystem :cran
                                              :package-name package-name
                                              :dependency-scope dependency-scope
                                              :source-line 1})))))))
       (remove nil?)
       distinct
       vec))

(defn- r-namespace-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ package-name]
                              (re-matches #"^\s*import(?:From)?\(([A-Za-z_][A-Za-z0-9_.]*).*\)\s*$" line)]
                     (package-fact {:ecosystem :cran
                                    :package-name package-name
                                    :source-line (inc idx)}))
                   (when-let [[_ export-name]
                              (re-matches #"^\s*export\(([A-Za-z_][A-Za-z0-9_.]*)\)\s*$" line)]
                     {:kind :export
                      :label export-name
                      :source-line (inc idx)
                      :relation :defines}))))
       distinct
       vec))

(defn- toml-name-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name "\\s*=\\s*\"([^\"]+)\"")) content)
          second))

(defn- project-toml-dependencies
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-deps? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (= "[deps]" trimmed)
          (recur (rest remaining) true out)

          (and in-deps? (re-matches #"^\[.+\]$" trimmed))
          (recur (rest remaining) false out)

          in-deps?
          (let [package-name (second (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=.*" trimmed))]
            (recur (rest remaining)
                   true
                   (cond-> out
                     package-name
                     (conj (package-fact {:ecosystem :julia
                                          :package-name package-name
                                          :source-line (inc idx)})))))

          :else
          (recur (rest remaining) in-deps? out)))
      (->> out distinct vec))))

(defn- cpanfile-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name]
                          (re-matches #"^\s*requires\s+['\"]([^'\"]+)['\"].*" line)]
                 (package-fact {:ecosystem :cpan
                                :package-name package-name
                                :source-line (inc idx)}))))
       distinct
       vec))

(defn- cabal-package-name
  [content path]
  (or (some-> (re-find #"(?mi)^\s*name:\s*([A-Za-z0-9_.-]+)\s*$" content)
              second)
      path))

(defn- cabal-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (when-let [[_ deps]
                            (re-matches #"(?i)^\s*build-depends:\s*(.+)$" line)]
                   (map (fn [package-name]
                          (package-fact {:ecosystem :hackage
                                         :package-name package-name
                                         :source-line (inc idx)}))
                        (comma-package-names deps)))))
       (remove nil?)
       distinct
       vec))

(defn- stack-yaml-dependencies
  [content]
  (->> (yaml-section-keys content ["extra-deps" "packages"])
       (mapv (fn [{:keys [label source-line]}]
               {:kind :project-reference
                :label label
                :source-line source-line
                :relation :references}))))

(defn- solution-projects
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ name project-path]
                          (re-matches
                           #"^Project\(\"[^\"]+\"\)\s*=\s*\"([^\"]+)\",\s*\"([^\"]+)\",\s*\"[^\"]+\".*"
                           line)]
                 {:kind :dotnet-project
                  :label (str name " " (str/replace project-path "\\" "/"))
                  :source-line (inc idx)
                  :relation :defines})))
       vec))

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

(defn- package-json-project-label
  [content path]
  (or (:name (read-json-map content))
      path))

(defn- json-key-label
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (string? k) k
    :else (str k)))

(defn- dependency-scope
  [k]
  (case k
    :dependencies "runtime"
    :devDependencies "development"
    :peerDependencies "peer"
    :optionalDependencies "optional"
    nil))

(defn- dependency-map-facts
  [m keys]
  (->> keys
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (map (fn [[dep-name version]]
                            (package-fact {:ecosystem :npm
                                           :package-name (json-key-label dep-name)
                                           :version-range (when (string? version) version)
                                           :dependency-scope (dependency-scope k)
                                           :source-line 1}))
                          deps)))))
       (remove nil?)
       distinct
       vec))

(defn- package-script-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           keys
           (map (fn [script-name]
                  {:kind :package-script
                   :label (json-key-label script-name)
                   :source-line 1
                   :relation :defines}))
           distinct
           vec))))

(defn- package-script-command-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           (keep (fn [[script-name command]]
                   (when (string? command)
                     {:kind :package-script-command
                      :label (str (json-key-label script-name) "=" command)
                      :source-line 1
                      :relation :defines})))
           distinct
           vec))))

(defn- package-json-metadata-facts
  [m]
  (vec
   (concat
    (when (string? (:version m))
      [{:kind :package-version
        :label (:version m)
        :source-line 1
        :relation :defines}])
    (when (string? (:type m))
      [{:kind :package-type
        :label (:type m)
        :source-line 1
        :relation :defines}])
    (when (contains? m :private)
      [{:kind :package-private
        :label (str "private=" (:private m))
        :source-line 1
        :relation :defines}])
    (let [bin (:bin m)]
      (cond
        (string? bin)
        [{:kind :package-bin
          :label bin
          :source-line 1
          :relation :defines}]

        (map? bin)
        (map (fn [[name target]]
               {:kind :package-bin
                :label (str (json-key-label name) "=" target)
                :source-line 1
                :relation :defines})
             bin)

        :else []))
    (let [exports (:exports m)]
      (cond
        (string? exports)
        [{:kind :package-export
          :label exports
          :source-line 1
          :relation :defines}]

        (map? exports)
        (map (fn [[name target]]
               {:kind :package-export
                :label (str (json-key-label name)
                            "="
                            (if (string? target) target (pr-str target)))
                :source-line 1
                :relation :defines})
             exports)

        :else []))
    (when (map? (:engines m))
      (map (fn [[name value]]
             {:kind :package-engine
              :label (str (json-key-label name) "=" value)
              :source-line 1
              :relation :defines})
           (:engines m))))))

(defn- workspace-patterns
  [value]
  (cond
    (vector? value) (filterv string? value)
    (map? value) (filterv string? (:packages value))
    :else []))

(defn- package-workspace-facts
  [m]
  (->> (workspace-patterns (:workspaces m))
       (map (fn [pattern]
              {:kind :workspace-pattern
               :label pattern
               :source-line 1
               :relation :references}))
       distinct
       vec))

(defn- workspace-protocol-dependency-facts
  [m]
  (->> [:dependencies :devDependencies :peerDependencies :optionalDependencies]
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (keep (fn [[dep-name version]]
                             (when (and (string? version)
                                        (str/starts-with? version "workspace:"))
                               {:kind :workspace-dependency
                                :label (str (json-key-label dep-name) "=" version)
                                :source-line 1
                                :relation :references}))
                           deps)))))
       (remove nil?)
       distinct
       vec))

(defn- package-manager-fact
  [m]
  (when-let [package-manager (or (:packageManager m)
                                 (:npmClient m)
                                 (:pnpmVersion m)
                                 (:yarnVersion m))]
    [{:kind :package-manager
      :label package-manager
      :source-line 1
      :relation :uses}]))

(defn- package-json-facts
  [content]
  (if-let [m (read-json-map content)]
    (vec (concat (dependency-map-facts m
                                       [:dependencies
                                        :devDependencies
                                        :peerDependencies
                                        :optionalDependencies])
                 (package-script-facts m)
                 (package-script-command-facts m)
                 (package-json-metadata-facts m)
                 (package-workspace-facts m)
                 (workspace-protocol-dependency-facts m)
                 (package-manager-fact m)))
    []))

(defn- deno-json-facts
  [content]
  (if-let [m (read-json-map content)]
    (let [imports (:imports m)
          tasks (:tasks m)]
      (vec (concat
            (when (map? imports)
              (map (fn [[alias target]]
                     (package-fact {:ecosystem :deno
                                    :package-name (json-key-label alias)
                                    :version-range (str target)
                                    :source-line 1}))
                   imports))
            (when (map? tasks)
              (map (fn [[task _command]]
                     {:kind :package-script
                      :label (json-key-label task)
                      :source-line 1
                      :relation :defines})
                   tasks)))))
    []))

(defn- pnpm-workspace-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         catalog nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packages:\s*" trimmed)
          (recur (rest remaining) :packages nil out)

          (re-matches #"catalog:\s*" trimmed)
          (recur (rest remaining) :catalog nil out)

          (re-matches #"catalogs:\s*" trimmed)
          (recur (rest remaining) :catalogs nil out)

          (re-matches #"onlyBuiltDependencies:\s*" trimmed)
          (recur (rest remaining) :built-dependencies nil out)

          (and (= :packages section) (str/starts-with? trimmed "-"))
          (let [pattern (-> trimmed
                            (str/replace #"^-\s*" "")
                            (str/replace #"^['\"]|['\"]$" "")
                            str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq pattern)
                     (conj {:kind :workspace-pattern
                            :label pattern
                            :source-line (inc idx)
                            :relation :references}))))

          (and (= :built-dependencies section) (str/starts-with? trimmed "-"))
          (let [dependency (-> trimmed
                               (str/replace #"^-\s*" "")
                               (str/replace #"^['\"]|['\"]$" "")
                               str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq dependency)
                     (conj {:kind :pnpm-built-dependency
                            :label dependency
                            :source-line (inc idx)
                            :relation :uses}))))

          (and (= :catalog section)
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ catalog-name] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   catalog-name
                   (conj out {:kind :pnpm-catalog
                              :label catalog-name
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               catalog
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str catalog ":" package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and section (not (str/blank? trimmed)))
          (recur (rest remaining) nil nil out)

          :else
          (recur (rest remaining) section catalog out)))
      out)))

(def yarnrc-setting-keys
  #{"nodeLinker" "yarnPath" "npmRegistryServer" "checksumBehavior"
    "enableGlobalCache" "enableImmutableInstalls" "compressionLevel"})

(defn- yarnrc-setting-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (when-let [[_ key value]
                     (re-matches #"^([A-Za-z][A-Za-z0-9_-]*):\s*(.+?)\s*$"
                                 line)]
            (let [value (str/replace value #"^['\"]|['\"]$" "")
                  source-line (inc idx)]
              (when (contains? yarnrc-setting-keys key)
                (concat
                 [{:kind :yarn-setting
                   :label (str key "=" value)
                   :source-line source-line
                   :relation :defines}]
                 (case key
                   "nodeLinker" [{:kind :yarn-node-linker
                                  :label value
                                  :source-line source-line
                                  :relation :defines}]
                   "yarnPath" [{:kind :yarn-path
                                :label value
                                :source-line source-line
                                :relation :references}]
                   "npmRegistryServer" [{:kind :yarn-registry
                                         :label value
                                         :source-line source-line
                                         :relation :uses}]
                   [])))))))
       (remove nil?)
       distinct
       vec))

(defn- yarnrc-plugin-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-plugins? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"plugins:\s*$" trimmed)
          (recur (rest remaining) true out)

          (and in-plugins?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false out)

          (and in-plugins?
               (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          (and in-plugins?
               (re-matches #"^(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-plugins? out)))
      (vec (distinct out)))))

(defn- yarnrc-npm-scope-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-scopes? false
         current-scope nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"npmScopes:\s*$" trimmed)
          (recur (rest remaining) true nil out)

          (and in-scopes?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil out)

          (and in-scopes?
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ scope] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   true
                   scope
                   (conj out {:kind :yarn-npm-scope
                              :label scope
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmRegistryServer:\s*(.+?)\s*$" trimmed))
          (let [[_ registry] (re-matches #"^npmRegistryServer:\s*(.+?)\s*$"
                                         trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-scope-registry
                              :label (str current-scope "="
                                          (str/replace registry #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :uses})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmAuth(?:Token|Ident):\s*(.+?)\s*$" trimmed))
          (let [[_ auth-key _] (re-matches #"^(npmAuth(?:Token|Ident)):\s*(.+?)\s*$"
                                           trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-auth-key
                              :label (str current-scope ":" auth-key)
                              :source-line (inc idx)
                              :relation :defines})))

          :else
          (recur (rest remaining) in-scopes? current-scope out)))
      (vec (distinct out)))))

(defn- yarnrc-package-extension-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-extensions? false
         current-extension nil
         in-deps? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packageExtensions:\s*$" trimmed)
          (recur (rest remaining) true nil false out)

          (and in-extensions?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil false out)

          (and in-extensions?
               (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$" trimmed))
          (let [[_ extension] (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   extension
                   false
                   (conj out {:kind :yarn-package-extension
                              :label extension
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-extensions?
               current-extension
               (contains? #{"dependencies:" "peerDependencies:"} trimmed))
          (recur (rest remaining) true current-extension true out)

          (and in-extensions?
               current-extension
               in-deps?
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   true
                   current-extension
                   true
                   (conj out {:kind :yarn-extension-dependency
                              :label (str current-extension
                                          ":"
                                          package-name
                                          "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-extensions? current-extension in-deps? out)))
      (vec (distinct out)))))

(defn- yarnrc-facts
  [content]
  (vec (concat (yarnrc-setting-facts content)
               (yarnrc-plugin-facts content)
               (yarnrc-npm-scope-facts content)
               (yarnrc-package-extension-facts content))))

(defn- json-key-facts
  [m key kind relation]
  (let [value (get m key)]
    (cond
      (map? value)
      (->> value
           keys
           (map (fn [entry]
                  {:kind kind
                   :label (json-key-label entry)
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      (vector? value)
      (->> value
           (keep (fn [entry]
                   (cond
                     (string? entry) entry
                     (map? entry) (or (:packageName entry)
                                      (:projectFolder entry)
                                      (:name entry))
                     :else nil)))
           (map (fn [entry]
                  {:kind kind
                   :label entry
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      :else [])))

(defn- workspace-json-facts
  [content filename]
  (if-let [m (read-json-map content)]
    (case filename
      "turbo.json"
      (json-key-facts m :tasks :workspace-task :defines)

      "nx.json"
      (vec (concat (json-key-facts m :targetDefaults :workspace-task :defines)
                   (json-key-facts m :projects :workspace-project :references)))

      "workspace.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (json-key-facts m :targets :workspace-task :defines)))

      "lerna.json"
      (vec (concat (->> (workspace-patterns (:packages m))
                        (map (fn [pattern]
                               {:kind :workspace-pattern
                                :label pattern
                                :source-line 1
                                :relation :references})))
                   (package-manager-fact m)))

      "rush.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (package-manager-fact m)))

      [])
    []))

(defn- toml-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*=\\s*\"([^\"]+)\""))
                   content)
          second))

(defn- toml-section-lines
  [content section-name]
  (loop [remaining (str/split-lines content)
         in-section? false
         out []]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining) (= trimmed (str "[" section-name "]")) out)

          in-section?
          (recur (rest remaining) in-section? (conj out line))

          :else
          (recur (rest remaining) in-section? out)))
      out)))

(defn- toml-array-strings
  [value]
  (->> (re-seq #"\"([^\"]+)\"" (str value))
       (map second)
       (remove str/blank?)
       distinct
       vec))

(defn- toml-string-or-array-value
  [line]
  (or (some-> (re-find #"=\s*\"([^\"]+)\"" line) second vector)
      (some-> (re-find #"=\s*(\[[^\]]*\])" line) second toml-array-strings)))

(defn- cargo-package-name
  [content path]
  (or (some-> (str/join "\n" (toml-section-lines content "package"))
              (toml-string-value "name"))
      path))

(defn- cargo-dependencies
  [content]
  (let [dependency-section-facts
        (fn [section]
          (->> (toml-section-lines content section)
               (map-indexed vector)
               (keep (fn [[idx line]]
                       (when-let [[_ package-name quoted-version map-version]
                                  (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*(?:\"([^\"]+)\"|\{.*?version\s*=\s*\"([^\"]+)\".*\}).*"
                                              line)]
                         (package-fact {:ecosystem :cargo
                                        :package-name package-name
                                        :version-range (or quoted-version map-version)
                                        :dependency-scope section
                                        :source-line (inc idx)}))))))
        deps (->> ["dependencies" "dev-dependencies" "build-dependencies"
                   "workspace.dependencies"]
                  (mapcat dependency-section-facts)
                  distinct)
        members (->> (toml-section-lines content "workspace")
                     (keep #(second (re-matches #"^\s*members\s*=\s*(\[.*\])\s*$" %)))
                     (mapcat toml-array-strings)
                     (map (fn [member]
                            {:kind :workspace-pattern
                             :label member
                             :source-line 1
                             :relation :references})))
        features (->> (toml-section-lines content "features")
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ feature]
                                         (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=.*" line)]
                                {:kind :package-feature
                                 :label feature
                                 :source-line (inc idx)
                                 :relation :defines}))))]
    (->> (concat deps members features)
         distinct
         vec)))

(defn- go-module-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*module\s+(\S+)\s*$" content) second)
      path))

(defn- go-mod-requires
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining (map-indexed vector lines)
           in-require-block? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (-> line
                          (str/replace #"//.*$" "")
                          str/trim)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) in-require-block? out)

            (re-matches #"^require\s*\(\s*$" trimmed)
            (recur (rest remaining) true out)

            (and in-require-block? (= ")" trimmed))
            (recur (rest remaining) false out)

            :else
            (let [[package-name version] (or (some->> trimmed
                                                      (re-matches #"^require\s+(\S+)\s+(\S+).*$")
                                                      rest)
                                             (when in-require-block?
                                               (some->> trimmed
                                                        (re-matches #"^(\S+)\s+(\S+).*$")
                                                        rest)))]
              (recur (rest remaining)
                     in-require-block?
                     (cond-> out
                       package-name
                       (conj (package-fact {:ecosystem :go
                                            :package-name package-name
                                            :version-range version
                                            :source-line (inc idx)})))))))
        (vec (distinct out))))))

(defn- go-mod-extra-facts
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [trimmed (-> line
                              (str/replace #"//.*$" "")
                              str/trim)
                  source-line (inc idx)]
              (concat
               (when-let [[_ version] (re-matches #"^go\s+(\S+)\s*$" trimmed)]
                 [{:kind :go-version
                   :label version
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ version] (re-matches #"^toolchain\s+(\S+)\s*$" trimmed)]
                 [{:kind :go-toolchain
                   :label version
                   :source-line source-line
                   :relation :uses}])
               (when-let [[_ source target] (re-matches #"^replace\s+(\S+)\s+=>\s+(\S+).*$" trimmed)]
                 [{:kind :project-reference
                   :label (str source "=>" target)
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ package version] (re-matches #"^exclude\s+(\S+)\s+(\S+).*$" trimmed)]
                 [{:kind :go-exclude
                   :label (str package "@" version)
                   :source-line source-line
                   :relation :defines}])))))
         distinct
         vec)))

(defn- go-work-uses
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining (map-indexed vector lines)
           in-use-block? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (-> line
                          (str/replace #"//.*$" "")
                          str/trim)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) in-use-block? out)

            (re-matches #"^use\s*\(\s*$" trimmed)
            (recur (rest remaining) true out)

            (and in-use-block? (= ")" trimmed))
            (recur (rest remaining) false out)

            :else
            (let [target (or (second (re-matches #"^use\s+(\S+).*$" trimmed))
                             (when in-use-block?
                               (second (re-matches #"^(\S+).*$" trimmed))))]
              (recur (rest remaining)
                     in-use-block?
                     (cond-> out
                       target
                       (conj {:kind :project-reference
                              :label target
                              :source-line (inc idx)
                              :relation :references}))))))
        (vec (distinct out))))))

(defn- composer-project-name
  [content path]
  (or (:name (read-json-map content))
      path))

(defn- composer-dependency-scope
  [k]
  (case k
    :require "runtime"
    :require-dev "development"
    nil))

(defn- composer-json-facts
  [content]
  (if-let [m (read-json-map content)]
    (->> [:require :require-dev]
         (mapcat
          (fn [k]
            (when-let [deps (get m k)]
              (when (map? deps)
                (map (fn [[package-name version]]
                       (package-fact {:ecosystem :composer
                                      :package-name (json-key-label package-name)
                                      :version-range (when (string? version)
                                                       version)
                                      :dependency-scope (composer-dependency-scope k)
                                      :source-line 1}))
                     deps)))))
         (remove nil?)
         distinct
         vec)
    []))

(defn- pyproject-name
  [content path]
  (or (some-> (str/join "\n" (toml-section-lines content "project"))
              (toml-string-value "name"))
      (some-> (str/join "\n" (toml-section-lines content "tool.poetry"))
              (toml-string-value "name"))
      path))

(defn- python-dependency-name
  [value]
  (some-> (re-find #"^\s*([A-Za-z0-9_.-]+)" value)
          second))

(defn- pyproject-import-name-map
  [content]
  (->> (toml-section-lines content "tool.agraph.import-names")
       (keep (fn [line]
               (when-let [[_ package-name]
                          (re-matches #"^\s*\"?([A-Za-z0-9_.-]+)\"?\s*=.*" line)]
                 (when-let [import-names (seq (toml-string-or-array-value line))]
                   [package-name (vec import-names)]))))
       (into {})))

(defn- pyproject-dependencies
  [content]
  (let [project-lines (toml-section-lines content "project")
        import-name-map (pyproject-import-name-map content)
        inline-deps (->> project-lines
                         (keep #(second (re-matches #"^\s*dependencies\s*=\s*\[(.*)\]\s*$" %)))
                         (mapcat #(re-seq #"\"([^\"]+)\"" %))
                         (map second))
        poetry-deps (->> (toml-section-lines content "tool.poetry.dependencies")
                         (keep #(when-let [[_ dep-name version]
                                           (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\".*" %)]
                                  (when-not (= "python" dep-name)
                                    [dep-name version]))))
        optional-deps (->> (toml-section-lines content "project.optional-dependencies")
                           (mapcat (fn [line]
                                     (when-let [[_ group deps]
                                                (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\[(.*)\]\s*$" line)]
                                       (map (fn [[_ dep]]
                                              [group dep])
                                            (re-seq #"\"([^\"]+)\"" deps))))))]
    (vec
     (concat
      (keep (fn [dep]
              (when-let [dep-name (python-dependency-name dep)]
                (package-fact {:ecosystem :pypi
                               :package-name dep-name
                               :version-range dep
                               :import-names (get import-name-map dep-name)
                               :source-line 1})))
            inline-deps)
      (map (fn [[dep-name version]]
             (package-fact {:ecosystem :pypi
                            :package-name dep-name
                            :version-range version
                            :import-names (get import-name-map dep-name)
                            :source-line 1}))
           poetry-deps)
      (keep (fn [[group dep]]
              (when-let [dep-name (python-dependency-name dep)]
                (package-fact {:ecosystem :pypi
                               :package-name dep-name
                               :version-range dep
                               :dependency-scope group
                               :import-names (get import-name-map dep-name)
                               :source-line 1})))
            optional-deps)))))

(defn- setup-cfg-name
  [content path]
  (or (some->> (str/join "\n" (toml-section-lines content "metadata"))
               (re-find #"(?m)^\s*name\s*=\s*([A-Za-z0-9_.-]+)\s*$")
               second)
      path))

(defn- setup-cfg-dependencies
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           in-install? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            (re-matches #"^\[options\]\s*$" trimmed)
            (recur (rest remaining) false out)

            (re-matches #"^\[.*\]\s*$" trimmed)
            (recur (rest remaining) false out)

            (re-matches #"^install_requires\s*=.*" trimmed)
            (recur (rest remaining) true out)

            (and in-install? (seq trimmed))
            (recur (rest remaining)
                   in-install?
                   (cond-> out
                     (python-dependency-name trimmed)
                     (conj (package-fact
                            {:ecosystem :pypi
                             :package-name (python-dependency-name trimmed)
                             :version-range trimmed
                             :source-line (inc idx)}))))

            :else
            (recur (rest remaining) in-install? out)))
        (vec (distinct out))))))

(defn- setup-py-name
  [content path]
  (or (some-> (re-find #"(?s)\bname\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      path))

(defn- setup-py-dependencies
  [content]
  (->> (re-seq #"(?s)(?:install_requires|setup_requires|tests_require)\s*=\s*\[(.*?)\]"
               content)
       (mapcat (fn [[_ deps]]
                 (re-seq #"['\"]([^'\"]+)['\"]" deps)))
       (map second)
       (keep (fn [dep]
               (when-let [dep-name (python-dependency-name dep)]
                 (package-fact {:ecosystem :pypi
                                :package-name dep-name
                                :version-range dep
                                :source-line 1}))))
       distinct
       vec))

(defn- pipfile-dependencies
  [content]
  (->> ["packages" "dev-packages"]
       (mapcat
        (fn [section]
          (->> (toml-section-lines content section)
               (map-indexed vector)
               (keep (fn [[idx line]]
                       (when-let [[_ dep-name version]
                                  (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" line)]
                         (package-fact {:ecosystem :pypi
                                        :package-name dep-name
                                        :version-range (str/replace (str/trim version)
                                                                    #"^['\"]|['\"]$" "")
                                        :dependency-scope section
                                        :source-line (inc idx)})))))))
       distinct
       vec))

(defn- deps-edn-project-name
  [path]
  path)

(defn- deps-edn-dependencies
  [content]
  (try
    (let [parsed (edn/read-string content)
          dep-entries (concat (:deps parsed)
                              (mapcat :extra-deps (vals (:aliases parsed))))]
      (->> dep-entries
           (keep (fn [[dep-name spec]]
                   (cond
                     (:mvn/version spec)
                     (package-fact {:ecosystem :maven
                                    :package-name (str dep-name)
                                    :version-range (:mvn/version spec)
                                    :source-line 1})

                     (:git/url spec)
                     (package-fact {:ecosystem :git
                                    :package-name (:git/url spec)
                                    :version-range (or (:git/sha spec)
                                                       (:git/tag spec))
                                    :source-line 1})

                     :else nil)))
           distinct
           vec))
    (catch Exception _
      [])))

(defn- android-manifest-package
  [content]
  (some-> (re-find #"(?is)<manifest\b[^>]*>" content)
          (xml-attr-value "package")))

(defn- android-permissions
  [content]
  (->> (re-seq #"(?is)<uses-permission\b[^>]*(?:/>|>.*?</uses-permission>)"
               content)
       (keep (fn [element]
               (when-let [permission (xml-attr-value element "android:name")]
                 {:kind :android-permission
                  :label permission
                  :source-line 1
                  :relation :uses})))
       distinct
       vec))

(defn- android-components
  [content]
  (->> (re-seq #"(?is)<(activity|service|receiver|provider)\b[^>]*(?:/>|>.*?</\1>)"
               content)
       (keep (fn [[element tag]]
               (when-let [component-name (xml-attr-value element "android:name")]
                 {:kind :android-component
                  :label (str tag ":" component-name)
                  :source-line 1
                  :relation :defines})))
       distinct
       vec))

(defn- plist-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?is)<key>\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*</key>\\s*<string>(.*?)</string>"))
                   content)
          second
          str/trim))

(defn- plist-facts
  [content]
  (->> [{:kind :mobile-bundle
         :label (plist-string-value content "CFBundleIdentifier")
         :source-line 1
         :relation :defines}
        {:kind :mobile-display-name
         :label (or (plist-string-value content "CFBundleDisplayName")
                    (plist-string-value content "CFBundleName"))
         :source-line 1
         :relation :defines}]
       (filterv (comp seq :label))))

(defn- plist-key-facts
  [content kind]
  (->> (re-seq #"(?is)<key>\s*([^<]+?)\s*</key>\s*(?:<string>\s*([^<]+?)\s*</string>|<(true|false)\s*/>|<array>\s*(.*?)\s*</array>)"
               content)
       (map-indexed
        (fn [idx [_ key string-value bool-value array-value]]
          (let [array-items (->> (or array-value "")
                                 (re-seq #"(?is)<string>\s*([^<]+?)\s*</string>")
                                 (map second)
                                 (map str/trim)
                                 (remove str/blank?))
                value (or (some-> string-value str/trim)
                          bool-value
                          (when (seq array-items)
                            (str/join "," array-items))
                          "present")]
            {:kind kind
             :label (str (str/trim key) "=" value)
             :source-line (inc idx)
             :relation :defines})))
       distinct
       vec))

(defn- podfile-facts
  [content]
  (let [lines (str/split-lines content)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-matches #"^\s*target\s+['\"]([^'\"]+)['\"]\s+do\s*$"
                                                    line)]
                               {:kind :ios-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)
        pods (->> lines
                  (map-indexed vector)
                  (keep (fn [[idx line]]
                          (when-let [[_ pod-name]
                                     (re-matches #"^\s*pod\s+['\"]([^'\"]+)['\"].*"
                                                 line)]
                            (package-fact {:ecosystem :cocoapods
                                           :package-name pod-name
                                           :source-line (inc idx)}))))
                  vec)]
    (vec (concat targets pods))))

(defn- swift-package-name
  [content]
  (some-> (re-find #"(?s)Package\s*\(\s*name:\s*\"([^\"]+)\"" content)
          second))

(defn- swift-package-facts
  [content]
  (let [lines (str/split-lines content)
        package-deps (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-find #"\.package\s*\(\s*url:\s*\"([^\"]+)\""
                                                      line)]
                                    (package-fact {:ecosystem :swiftpm
                                                   :package-name url
                                                   :source-line (inc idx)}))))
                          vec)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-find #"\.(?:target|testTarget|executableTarget)\s*\(\s*name:\s*\"([^\"]+)\""
                                                 line)]
                               {:kind :swift-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)]
    (vec (concat package-deps targets))))

(defn- xcode-project-facts
  [content]
  (let [lines (str/split-lines content)
        products (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ product-name]
                                         (re-matches #"^\s*productName\s*=\s*([^;]+);\s*$"
                                                     line)]
                                {:kind :xcode-product
                                 :label (str/replace product-name #"^\"|\"$" "")
                                 :source-line (inc idx)
                                 :relation :defines})))
                      distinct
                      vec)
        package-urls (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-matches #"^\s*repositoryURL\s*=\s*\"([^\"]+)\";\s*$"
                                                         line)]
                                    (package-fact {:ecosystem :swiftpm
                                                   :package-name url
                                                   :source-line (inc idx)}))))
                          distinct
                          vec)]
    (vec (concat products package-urls))))

(defn- json-string-at
  [m path]
  (let [value (get-in m path)]
    (when (string? value)
      value)))

(defn- expo-json-facts
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)
          android-package (json-string-at expo [:android :package])
          ios-bundle (json-string-at expo [:ios :bundleIdentifier])
          plugins (:plugins expo)
          plugin-labels (->> plugins
                             (keep (fn [plugin]
                                     (cond
                                       (string? plugin) plugin
                                       (vector? plugin) (first plugin)
                                       :else nil)))
                             (filter string?)
                             distinct)]
      (vec (concat
            (when android-package
              [{:kind :android-package
                :label android-package
                :source-line 1
                :relation :defines}])
            (when ios-bundle
              [{:kind :mobile-bundle
                :label ios-bundle
                :source-line 1
                :relation :defines}])
            (map (fn [plugin]
                   {:kind :expo-plugin
                    :label plugin
                    :source-line 1
                    :relation :uses})
                 plugin-labels))))
    (catch Exception _
      [])))

(defn- expo-project-label
  [content path]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)]
      (or (json-string-at expo [:name])
          (json-string-at expo [:slug])
          path))
    (catch Exception _
      path)))

(defn- js-config-string-value
  [content key-name]
  (or (some-> (re-find (re-pattern (str "(?m)\\b" key-name "\\s*:\\s*['\"]([^'\"]+)['\"]"))
                       content)
              second)
      (some-> (re-find (re-pattern (str "(?m)\"" key-name "\"\\s*:\\s*\"([^\"]+)\""))
                       content)
              second)))

(defn- json-or-js-string-at
  [content path key-name]
  (or (some-> (read-json-map content)
              (json-string-at path))
      (js-config-string-value content key-name)))

(defn- object-key-facts
  [m kind relation source-line]
  (when (map? m)
    (mapv (fn [[k _]]
            {:kind kind
             :label (json-key-label k)
             :source-line source-line
             :relation relation})
          m)))

(defn- capacitor-plugin-facts
  [content]
  (if-let [plugins (:plugins (read-json-map content))]
    (object-key-facts plugins :capacitor-plugin :uses 1)
    (loop [remaining (map-indexed vector (str/split-lines content))
           in-plugins? false
           depth 0
           out []]
      (if-let [[idx line] (first remaining)]
        (let [starts? (and (not in-plugins?)
                           (re-find #"\bplugins\s*:\s*\{" line))
              depth-before (if starts? 1 depth)
              plugin (when (and (or in-plugins? starts?)
                                (= 1 depth-before))
                       (some-> (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*\{?.*$" line)
                               second))
              opens (count (re-seq #"\{" line))
              closes (count (re-seq #"\}" line))
              depth* (cond
                       starts? (+ opens (- closes))
                       in-plugins? (+ depth opens (- closes))
                       :else depth)
              in-plugins* (or (and starts? (pos? depth*))
                              (and in-plugins? (pos? depth*)))]
          (recur (rest remaining)
                 in-plugins*
                 depth*
                 (cond-> out
                   (and plugin (not= "plugins" plugin))
                   (conj {:kind :capacitor-plugin
                          :label plugin
                          :source-line (inc idx)
                          :relation :uses}))))
        (->> out distinct vec)))))

(defn- capacitor-config-facts
  [content]
  (let [app-id (json-or-js-string-at content [:appId] "appId")
        app-name (json-or-js-string-at content [:appName] "appName")
        web-dir (json-or-js-string-at content [:webDir] "webDir")
        server-url (json-or-js-string-at content [:server :url] "url")]
    (vec (concat
          (when app-id
            [{:kind :mobile-bundle
              :label app-id
              :source-line 1
              :relation :defines}])
          (when app-name
            [{:kind :mobile-display-name
              :label app-name
              :source-line 1
              :relation :defines}])
          (when web-dir
            [{:kind :mobile-web-dir
              :label web-dir
              :source-line 1
              :relation :references}])
          (when server-url
            [{:kind :mobile-entry-url
              :label server-url
              :source-line 1
              :relation :references}])
          (capacitor-plugin-facts content)))))

(defn- capacitor-project-label
  [content path]
  (or (json-or-js-string-at content [:appName] "appName")
      (json-or-js-string-at content [:appId] "appId")
      path))

(defn- tauri-config-value
  [content paths key-name]
  (or (some (fn [path]
              (some-> (read-json-map content)
                      (json-string-at path)))
            paths)
      (js-config-string-value content key-name)))

(defn- tauri-plugin-facts
  [content]
  (let [parsed (read-json-map content)
        plugins (:plugins parsed)]
    (object-key-facts plugins :tauri-plugin :uses 1)))

(defn- tauri-window-facts
  [content]
  (let [parsed (read-json-map content)
        windows (or (get-in parsed [:app :windows])
                    (get-in parsed [:tauri :windows]))]
    (when (vector? windows)
      (->> windows
           (keep (fn [window]
                   (when (map? window)
                     (let [label (or (:label window) (:title window))]
                       (when label
                         {:kind :tauri-window
                          :label (if-let [title (:title window)]
                                   (str label ":" title)
                                   label)
                          :source-line 1
                          :relation :defines})))))
           vec))))

(defn- tauri-config-facts
  [content]
  (let [identifier (tauri-config-value content
                                       [[:identifier] [:tauri :bundle :identifier]]
                                       "identifier")
        product-name (tauri-config-value content
                                         [[:productName] [:package :productName]]
                                         "productName")
        frontend-dist (tauri-config-value content
                                          [[:build :frontendDist] [:build :distDir]]
                                          "frontendDist")
        dev-url (tauri-config-value content
                                    [[:build :devUrl] [:build :devPath]]
                                    "devUrl")
        before-dev-command (tauri-config-value content
                                               [[:build :beforeDevCommand]]
                                               "beforeDevCommand")]
    (vec (concat
          (when identifier
            [{:kind :mobile-bundle
              :label identifier
              :source-line 1
              :relation :defines}])
          (when product-name
            [{:kind :mobile-display-name
              :label product-name
              :source-line 1
              :relation :defines}])
          (when frontend-dist
            [{:kind :mobile-web-dir
              :label frontend-dist
              :source-line 1
              :relation :references}])
          (when dev-url
            [{:kind :mobile-entry-url
              :label dev-url
              :source-line 1
              :relation :references}])
          (when before-dev-command
            [{:kind :task-command
              :label before-dev-command
              :source-line 1
              :relation :uses}])
          (tauri-window-facts content)
          (tauri-plugin-facts content)))))

(defn- tauri-project-label
  [content path]
  (or (tauri-config-value content [[:productName] [:package :productName]] "productName")
      (tauri-config-value content [[:identifier] [:tauri :bundle :identifier]] "identifier")
      path))

(defn- manifest-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)
        extension (last (str/split filename #"\."))]
    (cond
      (= "pom.xml" filename)
      {:project-label (or (maven-coordinates content) path)
       :facts (maven-facts content)}

      (contains? #{"build.gradle" "build.gradle.kts" "settings.gradle"
                   "settings.gradle.kts" "gradle.properties"}
                 filename)
      {:project-label (gradle-project-name content path)
       :facts (gradle-facts content)}

      (or (contains? #{"csproj" "fsproj" "vbproj" "props" "targets"}
                     extension)
          (contains? #{"directory.build.props" "directory.build.targets"
                       "packages.config" "nuget.config"}
                     filename))
      {:project-label (or (first (xml-tag-values content "AssemblyName"))
                          (first (xml-tag-values content "PackageId"))
                          path)
       :facts (vec (concat (dotnet-package-references content)
                           (dotnet-project-references content)
                           (dotnet-target-frameworks content)))}

      (= "sln" extension)
      {:project-label path
       :facts (solution-projects content)}

      (= "package.json" filename)
      {:project-label (package-json-project-label content path)
       :facts (package-json-facts content)}

      (= "deno.json" filename)
      {:project-label (package-json-project-label content path)
       :facts (deno-json-facts content)}

      (= "cargo.toml" filename)
      {:project-label (cargo-package-name content path)
       :facts (cargo-dependencies content)}

      (= "go.mod" filename)
      {:project-label (go-module-name content path)
       :facts (vec (concat (go-mod-requires content)
                           (go-mod-extra-facts content)))}

      (= "go.work" filename)
      {:project-label path
       :facts (go-work-uses content)}

      (= "composer.json" filename)
      {:project-label (composer-project-name content path)
       :facts (composer-json-facts content)}

      (= "pyproject.toml" filename)
      {:project-label (pyproject-name content path)
       :facts (pyproject-dependencies content)}

      (= "setup.cfg" filename)
      {:project-label (setup-cfg-name content path)
       :facts (setup-cfg-dependencies content)}

      (= "setup.py" filename)
      {:project-label (setup-py-name content path)
       :facts (setup-py-dependencies content)}

      (= "pipfile" filename)
      {:project-label path
       :facts (pipfile-dependencies content)}

      (= "deps.edn" filename)
      {:project-label (deps-edn-project-name path)
       :facts (deps-edn-dependencies content)}

      (or (= "gemfile" filename)
          (= "gemspec" extension))
      {:project-label (or (gemspec-name content) path)
       :facts (ruby-gem-dependencies content)}

      (= "pubspec.yaml" filename)
      {:project-label (or (yaml-top-level-value content "name") path)
       :facts (pubspec-dependencies content)}

      (= "build.sbt" filename)
      {:project-label (sbt-project-name content path)
       :facts (sbt-dependencies content)}

      (= "mix.exs" filename)
      {:project-label (mix-project-name content path)
       :facts (mix-dependencies content)}

      (= "rebar.config" filename)
      {:project-label path
       :facts (rebar-dependencies content)}

      (= "description" filename)
      {:project-label (r-description-package-name content path)
       :facts (r-description-dependencies content)}

      (= "namespace" filename)
      {:project-label path
       :facts (r-namespace-facts content)}

      (= "project.toml" filename)
      {:project-label (or (toml-name-value content "name") path)
       :facts (project-toml-dependencies content)}

      (= "cpanfile" filename)
      {:project-label path
       :facts (cpanfile-dependencies content)}

      (= "cabal" extension)
      {:project-label (cabal-package-name content path)
       :facts (cabal-dependencies content)}

      (= "stack.yaml" filename)
      {:project-label path
       :facts (stack-yaml-dependencies content)}

      (contains? #{"pnpm-workspace.yaml" "pnpm-workspace.yml"} filename)
      {:project-label path
       :facts (pnpm-workspace-facts content)}

      (contains? #{".yarnrc.yml" ".yarnrc.yaml"} filename)
      {:project-label path
       :facts (yarnrc-facts content)}

      (contains? #{"turbo.json" "nx.json" "workspace.json" "lerna.json" "rush.json"} filename)
      {:project-label path
       :facts (workspace-json-facts content filename)}

      (= "androidmanifest.xml" filename)
      {:project-label (or (android-manifest-package content) path)
       :facts (vec (concat (when-let [package-name (android-manifest-package content)]
                             [{:kind :android-package
                               :label package-name
                               :source-line 1
                               :relation :defines}])
                           (android-permissions content)
                           (android-components content)))}

      (= "info.plist" filename)
      {:project-label (or (plist-string-value content "CFBundleIdentifier")
                          (plist-string-value content "CFBundleName")
                          path)
       :facts (plist-facts content)}

      (= "entitlements" extension)
      {:project-label path
       :facts (plist-key-facts content :apple-entitlement)}

      (= "podfile" filename)
      {:project-label path
       :facts (podfile-facts content)}

      (= "package.swift" filename)
      {:project-label (or (swift-package-name content) path)
       :facts (swift-package-facts content)}

      (= "pbxproj" extension)
      {:project-label path
       :facts (xcode-project-facts content)}

      (contains? #{"app.json" "app.config.json" "eas.json"} filename)
      {:project-label (expo-project-label content path)
       :facts (expo-json-facts content)}

      (contains? #{"capacitor.config.json" "capacitor.config.ts"
                   "capacitor.config.js" "capacitor.config.mjs"
                   "capacitor.config.cjs"}
                 filename)
      {:project-label (capacitor-project-label content path)
       :facts (capacitor-config-facts content)}

      (contains? #{"tauri.conf.json" "tauri.conf.json5"} filename)
      {:project-label (tauri-project-label content path)
       :facts (tauri-config-facts content)}

      :else
      {:project-label path
       :facts []})))

(defn extract-manifest
  "Extract bounded declared dependency/reference facts from project manifests."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [{:keys [project-label facts]} (manifest-facts file)
        manifest-node (generic-node run-id id-scope file-id path :manifest project-label 1)
        fact-nodes (mapv #(fact-node run-id id-scope file-id path %) facts)
        fact-edges (mapv #(fact-edge-row run-id
                                         file-id
                                         path
                                         (:xt/id manifest-node)
                                         id-scope
                                         %)
                         facts)
        chunk-result (extract-text-source run-id file :manifest-file)]
    {:nodes (into [manifest-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- lockfile-name
  [path]
  (str/lower-case (last (str/split path #"/"))))

(defn- package-lock-path-name
  [package-path]
  (let [parts (str/split (str package-path) #"/")
        marker-indexes (keep-indexed #(when (= "node_modules" %2) %1) parts)
        idx (last marker-indexes)]
    (when idx
      (let [tail (subvec (vec parts) (inc idx))]
        (cond
          (and (seq tail) (str/starts-with? (first tail) "@") (second tail))
          (str (first tail) "/" (second tail))

          (seq tail)
          (first tail)

          :else nil)))))

(defn- package-lock-version-facts
  [content]
  (if-let [m (read-json-map content)]
    (->> (:packages m)
         (keep (fn [[package-path package]]
                 (when (map? package)
                   (let [package-name (or (:name package)
                                          (package-lock-path-name
                                           (json-key-label package-path)))
                         version (:version package)]
                     (package-version-fact {:ecosystem :npm
                                            :package-name package-name
                                            :resolved-version version
                                            :source-line 1})))))
         distinct
         vec)
    []))

(defn- cargo-lock-version-facts
  [content]
  (->> (str/split content #"(?m)^\[\[package\]\]\s*$")
       (keep (fn [block]
               (let [package-name (toml-string-value block "name")
                     version (toml-string-value block "version")]
                 (package-version-fact {:ecosystem :cargo
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))

(defn- go-sum-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-matches #"^(\S+)\s+(\S+)(?:/go\.mod)?\s+h1:.*$" line)]
                 (package-version-fact {:ecosystem :go
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))

(defn- package-key-name-version
  [value]
  (let [clean (-> (str value)
                  str/trim
                  (str/replace #"^\s*['\"]|['\"]\s*$" "")
                  (str/replace #"^/" "")
                  (str/replace #"\(.*\)$" ""))
        at-idx (.lastIndexOf clean "@")]
    (when (pos? at-idx)
      (let [package-name (subs clean 0 at-idx)
            version (subs clean (inc at-idx))]
        (when (and (seq package-name) (seq version))
          {:package-name package-name
           :version version})))))

(defn- pnpm-lock-version-facts
  [content]
  (->> (re-seq #"(?m)^\s{2}['\"]?/?([^'\"\s][^'\"]*?)['\"]?:\s*$" content)
       (keep (fn [[_ package-key]]
               (when-let [{:keys [package-name version]} (package-key-name-version package-key)]
                 (package-version-fact {:ecosystem :npm
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))

(defn- yarn-selector-package-name
  [selector]
  (let [selector (-> (str selector)
                     (str/trim)
                     (str/replace #"^['\"]|['\"]$" ""))]
    (cond
      (str/starts-with? selector "@")
      (let [idx (.indexOf selector "@" 1)]
        (when (pos? idx)
          (subs selector 0 idx)))

      :else
      (let [idx (.indexOf selector "@")]
        (when (pos? idx)
          (subs selector 0 idx))))))

(defn- yarn-header-package-names
  [line]
  (when-let [[_ header] (re-matches #"^(.+):\s*$" line)]
    (->> (str/split header #",")
         (keep yarn-selector-package-name)
         distinct
         vec)))

(defn- yarn-lock-version-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         package-names []
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (str/blank? line)
        (recur (rest remaining) package-names out)

        (and (not (str/starts-with? line " "))
             (seq (yarn-header-package-names line)))
        (recur (rest remaining) (yarn-header-package-names line) out)

        (and (seq package-names)
             (re-find #"^\s+version\s+" line))
        (let [[_ version] (re-find #"^\s+version\s+['\"]([^'\"]+)['\"]" line)
              facts (keep #(package-version-fact {:ecosystem :npm
                                                  :package-name %
                                                  :resolved-version version
                                                  :source-line (inc idx)})
                          package-names)]
          (recur (rest remaining) [] (into out facts)))

        :else
        (recur (rest remaining) package-names out))
      (vec (distinct out)))))

(defn- gemfile-lock-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-matches #"^\s{4}([A-Za-z0-9_.-]+)\s+\(([^)\s]+).*" line)]
                 (package-version-fact {:ecosystem :rubygems
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))

(defn- toml-package-version-facts
  [content ecosystem]
  (->> (str/split content #"(?m)^\[\[package\]\]\s*$")
       (keep (fn [block]
               (let [package-name (toml-string-value block "name")
                     version (toml-string-value block "version")]
                 (package-version-fact {:ecosystem ecosystem
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))

(defn- pubspec-lock-version-facts
  [content]
  (->> (re-seq #"(?m)^\s{2}([A-Za-z0-9_.-]+):\s*$|^\s{4}version:\s+['\"]?([^'\"\n]+)['\"]?\s*$"
               content)
       (reduce (fn [{:keys [current out]} [_ package-name version]]
                 (cond
                   package-name {:current package-name :out out}
                   (and current version) {:current nil
                                          :out (conj out
                                                     (package-version-fact
                                                      {:ecosystem :pub
                                                       :package-name current
                                                       :resolved-version version
                                                       :source-line 1}))}
                   :else {:current current :out out}))
               {:current nil :out []})
       :out
       (remove nil?)
       distinct
       vec))

(defn- composer-lock-version-facts
  [content]
  (if-let [m (read-json-map content)]
    (->> (concat (:packages m) (:packages-dev m))
         (keep (fn [package]
                 (when (map? package)
                   (package-version-fact {:ecosystem :composer
                                          :package-name (:name package)
                                          :resolved-version (:version package)
                                          :source-line 1}))))
         distinct
         vec)
    []))

(defn- pipfile-lock-version
  [package]
  (some-> (:version package)
          (str/replace #"^==" "")))

(defn- pipfile-lock-version-facts
  [content]
  (if-let [m (read-json-map content)]
    (->> (concat (:default m) (:develop m))
         (keep (fn [[package-name package]]
                 (when (map? package)
                   (package-version-fact {:ecosystem :pypi
                                          :package-name (json-key-label package-name)
                                          :resolved-version (pipfile-lock-version package)
                                          :source-line 1}))))
         distinct
         vec)
    []))

(defn- mix-lock-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-find #"\"([^\"]+)\"\s*:\s*\{:\w+,\s*:?\w+,\s*\"([^\"]+)\""
                                   line)]
                 (package-version-fact {:ecosystem :hex
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))

(defn- requirements-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [line (str/trim (first (str/split line #"\s+#" 2)))]
                 (when-let [[_ package-name version]
                            (re-matches #"(?i)^([A-Za-z0-9_.-]+)(?:\[[^\]]+\])?==([^;\s]+).*$"
                                        line)]
                   (package-version-fact {:ecosystem :pypi
                                          :package-name package-name
                                          :resolved-version version
                                          :source-line (inc idx)})))))
       distinct
       vec))

(defn- bun-lock-version-facts
  [content]
  (or (when-let [m (read-json-map content)]
        (->> (:packages m)
             (keep (fn [[package-name package]]
                     (cond
                       (string? package)
                       (package-version-fact {:ecosystem :npm
                                              :package-name (json-key-label package-name)
                                              :resolved-version package
                                              :source-line 1})

                       (map? package)
                       (package-version-fact {:ecosystem :npm
                                              :package-name (json-key-label package-name)
                                              :resolved-version (:version package)
                                              :source-line 1})

                       :else nil)))
             distinct
             vec))
      []))

(defn- dependency-lock-facts
  [{:keys [path content]}]
  (case (lockfile-name path)
    "package-lock.json" (package-lock-version-facts content)
    "cargo.lock" (cargo-lock-version-facts content)
    "go.sum" (go-sum-version-facts content)
    "pnpm-lock.yaml" (pnpm-lock-version-facts content)
    "yarn.lock" (yarn-lock-version-facts content)
    "gemfile.lock" (gemfile-lock-version-facts content)
    "poetry.lock" (toml-package-version-facts content :pypi)
    "uv.lock" (toml-package-version-facts content :pypi)
    "pubspec.lock" (pubspec-lock-version-facts content)
    "composer.lock" (composer-lock-version-facts content)
    "pipfile.lock" (pipfile-lock-version-facts content)
    "mix.lock" (mix-lock-version-facts content)
    "requirements.txt" (requirements-version-facts content)
    "bun.lock" (bun-lock-version-facts content)
    []))

(defn- version-package-fact
  [{:keys [ecosystem package-name source-line]}]
  (package-fact {:ecosystem ecosystem
                 :package-name package-name
                 :source-line source-line
                 :relation :version-of}))

(defn- version-of-edge
  [run-id file-id path id-scope version-fact]
  (let [package-fact (version-package-fact version-fact)]
    (fact-edge-row run-id
                   file-id
                   path
                   (node-id id-scope (:kind version-fact) (:label version-fact))
                   id-scope
                   package-fact)))

(defn extract-dependency-lock
  "Extract exact package version facts from supported dependency lockfiles."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (dependency-lock-facts file)
        package-facts (->> facts
                           (map version-package-fact)
                           (remove nil?)
                           distinct
                           vec)
        lock-node (generic-node run-id id-scope file-id path :dependency-lock path 1)
        fact-nodes (mapv #(fact-node run-id id-scope file-id path %)
                         (concat package-facts facts))
        resolve-edges (mapv #(fact-edge-row run-id
                                            file-id
                                            path
                                            (:xt/id lock-node)
                                            id-scope
                                            %)
                            facts)
        version-edges (mapv #(version-of-edge run-id file-id path id-scope %) facts)
        chunk-result (extract-text-source run-id file :dependency-lock-file)]
    {:nodes (into [lock-node] fact-nodes)
     :edges (vec (concat resolve-edges version-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- xml-start-tags
  [content]
  (let [matcher (re-matcher #"(?is)<([A-Za-z_][A-Za-z0-9_.:-]*)(?:\s+[^<>]*?)?/?>"
                            content)
        line-starts (line-start-offsets content)]
    (loop [out []]
      (if (.find matcher)
        (let [offset (.start matcher)
              source-line (count (take-while #(<= % offset) line-starts))]
          (recur (conj out {:element (.group matcher 0)
                            :tag (.group matcher 1)
                            :source-line (max 1 source-line)})))
        out))))

(defn- xml-element-label
  [{:keys [element tag]}]
  (let [identifier (or (xml-attr-value element "android:id")
                       (xml-attr-value element "id")
                       (xml-attr-value element "name")
                       (xml-attr-value element "android:name"))]
    (if (seq identifier)
      (str tag "#" identifier)
      tag)))

(defn- xml-reference-values
  [element]
  (->> (re-seq #"[\"'](@[^\"']+)[\"']" element)
       (map second)
       (remove #(str/starts-with? % "@+id/"))
       distinct
       vec))

(defn extract-xml
  "Extract bounded XML element and explicit resource reference facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [xml-node (generic-node run-id id-scope file-id path :xml-file path 1)
        elements (->> (xml-start-tags content)
                      (map #(assoc % :label (xml-element-label %)))
                      (remove #(= (:label %) (:tag %)))
                      distinct
                      vec)
        element-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id
                                            id-scope
                                            file-id
                                            path
                                            :xml-element
                                            label
                                            source-line))
                            elements)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id xml-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           element-nodes)
        reference-edges (->> elements
                             (mapcat (fn [{:keys [label element source-line]}]
                                       (map (fn [reference]
                                              (edge-row run-id
                                                        file-id
                                                        path
                                                        (node-id id-scope :xml-element label)
                                                        (node-id id-scope :xml-reference reference)
                                                        :references
                                                        :extracted
                                                        source-line))
                                            (xml-reference-values element))))
                             distinct
                             vec)
        chunk-result (extract-text-source run-id file :xml-file)]
    {:nodes (into [xml-node] element-nodes)
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- apple-config-settings
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ key value]
                          (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.$()/-]*)\s*=\s*(.*?)\s*$"
                                      line)]
                 (when-not (str/starts-with? (str/trim line) "//")
                   {:kind :build-setting
                    :label (str key "=" (str/trim value))
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))

(defn extract-apple-config
  "Extract bounded Apple xcconfig build setting facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [config-node (generic-node run-id id-scope file-id path :apple-config path 1)
        settings (apple-config-settings (:content file))
        setting-nodes (mapv (fn [{:keys [kind label source-line]}]
                              (generic-node run-id id-scope file-id path kind label source-line))
                            settings)
        setting-edges (mapv (fn [{:keys [kind label source-line relation]}]
                              (edge-row run-id
                                        file-id
                                        path
                                        (:xt/id config-node)
                                        (node-id id-scope kind label)
                                        relation
                                        :extracted
                                        source-line))
                            settings)
        chunk-result (extract-text-source run-id file :apple-config-file)]
    {:nodes (into [config-node] setting-nodes)
     :edges setting-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

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

(defn- config-json-facts
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (->> parsed
           (keep (fn [[k v]]
                   (when (or (string? v) (number? v) (boolean? v))
                     {:kind :config-setting
                      :label (str (name k) "=" v)
                      :source-line 1
                      :relation :defines})))
           vec))
    (catch Exception _
      [])))

(defn- config-assignment-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ key]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*[\{\[].*$"
                                          line)]
                     {:kind :config-section
                      :label key
                      :source-line (inc idx)
                      :relation :defines})
                   (when-let [[_ key value]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*['\"]?([^,'\"#]+)['\"]?.*$"
                                          line)]
                     {:kind :config-setting
                      :label (str key "=" (str/trim value))
                      :source-line (inc idx)
                      :relation :defines})
                   (when-let [[_ key]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*$"
                                          line)]
                     {:kind :config-section
                      :label key
                      :source-line (inc idx)
                      :relation :defines}))))
       distinct
       vec))

(defn- config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :config-reference
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))

(def config-reference-keys
  #{"extends" "plugins" "setupFiles" "setupFilesAfterEnv" "reporters"
    "projects" "presets"})

(defn- config-string-reference-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (when-let [[_ key rest]
                            (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(\[.*\]).*$" line)]
                   (when (contains? config-reference-keys key)
                     (map (fn [[_ target]]
                            {:kind :config-reference
                             :label target
                             :source-line (inc idx)
                             :relation :references})
                          (re-seq #"['\"]([^'\"]+)['\"]" rest))))))
       (remove nil?)
       distinct
       vec))

(defn- config-facts
  [{:keys [path content]}]
  (let [path-lower (str/lower-case (str path))
        filename (manifest-name path)
        json-facts (when (or (str/ends-with? path-lower ".json")
                             (str/ends-with? path-lower ".jsonc")
                             (contains? #{".prettierrc" ".eslintrc" ".stylelintrc"}
                                        filename))
                     (config-json-facts content))]
    (vec (concat (or json-facts [])
                 (config-assignment-facts content)
                 (config-import-facts path content)
                 (config-string-reference-facts content)))))

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
    (vec (concat (config-facts file)
                 (case filename
                   "flyway.conf" (flyway-config-facts content)
                   [])))))

(defn- extract-config-facts
  [run-id {:keys [id-scope file-id path] :as file} root-kind chunk-kind]
  (let [config-node (generic-node run-id id-scope file-id path root-kind path 1)
        facts (config-facts file)
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
    (vec (concat (config-facts file) special-facts))))

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
    (re-matches #"next\.config\.(?:js|cjs|mjs|ts)" filename) "next"
    (re-matches #"vite\.config\.(?:js|cjs|mjs|ts)" filename) "vite"
    (re-matches #"svelte\.config\.(?:js|cjs|mjs|ts)" filename) "sveltekit"
    (re-matches #"nuxt\.config\.(?:js|cjs|mjs|ts)" filename) "nuxt"
    (re-matches #"astro\.config\.(?:js|cjs|mjs|ts)" filename) "astro"
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
  (str/replace value #"\.(?:js|jsx|ts|tsx|mjs|cjs|svelte|vue|astro)$" ""))

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

(defn- web-route-info
  [path]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (re-find #"(?:^|/)app/(?:.+/)?(?:page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs)$"
               path-lower)
      (let [[_ route-part file-role] (re-find #"(?:^|/)app/(.*)/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs)$"
                                              path-lower)
            [_ file-role-root] (re-find #"(?:^|/)app/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs)$"
                                        path-lower)
            file-role (or file-role file-role-root)
            route-part (or route-part "")
            segments (if (seq route-part) (str/split route-part #"/") [])]
        {:framework "next"
         :route (route-path-from-segments segments)
         :role file-role})

      (re-find #"(?:^|/)pages/.+\.(?:js|jsx|ts|tsx|mjs|cjs)$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)pages/(.+)\.(?:js|jsx|ts|tsx|mjs|cjs)$"
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
                :web-framework-page)
        :label (str route ":" role)
        :source-line 1
        :relation :defines}]
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
  [{:keys [path] :as file}]
  (if (web-route-info path)
    (web-route-facts file)
    (web-config-facts file)))

(defn- web-framework-base-kind
  [path]
  (case (fs/extension path)
    (".ts" ".tsx" ".mts") :typescript
    (".js" ".jsx" ".mjs" ".cjs") :javascript
    ".svelte" :svelte
    ".astro" :astro
    ".vue" :vue
    nil))

(defn- web-framework-base-result
  [run-id {:keys [path] :as file}]
  (when (web-route-info path)
    (case (web-framework-base-kind path)
      :typescript (extract-js-family run-id (assoc file :kind :typescript))
      :javascript (extract-js-family run-id (assoc file :kind :javascript))
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

                       (re-matches (re-pattern (js-identifier)) segment)
                       segment

                       :else nil))))
           distinct
           vec))
    []))

(defn- content-config-define-collection-forms
  [content]
  (let [line-starts (line-start-offsets content)]
    (->> (re-seq (re-pattern (str "\\b(?:const|let|var)\\s+("
                                  (js-identifier)
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
     (or (re-find #"(^|/)\.vitepress/config\.(?:js|mjs|mts|ts)$" path-lower)
         (re-find #"(^|/)\.vitepress/config/index\.(?:js|mjs|mts|ts)$"
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

(defn- docs-config-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (case filename
      ("next.config.cjs" "next.config.js" "next.config.mjs" "next.config.ts")
      (nextra-next-config-facts path content)

      ("_meta.js" "_meta.jsx" "_meta.mjs" "_meta.ts" "_meta.tsx")
      (nextra-meta-facts content)

      "conf.py"
      (sphinx-config-facts content)

      ("config.js" "config.mjs" "config.mts" "config.ts"
                   "index.js" "index.mjs" "index.mts" "index.ts")
      (cond
        (vitepress-config-path? path)
        (vitepress-config-facts {:path path :content content})

        (re-find #"(^|/)src/content/config\.(?:js|mjs|ts)$"
                 (str/replace (str/lower-case (str path)) "\\" "/"))
        (astro-content-config-facts {:path path :content content})

        :else [])

      ("content.config.js" "content.config.mjs" "content.config.ts")
      (astro-content-config-facts {:path path :content content})

      ("docusaurus.config.js" "docusaurus.config.cjs"
                              "docusaurus.config.mjs" "docusaurus.config.ts")
      (vec (concat (docs-config-title-facts content)
                   (docs-config-route-facts content)
                   (docs-config-reference-facts content)
                   (docs-config-plugin-facts content)))

      ("sidebars.js" "sidebars.ts")
      (docs-sidebar-facts content)

      ("mkdocs.yml" "mkdocs.yaml")
      (mkdocs-line-facts content)

      [])))

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

(defn- notebook-cell-source
  [cell]
  (let [source (:source cell)]
    (cond
      (string? source) source
      (vector? source) (apply str source)
      :else "")))

(defn extract-notebook
  "Extract notebook metadata and cell chunks from Jupyter `.ipynb` files."
  [run-id {:keys [id-scope file-id path content]}]
  (let [parsed (read-json-value content)
        notebook-node (generic-node run-id id-scope file-id path :notebook-file path 1)
        metadata (when (map? parsed) (:metadata parsed))
        kernel (get-in metadata [:kernelspec :name])
        language (get-in metadata [:language_info :name])
        cells (if (vector? (:cells parsed)) (:cells parsed) [])
        metadata-facts (vec (remove nil?
                                    [(when (seq kernel)
                                       {:kind :notebook-kernel
                                        :label (json-label kernel)
                                        :source-line 1
                                        :relation :uses})
                                     (when (seq language)
                                       {:kind :notebook-language
                                        :label (json-label language)
                                        :source-line 1
                                        :relation :uses})]))
        cell-facts (mapv (fn [idx cell]
                           (let [cell-type (json-label (or (:cell_type cell) "unknown"))]
                             {:kind :notebook-cell
                              :label (str path "#cell-" idx ":" cell-type)
                              :source-line 1
                              :relation :defines
                              :cell-type cell-type
                              :cell-source (notebook-cell-source cell)}))
                         (range)
                         cells)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         (concat metadata-facts cell-facts))
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id notebook-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         (concat metadata-facts cell-facts))
        cell-chunks (mapv (fn [{:keys [label cell-type cell-source]}]
                            (source-text-chunk run-id
                                               id-scope
                                               file-id
                                               path
                                               (if (= "code" cell-type)
                                                 :notebook-code-cell
                                                 :notebook-markdown-cell)
                                               label
                                               cell-source
                                               jvm-family-definition-chunk-lines))
                          cell-facts)
        diagnostic (when-not (map? parsed)
                     [(diagnostic-row run-id file-id path :parse 1
                                      "Notebook JSON could not be parsed.")])]
    {:nodes (into [notebook-node] fact-nodes)
     :edges fact-edges
     :chunks cell-chunks
     :diagnostics (or diagnostic [])}))

(def devcontainer-command-keys
  [:initializeCommand :onCreateCommand :updateContentCommand
   :postCreateCommand :postStartCommand :postAttachCommand])

(defn- devcontainer-command-label
  [command]
  (cond
    (string? command) command
    (vector? command) (str/join " " (map str command))
    (map? command) (str/join " && " (map (fn [[k v]]
                                           (str (json-key-label k) "="
                                                (json-label v)))
                                         command))
    :else (str command)))

(defn- devcontainer-facts
  [content]
  (let [m (read-json-map content)
        features (:features m)
        run-services (:runServices m)
        forward-ports (:forwardPorts m)]
    (vec (concat
          (remove nil?
                  [(when-let [image (:image m)]
                     {:kind :container-image
                      :label (json-label image)
                      :source-line 1
                      :relation :uses})
                   (when-let [dockerfile (get-in m [:build :dockerfile])]
                     {:kind :build-reference
                      :label (json-label dockerfile)
                      :source-line 1
                      :relation :references})])
          (when (map? features)
            (map (fn [[feature _]]
                   {:kind :devcontainer-feature
                    :label (json-key-label feature)
                    :source-line 1
                    :relation :uses})
                 features))
          (when (vector? run-services)
            (map (fn [service]
                   {:kind :devcontainer-service
                    :label (json-label service)
                    :source-line 1
                    :relation :uses})
                 run-services))
          (when (vector? forward-ports)
            (map (fn [port]
                   {:kind :devcontainer-port
                    :label (json-label port)
                    :source-line 1
                    :relation :references})
                 forward-ports))
          (keep (fn [k]
                  (when-let [command (get m k)]
                    {:kind :devcontainer-command
                     :label (str (name k) "="
                                 (devcontainer-command-label command))
                     :source-line 1
                     :relation :defines}))
                devcontainer-command-keys)))))

(defn extract-devcontainer
  "Extract bounded Dev Container configuration facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :devcontainer-file
                        :devcontainer-file
                        (devcontainer-facts (:content file))))

(def kustomize-reference-sections
  #{"resources" "bases" "components" "patches" "patchesStrategicMerge"
    "configurations"})

(def kustomize-generator-sections
  #{"configMapGenerator" "secretGenerator"})

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

(defn- kustomize-facts
  [content]
  (let [items (yaml-section-items content
                                  (into kustomize-reference-sections
                                        (conj kustomize-generator-sections
                                              "images")))]
    (->> items
         (keep (fn [{:keys [section value source-line]}]
                 (when (seq value)
                   (cond
                     (contains? kustomize-reference-sections section)
                     {:kind :kustomize-reference
                      :label (str section "=" value)
                      :source-line source-line
                      :relation :references}

                     (= "images" section)
                     {:kind :container-image
                      :label value
                      :source-line source-line
                      :relation :references}

                     (contains? kustomize-generator-sections section)
                     {:kind :kustomize-generator
                      :label (str section "=" value)
                      :source-line source-line
                      :relation :defines}))))
         distinct
         vec)))

(defn extract-kustomize
  "Extract bounded Kustomize resources, patches, images, and generators."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :kustomize-file
                        :kustomize-file
                        (kustomize-facts (:content file))))

(defn- pre-commit-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (cond
                 (re-matches #"^\s*-\s+repo:\s+(.+?)\s*$" line)
                 (let [[_ repo] (re-matches #"^\s*-\s+repo:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-repo
                    :label (strip-yaml-scalar repo)
                    :source-line (inc idx)
                    :relation :references})

                 (re-matches #"^\s*rev:\s+(.+?)\s*$" line)
                 (let [[_ rev] (re-matches #"^\s*rev:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-rev
                    :label (strip-yaml-scalar rev)
                    :source-line (inc idx)
                    :relation :references})

                 (re-matches #"^\s*-\s+id:\s+(.+?)\s*$" line)
                 (let [[_ hook] (re-matches #"^\s*-\s+id:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-hook
                    :label (strip-yaml-scalar hook)
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))

(defn extract-pre-commit-config
  "Extract bounded pre-commit repository, revision, and hook facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :pre-commit-config-file
                        :pre-commit-config-file
                        (pre-commit-facts (:content file))))

(defn- codeowners-rules
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when (and (seq trimmed)
                            (not (str/starts-with? trimmed "#")))
                   (let [[pattern & owners] (str/split trimmed #"\s+")]
                     (when (and (seq pattern) (seq owners))
                       {:pattern pattern
                        :owners owners
                        :source-line (inc idx)}))))))
       vec))

(defn- codeowner-pattern-syntax-labels
  [pattern]
  (vec (concat
        (when (= "*" pattern)
          [(str "wildcard:" pattern)])
        (when (str/starts-with? pattern "/")
          [(str "rooted:" pattern)])
        (when (str/ends-with? pattern "/")
          [(str "directory:" pattern)])
        (when (str/includes? pattern "*")
          [(str "glob:" pattern)])
        (when (str/starts-with? pattern "!")
          [(str "negated:" pattern)]))))

(defn- codeowner-owner-syntax-label
  [owner]
  (cond
    (re-matches #"^@[^/\s]+/[^/\s]+$" owner) (str "team:" owner)
    (str/starts-with? owner "@") (str "handle:" owner)
    (re-matches #"^[^@\s]+@[^@\s]+\.[^@\s]+$" owner) (str "email:" owner)
    :else (str "owner:" owner)))

(defn extract-codeowners
  "Extract CODEOWNERS rule patterns and owner handles."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [file-node (generic-node run-id id-scope file-id path :codeowners-file path 1)
        rules (codeowners-rules content)
        rule-nodes (mapv (fn [{:keys [pattern source-line]}]
                           (generic-node run-id id-scope file-id path
                                         :codeowner-rule pattern source-line))
                         rules)
        owners (->> rules
                    (mapcat (fn [{:keys [owners source-line]}]
                              (map (fn [owner]
                                     {:label owner
                                      :source-line source-line})
                                   owners)))
                    distinct
                    vec)
        owner-nodes (mapv (fn [{:keys [label source-line]}]
                            (generic-node run-id id-scope file-id path
                                          :codeowner label source-line))
                          owners)
        pattern-syntaxes (->> rules
                              (mapcat (fn [{:keys [pattern source-line]}]
                                        (map (fn [label]
                                               {:label label
                                                :source-line source-line})
                                             (codeowner-pattern-syntax-labels pattern))))
                              distinct
                              vec)
        pattern-syntax-nodes (mapv (fn [{:keys [label source-line]}]
                                     (generic-node run-id id-scope file-id path
                                                   :codeowner-pattern-syntax
                                                   label
                                                   source-line))
                                   pattern-syntaxes)
        owner-syntaxes (->> owners
                            (map (fn [{:keys [label source-line]}]
                                   {:label (codeowner-owner-syntax-label label)
                                    :source-line source-line}))
                            distinct
                            vec)
        owner-syntax-nodes (mapv (fn [{:keys [label source-line]}]
                                   (generic-node run-id id-scope file-id path
                                                 :codeowner-owner-syntax
                                                 label
                                                 source-line))
                                 owner-syntaxes)
        define-edges (mapv (fn [{:keys [pattern source-line]}]
                             (edge-row run-id
                                       file-id
                                       path
                                       (:xt/id file-node)
                                       (node-id id-scope :codeowner-rule pattern)
                                       :defines
                                       :extracted
                                       source-line))
                           rules)
        pattern-syntax-edges (mapcat
                              (fn [{:keys [pattern source-line]}]
                                (map (fn [label]
                                       (edge-row run-id
                                                 file-id
                                                 path
                                                 (node-id id-scope
                                                          :codeowner-rule
                                                          pattern)
                                                 (node-id id-scope
                                                          :codeowner-pattern-syntax
                                                          label)
                                                 :describes
                                                 :extracted
                                                 source-line))
                                     (codeowner-pattern-syntax-labels pattern)))
                              rules)
        owner-syntax-edges (mapv
                            (fn [{:keys [label source-line]}]
                              (edge-row run-id
                                        file-id
                                        path
                                        (node-id id-scope :codeowner label)
                                        (node-id id-scope
                                                 :codeowner-owner-syntax
                                                 (codeowner-owner-syntax-label label))
                                        :describes
                                        :extracted
                                        source-line))
                            owners)
        assign-edges (mapv (fn [{:keys [pattern owners source-line]}]
                             (mapv (fn [owner]
                                     (edge-row run-id
                                               file-id
                                               path
                                               (node-id id-scope
                                                        :codeowner-rule
                                                        pattern)
                                               (node-id id-scope
                                                        :codeowner
                                                        owner)
                                               :assigns
                                               :extracted
                                               source-line))
                                   owners))
                           rules)
        chunk-result (extract-text-source run-id file :codeowners-file)]
    {:nodes (vec (concat [file-node]
                         rule-nodes
                         owner-nodes
                         pattern-syntax-nodes
                         owner-syntax-nodes))
     :edges (vec (concat define-edges
                         pattern-syntax-edges
                         owner-syntax-edges
                         (apply concat assign-edges)))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- taskfile-task-facts
  [content]
  (->> (yaml-top-section-blocks (str/split-lines content) "tasks")
       (mapcat (fn [{:keys [label source-line lines]}]
                 (let [body (map second lines)
                       commands (->> body
                                     (map-indexed vector)
                                     (keep (fn [[offset line]]
                                             (or
                                              (when-let [[_ command]
                                                         (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                                                {:kind :task-command
                                                 :label (str label ":" command)
                                                 :source-line (+ source-line offset)
                                                 :relation :defines})
                                              (when-let [[_ command]
                                                         (re-matches #"^\s*cmd:\s+(.+?)\s*$" line)]
                                                {:kind :task-command
                                                 :label (str label ":" command)
                                                 :source-line (+ source-line offset)
                                                 :relation :defines})))))
                       deps (->> body
                                 (map-indexed vector)
                                 (mapcat (fn [[offset line]]
                                           (or
                                            (when-let [[_ dep]
                                                       (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)\s*$" line)]
                                              [{:kind :task-dependency
                                                :label (str label "->" dep)
                                                :source-line (+ source-line offset)
                                                :relation :references}])
                                            (when-let [[_ deps]
                                                       (re-matches #"^\s*deps:\s+\[(.+?)\]\s*$" line)]
                                              (map (fn [dep]
                                                     {:kind :task-dependency
                                                      :label (str label "->"
                                                                  (strip-yaml-scalar dep))
                                                      :source-line (+ source-line offset)
                                                      :relation :references})
                                                   (str/split deps #",")))
                                            []))))]
                   (concat [{:kind :task
                             :label label
                             :source-line source-line
                             :relation :defines}]
                           commands
                           deps))))
       (remove nil?)
       distinct
       vec))

(defn- justfile-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (or (str/blank? (str/trim line))
              (str/starts-with? (str/trim line) "#"))
          (recur (rest remaining) current out)

          (and current
               (re-matches #"^\s+(.+?)\s*$" line))
          (let [[_ command] (re-matches #"^\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   current
                   (conj out {:kind :task-command
                              :label (str current ":" command)
                              :source-line (inc idx)
                              :relation :defines})))

          (and (not (str/includes? line ":="))
               (re-matches #"^([A-Za-z0-9_.-]+)(?:\s+[^:]*)?:\s*(.*?)\s*$" line))
          (let [[_ recipe deps] (re-matches #"^([A-Za-z0-9_.-]+)(?:\s+[^:]*)?:\s*(.*?)\s*$" line)
                dep-facts (->> (str/split (or deps "") #"\s+")
                               (remove str/blank?)
                               (map (fn [dep]
                                      {:kind :task-dependency
                                       :label (str recipe "->" dep)
                                       :source-line (inc idx)
                                       :relation :references})))]
            (recur (rest remaining)
                   recipe
                   (into (conj out {:kind :task
                                    :label recipe
                                    :source-line (inc idx)
                                    :relation :defines})
                         dep-facts)))

          :else
          (recur (rest remaining) nil out))
        (vec (distinct out))))))

(defn- task-runner-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (if (contains? #{"justfile" ".justfile"} filename)
      (justfile-facts content)
      (taskfile-task-facts content))))

(defn extract-task-runner
  "Extract bounded task runner task, recipe, dependency, and command facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :task-runner-file
                        :task-runner-file
                        (task-runner-facts file)))

(defn- starlark-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (let [source-line (inc idx)]
                   (cond
                     (re-matches #"^\s*load\(\s*\"([^\"]+)\".*\)\s*$" line)
                     (let [[_ target] (re-matches #"^\s*load\(\s*\"([^\"]+)\".*\)\s*$" line)
                           symbols (->> (re-seq #"\"([^\"]+)\"" line)
                                        (map second)
                                        rest)]
                       (concat [{:kind :starlark-load
                                 :label target
                                 :source-line source-line
                                 :relation :references}]
                               (map (fn [symbol]
                                      {:kind :starlark-symbol
                                       :label (str target ":" symbol)
                                       :source-line source-line
                                       :relation :references})
                                    symbols)))

                     (re-matches #"^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)
                     (let [[_ name] (re-matches #"^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)]
                       [{:kind :starlark-function
                         :label name
                         :source-line source-line
                         :relation :defines}])

                     (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*rule\s*\(.*" line)
                     (let [[_ name] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*rule\s*\(.*" line)]
                       [{:kind :starlark-rule
                         :label name
                         :source-line source-line
                         :relation :defines}])

                     :else []))))
       distinct
       vec))

(defn extract-starlark
  "Extract bounded Starlark load, function, and rule facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :starlark-file
                        :starlark-file
                        (starlark-facts (:content file))))

(defn- version-file-tool
  [filename]
  (case filename
    ".node-version" "node"
    ".python-version" "python"
    ".ruby-version" "ruby"
    nil))

(defn- plain-tool-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when (and (seq trimmed)
                            (not (str/starts-with? trimmed "#")))
                   (let [[tool & versions] (str/split trimmed #"\s+")]
                     (when (and (seq tool) (seq versions))
                       {:kind :tool-version
                        :label (str tool "@" (str/join "," versions))
                        :source-line (inc idx)
                        :relation :defines}))))))
       distinct
       vec))

(defn- single-version-file-facts
  [tool content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [version (str/trim line)]
                 (when (and (seq version)
                            (not (str/starts-with? version "#")))
                   {:kind :tool-version
                    :label (str tool "@" version)
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))

(defn- unquote-toml-value
  [value]
  (-> value
      str/trim
      (str/replace #"^['\"]|['\"]$" "")
      str/trim))

(defn- mise-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (or (str/blank? trimmed)
              (str/starts-with? trimmed "#"))
          (recur (rest remaining) section out)

          (re-matches #"^\[(.+)\]$" trimmed)
          (let [[_ next-section] (re-matches #"^\[(.+)\]$" trimmed)]
            (recur (rest remaining) next-section out))

          (and (= "tools" section)
               (re-matches #"^([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" trimmed))
          (let [[_ tool version] (re-matches #"^([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   (conj out {:kind :tool-version
                              :label (str tool "@"
                                          (unquote-toml-value version))
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= "env" section)
               (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$" trimmed))
          (let [[_ k v] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   (conj out {:kind :tool-env
                              :label (str k "=" (unquote-toml-value v))
                              :source-line (inc idx)
                              :relation :defines})))

          (and section
               (str/starts-with? section "tasks."))
          (let [task-name (subs section (count "tasks."))]
            (recur (rest remaining)
                   section
                   (conj out {:kind :task
                              :label (unquote-toml-value task-name)
                              :source-line (inc idx)
                              :relation :defines})))

          :else
          (recur (rest remaining) section out)))
      (vec (distinct out)))))

(defn- tool-version-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (cond
      (= ".tool-versions" filename) (plain-tool-version-facts content)
      (version-file-tool filename) (single-version-file-facts
                                    (version-file-tool filename)
                                    content)
      (contains? #{"mise.toml" ".mise.toml"} filename) (mise-facts content)
      :else [])))

(defn extract-tool-version-config
  "Extract bounded tool and version declarations."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :tool-version-config-file
                        :tool-version-config-file
                        (tool-version-facts file)))

(defn- license-document-facts
  [content]
  (let [lines (str/split-lines content)
        first-title (->> lines
                         (map-indexed vector)
                         (keep (fn [[idx line]]
                                 (let [trimmed (str/trim line)]
                                   (when (and (seq trimmed)
                                              (not (str/starts-with? trimmed "SPDX-"))
                                              (re-find #"(?i)\blicen[sc]e\b" trimmed))
                                     {:kind :license-title
                                      :label trimmed
                                      :source-line (inc idx)
                                      :relation :defines}))))
                         first)]
    (vec
     (concat
      (when first-title [first-title])
      (->> lines
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (let [source-line (inc idx)]
                (concat
                 (when-let [[_ id]
                            (re-matches #"(?i)^\s*SPDX-License-Identifier:\s*(.+?)\s*$"
                                        line)]
                   [{:kind :license-id
                     :label (str/trim id)
                     :source-line source-line
                     :relation :defines}])
                 (when-let [[_ text]
                            (or (re-matches #"(?i)^\s*SPDX-FileCopyrightText:\s*(.+?)\s*$"
                                            line)
                                (re-matches #"(?i)^\s*(copyright\s+.+?)\s*$"
                                            line))]
                   [{:kind :copyright-notice
                     :label (str/trim text)
                     :source-line source-line
                     :relation :defines}])))))
           distinct)))))

(defn- governance-facts
  [{:keys [path content]}]
  (let [path-lower (str/lower-case path)
        file-kind (cond
                    (str/includes? path-lower ".github/issue_template/")
                    "issue-template"
                    (str/includes? path-lower "pull_request_template")
                    "pull-request-template"
                    (str/ends-with? path-lower "funding.yml")
                    "funding"
                    (str/ends-with? path-lower "funding.yaml")
                    "funding"
                    (str/ends-with? path-lower "security.md")
                    "security"
                    (str/ends-with? path-lower "contributing.md")
                    "contributing"
                    (contains? #{"license" "copying"} (manifest-name path))
                    "license"
                    (= "notice" (manifest-name path))
                    "notice"
                    :else "governance")
        line-facts (->> (str/split-lines content)
                        (map-indexed vector)
                        (mapcat
                         (fn [[idx line]]
                           (let [source-line (inc idx)]
                             (concat
                              (when-let [[_ heading]
                                         (re-matches #"^\s{0,3}#{1,6}\s+(.+?)\s*$" line)]
                                [{:kind :governance-section
                                  :label heading
                                  :source-line source-line
                                  :relation :defines}])
                              (when-let [{:keys [key value]} (yaml-key-line idx line)]
                                (when (seq value)
                                  (if (= "funding" file-kind)
                                    [{:kind :funding-platform
                                      :label (str key "=" (strip-yaml-scalar value))
                                      :source-line source-line
                                      :relation :defines}]
                                    [{:kind :governance-field
                                      :label (str key "=" (strip-yaml-scalar value))
                                      :source-line source-line
                                      :relation :defines}])))
                              (when-let [[_ item]
                                         (re-matches #"^\s*-\s+\[[ xX]\]\s+(.+?)\s*$" line)]
                                [{:kind :governance-check
                                  :label item
                                  :source-line source-line
                                  :relation :defines}])))))
                        distinct)]
    (vec
     (concat
      [{:kind :governance-file
        :label file-kind
        :source-line 1
        :relation :defines}]
      line-facts
      (when (contains? #{"license" "notice"} file-kind)
        (license-document-facts content))))))

(defn extract-governance
  "Extract bounded repository governance and GitHub template facts."
  [run-id file]
  (extract-format-facts run-id
                        file
                        :governance-config-file
                        :governance-config-file
                        (governance-facts file)))

(defn- sbom-clean-label
  [value]
  (let [label (some-> value str str/trim)]
    (when (seq label) label)))

(defn- sbom-package-label
  [package]
  (or (sbom-clean-label (:purl package))
      (sbom-clean-label (:bom-ref package))
      (when-let [name (sbom-clean-label (:name package))]
        (if-let [version (sbom-clean-label (or (:version package)
                                               (:versionInfo package)))]
          (str name "@" version)
          name))
      (sbom-clean-label (:SPDXID package))))

(defn- sbom-package-ref
  [package]
  (or (sbom-clean-label (:bom-ref package))
      (sbom-clean-label (:purl package))
      (sbom-clean-label (:SPDXID package))
      (sbom-package-label package)))

(defn- sbom-license-labels
  [package]
  (let [declared [(sbom-clean-label (:licenseConcluded package))
                  (sbom-clean-label (:licenseDeclared package))]
        cyclonedx (->> (:licenses package)
                       (mapcat (fn [entry]
                                 [(sbom-clean-label (get-in entry [:license :id]))
                                  (sbom-clean-label (get-in entry [:license :name]))
                                  (sbom-clean-label (:expression entry))])))]
    (->> (concat declared cyclonedx)
         (keep sbom-clean-label)
         (remove #(contains? #{"NOASSERTION" "NONE"} %))
         distinct
         vec)))

(defn- sbom-cyclonedx-packages
  [m]
  (let [metadata-component (get-in m [:metadata :component])]
    (vec (concat (when (map? metadata-component)
                   [metadata-component])
                 (filter map? (:components m))))))

(defn- sbom-document-label
  [m path]
  (or (sbom-clean-label (:SPDXID m))
      (sbom-clean-label (:serialNumber m))
      (sbom-clean-label (:name m))
      (some-> (get-in m [:metadata :component]) sbom-package-label)
      path))

(defn- sbom-kind
  [m]
  (cond
    (= "CycloneDX" (:bomFormat m)) :cyclonedx
    (or (:spdxVersion m) (:SPDXID m)) :spdx
    :else nil))

(defn- sbom-dependency-edges
  [kind m ref->label]
  (case kind
    :cyclonedx
    (->> (:dependencies m)
         (filter map?)
         (mapcat
          (fn [{:keys [ref dependsOn]}]
            (let [source (get ref->label (sbom-clean-label ref))]
              (when source
                (->> dependsOn
                     (keep #(when-let [target (get ref->label (sbom-clean-label %))]
                              {:source-label source
                               :target-label target})))))))
         distinct
         vec)

    :spdx
    (->> (:relationships m)
         (filter map?)
         (keep
          (fn [{:keys [spdxElementId relationshipType relatedSpdxElement]}]
            (when (= "DEPENDS_ON" relationshipType)
              (when-let [source (get ref->label (sbom-clean-label spdxElementId))]
                (when-let [target (get ref->label
                                       (sbom-clean-label relatedSpdxElement))]
                  {:source-label source
                   :target-label target})))))
         distinct
         vec)

    []))

(defn extract-sbom
  "Extract explicit SPDX/CycloneDX SBOM document, package, license, and dependency facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [m (read-json-map content)
        kind (sbom-kind m)
        packages (case kind
                   :cyclonedx (sbom-cyclonedx-packages m)
                   :spdx (vec (filter map? (:packages m)))
                   [])
        package-records (->> packages
                             (keep (fn [package]
                                     (when-let [label (sbom-package-label package)]
                                       {:package package
                                        :label label
                                        :ref (sbom-package-ref package)
                                        :licenses (sbom-license-labels package)})))
                             distinct
                             vec)
        ref->label (into {}
                         (keep (fn [{:keys [ref label]}]
                                 (when (seq ref)
                                   [ref label])))
                         package-records)
        document-label (when kind (sbom-document-label m path))
        document-node (when document-label
                        (generic-node run-id
                                      id-scope
                                      file-id
                                      path
                                      :sbom-document
                                      document-label
                                      1))
        package-nodes (->> package-records
                           (map (fn [{:keys [package label ref]}]
                                  (cond-> (generic-node run-id
                                                        id-scope
                                                        file-id
                                                        path
                                                        :sbom-package
                                                        label
                                                        1)
                                    (sbom-clean-label (:name package))
                                    (assoc :package-name
                                           (sbom-clean-label (:name package)))
                                    (sbom-clean-label (or (:version package)
                                                          (:versionInfo package)))
                                    (assoc :version
                                           (sbom-clean-label
                                            (or (:version package)
                                                (:versionInfo package))))
                                    (seq ref) (assoc :package-ref ref)
                                    (sbom-clean-label (:purl package))
                                    (assoc :purl (sbom-clean-label (:purl package))))))
                           vec)
        license-labels (->> package-records
                            (mapcat :licenses)
                            distinct
                            vec)
        license-nodes (mapv #(generic-node run-id
                                           id-scope
                                           file-id
                                           path
                                           :license-id
                                           %
                                           1)
                            license-labels)
        root-node (generic-node run-id id-scope file-id path :sbom-file path 1)
        root-id (:xt/id root-node)
        root->document (when document-node
                         [(edge-row run-id
                                    file-id
                                    path
                                    root-id
                                    (:xt/id document-node)
                                    :defines
                                    1.0
                                    1)])
        root->packages (mapv #(edge-row run-id
                                        file-id
                                        path
                                        root-id
                                        (:xt/id %)
                                        :defines
                                        1.0
                                        (:source-line %))
                             package-nodes)
        root->licenses (mapv #(edge-row run-id
                                        file-id
                                        path
                                        root-id
                                        (:xt/id %)
                                        :defines
                                        1.0
                                        (:source-line %))
                             license-nodes)
        package->licenses (->> package-records
                               (mapcat
                                (fn [{:keys [label licenses]}]
                                  (map (fn [license-label]
                                         (edge-row run-id
                                                   file-id
                                                   path
                                                   (node-id id-scope
                                                            :sbom-package
                                                            label)
                                                   (node-id id-scope
                                                            :license-id
                                                            license-label)
                                                   :licenses
                                                   1.0
                                                   1))
                                       licenses)))
                               distinct
                               vec)
        dependency-edges (->> (sbom-dependency-edges kind m ref->label)
                              (mapv (fn [{:keys [source-label target-label]}]
                                      (edge-row run-id
                                                file-id
                                                path
                                                (node-id id-scope
                                                         :sbom-package
                                                         source-label)
                                                (node-id id-scope
                                                         :sbom-package
                                                         target-label)
                                                :depends-on
                                                1.0
                                                1))))
        chunk-result (extract-text-source run-id file :sbom-file)]
    {:nodes (vec (distinct (concat [root-node]
                                   (when document-node [document-node])
                                   package-nodes
                                   license-nodes)))
     :edges (vec (distinct (concat root->document
                                   root->packages
                                   root->licenses
                                   package->licenses
                                   dependency-edges)))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- ops-strip-scalar
  [value]
  (-> (str value)
      (str/replace #"^\s*['\"]|['\"]\s*$" "")
      str/trim))

(defn- ops-yaml-key-line
  [idx line]
  (when-let [[_ indent key value]
             (re-matches #"^(\s*)(?:-\s*)?([A-Za-z0-9_.-]+):(?:\s*(.*))?$"
                         line)]
    {:indent (count indent)
     :key key
     :value (str/trim (or value ""))
     :source-line (inc idx)}))

(defn- ops-yaml-section-blocks
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
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

(defn- ops-yaml-section-settings
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          (recur (rest remaining) false nil out)

          (and in-section?
               entry
               (> (:indent entry) section-indent)
               (seq (:value entry)))
          (recur (rest remaining)
                 true
                 section-indent
                 (conj out {:key (:key entry)
                            :value (ops-strip-scalar (:value entry))
                            :source-line (:source-line entry)}))

          :else
          (recur (rest remaining) in-section? section-indent out)))
      out)))

(defn- ops-block-value
  [block key-name]
  (->> (:lines block)
       (keep (fn [[idx line]]
               (when-let [{:keys [key value source-line]} (ops-yaml-key-line idx line)]
                 (when (and (= key-name key) (seq value))
                   {:value (ops-strip-scalar value)
                    :source-line source-line}))))
       first))

(defn- ops-reference-targets
  [lines]
  (->> lines
       (mapcat (fn [[idx line]]
                 (let [source-line (inc idx)]
                   (concat
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"Ref:\s*([A-Za-z0-9]+)" line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"!Ref\s+([A-Za-z0-9]+)" line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"Fn::GetAtt:\s*\[\s*([A-Za-z0-9]+)\s*," line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"!GetAtt\s+([A-Za-z0-9]+)\." line))))))
       distinct
       vec))

(defn- ops-block-section-entry-labels
  [block section-name]
  (loop [remaining (:lines block)
         in-section? false
         section-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          out

          (and in-section?
               entry
               (= (:indent entry) (+ section-indent 2)))
          (recur (rest remaining)
                 true
                 section-indent
                 (conj out {:label (:key entry)
                            :source-line (:source-line entry)}))

          :else
          (recur (rest remaining) in-section? section-indent out)))
      out)))

(defn- ops-section-name-facts
  [content section-name kind]
  (->> (ops-yaml-section-blocks content section-name)
       (mapv (fn [{:keys [label source-line]}]
               {:kind kind
                :label label
                :source-line source-line
                :relation :defines}))))

(defn- json-intrinsic-reference-targets
  [value]
  (let [get-att-key (keyword "Fn::GetAtt")]
    (cond
      (map? value)
      (vec
       (distinct
        (concat
         (when (string? (:Ref value))
           [(:Ref value)])
         (let [get-att (get value get-att-key)]
           (cond
             (and (vector? get-att) (string? (first get-att))) [(first get-att)]
             (string? get-att) [(first (str/split get-att #"\."))]
             :else []))
         (mapcat json-intrinsic-reference-targets (vals value)))))

      (vector? value)
      (vec (distinct (mapcat json-intrinsic-reference-targets value)))

      :else [])))

(defn- ops-config-format
  [{:keys [path content ext]}]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))]
    (cond
      (contains? #{"serverless.yml" "serverless.yaml"} filename) :serverless
      (= "cdk.json" filename) :cdk
      (or (str/includes? content "AWS::Serverless")
          (re-find #"(?m)^Transform:\s*['\"]?AWS::Serverless" content)) :sam
      (or (str/includes? content "AWSTemplateFormatVersion")
          (and (re-find #"(?m)^Resources:\s*$" content)
               (str/includes? content "AWS::"))
          (and (str/includes? content "\"Resources\"")
               (str/includes? content "AWS::"))) :cloudformation
      (re-matches #"pulumi(?:\.[a-z0-9_.-]+)?\.ya?ml" filename) :pulumi
      (= "nginx.conf" filename) :nginx
      (contains? #{".service" ".socket" ".timer"} ext) :systemd
      (and (or (re-find #"(?m)^\s*-\s*hosts:\s*.+" content)
               (re-find #"(?m)^\s*hosts:\s*.+" content))
           (re-find #"(?m)^\s*tasks:\s*$" content)) :ansible
      :else :ops)))

(defn- cloudformation-yaml-facts
  [content]
  (let [lines (vec (str/split-lines content))
        base (loop [remaining (map-indexed vector lines)
                    in-resources? false
                    current-resource nil
                    facts []
                    refs []]
               (if-let [[idx line] (first remaining)]
                 (cond
                   (re-matches #"^\s*Resources:\s*$" line)
                   (recur (rest remaining) true nil facts refs)

                   (and in-resources?
                        (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line)
                        (not (re-matches #"^\s*Resources:\s*$" line)))
                   (recur (rest remaining) false nil facts refs)

                   (and in-resources?
                        (re-matches #"^\s{2}([A-Za-z0-9]+):\s*$" line))
                   (let [resource (second (re-matches #"^\s{2}([A-Za-z0-9]+):\s*$"
                                                      line))]
                     (recur (rest remaining)
                            true
                            resource
                            (conj facts {:kind :cloudformation-resource
                                         :label resource
                                         :source-line (inc idx)
                                         :relation :defines})
                            refs))

                   (and current-resource
                        (re-matches #"^\s+Type:\s*([A-Za-z0-9:_.-]+)\s*$" line))
                   (let [resource-type (second
                                        (re-matches #"^\s+Type:\s*([A-Za-z0-9:_.-]+)\s*$"
                                                    line))]
                     (recur (rest remaining)
                            in-resources?
                            current-resource
                            (conj facts {:kind :cloudformation-resource-type
                                         :label resource-type
                                         :source-line (inc idx)
                                         :relation :defines})
                            refs))

                   (and current-resource
                        (re-matches #"^\s+DependsOn:\s*([A-Za-z0-9]+)\s*$" line))
                   (let [target (second (re-matches #"^\s+DependsOn:\s*([A-Za-z0-9]+)\s*$"
                                                    line))]
                     (recur (rest remaining)
                            in-resources?
                            current-resource
                            facts
                            (conj refs {:source-kind :cloudformation-resource
                                        :source-label current-resource
                                        :target-kind :cloudformation-resource
                                        :target-label target
                                        :source-line (inc idx)})))

                   :else
                   (recur (rest remaining) in-resources? current-resource facts refs))
                 {:facts facts
                  :refs refs}))
        resources (ops-yaml-section-blocks content "Resources")
        parameters (ops-yaml-section-blocks content "Parameters")
        conditions (ops-yaml-section-blocks content "Conditions")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        parameter-labels (set (map :label parameters))
        condition-labels (set (map :label conditions))
        target-kind (fn [target]
                      (cond
                        (contains? resource-labels target) :cloudformation-resource
                        (contains? parameter-labels target) :cloudformation-parameter
                        (contains? condition-labels target) :cloudformation-condition
                        :else nil))
        block-ref-facts (fn [source-kind {:keys [label lines]}]
                          (->> (ops-reference-targets lines)
                               (keep (fn [{:keys [target source-line]}]
                                       (when-let [target-kind (target-kind target)]
                                         {:source-kind source-kind
                                          :source-label label
                                          :target-kind target-kind
                                          :target-label target
                                          :source-line source-line})))))
        condition-ref (fn [{:keys [label] :as block}]
                        (when-let [{:keys [value source-line]} (ops-block-value block "Condition")]
                          (when (contains? condition-labels value)
                            {:source-kind :cloudformation-resource
                             :source-label label
                             :target-kind :cloudformation-condition
                             :target-label value
                             :source-line source-line})))
        extra-facts (concat
                     (ops-section-name-facts content "Parameters"
                                             :cloudformation-parameter)
                     (ops-section-name-facts content "Mappings"
                                             :cloudformation-mapping)
                     (ops-section-name-facts content "Conditions"
                                             :cloudformation-condition)
                     (ops-section-name-facts content "Outputs"
                                             :cloudformation-output))
        extra-refs (concat
                    (mapcat #(block-ref-facts :cloudformation-resource %) resources)
                    (keep condition-ref resources)
                    (mapcat #(block-ref-facts :cloudformation-condition %) conditions)
                    (mapcat #(block-ref-facts :cloudformation-output %) outputs))]
    {:facts (vec (distinct (concat (:facts base) extra-facts)))
     :refs (vec (distinct (concat (:refs base) extra-refs)))}))

(defn- cloudformation-json-facts
  [content]
  (if-let [m (read-json-map content)]
    (let [resources (:Resources m)
          parameters (:Parameters m)
          mappings (:Mappings m)
          conditions (:Conditions m)
          outputs (:Outputs m)
          section-facts (fn [section kind]
                          (when (map? section)
                            (map (fn [[k _]]
                                   {:kind kind
                                    :label (json-key-label k)
                                    :source-line 1
                                    :relation :defines})
                                 section)))
          resource-labels (set (map json-key-label (keys (or resources {}))))
          parameter-labels (set (map json-key-label (keys (or parameters {}))))
          condition-labels (set (map json-key-label (keys (or conditions {}))))
          target-kind (fn [target]
                        (cond
                          (contains? resource-labels target) :cloudformation-resource
                          (contains? parameter-labels target) :cloudformation-parameter
                          (contains? condition-labels target) :cloudformation-condition
                          :else nil))
          reference-facts (fn [source-kind source-label value]
                            (->> (json-intrinsic-reference-targets value)
                                 (keep (fn [target]
                                         (when-let [target-kind (target-kind target)]
                                           {:source-kind source-kind
                                            :source-label source-label
                                            :target-kind target-kind
                                            :target-label target
                                            :source-line 1})))))]
      (if-not (map? resources)
        {:facts (vec
                 (distinct
                  (concat
                   (section-facts parameters :cloudformation-parameter)
                   (section-facts mappings :cloudformation-mapping)
                   (section-facts conditions :cloudformation-condition)
                   (section-facts outputs :cloudformation-output))))
         :refs []}
        (let [facts (->> resources
                         (mapcat (fn [[resource spec]]
                                   (let [resource-name (json-key-label resource)
                                         resource-type (:Type spec)]
                                     (concat
                                      [{:kind :cloudformation-resource
                                        :label resource-name
                                        :source-line 1
                                        :relation :defines}]
                                      (when (string? resource-type)
                                        [{:kind :cloudformation-resource-type
                                          :label resource-type
                                          :source-line 1
                                          :relation :defines}])))))
                         distinct
                         vec)
              depends-refs (->> resources
                                (mapcat (fn [[resource spec]]
                                          (let [depends (:DependsOn spec)
                                                targets (cond
                                                          (string? depends) [depends]
                                                          (vector? depends) (filter string? depends)
                                                          :else [])]
                                            (map (fn [target]
                                                   {:source-kind :cloudformation-resource
                                                    :source-label (json-key-label resource)
                                                    :target-kind :cloudformation-resource
                                                    :target-label target
                                                    :source-line 1})
                                                 targets)))))
              intrinsic-refs (concat
                              (mapcat (fn [[resource spec]]
                                        (reference-facts :cloudformation-resource
                                                         (json-key-label resource)
                                                         spec))
                                      resources)
                              (mapcat (fn [[condition spec]]
                                        (reference-facts :cloudformation-condition
                                                         (json-key-label condition)
                                                         spec))
                                      conditions)
                              (mapcat (fn [[output spec]]
                                        (reference-facts :cloudformation-output
                                                         (json-key-label output)
                                                         spec))
                                      outputs))]
          {:facts (vec
                   (distinct
                    (concat facts
                            (section-facts parameters :cloudformation-parameter)
                            (section-facts mappings :cloudformation-mapping)
                            (section-facts conditions :cloudformation-condition)
                            (section-facts outputs :cloudformation-output))))
           :refs (vec (distinct (concat depends-refs intrinsic-refs)))})))
    (cloudformation-yaml-facts content)))

(defn- cloudformation-facts
  [content]
  (if (read-json-map content)
    (cloudformation-json-facts content)
    (cloudformation-yaml-facts content)))

(defn- serverless-function-facts
  [function-block]
  (let [{:keys [label source-line lines]} function-block
        handler (ops-block-value function-block "handler")
        role (ops-block-value function-block "role")
        event-kinds (->> lines
                         (keep (fn [[idx line]]
                                 (when-let [[_ event-kind]
                                            (re-matches #"^\s*-\s*([A-Za-z0-9_.-]+):.*$"
                                                        line)]
                                   {:kind :serverless-event
                                    :label (str label ":" event-kind)
                                    :source-line (inc idx)
                                    :relation :defines}))))
        route-fact (fn [[idx line]]
                     (let [source-line (inc idx)]
                       (or
                        (when-let [[_ route]
                                   (re-matches #"^\s*path:\s*(.+?)\s*$" line)]
                          {:kind :serverless-event-route
                           :label (str label ":" (ops-strip-scalar route))
                           :source-line source-line
                           :relation :defines})
                        (when-let [[_ method]
                                   (re-matches #"^\s*method:\s*(.+?)\s*$" line)]
                          {:kind :serverless-event-method
                           :label (str label ":" (str/upper-case
                                                  (ops-strip-scalar method)))
                           :source-line source-line
                           :relation :defines}))))
        route-facts (keep route-fact lines)]
    (concat
     [{:kind :serverless-function
       :label label
       :source-line source-line
       :relation :defines}]
     (when handler
       [{:kind :serverless-handler
         :label (:value handler)
         :source-line (:source-line handler)
         :relation :defines}])
     (when role
       [{:kind :serverless-role
         :label (:value role)
         :source-line (:source-line role)
         :relation :references}])
     event-kinds
     route-facts)))

(defn- serverless-resource-facts
  [resource-blocks]
  (->> resource-blocks
       (mapcat (fn [{:keys [label source-line] :as block}]
                 (concat
                  [{:kind :serverless-resource
                    :label label
                    :source-line source-line
                    :relation :defines}]
                  (when-let [resource-type (ops-block-value block "Type")]
                    [{:kind :serverless-resource-type
                      :label (:value resource-type)
                      :source-line (:source-line resource-type)
                      :relation :defines}]))))
       distinct
       vec))

(defn- serverless-facts
  [content]
  (let [provider-settings (ops-yaml-section-settings content "provider")
        provider-facts (->> provider-settings
                            (keep (fn [{:keys [key value source-line]}]
                                    (case key
                                      "name" {:kind :serverless-provider
                                              :label value
                                              :source-line source-line
                                              :relation :uses}
                                      "runtime" {:kind :serverless-runtime
                                                 :label value
                                                 :source-line source-line
                                                 :relation :defines}
                                      "stage" {:kind :serverless-stage
                                               :label value
                                               :source-line source-line
                                               :relation :defines}
                                      nil))))
        functions (ops-yaml-section-blocks content "functions")
        resources (ops-yaml-section-blocks content "Resources")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        output-facts (mapv (fn [{:keys [label source-line]}]
                             {:kind :serverless-output
                              :label label
                              :source-line source-line
                              :relation :defines})
                           outputs)
        function-role-ref (fn [{:keys [label] :as block}]
                            (when-let [role (ops-block-value block "role")]
                              (when (contains? resource-labels (:value role))
                                {:source-kind :serverless-function
                                 :source-label label
                                 :target-kind :serverless-resource
                                 :target-label (:value role)
                                 :source-line (:source-line role)})))
        output-ref-facts (fn [{:keys [label lines]}]
                           (->> (ops-reference-targets lines)
                                (keep (fn [{:keys [target source-line]}]
                                        (when (contains? resource-labels target)
                                          {:source-kind :serverless-output
                                           :source-label label
                                           :target-kind :serverless-resource
                                           :target-label target
                                           :source-line source-line})))))
        function-role-refs (keep function-role-ref functions)
        output-refs (mapcat output-ref-facts outputs)
        service-facts (when-let [service (yaml-top-level-value content "service")]
                        [{:kind :serverless-service
                          :label service
                          :source-line 1
                          :relation :defines}])]
    {:facts (vec (distinct
                  (concat service-facts
                          provider-facts
                          (mapcat serverless-function-facts functions)
                          (serverless-resource-facts resources)
                          output-facts)))
     :refs (vec (distinct (concat function-role-refs output-refs)))}))

(defn- sam-resource-facts
  [resource-blocks]
  (->> resource-blocks
       (mapcat (fn [{:keys [label source-line] :as block}]
                 (let [resource-type (ops-block-value block "Type")
                       sam-function? (= "AWS::Serverless::Function" (:value resource-type))
                       handler (ops-block-value block "Handler")
                       runtime (ops-block-value block "Runtime")
                       role (ops-block-value block "Role")
                       events (ops-block-section-entry-labels block "Events")]
                   (concat
                    [{:kind :sam-resource
                      :label label
                      :source-line source-line
                      :relation :defines}]
                    (when resource-type
                      [{:kind :sam-resource-type
                        :label (:value resource-type)
                        :source-line (:source-line resource-type)
                        :relation :defines}])
                    (when sam-function?
                      [{:kind :sam-function
                        :label label
                        :source-line source-line
                        :relation :defines}])
                    (when (and sam-function? handler)
                      [{:kind :sam-handler
                        :label (:value handler)
                        :source-line (:source-line handler)
                        :relation :defines}])
                    (when (and sam-function? runtime)
                      [{:kind :sam-runtime
                        :label (:value runtime)
                        :source-line (:source-line runtime)
                        :relation :defines}])
                    (when (and sam-function? role)
                      [{:kind :sam-role
                        :label (:value role)
                        :source-line (:source-line role)
                        :relation :references}])
                    (map (fn [{:keys [label source-line]}]
                           {:kind :sam-event
                            :label label
                            :source-line source-line
                            :relation :defines})
                         events)))))
       distinct
       vec))

(defn- sam-facts
  [content]
  (let [resources (ops-yaml-section-blocks content "Resources")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        output-facts (mapv (fn [{:keys [label source-line]}]
                             {:kind :sam-output
                              :label label
                              :source-line source-line
                              :relation :defines})
                           outputs)
        block-ref-facts (fn [source-kind {:keys [label lines]}]
                          (->> (ops-reference-targets lines)
                               (keep (fn [{:keys [target source-line]}]
                                       (when (contains? resource-labels target)
                                         {:source-kind source-kind
                                          :source-label label
                                          :target-kind :sam-resource
                                          :target-label target
                                          :source-line source-line})))))
        resource-refs (mapcat #(block-ref-facts :sam-resource %) resources)
        output-refs (mapcat #(block-ref-facts :sam-output %) outputs)]
    {:facts (vec (distinct (concat (sam-resource-facts resources)
                                   output-facts)))
     :refs (vec (distinct (concat resource-refs output-refs)))}))

(defn- cdk-facts
  [content]
  (if-let [m (read-json-map content)]
    (let [app (:app m)
          context (:context m)
          watch (:watch m)
          watch-values (fn [key kind]
                         (->> (get watch key)
                              (filter string?)
                              (map (fn [value]
                                     {:kind kind
                                      :label value
                                      :source-line 1
                                      :relation :defines}))))]
      {:facts (vec
               (distinct
                (concat
                 (when (string? app)
                   [{:kind :cdk-app
                     :label app
                     :source-line 1
                     :relation :defines}])
                 (when (map? context)
                   (mapcat (fn [[k v]]
                             (let [label (json-key-label k)]
                               (concat
                                [{:kind :cdk-context-key
                                  :label label
                                  :source-line 1
                                  :relation :defines}]
                                (when (or (string? v) (number? v) (boolean? v))
                                  [{:kind :cdk-context-setting
                                    :label (str label "=" v)
                                    :source-line 1
                                    :relation :defines}]))))
                           context))
                 (watch-values :include :cdk-watch-include)
                 (watch-values :exclude :cdk-watch-exclude))))
       :refs []})
    {:facts [] :refs []}))

(defn- pulumi-stack-name
  [path]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))]
    (some-> (re-matches #"pulumi\.([a-z0-9_.-]+)\.ya?ml" filename)
            second)))

(defn- pulumi-config-entries
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-config? false
         config-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= "config" (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-config?
               entry
               (<= (:indent entry) config-indent)
               (not= "config" (:key entry)))
          (recur (rest remaining) false nil out)

          (and in-config?
               (> (count (take-while #(= \space %) line)) config-indent)
               (re-matches #"^\s*([A-Za-z0-9_.:-]+):\s*(.+?)\s*$" line))
          (let [[_ key value] (re-matches #"^\s*([A-Za-z0-9_.:-]+):\s*(.+?)\s*$"
                                          line)
                value (ops-strip-scalar value)]
            (recur (rest remaining)
                   true
                   config-indent
                   (conj out {:key key
                              :value value
                              :secure? (or (str/includes? value "secure:")
                                           (str/includes? value "{secure"))
                              :source-line (inc idx)})))

          :else
          (recur (rest remaining) in-config? config-indent out)))
      out)))

(defn- pulumi-facts
  [{:keys [path content]}]
  (let [stack-name (pulumi-stack-name path)]
    (->> (concat
          (when stack-name
            [{:kind :pulumi-stack
              :label stack-name
              :source-line 1
              :relation :defines}])
          (when-let [name (yaml-top-level-value content "name")]
            [{:kind :pulumi-project
              :label name
              :source-line 1
              :relation :defines}])
          (when-let [runtime (yaml-top-level-value content "runtime")]
            [{:kind :pulumi-runtime
              :label runtime
              :source-line 1
              :relation :defines}])
          (when-let [secrets-provider (yaml-top-level-value content "secretsprovider")]
            [{:kind :pulumi-secrets-provider
              :label secrets-provider
              :source-line 1
              :relation :uses}])
          (mapcat (fn [{:keys [key value secure? source-line]}]
                    (concat
                     [{:kind :pulumi-config-key
                       :label key
                       :source-line source-line
                       :relation :defines}]
                     (if secure?
                       [{:kind :pulumi-secret-config
                         :label key
                         :source-line source-line
                         :relation :defines}]
                       [{:kind :pulumi-config-value
                         :label (str key "=" value)
                         :source-line source-line
                         :relation :defines}])))
                  (pulumi-config-entries content)))
         distinct
         vec)))

(defn- ansible-facts
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ hosts]
                          (or (re-matches #"^\s*-\s*hosts:\s*(.+?)\s*$" line)
                              (re-matches #"^\s*hosts:\s*(.+?)\s*$" line))]
                 [{:kind :ansible-play
                   :label (str "hosts=" hosts)
                   :source-line source-line
                   :relation :defines}
                  {:kind :ops-host
                   :label hosts
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ task-name]
                          (re-matches #"^\s*-\s*name:\s*(.+?)\s*$" line)]
                 [{:kind :ansible-task
                   :label task-name
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ module-name]
                          (re-matches #"^\s{4,}([A-Za-z_][A-Za-z0-9_.-]*):\s*(?:.*)$"
                                      line)]
                 (when-not (contains? #{"name" "when" "with_items" "register"}
                                      module-name)
                   [{:kind :ansible-module
                     :label module-name
                     :source-line source-line
                     :relation :references}]))))))
         distinct
         vec)))

(defn- nginx-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ port]
                        (re-matches #"^\s*listen\s+([0-9]+)[^;]*;\s*$" line)]
               [{:kind :ops-port
                 :label port
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ route]
                        (re-matches #"^\s*location\s+([^\s{]+)\s*\{\s*$" line)]
               [{:kind :ops-route
                 :label route
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ target]
                        (re-matches #"^\s*proxy_pass\s+([^;]+);\s*$" line)]
               [{:kind :config-reference
                 :label target
                 :source-line source-line
                 :relation :references}])))))
       distinct
       vec))

(defn- systemd-facts
  [path content]
  (let [unit-name (.getName (java.io.File. (str path)))]
    (->> (str/split-lines content)
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when (= 0 idx)
                 [{:kind :systemd-unit
                   :label unit-name
                   :source-line 1
                   :relation :defines}])
               (when-let [[_ section]
                          (re-matches #"^\[([A-Za-z0-9_.-]+)\]\s*$" line)]
                 [{:kind :systemd-section
                   :label section
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ command]
                          (re-matches #"^\s*Exec(?:Start|Stop|Reload)=\s*(.+?)\s*$"
                                      line)]
                 [{:kind :systemd-command
                   :label command
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ target]
                          (re-matches #"^\s*(?:After|Requires|Wants|WantedBy)=\s*(.+?)\s*$"
                                      line)]
                 [{:kind :systemd-target
                   :label target
                   :source-line source-line
                   :relation :references}])))))
         distinct
         vec)))

(defn- ops-config-facts
  [{:keys [path content] :as file}]
  (case (ops-config-format file)
    :serverless (serverless-facts content)
    :sam (sam-facts content)
    :cdk (cdk-facts content)
    :cloudformation (cloudformation-facts content)
    :pulumi {:facts (pulumi-facts file) :refs []}
    :ansible {:facts (ansible-facts content) :refs []}
    :nginx {:facts (nginx-facts content) :refs []}
    :systemd {:facts (systemd-facts path content) :refs []}
    {:facts (config-facts file) :refs []}))

(defn extract-ops-config
  "Extract bounded operational configuration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [{:keys [facts refs]} (ops-config-facts file)
        config-node (generic-node run-id id-scope file-id path :ops-config path 1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         kind label source-line))
                         facts)
        define-edges (mapv (fn [{:keys [kind label source-line relation]}]
                             (edge-row run-id
                                       file-id
                                       path
                                       (:xt/id config-node)
                                       (node-id id-scope kind label)
                                       relation
                                       :extracted
                                       source-line))
                           facts)
        reference-edges (mapv (fn [{:keys [source-kind source-label target-kind
                                           target-label source-line]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (node-id id-scope source-kind source-label)
                                          (node-id id-scope target-kind target-label)
                                          :references
                                          :extracted
                                          source-line))
                              refs)
        chunk-result (extract-text-source run-id file :ops-config-file)]
    {:nodes (into [config-node] fact-nodes)
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

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
                    (js-definition-line (+ (dec source-line) idx) line))
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

(defn- gettext-messages
  [content]
  (let [lines (vec (str/split-lines content))]
    (->> lines
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ msgid]
                            (re-matches #"^\s*msgid\s+\"(.*)\"\s*$" line)]
                   (when (seq msgid)
                     {:label msgid
                      :source-line (inc idx)}))))
         distinct
         vec)))

(defn extract-gettext
  "Extract gettext message ids as searchable translation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [catalog-node (generic-node run-id id-scope file-id path :gettext-catalog path 1)
        messages (gettext-messages content)
        message-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id
                                            id-scope
                                            file-id
                                            path
                                            :gettext-message
                                            label
                                            source-line))
                            messages)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id catalog-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           message-nodes)
        chunk-result (extract-text-source run-id file :gettext-file)]
    {:nodes (into [catalog-node] message-nodes)
     :edges define-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn extract-svg
  "Extract SVG id-bearing elements as concrete asset facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [asset-node (generic-node run-id id-scope file-id path :svg-file path 1)
        elements (->> (re-seq #"(?is)<([A-Za-z][A-Za-z0-9:_-]*)\b[^>]*\bid=[\"']([^\"']+)[\"'][^>]*>"
                              content)
                      (map-indexed (fn [idx [_ tag id]]
                                     {:kind :svg-element
                                      :label (str tag "#" id)
                                      :source-line (inc idx)}))
                      distinct
                      vec)
        element-nodes (mapv (fn [{:keys [kind label source-line]}]
                              (generic-node run-id id-scope file-id path kind label source-line))
                            elements)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id asset-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           element-nodes)
        chunk-result (extract-text-source run-id file :svg-file)]
    {:nodes (into [asset-node] element-nodes)
     :edges define-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn extract-binary-asset
  "Extract metadata for supported binary assets without reading text chunks."
  [run-id {:keys [id-scope file-id path kind size-bytes content-sha]}]
  (let [asset-node (cond-> (generic-node run-id id-scope file-id path kind path 1)
                     size-bytes (assoc :size-bytes size-bytes)
                     content-sha (assoc :content-sha content-sha))]
    {:nodes [asset-node]
     :edges []
     :chunks []
     :diagnostics []}))

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

(defn- compose-section-values
  [block section-name value-mode]
  (let [service-indent (some-> (:lines block) first second leading-spaces)]
    (loop [remaining (:lines block)
           section-indent nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [entry (yaml-key-line idx line)
              indent (leading-spaces line)
              section-indent* (cond
                                (and entry
                                     (= (+ service-indent 2) (:indent entry))
                                     (= section-name (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and section-indent
                                     entry
                                     (<= (:indent entry) section-indent))
                                nil

                                :else section-indent)
              inline-values (when (and entry
                                       (= (+ service-indent 2) (:indent entry))
                                       (= section-name (:key entry))
                                       (seq (:value entry)))
                              (map (fn [value]
                                     {:value value
                                      :source-line (:source-line entry)})
                                   (yaml-scalar-list-values (:value entry))))
              list-value (when (and section-indent*
                                    (= indent (+ section-indent* 2)))
                           (when-let [[_ value]
                                      (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                             (let [value (strip-yaml-scalar value)]
                               {:value (case value-mode
                                         :env-key (or (second (re-matches #"([^=\s]+)=.*" value))
                                                      value)
                                         value)
                                :source-line (inc idx)})))
              map-value (when (and section-indent*
                                   (= indent (+ section-indent* 2))
                                   entry)
                          (case value-mode
                            :env-key {:value (:key entry)
                                      :source-line (:source-line entry)}
                            :map-key {:value (:key entry)
                                      :source-line (:source-line entry)}
                            (when (seq (:value entry))
                              {:value (strip-yaml-scalar (:value entry))
                               :source-line (:source-line entry)})))]
          (recur (rest remaining)
                 section-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj list-value)
                   map-value (conj map-value))))
        (vec (distinct out))))))

(defn- compose-services
  [lines]
  (->> (yaml-top-section-blocks lines "services")
       (mapv (fn [{:keys [label source-line] :as block}]
               (let [values (block-key-values block)]
                 {:label label
                  :source-line source-line
                  :image (get values "image")
                  :build (get values "build")
                  :container-name (get values "container_name")
                  :depends-on (compose-section-values block "depends_on" :map-key)
                  :ports (compose-section-values block "ports" :scalar)
                  :volumes (compose-section-values block "volumes" :scalar)
                  :networks (compose-section-values block "networks" :map-key)
                  :environment (compose-section-values block
                                                       "environment"
                                                       :env-key)})))))

(defn- compose-service-references
  [services]
  (let [service-labels (set (map :label services))]
    (->> services
         (mapcat (fn [{:keys [label source-line] :as service}]
                   (concat
                    (keep (fn [[kind target]]
                            (when (seq target)
                              {:source label
                               :target target
                               :kind kind
                               :source-line source-line
                               :relation (if (contains? service-labels target)
                                           :requires
                                           :uses)}))
                          [[:container-image (:image service)]
                           [:build-reference (:build service)]])
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :compose-service
                            :source-line source-line
                            :relation :requires})
                         (:depends-on service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :container-port
                            :source-line source-line
                            :relation :defines})
                         (:ports service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :runtime-volume
                            :source-line source-line
                            :relation :references})
                         (:volumes service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :compose-network
                            :source-line source-line
                            :relation :uses})
                         (:networks service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target (str label ":" value)
                            :kind :runtime-env-var
                            :source-line source-line
                            :relation :defines})
                         (:environment service)))))
         distinct
         vec)))

(def docker-command-instructions
  #{"RUN" "CMD" "ENTRYPOINT" "HEALTHCHECK"})

(defn- docker-strip-flags
  [value]
  (->> (str/split (str/trim (or value "")) #"\s+")
       (drop-while #(str/starts-with? % "--"))
       (str/join " ")
       str/trim))

(defn- docker-env-keys
  [value]
  (let [value (str/trim (or value ""))]
    (cond
      (str/blank? value) []
      (str/includes? value "=") (->> (str/split value #"\s+")
                                     (keep #(some-> (re-matches #"([^=\s]+)=.*" %)
                                                    second))
                                     distinct
                                     vec)
      :else [(first (str/split value #"\s+"))])))

(defn- docker-label-keys
  [value]
  (->> (str/split (str/trim (or value "")) #"\s+")
       (keep #(some-> (re-matches #"([^=\s]+)=.*" %) second))
       distinct
       vec))

(defn- docker-copy-sources
  [value]
  (let [tokens (->> (str/split (docker-strip-flags value) #"\s+")
                    (remove str/blank?)
                    vec)]
    (if (< 1 (count tokens))
      (subvec tokens 0 (dec (count tokens)))
      [])))

(defn- docker-stage-label
  [idx image stage]
  (or (some-> stage str/trim not-empty)
      (str "stage-" (inc idx) ":" image)))

(defn- docker-instruction-rows
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ instruction value]
                              (re-matches #"(?i)^([A-Z]+)\s+(.+?)\s*$" trimmed)]
                     {:instruction (str/upper-case instruction)
                      :value (str/trim value)
                      :source-line (inc idx)
                      :idx idx})))))
       vec))

(defn- docker-stage-records
  [rows]
  (loop [remaining rows
         current nil
         out []]
    (if-let [{:keys [instruction value] :as row} (first remaining)]
      (if (= "FROM" instruction)
        (let [[_ image stage] (re-matches #"(?i)^([^\s]+)(?:\s+AS\s+([A-Za-z0-9_.-]+))?.*$"
                                          value)
              stage-record (assoc row
                                  :image image
                                  :label (docker-stage-label (count out)
                                                             image
                                                             stage))]
          (recur (rest remaining)
                 (assoc stage-record :lines [row])
                 (cond-> out current (conj current))))
        (recur (rest remaining)
               (when current
                 (update current :lines conj row))
               out))
      (cond-> out current (conj current)))))

(defn- docker-facts
  [content]
  (let [rows (docker-instruction-rows content)
        stages (docker-stage-records rows)
        stage-labels (set (map :label stages))]
    (->> stages
         (mapcat
          (fn [{stage-label :label image :image source-line :source-line lines :lines}]
            (concat
             [{:kind :docker-stage
               :label stage-label
               :source-line source-line
               :relation :defines}]
             (when-not (contains? stage-labels image)
               [{:kind :container-image
                 :label image
                 :source-line source-line
                 :relation :uses
                 :stage-label stage-label}])
             (mapcat
              (fn [{:keys [instruction value source-line]}]
                (case instruction
                  "FROM" []
                  "WORKDIR" [{:kind :docker-workdir
                              :label value
                              :source-line source-line
                              :relation :defines
                              :stage-label stage-label}]
                  "EXPOSE" (mapv (fn [port]
                                   {:kind :container-port
                                    :label port
                                    :source-line source-line
                                    :relation :defines
                                    :stage-label stage-label})
                                 (yaml-scalar-list-values value))
                  "ENV" (mapv (fn [env-key]
                                {:kind :runtime-env-var
                                 :label (str stage-label ":" env-key)
                                 :source-line source-line
                                 :relation :defines
                                 :stage-label stage-label})
                              (docker-env-keys value))
                  "ARG" (mapv (fn [arg-key]
                                {:kind :docker-build-arg
                                 :label arg-key
                                 :source-line source-line
                                 :relation :defines
                                 :stage-label stage-label})
                              (docker-env-keys value))
                  "LABEL" (mapv (fn [label-key]
                                  {:kind :docker-label
                                   :label label-key
                                   :source-line source-line
                                   :relation :defines
                                   :stage-label stage-label})
                                (docker-label-keys value))
                  ("COPY" "ADD") (mapv (fn [source]
                                         {:kind :docker-copy-source
                                          :label source
                                          :source-line source-line
                                          :relation :references
                                          :stage-label stage-label})
                                       (docker-copy-sources value))
                  "VOLUME" [{:kind :runtime-volume
                             :label value
                             :source-line source-line
                             :relation :defines
                             :stage-label stage-label}]
                  (when (contains? docker-command-instructions instruction)
                    [{:kind :runtime-command
                      :label (str instruction " " value)
                      :source-line source-line
                      :relation :defines
                      :stage-label stage-label}])))
              lines))))
         distinct
         vec)))

(defn- docker-stage-dependencies
  [stages]
  (let [stage-labels (set (map :label stages))]
    (->> stages
         (keep (fn [{:keys [label image source-line]}]
                 (when (contains? stage-labels image)
                   {:source label
                    :target image
                    :source-line source-line})))
         distinct
         vec)))

(defn- docker-stage-chunks
  [run-id id-scope file-id path content stages]
  (let [lines (vec (str/split-lines content))]
    (->> stages
         (map-indexed
          (fn [stage-idx {:keys [label source-line]}]
            (let [start-line source-line
                  next-stage-line (some-> (nth stages (inc stage-idx) nil)
                                          :source-line)
                  end-line (or (some-> next-stage-line dec)
                               (count lines))
                  text-lines (subvec lines
                                     (dec start-line)
                                     end-line)
                  text (str/join "\n" text-lines)]
              {:xt/id (chunk-id id-scope path label start-line)
               :file-id file-id
               :path path
               :kind :docker-stage
               :label label
               :text text
               :tokens (text/tokenize (str label "\n" text))
               :source-line start-line
               :end-line end-line
               :content-sha (hash/sha256-hex text)
               :active? true
               :run-id run-id})))
         vec)))

(defn extract-docker
  "Extract bounded Dockerfile/Containerfile build and runtime facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [root-node (generic-node run-id id-scope file-id path :docker-file path 1)
        stages (docker-stage-records (docker-instruction-rows content))
        facts (docker-facts content)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        stage-edge-source (fn [fact]
                            (if-let [stage-label (:stage-label fact)]
                              (node-id id-scope :docker-stage stage-label)
                              (:xt/id root-node)))
        fact-edges (mapv (fn [{:keys [kind label source-line relation] :as fact}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (if (= :docker-stage kind)
                                       (:xt/id root-node)
                                       (stage-edge-source fact))
                                     (node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        stage-dependency-edges (mapv (fn [{:keys [source target source-line]}]
                                       (edge-row run-id
                                                 file-id
                                                 path
                                                 (node-id id-scope
                                                          :docker-stage
                                                          source)
                                                 (node-id id-scope
                                                          :docker-stage
                                                          target)
                                                 :depends-on
                                                 :extracted
                                                 source-line))
                                     (docker-stage-dependencies stages))
        file-chunk (:chunks (extract-text-source run-id file :docker-file))]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct (concat fact-edges stage-dependency-edges)))
     :chunks (vec (concat file-chunk
                          (docker-stage-chunks run-id
                                               id-scope
                                               file-id
                                               path
                                               content
                                               stages)))
     :diagnostics []}))

(defn- procfile-processes
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ process command]
                              (re-matches #"^([A-Za-z0-9_.-]+):\s*(.+?)\s*$"
                                          trimmed)]
                     {:label process
                      :command command
                      :source-line (inc idx)})))))
       vec))

(defn extract-procfile
  "Extract explicit Procfile process declarations and commands."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [root-node (generic-node run-id id-scope file-id path :procfile path 1)
        processes (procfile-processes content)
        process-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id
                                            id-scope
                                            file-id
                                            path
                                            :runtime-process
                                            label
                                            source-line))
                            processes)
        command-nodes (mapv (fn [{:keys [label command source-line]}]
                              (generic-node run-id
                                            id-scope
                                            file-id
                                            path
                                            :runtime-command
                                            (str label ":" command)
                                            source-line))
                            processes)
        process-edges (mapv #(edge-row run-id
                                       file-id
                                       path
                                       (:xt/id root-node)
                                       (:xt/id %)
                                       :defines
                                       :extracted
                                       (:source-line %))
                            process-nodes)
        command-edges (mapv (fn [{:keys [label command source-line]}]
                              (edge-row run-id
                                        file-id
                                        path
                                        (node-id id-scope :runtime-process label)
                                        (node-id id-scope
                                                 :runtime-command
                                                 (str label ":" command))
                                        :defines
                                        :extracted
                                        source-line))
                            processes)
        file-chunk (:chunks (extract-text-source run-id file :procfile))
        process-chunks (mapv (fn [{:keys [label command source-line]}]
                               {:xt/id (chunk-id id-scope path label source-line)
                                :file-id file-id
                                :path path
                                :kind :runtime-process
                                :label label
                                :text command
                                :tokens (text/tokenize (str label "\n" command))
                                :source-line source-line
                                :content-sha (hash/sha256-hex command)
                                :active? true
                                :run-id run-id})
                             processes)]
    {:nodes (vec (concat [root-node] process-nodes command-nodes))
     :edges (vec (concat process-edges command-edges))
     :chunks (vec (concat file-chunk process-chunks))
     :diagnostics []}))

(defn extract-compose
  "Extract bounded Docker Compose service, image, and build facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        compose-node (generic-node run-id id-scope file-id path :compose-file path 1)
        services (compose-services lines)
        service-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id id-scope file-id path :compose-service label source-line))
                            services)
        reference-facts (compose-service-references services)
        service-labels (set (map :label services))
        reference-node-facts (->> reference-facts
                                  (remove #(and (= :compose-service (:kind %))
                                                (contains? service-labels
                                                           (:target %))))
                                  (reduce (fn [acc {:keys [kind target] :as fact}]
                                            (if (contains? acc [kind target])
                                              acc
                                              (assoc acc [kind target] fact)))
                                          {})
                                  vals)
        reference-nodes (mapv (fn [{:keys [kind target source-line]}]
                                (generic-node run-id id-scope file-id path kind target source-line))
                              reference-node-facts)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id compose-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           service-nodes)
        reference-edges (mapv (fn [{:keys [source target kind source-line relation]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (node-id id-scope :compose-service source)
                                          (node-id id-scope kind target)
                                          relation
                                          :extracted
                                          source-line))
                              reference-facts)
        chunk-result (extract-text-source run-id file :compose-file)]
    {:nodes (vec (concat [compose-node] service-nodes reference-nodes))
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- yaml-documents
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         current []
         out []]
    (if-let [[idx line] (first remaining)]
      (if (re-matches #"^\s*---\s*$" line)
        (recur (rest remaining)
               []
               (cond-> out (seq current) (conj current)))
        (recur (rest remaining)
               (conj current [idx line])
               out))
      (cond-> out (seq current) (conj current)))))

(defn- yaml-doc-value
  [doc key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (strip-yaml-scalar value)
               :source-line source-line})))
        doc))

(defn- yaml-doc-metadata-name
  [doc]
  (loop [remaining doc
         in-metadata? false]
    (if-let [[idx line] (first remaining)]
      (if-let [{:keys [indent key value source-line]} (yaml-key-line idx line)]
        (cond
          (and (= "metadata" key) (= 0 indent))
          (recur (rest remaining) true)

          (and in-metadata? (<= indent 0))
          nil

          (and in-metadata? (= "name" key) (seq value))
          {:value (strip-yaml-scalar value)
           :source-line source-line}

          :else
          (recur (rest remaining) in-metadata?))
        (recur (rest remaining) in-metadata?))
      nil)))

(defn- yaml-doc-metadata-value
  [doc key-name]
  (loop [remaining doc
         in-metadata? false]
    (if-let [[idx line] (first remaining)]
      (if-let [{:keys [indent key value source-line]} (yaml-key-line idx line)]
        (cond
          (and (= "metadata" key) (= 0 indent))
          (recur (rest remaining) true)

          (and in-metadata? (<= indent 0))
          nil

          (and in-metadata? (= key-name key) (seq value))
          {:value (strip-yaml-scalar value)
           :source-line source-line}

          :else
          (recur (rest remaining) in-metadata?))
        (recur (rest remaining) in-metadata?))
      nil)))

(defn- k8s-image-facts
  [doc resource-label]
  (->> doc
       (keep (fn [[idx line]]
               (when-let [[_ image]
                          (re-matches #"^\s*image:\s*['\"]?(.+?)['\"]?\s*$"
                                      line)]
                 {:kind :container-image
                  :label (strip-yaml-scalar image)
                  :source-line (inc idx)
                  :relation :uses
                  :resource-label resource-label})))
       distinct
       vec))

(defn- k8s-resource-facts
  [content]
  (->> (yaml-documents content)
       (mapcat
        (fn [doc]
          (let [doc-text (str/join "\n" (map second doc))
                api-version (yaml-doc-value doc "apiVersion")
                kind (yaml-doc-value doc "kind")
                name (yaml-doc-metadata-name doc)
                namespace (yaml-doc-metadata-value doc "namespace")
                resource-label (when (and (:value kind) (:value name))
                                 (str (:value kind) "/" (:value name)))
                base (when resource-label
                       (concat
                        [{:kind :k8s-resource
                          :label resource-label
                          :source-line (or (:source-line kind) (:source-line name) 1)
                          :resource-kind (:value kind)
                          :resource-name (:value name)}]
                        (when (:value api-version)
                          [{:kind :k8s-api-version
                            :label (str resource-label ":" (:value api-version))
                            :source-line (:source-line api-version)
                            :relation :defines}])
                        (when (:value namespace)
                          [{:kind :k8s-namespace
                            :label (str resource-label ":" (:value namespace))
                            :source-line (:source-line namespace)
                            :relation :defines}])
                        (k8s-image-facts doc resource-label)))
                crd (when (= "CustomResourceDefinition" (:value kind))
                      (concat
                       (when-let [[_ group] (re-find #"(?m)^\s*group:\s*(.+?)\s*$" doc-text)]
                         [{:kind :k8s-crd-group
                           :label (strip-yaml-scalar group)
                           :source-line (:source-line kind 1)
                           :relation :defines}])
                       (when-let [[_ crd-kind] (re-find #"(?m)^\s{4}kind:\s*(.+?)\s*$" doc-text)]
                         [{:kind :k8s-crd-kind
                           :label (strip-yaml-scalar crd-kind)
                           :source-line (:source-line kind 1)
                           :relation :defines}])
                       (->> (re-seq #"(?m)^\s*-\s+name:\s+(.+?)\s*$" doc-text)
                            (map second)
                            (map (fn [version]
                                   {:kind :k8s-crd-version
                                    :label (strip-yaml-scalar version)
                                    :source-line (:source-line kind 1)
                                    :relation :defines})))))
                crossplane? (or (some-> (:value api-version)
                                        (str/includes? "crossplane.io"))
                                (str/includes? doc-text "providerConfigRef:")
                                (str/includes? doc-text "compositionRef:"))
                crossplane (when (and crossplane? resource-label)
                             (concat
                              [{:kind :crossplane-resource
                                :label resource-label
                                :source-line (or (:source-line kind) 1)
                                :relation :defines}]
                              (when-let [[_ provider]
                                         (re-find #"(?m)^\s*providerConfigRef:\s*\n\s*name:\s*(.+?)\s*$"
                                                  doc-text)]
                                [{:kind :crossplane-provider-config
                                  :label (strip-yaml-scalar provider)
                                  :source-line (or (:source-line kind) 1)
                                  :relation :references}])))
                argocd (when (and (= "Application" (:value kind))
                                  (some-> (:value api-version)
                                          (str/includes? "argoproj.io"))
                                  resource-label)
                         (concat
                          [{:kind :argocd-application
                            :label (:value name)
                            :source-line (or (:source-line name) 1)
                            :relation :defines}]
                          (->> [["repoURL" :argocd-source]
                                ["path" :argocd-source-path]
                                ["chart" :argocd-source-chart]
                                ["server" :argocd-destination]
                                ["namespace" :argocd-destination]]
                               (keep (fn [[key kind]]
                                       (when-let [[_ value]
                                                  (re-find (re-pattern
                                                            (str "(?m)^\\s*"
                                                                 key
                                                                 ":\\s*(.+?)\\s*$"))
                                                           doc-text)]
                                         {:kind kind
                                          :label (strip-yaml-scalar value)
                                          :source-line (or (:source-line name) 1)
                                          :relation :references}))))))]
            (concat base crd crossplane argocd))))
       distinct
       vec))

(defn- workflow-source-import-targets
  [path content]
  (let [lines (str/split-lines content)
        js? (contains? #{".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts"}
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
  (let [api-version (some-> (yaml-doc-value doc "apiVersion") :value)
        kind (some-> (yaml-doc-value doc "kind") :value)]
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
  (let [kind (some-> (yaml-doc-value doc "kind") :value)
        name (some-> (yaml-doc-metadata-name doc) :value)]
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
  (->> (yaml-documents content)
       (mapcat
        (fn [doc]
          (let [framework (workflow-doc-framework doc)
                resource-label (workflow-doc-resource-label doc)
                kind-value (some-> (yaml-doc-value doc "kind") :value)
                kind-line (or (some-> (yaml-doc-value doc "kind") :source-line) 1)]
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
       (".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts") (workflow-js-facts content)
       [])))))

(defn- workflow-facts
  [{:keys [path] :as file}]
  (if (contains? #{".py" ".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts"}
                 (fs/extension path))
    (workflow-source-facts file)
    (workflow-config-facts file)))

(defn- workflow-base-result
  [run-id {:keys [path] :as file}]
  (case (fs/extension path)
    ".py" (extract-python run-id (assoc file :kind :python))
    (".ts" ".tsx" ".mts") (extract-js-family run-id (assoc file :kind :typescript))
    (".js" ".jsx" ".mjs" ".cjs") (extract-js-family run-id (assoc file :kind :javascript))
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

(defn- data-science-block-list-values
  [block key-name]
  (let [list-entry-value (fn [value]
                           (let [value (strip-yaml-scalar value)]
                             (or (some->> (re-matches #"^(?:path|name):\s*(.+?)\s*$"
                                                      value)
                                          second
                                          strip-yaml-scalar)
                                 value)))]
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
                                    list-entry-value))
              map-value (when (and list-indent*
                                   entry
                                   (> (:indent entry) list-indent*))
                          (if (and (= "path" (:key entry)) (seq (:value entry)))
                            (strip-yaml-scalar (:value entry))
                            (:key entry)))]
          (recur (rest remaining)
                 list-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj {:value list-value
                                     :source-line (inc idx)})
                   map-value (conj {:value map-value
                                    :source-line (:source-line entry)}))))
        (vec (distinct out))))))

(defn- data-science-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))

(defn- dvc-stage-facts
  [content]
  (->> (yaml-top-section-blocks (str/split-lines content) "stages")
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [{:kind :ml-pipeline-stage
             :label label
             :source-line source-line
             :relation :defines}]
           (when-let [{cmd :value cmd-line :source-line}
                      (data-science-block-scalar block "cmd")]
             [{:kind :pipeline-command
               :label (str label ":" cmd)
               :source-line cmd-line
               :relation :uses
               :source-kind :ml-pipeline-stage
               :source label}])
           (->> (data-science-block-list-values block "deps")
                (map (fn [{:keys [value source-line]}]
                       {:kind :data-artifact
                        :label value
                        :source-line source-line
                        :relation :uses
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "outs")
                (map (fn [{:keys [value source-line]}]
                       {:kind :data-artifact
                        :label value
                        :source-line source-line
                        :relation :produces
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "metrics")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-metric
                        :label value
                        :source-line source-line
                        :relation :produces
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "params")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-parameter
                        :label value
                        :source-line source-line
                        :relation :uses
                        :source-kind :ml-pipeline-stage
                        :source label}))))))
       distinct
       vec))

(defn- dvc-file-facts
  [content path]
  (let [stage-facts (dvc-stage-facts content)
        outs (data-science-block-list-values {:lines (map-indexed vector
                                                                  (str/split-lines content))}
                                             "outs")]
    (vec
     (concat
      [{:kind :data-science-framework
        :label "dvc"
        :source-line 1
        :relation :uses}]
      (if (seq stage-facts)
        stage-facts
        (concat
         [{:kind :data-version-file
           :label path
           :source-line 1
           :relation :defines}]
         (map (fn [{:keys [value source-line]}]
                {:kind :data-artifact
                 :label value
                 :source-line source-line
                 :relation :tracks
                 :source-kind :data-version-file
                 :source path})
              outs)))))))

(defn- mlproject-entry-point-facts
  [content]
  (->> (yaml-top-section-blocks (str/split-lines content) "entry_points")
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [{:kind :mlflow-entry-point
             :label label
             :source-line source-line
             :relation :defines}]
           (when-let [{command :value command-line :source-line}
                      (data-science-block-scalar block "command")]
             [{:kind :pipeline-command
               :label (str label ":" command)
               :source-line command-line
               :relation :uses
               :source-kind :mlflow-entry-point
               :source label}])
           (->> (data-science-block-list-values block "parameters")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-parameter
                        :label (str label ":" value)
                        :source-line source-line
                        :relation :uses
                        :source-kind :mlflow-entry-point
                        :source label}))))))
       distinct
       vec))

(defn- mlproject-facts
  [content]
  (vec
   (concat
    [{:kind :data-science-framework
      :label "mlflow"
      :source-line 1
      :relation :uses}]
    (when-let [project-name (yaml-top-level-value content "name")]
      [{:kind :mlflow-project
        :label project-name
        :source-line 1
        :relation :defines}])
    (when-let [conda-env (yaml-top-level-value content "conda_env")]
      [{:kind :ml-environment
        :label conda-env
        :source-line 1
        :relation :uses}])
    (when-let [docker-image (yaml-top-level-value content "image")]
      [{:kind :container-image
        :label docker-image
        :source-line 1
        :relation :uses}])
    (mlproject-entry-point-facts content))))

(defn- data-science-top-level-scalar
  [content key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [indent key value source-line]} (yaml-key-line idx line)]
            (when (and (zero? indent) (= key-name key) (seq value))
              {:value (strip-yaml-scalar value)
               :source-line source-line})))
        (map-indexed vector (str/split-lines content))))

(defn- data-science-top-list-values
  [content key-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         nested-list-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            indent (leading-spaces line)
            section-start? (and entry
                                (zero? (:indent entry))
                                (= key-name (:key entry)))
            section-end? (and in-section?
                              entry
                              (<= (:indent entry) section-indent)
                              (not section-start?))
            inline-values (when (and section-start? (seq (:value entry)))
                            (map (fn [value]
                                   {:value value
                                    :source-line (:source-line entry)})
                                 (yaml-scalar-list-values (:value entry))))
            pip-section? (and in-section?
                              (re-matches #"^\s*-\s+pip:\s*$" line))
            list-entry (when (and in-section?
                                  (or (= indent (+ section-indent 2))
                                      (and nested-list-indent
                                           (> indent nested-list-indent))))
                         (some->> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                  second
                                  strip-yaml-scalar))]
        (cond
          section-start?
          (recur (rest remaining)
                 true
                 (:indent entry)
                 nil
                 (into out inline-values))

          section-end?
          (recur remaining false nil nil out)

          pip-section?
          (recur (rest remaining) true section-indent indent out)

          (and list-entry (not (str/ends-with? list-entry ":")))
          (recur (rest remaining)
                 true
                 section-indent
                 nested-list-indent
                 (conj out {:value list-entry
                            :source-line (inc idx)}))

          :else
          (recur (rest remaining) in-section? section-indent nested-list-indent out)))
      (vec (distinct out)))))

(defn- conda-environment-facts
  [content path]
  (let [environment (or (:value (data-science-top-level-scalar content "name"))
                        path)
        environment-line (or (:source-line (data-science-top-level-scalar content "name"))
                             1)]
    (vec
     (concat
      [{:kind :ml-environment
        :label environment
        :source-line environment-line
        :relation :defines}]
      (->> (data-science-top-list-values content "channels")
           (map (fn [{:keys [value source-line]}]
                  {:kind :environment-channel
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :ml-environment
                   :source environment})))
      (->> (data-science-top-list-values content "dependencies")
           (map (fn [{:keys [value source-line]}]
                  {:kind :environment-dependency
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :ml-environment
                   :source environment})))))))

(defn- data-science-front-matter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (first lines))
      (let [end-idx (->> (map-indexed vector (rest lines))
                         (some (fn [[idx line]]
                                 (when (= "---" line)
                                   (inc idx)))))]
        (when end-idx
          {:lines (subvec lines 1 end-idx)
           :content (str/join "\n" (subvec lines 1 end-idx))})))))

(defn- data-card-kind
  [front-matter-content]
  (cond
    (or (data-science-top-level-scalar front-matter-content "model_name")
        (data-science-top-level-scalar front-matter-content "model-name")
        (data-science-top-level-scalar front-matter-content "model_id")
        (data-science-top-level-scalar front-matter-content "model-id")
        (re-find #"(?m)^model[_-](?:index|details):\s*$" front-matter-content))
    :model-card

    (or (data-science-top-level-scalar front-matter-content "dataset_name")
        (data-science-top-level-scalar front-matter-content "dataset-name")
        (re-find #"(?m)^datasets?:\s*(?:.+)?$" front-matter-content))
    :data-card

    :else nil))

(defn- card-metadata-facts
  [card-kind card-label front-matter-content]
  (let [source-kind card-kind
        metadata-kind (case card-kind
                        :model-card :model-metadata
                        :data-card :data-metadata)]
    (->> (map-indexed vector (str/split-lines front-matter-content))
         (keep (fn [[idx line]]
                 (when-let [{:keys [indent key value]} (yaml-key-line idx line)]
                   (when (and (zero? indent) (seq value))
                     {:kind metadata-kind
                      :label (str key ":" (strip-yaml-scalar value))
                      :source-line (+ 2 idx)
                      :relation :defines
                      :source-kind source-kind
                      :source card-label}))))
         distinct
         vec)))

(defn- data-card-dataset-facts
  [card-kind card-label front-matter-content]
  (let [scalar-keys ["dataset" "datasets" "dataset_name" "dataset-name"]]
    (vec
     (distinct
      (concat
       (->> scalar-keys
            (keep #(data-science-top-level-scalar front-matter-content %))
            (map (fn [{:keys [value source-line]}]
                   {:kind :data-artifact
                    :label value
                    :source-line (inc source-line)
                    :relation :references
                    :source-kind card-kind
                    :source card-label})))
       (->> (data-science-top-list-values front-matter-content "datasets")
            (map (fn [{:keys [value source-line]}]
                   {:kind :data-artifact
                    :label value
                    :source-line (inc source-line)
                    :relation :references
                    :source-kind card-kind
                    :source card-label}))))))))

(defn- data-card-facts
  [content path]
  (if-let [{front-matter-content :content} (data-science-front-matter content)]
    (if-let [card-kind (data-card-kind front-matter-content)]
      (let [model-entry (or (data-science-top-level-scalar front-matter-content "model_name")
                            (data-science-top-level-scalar front-matter-content "model-name")
                            (data-science-top-level-scalar front-matter-content "model_id")
                            (data-science-top-level-scalar front-matter-content "model-id"))
            model-name (:value model-entry)]
        (vec
         (concat
          [{:kind card-kind
            :label path
            :source-line 1
            :relation :defines}]
          (when (seq model-name)
            [{:kind :ml-model
              :label model-name
              :source-line (inc (:source-line model-entry))
              :relation :defines
              :source-kind card-kind
              :source path}])
          (data-card-dataset-facts card-kind path front-matter-content)
          (card-metadata-facts card-kind path front-matter-content))))
      [])
    []))

(defn- data-science-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (case filename
      ("dvc.yaml" "dvc.yml" "dvc.lock") (dvc-file-facts content path)
      "mlproject" (mlproject-facts content)
      (cond
        (= ".dvc" (fs/extension path))
        (dvc-file-facts content path)

        (and (re-find #"(?m)^channels:\s*$" content)
             (re-find #"(?m)^dependencies:\s*$" content))
        (conda-environment-facts content path)

        (= ".md" (fs/extension path))
        (data-card-facts content path)

        :else
        []))))

(defn- data-science-base-result
  [run-id {:keys [path] :as file}]
  (when (= ".md" (fs/extension path))
    (extract-doc run-id (assoc file :kind :doc))))

(defn- data-science-reference-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source-kind source kind label relation source-line]}]
               (when (and source-kind (seq source) (seq label))
                 (edge-row run-id
                           file-id
                           path
                           (node-id id-scope source-kind source)
                           (node-id id-scope kind label)
                           (or relation :references)
                           :extracted
                           source-line))))
       distinct
       vec))

(defn extract-data-science
  "Extract bounded data-science project facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (data-science-facts file)
        result (extract-format-facts run-id
                                     file
                                     :data-science-file
                                     :data-science-file
                                     facts)
        base-result (data-science-base-result run-id file)
        reference-edges (data-science-reference-edges run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      facts)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result)
                                     (:nodes result))))
       :edges (vec (distinct (concat (:edges base-result)
                                     (:edges result)
                                     reference-edges)))
       :chunks (vec (distinct (concat (:chunks base-result)
                                      (:chunks result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics result)))}
      (update result :edges #(vec (distinct (concat % reference-edges)))))))

(defn- obs-section-blocks
  [content section-name]
  (yaml-top-section-blocks (str/split-lines content) section-name))

(defn- obs-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))

(defn- obs-block-list-values
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

(defn- obs-yaml-named-list-blocks
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            section-start? (and entry (= section-name (:key entry)))
            top-exit? (and in-section?
                           entry
                           (<= (:indent entry) section-indent)
                           (not= section-name (:key entry)))
            name-start (when (and in-section?
                                  (re-matches #"^\s*-\s*name:\s*.+$" line))
                         {:label (strip-yaml-scalar
                                  (second (re-matches #"^\s*-\s*name:\s*(.+?)\s*$"
                                                      line)))
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

(defn- obs-fact
  [kind label source-line relation]
  {:kind kind
   :label label
   :source-line (or source-line 1)
   :relation relation})

(defn- otel-pipeline-blocks
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-service? false
         pipelines-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            in-service* (cond
                          (and entry (zero? (:indent entry)) (= "service" (:key entry)))
                          true

                          (and in-service? entry (zero? (:indent entry)))
                          false

                          :else in-service?)
            pipelines-indent* (cond
                                (and in-service*
                                     entry
                                     (= "pipelines" (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and pipelines-indent
                                     entry
                                     (<= (:indent entry) pipelines-indent))
                                nil

                                :else pipelines-indent)
            pipeline-start (when (and pipelines-indent*
                                      entry
                                      (= (:indent entry) (+ pipelines-indent* 2)))
                             {:label (:key entry)
                              :source-line (:source-line entry)
                              :lines [[idx line]]})]
        (cond
          pipeline-start
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 pipeline-start
                 (cond-> out current (conj current)))

          (and pipelines-indent* current)
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 current
                 out)))
      (cond-> out current (conj current)))))

(defn- otel-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "otel-collector" 1 :uses)]
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-receiver label source-line :defines))
          (obs-section-blocks content "receivers"))
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-processor label source-line :defines))
          (obs-section-blocks content "processors"))
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-exporter label source-line :defines))
          (obs-section-blocks content "exporters"))
     (mapcat
      (fn [{:keys [label source-line] :as block}]
        (concat
         [(obs-fact :otel-pipeline label source-line :defines)]
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-receiver
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "receivers"))
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-processor
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "processors"))
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-exporter
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "exporters"))))
      (otel-pipeline-blocks content))))))

(defn- prometheus-scrape-facts
  [content]
  (let [blocks (loop [remaining (map-indexed vector (str/split-lines content))
                      in-section? false
                      section-indent nil
                      current nil
                      out []]
                 (if-let [[idx line] (first remaining)]
                   (let [entry (yaml-key-line idx line)
                         section-start? (and entry (= "scrape_configs" (:key entry)))
                         top-exit? (and in-section?
                                        entry
                                        (<= (:indent entry) section-indent)
                                        (not= "scrape_configs" (:key entry)))
                         job-start (when (and in-section?
                                              (re-matches #"^\s*-\s*job_name:\s*.+$" line))
                                     {:label (strip-yaml-scalar
                                              (second (re-matches #"^\s*-\s*job_name:\s*(.+?)\s*$"
                                                                  line)))
                                      :source-line (inc idx)
                                      :lines [[idx line]]})]
                     (cond
                       section-start?
                       (recur (rest remaining) true (:indent entry) nil out)

                       top-exit?
                       (recur (rest remaining) false nil nil (cond-> out current (conj current)))

                       job-start
                       (recur (rest remaining)
                              true
                              section-indent
                              job-start
                              (cond-> out current (conj current)))

                       (and in-section? current)
                       (recur (rest remaining)
                              true
                              section-indent
                              (update current :lines conj [idx line])
                              out)

                       :else
                       (recur (rest remaining) in-section? section-indent current out)))
                   (cond-> out current (conj current))))]
    (mapcat
     (fn [{:keys [label source-line] :as block}]
       (concat
        [(obs-fact :prometheus-scrape-job label source-line :defines)]
        (when-let [{metrics-path :value line :source-line}
                   (obs-block-scalar block "metrics_path")]
          [(obs-fact :prometheus-metrics-path
                     (str label ":" metrics-path)
                     line
                     :references)])
        (map (fn [{:keys [value source-line]}]
               {:kind :prometheus-target
                :label (str label ":" value)
                :source-line source-line
                :relation :scrapes
                :source-kind :prometheus-scrape-job
                :source label})
             (obs-block-list-values block "targets"))))
     blocks)))

(defn- prometheus-rule-facts
  [content]
  (->> (obs-yaml-named-list-blocks content "groups")
       (mapcat
        (fn [{group-label :label source-line :source-line :as block}]
          (let [rules (->> (:lines block)
                           (keep (fn [[idx line]]
                                   (when-let [[_ alert-name]
                                              (re-matches #"^\s*-\s*alert:\s*(.+?)\s*$"
                                                          line)]
                                     {:kind :prometheus-alert-rule
                                      :label (strip-yaml-scalar alert-name)
                                      :source-line (inc idx)
                                      :relation :defines
                                      :source-kind :prometheus-rule-group
                                      :source group-label}))))]
            (concat [(obs-fact :prometheus-rule-group
                               group-label
                               source-line
                               :defines)]
                    rules))))))

(defn- alertmanager-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "alertmanager" 1 :uses)]
     (map (fn [{:keys [label source-line]}]
            (obs-fact :alertmanager-receiver label source-line :defines))
          (obs-yaml-named-list-blocks content "receivers"))
     (when-let [receiver (yaml-top-level-value content "receiver")]
       [(obs-fact :alertmanager-route-receiver receiver 1 :routes)])
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [[_ matcher]
                             (re-matches #"^\s*-\s*matchers?:\s*(.+?)\s*$"
                                         line)]
                    (obs-fact :alertmanager-matcher
                              (strip-yaml-scalar matcher)
                              (inc idx)
                              :defines)))))))))

(defn- grafana-datasource-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "grafana" 1 :uses)]
     (mapcat
      (fn [{:keys [label source-line] :as block}]
        (concat
         [(obs-fact :grafana-datasource label source-line :defines)]
         (when-let [{type :value type-line :source-line}
                    (obs-block-scalar block "type")]
           [(obs-fact :grafana-datasource-type
                      (str label ":" type)
                      type-line
                      :defines)])
         (when-let [{url :value url-line :source-line}
                    (obs-block-scalar block "url")]
           [(obs-fact :observability-endpoint
                      (str label ":" url)
                      url-line
                      :references)])))
      (obs-yaml-named-list-blocks content "datasources"))))))

(defn- grafana-dashboard-facts
  [content path]
  (if-let [m (read-json-map content)]
    (let [title (or (some-> (:title m) json-label) path)
          panels (if (vector? (:panels m)) (:panels m) [])]
      (vec
       (distinct
        (concat
         [(obs-fact :observability-platform "grafana" 1 :uses)
          (obs-fact :grafana-dashboard title 1 :defines)]
         (mapcat
          (fn [panel]
            (let [panel-title (json-label (or (:title panel)
                                              (:id panel)
                                              "panel"))
                  panel-label (str title ":" panel-title)
                  datasource (or (some-> (get-in panel [:datasource :uid]) json-label)
                                 (some-> (get-in panel [:datasource :type]) json-label))]
              (concat
               [(obs-fact :grafana-panel panel-label 1 :defines)]
               (when datasource
                 [{:kind :grafana-datasource
                   :label datasource
                   :source-line 1
                   :relation :references
                   :source-kind :grafana-panel
                   :source panel-label}]))))
          panels)))))
    []))

(defn- vector-toml-section-facts
  [content section-name kind]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ label]
                          (re-matches (re-pattern
                                       (str "^\\[" section-name "\\.([^\\]]+)\\]\\s*$"))
                                      line)]
                 (obs-fact kind label (inc idx) :defines))))
       distinct
       vec))

(defn- vector-yaml-facts
  [content]
  (let [sources (obs-section-blocks content "sources")
        transforms (obs-section-blocks content "transforms")
        sinks (obs-section-blocks content "sinks")]
    (vec
     (distinct
      (concat
       [(obs-fact :observability-platform "vector" 1 :uses)]
       (map (fn [{:keys [label source-line]}]
              (obs-fact :log-source label source-line :defines))
            sources)
       (map (fn [{:keys [label source-line]}]
              (obs-fact :log-transform label source-line :defines))
            transforms)
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [(obs-fact :log-sink label source-line :defines)]
           (map (fn [{:keys [value source-line]}]
                  {:kind :log-source
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :log-sink
                   :source label})
                (obs-block-list-values block "inputs"))))
        sinks))))))

(defn- vector-toml-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "vector" 1 :uses)]
     (vector-toml-section-facts content "sources" :log-source)
     (vector-toml-section-facts content "transforms" :log-transform)
     (vector-toml-section-facts content "sinks" :log-sink)))))

(defn- observability-facts
  [{:keys [path content]}]
  (let [filename (manifest-name path)]
    (vec
     (distinct
      (cond
        (contains? #{"otelcol.yaml" "otelcol.yml" "otel-collector.yaml" "otel-collector.yml"}
                   filename)
        (otel-facts content)

        (contains? #{"prometheus.yml" "prometheus.yaml"} filename)
        (vec (concat [(obs-fact :observability-platform "prometheus" 1 :uses)]
                     (prometheus-scrape-facts content)))

        (contains? #{"alertmanager.yml" "alertmanager.yaml"} filename)
        (alertmanager-facts content)

        (contains? #{"vector.yaml" "vector.yml"} filename)
        (vector-yaml-facts content)

        (= "vector.toml" filename)
        (vector-toml-facts content)

        (and (= ".json" (fs/extension path))
             (re-find #"(?s)\"schemaVersion\"\s*:" content)
             (re-find #"(?s)\"panels\"\s*:" content))
        (grafana-dashboard-facts content path)

        (re-find #"(?m)^datasources:\s*$" content)
        (grafana-datasource-facts content)

        (re-find #"(?m)^groups:\s*$" content)
        (vec (concat [(obs-fact :observability-platform "prometheus" 1 :uses)]
                     (prometheus-rule-facts content)))

        (and (re-find #"(?m)^receivers:\s*$" content)
             (re-find #"(?m)^exporters:\s*$" content)
             (re-find #"(?m)^\s*pipelines:\s*$" content))
        (otel-facts content)

        (and (re-find #"(?m)^sources:\s*$" content)
             (re-find #"(?m)^sinks:\s*$" content))
        (vector-yaml-facts content)

        :else
        [])))))

(defn- observability-reference-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source-kind source kind label relation source-line]}]
               (when (and source-kind (seq source) (seq label))
                 (edge-row run-id
                           file-id
                           path
                           (node-id id-scope source-kind source)
                           (node-id id-scope kind label)
                           (or relation :references)
                           :extracted
                           source-line))))
       distinct
       vec))

(defn extract-observability-config
  "Extract bounded observability and log pipeline configuration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (observability-facts file)
        result (extract-format-facts run-id
                                     file
                                     :observability-file
                                     :observability-file
                                     facts)
        reference-edges (observability-reference-edges run-id
                                                       id-scope
                                                       file-id
                                                       path
                                                       facts)]
    (update result :edges #(vec (distinct (concat % reference-edges))))))

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
        resource-facts (vec (concat (k8s-resource-facts content)
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

(defn extract-helm
  "Extract bounded Helm chart and Kubernetes resource facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [filename (manifest-name path)
        helm-node (generic-node run-id id-scope file-id path :helm-file path 1)
        chart-facts (when (= "chart.yaml" filename)
                      (->> [(when-let [name (yaml-top-level-value content "name")]
                              {:kind :helm-chart
                               :label name
                               :source-line 1
                               :relation :defines})
                            (when-let [version (yaml-top-level-value content "version")]
                              {:kind :helm-chart-version
                               :label version
                               :source-line 1
                               :relation :defines})
                            (when-let [app-version (yaml-top-level-value content "appVersion")]
                              {:kind :helm-app-version
                               :label app-version
                               :source-line 1
                               :relation :defines})]
                           (remove nil?)))
        dependency-facts (when (= "chart.yaml" filename)
                           (->> (yaml-section-items content #{"dependencies"})
                                (filter #(= "dependencies" (:section %)))
                                (map (fn [{:keys [value source-line]}]
                                       {:kind :helm-dependency
                                        :label value
                                        :source-line source-line
                                        :relation :references}))
                                distinct
                                vec))
        value-facts (when (str/starts-with? filename "values.")
                      (->> (str/split-lines content)
                           (map-indexed vector)
                           (keep (fn [[idx line]]
                                   (when-let [[_ key value]
                                              (re-matches #"^\s*(repository|tag|pullPolicy|name):\s*(.+?)\s*$"
                                                          line)]
                                     {:kind :helm-value
                                      :label (str key "=" (strip-yaml-scalar value))
                                      :source-line (inc idx)
                                      :relation :defines})))
                           distinct
                           vec))
        resource-facts (k8s-resource-facts content)
        facts (vec (concat chart-facts dependency-facts value-facts resource-facts))
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id helm-node)
                                     (node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file :helm-file)]
    {:nodes (into [helm-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- ci-workflow-label
  [path lines]
  (or (some (fn [[idx line]]
              (when-let [{:keys [indent key value]} (yaml-key-line idx line)]
                (when (and (zero? indent)
                           (= "name" key)
                           (seq value))
                  (strip-yaml-scalar value))))
            (map-indexed vector lines))
      path))

(defn- ci-github-job-blocks
  [lines]
  (let [jobs-start (some (fn [[idx line]]
                           (when (re-matches #"^\s*jobs:\s*$" line)
                             idx))
                         (map-indexed vector lines))]
    (if-not jobs-start
      []
      (loop [remaining (drop (inc jobs-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [blank-or-comment? (or (str/blank? line)
                                      (str/starts-with? (str/trim line) "#"))
                top-level? (and (not blank-or-comment?)
                                (zero? (leading-spaces line)))
                job-key (when-let [{:keys [indent key source-line]} (yaml-key-line idx line)]
                          (when (= 2 indent)
                            {:label key
                             :source-line source-line
                             :lines []}))]
            (cond
              top-level?
              (cond-> out current (conj current))

              job-key
              (recur (rest remaining)
                     (update job-key :lines conj [idx line])
                     (cond-> out current (conj current)))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out current (conj current)))))))

(def ci-gitlab-reserved-keys
  #{"after_script" "before_script" "cache" "default" "image" "include" "pages"
    "services" "stages" "variables" "workflow"})

(defn- ci-gitlab-job-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            top-key (when (and entry
                               (zero? (:indent entry))
                               (not (contains? ci-gitlab-reserved-keys (:key entry))))
                      {:label (:key entry)
                       :source-line (:source-line entry)
                       :lines []})]
        (cond
          top-key
          (recur (rest remaining)
                 (update top-key :lines conj [idx line])
                 (cond-> out current (conj current)))

          current
          (recur (rest remaining)
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) nil out)))
      (->> (cond-> out current (conj current))
           (filterv (fn [{:keys [lines]}]
                      (some (fn [[_ line]]
                              (re-find #"^\s*(script|stage|needs):(?:\s|$)" line))
                            lines)))))))

(defn- ci-jenkins-stage-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [[_ label] (re-matches #"^\s*stage\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                     line)]
        (recur (rest remaining)
               {:kind :ci-stage
                :label label
                :source-line (inc idx)
                :lines [[idx line]]}
               (cond-> out current (conj current)))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))

(defn- ci-azure-job-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [[_ indent kind label]
               (re-matches #"^(\s*)-\s*(stage|job):\s*(.+?)\s*$" line)]
        (let [job {:kind (case kind
                           "stage" :ci-stage
                           "job" :ci-job)
                   :label (strip-yaml-scalar label)
                   :source-line (inc idx)
                   :indent (count indent)
                   :lines [[idx line]]}]
          (recur (rest remaining)
                 job
                 (cond-> out current (conj current))))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))

(defn- ci-circleci-job-blocks
  [lines]
  (let [jobs-start (some (fn [[idx line]]
                           (when (re-matches #"^jobs:\s*$" line)
                             idx))
                         (map-indexed vector lines))]
    (if-not jobs-start
      []
      (loop [remaining (drop (inc jobs-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [entry (yaml-key-line idx line)
                top-level? (and (seq (str/trim line))
                                (zero? (leading-spaces line)))
                job-entry (when (and entry (= 2 (:indent entry)))
                            {:label (:key entry)
                             :source-line (:source-line entry)
                             :lines [[idx line]]})]
            (cond
              top-level?
              (cond-> out current (conj current))

              job-entry
              (recur (rest remaining)
                     job-entry
                     (cond-> out current (conj current)))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out current (conj current)))))))

(defn- ci-buildkite-step-label
  [step]
  (or (some (fn [[idx line]]
              (when-let [{:keys [key value]} (yaml-key-line idx line)]
                (when (and (= "key" key) (seq value))
                  (strip-yaml-scalar value))))
            (:lines step))
      (:label step)
      (str "step-" (:source-line step))))

(defn- ci-buildkite-step-blocks
  [lines]
  (let [steps-start (some (fn [[idx line]]
                            (when (re-matches #"^\s*steps:\s*$" line)
                              idx))
                          (map-indexed vector lines))]
    (if-not steps-start
      []
      (loop [remaining (drop (inc steps-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [top-level? (and (seq (str/trim line))
                                (zero? (leading-spaces line)))
                step-start (or
                            (when-let [[_ label]
                                       (re-matches #"^\s*-\s+label:\s+(.+?)\s*$"
                                                   line)]
                              {:label (strip-yaml-scalar label)
                               :source-line (inc idx)
                               :lines [[idx line]]})
                            (when-let [[_ command]
                                       (re-matches #"^\s*-\s+command:\s+(.+?)\s*$"
                                                   line)]
                              {:label (str "command:" (strip-yaml-scalar command))
                               :source-line (inc idx)
                               :lines [[idx line]]}))]
            (cond
              top-level?
              (cond-> out current (conj (assoc current
                                               :label
                                               (ci-buildkite-step-label current))))

              step-start
              (recur (rest remaining)
                     step-start
                     (cond-> out
                       current (conj (assoc current
                                            :label
                                            (ci-buildkite-step-label current)))))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out
            current (conj (assoc current
                                 :label
                                 (ci-buildkite-step-label current)))))))))

(defn- ci-list-step-label
  [step]
  (or (some (fn [[idx line]]
              (when-let [{:keys [key value]} (yaml-key-line idx line)]
                (when (and (= "name" key) (seq value))
                  (strip-yaml-scalar value))))
            (:lines step))
      (:label step)
      (str "step-" (:source-line step))))

(defn- ci-list-step-blocks
  [lines]
  (let [steps-start (some (fn [[idx line]]
                            (when (re-matches #"^\s*steps:\s*$" line)
                              idx))
                          (map-indexed vector lines))]
    (if-not steps-start
      []
      (loop [remaining (drop (inc steps-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [top-level? (and (seq (str/trim line))
                                (zero? (leading-spaces line)))
                step-start (when-let [[_ label]
                                      (re-matches #"^\s*-\s+name:\s+(.+?)\s*$"
                                                  line)]
                             {:label (strip-yaml-scalar label)
                              :source-line (inc idx)
                              :lines [[idx line]]})]
            (cond
              top-level?
              (cond-> out current (conj (assoc current
                                               :label
                                               (ci-list-step-label current))))

              step-start
              (recur (rest remaining)
                     step-start
                     (cond-> out
                       current (conj (assoc current
                                            :label
                                            (ci-list-step-label current)))))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out
            current (conj (assoc current
                                 :label
                                 (ci-list-step-label current)))))))))

(defn- ci-woodpecker-step-blocks
  [lines]
  (let [mapped-steps (yaml-top-section-blocks lines "steps")]
    (if (seq mapped-steps)
      mapped-steps
      (ci-list-step-blocks lines))))

(defn- ci-config-kind
  [path lines]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))
        path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (= "jenkinsfile" filename) :jenkins
      (contains? #{"azure-pipelines.yml" "azure-pipelines.yaml"} filename) :azure
      (re-find #"(^|/)\.circleci/config\.ya?ml$" path-lower) :circleci
      (or (re-find #"(^|/)\.buildkite/pipeline\.ya?ml$" path-lower)
          (contains? #{"buildkite.yml" "buildkite.yaml"} filename)) :buildkite
      (contains? #{".drone.yml" ".drone.yaml"} filename) :drone
      (contains? #{".woodpecker.yml" ".woodpecker.yaml"} filename) :woodpecker
      (re-find #"(^|/)\.github/workflows/[^/]+\.ya?ml$" path-lower) :github
      (contains? #{".gitlab-ci.yml" ".gitlab-ci.yaml"} filename) :gitlab
      (seq (ci-github-job-blocks lines)) :github
      :else :gitlab)))

(defn- ci-job-kind
  [job]
  (or (:kind job) :ci-job))

(defn- ci-job-blocks
  [config-kind lines]
  (case config-kind
    :jenkins (ci-jenkins-stage-blocks lines)
    :azure (ci-azure-job-blocks lines)
    :circleci (ci-circleci-job-blocks lines)
    :buildkite (ci-buildkite-step-blocks lines)
    :drone (ci-list-step-blocks lines)
    :woodpecker (ci-woodpecker-step-blocks lines)
    :github (ci-github-job-blocks lines)
    :gitlab (ci-gitlab-job-blocks lines)
    (let [github-jobs (ci-github-job-blocks lines)]
      (if (seq github-jobs)
        github-jobs
        (ci-gitlab-job-blocks lines)))))

(defn- ci-job-needs
  [job]
  (->> (:lines job)
       (mapcat (fn [[idx line]]
                 (if-let [[_ value] (re-matches #"^\s*(?:needs|dependsOn|depends_on):\s*(.+?)\s*$"
                                                line)]
                   (map (fn [target]
                          {:target target
                           :source-line (inc idx)})
                        (yaml-scalar-list-values value))
                   [])))
       (remove #(= (:target %) (:label job)))
       distinct
       vec))

(defn- ci-job-declared-facts
  [{:keys [label lines]}]
  (loop [remaining lines
         env-indent nil
         artifact-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            jenkins-fact (or
                          (when-let [[_ runner] (re-matches #"^\s*agent\s+([A-Za-z0-9_.-]+)\s*$"
                                                            line)]
                            {:kind :ci-runner
                             :label runner
                             :source-line (inc idx)})
                          (when-let [[_ runner] (re-find #"\blabel\s+['\"]([^'\"]+)['\"]"
                                                         line)]
                            {:kind :ci-runner
                             :label runner
                             :source-line (inc idx)})
                          (when-let [[_ key] (re-matches #"^\s*([A-Z_][A-Z0-9_]*)\s*=\s*['\"].*['\"]\s*$"
                                                         line)]
                            {:kind :ci-env-var
                             :label (str label ":" key)
                             :source-line (inc idx)})
                          (when-let [[_ tool value] (re-matches #"^\s*(jdk|maven|gradle|nodejs)\s+['\"]([^'\"]+)['\"].*$"
                                                                line)]
                            {:kind :ci-tool
                             :label (str tool ":" value)
                             :source-line (inc idx)})
                          (when-let [[_ artifacts] (re-matches #"^\s*archiveArtifacts\s+artifacts:\s*['\"]([^'\"]+)['\"].*$"
                                                               line)]
                            {:kind :ci-artifact
                             :label (str label ":" artifacts)
                             :source-line (inc idx)}))
            artifact-fact (when (and artifact-indent
                                     (> (leading-spaces line) artifact-indent))
                            (when-let [[_ artifact]
                                       (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                              {:kind :ci-artifact
                               :label (strip-yaml-scalar artifact)
                               :source-line (inc idx)}))
            env-indent* (when (and env-indent
                                   (or (nil? entry)
                                       (> (:indent entry) env-indent)))
                          env-indent)
            artifact-indent* (when (and artifact-indent
                                        (or (nil? entry)
                                            (> (:indent entry) artifact-indent)))
                               artifact-indent)
            fact (when entry
                   (let [{:keys [indent key value source-line]} entry
                         value (strip-yaml-scalar value)]
                     (cond
                       (and env-indent* (> indent env-indent*) (seq value))
                       {:kind :ci-env-var
                        :label (str label ":" key)
                        :source-line source-line}

                       (and (contains? #{"env" "variables" "environment"} key)
                            (str/blank? value))
                       nil

                       (and (= "runs-on" key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (= "vmImage" key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (contains? #{"container" "image"} key) (seq value))
                       {:kind :container-image
                        :label value
                        :source-line source-line}

                       (and (contains? #{"executor" "queue"} key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (contains? #{"uses" "task"} key) (seq value))
                       {:kind :ci-action
                        :label value
                        :source-line source-line}

                       (and (= "checkout" key) (seq value))
                       {:kind :ci-action
                        :label (str "checkout:" value)
                        :source-line source-line}

                       (and (contains? #{"artifacts" "store_artifacts"
                                         "artifact_paths"}
                                       key)
                            (str/blank? value))
                       {:kind :ci-artifact
                        :label (str label ":artifacts")
                        :source-line source-line}

                       (and (contains? #{"artifact" "publish" "path"
                                         "artifact_paths"}
                                       key)
                            (seq value))
                       {:kind :ci-artifact
                        :label value
                        :source-line source-line}

                       (and (contains? #{"cache" "restore_cache" "save_cache"} key)
                            (str/blank? value))
                       {:kind :ci-cache
                        :label (str label ":cache")
                        :source-line source-line}

                       (and (= "working-directory" key) (seq value))
                       {:kind :ci-working-directory
                        :label value
                        :source-line source-line})))
            env-indent-next (cond
                              (and entry
                                   (contains? #{"env" "variables" "environment"}
                                              (:key entry))
                                   (str/blank? (:value entry)))
                              (:indent entry)

                              (and env-indent entry (<= (:indent entry) env-indent))
                              nil

                              :else env-indent*)
            artifact-indent-next (cond
                                   (and entry
                                        (contains? #{"artifact_paths"} (:key entry))
                                        (str/blank? (:value entry)))
                                   (:indent entry)

                                   (and artifact-indent
                                        entry
                                        (<= (:indent entry) artifact-indent))
                                   nil

                                   :else artifact-indent*)]
        (recur (rest remaining)
               env-indent-next
               artifact-indent-next
               (cond-> out
                 jenkins-fact (conj jenkins-fact)
                 artifact-fact (conj artifact-fact)
                 (re-matches #"^\s*-\s+checkout\s*$" line)
                 (conj {:kind :ci-action
                        :label "checkout"
                        :source-line (inc idx)})
                 (re-matches #"^\s*-\s+[A-Za-z0-9_.-]+#[A-Za-z0-9_.-]+:\s*$" line)
                 (conj (let [[_ plugin]
                             (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+#[A-Za-z0-9_.-]+):\s*$"
                                         line)]
                         {:kind :ci-plugin
                          :label plugin
                          :source-line (inc idx)}))
                 fact (conj fact))))
      (->> out distinct vec))))

(defn- ci-yaml-top-list-values
  [lines key-name]
  (loop [remaining (map-indexed vector lines)
         in-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)]
        (cond
          (and entry
               (zero? (:indent entry))
               (= key-name (:key entry))
               (seq (:value entry)))
          (recur (rest remaining)
                 false
                 (into out
                       (map (fn [value]
                              {:value value
                               :source-line (:source-line entry)})
                            (yaml-scalar-list-values (:value entry)))))

          (and entry
               (zero? (:indent entry))
               (= key-name (:key entry))
               (str/blank? (:value entry)))
          (recur (rest remaining) true out)

          (and in-block?
               entry
               (zero? (:indent entry)))
          (recur (rest remaining) false out)

          in-block?
          (if-let [[_ value] (re-matches #"^\s*-\s*(.+?)\s*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:value (strip-yaml-scalar value)
                              :source-line (inc idx)}))
            (recur (rest remaining) true out))

          :else
          (recur (rest remaining) in-block? out)))
      (vec (distinct out)))))

(defn- ci-gitlab-include-facts
  [lines]
  (let [include-keys #{"local" "file" "template" "project" "remote" "component"}]
    (loop [remaining (map-indexed vector lines)
           include-indent nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [entry (yaml-key-line idx line)
              indent (leading-spaces line)
              include-indent* (cond
                                (and entry
                                     (zero? (:indent entry))
                                     (= "include" (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and include-indent
                                     entry
                                     (<= (:indent entry) include-indent))
                                nil

                                :else include-indent)
              inline-values (when (and entry
                                       (zero? (:indent entry))
                                       (= "include" (:key entry))
                                       (seq (:value entry)))
                              (map (fn [value]
                                     {:kind :ci-template
                                      :label value
                                      :source-line (:source-line entry)
                                      :relation :uses})
                                   (yaml-scalar-list-values (:value entry))))
              list-value (when (and include-indent*
                                    (> indent include-indent*))
                           (when-let [[_ value]
                                      (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                             (let [value (strip-yaml-scalar value)]
                               (if-let [[_ key scalar]
                                        (re-matches #"([A-Za-z0-9_.-]+):\s*(.+)"
                                                    value)]
                                 (when (contains? include-keys key)
                                   {:kind :ci-template
                                    :label (str key ":" (strip-yaml-scalar scalar))
                                    :source-line (inc idx)
                                    :relation :uses})
                                 {:kind :ci-template
                                  :label value
                                  :source-line (inc idx)
                                  :relation :uses}))))
              child-value (when (and include-indent*
                                     (> indent include-indent*)
                                     entry
                                     (contains? include-keys (:key entry))
                                     (seq (:value entry)))
                            {:kind :ci-template
                             :label (str (:key entry) ":"
                                         (strip-yaml-scalar (:value entry)))
                             :source-line (:source-line entry)
                             :relation :uses})]
          (recur (rest remaining)
                 include-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj list-value)
                   child-value (conj child-value))))
        (vec (distinct out))))))

(defn- ci-circleci-orb-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         orbs-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            orbs-indent* (cond
                           (and entry
                                (zero? (:indent entry))
                                (= "orbs" (:key entry))
                                (str/blank? (:value entry)))
                           (:indent entry)

                           (and orbs-indent
                                entry
                                (<= (:indent entry) orbs-indent))
                           nil

                           :else orbs-indent)
            fact (when (and orbs-indent*
                            entry
                            (> (:indent entry) orbs-indent*)
                            (seq (:value entry)))
                   {:kind :ci-template
                    :label (strip-yaml-scalar (:value entry))
                    :source-line (:source-line entry)
                    :relation :uses})]
        (recur (rest remaining)
               orbs-indent*
               (cond-> out fact (conj fact))))
      (vec (distinct out)))))

(defn- ci-azure-extends-template-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         extends-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            extends-indent* (cond
                              (and entry
                                   (zero? (:indent entry))
                                   (= "extends" (:key entry))
                                   (str/blank? (:value entry)))
                              (:indent entry)

                              (and extends-indent
                                   entry
                                   (<= (:indent entry) extends-indent))
                              nil

                              :else extends-indent)
            fact (when (and extends-indent*
                            entry
                            (> (:indent entry) extends-indent*)
                            (= "template" (:key entry))
                            (seq (:value entry)))
                   {:kind :ci-template
                    :label (strip-yaml-scalar (:value entry))
                    :source-line (:source-line entry)
                    :relation :uses})]
        (recur (rest remaining)
               extends-indent*
               (cond-> out fact (conj fact))))
      (vec (distinct out)))))

(defn- ci-template-facts
  [config-kind lines]
  (case config-kind
    :gitlab (ci-gitlab-include-facts lines)
    :circleci (ci-circleci-orb-facts lines)
    :azure (ci-azure-extends-template-facts lines)
    []))

(defn- ci-azure-workflow-facts
  [lines]
  (->> ["trigger" "pr"]
       (mapcat (fn [key-name]
                 (map (fn [{:keys [value source-line]}]
                        {:kind :ci-trigger
                         :label (str key-name ":" value)
                         :source-line source-line
                         :relation :uses})
                      (ci-yaml-top-list-values lines key-name))))
       distinct
       vec))

(defn- ci-jenkins-workflow-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ runner] (re-matches #"^\s*agent\s+([A-Za-z0-9_.-]+)\s*$"
                                                     line)]
                     {:kind :ci-runner
                      :label runner
                      :source-line (inc idx)
                      :relation :uses})
                   (when-let [[_ runner] (re-find #"\blabel\s+['\"]([^'\"]+)['\"]"
                                                  line)]
                     {:kind :ci-runner
                      :label runner
                      :source-line (inc idx)
                      :relation :uses})
                   (when-let [[_ trigger value] (re-matches #"^\s*(cron|pollSCM)\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                                            line)]
                     {:kind :ci-trigger
                      :label (str trigger ":" value)
                      :source-line (inc idx)
                      :relation :uses})
                   (when (re-matches #"^\s*githubPush\s*\(\s*\).*$" line)
                     {:kind :ci-trigger
                      :label "githubPush"
                      :source-line (inc idx)
                      :relation :uses}))))
       distinct
       vec))

(defn- ci-workflow-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-on-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)]
        (cond
          (and entry
               (zero? (:indent entry))
               (#{"on" "workflow"} (:key entry))
               (seq (:value entry)))
          (let [values (yaml-scalar-list-values (:value entry))]
            (recur (rest remaining)
                   false
                   (into out
                         (map (fn [value]
                                {:kind :ci-trigger
                                 :label value
                                 :source-line (:source-line entry)
                                 :relation :uses})
                              values))))

          (and entry
               (zero? (:indent entry))
               (#{"on" "workflow"} (:key entry))
               (str/blank? (:value entry)))
          (recur (rest remaining) true out)

          (and in-on-block?
               entry
               (zero? (:indent entry)))
          (recur (rest remaining) false out)

          (and in-on-block?
               (re-matches #"^\s+([A-Za-z0-9_.-]+):?.*$" line))
          (let [[_ trigger] (re-matches #"^\s+([A-Za-z0-9_.-]+):?.*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :ci-trigger
                              :label trigger
                              :source-line (inc idx)
                              :relation :uses})))

          :else
          (recur (rest remaining) in-on-block? out)))
      (vec (distinct out)))))

(defn- ci-event-trigger-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         event-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)
            indent (leading-spaces line)
            event-indent* (cond
                            (and entry
                                 (= "event" (:key entry))
                                 (str/blank? (:value entry)))
                            (:indent entry)

                            (and event-indent
                                 entry
                                 (<= (:indent entry) event-indent))
                            nil

                            :else event-indent)
            inline-values (when (and entry
                                     (= "event" (:key entry))
                                     (seq (:value entry)))
                            (map (fn [value]
                                   {:kind :ci-trigger
                                    :label value
                                    :source-line (:source-line entry)
                                    :relation :uses})
                                 (yaml-scalar-list-values (:value entry))))
            list-value (when (and event-indent*
                                  (> indent event-indent*))
                         (some-> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                 second
                                 strip-yaml-scalar))]
        (recur (rest remaining)
               event-indent*
               (cond-> out
                 inline-values (into inline-values)
                 list-value (conj {:kind :ci-trigger
                                   :label list-value
                                   :source-line (inc idx)
                                   :relation :uses}))))
      (vec (distinct out)))))

(defn- ci-circleci-workflow-facts
  [lines]
  (->> (yaml-top-section-blocks lines "workflows")
       (map (fn [{:keys [label source-line]}]
              {:kind :ci-workflow-entry
               :label label
               :source-line source-line
               :relation :defines}))
       distinct
       vec))

(defn- ci-circleci-workflow-needs
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-workflows? false
         current-job nil
         in-requires? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)
            indent (leading-spaces line)
            entry (yaml-key-line idx line)
            workflow-start? (and entry
                                 (zero? (:indent entry))
                                 (= "workflows" (:key entry)))
            job-entry (when (and in-workflows?
                                 (re-matches #"^-\s+[A-Za-z0-9_.-]+:\s*$"
                                             trimmed))
                        (-> trimmed
                            (str/replace #"^-\s+" "")
                            (str/replace #":$" "")))
            simple-job (when (and in-workflows?
                                  (re-matches #"^-\s+[A-Za-z0-9_.-]+\s*$"
                                              trimmed))
                         (-> trimmed
                             (str/replace #"^-\s+" "")
                             str/trim))
            requires-start? (and current-job
                                 (re-matches #"^requires:\s*$" trimmed))
            required-job (when (and current-job in-requires?)
                           (when-let [[_ target]
                                      (re-matches #"^-\s+([A-Za-z0-9_.-]+)\s*$"
                                                  trimmed)]
                             target))]
        (cond
          workflow-start?
          (recur (rest remaining) true nil false out)

          (and in-workflows? (zero? indent) (seq trimmed))
          (recur (rest remaining) false nil false out)

          job-entry
          (recur (rest remaining) true job-entry false out)

          requires-start?
          (recur (rest remaining) true current-job true out)

          required-job
          (recur (rest remaining)
                 true
                 current-job
                 true
                 (conj out {:source current-job
                            :target required-job
                            :source-line (inc idx)}))

          simple-job
          (recur (rest remaining) true simple-job false out)

          :else
          (recur (rest remaining)
                 in-workflows?
                 current-job
                 (and in-requires?
                      (or (str/blank? trimmed)
                          (> indent 10)))
                 out)))
      (vec (distinct out)))))

(defn- distinct-facts-by-kind-label
  [facts]
  (->> facts
       (reduce (fn [acc {:keys [kind label] :as fact}]
                 (if (contains? acc [kind label])
                   acc
                   (assoc acc [kind label] fact)))
               {})
       vals
       vec))

(defn- ci-command-chunks
  [run-id id-scope file-id path jobs]
  (->> jobs
       (mapcat
        (fn [{:keys [label lines]}]
          (loop [remaining lines
                 command-indent nil
                 out []]
            (if-let [[idx line] (first remaining)]
              (let [entry (yaml-key-line idx line)
                    indent (leading-spaces line)
                    command-indent* (cond
                                      (and entry
                                           (= "commands" (:key entry))
                                           (str/blank? (:value entry)))
                                      (:indent entry)

                                      (and command-indent
                                           entry
                                           (<= (:indent entry) command-indent))
                                      nil

                                      :else command-indent)
                    command (or (when-let [[_ command]
                                           (re-matches #"^\s*-?\s*(?:run|script|bash|pwsh|powershell|command|sh|bat):\s*(.+?)\s*$"
                                                       line)]
                                  command)
                                (when-let [[_ command]
                                           (re-matches #"^\s*(?:sh|bat|powershell|pwsh)\s+['\"]([^'\"]+)['\"].*$"
                                                       line)]
                                  command)
                                (when (and command-indent*
                                           (> indent command-indent*))
                                  (some-> (re-matches #"^\s*-\s+(.+?)\s*$"
                                                      line)
                                          second)))]
                (recur (rest remaining)
                       command-indent*
                       (cond-> out
                         command
                         (conj (let [chunk-label (str label " command " (inc idx))
                                     text (strip-yaml-scalar command)]
                                 {:xt/id (chunk-id id-scope
                                                   path
                                                   chunk-label
                                                   (inc idx))
                                  :file-id file-id
                                  :path path
                                  :kind :ci-command
                                  :label chunk-label
                                  :text text
                                  :tokens (text/tokenize (str chunk-label "\n" text))
                                  :source-line (inc idx)
                                  :content-sha (hash/sha256-hex text)
                                  :active? true
                                  :run-id run-id})))))
              out))))
       vec))

(defn extract-ci
  "Extract declared CI workflow jobs and explicit job dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        config-kind (ci-config-kind path lines)
        workflow-label (ci-workflow-label path lines)
        workflow-node (generic-node run-id id-scope file-id path :ci-workflow workflow-label 1)
        jobs (ci-job-blocks config-kind lines)
        workflow-facts (case config-kind
                         :jenkins (concat (ci-jenkins-workflow-facts lines)
                                          (ci-template-facts config-kind lines))
                         :azure (concat (ci-workflow-facts lines)
                                        (ci-azure-workflow-facts lines)
                                        (ci-template-facts config-kind lines))
                         :circleci (concat (ci-workflow-facts lines)
                                           (ci-circleci-workflow-facts lines)
                                           (ci-template-facts config-kind lines))
                         (:drone :woodpecker) (concat (ci-workflow-facts lines)
                                                      (ci-event-trigger-facts lines)
                                                      (ci-template-facts config-kind lines))
                         (concat (ci-workflow-facts lines)
                                 (ci-template-facts config-kind lines)))
        job-nodes (mapv (fn [{:keys [label source-line] :as job}]
                          (generic-node run-id
                                        id-scope
                                        file-id
                                        path
                                        (ci-job-kind job)
                                        label
                                        source-line))
                        jobs)
        declared-facts (concat workflow-facts (mapcat ci-job-declared-facts jobs))
        declared-node-facts (distinct-facts-by-kind-label declared-facts)
        declared-nodes (mapv (fn [{:keys [kind label source-line]}]
                               (generic-node run-id id-scope file-id path kind label source-line))
                             declared-node-facts)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id workflow-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           job-nodes)
        workflow-fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                                    (edge-row run-id
                                              file-id
                                              path
                                              (:xt/id workflow-node)
                                              (node-id id-scope kind label)
                                              (or relation :uses)
                                              :extracted
                                              source-line))
                                  workflow-facts)
        need-edges (->> jobs
                        (mapcat (fn [{:keys [label] :as job}]
                                  (map (fn [{:keys [target source-line]}]
                                         (edge-row run-id
                                                   file-id
                                                   path
                                                   (node-id id-scope (ci-job-kind job) label)
                                                   (node-id id-scope (ci-job-kind job) target)
                                                   :requires
                                                   :extracted
                                                   source-line))
                                       (ci-job-needs job))))
                        distinct
                        vec)
        workflow-need-edges (case config-kind
                              :circleci
                              (mapv (fn [{:keys [source target source-line]}]
                                      (edge-row run-id
                                                file-id
                                                path
                                                (node-id id-scope :ci-job source)
                                                (node-id id-scope :ci-job target)
                                                :requires
                                                :extracted
                                                source-line))
                                    (ci-circleci-workflow-needs lines))
                              [])
        declared-edges (->> jobs
                            (mapcat (fn [{job-label :label :as job}]
                                      (map (fn [{:keys [kind label source-line]}]
                                             (edge-row run-id
                                                       file-id
                                                       path
                                                       (node-id id-scope (ci-job-kind job) job-label)
                                                       (node-id id-scope kind label)
                                                       :uses
                                                       :extracted
                                                       source-line))
                                           (ci-job-declared-facts job))))
                            distinct
                            vec)
        chunk-result (extract-text-source run-id file :ci-file)
        command-chunks (ci-command-chunks run-id id-scope file-id path jobs)]
    {:nodes (vec (concat [workflow-node] job-nodes declared-nodes))
     :edges (vec (concat define-edges
                         workflow-fact-edges
                         need-edges
                         workflow-need-edges
                         declared-edges))
     :chunks (vec (concat (:chunks chunk-result) command-chunks))
     :diagnostics []}))

(defn- make-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target deps]
                          (re-matches #"^([A-Za-z0-9_.%/@-]+)\s*:\s*([^=].*)?$"
                                      line)]
                 (when-not (str/starts-with? target ".")
                   {:kind :build-target
                    :label target
                    :source-line (inc idx)
                    :deps (->> (str/split (or deps "") #"\s+")
                               (map str/trim)
                               (remove str/blank?)
                               vec)}))))
       vec))

(defn- cmake-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ target deps]
                              (re-matches #"(?i)^\s*add_(?:executable|library)\s*\(\s*([A-Za-z0-9_.@+-]+)\s*(.*?)\)\s*$"
                                          line)]
                     {:kind :build-target
                      :label target
                      :source-line (inc idx)
                      :deps (->> (str/split (or deps "") #"\s+")
                                 (remove str/blank?)
                                 vec)})
                   (when-let [[_ target deps]
                              (re-matches #"(?i)^\s*target_link_libraries\s*\(\s*([A-Za-z0-9_.@+-]+)\s+(.*?)\)\s*$"
                                          line)]
                     {:kind :build-target-ref
                      :label target
                      :source-line (inc idx)
                      :deps (->> (str/split (or deps "") #"\s+")
                                 (remove str/blank?)
                                 vec)}))))
       vec))

(defn- bazel-targets
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if current
        (let [attr (or (some-> (re-find #"\b(deps|dependencies|srcs|sources|data|outs|visibility)\s*=\s*\[" line)
                               second
                               {"deps" :deps
                                "dependencies" :deps
                                "srcs" :srcs
                                "sources" :srcs
                                "data" :data
                                "outs" :outs
                                "visibility" :visibility})
                       (:attr current))
              values (mapv second (re-seq #"\"([^\"\s]+)\"" line))
              attr* (when-not (re-find #"\]" line) attr)
              name (or (:label current)
                       (some-> (re-find #"\bname\s*=\s*\"([^\"]+)\"" line)
                               second))
              current* (cond-> (assoc current :label name :attr attr*)
                         (= :deps attr) (update :deps into values)
                         (= :srcs attr) (update :srcs into values)
                         (= :data attr) (update :data into values)
                         (= :outs attr) (update :outs into values)
                         (= :visibility attr) (update :visibility into values))]
          (if (re-find #"\)" line)
            (recur (rest remaining)
                   nil
                   (cond-> out
                     (:label current*)
                     (conj (dissoc current* :attr))))
            (recur (rest remaining) current* out)))
        (if-let [[_ rule] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*$" line)]
          (recur (rest remaining)
                 {:kind :build-target
                  :rule rule
                  :label nil
                  :source-line (inc idx)
                  :deps []
                  :srcs []
                  :data []
                  :outs []
                  :visibility []}
                 out)
          (recur (rest remaining) nil out)))
      out)))

(defn- bazel-dep-label
  [dep]
  (str/replace dep #"^:" ""))

(defn- pants-toml-scalar-value
  [value]
  (let [trimmed (-> value
                    (str/replace #"\s+#.*$" "")
                    str/trim)]
    (or (second (re-matches #"\"([^\"]*)\"" trimmed))
        (second (re-matches #"'([^']*)'" trimmed))
        (when-not (str/blank? trimmed)
          trimmed))))

(defn- pants-toml-entries
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         pending nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          pending
          (let [pending* (update pending :value str "\n" line)]
            (if (str/includes? line "]")
              (recur (rest remaining)
                     section
                     nil
                     (conj out pending*))
              (recur (rest remaining) section pending* out)))

          (or (str/blank? trimmed)
              (str/starts-with? trimmed "#"))
          (recur (rest remaining) section nil out)

          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining)
                 (subs trimmed 1 (dec (count trimmed)))
                 nil
                 out)

          :else
          (if-let [[_ key value]
                   (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*=\s*(.*?)\s*$"
                               line)]
            (let [entry {:section section
                         :key key
                         :value value
                         :source-line (inc idx)}]
              (if (and (str/starts-with? (str/trim value) "[")
                       (not (str/includes? value "]")))
                (recur (rest remaining) section entry out)
                (recur (rest remaining) section nil (conj out entry))))
            (recur (rest remaining) section nil out))))
      out)))

(defn- pants-toml-facts
  [content]
  (->> (pants-toml-entries content)
       (mapcat
        (fn [{:keys [section key value source-line]}]
          (let [values (if (str/starts-with? (str/trim value) "[")
                         (toml-array-strings value)
                         (some-> (pants-toml-scalar-value value) vector))]
            (case key
              "backend_packages"
              (map (fn [entry]
                     {:kind :build-plugin
                      :label entry
                      :source-line source-line
                      :relation :uses})
                   values)

              "root_patterns"
              (map (fn [entry]
                     {:kind :build-source-root
                      :label entry
                      :source-line source-line
                      :relation :references})
                   values)

              (map (fn [entry]
                     {:kind :build-setting
                      :label (str section "." key "=" entry)
                      :source-line source-line
                      :relation :defines})
                   values)))))
       (remove nil?)
       distinct
       vec))

(defn- build-targets
  [path lines]
  (let [filename (manifest-name path)]
    (cond
      (contains? #{"makefile" "gnumakefile"} filename)
      (make-targets lines)

      (or (= "cmakelists.txt" filename)
          (str/ends-with? filename ".cmake"))
      (cmake-targets lines)

      (contains? #{"build" "build.bazel" "workspace" "module.bazel" "buck"} filename)
      (bazel-targets lines)

      :else [])))

(defn- build-reference-edges
  [run-id id-scope file-id path targets]
  (let [target-labels (set (keep #(when (= :build-target (:kind %)) (:label %))
                                 targets))]
    (->> targets
         (mapcat
          (fn [{:keys [label deps source-line]}]
            (when (and (seq deps) label)
              (map (fn [dep]
                     (let [dep-label (bazel-dep-label dep)]
                       (edge-row run-id
                                 file-id
                                 path
                                 (node-id id-scope :build-target label)
                                 (node-id id-scope
                                          (if (contains? target-labels dep-label)
                                            :build-target
                                            :build-reference)
                                          dep-label)
                                 :requires
                                 :extracted
                                 source-line)))
                   (remove #{label} deps)))))
         distinct
         vec)))

(defn- build-target-facts
  [targets]
  (->> targets
       (mapcat
        (fn [{:keys [label rule source-line srcs data outs visibility]}]
          (when label
            (concat
             (when rule
               [{:source label
                 :kind :build-rule
                 :label (str label ":" rule)
                 :source-line source-line
                 :relation :uses}])
             (map (fn [source]
                    {:source label
                     :kind :build-source
                     :label source
                     :source-line source-line
                     :relation :references})
                  srcs)
             (map (fn [entry]
                    {:source label
                     :kind :build-data
                     :label entry
                     :source-line source-line
                     :relation :references})
                  data)
             (map (fn [entry]
                    {:source label
                     :kind :build-output
                     :label entry
                     :source-line source-line
                     :relation :builds})
                  outs)
             (map (fn [entry]
                    {:source label
                     :kind :build-visibility
                     :label entry
                     :source-line source-line
                     :relation :uses})
                  visibility)))))
       (remove nil?)
       distinct
       vec))

(defn- build-file-facts
  [path content]
  (let [filename (manifest-name path)]
    (cond
      (= "pants.toml" filename) (pants-toml-facts content)
      :else [])))

(defn- build-reference-nodes
  [run-id id-scope file-id path targets]
  (let [target-labels (set (keep #(when (= :build-target (:kind %)) (:label %))
                                 targets))]
    (->> targets
         (mapcat :deps)
         (map bazel-dep-label)
         (remove target-labels)
         (remove str/blank?)
         distinct
         (mapv #(generic-node run-id id-scope file-id path
                              :build-reference % 1)))))

(defn extract-build
  "Extract declared build targets and explicit target dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        build-node (generic-node run-id id-scope file-id path :build-file path 1)
        targets (build-targets path lines)
        target-facts (build-target-facts targets)
        file-facts (build-file-facts path content)
        target-nodes (->> targets
                          (filter #(= :build-target (:kind %)))
                          (mapv (fn [{:keys [label source-line]}]
                                  (generic-node run-id
                                                id-scope
                                                file-id
                                                path
                                                :build-target
                                                label
                                                source-line))))
        target-fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                                  (generic-node run-id
                                                id-scope
                                                file-id
                                                path
                                                kind
                                                label
                                                source-line))
                                target-facts)
        file-fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                                (generic-node run-id
                                              id-scope
                                              file-id
                                              path
                                              kind
                                              label
                                              source-line))
                              file-facts)
        reference-nodes (build-reference-nodes run-id id-scope file-id path targets)
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id build-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           target-nodes)
        target-fact-edges (mapv (fn [{:keys [source kind label source-line relation]}]
                                  (edge-row run-id
                                            file-id
                                            path
                                            (node-id id-scope :build-target source)
                                            (node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                                target-facts)
        file-fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (:xt/id build-node)
                                          (node-id id-scope kind label)
                                          relation
                                          :extracted
                                          source-line))
                              file-facts)
        reference-edges (build-reference-edges run-id id-scope file-id path targets)
        chunk-result (extract-text-source run-id file :build-file)]
    {:nodes (vec (concat [build-node]
                         target-nodes
                         target-fact-nodes
                         file-fact-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         target-fact-edges
                         file-fact-edges
                         reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- sql-name
  [value]
  (some-> value
          (str/replace #"^[`\"\[]|[`\"\]]$" "")
          (str/replace #";$" "")))

(defn- sql-create-row
  [idx line]
  (or (when-let [[_ table]
                 (re-find #"(?i)^\s*create\s+(?:temporary\s+|temp\s+)?table\s+(?:if\s+not\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                          line)]
        {:kind :table
         :name (sql-name table)
         :source-line (inc idx)})
      (when-let [[_ view]
                 (re-find #"(?i)^\s*create\s+(?:or\s+replace\s+)?view\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                          line)]
        {:kind :view
         :name (sql-name view)
         :source-line (inc idx)})))

(defn- sql-table-ranges
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [create (sql-create-row idx line)]
        (recur (rest remaining)
               (assoc create :start-idx idx :end-idx idx)
               (cond-> out current (conj current)))
        (let [current* (cond-> current
                         current (assoc :end-idx idx))]
          (if (and current (str/includes? line ");"))
            (recur (rest remaining) nil (conj out current*))
            (recur (rest remaining) current* out))))
      (cond-> out current (conj current)))))

(defn- sql-reference-targets
  [line]
  (->> (re-seq #"(?i)\breferences\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)" line)
       (map second)
       (map sql-name)
       (remove str/blank?)
       distinct))

(defn- sql-table-edges
  [run-id id-scope file-id path tables lines]
  (->> tables
       (filter #(= :table (:kind %)))
       (mapcat
        (fn [{:keys [name start-idx end-idx]}]
          (let [source-id (node-id id-scope :table name)]
            (->> (subvec (vec lines) start-idx (inc end-idx))
                 (map-indexed vector)
                 (mapcat (fn [[offset line]]
                           (map (fn [target]
                                  (edge-row run-id
                                            file-id
                                            path
                                            source-id
                                            (node-id id-scope :table target)
                                            :references
                                            :extracted
                                            (+ start-idx offset 1)))
                                (sql-reference-targets line))))))))
       distinct
       vec))

(defn- dbt-ref-calls
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ target]]
                 {:kind :dbt-model
                  :label target
                  :source-line (inc idx)})
               (re-seq #"\bref\s*\(\s*['\"]([^'\"]+)['\"]\s*\)" line))))
       distinct
       vec))

(defn- dbt-source-calls
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ source table]]
                 {:kind :dbt-source
                  :label (str source "." table)
                  :source-line (inc idx)})
               (re-seq #"\bsource\s*\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\)"
                       line))))
       distinct
       vec))

(defn- dbt-sql-reference-facts
  [lines]
  (vec (concat (dbt-ref-calls lines)
               (dbt-source-calls lines))))

(defn- dbt-sql-reference-rows
  [run-id id-scope file-id path refs]
  (when (seq refs)
    (let [sql-node (generic-node run-id id-scope file-id path :dbt-sql-file path 1)
          ref-nodes (mapv (fn [{:keys [kind label source-line]}]
                            (generic-node run-id id-scope file-id path
                                          kind label source-line))
                          refs)
          ref-edges (mapv (fn [{:keys [kind label source-line]}]
                            (edge-row run-id
                                      file-id
                                      path
                                      (:xt/id sql-node)
                                      (node-id id-scope kind label)
                                      :references
                                      :extracted
                                      source-line))
                          refs)]
      {:nodes (into [sql-node] ref-nodes)
       :edges ref-edges})))

(defn extract-sql
  "Extract declared SQL schema facts from a static SQL file."
  [run-id {:keys [id-scope file-id path content kind] :as file}]
  (let [lines (vec (str/split-lines content))
        declarations (sql-table-ranges lines)
        nodes (mapv (fn [{:keys [kind name source-line]}]
                      (generic-node run-id id-scope file-id path kind name source-line))
                    declarations)
        edges (sql-table-edges run-id id-scope file-id path declarations lines)
        dbt-rows (dbt-sql-reference-rows run-id
                                         id-scope
                                         file-id
                                         path
                                         (dbt-sql-reference-facts lines))
        chunk-result (extract-text-source run-id file :sql-file)]
    (assoc chunk-result
           :nodes (vec (concat nodes (:nodes dbt-rows)))
           :edges (vec (concat edges (:edges dbt-rows)))
           :chunks (mapv #(assoc % :file-kind kind) (:chunks chunk-result)))))

(defn- migration-filename-stem
  [path]
  (let [filename (.getName (java.io.File. (str path)))
        dot-idx (.lastIndexOf filename ".")]
    (if (neg? dot-idx)
      filename
      (subs filename 0 dot-idx))))

(defn- flyway-migration-fact
  [path]
  (let [stem (migration-filename-stem path)]
    (when (re-matches #"(?i)(?:V[0-9][A-Za-z0-9_.-]*|U[0-9][A-Za-z0-9_.-]*|R)__.+"
                      stem)
      {:kind :db-migration-version
       :label stem
       :source-line 1
       :relation :defines})))

(defn- distinct-migration-facts
  [facts]
  (->> facts
       (reduce (fn [acc {:keys [kind label relation] :as fact}]
                 (if (contains? acc [kind label relation])
                   acc
                   (assoc acc [kind label relation] fact)))
               {})
       vals
       vec))

(defn- migration-sql-line-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ index table]
                        (re-find #"(?i)^\s*create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)\s+on\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :index
                 :label (sql-name index)
                 :source-line source-line
                 :relation :defines}
                {:kind :table
                 :label (sql-name table)
                 :source-line source-line
                 :relation :references}])
             (when-let [[_ table]
                        (re-find #"(?i)^\s*alter\s+table\s+(?:if\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :table
                 :label (sql-name table)
                 :source-line source-line
                 :relation :references}])
             (when-let [[_ constraint]
                        (re-find #"(?i)\b(?:add\s+)?constraint\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :constraint
                 :label (sql-name constraint)
                 :source-line source-line
                 :relation :defines}])
             (map (fn [target]
                    {:kind :table
                     :label target
                     :source-line source-line
                     :relation :references})
                  (sql-reference-targets line))))))
       distinct-migration-facts))

(defn- migration-sql-facts
  [path lines]
  (let [version-fact (flyway-migration-fact path)
        declarations (sql-table-ranges lines)
        declaration-facts (mapv (fn [{:keys [kind name source-line]}]
                                  {:kind kind
                                   :label name
                                   :source-line source-line
                                   :relation :defines})
                                declarations)]
    (distinct-migration-facts
     (cond-> (vec (concat declaration-facts (migration-sql-line-facts lines)))
       version-fact (conj version-fact)))))

(defn- liquibase-line-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (map (fn [[_ id]]
                    {:kind :db-changeset
                     :label id
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bid\s*[:=]\s*|\"id\"\s*:\s*\")\"?([A-Za-z0-9_.:-]+)"
                          line))
             (map (fn [[_ table]]
                    {:kind :table
                     :label table
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\btableName\s*[:=]\s*|\"tableName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ index]]
                    {:kind :index
                     :label index
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bindexName\s*[:=]\s*|\"indexName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ constraint]]
                    {:kind :constraint
                     :label constraint
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bconstraintName\s*[:=]\s*|\"constraintName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ include-file]]
                    {:kind :project-reference
                     :label include-file
                     :source-line source-line
                     :relation :references})
                  (re-seq #"(?i)(?:\bfile\s*[:=]\s*|\"file\"\s*:\s*\")\"?([^\"'\s,}]+)"
                          line))
             (map (fn [[_ author]]
                    {:kind :db-changeset-author
                     :label author
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bauthor\s*[:=]\s*|\"author\"\s*:\s*\")\"?([A-Za-z0-9_.:-]+)"
                          line))
             (map (fn [[_ operation]]
                    {:kind :db-change-operation
                     :label operation
                     :source-line source-line
                     :relation :uses})
                  (remove #(contains? #{"changeSet" "include"} (second %))
                          (re-seq #"^\s*-\s+([A-Za-z][A-Za-z0-9]*):\s*$"
                                  line)))
             (when (re-matches #"(?i)^\s*rollback\s*:\s*.*$" line)
               [{:kind :db-rollback
                 :label "rollback"
                 :source-line source-line
                 :relation :defines}])))))
       distinct
       vec))

(defn- migration-facts
  [{:keys [path content ext]}]
  (let [lines (vec (str/split-lines content))]
    (if (= ".sql" ext)
      (migration-sql-facts path lines)
      (liquibase-line-facts lines))))

(defn extract-db-migration
  "Extract bounded database migration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [migration-node (generic-node run-id id-scope file-id path
                                     :db-migration path 1)
        facts (migration-facts file)
        fact-nodes (->> facts
                        (map (fn [{:keys [kind label source-line]}]
                               (generic-node run-id id-scope file-id path
                                             kind label source-line)))
                        (reduce (fn [acc node]
                                  (assoc acc (:xt/id node) node))
                                {})
                        vals
                        vec)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id migration-node)
                                     (node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file :db-migration-file)]
    {:nodes (into [migration-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn- block-start
  [idx line]
  (or (when-let [[_ resource-type name]
                 (re-matches #"^\s*resource\s+\"([^\"]+)\"\s+\"([^\"]+)\"\s*\{\s*$"
                             line)]
        {:block-type "resource"
         :kind :terraform-resource
         :name (str resource-type "." name)
         :provider resource-type
         :source-line (inc idx)})
      (when-let [[_ resource-type name]
                 (re-matches #"^\s*data\s+\"([^\"]+)\"\s+\"([^\"]+)\"\s*\{\s*$"
                             line)]
        {:block-type "data"
         :kind :terraform-data-source
         :name (str "data." resource-type "." name)
         :provider resource-type
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*module\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type "module"
         :kind :terraform-module
         :name (str "module." name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*provider\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type "provider"
         :kind :terraform-provider
         :name (str "provider." name)
         :provider name
         :source-line (inc idx)})))

(defn- hcl-blocks
  ([lines] (hcl-blocks lines block-start))
  ([lines start-fn]
   (loop [remaining (map-indexed vector lines)
          current nil
          depth 0
          out []]
     (if-let [[idx line] (first remaining)]
       (if current
         (let [opens (count (re-seq #"\{" line))
               closes (count (re-seq #"\}" line))
               depth* (+ depth opens (- closes))
               current* (-> current
                            (update :lines conj [idx line])
                            (assoc :end-line (inc idx)))]
           (if (<= depth* 0)
             (recur (rest remaining) nil 0 (conj out current*))
             (recur (rest remaining) current* depth* out)))
         (if-let [start (start-fn idx line)]
           (let [opens (count (re-seq #"\{" line))
                 closes (count (re-seq #"\}" line))]
             (recur (rest remaining)
                    (assoc start
                           :lines [[idx line]]
                           :end-line (inc idx))
                    (+ opens (- closes))
                    out))
           (recur (rest remaining) nil 0 out)))
       out))))

(defn- hcl-reference-targets
  [lines]
  (->> lines
       (mapcat (fn [[idx line]]
                 (map (fn [[_ target]]
                        {:target target
                         :source-line (inc idx)})
                      (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_-]*)+)\b"
                              line))))
       distinct
       vec))

(defn- hcl-reference-prefixes
  [target]
  (let [parts (str/split target #"\.")]
    (->> (range (count parts) 1 -1)
         (map #(str/join "." (take % parts))))))

(defn- hcl-reference-target-id
  [node-by-name target]
  (some #(get node-by-name %) (hcl-reference-prefixes target)))

(defn- hcl-string-attr
  [lines attr]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*" attr "\\s*=\\s*\"([^\"]+)\"\\s*$"))
                                 line)]
            {:value value
             :source-line (inc idx)}))
        lines))

(defn- hcl-ref-attr
  [lines attr]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*" attr "\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+)\\s*$"))
                                 line)]
            {:value value
             :source-line (inc idx)}))
        lines))

(defn- terraform-chunks
  [run-id id-scope file-id path blocks]
  (->> blocks
       (filter #(contains? #{"variable" "output"} (:block-type %)))
       (mapv (fn [{:keys [block-type name source-line lines]}]
               (let [label (if (contains? #{"variable" "output"} block-type)
                             name
                             (str block-type "." name))
                     text (->> lines (map second) (str/join "\n"))]
                 {:xt/id (chunk-id id-scope path label source-line)
                  :file-id file-id
                  :path path
                  :kind :terraform-block
                  :label label
                  :text text
                  :tokens (text/tokenize (str label "\n" text))
                  :source-line source-line
                  :active? true
                  :run-id run-id})))))

(defn- terraform-extra-block-start
  [idx line]
  (or (when-let [[_ block-type name]
                 (re-matches #"^\s*(variable|output)\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type block-type
         :kind (case block-type
                 "variable" :terraform-variable
                 "output" :terraform-output)
         :name (case block-type
                 "variable" (str "var." name)
                 "output" (str "output." name))
         :source-line (inc idx)})
      (block-start idx line)))

(defn- terraform-blocks
  [lines]
  (hcl-blocks lines terraform-extra-block-start))

(defn- terraform-block-facts
  [blocks]
  (->> blocks
       (mapcat
        (fn [{:keys [kind name lines]}]
          (let [module-source (when (= :terraform-module kind)
                                (hcl-string-attr lines "source"))
                provider-alias (when (= :terraform-provider kind)
                                 (hcl-string-attr lines "alias"))
                provider-use (when (contains? #{:terraform-resource :terraform-data-source} kind)
                               (hcl-ref-attr lines "provider"))]
            (cond-> []
              module-source
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-module-source
                     :target-label (:value module-source)
                     :relation :uses
                     :source-line (:source-line module-source)})

              provider-alias
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-provider-alias
                     :target-label (str name "." (:value provider-alias))
                     :relation :defines
                     :source-line (:source-line provider-alias)})

              provider-use
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-provider-alias
                     :target-label (str "provider." (:value provider-use))
                     :relation :uses
                     :source-line (:source-line provider-use)})))))
       distinct
       vec))

(defn extract-terraform
  "Extract declared Terraform/HCL blocks and explicit references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        file-node (generic-node run-id id-scope file-id path :terraform-file path 1)
        blocks (terraform-blocks lines)
        graph-blocks (filter :kind blocks)
        block-facts (terraform-block-facts graph-blocks)
        nodes (into [file-node]
                    (map (fn [{:keys [kind name source-line]}]
                           (generic-node run-id id-scope file-id path kind name source-line)))
                    graph-blocks)
        fact-nodes (mapv (fn [{:keys [target-kind target-label source-line]}]
                           (generic-node run-id id-scope file-id path
                                         target-kind target-label source-line))
                         block-facts)
        all-nodes (->> (concat nodes fact-nodes)
                       (reduce (fn [acc node]
                                 (assoc acc (:xt/id node) node))
                               {})
                       vals
                       vec)
        node-by-name (into {} (map (juxt :label :xt/id)) all-nodes)
        define-edges (mapv (fn [{:keys [kind name source-line]}]
                             (edge-row run-id
                                       file-id
                                       path
                                       (:xt/id file-node)
                                       (node-id id-scope kind name)
                                       :defines
                                       :extracted
                                       source-line))
                           graph-blocks)
        reference-edges (->> graph-blocks
                             (mapcat (fn [{:keys [kind name lines]}]
                                       (let [source-id (node-id id-scope kind name)]
                                         (keep (fn [{:keys [target source-line]}]
                                                 (when-let [target-id (hcl-reference-target-id node-by-name target)]
                                                   (when (not= source-id target-id)
                                                     (edge-row run-id
                                                               file-id
                                                               path
                                                               source-id
                                                               target-id
                                                               :references
                                                               :extracted
                                                               source-line))))
                                               (hcl-reference-targets lines)))))
                             distinct
                             vec)
        fact-edges (mapv (fn [{:keys [source-kind source-name target-kind target-label
                                      relation source-line]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (node-id id-scope source-kind source-name)
                                     (node-id id-scope target-kind target-label)
                                     relation
                                     :extracted
                                     source-line))
                         block-facts)
        chunk-result (extract-text-source run-id file :terraform-file)
        chunks (vec (concat (:chunks chunk-result)
                            (terraform-chunks run-id id-scope file-id path blocks)))]
    {:nodes all-nodes
     :edges (vec (concat define-edges reference-edges fact-edges))
     :chunks chunks
     :diagnostics []}))

(def openapi-methods
  #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})

(defn- openapi-operation-label
  [{:keys [path method operation-id]}]
  (str (str/upper-case method)
       " "
       path
       (when operation-id
         (str " " operation-id))))

(defn- openapi-ref-schema-name
  [ref]
  (json-ref-tail "#/components/schemas/" ref))

(defn- openapi-yaml-ref-values
  [lines]
  (->> lines
       (keep (fn [[idx line]]
               (when-let [[_ ref] (re-matches #"^\s*\$ref:\s*['\"]?([^'\"]+)['\"]?\s*$"
                                              line)]
                 {:ref ref
                  :source-line (inc idx)})))
       vec))

(defn- openapi-yaml-server-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-servers? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)]
        (cond
          (and entry (zero? (:indent entry)) (= "servers" (:key entry)))
          (recur (rest remaining) true out)

          (and in-servers? entry (zero? (:indent entry)))
          (recur (rest remaining) false out)

          in-servers?
          (if-let [[_ url] (re-matches #"^\s*-\s*url:\s*(.+?)\s*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:url (strip-yaml-scalar url)
                              :source-line (inc idx)}))
            (recur (rest remaining) true out))

          :else
          (recur (rest remaining) in-servers? out)))
      (vec (distinct out)))))

(defn- openapi-yaml-operation-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current-path nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (re-matches #"^\s{2}(/[^\s:]+):\s*$" line)
        (let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$" line)]
          (recur (rest remaining) path nil (cond-> out current (conj current))))

        (re-matches #"^\s{4}([a-z]+):\s*$" line)
        (let [[_ method] (re-matches #"^\s{4}([a-z]+):\s*$" line)]
          (if (and current-path (contains? openapi-methods method))
            (recur (rest remaining)
                   current-path
                   {:path current-path
                    :method method
                    :source-line (inc idx)
                    :lines [[idx line]]}
                   (cond-> out current (conj current)))
            (recur (rest remaining) current-path current out)))

        (and current
             (not (str/blank? line))
             (zero? (leading-spaces line)))
        (recur (rest remaining) nil nil (conj out current))

        current
        (recur (rest remaining)
               current-path
               (update current :lines conj [idx line])
               out)

        :else
        (recur (rest remaining) current-path current out))
      (cond-> out current (conj current)))))

(defn- openapi-yaml-schema-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-schemas? false
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (re-matches #"^\s{2}schemas:\s*$" line)
        (recur (rest remaining) true nil out)

        (and in-schemas?
             (re-matches #"^\s{2}[A-Za-z0-9_.-]+:\s*$" line))
        (recur (rest remaining) false nil (cond-> out current (conj current)))

        (and in-schemas?
             (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
        (let [[_ name] (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line)]
          (recur (rest remaining)
                 true
                 {:name name
                  :source-line (inc idx)
                  :lines [[idx line]]}
                 (cond-> out current (conj current))))

        current
        (recur (rest remaining)
               in-schemas?
               (update current :lines conj [idx line])
               out)

        :else
        (recur (rest remaining) in-schemas? current out))
      (cond-> out current (conj current)))))

(defn- openapi-json-spec
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (when (or (:openapi parsed) (:swagger parsed))
        parsed))
    (catch Exception _ nil)))

(defn- openapi-yaml-lines
  [lines]
  (when (some #(re-matches #"^\s*(openapi|swagger):\s*.+$" %) lines)
    (let [operation-blocks (openapi-yaml-operation-blocks lines)
          schema-blocks (openapi-yaml-schema-blocks lines)]
      {:servers (openapi-yaml-server-facts lines)
       :paths (->> lines
                   (map-indexed vector)
                   (keep (fn [[idx line]]
                           (when-let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$"
                                                           line)]
                             {:path path
                              :source-line (inc idx)})))
                   vec)
       :operations (->> operation-blocks
                        (mapv (fn [{:keys [lines] :as operation}]
                                (assoc operation
                                       :operation-id
                                       (some (fn [[_ line]]
                                               (some-> (re-matches #"^\s{6}operationId:\s*(.+?)\s*$" line)
                                                       second
                                                       strip-yaml-scalar))
                                             lines)))))
       :schemas (mapv #(select-keys % [:name :source-line]) schema-blocks)
       :operation-refs (->> operation-blocks
                            (mapcat (fn [operation]
                                      (map (fn [ref]
                                             (assoc ref
                                                    :source-kind :api-operation
                                                    :source-label (openapi-operation-label
                                                                   (assoc operation
                                                                          :operation-id
                                                                          (some (fn [[_ line]]
                                                                                  (some-> (re-matches #"^\s{6}operationId:\s*(.+?)\s*$" line)
                                                                                          second
                                                                                          strip-yaml-scalar))
                                                                                (:lines operation))))))
                                           (openapi-yaml-ref-values (:lines operation)))))
                            vec)
       :schema-refs (->> schema-blocks
                         (mapcat (fn [{:keys [name lines]}]
                                   (map (fn [ref]
                                          (assoc ref
                                                 :source-kind :api-schema
                                                 :source-label name))
                                        (openapi-yaml-ref-values lines))))
                         vec)})))

(defn- openapi-json-facts
  [spec]
  (when spec
    {:servers (->> (:servers spec)
                   (keep (fn [server]
                           (when-let [url (:url server)]
                             {:url url
                              :source-line 1})))
                   vec)
     :paths (mapv (fn [path]
                    {:path (name path)
                     :source-line 1})
                  (keys (:paths spec)))
     :operations (->> (:paths spec)
                      (mapcat (fn [[path methods]]
                                (->> methods
                                     (filter (fn [[method _]]
                                               (contains? openapi-methods (name method))))
                                     (map (fn [[method operation]]
                                            {:path (name path)
                                             :method (name method)
                                             :operation-id (:operationId operation)
                                             :source-line 1
                                             :refs (json-ref-values operation)})))))
                      vec)
     :schemas (mapv (fn [schema]
                      {:name (name schema)
                       :source-line 1})
                    (keys (get-in spec [:components :schemas])))
     :operation-refs (->> (:paths spec)
                          (mapcat (fn [[path methods]]
                                    (->> methods
                                         (filter (fn [[method _]]
                                                   (contains? openapi-methods (name method))))
                                         (mapcat (fn [[method operation]]
                                                   (map (fn [ref]
                                                          {:ref ref
                                                           :source-line 1
                                                           :source-kind :api-operation
                                                           :source-label (openapi-operation-label
                                                                          {:path (name path)
                                                                           :method (name method)
                                                                           :operation-id (:operationId operation)})})
                                                        (json-ref-values operation)))))))
                          vec)
     :schema-refs (->> (get-in spec [:components :schemas])
                       (mapcat (fn [[schema schema-value]]
                                 (map (fn [ref]
                                        {:ref ref
                                         :source-line 1
                                         :source-kind :api-schema
                                         :source-label (name schema)})
                                      (json-ref-values schema-value))))
                       vec)}))

(defn- openapi-facts
  [content]
  (let [lines (str/split-lines content)]
    (or (openapi-json-facts (openapi-json-spec content))
        (openapi-yaml-lines lines)
        {:paths []
         :operations []
         :schemas []
         :diagnostics [{:stage :parse
                        :line 1
                        :message "OpenAPI extractor did not find openapi or swagger declaration."}]})))

(defn extract-openapi
  "Extract declared OpenAPI paths, operations, and schemas."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (openapi-facts content)
        spec-node (generic-node run-id id-scope file-id path :api-spec path 1)
        server-nodes (mapv (fn [{:keys [url source-line]}]
                             (generic-node run-id id-scope file-id (:path file)
                                           :api-server url source-line))
                           (:servers facts))
        path-nodes (mapv (fn [{:keys [path source-line]}]
                           (generic-node run-id id-scope file-id (:path file)
                                         :api-path path source-line))
                         (:paths facts))
        operation-nodes (mapv (fn [{:keys [path method operation-id source-line]}]
                                (generic-node run-id id-scope file-id (:path file)
                                              :api-operation
                                              (openapi-operation-label
                                               {:path path
                                                :method method
                                                :operation-id operation-id})
                                              source-line))
                              (:operations facts))
        schema-nodes (mapv (fn [{:keys [name source-line]}]
                             (generic-node run-id id-scope file-id (:path file)
                                           :api-schema name source-line))
                           (:schemas facts))
        path-edges (mapv #(edge-row run-id file-id path
                                    (:xt/id spec-node)
                                    (:xt/id %)
                                    :defines
                                    :extracted
                                    (:source-line %))
                         path-nodes)
        server-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id spec-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           server-nodes)
        path-id-by-label (into {} (map (juxt :label :xt/id)) path-nodes)
        operation-edges (mapv (fn [{:keys [path method operation-id source-line]}]
                                (edge-row run-id
                                          file-id
                                          (:path file)
                                          (get path-id-by-label path)
                                          (node-id id-scope
                                                   :api-operation
                                                   (openapi-operation-label
                                                    {:path path
                                                     :method method
                                                     :operation-id operation-id}))
                                          :defines
                                          :extracted
                                          source-line))
                              (:operations facts))
        schema-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id spec-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           schema-nodes)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        ref-edges (->> (concat (:operation-refs facts) (:schema-refs facts))
                       (keep (fn [{:keys [source-kind source-label ref source-line]}]
                               (when-let [schema-name (openapi-ref-schema-name ref)]
                                 (when-let [target-id (get schema-id-by-label schema-name)]
                                   (edge-row run-id
                                             file-id
                                             path
                                             (node-id id-scope source-kind source-label)
                                             target-id
                                             :references
                                             :extracted
                                             source-line)))))
                       distinct
                       vec)
        chunk-result (extract-text-source run-id file :openapi-file)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [spec-node] server-nodes path-nodes operation-nodes schema-nodes))
     :edges (vec (concat server-edges path-edges operation-edges schema-edges ref-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))

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

(def asyncapi-operation-keys
  #{"publish" "subscribe" "send" "receive"})

(defn- json-ref-tail
  [prefix ref]
  (when (and (string? ref) (str/starts-with? ref prefix))
    (subs ref (count prefix))))

(defn- asyncapi-json-spec
  [content]
  (let [parsed (read-json-map content)]
    (when (:asyncapi parsed)
      parsed)))

(defn- asyncapi-json-facts
  [spec]
  (when spec
    (let [channels (:channels spec)
          servers (:servers spec)
          messages (get-in spec [:components :messages])
          schemas (get-in spec [:components :schemas])
          operation-traits (get-in spec [:components :operationTraits])
          operations (->> channels
                          (mapcat
                           (fn [[channel operations]]
                             (->> operations
                                  (filter (fn [[operation _]]
                                            (contains? asyncapi-operation-keys
                                                       (json-key-label operation))))
                                  (map (fn [[operation details]]
                                         (let [operation-name (json-key-label operation)
                                               channel-name (json-key-label channel)]
                                           {:channel channel-name
                                            :operation operation-name
                                            :operation-id (:operationId details)
                                            :label (or (:operationId details)
                                                       (str (str/upper-case operation-name)
                                                            " "
                                                            channel-name))
                                            :message-refs (->> (json-ref-values (:message details))
                                                               (keep #(json-ref-tail
                                                                       "#/components/messages/"
                                                                       %))
                                                               distinct
                                                               vec)
                                            :trait-refs (->> (json-ref-values (:traits details))
                                                             (keep #(json-ref-tail
                                                                     "#/components/operationTraits/"
                                                                     %))
                                                             distinct
                                                             vec)
                                            :source-line 1}))))))
                          vec)]
      {:channels (mapv (fn [channel]
                         {:label (json-key-label channel)
                          :source-line 1})
                       (keys channels))
       :servers (mapv (fn [[server server-value]]
                        {:label (json-key-label server)
                         :url (json-label (:url server-value))
                         :protocol (json-label (:protocol server-value))
                         :source-line 1})
                      servers)
       :operations operations
       :operation-traits (mapv (fn [trait]
                                 {:label (json-key-label trait)
                                  :source-line 1})
                               (keys operation-traits))
       :messages (mapv (fn [message]
                         {:label (json-key-label message)
                          :source-line 1})
                       (keys messages))
       :schemas (mapv (fn [schema]
                        {:label (json-key-label schema)
                         :source-line 1})
                      (keys schemas))
       :bindings (vec
                  (concat
                   (mapcat
                    (fn [[channel channel-value]]
                      (map (fn [[binding binding-value]]
                             {:label (str (json-key-label channel)
                                          ":"
                                          (json-key-label binding))
                              :source-line 1
                              :value (json/write-json-str binding-value)})
                           (:bindings channel-value)))
                    channels)
                   (mapcat
                    (fn [[message message-value]]
                      (map (fn [[binding binding-value]]
                             {:label (str (json-key-label message)
                                          ":"
                                          (json-key-label binding))
                              :source-line 1
                              :value (json/write-json-str binding-value)})
                           (:bindings message-value)))
                    messages)))
       :headers (mapv (fn [[message message-value]]
                        (when (:headers message-value)
                          {:label (json-key-label message)
                           :source-line 1
                           :refs (json-ref-values (:headers message-value))}))
                      messages)
       :correlation-ids (mapv (fn [[message message-value]]
                                (when-let [location (get-in message-value
                                                            [:correlationId
                                                             :location])]
                                  {:label (str (json-key-label message)
                                               ":"
                                               (json-label location))
                                   :source-line 1}))
                              messages)
       :message-schema-refs (->> messages
                                 (mapcat
                                  (fn [[message message-value]]
                                    (map (fn [ref]
                                           {:source-label (json-key-label message)
                                            :source-kind :asyncapi-message
                                            :ref ref
                                            :source-line 1})
                                         (json-ref-values message-value))))
                                 vec)
       :schema-refs (->> schemas
                         (mapcat
                          (fn [[schema schema-value]]
                            (map (fn [ref]
                                   {:source-label (json-key-label schema)
                                    :source-kind :asyncapi-schema
                                    :ref ref
                                    :source-line 1})
                                 (json-ref-values schema-value))))
                         vec)})))

(defn- asyncapi-yaml-facts
  [content]
  (let [lines (str/split-lines content)]
    (when (some #(re-matches #"^\s*asyncapi:\s*.+$" %) lines)
      (loop [remaining (map-indexed vector lines)
             top-section nil
             component-section nil
             current-channel nil
             out {:channels []
                  :operations []
                  :messages []
                  :schemas []}]
        (if-let [[idx line] (first remaining)]
          (cond
            (str/blank? (str/trim line))
            (recur (rest remaining) top-section component-section current-channel out)

            (re-matches #"^[A-Za-z][A-Za-z0-9_-]*:\s*.*$" line)
            (let [section (second (re-matches #"^([A-Za-z][A-Za-z0-9_-]*):\s*.*$"
                                              line))]
              (recur (rest remaining)
                     section
                     nil
                     nil
                     out))

            (and (= "channels" top-section)
                 (re-matches #"^\s{2}([^#\s][^:]*):\s*$" line))
            (let [channel (-> (second (re-matches #"^\s{2}([^#\s][^:]*):\s*$" line))
                              str/trim)]
              (recur (rest remaining)
                     top-section
                     component-section
                     channel
                     (update out :channels conj
                             {:label channel
                              :source-line (inc idx)})))

            (and (= "channels" top-section)
                 current-channel
                 (re-matches #"^\s{4}(publish|subscribe|send|receive):\s*$" line))
            (let [operation (second (re-matches #"^\s{4}(publish|subscribe|send|receive):\s*$"
                                                line))]
              (recur (rest remaining)
                     top-section
                     component-section
                     current-channel
                     (update out :operations conj
                             {:channel current-channel
                              :operation operation
                              :label (str (str/upper-case operation)
                                          " "
                                          current-channel)
                              :message-refs []
                              :source-line (inc idx)})))

            (and (= "components" top-section)
                 (re-matches #"^\s{2}(messages|schemas):\s*$" line))
            (let [section (second (re-matches #"^\s{2}(messages|schemas):\s*$" line))]
              (recur (rest remaining)
                     top-section
                     section
                     nil
                     out))

            (and (= "components" top-section)
                 (contains? #{"messages" "schemas"} component-section)
                 (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
            (let [label (second (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
                  target-key (if (= "messages" component-section) :messages :schemas)]
              (recur (rest remaining)
                     top-section
                     component-section
                     nil
                     (update out target-key conj
                             {:label label
                              :source-line (inc idx)})))

            :else
            (recur (rest remaining) top-section component-section current-channel out))
          out)))))

(defn- asyncapi-facts
  [content]
  (or (asyncapi-json-facts (asyncapi-json-spec content))
      (asyncapi-yaml-facts content)
      {:channels []
       :servers []
       :operations []
       :operation-traits []
       :messages []
       :schemas []
       :bindings []
       :headers []
       :correlation-ids []
       :message-schema-refs []
       :schema-refs []
       :diagnostics [{:stage :parse
                      :line 1
                      :message "AsyncAPI extractor did not find asyncapi declaration."}]}))

(defn extract-asyncapi
  "Extract declared AsyncAPI channels, operations, messages, and schemas."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (asyncapi-facts content)
        spec-node (generic-node run-id id-scope file-id path :asyncapi-spec path 1)
        server-nodes (mapv (fn [{:keys [label source-line]}]
                             (generic-node run-id id-scope file-id path
                                           :asyncapi-server label source-line))
                           (:servers facts))
        channel-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id id-scope file-id path
                                            :asyncapi-channel label source-line))
                            (:channels facts))
        operation-nodes (mapv (fn [{:keys [label source-line]}]
                                (generic-node run-id id-scope file-id path
                                              :asyncapi-operation label source-line))
                              (:operations facts))
        message-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id id-scope file-id path
                                            :asyncapi-message label source-line))
                            (:messages facts))
        schema-nodes (mapv (fn [{:keys [label source-line]}]
                             (generic-node run-id id-scope file-id path
                                           :asyncapi-schema label source-line))
                           (:schemas facts))
        trait-nodes (mapv (fn [{:keys [label source-line]}]
                            (generic-node run-id id-scope file-id path
                                          :asyncapi-operation-trait
                                          label
                                          source-line))
                          (:operation-traits facts))
        binding-nodes (mapv (fn [{:keys [label source-line]}]
                              (generic-node run-id id-scope file-id path
                                            :asyncapi-binding label source-line))
                            (:bindings facts))
        header-nodes (mapv (fn [{:keys [label source-line]}]
                             (generic-node run-id id-scope file-id path
                                           :asyncapi-header label source-line))
                           (remove nil? (:headers facts)))
        correlation-id-nodes (mapv (fn [{:keys [label source-line]}]
                                     (generic-node run-id id-scope file-id path
                                                   :asyncapi-correlation-id
                                                   label
                                                   source-line))
                                   (remove nil? (:correlation-ids facts)))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id spec-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           (concat server-nodes
                                   channel-nodes
                                   message-nodes
                                   schema-nodes
                                   trait-nodes
                                   binding-nodes
                                   header-nodes
                                   correlation-id-nodes))
        channel-id-by-label (into {} (map (juxt :label :xt/id)) channel-nodes)
        operation-edges (mapv (fn [{:keys [channel label source-line]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (get channel-id-by-label channel)
                                          (node-id id-scope :asyncapi-operation label)
                                          :defines
                                          :extracted
                                          source-line))
                              (:operations facts))
        reference-edges (->> (:operations facts)
                             (mapcat
                              (fn [{:keys [label message-refs trait-refs
                                           source-line]}]
                                (concat
                                 (map (fn [target]
                                        (edge-row run-id
                                                  file-id
                                                  path
                                                  (node-id id-scope
                                                           :asyncapi-operation
                                                           label)
                                                  (node-id id-scope
                                                           :asyncapi-message
                                                           target)
                                                  :references
                                                  :extracted
                                                  source-line))
                                      message-refs)
                                 (map (fn [target]
                                        (edge-row run-id
                                                  file-id
                                                  path
                                                  (node-id id-scope
                                                           :asyncapi-operation
                                                           label)
                                                  (node-id id-scope
                                                           :asyncapi-operation-trait
                                                           target)
                                                  :references
                                                  :extracted
                                                  source-line))
                                      trait-refs))))
                             distinct
                             vec)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        schema-reference-edges (->> (concat (:message-schema-refs facts)
                                            (:schema-refs facts))
                                    (keep
                                     (fn [{:keys [source-kind source-label ref
                                                  source-line]}]
                                       (when-let [schema-name
                                                  (json-ref-tail
                                                   "#/components/schemas/"
                                                   ref)]
                                         (when-let [target-id
                                                    (get schema-id-by-label
                                                         schema-name)]
                                           (edge-row run-id
                                                     file-id
                                                     path
                                                     (node-id id-scope
                                                              (or source-kind
                                                                  :asyncapi-message)
                                                              source-label)
                                                     target-id
                                                     :references
                                                     :extracted
                                                     source-line)))))
                                    distinct
                                    vec)
        chunk-result (extract-text-source run-id file :asyncapi-file)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [spec-node]
                         server-nodes
                         channel-nodes
                         operation-nodes
                         message-nodes
                         schema-nodes
                         trait-nodes
                         binding-nodes
                         header-nodes
                         correlation-id-nodes))
     :edges (vec (concat define-edges
                         operation-edges
                         reference-edges
                         schema-reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))

(defn- json-schema-facts
  [content path]
  (if-let [schema (read-json-map content)]
    (let [root-label (or (get schema json-id-key) (:title schema) path)
          definitions (concat (get schema json-defs-key)
                              (:definitions schema))
          properties (:properties schema)]
      {:root-label root-label
       :definitions (mapv (fn [[definition _]]
                            {:label (json-key-label definition)
                             :source-line 1})
                          definitions)
       :properties (mapv (fn [[property _]]
                           {:label (json-key-label property)
                            :source-line 1})
                         properties)
       :references (->> (json-ref-values schema)
                        distinct
                        (mapv (fn [ref]
                                {:label ref
                                 :source-line 1})))})
    {:root-label path
     :definitions []
     :properties []
     :references []
     :diagnostics [{:stage :parse
                    :line 1
                    :message "JSON Schema extractor could not parse JSON object."}]}))

(defn extract-json-schema
  "Extract declared JSON Schema definitions, properties, and explicit refs."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (json-schema-facts content path)
        schema-node (generic-node run-id id-scope file-id path
                                  :json-schema
                                  (:root-label facts)
                                  1)
        definition-nodes (mapv (fn [{:keys [label source-line]}]
                                 (generic-node run-id id-scope file-id path
                                               :json-schema-definition
                                               label
                                               source-line))
                               (:definitions facts))
        property-nodes (mapv (fn [{:keys [label source-line]}]
                               (generic-node run-id id-scope file-id path
                                             :json-schema-property
                                             label
                                             source-line))
                             (:properties facts))
        reference-nodes (mapv (fn [{:keys [label source-line]}]
                                (generic-node run-id id-scope file-id path
                                              :json-schema-reference
                                              label
                                              source-line))
                              (:references facts))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id schema-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           (concat definition-nodes property-nodes))
        reference-edges (mapv #(edge-row run-id file-id path
                                         (:xt/id schema-node)
                                         (:xt/id %)
                                         :references
                                         :extracted
                                         (:source-line %))
                              reference-nodes)
        chunk-result (extract-text-source run-id file :json-schema-file)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [schema-node]
                         definition-nodes
                         property-nodes
                         reference-nodes))
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))

(def avro-primitive-types
  #{"boolean" "bytes" "double" "float" "int" "long" "null" "string"})

(def avro-named-types
  #{"enum" "fixed" "record"})

(defn- avro-full-name
  [m]
  (let [name (:name m)
        namespace (:namespace m)]
    (when (string? name)
      (if (and (string? namespace)
               (not (str/includes? name ".")))
        (str namespace "." name)
        name))))

(defn- avro-schema-kind
  [schema-type]
  (case schema-type
    "record" :avro-record
    "enum" :avro-enum
    "fixed" :avro-fixed
    :avro-schema))

(declare avro-type-references)

(defn- avro-named-schemas
  [value]
  (cond
    (map? value)
    (let [schema-type (:type value)
          named-schema (when (and (contains? avro-named-types schema-type)
                                  (avro-full-name value))
                         {:kind (avro-schema-kind schema-type)
                          :label (avro-full-name value)
                          :schema value
                          :source-line 1})]
      (cond-> (mapcat avro-named-schemas (vals value))
        named-schema (conj named-schema)))

    (vector? value)
    (mapcat avro-named-schemas value)

    :else
    []))

(defn- avro-record-fields
  [schemas]
  (->> schemas
       (filter #(= :avro-record (:kind %)))
       (mapcat
        (fn [{:keys [label schema]}]
          (->> (:fields schema)
               (keep (fn [field]
                       (when-let [field-name (:name field)]
                         {:label (str label "." field-name)
                          :record-label label
                          :type (:type field)
                          :source-line 1}))))))))

(defn- avro-type-references
  [type-value]
  (cond
    (string? type-value)
    (when-not (or (contains? avro-primitive-types type-value)
                  (contains? avro-named-types type-value))
      [type-value])

    (map? type-value)
    (avro-type-references (:type type-value))

    (vector? type-value)
    (mapcat avro-type-references type-value)

    :else
    []))

(defn- avro-json-facts
  [content]
  (when-let [value (read-json-value content)]
    (let [schemas (->> (avro-named-schemas value)
                       distinct
                       vec)
          fields (vec (avro-record-fields schemas))
          references (->> fields
                          (mapcat (fn [{:keys [label type source-line]}]
                                    (map (fn [target]
                                           {:source-label label
                                            :target-label target
                                            :source-line source-line})
                                         (avro-type-references type))))
                          distinct
                          vec)]
      {:schemas schemas
       :fields fields
       :references references})))

(defn- avro-idl-facts
  [content]
  (let [lines (str/split-lines content)
        protocol (->> lines
                      (map-indexed vector)
                      (some (fn [[idx line]]
                              (when-let [[_ name]
                                         (re-matches
                                          #"^\s*protocol\s+([A-Za-z_][A-Za-z0-9_.]*)\s*\{?.*"
                                          line)]
                                {:kind :avro-protocol
                                 :label name
                                 :source-line (inc idx)}))))
        schemas (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ type-name name]
                                        (re-matches
                                         #"^\s*(record|enum|fixed)\s+([A-Za-z_][A-Za-z0-9_.]*)\b.*"
                                         line)]
                               {:kind (avro-schema-kind type-name)
                                :label name
                                :source-line (inc idx)})))
                     vec)]
    (when (or protocol (seq schemas))
      {:protocol protocol
       :schemas schemas
       :fields []
       :references []})))

(defn- avro-facts
  [content]
  (or (avro-json-facts content)
      (avro-idl-facts content)
      {:schemas []
       :fields []
       :references []
       :diagnostics [{:stage :parse
                      :line 1
                      :message "Avro extractor did not find JSON schema or IDL declarations."}]}))

(defn extract-avro
  "Extract declared Avro schemas, fields, and explicit type references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (avro-facts content)
        root-node (or (when-let [{:keys [kind label source-line]} (:protocol facts)]
                        (generic-node run-id id-scope file-id path kind label source-line))
                      (generic-node run-id id-scope file-id path :avro-file path 1))
        schema-nodes (mapv (fn [{:keys [kind label source-line]}]
                             (generic-node run-id id-scope file-id path
                                           kind
                                           label
                                           source-line))
                           (:schemas facts))
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (generic-node run-id id-scope file-id path
                                          :avro-field
                                          label
                                          source-line))
                          (:fields facts))
        reference-nodes (->> (:references facts)
                             (map :target-label)
                             distinct
                             (mapv #(generic-node run-id id-scope file-id path
                                                  :avro-reference
                                                  %
                                                  1)))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id root-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           schema-nodes)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        field-edges (mapv (fn [{:keys [record-label label source-line]}]
                            (edge-row run-id
                                      file-id
                                      path
                                      (get schema-id-by-label record-label)
                                      (node-id id-scope :avro-field label)
                                      :defines
                                      :extracted
                                      source-line))
                          (:fields facts))
        reference-edges (mapv (fn [{:keys [source-label target-label source-line]}]
                                (edge-row run-id
                                          file-id
                                          path
                                          (node-id id-scope :avro-field source-label)
                                          (node-id id-scope
                                                   :avro-reference
                                                   target-label)
                                          :references
                                          :extracted
                                          source-line))
                              (:references facts))
        chunk-result (extract-text-source run-id file :avro-file)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [root-node] schema-nodes field-nodes reference-nodes))
     :edges (vec (concat define-edges field-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))

(def graphql-definition-kinds
  #{"directive" "enum" "fragment" "input" "interface" "mutation" "query"
    "scalar" "schema" "subscription" "type" "union"})

(defn- graphql-definition-kind
  [kind]
  (case kind
    "type" :graphql-type
    "interface" :graphql-interface
    "input" :graphql-input
    "enum" :graphql-enum
    "union" :graphql-union
    "scalar" :graphql-scalar
    "directive" :graphql-directive
    "schema" :graphql-schema
    ("query" "mutation" "subscription") :graphql-operation
    "fragment" :graphql-fragment
    :graphql-definition))

(defn- graphql-definition-name
  [kind name]
  (case kind
    "directive" (str "@" name)
    "schema" "schema"
    name))

(defn- graphql-definition-line
  [idx line]
  (or (when-let [[_ kind name]
                 (re-matches #"^\s*(type|interface|input|enum|union|scalar)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind (graphql-definition-kind kind)
         :name (graphql-definition-name kind name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*directive\s+@([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-directive
         :name (str "@" name)
         :source-line (inc idx)})
      (when-let [[_ kind name]
                 (re-matches #"^\s*(query|mutation|subscription)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-operation
         :operation-kind (keyword kind)
         :name name
         :source-line (inc idx)})
      (when-let [[_ name target]
                 (re-matches #"^\s*fragment\s+([A-Za-z_][A-Za-z0-9_]*)\s+on\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-fragment
         :name name
         :target target
         :source-line (inc idx)})
      (when (re-matches #"^\s*schema\s*\{.*" line)
        {:kind :graphql-schema
         :name "schema"
         :source-line (inc idx)})))

(def graphql-reference-exclusions
  #{"Boolean" "Float" "ID" "Int" "String"
    "enum" "extends" "fragment" "implements" "input" "interface" "mutation"
    "on" "query" "scalar" "schema" "subscription" "type" "union"})

(defn- graphql-reference-targets
  [line]
  (let [type-refs (->> (re-seq #"[!\[\]:={|]\s*([A-Z][A-Za-z0-9_]*)\b" line)
                       (map second))
        implements-refs (->> (re-seq #"\bimplements\s+([A-Z][A-Za-z0-9_&\s]*)" line)
                             (map second)
                             (mapcat #(str/split % #"&"))
                             (map str/trim))
        fragment-refs (->> (re-seq #"\.\.\.([A-Za-z_][A-Za-z0-9_]*)" line)
                           (map second))]
    (->> (concat type-refs implements-refs fragment-refs)
         (remove str/blank?)
         (remove graphql-reference-exclusions)
         distinct
         vec)))

(defn- graphql-definition-spans
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (let [definition (when-not current
                         (graphql-definition-line idx line))
            current* (or current
                         (when definition
                           (assoc definition :lines [])))
            depth* (if current*
                     (+ depth (curly-depth-delta line))
                     depth)
            current** (cond-> current*
                        current* (update :lines conj [idx line]))]
        (cond
          (and current** (zero? depth*) (not (str/includes? line "{")))
          (recur (rest remaining) nil 0 (conj out current**))

          (and current** (<= depth* 0) (pos? depth))
          (recur (rest remaining) nil 0 (conj out current**))

          current**
          (recur (rest remaining) current** depth* out)

          :else
          (recur (rest remaining) nil 0 out)))
      (cond-> out current (conj current)))))

(defn- graphql-reference-edges
  [run-id id-scope file-id path definitions]
  (->> definitions
       (mapcat
        (fn [{:keys [kind name target source-line lines]}]
          (let [source-id (node-id id-scope kind name)
                inline-targets (cond-> []
                                 target (conj target))
                line-targets (->> lines
                                  (mapcat (comp graphql-reference-targets second)))]
            (map (fn [target]
                   (edge-row run-id
                             file-id
                             path
                             source-id
                             (node-id id-scope :graphql-reference target)
                             :references
                             :extracted
                             source-line))
                 (distinct (concat inline-targets line-targets))))))
       distinct
       vec))

(def graphql-field-parent-kinds
  #{:graphql-type :graphql-interface :graphql-input})

(defn- graphql-field-facts
  [definitions]
  (->> definitions
       (filter #(contains? graphql-field-parent-kinds (:kind %)))
       (mapcat
        (fn [{:keys [kind name lines]}]
          (keep (fn [[idx line]]
                  (when-let [[_ field-name]
                             (re-matches
                              #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*:\s*.+"
                              line)]
                    {:kind :graphql-field
                     :label (str name "." field-name)
                     :parent-kind kind
                     :parent-label name
                     :source-line (inc idx)
                     :line line
                     :references (graphql-reference-targets line)}))
                lines)))
       distinct
       vec))

(defn- graphql-enum-value-facts
  [definitions]
  (->> definitions
       (filter #(= :graphql-enum (:kind %)))
       (mapcat
        (fn [{:keys [name lines]}]
          (keep (fn [[idx line]]
                  (let [trimmed (str/trim line)]
                    (when (and (not (str/blank? trimmed))
                               (not (str/starts-with? trimmed "#"))
                               (not (str/includes? trimmed "{"))
                               (not (str/includes? trimmed "}")))
                      (when-let [[_ value-name]
                                 (re-matches
                                  #"^([A-Za-z_][A-Za-z0-9_]*)(?:\s+@.*)?$"
                                  trimmed)]
                        {:kind :graphql-enum-value
                         :label (str name "." value-name)
                         :parent-label name
                         :source-line (inc idx)}))))
                lines)))
       distinct
       vec))

(defn- graphql-field-reference-edges
  [run-id id-scope file-id path field-facts]
  (->> field-facts
       (mapcat
        (fn [{:keys [label source-line references]}]
          (map (fn [target]
                 (edge-row run-id
                           file-id
                           path
                           (node-id id-scope :graphql-field label)
                           (node-id id-scope :graphql-reference target)
                           :references
                           :extracted
                           source-line))
               references)))
       distinct
       vec))

(defn extract-graphql
  "Extract declared GraphQL schema and operation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        spec-node (generic-node run-id id-scope file-id path :graphql-file path 1)
        definitions (graphql-definition-spans lines)
        field-facts (graphql-field-facts definitions)
        enum-value-facts (graphql-enum-value-facts definitions)
        definition-nodes (mapv (fn [{:keys [kind name source-line]}]
                                 (generic-node run-id
                                               id-scope
                                               file-id
                                               path
                                               kind
                                               name
                                               source-line))
                               definitions)
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (generic-node run-id
                                          id-scope
                                          file-id
                                          path
                                          :graphql-field
                                          label
                                          source-line))
                          field-facts)
        enum-value-nodes (mapv (fn [{:keys [label source-line]}]
                                 (generic-node run-id
                                               id-scope
                                               file-id
                                               path
                                               :graphql-enum-value
                                               label
                                               source-line))
                               enum-value-facts)
        reference-nodes (->> (concat (mapcat :references field-facts)
                                     (mapcat (fn [{:keys [target lines]}]
                                               (distinct
                                                (concat (when target [target])
                                                        (mapcat (comp graphql-reference-targets second)
                                                                lines))))
                                             definitions))
                             distinct
                             (mapv #(generic-node run-id
                                                  id-scope
                                                  file-id
                                                  path
                                                  :graphql-reference
                                                  %
                                                  1)))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id spec-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           definition-nodes)
        definition-id-by-label (into {} (map (juxt :label :xt/id)) definition-nodes)
        field-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                   (edge-row run-id
                                             file-id
                                             path
                                             (get definition-id-by-label parent-label)
                                             (node-id id-scope :graphql-field label)
                                             :defines
                                             :extracted
                                             source-line))
                                 field-facts)
        enum-value-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                        (edge-row run-id
                                                  file-id
                                                  path
                                                  (get definition-id-by-label parent-label)
                                                  (node-id id-scope
                                                           :graphql-enum-value
                                                           label)
                                                  :defines
                                                  :extracted
                                                  source-line))
                                      enum-value-facts)
        reference-edges (graphql-reference-edges run-id id-scope file-id path definitions)
        field-reference-edges (graphql-field-reference-edges run-id
                                                             id-scope
                                                             file-id
                                                             path
                                                             field-facts)
        chunk-result (extract-text-source run-id file :graphql-file)
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
                                definitions)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "GraphQL")]
    {:nodes (vec (concat [spec-node]
                         definition-nodes
                         field-nodes
                         enum-value-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         field-define-edges
                         enum-value-define-edges
                         reference-edges
                         field-reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))

(defn- protobuf-package-name
  [path lines]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                                  line)))
            lines)
      (source-module-name path)))

(defn- protobuf-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+(?:public\s+|weak\s+)?\"([^\"]+)\"\s*;\s*$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))

(defn- protobuf-definition-kind
  [kind]
  (case kind
    "message" :protobuf-message
    "enum" :protobuf-enum
    "service" :protobuf-service
    "rpc" :protobuf-rpc
    :protobuf-definition))

(defn- protobuf-top-level-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(message|enum|service)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*"
                         line)]
    {:kind (protobuf-definition-kind kind)
     :name name
     :source-line (inc idx)}))

(defn- protobuf-definition-spans
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (let [definition (when-not current
                         (protobuf-top-level-line idx line))
            current* (or current
                         (when definition
                           (assoc definition :lines [])))
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
      (cond-> out current (conj current)))))

(defn- protobuf-rpcs
  [service]
  (->> (:lines service)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ name request response]]
                 {:kind :protobuf-rpc
                  :name (str (:name service) "." name)
                  :request request
                  :response response
                  :source-line (inc idx)})
               (re-seq #"\brpc\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)\s+returns\s+\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)"
                       line))))
       vec))

(def protobuf-scalar-types
  #{"bool" "bytes" "double" "fixed32" "fixed64" "float" "int32" "int64"
    "sfixed32" "sfixed64" "sint32" "sint64" "string" "uint32" "uint64"})

(defn- protobuf-normalize-reference-target
  [target]
  (str/replace target #"^\." ""))

(defn- protobuf-field-facts
  [definitions]
  (->> definitions
       (filter #(= :protobuf-message (:kind %)))
       (mapcat
        (fn [{message-name :name lines :lines}]
          (keep (fn [[idx line]]
                  (or
                   (when-let [[_ key-type value-type field-name]
                              (re-matches
                               #"^\s*map\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*,\s*(\.?[A-Za-z_][A-Za-z0-9_.]*)\s*>\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*[0-9]+.*"
                               line)]
                     (let [references (->> [key-type value-type]
                                           (remove #(contains?
                                                     protobuf-scalar-types
                                                     %))
                                           (map protobuf-normalize-reference-target)
                                           distinct
                                           vec)]
                       {:kind :protobuf-field
                        :label (str message-name "." field-name)
                        :parent-label message-name
                        :field-type (str "map<" key-type "," value-type ">")
                        :source-line (inc idx)
                        :references references}))
                   (when-let [[_ field-type field-name]
                              (re-matches
                               #"^\s*(?:(?:repeated|optional|required)\s+)?(\.?[A-Za-z_][A-Za-z0-9_.]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*[0-9]+.*"
                               line)]
                     (let [target (protobuf-normalize-reference-target field-type)]
                       {:kind :protobuf-field
                        :label (str message-name "." field-name)
                        :parent-label message-name
                        :field-type field-type
                        :source-line (inc idx)
                        :references (if (contains? protobuf-scalar-types target)
                                      []
                                      [target])}))))
                lines)))
       distinct
       vec))

(defn- protobuf-enum-value-facts
  [definitions]
  (->> definitions
       (filter #(= :protobuf-enum (:kind %)))
       (mapcat
        (fn [{enum-name :name lines :lines}]
          (keep (fn [[idx line]]
                  (when-let [[_ value-name]
                             (re-matches
                              #"^\s*([A-Z][A-Z0-9_]*)\s*=\s*[0-9]+\s*;.*"
                              line)]
                    {:kind :protobuf-enum-value
                     :label (str enum-name "." value-name)
                     :parent-label enum-name
                     :source-line (inc idx)}))
                lines)))
       distinct
       vec))

(defn- protobuf-field-reference-targets
  [definition]
  (->> (:lines definition)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ _label field-type]]
                 {:target field-type
                  :source-line (inc idx)})
               (re-seq #"^\s*(?:(repeated|optional|required)\s+)?([A-Za-z_][A-Za-z0-9_.]*)\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*[0-9]+"
                       line))))
       (remove #(contains? protobuf-scalar-types (:target %)))
       distinct
       vec))

(defn- protobuf-field-reference-edges
  [run-id id-scope file-id path package-name field-facts]
  (->> field-facts
       (mapcat
        (fn [{:keys [label source-line references]}]
          (map (fn [target]
                 (edge-row run-id
                           file-id
                           path
                           (node-id id-scope
                                    :protobuf-field
                                    (str package-name "/" label))
                           (node-id id-scope :protobuf-reference target)
                           :references
                           :extracted
                           source-line))
               references)))
       distinct
       vec))

(defn- protobuf-reference-edges
  [run-id id-scope file-id path package-name definitions rpcs]
  (let [field-edges (->> definitions
                         (mapcat
                          (fn [{:keys [kind name] :as definition}]
                            (let [source-id (node-id id-scope
                                                     kind
                                                     (str package-name "/" name))]
                              (map (fn [{:keys [target source-line]}]
                                     (edge-row run-id
                                               file-id
                                               path
                                               source-id
                                               (node-id id-scope :protobuf-reference target)
                                               :references
                                               :extracted
                                               source-line))
                                   (protobuf-field-reference-targets definition))))))
        rpc-edges (->> rpcs
                       (mapcat
                        (fn [{:keys [name request response source-line]}]
                          (let [source-id (node-id id-scope
                                                   :protobuf-rpc
                                                   (str package-name "/" name))]
                            [(edge-row run-id
                                       file-id
                                       path
                                       source-id
                                       (node-id id-scope :protobuf-reference request)
                                       :references
                                       :extracted
                                       source-line)
                             (edge-row run-id
                                       file-id
                                       path
                                       source-id
                                       (node-id id-scope :protobuf-reference response)
                                       :references
                                       :extracted
                                       source-line)]))))]
    (->> (concat field-edges rpc-edges)
         distinct
         vec)))

(defn extract-protobuf
  "Extract declared Protobuf packages, messages, services, RPCs, and references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        package-name (protobuf-package-name path lines)
        package-node (namespace-node run-id id-scope file-id path package-name)
        definitions (protobuf-definition-spans lines)
        rpcs (->> definitions
                  (filter #(= :protobuf-service (:kind %)))
                  (mapcat protobuf-rpcs)
                  vec)
        field-facts (protobuf-field-facts definitions)
        enum-value-facts (protobuf-enum-value-facts definitions)
        definition-nodes (mapv (fn [{:keys [kind name source-line]}]
                                 (generic-node run-id
                                               id-scope
                                               file-id
                                               path
                                               kind
                                               (str package-name "/" name)
                                               source-line))
                               definitions)
        rpc-nodes (mapv (fn [{:keys [kind name source-line]}]
                          (generic-node run-id
                                        id-scope
                                        file-id
                                        path
                                        kind
                                        (str package-name "/" name)
                                        source-line))
                        rpcs)
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (generic-node run-id
                                          id-scope
                                          file-id
                                          path
                                          :protobuf-field
                                          (str package-name "/" label)
                                          source-line))
                          field-facts)
        enum-value-nodes (mapv (fn [{:keys [label source-line]}]
                                 (generic-node run-id
                                               id-scope
                                               file-id
                                               path
                                               :protobuf-enum-value
                                               (str package-name "/" label)
                                               source-line))
                               enum-value-facts)
        reference-nodes (->> (concat (mapcat :references field-facts)
                                     (mapcat (fn [{:keys [request response]}]
                                               [request response])
                                             rpcs)
                                     (mapcat (fn [definition]
                                               (map :target
                                                    (protobuf-field-reference-targets
                                                     definition)))
                                             definitions))
                             (remove str/blank?)
                             (map protobuf-normalize-reference-target)
                             distinct
                             (mapv #(generic-node run-id
                                                  id-scope
                                                  file-id
                                                  path
                                                  :protobuf-reference
                                                  %
                                                  1)))
        define-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id package-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           (concat definition-nodes rpc-nodes))
        definition-id-by-label (into {} (map (juxt :label :xt/id)) definition-nodes)
        field-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                   (edge-row run-id
                                             file-id
                                             path
                                             (get definition-id-by-label
                                                  (str package-name "/" parent-label))
                                             (node-id id-scope
                                                      :protobuf-field
                                                      (str package-name "/" label))
                                             :defines
                                             :extracted
                                             source-line))
                                 field-facts)
        enum-value-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                        (edge-row run-id
                                                  file-id
                                                  path
                                                  (get definition-id-by-label
                                                       (str package-name "/"
                                                            parent-label))
                                                  (node-id id-scope
                                                           :protobuf-enum-value
                                                           (str package-name "/"
                                                                label))
                                                  :defines
                                                  :extracted
                                                  source-line))
                                      enum-value-facts)
        import-edges (mapv #(edge-row run-id file-id path
                                      (:xt/id package-node)
                                      (node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (protobuf-imports lines))
        reference-edges (protobuf-reference-edges run-id
                                                  id-scope
                                                  file-id
                                                  path
                                                  package-name
                                                  definitions
                                                  rpcs)
        field-reference-edges (protobuf-field-reference-edges run-id
                                                              id-scope
                                                              file-id
                                                              path
                                                              package-name
                                                              field-facts)
        chunk-result (extract-text-source run-id file :protobuf-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line lines]}]
                                  (source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str package-name "/" name)
                                   kind
                                   source-line
                                   (str/join "\n" (map second lines))))
                                definitions)
        diagnostics (curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "Protobuf")]
    {:nodes (vec (concat [package-node]
                         definition-nodes
                         rpc-nodes
                         field-nodes
                         enum-value-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         field-define-edges
                         enum-value-define-edges
                         import-edges
                         reference-edges
                         field-reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (normalize-extraction
   (case (:kind file)
     :code (extract-code run-id file)
     :go (extract-go run-id file)
     :java (if (parser-worker-enabled? :java)
             (extract-java-worker run-id file)
             (extract-java run-id file))
     :groovy (extract-groovy run-id file)
     :kotlin (extract-kotlin run-id file)
     :swift (extract-swift run-id file)
     :dotnet (if (parser-worker-enabled? :dotnet)
               (extract-dotnet-worker run-id file)
               (extract-dotnet run-id file))
     :ruby (extract-ruby run-id file)
     :cpp (extract-cpp run-id file)
     :objective-c (extract-objective-c run-id file)
     :dart (extract-dart run-id file)
     :scala (extract-scala run-id file)
     :elixir (extract-elixir run-id file)
     :erlang (extract-erlang run-id file)
     :lua (extract-lua run-id file)
     :r (extract-r run-id file)
     :julia (extract-julia run-id file)
     :ocaml (extract-ocaml run-id file)
     :perl (extract-perl run-id file)
     :haskell (extract-haskell run-id file)
     :odin (extract-odin run-id file)
     :zig (extract-zig run-id file)
     :apple-config (extract-apple-config run-id file)
     :prisma (extract-prisma run-id file)
     :dbt (extract-dbt run-id file)
     :data-science (extract-data-science run-id file)
     :db-config (extract-db-config run-id file)
     :codegen-config (extract-codegen-config run-id file)
     :test-config (extract-test-config run-id file)
     :quality-config (extract-quality-config run-id file)
     :editor-config (extract-editor-config run-id file)
     :release-config (extract-release-config run-id file)
     :web-framework (extract-web-framework run-id file)
     :workflow-orchestration (extract-workflow-orchestration run-id file)
     :observability-config (extract-observability-config run-id file)
     :tool-config (extract-tool-config run-id file)
     :ops-config (extract-ops-config run-id file)
     :astro (extract-astro run-id file)
     :php (extract-php run-id file)
     :notebook (extract-notebook run-id file)
     :devcontainer (extract-devcontainer run-id file)
     :kustomize (extract-kustomize run-id file)
     :pre-commit-config (extract-pre-commit-config run-id file)
     :codeowners (extract-codeowners run-id file)
     :task-runner (extract-task-runner run-id file)
     :starlark (extract-starlark run-id file)
     :tool-version-config (extract-tool-version-config run-id file)
     :storybook (extract-storybook run-id file)
     :docs-config (extract-docs-config run-id file)
     :governance (extract-governance run-id file)
     :sbom (extract-sbom run-id file)
     :vue (extract-sfc run-id file)
     :svelte (extract-sfc run-id file)
     :ci (extract-ci run-id file)
     :build (extract-build run-id file)
     :javascript (extract-js-family run-id file)
     :typescript (extract-js-family run-id file)
     :python (extract-python run-id file)
     :rust (extract-rust run-id file)
     :style (extract-style run-id file)
     :shell (extract-shell run-id file)
     :sql (extract-sql run-id file)
     :db-migration (extract-db-migration run-id file)
     :terraform (extract-terraform run-id file)
     :openapi (extract-openapi run-id file)
     :asyncapi (extract-asyncapi run-id file)
     :json-schema (extract-json-schema run-id file)
     :avro (extract-avro run-id file)
     :graphql (extract-graphql run-id file)
     :protobuf (extract-protobuf run-id file)
     :yaml (extract-yaml run-id file)
     :docker (extract-docker run-id file)
     :procfile (extract-procfile run-id file)
     :compose (extract-compose run-id file)
     :helm (extract-helm run-id file)
     :gettext (extract-gettext run-id file)
     :svg (extract-svg run-id file)
     :xml (extract-xml run-id file)
     :html (extract-text-source run-id file :html-file)
     :env (extract-env run-id file)
     :text (extract-text-source run-id file :text-file)
     :unknown (extract-text-source run-id file :unknown-file)
     (:image-asset :font-asset :gettext-binary) (extract-binary-asset run-id file)
     :doc (extract-doc run-id file)
     :edn (extract-edn run-id file)
     :config (extract-edn run-id file)
     :manifest (extract-manifest run-id file)
     :dependency-lock (extract-dependency-lock run-id file)
     (empty-extraction))))
