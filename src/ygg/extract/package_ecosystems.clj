(ns ygg.extract.package-ecosystems
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- yaml-section-keys
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) section out)

            (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
            (let [next-section (second (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*.*"
                                                   line))]
              (recur (rest remaining)
                     (when (contains? sections next-section) next-section)
                     out))

            section
            (let [entry (second (re-matches #"^\s{2}([A-Za-z_][A-Za-z0-9_.-]*)\s*:.*"
                                            line))]
              (recur (rest remaining)
                     section
                     (cond-> out
                       entry
                       (conj {:section section
                              :label entry
                              :source-line (inc idx)}))))

            :else
            (recur (rest remaining) section out)))
        out))))
(defn pubspec-dependencies
  [content]
  (->> (yaml-section-keys content ["dependencies" "dev_dependencies"])
       (mapv (fn [{:keys [label source-line]}]
               (common/package-fact {:ecosystem :pub
                                     :package-name label
                                     :source-line source-line})))))
(defn sbt-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*name\s*:=\s*\"([^\"]+)\"" content)
              second)
      path))
(defn sbt-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ group artifact]
                          (re-find #"\"([^\"]+)\"\s*%%?\s*\"([^\"]+)\"\s*%" line)]
                 (common/package-fact {:ecosystem :maven
                                       :package-name (str group ":" artifact)
                                       :source-line (inc idx)}))))
       distinct
       vec))
(defn mix-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*app:\s*:([A-Za-z_][A-Za-z0-9_]*)" content)
              second
              (str/replace "_" "-"))
      path))
(defn mix-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ dep-name]
                          (re-find #"\{\s*:([A-Za-z_][A-Za-z0-9_]*)\s*,\s*\"" line)]
                 (common/package-fact {:ecosystem :hex
                                       :package-name dep-name
                                       :source-line (inc idx)}))))
       distinct
       vec))
(defn rebar-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ dep-name]
                          (re-find #"\{\s*([a-z_][A-Za-z0-9_]*)\s*,\s*\{" line)]
                 (common/package-fact {:ecosystem :hex
                                       :package-name dep-name
                                       :source-line (inc idx)}))))
       distinct
       vec))
(defn- field-value
  [content field-name]
  (some-> (re-find (re-pattern (str "(?m)^" field-name ":\\s*(.+)$")) content)
          second
          str/trim))
(defn- comma-package-names
  [value]
  (->> (str/split (str value) #",")
       (map #(-> %
                 (str/replace #"\([^)]*\)" "")
                 str/trim))
       (remove str/blank?)
       vec))
(defn r-description-package-name
  [content path]
  (or (field-value content "Package") path))
(defn r-description-dependencies
  [content]
  (->> ["Depends" "Imports" "Suggests" "LinkingTo"]
       (mapcat (fn [field-name]
                 (let [dependency-scope (keyword (str/lower-case field-name))]
                   (->> (comma-package-names (field-value content field-name))
                        (remove #{"R"})
                        (map (fn [package-name]
                               (common/package-fact {:ecosystem :cran
                                                     :package-name package-name
                                                     :dependency-scope dependency-scope
                                                     :source-line 1})))))))
       (remove nil?)
       distinct
       vec))
(defn r-namespace-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ package-name]
                              (re-matches #"^\s*import(?:From)?\(([A-Za-z_][A-Za-z0-9_.]*).*\)\s*$" line)]
                     (common/package-fact {:ecosystem :cran
                                           :package-name package-name
                                           :source-line (inc idx)}))
                   (when-let [[_ export-name]
                              (re-matches #"^\s*export\(([A-Za-z_][A-Za-z0-9_.]*)\)\s*$" line)]
                     {:kind :export
                      :label export-name
                      :source-line (inc idx)
                      :relation :defines}))))
       distinct
       vec))
(defn toml-name-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name "\\s*=\\s*\"([^\"]+)\"")) content)
          second))
(defn project-toml-dependencies
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-deps? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (= "[deps]" trimmed)
          (recur (rest remaining) true out)

          (and in-deps? (re-matches #"^\[.+\]$" trimmed))
          (recur (rest remaining) false out)

          in-deps?
          (let [package-name (second (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=.*" trimmed))]
            (recur (rest remaining)
                   true
                   (cond-> out
                     package-name
                     (conj (common/package-fact {:ecosystem :julia
                                                 :package-name package-name
                                                 :source-line (inc idx)})))))

          :else
          (recur (rest remaining) in-deps? out)))
      (->> out distinct vec))))
(defn cpanfile-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name]
                          (re-matches #"^\s*requires\s+['\"]([^'\"]+)['\"].*" line)]
                 (common/package-fact {:ecosystem :cpan
                                       :package-name package-name
                                       :source-line (inc idx)}))))
       distinct
       vec))
(defn cabal-package-name
  [content path]
  (or (some-> (re-find #"(?mi)^\s*name:\s*([A-Za-z0-9_.-]+)\s*$" content)
              second)
      path))
(defn cabal-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (when-let [[_ deps]
                            (re-matches #"(?i)^\s*build-depends:\s*(.+)$" line)]
                   (map (fn [package-name]
                          (common/package-fact {:ecosystem :hackage
                                                :package-name package-name
                                                :source-line (inc idx)}))
                        (comma-package-names deps)))))
       (remove nil?)
       distinct
       vec))
(defn stack-yaml-dependencies
  [content]
  (->> (yaml-section-keys content ["extra-deps" "packages"])
       (mapv (fn [{:keys [label source-line]}]
               {:kind :project-reference
                :label label
                :source-line source-line
                :relation :references}))))
