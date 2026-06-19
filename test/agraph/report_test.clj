(ns agraph.report-test
  (:require [agraph.graph :as graph]
            [agraph.project :as project]
            [agraph.report :as report]
            [agraph.report-plugin :as report-plugin]
            [agraph.xtdb :as store]
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

(defn- fixture-project
  []
  {:id "fixture"
   :name "Fixture"
   :path "test/fixtures/project-repo/project.edn"
   :repos [{:id "app"
            :root (.getPath (io/file "test/fixtures/project-repo"))
            :role :application}]})

(deftest report-data-includes-compact-package-diagnostics
  (let [packet (report/report-data
                {:project {:id "fixture"
                           :name "Fixture"
                           :path "project.edn"
                           :repos []
                           :plugin-packages
                           [{:id "datastar-hiccup"
                             :name "Datastar Hiccup"
                             :version "0.1.0"
                             :path ".dev/agraph/plugins/cache/datastar"
                             :source {:type :git
                                      :url "https://example.test/datastar.git"
                                      :rev "abc123"
                                      :extra "drop"}
                             :visibility :public
                             :license {:spdx "MIT"}
                             :scope {:kind :base}
                             :benchmark-status :unbenchmarked
                             :manifest-fingerprint "sha256:manifest"
                             :expected-manifest-fingerprint "sha256:manifest"
                             :extractor-plugins 1
                             :report-plugins 1
                             :warnings ["datastar-hiccup is unbenchmarked"]}]}
                 :detail :primary
                 :generated-at-ms 1
                 :graph-data {:nodes [] :edges []}
                 :systems-data {:nodes [] :edges []}
                 :coverage {:diagnostics {:total 2}}
                 :maintenance {}
                 :context-example {}
                 :evidence {:counts {:activity-items 3
                                     :activity-events 4
                                     :validation-events 1
                                     :result-schema-mismatch-events 1}}
                 :package-report {:counts {:packages 2
                                           :unresolved-imports 1
                                           :declared-without-import-evidence 1
                                           :version-conflicts 1}
                                  :ecosystems [{:ecosystem :npm
                                                :packages 2
                                                :versions 3
                                                :imports 1}]
                                  :declared-without-import-evidence
                                  [{:id "package:npm:lodash"
                                    :label "npm:lodash"
                                    :ecosystem :npm
                                    :package-name "lodash"
                                    :declared-by [{:path "package.json"
                                                   :line 1}]
                                    :imported-by [{:path "src/app.ts"}]
                                    :extra "drop"}]
                                  :unresolved-imports
                                  [{:source-id "node:namespace:src.app"
                                    :source-label "src.app"
                                    :target-id "node:namespace:left-pad"
                                    :import "left-pad"
                                    :path "src/app.ts"
                                    :line 4
                                    :kind :typescript
                                    :extra "drop"}]
                                  :version-conflicts
                                  [{:id "package:npm:react"
                                    :label "npm:react"
                                    :ecosystem :npm
                                    :package-name "react"
                                    :versions ["18.3.1" "19.1.0"]
                                    :declared-by [{:path "package.json"}]}]}
                 :artifacts {}})]
    (is (= [{:id "package:npm:lodash"
             :label "npm:lodash"
             :ecosystem :npm
             :package-name "lodash"
             :declared-by [{:path "package.json"
                            :line 1}]}]
           (get-in packet [:packages :declared-without-import-evidence])))
    (is (= [{:source-id "node:namespace:src.app"
             :source-label "src.app"
             :target-id "node:namespace:left-pad"
             :import "left-pad"
             :path "src/app.ts"
             :line 4
             :kind :typescript}]
           (get-in packet [:packages :unresolved-imports])))
    (is (= [{:id "package:npm:react"
             :label "npm:react"
             :ecosystem :npm
             :package-name "react"
             :versions ["18.3.1" "19.1.0"]}]
           (get-in packet [:packages :version-conflicts])))
    (is (= "agraph.report.atlas/v1" (get-in packet [:atlas :schema])))
    (is (= report-plugin/bundle-schema (get-in packet [:plugins :schema])))
    (is (empty? (get-in packet [:plugins :panels])))
    (is (= {:packages 1
            :warnings 1
            :unbenchmarked 1
            :benchmarked 0}
           (get-in packet [:plugin-packages :counts])))
    (is (= [{:id "datastar-hiccup"
             :name "Datastar Hiccup"
             :version "0.1.0"
             :path ".dev/agraph/plugins/cache/datastar"
             :visibility :public
             :license {:spdx "MIT"}
             :scope {:kind :base}
             :benchmark-status :unbenchmarked
             :manifest-fingerprint "sha256:manifest"
             :expected-manifest-fingerprint "sha256:manifest"
             :extractor-plugins 1
             :report-plugins 1
             :warnings ["datastar-hiccup is unbenchmarked"]
             :source {:type :git
                      :url "https://example.test/datastar.git"
                      :rev "abc123"}
             :diagnose-command
             "agraph plugin diagnose .dev/agraph/plugins/cache/datastar --json"}]
           (get-in packet [:plugin-packages :packages])))
    (is (= 2 (get-in packet [:atlas :dependencies :packages])))
    (is (= 1 (get-in packet [:atlas :dependencies :unresolved-imports])))
    (is (= 1 (get-in packet [:atlas :dependencies :version-conflicts])))
    (is (= {:items 3
            :events 4
            :validation-events 1
            :result-schema-mismatch-events 1}
           (get-in packet [:atlas :activity])))
    (is (= #{"agraph packages --project fixture --json"
             "agraph packages --project fixture --with-conflicts --json"
             "agraph sync coverage project.edn --json"
             "agraph sync activity project.edn --json"}
           (set (map :command (get-in packet [:atlas :next-actions])))))
    (is (some #(= {:kind :activity
                   :label "Inspect result schema mismatch activity"
                   :count 1
                   :mcpTool "agraph_sync_activity"
                   :mcpArgs {:configPath "project.edn"}
                   :command "agraph sync activity project.edn --json"}
                  %)
              (get-in packet [:atlas :next-actions])))))

(deftest report-data-suggests-maintenance-work-commands
  (let [packet (report/report-data
                {:project {:id "fixture"
                           :path "project.edn"
                           :repos []}
                 :map-path "agraph.map.json"
                 :detail :primary
                 :generated-at-ms 1
                 :graph-data {:nodes [] :edges []}
                 :systems-data {:nodes [] :edges []}
                 :coverage {}
                 :maintenance {:decision-queue [{:id "maintenance-decision:1"}]
                               :infra-review-queue [{:reviewId "infra-review:1"}]
                               :dependency-review-queue [{:reviewId "dependency-review:1"}]}
                 :context-example {}
                 :evidence {}
                 :package-report {}
                 :artifacts {}})
        commands (set (:commands packet))]
    (is (contains? commands "agraph sync work list --project fixture"))
    (is (contains? commands "agraph sync work show <work-id>"))
    (is (contains? commands "agraph sync work pull --project fixture --agent <agent-id>"))
    (is (contains? commands "agraph sync work pull --project fixture --kind maintenance-decision --agent <agent-id>"))
    (is (contains? commands "agraph sync work pull --project fixture --kind infra-review --agent <agent-id>"))
    (is (contains? commands "agraph sync work pull --project fixture --kind dependency-review --agent <agent-id>"))
    (is (contains? commands "agraph sync work heartbeat <work-id> --agent <agent-id> --lease-minutes 30"))
    (is (contains? commands "agraph sync work complete <work-id> --result result.json"))
    (is (contains? commands "agraph sync work apply <work-id> --map agraph.map.json"))
    (is (= [{:kind :maintenance
             :label "Process maintenance work queue"
             :count 3
             :command "agraph sync work list --project fixture"}]
           (get-in packet [:atlas :next-actions])))))

(deftest report-data-quotes-shell-sensitive-action-commands
  (let [packet (report/report-data
                {:project {:id "fixture project"
                           :path "Project Files/project.edn"
                           :repos []}
                 :map-path "Maps/agraph map.json"
                 :detail :primary
                 :generated-at-ms 1
                 :graph-data {:nodes [] :edges []}
                 :systems-data {:nodes [] :edges []}
                 :coverage {:diagnostics {:total 1}}
                 :maintenance {:decision-queue [{:id "maintenance-decision:1"}]}
                 :context-example {}
                 :evidence {}
                 :package-report {:counts {:unresolved-imports 1}}
                 :artifacts {}})
        commands (set (:commands packet))
        atlas-commands (set (map :command (get-in packet [:atlas :next-actions])))]
    (is (contains? commands
                   "agraph sync 'Project Files/project.edn' --check --map 'Maps/agraph map.json'"))
    (is (contains? commands
                   "agraph packages --project 'fixture project' --json"))
    (is (contains? commands
                   "agraph sync work apply <work-id> --map 'Maps/agraph map.json'"))
    (is (contains? atlas-commands
                   "agraph packages --project 'fixture project' --json"))
    (is (contains? atlas-commands
                   "agraph sync coverage 'Project Files/project.edn' --json"))))

(deftest writes-report-bundle-from-project-fixture
  (let [xtdb-path (temp-dir "agraph-report-xtdb")
        out-dir (io/file (temp-dir "agraph-report-out") "bundle")
        stale-asset (io/file out-dir "assets" "stale.js")
        project (fixture-project)]
    (.mkdirs (.getParentFile stale-asset))
    (spit stale-asset "stale")
    (store/with-node xtdb-path
      (fn [xtdb]
        (project/index-project! xtdb project {})
        (project/infer-project! xtdb project)
        (let [result (report/bundle! xtdb
                                     project
                                     {:out (.getPath out-dir)
                                      :detail :expanded
                                      :generated-at-ms 12345})
              files (:files result)
              graph-json (json/read-json (slurp (:graph files)) :key-fn keyword)
              systems-json (json/read-json (slurp (:systems files)) :key-fn keyword)
              report-json (json/read-json (slurp (:report-data files)) :key-fn keyword)
              plugins-json (json/read-json (slurp (:plugins files)) :key-fn keyword)
              report-md (slurp (:report files))]
          (is (= report/schema (:schema result)))
          (is (= "fixture" (:project-id result)))
          (is (= "agraph.evidence/v1" (get-in result [:evidence :schema])))
          (is (every? #(.exists (io/file %)) (vals files)))
          (is (= graph/schema (:schema graph-json)))
          (is (= graph/schema (:schema systems-json)))
          (is (= report/schema (:schema report-json)))
          (is (= report-plugin/bundle-schema (:schema plugins-json)))
          (is (= report-plugin/bundle-schema (get-in report-json [:plugins :schema])))
          (is (not (.exists stale-asset)))
          (is (some #(= report-plugin/core-plugin-id (get-in % [:plugin :id]))
                    (:panels plugins-json)))
          (is (= "fixture" (get-in report-json [:project :id])))
          (is (= "agraph.evidence/v1" (get-in report-json [:evidence :schema])))
          (is (= "agraph.report.atlas/v1" (get-in report-json [:atlas :schema])))
          (is (number? (get-in report-json [:atlas :evidence :files])))
          (is (number? (get-in report-json [:atlas :systems :nodes])))
          (is (= "graph.json" (get-in report-json [:graphs :overview :artifact])))
          (is (= "systems.json" (get-in report-json [:graphs :systems :artifact])))
          (is (vector? (get-in report-json [:packages :declared-without-import-evidence])))
          (is (vector? (get-in report-json [:packages :unresolved-imports])))
          (is (vector? (get-in report-json [:packages :version-conflicts])))
          (is (vector? (get-in report-json [:coverage :skipped-by-extension])))
          (is (seq (get-in report-json [:coverage :extractors])))
          (is (every? #(and (:kind %)
                            (:extractor-version %)
                            (number? (:files %)))
                      (get-in report-json [:coverage :extractors])))
          (is (seq (get-in report-json [:coverage :extractor-fingerprints])))
          (is (every? #(and (:kind %)
                            (:extractor-version %)
                            (:extractor-fingerprint %)
                            (number? (:files %)))
                      (get-in report-json [:coverage :extractor-fingerprints])))
          (is (= (get-in report-json [:maintenance :queue :decisions])
                 (get-in report-json [:maintenance :decision-summary :total])))
          (is (= "agraph.context/v1"
                 (:schema (json/read-json (slurp (:context-example files))
                                          :key-fn keyword))))
          (is (str/includes? report-md "# AGraph Report"))
          (is (str/includes? report-md "<EvidenceSurface"))
          (is (str/includes? report-md "## File Coverage"))
          (is (str/includes? report-md "agraph ask"))
          (is (str/includes? report-md "generated-at-ms: 12345"))
          (is (not (str/includes? report-md ":xt/id")))
          (is (not (str/includes? report-md "\"nodes\"")))
          (let [index-html (slurp (:index files))]
            (is (str/includes? index-html "id=\"root\""))
            (is (str/includes? index-html "__AGRAPH_BOOT__"))
            (is (str/includes? index-html "\"mode\":\"report\""))))))))

(deftest writes-shared-graph-viewer
  (let [out-dir (temp-dir "agraph-graph-viewer")
        html-file (io/file out-dir "systems.html")
        stale-asset (io/file out-dir "systems.assets" "assets" "stale.js")
        graph-data {:schema graph/schema
                    :title "Systems"
                    :nodes [{:id "system:app"
                             :label "App"
                             :kind "system"}]
                    :edges []}
        _ (.mkdirs (.getParentFile stale-asset))
        _ (spit stale-asset "stale")
        html-path (report/write-graph-viewer! (.getPath html-file) graph-data)
        graph-json (io/file out-dir "systems.graph.json")
        assets-dir (io/file out-dir "systems.assets")]
    (is (= (.getPath html-file) html-path))
    (is (.exists html-file))
    (is (.exists graph-json))
    (is (.isDirectory assets-dir))
    (is (.isDirectory (io/file assets-dir "assets")))
    (is (not (.exists stale-asset)))
    (let [html (slurp html-file)
          exported (json/read-json (slurp graph-json) :key-fn keyword)]
      (is (str/includes? html "__AGRAPH_BOOT__"))
      (is (str/includes? html "\"mode\":\"graph\""))
      (is (str/includes? html "systems.assets/assets/"))
      (is (= graph/schema (:schema exported)))
      (is (= (:nodes graph-data) (:nodes exported))))))

(deftest missing-shared-viewer-assets-fail-clearly
  (with-redefs-fn {#'report/report-ui-source-dir (constantly nil)}
    (fn []
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing compiled report UI assets\. Run bb report-ui:build\."
           (report/write-graph-viewer! "/tmp/agraph-missing-assets.html"
                                       {:schema graph/schema
                                        :title "Missing Assets"
                                        :nodes []
                                        :edges []}))))))

(deftest output-file-conflicts-unless-forced
  (let [root (temp-dir "agraph-report-conflict")
        target (io/file root "project.clj")]
    (spit target "(ns app)\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"existing file"
                          (report/prepare-output-dir! (.getPath target) false)))
    (is (= (.getPath target)
           (report/prepare-output-dir! (.getPath target) true)))
    (is (.isDirectory target))))
