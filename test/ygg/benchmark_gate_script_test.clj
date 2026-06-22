(ns ygg.benchmark-gate-script-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- run-gate
  [& args]
  (apply shell/sh "bash" "scripts/benchmark-gate.sh" args))

(defn- output-lines
  [result]
  (->> (:out result)
       str/split-lines
       (remove str/blank?)
       vec))

(deftest dry-run-prints-full-strict-gate
  (let [result (run-gate "--dry-run"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 3 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench:repos check --manifest benchmarks/custom-repos.edn --suite benchmarks/custom.edn"))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/custom.edn"))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/custom.edn"))
    (is (str/includes? (nth lines 2)
                       "--max-maintenance-preflight-blockers 0"))
    (is (str/includes? (nth lines 2)
                       "--max-case-total-tokens 24000"))))

(deftest check-only-dry-run-skips-baseline-and-keeps-strict-checks
  (let [result (run-gate "--dry-run"
                         "--check-only"
                         "--case" "case-1"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 2 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench:repos check --manifest benchmarks/custom-repos.edn --suite benchmarks/custom.edn"))
    (is (not-any? #(str/includes? % "agent-baseline") lines))
    (is (str/includes? (nth lines 1)
                       "bench agent-check benchmarks/custom.edn --case case-1"))
    (is (str/includes? (nth lines 1) "--min-cases 1"))
    (is (str/includes? (nth lines 1) "--min-runs 1"))
    (is (str/includes? (nth lines 1) "--max-unverified-score-runs 0"))
    (is (str/includes? (nth lines 1)
                       "--max-maintenance-preflight-blockers 0"))
    (is (str/includes? (nth lines 1)
                       "--max-case-total-tokens 24000"))))

(deftest help-lists-check-only
  (let [result (run-gate "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result) "--check-only"))
    (is (str/includes? (:out result) "current artifacts already"))))
