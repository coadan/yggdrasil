(ns agraph.dependency.imports.common
  "Shared mechanical import-name helpers for dependency candidate filtering."
  (:require [clojure.string :as str]))

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
