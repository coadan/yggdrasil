(ns ygg.dependency.imports.common
  "Shared mechanical import-name helpers for dependency candidate filtering."
  (:require [clojure.string :as str]))

(defn namespace-target
  [target-id]
  (some-> (re-find #"node:namespace:(.+)$" (str target-id))
          second))

(defn- dotted-prefixes
  [value]
  (let [parts (vec (remove str/blank? (str/split (str value) #"\.")))]
    (mapv #(str/join "." (take % parts))
          (range 1 (inc (count parts))))))

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

(defn local-namespace-targets
  [nodes]
  (->> nodes
       (mapcat (fn [node]
                 (when (seq (:path node))
                   (if (= :namespace (:kind node))
                     [(namespace-target (:xt/id node))]
                     (some-> (or (:label node) (:name node))
                             str
                             (str/replace "/" ".")
                             dotted-prefixes)))))
       (remove str/blank?)
       set))

(defn local-namespace-import?
  ([nodes-by-id edge]
   (local-namespace-import? nodes-by-id
                            (local-namespace-targets (vals nodes-by-id))
                            edge))
  ([_nodes-by-id local-namespace-targets edge]
   (contains? local-namespace-targets
              (namespace-target (:target-id edge)))))

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
