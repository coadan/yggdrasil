(ns ygg.dependency.imports.common
  "Shared mechanical import-name helpers for dependency candidate filtering."
  (:require [clojure.string :as str]))

(defn namespace-target
  [target-id]
  (some-> (re-find #"node:namespace:(.+)$" (str target-id))
          second))

(defn dirname
  [path]
  (let [idx (.lastIndexOf (str path) "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn extension
  [path]
  (some-> (re-find #"\.([A-Za-z0-9]+)$" (str path))
          second
          str/lower-case))

(defn ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(defn dotted-root
  [target]
  (first (str/split (str target) #"\.")))

(defn dotted-prefix
  [target n]
  (let [parts (remove str/blank? (str/split (str target) #"\."))]
    (when (>= (count parts) n)
      (str/join "." (take n parts)))))

(defn slash-root
  [target]
  (first (str/split (str target) #"/")))

(defn local-namespace-import?
  [nodes-by-id edge]
  (let [target (get nodes-by-id (:target-id edge))]
    (and (= :namespace (:kind target))
         (seq (:path target)))))

(defn module-path-alias-node?
  [node]
  (= :module-path-alias (:kind node)))

(defn- alias-pattern-prefix
  [pattern]
  (let [pattern (str pattern)]
    (if-let [idx (str/index-of pattern "*")]
      (subs pattern 0 idx)
      pattern)))

(defn- alias-pattern-match?
  [pattern target]
  (let [prefix (alias-pattern-prefix pattern)
        target (str target)]
    (if (str/includes? (str pattern) "*")
      (str/starts-with? target prefix)
      (or (= target prefix)
          (str/starts-with? target (str prefix "/"))
          (str/starts-with? target (str prefix "."))))))

(defn- module-path-alias-match?
  [alias-node edge target]
  (when-let [[_ alias-pattern]
             (re-matches #"^(.+?)=.*$" (str (:label alias-node)))]
    (and (ancestor-dir? (dirname (:path alias-node)) (:path edge))
         (alias-pattern-match? alias-pattern target))))

(defn local-path-alias-import?
  [alias-nodes edge target]
  (some #(module-path-alias-match? % edge target)
        alias-nodes))
