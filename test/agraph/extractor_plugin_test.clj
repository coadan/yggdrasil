(ns agraph.extractor-plugin-test
  (:require [agraph.extractor-plugin :as extractor-plugin]
            [agraph.index :as index]
            [agraph.project :as project]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(defn- emit-plugin-command
  []
  ["python3" (.getCanonicalPath (io/file "test/fixtures/extractor-plugin/emit.py"))])

(defn- plugin-config
  []
  {:id "panel-plugin"
   :version "0.1.0"
   :command (emit-plugin-command)
   :modes [:enhance :scan]
   :applies-to {:file-kinds [:code]
                :path-globs ["src/*.clj"]}
   :scan {:path-globs ["flows/*.panel"]
          :file-kind :panel}
   :emits [:plugin-example]})

(deftest plugin-enhances-core-files-and-scans-configured-unsupported-files
  (let [repo (temp-dir "agraph-plugin-repo")
        xtdb-path (temp-dir "agraph-plugin-xtdb")]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn value [] 1)\n")
    (spit-file! repo "flows/home.panel" "panel Home\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo!
                       xtdb
                       repo
                       {:project-id "plugin-project"
                        :repo-id "app"
                        :extractor-plugins [(plugin-config)]})
              files (store/rows-by-field xtdb
                                         (:files store/tables)
                                         :project-id
                                         "plugin-project")
              nodes (store/rows-by-field xtdb
                                         (:nodes store/tables)
                                         :project-id
                                         "plugin-project")
              edges (store/rows-by-field xtdb
                                         (:edges store/tables)
                                         :project-id
                                         "plugin-project")
              file-facts (store/rows-by-field xtdb
                                              (:file-facts store/tables)
                                              :project-id
                                              "plugin-project")
              chunks (store/rows-by-field xtdb
                                          (:chunks store/tables)
                                          :project-id
                                          "plugin-project")]
          (is (= :completed (:status summary)))
          (is (some #(and (= "flows/home.panel" (:path %))
                          (= :panel (:kind %))
                          (:plugin-scanned? %)
                          (= ["panel-plugin"] (:plugin-ids %)))
                    files))
          (is (= #{"flows/home.panel" "src/app.clj"}
                 (->> nodes
                      (filter #(= "panel-plugin" (:plugin-id %)))
                      (map :path)
                      set)))
          (is (every? #(= :unbenchmarked (:benchmark-status %))
                      (filter #(= "panel-plugin" (:plugin-id %)) nodes)))
          (is (some #(and (= :plugin-links (:relation %))
                          (= "panel-plugin" (:plugin-id %)))
                    edges))
          (is (some #(and (= :plugin-fact (:kind %))
                          (= "flows/home.panel" (:path %))
                          (= "panel-plugin" (:plugin-id %)))
                    file-facts))
          (is (some #(and (= :plugin-summary (:kind %))
                          (= "flows/home.panel" (:path %))
                          (= "panel-plugin" (:plugin-id %)))
                    chunks)))))))

(deftest graph-profile-keeps-only-opted-in-plugin-search-chunks
  (let [repo (temp-dir "agraph-plugin-search-repo")
        xtdb-path (temp-dir "agraph-plugin-search-xtdb")]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo!
                       xtdb
                       repo
                       {:project-id "plugin-search-project"
                        :repo-id "app"
                        :index-profile :graph
                        :extractor-plugins [(assoc (plugin-config)
                                                   :search {:chunks? true})]})
              chunks (store/rows-by-field xtdb
                                          (:chunks store/tables)
                                          :project-id
                                          "plugin-search-project")
              search-docs (store/rows-by-field xtdb
                                               (:search-docs store/tables)
                                               :project-id
                                               "plugin-search-project")]
          (is (= :completed (:status summary)))
          (is (= 1 (get-in summary [:stats :chunks])))
          (is (= 1 (get-in summary [:stats :search-docs])))
          (is (= [:plugin-summary] (mapv :kind chunks)))
          (is (= [:chunk] (mapv :target-kind search-docs)))
          (is (every? #(= "panel-plugin" (:plugin-id %)) chunks)))))))

(deftest graph-profile-suppresses-plugin-chunks-without-search-opt-in
  (let [repo (temp-dir "agraph-plugin-no-search-repo")
        xtdb-path (temp-dir "agraph-plugin-no-search-xtdb")]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo!
                       xtdb
                       repo
                       {:project-id "plugin-no-search-project"
                        :repo-id "app"
                        :index-profile :graph
                        :extractor-plugins [(plugin-config)]})
              chunks (store/rows-by-field xtdb
                                          (:chunks store/tables)
                                          :project-id
                                          "plugin-no-search-project")
              search-docs (store/rows-by-field xtdb
                                               (:search-docs store/tables)
                                               :project-id
                                               "plugin-no-search-project")]
          (is (= :completed (:status summary)))
          (is (zero? (get-in summary [:stats :chunks])))
          (is (zero? (get-in summary [:stats :search-docs])))
          (is (empty? chunks))
          (is (empty? search-docs)))))))

(deftest plugin-command-failure-becomes-diagnostic
  (let [repo (temp-dir "agraph-plugin-failure-repo")
        xtdb-path (temp-dir "agraph-plugin-failure-xtdb")]
    (spit-file! repo "src/app.clj" "(ns app)\n(defn value [] 1)\n")
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [summary (index/index-repo!
                       xtdb
                       repo
                       {:project-id "plugin-project"
                        :repo-id "app"
                        :extractor-plugins [{:id "broken-plugin"
                                             :command ["python3"
                                                       "-c"
                                                       (str "import sys; "
                                                            "sys.stderr.write('broken'); "
                                                            "sys.exit(7)")]
                                             :applies-to {:file-kinds [:code]}}]})
              diagnostics (store/rows-by-field xtdb
                                               (:diagnostics store/tables)
                                               :project-id
                                               "plugin-project")]
          (is (= :completed (:status summary)))
          (is (some #(and (= :extractor-plugin (:stage %))
                          (= "broken-plugin" (:plugin-id %))
                          (re-find #"exit 7" (:message %)))
                    diagnostics)))))))

(deftest project-config-normalizes-extractor-plugins
  (let [root (io/file (temp-dir "agraph-plugin-config"))
        repo-root (io/file root "repo")
        project-edn (io/file root "project.edn")]
    (.mkdirs repo-root)
    (spit project-edn
          (pr-str {:id "plugin-config"
                   :repos [{:id "repo"
                            :root (.getPath repo-root)}]
                   :extractor-plugins [(plugin-config)]}))
    (let [plugin (-> (project/read-project (.getPath project-edn))
                     :extractor-plugins
                     first)]
      (is (= "panel-plugin" (:id plugin)))
      (is (= #{:enhance :scan} (:modes plugin)))
      (is (= :panel (get-in plugin [:scan :file-kind])))
      (is (= :unbenchmarked (:benchmark-status plugin)))
      (is (= {} (:search plugin)))
      (is (seq (extractor-plugin/scan-specs [plugin]))))))

(deftest extractor-plugin-input-includes-package-provenance
  (let [plugin (extractor-plugin/normalize-plugin
                (merge (plugin-config)
                       {:authority :git-plugin
                        :benchmark-status :unbenchmarked
                        :package-id "datastar-hiccup"
                        :package-version "0.1.0"
                        :package-rev "abc123"
                        :package-manifest-fingerprint "sha256:manifest"
                        :package-claim-authority {:status :non-authoritative
                                                  :public-claims? false
                                                  :review-required? false
                                                  :blockers [{:code :unbenchmarked
                                                              :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}
                        :package-source {:type :git
                                         :url "https://example.test/datastar.git"
                                         :rev "abc123"}}))
        input (#'extractor-plugin/plugin-input
               {:run-id "run:1"
                :project-id "plugin-input-project"
                :repo-id "app"
                :root-path "/repo"
                :file {:file-id "file:1"
                       :path "src/app.clj"
                       :kind :code
                       :content "(ns app)"}
                :core-extraction {:nodes []
                                  :edges []
                                  :chunks []
                                  :diagnostics []}}
               plugin)]
    (is (= {:id "panel-plugin"
            :version "0.1.0"
            :authority :git-plugin
            :benchmarkStatus "unbenchmarked"
            :packageId "datastar-hiccup"
            :packageVersion "0.1.0"
            :packageRev "abc123"
            :packageManifestFingerprint "sha256:manifest"
            :packageClaimAuthority {:status :non-authoritative
                                    :public-claims? false
                                    :review-required? false
                                    :blockers [{:code :unbenchmarked
                                                :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."}]}
            :packageSource {:type :git
                            :url "https://example.test/datastar.git"
                            :rev "abc123"}}
           (:plugin input)))))
