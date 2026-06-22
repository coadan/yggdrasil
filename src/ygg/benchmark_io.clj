(ns ygg.benchmark-io
  (:require [charred.api :as json]
            [clojure.java.io :as io]))

(defn ensure-parent!
  [file]
  (.mkdirs (.getParentFile (io/file file))))
(defn write-json-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (json/write-json-str value {:indent-str "  "}))
  path)
(defn write-text-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (str value))
  path)
(defn write-edn-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (str (pr-str value) "\n"))
  path)
(defn read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))
