(ns ygg.benchmark-feature-planning-test
  (:require [clojure.test :refer [deftest is]]
            [ygg.benchmark :as benchmark]))

(defn- case-by-id
  [suite id]
  (some #(when (= id (:id %)) %) (:cases suite)))

(defn- tag-set
  [case]
  (set (:tags case)))

(defn- decision-kind
  [case]
  (some-> (get-in case [:decision-ground-truth :kind]) name))

(defn- required-decision-ids
  [case]
  (set (get-in case [:decision-ground-truth :required])))

(deftest feature-planning-suite-covers-change-plan-benchmarks
  (let [suite (benchmark/read-suite "benchmarks/feature-planning.edn")
        cases (:cases suite)
        tags (set (mapcat :tags cases))]
    (is (= "feature-planning" (:id suite)))
    (is (= 4 (count cases)))
    (is (every? #(contains? (tag-set %) "feature-planning") cases))
    (is (every? #(contains? (tag-set %) "problem-planning") cases))
    (is (every? #(contains? (tag-set %) "decision-quality") cases))
    (is (every? #(= "change-plan" (decision-kind %)) cases))
    (is (every? #(seq (:decision-candidates %)) cases))
    (is (every? #(seq (get-in % [:ground-truth :localization-files])) cases))
    (is (contains? tags "architecture-dependency-flow"))
    (is (contains? tags "architecture-runtime-boundary"))
    (is (contains? tags "architecture-data-ownership"))
    (is (contains? tags "audit-scope-dependencies"))
    (is (contains? tags "audit-scope-runtime-config"))
    (is (contains? tags "audit-scope-containers"))))

(deftest feature-planning-cases-require-whole-change-plans
  (let [suite (benchmark/read-suite "benchmarks/feature-planning.edn")]
    (is (= #{"plan-config-and-manifest"}
           (required-decision-ids
            (case-by-id suite "planning-bootstrap-docs-search-integration"))))
    (is (= #{"plan-sql-owner-and-env"}
           (required-decision-ids
            (case-by-id suite "planning-supabase-trigger-owner-validation"))))
    (is (= #{"plan-adapter-and-proxy-tests"}
           (required-decision-ids
            (case-by-id suite "planning-axios-native-proxy-option"))))
    (is (= #{"plan-core-tests-and-postgres-stack"}
           (required-decision-ids
            (case-by-id suite "planning-dapper-jsonb-string-handling"))))))

(deftest broad-agent-efficiency-suite-includes-feature-planning
  (let [suite (benchmark/read-suite "benchmarks/agent-efficiency-broad.edn")
        included-ids (set (map :id (:included-suites suite)))
        planning-cases (filter #(contains? (tag-set %) "problem-planning")
                               (:cases suite))]
    (is (contains? included-ids "feature-planning"))
    (is (= 4 (count planning-cases)))
    (is (every? #(seq (:decision-candidates %)) planning-cases))
    (is (every? #(= "change-plan" (decision-kind %)) planning-cases))))
