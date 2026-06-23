(ns ygg.benchmark-agent-run
  (:require [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.extract :as extract]
            [ygg.fs :as fs]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def default-agent-run-timeout-ms
  600000)

(def supported-agent-prompt-profiles
  ["standard" "fast"])

(def suspected-files-scope-rules
  ["Only include files likely to require edits in suspectedFiles; cite comparison, example, generated, or clearly read-only support files as evidence instead."
   "When Yggdrasil hints expose coverageSourceKinds or coverage-filtered diagnostics, check those source-kind lanes before finalizing suspectedFiles."
   "If a runtime/config/setup file may need edits for the issue or test path, include it as a suspectedFile rather than citing it only as supporting evidence."])

(def inspection-files-scope-rules
  ["Include files the issue asks to inspect before editing, even when they may end up as context rather than direct edit targets."
   "Keep suspectedFiles limited to the best inspection targets; cite broad background, examples, generated, or clearly read-only files as evidence instead."
   "When Yggdrasil hints expose coverageSourceKinds or coverage-filtered diagnostics, check those source-kind lanes before finalizing suspectedFiles."])

(defn result-scope-rules
  [result-scope]
  (case (or (some-> result-scope name) "edit-files")
    "edit-files" suspected-files-scope-rules
    "inspection-files" inspection-files-scope-rules
    suspected-files-scope-rules))

(def evidence-citation-rules
  ["For every suspectedFiles row, include at least one evidence string containing that row's exact repo-relative path."
   "When citing expected evidence from Yggdrasil hints, include the exact evidence path and label when available; do not rely on basenames."])

(def result-integrity-rules
  ["Copy caseId, caseFingerprint, and agentInputFingerprint from the current packet or YGG_BENCH_* environment variables."
   "Use warnings only for current result-validity blockers verified in this run; do not carry over stale graph-health text from older results."])

(defn- parser-worker-option
  [opts]
  (extract/normalize-parser-worker-mode (:parser-worker opts)))

(defn- agent-result-json-schema
  []
  (benchmark-agent-score/agent-result-output-json-schema))

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
  (when (benchmark-util/blankish? (:agent-id opts))
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
          _ (.close (.getOutputStream process))
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
   :agentInputFingerprint "agent input fingerprint from the packet"
   :agentId "stable id for the agent run"
   :mode "ygg, shell-only, local-vector, or codebase-memory"
   :selection {:rawCandidateFiles 0
               :candidateFiles 0
               :coverageFilteredCandidateFiles 0
               :limit 20
               :coverageSourceKinds []}
   :parserWorker {:mode "none|java|dotnet|all"
                  :source "option|env|default|agent-result|unknown"}
   :suspectedFiles [{:path "repo-relative/path.ext"
                     :repoId "repo id for multi-repo cases"
                     :rank 1
                     :confidence 0.0
                     :reason "short evidence-based reason"
                     :evidence ["path=repo-relative/path.ext command-or-graph citation"]}]
   :suspectedSymbols []
   :commands []
   :warnings []
   :summary "brief rationale"
   :decision {:kind "architecture-choice|change-plan|maintenance-action|audit-assessment|plugin-fit"
              :choices [{:id "visible decision candidate id"
                         :status "include|exclude|defer"
                         :confidence 0.0
                         :reason "short evidence-based reason"
                         :evidence ["repo-relative/path.ext command-or-graph citation"]}]
              :risks []
              :followups []}
   :tokenUsage nil})
(defn write-agent-output-schema!
  [suite case opts]
  (let [schema-path (benchmark-paths/agent-run-output-schema-path suite case opts)]
    (benchmark-io/write-json-file! schema-path (agent-result-json-schema))
    (fs/canonical-path schema-path)))
(defn- task-rule-lines
  [task]
  (let [rules (:rules task)]
    (if (seq rules)
      rules
      (concat (result-scope-rules (:resultScope task))
              evidence-citation-rules
              result-integrity-rules))))
(defn- agent-prompt-profile-lines
  [profile result-scope]
  (case profile
    "fast" ["## Fast Localization Profile"
            "- Localization only. Do not patch files."
            "- Do not run full test/build suites."
            "- Use at most 8 local shell commands."
            "- Inspect at most 12 files or snippets."
            "- Prefer `rg`, focused `sed`, and packet-provided Yggdrasil ask/explore commands."
            "- Return the best 1-5 suspected files as soon as evidence is sufficient."
            (str "- " (str/join "\n- " (result-scope-rules result-scope)))
            (str "- " (str/join "\n- " evidence-citation-rules))
            (str "- " (str/join "\n- " result-integrity-rules))
            "- If structured output is active, make the final response the result JSON."
            "- Otherwise write JSON to `YGG_BENCH_RESULT`; do not include prose outside JSON."
            ""]
    []))
(defn- decision-prompt-lines
  [packet]
  (let [candidates (vec (get-in packet [:task :decisionCandidates]))]
    (when (seq candidates)
      ["## Decision Quality"
       "This case also scores decision quality. Add `decision` to the result JSON."
       "Use only the candidate ids listed below. Do not invent ids."
       "For each candidate you can judge, set status to include, exclude, or defer."
       "Cite exact repo-relative candidate evidence paths when available."
       ""
       "```json"
       (json-example {:kind (get-in packet [:task :decisionKind])
                      :candidates candidates})
       "```"
       ""])))
(defn- ygg-mode?
  [packet]
  (= "ygg" (:mode packet)))

(defn- compact-result-contract?
  [packet profile]
  (and (ygg-mode? packet)
       (= "fast" profile)))

(defn- ygg-artifact-lines
  [packet]
  (when (or (get-in packet [:artifacts :yggHintsPath])
            (get-in packet [:artifacts :yggContextPath]))
    ["- Yggdrasil artifacts: use `YGG_BENCH_YGG_HINTS` and `YGG_BENCH_YGG_CONTEXT`."]))

(defn- result-contract-lines
  [profile packet]
  (if (compact-result-contract? packet profile)
    ["## Result Contract"
     (str "Write JSON with schema `" agent-result-schema
          "` to `YGG_BENCH_RESULT`; use `YGG_BENCH_OUTPUT_SCHEMA` for fields.")
     "For structured-output runners, return only JSON; otherwise write the file."
     "Use repo-relative paths, and set `repoId` for multi-repo files."
     ""]
    ["## Result Contract"
     (str "Write JSON with schema `" agent-result-schema "` to `YGG_BENCH_RESULT`.")
     "When your agent runner supports structured output, use `YGG_BENCH_OUTPUT_SCHEMA`."
     "For structured-output runners that capture the final response, return only the JSON result as the final response and do not also shell-write the result file."
     "For plain shell runners, write the JSON result directly to `YGG_BENCH_RESULT`."
     "Use repo-relative file paths from the base checkout. For multi-repo cases, set `repoId` on each suspectedFiles row."
     ""
     "```json"
     (json-example (agent-result-contract))
     "```"
     ""]))

(defn- ygg-mode-lines
  [packet]
  ["## Yggdrasil Mode"
   (if (ygg-mode? packet)
     (str "Yggdrasil is prepared. Read `YGG_BENCH_YGG_HINTS` first; use "
          "`YGG_BENCH_YGG_CONTEXT` for snippets. Prefer `topFiles`, "
          "`architecture`, and `auditScopes` before broad search. Treat "
          "`sourceCoverage` and `diagnostics` as trust boundaries; run listed "
          "`commands` or `architecture.validationGaps.nextActions` only for "
          "weak or missing planes.")
     "No Yggdrasil: use local shell inspection only.")
   ""])
(defn agent-run-prompt
  [packet result-path output-schema-path opts]
  (let [profile (agent-prompt-profile opts)
        task (:task packet)
        result-scope (:resultScope task)]
    (str/join
     "\n"
     (concat
      ["# Yggdrasil Issue Localization Benchmark"
       ""
       "You are evaluating a coding-agent workflow against one real issue."
       "Use only the base checkout, the issue text in the packet, and Yggdrasil output generated from that base checkout."
       "Do not inspect the fixing diff, PR body, post-fix commits, or benchmark ground-truth artifacts."
       ""
       "## Files"
       (str "- Packet JSON: " (get-in packet [:artifacts :packetPath]))
       (str "- Result JSON to write: " (fs/canonical-path result-path))
       (str "- Output JSON Schema: " output-schema-path)
       (str "- Worktree: " (:worktreeRoot packet))
       (str "- Project config: " (get-in packet [:artifacts :projectConfig]))
       (str "- XTDB path: " (get-in packet [:artifacts :xtdbPath]))]
      (ygg-artifact-lines packet)
      [""
       "## Environment"
       "- `YGG_BENCH_PACKET` points to the packet JSON."
       "- `YGG_BENCH_YGG_HINTS` points to compact Yggdrasil hints when available."
       "- `YGG_BENCH_YGG_CONTEXT` points to precomputed Yggdrasil context when available."
       "- `YGG_BENCH_OUTPUT_SCHEMA` points to the JSON Schema for the result."
       "- `YGG_BENCH_PROMPT_PROFILE` identifies the prompt profile for this run."
       "- `YGG_BENCH_RESULT` is the only result file scored by the benchmark."
       "- `YGG_BENCH_WORKTREE` is the base checkout."
       "- `YGG_BENCH_PROJECT` is the generated Yggdrasil project config."
       "- `YGG_BENCH_XTDB_PATH` and `YGG_XTDB_PATH` point to the graph store."
       ""]
      (agent-prompt-profile-lines profile result-scope)
      ["## Task"
       (get-in packet [:task :objective])
       ""
       "Read the packet, inspect the checkout, and write the ranked localization result JSON."
       "Return files before proposing or applying a patch."
       (str/join "\n" (task-rule-lines task))
       ""]
      (decision-prompt-lines packet)
      (result-contract-lines profile packet)
      (ygg-mode-lines packet)
      ["## Run Metadata"
       (str "- Suite: " (:suite-id packet))
       (str "- Case: " (:case-id packet))
       (str "- Repo: " (:repo-id packet))
       (when (seq (:repos packet))
         (str "- Repos: "
              (str/join ", "
                        (map (fn [{:keys [id root]}]
                               (str id "=" root))
                             (:repos packet)))))
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
  (cond-> {"YGG_BENCH_SUITE_ID" (:suite-id packet)
           "YGG_BENCH_CASE_ID" (:case-id packet)
           "YGG_BENCH_CASE_FINGERPRINT" (:caseFingerprint packet)
           "YGG_BENCH_AGENT_INPUT_FINGERPRINT" (:agentInputFingerprint packet)
           "YGG_BENCH_REPO_ID" (:repo-id packet)
           "YGG_BENCH_PROJECT_ID" (:project-id packet)
           "YGG_BENCH_MODE" (:mode packet)
           "YGG_BENCH_AGENT_ID" (:agent-id opts)
           "YGG_BENCH_PACKET" (get-in packet [:artifacts :packetPath])
           "YGG_BENCH_PROMPT" prompt-path
           "YGG_BENCH_PROMPT_PROFILE" (agent-prompt-profile opts)
           "YGG_BENCH_OUTPUT_SCHEMA" output-schema-path
           "YGG_BENCH_RESULT" (fs/canonical-path result-path)
           "YGG_BENCH_WORKTREE" (:worktreeRoot packet)
           "YGG_BENCH_WORKTREES" (json/write-json-str (:repos packet))
           "YGG_BENCH_PROJECT" (get-in packet [:artifacts :projectConfig])
           "YGG_BENCH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])
           "YGG_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])}
    (:token-usage-path opts)
    (assoc "YGG_BENCH_TOKEN_USAGE" (:token-usage-path opts))
    (parser-worker-option opts)
    (assoc "YGG_BENCH_PARSER_WORKER" (parser-worker-option opts)
           "YGG_PARSER_WORKER" (parser-worker-option opts))
    (get-in packet [:artifacts :yggContextPath])
    (assoc "YGG_BENCH_YGG_CONTEXT"
           (get-in packet [:artifacts :yggContextPath]))
    (get-in packet [:artifacts :yggHintsPath])
    (assoc "YGG_BENCH_YGG_HINTS"
           (get-in packet [:artifacts :yggHintsPath]))))
