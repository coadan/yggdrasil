(ns ygg.agent-install
  "Assistant integration file generation for Yggdrasil."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "ygg.agent-install/v1")

(def supported-platforms
  #{"codex"})

(def ^:private begin-marker
  "<!-- BEGIN YGG AGENT-INSTALL -->")

(def ^:private end-marker
  "<!-- END YGG AGENT-INSTALL -->")

(defn- ensure-supported-platform!
  [platform]
  (when-not (contains? supported-platforms platform)
    (throw (ex-info "Unsupported agent platform."
                    {:platform platform
                     :supported (sort supported-platforms)}))))

(defn- project-root
  [opts]
  (io/file (or (:root opts) (System/getProperty "user.dir"))))

(defn- instruction-path
  [platform opts]
  (case platform
    "codex" (io/file (project-root opts) "AGENTS.md")))

(defn- hook-path
  [platform opts]
  (case platform
    "codex" (io/file (project-root opts) ".codex" "hooks.json")))

(defn- read-file
  [file]
  (when (.exists file)
    (slurp file)))

(defn- write-file!
  [file content]
  (let [parent (.getParentFile file)
        tmp (io/file (str (.getPath file) ".tmp"))]
    (when parent
      (.mkdirs parent))
    (spit tmp content)
    (try
      (java.nio.file.Files/move
       (.toPath tmp)
       (.toPath file)
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (java.nio.file.Files/move
         (.toPath tmp)
         (.toPath file)
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
    (.getPath file)))

(defn- replace-marked-section
  [content section]
  (let [content (or content "")
        pattern (re-pattern (str "(?s)"
                                 (java.util.regex.Pattern/quote begin-marker)
                                 ".*?"
                                 (java.util.regex.Pattern/quote end-marker)
                                 "\\n*"))]
    (if (re-find pattern content)
      (str/replace content pattern section)
      (str (str/trimr content)
           (when (seq (str/trim content)) "\n\n")
           section))))

(defn- instruction-section
  []
  (str begin-marker "\n"
       "# Yggdrasil Agent Workflow\n\n"
       "Use Yggdrasil before broad project exploration when the task depends on "
       "project structure, ownership, dependencies, or system boundaries.\n\n"
       "Start with:\n\n"
       "```sh\n"
       "ygg sync inspect <project.edn> --json\n"
       "ygg sync <project.edn> --check\n"
       "ygg audit-scope <project.edn> --json\n"
       "ygg view systems --project <project-id> --detail primary\n"
       "```\n\n"
       "`sync inspect --json` returns graph-basis freshness, `evidence.families`, "
       "coverage counts, extractor fingerprint groups, plugin package caveats, "
       "diagnostics, and structured `nextActions`; follow freshness, coverage, "
       "and unbenchmarked plugin caveats before trusting absence of graph "
       "evidence.\n\n"
       "`audit-scope --json` summarizes core evidence across source, docs, "
       "dependencies, runtime/config, containers, assets, skipped files, and "
       "extractor diagnostics. Use it for architecture-class work and "
       "right-tool-for-the-job benchmark cases before falling back to broad "
       "CLI-only inventory.\n\n"
       "When coverage, sync inspect, or evidence readiness points at skipped or unsupported "
       "source, use the plugin lane before concluding Yggdrasil has no evidence:\n\n"
       "```sh\n"
       "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>\n"
       "bb plugin list <project.edn> --kind extractor --query <file-kind-or-extension>\n"
       "bb plugin gap extractor <package-dir> <repo-root> <file> --json\n"
       "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture <file>\n"
       "bb plugin dry-run extractor <package-dir> <repo-root> <file> --json\n"
       "bb plugin diagnose <package-dir>\n"
       "bb plugin core-check <package-dir>\n"
       "```\n\n"
       "Extractor plugins run after core extraction: they may enhance existing "
       "core rows or add rows for file families core does not support. Report "
       "plugins use the same package model for generated report panels, "
       "diagnostics, and artifacts. Treat unbenchmarked or project-local plugin "
       "output as useful review evidence, not benchmark-backed architecture "
       "understanding. Keep project-specific plugins outside core; promote only "
       "base-scoped, FOSS/non-commercial packages with benchmark artifacts that "
       "show material improvement for the relevant problem class.\n\n"
       "For graph-grounded query packets:\n\n"
       "```sh\n"
       "ygg query \"<question>\" --project <project-id> --json\n"
       "```\n\n"
       "When known, pass anchors, symbols, or literal strings to `ygg query` "
       "instead of relying on broad prose alone.\n\n"
       "For index maintenance work:\n\n"
       "```sh\n"
       "ygg sync check <project.edn> --enqueue\n"
       "ygg sync work list --project <project-id> --status ready\n"
       "ygg sync work pull --project <project-id> --agent codex\n"
       "ygg sync work show <work-id>\n"
       "ygg sync work heartbeat <work-id> --agent codex --lease-minutes 30\n"
       "ygg sync work complete <work-id> --result result.json\n"
       "ygg sync activity <project.edn>\n"
       "ygg sync work validate <work-id>\n"
       "```\n\n"
       "Use `ygg.map.json` only as an explicit portability format for reviewed "
       "imports or exports, not as an ordinary context argument in agent "
       "workflows.\n\n"
       "For MCP clients, run `ygg-mcp --config project.edn` "
       "and start with the default typed tools: `ygg_query`, "
       "`ygg_node`, `ygg_status`, and `ygg_systems`. Opt into advanced listed "
       "tools with `--tools default,sync,work` or "
       "`YGG_MCP_TOOLS=all`; direct handlers remain bounded Yggdrasil packet "
       "tools, not arbitrary shell or map mutation.\n\n"
       "Use `ygg_query` as the primary context packet for structural "
       "questions. It returns graph-basis freshness, `evidence.planes`, candidate files, "
       "snippets, relationships, graph facts, architecture evidence, and "
       "drilldowns. Inspect `architecture.summary` first for evidence-family "
       "statuses, validation gaps, and bounded next-action samples; it is kept "
       "even when detailed architecture rows are trimmed. Treat returned "
       "snippets as already-read source context and use relationships for "
       "nearby mechanical edges before broad grep/read loops. If `sync inspect` "
       "or evidence readiness reports `limited`, `empty`, `stale`, or `unsynced`, follow "
       "`nextActions` before concluding evidence does not exist.\n\n"
       "Use queued review packets for bounded corrections such as unresolved "
       "dependency imports. Complete packets with explicit JSON results that cite "
       "packet evidence before applying accepted corrections through supported "
       "Yggdrasil commands.\n\n"
       "Keep Yggdrasil evidence-first. Deterministic code records mechanical facts; "
       "humans or LLM-backed correction packets make semantic decisions. Do not "
       "infer architecture from names, host strings, path vocabulary, prose, or "
       "substring lists. Record accepted project meaning through supported "
       "Yggdrasil commands; export a portable map artifact only when the task "
       "explicitly calls for one.\n\n"
       "Do not justify Yggdrasil agent-efficiency work with hand-wavy claims. Any "
       "claim that Yggdrasil makes agents faster, easier, or more effective must "
       "point to replayable shell-only versus Yggdrasil evidence such as benchmark "
       "reports, `bb efficiency` summaries, command counts, timing, localization, "
       "citation rates, or patch success. Treat `yggCommandCount` as observed "
       "tool usage; search/read/shell command reductions are the lower-is-better "
       "loop metrics. Efficiency suites must include manually tagged problem "
       "classes, not only simple file-localization issues. Include architecture-"
       "class cases, using tracked benchmark suites with curated ground truth "
       "when necessary, and name the class where Yggdrasil helped or regressed. "
       "Before making architecture or extractor improvement claims, run "
       "`bb bench:gate --check-only` when current score artifacts exist; run "
       "`bb bench:gate` when they are missing or stale. The gate must pass with "
       "claim readiness supported, graph expectations passing, and zero "
       "maintenance preflight blockers.\n"
       end-marker "\n"))

(defn- broad-search-hook
  []
  {:matcher "Bash"
   :hooks [{:type "command"
            :command (str
                      "CMD=$(python3 -c \"import json,sys; "
                      "d=json.load(sys.stdin); "
                      "print(d.get('tool_input', d).get('command', ''))\" "
                      "2>/dev/null || true); "
                      "case \"$CMD\" in "
                      "*rg\\ *|*grep\\ *|*find\\ *|*fd\\ *|*ag\\ *|*ack\\ *) "
                      "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                      "\"additionalContext\":\"Yggdrasil may have relevant project context. "
                      "Prefer `ygg sync inspect <project.edn> --json` "
                      "to inspect freshness and evidence.families, then "
                      "`ygg query \\\"<question>\\\" --project "
                      "<project-id> --json` before broad raw-file search.\"}}' ;; "
                      "esac")}]})

(defn- hooks-json
  []
  {:hooks [(broad-search-hook)]})

(defn- hooks-content
  []
  (str (json/write-json-str (hooks-json) {:indent-str "  "}) "\n"))

(defn- install-plan
  [platform opts]
  (let [instructions (instruction-path platform opts)
        existing (read-file instructions)
        new-content (replace-marked-section existing (instruction-section))
        hooks (when (:hooks? opts)
                (let [file (hook-path platform opts)]
                  {:path (.getPath file)
                   :content (hooks-content)}))]
    (cond-> {:schema schema
             :action "print-config"
             :platform platform
             :scope "project"
             :instructions {:path (.getPath instructions)
                            :content new-content}}
      hooks (assoc :hooks hooks))))

(defn- write-hooks!
  [platform opts]
  (let [file (hook-path platform opts)
        content (hooks-content)
        existing (read-file file)]
    (when (and existing
               (not= existing content)
               (not (:force? opts)))
      (throw (ex-info "Hook file already exists. Pass --force to replace it."
                      {:path (.getPath file)})))
    (write-file! file content)))

(defn install!
  "Install assistant guidance for platform.

  Supported opts:
  - `:root`: project root, defaults to current working directory
  - `:project?`: must be true for the current implementation
  - `:hooks?`: write optional hook configuration
  - `:force?`: replace an existing hook file when `:hooks?` is true
  - `:print-config?`: return generated config without writing files"
  [platform opts]
  (ensure-supported-platform! platform)
  (when-not (:project? opts)
    (throw (ex-info "Only project-scoped agent install is implemented."
                    {:platform platform
                     :hint "Pass --project."})))
  (if (:print-config? opts)
    (install-plan platform opts)
    (let [instructions (instruction-path platform opts)
          existing (read-file instructions)
          new-content (replace-marked-section existing (instruction-section))
          instruction-path (write-file! instructions new-content)
          hooks-path (when (:hooks? opts)
                       (write-hooks! platform opts))]
      (cond-> {:schema schema
               :action "install"
               :platform platform
               :scope "project"
               :instructions instruction-path}
        hooks-path (assoc :hooks hooks-path)))))

(defn uninstall!
  "Remove assistant guidance for platform."
  [platform opts]
  (ensure-supported-platform! platform)
  (when-not (:project? opts)
    (throw (ex-info "Only project-scoped agent uninstall is implemented."
                    {:platform platform
                     :hint "Pass --project."})))
  (let [instructions (instruction-path platform opts)
        existing (read-file instructions)
        cleaned (when existing
                  (let [pattern (re-pattern (str "(?s)\\n*"
                                                 (java.util.regex.Pattern/quote begin-marker)
                                                 ".*?"
                                                 (java.util.regex.Pattern/quote end-marker)
                                                 "\\n*"))]
                    (str/trimr (str/replace existing pattern "\n"))))
        removed-instructions? (boolean (and existing
                                            (not= existing cleaned)))
        hooks (hook-path platform opts)
        removed-hooks? (and (.exists hooks)
                            (do
                              (.delete hooks)
                              true))]
    (when removed-instructions?
      (if (seq (str/trim cleaned))
        (write-file! instructions (str cleaned "\n"))
        (.delete instructions)))
    {:schema schema
     :action "uninstall"
     :platform platform
     :scope "project"
     :removed {:instructions removed-instructions?
               :hooks (boolean removed-hooks?)}}))

(defn list-platforms
  "Return supported install targets."
  []
  {:schema schema
   :platforms (->> supported-platforms
                   sort
                   (mapv (fn [platform]
                           {:id platform
                            :scopes ["project"]
                            :hooks? (= "codex" platform)})))})
