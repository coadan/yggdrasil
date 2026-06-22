(ns ygg.xtdb-query-pushdown-test
  (:require [ygg.query :as query]
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
