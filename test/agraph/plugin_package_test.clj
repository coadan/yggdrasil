(ns agraph.plugin-package-test
  (:require [agraph.plugin-package :as plugin-package]
            [agraph.project :as project]
            [clojure.edn :as edn]
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

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(defn- git!
  [root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed"
                      {:args args
                       :out out
                       :err err
                       :exit exit})))
    (str/trim out)))

(defn- init-git-package!
  [root]
  (write-file! root
               "extract.py"
               "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
  (write-file! root
               "report.py"
               "import json, sys\njson.dump({'schema':'agraph.report-plugin.result/v1','panels':[]}, sys.stdout)\n")
  (write-file! root
               plugin-package/manifest-filename
               (pr-str
                {:schema plugin-package/manifest-schema
                 :id "sample-plugin-pack"
                 :name "Sample Plugin Pack"
                 :version "0.1.0"
                 :license {:spdx "MIT"}
                 :distribution {:visibility :public
                                :commercial? false}
                 :benchmark {:status :unbenchmarked}
                 :extractor-plugins
                 [{:id "sample-extractor"
                   :command ["python3" "extract.py"]
                   :applies-to {:file-kinds [:code]}
                   :emits [:sample-fact]}]
                 :report-plugins
                 [{:id "sample-report"
                   :command ["python3" "report.py"]
                   :slots [:plugins]}]}))
  (git! root "init" "--quiet")
  (git! root "add" ".")
  (git! root
        "-c"
        "user.name=AGraph Test"
        "-c"
        "user.email=agraph-test@example.test"
        "commit"
        "--quiet"
        "-m"
        "initial plugin package")
  root)

(deftest installs-git-plugin-package-and-project-loads-plugins
  (let [workspace (temp-dir "agraph-plugin-package")
        app-root (io/file workspace "app")
        package-root (init-git-package! (.getPath (io/file workspace "plugin-package")))
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/agraph/plugins/cache")]
    (.mkdirs app-root)
    (spit project-edn
          (pr-str {:id "plugin-fixture"
                   :repos [{:id "app"
                            :root (.getPath app-root)}]}))
    (let [install-result (plugin-package/install!
                          (.getPath project-edn)
                          package-root
                          {:cache-root (.getPath cache-root)})
          data (edn/read-string (slurp project-edn))
          entry (first (:plugin-packages data))
          listed (plugin-package/list-installed (.getPath project-edn))
          loaded (project/read-project (.getPath project-edn))
          extractor (first (:extractor-plugins loaded))
          report (first (:report-plugins loaded))]
      (is (= plugin-package/install-schema (:schema install-result)))
      (is (= "sample-plugin-pack" (get-in install-result [:package :id])))
      (is (= "plugin-fixture" (:project-id install-result)))
      (is (= :git (get-in entry [:source :type])))
      (is (= package-root (get-in entry [:source :url])))
      (is (not (str/blank? (get-in entry [:source :rev]))))
      (is (= "sample-plugin-pack" (get-in listed [:packages 0 :id])))
      (is (= 1 (get-in listed [:packages 0 :extractor-plugins])))
      (is (= 1 (get-in listed [:packages 0 :report-plugins])))
      (is (some #(str/includes? % "unbenchmarked")
                (get-in listed [:packages 0 :warnings])))
      (is (= "sample-extractor" (:id extractor)))
      (is (= :git-plugin (:authority extractor)))
      (is (= :unbenchmarked (:benchmark-status extractor)))
      (is (= "sample-plugin-pack" (:package-id extractor)))
      (is (= (get-in entry [:source :rev]) (:package-rev extractor)))
      (is (= (:path entry) (:cwd extractor)))
      (is (= "sample-report" (:id report)))
      (is (= :git-plugin (:authority report)))
      (is (= "sample-plugin-pack" (:package-id report)))
      (is (= (:path entry) (:cwd report))))))

(deftest scaffolds-valid-package-and-dry-runs-extractor
  (let [workspace (temp-dir "agraph-plugin-authoring")
        package-dir (io/file workspace "plugins" "demo")
        repo-root (io/file workspace "repo")
        src (io/file repo-root "src")]
    (.mkdirs src)
    (spit (io/file src "page.clj") "(ns page)\n(defn render [] :ok)\n")
    (let [created (plugin-package/new! (.getPath package-dir)
                                       {:id "demo-plugin"})
          validation (plugin-package/validate-local (.getPath package-dir))
          diagnosis (plugin-package/diagnose-local (.getPath package-dir))
          dry-run (plugin-package/dry-run-extractor
                   (.getPath package-dir)
                   (.getPath repo-root)
                   "src/page.clj"
                   {})]
      (is (= plugin-package/new-schema (:schema created)))
      (is (= "demo-plugin" (:package-id created)))
      (is (= true (:extractor? created)))
      (is (= true (:report? created)))
      (is (.exists (io/file package-dir plugin-package/manifest-filename)))
      (is (.exists (io/file package-dir "extract.py")))
      (is (.exists (io/file package-dir "report.py")))
      (is (= plugin-package/validate-schema (:schema validation)))
      (is (= :warning (:status validation)))
      (is (= 1 (count (:extractor-plugins validation))))
      (is (= 1 (count (:report-plugins validation))))
      (is (some #(str/includes? % "unbenchmarked") (:warnings validation)))
      (is (= plugin-package/diagnose-schema (:schema diagnosis)))
      (is (= :warning (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :local-use :status])))
      (is (= :private (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:project-local-scope :unbenchmarked}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= plugin-package/dry-run-schema (:schema dry-run)))
      (is (= :passed (:status dry-run)))
      (is (= "src/page.clj" (get-in dry-run [:file :path])))
      (is (pos? (get-in dry-run [:enhanced-counts :file-facts])))
      (is (pos? (get-in dry-run [:enhanced-counts :chunks])))
      (is (some #(= "demo-plugin-extractor" (:plugin-id %))
                (get-in dry-run [:rows :file-facts]))))))

(deftest diagnose-blocks-invalid-public-package-policy
  (let [workspace (temp-dir "agraph-plugin-diagnose")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"agraph.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "paid-plugin"
                   :version "0.1.0"
                   :license {:spdx "Proprietary"}
                   :distribution {:visibility :public
                                  :commercial? true}
                   :scope {:kind :base
                           :reason "Policy test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report}]}
                   :extractor-plugins
                   [{:id "paid-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :review-required (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:public-license-missing :public-commercial}
             (set (map :code (:diagnostics diagnosis))))))))

(deftest diagnose-requires-benchmark-artifacts-for-claims-and-core-promotion
  (let [workspace (temp-dir "agraph-plugin-benchmark-evidence")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "claimed-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark evidence test fixture."}
                   :benchmark {:status :benchmarked}
                   :extractor-plugins
                   [{:id "claimed-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= [:benchmark-artifacts-missing]
             (mapv :code (:diagnostics diagnosis)))))))

(deftest diagnose-accepts-existing-benchmark-artifacts-for-claims
  (let [workspace (temp-dir "agraph-plugin-benchmark-ready")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"agraph.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "benchmarked-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark evidence test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report
                                            :case-id "plugin-case"}]}
                   :extractor-plugins
                   [{:id "benchmarked-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :passed (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :claims :status])))
      (is (= :review-required (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= ["benchmarks/report.json"]
             (mapv :path (get-in diagnosis [:package :benchmark-artifacts])))))))

(deftest diagnose-keeps-project-local-plugins-external
  (let [workspace (temp-dir "agraph-plugin-project-local")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"agraph.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "local-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :project-local
                           :reason "Depends on one repository's conventions."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report}]}
                   :extractor-plugins
                   [{:id "local-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :warning (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= [:project-local-scope]
             (mapv :code (:diagnostics diagnosis)))))))
