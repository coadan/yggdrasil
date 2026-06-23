(ns ygg.headline-bench-script-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- run-headline
  [& args]
  (apply shell/sh "bash" "scripts/headline-bench.sh" args))

(defn- run-agent-efficiency
  [& args]
  (apply shell/sh "bash" "scripts/agent-efficiency-bench.sh" args))

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
                             "--out" ".dev/ygg/headline-bench/custom"
                             "--agent" "codex-test"
                             "--timeout-ms" "12345")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 11 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench agent-baseline benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 1)
                       "bb bench agent-report benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 2)
                       "bb bench agent-run benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 2) "--mode shell-only"))
    (is (str/includes? (nth lines 2) "--agent codex-test"))
    (is (str/includes? (nth lines 2) "--timeout-ms 12345"))
    (is (str/includes? (nth lines 2) "codex-benchmark-agent.py"))
    (is (str/includes? (nth lines 3) "--mode ygg"))
    (is (str/includes? (nth lines 4) "--mode shell-only"))
    (is (str/includes? (nth lines 5) "--mode ygg"))
    (is (str/includes? (nth lines 6) "scripts/prompt-token-measure.py"))
    (is (str/includes? (nth lines 6)
                       "--out .dev/ygg/headline-bench/custom/prompt-token-measure.json"))
    (is (str/includes? (nth lines 7) "scripts/prompt-token-gate.py"))
    (is (str/includes? (nth lines 7)
                       ".dev/ygg/headline-bench/custom/prompt-token-measure.json"))
    (is (str/includes? (nth lines 7)
                       "--out .dev/ygg/headline-bench/custom/prompt-token-gate.json"))
    (is (str/includes? (nth lines 8)
                       "bb bench agent-check benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 8) "--mode shell-only"))
    (is (str/includes? (nth lines 8) "--max-total-tokens 999999999999"))
    (is (str/includes? (nth lines 9) "--mode ygg"))
    (is (str/includes? (nth lines 10)
                       "bb bench claim-pack benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 10)
                       "--shell-report .dev/ygg/headline-bench/custom/shell-only/\\*/agent-report.json"))
    (is (str/includes? (nth lines 10)
                       "--ygg-report .dev/ygg/headline-bench/custom/ygg/\\*/agent-report.json"))
    (is (str/includes? (nth lines 10)
                       "--out .dev/ygg/headline-bench/custom/claim-pack"))
    (is (every? #(str/includes? % ".dev/ygg/headline-bench/custom")
                lines))))

(deftest dry-run-can-skip-token-check
  (let [result (run-headline "all"
                             "--dry-run"
                             "--skip-token-check"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 7 (count lines)))
    (is (not-any? #(str/includes? % "bench agent-check") lines))
    (is (not-any? #(str/includes? % "prompt-token-") lines))))

(deftest dry-run-can-skip-existing-benchmark-artifacts
  (let [result (run-headline "all"
                             "--dry-run"
                             "--skip-existing"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (str/includes? (nth lines 0) "--skip-existing"))
    (is (str/includes? (nth lines 2) "--skip-existing"))
    (is (str/includes? (nth lines 3) "--skip-existing"))
    (is (not (str/includes? (nth lines 1) "--skip-existing")))
    (is (not-any? #(and (str/includes? % "bench agent-report")
                        (str/includes? % "--skip-existing"))
                  lines))))

(deftest dry-run-propagates-case-filter-to-benchmark-phases
  (let [result (run-headline "all"
                             "--dry-run"
                             "--case" "case-1"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 11 (count lines)))
    (doseq [idx [0 1 2 3 4 5 8 9]]
      (is (str/includes? (nth lines idx) "--case case-1")))
    (doseq [idx [6 7 10]]
      (is (not (str/includes? (nth lines idx) "--case case-1"))))))

(deftest dry-run-prints-broad-agent-efficiency-defaults
  (let [result (run-agent-efficiency "token-check"
                                     "--dry-run"
                                     "--agent" "codex-test"
                                     "--max-total-tokens" "123")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 2 (count lines)))
    (is (every? #(str/includes? % "benchmarks/agent-efficiency-broad.edn")
                lines))
    (is (every? #(str/includes? % ".dev/ygg/agent-efficiency/broad")
                lines))
    (is (every? #(str/includes? % "--max-total-tokens 123")
                lines))))

(deftest help-lists-headline-actions-and-dry-run
  (let [result (run-headline "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result)
                       "baseline|codebase-memory|external-baselines|shell-only|ygg|agents|reports|prompt-token-check|stage-time-check|token-check|compare|claim-pack|all"))
    (is (str/includes? (:out result) "--dry-run"))))

(deftest dry-run-prints-prompt-token-check-workflow
  (let [result (run-headline "prompt-token-check"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom"
                             "--min-prompt-shared-cases" "5")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 2 (count lines)))
    (is (str/includes? (nth lines 0) "scripts/prompt-token-measure.py"))
    (is (str/includes? (nth lines 0) ".dev/ygg/headline-bench/custom"))
    (is (str/includes? (nth lines 0)
                       "--out .dev/ygg/headline-bench/custom/prompt-token-measure.json"))
    (is (str/includes? (nth lines 1) "scripts/prompt-token-gate.py"))
    (is (str/includes? (nth lines 1)
                       ".dev/ygg/headline-bench/custom/prompt-token-measure.json"))
    (is (str/includes? (nth lines 1) "--min-shared-cases 5"))
    (is (str/includes? (nth lines 1)
                       "--out .dev/ygg/headline-bench/custom/prompt-token-gate.json"))))

(deftest dry-run-prints-stage-time-check-workflow
  (let [result (run-headline "stage-time-check"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom"
                             "--max-stage-elapsed-ms" "120000")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 1 (count lines)))
    (is (str/includes? (nth lines 0) "scripts/stage-time-gate.py"))
    (is (str/includes? (nth lines 0)
                       ".dev/ygg/headline-bench/custom/ygg-baseline/\\*/agent-report.json"))
    (is (str/includes? (nth lines 0) "--max-case-stage-ms 120000"))
    (is (str/includes? (nth lines 0)
                       "--out .dev/ygg/headline-bench/custom/stage-time-gate.json"))))

(deftest dry-run-all-runs-stage-time-check-when-threshold-is-set
  (let [result (run-headline "all"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom"
                             "--max-stage-elapsed-ms" "120000")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 12 (count lines)))
    (is (str/includes? (nth lines 10) "scripts/stage-time-gate.py"))
    (is (str/includes? (nth lines 11)
                       "bb bench claim-pack benchmarks/custom-headline.edn"))))

(deftest dry-run-prints-codebase-memory-workflow
  (let [result (run-headline "codebase-memory"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom"
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
    (is (str/includes? (nth lines 1) "--agent ygg-baseline-codebase-memory"))))

(deftest dry-run-prints-external-baseline-workflow
  (let [result (run-headline "external-baselines"
                             "--dry-run"
                             "--suite" "benchmarks/custom-headline.edn"
                             "--out" ".dev/ygg/headline-bench/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench agent-baseline benchmarks/custom-headline.edn"))
    (is (str/includes? (nth lines 1) "--agent ygg-baseline-lexical"))
    (is (str/includes? (nth lines 2) "--retriever codebase-memory"))
    (is (str/includes? (nth lines 3) "--mode codebase-memory"))))
