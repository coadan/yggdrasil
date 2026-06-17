(ns agraph.benchmark-test
  (:require [agraph.benchmark :as benchmark]
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
