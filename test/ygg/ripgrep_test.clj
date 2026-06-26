(ns ygg.ripgrep-test
  (:require [ygg.ripgrep :as ripgrep]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [path (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile path))))

(defn- spit-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(deftest available-reports-missing-binary
  (is (false? (ripgrep/available? {:bin "definitely-not-ygg-rg"}))))

(deftest files-returns-repo-relative-hidden-and-ignore-aware-paths
  (let [root (temp-dir "ygg-rg-files")]
    (spit-file! root "src/app.clj" "(ns app)\n")
    (spit-file! root ".hidden/config.edn" "{}\n")
    (spit-file! root "target/generated.txt" "skip\n")
    (spit-file! root ".gitignore" "ignored.txt\n")
    (spit-file! root "ignored.txt" "skip\n")
    (let [result (ripgrep/files root {:ignore-globs ["target/**" "ignored.txt"]})
          paths (set (:paths result))]
      (is (= 0 (:exit result)))
      (is (contains? paths "src/app.clj"))
      (is (contains? paths ".hidden/config.edn"))
      (is (not (contains? paths "ignored.txt")))
      (is (not (contains? paths "target/generated.txt")))
      (is (false? (:truncated? result)))
      (is (empty? (:diagnostics result))))))

(deftest search-json-returns-literal-match-rows
  (let [root (temp-dir "ygg-rg-search")]
    (spit-file! root "src/app.clj" "(def projection-gateway-url \"PROJECTION_GATEWAY_URL\")\n")
    (spit-file! root "src/other.clj" "(def other \"value\")\n")
    (let [result (ripgrep/search-json root
                                      "PROJECTION_GATEWAY_URL"
                                      ["src/app.clj" "src/other.clj"])
          match (first (:matches result))]
      (is (= 0 (:exit result)))
      (is (= 1 (:match-count result)))
      (is (= "src/app.clj" (:path match)))
      (is (= 1 (:line match)))
      (is (str/includes? (:text match) "PROJECTION_GATEWAY_URL"))
      (is (= "PROJECTION_GATEWAY_URL" (get-in match [:submatches 0 :text])))
      (is (empty? (:diagnostics result))))))

(deftest search-json-can-search-root-case-insensitively
  (let [root (temp-dir "ygg-rg-search-root")]
    (spit-file! root "src/app.clj" "(def ProjectionGatewayUrl \"value\")\n")
    (let [result (ripgrep/search-json root
                                      "projectiongatewayurl"
                                      []
                                      {:hidden? false
                                       :ignore-case? true})
          match (first (:matches result))]
      (is (= 0 (:exit result)))
      (is (= 1 (:match-count result)))
      (is (= "src/app.clj" (:path match)))
      (is (= "ProjectionGatewayUrl" (get-in match [:submatches 0 :text])))
      (is (empty? (:diagnostics result))))))

(deftest search-json-treats-exit-one-as-zero-matches
  (let [root (temp-dir "ygg-rg-no-match")]
    (spit-file! root "src/app.clj" "(def value 1)\n")
    (let [result (ripgrep/search-json root "missing-literal" ["src/app.clj"])]
      (is (= 1 (:exit result)))
      (is (= 0 (:match-count result)))
      (is (empty? (:matches result)))
      (is (empty? (:diagnostics result))))))

(deftest search-json-reports-ripgrep-errors
  (let [root (temp-dir "ygg-rg-error")
        result (ripgrep/search-json root "value" ["does-not-exist.clj"])]
    (is (= 2 (:exit result)))
    (is (= 0 (:match-count result)))
    (is (= :ripgrep-error (-> result :diagnostics first :kind)))))

(deftest search-counts-returns-compact-per-file-match-counts
  (let [root (temp-dir "ygg-rg-counts")]
    (spit-file! root "src/app.clj" "needle\nneedle\nother\n")
    (spit-file! root "src/path:with-colon.clj" "needle\nhaystack\n")
    (let [result (ripgrep/search-counts root
                                        "needle"
                                        ["src/app.clj" "src/path:with-colon.clj"])
          multi-result (ripgrep/search-counts-many
                        root
                        ["needle" "haystack"]
                        ["src/app.clj" "src/path:with-colon.clj"])
          rows-by-path (into {} (map (juxt :path :count)) (:matches result))]
      (is (= 0 (:exit result)))
      (is (= 3 (:match-count result)))
      (is (= 2 (:file-count result)))
      (is (= {"src/app.clj" 2
              "src/path:with-colon.clj" 1}
             rows-by-path))
      (is (= 4 (:match-count multi-result)))
      (is (= {"src/app.clj" 2
              "src/path:with-colon.clj" 2}
             (into {} (map (juxt :path :count)) (:matches multi-result))))
      (is (empty? (:diagnostics result))))))

(deftest search-counts-can-search-hidden-paths-with-ignore-globs
  (let [root (temp-dir "ygg-rg-hidden-counts")]
    (spit-file! root ".github/workflows/ci.yml" "node-version: 22\n")
    (spit-file! root ".git/config" "node-version\n")
    (let [hidden-result (ripgrep/search-counts
                         root
                         "node-version"
                         []
                         {:hidden? true
                          :ignore-globs [".git/**" "**/.git/**"]})
          visible-result (ripgrep/search-counts
                          root
                          "node-version"
                          []
                          {:hidden? false
                           :ignore-globs [".git/**" "**/.git/**"]})
          hidden-paths (set (map :path (:matches hidden-result)))]
      (is (= 0 (:exit hidden-result)))
      (is (contains? hidden-paths ".github/workflows/ci.yml"))
      (is (not (contains? hidden-paths ".git/config")))
      (is (= 1 (:exit visible-result)))
      (is (empty? (:matches visible-result)))
      (is (empty? (:diagnostics hidden-result))))))

(deftest search-json-reports-truncation-without-shelling
  (let [root (temp-dir "ygg-rg-truncated")]
    (spit-file! root "src/app.clj" (str/join "\n" (repeat 100 "needle value")))
    (let [result (ripgrep/search-json root
                                      "needle"
                                      ["src/app.clj"]
                                      {:max-stdout-bytes 64})]
      (is (= 0 (:exit result)))
      (is (true? (:truncated? result)))
      (is (some #(= :stdout-truncated (:kind %)) (:diagnostics result))))))
