(ns ygg.benchmark-expectations-test
  (:require [ygg.benchmark-expectations :as benchmark-expectations]
            [ygg.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(deftest graph-expectations-use-constrained-project-reads
  (let [calls (atom [])]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints & [_ctx]]
                    (swap! calls conj [table constraints])
                    (case table
                      :ygg/system-evidence []
                      :ygg/nodes [{:xt/id "node:a"
                                   :project-id "fixture"
                                   :kind :function
                                   :path "src/app.clj"}]
                      :ygg/chunks []
                      :ygg/system-edges []))]
      (is (= "passed"
             (:status
              (benchmark-expectations/evaluate-graph-expectations
               :xtdb
               {:project-id "fixture"
                :expectations {:nodes [{:xt/id "node:a"}]}})))))
    (is (= #{[(:system-evidence store/tables) {:project-id "fixture"}]
             [(:nodes store/tables) {:project-id "fixture"}]
             [(:chunks store/tables) {:project-id "fixture"}]
             [(:system-edges store/tables) {:project-id "fixture"}]}
           (set @calls)))))
