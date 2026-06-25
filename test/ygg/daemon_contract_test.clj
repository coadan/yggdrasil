(ns ygg.daemon-contract-test
  (:require [ygg.daemon-contract :as daemon-contract]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest direct-entrypoint-response-uses-canonical-failure-shape
  (let [response (daemon-contract/direct-entrypoint-response "ygg.demo" "ygg demo")]
    (is (= false (:ok response)))
    (is (= daemon-contract/unavailable-exit (:exit response)))
    (is (= "" (:out response)))
    (is (str/includes? (:err response) "Direct ygg.demo entrypoint is disabled."))
    (is (str/includes? (:err response) "ygg init"))
    (is (str/includes? (:err response) "ygg start"))
    (is (str/includes? (:err response) "ygg demo"))))
