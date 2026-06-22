(ns ygg.extract.assets
  "Metadata-only extraction for binary and opaque asset files."
  (:require [ygg.extract.common :as common]))

(defn extract-binary-asset
  "Extract metadata for supported binary assets without reading text chunks."
  [run-id {:keys [id-scope file-id path kind size-bytes content-sha]}]
  (let [asset-node (cond-> (common/generic-node run-id id-scope file-id path kind path 1)
                     size-bytes (assoc :size-bytes size-bytes)
                     content-sha (assoc :content-sha content-sha))]
    {:nodes [asset-node]
     :edges []
     :chunks []
     :diagnostics []}))
