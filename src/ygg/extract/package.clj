(ns ygg.extract.package
  (:require [ygg.extract.common :as common]
            [ygg.extract.package-lock :as package-lock]
            [ygg.extract.package-manifest :as package-manifest]))

(defn extract-manifest
  "Extract bounded declared dependency/reference facts from project manifests."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [{:keys [project-label facts]} (package-manifest/manifest-facts file)
        manifest-node (common/generic-node run-id id-scope file-id path :manifest project-label 1)
        fact-nodes (mapv #(common/fact-node run-id id-scope file-id path %) facts)
        fact-edges (mapv #(common/fact-edge-row run-id
                                                file-id
                                                path
                                                (:xt/id manifest-node)
                                                id-scope
                                                %)
                         facts)
        chunk-result (common/extract-text-source run-id file :manifest-file)]
    {:nodes (into [manifest-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
(defn extract-dependency-lock
  "Extract exact package version facts from supported dependency lockfiles."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (package-lock/dependency-lock-facts file)
        package-facts (->> facts
                           (map package-lock/version-package-fact)
                           (remove nil?)
                           distinct
                           vec)
        lock-node (common/generic-node run-id id-scope file-id path :dependency-lock path 1)
        fact-nodes (mapv #(common/fact-node run-id id-scope file-id path %)
                         (concat package-facts facts))
        resolve-edges (mapv #(common/fact-edge-row run-id
                                                   file-id
                                                   path
                                                   (:xt/id lock-node)
                                                   id-scope
                                                   %)
                            facts)
        version-edges (mapv #(package-lock/version-of-edge run-id file-id path id-scope %) facts)
        chunk-result (common/extract-text-source run-id file :dependency-lock-file)]
    {:nodes (into [lock-node] fact-nodes)
     :edges (vec (concat resolve-edges version-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
