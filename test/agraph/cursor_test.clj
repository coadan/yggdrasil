(ns agraph.cursor-test
  (:require [agraph.cursor :as cursor]
            [agraph.map :as graph-map]
            [agraph.map-store :as map-store]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- system-node
  [id label kind path-prefix]
  {:xt/id id
   :project-id "test"
   :repo-id "repo"
   :system-key label
   :label label
   :kind kind
   :path-prefix path-prefix
   :aliases []
   :active? true
   :run-id "run:system"})

(defn- system-edge
  [id source target relation]
  {:xt/id id
   :project-id "test"
   :source-id source
   :target-id target
   :relation relation
   :confidence 0.95
   :evidence-ids []
   :rules ["test"]
   :active? true
   :run-id "run:system"})

(defn- search-doc
  [id target label text]
  {:xt/id id
   :project-id "test"
   :repo-id "repo"
   :target-id target
   :target-kind :system-node
   :file-id "file:repo:systems"
   :path "systems"
   :kind :system-node
   :label label
   :text text
   :tokens (str/split (str/lower-case text) #"\s+")
   :input-sha id
   :active? true
   :run-id "run:system"})

(defn- doc-chunk
  []
  {:xt/id "chunk:test:repo:docs/api"
   :project-id "test"
   :repo-id "repo"
   :file-id "file:test:repo:docs/api.md"
   :path "docs/api.md"
   :kind :markdown
   :label "API Runbook"
   :text "API Service accepts HTTP traffic and calls the Worker Service."
   :tokens ["api" "service" "http" "worker"]
   :heading-path ["API Runbook"]
   :content-sha "sha:docs"
   :source-line 1
   :end-line 3
   :active? true
   :run-id "run:docs"})

(defn- seed-system-graph!
  [xtdb]
  (let [api-id "system:test:api"
        worker-id "system:test:worker"]
    (store/commit-system-graph!
     xtdb
     "test"
     {:nodes [(system-node api-id "API Service" :service "services/api")
              (system-node worker-id "Worker Service" :service "services/worker")]
      :edges [(system-edge "system-edge:test:api-worker"
                           api-id
                           worker-id
                           :calls-http)]
      :evidence []
      :search-docs [(search-doc "search:test:api"
                                api-id
                                "API Service"
                                "api service http runtime")
                    (search-doc "search:test:worker"
                                worker-id
                                "Worker Service"
                                "worker service async jobs")]})
    (store/execute-tx!
     xtdb
     [(store/put-op (store/table-ref :chunks) (doc-chunk))])
    {:api-id api-id
     :worker-id worker-id}))

(deftest cursor-create-open-and-expand-are-revisioned
  (store/with-node (temp-dir "agraph-cursor-xtdb")
    (fn [xtdb]
      (let [{:keys [api-id worker-id]} (seed-system-graph! xtdb)
            created (cursor/create! xtdb
                                    {:project-id "test"
                                     :query-text "api runtime"
                                     :retriever :lexical
                                     :budget 2000})
            cursor-id (get-in created [:cursor :id])]
        (is (= cursor/packet-schema (:schema created)))
        (is (= 0 (get-in created [:cursor :revision])))
        (is (= [api-id] (mapv :id (:focus created))))
        (is (some #(= {:kind :expand
                       :label "Expand adjacent systems"
                       :target api-id
                       :command (str "agraph explore expand " cursor-id " " api-id)
                       :reason "Follow adjacent system graph edges"}
                      %)
                  (:nextActions created)))
        (is (some #(= {:command (str "agraph explore search " cursor-id " <text>")
                       :reason "Search within this cursor basis"}
                      %)
                  (:next created)))
        (testing "open creates a child revision and includes docs candidates"
          (let [opened (cursor/open! xtdb cursor-id "API Service" {:budget 2000})
                opened-id (get-in opened [:cursor :id])]
            (is (= cursor-id (get-in opened [:cursor :parentId])))
            (is (= 1 (get-in opened [:cursor :revision])))
            (is (= [api-id] (mapv :id (:focus opened))))
            (is (seq (:docs opened)))
            (testing "expand creates the next child with neighboring systems"
              (let [expanded (cursor/expand! xtdb opened-id api-id {:budget 2000})]
                (is (= opened-id (get-in expanded [:cursor :parentId])))
                (is (= 2 (get-in expanded [:cursor :revision])))
                (is (some #(= worker-id (:id %)) (:frontier expanded)))))))
        (is (= 3 (count (store/graph-cursors xtdb {:project-id "test"}))))))))

(deftest cursor-docs-use-stored-map-overlay
  (let [map-path (.getPath (io/file (temp-dir "agraph-cursor-map") "agraph.map.json"))]
    (store/with-node (temp-dir "agraph-cursor-map-xtdb")
      (fn [xtdb]
        (seed-system-graph! xtdb)
        (map-store/write-map!
         map-path
         (graph-map/add-doc
          {:schema graph-map/schema
           :project "test"
           :systems [{:id "system:test:accepted-api"
                      :label "Accepted API"
                      :kind "service"
                      :includes [{:repo "repo" :path "services/api"}]
                      :status "accepted"}]
           :reject []
           :edges []
           :docs []}
          "Accepted API"
          {:repo "repo" :path "docs/api.md"}
          {:role "runbook"
           :heading "API Runbook"}))
        (let [created (cursor/create! xtdb
                                      {:project-id "test"
                                       :map-path map-path
                                       :budget 2000
                                       :node-limit 1})
              cursor-id (get-in created [:cursor :id])]
          (is (= ["Accepted API"] (mapv :label (:focus created))))
          (map-store/write-map!
           map-path
           {:schema graph-map/schema
            :project "test"
            :systems []
            :reject []
            :edges []
            :docs []})
          (let [shown (cursor/show xtdb cursor-id {:budget 2000})
                docs (cursor/docs! xtdb
                                   cursor-id
                                   "Accepted API"
                                   {:budget 2000})]
            (is (= ["Accepted API"] (mapv :label (:focus shown))))
            (is (= "runbook" (:role (first (:docs docs)))))
            (is (str/includes? (:snippet (first (:docs docs)))
                               "API Service accepts HTTP traffic"))))))))

(deftest invalid-target-does-not-create-revision
  (store/with-node (temp-dir "agraph-cursor-invalid-xtdb")
    (fn [xtdb]
      (seed-system-graph! xtdb)
      (let [created (cursor/create! xtdb
                                    {:project-id "test"
                                     :budget 2000})
            cursor-id (get-in created [:cursor :id])
            before (count (store/graph-cursors xtdb {:project-id "test"}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Cursor target not found"
                              (cursor/open! xtdb
                                            cursor-id
                                            "missing"
                                            {:budget 2000})))
        (is (= before
               (count (store/graph-cursors xtdb {:project-id "test"}))))))))
