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

(deftest codex-guidance-points-agents-at-evidence-plane-readiness
  (let [root (temp-dir "agraph-agent-guidance")
        result (agent-install/install! "codex" {:root root
                                                :project? true})
        content (slurp (io/file root "AGENTS.md"))]
    (is (= agent-install/schema (:schema result)))
    (is (str/includes? content "`evidence.planes`"))
    (is (str/includes? content "`answerability.planes`"))
    (is (str/includes? content "If `status` or `answerability` is `limited`, `empty`, `stale`, or `unsynced`"))
    (is (str/includes? content "`nextActions` before concluding evidence does not exist"))))

(deftest codex-broad-search-hook-prefers-status-and-explore
  (let [root (temp-dir "agraph-agent-hook")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true})
        hook-json (json/read-json (slurp (:hooks result)) :key-fn keyword)
        command (get-in hook-json [:hooks 0 :hooks 0 :command])]
    (is (str/includes? command "agraph status <project.edn> --json"))
    (is (str/includes? command "evidence.planes"))
    (is (str/includes? command "agraph explore"))))
