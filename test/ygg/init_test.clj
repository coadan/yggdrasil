(ns ygg.init-test
  (:require [ygg.fs :as fs]
            [ygg.init :as init]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest init-writes-plain-repo-project-config
  (let [root (temp-dir "ygg-init-repo")
        canonical-root (fs/canonical-path root)
        out (.getPath (io/file root "project.edn"))
        result (init/init! root {:out out
                                 :project-id "demo"
                                 :name "Demo"})]
    (is (= init/schema (:schema result)))
    (is (= "demo" (:project-id result)))
    (is (= 1 (:repos result)))
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
             (edn/read-string (slurp (:project-ref result))))))))

(deftest init-registers-project-by-default
  (let [root (temp-dir "ygg-init-registry")
        canonical-root (fs/canonical-path root)
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (init/init! root {:project-id "demo"
                                     :name "Demo"})
            registered (registry/read-project "demo")]
        (is (= "demo" (:project-id result)))
        (is (= registry-path (:registry result)))
        (is (= true (:registered result)))
        (is (not (.exists (io/file root "project.edn"))))
        (is (= "demo" (:id registered)))
        (is (= canonical-root (get-in registered [:repos 0 :root])))
        (is (= "demo"
               (:project-id (edn/read-string (slurp (:project-ref result))))))))))

(deftest init-detects-git-root-when-run-from-subdirectory
  (let [root (temp-dir "ygg-init-git")
        canonical-root (fs/canonical-path root)
        subdir (io/file root "src" "app")]
    (shell/sh "git" "-C" root "init")
    (.mkdirs subdir)
    (let [out (.getPath (io/file root "project.edn"))
          result (init/init! (.getPath subdir) {:out out
                                                :project-id "git-demo"})
          raw (edn/read-string (slurp out))]
      (is (= "git-demo" (:project-id result)))
      (is (= canonical-root (get-in raw [:repos 0 :root]))))))

(deftest init-writes-workbench-project-config
  (let [root (temp-dir "ygg-init-workbench")
        canonical-root (fs/canonical-path root)
        out (.getPath (io/file root "project.edn"))]
    (spit (io/file root "repos.json")
          (json/write-json-str {:repos {:app {:url "https://example.invalid/app.git"}
                                        :cli {:url "https://example.invalid/cli.git"}}}))
    (let [result (init/init! root {:out out
                                   :project-id "bench"
                                   :workbench? true
                                   :task "task-1"})
          raw (edn/read-string (slurp out))]
      (is (= "workbench" (:mode result)))
      (is (= 3 (:repos result)))
      (is (= {:id "bench"
              :name "bench"
              :workbench-root canonical-root
              :repos [{:id "workbench"
                       :root canonical-root
                       :role :tooling}]
              :workbench-task "task-1"}
             raw)))))

(deftest init-refuses-to-overwrite-without-force
  (let [root (temp-dir "ygg-init-overwrite")
        out (.getPath (io/file root "project.edn"))]
    (init/init! root {:out out
                      :project-id "first"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Project config already exists"
                          (init/init! root {:out out
                                            :project-id "second"})))
    (init/init! root {:out out
                      :project-id "second"
                      :force? true})
    (is (= "second" (:id (edn/read-string (slurp out)))))))

(deftest next-commands-use-config-path-when-provided
  (let [root (temp-dir "ygg-init-next")
        out (.getPath (io/file root "project.edn"))
        result (init/init! root {:out out
                                 :project-id "demo"})]
    (is (some #(= (str "ygg sync " out " --check") %)
              (:next result)))
    (is (some #(= {:kind :sync
                   :label "Index and validate project graph"
                   :command (str "ygg sync " out " --check")}
                  %)
              (:nextActions result)))))

(deftest next-actions-quote-shell-sensitive-paths
  (let [root (temp-dir "ygg-init next")
        out (.getPath (io/file root "Project Files" "project.edn"))
        result (init/init! root {:out out
                                 :project-id "demo project"})]
    (is (some #(= (str "ygg sync '" out "' --check")
                  (:command %))
              (:nextActions result)))
    (is (some #(= "ygg query \"where is this handled?\" --project 'demo project' --json"
                  (:command %))
              (:nextActions result)))))
