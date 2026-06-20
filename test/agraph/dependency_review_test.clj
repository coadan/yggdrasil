(ns agraph.dependency-review-test
  (:require [agraph.dependency-review :as dependency-review]
            [agraph.map :as graph-map]
            [agraph.queue :as queue]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(deftest dependency-review-packet-applies-package-import-correction
  (let [root (temp-dir "agraph-dependency-review-queue")
        dir (temp-dir "agraph-dependency-review-map")
        map-path (.getPath (io/file dir "agraph.map.json"))
        report {:packages [{:id "package:maven:org.slf4j:slf4j-api"
                            :label "maven:org.slf4j:slf4j-api"
                            :ecosystem "maven"
                            :package-name "org.slf4j:slf4j-api"
                            :version-range "2.0.0"
                            :dependency-scope :compile
                            :declared-by [{:path "pom.xml"}]}]
                :unresolved-imports [{:repo-id "app"
                                      :source-id "node:demo"
                                      :source-label "demo"
                                      :target-id "node:org.slf4j.Logger"
                                      :import "org.slf4j.Logger"
                                      :path "src/main/java/demo/App.java"
                                      :line 2
                                      :kind :java}]}
        packet (first (dependency-review/review-packets
                       {:project-id "fixture"
                        :basis {:schema "agraph.graph-basis/v1"
                                :project-id "fixture"
                                :hash "basis"}
                        :package-report report}))
        evidence-id (get-in packet [:facts :evidence 0 :id])
        id (get-in (queue/enqueue! packet {:root root
                                           :kind dependency-review/work-kind
                                           :project-id "fixture"})
                   [:item :id])]
    (is (= dependency-review/packet-schema (:schema packet)))
    (is (= "org.slf4j.Logger" (get-in packet [:facts :unresolvedImport :import])))
    (is (= {:totalPackages 1
            :includedPackages 1
            :packageLimit 40
            :truncated false
            :selectionBasis "mechanical-import-package-string-signals"
            :matchingPackages 1}
           (get-in packet [:facts :packageSelection])))
    (is (= {:id "package:maven:org.slf4j:slf4j-api"
            :label "maven:org.slf4j:slf4j-api"
            :ecosystem "maven"
            :package-name "org.slf4j:slf4j-api"
            :version-range "2.0.0"
            :dependency-scope :compile
            :declared-by [{:path "pom.xml"}]
            :candidateScore 90
            :candidateSignals [{:kind "import-prefix"
                                :value "org.slf4j"
                                :score 90}]}
           (get-in packet [:facts :packages 0])))
    (is (= ["add-package-import" "none"] (:allowedActions packet)))
    (queue/claim-next! root {:agent-id "codex"
                             :project-id "fixture"})
    (queue/complete! root
                     id
                     {:schema dependency-review/result-schema
                      :reviewId (:reviewId packet)
                      :recommendation "add-package-import"
                      :confidence 0.9
                      :reason "The import prefix is provided by the declared package."
                      :mapPatch [{:op "add-package-import"
                                  :import "org.slf4j"
                                  :ecosystem "maven"
                                  :package "org.slf4j:slf4j-api"
                                  :evidence [evidence-id]
                                  :reason "The package declares the matching API."}]})
    (let [applied (dependency-review/apply-work-result! root id map-path)
          map-data (graph-map/read-map map-path)
          package-import (first (:packageImports map-data))]
      (is (= dependency-review/apply-schema (:schema applied)))
      (is (= "applied" (:status applied)))
      (is (= 1 (:patchesApplied applied)))
      (is (= {:import "org.slf4j"
              :ecosystem "maven"
              :package "org.slf4j:slf4j-api"
              :status "accepted"
              :repo "app"
              :evidence [evidence-id]
              :rules (:reviewId packet)
              :reviewId (:reviewId packet)
              :confidence 0.9
              :reason "The package declares the matching API."}
             package-import)))))

(deftest dependency-review-rejects-package-outside-packet
  (let [packet (first (dependency-review/review-packets
                       {:project-id "fixture"
                        :basis {:hash "basis"}
                        :package-report {:packages [{:id "package:npm:react"
                                                     :label "npm:react"
                                                     :ecosystem "npm"
                                                     :package-name "react"}]
                                         :unresolved-imports [{:repo-id "app"
                                                               :import "org.slf4j.Logger"
                                                               :path "App.java"
                                                               :line 1}]}}))
        evidence-id (get-in packet [:facts :evidence 0 :id])
        errors (dependency-review/validate-result
                {:status :done
                 :payload packet
                 :result {:schema dependency-review/result-schema
                          :reviewId (:reviewId packet)
                          :recommendation "add-package-import"
                          :reason "Use a package."
                          :mapPatch [{:op "add-package-import"
                                      :import "org.slf4j"
                                      :ecosystem "maven"
                                      :package "org.slf4j:slf4j-api"
                                      :evidence [evidence-id]
                                      :reason "Not in packet."}]}})]
    (is (= [{:path [:mapPatch 0 :package]
             :error "Patch package must be one of facts.packages[]."
             :value {:ecosystem "maven"
                     :package "org.slf4j:slf4j-api"}}]
           errors))))

(deftest dependency-review-packet-reports-truncated-package-selection
  (let [packages (mapv (fn [idx]
                         {:id (str "package:npm:pkg-" idx)
                          :label (str "npm:pkg-" idx)
                          :ecosystem "npm"
                          :package-name (str "pkg-" idx)})
                       (range 45))
        packet (first (dependency-review/review-packets
                       {:project-id "fixture"
                        :basis {:hash "basis"}
                        :package-report {:counts {:packages 45}
                                         :packages packages
                                         :unresolved-imports [{:repo-id "app"
                                                               :import "pkg-44"
                                                               :path "src/app.js"
                                                               :line 1}]}}))]
    (is (= {:totalPackages 45
            :includedPackages 40
            :packageLimit 40
            :truncated true
            :selectionBasis "mechanical-import-package-string-signals"
            :matchingPackages 1}
           (get-in packet [:facts :packageSelection])))
    (is (= 40 (count (get-in packet [:facts :packages]))))
    (is (= "pkg-44" (get-in packet [:facts :packages 0 :package-name])))
    (is (= 100 (get-in packet [:facts :packages 0 :candidateScore])))
    (is (= [{:kind "exact-import"
             :value "pkg-44"
             :score 100}]
           (get-in packet [:facts :packages 0 :candidateSignals])))))
