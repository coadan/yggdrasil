(ns agraph.search-doc-test
  (:require [agraph.search-doc :as search-doc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest build-search-docs-includes-neighbor-labels
  (let [docs (search-doc/build-search-docs
              "run/test"
              {:nodes [{:xt/id "node:symbol:Source"
                        :label "demo/Source"
                        :kind :function
                        :file-id "file:demo"
                        :path "src/Demo.java"
                        :active? true}
                       {:xt/id "node:symbol:Target"
                        :label "demo/Target"
                        :kind :class
                        :file-id "file:demo"
                        :path "src/Demo.java"
                        :active? true}]
               :edges [{:source-id "node:symbol:Source"
                        :target-id "node:symbol:Target"
                        :relation :references}]
               :chunks []})
        by-target (into {} (map (juxt :target-id identity)) docs)]
    (is (str/includes? (get-in by-target ["node:symbol:Source" :text])
                       "references demo/Target"))
    (is (str/includes? (get-in by-target ["node:symbol:Target" :text])
                       "references by demo/Source"))))
