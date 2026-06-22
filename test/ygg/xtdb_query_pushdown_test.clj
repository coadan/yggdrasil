(ns ygg.xtdb-query-pushdown-test
  (:require [ygg.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(defn- cursor-row
  [id project-id revision active?]
  {:xt/id id
   :schema "ygg.graph-cursor/v1"
   :project-id project-id
   :mode :explore
   :root-ids []
   :focus-ids []
   :visited-ids []
   :frontier-ids []
   :basis {}
   :limits {}
   :revision revision
   :operation {}
   :active? active?
   :created-at-ms revision})

(deftest scoped-file-row-uses-constrained-project-repo-path-read
  (let [calls (atom [])]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints & [_ctx]]
                    (swap! calls conj [table constraints])
                    [{:xt/id "file:target"
                      :project-id "project-a"
                      :repo-id "app"
                      :path "src/app.clj"}])]
      (is (= "file:target"
             (:xt/id (store/scoped-file-row :xtdb
                                            "project-a"
                                            "app"
                                            "src/app.clj")))))
    (is (= [[(:files store/tables)
             {:path "src/app.clj"
              :project-id "project-a"
              :repo-id "app"}]]
           @calls))))

(deftest graph-cursors-use-constrained-project-read
  (let [calls (atom [])]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints & [_ctx]]
                    (swap! calls conj [table constraints])
                    [(cursor-row "cursor:inactive" "project-a" 1 false)
                     (cursor-row "cursor:active" "project-a" 2 true)])]
      (is (= ["cursor:active"]
             (mapv :xt/id (store/graph-cursors :xtdb
                                               {:project-id "project-a"})))))
    (is (= [[(:graph-cursors store/tables) {:project-id "project-a"}]]
           @calls))))

(deftest constrained-rows-supports-legacy-non-xtdb-row-stubs
  (let [calls (atom [])]
    (with-redefs [store/rows-by-field
                  (fn [_ table field value _ctx]
                    (swap! calls conj [table field value])
                    [{:xt/id "file:a"
                      :project-id "project-a"
                      :repo-id "app"
                      :active? true}
                     {:xt/id "file:inactive"
                      :project-id "project-a"
                      :repo-id "app"
                      :active? false}
                     {:xt/id "file:other"
                      :project-id "project-b"
                      :repo-id "app"
                      :active? true}])]
      (is (= ["file:a"]
             (mapv :xt/id
                   (store/constrained-rows
                    :xtdb
                    (:files store/tables)
                    {:project-id "project-a"
                     :repo-id "app"
                     :active? true})))))
    (is (= [[(:files store/tables) :repo-id "app"]]
           @calls))))

(deftest constrained-rows-supports-legacy-non-xtdb-relation-stubs
  (let [queries (atom [])]
    (with-redefs [store/q
                  (fn [_ query _ctx]
                    (swap! queries conj query)
                    [{:xt/id "edge:a"
                      :project-id "project-a"
                      :repo-id "app"
                      :relation :imports
                      :active? true}
                     {:xt/id "edge:inactive"
                      :project-id "project-a"
                      :repo-id "app"
                      :relation :imports
                      :active? false}])]
      (is (= ["edge:a"]
             (mapv :xt/id
                   (store/constrained-rows
                    :xtdb
                    (:edges store/tables)
                    {:project-id "project-a"
                     :repo-id "app"
                     :relation :imports
                     :active? true})))))
    (is (= :imports (last (first @queries))))))
