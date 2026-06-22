(ns ygg.extract.source-beam
  (:require [ygg.extract.common :as common]
            [ygg.text :as text]
            [clojure.string :as str]))

(defn- elixir-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*defmodule\s+([A-Za-z_][A-Za-z0-9_.]*)\s+do\s*$"
                                  line)))
            (str/split-lines content))
      (common/source-module-name path)))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed #(elixir-definition-line module-name %1 %2))
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
                           (elixir-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :elixir-file
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
(defn- erlang-module-name
  [path content]
  (or (some-> (re-find #"(?m)^\s*-module\(([A-Za-z_][A-Za-z0-9_]*)\)\." content)
              second)
      (common/source-module-name path)))
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
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (->> lines
                       (map-indexed erlang-definition-line)
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
                           (erlang-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :erlang-file
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
