(ns ygg.extract.assets-text
  "Text-backed asset and catalog extractors."
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- gettext-messages
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ msgid]
                          (re-matches #"^\s*msgid\s+\"(.*)\"\s*$" line)]
                 (when (seq msgid)
                   {:label msgid
                    :source-line (inc idx)}))))
       distinct
       vec))

(defn extract-gettext
  "Extract gettext message ids as searchable translation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [catalog-node (common/generic-node run-id id-scope file-id path :gettext-catalog path 1)
        messages (gettext-messages content)
        message-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id
                                                   id-scope
                                                   file-id
                                                   path
                                                   :gettext-message
                                                   label
                                                   source-line))
                            messages)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id catalog-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           message-nodes)
        chunk-result (common/extract-text-source run-id file :gettext-file)]
    {:nodes (into [catalog-node] message-nodes)
     :edges define-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(def ^:private max-svg-summary-elements 500)

(defn- svg-summary
  [path elements]
  (str/join "\n"
            (concat [path]
                    (map :label (take max-svg-summary-elements elements)))))

(defn extract-svg
  "Extract SVG id-bearing elements as concrete asset facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [asset-node (common/generic-node run-id id-scope file-id path :svg-file path 1)
        elements (->> (re-seq #"(?is)<([A-Za-z][A-Za-z0-9:_-]*)\b[^>]*\bid=[\"']([^\"']+)[\"'][^>]*>"
                              content)
                      (map-indexed (fn [idx [_ tag id]]
                                     {:kind :svg-element
                                      :label (str tag "#" id)
                                      :source-line (inc idx)}))
                      distinct
                      vec)
        element-nodes (mapv (fn [{:keys [kind label source-line]}]
                              (common/generic-node run-id id-scope file-id path kind label source-line))
                            elements)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id asset-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           element-nodes)
        chunk-result (common/extract-text-source run-id
                                                 (assoc file :content (svg-summary path elements))
                                                 :svg-file)]
    {:nodes (into [asset-node] element-nodes)
     :edges define-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
