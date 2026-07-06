(ns ygg.benchmark-gate-script-test
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-test-support :refer [spit-file! spit-json! temp-dir]]))

(defn- run-gate
  [& args]
  (apply shell/sh "bash" "scripts/benchmark-gate.sh" args))

(defn- run-claim-quick-gate
  [& args]
  (apply shell/sh "bash" "scripts/claim-quick-gate.sh" args))
(defn- run-docs-claim-gate
  [& args]
  (apply shell/sh "bash" "scripts/docs-claim-gate.sh" args))

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

(defn- run-stage-gate
  [& args]
  (apply shell/sh "python3" "scripts/stage-time-gate.py" args))

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

(defn- stage-report!
  [root path {:keys [stage total-ms case-ms stage-class agent-preparation]
              :or {stage "index-project"}}]
  (spit-json! root
              path
              (cond-> {:schema "ygg.benchmark.agent-report/v2"
                       :suite-id "fixture"
                       :timings {:cases 1
                                 :stageElapsedMs [{:stage stage
                                                   :elapsedMs total-ms}]
                                 :stageTiming
                                 {:classes (cond-> []
                                             stage-class
                                             (conj {:stage stage
                                                    :class stage-class
                                                    :elapsedMs total-ms}))}}
                       :caseProgress [{:case-id "case-1"
                                       :repo-id "fixture-repo"
                                       :stageElapsedMs [{:stage stage
                                                         :elapsedMs case-ms}]}]}
                agent-preparation
                (assoc :agentPreparation agent-preparation))))

(deftest dry-run-prints-full-strict-gate
  (let [result (run-gate "--dry-run"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench repos check --manifest benchmarks/custom-repos.edn --suite benchmarks/custom.edn"))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/custom.edn"))
    (is (str/includes? (nth lines 1)
                       "--retriever auto"))
    (is (str/includes? (nth lines 1)
                       "--reuse-context"))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/custom.edn"))
    (is (str/includes? (nth lines 2)
                       "--agent ygg-baseline-auto"))
    (is (str/includes? (nth lines 2)
                       "--max-benchmark-preflight-blockers 0"))
    (is (str/includes? (nth lines 2)
                       "--max-case-total-tokens 24000"))
    (is (str/includes? (nth lines 2)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (nth lines 2)
                       "--min-case-expected-evidence-citation-rate 0.50"))
    (is (str/includes? (nth lines 3)
                       "python3 scripts/stage-time-gate.py"))
    (is (str/includes? (nth lines 3)
                       "--out .dev/ygg/benchmark-gate/custom/stage-time-gate.json"))))

(deftest check-only-dry-run-skips-baseline-and-keeps-strict-checks
  (let [result (run-gate "--dry-run"
                         "--check-only"
                         "--case" "case-1"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 3 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench repos check --manifest benchmarks/custom-repos.edn --suite benchmarks/custom.edn"))
    (is (not-any? #(str/includes? % "agent-baseline") lines))
    (is (str/includes? (nth lines 1)
                       "bench agent-check benchmarks/custom.edn --case case-1"))
    (is (str/includes? (nth lines 1)
                       "--agent ygg-baseline-auto"))
    (is (str/includes? (nth lines 1) "--min-cases 1"))
    (is (str/includes? (nth lines 1) "--min-runs 1"))
    (is (str/includes? (nth lines 1) "--max-unverified-score-runs 0"))
    (is (str/includes? (nth lines 1)
                       "--max-benchmark-preflight-blockers 0"))
    (is (str/includes? (nth lines 1)
                       "--max-case-total-tokens 24000"))
    (is (str/includes? (nth lines 1)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (nth lines 1)
                       "--min-case-expected-evidence-citation-rate 0.50"))
    (is (str/includes? (nth lines 2)
                       "python3 scripts/stage-time-gate.py"))))

(deftest reuse-context-and-skip-existing-dry-run-forward-only-to-baseline-generation
  (let [result (run-gate "--dry-run"
                         "--reuse-context"
                         "--skip-existing"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/custom.edn"))
    (is (str/includes? (nth lines 1)
                       "--reuse-context"))
    (is (str/includes? (nth lines 1)
                       "--skip-existing"))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/custom.edn"))
    (is (not (str/includes? (nth lines 2)
                            "--reuse-context")))
    (is (not (str/includes? (nth lines 2)
                            "--skip-existing")))))

(deftest fresh-context-dry-run-disables-default-context-reuse
  (let [result (run-gate "--dry-run"
                         "--fresh-context"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/custom.edn"))
    (is (not (str/includes? (nth lines 1)
                            "--reuse-context")))))

(deftest help-lists-check-only
  (let [result (run-gate "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result) "--check-only"))
    (is (str/includes? (:out result) "--reuse-context"))
    (is (str/includes? (:out result) "--fresh-context"))
    (is (str/includes? (:out result) "--skip-existing"))
    (is (str/includes? (:out result) "--stage-time-baseline-report"))
    (is (str/includes? (:out result)
                       "--min-expected-evidence-citation-rate N"))
    (is (str/includes? (:out result)
                       "Default: 0.80"))
    (is (str/includes? (:out result)
                       "--min-case-expected-evidence-citation-rate N"))
    (is (str/includes? (:out result)
                       "Default: 0.50"))
    (is (str/includes? (:out result) "--retriever MODE"))
    (is (str/includes? (:out result) "current artifacts already"))))

(deftest claim-quick-dry-run-uses-claim-suite-and-expected-evidence-gates
  (let [result (run-claim-quick-gate "--dry-run")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench repos check --manifest benchmarks/repos.edn --suite benchmarks/historical-replay-claim-quick.edn"))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/historical-replay-claim-quick.edn"))
    (is (str/includes? (nth lines 1)
                       "--out .dev/ygg/claim-quick-gate"))
    (is (str/includes? (nth lines 1)
                       "--reuse-context"))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/historical-replay-claim-quick.edn"))
    (is (str/includes? (nth lines 2)
                       "--min-mrr 0.30"))
    (is (str/includes? (nth lines 2)
                       "--max-noise-at-20 0.80"))
    (is (str/includes? (nth lines 2)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (nth lines 2)
                       "--min-case-expected-evidence-citation-rate 0.50"))
    (is (str/includes? (nth lines 3)
                       "python3 scripts/stage-time-gate.py"))))

(deftest claim-quick-dry-run-allows-threshold-overrides
  (let [result (run-claim-quick-gate "--dry-run"
                                     "--min-expected-evidence-citation-rate"
                                     "0.5"
                                     "--min-case-expected-evidence-citation-rate"
                                     "0.25"
                                     "--max-noise-at-20"
                                     "0.95")
        check-line (first (filter #(str/includes? % "bench agent-check")
                                  (output-lines result)))]
    (is (= 0 (:exit result)))
    (is (str/includes? check-line
                       "--min-expected-evidence-citation-rate 0.5"))
    (is (str/includes? check-line
                       "--min-case-expected-evidence-citation-rate 0.25"))
    (is (str/includes? check-line
                       "--max-noise-at-20 0.95"))
    (is (not (str/includes? check-line
                            "--min-expected-evidence-citation-rate 1.0")))
    (is (not (str/includes? check-line
                            "--min-case-expected-evidence-citation-rate 1.0")))
    (is (not (str/includes? check-line
                            "--max-noise-at-20 0.80")))))

(deftest claim-quick-help-documents-defaults
  (let [result (run-claim-quick-gate "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result)
                       "benchmarks/historical-replay-claim-quick.edn"))
    (is (str/includes? (:out result)
                       "--min-mrr 0.30"))
    (is (str/includes? (:out result)
                       "--max-noise-at-20 0.80"))
    (is (str/includes? (:out result)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (:out result)
                       "--min-case-expected-evidence-citation-rate 0.50"))))
(deftest docs-claim-dry-run-uses-docs-suite-and-expected-evidence-gates
  (let [result (run-docs-claim-gate "--dry-run")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 0)
                       "bb bench repos check --manifest benchmarks/repos.edn --suite benchmarks/historical-docs-claim-quick.edn"))
    (is (str/includes? (nth lines 1)
                       "bench agent-baseline benchmarks/historical-docs-claim-quick.edn"))
    (is (str/includes? (nth lines 1)
                       "--out .dev/ygg/docs-claim-gate"))
    (is (str/includes? (nth lines 1)
                       "--reuse-context"))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/historical-docs-claim-quick.edn"))
    (is (str/includes? (nth lines 2)
                       "--min-mrr 0.30"))
    (is (str/includes? (nth lines 2)
                       "--max-noise-at-20 0.90"))
    (is (str/includes? (nth lines 2)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (nth lines 2)
                       "--min-case-expected-evidence-citation-rate 0.50"))
    (is (str/includes? (nth lines 3)
                       "python3 scripts/stage-time-gate.py"))))

(deftest docs-claim-help-documents-defaults
  (let [result (run-docs-claim-gate "--help")]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result)
                       "benchmarks/historical-docs-claim-quick.edn"))
    (is (str/includes? (:out result)
                       "--min-mrr 0.30"))
    (is (str/includes? (:out result)
                       "--max-noise-at-20 0.90"))
    (is (str/includes? (:out result)
                       "--min-expected-evidence-citation-rate 0.80"))
    (is (str/includes? (:out result)
                       "--min-case-expected-evidence-citation-rate 0.50"))))

(deftest dry-run-runs-stage-time-gate-when-threshold-is-set
  (let [result (run-gate "--dry-run"
                         "--max-total-stage-ms" "240000"
                         "--stage" "context-packet"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)
        stage-line (last lines)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/custom.edn"))
    (is (str/includes? stage-line "python3 scripts/stage-time-gate.py"))
    (is (str/includes? stage-line ".dev/ygg/benchmark-gate/custom/\\*/agent-report.json"))
    (is (str/includes? stage-line "--out .dev/ygg/benchmark-gate/custom/stage-time-gate.json"))
    (is (str/includes? stage-line "--max-total-stage-ms 240000"))
    (is (str/includes? stage-line "--stage context-packet"))))

(deftest dry-run-runs-stage-time-regression-gate-with-baseline
  (let [result (run-gate "--dry-run"
                         "--stage-time-baseline-report" "before.json"
                         "--max-total-stage-regression-ratio" "1.2"
                         "--max-case-stage-regression-ms" "100"
                         "--min-stage-regression-ms" "50"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)
        stage-line (last lines)]
    (is (= 0 (:exit result)))
    (is (= 4 (count lines)))
    (is (str/includes? (nth lines 2)
                       "bench agent-check benchmarks/custom.edn"))
    (is (str/includes? stage-line "python3 scripts/stage-time-gate.py"))
    (is (str/includes? stage-line "--baseline-report before.json"))
    (is (str/includes? stage-line "--require-strict-warm"))
    (is (str/includes? stage-line "--max-total-stage-regression-ms 120000"))
    (is (str/includes? stage-line "--max-total-stage-regression-ratio 1.2"))
    (is (str/includes? stage-line "--max-case-stage-regression-ms 100"))
    (is (str/includes? stage-line "--max-case-stage-regression-ratio 1.50"))
    (is (str/includes? stage-line "--min-stage-regression-ms 50"))))

(deftest dry-run-stage-time-baseline-uses-repeat-run-default-thresholds
  (let [result (run-gate "--dry-run"
                         "--stage-time-baseline-report" "before.json"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        stage-line (last (output-lines result))]
    (is (= 0 (:exit result)))
    (is (str/includes? stage-line "--require-strict-warm"))
    (is (str/includes? stage-line "--max-case-stage-regression-ms 30000"))
    (is (str/includes? stage-line "--max-total-stage-regression-ms 120000"))
    (is (str/includes? stage-line "--max-case-stage-regression-ratio 1.50"))
    (is (str/includes? stage-line "--max-total-stage-regression-ratio 1.50"))
    (is (str/includes? stage-line "--min-stage-regression-ms 5000"))))

(deftest dry-run-passes-retriever-and-semantic-client-options
  (let [result (run-gate "--dry-run"
                         "--retriever" "semantic"
                         "--provider" "local"
                         "--model" "sentence-transformers/all-MiniLM-L6-v2"
                         "--batch-size" "128"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (str/includes? (nth lines 1)
                       "--retriever semantic"))
    (is (str/includes? (nth lines 1)
                       "--provider local"))
    (is (str/includes? (nth lines 1)
                       "--model sentence-transformers/all-MiniLM-L6-v2"))
    (is (str/includes? (nth lines 1)
                       "--batch-size 128"))
    (is (str/includes? (nth lines 2)
                       "--agent ygg-baseline-semantic"))))

(deftest dry-run-passes-retrieval-ablation-options
  (let [result (run-gate "--dry-run"
                         "--retriever" "auto"
                         "--fusion-strategy" "rrf"
                         "--sqlite-fts"
                         "--diversity-rerank-limit" "5"
                         "--fts-candidate-limit" "80"
                         "--fts-weight" "0.1"
                         "--suite" "benchmarks/custom.edn"
                         "--manifest" "benchmarks/custom-repos.edn"
                         "--out" ".dev/ygg/benchmark-gate/custom")
        lines (output-lines result)]
    (is (= 0 (:exit result)))
    (is (str/includes? (nth lines 1)
                       "--fusion-strategy rrf"))
    (is (str/includes? (nth lines 1)
                       "--sqlite-fts"))
    (is (str/includes? (nth lines 1)
                       "--diversity-rerank-limit 5"))
    (is (str/includes? (nth lines 1)
                       "--fts-candidate-limit 80"))
    (is (str/includes? (nth lines 1)
                       "--fts-weight 0.1"))
    (is (str/includes? (nth lines 2)
                       "--agent ygg-baseline-auto-fusion-rrf-sqlite-fts-diversity-5-fts-80-ftsw-0.1"))))

(deftest benchmark-baseline-id-includes-retrieval-ablation-options
  (is (= "ygg-baseline-auto-fusion-rrf-sqlite-fts-diversity-5-fts-80-ftsw-0.1"
         (benchmark-paths/agent-baseline-id {:retriever :auto
                                             :fusion-strategy :rrf
                                             :sqlite-fts? true
                                             :diversity-rerank-limit 5
                                             :fts-candidate-limit 80
                                             :fts-weight 0.1}))))

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

(deftest stage-time-gate-compares-current-report-to-baseline-artifact
  (let [root (temp-dir "ygg-stage-time-gate-compare")
        baseline-path (stage-report! root
                                     "baseline.json"
                                     {:total-ms 1000
                                      :case-ms 500})
        current-path (stage-report! root
                                    "current.json"
                                    {:total-ms 1300
                                     :case-ms 700})
        result (run-stage-gate current-path
                               "--baseline-report" baseline-path
                               "--max-total-stage-regression-ratio" "1.2"
                               "--max-case-stage-regression-ms" "100"
                               "--min-stage-regression-ms" "50")
        parsed (json/read-json (:out result) :key-fn keyword)
        failure-metrics (set (map :metric (:failures parsed)))]
    (is (= 1 (:exit result)))
    (is (= "failed" (:status parsed)))
    (is (= 1 (count (:baselineReports parsed))))
    (is (= #{"totalStageRegressionRatio"
             "caseStageRegressionMs"}
           failure-metrics))
    (is (= {:stage "index-project"
            :baselineElapsedMs 1000
            :currentElapsedMs 1300
            :deltaMs 300
            :regression true}
           (select-keys (first (:stageDeltas parsed))
                        [:stage
                         :baselineElapsedMs
                         :currentElapsedMs
                         :deltaMs
                         :regression])))))

(deftest stage-time-gate-ignores-comparison-noise-below-floor
  (let [root (temp-dir "ygg-stage-time-gate-noise-floor")
        baseline-path (stage-report! root
                                     "baseline.json"
                                     {:total-ms 1000
                                      :case-ms 500})
        current-path (stage-report! root
                                    "current.json"
                                    {:total-ms 1040
                                     :case-ms 550})
        result (run-stage-gate current-path
                               "--baseline-report" baseline-path
                               "--max-total-stage-regression-ratio" "1.01"
                               "--max-case-stage-regression-ms" "10"
                               "--min-stage-regression-ms" "50")
        parsed (json/read-json (:out result) :key-fn keyword)]
    (is (= 0 (:exit result)) (:out result))
    (is (= "passed" (:status parsed)))
    (is (= 40 (:deltaMs (first (:stageDeltas parsed)))))
    (is (= [] (:failures parsed)))))

(deftest stage-time-gate-requires-strict-warm-reports
  (let [root (temp-dir "ygg-stage-time-gate-strict-warm")
        cold-path (stage-report! root
                                 "cold.json"
                                 {:total-ms 1000
                                  :case-ms 500
                                  :agent-preparation
                                  {:runs 1
                                   :allRunsReadyBeforeAgent false
                                   :strictWarmBenchmark false
                                   :statusCounts {"prepared" 1}
                                   :caseIds ["case-1"]}})
        warm-path (stage-report! root
                                 "warm.json"
                                 {:total-ms 900
                                  :case-ms 450
                                  :agent-preparation
                                  {:runs 1
                                   :allRunsReadyBeforeAgent true
                                   :strictWarmBenchmark true
                                   :statusCounts {"reused" 1}
                                   :caseIds ["case-1"]}})
        cold-result (run-stage-gate cold-path "--require-strict-warm")
        warm-result (run-stage-gate warm-path "--require-strict-warm")
        cold (json/read-json (:out cold-result) :key-fn keyword)
        warm (json/read-json (:out warm-result) :key-fn keyword)]
    (is (= 1 (:exit cold-result)))
    (is (= "failed" (:status cold)))
    (is (= "strictWarmBenchmark" (get-in cold [:failures 0 :metric])))
    (is (= false (get-in cold [:failures 0 :actual])))
    (is (= 0 (:exit warm-result)) (:out warm-result))
    (is (= "passed" (:status warm)))
    (is (true? (get-in warm [:thresholds :requireStrictWarm])))))

(deftest stage-time-gate-aggregates-stage-class-profile
  (let [root (temp-dir "ygg-stage-time-gate-stage-class-profile")
        report-path (spit-json!
                     root
                     "current.json"
                     {:schema "ygg.benchmark.agent-report/v2"
                      :suite-id "fixture"
                      :timings {:cases 1
                                :stageElapsedMs [{:stage "index-project"
                                                  :elapsedMs 1000}
                                                 {:stage "embed-search-docs"
                                                  :elapsedMs 700}
                                                 {:stage "score-agent-result"
                                                  :elapsedMs 200}
                                                 {:stage "write-agent-artifacts"
                                                  :elapsedMs 150}]
                                :stageTiming
                                {:classes [{:stage "index-project"
                                            :class "graph-setup"
                                            :elapsedMs 1000}
                                           {:stage "embed-search-docs"
                                            :class "embedding"
                                            :elapsedMs 700}
                                           {:stage "score-agent-result"
                                            :class "scoring"
                                            :elapsedMs 200}
                                           {:stage "write-agent-artifacts"
                                            :class "agent-preparation"
                                            :elapsedMs 150}]}}
                      :caseProgress [{:case-id "case-1"
                                      :repo-id "fixture-repo"
                                      :stageElapsedMs [{:stage "index-project"
                                                        :elapsedMs 500}
                                                       {:stage "embed-search-docs"
                                                        :elapsedMs 350}
                                                       {:stage "score-agent-result"
                                                        :elapsedMs 100}
                                                       {:stage "write-agent-artifacts"
                                                        :elapsedMs 75}]}]})
        result (run-stage-gate report-path)
        parsed (json/read-json (:out result) :key-fn keyword)
        totals-by-class (into {}
                              (map (juxt :stageClass :elapsedMs))
                              (:stageClassTotals parsed))
        case-totals-by-class (into {}
                                   (map (juxt :stageClass :elapsedMs))
                                   (:slowestCaseStageClasses parsed))]
    (is (= 0 (:exit result)) (:out result))
    (is (= "passed" (:status parsed)))
    (is (= {"graph-setup" 1000
            "embedding" 700
            "scoring" 200
            "agent-preparation" 150}
           totals-by-class))
    (is (= {"graph-setup" 500
            "embedding" 350
            "scoring" 100
            "agent-preparation" 75}
           case-totals-by-class))))

(deftest stage-time-gate-falls-back-to-mechanical-stage-classes
  (let [root (temp-dir "ygg-stage-time-gate-stage-class-fallback")
        report-path (stage-report! root
                                   "current.json"
                                   {:stage "embed-search-docs"
                                    :total-ms 700
                                    :case-ms 350})
        result (run-stage-gate report-path)
        parsed (json/read-json (:out result) :key-fn keyword)]
    (is (= 0 (:exit result)) (:out result))
    (is (= "embedding" (:stageClass (first (:stageClassTotals parsed)))))
    (is (= "embedding" (:stageClass (first (:slowestCaseStageClasses parsed)))))))
