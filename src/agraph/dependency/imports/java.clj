(ns agraph.dependency.imports.java
  "Java platform import candidate filtering."
  (:require [agraph.dependency.imports.common :as import-common]))

(def builtin-roots
  #{"java" "javax" "jdk" "sun"})

(def builtin-prefixes
  #{"com.sun"})

(defn runtime-import?
  [target]
  (or (contains? builtin-roots (import-common/dotted-root target))
      (contains? builtin-prefixes (import-common/dotted-prefix target 2))))

(defn external-package-candidate?
  [target]
  (not (runtime-import? target)))
