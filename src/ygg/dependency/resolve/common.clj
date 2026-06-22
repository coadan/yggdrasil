(ns ygg.dependency.resolve.common
  "Shared mechanical helpers for package import resolution."
  (:require [clojure.string :as str]))

(defn namespace-target
  [target-id]
  (some-> (re-find #"node:namespace:(.+)$" (str target-id))
          second))

(defn dirname
  [path]
  (let [idx (.lastIndexOf (str path) "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn filename
  [path]
  (last (str/split (str path) #"/")))

(defn extension
  [path]
  (some-> (re-find #"\.([A-Za-z0-9]+)$" (str path))
          second
          str/lower-case))

(defn ancestor-paths
  [path filename]
  (loop [dir (dirname path)
         out []]
    (if (seq dir)
      (recur (dirname dir) (conj out (str dir "/" filename)))
      (conj out filename))))

(defn nearest-manifest
  [manifest-paths source-path filename]
  (some #(when (contains? manifest-paths %) %) (ancestor-paths source-path filename)))

(defn ancestor-manifest-paths
  [manifest-paths source-path filename]
  (filterv #(contains? manifest-paths %) (ancestor-paths source-path filename)))

(defn ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(defn distinct-packages
  [packages]
  (->> packages
       (map (juxt :xt/id identity))
       (into {})
       vals
       vec))

(defn package-by-name
  [packages package-name]
  (let [matches (->> packages
                     (filter #(= package-name (:package-name %)))
                     distinct-packages)]
    (when (= 1 (count matches))
      (first matches))))

(defn packages-for
  [packages-by-source source-path ecosystem]
  (->> (get packages-by-source source-path)
       (filter #(= ecosystem (:ecosystem %)))
       vec))

(defn ancestor-source-packages
  [packages-by-source source-path ecosystem]
  (->> packages-by-source
       (filter (fn [[package-source-path _packages]]
                 (ancestor-dir? (dirname package-source-path) source-path)))
       (mapcat val)
       (filter #(= ecosystem (:ecosystem %)))
       distinct-packages))

(defn all-source-packages
  [packages-by-source ecosystem]
  (->> packages-by-source
       vals
       (mapcat identity)
       (filter #(= ecosystem (:ecosystem %)))
       distinct-packages))

(defn package-result
  ([package resolution-source]
   (package-result package resolution-source nil))
  ([package resolution-source import-name]
   (when package
     (cond-> {:package package
              :resolution-source resolution-source}
       import-name (assoc :import-name import-name)))))

(defn import-prefix-match?
  [target import-name]
  (let [target (str target)
        import-name (str import-name)]
    (and (seq target)
         (seq import-name)
         (or (= target import-name)
             (str/starts-with? target (str import-name "."))
             (str/starts-with? target (str import-name "/"))))))

(defn package-by-import-name
  [packages target]
  (let [matches (->> packages
                     (keep (fn [package]
                             (or (when-let [import-name
                                            (->> (:import-names package)
                                                 (filter #(import-prefix-match? target %))
                                                 (sort-by count >)
                                                 first)]
                                   {:package package
                                    :import-name import-name
                                    :resolution-source :manifest-import-name})
                                 (when (import-prefix-match? target (:package-name package))
                                   {:package package
                                    :import-name (:package-name package)
                                    :resolution-source :package-name})))))
        packages (distinct-packages (map :package matches))]
    (when (= 1 (count packages))
      (first matches))))
