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
