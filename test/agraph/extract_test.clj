(ns agraph.extract-test
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def canonical-buckets
  #{:nodes :edges :chunks :diagnostics})

(deftest extract-file-returns-canonical-buckets
  (let [files [(fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/sample/core.clj")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/internal/cli/flows.go")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/web/app.ts")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/web/theme.scss")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/db/schema.sql")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/python/app.py")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/src/rust/lib.rs")
               (fs/file-record "test/fixtures/sample-repo"
                               "test/fixtures/sample-repo/docs/overview.md")
               {:file-id "file:config.yaml"
                :path "config.yaml"
                :kind :yaml
                :content ""}]]
    (doseq [result (map #(extract/extract-file "run/test" %) files)]
      (is (= canonical-buckets (set (keys result))))
      (is (every? vector? (vals result))))))

(deftest extracts-clojure-namespace-definitions-and-requires
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/sample/core.clj")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "sample.core"))
    (is (contains? labels "sample.core/greet"))
    (is (contains? labels "sample.core/helper"))
    (is (= 1 (:requires relations)))
    (is (<= 2 (get relations :defines 0)))))

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

(deftest extracts-python-modules-definitions-imports-and-diagnostics
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/python/app.py")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :python (:kind file)))
    (is (contains? labels "src.python.app"))
    (is (contains? labels "src.python.app/Service"))
    (is (contains? labels "src.python.app/Service.fetch"))
    (is (contains? labels "src.python.app/build"))
    (is (contains? labels "src.python.app/main"))
    (is (= 4 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.python.local")))
    (is (<= 5 (get relations :defines 0)))
    (is (= [:python-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result))))
  (let [root (doto (java.io.File/createTempFile "agraph-python-extract" "")
               (.delete)
               (.mkdirs)
               (.deleteOnExit))
        py (doto (io/file root "broken.py")
             (.deleteOnExit))]
    (spit py "def broken(:\n    pass\n")
    (let [result (extract/extract-file "run/test"
                                       (fs/file-record (.getPath root)
                                                       (.getPath py)))
          diagnostic (first (:diagnostics result))]
      (is (= :parse (:stage diagnostic)))
      (is (= [:python-file] (mapv :kind (:chunks result)))))))

(deftest extracts-typescript-modules-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/web/app.ts")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :typescript (:kind file)))
    (is (contains? labels "src.web.app"))
    (is (contains? labels "src.web.app/helper"))
    (is (contains? labels "src.web.app/Panel"))
    (is (contains? labels "src.web.app/loadPanel"))
    (is (contains? labels "src.web.app/route"))
    (is (= 5 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.types")))
    (is (contains? import-targets
                   (extract/node-id :namespace "react")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.theme")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.loader")))
    (is (contains? import-targets
                   (extract/node-id :namespace "src.web.data")))
    (is (= 4 (get relations :defines 0)))
    (is (= [:typescript-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))

(deftest extracts-style-and-sql-as-searchable-source-chunks
  (let [style-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/sample-repo"
                                      "test/fixtures/sample-repo/src/web/theme.scss"))
        sql-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/sample-repo"
                                    "test/fixtures/sample-repo/db/schema.sql"))]
    (is (= [:style-file] (mapv :kind (:chunks style-result))))
    (is (= [:sql-file] (mapv :kind (:chunks sql-result))))
    (is (empty? (:nodes style-result)))
    (is (empty? (:edges style-result)))
    (is (empty? (:nodes sql-result)))
    (is (empty? (:edges sql-result)))))
