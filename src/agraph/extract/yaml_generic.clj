(ns agraph.extract.yaml-generic
  (:require [agraph.extract.common :as common]
            [agraph.extract.infra :as infra]
            [clojure.string :as str]))

(defn- framework-yaml-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ route] (re-matches #"^\s*path:\s*['\"]?(/[^'\"\s#]+).*" line)]
               [{:kind :framework-route
                 :label route
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ controller] (re-matches #"^\s*controller:\s*['\"]?([^'\"\s#]+).*" line)]
               [{:kind :framework-controller
                 :label controller
                 :source-line source-line
                 :relation :references}])))))
       distinct
       vec))
(defn extract-yaml
  "Extract generic YAML files and explicit Kubernetes resource declarations."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [yaml-node (common/generic-node run-id id-scope file-id path :yaml-file path 1)
        resource-facts (vec (concat (infra/k8s-resource-facts content)
                                    (framework-yaml-facts content)))
        resource-nodes (mapv (fn [{:keys [kind label source-line]}]
                               (common/generic-node run-id id-scope file-id path kind label source-line))
                             resource-facts)
        resource-edges (mapv (fn [{:keys [kind label source-line relation]}]
                               (common/edge-row run-id
                                                file-id
                                                path
                                                (:xt/id yaml-node)
                                                (common/node-id id-scope kind label)
                                                (or relation :defines)
                                                :extracted
                                                source-line))
                             resource-facts)
        chunk-result (common/extract-text-source run-id file :yaml-file)]
    {:nodes (into [yaml-node] resource-nodes)
     :edges resource-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
