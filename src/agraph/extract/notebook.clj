(ns agraph.extract.notebook
  (:require [agraph.extract.common :as common]
            [charred.api :as json]))

(defn- read-json-value
  [content]
  (try
    (json/read-json content :key-fn keyword)
    (catch Exception _
      nil)))

(defn- json-key-label
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (string? k) k
    :else (str k)))

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
        notebook-node (common/generic-node run-id id-scope file-id path :notebook-file path 1)
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
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         (concat metadata-facts cell-facts))
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id notebook-node)
                                            (common/node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                         (concat metadata-facts cell-facts))
        cell-chunks (mapv (fn [{:keys [label cell-type cell-source]}]
                            (common/source-text-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      (if (= "code" cell-type)
                                                        :notebook-code-cell
                                                        :notebook-markdown-cell)
                                                      label
                                                      cell-source
                                                      120))
                          cell-facts)
        diagnostic (when-not (map? parsed)
                     [(common/diagnostic-row run-id file-id path :parse 1
                                             "Notebook JSON could not be parsed.")])]
    {:nodes (into [notebook-node] fact-nodes)
     :edges fact-edges
     :chunks cell-chunks
     :diagnostics (or diagnostic [])}))
