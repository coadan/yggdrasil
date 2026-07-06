(ns ygg.benchmark-classes
  "Shared benchmark class tag predicates."
  (:require [clojure.string :as str]))

(def architecture-class-tags
  #{"architecture-boundary"
    "architecture-runtime-boundary"
    "architecture-dependency-flow"
    "architecture-data-ownership"
    "architecture-cross-system-impact"
    "audit-scope-dependencies"
    "audit-scope-runtime-config"
    "audit-scope-containers"
    "audit-scope-docs"})

(def recall-class-tags
  #{"recall-graph"
    "recall-hybrid"
    "recall-lexical"
    "recall-semantic"})

(defn problem-class-tag?
  [tag]
  (and (string? tag)
       (str/starts-with? tag "problem-")))

(defn architecture-class-tag?
  [tag]
  (contains? architecture-class-tags tag))

(defn docs-problem-class-tag?
  [tag]
  (and (problem-class-tag? tag)
       (str/starts-with? tag "problem-docs")))

(defn docs-architecture-class-tag?
  [tag]
  (= "audit-scope-docs" tag))

(defn recall-class-tag?
  [tag]
  (contains? recall-class-tags tag))
