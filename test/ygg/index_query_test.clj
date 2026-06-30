(ns ygg.index-query-test
  (:require [ygg.dependency :as dependency]
            [ygg.extract :as extract]
            [ygg.file-facts :as file-facts]
            [ygg.fs :as fs]
            [ygg.graph :as graph]
            [ygg.index :as index]
            [ygg.query :as query]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
    file))

(deftest indexable-extraction-drops-nil-optional-values
  (let [result (#'index/indexable-extraction
                "run:test"
                "project"
                "repo"
                :query
                []
                {:file-id "file:src/app.clj"
                 :path "src/app.clj"
                 :kind :source
                 :content ""}
                {:nodes [{:xt/id "node:app"
                          :file-id "file:src/app.clj"
                          :path "src/app.clj"
                          :kind :namespace
                          :label "app"
                          :public? nil
                          :active? true
                          :run-id "run:test"}]
                 :edges []
                 :chunks []
                 :file-facts []
                 :diagnostics []})
        node (first (:nodes result))]
    (is (= "project" (:project-id node)))
    (is (= "repo" (:repo-id node)))
    (is (not (contains? node :public?)))))

(deftest query-scoped-reads-use-constrained-store-queries
  (let [calls (atom [])
        chunk-calls (atom [])]
    (with-redefs [store/constrained-rows
                  (fn [_ table constraints ctx]
                    (swap! calls conj [table constraints ctx])
                    (case table
                      :ygg/nodes
                      [{:xt/id "node:app"
                        :project-id "project-a"
                        :repo-id "repo-a"
                        :label "app"}]

                      :ygg/search-docs
                      [{:xt/id "search-doc:app"
                        :project-id "project-a"
                        :repo-id "repo-a"
                        :active? true}]

                      :ygg/chunks
                      (throw (ex-info "chunks should use a batched path read"
                                      {:constraints constraints}))))
                  store/rows-with-field-values
                  (fn [_ request]
                    (swap! chunk-calls conj request)
                    [{:xt/id "chunk:src/db.clj"
                      :project-id "project-a"
                      :repo-id "repo-a"
                      :path "src/db.clj"}
                     {:xt/id "chunk:src/app.clj"
                      :project-id "project-a"
                      :repo-id "repo-a"
                      :path "src/app.clj"}])]
      (is (= ["node:app"]
             (mapv :xt/id
                   (query/all-nodes :xtdb
                                    {:project-id "project-a"
                                     :repo-id "repo-a"}))))
      (is (= ["search-doc:app"]
             (mapv :xt/id
                   (query/all-search-docs :xtdb
                                          {:project-id "project-a"
                                           :repo-id "repo-a"}))))
      (is (= ["chunk:src/app.clj" "chunk:src/db.clj"]
             (mapv :xt/id
                   (query/chunks-by-paths :xtdb
                                          ["src/app.clj" "src/app.clj" "src/db.clj"]
                                          {:project-id "project-a"
                                           :repo-id "repo-a"})))))
    (is (= [[(:nodes store/tables)
             {:project-id "project-a"
              :repo-id "repo-a"}
             {}]
            [(:search-docs store/tables)
             {:project-id "project-a"
              :repo-id "repo-a"
              :active? true}
             {}]]
           @calls))
    (is (= [{:table (:chunks store/tables)
             :field :path
             :values ["src/app.clj" "src/db.clj"]
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"}
             :return-fields @#'query/chunk-row-query-fields
             :read-context {}}]
           @chunk-calls))))

(deftest lexical-frequency-builders-count-only-query-tokens
  (let [query-token-set #{"auth" "proxy"}
        token-frequencies @#'query/token-frequencies
        lexical-score-input @#'query/lexical-score-input
        docs [{:tokens ["auth" "auth" "noise" "noise"]}
              {:tokens ["proxy" "proxy" "auth" "other"]}
              {:tokens ["noise" "other"]}]]
    (is (= {"auth" 2
            "proxy" 1}
           (token-frequencies query-token-set ["auth" "auth" "proxy" "noise"])))
    (is (= {}
           (token-frequencies #{} ["auth" "auth" "proxy" "noise"])))
    (is (= {"auth" 2
            "proxy" 1}
           (:doc-freqs (lexical-score-input query-token-set docs))))))

(deftest indexes-and-queries-sample-repo
  (let [xtdb-path (temp-dir "ygg-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo! xtdb repo {})
              search (query/semantic-query xtdb "greeting shout" {:limit 5})
              deps (query/deps xtdb "sample.core")
              path (query/graph-path xtdb "sample.core" "sample.util")
              report (query/report xtdb)]
          (is (= :completed (:status summary)))
          (is (pos? (get-in summary [:stats :files-indexed])))
          (is (seq search))
          (is (seq (:outgoing deps)))
          (is (not-any? #(= :defines (:relation %)) (:outgoing deps)))
          (is (seq (:definitions deps)))
          (is (= ["sample.core" "sample.util"] (mapv :label path)))
          (is (pos? (get-in report [:counts :nodes])))
          (is (pos? (get-in report [:counts :search-docs]))))))))

(deftest index-repo-honors-cooperative-deadline
  (let [repo (io/file (temp-dir "ygg-index-deadline-repo"))
        src (io/file repo "src")]
    (.mkdirs src)
    (spit (io/file src "app.clj") "(ns app)\n(defn value [] 1)\n")
    (let [error (try
                  (index/index-repo! nil
                                     (.getPath repo)
                                     {:dry-run? true
                                      :index-deadline-ns (dec (System/nanoTime))})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (is (some? error))
      (is (= "Index deadline exceeded." (ex-message error)))
      (is (= :canonicalize-root (:phase (ex-data error)))))))

(deftest exact-path-mentions-rank-matching-search-docs
  (let [xtdb-path (temp-dir "ygg-query-path-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {:index-profile :query})
        (let [results (query/semantic-query
                       xtdb
                       "Pasting fails in src/sample/util.clj after many unrelated workspace details."
                       {:retriever :lexical
                        :limit 5})
              first-result (first results)]
          (is (= "src/sample/util.clj" (:path first-result)))
          (is (= 2.0 (get-in first-result [:score-components :exact]))))))))

(deftest auto-query-with-exact-path-skips-embedding-client
  (store/with-node (temp-dir "ygg-query-auto-path-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:hit"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:hit"
                       :target-kind :node
                       :file-id "file:hit"
                       :path "src/hit.clj"
                       :kind :namespace
                       :label "demo.hit"
                       :text "demo.hit"
                       :tokens ["demo" "hit"]
                       :input-sha "hit"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (let [calls (atom 0)
            report (query/search-report
                    xtdb
                    "Open src/hit.clj before reading anything else"
                    {:retriever :auto
                     :embedding-client {:provider :fake
                                        :model "fake"
                                        :embed-batch (fn [_inputs]
                                                       (swap! calls inc)
                                                       [[1.0 0.0]])}
                     :project-id "fixture"
                     :repo-id "app"
                     :limit 5})
            instrumentation (:instrumentation report)]
        (is (zero? @calls))
        (is (= :lexical (:retriever-effective report)))
        (is (= :exact-path-candidates
               (:auto-lexical-short-circuit-reason instrumentation)))
        (is (true? (:auto-lexical-short-circuit? instrumentation)))
        (is (zero? (:query-embedding-ms instrumentation)))
        (is (zero? (:load-embeddings-ms instrumentation)))
        (is (= "src/hit.clj" (:path (first (:results report)))))))))

(deftest auto-query-with-path-token-candidate-still-runs-semantic-search
  (store/with-node (temp-dir "ygg-query-auto-path-token-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:path-token"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:path-token"
                       :target-kind :node
                       :file-id "file:path-token"
                       :path "src/hello_world.clj"
                       :kind :namespace
                       :label "demo.path-token"
                       :text "demo path token"
                       :tokens ["demo" "path" "token"]
                       :input-sha "path-token"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :embeddings)
                      {:xt/id "embedding:path-token"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:path-token"
                       :provider :fake
                       :model "fake"
                       :dims 2
                       :input-sha "path-token"
                       :vector [1.0 0.0]
                       :created-at-ms 1
                       :active? true})])
      (let [calls (atom 0)
            report (query/search-report
                    xtdb
                    "hello"
                    {:retriever :auto
                     :embedding-client {:provider :fake
                                        :model "fake"
                                        :embed-batch (fn [_inputs]
                                                       (swap! calls inc)
                                                       [[1.0 0.0]])}
                     :project-id "fixture"
                     :repo-id "app"
                     :limit 5})
            instrumentation (:instrumentation report)]
        (is (= 1 @calls))
        (is (= :hybrid (:retriever-effective report)))
        (is (false? (:auto-lexical-short-circuit? instrumentation)))
        (is (= :none (:auto-lexical-short-circuit-reason instrumentation)))
        (is (pos? (:path-token-candidates instrumentation)))
        (is (pos? (:semantic-positive instrumentation)))))))

(deftest exact-path-mentions-enter-query-candidates
  (store/with-node (temp-dir "ygg-query-path-candidate-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:noise"
                       :target-id "chunk:noise"
                       :target-kind :chunk
                       :file-id "file:noise"
                       :path "docs/noise.md"
                       :kind :markdown
                       :label "Pasting workspace failure details"
                       :text "Pasting fails after unrelated workspace details"
                       :tokens ["pasting" "fails" "workspace" "details"]
                       :input-sha "noise"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:hidden"
                       :target-id "chunk:hidden"
                       :target-kind :chunk
                       :file-id "file:hidden"
                       :path "src/hidden/file.clj"
                       :kind :namespace
                       :label "hidden.file"
                       :text "hidden.file"
                       :tokens []
                       :input-sha "hidden"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1]
        (let [results (query/semantic-query
                       xtdb
                       "Pasting fails in src/hidden/file.clj after unrelated workspace details."
                       {:retriever :lexical
                        :limit 5})
              first-result (first results)]
          (is (= "src/hidden/file.clj" (:path first-result)))
          (is (= 2.0 (get-in first-result [:score-components :exact]))))))))

(deftest query-token-path-overlap-boosts-search-docs
  (store/with-node (temp-dir "ygg-query-path-token-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:consumer"
                       :target-id "node:consumer"
                       :target-kind :node
                       :file-id "file:consumer"
                       :path "consumer/consumer.go"
                       :kind :namespace
                       :label "go.opentelemetry.io/collector/internal"
                       :text "contracts"
                       :tokens ["contracts"]
                       :input-sha "consumer"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:other"
                       :target-id "node:other"
                       :target-kind :node
                       :file-id "file:other"
                       :path "other/other.go"
                       :kind :namespace
                       :label "go.opentelemetry.io/collector/internal"
                       :text "contracts"
                       :tokens ["contracts"]
                       :input-sha "other"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 0
                    query/default-kind-candidates 0]
        (let [results (query/semantic-query xtdb
                                            "consumer contracts"
                                            {:retriever :lexical
                                             :limit 2})
              first-result (first results)]
          (is (= ["consumer/consumer.go"] (mapv :path results)))
          (is (= 0.05 (get-in first-result [:score-components :exact]))))))))

(deftest path-token-candidates-preserve-file-stem-identity
  (let [docs (conj (mapv (fn [idx]
                           {:target-id (str "node:generic-" idx)
                            :path (format "aaa/engine/lifecycle/execution-%02d.java"
                                          idx)
                            :label (str "generic.execution." idx)})
                         (range 5))
                   {:target-id "node:jupiter-test-engine"
                    :path (str "junit-jupiter-engine/src/main/java/org/junit/"
                               "jupiter/engine/JupiterTestEngine.java")
                    :label "org.junit.jupiter.engine/JupiterTestEngine"})
        data (#'query/path-token-candidate-data
              #{"jupitertestengine" "engine" "lifecycle" "execution"}
              docs
              3)
        boost (#'query/exact-match-boost
               ""
               #{"jupitertestengine" "engine" "lifecycle" "execution"}
               false
               {"node:generic-0" 3
                "node:jupiter-test-engine" 1}
               {:target-id "node:jupiter-test-engine"
                :path (str "junit-jupiter-engine/src/main/java/org/junit/"
                           "jupiter/engine/JupiterTestEngine.java")
                :label "org.junit.jupiter.engine/JupiterTestEngine"})
        generic-boost (#'query/exact-match-boost
                       ""
                       #{"jupitertestengine" "engine" "lifecycle" "execution"}
                       false
                       {"node:generic-0" 3
                        "node:jupiter-test-engine" 1}
                       {:target-id "node:generic-0"
                        :path "aaa/engine/lifecycle/execution-00.java"
                        :label "generic.execution.0"})]
    (is (= "node:jupiter-test-engine" (first (:candidate-ids data))))
    (is (some #{"node:jupiter-test-engine"} (:candidate-ids data)))
    (is (< generic-boost boost))))

(deftest lexical-query-promotes-grep-matched-files-without-search-docs
  (let [repo-root (temp-dir "ygg-query-grep-file-repo")]
    (spit-file! repo-root "src/reset.clj" "(defn ResetTokenHandler [] :ok)\n")
    (store/with-node (temp-dir "ygg-query-grep-file-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :repos)
                        {:xt/id "repo:fixture:app"
                         :project-id "fixture"
                         :repo-id "app"
                         :root repo-root
                         :active? true})
          (store/put-op (store/table-ref :files)
                        {:xt/id "file:fixture:app:src/reset.clj"
                         :project-id "fixture"
                         :repo-id "app"
                         :repo-root repo-root
                         :path "src/reset.clj"
                         :ext "clj"
                         :kind :clojure
                         :content-sha "sha"
                         :mtime-ms 1
                         :size-bytes 32
                         :active? true
                         :run-id "run"})])
        (let [report (query/search-report xtdb
                                          "reset token handler"
                                          {:retriever :lexical
                                           :project-id "fixture"
                                           :repo-id "app"
                                           :limit 5})
              result (first (:results report))
              instrumentation (:instrumentation report)]
          (is (= ["src/reset.clj"] (mapv :path (:results report))))
          (is (= :file (:result-kind result)))
          (is (= 1.0 (get-in result [:score-components :grep])))
          (is (= 1 (:transient-file-candidates instrumentation)))
          (is (= 1 (:grep-candidates instrumentation))))))))

(deftest lexical-query-includes-internal-grep-candidates-from-repo-metadata
  (let [repo-root (temp-dir "ygg-query-grep-repo")]
    (spit-file! repo-root "src/reset.clj" "(defn ResetTokenHandler [] :ok)\n")
    (store/with-node (temp-dir "ygg-query-grep-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :repos)
                        {:xt/id "repo:fixture:app"
                         :project-id "fixture"
                         :repo-id "app"
                         :root repo-root
                         :active? true})
          (store/put-op (store/table-ref :search-docs)
                        {:xt/id "search-doc:reset"
                         :project-id "fixture"
                         :repo-id "app"
                         :target-id "node:reset"
                         :target-kind :node
                         :file-id "file:reset"
                         :path "src/reset.clj"
                         :kind :var
                         :label "demo/reset"
                         :text "demo/reset"
                         :tokens []
                         :input-sha "reset"
                         :source-line 1
                         :active? true
                         :run-id "run"})])
        (with-redefs [query/default-kind-candidates 0
                      query/default-path-token-candidates 0]
          (let [report (query/search-report xtdb
                                            "reset token handler"
                                            {:retriever :lexical
                                             :project-id "fixture"
                                             :repo-id "app"
                                             :limit 5})
                result (first (:results report))
                instrumentation (:instrumentation report)]
            (is (= ["src/reset.clj"] (mapv :path (:results report))))
            (is (= 1.0 (get-in result [:score-components :grep])))
            (is (= "literal grep match" (:reason result)))
            (is (= :ok (:grep-status instrumentation)))
            (is (= 1 (:grep-repos instrumentation)))
            (is (= 3 (:grep-patterns instrumentation)))
            (is (= 3 (:grep-searches instrumentation)))
            (is (= 3 (:grep-raw-matches instrumentation)))
            (is (= 1 (:grep-indexed-paths instrumentation)))
            (is (= 1 (:grep-candidates instrumentation)))))))))

(deftest grep-patterns-prefer-corpus-backed-normalized-tokens
  (let [axios-patterns (#'query/grep-patterns
                        (text/tokenize
                         "Locate the boundary between Node native proxy handling and Axios proxy rewriting. Identify the Axios adapter and tests.")
                        [{:path "tests/unit/adapters/http.test.js"
                          :label "http adapter proxy"
                          :kind :namespace
                          :tokens ["proxy" "adapter" "axios" "node" "identify" "cite" "evidence"]}
                         {:path "lib/adapters/http.js"
                          :label "native http adapter proxy"
                          :kind :namespace
                          :tokens ["proxy" "environment" "native" "adapter"]}
                         {:path "AGENTS.md"
                          :label "Agent instructions"
                          :kind :markdown
                          :tokens ["identify" "likely" "edit" "evidence" "cite" "natively"]}]
                        {:grep-pattern-limit 6})
        dapper-patterns (#'query/grep-patterns
                         (text/tokenize
                          "Add ReferenceTrimmer and remove unused references. Historical replay of Dapper commit 72a54c475f75e18cb93cba0809d00a5e6e49efd9. Identify central package files likely to need dependency edits.")
                         [{:path "Directory.Packages.props"
                           :label "PackageVersion Dapper.Contrib"
                           :kind :package-version
                           :tokens ["reference" "package" "dependency" "dapper" "edit"]}
                          {:path "benchmarks/Dapper.Tests.Performance/Dapper.Tests.Performance.csproj"
                           :label "PackageReference BenchmarkDotNet"
                           :kind :package-dependency
                           :tokens ["reference" "references" "package" "dependency"]}
                          {:path "Readme.md"
                           :label "Project notes"
                           :kind :markdown
                           :tokens ["identify" "edit" "removed" "files" "diff" "added"]}]
                         {:grep-pattern-limit 6})]
    (is (some #{"proxy"} axios-patterns))
    (is (some #{"adapter"} axios-patterns))
    (is (not-any? #{"rewriting."} axios-patterns))
    (is (not-any? #{"cite" "identify" "evidence"} (take 5 axios-patterns)))
    (is (some #{"reference"} dapper-patterns))
    (is (some #{"package"} dapper-patterns))
    (is (some #{"dependency"} dapper-patterns))
    (is (not-any? #{"references."
                    "72a54c475f75e18cb93cba0809d00a5e6e49efd9"
                    "identify"
                    "edit"}
                  (take 5 dapper-patterns)))))

(deftest lexical-query-grep-patterns-prefer-specific-late-tokens
  (let [repo-root (temp-dir "ygg-query-specific-grep-repo")]
    (spit-file! repo-root
                "mercury_app/templates/root.html"
                "{{ notebooks_button_label }}\n")
    (spit-file! repo-root
                "src/pytop/__init__.py"
                "__version__ = \"1.7.0\"\n")
    (spit-file! repo-root
                "mercury_app/templates/login.html"
                "<form id=\"login_submit\">Log in</form>\n")
    (store/with-node (temp-dir "ygg-query-specific-grep-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :repos)
                        {:xt/id "repo:fixture:app"
                         :project-id "fixture"
                         :repo-id "app"
                         :root repo-root
                         :active? true})
          (store/put-op (store/table-ref :files)
                        {:xt/id "file:fixture:app:mercury_app/templates/root.html"
                         :project-id "fixture"
                         :repo-id "app"
                         :repo-root repo-root
                         :path "mercury_app/templates/root.html"
                         :ext "html"
                         :kind :html
                         :content-sha "root-sha"
                         :mtime-ms 1
                         :size-bytes 29
                         :active? true
                         :run-id "run"})
          (store/put-op (store/table-ref :files)
                        {:xt/id "file:fixture:app:src/pytop/__init__.py"
                         :project-id "fixture"
                         :repo-id "app"
                         :repo-root repo-root
                         :path "src/pytop/__init__.py"
                         :ext "py"
                         :kind :python
                         :content-sha "init-sha"
                         :mtime-ms 1
                         :size-bytes 22
                         :active? true
                         :run-id "run"})
          (store/put-op (store/table-ref :files)
                        {:xt/id "file:fixture:app:mercury_app/templates/login.html"
                         :project-id "fixture"
                         :repo-id "app"
                         :repo-root repo-root
                         :path "mercury_app/templates/login.html"
                         :ext "html"
                         :kind :html
                         :content-sha "login-sha"
                         :mtime-ms 1
                         :size-bytes 38
                         :active? true
                         :run-id "run"})])
        (let [root-report (query/search-report
                           xtdb
                           "customize search notebooks filter config.toml notebooks_button_label hide search"
                           {:retriever :lexical
                            :project-id "fixture"
                            :repo-id "app"
                            :limit 5})
              version-report (query/search-report
                              xtdb
                              "python -m pytop --version capability banner __version__ __main__"
                              {:retriever :lexical
                               :project-id "fixture"
                               :repo-id "app"
                               :limit 5})
              login-report (query/search-report
                            xtdb
                            "login view custom styles top toolbar color fonts config theme"
                            {:retriever :lexical
                             :project-id "fixture"
                             :repo-id "app"
                             :limit 5})]
          (is (= ["mercury_app/templates/root.html"]
                 (mapv :path (:results root-report))))
          (is (= 1 (get-in root-report
                           [:instrumentation :transient-file-candidates])))
          (is (= ["src/pytop/__init__.py"]
                 (mapv :path (:results version-report))))
          (is (= 1 (get-in version-report
                           [:instrumentation :transient-file-candidates])))
          (is (= ["mercury_app/templates/login.html"]
                 (mapv :path (:results login-report))))
          (is (= 1 (get-in login-report
                           [:instrumentation :transient-file-candidates]))))))))

(deftest lexical-query-ranks-indexed-docs-with-literal-grep-evidence
  (let [repo-root (temp-dir "ygg-query-indexed-grep-repo")]
    (spit-file! repo-root
                "mercury_app/templates/root.html"
                "{{ notebooks_button_label }}\n")
    (store/with-node (temp-dir "ygg-query-indexed-grep-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :repos)
                        {:xt/id "repo:fixture:app"
                         :project-id "fixture"
                         :repo-id "app"
                         :root repo-root
                         :active? true})
          (store/put-op (store/table-ref :search-docs)
                        {:xt/id "search-doc:root-chunk"
                         :project-id "fixture"
                         :repo-id "app"
                         :target-id "chunk:root"
                         :target-kind :chunk
                         :file-id "file:root"
                         :path "mercury_app/templates/root.html"
                         :kind :chunk
                         :label "layout block"
                         :text "layout block"
                         :tokens ["layout" "block"]
                         :input-sha "root"
                         :source-line 1
                         :active? true
                         :run-id "run"})])
        (let [report (query/search-report
                      xtdb
                      "customize search notebooks filter config.toml notebooks_button_label hide search"
                      {:retriever :lexical
                       :project-id "fixture"
                       :repo-id "app"
                       :limit 5})
              result (first (:results report))]
          (is (= ["mercury_app/templates/root.html"]
                 (mapv :path (:results report))))
          (is (= :chunk (:result-kind result)))
          (is (= 1.0 (get-in result [:score-components :grep])))
          (is (pos? (:score result))))))))

(deftest lexical-query-ranks-indexed-hidden-docs-with-literal-grep-evidence
  (let [repo-root (temp-dir "ygg-query-hidden-indexed-grep-repo")]
    (spit-file! repo-root
                ".github/workflows/ci.yml"
                "steps:\n  - uses: actions/setup-node@v4\n    with:\n      node-version: 22\n")
    (store/with-node (temp-dir "ygg-query-hidden-indexed-grep-xtdb")
      (fn [xtdb]
        (store/execute-tx!
         xtdb
         [(store/put-op (store/table-ref :repos)
                        {:xt/id "repo:fixture:app"
                         :project-id "fixture"
                         :repo-id "app"
                         :root repo-root
                         :active? true})
          (store/put-op (store/table-ref :search-docs)
                        {:xt/id "search-doc:hidden-workflow"
                         :project-id "fixture"
                         :repo-id "app"
                         :target-id "chunk:hidden-workflow"
                         :target-kind :chunk
                         :file-id "file:hidden-workflow"
                         :path ".github/workflows/ci.yml"
                         :kind :chunk
                         :label "workflow config"
                         :text "workflow config"
                         :tokens ["workflow" "config"]
                         :input-sha "hidden-workflow"
                         :source-line 1
                         :active? true
                         :run-id "run"})])
        (let [report (query/search-report
                      xtdb
                      "workflow config node-version-file"
                      {:retriever :lexical
                       :project-id "fixture"
                       :repo-id "app"
                       :limit 5})
              result (first (:results report))]
          (is (= [".github/workflows/ci.yml"]
                 (mapv :path (:results report))))
          (is (= :chunk (:result-kind result)))
          (is (pos? (double (get-in result [:score-components :grep] 0.0))))
          (is (some #{"node-version"}
                    (get-in report [:instrumentation :grep-pattern-values]))))))))

(deftest explicit-hybrid-query-still-calls-embedding-client
  (store/with-node (temp-dir "ygg-query-explicit-hybrid-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:hit"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:hit"
                       :target-kind :node
                       :file-id "file:hit"
                       :path "src/hit.clj"
                       :kind :namespace
                       :label "demo.hit"
                       :text "hello"
                       :tokens ["hello"]
                       :input-sha "hit"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :embeddings)
                      {:xt/id "embedding:hit"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:hit"
                       :provider :fake
                       :model "fake"
                       :dims 2
                       :input-sha "hit"
                       :vector [1.0 0.0]
                       :created-at-ms 1
                       :active? true})])
      (let [calls (atom 0)
            report (query/search-report
                    xtdb
                    "hello"
                    {:retriever :hybrid
                     :embedding-client {:provider :fake
                                        :model "fake"
                                        :embed-batch (fn [_inputs]
                                                       (swap! calls inc)
                                                       [[1.0 0.0]])}
                     :project-id "fixture"
                     :repo-id "app"
                     :limit 5})]
        (is (= 1 @calls))
        (is (= :hybrid (:retriever-effective report)))
        (is (false? (get-in report [:instrumentation
                                    :auto-lexical-short-circuit?])))
        (is (pos? (get-in report [:instrumentation :semantic-positive])))))))

(deftest ranked-candidates-reuse-path-token-matches-for-exact-boost
  (let [tokenize-calls (atom [])
        internal-path-match-key (keyword "ygg.query" "path-token-matches")
        docs [{:xt/id "search-doc:consumer"
               :target-id "node:consumer"
               :target-kind :node
               :path "consumer/consumer.go"
               :label "unrelated label"
               :kind :namespace}
              {:xt/id "search-doc:other"
               :target-id "node:other"
               :target-kind :node
               :path "other/other.go"
               :label "unrelated other"
               :kind :namespace}]]
    (with-redefs [text/tokenize
                  (fn [value]
                    (swap! tokenize-calls conj value)
                    (case value
                      "consumer/consumer.go" ["consumer" "go"]
                      "other/other.go" ["other" "go"]
                      "unrelated label" ["unrelated" "label"]
                      []))]
      (let [ranked (:ranked (#'query/ranked-candidates
                             {:query-text "consumer contracts"
                              :query-tokens ["consumer" "contracts"]
                              :docs docs
                              :lexical {}
                              :semantic {}
                              :neighbor-scores {}
                              :retriever :lexical}))]
        (is (= ["node:consumer"] (mapv :target-id ranked)))
        (is (= 0.05 (get-in (first ranked) [:score-components :exact])))
        (is (= 1 (count (filter #{"consumer/consumer.go"} @tokenize-calls))))
        (is (= 1 (count (filter #{"other/other.go"} @tokenize-calls))))
        (is (not-any? #(contains? % internal-path-match-key) ranked))))))

(deftest ranked-result-selection-reserves-missing-result-kind
  (let [rows (concat
              (mapv (fn [idx]
                      {:target-id (str "chunk:" idx)
                       :result-kind :chunk
                       :score (- 2.0 (* idx 0.01))
                       :label (str "chunk " idx)
                       :path (str "docs/chunk_" idx ".md")
                       :score-components {:lexical 1.0}})
                    (range 12))
              [{:target-id "system:api"
                :result-kind :system-node
                :score 0.1
                :label "services/api"
                :path "services/api"
                :score-components {:lexical 0.1}}])
        selected (#'query/select-ranked-results rows 10)]
    (is (= 10 (count selected)))
    (is (some #(= :system-node (:result-kind %)) selected))))

(deftest ranked-result-selection-reserves-source-file-kind-rows
  (let [rows (concat
              (mapv (fn [idx]
                      {:target-id (str "chunk:" idx)
                       :result-kind :chunk
                       :kind :markdown
                       :score (- 2.0 (* idx 0.01))
                       :label (str "doc chunk " idx)
                       :path (str "docs/chunk_" idx ".md")
                       :score-components {:lexical 1.0}})
                    (range 20))
              [{:target-id "chunk:doc-file"
                :result-kind :chunk
                :kind :doc-file
                :score 0.2
                :label "doc file"
                :path "docs/reference.md"
                :score-components {:lexical 1.0}}
               {:target-id "chunk:source-file"
                :result-kind :chunk
                :kind :python-file
                :score 0.1
                :label "source file"
                :path "src/app.py"
                :score-components {:lexical 0.5}}])
        selected (#'query/select-ranked-results rows 12)]
    (is (= 12 (count selected)))
    (is (some #(= "chunk:source-file" (:target-id %)) selected))
    (is (not-any? #(= "chunk:doc-file" (:target-id %)) selected))))

(deftest ranked-result-selection-reserves-direct-source-kind-graph-rows
  (let [rows (concat
              (mapv (fn [idx]
                      {:target-id (str "chunk:" idx)
                       :result-kind :chunk
                       :kind :markdown
                       :score (- 2.0 (* idx 0.01))
                       :label (str "doc chunk " idx)
                       :path (str "docs/chunk_" idx ".md")
                       :score-components {:lexical 1.0}})
                    (range 20))
              [{:target-id "file:doc"
                :result-kind :file
                :kind :doc-file
                :score 0.2
                :label "reference doc"
                :path "docs/reference.md"
                :score-components {:sourceGraph 0.6}}
               {:target-id "file:source"
                :result-kind :file
                :kind :python
                :score 0.1
                :label "source file"
                :path "src/app.py"
                :score-components {:sourceGraph 0.6}}])
        selected (#'query/select-ranked-results rows 12)]
    (is (= 12 (count selected)))
    (is (some #(= "file:source" (:target-id %)) selected))
    (is (not-any? #(= "file:doc" (:target-id %)) selected))))

(deftest search-report-includes-instrumentation-and-persists-query-run
  (store/with-node (temp-dir "ygg-query-report-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:node"
                       :target-id "node:auth"
                       :target-kind :node
                       :file-id "file:auth"
                       :path "src/auth.clj"
                       :kind :var
                       :label "demo/auth"
                       :text "auth login"
                       :tokens ["auth" "login"]
                       :input-sha "auth"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:chunk"
                       :target-id "chunk:auth"
                       :target-kind :chunk
                       :file-id "file:auth"
                       :path "src/auth.clj"
                       :kind :code-definition
                       :label "demo/auth"
                       :text "auth login handler"
                       :tokens ["auth" "login" "handler"]
                       :input-sha "auth-chunk"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (let [report (query/search-report xtdb
                                        "auth login"
                                        {:retriever :lexical
                                         :limit 5})
            instrumentation (:instrumentation report)
            query-run (first (store/all-rows xtdb (store/table-ref :query-runs)))]
        (is (= query/search-report-schema (:schema report)))
        (is (= :lexical (:retriever-requested report)))
        (is (= :lexical (:retriever-effective report)))
        (is (= ["src/auth.clj"] (distinct (map :path (:results report)))))
        (is (= (:query-run-id report) (:xt/id query-run)))
        (is (= instrumentation (:instrumentation query-run)))
        (is (= 2 (:search-docs instrumentation)))
        (is (= 2 (:returned-count instrumentation)))
        (is (= {:chunk 1 :node 1} (:search-docs-by-kind instrumentation)))
        (is (= :weighted (:fusion-strategy instrumentation)))
        (is (contains? (:fusion-source-counts instrumentation) :lexical))
        (is (contains? instrumentation :candidate-facets))
        (is (= "xtql-rel-unify" (:graph-adjacency-strategy instrumentation)))
        (is (= (:load-edges-ms instrumentation)
               (:graph-adjacency-ms instrumentation)))
        (is (= 2 (:graph-adjacency-query-count instrumentation)))
        (is (= 1 (:graph-adjacency-source-query-count instrumentation)))
        (is (= 1 (:graph-adjacency-target-query-count instrumentation)))
        (is (= (:seed-count instrumentation)
               (:graph-adjacency-seed-count instrumentation)))
        (is (= ["chunk:auth" "node:auth"]
               (:graph-adjacency-seed-id-sample instrumentation)))
        (is (false? (:graph-adjacency-seed-ids-truncated? instrumentation)))
        (is (= (:graph-edges-loaded instrumentation)
               (:graph-adjacency-loaded-rows instrumentation)))
        (is (every? #(not (neg? (get instrumentation %)))
                    [:load-search-docs-ms
                     :tokenize-ms
                     :lexical-score-ms
                     :fts-index-ms
                     :fts-search-ms
                     :load-edges-ms
                     :graph-expansion-ms
                     :rank-ms
                     :search-total-ms]))))))

(deftest ranked-candidates-supports-rrf-fusion-ablation
  (let [docs [{:target-id "target:a"
               :target-kind :node
               :path "src/a.clj"
               :label "alpha"}
              {:target-id "target:b"
               :target-kind :node
               :path "src/b.clj"
               :label "beta"}
              {:target-id "target:c"
               :target-kind :node
               :path "src/c.clj"
               :label "gamma"}]
        ranked-data (#'query/ranked-candidates
                     {:query-text "unmatched"
                      :query-tokens ["unmatched"]
                      :docs docs
                      :lexical {"target:a" 1.0
                                "target:c" 0.5}
                      :semantic {"target:b" 1.0
                                 "target:a" 0.5}
                      :fts {}
                      :grep {}
                      :neighbor-scores {}
                      :same-label-scores {}
                      :retriever :hybrid
                      :fusion-strategy :rrf
                      :fusion-k 20
                      :limit 3})]
    (is (= :rrf (:fusion-strategy ranked-data)))
    (is (= 20 (:fusion-k ranked-data)))
    (is (= 1 (:fusion-overlap-count ranked-data)))
    (is (= {:exact 0
            :fts 0
            :graph 0
            :grep 0
            :lexical 2
            :same-label 0
            :semantic 2}
           (:fusion-source-counts ranked-data)))
    (is (= #{"target:a" "target:b" "target:c"}
           (set (map :target-id (:ranked ranked-data)))))))

(deftest ranked-candidates-supports-fts-weight-ablation
  (let [docs [{:target-id "target:lexical"
               :target-kind :chunk
               :path "src/lexical.clj"
               :label "lexical"}
              {:target-id "target:fts"
               :target-kind :chunk
               :path "src/fts.clj"
               :label "fts"}]
        ranked-data (#'query/ranked-candidates
                     {:query-text "unmatched"
                      :query-tokens ["unmatched"]
                      :docs docs
                      :lexical {"target:lexical" 0.2}
                      :semantic {}
                      :fts {"target:fts" 1.0}
                      :grep {}
                      :neighbor-scores {}
                      :same-label-scores {}
                      :retriever :lexical
                      :fts-weight 0.1
                      :limit 2})]
    (is (= 0.1 (:fts-weight ranked-data)))
    (is (= 0.1 (get-in ranked-data [:fusion-source-weights :fts])))
    (is (= ["target:lexical" "target:fts"]
           (mapv :target-id (:ranked ranked-data))))))

(deftest ranked-candidates-diversity-rerank-is-opt-in
  (let [docs [{:target-id "target:a"
               :target-kind :chunk
               :path "docs/auth.md"
               :label "a"}
              {:target-id "target:b"
               :target-kind :chunk
               :path "docs/auth.md"
               :label "b"}
              {:target-id "target:c"
               :target-kind :chunk
               :path "docs/runtime.md"
               :label "c"}]
        ranked-data (#'query/ranked-candidates
                     {:query-text "unmatched"
                      :query-tokens ["unmatched"]
                      :docs docs
                      :lexical {"target:a" 1.0
                                "target:b" 0.9
                                "target:c" 0.8}
                      :semantic {}
                      :fts {}
                      :grep {}
                      :neighbor-scores {}
                      :same-label-scores {}
                      :retriever :lexical
                      :diversity-rerank-limit 3
                      :limit 3})]
    (is (:diversity-rerank? ranked-data))
    (is (= ["target:a" "target:c" "target:b"]
           (mapv :target-id (:ranked ranked-data))))
    (is (= [1 3 2]
           (mapv :pre-diversity-rank (:ranked ranked-data))))))

(deftest semantic-query-remains-result-vector
  (store/with-node (temp-dir "ygg-semantic-query-compat-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:auth"
                       :target-id "node:auth"
                       :target-kind :node
                       :file-id "file:auth"
                       :path "src/auth.clj"
                       :kind :var
                       :label "demo/auth"
                       :text "auth login"
                       :tokens ["auth" "login"]
                       :input-sha "auth"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (let [results (query/semantic-query xtdb
                                          "auth"
                                          {:retriever :lexical
                                           :limit 5})]
        (is (vector? results))
        (is (= ["src/auth.clj"] (mapv :path results)))))))

(deftest lexical-query-keeps-node-candidates-when-chunks-dominate
  (store/with-node (temp-dir "ygg-query-kind-candidate-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:chunk"
                       :target-id "chunk:dominant"
                       :target-kind :chunk
                       :file-id "file:dominant"
                       :path "docs/dominant.md"
                       :kind :markdown
                       :label "Dominant docs"
                       :text "alpha beta gamma delta"
                       :tokens ["alpha" "beta" "gamma" "delta"]
                       :input-sha "dominant"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:node"
                       :target-id "node:needle"
                       :target-kind :node
                       :file-id "file:needle"
                       :path "src/needle.clj"
                       :kind :var
                       :label "demo/needle"
                       :text "demo/needle"
                       :tokens ["demo/needle" "demo" "needle"]
                       :input-sha "needle"
                       :source-line 10
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 1]
        (let [results (query/semantic-query
                       xtdb
                       "alpha beta gamma delta needle"
                       {:retriever :lexical
                        :limit 5})]
          (is (some #(and (= :node (:target-kind %))
                          (= "src/needle.clj" (:path %)))
                    results)))))))

(deftest lexical-query-keeps-extracted-kind-candidates-when-node-kind-dominates
  (store/with-node (temp-dir "ygg-query-extracted-kind-xtdb")
    (fn [xtdb]
      (let [symbol-docs (mapv (fn [idx]
                                {:xt/id (str "search-doc:symbol:" idx)
                                 :target-id (str "node:symbol:" idx)
                                 :target-kind :node
                                 :file-id (str "file:symbol:" idx)
                                 :path (str "src/symbol" idx ".clj")
                                 :kind :var
                                 :label (str "demo/symbol" idx)
                                 :text (str "demo/symbol" idx)
                                 :tokens []
                                 :score 1.0
                                 :input-sha (str "symbol-" idx)
                                 :source-line 1
                                 :active? true
                                 :run-id "run"})
                              (range 3))
            config-doc {:xt/id "search-doc:config"
                        :target-id "node:config"
                        :target-kind :node
                        :file-id "file:config"
                        :path "config/runtime.env"
                        :kind :env-var
                        :label "DATABASE_URL"
                        :text "DATABASE_URL"
                        :tokens []
                        :score 0.25
                        :input-sha "config"
                        :source-line 1
                        :active? true
                        :run-id "run"}]
        (store/execute-tx!
         xtdb
         (mapv #(store/put-op (store/table-ref :search-docs) %)
               (conj symbol-docs config-doc))))
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 1]
        (let [results (query/semantic-query
                       xtdb
                       "runtime database configuration"
                       {:retriever :lexical
                        :limit 10})]
          (is (some #(and (= :node (:target-kind %))
                          (= :env-var (:kind %))
                          (= "config/runtime.env" (:path %)))
                    results)))))))

(deftest lexical-query-includes-graph-neighbor-candidates
  (store/with-node (temp-dir "ygg-query-graph-neighbor-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed"
                       :target-id "node:seed"
                       :target-kind :node
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :var
                       :label "demo/seed"
                       :text "alpha beta"
                       :tokens ["alpha" "beta"]
                       :input-sha "seed"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed-chunk"
                       :target-id "chunk:seed"
                       :target-kind :chunk
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :code-definition
                       :label "demo/seed"
                       :text "alpha beta"
                       :tokens ["alpha" "beta"]
                       :input-sha "seed-chunk"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:neighbor"
                       :target-id "node:neighbor"
                       :target-kind :node
                       :file-id "file:neighbor"
                       :path "src/neighbor.clj"
                       :kind :var
                       :label "demo/neighbor"
                       :text "demo/neighbor"
                       :tokens []
                       :input-sha "neighbor"
                       :source-line 5
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:seed:neighbor"
                       :source-id "node:seed"
                       :target-id "node:neighbor"
                       :relation :references
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 0
                    query/default-seed-count 1]
        (let [results (query/semantic-query xtdb
                                            "alpha"
                                            {:retriever :lexical
                                             :limit 5})]
          (is (some #(and (= "src/neighbor.clj" (:path %))
                          (= 1.0 (get-in % [:score-components :graph])))
                    results)))))))

(deftest lexical-query-expands-from-chunk-to-same-label-node-neighbors
  (store/with-node (temp-dir "ygg-query-chunk-graph-neighbor-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed-chunk"
                       :target-id "chunk:seed"
                       :target-kind :chunk
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :code-definition
                       :label "demo/seed"
                       :text "alpha beta"
                       :tokens ["alpha" "beta"]
                       :input-sha "seed-chunk"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed-node"
                       :target-id "node:seed"
                       :target-kind :node
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :var
                       :label "demo/seed"
                       :text "demo/seed"
                       :tokens []
                       :input-sha "seed-node"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:neighbor"
                       :target-id "node:neighbor"
                       :target-kind :node
                       :file-id "file:neighbor"
                       :path "src/neighbor.clj"
                       :kind :var
                       :label "demo/neighbor"
                       :text "demo/neighbor"
                       :tokens []
                       :input-sha "neighbor"
                       :source-line 5
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:seed:neighbor"
                       :source-id "node:seed"
                       :target-id "node:neighbor"
                       :relation :references
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 0
                    query/default-seed-count 1]
        (let [results (query/semantic-query xtdb
                                            "alpha"
                                            {:retriever :lexical
                                             :limit 5})]
          (is (some #(and (= "src/neighbor.clj" (:path %))
                          (= 1.0 (get-in % [:score-components :graph])))
                    results)))))))

(deftest lexical-query-includes-bounded-same-label-doc-candidates
  (store/with-node (temp-dir "ygg-query-same-label-doc-candidates-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed"
                       :target-id "chunk:seed"
                       :target-kind :chunk
                       :file-id "file:seed"
                       :path "src/widget.Partial.cs"
                       :kind :code-definition
                       :label "demo/Widget"
                       :text "alpha beta"
                       :tokens ["alpha" "beta"]
                       :input-sha "seed"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:same-label"
                       :target-id "chunk:same-label"
                       :target-kind :chunk
                       :file-id "file:same-label"
                       :path "src/widget.cs"
                       :kind :code-definition
                       :label "demo/Widget"
                       :text "demo/Widget"
                       :tokens []
                       :input-sha "same-label"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:noise"
                       :target-id "chunk:noise"
                       :target-kind :chunk
                       :file-id "file:noise"
                       :path "src/noise.cs"
                       :kind :code-definition
                       :label "demo/Other"
                       :text "demo/Other"
                       :tokens []
                       :input-sha "noise"
                       :source-line 1
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 0
                    query/default-seed-count 1]
        (let [report (query/search-report xtdb
                                          "alpha"
                                          {:retriever :lexical
                                           :limit 5})
              results (:results report)]
          (is (= 1 (get-in report [:instrumentation :same-label-doc-candidates])))
          (is (some #(and (= "src/widget.cs" (:path %))
                          (= 1.0 (get-in % [:score-components :sameLabel]))
                          (= "same-label candidate" (:reason %)))
                    results))
          (is (not-any? #(= "src/noise.cs" (:path %)) results)))))))

(deftest lexical-query-loads-only-seed-adjacent-edges
  (store/with-node (temp-dir "ygg-query-seed-edge-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:seed"
                       :target-kind :node
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :var
                       :label "demo/seed"
                       :text "alpha"
                       :tokens ["alpha"]
                       :input-sha "seed"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:neighbor"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:neighbor"
                       :target-kind :node
                       :file-id "file:neighbor"
                       :path "src/neighbor.clj"
                       :kind :var
                       :label "demo/neighbor"
                       :text "demo/neighbor"
                       :tokens []
                       :input-sha "neighbor"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:noise"
                       :project-id "fixture"
                       :repo-id "app"
                       :target-id "node:noise"
                       :target-kind :node
                       :file-id "file:noise"
                       :path "src/noise.clj"
                       :kind :var
                       :label "demo/noise"
                       :text "demo/noise"
                       :tokens []
                       :input-sha "noise"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:seed:neighbor"
                       :project-id "fixture"
                       :repo-id "app"
                       :source-id "node:seed"
                       :target-id "node:neighbor"
                       :relation :references
                       :confidence :high
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:noise:other"
                       :project-id "fixture"
                       :repo-id "app"
                       :source-id "node:noise"
                       :target-id "node:other"
                       :relation :references
                       :confidence :high
                       :file-id "file:noise"
                       :path "src/noise.clj"
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-lexical-candidates 1
                    query/default-kind-candidates 0
                    query/default-seed-count 1]
        (let [report (query/search-report xtdb
                                          "alpha"
                                          {:retriever :lexical
                                           :project-id "fixture"
                                           :repo-id "app"
                                           :limit 5})
              results (:results report)]
          (is (= 1 (get-in report [:instrumentation :graph-edges-loaded])))
          (is (= 1 (get-in report [:instrumentation :graph-adjacency-loaded-rows])))
          (is (= 2 (get-in report [:instrumentation :graph-adjacency-query-count])))
          (is (= 1 (get-in report [:instrumentation :graph-adjacency-source-query-count])))
          (is (= 1 (get-in report [:instrumentation :graph-adjacency-target-query-count])))
          (is (some #(and (= "src/neighbor.clj" (:path %))
                          (= 1.0 (get-in % [:score-components :graph])))
                    results)))))))

(deftest deps-and-path-use-bounded-xtdb-lookups
  (store/with-node (temp-dir "ygg-query-navigation-pushdown-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :nodes)
                      {:xt/id "node:alpha"
                       :project-id "pushdown"
                       :repo-id "app"
                       :label "alpha"
                       :kind :namespace
                       :file-id "file:alpha"
                       :path "src/alpha.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :nodes)
                      {:xt/id "node:beta"
                       :project-id "pushdown"
                       :repo-id "app"
                       :label "beta"
                       :kind :namespace
                       :file-id "file:beta"
                       :path "src/beta.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :nodes)
                      {:xt/id "node:gamma"
                       :project-id "pushdown"
                       :repo-id "app"
                       :label "gamma"
                       :kind :namespace
                       :file-id "file:gamma"
                       :path "src/gamma.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :nodes)
                      {:xt/id "node:noise"
                       :project-id "pushdown"
                       :repo-id "app"
                       :label "noise"
                       :kind :namespace
                       :file-id "file:noise"
                       :path "src/noise.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:alpha:beta"
                       :project-id "pushdown"
                       :repo-id "app"
                       :source-id "node:alpha"
                       :target-id "node:beta"
                       :relation :requires
                       :confidence :high
                       :file-id "file:alpha"
                       :path "src/alpha.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:beta:gamma"
                       :project-id "pushdown"
                       :repo-id "app"
                       :source-id "node:beta"
                       :target-id "node:gamma"
                       :relation :requires
                       :confidence :high
                       :file-id "file:beta"
                       :path "src/beta.clj"
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:noise:other"
                       :project-id "pushdown"
                       :repo-id "app"
                       :source-id "node:noise"
                       :target-id "node:other"
                       :relation :requires
                       :confidence :high
                       :file-id "file:noise"
                       :path "src/noise.clj"
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/all-nodes (fn [& _]
                                      (throw (ex-info "Unexpected full node load." {})))
                    query/all-edges (fn [& _]
                                      (throw (ex-info "Unexpected full edge load." {})))]
        (let [scope {:project-id "pushdown"
                     :repo-id "app"}
              deps (query/deps xtdb "alpha" scope)
              path (query/graph-path xtdb "alpha" "gamma" scope)]
          (is (= ["beta"] (mapv (comp :label :target) (:outgoing deps))))
          (is (empty? (:incoming deps)))
          (is (= ["alpha" "beta" "gamma"] (mapv :label path))))))))

(deftest system-path-uses-bounded-xtdb-lookups
  (store/with-node (temp-dir "ygg-query-system-path-pushdown-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :system-nodes)
                      {:xt/id "system:api"
                       :project-id "pushdown"
                       :repo-id "app"
                       :system-key "api"
                       :label "api"
                       :kind :candidate-system
                       :aliases []
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :system-nodes)
                      {:xt/id "system:core"
                       :project-id "pushdown"
                       :repo-id "app"
                       :system-key "core"
                       :label "core"
                       :kind :candidate-system
                       :aliases []
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :system-nodes)
                      {:xt/id "system:noise"
                       :project-id "pushdown"
                       :repo-id "app"
                       :system-key "noise"
                       :label "noise"
                       :kind :candidate-system
                       :aliases []
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :system-edges)
                      {:xt/id "system-edge:api:core"
                       :project-id "pushdown"
                       :repo-id "app"
                       :source-id "system:api"
                       :target-id "system:core"
                       :relation :code-depends-on
                       :evidence-count 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :system-edges)
                      {:xt/id "system-edge:noise:other"
                       :project-id "pushdown"
                       :repo-id "app"
                       :source-id "system:noise"
                       :target-id "system:other"
                       :relation :code-depends-on
                       :evidence-count 1
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/all-system-nodes (fn [& _]
                                             (throw (ex-info "Unexpected full system node load." {})))
                    query/all-system-edges (fn [& _]
                                             (throw (ex-info "Unexpected full system edge load." {})))]
        (is (= ["api" "core"]
               (mapv :label
                     (query/system-path xtdb
                                        "api"
                                        "core"
                                        {:project-id "pushdown"
                                         :repo-id "app"}))))))))

(deftest lexical-query-keeps-strong-lexical-results-ahead-of-weak-graph-neighbors
  (store/with-node (temp-dir "ygg-query-graph-neighbor-rank-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:seed"
                       :target-id "node:seed"
                       :target-kind :node
                       :file-id "file:seed"
                       :path "src/seed.clj"
                       :kind :var
                       :label "demo/seed"
                       :text "alpha"
                       :tokens ["alpha"]
                       :input-sha "seed"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:strong"
                       :target-id "chunk:strong"
                       :target-kind :chunk
                       :file-id "file:strong"
                       :path "src/strong.clj"
                       :kind :code-definition
                       :label "demo/strong"
                       :text "alpha focus focus"
                       :tokens ["alpha" "focus" "focus"]
                       :input-sha "strong"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :search-docs)
                      {:xt/id "search-doc:neighbor"
                       :target-id "node:neighbor"
                       :target-kind :node
                       :file-id "file:neighbor"
                       :path "src/neighbor.clj"
                       :kind :var
                       :label "demo/neighbor"
                       :text "demo/neighbor"
                       :tokens []
                       :input-sha "neighbor"
                       :source-line 1
                       :active? true
                       :run-id "run"})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:seed:neighbor"
                       :source-id "node:seed"
                       :target-id "node:neighbor"
                       :relation :references
                       :active? true
                       :run-id "run"})])
      (with-redefs [query/default-kind-candidates 0
                    query/default-seed-count 2]
        (let [results (query/semantic-query xtdb
                                            "alpha focus"
                                            {:retriever :lexical
                                             :limit 5})
              ranks (into {} (map-indexed (fn [idx row]
                                            [(:path row) (inc idx)])
                                          results))]
          (is (< (get ranks "src/strong.clj")
                 (get ranks "src/neighbor.clj"))))))))

(deftest index-persists-extracted-reference-edges
  (let [xtdb-path (temp-dir "ygg-index-reference-edge-xtdb")
        repo (io/file (temp-dir "ygg-index-reference-edge-repo"))
        src-dir (io/file repo "src" "main" "java" "demo")]
    (.mkdirs src-dir)
    (spit (io/file src-dir "Options.java")
          (str "package demo;\n"
               "class Options {\n"
               "}\n"))
    (spit (io/file src-dir "OptionsMixin.java")
          (str "package demo;\n"
               "class OptionsMixin {\n"
               "  void applyTo(Options options) {\n"
               "  }\n"
               "}\n"))
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb
                           (.getPath repo)
                           {:project-id "reference-edge-test"
                            :repo-id "app"
                            :index-profile :query})
        (let [edges (query/all-edges xtdb {:project-id "reference-edge-test"
                                           :repo-id "app"})
              target-id "project:reference-edge-test:repo:app:node:symbol:demo/Options"]
          (is (some #(and (= :references (:relation %))
                          (= target-id (:target-id %)))
                    edges)))))))

(deftest index-batches-parser-worker-facts-for-changed-files
  (let [xtdb-path (temp-dir "ygg-index-parser-worker-xtdb")
        repo (io/file (temp-dir "ygg-index-parser-worker-repo"))
        src-dir (io/file repo "src" "main" "java" "demo")
        batch-calls (atom [])]
    (.mkdirs src-dir)
    (spit (io/file src-dir "App.java")
          (str "package demo;\n"
               "class App {\n"
               "  Target make() { return new Target(); }\n"
               "}\n"))
    (spit (io/file src-dir "Target.java")
          (str "package demo;\n"
               "class Target {\n"
               "}\n"))
    (store/with-node xtdb-path
      (fn [xtdb]
        (with-redefs [extract/parser-worker-enabled? #(= :java %)
                      extract/parser-worker-batch-facts
                      (fn [files]
                        (swap! batch-calls conj (mapv :path files))
                        {"src/main/java/demo/App.java"
                         {:package "demo"
                          :definitions [{:kind "class"
                                         :name "App"
                                         :line 2}
                                        {:kind "method"
                                         :name "App.make"
                                         :line 3}]
                          :imports []
                          :references [{:source "App.make"
                                        :target "Target"
                                        :kind "type"
                                        :line 3}]
                          :diagnostics []}
                         "src/main/java/demo/Target.java"
                         {:package "demo"
                          :definitions [{:kind "class"
                                         :name "Target"
                                         :line 2}]
                          :imports []
                          :references []
                          :diagnostics []}})]
          (let [summary (index/index-repo! xtdb
                                           (.getPath repo)
                                           {:project-id "parser-worker-test"
                                            :repo-id "app"
                                            :index-profile :query})
                edges (query/all-edges xtdb {:project-id "parser-worker-test"
                                             :repo-id "app"})
                target-id "project:parser-worker-test:repo:app:node:symbol:demo/Target"]
            (is (= 1 (count @batch-calls)))
            (is (= #{"src/main/java/demo/App.java"
                     "src/main/java/demo/Target.java"}
                   (set (first @batch-calls))))
            (is (= 2 (get-in summary [:stats :files-indexed])))
            (is (some #(and (= :references (:relation %))
                            (= target-id (:target-id %)))
                      edges))))))))

(deftest graph-profile-skips-query-rows-and-query-profile-restores-them
  (let [xtdb-path (temp-dir "ygg-index-profile-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [graph-summary (index/index-repo! xtdb repo {:index-profile :graph})
              graph-report (query/report xtdb)]
          (is (= :graph (:index-profile graph-summary)))
          (is (pos? (get-in graph-summary [:stats :nodes])))
          (is (zero? (get-in graph-summary [:stats :chunks])))
          (is (zero? (get-in graph-summary [:stats :search-docs])))
          (is (zero? (get-in graph-report [:counts :search-docs])))
          (let [query-summary (index/index-repo! xtdb repo {:index-profile :query})
                query-report (query/report xtdb)]
            (is (= :query (:index-profile query-summary)))
            (is (pos? (get-in query-summary [:stats :files-indexed])))
            (is (pos? (get-in query-summary [:stats :chunks])))
            (is (pos? (get-in query-summary [:stats :search-docs])))
            (is (pos? (get-in query-report [:counts :search-docs])))))))))

(deftest report-respects-project-scope-and-readable-missing-targets
  (store/with-node (temp-dir "ygg-report-scope-xtdb")
    (fn [xtdb]
      (store/execute-tx!
       xtdb
       [(store/put-op (store/table-ref :nodes)
                      {:xt/id "node:p1:namespace:alpha"
                       :project-id "p1"
                       :label "alpha"
                       :kind :namespace
                       :active? true})
        (store/put-op (store/table-ref :nodes)
                      {:xt/id "node:p2:namespace:beta"
                       :project-id "p2"
                       :label "beta"
                       :kind :namespace
                       :active? true})
        (store/put-op (store/table-ref :edges)
                      {:xt/id "edge:p1:alpha:external"
                       :project-id "p1"
                       :source-id "node:p1:namespace:alpha"
                       :target-id "project:p1:repo:r1:node:namespace:clojure.string"
                       :relation :requires
                       :active? true})])
      (is (= 2 (get-in (query/report xtdb) [:counts :nodes])))
      (is (= 1 (get-in (query/report xtdb {:project-id "p1"}) [:counts :nodes])))
      (is (contains? (set (map (comp :label :node)
                               (:top-nodes (query/report xtdb {:project-id "p1"}))))
                     "clojure.string")))))

(deftest scan-files-respects-gitignore
  (let [repo (io/file (temp-dir "ygg-gitignore-repo"))
        src-dir (io/file repo "src")
        ignored-dir (io/file repo "ignored")]
    (.mkdirs src-dir)
    (.mkdirs ignored-dir)
    (spit (io/file repo ".gitignore") "ignored/\n*.secret.edn\n")
    (spit (io/file src-dir "kept.clj") "(ns kept)")
    (spit (io/file src-dir "scratch.secret.edn") "{:secret true}")
    (spit (io/file ignored-dir "ignored.clj") "(ns ignored)")
    (spit (io/file repo "package-lock.json") "{\"packages\":{}}")
    (.mkdirs (io/file repo "ygg-out"))
    (spit (io/file repo "ygg-out" "report.json") "{}")
    (.mkdirs (io/file repo ".clj-kondo"))
    (spit (io/file repo ".clj-kondo" "config.edn") "{:linters {}}")
    (.mkdirs (io/file repo ".workbench" "repos" "nested" "src"))
    (spit (io/file repo ".workbench" "repos" "nested" "src" "ignored.clj")
          "(ns nested.ignored)")
    (is (zero? (:exit (shell/sh "git" "-C" (.getPath repo) "init"))))
    (let [paths (set (map :path (fs/scan-files repo)))]
      (is (contains? paths "src/kept.clj"))
      (is (not (contains? paths "src/scratch.secret.edn")))
      (is (not (contains? paths "ignored/ignored.clj")))
      (is (contains? paths "package-lock.json"))
      (is (not (contains? paths "ygg-out/report.json")))
      (is (not (contains? paths ".clj-kondo/config.edn")))
      (is (not (contains? paths ".workbench/repos/nested/src/ignored.clj"))))))

(deftest index-derives-and-replaces-import-package-edges
  (let [xtdb-path (temp-dir "ygg-dependency-xtdb")
        repo (io/file (temp-dir "ygg-dependency-repo"))
        src-dir (io/file repo "src")]
    (.mkdirs src-dir)
    (spit (io/file repo "package.json")
          "{\"name\":\"demo\",\"dependencies\":{\"react\":\"^19.0.0\",\"lodash\":\"^4.17.0\"}}\n")
    (spit (io/file repo "package-lock.json")
          (str "{\"packages\":{"
               "\"node_modules/react\":{\"version\":\"19.1.0\"},"
               "\"node_modules/nested/node_modules/react\":{\"version\":\"18.3.1\"},"
               "\"node_modules/lodash\":{\"version\":\"4.17.21\"}"
               "}}\n"))
    (spit (io/file src-dir "app.ts")
          "import runtime from 'react/jsx-runtime';\nexport const value = runtime;\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [first-summary (index/index-repo! xtdb
                                               (.getPath repo)
                                               {:project-id "dep-test"
                                                :repo-id "app"})
              deps (query/deps xtdb "src.app" {:project-id "dep-test"
                                               :repo-id "app"})
              package-edges (filter #(= :imports-package (:relation %))
                                    (:outgoing deps))
              report (dependency/package-report xtdb
                                                {:project-id "dep-test"
                                                 :repo-id "app"}
                                                {})
              npm-report (dependency/package-report xtdb
                                                    {:project-id "dep-test"
                                                     :repo-id "app"}
                                                    {:ecosystem "npm"})
              react-report (dependency/package-report xtdb
                                                      {:project-id "dep-test"
                                                       :repo-id "app"}
                                                      {:package "react"})
              conflict-report (dependency/package-report xtdb
                                                         {:project-id "dep-test"
                                                          :repo-id "app"}
                                                         {:with-conflicts? true})
              no-import-report (dependency/package-report xtdb
                                                          {:project-id "dep-test"
                                                           :repo-id "app"}
                                                          {:without-import-evidence? true})
              deps-graph (graph/deps-graph xtdb
                                           "npm:react"
                                           {:project-id "dep-test"
                                            :repo-id "app"})
              package-by-label (into {} (map (juxt :label identity)
                                             (:packages report)))]
          (is (= 1 (get-in first-summary [:stats :dependency-edges])))
          (is (= ["npm:react"] (mapv (comp :label :target) package-edges)))
          (is (= [:declared] (mapv :resolution-source package-edges)))
          (is (= [:typescript] (mapv :source-kind package-edges)))
          (is (= 2 (get-in report [:counts :packages])))
          (is (= 3 (get-in report [:counts :versions])))
          (is (= 1 (get-in report [:counts :imports-package])))
          (is (= ["npm:lodash"]
                 (mapv :label (:declared-without-import-evidence report))))
          (is (= ["18.3.1" "19.1.0"]
                 (-> report :version-conflicts first :versions)))
          (is (= ["src/app.ts"]
                 (mapv :path (get-in package-by-label ["npm:react" :imported-by]))))
          (is (= [:typescript]
                 (mapv :kind (get-in package-by-label ["npm:react" :imported-by]))))
          (is (= ["npm:lodash" "npm:react"]
                 (mapv :label (:packages npm-report))))
          (is (= ["npm:react"] (mapv :label (:packages react-report))))
          (is (= ["npm:react"] (mapv :label (:packages conflict-report))))
          (is (= ["npm:lodash"] (mapv :label (:packages no-import-report))))
          (is (contains? (set (map :label (:nodes deps-graph))) "package-lock.json"))
          (is (contains? (set (map :label (:nodes deps-graph))) "npm:react@19.1.0"))
          (is (contains? (set (map :label (:nodes deps-graph))) "src.app"))
          (is (= #{:imports-package :requires :resolves :version-of}
                 (set (map (comp keyword :relation) (:edges deps-graph)))))
          (spit (io/file src-dir "app.ts")
                "import './local';\nexport const value = 1;\n")
          (let [second-summary (index/index-repo! xtdb
                                                  (.getPath repo)
                                                  {:project-id "dep-test"
                                                   :repo-id "app"})
                next-deps (query/deps xtdb "src.app" {:project-id "dep-test"
                                                      :repo-id "app"})]
            (is (zero? (get-in second-summary [:stats :dependency-edges])))
            (is (empty? (filter #(= :imports-package (:relation %))
                                (:outgoing next-deps))))))))))

(deftest index-resolves-python-imports-through-explicit-pyproject-import-names
  (let [xtdb-path (temp-dir "ygg-python-dependency-xtdb")
        repo (io/file (temp-dir "ygg-python-dependency-repo"))
        src-dir (io/file repo "src")]
    (.mkdirs src-dir)
    (spit (io/file repo "pyproject.toml")
          (str "[project]\n"
               "name = \"demo\"\n"
               "dependencies = [\"beautifulsoup4>=4\"]\n\n"
               "[tool.ygg.import-names]\n"
               "beautifulsoup4 = [\"bs4\"]\n"))
    (spit (io/file src-dir "app.py")
          "import bs4\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo! xtdb
                                         (.getPath repo)
                                         {:project-id "py-dep-test"
                                          :repo-id "app"})
              deps (query/deps xtdb "app" {:project-id "py-dep-test"
                                           :repo-id "app"})
              package-edges (filter #(= :imports-package (:relation %))
                                    (:outgoing deps))
              report (dependency/package-report xtdb
                                                {:project-id "py-dep-test"
                                                 :repo-id "app"}
                                                {})]
          (is (= 1 (get-in summary [:stats :dependency-edges])))
          (is (= ["pypi:beautifulsoup4"] (mapv (comp :label :target) package-edges)))
          (is (= [:manifest-import-name] (mapv :resolution-source package-edges)))
          (is (empty? (:unresolved-imports report))))))))

(deftest index-resolves-python-imports-through-doc-pip-install-evidence
  (let [xtdb-path (temp-dir "ygg-python-doc-dependency-xtdb")
        repo (io/file (temp-dir "ygg-python-doc-dependency-repo"))
        testinfra-dir (io/file repo "testinfra")]
    (.mkdirs testinfra-dir)
    (spit (io/file testinfra-dir "README.md")
          (str "# Testinfra\n\n"
               "pip3 install boto3 ec2instanceconnectcli "
               "pytest-testinfra[paramiko,docker] requests\n"))
    (spit (io/file testinfra-dir "test_ami_nix.py")
          (str "import boto3\n"
               "import requests\n"
               "import testinfra\n"
               "from ec2instanceconnectcli.EC2InstanceConnectKey "
               "import EC2InstanceConnectKey\n"))
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo! xtdb
                                         (.getPath repo)
                                         {:project-id "py-doc-dep-test"
                                          :repo-id "app"})
              deps (query/deps xtdb
                               "testinfra.test_ami_nix"
                               {:project-id "py-doc-dep-test"
                                :repo-id "app"})
              package-edges (filter #(= :imports-package (:relation %))
                                    (:outgoing deps))
              report (dependency/package-report xtdb
                                                {:project-id "py-doc-dep-test"
                                                 :repo-id "app"}
                                                {})
              corrected-report (dependency/package-report
                                xtdb
                                {:project-id "py-doc-dep-test"
                                 :repo-id "app"}
                                {:correction-overlay
                                 {:packageImports
                                  [{:repo "app"
                                    :import "testinfra"
                                    :ecosystem "pypi"
                                    :package-name "pytest-testinfra"}]}})]
          (is (= 3 (get-in summary [:stats :dependency-edges])))
          (is (= #{"pypi:boto3" "pypi:ec2instanceconnectcli" "pypi:requests"}
                 (set (map (comp :label :target) package-edges))))
          (is (= #{:package-name}
                 (set (map :resolution-source package-edges))))
          (is (= ["testinfra"] (mapv :import (:unresolved-imports report))))
          (is (empty? (:unresolved-imports corrected-report))))))))

(deftest index-resolves-jvm-imports-through-maven-coordinates
  (let [xtdb-path (temp-dir "ygg-jvm-dependency-xtdb")
        repo (io/file (temp-dir "ygg-jvm-dependency-repo"))
        src-dir (io/file repo "src" "main" "java" "demo")]
    (.mkdirs src-dir)
    (spit (io/file repo "pom.xml")
          (str "<project><dependencies><dependency>"
               "<groupId>org.slf4j</groupId>"
               "<artifactId>slf4j-api</artifactId>"
               "<version>2.0.0</version>"
               "</dependency></dependencies></project>\n"))
    (spit (io/file src-dir "App.java")
          "package demo;\nimport org.slf4j.Logger;\nclass App {}\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [raw-summary (index/index-repo! xtdb
                                             (.getPath repo)
                                             {:project-id "jvm-dep-test"
                                              :repo-id "app"})
              raw-report (dependency/package-report xtdb
                                                    {:project-id "jvm-dep-test"
                                                     :repo-id "app"}
                                                    {})
              correction-overlay {:schema "ygg.correction-overlay/v1"
                                  :project "jvm-dep-test"
                                  :systems []
                                  :reject []
                                  :edges []
                                  :docs []
                                  :packageImports [{:import "org.slf4j"
                                                    :ecosystem "maven"
                                                    :package "org.slf4j:slf4j-api"
                                                    :status "accepted"}]}
              mapped-report-before-sync (dependency/package-report xtdb
                                                                   {:project-id "jvm-dep-test"
                                                                    :repo-id "app"}
                                                                   {:correction-overlay correction-overlay})
              mapped-before-package-by-label (into {}
                                                   (map (juxt :label identity)
                                                        (:packages mapped-report-before-sync)))
              mapped-summary (index/index-repo! xtdb
                                                (.getPath repo)
                                                {:project-id "jvm-dep-test"
                                                 :repo-id "app"
                                                 :correction-overlay correction-overlay})
              deps (query/deps xtdb "demo" {:project-id "jvm-dep-test"
                                            :repo-id "app"})
              package-edges (filter #(= :imports-package (:relation %))
                                    (:outgoing deps))
              mapped-report (dependency/package-report xtdb
                                                       {:project-id "jvm-dep-test"
                                                        :repo-id "app"}
                                                       {:correction-overlay correction-overlay})]
          (is (= 1 (get-in raw-summary [:stats :dependency-edges])))
          (is (= 1 (get-in raw-report [:counts :imports-package])))
          (is (empty? (:unresolved-imports raw-report)))
          (is (not-any? #(= :dependency-review (:kind %))
                        (:nextActions raw-report)))
          (is (= 1 (get-in mapped-report-before-sync [:counts :imports-package])))
          (is (empty? (:unresolved-imports mapped-report-before-sync)))
          (is (empty? (:declared-without-import-evidence mapped-report-before-sync)))
          (is (= ["src/main/java/demo/App.java"]
                 (mapv :path
                       (get-in mapped-before-package-by-label
                               ["maven:org.slf4j:slf4j-api" :imported-by]))))
          (is (= [:maven-coordinate-prefix]
                 (mapv :resolution-source
                       (get-in mapped-before-package-by-label
                               ["maven:org.slf4j:slf4j-api" :imported-by]))))
          (is (= 1 (get-in mapped-summary [:stats :dependency-edges])))
          (is (= ["maven:org.slf4j:slf4j-api"]
                 (mapv (comp :label :target) package-edges)))
          (is (= [:correction-overlay] (mapv :resolution-source package-edges)))
          (is (empty? (:unresolved-imports mapped-report)))
          (is (not-any? #(= :dependency-review (:kind %))
                        (:nextActions mapped-report))))))))

(deftest index-resolves-rust-underscore-crate-imports
  (let [xtdb-path (temp-dir "ygg-rust-dependency-xtdb")
        repo (io/file (temp-dir "ygg-rust-dependency-repo"))
        src-dir (io/file repo "src")]
    (.mkdirs src-dir)
    (spit (io/file repo "Cargo.toml")
          "[package]\nname = \"demo\"\n\n[dependencies]\nserde_json = \"1\"\n")
    (spit (io/file src-dir "lib.rs")
          "use serde_json::Value;\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo! xtdb
                                         (.getPath repo)
                                         {:project-id "rust-dep-test"
                                          :repo-id "app"})
              report (dependency/package-report xtdb
                                                {:project-id "rust-dep-test"
                                                 :repo-id "app"}
                                                {})
              package-by-label (into {} (map (juxt :label identity)
                                             (:packages report)))]
          (is (= 1 (get-in summary [:stats :dependency-edges])))
          (is (= ["src/lib.rs"]
                 (mapv :path
                       (get-in package-by-label ["cargo:serde_json" :imported-by])))))))))

(deftest index-writes-temporal-source-snapshots
  (let [xtdb-path (temp-dir "ygg-index-temporal-xtdb")
        repo (io/file (temp-dir "ygg-index-temporal-repo"))
        src (io/file repo "src" "demo")]
    (.mkdirs src)
    (spit (io/file src "a.clj") "(ns demo.a (:require [demo.b :as b]))\n(defn call [] (b/value))\n")
    (spit (io/file src "b.clj") "(ns demo.b)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [clock (atom 1767225600000)]
          (with-redefs [index/now-ms (fn []
                                       (let [value @clock]
                                         (swap! clock + 1000)
                                         value))]
            (let [first-summary (index/index-repo! xtdb (.getPath repo) {})
                  first-valid (:valid-from first-summary)]
              (spit (io/file src "a.clj") "(ns demo.a)\n(defn call [] 1)\n")
              (.delete (io/file src "b.clj"))
              (let [second-summary (index/index-repo! xtdb (.getPath repo) {})
                    second-valid (:valid-from second-summary)]
                (is (= :synthetic
                       (:basis-kind (store/row-by-id xtdb
                                                     (store/table-ref :source-snapshots)
                                                     (:snapshot-id first-summary)
                                                     {:valid-at first-valid}))))
                (is (= :completed
                       (:status (store/row-by-id xtdb
                                                 (store/table-ref :index-runs)
                                                 (:run-id second-summary)
                                                 {:valid-at second-valid}))))
                (is (seq (:outgoing (query/deps xtdb
                                                "demo.a"
                                                {:read-context {:valid-at first-valid}}))))
                (is (empty? (:outgoing (query/deps xtdb
                                                   "demo.a"
                                                   {:read-context {:valid-at second-valid}}))))
                (is (some? (query/find-node xtdb
                                            "demo.b"
                                            {:read-context {:valid-at first-valid}})))
                (is (nil? (query/find-node xtdb
                                           "demo.b"
                                           {:read-context {:valid-at second-valid}})))))))))))

(deftest index-skips-by-content-and-extractor-fingerprint
  (let [xtdb-path (temp-dir "ygg-index-fingerprint-xtdb")
        repo (io/file (temp-dir "ygg-index-fingerprint-repo"))
        src (io/file repo "src")]
    (.mkdirs src)
    (spit (io/file src "demo.clj") "(ns demo)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [first-summary (index/index-repo! xtdb (.getPath repo) {})
              second-summary (index/index-repo! xtdb (.getPath repo) {})
              row (store/file-row xtdb "src/demo.clj")]
          (is (= 1 (get-in first-summary [:stats :files-indexed])))
          (is (pos? (get-in first-summary [:stats :timings-ms :total-ms])))
          (is (contains? (get-in first-summary [:stats :timings-ms])
                         :scan-ms))
          (is (contains? (get-in first-summary [:stats :timings-ms])
                         :extract-ms))
          (let [code-extraction (some #(when (= :code (:kind %)) %)
                                      (get-in first-summary
                                              [:stats :extraction :by-kind]))]
            (is (= 1 (:files code-extraction)))
            (is (not (neg? (:elapsed-ms code-extraction)))))
          (is (= 1 (get-in second-summary [:stats :files-skipped])))
          (is (= 1 (get-in second-summary [:stats :files-reused])))
          (is (string? (:extractor-fingerprint row)))
          (with-redefs [index/extractor-fingerprint (fn [& _]
                                                      "extractor:test-changed")]
            (let [third-summary (index/index-repo! xtdb (.getPath repo) {})
                  changed-row (store/file-row xtdb "src/demo.clj")]
              (is (= 1 (get-in third-summary [:stats :files-indexed])))
              (is (= "extractor:test-changed"
                     (:extractor-fingerprint changed-row))))))))))

(deftest index-skips-derived-dependency-refresh-when-inputs-are-unchanged
  (let [xtdb-path (temp-dir "ygg-index-dependency-refresh-xtdb")
        repo (io/file (temp-dir "ygg-index-dependency-refresh-repo"))
        calls (atom [])]
    (.mkdirs repo)
    (spit (io/file repo "deps.edn") "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}}\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (with-redefs [dependency/refresh-derived-edges!
                      (fn [& args]
                        (swap! calls conj args)
                        {:dependency-edges 0})]
          (let [opts {:project-id "fixture"
                      :repo-id "app"}
                first-summary (index/index-repo! xtdb (.getPath repo) opts)]
            (is (= 1 (get-in first-summary [:stats :files-indexed])))
            (is (= 1 (count @calls)))
            (let [second-summary (index/index-repo! xtdb (.getPath repo) opts)
                  correction-overlay {:schema "ygg.correction-overlay/v1"
                                      :project "fixture"
                                      :packageImports [{:repo "app"
                                                        :import "clojure"
                                                        :ecosystem "maven"
                                                        :package "org.clojure:clojure"
                                                        :status "accepted"}]}]
              (is (= 1 (get-in second-summary [:stats :files-skipped])))
              (is (= 1 (count @calls)))
              (is (not (contains? (get-in second-summary [:stats :timings-ms])
                                  :dependency-ms)))
              (let [third-summary (index/index-repo! xtdb
                                                     (.getPath repo)
                                                     (assoc opts
                                                            :correction-overlay correction-overlay))]
                (is (= 1 (get-in third-summary [:stats :files-skipped])))
                (is (= 2 (count @calls)))
                (is (= correction-overlay
                       (:correction-overlay (nth (last @calls) 4))))))))))))

(deftest index-reindexes-when-file-facts-contract-changes
  (let [xtdb-path (temp-dir "ygg-index-file-facts-fingerprint-xtdb")
        repo (io/file (temp-dir "ygg-index-file-facts-fingerprint-repo"))]
    (.mkdirs repo)
    (spit (io/file repo ".env") "DATABASE_URL=postgres://example\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [first-summary (index/index-repo! xtdb (.getPath repo) {})
              second-summary (index/index-repo! xtdb (.getPath repo) {})]
          (is (= 1 (get-in first-summary [:stats :files-indexed])))
          (is (= 1 (get-in second-summary [:stats :files-skipped])))
          (is (= 1 (get-in second-summary [:stats :files-reused])))
          (with-redefs [file-facts/facts-contract-version "ygg.file-facts/test-changed"]
            (let [third-summary (index/index-repo! xtdb (.getPath repo) {})
                  changed-row (store/file-row xtdb ".env")]
              (is (= 1 (get-in third-summary [:stats :files-indexed])))
              (is (string? (:extractor-fingerprint changed-row))))))))))
