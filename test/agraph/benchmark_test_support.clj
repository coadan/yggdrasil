(ns agraph.benchmark-test-support
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))
(defn sh!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed."
                      {:args args
                       :exit exit
                       :out out
                       :err err})))
    out))
(defn git!
  [repo & args]
  (apply sh! "git" "-C" repo args))
(defn spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))
(defn spit-json!
  [root path value]
  (spit-file! root path (json/write-json-str value {:indent-str "  "})))
(defn commit!
  [repo message]
  (git! repo "add" ".")
  (git! repo "commit" "-m" message)
  (str/trim (git! repo "rev-parse" "HEAD")))
