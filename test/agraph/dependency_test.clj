(ns agraph.dependency-test
  (:require [agraph.dependency :as dependency]
            [agraph.xtdb :as store]
            [clojure.test :refer [deftest is]]))

(deftest import-package-resolution-reads-repo-scoped-rows
  (let [row-queries (atom [])
        xtql-queries (atom [])]
    (with-redefs [store/rows-by-field (fn [_ table field value]
                                        (swap! row-queries conj [table field value])
                                        (if (= table (:nodes store/tables))
                                          [{:xt/id "manifest:maven"
                                            :kind :manifest
                                            :path "pom.xml"
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:xt/id "pkg:maven:junit"
                                            :kind :external-package
                                            :ecosystem :maven
                                            :package-name "org.junit.jupiter"
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}]
                                          []))
                  store/q (fn [_ query]
                            (swap! xtql-queries conj query)
                            (let [relation (last query)]
                              (if (= :requires relation)
                                [{:source-id "manifest:maven"
                                  :target-id "pkg:maven:junit"
                                  :relation :requires
                                  :active? true
                                  :project-id "project-a"
                                  :repo-id "repo-a"}]
                                [])))]
      (is (= [] (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {}))))
    (is (= [[(:files store/tables) :repo-id "repo-a"]
            [(:nodes store/tables) :repo-id "repo-a"]]
           @row-queries))
    (is (= #{:requires :resolves :version-of}
           (set (map last @xtql-queries))))))

(deftest import-package-resolution-reads-source-edges-when-map-overlay-can-resolve
  (let [xtql-queries (atom [])]
    (with-redefs [store/rows-by-field (fn [_ table _ _]
                                        (case table
                                          :agraph/nodes
                                          [{:xt/id "pkg:maven:junit"
                                            :kind :external-package
                                            :ecosystem :maven
                                            :package-name "org.junit.jupiter"
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}]
                                          []))
                  store/q (fn [_ query]
                            (swap! xtql-queries conj query)
                            [])]
      (is (= [] (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {:map-overlay {:package-imports [{:repo "repo-a"
                                                   :ecosystem :maven
                                                   :package-name "org.junit.jupiter"
                                                   :import "org.junit.jupiter"}]}}))))
    (is (= #{:imports :requires :resolves :uses :version-of}
           (set (map last @xtql-queries))))))

(deftest import-package-resolution-preserves-source-kind
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "src/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:maven"
                                          :kind :manifest
                                          :path "pom.xml"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:slf4j-api"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.slf4j:slf4j-api"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:maven"
                              :target-id "pkg:maven:slf4j-api"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:java:demo.App"
                              :target-id "node:namespace:org.slf4j.Logger"
                              :relation :imports
                              :path "src/App.java"
                              :source-line 3
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses
                            []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {:map-overlay {:package-imports [{:repo "repo-a"
                                                   :ecosystem :maven
                                                   :package-name "org.slf4j:slf4j-api"
                                                   :import "org.slf4j"}]}})]
      (is (= [:java] (mapv :source-kind edges))))))

(deftest import-package-resolution-uses-ancestor-package-manifests
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "tests/smoke/esm/tests/basic.test.js"
                                          :kind :javascript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:root"
                                          :kind :manifest
                                          :path "package.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "manifest:nested"
                                          :kind :manifest
                                          :path "tests/smoke/esm/package.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:npm:axios"
                                          :kind :external-package
                                          :ecosystem :npm
                                          :package-name "axios"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:npm:vitest"
                                          :kind :external-package
                                          :ecosystem :npm
                                          :package-name "vitest"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:root"
                              :target-id "pkg:npm:axios"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:nested"
                              :target-id "pkg:npm:vitest"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:smoke"
                              :target-id "node:namespace:axios"
                              :relation :imports
                              :path "tests/smoke/esm/tests/basic.test.js"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= ["axios"] (mapv :package-name edges)))
      (is (= [:declared] (mapv :resolution-source edges))))))

(deftest import-package-resolution-uses-ancestor-npm-lock-packages
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "site/src/libs/prism.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "lock:npm"
                                          :kind :dependency-lock
                                          :path "package-lock.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "version:npm:prismjs"
                                          :kind :external-package-version
                                          :label "npm:prismjs@1.30.0"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "version:npm:prismjs:duplicate-lock-entry"
                                          :kind :external-package-version
                                          :label "npm:prismjs@1.30.0"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:npm:prismjs"
                                          :kind :external-package
                                          :ecosystem :npm
                                          :package-name "prismjs"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires []
                            :resolves
                            [{:source-id "lock:npm"
                              :target-id "version:npm:prismjs"
                              :relation :resolves
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "lock:npm"
                              :target-id "version:npm:prismjs:duplicate-lock-entry"
                              :relation :resolves
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :version-of
                            [{:source-id "version:npm:prismjs"
                              :target-id "pkg:npm:prismjs"
                              :relation :version-of
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "version:npm:prismjs:duplicate-lock-entry"
                              :target-id "pkg:npm:prismjs"
                              :relation :version-of
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:site.src.libs.prism"
                              :target-id "node:namespace:prismjs"
                              :relation :imports
                              :path "site/src/libs/prism.ts"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= ["prismjs"] (mapv :package-name edges)))
      (is (= [:dependency-lock] (mapv :resolution-source edges))))))

(deftest import-package-resolution-uses-types-package-for-type-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "site/src/libs/rehype.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "lock:npm"
                                          :kind :dependency-lock
                                          :path "package-lock.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "version:npm:@types/hast"
                                          :kind :external-package-version
                                          :label "npm:@types/hast@3.0.4"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "version:npm:@types/hast:duplicate-lock-entry"
                                          :kind :external-package-version
                                          :label "npm:@types/hast@3.0.4"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:npm:@types/hast"
                                          :kind :external-package
                                          :ecosystem :npm
                                          :package-name "@types/hast"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires []
                            :resolves
                            [{:source-id "lock:npm"
                              :target-id "version:npm:@types/hast"
                              :relation :resolves
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "lock:npm"
                              :target-id "version:npm:@types/hast:duplicate-lock-entry"
                              :relation :resolves
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :version-of
                            [{:source-id "version:npm:@types/hast"
                              :target-id "pkg:npm:@types/hast"
                              :relation :version-of
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "version:npm:@types/hast:duplicate-lock-entry"
                              :target-id "pkg:npm:@types/hast"
                              :relation :version-of
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:site.src.libs.rehype"
                              :target-id "node:namespace:hast"
                              :relation :imports
                              :import-kind :type
                              :path "site/src/libs/rehype.ts"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= ["@types/hast"] (mapv :package-name edges)))
      (is (= ["hast"] (mapv :import-name edges)))
      (is (= [:type-dependency-lock] (mapv :resolution-source edges))))))

(deftest import-package-resolution-uses-deno-import-map-facts
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "tests/smoke/deno/tests/import.test.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:deno"
                                          :kind :manifest
                                          :path "tests/smoke/deno/deno.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:deno:assert"
                                          :kind :external-package
                                          :ecosystem :deno
                                          :package-name "@std/assert"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:deno:axios"
                                          :kind :external-package
                                          :ecosystem :deno
                                          :package-name "axios"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:deno"
                              :target-id "pkg:deno:assert"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:deno"
                              :target-id "pkg:deno:axios"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:deno"
                              :target-id "node:namespace:@std/assert"
                              :relation :imports
                              :path "tests/smoke/deno/tests/import.test.ts"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:deno"
                              :target-id "node:namespace:axios"
                              :relation :imports
                              :path "tests/smoke/deno/tests/import.test.ts"
                              :source-line 2
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= #{"@std/assert" "axios"} (set (map :package-name edges))))
      (is (= #{:deno} (set (map :ecosystem edges))))
      (is (= #{:deno-import-map} (set (map :resolution-source edges)))))))

(deftest import-package-resolution-resolves-dotnet-nuget-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "tests/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:tests:csproj"
                                          :kind :manifest
                                          :path "tests/App.csproj"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:BenchmarkDotNet"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "BenchmarkDotNet"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:ef-sqlserver"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "Microsoft.EntityFrameworkCore.SqlServer"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:tests:csproj"
                              :target-id "pkg:nuget:BenchmarkDotNet"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:tests:csproj"
                              :target-id "pkg:nuget:ef-sqlserver"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:App"
                              :target-id "node:namespace:BenchmarkDotNet.Attributes"
                              :relation :imports
                              :path "tests/App.cs"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:App"
                              :target-id "node:namespace:Microsoft.EntityFrameworkCore"
                              :relation :imports
                              :path "tests/App.cs"
                              :source-line 2
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= #{"BenchmarkDotNet" "Microsoft.EntityFrameworkCore.SqlServer"}
             (set (map :package-name edges))))
      (is (= #{:dotnet} (set (map :source-kind edges))))
      (is (= #{:declared} (set (map :resolution-source edges)))))))

(deftest import-package-resolution-resolves-dotnet-normalized-and-assembly-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "tests/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:tests:csproj"
                                          :kind :manifest
                                          :path "tests/App.csproj"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "manifest:tests:props"
                                          :kind :manifest
                                          :path "tests/Directory.Build.props"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:belgrade"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "Belgrade.Sql.Client"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:assembly:microsoft-csharp"
                                          :kind :external-package
                                          :ecosystem :dotnet-assembly
                                          :package-name "Microsoft.CSharp"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:repodb-sqlserver"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "RepoDb.SqlServer"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:tests:csproj"
                              :target-id "pkg:nuget:belgrade"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:tests:props"
                              :target-id "pkg:assembly:microsoft-csharp"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:tests:csproj"
                              :target-id "pkg:nuget:repodb-sqlserver"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:App"
                              :target-id "node:namespace:Belgrade.SqlClient.SqlDb"
                              :relation :imports
                              :path "tests/App.cs"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:App"
                              :target-id "node:namespace:Microsoft.CSharp.RuntimeBinder"
                              :relation :imports
                              :path "tests/App.cs"
                              :source-line 2
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:App"
                              :target-id "node:namespace:RepoDb.DbHelpers"
                              :relation :imports
                              :path "tests/App.cs"
                              :source-line 3
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (let [edges (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})]
      (is (= #{"Belgrade.Sql.Client" "Microsoft.CSharp" "RepoDb.SqlServer"}
             (set (map :package-name edges))))
      (is (= #{:nuget :dotnet-assembly} (set (map :ecosystem edges)))))))

(deftest import-package-resolution-rejects-ambiguous-dotnet-root-match
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "src/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "manifest:app:csproj"
                                          :kind :manifest
                                          :path "src/App.csproj"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:foo-a"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "Foo.ProviderA"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:nuget:foo-b"
                                          :kind :external-package
                                          :ecosystem :nuget
                                          :package-name "Foo.ProviderB"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:app:csproj"
                              :target-id "pkg:nuget:foo-a"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:app:csproj"
                              :target-id "pkg:nuget:foo-b"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:App"
                              :target-id "node:namespace:Foo.Bar"
                              :relation :imports
                              :path "src/App.cs"
                              :source-line 1
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :uses []
                            []))]
    (is (empty? (dependency/resolve-import-package-edges
                 :xtdb
                 "project-a"
                 "repo-a"
                 "run-a"
                 {})))))

(deftest package-report-ignores-ci-workflow-edges-as-package-import-candidates
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path ".github/workflows/ci.yml"
                                          :kind :ci
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "node:ci:job"
                                          :kind :ci-job
                                          :label "ci"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:namespace:actions/checkout@v4"
                                          :kind :namespace
                                          :label "actions/checkout@v4"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/edges
                                        [{:source-id "node:ci:job"
                                          :target-id "node:namespace:actions/checkout@v4"
                                          :relation :imports
                                          :path ".github/workflows/ci.yml"
                                          :source-line 12
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (zero? (get-in report [:counts :source-import-candidates])))
      (is (empty? (:unresolved-imports report))))))

(deftest package-report-ignores-language-runtime-import-roots
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "src/app.py"
                                          :kind :python
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/runtime.js"
                                          :kind :javascript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/View.astro"
                                          :kind :astro
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        []
                                        :agraph/edges
                                        [{:source-id "node:namespace:app"
                                          :target-id "node:namespace:json"
                                          :relation :imports
                                          :path "src/app.py"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:app"
                                          :target-id "node:namespace:requests"
                                          :relation :imports
                                          :path "src/app.py"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:System.Data"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:Xunit"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:runtime"
                                          :target-id "node:namespace:fs"
                                          :relation :imports
                                          :path "src/runtime.js"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:runtime"
                                          :target-id "node:namespace:node:path"
                                          :relation :imports
                                          :path "src/runtime.js"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:runtime"
                                          :target-id "node:namespace:bun:test"
                                          :relation :imports
                                          :path "src/runtime.js"
                                          :source-line 3
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:View"
                                          :target-id "node:namespace:astro:content"
                                          :relation :imports
                                          :path "src/View.astro"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:View"
                                          :target-id "node:namespace:react"
                                          :relation :imports
                                          :path "src/View.astro"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (= 3 (get-in report [:counts :source-import-candidates])))
      (is (= ["Xunit" "react" "requests"]
             (sort (map :import (:unresolved-imports report))))))))

(deftest package-report-ignores-locally-defined-namespace-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "src/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/Local.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "node:namespace:Acme.Local"
                                          :kind :namespace
                                          :path "src/Local.cs"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/edges
                                        [{:source-id "node:namespace:App"
                                          :target-id "node:namespace:Acme.Local"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:Xunit"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (= 1 (get-in report [:counts :source-import-candidates])))
      (is (= ["Xunit"]
             (mapv :import (:unresolved-imports report)))))))

(deftest package-report-ignores-configured-module-path-alias-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "site/src/App.astro"
                                          :kind :astro
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "other/src/App.astro"
                                          :kind :astro
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        [{:xt/id "node:module-path-alias:libs"
                                          :kind :module-path-alias
                                          :label "@libs/*=src/libs/*"
                                          :path "site/tsconfig.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/edges
                                        [{:source-id "node:namespace:site.App"
                                          :target-id "node:namespace:@libs/path"
                                          :relation :imports
                                          :path "site/src/App.astro"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:site.App"
                                          :target-id "node:namespace:react"
                                          :relation :imports
                                          :path "site/src/App.astro"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:other.App"
                                          :target-id "node:namespace:@libs/path"
                                          :relation :imports
                                          :path "other/src/App.astro"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (= 2 (get-in report [:counts :source-import-candidates])))
      (is (= ["@libs/path" "react"]
             (sort (map :import (:unresolved-imports report))))))))

(deftest package-report-ignores-local-non-relative-js-path-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :agraph/files
                                        [{:path "site/src/assets/snippets.js"
                                          :kind :javascript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "site/src/assets/partials/snippets.js"
                                          :kind :javascript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :agraph/nodes
                                        []
                                        :agraph/edges
                                        [{:source-id "node:namespace:site.src.assets.snippets"
                                          :target-id "node:namespace:js/partials/snippets.js"
                                          :relation :imports
                                          :path "site/src/assets/snippets.js"
                                          :source-line 12
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (zero? (get-in report [:counts :source-import-candidates])))
      (is (empty? (:unresolved-imports report))))))
