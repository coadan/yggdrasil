(ns agraph.benchmark-paths
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-output-root
  ".dev/agraph/bench")
(defn safe-id
  [value]
  (-> (str value)
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-._]+|[-._]+$)" "")
      not-empty
      (or "benchmark")))
(defn output-root
  [suite opts]
  (io/file (or (:out opts) default-output-root) (safe-id (:id suite))))
(defn case-output-dir
  [suite case opts]
  (io/file (output-root suite opts) "cases" (safe-id (:id case))))
(defn worktree-dir
  [suite case opts]
  (io/file (case-output-dir suite case opts) "worktree"))
(defn xtdb-dir
  [suite case opts]
  (.getPath (io/file (case-output-dir suite case opts) "xtdb")))
(defn prepared-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "prepared.json"))
(defn result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "result.json"))
(defn agent-project-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-project.edn"))
(defn agent-packet-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-packet.json"))
(defn agent-baseline-id
  [opts]
  (str "agraph-baseline-" (name (keyword (or (:retriever opts) :lexical)))))
(defn agent-baseline-result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".json")))
(defn agent-baseline-context-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".context.json")))
(defn local-vector-request-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".request.json")))
(defn agent-run-id
  [opts]
  (or (some-> (:agent-id opts) safe-id not-empty)
      "agent"))
(defn agent-run-result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (agent-run-id opts) ".json")))
(defn agent-run-log-path
  [suite case opts stream]
  (io/file (case-output-dir suite case opts)
           "agent-logs"
           (str (agent-run-id opts) "." stream ".txt")))
(defn agent-run-prompt-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-prompts"
           (str (agent-run-id opts) ".md")))
(defn agent-run-output-schema-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-output-schemas"
           (str (agent-run-id opts) ".schema.json")))
(defn agent-run-context-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-contexts"
           (str (agent-run-id opts) ".agraph-context.json")))
(defn agent-run-hints-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-contexts"
           (str (agent-run-id opts) ".agraph-hints.json")))
(defn agent-run-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-runs"
           (str (agent-run-id opts) ".json")))
(defn- without-json-suffix
  [path]
  (str/replace (.getName (io/file path)) #"\.json$" ""))
(defn agent-score-path
  [suite case opts result-file]
  (io/file (case-output-dir suite case opts)
           "agent-scores"
           (str (safe-id (without-json-suffix result-file)) ".score.json")))
(defn agent-score-dir
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-scores"))
(defn agent-report-path
  [suite opts]
  (io/file (output-root suite opts) "agent-report.json"))
(defn agent-check-path
  [suite opts]
  (io/file (output-root suite opts) "agent-check.json"))
(defn agent-compare-path
  [suite opts]
  (io/file (output-root suite opts) "agent-compare.json"))
(defn system-improvement-report-path
  [suite opts]
  (io/file (output-root suite opts) "system-improvement-report.json"))
(defn progress-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "progress.json"))
(defn report-path
  [suite opts]
  (io/file (output-root suite opts) "report.json"))
