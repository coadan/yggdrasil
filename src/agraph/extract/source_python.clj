(ns agraph.extract.source-python
  (:require [agraph.extract.common :as common]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        facts (if (contains? file :parser-worker-facts)
                (:parser-worker-facts file)
                (python-facts absolute-path))
        defs (->> (:definitions facts)
                  (mapv (fn [{:keys [kind name line]}]
                          (let [label (str module-name "/" name)]
                            (cond-> {:xt/id (common/node-id id-scope :symbol label)
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
        define-edges (mapv #(common/edge-row run-id file-id path
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
                                      (common/edge-row run-id file-id path
                                                       (:xt/id ns-node)
                                                       (common/node-id id-scope :namespace target)
                                                       :imports
                                                       :extracted
                                                       (or line 1))))))
                          vec)
        diagnostics (mapv #(common/diagnostic-row run-id
                                                  file-id
                                                  path
                                                  (:stage %)
                                                  (:line %)
                                                  (:message %))
                          (:diagnostics facts))
        chunk-text (str/join "\n" (take 100 (str/split-lines content)))
        chunk {:xt/id (common/chunk-id id-scope path module-name 1)
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
