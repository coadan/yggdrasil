(ns ygg.cli-sync-query-test
  (:require [ygg.activity :as activity]
            [ygg.audit-scope :as audit-scope]
            [ygg.benchmark :as benchmark]
            [ygg.cli :as cli]
            [ygg.cli-query :as cli-query]
            [ygg.context :as context]
            [ygg.corrections :as corrections]
            [ygg.coverage :as coverage]
            [ygg.dependency-review :as dependency-review]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.infra-review :as infra-review]
            [ygg.init :as init]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.queue :as queue]
            [ygg.query :as query]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.xtdb :as store]
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

(defn- temp-queue-db
  [prefix]
  (.getPath (io/file (temp-dir prefix) "project.sqlite")))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- read-json-output
  [s]
  (json/read-json s :key-fn keyword))

(defn- json-roundtrip
  [value]
  (json/read-json (json/write-json-str value) :key-fn keyword))

(def project-fixture
  {:id "fixture"
   :name "Fixture"
   :path "project.edn"
   :repos [{:id "app"
            :root "/tmp/app"
            :role :application}]})

(def plugin-package-fixture
  {:id "datastar-hiccup"
   :version "0.1.0"
   :visibility :public
   :scope {:kind :base}
   :benchmark-status :unbenchmarked
   :claim-authority {:status :non-authoritative}
   :diagnostic-counts {:total 1
                       :errors 0
                       :warnings 1}
   :warnings ["datastar-hiccup is unbenchmarked"]})

(def project-with-plugin-package
  (assoc-in project-fixture [:plugins :packages] [plugin-package-fixture]))

(defn- empty-correction-overlay
  [project-id]
  {:schema "ygg.correction-overlay/v1"
   :project project-id
   :systems []
   :reject []
   :edges []
   :docs []})

(deftest init-command-can-run-sync-with-corrections-overlay
  (let [root (temp-dir "ygg-cli-init")
        config-path (.getPath (io/file root "project.edn"))
        canonical-config-path (canonical-path config-path)
        calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         (assoc project-fixture :path path))
                  corrections/overlay (fn [_ project-id]
                                        (empty-correction-overlay project-id))
                  store/xtdb-handle? (constantly true)
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
                                        "--sync"]))
            parsed (read-json-output out)]
        (is (= init/schema (:schema parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (str/includes? (:sync-output parsed) "# Sync"))
        (is (= [[:read canonical-config-path]
                [:read canonical-config-path]
                [:index :xtdb "fixture" {:dry-run? false
                                         :index-profile :query
                                         :correction-overlay {:schema "ygg.correction-overlay/v1"
                                                              :project "fixture"
                                                              :systems []
                                                              :reject []
                                                              :edges []
                                                              :docs []}}]
                [:infer :xtdb "fixture"]
                [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                         :correction-overlay {:schema "ygg.correction-overlay/v1"
                                                              :project "fixture"
                                                              :systems []
                                                              :reject []
                                                              :edges []
                                                              :docs []}}]]
               (mapv (fn [call]
                       (if (contains? #{:index :check} (first call))
                         (-> call
                             (update-in [3 :correction-overlay] #(select-keys %
                                                                              [:schema
                                                                               :project
                                                                               :systems
                                                                               :reject
                                                                               :edges
                                                                               :docs]))
                             (update 3 dissoc :progress-fn))
                         call))
                     @calls))))
      (is (fn? (->> @calls
                    (filter #(= :index (first %)))
                    first
                    (#(get-in % [3 :progress-fn])))))
      (is (= {:schema "ygg.correction-overlay/v1"
              :project "fixture"
              :systems []
              :reject []
              :edges []
              :docs []}
             (-> @calls
                 last
                 (get-in [3 :correction-overlay])
                 (select-keys [:schema :project :systems :reject :edges :docs])))))))
(deftest sync-runs-index-infer-and-optional-check
  (let [config-path (canonical-path "project.edn")
        calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         project-fixture)
                  store/project-sqlite-path (constantly ".dev/test-project.sqlite")
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
    (is (= [[:read config-path]
            [:index :xtdb "fixture" {:dry-run? false
                                     :index-profile :graph
                                     :correction-overlay nil}]
            [:infer :xtdb "fixture"]
            [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                     :correction-overlay nil}]]
           @calls))))
(deftest sync-without-maintenance-uses-query-index
  (let [config-path (canonical-path "project.edn")
        calls (atom [])]
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
    (is (= [[:read config-path]
            [:index :xtdb "fixture" {:dry-run? false
                                     :index-profile :query
                                     :correction-overlay nil}]
            [:infer :xtdb "fixture"]]
           @calls))))
(deftest sync-check-enqueues-maintenance-work
  (let [root (temp-queue-db "ygg-cli-queue")
        decision {:id "maintenance-decision:test"
                  :project-id "fixture"
                  :kind :unclustered-system
                  :status :open
                  :severity :medium
                  :target "system:fixture:api"
                  :reason "Needs review."
                  :evidence-ids []
                  :recommended-actions [:accept-system]
                  :basis {:schema "ygg.graph-basis/v1"
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
                  store/project-sqlite-path (constantly root)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [_ project _]
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [_ project]
                                           {:project-id (:id project)
                                            :status :completed})
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
                                ["project.edn"
                                 "--check"
                                 "--enqueue"
                                 "--json"]))
            parsed (read-json-output out)
            items (queue/list-items root {:status "ready"})]
        (is (= "ygg.sync/v1" (:schema parsed)))
        (is (= index-maintenance/schema (get-in parsed [:check-report :schema])))
        (is (= "graph" (get-in parsed [:check-report :lanes :graph :lane])))
        (is (= 3 (count (get-in parsed [:check-report :work]))))
        (is (= #{"maintenance-decision" "infra-review" "dependency-review"}
               (set (mapv #(get-in % [:item :kind]) items))))
        (is (= #{{:schema index-maintenance/source-schema
                  :producer index-maintenance/producer
                  :lane index-maintenance/graph-lane}}
               (set (mapv #(get-in % [:item :source]) items))))
        (is (= #{"maintenance-decision:test"}
               (set (mapcat #(map :decisionId
                                  (get-in % [:item :payload :items]))
                            items))))
        (is (= #{"infra-review:test" nil}
               (set (mapv #(when (= infra-review/packet-schema
                                    (get-in % [:item :payload :schema]))
                             (get-in % [:item :payload :reviewId]))
                          items))))
        (is (= #{"dependency-review:test" nil}
               (set (mapv #(when (= dependency-review/packet-schema
                                    (get-in % [:item :payload :schema]))
                             (get-in % [:item :payload :reviewId]))
                          items))))
        (with-out-str
          (cli/dispatch "sync"
                        ["project.edn"
                         "--check"
                         "--enqueue"
                         "--json"]))
        (is (= 3 (count (queue/list-items root {:status "ready"}))))))))

(deftest sync-check-does-not-reenqueue-identical-review-batches
  (let [root (temp-queue-db "ygg-cli-review-batch-queue")
        infra-packets (mapv (fn [idx]
                              {:schema infra-review/packet-schema
                               :reviewId (str "infra-review:batch-" idx)
                               :project-id "fixture"
                               :kind "container-image-consumer-without-producer"
                               :artifact (str "container-image:api-" idx)
                               :facts {:systems []
                                       :evidence []}
                               :allowedActions ["none"]
                               :expectedOutput {:schema infra-review/result-schema
                                                :reviewId (str "infra-review:batch-" idx)}
                               :expectedResultSchema infra-review/result-schema})
                            (range index-maintenance/review-batch-size))
        dependency-packets (mapv (fn [idx]
                                   {:schema dependency-review/packet-schema
                                    :reviewId (str "dependency-review:batch-" idx)
                                    :project-id "fixture"
                                    :kind "unresolved-import"
                                    :facts {:unresolvedImport {:import (str "missing-" idx)
                                                               :path "App.java"
                                                               :line idx}
                                            :packages []
                                            :evidence []}
                                    :allowedActions ["none"]
                                    :expectedOutput {:schema dependency-review/result-schema
                                                     :reviewId (str "dependency-review:batch-" idx)}
                                    :expectedResultSchema dependency-review/result-schema})
                                 (range index-maintenance/review-batch-size))]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/project-sqlite-path (constantly root)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [_ project _]
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [_ project]
                                           {:project-id (:id project)
                                            :status :completed})
                  project/maintain-project (fn [_ _ _]
                                             {:project-id "fixture"
                                              :counts {:infra-review-items (count infra-packets)
                                                       :dependency-review-items (count dependency-packets)}
                                              :infra-review-queue infra-packets
                                              :dependency-review-queue dependency-packets})]
      (with-out-str
        (cli/dispatch "sync"
                      ["project.edn"
                       "--check"
                       "--enqueue"
                       "--json"]))
      (let [items (queue/list-items root {:status "ready"})]
        (is (= 2 (count items)))
        (is (= #{infra-review/batch-packet-schema
                 dependency-review/batch-packet-schema}
               (set (mapv #(get-in % [:item :payload :schema]) items)))))
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["project.edn"
                                 "--check"
                                 "--enqueue"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= ["existing" "existing"]
               (sort (mapv :enqueue-status (:enqueued parsed)))))
        (is (= {:items 2
                :enqueued 0
                :existing 2
                :over-emitted 2
                :by-status {:existing 2}
                :by-kind {:infra-review 1
                          :dependency-review 1}}
               (:enqueue-summary parsed)))
        (is (= 2 (count (queue/list-items root))))))))

(deftest sync-check-supersedes-stale-maintenance-work
  (let [root (temp-queue-db "ygg-cli-stale-maintenance-queue")
        stale-packet {:schema decision-classifier/batch-packet-schema
                      :batchId "maintenance-decision-batch:stale"
                      :project-id "fixture"
                      :items []
                      :expectedResultSchema decision-classifier/batch-result-schema}
        stale-source {:schema index-maintenance/source-schema
                      :producer index-maintenance/producer
                      :lane index-maintenance/graph-lane}
        stale-ready-id (get-in (queue/enqueue! stale-packet
                                               {:root root
                                                :kind "maintenance-decision"
                                                :project-id "fixture"
                                                :source stale-source})
                               [:item :id])
        stale-failed-id (get-in (queue/fail!
                                 root
                                 (get-in (queue/enqueue!
                                          (assoc stale-packet
                                                 :batchId
                                                 "maintenance-decision-batch:failed")
                                          {:root root
                                           :kind "maintenance-decision"
                                           :project-id "fixture"
                                           :source stale-source})
                                         [:item :id])
                                 "old failure")
                                [:item :id])
        stale-claimed-id (let [id (get-in (queue/enqueue!
                                           (assoc stale-packet
                                                  :batchId
                                                  "maintenance-decision-batch:claimed")
                                           {:root root
                                            :kind "maintenance-decision"
                                            :project-id "fixture"
                                            :priority 99
                                            :source stale-source})
                                          [:item :id])]
                           (queue/claim-next! root
                                              {:agent-id "old-worker"
                                               :project-id "fixture"
                                               :kind "maintenance-decision"
                                               :lease-ms -1})
                           id)
        unrelated-id (get-in (queue/enqueue!
                              {:schema "custom.packet/v1"
                               :id "custom"}
                              {:root root
                               :kind "custom"
                               :project-id "fixture"})
                             [:item :id])
        current-decision {:id "maintenance-decision:current"
                          :project-id "fixture"
                          :kind :unclustered-system
                          :status :open
                          :severity :medium
                          :target "system:fixture:api"
                          :reason "Needs review."
                          :evidence-ids []
                          :recommended-actions [:accept-system]
                          :basis {:schema "ygg.graph-basis/v1"
                                  :project-id "fixture"
                                  :hash "basis"}
                          :data {}}]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/project-sqlite-path (constantly root)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/index-project! (fn [_ project _]
                                           {:project-id (:id project)
                                            :status :completed
                                            :repos []})
                  project/infer-project! (fn [_ project]
                                           {:project-id (:id project)
                                            :status :completed})
                  project/maintain-project (fn [_ _ _]
                                             {:project-id "fixture"
                                              :counts {:maintenance-decisions 1}
                                              :decision-queue [current-decision]})]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["project.edn"
                                 "--check"
                                 "--enqueue"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= ["enqueued"] (mapv :enqueue-status (:enqueued parsed))))
        (is (= "rejected" (get-in (queue/find-item root stale-ready-id)
                                  [:item :status])))
        (is (= "rejected" (get-in (queue/find-item root stale-failed-id)
                                  [:item :status])))
        (is (= "rejected" (get-in (queue/find-item root stale-claimed-id)
                                  [:item :status])))
        (is (= "Superseded by newer index maintenance report."
               (get-in (queue/find-item root stale-ready-id) [:item :reason])))
        (is (= "ready" (get-in (queue/find-item root unrelated-id)
                               [:item :status])))
        (is (= 1 (count (queue/list-items root {:status "ready"
                                                :kind "maintenance-decision"}))))))))

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
                                                     :command "ygg sync coverage project.edn --json"}]})]
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
      (is (str/includes? plain-out "- ygg sync coverage project.edn --json")))))
(deftest sync-inspect-json-includes-evidence-surface
  (with-redefs [project/read-project (constantly project-fixture)
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
                                                  :missingQueryIndex true
                                                  :counts {:indexed 2
                                                           :current 3
                                                           :changed 1
                                                           :missing 0
                                                           :unindexed 1}
                                                  :repos [{:repo-id "app"
                                                           :status :stale
                                                           :counts {:indexed 2
                                                                    :current 3
                                                                    :changed 1
                                                                    :missing 0
                                                                    :unindexed 1}
                                                           :git-state {:git-branch "main"
                                                                       :git-upstream "origin/main"
                                                                       :git-upstream-status :behind
                                                                       :git-upstream-current? false
                                                                       :git-ahead 0
                                                                       :git-behind 4}}]}
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
                                      :nextActions [{:kind :query
                                                     :command "ygg query \"where is this handled?\" --project fixture --json"}]
                                      :next ["ygg query \"where is this handled?\" --project fixture --json"]})]
    (let [out (with-out-str
                (cli/dispatch "sync" ["inspect" "project.edn" "--json"]))
          plain-out (with-out-str
                      (cli/dispatch "sync" ["inspect" "project.edn"]))
          parsed (read-json-output out)]
      (is (= "ygg.project.inspect/v1" (:schema parsed)))
      (is (= "fixture" (get-in parsed [:project :id])))
      (is (= [{:id "app"
               :root "/tmp/app"
               :role "application"}]
             (:repos parsed)))
      (is (= "ygg.evidence/v2" (get-in parsed [:evidence :schema])))
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
              :missingQueryIndex true
              :counts {:indexed 2
                       :current 3
                       :changed 1
                       :missing 0
                       :unindexed 1}
              :repos [{:repo-id "app"
                       :status "stale"
                       :counts {:indexed 2
                                :current 3
                                :changed 1
                                :missing 0
                                :unindexed 1}
                       :git-state {:git-branch "main"
                                   :git-upstream "origin/main"
                                   :git-upstream-status "behind"
                                   :git-upstream-current? false
                                   :git-ahead 0
                                   :git-behind 4}}]}
             (:freshness parsed)))
      (is (= [{:kind "query"
               :command "ygg query \"where is this handled?\" --project fixture --json"}]
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
      (is (str/includes? plain-out "- missing-query-index true"))
      (is (str/includes? plain-out "- changed 1"))
      (is (str/includes? plain-out "- unindexed 1"))
      (is (str/includes? plain-out "## Repo Freshness"))
      (is (str/includes? plain-out
                         "- app stale indexed=2 current=3 changed=1 missing=0 unindexed=1 branch=main upstream=origin/main upstream-status=behind upstream-current=false ahead=0 behind=4"))
      (let [status-out (with-out-str
                         (cli/dispatch "status" ["project.edn" "--json"]))
            status-parsed (read-json-output status-out)]
        (is (= "ygg.project.inspect/v1" (:schema status-parsed)))
        (is (= "fixture" (get-in status-parsed [:project :id])))
        (is (= ["source-graph" "docs"]
               (get-in status-parsed [:evidence :available])))
        (is (= (:coverage parsed) (:coverage status-parsed)))
        (is (= (:freshness parsed) (:freshness status-parsed)))
        (is (= (:nextActions parsed) (:nextActions status-parsed)))))))
(deftest sync-inspect-resolves-explicit-repo-project-ref
  (let [root (temp-dir "ygg-cli-sync-ref")
        checkout (io/file root "checkout")
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (.mkdirs checkout)
    (with-redefs [registry/registry-path (constantly registry-path)
                  store/with-node (fn [_ f] (f :xtdb))
                  evidence/summarize (fn [xtdb project opts]
                                       {:schema evidence/schema
                                        :xtdb xtdb
                                        :project-id (:id project)
                                        :config-path (:config-path opts)
                                        :available []
                                        :families []
                                        :counts {}
                                        :freshness {:status :fresh
                                                    :counts {}}
                                        :nextActions []})]
      (registry/upsert-project! {:id "fixture"
                                 :name "Fixture"
                                 :repos [{:id "app"
                                          :root (.getPath checkout)
                                          :role :application}]})
      (let [ref-path (registry/write-project-ref! (.getPath checkout) "fixture")
            out (with-out-str
                  (cli/dispatch "sync" ["inspect" ref-path "--json"]))
            parsed (read-json-output out)]
        (is (= "ygg.project.inspect/v1" (:schema parsed)))
        (is (= "fixture" (get-in parsed [:project :id])))
        (is (= ref-path (get-in parsed [:project :config-path])))
        (is (= ref-path (get-in parsed [:evidence :config-path])))))))
(deftest sync-check-resolves-repo-local-project-ref-from-cwd
  (let [root (temp-dir "ygg-cli-sync-cwd-ref")
        checkout (io/file root "checkout")
        nested (io/file checkout "src")
        registry-path (.getPath (io/file root ".config" "projects.edn"))
        previous-dir (System/getProperty "user.dir")
        calls (atom [])]
    (.mkdirs nested)
    (with-redefs [registry/registry-path (constantly registry-path)
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
      (registry/upsert-project! {:id "fixture"
                                 :name "Fixture"
                                 :repos [{:id "app"
                                          :root (.getPath checkout)
                                          :role :application}]})
      (registry/write-project-ref! (.getPath checkout) "fixture")
      (try
        (System/setProperty "user.dir" (.getPath nested))
        (let [out (with-out-str
                    (cli/dispatch "sync" ["--check" "--json"]))
              parsed (read-json-output out)]
          (is (= "ygg.sync/v1" (:schema parsed)))
          (is (= "fixture" (:project-id parsed)))
          (is (= [[:index :xtdb "fixture" {:dry-run? false
                                           :index-profile :graph
                                           :correction-overlay nil}]
                  [:infer :xtdb "fixture"]
                  [:check :xtdb "fixture" {:low-confidence-threshold 0.6
                                           :correction-overlay nil}]]
                 @calls)))
        (finally
          (System/setProperty "user.dir" previous-dir))))))
(deftest sync-activity-routes-through-project-config
  (let [config-path (canonical-path "project.edn")
        calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         project-fixture)
                  store/project-sqlite-path (constantly ".dev/test-project.sqlite")
                  store/with-node (fn [_ f] (f :xtdb))
                  activity/sync-queue! (fn [xtdb project opts]
                                         (swap! calls conj [:activity xtdb (:id project) opts])
                                         {:schema activity/sync-schema
                                          :project-id (:id project)
                                          :queue-db (:queue-db opts)
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
                                 "--json"]))
            plain-out (with-out-str
                        (cli/dispatch "sync"
                                      ["activity" "project.edn"]))
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
        (is (= [[:read config-path]
                [:activity :xtdb "fixture" {:queue-db ".dev/test-project.sqlite"}]
                [:read config-path]
                [:activity :xtdb "fixture" {:queue-db ".dev/test-project.sqlite"}]]
               @calls))))))
(deftest bench-run-dispatches-to-benchmark-runner
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/run-suite! (fn [suite opts]
                                         (swap! calls conj [:run suite opts])
                                         {:schema "ygg.benchmark.run/v1"
                                          :suite-id (:id suite)
                                          :cases []})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["run" "benchmark.edn"
                                 "--case" "case-1"
                                 "--out" ".dev/bench"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "ygg.benchmark.run/v1" (:schema parsed)))
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
  (let [root (temp-queue-db "ygg-cli-work-heartbeat")
        id (get-in (queue/enqueue! {:schema context/schema
                                    :project-id "fixture"}
                                   {:root root
                                    :kind "context"
                                    :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"
                             :lease-ms 1000})
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["work" "heartbeat" id
                                 "--project" "fixture"
                                 "--agent" "codex"
                                 "--lease-minutes" "5"]))
            parsed (read-json-output out)]
        (is (= queue/summary-schema (:schema parsed)))
        (is (= id (:id parsed)))
        (is (= "claimed" (:status parsed)))
        (is (= "codex" (get-in parsed [:lease :agent-id])))
        (is (integer? (get-in parsed [:lease :heartbeat-at-ms])))))))

(deftest sync-work-release-expired-releases-stale-claims
  (let [root (temp-queue-db "ygg-cli-work-release-expired")
        id (get-in (queue/enqueue! {:schema context/schema
                                    :project-id "fixture"}
                                   {:root root
                                    :kind "context"
                                    :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"
                             :lease-ms -1})
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["work" "release-expired"
                                 "--project" "fixture"]))
            parsed (read-json-output out)]
        (is (= "ygg.queue.release-expired/v1" (:schema parsed)))
        (is (= 1 (:released parsed)))
        (is (= "ready" (get-in (queue/find-item root id) [:item :status])))
        (is (= "lease expired"
               (get-in (queue/find-item root id) [:item :release-reason])))))))

(deftest sync-work-list-releases-expired-claims-before-listing
  (let [root (temp-queue-db "ygg-cli-work-list-release-expired")
        id (get-in (queue/enqueue! {:schema context/schema
                                    :project-id "fixture"}
                                   {:root root
                                    :kind "context"
                                    :project-id "fixture"})
                   [:item :id])]
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"
                             :lease-ms -1})
    (with-redefs [store/project-sqlite-path (constantly root)]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["work" "list"
                                 "--project" "fixture"
                                 "--status" "ready"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= queue/list-schema (:schema parsed)))
        (is (= [id] (mapv :id (:items parsed))))
        (is (= "ready" (get-in (queue/find-item root id) [:item :status])))))))

(deftest sync-work-validate-reports-valid-invalid-not-done-and-unsupported-results
  (let [root (temp-queue-db "ygg-cli-work-validate")
        dir (temp-dir "ygg-cli-work-validate-results")
        valid-result-path (.getPath (io/file dir "valid.json"))
        invalid-result-path (.getPath (io/file dir "invalid.json"))
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
                :expectedResultSchema dependency-review/result-schema
                :allowedActions ["add-package-import" "none"]}
        valid-id (get-in (queue/enqueue! packet {:root root
                                                 :kind dependency-review/work-kind
                                                 :project-id "fixture"
                                                 :priority 100})
                         [:item :id])
        invalid-id (get-in (queue/enqueue! (assoc packet
                                                  :reviewId "dependency-review:invalid")
                                           {:root root
                                            :kind dependency-review/work-kind
                                            :project-id "fixture"
                                            :priority 90})
                           [:item :id])
        ready-id (get-in (queue/enqueue! (assoc packet
                                                :reviewId "dependency-review:ready")
                                         {:root root
                                          :kind dependency-review/work-kind
                                          :project-id "fixture"
                                          :priority 10})
                         [:item :id])
        unsupported-id (get-in (queue/enqueue! {:schema "ygg.custom.work/v1"
                                                :expectedResultSchema "custom.result/v1"
                                                :project-id "fixture"}
                                               {:root root
                                                :kind "custom"
                                                :project-id "fixture"})
                               [:item :id])]
    (spit valid-result-path
          (json/write-json-str
           {:schema dependency-review/result-schema
            :reviewId "dependency-review:test"
            :recommendation "add-package-import"
            :confidence 0.86
            :reason "The import prefix is provided by the declared package."
            :correctionPatch [{:op "add-package-import"
                               :import "org.slf4j"
                               :ecosystem "maven"
                               :package "org.slf4j:slf4j-api"
                               :evidence [evidence-id]
                               :reason "Explicit package import mapping."}]}
           {:indent-str "  "}))
    (spit invalid-result-path
          (json/write-json-str
           {:schema dependency-review/result-schema
            :reviewId "dependency-review:invalid"
            :recommendation "maybe"
            :reason "Invalid recommendation."
            :correctionPatch []}
           {:indent-str "  "}))
    (with-redefs [store/project-sqlite-path (constantly root)]
      (queue/claim-next! root {:agent-id "codex"
                               :project-id "fixture"
                               :kind dependency-review/work-kind})
      (with-out-str
        (cli/dispatch "sync"
                      ["work" "complete" valid-id
                       "--project" "fixture"
                       "--result" valid-result-path]))
      (queue/claim-next! root {:agent-id "codex"
                               :project-id "fixture"
                               :kind dependency-review/work-kind})
      (with-out-str
        (cli/dispatch "sync"
                      ["work" "complete" invalid-id
                       "--project" "fixture"
                       "--result" invalid-result-path]))
      (queue/claim-next! root {:agent-id "codex"
                               :project-id "fixture"
                               :kind "custom"})
      (with-out-str
        (cli/dispatch "sync"
                      ["work" "complete" unsupported-id
                       "--project" "fixture"
                       "--result" valid-result-path]))
      (let [valid (read-json-output
                   (with-out-str
                     (cli/dispatch "sync"
                                   ["work" "validate" valid-id
                                    "--project" "fixture"])))
            invalid (read-json-output
                     (with-out-str
                       (cli/dispatch "sync"
                                     ["work" "validate" invalid-id
                                      "--project" "fixture"])))
            not-done (read-json-output
                      (with-out-str
                        (cli/dispatch "sync"
                                      ["work" "validate" ready-id
                                       "--project" "fixture"])))
            unsupported (read-json-output
                         (with-out-str
                           (cli/dispatch "sync"
                                         ["work" "validate" unsupported-id
                                          "--project" "fixture"])))]
        (is (= "ygg.sync.work.validation/v1" (:schema valid)))
        (is (= "valid" (:status valid)))
        (is (= dependency-review/packet-schema (:payload-schema valid)))
        (is (= dependency-review/result-schema (:result-schema valid)))
        (is (= dependency-review/result-schema (:expected-result-schema valid)))
        (is (= "invalid" (:status invalid)))
        (is (= [{:path ["result" "recommendation"]
                 :error "Result recommendation is required and must be supported."
                 :value "maybe"}]
               (:errors invalid)))
        (is (= "not-done" (:status not-done)))
        (is (= "unsupported" (:status unsupported)))
        (is (= [{:path ["payload" "schema"]
                 :error "No validation handler for work item payload schema."
                 :value "ygg.custom.work/v1"}]
               (:errors unsupported)))))))
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
                       :correctionPatch []}}]
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
                                 :correctionPatch []
                                 :findings ["Needs a human call."]}))))))

(deftest infra-review-batch-result-requires-one-result-per-review
  (let [first-packet {:schema infra-review/packet-schema
                      :reviewId "infra-review:first"
                      :project-id "fixture"
                      :facts {:systems []
                              :evidence []}
                      :allowedActions ["none"]
                      :expectedOutput {:schema infra-review/result-schema
                                       :reviewId "infra-review:first"}}
        second-packet {:schema infra-review/packet-schema
                       :reviewId "infra-review:second"
                       :project-id "fixture"
                       :facts {:systems []
                               :evidence []}
                       :allowedActions ["none"]
                       :expectedOutput {:schema infra-review/result-schema
                                        :reviewId "infra-review:second"}}
        packet (infra-review/batch-packet [first-packet second-packet])
        valid-result {:schema infra-review/batch-result-schema
                      :batchId (:batchId packet)
                      :results [{:schema infra-review/result-schema
                                 :reviewId "infra-review:first"
                                 :recommendation "no-change"
                                 :confidence 0.5
                                 :reason "Evidence is insufficient."
                                 :correctionPatch []}
                                {:schema infra-review/result-schema
                                 :reviewId "infra-review:second"
                                 :recommendation "needs-scanner"
                                 :confidence 0.5
                                 :reason "A scanner should provide more evidence."
                                 :correctionPatch []}]}
        missing-result (update valid-result :results subvec 0 1)]
    (is (empty? (infra-review/validate-result
                 {:status :done
                  :payload packet
                  :result valid-result})))
    (is (= [{:path [:result :results]
             :error "Batch result is missing infra review results."
             :value ["infra-review:second"]}]
           (infra-review/validate-result
            {:status :done
             :payload packet
             :result missing-result})))))

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
                          :correctionPatch [{:op "set-system-kind"
                                             :target target-id
                                             :value {:kind "application"}}]}}]
    (is (= [{:path [:result :recommendation]
             :error "Result recommendation is required and must be supported."
             :value "maybe"}
            {:path [:result :reason]
             :error "Result reason is required."}
            {:path [:correctionPatch 0 :reason]
             :error "Patch reason is required."}]
           (decision-classifier/validate-result invalid)))
    (is (empty? (decision-classifier/validate-result
                 (assoc invalid
                        :result {:schema decision-classifier/schema
                                 :decisionId "maintenance-decision:test"
                                 :recommendation "investigate"
                                 :confidence 0.5
                                 :reason "The packet does not contain enough bounded evidence."
                                 :correctionPatch []}))))))
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
                       :correctionPatch [{:op "add-edge"
                                          :value {:source target-id
                                                  :target "external-api:api.fixture.test"
                                                  :relation "references"}
                                          :reason "This patch was not advertised by the decision."}]}}]
    (is (= ["set-system-kind" "none"]
           (:allowedActions packet)))
    (is (= [{:path [:correctionPatch 0 :op]
             :error "Index maintenance correction patch op is not allowed for this decision."
             :value "add-edge"}]
           (decision-classifier/validate-result item)))
    (is (empty? (decision-classifier/validate-result
                 (assoc-in item
                           [:result :correctionPatch]
                           [{:op "set-system-kind"
                             :target target-id
                             :value {:kind "application"}
                             :reason "Accept the bounded system candidate."}]))))))

(deftest maintenance-decision-batch-result-requires-one-result-per-decision
  (let [first-target "system:fixture:app:first"
        second-target "system:fixture:app:second"
        packet (decision-classifier/decision-batch-packet
                [{:id "maintenance-decision:first"
                  :project-id "fixture"
                  :target first-target
                  :recommended-actions [:none]
                  :data {:system {:xt/id first-target
                                  :label "first"}}}
                 {:id "maintenance-decision:second"
                  :project-id "fixture"
                  :target second-target
                  :recommended-actions [:none]
                  :data {:system {:xt/id second-target
                                  :label "second"}}}])
        valid-result {:schema decision-classifier/batch-result-schema
                      :batchId (:batchId packet)
                      :results [{:schema decision-classifier/schema
                                 :decisionId "maintenance-decision:first"
                                 :recommendation "investigate"
                                 :confidence 0.5
                                 :reason "Evidence is insufficient."
                                 :correctionPatch []}
                                {:schema decision-classifier/schema
                                 :decisionId "maintenance-decision:second"
                                 :recommendation "investigate"
                                 :confidence 0.5
                                 :reason "Evidence is insufficient."
                                 :correctionPatch []}]}
        missing-result (update valid-result :results subvec 0 1)]
    (is (empty? (decision-classifier/validate-result
                 {:status :done
                  :payload packet
                  :result valid-result})))
    (is (= [{:path [:result :results]
             :error "Batch result is missing decision results."
             :value ["maintenance-decision:second"]}]
           (decision-classifier/validate-result
            {:status :done
             :payload packet
             :result missing-result})))))
(deftest sync-work-show-returns-summary-with-full-item
  (let [root (temp-queue-db "ygg-cli-work-show")
        id (get-in (queue/enqueue! {:schema "custom.packet/v1"
                                    :project-id "fixture"
                                    :expectedResultSchema "custom.result/v1"
                                    :value 1}
                                   {:root root
                                    :kind "custom"
                                    :priority 10})
                   [:item :id])
        out (with-redefs [store/project-sqlite-path (constantly root)]
              (with-out-str
                (cli/dispatch "sync"
                              ["work" "show" id
                               "--project" "fixture"])))
        parsed (read-json-output out)]
    (is (= queue/summary-schema (:schema parsed)))
    (is (= id (:id parsed)))
    (is (= "custom" (:kind parsed)))
    (is (= "custom.packet/v1" (:payload-schema parsed)))
    (is (= "custom.result/v1" (:expected-result-schema parsed)))
    (is (= "custom.packet/v1" (get-in parsed [:item :payload :schema])))))

(deftest sync-work-requires-central-project-queue
  (with-redefs [registry/resolve-project (constantly nil)]
    (try
      (with-out-str
        (cli/dispatch "sync" ["work" "list" "--json"]))
      (is false "expected missing project queue error")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Missing Yggdrasil project for central work queue."
               (ex-message e)))
        (is (= "--project" (:option (ex-data e))))))))

(deftest sync-work-pull-returns-summary-with-full-item
  (let [root (temp-queue-db "ygg-cli-work-pull")
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
        out (with-redefs [store/project-sqlite-path (constantly root)]
              (with-out-str
                (cli/dispatch "sync"
                              ["work" "pull"
                               "--project" "fixture"
                               "--kind" "infra-review"
                               "--agent" "codex"])))
        parsed (read-json-output out)]
    (is (= queue/summary-schema (:schema parsed)))
    (is (= id (:id parsed)))
    (is (= "claimed" (:status parsed)))
    (is (= "infra-review:test" (get-in parsed [:payload-summary :id])))
    (is (= "container-image:api" (get-in parsed [:payload-summary :artifact])))
    (is (= infra-review/packet-schema (get-in parsed [:item :payload :schema])))))
(deftest query-json-returns-context-packet
  (let [summaries (atom [])]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  project/read-project (fn [path]
                                         (assoc project-with-plugin-package :path path))
                  cli-query/embedding-options (constantly {:provider :local
                                                           :model "test"})
                  cli-query/query-embedding-client (constantly :embedding-client)
                  evidence/summarize (fn [xtdb project opts]
                                       (swap! summaries conj [xtdb project opts])
                                       {:freshness {:status :current
                                                    :counts {:indexed 3}}})
                  context/context-packet (fn [xtdb query-text opts]
                                           {:schema context/schema
                                            :xtdb xtdb
                                            :query query-text
                                            :project-id (:project-id opts)
                                            :output (:output opts)
                                            :proofCommands (:proof-commands? opts)
                                            :pluginPackages (get-in opts [:plugins :packages])
                                            :freshness (:freshness opts)})]
      (let [out (with-out-str
                  (cli/dispatch "query"
                                ["where" "auth"
                                 "--project" "fixture"
                                 "--config" "project.edn"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= context/schema (:schema parsed)))
        (is (= "where auth" (:query parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= "compact" (:output parsed)))
        (is (false? (:proofCommands parsed)))
        (is (= {:status "current"
                :counts {:indexed 3}}
               (:freshness parsed)))
        (is (= (json-roundtrip [plugin-package-fixture])
               (:pluginPackages parsed)))
        (is (= [[:xtdb
                 (assoc project-with-plugin-package :path "project.edn")
                 {:correction-overlay nil
                  :config-path "project.edn"
                  :summary? true}]]
               @summaries))))))

(deftest query-json-surfaces-semantic-availability-fallback
  (let [err (java.io.StringWriter.)
        semantic-status {:schema "ygg.semantic-availability/v1"
                         :requested :auto
                         :effective :lexical
                         :provider :openrouter
                         :model "deepseek/embedding"
                         :semanticAvailable false
                         :status :lexical-fallback
                         :reason :missing-provider-credentials
                         :message "Missing OpenRouter API key. Auto retrieval used lexical fallback."}]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  cli-query/embedding-options (constantly {:provider :openrouter
                                                           :model "deepseek/embedding"})
                  cli-query/semantic-availability (constantly semantic-status)
                  cli-query/query-embedding-client (constantly nil)
                  context/context-packet (fn [_ _ opts]
                                           {:schema context/schema
                                            :semanticStatus (:semantic-status opts)})]
      (let [out (with-out-str
                  (binding [*err* err]
                    (cli/dispatch "query"
                                  ["where" "auth"
                                   "--project" "fixture"
                                   "--json"])))
            parsed (read-json-output out)]
        (is (str/includes? (str err)
                           "Missing OpenRouter API key. Auto retrieval used lexical fallback."))
        (is (= (json-roundtrip semantic-status)
               (:semanticStatus parsed)))))))

(deftest query-embedding-options-use-repo-project-config
  (let [root (temp-dir "ygg-cli-query-embeddings")
        checkout (io/file root "checkout")
        registry-path (.getPath (io/file root ".config" "projects.edn"))
        previous-dir (System/getProperty "user.dir")]
    (.mkdirs checkout)
    (with-redefs [registry/registry-path (constantly registry-path)]
      (registry/upsert-project! {:id "fixture"
                                 :name "Fixture"
                                 :repos [{:id "app"
                                          :root (.getPath checkout)
                                          :role :application}]
                                 :embeddings {:provider :openrouter
                                              :model "openai/text-embedding-3-small"
                                              :request-timeout-ms 30000
                                              :max-retries 1}})
      (registry/write-project-ref! (.getPath checkout) "fixture")
      (try
        (System/setProperty "user.dir" (.getPath checkout))
        (is (= {:provider :openrouter
                :model "openai/text-embedding-3-small"
                :request-timeout-ms 30000
                :max-retries 1}
               (cli-query/embedding-options [])))
        (finally
          (System/setProperty "user.dir" previous-dir))))))

(deftest query-json-passes-output-and-proof-command-options
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                context/context-packet (fn [_ _ opts]
                                         {:schema context/compact-schema
                                          :output (:output opts)
                                          :proofCommands (:proof-commands? opts)
                                          :queryInput (:query-input opts)})]
    (let [out (with-out-str
                (cli/dispatch "query"
                              ["where" "auth"
                               "--project" "fixture"
                               "--json"
                               "--output" "full"
                               "--proof-commands"
                               "--task" "impact"
                               "--anchor" "src/a.clj:12"
                               "--symbol" "Auth"
                               "--literal" "AUTH_URL"
                               "--lanes" "grep,graph"
                               "--since" "HEAD~1"
                               "--changed-only"]))
          parsed (read-json-output out)]
      (is (= context/compact-schema (:schema parsed)))
      (is (= "full" (:output parsed)))
      (is (true? (:proofCommands parsed)))
      (is (= {:task "impact"
              :anchors ["src/a.clj:12"]
              :symbols ["Auth"]
              :literals ["AUTH_URL"]
              :changed-only? true
              :lanes ["grep" "graph"]
              :since "HEAD~1"}
             (:queryInput parsed))))))

(deftest query-plain-empty-result-prints-evidence-warning
  (let [err (java.io.StringWriter.)
        calls (atom [])]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  query/semantic-query (fn [xtdb query-text opts]
                                         (swap! calls conj [:query xtdb query-text opts])
                                         [])
                  context/context-packet (fn [xtdb query-text opts]
                                           (swap! calls conj [:context xtdb query-text opts])
                                           {:schema context/schema
                                            :evidence
                                            {:status :empty
                                             :missing [:docs :embeddings]
                                             :weak []
                                             :unsupported [:remote-work]
                                             :warnings ["No search docs are indexed."
                                                        "No activity/work rows are indexed."]
                                             :nextActions [{:kind :docs
                                                            :command "ygg sync <project.edn> --query-index"}]}})]
      (let [out (with-out-str
                  (binding [*err* err]
                    (cli/dispatch "query"
                                  ["prior" "work"
                                   "--project" "fixture"
                                   "--retriever" "lexical"])))]
        (is (= "" out))
        (is (str/includes? (str err) "No query results."))
        (is (str/includes? (str err) "Evidence empty"))
        (is (str/includes? (str err) "Missing evidence: docs, embeddings"))
        (is (str/includes? (str err) "Unsupported evidence: remote-work"))
        (is (str/includes? (str err) "Run ygg sync <project.edn> --query-index"))
        (is (= [:query :context] (mapv first @calls)))
        (is (= "prior work" (nth (second @calls) 2)))
        (is (= :full (:output (nth (second @calls) 3))))))))
(deftest query-plain-empty-result-shows-actionable-next-steps
  (let [err (java.io.StringWriter.)]
    (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                  query/semantic-query (fn [& _] [])
                  context/context-packet (fn [& _]
                                           {:schema context/schema
                                            :evidence
                                            {:status :limited
                                             :missing []
                                             :weak [:dependencies]
                                             :unsupported []
                                             :warnings ["No search docs are indexed."
                                                        "Dependency graph has unresolved imports."
                                                        "Package version conflicts are present."
                                                        "No embeddings are indexed."]
                                             :nextActions [{:kind :dependencies
                                                            :command "ygg packages --project fixture --json"}
                                                           {:kind :dependencies
                                                            :command "ygg packages --project fixture --without-import-evidence --json"}
                                                           {:kind :dependencies
                                                            :command "ygg packages --project fixture --with-conflicts --json"}
                                                           {:kind :dependency-review
                                                            :command "ygg sync <project.edn> --check --enqueue"}
                                                           {:kind :coverage
                                                            :command "ygg sync coverage <project.edn> --json"}]}})]
      (with-out-str
        (binding [*err* err]
          (cli/dispatch "query" ["deps" "--project" "fixture" "--retriever" "lexical"])))
      (is (str/includes? (str err) "Weak evidence: dependencies"))
      (is (str/includes? (str err) "Run ygg sync <project.edn> --check --enqueue"))
      (is (str/includes? (str err) "1 more warnings in --json output."))
      (is (str/includes? (str err) "1 more commands in --json output.")))))
(deftest query-plain-success-does-not-build-context-packet
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
                  (cli/dispatch "query"
                                ["auth"
                                 "--project" "fixture"
                                 "--retriever" "lexical"])))]
      (is (str/includes? out "Auth"))
      (is (str/includes? out "lexical match"))
      (is (= "" (str err))))))

(deftest query-plain-success-warns-when-indexing-is-active
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
                (binding [*err* err
                          cli-query/*deps* {:usage (fn [] "")
                                            :print-json (fn [_])
                                            :temporal-options (fn [_] {})
                                            :context-packet-options
                                            (fn [_ _ opts] opts)
                                            :active-indexing
                                            {:schema
                                             "ygg.server.active-operation/v1"
                                             :op "sync"
                                             :projectId "fixture"
                                             :lockKey "project:fixture"}}]
                  (cli-query/query!
                   ["auth"
                    "--project" "fixture"
                    "--retriever" "lexical"])))]
      (is (str/includes? out "Auth"))
      (is (str/includes? out "lexical match"))
      (is (str/includes? (str err) context/indexing-degradation-message)))))
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
        (cli/dispatch "view" ["overview" "--project" "fixture" "--format" "json" "--out" "graph.json"]))
      (is (= "graph.json" (:path @written)))
      (is (= graph/schema (get-in @written [:data :schema]))))))
