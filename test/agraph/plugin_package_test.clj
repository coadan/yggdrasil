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
          manifest-fingerprint (:manifest-fingerprint entry)
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
      (is (str/starts-with? manifest-fingerprint "sha256:"))
      (is (= "sample-plugin-pack" (get-in listed [:packages 0 :id])))
      (is (= manifest-fingerprint (get-in install-result [:package :manifest-fingerprint])))
      (is (= manifest-fingerprint (get-in listed [:packages 0 :manifest-fingerprint])))
      (is (= manifest-fingerprint
             (get-in listed [:packages 0 :expected-manifest-fingerprint])))
      (is (= "sample-plugin-pack"
             (get-in listed [:packages 0 :expected-package-id])))
      (is (= 1 (get-in listed [:packages 0 :extractor-plugins])))
      (is (= 1 (get-in listed [:packages 0 :report-plugins])))
      (is (some #(str/includes? % "unbenchmarked")
                (get-in listed [:packages 0 :warnings])))
      (is (= "sample-extractor" (:id extractor)))
      (is (= :git-plugin (:authority extractor)))
      (is (= :unbenchmarked (:benchmark-status extractor)))
      (is (= "sample-plugin-pack" (:package-id extractor)))
      (is (= (get-in entry [:source :rev]) (:package-rev extractor)))
      (is (= manifest-fingerprint (:package-manifest-fingerprint extractor)))
      (is (= (:path entry) (:cwd extractor)))
      (is (= "sample-report" (:id report)))
      (is (= :git-plugin (:authority report)))
      (is (= "sample-plugin-pack" (:package-id report)))
      (is (= manifest-fingerprint (:package-manifest-fingerprint report)))
      (is (= (:path entry) (:cwd report)))
      (spit project-edn
            (pr-str (assoc data
                           :plugin-packages
                           [(assoc entry :manifest-fingerprint "sha256:stale")])))
      (is (some #(str/includes? % "manifest fingerprint")
                (get-in (plugin-package/list-installed (.getPath project-edn))
                        [:packages 0 :warnings])))
      (spit project-edn
            (pr-str (assoc data
                           :plugin-packages
                           [(assoc entry :id "different-plugin")])))
      (let [mismatched (plugin-package/list-installed (.getPath project-edn))]
        (is (= "different-plugin"
               (get-in mismatched [:packages 0 :expected-package-id])))
        (is (= #{:package-id-mismatch :project-local-scope :unbenchmarked}
               (set (map :code (get-in mismatched [:packages 0 :diagnostics])))))
        (is (= {:total 3
                :errors 1
                :warnings 2}
               (get-in mismatched [:packages 0 :diagnostic-counts])))
        (is (some #(str/includes? % "package id")
                  (get-in mismatched [:packages 0 :warnings])))))))

(deftest removes-installed-plugin-package-entry
  (let [workspace (temp-dir "agraph-plugin-remove")
        project-edn (io/file workspace "project.edn")]
    (spit project-edn
          (pr-str {:id "plugin-remove-fixture"
                   :repos []
                   :plugin-packages [{:id "keep-plugin"
                                      :path "/tmp/keep"
                                      :manifest plugin-package/manifest-filename}
                                     {:id "remove-plugin"
                                      :path "/tmp/remove"
                                      :manifest plugin-package/manifest-filename}]}))
    (let [result (plugin-package/remove! (.getPath project-edn) "remove-plugin")
          data (edn/read-string (slurp project-edn))]
      (is (= plugin-package/remove-schema (:schema result)))
      (is (= "plugin-remove-fixture" (:project-id result)))
      (is (= "remove-plugin" (:package-id result)))
      (is (= "/tmp/remove" (get-in result [:removed-entry :path])))
      (is (= 1 (:remaining result)))
      (is (= ["keep-plugin"] (mapv :id (:plugin-packages data))))))
  (let [workspace (temp-dir "agraph-plugin-remove-missing")
        project-edn (io/file workspace "project.edn")]
    (spit project-edn
          (pr-str {:id "plugin-remove-missing"
                   :plugin-packages []}))
    (try
      (plugin-package/remove! (.getPath project-edn) "missing-plugin")
      (is false "Expected remove! to reject missing package id.")
      (catch clojure.lang.ExceptionInfo e
        (is (= {:plugin-package-id "missing-plugin"
                :installed []}
               (ex-data e)))))))

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
                   {})
          report-dry-run (plugin-package/dry-run-report
                          (.getPath package-dir)
                          {})
          claim-authority {:status :non-authoritative
                           :public-claims? false
                           :review-required? false
                           :blockers [{:code :project-local
                                       :message "Project-local package output stays external and cannot support public claims."}
                                      {:code :unbenchmarked
                                       :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}
          manifest-fingerprint (get-in validation [:package :manifest-fingerprint])]
      (is (= plugin-package/new-schema (:schema created)))
      (is (= "demo-plugin" (:package-id created)))
      (is (= true (:extractor? created)))
      (is (= true (:report? created)))
      (is (.exists (io/file package-dir plugin-package/manifest-filename)))
      (is (.exists (io/file package-dir "registry.example.edn")))
      (is (.exists (io/file package-dir "benchmarks" "README.md")))
      (is (.exists (io/file package-dir "extract.py")))
      (is (.exists (io/file package-dir "report.py")))
      (is (some #(str/ends-with? % "registry.example.edn") (:files created)))
      (is (some #(str/ends-with? % "benchmarks/README.md") (:files created)))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "`registry.example.edn` is a sharing template"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "Unsupported file families"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         ":benchmark"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         "benchmarks/demo-plugin-agent-report.json"))
      (is (= {:schema plugin-package/registry-schema
              :id "local-plugin-registry"
              :packages [{:id "demo-plugin"
                          :path "."
                          :source "https://github.com/ORG/demo-plugin.git"
                          :ref "v0.1.0"}]}
             (edn/read-string (slurp (io/file package-dir "registry.example.edn")))))
      (is (= plugin-package/validate-schema (:schema validation)))
      (is (str/starts-with? manifest-fingerprint "sha256:"))
      (is (= :warning (:status validation)))
      (is (= 1 (count (:extractor-plugins validation))))
      (is (= 1 (count (:report-plugins validation))))
      (is (some #(str/includes? % "unbenchmarked") (:warnings validation)))
      (is (= #{:project-local-scope :unbenchmarked}
             (set (map :code (get-in validation [:package :diagnostics])))))
      (is (= {:total 2
              :errors 0
              :warnings 2}
             (get-in validation [:package :diagnostic-counts])))
      (is (= plugin-package/diagnose-schema (:schema diagnosis)))
      (is (= :warning (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :local-use :status])))
      (is (= :private (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:project-local-scope :unbenchmarked}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= plugin-package/dry-run-schema (:schema dry-run)))
      (is (= :passed (:status dry-run)))
      (is (= :unbenchmarked (get-in dry-run [:package :benchmark-status])))
      (is (= claim-authority (get-in dry-run [:package :claim-authority])))
      (is (= :project-local (get-in dry-run [:package :scope :kind])))
      (is (= manifest-fingerprint (get-in dry-run [:package :manifest-fingerprint])))
      (is (some #(str/includes? % "unbenchmarked")
                (get-in dry-run [:package :warnings])))
      (is (= #{:project-local-scope :unbenchmarked}
             (set (map :code (get-in dry-run [:package :diagnostics])))))
      (is (= {:total 2
              :errors 0
              :warnings 2}
             (get-in dry-run [:package :diagnostic-counts])))
      (is (= "src/page.clj" (get-in dry-run [:file :path])))
      (is (pos? (get-in dry-run [:enhanced-counts :file-facts])))
      (is (pos? (get-in dry-run [:enhanced-counts :chunks])))
      (is (some #(= "demo-plugin-extractor" (:plugin-id %))
                (get-in dry-run [:rows :file-facts])))
      (is (= manifest-fingerprint
             (get-in dry-run [:plugins 0 :package-manifest-fingerprint])))
      (is (= claim-authority
             (get-in dry-run [:plugins 0 :package-claim-authority])))
      (is (some #(= manifest-fingerprint (:plugin-package-manifest-fingerprint %))
                (get-in dry-run [:rows :file-facts])))
      (is (some #(= claim-authority (:plugin-package-claim-authority %))
                (get-in dry-run [:rows :file-facts])))
      (is (= plugin-package/dry-run-schema (:schema report-dry-run)))
      (is (= :report (:kind report-dry-run)))
      (is (= :passed (:status report-dry-run)))
      (is (= :unbenchmarked (get-in report-dry-run [:package :benchmark-status])))
      (is (= claim-authority (get-in report-dry-run [:package :claim-authority])))
      (is (= :project-local (get-in report-dry-run [:package :scope :kind])))
      (is (= manifest-fingerprint
             (get-in report-dry-run [:package :manifest-fingerprint])))
      (is (some #(str/includes? % "unbenchmarked")
                (get-in report-dry-run [:package :warnings])))
      (is (= #{:project-local-scope :unbenchmarked}
             (set (map :code (get-in report-dry-run [:package :diagnostics])))))
      (is (= {:total 2
              :errors 0
              :warnings 2}
             (get-in report-dry-run [:package :diagnostic-counts])))
      (is (= 1 (get-in report-dry-run [:counts :panels])))
      (is (= "demo-plugin-report" (get-in report-dry-run [:plugins 0 :id])))
      (is (= :unbenchmarked (get-in report-dry-run [:plugins 0 :benchmark-status])))
      (is (= claim-authority
             (get-in report-dry-run [:plugins 0 :package-claim-authority])))
      (is (= manifest-fingerprint
             (get-in report-dry-run [:plugins 0 :package-manifest-fingerprint])))
      (is (= "unbenchmarked"
             (get-in report-dry-run
                     [:outputs 0 :output :panels 0 :plugin :benchmarkStatus])))
      (is (= manifest-fingerprint
             (get-in report-dry-run
                     [:outputs 0 :output :panels 0 :plugin :packageManifestFingerprint])))
      (is (= claim-authority
             (get-in report-dry-run
                     [:outputs 0 :output :panels 0 :plugin :packageClaimAuthority]))))))

(deftest scaffolds-unsupported-file-family-extractor-options
  (let [workspace (temp-dir "agraph-plugin-unsupported-file-family")
        package-dir (io/file workspace "plugins" "htmx")
        repo-root (io/file workspace "repo")
        template-dir (io/file repo-root "templates")
        created (plugin-package/new! (.getPath package-dir)
                                     {:id "htmx-plugin"
                                      :extractor? true
                                      :file-kind "htmx"
                                      :path-globs "templates/*.html,resources/**/*.html"
                                      :scan-globs "templates/*.html"
                                      :fixture-path "fixtures/sample.html"})
        manifest (edn/read-string
                  (slurp (io/file package-dir plugin-package/manifest-filename)))
        validation (plugin-package/validate-local (.getPath package-dir))
        extractor (first (:extractor-plugins validation))]
    (.mkdirs template-dir)
    (spit (io/file template-dir "page.html") "<button hx-post=\"/save\">Save</button>\n")
    (is (= :htmx (:file-kind created)))
    (is (= ["templates/*.html" "resources/**/*.html"] (:path-globs created)))
    (is (= ["templates/*.html"] (:scan-globs created)))
    (is (= "fixtures/sample.html" (:fixture-path created)))
    (is (.exists (io/file package-dir "fixtures" "sample.html")))
    (is (str/includes? (slurp (io/file package-dir "fixtures" "sample.html"))
                       "Sample htmx fixture"))
    (is (= [:htmx]
           (get-in manifest [:extractor-plugins 0 :applies-to :file-kinds])))
    (is (= ["templates/*.html" "resources/**/*.html"]
           (get-in manifest [:extractor-plugins 0 :applies-to :path-globs])))
    (is (= {:path-globs ["templates/*.html"]
            :file-kind :htmx}
           (get-in manifest [:extractor-plugins 0 :scan])))
    (is (= :warning (:status validation)))
    (is (= #{:enhance :scan} (:modes extractor)))
    (is (= :htmx (get-in extractor [:scan :file-kind])))
    (let [dry-run (plugin-package/dry-run-extractor
                   (.getPath package-dir)
                   (.getPath repo-root)
                   "templates/page.html"
                   {})]
      (is (= :passed (:status dry-run)))
      (is (= :htmx (get-in dry-run [:file :kind])))
      (is (= true (get-in dry-run [:file :plugin-scanned?])))
      (is (= ["htmx-plugin-extractor"] (get-in dry-run [:file :plugin-ids])))
      (is (pos? (get-in dry-run [:enhanced-counts :file-facts])))
      (is (pos? (get-in dry-run [:enhanced-counts :chunks]))))))

(deftest dry-run-fails-when-package-has-no-plugin-for-selected-lane
  (let [workspace (temp-dir "agraph-plugin-empty-lane")
        report-only-dir (io/file workspace "plugins" "report-only")
        extractor-only-dir (io/file workspace "plugins" "extractor-only")
        repo-root (io/file workspace "repo")
        src (io/file repo-root "src")]
    (.mkdirs src)
    (spit (io/file src "page.clj") "(ns page)\n(defn render [] :ok)\n")
    (plugin-package/new! (.getPath report-only-dir)
                         {:id "report-only"
                          :report? true})
    (plugin-package/new! (.getPath extractor-only-dir)
                         {:id "extractor-only"
                          :extractor? true})
    (let [extractor-result (plugin-package/dry-run-extractor
                            (.getPath report-only-dir)
                            (.getPath repo-root)
                            "src/missing.clj"
                            {})
          report-result (plugin-package/dry-run-report
                         (.getPath extractor-only-dir)
                         {})]
      (is (= :failed (:status extractor-result)))
      (is (= [] (:plugins extractor-result)))
      (is (= "src/missing.clj" (get-in extractor-result [:file :path])))
      (is (= [:no-extractor-plugins-selected]
             (mapv :code (:diagnostics extractor-result))))
      (is (= [:no-extractor-plugins-selected]
             (mapv :code (get-in extractor-result [:rows :diagnostics]))))
      (is (= :failed (:status report-result)))
      (is (= [] (:plugins report-result)))
      (is (= {:panels 0
              :diagnostics 1
              :artifacts 0}
             (:counts report-result)))
      (is (= [:no-report-plugins-selected]
             (mapv :code (:diagnostics report-result)))))))

(deftest validate-rejects-unsupported-package-benchmark-status
  (let [workspace (temp-dir "agraph-plugin-benchmark-status")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "bad-benchmark-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :private
                                  :commercial? false}
                   :scope {:kind :project-local
                           :reason "Status validation fixture."}
                   :benchmark {:status :claimed}
                   :extractor-plugins
                   [{:id "bad-benchmark-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [validation (plugin-package/validate-local (.getPath package-dir))]
      (is (= :failed (:status validation)))
      (is (= ["Unknown plugin package benchmark status."] (:errors validation)))
      (is (= {:benchmark-status :claimed
              :supported [:benchmarked :unbenchmarked]}
             (:data validation))))))

(deftest validate-rejects-duplicate-plugin-ids-within-package-lanes
  (let [workspace (temp-dir "agraph-plugin-duplicate-ids")
        package-dir (io/file workspace "plugin")
        repo-root (io/file workspace "repo")
        src (io/file repo-root "src")
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/agraph/plugins/cache")]
    (.mkdirs package-dir)
    (.mkdirs src)
    (spit (io/file src "page.clj") "(ns page)\n(defn render [] :ok)\n")
    (spit project-edn
          (pr-str {:id "duplicate-plugin-install"
                   :repos [{:id "repo"
                            :root (.getPath repo-root)}]}))
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "duplicate-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :private
                                  :commercial? false}
                   :scope {:kind :project-local
                           :reason "Duplicate id validation fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "dup-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}
                    {:id "dup-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]
                   :report-plugins
                   [{:id "dup-report"
                     :command ["python3" "report.py"]
                     :slots [:plugins]}
                    {:id "dup-report"
                     :command ["python3" "report.py"]
                     :slots [:plugins]}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath package-dir)
                 "report.py"
                 "import json, sys\njson.dump({'schema':'agraph.report-plugin.result/v1','panels':[]}, sys.stdout)\n")
    (git! (.getPath package-dir) "init" "--quiet")
    (git! (.getPath package-dir) "add" ".")
    (git! (.getPath package-dir)
          "-c"
          "user.name=AGraph Test"
          "-c"
          "user.email=agraph-test@example.test"
          "commit"
          "--quiet"
          "-m"
          "duplicate plugin package")
    (let [validation (plugin-package/validate-local (.getPath package-dir))
          extractor-dry-run (plugin-package/dry-run-extractor
                             (.getPath package-dir)
                             (.getPath repo-root)
                             "src/page.clj"
                             {})
          report-dry-run (plugin-package/dry-run-report
                          (.getPath package-dir)
                          {})]
      (is (= :failed (:status validation)))
      (is (= #{"duplicate-plugin declares duplicate extractor plugin id: dup-extractor"
               "duplicate-plugin declares duplicate report plugin id: dup-report"}
             (set (:errors validation))))
      (is (= #{:duplicate-extractor-plugin-id
               :duplicate-report-plugin-id
               :project-local-scope
               :unbenchmarked}
             (set (map :code (get-in validation [:package :diagnostics])))))
      (is (= {:total 4
              :errors 2
              :warnings 2}
             (get-in validation [:package :diagnostic-counts])))
      (is (= :failed (:status extractor-dry-run)))
      (is (= #{:duplicate-extractor-plugin-id :duplicate-report-plugin-id}
             (set (map :code (:diagnostics extractor-dry-run)))))
      (is (= :failed (:status report-dry-run)))
      (is (= #{:duplicate-extractor-plugin-id :duplicate-report-plugin-id}
             (set (map :code (:diagnostics report-dry-run)))))
      (try
        (plugin-package/install!
         (.getPath project-edn)
         (.getPath package-dir)
         {:cache-root (.getPath cache-root)})
        (is false "Expected install to reject package local-use errors.")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Plugin package local-use validation failed." (ex-message e)))
          (is (= #{:duplicate-extractor-plugin-id :duplicate-report-plugin-id}
                 (set (map :code (:diagnostics (ex-data e))))))))
      (is (nil? (:plugin-packages (edn/read-string (slurp project-edn))))))))

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
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:public-license-missing
               :public-commercial
               :core-fixtures-missing
               :core-tests-missing}
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
      (is (= :non-authoritative
             (get-in diagnosis [:package :claim-authority :status])))
      (is (= [:benchmark-artifacts-missing]
             (mapv :code
                   (get-in diagnosis [:package :claim-authority :blockers]))))
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
      (is (= :warning (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:core-fixtures-missing :core-tests-missing}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= ["benchmarks/report.json"]
             (mapv :path (get-in diagnosis [:package :benchmark-artifacts])))))))

(deftest diagnose-requires-core-promotion-fixtures-and-tests
  (let [workspace (temp-dir "agraph-plugin-core-ready")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"agraph.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
    (write-file! (.getPath package-dir)
                 "fixtures/sample.clj"
                 "(ns sample)\n")
    (write-file! (.getPath package-dir)
                 "test/sample_test.clj"
                 "(ns sample-test)\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "core-ready-plugin"
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
                   :core-promotion {:fixtures [{:path "fixtures/sample.clj"
                                                :kind :fixture}]
                                    :tests [{:path "test/sample_test.clj"
                                             :kind :test}]}
                   :extractor-plugins
                   [{:id "core-ready-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :passed (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :claims :status])))
      (is (= :review-required (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= ["fixtures/sample.clj"]
             (mapv :path (get-in diagnosis [:package :core-promotion :fixtures]))))
      (is (= ["test/sample_test.clj"]
             (mapv :path (get-in diagnosis [:package :core-promotion :tests])))))))

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

(deftest registry-validation-enforces-public-sharing-readiness
  (let [workspace (temp-dir "agraph-plugin-registry")
        registry-path (io/file workspace "registry.edn")
        base-dir (io/file workspace "base")
        local-dir (io/file workspace "local")
        missing-source-dir (io/file workspace "missing-source")]
    (.mkdirs base-dir)
    (.mkdirs local-dir)
    (write-file! (.getPath base-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath base-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "base-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Reusable fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "base-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath local-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath missing-source-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath local-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "local-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :project-local
                           :reason "One project only."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "local-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath missing-source-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "missing-source-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Reusable fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "missing-source-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (spit registry-path
          (pr-str {:schema plugin-package/registry-schema
                   :id "official"
                   :packages [{:id "base-plugin"
                               :path "base"
                               :source "https://github.com/org/agraph-plugins.git"
                               :ref "v0.1.0"
                               :subdir "packages/base-plugin"}
                              {:id "local-plugin"
                               :path "local"}
                              {:id "missing-source-plugin"
                               :path "missing-source"}]}))
    (let [result (plugin-package/validate-registry (.getPath registry-path))
          by-id (into {} (map (juxt :id identity) (:packages result)))]
      (is (= plugin-package/registry-validate-schema (:schema result)))
      (is (= :failed (:status result)))
      (is (= {:packages 3
              :passed 1
              :failed 2
              :claim-ready 0
              :non-authoritative 3}
             (:counts result)))
      (is (= {:registry-source-missing 2
              :public-sharing-not-ready 1}
             (:error-counts result)))
      (is (= :passed (get-in by-id ["base-plugin" :status])))
      (is (= {:type :git
              :url "https://github.com/org/agraph-plugins.git"
              :ref "v0.1.0"
              :subdir "packages/base-plugin"}
             (get-in by-id ["base-plugin" :install :source])))
      (is (= ["plugin" "install" "<project.edn>" "https://github.com/org/agraph-plugins.git"
              "--ref" "v0.1.0" "--subdir" "packages/base-plugin"]
             (get-in by-id ["base-plugin" :install :args])))
      (is (= "bb plugin install '<project.edn>' https://github.com/org/agraph-plugins.git --ref v0.1.0 --subdir packages/base-plugin"
             (get-in by-id ["base-plugin" :install :command])))
      (is (= :failed (get-in by-id ["local-plugin" :status])))
      (is (= [:registry-source-missing :public-sharing-not-ready]
             (mapv :code (get-in by-id ["local-plugin" :errors]))))
      (is (= :failed (get-in by-id ["missing-source-plugin" :status])))
      (is (= [:registry-source-missing]
             (mapv :code (get-in by-id ["missing-source-plugin" :errors])))))))

(deftest registry-validation-rejects-duplicate-package-ids
  (let [workspace (temp-dir "agraph-plugin-registry-duplicate-id")
        registry-path (io/file workspace "registry.edn")
        package-dir (io/file workspace "package")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'agraph.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "base-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Reusable fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "base-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (spit registry-path
          (pr-str {:schema plugin-package/registry-schema
                   :id "official"
                   :packages [{:id "base-plugin"
                               :path "package"
                               :source "https://github.com/org/agraph-plugins.git"
                               :subdir "packages/base-plugin"}
                              {:id "base-plugin"
                               :path "package"
                               :source "https://github.com/org/agraph-plugins.git"
                               :subdir "packages/base-plugin-copy"}]}))
    (let [result (plugin-package/validate-registry (.getPath registry-path))]
      (is (= :failed (:status result)))
      (is (= {:packages 2
              :passed 0
              :failed 2
              :claim-ready 0
              :non-authoritative 2}
             (:counts result)))
      (is (= {:registry-duplicate-package-id 2}
             (:error-counts result)))
      (is (= [[:registry-duplicate-package-id]
              [:registry-duplicate-package-id]]
             (mapv #(mapv :code (:errors %)) (:packages result)))))))
