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
                       "bb efficiency .dev/agraph/headline-bench/custom/shell-only/\\*/agent-report.json"))
    (is (str/includes? (nth lines 6) "--markdown-out"))
    (is (str/includes? (nth lines 6)
                       ".dev/agraph/headline-bench/custom/REPORT.md"))
    (is (every? #(str/includes? % ".dev/agraph/headline-bench/custom")
                lines))))

(deftest help-lists-headline-actions-and-dry-run
  (let [result (run-headline "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result)
                       "baseline|codebase-memory|external-baselines|shell-only|agraph|agents|reports|compare|all"))
    (is (str/includes? (:out result) "--dry-run"))))

(deftest dry-run-prints-codebase-memory-workflow
  (let [result (run-headline "codebase-memory"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/agraph/headline-bench/custom"
                             "--codebase-memory-bin" "fake-codebase-memory-mcp"
                             "--codebase-memory-command" "fake-codebase-memory-worker")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 2 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench agent-baseline benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 0) "--retriever codebase-memory"))
    (is (str/includes? (nth lines 0) "--codebase-memory-bin fake-codebase-memory-mcp"))
    (is (str/includes? (nth lines 0)
                       "--codebase-memory-command fake-codebase-memory-worker"))
    (is (str/includes? (nth lines 1)
                       "bb bench agent-report benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 1) "--mode codebase-memory"))
    (is (str/includes? (nth lines 1) "--agent agraph-baseline-codebase-memory"))))

(deftest dry-run-prints-external-baseline-workflow
  (let [result (run-headline "external-baselines"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/agraph/headline-bench/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench agent-baseline benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 1) "--agent agraph-baseline-lexical"))
    (is (str/includes? (nth lines 2) "--retriever codebase-memory"))
    (is (str/includes? (nth lines 3) "--mode codebase-memory"))))
