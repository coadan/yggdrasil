(ns agraph.agent-install-test
  (:require [agraph.agent-install :as agent-install]
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
  (let [root (temp-dir "agraph-agent-guidance")
        result (agent-install/install! "codex" {:root root
                                                :project? true})
        content (slurp (io/file root "AGENTS.md"))]
    (is (= agent-install/schema (:schema result)))
    (is (str/includes? content "`evidence.families`"))
    (is (str/includes? content "`answerability.planes`"))
    (is (str/includes? content "`architecture.summary` first"))
    (is (str/includes? content "bounded next-action samples"))
    (is (str/includes? content "detailed architecture rows are trimmed"))
    (is (str/includes? content "agraph audit-scope <project.edn> --map agraph.map.json --json"))
    (is (str/includes? content "Use it for architecture-class work"))
    (is (str/includes? content "If `status` or `answerability` is `limited`, `empty`, `stale`, or `unsynced`"))
    (is (str/includes? content "`nextActions` before concluding evidence does not exist"))
    (is (str/includes? content "`agraphCommandCount` as observed tool usage"))
    (is (str/includes? content "search/read/shell command reductions are the lower-is-better"))))

(deftest codex-broad-search-hook-prefers-status-and-explore
  (let [root (temp-dir "agraph-agent-hook")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true})
        hook-json (json/read-json (slurp (:hooks result)) :key-fn keyword)
        command (get-in hook-json [:hooks 0 :hooks 0 :command])]
    (is (str/includes? command "agraph status <project.edn> --json"))
    (is (str/includes? command "evidence.families"))
    (is (str/includes? command "agraph explore"))))
