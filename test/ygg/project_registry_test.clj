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
        (is (= :project-ref (:source resolved)))))))

(deftest registry-resolves-nearest-repo-local-project-reference
  (let [root (temp-dir "ygg-registry-ref")
        checkout (io/file root "checkout")
        nested (io/file checkout "src" "app")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (.mkdirs nested)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "demo"
                                 :repos [{:id "canonical"
                                          :root (temp-dir "ygg-registry-canonical")
                                          :role :application}]})
      (let [ref-path (registry/write-project-ref! (.getPath checkout) "demo")
            resolved (registry/resolve-project {:cwd (.getPath nested)})]
        (is (= ref-path (:project-ref resolved)))
        (is (= "demo" (:project-id resolved)))
        (is (= :project-ref (:source resolved)))
        (is (= (fs/canonical-path (.getPath checkout)) (:root resolved)))))))

(deftest registry-repo-local-project-reference-wins-over-root-match
  (let [root (temp-dir "ygg-registry-ref-precedence")
        nested (io/file root "src" "app")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (.mkdirs nested)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "demo"
                                 :repos [{:id "app"
                                          :root (fs/canonical-path root)
                                          :role :application}]})
      (let [ref-path (registry/write-project-ref! root "demo")
            resolved (registry/resolve-project {:cwd (.getPath nested)})]
        (is (= "demo" (:project-id resolved)))
        (is (= :project-ref (:source resolved)))
        (is (= ref-path (:project-ref resolved)))
        (is (= (fs/canonical-path root) (:root resolved)))))))

(deftest registry-use-project-writes-repo-local-reference
  (let [root (temp-dir "ygg-registry-use-ref")
        checkout (io/file root "checkout")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (.mkdirs checkout)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "demo"
                                 :repos [{:id "app"
                                          :root (temp-dir "ygg-registry-app")
                                          :role :application}]})
      (let [result (registry/use-project! "demo" (.getPath checkout))]
        (is (= (registry/project-ref-path (.getPath checkout))
               (:project-ref result)))
        (is (= "demo"
               (:project-id (read-string (slurp (:project-ref result))))))))))

(deftest registry-registers-existing-project-config-by-reference
  (let [root (temp-dir "ygg-registry-config-ref")
        config-path (.getPath (io/file root "project.edn"))
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (spit config-path
          (pr-str {:id "demo"
                   :name "Demo"
                   :repos [{:id "app"
                            :root (fs/canonical-path root)
                            :role :application}]}))
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [result (registry/register-project-config! config-path)]
        (is (= "ygg.project.registry.register/v1" (:schema result)))
        (is (= "demo" (:project-id result)))
        (is (= (fs/canonical-path config-path) (:config-path result)))
        (is (= {:id "demo"
                :config-path (fs/canonical-path config-path)}
               (get-in (registry/read-registry) [:projects "demo"])))
        (is (= :cwd (:source (registry/resolve-project {:cwd root}))))
        (spit config-path
              (pr-str {:id "demo"
                       :name "Renamed"
                       :repos [{:id "app"
                                :root (fs/canonical-path root)
                                :role :application}]}))
        (is (= "Renamed" (:name (registry/read-project "demo"))))))))

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
