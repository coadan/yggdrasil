(ns ygg.extract.package-toml
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn toml-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*=\\s*\"([^\"]+)\""))
                   content)
          second))
(defn toml-section-lines
  [content section-name]
  (loop [remaining (str/split-lines content)
         in-section? false
         out []]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining) (= trimmed (str "[" section-name "]")) out)

          in-section?
          (recur (rest remaining) in-section? (conj out line))

          :else
          (recur (rest remaining) in-section? out)))
      out)))
(defn toml-string-or-array-value
  [line]
  (or (some-> (re-find #"=\s*\"([^\"]+)\"" line) second vector)
      (some-> (re-find #"=\s*(\[[^\]]*\])" line) second common/toml-array-strings)))
