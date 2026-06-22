(ns ygg.dependency.imports.dotnet
  "Dotnet runtime import candidate filtering."
  (:require [ygg.dependency.imports.common :as import-common]))

(def builtin-roots
  #{"System"})

(defn runtime-import?
  [target]
  (contains? builtin-roots (import-common/dotted-root target)))

(defn external-package-candidate?
  [target]
  (not (runtime-import? target)))
