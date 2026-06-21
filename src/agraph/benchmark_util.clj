(ns agraph.benchmark-util
  (:require [clojure.string :as str]))

(defn blankish?
  [value]
  (str/blank? (str value)))
