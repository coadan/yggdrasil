(ns agraph.benchmark-test
  (:require [agraph.benchmark :as benchmark]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- sh!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed."
                      {:args args
                       :exit exit
                       :out out
                       :err err})))
    out))

(defn- git!
  [repo & args]
  (apply sh! "git" "-C" repo args))

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(defn- spit-json!
  [root path value]
  (spit-file! root path (json/write-json-str value {:indent-str "  "})))

(defn- commit!
  [repo message]
  (git! repo "add" ".")
  (git! repo "commit" "-m" message)
  (str/trim (git! repo "rev-parse" "HEAD")))

(deftest scores-file-localization
  (let [result {:groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                              :unsupportedGroundTruthFiles []}
                :agraph {:topFiles [{:path "src/other.clj" :rank 1}
                                    {:path "src/app.clj" :rank 2}
                                    {:path "src/more.clj" :rank 3}
                                    {:path "src/db.clj" :rank 4}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 0.5 (:meanReciprocalRankFile scores)))
    (is (= 0.5 (:noiseRatioAt20 scores)))
    (is (= 0 (:unsupportedGroundTruthFiles scores)))))

(deftest scores-only-base-visible-ground-truth-files
  (let [result {:groundTruth {:changedFiles [".chloggen/fix.yaml"
                                             "src/app.clj"
                                             "src/app_test.clj"]
                              :unsupportedGroundTruthFiles [{:path ".chloggen/fix.yaml"
                                                             :reason "missing-at-base"}]}
                :agraph {:topFiles [{:path "src/app.clj" :rank 1}
                                    {:path "src/app_test.clj" :rank 2}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 1.0 (:meanReciprocalRankFile scores)))
    (is (= 0.0 (:noiseRatioAt20 scores)))
    (is (= 3 (:changedFiles scores)))
    (is (= 2 (:scoreableChangedFiles scores)))
    (is (= 1 (:unsupportedGroundTruthFiles scores)))))

(deftest reports-agent-score-artifacts
  (let [out (temp-dir "agraph-agent-report")
        suite {:id "suite"
               :cases [{:id "case-1"}
                       {:id "case-2"}]}]
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-1.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "codex"
                         :mode "agraph"}
                 :scores {:fileRecallAt5 1.0
                          :fileRecallAt10 1.0
                          :fileRecallAt20 1.0
                          :meanReciprocalRankFile 1.0
                          :noiseRatioAt20 0.5
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (spit-json! out
                "suite/cases/case-1/agent-scores/run-2.score.json"
                {:schema benchmark/agent-score-schema
                 :suite-id "suite"
                 :case-id "case-1"
                 :repo-id "repo"
                 :agent {:agentId "baseline"
                         :mode "shell-only"}
                 :scores {:fileRecallAt5 0.0
                          :fileRecallAt10 0.5
                          :fileRecallAt20 0.5
                          :meanReciprocalRankFile 0.0
                          :noiseRatioAt20 1.0
                          :changedFiles 3
                          :scoreableChangedFiles 2
                          :unsupportedGroundTruthFiles 1}})
    (let [report (benchmark/report-agent-suite suite {:out out})]
      (is (= benchmark/agent-report-schema (:schema report)))
      (is (= 2 (:cases report)))
      (is (= 1 (:completed report)))
      (is (= 2 (:runs report)))
      (is (= ["case-2"] (:missing report)))
      (is (= 0.75 (get-in report [:scores :fileRecallAt10])))
      (is (= 6 (get-in report [:scores :changedFiles])))
      (is (= 4 (get-in report [:scores :scoreableChangedFiles])))
      (is (= #{"agraph" "shell-only"} (set (map :key (:byMode report)))))
      (is (= ["baseline" "codex"] (mapv :key (:byAgent report)))))
    (let [report (benchmark/report-agent-suite suite {:out out
                                                      :mode "agraph"})]
      (is (= 1 (:runs report)))
      (is (= 1.0 (get-in report [:scores :fileRecallAt10])))
      (is (= ["agraph"] (mapv :key (:byMode report)))))))

(deftest prepares-issue-replay-case-from-git-diff
  (let [root (temp-dir "agraph-bench-repo")
        out (temp-dir "agraph-bench-out")
        suite-dir (temp-dir "agraph-bench-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (spit-file! root "src/new.clj" "(ns new)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              prepared (first (:cases (benchmark/prepare-suite! suite {:out out})))]
          (is (= benchmark/prepared-case-schema (:schema prepared)))
          (is (= #{"src/app.clj" "src/new.clj"}
                 (set (get-in prepared [:groundTruth :changedFiles]))))
          (is (= [{:path "src/new.clj"
                   :reason "missing-at-base"}]
                 (get-in prepared [:groundTruth :unsupportedGroundTruthFiles])))
          (is (.isDirectory (io/file (:worktreeRoot prepared)))))))))

(deftest writes-agent-packet-without-ground-truth
  (let [root (temp-dir "agraph-bench-agent-repo")
        out (temp-dir "agraph-bench-agent-out")
        suite-dir (temp-dir "agraph-bench-agent-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "agraph@example.test")
    (git! root "config" "user.name" "AGraph Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              packet (first (:packets (benchmark/agent-packets! suite {:out out
                                                                       :case-id "case-1"})))
              project-path (get-in packet [:artifacts :projectConfig])
              packet-path (get-in packet [:artifacts :packetPath])
              project-config (read-string (slurp project-path))]
          (is (= benchmark/agent-packet-schema (:schema packet)))
          (is (= benchmark/agent-result-schema
                 (get-in packet [:task :expectedResultSchema])))
          (is (not (contains? packet :groundTruth)))
          (is (= (:project-id packet) (:id project-config)))
          (is (= (:worktreeRoot packet) (get-in project-config [:repos 0 :root])))
          (is (.isFile (io/file packet-path))))))))

(deftest scores-agent-localization-result
  (let [root (temp-dir "agraph-bench-agent-score")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :input {:title "broken app"}
                  :groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "agraph"
                      :suspectedFiles [{:path "src/other.clj"
                                        :rank 1
                                        :confidence 0.9}
                                       {:path "src/app.clj"
                                        :rank 2
                                        :confidence 0.8}
                                       {:path "src/db.clj"
                                        :rank 3
                                        :confidence 0.7}]}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= benchmark/agent-score-schema (:schema scored)))
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= 0.5 (get-in scored [:scores :meanReciprocalRankFile])))
    (is (= ["src/other.clj"] (get-in scored [:agent :missingPredictedFiles])))
    (is (= [{:path "src/app.clj"
             :rank 2
             :found? true}
            {:path "src/db.clj"
             :rank 3
             :found? true}]
           (get-in scored [:groundTruthRanks :files])))))
