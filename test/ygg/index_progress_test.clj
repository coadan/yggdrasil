(ns ygg.index-progress-test
  (:require [ygg.index :as index]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(deftest index-repo-emits-progress-events
  (let [repo (temp-dir "ygg-progress-repo")
        xtdb-path (temp-dir "ygg-progress-xtdb")
        events (atom [])]
    (write-file! repo "src/app.clj" "(ns app)\n(defn value [] 1)\n")
    (write-file! repo "src/util.clj" "(ns util)\n(defn inc-value [x] (inc x))\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo!
                       xtdb
                       repo
                       {:project-id "progress-project"
                        :repo-id "app"
                        :progress-interval 2
                        :progress-fn #(swap! events conj %)})]
          (is (= :completed (:status summary))))))
    (is (= [:repo-start
            :scan-complete
            :plan-complete
            :extract-start
            :extract-progress
            :extract-progress
            :extract-complete
            :commit-start
            :commit-complete
            :delete-complete
            :dependency-start
            :dependency-complete
            :repo-complete]
           (mapv :phase @events)))
    (is (every? #(and (= "progress-project" (:project-id %))
                      (= "app" (:repo-id %)))
                @events))
    (is (= [1 2]
           (mapv :files-extracted
                 (filter #(= :extract-progress (:phase %)) @events))))
    (is (= 2 (:files-scanned (first (filter #(= :scan-complete (:phase %))
                                            @events)))))
    (is (= 2 (:files-indexed (first (filter #(= :repo-complete (:phase %))
                                            @events)))))))
