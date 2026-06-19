(ns agraph.extract.package-python
  (:require [agraph.extract.common :as common]
            [agraph.extract.package-toml :as package-toml]
            [clojure.string :as str]))

(defn pyproject-name
  [content path]
  (or (some-> (str/join "\n" (package-toml/toml-section-lines content "project"))
              (package-toml/toml-string-value "name"))
      (some-> (str/join "\n" (package-toml/toml-section-lines content "tool.poetry"))
              (package-toml/toml-string-value "name"))
      path))
(defn- python-dependency-name
  [value]
  (some-> (re-find #"^\s*([A-Za-z0-9_.-]+)" value)
          second))
(defn- pyproject-import-name-map
  [content]
  (->> (package-toml/toml-section-lines content "tool.agraph.import-names")
       (keep (fn [line]
               (when-let [[_ package-name]
                          (re-matches #"^\s*\"?([A-Za-z0-9_.-]+)\"?\s*=.*" line)]
                 (when-let [import-names (seq (package-toml/toml-string-or-array-value line))]
                   [package-name (vec import-names)]))))
       (into {})))
(defn pyproject-dependencies
  [content]
  (let [project-lines (package-toml/toml-section-lines content "project")
        import-name-map (pyproject-import-name-map content)
        inline-deps (->> project-lines
                         (keep #(second (re-matches #"^\s*dependencies\s*=\s*\[(.*)\]\s*$" %)))
                         (mapcat #(re-seq #"\"([^\"]+)\"" %))
                         (map second))
        poetry-deps (->> (package-toml/toml-section-lines content "tool.poetry.dependencies")
                         (keep #(when-let [[_ dep-name version]
                                           (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\".*" %)]
                                  (when-not (= "python" dep-name)
                                    [dep-name version]))))
        optional-deps (->> (package-toml/toml-section-lines content "project.optional-dependencies")
                           (mapcat (fn [line]
                                     (when-let [[_ group deps]
                                                (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\[(.*)\]\s*$" line)]
                                       (map (fn [[_ dep]]
                                              [group dep])
                                            (re-seq #"\"([^\"]+)\"" deps))))))]
    (vec
     (concat
      (keep (fn [dep]
              (when-let [dep-name (python-dependency-name dep)]
                (common/package-fact {:ecosystem :pypi
                               :package-name dep-name
                               :version-range dep
                               :import-names (get import-name-map dep-name)
                               :source-line 1})))
            inline-deps)
      (map (fn [[dep-name version]]
             (common/package-fact {:ecosystem :pypi
                            :package-name dep-name
                            :version-range version
                            :import-names (get import-name-map dep-name)
                            :source-line 1}))
           poetry-deps)
      (keep (fn [[group dep]]
              (when-let [dep-name (python-dependency-name dep)]
                (common/package-fact {:ecosystem :pypi
                               :package-name dep-name
                               :version-range dep
                               :dependency-scope group
                               :import-names (get import-name-map dep-name)
                               :source-line 1})))
            optional-deps)))))
(defn setup-cfg-name
  [content path]
  (or (some->> (str/join "\n" (package-toml/toml-section-lines content "metadata"))
               (re-find #"(?m)^\s*name\s*=\s*([A-Za-z0-9_.-]+)\s*$")
               second)
      path))
(defn setup-cfg-dependencies
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           in-install? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            (re-matches #"^\[options\]\s*$" trimmed)
            (recur (rest remaining) false out)

            (re-matches #"^\[.*\]\s*$" trimmed)
            (recur (rest remaining) false out)

            (re-matches #"^install_requires\s*=.*" trimmed)
            (recur (rest remaining) true out)

            (and in-install? (seq trimmed))
            (recur (rest remaining)
                   in-install?
                   (cond-> out
                     (python-dependency-name trimmed)
                     (conj (common/package-fact
                            {:ecosystem :pypi
                             :package-name (python-dependency-name trimmed)
                             :version-range trimmed
                             :source-line (inc idx)}))))

            :else
            (recur (rest remaining) in-install? out)))
        (vec (distinct out))))))
(defn setup-py-name
  [content path]
  (or (some-> (re-find #"(?s)\bname\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      path))
(defn setup-py-dependencies
  [content]
  (->> (re-seq #"(?s)(?:install_requires|setup_requires|tests_require)\s*=\s*\[(.*?)\]"
               content)
       (mapcat (fn [[_ deps]]
                 (re-seq #"['\"]([^'\"]+)['\"]" deps)))
       (map second)
       (keep (fn [dep]
               (when-let [dep-name (python-dependency-name dep)]
                 (common/package-fact {:ecosystem :pypi
                                :package-name dep-name
                                :version-range dep
                                :source-line 1}))))
       distinct
       vec))
(defn pipfile-dependencies
  [content]
  (->> ["packages" "dev-packages"]
       (mapcat
        (fn [section]
          (->> (package-toml/toml-section-lines content section)
               (map-indexed vector)
               (keep (fn [[idx line]]
                       (when-let [[_ dep-name version]
                                  (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" line)]
                         (common/package-fact {:ecosystem :pypi
                                        :package-name dep-name
                                        :version-range (str/replace (str/trim version)
                                                                    #"^['\"]|['\"]$" "")
                                        :dependency-scope section
                                        :source-line (inc idx)})))))))
       distinct
       vec))
