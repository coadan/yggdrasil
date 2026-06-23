(ns ygg.benchmark-context-artifacts-test
  (:require [ygg.benchmark-context-artifacts :as context-artifacts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (.toFile
   (java.nio.file.Files/createTempDirectory prefix
                                            (make-array
                                             java.nio.file.attribute.FileAttribute
                                             0))))

(defn- write-sized-file!
  [dir name bytes]
  (let [file (io/file dir name)]
    (spit file (apply str (repeat bytes "x")))
    (.getPath file)))

(deftest context-artifact-telemetry-measures-frontload-and-expansion-bytes
  (let [dir (temp-dir "ygg-context-artifacts")
        prompt (write-sized-file! dir "prompt.md" 10)
        compact-hints (write-sized-file! dir "hints.json" 20)
        full-hints (write-sized-file! dir "hints.full.json" 80)
        context (write-sized-file! dir "context.json" 200)
        telemetry (context-artifacts/context-artifact-telemetry
                   {:prompt prompt
                    :yggHints compact-hints
                    :yggFullHints full-hints
                    :yggContext context})]
    (is (= context-artifacts/schema (:schema telemetry)))
    (is (= 10 (:promptBytes telemetry)))
    (is (= 20 (:compactHintsBytes telemetry)))
    (is (= 80 (:fullHintsBytes telemetry)))
    (is (= 200 (:contextBytes telemetry)))
    (is (= 30 (:frontloadBytes telemetry)))
    (is (= 280 (:expansionBytes telemetry)))
    (is (= 310 (:fullAvailableBytes telemetry)))
    (is (= 60 (:hintSavingsBytes telemetry)))
    (is (= 0.75 (:hintSavingsRatio telemetry)))
    (is (= (/ 30.0 280.0) (:frontloadToExpansionRatio telemetry)))
    (is (str/ends-with? (get-in telemetry [:artifacts :yggHints :path])
                        "hints.json"))))

(deftest context-artifact-telemetry-measures-compact-read-plan
  (let [dir (temp-dir "ygg-context-artifacts-read-plan")
        hints (io/file dir "hints.json")
        _ (spit hints
                "{\"readPlan\":{\"snippets\":[{\"snippet\":\"abc\"},{\"snippet\":\"defgh\"}]}}")
        telemetry (context-artifacts/context-artifact-telemetry
                   {:yggHints (.getPath hints)})
        aggregate (context-artifacts/aggregate-context-artifact-telemetry
                   [{:case-id "case-1"
                     :contextArtifacts telemetry}
                    {:case-id "case-2"
                     :contextArtifacts {:compactHintsBytes 1}}])]
    (is (= 2 (:readPlanSnippetCount telemetry)))
    (is (= 8 (:readPlanSnippetBytes telemetry)))
    (is (= 2 (get-in aggregate [:totals :readPlanSnippetCount])))
    (is (= 8 (get-in aggregate [:totals :readPlanSnippetBytes])))
    (is (= {:total 2
            :average 2.0}
           (get-in aggregate [:averages :readPlanSnippetCount])))))

(deftest aggregate-context-artifact-telemetry-sums-shared-runs
  (let [results [{:case-id "case-1"
                  :contextArtifacts {:promptBytes 10
                                     :compactHintsBytes 20
                                     :fullHintsBytes 80
                                     :contextBytes 200
                                     :frontloadBytes 30
                                     :expansionBytes 280
                                     :fullAvailableBytes 310
                                     :hintSavingsBytes 60}}
                 {:case-id "case-2"
                  :contextArtifacts {:promptBytes 5
                                     :compactHintsBytes 15
                                     :fullHintsBytes 45
                                     :contextBytes 100
                                     :frontloadBytes 20
                                     :expansionBytes 145
                                     :fullAvailableBytes 165
                                     :hintSavingsBytes 30}}
                 {:case-id "case-3"}]
        aggregate (context-artifacts/aggregate-context-artifact-telemetry results)]
    (is (= 2 (:runs aggregate)))
    (is (= ["case-1" "case-2"] (:caseIds aggregate)))
    (is (= {:promptBytes 15
            :compactHintsBytes 35
            :fullHintsBytes 125
            :contextBytes 300
            :frontloadBytes 50
            :expansionBytes 425
            :fullAvailableBytes 475
            :hintSavingsBytes 90}
           (:totals aggregate)))
    (is (= {:total 50
            :average 25.0}
           (get-in aggregate [:averages :frontloadBytes])))
    (is (= (/ 90.0 125.0) (:hintSavingsRatio aggregate)))
    (is (= (/ 50.0 425.0) (:frontloadToExpansionRatio aggregate)))))
