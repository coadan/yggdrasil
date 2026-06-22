(ns ygg.extract.config-generic
  (:require [ygg.extract.common :as common]))

(defn- extract-config-facts
  [run-id {:keys [id-scope file-id path] :as file} root-kind chunk-kind]
  (let [config-node (common/generic-node run-id id-scope file-id path root-kind path 1)
        facts (common/config-facts file)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id config-node)
                                            (common/node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file chunk-kind)]
    {:nodes (into [config-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
(defn extract-codegen-config
  "Extract bounded GraphQL/codegen configuration facts."
  [run-id file]
  (extract-config-facts run-id file :codegen-config :codegen-config-file))
(defn extract-test-config
  "Extract bounded test runner configuration facts."
  [run-id file]
  (extract-config-facts run-id file :test-config :test-config-file))
