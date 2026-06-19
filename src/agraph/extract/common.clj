(ns agraph.extract.common
  "Shared mechanical row helpers for extractor implementation namespaces."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]))

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (node-id nil kind value))
  ([id-scope kind value]
   (str (when (seq id-scope) (str id-scope ":"))
        "node:" (name kind) ":" value)))

(defn generic-node
  [run-id id-scope file-id path kind label source-line]
  {:xt/id (node-id id-scope kind label)
   :label label
   :kind kind
   :file-id file-id
   :path path
   :name label
   :source-line (or source-line 1)
   :tokens (text/tokenize label)
   :active? true
   :run-id run-id})

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (str "edge:" (hash/short-hash [source-id relation target-id])))

(defn edge-row
  [run-id file-id path source-id target-id relation confidence source-line]
  {:xt/id (edge-id source-id target-id relation path source-line)
   :source-id source-id
   :target-id target-id
   :relation relation
   :confidence confidence
   :file-id file-id
   :path path
   :source-line (or source-line 1)
   :active? true
   :run-id run-id})

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (chunk-id nil path label source-line))
  ([id-scope path label source-line]
   (str "chunk:" (hash/short-hash [id-scope path label source-line]))))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]} chunk-kind]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind chunk-kind
             :file-kind kind
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})
