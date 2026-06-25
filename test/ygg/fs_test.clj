(ns ygg.fs-test
  (:require [ygg.fs :as fs]
            [ygg.ripgrep :as ripgrep]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir
  [prefix]
  (let [path (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile path))))

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(deftest scan-files-prefers-ripgrep-discovery-and-keeps-ygg-filters
  (let [root (temp-dir "ygg-fs-rg")]
    (spit-file! root "src/kept.clj" "(ns kept)\n")
    (spit-file! root ".env" "API_KEY=secret\n")
    (spit-file! root ".github/workflows/ci.yml" "name: ci\n")
    (spit-file! root ".workbench/api/server.log" "hidden artifact\n")
    (spit-file! root ".worktrees/feature/src/generated.clj" "(ns generated)\n")
    (spit-file! root "target/generated.clj" "(ns generated)\n")
    (spit-file! root ".clj-kondo/config.edn" "{}\n")
    (with-redefs [ripgrep/files (fn [_root opts]
                                  (is (false? (:hidden? opts)))
                                  (is (some #{"target/**"} (:ignore-globs opts)))
                                  {:exit 0
                                   :elapsed-ms 7
                                   :paths ["src/kept.clj"
                                           "target/generated.clj"
                                           ".clj-kondo/config.edn"]
                                   :diagnostics []})
                  shell/sh (fn [& args]
                             (throw (ex-info "git fallback should not run after ripgrep succeeds"
                                             {:args args})))]
      (let [files (fs/scan-files root)
            coverage (fs/scan-file-coverage root)]
        (is (= :ripgrep (-> files meta :discovery :backend)))
        (is (= 7 (-> files meta :discovery :elapsed-ms)))
        (is (= [".env" ".github/workflows/ci.yml" "src/kept.clj"]
               (mapv :path files)))
        (is (= :ripgrep (get-in coverage [:discovery :backend])))
        (is (= [".env" ".github/workflows/ci.yml" "src/kept.clj"]
               (mapv :path (:files coverage))))))))

(deftest scan-files-falls-back-to-git-when-ripgrep-is-unavailable
  (let [root (temp-dir "ygg-fs-git")]
    (spit-file! root "src/kept.clj" "(ns kept)\n")
    (spit-file! root "ignored/skip.clj" "(ns skip)\n")
    (spit-file! root ".gitignore" "ignored/\n")
    (is (zero? (:exit (shell/sh "git" "-C" root "init"))))
    (with-redefs [ripgrep/files (fn [_root _opts]
                                  {:exit nil
                                   :elapsed-ms 1
                                   :paths []
                                   :diagnostics [{:kind :unavailable}]})]
      (let [files (fs/scan-files root)
            discovery (-> files meta :discovery)]
        (is (= :git (:backend discovery)))
        (is (= ["src/kept.clj"] (mapv :path files)))
        (is (= [{:backend :ripgrep
                 :status :unavailable}]
               (:fallbacks discovery)))))))

(deftest scan-files-falls-back-to-filesystem-when-ripgrep-and-git-are-unavailable
  (let [root (temp-dir "ygg-fs-filesystem")]
    (spit-file! root "src/kept.clj" "(ns kept)\n")
    (spit-file! root ".env" "API_KEY=secret\n")
    (spit-file! root ".workbench/api/server.log" "hidden artifact\n")
    (spit-file! root ".worktrees/feature/src/generated.clj" "(ns generated)\n")
    (spit-file! root "target/generated.clj" "(ns generated)\n")
    (with-redefs [ripgrep/files (fn [_root _opts]
                                  {:exit nil
                                   :elapsed-ms 1
                                   :paths []
                                   :diagnostics [{:kind :unavailable}]})
                  shell/sh (fn [& _args]
                             {:exit 128
                              :out ""
                              :err "not a git repository"})]
      (testing "filesystem discovery still applies Yggdrasil path filtering"
        (let [files (fs/scan-files root)
              discovery (-> files meta :discovery)]
          (is (= :filesystem (:backend discovery)))
          (is (= [".env" "src/kept.clj"] (mapv :path files)))
          (is (= [{:backend :ripgrep
                   :status :unavailable}
                  {:backend :git
                   :status :unavailable}]
                 (:fallbacks discovery))))))))
