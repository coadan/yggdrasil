(ns sample.core-test
  (:require [clojure.test :refer [deftest is]]
            [sample.core :as core]))

(deftest greet-test
  (is (= "HELLO WORLD" (core/greet "world"))))
