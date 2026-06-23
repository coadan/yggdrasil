(ns ygg.benchmark-gate-script-test
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ygg.benchmark-test-support :refer [spit-file! spit-json! temp-dir]]))

(defn- run-gate
  [& args]
  (apply shell/sh "bash" "scripts/benchmark-gate.sh" args))

(defn- output-lines
  [result]
  (->> (:out result)
       str/split-lines
       (remove str/blank?)
       vec))

(defn- run-prompt-gate
  [& args]
  (apply shell/sh "python3" "scripts/prompt-token-gate.py" args))

(defn- run-prompt-measure
  [& args]
  (apply shell/sh "python3" "scripts/prompt-token-measure.py" args))

(defn- estimate-prompt-tokens
  [prompt]
  (long (Math/ceil (/ (count (json/write-json-str prompt)) 4.0))))

(defn- prompt-row
  [mode case-id prompt]
  {:mode mode
   :caseId case-id
   :promptBytes (count (.getBytes prompt "UTF-8"))
   :estimatedPromptTokens (estimate-prompt-tokens prompt)})

(defn- prompt-report!
  [root {:keys [case-id shell-prompt ygg-prompt]}]
  (let [source "run"
        prompt-file (fn [mode prompt]
                      (spit-file! root
                                  (str source
                                       "/"
                                       mode
                                       "/suite/cases/"
                                       case-id
                                       "/agent-prompts/probe.md")
                                  prompt))]
    (prompt-file "shell-only" shell-prompt)
    (prompt-file "ygg" ygg-prompt)
    (spit-json! root
                "measure.json"
                {:schema "ygg.dev.prompt-token-measure/v1"
                 :suite "fixture"
                 :source (.getPath (io/file root source))
                 :rows [(prompt-row "shell-only" case-id shell-prompt)
                        (prompt-row "ygg" case-id ygg-prompt)]})))

(defn- prompt-report-with-relative-source!
  [root opts]
  (let [report-path (prompt-report! root opts)
        report (json/read-json (slurp report-path) :key-fn keyword)]
    (spit report-path
          (json/write-json-str (assoc report :source "run")
                               {:indent-str "  "}))
    report-path))

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

(deftest prompt-token-gate-passes-when-ygg-prompt-is-smaller
  (let [root (temp-dir "ygg-prompt-token-gate-pass")
        report-path (prompt-report!
                     root
                     {:case-id "case-1"
                      :shell-prompt "shell prompt with a longer contract and repeated instructions"
                      :ygg-prompt "short ygg prompt"})
        result (run-prompt-gate report-path "--min-shared-cases" "1")
        parsed (json/read-json (:out result) :key-fn keyword)]
    (is (= 0 (:exit result)) (:out result))
    (is (= "passed" (:status parsed)))
    (is (= 1 (:sharedCases parsed)))
    (is (neg? (:totalTokenDelta parsed)))
    (is (true? (:allYggReduced parsed)))))

(deftest prompt-token-measure-scans-prompts-and-gate-writes-artifact
  (let [root (temp-dir "ygg-prompt-token-measure")
        source (io/file root "run")
        measure-path (io/file root "measure.json")
        gate-path (io/file root "gate.json")]
    (spit-file! root
                "run/shell-only/suite/cases/case-1/agent-prompts/probe.md"
                "shell prompt with a longer contract and repeated instructions")
    (spit-file! root
                "run/ygg/suite/cases/case-1/agent-prompts/probe.md"
                "short ygg prompt")
    (let [measure-result (run-prompt-measure (.getPath source)
                                             "--suite" "fixture"
                                             "--out" (.getPath measure-path))
          measure (json/read-json (slurp measure-path) :key-fn keyword)
          gate-result (run-prompt-gate (.getPath measure-path)
                                       "--out" (.getPath gate-path))
          gate-stdout (json/read-json (:out gate-result) :key-fn keyword)
          gate-file (json/read-json (slurp gate-path) :key-fn keyword)]
      (is (= 0 (:exit measure-result)) (:out measure-result))
      (is (= "ygg.dev.prompt-token-measure/v1" (:schema measure)))
      (is (= "fixture" (:suite measure)))
      (is (= "run" (:source measure)))
      (is (= 2 (count (:rows measure))))
      (is (= 1 (get-in measure [:summary :sharedCases])))
      (is (true? (get-in measure [:summary :allYggReduced])))
      (is (= 0 (:exit gate-result)) (:out gate-result))
      (is (= "passed" (:status gate-stdout)))
      (is (= gate-stdout gate-file)))))

(deftest prompt-token-gate-resolves-relative-source-from-report-directory
  (let [root (temp-dir "ygg-prompt-token-gate-relative")
        report-path (prompt-report-with-relative-source!
                     root
                     {:case-id "case-1"
                      :shell-prompt "shell prompt with a longer contract and repeated instructions"
                      :ygg-prompt "short ygg prompt"})
        result (run-prompt-gate report-path)
        parsed (json/read-json (:out result) :key-fn keyword)]
    (is (= 0 (:exit result)) (:out result))
    (is (= "passed" (:status parsed)))
    (is (str/ends-with? (:source parsed) "/run"))))

(deftest prompt-token-gate-fails-when-report-does-not-match-artifact
  (let [root (temp-dir "ygg-prompt-token-gate-stale")
        report-path (prompt-report!
                     root
                     {:case-id "case-1"
                      :shell-prompt "shell prompt with a longer contract"
                      :ygg-prompt "short ygg prompt"})
        report (json/read-json (slurp report-path) :key-fn keyword)
        stale (assoc-in report [:rows 1 :estimatedPromptTokens] 999)]
    (spit report-path (json/write-json-str stale {:indent-str "  "}))
    (let [result (run-prompt-gate report-path)
          parsed (json/read-json (:out result) :key-fn keyword)]
      (is (= 1 (:exit result)))
      (is (= "failed" (:status parsed)))
      (is (some #(str/includes? % "estimatedPromptTokens mismatch")
                (:failures parsed))))))

(deftest prompt-token-gate-fails-without-per-case-reduction
  (let [root (temp-dir "ygg-prompt-token-gate-regression")
        report-path (prompt-report!
                     root
                     {:case-id "case-1"
                      :shell-prompt "short shell prompt"
                      :ygg-prompt "longer ygg prompt with extra guidance and context"})
        result (run-prompt-gate report-path)
        parsed (json/read-json (:out result) :key-fn keyword)]
    (is (= 1 (:exit result)))
    (is (= "failed" (:status parsed)))
    (is (some #(str/includes? % "did not reduce tokens")
              (:failures parsed)))))
