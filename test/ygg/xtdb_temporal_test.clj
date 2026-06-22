(ns ygg.xtdb-temporal-test
  (:require [ygg.graph :as graph]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(def t1
  #inst "2026-01-01T00:00:00Z")

(def t2
  #inst "2026-02-01T00:00:00Z")

(def t3
  #inst "2026-03-01T00:00:00Z")

(deftest instant-normalizes-xtdb-time-values
  (is (= t1 (store/instant (java.time.ZonedDateTime/parse "2026-01-01T00:00:00Z[UTC]"))))
  (is (= t1 (store/instant (java.time.OffsetDateTime/parse "2026-01-01T00:00:00Z")))))

(deftest temporal-ops-read-valid-time-history
  (let [table :ygg/temporal-test
        id "fact:alpha"]
    (store/with-node (temp-dir "ygg-temporal-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op table {:xt/id id :label "Alpha" :version 1} {:valid-from t1})
          (store/put-op table {:xt/id id :label "Alpha" :version 2} {:valid-from t2})
          (store/delete-op table id {:valid-from t3})])
        (is (= 1 (:version (store/row-by-id xtdb
                                            table
                                            id
                                            (store/read-context
                                             {:valid-at #inst "2026-01-15T00:00:00Z"})))))
        (is (= 2 (:version (store/row-by-id xtdb
                                            table
                                            id
                                            (store/read-context
                                             {:valid-at #inst "2026-02-15T00:00:00Z"})))))
        (is (nil? (store/row-by-id xtdb
                                   table
                                   id
                                   (store/read-context
                                    {:valid-at #inst "2026-03-15T00:00:00Z"}))))))))

(deftest temporal-bundle-writes-snapshot-run-puts-and-deletes
  (store/with-node (temp-dir "ygg-temporal-bundle-xtdb")
    (fn [xtdb]
      (store/commit-temporal-bundle!
       xtdb
       {:snapshot {:xt/id "snapshot:test:repo:1"
                   :project-id "test"
                   :repo-id "repo"
                   :basis-kind :synthetic
                   :basis-instant t1
                   :dirty? true}
        :run {:xt/id "run:test:1"
              :project-id "test"
              :repo-id "repo"
              :snapshot-id "snapshot:test:repo:1"
              :valid-from t1
              :status :completed}
        :valid-from t1
        :puts {:nodes [{:xt/id "node:test:repo:namespace:alpha"
                        :project-id "test"
                        :repo-id "repo"
                        :label "alpha"
                        :kind :namespace}]}})
      (store/commit-temporal-bundle!
       xtdb
       {:valid-from t2
        :deletes {:nodes ["node:test:repo:namespace:alpha"]}})
      (is (= "snapshot:test:repo:1"
             (:xt/id (store/row-by-id xtdb
                                      (store/table-ref :source-snapshots)
                                      "snapshot:test:repo:1"
                                      {:valid-at t1}))))
      (is (= :completed
             (:status (store/row-by-id xtdb
                                       (store/table-ref :index-runs)
                                       "run:test:1"
                                       {:valid-at t1}))))
      (is (= "alpha"
             (:label (store/row-by-id xtdb
                                      (store/table-ref :nodes)
                                      "node:test:repo:namespace:alpha"
                                      {:valid-at #inst "2026-01-15T00:00:00Z"}))))
      (is (nil? (store/row-by-id xtdb
                                 (store/table-ref :nodes)
                                 "node:test:repo:namespace:alpha"
                                 {:valid-at #inst "2026-02-15T00:00:00Z"}))))))

(deftest read-context-threads-through-query-and-graph
  (store/with-node (temp-dir "ygg-temporal-query-xtdb")
    (fn [xtdb]
      (let [alpha {:xt/id "node:test:repo:namespace:alpha"
                   :project-id "test"
                   :repo-id "repo"
                   :label "alpha"
                   :kind :namespace
                   :active? true}
            beta {:xt/id "node:test:repo:namespace:beta"
                  :project-id "test"
                  :repo-id "repo"
                  :label "beta"
                  :kind :namespace
                  :active? true}
            edge {:xt/id "edge:test:repo:alpha:requires:beta"
                  :project-id "test"
                  :repo-id "repo"
                  :source-id (:xt/id alpha)
                  :target-id (:xt/id beta)
                  :relation :requires
                  :confidence :high
                  :active? true}]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :nodes) alpha {:valid-from t1})
          (store/put-op (store/table-ref :nodes) beta {:valid-from t1})
          (store/put-op (store/table-ref :edges) edge {:valid-from t1})
          (store/delete-op (store/table-ref :edges) (:xt/id edge) {:valid-from t2})])
        (is (= [:requires]
               (mapv :relation (:outgoing (query/deps xtdb
                                                      "alpha"
                                                      {:project-id "test"
                                                       :read-context
                                                       {:valid-at #inst "2026-01-15T00:00:00Z"}})))))
        (is (empty? (:outgoing (query/deps xtdb
                                           "alpha"
                                           {:project-id "test"
                                            :read-context
                                            {:valid-at #inst "2026-02-15T00:00:00Z"}}))))
        (is (= 1
               (count (:edges (graph/deps-graph xtdb
                                                "alpha"
                                                {:project-id "test"
                                                 :read-context
                                                 {:valid-at #inst "2026-01-15T00:00:00Z"}})))))
        (is (empty? (:edges (graph/deps-graph xtdb
                                              "alpha"
                                              {:project-id "test"
                                               :read-context
                                               {:valid-at #inst "2026-02-15T00:00:00Z"}}))))))))
