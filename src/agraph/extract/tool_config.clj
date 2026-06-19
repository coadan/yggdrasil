(ns agraph.extract.tool-config
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- quality-tool
  [filename]
  (cond
    (= ".coveragerc" filename) "coverage.py"
    (= "coverage.toml" filename) "coverage.py"
    (contains? #{"mypy.ini" ".mypy.ini"} filename) "mypy"
    (contains? #{"ruff.toml" ".ruff.toml"} filename) "ruff"
    (= "sonar-project.properties" filename) "sonar"
    (= "checkstyle.xml" filename) "checkstyle"
    (= "pmd.xml" filename) "pmd"
    (= "spotbugs-exclude.xml" filename) "spotbugs"
    (contains? #{"phpstan.neon" "phpstan.neon.dist"} filename) "phpstan"
    (= "psalm.xml" filename) "psalm"
    (contains? #{".rubocop.yml" ".rubocop.yaml"} filename) "rubocop"
    (contains? #{".swiftlint.yml" ".swiftlint.yaml"} filename) "swiftlint"
    (contains? #{"detekt.yml" "detekt.yaml"} filename) "detekt"
    :else filename))
(defn- quality-assignment-facts
  [content]
  (->> (common/properties-assignment-lines content)
       (map (fn [{:keys [key value source-line]}]
              {:kind :quality-setting
               :label (str key "=" (common/strip-yaml-scalar value))
               :source-line source-line
               :relation :defines}))
       distinct
       vec))
(defn- quality-yaml-list-facts
  [content section-names kind relation]
  (->> (common/yaml-section-items content section-names)
       (map (fn [{:keys [section value source-line]}]
              {:kind kind
               :label (str section "=" value)
               :source-line source-line
               :relation relation}))
       distinct
       vec))
(defn- quality-yaml-rule-facts
  [content]
  (vec
   (distinct
    (concat
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [[_ key value]
                             (re-matches #"^([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+):\s*(.*?)\s*$"
                                         line)]
                    {:kind :quality-rule
                     :label (if (seq value)
                              (str key "=" (common/strip-yaml-scalar value))
                              key)
                     :source-line (inc idx)
                     :relation :defines}))))
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [{:keys [indent key value source-line]} (common/yaml-key-line idx line)]
                    (when (and (zero? indent)
                               (re-find #"/" key)
                               (not= "Enabled" key))
                      {:kind :quality-rule
                       :label (if (seq value)
                                (str key "=" (common/strip-yaml-scalar value))
                                key)
                       :source-line source-line
                       :relation :defines})))))))))
(defn- quality-neon-facts
  [content]
  (let [section-list-values (fn [section-name]
                              (loop [remaining (map-indexed vector (str/split-lines content))
                                     active? false
                                     section-indent nil
                                     out []]
                                (if-let [[idx line] (first remaining)]
                                  (let [entry (common/yaml-key-line idx line)
                                        active* (cond
                                                  (and entry
                                                       (= section-name (:key entry))
                                                       (str/blank? (:value entry)))
                                                  true

                                                  (and active?
                                                       entry
                                                       (<= (:indent entry) section-indent))
                                                  false

                                                  :else active?)
                                        section-indent* (cond
                                                          (and entry
                                                               (= section-name (:key entry))
                                                               (str/blank? (:value entry)))
                                                          (:indent entry)

                                                          (not active*) nil
                                                          :else section-indent)
                                        list-value (when (and active*
                                                              (> (common/leading-spaces line)
                                                                 section-indent*))
                                                     (some->> (re-matches #"^\s*-\s+(.+?)\s*$"
                                                                          line)
                                                              second
                                                              common/strip-yaml-scalar))]
                                    (recur (rest remaining)
                                           active*
                                           section-indent*
                                           (cond-> out
                                             list-value (conj {:value list-value
                                                               :source-line (inc idx)}))))
                                  (vec (distinct out)))))]
    (vec
     (concat
      (map (fn [{:keys [value source-line]}]
             {:kind :quality-include
              :label (str "includes=" value)
              :source-line source-line
              :relation :references})
           (section-list-values "includes"))
      (map (fn [{:keys [value source-line]}]
             {:kind :quality-path
              :label (str "paths=" value)
              :source-line source-line
              :relation :references})
           (section-list-values "paths"))
      (quality-assignment-facts content)))))
(defn- quality-xml-facts
  [content filename]
  (let [checkstyle-rules (when (= "checkstyle.xml" filename)
                           (->> (re-seq #"<module\s+[^>]*name=\"([^\"]+)\"" content)
                                (map second)
                                (map (fn [rule]
                                       {:kind :quality-rule
                                        :label rule
                                        :source-line 1
                                        :relation :defines}))))
        pmd-rules (when (= "pmd.xml" filename)
                    (->> (re-seq #"<rule\s+[^>]*ref=\"([^\"]+)\"" content)
                         (map second)
                         (map (fn [rule]
                                {:kind :quality-rule
                                 :label rule
                                 :source-line 1
                                 :relation :references}))))
        spotbugs (when (= "spotbugs-exclude.xml" filename)
                   (->> (re-seq #"<(?:Class|Bug)\s+[^>]*(?:name|pattern)=\"([^\"]+)\""
                                content)
                        (map second)
                        (map (fn [rule]
                               {:kind :quality-rule
                                :label rule
                                :source-line 1
                                :relation :defines}))))
        psalm-paths (when (= "psalm.xml" filename)
                      (->> (re-seq #"<directory\s+[^>]*name=\"([^\"]+)\"" content)
                           (map second)
                           (map (fn [path]
                                  {:kind :quality-path
                                   :label path
                                   :source-line 1
                                   :relation :references}))))]
    (vec (distinct (concat checkstyle-rules pmd-rules spotbugs psalm-paths)))))
(defn- quality-config-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)
        tool (quality-tool filename)]
    (vec
     (distinct
      (concat
       [{:kind :quality-tool
         :label tool
         :source-line 1
         :relation :uses}]
       (cond
         (contains? #{"checkstyle.xml" "pmd.xml" "spotbugs-exclude.xml" "psalm.xml"}
                    filename)
         (quality-xml-facts content filename)

         (contains? #{"phpstan.neon" "phpstan.neon.dist"} filename)
         (quality-neon-facts content)

         (contains? #{".rubocop.yml" ".rubocop.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"inherit_from"} :quality-include :references)
          (quality-yaml-list-facts content #{"require"} :quality-plugin :uses)
          (quality-yaml-rule-facts content))

         (contains? #{".swiftlint.yml" ".swiftlint.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"included" "excluded"} :quality-path :references)
          (quality-yaml-list-facts content #{"opt_in_rules" "disabled_rules"} :quality-rule :defines))

         (contains? #{"detekt.yml" "detekt.yaml"} filename)
         (concat
          (quality-yaml-list-facts content #{"config"} :quality-include :references)
          (quality-assignment-facts content))

         :else
         (quality-assignment-facts content)))))))
(defn extract-quality-config
  "Extract bounded coverage and static-analysis configuration facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :quality-config
                        :quality-config-file
                        (quality-config-facts file)))
(defn- dependabot-update-blocks
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if (re-matches #"^\s*-\s+package-ecosystem:\s+.+?\s*$" line)
        (recur (rest remaining)
               {:source-line (inc idx)
                :lines [[idx line]]}
               (cond-> out current (conj current)))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))
(defn- yaml-block-scalar
  [lines key-name]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*(?:-\\s*)?"
                                                  key-name
                                                  ":\\s+(.+?)\\s*$"))
                                 line)]
            {:value (common/strip-yaml-scalar value)
             :source-line (inc idx)}))
        lines))
(defn- dependabot-group-pattern-facts
  [lines]
  (loop [remaining lines
         in-groups? false
         groups-indent nil
         current-group nil
         in-patterns? false
         patterns-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)
            indent (common/leading-spaces line)
            groups-start? (re-matches #"^groups:\s*$" trimmed)
            patterns-start? (and current-group
                                 (re-matches #"^patterns:\s*$" trimmed))
            group-entry (when (and in-groups?
                                   (not in-patterns?)
                                   groups-indent
                                   (> indent groups-indent))
                          (when-let [[_ label] (re-matches #"^([A-Za-z0-9_.-]+):\s*$"
                                                           trimmed)]
                            {:label label
                             :source-line (inc idx)}))
            pattern-value (when (and current-group in-patterns?)
                            (when-let [[_ value] (re-matches #"^-\s+(.+?)\s*$"
                                                             trimmed)]
                              (common/strip-yaml-scalar value)))]
        (cond
          groups-start?
          (recur (rest remaining) true indent nil false nil out)

          (and in-groups?
               groups-indent
               (<= indent groups-indent)
               (seq trimmed))
          (recur (rest remaining) false nil nil false nil out)

          patterns-start?
          (recur (rest remaining) true groups-indent current-group true indent out)

          group-entry
          (recur (rest remaining)
                 true
                 groups-indent
                 (:label group-entry)
                 false
                 nil
                 (conj out {:kind :dependency-update-group
                            :label (:label group-entry)
                            :source-line (:source-line group-entry)
                            :relation :defines}))

          (and in-patterns? pattern-value)
          (recur (rest remaining)
                 true
                 groups-indent
                 current-group
                 true
                 patterns-indent
                 (conj out {:kind :dependency-update-pattern
                            :label (str current-group ":" pattern-value)
                            :source-line (inc idx)
                            :relation :applies-to}))

          :else
          (recur (rest remaining)
                 in-groups?
                 groups-indent
                 current-group
                 (and in-patterns?
                      patterns-indent
                      (or (str/blank? trimmed)
                          (> indent patterns-indent)))
                 patterns-indent
                 out)))
      out)))
(defn- dependabot-update-facts
  [{:keys [lines source-line]}]
  (let [ecosystem (yaml-block-scalar lines "package-ecosystem")
        directory (yaml-block-scalar lines "directory")
        interval (yaml-block-scalar lines "interval")
        day (yaml-block-scalar lines "day")
        update-label (str (:value ecosystem) ":" (:value directory))]
    (vec (concat
          (when (and (:value ecosystem) (:value directory))
            [{:kind :dependency-update
              :label update-label
              :source-line source-line
              :relation :updates}])
          (when (:value ecosystem)
            [{:kind :dependency-update-ecosystem
              :label (:value ecosystem)
              :source-line (:source-line ecosystem)
              :relation :updates}])
          (when (:value directory)
            [{:kind :dependency-update-directory
              :label (:value directory)
              :source-line (:source-line directory)
              :relation :applies-to}])
          (when (:value interval)
            [{:kind :dependency-update-schedule
              :label (str update-label ":interval=" (:value interval))
              :source-line (:source-line interval)
              :relation :defines}])
          (when (:value day)
            [{:kind :dependency-update-schedule
              :label (str update-label ":day=" (:value day))
              :source-line (:source-line day)
              :relation :defines}])
          (dependabot-group-pattern-facts lines)))))
(defn- dependabot-facts
  [content]
  (->> (dependabot-update-blocks content)
       (mapcat dependabot-update-facts)
       distinct
       vec))
(defn- renovate-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))
(defn- renovate-package-rule-facts
  [rule]
  (let [group-name (:groupName rule)]
    (vec (concat
          (when (string? group-name)
            [{:kind :dependency-update-group
              :label group-name
              :source-line 1
              :relation :defines}])
          (map (fn [manager]
                 {:kind :dependency-update-manager
                  :label (common/json-label manager)
                  :source-line 1
                  :relation :updates})
               (renovate-array-values (:matchManagers rule)))
          (map (fn [pattern]
                 {:kind :dependency-update-pattern
                  :label (if (string? group-name)
                           (str group-name ":" (common/json-label pattern))
                           (common/json-label pattern))
                  :source-line 1
                  :relation :applies-to})
               (concat (renovate-array-values (:matchPackagePatterns rule))
                       (renovate-array-values (:matchPackageNames rule))))))))
(defn- renovate-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat
          (map (fn [preset]
                 {:kind :dependency-update-preset
                  :label (common/json-label preset)
                  :source-line 1
                  :relation :references})
               (renovate-array-values (:extends m)))
          (mapcat renovate-package-rule-facts
                  (filter map? (renovate-array-values (:packageRules m))))))
    []))
(defn- tool-config-facts
  [{:keys [path content] :as file}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)
        special-facts (cond
                        (re-find #"(^|/)\.github/dependabot\.ya?ml$" path-lower)
                        (dependabot-facts content)

                        (contains? #{"renovate.json" ".renovaterc" ".renovaterc.json"}
                                   filename)
                        (renovate-facts content)

                        :else
                        [])]
    (vec (concat (common/config-facts file) special-facts))))
(defn extract-tool-config
  "Extract bounded lint/format/tool configuration facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :tool-config
                        :tool-config-file
                        (tool-config-facts file)))
