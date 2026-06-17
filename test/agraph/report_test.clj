(ns agraph.report-test
  (:require [agraph.graph :as graph]
            [agraph.project :as project]
            [agraph.report :as report]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- fixture-project
  []
  {:id "fixture"
   :name "Fixture"
   :path "test/fixtures/project-repo/project.edn"
   :repos [{:id "app"
            :root (.getPath (io/file "test/fixtures/project-repo"))
            :role :application}]})

(deftest writes-report-bundle-from-project-fixture
  (let [xtdb-path (temp-dir "agraph-report-xtdb")
        out-dir (io/file (temp-dir "agraph-report-out") "bundle")
        project (fixture-project)]
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [result (report/bundle! xtdb
                                     project
                                     {:out (.getPath out-dir)
                                      :detail :expanded
                                      :generated-at-ms 12345})
              files (:files result)
              graph-json (json/read-json (slurp (:graph files)) :key-fn keyword)
              systems-json (json/read-json (slurp (:systems files)) :key-fn keyword)
              report-md (slurp (:report files))]
          (is (= report/schema (:schema result)))
          (is (= "fixture" (:project-id result)))
          (is (every? #(.exists (io/file %)) (vals files)))
          (is (= graph/schema (:schema graph-json)))
          (is (= graph/schema (:schema systems-json)))
          (is (= "agraph.context/v1"
                 (:schema (json/read-json (slurp (:context-example files))
                                          :key-fn keyword))))
          (is (str/includes? report-md "# AGraph Report"))
          (is (str/includes? report-md "## File Coverage"))
          (is (str/includes? report-md "agraph ask"))
          (is (str/includes? report-md "generated-at-ms: 12345"))
          (is (not (str/includes? report-md ":xt/id")))
          (is (not (str/includes? report-md "\"nodes\"")))
          (is (str/includes? (slurp (:index files)) "Systems: fixture")))))))

(deftest output-file-conflicts-unless-forced
  (let [root (temp-dir "agraph-report-conflict")
        target (io/file root "project.clj")]
    (spit target "(ns app)\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"existing file"
                          (report/prepare-output-dir! (.getPath target) false)))
    (is (= (.getPath target)
           (report/prepare-output-dir! (.getPath target) true)))
    (is (.isDirectory target))))
