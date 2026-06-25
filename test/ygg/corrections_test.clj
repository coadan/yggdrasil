(ns ygg.corrections-test
  (:require [clojure.test :refer [deftest is testing]]
            [ygg.corrections :as corrections]
            [ygg.corrections-api :as corrections-api]
            [ygg.xtdb :as store]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(deftest correction-fact-rows-use-typed-canonical-shape
  (testing "reason is mandatory at the fact boundary"
    (try
      (corrections/fact-row "demo" :accept-system {:id "system:api"})
      (is false "expected missing reason to fail")
      (catch clojure.lang.ExceptionInfo ex
        (is (= "Correction requires --reason." (ex-message ex)))
        (is (= {:action :accept-system} (ex-data ex))))))
  (let [row (corrections/fact-row
             "demo"
             :accept-system
             {:id "system:api"
              :label "API"
              :kind :service}
             {:reason "The API entrypoint owns this boundary."
              :source {:kind :index-maintenance-worker
                       :work-id "work:1"
                       :agent-id "codex"}
              :now 1790000000000})]
    (is (= "ygg.correction/v1" (:schema row)))
    (is (= :system-acceptance (:kind row)))
    (is (= :accept-system (:operation row)))
    (is (= :accepted (:status row)))
    (is (= "system:api" (:target-id row)))
    (is (= 1.0 (:confidence row)))
    (is (= {:kind :index-maintenance-worker
            :work-id "work:1"
            :agent-id "codex"}
           (:source row)))
    (is (= "correction:demo:" (subs (:xt/id row) 0 16)))))

(deftest correction-facts-persist-and-resolve-from-xtdb
  (let [path (temp-dir "ygg-corrections-xtdb")
        raw-system {:xt/id "node:generated-api"
                    :project-id "demo"
                    :repo-id "app"
                    :label "Generated API Candidate"
                    :kind "candidate"
                    :source "system-extractor"
                    :active? true}]
    (store/with-node
      path
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (:system-nodes store/tables) raw-system)])
        (corrections-api/accept-system!
         xtdb
         "demo"
         "system:api"
         {:kind "service"
          :label "API"
          :include {:repo "app"
                    :path "src/api"}
          :reason "The API entrypoint owns this boundary."})
        (corrections-api/edge-add!
         xtdb
         "demo"
         {:source "system:api"
          :target "system:db"
          :relation "uses"
          :reason "The API issues database queries."
          :evidence ["edge:e1"]
          :confidence 0.75})
        (let [facts (corrections/facts xtdb "demo")]
          (is (= [:accept-system :add-edge] (mapv :operation facts)))
          (is (every? #(= :accepted (:status %)) facts))
          (is (= {:kind :human} (:source (first facts))))
          (is (= raw-system
                 (store/row-by-id xtdb
                                  (:system-nodes store/tables)
                                  "node:generated-api"))))))
    (store/with-node
      path
      (fn [xtdb]
        (let [facts (corrections/facts xtdb "demo")
              api-facts (corrections/facts-for-targets xtdb "demo" ["system:api"])
              db-facts (corrections/facts-for-targets xtdb "demo" ["system:db"])
              overlay (corrections/overlay xtdb "demo")]
          (is (= 2 (count facts)))
          (is (= [:accept-system] (mapv :operation api-facts)))
          (is (= [:add-edge] (mapv :operation db-facts)))
          (is (= {:systems 1
                  :rejects 0
                  :edges 1
                  :docs 0
                  :packageImports 0}
                 (corrections/counts xtdb "demo")))
          (is (= {:id "system:api"
                  :label "API"
                  :kind "service"
                  :includes [{:repo "app"
                              :path "src/api"}]
                  :status "accepted"
                  :reason "The API entrypoint owns this boundary."}
                 (first (:systems overlay))))
          (is (= {:source "system:api"
                  :target "system:db"
                  :relation "uses"
                  :status "accepted"
                  :confidence 0.75
                  :rules "human"
                  :evidence ["edge:e1"]
                  :reason "The API issues database queries."}
                 (first (:edges overlay)))))))))

(deftest overlay-import-writes-facts-not-a-map-blob
  (store/with-node
    (temp-dir "ygg-corrections-import-xtdb")
    (fn [xtdb]
      (corrections/import-overlay!
       xtdb
       "demo"
       {:systems [{:id "system:web"
                   :label "Web"
                   :kind "service"
                   :reason "The route manifest identifies the web boundary."}]
        :docs [{:target "system:web"
                :source {:repo "app"
                         :path "docs/web.md"}
                :role "owner"
                :reason "This doc declares the web service owner."}]
        :packageImports [{:repo "app"
                          :import "xtdb.api"
                          :ecosystem "maven"
                          :package "com.xtdb/xtdb-api"
                          :reason "The import prefix comes from deps.edn."}]}
       {:source {:kind :import
                 :id "seed-corrections"}
        :now 1790000000000})
      (let [facts (corrections/facts xtdb "demo")]
        (is (= #{:accept-system :attach-doc :add-package-import}
               (set (map :operation facts))))
        (is (every? #(= "ygg.correction/v1" (:schema %)) facts))
        (is (every? #(= {:kind :import
                         :id "seed-corrections"}
                        (:source %))
                    facts))
        (is (every? #(not (contains? % :systems)) facts))))))
