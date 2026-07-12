(ns ygg.benchmark-hints-progressive-test
  (:require [ygg.benchmark-hints-progressive :as progressive]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest full-hints-path-keeps-agent-context-directory
  (is (str/ends-with?
       (str (progressive/full-hints-path
             "out/cases/case-1/agent-contexts/codex.ygg-hints.json"))
       "out/cases/case-1/agent-contexts/codex.ygg-hints.full.json")))

(deftest compact-agent-hints-keeps-ranked-starting-points-and-expansion-paths
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "ygg-bench-hints-progressive"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture-file (io/file root "lib/adapters/http.js")
        related-file (io/file root "lib/core/connection.js")
        _ (.mkdirs (.getParentFile fixture-file))
        _ (.mkdirs (.getParentFile related-file))
        _ (spit fixture-file
                (str/join
                 "\n"
                 (map (fn [line-no]
                        (case line-no
                          24 "export function setProxy(config) {"
                          25 "  return config.proxy;"
                          26 "}"
                          (str "// filler " line-no)))
                      (range 1 41))))
        _ (spit related-file
                (str/join
                 "\n"
                 (map (fn [line-no]
                        (case line-no
                          10 "export function connect() {"
                          11 "  return true;"
                          12 "}"
                          (str "// related " line-no)))
                      (range 1 25))))
        hints {:schema "ygg.benchmark.agent-hints/v1"
               :suite-id "suite"
               :case-id "case-1"
               :repo-id "repo"
               :project-id "project"
               :query "proxy request fails"
               :topFiles [{:rank 1
                           :path "lib/adapters/http.js"
                           :confidence 1.0
                           :reason (apply str (repeat 30 "proxy "))
                           :evidence ["context-doc:lib/adapters/http.js lines 24-26"
                                      "architecture-evidence:lib/adapters/http.js"]}
                          {:rank 2
                           :path "tests/unit/adapters/http.test.js"
                           :confidence 0.9
                           :reason "test evidence"
                           :evidence ["context-doc:tests/unit/adapters/http.test.js"]}]
               :topDocs [{:rank 1
                          :path "lib/adapters/http.js"
                          :heading "http adapter"
                          :snippet "long code snippet"}]
               :relatedFiles [{:rank 1
                               :path "lib/core/connection.js"
                               :relation "imports-package"
                               :sourceLine 10
                               :evidence ["source-graph: lib/adapters/http.js imports lib/core line 3"]}]
               :importPackages [{:rank 1
                                 :packagePrefix "lib/core"
                                 :target "lib/core"
                                 :relation "imports-package"
                                 :seedPaths ["lib/adapters/http.js"]
                                 :evidence ["source-graph: lib/adapters/http.js imports lib/core line 3"]
                                 :files [{:path "lib/core/connection.js"
                                          :kind "namespace"}
                                         {:path "lib/core/pool.js"
                                          :kind "namespace"}]}]
               :preparedLocalization
               {:basis "mechanical prepared localization candidates from parser/source declarations"
                :candidates [{:rank 1
                              :path "lib/adapters/http.js"
                              :confidence 0.9
                              :reason (apply str (repeat 30 "prepared "))
                              :metrics {:declarationCount 1}
                              :evidence ["prepared-declaration:lib/adapters/http.js lines 24-26 kind=function label=\"setProxy\""
                                         "prepared-top-file:lib/adapters/http.js rank=1"]
                              :declarations [{:rank 1
                                              :path "lib/adapters/http.js"
                                              :label "setProxy"
                                              :kind "function"
                                              :sourceLine 24
                                              :endLine 26
                                              :matchedTokens ["proxy"]}]}
                             {:rank 2
                              :path "tests/unit/adapters/http.test.js"
                              :confidence 0.4
                              :reason "test candidate"
                              :metrics {:declarationCount 0}
                              :evidence ["prepared-file-candidate:tests/unit/adapters/http.test.js rank=2"]
                              :declarations []}]}
               :topSymbols [{:rank 1
                             :name "setProxy"
                             :path "lib/adapters/http.js"
                             :kind "function"
                             :reason "symbol evidence"
                             :evidence ["context-doc:lib/adapters/http.js"]}]
               :topDeclarations [{:rank 1
                                  :sourceRank 2
                                  :path "lib/core/connection.js"
                                  :label "connect"
                                  :kind "function"
                                  :sourceLine 10
                                  :matchedTokens ["request"]
                                  :supportLabels ["connection pool"
                                                  "proxy request"]
                                  :evidence ["source-declaration:lib/core/connection.js lines 10 kind=function label=\"connect\""]}]
               :candidateSystems [{:rank 1
                                   :id "system:http"
                                   :path "lib/adapters"
                                   :metrics {:file-count 4}}]
               :commands ["rg proxy lib/adapters/http.js"
                          "sed -n '1,80p' lib/adapters/http.js"]
               :architecture {:summary {:counts {:runtimeEvidence 2}}
                              :dependencyEvidence [{:path "lib/adapters/http.js"}]
                              :nextActions [{:kind "inspect"
                                             :target "system:http"
                                             :command "ygg node system:http"}]}
               :evidence {:status "weak"
                          :counts {:available 1}
                          :retrieval {:large "omitted"}}
               :sourceCoverage {:totals {:files 2}
                                :diagnostics {:large "omitted"}}
               :selection {:limit 20}
               :warnings ["candidate files trimmed"]}
        compact (progressive/compact-agent-hints
                 hints
                 {:context-path "/tmp/context.json"
                  :full-hints-path "/tmp/hints.full.json"
                  :root root
                  :limits {:top-files 1
                           :top-symbols 1
                           :top-docs 1
                           :related-files 1
                           :import-packages 1
                           :import-package-files 1
                           :prepared-localization-candidates 1
                           :prepared-localization-declarations 1
                           :candidate-systems 1
                           :commands 1
                           :audit-scopes 1
                           :evidence-per-row 1
                           :reason-chars 40
                           :snippet-before-lines 2
                           :snippet-after-lines 1}})]
    (is (= "compact-v1" (get-in compact [:progressive :profile])))
    (is (str/ends-with? (get-in compact [:progressive :contextPath])
                        "/tmp/context.json"))
    (is (str/ends-with? (get-in compact [:progressive :fullHintsPath])
                        "/tmp/hints.full.json"))
    (is (= {:topFiles 2
            :topSymbols 1
            :topDeclarations 1
            :topDocs 1
            :relatedFiles 1
            :importPackages 1
            :preparedLocalization 2
            :candidateSystems 1
            :commands 2
            :auditScopes 0}
           (get-in compact [:progressive :sourceCounts])))
    (is (= ["lib/adapters/http.js"] (mapv :path (:topFiles compact))))
    (is (= ["lib/core/connection.js"] (mapv :path (:relatedFiles compact))))
    (is (= [{:rank 1
             :sourceRank 2
             :path "lib/core/connection.js"
             :label "connect"
             :kind "function"
             :sourceLine 10
             :matchedTokens ["request"]
             :supportLabels ["connection pool" "proxy request"]
             :evidence ["source-declaration:lib/core/connection.js lines 10 kind=function label=\"connect\""]}]
           (:topDeclarations compact)))
    (is (= [{:rank 1
             :packagePrefix "lib/core"
             :target "lib/core"
             :relation "imports-package"
             :seedPaths ["lib/adapters/http.js"]
             :evidence ["source-graph: lib/adapters/http.js imports lib/core line 3"]
             :files [{:path "lib/core/connection.js"
                      :kind "namespace"}]}]
           (:importPackages compact)))
    (is (= [{:rank 1
             :path "lib/adapters/http.js"
             :confidence 0.9
             :reason "prepared prepared prepared [truncated]"
             :evidence ["prepared-declaration:lib/adapters/http.js lines 24-26 kind=function label=\"setProxy\""]
             :evidenceCount 2
             :declarations [{:rank 1
                             :path "lib/adapters/http.js"
                             :label "setProxy"
                             :kind "function"
                             :sourceLine 24
                             :endLine 26
                             :matchedTokens ["proxy"]}]
             :metrics {:declarationCount 1}}]
           (get-in compact [:preparedLocalization :candidates])))
    (is (= 2 (get-in compact [:topFiles 0 :evidenceCount])))
    (is (= ["context-doc:lib/adapters/http.js lines 24-26"]
           (get-in compact [:topFiles 0 :evidence])))
    (is (str/ends-with? (get-in compact [:topFiles 0 :reason])
                        "[truncated]"))
    (is (= true (get-in compact [:topDocs 0 :snippetAvailable])))
    (is (not (contains? (get-in compact [:topDocs 0]) :snippet)))
    (is (= [{:kind "inspect"
             :target "system:http"
             :command "ygg node system:http"}]
           (get-in compact [:architecture :nextActions])))
    (is (= {:totals {:files 2}}
           (:sourceCoverage compact)))
    (is (= {:status "weak"
            :counts {:available 1}}
           (:evidence compact)))
    (is (= "lib/adapters/http.js"
           (get-in compact [:readPlan :snippets 0 :path])))
    (is (= {:start 22
            :end 27}
           (get-in compact [:readPlan :snippets 0 :lines])))
    (is (= "lib/core/connection.js"
           (get-in compact [:readPlan :snippets 1 :path])))
    (is (= {:start 8
            :end 11}
           (get-in compact [:readPlan :snippets 1 :lines])))
    (is (str/includes? (get-in compact [:readPlan :snippets 0 :command])
                       "sed -n"))
    (is (str/includes? (get-in compact [:readPlan :snippets 0 :snippet])
                       "24: export function setProxy(config) {"))
    (is (some #(str/includes? % "Do not print entire Yggdrasil JSON")
              (get-in compact [:readPlan :rules])))
    (is (= ["rg proxy lib/adapters/http.js"] (:commands compact)))))

(deftest compact-read-plan-uses-path-diverse-declaration-snippets
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "ygg-bench-hints-progressive-read-plan"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        main-file (io/file root "src/main.clj")
        second-file (io/file root "src/second.clj")
        a-file (io/file root "src/a.clj")
        b-file (io/file root "src/b.clj")
        write-lines! (fn [file markers]
                       (.mkdirs (.getParentFile file))
                       (spit file
                             (str/join
                              "\n"
                              (map (fn [line-no]
                                     (get markers line-no
                                          (str ";; filler " line-no)))
                                   (range 1 101)))))]
    (write-lines! main-file {50 "(defn main-entry [] :main)"})
    (write-lines! second-file {60 "(defn second-entry [] :second)"})
    (write-lines! a-file {20 "(defn earlier [] :a)"
                          80 "(defn later [] :a)"})
    (write-lines! b-file {40 "(defn other [] :b)"})
    (let [compact (progressive/compact-agent-hints
                   {:schema "ygg.benchmark.agent-hints/v1"
                    :topFiles [{:rank 1
                                :path "src/main.clj"
                                :sourceLine 50}
                               {:rank 2
                                :path "src/second.clj"
                                :sourceLine 60}]
                    :topDeclarations [{:rank 1
                                       :path "src/a.clj"
                                       :label "later"
                                       :kind "function"
                                       :sourceLine 80}
                                      {:rank 2
                                       :path "src/a.clj"
                                       :label "earlier"
                                       :kind "function"
                                       :sourceLine 20}
                                      {:rank 3
                                       :path "src/b.clj"
                                       :label "other"
                                       :kind "function"
                                       :sourceLine 40}]}
                   {:root root
                    :limits {:read-plan-files 3
                             :snippet-before-lines 1
                             :snippet-after-lines 1
                             :snippet-max-chars 1000}})
          snippets (get-in compact [:readPlan :snippets])]
      (is (= ["src/main.clj" "src/second.clj" "src/a.clj"]
             (mapv :path snippets)))
      (is (= {:start 59
              :end 61}
             (get-in snippets [1 :lines])))
      (is (str/includes? (get-in snippets [1 :snippet])
                         "60: (defn second-entry [] :second)"))
      (is (= {:start 19
              :end 21}
             (get-in snippets [2 :lines])))
      (is (str/includes? (get-in snippets [2 :snippet])
                         "20: (defn earlier [] :a)")))))

(deftest compact-read-plan-starts-with-prepared-declaration-block
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "ygg-bench-prepared-read-plan"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        target-file (io/file root "modules/vpc-endpoints/main.tf")
        root-file (io/file root "main.tf")
        write-lines! (fn [file marker-line marker]
                       (.mkdirs (.getParentFile file))
                       (spit file
                             (str/join
                              "\n"
                              (map (fn [line-no]
                                     (if (= marker-line line-no)
                                       marker
                                       (str "# filler " line-no)))
                                   (range 1 81)))))]
    (write-lines! target-file 32 "resource \"aws_vpc_endpoint\" \"this\" {")
    (write-lines! root-file 4 "resource \"aws_vpc\" \"this\" {")
    (let [compact (progressive/compact-agent-hints
                   {:schema "ygg.benchmark.agent-hints/v1"
                    :preparedLocalization
                    {:candidates [{:rank 1
                                   :path "modules/vpc-endpoints/main.tf"
                                   :reason "compound identifier match"
                                   :declarations [{:rank 7
                                                   :path "modules/vpc-endpoints/main.tf"
                                                   :label "aws_vpc_endpoint.this"
                                                   :kind "terraform-resource"
                                                   :sourceLine 32}]}]}
                    :topFiles [{:rank 1
                                :path "main.tf"
                                :sourceLine 4}
                               {:rank 2
                                :path "modules/vpc-endpoints/main.tf"
                                :sourceLine 1}]}
                   {:root root
                    :limits {:read-plan-files 2
                             :snippet-before-lines 2
                             :snippet-after-lines 3
                             :snippet-max-chars 1000}})
          snippets (get-in compact [:readPlan :snippets])]
      (is (= ["modules/vpc-endpoints/main.tf" "main.tf"]
             (mapv :path snippets)))
      (is (= {:start 30
              :end 38}
             (get-in snippets [0 :lines])))
      (is (str/includes? (get-in snippets [0 :snippet])
                         "32: resource \"aws_vpc_endpoint\" \"this\" {")))))
