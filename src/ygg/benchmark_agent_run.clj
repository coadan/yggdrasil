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
   "If the issue asks for multiple packages, components, contracts, or files, include each directly requested target's primary file as a suspectedFile when evidence shows it participates in the requested change path; do not demote those requested targets solely to evidence rows."
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
  ["Copy caseId, caseFingerprint, and agentInputFingerprint from Run Metadata below; do not run env, printenv, jq, or packet reads solely to recover them."
   "Do not read YGG_BENCH_PACKET only to recover issue text or identity fields when they are already present in the prompt or environment."
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
   :parserWorker {:mode "none|java|dotnet|javascript|typescript|all"
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

(declare ygg-mode?)

(defn- agent-prompt-profile-lines
  [profile packet result-scope]
  (case profile
    "fast" (vec
            (remove nil?
                    ["## Fast Localization Profile"
                     "- Localization only. Do not patch files."
                     "- Do not run full test/build suites."
                     (if (ygg-mode? packet)
                       "- Yggdrasil fast mode hard cap: use at most 3 local shell commands; zero commands is valid when prepared evidence is sufficient."
                       "- Use at most 8 local shell commands.")
                     "- Inspect at most 12 files or snippets."
                     (if (ygg-mode? packet)
                       "- Use the Prepared Yggdrasil Summary first; if shell proof is needed, run only the listed minimal proof commands unless they fail."
                       "- Prefer `rg`, focused `sed`, and packet-provided Yggdrasil query commands.")
                     (if (ygg-mode? packet)
                       "- Do not invent separate `rg` commands for prepared candidates, and do not run `rg` on file-only candidates without declaration labels."
                       "- Constrain `rg` to exact files or shallow globs and cap output; avoid recursive directory searches that dump support files.")
                     (when (ygg-mode? packet)
                       "- Keep result JSON compact: `suspectedSymbols` may be empty; include at most 3 symbols, at most 2 evidence strings per file, and at most 1 evidence string per symbol.")
                     (when (ygg-mode? packet)
                       "- Keep summary, reasons, and evidence to short single sentences; cite only the strongest distinct evidence.")
                     (when (ygg-mode? packet)
                       "- Do not emit provisional JSON before local inspection; produce exactly one final JSON result after any commands you choose to run.")
                     (when (ygg-mode? packet)
                       "- For package-level requested targets, keep the primary package contract file as the suspectedFile; use sibling per-signal files as evidence unless the issue explicitly asks for each sibling file.")
                     (when (ygg-mode? packet)
                       "- When the Prepared Yggdrasil Summary lists related graph files from import-package edges, consider those rows before per-signal sibling files for package-level requested targets.")
                     (when (ygg-mode? packet)
                       "- Do not read the same file twice in fast Yggdrasil mode; use one narrow proof window or answer from prepared evidence.")
                     (when (ygg-mode? packet)
                       "- For single-file documentation or wording tasks, if the prepared/context summary identifies the target page, return that file without repeated local reads.")
                     (when (ygg-mode? packet)
                       "- For zero-command answers, every suspectedFiles evidence string must cite a shown prepared/context/related/candidate evidence row for that path; issue-text inference alone is not enough.")
                     "- Return the best 1-5 suspected files as soon as evidence is sufficient."
                     (str "- " (str/join "\n- " (result-scope-rules result-scope)))
                     (str "- " (str/join "\n- " evidence-citation-rules))
                     (str "- " (str/join "\n- " result-integrity-rules))
                     "- If structured output is active, make the final response the result JSON."
                     "- Otherwise write JSON to `YGG_BENCH_RESULT`; do not include prose outside JSON."
                     ""]))
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

(defn- prompt-text
  [value]
  (some-> value str str/trim not-empty))

(defn- bounded-prompt-text
  [limit value]
  (when-let [text (prompt-text value)]
    (if (<= (count text) limit)
      text
      (str (subs text 0 (max 0 (- limit 14))) " [truncated]"))))

(defn- issue-lines
  [packet]
  (let [input (:input packet)
        title (bounded-prompt-text 400 (:title input))
        body (bounded-prompt-text 4000 (:body input))
        comments (->> (:comments input)
                      (keep #(bounded-prompt-text 1000 %))
                      (take 3)
                      vec)]
    (when (or title body (seq comments))
      (vec
       (concat
        ["## Issue"
         "The issue text is reproduced here; do not read `YGG_BENCH_PACKET` only to recover the issue."
         (str "- Title: " (or title "not provided"))]
        (when body
          [""
           "Body:"
           body])
        (when (seq comments)
          (concat ["" "Comments:"]
                  (map-indexed (fn [idx comment]
                                 (str (inc idx) ". " comment))
                               comments)))
        [""])))))

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

(defn- read-json-artifact
  [path]
  (when-not (benchmark-util/blankish? path)
    (try
      (json/read-json (slurp path) :key-fn keyword)
      (catch Exception _
        nil))))

(defn- compact-text
  [limit value]
  (when-let [text (some-> value str str/trim not-empty)]
    (if (<= (count text) limit)
      text
      (str (subs text 0 (max 0 (- limit 14))) " [truncated]"))))

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- declaration-summary
  [declaration]
  (let [label (compact-text 90 (:label declaration))
        kind (compact-text 40 (:kind declaration))
        source-line (:sourceLine declaration)]
    (when label
      (str label
           (when kind
             (str " " kind))
           (when source-line
             (str ":" source-line))))))

(defn- row-repo
  [row]
  (some-> (or (:repoId row) (:repo-id row) (:repo row)) str not-empty))

(defn- confidence-fragment
  [row]
  (when-let [confidence (when (number? (:confidence row))
                          (:confidence row))]
    (format " confidence %.2f" (double confidence))))

(defn- prepared-candidate-summary
  [candidate]
  (let [path (compact-text 120 (:path candidate))
        repo (compact-text 80 (row-repo candidate))
        declarations (->> (:declarations candidate)
                          (keep declaration-summary)
                          (take 2)
                          vec)
        evidence (compact-text 120 (first (:evidence candidate)))]
    (when path
      (str "- " (:rank candidate) ". `" path "`"
           (when repo
             (str " repo " repo))
           (confidence-fragment candidate)
           (when (seq declarations)
             (str " declarations: " (str/join "; " declarations)))
           (when (and (seq declarations) evidence)
             " |")
           (when (and (empty? declarations) evidence)
             " ")
           (when evidence
             (str " evidence: " evidence))))))

(defn- related-file-summary
  [row]
  (let [path (compact-text 120 (:path row))
        repo (compact-text 80 (row-repo row))
        relation (compact-text 40 (:relation row))
        source (some-> row :via first)
        source-path (compact-text 90 (:seedPath source))
        source-line (:sourceLine source)
        evidence (compact-text 140 (first (:evidence row)))]
    (when path
      (str "- related " (:rank row) ". `" path "`"
           (when repo
             (str " repo " repo))
           (when relation
             (str " relation: " relation))
           (when source-path
             (str " via " source-path
                  (when source-line
                    (str ":" source-line))))
           (when evidence
             (str " | evidence: " evidence))))))

(defn- context-file-summary
  [row]
  (let [path (compact-text 120 (:path row))
        repo (compact-text 80 (row-repo row))
        evidence (compact-text 140 (first (:evidence row)))]
    (when path
      (str "- context " (:rank row) ". `" path "`"
           (when repo
             (str " repo " repo))
           (confidence-fragment row)
           (when evidence
             (str " | evidence: " evidence))))))

(defn- path-depth
  [path]
  (if-let [path (some-> path str not-empty)]
    (count (filter #{\/} path))
    Long/MAX_VALUE))

(defn- candidate-rank
  [candidate]
  (long (or (:rank candidate) Long/MAX_VALUE)))

(defn- select-prepared-summary-candidates
  [candidates]
  (let [candidates (vec candidates)
        top-ranked (take 2 candidates)
        shallow-file-only (->> candidates
                               (filter #(empty? (:declarations %)))
                               (sort-by (juxt (comp path-depth :path)
                                              candidate-rank))
                               (take 1))
        fallback-ranked (->> candidates
                             (drop 2)
                             (take 1))]
    (->> (concat top-ranked shallow-file-only fallback-ranked)
         (reduce (fn [selected candidate]
                   (if (some #(= (:path %) (:path candidate)) selected)
                     selected
                     (conj selected candidate)))
                 [])
         (take 3))))

(defn- select-related-summary-files
  [related-files]
  (->> related-files
       (filter #(some-> (:path %) str not-empty))
       (filter #(= "imports-package" (some-> (:relation %) str)))
       (take 8)))

(defn- select-context-summary-files
  [top-files]
  (->> top-files
       (filter #(some-> (:path %) str not-empty))
       (take 12)))

(defn- terraform-declaration-pattern
  [{:keys [kind label]}]
  (let [kind (some-> kind str)
        label (some-> label str)]
    (cond
      (and (= "terraform-variable" kind)
           (str/starts-with? label "var."))
      (str "variable \"" (subs label 4) "\"")

      (and (= "terraform-output" kind)
           (str/starts-with? label "output."))
      (str "output \"" (subs label 7) "\"")

      (and (= "terraform-provider" kind)
           (str/starts-with? label "provider."))
      (str "provider \"" (subs label 9) "\"")

      (and (#{"terraform-resource" "terraform-data-source"} kind)
           label)
      (let [label (if (and (= "terraform-data-source" kind)
                           (str/starts-with? label "data."))
                    (subs label 5)
                    label)
            [resource-type resource-name] (str/split label #"\." 2)]
        (when (and (not-empty resource-type) (not-empty resource-name))
          (str (if (= "terraform-data-source" kind) "data" "resource")
               " \""
               resource-type
               "\" \""
               resource-name
               "\""))))))

(defn- declaration-rg-command
  [candidates]
  (let [patterns (->> candidates
                      (mapcat :declarations)
                      (keep terraform-declaration-pattern)
                      distinct
                      (take 8)
                      vec)
        paths (->> candidates
                   (filter #(some terraform-declaration-pattern
                                  (:declarations %)))
                   (keep :path)
                   distinct
                   (take 4)
                   vec)]
    (when (and (seq patterns) (seq paths))
      (str "rg -n --fixed-strings "
           (str/join " "
                     (map #(str "-e " (shell-quote %)) patterns))
           " "
           (str/join " " (map shell-quote paths))))))

(defn- file-start-command
  [candidate]
  (when-let [path (some-> (:path candidate) not-empty)]
    (str "sed -n '1,80p' " (shell-quote path))))

(defn- selected-read-plan-commands
  [hints selected-paths]
  (let [selected-paths (set selected-paths)]
    (->> (get-in hints [:readPlan :snippets])
         (filter #(contains? selected-paths (:path %)))
         (keep :command))))

(defn- terraform-declaration-candidate?
  [candidate]
  (boolean
   (some terraform-declaration-pattern (:declarations candidate))))

(defn- resource-like-candidate?
  [candidate]
  (boolean
   (some #(contains? #{"terraform-resource" "terraform-data-source"}
                     (some-> (:kind %) str))
         (:declarations candidate))))

(defn- proof-commands
  ([hints selected-candidates]
   (proof-commands hints selected-candidates []))
  ([hints selected-candidates context-files]
   (let [declaration-command (declaration-rg-command selected-candidates)
         read-plan-paths (->> selected-candidates
                              (filter #(or (resource-like-candidate? %)
                                           (and (seq (:declarations %))
                                                (not (terraform-declaration-candidate? %)))))
                              (keep :path)
                              (concat (keep :path context-files))
                              set)
         file-only-commands (->> selected-candidates
                                 (filter #(empty? (:declarations %)))
                                 (keep file-start-command))]
     (->> (concat [declaration-command]
                  (selected-read-plan-commands hints read-plan-paths)
                  file-only-commands)
          (remove nil?)
          distinct
          (take 3)
          vec))))

(defn- prepared-summary-lines
  [packet]
  (when (ygg-mode? packet)
    (let [hints (read-json-artifact (get-in packet [:artifacts :yggHintsPath]))
          selected-candidates (->> (get-in hints [:preparedLocalization :candidates])
                                   select-prepared-summary-candidates
                                   vec)
          candidates (->> selected-candidates
                          (keep prepared-candidate-summary)
                          vec)
          context-files (->> (:topFiles hints)
                             select-context-summary-files
                             (keep context-file-summary)
                             vec)
          related-files (->> (:relatedFiles hints)
                             select-related-summary-files
                             (keep related-file-summary)
                             vec)
          proof-commands (proof-commands hints selected-candidates context-files)]
      (when (or (seq candidates) (seq context-files) (seq related-files))
        (vec
         (concat
          ["## Prepared Yggdrasil Summary"
           "Start from this compact prepared localization summary before opening hint artifacts."
           "These rows are a starter audit surface, not an edit list; return only files with direct task evidence."
           "Context-ranked files are compact Yggdrasil topFiles rows with path evidence and repo identity."
           "Related graph files come from explicit import-package edges and can cover requested package-level targets."
           "Do not run a first-step `jq` projection over `YGG_BENCH_YGG_HINTS`; the file is available only for evidence gaps."
           "Do not grep Yggdrasil hint/context artifacts for extra citations; summary evidence strings and local file lines are enough when they answer the task."
           "Use exact-file `rg -n --fixed-strings <symbol> <path...>` or narrow `sed` windows on these paths when proof is needed."
           ""]
          candidates
          (when (seq context-files)
            (concat
             ["" "Context-ranked files from compact Yggdrasil topFiles:"]
             context-files))
          (when (seq related-files)
            (concat
             ["" "Related graph files from prepared import edges:"]
             related-files))
          (when (seq proof-commands)
            (concat
             ["" "Minimal proof commands; if shell proof is needed, run only these until direct evidence is sufficient:"]
             (map #(str "- `" % "`") proof-commands)))
          [""]))))))

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
     (str "Yggdrasil is prepared and warm. Use the Prepared Yggdrasil Summary "
          "above as the first ranked audit surface. If those candidates cover "
          "the requested files and declarations, cite their shown evidence and "
          "avoid rediscovering them with `rg`. Do not run a first-step `jq` "
          "projection over `YGG_BENCH_YGG_HINTS`, and do not print entire "
          "Yggdrasil JSON artifacts, evidence arrays, or "
          "`readPlan.snippets[].snippet`. Use exact candidate paths for focused "
          "local file reads only when prepared candidates leave a concrete "
          "evidence gap. Avoid broad `rg`; in Yggdrasil mode, do not pass "
          "directories to `rg` when exact files are listed by the prepared "
          "summary or hints. Do not run `rg`, `grep`, `jq`, `cat`, or `sed` "
          "against `YGG_BENCH_YGG_HINTS` or `YGG_BENCH_YGG_CONTEXT` just to add "
          "graph citations; cite the prepared summary evidence strings already "
          "shown in the prompt and the local file lines you inspect. "
          "Use full hints or context only when the prepared summary and focused "
          "file reads cannot answer the task. Run suggested proof commands only "
          "until direct evidence is sufficient before inventing new commands. "
          "Do not invent extra `rg` commands for prepared candidates. For "
          "file-only candidates without declaration labels, use the suggested "
          "`sed` window instead of `rg` over generic identifiers. Prefer exact-file `rg -n` for "
          "named declarations and references before long `sed` dumps; keep "
          "each `sed` window at 90 lines or fewer unless a shorter read fails "
          "to expose required evidence. Focused file reads can satisfy "
          "evidence without a full-context lookup. Do not run directory-wide "
          "`rg` just to reconfirm prepared top-file or read-plan hits. Treat "
          "`sourceCoverage` and `diagnostics` as trust boundaries; run listed "
          "commands or validation-gap next actions only for weak or missing "
          "planes.")
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
       "- `YGG_BENCH_CASE_ID`, `YGG_BENCH_CASE_FINGERPRINT`, and `YGG_BENCH_AGENT_INPUT_FINGERPRINT` carry result identity; use them instead of reading the packet solely for identity."
       "- `YGG_BENCH_WORKTREE` is the base checkout."
       "- `YGG_BENCH_PROJECT` is the generated Yggdrasil project config."
       "- `YGG_BENCH_XTDB_PATH` and `YGG_XTDB_PATH` point to the graph store."
       ""]
      (agent-prompt-profile-lines profile packet result-scope)
      (issue-lines packet)
      ["## Task"
       (get-in packet [:task :objective])
       ""
       "Use the issue text above, inspect the checkout, and write the ranked localization result JSON."
       "Return files before proposing or applying a patch."
       (str/join "\n" (task-rule-lines task))
       ""]
      (decision-prompt-lines packet)
      (result-contract-lines profile packet)
      (prepared-summary-lines packet)
      (ygg-mode-lines packet)
      ["## Run Metadata"
       (str "- Suite: " (:suite-id packet))
       (str "- Case: " (:case-id packet))
       (str "- Case fingerprint: " (:caseFingerprint packet))
       (str "- Agent input fingerprint: " (:agentInputFingerprint packet))
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
