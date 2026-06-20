(ns agraph.benchmark-agent-run
  (:require [agraph.benchmark-agent-score :as benchmark-agent-score]
            [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.extract :as extract]
            [agraph.fs :as fs]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def default-agent-run-timeout-ms
  600000)

(def supported-agent-prompt-profiles
  ["standard" "fast"])

(def ^:private suspected-files-scope-rule
  "Only include files likely to require edits in suspectedFiles; cite comparison, example, generated, or read-only support files as evidence instead.")

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn- parser-worker-option
  [opts]
  (extract/normalize-parser-worker-mode (:parser-worker opts)))

(defn- agent-result-json-schema
  []
  (benchmark-agent-score/agent-result-json-schema))

(defn agent-run-command
  [opts]
  (or (some-> (:command opts) not-empty)
      (throw (ex-info "Missing agent command." {:option "--command"}))))
(defn agent-run-timeout-ms
  [opts]
  (long (or (:timeout-ms opts) default-agent-run-timeout-ms)))
(defn agent-prompt-profile
  [opts]
  (let [profile (name (keyword (or (:prompt-profile opts) :standard)))]
    (when-not ((set supported-agent-prompt-profiles) profile)
      (throw (ex-info "Unknown benchmark agent prompt profile."
                      {:prompt-profile profile
                       :supported supported-agent-prompt-profiles})))
    profile))
(defn ensure-agent-run-id!
  [opts]
  (when (blankish? (:agent-id opts))
    (throw (ex-info "Missing benchmark agent id." {:option "--agent"}))))
(defn- process-output-future
  [stream]
  (future (slurp stream)))
(defn- invoke-no-arg-method
  [target method-name]
  (clojure.lang.Reflector/invokeInstanceMethod target method-name (object-array 0)))
(defn- process-descendants
  [^Process process]
  (try
    (let [handle (invoke-no-arg-method process "toHandle")
          descendants-stream (invoke-no-arg-method handle "descendants")
          iterator (invoke-no-arg-method descendants-stream "iterator")]
      (->> (iterator-seq iterator)
           reverse
           vec))
    (catch Exception _
      [])))
(defn- destroy-handle-forcibly!
  [handle]
  (try
    (invoke-no-arg-method handle "destroyForcibly")
    (catch Exception _
      nil)))
(defn- await-handle-exit!
  [handle]
  (try
    (invoke-no-arg-method handle "onExit")
    (catch Exception _
      nil)))
(defn- destroy-process-tree!
  [^Process process]
  (let [descendants (process-descendants process)]
    (doseq [descendant descendants]
      (destroy-handle-forcibly! descendant))
    (.destroyForcibly process)
    (doseq [descendant descendants]
      (await-handle-exit! descendant))))
(defn- wait-for-process
  [process timeout-ms]
  (let [finished? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      {:exit (.exitValue process)
       :timedOut false}
      (do
        (destroy-process-tree! process)
        (.waitFor process 1000 TimeUnit/MILLISECONDS)
        {:exit -1
         :timedOut true}))))
(defn run-process!
  [command cwd env timeout-ms]
  (let [started-at (System/currentTimeMillis)
        process-builder (ProcessBuilder. ["sh" "-lc" command])
        process-env (.environment process-builder)]
    (.directory process-builder (io/file cwd))
    (doseq [[k v] env]
      (.put process-env k (str v)))
    (let [process (.start process-builder)
          out-stream (.getInputStream process)
          err-stream (.getErrorStream process)
          out-future (process-output-future out-stream)
          err-future (process-output-future err-stream)
          result (wait-for-process process timeout-ms)]
      (when (:timedOut result)
        (doseq [stream [out-stream err-stream]]
          (try
            (.close stream)
            (catch Exception _ nil))))
      (assoc result
             :durationMs (- (System/currentTimeMillis) started-at)
             :stdout (deref out-future
                            1000
                            "<stdout unavailable after process timeout>\n")
             :stderr (deref err-future
                            1000
                            "<stderr unavailable after process timeout>\n")))))
(defn write-agent-run-logs!
  [suite case opts process-result]
  (let [stdout-path (benchmark-paths/agent-run-log-path suite case opts "stdout")
        stderr-path (benchmark-paths/agent-run-log-path suite case opts "stderr")]
    (benchmark-io/write-text-file! stdout-path (:stdout process-result))
    (benchmark-io/write-text-file! stderr-path (:stderr process-result))
    {:stdoutPath (fs/canonical-path stdout-path)
     :stderrPath (fs/canonical-path stderr-path)}))
(defn- json-example
  [value]
  (json/write-json-str value {:indent-str "  "}))
(defn agent-result-contract
  []
  {:schema agent-result-schema
   :caseId "case id from the packet"
   :caseFingerprint "case fingerprint from the packet"
   :agentId "stable id for the agent run"
   :mode "agraph, shell-only, or local-vector"
   :selection {:rawCandidateFiles 0
               :candidateFiles 0
               :coverageFilteredCandidateFiles 0
               :limit 20
               :coverageSourceKinds []}
   :parserWorker {:mode "none|java|dotnet|all"
                  :source "option|env|default|agent-result|unknown"}
   :suspectedFiles [{:path "repo-relative/path.ext"
                     :rank 1
                     :confidence 0.0
                     :reason "short evidence-based reason"
                     :evidence []}]
   :suspectedSymbols []
   :commands []
   :warnings []
   :summary "brief rationale"})
(defn write-agent-output-schema!
  [suite case opts]
  (let [schema-path (benchmark-paths/agent-run-output-schema-path suite case opts)]
    (benchmark-io/write-json-file! schema-path (agent-result-json-schema))
    (fs/canonical-path schema-path)))
(defn- agent-prompt-profile-lines
  [profile]
  (case profile
    "fast" ["## Fast Localization Profile"
            "- Localization only. Do not patch files."
            "- Do not run full test/build suites."
            "- Use at most 8 local shell commands."
            "- Inspect at most 12 files or snippets."
            "- Prefer `rg`, focused `sed`, and packet-provided AGraph ask/explore commands."
            "- Return the best 1-5 suspected files as soon as evidence is sufficient."
            (str "- " suspected-files-scope-rule)
            "- If structured output is active, make the final response the result JSON."
            "- Otherwise write JSON to `AGRAPH_BENCH_RESULT`; do not include prose outside JSON."
            ""]
    []))
(defn agent-run-prompt
  [packet result-path output-schema-path opts]
  (let [profile (agent-prompt-profile opts)]
    (str/join
     "\n"
     (concat
      ["# AGraph Issue Localization Benchmark"
       ""
       "You are evaluating a coding-agent workflow against one real issue."
       "Use only the base checkout, the issue text in the packet, and AGraph output generated from that base checkout."
       "Do not inspect the fixing diff, PR body, post-fix commits, or benchmark ground-truth artifacts."
       ""
       "## Files"
       (str "- Packet JSON: " (get-in packet [:artifacts :packetPath]))
       (str "- Result JSON to write: " (fs/canonical-path result-path))
       (str "- Output JSON Schema: " output-schema-path)
       (str "- Worktree: " (:worktreeRoot packet))
       (str "- Project config: " (get-in packet [:artifacts :projectConfig]))
       (str "- XTDB path: " (get-in packet [:artifacts :xtdbPath]))
       (when-let [hints-path (get-in packet [:artifacts :agraphHintsPath])]
         (str "- AGraph hints JSON: " hints-path))
       (when-let [context-path (get-in packet [:artifacts :agraphContextPath])]
         (str "- AGraph context JSON: " context-path))
       ""
       "## Environment"
       "- `AGRAPH_BENCH_PACKET` points to the packet JSON."
       "- `AGRAPH_BENCH_AGRAPH_HINTS` points to compact AGraph hints when available."
       "- `AGRAPH_BENCH_AGRAPH_CONTEXT` points to precomputed AGraph context when available."
       "- `AGRAPH_BENCH_OUTPUT_SCHEMA` points to the JSON Schema for the result."
       "- `AGRAPH_BENCH_PROMPT_PROFILE` identifies the prompt profile for this run."
       "- `AGRAPH_BENCH_RESULT` is the only result file scored by the benchmark."
       "- `AGRAPH_BENCH_WORKTREE` is the base checkout."
       "- `AGRAPH_BENCH_PROJECT` is the generated AGraph project config."
       "- `AGRAPH_BENCH_XTDB_PATH` and `AGRAPH_XTDB_PATH` point to the graph store."
       ""]
      (agent-prompt-profile-lines profile)
      ["## Task"
       (get-in packet [:task :objective])
       ""
       "Read the packet, inspect the checkout, and write the ranked localization result JSON."
       "Return files before proposing or applying a patch."
       suspected-files-scope-rule
       ""
       "## Result Contract"
       (str "Write JSON with schema `" agent-result-schema "` to `AGRAPH_BENCH_RESULT`.")
       "When your agent runner supports structured output, use `AGRAPH_BENCH_OUTPUT_SCHEMA`."
       "For structured-output runners that capture the final response, return only the JSON result as the final response and do not also shell-write the result file."
       "For plain shell runners, write the JSON result directly to `AGRAPH_BENCH_RESULT`."
       "Use repo-relative file paths from the base checkout."
       ""
       "```json"
       (json-example (agent-result-contract))
       "```"
       ""
       "## AGraph Mode"
       (if (= "agraph" (:mode packet))
         (str "AGraph is available and has already been prepared for this run. "
              "Read `AGRAPH_BENCH_AGRAPH_HINTS` first when it is set; use "
              "`AGRAPH_BENCH_AGRAPH_CONTEXT` for supporting snippets. In the "
              "hints, prefer `topFiles`, `architecture`, and `auditScopes` "
              "before broad shell search; treat `answerability`, "
              "`sourceCoverage`, and `diagnostics` as trust boundaries. Use "
              "`commands` as bounded follow-up checks, especially commands "
              "copied from `architecture.validationGaps.nextActions` when a "
              "plane is missing or weak. Use live ask/explore commands only if "
              "the context artifact is missing or insufficient; run setup only "
              "if graph commands report missing data.")
         "AGraph is not part of this run. Use ordinary local shell inspection only.")
       ""
       "## Run Metadata"
       (str "- Suite: " (:suite-id packet))
       (str "- Case: " (:case-id packet))
       (str "- Repo: " (:repo-id packet))
       (str "- Project: " (:project-id packet))
       (str "- Agent: " (:agent-id opts))
       (str "- Mode: " (:mode packet))
       (str "- Prompt profile: " profile)
       ""]))))
(defn write-agent-run-prompt!
  [suite case packet result-path output-schema-path opts]
  (let [prompt-path (benchmark-paths/agent-run-prompt-path suite case opts)]
    (benchmark-io/write-text-file! prompt-path
                                   (agent-run-prompt packet
                                                     result-path
                                                     output-schema-path
                                                     opts))
    (fs/canonical-path prompt-path)))
(defn agent-run-env
  [packet result-path prompt-path output-schema-path opts]
  (cond-> {"AGRAPH_BENCH_SUITE_ID" (:suite-id packet)
           "AGRAPH_BENCH_CASE_ID" (:case-id packet)
           "AGRAPH_BENCH_CASE_FINGERPRINT" (:caseFingerprint packet)
           "AGRAPH_BENCH_REPO_ID" (:repo-id packet)
           "AGRAPH_BENCH_PROJECT_ID" (:project-id packet)
           "AGRAPH_BENCH_MODE" (:mode packet)
           "AGRAPH_BENCH_AGENT_ID" (:agent-id opts)
           "AGRAPH_BENCH_PACKET" (get-in packet [:artifacts :packetPath])
           "AGRAPH_BENCH_PROMPT" prompt-path
           "AGRAPH_BENCH_PROMPT_PROFILE" (agent-prompt-profile opts)
           "AGRAPH_BENCH_OUTPUT_SCHEMA" output-schema-path
           "AGRAPH_BENCH_RESULT" (fs/canonical-path result-path)
           "AGRAPH_BENCH_WORKTREE" (:worktreeRoot packet)
           "AGRAPH_BENCH_PROJECT" (get-in packet [:artifacts :projectConfig])
           "AGRAPH_BENCH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])
           "AGRAPH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])}
    (parser-worker-option opts)
    (assoc "AGRAPH_BENCH_PARSER_WORKER" (parser-worker-option opts)
           "AGRAPH_PARSER_WORKER" (parser-worker-option opts))
    (get-in packet [:artifacts :agraphContextPath])
    (assoc "AGRAPH_BENCH_AGRAPH_CONTEXT"
           (get-in packet [:artifacts :agraphContextPath]))
    (get-in packet [:artifacts :agraphHintsPath])
    (assoc "AGRAPH_BENCH_AGRAPH_HINTS"
           (get-in packet [:artifacts :agraphHintsPath]))))
