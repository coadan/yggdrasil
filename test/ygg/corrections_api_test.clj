(ns ygg.corrections-api-test
  (:require [ygg.cli :as cli]
            [ygg.corrections :as corrections]
            [ygg.corrections-api :as corrections-api]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.project-registry :as registry]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory
              prefix
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(defn- read-json-output
  [s]
  (json/read-json s :key-fn keyword))

(deftest correction-import-projects-accepted-overlay-data-to-xtdb-facts
  (let [xtdb-path (temp-dir "ygg-corrections-import")
        input {:schema correction-overlay/schema
               :project "fixture"
               :systems [{:id "system:candidate"
                          :label "Candidate"
                          :kind "candidate-system"
                          :status "candidate"
                          :provenance "generated-by-ygg"
                          :source "deterministic"
                          :candidateTypes ["path-cluster"]
                          :metrics {:degree 8}}
                         {:id "system:accepted"
                          :label "Accepted"
                          :kind "service"
                          :status "accepted"
                          :provenance "generated-by-ygg"
                          :source "deterministic"
                          :candidateTypes ["path-cluster"]
                          :metrics {:degree 8}
                          :evidence [{:id "evidence:1"}]
                          :includes [{:repo "app"
                                      :path "src/accepted"}]
                          :reason "Reviewed architecture boundary."}]
               :reject []
               :edges []
               :docs []
               :package-imports [{:repo "app"
                                  :import "org.slf4j"
                                  :ecosystem "maven"
                                  :package "org.slf4j:slf4j-api"
                                  :reason "Reviewed package mapping."}]}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (corrections/import-overlay! xtdb "fixture" input {:source "test"})
        (let [overlay (corrections/overlay xtdb "fixture")
              system (first (:systems overlay))]
          (is (= correction-overlay/schema (:schema overlay)))
      (is (= ["system:accepted"] (mapv :id (:systems overlay))))
      (is (= {:id "system:accepted"
              :label "Accepted"
              :kind "service"
              :includes [{:repo "app"
                          :path "src/accepted"}]
              :status "accepted"
              :reason "Reviewed architecture boundary."}
             system))
      (is (not-any? #(contains? system %)
                    [:provenance :source :candidateTypes :metrics :evidence]))
      (is (= [{:import "org.slf4j"
               :ecosystem "maven"
               :package "org.slf4j:slf4j-api"
               :status "accepted"
               :repo "app"
               :reason "Reviewed package mapping."
               :confidence 1.0
               :rules "agent"}]
             (:packageImports overlay))))))))

(deftest correction-import-ignores-generated-candidate-overlays
  (let [xtdb-path (temp-dir "ygg-corrections-import-candidate")
        candidate {:xt/id "system:candidate"
                   :project-id "fixture"
                   :repo-id "app"
                   :label "Candidate"
                   :kind :candidate-system
                   :source :deterministic
                   :path-prefix "src/candidate"
                   :candidate-types [:path-cluster]
                   :metrics {:degree 3}
                   :active? true}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (is (= [] (corrections/import-overlay! xtdb
                                               "fixture"
                                               (correction-overlay/propose-overlay
                                                "fixture"
                                                [candidate]
                                                [])
                                               {:source "test"})))
        (is (= [] (:systems (corrections/overlay xtdb "fixture"))))))))

(deftest corrections-review-is-read-only-and-returns-candidates
  (with-redefs [registry/resolve-project (fn [_] {:project {:id "fixture"}})
                store/with-node (fn [_ f] (f :xtdb))
                corrections/overlay (fn [_ project-id] (correction-overlay/empty-overlay project-id))
                corrections-api/active-project-system-page
                (fn [_ project-id limit]
                  (is (= "fixture" project-id))
                  (is (= 50 limit))
                  [{:xt/id "system:api"
                    :project-id project-id
                    :repo-id "app"
                    :label "API"
                    :kind :candidate-system
                    :source :deterministic
                    :path-prefix "src/api"
                    :candidate-types [:path-cluster]
                    :metrics {:degree 5}
                    :active? true}])
                corrections-api/active-project-system-count (fn [_ _] 1)
                corrections-api/active-project-system-edge-count (fn [_ _] 0)]
    (let [out (with-out-str
                (cli/dispatch "corrections" ["review"
                                             "--project" "fixture"
                                             "--json"]))
          parsed (read-json-output out)]
      (is (= corrections-api/review-schema (:schema parsed)))
      (is (= "fixture" (:project-id parsed)))
      (is (= 1 (get-in parsed [:candidates :totalSystems])))
      (is (= ["system:api"]
             (mapv :id (get-in parsed [:candidates :systems])))))))

(deftest corrections-accept-system-writes-xtdb-fact-through-cli
  (let [xtdb-path (temp-dir "ygg-corrections-accept")]
    (with-redefs [registry/resolve-project (fn [_] {:project {:id "fixture"}})
                  store/storage-path (fn [_] xtdb-path)]
      (with-out-str
        (cli/dispatch "corrections" ["accept" "system" "system:api"
                                     "--kind" "service"
                                     "--label" "API"
                                     "--include" "app:src/api"
                                     "--reason" "Reviewed API boundary."
                                     "--project" "fixture"])))
    (store/with-node xtdb-path
      (fn [xtdb]
        (is (= [{:id "system:api"
                 :label "API"
                 :kind "service"
                 :includes [{:repo "app"
                             :path "src/api"}]
                 :status "accepted"
                 :reason "Reviewed API boundary."}]
               (:systems (corrections/overlay xtdb "fixture"))))))))

(deftest corrections-writes-require-auditable-reason
  (let [xtdb-path (temp-dir "ygg-corrections-reason")]
    (with-redefs [registry/resolve-project (fn [_] {:project {:id "fixture"}})
                  store/storage-path (fn [_] xtdb-path)]
      (try
        (cli/dispatch "corrections" ["reject" "external-api" "docs.example.com"
                                     "--project" "fixture"])
        (is false "expected correction write without reason to fail")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Correction requires --reason." (.getMessage e)))
          (is (= {:action "reject-system"} (ex-data e))))))))

(deftest retired-sync-write-aliases-point-to-corrections
  (try
    (cli/dispatch "sync" ["ignore" "external-api" "docs.example.com"
                          "--reason" "Documentation reference"])
    (is false "expected retired sync correction alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Correction writes are handled by ygg corrections."
             (.getMessage e)))
      (is (= "ygg corrections" (:replacement (ex-data e))))))
  (try
    (cli/dispatch "sync" ["docs" "attach" "API Gateway" "app:docs/api.md"
                          "--reason" "Reviewed API contract"])
    (is false "expected retired sync docs attach alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Doc attachment corrections are handled by ygg corrections."
             (.getMessage e)))
      (is (= "ygg corrections docs attach" (:replacement (ex-data e))))))
  (try
    (cli/dispatch "docs" ["attach" "API Gateway" "app:docs/api.md"
                          "--reason" "Reviewed API contract"])
    (is false "expected retired top-level docs attach alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Doc attachment corrections are handled by ygg corrections."
             (.getMessage e)))
      (is (= "ygg corrections docs attach" (:replacement (ex-data e)))))))
