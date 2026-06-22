(ns agraph.dependency.imports.java
  "Java platform import candidate filtering."
  (:require [agraph.dependency.imports.common :as import-common]
            [clojure.string :as str]))

(def builtin-roots
  #{"java" "javax" "jdk" "sun"})

(def builtin-prefixes
  #{"com.sun" "org.w3c" "org.xml.sax"})

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
