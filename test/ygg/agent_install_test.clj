(ns ygg.agent-install-test
  (:require [ygg.agent-install :as agent-install]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [dir (.toFile
             (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (.getPath dir)))

(deftest codex-guidance-points-agents-at-evidence-family-readiness
  (let [root (temp-dir "ygg-agent-guidance")
        result (agent-install/install! "codex" {:root root
                                                :project? true})
        content (slurp (io/file root "AGENTS.md"))]
    (is (= agent-install/schema (:schema result)))
    (is (str/includes? content "`evidence.families`"))
    (is (str/includes? content "extractor fingerprint groups"))
    (is (str/includes? content "`evidence.planes`"))
    (is (str/includes? content "`architecture.summary` first"))
    (is (str/includes? content "bounded next-action samples"))
    (is (str/includes? content "detailed architecture rows are trimmed"))
    (is (str/includes? content "pass anchors, symbols, or literal strings"))
    (is (str/includes? content "ygg sync inspect <project.edn> --json"))
    (is (str/includes? content "ygg audit-scope <project.edn> --json"))
    (is (str/includes? content "Use it for architecture-class work"))
    (is (str/includes? content "If `sync inspect` or evidence readiness reports"))
    (is (str/includes? content "`nextActions` before concluding evidence does not exist"))
    (is (str/includes? content "`yggCommandCount` as observed tool usage"))
    (is (str/includes? content "search/read/shell command reductions are the lower-is-better"))))

(deftest print-config-returns-codex-guidance-without-writing-files
  (let [root (temp-dir "ygg-agent-print-config")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true
                                                :print-config? true})
        agents (io/file root "AGENTS.md")
        hooks (io/file root ".codex" "hooks.json")]
    (is (= agent-install/schema (:schema result)))
    (is (= "print-config" (:action result)))
    (is (= (.getPath agents) (get-in result [:instructions :path])))
    (is (= (.getPath hooks) (get-in result [:hooks :path])))
    (is (str/includes? (get-in result [:instructions :content])
                       "Yggdrasil Agent Workflow"))
    (is (str/includes? (get-in result [:hooks :content])
                       "Yggdrasil may have relevant project context"))
    (is (not (.exists agents)))
    (is (not (.exists hooks)))))

(deftest codex-broad-search-hook-prefers-inspect-and-query
  (let [root (temp-dir "ygg-agent-hook")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true})
        hook-json (json/read-json (slurp (:hooks result)) :key-fn keyword)
        command (get-in hook-json [:hooks 0 :hooks 0 :command])]
    (is (str/includes? command "ygg sync inspect <project.edn> --json"))
    (is (str/includes? command "evidence.families"))
    (is (str/includes? command "ygg query"))))

(deftest codex-install-can-write-skill-and-mcp-command
  (let [root (temp-dir "ygg-agent-skill")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :skill? true
                                                :mcp? true})
        skill-file (io/file root ".codex" "skills" "ygg" "SKILL.md")
        content (slurp skill-file)]
    (is (= (.getPath skill-file) (:skill result)))
    (is (= {:command "ygg-mcp --config project.edn"} (:mcp result)))
    (is (str/includes? content "name: ygg"))
    (is (str/includes? content "ygg sync inspect <project.edn> --json"))
    (is (str/includes? content "Project memory enters normal query packets"))))

(deftest codex-platform-detection-uses-project-markers
  (let [root (temp-dir "ygg-agent-detect")
        codex-dir (io/file root ".codex")]
    (.mkdirs codex-dir)
    (let [result (agent-install/detect-platforms {:root root})]
      (is (= "ygg.agent-detect/v1" (:schema result)))
      (is (= "codex" (get-in result [:platforms 0 :id])))
      (is (= "project-marker" (get-in result [:platforms 0 :reason]))))))
