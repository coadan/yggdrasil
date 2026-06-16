(ns sample.core
  (:require [sample.util :as util]))

(def config
  {:message "hello"})

(defn greet
  [name]
  (util/shout (str "hello " name)))

(defn- helper
  []
  (greet "world"))
