(ns ygg.hash-test
  (:require [ygg.hash :as hash]
            [clojure.test :refer [deftest is]]))

(deftest sha256-hex-matches-known-values
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (hash/sha256-hex "")))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (hash/sha256-hex "abc")))
  (is (= (hash/sha256-hex "abc")
         (hash/sha256-bytes-hex (.getBytes "abc" "UTF-8")))))
