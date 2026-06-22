(ns ygg.benchmark-hints-progressive-test
  (:require [ygg.benchmark-hints-progressive :as progressive]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest full-hints-path-keeps-agent-context-directory
  (is (str/ends-with?
       (str (progressive/full-hints-path
             "out/cases/case-1/agent-contexts/codex.ygg-hints.json"))
       "out/cases/case-1/agent-contexts/codex.ygg-hints.full.json")))

(deftest compact-agent-hints-keeps-ranked-starting-points-and-expansion-paths
  (let [hints {:schema "ygg.benchmark.agent-hints/v1"
               :suite-id "suite"
               :case-id "case-1"
               :repo-id "repo"
               :project-id "project"
               :query "proxy request fails"
               :topFiles [{:rank 1
                           :path "lib/adapters/http.js"
                           :confidence 1.0
                           :reason (apply str (repeat 30 "proxy "))
                           :evidence ["context-doc:lib/adapters/http.js"
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
               :topSymbols [{:rank 1
                             :name "setProxy"
                             :path "lib/adapters/http.js"
                             :kind "function"
                             :reason "symbol evidence"
                             :evidence ["context-doc:lib/adapters/http.js"]}]
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
                  :limits {:top-files 1
                           :top-symbols 1
                           :top-docs 1
                           :candidate-systems 1
                           :commands 1
                           :audit-scopes 1
                           :evidence-per-row 1
                           :reason-chars 40}})]
    (is (= "compact-v1" (get-in compact [:progressive :profile])))
    (is (str/ends-with? (get-in compact [:progressive :contextPath])
                        "/tmp/context.json"))
    (is (str/ends-with? (get-in compact [:progressive :fullHintsPath])
                        "/tmp/hints.full.json"))
    (is (= {:topFiles 2
            :topSymbols 1
            :topDocs 1
            :candidateSystems 1
            :commands 2
            :auditScopes 0}
           (get-in compact [:progressive :sourceCounts])))
    (is (= ["lib/adapters/http.js"] (mapv :path (:topFiles compact))))
    (is (= 2 (get-in compact [:topFiles 0 :evidenceCount])))
    (is (= ["context-doc:lib/adapters/http.js"]
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
    (is (= ["rg proxy lib/adapters/http.js"] (:commands compact)))))
