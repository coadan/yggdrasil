(ns ygg.plugin-package-test
  (:require [ygg.plugin-package :as plugin-package]
            [ygg.project :as project]
            [ygg.xtdb :as store]
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

(defn- canonical-plugin-manifest
  [manifest]
  (let [extractors (mapv #(assoc % :kind :extractor)
                         (:extractor-plugins manifest))
        reports (mapv #(assoc % :kind :report)
                      (:report-plugins manifest))]
    (cond-> (dissoc manifest :extractor-plugins :report-plugins)
      (or (seq extractors) (seq reports))
      (assoc :plugins (vec (concat extractors reports))))))

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file
          (if (= path plugin-package/manifest-filename)
            (pr-str (canonical-plugin-manifest (edn/read-string content)))
            content))
    (.getPath file)))

(def benchmark-improvement-fixture
  {:metric :file-recall-at-5
   :baseline :core-ygg
   :candidate :plugin-enhanced-ygg
   :delta "+0.25"
   :effect 0.25})

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
               "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
  (write-file! root
               "report.py"
               "import json, sys\njson.dump({'schema':'ygg.report-plugin.result/v1','panels':[]}, sys.stdout)\n")
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
        "user.name=Yggdrasil Test"
        "-c"
        "user.email=ygg-test@example.test"
        "commit"
        "--quiet"
        "-m"
        "initial plugin package")
  root)

(deftest rejects-legacy-project-plugin-keys
  (let [workspace (temp-dir "ygg-plugin-legacy-project")
        app-root (io/file workspace "app")
        project-edn (io/file workspace "project.edn")]
    (.mkdirs app-root)
    (spit project-edn
          (pr-str {:id "legacy-plugin-config"
                   :repos [{:id "app"
                            :root (.getPath app-root)}]
                   :plugin-packages []}))
    (try
      (project/read-project (.getPath project-edn))
      (is false "Expected legacy project plugin key rejection.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Project config uses legacy plugin keys. Use canonical :plugins entries with :kind instead."
               (ex-message e)))
        (is (= {:legacy-keys [:plugin-packages]
                :replacement :plugins}
               (ex-data e)))))))

(deftest validate-local-rejects-legacy-package-plugin-keys
  (let [package-dir (io/file (temp-dir "ygg-plugin-legacy-package"))]
    (spit (io/file package-dir plugin-package/manifest-filename)
          (pr-str {:schema plugin-package/manifest-schema
                   :id "legacy-package"
                   :version "0.1.0"
                   :extractor-plugins []}))
    (let [validation (plugin-package/validate-local (.getPath package-dir))]
      (is (= :failed (:status validation)))
      (is (= ["Plugin package manifest uses legacy plugin keys. Use canonical :plugins entries with :kind instead."]
             (:errors validation)))
      (is (= {:legacy-keys [:extractor-plugins]
              :replacement :plugins}
             (:data validation))))))

(deftest validate-local-rejects-package-kind-in-package-manifests
  (let [package-dir (io/file (temp-dir "ygg-plugin-nested-package-kind"))]
    (spit (io/file package-dir plugin-package/manifest-filename)
          (pr-str {:schema plugin-package/manifest-schema
                   :id "nested-package"
                   :version "0.1.0"
                   :plugins [{:kind :package
                              :id "other-package"}]}))
    (let [validation (plugin-package/validate-local (.getPath package-dir))]
      (is (= :failed (:status validation)))
      (is (= ["Plugin package manifest plugin entry declares unsupported :kind."]
             (:errors validation)))
      (is (= :package (get-in validation [:data :kind])))
      (is (= #{:extractor :report}
             (set (get-in validation [:data :supported])))))))

(deftest installs-git-plugin-package-and-project-loads-plugins
  (let [workspace (temp-dir "ygg-plugin-package")
        app-root (io/file workspace "app")
        package-root (init-git-package! (.getPath (io/file workspace "plugin-package")))
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/ygg/plugins/cache")]
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
          entry (first (:plugins data))
          manifest-fingerprint (:manifest-fingerprint entry)
          listed (plugin-package/list-installed (.getPath project-edn))
          filtered (plugin-package/list-installed (.getPath project-edn)
                                                  {:kind "extractor"
                                                   :query "sample"})
          missing-filter (plugin-package/list-installed (.getPath project-edn)
                                                        {:kind "report"
                                                         :query "missing"})
          loaded (project/read-project (.getPath project-edn))
          extractor (first (project/extractors loaded))
          report (first (project/reports loaded))]
      (is (= plugin-package/install-schema (:schema install-result)))
      (is (= "sample-plugin-pack" (get-in install-result [:package :id])))
      (is (= "plugin-fixture" (:project-id install-result)))
      (is (= :package (:kind entry)))
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
      (is (= {:packages 1
              :matched 1
              :extractor 1
              :report 1}
             (:counts listed)))
      (is (= {:kind "extractor"
              :query "sample"}
             (:filters filtered)))
      (is (= ["sample-plugin-pack"] (mapv :id (:packages filtered))))
      (is (= 0 (get-in missing-filter [:counts :matched])))
      (is (empty? (:packages missing-filter)))
      (is (= [:search-registry :scaffold-report :author-report-gap]
             (mapv :id (:next-actions missing-filter))))
      (is (= "bb plugin registry list '<registry.edn>' --kind report --query missing"
             (get-in missing-filter [:next-actions 0 :command])))
      (is (= "bb plugin new '<package-dir>' --report"
             (get-in missing-filter [:next-actions 1 :command])))
      (is (= "bb plugin gap report '<package-dir>' --json"
             (get-in missing-filter [:next-actions 2 :command])))
      (is (not (contains? loaded :plugin-packages)))
      (is (not (contains? loaded :extractor-plugins)))
      (is (not (contains? loaded :report-plugins)))
      (is (= "sample-plugin-pack"
             (get-in loaded [:plugins :packages 0 :id])))
      (is (= "sample-extractor"
             (get-in loaded [:plugins :extractors 0 :id])))
      (is (= "sample-report"
             (get-in loaded [:plugins :reports 0 :id])))
      (is (= {:total 2
              :extractor 1
              :report 1}
             (get-in listed [:packages 0 :plugins])))
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
                           :plugins
                           [(assoc entry :manifest-fingerprint "sha256:stale")])))
      (is (some #(str/includes? % "manifest fingerprint")
                (get-in (plugin-package/list-installed (.getPath project-edn))
                        [:packages 0 :warnings])))
      (spit project-edn
            (pr-str (assoc data
                           :plugins
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

(deftest updates-installed-git-plugin-package-from-pinned-ref
  (let [workspace (temp-dir "ygg-plugin-update")
        app-root (io/file workspace "app")
        package-root (init-git-package! (.getPath (io/file workspace "plugin-package")))
        branch (git! package-root "rev-parse" "--abbrev-ref" "HEAD")
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/ygg/plugins/cache")]
    (.mkdirs app-root)
    (spit project-edn
          (pr-str {:id "plugin-update-fixture"
                   :repos [{:id "app"
                            :root (.getPath app-root)}]}))
    (let [installed (plugin-package/install!
                     (.getPath project-edn)
                     package-root
                     {:ref branch
                      :cache-root (.getPath cache-root)})
          previous-entry (:entry installed)
          previous-rev (get-in previous-entry [:source :rev])
          previous-fingerprint (:manifest-fingerprint previous-entry)]
      (write-file! package-root
                   plugin-package/manifest-filename
                   (pr-str
                    {:schema plugin-package/manifest-schema
                     :id "sample-plugin-pack"
                     :name "Sample Plugin Pack"
                     :version "0.2.0"
                     :license {:spdx "MIT"}
                     :distribution {:visibility :public
                                    :commercial? false}
                     :benchmark {:status :unbenchmarked}
                     :extractor-plugins
                     [{:id "sample-extractor"
                       :command ["python3" "extract.py"]
                       :applies-to {:file-kinds [:code]}}]
                     :report-plugins
                     [{:id "sample-report"
                       :command ["python3" "report.py"]
                       :slots [:plugins]}]}))
      (git! package-root "add" ".")
      (git! package-root
            "-c"
            "user.name=Yggdrasil Test"
            "-c"
            "user.email=ygg-test@example.test"
            "commit"
            "--quiet"
            "-m"
            "update plugin package")
      (let [updated (plugin-package/update!
                     (.getPath project-edn)
                     "sample-plugin-pack"
                     {:cache-root (.getPath cache-root)})
            data (edn/read-string (slurp project-edn))
            entry (first (:plugins data))]
        (is (= plugin-package/update-schema (:schema updated)))
        (is (= "sample-plugin-pack" (:package-id updated)))
        (is (= previous-entry (:previous-entry updated)))
        (is (= branch (:update-ref updated)))
        (is (= true (:refresh? updated)))
        (is (= "0.2.0" (get-in updated [:package :version])))
        (is (= :package (:kind entry)))
        (is (not= previous-rev (get-in updated [:entry :source :rev])))
        (is (not= previous-fingerprint
                  (get-in updated [:entry :manifest-fingerprint])))
        (is (= (get-in updated [:entry :source :rev])
               (get-in entry [:source :rev])))
        (is (= (get-in updated [:entry :manifest-fingerprint])
               (:manifest-fingerprint entry)))))))

(deftest failed-plugin-update-keeps-installed-entry-pinned
  (let [workspace (temp-dir "ygg-plugin-update-failure")
        app-root (io/file workspace "app")
        package-root (init-git-package! (.getPath (io/file workspace "plugin-package")))
        branch (git! package-root "rev-parse" "--abbrev-ref" "HEAD")
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/ygg/plugins/cache")]
    (.mkdirs app-root)
    (spit project-edn
          (pr-str {:id "plugin-update-failure-fixture"
                   :repos [{:id "app"
                            :root (.getPath app-root)}]}))
    (let [installed (plugin-package/install!
                     (.getPath project-edn)
                     package-root
                     {:ref branch
                      :cache-root (.getPath cache-root)})
          previous-entry (:entry installed)]
      (write-file! package-root
                   plugin-package/manifest-filename
                   (pr-str
                    {:schema plugin-package/manifest-schema
                     :id "sample-plugin-pack"
                     :name "Sample Plugin Pack"
                     :version "0.2.0"
                     :license {:spdx "MIT"}
                     :distribution {:visibility :public
                                    :commercial? false}
                     :benchmark {:status :unbenchmarked}
                     :extractor-plugins
                     [{:id "duplicate-extractor"
                       :command ["python3" "extract.py"]
                       :applies-to {:file-kinds [:code]}}
                      {:id "duplicate-extractor"
                       :command ["python3" "extract.py"]
                       :applies-to {:file-kinds [:code]}}]}))
      (git! package-root "add" ".")
      (git! package-root
            "-c"
            "user.name=Yggdrasil Test"
            "-c"
            "user.email=ygg-test@example.test"
            "commit"
            "--quiet"
            "-m"
            "invalid plugin package")
      (try
        (plugin-package/update!
         (.getPath project-edn)
         "sample-plugin-pack"
         {:cache-root (.getPath cache-root)})
        (is false "Expected update! to reject invalid refreshed package.")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Plugin package local-use validation failed."
                 (ex-message e)))
          (is (= [:duplicate-extractor-plugin-id]
                 (mapv :code (:diagnostics (ex-data e)))))))
      (let [data (edn/read-string (slurp project-edn))]
        (is (= [previous-entry] (:plugins data)))))))

(deftest removes-installed-plugin-package-entry
  (let [workspace (temp-dir "ygg-plugin-remove")
        project-edn (io/file workspace "project.edn")]
    (spit project-edn
          (pr-str {:id "plugin-remove-fixture"
                   :repos []
                   :plugins [{:kind :package
                              :id "keep-plugin"
                              :path "/tmp/keep"
                              :manifest plugin-package/manifest-filename}
                             {:kind :package
                              :id "remove-plugin"
                              :path "/tmp/remove"
                              :manifest plugin-package/manifest-filename}]}))
    (let [result (plugin-package/remove! (.getPath project-edn) "remove-plugin")
          data (edn/read-string (slurp project-edn))]
      (is (= plugin-package/remove-schema (:schema result)))
      (is (= "plugin-remove-fixture" (:project-id result)))
      (is (= "remove-plugin" (:package-id result)))
      (is (= "/tmp/remove" (get-in result [:removed-entry :path])))
      (is (= 1 (:remaining result)))
      (is (= ["keep-plugin"] (mapv :id (:plugins data))))))
  (let [workspace (temp-dir "ygg-plugin-remove-missing")
        project-edn (io/file workspace "project.edn")]
    (spit project-edn
          (pr-str {:id "plugin-remove-missing"
                   :plugins []}))
    (try
      (plugin-package/remove! (.getPath project-edn) "missing-plugin")
      (is false "Expected remove! to reject missing package id.")
      (catch clojure.lang.ExceptionInfo e
        (is (= {:plugin-package-id "missing-plugin"
                :installed []}
               (ex-data e)))))))

(deftest scaffolds-valid-package-and-dry-runs-extractor
  (let [workspace (temp-dir "ygg-plugin-authoring")
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
          input-sample (plugin-package/sample-extractor-inputs
                        (.getPath package-dir)
                        (.getPath repo-root)
                        "src/page.clj"
                        {})
          gap-packet (plugin-package/extractor-gap-packet
                      (.getPath package-dir)
                      (.getPath repo-root)
                      "src/page.clj"
                      {})
          report-dry-run (plugin-package/dry-run-report
                          (.getPath package-dir)
                          {})
          report-input-sample (plugin-package/sample-report-inputs
                               (.getPath package-dir)
                               {})
          report-gap-packet (plugin-package/report-gap-packet
                             (.getPath package-dir)
                             {})
          report-context (#'plugin-package/report-dry-run-context
                          (.getPath package-dir)
                          (plugin-package/read-local-package (.getPath package-dir)))
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
      (is (= :private (:visibility created)))
      (is (= :project-local (get-in created [:scope :kind])))
      (is (.exists (io/file package-dir plugin-package/manifest-filename)))
      (is (.exists (io/file package-dir "registry.example.edn")))
      (is (.exists (io/file package-dir "benchmarks" "README.md")))
      (is (.exists (io/file package-dir "benchmarks" "suite.template.edn")))
      (is (.exists (io/file package-dir "benchmarks" "agent-report.template.json")))
      (is (.exists (io/file package-dir "extract.py")))
      (is (.exists (io/file package-dir "report.py")))
      (is (some #(str/ends-with? % "registry.example.edn") (:files created)))
      (is (some #(str/ends-with? % "benchmarks/README.md") (:files created)))
      (is (some #(str/ends-with? % "benchmarks/suite.template.edn") (:files created)))
      (is (some #(str/ends-with? % "benchmarks/agent-report.template.json")
                (:files created)))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "`registry.example.edn` is a sharing template"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "Unsupported file families"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "use `overlays` to supersede or hide weaker core rows"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "Use `:override` mode"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "`packageName`, `versionRange`, `dependencyScope`, and `importNames`"))
      (is (str/includes? (slurp (io/file package-dir "extract.py"))
                         "\"overlays\": []"))
      (is (str/includes? (slurp (io/file package-dir "extract.py"))
                         "packet.get(\"core\", {})"))
      (is (str/includes? (slurp (io/file package-dir "extract.py"))
                         "dependencyScope"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "bb plugin input extractor . /path/to/repo src/example.clj --json"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "bb plugin input report . --json"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "bb plugin gap extractor . /path/to/repo src/example.clj --json"))
      (is (str/includes? (slurp (io/file package-dir "README.md"))
                         "bb plugin gap report . --json"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         ":benchmark"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         "benchmarks/demo-plugin-agent-report.json"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         "Do not list it in `ygg.plugin.edn`"))
      (is (str/includes? (slurp (io/file package-dir "benchmarks" "README.md"))
                         "bb bench agent-compare benchmarks/suite.edn"))
      (is (= {:id "demo-plugin-plugin"
              :project-id "demo-plugin-plugin"
              :description "Project-agnostic benchmark starter for demo-plugin."
              :repos [{:id "sample-repo"
                       :root "../TODO/path/to/repo"
                       :role :application}]
              :cases [{:id "demo-plugin-architecture"
                       :repo-id "sample-repo"
                       :coverage {:source-kinds [:code]}
                       :tags [:plugin
                              :problem-architecture
                              :architecture-understanding]
                       :base-sha "TODO_BASE_SHA"
                       :fix-sha "TODO_FIX_OR_BASE_SHA"
                       :ground-truth {:localization-files ["fixtures/sample.clj"]}
                       :expectations {:evidence [{:kind :plugin-observation
                                                  :path "fixtures/sample.clj"
                                                  :label "TODO expected plugin evidence"}]
                                      :chunks [{:kind :plugin-summary
                                                :path "fixtures/sample.clj"}]}
                       :issue {:id "demo-plugin-architecture"
                               :url "local:plugin-benchmark/TODO"
                               :title "TODO architecture-understanding task title"
                               :body "TODO ask the agent to identify architecture-relevant files without exposing ground truth."}}]}
             (edn/read-string
              (slurp (io/file package-dir "benchmarks" "suite.template.edn")))))
      (is (str/includes?
           (slurp (io/file package-dir "benchmarks" "agent-report.template.json"))
           "Template only. Replace with real bb bench agent-report output"))
      (is (= {:schema plugin-package/registry-schema
              :id "local-plugin-registry"
              :packages [{:id "demo-plugin"
                          :kinds [:extractor :report]
                          :maintainers [{:name "TODO"}]
                          :support {:status :experimental}
                          :trust {:code-reviewed? false}
                          :path "."
                          :source "https://github.com/ORG/demo-plugin.git"
                          :ref "v0.1.0"}]}
             (edn/read-string (slurp (io/file package-dir "registry.example.edn")))))
      (is (= plugin-package/validate-schema (:schema validation)))
      (is (str/starts-with? manifest-fingerprint "sha256:"))
      (is (= :warning (:status validation)))
      (is (= 1 (count (filter #(= :extractor (:kind %)) (:plugins validation)))))
      (is (= 1 (count (filter #(= :report (:kind %)) (:plugins validation)))))
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
      (is (= {:kind :extractor
              :available ["demo-plugin-extractor"]
              :selected ["demo-plugin-extractor"]
              :skipped []
              :counts {:available 1
                       :selected 1
                       :skipped 0}}
             (:selection dry-run)))
      (is (pos? (get-in dry-run [:transformed-counts :file-facts])))
      (is (pos? (get-in dry-run [:transformed-counts :chunks])))
      (is (some #(= "demo-plugin-extractor" (:plugin-id %))
                (get-in dry-run [:rows :file-facts])))
      (is (= "demo-plugin"
             (get-in dry-run [:plugins 0 :package-id])))
      (is (= "0.1.0"
             (get-in dry-run [:plugins 0 :package-version])))
      (is (= {:type :local
              :path (.getCanonicalPath package-dir)}
             (select-keys (get-in dry-run [:plugins 0 :package-source])
                          [:type :path])))
      (is (= manifest-fingerprint
             (get-in dry-run [:plugins 0 :package-manifest-fingerprint])))
      (is (= claim-authority
             (get-in dry-run [:plugins 0 :package-claim-authority])))
      (is (some #(= manifest-fingerprint (:plugin-package-manifest-fingerprint %))
                (get-in dry-run [:rows :file-facts])))
      (is (some #(= claim-authority (:plugin-package-claim-authority %))
                (get-in dry-run [:rows :file-facts])))
      (is (= plugin-package/input-sample-schema (:schema input-sample)))
      (is (= :passed (:status input-sample)))
      (is (= "src/page.clj" (get-in input-sample [:file :path])))
      (is (= {:kind :extractor
              :available ["demo-plugin-extractor"]
              :selected ["demo-plugin-extractor"]
              :skipped []
              :counts {:available 1
                       :selected 1
                       :skipped 0}}
             (:selection input-sample)))
      (is (= 1 (count (:inputs input-sample))))
      (is (= "ygg.extractor-plugin.input/v1"
             (get-in input-sample [:inputs 0 :schema])))
      (is (= "demo-plugin-extractor"
             (get-in input-sample [:inputs 0 :plugin :id])))
      (is (= "demo-plugin"
             (get-in input-sample [:inputs 0 :plugin :packageId])))
      (is (= manifest-fingerprint
             (get-in input-sample [:inputs 0 :plugin :packageManifestFingerprint])))
      (is (= claim-authority
             (get-in input-sample [:inputs 0 :plugin :packageClaimAuthority])))
      (is (= "src/page.clj"
             (get-in input-sample [:inputs 0 :file :path])))
      (is (contains? (get-in input-sample [:inputs 0 :core]) :nodes))
      (is (= (get-in dry-run [:core-counts :nodes])
             (get-in input-sample [:core-counts :nodes])))
      (is (= plugin-package/extractor-gap-schema (:schema gap-packet)))
      (is (= :passed (:status gap-packet)))
      (is (= (:selection input-sample) (:selection gap-packet)))
      (is (= (:inputs input-sample) (:inputs gap-packet)))
      (is (= "ygg.extractor-plugin.result/v1"
             (get-in gap-packet [:output-contract :schema])))
      (is (= [:nodes :edges :chunks :fileFacts :diagnostics]
             (get-in gap-packet [:output-contract :core-input-buckets])))
      (is (= [:enhance :override :scan]
             (mapv :name (get-in gap-packet [:output-contract :plugin-modes]))))
      (is (= [:nodes :edges :fileFacts :chunks :diagnostics :overlays]
             (mapv :name (get-in gap-packet [:output-contract :buckets]))))
      (is (= [:packageName
              :versionRange
              :resolvedVersion
              :dependencyScope
              :importNames
              :importName
              :importKind
              :resolutionSource]
             (mapv :json (get-in gap-packet [:output-contract :dependency-aliases]))))
      (is (some #(str/includes? % "same xt/id")
                (get-in gap-packet [:output-contract :row-requirements])))
      (is (some #(str/includes? % "external-package")
                (get-in gap-packet [:output-contract :row-requirements])))
      (is (some #(str/includes? % "targetId")
                (get-in gap-packet [:output-contract :overlay-requirements])))
      (is (some #(= :dry-run (:id %))
                (get-in gap-packet [:proof :local-checks])))
      (is (some #(str/includes? % "benchmark")
                (get-in gap-packet [:proof :public-claim-requirements])))
      (is (some #(str/includes? % "project-specific")
                (get-in gap-packet [:proof :core-promotion-requirements])))
      (is (= :unbenchmarked (get-in gap-packet [:caveats :benchmark-status])))
      (is (= claim-authority (get-in gap-packet [:caveats :claim-authority])))
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
      (is (= {:kind :report
              :available ["demo-plugin-report"]
              :selected ["demo-plugin-report"]
              :skipped []
              :counts {:available 1
                       :selected 1
                       :skipped 0}}
             (:selection report-dry-run)))
      (is (= "demo-plugin-report" (get-in report-dry-run [:plugins 0 :id])))
      (is (= :unbenchmarked (get-in report-dry-run [:plugins 0 :benchmark-status])))
      (is (= "demo-plugin"
             (get-in report-dry-run [:plugins 0 :package-id])))
      (is (= "0.1.0"
             (get-in report-dry-run [:plugins 0 :package-version])))
      (is (= {:type :local
              :path (.getCanonicalPath package-dir)}
             (select-keys (get-in report-dry-run [:plugins 0 :package-source])
                          [:type :path])))
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
                     [:outputs 0 :output :panels 0 :plugin :packageClaimAuthority])))
      (is (= plugin-package/input-sample-schema (:schema report-input-sample)))
      (is (= :report (:kind report-input-sample)))
      (is (= :passed (:status report-input-sample)))
      (is (= {:kind :report
              :available ["demo-plugin-report"]
              :selected ["demo-plugin-report"]
              :skipped []
              :counts {:available 1
                       :selected 1
                       :skipped 0}}
             (:selection report-input-sample)))
      (is (= 1 (count (:inputs report-input-sample))))
      (is (= "ygg.report-plugin.input/v1"
             (get-in report-input-sample [:inputs 0 :schema])))
      (is (= "demo-plugin-report"
             (get-in report-input-sample [:inputs 0 :plugin :id])))
      (is (= "demo-plugin"
             (get-in report-input-sample [:inputs 0 :plugin :packageId])))
      (is (= manifest-fingerprint
             (get-in report-input-sample
                     [:inputs 0 :plugin :packageManifestFingerprint])))
      (is (= claim-authority
             (get-in report-input-sample
                     [:inputs 0 :plugin :packageClaimAuthority])))
      (is (= "demo-plugin"
             (get-in report-input-sample
                     [:inputs 0 :pluginPackages :packages 0 :id])))
      (is (= plugin-package/report-gap-schema (:schema report-gap-packet)))
      (is (= :report (:kind report-gap-packet)))
      (is (= :passed (:status report-gap-packet)))
      (is (= (:selection report-input-sample) (:selection report-gap-packet)))
      (is (= (get-in report-input-sample [:inputs 0 :schema])
             (get-in report-gap-packet [:inputs 0 :schema])))
      (is (= (get-in report-input-sample [:inputs 0 :plugin])
             (get-in report-gap-packet [:inputs 0 :plugin])))
      (is (= (get-in report-input-sample [:inputs 0 :pluginPackages])
             (get-in report-gap-packet [:inputs 0 :pluginPackages])))
      (is (= "ygg.report-plugin.result/v1"
             (get-in report-gap-packet [:output-contract :schema])))
      (is (= [:panels :diagnostics :artifacts]
             (mapv :name (get-in report-gap-packet [:output-contract :buckets]))))
      (is (= ["atlas" "systems" "dependencies" "evidence" "maintenance" "plugins"]
             (get-in report-gap-packet [:output-contract :known-slots])))
      (is (some #(= :dry-run (:id %))
                (get-in report-gap-packet [:proof :local-checks])))
      (is (some #(str/includes? % "benchmark")
                (get-in report-gap-packet [:proof :public-claim-requirements])))
      (is (some #(str/includes? % "project-specific")
                (get-in report-gap-packet [:proof :core-promotion-requirements])))
      (is (= :unbenchmarked (get-in report-gap-packet [:caveats :benchmark-status])))
      (is (= claim-authority (get-in report-gap-packet [:caveats :claim-authority])))
      (is (= 1 (get-in report-context [:report :plugins :packages :counts :packages])))
      (is (= 1 (get-in report-context [:report :plugins :packages :counts :unbenchmarked])))
      (is (= "demo-plugin"
             (get-in report-context [:report :plugins :packages :packages 0 :id])))
      (is (= claim-authority
             (get-in report-context
                     [:report :plugins :packages :packages 0 :claim-authority]))))))

(deftest scaffold-and-gap-packet-share-extractor-authoring-contract
  (let [workspace (temp-dir "ygg-plugin-authoring-contract")
        package-dir (io/file workspace "plugins" "contract")
        repo-root (io/file workspace "repo")
        src-dir (io/file repo-root "src")]
    (.mkdirs src-dir)
    (spit (io/file src-dir "page.clj") "(ns page)\n(defn render [] :ok)\n")
    (plugin-package/new! (.getPath package-dir)
                         {:id "contract-plugin"
                          :extractor? true
                          :report? false})
    (let [manifest (edn/read-string
                    (slurp (io/file package-dir plugin-package/manifest-filename)))
          extractor (first (filter #(= :extractor (:kind %)) (:plugins manifest)))
          extract-py (slurp (io/file package-dir "extract.py"))
          gap-packet (plugin-package/extractor-gap-packet
                      (.getPath package-dir)
                      (.getPath repo-root)
                      "src/page.clj"
                      {})
          output-contract (:output-contract gap-packet)
          gap-aliases (set (map :json (:dependency-aliases output-contract)))
          scaffold-aliases [:packageName
                            :versionRange
                            :dependencyScope
                            :importNames
                            :importKind]]
      (is (= [:enhance :override :scan] (:modes extractor)))
      (is (= ["python3" "extract.py"] (:command extractor)))
      (doseq [token ["packet.get(\"core\", {})"
                     "core.get(\"fileFacts\", [])"
                     "same xt/id"]]
        (is (str/includes? extract-py token)))
      (doseq [alias scaffold-aliases]
        (is (str/includes? extract-py (name alias)))
        (is (contains? gap-aliases alias)))
      (is (= [:nodes :edges :chunks :fileFacts :diagnostics]
             (:core-input-buckets output-contract)))
      (is (= [:enhance :override :scan]
             (mapv :name (:plugin-modes output-contract))))
      (is (some #(str/includes? % "same xt/id")
                (:row-requirements output-contract)))
      (is (some #(str/includes? % "external-package")
                (:row-requirements output-contract))))))

(deftest project-sync-dogfoods-plugin-overrides-and-dependency-rows
  (let [workspace (temp-dir "ygg-plugin-sync-dogfood")
        repo-root (io/file workspace "repo")
        package-dir (io/file workspace "plugins" "override-deps")
        project-edn (io/file workspace "project.edn")
        xtdb-path (temp-dir "ygg-plugin-sync-dogfood-xtdb")
        package-source {:type :local
                        :path (.getPath package-dir)}]
    (write-file! repo-root
                 "package.json"
                 "{\"name\":\"dogfood-app\",\"dependencies\":{}}\n")
    (write-file! repo-root
                 "src/app.js"
                 "import pluginLib from \"plugin-lib\";\nconsole.log(pluginLib);\n")
    (write-file!
     package-dir
     "extract.py"
     (str "import json, sys\n"
          "packet=json.load(sys.stdin)\n"
          "manifest=next(node for node in packet['core']['nodes'] "
          "if str(node.get('kind')).endswith('manifest'))\n"
          "manifest_id=manifest['xt/id']\n"
          "package_id='node:external-package:npm:plugin-lib'\n"
          "json.dump({'schema':'ygg.extractor-plugin.result/v1',"
          "'nodes':[{'xt/id':manifest_id,"
          "'kind':'manifest',"
          "'label':'plugin manifest override',"
          "'sourceLine':1},"
          "{'xt/id':package_id,"
          "'kind':'external-package',"
          "'label':'npm:plugin-lib',"
          "'ecosystem':'npm',"
          "'packageName':'plugin-lib',"
          "'sourceLine':1}],"
          "'edges':[{'sourceId':manifest_id,"
          "'targetId':package_id,"
          "'relation':'requires',"
          "'ecosystem':'npm',"
          "'packageName':'plugin-lib',"
          "'versionRange':'^1.0.0',"
          "'sourceLine':1}]}, sys.stdout)\n"))
    (write-file!
     package-dir
     plugin-package/manifest-filename
     (pr-str {:schema plugin-package/manifest-schema
              :id "override-deps"
              :name "Override Dependencies"
              :version "0.1.0"
              :license {:spdx "MIT"}
              :distribution {:visibility :private
                             :commercial? false}
              :scope {:kind :project-local
                      :reason "Dogfood package for end-to-end plugin sync."}
              :benchmark {:status :unbenchmarked}
              :plugins [{:kind :extractor
                         :id "override-deps-extractor"
                         :command ["python3" "extract.py"]
                         :modes [:enhance :override]
                         :applies-to {:file-kinds [:manifest]
                                      :path-globs ["package.json"]}
                         :emits [:external-package]}]}))
    (spit project-edn
          (pr-str {:id "plugin-sync-dogfood"
                   :repos [{:id "app"
                            :root (.getPath repo-root)}]
                   :plugins [{:kind :package
                              :id "override-deps"
                              :path (.getPath package-dir)
                              :source package-source}]}))
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [loaded (project/read-project (.getPath project-edn))
              summary (project/index-project! xtdb loaded {})
              repo-summary (first (:repos summary))
              nodes (store/rows-by-field xtdb
                                         (:nodes store/tables)
                                         :project-id
                                         "plugin-sync-dogfood")
              edges (store/rows-by-field xtdb
                                         (:edges store/tables)
                                         :project-id
                                         "plugin-sync-dogfood")
              override-node (some #(when (= "plugin manifest override"
                                            (:label %))
                                     %)
                                  nodes)
              package-node (some #(when (and (= :external-package (:kind %))
                                             (= "plugin-lib" (:package-name %)))
                                    %)
                                 nodes)
              requires-edge (some #(when (and (= :requires (:relation %))
                                              (= "plugin-lib" (:package-name %)))
                                     %)
                                  edges)
              dependency-edge (some #(when (and (= :imports-package (:relation %))
                                                (= "plugin-lib" (:package-name %)))
                                       %)
                                    edges)]
          (is (= :completed (:status summary)))
          (is (= :completed (:status repo-summary)))
          (is (= 1 (get-in repo-summary [:stats :dependency-edges])))
          (is (= {:provenance :plugin
                  :plugin-id "override-deps-extractor"
                  :plugin-package-id "override-deps"
                  :plugin-package-source package-source
                  :benchmark-status :unbenchmarked}
                 (select-keys override-node
                              [:provenance
                               :plugin-id
                               :plugin-package-id
                               :plugin-package-source
                               :benchmark-status])))
          (is (str/starts-with? (:plugin-package-manifest-fingerprint override-node)
                                "sha256:"))
          (is (= "override-deps" (:plugin-package-id package-node)))
          (is (= "override-deps" (:plugin-package-id requires-edge)))
          (is (= :declared (:resolution-source dependency-edge)))
          (is (= :npm (:ecosystem dependency-edge)))
          (is (= "src/app.js" (:path dependency-edge))))))))

(deftest scaffold-can-create-explicit-public-base-package
  (let [workspace (temp-dir "ygg-plugin-public-base")
        package-dir (io/file workspace "plugins" "shared")
        created (plugin-package/new! (.getPath package-dir)
                                     {:id "shared-plugin"
                                      :extractor? true
                                      :report? false
                                      :public-base? true})
        manifest (edn/read-string
                  (slurp (io/file package-dir plugin-package/manifest-filename)))
        diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
    (is (= :public (:visibility created)))
    (is (= :base (get-in created [:scope :kind])))
    (is (= {:visibility :public
            :commercial? false}
           (:distribution manifest)))
    (is (= :base (get-in manifest [:scope :kind])))
    (is (= :caution (get-in diagnosis [:readiness :public-sharing :status])))
    (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
    (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
    (is (= [:unbenchmarked]
           (mapv :code (get-in diagnosis [:package :claim-authority :blockers]))))))

(deftest scaffolds-unsupported-file-family-extractor-options
  (let [workspace (temp-dir "ygg-plugin-unsupported-file-family")
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
        extractor (first (filter #(= :extractor (:kind %)) (:plugins validation)))]
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
           (get-in manifest [:plugins 0 :applies-to :file-kinds])))
    (is (= ["templates/*.html" "resources/**/*.html"]
           (get-in manifest [:plugins 0 :applies-to :path-globs])))
    (is (= {:path-globs ["templates/*.html"]
            :file-kind :htmx}
           (get-in manifest [:plugins 0 :scan])))
    (is (= :warning (:status validation)))
    (is (= #{:enhance :override :scan} (:modes extractor)))
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
      (is (pos? (get-in dry-run [:transformed-counts :file-facts])))
      (is (pos? (get-in dry-run [:transformed-counts :chunks]))))))

(deftest dry-run-fails-when-package-has-no-plugin-for-selected-lane
  (let [workspace (temp-dir "ygg-plugin-empty-lane")
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

(deftest dry-run-reports-selected-and-skipped-plugins
  (let [workspace (temp-dir "ygg-plugin-selection")
        package-dir (io/file workspace "plugin")
        repo-root (io/file workspace "repo")
        src (io/file repo-root "src")]
    (.mkdirs package-dir)
    (.mkdirs src)
    (spit (io/file src "page.clj") "(ns page)\n(defn render [] :ok)\n")
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath package-dir)
                 "report.py"
                 "import json, sys\njson.dump({'schema':'ygg.report-plugin.result/v1','panels':[]}, sys.stdout)\n")
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "selection-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :private
                                  :commercial? false}
                   :scope {:kind :project-local
                           :reason "Selection test fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "first-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}
                    {:id "second-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]
                   :report-plugins
                   [{:id "first-report"
                     :command ["python3" "report.py"]
                     :slots [:plugins]}
                    {:id "second-report"
                     :command ["python3" "report.py"]
                     :slots [:plugins]}]}))
    (let [extractor-result (plugin-package/dry-run-extractor
                            (.getPath package-dir)
                            (.getPath repo-root)
                            "src/page.clj"
                            {:plugin-id "first-extractor"})
          report-result (plugin-package/dry-run-report
                         (.getPath package-dir)
                         {:plugin-id "second-report"})]
      (is (= :passed (:status extractor-result)))
      (is (= {:kind :extractor
              :requested-plugin-id "first-extractor"
              :available ["first-extractor" "second-extractor"]
              :selected ["first-extractor"]
              :skipped ["second-extractor"]
              :counts {:available 2
                       :selected 1
                       :skipped 1}}
             (:selection extractor-result)))
      (is (= :passed (:status report-result)))
      (is (= {:kind :report
              :requested-plugin-id "second-report"
              :available ["first-report" "second-report"]
              :selected ["second-report"]
              :skipped ["first-report"]
              :counts {:available 2
                       :selected 1
                       :skipped 1}}
             (:selection report-result))))))

(deftest validate-rejects-unsupported-package-benchmark-status
  (let [workspace (temp-dir "ygg-plugin-benchmark-status")
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
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [validation (plugin-package/validate-local (.getPath package-dir))]
      (is (= :failed (:status validation)))
      (is (= ["Unknown plugin package benchmark status."] (:errors validation)))
      (is (= {:benchmark-status :claimed
              :supported [:benchmarked :unbenchmarked]}
             (:data validation))))))

(deftest validate-rejects-duplicate-plugin-ids-within-package-lanes
  (let [workspace (temp-dir "ygg-plugin-duplicate-ids")
        package-dir (io/file workspace "plugin")
        repo-root (io/file workspace "repo")
        src (io/file repo-root "src")
        project-edn (io/file workspace "project.edn")
        cache-root (io/file workspace ".dev/ygg/plugins/cache")]
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
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath package-dir)
                 "report.py"
                 "import json, sys\njson.dump({'schema':'ygg.report-plugin.result/v1','panels':[]}, sys.stdout)\n")
    (git! (.getPath package-dir) "init" "--quiet")
    (git! (.getPath package-dir) "add" ".")
    (git! (.getPath package-dir)
          "-c"
          "user.name=Yggdrasil Test"
          "-c"
          "user.email=ygg-test@example.test"
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
      (is (nil? (:plugins (edn/read-string (slurp project-edn))))))))

(deftest diagnose-blocks-invalid-public-package-policy
  (let [workspace (temp-dir "ygg-plugin-diagnose")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                   :id "paid-plugin"
                   :version "0.1.0"
                   :license {:spdx "Proprietary"}
                   :distribution {:visibility :public
                                  :commercial? true}
                   :scope {:kind :base
                           :reason "Policy test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report
                                            :case-id "paid-plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement benchmark-improvement-fixture}]}
                   :extractor-plugins
                   [{:id "paid-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:public-license-missing
               :public-commercial
               :core-fixtures-missing
               :core-tests-missing}
             (set (map :code (:diagnostics diagnosis))))))))

(deftest diagnose-requires-explicit-benchmark-status-for-public-sharing
  (let [workspace (temp-dir "ygg-plugin-benchmark-status-required")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "missing-benchmark-status-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark status fixture."}
                   :extractor-plugins
                   [{:id "missing-benchmark-status-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [validation (plugin-package/validate-local (.getPath package-dir))
          diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :warning (:status validation)))
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:benchmark-status-missing :unbenchmarked}
             (set (map :code (:diagnostics diagnosis))))))))

(deftest diagnose-requires-benchmark-artifacts-for-claims-and-core-promotion
  (let [workspace (temp-dir "ygg-plugin-benchmark-evidence")
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
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
  (let [workspace (temp-dir "ygg-plugin-benchmark-ready")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                                            :case-id "plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement benchmark-improvement-fixture}]}
                   :extractor-plugins
                   [{:id "benchmarked-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :warning (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:core-fixtures-missing :core-tests-missing}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= ["benchmarks/report.json"]
             (mapv :path (get-in diagnosis [:package :benchmark-artifacts])))))))

(deftest diagnose-requires-benchmark-artifact-metadata-for-claims
  (let [workspace (temp-dir "ygg-plugin-benchmark-metadata")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                   :id "metadata-missing-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark metadata test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"}]}
                   :core-promotion {:fixtures [{:path "fixtures/sample.clj"
                                                :kind :fixture}]
                                    :tests [{:path "test/sample_test.clj"
                                             :kind :test}]}
                   :extractor-plugins
                   [{:id "metadata-missing-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= #{:benchmark-artifact-kind-missing
               :benchmark-artifact-case-id-missing
               :benchmark-artifact-problem-class-missing
               :benchmark-artifact-improvement-missing}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= #{:benchmark-artifact-kind-missing
               :benchmark-artifact-case-id-missing
               :benchmark-artifact-problem-class-missing
               :benchmark-artifact-improvement-missing}
             (set (map :code
                       (get-in diagnosis
                               [:package :claim-authority :blockers]))))))))

(deftest diagnose-requires-material-improvement-metadata-for-benchmark-artifacts
  (let [workspace (temp-dir "ygg-plugin-benchmark-improvement")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                   :id "improvement-missing-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark improvement metadata test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report
                                            :case-id "plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement {:metric :file-recall-at-5
                                                          :baseline :core-ygg
                                                          :candidate :plugin-enhanced-ygg
                                                          :effect 0.25}}]}
                   :core-promotion {:fixtures [{:path "fixtures/sample.clj"
                                                :kind :fixture}]
                                    :tests [{:path "test/sample_test.clj"
                                             :kind :test}]}
                   :extractor-plugins
                   [{:id "improvement-missing-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= [:benchmark-artifact-improvement-delta-missing]
             (mapv :code (:diagnostics diagnosis)))))))

(deftest diagnose-requires-positive-material-improvement-effect
  (let [workspace (temp-dir "ygg-plugin-benchmark-effect")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                   :id "improvement-effect-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Benchmark effect metadata test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report
                                            :case-id "plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement {:metric :file-recall-at-5
                                                          :baseline :core-ygg
                                                          :candidate :plugin-enhanced-ygg
                                                          :delta "+0.00"
                                                          :effect 0}}]}
                   :core-promotion {:fixtures [{:path "fixtures/sample.clj"
                                                :kind :fixture}]
                                    :tests [{:path "test/sample_test.clj"
                                             :kind :test}]}
                   :extractor-plugins
                   [{:id "improvement-effect-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :failed (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= [:benchmark-artifact-improvement-effect-invalid]
             (mapv :code (:diagnostics diagnosis)))))))

(deftest diagnose-requires-core-promotion-fixtures-and-tests
  (let [workspace (temp-dir "ygg-plugin-core-ready")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                                            :case-id "plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement benchmark-improvement-fixture}]}
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
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))
          core-check (plugin-package/core-promotion-check (.getPath package-dir))]
      (is (= :passed (:status diagnosis)))
      (is (= :ready (get-in diagnosis [:readiness :claims :status])))
      (is (= :review-required (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= plugin-package/core-check-schema (:schema core-check)))
      (is (= :passed (:status core-check)))
      (is (= :review-required (get-in core-check [:core-promotion :status])))
      (is (= ["fixtures/sample.clj"]
             (mapv :path (get-in diagnosis [:package :core-promotion :fixtures]))))
      (is (= ["test/sample_test.clj"]
             (mapv :path (get-in diagnosis [:package :core-promotion :tests])))))))

(deftest diagnose-blocks-claims-and-core-promotion-for-commercial-policy
  (let [workspace (temp-dir "ygg-plugin-commercial-policy")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                   :id "commercial-plugin"
                   :version "0.1.0"
                   :license {:spdx "Proprietary"}
                   :distribution {:visibility :private
                                  :commercial? true}
                   :scope {:kind :base
                           :reason "Policy test fixture."}
                   :benchmark {:status :benchmarked
                               :artifacts [{:path "benchmarks/report.json"
                                            :kind :agent-report
                                            :case-id "plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement benchmark-improvement-fixture}]}
                   :core-promotion {:fixtures [{:path "fixtures/sample.clj"
                                                :kind :fixture}]
                                    :tests [{:path "test/sample_test.clj"
                                             :kind :test}]}
                   :extractor-plugins
                   [{:id "commercial-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))]
      (is (= :warning (:status diagnosis)))
      (is (= :private (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= :non-authoritative
             (get-in diagnosis [:package :claim-authority :status])))
      (is (= #{:claim-license-not-foss :claim-commercial}
             (set (map :code (:diagnostics diagnosis)))))
      (is (= #{:claim-license-not-foss :claim-commercial}
             (set (map :code
                       (get-in diagnosis
                               [:package :claim-authority :blockers]))))))))

(deftest diagnose-keeps-project-local-plugins-external
  (let [workspace (temp-dir "ygg-plugin-project-local")
        package-dir (io/file workspace "plugin")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "benchmarks/report.json"
                 "{\"schema\":\"ygg.benchmark.agent-report/v1\",\"suite-id\":\"plugin\"}\n")
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
                                            :kind :agent-report
                                            :case-id "local-plugin-case"
                                            :problem-class :architecture-understanding
                                            :improvement benchmark-improvement-fixture}]}
                   :extractor-plugins
                   [{:id "local-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (let [diagnosis (plugin-package/diagnose-local (.getPath package-dir))
          core-check (plugin-package/core-promotion-check (.getPath package-dir))]
      (is (= :warning (:status diagnosis)))
      (is (= :blocked (get-in diagnosis [:readiness :public-sharing :status])))
      (is (= :blocked (get-in diagnosis [:readiness :claims :status])))
      (is (= :blocked (get-in diagnosis [:readiness :core-promotion :status])))
      (is (= :failed (:status core-check)))
      (is (= :blocked (get-in core-check [:core-promotion :status])))
      (is (= [:project-local-scope]
             (mapv :code (:diagnostics diagnosis)))))))

(deftest registry-list-supports-remote-discovery-without-package-checkouts
  (let [workspace (temp-dir "ygg-plugin-registry-list")
        registry-path (io/file workspace "registry.edn")]
    (spit registry-path
          (pr-str {:schema plugin-package/registry-schema
                   :id "official"
                   :name "Yggdrasil plugins"
                   :description "Public plugin index."
                   :packages [{:id "datastar-hiccup"
                               :description "Datastar and Hiccup extractor."
                               :tags ["clojure" "hypermedia"]
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"
                               :subdir "packages/datastar-hiccup"}
                              {:id "dashboard-panels"
                               :description "Report panels."
                               :kinds [:report]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? true}
                               :source "https://github.com/org/ygg-plugins.git"}]}))
    (let [all-result (plugin-package/list-registry (.getPath registry-path))
          filtered (plugin-package/list-registry
                    (.getPath registry-path)
                    {:kind "extractor"
                     :query "hiccup"})
          by-id (into {} (map (juxt :id identity) (:packages all-result)))]
      (is (= plugin-package/registry-list-schema (:schema all-result)))
      (is (= :warning (:status all-result)))
      (is (= {:packages 2
              :matched 2
              :listed 1
              :invalid 1
              :installable 1}
             (:counts all-result)))
      (is (= {:id "datastar-hiccup"
              :description "Datastar and Hiccup extractor."
              :tags ["clojure" "hypermedia"]
              :kinds [:extractor]
              :maintainers [{:name "Maintainer"}]
              :support {:status :experimental}
              :trust {:code-reviewed? false}
              :source "https://github.com/org/ygg-plugins.git"
              :ref "v0.1.0"
              :subdir "packages/datastar-hiccup"}
             (get-in by-id ["datastar-hiccup" :registry-entry])))
      (is (= "bb plugin install '<project.edn>' https://github.com/org/ygg-plugins.git --ref v0.1.0 --subdir packages/datastar-hiccup"
             (get-in by-id ["datastar-hiccup" :install :command])))
      (is (= [:registry-ref-missing]
             (mapv :code (get-in by-id ["dashboard-panels" :errors]))))
      (is (= :passed (:status filtered)))
      (is (= {:kind "extractor"
              :query "hiccup"}
             (:filters filtered)))
      (is (= ["datastar-hiccup"] (mapv :id (:packages filtered)))))))

(deftest registry-validation-enforces-public-sharing-readiness
  (let [workspace (temp-dir "ygg-plugin-registry")
        registry-path (io/file workspace "registry.edn")
        base-dir (io/file workspace "base")
        local-dir (io/file workspace "local")
        missing-source-dir (io/file workspace "missing-source")
        floating-ref-dir (io/file workspace "floating-ref")]
    (.mkdirs base-dir)
    (.mkdirs local-dir)
    (write-file! (.getPath base-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath missing-source-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
    (write-file! (.getPath floating-ref-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
    (write-file! (.getPath floating-ref-dir)
                 plugin-package/manifest-filename
                 (pr-str
                  {:schema plugin-package/manifest-schema
                   :id "floating-ref-plugin"
                   :version "0.1.0"
                   :license {:spdx "MIT"}
                   :distribution {:visibility :public
                                  :commercial? false}
                   :scope {:kind :base
                           :reason "Reusable fixture."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "floating-ref-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (spit registry-path
          (pr-str {:schema plugin-package/registry-schema
                   :id "official"
                   :packages [{:id "base-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "base"
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"
                               :subdir "packages/base-plugin"}
                              {:id "local-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "local"}
                              {:id "missing-source-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "missing-source"}
                              {:id "floating-ref-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "floating-ref"
                               :source "https://github.com/org/ygg-plugins.git"
                               :subdir "packages/floating-ref"}]}))
    (let [result (plugin-package/validate-registry (.getPath registry-path))
          by-id (into {} (map (juxt :id identity) (:packages result)))]
      (is (= plugin-package/registry-validate-schema (:schema result)))
      (is (= :failed (:status result)))
      (is (= {:packages 4
              :passed 1
              :failed 3
              :claim-ready 0
              :non-authoritative 4}
             (:counts result)))
      (is (= {:registry-source-missing 2
              :registry-ref-missing 1
              :public-sharing-not-ready 1}
             (:error-counts result)))
      (is (= :passed (get-in by-id ["base-plugin" :status])))
      (is (= {:type :git
              :url "https://github.com/org/ygg-plugins.git"
              :ref "v0.1.0"
              :subdir "packages/base-plugin"}
             (get-in by-id ["base-plugin" :install :source])))
      (is (= ["plugin" "install" "<project.edn>" "https://github.com/org/ygg-plugins.git"
              "--ref" "v0.1.0" "--subdir" "packages/base-plugin"]
             (get-in by-id ["base-plugin" :install :args])))
      (is (= "bb plugin install '<project.edn>' https://github.com/org/ygg-plugins.git --ref v0.1.0 --subdir packages/base-plugin"
             (get-in by-id ["base-plugin" :install :command])))
      (is (= {:id "base-plugin"
              :kinds [:extractor]
              :maintainers [{:name "Maintainer"}]
              :support {:status :experimental}
              :trust {:code-reviewed? false}
              :source "https://github.com/org/ygg-plugins.git"
              :ref "v0.1.0"
              :subdir "packages/base-plugin"}
             (get-in by-id ["base-plugin" :registry-entry])))
      (is (= {:id "base-plugin"
              :version "0.1.0"
              :visibility :public
              :license {:spdx "MIT"}
              :scope {:kind :base
                      :reason "Reusable fixture."}
              :benchmark-status :unbenchmarked
              :benchmark-cases {:artifacts 0
                                :case-ids []
                                :problem-classes []
                                :improvement-metrics []}
              :claim-authority {:status :non-authoritative
                                :public-claims? false
                                :review-required? false
                                :blockers [{:code :unbenchmarked
                                            :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}
              :diagnostic-counts {:total 1
                                  :errors 0
                                  :warnings 1}}
             (get-in by-id ["base-plugin" :package-summary])))
      (is (= :failed (get-in by-id ["local-plugin" :status])))
      (is (= [:registry-source-missing :public-sharing-not-ready]
             (mapv :code (get-in by-id ["local-plugin" :errors]))))
      (is (= :failed (get-in by-id ["missing-source-plugin" :status])))
      (is (= [:registry-source-missing]
             (mapv :code (get-in by-id ["missing-source-plugin" :errors]))))
      (is (= :failed (get-in by-id ["floating-ref-plugin" :status])))
      (is (= [:registry-ref-missing]
             (mapv :code (get-in by-id ["floating-ref-plugin" :errors])))))))

(deftest registry-validation-requires-public-entry-metadata
  (let [workspace (temp-dir "ygg-plugin-registry-metadata")
        registry-path (io/file workspace "registry.edn")
        package-dir (io/file workspace "package")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"}]}))
    (let [result (plugin-package/validate-registry (.getPath registry-path))
          errors (mapv :code (get-in result [:packages 0 :errors]))]
      (is (= :failed (:status result)))
      (is (= {:registry-kinds-missing 1
              :registry-maintainers-missing 1
              :registry-support-status-missing 1
              :registry-trust-review-missing 1}
             (:error-counts result)))
      (is (= [:registry-kinds-missing
              :registry-maintainers-missing
              :registry-support-status-missing
              :registry-trust-review-missing]
             errors)))))

(deftest registry-install-installs-passed-entry-through-pinned-git-source
  (let [workspace (temp-dir "ygg-plugin-registry-install")
        registry-path (io/file workspace "registry.edn")
        package-dir (io/file workspace "package")
        calls (atom [])]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
                   :name "Official"
                   :packages [{:id "base-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "package"
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"
                               :subdir "packages/base-plugin"}]}))
    (with-redefs [plugin-package/install!
                  (fn [config-path source opts]
                    (swap! calls conj [config-path source opts])
                    {:schema plugin-package/install-schema
                     :project-id "fixture"
                     :package {:id "base-plugin"
                               :version "0.1.0"}
                     :entry {:source {:type :git
                                      :url source
                                      :ref (:ref opts)
                                      :rev "abc123"
                                      :subdir (:subdir opts)}}})]
      (let [result (plugin-package/registry-install!
                    (.getPath registry-path)
                    "project.edn"
                    "base-plugin"
                    {:cache-root ".cache/plugins"
                     :force? true})]
        (is (= plugin-package/registry-install-schema (:schema result)))
        (is (= "base-plugin" (:package-id result)))
        (is (= {:id "official"
                :name "Official"}
               (:registry result)))
        (is (= :passed (get-in result [:registry-package :status])))
        (is (= {:type :git
                :url "https://github.com/org/ygg-plugins.git"
                :ref "v0.1.0"
                :subdir "packages/base-plugin"}
               (get-in result [:registry-package :install :source])))
        (is (= [["project.edn"
                 "https://github.com/org/ygg-plugins.git"
                 {:ref "v0.1.0"
                  :subdir "packages/base-plugin"
                  :cache-root ".cache/plugins"
                  :force? true}]]
               @calls))))))

(deftest registry-install-rejects-failed-registry-entry
  (let [workspace (temp-dir "ygg-plugin-registry-install-failed")
        registry-path (io/file workspace "registry.edn")
        package-dir (io/file workspace "package")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
                           :reason "One project only."}
                   :benchmark {:status :unbenchmarked}
                   :extractor-plugins
                   [{:id "local-extractor"
                     :command ["python3" "extract.py"]
                     :applies-to {:file-kinds [:code]}}]}))
    (spit registry-path
          (pr-str {:schema plugin-package/registry-schema
                   :id "official"
                   :packages [{:id "local-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "package"
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"}]}))
    (try
      (plugin-package/registry-install!
       (.getPath registry-path)
       "project.edn"
       "local-plugin"
       {})
      (is false "Expected registry-install! to reject failed registry entry.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Plugin registry package is not installable."
               (ex-message e)))
        (is (= "local-plugin" (:package-id (ex-data e))))
        (is (= [:public-sharing-not-ready]
               (mapv :code (:errors (ex-data e)))))))))

(deftest registry-validation-rejects-duplicate-package-ids
  (let [workspace (temp-dir "ygg-plugin-registry-duplicate-id")
        registry-path (io/file workspace "registry.edn")
        package-dir (io/file workspace "package")]
    (.mkdirs package-dir)
    (write-file! (.getPath package-dir)
                 "extract.py"
                 "import json, sys\njson.dump({'schema':'ygg.extractor-plugin.result/v1'}, sys.stdout)\n")
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
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "package"
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"
                               :subdir "packages/base-plugin"}
                              {:id "base-plugin"
                               :kinds [:extractor]
                               :maintainers [{:name "Maintainer"}]
                               :support {:status :experimental}
                               :trust {:code-reviewed? false}
                               :path "package"
                               :source "https://github.com/org/ygg-plugins.git"
                               :ref "v0.1.0"
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
