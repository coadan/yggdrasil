(ns agraph.extract
  "Deterministic extraction from supported source, config, and document files."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def definition-symbols
  '#{def defonce defn defn- defmacro defmulti defmethod defprotocol defrecord
     deftype deftest})

(def public-definition-symbols
  '#{def defonce defn defmacro defmulti defmethod defprotocol defrecord deftype})

(def extraction-buckets
  [:nodes :edges :chunks :diagnostics])

(def extractor-contract-version
  "agraph.extract/v1")

(def extractor-versions
  {:code "clojure/v4"
   :go "go/v2"
   :javascript "javascript/v1"
   :typescript "typescript/v1"
   :python "python/v3"
   :rust "rust/v2"
   :style "style/v1"
   :sql "sql/v2"
   :terraform "terraform/v1"
   :openapi "openapi/v1"
   :doc "markdown/v1"
   :edn "edn/v1"
   :config "config/v1"
   :yaml "none/v1"
   :manifest "none/v1"
   :docker "none/v1"
   :compose "none/v1"
   :helm "none/v1"
   :shell "none/v1"})

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

(defn extractor-fingerprint
  "Return the stable extractor fingerprint for a file record."
  [file]
  (let [kind (:kind file)]
    (str "extractor:"
         (hash/short-hash [extractor-contract-version
                           kind
                           (get extractor-versions kind "none/v1")]))))

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
  (case sym
    defn :var
    defn- :var
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
  (contains? public-definition-symbols sym))

(defn- definition-node
  [run-id id-scope file-id path ns-name {:keys [def-sym name line]}]
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
     :public? (definition-public? def-sym)
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

(defn- definition-forms
  [content]
  (let [pattern (re-pattern (str "^\\s*\\(("
                                 (->> definition-symbols
                                      (map name)
                                      (sort-by count >)
                                      (str/join "|"))
                                 ")\\s+("
                                 clojure-symbol-pattern
                                 ")"))]
    (->> (str/split-lines content)
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ def-sym name] (re-find pattern line)]
                   {:def-sym def-sym
                    :name name
                    :line (inc idx)})))
         vec)))

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
          chunk-text (str/join "\n" (take 80 (str/split-lines content)))
          chunk {:xt/id (chunk-id id-scope path ns-name 1)
                 :file-id file-id
                 :path path
                 :kind :code-file
                 :label ns-name
                 :text chunk-text
                 :tokens (text/tokenize (str ns-name "\n" chunk-text))
                 :source-line 1
                 :active? true
                 :run-id run-id}]
      {:nodes (into [ns-node] defs)
       :edges (vec (concat contains-edges require-edges))
       :chunks [chunk]
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

(defn extract-doc
  "Extract Markdown chunks."
  [run-id {:keys [id-scope file-id path content]}]
  (let [lines (str/split-lines content)
        total-lines (count lines)
        close-chunk (fn [out current end-line]
                      (cond-> out
                        (seq (:lines current))
                        (conj (assoc current :end-line end-line))))
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
    {:nodes []
     :edges []
     :chunks (mapv (fn [{:keys [label start-line lines] :as chunk}]
                     (let [text (str/join "\n" lines)]
                       {:xt/id (chunk-id id-scope path label start-line)
                        :file-id file-id
                        :path path
                        :kind :markdown
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

(defn- rust-module-name
  [path]
  (-> path
      (str/replace #"\.rs$" "")
      (str/replace #"/src/(lib|main)$" "")
      (str/replace #"/src/" "::")
      (str/replace #"/" "::")
      (str/replace #"-" "_")))

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
              (node-id id-scope :namespace (str (rust-module-name path) "::" module-name))
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

(defn extract-rust
  "Extract graph rows from a Rust source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (rust-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        defs (->> lines
                  (map-indexed rust-definition-line)
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
        chunk-text (str/join "\n" (take 80 lines))
        chunk {:xt/id (chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind :rust-file
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges module-edges use-edges call-edges))
     :chunks [chunk]
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
  [path idx line]
  (or (when-let [[_ receiver name]
                 (re-matches #"^\s*func\s+(?:\(([^)]*)\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                             line)]
        (let [receiver-type (go-receiver-type receiver)]
          {:kind (go-definition-kind path "func" name receiver-type)
           :name (if receiver-type
                   (str receiver-type "." name)
                   name)
           :public? (go-public? name)
           :source-line (inc idx)}))
      (when-let [[_ name kind]
                 (re-matches #"^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)?.*"
                             line)]
        {:kind (go-definition-kind path kind name nil)
         :name name
         :public? (go-public? name)
         :source-line (inc idx)})
      (when-let [[_ kind name]
                 (re-matches #"^\s*(const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind (go-definition-kind path kind name nil)
         :name name
         :public? (go-public? name)
         :source-line (inc idx)})))

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
        defs (->> lines
                  (map-indexed #(go-definition-line path %1 %2))
                  (keep identity)
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
        chunk-text (str/join "\n" (take 100 lines))
        chunk {:xt/id (chunk-id id-scope path namespace-name 1)
               :file-id file-id
               :path path
               :kind :go-file
               :label namespace-name
               :text chunk-text
               :tokens (text/tokenize (str namespace-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges call-edges))
     :chunks [chunk]
     :diagnostics []}))

(defn- python-module-name
  [path]
  (let [module (-> path
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
  [run-id {:keys [id-scope file-id path absolute-path content]}]
  (let [module-name (python-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        facts (python-facts absolute-path)
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
      (str/replace #"\.(mjs|cjs|jsx|js|tsx|ts|scss|css|sql)$" "")
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
      (str/replace #"\.(mjs|cjs|jsx|js|tsx|ts|scss|css|json)$" "")))

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
               :run-id run-id}]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks [chunk]
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

(defn extract-sql
  "Extract declared SQL schema facts from a static SQL file."
  [run-id {:keys [id-scope file-id path content kind] :as file}]
  (let [lines (vec (str/split-lines content))
        declarations (sql-table-ranges lines)
        nodes (mapv (fn [{:keys [kind name source-line]}]
                      (generic-node run-id id-scope file-id path kind name source-line))
                    declarations)
        edges (sql-table-edges run-id id-scope file-id path declarations lines)
        chunk-result (extract-text-source run-id file :sql-file)]
    (assoc chunk-result
           :nodes nodes
           :edges edges
           :chunks (mapv #(assoc % :file-kind kind) (:chunks chunk-result)))))

(defn- block-start
  [idx line]
  (or (when-let [[_ block-type resource-type name]
                 (re-matches #"^\s*(resource|data)\s+\"([^\"]+)\"\s+\"([^\"]+)\"\s*\{\s*$"
                             line)]
        {:block-type block-type
         :kind :terraform-resource
         :name (str resource-type "." name)
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
                      (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_-]*)\b"
                              line))))
       distinct
       vec))

(defn- terraform-chunks
  [run-id id-scope file-id path blocks]
  (->> blocks
       (filter #(contains? #{"variable" "output"} (:block-type %)))
       (mapv (fn [{:keys [block-type name source-line lines]}]
               (let [label (str block-type "." name)
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
         :name name
         :source-line (inc idx)})
      (block-start idx line)))

(defn- terraform-blocks
  [lines]
  (hcl-blocks lines terraform-extra-block-start))

(defn extract-terraform
  "Extract declared Terraform/HCL blocks and explicit references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        file-node (generic-node run-id id-scope file-id path :terraform-file path 1)
        blocks (terraform-blocks lines)
        graph-blocks (filter :kind blocks)
        nodes (into [file-node]
                    (map (fn [{:keys [kind name source-line]}]
                           (generic-node run-id id-scope file-id path kind name source-line)))
                    graph-blocks)
        node-by-name (into {} (map (juxt :label :xt/id)) nodes)
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
                                                 (when-let [target-id (get node-by-name target)]
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
        chunk-result (extract-text-source run-id file :terraform-file)
        chunks (vec (concat (:chunks chunk-result)
                            (terraform-chunks run-id id-scope file-id path blocks)))]
    {:nodes nodes
     :edges (vec (concat define-edges reference-edges))
     :chunks chunks
     :diagnostics []}))

(def openapi-methods
  #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})

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
    {:paths (->> lines
                 (map-indexed vector)
                 (keep (fn [[idx line]]
                         (when-let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$"
                                                         line)]
                           {:path path
                            :source-line (inc idx)})))
                 vec)
     :operations (->> lines
                      (map-indexed vector)
                      (reduce (fn [{:keys [current-path] :as state} [idx line]]
                                (if-let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$" line)]
                                  (assoc state :current-path path)
                                  (if-let [[_ method] (re-matches #"^\s{4}([a-z]+):\s*$" line)]
                                    (if (and current-path (contains? openapi-methods method))
                                      (update state :operations conj
                                              {:path current-path
                                               :method method
                                               :source-line (inc idx)})
                                      state)
                                    state)))
                              {:current-path nil
                               :operations []})
                      :operations)
     :schemas (->> lines
                   (map-indexed vector)
                   (reduce (fn [{:keys [in-schemas?] :as state} [idx line]]
                             (cond
                               (re-matches #"^\s{2}schemas:\s*$" line)
                               (assoc state :in-schemas? true)

                               (and in-schemas?
                                    (re-matches #"^\s{2}[A-Za-z0-9_.-]+:\s*$" line))
                               (assoc state :in-schemas? false)

                               :else
                               (if-let [[_ name] (and in-schemas?
                                                      (re-matches
                                                       #"^\s{4}([A-Za-z0-9_.-]+):\s*$"
                                                       line))]
                                 (update state :schemas conj
                                         {:name name
                                          :source-line (inc idx)})
                                 state)))
                           {:in-schemas? false
                            :schemas []})
                   :schemas)}))

(defn- openapi-json-facts
  [spec]
  (when spec
    {:paths (mapv (fn [path]
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
                                             :source-line 1})))))
                      vec)
     :schemas (mapv (fn [schema]
                      {:name (name schema)
                       :source-line 1})
                    (keys (get-in spec [:components :schemas])))}))

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
        path-nodes (mapv (fn [{:keys [path source-line]}]
                           (generic-node run-id id-scope file-id (:path file)
                                         :api-path path source-line))
                         (:paths facts))
        operation-nodes (mapv (fn [{:keys [path method operation-id source-line]}]
                                (generic-node run-id id-scope file-id (:path file)
                                              :api-operation
                                              (str (str/upper-case method)
                                                   " "
                                                   path
                                                   (when operation-id
                                                     (str " " operation-id)))
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
        path-id-by-label (into {} (map (juxt :label :xt/id)) path-nodes)
        operation-edges (mapv (fn [{:keys [path method operation-id source-line]}]
                                (edge-row run-id
                                          file-id
                                          (:path file)
                                          (get path-id-by-label path)
                                          (node-id id-scope
                                                   :api-operation
                                                   (str (str/upper-case method)
                                                        " "
                                                        path
                                                        (when operation-id
                                                          (str " " operation-id))))
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
        chunk-result (extract-text-source run-id file :openapi-file)
        diagnostics (mapv #(diagnostic-row run-id
                                           file-id
                                           path
                                           (:stage %)
                                           (:line %)
                                           (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [spec-node] path-nodes operation-nodes schema-nodes))
     :edges (vec (concat path-edges operation-edges schema-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (normalize-extraction
   (case (:kind file)
     :code (extract-code run-id file)
     :go (extract-go run-id file)
     :javascript (extract-js-family run-id file)
     :typescript (extract-js-family run-id file)
     :python (extract-python run-id file)
     :rust (extract-rust run-id file)
     :style (extract-text-source run-id file :style-file)
     :sql (extract-sql run-id file)
     :terraform (extract-terraform run-id file)
     :openapi (extract-openapi run-id file)
     :doc (extract-doc run-id file)
     :edn (extract-edn run-id file)
     :config (extract-edn run-id file)
     (empty-extraction))))
