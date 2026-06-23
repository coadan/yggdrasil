(ns ygg.benchmark-repos-test
  (:require [ygg.benchmark-repos :as benchmark-repos]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (-> (java.nio.file.Files/createTempDirectory prefix
                                               (make-array java.nio.file.attribute.FileAttribute 0))
      .toFile
      .getCanonicalFile))

(defn- mkdirs!
  [path]
  (.mkdirs (io/file path)))

(defn- spit-edn!
  [root name value]
  (let [file (io/file root name)]
    (spit file (pr-str value))
    (.getPath file)))

(deftest check-repos-reports-common-cache-and-legacy-status
  (let [root (temp-dir "ygg-benchmark-repos")
        cache (io/file root "cache")
        legacy (io/file root "legacy")
        manifest-path (spit-edn!
                       root
                       "repos.edn"
                       {:schema "ygg.benchmark.repos/v1"
                        :cache-root (.getPath cache)
                        :legacy-cache-root (.getPath legacy)
                        :repos [{:id "ready"
                                 :url "https://example.test/ready.git"
                                 :dir "ready"}
                                {:id "missing"
                                 :url "https://example.test/missing.git"
                                 :dir "missing"}]})]
    (mkdirs! (io/file cache "ready" ".git"))
    (mkdirs! (io/file legacy "missing" ".git"))
    (let [check (benchmark-repos/check-repos {:manifest-path manifest-path})
          by-id (into {} (map (juxt :id identity)) (:repos check))]
      (is (= "failed" (:status check)))
      (is (= {:repos 2
              :ready 1
              :missing 1
              :not-git 0
              :missing-shas 0
              :unknown 0}
             (:counts check)))
      (is (= :ready (get-in by-id ["ready" :status])))
      (is (= :missing (get-in by-id ["missing" :status])))
      (is (true? (get-in by-id ["missing" :legacy-present]))))))

(deftest check-repos-can-be-narrowed-to-suite-repos
  (let [root (temp-dir "ygg-benchmark-repos-suite")
        cache (io/file root "cache")
        manifest-path (spit-edn!
                       root
                       "repos.edn"
                       {:schema "ygg.benchmark.repos/v1"
                        :cache-root (.getPath cache)
                        :repos [{:id "ready"
                                 :url "https://example.test/ready.git"
                                 :dir "ready"}]})
        suite-path (spit-edn!
                    root
                    "suite.edn"
                    {:id "suite"
                     :repos [{:id "ready"
                              :root "../cache/ready"}
                             {:id "unknown"
                              :root "../cache/unknown"}]})]
    (mkdirs! (io/file cache "ready" ".git"))
    (let [check (benchmark-repos/check-repos {:manifest-path manifest-path
                                              :suite-path suite-path})]
      (is (= "failed" (:status check)))
      (is (= {:repos 2
              :ready 1
              :missing 0
              :not-git 0
              :missing-shas 0
              :unknown 1}
             (:counts check)))
      (is (= ["unknown"] (:unknown-repos check)))
      (is (= ["ready"] (mapv :id (:repos check)))))))

(deftest check-repos-resolves-included-suite-repos
  (let [root (temp-dir "ygg-benchmark-repos-included-suite")
        cache (io/file root "cache")
        manifest-path (spit-edn!
                       root
                       "repos.edn"
                       {:schema "ygg.benchmark.repos/v1"
                        :cache-root (.getPath cache)
                        :repos [{:id "included"
                                 :url "https://example.test/included.git"
                                 :dir "included"}]})
        included-suite-path (spit-edn!
                             root
                             "included.edn"
                             {:id "included-suite"
                              :repos [{:id "included"
                                       :root "../cache/included"}]
                              :cases [{:id "case-1"
                                       :repo-id "included"
                                       :issue {:title "included"}}]})
        suite-path (spit-edn!
                    root
                    "suite.edn"
                    {:id "suite"
                     :include-suites [(.getName (io/file included-suite-path))]})]
    (mkdirs! (io/file cache "included" ".git"))
    (let [check (benchmark-repos/check-repos {:manifest-path manifest-path
                                              :suite-path suite-path})]
      (is (= "passed" (:status check)))
      (is (= {:repos 1
              :ready 1
              :missing 0
              :not-git 0
              :missing-shas 0
              :unknown 0}
             (:counts check)))
      (is (= ["included"] (mapv :id (:repos check)))))))

(deftest check-repos-honors-filtered-included-suite-repos
  (let [root (temp-dir "ygg-benchmark-repos-filtered-suite")
        cache (io/file root "cache")
        manifest-path (spit-edn!
                       root
                       "repos.edn"
                       {:schema "ygg.benchmark.repos/v1"
                        :cache-root (.getPath cache)
                        :repos [{:id "fast"
                                 :url "https://example.test/fast.git"
                                 :dir "fast"}
                                {:id "slow"
                                 :url "https://example.test/slow.git"
                                 :dir "slow"}]})
        included-suite-path (spit-edn!
                             root
                             "included.edn"
                             {:id "included-suite"
                              :repos [{:id "fast"
                                       :root "../cache/fast"}
                                      {:id "slow"
                                       :root "../cache/slow"}]
                              :cases [{:id "fast-case"
                                       :repo-id "fast"
                                       :issue {:title "fast"}}
                                      {:id "slow-case"
                                       :repo-id "slow"
                                       :issue {:title "slow"}}]})
        suite-path (spit-edn!
                    root
                    "suite.edn"
                    {:id "suite"
                     :include-suites [{:path (.getName (io/file included-suite-path))
                                       :case-ids ["fast-case"]}]})]
    (mkdirs! (io/file cache "fast" ".git"))
    (let [check (benchmark-repos/check-repos {:manifest-path manifest-path
                                              :suite-path suite-path})]
      (is (= "passed" (:status check)))
      (is (= {:repos 1
              :ready 1
              :missing 0
              :not-git 0
              :missing-shas 0
              :unknown 0}
             (:counts check)))
      (is (= ["fast"] (mapv :id (:repos check)))))))
