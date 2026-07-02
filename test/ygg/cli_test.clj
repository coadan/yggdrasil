(ns ygg.cli-test
  (:require [ygg.affected :as affected]
            [ygg.agent-efficiency :as agent-efficiency]
            [ygg.agent-install :as agent-install]
            [ygg.benchmark :as benchmark]
            [ygg.benchmark-repos :as benchmark-repos]
            [ygg.cli :as cli]
            [ygg.cli-bench :as cli-bench]
            [ygg.cli-query :as cli-query]
            [ygg.cli-sync-inspect :as cli-sync-inspect]
            [ygg.cli-sync :as cli-sync]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.embedding :as embedding]
            [ygg.embedding.local :as local-embedding]
            [ygg.evidence :as evidence]
            [ygg.hook :as hook]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.plugin-package :as plugin-package]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.report :as report]
            [ygg.watch :as watch]
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

(deftest direct-clojure-mains-require-server-backed-entrypoints
  (let [cli-response (cli/direct-main-response ["query" "needle"])
        inspect-response (cli-sync-inspect/direct-main-response ["project.edn"])]
    (is (= daemon-contract/unavailable-exit (:exit cli-response)))
    (is (= "" (:out cli-response)))
    (is (str/includes? (:err cli-response)
                       "Direct ygg.cli entrypoint is disabled."))
    (is (str/includes? (:err cli-response) "ygg init"))
    (is (str/includes? (:err cli-response) "ygg start"))
    (is (str/includes? (:err cli-response) "ygg <command>"))
    (is (= daemon-contract/unavailable-exit (:exit inspect-response)))
    (is (= "" (:out inspect-response)))
    (is (str/includes? (:err inspect-response)
                       "Direct ygg.cli-sync-inspect entrypoint is disabled."))
    (is (str/includes? (:err inspect-response) "ygg init"))
    (is (str/includes? (:err inspect-response) "ygg start"))
    (is (str/includes? (:err inspect-response) "ygg sync inspect"))))

(deftest usage-shows-canonical-surface
  (let [usage (cli/usage)]
    (is (str/includes? usage "Setup:"))
    (is (str/includes? usage "Sync and maintenance:"))
    (is (str/includes? usage "Query:"))
    (is (str/includes? usage "View and report:"))
    (is (str/includes? usage "Plugins:"))
    (is (str/includes? usage "Agent integration:"))
    (is (str/includes? usage "Server integration:"))
    (is (str/includes? usage "init <repo-root>"))
    (is (str/includes? usage "--maintenance none|harness|deepseek|openrouter"))
    (is (str/includes? usage "projects list|show <project-id>|register <project.edn>|remove <project-id>"))
    (is (str/includes? usage "sync inspect [<project.edn>] [--project ID] [--json]"))
    (is (str/includes? usage "  start"))
    (is (str/includes? usage "status [--json]"))
    (is (str/includes? usage "audit-scope [<project.edn>] [--project ID]"))
    (is (str/includes? usage "sync [<project.edn>] [--project ID]"))
    (is (str/includes? usage "init <repo-root>"))
    (is (str/includes? usage "query <text>"))
    (is (str/includes? usage "affected <project.edn>"))
    (is (str/includes? usage "view overview|deps|query|systems"))
    (is (str/includes? usage "report [<project.edn>] [--project ID]"))
    (is (str/includes? usage "plugin new <dir>"))
    (is (str/includes? usage "--public-base"))
    (is (str/includes? usage "plugin validate <dir>"))
    (is (str/includes? usage "plugin diagnose <dir>"))
    (is (str/includes? usage "plugin core-check <dir>"))
    (is (str/includes? usage "plugin dry-run extractor <dir>"))
    (is (str/includes? usage "plugin dry-run report <dir>"))
    (is (str/includes? usage "plugin input extractor <dir>"))
    (is (str/includes? usage "plugin input report <dir>"))
    (is (str/includes? usage "plugin gap extractor <dir>"))
    (is (str/includes? usage "plugin gap report <dir>"))
    (is (str/includes? usage "plugin install <project.edn>"))
    (is (str/includes? usage "plugin update <project.edn> <package-id>"))
    (is (str/includes? usage "plugin list <project.edn>"))
    (is (str/includes? usage "plugin remove <project.edn> <package-id>"))
    (is (str/includes? usage "plugin registry list <registry.edn>"))
    (is (str/includes? usage "plugin registry validate <registry.edn>"))
    (is (str/includes? usage "agent install --platform codex --project"))
    (is (str/includes? usage "--skill"))
    (is (str/includes? usage "--mcp"))
    (is (str/includes? usage "--print-config"))
    (is (str/includes? usage "agent uninstall --platform codex --project"))
    (is (str/includes? usage "agent list"))
    (is (not (str/includes? usage "  install --platform codex --project")))
    (is (not (str/includes? usage "  uninstall --platform codex --project")))
    (is (not (str/includes? usage "install-agent --platform codex --project")))
    (is (str/includes? usage "mcp [--root DIR]"))
    (is (str/includes? usage "service start-at-login enable|disable|status"))
    (is (str/includes? usage "watch <project.edn>"))
    (is (str/includes? usage "hook install <project.edn>"))
    (is (str/includes? usage "bench prepare|run|report"))
    (is (str/includes? usage "bench show"))
    (is (str/includes? usage "bench agent-packet"))
    (is (str/includes? usage "bench agent-baseline"))
    (is (str/includes? usage "--provider local|openrouter|openai"))
    (is (str/includes? usage "--embedding-provider-limit N"))
    (is (str/includes? usage "--retriever auto|hybrid|lexical|semantic|local-vector|codebase-memory|graphify"))
    (is (str/includes? usage "--codebase-memory-command CMD"))
    (is (str/includes? usage "--codebase-memory-bin PATH"))
    (is (str/includes? usage "--graphify-command CMD"))
    (is (str/includes? usage "--graphify-bin CMD"))
    (is (str/includes? usage "bench agent-run"))
    (is (str/includes? usage "bench agent-rerun"))
    (is (str/includes? usage "bench agent-score"))
    (is (str/includes? usage "bench agent-score <benchmark.edn> --case ID --result result.json [--parser-worker none|java|dotnet|javascript|typescript|all]"))
    (is (str/includes? usage "bench agent-report"))
    (is (str/includes? usage "bench improve"))
    (is (str/includes? usage "bench agent-check"))
    (is (str/includes? usage "bench agent-compare"))
    (is (str/includes? usage "bench claim-pack"))
    (is (str/includes? usage "bench repos check"))
    (is (str/includes? usage "bench efficiency"))
    (is (str/includes? usage "memory add --text TEXT"))
    (is (not (str/includes? usage "memory search")))
    (is (str/includes? usage "sync work heartbeat"))
    (is (str/includes? usage "sync work auto <project.edn>"))
    (is (str/includes? usage "embed setup [--venv PATH] [--python PYTHON] [--json]"))
    (is (str/includes? usage "--cases ID,ID"))
    (is (str/includes? usage "--min-evidence-citation-rate N"))
    (is (str/includes? usage "--min-path-evidence-citation-rate N"))
    (is (str/includes? usage "--min-expected-evidence-citation-rate N"))
    (is (str/includes? usage "--min-case-evidence-citation-rate N"))
    (is (str/includes? usage "--min-case-path-evidence-citation-rate N"))
    (is (str/includes? usage "--min-case-expected-evidence-citation-rate N"))
    (is (str/includes? usage "--max-commandless-runs N"))
    (is (str/includes? usage "--max-missing-predicted-file-runs N"))
    (is (str/includes? usage "--max-warning-runs N"))
    (is (str/includes? usage "--max-identity-mismatch-runs N"))
    (is (str/includes? usage "--max-context-rank-missing-runs N"))
    (is (str/includes? usage "--max-missed-but-present-in-context-runs N"))
    (is (str/includes? usage "--max-missed-and-absent-from-context-runs N"))
    (is (str/includes? usage "--require-parser-worker none|java|dotnet|javascript|typescript|all"))
    (is (not (str/includes? usage "overlay")))))

(deftest maintenance-status-resolves-project-from-current-directory
  (let [resolve-opts (atom nil)
        status-project (atom nil)]
    (with-redefs [registry/resolve-project
                  (fn [opts]
                    (reset! resolve-opts opts)
                    {:project-id "fixture"
                     :project project-fixture
                     :source :project-ref
                     :project-ref "/repo/.ygg/project.edn"
                     :registry "/config/projects.edn"})
                  index-maintenance-worker/config-status
                  (fn [project]
                    (reset! status-project project)
                    {:project-id (:id project)
                     :configured true
                     :enabled true
                     :schedules []
                     :worker {:configured true
                              :enabled true
                              :leaseMinutes 30
                              :maxItemsPerRun 8
                              :maxFailuresPerRun 3
                              :apply {:mode :complete-only}
                              :availableExecutorCount 1
                              :executorCount 1
                              :executors []}})]
      (let [out (with-out-str
                  (cli/dispatch "maintenance" ["status"]))]
        (is (= project-fixture @status-project))
        (is (nil? (:project-id @resolve-opts)))
        (is (nil? (:config-path @resolve-opts)))
        (is (string? (:cwd @resolve-opts)))
        (is (str/includes? out "- project fixture"))
        (is (str/includes? out "- project-ref /repo/.ygg/project.edn"))
        (is (str/includes? out "- maintenance enabled"))
        (is (str/includes? out "- worker-controls lease=30m max-items=8 max-failures=3 apply=complete-only"))))))

(deftest projects-register-command-registers-existing-config
  (let [root (temp-dir "ygg-cli-projects-register")
        config-path (.getPath (io/file root "project.edn"))
        registry-path (.getPath (io/file root ".config" "projects.edn"))]
    (spit config-path
          (pr-str {:id "demo"
                   :name "Demo"
                   :repos [{:id "app"
                            :root root
                            :role :application}]}))
    (with-redefs [registry/registry-path (constantly registry-path)]
      (let [out (with-out-str
                  (cli/dispatch "projects" ["register" config-path "--json"]))
            result (read-json-output out)]
        (is (= "ygg.project.registry.register/v1" (:schema result)))
        (is (= "demo" (:project-id result)))
        (is (= "demo" (get-in result [:project :id])))
        (is (= (get-in (registry/read-registry) [:projects "demo" :config-path])
               (:config-path result)))))))

(deftest sync-work-auto-dispatches-to-index-maintenance-worker
  (let [calls (atom [])]
    (with-redefs [project/read-project (fn [path]
                                         (swap! calls conj [:project path])
                                         project-fixture)
                  index-maintenance-worker/run! (fn [project]
                                                  (swap! calls conj [:run (:id project)])
                                                  {:schema index-maintenance-worker/schema
                                                   :project-id (:id project)
                                                   :status "disabled"
                                                   :counts {:claimed 0
                                                            :completed 0
                                                            :failed 0
                                                            :validated 0}})]
      (let [result (read-json-output
                    (with-out-str
                      (cli/dispatch "sync" ["work" "auto" "project.edn" "--json"])))]
        (is (= [[:project "project.edn"]
                [:run "fixture"]]
               @calls))
        (is (= "ygg.index-maintenance-worker.run/v1" (:schema result)))
        (is (= "disabled" (:status result)))))))

(deftest embed-setup-dispatches-to-local-embedding-setup
  (let [calls (atom [])]
    (with-redefs [local-embedding/setup-venv!
                  (fn [opts]
                    (swap! calls conj opts)
                    {:provider :local
                     :venv "custom-venv"
                     :python "custom-venv/bin/python"
                     :requirements "/ygg/scripts/local-vector-requirements.txt"
                     :created? true
                     :default? false})]
      (let [parsed (read-json-output
                    (with-out-str
                      (cli/dispatch "embed" ["setup"
                                             "--venv" "custom-venv"
                                             "--python" "python3.12"
                                             "--json"])))]
        (is (= [{:venv-path "custom-venv"
                 :python "python3.12"}]
               @calls))
        (is (= {:provider "local"
                :venv "custom-venv"
                :python "custom-venv/bin/python"
                :requirements "/ygg/scripts/local-vector-requirements.txt"
                :created? true
                :default? false}
               parsed))))))

(deftest embed-command-uses-project-embedding-defaults
  (let [calls (atom [])
        project (assoc project-fixture
                       :embeddings {:provider :openrouter
                                    :model "openai/text-embedding-3-small"
                                    :request-timeout-ms 45000
                                    :max-retries 0})]
    (with-redefs [registry/resolve-project
                  (fn [opts]
                    (is (= "fixture" (:project-id opts)))
                    {:project project})
                  cli-query/provider-api-key (constantly "openrouter-key")
                  cli-query/provider-client
                  (fn [provider model opts]
                    (swap! calls conj [:client provider model opts])
                    {:provider provider
                     :model model
                     :embed-batch (fn [_inputs] [])})
                  store/with-node (fn [_path f] (f :xtdb))
                  embedding/embed-search-docs!
                  (fn [xtdb client opts]
                    (swap! calls conj [:embed
                                       xtdb
                                       (select-keys client [:provider :model])
                                       opts])
                    {:provider (:provider client)
                     :model (:model client)
                     :search-docs 10
                     :pending 0
                     :embedded 0
                     :skipped 10})]
      (let [out (with-out-str
                  (cli/dispatch "embed" ["--project" "fixture"
                                         "--limit" "25"]))]
        (is (str/includes? out "- provider openrouter"))
        (is (= [[:client
                 :openrouter
                 "openai/text-embedding-3-small"
                 {:provider :openrouter
                  :model "openai/text-embedding-3-small"
                  :request-timeout-ms 45000
                  :max-retries 0}]
                [:embed
                 :xtdb
                 {:provider :openrouter
                  :model "openai/text-embedding-3-small"}
                 {:batch-size embedding/default-batch-size
                  :limit 25
                  :project-id "fixture"
                  :repo-id nil}]]
               @calls))))))

(deftest sync-inspect-json-exposes-stable-freshness-summary
  (with-redefs [project/read-project (fn [path]
                                       (assoc project-fixture :path path))
                store/with-node (fn [_ f]
                                  (f :xtdb))
                evidence/summarize (fn [xtdb project opts]
                                     (is (= :xtdb xtdb))
                                     (is (= "fixture" (:id project)))
                                     (is (= "project.edn" (:config-path opts)))
                                     {:freshness {:status :stale
                                                  :counts {:indexed 2
                                                           :current 1
                                                           :changed 1
                                                           :missing 0
                                                           :unindexed 0}
                                                  :repos [{:repo-id "app"
                                                           :status :stale
                                                           :counts {:indexed 2
                                                                    :current 1
                                                                    :changed 1
                                                                    :missing 0
                                                                    :unindexed 0}
                                                           :git-state {:git-branch "main"
                                                                       :git-upstream "origin/main"
                                                                       :git-upstream-status :behind
                                                                       :git-upstream-current? false
                                                                       :git-ahead 0
                                                                       :git-behind 2}
                                                           :samples {:changed [{:repo-id "app"
                                                                                :path "AGENTS.md"}]}}]}
                                      :families []
                                      :counts {}
                                      :nextActions []})]
    (let [parsed (read-json-output
                  (with-out-str
                    (cli/dispatch "sync" ["inspect" "project.edn" "--json"])))]
      (is (= {:indexed 2
              :current 1
              :changed 1
              :missing 0
              :unindexed 0}
             (:freshnessCounts parsed)))
      (is (= [{:id "app"
               :status "stale"
               :counts {:indexed 2
                        :current 1
                        :changed 1
                        :missing 0
                        :unindexed 0}
               :gitState {:git-branch "main"
                          :git-upstream "origin/main"
                          :git-upstream-status "behind"
                          :git-upstream-current? false
                          :git-ahead 0
                          :git-behind 2}
               :samples {:changed [{:repo-id "app"
                                    :path "AGENTS.md"}]}}]
             (:repoFreshness parsed))))))

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
                                                       :plugins {:total 2
                                                                 :extractor 1
                                                                 :report 1}
                                                       :benchmark-status :unbenchmarked
                                                       :claim-authority claim-authority
                                                       :manifest-fingerprint "sha256:abc123"
                                                       :source {:url source
                                                                :rev "abc123"}
                                                       :warnings []}
                                             :entry {:manifest "ygg.plugin.edn"
                                                     :manifest-fingerprint "sha256:abc123"
                                                     :path "/tmp/pkg"}
                                             :force? (:force? opts)})]
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str
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
                                   "--force"])))]
        (is (str/includes? out "# Plugin Installed"))
        (is (str/includes? (str err) "# Plugin Progress"))
        (is (str/includes? (str err) "- install start git@example.test:org/pkg.git"))
        (is (str/includes? (str err) "- install complete package=pkg"))
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
                                                      :plugins {:total 2
                                                                :extractor 1
                                                                :report 1}
                                                      :benchmark-status :unbenchmarked
                                                      :claim-authority claim-authority
                                                      :manifest-fingerprint "sha256:def456"
                                                      :source {:url "git@example.test:org/pkg.git"
                                                               :rev "newrev"}
                                                      :warnings []}
                                            :entry {:manifest "ygg.plugin.edn"
                                                    :manifest-fingerprint "sha256:def456"
                                                    :path "/tmp/pkg"
                                                    :source {:rev "newrev"}}})]
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str
                    (cli/dispatch "plugin"
                                  ["update"
                                   "project.edn"
                                   "pkg"
                                   "--ref"
                                   "v0.2.0"
                                   "--subdir"
                                   "packages/pkg"
                                   "--cache-dir"
                                   ".cache/plugins"])))]
        (is (str/includes? out "# Plugin Updated"))
        (is (str/includes? (str err) "# Plugin Progress"))
        (is (str/includes? (str err) "- update start pkg"))
        (is (str/includes? (str err) "- update complete package=pkg rev=newrev"))
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

(deftest plugin-list-dispatches-with-kind-and-query-filters
  (let [calls (atom [])]
    (with-redefs [plugin-package/list-installed
                  (fn [config-path opts]
                    (swap! calls conj [config-path opts])
                    {:schema plugin-package/list-schema
                     :project-id "fixture"
                     :filters opts
                     :counts {:packages 2
                              :matched 1
                              :extractor 1
                              :report 1}
                     :packages [{:id "datastar-hiccup"
                                 :version "0.1.0"
                                 :plugins {:total 1
                                           :extractor 1
                                           :report 0}
                                 :benchmark-status :unbenchmarked
                                 :claim-authority {:status :non-authoritative
                                                   :public-claims? false
                                                   :blockers []}
                                 :warnings []}]})]
      (let [out (with-out-str
                  (cli/dispatch "plugin"
                                ["list"
                                 "project.edn"
                                 "--kind"
                                 "extractor"
                                 "--query"
                                 "datastar"]))]
        (is (str/includes? out "# Plugins"))
        (is (str/includes? out "- filters kind=extractor query=datastar"))
        (is (str/includes? out "- packages 2"))
        (is (str/includes? out "- matched 1"))
        (is (str/includes? out "- extractor 1"))
        (is (str/includes? out "- report 1"))
        (is (str/includes? out "datastar-hiccup"))
        (is (= [["project.edn" {:kind "extractor"
                                :query "datastar"}]]
               @calls))))))

(deftest plugin-list-renders-no-match-next-actions
  (with-redefs [plugin-package/list-installed
                (fn [_config-path opts]
                  {:schema plugin-package/list-schema
                   :project-id "fixture"
                   :filters opts
                   :counts {:packages 1
                            :matched 0
                            :extractor 0
                            :report 1}
                   :next-actions [{:id :search-registry
                                   :reason "No installed plugin package matched the filters."
                                   :command "bb plugin registry list '<registry.edn>' --kind extractor --query htmx"}
                                  {:id :scaffold-extractor
                                   :reason "Create a local extractor package for the missing file family or architecture evidence gap."
                                   :command "bb plugin new '<package-dir>' --extractor --file-kind '<file-kind>' --path-glob '<glob>' --fixture '<file>'"}
                                  {:id :author-extractor-gap
                                   :reason "Generate the extractor authoring packet after scaffolding or selecting a package."
                                   :command "bb plugin gap extractor '<package-dir>' '<repo-root>' '<file>' --json"}]
                   :packages []})]
    (let [out (with-out-str
                (cli/dispatch "plugin"
                              ["list"
                               "project.edn"
                               "--kind"
                               "extractor"
                               "--query"
                               "htmx"]))]
      (is (str/includes? out "- matched 0"))
      (is (str/includes? out "- next-actions"))
      (is (str/includes? out "search-registry bb plugin registry list"))
      (is (str/includes? out "scaffold-extractor bb plugin new"))
      (is (str/includes? out "author-extractor-gap bb plugin gap extractor"))
      (is (str/includes? out "No installed plugin package matched the filters.")))))

(deftest plugin-registry-install-dispatches-to-registry-installer
  (let [calls (atom [])]
    (with-redefs [plugin-package/registry-install!
                  (fn [registry-path config-path package-id opts]
                    (swap! calls conj [registry-path config-path package-id opts])
                    {:schema plugin-package/registry-install-schema
                     :registry {:id "official"}
                     :registry-path registry-path
                     :package-id package-id
                     :registry-package {:id package-id
                                        :status :passed
                                        :registry-entry
                                        {:kinds [:extractor]
                                         :maintainers [{:name "Maintainer"}]
                                         :support {:status :experimental}
                                         :trust {:code-reviewed? false}}
                                        :package-summary
                                        {:id package-id
                                         :version "0.1.0"
                                         :visibility :public
                                         :license {:spdx "MIT"}
                                         :scope {:kind :base}
                                         :benchmark-status :unbenchmarked}
                                        :install
                                        {:command "bb plugin install '<project.edn>' https://github.com/org/plugins.git --ref v0.1.0"}}
                     :install {:schema plugin-package/install-schema
                               :project-id "fixture"
                               :package {:id package-id
                                         :version "0.1.0"
                                         :plugins {:total 1
                                                   :extractor 1
                                                   :report 0}
                                         :benchmark-status :unbenchmarked
                                         :manifest-fingerprint "sha256:abc123"
                                         :warnings []}
                               :entry {:manifest "ygg.plugin.edn"
                                       :manifest-fingerprint "sha256:abc123"
                                       :path "/tmp/pkg"
                                       :source {:rev "abc123"}}
                               :force? (:force? opts)}})]
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str
                    (cli/dispatch "plugin"
                                  ["registry"
                                   "install"
                                   "registry.edn"
                                   "project.edn"
                                   "pkg"
                                   "--cache-dir"
                                   ".cache/plugins"
                                   "--force"])))]
        (is (str/includes? out "# Plugin Registry Install"))
        (is (str/includes? out "- registry registry.edn"))
        (is (str/includes? out "- package pkg"))
        (is (str/includes? out "registry-command bb plugin install"))
        (is (str/includes? out "# Plugin Installed"))
        (is (str/includes? (str err) "# Plugin Progress"))
        (is (str/includes? (str err) "- registry install start pkg"))
        (is (str/includes? (str err) "- registry install complete package=pkg rev=abc123"))
        (is (= [["registry.edn"
                 "project.edn"
                 "pkg"
                 {:cache-root ".cache/plugins"
                  :force? true}]]
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
                               :benchmark-cases {:artifacts 1
                                                 :case-ids ["core-ready-architecture"]
                                                 :problem-classes ["architecture-understanding"]
                                                 :improvement-metrics ["file-recall-at-5"]}
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
        (is (str/includes?
             out
             "- benchmark-cases artifacts=1 case-ids=core-ready-architecture problem-classes=architecture-understanding improvement-metrics=file-recall-at-5"))
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
                                         :manifest (str dir "/ygg.plugin.edn")
                                         :file-kind (some-> (:file-kind opts) keyword)
                                         :fixture-path (:fixture-path opts)
                                         :visibility (if (:public-base? opts)
                                                       :public
                                                       :private)
                                         :scope {:kind (if (:public-base? opts)
                                                         :base
                                                         :project-local)}
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
                                                   :plugins [{:kind :extractor}
                                                             {:kind :report}]
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
                                                      :transformed-counts {:nodes 2}
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
                     :inputs [{:schema "ygg.extractor-plugin.input/v1"
                               :plugin {:id "demo-extractor"}}]})
                  plugin-package/sample-report-inputs
                  (fn [dir opts]
                    (swap! calls conj [:input-report dir opts])
                    {:schema plugin-package/input-sample-schema
                     :kind :report
                     :status :passed
                     :package {:id "demo"
                               :version "0.1.0"
                               :benchmark-status :unbenchmarked
                               :claim-authority claim-authority
                               :scope {:kind :project-local}}
                     :plugins [{:id "demo-report"}]
                     :selection {:kind :report
                                 :requested-plugin-id "demo-report"
                                 :available ["demo-report" "other-report"]
                                 :selected ["demo-report"]
                                 :skipped ["other-report"]
                                 :counts {:available 2
                                          :selected 1
                                          :skipped 1}}
                     :counts {:inputs 1}
                     :diagnostics []
                     :inputs [{:schema "ygg.report-plugin.input/v1"
                               :plugin {:id "demo-report"}}]})
                  plugin-package/extractor-gap-packet
                  (fn [dir root file opts]
                    (swap! calls conj [:gap dir root file opts])
                    {:schema plugin-package/extractor-gap-schema
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
                     :inputs [{:schema "ygg.extractor-plugin.input/v1"
                               :plugin {:id "demo-extractor"}}]
                     :output-contract {:schema "ygg.extractor-plugin.result/v1"
                                       :core-input-buckets [:nodes
                                                            :edges
                                                            :chunks
                                                            :fileFacts
                                                            :diagnostics]
                                       :plugin-modes [{:name :enhance}
                                                      {:name :override}
                                                      {:name :scan}]
                                       :buckets [{:name :nodes}
                                                 {:name :edges}
                                                 {:name :fileFacts}
                                                 {:name :chunks}
                                                 {:name :diagnostics}]
                                       :dependency-aliases [{:json :packageName}
                                                            {:json :versionRange}
                                                            {:json :resolvedVersion}
                                                            {:json :dependencyScope}
                                                            {:json :importNames}
                                                            {:json :importName}
                                                            {:json :importKind}
                                                            {:json :resolutionSource}]}
                     :proof {:local-checks [{:id :validate
                                             :command "bb plugin validate .dev/plugins/demo"}
                                            {:id :dry-run
                                             :command "bb plugin dry-run extractor .dev/plugins/demo . src/page.clj --plugin demo-extractor --json"}]}})
                  plugin-package/report-gap-packet
                  (fn [dir opts]
                    (swap! calls conj [:gap-report dir opts])
                    {:schema plugin-package/report-gap-schema
                     :kind :report
                     :status :passed
                     :package {:id "demo"
                               :version "0.1.0"
                               :benchmark-status :unbenchmarked
                               :claim-authority claim-authority
                               :scope {:kind :project-local}}
                     :plugins [{:id "demo-report"}]
                     :selection {:kind :report
                                 :requested-plugin-id "demo-report"
                                 :available ["demo-report" "other-report"]
                                 :selected ["demo-report"]
                                 :skipped ["other-report"]
                                 :counts {:available 2
                                          :selected 1
                                          :skipped 1}}
                     :counts {:inputs 1}
                     :diagnostics []
                     :inputs [{:schema "ygg.report-plugin.input/v1"
                               :plugin {:id "demo-report"}}]
                     :output-contract {:schema "ygg.report-plugin.result/v1"
                                       :buckets [{:name :panels}
                                                 {:name :diagnostics}
                                                 {:name :artifacts}]}
                     :proof {:local-checks [{:id :validate
                                             :command "bb plugin validate .dev/plugins/demo"}
                                            {:id :dry-run
                                             :command "bb plugin dry-run report .dev/plugins/demo --plugin demo-report --json"}]}})
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
                                                                  :registry-entry {:id "demo"
                                                                                   :kinds [:extractor :report]
                                                                                   :maintainers [{:name "Demo Maintainer"}]
                                                                                   :support {:status :experimental}
                                                                                   :trust {:code-reviewed? false}
                                                                                   :source "https://github.com/org/demo.git"
                                                                                   :ref "v0.1.0"}
                                                                  :package-summary {:version "0.1.0"
                                                                                    :visibility :public
                                                                                    :license {:spdx "MIT"}
                                                                                    :scope {:kind :base}
                                                                                    :benchmark-status :unbenchmarked
                                                                                    :benchmark-cases {:artifacts 1
                                                                                                      :case-ids ["demo-architecture"]
                                                                                                      :problem-classes ["architecture-understanding"]
                                                                                                      :improvement-metrics ["file-recall-at-5"]}
                                                                                    :diagnostic-counts {:total 1
                                                                                                        :errors 0
                                                                                                        :warnings 1}}
                                                                  :install {:command "bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"}
                                                                  :diagnosis {:package {:claim-authority claim-authority}}
                                                                  :errors []}]})
                  plugin-package/list-registry (fn [path opts]
                                                 (swap! calls conj [:registry-list path opts])
                                                 {:schema plugin-package/registry-list-schema
                                                  :status :passed
                                                  :path path
                                                  :filters opts
                                                  :counts {:packages 1
                                                           :matched 1
                                                           :listed 1
                                                           :invalid 0
                                                           :installable 1}
                                                  :errors []
                                                  :packages [{:id "demo"
                                                              :status :listed
                                                              :registry-entry {:id "demo"
                                                                               :description "Demo plugin"
                                                                               :kinds [:extractor :report]
                                                                               :maintainers [{:name "Demo Maintainer"}]
                                                                               :support {:status :experimental}
                                                                               :trust {:code-reviewed? false}
                                                                               :source "https://github.com/org/demo.git"
                                                                               :ref "v0.1.0"}
                                                              :install {:command "bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"}
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
        (is (str/includes? new-out "- fixture fixtures/sample.html"))
        (is (str/includes? new-out "- visibility private"))
        (is (str/includes? new-out "- scope project-local")))
      (let [public-base-out (with-out-str
                              (cli/dispatch "plugin"
                                            ["new"
                                             ".dev/plugins/public-base"
                                             "--id"
                                             "public-base"
                                             "--extractor"
                                             "--public-base"]))]
        (is (str/includes? public-base-out "- visibility public"))
        (is (str/includes? public-base-out "- scope base")))
      (let [progress-err (java.io.StringWriter.)
            validate-out (with-out-str
                           (cli/dispatch "plugin" ["validate" ".dev/plugins/demo"]))
            diagnose-out (with-out-str
                           (cli/dispatch "plugin" ["diagnose" ".dev/plugins/demo"]))
            extractor-out (binding [*err* progress-err]
                            (with-out-str
                              (cli/dispatch "plugin"
                                            ["dry-run"
                                             "extractor"
                                             ".dev/plugins/demo"
                                             "."
                                             "src/page.clj"
                                             "--plugin"
                                             "demo-extractor"])))
            input-out (with-out-str
                        (cli/dispatch "plugin"
                                      ["input"
                                       "extractor"
                                       ".dev/plugins/demo"
                                       "."
                                       "src/page.clj"
                                       "--plugin"
                                       "demo-extractor"]))
            gap-out (with-out-str
                      (cli/dispatch "plugin"
                                    ["gap"
                                     "extractor"
                                     ".dev/plugins/demo"
                                     "."
                                     "src/page.clj"
                                     "--plugin"
                                     "demo-extractor"]))
            report-out (binding [*err* progress-err]
                         (with-out-str
                           (cli/dispatch "plugin"
                                         ["dry-run"
                                          "report"
                                          ".dev/plugins/demo"
                                          "--plugin"
                                          "demo-report"])))
            report-input-out (with-out-str
                               (cli/dispatch "plugin"
                                             ["input"
                                              "report"
                                              ".dev/plugins/demo"
                                              "--plugin"
                                              "demo-report"]))
            report-gap-out (with-out-str
                             (cli/dispatch "plugin"
                                           ["gap"
                                            "report"
                                            ".dev/plugins/demo"
                                            "--plugin"
                                            "demo-report"]))]
        (is (str/includes? validate-out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes? diagnose-out "claim-blockers project-local,unbenchmarked"))
        (is (str/includes? extractor-out "benchmark=unbenchmarked"))
        (is (str/includes? (str progress-err) "# Plugin Progress"))
        (is (str/includes? (str progress-err) "- dry-run start extractor .dev/plugins/demo src/page.clj"))
        (is (str/includes? (str progress-err) "- dry-run complete status=passed plugins=1"))
        (is (str/includes? (str progress-err) "- dry-run start report .dev/plugins/demo"))
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
        (is (str/includes? gap-out "# Plugin Extractor Gap"))
        (is (str/includes? gap-out "- output-schema ygg.extractor-plugin.result/v1"))
        (is (str/includes? gap-out "- output-buckets nodes,edges,fileFacts,chunks,diagnostics"))
        (is (str/includes? gap-out "- core-input-buckets nodes,edges,chunks,fileFacts,diagnostics"))
        (is (str/includes? gap-out "- plugin-modes enhance,override,scan"))
        (is (str/includes? gap-out "- dependency-aliases packageName,versionRange,resolvedVersion,dependencyScope,importNames,importName,importKind,resolutionSource"))
        (is (str/includes? gap-out "- dry-run bb plugin dry-run extractor"))
        (is (str/includes? report-out "benchmark=unbenchmarked"))
        (is (str/includes? report-out "scope=project-local"))
        (is (str/includes? report-out "claim-authority status=non-authoritative public-claims=false"))
        (is (str/includes?
             report-out
             "- selection available=demo-report,other-report selected=demo-report skipped=other-report"))
        (is (str/includes? report-out "- requested-plugin demo-report"))
        (is (str/includes? report-input-out "# Plugin Input Sample"))
        (is (str/includes? report-input-out "- inputs 1"))
        (is (str/includes?
             report-input-out
             "- selection available=demo-report,other-report selected=demo-report skipped=other-report"))
        (is (str/includes? report-gap-out "# Plugin Report Gap"))
        (is (str/includes? report-gap-out "- output-schema ygg.report-plugin.result/v1"))
        (is (str/includes? report-gap-out "- output-buckets panels,diagnostics,artifacts"))
        (is (str/includes? report-gap-out "- dry-run bb plugin dry-run report")))
      (let [remove-out (with-out-str
                         (cli/dispatch "plugin"
                                       ["remove"
                                        "project.edn"
                                        "demo"]))]
        (is (str/includes? remove-out "# Plugin Removed"))
        (is (str/includes? remove-out "- package demo")))
      (let [registry-err (java.io.StringWriter.)
            registry-out (binding [*err* registry-err]
                           (with-out-str
                             (cli/dispatch "plugin"
                                           ["registry"
                                            "validate"
                                            ".dev/plugins/registry.edn"])))]
        (is (str/includes? registry-out
                           "install bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"))
        (is (str/includes? (str registry-err) "# Plugin Progress"))
        (is (str/includes? (str registry-err) "- registry validate start .dev/plugins/registry.edn"))
        (is (str/includes? (str registry-err) "- registry validate complete status=passed packages=1 failed=0"))
        (is (str/includes? registry-out "kinds extractor,report"))
        (is (str/includes? registry-out "support experimental"))
        (is (str/includes? registry-out "code-reviewed false"))
        (is (str/includes? registry-out "maintainers Demo Maintainer"))
        (is (str/includes? registry-out "package version=0.1.0 visibility=public benchmark=unbenchmarked scope=base"))
        (is (str/includes? registry-out "license MIT"))
        (is (str/includes?
             registry-out
             "benchmark-cases artifacts=1 case-ids=demo-architecture problem-classes=architecture-understanding improvement-metrics=file-recall-at-5"))
        (is (str/includes? registry-out "diagnostics {:total 1, :errors 0, :warnings 1}"))
        (is (str/includes? registry-out "- non-authoritative 1"))
        (is (str/includes? registry-out
                           "claim-blockers project-local,unbenchmarked")))
      (let [registry-list-err (java.io.StringWriter.)
            registry-list-out (binding [*err* registry-list-err]
                                (with-out-str
                                  (cli/dispatch "plugin"
                                                ["registry"
                                                 "list"
                                                 ".dev/plugins/registry.edn"
                                                 "--kind"
                                                 "extractor"
                                                 "--query"
                                                 "demo"])))]
        (is (str/includes? registry-list-out "# Plugin Registry"))
        (is (str/includes? registry-list-out "- filters kind=extractor query=demo"))
        (is (str/includes? registry-list-out "- matched 1"))
        (is (str/includes? registry-list-out "- installable 1"))
        (is (str/includes? registry-list-out "description Demo plugin"))
        (is (str/includes? registry-list-out
                           "install bb plugin install '<project.edn>' https://github.com/org/demo.git --ref v0.1.0"))
        (is (str/includes? (str registry-list-err) "- registry list start .dev/plugins/registry.edn"))
        (is (str/includes? (str registry-list-err) "- registry list complete status=passed matched=1 installable=1")))
      (is (= [[:new ".dev/plugins/demo" {:id "demo"
                                         :extractor? false
                                         :report? false
                                         :public-base? false
                                         :force? true}]
              [:new ".dev/plugins/htmx" {:id "htmx"
                                         :extractor? true
                                         :report? false
                                         :public-base? false
                                         :force? false
                                         :file-kind "htmx"
                                         :path-globs "templates/*.html"
                                         :scan-globs "templates/*.html"
                                         :fixture-path "fixtures/sample.html"}]
              [:new ".dev/plugins/public-base" {:id "public-base"
                                                :extractor? true
                                                :report? false
                                                :public-base? true
                                                :force? false}]
              [:validate ".dev/plugins/demo"]
              [:diagnose ".dev/plugins/demo"]
              [:dry-run ".dev/plugins/demo" "." "src/page.clj" {:plugin-id "demo-extractor"}]
              [:input ".dev/plugins/demo" "." "src/page.clj" {:plugin-id "demo-extractor"}]
              [:gap ".dev/plugins/demo" "." "src/page.clj" {:plugin-id "demo-extractor"}]
              [:dry-run-report ".dev/plugins/demo" {:plugin-id "demo-report"}]
              [:input-report ".dev/plugins/demo" {:plugin-id "demo-report"}]
              [:gap-report ".dev/plugins/demo" {:plugin-id "demo-report"}]
              [:remove "project.edn" "demo"]
              [:registry ".dev/plugins/registry.edn"]
              [:registry-list ".dev/plugins/registry.edn" {:kind "extractor"
                                                           :query "demo"}]]
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
                     :transformed-counts {:nodes 0}
                     :diagnostics [diagnostic]
                     :rows {:diagnostics [diagnostic]}})]
      (let [error (atom nil)
            err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str
                    (try
                      (cli/dispatch "plugin"
                                    ["dry-run"
                                     "extractor"
                                     ".dev/plugins/demo"
                                     "."
                                     "src/missing.clj"])
                      (catch clojure.lang.ExceptionInfo e
                        (reset! error e)))))]
        (is (str/includes? out "- status failed"))
        (is (str/includes? (str err) "- dry-run start extractor .dev/plugins/demo src/missing.clj"))
        (is (str/includes? (str err) "- dry-run complete status=failed plugins=0"))
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
              (cli-bench/print-benchmark-summary
               {:schema benchmark/agent-baselines-schema
                :suite-id "suite"
                :completed 1
                :skipped 0
                :baselines [{:case-id "case-1"
                             :repo-id "repo"
                             :agentId "ygg-baseline-lexical"
                             :scores {:fileRecallAt10 0.5
                                      :meanReciprocalRankFile 1.0}}]}))]
    (is (str/includes? out "- skipped 0"))
    (is (str/includes? out "case-1 repo agent ygg-baseline-lexical status ran recall@10 0.50 mrr 1.00"))
    (is (not (str/includes? out "- file-recall@10 0.00")))))

(deftest maintenance-summary-prints-decision-breakdown
  (let [out (with-out-str
              (cli-sync/print-maintenance-report
               (index-maintenance/from-graph-report
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
                 :decision-queue []})))]
    (is (str/includes? out "# Index Maintain"))
    (is (str/includes? out "- decision-summary severity high:1,low:1 kind ambiguous-high-salience-edge:1,unclustered-system:1 action accept-system:1,set-edge-visibility:2"))))

(deftest benchmark-summary-prints-parser-worker-profiles
  (let [out (with-out-str
              (cli-bench/print-benchmark-summary
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
                     (cli-bench/print-benchmark-summary
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
                    (cli-bench/print-benchmark-summary
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
                               :timings {:elapsedMs 12000
                                         :warmElapsedMs 3000
                                         :amortizedSetupElapsedMs 9000
                                         :agentReadyElapsedMs 250
                                         :runningCases 0
                                         :failedCases 0
                                         :stageTiming {:classes [{:stage "index-project"
                                                                  :class "graph-setup"}
                                                                 {:stage "embed-search-docs"
                                                                  :class "embedding"}
                                                                 {:stage "context-packet"
                                                                  :class "agent-preparation"}]}
                                         :stageElapsedMs [{:stage "context-packet"
                                                           :elapsedMs 4000}
                                                          {:stage "embed-search-docs"
                                                           :elapsedMs 6000}
                                                          {:stage "index-project"
                                                           :elapsedMs 8000}]
                                         :slowestCases [{:case-id "case-2"
                                                         :status "completed"
                                                         :elapsedMs 7000}]}
                               :agentDiagnostics diagnostics}
                      :timings {:elapsedMs 12000
                                :warmElapsedMs 3000
                                :amortizedSetupElapsedMs 9000
                                :agentReadyElapsedMs 250
                                :runningCases 0
                                :failedCases 0
                                :stageTiming {:classes [{:stage "index-project"
                                                         :class "graph-setup"}
                                                        {:stage "embed-search-docs"
                                                         :class "embedding"}
                                                        {:stage "context-packet"
                                                         :class "agent-preparation"}]}
                                :stageElapsedMs [{:stage "context-packet"
                                                  :elapsedMs 4000}
                                                 {:stage "embed-search-docs"
                                                  :elapsedMs 6000}
                                                 {:stage "index-project"
                                                  :elapsedMs 8000}]
                                :slowestCases [{:case-id "case-2"
                                                :status "completed"
                                                :elapsedMs 7000}]}
                      :failures []}))]
    (doseq [out [report-out check-out]]
      (is (str/includes? out "- missing-predicted-file-runs 1 files 2 cases case-1"))
      (is (str/includes? out "- commandless-runs 1 cases case-2"))
      (is (str/includes? out "- warning-runs 1 cases case-3")))
    (is (str/includes? check-out "- timing-ms 12000 warm 3000 amortized-setup 9000 agent-ready 250 running 0 failed 0"))
    (is (str/includes? check-out "- stage-timing index-project class graph-setup elapsed 8000 ms"))
    (is (str/includes? check-out "- stage-timing embed-search-docs class embedding elapsed 6000 ms"))
    (is (str/includes? check-out "- stage-timing context-packet class agent-preparation elapsed 4000 ms"))
    (is (str/includes? check-out "- slowest case-2 completed 7000 ms"))))

(deftest benchmark-summary-prints-artifact-diagnostics
  (let [diagnostics {:unverifiedScoreRuns 2
                     :unverifiedScoreCaseIds ["case-1" "case-2"]
                     :obsoleteScoreSchemaRuns 1
                     :obsoleteScoreSchemaCaseIds ["case-1"]
                     :obsoleteScoreSchemas ["ygg.benchmark.agent-score/v1"]
                     :expectedScoreSchema benchmark/agent-score-schema
                     :obsoleteAgentResultSchemaRuns 1
                     :obsoleteAgentResultSchemaCaseIds ["case-1"]
                     :obsoleteAgentResultSchemas ["ygg.benchmark.agent-result/v1"]
                     :expectedAgentResultSchema benchmark/agent-result-schema
                     :staleScoreRuns 1
                     :staleScoreCaseIds ["case-2"]}
        report-out (with-out-str
                     (cli-bench/print-benchmark-summary
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
                    (cli-bench/print-benchmark-summary
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
      (is (str/includes? out "- obsolete-score-schema-runs 1 schemas ygg.benchmark.agent-score/v1 expected "))
      (is (str/includes? out "- obsolete-agent-result-schema-runs 1 schemas ygg.benchmark.agent-result/v1 expected "))
      (is (str/includes? out " cases case-1"))
      (is (str/includes? out "- stale-score-runs 1 cases case-2")))))

(deftest benchmark-summary-prints-claim-readiness
  (let [claim-readiness {:status "not-supported"
                         :broadArchitectureClaimSupported false
                         :measuredProblemClassTags ["problem-architecture"]
                         :measuredArchitectureClassTags []
                         :warnings ["No measured architecture-class groups."]}
        report-out (with-out-str
                     (cli-bench/print-benchmark-summary
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
                    (cli-bench/print-benchmark-summary
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

(deftest benchmark-summary-prints-benchmark-preflight
  (let [preflight {:status "failed"
                   :requiredForClaim true
                   :blockedRuns 2
                   :blockedCaseIds ["case-1" "case-2"]
                   :checks [{:check "index"
                             :status "passed"
                             :passedRuns 2
                             :failedRuns 0
                             :notRunRuns 0}
                            {:check "syncCheck"
                             :status "failed"
                             :passedRuns 0
                             :failedRuns 1
                             :failedCaseIds ["case-1"]
                             :notRunRuns 1
                             :notRunCaseIds ["case-2"]}]}
        report-out (with-out-str
                     (cli-bench/print-benchmark-summary
                      {:schema benchmark/agent-report-schema
                       :suite-id "suite"
                       :cases 2
                       :completed 2
                       :runs 2
                       :scores {:fileRecallAt10 0.5
                                :meanReciprocalRankFile 0.25
                                :evidenceCitationRate 0.75}
                       :benchmarkPreflightDiagnostics preflight}))
        check-out (with-out-str
                    (cli-bench/print-benchmark-summary
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
                               :benchmarkPreflightDiagnostics preflight}
                      :failures []}))]
    (doseq [out [report-out check-out]]
      (is (str/includes? out "- benchmark-preflight failed blocked 2 cases case-1,case-2"))
      (is (str/includes? out "- benchmark-preflight-check syncCheck failed failed 1 not-run 1 cases case-1,case-2")))))

(deftest benchmark-summary-prints-compare-comparability
  (let [out (with-out-str
              (cli-bench/print-benchmark-summary
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
             :hooks? true
             :skill? true
             :mcp? true}]
           (:platforms parsed)))))

(deftest agent-install-command-writes-codex-project-guidance
  (let [root (temp-dir "ygg-agent-install-cli")
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
        (is (str/includes? (slurp agents) "Yggdrasil Agent Workflow")))
      (finally
        (System/setProperty "user.dir" old-dir)))))

(deftest agent-install-print-config-does-not-write-files
  (let [root (temp-dir "ygg-agent-print-config-cli")
        old-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" root)
      (let [out (with-out-str
                  (cli/dispatch "agent" ["install"
                                         "--platform" "codex"
                                         "--project"
                                         "--hooks"
                                         "--skill"
                                         "--mcp"
                                         "--print-config"]))
            parsed (read-json-output out)
            agents (io/file root "AGENTS.md")
            hooks (io/file root ".codex" "hooks.json")
            skill (io/file root ".codex" "skills" "ygg" "SKILL.md")]
        (is (= agent-install/schema (:schema parsed)))
        (is (= "print-config" (:action parsed)))
        (is (= (.getPath agents) (get-in parsed [:instructions :path])))
        (is (= (.getPath hooks) (get-in parsed [:hooks :path])))
        (is (= (.getPath skill) (get-in parsed [:skill :path])))
        (is (= {:command "ygg-mcp --config project.edn"} (:mcp parsed)))
        (is (str/includes? (get-in parsed [:instructions :content])
                           "Yggdrasil Agent Workflow"))
        (is (str/includes? (get-in parsed [:hooks :content])
                           "Yggdrasil may have relevant project context"))
        (is (str/includes? (get-in parsed [:skill :content])
                           "name: ygg"))
        (is (not (.exists agents)))
        (is (not (.exists hooks)))
        (is (not (.exists skill))))
      (finally
        (System/setProperty "user.dir" old-dir)))))

(deftest agent-uninstall-command-removes-codex-project-guidance
  (let [root (temp-dir "ygg-uninstall-cli")
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
  (let [root (temp-dir "ygg-agent-install")
        agents (io/file root "AGENTS.md")]
    (spit agents "# Existing\n\nKeep this line.\n")
    (agent-install/install! "codex" {:root root
                                     :project? true})
    (let [first-content (slurp agents)]
      (agent-install/install! "codex" {:root root
                                       :project? true})
      (is (= first-content (slurp agents)))
      (is (str/includes? first-content "Keep this line."))
      (is (str/includes? first-content "ygg sync inspect <project.edn> --json"))
      (is (str/includes? first-content "ygg audit-scope <project.edn> --json"))
      (is (str/includes? first-content "`audit-scope --json` summarizes core evidence"))
      (is (str/includes? first-content "graph-basis freshness, `evidence.families`"))
      (is (str/includes? first-content "When coverage, sync inspect, or evidence readiness points at skipped or unsupported"))
      (is (str/includes? first-content "bb plugin registry list <registry.edn> --kind extractor"))
      (is (str/includes? first-content "bb plugin gap extractor <package-dir> <repo-root> <file> --json"))
      (is (str/includes? first-content "bb plugin dry-run extractor <package-dir> <repo-root> <file> --json"))
      (is (str/includes? first-content "Extractor plugins run after core extraction"))
      (is (str/includes? first-content "Report plugins use the same package model"))
      (is (str/includes? first-content "unbenchmarked or project-local plugin output as useful review evidence"))
      (is (str/includes? first-content "base-scoped, FOSS/non-commercial packages with benchmark artifacts"))
      (is (str/includes? first-content "ygg query \"<question>\" --project <project-id> --json"))
      (is (str/includes? first-content "ygg sync <project.edn> --check --enqueue"))
      (is (str/includes? first-content "ygg sync work list --project <project-id> --status ready"))
      (is (str/includes? first-content "ygg sync work show <work-id>"))
      (is (str/includes? first-content "ygg sync work heartbeat <work-id> --agent codex --lease-minutes 30"))
      (is (str/includes? first-content "XTDB-backed correction facts"))
      (is (str/includes? first-content "ygg-mcp --config project.edn"))
      (is (str/includes? first-content "`ygg_query`"))
      (is (str/includes? first-content "`ygg_node`"))
      (is (str/includes? first-content "`ygg_status`"))
      (is (str/includes? first-content "`ygg_systems`"))
      (is (str/includes? first-content "--tools default,sync,work"))
      (is (str/includes? first-content "YGG_MCP_TOOLS=all"))
      (is (str/includes? first-content "Use `ygg_query` as the primary context packet"))
      (is (str/includes? first-content "graph-basis freshness, `evidence.planes`"))
      (is (str/includes? first-content "relationships, graph facts, architecture evidence, and drilldowns"))
      (is (str/includes? first-content "Treat returned snippets as already-read source context"))
      (is (str/includes? first-content "nearby mechanical edges before broad grep/read loops"))
      (is (str/includes? first-content "Use queued review packets"))
      (is (str/includes? first-content "Do not infer architecture from names")))))

(deftest agent-install-codex-project-hooks-are-valid-json
  (let [root (temp-dir "ygg-agent-install-hooks")
        result (agent-install/install! "codex" {:root root
                                                :project? true
                                                :hooks? true})
        hooks-file (io/file root ".codex" "hooks.json")
        parsed (json/read-json (slurp hooks-file) :key-fn keyword)]
    (is (= (.getPath hooks-file) (:hooks result)))
    (is (= "Bash" (get-in parsed [:hooks 0 :matcher])))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "ygg sync inspect <project.edn> --json"))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "evidence.families"))
    (is (str/includes? (get-in parsed [:hooks 0 :hooks 0 :command])
                       "ygg query"))))

(deftest agent-install-does-not-overwrite-different-hook-without-force
  (let [root (temp-dir "ygg-agent-install-hook-conflict")
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
    (is (str/includes? (slurp hooks-file) "Yggdrasil may have relevant project context"))))

(deftest agent-uninstall-removes-only-owned-section
  (let [root (temp-dir "ygg-agent-uninstall")
        agents (io/file root "AGENTS.md")]
    (spit agents "# Existing\n\nKeep this line.\n")
    (agent-install/install! "codex" {:root root
                                     :project? true})
    (let [result (agent-install/uninstall! "codex" {:root root
                                                    :project? true})
          content (slurp agents)]
      (is (= true (get-in result [:removed :instructions])))
      (is (str/includes? content "Keep this line."))
      (is (not (str/includes? content "YGG AGENT-INSTALL"))))))

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
                               "--query-index"
                               "--debounce-ms" "25"]))
      (is (= "fixture" (get-in @called [:project :id])))
      (is (= {:config-path "project.edn"
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
                                    :out "/tmp/ygg-out"
                                    :files {:index "/tmp/ygg-out/index.html"}})]
      (let [out (with-out-str
                  (cli/dispatch "report" ["project.edn"
                                          "--out" "bundle"
                                          "--detail" "expanded"
                                          "--force"]))
            parsed (read-json-output out)]
        (is (= report/schema (:schema parsed)))
        (is (= [[:read "project.edn"]
                [:report :xtdb "fixture" {:out "bundle"
                                          :detail :expanded
                                          :force? true}]]
               @calls))))))

(deftest bench-agent-packet-can-enqueue-provider-neutral-work
  (let [root (temp-dir "ygg-cli-agent-bench-queue")
        queue-projects (atom [])]
    (with-redefs [benchmark/read-suite (constantly {:id "suite"})
                  benchmark/agent-packets! (fn [suite opts]
                                             {:schema "ygg.benchmark.agent-packets/v1"
                                              :suite-id (:id suite)
                                              :opts opts
                                              :packets [{:schema benchmark/agent-packet-schema
                                                         :suite-id (:id suite)
                                                         :case-id "case-1"
                                                         :repo-id "repo"
                                                         :project-id "suite-case-1"
                                                         :mode "ygg"
                                                         :artifacts {:packetPath "/tmp/packet.json"}}]})
                  store/project-sqlite-path (fn [project-id]
                                              (swap! queue-projects conj project-id)
                                              root)]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-packet" "benchmark.edn"
                                 "--case" "case-1"
                                 "--mode" "ygg"
                                 "--parser-worker" "dotnet"
                                 "--enqueue"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "ygg.benchmark.agent-packets/v1" (:schema parsed)))
        (is (= {:case-id "case-1"
                :out nil
                :retriever nil
                :parser-worker "dotnet"
                :mode "ygg"
                :result-path nil
                :command nil}
               (:opts parsed)))
        (is (= ["benchmark-agent"] (mapv :kind (:enqueued parsed))))
        (is (= [benchmark/agent-packet-schema]
               (mapv :payload-schema (:enqueued parsed))))
        (is (= ["suite-case-1"] @queue-projects))))))

(deftest bench-agent-packet-rejects-local-queue-dir
  (let [root (temp-dir "ygg-cli-agent-bench-queue-reject")
        error (atom nil)]
    (with-redefs [benchmark/read-suite (constantly {:id "suite"})
                  benchmark/agent-packets! (fn [suite _opts]
                                             {:schema "ygg.benchmark.agent-packets/v1"
                                              :suite-id (:id suite)
                                              :packets [{:schema benchmark/agent-packet-schema
                                                         :suite-id (:id suite)
                                                         :case-id "case-1"
                                                         :repo-id "repo"
                                                         :project-id "suite-case-1"
                                                         :mode "ygg"}]})]
      (with-out-str
        (try
          (cli/dispatch "bench"
                        ["agent-packet" "benchmark.edn"
                         "--case" "case-1"
                         "--enqueue"
                         "--queue-dir" root
                         "--json"])
          (catch clojure.lang.ExceptionInfo e
            (reset! error e))))
      (is (= "Benchmark packet enqueue uses the central project queue."
             (ex-message @error)))
      (is (= {:command "bench agent-packet"
              :option "--queue-dir"}
             (ex-data @error))))))

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
                                                             :agentId "ygg-baseline-lexical"
                                                             :scores {:fileRecallAt10 1.0
                                                                      :meanReciprocalRankFile 1.0}}]})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-baseline" "benchmark.edn"
                                 "--case" "case-1"
                                 "--retriever" "lexical"
                                 "--provider" "local"
                                 "--model" "fake-embedding-model"
                                 "--batch-size" "9"
                                 "--embedding-input-max-chars" "321"
                                 "--embedding-request-timeout-ms" "12345"
                                 "--embedding-max-retries" "2"
                                 "--embedding-provider-limit" "44"
                                 "--limit" "3"
                                 "--doc-limit" "12"
                                 "--retrieval-limit" "80"
                                 "--vector-command" "fake-vector-worker"
                                 "--vector-model" "fake-vector-model"
                                 "--codebase-memory-command" "fake-codebase-memory-worker"
                                 "--codebase-memory-bin" "fake-codebase-memory-bin"
                                 "--codebase-memory-cache-dir" ".dev/fake-cbm-cache"
                                 "--graphify-command" "fake-graphify-worker"
                                 "--graphify-bin" "fake-graphify-bin"
                                 "--graphify-output-dir" ".dev/fake-graphify"
                                 "--graphify-query-budget" "123"
                                 "--graphify-max-workers" "2"
                                 "--graphify-include-non-code"
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
                  :provider "local"
                  :model "fake-embedding-model"
                  :batch-size 9
                  :embedding-input-max-chars 321
                  :embedding-request-timeout-ms 12345
                  :embedding-max-retries 2
                  :embedding-provider-limit 44
                  :parser-worker "java"
                  :mode nil
                  :result-path nil
                  :command nil
                  :vector-command "fake-vector-worker"
                  :vector-model "fake-vector-model"
                  :codebase-memory-command "fake-codebase-memory-worker"
                  :codebase-memory-bin "fake-codebase-memory-bin"
                  :codebase-memory-cache-dir ".dev/fake-cbm-cache"
                  :graphify-command "fake-graphify-worker"
                  :graphify-bin "fake-graphify-bin"
                  :graphify-output-dir ".dev/fake-graphify"
                  :graphify-query-budget 123
                  :graphify-max-workers 2
                  :limit 3
                  :doc-limit 12
                  :retrieval-limit 80
                  :index-timeout-ms 1234
                  :skip-existing? true
                  :graphify-include-non-code? true}]]
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
                                 "--mode" "ygg"
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
                  :mode "ygg"
                  :agent-id "codex"
                  :result-path nil
                  :command "codex exec --json"
                  :prompt-profile "fast"
                  :timeout-ms 120000
                  :skip-existing? true}]]
               @calls))))))

(deftest bench-agent-rerun-dispatches-to-benchmark-rerunner
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/rerun-agent-lane! (fn [suite opts]
                                                (swap! calls conj [:rerun-agent suite opts])
                                                {:schema benchmark/agent-runs-schema
                                                 :suite-id (:id suite)
                                                 :completed 1
                                                 :failed 0
                                                 :skipped 0
                                                 :runs []
                                                 :rerunLane {:caseIds ["case-1"]}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["agent-rerun" "benchmark.edn"
                                 "--agent-report" "agent-report.json"
                                 "--case" "case-1"
                                 "--mode" "ygg"
                                 "--agent" "codex"
                                 "--command" "codex exec --json"
                                 "--prompt-profile" "fast"
                                 "--timeout-ms" "120000"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/agent-runs-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:rerun-agent {:id "suite"}
                 {:case-id "case-1"
                  :out nil
                  :retriever nil
                  :parser-worker nil
                  :mode "ygg"
                  :result-path nil
                  :command "codex exec --json"
                  :agent-report-path "agent-report.json"
                  :agent-id "codex"
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
                                 "--cases" "case-1, case-2"
                                 "--mode" "ygg"
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
                  :mode "ygg"
                  :agent-id "codex"
                  :result-path nil
                  :command nil}]]
               @calls))))))

(deftest bench-improve-dispatches-to-system-improvement-reporter
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/improve-agent-suite (fn [suite opts]
                                                  (swap! calls conj [:improve suite opts])
                                                  {:schema benchmark/system-improvement-report-schema
                                                   :suite-id (:id suite)
                                                   :sourceReport {:runs 1}
                                                   :claimReady? false
                                                   :systemImprovementSignals []
                                                   :lanes []})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["improve" "benchmark.edn"
                                 "--mode" "ygg"
                                 "--agent" "codex"
                                 "--out" ".dev/reports/bench"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/system-improvement-report-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:improve {:id "suite"}
                 {:case-id nil
                  :out ".dev/reports/bench"
                  :retriever nil
                  :parser-worker nil
                  :mode "ygg"
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
                                 "--mode" "ygg"
                                 "--agent" "ygg-baseline-lexical"
                                 "--min-cases" "4"
                                 "--min-runs" "4"
                                 "--min-file-recall-at-10" "1.0"
                                 "--min-mrr" "0.9"
                                 "--max-noise-at-20" "0.5"
                                 "--min-evidence-citation-rate" "0.8"
                                 "--min-path-evidence-citation-rate" "0.6"
                                 "--min-expected-evidence-citation-rate" "0.7"
                                 "--min-decision-f1" "0.75"
                                 "--min-decision-evidence-citation-rate" "0.65"
                                 "--max-total-tokens" "10000"
                                 "--max-input-tokens" "8000"
                                 "--max-output-tokens" "2500"
                                 "--max-cost-usd" "0.5"
                                 "--min-case-file-recall-at-10" "1.0"
                                 "--min-case-mrr" "0.9"
                                 "--min-case-evidence-citation-rate" "0.7"
                                 "--min-case-path-evidence-citation-rate" "0.55"
                                 "--min-case-expected-evidence-citation-rate" "0.45"
                                 "--min-case-decision-f1" "0.5"
                                 "--max-case-total-tokens" "4000"
                                 "--max-case-input-tokens" "3200"
                                 "--max-case-output-tokens" "1000"
                                 "--max-case-cost-usd" "0.2"
                                 "--max-case-noise-at-20" "0.75"
                                 "--max-input-hinted-cases" "0"
                                 "--max-unsupported-ground-truth-files" "0"
                                 "--max-commandless-runs" "0"
                                 "--max-missing-predicted-file-runs" "0"
                                 "--max-missing-decision-runs" "0"
                                 "--max-warning-runs" "0"
                                 "--max-identity-mismatch-runs" "0"
                                 "--max-missing-declared-source-kind-runs" "0"
                                 "--max-missed-runs" "0"
                                 "--max-context-rank-missing-runs" "0"
                                 "--max-missed-but-present-in-context-runs" "0"
                                 "--max-missed-and-absent-from-context-runs" "0"
                                 "--max-ranked-outside-top-5-runs" "0"
                                 "--max-ranked-outside-top-10-runs" "0"
                                 "--max-ranked-outside-top-20-runs" "0"
                                 "--max-improvement-target-runs" "0"
                                 "--max-improvement-target-kind-runs" "source-skipped-files=0"
                                 "--max-improvement-target-kind-runs" "hint-diagnostics=1"
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
                  :mode "ygg"
                  :agent-id "ygg-baseline-lexical"
                  :result-path nil
                  :command nil
                  :min-cases 4
                  :min-runs 4
                  :min-file-recall-at-10 1.0
                  :min-mrr 0.9
                  :max-noise-at-20 0.5
                  :min-evidence-citation-rate 0.8
                  :min-path-evidence-citation-rate 0.6
                  :min-expected-evidence-citation-rate 0.7
                  :min-decision-f1 0.75
                  :min-decision-evidence-citation-rate 0.65
                  :max-total-tokens 10000.0
                  :max-input-tokens 8000.0
                  :max-output-tokens 2500.0
                  :max-cost-usd 0.5
                  :min-case-file-recall-at-10 1.0
                  :min-case-mrr 0.9
                  :min-case-evidence-citation-rate 0.7
                  :min-case-path-evidence-citation-rate 0.55
                  :min-case-expected-evidence-citation-rate 0.45
                  :min-case-decision-f1 0.5
                  :max-case-total-tokens 4000.0
                  :max-case-input-tokens 3200.0
                  :max-case-output-tokens 1000.0
                  :max-case-cost-usd 0.2
                  :max-case-noise-at-20 0.75
                  :max-input-hinted-cases 0.0
                  :max-unsupported-ground-truth-files 0.0
                  :max-commandless-runs 0.0
                  :max-missing-predicted-file-runs 0.0
                  :max-missing-decision-runs 0.0
                  :max-warning-runs 0.0
                  :max-identity-mismatch-runs 0.0
                  :max-missing-declared-source-kind-runs 0.0
                  :max-missed-runs 0.0
                  :max-context-rank-missing-runs 0.0
                  :max-missed-but-present-in-context-runs 0.0
                  :max-missed-and-absent-from-context-runs 0.0
                  :max-ranked-outside-top-5-runs 0.0
                  :max-ranked-outside-top-10-runs 0.0
                  :max-ranked-outside-top-20-runs 0.0
                  :max-improvement-target-runs 0.0
                  :max-improvement-target-kind-runs {"hint-diagnostics" 1.0
                                                     "source-skipped-files" 0.0}
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

(deftest bench-claim-pack-dispatches-to-claim-pack-writer
  (let [calls (atom [])]
    (with-redefs [benchmark/read-suite (fn [path]
                                         (swap! calls conj [:read path])
                                         {:id "suite"})
                  benchmark/claim-pack! (fn [suite opts]
                                          (swap! calls conj [:claim-pack
                                                             suite
                                                             opts])
                                          {:schema benchmark/claim-pack-schema
                                           :suiteId (:id suite)
                                           :summary {:verdict "helped"
                                                     :claimReadiness "supported"
                                                     :qualityCostTradeoff
                                                     {:status "better-quality-lower-token-cost"}}
                                           :artifacts {:claimPackPath "claim-pack.json"
                                                       :claimPackMarkdownPath
                                                       "CLAIM-PACK.md"}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["claim-pack" "benchmark.edn"
                                 "--shell-report" "shell/agent-report.json"
                                 "--ygg-report" "ygg/agent-report.json"
                                 "--min-shared-cases" "4"
                                 "--out" ".dev/reports/claim-pack"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= benchmark/claim-pack-schema (:schema parsed)))
        (is (= [[:read "benchmark.edn"]
                [:claim-pack {:id "suite"}
                 {:case-id nil
                  :out ".dev/reports/claim-pack"
                  :retriever nil
                  :parser-worker nil
                  :mode nil
                  :result-path nil
                  :command nil
                  :shell-report "shell/agent-report.json"
                  :ygg-report "ygg/agent-report.json"
                  :min-shared-cases 4}]]
               @calls))))))

(deftest bench-repos-check-dispatches-to-benchmark-repo-preflight
  (let [calls (atom [])]
    (with-redefs [benchmark-repos/check-repos
                  (fn [opts]
                    (swap! calls conj [:check opts])
                    {:schema "ygg.benchmark.repo-check/v1"
                     :status "passed"
                     :counts {:repos 1
                              :ready 1
                              :missing 0
                              :not-git 0
                              :missing-shas 0
                              :unknown 0}
                     :repos []})
                  benchmark-repos/print-human
                  (fn [check]
                    (swap! calls conj [:print (:status check)])
                    (println (str "repo-check=" (:status check))))]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["repos"
                                 "check"
                                 "--manifest" "benchmarks/repos.edn"
                                 "--suite" "benchmarks/custom.edn"
                                 "--repo" "repo-a"
                                 "--repo" "repo-b"]))]
        (is (= "repo-check=passed\n" out))
        (is (= [[:check {:manifest-path "benchmarks/repos.edn"
                         :suite-path "benchmarks/custom.edn"
                         :repo-ids ["repo-a" "repo-b"]}]
                [:print "passed"]]
               @calls))))))

(deftest bench-efficiency-dispatches-to-agent-efficiency-comparison
  (let [calls (atom [])]
    (with-redefs [agent-efficiency/compare-report-files!
                  (fn [shell-report ygg-report opts]
                    (swap! calls conj [:compare shell-report ygg-report opts])
                    {:schema "ygg.agent-efficiency/v1"
                     :status "observed"
                     :byTag {:comparability {:sharedTags 2}}
                     :classSignals {:summary {:measuredProblemClasses 1
                                              :measuredArchitectureClasses 1}}})]
      (let [out (with-out-str
                  (cli/dispatch "bench"
                                ["efficiency"
                                 "shell/agent-report.json"
                                 "ygg/agent-report.json"
                                 "--out" ".dev/reports/efficiency.json"
                                 "--markdown-out" ".dev/reports/efficiency.md"
                                 "--min-shared-cases" "2"
                                 "--json"]))
            parsed (read-json-output out)]
        (is (= "ygg.agent-efficiency/v1" (:schema parsed)))
        (is (= [[:compare
                 "shell/agent-report.json"
                 "ygg/agent-report.json"
                 {:out ".dev/reports/efficiency.json"
                  :markdown-out ".dev/reports/efficiency.md"
                  :min-shared-cases 2}]]
               @calls))))))
