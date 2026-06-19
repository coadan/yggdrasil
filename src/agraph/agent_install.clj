(ns agraph.agent-install
  "Assistant integration file generation for AGraph."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.agent-install/v1")

(def supported-platforms
  #{"codex"})

(def ^:private begin-marker
  "<!-- BEGIN AGRAPH AGENT-INSTALL -->")

(def ^:private end-marker
  "<!-- END AGRAPH AGENT-INSTALL -->")

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
       "# AGraph Agent Workflow\n\n"
       "Use AGraph before broad project exploration when the task depends on "
       "project structure, ownership, dependencies, or system boundaries.\n\n"
       "Start with:\n\n"
       "```sh\n"
       "agraph status <project.edn> --json\n"
       "agraph sync <project.edn> --check --map agraph.map.json\n"
       "agraph view systems --project <project-id> --detail primary\n"
       "```\n\n"
       "`status --json` returns graph-basis freshness, available evidence "
       "planes, counts, and structured `nextActions`; follow those rows before "
       "broad raw-file search.\n\n"
       "For one-shot evidence packets:\n\n"
       "```sh\n"
       "agraph explore \"<question>\" --project <project-id> --json\n"
       "```\n\n"
       "For longer investigations with stable graph context:\n\n"
       "```sh\n"
       "agraph explore create \"<question>\" --project <project-id> --map agraph.map.json\n"
       "agraph explore open <cursor-id> <target-id-or-label>\n"
       "agraph explore expand <cursor-id> <target-id-or-label>\n"
       "agraph explore docs <cursor-id> <target-id-or-label>\n"
       "agraph explore search <cursor-id> \"<follow-up query>\"\n"
       "```\n\n"
       "For graph maintenance work:\n\n"
       "```sh\n"
       "agraph sync check <project.edn> --map agraph.map.json --enqueue\n"
       "agraph sync work list --project <project-id> --status ready\n"
       "agraph sync work pull --project <project-id> --agent codex\n"
       "agraph sync work show <work-id>\n"
       "agraph sync work heartbeat <work-id> --agent codex --lease-minutes 30\n"
       "agraph sync work complete <work-id> --result result.json\n"
       "agraph sync activity <project.edn>\n"
       "agraph sync work apply <work-id> --map agraph.map.json\n"
       "```\n\n"
       "For MCP clients, run `agraph-mcp --config project.edn --map agraph.map.json` "
       "and prefer the typed tools over reconstructing shell commands: "
       "`agraph_explore`, `agraph_ask`, `agraph_explore_create`, `agraph_explore_open`, "
       "`agraph_explore_expand`, `agraph_explore_docs`, `agraph_explore_search`, "
       "`agraph_view_systems`, `agraph_status`, `agraph_sync_inspect`, "
       "`agraph_sync_check`, `agraph_sync_activity`, `agraph_work_list`, `agraph_work_show`, "
       "`agraph_work_pull`, `agraph_work_heartbeat`, "
       "`agraph_work_complete`, `agraph_work_release`, and "
       "`agraph_work_reject`.\n\n"
       "Use `agraph_explore` as the primary one-shot orientation packet for "
       "structural questions. It returns the same context shape as `agraph ask` "
       "with answerability, candidate files, snippets, graph facts, and drilldowns.\n\n"
       "Use queued review packets for bounded corrections such as unresolved "
       "dependency imports. Complete packets with explicit JSON results that cite "
       "packet evidence before applying accepted corrections to `agraph.map.json`.\n\n"
       "Keep AGraph evidence-first. Deterministic code records mechanical facts; "
       "humans or LLM-backed correction packets make semantic decisions. Do not "
       "infer architecture from names, host strings, path vocabulary, prose, or "
       "substring lists. Record accepted project meaning in `agraph.map.json` or "
       "metadata through supported `agraph sync` commands.\n\n"
       "Do not justify AGraph agent-efficiency work with hand-wavy claims. Any "
       "claim that AGraph makes agents faster, easier, or more effective must "
       "point to replayable shell-only versus AGraph evidence such as benchmark "
       "reports, `bb efficiency` summaries, command counts, timing, localization, "
       "citation rates, or patch success. Efficiency suites must include manually "
       "tagged problem classes, not only simple file-localization issues. Include "
       "architecture-class cases, using synthetic OSS-corpus prompts with curated "
       "ground truth when necessary, and name the class where AGraph helped or "
       "regressed.\n"
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
                      "\"additionalContext\":\"AGraph may have relevant project context. "
                      "Prefer `agraph ask \\\"<question>\\\" --project <project-id> --json` "
                      "or `agraph view systems --project <project-id> --detail primary` "
                      "before broad raw-file search.\"}}' ;; "
                      "esac")}]})

(defn- hooks-json
  []
  {:hooks [(broad-search-hook)]})

(defn- write-hooks!
  [platform opts]
  (let [file (hook-path platform opts)
        content (str (json/write-json-str (hooks-json) {:indent-str "  "}) "\n")
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
  - `:force?`: replace an existing hook file when `:hooks?` is true"
  [platform opts]
  (ensure-supported-platform! platform)
  (when-not (:project? opts)
    (throw (ex-info "Only project-scoped agent install is implemented."
                    {:platform platform
                     :hint "Pass --project."})))
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
      hooks-path (assoc :hooks hooks-path))))

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
