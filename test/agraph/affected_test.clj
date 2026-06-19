(ns agraph.affected-test
  (:require [agraph.affected :as affected]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(def project
  {:id "fixture"
   :repos [{:id "app"
            :root "/tmp/app"}
           {:id "admin"
            :root "/tmp/admin"}]})

(defn- file-row
  [repo path id]
  {:xt/id id
   :project-id "fixture"
   :repo-id repo
   :path path
   :kind :clojure
   :active? true})

(defn- node-row
  [id file-id path kind label]
  {:xt/id id
   :project-id "fixture"
   :repo-id "app"
   :file-id file-id
   :path path
   :kind kind
   :label label
   :active? true})

(defn- edge-row
  [id source target relation]
  {:xt/id id
   :project-id "fixture"
   :repo-id "app"
   :source-id source
   :target-id target
   :relation relation
   :confidence :extracted
   :active? true})

(def rows
  {:files [(file-row "app" "src/core.clj" "file:core")
           (file-row "app" "src/helper.clj" "file:helper")
           (file-row "app" "test/core_test.clj" "file:test")]
   :nodes [(node-row "node:core" "file:core" "src/core.clj" :namespace "app.core")
           (node-row "node:helper" "file:helper" "src/helper.clj" :namespace "app.helper")
           (node-row "node:test" "file:test" "test/core_test.clj" :test "core-test")]
   :edges [(edge-row "edge:test-core" "node:test" "node:core" :imports)
           (edge-row "edge:core-helper" "node:core" "node:helper" :imports)
           (edge-row "edge:core-defines" "node:core" "node:core" :defines)]
   :chunks []})

(deftest explicit-files-report-mechanical-neighbor-files
  (let [result (affected/analyze-rows project
                                      rows
                                      {:repo-id "app"
                                       :files ["src/core.clj"]})
        affected-files (:affectedFiles result)]
    (is (= "agraph.affected/v1" (:schema result)))
    (is (= {:mode "explicit-files"
            :repo-id "app"
            :supportedRelations ["declares-module"
                                 "imports"
                                 "imports-package"
                                 "references"
                                 "requires"
                                 "resolves"
                                 "uses"
                                 "version-of"]
            :testsOnly false}
           (:basis result)))
    (is (= [{:repo-id "app"
             :path "src/core.clj"
             :fileId "file:core"
             :kind "clojure"}]
           (:changedFiles result)))
    (is (= ["src/helper.clj" "test/core_test.clj"]
           (mapv :path affected-files)))
    (is (= [["outgoing-dependency"] ["incoming-dependent"]]
           (mapv :directions affected-files)))
    (is (= {:count 1
            :samples [{:edgeId "edge:core-defines"
                       :relation "defines"
                       :sourceNode "node:core"
                       :targetNode "node:core"}]}
           (:unsupportedIncidentEdges result)))))

(deftest tests-filter-uses-indexed-test-definitions-only
  (let [result (affected/analyze-rows project
                                      rows
                                      {:repo-id "app"
                                       :files ["src/core.clj"]
                                       :tests-only? true})]
    (is (= ["test/core_test.clj"]
           (mapv :path (:affectedFiles result))))
    (is (= [{:xt/id "node:test"
             :label "core-test"
             :kind :test
             :path "test/core_test.clj"}]
           (mapv #(dissoc % :source-line)
                 (get-in result [:affectedFiles 0 :testEvidence]))))
    (is (some #(= "tests-filter-boundary" (:kind %)) (:warnings result)))))

(deftest ambiguous-unscoped-paths-are-boundary-warnings
  (let [result (affected/analyze-rows project
                                      {:files [(file-row "app" "src/core.clj" "file:app-core")
                                               (file-row "admin" "src/core.clj" "file:admin-core")]
                                       :nodes []
                                       :edges []
                                       :chunks []}
                                      {:files ["src/core.clj"]})]
    (is (= ["ambiguous"] (mapv :status (:inputs result))))
    (is (= [] (:changedFiles result)))
    (is (some #(= "ambiguous-inputs" (:kind %)) (:warnings result)))))

(defn- temp-dir
  [prefix]
  (let [dir (.toFile
             (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (.getPath dir)))

(defn- sh!
  [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "command failed" {:args args
                                        :result result})))
    result))

(deftest git-diff-input-files-use-repo-roots
  (let [root (temp-dir "agraph-affected-git")
        file (io/file root "src/core.clj")
        project {:id "fixture"
                 :repos [{:id "app"
                          :root root}]}]
    (sh! "git" "-C" root "init")
    (sh! "git" "-C" root "config" "user.email" "test@example.invalid")
    (sh! "git" "-C" root "config" "user.name" "AGraph Test")
    (.mkdirs (.getParentFile file))
    (spit file "(ns core)\n")
    (sh! "git" "-C" root "add" ".")
    (sh! "git" "-C" root "commit" "-m" "initial")
    (spit file "(ns core)\n(def changed true)\n")
    (is (= [{:repo-id "app"
             :path "src/core.clj"}]
           (affected/input-files project {:since "HEAD"})))))
