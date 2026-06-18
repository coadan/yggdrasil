(ns agraph.cli-test
  (:require [agraph.agent-install :as agent-install]
            [agraph.activity :as activity]
            [agraph.benchmark :as benchmark]
            [agraph.cli :as cli]
            [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.cursor :as cursor]
            [agraph.graph :as graph]
            [agraph.hook :as hook]
            [agraph.init :as init]
            [agraph.infra-review :as infra-review]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.query :as query]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.report :as report]
            [agraph.watch :as watch]
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

(deftest usage-shows-canonical-surface
  (let [usage (cli/usage)]
    (is (str/includes? usage "Setup:"))
    (is (str/includes? usage "Sync and maintenance:"))
    (is (str/includes? usage "Ask and explore:"))
    (is (str/includes? usage "View and report:"))
    (is (str/includes? usage "Agent integration:"))
    (is (str/includes? usage "Server integration:"))
    (is (str/includes? usage "start <repo-root>"))
    (is (str/includes? usage "sync <project.edn>"))
    (is (str/includes? usage "init <repo-root>"))
    (is (str/includes? usage "ask <text>"))
    (is (str/includes? usage "explore create"))
    (is (str/includes? usage "view overview|deps|query|systems"))
    (is (str/includes? usage "report <project.edn>"))
    (is (str/includes? usage "install-agent --platform codex --project"))
    (is (str/includes? usage "mcp [--root DIR]"))
    (is (str/includes? usage "watch <project.edn>"))
    (is (str/includes? usage "hook install <project.edn>"))
    (is (str/includes? usage "bench prepare|run|report|show"))
    (is (str/includes? usage "bench agent-packet"))
    (is (str/includes? usage "bench agent-baseline"))
    (is (str/includes? usage "bench agent-run"))
    (is (str/includes? usage "bench agent-score"))
    (is (str/includes? usage "bench agent-report"))
    (is (str/includes? usage "bench agent-check"))
    (is (not (str/includes? usage "overlay")))))

(deftest install-agent-list-shows-supported-platforms
  (let [out (with-out-str
              (cli/dispatch "install-agent" ["list"]))
        parsed (read-json-output out)]
    (is (= agent-install/schema (:schema parsed)))
    (is (= [{:id "codex"
             :scopes ["project"]
             :hooks? true}]
           (:platforms parsed)))))

(deftest install-agent-command-writes-codex-project-guidance
  (let [root (temp-dir "agraph-agent-install-cli")
        old-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" root)
      (let [out (with-out-str
                  (cli/dispatch "install-agent" ["--platform" "codex" "--project"]))
            parsed (read-json-output out)
            agents (io/file root "AGENTS.md")]
        (is (= agent-install/schema (:schema parsed)))
        (is (= "install" (:action parsed)))
        (is (= (.getPath agents) (:instructions parsed)))
        (is (str/includes? (slurp agents) "AGraph Agent Workflow")))
      (finally
        (System/setProperty "user.dir" old-dir)))))

(deftest agent-install-codex-project-guidance-is-idempotent
  (let [root (temp-dir "agraph-agent-install")
        agents (io/file root "AGENTS.md")]
    (spit agents "# Existing\n\nKeep this line.\n")
    (agent-install/install! "codex" {:root root
                                     :project? true})
    (let [first-content (slurp agents)]
      (agent-install/install! "codex" {:root root
                                       :project? true})
      (is (= first-content (slurp agents)))
      (is (str/includes? first-content "Keep this line."))
      (is (str/includes? first-content "agraph ask \"<question>\" --project <project-id> --json"))
      (is (str/includes? first-content "Do not infer architecture from names")))))

(deftest agent-install-codex-project-hooks-are-valid-json
  (let [root (temp-dir "agraph-agent-install-hooks")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true})
        hooks-file (io/file root ".codex" "hooks.json")
        parsed (json/read-json (slurp hooks-file) :key-fn keyword)]
    (is (= (.getPath hooks-file) (:hooks result)))
    (is (= "Bash" (get-in parsed [:hooks 0 :matcher])))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "agraph ask"))))

(deftest agent-install-does-not-overwrite-different-hook-without-force
  (let [root (temp-dir "agraph-agent-install-hook-conflict")
        hooks-file (io/file root ".codex" "hooks.json")]
    (.mkdirs (.getParentFile hooks-file))
    (spit hooks-file "{\"hooks\":[]}\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Hook file already exists"
                          (agent-install/install! "codex" {:root root
                                                           :project? true
                                                           :hooks? true})))
    (agent-install/install! "codex" {:root root
                                     :project? true
                                     :hooks? true
                                     :force? true})
    (is (str/includes? (slurp hooks-file) "AGraph may have relevant project context"))))

(deftest agent-uninstall-removes-only-owned-section
  (let [root (temp-dir "agraph-agent-uninstall")
        agents (io/file root "AGENTS.md")]
    (spit agents "# Existing\n\nKeep this line.\n")
    (agent-install/install! "codex" {:root root
                                     :project? true})
    (let [result (agent-install/uninstall! "codex" {:root root
                                                    :project? true})
          content (slurp agents)]
      (is (= true (get-in result [:removed :instructions])))
      (is (str/includes? content "Keep this line."))
      (is (not (str/includes? content "AGRAPH AGENT-INSTALL"))))))

(deftest hook-status-routes-through-project-config
  (with-redefs [project/read-project (constantly project-fixture)
                hook/status (fn [project]
                              {:schema hook/schema
                               :project-id (:id project)
                               :action "status"
                               :repos []})]
    (let [out (with-out-str
                (cli/dispatch "hook" ["status" "project.edn"]))
          parsed (read-json-output out)]
      (is (= hook/schema (:schema parsed)))
      (is (= "fixture" (:project-id parsed))))))

(deftest watch-command-routes-through-project-config
  (let [called (atom nil)]
    (with-redefs [project/read-project (constantly project-fixture)
                  watch/watch! (fn [project opts]
                                 (reset! called {:project project
                                                 :opts opts}))]
      (with-out-str
        (cli/dispatch "watch" ["project.edn"
                               "--map" "agraph.map.json"
                               "--query-index"
                               "--debounce-ms" "25"]))
      (is (= "fixture" (get-in @called [:project :id])))
      (is (= {:config-path "project.edn"
              :map-path "agraph.map.json"
              :query-index? true
              :debounce-ms 25}
             (:opts @called))))))

(deftest report-command-routes-through-project-config
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:read path])
                                         (assoc project-fixture :path path))
                  store/with-node (fn [_ f] (f :xtdb))
                  report/bundle! (fn [xtdb project opts]
                                   (swap! calls conj [:report xtdb (:id project) opts])
                                   {:schema report/schema
                                    :project-id (:id project)
                                    :out "/tmp/agraph-out"
                                    :files {:index "/tmp/agraph-out/index.html"}})]
      (let [out (with-out-str
                  (cli/dispatch "report" ["project.edn"
                                          "--map" "agraph.map.json"
                                          "--out" "bundle"
                                          "--detail" "expanded"
                                          "--force"]))
            parsed (read-json-output out)]
        (is (= report/schema (:schema parsed)))
        (is (= [[:read "project.edn"]
                [:report :xtdb "fixture" {:out "bundle"
                                          :map-path "agraph.map.json"
                                          :detail :expanded
                                          :force? true}]]
               @calls))))))

(deftest bench-agent-packet-can-enqueue-provider-neutral-work
  (let [root (temp-dir "agraph-cli-agent-bench-queue")]
    (with-redefs [benchmark/read-suite (constantly {:id "suite"})
                  benchmark/agent-packets! (fn [suite opts]
                                             {:schema "agraph.benchmark.agent-packets/v1"
                                              :suite-id (:id suite)
                                              :opts opts
                                              :packets [{:schema benchmark/agent-packet-schema
                                                         :suite-id (:id suite)
                                                         :case-id "case-1"
                                                         :repo-id "repo"
                                                         :project-id "suite-case-1"
                                                         :mode "agraph"
                                                         :artifacts {:packetPath "/tmp/packet.json"}}]})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-packet" "benchmark.edn"
                                 "--case" "case-1"
                                 "--mode" "agraph"
                                 "--enqueue"
                                 "--queue-dir" root
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "agraph.benchmark.agent-packets/v1" (:schema parsed)))
        (is (= {:case-id "case-1"
                :out nil
                :retriever nil
                :mode "agraph"
                :result-path nil
                :command nil}
               (:opts parsed)))
        (is (= ["benchmark-agent"] (mapv :kind (:enqueued parsed))))
        (is (= [benchmark/agent-packet-schema]
               (mapv :payload-schema (:enqueued parsed))))))))

(deftest bench-agent-score-dispatches-to-benchmark-scorer
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/selected-cases (fn [suite case-id]
                                             (swap! calls conj [:select (:id suite) case-id])
                                             [{:id case-id}])
                  benchmark/score-agent-result! (fn [suite case opts]
                                                  (swap! calls conj [:score suite case opts])
                                                  {:schema benchmark/agent-score-schema
                                                   :suite-id (:id suite)
                                                   :case-id (:id case)
                                                   :scores {:fileRecallAt10 1.0
                                                            :meanReciprocalRankFile 1.0}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-score" "benchmark.edn"
                                 "--case" "case-1"
                                 "--result" "agent-result.json"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-score-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:select "suite" "case-1"]
                [:score {:id "suite"}
                 {:id "case-1"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :mode nil
                  :result-path "agent-result.json"
                  :command nil}]]
               @calls))))))

(deftest bench-agent-baseline-dispatches-to-benchmark-runner
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/agent-baselines! (fn [suite opts]
                                               (swap! calls conj [:baseline suite opts])
                                               {:schema benchmark/agent-baselines-schema
                                                :suite-id (:id suite)
                                                :baselines [{:schema benchmark/agent-baseline-schema
                                                             :case-id "case-1"
                                                             :repo-id "repo"
                                                             :agentId "agraph-baseline-lexical"
                                                             :scores {:fileRecallAt10 1.0
                                                                      :meanReciprocalRankFile 1.0}}]})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-baseline" "benchmark.edn"
                                 "--case" "case-1"
                                 "--retriever" "lexical"
                                 "--limit" "3"
                                 "--doc-limit" "12"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-baselines-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:baseline {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever "lexical"
                  :mode nil
                  :result-path nil
                  :command nil
                  :limit 3
                  :doc-limit 12}]]
               @calls))))))

(deftest bench-agent-run-dispatches-to-benchmark-runner
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/agent-runs! (fn [suite opts]
                                          (swap! calls conj [:run-agent suite opts])
                                          {:schema benchmark/agent-runs-schema
                                           :suite-id (:id suite)
                                           :completed 1
                                           :failed 0
                                           :runs [{:schema benchmark/agent-run-schema
                                                   :case-id "case-1"
                                                   :repo-id "repo"
                                                   :agentId "codex"
                                                   :status "passed"
                                                   :scores {:fileRecallAt10 1.0
                                                            :meanReciprocalRankFile 1.0}}]})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-run" "benchmark.edn"
                                 "--case" "case-1"
                                 "--mode" "agraph"
                                 "--agent" "codex"
                                 "--command" "codex exec --json"
                                 "--prompt-profile" "fast"
                                 "--timeout-ms" "120000"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-runs-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:run-agent {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :mode "agraph"
                  :agent-id "codex"
                  :result-path nil
                  :command "codex exec --json"
                  :prompt-profile "fast"
                  :timeout-ms 120000}]]
               @calls))))))

(deftest bench-agent-report-dispatches-to-benchmark-reporter
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/report-agent-suite (fn [suite opts]
                                                 (swap! calls conj [:report suite opts])
                                                 {:schema benchmark/agent-report-schema
                                                  :suite-id (:id suite)
                                                  :cases 1
                                                  :completed 1
                                                  :runs 1
                                                  :scores {:fileRecallAt10 1.0
                                                           :meanReciprocalRankFile 1.0}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-report" "benchmark.edn"
                                 "--case" "case-1"
                                 "--mode" "agraph"
                                 "--agent" "codex"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-report-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:report {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :mode "agraph"
                  :agent-id "codex"
                  :result-path nil
                  :command nil}]]
               @calls))))))

(deftest bench-agent-check-dispatches-to-benchmark-checker
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/check-agent-suite (fn [suite opts]
                                                (swap! calls conj [:check suite opts])
                                                {:schema benchmark/agent-check-schema
                                                 :suite-id (:id suite)
                                                 :status "passed"
                                                 :failures []
                                                 :report {:scores {:fileRecallAt10 1.0
                                                                   :meanReciprocalRankFile 1.0}}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-check" "benchmark.edn"
                                 "--case" "case-1"
                                 "--mode" "agraph"
                                 "--agent" "agraph-baseline-lexical"
                                 "--min-cases" "4"
                                 "--min-runs" "4"
                                 "--min-file-recall-at-10" "1.0"
                                 "--min-mrr" "0.9"
                                 "--max-noise-at-20" "0.5"
                                 "--min-case-file-recall-at-10" "1.0"
                                 "--min-case-mrr" "0.9"
                                 "--max-case-noise-at-20" "0.75"
                                 "--max-input-hinted-cases" "0"
                                 "--max-unsupported-ground-truth-files" "0"
                                 "--allow-missing"
                                 "--allow-duplicate-runs"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-check-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:check {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :mode "agraph"
                  :agent-id "agraph-baseline-lexical"
                  :result-path nil
                  :command nil
                  :min-cases 4
                  :min-runs 4
                  :min-file-recall-at-10 1.0
                  :min-mrr 0.9
                  :max-noise-at-20 0.5
                  :min-case-file-recall-at-10 1.0
                  :min-case-mrr 0.9
                  :max-case-noise-at-20 0.75
                  :max-input-hinted-cases 0.0
                  :max-unsupported-ground-truth-files 0.0
                  :allow-missing? true
                  :allow-duplicate-runs? true}]]
               @calls))))))

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
                                                   :validation-events 0}})
                  report/bundle! (fn [xtdb project opts]
                                   (swap! calls conj [:report xtdb (:id project) opts])
                                   {:schema report/schema
                                    :project-id (:id project)
                                    :out (:out opts)
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
                :validation-events 0}
               (get-in parsed [:counts :activity])))
        (is (graph-map/file-exists? map-path))
        (is (some #(str/includes? % "agraph ask")
                  (:next parsed)))
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
                      :expectedResultSchema infra-review/result-schema}]
    (with-redefs [project/read-project (constantly project-fixture)
                  store/with-node (fn [_ f] (f :xtdb))
                  project/maintain-project (fn [_ _ _]
                                             {:project-id "fixture"
                                              :counts {:maintenance-decisions 1
                                                       :infra-review-items 1}
                                              :decision-queue [decision]
                                              :infra-review-queue [infra-packet]})]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["check" "project.edn"
                                 "--enqueue"
                                 "--json"
                                 "--queue-dir" root]))
            parsed (read-json-output out)
            items (queue/list-items root {:status "ready"})]
        (is (= "agraph.sync.check/v1" (:schema parsed)))
        (is (= #{"maintenance-decision" "infra-review"}
               (set (mapv #(get-in % [:item :kind]) items))))
        (is (= #{"maintenance-decision:test" nil}
               (set (mapv #(get-in % [:item :payload :decisionId]) items))))
        (is (= #{"infra-review:test" nil}
               (set (mapv #(get-in % [:item :payload :reviewId]) items))))))))

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
                                             :diagnostics {:total 0}
                                             :repos []})]
    (let [out (with-out-str
                (cli/dispatch "sync" ["coverage" "project.edn" "--json"]))
          parsed (read-json-output out)]
      (is (= coverage/schema (:schema parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= {:files 2 :supported 1 :skipped 1}
             (:totals parsed))))))

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
                                                   :validation-events 1}})]
      (let [out (with-out-str
                  (cli/dispatch "sync"
                                ["activity" "project.edn"
                                 "--queue-dir" ".dev/test-queue"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= activity/sync-schema (:schema parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= [[:read "project.edn"]
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
                  :mode nil
                  :result-path nil
                  :command nil}]]
               @calls))))))

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
                  :recommended-actions [:set-system-kind]
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

(deftest sync-work-show-returns-summary-with-full-item
  (let [root (temp-dir "agraph-cli-work-show")
        id (get-in (queue/enqueue! {:schema "custom.packet/v1"
                                    :project-id "fixture"
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
  (with-redefs [store/with-node (fn [_ f] (f :xtdb))
                context/context-packet (fn [xtdb query-text opts]
                                         {:schema context/schema
                                          :xtdb xtdb
                                          :query query-text
                                          :project-id (:project-id opts)})]
    (let [out (with-out-str
                (cli/dispatch "ask" ["where" "auth" "--project" "fixture" "--json"]))
          parsed (read-json-output out)]
      (is (= context/schema (:schema parsed)))
      (is (= "where auth" (:query parsed)))
      (is (= "fixture" (:project-id parsed))))))

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
