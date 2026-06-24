(ns ygg.benchmark-command-telemetry-test
  (:require [clojure.test :refer [deftest is]]
            [ygg.benchmark-command-telemetry :as telemetry]))

(deftest splits-broad-scoped-and-exact-file-searches
  (let [summary (telemetry/command-telemetry
                 ["rg broken src"
                  "rg --hidden --fixed-strings broken src"
                  "git grep broken -- src"
                  "rg -n broken src/app.clj"
                  "rg --glob=*.clj broken ./src/app.clj"
                  "fd -t f broken src"]
                 {:candidate-paths ["src/app.clj"]})]
    (is (= {:commandCount 6
            :searchCommandCount 6
            :broadSearchCommandCount 3
            :scopedSearchCommandCount 3
            :exactFileSearchCommandCount 2}
           (select-keys summary
                        [:commandCount
                         :searchCommandCount
                         :broadSearchCommandCount
                         :scopedSearchCommandCount
                         :exactFileSearchCommandCount])))))

(deftest aggregate-command-telemetry-tolerates-older-rows
  (let [summary (telemetry/aggregate-command-telemetry
                 [{:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 1
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}
                  {:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 1
                                      :broadSearchCommandCount 1
                                      :scopedSearchCommandCount 0
                                      :exactFileSearchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}])]
    (is (= {:commandCount 2
            :yggCommandCount 0
            :searchCommandCount 2
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 0
            :exactFileSearchCommandCount 0
            :fileReadCommandCount 0
            :shellCommandCount 0}
           summary))))
