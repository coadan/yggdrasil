(ns agraph.headline-bench-script-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- run-headline
  [& args]
  (apply shell/sh "bash" "scripts/headline-bench.sh" args))

(defn- output-lines
  [result]
  (->> (:out result)
       str/split-lines
       (remove str/blank?)
       vec))

(deftest dry-run-prints-repeatable-headline-workflow
  (let [result (run-headline "all"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/agraph/headline-bench/custom"
                             "--agent" "codex-test"
                             "--timeout-ms" "12345")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 7 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench agent-baseline benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 1)
                       "bb bench agent-report benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 2)
                       "bb bench agent-run benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 2) "--mode shell-only"))
    (is (str/includes? (nth lines 2) "--agent codex-test"))
    (is (str/includes? (nth lines 2) "--timeout-ms 12345"))
    (is (str/includes? (nth lines 3) "--mode agraph"))
    (is (str/includes? (nth lines 4) "--mode shell-only"))
    (is (str/includes? (nth lines 5) "--mode agraph"))
    (is (str/includes? (nth lines 6)
                       "bb efficiency .dev/agraph/headline-bench/custom/shell-only/agent-report.json"))
    (is (every? #(str/includes? % ".dev/agraph/headline-bench/custom")
                lines))))

(deftest help-lists-headline-actions-and-dry-run
  (let [result (run-headline "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result)
                       "baseline|shell-only|agraph|agents|reports|compare|all"))
    (is (str/includes? (:out result) "--dry-run"))))
