(ns build
  "Build tasks for JVM artifacts and GraalVM native-image."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def version
  "0.1.0-SNAPSHOT")

(def class-dir
  "target/classes")

(def target-dir
  "target")

(def native-dir
  "target/native")

(def uber-file
  (format "target/ygg-%s-standalone.jar" version))

(def native-image-name
  "ygg-server")

(def native-main
  'ygg.native)

(def uber-excludes
  ["(?i)^META-INF/license($|/.*)"])

(def src-dirs
  ["src"])

(defn- clj-file?
  [^java.io.File file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".clj")))

(defn- file-ns
  [file]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (let [form (read reader)]
        (when (= 'ns (first form))
          (second form))))
    (catch Exception _
      nil)))

(defn- source-namespaces
  []
  (->> src-dirs
       (map io/file)
       (mapcat file-seq)
       (filter clj-file?)
       (keep file-ns)
       (remove #{'ygg.native})
       (sort-by str)
       (cons native-main)
       vec))

(defn- native-image-binary
  []
  (or (System/getenv "NATIVE_IMAGE") "native-image"))

(defn- native-image-version
  [program]
  (try
    (sh/sh program "--version")
    (catch java.io.IOException e
      {:exit 127
       :err (.getMessage e)})))

(defn- require-native-image!
  [program]
  (let [{:keys [exit out err]} (native-image-version program)]
    (when-not (zero? exit)
      (throw (ex-info "GraalVM native-image is not available."
                      {:program program
                       :env "NATIVE_IMAGE"
                       :error err})))
    (println (first (str/split-lines out)))))

(defn- native-binary-path
  []
  (str (io/file native-dir native-image-name)))

(defn- native-image-args
  []
  ["--no-fallback"
   "-H:+ReportExceptionStackTraces"
   "--enable-url-protocols=http,https"
   "-jar"
   uber-file
   (native-binary-path)])

(defn clean
  [_]
  (b/delete {:path target-dir}))

(defn compile-clj
  [_]
  (b/delete {:path class-dir})
  (b/compile-clj {:basis (b/create-basis {:project "deps.edn"
                                          :aliases [:native]})
                  :src-dirs src-dirs
                  :class-dir class-dir
                  :ns-compile (source-namespaces)}))

(defn uber
  [_]
  (compile-clj nil)
  (b/uber {:basis (b/create-basis {:project "deps.edn"
                                   :aliases [:native]})
           :class-dir class-dir
           :uber-file uber-file
           :main native-main
           :exclude uber-excludes})
  {:uber-file uber-file
   :main native-main})

(defn native-command
  [_]
  {:program (native-image-binary)
   :args (native-image-args)
   :binary (native-binary-path)})

(defn native-args
  [_]
  (let [{:keys [program args]} (native-command nil)]
    (println (str/join " " (cons program args)))))

(defn native-check
  [_]
  (let [program (native-image-binary)]
    (require-native-image! program)
    {:program program}))

(defn native
  [_]
  (let [program (native-image-binary)]
    (require-native-image! program))
  (uber nil)
  (.mkdirs (io/file native-dir))
  (let [{:keys [program args binary]} (native-command nil)
        command-args (vec (cons program args))]
    (println (str/join " " command-args))
    (b/process {:command-args command-args})
    {:binary binary}))
