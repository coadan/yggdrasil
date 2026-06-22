(ns agraph.dependency.imports.javascript
  "JavaScript-family runtime import candidate filtering."
  (:require [agraph.dependency.imports.common :as import-common]
            [clojure.string :as str]))

(def runtime-builtin-roots
  #{"assert" "buffer" "child_process" "cluster" "console" "crypto" "dgram" "dns"
    "domain" "events" "fs" "http" "http2" "https" "module" "net" "os" "path"
    "perf_hooks" "process" "querystring" "readline" "stream" "string_decoder"
    "timers" "tls" "tty" "url" "util" "v8" "vm" "worker_threads" "zlib"})

(def runtime-virtual-prefixes
  #{"astro:" "bun:" "node:"})

(def local-source-kinds
  #{:javascript :typescript :astro :vue :svelte})

(def local-file-extensions
  ["" ".js" ".jsx" ".ts" ".tsx" ".mjs" ".cjs" ".mts" ".cts"])

(defn source-kind?
  [kind]
  (contains? local-source-kinds kind))

(defn- strip-resource-suffix
  [target]
  (str/replace (str target) #"[?#].*$" ""))

(defn- local-file-candidates
  [path]
  (let [path (strip-resource-suffix path)]
    (if (import-common/extension path)
      [path]
      (vec (concat
            (map #(str path %) local-file-extensions)
            (map #(str path "/index" %) local-file-extensions))))))

(defn- candidate-local-import-paths
  [source-path target]
  (let [target (strip-resource-suffix target)
        source-dir (import-common/dirname source-path)
        target-parts (vec (remove str/blank? (str/split target #"/")))
        target-suffix (when (< 1 (count target-parts))
                        (str/join "/" (rest target-parts)))]
    (->> (cond-> []
           (seq target)
           (conj target)

           (and (seq source-dir) (seq target))
           (conj (str source-dir "/" target))

           (and (seq source-dir) (seq target-suffix))
           (conj (str source-dir "/" target-suffix)))
         (mapcat local-file-candidates)
         distinct
         vec)))

(defn runtime-import?
  [target]
  (or (contains? runtime-builtin-roots (import-common/slash-root target))
      (some #(str/starts-with? target %) runtime-virtual-prefixes)))

(defn external-package-candidate?
  [target]
  (and (not (str/starts-with? target "."))
       (not (runtime-import? target))))

(defn local-import?
  [{:keys [files-by-path edge target kind]}]
  (and (source-kind? kind)
       (not (str/starts-with? target "."))
       (not (str/starts-with? target "@"))
       (str/includes? target "/")
       (some #(contains? files-by-path %)
             (candidate-local-import-paths (:path edge) target))))
