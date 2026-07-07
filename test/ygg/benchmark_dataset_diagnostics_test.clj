(ns ygg.benchmark-dataset-diagnostics-test
  (:require [ygg.benchmark-dataset-diagnostics :as dataset-diagnostics]
            [clojure.test :refer [deftest is]]))

(deftest dataset-diagnostics-counts-synthetic-classes-and-source-kinds
  (let [diagnostics (dataset-diagnostics/dataset-diagnostics
                     [{:id "case-1"
                       :repo-id "repo-a"
                       :coverage {:source-kinds [:javascript :manifest]}
                       :tags [:synthetic
                              :problem-architecture
                              :architecture-dependency-flow
                              :audit-scope-dependencies]}
                      {:id "case-2"
                       :repo-id "repo-a"
                       :repos [{:repo-id "repo-a"}
                               {:repo-id "repo-b"}]
                       :coverage {:source-kinds [:go]}
                       :tags [:problem-maintenance]}])]
    (is (= 2 (:cases diagnostics)))
    (is (= 1 (:syntheticCases diagnostics)))
    (is (= ["case-1"] (:syntheticCaseIds diagnostics)))
    (is (= 1 (:nonSyntheticCases diagnostics)))
    (is (= false (:syntheticOnly diagnostics)))
    (is (= [{:repoId "repo-a"
             :cases 2
             :caseIds ["case-1" "case-2"]}
            {:repoId "repo-b"
             :cases 1
             :caseIds ["case-2"]}]
           (:repos diagnostics)))
    (is (= [{:kind "go"
             :cases 1
             :caseIds ["case-2"]}
            {:kind "javascript"
             :cases 1
             :caseIds ["case-1"]}
            {:kind "manifest"
             :cases 1
             :caseIds ["case-1"]}]
           (:sourceKinds diagnostics)))
    (is (= [{:tag "problem-architecture"
             :cases 1
             :caseIds ["case-1"]}
            {:tag "problem-maintenance"
             :cases 1
             :caseIds ["case-2"]}]
           (:problemClasses diagnostics)))
    (is (= [{:tag "problem-maintenance"
             :cases 1
             :caseIds ["case-2"]}]
           (:nonSyntheticProblemClasses diagnostics)))
    (is (= [{:tag "architecture-dependency-flow"
             :cases 1
             :caseIds ["case-1"]}
            {:tag "audit-scope-dependencies"
             :cases 1
             :caseIds ["case-1"]}]
           (:architectureClasses diagnostics)))
    (is (= []
           (:nonSyntheticArchitectureClasses diagnostics)))))

(deftest dataset-diagnostics-warns-on-synthetic-only-suite
  (let [diagnostics (dataset-diagnostics/dataset-diagnostics
                     [{:id "case-1"
                       :repo-id "repo-a"
                       :tags [:synthetic :problem-architecture]}
                      {:id "case-2"
                       :repo-id "repo-b"
                       :tags [:synthetic :problem-planning]}])]
    (is (= true (:syntheticOnly diagnostics)))
    (is (= 0 (:nonSyntheticCases diagnostics)))
    (is (= [] (:nonSyntheticProblemClasses diagnostics)))
    (is (= [] (:nonSyntheticArchitectureClasses diagnostics)))
    (is (= [{:kind "synthetic-only-dataset"
             :severity "warning"
             :message (str "Selected benchmark cases are all synthetic; broad "
                           "efficiency claims should be restricted or backed "
                           "by non-synthetic replay cases.")}]
           (:warnings diagnostics)))))
