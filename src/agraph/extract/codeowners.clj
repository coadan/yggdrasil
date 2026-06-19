(ns agraph.extract.codeowners
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- codeowners-rules
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when (and (seq trimmed)
                            (not (str/starts-with? trimmed "#")))
                   (let [[pattern & owners] (str/split trimmed #"\s+")]
                     (when (and (seq pattern) (seq owners))
                       {:pattern pattern
                        :owners owners
                        :source-line (inc idx)}))))))
       vec))
(defn- codeowner-pattern-syntax-labels
  [pattern]
  (vec (concat
        (when (= "*" pattern)
          [(str "wildcard:" pattern)])
        (when (str/starts-with? pattern "/")
          [(str "rooted:" pattern)])
        (when (str/ends-with? pattern "/")
          [(str "directory:" pattern)])
        (when (str/includes? pattern "*")
          [(str "glob:" pattern)])
        (when (str/starts-with? pattern "!")
          [(str "negated:" pattern)]))))
(defn- codeowner-owner-syntax-label
  [owner]
  (cond
    (re-matches #"^@[^/\s]+/[^/\s]+$" owner) (str "team:" owner)
    (str/starts-with? owner "@") (str "handle:" owner)
    (re-matches #"^[^@\s]+@[^@\s]+\.[^@\s]+$" owner) (str "email:" owner)
    :else (str "owner:" owner)))
(defn extract-codeowners
  "Extract CODEOWNERS rule patterns and owner handles."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [file-node (common/generic-node run-id id-scope file-id path :codeowners-file path 1)
        rules (codeowners-rules content)
        rule-nodes (mapv (fn [{:keys [pattern source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                         :codeowner-rule pattern source-line))
                         rules)
        owners (->> rules
                    (mapcat (fn [{:keys [owners source-line]}]
                              (map (fn [owner]
                                     {:label owner
                                      :source-line source-line})
                                   owners)))
                    distinct
                    vec)
        owner-nodes (mapv (fn [{:keys [label source-line]}]
                            (common/generic-node run-id id-scope file-id path
                                          :codeowner label source-line))
                          owners)
        pattern-syntaxes (->> rules
                              (mapcat (fn [{:keys [pattern source-line]}]
                                        (map (fn [label]
                                               {:label label
                                                :source-line source-line})
                                             (codeowner-pattern-syntax-labels pattern))))
                              distinct
                              vec)
        pattern-syntax-nodes (mapv (fn [{:keys [label source-line]}]
                                     (common/generic-node run-id id-scope file-id path
                                                   :codeowner-pattern-syntax
                                                   label
                                                   source-line))
                                   pattern-syntaxes)
        owner-syntaxes (->> owners
                            (map (fn [{:keys [label source-line]}]
                                   {:label (codeowner-owner-syntax-label label)
                                    :source-line source-line}))
                            distinct
                            vec)
        owner-syntax-nodes (mapv (fn [{:keys [label source-line]}]
                                   (common/generic-node run-id id-scope file-id path
                                                 :codeowner-owner-syntax
                                                 label
                                                 source-line))
                                 owner-syntaxes)
        define-edges (mapv (fn [{:keys [pattern source-line]}]
                             (common/edge-row run-id
                                       file-id
                                       path
                                       (:xt/id file-node)
                                       (common/node-id id-scope :codeowner-rule pattern)
                                       :defines
                                       :extracted
                                       source-line))
                           rules)
        pattern-syntax-edges (mapcat
                              (fn [{:keys [pattern source-line]}]
                                (map (fn [label]
                                       (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (common/node-id id-scope
                                                          :codeowner-rule
                                                          pattern)
                                                 (common/node-id id-scope
                                                          :codeowner-pattern-syntax
                                                          label)
                                                 :describes
                                                 :extracted
                                                 source-line))
                                     (codeowner-pattern-syntax-labels pattern)))
                              rules)
        owner-syntax-edges (mapv
                            (fn [{:keys [label source-line]}]
                              (common/edge-row run-id
                                        file-id
                                        path
                                        (common/node-id id-scope :codeowner label)
                                        (common/node-id id-scope
                                                 :codeowner-owner-syntax
                                                 (codeowner-owner-syntax-label label))
                                        :describes
                                        :extracted
                                        source-line))
                            owners)
        assign-edges (mapv (fn [{:keys [pattern owners source-line]}]
                             (mapv (fn [owner]
                                     (common/edge-row run-id
                                               file-id
                                               path
                                               (common/node-id id-scope
                                                        :codeowner-rule
                                                        pattern)
                                               (common/node-id id-scope
                                                        :codeowner
                                                        owner)
                                               :assigns
                                               :extracted
                                               source-line))
                                   owners))
                           rules)
        chunk-result (common/extract-text-source run-id file :codeowners-file)]
    {:nodes (vec (concat [file-node]
                         rule-nodes
                         owner-nodes
                         pattern-syntax-nodes
                         owner-syntax-nodes))
     :edges (vec (concat define-edges
                         pattern-syntax-edges
                         owner-syntax-edges
                         (apply concat assign-edges)))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
