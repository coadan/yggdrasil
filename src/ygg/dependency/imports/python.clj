(ns ygg.dependency.imports.python
  "Python standard-library import candidate filtering."
  (:require [ygg.dependency.imports.common :as import-common]))

(def stdlib-roots
  #{"argparse" "asyncio" "base64" "collections" "contextlib" "csv" "dataclasses"
    "datetime" "decimal" "enum" "functools" "gzip" "hashlib" "http" "importlib"
    "inspect" "itertools" "json" "logging" "math" "os" "pathlib" "re" "socket"
    "sqlite3" "statistics" "string" "subprocess" "sys" "tempfile" "time" "typing"
    "unittest" "urllib" "uuid" "xml"})

(defn external-package-candidate?
  [target]
  (not (contains? stdlib-roots (import-common/dotted-root target))))
