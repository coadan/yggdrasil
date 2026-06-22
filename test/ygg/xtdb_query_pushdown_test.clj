(ns ygg.xtdb-query-pushdown-test
  (:require [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.string :as str]
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

(deftest edge-rows-touching-ids-uses-bounded-xtql-unify-queries
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (let [idx (count @calls)]
                      (swap! calls conj {:query query
                                         :ctx ctx})
                      [(case idx
                         0 {:xt/id "edge:out"
                            :source-id "node:a"
                            :target-id "node:out"}
                         {:xt/id "edge:in"
                          :source-id "node:in"
                          :target-id "node:a"})]))]
      (is (= ["edge:out" "edge:in"]
             (mapv :xt/id
                   (store/edge-rows-touching-ids
                    {:node :stub}
                    ["node:a" "node:b" "node:a"]
                    {:project-id "project-a"
                     :active? true}
                    {:valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= 2 (count @calls)))
    (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"} (:ctx %)) @calls))
    (let [queries (map (comp pr-str :query) @calls)]
      (is (every? #(str/includes? % "unify") queries))
      (is (every? #(str/includes? % "rel") queries))
      (is (some #(str/includes? % ":source-id match-value") queries))
      (is (some #(str/includes? % ":target-id match-value") queries))
      (is (not-any? #(str/includes? % "*") queries)))))

(deftest system-edges-touching-ids-use-bounded-xtql-unify-queries
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (let [idx (count @calls)]
                      (swap! calls conj {:query query
                                         :ctx ctx})
                      [(case idx
                         0 {:xt/id "system-edge:out"
                            :project-id "project-a"
                            :source-id "system:a"
                            :target-id "system:out"
                            :relation :calls-http
                            :confidence 0.9
                            :evidence-ids []
                            :rules []
                            :active? true
                            :run-id "run:system"}
                         {:xt/id "system-edge:in"
                          :project-id "project-a"
                          :source-id "system:in"
                          :target-id "system:a"
                          :relation :calls-http
                          :confidence 0.9
                          :evidence-ids []
                          :rules []
                          :active? true
                          :run-id "run:system"})]))]
      (is (= ["system-edge:out" "system-edge:in"]
             (mapv :xt/id
                   (query/system-edges-touching-ids
                    {:node :stub}
                    ["system:a" "system:b" "system:a"]
                    {:project-id "project-a"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= 2 (count @calls)))
    (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"} (:ctx %)) @calls))
    (let [queries (map (comp pr-str :query) @calls)]
      (is (every? #(str/includes? % "unify") queries))
      (is (every? #(str/includes? % "rel") queries))
      (is (every? #(str/includes? % ":active? v0") queries))
      (is (some #(str/includes? % ":source-id match-value") queries))
      (is (some #(str/includes? % ":target-id match-value") queries))
      (is (not-any? #(str/includes? % "*") queries)))))

(deftest chunks-by-ids-use-batched-id-read-and-preserve-input-order
  (let [calls (atom [])
        rows (with-redefs [store/rows-with-field-values
                           (fn [_ request]
                             (swap! calls conj request)
                             [{:xt/id "chunk:b"
                               :project-id "project-a"
                               :repo-id "app"
                               :active? true}
                              {:xt/id "chunk:a"
                               :project-id "project-a"
                               :repo-id "app"
                               :active? true}])
                           store/row-by-id
                           (fn [& _]
                             (throw (ex-info "row-by-id should not be used for batched chunk reads"
                                             {})))]
               (query/chunks-by-ids
                {:node :stub}
                ["chunk:a" "chunk:b" "chunk:a"]
                {:project-id "project-a"
                 :repo-id "app"
                 :valid-at #inst "2026-01-01T00:00:00Z"}))]
    (is (= ["chunk:a" "chunk:b"] (mapv :xt/id rows)))
    (is (= 1 (count @calls)))
    (is (= {:table (:chunks store/tables)
            :field :xt/id
            :values ["chunk:a" "chunk:b"]
            :constraints {:project-id "project-a"
                          :repo-id "app"}}
           (select-keys (first @calls) [:table :field :values :constraints])))
    (is (= {:valid-at #inst "2026-01-01T00:00:00Z"}
           (:read-context (first @calls))))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest chunks-by-paths-use-batched-path-read-and-preserve-path-order
  (let [calls (atom [])
        rows (with-redefs [store/rows-with-field-values
                           (fn [_ request]
                             (swap! calls conj request)
                             [{:xt/id "chunk:db"
                               :project-id "project-a"
                               :repo-id "app"
                               :path "src/db.clj"
                               :active? true}
                              {:xt/id "chunk:app"
                               :project-id "project-a"
                               :repo-id "app"
                               :path "src/app.clj"
                               :active? true}])
                           store/constrained-rows
                           (fn [& _]
                             (throw (ex-info "constrained-rows should not be used for path chunk reads"
                                             {})))]
               (query/chunks-by-paths
                {:node :stub}
                ["src/app.clj" "src/app.clj" "src/db.clj"]
                {:project-id "project-a"
                 :repo-id "app"
                 :valid-at #inst "2026-01-01T00:00:00Z"}))]
    (is (= ["chunk:app" "chunk:db"] (mapv :xt/id rows)))
    (is (= 1 (count @calls)))
    (is (= {:table (:chunks store/tables)
            :field :path
            :values ["src/app.clj" "src/db.clj"]
            :constraints {:project-id "project-a"
                          :repo-id "app"}}
           (select-keys (first @calls) [:table :field :values :constraints])))
    (is (= {:valid-at #inst "2026-01-01T00:00:00Z"}
           (:read-context (first @calls))))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest deps-loads-endpoint-nodes-with-batched-id-read
  (let [calls (atom [])
        edge-calls (atom [])
        node-a {:xt/id "node:a"
                :project-id "project-a"
                :repo-id "app"
                :label "A"
                :active? true}
        node-b {:xt/id "node:b"
                :project-id "project-a"
                :repo-id "app"
                :label "B"
                :active? true}
        edge {:xt/id "edge:a-b"
              :project-id "project-a"
              :repo-id "app"
              :source-id "node:a"
              :target-id "node:b"
              :relation :calls
              :active? true}]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (cond
                      (= #{"node:a"} (set (:values request)))
                      [node-a]

                      (= #{"node:a" "node:b"} (set (:values request)))
                      [node-b node-a]

                      :else
                      []))
                  store/edge-rows-touching-ids
                  (fn [_ ids constraints ctx]
                    (swap! edge-calls conj {:ids ids
                                            :constraints constraints
                                            :ctx ctx})
                    [edge])
                  store/row-by-id
                  (fn [& _]
                    (throw (ex-info "row-by-id should not be used for endpoint hydration"
                                    {})))]
      (let [result (query/deps {:node :stub}
                               "node:a"
                               {:project-id "project-a"
                                :repo-id "app"
                                :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= "node:a" (get-in result [:node :xt/id])))
        (is (= ["node:b"] (mapv #(get-in % [:target :xt/id]) (:outgoing result))))))
    (is (= 2 (count @calls)))
    (is (every? #(= :xt/id (:field %)) @calls))
    (is (every? #(= (:nodes store/tables) (:table %)) @calls))
    (is (every? #(= {:project-id "project-a"
                     :repo-id "app"}
                    (:constraints %))
                @calls))
    (is (= [{:ids ["node:a"]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @edge-calls))))
