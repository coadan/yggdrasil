(ns ygg.extract.xml
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- xml-start-tags
  [content]
  (let [matcher (re-matcher #"(?is)<([A-Za-z_][A-Za-z0-9_.:-]*)(?:\s+[^<>]*?)?/?>"
                            content)
        line-starts (common/line-start-offsets content)]
    (loop [out []]
      (if (.find matcher)
        (let [offset (.start matcher)
              source-line (count (take-while #(<= % offset) line-starts))]
          (recur (conj out {:element (.group matcher 0)
                            :tag (.group matcher 1)
                            :source-line (max 1 source-line)})))
        out))))
(defn- xml-element-label
  [{:keys [element tag]}]
  (let [identifier (or (common/xml-attr-value element "android:id")
                       (common/xml-attr-value element "id")
                       (common/xml-attr-value element "name")
                       (common/xml-attr-value element "android:name"))]
    (if (seq identifier)
      (str tag "#" identifier)
      tag)))
(defn- xml-reference-values
  [element]
  (->> (re-seq #"[\"'](@[^\"']+)[\"']" element)
       (map second)
       (remove #(str/starts-with? % "@+id/"))
       distinct
       vec))
(defn extract-xml
  "Extract bounded XML element and explicit resource reference facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [xml-node (common/generic-node run-id id-scope file-id path :xml-file path 1)
        elements (->> (xml-start-tags content)
                      (map #(assoc % :label (xml-element-label %)))
                      (remove #(= (:label %) (:tag %)))
                      distinct
                      vec)
        element-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id
                                                   id-scope
                                                   file-id
                                                   path
                                                   :xml-element
                                                   label
                                                   source-line))
                            elements)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id xml-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           element-nodes)
        reference-edges (->> elements
                             (mapcat (fn [{:keys [label element source-line]}]
                                       (map (fn [reference]
                                              (common/edge-row run-id
                                                               file-id
                                                               path
                                                               (common/node-id id-scope :xml-element label)
                                                               (common/node-id id-scope :xml-reference reference)
                                                               :references
                                                               :extracted
                                                               source-line))
                                            (xml-reference-values element))))
                             distinct
                             vec)
        chunk-result (common/extract-text-source run-id file :xml-file)]
    {:nodes (into [xml-node] element-nodes)
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
(defn- apple-config-settings
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ key value]
                          (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.$()/-]*)\s*=\s*(.*?)\s*$"
                                      line)]
                 (when-not (str/starts-with? (str/trim line) "//")
                   {:kind :build-setting
                    :label (str key "=" (str/trim value))
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))
(defn extract-apple-config
  "Extract bounded Apple xcconfig build setting facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [config-node (common/generic-node run-id id-scope file-id path :apple-config path 1)
        settings (apple-config-settings (:content file))
        setting-nodes (mapv (fn [{:keys [kind label source-line]}]
                              (common/generic-node run-id id-scope file-id path kind label source-line))
                            settings)
        setting-edges (mapv (fn [{:keys [kind label source-line relation]}]
                              (common/edge-row run-id
                                               file-id
                                               path
                                               (:xt/id config-node)
                                               (common/node-id id-scope kind label)
                                               relation
                                               :extracted
                                               source-line))
                            settings)
        chunk-result (common/extract-text-source run-id file :apple-config-file)]
    {:nodes (into [config-node] setting-nodes)
     :edges setting-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
