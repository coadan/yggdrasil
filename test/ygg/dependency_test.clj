(ns ygg.dependency-test
  (:require [ygg.dependency :as dependency]
            [ygg.dependency.imports.python :as python-imports]
            [ygg.dependency.resolve.python :as resolve-python]
            [ygg.xtdb :as store]
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
    (is (= #{:imports :requires :resolves :uses :version-of}
           (set (map last @xtql-queries))))))

(deftest import-package-resolution-pushes-real-xtdb-scope-constraints
  (let [row-queries (atom [])
        edge-queries (atom [])
        manifest {:xt/id "manifest:maven"
                  :kind :manifest
                  :path "pom.xml"
                  :active? true
                  :project-id "project-a"
                  :repo-id "repo-a"}
        package {:xt/id "pkg:maven:junit"
                 :kind :external-package
                 :ecosystem :maven
                 :package-name "org.junit.jupiter"
                 :active? true
                 :project-id "project-a"
                 :repo-id "repo-a"}
        requires-edge {:source-id "manifest:maven"
                       :target-id "pkg:maven:junit"
                       :relation :requires
                       :active? true
                       :project-id "project-a"
                       :repo-id "repo-a"}]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! row-queries conj request)
                    (case (:table request)
                      :ygg/files []
                      :ygg/nodes [manifest package]
                      []))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "scoped dependency rows should use projected reads"
                                    {})))
                  store/rows-with-field-values
                  (fn [_ request]
                    (swap! edge-queries conj request)
                    (if (some #{:requires} (:values request))
                      [requires-edge]
                      []))
                  store/rows-by-field
                  (fn [& _]
                    (throw (ex-info "rows-by-field should not be used for real XTDB handles"
                                    {})))
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for scoped dependency reads"
                                    {})))
                  store/q
                  (fn [& _]
                    (throw (ex-info "store/q relation fallback should not be used for real XTDB handles"
                                    {})))]
      (is (= [] (dependency/resolve-import-package-edges
                 {:node :node}
                 "project-a"
                 "repo-a"
                 "run-a"
                 {}))))
    (is (= [{:table (:files store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-file-row-fields}
            {:table (:nodes store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-node-row-fields}]
           (mapv #(select-keys % [:table :constraints :return-fields])
                 @row-queries)))
    (is (= [{:table (:edges store/tables)
             :field :relation
             :values [:requires :resolves :version-of]
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-edge-row-fields}
            {:table (:edges store/tables)
             :field :relation
             :values [:imports :uses]
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-edge-row-fields}]
           (mapv #(select-keys % [:table :field :values :constraints :return-fields])
                 @edge-queries)))))

(deftest import-package-resolution-preserves-type-import-kind-from-batched-edge-projection
  (let [file {:path "site/src/libs/rehype.ts"
              :kind :typescript
              :active? true
              :project-id "project-a"
              :repo-id "repo-a"}
        lock {:xt/id "lock:npm"
              :kind :dependency-lock
              :path "package-lock.json"
              :active? true
              :project-id "project-a"
              :repo-id "repo-a"}
        version {:xt/id "version:npm:@types/hast"
                 :kind :external-package-version
                 :label "npm:@types/hast@3.0.4"
                 :active? true
                 :project-id "project-a"
                 :repo-id "repo-a"}
        package {:xt/id "pkg:npm:@types/hast"
                 :kind :external-package
                 :ecosystem :npm
                 :package-name "@types/hast"
                 :active? true
                 :project-id "project-a"
                 :repo-id "repo-a"}
        resolves-edge {:source-id "lock:npm"
                       :target-id "version:npm:@types/hast"
                       :relation :resolves
                       :active? true
                       :project-id "project-a"
                       :repo-id "repo-a"}
        version-edge {:source-id "version:npm:@types/hast"
                      :target-id "pkg:npm:@types/hast"
                      :relation :version-of
                      :active? true
                      :project-id "project-a"
                      :repo-id "repo-a"}
        import-edge {:source-id "node:namespace:site.src.libs.rehype"
                     :target-id "node:namespace:hast"
                     :relation :imports
                     :import-kind :type
                     :path "site/src/libs/rehype.ts"
                     :source-line 1
                     :active? true
                     :project-id "project-a"
                     :repo-id "repo-a"}
        edge-rows [resolves-edge version-edge import-edge]
        projected-edge-rows (fn [{:keys [values return-fields]}]
                              (let [relations (set values)]
                                (->> edge-rows
                                     (filter #(contains? relations (:relation %)))
                                     (map #(select-keys % return-fields))
                                     vec)))]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (case (:table request)
                      :ygg/files [file]
                      :ygg/nodes [lock version package]
                      []))
                  store/rows-with-field-values
                  (fn [_ request]
                    (projected-edge-rows request))]
      (let [edges (dependency/resolve-import-package-edges
                   {:node :node}
                   "project-a"
                   "repo-a"
                   "run-a"
                   {})]
        (is (= [{:package-name "@types/hast"
                 :import-name "hast"
                 :resolution-source :type-dependency-lock}]
               (mapv #(select-keys % [:package-name
                                      :import-name
                                      :resolution-source])
                     edges)))))))

(deftest package-report-uses-relation-scoped-edge-reads
  (let [row-queries (atom [])
        edge-queries (atom [])
        manifest {:xt/id "manifest:npm"
                  :kind :manifest
                  :path "package.json"
                  :active? true
                  :project-id "project-a"
                  :repo-id "repo-a"}
        package {:xt/id "pkg:npm:react"
                 :kind :external-package
                 :label "npm:react"
                 :ecosystem :npm
                 :package-name "react"
                 :active? true
                 :project-id "project-a"
                 :repo-id "repo-a"}
        requires-edge {:source-id "manifest:npm"
                       :target-id "pkg:npm:react"
                       :relation :requires
                       :path "package.json"
                       :active? true
                       :project-id "project-a"
                       :repo-id "repo-a"}]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! row-queries conj request)
                    (case (:table request)
                      :ygg/files []
                      :ygg/nodes [manifest package]
                      []))
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "package report scoped rows should use projected reads"
                                    {})))
                  store/rows-with-field-values
                  (fn [_ request]
                    (swap! edge-queries conj request)
                    (if (some #{:requires} (:values request))
                      [requires-edge]
                      []))
                  store/all-rows
                  (fn [& _]
                    (throw (ex-info "all-rows should not be used for package reports"
                                    {})))]
      (let [report (dependency/package-report
                    {:node :node}
                    {:project-id "project-a"
                     :repo-id "repo-a"}
                    {})]
        (is (= 1 (get-in report [:counts :packages])))
        (is (= 1 (get-in report [:counts :requires])))))
    (is (= [{:table (:files store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-file-row-fields}
            {:table (:nodes store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-node-row-fields}]
           (mapv #(select-keys % [:table :constraints :return-fields])
                 @row-queries)))
    (is (= [{:table (:edges store/tables)
             :field :relation
             :values [:requires :resolves :version-of :imports-package :imports :uses]
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :active? true}
             :return-fields @#'dependency/dependency-edge-row-fields}]
           (mapv #(select-keys % [:table :field :values :constraints :return-fields])
                 @edge-queries)))))

(deftest derived-dependency-edge-replacement-pushes-relation-scope-constraints
  (let [calls (atom [])
        tx-ops (atom nil)]
    (with-redefs [store/ordered-rows
                  (fn [_ request]
                    (swap! calls conj request)
                    [{:xt/id "edge:old-import-package"}])
                  store/constrained-rows
                  (fn [& _]
                    (throw (ex-info "derived edge replacement should not hydrate full edges"
                                    {})))
                  store/execute-tx!
                  (fn [_ ops]
                    (reset! tx-ops ops)
                    {:tx-id 1})]
      (is (= {:dependency-edges 0
              :dependency-edges-deleted 1}
             (store/commit-derived-dependency-edges!
              :xtdb
              "project-a"
              "repo-a"
              []
              {:valid-from #inst "2026-01-01T00:00:00Z"}))))
    (is (= [{:table (:edges store/tables)
             :constraints {:project-id "project-a"
                           :repo-id "repo-a"
                           :relation :imports-package}
             :return-fields [:xt/id]
             :read-context {:valid-at #inst "2026-01-01T00:00:00Z"}}]
           (mapv #(select-keys % [:table :constraints :return-fields :read-context])
                 @calls)))
    (is (= 1 (count @tx-ops)))
    (is (= #inst "2026-01-01T00:00:00Z"
           (get-in (first @tx-ops) [1 :valid-from])))
    (is (= "edge:old-import-package" (last (first @tx-ops))))))

(deftest import-package-resolution-reads-source-edges-when-correction-overlay-can-resolve
  (let [xtql-queries (atom [])]
    (with-redefs [store/rows-by-field (fn [_ table _ _]
                                        (case table
                                          :ygg/nodes
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
                 {:correction-overlay {:package-imports [{:repo "repo-a"
                                                          :ecosystem :maven
                                                          :package-name "org.junit.jupiter"
                                                          :import "org.junit.jupiter"}]}}))))
    (is (= #{:imports :requires :resolves :uses :version-of}
           (set (map last @xtql-queries))))))

(deftest import-package-resolution-preserves-source-kind
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "src/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                 {:correction-overlay {:package-imports [{:repo "repo-a"
                                                          :ecosystem :maven
                                                          :package-name "org.slf4j:slf4j-api"
                                                          :import "org.slf4j"}]}})]
      (is (= [:java] (mapv :source-kind edges))))))

(deftest import-package-resolution-resolves-java-maven-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "src/main/java/demo/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        [{:xt/id "manifest:maven"
                                          :kind :manifest
                                          :path "pom.xml"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:local"
                                          :kind :class
                                          :path "src/main/java/demo/Local.java"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:symbol:demo/Local"
                                          :kind :class
                                          :path "src/main/java/demo/Local.java"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:slf4j-api"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.slf4j:slf4j-api"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:junit-api"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.junit.jupiter:junit-jupiter-api"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:junit-engine"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.junit.jupiter:junit-jupiter-engine"
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
                              :repo-id "repo-a"}
                             {:source-id "manifest:maven"
                              :target-id "pkg:maven:junit-api"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:maven"
                              :target-id "pkg:maven:junit-engine"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:demo"
                              :target-id "node:namespace:org.slf4j.Logger"
                              :relation :imports
                              :path "src/main/java/demo/App.java"
                              :source-line 3
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:demo"
                              :target-id "node:namespace:org.junit.jupiter.api.Test"
                              :relation :imports
                              :path "src/main/java/demo/App.java"
                              :source-line 4
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "node:namespace:demo"
                              :target-id "node:namespace:demo.Local"
                              :relation :imports
                              :path "src/main/java/demo/App.java"
                              :source-line 5
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
                 {})
          package-names (set (map :package-name edges))]
      (is (= #{"org.slf4j:slf4j-api"
               "org.junit.jupiter:junit-jupiter-api"}
             package-names))
      (is (= #{:maven-coordinate-prefix}
             (set (map :resolution-source edges))))
      (is (not-any? #(= "demo.Local" (:import-name %)) edges)))))

(deftest import-package-resolution-leaves-ambiguous-java-maven-group-import-unresolved
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "src/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        [{:xt/id "manifest:maven"
                                          :kind :manifest
                                          :path "pom.xml"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:api"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.example:example-api"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "pkg:maven:engine"
                                          :kind :external-package
                                          :ecosystem :maven
                                          :package-name "org.example:example-engine"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [_ query]
                          (case (last query)
                            :requires
                            [{:source-id "manifest:maven"
                              :target-id "pkg:maven:api"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}
                             {:source-id "manifest:maven"
                              :target-id "pkg:maven:engine"
                              :relation :requires
                              :active? true
                              :project-id "project-a"
                              :repo-id "repo-a"}]
                            :imports
                            [{:source-id "node:namespace:demo"
                              :target-id "node:namespace:org.example.Shared"
                              :relation :imports
                              :path "src/App.java"
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

(deftest import-package-resolution-uses-ancestor-package-manifests
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "tests/smoke/esm/tests/basic.test.js"
                                          :kind :javascript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "site/src/libs/prism.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "site/src/libs/rehype.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "tests/smoke/deno/tests/import.test.ts"
                                          :kind :typescript
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "tests/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "tests/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path "src/App.cs"
                                          :kind :dotnet
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/files
                                        [{:path ".github/workflows/ci.yml"
                                          :kind :ci
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
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
                                        :ygg/edges
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
                                        :ygg/files
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
                                         {:path "src/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/View.astro"
                                          :kind :astro
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        []
                                        :ygg/edges
                                        [{:source-id "node:namespace:app"
                                          :target-id "node:namespace:json"
                                          :relation :imports
                                          :path "src/app.py"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:app"
                                          :target-id "node:namespace:__future__"
                                          :relation :imports
                                          :path "src/app.py"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:app"
                                          :target-id "node:namespace:contextvars"
                                          :relation :imports
                                          :path "src/app.py"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:app"
                                          :target-id "node:namespace:types"
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
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:jdk.jfr.Event"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:com.sun.net.httpserver.HttpServer"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:sun.misc.Unsafe"
                                          :relation :imports
                                          :path "src/App.java"
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

(deftest package-report-ignores-python-local-package-and-sibling-imports
  (let [local-target-calls (atom 0)
        local-targets python-imports/local-targets]
    (with-redefs [python-imports/local-targets (fn [& args]
                                                 (swap! local-target-calls inc)
                                                 (apply local-targets args))
                  store/rows-by-field (fn [_ table _ _]
                                        (case table
                                          :ygg/files
                                          [{:path "examples/tutorial/flaskr/__init__.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "examples/tutorial/flaskr/db.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "examples/tutorial/tests/test_db.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "tests/test_apps/helloworld/hello.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "tests/test_apps/helloworld/wsgi.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "examples/javascript/js_example/__init__.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:path "examples/javascript/js_example/views.py"
                                            :kind :python
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}]
                                          :ygg/nodes
                                          [{:xt/id "node:namespace:examples.javascript.js_example"
                                            :kind :namespace
                                            :label "examples.javascript.js_example"
                                            :path "examples/javascript/js_example/__init__.py"
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}]
                                          :ygg/edges
                                          [{:source-id "node:namespace:examples.tutorial.tests.test_db"
                                            :target-id "node:namespace:flaskr.db"
                                            :relation :imports
                                            :path "examples/tutorial/tests/test_db.py"
                                            :source-line 1
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:source-id "node:namespace:tests.test_apps.helloworld.wsgi"
                                            :target-id "node:namespace:hello"
                                            :relation :imports
                                            :path "tests/test_apps/helloworld/wsgi.py"
                                            :source-line 1
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:source-id "node:namespace:examples.javascript.js_example.views"
                                            :target-id "node:namespace:examples.javascript.js_example.app"
                                            :relation :imports
                                            :path "examples/javascript/js_example/views.py"
                                            :source-line 1
                                            :active? true
                                            :project-id "project-a"
                                            :repo-id "repo-a"}
                                           {:source-id "node:namespace:examples.tutorial.tests.test_db"
                                            :target-id "node:namespace:requests"
                                            :relation :imports
                                            :path "examples/tutorial/tests/test_db.py"
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
        (is (= 1 @local-target-calls))
        (is (= 1 (get-in report [:counts :source-import-candidates])))
        (is (= ["requests"] (mapv :import (:unresolved-imports report))))))))

(deftest package-report-resolves-python-private-module-roots-to-declared-package
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "pyproject.toml"
                                          :kind :manifest
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "tests/test_app.py"
                                          :kind :python
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        [{:xt/id "node:manifest:pyproject"
                                          :kind :manifest
                                          :path "pyproject.toml"
                                          :label "pyproject.toml"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:package:pypi:pytest"
                                          :kind :external-package
                                          :label "pypi:pytest"
                                          :ecosystem :pypi
                                          :package-name "pytest"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:namespace:tests.test_app"
                                          :kind :namespace
                                          :label "tests.test_app"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/edges
                                        [{:source-id "node:manifest:pyproject"
                                          :target-id "node:package:pypi:pytest"
                                          :relation :requires
                                          :path "pyproject.toml"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:tests.test_app"
                                          :target-id "node:namespace:_pytest.monkeypatch"
                                          :relation :imports
                                          :path "tests/test_app.py"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        []))
                store/q (fn [& _] [])]
    (let [pytest-package {:xt/id "node:package:pypi:pytest"
                          :kind :external-package
                          :ecosystem :pypi
                          :package-name "pytest"}
          result (resolve-python/resolve-import
                  {:packages-by-source {"pyproject.toml" [pytest-package]}
                   :edge {:path "tests/test_app.py"}
                   :target "_pytest.monkeypatch"})
          report (dependency/package-report :xtdb
                                            {:project-id "project-a"
                                             :repo-id "repo-a"}
                                            {})]
      (is (= pytest-package (:package result)))
      (is (= "_pytest" (:import-name result)))
      (is (= :python-private-module-root (:resolution-source result)))
      (is (empty? (:unresolved-imports report))))))

(deftest package-report-ignores-locally-defined-namespace-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
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
                                        :ygg/nodes
                                        [{:xt/id "node:namespace:Acme.Local"
                                          :kind :namespace
                                          :path "src/Local.cs"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:namespace:Acme.Mapper"
                                          :kind :namespace
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:symbol:Acme/Mapper"
                                          :kind :class
                                          :label "Acme/Mapper"
                                          :path "src/Mapper.cs"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/edges
                                        [{:source-id "node:namespace:App"
                                          :target-id "node:namespace:Acme.Local"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:Acme.Mapper"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:App"
                                          :target-id "node:namespace:Xunit"
                                          :relation :imports
                                          :path "src/App.cs"
                                          :source-line 3
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

(deftest package-report-ignores-local-java-member-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "src/App.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:path "src/Local.java"
                                          :kind :java
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        [{:xt/id "node:symbol:demo/Local"
                                          :label "demo/Local"
                                          :kind :class
                                          :path "src/Local.java"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:xt/id "node:symbol:demo/Exact.staticMethod"
                                          :label "demo/Exact.staticMethod"
                                          :kind :method
                                          :path "src/Local.java"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/edges
                                        [{:source-id "node:namespace:demo"
                                          :target-id "node:namespace:demo.Local.CONSTANT"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 1
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:demo"
                                          :target-id "node:namespace:demo.Local.Nested.VALUE"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 2
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:demo"
                                          :target-id "node:namespace:demo.Exact.staticMethod"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 3
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:demo"
                                          :target-id "node:namespace:org.slf4j.LoggerFactory.getLogger"
                                          :relation :imports
                                          :path "src/App.java"
                                          :source-line 4
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
      (is (= ["org.slf4j.LoggerFactory.getLogger"]
             (mapv :import (:unresolved-imports report)))))))

(deftest package-report-ignores-configured-module-path-alias-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
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
                                        :ygg/nodes
                                        [{:xt/id "node:module-path-alias:libs"
                                          :kind :module-path-alias
                                          :label "@libs/*=src/libs/*"
                                          :path "site/tsconfig.json"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/edges
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
                                        :ygg/files
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
                                        :ygg/nodes
                                        []
                                        :ygg/edges
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

(deftest package-report-ignores-go-stdlib-and-local-module-imports
  (with-redefs [store/rows-by-field (fn [_ table _ _]
                                      (case table
                                        :ygg/files
                                        [{:path "cmd/app/main.go"
                                          :kind :go
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/nodes
                                        [{:xt/id "node:manifest:go.mod"
                                          :kind :manifest
                                          :label "example.com/app"
                                          :path "go.mod"
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}]
                                        :ygg/edges
                                        [{:source-id "node:namespace:cmd/app/main"
                                          :target-id "node:namespace:context"
                                          :relation :imports
                                          :path "cmd/app/main.go"
                                          :source-line 3
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:cmd/app/main"
                                          :target-id "node:namespace:example.com/app/internal/config"
                                          :relation :imports
                                          :path "cmd/app/main.go"
                                          :source-line 4
                                          :active? true
                                          :project-id "project-a"
                                          :repo-id "repo-a"}
                                         {:source-id "node:namespace:cmd/app/main"
                                          :target-id "node:namespace:github.com/acme/lib"
                                          :relation :imports
                                          :path "cmd/app/main.go"
                                          :source-line 5
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
      (is (= ["github.com/acme/lib"]
             (mapv :import (:unresolved-imports report)))))))
