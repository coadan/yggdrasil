(ns agraph.dependency.imports.go
  "Go standard-library import candidate filtering."
  (:require [agraph.dependency.imports.common :as import-common]
            [clojure.string :as str]))

(def stdlib-roots
  #{"archive" "bufio" "bytes" "cmp" "compress" "container" "context" "crypto"
    "database" "debug" "embed" "encoding" "errors" "expvar" "flag" "fmt" "go"
    "hash" "html" "image" "index" "io" "iter" "log" "maps" "math" "mime" "net"
    "os" "path" "plugin" "reflect" "regexp" "runtime" "slices" "sort" "strconv"
    "strings" "structs" "sync" "syscall" "testing" "text" "time" "unicode"
    "unique" "unsafe" "weak"})

(defn external-package-candidate?
  [target]
  (not (contains? stdlib-roots (import-common/slash-root target))))

(defn module-nodes
  [nodes]
  (->> nodes
       (filter #(and (= :manifest (:kind %))
                     (str/ends-with? (str (:path %)) "go.mod")
                     (seq (:label %))))
       vec))

(defn- dirname
  [path]
  (let [idx (.lastIndexOf (str path) "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn- ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(defn- module-prefix-match?
  [module-name target]
  (or (= module-name target)
      (str/starts-with? (str target) (str module-name "/"))))

(defn local-module-import?
  [module-nodes source-path target kind]
  (and (= :go kind)
       (some #(and (ancestor-dir? (dirname (:path %)) source-path)
                   (module-prefix-match? (:label %) target))
             module-nodes)))

(defn local-import?
  [{:keys [module-nodes edge target kind]}]
  (local-module-import? module-nodes (:path edge) target kind))
