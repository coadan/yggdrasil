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

(deftest constrained-rows-filter-by-equality-constraints
  (store/with-node (temp-dir "ygg-constrained-rows-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op :ygg/constrained-test
                      {:xt/id "row:match"
                       :project-id "demo"
                       :repo-id "app"
                       :active? true})
        (store/put-op :ygg/constrained-test
                      {:xt/id "row:other-repo"
                       :project-id "demo"
                       :repo-id "other"
                       :active? true})
        (store/put-op :ygg/constrained-test
                      {:xt/id "row:inactive"
                       :project-id "demo"
                       :repo-id "app"
                       :active? false})])
      (is (= ["row:match"]
             (mapv :xt/id
                   (store/constrained-rows xtdb
                                           :ygg/constrained-test
                                           {:project-id "demo"
                                            :repo-id "app"
                                            :active? true})))))))

(deftest rows-matching-any-token-pushes-scope-and-token-predicates
  (with-redefs [store/q
                (fn [xtdb sql ctx]
                  (is (= {:node :stub} xtdb))
                  (is (= (str "SELECT * FROM ygg.token_match WHERE "
                              "\"active?\" = ? AND "
                              "\"project_id\" = ? AND "
                              "\"repo_id\" = ? AND "
                              "(LOWER(CAST(\"path\" AS VARCHAR)) LIKE ? ESCAPE '\\\\' OR "
                              "LOWER(CAST(\"path\" AS VARCHAR)) LIKE ? ESCAPE '\\\\' OR "
                              "LOWER(CAST(\"label\" AS VARCHAR)) LIKE ? ESCAPE '\\\\' OR "
                              "LOWER(CAST(\"label\" AS VARCHAR)) LIKE ? ESCAPE '\\\\' OR "
                              "LOWER(CAST(\"kind\" AS VARCHAR)) LIKE ? ESCAPE '\\\\' OR "
                              "LOWER(CAST(\"kind\" AS VARCHAR)) LIKE ? ESCAPE '\\\\')")
                         sql))
                  (is (= {:args [true
                                 "demo"
                                 "app"
                                 "%astro%"
                                 "%bootstrap%"
                                 "%astro%"
                                 "%bootstrap%"
                                 "%astro%"
                                 "%bootstrap%"]
                          :valid-at t1}
                         ctx))
                  [{:xt/id "row:match"}])]
    (is (= ["row:match"]
           (mapv :xt/id
                 (store/rows-matching-any-token
                  {:node :stub}
                  :ygg/token-match
                  [:path :label :kind]
                  ["astro" "bootstrap"]
                  {:project-id "demo"
                   :repo-id "app"
                   :active? true}
                  {:valid-at t1}))))))

(deftest constrained-rows-fallback-filters-test-stub-rows
  (with-redefs [store/all-rows (fn [_ table]
                                 (is (= :ygg/constrained-test table))
                                 [{:xt/id "row:match"
                                   :project-id "demo"
                                   :active? true}
                                  {:xt/id "row:other"
                                   :project-id "other"
                                   :active? true}])]
    (is (= ["row:match"]
           (mapv :xt/id
                 (store/constrained-rows :stub
                                         :ygg/constrained-test
                                         {:project-id "demo"
                                          :active? true}))))))

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
