(ns ygg.dependency.imports.java
  "Java platform import candidate filtering."
  (:require [ygg.dependency.imports.common :as import-common]
            [clojure.string :as str]))

(def builtin-roots
  #{"java" "javax" "jdk" "sun"})

(def builtin-prefixes
  #{"com.sun" "org.w3c" "org.xml.sax"})

(def type-symbol-kinds
  #{:class :interface :enum :record :annotation :java-symbol :symbol})

(defn- dotted-symbol-labels
  [target]
  (let [parts (vec (remove str/blank? (str/split (str target) #"\.")))]
    (->> (range 1 (count parts))
         (map (fn [idx]
                (str (str/join "." (subvec parts 0 idx))
                     "/"
                     (str/join "." (subvec parts idx)))))
         distinct
         vec)))

(defn- dotted-symbol-owner-labels
  [target]
  (let [parts (vec (remove str/blank? (str/split (str target) #"\.")))]
    (->> (range 1 (count parts))
         (mapcat (fn [namespace-end]
                   (let [namespace-name (str/join "."
                                                  (subvec parts 0 namespace-end))
                         symbol-parts (subvec parts namespace-end)]
                     (->> (range 1 (count symbol-parts))
                          (map (fn [symbol-end]
                                 (str namespace-name
                                      "/"
                                      (str/join "."
                                                (subvec symbol-parts
                                                        0
                                                        symbol-end)))))))))
         distinct
         vec)))

(defn- scoped-symbol-id
  [target-id symbol-label]
  (when-let [[_ scope] (re-matches #"^(.*)node:namespace:.+$"
                                   (str target-id))]
    (str scope "node:symbol:" symbol-label)))

(defn- local-symbol-node?
  [node]
  (seq (:path node)))

(defn- local-type-symbol-node?
  [node]
  (and (local-symbol-node? node)
       (contains? type-symbol-kinds (:kind node))))

(defn- local-scoped-symbol?
  [nodes-by-id target-id symbol-label predicate]
  (when-let [node (get nodes-by-id (scoped-symbol-id target-id symbol-label))]
    (predicate node)))

(defn- local-namespace-prefix?
  [local-namespace-targets target]
  (let [target (str target)]
    (boolean
     (some (fn [namespace-name]
             (and (seq namespace-name)
                  (str/starts-with? target (str namespace-name "."))))
           local-namespace-targets))))

(defn- builtin-prefix?
  [target]
  (let [target (str target)]
    (some #(or (= target %)
               (str/starts-with? target (str % ".")))
          builtin-prefixes)))

(defn runtime-import?
  [target]
  (or (contains? builtin-roots (import-common/dotted-root target))
      (builtin-prefix? target)))

(defn external-package-candidate?
  [target]
  (not (runtime-import? target)))

(defn local-import?
  [{:keys [nodes-by-id local-namespace-targets edge target kind]}]
  (and (= :java kind)
       (or (local-namespace-prefix? local-namespace-targets target)
           (some #(local-scoped-symbol? nodes-by-id
                                        (:target-id edge)
                                        %
                                        local-symbol-node?)
                 (dotted-symbol-labels target))
           (some #(local-scoped-symbol? nodes-by-id
                                        (:target-id edge)
                                        %
                                        local-type-symbol-node?)
                 (dotted-symbol-owner-labels target)))))
