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
              report-json (json/read-json (slurp (:report-data files)) :key-fn keyword)
              report-md (slurp (:report files))]
          (is (= report/schema (:schema result)))
          (is (= "fixture" (:project-id result)))
          (is (= "agraph.evidence/v1" (get-in result [:evidence :schema])))
          (is (every? #(.exists (io/file %)) (vals files)))
          (is (= graph/schema (:schema graph-json)))
          (is (= graph/schema (:schema systems-json)))
          (is (= report/schema (:schema report-json)))
          (is (= "fixture" (get-in report-json [:project :id])))
          (is (= "agraph.evidence/v1" (get-in report-json [:evidence :schema])))
          (is (= "graph.json" (get-in report-json [:graphs :overview :artifact])))
          (is (= "systems.json" (get-in report-json [:graphs :systems :artifact])))
          (is (seq (get-in report-json [:coverage :extractors])))
          (is (every? #(and (:kind %)
                            (:extractor-version %)
                            (number? (:files %)))
                      (get-in report-json [:coverage :extractors])))
          (is (= (get-in report-json [:maintenance :queue :decisions])
                 (get-in report-json [:maintenance :decision-summary :total])))
          (is (= "agraph.context/v1"
                 (:schema (json/read-json (slurp (:context-example files))
                                          :key-fn keyword))))
          (is (str/includes? report-md "# AGraph Report"))
          (is (str/includes? report-md "<EvidenceSurface"))
          (is (str/includes? report-md "## File Coverage"))
          (is (str/includes? report-md "agraph ask"))
          (is (str/includes? report-md "generated-at-ms: 12345"))
          (is (not (str/includes? report-md ":xt/id")))
          (is (not (str/includes? report-md "\"nodes\"")))
          (let [index-html (slurp (:index files))]
            (is (str/includes? index-html "id=\"root\""))
            (is (str/includes? index-html "__AGRAPH_BOOT__"))
            (is (str/includes? index-html "\"mode\":\"report\""))))))))

(deftest writes-shared-graph-viewer
  (let [out-dir (temp-dir "agraph-graph-viewer")
        html-file (io/file out-dir "systems.html")
        graph-data {:schema graph/schema
                    :title "Systems"
                    :nodes [{:id "system:app"
                             :label "App"
                             :kind "system"}]
                    :edges []}
        html-path (report/write-graph-viewer! (.getPath html-file) graph-data)
        graph-json (io/file out-dir "systems.graph.json")
        assets-dir (io/file out-dir "systems.assets")]
    (is (= (.getPath html-file) html-path))
    (is (.exists html-file))
    (is (.exists graph-json))
    (is (.isDirectory assets-dir))
    (is (.isDirectory (io/file assets-dir "assets")))
    (let [html (slurp html-file)
          exported (json/read-json (slurp graph-json) :key-fn keyword)]
      (is (str/includes? html "__AGRAPH_BOOT__"))
      (is (str/includes? html "\"mode\":\"graph\""))
      (is (str/includes? html "systems.assets/assets/"))
      (is (= graph/schema (:schema exported)))
      (is (= (:nodes graph-data) (:nodes exported))))))

(deftest missing-shared-viewer-assets-fail-clearly
  (with-redefs-fn {#'report/report-ui-source-dir (constantly nil)}
    (fn []
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing compiled report UI assets\. Run bb report-ui:build\."
           (report/write-graph-viewer! "/tmp/agraph-missing-assets.html"
                                       {:schema graph/schema
                                        :title "Missing Assets"
                                        :nodes []
                                        :edges []}))))))

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
