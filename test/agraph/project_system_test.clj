(ns agraph.project-system-test
  (:require [agraph.context :as context]
            [agraph.graph :as graph]
            [agraph.infra-review :as infra-review]
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

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

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
              mapped-maintenance (project/maintain-project
                                  xtdb
                                  project
                                  {:map-overlay {:schema graph-map/schema
                                                 :project "fixture"
                                                 :systems []
                                                 :reject [{:match {:kind "external-api"
                                                                   :host "api.stripe.com"}}]
                                                 :edges []
                                                 :docs []}})
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
          (is (some #(and (= :external-api (:kind %))
                          (= "docs.example.com" (:label %)))
                    systems))
          (is (some #(and (= :external-api (:kind %))
                          (= "spacetimedb.com" (:label %)))
                    systems))
          (is (some #(and (= :external-api (:kind %))
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
          (is (seq (get-in maintenance [:graph-health :high-degree-hubs])))
          (is (contains? (:graph-health maintenance) :cross-cluster-edges))
          (is (seq (get-in maintenance [:graph-health :orphaned-candidates])))
          (is (seq (get-in maintenance [:graph-health :evidence-concentrations])))
          (is (= 1 (get-in mapped-maintenance [:map :rejected-systems])))
          (is (= (dec (get-in maintenance [:counts :systems]))
                 (get-in mapped-maintenance [:counts :systems])))
          (is (not-any? #(or (str/includes? (:source-id %) "api.stripe.com")
                             (str/includes? (:target-id %) "api.stripe.com"))
                        (:semantic-connections mapped-maintenance)))
          (is (= :small (get-in maintenance [:scale :tier])))
          (is (contains? (set (map :kind (get-in maintenance [:fold-in :actions])))
                         :review-primary-graph))
          (is (every? :basis (:decision-queue maintenance)))
          (is (every? #(= :open (:status %)) (:decision-queue maintenance)))
          (is (<= (count (:edges graph-data)) (count (:edges raw-graph-data)))))))))

(deftest container-image-artifacts-connect-producers-to-deployment-manifests
  (let [xtdb-path (temp-dir "agraph-image-xtdb")
        app-root (temp-dir "agraph-image-app")
        env-root (temp-dir "agraph-image-env")
        project {:id "images"
                 :name "Images"
                 :repos [{:id "app" :root app-root :role :application}
                         {:id "env" :root env-root :role :infrastructure}]}]
    (spit-file! app-root
                "bases/flows-api/Dockerfile"
                "FROM eclipse-temurin:25\nCOPY target/base.jar /app/base.jar\n")
    (doseq [environment ["dev" "prod"]]
      (spit-file! env-root
                  (format "bases/flows-api/kube/%s/kustomization.yaml" environment)
                  (str "images:\n"
                       "- name: image_placeholder\n"
                       "  newName: europe-north1-docker.pkg.dev/demo/builds/flows-api\n"
                       "  newTag: abc123\n")))
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [system-edges (store/rows-by-field xtdb (:system-edges store/tables) :project-id "images")
              deploy-edge (some #(when (= :deploys (:relation %)) %) system-edges)
              graph-data (graph/system-graph xtdb "images" {})
              graph-deploy-edge (some #(when (= "deploys" (:relation %)) %) (:edges graph-data))]
          (is (some? deploy-edge))
          (is (str/includes? (:source-id deploy-edge) "app:path/bases/flows-api"))
          (is (str/includes? (:target-id deploy-edge) "env:path/bases/flows-api"))
          (is (= 3 (count (:evidence-ids deploy-edge))))
          (is (some? graph-deploy-edge))
          (is (= "primary" (:visibility graph-deploy-edge))))))))

(deftest unmatched-container-image-facts-create-infra-review-work
  (let [xtdb-path (temp-dir "agraph-image-review-xtdb")
        app-root (temp-dir "agraph-image-review-app")
        project {:id "image-review"
                 :name "Image Review"
                 :repos [{:id "app" :root app-root :role :application}]}]
    (spit-file! app-root
                "bases/worker/Dockerfile"
                "FROM eclipse-temurin:25\nCOPY target/worker.jar /app/worker.jar\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [maintenance (project/maintain-project xtdb project {})
              packet (first (:infra-review-queue maintenance))]
          (is (= 1 (get-in maintenance [:counts :infra-review-items])))
          (is (= infra-review/packet-schema (:schema packet)))
          (is (= infra-review/result-schema (:expectedResultSchema packet)))
          (is (= "container-image-producer-without-consumer" (:kind packet)))
          (is (= "container-image:worker" (:artifact packet)))
          (is (= ["add-edge" "set-edge-visibility" "reject-edge" "none"]
                 (:allowedActions packet)))
          (is (= ["container-image-producer"]
                 (mapv :kind (get-in packet [:facts :evidence]))))
          (is (seq (get-in packet [:facts :systems])))
          (is (str/includes? (get-in packet [:messages 1 :content])
                             "Return this JSON shape")))))))

(deftest shell-image-tags-connect-build-and-runtime-scripts
  (let [xtdb-path (temp-dir "agraph-shell-image-xtdb")
        repo-root (temp-dir "agraph-shell-image-repo")
        project {:id "shell-images"
                 :name "Shell Images"
                 :repos [{:id "app" :root repo-root :role :application}]}]
    (spit-file! repo-root
                "build/runtime/build-image.sh"
                (str "#!/usr/bin/env bash\n"
                     "IMAGE=\"demo-runtime:local\"\n"
                     "docker build -t \"${IMAGE}\" .\n"))
    (spit-file! repo-root
                "runtime/worker/run.sh"
                (str "#!/usr/bin/env bash\n"
                     ": \"${DEMO_RUNTIME_IMAGE:=demo-runtime:local}\"\n"
                     "docker run \"${DEMO_RUNTIME_IMAGE}\"\n"))
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [system-edges (store/rows-by-field xtdb
                                                (:system-edges store/tables)
                                                :project-id
                                                "shell-images")
              deploy-edge (some #(when (= :deploys (:relation %)) %) system-edges)
              evidence (store/rows-by-field xtdb
                                            (:system-evidence store/tables)
                                            :project-id
                                            "shell-images")]
          (is (some? deploy-edge))
          (is (str/includes? (:source-id deploy-edge) "app:path/build/runtime"))
          (is (str/includes? (:target-id deploy-edge) "app:path/runtime/worker"))
          (is (= #{:container-image-producer :container-image-consumer}
                 (set (map :kind evidence)))))))))

(deftest system-inference-uses-indexed-file-facts
  (let [xtdb-path (temp-dir "agraph-indexed-facts-xtdb")
        repo-root (temp-dir "agraph-indexed-facts-repo")
        project {:id "indexed-content"
                 :name "Indexed Content"
                 :repos [{:id "app" :root repo-root :role :application}]}]
    (spit-file! repo-root
                "build/runtime/build-image.sh"
                (str "#!/usr/bin/env bash\n"
                     "IMAGE=\"indexed-runtime:local\"\n"
                     "docker build -t \"${IMAGE}\" .\n"))
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (spit-file! repo-root
                    "build/runtime/build-image.sh"
                    (str "#!/usr/bin/env bash\n"
                         "IMAGE=\"live-runtime:local\"\n"
                         "docker build -t \"${IMAGE}\" .\n"))
        (project/infer-project! xtdb project)
        (let [evidence (store/rows-by-field xtdb
                                            (:system-evidence store/tables)
                                            :project-id
                                            "indexed-content")
              values (set (map :normalized-value evidence))]
          (is (contains? values "container-image:indexed-runtime"))
          (is (not (contains? values "container-image:live-runtime"))))))))

(deftest maintenance-graph-health-reports-cross-cluster-bridges
  (store/with-node (temp-dir "agraph-health-xtdb")
    (fn [xtdb]
      (let [nodes [{:xt/id "system:health:app:a"
                    :project-id "health"
                    :repo-id "app"
                    :system-key "a"
                    :label "A"
                    :kind :candidate-system
                    :aliases []
                    :active? true
                    :run-id "run/health"}
                   {:xt/id "system:health:app:b"
                    :project-id "health"
                    :repo-id "app"
                    :system-key "b"
                    :label "B"
                    :kind :candidate-system
                    :aliases []
                    :active? true
                    :run-id "run/health"}
                   {:xt/id "system:health:app:c"
                    :project-id "health"
                    :repo-id "app"
                    :system-key "c"
                    :label "C"
                    :kind :candidate-system
                    :aliases []
                    :active? true
                    :run-id "run/health"}]
            edge (fn [id source target relation]
                   {:xt/id id
                    :project-id "health"
                    :source-id source
                    :target-id target
                    :relation relation
                    :confidence 1.0
                    :evidence-ids []
                    :rules []
                    :active? true
                    :run-id "run/health"})
            edges [(edge "edge:a-b-code"
                         "system:health:app:a"
                         "system:health:app:b"
                         :code-depends-on)
                   (edge "edge:a-b-http"
                         "system:health:app:a"
                         "system:health:app:b"
                         :calls-http)
                   (edge "edge:b-c"
                         "system:health:app:b"
                         "system:health:app:c"
                         :calls-external-api)]]
        (store/execute-tx!
         xtdb
         (vec (concat
               (map #(store/put-op (store/table-ref :system-nodes) %) nodes)
               (map #(store/put-op (store/table-ref :system-edges) %) edges))))
        (let [report (system/maintenance-report xtdb "health" {})
              bridge (first (get-in report [:graph-health :cross-cluster-edges]))]
          (is (seq (get-in report [:graph-health :high-degree-hubs])))
          (is (= :calls-external-api (:relation bridge)))
          (is (= "B" (get-in bridge [:source :label])))
          (is (= "C" (get-in bridge [:target :label])))
          (is (some? (:source-cluster bridge)))
          (is (some? (:target-cluster bridge))))))))

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
          (is (= :ready (get-in packet [:answerability :status])))
          (is (contains? (set (get-in packet [:answerability :available])) :docs))
          (is (contains? (set (get-in packet [:answerability :available])) :system-graph))
          (is (contains? (set (get-in packet [:answerability :missing])) :embeddings))
          (is (contains? (set (get-in packet [:answerability :missing])) :activity))
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

(deftest context-packet-reports-answerability-for-empty-project
  (store/with-node (temp-dir "agraph-empty-answerability-xtdb")
    (fn [xtdb]
      (let [packet (context/context-packet xtdb
                                           "prior work around auth"
                                           {:project-id "fixture"
                                            :retriever :lexical
                                            :budget 2000})
            answerability (:answerability packet)]
        (is (= context/schema (:schema packet)))
        (is (= :empty (:status answerability)))
        (is (contains? (set (:missing answerability)) :source-graph))
        (is (contains? (set (:missing answerability)) :docs))
        (is (contains? (set (:missing answerability)) :system-graph))
        (is (contains? (set (:missing answerability)) :embeddings))
        (is (contains? (set (:missing answerability)) :activity))
        (is (= {:requested :lexical
                :effective :lexical
                :fallback? false}
               (:retrieval answerability)))
        (is (some #(str/includes? % "No search docs")
                  (:warnings answerability)))
        (is (some #(str/includes? % "activity/work")
                  (:warnings answerability)))))))

(deftest context-packet-is-limited-when-system-graph-is-missing
  (let [xtdb-path (temp-dir "agraph-missing-system-answerability-xtdb")
        repo (.getPath (io/file "test/fixtures/project-repo"))
        project {:id "fixture"
                 :name "Fixture"
                 :repos [{:id "app" :root repo :role :application}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (let [packet (context/context-packet xtdb
                                             "fixture api runtime"
                                             {:project-id "fixture"
                                              :retriever :lexical
                                              :budget 2000})
              answerability (:answerability packet)]
          (is (seq (:docs packet)))
          (is (empty? (:entities packet)))
          (is (= :limited (:status answerability)))
          (is (contains? (set (:available answerability)) :docs))
          (is (contains? (set (:missing answerability)) :system-graph))
          (is (some #(str/includes? % "No system graph rows")
                    (:warnings answerability))))))))

(deftest context-packet-reports-graph-profile-and-auto-fallback
  (let [xtdb-path (temp-dir "agraph-graph-profile-answerability-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))
        project {:id "fixture"
                 :name "Fixture"
                 :repos [{:id "app" :root repo :role :application}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {:index-profile :graph})
        (let [packet (context/context-packet xtdb
                                             "greeting"
                                             {:project-id "fixture"
                                              :retriever :auto
                                              :budget 2000})
              answerability (:answerability packet)]
          (is (= :empty (:status answerability)))
          (is (contains? (set (:available answerability)) :source-graph))
          (is (contains? (set (:missing answerability)) :docs))
          (is (pos? (get-in answerability [:counts :files])))
          (is (pos? (get-in answerability [:counts :nodes])))
          (is (zero? (get-in answerability [:counts :search-docs])))
          (is (= {:requested :auto
                  :effective :lexical
                  :fallback? true
                  :reason "No embedding client was available."}
                 (:retrieval answerability)))
          (is (some #(str/includes? % "--query-index")
                    (:next answerability))))))))

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
