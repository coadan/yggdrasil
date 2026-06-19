(ns agraph.extract.text
  (:require [agraph.extract.common :as common]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (common/extract-text-source run-id file chunk-kind))

(defn extract-edn
  "Extract EDN as a searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]}]
  {:nodes []
   :edges []
   :chunks [{:xt/id (common/chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind (or kind :edn)
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(defn- env-var-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ key]
                              (re-matches #"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|:).*$"
                                          trimmed)]
                     {:kind :env-var
                      :label key
                      :source-line (inc idx)
                      :relation :defines})))))
       distinct
       vec))
(defn extract-env
  "Extract dotenv-style files without storing assigned values as searchable text."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [root-node (common/generic-node run-id id-scope file-id path :env-file path 1)
        facts (env-var-facts content)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (common/node-id id-scope kind label)
                                     relation
                                     :extracted
                                     source-line))
                         facts)
        sanitized-text (str/join "\n" (cons path (map :label facts)))]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks [{:xt/id (common/chunk-id id-scope path path 1)
               :file-id file-id
               :path path
               :kind :env-file
               :file-kind kind
               :label path
               :text sanitized-text
               :tokens (text/tokenize sanitized-text)
               :content-sha (hash/sha256-hex sanitized-text)
               :source-line 1
               :active? true
               :run-id run-id}]
     :diagnostics []}))
