(ns ygg.extract.package-misc
  (:require [ygg.extract.common :as common]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn ruby-gem-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ gem-name]
                          (re-matches
                           #"^\s*(?:gem|spec\.add_(?:runtime_)?dependency|spec\.add_development_dependency)\s+['\"]([^'\"]+)['\"].*"
                           line)]
                 (common/package-fact {:ecosystem :rubygems
                                       :package-name gem-name
                                       :source-line (inc idx)}))))
       distinct
       vec))
(defn gemspec-name
  [content]
  (some-> (re-find #"(?m)^\s*[^#\n]*\.name\s*=\s*['\"]([^'\"]+)['\"]" content)
          second))
(defn composer-project-name
  [content path]
  (or (:name (common/read-json-map content))
      path))
(defn- composer-dependency-scope
  [k]
  (case k
    :require "runtime"
    :require-dev "development"
    nil))
(defn composer-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (->> [:require :require-dev]
         (mapcat
          (fn [k]
            (when-let [deps (get m k)]
              (when (map? deps)
                (map (fn [[package-name version]]
                       (common/package-fact {:ecosystem :composer
                                             :package-name (common/json-key-label package-name)
                                             :version-range (when (string? version)
                                                              version)
                                             :dependency-scope (composer-dependency-scope k)
                                             :source-line 1}))
                     deps)))))
         (remove nil?)
         distinct
         vec)
    []))
(defn deps-edn-project-name
  [path]
  path)
(defn deps-edn-dependencies
  [content]
  (try
    (let [parsed (edn/read-string content)
          dep-entries (concat (:deps parsed)
                              (mapcat :extra-deps (vals (:aliases parsed))))]
      (->> dep-entries
           (keep (fn [[dep-name spec]]
                   (cond
                     (:mvn/version spec)
                     (common/package-fact {:ecosystem :maven
                                           :package-name (str dep-name)
                                           :version-range (:mvn/version spec)
                                           :source-line 1})

                     (:git/url spec)
                     (common/package-fact {:ecosystem :git
                                           :package-name (:git/url spec)
                                           :version-range (or (:git/sha spec)
                                                              (:git/tag spec))
                                           :source-line 1})

                     :else nil)))
           distinct
           vec))
    (catch Exception _
      [])))
