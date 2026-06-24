(ns ygg.project-registry-test
  (:require [ygg.fs :as fs]
            [ygg.project-registry :as registry]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest registry-resolves-project-from-current-directory
  (let [root (temp-dir "ygg-registry-root")
        child (io/file root "src" "app")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (.mkdirs child)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "demo"
                                 :name "Demo"
                                 :repos [{:id "app"
                                          :root (fs/canonical-path root)
                                          :role :application}]})
      (let [resolved (registry/resolve-project {:cwd (.getPath child)})]
        (is (= "demo" (:project-id resolved)))
        (is (= :cwd (:source resolved)))
        (is (= (fs/canonical-path root) (:root resolved)))))))

(deftest registry-active-project-is-used-when-cwd-does-not-match
  (let [root (temp-dir "ygg-registry-active")
        other (temp-dir "ygg-registry-other")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "demo"
                                 :repos [{:id "app"
                                          :root (fs/canonical-path root)
                                          :role :application}]})
      (registry/use-project! "demo" other)
      (let [resolved (registry/resolve-project {:cwd other})]
        (is (= "demo" (:project-id resolved)))
        (is (= :active-dir (:source resolved)))))))

(deftest registry-project-option-wins-over-cwd
  (let [left (temp-dir "ygg-registry-left")
        right (temp-dir "ygg-registry-right")
        registry-path (.getPath (io/file left ".config" "projects.edn"))]
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "left"
                                 :repos [{:id "app"
                                          :root (fs/canonical-path left)
                                          :role :application}]})
      (registry/upsert-project! {:id "right"
                                 :repos [{:id "app"
                                          :root (fs/canonical-path right)
                                          :role :application}]})
      (let [resolved (registry/resolve-project {:cwd left
                                                :project-id "right"})]
        (is (= "right" (:project-id resolved)))
        (is (= :project-option (:source resolved)))))))
