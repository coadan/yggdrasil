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
    (is (= #{:requires}
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
    (is (= #{:requires :imports :uses}
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
