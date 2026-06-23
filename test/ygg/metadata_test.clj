(ns ygg.metadata-test
  (:require [ygg.context :as context]
            [ygg.graph :as graph]
            [ygg.metadata :as metadata]
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

(defn- node-row
  [id label]
  {:xt/id id
   :project-id "test"
   :repo-id "repo"
   :label label
   :kind :namespace
   :file-id "file:test"
   :path (str "src/" label ".clj")
   :active? true
   :run-id "run:test"})

(defn- edge-row
  [id source target]
  {:xt/id id
   :project-id "test"
   :repo-id "repo"
   :source-id source
   :target-id target
   :relation :requires
   :confidence :high
   :file-id "file:test"
   :path "src/alpha.clj"
   :active? true
   :run-id "run:test"})

(defn- search-doc-row
  [id target-id label text]
  {:xt/id id
   :project-id "test"
   :repo-id "repo"
   :target-id target-id
   :target-kind :system-node
   :file-id "file:system"
   :path "src/system.clj"
   :kind :system-node
   :label label
   :text text
   :tokens ["payments" "platform" "owner" "critical"]
   :input-sha "sha:test"
   :active? true
   :run-id "run:system"})

(deftest metadata-target-reads-use-batched-target-id-query
  (let [calls (atom [])]
    (with-redefs [store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    (for [target-id (:values request)]
                      (metadata/row {:project-id (get-in request [:constraints :project-id])
                                     :repo-id (get-in request [:constraints :repo-id])
                                     :target-id target-id
                                     :target-kind :node
                                     :key :owner/team
                                     :value (str "team:" target-id)})))]
      (is (= ["team:target:a" "team:target:b"]
             (mapv :value
                   (store/metadata-for-targets
                    :xtdb
                    ["target:a" "target:a" "target:b"]
                    {:project-id "test"
                     :repo-id "repo"
                     :valid-at t1})))))
    (is (= 1 (count @calls)))
    (is (= {:table (:metadata store/tables)
            :field :target-id
            :values ["target:a" "target:b"]
            :constraints {:project-id "test"
                          :repo-id "repo"}
            :read-context {:project-id "test"
                           :repo-id "repo"
                           :valid-at t1}}
           (select-keys (first @calls)
                        [:table :field :values :constraints :read-context])))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest metadata-commit-single-cardinality-uses-batched-target-id-query
  (let [calls (atom [])
        tx-ops (atom nil)
        rows [(metadata/row {:project-id "test"
                             :repo-id "repo"
                             :target-id "target:a"
                             :target-kind :node
                             :key :owner/team
                             :source :review
                             :value "platform"})
              (metadata/row {:project-id "test"
                             :repo-id "repo"
                             :target-id "target:b"
                             :target-kind :node
                             :key :owner/team
                             :source :review
                             :value "product"})
              (metadata/row {:project-id "test"
                             :repo-id "repo"
                             :target-id "target:a"
                             :target-kind :node
                             :key :runtime/deploy-target
                             :source :review
                             :value "prod"})]]
    (with-redefs [store/metadata-defs
                  (fn [_ ctx]
                    (is (= {:valid-at t2} (select-keys ctx [:valid-at])))
                    {:owner/team {:cardinality :one}
                     :runtime/deploy-target {:cardinality :many}})
                  store/rows-with-field-values
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "metadata:old-a"
                      :project-id "test"
                      :target-id "target:a"
                      :key :owner/team
                      :source :review
                      :active? true}
                     {:xt/id "metadata:old-b"
                      :project-id "test"
                      :target-id "target:b"
                      :key :owner/team
                      :source :review
                      :active? true}
                     {:xt/id "metadata:other-key"
                      :project-id "test"
                      :target-id "target:a"
                      :key :risk/criticality
                      :source :review
                      :active? true}
                     {:xt/id "metadata:other-project"
                      :project-id "other"
                      :target-id "target:a"
                      :key :owner/team
                      :source :review
                      :active? true}])
                  store/execute-tx!
                  (fn [_ ops]
                    (reset! tx-ops ops)
                    {:tx-id 1})]
      (is (= {:metadata 3}
             (store/commit-metadata! :xtdb rows {:valid-from t2}))))
    (is (= 1 (count @calls)))
    (is (= {:table (:metadata store/tables)
            :field :target-id
            :values ["target:a" "target:b"]
            :read-context {:valid-at t2}}
           (select-keys (first @calls)
                        [:table :field :values :read-context])))
    (is (not-any? #{'*} (:return-fields (first @calls))))
    (is (= ["metadata:old-a" "metadata:old-b"]
           (->> @tx-ops
                (filter #(= :delete-docs (first %)))
                (mapv last))))
    (is (= 3 (count (filter #(= :put-docs (first %)) @tx-ops))))))

(deftest metadata-delete-uses-projected-key-source-scope-query
  (let [calls (atom [])
        tx-ops (atom nil)]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "metadata:old"}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "metadata deletion should not hydrate full rows"
                                    {})))
                  store/execute-tx!
                  (fn [_ ops]
                    (reset! tx-ops ops)
                    {:tx-id 1})]
      (is (= {:metadata-deleted 1}
             (store/delete-metadata!
              :xtdb
              {:target-id "target:a"
               :key "owner/team"
               :source "review"
               :project-id "test"
               :repo-id "repo"
               :valid-from t2}))))
    (is (= [{:table (:metadata store/tables)
             :constraints {:target-id "target:a"
                           :key :owner/team
                           :source :review
                           :project-id "test"
                           :repo-id "repo"}
             :return-fields [:xt/id]
             :read-context {:project-id "test"
                            :repo-id "repo"
                            :valid-at t2}}]
           (mapv #(select-keys % [:table :constraints :return-fields :read-context])
                 @calls)))
    (is (= 1 (count @tx-ops)))
    (is (= t2 (get-in (first @tx-ops) [1 :valid-from])))
    (is (= "metadata:old" (last (first @tx-ops))))))

(deftest metadata-cardinality-is-bitemporal
  (store/with-node (temp-dir "ygg-metadata-temporal-xtdb")
    (fn [xtdb]
      (let [target-id "node:test:repo:namespace:alpha"
            first-row (metadata/row {:project-id "test"
                                     :repo-id "repo"
                                     :target-id target-id
                                     :target-kind :node
                                     :key :owner/team
                                     :value "platform"})
            second-row (metadata/row {:project-id "test"
                                      :repo-id "repo"
                                      :target-id target-id
                                      :target-kind :node
                                      :key :owner/team
                                      :value "product"})]
        (store/commit-metadata! xtdb [first-row] {:valid-from t1})
        (store/commit-metadata! xtdb [second-row] {:valid-from t2})
        (is (= ["platform"]
               (mapv :value (store/metadata-for-targets
                             xtdb
                             [target-id]
                             {:project-id "test"
                              :valid-at #inst "2026-01-15T00:00:00Z"}))))
        (is (= ["product"]
               (mapv :value (store/metadata-for-targets
                             xtdb
                             [target-id]
                             {:project-id "test"
                              :valid-at #inst "2026-02-15T00:00:00Z"}))))))))

(deftest graph-export-enriches-and-filters-with-metadata
  (store/with-node (temp-dir "ygg-metadata-graph-xtdb")
    (fn [xtdb]
      (let [alpha-id "node:test:repo:namespace:alpha"
            beta-id "node:test:repo:namespace:beta"
            edge-id "edge:test:repo:alpha:requires:beta"]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :nodes) (node-row alpha-id "alpha"))
          (store/put-op (store/table-ref :nodes) (node-row beta-id "beta"))
          (store/put-op (store/table-ref :edges) (edge-row edge-id alpha-id beta-id))])
        (store/commit-metadata!
         xtdb
         [(metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id alpha-id
                         :target-kind :node
                         :key :owner/team
                         :value "platform"})
          (metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id alpha-id
                         :target-kind :node
                         :key :security/contains-pii?
                         :value true
                         :value-type :boolean})
          (metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id alpha-id
                         :target-kind :node
                         :key :runtime/deploy-target
                         :value "prod"})
          (metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id alpha-id
                         :target-kind :node
                         :key :runtime/deploy-target
                         :value "staging"})
          (metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id beta-id
                         :target-kind :node
                         :key :risk/churn-score
                         :value "0.7"
                         :value-type :number})])
        (store/commit-graph-views!
         xtdb
         [{:xt/id "view:platform"
           :label "Platform"
           :description "Only platform-owned graph targets."
           :project-id "test"
           :node-filter {:metadata {"owner/team" "platform"}}
           :display [:owner/team :security/contains-pii?]
           :active? true}])
        (let [exported (graph/overview-graph xtdb {:project-id "test"
                                                   :limit 20})
              nodes-by-id (into {} (map (juxt :id identity)) (:nodes exported))
              alpha (get nodes-by-id alpha-id)
              beta (get nodes-by-id beta-id)
              filtered (graph/overview-graph xtdb {:project-id "test"
                                                   :limit 20
                                                   :view-id "view:platform"})]
          (is (= graph/schema (:schema exported)))
          (is (= "platform" (get-in alpha [:attrs "owner/team"])))
          (is (= ["prod" "staging"] (get-in alpha [:attrs "runtime/deploy-target"])))
          (is (= ["security/contains-pii"] (:tags alpha)))
          (is (= 0.7 (get-in beta [:metrics "risk/churn-score"])))
          (is (contains? (set (map :key (:metadataDefs exported))) "owner/team"))
          (is (= [alpha-id] (mapv :id (:nodes filtered))))
          (is (empty? (:edges filtered))))))))

(deftest deps-graph-includes-readable-stubs-for-missing-targets
  (store/with-node (temp-dir "ygg-missing-target-graph-xtdb")
    (fn [xtdb]
      (let [alpha-id "node:test:repo:namespace:alpha"
            external-id "project:test:repo:repo:node:namespace:clojure.string"
            edge-id "edge:test:repo:alpha:requires:clojure-string"]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :nodes) (node-row alpha-id "alpha"))
          (store/put-op (store/table-ref :edges) (edge-row edge-id alpha-id external-id))])
        (let [exported (graph/deps-graph xtdb
                                         "alpha"
                                         {:project-id "test"
                                          :depth 1
                                          :limit 20})
              labels (set (map :label (:nodes exported)))]
          (is (contains? labels "alpha"))
          (is (contains? labels "clojure.string"))
          (is (= [external-id]
                 (->> (:edges exported)
                      (filter #(= alpha-id (:source %)))
                      (mapv :target)))))))))

(deftest context-packet-carries-system-metadata
  (store/with-node (temp-dir "ygg-metadata-context-xtdb")
    (fn [xtdb]
      (let [system-id "system:test:payments"]
        (store/commit-system-graph!
         xtdb
         "test"
         {:nodes [{:xt/id system-id
                   :project-id "test"
                   :repo-id "repo"
                   :system-key "payments"
                   :label "Payments API"
                   :kind :service
                   :path-prefix "src/payments"
                   :aliases []
                   :active? true
                   :run-id "run:system"}]
          :edges []
          :evidence []
          :search-docs [(search-doc-row "search:system:test:payments"
                                        system-id
                                        "Payments API"
                                        "payments platform owner critical service")]})
        (store/commit-metadata!
         xtdb
         [(metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id system-id
                         :target-kind :system-node
                         :key :owner/team
                         :value "platform"})
          (metadata/row {:project-id "test"
                         :repo-id "repo"
                         :target-id system-id
                         :target-kind :system-node
                         :key :risk/churn-score
                         :value "0.9"
                         :value-type :number})])
        (let [packet (context/context-packet xtdb
                                             "platform payments"
                                             {:project-id "test"
                                              :retriever :lexical
                                              :budget 2000})
              entity (first (filter #(= system-id (:id %)) (:entities packet)))]
          (is (= context/schema (:schema packet)))
          (is (= "platform" (get-in entity [:attrs "owner/team"])))
          (is (= 0.9 (get-in entity [:metrics "risk/churn-score"])))
          (is (not (contains? entity :tags))))))))
