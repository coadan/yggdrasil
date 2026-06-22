(ns agraph.dependency.imports.rust
  "Rust runtime import candidate filtering."
  (:require [clojure.string :as str]))

(def builtin-roots
  #{"alloc" "core" "crate" "self" "std" "super"})

(defn import-root
  [target]
  (first (str/split (str target) #"::")))

(defn external-package-candidate?
  [target]
  (let [root (import-root target)]
    (and (seq root)
         (not (contains? builtin-roots root)))))
