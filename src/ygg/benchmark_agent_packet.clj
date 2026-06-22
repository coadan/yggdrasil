(ns ygg.benchmark-agent-packet
  (:require [ygg.benchmark-agent-run :as benchmark-agent-run]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.extract :as extract]
            [ygg.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def agent-packet-schema
  "ygg.benchmark.agent-packet/v1")

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def ^:private agent-run-modes
  ["ygg" "shell-only"])

(def ^:private agent-run-mode-set
  (set agent-run-modes))

(defn parser-worker-option
  [opts]
  (extract/normalize-parser-worker-mode (:parser-worker opts)))
(defn parser-worker-profile
  [opts]
  (let [option-mode (parser-worker-option opts)
        env-mode (extract/normalize-parser-worker-mode
                  (System/getenv "YGG_PARSER_WORKER"))]
    {:mode (or option-mode env-mode "none")
     :source (cond
               option-mode "option"
               env-mode "env"
               :else "default")}))
(defn normalize-parser-worker-profile
  [profile]
  (let [mode (extract/normalize-parser-worker-mode (:mode profile))
        source (some-> (:source profile) str str/trim not-empty)]
    (when mode
      {:mode mode
       :source (or source "unknown")})))
(defn agent-score-parser-worker-profile
  [opts agent-result]
  (or (when (parser-worker-option opts)
        (parser-worker-profile opts))
      (let [profile (normalize-parser-worker-profile (:parserWorker agent-result))]
        (when (not= "unknown" (:source profile))
          profile))
      (parser-worker-profile opts)))
(defn with-benchmark-parser-worker
  [opts f]
  (extract/with-parser-worker-mode (parser-worker-option opts)
    (f)))
(defn shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))
(defn- ygg-command-root
  []
  (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(defn- agent-clj-config-dir
  [prepared]
  (fs/canonical-path (io/file (:worktreeRoot prepared) ".cpcache" "clj-config")))
(defn- env-command
  [xtdb-path clj-config-dir & args]
  (let [[extra-env args] (if (map? (first args))
                           [(first args) (rest args)]
                           [{} args])
        [command & command-args] args
        env-vars (merge {"CLJ_CONFIG" clj-config-dir
                         "YGG_XTDB_PATH" xtdb-path}
                        extra-env)]
    (str "mkdir -p "
         (shell-quote clj-config-dir)
         " && cd "
         (shell-quote (ygg-command-root))
         " && "
         (str/join " "
                   (map (fn [[k v]]
                          (str k "=" (shell-quote v)))
                        env-vars))
         " "
         (str/join " " (cons command (map shell-quote command-args))))))
(defn agent-mode
  [opts]
  (let [mode (name (keyword (or (:mode opts) :ygg)))]
    (when-not (contains? agent-run-mode-set mode)
      (throw (ex-info "Unknown benchmark agent mode."
                      {:mode mode
                       :supported agent-run-modes})))
    mode))
(defn agent-project
  [prepared]
  {:id (:project-id prepared)
   :name (:case-id prepared)
   :repos [{:id (:repo-id prepared)
            :root (:worktreeRoot prepared)
            :role :application}]})
(defn- parser-worker-command-env
  [opts]
  (if-let [mode (parser-worker-option opts)]
    {"YGG_PARSER_WORKER" mode}
    {}))
(defn- agent-command-hints
  [prepared project-path xtdb-path mode opts]
  (let [clj-config-dir (agent-clj-config-dir prepared)
        parser-env (parser-worker-command-env opts)]
    (cond-> {:shell ["Inspect the checkout with ordinary local commands such as git, rg, find, sed, and tests."
                     "Do not read the fixing diff, PR, post-fix commits, or ground-truth artifacts."]}
      (= "ygg" mode)
      (assoc :ygg
             {:projectConfig project-path
              :xtdbPath xtdb-path
              :cljConfigDir clj-config-dir
              :setupCommand (env-command xtdb-path
                                         clj-config-dir
                                         parser-env
                                         "bb"
                                         "sync"
                                         project-path)
              :askCommand (env-command xtdb-path
                                       clj-config-dir
                                       parser-env
                                       "bb"
                                       "ask"
                                       (get-in prepared [:input :queryText])
                                       "--project"
                                       (:project-id prepared)
                                       "--json")
              :exploreCommand (env-command xtdb-path
                                           clj-config-dir
                                           parser-env
                                           "bb"
                                           "explore"
                                           "create"
                                           (get-in prepared [:input :queryText])
                                           "--project"
                                           (:project-id prepared)
                                           "--json")}))))
(defn- task-shape
  [prepared]
  (cond-> {:kind "issue-localization"
           :objective (str "Identify the repo-relative files and optional symbols most likely "
                           "needed to fix the issue from the base checkout.")
           :rules ["Use only the base checkout and issue text in this packet."
                   "Return ranked suspected files before attempting a patch."
                   (str/join " " benchmark-agent-run/suspected-files-scope-rules)
                   (str/join " " benchmark-agent-run/evidence-citation-rules)
                   (str/join " " benchmark-agent-run/result-integrity-rules)
                   "Keep reasoning evidence-based and cite commands or graph context used."
                   "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]
           :expectedResultSchema agent-result-schema
           :resultContract (benchmark-agent-run/agent-result-contract)}
    (seq (:decisionCandidates prepared))
    (assoc :decisionCandidates (:decisionCandidates prepared)
           :decisionKind (get-in prepared [:decisionGroundTruth :kind])
           :decisionRules ["Use only visible decision candidate ids."
                           "For each decision choice, return status include, exclude, or defer."
                           "Cite exact repo-relative evidence paths from the candidate when available."])))
(defn agent-packet-from-prepared!
  [suite case prepared opts]
  (let [mode (agent-mode opts)
        project-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))
        xtdb-path (fs/canonical-path (benchmark-paths/xtdb-dir suite case opts))
        packet-path (fs/canonical-path (benchmark-paths/agent-packet-path suite case opts))
        project-config (agent-project prepared)
        artifacts (cond-> {:projectConfig project-path
                           :packetPath packet-path
                           :xtdbPath xtdb-path}
                    (:ygg-context-path opts)
                    (assoc :yggContextPath (:ygg-context-path opts))
                    (:ygg-hints-path opts)
                    (assoc :yggHintsPath (:ygg-hints-path opts)))
        packet {:schema agent-packet-schema
                :suite-id (:suite-id prepared)
                :case-id (:case-id prepared)
                :caseFingerprint (:caseFingerprint prepared)
                :agentInputFingerprint (:agentInputFingerprint prepared)
                :repo-id (:repo-id prepared)
                :project-id (:project-id prepared)
                :mode mode
                :parserWorker (parser-worker-profile opts)
                :baseSha (:baseSha prepared)
                :worktreeRoot (:worktreeRoot prepared)
                :input (:input prepared)
                :task (task-shape prepared)
                :tools (agent-command-hints prepared project-path xtdb-path mode opts)
                :artifacts artifacts
                :fairness {:allowedInput ["issue title"
                                          "issue body"
                                          "pre-fix issue comments"
                                          "base checkout"
                                          "visible decision candidates"
                                          "Yggdrasil output generated from the base checkout"]
                           :forbiddenInput ["fix diff"
                                            "PR title or body"
                                            "post-fix issue comments"
                                            "post-fix commits"
                                            "ground-truth benchmark artifacts"]}}]
    (benchmark-io/write-edn-file! project-path project-config)
    (benchmark-io/write-json-file! packet-path packet)
    packet))
(defn agent-packet!
  "Prepare one case and write a provider-neutral agent localization packet."
  [suite case opts]
  (agent-packet-from-prepared! suite case (benchmark-prepare/prepare-case! suite case opts) opts))
(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  {:schema "ygg.benchmark.agent-packets/v1"
   :suite-id (:id suite)
   :packets (mapv #(agent-packet! suite % opts)
                  (benchmark-suite/selected-cases
                   suite
                   (benchmark-suite/case-selector opts)))})
