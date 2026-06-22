(ns ygg.extract.package-cargo-go
  (:require [ygg.extract.common :as common]
            [ygg.extract.package-toml :as package-toml]
            [clojure.string :as str]))

(defn cargo-package-name
  [content path]
  (or (some-> (str/join "\n" (package-toml/toml-section-lines content "package"))
              (package-toml/toml-string-value "name"))
      path))
(defn cargo-dependencies
  [content]
  (let [dependency-section-facts
        (fn [section]
          (->> (package-toml/toml-section-lines content section)
               (map-indexed vector)
               (keep (fn [[idx line]]
                       (when-let [[_ package-name quoted-version map-version]
                                  (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*(?:\"([^\"]+)\"|\{.*?version\s*=\s*\"([^\"]+)\".*\}).*"
                                              line)]
                         (common/package-fact {:ecosystem :cargo
                                               :package-name package-name
                                               :version-range (or quoted-version map-version)
                                               :dependency-scope section
                                               :source-line (inc idx)}))))))
        deps (->> ["dependencies" "dev-dependencies" "build-dependencies"
                   "workspace.dependencies"]
                  (mapcat dependency-section-facts)
                  distinct)
        members (->> (package-toml/toml-section-lines content "workspace")
                     (keep #(second (re-matches #"^\s*members\s*=\s*(\[.*\])\s*$" %)))
                     (mapcat common/toml-array-strings)
                     (map (fn [member]
                            {:kind :workspace-pattern
                             :label member
                             :source-line 1
                             :relation :references})))
        features (->> (package-toml/toml-section-lines content "features")
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ feature]
                                         (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=.*" line)]
                                {:kind :package-feature
                                 :label feature
                                 :source-line (inc idx)
                                 :relation :defines}))))]
    (->> (concat deps members features)
         distinct
         vec)))
(defn go-module-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*module\s+(\S+)\s*$" content) second)
      path))
(defn go-mod-requires
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining (map-indexed vector lines)
           in-require-block? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (-> line
                          (str/replace #"//.*$" "")
                          str/trim)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) in-require-block? out)

            (re-matches #"^require\s*\(\s*$" trimmed)
            (recur (rest remaining) true out)

            (and in-require-block? (= ")" trimmed))
            (recur (rest remaining) false out)

            :else
            (let [[package-name version] (or (some->> trimmed
                                                      (re-matches #"^require\s+(\S+)\s+(\S+).*$")
                                                      rest)
                                             (when in-require-block?
                                               (some->> trimmed
                                                        (re-matches #"^(\S+)\s+(\S+).*$")
                                                        rest)))]
              (recur (rest remaining)
                     in-require-block?
                     (cond-> out
                       package-name
                       (conj (common/package-fact {:ecosystem :go
                                                   :package-name package-name
                                                   :version-range version
                                                   :source-line (inc idx)})))))))
        (vec (distinct out))))))
(defn go-mod-extra-facts
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [trimmed (-> line
                              (str/replace #"//.*$" "")
                              str/trim)
                  source-line (inc idx)]
              (concat
               (when-let [[_ version] (re-matches #"^go\s+(\S+)\s*$" trimmed)]
                 [{:kind :go-version
                   :label version
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ version] (re-matches #"^toolchain\s+(\S+)\s*$" trimmed)]
                 [{:kind :go-toolchain
                   :label version
                   :source-line source-line
                   :relation :uses}])
               (when-let [[_ source target] (re-matches #"^replace\s+(\S+)\s+=>\s+(\S+).*$" trimmed)]
                 [{:kind :project-reference
                   :label (str source "=>" target)
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ package version] (re-matches #"^exclude\s+(\S+)\s+(\S+).*$" trimmed)]
                 [{:kind :go-exclude
                   :label (str package "@" version)
                   :source-line source-line
                   :relation :defines}])))))
         distinct
         vec)))
(defn go-work-uses
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining (map-indexed vector lines)
           in-use-block? false
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (-> line
                          (str/replace #"//.*$" "")
                          str/trim)]
          (cond
            (str/blank? trimmed)
            (recur (rest remaining) in-use-block? out)

            (re-matches #"^use\s*\(\s*$" trimmed)
            (recur (rest remaining) true out)

            (and in-use-block? (= ")" trimmed))
            (recur (rest remaining) false out)

            :else
            (let [target (or (second (re-matches #"^use\s+(\S+).*$" trimmed))
                             (when in-use-block?
                               (second (re-matches #"^(\S+).*$" trimmed))))]
              (recur (rest remaining)
                     in-use-block?
                     (cond-> out
                       target
                       (conj {:kind :project-reference
                              :label target
                              :source-line (inc idx)
                              :relation :references}))))))
        (vec (distinct out))))))
