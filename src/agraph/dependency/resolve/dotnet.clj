(ns agraph.dependency.resolve.dotnet
  "Dotnet package import resolution."
  (:require [agraph.dependency.resolve.common :as common]
            [clojure.string :as str]))

(def ^:private manifest-extensions
  #{"csproj" "fsproj" "vbproj" "props" "targets"})

(def ^:private manifest-filenames
  #{"packages.config" "nuget.config"})

(def ^:private dependency-ecosystems
  #{:nuget :dotnet-assembly})

(defn- manifest?
  [path]
  (let [name (str/lower-case (common/filename path))]
    (or (contains? manifest-extensions (common/extension path))
        (contains? manifest-filenames name))))

(defn- packages-for-source
  [packages-by-source source-path]
  (->> packages-by-source
       (filter (fn [[manifest-path _packages]]
                 (and (manifest? manifest-path)
                      (common/ancestor-dir? (common/dirname manifest-path) source-path))))
       (mapcat val)
       (filter #(contains? dependency-ecosystems (:ecosystem %)))
       vec))

(defn- segment-prefix?
  [prefix value]
  (or (= prefix value)
      (str/starts-with? value (str prefix "."))))

(defn- normalized-name
  [value]
  (str/replace (str/lower-case (str value)) #"[^a-z0-9]" ""))

(defn- normalized-prefix?
  [prefix value]
  (let [prefix (normalized-name prefix)
        value (normalized-name value)]
    (and (seq prefix)
         (or (= prefix value)
             (str/starts-with? value prefix)))))

(defn- name-root
  [value]
  (first (str/split (str value) #"\.")))

(defn- normalized-root-match?
  [target package-name]
  (let [target-root (normalized-name (name-root target))
        package-root (normalized-name (name-root package-name))]
    (and (seq target-root)
         (= target-root package-root))))

(defn- package-match-score
  [target package-name]
  (let [target (str/lower-case (str target))
        package-name (str/lower-case (str package-name))]
    (cond
      (= target package-name) 3
      (segment-prefix? package-name target) 2
      (segment-prefix? target package-name) 1
      (normalized-prefix? package-name target) 1
      (normalized-prefix? target package-name) 1
      (normalized-root-match? target package-name) 0.5
      :else nil)))

(defn- package
  [packages target]
  (let [matches (->> packages
                     (keep (fn [package]
                             (when-let [score (package-match-score
                                               target
                                               (:package-name package))]
                               (assoc package :match-score score))))
                     (sort-by (juxt (comp - :match-score)
                                    (comp - count :package-name)))
                     vec)
        best-score (:match-score (first matches))
        best (filter #(= best-score (:match-score %)) matches)]
    (when (= 1 (count best))
      (dissoc (first best) :match-score))))

(defn resolve-import
  [{:keys [packages-by-source edge target]}]
  (let [packages (packages-for-source packages-by-source (:path edge))]
    (common/package-result (package packages target)
                           :declared)))
