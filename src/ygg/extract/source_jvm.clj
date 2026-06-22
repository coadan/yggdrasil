(ns ygg.extract.source-jvm
  (:require [ygg.extract.common :as common]
            [ygg.text :as text]
            [clojure.string :as str]))

(defn java-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_$][A-Za-z0-9_$.]*)\s*;\s*$"
                                  line)))
            (str/split-lines content))
      (common/source-module-name path)))
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
(defn java-import-symbols-by-simple-name
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
(defn java-reference-target-label
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
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
                                     :text (or (common/balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (common/curly-depth-delta line)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        imports (java-imports lines)
        import-symbols (java-import-symbols-by-simple-name imports)
        def-forms (java-definition-forms content)
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
                           imports)
        reference-edges (->> def-forms
                             (mapcat
                              (fn [{:keys [name source-line text]}]
                                (let [source-id (common/node-id id-scope
                                                                :symbol
                                                                (str module-name "/" name))
                                      source-type (first (str/split name #"\."))
                                      target-names (java-type-position-reference-names text)]
                                  (->> target-names
                                       (remove #(= source-type %))
                                       (map (fn [target-name]
                                              (common/edge-row
                                               run-id
                                               file-id
                                               path
                                               source-id
                                               (common/node-id id-scope
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
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :java-file
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
                                                      "Java")]
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
      (common/source-module-name path)))
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
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
                                     :text (or (common/balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (common/curly-depth-delta line)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (groovy-definition-forms content)
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
                           (groovy-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :groovy-file
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
      (common/source-module-name path)))
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
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
                                     :text (or (common/balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (common/curly-depth-delta line)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (kotlin-definition-forms content)
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
                           (kotlin-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :kotlin-file
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
                                                      "Kotlin")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
(defn- swift-module-name
  [path]
  (common/source-module-name path))
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
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
                                     :text (or (common/balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
                          forms)
              delta (common/curly-depth-delta line)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (swift-definition-forms content)
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
                           (swift-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :swift-file
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
                                                      "Swift")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
