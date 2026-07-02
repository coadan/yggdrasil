(ns ygg.cli-options
  "Shared command line option parsing helpers."
  (:require [ygg.graph :as graph]
            [clojure.string :as str]))

(defn parse-limit
  [args]
  (let [idx (.indexOf args "--limit")]
    (when-not (neg? idx)
      (Long/parseLong (nth args (inc idx))))))

(defn option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn option-values
  [args flag]
  (->> (partition 2 1 args)
       (keep (fn [[arg value]]
               (when (= flag arg)
                 value)))
       vec))

(defn parse-case-ids
  [args]
  (some->> (option-value args "--cases")
           (#(str/split % #","))
           (map str/trim)
           (remove str/blank?)
           vec
           not-empty))

(defn parse-long-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Long/parseLong value)
    default))

(defn parse-optional-long
  [args flag]
  (some-> (option-value args flag) Long/parseLong))

(defn parse-depth
  [args]
  (parse-long-option args "--depth" graph/default-depth))

(defn parse-double-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Double/parseDouble value)
    default))

(defn parse-optional-double
  [args flag]
  (some-> (option-value args flag) Double/parseDouble))

(def value-options
  #{"--limit" "--retriever" "--model" "--batch-size" "--provider" "--depth" "--out"
    "--project" "--repo" "--config" "--min-confidence" "--reason" "--budget"
    "--entity-limit" "--edge-limit" "--doc-limit" "--snippet-chars" "--role"
    "--heading" "--start-line" "--end-line" "--valid-at" "--known-at"
    "--snapshot-token" "--type" "--source" "--confidence" "--view" "--query"
    "--relation" "--base-url" "--detail" "--status" "--agent"
    "--lease-minutes" "--result" "--kind" "--priority" "--format" "--platform"
    "--debounce-ms" "--name" "--workbench" "--task" "--case" "--mode" "--tools"
    "--files" "--since" "--harness" "--maintenance" "--maintenance-model"
    "--maintenance-reasoning" "--maintenance-command"
    "--id" "--plugin" "--file-kind" "--path-glob" "--scan-glob" "--fixture"
    "--text" "--target" "--tag" "--owner" "--visibility" "--scope" "--summary"
    "--ecosystem" "--package" "--prompt-profile" "--report-out" "--command"
    "--python" "--venv"
    "--vector-command" "--vector-model" "--codebase-memory-command"
    "--codebase-memory-bin" "--codebase-memory-cache-dir"
    "--graphify-command" "--graphify-bin" "--graphify-output-dir"
    "--graphify-query-budget" "--graphify-max-workers" "--parser-worker"
    "--ref" "--subdir" "--cache-dir" "--shell-report" "--ygg-report"
    "--timeout-ms" "--index-timeout-ms" "--extract-parallelism" "--min-cases" "--min-runs"
    "--min-shared-cases"
    "--retrieval-limit" "--embedding-provider-limit"
    "--output" "--anchor" "--symbol" "--literal" "--lanes"
    "--fusion-strategy" "--diversity-rerank-limit" "--fts-candidate-limit"
    "--fts-weight"
    "--min-file-recall-at-5" "--min-file-recall-at-10"
    "--min-file-recall-at-20" "--min-mrr" "--max-noise-at-20"
    "--min-evidence-citation-rate"
    "--min-path-evidence-citation-rate"
    "--min-expected-evidence-citation-rate"
    "--min-decision-f1"
    "--min-decision-evidence-citation-rate"
    "--max-total-tokens" "--max-input-tokens" "--max-output-tokens"
    "--max-cost-usd"
    "--min-case-file-recall-at-5" "--min-case-file-recall-at-10"
    "--min-case-file-recall-at-20" "--min-case-mrr"
    "--min-case-evidence-citation-rate"
    "--min-case-path-evidence-citation-rate"
    "--min-case-expected-evidence-citation-rate"
    "--min-case-decision-f1"
    "--max-case-total-tokens" "--max-case-input-tokens"
    "--max-case-output-tokens" "--max-case-cost-usd"
    "--max-case-noise-at-20"
    "--max-input-hinted-cases" "--max-unsupported-ground-truth-files"
    "--max-empty-result-runs" "--max-missing-predicted-file-runs"
    "--max-missing-decision-runs"
    "--max-commandless-runs" "--max-warning-runs"
    "--max-hint-diagnostic-runs"
    "--max-identity-mismatch-runs"
    "--max-unverified-score-runs"
    "--max-graph-expectation-failures"
    "--max-benchmark-preflight-blockers"
    "--max-missing-declared-source-kind-runs"
    "--max-missed-runs"
    "--max-context-rank-missing-runs"
    "--max-missed-but-present-in-context-runs"
    "--max-missed-and-absent-from-context-runs"
    "--max-ranked-outside-top-5-runs"
    "--max-ranked-outside-top-10-runs"
    "--max-ranked-outside-top-20-runs"
    "--max-improvement-target-runs"
    "--max-improvement-target-kind-runs"
    "--max-active-stage-ms" "--max-parser-worker-profiles"
    "--min-measured-problem-classes" "--min-measured-architecture-classes"
    "--require-parser-worker" "--regression-tolerance" "--skip-existing"})

(def boolean-options
  #{"--dry-run" "--systems" "--json" "--index" "--infer" "--enqueue"
    "--check" "--query-index" "--force" "--hooks" "--sync" "--allow-missing"
    "--allow-duplicate-runs" "--allow-unverified-scores"
    "--skip-existing" "--with-conflicts" "--without-import-evidence"
    "--no-progress" "--extractor" "--report" "--tests" "--proof-commands"
    "--graphify-include-non-code" "--sqlite-fts"
    "--changed-only" "--reviewed" "--skill" "--mcp" "--force-agent" "--start-at-login"
    "--no-start-at-login" "--no-start-server" "--yes" "--no-input" "--non-interactive"})

(defn positional-args
  [args]
  (loop [remaining args
         out []]
    (if-let [arg (first remaining)]
      (if (contains? value-options arg)
        (recur (nnext remaining) out)
        (if (contains? boolean-options arg)
          (recur (next remaining) out)
          (recur (next remaining) (conj out arg))))
      out)))

(defn dry-run?
  [args]
  (boolean (some #{"--dry-run"} args)))

(defn system-path?
  [args]
  (some #{"--systems"} args))

(defn project-scope
  [args]
  {:project-id (option-value args "--project")
   :repo-id (option-value args "--repo")})

(defn remove-option
  [args flag]
  (loop [remaining args
         out []]
    (if-let [arg (first remaining)]
      (if (= flag arg)
        (recur (nnext remaining) out)
        (recur (next remaining) (conj out arg)))
      out)))

(defn append-option
  [args flag value]
  (cond-> args
    value (conj flag value)))

(defn json-output?
  [args]
  (boolean (some #{"--json"} args)))
