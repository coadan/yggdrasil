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

(defn runtime-import?
  [target]
  (or (contains? runtime-builtin-roots (import-common/slash-root target))
      (some #(str/starts-with? target %) runtime-virtual-prefixes)))

(defn external-package-candidate?
  [target]
  (and (not (str/starts-with? target "."))
       (not (runtime-import? target))))
