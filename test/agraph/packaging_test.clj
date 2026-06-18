(ns agraph.packaging-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest packaged-report-ui-assets-are-distribution-inputs
  (let [asset-dir (io/file "resources/agraph/report-ui")
        dockerfile (slurp "Dockerfile")
        dockerignore (slurp ".dockerignore")
        formula (slurp "packaging/homebrew/Formula/agraph.rb.template")]
    (is (.isDirectory asset-dir))
    (is (.exists (io/file asset-dir "index.html")))
    (is (seq (filter #(.endsWith (.getName %) ".js")
                     (file-seq (io/file asset-dir "assets")))))
    (is (str/includes? dockerfile "COPY resources ./resources"))
    (is (not (re-find #"(?m)^resources(?:/|$)" dockerignore)))
    (is (str/includes? formula "libexec.install Dir[\"*\"]"))))
