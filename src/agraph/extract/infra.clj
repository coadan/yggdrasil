(ns agraph.extract.infra
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn yaml-documents
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         current []
         out []]
    (if-let [[idx line] (first remaining)]
      (if (re-matches #"^\s*---\s*$" line)
        (recur (rest remaining)
               []
               (cond-> out (seq current) (conj current)))
        (recur (rest remaining)
               (conj current [idx line])
               out))
      (cond-> out (seq current) (conj current)))))
(defn yaml-doc-value
  [doc key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (common/yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (common/strip-yaml-scalar value)
               :source-line source-line})))
        doc))
(defn yaml-doc-metadata-name
  [doc]
  (loop [remaining doc
         in-metadata? false]
    (if-let [[idx line] (first remaining)]
      (if-let [{:keys [indent key value source-line]} (common/yaml-key-line idx line)]
        (cond
          (and (= "metadata" key) (= 0 indent))
          (recur (rest remaining) true)

          (and in-metadata? (<= indent 0))
          nil

          (and in-metadata? (= "name" key) (seq value))
          {:value (common/strip-yaml-scalar value)
           :source-line source-line}

          :else
          (recur (rest remaining) in-metadata?))
        (recur (rest remaining) in-metadata?))
      nil)))
(defn- yaml-doc-metadata-value
  [doc key-name]
  (loop [remaining doc
         in-metadata? false]
    (if-let [[idx line] (first remaining)]
      (if-let [{:keys [indent key value source-line]} (common/yaml-key-line idx line)]
        (cond
          (and (= "metadata" key) (= 0 indent))
          (recur (rest remaining) true)

          (and in-metadata? (<= indent 0))
          nil

          (and in-metadata? (= key-name key) (seq value))
          {:value (common/strip-yaml-scalar value)
           :source-line source-line}

          :else
          (recur (rest remaining) in-metadata?))
        (recur (rest remaining) in-metadata?))
      nil)))
(defn- k8s-image-facts
  [doc resource-label]
  (->> doc
       (keep (fn [[idx line]]
               (when-let [[_ image]
                          (re-matches #"^\s*image:\s*['\"]?(.+?)['\"]?\s*$"
                                      line)]
                 {:kind :container-image
                  :label (common/strip-yaml-scalar image)
                  :source-line (inc idx)
                  :relation :uses
                  :resource-label resource-label})))
       distinct
       vec))
(defn k8s-resource-facts
  [content]
  (->> (yaml-documents content)
       (mapcat
        (fn [doc]
          (let [doc-text (str/join "\n" (map second doc))
                api-version (yaml-doc-value doc "apiVersion")
                kind (yaml-doc-value doc "kind")
                name (yaml-doc-metadata-name doc)
                namespace (yaml-doc-metadata-value doc "namespace")
                resource-label (when (and (:value kind) (:value name))
                                 (str (:value kind) "/" (:value name)))
                base (when resource-label
                       (concat
                        [{:kind :k8s-resource
                          :label resource-label
                          :source-line (or (:source-line kind) (:source-line name) 1)
                          :resource-kind (:value kind)
                          :resource-name (:value name)}]
                        (when (:value api-version)
                          [{:kind :k8s-api-version
                            :label (str resource-label ":" (:value api-version))
                            :source-line (:source-line api-version)
                            :relation :defines}])
                        (when (:value namespace)
                          [{:kind :k8s-namespace
                            :label (str resource-label ":" (:value namespace))
                            :source-line (:source-line namespace)
                            :relation :defines}])
                        (k8s-image-facts doc resource-label)))
                crd (when (= "CustomResourceDefinition" (:value kind))
                      (concat
                       (when-let [[_ group] (re-find #"(?m)^\s*group:\s*(.+?)\s*$" doc-text)]
                         [{:kind :k8s-crd-group
                           :label (common/strip-yaml-scalar group)
                           :source-line (:source-line kind 1)
                           :relation :defines}])
                       (when-let [[_ crd-kind] (re-find #"(?m)^\s{4}kind:\s*(.+?)\s*$" doc-text)]
                         [{:kind :k8s-crd-kind
                           :label (common/strip-yaml-scalar crd-kind)
                           :source-line (:source-line kind 1)
                           :relation :defines}])
                       (->> (re-seq #"(?m)^\s*-\s+name:\s+(.+?)\s*$" doc-text)
                            (map second)
                            (map (fn [version]
                                   {:kind :k8s-crd-version
                                    :label (common/strip-yaml-scalar version)
                                    :source-line (:source-line kind 1)
                                    :relation :defines})))))
                crossplane? (or (some-> (:value api-version)
                                        (str/includes? "crossplane.io"))
                                (str/includes? doc-text "providerConfigRef:")
                                (str/includes? doc-text "compositionRef:"))
                crossplane (when (and crossplane? resource-label)
                             (concat
                              [{:kind :crossplane-resource
                                :label resource-label
                                :source-line (or (:source-line kind) 1)
                                :relation :defines}]
                              (when-let [[_ provider]
                                         (re-find #"(?m)^\s*providerConfigRef:\s*\n\s*name:\s*(.+?)\s*$"
                                                  doc-text)]
                                [{:kind :crossplane-provider-config
                                  :label (common/strip-yaml-scalar provider)
                                  :source-line (or (:source-line kind) 1)
                                  :relation :references}])))
                argocd (when (and (= "Application" (:value kind))
                                  (some-> (:value api-version)
                                          (str/includes? "argoproj.io"))
                                  resource-label)
                         (concat
                          [{:kind :argocd-application
                            :label (:value name)
                            :source-line (or (:source-line name) 1)
                            :relation :defines}]
                          (->> [["repoURL" :argocd-source]
                                ["path" :argocd-source-path]
                                ["chart" :argocd-source-chart]
                                ["server" :argocd-destination]
                                ["namespace" :argocd-destination]]
                               (keep (fn [[key kind]]
                                       (when-let [[_ value]
                                                  (re-find (re-pattern
                                                            (str "(?m)^\\s*"
                                                                 key
                                                                 ":\\s*(.+?)\\s*$"))
                                                           doc-text)]
                                         {:kind kind
                                          :label (common/strip-yaml-scalar value)
                                          :source-line (or (:source-line name) 1)
                                          :relation :references}))))))]
            (concat base crd crossplane argocd))))
       distinct
       vec))
(defn extract-helm
  "Extract bounded Helm chart and Kubernetes resource facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [filename (common/manifest-name path)
        helm-node (common/generic-node run-id id-scope file-id path :helm-file path 1)
        chart-facts (when (= "chart.yaml" filename)
                      (->> [(when-let [name (common/yaml-top-level-value content "name")]
                              {:kind :helm-chart
                               :label name
                               :source-line 1
                               :relation :defines})
                            (when-let [version (common/yaml-top-level-value content "version")]
                              {:kind :helm-chart-version
                               :label version
                               :source-line 1
                               :relation :defines})
                            (when-let [app-version (common/yaml-top-level-value content "appVersion")]
                              {:kind :helm-app-version
                               :label app-version
                               :source-line 1
                               :relation :defines})]
                           (remove nil?)))
        dependency-facts (when (= "chart.yaml" filename)
                           (->> (common/yaml-section-items content #{"dependencies"})
                                (filter #(= "dependencies" (:section %)))
                                (map (fn [{:keys [value source-line]}]
                                       {:kind :helm-dependency
                                        :label value
                                        :source-line source-line
                                        :relation :references}))
                                distinct
                                vec))
        value-facts (when (str/starts-with? filename "values.")
                      (->> (str/split-lines content)
                           (map-indexed vector)
                           (keep (fn [[idx line]]
                                   (when-let [[_ key value]
                                              (re-matches #"^\s*(repository|tag|pullPolicy|name):\s*(.+?)\s*$"
                                                          line)]
                                     {:kind :helm-value
                                      :label (str key "=" (common/strip-yaml-scalar value))
                                      :source-line (inc idx)
                                      :relation :defines})))
                           distinct
                           vec))
        resource-facts (k8s-resource-facts content)
        facts (vec (concat chart-facts dependency-facts value-facts resource-facts))
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id helm-node)
                                            (common/node-id id-scope kind label)
                                            (or relation :defines)
                                            :extracted
                                            source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :helm-file)]
    {:nodes (into [helm-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
