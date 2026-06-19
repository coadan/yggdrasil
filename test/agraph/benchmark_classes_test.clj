(ns agraph.benchmark-classes-test
  (:require [agraph.benchmark-classes :as benchmark-classes]
            [clojure.test :refer [are deftest is]]))

(deftest architecture-class-tags-include-audit-scope-tags
  (is (= #{"architecture-boundary"
           "architecture-runtime-boundary"
           "architecture-dependency-flow"
           "architecture-data-ownership"
           "architecture-cross-system-impact"
           "audit-scope-runtime-config"
           "audit-scope-containers"
           "audit-scope-docs"}
         benchmark-classes/architecture-class-tags))
  (are [tag] (benchmark-classes/architecture-class-tag? tag)
    "architecture-boundary"
    "architecture-runtime-boundary"
    "architecture-dependency-flow"
    "architecture-data-ownership"
    "architecture-cross-system-impact"
    "audit-scope-runtime-config"
    "audit-scope-containers"
    "audit-scope-docs"))

(deftest problem-class-tags-stay-separate-from-architecture-tags
  (are [tag] (benchmark-classes/problem-class-tag? tag)
    "problem-architecture"
    "problem-localization"
    "problem-cross-file")
  (are [tag] (not (benchmark-classes/architecture-class-tag? tag))
    "problem-architecture"
    "problem-localization"
    "problem-cross-file"))

(deftest class-tag-predicates-ignore-non-string-values
  (are [tag] (not (benchmark-classes/problem-class-tag? tag))
    nil
    :problem-architecture
    "architecture-dependency-flow"
    "audit-scope-docs")
  (are [tag] (not (benchmark-classes/architecture-class-tag? tag))
    nil
    :architecture-dependency-flow
    "problem-architecture"
    "headline"))
