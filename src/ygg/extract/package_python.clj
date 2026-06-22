(ns ygg.extract.package-python
  (:require [ygg.extract.common :as common]
            [ygg.extract.package-toml :as package-toml]
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

(def ^:private pip-install-pattern
  #"(?i)(?:^|[`$>({;\s])(?:python(?:\d+(?:\.\d+)?)?\s+-m\s+)?pip\d*\s+install\s+(.+)$")

(def ^:private shell-token-pattern
  #"'([^']*)'|\"([^\"]*)\"|(\S+)")

(def ^:private pip-options-with-values
  #{"-c" "--constraint" "-r" "--requirement" "-i" "--index-url"
    "--extra-index-url" "-f" "--find-links" "--trusted-host" "--proxy"
    "--cert" "--client-cert" "--cache-dir" "--target" "--platform"
    "--python-version" "--implementation" "--abi" "--root" "--prefix"
    "--src" "--upgrade-strategy" "--config-settings" "-C"})

(defn- shell-token-value
  [[_ single-quoted double-quoted bare]]
  (or single-quoted double-quoted bare))

(defn- trim-markdown-token
  [token]
  (-> (str token)
      (str/replace #"^`+" "")
      (str/replace #"[`.,:;)]*$" "")))

(defn- command-tail
  [value]
  (-> (str value)
      (str/split #"\s*(?:&&|\|\||;|\|)\s*" 2)
      first
      (str/split #"\s+#" 2)
      first))

(defn- shell-tokens
  [value]
  (->> (re-seq shell-token-pattern (command-tail value))
       (map shell-token-value)
       (map trim-markdown-token)
       (remove str/blank?)
       vec))

(defn- option-with-value?
  [token]
  (let [token (str token)]
    (or (contains? pip-options-with-values token)
        (some #(str/starts-with? token (str % "="))
              pip-options-with-values))))

(defn- package-token?
  [token]
  (let [token (str token)]
    (and (seq token)
         (not (str/starts-with? token "-"))
         (not (str/starts-with? token "."))
         (not (str/starts-with? token "/"))
         (not (str/starts-with? token "$"))
         (not (str/includes? token "://")))))

(defn pip-install-dependencies
  "Return PyPI package facts from explicit pip install command lines."
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (when-let [[_ args] (re-find pip-install-pattern line)]
                   (loop [tokens (shell-tokens args)
                          skip-next? false
                          out []]
                     (if-let [token (first tokens)]
                       (cond
                         skip-next?
                         (recur (rest tokens) false out)

                         (option-with-value? token)
                         (recur (rest tokens)
                                (not (str/includes? token "="))
                                out)

                         (str/starts-with? token "-")
                         (recur (rest tokens) false out)

                         :else
                         (recur (rest tokens)
                                false
                                (cond-> out
                                  (and (package-token? token)
                                       (python-dependency-name token))
                                  (conj (common/package-fact
                                         {:ecosystem :pypi
                                          :package-name (python-dependency-name token)
                                          :version-range token
                                          :dependency-scope "pip-install-command"
                                          :source-line (inc idx)})))))
                       out)))))
       distinct
       vec))
(defn- pyproject-import-name-map
  [content]
  (->> (package-toml/toml-section-lines content "tool.ygg.import-names")
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
