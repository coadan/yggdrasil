(ns agraph.extract.source-dotnet
  (:require [agraph.extract.common :as common]
            [agraph.fs :as fs]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn dotnet-module-name
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
      (common/source-module-name path)))
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
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (dotnet-language-definition-forms path content)
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
        using-edges (mapv #(common/edge-row run-id file-id path
                                            (:xt/id ns-node)
                                            (common/node-id id-scope :namespace (:target %))
                                            :imports
                                            :extracted
                                            (:source-line %))
                          (dotnet-usings lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :dotnet-file
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
                                                      ".NET")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges using-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
