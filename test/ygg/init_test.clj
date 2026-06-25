(ns ygg.init-test
  (:require [ygg.fs :as fs]
            [ygg.init :as init]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [charred.api :as json]
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

(defn- registry-path
  [root]
  (.getPath (io/file root ".config" "projects.edn")))

(defn- canonical-file-path
  [file]
  (.getCanonicalPath file))

(deftest init-writes-plain-repo-project-config
  (let [root (temp-dir "ygg-init-repo")
        canonical-root (fs/canonical-path root)
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "demo"
                                     :name "Demo"})]
        (is (= init/schema (:schema result)))
        (is (= "demo" (:project-id result)))
        (is (= 1 (:repos result)))
        (is (= true (:first-init result)))
        (is (= 1 (:init-count result)))
        (is (= registry-path (:registry result)))
        (let [raw (edn/read-string (slurp out))
              project (project/read-project out)]
          (is (= {:id "demo"
                  :name "Demo"
                  :repos [{:id "app"
                           :root canonical-root
                           :role :application}]}
                 raw))
          (is (= "demo" (:id project)))
          (is (= canonical-root (get-in project [:repos 0 :root])))
          (is (= {:schema registry/project-ref-schema
                  :project-id "demo"}
                 (edn/read-string (slurp (:project-ref result))))))))))

(deftest init-registers-project-by-default
  (let [root (temp-dir "ygg-init-registry")
        canonical-root (fs/canonical-path root)
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:project-id "demo"
                                     :name "Demo"})
            registered (registry/read-project "demo")]
        (is (= "demo" (:project-id result)))
        (is (= registry-path (:registry result)))
        (is (= true (:registered result)))
        (is (= true (:first-init result)))
        (is (= 1 (:init-count result)))
        (is (not (.exists (io/file root "project.edn"))))
        (is (= "demo" (:id registered)))
        (is (= canonical-root (get-in registered [:repos 0 :root])))
        (is (= "demo"
               (:project-id (edn/read-string (slurp (:project-ref result))))))))))

(deftest init-detects-git-root-when-run-from-subdirectory
  (let [root (temp-dir "ygg-init-git")
        canonical-root (fs/canonical-path root)
        subdir (io/file root "src" "app")
        registry-path (registry-path root)]
    (shell/sh "git" "-C" root "init")
    (.mkdirs subdir)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [out (.getPath (io/file root "project.edn"))
            result (init/init! (.getPath subdir) {:out out
                                                  :project-id "git-demo"})
            raw (edn/read-string (slurp out))]
        (is (= "git-demo" (:project-id result)))
        (is (= true (:first-init result)))
        (is (= canonical-root (get-in raw [:repos 0 :root])))))))

(deftest init-writes-workbench-project-config
  (let [root (temp-dir "ygg-init-workbench")
        canonical-root (fs/canonical-path root)
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (spit (io/file root "repos.json")
          (json/write-json-str {:repos {:app {:url "https://example.invalid/app.git"}
                                        :cli {:url "https://example.invalid/cli.git"}}}))
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "bench"
                                     :workbench? true
                                     :task "task-1"})
            raw (edn/read-string (slurp out))]
        (is (= "workbench" (:mode result)))
        (is (= 3 (:repos result)))
        (is (= true (:first-init result)))
        (is (= {:id "bench"
                :name "bench"
                :workbench-root canonical-root
                :repos [{:id "workbench"
                         :root canonical-root
                         :role :tooling}]
                :workbench-task "task-1"}
               raw))))))

(deftest init-refuses-to-overwrite-without-force
  (let [root (temp-dir "ygg-init-overwrite")
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (init/init! root {:out out
                        :project-id "first"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Project config already exists"
                            (init/init! root {:out out
                                              :project-id "second"})))
      (let [result (init/init! root {:out out
                                     :project-id "second"
                                     :force? true})]
        (is (= false (:first-init result)))
        (is (= 2 (:init-count result))))
      (is (= "second" (:id (edn/read-string (slurp out))))))))

(deftest init-records-first-run-once
  (let [root (temp-dir "ygg-init-first-run")
        first-out (.getPath (io/file root "first.edn"))
        second-out (.getPath (io/file root "second.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [first-result (init/init! root {:out first-out
                                           :project-id "first"})
            second-result (init/init! root {:out second-out
                                            :project-id "second"})
            onboarding (:onboarding (registry/read-registry))]
        (is (= true (:first-init first-result)))
        (is (= 1 (:init-count first-result)))
        (is (= false (:first-init second-result)))
        (is (= 2 (:init-count second-result)))
        (is (= registry/onboarding-schema (:schema onboarding)))
        (is (= 2 (:init-count onboarding)))
        (is (:first-init-at-ms onboarding))
        (is (:last-init-at-ms onboarding))))))

(deftest init-can-install-codex-harness-artifacts
  (let [root (temp-dir "ygg-init-harness")
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "demo"
                                     :harness "codex"
                                     :hooks? true
                                     :skill? true
                                     :mcp? true})
            agents (io/file root "AGENTS.md")
            hooks (io/file root ".codex" "hooks.json")
            skill (io/file root ".codex" "skills" "ygg" "SKILL.md")]
        (is (= true (get-in result [:setup :harness :installed])))
        (is (= "codex" (get-in result [:setup :harness :platform])))
        (is (= (canonical-file-path agents)
               (get-in result [:setup :harness :install :instructions])))
        (is (= (canonical-file-path hooks)
               (get-in result [:setup :harness :install :hooks])))
        (is (= (canonical-file-path skill)
               (get-in result [:setup :harness :install :skill])))
        (is (= {:command "ygg-mcp --config project.edn"}
               (get-in result [:setup :mcp])))
        (is (str/includes? (slurp agents) "Yggdrasil Agent Workflow"))
        (is (str/includes? (slurp skill) "name: ygg"))
        (is (some #(= :mcp (:kind %)) (:nextActions result)))))))

(deftest init-can-configure-harness-maintenance
  (let [root (temp-dir "ygg-init-maintenance-harness")
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "demo"
                                     :harness "codex"
                                     :maintenance "harness"
                                     :maintenance-reasoning "high"})
            config (edn/read-string (slurp out))
            maintenance (:maintenance config)
            executor (get-in maintenance [:worker :executors 0])]
        (is (= true (:enabled maintenance)))
        (is (= {:max-decisions 24
                :max-decisions-per-kind 8
                :max-infra-reviews 32
                :max-dependency-reviews 32
                :decision-batch-size 12
                :review-batch-size 16}
               (:work maintenance)))
        (is (= true (get-in maintenance [:worker :enabled])))
        (is (= ["scripts/ygg-maintenance-codex.sh"] (:command executor)))
        (is (= :command-harness (:type executor)))
        (is (= "high" (:reasoning executor)))
        (is (= #{:maintenance-decision :infra-review :dependency-review}
               (:kinds executor)))
        (is (= {:mode "harness"
                :enabled true
                :executor "codex"}
               (get-in result [:setup :maintenance])))
        (is (some #(= :maintenance-status (:kind %)) (:nextActions result)))))))

(deftest init-can-configure-openrouter-maintenance
  (let [root (temp-dir "ygg-init-maintenance-openrouter")
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (init/init! root {:out out
                        :project-id "demo"
                        :maintenance "openrouter"
                        :maintenance-model "deepseek/deepseek-v4-pro"})
      (let [executor (get-in (edn/read-string (slurp out))
                             [:maintenance :worker :executors 0])]
        (is (= :openai-compatible (:type executor)))
        (is (= :openrouter (:provider executor)))
        (is (= "deepseek/deepseek-v4-pro" (:model executor)))
        (is (= "YGG_OPENROUTER_API_KEY" (:env executor)))))))

(deftest next-commands-use-config-path-when-provided
  (let [root (temp-dir "ygg-init-next")
        out (.getPath (io/file root "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "demo"})]
        (is (some #(= (str "ygg sync " out " --check") %)
                  (:next result)))
        (is (some #(= {:kind :sync
                       :label "Index and validate project graph"
                       :command (str "ygg sync " out " --check")}
                      %)
                  (:nextActions result)))))))

(deftest next-actions-quote-shell-sensitive-paths
  (let [root (temp-dir "ygg-init next")
        out (.getPath (io/file root "Project Files" "project.edn"))
        registry-path (registry-path root)]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:out out
                                     :project-id "demo project"})]
        (is (some #(= (str "ygg sync '" out "' --check")
                      (:command %))
                  (:nextActions result)))
        (is (some #(= "ygg query \"where is this handled?\" --project 'demo project' --json"
                      (:command %))
                  (:nextActions result)))))))
