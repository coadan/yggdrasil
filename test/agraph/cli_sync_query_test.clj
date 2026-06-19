(ns agraph.cli-sync-query-test
  (:require [agraph.activity :as activity]
            [agraph.audit-scope :as audit-scope]
            [agraph.benchmark :as benchmark]
            [agraph.cli :as cli]
            [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.cursor :as cursor]
            [agraph.dependency-review :as dependency-review]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.infra-review :as infra-review]
            [agraph.init :as init]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.query :as query]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.report :as report]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- read-json-output
  [s]
  (json/read-json s :key-fn keyword))

(def project-fixture
  {:id "fixture"
   :name "Fixture"
   :path "project.edn"
   :repos [{:id "app"
            :root "/tmp/app"
            :role :application}]})

(deftest init-command-can-run-sync-and-create-explicit-map
  (let [root (temp-dir "agraph-cli-init")
        config-path (.getPath (io/file root "project.edn"))
        map-path (.getPath (io/file root "agraph.map.json"))
        calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         (assoc project-fixture :path path))
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [xtdb project]
                                           (swap! calls conj [:infer xtdb (:id project)])
                                           {:project-id (:id project)
                                            :status :completed
                                            :system-evidence 0
                                            :system-nodes 0
                                            :system-edges 0})
                  project/maintain-project (fn [xtdb project opts]
                                             (swap! calls conj [:check xtdb (:id project) opts])
                                             {:project-id (:id project)
                                              :counts {:maintenance-decisions 0}
                                              :decision-queue []})]
      (let [out (with-out-str
                  (cli/dispatch "init" [root
                                        "--project" "fixture"
                                        "--out" config-path
                                        "--sync"
                                        "--map" map-path]))
            parsed (read-json-output out)]
        (is (= init/schema (:schema parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (str/includes? (:sync-output parsed) "# Sync"))
        (is (graph-map/file-exists? map-path))
        (is (= [[:read config-path]
                [:index :xtdb "fixture" {:dry-run? false
                                         :index-profile :graph
                                         :map-overlay {:schema "agraph.map/v1"
                                                       :project "fixture"
                                                       :systems []
                                                       :reject []
                                                       :edges []
                                                       :docs []}}]
                [:infer :xtdb "fixture"]
                [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                         :map-overlay {:schema "agraph.map/v1"
                                                       :project "fixture"
                                                       :systems []
                                                       :reject []
                                                       :edges []
                                                       :docs []}}]]
               (mapv (fn [call]
                       (if (contains? #{:index :check} (first call))
                         (update-in call [3 :map-overlay] #(select-keys %
                                                                        [:schema
                                                                         :project
                                                                         :systems
                                                                         :reject
                                                                         :edges
                                                                         :docs]))
                         call))
                     @calls))))
      (is (= {:schema "agraph.map/v1"
              :project "fixture"
              :systems []
              :reject []
              :edges []
              :docs []}
             (-> @calls
                 last
                 (get-in [3 :map-overlay])
                 (select-keys [:schema :project :systems :reject :edges :docs])))))))
(deftest start-command-initializes-syncs-activity-and-report
  (let [root (temp-dir "agraph-cli-start")
        config-path (.getPath (io/file root "project.edn"))
        map-path (.getPath (io/file root "agraph.map.json"))
        report-out (.getPath (io/file root "agraph-out"))
        calls (atom [])]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [xtdb project]
                                           (swap! calls conj [:infer xtdb (:id project)])
                                           {:project-id (:id project)
                                            :status :completed
                                            :system-evidence 0
                                            :system-nodes 0
                                            :system-edges 0})
                  project/maintain-project (fn [xtdb project opts]
                                             (swap! calls conj [:check xtdb (:id project) opts])
                                             {:project-id (:id project)
                                              :counts {:maintenance-decisions 0}
                                              :decision-queue []})
                  activity/sync-queue! (fn [xtdb project opts]
                                         (swap! calls conj [:activity xtdb (:id project) opts])
                                         {:schema activity/sync-schema
                                          :project-id (:id project)
                                          :queue-root (:queue-root opts)
                                          :counts {:items 0
                                                   :events 0
                                                   :validation-events 0
                                                   :result-schema-mismatch-events 0}})
                  report/bundle! (fn [xtdb project opts]
                                   (swap! calls conj [:report xtdb (:id project) opts])
                                   {:schema report/schema
                                    :project-id (:id project)
                                    :out (:out opts)
                                    :evidence {:schema evidence/schema
                                               :available [:source-graph]
                                               :counts {:files 1}}
                                    :files {:index "index.html"}})]
      (let [out (with-out-str
                  (cli/dispatch "start" [root
                                         "--project" "fixture"
                                         "--out" config-path
                                         "--map" map-path
                                         "--report-out" report-out]))
            parsed (read-json-output out)]
        (is (= "agraph.start/v1" (:schema parsed)))
        (is (= "initialized" (:mode parsed)))
        (is (= true (:initialized parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= config-path (:config parsed)))
        (is (= map-path (:map parsed)))
        (is (not (contains? parsed :sync)))
        (is (not (contains? parsed :activity)))
        (is (not (contains? parsed :check-report)))
        (is (not (contains? parsed :semantic-connections)))
        (is (= report-out (get-in parsed [:report :out])))
        (is (= "agraph.evidence/v2" (get-in parsed [:evidence :schema])))
        (is (= ["source-graph"] (get-in parsed [:evidence :available])))
        (is (= {:status "ready"
                :basis "start-run"
                :summary "Ready for ask/explore with the graph produced by this start run."
                :readyFor ["ask" "explore" "systems" "report"]
                :checks {:graph-sync true
                         :system-inference true
                         :report-written true
                         :evidence-summary true}
                :agentGuidance {:status "available"
                                :installed false
                                :command "agraph agent install --platform codex --project"}}
               (:readiness parsed)))
        (is (= {:scanned 0
                :indexed 0
                :skipped 0
                :deleted 0
                :diagnostics 0}
               (get-in parsed [:counts :files])))
        (is (= {:nodes 0
                :edges 0
                :file-facts 0
                :chunks 0
                :search-docs 0}
               (get-in parsed [:counts :graph])))
        (is (= {:nodes 0
                :edges 0
                :evidence 0
                :maintenance-decisions 0
                :orphaned-candidates 0}
               (get-in parsed [:counts :systems])))
        (is (= {:items 0
                :events 0
                :validation-events 0
                :result-schema-mismatch-events 0}
               (get-in parsed [:counts :activity])))
        (is (graph-map/file-exists? map-path))
        (is (some #(str/includes? % "agraph ask")
                  (:next parsed)))
        (is (some #(= {:kind "ask"
                       :label "Ask a graph-grounded implementation question"
                       :command "agraph ask \"where is this handled?\" --project fixture --json"}
                      %)
                  (:nextActions parsed)))
        (is (= (set (:next parsed))
               (set (map :command (:nextActions parsed)))))
        (is (= [[:index :xtdb "fixture" {:dry-run? false
                                         :index-profile :graph
                                         :map-overlay {:schema "agraph.map/v1"
                                                       :project "fixture"
                                                       :systems []
                                                       :reject []
                                                       :edges []
                                                       :docs []}}]
                [:infer :xtdb "fixture"]
                [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                         :map-overlay {:schema "agraph.map/v1"
                                                       :project "fixture"
                                                       :systems []
                                                       :reject []
                                                       :edges []
                                                       :docs []}}]
                [:activity :xtdb "fixture" {:queue-root queue/default-root}]
                [:report :xtdb "fixture" {:out report-out
                                          :map-path map-path
                                          :detail :primary
                                          :force? false}]]
               (mapv (fn [call]
                       (if (contains? #{:index :check} (first call))
                         (update-in call [3 :map-overlay] #(select-keys %
                                                                        [:schema
                                                                         :project
                                                                         :systems
                                                                         :reject
                                                                         :edges
                                                                         :docs]))
                         call))
                     @calls)))))))
(deftest start-command-reuses-existing-project-config
  (let [root (temp-dir "agraph-cli-start-existing")
        config-path (.getPath (io/file root "project.edn"))
        map-path (.getPath (io/file root "agraph.map.json"))
        report-out (.getPath (io/file root "agraph-out"))
        calls (atom [])]
    (init/init! root {:out config-path
                      :project-id "existing"})
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (constantly {:status :completed})
                  project/maintain-project (constantly {:counts {}
                                                        :decision-queue []})
                  activity/sync-queue! (fn [_ project _]
                                         {:schema activity/sync-schema
                                          :project-id (:id project)
                                          :counts {}})
                  report/bundle! (fn [_ project _]
                                   {:schema report/schema
                                    :project-id (:id project)})]
      (let [out (with-out-str
                  (cli/dispatch "start" [root
                                         "--out" config-path
                                         "--map" map-path
                                         "--report-out" report-out]))
            parsed (read-json-output out)]
        (is (= "existing" (:mode parsed)))
        (is (= "existing" (:project-id parsed)))
        (is (not (contains? parsed :init)))
        (is (not (contains? parsed :initialized)))
        (is (not (contains? parsed :sync)))
        (is (some #(= (str "agraph report " config-path
                           " --map " map-path
                           " --out " report-out)
                      %)
                  (:next parsed)))
        (is (= [[:index :xtdb "existing" {:dry-run? false
                                          :index-profile :graph
                                          :map-overlay {:schema "agraph.map/v1"
                                                        :project "existing"
                                                        :systems []
                                                        :reject []
                                                        :edges []
                                                        :docs []}}]]
               (mapv (fn [call]
                       (if (= :index (first call))
                         (update-in call [3 :map-overlay] #(select-keys %
                                                                        [:schema
                                                                         :project
                                                                         :systems
                                                                         :reject
                                                                         :edges
                                                                         :docs]))
                         call))
                     @calls)))))))
(deftest start-next-actions-quote-shell-sensitive-values
  (let [actions (#'cli/start-next-actions
                 "fixture project"
                 "Project Files/project.edn"
                 "Maps/agraph map.json"
                 "Report Output")
        commands (set (map :command actions))]
    (is (contains? commands
                   "agraph ask \"where is this handled?\" --project 'fixture project' --json"))
    (is (contains? commands
                   "agraph report 'Project Files/project.edn' --map 'Maps/agraph map.json' --out 'Report Output'"))
    (is (contains? commands
                   "agraph agent install --platform codex --project"))
    (is (= commands (set (#'cli/start-next-commands actions))))))
(deftest sync-runs-index-infer-and-optional-check
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [xtdb project]
                                           (swap! calls conj [:infer xtdb (:id project)])
                                           {:project-id (:id project)
                                            :status :completed
                                            :system-evidence 1
                                            :system-nodes 2
                                            :system-edges 3})
                  project/maintain-project (fn [xtdb project opts]
                                             (swap! calls conj [:check xtdb (:id project) opts])
                                             {:project-id (:id project)
                                              :counts {:maintenance-decisions 0}
                                              :decision-queue []})]
      (with-out-str
        (cli/dispatch "sync" ["project.edn" "--check" "--json"])))
    (is (= [[:read "project.edn"]
            [:index :xtdb "fixture" {:dry-run? false
                                     :index-profile :graph
                                     :map-overlay nil}]
            [:infer :xtdb "fixture"]
            [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                     :map-overlay nil}]]
           @calls))))
(deftest sync-without-maintenance-uses-query-index
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [xtdb project opts]
                                           (swap! calls conj [:index xtdb (:id project) opts])
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [xtdb project]
                                           (swap! calls conj [:infer xtdb (:id project)])
                                           {:project-id (:id project)
                                            :status :completed
                                            :system-evidence 1
                                            :system-nodes 2
                                            :system-edges 3})]
      (with-out-str
        (cli/dispatch "sync" ["project.edn" "--json"])))
    (is (= [[:read "project.edn"]
            [:index :xtdb "fixture" {:dry-run? false
                                     :index-profile :query
                                     :map-overlay nil}]
            [:infer :xtdb "fixture"]]
           @calls))))
(deftest sync-check-enqueues-maintenance-work
  (let [root (temp-dir "agraph-cli-queue")
        decision {:id "maintenance-decision:test"
                  :project-id "fixture"
                  :kind :unclustered-system
                  :status :open
                  :severity :medium
                  :target "system:fixture:api"
                  :reason "Needs review."
                  :evidence-ids []
                  :recommended-actions [:accept-system]
                  :basis {:schema "agraph.graph-basis/v1"
                          :project-id "fixture"
                          :hash "basis"}
                  :data {}}
        infra-packet {:schema infra-review/packet-schema
                      :reviewId "infra-review:test"
                      :project-id "fixture"
                      :kind "container-image-consumer-without-producer"
                      :artifact "container-image:api"
                      :facts {:systems []
                              :evidence []}
                      :allowedActions ["none"]
                      :expectedResultSchema infra-review/result-schema}
        dependency-packet {:schema dependency-review/packet-schema
                           :reviewId "dependency-review:test"
                           :project-id "fixture"
                           :kind "unresolved-import"
                           :facts {:unresolvedImport {:import "org.slf4j.Logger"
                                                      :path "App.java"
                                                      :line 1}
                                   :packages []
                                   :evidence []}
                           :allowedActions ["none"]
                           :expectedResultSchema dependency-review/result-schema}]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/maintain-project (fn [_ _ _]
                                             {:project-id "fixture"
                                              :counts {:maintenance-decisions 1
                                                       :infra-review-items 1
                                                       :dependency-review-items 1}
                                              :decision-queue [decision]
                                              :infra-review-queue [infra-packet]
                                              :dependency-review-queue [dependency-packet]})]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["check" "project.edn"
                                 "--enqueue"
                                 "--json"
                                 "--queue-dir" root]))
            parsed (read-json-output out)
            items (queue/list-items root {:status "ready"})]
        (is (= "agraph.sync.check/v1" (:schema parsed)))
        (is (= #{"maintenance-decision" "infra-review" "dependency-review"}
               (set (mapv #(get-in % [:item :kind]) items))))
        (is (= #{"maintenance-decision:test" nil}
               (set (mapv #(get-in % [:item :payload :decisionId]) items))))
        (is (= #{"infra-review:test" nil}
               (set (mapv #(when (= infra-review/packet-schema
                                    (get-in % [:item :payload :schema]))
                             (get-in % [:item :payload :reviewId]))
                          items))))
        (is (= #{"dependency-review:test" nil}
               (set (mapv #(when (= dependency-review/packet-schema
                                    (get-in % [:item :payload :schema]))
                             (get-in % [:item :payload :reviewId]))
                          items))))))))
(deftest sync-coverage-returns-source-coverage-report
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                coverage/project-coverage (fn [xtdb project opts]
                                            {:schema coverage/schema
                                             :xtdb xtdb
                                             :project-id (:id project)
                                             :opts opts
                                             :totals {:files 2
                                                      :supported 1
                                                      :skipped 1}
                                             :files-by-kind []
                                             :skipped-by-extension [{:ext ".wasm"
                                                                     :count 1
                                                                     :samples [{:repo-id "app"
                                                                                :path "web/widget.wasm"}]}]
                                             :skipped-by-reason [{:reason "unsupported-extension"
                                                                  :count 1
                                                                  :samples [{:repo-id "app"
                                                                             :path "web/widget.wasm"}]}]
                                             :indexedConnectivity {:indexedFiles 1
                                                                   :nodes 2
                                                                   :edges 1
                                                                   :connectedFiles 1
                                                                   :crossFileConnectedFiles 0
                                                                   :isolatedFiles 0
                                                                   :byKind [{:kind "clojure"
                                                                             :indexedFiles 1
                                                                             :connectedFiles 1
                                                                             :crossFileConnectedFiles 0
                                                                             :isolatedFiles 0}]}
                                             :extractors []
                                             :diagnostics {:total 0}
                                             :repos []})]
    (let [out (with-out-str
                (cli/dispatch "sync" ["coverage" "project.edn" "--json"]))
          plain-out (with-out-str
                      (cli/dispatch "sync" ["coverage" "project.edn"]))
          parsed (read-json-output out)]
      (is (= coverage/schema (:schema parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= {:files 2 :supported 1 :skipped 1}
             (:totals parsed)))
      (is (str/includes? plain-out
                         "- .wasm 1 samples app:web/widget.wasm"))
      (is (str/includes? plain-out
                         "- unsupported-extension 1 samples app:web/widget.wasm"))
      (is (str/includes? plain-out
                         "- indexed-connectivity indexed=1 nodes=2 edges=1 connected=1 cross-file=0 isolated=0"))
      (is (str/includes? plain-out
                         "## Connectivity By Kind"))
      (is (str/includes? plain-out
                         "- clojure indexed=1 connected=1 cross-file=0 isolated=0")))))
(deftest audit-scope-command-returns-core-scope-report
  (with-redefs [project/read-project (constantly project-fixture)
                store/with-node (fn [_ f] (f :xtdb))
                graph-map/file-exists? (constantly false)
                audit-scope/report (fn [xtdb project opts]
                                     {:schema audit-scope/report-schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :repo-id (:repo-id opts)
                                      :coverage {:files 2
                                                 :supportedFiles 1
                                                 :skippedFiles 1
                                                 :diagnostics 0}
                                      :scopes [{:kind "runtime-config"
                                                :basis "indexed-graph"
                                                :supportedFiles 1
                                                :skippedFiles 0
                                                :facts 2
                                                :diagnostics 0
                                                :overlayCount 0
                                                :topEvidenceTypes [{:kind "env-var"
                                                                    :count 2}]
                                                :samples [{:repo-id "app"
                                                           :path ".env"}]}]
                                      :nextActions [{:kind :coverage
                                                     :command "agraph sync coverage project.edn --json"}]})]
    (let [out (with-out-str
                (cli/dispatch "audit-scope"
                              ["project.edn" "--repo" "app" "--json"]))
          plain-out (with-out-str
                      (cli/dispatch "audit-scope"
                                    ["project.edn" "--repo" "app"]))
          parsed (read-json-output out)]
      (is (= audit-scope/report-schema (:schema parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= "app" (:repo-id parsed)))
      (is (= [{:kind "runtime-config"
               :basis "indexed-graph"
               :supportedFiles 1
               :skippedFiles 0
               :facts 2
               :diagnostics 0
               :overlayCount 0
               :topEvidenceTypes [{:kind "env-var"
                                   :count 2}]
               :samples [{:repo-id "app"
                          :path ".env"}]}]
             (:scopes parsed)))
      (is (str/includes? plain-out "# Audit Scopes"))
      (is (str/includes? plain-out "## runtime-config"))
      (is (str/includes? plain-out "- evidence env-var:2"))
      (is (str/includes? plain-out "- agraph sync coverage project.edn --json")))))
(deftest sync-inspect-json-includes-evidence-surface
  (with-redefs [project/read-project (constantly project-fixture)
                graph-map/file-exists? (constantly false)
                store/with-node (fn [_ f] (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     {:schema evidence/schema
                                      :xtdb xtdb
                                      :project-id (:id project)
                                      :config-path (:config-path opts)
                                      :available [:source-graph :docs]
                                      :families [{:family :source-graph
                                                  :status :available
                                                  :counts {:nodes 3
                                                           :edges 4}}
                                                 {:family :system-evidence
                                                  :status :missing
                                                  :counts {:system-evidence 0}}
                                                 {:family :docs
                                                  :status :available
                                                  :counts {:search-docs 9}}]
                                      :freshness {:status :stale
                                                  :basis "indexed-graph"
                                                  :projectConfig "project.edn"
                                                  :map "agraph.map.json"
                                                  :mapExists false
                                                  :missingQueryIndex true
                                                  :counts {:indexed 2
                                                           :current 3
                                                           :changed 1
                                                           :missing 0
                                                           :unindexed 1}
                                                  :repos []}
                                      :counts {:files 2
                                               :nodes 3
                                               :edges 4
                                               :activity-events 5
                                               :validation-events 1
                                               :result-schema-statuses {:matching 2
                                                                        :missing-result 1}
                                               :result-schema-status-items 3
                                               :result-schema-matching-items 2
                                               :result-schema-missing-result-items 1
                                               :result-schema-mismatch-events 1
                                               :skipped-files 1
                                               :diagnostics 2}
                                      :top-file-kinds [{:kind "clojure"
                                                        :count 2}]
                                      :extractors [{:kind "clojure"
                                                    :files 2}]
                                      :extractor-fingerprints [{:kind "clojure"
                                                                :extractor-version "clojure/v1"
                                                                :extractor-fingerprint "extractor:clj-a"
                                                                :files 2}]
                                      :diagnostics {:total 2
                                                    :by-stage [{:stage "parse"
                                                                :count 2}]
                                                    :samples [{:repo-id "app"
                                                               :path "src/broken.clj"
                                                               :stage "parse"
                                                               :message "reader error"}]}
                                      :nextActions [{:kind :ask
                                                     :command "agraph ask \"where is this handled?\" --project fixture --json"}]
                                      :next ["agraph ask \"where is this handled?\" --project fixture --json"]})]
    (let [out (with-out-str
                (cli/dispatch "sync" ["inspect" "project.edn" "--json"]))
          plain-out (with-out-str
                      (cli/dispatch "sync" ["inspect" "project.edn"]))
          parsed (read-json-output out)]
      (is (= "agraph.project.inspect/v1" (:schema parsed)))
      (is (= "fixture" (get-in parsed [:project :id])))
      (is (= [{:id "app"
               :root "/tmp/app"
               :role "application"}]
             (:repos parsed)))
      (is (= "agraph.evidence/v2" (get-in parsed [:evidence :schema])))
      (is (= ["source-graph" "docs"] (get-in parsed [:evidence :available])))
      (is (= {:counts {:files 2
                       :skippedFiles 1
                       :diagnostics 2}
              :topFileKinds [{:kind "clojure"
                              :count 2}]
              :extractors [{:kind "clojure"
                            :files 2}]
              :extractorFingerprints [{:kind "clojure"
                                       :extractor-version "clojure/v1"
                                       :extractor-fingerprint "extractor:clj-a"
                                       :files 2}]
              :diagnostics {:total 2
                            :by-stage [{:stage "parse"
                                        :count 2}]
                            :samples [{:repo-id "app"
                                       :path "src/broken.clj"
                                       :stage "parse"
                                       :message "reader error"}]}}
             (:coverage parsed)))
      (is (= {:status "stale"
              :basis "indexed-graph"
              :projectConfig "project.edn"
              :map "agraph.map.json"
              :mapExists false
              :missingQueryIndex true
              :counts {:indexed 2
                       :current 3
                       :changed 1
                       :missing 0
                       :unindexed 1}
              :repos []}
             (:freshness parsed)))
      (is (= [{:kind "ask"
               :command "agraph ask \"where is this handled?\" --project fixture --json"}]
             (:nextActions parsed)))
      (is (= {:files 2
              :nodes 3
              :edges 4
              :activity-events 5
              :validation-events 1
              :result-schema-statuses {:matching 2
                                       :missing-result 1}
              :result-schema-status-items 3
              :result-schema-matching-items 2
              :result-schema-missing-result-items 1
              :result-schema-mismatch-events 1
              :skipped-files 1
              :diagnostics 2}
             (get-in parsed [:evidence :counts])))
      (is (str/includes? plain-out "- activity-events 5"))
      (is (str/includes? plain-out "- validation-events 1"))
      (is (str/includes? plain-out "- result-schema-status-items 3"))
      (is (str/includes? plain-out
                         "- result-schema-statuses matching=2, missing-result=1"))
      (is (str/includes? plain-out "- result-schema-mismatch-events 1"))
      (is (str/includes? plain-out "## Evidence Families"))
      (is (str/includes? plain-out "- source-graph available edges=4, nodes=3"))
      (is (str/includes? plain-out "- system-evidence missing system-evidence=0"))
      (is (str/includes? plain-out "- docs available search-docs=9"))
      (is (str/includes? plain-out "## Freshness"))
      (is (str/includes? plain-out "- status stale"))
      (is (str/includes? plain-out "- basis indexed-graph"))
      (is (str/includes? plain-out "- project-config project.edn"))
      (is (str/includes? plain-out "- map agraph.map.json exists false"))
      (is (str/includes? plain-out "- missing-query-index true"))
      (is (str/includes? plain-out "- changed 1"))
      (is (str/includes? plain-out "- unindexed 1"))
      (let [status-out (with-out-str
                         (cli/dispatch "status" ["project.edn" "--json"]))
            status-parsed (read-json-output status-out)]
        (is (= "agraph.project.inspect/v1" (:schema status-parsed)))
        (is (= "fixture" (get-in status-parsed [:project :id])))
        (is (= ["source-graph" "docs"]
               (get-in status-parsed [:evidence :available])))
        (is (= (:coverage parsed) (:coverage status-parsed)))
        (is (= (:freshness parsed) (:freshness status-parsed)))
        (is (= (:nextActions parsed) (:nextActions status-parsed)))))))
(deftest sync-activity-routes-through-project-config
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  activity/sync-queue! (fn [xtdb project opts]
                                         (swap! calls conj [:activity xtdb (:id project) opts])
                                         {:schema activity/sync-schema
                                          :project-id (:id project)
                                          :queue-root (:queue-root opts)
                                          :counts {:items 1
                                                   :events 2
                                                   :validation-events 1
                                                   :result-schema-mismatch-events 1}
                                          :result-schema-mismatches
                                          [{:sourceId "queue:abc"
                                            :itemId "activity-item:abc"
                                            :expectedResultSchema "expected/v1"
                                            :resultSchema "actual/v1"
                                            :status "done"}]})]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["activity" "project.edn"
                                 "--queue-dir" ".dev/test-queue"
                                 "--json"]))
            plain-out (with-out-str
                        (cli/dispatch "sync"
                                      ["activity" "project.edn"
                                       "--queue-dir" ".dev/test-queue"]))
            parsed (read-json-output out)]
        (is (= activity/sync-schema (:schema parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= 1 (get-in parsed [:counts :result-schema-mismatch-events])))
        (is (= [{:sourceId "queue:abc"
                 :itemId "activity-item:abc"
                 :expectedResultSchema "expected/v1"
                 :resultSchema "actual/v1"
                 :status "done"}]
               (:result-schema-mismatches parsed)))
        (is (str/includes? plain-out "## Result Schema Mismatches"))
        (is (str/includes? plain-out
                           "- queue:abc expected expected/v1 actual actual/v1 status done item activity-item:abc"))
        (is (= [[:read "project.edn"]
                [:activity :xtdb "fixture" {:queue-root ".dev/test-queue"}]
                [:read "project.edn"]
                [:activity :xtdb "fixture" {:queue-root ".dev/test-queue"}]]
               @calls))))))
(deftest bench-run-dispatches-to-benchmark-runner
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/run-suite! (fn [suite opts]
                                         (swap! calls conj [:run suite opts])
                                         {:schema "agraph.benchmark.run/v1"
                                          :suite-id (:id suite)
                                          :cases []})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["run" "benchmark.edn"
                                 "--case" "case-1"
                                 "--out" ".dev/bench"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "agraph.benchmark.run/v1" (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:run {:id "suite"}
                 {:case-id "case-1"
                  :out ".dev/bench"
                  :retriever nil
                  :parser-worker nil
                  :mode nil
                  :result-path nil
                  :command nil}]]
               @calls))))))
(deftest sync-work-heartbeat-extends-claimed-work-lease
  (let [root (temp-dir "agraph-cli-work-heartbeat")
        id (get-in (queue/enqueue! {:schema context/schema
                                    :project-id "fixture"}
                                   {:root root
                                    :kind "context"
                                    :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"
                             :lease-ms 1000})
    (let [out (with-out-str
                (cli/dispatch "sync"
                              ["work" "heartbeat" id
                               "--queue-dir" root
                               "--agent" "codex"
                               "--lease-minutes" "5"]))
          parsed (read-json-output out)]
      (is (= queue/summary-schema (:schema parsed)))
      (is (= id (:id parsed)))
      (is (= "claimed" (:status parsed)))
      (is (= "codex" (get-in parsed [:lease :agent-id])))
      (is (integer? (get-in parsed [:lease :heartbeat-at-ms]))))))
(deftest sync-work-apply-valid-infra-review-result-updates-map
  (let [root (temp-dir "agraph-cli-work-apply")
        dir (temp-dir "agraph-cli-work-map")
        map-path (.getPath (io/file dir "agraph.map.json"))
        result-path (.getPath (io/file dir "result.json"))
        source-id "system:fixture:app:api"
        target-id "system:fixture:env:api"
        evidence-id "evidence:image:api"
        packet {:schema infra-review/packet-schema
                :reviewId "infra-review:test"
                :project-id "fixture"
                :facts {:systems [{:id source-id}
                                  {:id target-id}]
                        :evidence [{:id evidence-id}]}
                :allowedActions ["add-edge" "none"]}
        id (get-in (queue/enqueue! packet {:root root
                                           :kind infra-review/work-kind
                                           :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"})
    (spit result-path
          (json/write-json-str
           {:schema infra-review/result-schema
            :reviewId "infra-review:test"
            :recommendation "add-map-edge"
            :confidence 0.86
            :reason "Evidence supports a deploys relationship."
            :mapPatch [{:op "add-edge"
                        :source source-id
                        :target target-id
                        :relation "deploys"
                        :confidence 0.86
                        :evidence [evidence-id]
                        :reason "Image producer and consumer are verified."}]}
           {:indent-str "  "}))
    (with-out-str
      (cli/dispatch "sync"
                    ["work" "complete" id
                     "--result" result-path
                     "--queue-dir" root]))
    (let [out (with-out-str
                (cli/dispatch "sync"
                              ["work" "apply" id
                               "--map" map-path
                               "--queue-dir" root]))
          parsed (read-json-output out)
          map-data (graph-map/read-map map-path)
          edge (first (:edges map-data))]
      (is (= infra-review/apply-schema (:schema parsed)))
      (is (= "applied" (:status parsed)))
      (is (= 1 (:patchesApplied parsed)))
      (is (= source-id (:source edge)))
      (is (= target-id (:target edge)))
      (is (= "deploys" (:relation edge)))
      (is (= [evidence-id] (:evidence edge)))
      (is (str/includes? (:rules edge) "infra-review:test")))))
(deftest sync-work-apply-rejects-infra-review-edge-without-evidence
  (let [root (temp-dir "agraph-cli-work-apply-missing-evidence")
        dir (temp-dir "agraph-cli-work-map-missing-evidence")
        map-path (.getPath (io/file dir "agraph.map.json"))
        result-path (.getPath (io/file dir "result.json"))
        source-id "system:fixture:app:api"
        target-id "system:fixture:env:api"
        evidence-id "evidence:image:api"
        packet {:schema infra-review/packet-schema
                :reviewId "infra-review:test"
                :project-id "fixture"
                :facts {:systems [{:id source-id}
                                  {:id target-id}]
                        :evidence [{:id evidence-id}]}
                :allowedActions ["add-edge" "none"]}
        id (get-in (queue/enqueue! packet {:root root
                                           :kind infra-review/work-kind
                                           :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"})
    (spit result-path
          (json/write-json-str
           {:schema infra-review/result-schema
            :reviewId "infra-review:test"
            :recommendation "add-map-edge"
            :confidence 0.86
            :reason "Evidence supports a deploys relationship."
            :mapPatch [{:op "add-edge"
                        :source source-id
                        :target target-id
                        :relation "deploys"
                        :confidence 0.86
                        :reason "Image producer and consumer are verified."}]}
           {:indent-str "  "}))
    (with-out-str
      (cli/dispatch "sync"
                    ["work" "complete" id
                     "--result" result-path
                     "--queue-dir" root]))
    (let [out (with-out-str
                (cli/dispatch "sync"
                              ["work" "apply" id
                               "--map" map-path
                               "--queue-dir" root]))
          parsed (read-json-output out)]
      (is (= infra-review/apply-schema (:schema parsed)))
      (is (= "failed" (:status parsed)))
      (is (= [{:path ["mapPatch" 0 "evidence"]
               :error "Edge patch must cite at least one facts.evidence[].id."}]
             (:errors parsed)))
      (is (false? (graph-map/file-exists? map-path))))))
(deftest sync-work-apply-valid-dependency-review-result-updates-map
  (let [root (temp-dir "agraph-cli-work-dependency-apply")
        dir (temp-dir "agraph-cli-work-dependency-map")
        map-path (.getPath (io/file dir "agraph.map.json"))
        result-path (.getPath (io/file dir "result.json"))
        evidence-id "dependency-evidence:test"
        packet {:schema dependency-review/packet-schema
                :reviewId "dependency-review:test"
                :project-id "fixture"
                :facts {:unresolvedImport {:repo-id "app"
                                           :import "org.slf4j.Logger"
                                           :path "App.java"
                                           :line 1}
                        :packages [{:ecosystem "maven"
                                    :package-name "org.slf4j:slf4j-api"}]
                        :evidence [{:id evidence-id}]}
                :allowedActions ["add-package-import" "none"]}
        id (get-in (queue/enqueue! packet {:root root
                                           :kind dependency-review/work-kind
                                           :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"})
    (spit result-path
          (json/write-json-str
           {:schema dependency-review/result-schema
            :reviewId "dependency-review:test"
            :recommendation "add-package-import"
            :confidence 0.86
            :reason "The import prefix is provided by the declared package."
            :mapPatch [{:op "add-package-import"
                        :import "org.slf4j"
                        :ecosystem "maven"
                        :package "org.slf4j:slf4j-api"
                        :evidence [evidence-id]
                        :reason "Explicit package import mapping."}]}
           {:indent-str "  "}))
    (with-out-str
      (cli/dispatch "sync"
                    ["work" "complete" id
                     "--result" result-path
                     "--queue-dir" root]))
    (let [out (with-out-str
                (cli/dispatch "sync"
                              ["work" "apply" id
                               "--map" map-path
                               "--queue-dir" root]))
          parsed (read-json-output out)
          package-import (first (:packageImports (graph-map/read-map map-path)))]
      (is (= dependency-review/apply-schema (:schema parsed)))
      (is (= "applied" (:status parsed)))
      (is (= 1 (:patchesApplied parsed)))
      (is (= "org.slf4j" (:import package-import)))
      (is (= "maven" (:ecosystem package-import)))
      (is (= "org.slf4j:slf4j-api" (:package package-import)))
      (is (= [evidence-id] (:evidence package-import)))
      (is (= "dependency-review:test" (:rules package-import)))
      (is (= "dependency-review:test" (:reviewId package-import)))
      (is (= 0.86 (:confidence package-import))))))
(deftest infra-review-result-requires-supported-recommendation-and-reason
  (let [packet {:schema infra-review/packet-schema
                :reviewId "infra-review:test"
                :project-id "fixture"
                :facts {:systems []
                        :evidence []}
                :allowedActions ["none"]}
        item {:status :done
              :payload packet
              :result {:schema infra-review/result-schema
                       :reviewId "infra-review:test"
                       :recommendation "maybe"
                       :confidence 0.5
                       :mapPatch []}}]
    (is (= [{:path [:result :recommendation]
             :error "Result recommendation is required and must be supported."
             :value "maybe"}
            {:path [:result :reason]
             :error "Result reason is required."}]
           (infra-review/validate-result item)))
    (is (empty? (infra-review/validate-result
                 (assoc item
                        :result {:schema infra-review/result-schema
                                 :reviewId "infra-review:test"
                                 :recommendation "needs-human"
                                 :confidence 0.5
                                 :reason "The packet evidence is insufficient."
                                 :mapPatch []
                                 :findings ["Needs a human call."]}))))))
(deftest sync-work-apply-valid-maintenance-result-updates-map
  (let [root (temp-dir "agraph-cli-work-maintenance-apply")
        dir (temp-dir "agraph-cli-work-maintenance-map")
        map-path (.getPath (io/file dir "agraph.map.json"))
        result-path (.getPath (io/file dir "result.json"))
        target-id "system:fixture:app:cmd-api"
        decision {:id "maintenance-decision:test"
                  :project-id "fixture"
                  :kind :unclustered-system
                  :status :open
                  :severity :low
                  :target target-id
                  :reason "Candidate has enough local evidence to name explicitly."
                  :recommended-actions [:set-system-kind :add-edge]
                  :basis {:schema "agraph.graph-basis/v1"
                          :project-id "fixture"
                          :hash "basis"}
                  :data {:system {:xt/id target-id
                                  :label "cmd/api"
                                  :kind :candidate
                                  :repo-id "app"
                                  :path-prefix "cmd/api"}}}
        id (get-in (queue/enqueue! (decision-classifier/decision-packet decision)
                                   {:root root
                                    :kind "maintenance-decision"
                                    :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"})
    (spit result-path
          (json/write-json-str
           {:schema decision-classifier/schema
            :decisionId "maintenance-decision:test"
            :recommendation "change"
            :confidence 0.78
            :reason "The existing candidate should be accepted with a clearer kind."
            :mapPatch [{:op "set-system-kind"
                        :value {:target target-id
                                :kind "application"}
                        :reason "Accept the bounded system candidate."}
                       {:op "add-edge"
                        :value {:source target-id
                                :target "external-api:api.fixture.test"
                                :relation "references"}
                        :reason "Record a bounded relation from the packet."}]}
           {:indent-str "  "}))
    (with-out-str
      (cli/dispatch "sync"
                    ["work" "complete" id
                     "--result" result-path
                     "--queue-dir" root]))
    (let [out (with-out-str
                (cli/dispatch "sync"
                              ["work" "apply" id
                               "--map" map-path
                               "--queue-dir" root]))
          parsed (read-json-output out)
          map-data (graph-map/read-map map-path)
          system (first (:systems map-data))
          edge (first (:edges map-data))]
      (is (= infra-review/apply-schema (:schema parsed)))
      (is (= "applied" (:status parsed)))
      (is (= 2 (:patchesApplied parsed)))
      (is (= target-id (:id system)))
      (is (= "application" (:kind system)))
      (is (= "accepted" (:status system)))
      (is (= [{:repo "app" :path "cmd/api"}] (:includes system)))
      (is (= target-id (:source edge)))
      (is (= "external-api:api.fixture.test" (:target edge)))
      (is (= "references" (:relation edge))))))
(deftest maintenance-decision-result-requires-supported-recommendation-and-reasons
  (let [target-id "system:fixture:app:cmd-api"
        packet (decision-classifier/decision-packet
                {:id "maintenance-decision:test"
                 :project-id "fixture"
                 :target target-id
                 :recommended-actions [:set-system-kind]
                 :data {:system {:xt/id target-id
                                 :label "cmd/api"}}})
        invalid {:status :done
                 :payload packet
                 :result {:schema decision-classifier/schema
                          :decisionId "maintenance-decision:test"
                          :recommendation "maybe"
                          :confidence 0.5
                          :mapPatch [{:op "set-system-kind"
                                      :target target-id
                                      :value {:kind "application"}}]}}]
    (is (= [{:path [:result :recommendation]
             :error "Result recommendation is required and must be supported."
             :value "maybe"}
            {:path [:result :reason]
             :error "Result reason is required."}
            {:path [:mapPatch 0 :reason]
             :error "Patch reason is required."}]
           (decision-classifier/validate-result invalid)))
    (is (empty? (decision-classifier/validate-result
                 (assoc invalid
                        :result {:schema decision-classifier/schema
                                 :decisionId "maintenance-decision:test"
                                 :recommendation "investigate"
                                 :confidence 0.5
                                 :reason "The packet does not contain enough bounded evidence."
                                 :mapPatch []}))))))
(deftest maintenance-decision-result-rejects-unadvertised-patch-actions
  (let [target-id "system:fixture:app:cmd-api"
        packet (decision-classifier/decision-packet
                {:id "maintenance-decision:test"
                 :project-id "fixture"
                 :target target-id
                 :recommended-actions [:set-system-kind]
                 :data {:system {:xt/id target-id
                                 :label "cmd/api"}}})
        item {:status :done
              :payload packet
              :result {:schema decision-classifier/schema
                       :decisionId "maintenance-decision:test"
                       :recommendation "change"
                       :confidence 0.5
                       :reason "A bounded correction is warranted."
                       :mapPatch [{:op "add-edge"
                                   :value {:source target-id
                                           :target "external-api:api.fixture.test"
                                           :relation "references"}
                                   :reason "This patch was not advertised by the decision."}]}}]
    (is (= ["set-system-kind" "none"]
           (:allowedActions packet)))
    (is (= [{:path [:mapPatch 0 :op]
             :error "Maintenance map patch op is not allowed for this decision."
             :value "add-edge"}]
           (decision-classifier/validate-result item)))
    (is (empty? (decision-classifier/validate-result
                 (assoc-in item
                           [:result :mapPatch]
                           [{:op "set-system-kind"
                             :target target-id
                             :value {:kind "application"}
                             :reason "Accept the bounded system candidate."}]))))))
(deftest sync-work-show-returns-summary-with-full-item
  (let [root (temp-dir "agraph-cli-work-show")
        id (get-in (queue/enqueue! {:schema "custom.packet/v1"
                                    :project-id "fixture"
                                    :expectedResultSchema "custom.result/v1"
                                    :value 1}
                                   {:root root
                                    :kind "custom"
                                    :priority 10})
                   [:item :id])
        out (with-out-str
              (cli/dispatch "sync" ["work" "show" id "--queue-dir" root]))
        parsed (read-json-output out)]
    (is (= queue/summary-schema (:schema parsed)))
    (is (= id (:id parsed)))
    (is (= "custom" (:kind parsed)))
    (is (= "custom.packet/v1" (:payload-schema parsed)))
    (is (= "custom.result/v1" (:expected-result-schema parsed)))
    (is (= "custom.packet/v1" (get-in parsed [:item :payload :schema])))))
(deftest sync-work-pull-returns-summary-with-full-item
  (let [root (temp-dir "agraph-cli-work-pull")
        id (get-in (queue/enqueue! {:schema infra-review/packet-schema
                                    :reviewId "infra-review:test"
                                    :project-id "fixture"
                                    :kind "container-image-consumer-without-producer"
                                    :artifact "container-image:api"}
                                   {:root root
                                    :kind infra-review/work-kind
                                    :project-id "fixture"
                                    :priority 50})
                   [:item :id])
        out (with-out-str
              (cli/dispatch "sync"
                            ["work" "pull"
                             "--project" "fixture"
                             "--kind" "infra-review"
                             "--agent" "codex"
                             "--queue-dir" root]))
        parsed (read-json-output out)]
    (is (= queue/summary-schema (:schema parsed)))
    (is (= id (:id parsed)))
    (is (= "claimed" (:status parsed)))
    (is (= "infra-review:test" (get-in parsed [:payload-summary :id])))
    (is (= "container-image:api" (get-in parsed [:payload-summary :artifact])))
    (is (= infra-review/packet-schema (get-in parsed [:item :payload :schema])))))
(deftest ask-json-returns-context-packet
  (let [summaries (atom [])]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  project/read-project (fn [path]
                                         (assoc project-fixture :path path))
                  evidence/summarize (fn [xtdb project opts]
                                       (swap! summaries conj [xtdb project opts])
                                       {:freshness {:status :current
                                                    :counts {:indexed 3}}})
                  context/context-packet (fn [xtdb query-text opts]
                                           {:schema context/schema
                                            :xtdb xtdb
                                            :query query-text
                                            :project-id (:project-id opts)
                                            :freshness (:freshness opts)})]
      (let [out (with-out-str
                  (cli/dispatch "ask"
                                ["where" "auth"
                                 "--project" "fixture"
                                 "--config" "project.edn"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= context/schema (:schema parsed)))
        (is (= "where auth" (:query parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= {:status "current"
                :counts {:indexed 3}}
               (:freshness parsed)))
        (is (= [[:xtdb
                 (assoc project-fixture :path "project.edn")
                 {:map-overlay nil
                  :config-path "project.edn"
                  :map-path nil}]]
               @summaries))))))
(deftest explore-json-returns-one-shot-context-packet
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                project/read-project (fn [path]
                                       (assoc project-fixture :path path))
                evidence/summarize (fn [_ _ _]
                                     {:freshness {:status :stale
                                                  :counts {:changed 1}}})
                context/context-packet (fn [xtdb query-text opts]
                                         {:schema context/schema
                                          :xtdb xtdb
                                          :query query-text
                                          :project-id (:project-id opts)
                                          :retriever (:retriever opts)
                                          :freshness (:freshness opts)
                                          :answerability {:status :usable}})]
    (let [out (with-out-str
                (cli/dispatch "explore"
                              ["where" "auth"
                               "--project" "fixture"
                               "--config" "project.edn"
                               "--retriever" "lexical"
                               "--json"]))
          parsed (read-json-output out)]
      (is (= context/schema (:schema parsed)))
      (is (= "where auth" (:query parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= "lexical" (:retriever parsed)))
      (is (= {:status "stale"
              :counts {:changed 1}}
             (:freshness parsed)))
      (is (= {:status "usable"} (:answerability parsed))))))
(deftest ask-plain-empty-result-prints-answerability-warning
  (let [err (java.io.StringWriter.)
        calls (atom [])]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  query/semantic-query (fn [xtdb query-text opts]
                                         (swap! calls conj [:query xtdb query-text opts])
                                         [])
                  context/context-packet (fn [xtdb query-text opts]
                                           (swap! calls conj [:context xtdb query-text opts])
                                           {:schema context/schema
                                            :answerability
                                            {:status :empty
                                             :missing [:docs :embeddings]
                                             :weak []
                                             :unsupported [:remote-work]
                                             :warnings ["No search docs are indexed."
                                                        "No activity/work rows are indexed."]
                                             :next ["Run agraph sync <project.edn> --query-index"]}})]
      (let [out (with-out-str
                  (binding [*err* err]
                    (cli/dispatch "ask"
                                  ["prior" "work"
                                   "--project" "fixture"
                                   "--retriever" "lexical"])))]
        (is (= "" out))
        (is (str/includes? (str err) "No query results."))
        (is (str/includes? (str err) "Answerability empty"))
        (is (str/includes? (str err) "Missing evidence: docs, embeddings"))
        (is (str/includes? (str err) "Unsupported evidence: remote-work"))
        (is (str/includes? (str err) "Run agraph sync <project.edn> --query-index"))
        (is (= [:query :context] (mapv first @calls)))
        (is (= "prior work" (nth (second @calls) 2)))))))
(deftest ask-plain-empty-result-shows-actionable-next-steps
  (let [err (java.io.StringWriter.)]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  query/semantic-query (fn [& _] [])
                  context/context-packet (fn [& _]
                                           {:schema context/schema
                                            :answerability
                                            {:status :limited
                                             :missing []
                                             :weak [:dependencies]
                                             :unsupported []
                                             :warnings ["No search docs are indexed."
                                                        "Dependency graph has unresolved imports."
                                                        "Package version conflicts are present."
                                                        "No embeddings are indexed."]
                                             :next ["Run agraph packages --project fixture --json"
                                                    "Run agraph packages --project fixture --without-import-evidence --json"
                                                    "Run agraph packages --project fixture --with-conflicts --json"
                                                    "Run agraph sync <project.edn> --check --enqueue"
                                                    "Run agraph sync coverage <project.edn> --json"]}})]
      (with-out-str
        (binding [*err* err]
          (cli/dispatch "ask" ["deps" "--project" "fixture" "--retriever" "lexical"])))
      (is (str/includes? (str err) "Weak evidence: dependencies"))
      (is (str/includes? (str err) "Run agraph sync <project.edn> --check --enqueue"))
      (is (str/includes? (str err) "1 more warnings in --json output."))
      (is (str/includes? (str err) "1 more commands in --json output.")))))
(deftest ask-plain-success-does-not-build-context-packet
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                query/semantic-query (fn [_ _ _]
                                       [{:score 1.0
                                         :result-kind :node
                                         :label "Auth"
                                         :path "src/auth.clj"
                                         :source-line 7
                                         :reason "lexical match"}])
                context/context-packet (fn [& _]
                                         (throw (ex-info "unexpected context packet" {})))]
    (let [err (java.io.StringWriter.)
          out (with-out-str
                (binding [*err* err]
                  (cli/dispatch "ask"
                                ["auth"
                                 "--project" "fixture"
                                 "--retriever" "lexical"])))]
      (is (str/includes? out "Auth"))
      (is (str/includes? out "lexical match"))
      (is (= "" (str err))))))
(deftest query-json-returns-search-report
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                query/search-report (fn [xtdb query-text opts]
                                      {:schema query/search-report-schema
                                       :xtdb xtdb
                                       :query-text query-text
                                       :retriever-requested (:retriever opts)
                                       :retriever-effective :lexical
                                       :project-id (:project-id opts)
                                       :instrumentation {:search-docs 2
                                                         :returned-count 1}
                                       :results [{:label "Auth"}]})]
    (let [out (with-out-str
                (cli/dispatch "query" ["auth"
                                       "--project" "fixture"
                                       "--retriever" "lexical"
                                       "--json"]))
          parsed (read-json-output out)]
      (is (= query/search-report-schema (:schema parsed)))
      (is (= "auth" (:query-text parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= 2 (get-in parsed [:instrumentation :search-docs])))
      (is (= [{:label "Auth"}] (:results parsed))))))
(deftest explore-routes-to-cursor-implementation
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                cursor/create! (fn [xtdb opts]
                                 {:schema cursor/packet-schema
                                  :xtdb xtdb
                                  :basis {:project-id (:project-id opts)}
                                  :cursor {:id "cursor:test"}})]
    (let [out (with-out-str
                (cli/dispatch "explore" ["create" "api" "--project" "fixture"]))
          parsed (read-json-output out)]
      (is (= cursor/packet-schema (:schema parsed)))
      (is (= "fixture" (get-in parsed [:basis :project-id])))
      (is (= "cursor:test" (get-in parsed [:cursor :id]))))))
(deftest view-json-writes-canonical-export
  (let [written (atom nil)]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  graph/overview-graph (fn [xtdb opts]
                                         {:schema graph/schema
                                          :xtdb xtdb
                                          :opts opts
                                          :title "Overview"
                                          :nodes []
                                          :edges []})
                  graph/write-canonical! (fn [path data]
                                           (reset! written {:path path
                                                            :data data})
                                           path)]
      (with-out-str
        (cli/dispatch "view" ["overview" "--format" "json" "--out" "graph.json"]))
      (is (= "graph.json" (:path @written)))
      (is (= graph/schema (get-in @written [:data :schema]))))))
(deftest sync-ignore-updates-map-file
  (let [dir (temp-dir "agraph-cli-map")
        map-path (.getPath (io/file dir "agraph.map.json"))]
    (spit map-path (json/write-json-str {:schema "agraph.map/v1"
                                         :project "fixture"
                                         :systems []
                                         :reject []
                                         :edges []
                                         :docs []}))
    (with-out-str
      (cli/dispatch "sync"
                    ["ignore" "external-api" "docs.example.com"
                     "--map" map-path
                     "--reason" "Documentation reference"]))
    (let [data (json/read-json (slurp map-path) :key-fn keyword)]
      (is (= [{:match {:kind "external-api"
                       :host "docs.example.com"}
               :reason "Documentation reference"}]
             (:reject data))))))
(deftest sync-package-import-updates-map-file
  (let [dir (temp-dir "agraph-cli-package-import-map")
        map-path (.getPath (io/file dir "agraph.map.json"))]
    (spit map-path (json/write-json-str {:schema "agraph.map/v1"
                                         :project "fixture"
                                         :systems []
                                         :reject []
                                         :edges []
                                         :docs []
                                         :packageImports []}))
    (with-out-str
      (cli/dispatch "sync"
                    ["package" "import" "org.slf4j" "maven:org.slf4j:slf4j-api"
                     "--repo" "app"
                     "--map" map-path
                     "--reason" "Maven coordinate exports this Java package"]))
    (let [data (json/read-json (slurp map-path) :key-fn keyword)]
      (is (= [{:import "org.slf4j"
               :ecosystem "maven"
               :package "org.slf4j:slf4j-api"
               :status "accepted"
               :repo "app"
               :reason "Maven coordinate exports this Java package"}]
             (:packageImports data))))))
