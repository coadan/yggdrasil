(ns agraph.project-system-test
  (:require [agraph.context :as context]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.system :as system]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest path-system-returns-neutral-structural-candidates
  (is (= {:system-key "path/bases/flows-api"
          :label "bases/flows-api"
          :kind :candidate-system
          :path-prefix "bases/flows-api"
          :source :deterministic
          :candidate-types [:path-cluster]}
         (select-keys (system/path-system {:id "repo" :role :application}
                                          "bases/flows-api/src/app/core.clj")
                      [:system-key :label :kind :path-prefix :source :candidate-types])))
  (is (= {:system-key "path/libraries/flows"
          :label "libraries/flows"
          :kind :candidate-system
          :path-prefix "libraries/flows"
          :source :deterministic
          :candidate-types [:path-cluster]}
         (select-keys (system/path-system {:id "repo" :role :application}
                                          "libraries/flows/src/lib/core.clj")
                      [:system-key :label :kind :path-prefix :source :candidate-types])))
  (is (= {:system-key "path/components/catalog"
          :label "components/catalog"
          :kind :candidate-system
          :path-prefix "components/catalog"
          :source :deterministic
          :candidate-types [:path-cluster]}
         (select-keys (system/path-system {:id "repo" :role :application}
                                          "components/catalog/src/catalog/core.clj")
                      [:system-key :label :kind :path-prefix :source :candidate-types])))
  (is (= {:system-key "path/scripts/flows"
          :label "scripts/flows"
          :kind :candidate-system
          :path-prefix "scripts/flows"
          :source :deterministic
          :candidate-types [:path-cluster]}
         (select-keys (system/path-system {:id "repo" :role :application}
                                          "scripts/flows/reindex.clj")
                      [:system-key :label :kind :path-prefix :source :candidate-types]))))

(deftest indexes-project-and-infers-system-graph
  (let [xtdb-path (temp-dir "agraph-project-xtdb")
        repo (.getPath (io/file "test/fixtures/project-repo"))
        project {:id "fixture"
                 :name "Fixture"
                 :repos [{:id "app" :root repo :role :application}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [index-summary (project/index-project! xtdb project {})
              system-summary (project/infer-project! xtdb project)
              systems (store/rows-by-field xtdb (:system-nodes store/tables) :project-id "fixture")
              system-edges (store/rows-by-field xtdb (:system-edges store/tables) :project-id "fixture")
              maintenance (project/maintain-project xtdb project {})
              search (query/semantic-query xtdb
                                           "services api depends on core"
                                           {:project-id "fixture"
                                            :retriever :lexical
                                            :limit 10})
              system-path (query/system-path xtdb
                                             "services/api"
                                             "libs/core"
                                             {:project-id "fixture"})
              graph-data (graph/system-graph xtdb "fixture" {:detail :expanded})
              raw-graph-data (graph/system-graph xtdb "fixture" {:detail :raw})]
          (is (= :completed (:status index-summary)))
          (is (= 1 (count (:repos index-summary))))
          (is (= :completed (:status system-summary)))
          (is (some #(and (= "libs/core" (:label %))
                          (= :candidate-system (:kind %))
                          (some #{:path-cluster} (:candidate-types %)))
                    systems))
          (is (some #(and (= "services/api" (:label %))
                          (= :candidate-system (:kind %))
                          (some #{:manifest-root} (:candidate-types %)))
                    systems))
          (is (some #(and (= "integrations/payments" (:label %))
                          (= :candidate-system (:kind %))
                          (some #{:manifest-root} (:candidate-types %)))
                    systems))
          (is (not-any? #(contains? #{:service :library :tool :integration} (:kind %))
                        systems))
          (is (some #(and (= :external-api (:kind %))
                          (= "api.stripe.com" (:label %)))
                    systems))
          (is (not-any? #(and (= :external-api (:kind %))
                              (#{"docs.example.com" "spacetimedb.com"} (:label %)))
                        systems))
          (is (not-any? #(and (= :external-api (:kind %))
                              (= "api.example.com" (:label %)))
                        systems))
          (is (some #(= :code-depends-on (:relation %)) system-edges))
          (is (some #(= :calls-external-api (:relation %)) system-edges))
          (is (pos? (get-in maintenance [:counts :orphaned-systems])))
          (is (some #(= :system-node (:result-kind %)) search))
          (is (= ["services/api" "libs/core"] (mapv :label system-path)))
          (is (seq (:nodes graph-data)))
          (is (seq (:edges graph-data)))
          (is (every? :visibility (:edges graph-data)))
          (is (every? :salience (:edges graph-data)))
          (is (seq (:clusters graph-data)))
          (is (some :clusterId (:nodes graph-data)))
          (is (= "agraph.graph-basis/v1" (get-in maintenance [:graph-basis :schema])))
          (is (string? (get-in maintenance [:graph-basis :hash])))
          (is (= :small (get-in maintenance [:scale :tier])))
          (is (contains? (set (map :kind (get-in maintenance [:fold-in :actions])))
                         :review-primary-graph))
          (is (every? :basis (:decision-queue maintenance)))
          (is (every? #(= :open (:status %)) (:decision-queue maintenance)))
          (is (<= (count (:edges graph-data)) (count (:edges raw-graph-data)))))))))

(deftest graph-map-overlay-corrects-system-graph
  (let [xtdb-path (temp-dir "agraph-map-xtdb")
        repo (.getPath (io/file "test/fixtures/project-repo"))
        map-path (.getPath (io/file (temp-dir "agraph-map") "agraph.map.json"))
        project {:id "fixture"
                 :name "Fixture"
                 :repos [{:id "app" :root repo :role :application}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (graph-map/write-map!
         map-path
         {:schema graph-map/schema
          :project "fixture"
          :systems [{:id "system:fixture:accepted-api"
                     :label "Fixture API"
                     :kind "service"
                     :includes [{:repo "app" :path "services/api"}]
                     :status "accepted"
                     :reason "Agent verified this path is the API service."}]
          :reject [{:match {:kind "external-api"
                            :host "api.stripe.com"}
                    :reason "Example payment URL in fixture code."}]
          :edges []})
        (let [graph-data (graph/system-graph xtdb "fixture" {:map-path map-path})
              labels (set (map :label (:nodes graph-data)))
              api-node (some #(when (= "Fixture API" (:label %)) %) (:nodes graph-data))]
          (is (= "service" (:kind api-node)))
          (is (contains? labels "Fixture API"))
          (is (not (contains? labels "services/api")))
          (is (not (contains? labels "api.stripe.com"))))))))

(deftest context-packet-uses-attached-doc-snippets-with-budget
  (let [xtdb-path (temp-dir "agraph-context-xtdb")
        repo (.getPath (io/file "test/fixtures/project-repo"))
        map-path (.getPath (io/file (temp-dir "agraph-context-map") "agraph.map.json"))
        accepted-id "system:fixture:accepted-api"
        project {:id "fixture"
                 :name "Fixture"
                 :repos [{:id "app" :root repo :role :application}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [map-data (graph-map/add-doc
                        {:schema graph-map/schema
                         :project "fixture"
                         :systems [{:id accepted-id
                                    :label "Fixture API"
                                    :kind "service"
                                    :includes [{:repo "app" :path "services/api"}]
                                    :status "accepted"}]
                         :reject []
                         :edges []
                         :docs []}
                        "Fixture API"
                        {:repo "app" :path "docs/api.md"}
                        {:role "runbook" :heading "Fixture API"})]
          (is (= accepted-id (get-in map-data [:docs 0 :target])))
          (graph-map/write-map! map-path map-data))
        (let [packet (context/context-packet xtdb
                                             "fixture api runtime"
                                             {:project-id "fixture"
                                              :map-path map-path
                                              :retriever :lexical
                                              :budget 1200
                                              :doc-limit 4
                                              :snippet-chars 220})
              labels (set (map :label (:entities packet)))
              doc (first (:docs packet))]
          (is (= context/schema (:schema packet)))
          (is (= "primary" (get-in packet [:graph :defaultDetail])))
          (is (pos? (get-in packet [:graph :counts :nodes])))
          (is (<= (context/estimate-tokens packet) 1200))
          (is (contains? labels "Fixture API"))
          (is (= "runbook" (:role doc)))
          (is (= "accepted" (:status doc)))
          (is (= "docs/api.md" (get-in doc [:source :path])))
          (is (= 1
                 (count (filter #(= ["app" "docs/api.md" [1 5]]
                                    [(get-in % [:source :repo])
                                     (get-in % [:source :path])
                                     (get-in % [:source :lines])])
                                (:docs packet)))))
          (is (str/includes? (:snippet doc) "Fixture API service")))))))

(deftest reads-workbench-project-config
  (let [root (io/file (temp-dir "agraph-workbench"))
        repos-json (io/file root "repos.json")
        cache-root (io/file root ".workbench" "repos" "demo-cli")
        project-edn (io/file root "project.edn")]
    (.mkdirs cache-root)
    (spit repos-json "{\"repos\":{\"demo-cli\":{\"url\":\"https://example.invalid/demo-cli.git\"}}}")
    (spit project-edn "{:id \"workbench\" :workbench-root \".\"}")
    (let [project (project/read-project (.getPath project-edn))]
      (is (= "workbench" (:id project)))
      (is (= [{:id "demo-cli"
               :root (.getCanonicalPath cache-root)
               :role :tooling}]
             (:repos project))))))

(deftest reads-project-config-with-absolute-repo-root
  (let [root (io/file (temp-dir "agraph-absolute-root"))
        repo-root (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo-root)
    (spit project-edn
          (pr-str {:id "absolute"
                   :repos [{:id "repo"
                            :root (.getPath repo-root)
                            :role :application}]}))
    (let [project (project/read-project (.getPath project-edn))]
      (is (= [{:id "repo"
               :root (.getCanonicalPath repo-root)
               :role :application}]
             (:repos project))))))

(deftest adds-repo-to-project-config
  (let [root (io/file (temp-dir "agraph-add-repo"))
        repo-a (io/file root "repo-a")
        repo-b (io/file root "repo-b-cli")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo-a)
    (.mkdirs repo-b)
    (spit project-edn
          (pr-str {:id "multi"
                   :repos [{:id "repo-a"
                            :root (.getPath repo-a)
                            :role :application}]}))
    (let [project (project/add-repo-to-config! (.getPath project-edn)
                                               (.getPath repo-b)
                                               {})]
      (is (= ["repo-a" "repo-b-cli"] (mapv :id (:repos project))))
      (is (= :tooling (-> project :repos second :role)))
      (is (= (.getCanonicalPath repo-b) (-> project :repos second :root)))
      (is (= (:repos project)
             (:repos (project/read-project (.getPath project-edn))))))))
