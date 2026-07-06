(ns ygg.benchmark-agent-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-baseline :as benchmark-agent-baseline]
            [ygg.benchmark-agent-run :as benchmark-agent-run]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-expectations :as benchmark-expectations]
            [ygg.benchmark-hints :as benchmark-hints]
            [ygg.benchmark-readiness :as benchmark-readiness]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-test-support :refer [commit! git! sh! spit-file! spit-json! temp-dir]]
            [ygg.context :as context]
            [ygg.embedding-client :as embedding-client]
            [ygg.extract :as extract]
            [ygg.project :as project]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest deterministic-baseline-emits-tighter-compact-surface
  (is (= 20 @#'benchmark-agent-baseline/default-agent-baseline-suspect-limit))
  (is (= 10 @#'benchmark-agent-baseline/default-agent-baseline-compact-result-limit))
  (is (< @#'benchmark-agent-baseline/default-agent-baseline-compact-result-limit
         @#'benchmark-agent-baseline/default-agent-baseline-suspect-limit)))

(defn- object-schema?
  [schema]
  (let [schema-type (:type schema)]
    (or (= "object" schema-type)
        (and (vector? schema-type)
             (some #{"object"} schema-type)))))

(defn- strict-schema-required-mismatches
  ([schema]
   (strict-schema-required-mismatches [] schema))
  ([path schema]
   (let [properties (:properties schema)
         required (set (map name (:required schema)))
         missing (when (and (object-schema? schema)
                            (= false (:additionalProperties schema))
                            (map? properties))
                   (->> (keys properties)
                        (remove #(contains? required (name %)))
                        (map name)
                        sort
                        vec))
         self (when (seq missing)
                [{:path path
                  :missing missing}])]
     (vec
      (concat
       self
       (mapcat (fn [[key child]]
                 (strict-schema-required-mismatches (conj path key) child))
               properties)
       (when-let [items (:items schema)]
         (strict-schema-required-mismatches (conj path :items) items)))))))

(deftest agent-run-timeout-kills-child-processes
  (let [started-at (System/currentTimeMillis)
        result (benchmark-agent-run/run-process!
                "sleep 10"
                "."
                {}
                100)
        elapsed (- (System/currentTimeMillis) started-at)]
    (is (:timedOut result))
    (is (= -1 (:exit result)))
    (is (< elapsed 3000))))

(deftest agent-run-closes-child-stdin
  (let [started-at (System/currentTimeMillis)
        result (benchmark-agent-run/run-process!
                "cat >/dev/null"
                "."
                {}
                3000)
        elapsed (- (System/currentTimeMillis) started-at)]
    (is (false? (:timedOut result)))
    (is (= 0 (:exit result)))
    (is (< elapsed 1000))))

(deftest writes-agent-packet-without-ground-truth
  (let [root (temp-dir "ygg-bench-agent-repo")
        out (temp-dir "ygg-bench-agent-out")
        suite-dir (temp-dir "ygg-bench-agent-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              packet (first (:packets (benchmark/agent-packets! suite {:out out
                                                                       :case-id "case-1"
                                                                       :parser-worker "java"})))
              project-path (get-in packet [:artifacts :projectConfig])
              packet-path (get-in packet [:artifacts :packetPath])
              project-config (read-string (slurp project-path))
              setup-command (get-in packet [:tools :ygg :setupCommand])]
          (is (= benchmark/agent-packet-schema (:schema packet)))
          (is (= benchmark/agent-result-schema
                 (get-in packet [:task :expectedResultSchema])))
          (is (str/starts-with? (:caseFingerprint packet) "sha256:"))
          (is (str/starts-with? (:agentInputFingerprint packet) "sha256:"))
          (is (not= (:caseFingerprint packet)
                    (:agentInputFingerprint packet)))
          (is (some #(str/includes? % "Only include files likely to require edits in suspectedFiles")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "do not demote those requested targets")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "coverageSourceKinds")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "runtime/config/setup file may need edits")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "exact repo-relative path")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "caseId, caseFingerprint, and agentInputFingerprint")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "Do not read YGG_BENCH_PACKET only")
                    (get-in packet [:task :rules])))
          (is (some #(str/includes? % "do not carry over stale graph-health text")
                    (get-in packet [:task :rules])))
          (is (= {:mode "none|java|dotnet|javascript|typescript|all"
                  :source "option|env|default|agent-result|unknown"}
                 (get-in packet [:task :resultContract :parserWorker])))
          (is (not (contains? packet :groundTruth)))
          (is (not (contains? packet :inputHints)))
          (is (= {:mode "java"
                  :source "option"}
                 (:parserWorker packet)))
          (is (= (:project-id packet) (:id project-config)))
          (is (= (:worktreeRoot packet) (get-in project-config [:repos 0 :root])))
          (is (str/includes? setup-command "cd "))
          (is (str/includes? setup-command "CLJ_CONFIG="))
          (is (str/includes? setup-command "YGG_PARSER_WORKER='java'"))
          (is (str/includes? setup-command ".cpcache"))
          (is (str/includes? setup-command "bb 'sync'"))
          (is (.isFile (io/file packet-path))))))))
(deftest agent-baseline-context-options-use-wide-candidate-pool-by-default
  (let [prepared {:project-id "project"
                  :repo-id "repo"}
        defaults (#'benchmark/agent-baseline-context-options prepared {})
        override (#'benchmark/agent-baseline-context-options
                  prepared
                  {:budget 12000
                   :retrieval-limit 42})]
    (is (= 24000 (:budget defaults)))
    (is (= 12000 (:budget override)))
    (is (= :auto (:retriever defaults)))
    (is (= 300 (:retrieval-limit defaults)))
    (is (= 42 (:retrieval-limit override)))))

(deftest agent-baseline-embedding-options-use-context-sized-inputs
  (let [prepared {:project-id "project"
                  :repo-id "repo"
                  :repos [{:id "repo"}]}
        defaults (#'benchmark-agent-baseline/agent-baseline-embedding-options
                  prepared
                  {})
        override (#'benchmark-agent-baseline/agent-baseline-embedding-options
                  prepared
                  {:embedding-input-max-chars 321
                   :batch-size 9})]
    (is (= context/default-snippet-chars (:input-max-chars defaults)))
    (is (= 64 (:batch-size defaults)))
    (is (= 321 (:input-max-chars override)))
    (is (= 9 (:batch-size override)))))

(deftest agent-baseline-context-cache-key-includes-implementation-fingerprint
  (let [prepared {:project-id "project"
                  :repo-id "repo"
                  :repos [{:id "repo"}]}
        key (#'benchmark-agent-baseline/baseline-context-cache-key
             prepared
             {}
             nil)]
    (is (str/starts-with? (:contextImplementationFingerprint key)
                          "sha256:"))))

(deftest provider-embedding-targets-use-bounded-lexical-prepass
  (let [calls (atom [])]
    (with-redefs [query/search-report
                  (fn [xtdb query-text opts]
                    (swap! calls conj [xtdb query-text opts])
                    {:retriever-effective :lexical
                     :instrumentation {:search-docs 50
                                       :durable-search-docs 50
                                       :lexical-positive 12
                                       :grep-positive 2
                                       :fts-positive 3
                                       :candidate-count 5
                                       :returned-count 4
                                       :search-total-ms 7}
                     :results [{:target-id "target:a"}
                               {:target-id "target:b"}
                               {:target-id "target:a"}
                               {:target-id "target:c"}]})]
      (let [targets (#'benchmark-agent-baseline/provider-embedding-targets
                     :xtdb
                     {:project-id "project"
                      :repo-id "repo"
                      :repos [{:id "repo"}]
                      :input {:queryText "where is the routing contract"}}
                     {:embedding-provider-limit 2
                      :fusion-strategy :rrf
                      :sqlite-fts? true})]
        (is (= ["target:a" "target:b"] (:target-ids targets)))
        (is (= 2 (:count targets)))
        (is (= 2 (:limit targets)))
        (is (= :lexical (get-in targets [:search :retriever-effective])))
        (is (= [[:xtdb
                 "where is the routing contract"
                 {:limit 2
                  :retriever :lexical
                  :project-id "project"
                  :read-context nil
                  :fusion-strategy :rrf
                  :sqlite-fts? true
                  :diversity-rerank-limit nil
                  :fts-candidate-limit nil
                  :fts-weight nil
                  :repo-id "repo"}]]
               @calls))))))

(deftest agent-baseline-resolves-semantic-client-from-provider-options
  (let [calls (atom [])]
    (with-redefs [embedding-client/configured-query-client
                  (fn [retriever opts]
                    (swap! calls conj [retriever opts])
                    {:provider :fake
                     :model "fake-model"
                     :embed-batch (fn [_] [])})]
      (is (= :fake
             (:provider (#'benchmark-agent-baseline/agent-baseline-embedding-client
                         {:retriever "hybrid"
                          :provider "local"
                          :model "fake-model"}))))
      (is (= :fake
             (:provider (#'benchmark-agent-baseline/agent-baseline-embedding-client
                         {:provider "openai"
                          :model "fake-default-model"}))))
      (is (= [[:hybrid {:provider "local"
                        :model "fake-model"
                        :request-timeout-ms 30000
                        :max-retries 1}]
              [:auto {:provider "openai"
                      :model "fake-default-model"
                      :request-timeout-ms 30000
                      :max-retries 1}]]
             @calls)))))

(deftest agent-baselines-share-embedding-cache-across-ygg-cases
  (let [caches (atom [])]
    (with-redefs [benchmark-agent-baseline/agent-baseline!
                  (fn [_suite case opts]
                    (swap! caches conj (:embedding-cache opts))
                    {:schema benchmark/agent-baseline-schema
                     :suite-id "suite"
                     :case-id (:id case)
                     :repo-id (:repo-id case)
                     :project-id (:id case)
                     :agentId "ygg-baseline-auto"
                     :mode "ygg"
                     :retriever "auto"
                     :scores {}})]
      (let [result (benchmark/agent-baselines!
                    {:id "suite"
                     :repos [{:id "repo"
                              :root "."}]
                     :cases [{:id "case-a"
                              :repo-id "repo"}
                             {:id "case-b"
                              :repo-id "repo"}]}
                    {:retriever "auto"})]
        (is (= 2 (:completed result)))
        (is (= 2 (count @caches)))
        (is (every? some? @caches))
        (is (= :sqlite (:type (first @caches))))
        (is (apply identical? @caches))))))
(deftest benchmark-index-options-are-bounded-by-default
  (is (= {:index-profile :query
          :extract-parallelism 4
          :index-timeout-ms 600000}
         (#'benchmark/benchmark-index-options {})))
  (is (= {:index-profile :query
          :extract-parallelism 4
          :index-timeout-ms 1234}
         (#'benchmark/benchmark-index-options {:index-timeout-ms 1234})))
  (is (= {:index-profile :query
          :extract-parallelism 4}
         (#'benchmark/benchmark-index-options {:index-timeout-ms 0})))
  (is (= {:index-profile :query
          :extract-parallelism 2
          :index-timeout-ms 600000}
         (#'benchmark/benchmark-index-options {:extract-parallelism 2}))))

(deftest agent-baseline-records-maintenance-activity-and-sync-inspect
  (let [root (temp-dir "ygg-bench-baseline-maintenance-repo")
        out (temp-dir "ygg-bench-baseline-maintenance-out")]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")
          suite {:id "suite"
                 :repos [{:id "repo"
                          :root root
                          :role :application}]
                 :cases [{:id "case-1"
                          :repo-id "repo"
                          :base-sha base-sha
                          :fix-sha base-sha
                          :coverage {:source-kinds [:code]}
                          :ground-truth {:localization-files ["src/app.clj"]}
                          :issue {:title "broken app"
                                  :body "Find the broken app function."}}]}
          result (benchmark/agent-baselines! suite {:out out
                                                    :now-ms 1000})
          baseline (first (:baselines result))
          skipped-result (benchmark/agent-baselines! suite {:out out
                                                            :now-ms 2000
                                                            :skip-existing? true})
          skipped-baseline (first (:baselines skipped-result))
          score (json/read-json (slurp (get-in baseline
                                               [:artifacts :agentScorePath]))
                                :key-fn keyword)
          context-packet (json/read-json
                          (slurp (get-in baseline
                                         [:artifacts :contextPacketPath]))
                          :key-fn keyword)
          token-usage (get-in score [:agent :tokenUsage])
          family-by-name (into {}
                               (map (juxt #(keyword (:family %)) identity))
                               (get-in baseline [:syncInspect :families]))
          stage-profile (:stageProfile baseline)
          stages (set (map :stage (:stageElapsedMs stage-profile)))]
      (is (= benchmark/agent-baselines-schema (:schema result)))
      (is (= benchmark/agent-baselines-schema (:schema skipped-result)))
      (is (= "ygg-baseline-auto" (:agentId baseline)))
      (is (= "auto" (:retriever baseline)))
      (is (= 1 (:skipped skipped-result)))
      (is (= "skipped" (:status skipped-baseline)))
      (is (= "current-score-artifact" (:skipReason skipped-baseline)))
      (is (= (:scores baseline) (:scores skipped-baseline)))
      (is (= true (:claimReady skipped-baseline)))
      (is (= "passed" (get-in skipped-baseline [:benchmarkPreflight :status])))
      (is (= "completed" (get-in skipped-baseline [:stageProfile :status])))
      (is (= 1 (get-in skipped-result [:timings :cases])))
      (is (pos? (get-in skipped-result [:timings :elapsedMs])))
      (is (= {:items 1
              :events 1
              :deleted-items 0
              :deleted-events 0}
             (:benchmarkActivity baseline)))
      (is (= "completed" (get-in score [:syncInspect :status])))
      (is (= :available (get-in family-by-name [:activity :status])))
      (is (= :available (get-in family-by-name [:validation-history :status])))
      (is (= :missing (get-in family-by-name [:corrections :status])))
      (is (= true (:claimReady score)))
      (is (= true (:claimReady baseline)))
      (is (= "ygg-baseline-compact-surface-estimate"
             (:source token-usage)))
      (is (= (:inputTokens token-usage) (:totalTokens token-usage)))
      (is (= 0 (:outputTokens token-usage)))
      (is (= 0.0 (:costUsd token-usage)))
      (is (< (:inputTokens token-usage)
             (context/estimate-tokens context-packet)))
      (is (= "sync-inspect"
             (get-in baseline
                     [:benchmarkPreflight :checks :syncCheck :source])))
      (is (= "completed" (:status stage-profile)))
      (is (= 1 (get-in result [:timings :cases])))
      (is (pos? (get-in result [:timings :elapsedMs])))
      (is (pos? (:elapsedMs stage-profile)))
      (is (pos? (:graphSetupElapsedMs stage-profile)))
      (is (seq (:stageClassElapsedMs stage-profile)))
      (is (contains? stages "index-project"))
      (is (contains? stages "embed-search-docs"))
      (is (contains? stages "context-packet"))
      (is (contains? stages "score-agent-result"))
      (is (contains? stages "write-agent-artifacts")))))

(deftest agent-baseline-reuses-context-manifest-when-score-is-missing-or-reuse-requested
  (let [out (temp-dir "ygg-bench-baseline-context-reuse")
        worktree (temp-dir "ygg-bench-baseline-context-reuse-worktree")
        suite {:id "suite"
               :project-id "fixture"
               :cases [{:id "case-1"}]}
        case (first (:cases suite))
        prepared {:schema benchmark-prepare/prepared-case-schema
                  :suite-id "suite"
                  :case-id "case-1"
                  :caseFingerprint "sha256:case"
                  :agentInputFingerprint "sha256:agent-input"
                  :repo-id "repo"
                  :repoIds ["repo"]
                  :repos [{:id "repo"
                           :root worktree
                           :role :application}]
                  :project-id "project"
                  :worktreeRoot worktree
                  :worktreeRoots {"repo" worktree}
                  :input {:queryText "broken app"}
                  :coverage {:declaredSourceKinds []}
                  :groundTruth {:changedFiles ["src/app.clj"]}}
        opts {:out out
              :case-id "case-1"
              :retriever "lexical"
              :now-ms 1000}
        store-count (atom 0)
        index-count (atom 0)]
    (spit-file! worktree "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (with-redefs [benchmark-prepare/prepare-case! (fn [& _] prepared)
                  benchmark-agent-packet/agent-project (fn [_]
                                                         {:id "project"
                                                          :repos [{:id "repo"
                                                                   :root worktree
                                                                   :role :application}]})
                  store/with-node (fn [_ f]
                                    (swap! store-count inc)
                                    (f {:indexed? (atom false)}))
                  benchmark-readiness/prepare-agent-overlay! (fn [& _] {})
                  benchmark-readiness/record-benchmark-agent-activity!
                  (fn [& _]
                    {:activity {:items 1
                                :events 1
                                :deleted-items 0
                                :deleted-events 0}
                     :syncInspect {:status "completed"}})
                  benchmark-expectations/evaluate-graph-expectations
                  (fn [& _] {:status "passed"})
                  project/index-project! (fn [xtdb _project _opts]
                                           (swap! index-count inc)
                                           (reset! (:indexed? xtdb) true)
                                           {:status "completed"
                                            :files 1})
                  project/infer-project! (fn [& _]
                                           {:status "completed"
                                            :systems 0
                                            :candidates 0
                                            :edges 0})
                  context/context-packet (fn [xtdb query-text _opts]
                                           {:schema "ygg.context/v1"
                                            :query query-text
                                            :docs []
                                            :entities []
                                            :edges []
                                            :warnings []
                                            :search {:instrumentation
                                                     {:indexed @(:indexed? xtdb)}}
                                            :candidateFiles [{:path "src/app.clj"
                                                              :rank 1
                                                              :score 1.0
                                                              :targetKind "chunk"
                                                              :label "app/broken"}]})
                  benchmark-hints/context-packet->agent-hints
                  (fn [_prepared packet _opts]
                    {:schema benchmark/agent-hints-schema
                     :diagnostics []
                     :topFiles (:candidateFiles packet)})]
      (let [first-result (benchmark/agent-baselines! suite opts)
            first-baseline (first (:baselines first-result))
            score-path (get-in first-baseline [:artifacts :agentScorePath])
            manifest-path (get-in first-baseline [:artifacts :contextManifestPath])
            _ (is (.delete (io/file score-path)))
            second-result (benchmark/agent-baselines! suite
                                                      (assoc opts
                                                             :skip-existing? true
                                                             :now-ms 2000))
            second-baseline (first (:baselines second-result))
            third-result (benchmark/agent-baselines! suite
                                                     (assoc opts
                                                            :reuse-context? true
                                                            :now-ms 3000))
            third-baseline (first (:baselines third-result))
            progress (json/read-json
                      (slurp (benchmark-paths/progress-path suite case opts))
                      :key-fn keyword)
            progress-stages (set (map :stage (:events progress)))
            manifest (json/read-json (slurp manifest-path) :key-fn keyword)]
        (is (= benchmark/agent-baselines-schema (:schema first-result)))
        (is (= benchmark/agent-baselines-schema (:schema second-result)))
        (is (= benchmark/agent-baselines-schema (:schema third-result)))
        (is (= 1 @store-count))
        (is (= 1 @index-count))
        (is (= 0 (:skipped second-result)))
        (is (= 0 (:skipped third-result)))
        (is (= "reused" (get-in second-baseline
                                [:ygg :contextReuse :status])))
        (is (= "reused" (get-in third-baseline
                                [:ygg :contextReuse :status])))
        (is (not= "skipped" (:status third-baseline)))
        (is (= manifest-path
               (get-in second-baseline
                       [:artifacts :reusedContextManifestPath])))
        (is (= manifest-path
               (get-in third-baseline
                       [:artifacts :reusedContextManifestPath])))
        (is (.isFile (io/file score-path)))
        (is (= benchmark-agent-baseline/agent-baseline-context-schema
               (:schema manifest)))
        (is (= "sha256:case" (:caseFingerprint manifest)))
        (is (contains? progress-stages "reuse-agent-baseline-context"))
        (is (not (contains? progress-stages "index-project")))
        (is (not (contains? progress-stages "context-packet")))
        (is (some #(and (= "reuse-agent-baseline-context" (:stage %))
                        (= "completed" (:status %)))
                  (:events progress)))))))

(deftest benchmark-parser-worker-profile-is-explicit-and-bindable
  (is (= {:mode "java"
          :source "option"}
         (#'benchmark/parser-worker-profile {:parser-worker "Java"})))
  (is (= {:mode "none"
          :source "default"}
         (#'benchmark/agent-score-parser-worker-profile
          {}
          {:parserWorker {:mode "none"
                          :source "unknown"}})))
  (extract/with-parser-worker-mode
    "dotnet"
    (is (= "java"
           (#'benchmark/with-benchmark-parser-worker
            {:parser-worker "java"}
            #(extract/parser-worker-mode))))))
(deftest skip-existing-agent-baselines-match-parser-worker-profile
  (let [out (temp-dir "ygg-bench-parser-worker-skip")
        suite {:id "suite"}
        case {:id "case-1"
              :repo-id "repo"
              :base-sha "base"
              :fix-sha "fix"
              :issue {:title "broken app"
                      :body "The app is broken."}}
        java-opts {:out out
                   :parser-worker "java"}
        dotnet-opts {:out out
                     :parser-worker "dotnet"}
        result-path (#'benchmark/agent-baseline-result-path suite case java-opts)
        score-path (#'benchmark/agent-score-path suite case java-opts result-path)
        score {:schema benchmark/agent-score-schema
               :agentResultContractVersion
               benchmark/agent-result-contract-version
               :case-id "case-1"
               :caseFingerprint (#'benchmark/case-fingerprint suite case)
               :agentInputFingerprint (#'benchmark/agent-input-fingerprint suite case)
               :agent {:agentId "ygg-baseline-auto"
                       :schema benchmark/agent-result-schema
                       :mode "ygg"
                       :agentInputFingerprint (#'benchmark/agent-input-fingerprint suite case)}
               :agentResultPath (.getCanonicalPath (io/file result-path))
               :parserWorker {:mode "dotnet"
                              :source "option"}
               :scores {:fileRecallAt10 1.0}}]
    (.mkdirs (.getParentFile score-path))
    (spit score-path (json/write-json-str score))
    (is (empty? (#'benchmark/current-agent-score-artifacts
                 suite
                 case
                 java-opts
                 {:agent-id "ygg-baseline-auto"
                  :mode "ygg"
                  :result-path result-path})))
    (is (= 1
           (count (#'benchmark/current-agent-score-artifacts
                   suite
                   case
                   dotnet-opts
                   {:agent-id "ygg-baseline-auto"
                    :mode "ygg"
                    :result-path result-path}))))))
(deftest agent-run-env-carries-explicit-parser-worker-profile
  (let [env (#'benchmark/agent-run-env
             {:suite-id "suite"
              :case-id "case-1"
              :repo-id "repo"
              :project-id "project"
              :mode "ygg"
              :worktreeRoot "/tmp/worktree"
              :artifacts {:packetPath "/tmp/packet.json"
                          :projectConfig "/tmp/project.edn"
                          :xtdbPath "/tmp/xtdb"}}
             "/tmp/result.json"
             "/tmp/prompt.txt"
             "/tmp/schema.json"
             {:agent-id "agent"
              :parser-worker "dotnet"})]
    (is (= "dotnet" (get env "YGG_BENCH_PARSER_WORKER")))
    (is (= "dotnet" (get env "YGG_PARSER_WORKER")))))
(deftest ygg-agent-run-prompt-points-agents-at-architecture-hints
  (let [root (temp-dir "ygg-bench-agent-prompt")
        result-path (.getPath (io/file root "result.json"))
        schema-path (.getPath (io/file root "schema.json"))
        hints-path (spit-json!
                    root
                    "hints.json"
                    {:preparedLocalization
                     {:candidates
                      [{:rank 1
                        :path "src/connector.clj"
                        :repoId "core"
                        :confidence 0.98
                        :declarations [{:label "connector/contract"
                                        :kind "clj-var"
                                        :sourceLine 42}]
                        :evidence ["prepared-declaration:src/connector.clj lines 42 label=\"connector/contract\""]}]}
                     :topFiles
                     [{:rank 1
                       :path "src/context.clj"
                       :repoId "core"
                       :confidence 0.91
                       :evidence ["context-doc:src/context.clj lines 10-20 provenance=retrieved-doc"]}]
                     :relatedFiles
                     [{:rank 1
                       :path "src/consumer.clj"
                       :relation "imports-package"
                       :repoId "core"
                       :via [{:seedPath "src/connector.clj"
                              :sourceLine 7}]
                       :evidence ["source-graph: src/connector.clj imports src/consumer line 7"]}
                      {:rank 2
                       :path "src/ignored-helper.clj"
                       :relation "same-directory"
                       :evidence ["source-graph: same directory"]}]})
        prompt (#'benchmark/agent-run-prompt
                {:suite-id "suite"
                 :case-id "case-1"
                 :caseFingerprint "sha256:case"
                 :agentInputFingerprint "sha256:input"
                 :repo-id "repo"
                 :project-id "project"
                 :mode "ygg"
                 :worktreeRoot "/tmp/worktree"
                 :input {:title "Trace connector contracts"
                         :body "Identify connector, consumer, and component contract files."}
                 :task {:objective "Find likely edit locations."}
                 :artifacts {:packetPath "/tmp/packet.json"
                             :projectConfig "/tmp/project.edn"
                             :xtdbPath "/tmp/xtdb"
                             :yggHintsPath hints-path
                             :yggContextPath "/tmp/context.json"}}
                result-path
                schema-path
                {:agent-id "agent"
                 :prompt-profile "fast"})]
    (is (str/includes? prompt
                       "Yggdrasil artifacts: use `YGG_BENCH_YGG_HINTS` and `YGG_BENCH_YGG_CONTEXT`"))
    (is (str/includes? prompt "## Issue"))
    (is (str/includes? prompt "Trace connector contracts"))
    (is (str/includes? prompt
                       "Identify connector, consumer, and component contract files."))
    (is (str/includes? prompt
                       "do not read `YGG_BENCH_PACKET` only to recover the issue"))
    (is (str/includes? prompt
                       "`YGG_BENCH_CASE_ID`, `YGG_BENCH_CASE_FINGERPRINT`, and `YGG_BENCH_AGENT_INPUT_FINGERPRINT`"))
    (is (str/includes? prompt
                       "Yggdrasil fast mode hard cap: use at most 3 local shell commands"))
    (is (not (str/includes? prompt
                            "Use at most 8 local shell commands")))
    (is (str/includes? prompt
                       "run only the listed minimal proof commands"))
    (is (str/includes? prompt
                       "do not run `rg` on file-only candidates without declaration labels"))
    (is (str/includes? prompt
                       "`suspectedSymbols` may be empty; include at most 3 symbols"))
    (is (str/includes? prompt
                       "at most 2 evidence strings per file"))
    (is (str/includes? prompt
                       "summary, reasons, and evidence to short single sentences"))
    (is (str/includes? prompt
                       "Do not emit provisional JSON before local inspection"))
    (is (str/includes? prompt
                       "primary package contract file as the suspectedFile"))
    (is (str/includes? prompt
                       "related graph files from import-package edges"))
    (is (str/includes? prompt
                       "Context-ranked files from compact Yggdrasil topFiles"))
    (is (str/includes? prompt "src/context.clj"))
    (is (str/includes? prompt "repo core"))
    (is (str/includes? prompt
                       "context-doc:src/context.clj lines 10-20"))
    (is (str/includes? prompt
                       "Do not read the same file twice in fast Yggdrasil mode"))
    (is (str/includes? prompt
                       "single-file documentation or wording tasks"))
    (is (str/includes? prompt
                       "zero-command answers"))
    (is (str/includes? prompt
                       "issue-text inference alone is not enough"))
    (is (str/includes? prompt
                       "Related graph files from prepared import edges"))
    (is (str/includes? prompt "src/consumer.clj"))
    (is (str/includes? prompt
                       "source-graph: src/connector.clj imports src/consumer line 7"))
    (is (not (str/includes? prompt "src/ignored-helper.clj")))
    (is (not (str/includes? prompt "Yggdrasil hints JSON: /tmp/hints.json")))
    (is (not (str/includes? prompt "Yggdrasil context JSON: /tmp/context.json")))
    (is (str/includes? prompt "Yggdrasil is prepared and warm"))
    (is (str/includes? prompt "## Prepared Yggdrasil Summary"))
    (is (str/includes? prompt "src/connector.clj"))
    (is (str/includes? prompt "connector/contract clj-var:42"))
    (is (str/includes? prompt "not an edit list"))
    (is (str/includes? prompt
                       "Do not run a first-step `jq` projection over `YGG_BENCH_YGG_HINTS`"))
    (is (str/includes? prompt
                       "Do not grep Yggdrasil hint/context artifacts for extra citations"))
    (is (not (str/includes? prompt
                            "`jq '{selection,topFiles:[((.topFiles//[])[:8])[]|{rank,path}]")))
    (is (not (str/includes? prompt
                            "preparedLocalization:{candidates:[((.preparedLocalization.candidates//[])[:12])[]")))
    (is (str/includes? prompt
                       "do not print entire Yggdrasil JSON artifacts"))
    (is (str/includes? prompt
                       "as the first ranked audit surface"))
    (is (str/includes? prompt
                       "avoid rediscovering them with `rg`"))
    (is (str/includes? prompt
                       "exact candidate paths"))
    (is (str/includes? prompt "Avoid broad `rg`"))
    (is (str/includes? prompt
                       "do not pass directories to `rg`"))
    (is (str/includes? prompt
                       "keep each `sed` window at 90 lines or fewer"))
    (is (str/includes? prompt
                       "Do not run directory-wide `rg` just to reconfirm prepared top-file"))
    (is (str/includes? prompt
                       "Do not invent extra `rg` commands for prepared candidates"))
    (is (str/includes? prompt "narrow `sed` windows"))
    (is (str/includes? prompt
                       "Use full hints or context only when the prepared summary and focused"))
    (is (str/includes? prompt
                       "against `YGG_BENCH_YGG_HINTS` or `YGG_BENCH_YGG_CONTEXT` just to add graph citations"))
    (is (str/includes? prompt "file reads can satisfy evidence"))
    (is (str/includes? prompt
                       "`sourceCoverage` and `diagnostics`"))
    (is (str/includes? prompt
                       "run listed commands"))
    (is (str/includes? prompt
                       "validation-gap next actions"))
    (is (str/includes? prompt
                       "use `YGG_BENCH_OUTPUT_SCHEMA` for fields"))
    (is (not (str/includes? prompt "\"tokenUsage\": null")))
    (is (str/includes? prompt "coverageSourceKinds"))
    (is (str/includes? prompt "runtime/config/setup file may need edits"))
    (is (str/includes? prompt "do not demote those requested targets"))
    (is (str/includes? prompt "exact repo-relative path"))
    (is (str/includes? prompt "exact evidence path and label"))
    (is (str/includes? prompt "caseId, caseFingerprint, and agentInputFingerprint"))
    (is (str/includes? prompt "do not run env, printenv, jq, or packet reads solely"))
    (is (str/includes? prompt "- Case fingerprint: sha256:case"))
    (is (str/includes? prompt "- Agent input fingerprint: sha256:input"))
    (is (str/includes? prompt "do not carry over stale graph-health text"))))

(deftest ygg-agent-proof-commands-collapse-declaration-greps
  (let [hints {:readPlan
               {:snippets [{:path "variables.tf"
                            :command "sed -n '1582,1614p' 'variables.tf'"}
                           {:path "vpc-flow-logs.tf"
                            :command "sed -n '14,46p' 'vpc-flow-logs.tf'"}]}}
        candidates [{:rank 1
                     :path "variables.tf"
                     :declarations [{:label "var.vpc_flow_log_iam_role_name"
                                     :kind "terraform-variable"}
                                    {:label "var.vpc_flow_log_iam_role_path"
                                     :kind "terraform-variable"}
                                    {:label "var.flow_log_destination_type"
                                     :kind "terraform-variable"}]}
                    {:rank 2
                     :path "vpc-flow-logs.tf"
                     :declarations [{:label "aws_iam_role.vpc_flow_log_cloudwatch"
                                     :kind "terraform-resource"}
                                    {:label "data.aws_iam_policy_document.flow_log_cloudwatch_assume_role"
                                     :kind "terraform-data-source"}]}
                    {:rank 8
                     :path "main.tf"
                     :declarations []}]]
    (is (= ["rg -n --fixed-strings -e 'variable \"vpc_flow_log_iam_role_name\"' -e 'variable \"vpc_flow_log_iam_role_path\"' -e 'variable \"flow_log_destination_type\"' -e 'resource \"aws_iam_role\" \"vpc_flow_log_cloudwatch\"' -e 'data \"aws_iam_policy_document\" \"flow_log_cloudwatch_assume_role\"' 'variables.tf' 'vpc-flow-logs.tf'"
            "sed -n '14,46p' 'vpc-flow-logs.tf'"
            "sed -n '1,80p' 'main.tf'"]
           (#'benchmark-agent-run/proof-commands hints candidates)))))

(deftest ygg-agent-proof-commands-keep-read-plan-for-non-terraform-declarations
  (let [hints {:readPlan
               {:snippets [{:path "src/connector.clj"
                            :command "sed -n '40,70p' 'src/connector.clj'"}]}}
        candidates [{:rank 1
                     :path "src/connector.clj"
                     :declarations [{:label "connector/contract"
                                     :kind "clj-var"}]}]]
    (is (= ["sed -n '40,70p' 'src/connector.clj'"]
           (#'benchmark-agent-run/proof-commands hints candidates)))))

(deftest ygg-agent-proof-commands-include-context-ranked-read-plan
  (let [hints {:readPlan
               {:snippets [{:path "src/connector.clj"
                            :command "sed -n '40,70p' 'src/connector.clj'"}
                           {:path "src/context.clj"
                            :command "sed -n '10,30p' 'src/context.clj'"}]}}
        candidates [{:rank 1
                     :path "src/connector.clj"
                     :declarations [{:label "connector/contract"
                                     :kind "clj-var"}]}]
        context-files [{:rank 1
                        :path "src/context.clj"}]]
    (is (= ["sed -n '40,70p' 'src/connector.clj'"
            "sed -n '10,30p' 'src/context.clj'"]
           (#'benchmark-agent-run/proof-commands hints candidates context-files)))))

(deftest ygg-agent-run-builds-context-artifacts-after-indexing
  (let [out (temp-dir "ygg-bench-agent-run-context-order")
        worktree (temp-dir "ygg-bench-agent-run-context-worktree")
        suite {:id "suite"}
        case {:id "case-1"}
        prepared {:case-id "case-1"
                  :project-id "project"
                  :repo-id "repo"
                  :worktreeRoot worktree
                  :input {:queryText "broken app"}
                  :coverage {:declaredSourceKinds []}}
        opts {:out out
              :agent-id "agent"
              :mode "ygg"}]
    (with-redefs [store/with-node (fn [_ f]
                                    (f {:indexed? (atom false)}))
                  benchmark-readiness/prepare-agent-overlay! (fn [& _] {})
                  benchmark-readiness/sync-inspect-summary (fn [& _]
                                                             {:status "completed"})
                  project/index-project! (fn [xtdb _project _opts]
                                           (reset! (:indexed? xtdb) true)
                                           {:status "completed"})
                  project/infer-project! (fn [_xtdb _project]
                                           {:status "completed"})
                  context/context-packet (fn [xtdb query-text _opts]
                                           (if @(:indexed? xtdb)
                                             {:schema "ygg.context/v1"
                                              :query query-text
                                              :docs []
                                              :entities []
                                              :edges []
                                              :warnings []
                                              :search {:instrumentation {:search-docs 1}}
                                              :candidateFiles [{:path "src/app.clj"
                                                                :rank 1
                                                                :score 1.0
                                                                :targetKind "chunk"
                                                                :label "app/broken"}]}
                                             {:schema "ygg.context/v1"
                                              :query query-text
                                              :docs []
                                              :entities []
                                              :edges []
                                              :warnings []
                                              :search {:instrumentation {:search-docs 0}}
                                              :candidateFiles []}))
                  benchmark/context-packet->agent-hints (fn [_prepared packet _opts]
                                                          {:schema benchmark/agent-hints-schema
                                                           :selection {:candidateFiles (count (:candidateFiles packet))}
                                                           :topFiles (:candidateFiles packet)})
                  query/nodes-by-paths (fn [& _] [])
                  query/nodes-by-labels (fn [& _] [])
                  query/edges-by-file-ids (fn [& _] [])
                  query/nodes-by-path-prefixes (fn [& _] [])]
      (let [result (#'benchmark/prepare-agent-graph-and-artifacts! suite
                                                                   case
                                                                   prepared
                                                                   opts)
            context-json (json/read-json
                          (slurp (get-in result [:artifacts :context-path]))
                          :key-fn keyword)
            hints-json (json/read-json
                        (slurp (get-in result [:artifacts :hints-path]))
                        :key-fn keyword)]
        (is (= {:status "completed"} (get-in result [:summary :indexSummary])))
        (is (= 1 (get-in context-json [:search :instrumentation :search-docs])))
        (is (= [{:path "src/app.clj"
                 :rank 1
                 :score 1.0
                 :targetKind "chunk"
                 :label "app/broken"}]
               (:candidateFiles context-json)))
        (is (= 1 (get-in hints-json [:selection :candidateFiles])))))))

(deftest graph-related-files-resolve-go-module-imports-to-package-files
  (let [prefix-calls (atom [])
        prepared {:project-id "project"
                  :repo-id "repo"}
        packet {:candidateFiles [{:path "connector/connector.go"
                                  :rank 1}]}
        seed-node {:xt/id "node:connector"
                   :kind :namespace
                   :project-id "project"
                   :repo-id "repo"
                   :file-id "file:connector"
                   :path "connector/connector.go"
                   :active? true}
        manifest-node {:xt/id "node:go-mod"
                       :kind :manifest
                       :project-id "project"
                       :repo-id "repo"
                       :path "go.mod"
                       :label "go.opentelemetry.io/collector"
                       :active? true}]
    (with-redefs [query/nodes-by-paths
                  (fn [_ paths _scope]
                    (cond
                      (= ["connector/connector.go"] (vec paths))
                      [seed-node]

                      :else
                      (filter #(contains? (set paths) (:path %))
                              [manifest-node])))
                  query/edges-by-file-ids
                  (fn [_ file-ids _scope]
                    (when (= ["file:connector"] (vec file-ids))
                      [{:xt/id "edge:consumer"
                        :project-id "project"
                        :repo-id "repo"
                        :file-id "file:connector"
                        :path "connector/connector.go"
                        :relation :imports
                        :target-id "node:namespace:go.opentelemetry.io/collector/consumer"
                        :source-line 11
                        :active? true}
                       {:xt/id "edge:component"
                        :project-id "project"
                        :repo-id "repo"
                        :file-id "file:connector"
                        :path "connector/connector.go"
                        :relation :imports
                        :target-id "node:namespace:go.opentelemetry.io/collector/component"
                        :source-line 12
                        :active? true}]))
                  query/nodes-by-labels (fn [& _] [])
                  query/nodes-by-path-prefixes
                  (fn [_ prefixes _scope]
                    (reset! prefix-calls (vec prefixes))
                    [{:xt/id "node:consumer-traces"
                      :kind :namespace
                      :project-id "project"
                      :repo-id "repo"
                      :file-id "file:consumer-traces"
                      :path "consumer/traces.go"
                      :active? true}
                     {:xt/id "node:component"
                      :kind :namespace
                      :project-id "project"
                      :repo-id "repo"
                      :file-id "file:component"
                      :path "component/component.go"
                      :active? true}
                     seed-node])]
      (let [artifacts (#'benchmark/graph-related-artifacts nil prepared packet {})
            rows (:related-files artifacts)]
        (is (= ["consumer" "component"] @prefix-calls))
        (is (= ["component/component.go" "consumer/traces.go"]
               (mapv :path rows)))
        (is (= [1 2] (mapv :rank rows)))
        (is (= ["imports-package" "imports-package"]
               (mapv :relation rows)))
        (is (every? #(= 1 (:sourceLine %)) rows))
        (is (every? #(str/includes? (first (:evidence %))
                                    "connector/connector.go imports")
                    rows))))))

(deftest graph-related-files-resolve-sibling-go-module-imports
  (let [prefix-calls (atom [])
        prepared {:project-id "project"
                  :repo-id "repo"}
        packet {:candidateFiles [{:path "connector/connector.go"
                                  :rank 1}]}
        seed-node {:xt/id "node:connector"
                   :kind :namespace
                   :project-id "project"
                   :repo-id "repo"
                   :file-id "file:connector"
                   :path "connector/connector.go"
                   :active? true}
        connector-manifest {:xt/id "node:connector-go-mod"
                            :kind :manifest
                            :project-id "project"
                            :repo-id "repo"
                            :path "connector/go.mod"
                            :label "go.opentelemetry.io/collector/connector"
                            :active? true}
        consumer-manifest {:xt/id "node:consumer-go-mod"
                           :kind :manifest
                           :project-id "project"
                           :repo-id "repo"
                           :path "consumer/go.mod"
                           :label "go.opentelemetry.io/collector/consumer"
                           :active? true}
        component-manifest {:xt/id "node:component-go-mod"
                            :kind :manifest
                            :project-id "project"
                            :repo-id "repo"
                            :path "component/go.mod"
                            :label "go.opentelemetry.io/collector/component"
                            :active? true}
        namespace-nodes [{:xt/id "node:consumer-traces"
                          :kind :namespace
                          :project-id "project"
                          :repo-id "repo"
                          :file-id "file:consumer-traces"
                          :path "consumer/traces.go"
                          :active? true}
                         {:xt/id "node:component"
                          :kind :namespace
                          :project-id "project"
                          :repo-id "repo"
                          :file-id "file:component"
                          :path "component/component.go"
                          :active? true}]]
    (with-redefs [query/nodes-by-paths
                  (fn [_ paths _scope]
                    (cond
                      (= ["connector/connector.go"] (vec paths))
                      [seed-node]

                      :else
                      (filter #(contains? (set paths) (:path %))
                              [connector-manifest])))
                  query/edges-by-file-ids
                  (fn [_ file-ids _scope]
                    (when (= ["file:connector"] (vec file-ids))
                      [{:xt/id "edge:consumer"
                        :project-id "project"
                        :repo-id "repo"
                        :file-id "file:connector"
                        :path "connector/connector.go"
                        :relation :imports
                        :target-id "node:namespace:go.opentelemetry.io/collector/consumer"
                        :source-line 11
                        :active? true}
                       {:xt/id "edge:component"
                        :project-id "project"
                        :repo-id "repo"
                        :file-id "file:connector"
                        :path "connector/connector.go"
                        :relation :imports
                        :target-id "node:namespace:go.opentelemetry.io/collector/component"
                        :source-line 12
                        :active? true}]))
                  query/nodes-by-labels
                  (fn [_ labels _scope]
                    (filter #(contains? (set labels) (:label %))
                            [consumer-manifest component-manifest]))
                  query/nodes-by-path-prefixes
                  (fn [_ prefixes _scope]
                    (reset! prefix-calls (vec prefixes))
                    (filterv (fn [node]
                               (some #(or (= (:path node) %)
                                          (str/starts-with? (:path node)
                                                            (str % "/")))
                                     prefixes))
                             namespace-nodes))]
      (let [artifacts (#'benchmark/graph-related-artifacts nil prepared packet {})
            rows (:related-files artifacts)]
        (is (= ["consumer" "component"] @prefix-calls))
        (is (= ["component/component.go" "consumer/traces.go"]
               (mapv :path rows)))
        (is (= [{:prefix "component"
                 :files ["component/component.go"]}
                {:prefix "consumer"
                 :files ["consumer/traces.go"]}]
               (mapv (fn [package]
                       {:prefix (:packagePrefix package)
                        :files (mapv :path (:files package))})
                     (:import-packages artifacts))))
        (is (every? #(str/includes? (first (:evidence %))
                                    "via module go.opentelemetry.io/collector/")
                    rows))))))

(deftest graph-related-files-anchor-nested-go-module-imports-to-manifest-dir
  (let [prefix-calls (atom [])
        prepared {:project-id "project"
                  :repo-id "repo"}
        packet {:candidateFiles [{:path "connector/connector.go"
                                  :rank 1}]}
        seed-node {:xt/id "node:connector"
                   :kind :namespace
                   :project-id "project"
                   :repo-id "repo"
                   :file-id "file:connector"
                   :path "connector/connector.go"
                   :active? true}
        manifest-node {:xt/id "node:connector-go-mod"
                       :kind :manifest
                       :project-id "project"
                       :repo-id "repo"
                       :path "connector/go.mod"
                       :label "go.opentelemetry.io/collector/connector"
                       :active? true}
        namespace-nodes [{:xt/id "node:connector-internal"
                          :kind :namespace
                          :project-id "project"
                          :repo-id "repo"
                          :file-id "file:connector-internal"
                          :path "connector/internal/testcomponents.go"
                          :active? true}
                         {:xt/id "node:unrelated-internal"
                          :kind :namespace
                          :project-id "project"
                          :repo-id "repo"
                          :file-id "file:unrelated-internal"
                          :path "internal/cmd/pdatagen/internal/pdata/base_slices.go"
                          :active? true}]]
    (with-redefs [query/nodes-by-paths
                  (fn [_ paths _scope]
                    (cond
                      (= ["connector/connector.go"] (vec paths))
                      [seed-node]

                      :else
                      (filter #(contains? (set paths) (:path %))
                              [manifest-node])))
                  query/edges-by-file-ids
                  (fn [_ file-ids _scope]
                    (when (= ["file:connector"] (vec file-ids))
                      [{:xt/id "edge:internal"
                        :project-id "project"
                        :repo-id "repo"
                        :file-id "file:connector"
                        :path "connector/connector.go"
                        :relation :imports
                        :target-id "node:namespace:go.opentelemetry.io/collector/connector/internal"
                        :source-line 10
                        :active? true}]))
                  query/nodes-by-labels (fn [& _] [])
                  query/nodes-by-path-prefixes
                  (fn [_ prefixes _scope]
                    (reset! prefix-calls (vec prefixes))
                    (filterv (fn [node]
                               (some #(or (= (:path node) %)
                                          (str/starts-with? (:path node)
                                                            (str % "/")))
                                     prefixes))
                             namespace-nodes))]
      (let [artifacts (#'benchmark/graph-related-artifacts nil prepared packet {})
            rows (:related-files artifacts)]
        (is (= ["connector/internal"] @prefix-calls))
        (is (= ["connector/internal/testcomponents.go"]
               (mapv :path rows)))
        (is (str/includes? (first (:evidence (first rows)))
                           "-> package connector/internal"))))))

(deftest ygg-agent-preparation-reuses-warm-artifacts
  (let [out (temp-dir "ygg-bench-agent-run-context-reuse")
        worktree (temp-dir "ygg-bench-agent-run-context-reuse-worktree")
        suite {:id "suite"}
        case {:id "case-1"}
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :caseFingerprint "sha256:case"
                  :agentInputFingerprint "sha256:agent-input"
                  :project-id "project"
                  :repo-id "repo"
                  :worktreeRoot worktree
                  :input {:queryText "broken app"}
                  :coverage {:declaredSourceKinds []}}
        opts {:out out
              :agent-id "agent"
              :mode "ygg"}
        index-count (atom 0)]
    (with-redefs [store/with-node (fn [_ f]
                                    (f {:indexed? (atom false)}))
                  benchmark-readiness/prepare-agent-overlay! (fn [& _] {})
                  benchmark-readiness/sync-inspect-summary (fn [& _]
                                                             {:status "completed"})
                  project/index-project! (fn [xtdb _project _opts]
                                           (swap! index-count inc)
                                           (reset! (:indexed? xtdb) true)
                                           {:status "completed"})
                  project/infer-project! (fn [_xtdb _project]
                                           {:status "completed"})
                  context/context-packet (fn [xtdb query-text _opts]
                                           {:schema "ygg.context/v1"
                                            :query query-text
                                            :docs []
                                            :entities []
                                            :edges []
                                            :warnings []
                                            :search {:instrumentation {:indexed @(:indexed? xtdb)}}
                                            :candidateFiles [{:path "src/app.clj"
                                                              :rank 1
                                                              :score 1.0
                                                              :targetKind "chunk"
                                                              :label "app/broken"}]})
                  benchmark/context-packet->agent-hints (fn [_prepared packet _opts]
                                                          {:schema benchmark/agent-hints-schema
                                                           :selection {:candidateFiles (count (:candidateFiles packet))}
                                                           :topFiles (:candidateFiles packet)})
                  query/nodes-by-paths (fn [& _] [])
                  query/nodes-by-labels (fn [& _] [])
                  query/edges-by-file-ids (fn [& _] [])
                  query/nodes-by-path-prefixes (fn [& _] [])]
      (let [prepared-result (#'benchmark/prepare-agent-graph-and-artifacts! suite
                                                                            case
                                                                            prepared
                                                                            opts)
            reused-result (#'benchmark/prepare-or-reuse-agent-graph-and-artifacts!
                           suite
                           case
                           prepared
                           opts)
            progress (json/read-json
                      (slurp (benchmark-paths/progress-path suite case opts))
                      :key-fn keyword)]
        (is (= 1 @index-count))
        (is (= "prepared" (get-in prepared-result [:preparation :status])))
        (is (= "reused" (get-in reused-result [:preparation :status])))
        (is (= (get-in prepared-result [:artifacts :context-path])
               (get-in reused-result [:artifacts :context-path])))
        (is (.isFile (io/file (get-in reused-result [:preparation :path]))))
        (is (some #(and (= "reuse-agent-artifacts" (:stage %))
                        (= "completed" (:status %)))
                  (:events progress)))))))
(deftest runs-external-agent-command-and-scores-result
  (let [root (temp-dir "ygg-bench-agent-run-repo")
        out (temp-dir "ygg-bench-agent-run-out")
        suite-dir (temp-dir "ygg-bench-agent-run-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-run-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (spit script-path
              (str "test -f src/app.clj\n"
                   "test -f \"$YGG_BENCH_PACKET\"\n"
                   "test -f \"$YGG_BENCH_PROMPT\"\n"
                   "test -f \"$YGG_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$YGG_BENCH_PROMPT_PROFILE\" = fast\n"
                   "grep -q 'YGG_BENCH_RESULT' \"$YGG_BENCH_PROMPT\"\n"
                   "grep -q 'Fast Localization Profile' \"$YGG_BENCH_PROMPT\"\n"
                   "grep -q 'suspectedFiles' \"$YGG_BENCH_OUTPUT_SCHEMA\"\n"
                   "test \"$YGG_BENCH_CASE_ID\" = case-1\n"
                   "test -n \"$YGG_BENCH_CASE_FINGERPRINT\"\n"
                   "test -n \"$YGG_BENCH_AGENT_INPUT_FINGERPRINT\"\n"
                   "cat > \"$YGG_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[{\"kind\":\"command\","
                   "\"value\":\"rg broken src/app.clj\"}]}],"
                   "\"warnings\":[\"agent note\"],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"
                   "printf 'ran %s\\n' \"$YGG_BENCH_AGENT_ID\"\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "script-agent"
                                                   :mode "shell-only"
                                                   :prompt-profile "fast"
                                                   :command (str "sh " script-path)})
              resumed (benchmark/agent-runs! suite {:out out
                                                    :case-id "case-1"
                                                    :agent-id "script-agent"
                                                    :mode "shell-only"
                                                    :prompt-profile "fast"
                                                    :command "exit 99"
                                                    :skip-existing? true})
              rerun (benchmark/agent-runs! suite {:out out
                                                  :case-id "case-1"
                                                  :agent-id "script-agent"
                                                  :mode "shell-only"
                                                  :prompt-profile "fast"
                                                  :command (str "sh " script-path)})
              run (first (:runs result))
              skipped (first (:runs resumed))
              rerun-run (first (:runs rerun))
              progress (json/read-json
                        (slurp (benchmark-paths/progress-path
                                suite
                                (first (:cases suite))
                                {:out out}))
                        :key-fn keyword)
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "script-agent"})]
          (is (= benchmark/agent-runs-schema (:schema result)))
          (is (= 1 (:completed result)))
          (is (= 0 (:failed result)))
          (is (= benchmark/agent-run-schema (:schema run)))
          (is (= "passed" (:status run)))
          (is (= "fast" (:promptProfile run)))
          (is (= ["agent note"] (:warnings run)))
          (is (= 0 (get-in run [:process :exit])))
          (is (= 1.0 (get-in run [:scores :fileRecallAt10])))
          (is (= 1.0 (get-in run [:scores :meanReciprocalRankFile])))
          (is (.isFile (io/file (get-in run [:artifacts :agentResultPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :agentScorePath]))))
          (is (.isFile (io/file (get-in run [:artifacts :promptPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :outputSchemaPath]))))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             benchmark/agent-result-schema))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "YGG_BENCH_OUTPUT_SCHEMA"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "final response"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "Only include files likely to require edits in suspectedFiles"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "do not demote those requested targets"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "coverageSourceKinds"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "runtime/config/setup file may need edits"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "exact repo-relative path"))
          (is (str/includes? (slurp (get-in run [:artifacts :promptPath]))
                             "caseId, caseFingerprint, and agentInputFingerprint"))
          (let [output-schema (json/read-json
                               (slurp (get-in run [:artifacts :outputSchemaPath]))
                               :key-fn keyword)]
            (is (= ["schema"
                    "caseId"
                    "caseFingerprint"
                    "agentInputFingerprint"
                    "agentId"
                    "mode"
                    "selection"
                    "parserWorker"
                    "suspectedFiles"
                    "suspectedSymbols"
                    "commands"
                    "warnings"
                    "summary"
                    "decision"
                    "tokenUsage"]
                   (:required output-schema)))
            (is (= [] (strict-schema-required-mismatches output-schema))))
          (is (= {:type "string"}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :caseFingerprint])))
          (is (= {:type "string"}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :agentInputFingerprint])))
          (is (= {:type "string"
                  :enum ["ygg" "shell-only" "local-vector" "codebase-memory" "graphify"]}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :mode])))
          (is (= {:type "object"
                  :additionalProperties false
                  :required ["mode" "source"]
                  :properties {:mode {:type "string"}
                               :source {:type "string"}}}
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :parserWorker])))
          (is (not (contains?
                    (get-in (json/read-json
                             (slurp (get-in run [:artifacts :outputSchemaPath]))
                             :key-fn keyword)
                            [:properties
                             :suspectedFiles
                             :items
                             :properties])
                    :metrics)))
          (is (str/includes?
               (get-in (json/read-json
                        (slurp (get-in run [:artifacts :outputSchemaPath]))
                        :key-fn keyword)
                       [:properties
                        :suspectedFiles
                        :items
                        :properties
                        :evidence
                        :description])
               "exact repo-relative path"))
          (let [selection-schema (get-in (json/read-json
                                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                                          :key-fn keyword)
                                         [:properties :selection])]
            (is (= false (:additionalProperties selection-schema)))
            (is (= ["rawCandidateFiles"
                    "candidateFiles"
                    "coverageFilteredCandidateFiles"
                    "limit"
                    "coverageSourceKinds"]
                   (:required selection-schema)))
            (is (= (set (:required selection-schema))
                   (set (map name (keys (:properties selection-schema))))))
            (is (= {:type "integer"
                    :minimum 0}
                   (get-in selection-schema [:properties :rawCandidateFiles])))
            (is (= {:type ["integer" "null"]
                    :minimum 0}
                   (get-in selection-schema [:properties :limit])))
            (is (not (contains? (:properties selection-schema)
                                :inspectionRepoCandidateSelected))))
          (is (= ["object" "null"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :decision :type])))
          (is (= ["kind" "choices" "risks" "followups"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :decision :required])))
          (is (= ["object" "null"]
                 (get-in (json/read-json
                          (slurp (get-in run [:artifacts :outputSchemaPath]))
                          :key-fn keyword)
                         [:properties :tokenUsage :type])))
          (let [prompt-text (slurp (get-in run [:artifacts :promptPath]))]
            (is (not (str/includes? prompt-text "changedFiles")))
            (is (str/includes? prompt-text "\"tokenUsage\": null"))
            (is (not (str/includes? prompt-text "\"totalTokens\": 0"))))
          (is (str/includes? (slurp (get-in run [:artifacts :stdoutPath]))
                             "ran script-agent"))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores run) (:scores skipped)))
          (is (= "passed" (:status rerun-run)))
          (is (= ["agent-result"
                  "agent-result"
                  "score-agent-result"
                  "score-agent-result"]
                 (mapv :stage (:events progress))))
          (is (= 1 (:runs report)))
          (is (= "script-agent" (get-in report [:results 0 :agent :agentId]))))))))
(deftest external-agent-result-preserves-identity-mismatches
  (let [root (temp-dir "ygg-bench-agent-run-identity-repo")
        out (temp-dir "ygg-bench-agent-run-identity-out")
        suite-dir (temp-dir "ygg-bench-agent-run-identity-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-run-identity-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (spit script-path
              (str "cat > \"$YGG_BENCH_RESULT\" <<'JSON'\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"caseId\":\"case-2\","
                   "\"caseFingerprint\":\"sha256:old-case\","
                   "\"agentId\":\"identity-agent\","
                   "\"mode\":\"shell-only\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"suspectedSymbols\":[],"
                   "\"commands\":[\"rg broken src/app.clj\"],"
                   "\"warnings\":[],"
                   "\"tokenUsage\":{\"inputTokens\":0,\"outputTokens\":0,"
                   "\"totalTokens\":0,\"costUsd\":0.0,"
                   "\"source\":\"agent-report\"},"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "identity-agent"
                                                   :mode "shell-only"
                                                   :prompt-profile "fast"
                                                   :command (str "sh " script-path)})
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "identity-agent"
                                                          :allow-unverified-scores? true})
              warnings (get-in report [:results 0 :agentOutput :identityWarnings])]
          (is (= 1 (:completed result)))
          (is (= ["agent result caseId case-2 does not match expected case case-1"
                  (str "agent result caseFingerprint sha256:old-case does not match expected case fingerprint "
                       (get-in report [:results 0 :caseFingerprint]))]
                 warnings))
          (is (= {:identityMismatchRuns 1
                  :identityMismatchCaseIds ["case-1"]
                  :identityMismatches 2}
                 (select-keys (:agentDiagnostics report)
                              [:identityMismatchRuns
                               :identityMismatchCaseIds
                               :identityMismatches]))))))))
(deftest failed-external-agent-command-is-scored-as-empty-result
  (let [root (temp-dir "ygg-bench-agent-fail-repo")
        out (temp-dir "ygg-bench-agent-fail-out")
        suite-dir (temp-dir "ygg-bench-agent-fail-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-fail-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "failing-agent"
                                                   :mode "shell-only"
                                                   :command "exit 7"})
              run (first (:runs result))
              score (json/read-json
                     (slurp (get-in run [:artifacts :agentScorePath]))
                     :key-fn keyword)]
          (is (= 1 (:failed result)))
          (is (= "failed" (:status run)))
          (is (= 7 (get-in run [:process :exit])))
          (is (.isFile (io/file (get-in run [:artifacts :promptPath]))))
          (is (.isFile (io/file (get-in run [:artifacts :outputSchemaPath]))))
          (is (= 0.0 (get-in run [:scores :fileRecallAt10])))
          (is (= ["Agent command exited with status 7."
                  "agent result did not contain suspected files"]
                 (get-in score [:agent :warnings]))))))))

(deftest external-agent-token-usage-sidecar-is-merged-into-result
  (let [root (temp-dir "ygg-bench-agent-token-repo")
        out (temp-dir "ygg-bench-agent-token-out")
        suite-dir (temp-dir "ygg-bench-agent-token-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-token-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (spit script-path
              (str "cat > \"$YGG_BENCH_RESULT\" <<JSON\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"caseId\":\"case-1\","
                   "\"caseFingerprint\":\"$YGG_BENCH_CASE_FINGERPRINT\","
                   "\"agentInputFingerprint\":\"$YGG_BENCH_AGENT_INPUT_FINGERPRINT\","
                   "\"agentId\":\"token-agent\","
                   "\"mode\":\"shell-only\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"suspectedSymbols\":[],"
                   "\"commands\":[\"rg broken src/app.clj\"],"
                   "\"warnings\":[],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"
                   "cat > \"$YGG_BENCH_TOKEN_USAGE\" <<'JSON'\n"
                   "{\"input_tokens\":120,\"output_tokens\":30,"
                   "\"cost_usd\":0.01,\"provider\":\"test-provider\","
                   "\"model\":\"test-model\"}\n"
                   "JSON\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "token-agent"
                                                   :mode "shell-only"
                                                   :command (str "sh " script-path)})
              run (first (:runs result))
              score (json/read-json
                     (slurp (get-in run [:artifacts :agentScorePath]))
                     :key-fn keyword)
              report (benchmark/report-agent-suite suite {:out out
                                                          :agent-id "token-agent"})]
          (is (= "passed" (:status run)))
          (is (.isFile (io/file (get-in run [:artifacts :tokenUsagePath]))))
          (is (= {:inputTokens 120
                  :outputTokens 30
                  :totalTokens 150
                  :costUsd 0.01
                  :source "sidecar"
                  :model "test-model"
                  :provider "test-provider"}
                 (get-in score [:agent :tokenUsage])))
          (is (= {:inputTokens 120
                  :outputTokens 30
                  :totalTokens 150
                  :costUsd 0.01}
                 (get-in report [:agentDiagnostics :tokenTelemetry]))))))))

(deftest external-agent-missing-token-sidecar-uses-prompt-estimate
  (let [root (temp-dir "ygg-bench-agent-prompt-token-repo")
        out (temp-dir "ygg-bench-agent-prompt-token-out")
        suite-dir (temp-dir "ygg-bench-agent-prompt-token-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        script-path (.getPath (io/file suite-dir "agent.sh"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "agent-prompt-token-fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value."}}]}))
        (spit script-path
              (str "cat > \"$YGG_BENCH_RESULT\" <<JSON\n"
                   "{\"schema\":\"" benchmark/agent-result-schema "\","
                   "\"caseId\":\"case-1\","
                   "\"caseFingerprint\":\"$YGG_BENCH_CASE_FINGERPRINT\","
                   "\"agentInputFingerprint\":\"$YGG_BENCH_AGENT_INPUT_FINGERPRINT\","
                   "\"agentId\":\"prompt-token-agent\","
                   "\"mode\":\"shell-only\","
                   "\"suspectedFiles\":[{\"path\":\"src/app.clj\","
                   "\"rank\":1,\"confidence\":1.0,\"reason\":\"script\","
                   "\"evidence\":[\"rg broken src/app.clj\"]}],"
                   "\"suspectedSymbols\":[],"
                   "\"commands\":[\"rg broken src/app.clj\"],"
                   "\"warnings\":[],"
                   "\"summary\":\"script result\"}\n"
                   "JSON\n"))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-runs! suite {:out out
                                                   :case-id "case-1"
                                                   :agent-id "prompt-token-agent"
                                                   :mode "shell-only"
                                                   :command (str "sh " script-path)})
              run (first (:runs result))
              prompt-path (get-in run [:artifacts :promptPath])
              expected-input-tokens (context/estimate-tokens (slurp prompt-path))
              score-path (get-in run [:artifacts :agentScorePath])
              result-path (get-in run [:artifacts :agentResultPath])
              score (json/read-json (slurp score-path) :key-fn keyword)
              raw-result (dissoc (json/read-json (slurp result-path) :key-fn keyword)
                                 :tokenUsage)
              _ (spit result-path
                      (json/write-json-str raw-result {:indent-str "  "}))
              rescored (benchmark/score-agent-result! suite
                                                      (first (:cases suite))
                                                      {:out out
                                                       :result-path result-path})]
          (is (= "passed" (:status run)))
          (is (not (.isFile (io/file (get-in run
                                             [:artifacts :tokenUsagePath])))))
          (is (= {:inputTokens expected-input-tokens
                  :outputTokens 0
                  :totalTokens expected-input-tokens
                  :costUsd 0.0
                  :source "benchmark-prompt-estimate"}
                 (get-in score [:agent :tokenUsage])))
          (is (= (get-in score [:agent :tokenUsage])
                 (get-in rescored [:agent :tokenUsage]))))))))
(deftest context-packet-can-be-written-as-agent-result
  (let [root (temp-dir "ygg-bench-context-result")
        _ (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        packet {:query "broken app"
                :drilldowns [{:kind :query
                              :label "Continue graph query"
                              :command "ygg query 'broken app' --project fixture"
                              :mcpTool "ygg_query"
                              :mcpArgs {:query "broken app"
                                        :projectId "fixture"}}]
                :warnings ["Context warning."]
                :evidence {:nextActions [{:kind :dependencies
                                          :command "ygg packages --project fixture --json"}]}
                :architecture {:validationGaps [{:plane "dependencies"
                                                 :status "missing"
                                                 :nextActions [{:kind :dependency-review
                                                                :command "ygg sync project.edn --check --enqueue"}]}]}
                :docs [{:source {:path "src/app.clj"
                                 :heading "broken"
                                 :definitionKind :function
                                 :lines [2 4]}
                        :score 2.4
                        :provenance "retrieved-doc"}
                       {:source {:path "src/app.clj"
                                 :heading "broken"}
                        :score 1.5
                        :provenance "retrieved-doc"}
                       {:source {:path "src/missing.clj"
                                 :heading "missing"}
                        :score 1.0
                        :provenance "retrieved-doc"}]
                :entities [{:label "db"
                            :path "src/db.clj"
                            :score 0.7}]}
        result (benchmark/context-packet->agent-result
                packet
                {:agent-id "ygg-baseline-lexical"
                 :case-id "case-1"
                 :root root})]
    (is (= benchmark/agent-result-schema (:schema result)))
    (is (= "case-1" (:caseId result)))
    (is (= "ygg-baseline-lexical" (:agentId result)))
    (is (= "ygg" (:mode result)))
    (is (= [{:path "src/app.clj"
             :rank 1
             :confidence 1.0
             :metrics {:firstSourceRank 1
                       :supportCount 2
                       :docCount 2
                       :entityCount 0
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 3.0
                       :matchedTokenCount 2
                       :matchedPathQueryTokenCount 1
                       :definitionKinds ["function"]}
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"
                        "context-doc:src/app.clj provenance=retrieved-doc"]
             :reason (str "Yggdrasil context doc \"broken\" from src/app.clj lines 2-4 "
                          "with provenance retrieved-doc. Additional Yggdrasil evidence: "
                          "1 more matching row.")}
            {:path "src/db.clj"
             :rank 2
             :confidence 0.7
             :metrics {:firstSourceRank 1001
                       :supportCount 1
                       :docCount 0
                       :entityCount 1
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 0.7
                       :rankScore 0.8099999999999999
                       :matchedTokenCount 0
                       :definitionKinds []}
             :evidence ["graph-entity:db path=src/db.clj"]
             :reason "Yggdrasil graph entity \"db\" references src/db.clj."}]
           (:suspectedFiles result)))
    (is (= [{:name "broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"
             :confidence 1.0
             :reason "Yggdrasil context doc \"broken\" references src/app.clj lines 2-4."
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]}
            {:name "missing"
             :path "src/missing.clj"
             :rank 3
             :kind "unknown"
             :confidence 1.0
             :reason "Yggdrasil context doc \"missing\" references src/missing.clj."
             :evidence ["context-doc:src/missing.clj provenance=retrieved-doc"]}]
           (:suspectedSymbols result)))
    (is (= ["ygg query 'broken app' --project fixture"
            "ygg packages --project fixture --json"
            "ygg sync project.edn --check --enqueue"]
           (:commands result)))
    (is (= ["Context warning."] (:warnings result)))
    (let [expected-input-tokens (context/estimate-tokens
                                 (dissoc result :tokenUsage))]
      (is (= {:inputTokens expected-input-tokens
              :outputTokens 0
              :totalTokens expected-input-tokens
              :costUsd 0.0
              :source "ygg-baseline-compact-surface-estimate"}
             (:tokenUsage result))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 2
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds []}
           (:selection result)))
    (is (not (contains? result :groundTruth)))
    (is (not (contains? result :inputHints)))
    (let [compact (benchmark/context-packet->agent-result packet
                                                          {:root root
                                                           :compact-result? true})]
      (is (= [{:path "src/app.clj"
               :rank 1
               :confidence 1.0
               :reason "Ranked by Yggdrasil graph evidence."
               :evidence ["ygg:path:src/app.clj"]}
              {:path "src/db.clj"
               :rank 2
               :confidence 0.7
               :reason "Ranked by Yggdrasil graph evidence."
               :evidence ["ygg:path:src/db.clj"]}]
             (:suspectedFiles compact)))
      (is (= [] (:suspectedSymbols compact)))
      (is (true? (get-in compact [:selection :compactResultSurface])))
      (is (= (context/estimate-tokens (dissoc compact :tokenUsage))
             (get-in compact [:tokenUsage :totalTokens]))))
    (let [compact-limited (benchmark/context-packet->agent-result
                           packet
                           {:root root
                            :compact-result? true
                            :compact-result-limit 1})]
      (is (= ["src/app.clj"]
             (mapv :path (:suspectedFiles compact-limited))))
      (is (= 1 (get-in compact-limited [:selection :compactResultLimit])))
      (is (= 1 (get-in compact-limited [:selection :compactResultFiles]))))
    (let [limited (benchmark/context-packet->agent-result packet {:root root
                                                                  :limit 1})]
      (is (= [{:path "src/app.clj"
               :rank 1
               :confidence 1.0
               :metrics {:firstSourceRank 1
                         :supportCount 2
                         :docCount 2
                         :entityCount 0
                         :candidateFileCount 0
                         :retrievedSourceCount 0
                         :exactPathSourceCount 0
                         :maxConfidence 1.0
                         :rankScore 3.0
                         :matchedTokenCount 2
                         :matchedPathQueryTokenCount 1
                         :definitionKinds ["function"]}
               :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"
                          "context-doc:src/app.clj provenance=retrieved-doc"]
               :reason (str "Yggdrasil context doc \"broken\" from src/app.clj lines 2-4 "
                            "with provenance retrieved-doc. Additional Yggdrasil evidence: "
                            "1 more matching row.")}]
             (:suspectedFiles limited)))
      (is (= {:rawCandidateFiles 2
              :candidateFiles 2
              :coverageFilteredCandidateFiles 0
              :limit 1
              :coverageSourceKinds []}
             (:selection limited)))
      (is (= 2 (count (:suspectedSymbols limited)))))))

(deftest compact-agent-result-prunes-thin-candidate-output
  (let [root (temp-dir "ygg-bench-compact-thin-output")
        _ (doseq [path ["src/a.clj" "src/b.clj" "src/c.clj" "src/d.clj"]]
            (spit-file! root path "(ns fixture)\n"))
        packet {:query "broken app"
                :docs (mapv (fn [idx path]
                              {:source {:path path
                                        :heading (str "broken " idx)}
                               :score (- 2.0 (* idx 0.1))
                               :provenance "retrieved-doc"})
                            (range)
                            ["src/a.clj"
                             "src/b.clj"
                             "src/c.clj"
                             "src/d.clj"])}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :limit 20
                 :compact-result? true
                 :compact-result-limit 5})]
    (is (= ["src/a.clj" "src/b.clj" "src/c.clj"]
           (mapv :path (:suspectedFiles result))))
    (is (= 3 (get-in result [:selection :compactResultLimit])))
    (is (= 3 (get-in result [:selection :compactResultFiles])))
    (let [wider (benchmark/context-packet->agent-result
                 packet
                 {:root root
                  :limit 20
                  :compact-result? true
                  :compact-result-limit 12})]
      (is (= ["src/a.clj" "src/b.clj" "src/c.clj" "src/d.clj"]
             (mapv :path (:suspectedFiles wider))))
      (is (= 12 (get-in wider [:selection :compactResultLimit])))
      (is (= 4 (get-in wider [:selection :compactResultFiles]))))))

(deftest context-packet-agent-result-emits-structural-decision-choices
  (let [root (temp-dir "ygg-bench-context-decision")
        _ (spit-file! root "package.json" "{\"dependencies\":{\"astro\":\"latest\"}}\n")
        _ (spit-file! root "site/astro.config.ts" "import { defineConfig } from 'astro/config'\n")
        packet {:query "plan docs search integration"
                :docs [{:source {:path "package.json"
                                 :heading "dependencies"
                                 :definitionKind :manifest
                                 :lines [1 1]}
                        :score 3.0
                        :snippet "astro dependency"
                        :provenance "retrieved-doc"}]}
        decision-candidates [{:id "plan-config-and-manifest"
                              :kind "change-plan"
                              :paths ["site/astro.config.ts"
                                      "package.json"]}
                             {:id "plan-manifest-only"
                              :kind "change-plan"
                              :paths ["package.json"]}
                             {:id "plan-config-only"
                              :kind "change-plan"
                              :paths ["site/astro.config.ts"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :case-id "case-1"
                 :caseFingerprint "sha256:test-case"
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates})
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["package.json"]
                                :unsupportedGroundTruthFiles []}
                  :decisionCandidates decision-candidates
                  :decisionGroundTruth {:kind "change-plan"
                                        :required ["plan-config-and-manifest"]
                                        :forbidden ["plan-manifest-only"
                                                    "plan-config-only"]}}
        scored (benchmark-agent-score/score-agent-result prepared result)]
    (is (= "change-plan" (get-in result [:decision :kind])))
    (is (= "include" (get-in choices-by-id ["plan-config-and-manifest" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-manifest-only" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-config-only" :status])))
    (is (some #(= "site/astro.config.ts" (:path %))
              (:suspectedFiles result)))
    (is (= 2 (get-in (some #(when (= "site/astro.config.ts" (:path %)) %)
                           (:suspectedFiles result))
                     [:metrics :decisionCandidateCount])))
    (is (= ["Ygg baseline ranked file site/astro.config.ts at rank 2."
            "Ygg baseline ranked file package.json at rank 1."]
           (get-in choices-by-id ["plan-config-and-manifest" :evidence])))
    (is (= 1.0 (get-in scored [:scores :decisionRecall])))
    (is (= 1.0 (get-in scored [:scores :decisionPrecision])))
    (is (= 1.0 (get-in scored [:scores :decisionF1])))
    (is (= 1.0 (get-in scored [:scores :decisionEvidenceCitationRate])))))

(deftest context-packet-agent-result-includes-compatible-shared-path-decisions
  (let [root (temp-dir "ygg-bench-compatible-decision")
        _ (spit-file! root "migrations/setup.sql" "alter event trigger owned by postgres;\n")
        _ (spit-file! root "migrations/.env" "DATABASE_URL=postgres://example\n")
        packet {:query "event trigger owner runtime env validation"
                :candidateFiles [{:path "migrations/.env"
                                  :rank 1
                                  :score 2.0
                                  :targetKind "file"
                                  :label "DATABASE_URL"
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.8}}
                                 {:path "migrations/setup.sql"
                                  :rank 2
                                  :score 2.0
                                  :targetKind "file"
                                  :label "event trigger owner"
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.8}}]}
        decision-candidates [{:id "inspect-sql-owner-and-runtime-env"
                              :kind "maintenance-action"
                              :paths ["migrations/setup.sql"
                                      "migrations/.env"]}
                             {:id "defer-owner-change-until-sync-check"
                              :kind "maintenance-action"
                              :coexists-with ["inspect-sql-owner-and-runtime-env"]
                              :paths ["migrations/setup.sql"]}
                             {:id "change-testinfra-import-first"
                              :kind "maintenance-action"
                              :paths ["testinfra/test_postgres.py"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :decision-kind "maintenance-action"
                 :decision-candidates decision-candidates
                 :limit 5})
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))]
    (is (= "include"
           (get-in choices-by-id ["inspect-sql-owner-and-runtime-env" :status])))
    (is (= "include"
           (get-in choices-by-id ["defer-owner-change-until-sync-check" :status])))
    (is (= "defer"
           (get-in choices-by-id ["change-testinfra-import-first" :status])))
    (is (= ["Ygg baseline ranked file migrations/setup.sql at rank 1."]
           (get-in choices-by-id ["defer-owner-change-until-sync-check"
                                  :evidence])))))

(deftest decision-candidate-paths-contribute-to-file-ranking
  (let [root (temp-dir "ygg-bench-decision-candidate-path-rank")
        _ (doseq [path ["src/noise_one.cs"
                        "src/noise_two.cs"
                        "src/noise_three.cs"
                        "src/core.cs"
                        "tests/core_test.cs"]]
            (spit-file! root path "namespace Demo;\n"))
        packet {:query "plan jsonb string handling"
                :candidateFiles [{:path "src/noise_one.cs"
                                  :rank 1
                                  :score 4.3
                                  :targetKind "node"
                                  :label "Demo.NoiseOne.JsonString"
                                  :scoreComponents {:sourceGraph 4.3
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/noise_two.cs"
                                  :rank 2
                                  :score 4.2
                                  :targetKind "node"
                                  :label "Demo.NoiseTwo.JsonString"
                                  :scoreComponents {:sourceGraph 4.2
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/noise_three.cs"
                                  :rank 3
                                  :score 4.1
                                  :targetKind "node"
                                  :label "Demo.NoiseThree.JsonString"
                                  :scoreComponents {:sourceGraph 4.1
                                                    :lexical 0.45
                                                    :graph 0.2}}
                                 {:path "src/core.cs"
                                  :rank 15
                                  :score 4.0
                                  :targetKind "node"
                                  :label "Demo.Core.JsonString"
                                  :scoreComponents {:sourceGraph 4.0
                                                    :lexical 0.45
                                                    :graph 0.2}}]}
        decision-candidates [{:id "plan-core-and-tests"
                              :kind "change-plan"
                              :label "Plan core and tests"
                              :paths ["src/core.cs" "tests/core_test.cs"]}
                             {:id "plan-core-only"
                              :kind "change-plan"
                              :label "Plan core only"
                              :paths ["src/core.cs"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates
                 :limit 5})
        files (:suspectedFiles result)
        file-by-path (into {} (map (juxt :path identity)) files)
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))]
    (is (contains? file-by-path "src/core.cs"))
    (is (<= (:rank (get file-by-path "src/core.cs")) 5))
    (is (= 2 (get-in file-by-path ["src/core.cs" :metrics :decisionCandidateCount])))
    (is (= "include" (get-in choices-by-id ["plan-core-and-tests" :status])))
    (is (= "exclude" (get-in choices-by-id ["plan-core-only" :status])))))

(deftest decision-candidate-ranking-tokenizes-evidence-text-once-for-token-pairs
  (let [root (temp-dir "ygg-bench-decision-token-pairs")
        _ (spit-file! root "src/core.cs" "namespace Demo;\n")
        tokenize text/tokenize
        evidence-text "src/core.cs\nplan-core\nchange-plan\nPlan core\njsonb handling"
        calls (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (when (= evidence-text (str value))
                                    (swap! calls inc))
                                  (tokenize value))]
      (let [file (-> (benchmark/context-packet->agent-result
                      {:query "plan core jsonb handling"}
                      {:root root
                       :decision-kind "change-plan"
                       :decision-candidates [{:id "plan-core"
                                              :kind "change-plan"
                                              :label "Plan core"
                                              :summary "jsonb handling"
                                              :paths ["src/core.cs"]}]})
                     :suspectedFiles
                     first)]
        (is (= "src/core.cs" (:path file)))
        (is (= 1 @calls))
        (is (pos? (get-in file [:metrics :matchedTokenPairCount])))))))

(deftest context-packet-agent-result-respects-declared-source-coverage
  (let [root (temp-dir "ygg-bench-source-coverage")
        _ (spit-file! root ".github/ISSUE_TEMPLATE/bug_report.md" "bug report\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        packet {:query "bug report app"
                :docs [{:source {:path ".github/ISSUE_TEMPLATE/bug_report.md"
                                 :heading "bug report"}
                        :score 10.0
                        :snippet "bug report app"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/app.clj"
                                 :heading "app"}
                        :score 1.0
                        :snippet "app"
                        :provenance "retrieved-doc"}]}
        unfiltered (benchmark/context-packet->agent-result packet {:root root})
        filtered (benchmark/context-packet->agent-result
                  packet
                  {:root root
                   :coverage {:declaredSourceKinds ["code"]}})]
    (is (= [".github/ISSUE_TEMPLATE/bug_report.md" "src/app.clj"]
           (mapv :path (:suspectedFiles unfiltered))))
    (is (= ["src/app.clj"]
           (mapv :path (:suspectedFiles filtered))))
    (is (= [1]
           (mapv :rank (:suspectedFiles filtered))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["code"]}
           (:selection filtered)))))
(deftest context-packet-agent-result-filters-single-root-map-by-scanned-kind
  (let [root (temp-dir "ygg-bench-single-root-source-coverage")
        _ (spit-file! root "site/src/pages/index.astro" "---\n---\n<h1>Home</h1>\n")
        _ (spit-file! root "README.md" "# Docs\n")
        packet {:query "docs route"
                :candidateFiles [{:path "site/src/pages/index.astro"
                                  :repo "bootstrap"
                                  :rank 1
                                  :score 1.0
                                  :targetKind :node
                                  :label "/"}
                                 {:path "README.md"
                                  :repo "bootstrap"
                                  :rank 2
                                  :score 1.0
                                  :targetKind :node
                                  :label "Docs"}]}
        result (benchmark/context-packet->agent-result
                packet
                {:roots {"bootstrap" root}
                 :coverage {:declaredSourceKinds ["web-framework"]}})]
    (is (= ["site/src/pages/index.astro"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["web-framework"]}
           (:selection result)))))
(deftest context-packet-agent-result-prioritizes-declared-source-lanes
  (let [root (temp-dir "ygg-bench-source-lane-priority")
        _ (spit-file! root "src/first.clj" "(ns first)\n")
        _ (spit-file! root "src/second.clj" "(ns second)\n")
        _ (spit-file! root "config/.env" "DATABASE_URL=postgres://example\n")
        packet {:query "runtime configuration"
                :docs [{:source {:path "src/first.clj"
                                 :heading "runtime configuration"}
                        :score 4.0
                        :snippet "runtime configuration"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/second.clj"
                                 :heading "configuration helper"}
                        :score 3.5
                        :snippet "runtime configuration"
                        :provenance "retrieved-doc"}]
                :architecture {:runtimeEvidence [{:id "evidence:database-url"
                                                  :path "config/.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :score 1.0}]}}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :coverage {:declaredSourceKinds ["code" "env"]}})]
    (is (= ["src/first.clj" "config/.env" "src/second.clj"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2 3]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 3
            :candidateFiles 3
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds ["code" "env"]}
           (:selection result)))))
(deftest context-packet-agent-hints-respect-declared-source-coverage
  (let [root (temp-dir "ygg-bench-hints-source-coverage")
        _ (spit-file! root ".github/ISSUE_TEMPLATE/bug_report.md" "bug report\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "project"
                  :worktreeRoot root
                  :coverage {:declaredSourceKinds ["code" "python"]
                             :missingDeclaredSourceKinds ["python"]}}
        packet {:query "bug report app"
                :sourceCoverage {:schema "ygg.source-coverage.context/v1"
                                 :totals {:indexedFiles 2
                                          :skippedFiles 2
                                          :diagnostics 2}
                                 :skippedByExtension [{:extension ".bin"
                                                       :count 1}]
                                 :skippedByReason [{:reason "binary"
                                                    :count 1}]
                                 :indexedConnectivity {:indexedFiles 2
                                                       :nodes 1
                                                       :edges 0
                                                       :connectedFiles 0
                                                       :crossFileConnectedFiles 0
                                                       :isolatedFiles 2}}
                :auditScopes [{:kind "unclassified-extractor"
                               :basis "indexed-graph"
                               :supportedFiles 1
                               :skippedFiles 1
                               :facts 2
                               :diagnostics 1
                               :topEvidenceTypes [{:kind "panel"
                                                   :count 1}]
                               :samples [{:path "flows/home.panel"
                                          :section "files"}]}]
                :docs [{:source {:path ".github/ISSUE_TEMPLATE/bug_report.md"
                                 :heading "bug report"}
                        :score 10.0
                        :snippet "bug report app"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/app.clj"
                                 :heading "app"}
                        :score 1.0
                        :snippet "app"
                        :provenance "retrieved-doc"}]
                :relatedFiles [{:path "src/neighbor.clj"
                                :repoId "repo"
                                :sourceLine 1
                                :relation "imports-package"
                                :evidence ["source-graph: src/app.clj imports src/neighbor"]}]}
        hints (benchmark/context-packet->agent-hints prepared packet {})]
    (is (= ["src/app.clj"]
           (mapv :path (:topFiles hints))))
    (is (= [1]
           (mapv :rank (:topFiles hints))))
    (is (= [{:rank 1
             :path "src/neighbor.clj"
             :repoId "repo"
             :sourceLine 1
             :relation "imports-package"
             :evidence ["source-graph: src/app.clj imports src/neighbor"]}]
           (:relatedFiles hints)))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit 20
            :coverageSourceKinds ["code" "python"]}
           (:selection hints)))
    (is (= [{:kind "coverage-filtered-candidate-files"
             :severity "info"
             :message "Declared source coverage filtered candidate files out of the agent shortlist."
             :coverageSourceKinds ["code" "python"]
             :rawCandidateFiles 2
             :candidateFiles 1
             :filteredCandidateFiles 1}
            {:kind "missing-declared-source-kinds"
             :severity "warning"
             :message "The benchmark case declares source kinds with no scoreable indexed files."
             :sourceKinds ["python"]}
            {:kind "source-extraction-diagnostics"
             :severity "warning"
             :message "Indexed source coverage contains extraction diagnostics; inspect sourceCoverage.diagnostics.samples."
             :diagnostics 2}
            {:kind "source-skipped-files"
             :severity "info"
             :message "Indexed source coverage contains skipped files; inspect sourceCoverage skipped breakdowns before treating missing facts as absent."
             :skippedFiles 2
             :skippedByExtension [{:extension ".bin"
                                   :count 1}]
             :skippedByReason [{:reason "binary"
                                :count 1}]}
            {:kind "isolated-indexed-files"
             :severity "info"
             :message "Indexed source coverage contains files without active graph edges; inspect sourceCoverage.indexedConnectivity."
             :isolatedFiles 2
             :connectedFiles 0
             :crossFileConnectedFiles 0}
            {:kind "audit-scope-trust-boundary"
             :severity "warning"
             :message "Audit scope contains skipped files, extractor diagnostics, or unclassified extractor rows."
             :scope "unclassified-extractor"
             :supportedFiles 1
             :skippedFiles 1
             :diagnostics 1
             :facts 2}]
           (:diagnostics hints)))
    (is (= [{:kind "unclassified-extractor"
             :basis "indexed-graph"
             :supportedFiles 1
             :skippedFiles 1
             :facts 2
             :diagnostics 1
             :topEvidenceTypes [{:kind "panel"
                                 :count 1}]
             :samples [{:path "flows/home.panel"
                        :section "files"}]}]
           (:auditScopes hints)))
    (is (not (contains? hints :groundTruth)))
    (is (not (contains? hints :inputHints)))))

(deftest context-packet-agent-hints-preserve-search-instrumentation
  (let [prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "project"
                  :worktreeRoot "/tmp/repo"
                  :worktreeRoots {"repo" "/tmp/repo"}
                  :coverage {:declaredSourceKinds []}}
        packet {:query "connector"
                :search {:instrumentation {:grep-searches 2
                                           :grep-search-ms 17
                                           :grep-raw-matches 11}}
                :candidateFiles []
                :docs []
                :entities []
                :edges []
                :warnings []}
        hints (benchmark/context-packet->agent-hints prepared packet {})]
    (is (= {:instrumentation {:grep-searches 2
                              :grep-search-ms 17
                              :grep-raw-matches 11}}
           (:search hints)))))

(deftest context-packet-agent-result-uses-scanned-kind-for-extensionless-files
  (let [root (temp-dir "ygg-bench-extensionless-coverage")
        _ (spit-file! root "test/fast/Unit tests/nvm_remote_version"
                      "#!/usr/bin/env bash\necho ok\n")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        packet {:query "remote version app"
                :candidateFiles [{:path "test/fast/Unit tests/nvm_remote_version"
                                  :rank 1
                                  :score 1.0
                                  :targetKind :chunk
                                  :label "nvm_remote_version"}
                                 {:path "src/app.clj"
                                  :rank 2
                                  :score 1.0
                                  :targetKind :chunk
                                  :label "app"}]}
        filtered (benchmark/context-packet->agent-result
                  packet
                  {:root root
                   :coverage {:declaredSourceKinds ["shell"]}})]
    (is (= ["test/fast/Unit tests/nvm_remote_version"]
           (mapv :path (:suspectedFiles filtered))))
    (is (= [1]
           (mapv :rank (:suspectedFiles filtered))))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 1
            :coverageFilteredCandidateFiles 1
            :limit nil
            :coverageSourceKinds ["shell"]}
           (:selection filtered)))))

(deftest agent-score-matches-multi-repo-suspected-files-by-repo-id
  (let [provider-root (temp-dir "ygg-agent-score-provider")
        consumer-root (temp-dir "ygg-agent-score-consumer")]
    (spit-file! provider-root "src/contract.clj" "(ns provider.contract)\n")
    (spit-file! consumer-root "src/contract.clj" "(ns consumer.contract)\n")
    (let [prepared {:suite-id "suite"
                    :case-id "case"
                    :repo-id "provider"
                    :project-id "project"
                    :caseFingerprint "sha256:case"
                    :agentInputFingerprint "sha256:input"
                    :baseSha "provider-base"
                    :fixSha "provider-fix"
                    :worktreeRoot provider-root
                    :worktreeRoots {"provider" provider-root
                                    "consumer" consumer-root}
                    :input {:queryText "contract update"}
                    :inputHints {}
                    :coverage {}
                    :groundTruth {:changedFiles [{:repo-id "provider"
                                                  :path "src/contract.clj"}
                                                 {:repo-id "consumer"
                                                  :path "src/contract.clj"}]
                                  :unsupportedGroundTruthFiles []}}
          agent-result {:schema benchmark/agent-result-schema
                        :caseId "case"
                        :caseFingerprint "sha256:case"
                        :agentInputFingerprint "sha256:input"
                        :agentId "codex"
                        :mode "shell-only"
                        :suspectedFiles [{:repoId "consumer"
                                          :path "src/contract.clj"
                                          :rank 1
                                          :confidence 0.9
                                          :reason "consumer side"
                                          :evidence ["consumer src/contract.clj"]}
                                         {:repoId "provider"
                                          :path "src/contract.clj"
                                          :rank 2
                                          :confidence 0.8
                                          :reason "provider side"
                                          :evidence ["provider src/contract.clj"]}]
                        :suspectedSymbols []
                        :commands []
                        :warnings []
                        :summary "ranked both repos"}
          scored (benchmark-agent-score/score-agent-result prepared agent-result)]
      (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
      (is (= [{:repo-id "consumer"
               :path "src/contract.clj"
               :rank 1}
              {:repo-id "provider"
               :path "src/contract.clj"
               :rank 2}]
             (mapv #(select-keys % [:repo-id :path :rank])
                   (get-in scored [:agent :topFiles]))))
      (is (= [{:repo-id "provider"
               :path "src/contract.clj"
               :rank 2
               :found? true}
              {:repo-id "consumer"
               :path "src/contract.clj"
               :rank 1
               :found? true}]
             (mapv #(select-keys % [:repo-id :path :rank :found?])
                   (get-in scored [:groundTruthRanks :files])))))))
(deftest file-ranking-uses-mechanical-query-token-coverage
  (let [root (temp-dir "ygg-bench-token-coverage")
        _ (spit-file! root "src/early.clj" "(ns early)\n")
        _ (spit-file! root "src/later.clj" "(ns later)\n")
        packet {:query "open page root"
                :docs [{:source {:path "src/early.clj"
                                 :heading "open-handler"}
                        :score 2.0
                        :snippet "open"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/later.clj"
                                 :heading "page-root-handler"}
                        :score 1.7
                        :snippet "open page root"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/later.clj" "src/early.clj"]
           (mapv :path files)))
    (is (= 3 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 1 (get-in files [1 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
(deftest file-ranking-caps-query-token-support
  (let [root (temp-dir "ygg-bench-token-cap")
        _ (spit-file! root "src/broad.clj" "(ns broad)\n")
        _ (spit-file! root "src/strong.clj" "(ns strong)\n")
        packet {:query "one two three four five six seven eight nine ten"
                :docs [{:source {:path "src/broad.clj"
                                 :heading "one two three four five six seven eight nine ten"}
                        :score 1.0
                        :snippet "one two three four five six seven eight nine ten"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/strong.clj"
                                 :heading "one two three four five"}
                        :score 1.8
                        :snippet "one two three four five"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/strong.clj" "src/broad.clj"]
           (mapv :path files)))
    (is (= 5 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 10 (get-in files [1 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-repeated-query-matched-retrieved-source
  (let [root (temp-dir "ygg-bench-repeated-retrieved-source")
        _ (spit-file! root "src/adapter.js" "export const __setProxy = setProxy;\n")
        packet {:query "native proxy adapter boundary"
                :docs [{:source {:path "src/adapter.js"
                                 :heading "src.adapter/__setProxy"
                                 :definitionKind :var
                                 :lines [1 1]}
                        :score 1.1
                        :snippet "proxy adapter"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/adapter.js"
                                 :heading "src.adapter/__proxyBoundary"
                                 :definitionKind :var
                                 :lines [1 1]}
                        :score 1.0
                        :snippet "native proxy boundary"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        row (first (:suspectedFiles result))]
    (is (= "src/adapter.js" (:path row)))
    (is (= 2 (get-in row [:metrics :retrievedSourceCount])))
    (is (= 2 (get-in row [:metrics :docCount])))
    (is (pos? (get-in row [:metrics :repeatedRetrievedSourceBoost])))))

(deftest file-ranking-uses-retrieved-candidate-file-support
  (let [root (temp-dir "ygg-bench-candidate-files")
        _ (spit-file! root "src/seed.clj" "(ns seed)\n")
        _ (spit-file! root "src/adjacent.clj" "(ns adjacent)\n")
        packet {:query "open page root"
                :docs [{:source {:path "src/seed.clj"
                                 :heading "open-page"}
                        :score 0.5
                        :snippet "open page"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/adjacent.clj"
                                  :rank 30
                                  :score 0.7
                                  :targetKind "chunk"
                                  :label "page root"}
                                 {:path "src/adjacent.clj"
                                  :rank 38
                                  :score 0.6
                                  :targetKind "node"
                                  :label "open root"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/adjacent.clj" "src/seed.clj"]
           (mapv :path files)))
    (is (= 2 (get-in files [0 :metrics :candidateFileCount])))
    (is (= 3 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 1 (get-in files [0 :metrics :matchedTokenPairCount])))))

(deftest candidate-file-evidence-preserves-line-ranges
  (let [root (temp-dir "ygg-bench-candidate-file-lines")
        _ (spit-file! root "src/adjacent.clj" "(ns adjacent)\n(defn open-root [] nil)\n")
        packet {:query "open root"
                :candidateFiles [{:path "src/adjacent.clj"
                                  :rank 3
                                  :score 0.7
                                  :targetKind "chunk"
                                  :label "open root"
                                  :sourceLine 2
                                  :endLine 4}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["candidate-file:src/adjacent.clj rank=3 lines 2-4 targetKind=chunk label=\"open root\" score=0.7"]
           (get-in files [0 :evidence])))
    (is (= "Yggdrasil retrieved candidate file src/adjacent.clj lines 2-4 from result rank 3."
           (get-in files [0 :reason])))))

(deftest candidate-file-sibling-identity-adds-existing-base-file
  (let [root (temp-dir "ygg-bench-candidate-file-sibling")
        _ (spit-file! root "Dapper/SqlMapper.cs" "public static partial class SqlMapper {}\n")
        _ (spit-file! root "Dapper/SqlMapper.ITypeHandler.cs" "public interface ITypeHandler {}\n")
        _ (spit-file! root "Dapper/Noise.cs" "public class Noise {}\n")
        packet {:query "postgresql jsonb Dapper core type handler"
                :candidateFiles [{:path "Dapper/SqlMapper.ITypeHandler.cs"
                                  :rank 7
                                  :score 10.2
                                  :targetKind "chunk"
                                  :label "Dapper/SqlMapper"
                                  :supportLabels ["Dapper"
                                                  "Dapper/ITypeHandler.SetValue"]
                                  :scoreComponents {:sourceGraph 10.2
                                                    :lexical 0.61
                                                    :grep 0.002
                                                    :sameLabel 1.0
                                                    :exact 0.2}}
                                 {:path "Dapper/Noise.cs"
                                  :rank 8
                                  :score 9.0
                                  :targetKind "file"
                                  :label "Dapper/Noise"
                                  :scoreComponents {:sourceGraph 9.0
                                                    :lexical 0.55}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)
        file-by-path (into {} (map (juxt :path identity)) files)
        sibling (get file-by-path "Dapper/SqlMapper.cs")
        partial (get file-by-path "Dapper/SqlMapper.ITypeHandler.cs")]
    (is sibling)
    (is (< (:rank sibling) (:rank partial)))
    (is (str/includes? (first (:evidence sibling))
                       "candidate-file-sibling:Dapper/SqlMapper.cs"))
    (is (= 3 (get-in sibling [:metrics :matchedTokenCount])))
    (is (pos? (get-in sibling [:metrics :candidateSourceRank])))))

(deftest candidate-file-support-labels-contribute-to-file-ranking
  (let [root (temp-dir "ygg-bench-candidate-support-labels")
        _ (spit-file! root "src/candidate.cs" "namespace Demo.Tests;\n")
        packet {:query "type handler regression"
                :candidateFiles [{:path "src/candidate.cs"
                                  :rank 3
                                  :score 0.5
                                  :targetKind "node"
                                  :label "Demo.Tests"
                                  :supportLabels ["Demo.Tests/TypeHandlerTests.Regression"]}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        row (first (:suspectedFiles result))]
    (is (= "src/candidate.cs" (:path row)))
    (is (= 3 (get-in row [:metrics :matchedTokenCount])))
    (is (= 1 (get-in row [:metrics :matchedCompoundTokenPairCount])))
    (is (str/includes? (first (:evidence row))
                       "supportLabels=[\"Demo.Tests/TypeHandlerTests.Regression\"]"))))

(deftest file-ranking-caps-repeated-file-support-bonus
  (let [root (temp-dir "ygg-bench-repeated-file-support")
        _ (spit-file! root "src/early.tf" "resource \"demo\" \"early\" {}\n")
        _ (spit-file! root "src/repeated.tf" "variable \"demo\" {}\n")
        packet {:query "flow log policy stream"
                :docs (concat
                       [{:source {:path "src/early.tf"
                                  :heading "src/early.tf"}
                         :score 1.0
                         :snippet "flow log policy stream"
                         :retrievedSource true
                         :provenance "retrieved-doc"}]
                       (for [idx (range 7)]
                         {:source {:path "src/repeated.tf"
                                   :heading (str "var.repeated_" idx)}
                          :score 0.95
                          :snippet "flow log policy"
                          :retrievedSource true
                          :provenance "retrieved-doc"}))}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/early.tf" "src/repeated.tf"]
           (mapv :path files)))
    (is (= 7 (get-in files [1 :metrics :docCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
(deftest file-ranking-uses-mechanical-graph-neighbor-evidence
  (let [root (temp-dir "ygg-bench-candidate-graph")
        _ (spit-file! root "src/direct.clj" "(ns direct)\n")
        _ (spit-file! root "src/importing.clj" "(ns importing)\n")
        packet {:query "stream context"
                :candidateFiles [{:path "src/direct.clj"
                                  :rank 5
                                  :score 0.7
                                  :targetKind "node"
                                  :label "direct stream context"
                                  :scoreComponents {:lexical 0.7
                                                    :graph 0.0}}
                                 {:path "src/importing.clj"
                                  :rank 50
                                  :score 0.35
                                  :targetKind "node"
                                  :label "importing stream context"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/importing.clj" "src/direct.clj"]
           (mapv :path files)))
    (is (= ["candidate-file:src/importing.clj rank=50 targetKind=node label=\"importing stream context\" score=0.35 components=graph:0.6,lexical:0.2"]
           (get-in files [0 :evidence])))
    (is (not (contains? (first files) :graph-neighbor-score)))
    (is (= 0.6 (get-in files [0 :metrics :graphNeighborScore])))
    (is (= 2 (get-in files [0 :metrics :matchedTokenCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
(deftest file-ranking-does-not-stack-rare-token-score-on-graph-neighbor
  (let [root (temp-dir "ygg-bench-rare-token-graph-neighbor")
        _ (spit-file! root "src/importing.clj" "(ns importing)\n")
        packet {:query "stream context uncommon"
                :candidateFiles [{:path "src/importing.clj"
                                  :rank 50
                                  :score 0.35
                                  :targetKind "node"
                                  :label "importing stream context uncommon"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "src/importing.clj" (:path file)))
    (is (= 0.6 (get-in file [:metrics :graphNeighborScore])))
    (is (not (contains? (:metrics file) :rareQueryTokenScore)))))
(deftest file-ranking-uses-architecture-runtime-evidence
  (let [root (temp-dir "ygg-bench-architecture-runtime")
        _ (spit-file! root "config/runtime.env" "DATABASE_URL=postgres://example\n")
        packet {:query "database runtime config"
                :architecture {:runtimeEvidence [{:id "evidence:database-url"
                                                  :path "config/runtime.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :normalizedValue "database-url"
                                                  :score 1.2}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["config/runtime.env"]
           (mapv :path files)))
    (is (= ["architecture-evidence:runtimeEvidence:config/runtime.env kind=env-var fileKind=env label=\"DATABASE_URL\" score=1.2"]
           (get-in files [0 :evidence])))
    (is (= "Yggdrasil architecture runtimeEvidence row evidence:database-url references config/runtime.env."
           (get-in files [0 :reason])))
    (is (= {:firstSourceRank 701
            :supportCount 1
            :docCount 0
            :entityCount 0
            :candidateFileCount 1
            :retrievedSourceCount 0
            :exactPathSourceCount 0
            :maxConfidence 1.0
            :rankScore 4.589
            :rareQueryTokenScore 3.2
            :matchedTokenCount 3
            :matchedPathQueryTokenCount 2
            :architectureSupportBoost 0.189
            :definitionKinds ["env-var"]}
           (get-in files [0 :metrics])))))

(deftest file-ranking-boosts-query-supported-architecture-and-candidate-support
  (let [root (temp-dir "ygg-bench-architecture-candidate-support")
        _ (doseq [path ["migrations/schema.sql"
                        "migrations/schema-15.sql"
                        "migrations/db/init-scripts/00000000000003-post-setup.sql"
                        "migrations/.env"
                        "noise/.env"]]
            (spit-file! root path "fixture\n"))
        packet {:query "database runtime config post setup ownership trigger"
                :docs [{:source {:path "migrations/schema.sql"
                                 :heading "database schema"
                                 :definitionKind :file
                                 :lines [1 1]}
                        :score 1.6
                        :snippet "database schema"
                        :provenance "retrieved-doc"}
                       {:source {:path "migrations/schema-15.sql"
                                 :heading "database schema 15"
                                 :definitionKind :file
                                 :lines [1 1]}
                        :score 1.59
                        :snippet "database schema"
                        :provenance "retrieved-doc"}
                       {:source {:path "migrations/db/init-scripts/00000000000003-post-setup.sql"
                                 :heading "database post setup"
                                 :definitionKind :file
                                 :lines [1 1]}
                        :score 1.45
                        :snippet "database post setup"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "noise/.env"
                                  :rank 3
                                  :score 3.375
                                  :targetKind "file"
                                  :label "noise/.env"
                                  :supportLabels ["LOG_LEVEL" "PORT"]
                                  :scoreComponents {:sourceGraph 3.375
                                                    :lexical 0.46
                                                    :graph 0.0
                                                    :exact 0.2}}
                                 {:path "migrations/.env"
                                  :rank 13
                                  :score 3.3
                                  :targetKind "node"
                                  :label "DATABASE_URL"
                                  :supportLabels ["migrations/.env"]
                                  :scoreComponents {:sourceGraph 3.3
                                                    :lexical 0.56
                                                    :graph 0.0
                                                    :exact 0.15}}
                                 {:path "migrations/schema.sql"
                                  :rank 71
                                  :score 1.25
                                  :targetKind "file"
                                  :label "migrations/schema.sql"
                                  :scoreComponents {:sourceGraph 1.25
                                                    :lexical 0.99
                                                    :graph 0.0
                                                    :exact 0.1}}
                                 {:path "migrations/schema-15.sql"
                                  :rank 73
                                  :score 1.2
                                  :targetKind "file"
                                  :label "migrations/schema-15.sql"
                                  :scoreComponents {:sourceGraph 1.2
                                                    :lexical 1.0
                                                    :graph 0.0
                                                    :exact 0.1}}
                                 {:path "migrations/db/init-scripts/00000000000003-post-setup.sql"
                                  :rank 53
                                  :score 2.222222222222222
                                  :targetKind "file"
                                  :label "migrations/db/init-scripts/00000000000003-post-setup.sql"
                                  :scoreComponents {:sourceGraph 2.222222222222222
                                                    :lexical 0.82
                                                    :graph 0.0
                                                    :exact 0.15}}]
                :architecture {:runtimeEvidence [{:id "evidence:database-url"
                                                  :path "migrations/.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :normalizedValue "database-url"
                                                  :score 2.6}]}}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :coverage {:declaredSourceKinds ["env" "sql"]}})
        file-by-path (into {} (map (juxt :path identity)) (:suspectedFiles result))
        runtime-file (get file-by-path "migrations/.env")
        setup-file (get file-by-path "migrations/db/init-scripts/00000000000003-post-setup.sql")
        noise-file (get file-by-path "noise/.env")
        schema-file (get file-by-path "migrations/schema.sql")]
    (is (= 1 (:rank runtime-file)))
    (is (< (:rank runtime-file) (:rank noise-file)))
    (is (< (:rank setup-file) (:rank schema-file)))
    (is (pos? (get-in runtime-file [:metrics :architectureSupportBoost])))
    (is (pos? (get-in setup-file
                      [:metrics :docSupportedCandidateEvidenceBoost])))))

(deftest file-ranking-caps-mixed-architecture-support-boost
  (let [boost @#'benchmark-prediction/architecture-support-boost]
    (is (= 0.0 (boost 1 1 3.0)))
    (is (= 1.25 (boost 2 1 4.0)))))

(deftest file-ranking-frontloads-query-supported-architecture-only-evidence
  (let [root (temp-dir "ygg-bench-architecture-only-query-support")
        _ (doseq [path ["tests/Dapper.Tests/MiscTests.cs"
                        "tests/Dapper.Tests/TypeHandlerTests.cs"
                        "Dapper/SqlMapper.cs"
                        "tests/docker-compose.yml"]]
            (spit-file! root path "fixture\n"))
        packet {:query "postgresql jsonb test container Dapper core type handler"
                :docs [{:source {:path "tests/Dapper.Tests/MiscTests.cs"
                                 :heading "MiscTests"
                                 :definitionKind :method
                                 :lines [1 1]}
                        :score 3.5
                        :snippet "postgresql jsonb test container Dapper core type handler"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
                                 :heading "TypeHandlerTests"
                                 :definitionKind :property
                                 :lines [1 1]}
                        :score 1.7
                        :snippet "type handler regression"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :architecture {:runtimeEvidence [{:id "evidence:sqlmapper-url"
                                                  :path "Dapper/SqlMapper.cs"
                                                  :kind "url"
                                                  :fileKind "dotnet"
                                                  :label "Dapper core type handler"
                                                  :normalizedValue "dapper-core-type-handler"
                                                  :score 5.95}
                                                 {:id "evidence:compose-runtime"
                                                  :path "tests/docker-compose.yml"
                                                  :kind "container-image-consumer"
                                                  :fileKind "compose"
                                                  :label "postgresql test container"
                                                  :normalizedValue "container-image:postgresql"
                                                  :score 5.15}]
                               :deployEvidence [{:id "evidence:compose-deploy"
                                                 :path "tests/docker-compose.yml"
                                                 :kind "container-image-consumer"
                                                 :fileKind "compose"
                                                 :label "postgresql test container"
                                                 :normalizedValue "container-image:postgresql"
                                                 :score 5.15}]}}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :limit 4
                 :coverage {:declaredSourceKinds ["compose" "dotnet"]}})
        file-by-path (into {} (map (juxt :path identity)) (:suspectedFiles result))
        compose-file (get file-by-path "tests/docker-compose.yml")
        core-file (get file-by-path "Dapper/SqlMapper.cs")]
    (is (<= (:rank compose-file) 2))
    (is (< (:rank core-file)
           (:rank (get file-by-path "tests/Dapper.Tests/TypeHandlerTests.cs"))))
    (is (pos? (get-in compose-file
                      [:metrics :architectureSupportBoost])))
    (is (pos? (get-in core-file
                      [:metrics :architectureSupportBoost])))))

(deftest file-ranking-normalizes-architecture-evidence-families
  (let [root (temp-dir "ygg-bench-architecture-evidence-family-score")
        _ (spit-file! root "lib/adapters/http.js" "import proxyFromEnv from 'proxy-from-env';\n")
        _ (spit-file! root "tests/unit/adapters/fetch.test.js" "process.env.SERVER_PORT = '3000';\n")
        packet {:query (str "Locate the boundary between Node native proxy handling and Axios "
                            "proxy rewriting. Return the adapter and tests and cite "
                            "environment-variable evidence.")
                :architecture {:runtimeEvidence [{:id "evidence:server-port"
                                                  :path "tests/unit/adapters/fetch.test.js"
                                                  :kind "env-var"
                                                  :fileKind "javascript"
                                                  :label "SERVER_PORT"
                                                  :normalizedValue "server-port"
                                                  :score 5.35}]
                               :dependencyEvidence [{:id "node:pkg:proxy-from-env:import:lib/adapters/http.js:5"
                                                     :path "lib/adapters/http.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :package "proxy-from-env"
                                                     :label "npm:proxy-from-env"
                                                     :ecosystem "npm"
                                                     :relation "imports-package"
                                                     :sourceLine 5
                                                     :score 1.75}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["lib/adapters/http.js" "tests/unit/adapters/fetch.test.js"]
           (mapv :path files)))
    (is (= ["architecture-evidence:dependencyEvidence:lib/adapters/http.js kind=package-import fileKind=javascript relation=imports-package label=\"npm:proxy-from-env\" package=\"proxy-from-env\" score=1.75"]
           (get-in files [0 :evidence])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-treats-multiple-package-identity-tokens-as-query-supported
  (let [root (temp-dir "ygg-bench-package-identity-token-support")
        _ (spit-file! root "lib/adapters/http.js"
                      "import proxyFromEnv from 'proxy-from-env';\n")
        _ (spit-file! root "tests/unit/fetch.test.js"
                      "import axios from 'axios';\n")
        packet {:query "proxy from env boundary"
                :architecture {:dependencyEvidence [{:id "node:pkg:proxy-from-env:import:lib/adapters/http.js:5"
                                                     :path "lib/adapters/http.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :package "proxy-from-env"
                                                     :label "npm:proxy-from-env"
                                                     :ecosystem "npm"
                                                     :relation "imports-package"
                                                     :sourceLine 5
                                                     :score 1.75}
                                                    {:id "node:pkg:axios:import:tests/unit/fetch.test.js:1"
                                                     :path "tests/unit/fetch.test.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :package "axios"
                                                     :label "npm:axios"
                                                     :ecosystem "npm"
                                                     :relation "imports-package"
                                                     :sourceLine 1
                                                     :score 1.75}]}}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet {:root root}))
        file-by-path (into {} (map (juxt :path identity)) files)
        proxy-file (get file-by-path "lib/adapters/http.js")
        axios-file (get file-by-path "tests/unit/fetch.test.js")]
    (is (= "lib/adapters/http.js" (:path (first files))))
    (is (pos? (get-in proxy-file [:metrics :architectureSupportBoost])))
    (is (not (contains? (:metrics axios-file) :architectureSupportBoost)))))

(deftest architecture-file-ranking-computes-package-identity-token-count-once
  (let [root (temp-dir "ygg-bench-package-identity-token-count")
        _ (spit-file! root "lib/adapters/http.js"
                      "import proxyFromEnv from 'proxy-from-env';\n")
        token-count @#'benchmark-prediction/dependency-package-identity-token-count
        calls (atom 0)]
    (with-redefs-fn {#'benchmark-prediction/dependency-package-identity-token-count
                     (fn [query-tokens row]
                       (swap! calls inc)
                       (token-count query-tokens row))}
      (fn []
        (let [file (-> (benchmark/context-packet->agent-result
                        {:query "proxy from env boundary"
                         :architecture {:dependencyEvidence
                                        [{:id "node:pkg:proxy-from-env"
                                          :path "lib/adapters/http.js"
                                          :kind "package-import"
                                          :fileKind "javascript"
                                          :package "proxy-from-env"
                                          :label "npm:proxy-from-env"
                                          :ecosystem "npm"
                                          :relation "imports-package"
                                          :score 1.75}]}}
                        {:root root})
                       :suspectedFiles
                       first)]
          (is (= "lib/adapters/http.js" (:path file)))
          (is (= 1 @calls))
          (is (pos? (get-in file [:metrics :architectureSupportBoost]))))))))

(deftest file-ranking-boosts-query-supported-candidate-only-architecture-support
  (let [root (temp-dir "ygg-bench-candidate-only-architecture-support")
        _ (spit-file! root "tests/setup/server.js" "export const server = true;\n")
        _ (spit-file! root "tests/unit/adapters/http.test.js" "import {HttpsProxyAgent} from 'https-proxy-agent';\n")
        packet {:query (str "node native proxy boundary http adapter tests close server "
                            "environment variable evidence")
                :docs [{:source {:path "tests/setup/server.js"
                                 :heading "node proxy server"
                                 :definitionKind :var}
                        :score 1.5
                        :snippet "node proxy server"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "tests/unit/adapters/http.test.js"
                                  :rank 32
                                  :score 3.214285714285714
                                  :targetKind "node"
                                  :label "tests.unit.adapters.http.test/createHttp2Axios"
                                  :supportLabels ["tests.unit.adapters.http.test/closeServer"
                                                  "tests.unit.adapters.http.test/stop"
                                                  "tests.unit.adapters.http.test/startServer"
                                                  "tests.unit.adapters.http.test"]
                                  :scoreComponents {:sourceGraph 3.214285714285714
                                                    :lexical 0.64}}]
                :architecture {:dependencyEvidence [{:id "node:pkg:https-proxy-agent"
                                                     :path "tests/unit/adapters/http.test.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :package "https-proxy-agent"
                                                     :label "npm:https-proxy-agent"
                                                     :ecosystem "npm"
                                                     :relation "imports-package"
                                                     :score 1.75}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/unit/adapters/http.test.js"
            "tests/setup/server.js"]
           (mapv :path files)))
    (is (< 3.0 (get-in files [0 :metrics :architectureSupportBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-architecture-deploy-evidence
  (let [root (temp-dir "ygg-bench-architecture-deploy")
        _ (spit-file! root "tests/docker-compose.yml" "services:\n  db:\n    image: postgres:alpine\n")
        packet {:query "postgres container runtime setup"
                :architecture {:deployEvidence [{:id "evidence:postgres-container"
                                                 :path "tests/docker-compose.yml"
                                                 :kind "container-image-consumer"
                                                 :fileKind "compose"
                                                 :label "postgres:alpine"
                                                 :normalizedValue "container-image:postgres"
                                                 :score 1.4}]}}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/docker-compose.yml"]
           (mapv :path files)))
    (is (= ["architecture-evidence:deployEvidence:tests/docker-compose.yml kind=container-image-consumer fileKind=compose label=\"postgres:alpine\" score=1.4"]
           (get-in files [0 :evidence])))))

(deftest file-ranking-recognizes-compacted-architecture-candidate-evidence
  (let [root (temp-dir "ygg-bench-compacted-architecture-candidate")
        _ (spit-file! root "tests/docker-compose.yml" "services:\n  db:\n    image: postgres:alpine\n")
        packet {:query "postgres container runtime setup"
                :candidateFiles [{:path "tests/docker-compose.yml"
                                  :rank 99
                                  :score 5.15
                                  :kind "compose"
                                  :targetKind "file"
                                  :label "postgres:alpine"
                                  :supportLabels ["deployEvidence"
                                                  "container-image-consumer"
                                                  "postgres:alpine"
                                                  "container-image:postgres"]
                                  :architectureEvidence true
                                  :architectureSection "deployEvidence"
                                  :architectureKind "container-image-consumer"
                                  :scoreComponents {:sourceGraph 5.15}}]}
        file (-> (benchmark/context-packet->agent-result packet {:root root})
                 :suspectedFiles
                 first)]
    (is (= "tests/docker-compose.yml" (:path file)))
    (is (= ["container-image-consumer"]
           (get-in file [:metrics :definitionKinds])))
    (is (pos? (get-in file [:metrics :architectureSupportBoost])))))
(deftest file-ranking-requires-lexical-support-for-graph-neighbor-boost
  (let [root (temp-dir "ygg-bench-candidate-graph-support")
        _ (spit-file! root "src/thin.clj" "(ns thin)\n")
        _ (spit-file! root "src/supported.clj" "(ns supported)\n")
        packet {:query "open page root"
                :candidateFiles [{:path "src/thin.clj"
                                  :rank 1
                                  :score 0.7
                                  :targetKind "node"
                                  :label "open"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.8}}
                                 {:path "src/supported.clj"
                                  :rank 2
                                  :score 0.6
                                  :targetKind "node"
                                  :label "open page"
                                  :scoreComponents {:lexical 0.3
                                                    :graph 0.5}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/supported.clj" "src/thin.clj"]
           (mapv :path files)))
    (is (nil? (get-in files [1 :metrics :graphNeighborScore])))
    (is (= 0.5 (get-in files [0 :metrics :graphNeighborScore])))))
(deftest file-ranking-uses-mechanical-graph-and-lexical-score-support
  (let [root (temp-dir "ygg-bench-candidate-graph-lexical-support")
        _ (spit-file! root "src/plain.js" "export const plain = true;\n")
        _ (spit-file! root "src/graph.js" "export const graph = true;\n")
        packet {:query "native proxy boundary"
                :candidateFiles [{:path "src/plain.js"
                                  :rank 1
                                  :score 0.9
                                  :targetKind "node"
                                  :label "plain"
                                  :scoreComponents {:lexical 0.2
                                                    :graph 0.0}}
                                 {:path "src/graph.js"
                                  :rank 50
                                  :score 0.65
                                  :targetKind "node"
                                  :label "graph edge"
                                  :scoreComponents {:lexical 0.45
                                                    :graph 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/graph.js" "src/plain.js"]
           (mapv :path files)))
    (is (= 0.6 (get-in files [0 :metrics :graphNeighborScore])))
    (is (pos? (get-in files [0 :metrics :graphNeighborBoost])))))
(deftest file-ranking-bounds-candidate-only-graph-boost-by-evidence
  (let [root (temp-dir "ygg-bench-candidate-graph-bounds")
        _ (spit-file! root "src/direct.clj" "(ns direct)\n")
        _ (spit-file! root "src/late-neighbor.clj" "(ns late-neighbor)\n")
        packet {:query "console unique id selector"
                :docs [{:source {:path "src/direct.clj"
                                 :heading "direct unique id selector"}
                        :score 1.0
                        :snippet "unique id selector"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/late-neighbor.clj"
                                  :rank 100
                                  :score 0.35
                                  :targetKind "node"
                                  :label "console selector"
                                  :scoreComponents {:lexical 0.05
                                                    :graph 1.0}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/direct.clj" "src/late-neighbor.clj"]
           (mapv :path files)))
    (is (= 1.0 (get-in files [1 :metrics :graphNeighborScore])))
    (is (< (get-in files [1 :metrics :graphNeighborBoost])
           (get-in files [1 :metrics :graphNeighborScore])))))
(deftest file-ranking-uses-ordered-query-token-pairs-in-candidate-labels
  (let [root (temp-dir "ygg-bench-candidate-token-pairs")
        _ (spit-file! root "src/scattered.clj" "(ns scattered)\n")
        _ (spit-file! root "src/phrase.clj" "(ns phrase)\n")
        packet {:query "remote version descriptions"
                :candidateFiles [{:path "src/scattered.clj"
                                  :rank 1
                                  :score 0.8
                                  :targetKind "chunk"
                                  :label "remote docs version"}
                                 {:path "src/phrase.clj"
                                  :rank 2
                                  :score 0.55
                                  :targetKind "chunk"
                                  :label "remote version"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/phrase.clj" "src/scattered.clj"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedTokenPairCount])))
    (is (nil? (get-in files [1 :metrics :matchedTokenPairCount])))))
(deftest file-ranking-uses-compound-identifier-query-token-pairs
  (let [root (temp-dir "ygg-bench-compound-token-pairs")
        target "test/fast/Unit tests/nvm_remote_version"
        noisy-paths (mapv #(str "test/fast/Running 'nvm use "
                                %
                                "' should not create the current symlink")
                          (range 24))
        _ (doseq [path (cons target noisy-paths)]
            (spit-file! root path "#!/bin/sh\n"))
        packet {:query "nvm install argon should not remote version"
                :candidateFiles (vec
                                 (concat
                                  (map-indexed
                                   (fn [idx path]
                                     {:path path
                                      :rank (inc idx)
                                      :score 0.42
                                      :targetKind "chunk"
                                      :label path})
                                   noisy-paths)
                                  [{:path target
                                    :rank 99
                                    :score 0.40
                                    :targetKind "chunk"
                                    :label "nvm_remote_version"}]))}
        result (benchmark/context-packet->agent-result packet {:root root
                                                               :limit 20})
        files (:suspectedFiles result)
        target-row (some #(when (= target (:path %)) %) files)]
    (is (some? target-row))
    (is (= 1 (get-in target-row [:metrics :matchedCompoundTokenPairCount])))
    (is (<= (:rank target-row) 20))))
(deftest file-ranking-uses-identity-compound-token-pairs
  (let [root (temp-dir "ygg-bench-identity-compound-token-pairs")
        _ (spit-file! root "scss/forms/_input-group.scss" ".input-group {}\n")
        _ (spit-file! root "scss/forms/_form-select.scss" ".form-select {}\n")
        packet {:query "form select border radius"
                :candidateFiles [{:path "scss/forms/_input-group.scss"
                                  :rank 1
                                  :score 1.4
                                  :targetKind "chunk"
                                  :label "form select small large border radius"}
                                 {:path "scss/forms/_form-select.scss"
                                  :rank 2
                                  :score 0.9
                                  :targetKind "chunk"
                                  :label ".form-select"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["scss/forms/_input-group.scss" "scss/forms/_form-select.scss"]
           (mapv :path files)))
    (is (= 1 (get-in files [1 :metrics :matchedIdentityCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-identity-compound-token-spans
  (let [root (temp-dir "ygg-bench-identity-compound-token-spans")
        _ (spit-file! root "src/TargetEngineDescriptor.java" "class TargetEngineDescriptor {}\n")
        _ (spit-file! root "src/GenericDescriptor.java" "class GenericDescriptor {}\n")
        packet {:query "Trace TargetEngineDescriptor ownership"
                :candidateFiles [{:path "src/GenericDescriptor.java"
                                  :rank 1
                                  :score 1.1
                                  :targetKind "node"
                                  :label "GenericDescriptor.prepare"
                                  :supportLabels ["generic engine descriptor"]
                                  :scoreComponents {:lexical 0.6
                                                    :graph 1.0}}
                                 {:path "src/TargetEngineDescriptor.java"
                                  :rank 2
                                  :score 0.9
                                  :targetKind "node"
                                  :label "TargetEngineDescriptor.cleanUp"
                                  :supportLabels ["TargetEngineDescriptor"]
                                  :scoreComponents {:lexical 0.7
                                                    :graph 0.0}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/TargetEngineDescriptor.java" "src/GenericDescriptor.java"]
           (mapv :path files)))
    (is (<= 3 (get-in files [0 :metrics :matchedIdentityCompoundTokenSpanLength])))
    (is (pos? (get-in files [0 :metrics :identityCompoundTokenSpanScore])))))

(deftest file-ranking-uses-doc-identity-compound-token-pairs
  (let [root (temp-dir "ygg-bench-doc-identity-compound-token-pairs")
        _ (spit-file! root "tests/Demo/TypeHandlerTests.cs" "class TypeHandlerTests {}\n")
        _ (spit-file! root "tests/Demo/MiscTests.cs" "class MiscTests {}\n")
        packet {:query "type handler regression tests"
                :docs [{:source {:path "tests/Demo/MiscTests.cs"
                                 :heading "handler regression"}
                        :score 1.4
                        :snippet "handler regression"
                        :provenance "retrieved-doc"}
                       {:source {:path "tests/Demo/TypeHandlerTests.cs"
                                 :heading "RecordingTypeHandler.ParseWasCalled"}
                        :score 0.8
                        :snippet "parse was called"
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/Demo/TypeHandlerTests.cs" "tests/Demo/MiscTests.cs"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedIdentityCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-doc-supported-compound-token-pairs
  (let [root (temp-dir "ygg-bench-doc-compound-token-pairs")
        _ (spit-file! root "migrations/schema.sql" "-- schema\n")
        _ (spit-file! root "migrations/db/init-scripts/post-setup.sql" "-- setup\n")
        packet {:query "post setup"
                :docs [{:source {:path "migrations/schema.sql"
                                 :heading "setup"}
                        :score 1.0
                        :snippet "setup"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "migrations/db/init-scripts/post-setup.sql"
                                 :heading "post setup"}
                        :score 0.85
                        :snippet "post setup"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["migrations/db/init-scripts/post-setup.sql"
            "migrations/schema.sql"]
           (mapv :path files)))
    (is (= 1 (get-in files [0 :metrics :matchedCompoundTokenPairCount])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-retrieved-long-identity-spans
  (let [root (temp-dir "ygg-bench-long-identity-span")
        _ (spit-file! root "src/JupiterEngineExecutionContext.java" "class JupiterEngineExecutionContext {}\n")
        _ (spit-file! root "tests/ExecutionLifecycleTests.java" "class ExecutionLifecycleTests {}\n")
        packet {:query "JupiterEngineExecutionContext lifecycle state"
                :docs [{:source {:path "tests/ExecutionLifecycleTests.java"
                                 :heading "ExecutionLifecycleTests"}
                        :score 4.0
                        :snippet "lifecycle state"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/JupiterEngineExecutionContext.java"
                                 :heading "JupiterEngineExecutionContext"}
                        :score 1.0
                        :snippet "JupiterEngineExecutionContext lifecycle state"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/JupiterEngineExecutionContext.java"
            "tests/ExecutionLifecycleTests.java"]
           (mapv :path files)))
    (is (<= 4 (get-in files [0 :metrics :matchedIdentityCompoundTokenSpanLength])))
    (is (pos? (get-in files [0 :metrics :retrievedLongIdentityCompoundTokenSpanScore])))
    (is (pos? (get-in files [0 :metrics :retrievedEarlyLongIdentityCompoundTokenSpanScore])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-uses-candidate-support-label-density
  (let [root (temp-dir "ygg-bench-candidate-support-label-density")
        _ (spit-file! root "src/high-score.js" "export const value = 1;\n")
        _ (spit-file! root "src/dense-support.js" "export const value = 2;\n")
        packet {:query "adapter lifecycle"
                :candidateFiles [{:path "src/high-score.js"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "node"
                                  :label "adapter lifecycle"
                                  :supportLabels ["support-a"
                                                  "support-b"
                                                  "support-c"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.6}}
                                 {:path "src/dense-support.js"
                                  :rank 2
                                  :score 0.95
                                  :targetKind "node"
                                  :label "adapter lifecycle"
                                  :supportLabels ["support-a"
                                                  "support-b"
                                                  "support-c"
                                                  "support-d"]
                                  :scoreComponents {:sourceGraph 0.95
                                                    :lexical 0.6}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/dense-support.js"
            "src/high-score.js"]
           (mapv :path files)))
    (is (= 4 (get-in files [0 :metrics :candidateSupportLabelCount])))
    (is (pos? (get-in files [0 :metrics :candidateSupportLabelScore])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-retrieved-direct-file-identity-support
  (let [root (temp-dir "ygg-bench-direct-file-identity-support")
        _ (spit-file! root "src/noisy.cs" "public class Noisy {}\n")
        _ (spit-file! root "src/SqlMapper.cs" "public class SqlMapper {}\n")
        packet {:query "jsonb string type handler"
                :docs [{:source {:path "src/noisy.cs"
                                 :heading "jsonb string type handler"}
                        :score 3.0
                        :snippet "jsonb string type handler"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/SqlMapper.cs"
                                 :heading "type handler"}
                        :score 0.5
                        :snippet "jsonb type handler"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/noisy.cs"
                                  :rank 1
                                  :score 4.0
                                  :targetKind "node"
                                  :label "jsonb string type handler"
                                  :supportLabels ["Fixture/NoisyJsonb"
                                                  "Fixture/NoisyString"
                                                  "Fixture/NoisyHandler"]
                                  :scoreComponents {:sourceGraph 4.0
                                                    :lexical 1.0}}
                                 {:path "src/SqlMapper.cs"
                                  :rank 80
                                  :score 1.5
                                  :targetKind "file"
                                  :label "src/SqlMapper.cs"
                                  :supportLabels ["Fixture/SqlMapper.ReadJsonb"
                                                  "Fixture/SqlMapper.GetString"
                                                  "Fixture/SqlMapper.TypeHandler"
                                                  "Fixture/SqlMapper.Parse"]
                                  :scoreComponents {:sourceGraph 1.5
                                                    :graph 0.2
                                                    :lexical 0.4}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/SqlMapper.cs" "src/noisy.cs"]
           (mapv :path files)))
    (is (= 4 (get-in files [0 :metrics :fileIdentitySupportLabelCount])))
    (is (pos? (get-in files [0 :metrics :directFileIdentitySupportBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-does-not-boost-weak-late-direct-file-identity-support
  (let [root (temp-dir "ygg-bench-weak-late-direct-file-identity-support")
        _ (spit-file! root "tests/unit/adapters/errorDetails.test.js" "describe('error', () => {})\n")
        packet {:query "native proxy boundary http adapter tests"
                :docs [{:source {:path "tests/unit/adapters/errorDetails.test.js"
                                 :heading "adapter error details"}
                        :score 1.0
                        :snippet "native proxy boundary adapter tests"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "tests/unit/adapters/errorDetails.test.js"
                                  :rank 96
                                  :score 1.0
                                  :targetKind "file"
                                  :label "tests/unit/adapters/errorDetails.test.js"
                                  :supportLabels ["tests.unit.adapters.errorDetails.test/ErrorDetails"
                                                  "tests.unit.adapters.errorDetails.test/errorBoundary"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :graph 0.2
                                                    :lexical 0.7}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "tests/unit/adapters/errorDetails.test.js" (:path file)))
    (is (= 2 (get-in file [:metrics :fileIdentitySupportLabelCount])))
    (is (not (contains? (:metrics file)
                        :directFileIdentitySupportBoost)))))

(deftest file-ranking-counts-candidate-only-dotted-direct-file-identity-support
  (let [root (temp-dir "ygg-bench-dotted-direct-file-identity-support")
        _ (spit-file! root "src/Noise.cs" "public class Noise {}\n")
        _ (spit-file! root "src/SqlMapper.Settings.cs" "public class Settings {}\n")
        packet {:query "prefer enum typehandlers settings"
                :candidateFiles [{:path "src/Noise.cs"
                                  :rank 2
                                  :score 4.0
                                  :targetKind "file"
                                  :label "src/Noise.cs"
                                  :supportLabels ["Fixture/Noise.PreferEnum"
                                                  "Fixture/Noise.TypeHandlers"]
                                  :scoreComponents {:sourceGraph 4.0
                                                    :graph 0.2
                                                    :lexical 0.9}}
                                 {:path "src/SqlMapper.Settings.cs"
                                  :rank 80
                                  :score 1.0
                                  :targetKind "file"
                                  :label "src/SqlMapper.Settings.cs"
                                  :supportLabels ["Fixture/Settings.PreferEnumTypeHandlers"
                                                  "Fixture/Settings.Settings"
                                                  "Fixture/Settings.TypeHandlers"
                                                  "Fixture/Settings.CommandTimeout"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :graph 0.2
                                                    :lexical 0.5}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files-by-path (into {} (map (juxt :path identity)) (:suspectedFiles result))]
    (is (= 4 (get-in files-by-path
                     ["src/SqlMapper.Settings.cs"
                      :metrics
                      :fileIdentitySupportLabelCount])))
    (is (not (contains? (get-in files-by-path
                                ["src/SqlMapper.Settings.cs" :metrics])
                        :directFileIdentitySupportBoost)))))

(deftest file-ranking-frontloads-candidate-only-dotted-stem-part-source-graph-evidence
  (let [root (temp-dir "ygg-bench-dotted-stem-part-source-graph")
        _ (spit-file! root "tests/Dapper.Tests/ParameterTests.cs" "public class ParameterTests {}\n")
        _ (spit-file! root "tests/Dapper.Tests/EnumTests.cs" "public class EnumTests {}\n")
        _ (spit-file! root "tests/Dapper.Tests/TypeHandlerTests.cs" "public class TypeHandlerTests {}\n")
        _ (spit-file! root "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs" "public class BelgradeBenchmarks {}\n")
        _ (spit-file! root "benchmarks/Dapper.Tests.Performance/Benchmarks.cs" "public class Benchmarks {}\n")
        _ (spit-file! root "Dapper/SqlMapper.Settings.cs" "public static class Settings {}\n")
        packet {:query (str "Fix/prefer typehandlers for enums in Dapper. The fix added "
                            "an opt-in setting so registered enum TypeHandlers "
                            "are preferred before default enum boxing, with "
                            "changes in core mapping/settings code and "
                            "regression tests.")
                :docs [{:source {:path "tests/Dapper.Tests/ParameterTests.cs"
                                 :heading "Dapper.Tests/ParameterTests.TestSqlDataRecordListParametersWithTypeHandlers"}
                        :score 1.5
                        :snippet "type handlers enum tests"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "tests/Dapper.Tests/EnumTests.cs"
                                 :heading "Dapper.Tests/TestEnum"}
                        :score 1.9
                        :snippet "enum boxing tests"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
                                 :heading "Dapper.Tests/E_Int"}
                        :score 1.8
                        :snippet "type handler enum tests"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                                  :rank 1
                                  :score 0.93
                                  :targetKind "node"
                                  :label "Dapper.Tests.Performance"
                                  :supportLabels ["Dapper.Tests.Performance/BelgradeBenchmarks.Setup"
                                                  "Dapper.Tests.Performance/BelgradeBenchmarks"]
                                  :scoreComponents {:sourceGraph 0.6
                                                    :lexical 1.0
                                                    :grep 0.08}}
                                 {:path "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                                  :rank 2
                                  :score 0.84
                                  :targetKind "file"
                                  :label "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                                  :supportLabels ["Dapper.Tests.Performance/BenchmarkBase.ConnectionStringSettings"
                                                  "Dapper.Tests.Performance"]
                                  :scoreComponents {:sourceGraph 0.6}}
                                 {:path "Dapper/SqlMapper.Settings.cs"
                                  :rank 11
                                  :score 0.95
                                  :targetKind "file"
                                  :label "Dapper/SqlMapper.Settings.cs"
                                  :supportLabels ["Dapper/SqlMapper.TypeHandler.cs"
                                                  "Dapper/TypeHandler"
                                                  "Dapper"
                                                  "Dapper/TypeHandler.SetValue"]
                                  :scoreComponents {:sourceGraph 0.6}}]}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet
                                                                       {:root root}))
        paths (mapv :path files)
        settings (first (filter #(= "Dapper/SqlMapper.Settings.cs" (:path %))
                                files))]
    (is (> 5 (.indexOf paths "Dapper/SqlMapper.Settings.cs")))
    (is (= 1 (get-in settings [:metrics :queryMatchedFileStemPartCount])))
    (is (pos? (get-in settings [:metrics :sourceGraphQueryEvidenceBoost])))))

(deftest file-ranking-boosts-candidate-node-file-identity-support
  (let [root (temp-dir "ygg-bench-node-file-identity-support")
        _ (spit-file! root "tests/unit/adapters/http.test.js" "describe('http', () => {})\n")
        _ (spit-file! root "tests/unit/adapters/noise.test.js" "describe('noise', () => {})\n")
        packet {:query "node native proxy boundary http adapter tests"
                :candidateFiles [{:path "tests/unit/adapters/noise.test.js"
                                  :rank 4
                                  :score 3.2
                                  :targetKind "file"
                                  :label "tests/unit/adapters/noise.test.js"
                                  :supportLabels ["tests.unit.adapters.noise.test"]
                                  :scoreComponents {:sourceGraph 3.2
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:path "tests/unit/adapters/http.test.js"
                                  :rank 12
                                  :score 5.3
                                  :targetKind "node"
                                  :label "tests.unit.adapters.http.test/createHttp2Axios"
                                  :supportLabels ["tests.unit.adapters.http.test/CustomFormData"
                                                  "tests.unit.adapters.http.test/closeServer"
                                                  "tests.unit.adapters.http.test/stop"
                                                  "tests.unit.adapters.http.test/startServer"]
                                  :scoreComponents {:sourceGraph 5.3
                                                    :lexical 0.53}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["tests/unit/adapters/http.test.js"
            "tests/unit/adapters/noise.test.js"]
           (mapv :path files)))
    (is (= 4 (get-in files [0 :metrics :fileIdentitySupportLabelCount])))
    (is (pos? (get-in files [0 :metrics :candidateFileIdentitySupportBoost])))
    (is (= 7.0 (get-in files [0 :metrics :candidateFileIdentitySupportBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-dense-lexical-chunk-file-identity-support
  (let [root (temp-dir "ygg-bench-lexical-chunk-file-identity-support")
        _ (spit-file! root "src/CoreMapper.cs" "public class CoreMapper {}\n")
        _ (spit-file! root "src/Other.cs" "public class Other {}\n")
        packet {:query "jsonb string core mapper"
                :candidateFiles [{:path "src/Other.cs"
                                  :rank 1
                                  :score 2.5
                                  :targetKind "node"
                                  :label "Fixture/OtherJsonbString"
                                  :supportLabels ["Fixture/Other"]
                                  :scoreComponents {:sourceGraph 2.5
                                                    :lexical 0.7}}
                                 {:path "src/CoreMapper.cs"
                                  :rank 80
                                  :score 0.8
                                  :targetKind "chunk"
                                  :label "Fixture/CoreMapper.ReadJsonbString"
                                  :supportLabels ["Fixture/CoreMapper.LoadJsonb"
                                                  "Fixture/CoreMapper.GetString"
                                                  "Fixture/CoreMapper.MapValue"
                                                  "Fixture/CoreMapper.Parse"]
                                  :scoreComponents {:lexical 0.7
                                                    :exact 0.1}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/CoreMapper.cs" "src/Other.cs"]
           (mapv :path files)))
    (is (= 4 (get-in files [0 :metrics :matchedTokenCount])))
    (is (= 4 (get-in files [0 :metrics :fileIdentitySupportLabelCount])))
    (is (pos? (get-in files [0 :metrics :candidateFileIdentitySupportBoost])))))

(deftest file-ranking-boosts-retrieved-support-labels
  (let [root (temp-dir "ygg-bench-retrieved-support-labels")
        _ (spit-file! root "site/src/components/home/ComponentUtilities.astro" "---\n---\n")
        _ (spit-file! root "site/src/pages/docs/[version]/index.astro" "---\n---\n")
        _ (spit-file! root "site/src/pages/index.astro" "---\n---\n")
        packet {:query "docs route impact component utilities"
                :docs [{:source {:path "site/src/components/home/ComponentUtilities.astro"
                                 :heading "site/src/components/home/ComponentUtilities.astro"}
                        :score 1.0
                        :snippet "component utilities"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "site/src/pages/docs/[version]/index.astro"
                                  :rank 1
                                  :score 3.2
                                  :targetKind "node"
                                  :label "/docs/{version}"
                                  :supportLabels ["site.src.pages.docs.[version].index"
                                                  "site/src/pages/docs/[version]/index.astro"]
                                  :scoreComponents {:sourceGraph 3.2
                                                    :lexical 0.7}}
                                 {:path "site/src/pages/index.astro"
                                  :rank 2
                                  :score 3.1
                                  :targetKind "node"
                                  :label "@components/home/ComponentUtilities.astro"
                                  :supportLabels ["site.src.pages.index"
                                                  "site/src/pages/index.astro"
                                                  "@components/home/Customize.astro"]
                                  :scoreComponents {:sourceGraph 3.1
                                                    :lexical 0.95}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)
        page-file (first files)
        route-file (some #(when (= "site/src/pages/docs/[version]/index.astro"
                                   (:path %))
                            %)
                         files)]
    (is (= "site/src/pages/index.astro" (:path page-file)))
    (is (= 1 (get-in page-file [:metrics :retrievedSupportLabelCount])))
    (is (pos? (get-in page-file [:metrics :retrievedSupportLabelBoost])))
    (is (> (get-in page-file [:metrics :rankScore])
           (get-in route-file [:metrics :rankScore])))))

(deftest file-ranking-does-not-boost-late-retrieved-support-labels
  (let [root (temp-dir "ygg-bench-late-retrieved-support-labels")
        _ (spit-file! root "src/adapter.clj" "(ns adapter)\n")
        packet {:query "adapter boundary"
                :docs [{:source {:path "src/context.clj"
                                 :heading "Fixture/Adapter"}
                        :score 1.0
                        :snippet "adapter boundary"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/adapter.clj"
                                  :rank 80
                                  :score 3.0
                                  :targetKind "node"
                                  :label "Fixture/AdapterBoundary"
                                  :supportLabels ["Fixture/Adapter"
                                                  "Fixture/AdapterRuntime"]
                                  :scoreComponents {:sourceGraph 3.0
                                                    :lexical 0.7}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "src/adapter.clj" (:path file)))
    (is (= 3 (get-in file [:metrics :retrievedSupportLabelCount])))
    (is (not (contains? (:metrics file)
                        :retrievedSupportLabelBoost)))))

(deftest file-ranking-boosts-early-doc-direct-file-compound-match
  (let [root (temp-dir "ygg-bench-doc-direct-file-compound")
        _ (spit-file! root "db/init/post-setup.sql" "select 1;\n")
        _ (spit-file! root "db/init/before-create.sql" "select 1;\n")
        packet {:query "trigger owner post setup sql"
                :docs [{:source {:path "db/init/before-create.sql"
                                 :heading "before create sql"}
                        :score 1.0
                        :snippet "trigger owner setup sql"
                        :provenance "retrieved-doc"}
                       {:source {:path "db/init/post-setup.sql"
                                 :heading "post setup sql"}
                        :score 1.0
                        :snippet "trigger owner post setup sql"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "db/init/before-create.sql"
                                  :rank 2
                                  :score 1.0
                                  :targetKind "file"
                                  :label "before create sql"
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.8}}
                                 {:path "db/init/post-setup.sql"
                                  :rank 75
                                  :score 1.0
                                  :targetKind "file"
                                  :label "post setup sql"
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.8}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)
        setup-file (some #(when (= "db/init/post-setup.sql" (:path %)) %)
                         files)]
    (is (= "db/init/post-setup.sql" (:path (first files))))
    (is (pos? (get-in setup-file
                      [:metrics :matchedCompoundTokenPairCount])))
    (is (pos? (get-in setup-file
                      [:metrics :docSupportedDirectFileCompoundBoost])))))

(deftest file-ranking-scales-doc-direct-file-compound-match-by-token-density
  (let [boost @#'benchmark-prediction/doc-supported-direct-file-compound-boost]
    (is (= 1.5 (boost 1 1 1 1 5)))
    (is (= 2.75 (boost 1 1 1 1 6)))
    (is (= 4.0 (boost 1 1 1 1 7)))
    (is (= 4.0 (boost 1 1 1 1 9)))
    (is (= 0.0 (boost 1 1 1 1 4)))))

(deftest file-ranking-uses-candidate-lexical-component
  (let [root (temp-dir "ygg-bench-candidate-lexical-component")
        _ (spit-file! root "site/src/pages/docs/[version]/[...slug].astro" "---\n---\n")
        _ (spit-file! root "site/src/pages/index.astro" "---\n---\n")
        packet {:query "docs route impact theme components"
                :candidateFiles [{:path "site/src/pages/docs/[version]/[...slug].astro"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "node"
                                  :label "/docs/{version}/{...slug}"
                                  :supportLabels ["site.src.pages.docs.[version].[...slug]"
                                                  "site/src/pages/docs/[version]/[...slug].astro"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.5}}
                                 {:path "site/src/pages/index.astro"
                                  :rank 2
                                  :score 0.99
                                  :targetKind "node"
                                  :label "@components/home/ComponentUtilities.astro"
                                  :supportLabels ["site.src.pages.index"
                                                  "site/src/pages/index.astro"]
                                  :scoreComponents {:sourceGraph 0.99
                                                    :lexical 0.95}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["site/src/pages/index.astro"
            "site/src/pages/docs/[version]/[...slug].astro"]
           (mapv :path files)))
    (is (pos? (get-in files [0 :metrics :candidateLexicalComponentBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-keeps-candidate-debug-fields-in-metrics
  (let [root (temp-dir "ygg-bench-candidate-debug-field-scope")
        _ (spit-file! root "src/app.js" "export const value = 1;\n")
        packet {:query "adapter lifecycle"
                :candidateFiles [{:path "src/app.js"
                                  :rank 7
                                  :score 1.0
                                  :targetKind "node"
                                  :label "adapter lifecycle"
                                  :supportLabels ["support-a" "support-b"]
                                  :scoreComponents {:sourceGraph 1.0
                                                    :lexical 0.6}}]}
        file (-> (benchmark/context-packet->agent-result packet {:root root})
                 :suspectedFiles
                 first)]
    (is (false? (contains? file :candidate-source-rank)))
    (is (false? (contains? file :candidate-support-label-count)))
    (is (= 7 (get-in file [:metrics :candidateSourceRank])))
    (is (= 2 (get-in file [:metrics :candidateSupportLabelCount])))
    (is (pos? (get-in file [:metrics :candidateLexicalComponentBoost])))))

(deftest candidate-file-ranking-tokenizes-evidence-text-once-for-token-pairs
  (let [root (temp-dir "ygg-bench-candidate-file-token-pairs")
        _ (spit-file! root "src/http_adapter.clj" "(ns http-adapter)\n")
        tokenize text/tokenize
        evidence-text "src/http_adapter.clj\nhttp adapter\nproxy env"
        calls (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (when (= evidence-text (str value))
                                    (swap! calls inc))
                                  (tokenize value))]
      (let [file (-> (benchmark/context-packet->agent-result
                      {:query "http adapter proxy env"
                       :candidateFiles [{:path "src/http_adapter.clj"
                                         :rank 1
                                         :score 1.0
                                         :targetKind "file"
                                         :label "http adapter"
                                         :supportLabels ["proxy env"]
                                         :scoreComponents {:sourceGraph 1.0
                                                           :lexical 1.0}}]}
                      {:root root})
                     :suspectedFiles
                     first)]
        (is (= "src/http_adapter.clj" (:path file)))
        (is (= 1 @calls))
        (is (pos? (get-in file [:metrics :matchedTokenPairCount])))))))

(deftest entity-ranking-tokenizes-evidence-text-once-for-token-pairs
  (let [root (temp-dir "ygg-bench-entity-token-pairs")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        tokenize text/tokenize
        evidence-text "src/db.clj\nDB Adapter"
        calls (atom 0)]
    (with-redefs [text/tokenize (fn [value]
                                  (when (= evidence-text (str value))
                                    (swap! calls inc))
                                  (tokenize value))]
      (let [file (-> (benchmark/context-packet->agent-result
                      {:query "db adapter"
                       :entities [{:path "src/db.clj"
                                   :label "DB Adapter"
                                   :kind :namespace
                                   :score 0.8}]}
                      {:root root})
                     :suspectedFiles
                     first)]
        (is (= "src/db.clj" (:path file)))
        (is (= 1 @calls))
        (is (pos? (get-in file [:metrics :matchedTokenCount])))))))

(deftest doc-ranking-builds-evidence-text-once
  (let [root (temp-dir "ygg-bench-doc-evidence-text")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        evidence-text @#'benchmark-prediction/evidence-text
        calls (atom 0)]
    (with-redefs-fn {#'benchmark-prediction/evidence-text
                     (fn [doc]
                       (swap! calls inc)
                       (evidence-text doc))}
      (fn []
        (let [file (-> (benchmark/context-packet->agent-result
                        {:query "app route"
                         :docs [{:source {:path "src/app.clj"
                                          :heading "app route"}
                                 :score 1.0
                                 :snippet "app route"
                                 :provenance "retrieved-doc"}]}
                        {:root root})
                       :suspectedFiles
                       first)]
          (is (= "src/app.clj" (:path file)))
          (is (= 1 @calls))
          (is (pos? (get-in file [:metrics :matchedTokenCount]))))))))

(deftest context-packet-agent-result-does-not-report-budget-trim-as-warning
  (let [root (temp-dir "ygg-bench-budget-trim-warning-scope")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        packet {:query "app"
                :warnings ["candidate files trimmed to 61 of 127 to fit context budget"
                           "Context warning."]
                :docs [{:source {:path "src/app.clj"
                                 :heading "app"}
                        :score 1.0
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})]
    (is (= ["Context warning."] (:warnings result)))))

(deftest file-ranking-uses-source-graph-candidate-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-source-graph-candidate-rank")
        _ (spit-file! root "lib/adapters/http.js" "export default function httpAdapter() {}\n")
        _ (spit-file! root "tests/unit/core/AxiosError.test.js" "describe('AxiosError', () => {})\n")
        packet {:query "defer env proxy handling to Node HTTP adapter unit tests"
                :candidateFiles [{:path "tests/unit/adapters/http.test.js"
                                  :rank 1
                                  :score 6.4
                                  :targetKind "node"
                                  :label "tests.unit.adapters.http.test/createHttp2Axios"
                                  :scoreComponents {:sourceGraph 6.4}}
                                 {:path "lib/adapters/http.js"
                                  :rank 2
                                  :score 2.3
                                  :targetKind "file"
                                  :label "lib/adapters/http.js"
                                  :scoreComponents {:sourceGraph 2.3
                                                    :lexical 0.27}}
                                 {:path "lib/adapters/http.js"
                                  :rank 3
                                  :score 2.0
                                  :targetKind "package-import"
                                  :label "npm:proxy-from-env"
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.2}}
                                 {:path "tests/unit/core/AxiosError.test.js"
                                  :rank 40
                                  :score 2.1
                                  :targetKind "file"
                                  :label "tests/unit/core/AxiosError.test.js"
                                  :scoreComponents {:sourceGraph 2.1
                                                    :lexical 0.27}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["lib/adapters/http.js"
            "tests/unit/core/AxiosError.test.js"]
           (mapv :path files)))
    (is (= 2 (get-in files [0 :metrics :candidateSourceRank])))
    (is (> (get-in files [0 :metrics :candidateSourceRankScore])
           5.0))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-diversifies-repeated-root-definition-kind
  (let [root (temp-dir "ygg-bench-root-kind-diversity")
        _ (doseq [path ["consumer/logs.go"
                        "consumer/metrics.go"
                        "connector/connector.go"]]
            (spit-file! root path "package fixture\n"))
        packet {:query "connector consumer interface"
                :docs [{:source {:path "consumer/logs.go"
                                 :definitionKind :interface
                                 :heading "consumer/logs/Logs"}
                        :score 5.0
                        :snippet "consumer logs interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "consumer/metrics.go"
                                 :definitionKind :interface
                                 :heading "consumer/metrics/Metrics"}
                        :score 4.8
                        :snippet "consumer metrics interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "connector/connector.go"
                                 :definitionKind :interface
                                 :heading "connector/connector/Logs"}
                        :score 4.0
                        :snippet "connector interface"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["consumer/logs.go"
            "connector/connector.go"
            "consumer/metrics.go"]
           (mapv :path files)))))

(deftest file-ranking-diversity-preserves-score-ranked-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "site/src/pages/index.astro"
               :rank 1
               :repo-id "bootstrap"
               :metrics {:rankScore 5.5
                         :candidateFileCount 1
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "site/src/pages/docs/[version]/index.astro"
               :rank 2
               :repo-id "bootstrap"
               :metrics {:rankScore 5.2
                         :candidateFileCount 1
                         :docCount 0
                         :entityCount 0
                         :architectureSupportBoost 1.0
                         :definitionKinds ["node" "route"]}}
              {:path "site/src/pages/docs/[version]/examples/index.astro"
               :rank 3
               :repo-id "bootstrap"
               :metrics {:rankScore 4.8
                         :candidateFileCount 1
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["node"]}}]
        files (diversify rows)]
    (is (= ["site/src/pages/index.astro"
            "site/src/pages/docs/[version]/index.astro"
            "site/src/pages/docs/[version]/examples/index.astro"]
           (mapv :path files)))
    (is (= [1 2 3] (mapv :rank files)))))

(deftest file-ranking-diversity-keeps-strong-source-candidates-near-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "tests/fetch_test.clj"
               :rank 1
               :repo-id "fixture"
               :metrics {:rankScore 18.0
                         :candidateFileCount 2
                         :candidateSourceRank 39
                         :supportCount 3
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["function"]}}
              {:path "src/http.clj"
               :rank 2
               :repo-id "fixture"
               :metrics {:rankScore 16.0
                         :candidateFileCount 2
                         :candidateSourceRank 2
                         :supportCount 2
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["file"]}}
              {:path "tests/http_test.clj"
               :rank 3
               :repo-id "fixture"
               :metrics {:rankScore 17.0
                         :candidateFileCount 4
                         :candidateSourceRank 1
                         :supportCount 5
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["function"]}}
              {:path "tests/axios_test.clj"
               :rank 4
               :repo-id "fixture"
               :metrics {:rankScore 15.0
                         :candidateFileCount 2
                         :candidateSourceRank 34
                         :supportCount 3
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["file"]}}]
        files (diversify rows)]
    (is (= ["tests/fetch_test.clj"
            "src/http.clj"
            "tests/http_test.clj"
            "tests/axios_test.clj"]
           (mapv :path files)))
    (is (= [1 2 3 4] (mapv :rank files)))))

(deftest file-ranking-diversity-keeps-strong-candidate-identity-near-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "tests/setup/server.js"
               :rank 1
               :repo-id "fixture"
               :metrics {:rankScore 13.0
                         :candidateFileCount 1
                         :candidateSourceRank 2
                         :supportCount 2
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "tests/unit/adapters/http.test.js"
               :rank 2
               :repo-id "fixture"
               :metrics {:rankScore 11.7
                         :candidateFileCount 1
                         :candidateSourceRank 12
                         :supportCount 1
                         :fileIdentitySupportLabelCount 4
                         :candidateFileIdentitySupportBoost 5.6
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "lib/adapters/http.js"
               :rank 3
               :repo-id "fixture"
               :metrics {:rankScore 11.6
                         :candidateFileCount 2
                         :candidateSourceRank 16
                         :supportCount 4
                         :architectureSupportBoost 1.0
                         :docCount 2
                         :entityCount 0
                         :definitionKinds ["package-import"]}}
              {:path "src/CoreMapper.cs"
               :rank 4
               :repo-id "fixture"
               :metrics {:rankScore 10.4
                         :candidateFileCount 1
                         :supportCount 1
                         :fileIdentitySupportLabelCount 4
                         :candidateFileIdentitySupportBoost 5.6
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["chunk"]}}
              {:path "lib/adapters/adapters.js"
               :rank 5
               :repo-id "fixture"
               :metrics {:rankScore 9.8
                         :candidateFileCount 1
                         :candidateSourceRank 5
                         :supportCount 2
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["node"]}}]
        files (diversify rows)]
    (is (= ["tests/setup/server.js"
            "tests/unit/adapters/http.test.js"
            "src/CoreMapper.cs"
            "lib/adapters/http.js"]
           (mapv :path (take 4 files))))))

(deftest file-ranking-diversity-does-not-bypass-low-support-source-candidate
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "tests/head_test.clj"
               :rank 1
               :repo-id "fixture"
               :metrics {:rankScore 18.0
                         :candidateFileCount 2
                         :candidateSourceRank 12
                         :supportCount 3
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["function"]}}
              {:path "tests/low_support_test.clj"
               :rank 2
               :repo-id "fixture"
               :metrics {:rankScore 17.0
                         :candidateFileCount 1
                         :candidateSourceRank 1
                         :supportCount 1
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["function"]}}
              {:path "src/core.clj"
               :rank 3
               :repo-id "fixture"
               :metrics {:rankScore 16.0
                         :candidateFileCount 1
                         :candidateSourceRank 4
                         :supportCount 1
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["file"]}}]
        files (diversify rows)]
    (is (= ["tests/head_test.clj"
            "src/core.clj"
            "tests/low_support_test.clj"]
           (mapv :path files)))))

(deftest file-ranking-diversity-spreads-candidate-support-signatures
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        candidate-row (fn [path rank signature]
                        {:path path
                         :rank rank
                         :repo-id "fixture"
                         :metrics {:rankScore (- 20.0 rank)
                                   :candidateFileCount 1
                                   :candidateSourceRank rank
                                   :docCount 1
                                   :entityCount 0
                                   :candidateSupportLabelSignatures [signature]
                                   :definitionKinds ["sql-file"]}})
        rows [(candidate-row "migrations/schema-15.sql"
                             1
                             ["security definer"])
              (candidate-row "migrations/schema.sql"
                             2
                             ["security definer"])
              (candidate-row "migrations/schema-orioledb-17.sql"
                             3
                             ["security definer"])
              (candidate-row "migrations/db/migrations/trigger.sql"
                             4
                             ["event trigger"])
              (candidate-row "migrations/db/init-scripts/post-setup.sql"
                             5
                             ["createdb" "security definer"])]
        files (diversify rows)]
    (is (= ["migrations/schema-15.sql"
            "migrations/db/migrations/trigger.sql"
            "migrations/db/init-scripts/post-setup.sql"
            "migrations/schema.sql"
            "migrations/schema-orioledb-17.sql"]
           (mapv :path files)))
    (is (= [1 2 3 4 5] (mapv :rank files)))))

(deftest file-ranking-diversity-keeps-decision-candidate-files-early
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "tests/docker-compose.yml"
               :rank 1
               :repo-id "dapper"
               :metrics {:rankScore 13.0
                         :candidateFileCount 2
                         :decisionCandidateCount 2
                         :docCount 0
                         :entityCount 0
                         :architectureSupportBoost 1.0
                         :definitionKinds ["container-image-consumer"]}}
              {:path "Dapper/SqlMapper.cs"
               :rank 2
               :repo-id "dapper"
               :metrics {:rankScore 17.0
                         :candidateFileCount 1
                         :decisionCandidateCount 2
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["file"]}}
              {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
               :rank 3
               :repo-id "dapper"
               :metrics {:rankScore 15.0
                         :candidateFileCount 1
                         :decisionCandidateCount 1
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "tests/Dapper.Tests/MiscTests.cs"
               :rank 4
               :repo-id "dapper"
               :metrics {:rankScore 14.0
                         :candidateFileCount 2
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["method"]}}]
        files (diversify rows)]
    (is (= ["tests/docker-compose.yml"
            "Dapper/SqlMapper.cs"
            "tests/Dapper.Tests/TypeHandlerTests.cs"]
           (mapv :path (take 3 files))))
    (is (= [1 2 3] (mapv :rank (take 3 files))))))

(deftest file-ranking-diversity-promotes-decision-candidate-head
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "src/incidental.clj"
               :rank 1
               :repo-id "fixture"
               :metrics {:rankScore 20.0
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["file"]}}
              {:path "src/decision.clj"
               :rank 2
               :repo-id "fixture"
               :metrics {:rankScore 12.0
                         :decisionCandidateCount 1
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "src/other.clj"
               :rank 3
               :repo-id "fixture"
               :metrics {:rankScore 10.0
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["node"]}}]
        files (diversify rows)]
    (is (= ["src/decision.clj" "src/incidental.clj" "src/other.clj"]
           (mapv :path files)))
    (is (= [1 2 3] (mapv :rank files)))))

(deftest file-ranking-diversity-keeps-consecutive-decision-candidates-ranked
  (let [diversify @#'benchmark-prediction/diversify-ranked-file-predictions
        rows [{:path "tests/docker-compose.yml"
               :rank 1
               :repo-id "dapper"
               :metrics {:rankScore 13.0
                         :candidateFileCount 2
                         :decisionCandidateCount 2
                         :docCount 0
                         :entityCount 0
                         :architectureSupportBoost 1.0
                         :definitionKinds ["container-image-consumer"]}}
              {:path "Dapper/SqlMapper.cs"
               :rank 2
               :repo-id "dapper"
               :metrics {:rankScore 17.0
                         :candidateFileCount 1
                         :decisionCandidateCount 2
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["file"]}}
              {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
               :rank 3
               :repo-id "dapper"
               :metrics {:rankScore 15.0
                         :candidateFileCount 1
                         :decisionCandidateCount 1
                         :docCount 0
                         :entityCount 0
                         :definitionKinds ["node"]}}
              {:path "tests/Dapper.Tests/MiscTests.cs"
               :rank 4
               :repo-id "dapper"
               :metrics {:rankScore 14.0
                         :candidateFileCount 2
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["method"]}}
              {:path "Dapper/FeatureSupport.cs"
               :rank 5
               :repo-id "dapper"
               :metrics {:rankScore 13.5
                         :candidateFileCount 1
                         :docCount 1
                         :entityCount 0
                         :definitionKinds ["class"]}}]
        files (diversify rows)]
    (is (= ["tests/docker-compose.yml"
            "Dapper/SqlMapper.cs"
            "tests/Dapper.Tests/TypeHandlerTests.cs"
            "tests/Dapper.Tests/MiscTests.cs"]
           (mapv :path (take 4 files))))))

(deftest file-ranking-keeps-single-row-candidate-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-single-source-graph-candidate-rank")
        _ (spit-file! root "lib/adapters/http.js" "export default function httpAdapter() {}\n")
        packet {:query "defer env proxy handling"
                :candidateFiles [{:path "lib/adapters/http.js"
                                  :rank 2
                                  :score 2.3
                                  :targetKind "file"
                                  :label "lib/adapters/http.js"
                                  :scoreComponents {:sourceGraph 2.3
                                                    :lexical 0.27}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "lib/adapters/http.js" (:path file)))
    (is (= 2 (get-in file [:metrics :candidateSourceRank])))
    (is (<= (get-in file [:metrics :candidateSourceRankScore]) 0.2))
    (is (nil? (get-in file [:metrics :robustCandidateOnlyBoost])))))

(deftest file-ranking-boosts-query-matched-source-graph-grep-evidence
  (let [root (temp-dir "ygg-bench-source-graph-grep-evidence")
        _ (spit-file! root "src/flask/config.py" "class Config: pass\n")
        _ (spit-file! root "src/flask/sansio/app.py" "class App: pass\n")
        packet {:query "autoescape selection flask"
                :candidateFiles [{:path "src/flask/config.py"
                                  :rank 1
                                  :score 10.2
                                  :targetKind "node"
                                  :label "flask.config/Config.selection"
                                  :scoreComponents {:sourceGraph 10.2
                                                    :lexical 0.4
                                                    :graph 0.6}}
                                 {:path "src/flask/sansio/app.py"
                                  :rank 26
                                  :score 10.0
                                  :targetKind "node"
                                  :label "flask.sansio.app/App.select_jinja_autoescape"
                                  :scoreComponents {:sourceGraph 10.0
                                                    :lexical 0.35
                                                    :grep 0.65
                                                    :graph 0.6}}]}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet
                                                                       {:root root}))]
    (is (= "src/flask/sansio/app.py" (:path (first files))))
    (is (pos? (get-in files [0 :metrics :candidateGrepComponentBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-keeps-doc-supported-source-graph-rank-as-tiebreaker
  (let [root (temp-dir "ygg-bench-doc-source-graph-rank")
        _ (spit-file! root "src/settings.cs" "public static class Settings {}\n")
        packet {:query "prefer enum typehandler setting"
                :docs [{:source {:path "src/settings.cs"
                                 :heading "Settings.Settings"}
                        :score 1.0
                        :snippet "setting"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/settings.cs"
                                  :rank 1
                                  :score 4.0
                                  :targetKind "file"
                                  :label "src/settings.cs"
                                  :scoreComponents {:sourceGraph 4.0
                                                    :lexical 0.4}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        file (first (:suspectedFiles result))]
    (is (= "src/settings.cs" (:path file)))
    (is (= 1 (get-in file [:metrics :candidateSourceRank])))
    (is (<= (get-in file [:metrics :candidateSourceRankScore]) 0.2))
    (is (> (get-in file [:metrics :sourceRankScore]) 2.0))))

(deftest file-ranking-boosts-query-matched-retrieved-path-self-identity
  (let [root (temp-dir "ygg-bench-retrieved-path-self-identity")
        _ (spit-file! root "consumer/consumer.go" "package consumer\n")
        _ (spit-file! root "pdata/pmetric/metric_type.go" "package pmetric\n")
        packet {:query "consumer component connector contracts"
                :docs [{:source {:path "pdata/pmetric/metric_type.go"
                                 :heading "pmetric metric type"
                                 :definitionKind :type}
                        :score 1.0
                        :snippet "consumer component connector contracts"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "consumer/consumer.go"
                                 :heading "consumer/consumer/Option"
                                 :definitionKind :type}
                        :score 1.0
                        :snippet "consumer component connector contracts"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet
                                                                       {:root root}))]
    (is (= ["consumer/consumer.go"
            "pdata/pmetric/metric_type.go"]
           (mapv :path files)))
    (is (pos? (get-in files [0 :metrics :retrievedPathSelfIdentityBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-doc-supported-source-graph-head
  (let [root (temp-dir "ygg-bench-doc-source-graph-head")
        _ (spit-file! root "tests/Dapper.Tests/TypeHandlerTests.cs" "namespace Dapper.Tests;\n")
        _ (spit-file! root "tests/Dapper.Tests/ParameterTests.cs" "namespace Dapper.Tests;\n")
        packet {:query "jsonb type handler tests"
                :docs [{:source {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
                                 :heading "Dapper.Tests.TypeHandlerTests"}
                        :score 0.8
                        :snippet "jsonb type handler tests"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "tests/Dapper.Tests/ParameterTests.cs"
                                  :rank 12
                                  :score 3.1
                                  :targetKind "node"
                                  :label "Dapper.Tests.ParameterTests.JsonbString"
                                  :supportLabels ["Dapper.Tests.TypeHandlerTests.JsonbString"
                                                  "Dapper.Tests.ParameterTests.TypeHandler"]
                                  :scoreComponents {:sourceGraph 3.1
                                                    :lexical 0.7
                                                    :graph 0.2}}
                                 {:path "tests/Dapper.Tests/TypeHandlerTests.cs"
                                  :rank 1
                                  :score 1.3
                                  :targetKind "node"
                                  :label "Dapper.Tests.TypeHandlerTests.JsonbString"
                                  :supportLabels ["Dapper.Tests.TypeHandlerTests.TypeHandler"
                                                  "Dapper.Tests.TypeHandlerTests.Parse"]
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.55
                                                    :graph 0.2}}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= "tests/Dapper.Tests/TypeHandlerTests.cs"
           (:path (first files))))
    (is (pos? (get-in files [0 :metrics :docSupportedSourceGraphHeadBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-boosts-doc-supported-architecture-token-evidence
  (let [root (temp-dir "ygg-bench-doc-architecture-token")
        _ (spit-file! root "src/core.cs" "public static class Core {}\n")
        _ (spit-file! root "src/noise.cs" "public static class Noise {}\n")
        packet {:query "prefer enum typehandlers settings core mapping"
                :docs [{:source {:path "src/noise.cs"
                                 :heading "Noise.Enum"}
                        :score 5.0
                        :snippet "enum typehandlers enum typehandlers"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/core.cs"
                                 :heading "Core.Mapping"}
                        :score 1.2
                        :snippet "prefer enum typehandlers settings core mapping"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :architecture {:runtimeEvidence [{:path "src/core.cs"
                                                  :kind "url"
                                                  :fileKind "dotnet"
                                                  :label "core mapping settings reference"
                                                  :score 3.0}]}}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet
                                                                       {:root root}))]
    (is (= "src/core.cs" (:path (first files))))
    (is (pos? (get-in files [0 :metrics :docSupportedArchitectureTokenBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-does-not-stack-doc-architecture-token-boost-on-direct-file
  (let [root (temp-dir "ygg-bench-doc-architecture-direct-file")
        _ (spit-file! root "src/app.cs" "public static class App {}\n")
        packet {:query "prefer enum typehandlers settings core mapping"
                :docs [{:source {:path "src/app.cs"
                                 :heading "App.Mapping"}
                        :score 2.0
                        :snippet "prefer enum typehandlers settings core mapping"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/app.cs"
                                  :rank 2
                                  :score 3.0
                                  :targetKind "file"
                                  :label "src/app.cs"
                                  :supportLabels ["prefer enum typehandlers settings"]
                                  :scoreComponents {:sourceGraph 3.0
                                                    :lexical 0.6
                                                    :graph 0.2}}]
                :architecture {:runtimeEvidence [{:path "src/app.cs"
                                                  :kind "url"
                                                  :fileKind "dotnet"
                                                  :label "core mapping settings reference"
                                                  :score 3.0}]}}
        file (-> (benchmark/context-packet->agent-result packet {:root root})
                 :suspectedFiles
                 first)]
    (is (= "src/app.cs" (:path file)))
    (is (nil? (get-in file [:metrics :docSupportedArchitectureTokenBoost])))))

(deftest file-ranking-boosts-candidate-only-direct-file-with-architecture-and-graph
  (let [root (temp-dir "ygg-bench-direct-file-architecture-graph")
        _ (spit-file! root "src/settings.cs" "public static class Settings {}\n")
        _ (spit-file! root "src/noise.cs" "public static class Noise {}\n")
        packet {:query "prefer enum typehandler setting"
                :candidateFiles [{:path "src/noise.cs"
                                  :rank 1
                                  :score 5.0
                                  :targetKind "node"
                                  :label "Fixture.TypeHandlerNoise"
                                  :supportLabels ["Fixture.Enum"]
                                  :scoreComponents {:sourceGraph 5.0
                                                    :lexical 0.35
                                                    :graph 0.2}}
                                 {:path "src/settings.cs"
                                  :rank 54
                                  :score 3.3
                                  :targetKind "file"
                                  :label "src/settings.cs"
                                  :supportLabels ["Settings.Settings"
                                                  "Settings.CommandTimeout"
                                                  "prefer enum typehandler setting"]
                                  :scoreComponents {:sourceGraph 3.3
                                                    :lexical 0.33
                                                    :graph 0.2}}]
                :architecture {:runtimeEvidence [{:path "src/settings.cs"
                                                  :kind "url"
                                                  :fileKind "dotnet"
                                                  :label "prefer enum typehandler setting"
                                                  :score 3.25}]}}
        files (:suspectedFiles (benchmark/context-packet->agent-result packet
                                                                       {:root root}))]
    (is (= "src/settings.cs" (:path (first files))))
    (is (pos? (get-in files [0 :metrics :directFileArchitectureGraphBoost])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))

(deftest file-ranking-does-not-use-dependency-import-path-as-query-architecture-support
  (let [root (temp-dir "ygg-bench-dependency-import-path-support")
        _ (spit-file! root "src/settings.cs" "public static class Settings {}\n")
        packet {:query "prefer enum typehandler setting"
                :candidateFiles [{:path "src/settings.cs"
                                  :rank 54
                                  :score 3.3
                                  :targetKind "file"
                                  :label "src/settings.cs"
                                  :supportLabels ["Settings.CommandTimeout"]
                                  :scoreComponents {:sourceGraph 3.3
                                                    :lexical 0.33
                                                    :graph 0.2}}]
                :architecture {:dependencyEvidence [{:path "src/settings.cs"
                                                     :kind "package-import"
                                                     :fileKind "dotnet"
                                                     :relation "imports-package"
                                                     :label "nuget:unrelated"
                                                     :package "unrelated"
                                                     :score 2.0}]}}
        file (-> (benchmark/context-packet->agent-result packet {:root root})
                 :suspectedFiles
                 first)]
    (is (= "src/settings.cs" (:path file)))
    (is (not (contains? (:metrics file) :directFileArchitectureGraphBoost)))))

(deftest file-ranking-does-not-use-single-dependency-package-identity-token
  (let [root (temp-dir "ygg-bench-dependency-import-single-token-support")
        _ (spit-file! root "src/http-adapter.js" "import proxyAgent from 'https-proxy-agent';\n")
        packet {:query "proxy package import adapter boundary"
                :candidateFiles [{:path "src/http-adapter.js"
                                  :rank 8
                                  :score 3.0
                                  :targetKind "file"
                                  :label "src/http-adapter.js"
                                  :supportLabels ["proxy adapter boundary"]
                                  :scoreComponents {:sourceGraph 3.0
                                                    :lexical 0.8
                                                    :graph 0.7}}]
                :architecture {:dependencyEvidence [{:path "src/http-adapter.js"
                                                     :kind "package-import"
                                                     :fileKind "javascript"
                                                     :relation "imports-package"
                                                     :label "npm:https-proxy-agent"
                                                     :package "https-proxy-agent"
                                                     :score 1.75}]}}
        file (-> (benchmark/context-packet->agent-result packet {:root root})
                 :suspectedFiles
                 first)]
    (is (= "src/http-adapter.js" (:path file)))
    (is (not (contains? (:metrics file) :directFileArchitectureGraphBoost)))
    (is (pos? (get-in file [:metrics :architectureSupportBoost])))))

(deftest limited-agent-result-reserves-candidate-file-only-evidence
  (let [root (temp-dir "ygg-bench-candidate-file-quota")
        _ (doseq [path ["src/doc-1.clj" "src/doc-2.clj" "src/doc-3.clj"
                        "src/doc-4.clj" "src/doc-5.clj" "src/candidate.clj"]]
            (spit-file! root path "(ns fixture)\n"))
        packet {:query "remote version"
                :docs (mapv (fn [idx]
                              {:source {:path (str "src/doc-" idx ".clj")
                                        :heading (str "doc " idx)}
                               :score (- 2.0 (* 0.1 idx))
                               :snippet "remote"
                               :provenance "retrieved-doc"})
                            (range 1 6))
                :candidateFiles [{:path "src/candidate.clj"
                                  :rank 99
                                  :score 0.4
                                  :targetKind "chunk"
                                  :label "remote version"}]}
        result (benchmark/context-packet->agent-result packet {:root root
                                                               :limit 5})
        files (:suspectedFiles result)
        files-by-path (into {} (map (juxt :path identity)) files)]
    (is (= 5 (count files)))
    (is (contains? files-by-path "src/candidate.clj"))
    (is (not (contains? files-by-path "src/doc-5.clj")))
    (is (= 1 (get-in files-by-path ["src/candidate.clj"
                                    :metrics
                                    :matchedTokenPairCount])))
    (is (= {:rawCandidateFiles 6
            :candidateFiles 6
            :coverageFilteredCandidateFiles 0
            :limit 5
            :coverageSourceKinds []
            :candidateFileOnlyQuota 5
            :candidateFileOnlySelected 1}
           (:selection result)))))

(deftest limited-agent-result-reserves-candidate-only-by-source-rank
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        candidate-row (fn [path rank source-rank]
                        {:path path
                         :rank rank
                         :metrics {:candidateFileCount 1
                                   :docCount 0
                                   :entityCount 0
                                   :candidateSourceRank source-rank}})
        rows (concat
              [{:path "docs/context-1.md"
                :rank 1
                :metrics {:docCount 1
                          :candidateFileCount 0
                          :entityCount 0}}
               {:path "docs/context-2.md"
                :rank 2
                :metrics {:docCount 1
                          :candidateFileCount 0
                          :entityCount 0}}]
              [(candidate-row "src/noise-1.clj" 3 90)
               (candidate-row "src/noise-2.clj" 4 91)
               (candidate-row "src/noise-3.clj" 5 92)
               (candidate-row "src/noise-4.clj" 6 93)
               (candidate-row "src/noise-5.clj" 7 94)
               (candidate-row "src/adapter.clj" 8 2)])
        result (select-limited rows 5)]
    (is (some #{"src/adapter.clj"} (map :path (:files result))))
    (is (not-any? #{"src/noise-5.clj"} (map :path (:files result))))
    (is (= 5 (:candidateFileOnlySelected result)))))

(deftest limited-agent-result-score-elbow-keeps-candidate-only-quota
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        doc-row (fn [path rank rank-score]
                  {:path path
                   :rank rank
                   :metrics {:rankScore rank-score
                             :docCount 1
                             :candidateFileCount 0
                             :entityCount 0}})
        candidate-row (fn [path rank source-rank rank-score]
                        {:path path
                         :rank rank
                         :metrics {:rankScore rank-score
                                   :candidateFileCount 1
                                   :docCount 0
                                   :entityCount 0
                                   :candidateSourceRank source-rank}})
        rows [(doc-row "src/high_a.clj" 1 10.0)
              (doc-row "src/high_b.clj" 2 9.0)
              (candidate-row "src/source_1.clj" 3 1 3.0)
              (candidate-row "src/source_2.clj" 4 2 2.0)
              (candidate-row "src/source_3.clj" 5 3 1.8)
              (candidate-row "src/query_dispatch.clj" 6 4 1.6)
              (candidate-row "src/source_5.clj" 7 5 1.4)
              (doc-row "docs/tail.md" 8 0.5)]
        result (select-limited rows 20)]
    (is (contains? (set (map :path (:files result)))
                   "src/query_dispatch.clj"))
    (is (= 5 (:candidateFileOnlySelected result)))
    (is (<= 7 (count (:files result))))))

(deftest compact-output-prune-preserves-query-evidence-source-rows
  (let [prune @#'benchmark-prediction/compact-output-prune-score-tail
        row (fn [path rank rank-score metrics]
              {:path path
               :rank rank
               :metrics (merge {:rankScore rank-score
                                :candidateFileCount 0
                                :docCount 0
                                :entityCount 0}
                               metrics)})
        query-source (row "src/query_dispatch.py"
                          4
                          2.0
                          {:candidateFileCount 1
                           :candidateSourceRank 293
                           :matchedTokenCount 2
                           :sourceGraphCandidateEvidenceScore 4.0
                           :candidateGrepScore 0.1})
        selected [(row "src/high_a.py" 1 10.0 {})
                  (row "src/high_b.py" 2 9.0 {})
                  (row "src/mid.py" 3 3.0 {})
                  query-source
                  (row "src/tail_a.py" 5 1.5 {})
                  (row "src/tail_b.py" 6 1.0 {})]
        result (prune selected 7 nil [] {})]
    (is (contains? (set (map :path result))
                   "src/query_dispatch.py"))
    (is (< (count result) (count selected)))
    (is (= ["src/high_a.py"
            "src/high_b.py"
            "src/mid.py"
            "src/query_dispatch.py"]
           (mapv :path result)))))

(deftest limited-agent-result-reserves-dense-candidate-only-file-identity
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        candidate-row (fn [path rank source-rank metrics]
                        {:path path
                         :rank rank
                         :metrics (merge {:candidateFileCount 1
                                          :docCount 0
                                          :entityCount 0
                                          :candidateSourceRank source-rank}
                                         metrics)})
        rows [(candidate-row "src/noise-1.cs" 1 1 {})
              (candidate-row "src/noise-2.cs" 2 2 {})
              (candidate-row "src/noise-3.cs" 3 3 {})
              (candidate-row "src/noise-4.cs" 4 4 {})
              (candidate-row "src/noise-5.cs" 5 5 {})
              (candidate-row "src/Mapper.Settings.cs"
                             9
                             80
                             {:directFileCandidateCount 1
                              :fileIdentitySupportLabelCount 4
                              :matchedTokenCount 4
                              :graphNeighborScore 0.2})]
        result (select-limited rows 5)]
    (is (some #{"src/Mapper.Settings.cs"} (map :path (:files result))))
    (is (not-any? #{"src/noise-5.cs"} (map :path (:files result))))
    (is (= 5 (:candidateFileOnlySelected result)))))

(deftest limited-agent-result-reserves-query-matched-source-graph-evidence
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        candidate-row (fn [path rank source-rank metrics]
                        {:path path
                         :rank rank
                         :metrics (merge {:candidateFileCount 1
                                          :docCount 0
                                          :entityCount 0
                                          :candidateSourceRank source-rank}
                                         metrics)})
        rows [(candidate-row "src/noise-1.py"
                             1
                             1
                             {:matchedTokenCount 1
                              :sourceGraphCandidateEvidenceScore 3.1})
              (candidate-row "src/noise-2.py"
                             2
                             2
                             {:matchedTokenCount 1
                              :sourceGraphCandidateEvidenceScore 3.0})
              (candidate-row "src/noise-3.py"
                             3
                             3
                             {:matchedTokenCount 1
                              :sourceGraphCandidateEvidenceScore 2.9})
              (candidate-row "src/noise-4.py"
                             4
                             4
                             {:matchedTokenCount 1
                              :sourceGraphCandidateEvidenceScore 2.8})
              (candidate-row "src/noise-5.py"
                             5
                             5
                             {:matchedTokenCount 1
                              :sourceGraphCandidateEvidenceScore 2.7})
              (candidate-row "src/flask/sansio/app.py"
                             23
                             26
                             {:matchedTokenCount 2
                              :sourceGraphCandidateEvidenceScore 6.1
                              :candidateGrepScore 0.64
                              :candidateLexicalComponentBoost 0.05})]
        result (select-limited rows 5)
        paths (mapv :path (:files result))]
    (is (some #{"src/flask/sansio/app.py"} paths))
    (is (not-any? #{"src/noise-5.py"} paths))
    (is (= 5 (:candidateFileOnlySelected result)))))

(deftest compact-output-reserves-query-matched-source-graph-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat (mapv (fn [idx]
                               (row (str "src/head-" idx ".py")
                                    idx
                                    {:docCount 1
                                     :matchedTokenCount 2}))
                             (range 1 13))
                       [(row "src/flask/sansio/app.py"
                             14
                             {:candidateFileCount 1
                              :docCount 0
                              :entityCount 0
                              :candidateSourceRank 26
                              :matchedTokenCount 2
                              :sourceGraphCandidateEvidenceScore 6.1
                              :candidateGrepScore 0.64})]))
        paths (mapv :path (compact-output files 12 nil))]
    (is (some #{"src/flask/sansio/app.py"} paths))
    (is (not-any? #{"src/head-12.py"} paths))))

(deftest compact-output-frontloads-query-evidence-source-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "surface/a.page"
                    1
                    {:candidateFileCount 4
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 6
                     :candidateGrepScore 0.4
                     :candidateLexicalComponentBoost 0.15
                     :sourceGraphCandidateEvidenceScore 0.55
                     :rankScore 22.0})
               (row "surface/b.page"
                    2
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :directFileCandidateCount 1
                     :matchedTokenCount 4
                     :sourceGraphCandidateEvidenceScore 3.5
                     :rankScore 8.0})
               (row "surface/c.page"
                    3
                    {:candidateFileCount 1
                     :docCount 1
                     :entityCount 0
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.59
                     :rankScore 7.0})
               (row "surface/d.page"
                    4
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 4
                     :candidateSourceRank 6
                     :sourceGraphCandidateEvidenceScore 0.36
                     :rankScore 5.2})
               (row "surface/e.page"
                    5
                    {:candidateFileCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 1
                     :candidateSourceRank 8
                     :sourceGraphCandidateEvidenceScore 0.47
                     :rankScore 2.4})
               (row "surface/query-supported.page"
                    6
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 4
                     :matchedTokenCount 3
                     :candidateGrepScore 0.3
                     :candidateLexicalComponentBoost 0.1
                     :sourceGraphCandidateEvidenceScore 0.55
                     :rankScore 3.1})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (= "surface/query-supported.page" (nth paths 4)))
    (is (< (.indexOf paths "surface/query-supported.page")
           (.indexOf paths "surface/e.page")))))

(deftest compact-output-preserves-ranked-doc-head-over-late-query-source
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        doc-row (fn [path rank tokens pairs source-rank rank-score]
                  (row path
                       rank
                       {:docCount 1
                        :candidateFileCount 1
                        :entityCount 0
                        :retrievedSourceCount 1
                        :matchedTokenCount tokens
                        :matchedTokenPairCount pairs
                        :matchedPathQueryTokenCount 3
                        :retrievedPathQueryTokenBoost 7.25
                        :sourceGraphCandidateEvidenceScore 0.5
                        :candidateSourceRank source-rank
                        :rankScore rank-score}))
        files [(doc-row "tests/unit/adapters/http.test.js" 1 10 2 5 32.3)
               (doc-row "tests/unit/toFormData.test.js" 2 6 2 8 23.5)
               (doc-row "tests/unit/adapters/fetch.test.js" 3 9 1 7 21.1)
               (doc-row "lib/helpers/toFormData.js" 4 5 2 12 16.9)
               (doc-row "tests/setup/server.js" 5 5 1 6 15.1)
               (row "tests/unit/query.test.js"
                    17
                    {:candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 6
                     :matchedTokenCount 4
                     :matchedPathQueryTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.46
                     :candidateGrepScore 0.48
                     :rankScore 5.3})]
        selected (compact-output files 5 nil)]
    (is (= ["tests/unit/adapters/http.test.js"
            "tests/unit/toFormData.test.js"
            "tests/unit/adapters/fetch.test.js"
            "lib/helpers/toFormData.js"
            "tests/setup/server.js"]
           (mapv :path selected)))
    (is (not-any? #{"tests/unit/query.test.js"} (map :path selected)))))

(deftest compact-output-prune-preserves-ranked-doc-head-row
  (let [prune @#'benchmark-prediction/compact-output-prune-score-tail
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        direct-row (fn [path rank score]
                     (row path
                          rank
                          {:candidateFileCount 2
                           :docCount 0
                           :entityCount 0
                           :directFileCandidateCount 1
                           :fileIdentitySupportLabelCount 4
                           :architectureSupportBoost 5.0
                           :matchedTokenCount 8
                           :rankScore score}))
        selected [(direct-row "tests/unit/adapters/fetch.test.js" 1 23.0)
                  (row "tests/unit/adapters/http.test.js"
                       2
                       {:candidateFileCount 1
                        :docCount 1
                        :entityCount 0
                        :fileIdentitySupportLabelCount 4
                        :retrievedSourceCount 1
                        :matchedTokenCount 8
                        :matchedTokenPairCount 2
                        :matchedPathQueryTokenCount 3
                        :retrievedPathQueryTokenBoost 7.25
                        :sourceGraphCandidateEvidenceScore 0.36
                        :rankScore 22.0})
                  (direct-row "tests/unit/adapters/adapters.test.js" 3 21.0)
                  (row "lib/adapters/http.js"
                       4
                       {:candidateFileCount 8
                        :docCount 1
                        :entityCount 0
                        :directFileCandidateCount 1
                        :retrievedSourceCount 1
                        :matchedTokenCount 11
                        :matchedTokenPairCount 3
                        :matchedPathQueryTokenCount 2
                        :retrievedPathQueryTokenBoost 6.0
                        :sourceGraphCandidateEvidenceScore 0.33
                        :rankScore 16.6})
                  (row "lib/core/AxiosError.js"
                       5
                       {:candidateFileCount 2
                        :docCount 0
                        :entityCount 0
                        :matchedTokenCount 8
                        :rankScore 5.0})]
        result (prune selected 5 nil [] {})]
    (is (some #{"lib/adapters/http.js"} (map :path result)))
    (is (not= ["tests/unit/adapters/fetch.test.js"
               "tests/unit/adapters/http.test.js"
               "tests/unit/adapters/adapters.test.js"]
              (mapv :path result)))))

(deftest compact-output-frontloads-source-graph-query-evidence-head
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        doc-row (fn [path rank tokens pairs source-rank rank-score]
                  (row path
                       rank
                       {:docCount 1
                        :candidateFileCount 1
                        :entityCount 0
                        :retrievedSourceCount 1
                        :matchedTokenCount tokens
                        :matchedTokenPairCount pairs
                        :matchedPathQueryTokenCount 2
                        :retrievedPathQueryTokenBoost 6.0
                        :sourceGraphCandidateEvidenceScore 0.55
                        :candidateSourceRank source-rank
                        :rankScore rank-score}))
        source-query-row (fn [path rank metrics]
                           (row path
                                rank
                                (merge {:candidateFileCount 1
                                        :entityCount 0
                                        :matchedTokenCount 6
                                        :matchedTokenPairCount 2
                                        :matchedPathQueryTokenCount 2
                                        :sourceGraphCandidateEvidenceScore 0.56
                                        :sourceGraphQueryEvidenceBoost 9.0}
                                       metrics)))
        files [(doc-row "tests/Dapper.Tests/ParameterTests.cs" 1 7 3 7 22.6)
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                    2
                    {:candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 5
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.55
                     :architectureSupportBoost 0.81
                     :rankScore 23.0})
               (doc-row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                        3
                        6
                        2
                        2
                        14.5)
               (source-query-row "Dapper/SqlMapper.Settings.cs"
                                 4
                                 {:docCount 0
                                  :directFileCandidateCount 1
                                  :rankScore 24.3})
               (doc-row "tests/Dapper.Tests/EnumTests.cs" 5 4 1 27 23.7)
               (source-query-row "Dapper/SqlMapper.cs"
                                 6
                                 {:docCount 1
                                  :retrievedSourceCount 1
                                  :docSupportedSourceGraphQueryBoost 2.0
                                  :candidateFileCount 17
                                  :rankScore 23.6})]
        paths (mapv :path (compact-output files 10 nil))]
    (is (= ["tests/Dapper.Tests/ParameterTests.cs"
            "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
            "tests/Dapper.Tests/EnumTests.cs"
            "Dapper/SqlMapper.cs"
            "Dapper/SqlMapper.Settings.cs"]
           (subvec paths 0 5)))
    (is (< (.indexOf paths "Dapper/SqlMapper.Settings.cs")
           (.indexOf paths
                     "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs")))))

(deftest compact-output-frontloads-direct-doc-architecture-row
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        doc-row (fn [path rank tokens]
                  (row path
                       rank
                       {:docCount 1
                        :candidateFileCount 1
                        :entityCount 0
                        :retrievedSourceCount 1
                        :matchedTokenCount tokens
                        :matchedPathQueryTokenCount 2
                        :rankScore (- 20.0 rank)}))
        files [(doc-row "tests/unit/adapters/fetch.test.js" 1 11)
               (row "lib/core/AxiosError.js"
                    2
                    {:candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.36
                     :rankScore 19.6})
               (doc-row "lib/env/data.js" 3 2)
               (doc-row "tests/smoke/cjs/tests/fetch.smoke.test.cjs" 4 6)
               (row "tests/unit/adapters/adapters.test.js"
                    7
                    {:candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 5
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 3
                     :sourceGraphCandidateEvidenceScore 0.33
                     :rankScore 4.3})
               (row "lib/adapters/http.js"
                    8
                    {:candidateFileCount 4
                     :docCount 1
                     :entityCount 0
                     :directFileCandidateCount 1
                     :retrievedSourceCount 1
                     :architectureSupportBoost 0.96
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.33
                     :rankScore 15.0})]
        paths (mapv :path (compact-output files 10 nil))]
    (is (< (.indexOf paths "lib/adapters/http.js")
           (.indexOf paths "tests/unit/adapters/adapters.test.js")))
    (is (> 5 (.indexOf paths "lib/adapters/http.js")))))

(deftest compact-output-extends-retrieved-source-evidence-head
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        retrieved-row (fn [path rank rank-score source-rank grep source-score
                           tokens & [extra]]
                        (row path
                             rank
                             (merge {:docCount 1
                                     :candidateFileCount 1
                                     :entityCount 0
                                     :retrievedSourceCount 1
                                     :matchedTokenCount tokens
                                     :matchedPathQueryTokenCount 2
                                     :matchedTokenPairCount 1
                                     :candidateSourceRank source-rank
                                     :candidateGrepScore grep
                                     :sourceGraphCandidateEvidenceScore
                                     source-score
                                     :rankScore rank-score}
                                    extra)))
        files [(retrieved-row "workflows/node-sass.yml" 1 19.5 9 0.75 0.52 7)
               (retrieved-row "workflows/docs.yml" 2 15.9 8 0.66 0.54 7)
               (retrieved-row "workflows/browserstack.yml" 3 14.6 7 0.66 0.57 8)
               (retrieved-row "workflows/bundlewatch.yml" 4 14.4 11 0.66 0.51 7)
               (retrieved-row "workflows/lint.yml" 5 14.2 10 0.66 0.51 7)
               (retrieved-row "workflows/js.yml" 6 14.0 12 0.66 0.51 7)
               (retrieved-row "workflows/css.yml" 7 13.9 14 0.66 0.50 7)
               (retrieved-row "workflows/release-notes.yml"
                              10
                              13.4
                              15
                              0.12
                              0.43
                              6
                              {:matchedCompoundTokenPairCount 2})
               (retrieved-row "workflows/cspell.yml" 11 13.2 16 0.12 0.43 9)
               (row "workflows/issue-labeled.yml"
                    12
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 6
                     :rankScore 3.7})]
        paths (mapv :path (compact-output files 10 nil))]
    (is (= ["workflows/node-sass.yml"
            "workflows/docs.yml"
            "workflows/browserstack.yml"
            "workflows/bundlewatch.yml"
            "workflows/lint.yml"
            "workflows/js.yml"
            "workflows/css.yml"]
           (subvec paths 0 7)))
    (is (< (.indexOf paths "workflows/js.yml")
           (.indexOf paths "workflows/release-notes.yml")))
    (is (< (.indexOf paths "workflows/css.yml")
           (.indexOf paths "workflows/cspell.yml")))))

(deftest compact-output-spreads-candidate-support-signatures
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank signature]
              {:path path
               :rank rank
               :repo-id "fixture"
               :metrics {:rankScore (- 20.0 (* 0.1 rank))
                         :candidateFileCount 1
                         :docCount 1
                         :entityCount 0
                         :matchedTokenCount 4
                         :candidateSupportLabelSignatures
                         (when signature [signature])}})
        files [(row "migrations/.env"
                    1
                    ["database_url"])
               (row "migrations/schema-15.sql"
                    2
                    ["security definer"])
               (row "migrations/db/migrations/trigger.sql"
                    3
                    ["event trigger"])
               (row "migrations/schema.sql"
                    4
                    ["security definer"])
               (row "migrations/schema-orioledb-17.sql"
                    5
                    ["security definer"])
               (row "migrations/db/init-scripts/post-setup.sql"
                    6
                    ["createdb" "security definer"])]
        paths (mapv :path (compact-output files 6 nil))]
    (is (= ["migrations/.env"
            "migrations/schema-15.sql"
            "migrations/db/migrations/trigger.sql"
            "migrations/db/init-scripts/post-setup.sql"
            "migrations/schema.sql"
            "migrations/schema-orioledb-17.sql"]
           paths))))

(deftest compact-output-promotes-direct-support-owner-rows-with-shared-signature
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :repo-id "fixture"
               :metrics metrics})
        shared-signature [["src/core/error.js"]]
        files [(row "tests/transport/fetch-test.js" 1
                    {:rankScore 22.0
                     :candidateFileCount 5
                     :docCount 1
                     :entityCount 0
                     :retrievedSourceCount 1
                     :matchedTokenCount 11
                     :matchedTokenPairCount 3
                     :matchedPathQueryTokenCount 3
                     :sourceGraphCandidateEvidenceScore 0.38
                     :candidateGrepScore 0.42
                     :fileIdentitySupportLabelCount 4
                     :retrievedSupportLabelCount 5
                     :candidateSupportLabelSignatures shared-signature})
               (row "src/core/error.js" 2
                    {:rankScore 19.5
                     :candidateFileCount 2
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :sourceGraphCandidateEvidenceScore 0.36
                     :retrievedSupportLabelCount 3
                     :candidateSupportLabelSignatures [["src/core/headers.js"]]})
               (row "src/env/data.js" 14
                    {:rankScore 12.4
                     :candidateFileCount 1
                     :docCount 1
                     :entityCount 0
                     :retrievedSourceCount 1
                     :matchedTokenCount 6
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.36})
               (row "tests/smoke/fetch-smoke-test.js" 15
                    {:rankScore 6.1
                     :candidateFileCount 1
                     :docCount 1
                     :entityCount 0
                     :retrievedSourceCount 1
                     :matchedTokenCount 6
                     :matchedTokenPairCount 1
                     :matchedPathQueryTokenCount 1
                     :candidateGrepScore 0.28})
               (row "tests/cancel/canceled-error-test.js" 16
                    {:rankScore 3.1
                     :candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 9
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.33
                     :fileIdentitySupportLabelCount 2
                     :candidateSupportLabelSignatures [["src/cancel/canceled-error.js"]]})
               (row "tests/core/client-test.js" 17
                    {:rankScore 3.0
                     :candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 5
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 3
                     :sourceGraphCandidateEvidenceScore 0.33
                     :fileIdentitySupportLabelCount 3
                     :candidateSupportLabelSignatures [["src/core/client.js"]]})
               (row "src/core/headers.js" 18
                    {:rankScore 6.0
                     :candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 8
                     :matchedTokenPairCount 2
                     :matchedPathQueryTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.36
                     :retrievedSupportLabelCount 3})
               (row "src/transport/http.js" 19
                    {:rankScore 15.0
                     :candidateFileCount 7
                     :directFileCandidateCount 1
                     :docCount 1
                     :entityCount 0
                     :retrievedSourceCount 1
                     :matchedTokenCount 11
                     :matchedTokenPairCount 3
                     :matchedPathQueryTokenCount 2
                     :sourceGraphCandidateEvidenceScore 0.33
                     :supportOwnerEvidenceCount 1
                     :retrievedSupportLabelCount 3
                     :candidateSupportLabelSignatures shared-signature})
               (row "src/transport/fetch.js" 20
                    {:rankScore 12.2
                     :candidateFileCount 4
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 11
                     :matchedTokenPairCount 3
                     :matchedPathQueryTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.36
                     :candidateGrepScore 0.36
                     :fileIdentitySupportLabelCount 1
                     :retrievedSupportLabelCount 2
                     :candidateSupportLabelSignatures shared-signature})
               (row "tests/transport/http-test.js" 21
                    {:rankScore 11.4
                     :candidateFileCount 3
                     :directFileCandidateCount 1
                     :docCount 0
                     :entityCount 0
                     :matchedTokenCount 11
                     :matchedTokenPairCount 3
                     :matchedPathQueryTokenCount 4
                     :sourceGraphCandidateEvidenceScore 0.33
                     :supportOwnerEvidenceCount 1
                     :retrievedSupportLabelCount 4
                     :candidateSupportLabelSignatures shared-signature})]
        paths (mapv :path (compact-output files 10 nil))]
    (is (<= (.indexOf paths "src/transport/http.js") 4))
    (is (<= (.indexOf paths "tests/transport/http-test.js") 4))
    (is (< (.indexOf paths "tests/transport/http-test.js")
           (.indexOf paths "src/env/data.js")))))

(deftest compact-output-anchors-early-source-graph-grep-row
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "Directory.Build.props"
                    1
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedTokenCount 4
                     :rankScore 8.8})
               (row "Dapper.EntityFramework/Dapper.EntityFramework.csproj"
                    2
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 3
                     :rankScore 9.2})
               (row "benchmarks/Dapper.Tests.Performance/Dapper.Tests.Performance.csproj"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :matchedTokenCount 2
                     :rankScore 6.8})
               (row "Build.csproj"
                    10
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 14
                     :matchedTokenCount 0
                     :sourceGraphCandidateEvidenceScore 0.52
                     :candidateGrepScore 0.33
                     :rankScore 4.9})
               (row "tests/Directory.Build.props"
                    11
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 49
                     :matchedTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.53
                     :candidateGrepScore 0.74
                     :rankScore 2.6})
               (row "Directory.Packages.props"
                    14
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :candidateSourceRank 19
                     :matchedTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.44
                     :candidateGrepScore 0.53
                     :rankScore 0.83})]
        selected (compact-output files 10 nil)]
    (is (= ["Directory.Build.props"
            "Directory.Packages.props"]
           (subvec (mapv :path selected) 0 2)))
    (is (not= "Build.csproj" (second (mapv :path selected))))))

(deftest compact-output-reserves-retrieved-label-source-graph-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat (mapv (fn [idx]
                               (row (str "src/head-" idx ".cs")
                                    idx
                                    {:docCount 1
                                     :matchedTokenCount 2
                                     :rankScore (+ 10 idx)}))
                             (range 1 8))
                       [(row "Dapper/SqlMapper.cs"
                             16
                             {:candidateFileCount 3
                              :docCount 0
                              :entityCount 0
                              :candidateSourceRank 7
                              :matchedTokenCount 6
                              :matchedTokenPairCount 1
                              :matchedCompoundTokenPairCount 1
                              :matchedIdentityCompoundTokenPairCount 1
                              :sourceGraphCandidateEvidenceScore 6.1
                              :retrievedSupportLabelCount 3
                              :retrievedSupportLabelBoost 4.8
                              :candidateGrepScore 0.002
                              :rankScore 15.3})]))
        paths (mapv :path (compact-output files 7 nil))]
    (is (some #{"Dapper/SqlMapper.cs"} paths))
    (is (not-any? #{"src/head-7.cs"} paths))))

(deftest compact-output-reserves-retrieved-label-doc-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files (vec
               (concat (mapv (fn [idx]
                               (row (str "src/head-" idx ".cs")
                                    idx
                                    {:docCount 1
                                     :matchedTokenCount 2
                                     :rankScore (+ 10 idx)}))
                             (range 1 8))
                       [(row "tests/Dapper.Tests/TypeHandlerTests.cs"
                             12
                             {:candidateFileCount 1
                              :docCount 1
                              :entityCount 0
                              :candidateSourceRank 22
                              :matchedTokenCount 6
                              :matchedTokenPairCount 2
                              :matchedCompoundTokenPairCount 1
                              :sourceGraphCandidateEvidenceScore 8.6
                              :retrievedSupportLabelCount 4
                              :rankScore 24.5})]))
        paths (mapv :path (compact-output files 7 nil))]
    (is (some #{"tests/Dapper.Tests/TypeHandlerTests.cs"} paths))
    (is (not-any? #{"src/head-7.cs"} paths))))

(deftest compact-output-frontloads-retrieved-label-doc-query-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "Dapper/SqlMapper.cs"
                    1
                    {:docCount 1
                     :candidateFileCount 25
                     :retrievedSourceCount 1
                     :matchedTokenCount 9
                     :matchedCompoundTokenPairCount 1
                     :retrievedSupportLabelCount 5
                     :sourceGraphCandidateEvidenceScore 0.91
                     :rankScore 15.8})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                    2
                    {:docCount 0
                     :candidateFileCount 15
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.61
                     :candidateSourceRank 18
                     :rankScore 16.9})
               (row "Dapper/SqlMapper.TypeHandler.cs"
                    3
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 7
                     :matchedCompoundTokenPairCount 1
                     :retrievedSupportLabelCount 3
                     :sourceGraphCandidateEvidenceScore 0.77
                     :rankScore 11.8})
               (row "tests/docker-compose.yml"
                    4
                    {:docCount 0
                     :candidateFileCount 1
                     :directFileCandidateCount 1
                     :architectureSupportBoost 2.0
                     :matchedTokenCount 4
                     :sourceGraphCandidateEvidenceScore 4.89
                     :rankScore 11.0})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Linq2Sql.cs"
                    5
                    {:docCount 0
                     :candidateFileCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 3
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.61
                     :candidateSourceRank 18
                     :rankScore 16.3})
               (row "Dapper/SqlMapper.TypeDeserializerCache.cs"
                    6
                    {:docCount 0
                     :candidateFileCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 2
                     :matchedTokenCount 4
                     :sourceGraphCandidateEvidenceScore 0.69
                     :candidateSourceRank 11
                     :rankScore 15.1})
               (row "tests/Dapper.Tests/TypeHandlerTests.cs"
                    7
                    {:docCount 1
                     :candidateFileCount 1
                     :retrievedSourceCount 1
                     :matchedTokenCount 10
                     :matchedCompoundTokenPairCount 1
                     :retrievedSupportLabelCount 5
                     :sourceGraphCandidateEvidenceScore 0.78
                     :candidateGrepScore 0.73
                     :rankScore 11.8})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (> 5 (.indexOf paths "tests/Dapper.Tests/TypeHandlerTests.cs")))
    (is (< (.indexOf paths "tests/Dapper.Tests/TypeHandlerTests.cs")
           (.indexOf paths
                     "benchmarks/Dapper.Tests.Performance/Benchmarks.Linq2Sql.cs")))))

(deftest compact-output-frontloads-identity-compound-source-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/Dapper.Tests/ParameterTests.cs"
                    1
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedTokenCount 8
                     :matchedCompoundTokenPairCount 2
                     :architectureSupportBoost 4.81
                     :sourceGraphCandidateEvidenceScore 0.82
                     :candidateGrepScore 1.0
                     :rankScore 16.8})
               (row "Dapper/SqlMapper.cs"
                    2
                    {:docCount 1
                     :candidateFileCount 5
                     :matchedTokenCount 8
                     :matchedIdentityCompoundTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 1.0
                     :candidateGrepScore 0.86
                     :rankScore 14.1})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                    3
                    {:docCount 1
                     :candidateFileCount 2
                     :matchedTokenCount 7
                     :architectureSupportBoost 4.81
                     :rankScore 13.6})
               (row "benchmarks/Dapper.Tests.Performance/Benchmarks.Dapper.cs"
                    4
                    {:docCount 0
                     :candidateFileCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 5
                     :sourceGraphCandidateEvidenceScore 0.64
                     :candidateSourceRank 11
                     :rankScore 8.4})
               (row "tests/Dapper.Tests/MiscTests.cs"
                    5
                    {:docCount 1
                     :candidateFileCount 3
                     :matchedTokenCount 4
                     :sourceGraphCandidateEvidenceScore 0.59
                     :candidateGrepScore 0.77
                     :rankScore 12.8})
               (row "Dapper/SqlMapper.Settings.cs"
                    11
                    {:docCount 0
                     :candidateFileCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 2
                     :matchedTokenCount 7
                     :matchedCompoundTokenPairCount 1
                     :matchedIdentityCompoundTokenPairCount 1
                     :sourceGraphCandidateEvidenceScore 0.86
                     :candidateSourceRank 4
                     :rankScore 8.9})]
        paths (mapv :path (compact-output files 20 nil))]
    (is (> 5 (.indexOf paths "Dapper/SqlMapper.Settings.cs")))
    (is (< (.indexOf paths "Dapper/SqlMapper.Settings.cs")
           (.indexOf paths
                     "benchmarks/Dapper.Tests.Performance/Benchmarks.Dapper.cs")))))

(deftest compact-output-reserves-doc-supported-identity-evidence
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        candidate-row (fn [path rank source-rank]
                        (row path
                             rank
                             {:candidateFileCount 1
                              :docCount 0
                              :entityCount 0
                              :candidateSourceRank source-rank
                              :matchedTokenCount 6
                              :sourceGraphCandidateEvidenceScore 0.6
                              :candidateLexicalComponentBoost 0.1
                              :rankScore (- 12.0 rank)}))
        files [(row "benchmarks/Dapper.Tests.Performance/Benchmarks.Belgrade.cs"
                    1
                    {:docCount 1
                     :candidateFileCount 2
                     :retrievedSourceCount 1
                     :matchedTokenCount 7
                     :architectureSupportBoost 4.8
                     :rankScore 13.9})
               (row "tests/Dapper.Tests/SharedTypes/Enums.cs"
                    2
                    {:docCount 2
                     :candidateFileCount 1
                     :retrievedSourceCount 2
                     :repeatedRetrievedSourceBoost 3.2
                     :matchedTokenCount 6
                     :rankScore 11.7})
               (row "Dapper/SqlMapper.cs"
                    4
                    {:docCount 1
                     :candidateFileCount 5
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 7
                     :sourceGraphCandidateEvidenceScore 0.36
                     :rankScore 8.1})
               (row "tests/Dapper.Tests/TypeHandlerTests.cs"
                    5
                    {:docCount 5
                     :candidateFileCount 1
                     :retrievedSourceCount 5
                     :repeatedRetrievedSourceBoost 3.2
                     :matchedTokenCount 8
                     :rankScore 10.8})
               (candidate-row "benchmarks/Dapper.Tests.Performance/XPO/Post.cs"
                              8
                              12)
               (candidate-row "benchmarks/Dapper.Tests.Performance/Benchmarks.cs"
                              9
                              27)
               (candidate-row "Dapper/SimpleMemberMap.cs" 10 30)
               (candidate-row "benchmarks/Dapper.Tests.Performance/Post.cs"
                              11
                              32)
               (candidate-row "benchmarks/Dapper.Tests.Performance/Benchmarks.Mighty.cs"
                              12
                              23)]
        paths (mapv :path (compact-output files 20 nil))]
    (is (some #{"Dapper/SqlMapper.cs"} paths))
    (is (< (count paths) (count files)))))

(deftest compact-output-anchors-query-evidence-beside-selected-directory
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        doc-row (fn [path rank tokens]
                  {:path path
                   :rank rank
                   :metrics {:docCount 1
                             :matchedTokenCount tokens}})
        source-row (fn [path rank grep-score]
                     {:path path
                      :rank rank
                      :metrics {:candidateFileCount 1
                                :docCount 0
                                :entityCount 0
                                :matchedTokenCount 4
                                :sourceGraphCandidateEvidenceScore 7.0
                                :candidateGrepScore grep-score}})
        files (vec
               (concat [(doc-row "tests/unit/adapters/http.test.js" 1 7)
                        (doc-row "tests/setup/server.js" 2 6)]
                       (mapv #(doc-row (str "tests/noise-" % ".js") % 3)
                             (range 3 11))
                       [(source-row "tests/unit/adapters/fetch.test.js" 11 0.21)]))
        selected (compact-output files 10 nil)]
    (is (= ["tests/unit/adapters/http.test.js"
            "tests/unit/adapters/fetch.test.js"
            "tests/setup/server.js"]
           (subvec (mapv :path selected) 0 3)))))

(deftest compact-output-reserves-co-located-direct-candidate
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        doc-row (fn [path rank tokens]
                  {:path path
                   :rank rank
                   :metrics {:docCount 1
                             :candidateFileCount 1
                             :matchedTokenCount tokens}})
        direct-row (fn [path rank source-rank]
                     {:path path
                      :rank rank
                      :metrics {:docCount 0
                                :candidateFileCount 1
                                :directFileCandidateCount 1
                                :matchedTokenCount 4
                                :candidateSourceRank source-rank
                                :sourceGraphCandidateEvidenceScore 4.0}})
        files [(doc-row "variables.tf" 1 8)
               (doc-row "vpc-flow-logs.tf" 2 7)
               (doc-row "modules/flow-log/main.tf" 3 7)
               (direct-row "modules/vpc-endpoints/main.tf" 4 4)
               (direct-row "main.tf" 12 12)
               (direct-row "wrappers/flow-log/main.tf" 13 13)]
        selected (compact-output files 5 nil)]
    (is (= ["variables.tf"
            "vpc-flow-logs.tf"
            "main.tf"
            "modules/flow-log/main.tf"
            "modules/vpc-endpoints/main.tf"]
           (mapv :path selected)))))

(deftest compact-output-promotes-co-located-declared-source-kind-sibling
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        edit-row (fn [path rank source-rank kind & [matched-tokens]]
                   (row path
                        rank
                        {:candidateFileCount 1
                         :docCount 0
                         :entityCount 0
                         :candidateSourceRank source-rank
                         :matchedTokenCount (or matched-tokens 3)
                         :sourceGraphCandidateEvidenceScore 0.7
                         :candidateGrepScore 0.3
                         :candidateLexicalComponentBoost 0.1
                         :rankScore (- 14.0 rank)
                         :sourceKind kind}))
        files [(edit-row "connector/routingconnector/config.go" 1 1 "go")
               (edit-row "connector/routingconnector/factory_test.go" 2 2 "go")
               (edit-row "connector/routingconnector/factory.go" 3 3 "go")
               (edit-row "connector/routingconnector/metadata.yaml" 4 4 "yaml")
               (row "component/component.go" 5
                    {:docCount 0
                     :entityCount 1
                     :matchedTokenCount 3
                     :rankScore 11.0})
               (row "connector/connector.go" 6
                    {:docCount 0
                     :entityCount 1
                     :matchedTokenCount 3
                     :rankScore 10.0})
               (row "consumer/consumer.go" 7
                    {:docCount 0
                     :entityCount 1
                     :matchedTokenCount 3
                     :rankScore 9.0})
               (row "connector/routingconnector/config.schema.yaml" 8
                    {:candidateFileCount 1
                     :docCount 1
                     :entityCount 0
                     :candidateSourceRank 8
                     :matchedTokenCount 1
                     :sourceGraphCandidateEvidenceScore 0.7
                     :candidateGrepScore 0.3
                     :candidateLexicalComponentBoost 0.1
                     :rankScore 6.0
                     :sourceKind "yaml"})]
        kind-by-path (into {}
                           (map (juxt :path #(get-in % [:metrics :sourceKind])))
                           files)
        paths (mapv :path (compact-output files
                                          8
                                          nil
                                          ["go" "yaml"]
                                          kind-by-path))]
    (is (<= (.indexOf paths "connector/routingconnector/config.schema.yaml") 4))
    (is (< (.indexOf paths "connector/routingconnector/config.schema.yaml")
           (.indexOf paths "connector/connector.go")))))

(deftest compact-output-reserves-doc-supported-and-identity-supported-rows
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/type-handler.cs" 1
                    {:docCount 1
                     :matchedTokenCount 3})
               (row "src/type-handler.cs" 2
                    {:candidateFileCount 1
                     :docCount 0})
               (row "benchmarks/noise.cs" 3
                    {:candidateFileCount 1
                     :docCount 0})
               (row "tests/single-row.cs" 4
                    {:docCount 1
                     :matchedTokenCount 2})
               (row "benchmarks/other-noise.cs" 5
                    {:candidateFileCount 1
                     :docCount 0})
               (row "src/Mapper.cs" 7
                    {:docCount 1
                     :matchedTokenCount 7
                     :matchedCompoundTokenPairCount 2})
               (row "benchmarks/identity-noise.cs" 8
                    {:candidateFileCount 1
                     :docCount 0
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 4
                     :candidateLexicalComponentBoost 0.03
                     :graphNeighborScore 0.2})
               (row "src/Mapper.Settings.cs" 9
                    {:candidateFileCount 1
                     :docCount 0
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 4
                     :candidateLexicalComponentBoost 0.06
                     :graphNeighborScore 0.2})]
        selected (compact-output files 5 nil)]
    (is (= ["tests/type-handler.cs"
            "src/type-handler.cs"
            "benchmarks/noise.cs"
            "src/Mapper.cs"
            "src/Mapper.Settings.cs"]
           (mapv :path selected)))
    (is (= [1 2 3 4 5] (mapv :rank selected)))))

(deftest compact-output-reserves-repeated-retrieved-source-row
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/http.test.js" 1
                    {:candidateFileCount 1
                     :docCount 0
                     :architectureSupportBoost 5.0
                     :matchedTokenCount 8
                     :rareQueryTokenScore 2.0})
               (row "tests/smoke/files.test.js" 2
                    {:docCount 1
                     :matchedTokenCount 5
                     :retrievedSourceCount 1})
               (row "tests/setup/server.js" 3
                    {:docCount 1
                     :matchedTokenCount 5
                     :retrievedSourceCount 1})
               (row "tests/unit/adapters/fetch.test.js" 4
                    {:docCount 1
                     :matchedTokenCount 9
                     :retrievedSourceCount 1})
               (row "tests/unit/axiosHeaders.test.js" 5
                    {:candidateFileCount 1
                     :docCount 0
                     :matchedTokenCount 4})
               (row "lib/adapters/http.js" 8
                    {:docCount 2
                     :matchedTokenCount 2
                     :retrievedSourceCount 2
                     :repeatedRetrievedSourceBoost 2.4
                     :rankScore 20.0})]
        selected (compact-output files 5 nil)]
    (is (some #{"lib/adapters/http.js"} (map :path selected)))
    (is (not-any? #{"tests/unit/axiosHeaders.test.js"} (map :path selected)))))

(deftest compact-output-prunes-to-direct-and-retrieved-core
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/unit/adapters/http.test.js" 1
                    {:candidateFileCount 2
                     :docCount 0
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :architectureSupportBoost 5.0
                     :matchedTokenCount 8
                     :rankScore 14.6})
               (row "tests/smoke/files.test.js" 2
                    {:candidateFileCount 1
                     :docCount 1
                     :matchedTokenCount 5
                     :retrievedSourceCount 1
                     :rankScore 15.8})
               (row "tests/setup/server.js" 3
                    {:candidateFileCount 1
                     :docCount 1
                     :matchedTokenCount 5
                     :retrievedSourceCount 1
                     :rankScore 21.0})
               (row "lib/adapters/http.js" 4
                    {:candidateFileCount 1
                     :docCount 2
                     :matchedTokenCount 2
                     :retrievedSourceCount 2
                     :repeatedRetrievedSourceBoost 2.4
                     :rankScore 20.9})
               (row "tests/unit/adapters/fetch.test.js" 5
                    {:candidateFileCount 2
                     :docCount 1
                     :matchedTokenCount 9
                     :retrievedSourceCount 1
                     :rankScore 17.4})
               (row "tests/unit/axios.test.js" 6
                    {:candidateFileCount 1
                     :docCount 0
                     :matchedTokenCount 4
                     :rankScore 8.4})
               (row "tests/unit/axiosHeaders.test.js" 7
                    {:candidateFileCount 1
                     :docCount 0
                     :matchedTokenCount 4
                     :rankScore 8.3})]
        selected (compact-output files 7 nil)]
    (is (= ["tests/unit/adapters/http.test.js"
            "lib/adapters/http.js"]
           (mapv :path selected)))))

(deftest compact-output-core-prune-preserves-query-evidence-source-row
  (let [prune @#'benchmark-prediction/compact-output-prune-score-tail
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        selected [(row "tests/unit/adapters/http.test.js" 1
                       {:candidateFileCount 2
                        :docCount 0
                        :directFileCandidateCount 1
                        :fileIdentitySupportLabelCount 4
                        :architectureSupportBoost 5.0
                        :matchedTokenCount 8
                        :rankScore 14.6})
                  (row "lib/adapters/http.js" 2
                       {:candidateFileCount 1
                        :docCount 2
                        :matchedTokenCount 2
                        :retrievedSourceCount 2
                        :repeatedRetrievedSourceBoost 2.4
                        :rankScore 20.9})
                  (row "src/query_dispatch.py" 3
                       {:candidateFileCount 1
                        :docCount 0
                        :entityCount 0
                        :candidateSourceRank 182
                        :matchedTokenCount 2
                        :sourceGraphCandidateEvidenceScore 4.0
                        :candidateGrepScore 0.2
                        :rankScore 2.0})
                  (row "src/filler.py" 4
                       {:candidateFileCount 1
                        :docCount 0
                        :matchedTokenCount 4
                        :rankScore 8.3})]
        result (prune selected 7 nil [] {})]
    (is (= ["tests/unit/adapters/http.test.js"
            "lib/adapters/http.js"
            "src/query_dispatch.py"]
           (mapv :path result)))))

(deftest compact-output-core-prune-preserves-doc-and-architecture-evidence
  (let [prune @#'benchmark-prediction/compact-output-prune-score-tail
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        selected [(row "src/direct.cs" 1
                       {:candidateFileCount 2
                        :docCount 0
                        :directFileCandidateCount 1
                        :fileIdentitySupportLabelCount 4
                        :architectureSupportBoost 5.0
                        :matchedTokenCount 8
                        :rankScore 14.6})
                  (row "src/retrieved.cs" 2
                       {:candidateFileCount 1
                        :docCount 2
                        :matchedTokenCount 2
                        :retrievedSourceCount 2
                        :repeatedRetrievedSourceBoost 2.4
                        :rankScore 20.9})
                  (row "src/source_graph.cs" 3
                       {:candidateFileCount 1
                        :docCount 1
                        :retrievedSourceCount 1
                        :matchedTokenCount 8
                        :matchedTokenPairCount 2
                        :matchedCompoundTokenPairCount 1
                        :matchedIdentityCompoundTokenPairCount 1
                        :sourceGraphCandidateEvidenceScore 0.59
                        :rankScore 11.3})
                  (row "deploy/docker-compose.yml" 4
                       {:candidateFileCount 2
                        :docCount 0
                        :architectureSupportBoost 1.6
                        :matchedTokenCount 3
                        :matchedTokenPairCount 1
                        :rareQueryTokenScore 1.1
                        :rankScore 7.8})
                  (row "src/fill.cs" 5
                       {:candidateFileCount 1
                        :docCount 0
                        :matchedTokenCount 4
                        :rankScore 8.3})]
        result (prune selected 5 nil [] {})]
    (is (= ["src/direct.cs"
            "src/source_graph.cs"
            "src/retrieved.cs"
            "deploy/docker-compose.yml"]
           (mapv :path result)))))

(deftest compact-output-caps-all-candidate-only-surface
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank score]
              {:path path
               :rank rank
               :metrics {:candidateFileCount 1
                         :docCount 0
                         :entityCount 0
                         :matchedTokenCount 3
                         :rankScore score}})
        files [(row "site/src/pages/index.astro" 1 15.0)
               (row "site/src/pages/docs/index.astro" 2 9.8)
               (row "site/src/pages/docs/[version]/[...slug].astro" 3 8.9)
               (row "site/src/pages/docs/[version]/examples/index.astro" 4 8.8)
               (row "site/src/pages/docs/versions.astro" 5 7.7)
               (row "site/src/pages/docs/[version]/index.astro" 6 7.5)]
        kind-by-path (zipmap (map :path files)
                             (repeat "web-framework"))
        selected (compact-output files 7 nil ["web-framework"] kind-by-path)]
    (is (= ["site/src/pages/index.astro"
            "site/src/pages/docs/index.astro"
            "site/src/pages/docs/[version]/[...slug].astro"
            "site/src/pages/docs/[version]/examples/index.astro"]
           (mapv :path selected)))))

(deftest compact-output-prefers-architecture-supported-edit-surface
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "tests/http.test.js" 1
                    {:docCount 1
                     :matchedTokenCount 4})
               (row "tests/headers.test.js" 2
                    {:docCount 1
                     :matchedTokenCount 6})
               (row "lib/http.js" 3
                    {:docCount 0
                     :architectureSupportBoost 4.0
                     :matchedTokenCount 5
                     :rareQueryTokenScore 1.0})
               (row "tests/fetch.test.js" 4
                    {:docCount 1
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 6
                     :graphNeighborScore 0.2})]
        selected (compact-output files 5 nil)]
    (is (= ["tests/http.test.js"
            "tests/headers.test.js"
            "lib/http.js"
            "tests/fetch.test.js"]
           (mapv :path selected)))
    (is (= [1 2 3 4] (mapv :rank selected)))))

(deftest compact-output-prunes-score-separated-fill-tail
  (let [compact-output @#'benchmark-prediction/compact-output-selected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        files [(row "lib/adapters/http.js" 1
                    {:docCount 2
                     :matchedTokenCount 4
                     :rankScore 18.0})
               (row "tests/setup/server.js" 2
                    {:docCount 1
                     :matchedTokenCount 5
                     :rankScore 16.0})
               (row "tests/unit/adapters/http.test.js" 3
                    {:candidateFileCount 1
                     :docCount 0
                     :entityCount 0
                     :directFileCandidateCount 1
                     :fileIdentitySupportLabelCount 4
                     :matchedTokenCount 5
                     :rankScore 15.5})
               (row "tests/unit/adapters/fetch.test.js" 4
                    {:docCount 1
                     :matchedTokenCount 4
                     :rankScore 10.0})
               (row "tests/unit/adapters/adapters.test.js" 5
                    {:docCount 1
                     :matchedTokenCount 3
                     :rankScore 9.0})]
        selected (compact-output files 5 nil)]
    (is (= ["lib/adapters/http.js"
            "tests/setup/server.js"
            "tests/unit/adapters/http.test.js"]
           (mapv :path selected)))
    (is (= [1 2 3] (mapv :rank selected)))))

(deftest compact-agent-result-prefers-included-decision-candidate-paths
  (let [root (temp-dir "ygg-bench-compact-decision-output")
        _ (doseq [path ["docs/noise.md"
                        "src/noise.clj"
                        "src/app.clj"
                        "test/app_test.clj"]]
            (spit-file! root path "(ns fixture)\n"))
        packet {:query "change app test plan"
                :docs [{:source {:path "docs/noise.md"
                                 :heading "change app test plan"}
                        :score 10.0
                        :snippet "change app test plan"
                        :provenance "retrieved-doc"}
                       {:source {:path "src/noise.clj"
                                 :heading "change plan"}
                        :score 9.0
                        :snippet "change plan"
                        :provenance "retrieved-doc"}]}
        decision-candidates [{:id "change-app-and-test"
                              :kind "change-plan"
                              :paths ["src/app.clj"
                                      "test/app_test.clj"]}
                             {:id "change-missing-file"
                              :kind "change-plan"
                              :paths ["src/missing.clj"]}]
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :decision-kind "change-plan"
                 :decision-candidates decision-candidates
                 :limit 10
                 :compact-result? true
                 :compact-result-limit 5})
        choices-by-id (->> (get-in result [:decision :choices])
                           (map (juxt :id identity))
                           (into {}))]
    (is (= ["src/app.clj" "test/app_test.clj"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2] (mapv :rank (:suspectedFiles result))))
    (is (= "include" (get-in choices-by-id ["change-app-and-test" :status])))
    (is (= "defer" (get-in choices-by-id ["change-missing-file" :status])))
    (is (= 2 (get-in result [:selection :compactResultFiles])))))

(deftest compact-agent-result-caps-command-hints
  (let [packet {:query "broken app"
                :drilldowns [{:command "cmd-1"}
                             {:command "cmd-2"}
                             {:command "cmd-3"}
                             {:command "cmd-4"}
                             {:command "cmd-5"}
                             {:command "cmd-6"}]}
        result (benchmark/context-packet->agent-result
                packet
                {:compact-result? true})]
    (is (= ["cmd-1" "cmd-2" "cmd-3" "cmd-4" "cmd-5"]
           (:commands result)))))

(deftest limited-agent-result-preserves-declared-source-kind-diversity
  (let [root (temp-dir "ygg-bench-source-kind-diversity")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/other.clj" "(ns other)\n")
        _ (spit-file! root "config/.env" "DATABASE_URL=postgres://example\n")
        packet {:query "runtime configuration"
                :candidateFiles [{:path "src/app.clj"
                                  :rank 1
                                  :score 1.0
                                  :targetKind "chunk"
                                  :label "runtime configuration"}
                                 {:path "src/other.clj"
                                  :rank 2
                                  :score 0.95
                                  :targetKind "chunk"
                                  :label "configuration helper"}
                                 {:path "config/.env"
                                  :rank 3
                                  :score 0.1
                                  :targetKind "node"
                                  :label "DATABASE_URL"}]}
        result (benchmark/context-packet->agent-result
                packet
                {:root root
                 :limit 2
                 :coverage {:declaredSourceKinds ["code" "env"]}})]
    (is (= ["src/app.clj" "config/.env"]
           (mapv :path (:suspectedFiles result))))
    (is (= [1 2]
           (mapv :rank (:suspectedFiles result))))
    (is (= {:rawCandidateFiles 3
            :candidateFiles 3
            :coverageFilteredCandidateFiles 0
            :limit 2
            :coverageSourceKinds ["code" "env"]
            :candidateFileOnlyQuota 2
            :candidateFileOnlySelected 2}
           (:selection result)))))

(deftest source-kind-lanes-prefer-strong-mechanical-evidence
  (let [prioritize @#'benchmark-prediction/prioritize-coverage-source-lanes
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "tests/setup/server.js"
                   1
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :matchedTokenCount 5
                    :rankScore 21.0})
              (row "tests/smoke/files.test.js"
                   2
                   {:docCount 1
                    :candidateFileCount 1
                    :retrievedSourceCount 1
                    :matchedTokenCount 5
                    :rankScore 15.0})
              (row "lib/adapters/http.js"
                   4
                   {:docCount 2
                    :candidateFileCount 1
                    :retrievedSourceCount 2
                    :repeatedRetrievedSourceBoost 2.4
                    :matchedTokenCount 2
                    :rankScore 20.9})]
        kind-by-path {"tests/setup/server.js" "javascript"
                      "tests/smoke/files.test.js" "javascript"
                      "lib/adapters/http.js" "javascript"}]
    (is (= ["lib/adapters/http.js"
            "tests/setup/server.js"
            "tests/smoke/files.test.js"]
           (mapv :path (prioritize ["javascript"] kind-by-path rows))))))

(deftest source-kind-lanes-prefer-architecture-supported-runtime-row
  (let [prioritize @#'benchmark-prediction/prioritize-coverage-source-lanes
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "ansible/files/kong_config/kong.env.j2"
                   1
                   {:candidateFileCount 1
                    :directFileCandidateCount 1
                    :matchedTokenCount 4
                    :rankScore 10.4})
              (row "migrations/db/migrations/event-trigger.sql"
                   2
                   {:docCount 1
                    :candidateFileCount 2
                    :architectureSupportBoost 5.25
                    :matchedTokenCount 5
                    :rankScore 21.4})
              (row "migrations/.env"
                   4
                   {:candidateFileCount 2
                    :architectureSupportBoost 1.25
                    :matchedTokenCount 3
                    :rankScore 10.3})]
        kind-by-path {"ansible/files/kong_config/kong.env.j2" "env"
                      "migrations/.env" "env"
                      "migrations/db/migrations/event-trigger.sql" "sql"}]
    (is (= ["migrations/.env"
            "migrations/db/migrations/event-trigger.sql"
            "ansible/files/kong_config/kong.env.j2"]
           (mapv :path (prioritize ["env" "sql"] kind-by-path rows))))))

(deftest source-kind-lanes-require-competitive-support-score
  (let [prioritize @#'benchmark-prediction/prioritize-coverage-source-lanes
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "site/src/pages/docs/index.astro"
                   1
                   {:candidateFileCount 2
                    :architectureSupportBoost 1.25
                    :matchedTokenCount 3
                    :rankScore 9.8})
              (row "site/src/pages/index.astro"
                   2
                   {:candidateFileCount 1
                    :matchedTokenCount 4
                    :rankScore 15.0})
              (row "site/src/pages/docs/[version]/examples/index.astro"
                   3
                   {:candidateFileCount 1
                    :matchedTokenCount 3
                    :rankScore 8.8})]
        kind-by-path {"site/src/pages/docs/index.astro" "web-framework"
                      "site/src/pages/index.astro" "web-framework"
                      "site/src/pages/docs/[version]/examples/index.astro" "web-framework"}]
    (is (= ["site/src/pages/index.astro"
            "site/src/pages/docs/index.astro"
            "site/src/pages/docs/[version]/examples/index.astro"]
           (mapv :path (prioritize ["web-framework"] kind-by-path rows))))))

(deftest source-kind-lanes-allow-direct-identity-support-below-score-floor
  (let [prioritize @#'benchmark-prediction/prioritize-coverage-source-lanes
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "tests/setup/server.js"
                   1
                   {:candidateFileCount 1
                    :docCount 1
                    :matchedTokenCount 5
                    :rankScore 21.0})
              (row "tests/unit/adapters/fetch.test.js"
                   2
                   {:candidateFileCount 2
                    :docCount 1
                    :architectureSupportBoost 4.7
                    :matchedTokenCount 9
                    :rankScore 17.4})
              (row "tests/unit/adapters/http.test.js"
                   6
                   {:candidateFileCount 2
                    :directFileCandidateCount 1
                    :fileIdentitySupportLabelCount 4
                    :architectureSupportBoost 5.0
                    :matchedTokenCount 8
                    :rankScore 14.6})]
        kind-by-path {"tests/setup/server.js" "javascript"
                      "tests/unit/adapters/fetch.test.js" "javascript"
                      "tests/unit/adapters/http.test.js" "javascript"}]
    (is (= ["tests/unit/adapters/http.test.js"
            "tests/setup/server.js"
            "tests/unit/adapters/fetch.test.js"]
           (mapv :path (prioritize ["javascript"] kind-by-path rows))))))

(deftest limited-agent-result-prunes-unsaturated-decision-tail
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "package.json" 1
                   {:decisionCandidateCount 2
                    :candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :rankScore 20.0})
              (row "site/astro.config.ts" 2
                   {:decisionCandidateCount 1
                    :candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :rankScore 18.0})
              (row "site/src/pages/docs/[version]/[...slug].astro" 3
                   {:docCount 1
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 9.25})
              (row "site/src/pages/docs/[version]/index.astro" 4
                   {:docCount 1
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 8.75})
              (row "site/src/pages/docs/index.astro" 5
                   {:docCount 0
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 2.0})]
        result (select-limited rows 20)]
    (is (= ["package.json"
            "site/astro.config.ts"
            "site/src/pages/docs/[version]/[...slug].astro"]
           (mapv :path (:files result))))
    (is (= 2 (:unsaturatedDecisionTailPruned result)))
    (is (= [1 2 3] (mapv :rank (:files result))))))

(deftest limited-agent-result-does-not-prune-declared-source-kind-tail
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        row (fn [path rank metrics]
              {:path path
               :rank rank
               :metrics metrics})
        rows [(row "src/app.clj" 1
                   {:decisionCandidateCount 1
                    :candidateFileCount 1
                    :docCount 0
                    :entityCount 0
                    :rankScore 20.0})
              (row "src/other.clj" 2
                   {:docCount 1
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 8.0})
              (row "src/third.clj" 3
                   {:docCount 1
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 7.0})
              (row "config/.env" 4
                   {:docCount 0
                    :candidateFileCount 1
                    :entityCount 0
                    :rankScore 1.0})]
        result (select-limited rows
                               20
                               {:source-kinds ["code" "env"]
                                :kind-by-path {"src/app.clj" "code"
                                               "src/other.clj" "code"
                                               "src/third.clj" "code"
                                               "config/.env" "env"}})]
    (is (= ["src/app.clj" "src/other.clj" "src/third.clj" "config/.env"]
           (mapv :path (:files result))))
    (is (nil? (:unsaturatedDecisionTailPruned result)))))

(deftest limited-agent-result-prunes-score-elbow-tail
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        row (fn [path rank score]
              {:path path
               :rank rank
               :metrics {:docCount 1
                         :candidateFileCount 1
                         :entityCount 0
                         :rankScore score}})
        rows [(row "src/strong-a.clj" 1 20.0)
              (row "src/strong-b.clj" 2 18.0)
              (row "src/tail-a.clj" 3 9.0)
              (row "src/tail-b.clj" 4 8.0)
              (row "src/tail-c.clj" 5 4.0)
              (row "src/tail-d.clj" 6 2.0)]
        result (select-limited rows 20)]
    (is (= ["src/strong-a.clj" "src/strong-b.clj"]
           (mapv :path (:files result))))
    (is (= 4 (:scoreElbowTailPruned result)))
    (is (= 18.0 (:scoreElbowTailScoreFloor result)))))

(deftest limited-agent-result-does-not-prune-score-elbow-source-kind-tail
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        row (fn [path rank score]
              {:path path
               :rank rank
               :metrics {:docCount 1
                         :candidateFileCount 1
                         :entityCount 0
                         :rankScore score}})
        rows [(row "src/strong-a.clj" 1 20.0)
              (row "src/strong-b.clj" 2 18.0)
              (row "config/runtime.env" 3 9.0)
              (row "src/tail-a.clj" 4 8.0)
              (row "src/tail-b.clj" 5 4.0)
              (row "src/tail-c.clj" 6 2.0)]
        result (select-limited rows
                               20
                               {:source-kinds ["code" "env"]
                                :kind-by-path {"src/strong-a.clj" "code"
                                               "src/strong-b.clj" "code"
                                               "config/runtime.env" "env"
                                               "src/tail-a.clj" "code"
                                               "src/tail-b.clj" "code"
                                               "src/tail-c.clj" "code"}})]
    (is (= ["src/strong-a.clj"
            "src/strong-b.clj"
            "config/runtime.env"
            "src/tail-a.clj"
            "src/tail-b.clj"
            "src/tail-c.clj"]
           (mapv :path (:files result))))
    (is (nil? (:scoreElbowTailPruned result)))))

(deftest limited-agent-result-does-not-prune-score-elbow-saturated-limit
  (let [select-limited @#'benchmark-prediction/select-limited-suspected-files
        row (fn [path rank score]
              {:path path
               :rank rank
               :metrics {:docCount 1
                         :candidateFileCount 1
                         :entityCount 0
                         :rankScore score}})
        rows [(row "src/strong-a.clj" 1 20.0)
              (row "src/strong-b.clj" 2 18.0)
              (row "src/tail-a.clj" 3 9.0)
              (row "src/tail-b.clj" 4 8.0)
              (row "src/tail-c.clj" 5 4.0)
              (row "src/tail-d.clj" 6 2.0)]
        result (select-limited rows 6)]
    (is (= ["src/strong-a.clj"
            "src/strong-b.clj"
            "src/tail-a.clj"
            "src/tail-b.clj"
            "src/tail-c.clj"
            "src/tail-d.clj"]
           (mapv :path (:files result))))
    (is (nil? (:scoreElbowTailPruned result)))))

(deftest inspection-file-result-scope-frontloads-file-and-repo-candidate-lanes
  (let [core-root (temp-dir "ygg-bench-inspection-core")
        contrib-root (temp-dir "ygg-bench-inspection-contrib")
        _ (doseq [[root paths] [[core-root ["connector/logs_router.go"
                                            "connector/metrics_router.go"
                                            "connector/connector.go"
                                            "consumer/consumer.go"
                                            "component/component.go"]]
                                [contrib-root ["connector/routingconnector/factory.go"
                                               "connector/routingconnector/config.go"
                                               "connector/routingconnector/logs.go"
                                               "connector/routingconnector/router.go"]]]]
            (doseq [path paths]
              (spit-file! root path "package fixture\n")))
        packet {:query "connector contract inspection"
                :docs [{:source {:repo "core"
                                 :path "connector/logs_router.go"
                                 :heading "connector/logs_router/LogsRouterAndConsumer"}
                        :score 8.0
                        :snippet "connector contract inspection"
                        :provenance "retrieved-doc"}
                       {:source {:repo "contrib"
                                 :path "connector/routingconnector/logs.go"
                                 :heading "connector/routingconnector/logs/newLogsConnector"}
                        :score 7.5
                        :snippet "connector contract inspection"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:repo "core"
                                  :path "connector/logs_router.go"
                                  :rank 2
                                  :score 2.2
                                  :targetKind "node"
                                  :label "connector/logs_router/LogsRouterAndConsumer"
                                  :scoreComponents {:sourceGraph 2.2
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "core"
                                  :path "component/component.go"
                                  :rank 3
                                  :score 1.3
                                  :targetKind "file"
                                  :label "component/component.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.7}}
                                 {:repo "core"
                                  :path "consumer/consumer.go"
                                  :rank 4
                                  :score 1.3
                                  :targetKind "file"
                                  :label "consumer/consumer.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.7}}
                                 {:repo "core"
                                  :path "connector/metrics_router.go"
                                  :rank 16
                                  :score 1.25
                                  :targetKind "file"
                                  :label "connector/metrics_router.go"
                                  :scoreComponents {:sourceGraph 1.25
                                                    :lexical 0.7
                                                    :graph 0.2}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/config.go"
                                  :rank 12
                                  :score 2.1
                                  :targetKind "node"
                                  :label "connector/routingconnector/config/Config"
                                  :supportLabels ["connector/routingconnector/config/Action"
                                                  "connector/routingconnector/config/Config"
                                                  "connector/routingconnector/config/RoutingTableItem"]
                                  :scoreComponents {:sourceGraph 2.1
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/router.go"
                                  :rank 13
                                  :score 2.0
                                  :targetKind "node"
                                  :label "connector/routingconnector/router/router"
                                  :supportLabels ["connector/routingconnector/router/router.registerRouteConsumers"]
                                  :scoreComponents {:sourceGraph 2.0
                                                    :lexical 0.8
                                                    :graph 0.2}}
                                 {:repo "core"
                                  :path "connector/connector.go"
                                  :rank 15
                                  :score 1.3
                                  :targetKind "file"
                                  :label "connector/connector.go"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :lexical 0.4}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/factory.go"
                                  :rank 10
                                  :score 2.2
                                  :targetKind "node"
                                  :label "connector/routingconnector/factory.go"
                                  :supportLabels ["connector/routingconnector/factory/createTracesToTraces"
                                                  "connector/routingconnector/factory/defaultErrorMode"]
                                  :scoreComponents {:sourceGraph 2.2
                                                    :lexical 0.9}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/logs.go"
                                  :rank 11
                                  :score 2.15
                                  :targetKind "node"
                                  :label "connector/routingconnector/logs.go"
                                  :supportLabels ["connector/routingconnector/logs/logsConnector"
                                                  "connector/routingconnector/logs/newLogsConnector"]
                                  :scoreComponents {:sourceGraph 2.15
                                                    :lexical 0.9}}]}
        result (benchmark/context-packet->agent-result
                packet
                {:roots {"core" core-root
                         "contrib" contrib-root}
                 :limit 5
                 :result-scope :inspection-files})
        compact-result (benchmark/context-packet->agent-result
                        packet
                        {:roots {"core" core-root
                                 "contrib" contrib-root}
                         :limit 20
                         :result-scope :inspection-files
                         :compact-result? true
                         :compact-result-limit 5})
        files (:suspectedFiles result)]
    (is (= [{:repo-id "core"
             :path "component/component.go"
             :rank 1}
            {:repo-id "core"
             :path "consumer/consumer.go"
             :rank 2}
            {:repo-id "core"
             :path "connector/connector.go"
             :rank 3}
            {:repo-id "contrib"
             :path "connector/routingconnector/factory.go"
             :rank 4}
            {:repo-id "contrib"
             :path "connector/routingconnector/config.go"
             :rank 5}]
           (mapv #(select-keys % [:repo-id :path :rank]) files)))
    (is (= 10 (get-in files [3 :metrics :candidateSourceRank])))
    (is (= {:rawCandidateFiles 9
            :candidateFiles 9
            :coverageFilteredCandidateFiles 0
            :limit 5
            :coverageSourceKinds []
            :inspectionDirectFileSelected 3
            :inspectionRepoCandidateSelected 2
            :inspectionCandidateFillSkipped 4}
           (:selection result)))
    (let [paths (mapv (juxt :repoId :path) (:suspectedFiles compact-result))]
      (is (= [["core" "component/component.go"]
              ["core" "consumer/consumer.go"]
              ["core" "connector/connector.go"]
              ["contrib" "connector/routingconnector/factory.go"]
              ["contrib" "connector/routingconnector/config.go"]]
             paths))
      (is (= 5 (get-in compact-result [:selection :compactResultLimit])))
      (is (= 5 (get-in compact-result [:selection :compactResultFiles]))))))

(deftest inspection-file-result-scope-frontloads-query-identity-candidates
  (let [core-root (temp-dir "ygg-bench-inspection-identity-core")
        contrib-root (temp-dir "ygg-bench-inspection-identity-contrib")
        _ (doseq [[root paths] [[core-root ["connector/connector.go"
                                            "consumer/consumer.go"
                                            "component/component.go"
                                            "connector/traces_router.go"]]
                                [contrib-root ["connector/routingconnector/traces.go"
                                               "connector/routingconnector/router.go"
                                               "connector/routingconnector/factory.go"]]]]
            (doseq [path paths]
              (spit-file! root path "package fixture\n")))
        packet {:query "component consumer connector contract inspection"
                :docs [{:source {:repo "core"
                                 :path "component/component.go"
                                 :heading "component/component"}
                        :score 8.0
                        :snippet "component consumer connector contract"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:repo "core"
                                 :path "consumer/consumer.go"
                                 :heading "consumer/consumer"}
                        :score 7.9
                        :snippet "component consumer connector contract"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:repo "core"
                                 :path "connector/connector.go"
                                 :heading "connector/connector"}
                        :score 7.8
                        :snippet "component consumer connector contract"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]
                :architecture {:dependencyEvidence [{:repo "contrib"
                                                     :path "connector/routingconnector/factory.go"
                                                     :kind "package-import"
                                                     :label "go:fixture/connector"
                                                     :relation "imports-package"
                                                     :score 2.0}]}
                :candidateFiles [{:repo "contrib"
                                  :path "connector/routingconnector/traces.go"
                                  :rank 1
                                  :score 2.3
                                  :targetKind "node"
                                  :label "connector/routingconnector/traces"
                                  :supportLabels ["connector/routingconnector/traces/tracesConnector"]
                                  :scoreComponents {:sourceGraph 2.3
                                                    :grep 0.9
                                                    :lexical 0.9}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/router.go"
                                  :rank 2
                                  :score 2.2
                                  :targetKind "node"
                                  :label "connector/routingconnector/router"
                                  :supportLabels ["connector/routingconnector/router/registerConsumers"]
                                  :scoreComponents {:sourceGraph 2.2
                                                    :grep 0.9
                                                    :lexical 0.9}}
                                 {:repo "core"
                                  :path "component/component.go"
                                  :rank 8
                                  :score 1.4
                                  :targetKind "node"
                                  :label "component/component"
                                  :scoreComponents {:sourceGraph 1.4
                                                    :grep 0.4
                                                    :lexical 0.8}}
                                 {:repo "core"
                                  :path "consumer/consumer.go"
                                  :rank 9
                                  :score 1.3
                                  :targetKind "node"
                                  :label "consumer/consumer"
                                  :scoreComponents {:sourceGraph 1.3
                                                    :grep 0.3
                                                    :lexical 0.8}}
                                 {:repo "core"
                                  :path "connector/connector.go"
                                  :rank 10
                                  :score 1.2
                                  :targetKind "chunk"
                                  :label "connector/connector"
                                  :scoreComponents {:sourceGraph 1.2
                                                    :grep 0.2
                                                    :lexical 0.6}}
                                 {:repo "contrib"
                                  :path "connector/routingconnector/factory.go"
                                  :rank 11
                                  :score 1.2
                                  :targetKind "node"
                                  :label "connector/routingconnector/factory"
                                  :scoreComponents {:sourceGraph 1.2
                                                    :grep 0.2
                                                    :lexical 0.6}}]}
        result (benchmark/context-packet->agent-result
                packet
                {:roots {"core" core-root
                         "contrib" contrib-root}
                 :limit 5
                 :result-scope :inspection-files})]
    (is (= [{:repo-id "core"
             :path "component/component.go"
             :rank 1}
            {:repo-id "core"
             :path "consumer/consumer.go"
             :rank 2}
            {:repo-id "core"
             :path "connector/connector.go"
             :rank 3}
            {:repo-id "contrib"
             :path "connector/routingconnector/factory.go"
             :rank 4}]
           (mapv #(select-keys % [:repo-id :path :rank])
                 (take 4 (:suspectedFiles result)))))
    (is (= 3 (get-in result [:selection :inspectionIdentitySelected])))
    (is (= 0 (get-in result [:selection :inspectionDirectFileSelected])))))
(deftest file-ranking-preserves-early-retrieved-source-order
  (let [root (temp-dir "ygg-bench-retrieved-rank")
        _ (spit-file! root "src/early.clj" "(ns early)\n")
        _ (spit-file! root "src/later.clj" "(ns later)\n")
        packet {:query "json handler conversion"
                :docs [{:source {:path "src/unrelated-1.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/early.clj"
                                 :heading "handler"}
                        :score 1.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/unrelated-2.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/unrelated-3.clj"
                                 :heading "unrelated"}
                        :score 9.0
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}
                       {:source {:path "src/later.clj"
                                 :heading "handler"}
                        :score 1.05
                        :snippet "json handler conversion"
                        :retrievedSource true
                        :provenance "retrieved-doc"}]}
        result (benchmark/context-packet->agent-result packet {:root root})
        files (:suspectedFiles result)]
    (is (= ["src/early.clj" "src/later.clj"]
           (mapv :path files)))
    (is (> (get-in files [0 :metrics :sourceRankScore])
           (get-in files [1 :metrics :sourceRankScore])))
    (is (> (get-in files [0 :metrics :rankScore])
           (get-in files [1 :metrics :rankScore])))))
(deftest local-vector-baseline-shells-out-and-scores-agent-result
  (let [repo (temp-dir "ygg-bench-local-vector-repo")
        out (temp-dir "ygg-bench-local-vector-out")
        suite-dir (temp-dir "ygg-bench-local-vector-suite")
        worker-dir (temp-dir "ygg-bench-local-vector-worker")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        worker-path (spit-file!
                     worker-dir
                     "fake-vector-worker.sh"
                     (str "#!/bin/sh\n"
                          "case_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseId\"])' \"$1\")\n"
                          "case_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseFingerprint\"])' \"$1\")\n"
                          "agent_input_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentInputFingerprint\"])' \"$1\")\n"
                          "agent_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentId\"])' \"$1\")\n"
                          "cat > \"$2\" <<JSON\n"
                          "{\"schema\":\"ygg.benchmark.agent-result/v2\","
                          "\"caseId\":\"$case_id\","
                          "\"caseFingerprint\":\"$case_fingerprint\","
                          "\"agentInputFingerprint\":\"$agent_input_fingerprint\","
                          "\"agentId\":\"$agent_id\","
                          "\"mode\":\"local-vector\","
                          "\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake local vector match\","
                          "\"evidence\":[\"fake-vector:src/app.clj\"]}],"
                          "\"suspectedSymbols\":[],\"commands\":[\"fake-vector-worker\"],"
                          "\"warnings\":[],\"summary\":\"fake vector result\"}\n"
                          "JSON\n"))]
    (.setExecutable (io/file worker-path) true)
    (sh! "git" "init" repo)
    (git! repo "config" "user.email" "bench@example.test")
    (git! repo "config" "user.name" "Benchmark Test")
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base (commit! repo "base")]
      (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :new)\n")
      (let [fix (commit! repo "fix")]
        (spit-file!
         suite-dir
         "benchmark.edn"
         (pr-str {:id "fixture"
                  :repos [{:id "repo" :root repo}]
                  :cases [{:id "case-1"
                           :repo-id "repo"
                           :base-sha base
                           :fix-sha fix
                           :issue {:id 1
                                   :title "broken app"
                                   :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-baselines!
                      suite
                      {:out out
                       :case-id "case-1"
                       :retriever "local-vector"
                       :vector-command worker-path
                       :vector-model "fake-local-model"})
              baseline (first (:baselines result))
              request (json/read-json
                       (slurp (get-in baseline [:artifacts :localVectorRequestPath]))
                       :key-fn keyword)
              scored (json/read-json
                      (slurp (get-in baseline [:artifacts :agentScorePath]))
                      :key-fn keyword)
              resumed (benchmark/agent-baselines!
                       suite
                       {:out out
                        :case-id "case-1"
                        :retriever "local-vector"
                        :vector-command "missing-vector-worker"
                        :vector-model "fake-local-model"
                        :skip-existing? true})
              skipped (first (:baselines resumed))]
          (is (= benchmark/agent-baselines-schema (:schema result)))
          (is (= "ygg-baseline-local-vector" (:agentId baseline)))
          (is (= "local-vector" (:mode baseline)))
          (is (= "local-vector" (:retriever baseline)))
          (is (= "fake-local-model" (get-in baseline [:localVector :model])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "ygg.benchmark.local-vector-request/v1" (:schema request)))
          (is (= (:caseFingerprint baseline) (:caseFingerprint request)))
          (is (= (:agentInputFingerprint baseline)
                 (:agentInputFingerprint request)))
          (is (= "fake-local-model" (:model request)))
          (is (not (contains? request :groundTruth)))
          (is (= benchmark/agent-result-schema (get-in scored [:agent :schema])))
          (is (= "local-vector" (get-in scored [:agent :mode])))
          (is (empty? (get-in scored [:agent :warnings])))
          (is (= 1.0 (get-in scored [:scores :evidenceCitationRate])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))
(deftest local-vector-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-local-vector-worker-repo")
        module-dir (temp-dir "ygg-bench-local-vector-worker-module")
        request-dir (temp-dir "ygg-bench-local-vector-worker-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.local-vector-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-local-vector"
                       :mode "local-vector"
                       :worktreeRoot repo
                       :input {:title "broken app"
                               :body "The app returns the old value."}
                       :limit 2
                       :model "fake-local-model"})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! repo "docs/readme.md" "Quiet documentation.\n")
    (spit-file!
     module-dir
     "sentence_transformers/__init__.py"
     (str "class SentenceTransformer:\n"
          "    def __init__(self, name):\n"
          "        self.name = name\n"
          "    def encode(self, texts, batch_size=None, normalize_embeddings=True, show_progress_bar=False):\n"
          "        vectors = []\n"
          "        for text in texts:\n"
          "            if 'broken' in text.lower():\n"
          "                vectors.append([1.0, 0.0])\n"
          "            else:\n"
          "                vectors.append([0.0, 1.0])\n"
          "        return vectors\n"))
    (let [{:keys [exit err]} (shell/sh "env"
                                       (str "PYTHONPATH=" module-dir)
                                       "python3"
                                       "scripts/local-vector-baseline.py"
                                       request-path
                                       result-path
                                       "fake-local-model")]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "ygg-baseline-local-vector" (:agentId result)))
          (is (= "local-vector" (:mode result)))
          (is (= ["src/app.clj" "docs/readme.md"]
                 (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (seq (:commands result))))))))

(deftest codebase-memory-baseline-shells-out-and-scores-agent-result
  (let [repo (temp-dir "ygg-bench-codebase-memory-repo")
        out (temp-dir "ygg-bench-codebase-memory-out")
        suite-dir (temp-dir "ygg-bench-codebase-memory-suite")
        worker-dir (temp-dir "ygg-bench-codebase-memory-worker")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        worker-path (spit-file!
                     worker-dir
                     "fake-codebase-memory-worker.sh"
                     (str "#!/bin/sh\n"
                          "case_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseId\"])' \"$1\")\n"
                          "case_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseFingerprint\"])' \"$1\")\n"
                          "agent_input_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentInputFingerprint\"])' \"$1\")\n"
                          "agent_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentId\"])' \"$1\")\n"
                          "cat > \"$2\" <<JSON\n"
                          "{\"schema\":\"ygg.benchmark.agent-result/v2\","
                          "\"caseId\":\"$case_id\","
                          "\"caseFingerprint\":\"$case_fingerprint\","
                          "\"agentInputFingerprint\":\"$agent_input_fingerprint\","
                          "\"agentId\":\"$agent_id\","
                          "\"mode\":\"codebase-memory\","
                          "\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake codebase memory match\","
                          "\"evidence\":[\"fake-codebase-memory path=src/app.clj\"]}],"
                          "\"suspectedSymbols\":[],\"commands\":[\"fake-codebase-memory-worker\"],"
                          "\"warnings\":[],\"summary\":\"fake codebase memory result\","
                          "\"tokenUsage\":{\"inputTokens\":0,\"outputTokens\":0,"
                          "\"totalTokens\":0,\"costUsd\":0.0,"
                          "\"source\":\"codebase-memory-baseline\"}}\n"
                          "JSON\n"))]
    (.setExecutable (io/file worker-path) true)
    (sh! "git" "init" repo)
    (git! repo "config" "user.email" "bench@example.test")
    (git! repo "config" "user.name" "Benchmark Test")
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base (commit! repo "base")]
      (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :new)\n")
      (let [fix (commit! repo "fix")]
        (spit-file!
         suite-dir
         "benchmark.edn"
         (pr-str {:id "fixture"
                  :repos [{:id "repo" :root repo}]
                  :cases [{:id "case-1"
                           :repo-id "repo"
                           :base-sha base
                           :fix-sha fix
                           :issue {:id 1
                                   :title "broken app"
                                   :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-baselines!
                      suite
                      {:out out
                       :case-id "case-1"
                       :retriever "codebase-memory"
                       :codebase-memory-command worker-path
                       :codebase-memory-bin "fake-codebase-memory-mcp"})
              baseline (first (:baselines result))
              request (json/read-json
                       (slurp (get-in baseline [:artifacts :codebaseMemoryRequestPath]))
                       :key-fn keyword)
              scored (json/read-json
                      (slurp (get-in baseline [:artifacts :agentScorePath]))
                      :key-fn keyword)
              resumed (benchmark/agent-baselines!
                       suite
                       {:out out
                        :case-id "case-1"
                        :retriever "codebase-memory"
                        :codebase-memory-command "missing-codebase-memory-worker"
                        :codebase-memory-bin "fake-codebase-memory-mcp"
                        :skip-existing? true})
              skipped (first (:baselines resumed))]
          (is (= benchmark/agent-baselines-schema (:schema result)))
          (is (= "ygg-baseline-codebase-memory" (:agentId baseline)))
          (is (= "codebase-memory" (:mode baseline)))
          (is (= "codebase-memory" (:retriever baseline)))
          (is (= "fake-codebase-memory-mcp" (get-in baseline [:codebaseMemory :binary])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "ygg.benchmark.codebase-memory-request/v1" (:schema request)))
          (is (= (:caseFingerprint baseline) (:caseFingerprint request)))
          (is (= (:agentInputFingerprint baseline)
                 (:agentInputFingerprint request)))
          (is (= "fake-codebase-memory-mcp" (get-in request [:codebaseMemory :binary])))
          (is (str/ends-with? (get-in request [:codebaseMemory :cacheDir])
                              "codebase-memory-cache"))
          (is (not (contains? request :groundTruth)))
          (is (= benchmark/agent-result-schema (get-in scored [:agent :schema])))
          (is (= "codebase-memory" (get-in scored [:agent :mode])))
          (is (= "codebase-memory-result-surface-estimate"
                 (get-in scored [:agent :tokenUsage :source])))
          (is (pos? (get-in scored [:agent :tokenUsage :totalTokens])))
          (is (empty? (get-in scored [:agent :warnings])))
          (is (= 1.0 (get-in scored [:scores :evidenceCitationRate])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))

(deftest codebase-memory-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-codebase-memory-worker-repo")
        bin-dir (temp-dir "ygg-bench-codebase-memory-worker-bin")
        request-dir (temp-dir "ygg-bench-codebase-memory-worker-request")
        binary-path (spit-file!
                     bin-dir
                     "fake-codebase-memory-mcp"
                     (str "#!/usr/bin/env python3\n"
                          "import json, sys\n"
                          "tool = sys.argv[2] if len(sys.argv) > 2 else ''\n"
                          "payload = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}\n"
                          "if tool == 'index_repository':\n"
                          "    print(json.dumps({'indexed': True, 'path': 'src/app.clj'}))\n"
                          "elif tool == 'list_projects':\n"
                          "    print(json.dumps({'projects': [{'name': 'fixture-project', 'root_path': '"
                          repo
                          "'}]}))\n"
                          "elif tool == 'search_code':\n"
                          "    print(json.dumps({'results': [{'file': 'src/app.clj', 'score': 0.9}], 'project': payload.get('project')}))\n"
                          "elif tool == 'search_graph':\n"
                          "    print(json.dumps({'results': [{'file_path': 'docs/readme.md', 'score': 0.7}], 'project': payload.get('project')}))\n"
                          "elif tool == 'get_architecture':\n"
                          "    print(json.dumps({'hotspots': [{'relative_path': 'src/app.clj'}], 'file_tree': [{'path': 'ignored.txt'}]}))\n"
                          "else:\n"
                          "    print(json.dumps({'error': tool}))\n"
                          "    sys.exit(1)\n"))
        cache-dir (.getPath (io/file request-dir "cache"))
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.codebase-memory-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-codebase-memory"
                       :mode "codebase-memory"
                       :worktreeRoot repo
                       :input {:title "broken app"
                               :body "The app returns the old value."}
                       :limit 2
                       :codebaseMemory {:binary binary-path
                                        :cacheDir cache-dir}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (.setExecutable (io/file binary-path) true)
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! repo "docs/readme.md" "The app behavior is documented here.\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/codebase-memory-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "ygg-baseline-codebase-memory" (:agentId result)))
          (is (= "codebase-memory" (:mode result)))
          (is (= ["src/app.clj" "docs/readme.md"]
                 (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (= [{:supportCount 2
                   :firstSourceRank 1}
                  {:supportCount 1
                   :firstSourceRank 1}]
                 (mapv #(select-keys (:metrics %) [:supportCount
                                                   :firstSourceRank])
                       (:suspectedFiles result))))
          (is (= [2 1]
                 (mapv #(count (:evidence %)) (:suspectedFiles result))))
          (is (= "codebase-memory-result-surface-estimate"
                 (get-in result [:tokenUsage :source])))
          (is (pos? (get-in result [:tokenUsage :totalTokens])))
          (is (empty? (:warnings result)))
          (is (= 9 (count (:commands result)))))))))

(deftest codebase-memory-worker-emits-warned-empty-result-when-binary-is-missing
  (let [repo (temp-dir "ygg-bench-codebase-memory-missing-repo")
        request-dir (temp-dir "ygg-bench-codebase-memory-missing-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.codebase-memory-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-codebase-memory"
                       :mode "codebase-memory"
                       :worktreeRoot repo
                       :input {:title "broken app"}
                       :limit 2
                       :codebaseMemory {:binary (.getPath
                                                 (io/file request-dir "missing-cbm"))}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/codebase-memory-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "codebase-memory" (:mode result)))
          (is (empty? (:suspectedFiles result)))
          (is (seq (:warnings result)))
          (is (= "codebase-memory-result-surface-estimate"
                 (get-in result [:tokenUsage :source])))
          (is (pos? (get-in result [:tokenUsage :totalTokens]))))))))

(deftest graphify-baseline-shells-out-and-scores-agent-result
  (let [repo (temp-dir "ygg-bench-graphify-repo")
        out (temp-dir "ygg-bench-graphify-out")
        suite-dir (temp-dir "ygg-bench-graphify-suite")
        worker-dir (temp-dir "ygg-bench-graphify-worker")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))
        worker-path (spit-file!
                     worker-dir
                     "fake-graphify-worker.sh"
                     (str "#!/bin/sh\n"
                          "case_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseId\"])' \"$1\")\n"
                          "case_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"caseFingerprint\"])' \"$1\")\n"
                          "agent_input_fingerprint=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentInputFingerprint\"])' \"$1\")\n"
                          "agent_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"agentId\"])' \"$1\")\n"
                          "cat > \"$2\" <<JSON\n"
                          "{\"schema\":\"ygg.benchmark.agent-result/v2\","
                          "\"caseId\":\"$case_id\","
                          "\"caseFingerprint\":\"$case_fingerprint\","
                          "\"agentInputFingerprint\":\"$agent_input_fingerprint\","
                          "\"agentId\":\"$agent_id\","
                          "\"mode\":\"graphify\","
                          "\"suspectedFiles\":[{\"path\":\"src/app.clj\",\"rank\":1,"
                          "\"confidence\":0.9,\"reason\":\"fake graphify match\","
                          "\"evidence\":[\"fake-graphify path: src/app.clj\"]}],"
                          "\"suspectedSymbols\":[],\"commands\":[\"fake-graphify-worker\"],"
                          "\"warnings\":[],\"summary\":\"fake graphify result\"}\n"
                          "JSON\n"))]
    (.setExecutable (io/file worker-path) true)
    (sh! "git" "init" repo)
    (git! repo "config" "user.email" "bench@example.test")
    (git! repo "config" "user.name" "Benchmark Test")
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [base (commit! repo "base")]
      (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :new)\n")
      (let [fix (commit! repo "fix")]
        (spit-file!
         suite-dir
         "benchmark.edn"
         (pr-str {:id "fixture"
                  :repos [{:id "repo" :root repo}]
                  :cases [{:id "case-1"
                           :repo-id "repo"
                           :base-sha base
                           :fix-sha fix
                           :issue {:id 1
                                   :title "broken app"
                                   :body "The app returns the old value."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              result (benchmark/agent-baselines!
                      suite
                      {:out out
                       :case-id "case-1"
                       :retriever "graphify"
                       :graphify-command worker-path
                       :graphify-bin "fake-graphify"
                       :graphify-query-budget 123
                       :graphify-max-workers 2})
              baseline (first (:baselines result))
              request (json/read-json
                       (slurp (get-in baseline [:artifacts :graphifyRequestPath]))
                       :key-fn keyword)
              scored (json/read-json
                      (slurp (get-in baseline [:artifacts :agentScorePath]))
                      :key-fn keyword)
              resumed (benchmark/agent-baselines!
                       suite
                       {:out out
                        :case-id "case-1"
                        :retriever "graphify"
                        :graphify-command "missing-graphify-worker"
                        :graphify-bin "fake-graphify"
                        :skip-existing? true})
              skipped (first (:baselines resumed))]
          (is (= benchmark/agent-baselines-schema (:schema result)))
          (is (= "ygg-baseline-graphify" (:agentId baseline)))
          (is (= "graphify" (:mode baseline)))
          (is (= "graphify" (:retriever baseline)))
          (is (= "fake-graphify" (get-in baseline [:graphify :binary])))
          (is (= 123 (get-in baseline [:graphify :queryBudget])))
          (is (true? (get-in baseline [:graphify :codeOnly])))
          (is (= 1.0 (get-in baseline [:scores :fileRecallAt5])))
          (is (= 1.0 (get-in baseline [:scores :meanReciprocalRankFile])))
          (is (= "ygg.benchmark.graphify-request/v1" (:schema request)))
          (is (= (:caseFingerprint baseline) (:caseFingerprint request)))
          (is (= (:agentInputFingerprint baseline)
                 (:agentInputFingerprint request)))
          (is (= "fake-graphify" (get-in request [:graphify :command])))
          (is (= 123 (get-in request [:graphify :queryBudget])))
          (is (= 2 (get-in request [:graphify :maxWorkers])))
          (is (true? (get-in request [:graphify :codeOnly])))
          (is (str/ends-with? (get-in request [:graphify :outputDir])
                              "graphify"))
          (is (not (contains? request :groundTruth)))
          (is (= benchmark/agent-result-schema (get-in scored [:agent :schema])))
          (is (= "graphify" (get-in scored [:agent :mode])))
          (is (= "graphify-result-surface-estimate"
                 (get-in scored [:agent :tokenUsage :source])))
          (is (pos? (get-in scored [:agent :tokenUsage :totalTokens])))
          (is (empty? (get-in scored [:agent :warnings])))
          (is (= 1.0 (get-in scored [:scores :evidenceCitationRate])))
          (is (= 1.0 (get-in scored [:scores :pathEvidenceCitationRate])))
          (is (= ["src/app.clj"]
                 (mapv :path (get-in scored [:agent :topFiles]))))
          (is (= 1 (:skipped resumed)))
          (is (= "skipped" (:status skipped)))
          (is (= "current-score-artifact" (:skipReason skipped)))
          (is (= (:scores baseline) (:scores skipped))))))))

(deftest graphify-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-graphify-worker-repo")
        bin-dir (temp-dir "ygg-bench-graphify-worker-bin")
        request-dir (temp-dir "ygg-bench-graphify-worker-request")
        binary-path (spit-file!
                     bin-dir
                     "fake-graphify"
                     (str "#!/usr/bin/env python3\n"
                          "import json, pathlib, sys\n"
                          "cmd = sys.argv[1] if len(sys.argv) > 1 else ''\n"
                          "if cmd == 'extract':\n"
                          "    out = pathlib.Path(sys.argv[sys.argv.index('--out') + 1])\n"
                          "    graph_dir = out / 'graphify-out'\n"
                          "    graph_dir.mkdir(parents=True, exist_ok=True)\n"
                          "    src = pathlib.Path('"
                          repo
                          "') / 'src' / 'app.clj'\n"
                          "    graph = {'nodes': [{'id': 'broken_app', 'label': 'broken app', 'source_file': str(src), 'source_location': 'L1'}], 'edges': []}\n"
                          "    (graph_dir / 'graph.json').write_text(json.dumps(graph), encoding='utf-8')\n"
                          "elif cmd == 'query':\n"
                          "    src = pathlib.Path('"
                          repo
                          "') / 'src' / 'app.clj'\n"
                          "    print(f'NODE broken_app [src={src} loc=L1 community=]')\n"
                          "else:\n"
                          "    print('unknown command', cmd, file=sys.stderr)\n"
                          "    sys.exit(1)\n"))
        graphify-out (.getPath (io/file request-dir "graphify"))
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.graphify-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-graphify"
                       :mode "graphify"
                       :worktreeRoot repo
                       :input {:title "broken app"
                               :body "The app returns the old value."}
                       :limit 2
                       :graphify {:command binary-path
                                  :outputDir graphify-out
                                  :codeOnly true
                                  :queryBudget 100
                                  :maxWorkers 1}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (.setExecutable (io/file binary-path) true)
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! repo "docs/readme.md" "The app behavior is documented here.\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/graphify-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "ygg-baseline-graphify" (:agentId result)))
          (is (= "graphify" (:mode result)))
          (is (= ["src/app.clj"] (mapv :path (:suspectedFiles result))))
          (is (every? #(seq (:evidence %)) (:suspectedFiles result)))
          (is (some #(str/includes? % "code-only extraction")
                    (:warnings result)))
          (is (= "graphify-result-surface-estimate"
                 (get-in result [:tokenUsage :source])))
          (is (pos? (get-in result [:tokenUsage :totalTokens])))
          (is (= 2 (count (:commands result)))))))))

(deftest graphify-worker-emits-warned-empty-result-when-binary-is-missing
  (let [repo (temp-dir "ygg-bench-graphify-missing-repo")
        request-dir (temp-dir "ygg-bench-graphify-missing-request")
        request-path (spit-json!
                      request-dir
                      "request.json"
                      {:schema "ygg.benchmark.graphify-request/v1"
                       :caseId "case-1"
                       :caseFingerprint "sha256:case-1"
                       :agentInputFingerprint "sha256:case-input"
                       :agentId "ygg-baseline-graphify"
                       :mode "graphify"
                       :worktreeRoot repo
                       :input {:title "broken app"}
                       :limit 2
                       :graphify {:command (.getPath
                                            (io/file request-dir "missing-graphify"))
                                  :outputDir (.getPath
                                              (io/file request-dir "graphify"))}})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n")
    (let [{:keys [exit err]} (shell/sh "python3"
                                       "scripts/graphify-baseline.py"
                                       request-path
                                       result-path)]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "graphify" (:mode result)))
          (is (empty? (:suspectedFiles result)))
          (is (seq (:warnings result)))
          (is (= "graphify-result-surface-estimate"
                 (get-in result [:tokenUsage :source])))
          (is (pos? (get-in result [:tokenUsage :totalTokens]))))))))

(deftest deepseek-worker-emits-current-agent-result-contract
  (let [repo (temp-dir "ygg-bench-deepseek-worker-repo")
        request-dir (temp-dir "ygg-bench-deepseek-worker-request")
        prompt-path (spit-file! request-dir
                                "prompt.md"
                                "# Prompt\nFind the broken app file.\n")
        context-path (spit-json!
                      request-dir
                      "context.json"
                      {:candidateFiles [{:path "src/app.clj"}]
                       :coverage {:sourceKinds ["code"]}})
        mock-response-path (spit-json!
                            request-dir
                            "deepseek-response.json"
                            {:usage {:input_tokens 11
                                     :output_tokens 7}
                             :stop_reason "tool_use"
                             :content [{:type "tool_use"
                                        :id "toolu_1"
                                        :name "write_result"
                                        :input {:result
                                                {:suspectedFiles
                                                 [{:path "src/app.clj"
                                                   :rank 1
                                                   :confidence 0.9
                                                   :reason "Mock DeepSeek result."
                                                   :evidence ["src/app.clj contains the app entry."]}]
                                                 :summary "Mocked DeepSeek localization."}}}]})
        result-path (.getPath (io/file request-dir "result.json"))]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (let [{:keys [exit err]} (shell/sh "env"
                                       (str "YGG_BENCH_PROMPT=" prompt-path)
                                       (str "YGG_BENCH_RESULT=" result-path)
                                       (str "YGG_BENCH_WORKTREE=" repo)
                                       "YGG_BENCH_CASE_ID=case-1"
                                       "YGG_BENCH_CASE_FINGERPRINT=sha256:case-1"
                                       "YGG_BENCH_AGENT_INPUT_FINGERPRINT=sha256:case-input"
                                       "YGG_BENCH_AGENT_ID=deepseek-test"
                                       "YGG_BENCH_MODE=ygg"
                                       "YGG_BENCH_PARSER_WORKER=dotnet"
                                       (str "YGG_BENCH_YGG_CONTEXT=" context-path)
                                       (str "DEEPSEEK_MOCK_RESPONSE=" mock-response-path)
                                       "python3"
                                       "scripts/deepseek-agent.py")]
      (is (zero? exit) err)
      (when (zero? exit)
        (let [result (json/read-json (slurp result-path) :key-fn keyword)]
          (is (= benchmark/agent-result-schema (:schema result)))
          (is (= "case-1" (:caseId result)))
          (is (= "sha256:case-1" (:caseFingerprint result)))
          (is (= "sha256:case-input" (:agentInputFingerprint result)))
          (is (= "deepseek-test" (:agentId result)))
          (is (= "ygg" (:mode result)))
          (is (= {:rawCandidateFiles 1
                  :candidateFiles 1
                  :coverageFilteredCandidateFiles 1
                  :limit nil
                  :coverageSourceKinds ["code"]}
                 (:selection result)))
          (is (= {:mode "dotnet"
                  :source "env"}
                 (:parserWorker result)))
          (is (= ["src/app.clj"] (mapv :path (:suspectedFiles result))))
          (is (= {:inputTokens 11
                  :outputTokens 7
                  :totalTokens 18
                  :costUsd 0.0
                  :source "deepseek-agent"}
                 (:tokenUsage result))))))))
