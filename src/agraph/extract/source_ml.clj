(ns agraph.extract.source-ml
  (:require [agraph.extract.common :as common]
            [agraph.text :as text]
            [clojure.string :as str]))

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
