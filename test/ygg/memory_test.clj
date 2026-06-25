(ns ygg.memory-test
  (:require [ygg.activity :as activity]
            [ygg.context :as context]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.embedding :as embedding]
            [ygg.graph :as graph]
            [ygg.memory :as memory]
            [ygg.query :as query]
            [ygg.search-doc :as search-doc]
            [ygg.xtdb :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- empty-dependency-report
  []
  {:schema "ygg.dependency.report/v1"
   :counts {:packages 0
            :versions 0
            :requires 0
            :resolves 0
            :imports-package 0
            :unresolved-imports 0
            :declared-without-import-evidence 0
            :version-conflicts 0}
   :packages []
   :declared-without-import-evidence []
   :unresolved-imports []
   :version-conflicts []})

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- search-doc-for
  [xtdb memory-row]
  (store/row-by-id xtdb
                   (:search-docs store/tables)
                   (search-doc/search-doc-id (:xt/id memory-row))
                   {}))

(deftest memory-rows-default-to-private-developer-suggestions
  (let [row (memory/memory-row
             "fixture"
             {:text "Prefer query packets for project recall."
              :target-ids ["node:query"]
              :now 1800000000000})]
    (is (= memory/schema (:schema row)))
    (is (= :developer (:scope row)))
    (is (= :private (:visibility row)))
    (is (= :suggested (:status row)))
    (is (= :suggested (:confidence row)))
    (is (= ["node:query"] (:target-ids row)))
    (is (= true (:active? row))))
  (testing "non-project memories need an explicit graph target"
    (try
      (memory/memory-row "fixture" {:text "targetless"})
      (is false "expected targetless developer memory to fail")
      (catch clojure.lang.ExceptionInfo ex
        (is (= "Memory requires --target unless --scope project is explicit."
               (ex-message ex))))))
  (testing "project-scope memory can describe project-wide recall"
    (is (= :project
           (:visibility (memory/memory-row
                         "fixture"
                         {:text "Project-wide preference."
                          :scope :project
                          :now 1800000000000}))))))

(deftest reviewed-memory-is-query-visible-but-private-memory-stays-owner-scoped
  (store/with-node
    (temp-dir "ygg-memory-visibility")
    (fn [xtdb]
      (memory/add! xtdb
                   "fixture"
                   {:text "Use the canonical query packet for memory recall."
                    :target-ids ["node:query"]
                    :scope :project
                    :status :reviewed
                    :now 1800000000000})
      (memory/add! xtdb
                   "fixture"
                   {:text "Alice keeps the release checklist in query notes."
                    :target-ids ["node:release"]
                    :owner "alice"
                    :status :reviewed
                    :now 1800000000100})
      (let [public-result (memory/search xtdb
                                         "canonical query memory"
                                         {:project-id "fixture"})
            alice-result (memory/search xtdb
                                        "release checklist"
                                        {:project-id "fixture"
                                         :owner "alice"})
            bob-result (memory/search xtdb
                                      "release checklist"
                                      {:project-id "fixture"
                                       :owner "bob"})
            public-only-result (memory/search xtdb
                                              "release checklist"
                                              {:project-id "fixture"
                                               :owner "alice"
                                               :exclude-private? true})]
        (is (= ["Use the canonical query packet for memory recall."]
               (mapv :text (:memories public-result))))
        (is (= ["Alice keeps the release checklist in query notes."]
               (mapv :text (:memories alice-result))))
        (is (= [] (:memories bob-result)))
        (is (= [] (:memories public-only-result)))))))

(deftest suggested-memory-enters-query-only-through-direct-graph-attachment
  (store/with-node
    (temp-dir "ygg-memory-suggested")
    (fn [xtdb]
      (memory/add! xtdb
                   "fixture"
                   {:text "Draft note about billing retries."
                    :target-ids ["node:billing"]
                    :status :suggested
                    :scope :project
                    :now 1800000000000})
      (is (= []
             (:memories
              (memory/search xtdb
                             "billing retries"
                             {:project-id "fixture"}))))
      (let [direct (:memories
                    (memory/search xtdb
                                   "unrelated"
                                   {:project-id "fixture"
                                    :target-ids ["node:billing"]}))]
        (is (= ["Draft note about billing retries."]
               (mapv :text direct)))
        (is (= [[:graph-attachment :status]]
               (mapv :basis direct)))))))

(deftest memory-lifecycle-updates-are-auditable-xtdb-rows
  (store/with-node
    (temp-dir "ygg-memory-lifecycle")
    (fn [xtdb]
      (let [row (memory/add! xtdb
                             "fixture"
                             {:text "Initial note."
                              :target-ids ["node:old"]
                              :owner "alice"
                              :now 1800000000000})
            accepted (memory/accept! xtdb
                                     (:xt/id row)
                                     "Reviewed during release QA.")
            attached (memory/attach! xtdb
                                     (:xt/id accepted)
                                     ["node:new"]
                                     "New graph target covers the same fact.")
            result (memory/supersede! xtdb
                                      (:xt/id attached)
                                      {:text "Replacement note."
                                       :reason "More precise wording."
                                       :target-ids ["node:new"]
                                       :owner "alice"})]
        (is (= :reviewed (:status accepted)))
        (is (= #{"node:old" "node:new"}
               (set (:target-ids attached))))
        (is (= (:xt/id attached) (:superseded result)))
        (is (= "Replacement note."
               (get-in result [:memory :text])))
        (is (= :superseded
               (:status (memory/memory-by-id xtdb (:xt/id attached)))))
        (is (= false
               (:active? (memory/memory-by-id xtdb (:xt/id attached)))))))))

(deftest reviewed-memory-enters-normal-query-through-search-docs
  (store/with-node
    (temp-dir "ygg-memory-search-docs")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (:system-nodes store/tables)
                      {:xt/id "system:query"
                       :project-id "fixture"
                       :system-key "query"
                       :label "Query Surface"
                       :kind :workflow
                       :active? true})])
      (let [reviewed (memory/add! xtdb
                                  "fixture"
                                  {:text "Normal query packets should include reviewed memory."
                                   :summary "Memory joins query packets."
                                   :target-ids ["system:query"]
                                   :scope :project
                                   :status :reviewed
                                   :now 1800000000000})
            suggested (memory/add! xtdb
                                   "fixture"
                                   {:text "Draft billing retry note."
                                    :target-ids ["system:query"]
                                    :scope :project
                                    :status :suggested
                                    :now 1800000000100})
            reviewed-doc (search-doc-for xtdb reviewed)
            suggested-doc (search-doc-for xtdb suggested)
            lexical-results (query/semantic-query xtdb
                                                  "query packets memory"
                                                  {:project-id "fixture"
                                                   :retriever :lexical
                                                   :limit 5})
            client (embedding/fake-client {:model "memory-docs"})
            embed-result (embedding/embed-search-docs! xtdb
                                                       client
                                                       {:project-id "fixture"})
            semantic-results (query/semantic-query xtdb
                                                   "query packets memory"
                                                   {:project-id "fixture"
                                                    :retriever :semantic
                                                    :embedding-client client
                                                    :limit 5})]
        (is (= :memory (:target-kind reviewed-doc)))
        (is (= :memory (:kind reviewed-doc)))
        (is (= true (:active? reviewed-doc)))
        (is (str/includes? (:text reviewed-doc) "Query Surface"))
        (is (= false (:active? suggested-doc)))
        (is (some #(= (:xt/id reviewed) (:target-id %)) lexical-results))
        (is (not-any? #(= (:xt/id suggested) (:target-id %)) lexical-results))
        (is (= 1 (:pending embed-result)))
        (is (= 1 (:embedded embed-result)))
        (is (some #(= (:xt/id reviewed) (:target-id %)) semantic-results))))))

(deftest context-packet-seamlessly-includes-query-matched-memory
  (with-redefs [query/search-report (fn [_ query-text opts]
                                      {:schema query/search-report-schema
                                       :query-run-id "query:memory"
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :instrumentation {:search-docs 0
                                                         :returned-count 1}
                                       :results [{:path "src/query.clj"
                                                  :score 1.0
                                                  :target-kind :node
                                                  :target-id "system:query"
                                                  :label "Query"
                                                  :source-line 1}]})
                graph/system-graph (fn [_ project-id _]
                                     {:basis {:project-id project-id}
                                      :nodes [{:id "system:query"
                                               :label "Query"
                                               :kind "service"
                                               :degree 1}]
                                      :edges []
                                      :clusters []})
                query/all-chunks (fn [& _] [])
                query/chunks-by-ids (fn [& _] [])
                query/chunks-by-paths (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                dependency/package-report (fn [& _] (empty-dependency-report))
                activity/select-activity (fn [& _] [])
                memory/context-memories (fn [xtdb query-text opts]
                                          (is (= :xtdb xtdb))
                                          (is (= "memory should enter query" query-text))
                                          (is (= "fixture" (:project-id opts)))
                                          [{:id "memory:fixture:1"
                                            :kind :lesson
                                            :scope :project
                                            :visibility :project
                                            :status :reviewed
                                            :summary "Memory rides along with query packets."
                                            :targetIds ["system:query"]
                                            :score 2.5
                                            :basis [:lexical]}])
                context/query-evidence (fn [_ _ _ counts]
                                         (is (= 1 (:memory-count counts)))
                                         {:status :ready
                                          :counts {:files 1
                                                   :nodes 1
                                                   :edges 0
                                                   :memories 1}
                                          :missing []
                                          :weak []})
                coverage/context-summary (fn [& _] {})]
    (let [packet (context/context-packet :xtdb
                                         "memory should enter query"
                                         {:project-id "fixture"
                                          :retriever :lexical})]
      (is (= [{:id "memory:fixture:1"
               :kind :lesson
               :scope :project
               :visibility :project
               :status :reviewed
               :summary "Memory rides along with query packets."
               :targetIds ["system:query"]
               :score 2.5
               :basis [:lexical]}]
             (:memories packet)))
      (is (= :ready (get-in packet [:graph :readiness :status]))))))

(deftest query-evidence-counts-memory-plane-by-default
  (with-redefs [dependency/package-report (fn [& _] (empty-dependency-report))
                query/all-nodes (fn [& _] [])
                query/all-edges (fn [& _] [])
                query/all-system-evidence (fn [& _] [])
                query/all-chunks (fn [& _] [])
                query/all-search-docs (fn [& _] [])
                query/all-embeddings (fn [& _] [])
                query/all-system-nodes (fn [& _] [])
                query/all-system-edges (fn [& _] [])
                activity/all-items (fn [& _] [])
                activity/all-events (fn [& _] [])
                store/active-row-count (fn [& _] 0)
                coverage/index-run-skipped-files (fn [& _] 0)
                memory/counts (fn [_ scope]
                                (is (= {:project-id "fixture"
                                        :repo-id nil
                                        :read-context nil}
                                       scope))
                                {:memories 2
                                 :memory-statuses {:reviewed 1
                                                   :suggested 1}
                                 :suggested-memories 1
                                 :observed-memories 0
                                 :reviewed-memories 1})]
    (let [evidence (#'context/query-evidence
                    :xtdb
                    {}
                    {:project-id "fixture"
                     :retriever :lexical}
                    {:entity-count 0
                     :doc-count 0
                     :memory-count 1
                     :activity-count 0
                     :runtime-count 0
                     :validation-count 0})]
      (is (some #{:memory} (:available evidence)))
      (is (not (some #{:memory} (:weak evidence))))
      (is (= {:memories 2
              :memory-statuses {:reviewed 1
                                :suggested 1}
              :suggested-memories 1
              :observed-memories 0
              :reviewed-memories 1}
             (select-keys (:counts evidence)
                          [:memories
                           :memory-statuses
                           :suggested-memories
                           :observed-memories
                           :reviewed-memories]))))))
