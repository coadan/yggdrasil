(ns agraph.benchmark-classes
  "Shared benchmark class tag predicates."
  (:require [clojure.string :as str]))

(def architecture-class-tags
  #{"architecture-boundary"
    "architecture-runtime-boundary"
    "architecture-dependency-flow"
    "architecture-data-ownership"
    "architecture-cross-system-impact"
    "audit-scope-runtime-config"
    "audit-scope-containers"
    "audit-scope-docs"})

(defn problem-class-tag?
  [tag]
  (and (string? tag)
       (str/starts-with? tag "problem-")))

(defn architecture-class-tag?
  [tag]
  (contains? architecture-class-tags tag))
