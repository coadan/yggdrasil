(ns agraph.extract.source-misc
  (:require [agraph.extract.common :as common]
            [agraph.fs :as fs]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.string :as str]))

(defn- lua-module-name
  [path]
  (common/source-module-name path))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed lua-definition-line)
                       (keep identity)
                       vec)
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
                            (lua-requires lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :lua-file
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
(defn- r-module-name
  [path]
  (common/source-module-name path))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed r-definition-line)
                       (keep identity)
                       vec)
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
                           (r-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :r-file
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
        diagnostics (common/curly-balance-diagnostics run-id file-id path content "R")]
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
      (common/source-module-name path)))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed julia-definition-line)
                       (keep identity)
                       vec)
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
                           (julia-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :julia-file
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
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))
(defn- ocaml-module-name
  [path]
  (common/source-module-name path))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed ocaml-definition-line)
                       (keep identity)
                       vec)
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
                           (->> lines
                                (map-indexed ocaml-import-targets)
                                (mapcat identity)
                                distinct))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :ocaml-file
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
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))
(defn- perl-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_:]*)\s*;.*"
                                  line)))
            (str/split-lines content))
      (common/source-module-name path)))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed perl-definition-line)
                       (keep identity)
                       vec)
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
                           (perl-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :perl-file
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
        diagnostics (common/curly-balance-diagnostics run-id file-id path content "Perl")]
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
      (common/source-module-name path)))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed haskell-definition-line)
                       (keep identity)
                       distinct
                       vec)
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
                           (haskell-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :haskell-file
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
      (common/source-module-name path)))
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
  (let [parsed (common/read-json-map content)
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
    (common/extract-format-facts run-id file :odin-config-file :odin-config-file
                          (odin-config-facts content path))
    (let [module-name (odin-module-name path content)
          ns-node (common/namespace-node run-id id-scope file-id path module-name)
          package-node (common/generic-node run-id id-scope file-id path
                                     :odin-package module-name 1)
          lines (vec (str/split-lines content))
          def-forms (->> lines
                         (map-indexed odin-definition-line)
                         (keep identity)
                         vec)
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
          foreign-imports (odin-foreign-imports lines)
          foreign-nodes (mapv (fn [{:keys [name target source-line]}]
                                (common/generic-node run-id
                                              id-scope
                                              file-id
                                              path
                                              :foreign-import
                                              (str name ":" target)
                                              source-line))
                              foreign-imports)
          define-edges (mapv #(common/edge-row run-id file-id path
                                        (:xt/id ns-node)
                                        (:xt/id %)
                                        :defines
                                        :extracted
                                        (:source-line %))
                             (concat [package-node] defs foreign-nodes))
          import-edges (mapv #(common/edge-row run-id file-id path
                                        (:xt/id ns-node)
                                        (common/node-id id-scope :namespace (:target %))
                                        :imports
                                        :extracted
                                        (:source-line %))
                             (odin-imports lines))
          foreign-edges (mapv (fn [{:keys [name target source-line]}]
                                (common/edge-row run-id
                                          file-id
                                          path
                                          (common/node-id id-scope :foreign-import (str name ":" target))
                                          (common/node-id id-scope :namespace target)
                                          :imports
                                          :extracted
                                          source-line))
                              foreign-imports)
          chunk (common/source-text-chunk run-id
                                   id-scope
                                   file-id
                                   path
                                   :odin-file
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
          diagnostics (common/curly-balance-diagnostics run-id file-id path content "Odin")]
      {:nodes (vec (concat [ns-node package-node] defs foreign-nodes))
       :edges (vec (concat define-edges import-edges foreign-edges))
       :chunks (into [chunk] definition-chunks)
       :diagnostics diagnostics})))
(defn- zig-module-name
  [path]
  (common/source-module-name path))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed zig-definition-line)
                       (keep identity)
                       vec)
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
                           (zig-imports lines))
        chunk (common/source-text-chunk run-id
                                 id-scope
                                 file-id
                                 path
                                 :zig-file
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
        diagnostics (common/curly-balance-diagnostics run-id file-id path content "Zig")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
