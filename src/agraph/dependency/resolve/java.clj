(ns agraph.dependency.resolve.java
  "JVM package import resolution."
  (:require [agraph.dependency.resolve.common :as common]
            [clojure.string :as str]))

(defn- maven-coordinate
  [package-name]
  (let [[group-id artifact-id] (str/split (str package-name) #":" 2)]
    (when (and (seq group-id) (seq artifact-id))
      {:group-id group-id
       :artifact-id artifact-id})))

(defn- maven-artifact-prefixes
  [group-id artifact-id]
  (let [parts (vec (remove str/blank? (str/split (str artifact-id) #"-")))]
    (->> (range (count parts))
         (map #(subvec parts %))
         (remove empty?)
         (map #(str group-id "." (str/join "." %)))
         distinct
         vec)))

(defn- maven-import-prefixes
  [{:keys [package-name]}]
  (when-let [{:keys [group-id artifact-id]} (maven-coordinate package-name)]
    (->> (cond-> [group-id]
           (str/includes? group-id ".")
           (into (maven-artifact-prefixes group-id artifact-id)))
         distinct
         vec)))

(defn- maven-coordinate-match
  [target package]
  (->> (maven-import-prefixes package)
       (keep (fn [prefix]
               (when (common/import-prefix-match? target prefix)
                 {:package package
                  :import-name prefix
                  :resolution-source :maven-coordinate-prefix
                  :match-score (if (str/includes? prefix ".") 2 1)})))
       (sort-by (juxt (comp - :match-score)
                      (comp - count :import-name)))))

(defn- unique-best-package-match
  [matches]
  (let [matches (vec matches)
        best-score (:match-score (first matches))
        best-prefix-len (some-> (first matches) :import-name count)
        best (filter #(and (= best-score (:match-score %))
                           (= best-prefix-len (count (:import-name %))))
                     matches)
        packages (common/distinct-packages (map :package best))]
    (when (= 1 (count packages))
      (dissoc (first best) :match-score))))

(defn- maven-package-result
  [packages target]
  (or (common/package-by-import-name packages target)
      (unique-best-package-match
       (mapcat #(maven-coordinate-match target %) packages))))

(defn resolve-import
  [{:keys [packages-by-source edge target]}]
  (let [ancestor-packages (common/ancestor-source-packages packages-by-source
                                                           (:path edge)
                                                           :maven)
        all-packages (common/all-source-packages packages-by-source :maven)]
    (or (maven-package-result ancestor-packages target)
        (maven-package-result all-packages target))))
