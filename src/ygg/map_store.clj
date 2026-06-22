(ns ygg.map-store
  "File-backed storage for accepted map corrections."
  (:require [ygg.map :as graph-map]
            [charred.api :as json]
            [clojure.java.io :as io]))

(defn file-exists?
  [path]
  (.isFile (io/file path)))

(defn read-map
  "Read and normalize an ygg map file."
  [path]
  (graph-map/normalize-map
   (json/read-json (slurp (io/file path)) :key-fn keyword)))

(defn write-map!
  "Write a compact ygg.map/v2 file."
  [path overlay]
  (let [file (io/file path)
        data (assoc (graph-map/normalize-map overlay)
                    :updated-at-ms (graph-map/now-ms))]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str data {:indent-str "  "}) "\n"))
    (.getPath file)))

(defn read-or-empty
  [path project-id]
  (if (file-exists? path)
    (read-map path)
    (graph-map/empty-map project-id)))

(defn ensure-map!
  [path project-id]
  (when (and path (not (file-exists? path)))
    (write-map! path (graph-map/empty-map project-id))))

(defn apply-file
  "Apply map overlay from path to canonical graph data."
  [graph-data path]
  (graph-map/apply-overlay graph-data (read-map path)))
