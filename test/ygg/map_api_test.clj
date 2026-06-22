(ns ygg.map-api-test
  (:require [ygg.cli :as cli]
            [ygg.map :as graph-map]
            [ygg.map-api :as map-api]
            [ygg.map-store :as map-store]
            [ygg.project :as project]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
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

(deftest map-store-normalizes-generated-candidate-dumps-to-compact-v2
  (let [dir (temp-dir "ygg-map-normalize")
        map-path (.getPath (io/file dir "ygg.map.json"))]
    (spit map-path
          (json/write-json-str
           {:schema graph-map/legacy-schema
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
                               :reason "Reviewed package mapping."}]}
           {:indent-str "  "}))
    (let [overlay (map-store/read-map map-path)
          system (first (:systems overlay))]
      (is (= graph-map/schema (:schema overlay)))
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
               :reason "Reviewed package mapping."}]
             (:packageImports overlay))))))

(deftest map-store-write-strips-generated-candidates
  (let [dir (temp-dir "ygg-map-write-compact")
        map-path (.getPath (io/file dir "ygg.map.json"))
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
    (map-store/write-map! map-path (graph-map/propose-map "fixture" [candidate] []))
    (is (= []
           (:systems (map-store/read-map map-path))))))

(deftest map-review-is-read-only-and-returns-candidates
  (let [dir (temp-dir "ygg-map-review")
        map-path (.getPath (io/file dir "ygg.map.json"))]
    (with-redefs [project/read-project (fn [_] {:id "fixture"})
                  store/with-node (fn [_ f] (f :xtdb))
                  map-api/active-project-systems
                  (fn [_ project-id]
                    (is (= "fixture" project-id))
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
                  map-api/active-project-system-edges (fn [_ _] [])]
      (let [out (with-out-str
                  (cli/dispatch "map" ["review" "project.edn"
                                       "--map" map-path
                                       "--json"]))
            parsed (read-json-output out)]
        (is (= map-api/review-schema (:schema parsed)))
        (is (= "fixture" (:project-id parsed)))
        (is (= map-path (:map-path parsed)))
        (is (= 1 (get-in parsed [:candidates :totalSystems])))
        (is (= ["system:api"]
               (mapv :id (get-in parsed [:candidates :systems]))))
        (is (false? (map-store/file-exists? map-path)))))))

(deftest map-accept-system-writes-compact-correction-through-cli
  (let [dir (temp-dir "ygg-map-accept")
        map-path (.getPath (io/file dir "ygg.map.json"))]
    (map-store/write-map! map-path (graph-map/empty-map "fixture"))
    (with-out-str
      (cli/dispatch "map" ["accept" "system" "system:api"
                           "--kind" "service"
                           "--label" "API"
                           "--include" "app:src/api"
                           "--reason" "Reviewed API boundary."
                           "--map" map-path]))
    (is (= [{:id "system:api"
             :label "API"
             :kind "service"
             :includes [{:repo "app"
                         :path "src/api"}]
             :status "accepted"
             :reason "Reviewed API boundary."}]
           (:systems (map-store/read-map map-path))))))

(deftest map-writes-require-auditable-reason
  (let [dir (temp-dir "ygg-map-reason")
        map-path (.getPath (io/file dir "ygg.map.json"))]
    (map-store/write-map! map-path (graph-map/empty-map "fixture"))
    (try
      (cli/dispatch "map" ["reject" "external-api" "docs.example.com"
                           "--map" map-path])
      (is false "expected map write without reason to fail")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Map correction requires --reason." (.getMessage e)))
        (is (= {:action "reject"} (ex-data e)))))))

(deftest sync-map-write-aliases-are-retired
  (try
    (cli/dispatch "sync" ["ignore" "external-api" "docs.example.com"
                          "--map" "ygg.map.json"
                          "--reason" "Documentation reference"])
    (is false "expected retired sync map correction alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Map corrections are handled by the ygg map API."
             (.getMessage e)))
      (is (= "ygg map" (:replacement (ex-data e))))))
  (try
    (cli/dispatch "sync" ["docs" "attach" "API Gateway" "app:docs/api.md"
                          "--map" "ygg.map.json"
                          "--reason" "Reviewed API contract"])
    (is false "expected retired sync docs attach alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Map doc attachments are handled by the ygg map API."
             (.getMessage e)))
      (is (= "ygg map docs attach" (:replacement (ex-data e))))))
  (try
    (cli/dispatch "docs" ["attach" "ygg.map.json" "API Gateway" "app:docs/api.md"
                          "--reason" "Reviewed API contract"])
    (is false "expected retired top-level docs attach alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Map doc attachments are handled by the ygg map API."
             (.getMessage e)))
      (is (= "ygg map docs attach" (:replacement (ex-data e))))))
  (try
    (cli/dispatch "map" ["package-import" "org.slf4j" "maven:org.slf4j:slf4j-api"
                         "--map" "ygg.map.json"
                         "--reason" "Reviewed package mapping"])
    (is false "expected retired map package-import alias to fail")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Package import corrections use map package import."
             (.getMessage e)))
      (is (= "ygg map package import" (:replacement (ex-data e)))))))
