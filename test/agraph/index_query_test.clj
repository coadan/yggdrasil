(ns agraph.index-query-test
  (:require [agraph.fs :as fs]
            [agraph.index :as index]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest indexes-and-queries-sample-repo
  (let [xtdb-path (temp-dir "agraph-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo! xtdb repo {})
              search (query/semantic-query xtdb "greeting shout" {:limit 5})
              deps (query/deps xtdb "sample.core")
              path (query/graph-path xtdb "sample.core" "sample.util")
              report (query/report xtdb)]
          (is (= :completed (:status summary)))
          (is (pos? (get-in summary [:stats :files-indexed])))
          (is (seq search))
          (is (seq (:outgoing deps)))
          (is (not-any? #(= :defines (:relation %)) (:outgoing deps)))
          (is (seq (:definitions deps)))
          (is (= ["sample.core" "sample.util"] (mapv :label path)))
          (is (pos? (get-in report [:counts :nodes])))
          (is (pos? (get-in report [:counts :search-docs]))))))))

(deftest graph-profile-skips-query-rows-and-query-profile-restores-them
  (let [xtdb-path (temp-dir "agraph-index-profile-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [graph-summary (index/index-repo! xtdb repo {:index-profile :graph})
              graph-report (query/report xtdb)]
          (is (= :graph (:index-profile graph-summary)))
          (is (pos? (get-in graph-summary [:stats :nodes])))
          (is (zero? (get-in graph-summary [:stats :chunks])))
          (is (zero? (get-in graph-summary [:stats :search-docs])))
          (is (zero? (get-in graph-report [:counts :search-docs])))
          (let [query-summary (index/index-repo! xtdb repo {:index-profile :query})
                query-report (query/report xtdb)]
            (is (= :query (:index-profile query-summary)))
            (is (pos? (get-in query-summary [:stats :files-indexed])))
            (is (pos? (get-in query-summary [:stats :chunks])))
            (is (pos? (get-in query-summary [:stats :search-docs])))
            (is (pos? (get-in query-report [:counts :search-docs])))))))))

(deftest report-respects-project-scope-and-readable-missing-targets
  (store/with-node (temp-dir "agraph-report-scope-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :nodes)
                      {:xt/id "node:p1:namespace:alpha"
                       :project-id "p1"
                       :label "alpha"
                       :kind :namespace
                       :active? true})
        (store/put-op (store/table-ref :nodes)
                      {:xt/id "node:p2:namespace:beta"
                       :project-id "p2"
                       :label "beta"
                       :kind :namespace
                       :active? true})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:p1:alpha:external"
                       :project-id "p1"
                       :source-id "node:p1:namespace:alpha"
                       :target-id "project:p1:repo:r1:node:namespace:clojure.string"
                       :relation :requires
                       :active? true})])
      (is (= 2 (get-in (query/report xtdb) [:counts :nodes])))
      (is (= 1 (get-in (query/report xtdb {:project-id "p1"}) [:counts :nodes])))
      (is (contains? (set (map (comp :label :node)
                               (:top-nodes (query/report xtdb {:project-id "p1"}))))
                     "clojure.string")))))

(deftest scan-files-respects-gitignore
  (let [repo (io/file (temp-dir "agraph-gitignore-repo"))
        src-dir (io/file repo "src")
        ignored-dir (io/file repo "ignored")]
    (.mkdirs src-dir)
    (.mkdirs ignored-dir)
    (spit (io/file repo ".gitignore") "ignored/\n*.secret.edn\n")
    (spit (io/file src-dir "kept.clj") "(ns kept)")
    (spit (io/file src-dir "scratch.secret.edn") "{:secret true}")
    (spit (io/file ignored-dir "ignored.clj") "(ns ignored)")
    (spit (io/file repo "package-lock.json") "{\"packages\":{}}")
    (.mkdirs (io/file repo ".clj-kondo"))
    (spit (io/file repo ".clj-kondo" "config.edn") "{:linters {}}")
    (.mkdirs (io/file repo ".workbench" "repos" "nested" "src"))
    (spit (io/file repo ".workbench" "repos" "nested" "src" "ignored.clj")
          "(ns nested.ignored)")
    (is (zero? (:exit (shell/sh "git" "-C" (.getPath repo) "init"))))
    (let [paths (set (map :path (fs/scan-files repo)))]
      (is (contains? paths "src/kept.clj"))
      (is (not (contains? paths "src/scratch.secret.edn")))
      (is (not (contains? paths "ignored/ignored.clj")))
      (is (not (contains? paths "package-lock.json")))
      (is (not (contains? paths ".clj-kondo/config.edn")))
      (is (not (contains? paths ".workbench/repos/nested/src/ignored.clj"))))))

(deftest index-writes-temporal-source-snapshots
  (let [xtdb-path (temp-dir "agraph-index-temporal-xtdb")
        repo (io/file (temp-dir "agraph-index-temporal-repo"))
        src (io/file repo "src" "demo")]
    (.mkdirs src)
    (spit (io/file src "a.clj") "(ns demo.a (:require [demo.b :as b]))\n(defn call [] (b/value))\n")
    (spit (io/file src "b.clj") "(ns demo.b)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [clock (atom 1767225600000)]
          (with-redefs [index/now-ms (fn []
                                       (let [value @clock]
                                         (swap! clock + 1000)
                                         value))]
            (let [first-summary (index/index-repo! xtdb (.getPath repo) {})
                  first-valid (:valid-from first-summary)]
              (spit (io/file src "a.clj") "(ns demo.a)\n(defn call [] 1)\n")
              (.delete (io/file src "b.clj"))
              (let [second-summary (index/index-repo! xtdb (.getPath repo) {})
                    second-valid (:valid-from second-summary)]
                (is (= :synthetic
                       (:basis-kind (store/row-by-id xtdb
                                                     (store/table-ref :source-snapshots)
                                                     (:snapshot-id first-summary)
                                                     {:valid-at first-valid}))))
                (is (= :completed
                       (:status (store/row-by-id xtdb
                                                 (store/table-ref :index-runs)
                                                 (:run-id second-summary)
                                                 {:valid-at second-valid}))))
                (is (seq (:outgoing (query/deps xtdb
                                                "demo.a"
                                                {:read-context {:valid-at first-valid}}))))
                (is (empty? (:outgoing (query/deps xtdb
                                                   "demo.a"
                                                   {:read-context {:valid-at second-valid}}))))
                (is (some? (query/find-node xtdb
                                            "demo.b"
                                            {:read-context {:valid-at first-valid}})))
                (is (nil? (query/find-node xtdb
                                           "demo.b"
                                           {:read-context {:valid-at second-valid}})))))))))))

(deftest index-skips-by-content-and-extractor-fingerprint
  (let [xtdb-path (temp-dir "agraph-index-fingerprint-xtdb")
        repo (io/file (temp-dir "agraph-index-fingerprint-repo"))
        src (io/file repo "src")]
    (.mkdirs src)
    (spit (io/file src "demo.clj") "(ns demo)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [first-summary (index/index-repo! xtdb (.getPath repo) {})
              second-summary (index/index-repo! xtdb (.getPath repo) {})
              row (store/file-row xtdb "src/demo.clj")]
          (is (= 1 (get-in first-summary [:stats :files-indexed])))
          (is (= 1 (get-in second-summary [:stats :files-skipped])))
          (is (string? (:extractor-fingerprint row)))
          (with-redefs [index/extractor-fingerprint (fn [_]
                                                      "extractor:test-changed")]
            (let [third-summary (index/index-repo! xtdb (.getPath repo) {})
                  changed-row (store/file-row xtdb "src/demo.clj")]
              (is (= 1 (get-in third-summary [:stats :files-indexed])))
              (is (= "extractor:test-changed"
                     (:extractor-fingerprint changed-row))))))))))
