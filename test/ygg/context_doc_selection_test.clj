(ns ygg.context-doc-selection-test
  (:require [clojure.test :refer [deftest is]]
            [ygg.context :as context]))

(defn- doc
  [path score]
  {:source {:repo "repo"
            :path path
            :lines "1-2"}
   :role "reference"
   :score score
   :provenance "retrieved-doc"})

(deftest select-docs-bounds-diversification-and-preserves-ranked-paths
  (let [select-docs @#'context/select-docs
        docs (concat (map (fn [idx]
                            (doc (str "docs/noise-" idx ".md")
                                 (- 10000 idx)))
                          (range 500))
                     [(doc "src/important.clj" 1)])
        results [{:path "src/important.clj"}]
        diversified-count (atom nil)
        selected (with-redefs-fn {#'context/diversify-docs
                                  (fn [_ docs]
                                    (reset! diversified-count (count docs))
                                    docs)}
                   #(select-docs docs results 10 ["important"]))]
    (is (< @diversified-count (count docs)))
    (is (= "src/important.clj"
           (get-in (first selected) [:source :path])))))
