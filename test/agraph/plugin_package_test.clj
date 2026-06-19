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
