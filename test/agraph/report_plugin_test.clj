(ns agraph.report-plugin-test
  (:require [agraph.project :as project]
            [agraph.report-plugin :as report-plugin]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- plugin-command
  []
  ["python3" (.getCanonicalPath (io/file "test/fixtures/report-plugin/graph_crawl.py"))])

(defn- plugin-config
  []
  {:id "graph-crawl-plugin"
   :version "0.1.0"
   :command (plugin-command)
   :slots [:plugins]})

(deftest project-config-normalizes-report-plugins
  (let [root (io/file (temp-dir "agraph-report-plugin-config"))
        repo-root (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo-root)
    (spit project-edn
          (pr-str {:id "report-plugin-config"
                   :repos [{:id "repo"
                            :root (.getPath repo-root)}]
                   :report-plugins [(plugin-config)]}))
    (let [plugin (-> (project/read-project (.getPath project-edn))
                     :report-plugins
                     first)]
      (is (= "graph-crawl-plugin" (:id plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (= ["plugins"] (:slots plugin)))
      (is (= :project-plugin (:authority plugin)))
      (is (= (plugin-command) (:command plugin))))))

(deftest report-plugin-can-crawl-generated-graph-exports
  (let [plugin (report-plugin/normalize-plugin (plugin-config))
        packet {:schema "agraph.report/v2"
                :atlas {:evidence {:files 1
                                   :nodes 2
                                   :edges 1}
                        :systems {:nodes 1
                                  :edges 1}
                        :dependencies {:packages 1}
                        :maintenance {:external-api-review {:source-fanouts 0}}
                        :activity {}
                        :next-actions []}
                :evidence {:counts {:files 1
                                    :nodes 2
                                    :edges 1}}
                :packages {:counts {:packages 1
                                    :versions 1
                                    :imports-package 1}}
                :maintenance {:queue {:decisions 0
                                      :infra-review 0
                                      :dependency-review 0}}}
        bundle (report-plugin/bundle
                {:project {:id "report-plugin-test"
                           :name "Report Plugin Test"
                           :path "test/fixtures/project-repo/project.edn"
                           :repos []}
                 :generated-at-ms 1
                 :report packet
                 :graph {:schema "agraph.graph/v2"
                         :nodes [{:id "node:a"
                                  :label "app.core"}
                                 {:id "node:b"
                                  :label "db.core"}]
                         :edges [{:source "node:a"
                                  :target "node:b"
                                  :relation "imports"}]}
                 :systems {:schema "agraph.graph/v2"
                           :nodes [{:id "system:app"
                                    :label "App"}]
                           :edges [{:source "system:app"
                                    :target "node:a"
                                    :relation "contains"}]}
                 :coverage {}
                 :maintenance {}
                 :evidence {}
                 :package-report {}
                 :artifacts {}
                 :plugins [plugin]})
        panels (:panels bundle)
        graph-panel (first (filter #(= "graph-crawl" (:id %)) panels))
        metrics (into {} (map (juxt :label :value))
                      (get-in graph-panel [:data :metrics]))]
    (is (= report-plugin/bundle-schema (:schema bundle)))
    (is (some #(= report-plugin/core-plugin-id (get-in % [:plugin :id])) panels))
    (is (= "graph-crawl-plugin" (get-in graph-panel [:plugin :id])))
    (is (= 2 (get metrics "Overview Nodes")))
    (is (= 1 (get metrics "Overview Edges")))
    (is (= 1 (get metrics "System Nodes")))
    (is (= 1 (get metrics "System Edges")))
    (is (= "app.core"
           (get-in graph-panel [:data :rows :rows 0 :value])))
    (is (empty? (:diagnostics bundle)))))

(deftest malformed-plugin-output-becomes-row-diagnostics
  (let [plugin (report-plugin/normalize-plugin (plugin-config))
        normalized (report-plugin/normalize-result
                    plugin
                    {:schema report-plugin/result-schema
                     :panels ["bad-panel"
                              {:id "valid-panel"
                               :label "Valid Panel"
                               :mdx "## Valid"}]
                     :diagnostics "bad-diagnostics"
                     :artifacts ["bad-artifact"]})
        diagnostics (set (map (juxt :stage :message) (:diagnostics normalized)))]
    (is (= ["valid-panel"] (mapv :id (:panels normalized))))
    (is (empty? (:artifacts normalized)))
    (is (contains? diagnostics
                   ["invalid-diagnostics"
                    "expected diagnostics to be an array"]))
    (is (contains? diagnostics
                   ["invalid-panel"
                    "expected panel at index 0 to be an object"]))
    (is (contains? diagnostics
                   ["invalid-artifact"
                    "expected artifact at index 0 to be an object"]))))
