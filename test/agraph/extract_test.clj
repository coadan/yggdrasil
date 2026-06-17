(ns agraph.extract-test
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [clojure.test :refer [deftest is]]))

(deftest extracts-clojure-namespace-definitions-requires-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/sample/core.clj")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "sample.core"))
    (is (contains? labels "sample.core/greet"))
    (is (contains? labels "sample.core/helper"))
    (is (= 1 (:requires relations)))
    (is (<= 2 (get relations :defines 0)))
    (is (pos? (get relations :calls 0)))))

(deftest extracts-markdown-heading-chunks
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/docs/overview.md")
        result (extract/extract-file "run/test" file)
        chunk (first (:chunks result))]
    (is (= ["Greeting Flow"] (mapv :label (:chunks result))))
    (is (= ["Greeting Flow"] (:heading-path chunk)))
    (is (= 1 (:source-line chunk)))
    (is (= 3 (:end-line chunk)))
    (is (string? (:content-sha chunk)))
    (is (empty? (:diagnostics result)))))

(deftest extracts-rust-modules-definitions-uses-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/rust/lib.rs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "src::rust::lib/Config"))
    (is (contains? labels "src::rust::lib/run"))
    (is (= 1 (get relations :declares-module 0)))
    (is (= 1 (get relations :uses 0)))
    (is (pos? (get relations :defines 0)))))

(deftest extracts-go-packages-definitions-imports-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/internal/cli/flows.go")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :go (:kind file)))
    (is (contains? labels "internal/cli/flows"))
    (is (contains? labels "internal/cli/flows/Client"))
    (is (contains? labels "internal/cli/flows/RunFlow"))
    (is (contains? labels "internal/cli/flows/Client.PublishFlow"))
    (is (= 2 (get relations :imports 0)))
    (is (<= 4 (get relations :defines 0)))
    (is (pos? (get relations :calls 0)))))
