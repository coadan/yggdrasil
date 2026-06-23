(ns ygg.xtdb-query-pushdown-test
  (:require [ygg.activity :as activity]
            [ygg.cli :as cli]
            [ygg.context :as context]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.map-api :as map-api]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [xtdb.api :as xt]))

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

(defn- graph-view-row
  [id label project-id active?]
  (cond-> {:xt/id id
           :label label
           :active? active?}
    project-id (assoc :project-id project-id)))

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

(deftest graph-view-label-lookup-uses-bounded-label-read
  (let [calls (atom [])]
    (with-redefs [store/row-by-id
                  (fn [& _] nil)
                  store/rows-by-field
                  (fn [_ table field value ctx]
                    (swap! calls conj {:table table
                                       :field field
                                       :value value
                                       :ctx ctx})
                    [(graph-view-row "view:global" "Platform" nil true)
                     (graph-view-row "view:inactive" "Platform" "project-a" false)
                     (graph-view-row "view:project" "Platform" "project-a" true)
                     (graph-view-row "view:other" "Platform" "project-b" true)])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "graph-view label lookup should not hydrate all views"
                                    {})))]
      (let [view (store/graph-view :xtdb
                                   "Platform"
                                   {:project-id "project-a"
                                    :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= "view:project" (:xt/id view)))))
    (is (= [{:table (:graph-views store/tables)
             :field :label
             :value "Platform"
             :ctx {:project-id "project-a"
                   :valid-at #inst "2026-01-01T00:00:00Z"}}]
           @calls))))

(deftest latest-source-snapshot-uses-sql-order-limit-for-real-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ sql ctx]
                    (swap! calls conj {:sql sql
                                       :ctx ctx})
                    [{:xt/id "snapshot:new"
                      :project-id "project-a"
                      :basis-instant #inst "2026-01-02T00:00:00Z"}])
                  store/rows-by-field
                  (fn [& _]
                    (throw (ex-info "latest source snapshot should not hydrate all project snapshots"
                                    {})))]
      (is (= "snapshot:new"
             (:xt/id (store/latest-source-snapshot {:node :stub} "project-a")))))
    (is (= 1 (count @calls)))
    (let [{:keys [sql ctx]} (first @calls)]
      (is (str/includes? sql "SELECT * FROM ygg.source_snapshots"))
      (is (str/includes? sql "\"project_id\" = ?"))
      (is (str/includes? sql "ORDER BY \"basis_instant\" DESC LIMIT 1"))
      (is (= {:args ["project-a"]} ctx)))))

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

(deftest rows-with-field-tuples-uses-bounded-xtql-unify-query
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:xt/id "embedding:a"
                      :target-id "target:a"
                      :input-sha "sha:a"}])]
      (is (= ["embedding:a"]
             (mapv :xt/id
                   (store/rows-with-field-tuples
                    {:node :stub}
                    {:table (:embeddings store/tables)
                     :tuple-fields [:target-id :input-sha]
                     :tuples [{:target-id "target:a" :input-sha "sha:a"}
                              {:target-id "target:b" :input-sha "sha:b"}
                              {:target-id "target:a" :input-sha "sha:a"}
                              {:target-id "target:c"}]
                     :constraints {:project-id "project-a"
                                   :provider :fake
                                   :model "fake-model"
                                   :active? true}
                     :return-fields [:xt/id
                                     :project-id
                                     :target-id
                                     :provider
                                     :model
                                     :input-sha
                                     :active?]
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})))))
    (is (= 1 (count @calls)))
    (is (= {:valid-at #inst "2026-01-01T00:00:00Z"} (:ctx (first @calls))))
    (let [query-text (pr-str (:query (first @calls)))]
      (is (str/includes? query-text "unify"))
      (is (str/includes? query-text "rel"))
      (is (str/includes? query-text ":target-id match0"))
      (is (str/includes? query-text ":input-sha match1"))
      (is (str/includes? query-text ":project-id v"))
      (is (str/includes? query-text ":provider v"))
      (is (not (str/includes? query-text "*")))
      (is (= 1 (count (re-seq #"target:a" query-text))))
      (is (not (str/includes? query-text "target:c"))))))

(deftest system-evidence-by-ids-uses-bounded-value-query
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ opts]
                    (swap! calls conj opts)
                    [{:xt/id "evidence:b"
                      :project-id "project-a"
                      :repo-id "app"
                      :active? true}
                     {:xt/id "evidence:a"
                      :project-id "project-a"
                      :repo-id "app"
                      :active? true}])
                  query/all-system-evidence
                  (fn [& _]
                    (throw (ex-info "all-system-evidence should not be used"
                                    {})))]
      (is (= ["evidence:a" "evidence:b"]
             (mapv :xt/id
                   (query/system-evidence-by-ids
                    {:node :stub}
                    ["evidence:a" "evidence:b" "evidence:a"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= 1 (count @calls)))
    (let [call (first @calls)]
      (is (= (:system-evidence store/tables) (:table call)))
      (is (= :xt/id (:field call)))
      (is (= ["evidence:a" "evidence:b"] (:values call)))
      (is (= {:project-id "project-a"
              :repo-id "app"
              :active? true}
             (:constraints call)))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"}
             (:read-context call)))
      (is (some #{:system-id} (:return-fields call)))
      (is (some #{:normalized-value} (:return-fields call))))))

(deftest system-evidence-by-system-ids-and-paths-use-bounded-value-queries
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ opts]
                    (swap! calls conj opts)
                    (case (:field opts)
                      :system-id [{:xt/id "evidence:system"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :system-id "system:app"
                                   :active? true}]
                      :path [{:xt/id "evidence:path"
                              :project-id "project-a"
                              :repo-id "app"
                              :path "src/app.clj"
                              :active? true}]))
                  query/all-system-evidence
                  (fn [& _]
                    (throw (ex-info "all-system-evidence should not be used"
                                    {})))]
      (is (= ["evidence:system"]
             (mapv :xt/id
                   (query/system-evidence-by-system-ids
                    {:node :stub}
                    ["system:app"]
                    {:project-id "project-a"
                     :repo-id "app"}))))
      (is (= ["evidence:path"]
             (mapv :xt/id
                   (query/system-evidence-by-paths
                    {:node :stub}
                    ["src/app.clj"]
                    {:project-id "project-a"
                     :repo-id "app"})))))
    (is (= [[:system-id ["system:app"]]
            [:path ["src/app.clj"]]]
           (mapv (juxt :field :values) @calls)))))

(deftest edges-touching-node-ids-uses-bounded-adjacency-helper
  (let [calls (atom [])]
    (with-redefs [store/edge-rows-touching-ids
                  (fn [_ ids constraints ctx]
                    (swap! calls conj {:ids ids
                                       :constraints constraints
                                       :ctx ctx})
                    [{:xt/id "edge:a"}])]
      (is (= ["edge:a"]
             (mapv :xt/id
                   (query/edges-touching-node-ids
                    {:node :stub}
                    ["node:a" "node:b"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [{:ids ["node:a" "node:b"]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @calls))))

(deftest nodes-by-file-ids-and-paths-use-batched-value-queries
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (case (:field request)
                      :file-id [{:xt/id "node:file"
                                 :project-id "project-a"
                                 :repo-id "app"
                                 :file-id "file:app"
                                 :active? true}]
                      :path [{:xt/id "node:path"
                              :project-id "project-a"
                              :repo-id "app"
                              :path "src/app.clj"
                              :active? true}]
                      :label [{:xt/id "node:label"
                               :project-id "project-a"
                               :repo-id "app"
                               :label "app/handler"
                               :active? true}]
                      :namespace [{:xt/id "node:namespace"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :namespace "app.core"
                                   :active? true}]
                      :name [{:xt/id "node:name"
                              :project-id "project-a"
                              :repo-id "app"
                              :name "handler"
                              :active? true}]
                      :package-name [{:xt/id "node:package"
                                      :project-id "project-a"
                                      :repo-id "app"
                                      :package-name "react"
                                      :active? true}]))
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "all-nodes should not be used for batched node reads"
                                    {})))]
      (is (= ["node:file"]
             (mapv :xt/id
                   (query/nodes-by-file-ids
                    {:node :stub}
                    ["file:app" "file:app"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["node:path"]
             (mapv :xt/id
                   (query/nodes-by-paths
                    {:node :stub}
                    ["src/app.clj"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["node:label"]
             (mapv :xt/id
                   (query/nodes-by-labels
                    {:node :stub}
                    ["app/handler"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["node:namespace"]
             (mapv :xt/id
                   (query/nodes-by-namespaces
                    {:node :stub}
                    ["app.core"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["node:name"]
             (mapv :xt/id
                   (query/nodes-by-names
                    {:node :stub}
                    ["handler"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["node:package"]
             (mapv :xt/id
                   (query/nodes-by-package-names
                    {:node :stub}
                    ["react"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [[:file-id ["file:app"]]
            [:path ["src/app.clj"]]
            [:label ["app/handler"]]
            [:namespace ["app.core"]]
            [:name ["handler"]]
            [:package-name ["react"]]]
           (mapv (juxt :field :values) @calls)))
    (is (every? #(= {:project-id "project-a"
                     :repo-id "app"}
                    (:constraints %))
                @calls))
    (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"}
                    (:read-context %))
                @calls))
    (is (every? #(not-any? #{'*} (:return-fields %)) @calls))))

(deftest edges-by-file-ids-and-paths-use-batched-value-queries
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (case (:field request)
                      :file-id [{:xt/id "edge:file"
                                 :project-id "project-a"
                                 :repo-id "app"
                                 :file-id "file:app"
                                 :active? true}]
                      :path [{:xt/id "edge:path"
                              :project-id "project-a"
                              :repo-id "app"
                              :path "src/app.clj"
                              :active? true}]))
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "all-edges should not be used for batched edge reads"
                                    {})))]
      (is (= ["edge:file"]
             (mapv :xt/id
                   (query/edges-by-file-ids
                    {:node :stub}
                    ["file:app"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"}))))
      (is (= ["edge:path"]
             (mapv :xt/id
                   (query/edges-by-paths
                    {:node :stub}
                    ["src/app.clj"]
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [[:file-id ["file:app"]]
            [:path ["src/app.clj"]]]
           (mapv (juxt :field :values) @calls)))
    (is (every? #(= {:project-id "project-a"
                     :repo-id "app"}
                    (:constraints %))
                @calls))
    (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"}
                    (:read-context %))
                @calls))
    (is (every? #(not-any? #{'*} (:return-fields %)) @calls))
    (is (every? #(some #{:file-id} (:return-fields %)) @calls))
    (is (every? #(some #{:source-line} (:return-fields %)) @calls))))

(deftest active-source-rows-use-constrained-active-queries
  (let [calls (atom [])
        valid-at #inst "2026-01-01T00:00:00Z"]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints & [ctx]]
                    (swap! calls conj {:table table
                                       :constraints constraints
                                       :ctx ctx})
                    (case table
                      :ygg/nodes [{:xt/id "node:a"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :active? true}]
                      :ygg/edges [{:xt/id "edge:a-b"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :source-id "node:a"
                                   :target-id "node:b"
                                   :active? true}]
                      []))
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "active node reads should not hydrate all nodes"
                                    {})))
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "active edge reads should not hydrate all edges"
                                    {})))]
      (is (= ["node:a"]
             (mapv :xt/id
                   (query/active-nodes
                    {:node :stub}
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at valid-at}))))
      (is (= ["edge:a-b"]
             (mapv :xt/id
                   (query/active-edges
                    {:node :stub}
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at valid-at})))))
    (is (= [{:table :ygg/nodes
             :constraints {:project-id "project-a"
                           :repo-id "app"
                           :active? true}
             :ctx {:valid-at valid-at}}
            {:table :ygg/edges
             :constraints {:project-id "project-a"
                           :repo-id "app"
                           :active? true}
             :ctx {:valid-at valid-at}}]
           @calls))))

(deftest count-rows-uses-sql-count-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:row_count 42}])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for row counts"
                                    {})))]
      (is (= 42
             (store/count-rows {:node :stub}
                               (:nodes store/tables)
                               {:repo-id "app"
                                :project-id "project-a"}
                               {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT COUNT(*) AS row_count"))
      (is (str/includes? query "FROM ygg.nodes"))
      (is (str/includes? query "\"project_id\" = ?"))
      (is (str/includes? query "\"repo_id\" = ?"))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app"]}
             ctx)))))

(deftest ordered-rows-use-sql-order-limit-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{"_id" "system:app"
                      "repo-id" "app"
                      "label" "App"}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "ordered rows should not hydrate all rows"
                                    {})))]
      (is (= [{:xt/id "system:app"
               :repo-id "app"
               :label "App"}]
             (mapv #(select-keys % [:xt/id :repo-id :label])
                   (store/ordered-rows
                    {:node :stub}
                    {:table (:system-nodes store/tables)
                     :constraints {:project-id "project-a"
                                   :repo-id "app"}
                     :order-fields [:repo-id :label]
                     :limit 5
                     :return-fields [:xt/id :repo-id :label]
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT \"_id\" AS \"_id\""))
      (is (str/includes? query "FROM ygg.system_nodes"))
      (is (str/includes? query "\"project_id\" = ?"))
      (is (str/includes? query "\"repo_id\" = ?"))
      (is (str/includes? query "ORDER BY \"repo_id\" ASC NULLS FIRST, \"label\" ASC NULLS FIRST"))
      (is (str/includes? query "LIMIT ?"))
      (is (not (str/includes? query "*")))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app" 5]}
             ctx)))))

(deftest rows-with-min-field-value-uses-sql-comparison-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{"_id" "edge:a"
                      "confidence" 0.8}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "minimum field reads should not hydrate all rows"
                                    {})))]
      (is (= [{:xt/id "edge:a"
               :confidence 0.8}]
             (mapv #(select-keys % [:xt/id :confidence])
                   (store/rows-with-min-field-value
                    {:node :stub}
                    {:table (:system-edges store/tables)
                     :field :confidence
                     :min-value 0.55
                     :constraints {:project-id "project-a"
                                   :active? true}
                     :return-fields [:xt/id :confidence]
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT \"_id\" AS \"_id\""))
      (is (str/includes? query "FROM ygg.system_edges"))
      (is (str/includes? query "\"active?\" = ?"))
      (is (str/includes? query "\"project_id\" = ?"))
      (is (str/includes? query "\"confidence\" >= ?"))
      (is (not (str/includes? query "*")))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args [true "project-a" 0.55]}
             ctx)))))

(deftest rows-with-field-values-can-push-numeric-min-into-xtql
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:xt/id "edge:a"
                      :source-id "system:a"
                      :confidence 0.8}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "bounded min field reads should not hydrate all rows"
                                    {})))]
      (is (= [{:xt/id "edge:a"
               :source-id "system:a"
               :confidence 0.8}]
             (mapv #(select-keys % [:xt/id :source-id :confidence])
                   (store/rows-with-field-values
                    {:node :stub}
                    {:table (:system-edges store/tables)
                     :field :source-id
                     :values ["system:a" "system:b" "system:a"]
                     :constraints {:project-id "project-a"
                                   :active? true}
                     :min-field :confidence
                     :min-value 0.55
                     :return-fields [:xt/id :source-id :confidence]
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)
          query-text (pr-str query)]
      (is (str/includes? query-text "unify"))
      (is (str/includes? query-text "rel"))
      (is (str/includes? query-text "where"))
      (is (str/includes? query-text "(>= min-field-value min-value)"))
      (is (not (str/includes? query-text "*")))
      (is (= [true "project-a" 0.55] (vec (rest query))))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"} ctx)))))

(deftest system-graph-edges-use-confidence-pushdown
  (let [calls (atom [])]
    (with-redefs [store/rows-with-min-field-value
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "edge:system"
                      :project-id "project-a"
                      :source-id "system:a"
                      :target-id "system:b"
                      :relation :calls-http
                      :confidence 0.8
                      :active? true}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "system graph edges should not hydrate all active edges"
                                    {})))]
      (is (= ["edge:system"]
             (mapv :xt/id
                   (#'graph/active-system-edges
                    :xtdb
                    "project-a"
                    0.55
                    {:valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [{:table (:system-edges store/tables)
             :field :confidence
             :min-value 0.55
             :constraints {:project-id "project-a"
                           :active? true}
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table
                                  :field
                                  :min-value
                                  :constraints
                                  :read-context])
                 @calls)))
    (is (some #{:source-id} (:return-fields (first @calls))))
    (is (some #{:target-id} (:return-fields (first @calls))))
    (is (some #{:confidence} (:return-fields (first @calls))))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest map-review-uses-paged-system-read-and-count-pushdown
  (let [page-calls (atom [])
        count-calls (atom [])]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! page-calls conj request)
                    [{:xt/id "system:app"
                      :project-id "project-a"
                      :repo-id "app"
                      :label "App"
                      :active? true}])
                  store/count-rows
                  (fn [_ table constraints]
                    (swap! count-calls conj {:table table
                                             :constraints constraints
                                             :ctx {}})
                    (case table
                      :ygg/system-nodes 9
                      :ygg/system-edges 7))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "map review should not hydrate all system rows"
                                    {})))]
      (let [review (map-api/review {:node :stub}
                                   {:id "project-a"}
                                   {:limit 1})]
        (is (= 7 (get-in review [:candidates :totalEdges])))
        (is (= 9 (get-in review [:candidates :totalSystems])))
        (is (= ["system:app"]
               (mapv :id (get-in review [:candidates :systems]))))))
    (is (= [{:table (:system-nodes store/tables)
             :constraints {:project-id "project-a"
                           :active? true}
             :order-fields [:repo-id :label]
             :limit 1}]
           (mapv #(select-keys % [:table :constraints :order-fields :limit])
                 @page-calls)))
    (is (= [{:table (:system-nodes store/tables)
             :constraints {:project-id "project-a"
                           :active? true}
             :ctx {}}
            {:table (:system-edges store/tables)
             :constraints {:project-id "project-a"
                           :active? true}
             :ctx {}}]
           @count-calls))))

(deftest cli-candidate-system-rows-use-kind-value-pushdown
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "system:candidate"
                      :project-id "project-a"
                      :repo-id "app"
                      :label "App"
                      :kind :candidate-system
                      :candidate-types [:path-cluster]
                      :metrics {:file-count 2}
                      :active? true}
                     {:xt/id "system:repo"
                      :project-id "project-a"
                      :repo-id "lib"
                      :label "Lib"
                      :kind :repo-boundary
                      :candidate-types [:manifest-root]
                      :metrics {:file-count 1}
                      :active? true}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "candidate systems should not hydrate all system nodes"
                                    {})))]
      (is (= ["system:candidate" "system:repo"]
             (mapv :xt/id
                   (#'cli/candidate-system-rows {:node :stub} "project-a")))))
    (is (= 1 (count @calls)))
    (let [call (first @calls)]
      (is (= (:system-nodes store/tables) (:table call)))
      (is (= :kind (:field call)))
      (is (= [:candidate-system :repo-boundary] (:values call)))
      (is (= {:project-id "project-a"
              :active? true}
             (:constraints call)))
      (is (some #{:xt/id} (:return-fields call)))
      (is (some #{:kind} (:return-fields call)))
      (is (some #{:metrics} (:return-fields call)))
      (is (not-any? #{'*} (:return-fields call))))))

(deftest commit-activity-uses-projected-source-id-reads
  (let [calls (atom [])
        tx-ops (atom nil)]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! calls conj request)
                    (case (:table request)
                      :ygg/activity-items [{:xt/id "activity-item:old"}]
                      :ygg/activity-events [{:xt/id "activity-event:old"}]))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "activity replacement should not hydrate full existing rows"
                                    {})))
                  store/execute-tx!
                  (fn [_ ops]
                    (reset! tx-ops ops))]
      (is (= {:items 0
              :events 0
              :deleted-items 1
              :deleted-events 1}
             (activity/commit-activity! :xtdb
                                        {:project-id "project-a"
                                         :source :queue
                                         :items []
                                         :events []}))))
    (is (= [{:table (:activity-items store/tables)
             :constraints {:project-id "project-a"
                           :source :queue}
             :return-fields [:xt/id]}
            {:table (:activity-events store/tables)
             :constraints {:project-id "project-a"
                           :source :queue}
             :return-fields [:xt/id]}]
           (mapv #(select-keys % [:table :constraints :return-fields])
                 @calls)))
    (is (= [[:delete-docs (:activity-items store/tables) "activity-item:old"]
            [:delete-docs (:activity-events store/tables) "activity-event:old"]]
           @tx-ops))))

(deftest derived-dependency-edge-replacement-uses-projected-id-read
  (let [calls (atom [])
        tx-ops (atom nil)]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "edge:old-import"}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "derived edge replacement should not hydrate full edges"
                                    {})))
                  store/execute-tx!
                  (fn [_ ops]
                    (reset! tx-ops ops))]
      (is (= {:dependency-edges 0
              :dependency-edges-deleted 1}
             (store/commit-derived-dependency-edges! :xtdb
                                                     "project-a"
                                                     "app"
                                                     []
                                                     {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= [{:table (:edges store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "app"
                           :relation :imports-package}
             :return-fields [:xt/id]
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table :constraints :return-fields :read-context])
                 @calls)))
    (is (= [[:delete-docs (:edges store/tables) "edge:old-import"]]
           @tx-ops))))

(deftest active-row-count-pushes-active-unless-false-filter
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:row-count 7}])]
      (is (= 7
             (store/active-row-count {:node :stub}
                                     (:files store/tables)
                                     {:project-id "project-a"}
                                     {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (str/includes? query "SELECT COUNT(*) AS row_count"))
      (is (str/includes? query "\"active?\" IS NULL"))
      (is (str/includes? query "\"active?\" <> FALSE"))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a"]}
             ctx)))))

(deftest active-row-counts-by-field-uses-sql-group-by-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:value :namespace
                      :row_count 5}
                     {:value :var
                      :row_count 2}])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for grouped row counts"
                                    {})))]
      (is (= [{:value :namespace
               :count 5}
              {:value :var
               :count 2}]
             (store/active-row-counts-by-field
              {:node :stub}
              (:nodes store/tables)
              :kind
              {:project-id "project-a"
               :repo-id "app"}
              {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT \"kind\" AS value, COUNT(*) AS row_count"))
      (is (str/includes? query "FROM ygg.nodes"))
      (is (str/includes? query "\"project_id\" = ?"))
      (is (str/includes? query "\"repo_id\" = ?"))
      (is (str/includes? query "\"active?\" IS NULL"))
      (is (str/includes? query "\"active?\" <> FALSE"))
      (is (str/includes? query "GROUP BY \"kind\""))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app"]}
             ctx)))))

(deftest active-row-counts-by-fields-uses-sql-group-by-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:value0 :auth-reference
                      :value1 :service-account
                      :row_count 2}
                     {:value0 :url
                      :value1 :runtime-config
                      :row_count 1}])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for grouped row counts"
                                    {})))]
      (is (= [{:values {:kind :auth-reference
                        :auth-context :service-account}
               :count 2}
              {:values {:kind :url
                        :auth-context :runtime-config}
               :count 1}]
             (store/active-row-counts-by-fields
              {:node :stub}
              (:system-evidence store/tables)
              [:kind :auth-context]
              {:project-id "project-a"
               :repo-id "app"}
              {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT \"kind\" AS value0, \"auth_context\" AS value1"))
      (is (str/includes? query "COUNT(*) AS row_count"))
      (is (str/includes? query "FROM ygg.system_evidence"))
      (is (str/includes? query "\"project_id\" = ?"))
      (is (str/includes? query "\"repo_id\" = ?"))
      (is (str/includes? query "\"active?\" IS NULL"))
      (is (str/includes? query "\"active?\" <> FALSE"))
      (is (str/includes? query "GROUP BY \"kind\", \"auth_context\""))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app"]}
             ctx)))))

(deftest row-counts-by-any-field-uses-sql-union-aggregate-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:value "node:a"
                      :row_count 3}
                     {:value "node:b"
                      :row_count 1}])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for endpoint counts"
                                    {})))]
      (is (= [{:value "node:a"
               :count 3}
              {:value "node:b"
               :count 1}]
             (store/row-counts-by-any-field
              {:node :stub}
              (:edges store/tables)
              [:source-id :target-id]
              {:project-id "project-a"
               :repo-id "app"}
              {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT value, COUNT(*) AS row_count FROM"))
      (is (str/includes? query "SELECT \"source_id\" AS value FROM ygg.edges"))
      (is (str/includes? query "SELECT \"target_id\" AS value FROM ygg.edges"))
      (is (str/includes? query "UNION ALL"))
      (is (str/includes? query "WHERE value IS NOT NULL GROUP BY value"))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app" "project-a" "app"]}
             ctx)))))

(deftest active-row-counts-by-any-field-uses-sql-union-aggregate-for-xtdb-handles
  (let [calls (atom [])]
    (with-redefs [store/q
                  (fn [_ query ctx]
                    (swap! calls conj {:query query
                                       :ctx ctx})
                    [{:value "node:b"
                      :row_count 2}
                     {:value "node:a"
                      :row_count 1}])
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for active endpoint counts"
                                    {})))]
      (is (= [{:value "node:b"
               :count 2}
              {:value "node:a"
               :count 1}]
             (store/active-row-counts-by-any-field
              {:node :stub}
              (:edges store/tables)
              [:source-id :target-id]
              {:project-id "project-a"
               :repo-id "app"}
              {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= 1 (count @calls)))
    (let [{:keys [query ctx]} (first @calls)]
      (is (string? query))
      (is (str/includes? query "SELECT value, COUNT(*) AS row_count FROM"))
      (is (str/includes? query "UNION ALL"))
      (is (= 2 (count (re-seq (re-pattern
                               (java.util.regex.Pattern/quote
                                "\"active?\" IS NULL"))
                              query))))
      (is (= 2 (count (re-seq (re-pattern
                               (java.util.regex.Pattern/quote
                                "\"active?\" <> FALSE"))
                              query))))
      (is (= {:valid-at #inst "2026-01-01T00:00:00Z"
              :args ["project-a" "app" "project-a" "app"]}
             ctx)))))

(deftest row-counts-by-any-field-fallback-counts-endpoint-values
  (let [calls (atom [])]
    (with-redefs [store/all-rows
                  (fn [_ table ctx]
                    (swap! calls conj {:table table
                                       :ctx ctx})
                    [{:xt/id "edge:a->b"
                      :project-id "project-a"
                      :repo-id "app"
                      :source-id "node:a"
                      :target-id "node:b"
                      :active? true}
                     {:xt/id "edge:a->c"
                      :project-id "project-a"
                      :repo-id "app"
                      :source-id "node:a"
                      :target-id "node:c"
                      :active? false}
                     {:xt/id "edge:nil->b"
                      :project-id "project-a"
                      :repo-id "app"
                      :source-id nil
                      :target-id "node:b"
                      :active? true}
                     {:xt/id "edge:other"
                      :project-id "project-a"
                      :repo-id "other"
                      :source-id "node:a"
                      :target-id "node:ignored"
                      :active? true}])]
      (is (= [{:value "node:a"
               :count 2}
              {:value "node:b"
               :count 2}
              {:value "node:c"
               :count 1}]
             (store/row-counts-by-any-field
              :fallback
              (:edges store/tables)
              [:source-id :target-id]
              {:project-id "project-a"
               :repo-id "app"}
              {:valid-at #inst "2026-01-01T00:00:00Z"}))))
    (is (= [{:table (:edges store/tables)
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @calls))))

(deftest active-row-counts-by-any-field-fallback-filters-inactive-endpoints
  (with-redefs [store/all-rows
                (fn [_ table ctx]
                  (is (= (:edges store/tables) table))
                  (is (= {:valid-at #inst "2026-01-01T00:00:00Z"} ctx))
                  [{:xt/id "edge:a->b"
                    :project-id "project-a"
                    :repo-id "app"
                    :source-id "node:a"
                    :target-id "node:b"
                    :active? true}
                   {:xt/id "edge:a->c"
                    :project-id "project-a"
                    :repo-id "app"
                    :source-id "node:a"
                    :target-id "node:c"
                    :active? false}
                   {:xt/id "edge:nil->b"
                    :project-id "project-a"
                    :repo-id "app"
                    :source-id nil
                    :target-id "node:b"
                    :active? true}])]
    (is (= [{:value "node:b"
             :count 2}
            {:value "node:a"
             :count 1}]
           (store/active-row-counts-by-any-field
            :fallback
            (:edges store/tables)
            [:source-id :target-id]
            {:project-id "project-a"
             :repo-id "app"}
            {:valid-at #inst "2026-01-01T00:00:00Z"})))))

(deftest graph-readiness-reuses-precomputed-context-counts
  (let [active-calls (atom [])
        fail-duplicate-read (fn [& _]
                              (throw (ex-info "graph readiness should reuse precomputed counts"
                                              {})))]
    (with-redefs [store/active-row-count
                  (fn [_ table constraints ctx]
                    (swap! active-calls conj {:table table
                                              :constraints constraints
                                              :ctx ctx})
                    (case table
                      :ygg/files 4
                      :ygg/index-diagnostics 0
                      0))
                  store/count-rows fail-duplicate-read
                  query/all-nodes (fn [& _]
                                    [{:kind :external-package}
                                     {:kind :namespace}
                                     {:kind :external-package
                                      :active? false}])
                  query/all-edges (fn [& _]
                                    [{:relation :imports-package}
                                     {:relation :uses}
                                     {:relation :imports-package
                                      :active? false}])
                  query/all-chunks (fn [& _]
                                     [{:active? true}
                                      {:active? false}])
                  query/all-search-docs fail-duplicate-read
                  query/all-embeddings (fn [& _]
                                         [{} {}])
                  query/all-system-nodes (fn [& _]
                                           [{} {}])
                  query/all-system-edges (fn [& _]
                                           [{}])
                  query/all-system-evidence fail-duplicate-read
                  coverage/index-run-skipped-files (fn [& _] 0)
                  dependency/package-report fail-duplicate-read
                  activity/all-items (fn [& _] [])
                  activity/all-events (fn [& _]
                                        [{:event-kind :validation}
                                         {:event-kind :result-schema-mismatch}
                                         {:event-kind :other}])]
      (let [evidence (#'context/query-evidence
                      :xtdb
                      {}
                      {:project-id "project-a"
                       :repo-id "app"
                       :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}
                       :retriever :lexical
                       :dependency-counts {:packages 11
                                           :source-import-candidates 12
                                           :unresolved-imports 13
                                           :declared-without-import-evidence 14
                                           :version-conflicts 15}
                       :system-evidence-count 5
                       :search-doc-count 6}
                      {:entity-count 1
                       :doc-count 1
                       :activity-count 0
                       :validation-count 0
                       :runtime-count 1})]
        (is (= {:files 4
                :nodes 2
                :edges 2
                :external-packages 1
                :package-import-edges 1
                :declared-packages 11
                :source-import-candidates 12
                :unresolved-imports 13
                :package-evidence-gaps 14
                :package-conflicts 15
                :system-evidence 5
                :chunks 1
                :search-docs 6
                :embeddings 2
                :system-nodes 2
                :system-edges 1
                :activity-events 3
                :validation-events 1
                :result-schema-mismatch-events 1
                :diagnostics 0}
               (select-keys (:counts evidence)
                            [:files
                             :nodes
                             :edges
                             :external-packages
                             :package-import-edges
                             :declared-packages
                             :source-import-candidates
                             :unresolved-imports
                             :package-evidence-gaps
                             :package-conflicts
                             :system-evidence
                             :chunks
                             :search-docs
                             :embeddings
                             :system-nodes
                             :system-edges
                             :activity-events
                             :validation-events
                             :result-schema-mismatch-events
                             :diagnostics])))))
    (is (= #{{:table :ygg/files
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/index-diagnostics
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @active-calls)))))

(deftest query-report-counts-index-only-tables-with-count-queries
  (let [count-calls (atom [])
        degree-calls (atom [])
        node-calls (atom [])
        constraints {:project-id "project-a"
                     :repo-id "app"}
        read-context {:valid-at #inst "2026-01-01T00:00:00Z"}
        count-values {[:ygg/nodes constraints] 2
                      [:ygg/edges constraints] 2
                      [:ygg/chunks constraints] 3
                      [:ygg/search-docs (assoc constraints :active? true)] 4
                      [:ygg/embeddings (assoc constraints :active? true)] 5}
        fail-broad-read (fn [& _]
                          (throw (ex-info "query report should use count queries"
                                          {})))]
    (with-redefs [query/all-nodes fail-broad-read
                  query/all-edges fail-broad-read
                  query/all-diagnostics
                  (fn [_ opts]
                    (is (= {:project-id "project-a"
                            :repo-id "app"
                            :read-context read-context}
                           opts))
                    [{:xt/id "diagnostic:a"}])
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (swap! node-calls conj {:ids (vec ids)
                                            :opts opts})
                    [{:xt/id "node:a"
                      :label "A"}
                     {:xt/id "node:b"
                      :label "B"}])
                  query/all-chunks fail-broad-read
                  query/all-search-docs fail-broad-read
                  query/all-embeddings fail-broad-read
                  store/row-counts-by-any-field
                  (fn [_ table fields constraints ctx]
                    (swap! degree-calls conj {:table table
                                              :fields fields
                                              :constraints constraints
                                              :ctx ctx})
                    [{:value "node:a"
                      :count 2}
                     {:value "node:b"
                      :count 1}
                     {:value "node:missing"
                      :count 1}])
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! count-calls conj {:table table
                                             :constraints constraints
                                             :ctx ctx})
                    (get count-values [table constraints] 0))]
      (let [report (query/report
                    {:node :stub}
                    {:project-id "project-a"
                     :repo-id "app"
                     :read-context read-context})]
        (is (= {:nodes 2
                :edges 2
                :chunks 3
                :search-docs 4
                :embeddings 5
                :diagnostics 1}
               (:counts report)))
        (is (= [{:xt/id "diagnostic:a"}] (:diagnostics report)))))
    (is (= #{{:table :ygg/chunks
              :constraints constraints
              :ctx read-context}
             {:table :ygg/nodes
              :constraints constraints
              :ctx read-context}
             {:table :ygg/edges
              :constraints constraints
              :ctx read-context}
             {:table :ygg/search-docs
              :constraints (assoc constraints :active? true)
              :ctx read-context}
             {:table :ygg/embeddings
              :constraints (assoc constraints :active? true)
              :ctx read-context}}
           (set @count-calls)))
    (is (= [{:table :ygg/edges
             :fields [:source-id :target-id]
             :constraints constraints
             :ctx read-context}]
           @degree-calls))
    (is (= [{:ids ["node:a" "node:b" "node:missing"]
             :opts {:project-id "project-a"
                    :repo-id "app"
                    :read-context read-context}}]
           @node-calls))))

(deftest graph-readiness-real-handles-use-store-counts-with-precomputed-context-counts
  (let [active-calls (atom [])
        exact-calls (atom [])
        schema-count-calls (atom [])
        fail-broad-read (fn [& _]
                          (throw (ex-info "graph readiness should use count queries"
                                          {})))
        fail-duplicate-read (fn [& _]
                              (throw (ex-info "graph readiness should reuse precomputed counts"
                                              {})))
        active-counts {[:ygg/files {:project-id "project-a"
                                    :repo-id "app"}] 4
                       [:ygg/nodes {:project-id "project-a"
                                    :repo-id "app"}] 7
                       [:ygg/edges {:project-id "project-a"
                                    :repo-id "app"}] 9
                       [:ygg/nodes {:project-id "project-a"
                                    :repo-id "app"
                                    :kind :external-package}] 1
                       [:ygg/edges {:project-id "project-a"
                                    :repo-id "app"
                                    :relation :imports-package}] 2
                       [:ygg/chunks {:project-id "project-a"
                                     :repo-id "app"}] 3
                       [:ygg/index-diagnostics {:project-id "project-a"
                                                :repo-id "app"}] 0}
        exact-counts {[:ygg/embeddings {:project-id "project-a"
                                        :repo-id "app"
                                        :active? true}] 0
                      [:ygg/system-nodes {:project-id "project-a"
                                          :active? true}] 2
                      [:ygg/system-edges {:project-id "project-a"
                                          :active? true}] 1
                      [:ygg/activity-items {:project-id "project-a"
                                            :active? true}] 14
                      [:ygg/activity-events {:project-id "project-a"
                                             :active? true}] 4
                      [:ygg/activity-events {:project-id "project-a"
                                             :active? true
                                             :event-kind :validation}] 1
                      [:ygg/activity-events {:project-id "project-a"
                                             :active? true
                                             :event-kind :result-schema-mismatch}] 2}]
    (with-redefs [store/active-row-count
                  (fn [_ table constraints ctx]
                    (swap! active-calls conj {:table table
                                              :constraints constraints
                                              :ctx ctx})
                    (get active-counts [table constraints] 0))
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! exact-calls conj {:table table
                                             :constraints constraints
                                             :ctx ctx})
                    (get exact-counts [table constraints] 0))
                  store/active-row-counts-by-fields
                  (fn [_ table fields constraints ctx]
                    (swap! schema-count-calls conj {:table table
                                                    :fields fields
                                                    :constraints constraints
                                                    :ctx ctx})
                    [{:values {:expected-result-schema "schema/a"
                               :result-schema "schema/a"}
                      :count 2}
                     {:values {:expected-result-schema "schema/a"
                               :result-schema "schema/b"}
                      :count 1}
                     {:values {:expected-result-schema "schema/c"}
                      :count 1}
                     {:values {:result-schema "schema/d"}
                      :count 1}
                     {:values {}
                      :count 9}])
                  query/all-nodes fail-broad-read
                  query/all-edges fail-broad-read
                  query/all-chunks fail-broad-read
                  query/all-search-docs fail-duplicate-read
                  query/all-embeddings fail-broad-read
                  query/all-system-nodes fail-broad-read
                  query/all-system-edges fail-broad-read
                  query/all-system-evidence fail-duplicate-read
                  query/all-diagnostics fail-broad-read
                  coverage/index-run-skipped-files (fn [& _] 0)
                  dependency/package-report fail-duplicate-read
                  activity/all-items fail-broad-read
                  activity/all-events fail-broad-read]
      (let [evidence (#'context/query-evidence
                      {:node :stub}
                      {}
                      {:project-id "project-a"
                       :repo-id "app"
                       :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}
                       :retriever :lexical
                       :dependency-counts {:packages 11
                                           :source-import-candidates 12
                                           :unresolved-imports 13
                                           :declared-without-import-evidence 14
                                           :version-conflicts 15}
                       :system-evidence-count 5
                       :search-doc-count 6}
                      {:entity-count 1
                       :doc-count 1
                       :activity-count 0
                       :validation-count 0
                       :runtime-count 1})]
        (is (= {:files 4
                :nodes 7
                :edges 9
                :external-packages 1
                :package-import-edges 2
                :declared-packages 11
                :source-import-candidates 12
                :unresolved-imports 13
                :package-evidence-gaps 14
                :package-conflicts 15
                :system-evidence 5
                :chunks 3
                :search-docs 6
                :embeddings 0
                :system-nodes 2
                :system-edges 1
                :activity-items 14
                :activity-events 4
                :validation-events 1
                :result-schema-mismatch-events 2
                :result-schema-statuses {:matching 2
                                         :mismatch 1
                                         :missing-result 1
                                         :unexpected-result 1}
                :result-schema-status-items 5
                :result-schema-matching-items 2
                :result-schema-mismatch-items 1
                :result-schema-missing-result-items 1
                :result-schema-unexpected-result-items 1
                :diagnostics 0}
               (select-keys (:counts evidence)
                            [:files
                             :nodes
                             :edges
                             :external-packages
                             :package-import-edges
                             :declared-packages
                             :source-import-candidates
                             :unresolved-imports
                             :package-evidence-gaps
                             :package-conflicts
                             :system-evidence
                             :chunks
                             :search-docs
                             :embeddings
                             :system-nodes
                             :system-edges
                             :activity-items
                             :activity-events
                             :validation-events
                             :result-schema-mismatch-events
                             :result-schema-statuses
                             :result-schema-status-items
                             :result-schema-matching-items
                             :result-schema-mismatch-items
                             :result-schema-missing-result-items
                             :result-schema-unexpected-result-items
                             :diagnostics])))))
    (is (= #{{:table :ygg/files
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/nodes
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/edges
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/nodes
              :constraints {:project-id "project-a"
                            :repo-id "app"
                            :kind :external-package}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/edges
              :constraints {:project-id "project-a"
                            :repo-id "app"
                            :relation :imports-package}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/chunks
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/index-diagnostics
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @active-calls)))
    (is (= #{{:table :ygg/embeddings
              :constraints {:project-id "project-a"
                            :repo-id "app"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-nodes
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-edges
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-items
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true
                            :event-kind :validation}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true
                            :event-kind :result-schema-mismatch}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @exact-calls)))
    (is (= [{:table :ygg/activity-items
             :fields [:expected-result-schema :result-schema]
             :constraints {:active? true
                           :project-id "project-a"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @schema-count-calls))))

(deftest skipped-index-run-count-uses-completed-status-value-query
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ opts]
                    (swap! calls conj opts)
                    [{:xt/id "run:old"
                      :project-id "project-a"
                      :repo-id "app"
                      :status :completed
                      :finished-at-ms 10
                      :active? true
                      :stats {:files-skipped 5}}
                     {:xt/id "run:latest"
                      :project-id "project-a"
                      :repo-id "app"
                      :status :completed
                      :finished-at-ms 20
                      :active? true
                      :stats {:files-skipped 2}}
                     {:xt/id "run:other"
                      :project-id "project-a"
                      :repo-id "other"
                      :status :completed
                      :finished-at-ms 30
                      :active? true
                      :stats {:files-skipped 7}}
                     {:xt/id "run:inactive"
                      :project-id "project-a"
                      :repo-id "app"
                      :status :completed
                      :finished-at-ms 40
                      :active? false
                      :stats {:files-skipped 11}}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "skipped index run count should use a completed status query"
                                    {})))]
      (is (= 2
             (coverage/index-run-skipped-files
              {:node :stub}
              {:project-id "project-a"
               :repo-id "app"
               :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}))))
    (is (= [{:table :ygg/index-runs
             :field :status
             :values [:completed]
             :constraints {:active? true
                           :project-id "project-a"
                           :repo-id "app"}
             :return-fields [:xt/id
                             :project-id
                             :repo-id
                             :status
                             :stats
                             :started-at-ms
                             :finished-at-ms
                             :active?]
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @calls))))

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

(deftest system-edges-touching-ids-pass-min-confidence-to-bounded-reads
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    [(case (:field request)
                       :source-id {:xt/id "system-edge:out"
                                   :project-id "project-a"
                                   :source-id "system:a"
                                   :target-id "system:out"
                                   :relation :calls-http
                                   :confidence 0.9
                                   :active? true}
                       :target-id {:xt/id "system-edge:in"
                                   :project-id "project-a"
                                   :source-id "system:in"
                                   :target-id "system:a"
                                   :relation :calls-http
                                   :confidence 0.9
                                   :active? true})])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "system adjacency should use bounded reads"
                                    {})))]
      (is (= ["system-edge:out" "system-edge:in"]
             (mapv :xt/id
                   (query/system-edges-touching-ids
                    :xtdb
                    ["system:a" "system:b" "system:a"]
                    {:project-id "project-a"
                     :min-confidence 0.55
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [{:field :source-id
             :min-field :confidence
             :min-value 0.55
             :constraints {:active? true
                           :project-id "project-a"}
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:field :target-id
             :min-field :confidence
             :min-value 0.55
             :constraints {:active? true
                           :project-id "project-a"}
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:field
                                  :min-field
                                  :min-value
                                  :constraints
                                  :read-context])
                 @calls)))
    (is (every? #(= (:system-edges store/tables) (:table %)) @calls))
    (is (every? #(some #{:confidence} (:return-fields %)) @calls))
    (is (every? #(not-any? #{'*} (:return-fields %)) @calls))))

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

(deftest doc-candidates-use-token-pushdown-for-real-handles
  (let [calls (atom [])
        rows (with-redefs [store/rows-matching-any-token
                           (fn [_ table fields tokens constraints ctx return-fields]
                             (swap! calls conj {:table table
                                                :fields fields
                                                :tokens tokens
                                                :constraints constraints
                                                :ctx ctx
                                                :return-fields return-fields})
                             [{:xt/id "chunk:billing"
                               :project-id "project-a"
                               :repo-id "app"
                               :path "docs/billing.md"
                               :kind :markdown
                               :label "Billing API"
                               :text "Billing API reference"
                               :source-line 4
                               :active? true}])
                           query/all-chunks
                           (fn [& _]
                             (throw (ex-info "doc candidates should not hydrate all chunks"
                                             {})))]
               (context/doc-candidates
                {:node :stub}
                "billing api"
                {:project-id "project-a"
                 :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}))]
    (is (= ["docs/billing.md"] (mapv #(get-in % [:source :path]) rows)))
    (is (= [{:table (:chunks store/tables)
             :fields [:label :path :text]
             :tokens ["billing" "api"]
             :constraints {:project-id "project-a"
                           :kind :markdown}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}
             :return-fields [:xt/id
                             :project-id
                             :repo-id
                             :path
                             :kind
                             :definition-kind
                             :label
                             :text
                             :heading-path
                             :content-sha
                             :source-line
                             :end-line
                             :active?]}]
           @calls))))

(deftest query-report-uses-counts-for-index-tables
  (let [count-calls (atom [])
        degree-calls (atom [])
        node-calls (atom [])
        fail-broad-read (fn [& _]
                          (throw (ex-info "query report should use count queries"
                                          {})))]
    (with-redefs [query/all-nodes fail-broad-read
                  query/all-edges fail-broad-read
                  query/all-diagnostics
                  (fn [& _]
                    [{:xt/id "diagnostic:a"}])
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (swap! node-calls conj {:ids (vec ids)
                                            :opts opts})
                    [{:xt/id "node:a"
                      :label "A"}
                     {:xt/id "node:b"
                      :label "B"}])
                  query/all-chunks fail-broad-read
                  query/all-search-docs fail-broad-read
                  query/all-embeddings fail-broad-read
                  store/row-counts-by-any-field
                  (fn [_ table fields constraints ctx]
                    (swap! degree-calls conj {:table table
                                              :fields fields
                                              :constraints constraints
                                              :ctx ctx})
                    [{:value "node:a"
                      :count 2}
                     {:value "node:b"
                      :count 1}])
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! count-calls conj {:table table
                                             :constraints constraints
                                             :ctx ctx})
                    (case table
                      :ygg/nodes 2
                      :ygg/edges 1
                      :ygg/chunks 3
                      :ygg/search-docs 4
                      :ygg/embeddings 5))]
      (let [summary (query/report
                     {:node :stub}
                     {:project-id "project-a"
                      :repo-id "app"
                      :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})]
        (is (= {:nodes 2
                :edges 1
                :chunks 3
                :search-docs 4
                :embeddings 5
                :diagnostics 1}
               (:counts summary)))
        (is (= #{"node:a" "node:b"}
               (set (map #(get-in % [:node :xt/id]) (:top-nodes summary)))))))
    (is (= [{:table :ygg/nodes
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table :ygg/edges
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table :ygg/chunks
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table :ygg/search-docs
             :constraints {:project-id "project-a"
                           :repo-id "app"
                           :active? true}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table :ygg/embeddings
             :constraints {:project-id "project-a"
                           :repo-id "app"
                           :active? true}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @count-calls))
    (is (= [{:table :ygg/edges
             :fields [:source-id :target-id]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @degree-calls))
    (is (= [{:ids ["node:a" "node:b"]
             :opts {:project-id "project-a"
                    :repo-id "app"
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}]
           @node-calls))))

(deftest docs-for-and-audit-load-only-attachment-path-chunks
  (let [calls (atom [])
        overlay {:docs [{:target "system:billing"
                         :role "contract"
                         :status "accepted"
                         :source {:repo "app"
                                  :path "docs/billing.md"}}
                        {:target "system:billing"
                         :role "runbook"
                         :status "accepted"
                         :source {:repo "app"
                                  :path "docs/missing.md"}}]
                 :systems [{:id "system:billing"
                            :label "Billing"
                            :status "accepted"}]}
        chunk {:xt/id "chunk:billing"
               :project-id "project-a"
               :repo-id "app"
               :path "docs/billing.md"
               :kind :markdown
               :label "Billing"
               :text "billing docs"
               :source-line 1
               :active? true}]
    (with-redefs [query/chunks-by-paths
                  (fn [_ paths opts]
                    (swap! calls conj {:paths (vec paths)
                                       :opts opts})
                    (if (some #{"docs/billing.md"} paths)
                      [chunk]
                      []))
                  query/all-chunks
                  (fn [& _]
                    (throw (ex-info "attachment docs should not hydrate all chunks"
                                    {})))]
      (let [docs-result (context/docs-for
                         {:node :stub}
                         "system:billing"
                         {:project-id "project-a"
                          :map-overlay overlay
                          :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})
            audit-result (context/docs-audit
                          {:node :stub}
                          {:project-id "project-a"
                           :map-overlay overlay
                           :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})]
        (is (= ["accepted" "stale"] (mapv :status (:docs docs-result))))
        (is (= ["docs/missing.md"] (mapv #(get-in % [:source :path])
                                         (:unresolved audit-result))))))
    (is (= [{:paths ["docs/billing.md" "docs/missing.md"]
             :opts {:project-id "project-a"
                    :repo-id nil
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}
            {:paths ["docs/billing.md" "docs/missing.md"]
             :opts {:project-id "project-a"
                    :repo-id nil
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}]
           @calls))))

(defn- file-row
  [id path]
  {:xt/id id
   :project-id "project-a"
   :repo-id "app"
   :path path
   :ext "clj"
   :kind :source
   :content-sha (str "sha:" id)
   :mtime-ms 1
   :size-bytes 1
   :active? true
   :run-id "run:index"})

(defn- old-file-owned-rows
  [request]
  (case (:table request)
    :ygg/nodes [{:xt/id "node:a:old" :file-id "file:a"}
                {:xt/id "node:b:old" :file-id "file:b"}]
    :ygg/edges [{:xt/id "edge:a:old" :file-id "file:a"}]
    :ygg/chunks [{:xt/id "chunk:b:old" :file-id "file:b"}]
    :ygg/file-facts []
    :ygg/index-diagnostics []
    :ygg/search-docs [{:xt/id "search-doc:a:old" :file-id "file:a"}]
    []))

(deftest commit-files-batches-existing-file-owned-row-lookups
  (let [calls (atom [])
        txs (atom [])
        summary (with-redefs [store/rows-with-field-values
                              (fn [_ request]
                                (swap! calls conj request)
                                (old-file-owned-rows request))
                              store/rows-by-field
                              (fn [& _]
                                (throw (ex-info "rows-by-field should not be used by batch file commits"
                                                {})))
                              xt/execute-tx
                              (fn [_ ops]
                                (swap! txs conj ops)
                                {:tx-id 1})]
                  (store/commit-files!
                   {:node :stub}
                   [{:file-row (file-row "file:a" "src/a.clj")
                     :extraction {}}
                    {:file-row (file-row "file:b" "src/b.clj")
                     :extraction {}}]
                   {:batch-size 50}))]
    (is (= {:nodes 0
            :edges 0
            :chunks 0
            :file-facts 0
            :diagnostics 0
            :search-docs 0}
           summary))
    (is (= 6 (count @calls)))
    (is (= (set (map store/table-ref [:nodes
                                      :edges
                                      :chunks
                                      :file-facts
                                      :diagnostics
                                      :search-docs]))
           (set (map :table @calls))))
    (is (every? #(= :file-id (:field %)) @calls))
    (is (every? #(= ["file:a" "file:b"] (:values %)) @calls))
    (is (every? #(= [:xt/id :file-id] (:return-fields %)) @calls))
    (is (some #(and (= :delete-docs (first %))
                    (= (:nodes store/tables) (second %))
                    (= #{"node:a:old" "node:b:old"} (set (nnext %))))
              (first @txs)))
    (is (some #(and (= :delete-docs (first %))
                    (= (:search-docs store/tables) (second %))
                    (= ["search-doc:a:old"] (vec (nnext %))))
              (first @txs)))))

(deftest commit-file-deletes-batch-existing-file-owned-row-lookups
  (let [calls (atom [])
        txs (atom [])
        valid-from #inst "2026-01-01T00:00:00Z"
        summary (with-redefs [store/rows-with-field-values
                              (fn [_ request]
                                (swap! calls conj request)
                                (old-file-owned-rows request))
                              store/rows-by-field
                              (fn [& _]
                                (throw (ex-info "rows-by-field should not be used by batch file deletes"
                                                {})))
                              xt/execute-tx
                              (fn [_ ops]
                                (swap! txs conj ops)
                                {:tx-id 2})]
                  (store/commit-file-deletes!
                   {:node :stub}
                   [(file-row "file:a" "src/a.clj")
                    (file-row "file:b" "src/b.clj")]
                   {:valid-from valid-from}
                   {:batch-size 50}))]
    (is (= {:files-deleted 2
            :nodes-deleted 2
            :edges-deleted 1
            :chunks-deleted 1
            :file-facts-deleted 0
            :diagnostics-deleted 0
            :search-docs-deleted 1}
           summary))
    (is (= 6 (count @calls)))
    (is (every? #(= ["file:a" "file:b"] (:values %)) @calls))
    (is (every? #(= {:valid-at valid-from} (:read-context %)) @calls))
    (is (some #(and (= :delete-docs (first %))
                    (= (:files store/tables) (get-in % [1 :from]))
                    (= valid-from (get-in % [1 :valid-from]))
                    (= #{"file:a" "file:b"} (set (nnext %))))
              (first @txs)))))

(deftest commit-system-graph-bounds-search-doc-replacement-by-target-kind
  (let [ordered-calls (atom [])
        value-calls (atom [])
        txs (atom [])
        summary (with-redefs [store/ordered-rows
                              (fn [_ request]
                                (swap! ordered-calls conj request)
                                (case (:table request)
                                  :ygg/system-evidence [{:xt/id "system-evidence:old"}]
                                  :ygg/system-nodes [{:xt/id "system:old"}]
                                  :ygg/system-edges [{:xt/id "system-edge:old"}]
                                  []))
                              store/constrained-rows
                              (fn [& _]
                                (throw (ex-info "system graph replacement should not hydrate full rows"
                                                {})))
                              store/rows-with-field-values
                              (fn [_ request]
                                (swap! value-calls conj request)
                                [{:xt/id "search-doc:system-node"}
                                 {:xt/id "search-doc:system-edge"}])
                              xt/execute-tx
                              (fn [_ ops]
                                (swap! txs conj ops)
                                {:tx-id 3})]
                  (store/commit-system-graph!
                   {:node :stub}
                   "project-a"
                   {:evidence []
                    :nodes []
                    :edges []
                    :search-docs []}))]
    (is (= {:system-evidence 0
            :system-nodes 0
            :system-edges 0
            :search-docs 0}
           summary))
    (is (= [{:table (:system-evidence store/tables)
             :constraints {:project-id "project-a"}
             :return-fields [:xt/id]}
            {:table (:system-nodes store/tables)
             :constraints {:project-id "project-a"}
             :return-fields [:xt/id]}
            {:table (:system-edges store/tables)
             :constraints {:project-id "project-a"}
             :return-fields [:xt/id]}]
           (mapv #(select-keys % [:table :constraints :return-fields])
                 @ordered-calls)))
    (is (= [{:table (:search-docs store/tables)
             :field :target-kind
             :values [:system-node :system-edge]
             :constraints {:project-id "project-a"}
             :return-fields [:xt/id]}]
           @value-calls))
    (is (some #(and (= :delete-docs (first %))
                    (= (:system-evidence store/tables) (second %))
                    (= ["system-evidence:old"] (vec (nnext %))))
              (first @txs)))
    (is (some #(and (= :delete-docs (first %))
                    (= (:system-nodes store/tables) (second %))
                    (= ["system:old"] (vec (nnext %))))
              (first @txs)))
    (is (some #(and (= :delete-docs (first %))
                    (= (:system-edges store/tables) (second %))
                    (= ["system-edge:old"] (vec (nnext %))))
              (first @txs)))
    (is (some #(and (= :delete-docs (first %))
                    (= (:search-docs store/tables) (second %))
                    (= #{"search-doc:system-node" "search-doc:system-edge"}
                       (set (nnext %))))
              (first @txs)))))

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

(deftest find-node-substring-uses-token-pushdown-for-real-handles
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [& _] [])
                  store/constrained-rows
                  (fn [& _] [])
                  store/rows-matching-any-token
                  (fn [_ table fields tokens constraints ctx return-fields]
                    (swap! calls conj {:table table
                                       :fields fields
                                       :tokens tokens
                                       :constraints constraints
                                       :ctx ctx
                                       :return-fields return-fields})
                    [{:xt/id "node:handler"
                      :project-id "project-a"
                      :repo-id "app"
                      :label "AppHandler"
                      :active? true}])
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "find-node should not hydrate all nodes for substring fallback"
                                    {})))]
      (is (= "node:handler"
             (:xt/id (query/find-node
                      {:node :stub}
                      "handler"
                      {:project-id "project-a"
                       :repo-id "app"
                       :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [{:table (:nodes store/tables)
             :fields [:label]
             :tokens ["handler"]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table :fields :tokens :constraints :ctx]) @calls)))
    (is (some #{:xt/id} (:return-fields (first @calls))))
    (is (some #{:label} (:return-fields (first @calls))))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest find-system-node-substring-uses-token-pushdown-for-real-handles
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [& _] [])
                  store/constrained-rows
                  (fn [& _] [])
                  store/rows-matching-any-token
                  (fn [_ table fields tokens constraints ctx return-fields]
                    (swap! calls conj {:table table
                                       :fields fields
                                       :tokens tokens
                                       :constraints constraints
                                       :ctx ctx
                                       :return-fields return-fields})
                    [{:xt/id "system:billing"
                      :project-id "project-a"
                      :system-key "billing-service"
                      :label "Billing"
                      :active? true}])
                  query/all-system-nodes
                  (fn [& _]
                    (throw (ex-info "find-system-node should not hydrate all system nodes for substring fallback"
                                    {})))]
      (is (= "system:billing"
             (:xt/id (query/find-system-node
                      {:node :stub}
                      "service"
                      {:project-id "project-a"
                       :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (is (= [{:table (:system-nodes store/tables)
             :fields [:label :system-key]
             :tokens ["service"]
             :constraints {:project-id "project-a"
                           :active? true}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table :fields :tokens :constraints :ctx]) @calls)))
    (is (some #{:xt/id} (:return-fields (first @calls))))
    (is (some #{:system-key} (:return-fields (first @calls))))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest graph-path-batches-bfs-frontier-edge-reads
  (let [calls (atom [])
        nodes {"node:a" {:xt/id "node:a"
                         :project-id "project-a"
                         :repo-id "app"
                         :label "A"
                         :active? true}
               "node:b" {:xt/id "node:b"
                         :project-id "project-a"
                         :repo-id "app"
                         :label "B"
                         :active? true}
               "node:c" {:xt/id "node:c"
                         :project-id "project-a"
                         :repo-id "app"
                         :label "C"
                         :active? true}
               "node:d" {:xt/id "node:d"
                         :project-id "project-a"
                         :repo-id "app"
                         :label "D"
                         :active? true}}
        edges-by-frontier {#{"node:a"} [{:xt/id "edge:a-b"
                                         :project-id "project-a"
                                         :repo-id "app"
                                         :source-id "node:a"
                                         :target-id "node:b"}
                                        {:xt/id "edge:a-d"
                                         :project-id "project-a"
                                         :repo-id "app"
                                         :source-id "node:a"
                                         :target-id "node:d"}]
                           #{"node:b" "node:d"} [{:xt/id "edge:b-c"
                                                  :project-id "project-a"
                                                  :repo-id "app"
                                                  :source-id "node:b"
                                                  :target-id "node:c"}]}]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (case (:table request)
                      :ygg/nodes (keep nodes (:values request))
                      :ygg/edges (get edges-by-frontier (set (:values request)) [])
                      []))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "constrained-rows should not be used for exact graph path"
                                    {})))
                  store/edge-rows-touching-ids
                  (fn [& _]
                    (throw (ex-info "touching adjacency should not be used for directed graph path"
                                    {})))]
      (is (= ["A" "B" "C"]
             (mapv :label
                   (query/graph-path
                    {:node :stub}
                    "node:a"
                    "node:c"
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (let [edge-calls (filter #(and (= (:edges store/tables) (:table %))
                                   (= :source-id (:field %)))
                             @calls)
          node-calls (filter #(= (:nodes store/tables) (:table %)) @calls)]
      (is (= [["node:a"] ["node:b" "node:d"]]
             (mapv (comp vec :values) edge-calls)))
      (is (every? #(= {:project-id "project-a"
                       :repo-id "app"}
                      (:constraints %))
                  edge-calls))
      (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"}
                      (:read-context %))
                  edge-calls))
      (is (= [["node:a"] ["node:c"] ["node:b"]]
             (mapv (comp vec :values) node-calls))))))

(deftest overview-graph-uses-active-source-row-helpers
  (let [calls (atom [])
        nodes [{:xt/id "node:a"
                :project-id "project-a"
                :repo-id "app"
                :label "A"
                :kind :var
                :active? true}
               {:xt/id "node:b"
                :project-id "project-a"
                :repo-id "app"
                :label "B"
                :kind :var
                :active? true}]
        edges [{:xt/id "edge:a-b"
                :project-id "project-a"
                :repo-id "app"
                :source-id "node:a"
                :target-id "node:b"
                :relation :uses
                :active? true}]]
    (with-redefs [query/active-nodes
                  (fn [_ opts]
                    (swap! calls conj [:nodes opts])
                    nodes)
                  query/active-edges
                  (fn [_ opts]
                    (swap! calls conj [:edges opts])
                    edges)
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "overview graph should not hydrate all nodes"
                                    {})))
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "overview graph should not hydrate all edges"
                                    {})))
                  store/metadata-for-targets (fn [& _] [])
                  store/metadata-defs (fn [& _] [])]
      (let [result (graph/overview-graph
                    {:node :stub}
                    {:project-id "project-a"
                     :repo-id "app"
                     :limit 10
                     :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= #{"node:a" "node:b"} (set (map :id (:nodes result)))))
        (is (= ["edge:a-b"] (mapv :id (:edges result))))))
    (is (= [[:nodes {:project-id "project-a"
                     :repo-id "app"
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
            [:edges {:project-id "project-a"
                     :repo-id "app"
                     :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]]
           @calls))))

(deftest query-graph-uses-bounded-neighborhood-reads
  (let [edge-calls (atom [])
        node-calls (atom [])
        chunk-calls (atom [])
        edge {:xt/id "edge:seed-neighbor"
              :project-id "project-a"
              :repo-id "app"
              :source-id "node:seed"
              :target-id "node:neighbor"
              :relation :references
              :active? true}
        nodes {"node:seed" {:xt/id "node:seed"
                            :project-id "project-a"
                            :repo-id "app"
                            :label "Seed"
                            :kind :var
                            :active? true}
               "node:neighbor" {:xt/id "node:neighbor"
                                :project-id "project-a"
                                :repo-id "app"
                                :label "Neighbor"
                                :kind :var
                                :active? true}}]
    (with-redefs [query/semantic-query
                  (fn [& _]
                    [{:target-id "node:seed"
                      :score 1.0}])
                  store/edge-rows-touching-ids
                  (fn [_ ids constraints ctx]
                    (swap! edge-calls conj {:ids (set ids)
                                            :constraints constraints
                                            :ctx ctx})
                    (if (contains? (set ids) "node:seed")
                      [edge]
                      []))
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (swap! node-calls conj {:ids (set ids)
                                            :opts opts})
                    (keep nodes ids))
                  query/chunks-by-ids
                  (fn [_ ids opts]
                    (swap! chunk-calls conj {:ids (set ids)
                                             :opts opts})
                    [])
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "query graph should not hydrate all edges"
                                    {})))
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "query graph should not hydrate all nodes"
                                    {})))
                  query/all-chunks
                  (fn [& _]
                    (throw (ex-info "query graph should not hydrate all chunks"
                                    {})))
                  store/metadata-for-targets (fn [& _] [])
                  store/metadata-defs (fn [& _] [])]
      (let [result (graph/query-graph
                    {:node :stub}
                    "seed"
                    {:project-id "project-a"
                     :repo-id "app"
                     :retriever :lexical
                     :depth 1
                     :limit 5
                     :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= #{"node:seed" "node:neighbor"} (set (map :id (:nodes result)))))
        (is (= ["edge:seed-neighbor"] (mapv :id (:edges result))))))
    (is (= [{:ids #{"node:seed"}
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:ids #{"node:seed" "node:neighbor"}
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @edge-calls))
    (is (= [{:ids #{"node:seed" "node:neighbor"}
             :opts {:project-id "project-a"
                    :repo-id "app"
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}]
           @node-calls))
    (is (= @node-calls @chunk-calls))))

(deftest deps-graph-uses-bounded-neighborhood-reads-for-node-targets
  (let [edge-calls (atom [])
        node-calls (atom [])
        chunk-calls (atom [])
        edge {:xt/id "edge:seed-neighbor"
              :project-id "project-a"
              :repo-id "app"
              :source-id "node:seed"
              :target-id "node:neighbor"
              :relation :uses
              :active? true}
        nodes {"node:seed" {:xt/id "node:seed"
                            :project-id "project-a"
                            :repo-id "app"
                            :label "Seed"
                            :kind :var
                            :active? true}
               "node:neighbor" {:xt/id "node:neighbor"
                                :project-id "project-a"
                                :repo-id "app"
                                :label "Neighbor"
                                :kind :var
                                :active? true}}]
    (with-redefs [query/find-node
                  (fn [& _]
                    (get nodes "node:seed"))
                  store/edge-rows-touching-ids
                  (fn [_ ids constraints ctx]
                    (swap! edge-calls conj {:ids (set ids)
                                            :constraints constraints
                                            :ctx ctx})
                    (if (contains? (set ids) "node:seed")
                      [edge]
                      []))
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (swap! node-calls conj {:ids (set ids)
                                            :opts opts})
                    (keep nodes ids))
                  query/chunks-by-ids
                  (fn [_ ids opts]
                    (swap! chunk-calls conj {:ids (set ids)
                                             :opts opts})
                    [])
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "deps graph should not hydrate all edges"
                                    {})))
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "deps graph should not hydrate all nodes"
                                    {})))
                  query/all-chunks
                  (fn [& _]
                    (throw (ex-info "deps graph should not hydrate all chunks"
                                    {})))
                  store/metadata-for-targets (fn [& _] [])
                  store/metadata-defs (fn [& _] [])]
      (let [result (graph/deps-graph
                    {:node :stub}
                    "seed"
                    {:project-id "project-a"
                     :repo-id "app"
                     :depth 1
                     :limit 5
                     :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= #{"node:seed" "node:neighbor"} (set (map :id (:nodes result)))))
        (is (= ["edge:seed-neighbor"] (mapv :id (:edges result))))))
    (is (= [{:ids #{"node:seed"}
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:ids #{"node:seed" "node:neighbor"}
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @edge-calls))
    (is (= [{:ids #{"node:seed" "node:neighbor"}
             :opts {:project-id "project-a"
                    :repo-id "app"
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}]
           @node-calls))
    (is (= @node-calls @chunk-calls))))

(deftest evidence-summary-uses-counts-for-source-graph-and-index-tables
  (let [active-count-calls (atom [])
        exact-count-calls (atom [])
        grouped-count-calls (atom [])
        grouped-context-calls (atom [])
        fail-broad-read (fn [& _]
                          (throw (ex-info "evidence summary should use count queries"
                                          {})))
        count-values {[:ygg/nodes {:project-id "project-a"
                                   :repo-id "app"}] 7
                      [:ygg/edges {:project-id "project-a"
                                   :repo-id "app"}] 9
                      [:ygg/chunks {:project-id "project-a"
                                    :repo-id "app"}] 3
                      [:ygg/search-docs {:project-id "project-a"
                                         :repo-id "app"}] 4
                      [:ygg/embeddings {:project-id "project-a"
                                        :repo-id "app"}] 5
                      [:ygg/file-facts {:project-id "project-a"
                                        :repo-id "app"}] 2
                      [:ygg/system-evidence {:project-id "project-a"
                                             :repo-id "app"}] 4
                      [:ygg/system-nodes {:project-id "project-a"}] 2
                      [:ygg/system-edges {:project-id "project-a"}] 1}
        exact-count-values {[:ygg/activity-items {:project-id "project-a"
                                                  :active? true}] 5
                            [:ygg/activity-events {:project-id "project-a"
                                                   :active? true}] 4
                            [:ygg/activity-events {:project-id "project-a"
                                                   :active? true
                                                   :event-kind :validation}] 1
                            [:ygg/activity-events {:project-id "project-a"
                                                   :active? true
                                                   :event-kind :result-schema-mismatch}] 2}]
    (with-redefs [coverage/project-coverage
                  (fn [& _]
                    {:totals {:skipped 0}
                     :files-by-kind []
                     :extractors []
                     :indexedConnectivity {:indexedFiles 0
                                           :nodes 0
                                           :edges 0
                                           :connectedFiles 0
                                           :crossFileConnectedFiles 0
                                           :isolatedFiles 0
                                           :byKind []}
                     :skipped-by-extension []
                     :skipped-by-reason []
                     :diagnostics {:total 0}})
                  dependency/package-report
                  (fn [& _]
                    {:counts {:packages 0
                              :versions 0
                              :imports-package 0
                              :source-import-candidates 0
                              :version-conflicts 0
                              :declared-without-import-evidence 0
                              :unresolved-imports 0}
                     :ecosystems []})
                  store/constrained-rows
                  (fn [_ table constraints & [_ctx]]
                    (case table
                      :ygg/files [{:xt/id "file:a"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :path "src/a.clj"
                                   :kind :source
                                   :content-sha "sha:a"
                                   :mtime-ms 1
                                   :size-bytes 1
                                   :active? true}]
                      (throw (ex-info "unexpected constrained read"
                                      {:table table
                                       :constraints constraints}))))
                  store/active-row-count
                  (fn [_ table constraints ctx]
                    (swap! active-count-calls conj {:table table
                                                    :constraints constraints
                                                    :ctx ctx})
                    (get count-values [table constraints] 0))
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! exact-count-calls conj {:table table
                                                   :constraints constraints
                                                   :ctx ctx})
                    (get exact-count-values [table constraints] 0))
                  store/active-row-counts-by-field
                  (fn [_ table field constraints ctx]
                    (swap! grouped-count-calls conj {:table table
                                                     :field field
                                                     :constraints constraints
                                                     :ctx ctx})
                    (case [table field]
                      [:ygg/nodes :kind] [{:value :namespace
                                           :count 6}
                                          {:value :var
                                           :count 1}]
                      [:ygg/edges :relation] [{:value :imports
                                               :count 8}
                                              {:value :uses
                                               :count 1}]
                      [:ygg/file-facts :kind] [{:value :auth-reference
                                                :count 1}
                                               {:value :url
                                                :count 1}]
                      [:ygg/system-evidence :kind] [{:value :auth-reference
                                                     :count 2}
                                                    {:value :env-var
                                                     :count 1}
                                                    {:value :url
                                                     :count 1}]
                      []))
                  store/active-row-counts-by-fields
                  (fn [_ table fields constraints ctx]
                    (swap! grouped-context-calls conj {:table table
                                                       :fields fields
                                                       :constraints constraints
                                                       :ctx ctx})
                    (case [table fields]
                      [:ygg/system-evidence [:kind :url-context :auth-context]]
                      [{:values {:kind :auth-reference
                                 :auth-context :service-account}
                        :count 1}
                       {:values {:kind :auth-reference
                                 :auth-context :api-key}
                        :count 1}
                       {:values {:kind :env-var}
                        :count 1}
                       {:values {:kind :url
                                 :url-context :runtime-config}
                        :count 1}]

                      [:ygg/activity-items [:expected-result-schema :result-schema]]
                      [{:values {:expected-result-schema "schema/a"
                                 :result-schema "schema/a"}
                        :count 2}
                       {:values {:expected-result-schema "schema/a"
                                 :result-schema "schema/b"}
                        :count 1}
                       {:values {:expected-result-schema "schema/c"}
                        :count 1}
                       {:values {:result-schema "schema/d"}
                        :count 1}]

                      []))
                  query/all-nodes fail-broad-read
                  query/all-edges fail-broad-read
                  query/all-chunks fail-broad-read
                  query/all-search-docs fail-broad-read
                  query/all-embeddings fail-broad-read
                  query/all-system-nodes fail-broad-read
                  query/all-system-edges fail-broad-read
                  query/all-system-evidence fail-broad-read
                  activity/all-items fail-broad-read
                  activity/all-events fail-broad-read]
      (let [summary (evidence/summarize
                     {:node :stub}
                     {:id "project-a"
                      :repos [{:id "app"
                               :root "."}]}
                     {:repo-id "app"
                      :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}})]
        (is (= {:nodes 7
                :edges 9
                :chunks 3
                :search-docs 4
                :embeddings 5
                :file-facts 2
                :system-evidence 4
                :runtime-config-evidence 4
                :auth-references 2
                :service-account-references 1
                :api-key-references 1
                :system-nodes 2
                :system-edges 1
                :activity-items 5
                :activity-events 4
                :validation-events 1
                :result-schema-mismatch-events 2
                :result-schema-statuses {:matching 2
                                         :mismatch 1
                                         :missing-result 1
                                         :unexpected-result 1}
                :result-schema-status-items 5}
               (select-keys (:counts summary)
                            [:nodes
                             :edges
                             :chunks
                             :search-docs
                             :embeddings
                             :file-facts
                             :system-evidence
                             :runtime-config-evidence
                             :auth-references
                             :service-account-references
                             :api-key-references
                             :system-nodes
                             :system-edges
                             :activity-items
                             :activity-events
                             :validation-events
                             :result-schema-mismatch-events
                             :result-schema-statuses
                             :result-schema-status-items])))
        (is (= [{:value "namespace"
                 :count 6}
                {:value "var"
                 :count 1}]
               (:top-node-kinds summary)))
        (is (= [{:value "imports"
                 :count 8}
                {:value "uses"
                 :count 1}]
               (:top-edge-relations summary)))
        (is (= {:nodes [{:value "namespace"
                         :count 6}
                        {:value "var"
                         :count 1}]
                :edges [{:value "imports"
                         :count 8}
                        {:value "uses"
                         :count 1}]}
               (get-in summary [:kinds :source-graph])))
        (is (= [{:kind :auth-reference
                 :count 1}
                {:kind :url
                 :count 1}]
               (get-in summary [:kinds :file-facts])))
        (is (= [{:kind :auth-reference
                 :count 2}
                {:kind :env-var
                 :count 1}
                {:kind :url
                 :count 1}]
               (get-in summary [:kinds :system-evidence])))))
    (is (= #{{:table :ygg/nodes
              :field :kind
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/edges
              :field :relation
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/file-facts
              :field :kind
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-evidence
              :field :kind
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @grouped-count-calls)))
    (is (= [{:table :ygg/system-evidence
             :fields [:kind :url-context :auth-context]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table :ygg/activity-items
             :fields [:expected-result-schema :result-schema]
             :constraints {:active? true
                           :project-id "project-a"}
             :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           @grouped-context-calls))
    (is (= #{{:table :ygg/nodes
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/edges
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/chunks
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/search-docs
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/embeddings
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/file-facts
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-evidence
              :constraints {:project-id "project-a"
                            :repo-id "app"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-nodes
              :constraints {:project-id "project-a"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/system-edges
              :constraints {:project-id "project-a"}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @active-count-calls)))
    (is (= #{{:table :ygg/activity-items
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true
                            :event-kind :validation}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}
             {:table :ygg/activity-events
              :constraints {:project-id "project-a"
                            :active? true
                            :event-kind :result-schema-mismatch}
              :ctx {:valid-at #inst "2026-01-01T00:00:00Z"}}}
           (set @exact-count-calls)))))

(deftest deps-graph-uses-targeted-package-edge-reads
  (let [edge-calls (atom [])
        node-calls (atom [])
        chunk-calls (atom [])
        package {:xt/id "package:react"
                 :project-id "project-a"
                 :repo-id "app"
                 :label "npm:react"
                 :kind :external-package
                 :active? true}
        nodes {"package:react" package
               "manifest:package-json" {:xt/id "manifest:package-json"
                                        :project-id "project-a"
                                        :repo-id "app"
                                        :label "package.json"
                                        :kind :dependency-lock
                                        :active? true}
               "node:src-app" {:xt/id "node:src-app"
                               :project-id "project-a"
                               :repo-id "app"
                               :label "src.app"
                               :kind :namespace
                               :active? true}
               "version:react-19" {:xt/id "version:react-19"
                                   :project-id "project-a"
                                   :repo-id "app"
                                   :label "npm:react@19.1.0"
                                   :kind :external-package-version
                                   :active? true}
               "lock:package-lock" {:xt/id "lock:package-lock"
                                    :project-id "project-a"
                                    :repo-id "app"
                                    :label "package-lock.json"
                                    :kind :dependency-lock
                                    :active? true}}
        package-target-edges [{:xt/id "edge:requires-react"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "manifest:package-json"
                               :target-id "package:react"
                               :relation :requires
                               :active? true}
                              {:xt/id "edge:imports-react"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "node:src-app"
                               :target-id "package:react"
                               :relation :imports-package
                               :active? true}
                              {:xt/id "edge:react-19-version"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "version:react-19"
                               :target-id "package:react"
                               :relation :version-of
                               :active? true}
                              {:xt/id "edge:inactive-requires-react"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "manifest:old"
                               :target-id "package:react"
                               :relation :requires
                               :active? false}
                              {:xt/id "edge:ignored-package-relation"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "node:other"
                               :target-id "package:react"
                               :relation :uses
                               :active? true}]
        version-target-edges [{:xt/id "edge:lock-resolves-react-19"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "lock:package-lock"
                               :target-id "version:react-19"
                               :relation :resolves
                               :active? true}
                              {:xt/id "edge:ignored-version-relation"
                               :project-id "project-a"
                               :repo-id "app"
                               :source-id "node:other"
                               :target-id "version:react-19"
                               :relation :uses
                               :active? true}]]
    (with-redefs [query/find-node
                  (fn [& _] package)
                  store/rows-with-field-values
                  (fn [_ request]
                    (swap! edge-calls conj request)
                    (condp = (set (:values request))
                      #{"package:react"} package-target-edges
                      #{"version:react-19"} version-target-edges
                      []))
                  query/nodes-by-ids
                  (fn [_ ids opts]
                    (swap! node-calls conj {:ids (vec ids)
                                            :opts opts})
                    (keep nodes ids))
                  query/chunks-by-ids
                  (fn [_ ids opts]
                    (swap! chunk-calls conj {:ids (vec ids)
                                             :opts opts})
                    [])
                  query/all-edges
                  (fn [& _]
                    (throw (ex-info "package deps graph should not hydrate all edges"
                                    {})))
                  query/all-nodes
                  (fn [& _]
                    (throw (ex-info "package deps graph should not hydrate all nodes"
                                    {})))
                  query/all-chunks
                  (fn [& _]
                    (throw (ex-info "package deps graph should not hydrate all chunks"
                                    {})))
                  store/metadata-for-targets (fn [& _] [])
                  store/metadata-defs (fn [& _] [])]
      (let [result (graph/deps-graph
                    {:node :stub}
                    "npm:react"
                    {:project-id "project-a"
                     :repo-id "app"
                     :depth 1
                     :limit 10
                     :valid-at #inst "2026-01-01T00:00:00Z"})]
        (is (= #{"package:react"
                 "manifest:package-json"
                 "node:src-app"
                 "version:react-19"
                 "lock:package-lock"}
               (set (map :id (:nodes result)))))
        (is (= ["edge:requires-react"
                "edge:imports-react"
                "edge:react-19-version"
                "edge:lock-resolves-react-19"]
               (mapv :id (:edges result))))
        (is (= ["requires" "imports-package" "version-of" "resolves"]
               (mapv :relation (:edges result))))))
    (is (= [{:table (:edges store/tables)
             :field :target-id
             :values ["package:react"]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}
            {:table (:edges store/tables)
             :field :target-id
             :values ["version:react-19"]
             :constraints {:project-id "project-a"
                           :repo-id "app"}
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table :field :values :constraints :read-context])
                 @edge-calls)))
    (is (= [{:ids ["package:react"
                   "manifest:package-json"
                   "node:src-app"
                   "version:react-19"
                   "lock:package-lock"]
             :opts {:project-id "project-a"
                    :repo-id "app"
                    :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}}]
           @node-calls))
    (is (= @node-calls @chunk-calls))))

(deftest system-path-batches-bfs-frontier-edge-reads
  (let [calls (atom [])
        nodes {"system:a" {:xt/id "system:a"
                           :project-id "project-a"
                           :repo-id "app"
                           :system-key "a"
                           :label "A"
                           :active? true}
               "system:b" {:xt/id "system:b"
                           :project-id "project-a"
                           :repo-id "app"
                           :system-key "b"
                           :label "B"
                           :active? true}
               "system:c" {:xt/id "system:c"
                           :project-id "project-a"
                           :repo-id "app"
                           :system-key "c"
                           :label "C"
                           :active? true}
               "system:d" {:xt/id "system:d"
                           :project-id "project-a"
                           :repo-id "app"
                           :system-key "d"
                           :label "D"
                           :active? true}}
        edges-by-frontier {#{"system:a"} [{:xt/id "system-edge:a-b"
                                           :project-id "project-a"
                                           :repo-id "app"
                                           :source-id "system:a"
                                           :target-id "system:b"
                                           :active? true}
                                          {:xt/id "system-edge:a-d"
                                           :project-id "project-a"
                                           :repo-id "app"
                                           :source-id "system:a"
                                           :target-id "system:d"
                                           :active? true}]
                           #{"system:b" "system:d"} [{:xt/id "system-edge:b-c"
                                                      :project-id "project-a"
                                                      :repo-id "app"
                                                      :source-id "system:b"
                                                      :target-id "system:c"
                                                      :active? true}]}]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (case (:table request)
                      :ygg/system-nodes (keep nodes (:values request))
                      :ygg/system-edges (get edges-by-frontier (set (:values request)) [])
                      []))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "constrained-rows should not be used for exact system path"
                                    {})))]
      (is (= ["A" "B" "C"]
             (mapv :label
                   (query/system-path
                    {:node :stub}
                    "system:a"
                    "system:c"
                    {:project-id "project-a"
                     :repo-id "app"
                     :valid-at #inst "2026-01-01T00:00:00Z"})))))
    (let [edge-calls (filter #(and (= (:system-edges store/tables) (:table %))
                                   (= :source-id (:field %)))
                             @calls)
          node-calls (filter #(= (:system-nodes store/tables) (:table %)) @calls)]
      (is (= [["system:a"] ["system:b" "system:d"]]
             (mapv (comp vec :values) edge-calls)))
      (is (every? #(= {:project-id "project-a"
                       :repo-id "app"
                       :active? true}
                      (:constraints %))
                  edge-calls))
      (is (every? #(= {:valid-at #inst "2026-01-01T00:00:00Z"}
                      (:read-context %))
                  edge-calls))
      (is (= [["system:a"] ["system:c"] ["system:b"]]
             (mapv (comp vec :values) node-calls))))))
