(ns agraph.extract.source-scripting
  (:require [agraph.extract.common :as common]
            [agraph.text :as text]
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
