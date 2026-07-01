(ns ygg.dependency-review-test
  (:require [ygg.cli :as cli]
            [ygg.corrections :as corrections]
            [ygg.dependency :as dependency]
            [ygg.dependency-review :as dependency-review]
            [ygg.project :as project]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix
                                                (make-array java.nio.file.attribute.FileAttribute
                                                            0))))

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn- read-json-output
  [s]
  (json/read-json s :key-fn keyword))

(defn- sync-dispatch-json
  [& args]
  (read-json-output
   (with-out-str
     (cli/dispatch "sync" (vec args)))))

(deftest dependency-review-packet-applies-package-import-correction
  (let [root (temp-dir "ygg-dependency-review-queue")
        xtdb-path (temp-dir "ygg-dependency-review-corrections")
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
                        :basis {:schema "ygg.graph-basis/v1"
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
                      :correctionPatch [{:op "add-package-import"
                                         :import "org.slf4j"
                                         :ecosystem "maven"
                                         :package "org.slf4j:slf4j-api"
                                         :evidence [evidence-id]
                                         :reason "The package declares the matching API."}]})
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [applied (dependency-review/apply-work-result! xtdb root id)
              overlay (corrections/overlay xtdb "fixture")
              package-import (first (:packageImports overlay))]
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
                 package-import)))))))

(deftest sync-work-dogfoods-unresolved-import-review-loop
  (let [workspace (temp-dir "ygg-dependency-review-dogfood")
        repo (io/file workspace "repo")
        project-path (.getPath (io/file workspace "project.edn"))
        xtdb-path (temp-dir "ygg-dependency-review-dogfood-xtdb")
        queue-root (.getPath (io/file workspace "project.sqlite"))
        result-path (.getPath (io/file workspace "result.json"))
        project-id "dependency-review-dogfood"
        project-data {:id project-id
                      :repos [{:id "app"
                               :root (.getPath repo)
                               :role :application}]}]
    (write-file! repo
                 "package.json"
                 "{\"name\":\"dogfood-app\",\"dependencies\":{\"left-pad\":\"1.3.0\"}}\n")
    (write-file! repo
                 "src/app.js"
                 "import leftPad from \"left_pad\";\nconsole.log(leftPad);\n")
    (spit project-path (pr-str project-data))
    (store/with-node xtdb-path
      (fn [xtdb]
        (let [loaded (project/read-project project-path)
              summary (project/index-project! xtdb loaded {:index-profile :graph})
              _ (project/infer-project! xtdb loaded)
              report (dependency/package-report xtdb {:project-id project-id} {})]
          (is (= :completed (:status summary)))
          (is (= 1 (get-in report [:counts :packages])))
          (is (= 1 (get-in report [:counts :unresolved-imports])))
          (is (= ["left_pad"] (mapv :import (:unresolved-imports report)))))))
    (with-redefs [store/storage-path (constantly xtdb-path)
                  store/project-sqlite-path
                  (fn [requested-project-id]
                    (is (= project-id requested-project-id))
                    queue-root)]
      (let [check-result (sync-dispatch-json project-path
                                             "--check"
                                             "--enqueue"
                                             "--json")
            queued-items (queue/list-items queue-root
                                           {:status "ready"
                                            :project-id project-id
                                            :kind dependency-review/work-kind})
            claimed (sync-dispatch-json "work"
                                        "pull"
                                        "--project"
                                        project-id
                                        "--kind"
                                        dependency-review/work-kind
                                        "--agent"
                                        "codex")
            work-id (:id claimed)
            packet (get-in claimed [:item :payload])
            evidence-id (get-in packet [:facts :evidence 0 :id])
            package (get-in packet [:facts :packages 0])]
        (is (= "ygg.sync/v1" (:schema check-result)))
        (is (= 1 (get-in check-result
                         [:check-report :counts :dependency-review-items])))
        (is (= 1 (count queued-items)))
        (is (= dependency-review/packet-schema (:schema packet)))
        (is (= "left_pad" (get-in packet [:facts :unresolvedImport :import])))
        (is (= "left-pad" (:package-name package)))
        (is (= 70 (:candidateScore package)))
        (spit result-path
              (json/write-json-str
               {:schema dependency-review/result-schema
                :reviewId (:reviewId packet)
                :recommendation "add-package-import"
                :confidence 0.83
                :reason "The unresolved import matches the declared package after separator normalization."
                :correctionPatch [{:op "add-package-import"
                                   :import "left_pad"
                                   :ecosystem "npm"
                                   :package "left-pad"
                                   :evidence [evidence-id]
                                   :reason "Reviewed package import mapping."}]}
               {:indent-str "  "}))
        (let [completed (sync-dispatch-json "work"
                                            "complete"
                                            work-id
                                            "--project"
                                            project-id
                                            "--result"
                                            result-path)
              applied (sync-dispatch-json "work"
                                          "apply"
                                          work-id
                                          "--project"
                                          project-id)
              overlay (store/with-node xtdb-path
                        (fn [xtdb]
                          (corrections/overlay xtdb project-id)))
              package-import (first (:packageImports overlay))]
          (is (= "done" (:status completed)))
          (is (= dependency-review/apply-schema (:schema applied)))
          (is (= "applied" (:status applied)))
          (is (= "valid" (get-in applied [:validation :status])))
          (is (= 1 (:patchesApplied applied)))
          (is (= {:import "left_pad"
                  :ecosystem "npm"
                  :package "left-pad"
                  :status "accepted"
                  :repo "app"
                  :evidence [evidence-id]
                  :rules (:reviewId packet)
                  :reviewId (:reviewId packet)
                  :confidence 0.83
                  :reason "Reviewed package import mapping."}
                 package-import))
          (store/with-node xtdb-path
            (fn [xtdb]
              (let [resolved-report (dependency/package-report
                                     xtdb
                                     {:project-id project-id}
                                     {:correction-overlay overlay})]
                (is (empty? (:unresolved-imports resolved-report)))
                (is (= 1 (get-in resolved-report [:counts :imports-package])))
                (is (= ["left_pad"]
                       (mapv :import-name
                             (get-in (first (:packages resolved-report))
                                     [:imported-by]))))))))))))

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
                          :correctionPatch [{:op "add-package-import"
                                             :import "org.slf4j"
                                             :ecosystem "maven"
                                             :package "org.slf4j:slf4j-api"
                                             :evidence [evidence-id]
                                             :reason "Not in packet."}]}})]
    (is (= [{:path [:correctionPatch 0 :package]
             :error "Patch package must be one of facts.packages[]."
             :value {:ecosystem "maven"
                     :package "org.slf4j:slf4j-api"}}]
           errors))))

(deftest dependency-review-batch-result-requires-one-result-per-review
  (let [first-packet {:schema dependency-review/packet-schema
                      :reviewId "dependency-review:first"
                      :project-id "fixture"
                      :facts {:unresolvedImport {:import "left_pad"}
                              :packages []
                              :evidence []}
                      :allowedActions ["none"]
                      :expectedOutput {:schema dependency-review/result-schema
                                       :reviewId "dependency-review:first"}}
        second-packet {:schema dependency-review/packet-schema
                       :reviewId "dependency-review:second"
                       :project-id "fixture"
                       :facts {:unresolvedImport {:import "right_pad"}
                               :packages []
                               :evidence []}
                       :allowedActions ["none"]
                       :expectedOutput {:schema dependency-review/result-schema
                                        :reviewId "dependency-review:second"}}
        packet (dependency-review/batch-packet [first-packet second-packet])
        valid-result {:schema dependency-review/batch-result-schema
                      :batchId (:batchId packet)
                      :results [{:schema dependency-review/result-schema
                                 :reviewId "dependency-review:first"
                                 :recommendation "no-change"
                                 :confidence 0.5
                                 :reason "Evidence is insufficient."
                                 :correctionPatch []}
                                {:schema dependency-review/result-schema
                                 :reviewId "dependency-review:second"
                                 :recommendation "needs-scanner"
                                 :confidence 0.5
                                 :reason "A scanner should provide more evidence."
                                 :correctionPatch []}]}
        missing-result (update valid-result :results subvec 0 1)]
    (is (empty? (dependency-review/validate-result
                 {:status :done
                  :payload packet
                  :result valid-result})))
    (is (= [{:path [:result :results]
             :error "Batch result is missing dependency review results."
             :value ["dependency-review:second"]}]
           (dependency-review/validate-result
            {:status :done
             :payload packet
             :result missing-result})))))

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
