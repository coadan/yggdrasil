(ns agraph.coverage-test
  (:require [agraph.coverage :as coverage]
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

(defn- row-by
  [k value rows]
  (some #(when (= value (get % k)) %) rows))

(deftest project-coverage-reports-supported-and-skipped-source-types
  (let [root (temp-dir "agraph-coverage-repo")]
    (spit-file! root "src/app.py" "def main():\n    return 1\n")
    (spit-file! root "README.md" "# Demo\n")
    (spit-file! root "web/app.ts" "export const value = 1;\n")
    (spit-file! root "web/widget.vue" "<template></template>\n")
    (spit-file! root "package-lock.json" "{}\n")
    (spit-file! root ".github/workflows/ci.yml" "name: ci\n")
    (spit-file! root "target/generated.clj" "(ns generated)\n")
    (spit-file! root "node_modules/pkg/index.js" "module.exports = {}\n")
    (let [report (coverage/project-coverage
                  {:id "fixture"
                   :repos [{:id "app"
                            :root root
                            :role :application}]})]
      (is (= coverage/schema (:schema report)))
      (is (= {:files 5
              :supported 3
              :skipped 2}
             (select-keys (:totals report) [:files :supported :skipped])))
      (is (= 1 (:count (row-by :kind "python" (:files-by-kind report)))))
      (is (= 1 (:count (row-by :kind "doc" (:files-by-kind report)))))
      (is (= 1 (:count (row-by :kind "typescript" (:files-by-kind report)))))
      (is (= 1 (:count (row-by :ext ".vue" (:skipped-by-extension report)))))
      (is (= 1 (:count (row-by :reason "ignored-filename" (:skipped-by-reason report)))))
      (is (some #(and (= "python" (:kind %))
                      (= 1 (:files %))
                      (string? (:extractor-version %)))
                (:extractors report)))
      (is (= 0 (get-in report [:diagnostics :total]))))))
