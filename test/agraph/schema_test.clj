(ns agraph.schema-test
  (:require [agraph.schema :as schema]
            [clojure.test :refer [deftest is]]))

(deftest assert-validates-with-cached-validator
  (let [row {:xt/id "file:demo"
             :path "src/demo.clj"
             :ext ".clj"
             :kind :clojure
             :content-sha "abc"
             :mtime-ms 1
             :size-bytes 2
             :active? true}]
    (is (= row (schema/assert! schema/file-row row "Invalid file row.")))))

(deftest assert-reports-explain-data-on-invalid-value
  (let [error (try
                (schema/assert! schema/file-row
                                {:xt/id "file:bad"
                                 :path "bad.clj"}
                                "Invalid file row.")
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
    (is (= "Invalid file row." (ex-message error)))
    (is (map? (:explain (ex-data error))))))
