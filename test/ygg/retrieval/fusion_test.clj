(ns ygg.retrieval.fusion-test
  (:require [ygg.retrieval.fusion :as fusion]
            [clojure.test :refer [deftest is]]))

(deftest rank-map-orders-positive-scores-deterministically
  (is (= {"a" 1
          "b" 2
          "c" 3}
         (fusion/rank-map {"b" 0.8
                           "c" 0.2
                           "ignored" 0.0
                           "a" 0.8}))))

(deftest weighted-fuse-preserves-weighted-score-composition
  (is (= {"target:a" 1.2
          "target:b" 0.5}
         (fusion/weighted-fuse {:lexical {"target:a" 1.0
                                          "target:b" 0.5}
                                :grep {"target:a" 1.0}}
                               {:lexical 1.0
                                :grep 0.2}))))

(deftest rrf-fuse-combines-source-ranks
  (let [scores (fusion/rrf-fuse {:semantic {"a" 0.9
                                            "b" 0.8}
                                 :lexical {"b" 1.0
                                           "c" 0.7}}
                                {:k 60})]
    (is (< (scores "a") (scores "b")))
    (is (< (scores "c") (scores "b")))
    (is (= {:lexical 2
            :semantic 2}
           (fusion/source-counts {:semantic {"a" 0.9
                                             "b" 0.8}
                                  :lexical {"b" 1.0
                                            "c" 0.7}})))
    (is (= 1
           (fusion/overlap-count {:semantic {"a" 0.9
                                             "b" 0.8}
                                  :lexical {"b" 1.0
                                            "c" 0.7}})))))
