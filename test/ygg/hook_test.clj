(ns ygg.hook-test
  (:require [ygg.hook :as hook]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- git-repo!
  [root]
  (.mkdirs (io/file root ".git" "hooks"))
  root)

(defn- project
  [root]
  {:id "fixture"
   :path (str root "/project.edn")
   :repos [{:id "app"
            :root root
            :role :application}]})

(deftest install-preserves-existing-hook-content-and-fails-open
  (let [root (git-repo! (temp-dir "ygg-hook-install"))
        hook-file (io/file root ".git" "hooks" "post-commit")]
    (spit hook-file "#!/usr/bin/env sh\necho existing\n")
    (let [result (hook/install! (project root)
                                {:config-path (str root "/project.edn")
                                 :ygg-bin "/bin/false"})]
      (is (= hook/schema (:schema result)))
      (is (= "installed" (get-in result [:repos 0 :status])))
      (let [content (slurp hook-file)]
        (is (str/includes? content "echo existing"))
        (is (str/includes? content "# BEGIN YGG HOOK"))
        (is (str/includes? content "'/bin/false' sync"))
        (is (str/includes? content "|| true"))))))

(deftest install-is-idempotent
  (let [root (git-repo! (temp-dir "ygg-hook-idempotent"))
        project (project root)
        opts {:config-path (str root "/project.edn")}]
    (hook/install! project opts)
    (let [hook-file (io/file root ".git" "hooks" "post-commit")
          first-content (slurp hook-file)]
      (hook/install! project opts)
      (is (= first-content (slurp hook-file))))))

(deftest uninstall-removes-only-owned-hook-block
  (let [root (git-repo! (temp-dir "ygg-hook-uninstall"))
        hook-file (io/file root ".git" "hooks" "post-commit")
        project (project root)]
    (spit hook-file "#!/usr/bin/env sh\necho existing\n")
    (hook/install! project {:config-path (str root "/project.edn")})
    (let [result (hook/uninstall! project)
          content (slurp hook-file)]
      (is (= hook/schema (:schema result)))
      (is (= true (get-in result [:repos 0 :hooks 0 :removed])))
      (is (str/includes? content "echo existing"))
      (is (not (str/includes? content "# BEGIN YGG HOOK"))))))

(deftest status-reports-installed-hooks
  (let [root (git-repo! (temp-dir "ygg-hook-status"))
        project (project root)]
    (hook/install! project {:config-path (str root "/project.edn")})
    (let [result (hook/status project)]
      (is (= hook/schema (:schema result)))
      (is (= true (get-in result [:repos 0 :git?])))
      (is (every? :installed? (get-in result [:repos 0 :hooks]))))))

(deftest install-skips-non-git-repos
  (let [root (temp-dir "ygg-hook-skip")
        result (hook/install! (project root) {:config-path (str root "/project.edn")})]
    (is (= "skipped" (get-in result [:repos 0 :status])))
    (is (= "not-a-git-repo" (get-in result [:repos 0 :reason])))))
