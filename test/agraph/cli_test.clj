(ns agraph.cli-test
  (:require [agraph.affected :as affected]
            [agraph.agent-install :as agent-install]
            [agraph.activity :as activity]
            [agraph.audit-scope :as audit-scope]
            [agraph.benchmark :as benchmark]
            [agraph.cli :as cli]
            [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.cursor :as cursor]
            [agraph.dependency-review :as dependency-review]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.hook :as hook]
            [agraph.init :as init]
            [agraph.infra-review :as infra-review]
            [agraph.map :as graph-map]
            [agraph.plugin-package :as plugin-package]
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
    (is (str/includes? usage "Plugins:"))
    (is (str/includes? usage "Agent integration:"))
    (is (str/includes? usage "Server integration:"))
    (is (str/includes? usage "start <repo-root>"))
    (is (str/includes? usage "status <project.edn>"))
    (is (str/includes? usage "audit-scope <project.edn>"))
    (is (str/includes? usage "sync <project.edn>"))
    (is (str/includes? usage "init <repo-root>"))
    (is (str/includes? usage "ask <text>"))
    (is (str/includes? usage "explore <text>"))
    (is (str/includes? usage "explore create"))
    (is (str/includes? usage "affected <project.edn>"))
    (is (str/includes? usage "view overview|deps|query|systems"))
    (is (str/includes? usage "report <project.edn>"))
    (is (str/includes? usage "plugin new <dir>"))
    (is (str/includes? usage "plugin validate <dir>"))
    (is (str/includes? usage "plugin diagnose <dir>"))
    (is (str/includes? usage "plugin core-check <dir>"))
    (is (str/includes? usage "plugin dry-run extractor <dir>"))
    (is (str/includes? usage "plugin dry-run report <dir>"))
    (is (str/includes? usage "plugin input extractor <dir>"))
    (is (str/includes? usage "plugin install <project.edn>"))
    (is (str/includes? usage "plugin update <project.edn> <package-id>"))
    (is (str/includes? usage "plugin list <project.edn>"))
    (is (str/includes? usage "plugin remove <project.edn> <package-id>"))
    (is (str/includes? usage "plugin registry validate <registry.edn>"))
    (is (str/includes? usage "agent install --platform codex --project"))
    (is (str/includes? usage "--print-config"))
    (is (str/includes? usage "agent uninstall --platform codex --project"))
    (is (str/includes? usage "agent list"))
    (is (not (str/includes? usage "  install --platform codex --project")))
    (is (not (str/includes? usage "  uninstall --platform codex --project")))
    (is (not (str/includes? usage "install-agent --platform codex --project")))
    (is (str/includes? usage "mcp [--root DIR]"))
    (is (str/includes? usage "watch <project.edn>"))
    (is (str/includes? usage "hook install <project.edn>"))
    (is (str/includes? usage "bench prepare|run|report"))
    (is (str/includes? usage "bench show"))
    (is (str/includes? usage "bench agent-packet"))
    (is (str/includes? usage "bench agent-baseline"))
    (is (str/includes? usage "bench agent-run"))
    (is (str/includes? usage "bench agent-score"))
    (is (str/includes? usage "bench agent-score <benchmark.edn> --case ID --result result.json [--parser-worker none|java|dotnet|all]"))
    (is (str/includes? usage "bench agent-report"))
    (is (str/includes? usage "bench agent-check"))
    (is (str/includes? usage "bench agent-compare"))
    (is (str/includes? usage "sync work heartbeat"))
    (is (str/includes? usage "--cases ID,ID"))
    (is (str/includes? usage "--min-evidence-citation-rate N"))
    (is (str/includes? usage "--min-path-evidence-citation-rate N"))
    (is (str/includes? usage "--min-case-evidence-citation-rate N"))
    (is (str/includes? usage "--min-case-path-evidence-citation-rate N"))
    (is (str/includes? usage "--max-commandless-runs N"))
    (is (str/includes? usage "--max-missing-predicted-file-runs N"))
    (is (str/includes? usage "--max-warning-runs N"))
    (is (str/includes? usage "--max-identity-mismatch-runs N"))
    (is (str/includes? usage "--max-missed-but-present-in-context-runs N"))
    (is (str/includes? usage "--max-missed-and-absent-from-context-runs N"))
    (is (str/includes? usage "--require-parser-worker none|java|dotnet|all"))
    (is (not (str/includes? usage "overlay")))))

(deftest plugin-install-dispatches-to-package-installer
  (let [calls (atom [])
        claim-authority {:status :non-authoritative
                         :public-claims? false
                         :review-required? false
                         :blockers [{:code :unbenchmarked
                                     :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}]
    (with-redefs [plugin-package/install! (fn [config-path source opts]
                                            (swap! calls conj [config-path source opts])
                                            {:schema plugin-package/install-schema
                                             :project-id "fixture"
                                             :package {:id "pkg"
                                                       :version "0.1.0"
                                                       :extractor-plugins 1
                                                       :report-plugins 1
                                                       :benchmark-status :unbenchmarked
                                                       :claim-authority claim-authority
                                                       :manifest-fingerprint "sha256:abc123"
                                                       :source {:url source
                                                                :rev "abc123"}
                                                       :warnings []}
                                             :entry {:manifest "agraph.plugin.edn"
                                                     :manifest-fingerprint "sha256:abc123"
                                                     :path "/tmp/pkg"}
                                             :force? (:force? opts)})]
      (let [out (with-out-str
                  (cli/dispatch "plugin"
                                ["install"
                                 "project.edn"
                                 "git@example.test:org/pkg.git"
                                 "--ref"
                                 "v0.1.0"
                                 "--subdir"
                                 "packages/pkg"
                                 "--cache-dir"
                                 ".cache/plugins"
                                 "--force"]))]
        (is (str/includes? out "# Plugin Installed"))
        (is (str/includes? out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes? out "claim-blockers unbenchmarked"))
        (is (str/includes? out "manifest-fingerprint sha256:abc123"))
        (is (str/includes? out "fingerprint=sha256:abc123"))
        (is (= [["project.edn"
                 "git@example.test:org/pkg.git"
                 {:ref "v0.1.0"
                  :subdir "packages/pkg"
                  :cache-root ".cache/plugins"
                  :force? true}]]
               @calls))))))

(deftest plugin-update-dispatches-to-package-updater
  (let [calls (atom [])
        claim-authority {:status :non-authoritative
                         :public-claims? false
                         :review-required? false
                         :blockers [{:code :unbenchmarked
                                     :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}]
    (with-redefs [plugin-package/update! (fn [config-path package-id opts]
                                           (swap! calls conj [config-path package-id opts])
                                           {:schema plugin-package/update-schema
                                            :project-id "fixture"
                                            :package-id package-id
                                            :refresh? true
                                            :update-ref "v0.2.0"
                                            :update-subdir "packages/pkg"
                                            :previous-entry {:source {:rev "oldrev"}}
                                            :package {:id package-id
                                                      :version "0.2.0"
                                                      :extractor-plugins 1
                                                      :report-plugins 1
                                                      :benchmark-status :unbenchmarked
                                                      :claim-authority claim-authority
                                                      :manifest-fingerprint "sha256:def456"
                                                      :source {:url "git@example.test:org/pkg.git"
                                                               :rev "newrev"}
                                                      :warnings []}
                                            :entry {:manifest "agraph.plugin.edn"
                                                    :manifest-fingerprint "sha256:def456"
                                                    :path "/tmp/pkg"
                                                    :source {:rev "newrev"}}})]
      (let [out (with-out-str
                  (cli/dispatch "plugin"
                                ["update"
                                 "project.edn"
                                 "pkg"
                                 "--ref"
                                 "v0.2.0"
                                 "--subdir"
                                 "packages/pkg"
                                 "--cache-dir"
                                 ".cache/plugins"]))]
        (is (str/includes? out "# Plugin Updated"))
        (is (str/includes? out "- previous-rev oldrev"))
        (is (str/includes? out "- rev newrev"))
        (is (str/includes? out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes? out "manifest-fingerprint sha256:def456"))
        (is (= [["project.edn"
                 "pkg"
                 {:ref "v0.2.0"
                  :subdir "packages/pkg"
                  :cache-root ".cache/plugins"}]]
               @calls))))))

(deftest plugin-core-check-dispatches-to-package-gate
  (let [calls (atom [])
        claim-authority {:status :benchmark-backed
                         :public-claims? true
                         :review-required? true
                         :blockers []}]
    (with-redefs [plugin-package/core-promotion-check
                  (fn [dir]
                    (swap! calls conj dir)
                    {:schema plugin-package/core-check-schema
                     :status :passed
                     :package-dir dir
                     :package {:id "core-ready"
                               :version "0.1.0"
                               :claim-authority claim-authority}
                     :core-promotion {:status :review-required
                                      :reason "Benchmark metadata is present; project-agnostic core suitability still needs review."
                                      :next-actions ["Verify there are no project-specific rules."]}
                     :diagnostics []})]
      (let [out (with-out-str
                  (cli/dispatch "plugin" ["core-check" ".dev/plugins/core-ready"]))]
        (is (str/includes? out "# Plugin Core Promotion Check"))
        (is (str/includes? out "- status passed"))
        (is (str/includes? out "- core-promotion review-required"))
        (is (str/includes? out "claim-authority status=benchmark-backed public-claims=true"))
        (is (= [".dev/plugins/core-ready"] @calls))))))

(deftest plugin-authoring-dispatches-to-package-helpers
  (let [calls (atom [])
        claim-authority {:status :non-authoritative
                         :public-claims? false
                         :review-required? false
                         :blockers [{:code :project-local
                                     :message "Project-local package output stays external and cannot support public claims."}
                                    {:code :unbenchmarked
                                     :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}]
    (with-redefs [plugin-package/new! (fn [dir opts]
                                        (swap! calls conj [:new dir opts])
                                        {:schema plugin-package/new-schema
                                         :package-id (:id opts)
                                         :path dir
                                         :manifest (str dir "/agraph.plugin.edn")
                                         :file-kind (some-> (:file-kind opts) keyword)
                                         :fixture-path (:fixture-path opts)
                                         :files []
                                         :extractor? true
                                         :report? true})
                  plugin-package/validate-local (fn [dir]
                                                  (swap! calls conj [:validate dir])
                                                  {:schema plugin-package/validate-schema
                                                   :status :warning
                                                   :package {:id "demo"
                                                             :version "0.1.0"
                                                             :claim-authority claim-authority}
                                                   :extractor-plugins [{}]
                                                   :report-plugins [{}]
                                                   :warnings ["demo is unbenchmarked"]
                                                   :errors []})
                  plugin-package/diagnose-local (fn [dir]
                                                  (swap! calls conj [:diagnose dir])
                                                  {:schema plugin-package/diagnose-schema
                                                   :status :warning
                                                   :package {:id "demo"
                                                             :version "0.1.0"
                                                             :claim-authority claim-authority}
                                                   :diagnostics [{:severity :warning
                                                                  :code :unbenchmarked
                                                                  :message "demo is unbenchmarked"}]
                                                   :readiness {:local-use {:status :ready
                                                                           :reason "ready"
                                                                           :next-actions []}}})
                  plugin-package/dry-run-extractor (fn [dir root file opts]
                                                     (swap! calls conj [:dry-run dir root file opts])
                                                     {:schema plugin-package/dry-run-schema
                                                      :kind :extractor
                                                      :status :passed
                                                      :package {:id "demo"
                                                                :version "0.1.0"
                                                                :benchmark-status :unbenchmarked
                                                                :claim-authority claim-authority
                                                                :scope {:kind :project-local}
                                                                :manifest-fingerprint "sha256:demo"
                                                                :warnings ["demo is unbenchmarked"]}
                                                      :plugins [{:id "demo-extractor"}]
                                                      :selection {:kind :extractor
                                                                  :requested-plugin-id "demo-extractor"
                                                                  :available ["demo-extractor"
                                                                              "other-extractor"]
                                                                  :selected ["demo-extractor"]
                                                                  :skipped ["other-extractor"]
                                                                  :counts {:available 2
                                                                           :selected 1
                                                                           :skipped 1}}
                                                      :file {:path file
                                                             :kind :code}
                                                      :core-counts {:nodes 1}
                                                      :enhanced-counts {:nodes 2}
                                                      :diagnostics []})
                  plugin-package/dry-run-report (fn [dir opts]
                                                  (swap! calls conj [:dry-run-report dir opts])
                                                  {:schema plugin-package/dry-run-schema
                                                   :kind :report
                                                   :status :passed
                                                   :package {:id "demo"
                                                             :version "0.1.0"
                                                             :benchmark-status :unbenchmarked
                                                             :claim-authority claim-authority
                                                             :scope {:kind :project-local}
                                                             :manifest-fingerprint "sha256:demo"
                                                             :warnings ["demo is unbenchmarked"]}
                                                   :plugins [{:id "demo-report"}]
                                                   :selection {:kind :report
                                                               :requested-plugin-id "demo-report"
                                                               :available ["demo-report"
                                                                           "other-report"]
                                                               :selected ["demo-report"]
                                                               :skipped ["other-report"]
                                                               :counts {:available 2
                                                                        :selected 1
                                                                        :skipped 1}}
                                                   :counts {:panels 1
                                                            :diagnostics 0
                                                            :artifacts 0}
                                                   :diagnostics []})
                  plugin-package/sample-extractor-inputs
                  (fn [dir root file opts]
                    (swap! calls conj [:input dir root file opts])
                    {:schema plugin-package/input-sample-schema
                     :kind :extractor
                     :status :passed
                     :package {:id "demo"
                               :version "0.1.0"
                               :benchmark-status :unbenchmarked
                               :claim-authority claim-authority
                               :scope {:kind :project-local}}
                     :plugins [{:id "demo-extractor"}]
                     :selection {:kind :extractor
                                 :requested-plugin-id "demo-extractor"
                                 :available ["demo-extractor" "other-extractor"]
                                 :selected ["demo-extractor"]
                                 :skipped ["other-extractor"]
                                 :counts {:available 2
                                          :selected 1
                                          :skipped 1}}
                     :file {:path file
                            :kind :code}
                     :core-counts {:nodes 1
                                   :edges 0
                                   :chunks 1
                                   :file-facts 0
                                   :diagnostics 0}
                     :diagnostics []
                     :inputs [{:schema "agraph.extractor-plugin.input/v1"
                               :plugin {:id "demo-extractor"}}]})
                  plugin-package/remove! (fn [config-path package-id]
                                           (swap! calls conj [:remove config-path package-id])
                                           {:schema plugin-package/remove-schema
                                            :project-id "demo-project"
                                            :package-id package-id
                                            :removed-entry {:path ".dev/plugins/demo"}
                                            :remaining 0})
                  plugin-package/validate-registry (fn [path]
                                                     (swap! calls conj [:registry path])
                                                     {:schema plugin-package/registry-validate-schema
                                                      :status :passed
                                                      :path path
                                                      :counts {:packages 1
                                                               :passed 1
                                                               :failed 0
                                                               :claim-ready 0
                                                               :non-authoritative 1}
                                                      :errors []
                                                      :packages [{:id "demo"
                                                                  :status :passed
                                                                  :install {:command "bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"}
                                                                  :diagnosis {:package {:claim-authority claim-authority}}
                                                                  :errors []}]})]
      (with-out-str
        (cli/dispatch "plugin" ["new" ".dev/plugins/demo" "--id" "demo" "--force"]))
      (let [new-out (with-out-str
                      (cli/dispatch "plugin"
                                    ["new"
                                     ".dev/plugins/htmx"
                                     "--id"
                                     "htmx"
                                     "--file-kind"
                                     "htmx"
                                     "--path-glob"
                                     "templates/*.html"
                                     "--scan-glob"
                                     "templates/*.html"
                                     "--fixture"
                                     "fixtures/sample.html"
                                     "--extractor"]))]
        (is (str/includes? new-out "- file-kind htmx"))
        (is (str/includes? new-out "- fixture fixtures/sample.html")))
      (let [validate-out (with-out-str
                           (cli/dispatch "plugin" ["validate" ".dev/plugins/demo"]))
            diagnose-out (with-out-str
                           (cli/dispatch "plugin" ["diagnose" ".dev/plugins/demo"]))
            extractor-out (with-out-str
                            (cli/dispatch "plugin"
                                          ["dry-run"
                                           "extractor"
                                           ".dev/plugins/demo"
                                           "."
                                           "src/page.clj"
                                           "--plugin"
                                           "demo-extractor"]))
            input-out (with-out-str
                        (cli/dispatch "plugin"
                                      ["input"
                                       "extractor"
                                       ".dev/plugins/demo"
                                       "."
                                       "src/page.clj"
                                       "--plugin"
                                       "demo-extractor"]))
            report-out (with-out-str
                         (cli/dispatch "plugin"
                                       ["dry-run"
                                        "report"
                                        ".dev/plugins/demo"
                                        "--plugin"
                                        "demo-report"]))]
        (is (str/includes? validate-out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes? diagnose-out "claim-blockers project-local,unbenchmarked"))
        (is (str/includes? extractor-out "benchmark=unbenchmarked"))
        (is (str/includes? extractor-out "scope=project-local"))
        (is (str/includes? extractor-out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes? extractor-out "claim-blockers project-local,unbenchmarked"))
        (is (str/includes? extractor-out "manifest-fingerprint sha256:demo"))
        (is (str/includes? extractor-out "warning demo is unbenchmarked"))
        (is (str/includes?
             extractor-out
             "- selection available=demo-extractor,other-extractor selected=demo-extractor skipped=other-extractor"))
        (is (str/includes? extractor-out "- requested-plugin demo-extractor"))
        (is (str/includes? input-out "# Plugin Input Sample"))
        (is (str/includes? input-out "- inputs 1"))
        (is (str/includes?
             input-out
             "- selection available=demo-extractor,other-extractor selected=demo-extractor skipped=other-extractor"))
        (is (str/includes? report-out "benchmark=unbenchmarked"))
        (is (str/includes? report-out "scope=project-local"))
        (is (str/includes? report-out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes?
             report-out
             "- selection available=demo-report,other-report selected=demo-report skipped=other-report"))
        (is (str/includes? report-out "- requested-plugin demo-report")))
      (let [remove-out (with-out-str
                         (cli/dispatch "plugin"
                                       ["remove"
                                        "project.edn"
                                        "demo"]))]
        (is (str/includes? remove-out "# Plugin Removed"))
        (is (str/includes? remove-out "- package demo")))
      (let [registry-out (with-out-str
                           (cli/dispatch "plugin"
                                         ["registry"
                                          "validate"
                                          ".dev/plugins/registry.edn"]))]
        (is (str/includes? registry-out
                           "install bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"))
        (is (str/includes? registry-out "- non-authoritative 1"))
        (is (str/includes? registry-out
                           "claim-blockers project-local,unbenchmarked")))
      (is (= [[:new ".dev/plugins/demo" {:id "demo"
                                         :extractor? false
                                         :report? false
                                         :force? true}]
              [:new ".dev/plugins/htmx" {:id "htmx"
                                         :extractor? true
                                         :report? false
                                         :force? false
                                         :file-kind "htmx"
                                         :path-globs "templates/*.html"
                                         :scan-globs "templates/*.html"
                                         :fixture-path "fixtures/sample.html"}]
              [:validate ".dev/plugins/demo"]
              [:diagnose ".dev/plugins/demo"]
              [:dry-run ".dev/plugins/demo" "." "src/page.clj" {:plugin-id "demo-extractor"}]
              [:input ".dev/plugins/demo" "." "src/page.clj" {:plugin-id "demo-extractor"}]
              [:dry-run-report ".dev/plugins/demo" {:plugin-id "demo-report"}]
              [:remove "project.edn" "demo"]
              [:registry ".dev/plugins/registry.edn"]]
             @calls)))))

(deftest plugin-dry-run-failure-exits-with-diagnostics
  (let [diagnostic {:severity :error
                    :code :no-extractor-plugins-selected
                    :message "demo has no extractor plugins selected for this dry-run."}
        calls (atom [])]
    (with-redefs [plugin-package/dry-run-extractor
                  (fn [dir root file opts]
                    (swap! calls conj [dir root file opts])
                    {:schema plugin-package/dry-run-schema
                     :kind :extractor
                     :status :failed
                     :package {:id "demo"
                               :version "0.1.0"
                               :benchmark-status :unbenchmarked
                               :scope {:kind :project-local}}
                     :plugins []
                     :file {:path file}
                     :core-counts {:nodes 0}
                     :enhanced-counts {:nodes 0}
                     :diagnostics [diagnostic]
                     :rows {:diagnostics [diagnostic]}})]
      (let [error (atom nil)
            out (with-out-str
                  (try
                    (cli/dispatch "plugin"
                                  ["dry-run"
                                   "extractor"
                                   ".dev/plugins/demo"
                                   "."
                                   "src/missing.clj"])
                    (catch clojure.lang.ExceptionInfo e
                      (reset! error e))))]
        (is (str/includes? out "- status failed"))
        (is (str/includes? out "error no-extractor-plugins-selected"))
        (is (= "Plugin dry-run failed." (ex-message @error)))
        (is (= {:kind "extractor"
                :status :failed
                :diagnostics [diagnostic]}
               (ex-data @error)))
        (is (= [[".dev/plugins/demo"
                 "."
                 "src/missing.clj"
                 {:plugin-id nil}]]
               @calls))))))

(deftest benchmark-summary-prints-agent-baseline-scores
  (let [out (with-out-str
              (#'cli/print-benchmark-summary
               {:schema benchmark/agent-baselines-schema
                :suite-id "suite"
                :completed 1
                :skipped 0
                :baselines [{:case-id "case-1"
                             :repo-id "repo"
                             :agentId "agraph-baseline-lexical"
                             :scores {:fileRecallAt10 0.5
                                      :meanReciprocalRankFile 1.0}}]}))]
    (is (str/includes? out "- skipped 0"))
    (is (str/includes? out "case-1 repo agent agraph-baseline-lexical status ran recall@10 0.50 mrr 1.00"))
    (is (not (str/includes? out "- file-recall@10 0.00")))))

(deftest maintenance-summary-prints-decision-breakdown
  (let [out (with-out-str
              (#'cli/print-maintenance-report
               {:project-id "fixture"
                :graph-basis {:hash "basis123"}
                :counts {:maintenance-decisions 2}
                :decision-summary {:total 2
                                   :bySeverity [{:severity :high
                                                 :count 1}
                                                {:severity :low
                                                 :count 1}]
                                   :byKind [{:kind :ambiguous-high-salience-edge
                                             :count 1}
                                            {:kind :unclustered-system
                                             :count 1}]
                                   :byRecommendedAction [{:action :accept-system
                                                          :count 1}
                                                         {:action :set-edge-visibility
                                                          :count 2}]}
                :decision-queue []}))]
    (is (str/includes? out "# Maintain"))
    (is (str/includes? out "- decision-summary severity high:1,low:1 kind ambiguous-high-salience-edge:1,unclustered-system:1 action accept-system:1,set-edge-visibility:2"))))

(deftest benchmark-summary-prints-parser-worker-profiles
  (let [out (with-out-str
              (#'cli/print-benchmark-summary
               {:schema benchmark/agent-report-schema
                :suite-id "suite"
                :cases 1
                :completed 1
                :runs 2
                :scores {:fileRecallAt10 0.5
                         :meanReciprocalRankFile 0.25}
                :parserWorkers [{:mode "all"
                                 :source "option"
                                 :runs 1}
                                {:mode "unknown"
                                 :source "missing"
                                 :runs 1}]}))]
    (is (str/includes? out "- parser-workers all/option:1, unknown/missing:1"))))

(deftest benchmark-summary-prints-agent-diagnostics
  (let [diagnostics {:missingPredictedFileRuns 1
                     :missingPredictedFileCaseIds ["case-1"]
                     :missingPredictedFiles 2
                     :commandlessRuns 1
                     :commandlessCaseIds ["case-2"]
                     :warningRuns 1
                     :warningCaseIds ["case-3"]}
        report-out (with-out-str
                     (#'cli/print-benchmark-summary
                      {:schema benchmark/agent-report-schema
                       :suite-id "suite"
                       :cases 3
                       :completed 3
                       :runs 3
                       :scores {:fileRecallAt10 0.5
                                :meanReciprocalRankFile 0.25
                                :evidenceCitationRate 0.75}
                       :agentDiagnostics diagnostics}))
        check-out (with-out-str
                    (#'cli/print-benchmark-summary
                     {:schema benchmark/agent-check-schema
                      :suite-id "suite"
                      :status "failed"
                      :report {:cases 3
                               :completed 3
                               :runs 3
                               :scores {:fileRecallAt10 0.5
                                        :meanReciprocalRankFile 0.25
                                        :evidenceCitationRate 0.75
                                        :noiseRatioAt20 0.9}
                               :agentDiagnostics diagnostics}
                      :failures []}))]
    (doseq [out [report-out check-out]]
      (is (str/includes? out "- missing-predicted-file-runs 1 files 2 cases case-1"))
      (is (str/includes? out "- commandless-runs 1 cases case-2"))
      (is (str/includes? out "- warning-runs 1 cases case-3")))))

(deftest benchmark-summary-prints-artifact-diagnostics
  (let [diagnostics {:unverifiedScoreRuns 2
                     :unverifiedScoreCaseIds ["case-1" "case-2"]
                     :obsoleteScoreSchemaRuns 1
                     :obsoleteScoreSchemaCaseIds ["case-1"]
                     :obsoleteScoreSchemas ["agraph.benchmark.agent-score/v1"]
                     :expectedScoreSchema benchmark/agent-score-schema
                     :obsoleteAgentResultSchemaRuns 1
                     :obsoleteAgentResultSchemaCaseIds ["case-1"]
                     :obsoleteAgentResultSchemas ["agraph.benchmark.agent-result/v1"]
                     :expectedAgentResultSchema benchmark/agent-result-schema
                     :staleScoreRuns 1
                     :staleScoreCaseIds ["case-2"]}
        report-out (with-out-str
                     (#'cli/print-benchmark-summary
                      {:schema benchmark/agent-report-schema
                       :suite-id "suite"
                       :cases 2
                       :completed 1
                       :runs 1
                       :scores {:fileRecallAt10 0.5
                                :meanReciprocalRankFile 0.25
                                :evidenceCitationRate 0.75}
                       :artifactDiagnostics diagnostics}))
        check-out (with-out-str
                    (#'cli/print-benchmark-summary
                     {:schema benchmark/agent-check-schema
                      :suite-id "suite"
                      :status "failed"
                      :report {:cases 2
                               :completed 1
                               :runs 1
                               :scores {:fileRecallAt10 0.5
                                        :meanReciprocalRankFile 0.25
                                        :evidenceCitationRate 0.75
                                        :noiseRatioAt20 0.9}
                               :artifactDiagnostics diagnostics}
                      :failures []}))]
    (doseq [out [report-out check-out]]
      (is (str/includes? out "- unverified-score-runs 2 cases case-1,case-2"))
      (is (str/includes? out "- obsolete-score-schema-runs 1 schemas agraph.benchmark.agent-score/v1 expected "))
      (is (str/includes? out "- obsolete-agent-result-schema-runs 1 schemas agraph.benchmark.agent-result/v1 expected "))
      (is (str/includes? out " cases case-1"))
      (is (str/includes? out "- stale-score-runs 1 cases case-2")))))

(deftest benchmark-summary-prints-claim-readiness
  (let [claim-readiness {:status "not-supported"
                         :broadArchitectureClaimSupported false
                         :measuredProblemClassTags ["problem-architecture"]
                         :measuredArchitectureClassTags []
                         :warnings ["No measured architecture-class groups."]}
        report-out (with-out-str
                     (#'cli/print-benchmark-summary
                      {:schema benchmark/agent-report-schema
                       :suite-id "suite"
                       :cases 2
                       :completed 2
                       :runs 2
                       :scores {:fileRecallAt10 0.5
                                :meanReciprocalRankFile 0.25
                                :evidenceCitationRate 0.75}
                       :claimReadiness claim-readiness}))
        check-out (with-out-str
                    (#'cli/print-benchmark-summary
                     {:schema benchmark/agent-check-schema
                      :suite-id "suite"
                      :status "failed"
                      :report {:cases 2
                               :completed 2
                               :runs 2
                               :scores {:fileRecallAt10 0.5
                                        :meanReciprocalRankFile 0.25
                                        :evidenceCitationRate 0.75
                                        :noiseRatioAt20 0.5}
                               :claimReadiness claim-readiness}
                      :failures []}))]
    (doseq [out [report-out check-out]]
      (is (str/includes? out "- claim-readiness not-supported"))
      (is (str/includes? out "- measured-problem-classes problem-architecture"))
      (is (str/includes? out "## Claim Readiness Warnings"))
      (is (str/includes? out "- No measured architecture-class groups.")))))

(deftest benchmark-summary-prints-compare-comparability
  (let [out (with-out-str
              (#'cli/print-benchmark-summary
               {:schema benchmark/agent-compare-schema
                :suite-id "suite"
                :status "passed"
                :tolerance 0.0
                :aggregateComparable false
                :aggregateComparableReasons ["parser-worker-profile-changed"]
                :baseline {:scores {:fileRecallAt10 0.8
                                    :meanReciprocalRankFile 0.5}}
                :candidate {:scores {:fileRecallAt10 0.9
                                     :meanReciprocalRankFile 0.6}}
                :regressions []}))]
    (is (str/includes? out "- aggregate-comparable false"))
    (is (str/includes? out "- aggregate-comparable-reasons parser-worker-profile-changed"))))

(deftest affected-command-routes-through-project-config
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:project path])
                                         project-fixture)
                  store/with-node (fn [_ f]
                                    (f :xtdb))
                  affected/analyze (fn [xtdb project opts]
                                     (swap! calls conj [:affected xtdb (:id project) opts])
                                     {:schema affected/schema
                                      :project-id (:id project)
                                      :basis {:mode "explicit-files"
                                              :repo-id (:repo-id opts)
                                              :testsOnly (:tests-only? opts)}
                                      :inputs []
                                      :changedFiles []
                                      :changedNodes []
                                      :affectedFiles []
                                      :unsupportedIncidentEdges {:count 0
                                                                 :byRelation []
                                                                 :samples []}
                                      :warnings []
                                      :nextActions []})]
      (let [out (with-out-str
                  (cli/dispatch "affected" ["project.edn"
                                            "--repo" "app"
                                            "--files" "src/a.clj,src/b.clj"
                                            "--tests"
                                            "--json"]))
            parsed (read-json-output out)]
        (is (= affected/schema (:schema parsed)))
        (is (= [[:project "project.edn"]
                [:affected
                 :xtdb
                 "fixture"
                 {:repo-id "app"
                  :since nil
                  :config-path "project.edn"
                  :tests-only? true
                  :read-context {}
                  :files ["src/a.clj" "src/b.clj"]}]]
               @calls))))))

(deftest agent-list-shows-supported-platforms
  (let [out (with-out-str
              (cli/dispatch "agent" ["list"]))
        parsed (read-json-output out)]
    (is (= agent-install/schema (:schema parsed)))
    (is (= [{:id "codex"
             :scopes ["project"]
             :hooks? true}]
           (:platforms parsed)))))

(deftest agent-install-command-writes-codex-project-guidance
  (let [root (temp-dir "agraph-agent-install-cli")
        old-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" root)
      (let [out (with-out-str
                  (cli/dispatch "agent" ["install" "--platform" "codex" "--project"]))
            parsed (read-json-output out)
            agents (io/file root "AGENTS.md")]
        (is (= agent-install/schema (:schema parsed)))
        (is (= "install" (:action parsed)))
        (is (= (.getPath agents) (:instructions parsed)))
        (is (str/includes? (slurp agents) "AGraph Agent Workflow")))
      (finally
        (System/setProperty "user.dir" old-dir)))))

(deftest agent-install-print-config-does-not-write-files
  (let [root (temp-dir "agraph-agent-print-config-cli")
        old-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" root)
      (let [out (with-out-str
                  (cli/dispatch "agent" ["install"
                                         "--platform" "codex"
                                         "--project"
                                         "--hooks"
                                         "--print-config"]))
            parsed (read-json-output out)
            agents (io/file root "AGENTS.md")
            hooks (io/file root ".codex" "hooks.json")]
        (is (= agent-install/schema (:schema parsed)))
        (is (= "print-config" (:action parsed)))
        (is (= (.getPath agents) (get-in parsed [:instructions :path])))
        (is (= (.getPath hooks) (get-in parsed [:hooks :path])))
        (is (str/includes? (get-in parsed [:instructions :content])
                           "AGraph Agent Workflow"))
        (is (str/includes? (get-in parsed [:hooks :content])
                           "AGraph may have relevant project context"))
        (is (not (.exists agents)))
        (is (not (.exists hooks))))
      (finally
        (System/setProperty "user.dir" old-dir)))))

(deftest agent-uninstall-command-removes-codex-project-guidance
  (let [root (temp-dir "agraph-uninstall-cli")
        old-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" root)
      (agent-install/install! "codex" {:root root
                                       :project? true})
      (let [out (with-out-str
                  (cli/dispatch "agent" ["uninstall" "--platform" "codex" "--project"]))
            parsed (read-json-output out)
            agents (io/file root "AGENTS.md")]
        (is (= agent-install/schema (:schema parsed)))
        (is (= "uninstall" (:action parsed)))
        (is (= true (get-in parsed [:removed :instructions])))
        (is (not (.exists agents))))
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
      (is (str/includes? first-content "agraph status <project.edn> --json"))
      (is (str/includes? first-content "agraph audit-scope <project.edn> --map agraph.map.json --json"))
      (is (str/includes? first-content "`audit-scope --json` summarizes core evidence"))
      (is (str/includes? first-content "graph-basis freshness, `evidence.families`"))
      (is (str/includes? first-content "agraph explore \"<question>\" --project <project-id> --json"))
      (is (str/includes? first-content "agraph explore search <cursor-id> \"<follow-up query>\""))
      (is (str/includes? first-content "agraph sync check <project.edn> --map agraph.map.json --enqueue"))
      (is (str/includes? first-content "agraph sync work list --project <project-id> --status ready"))
      (is (str/includes? first-content "agraph sync work show <work-id>"))
      (is (str/includes? first-content "agraph sync work heartbeat <work-id> --agent codex --lease-minutes 30"))
      (is (str/includes? first-content "agraph-mcp --config project.edn --map agraph.map.json"))
      (is (str/includes? first-content "`agraph_explore`"))
      (is (str/includes? first-content "`agraph_node`"))
      (is (str/includes? first-content "`agraph_status`"))
      (is (str/includes? first-content "`agraph_systems`"))
      (is (str/includes? first-content "--tools default,cursor,sync,work,ask"))
      (is (str/includes? first-content "AGRAPH_MCP_TOOLS=all"))
      (is (str/includes? first-content "Use `agraph_explore` as the primary one-shot orientation packet"))
      (is (str/includes? first-content "with graph-basis freshness, `answerability.planes`"))
      (is (str/includes? first-content "relationships, graph facts, architecture evidence, and drilldowns"))
      (is (str/includes? first-content "Treat returned snippets as already-read source context"))
      (is (str/includes? first-content "nearby mechanical edges before broad grep/read loops"))
      (is (str/includes? first-content "Use queued review packets"))
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
                       "agraph status <project.edn> --json"))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "evidence.families"))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "agraph explore"))))

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
                                 "--parser-worker" "dotnet"
                                 "--enqueue"
                                 "--queue-dir" root
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "agraph.benchmark.agent-packets/v1" (:schema parsed)))
        (is (= {:case-id "case-1"
                :out nil
                :retriever nil
                :parser-worker "dotnet"
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
                                 "--parser-worker" "dotnet"
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
                  :parser-worker "dotnet"
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
                                 "--retrieval-limit" "80"
                                 "--vector-command" "fake-vector-worker"
                                 "--vector-model" "fake-vector-model"
                                 "--parser-worker" "java"
                                 "--index-timeout-ms" "1234"
                                 "--skip-existing"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-baselines-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:baseline {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever "lexical"
                  :parser-worker "java"
                  :mode nil
                  :result-path nil
                  :command nil
                  :vector-command "fake-vector-worker"
                  :vector-model "fake-vector-model"
                  :limit 3
                  :doc-limit 12
                  :retrieval-limit 80
                  :index-timeout-ms 1234
                  :skip-existing? true}]]
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
                                 "--skip-existing"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-runs-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:run-agent {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :parser-worker nil
                  :mode "agraph"
                  :agent-id "codex"
                  :result-path nil
                  :command "codex exec --json"
                  :prompt-profile "fast"
                  :timeout-ms 120000
                  :skip-existing? true}]]
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
                                 "--cases" "case-1, case-2"
                                 "--mode" "agraph"
                                 "--agent" "codex"
                                 "--parser-worker" "all"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-report-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:report {:id "suite"}
                 {:case-id nil
                  :case-ids ["case-1" "case-2"]
                  :out nil
                  :retriever nil
                  :parser-worker "all"
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
                                 "--min-evidence-citation-rate" "0.8"
                                 "--min-path-evidence-citation-rate" "0.6"
                                 "--min-case-file-recall-at-10" "1.0"
                                 "--min-case-mrr" "0.9"
                                 "--min-case-evidence-citation-rate" "0.7"
                                 "--min-case-path-evidence-citation-rate" "0.55"
                                 "--max-case-noise-at-20" "0.75"
                                 "--max-input-hinted-cases" "0"
                                 "--max-unsupported-ground-truth-files" "0"
                                 "--max-commandless-runs" "0"
                                 "--max-missing-predicted-file-runs" "0"
                                 "--max-warning-runs" "0"
                                 "--max-identity-mismatch-runs" "0"
                                 "--max-missing-declared-source-kind-runs" "0"
                                 "--max-missed-runs" "0"
                                 "--max-missed-but-present-in-context-runs" "0"
                                 "--max-missed-and-absent-from-context-runs" "0"
                                 "--max-ranked-outside-top-5-runs" "0"
                                 "--max-ranked-outside-top-10-runs" "0"
                                 "--max-ranked-outside-top-20-runs" "0"
                                 "--max-active-stage-ms" "120000"
                                 "--max-parser-worker-profiles" "1"
                                 "--min-measured-problem-classes" "1"
                                 "--min-measured-architecture-classes" "1"
                                 "--require-parser-worker" "all"
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
                  :parser-worker nil
                  :mode "agraph"
                  :agent-id "agraph-baseline-lexical"
                  :result-path nil
                  :command nil
                  :min-cases 4
                  :min-runs 4
                  :min-file-recall-at-10 1.0
                  :min-mrr 0.9
                  :max-noise-at-20 0.5
                  :min-evidence-citation-rate 0.8
                  :min-path-evidence-citation-rate 0.6
                  :min-case-file-recall-at-10 1.0
                  :min-case-mrr 0.9
                  :min-case-evidence-citation-rate 0.7
                  :min-case-path-evidence-citation-rate 0.55
                  :max-case-noise-at-20 0.75
                  :max-input-hinted-cases 0.0
                  :max-unsupported-ground-truth-files 0.0
                  :max-commandless-runs 0.0
                  :max-missing-predicted-file-runs 0.0
                  :max-warning-runs 0.0
                  :max-identity-mismatch-runs 0.0
                  :max-missing-declared-source-kind-runs 0.0
                  :max-missed-runs 0.0
                  :max-missed-but-present-in-context-runs 0.0
                  :max-missed-and-absent-from-context-runs 0.0
                  :max-ranked-outside-top-5-runs 0.0
                  :max-ranked-outside-top-10-runs 0.0
                  :max-ranked-outside-top-20-runs 0.0
                  :max-active-stage-ms 120000
                  :max-parser-worker-profiles 1
                  :min-measured-problem-classes 1
                  :min-measured-architecture-classes 1
                  :require-parser-worker "all"
                  :allow-missing? true
                  :allow-duplicate-runs? true}]]
               @calls))))))

(deftest bench-agent-compare-dispatches-to-benchmark-comparer
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/compare-agent-report-files! (fn [suite opts]
                                                          (swap! calls conj [:compare suite opts])
                                                          {:schema benchmark/agent-compare-schema
                                                           :suite-id (:id suite)
                                                           :status "passed"
                                                           :regressions []
                                                           :baseline {:scores {:fileRecallAt10 0.8
                                                                               :meanReciprocalRankFile 0.5}}
                                                           :candidate {:scores {:fileRecallAt10 0.9
                                                                                :meanReciprocalRankFile 0.6}}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-compare" "benchmark.edn"
                                 "--baseline-report" "before.json"
                                 "--candidate-report" "after.json"
                                 "--regression-tolerance" "0.01"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-compare-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:compare {:id "suite"}
                 {:case-id nil
                  :out nil
                  :retriever nil
                  :parser-worker nil
                  :mode nil
                  :result-path nil
                  :command nil
                  :baseline-report "before.json"
                  :candidate-report "after.json"
                  :regression-tolerance 0.01}]]
               @calls))))))























