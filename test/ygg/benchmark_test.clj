(ns ygg.benchmark-test
  (:require [ygg.benchmark :as benchmark]
            [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-classes :as benchmark-classes]
            [ygg.benchmark-maintenance :as benchmark-maintenance]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.benchmark-test-support :refer [commit!
                                                git!
                                                spit-file!
                                                spit-json!
                                                temp-dir]]
            [ygg.map-store :as map-store]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest selects-one-or-more-benchmark-cases
  (let [suite {:id "suite"
               :cases [{:id "case-1"}
                       {:id "case-2"}
                       {:id "case-3"}]}]
    (is (= ["case-1" "case-2" "case-3"]
           (mapv :id (benchmark/selected-cases suite nil))))
    (is (= ["case-2"]
           (mapv :id (benchmark/selected-cases suite "case-2"))))
    (is (= ["case-1" "case-3"]
           (mapv :id (benchmark/selected-cases suite ["case-3" "case-1"]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Benchmark case not found"
                          (benchmark/selected-cases suite ["case-1" "missing"])))))

(deftest rerun-agent-lane-selects-affected-cases-from-agent-report
  (let [root (temp-dir "ygg-bench-agent-rerun")
        report-path (spit-json! root
                                "agent-report.json"
                                {:improvementSummary
                                 [{:kind "stale-agent-input-fingerprints"
                                   :caseIds ["case-b" "case-a"]}
                                  {:kind "obsolete-agent-result-contract"
                                   :caseIds ["case-a"]}
                                  {:kind "missing-context-ranks"
                                   :caseIds ["case-c"]}]})
        suite {:id "suite"
               :cases [{:id "case-a"}
                       {:id "case-b"}
                       {:id "case-c"}]}
        calls (atom [])]
    (with-redefs [benchmark/agent-runs! (fn [suite opts]
                                          (swap! calls conj [suite opts])
                                          {:schema benchmark/agent-runs-schema
                                           :suite-id (:id suite)
                                           :completed 2
                                           :failed 0
                                           :skipped 0
                                           :runs []})]
      (let [result (benchmark/rerun-agent-lane!
                    suite
                    {:agent-id "codex"
                     :command "codex exec --json"
                     :agent-report-path report-path
                     :skip-existing? true})]
        (is (= ["case-a" "case-b"]
               (get-in result [:rerunLane :caseIds])))
        (is (= ["obsolete-agent-result-contract"
                "stale-agent-input-fingerprints"]
               (get-in result [:rerunLane :sourceKinds])))
        (is (= "improvement-summary"
               (get-in result [:rerunLane :selection])))
        (is (= [[suite {:agent-id "codex"
                        :command "codex exec --json"
                        :case-ids ["case-a" "case-b"]}]]
               @calls))))))

(deftest rerun-agent-lane-honors-explicit-case-selection-without-reading-report
  (let [suite {:id "suite"
               :cases [{:id "case-a"}
                       {:id "case-b"}]}
        calls (atom [])]
    (with-redefs [benchmark/agent-runs! (fn [suite opts]
                                          (swap! calls conj [suite opts])
                                          {:schema benchmark/agent-runs-schema
                                           :suite-id (:id suite)
                                           :completed 1
                                           :failed 0
                                           :skipped 0
                                           :runs []})]
      (let [result (benchmark/rerun-agent-lane!
                    suite
                    {:agent-id "codex"
                     :command "codex exec --json"
                     :agent-report-path "/missing/agent-report.json"
                     :case-id "case-b"})]
        (is (= ["case-b"] (get-in result [:rerunLane :caseIds])))
        (is (= "explicit" (get-in result [:rerunLane :selection])))
        (is (= [[suite {:agent-id "codex"
                        :command "codex exec --json"
                        :case-ids ["case-b"]}]]
               @calls))))))

(deftest read-suite-rejects-ambiguous-or-invalid-ids
  (let [suite-dir (temp-dir "ygg-bench-suite-shape")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (spit suite-path
          (pr-str {:id "shape-fixture"
                   :repos [{:id "repo"
                            :root "."}
                           {:id "repo"
                            :root "."}]
                   :cases [{:id "case-1"
                            :repo-id "repo"
                            :issue {:title "first"}}
                           {:id "case-1"
                            :repo-id "repo"
                            :issue {:title "duplicate"}}
                           {:id "case-2"
                            :repo-id "missing"
                            :issue {:title "unknown repo"}}]}))
    (try
      (benchmark/read-suite suite-path)
      (is false "Expected duplicate and unknown ids to fail suite reading.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Benchmark suite has ambiguous or invalid ids."
               (ex-message e)))
        (is (= {:suite-id "shape-fixture"
                :errors [{:kind :duplicate-repo-ids
                          :ids ["repo"]}
                         {:kind :duplicate-case-ids
                          :ids ["case-1"]}
                         {:kind :unknown-case-repos
                          :cases [{:case-id "case-2"
                                   :repo-id "missing"}]}]}
               (ex-data e)))))))

(deftest read-suite-accepts-multi-repo-case-shape
  (let [suite-dir (temp-dir "ygg-bench-suite-multi-repo")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (spit suite-path
          (pr-str {:id "multi-repo-shape"
                   :repos [{:id "provider"
                            :root "provider"}
                           {:id "consumer"
                            :root "consumer"}]
                   :cases [{:id "contract-case"
                            :repos [{:repo-id "provider"
                                     :base-sha "provider-base"
                                     :fix-sha "provider-fix"}
                                    {:repo-id "consumer"
                                     :base-sha "consumer-base"
                                     :fix-sha "consumer-fix"}]
                            :issue {:title "Trace contract"}}]}))
    (let [suite (benchmark/read-suite suite-path)
          case (first (:cases suite))]
      (is (= "provider" (:repo-id case)))
      (is (= ["provider" "consumer"]
             (mapv :repo-id (:repos case))))
      (is (= ["provider" "consumer"]
             (benchmark-suite/case-repo-ids case))))))

(deftest read-suite-composes-included-suites
  (let [suite-dir (temp-dir "ygg-bench-suite-includes")
        child-a (.getPath (io/file suite-dir "child-a.edn"))
        child-b (.getPath (io/file suite-dir "child-b.edn"))
        parent (.getPath (io/file suite-dir "parent.edn"))]
    (spit child-a
          (pr-str {:id "child-a"
                   :repos [{:id "repo-a"
                            :root "repo-a"}]
                   :cases [{:id "case-a"
                            :repo-id "repo-a"
                            :tags [:problem-architecture]
                            :issue {:title "a"}}]}))
    (spit child-b
          (pr-str {:id "child-b"
                   :repos [{:id "repo-a"
                            :root "repo-a"}
                           {:id "repo-b"
                            :root "repo-b"
                            :role :library}]
                   :cases [{:id "case-b"
                            :repo-id "repo-b"
                            :tags [:problem-audit]
                            :issue {:title "b"}}]}))
    (spit parent
          (pr-str {:id "parent"
                   :include-suites ["child-a.edn"
                                    "child-b.edn"]
                   :repos [{:id "repo-c"
                            :root "repo-c"}]
                   :cases [{:id "case-c"
                            :repo-id "repo-c"
                            :tags [:problem-maintenance]
                            :issue {:title "c"}}]}))
    (let [suite (benchmark/read-suite parent)]
      (is (= "parent" (:id suite)))
      (is (= ["child-a" "child-b"]
             (mapv :id (:included-suites suite))))
      (is (= ["repo-a" "repo-b" "repo-c"]
             (mapv :id (:repos suite))))
      (is (= [:application :library :application]
             (mapv :role (:repos suite))))
      (is (= ["case-a" "case-b" "case-c"]
             (mapv :id (:cases suite))))
      (is (= ["problem-architecture" "problem-audit" "problem-maintenance"]
             (mapcat :tags (:cases suite)))))))

(deftest read-suite-rejects-include-cycles
  (let [suite-dir (temp-dir "ygg-bench-suite-cycle")
        a (.getPath (io/file suite-dir "a.edn"))
        b (.getPath (io/file suite-dir "b.edn"))]
    (spit a (pr-str {:id "a"
                     :include-suites ["b.edn"]}))
    (spit b (pr-str {:id "b"
                     :include-suites ["a.edn"]}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Benchmark suite includes form a cycle"
                          (benchmark/read-suite a)))))

(deftest architecture-starter-suite-covers-required-problem-classes
  (let [suite (benchmark/read-suite "benchmarks/architecture-synthetic.edn")
        cases (:cases suite)
        tags (set (mapcat :tags cases))
        source-kinds (set (mapcat #(get-in % [:coverage :source-kinds]) cases))
        evidence-kinds (set (mapcat #(map :kind (get-in % [:expectations :evidence])) cases))
        node-kinds (set (mapcat #(map :kind (get-in % [:expectations :nodes])) cases))
        cases-by-id (into {} (map (juxt :id identity)) cases)
        citation-graph-case-ids ["bootstrap-synthetic-docs-route-impact"
                                 "bootstrap-synthetic-astro-plugin-config"]]
    (is (= "architecture-synthetic" (:id suite)))
    (is (<= 4 (count cases)))
    (is (every? #(contains? (set (:tags %)) "synthetic") cases))
    (is (every? #(contains? (set (:tags %)) "problem-architecture") cases))
    (is (every? #(seq (get-in % [:coverage :source-kinds])) cases))
    (is (every? #(seq (get-in % [:ground-truth :localization-files])) cases))
    (is (every? #(seq (get-in % [:expectations :evidence])) cases))
    (is (every? #(or (seq (get-in % [:expectations :evidence]))
                     (seq (get-in % [:expectations :nodes]))
                     (seq (get-in % [:expectations :chunks]))
                     (seq (get-in % [:expectations :edges])))
                cases))
    (is (every? #(seq (:root %)) (:repos suite)))
    (is (every? source-kinds
                [:web-framework :manifest :env :sql :javascript :dotnet :compose]))
    (is (every? evidence-kinds
                [:web-framework-route
                 :web-framework-import
                 :web-framework-plugin
                 :manifest-package
                 :env-var
                 :sql-security
                 :container-image-consumer]))
    (is (every? node-kinds
                [:web-framework-route :web-framework-import :web-framework-plugin :external-package]))
    (is (every? #(contains? (set (keys cases-by-id)) %)
                ["bootstrap-synthetic-docs-route-impact"
                 "bootstrap-synthetic-astro-plugin-config"
                 "supabase-postgres-synthetic-trigger-ownership-flow"
                 "axios-synthetic-native-proxy-boundary"]))
    (doseq [case-id citation-graph-case-ids
            :let [expectations (get-in cases-by-id [case-id :expectations])]]
      (is (contains? expectations :graph-evidence))
      (is (empty? (:graph-evidence expectations)))
      (is (or (seq (:nodes expectations))
              (seq (:chunks expectations))
              (seq (:edges expectations)))))
    (is (every? tags
                ["architecture-cross-system-impact"
                 "architecture-dependency-flow"
                 "architecture-data-ownership"
                 "architecture-runtime-boundary"
                 "audit-scope-dependencies"]))))

(defn- benchmark-suite-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".edn")
       (let [suite (edn/read-string (slurp file))]
         (or (seq (:cases suite))
             (seq (:include-suites suite))))))

(deftest tracked-benchmark-suites-use-common-local-artifact-space
  (let [suite-files (->> (file-seq (io/file "benchmarks"))
                         (filter benchmark-suite-file?)
                         (sort-by #(.getPath %))
                         vec)
        suites (mapv #(benchmark/read-suite (.getPath %)) suite-files)]
    (is (seq suite-files))
    (is (some #(= "architecture-synthetic" (:id %)) suites))
    (is (some #(= "agent-efficiency-broad" (:id %)) suites))
    (is (every? #(not (str/includes? (.getName %) "oss-")) suite-files))
    (doseq [suite-file suite-files
            repo (:repos (edn/read-string (slurp suite-file)))]
      (is (str/includes? (:root repo) "/.dev/ygg/benchmark-repos/")))))

(deftest headline-suite-covers-architecture-first-agent-questions
  (let [suite (benchmark/read-suite "benchmarks/headline.edn")
        cases (:cases suite)
        tags (set (mapcat :tags cases))
        tag-counts (frequencies (mapcat :tags cases))
        measured-architecture-tags (->> tag-counts
                                        (filter (fn [[tag count]]
                                                  (and (benchmark-classes/architecture-class-tag?
                                                        tag)
                                                       (<= 2 count))))
                                        (mapv first)
                                        sort)
        source-kinds (set (mapcat #(get-in % [:coverage :source-kinds]) cases))
        repo-ids (set (map :repo-id cases))
        evidence-kinds (set (mapcat #(map :kind (get-in % [:expectations :evidence])) cases))
        node-kinds (set (mapcat #(map :kind (get-in % [:expectations :nodes])) cases))
        dapper-case (first (filter #(= "headline-dapper-jsonb-test-stack" (:id %))
                                   cases))]
    (is (= "headline-architecture" (:id suite)))
    (is (<= 5 (count cases) 10))
    (is (every? #(contains? (set (:tags %)) "headline") cases))
    (is (every? #(contains? (set (:tags %)) "problem-architecture") cases))
    (is (every? #(seq (get-in % [:coverage :source-kinds])) cases))
    (is (every? #(seq (get-in % [:ground-truth :localization-files])) cases))
    (is (every? #(seq (get-in % [:expectations :evidence])) cases))
    (is (every? #(or (seq (get-in % [:expectations :evidence]))
                     (seq (get-in % [:expectations :nodes]))
                     (seq (get-in % [:expectations :chunks]))
                     (seq (get-in % [:expectations :edges])))
                cases))
    (is (every? repo-ids ["bootstrap" "supabase-postgres" "axios" "dapper"]))
    (is (every? source-kinds
                [:web-framework :manifest :env :sql :javascript :dotnet :compose]))
    (is (every? evidence-kinds
                [:web-framework-route
                 :web-framework-import
                 :web-framework-plugin
                 :manifest-package
                 :env-var
                 :sql-security
                 :container-image-consumer]))
    (is (every? node-kinds
                [:web-framework-route :web-framework-import :web-framework-plugin :external-package]))
    (is (= [:dotnet :compose]
           (get-in dapper-case [:coverage :source-kinds])))
    (is (some #(and (= :external-package (:kind %))
                    (= "tests/Dapper.Tests/Dapper.Tests.csproj" (:path %)))
              (get-in dapper-case [:expectations :nodes])))
    (is (every? tags
                ["architecture-cross-system-impact"
                 "architecture-dependency-flow"
                 "architecture-data-ownership"
                 "architecture-runtime-boundary"
                 "audit-scope-dependencies"
                 "docs-contracts"
                 "runtime-config"
                 "shell-sufficient-control"]))
    (is (= 1 (get tag-counts "shell-sufficient-control")))
    (is (= ["architecture-dependency-flow"
            "architecture-runtime-boundary"
            "audit-scope-dependencies"
            "audit-scope-runtime-config"]
           measured-architecture-tags))))

(deftest architecture-coverage-suite-expands-measured-classes
  (let [suite (benchmark/read-suite "benchmarks/architecture-coverage.edn")
        cases (:cases suite)
        tags (set (mapcat :tags cases))
        tag-counts (frequencies (mapcat :tags cases))
        measured-architecture-tags (->> tag-counts
                                        (filter (fn [[tag count]]
                                                  (and (benchmark-classes/architecture-class-tag?
                                                        tag)
                                                       (<= 2 count))))
                                        (mapv first)
                                        sort)
        source-kinds (set (mapcat #(get-in % [:coverage :source-kinds]) cases))
        repo-ids (set (map :repo-id cases))
        node-kinds (set (mapcat #(map :kind (get-in % [:expectations :nodes])) cases))]
    (is (= "architecture-coverage" (:id suite)))
    (is (= 4 (count cases)))
    (is (every? #(contains? (set (:tags %)) "synthetic") cases))
    (is (every? #(contains? (set (:tags %)) "problem-architecture") cases))
    (is (every? #(seq (get-in % [:coverage :source-kinds])) cases))
    (is (every? #(seq (get-in % [:ground-truth :localization-files])) cases))
    (is (every? #(or (seq (get-in % [:expectations :evidence]))
                     (seq (get-in % [:expectations :nodes]))
                     (seq (get-in % [:expectations :chunks]))
                     (seq (get-in % [:expectations :edges])))
                cases))
    (is (every? repo-ids ["opentelemetry-collector" "terraform-aws-vpc"
                          "flask" "junit-framework"]))
    (is (every? source-kinds [:go :terraform :python :java]))
    (is (every? node-kinds [:interface :terraform-resource :terraform-variable :class]))
    (is (every? tags
                ["architecture-cross-system-impact"
                 "architecture-data-ownership"]))
    (is (= ["architecture-cross-system-impact"
            "architecture-data-ownership"
            "audit-scope-dependencies"
            "audit-scope-runtime-config"]
           measured-architecture-tags))
    (is (<= 2 (get tag-counts "architecture-cross-system-impact")))
    (is (<= 2 (get tag-counts "architecture-data-ownership")))))

(deftest multi-repo-quality-suite-contains-cross-repo-case
  (let [suite (benchmark/read-suite "benchmarks/multi-repo-quality.edn")
        case (first (:cases suite))
        repo-ids (set (map :id (:repos suite)))
        case-repo-ids (mapv :repo-id (:repos case))
        ground-truth-files (mapcat #(get-in % [:ground-truth :localization-files])
                                   (:repos case))]
    (is (= "multi-repo-quality" (:id suite)))
    (is (= #{"opentelemetry-collector"
             "opentelemetry-collector-contrib"}
           repo-ids))
    (is (= ["opentelemetry-collector"
            "opentelemetry-collector-contrib"]
           case-repo-ids))
    (is (contains? (set (:tags case)) "multi-repo-quality"))
    (is (every? #(seq (get-in % [:ground-truth :localization-files]))
                (:repos case)))
    (is (some #{"connector/connector.go"} ground-truth-files))
    (is (some #{"connector/routingconnector/factory.go"} ground-truth-files))
    (is (every? #(contains? % :repo-id)
                (concat (get-in case [:expectations :nodes])
                        (get-in case [:expectations :evidence]))))))

(deftest broad-agent-efficiency-suite-composes-task-token-coverage
  (let [suite (benchmark/read-suite "benchmarks/agent-efficiency-broad.edn")
        cases (:cases suite)
        tags (set (mapcat :tags cases))
        tag-counts (frequencies (mapcat :tags cases))
        source-kinds (set (mapcat #(get-in % [:coverage :source-kinds]) cases))
        repo-ids (set (map :repo-id cases))
        measured-architecture-tags (->> tag-counts
                                        (filter (fn [[tag count]]
                                                  (and (benchmark-classes/architecture-class-tag?
                                                        tag)
                                                       (<= 2 count))))
                                        (mapv first)
                                        sort)]
    (is (= "agent-efficiency-broad" (:id suite)))
    (is (= ["headline-architecture"
            "architecture-coverage"
            "decision-quality-pilot"
            "feature-planning"]
           (mapv :id (:included-suites suite))))
    (is (= 18 (count cases)))
    (is (= 8 (count (:repos suite))))
    (is (every? repo-ids
                ["bootstrap"
                 "supabase-postgres"
                 "axios"
                 "dapper"
                 "opentelemetry-collector"
                 "terraform-aws-vpc"
                 "flask"
                 "junit-framework"]))
    (is (every? source-kinds
                [:web-framework :manifest :env :sql :javascript :dotnet
                 :compose :go :terraform :python :java]))
    (is (every? tags
                ["problem-architecture"
                 "problem-audit"
                 "problem-maintenance"
                 "decision-quality"
                 "runtime-config"
                 "architecture-cross-system-impact"
                 "architecture-data-ownership"
                 "architecture-dependency-flow"
                 "architecture-runtime-boundary"
                 "audit-scope-dependencies"
                 "audit-scope-runtime-config"]))
    (is (= 1 (get tag-counts "problem-audit")))
    (is (= 1 (get tag-counts "problem-maintenance")))
    (is (= ["architecture-cross-system-impact"
            "architecture-data-ownership"
            "architecture-dependency-flow"
            "architecture-runtime-boundary"
            "audit-scope-containers"
            "audit-scope-dependencies"
            "audit-scope-docs"
            "audit-scope-runtime-config"]
           measured-architecture-tags))))

(deftest scores-file-localization
  (let [result {:groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                              :unsupportedGroundTruthFiles []}
                :ygg {:topFiles [{:path "src/other.clj" :rank 1}
                                 {:path "src/app.clj" :rank 2}
                                 {:path "src/more.clj" :rank 3}
                                 {:path "src/db.clj" :rank 4}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 0.5 (:meanReciprocalRankFile scores)))
    (is (= 0.5 (:noiseRatioAt20 scores)))
    (is (= 0 (:unsupportedGroundTruthFiles scores)))))

(deftest scores-multi-repo-file-localization-by-repo-and-path
  (let [result {:groundTruth {:changedFiles [{:repo-id "provider"
                                              :path "src/contract.clj"}
                                             {:repo-id "consumer"
                                              :path "src/contract.clj"}]
                              :unsupportedGroundTruthFiles []}
                :ygg {:topFiles [{:repo-id "provider"
                                  :path "src/contract.clj"
                                  :rank 1}
                                 {:repo-id "other"
                                  :path "src/contract.clj"
                                  :rank 2}
                                 {:repo-id "consumer"
                                  :path "src/contract.clj"
                                  :rank 3}]}}
        scores (benchmark/score-result result)
        ranks (benchmark-score/ground-truth-file-ranks
               (benchmark-score/scoreable-changed-files (:groundTruth result))
               (get-in result [:ygg :topFiles]))]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 1.0 (:meanReciprocalRankFile scores)))
    (is (= (/ 1.0 3.0) (:noiseRatioAt20 scores)))
    (is (= [{:path "src/contract.clj"
             :repo-id "provider"
             :rank 1
             :found? true}
            {:path "src/contract.clj"
             :repo-id "consumer"
             :rank 3
             :found? true}]
           (mapv #(select-keys % [:repo-id :path :rank :found?]) ranks)))))

(deftest scores-agent-evidence-citation-rate
  (let [result {:groundTruth {:changedFiles ["src/app.clj"]
                              :unsupportedGroundTruthFiles []}
                :ygg {:topFiles [{:path "src/app.clj"
                                  :rank 1
                                  :evidence ["context-doc:src/app.clj"]}
                                 {:path "src/other.clj"
                                  :rank 2
                                  :evidence ["near miss src/other.cljs"]}
                                 {:path "src/more.clj"
                                  :rank 3
                                  :evidence []}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= (/ 2.0 3.0) (:evidenceCitationRate scores)))
    (is (= (/ 1.0 3.0) (:pathEvidenceCitationRate scores)))))

(deftest scores-expected-evidence-citation-rate
  (let [result {:groundTruth {:changedFiles ["src/app.clj"]
                              :unsupportedGroundTruthFiles []}
                :expectations {:evidence [{:kind :env-var
                                           :path "config/runtime.env"
                                           :label "DATABASE_URL"}
                                          {:kind :env-var
                                           :path "config/secrets.env"
                                           :label "SECRET_KEY"}
                                          {:kind :env-var
                                           :label "CACHE_URL"}]}
                :ygg {:topFiles [{:path "src/app.clj"
                                  :rank 1
                                  :evidence ["context-doc:config/runtime.env DATABASE_URL"
                                             "rg CACHE_URL"]}]}}
        scores (benchmark/score-result result)]
    (is (= (/ 2.0 3.0) (:expectedEvidenceCitationRate scores)))
    (is (= 2 (:expectedEvidenceCitations scores)))
    (is (= 3 (:expectedEvidenceCitationTargets scores)))))

(deftest scores-only-base-visible-ground-truth-files
  (let [result {:groundTruth {:changedFiles [".chloggen/fix.yaml"
                                             "src/app.clj"
                                             "src/app_test.clj"]
                              :unsupportedGroundTruthFiles [{:path ".chloggen/fix.yaml"
                                                             :reason "missing-at-base"}]}
                :ygg {:topFiles [{:path "src/app.clj" :rank 1}
                                 {:path "src/app_test.clj" :rank 2}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 1.0 (:meanReciprocalRankFile scores)))
    (is (= 0.0 (:noiseRatioAt20 scores)))
    (is (= 3 (:changedFiles scores)))
    (is (= 2 (:scoreableChangedFiles scores)))
    (is (= 1 (:unsupportedGroundTruthFiles scores)))))

(deftest scores-explicit-scoreable-ground-truth-files
  (let [result {:groundTruth {:changedFiles ["CHANGELOG.md"
                                             "src/app.js"
                                             "test/app.test.js"]
                              :localizationFiles ["CHANGELOG.md"
                                                  "src/app.js"
                                                  "test/app.test.js"]
                              :scoreableFiles ["src/app.js"
                                               "test/app.test.js"]
                              :coverageExcludedFiles [{:path "CHANGELOG.md"
                                                       :kind "doc"}]
                              :unsupportedGroundTruthFiles []}
                :ygg {:topFiles [{:path "src/app.js" :rank 1}
                                 {:path "test/app.test.js" :rank 2}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 2 (:scoreableChangedFiles scores)))
    (is (= 1 (:coverageExcludedGroundTruthFiles scores)))))

(deftest scores-explicit-localization-files-when-present
  (let [result {:groundTruth {:changedFiles ["docs/release-notes.md"
                                             "src/app.clj"
                                             "test/app_test.clj"]
                              :localizationFiles ["src/app.clj"
                                                  "test/app_test.clj"]
                              :unsupportedGroundTruthFiles []}
                :ygg {:topFiles [{:path "docs/release-notes.md" :rank 1}
                                 {:path "src/app.clj" :rank 2}
                                 {:path "test/app_test.clj" :rank 3}]}}
        scores (benchmark/score-result result)]
    (is (= 1.0 (:fileRecallAt5 scores)))
    (is (= 0.5 (:meanReciprocalRankFile scores)))
    (is (= 3 (:changedFiles scores)))
    (is (= 2 (:localizationFiles scores)))
    (is (= 2 (:scoreableChangedFiles scores)))))

(deftest agent-input-fingerprint-excludes-hidden-scoring-contract
  (let [suite {:id "suite"}
        base-case {:id "case-1"
                   :repo-id "repo"
                   :base-sha "base"
                   :fix-sha "fix"
                   :coverage {:source-kinds [:clojure]}
                   :ground-truth {:localization-files ["src/app.clj"]}
                   :expectations {:nodes [{:kind :service
                                           :path "src/app.clj"}]}
                   :issue {:title "Broken app"
                           :body "Find the route."}}
        changed-expectations (assoc base-case
                                    :expectations
                                    {:nodes [{:kind :service
                                              :path "src/other.clj"}]})]
    (is (not= (benchmark-prepare/case-fingerprint suite base-case)
              (benchmark-prepare/case-fingerprint suite changed-expectations)))
    (is (= (benchmark-prepare/agent-input-fingerprint suite base-case)
           (benchmark-prepare/agent-input-fingerprint suite changed-expectations)))))

(deftest matches-graph-expectation-rows-with-explicit-fields
  (let [evidence [{:kind :auth-reference
                   :path "config/app.yml"
                   :auth-context :bearer}]
        chunks [{:kind :code-definition
                 :path "scripts/nvm.sh"
                 :definition-kind :function
                 :label "nvm_remote_version"}]
        edges [{:relation :calls-external-api
                :source-id "src/app.clj"
                :target-id "external-api:auth.example.test"}]
        expected (#'benchmark/expected-row-results
                  evidence
                  [{:kind "auth-reference"
                    :path "config/app.yml"
                    :authContext "bearer"}]
                  identity)
        unsupported (#'benchmark/expected-row-results
                     evidence
                     [{:semantic-label "auth config"}]
                     identity)
        expected-chunk (#'benchmark/expected-row-results
                        chunks
                        [{:kind "code-definition"
                          :path "scripts/nvm.sh"
                          :definitionKind "function"
                          :label "nvm_remote_version"}]
                        identity)
        forbidden (#'benchmark/forbidden-row-results
                   edges
                   [:calls-external-api]
                   identity)]
    (is (= true (get-in expected [0 :found?])))
    (is (= {:auth-context "bearer"
            :kind "auth-reference"
            :path "config/app.yml"}
           (get-in expected [0 :expectation])))
    (is (= false (get-in unsupported [0 :found?])))
    (is (= true (get-in expected-chunk [0 :found?])))
    (is (= {:definition-kind "function"
            :kind "code-definition"
            :label "nvm_remote_version"
            :path "scripts/nvm.sh"}
           (get-in expected-chunk [0 :expectation])))
    (is (= true (get-in forbidden [0 :violated?])))))

(deftest evaluates-source-chunk-graph-expectations
  (let [prepared {:project-id "fixture"
                  :expectations {:nodes [{:kind :web-framework-plugin
                                          :path "site/astro.config.ts"
                                          :label "astro/config"}]
                                 :chunks [{:kind :code-definition
                                           :path "scripts/nvm.sh"
                                           :definitionKind :function
                                           :label "nvm_remote_version"}]
                                 :forbidden-nodes [{:kind :web-framework-plugin
                                                    :path "site/astro.config.ts"
                                                    :label "missing-plugin"}]
                                 :forbidden-chunks [{:kind :code-definition
                                                     :path "scripts/nvm.sh"
                                                     :label "legacy_function"}]}}
        node {:xt/id "node:astro-plugin"
              :kind :web-framework-plugin
              :path "site/astro.config.ts"
              :label "astro/config"
              :source-line 1
              :project-id "fixture"}
        chunk {:xt/id "chunk:shell-function"
               :kind :code-definition
               :path "scripts/nvm.sh"
               :definition-kind :function
               :label "nvm_remote_version"
               :source-line 12
               :end-line 18
               :project-id "fixture"}]
    (with-redefs [store/constrained-rows
                  (fn [_ table _constraints]
                    (cond
                      (= table (:chunks store/tables)) [chunk]
                      (= table (:nodes store/tables)) [node]
                      (= table (:system-evidence store/tables)) []
                      (= table (:system-edges store/tables)) []
                      :else []))]
      (let [result (#'benchmark/evaluate-graph-expectations nil prepared)]
        (is (= benchmark/graph-expectations-schema (:schema result)))
        (is (= "passed" (:status result)))
        (is (= {:expectedEvidence 0
                :foundEvidence 0
                :missingEvidence 0
                :expectedNodes 1
                :foundNodes 1
                :missingNodes 0
                :expectedChunks 1
                :foundChunks 1
                :missingChunks 0
                :expectedEdges 0
                :foundEdges 0
                :missingEdges 0
                :forbiddenNodes 1
                :forbiddenNodeViolations 0
                :forbiddenChunks 1
                :forbiddenChunkViolations 0
                :forbiddenEdges 0
                :forbiddenEdgeViolations 0}
               (:summary result)))
        (is (= true (get-in result [:expectedNodes 0 :found?])))
        (is (= true (get-in result [:expectedChunks 0 :found?])))
        (is (= false (get-in result [:forbiddenNodes 0 :violated?])))
        (is (= false (get-in result [:forbiddenChunks 0 :violated?])))
        (is (= [{:xt/id "node:astro-plugin"
                 :kind :web-framework-plugin
                 :path "site/astro.config.ts"
                 :label "astro/config"
                 :source-line 1}]
               (get-in result [:expectedNodes 0 :matches])))
        (is (= [{:xt/id "chunk:shell-function"
                 :kind :code-definition
                 :path "scripts/nvm.sh"
                 :definition-kind :function
                 :label "nvm_remote_version"
                 :source-line 12
                 :end-line 18}]
               (get-in result [:expectedChunks 0 :matches])))))))

(deftest separates-citation-evidence-from-graph-evidence
  (let [prepared {:project-id "fixture"
                  :expectations {:evidence [{:kind :web-framework-route
                                             :path "site/src/pages/index.astro"
                                             :label "citation target"}]
                                 :graph-evidence []
                                 :nodes [{:kind :web-framework-route
                                          :path "site/src/pages/index.astro"}]}}
        node {:xt/id "node:index-route"
              :kind :web-framework-route
              :path "site/src/pages/index.astro"
              :label "/"
              :project-id "fixture"}]
    (with-redefs [store/constrained-rows
                  (fn [_ table _constraints]
                    (cond
                      (= table (:nodes store/tables)) [node]
                      (= table (:system-evidence store/tables)) []
                      (= table (:chunks store/tables)) []
                      (= table (:system-edges store/tables)) []
                      :else []))]
      (let [result (#'benchmark/evaluate-graph-expectations nil prepared)]
        (is (= "passed" (:status result)))
        (is (= 0 (get-in result [:summary :expectedEvidence])))
        (is (= 1 (get-in result [:summary :expectedNodes])))
        (is (= true (get-in result [:expectedNodes 0 :found?])))))))

(deftest prepares-issue-replay-case-from-git-diff
  (let [root (temp-dir "ygg-bench-repo")
        out (temp-dir "ygg-bench-out")
        suite-dir (temp-dir "ygg-bench-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (git! root "init")
    (git! root "config" "user.email" "ygg@example.test")
    (git! root "config" "user.name" "Yggdrasil Test")
    (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
    (spit-file! root "docs/release.md" "old app\n")
    (let [base-sha (commit! root "base")]
      (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :fixed)\n")
      (spit-file! root "docs/release.md" "fixed app\n")
      (spit-file! root "src/new.clj" "(ns new)\n")
      (let [fix-sha (commit! root "fix")]
        (spit suite-path
              (pr-str {:id "fixture"
                       :repos [{:id "repo"
                                :root root}]
                       :cases [{:id "case-1"
                                :repo-id "repo"
                                :tags [:runtime-config :auth]
                                :expectations {:evidence [{:kind :auth-reference
                                                           :path "src/app.clj"}]
                                               :forbidden-edges [:shares-config]}
                                :coverage {:source-kinds [:code :python]}
                                :base-sha base-sha
                                :fix-sha fix-sha
                                :ground-truth {:localization-files ["docs/release.md"
                                                                    "src/app.clj"]}
                                :issue {:id 1
                                        :title "broken app"
                                        :body "The app returns the old value in src/app.clj."}}]}))
        (let [suite (benchmark/read-suite suite-path)
              prepared (first (:cases (benchmark/prepare-suite! suite {:out out})))
              progress-path (io/file out
                                     "fixture"
                                     "cases"
                                     "case-1"
                                     "progress.json")
              progress (json/read-json (slurp progress-path) :key-fn keyword)]
          (is (= benchmark/prepared-case-schema (:schema prepared)))
          (is (str/starts-with? (:caseFingerprint prepared) "sha256:"))
          (is (str/starts-with? (:agentInputFingerprint prepared) "sha256:"))
          (is (not= (:caseFingerprint prepared)
                    (:agentInputFingerprint prepared)))
          (is (= ["auth" "runtime-config"] (:tags prepared)))
          (is (= {:evidence [{:kind :auth-reference
                              :path "src/app.clj"}]
                  :forbidden-edges [:shares-config]}
                 (:expectations prepared)))
          (is (= #{"docs/release.md" "src/app.clj" "src/new.clj"}
                 (set (get-in prepared [:groundTruth :changedFiles]))))
          (is (= ["docs/release.md" "src/app.clj"]
                 (get-in prepared [:groundTruth :localizationFiles])))
          (is (= ["src/app.clj"]
                 (get-in prepared [:groundTruth :scoreableFiles])))
          (is (= [{:path "docs/release.md"
                   :kind "doc"}]
                 (get-in prepared [:groundTruth :coverageExcludedFiles])))
          (is (= [{:path "src/new.clj"
                   :reason "missing-at-base"}]
                 (get-in prepared [:groundTruth :unsupportedGroundTruthFiles])))
          (is (= {:hinted true
                  :mentionedChangedFiles ["src/app.clj"]
                  :mentionedChangedFileCount 1
                  :changedFileCount 3}
                 (:inputHints prepared)))
          (is (= {:declaredSourceKinds ["code" "python"]
                  :scoreableSourceKinds ["code" "doc"]
                  :scoreableFilesByKind [{:kind "code"
                                          :files 1}
                                         {:kind "doc"
                                          :files 1}]
                  :missingDeclaredSourceKinds ["python"]}
                 (:coverage prepared)))
          (is (.isDirectory (io/file (:worktreeRoot prepared))))
          (is (= "ygg.benchmark.case-progress/v1" (:schema progress)))
          (is (= #{"prepare-worktree"
                   "prepare-ground-truth"
                   "prepare-graph-index"
                   "write-prepared-case"}
                 (set (map :stage (:events progress)))))
          (is (every? pos?
                      (keep :elapsedMs
                            (filter #(= "completed" (:status %))
                                    (:events progress))))))))))

(deftest prepares-multi-repo-case-with-repo-qualified-ground-truth
  (let [provider-root (temp-dir "ygg-bench-provider-repo")
        consumer-root (temp-dir "ygg-bench-consumer-repo")
        out (temp-dir "ygg-bench-multi-out")
        suite-dir (temp-dir "ygg-bench-multi-suite")
        suite-path (.getPath (io/file suite-dir "benchmark.edn"))]
    (doseq [root [provider-root consumer-root]]
      (git! root "init")
      (git! root "config" "user.email" "ygg@example.test")
      (git! root "config" "user.name" "Yggdrasil Test"))
    (spit-file! provider-root "src/contract.clj" "(ns provider.contract)\n(defn encode [] :old)\n")
    (spit-file! provider-root "src/noise.clj" "(ns provider.noise)\n(defn unrelated [] :noise)\n")
    (let [provider-base (commit! provider-root "provider base")]
      (spit-file! provider-root "src/contract.clj" "(ns provider.contract)\n(defn encode [] :new)\n")
      (let [provider-fix (commit! provider-root "provider fix")]
        (spit-file! consumer-root "src/contract.clj" "(ns consumer.contract)\n(defn decode [] :old)\n")
        (spit-file! consumer-root "src/noise.clj" "(ns consumer.noise)\n(defn unrelated [] :noise)\n")
        (let [consumer-base (commit! consumer-root "consumer base")]
          (spit-file! consumer-root "src/contract.clj" "(ns consumer.contract)\n(defn decode [] :new)\n")
          (let [consumer-fix (commit! consumer-root "consumer fix")]
            (spit suite-path
                  (pr-str {:id "multi-repo-quality"
                           :repos [{:id "provider"
                                    :root provider-root
                                    :role :library}
                                   {:id "consumer"
                                    :root consumer-root
                                    :role :application}]
                           :cases [{:id "provider-consumer-contract"
                                    :repos [{:repo-id "provider"
                                             :base-sha provider-base
                                             :fix-sha provider-fix
                                             :index-files ["src/contract.clj"]
                                             :ground-truth {:localization-files ["src/contract.clj"]}}
                                            {:repo-id "consumer"
                                             :base-sha consumer-base
                                             :fix-sha consumer-fix
                                             :index-files ["src/contract.clj"]
                                             :ground-truth {:localization-files ["src/contract.clj"]}}]
                                    :coverage {:source-kinds [:code]}
                                    :tags [:synthetic
                                           :problem-architecture
                                           :multi-repo-quality]
                                    :issue {:id "provider-consumer-contract"
                                            :title "Trace provider and consumer contract edits"
                                            :body "The provider contract and consumer adapter both need the contract update. Inspect provider:src/contract.clj and consumer:src/contract.clj."}}]}))
            (let [suite (benchmark/read-suite suite-path)
                  prepared (first (:cases (benchmark/prepare-suite! suite {:out out})))
                  project (benchmark-agent-packet/agent-project prepared)]
              (is (= "provider" (:repo-id prepared)))
              (is (= ["provider" "consumer"] (:repoIds prepared)))
              (is (= #{"provider" "consumer"} (set (keys (:worktreeRoots prepared)))))
              (is (= #{"provider" "consumer"} (set (keys (:graphRoots prepared)))))
              (is (every? #(.isDirectory (io/file %))
                          (vals (:worktreeRoots prepared))))
              (is (every? #(.isDirectory (io/file %))
                          (vals (:graphRoots prepared))))
              (is (every? (fn [[repo-id graph-root]]
                            (not= graph-root (get-in prepared [:worktreeRoots repo-id])))
                          (:graphRoots prepared)))
              (is (every? (fn [[_ graph-root]]
                            (.isFile (io/file graph-root "src/contract.clj")))
                          (:graphRoots prepared)))
              (is (every? (fn [[_ graph-root]]
                            (not (.exists (io/file graph-root "src/noise.clj"))))
                          (:graphRoots prepared)))
              (is (= {:bounded? true
                      :repos [{:repo-id "provider"
                               :root (get-in prepared [:graphRoots "provider"])
                               :files ["src/contract.clj"]}
                              {:repo-id "consumer"
                               :root (get-in prepared [:graphRoots "consumer"])
                               :files ["src/contract.clj"]}]}
                     (:graphIndex prepared)))
              (is (= [{:repo-id "provider"
                       :path "src/contract.clj"}
                      {:repo-id "consumer"
                       :path "src/contract.clj"}]
                     (get-in prepared [:groundTruth :changedFiles])))
              (is (= (get-in prepared [:groundTruth :changedFiles])
                     (get-in prepared [:groundTruth :localizationFiles])))
              (is (= (get-in prepared [:groundTruth :changedFiles])
                     (get-in prepared [:groundTruth :scoreableFiles])))
              (is (= []
                     (get-in prepared [:groundTruth :unsupportedGroundTruthFiles])))
              (is (= {:hinted true
                      :mentionedChangedFiles [{:repo-id "provider"
                                               :path "src/contract.clj"}
                                              {:repo-id "consumer"
                                               :path "src/contract.clj"}]
                      :mentionedChangedFileCount 2
                      :changedFileCount 2}
                     (:inputHints prepared)))
              (is (= [{:id "provider"
                       :root (get-in prepared [:graphRoots "provider"])
                       :role :library}
                      {:id "consumer"
                       :root (get-in prepared [:graphRoots "consumer"])
                       :role :application}]
                     (:repos project))))))))))

(deftest progress-stage-records-shutdown-interruption
  (let [out (temp-dir "ygg-bench-progress-shutdown")
        expr (pr-str
              `(do
                 (require 'ygg.benchmark-progress)
                 ((var ygg.benchmark-progress/progress-stage!)
                  {:id "fixture"}
                  {:id "case-1" :repo-id "repo"}
                  {:out ~out}
                  :index-project
                  (fn []
                    (System/exit 42)))))
        process (shell/sh "clojure" "-M" "-e" expr)
        progress-path (io/file out
                               "fixture"
                               "cases"
                               "case-1"
                               "progress.json")
        progress (json/read-json (slurp progress-path) :key-fn keyword)
        events (:events progress)
        last-event (last events)]
    (is (= 42 (:exit process)))
    (is (= ["started" "failed"] (mapv :status events)))
    (is (= "index-project" (:stage last-event)))
    (is (= true (:interrupted last-event)))
    (is (= "Benchmark JVM shut down before stage completed."
           (get-in last-event [:error :message])))
    (is (pos? (:elapsedMs last-event)))))

(deftest progress-stage-records-bounded-ex-data
  (let [out (temp-dir "ygg-bench-progress-ex-data")
        progress-path (io/file out
                               "fixture"
                               "cases"
                               "case-1"
                               "progress.json")]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Index deadline exceeded"
      ((var benchmark-progress/progress-stage!)
       {:id "fixture"}
       {:id "case-1" :repo-id "repo"}
       {:out out}
       :index-project
       (fn []
         (throw (ex-info "Index deadline exceeded."
                         {:phase :extract
                          :project-id "project"
                          :repo-id "repo"
                          :files-changed 12
                          :path "src/app.clj"
                          :ignored-object (Object.)}))))))
    (let [progress (json/read-json (slurp progress-path) :key-fn keyword)
          failed (last (:events progress))]
      (is (= "failed" (:status failed)))
      (is (= "index-project" (:stage failed)))
      (is (= {:phase "extract"
              :project-id "project"
              :repo-id "repo"
              :files-changed 12
              :path "src/app.clj"}
             (get-in failed [:error :data]))))))

(deftest context-packet-can-be-written-as-agent-hints
  (let [root (temp-dir "ygg-bench-context-hints")
        _ (spit-file! root "src/app.clj" "(ns app)\n(defn broken [] :old)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "project"
                  :worktreeRoot root}
        packet {:query "broken app"
                :drilldowns ["ygg ask 'broken app' --project project"]
                :warnings []
                :evidence {:status :ok
                           :nextActions [{:kind :dependencies
                                          :command "ygg packages --project project --json"}]}
                :sourceCoverage {:schema "ygg.source-coverage.context/v1"
                                 :basis "indexed-graph"
                                 :totals {:indexedFiles 2
                                          :diagnostics 0
                                          :fileKinds 1}
                                 :topFileKinds [{:kind "code"
                                                 :count 2}]
                                 :extractors [{:kind "code"
                                               :extractorVersion "clojure/v9"
                                               :files 2}]
                                 :diagnostics {:byStage []
                                               :byExtractor []}}
                :docs [{:source {:repo "repo"
                                 :path "src/app.clj"
                                 :kind "code-definition"
                                 :heading "app/broken"
                                 :definitionKind :function
                                 :lines [2 4]}
                        :score 2.4
                        :snippet "(defn broken [] :old)"
                        :provenance "retrieved-doc"}
                       {:source {:repo "repo"
                                 :path "src/missing.clj"
                                 :heading "missing"}
                        :score 1.0
                        :snippet "missing"
                        :provenance "retrieved-doc"}]
                :entities [{:id "system:repo:path/src"
                            :label "src"
                            :path "src"
                            :repo "repo"
                            :score 0.7
                            :why "retrieval and graph match"
                            :candidateTypes ["runtime-url-host"]
                            :candidateEvidence [{:type "runtime-url-host"
                                                 :host "api.example.test"}]
                            :metrics {:file-count 2}}]
                :architecture {:basis "mechanical-plus-map"
                               :acceptedSystems [{:id "system:repo:path/src"
                                                  :label "src"
                                                  :status "accepted"}]
                               :rejectedCorrections [{:kind "map-reject"
                                                      :id "map-reject:1"
                                                      :status "rejected"
                                                      :provenance "map-overlay"
                                                      :match {:path "old/src"}
                                                      :reason "old boundary was rejected"}]
                               :boundaryEvidence [{:id "edge:src-db"
                                                   :source "system:repo:path/src"
                                                   :target "system:repo:path/db"
                                                   :relation "uses"}]
                               :runtimeEvidence [{:id "evidence:database-url"
                                                  :path "config/runtime.env"
                                                  :kind "env-var"
                                                  :fileKind "env"
                                                  :label "DATABASE_URL"
                                                  :score 1.2}]
                               :deployEvidence [{:id "evidence:deploy-image"
                                                 :path "compose.yml"
                                                 :kind "container-image-consumer"
                                                 :fileKind "compose"
                                                 :label "app image"}]
                               :openDecisions [{:id "activity:review"
                                                :kind "maintenance-decision"
                                                :status "ready"
                                                :sourceId "work:review"
                                                :summary "review boundary"}]
                               :evidenceFamilies [{:family "source-structure"
                                                   :status "available"
                                                   :rowCount 2}
                                                  {:family "runtime-config"
                                                   :status "available"
                                                   :rowCount 1}]
                               :summary {:counts {:acceptedSystems 1
                                                  :candidateSystems 0
                                                  :deployEvidence 1
                                                  :openDecisions 1}
                                         :nextActionKinds {:inspect 1}}
                               :validationGaps [{:plane "dependencies"
                                                 :status "missing"
                                                 :nextActions [{:kind :dependencies
                                                                :label "Inspect package graph facts"
                                                                :command "ygg packages --project project --json"}
                                                               {:kind :dependency-review
                                                                :label "Queue unresolved import review"
                                                                :command "ygg sync project.edn --check --enqueue"}]}]
                               :nextActions [{:kind :inspect
                                              :target "system:repo:path/src"
                                              :command "ygg sync explain system:repo:path/src --map ygg.map.json"}]}
                :auditScopes [{:kind "source-structure"
                               :basis "selected-architecture-evidence"
                               :facts 1
                               :topEvidenceTypes [{:kind "uses"
                                                   :count 1}]
                               :samples [{:id "edge:src-db"
                                          :relation "uses"
                                          :section "boundaryEvidence"}]}
                              {:kind "runtime-config"
                               :basis "selected-architecture-evidence"
                               :facts 1
                               :files 1
                               :topEvidenceTypes [{:kind "env-var"
                                                   :count 1}]
                               :samples [{:id "evidence:database-url"
                                          :path "config/runtime.env"
                                          :kind "env-var"
                                          :section "runtimeEvidence"}]}]}
        hints (benchmark/context-packet->agent-hints prepared packet {:limit 1})]
    (is (= benchmark/agent-hints-schema (:schema hints)))
    (is (= "case-1" (:case-id hints)))
    (is (= "broken app" (:query hints)))
    (is (= [{:path "src/app.clj"
             :rank 1
             :confidence 1.0
             :metrics {:firstSourceRank 1
                       :supportCount 1
                       :docCount 1
                       :entityCount 0
                       :candidateFileCount 0
                       :retrievedSourceCount 0
                       :exactPathSourceCount 0
                       :maxConfidence 1.0
                       :rankScore 2.92
                       :matchedTokenCount 2
                       :definitionKinds ["function"]}
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]
             :reason "Yggdrasil context doc \"app/broken\" from src/app.clj lines 2-4 with provenance retrieved-doc."}]
           (:topFiles hints)))
    (is (= [{:name "app/broken"
             :path "src/app.clj"
             :rank 1
             :kind "function"
             :confidence 1.0
             :reason "Yggdrasil context doc \"app/broken\" references src/app.clj lines 2-4."
             :evidence ["context-doc:src/app.clj lines 2-4 provenance=retrieved-doc"]}
            {:name "missing"
             :path "src/missing.clj"
             :rank 2
             :kind "unknown"
             :confidence 1.0
             :reason "Yggdrasil context doc \"missing\" references src/missing.clj."
             :evidence ["context-doc:src/missing.clj provenance=retrieved-doc"]}]
           (:topSymbols hints)))
    (is (= [{:rank 1
             :path "src/app.clj"
             :heading "app/broken"
             :kind "code-definition"
             :definitionKind :function
             :score 2.4
             :provenance "retrieved-doc"
             :snippet "(defn broken [] :old)"
             :repo "repo"
             :lines {:start 2
                     :end 4}}
            {:rank 2
             :path "src/missing.clj"
             :heading "missing"
             :kind nil
             :definitionKind nil
             :score 1.0
             :provenance "retrieved-doc"
             :snippet "missing"
             :repo "repo"}]
           (:topDocs hints)))
    (is (= [{:rank 1
             :id "system:repo:path/src"
             :repo "repo"
             :path "src"
             :label "src"
             :score 0.7
             :why "retrieval and graph match"
             :candidateTypes ["runtime-url-host"]
             :candidateEvidence [{:type "runtime-url-host"
                                  :host "api.example.test"}]
             :metrics {:file-count 2}}]
           (:candidateSystems hints)))
    (is (= ["ygg ask 'broken app' --project project"
            "ygg packages --project project --json"
            "ygg sync explain system:repo:path/src --map ygg.map.json"
            "ygg sync project.edn --check --enqueue"]
           (:commands hints)))
    (is (= {:rawCandidateFiles 1
            :candidateFiles 1
            :coverageFilteredCandidateFiles 0
            :limit 1
            :coverageSourceKinds []}
           (:selection hints)))
    (is (= {:indexedFiles 2
            :diagnostics 0
            :fileKinds 1}
           (get-in hints [:sourceCoverage :totals])))
    (is (= {:basis "mechanical-plus-map"
            :acceptedSystems [{:id "system:repo:path/src"
                               :label "src"
                               :status "accepted"}]
            :candidateSystems []
            :rejectedCorrections [{:kind "map-reject"
                                   :id "map-reject:1"
                                   :status "rejected"
                                   :provenance "map-overlay"
                                   :match {:path "old/src"}
                                   :reason "old boundary was rejected"}]
            :boundaryEvidence [{:id "edge:src-db"
                                :source "system:repo:path/src"
                                :target "system:repo:path/db"
                                :relation "uses"}]
            :runtimeEvidence [{:id "evidence:database-url"
                               :path "config/runtime.env"
                               :kind "env-var"
                               :fileKind "env"
                               :label "DATABASE_URL"
                               :score 1.2}]
            :deployEvidence [{:id "evidence:deploy-image"
                              :path "compose.yml"
                              :kind "container-image-consumer"
                              :fileKind "compose"
                              :label "app image"}]
            :dependencyEvidence []
            :docs []
            :openDecisions [{:id "activity:review"
                             :kind "maintenance-decision"
                             :status "ready"
                             :sourceId "work:review"
                             :summary "review boundary"}]
            :evidenceFamilies [{:family "source-structure"
                                :status "available"
                                :rowCount 2}
                               {:family "runtime-config"
                                :status "available"
                                :rowCount 1}]
            :summary {:counts {:acceptedSystems 1
                               :candidateSystems 0
                               :deployEvidence 1
                               :openDecisions 1}
                      :nextActionKinds {:inspect 1}}
            :validationGaps [{:plane "dependencies"
                              :status "missing"
                              :nextActions [{:kind :dependencies
                                             :label "Inspect package graph facts"
                                             :command "ygg packages --project project --json"}
                                            {:kind :dependency-review
                                             :label "Queue unresolved import review"
                                             :command "ygg sync project.edn --check --enqueue"}]}]
            :warnings []
            :nextActions [{:kind :inspect
                           :target "system:repo:path/src"
                           :command "ygg sync explain system:repo:path/src --map ygg.map.json"}]}
           (:architecture hints)))
    (is (= [{:kind "source-structure"
             :basis "selected-architecture-evidence"
             :facts 1
             :topEvidenceTypes [{:kind "uses"
                                 :count 1}]
             :samples [{:id "edge:src-db"
                        :relation "uses"
                        :section "boundaryEvidence"}]}
            {:kind "runtime-config"
             :basis "selected-architecture-evidence"
             :facts 1
             :files 1
             :topEvidenceTypes [{:kind "env-var"
                                 :count 1}]
             :samples [{:id "evidence:database-url"
                        :path "config/runtime.env"
                        :kind "env-var"
                        :section "runtimeEvidence"}]}]
           (:auditScopes hints)))
    (is (not (contains? hints :groundTruth)))
    (is (not (contains? hints :inputHints)))))

(deftest scores-agent-localization-result
  (let [root (temp-dir "ygg-bench-agent-score")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :agentInputFingerprint "sha256:test-input"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :input {:title "broken app"}
                  :inputHints {:hinted true
                               :mentionedChangedFiles ["src/app.clj"]
                               :mentionedChangedFileCount 1
                               :changedFileCount 2}
                  :expectations {:evidence [{:kind :env-var
                                             :path "config/runtime.env"
                                             :label "DATABASE_URL"}
                                            {:kind :env-var
                                             :path "config/secrets.env"
                                             :label "SECRET_KEY"}]}
                  :groundTruth {:changedFiles ["src/app.clj" "src/db.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/other.clj"
                                        :rank 1
                                        :confidence 0.9}
                                       {:path "src/app.clj"
                                        :rank 2
                                        :confidence 0.8
                                        :evidence ["context-doc:config/runtime.env DATABASE_URL"]
                                        :metrics {:supportCount 2
                                                  :retrievedSourceCount 1}}
                                       {:path "src/db.clj"
                                        :rank 3
                                        :confidence 0.7}]}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= benchmark/agent-score-schema (:schema scored)))
    (is (= "sha256:test-case" (:caseFingerprint scored)))
    (is (= "sha256:test-input" (:agentInputFingerprint scored)))
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= 0.5 (get-in scored [:scores :meanReciprocalRankFile])))
    (is (= 0.5 (get-in scored [:scores :expectedEvidenceCitationRate])))
    (is (= (:inputHints prepared) (:inputHints scored)))
    (is (= ["src/other.clj"] (get-in scored [:agent :missingPredictedFiles])))
    (is (= {:supportCount 2
            :retrievedSourceCount 1}
           (get-in scored [:agent :topFiles 1 :metrics])))
    (is (= [{:path "src/app.clj"
             :rank 2
             :found? true}
            {:path "src/db.clj"
             :rank 3
             :found? true}]
           (get-in scored [:groundTruthRanks :files])))))

(deftest scores-agent-decision-quality-result
  (let [root (temp-dir "ygg-bench-agent-decision-score")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "test/app_test.clj" "(ns app-test)\n")
        _ (spit-file! root "README.md" "# docs\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :agentInputFingerprint "sha256:test-input"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}
                  :decisionCandidates [{:id "edit-runtime"
                                        :kind "architecture-choice"
                                        :paths ["src/app.clj"]}
                                       {:id "add-regression-test"
                                        :kind "architecture-choice"
                                        :paths ["test/app_test.clj"]}
                                       {:id "docs-only"
                                        :kind "architecture-choice"
                                        :paths ["README.md"]}]
                  :decisionGroundTruth {:kind "architecture-choice"
                                        :required ["edit-runtime"
                                                   "add-regression-test"]
                                        :forbidden ["docs-only"]}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentInputFingerprint "sha256:test-input"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.9
                                        :reason "runtime edit"
                                        :evidence ["src/app.clj defines the runtime path"]}]
                      :suspectedSymbols []
                      :commands ["rg runtime src/app.clj"]
                      :warnings []
                      :summary "Decision candidates scored."
                      :decision {:kind "architecture-choice"
                                 :choices [{:id "edit-runtime"
                                            :status "include"
                                            :confidence 0.9
                                            :reason "runtime owner"
                                            :evidence ["src/app.clj owns the runtime path"]}
                                           {:id "add-regression-test"
                                            :status "defer"
                                            :confidence 0.6
                                            :reason "needs confirmation"
                                            :evidence ["test/app_test.clj is the likely test path"]}
                                           {:id "docs-only"
                                            :status "include"
                                            :confidence 0.4
                                            :reason "wrongly included"
                                            :evidence ["manual note without exact path"]}]}}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= 0.5 (get-in scored [:scores :decisionRecall])))
    (is (= 0.5 (get-in scored [:scores :decisionPrecision])))
    (is (= 0.5 (get-in scored [:scores :decisionF1])))
    (is (= 0.5 (get-in scored [:scores :decisionEvidenceCitationRate])))
    (is (= {:configured true
            :kind "architecture-choice"
            :candidateIds ["add-regression-test" "docs-only" "edit-runtime"]
            :requiredChoiceIds ["add-regression-test" "edit-runtime"]
            :forbiddenChoiceIds ["docs-only"]
            :includedChoiceIds ["docs-only" "edit-runtime"]
            :excludedChoiceIds []
            :deferredChoiceIds ["add-regression-test"]
            :unknownChoiceIds []
            :matchedRequiredChoiceIds ["edit-runtime"]
            :missedChoiceIds ["add-regression-test"]
            :wrongIncludedChoiceIds ["docs-only"]
            :deferredRequiredChoiceIds ["add-regression-test"]
            :uncitedChoiceIds ["docs-only"]
            :missingDecision false}
           (:decisionScoring scored)))))

(deftest scores-decision-benchmark-missing-agent-decision
  (let [root (temp-dir "ygg-bench-missing-agent-decision")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}
                  :decisionCandidates [{:id "edit-runtime"
                                        :kind "maintenance-action"
                                        :paths ["src/app.clj"]}]
                  :decisionGroundTruth {:kind "maintenance-action"
                                        :required ["edit-runtime"]}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "runtime edit"
                                        :evidence ["src/app.clj cited"]}]
                      :suspectedSymbols []
                      :commands []
                      :warnings []
                      :summary "No decision block."}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (true? (get-in scored [:decisionScoring :missingDecision])))
    (is (= 0.0 (get-in scored [:scores :decisionF1])))
    (is (some #{"agent result missing decision for decision benchmark case"}
              (get-in scored [:agent :warnings])))))

(deftest score-agent-result-warns-on-malformed-agent-output
  (let [root (temp-dir "ygg-bench-agent-score-shape")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8}
                                       "README.md"]
                      :suspectedSymbols [{:name "broken"
                                          :path "src/app.clj"
                                          :rank 1}]}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= ["agent result missing required field caseId"
            "agent result missing required field caseFingerprint"
            "agent result missing required field agentId"
            "agent result missing required field mode"
            "agent result missing required field commands"
            "agent result missing required field warnings"
            "agent result missing required field summary"
            "agent result suspectedFiles row 1 path src/app.clj missing reason"
            "agent result suspectedFiles row 1 path src/app.clj missing evidence"
            "agent result suspectedFiles row 2 is not an object"
            "agent result suspectedSymbols row 1 path src/app.clj missing kind"
            "agent result suspectedSymbols row 1 path src/app.clj missing confidence"
            "agent result suspectedSymbols row 1 path src/app.clj missing reason"
            "agent result suspectedSymbols row 1 path src/app.clj missing evidence"
            "agent result referenced files missing from the base checkout"]
           (get-in scored [:agent :warnings])))))

(deftest score-agent-result-warns-on-non-contract-fields
  (let [root (temp-dir "ygg-bench-agent-score-contract")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentId "codex"
                      :mode "ygg"
                      :selection {:candidateFiles 1
                                  :extraSelection "ignored"}
                      :parserWorker {:mode "local"
                                     :extraParser "ignored"}
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "candidate"
                                        :evidence ["manual"]
                                        :metrics {:supportCount 1
                                                  :weirdMetric 2}
                                        :extraFile "ignored"}]
                      :suspectedSymbols [{:name "load"
                                          :path "src/app.clj"
                                          :kind "function"
                                          :rank 1
                                          :confidence 0.7
                                          :reason "candidate"
                                          :evidence ["manual"]
                                          :extraSymbol "ignored"}]
                      :commands []
                      :warnings []
                      :summary "Found candidates."
                      :extraRoot "ignored"}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= ["agent result unknown field extraRoot"
            "agent result selection unknown field extraSelection"
            "agent result parserWorker unknown field extraParser"
            "agent result suspectedFiles row 1 path src/app.clj unknown field extraFile"
            "agent result suspectedFiles row 1 path src/app.clj metrics unknown field weirdMetric"
            "agent result suspectedSymbols row 1 path src/app.clj unknown field extraSymbol"]
           (get-in scored [:agent :warnings])))))

(deftest score-agent-result-warns-on-invalid-rankable-values
  (let [root (temp-dir "ygg-bench-agent-score-values")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank "later"
                                        :confidence 1.2
                                        :reason "bad rank and confidence"
                                        :evidence ["manual"]}]
                      :suspectedSymbols [{:name "broken"
                                          :path "src/app.clj"
                                          :kind "function"
                                          :rank 0
                                          :confidence "sure"
                                          :reason "bad rank and confidence"
                                          :evidence ["manual"]}]
                      :commands []
                      :warnings []
                      :summary "Found the changed file."}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= ["agent result suspectedFiles row 1 path src/app.clj rank must be a positive integer"
            "agent result suspectedFiles row 1 path src/app.clj confidence must be between 0 and 1"
            "agent result suspectedSymbols row 1 path src/app.clj rank must be a positive integer"
            "agent result suspectedSymbols row 1 path src/app.clj confidence must be between 0 and 1"]
           (get-in scored [:agent :warnings])))))

(deftest score-agent-result-warns-on-duplicate-ranks
  (let [root (temp-dir "ygg-bench-agent-score-duplicate-ranks")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        _ (spit-file! root "src/db.clj" "(ns db)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "first"
                                        :evidence ["manual"]}
                                       {:path "src/db.clj"
                                        :rank 1
                                        :confidence 0.7
                                        :reason "second"
                                        :evidence ["manual"]}]
                      :suspectedSymbols [{:name "load"
                                          :path "src/app.clj"
                                          :kind "function"
                                          :rank 2
                                          :confidence 0.8
                                          :reason "first"
                                          :evidence ["manual"]}
                                         {:name "save"
                                          :path "src/db.clj"
                                          :kind "function"
                                          :rank 2
                                          :confidence 0.7
                                          :reason "second"
                                          :evidence ["manual"]}]
                      :commands []
                      :warnings []
                      :summary "Found candidates."}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= ["agent result suspectedFiles rank 1 is duplicated by row 1 path src/app.clj and row 2 path src/db.clj"
            "agent result suspectedSymbols rank 2 is duplicated by row 1 path src/app.clj and row 2 path src/db.clj"]
           (get-in scored [:agent :warnings])))))

(deftest score-agent-result-warns-on-duplicate-file-paths
  (let [root (temp-dir "ygg-bench-agent-score-duplicate-paths")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 2
                                        :confidence 0.8
                                        :reason "first"
                                        :evidence ["manual"]}
                                       {:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.7
                                        :reason "second"
                                        :evidence ["manual"]}]
                      :suspectedSymbols []
                      :commands []
                      :warnings []
                      :summary "Found candidates."}
        scored (benchmark/score-agent-result prepared agent-result)]
    (is (= ["agent result suspectedFiles path src/app.clj is duplicated by row 1 path src/app.clj and row 2 path src/app.clj"]
           (get-in scored [:agent :warnings])))
    (is (= [{:path "src/app.clj"
             :rank 1
             :confidence 0.7
             :reason "second"
             :evidence ["manual"]
             :metrics nil}]
           (get-in scored [:agent :topFiles])))))

(deftest score-agent-result-warns-on-mismatched-agent-identity
  (let [root (temp-dir "ygg-bench-agent-score-identity")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema "ygg.benchmark.agent-result/old"
                      :caseId "case-2"
                      :caseFingerprint "sha256:old-case"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "Yggdrasil context identified the file."
                                        :evidence ["context-doc:src/app.clj"]}]
                      :suspectedSymbols []
                      :commands []
                      :warnings []
                      :summary "Found the changed file."}
        scored (benchmark/score-agent-result prepared agent-result)
        diagnostic (#'benchmark/agent-output-diagnostic scored)]
    (is (= 1.0 (get-in scored [:scores :fileRecallAt5])))
    (is (= [(str "agent result schema ygg.benchmark.agent-result/old does not match expected schema "
                 benchmark/agent-result-schema)
            "agent result caseId case-2 does not match expected case case-1"
            "agent result caseFingerprint sha256:old-case does not match expected case fingerprint sha256:test-case"]
           (get-in scored [:agent :warnings])))
    (is (= {:identityWarnings (get-in scored [:agent :warnings])
            :hasIdentityMismatch true}
           (select-keys diagnostic [:identityWarnings :hasIdentityMismatch])))))

(deftest score-agent-result-prefers-agent-input-fingerprint-for-identity
  (let [root (temp-dir "ygg-bench-agent-score-input-identity")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:new-score-contract"
                  :agentInputFingerprint "sha256:visible-input"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:old-score-contract"
                      :agentInputFingerprint "sha256:visible-input"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "Yggdrasil context identified the file."
                                        :evidence ["context-doc:src/app.clj"]}]
                      :suspectedSymbols []
                      :commands []
                      :warnings []
                      :summary "Found the changed file."}
        scored (benchmark/score-agent-result prepared agent-result)
        diagnostic (#'benchmark/agent-output-diagnostic scored)]
    (is (= [] (get-in scored [:agent :warnings])))
    (is (= {:identityWarnings []
            :hasIdentityMismatch false}
           (select-keys diagnostic [:identityWarnings :hasIdentityMismatch])))))

(deftest score-agent-result-writes-parser-worker-provenance
  (let [root (temp-dir "ygg-bench-agent-score-worker")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "ygg"
                      :parserWorker {:mode "dotnet"
                                     :source "agent-result"}
                      :suspectedFiles [{:path "src/app.clj"}]}
        suite {:id "suite"}
        case {:id "case-1"}
        result-path (.getPath (io/file root "agent-result.json"))
        score-path (.getPath (io/file root
                                      "suite"
                                      "cases"
                                      "case-1"
                                      "agent-scores"
                                      "agent-result.score.json"))]
    (spit-json! root "agent-result.json" agent-result)
    (with-redefs [benchmark/prepare-case! (fn [_suite _case _opts] prepared)]
      (let [scored (benchmark/score-agent-result! suite
                                                  case
                                                  {:out root
                                                   :result-path result-path
                                                   :parser-worker "all"})]
        (is (= {:mode "all"
                :source "option"}
               (:parserWorker scored)))
        (is (= (:parserWorker scored)
               (:parserWorker (json/read-json (slurp score-path) :key-fn keyword))))))
    (with-redefs [benchmark/prepare-case! (fn [_suite _case _opts] prepared)]
      (let [scored (benchmark/score-agent-result! suite
                                                  case
                                                  {:out root
                                                   :result-path result-path})]
        (is (= {:mode "dotnet"
                :source "agent-result"}
               (:parserWorker scored)))))))

(deftest score-agent-result-refreshes-ygg-maintenance-artifacts
  (let [root (temp-dir "ygg-bench-agent-score-ygg-artifacts")
        _ (spit-file! root "src/app.clj" "(ns app)\n")
        suite {:id "suite"}
        case {:id "case-1"}
        result-path (.getPath (io/file root
                                       "suite"
                                       "cases"
                                       "case-1"
                                       "agent-results"
                                       "codex.json"))
        score-path (.getPath (io/file root
                                      "suite"
                                      "cases"
                                      "case-1"
                                      "agent-scores"
                                      "codex.score.json"))
        prepared {:suite-id "suite"
                  :case-id "case-1"
                  :repo-id "repo"
                  :project-id "suite-case-1"
                  :caseFingerprint "sha256:test-case"
                  :agentInputFingerprint "sha256:test-input"
                  :baseSha "base"
                  :fixSha "fix"
                  :worktreeRoot root
                  :expectations {:nodes [{:kind :namespace
                                          :path "src/app.clj"}]}
                  :coverage {:declaredSourceKinds ["code"]
                             :scoreableSourceKinds ["code"]
                             :scoreableFilesByKind [{:kind "code"
                                                     :files 1}]
                             :missingDeclaredSourceKinds []}
                  :groundTruth {:changedFiles ["src/app.clj"]
                                :unsupportedGroundTruthFiles []}}
        agent-result {:schema benchmark/agent-result-schema
                      :caseId "case-1"
                      :caseFingerprint "sha256:test-case"
                      :agentInputFingerprint "sha256:test-input"
                      :agentId "codex"
                      :mode "ygg"
                      :suspectedFiles [{:path "src/app.clj"
                                        :rank 1
                                        :confidence 0.8
                                        :reason "Yggdrasil context identified the file."
                                        :evidence ["context-doc:src/app.clj"]}]
                      :suspectedSymbols []
                      :commands ["bb ask app --project suite-case-1"]
                      :warnings []
                      :summary "Found the app file."}
        hint-diagnostic {:kind "source-extraction-diagnostics"
                         :severity "warning"
                         :case-id "case-1"
                         :message "extractor emitted diagnostics"}
        graph-expectations {:schema benchmark/graph-expectations-schema
                            :status "passed"
                            :summary {:expectedNodes 1
                                      :foundNodes 1
                                      :missingNodes 0}}
        context-ranks {:files [{:path "src/app.clj"
                                :rank 1
                                :found? true}]
                       :selection {:candidateFiles 1}}]
    (spit-json! root
                "suite/cases/case-1/agent-results/codex.json"
                agent-result)
    (spit-json! root
                "suite/cases/case-1/agent-runs/codex.json"
                {:schema benchmark/agent-run-schema
                 :case-id "case-1"
                 :agentId "codex"
                 :mode "ygg"
                 :status "passed"
                 :artifacts {:agentResultPath result-path}
                 :ygg {:indexSummary {:files 1}
                       :systemSummary {:systems 1}}})
    (spit-json! root
                "suite/cases/case-1/agent-contexts/codex.ygg-hints.json"
                {:schema benchmark/agent-hints-schema
                 :diagnostics [hint-diagnostic]
                 :architecture {:validationGaps []}})
    (spit-json! root
                "suite/cases/case-1/agent-contexts/codex.ygg-context.json"
                {:query "app"
                 :docs [{:source {:path "src/app.clj"
                                  :heading "app"
                                  :kind "code"}
                         :score 1.0
                         :snippet "app"
                         :provenance "retrieved-doc"}]})
    (with-redefs [benchmark/prepare-case! (fn [_suite _case _opts] prepared)
                  benchmark/score-agent-result-graph-expectations
                  (fn [_suite _case _prepared _opts]
                    graph-expectations)
                  benchmark/context-ground-truth-ranks-from-path
                  (fn [_prepared _path]
                    context-ranks)]
      (let [scored (benchmark/score-agent-result! suite
                                                  case
                                                  {:out root
                                                   :result-path result-path})
            written (json/read-json (slurp score-path) :key-fn keyword)
            written-hints (json/read-json
                           (slurp (io/file root
                                           "suite/cases/case-1/agent-contexts/codex.ygg-hints.json"))
                           :key-fn keyword)]
        (is (= graph-expectations (:graphExpectations scored)))
        (is (= context-ranks (:contextGroundTruthRanks scored)))
        (is (nil? (:yggHints scored)))
        (is (= ["code"]
               (get-in written-hints [:selection :coverageSourceKinds])))
        (is (empty? (:diagnostics written-hints)))
        (is (= "passed" (get-in scored [:maintenancePreflight :status])))
        (is (= "passed"
               (get-in scored
                       [:maintenancePreflight :checks :graphExpectations :status])))
        (is (= "passed"
               (get-in scored
                       [:maintenancePreflight :checks :hintDiagnostics :status])))
        (is (= "passed"
               (get-in scored
                       [:maintenancePreflight :checks :syncCheck :status])))
        (is (= true (:claimReady scored)))
        (is (= (select-keys scored [:graphExpectations
                                    :contextGroundTruthRanks
                                    :yggHints
                                    :maintenancePreflight
                                    :claimReady])
               (select-keys written [:graphExpectations
                                     :contextGroundTruthRanks
                                     :yggHints
                                     :maintenancePreflight
                                     :claimReady])))))))

(deftest benchmark-agent-activity-rows-record-schema-validation-only
  (let [root (temp-dir "ygg-bench-activity-rows")
        result-file (io/file root "result.json")
        prepared {:project-id "suite-case-1"
                  :case-id "case-1"
                  :caseFingerprint "case-fingerprint"}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "ygg"
                      :agentInputFingerprint "agent-input-fingerprint"
                      :suspectedFiles [{:path "src/answer.clj"}]}
        rows (benchmark-maintenance/benchmark-activity-rows prepared
                                                            agent-result
                                                            result-file
                                                            {:agent-id "codex"}
                                                            "passed"
                                                            1000)
        item (first (:items rows))
        event (first (:events rows))]
    (is (= :benchmark-codex (:source rows)))
    (is (= "benchmark-agent-result" (:kind item)))
    (is (= :done (:status item)))
    (is (= benchmark/prepared-case-schema (:payload-schema item)))
    (is (= benchmark/agent-result-schema (:expected-result-schema item)))
    (is (= benchmark/agent-result-schema (:result-schema item)))
    (is (= ["suite-case-1" "case-1" "case-fingerprint" "agent-input-fingerprint"]
           (:target-ids item)))
    (is (not (str/includes? (:summary item) "src/answer.clj")))
    (is (= :validation (:event-kind event)))
    (is (= "benchmark result-schema matching" (:summary event)))))

(deftest benchmark-agent-activity-updates-sync-inspect-families
  (let [root (temp-dir "ygg-bench-activity-sync")
        worktree (io/file root "worktree")
        _ (.mkdirs worktree)
        result-file (io/file root "result.json")
        suite {:id "suite"}
        case {:id "case-1"}
        opts {:out root
              :agent-id "codex"
              :now-ms 1000}
        prepared {:suite-id "suite"
                  :project-id "suite-case-1"
                  :case-id "case-1"
                  :caseFingerprint "case-fingerprint"
                  :repo-id "repo"
                  :worktreeRoot (.getPath worktree)}
        agent-result {:schema benchmark/agent-result-schema
                      :agentId "codex"
                      :mode "ygg"
                      :agentInputFingerprint "agent-input-fingerprint"}]
    (store/with-node (benchmark-paths/xtdb-dir suite case opts)
      (fn [_xtdb] nil))
    (let [recorded (benchmark-maintenance/record-benchmark-agent-activity-from-artifacts!
                    suite
                    case
                    prepared
                    opts
                    agent-result
                    result-file
                    "passed")
          family-by-name (into {}
                               (map (juxt :family identity))
                               (get-in recorded [:syncInspect :families]))]
      (is (= {:items 1
              :events 1
              :deleted-items 0
              :deleted-events 0}
             (:activity recorded)))
      (is (= :available (get-in family-by-name [:activity :status])))
      (is (= {:activity-items 1
              :activity-events 1}
             (get-in family-by-name [:activity :counts])))
      (is (= :available (get-in family-by-name [:validation-history :status])))
      (is (= {:validation-events 1
              :result-schema-status-items 1
              :result-schema-matching-items 1
              :result-schema-mismatch-items 0
              :result-schema-missing-result-items 0
              :result-schema-unexpected-result-items 0
              :result-schema-mismatch-events 0}
             (get-in family-by-name [:validation-history :counts])))
      (is (= :available (get-in family-by-name [:map-overlay :status])))
      (is (= 1 (get-in family-by-name [:map-overlay :counts :map-file])))
      (is (.isFile (io/file (benchmark-paths/agent-map-path suite case opts)))))))

(deftest benchmark-agent-map-includes-case-map-overlay-package-imports
  (let [root (temp-dir "ygg-bench-case-map-overlay")
        suite {:id "suite"}
        case {:id "case-1"
              :map-overlay {:packageImports [{:repo "repo"
                                              :import "LinqToDB"
                                              :ecosystem :nuget
                                              :package "linq2db.SqlServer"
                                              :reason "Reviewed package import mapping."}
                                             {:repo "repo"
                                              :import "LinqToDB"
                                              :ecosystem :nuget
                                              :package "linq2db.SqlServer"
                                              :reason "Duplicate should be ignored."}]}}
        opts {:out root}
        prepared {:project-id "suite-case-1"}
        map-path (benchmark-maintenance/prepare-agent-map! suite case prepared opts)
        _ (benchmark-maintenance/prepare-agent-map! suite case prepared opts)
        overlay (map-store/read-map map-path)]
    (is (= "suite-case-1" (:project overlay)))
    (is (= [{:import "LinqToDB"
             :ecosystem "nuget"
             :package "linq2db.SqlServer"
             :status "accepted"
             :repo "repo"
             :reason "Reviewed package import mapping."}]
           (:packageImports overlay)))))

(deftest benchmark-agent-map-rejects-incomplete-case-map-overlay-package-imports
  (let [root (temp-dir "ygg-bench-case-map-overlay-invalid")
        suite {:id "suite"}
        case {:id "case-1"
              :map-overlay {:packageImports [{:repo "repo"
                                              :import "LinqToDB"
                                              :ecosystem :nuget
                                              :reason "Missing package should fail."}]}}
        opts {:out root}
        prepared {:project-id "suite-case-1"}
        map-path (benchmark-paths/agent-map-path suite case opts)]
    (try
      (benchmark-maintenance/prepare-agent-map! suite case prepared opts)
      (is false "expected incomplete package import to fail")
      (catch clojure.lang.ExceptionInfo e
        (is (= {:missing-fields [:package]
                :required-fields [:import :ecosystem :package]}
               (select-keys (ex-data e) [:missing-fields :required-fields])))))
    (is (not (.exists (io/file map-path))))))

(deftest context-ground-truth-ranks-show-context-misses-separately
  (let [root (temp-dir "ygg-bench-context-ground-truth")
        _ (spit-file! root "src/visible.clj" "(ns visible)\n")
        _ (spit-file! root "src/below-limit.clj" "(ns below-limit)\n")
        prepared {:worktreeRoot root
                  :groundTruth {:changedFiles ["src/below-limit.clj"]
                                :unsupportedGroundTruthFiles []}}
        packet {:query "visible below limit"
                :docs [{:source {:path "src/visible.clj"
                                 :heading "visible"}
                        :score 2.0
                        :snippet "visible"
                        :provenance "retrieved-doc"}]
                :candidateFiles [{:path "src/below-limit.clj"
                                  :rank 99
                                  :score 0.1
                                  :targetKind :chunk
                                  :label "below limit"}]}
        ranks (#'benchmark/context-ground-truth-ranks prepared packet)]
    (is (= [{:path "src/below-limit.clj"
             :rank 1
             :found? true}]
           (get-in ranks [:files])))
    (is (= {:rawCandidateFiles 2
            :candidateFiles 2
            :coverageFilteredCandidateFiles 0
            :limit nil
            :coverageSourceKinds []}
           (:selection ranks)))))
