(ns ygg.benchmark-dataset-diagnostics
  "Composition diagnostics for benchmark suites used in claim reports."
  (:require [ygg.benchmark-classes :as benchmark-classes]
            [clojure.string :as str]))

(defn- tag-name
  [tag]
  (when (some? tag)
    (name tag)))

(defn- case-tags
  [case]
  (set (keep tag-name (:tags case))))

(defn- repo-ids
  [case]
  (vec
   (distinct
    (cond
      (seq (:repos case))
      (keep :repo-id (:repos case))

      (:repo-id case)
      [(:repo-id case)]

      :else
      []))))

(defn- source-kinds
  [case]
  (mapv name (get-in case [:coverage :source-kinds])))

(defn- tagged-case?
  [tag case]
  (contains? (case-tags case) tag))

(defn- class-tags
  [pred case]
  (filterv pred (case-tags case)))

(defn- prefixed-tags
  [prefix case]
  (filterv #(str/starts-with? % prefix) (case-tags case)))

(defn- group-case-tags
  [cases tag-fn]
  (->> cases
       (mapcat (fn [case]
                 (map (fn [tag]
                        [tag (:id case)])
                      (tag-fn case))))
       (group-by first)
       (map (fn [[tag rows]]
              (let [case-ids (->> rows
                                  (map second)
                                  distinct
                                  sort
                                  vec)]
                {:tag tag
                 :cases (count case-ids)
                 :caseIds case-ids})))
       (sort-by :tag)
       vec))

(defn- group-case-values
  [cases value-fn key-name]
  (->> cases
       (mapcat (fn [case]
                 (map (fn [value]
                        [value (:id case)])
                      (value-fn case))))
       (group-by first)
       (map (fn [[value rows]]
              (let [case-ids (->> rows
                                  (map second)
                                  distinct
                                  sort
                                  vec)]
                {key-name value
                 :cases (count case-ids)
                 :caseIds case-ids})))
       (sort-by key-name)
       vec))

(defn dataset-diagnostics
  "Return suite composition diagnostics for benchmark claim scope."
  [cases]
  (let [cases (vec cases)
        synthetic (filterv #(tagged-case? "synthetic" %) cases)
        non-synthetic (remove #(tagged-case? "synthetic" %) cases)
        synthetic-only? (and (seq cases) (= (count cases) (count synthetic)))]
    {:cases (count cases)
     :syntheticCases (count synthetic)
     :syntheticCaseIds (mapv :id synthetic)
     :nonSyntheticCases (count non-synthetic)
     :nonSyntheticCaseIds (mapv :id non-synthetic)
     :syntheticOnly synthetic-only?
     :repos (group-case-values cases repo-ids :repoId)
     :sourceKinds (group-case-values cases source-kinds :kind)
     :problemClasses (group-case-tags cases
                                      #(class-tags
                                        benchmark-classes/problem-class-tag?
                                        %))
     :nonSyntheticProblemClasses (group-case-tags
                                  non-synthetic
                                  #(class-tags
                                    benchmark-classes/problem-class-tag?
                                    %))
     :architectureClasses (group-case-tags cases
                                           #(class-tags
                                             benchmark-classes/architecture-class-tag?
                                             %))
     :nonSyntheticArchitectureClasses (group-case-tags
                                       non-synthetic
                                       #(class-tags
                                         benchmark-classes/architecture-class-tag?
                                         %))
     :auditScopes (group-case-tags cases #(prefixed-tags "audit-scope-" %))
     :warnings (cond-> []
                 synthetic-only?
                 (conj {:kind "synthetic-only-dataset"
                        :severity "warning"
                        :message (str "Selected benchmark cases are all "
                                      "synthetic; broad efficiency claims "
                                      "should be restricted or backed by "
                                      "non-synthetic replay cases.")}))}))
