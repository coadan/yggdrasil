(ns ygg.report-plugin-test
  (:require [ygg.project :as project]
            [ygg.report-plugin :as report-plugin]
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
  (let [root (io/file (temp-dir "ygg-report-plugin-config"))
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
      (is (= :unbenchmarked (:benchmark-status plugin)))
      (is (= (plugin-command) (:command plugin))))))

(deftest report-plugin-normalizes-and-validates-benchmark-status
  (let [benchmarked (report-plugin/normalize-plugin
                     (assoc (plugin-config)
                            :benchmark {:status :benchmarked}))]
    (is (= :benchmarked (:benchmark-status benchmarked)))
    (try
      (report-plugin/normalize-plugin
       (assoc (plugin-config)
              :benchmark-status :claimed))
      (is false "Expected unsupported benchmark status to fail.")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Unknown report plugin benchmark status." (ex-message e)))
        (is (= {:plugin-id "graph-crawl-plugin"
                :benchmark-status :claimed
                :supported [:benchmarked :unbenchmarked]}
               (ex-data e)))))))

(deftest report-plugin-input-includes-plugin-package-caveats
  (let [plugin (report-plugin/normalize-plugin (plugin-config))
        plugin-packages {:counts {:packages 1
                                  :warnings 1
                                  :unbenchmarked 1}
                         :packages [{:id "datastar-hiccup"
                                     :benchmark-status :unbenchmarked
                                     :warnings ["unbenchmarked"]}]}
        input (report-plugin/build-plugin-input
               {:project {:id "report-plugin-test"
                          :name "Report Plugin Test"
                          :path "project.edn"
                          :repos []}
                :generated-at-ms 1
                :report {:schema "ygg.report/v2"
                         :plugin-packages plugin-packages}
                :graph {:nodes [] :edges []}
                :systems {:nodes [] :edges []}
                :coverage {}
                :maintenance {}
                :evidence {}
                :package-report {}
                :artifacts {}}
               plugin)]
    (is (= plugin-packages (:pluginPackages input)))
    (is (= plugin-packages (get-in input [:report :plugin-packages])))))

(deftest report-plugin-can-crawl-generated-graph-exports
  (let [plugin (report-plugin/normalize-plugin (plugin-config))
        packet {:schema "ygg.report/v2"
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
                 :graph {:schema "ygg.graph/v2"
                         :nodes [{:id "node:a"
                                  :label "app.core"}
                                 {:id "node:b"
                                  :label "db.core"}]
                         :edges [{:source "node:a"
                                  :target "node:b"
                                  :relation "imports"}]}
                 :systems {:schema "ygg.graph/v2"
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
    (is (some #(and (= report-plugin/core-plugin-id (get-in % [:plugin :id]))
                    (= :core (:provenance %)))
              panels))
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

(deftest packaged-report-plugin-summary-includes-source-provenance
  (let [source {:type :git
                :url "https://github.com/org/ygg-plugins.git"
                :rev "abc123"
                :subdir "packages/report"}
        claim-authority {:status :non-authoritative
                         :public-claims? false
                         :review-required? false
                         :blockers [{:code :unbenchmarked
                                     :message "Unbenchmarked package output is non-authoritative."}]}
        plugin (report-plugin/normalize-plugin
                (assoc (plugin-config)
                       :package-id "report-pack"
                       :package-version "0.1.0"
                       :package-rev "abc123"
                       :package-manifest-fingerprint "sha256:manifest"
                       :package-source source
                       :package-claim-authority claim-authority))
        normalized (report-plugin/normalize-result
                    plugin
                    {:schema report-plugin/result-schema
                     :panels [{:id "packaged-panel"
                               :label "Packaged Panel"
                               :mdx "## Packaged"
                               :pluginId "spoofed-panel-plugin"
                               :provenance "spoofed"
                               :benchmarkStatus "benchmarked"}]
                     :diagnostics [{:message "review this panel"
                                    :pluginId "spoofed-diagnostic-plugin"
                                    :provenance "spoofed"}]
                     :artifacts [{:path "artifacts/report.json"
                                  :plugin-id "spoofed-artifact-plugin"
                                  :provenance "spoofed"}]})
        panel-plugin (get-in normalized [:panels 0 :plugin])
        diagnostic-plugin (get-in normalized [:diagnostics 0 :plugin])
        artifact-plugin (get-in normalized [:artifacts 0 :plugin])
        rows [(get-in normalized [:panels 0])
              (get-in normalized [:diagnostics 0])
              (get-in normalized [:artifacts 0])]]
    (doseq [summary [panel-plugin diagnostic-plugin artifact-plugin]]
      (is (= "report-pack" (:packageId summary)))
      (is (= "0.1.0" (:packageVersion summary)))
      (is (= "abc123" (:packageRev summary)))
      (is (= "sha256:manifest" (:packageManifestFingerprint summary)))
      (is (= source (:packageSource summary)))
      (is (= claim-authority (:packageClaimAuthority summary)))
      (is (= "unbenchmarked" (:benchmarkStatus summary))))
    (doseq [row rows]
      (is (= :plugin (:provenance row)))
      (is (= "graph-crawl-plugin" (:plugin-id row)))
      (is (= "0.1.0" (:plugin-version row)))
      (is (= (report-plugin/plugin-fingerprint plugin)
             (:plugin-fingerprint row)))
      (is (= :project-plugin (:plugin-authority row)))
      (is (= "report-pack" (:plugin-package-id row)))
      (is (= "0.1.0" (:plugin-package-version row)))
      (is (= "abc123" (:plugin-package-rev row)))
      (is (= "sha256:manifest" (:plugin-package-manifest-fingerprint row)))
      (is (= source (:plugin-package-source row)))
      (is (= claim-authority (:plugin-package-claim-authority row)))
      (is (= :unbenchmarked (:benchmark-status row))))))
