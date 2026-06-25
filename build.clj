(ns build
  "Build tasks for JVM artifacts and GraalVM native-image."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
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

(def native-generated-resource-dir
  "target/native-generated-resources")

(def native-generated-java-dir
  "target/native-generated-java")

(def uber-file
  (format "target/ygg-%s-standalone.jar" version))

(def native-image-name
  "ygg-server")

(def native-linux-image-name
  "ygg-server-linux")

(def native-main
  'ygg.NativeMain)

(def native-root-namespaces
  ['ygg.server])

(def uber-excludes
  ["(?i)^META-INF/license($|/.*)"])

(def src-dirs
  ["src"])

(def resource-dirs
  ["resources"])

(def java-src-dirs
  ["src-java"])

(defn- basis
  []
  (b/create-basis {:project "deps.edn"
                   :aliases [:native]}))

(defn- clj-file?
  [^java.io.File file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".clj")))

(defn- read-ns-form
  [reader]
  (let [form (read {:read-cond :allow
                    :features #{:clj}}
                   reader)]
    (when (= 'ns (first form))
      form)))

(defn- file-ns
  [file]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (some-> (read-ns-form reader) second))
    (catch Exception _
      nil)))

(defn- source-namespaces
  []
  (->> src-dirs
       (map io/file)
       (mapcat file-seq)
       (filter clj-file?)
       (keep file-ns)
       (sort-by str)
       vec))

(defn- source-namespace-forms
  []
  (->> src-dirs
       (map io/file)
       (mapcat file-seq)
       (filter clj-file?)
       (keep (fn [file]
               (when-let [ns-form (try
                                    (with-open [reader (java.io.PushbackReader. (io/reader file))]
                                      (read-ns-form reader))
                                    (catch Exception _
                                      nil))]
                 [(second ns-form) ns-form])))
       (into {})))

(defn- ns-resource-base
  [ns-name]
  (-> (str ns-name)
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- ns-init-class-name
  [ns-name]
  (str (str/replace (str ns-name) "-" "_") "__init"))

(defn- ns-init-class-resource-path
  [ns-name]
  (str (str/replace (ns-init-class-name ns-name) "." "/") ".class"))

(defn- read-classpath-resource
  [path]
  (some (fn [root]
          (let [file (io/file root)]
            (cond
              (.isDirectory file)
              (let [resource (io/file file path)]
                (when (.isFile resource)
                  (with-open [reader (java.io.PushbackReader. (io/reader resource))]
                    (read-ns-form reader))))

              (and (.isFile file) (str/ends-with? (.getName file) ".jar"))
              (with-open [jar (java.util.jar.JarFile. file)]
                (when-let [entry (.getJarEntry jar path)]
                  (with-open [stream (.getInputStream jar entry)
                              reader (java.io.PushbackReader. (io/reader stream))]
                    (read-ns-form reader))))

              :else
              nil)))
        (:classpath-roots (basis))))

(defn- classpath-resource?
  [path]
  (boolean
   (some (fn [root]
           (let [file (io/file root)]
             (cond
               (.isDirectory file)
               (.isFile (io/file file path))

               (and (.isFile file) (str/ends-with? (.getName file) ".jar"))
               (with-open [jar (java.util.jar.JarFile. file)]
                 (some? (.getJarEntry jar path)))

               :else
               false)))
         (:classpath-roots (basis)))))

(defn- classpath-ns-form
  [ns-name]
  (let [base (ns-resource-base ns-name)]
    (some (fn [path]
            (read-classpath-resource path))
          [(str base ".clj") (str base ".cljc")])))

(defn- require-spec-symbols
  [spec]
  (cond
    (symbol? spec)
    [spec]

    (and (vector? spec) (symbol? (first spec)))
    [(first spec)]

    (and (seq? spec) (symbol? (first spec)))
    (mapcat (fn [child]
              (when (symbol? child)
                [(symbol (str (first spec) "." child))]))
            (rest spec))

    :else
    []))

(defn- ns-requires
  [ns-form]
  (->> (drop 2 ns-form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (mapcat require-spec-symbols)
       set))

(defn- discover-native-namespace-forms
  []
  (let [source-forms (source-namespace-forms)]
    (loop [pending native-root-namespaces
           seen #{}
           forms {}]
      (if-let [ns-name (first pending)]
        (if (or (seen ns-name)
                (str/starts-with? (str ns-name) "clojure."))
          (recur (rest pending) seen forms)
          (if-let [ns-form (or (get source-forms ns-name)
                               (classpath-ns-form ns-name))]
            (recur (into (rest pending) (ns-requires ns-form))
                   (conj seen ns-name)
                   (assoc forms ns-name ns-form))
            (recur (rest pending) (conj seen ns-name) forms)))
        forms))))

(defn- native-dependencies
  [ns-forms]
  (let [native-set (set (keys ns-forms))]
    (into {}
          (map (fn [[ns-name ns-form]]
                 [ns-name (->> (ns-requires ns-form)
                               (filter native-set)
                               set)]))
          ns-forms)))

(defn- dependency-ordered-namespaces
  []
  (let [dependencies (native-dependencies (discover-native-namespace-forms))]
    (loop [remaining dependencies
           ordered []]
      (if-not (seq remaining)
        ordered
        (let [ready (->> remaining
                         (filter (fn [[_ deps]]
                                   (empty? (set/difference deps (set ordered)))))
                         (map first)
                         (sort-by str)
                         vec)]
          (when-not (seq ready)
            (throw (ex-info "Cannot order namespaces for native initialization."
                            {:remaining remaining})))
          (recur (apply dissoc remaining ready)
                 (into ordered ready)))))))

(defn- native-aot-namespaces
  []
  (let [source-set (set (source-namespaces))]
    (->> (dependency-ordered-namespaces)
         (filter (fn [ns-name]
                   (or (source-set ns-name)
                       (not (classpath-resource? (ns-init-class-resource-path ns-name))))))
         vec)))

(defn- ns->init-class-name
  [ns-name]
  (ns-init-class-name ns-name))

(defn- json-string
  [value]
  (str "\"" (str/escape value {\\ "\\\\"
                               \" "\\\""}) "\""))

(defn- write-native-reflect-config!
  []
  (let [config-file (io/file native-generated-resource-dir
                             "META-INF/native-image/yggdrasil/ygg/reflect-config.json")
        ordered-namespaces (dependency-ordered-namespaces)
        entries (->> ordered-namespaces
                     (map ns->init-class-name)
                     distinct
                     sort
                     (map #(str "  {\"name\": " (json-string %) "}")))]
    (println (str "Native namespace preload: " (count ordered-namespaces) " namespaces"))
    (.mkdirs (.getParentFile config-file))
    (spit config-file
          (str "[\n"
               (str/join ",\n" entries)
               "\n]\n"))))

(defn- write-native-namespaces!
  []
  (let [source-file (io/file native-generated-java-dir "ygg/NativeNamespaces.java")
        ordered-namespaces (dependency-ordered-namespaces)
        init-lines (->> ordered-namespaces
                        (map ns->init-class-name)
                        (map #(str "    touch(" % ".const__0);")))]
    (println (str "Native namespace Java loader: " (count ordered-namespaces) " namespaces"))
    (.mkdirs (.getParentFile source-file))
    (spit source-file
          (str "package ygg;\n\n"
               "import clojure.lang.RT;\n"
               "import clojure.lang.Var;\n\n"
               "final class NativeNamespaces {\n"
               "  private NativeNamespaces() {}\n\n"
               "  static {\n"
               "    Var currentNs = RT.CURRENT_NS;\n"
               "    Var warnOnReflection = RT.var(\"clojure.core\", \"*warn-on-reflection*\");\n"
               "    Var uncheckedMath = RT.var(\"clojure.core\", \"*unchecked-math*\");\n"
               "    Var.pushThreadBindings(RT.mapUniqueKeys(currentNs, currentNs.deref(), warnOnReflection, warnOnReflection.deref(), uncheckedMath, uncheckedMath.deref()));\n"
               "    try {\n"
               "      load();\n"
               "    } finally {\n"
               "      Var.popThreadBindings();\n"
               "    }\n"
               "  }\n\n"
               "  static void ensureLoaded() {}\n\n"
               "  private static void load() {\n"
               (str/join "\n" init-lines)
               "\n  }\n\n"
               "  private static void touch(Object ignored) {}\n"
               "}\n"))))

(defn- build-input-file?
  [^java.io.File file]
  (.isFile file))

(defn- build-input-files
  []
  (concat
   [(io/file "deps.edn")
    (io/file "build.clj")]
   (->> ["src" "src-java" "resources"]
        (map io/file)
        (filter #(.exists ^java.io.File %))
        (mapcat file-seq)
        (filter build-input-file?))))

(defn- stale-uber?
  []
  (let [uber (io/file uber-file)]
    (or (not (.exists uber))
        (let [uber-mtime (.lastModified uber)]
          (some #(> (.lastModified ^java.io.File %) uber-mtime)
                (build-input-files))))))

(defn- native-image-binary
  []
  (or (System/getenv "NATIVE_IMAGE") "native-image"))

(defn- native-image-docker-image
  []
  (or (System/getenv "YGG_NATIVE_IMAGE_DOCKER_IMAGE")
      "ghcr.io/graalvm/native-image-community:21"))

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

(defn- native-linux-binary-path
  []
  (str (io/file native-dir native-linux-image-name)))

(defn- native-image-args
  [binary-path]
  ["--no-fallback"
   "-H:+ReportExceptionStackTraces"
   "--parallelism=4"
   "--enable-url-protocols=http,https"
   "--initialize-at-build-time=org.slf4j.LoggerFactory,org.slf4j.helpers.Reporter,kotlin.DeprecationLevel"
   (str "--initialize-at-run-time="
        (str/join "," (map ns->init-class-name (dependency-ordered-namespaces))))
   "-jar"
   uber-file
   "-o"
   binary-path])

(defn- native-docker-command-prefix
  []
  (let [repo-dir (.getCanonicalPath (io/file "."))]
    ["docker" "run" "--rm"
     "-v" (str repo-dir ":/work")
     "-w" "/work"
     (native-image-docker-image)]))

(defn- native-docker-command-args
  []
  (vec (concat (native-docker-command-prefix)
               (native-image-args (native-linux-binary-path)))))

(defn clean
  [_]
  (b/delete {:path target-dir}))

(defn compile-clj
  [_]
  (b/delete {:path class-dir})
  (b/compile-clj {:basis (basis)
                  :src-dirs src-dirs
                  :class-dir class-dir
                  :ns-compile (native-aot-namespaces)}))

(defn compile-java
  [_]
  (compile-clj nil)
  (b/delete {:path native-generated-resource-dir})
  (b/delete {:path native-generated-java-dir})
  (write-native-reflect-config!)
  (write-native-namespaces!)
  (b/copy-dir {:src-dirs (conj resource-dirs native-generated-resource-dir)
               :target-dir class-dir})
  (b/javac {:basis (basis)
            :src-dirs (conj java-src-dirs native-generated-java-dir)
            :class-dir class-dir
            :javac-opts ["--release" "21" "-proc:none"]}))

(defn uber
  [_]
  (compile-java nil)
  (b/uber {:basis (basis)
           :class-dir class-dir
           :uber-file uber-file
           :main native-main
           :exclude uber-excludes})
  {:uber-file uber-file
   :main native-main})

(defn ensure-uber
  [_]
  (if (stale-uber?)
    (uber nil)
    (do
      (println (str "Using current " uber-file))
      {:uber-file uber-file
       :main native-main})))

(defn native-command
  [_]
  {:program (native-image-binary)
   :args (native-image-args (native-binary-path))
   :binary (native-binary-path)})

(defn native-docker-command
  [_]
  {:command-args (native-docker-command-args)
   :binary (native-linux-binary-path)
   :image (native-image-docker-image)})

(defn native-args
  [_]
  (let [{:keys [program args]} (native-command nil)]
    (println (str/join " " (cons program args)))))

(defn native-check
  [_]
  (let [program (native-image-binary)]
    (require-native-image! program)
    {:program program}))

(defn native-docker-check
  [_]
  (let [{:keys [image]} (native-docker-command nil)
        check-command (conj (native-docker-command-prefix) "--version")]
    (println (str "docker image: " image))
    (b/process {:command-args check-command})
    {:image image}))

(defn native
  [_]
  (let [program (native-image-binary)]
    (require-native-image! program))
  (ensure-uber nil)
  (.mkdirs (io/file native-dir))
  (let [{:keys [program args binary]} (native-command nil)
        command-args (vec (cons program args))]
    (println (str/join " " command-args))
    (b/process {:command-args command-args})
    {:binary binary}))

(defn native-docker
  [_]
  (ensure-uber nil)
  (.mkdirs (io/file native-dir))
  (let [{:keys [command-args binary]} (native-docker-command nil)]
    (println (str/join " " command-args))
    (b/process {:command-args command-args})
    {:binary binary}))
