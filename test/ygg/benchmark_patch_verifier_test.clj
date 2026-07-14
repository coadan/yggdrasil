(ns ygg.benchmark-patch-verifier-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ygg.benchmark-patch-verifier :as patch-verifier]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.benchmark-test-support :refer [commit!
                                                git!
                                                spit-file!
                                                temp-dir]]))

(deftest behavioral-verifier-must-reject-base-and-accept-fix
  (let [repo (temp-dir "ygg-patch-verifier-repo")
        suite-dir (temp-dir "ygg-patch-verifier-suite")
        suite-path (io/file suite-dir "benchmark.edn")]
    (git! repo "init")
    (git! repo "config" "user.email" "ygg@example.test")
    (git! repo "config" "user.name" "Yggdrasil Test")
    (spit-file! repo "app.txt" "old\n")
    (let [base-sha (commit! repo "base")]
      (spit-file! repo "app.txt" "fixed\n")
      (let [fix-sha (commit! repo "fix")]
        (spit suite-path
              (pr-str {:id "patch-verifier-fixture"
                       :repos [{:id "repo"
                                :root repo}]
                       :cases [{:id "discriminating"
                                :repo-id "repo"
                                :result-scope :patch
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :patch {:required? true
                                        :verifiers [{:id "behavior"
                                                     :command "grep -q fixed app.txt"
                                                     :visibility :hidden
                                                     :kind :behavioral}]}
                                :issue {:title "Fix app"}}
                               {:id "weak"
                                :repo-id "repo"
                                :result-scope :patch
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :patch {:required? true
                                        :verifiers [{:id "behavior"
                                                     :command "true"
                                                     :visibility :hidden
                                                     :kind :behavioral}]}
                                :issue {:title "Fix app"}}]}))
        (let [suite (benchmark-suite/read-suite suite-path)
              passing (patch-verifier/check-suite!
                       suite
                       {:case-id "discriminating"})
              failing (patch-verifier/check-suite! suite {:case-id "weak"})]
          (is (= patch-verifier/check-schema (:schema passing)))
          (is (= "passed" (:status passing)))
          (is (= {:cases 1
                  :checkedCases 1
                  :verifiers 1
                  :passed 1
                  :failed 0
                  :missingBehavioralCases 0}
                 (:counts passing)))
          (is (= "failed"
                 (get-in passing [:cases 0 :verifiers 0 :base :status])))
          (is (= "passed"
                 (get-in passing [:cases 0 :verifiers 0 :fix :status])))
          (is (= "failed" (:status failing)))
          (is (= "passed"
                 (get-in failing [:cases 0 :verifiers 0 :base :status])))
          (is (= ["behavioral verifier passed at the base revision"]
                 (get-in failing [:cases 0 :verifiers 0 :failures])))
          (is (= 1
                 (->> (git! repo "worktree" "list" "--porcelain")
                      str/split-lines
                      (filter #(str/starts-with? % "worktree "))
                      count))))))))
