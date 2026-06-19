(ns agraph.extract.source-native
  (:require [agraph.extract.common :as common]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn- ruby-module-name
  [path]
  (common/source-module-name path))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (ruby-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
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
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        require-edges (mapv #(common/edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (common/node-id id-scope :namespace (:target %))
                                       :imports
                                       :extracted
                                       (:source-line %))
                            (ruby-requires lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :ruby-file
                                 module-name
                                 content
                                 common/source-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (common/source-definition-chunk
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
  (common/source-module-name path))
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           namespace-stack []
           out []]
      (if-let [[idx line] (first remaining)]
        (let [namespace-stack (common/pop-closed-type-scopes namespace-stack depth)
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
                                     :text (or (common/balanced-curly-block content start)
                                               line))))
                          forms)
              delta (common/curly-depth-delta line)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (cpp-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
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
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        include-edges (mapv #(common/edge-row run-id file-id path
                                       (:xt/id ns-node)
                                       (common/node-id id-scope :namespace (:target %))
                                       :imports
                                       :extracted
                                       (:source-line %))
                            (cpp-includes lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :cpp-file
                                 module-name
                                 content
                                 common/source-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (common/curly-balance-diagnostics run-id
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
  (common/source-module-name path))
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
         :text (or (common/balanced-curly-block content start) line)}))))
(defn- objective-c-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (common/line-start-offsets content)]
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (objective-c-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
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
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (common/node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (objective-c-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :objective-c-file
                                 module-name
                                 content
                                 common/source-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (common/curly-balance-diagnostics run-id
                                               file-id
                                               path
                                               content
                                               "Objective-C")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
