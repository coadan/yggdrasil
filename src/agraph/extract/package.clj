(ns agraph.extract.package
  (:require [agraph.extract.common :as common]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- xml-tag-values
  [content tag]
  (let [pattern (re-pattern (str "(?is)<" tag "\\b[^>]*>(.*?)</" tag ">"))]
    (->> (re-seq pattern content)
         (map second)
         (map str/trim)
         (remove str/blank?)
         distinct
         vec)))
(defn- xml-attr-value
  [element attr]
  (or (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*\"([^\"]+)\""))
                       element))
      (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*'([^']+)'"))
                       element))))
(defn- maven-coordinates
  [content]
  (let [project-content (str/replace content
                                     #"(?is)<parent\b[^>]*>.*?</parent>"
                                     "")
        group-id (first (xml-tag-values project-content "groupId"))
        artifact-id (first (xml-tag-values project-content "artifactId"))]
    (when artifact-id
      (if group-id
        (str group-id ":" artifact-id)
        artifact-id))))
(defn- maven-dependencies
  [content]
  (->> (re-seq #"(?is)<dependency\b[^>]*>(.*?)</dependency>" content)
       (map second)
       (keep (fn [dependency]
               (let [group-id (first (xml-tag-values dependency "groupId"))
                     artifact-id (first (xml-tag-values dependency "artifactId"))
                     version (first (xml-tag-values dependency "version"))
                     scope (first (xml-tag-values dependency "scope"))]
                 (when (and group-id artifact-id)
                   (common/package-fact {:ecosystem :maven
                                  :package-name (str group-id ":" artifact-id)
                                  :version-range version
                                  :dependency-scope scope
                                  :source-line 1})))))
       distinct
       vec))
(defn- maven-coordinate-fact
  [kind relation block]
  (let [group-id (first (xml-tag-values block "groupId"))
        artifact-id (first (xml-tag-values block "artifactId"))]
    (when (and group-id artifact-id)
      {:kind kind
       :label (str group-id ":" artifact-id)
       :source-line 1
       :relation relation})))
(defn- maven-module-facts
  [content]
  (->> (xml-tag-values content "module")
       (map (fn [module]
              {:kind :maven-module
               :label module
               :source-line 1
               :relation :defines}))
       distinct
       vec))
(defn- maven-plugin-facts
  [content]
  (->> (re-seq #"(?is)<plugin\b[^>]*>(.*?)</plugin>" content)
       (map second)
       (keep #(maven-coordinate-fact :maven-plugin :uses %))
       distinct
       vec))
(defn- maven-profile-facts
  [content]
  (->> (re-seq #"(?is)<profile\b[^>]*>(.*?)</profile>" content)
       (map second)
       (keep (fn [profile]
               (when-let [profile-id (first (xml-tag-values profile "id"))]
                 {:kind :maven-profile
                  :label profile-id
                  :source-line 1
                  :relation :defines})))
       distinct
       vec))
(defn- maven-repository-facts
  [content]
  (->> (re-seq #"(?is)<repository\b[^>]*>(.*?)</repository>" content)
       (map second)
       (mapcat (fn [repository]
                 (let [repo-id (first (xml-tag-values repository "id"))
                       url (first (xml-tag-values repository "url"))]
                   (remove nil?
                           [(when repo-id
                              {:kind :maven-repository
                               :label repo-id
                               :source-line 1
                               :relation :references})
                            (when url
                              {:kind :maven-repository
                               :label url
                               :source-line 1
                               :relation :references})]))))
       distinct
       vec))
(defn- maven-build-facts
  [content]
  (vec (remove nil?
               [(when-let [packaging (first (xml-tag-values content "packaging"))]
                  {:kind :maven-packaging
                   :label packaging
                   :source-line 1
                   :relation :defines})
                (when-let [directory (first (xml-tag-values content "directory"))]
                  {:kind :maven-build-output
                   :label directory
                   :source-line 1
                   :relation :defines})
                (when-let [final-name (first (xml-tag-values content "finalName"))]
                  {:kind :maven-build-output
                   :label final-name
                   :source-line 1
                   :relation :defines})])))
(defn- maven-facts
  [content]
  (vec (concat
        (maven-dependencies content)
        (maven-module-facts content)
        (when-let [parent (some->> (re-find #"(?is)<parent\b[^>]*>(.*?)</parent>"
                                            content)
                                   second
                                   (maven-coordinate-fact :maven-parent
                                                          :references))]
          [parent])
        (maven-plugin-facts content)
        (maven-profile-facts content)
        (maven-repository-facts content)
        (maven-build-facts content))))
(defn- gradle-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*rootProject\.name\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      (some-> (re-find #"(?m)^\s*name\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      path))
(defn- gradle-line-facts
  [content fact-fn]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (fact-fn (inc idx) line)))
       (remove nil?)
       distinct
       vec))
(defn- gradle-dependencies
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (let [scope-pattern #"(api|compileOnly|implementation|runtimeOnly|testImplementation|testRuntimeOnly|testFixturesImplementation|annotationProcessor|kapt|classpath)"
           string-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                   "\\s*(?:\\(?\\s*)['\"]([^:'\"]+):([^:'\"]+)(?::([^'\"]+))?['\"].*$"))
                                  line)
           map-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                "\\s*(?:\\(?\\s*)group\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*:\\s*['\"]([^'\"]+)['\"])?\\s*\\)?.*$"))
                               line)
           project-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                    "\\s*(?:\\(?\\s*)project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\).*$"))
                                   line)]
       (cond
         string-dep
         (let [[_ scope group-id artifact-id version] string-dep]
           [(common/package-fact {:ecosystem :maven
                           :package-name (str group-id ":" artifact-id)
                           :version-range version
                           :dependency-scope scope
                           :source-line source-line})])

         map-dep
         (let [[_ scope group-id artifact-id version] map-dep]
           [(common/package-fact {:ecosystem :maven
                           :package-name (str group-id ":" artifact-id)
                           :version-range version
                           :dependency-scope scope
                           :source-line source-line})])

         project-dep
         (let [[_ scope project-path] project-dep]
           [{:kind :gradle-project-dependency
             :label project-path
             :dependency-scope scope
             :source-line source-line
             :relation :requires}])

         :else [])))))
(defn- gradle-plugins
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*id\s+['\"]([^'\"]+)['\"].*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*id\s+['\"]([^'\"]+)['\"].*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*id\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*id\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*alias\s*\(\s*([A-Za-z0-9_.-]+)\s*\).*$" line)
       (let [[_ plugin-ref] (re-matches #"^\s*alias\s*\(\s*([A-Za-z0-9_.-]+)\s*\).*$" line)]
         [{:kind :gradle-plugin
           :label (str "alias:" plugin-ref)
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*apply\s+plugin:\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ plugin-id] (re-matches #"^\s*apply\s+plugin:\s*['\"]([^'\"]+)['\"].*$" line)]
         [{:kind :gradle-plugin
           :label plugin-id
           :source-line source-line
           :relation :uses}])

       :else []))))
(defn- gradle-repositories
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*(mavenCentral|google|gradlePluginPortal)\s*\(\s*\).*$" line)
       (let [[_ repository] (re-matches #"^\s*(mavenCentral|google|gradlePluginPortal)\s*\(\s*\).*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*maven\s*\{\s*url\s+['\"]([^'\"]+)['\"]\s*\}.*$" line)
       (let [[_ repository] (re-matches #"^\s*maven\s*\{\s*url\s+['\"]([^'\"]+)['\"]\s*\}.*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*url\s*(?:=)?\s*(?:uri\s*\()?\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ repository] (re-matches #"^\s*url\s*(?:=)?\s*(?:uri\s*\()?\s*['\"]([^'\"]+)['\"].*$"
                                        line)]
         [{:kind :package-repository
           :label repository
           :source-line source-line
           :relation :uses}])

       :else []))))
(defn- gradle-command-label
  [value]
  (->> (str/split (str value) #",")
       (map common/strip-yaml-scalar)
       (remove str/blank?)
       (str/join " ")))
(defn- gradle-modules
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (let [include-values (when-let [[_ values]
                                     (or (re-matches #"^\s*include\s+(.+?)\s*$" line)
                                         (re-matches #"^\s*include\s*\((.+?)\)\s*$" line))]
                            (->> (str/split values #",")
                                 (map #(str/replace % #"^[\s'\"]+|[\s'\"]+$" ""))
                                 (remove str/blank?)))
           project-dir (when-let [[_ module dir]
                                  (re-matches #"^\s*project\s*\(\s*['\"]([^'\"]+)['\"]\s*\)\.projectDir\s*=\s*file\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                              line)]
                         {:module module
                          :dir dir})]
       (cond
         (seq include-values)
         (map (fn [module-name]
                {:kind :gradle-module
                 :label module-name
                 :source-line source-line
                 :relation :defines})
              include-values)

         project-dir
         [{:kind :gradle-module-path
           :label (str (:module project-dir) "=" (:dir project-dir))
           :source-line source-line
           :relation :references}]

         :else [])))))
(defn- gradle-tasks
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (cond
       (re-matches #"^\s*tasks\.(?:register|create)\s*\(\s*['\"]([^'\"]+)['\"].*$" line)
       (let [[_ task-name] (re-matches #"^\s*tasks\.(?:register|create)\s*\(\s*['\"]([^'\"]+)['\"].*$"
                                       line)]
         [{:kind :task
           :label task-name
           :source-line source-line
           :relation :defines}])

       (re-matches #"^\s*task\s+([A-Za-z0-9_.-]+)\b.*$" line)
       (let [[_ task-name] (re-matches #"^\s*task\s+([A-Za-z0-9_.-]+)\b.*$" line)]
         [{:kind :task
           :label task-name
           :source-line source-line
           :relation :defines}])

       (re-matches #"^\s*commandLine\s+(.+?)\s*$" line)
       (let [[_ command] (re-matches #"^\s*commandLine\s+(.+?)\s*$" line)]
         [{:kind :task-command
           :label (gradle-command-label command)
           :source-line source-line
           :relation :uses}])

       (re-matches #"^\s*dependsOn\s*\(?\s*['\"]([^'\"]+)['\"]\s*\)?.*$" line)
       (let [[_ task-name] (re-matches #"^\s*dependsOn\s*\(?\s*['\"]([^'\"]+)['\"]\s*\)?.*$"
                                       line)]
         [{:kind :task-dependency
           :label (str "dependsOn:" task-name)
           :source-line source-line
           :relation :requires}])

       :else []))))
(defn- gradle-facts
  [content]
  (vec (distinct (concat (gradle-dependencies content)
                         (gradle-plugins content)
                         (gradle-repositories content)
                         (gradle-modules content)
                         (gradle-tasks content)))))
(defn- dotnet-package-references
  [content]
  (->> (re-seq #"(?is)<PackageReference\b[^>]*(?:/>|>.*?</PackageReference>)"
               content)
       (keep (fn [element]
               (when-let [package (xml-attr-value element "Include")]
                 (common/package-fact {:ecosystem :nuget
                                :package-name package
                                :version-range (xml-attr-value element "Version")
                                :source-line 1}))))
       distinct
       vec))
(defn- dotnet-project-references
  [content]
  (->> (re-seq #"(?is)<ProjectReference\b[^>]*(?:/>|>.*?</ProjectReference>)"
               content)
       (keep (fn [element]
               (when-let [project-path (xml-attr-value element "Include")]
                 {:kind :project-reference
                  :label (str/replace project-path "\\" "/")
                  :source-line 1
                  :relation :references})))
       distinct
       vec))
(defn- dotnet-target-frameworks
  [content]
  (let [single (xml-tag-values content "TargetFramework")
        multiple (->> (xml-tag-values content "TargetFrameworks")
                      (mapcat #(str/split % #";"))
                      (map str/trim)
                      (remove str/blank?))]
    (->> (concat single multiple)
         distinct
         (mapv (fn [framework]
                 {:kind :runtime
                  :label framework
                  :source-line 1
                  :relation :uses})))))
(defn- ruby-gem-dependencies
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
(defn- gemspec-name
  [content]
  (some-> (re-find #"(?m)^\s*[^#\n]*\.name\s*=\s*['\"]([^'\"]+)['\"]" content)
          second))
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
(defn- pubspec-dependencies
  [content]
  (->> (yaml-section-keys content ["dependencies" "dev_dependencies"])
       (mapv (fn [{:keys [label source-line]}]
               (common/package-fact {:ecosystem :pub
                              :package-name label
                              :source-line source-line})))))
(defn- sbt-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*name\s*:=\s*\"([^\"]+)\"" content)
              second)
      path))
(defn- sbt-dependencies
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
(defn- mix-project-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*app:\s*:([A-Za-z_][A-Za-z0-9_]*)" content)
              second
              (str/replace "_" "-"))
      path))
(defn- mix-dependencies
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
(defn- rebar-dependencies
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
(defn- r-description-package-name
  [content path]
  (or (field-value content "Package") path))
(defn- r-description-dependencies
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
(defn- r-namespace-facts
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
(defn- toml-name-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name "\\s*=\\s*\"([^\"]+)\"")) content)
          second))
(defn- project-toml-dependencies
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
(defn- cpanfile-dependencies
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
(defn- cabal-package-name
  [content path]
  (or (some-> (re-find #"(?mi)^\s*name:\s*([A-Za-z0-9_.-]+)\s*$" content)
              second)
      path))
(defn- cabal-dependencies
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
(defn- stack-yaml-dependencies
  [content]
  (->> (yaml-section-keys content ["extra-deps" "packages"])
       (mapv (fn [{:keys [label source-line]}]
               {:kind :project-reference
                :label label
                :source-line source-line
                :relation :references}))))
(defn- solution-projects
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ name project-path]
                          (re-matches
                           #"^Project\(\"[^\"]+\"\)\s*=\s*\"([^\"]+)\",\s*\"([^\"]+)\",\s*\"[^\"]+\".*"
                           line)]
                 {:kind :dotnet-project
                  :label (str name " " (str/replace project-path "\\" "/"))
                  :source-line (inc idx)
                  :relation :defines})))
       vec))
(defn- package-json-project-label
  [content path]
  (or (:name (common/read-json-map content))
      path))
(defn- dependency-scope
  [k]
  (case k
    :dependencies "runtime"
    :devDependencies "development"
    :peerDependencies "peer"
    :optionalDependencies "optional"
    nil))
(defn- dependency-map-facts
  [m keys]
  (->> keys
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (map (fn [[dep-name version]]
                            (common/package-fact {:ecosystem :npm
                                           :package-name (common/json-key-label dep-name)
                                           :version-range (when (string? version) version)
                                           :dependency-scope (dependency-scope k)
                                           :source-line 1}))
                          deps)))))
       (remove nil?)
       distinct
       vec))
(defn- package-script-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           keys
           (map (fn [script-name]
                  {:kind :package-script
                   :label (common/json-key-label script-name)
                   :source-line 1
                   :relation :defines}))
           distinct
           vec))))
(defn- package-script-command-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           (keep (fn [[script-name command]]
                   (when (string? command)
                     {:kind :package-script-command
                      :label (str (common/json-key-label script-name) "=" command)
                      :source-line 1
                      :relation :defines})))
           distinct
           vec))))
(defn- package-json-metadata-facts
  [m]
  (vec
   (concat
    (when (string? (:version m))
      [{:kind :package-version
        :label (:version m)
        :source-line 1
        :relation :defines}])
    (when (string? (:type m))
      [{:kind :package-type
        :label (:type m)
        :source-line 1
        :relation :defines}])
    (when (contains? m :private)
      [{:kind :package-private
        :label (str "private=" (:private m))
        :source-line 1
        :relation :defines}])
    (let [bin (:bin m)]
      (cond
        (string? bin)
        [{:kind :package-bin
          :label bin
          :source-line 1
          :relation :defines}]

        (map? bin)
        (map (fn [[name target]]
               {:kind :package-bin
                :label (str (common/json-key-label name) "=" target)
                :source-line 1
                :relation :defines})
             bin)

        :else []))
    (let [exports (:exports m)]
      (cond
        (string? exports)
        [{:kind :package-export
          :label exports
          :source-line 1
          :relation :defines}]

        (map? exports)
        (map (fn [[name target]]
               {:kind :package-export
                :label (str (common/json-key-label name)
                            "="
                            (if (string? target) target (pr-str target)))
                :source-line 1
                :relation :defines})
             exports)

        :else []))
    (when (map? (:engines m))
      (map (fn [[name value]]
             {:kind :package-engine
              :label (str (common/json-key-label name) "=" value)
              :source-line 1
              :relation :defines})
           (:engines m))))))
(defn- workspace-patterns
  [value]
  (cond
    (vector? value) (filterv string? value)
    (map? value) (filterv string? (:packages value))
    :else []))
(defn- package-workspace-facts
  [m]
  (->> (workspace-patterns (:workspaces m))
       (map (fn [pattern]
              {:kind :workspace-pattern
               :label pattern
               :source-line 1
               :relation :references}))
       distinct
       vec))
(defn- workspace-protocol-dependency-facts
  [m]
  (->> [:dependencies :devDependencies :peerDependencies :optionalDependencies]
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (keep (fn [[dep-name version]]
                             (when (and (string? version)
                                        (str/starts-with? version "workspace:"))
                               {:kind :workspace-dependency
                                :label (str (common/json-key-label dep-name) "=" version)
                                :source-line 1
                                :relation :references}))
                           deps)))))
       (remove nil?)
       distinct
       vec))
(defn- package-manager-fact
  [m]
  (when-let [package-manager (or (:packageManager m)
                                 (:npmClient m)
                                 (:pnpmVersion m)
                                 (:yarnVersion m))]
    [{:kind :package-manager
      :label package-manager
      :source-line 1
      :relation :uses}]))
(defn- package-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat (dependency-map-facts m
                                       [:dependencies
                                        :devDependencies
                                        :peerDependencies
                                        :optionalDependencies])
                 (package-script-facts m)
                 (package-script-command-facts m)
                 (package-json-metadata-facts m)
                 (package-workspace-facts m)
                 (workspace-protocol-dependency-facts m)
                 (package-manager-fact m)))
    []))
(defn- deno-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (let [imports (:imports m)
          tasks (:tasks m)]
      (vec (concat
            (when (map? imports)
              (map (fn [[alias target]]
                     (common/package-fact {:ecosystem :deno
                                    :package-name (common/json-key-label alias)
                                    :version-range (str target)
                                    :source-line 1}))
                   imports))
            (when (map? tasks)
              (map (fn [[task _command]]
                     {:kind :package-script
                      :label (common/json-key-label task)
                      :source-line 1
                      :relation :defines})
                   tasks)))))
    []))
(defn- pnpm-workspace-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         catalog nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packages:\s*" trimmed)
          (recur (rest remaining) :packages nil out)

          (re-matches #"catalog:\s*" trimmed)
          (recur (rest remaining) :catalog nil out)

          (re-matches #"catalogs:\s*" trimmed)
          (recur (rest remaining) :catalogs nil out)

          (re-matches #"onlyBuiltDependencies:\s*" trimmed)
          (recur (rest remaining) :built-dependencies nil out)

          (and (= :packages section) (str/starts-with? trimmed "-"))
          (let [pattern (-> trimmed
                            (str/replace #"^-\s*" "")
                            (str/replace #"^['\"]|['\"]$" "")
                            str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq pattern)
                     (conj {:kind :workspace-pattern
                            :label pattern
                            :source-line (inc idx)
                            :relation :references}))))

          (and (= :built-dependencies section) (str/starts-with? trimmed "-"))
          (let [dependency (-> trimmed
                               (str/replace #"^-\s*" "")
                               (str/replace #"^['\"]|['\"]$" "")
                               str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq dependency)
                     (conj {:kind :pnpm-built-dependency
                            :label dependency
                            :source-line (inc idx)
                            :relation :uses}))))

          (and (= :catalog section)
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ catalog-name] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   catalog-name
                   (conj out {:kind :pnpm-catalog
                              :label catalog-name
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               catalog
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str catalog ":" package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and section (not (str/blank? trimmed)))
          (recur (rest remaining) nil nil out)

          :else
          (recur (rest remaining) section catalog out)))
      out)))
(def yarnrc-setting-keys
  #{"nodeLinker" "yarnPath" "npmRegistryServer" "checksumBehavior"
    "enableGlobalCache" "enableImmutableInstalls" "compressionLevel"})
(defn- yarnrc-setting-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (when-let [[_ key value]
                     (re-matches #"^([A-Za-z][A-Za-z0-9_-]*):\s*(.+?)\s*$"
                                 line)]
            (let [value (str/replace value #"^['\"]|['\"]$" "")
                  source-line (inc idx)]
              (when (contains? yarnrc-setting-keys key)
                (concat
                 [{:kind :yarn-setting
                   :label (str key "=" value)
                   :source-line source-line
                   :relation :defines}]
                 (case key
                   "nodeLinker" [{:kind :yarn-node-linker
                                  :label value
                                  :source-line source-line
                                  :relation :defines}]
                   "yarnPath" [{:kind :yarn-path
                                :label value
                                :source-line source-line
                                :relation :references}]
                   "npmRegistryServer" [{:kind :yarn-registry
                                         :label value
                                         :source-line source-line
                                         :relation :uses}]
                   [])))))))
       (remove nil?)
       distinct
       vec))
(defn- yarnrc-plugin-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-plugins? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"plugins:\s*$" trimmed)
          (recur (rest remaining) true out)

          (and in-plugins?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false out)

          (and in-plugins?
               (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          (and in-plugins?
               (re-matches #"^(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-plugins? out)))
      (vec (distinct out)))))
(defn- yarnrc-npm-scope-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-scopes? false
         current-scope nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"npmScopes:\s*$" trimmed)
          (recur (rest remaining) true nil out)

          (and in-scopes?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil out)

          (and in-scopes?
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ scope] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   true
                   scope
                   (conj out {:kind :yarn-npm-scope
                              :label scope
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmRegistryServer:\s*(.+?)\s*$" trimmed))
          (let [[_ registry] (re-matches #"^npmRegistryServer:\s*(.+?)\s*$"
                                         trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-scope-registry
                              :label (str current-scope "="
                                          (str/replace registry #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :uses})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmAuth(?:Token|Ident):\s*(.+?)\s*$" trimmed))
          (let [[_ auth-key _] (re-matches #"^(npmAuth(?:Token|Ident)):\s*(.+?)\s*$"
                                           trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-auth-key
                              :label (str current-scope ":" auth-key)
                              :source-line (inc idx)
                              :relation :defines})))

          :else
          (recur (rest remaining) in-scopes? current-scope out)))
      (vec (distinct out)))))
(defn- yarnrc-package-extension-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-extensions? false
         current-extension nil
         in-deps? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packageExtensions:\s*$" trimmed)
          (recur (rest remaining) true nil false out)

          (and in-extensions?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil false out)

          (and in-extensions?
               (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$" trimmed))
          (let [[_ extension] (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   extension
                   false
                   (conj out {:kind :yarn-package-extension
                              :label extension
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-extensions?
               current-extension
               (contains? #{"dependencies:" "peerDependencies:"} trimmed))
          (recur (rest remaining) true current-extension true out)

          (and in-extensions?
               current-extension
               in-deps?
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   true
                   current-extension
                   true
                   (conj out {:kind :yarn-extension-dependency
                              :label (str current-extension
                                          ":"
                                          package-name
                                          "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-extensions? current-extension in-deps? out)))
      (vec (distinct out)))))
(defn- yarnrc-facts
  [content]
  (vec (concat (yarnrc-setting-facts content)
               (yarnrc-plugin-facts content)
               (yarnrc-npm-scope-facts content)
               (yarnrc-package-extension-facts content))))
(defn- json-key-facts
  [m key kind relation]
  (let [value (get m key)]
    (cond
      (map? value)
      (->> value
           keys
           (map (fn [entry]
                  {:kind kind
                   :label (common/json-key-label entry)
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      (vector? value)
      (->> value
           (keep (fn [entry]
                   (cond
                     (string? entry) entry
                     (map? entry) (or (:packageName entry)
                                      (:projectFolder entry)
                                      (:name entry))
                     :else nil)))
           (map (fn [entry]
                  {:kind kind
                   :label entry
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      :else [])))
(defn- workspace-json-facts
  [content filename]
  (if-let [m (common/read-json-map content)]
    (case filename
      "turbo.json"
      (json-key-facts m :tasks :workspace-task :defines)

      "nx.json"
      (vec (concat (json-key-facts m :targetDefaults :workspace-task :defines)
                   (json-key-facts m :projects :workspace-project :references)))

      "workspace.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (json-key-facts m :targets :workspace-task :defines)))

      "lerna.json"
      (vec (concat (->> (workspace-patterns (:packages m))
                        (map (fn [pattern]
                               {:kind :workspace-pattern
                                :label pattern
                                :source-line 1
                                :relation :references})))
                   (package-manager-fact m)))

      "rush.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (package-manager-fact m)))

      [])
    []))
(defn- toml-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*=\\s*\"([^\"]+)\""))
                   content)
          second))
(defn- toml-section-lines
  [content section-name]
  (loop [remaining (str/split-lines content)
         in-section? false
         out []]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining) (= trimmed (str "[" section-name "]")) out)

          in-section?
          (recur (rest remaining) in-section? (conj out line))

          :else
          (recur (rest remaining) in-section? out)))
      out)))
(defn- toml-string-or-array-value
  [line]
  (or (some-> (re-find #"=\s*\"([^\"]+)\"" line) second vector)
      (some-> (re-find #"=\s*(\[[^\]]*\])" line) second common/toml-array-strings)))
(defn- cargo-package-name
  [content path]
  (or (some-> (str/join "\n" (toml-section-lines content "package"))
              (toml-string-value "name"))
      path))
(defn- cargo-dependencies
  [content]
  (let [dependency-section-facts
        (fn [section]
          (->> (toml-section-lines content section)
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
        members (->> (toml-section-lines content "workspace")
                     (keep #(second (re-matches #"^\s*members\s*=\s*(\[.*\])\s*$" %)))
                     (mapcat common/toml-array-strings)
                     (map (fn [member]
                            {:kind :workspace-pattern
                             :label member
                             :source-line 1
                             :relation :references})))
        features (->> (toml-section-lines content "features")
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
(defn- go-module-name
  [content path]
  (or (some-> (re-find #"(?m)^\s*module\s+(\S+)\s*$" content) second)
      path))
(defn- go-mod-requires
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
(defn- go-mod-extra-facts
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
(defn- go-work-uses
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
(defn- composer-project-name
  [content path]
  (or (:name (common/read-json-map content))
      path))
(defn- composer-dependency-scope
  [k]
  (case k
    :require "runtime"
    :require-dev "development"
    nil))
(defn- composer-json-facts
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
(defn- pyproject-name
  [content path]
  (or (some-> (str/join "\n" (toml-section-lines content "project"))
              (toml-string-value "name"))
      (some-> (str/join "\n" (toml-section-lines content "tool.poetry"))
              (toml-string-value "name"))
      path))
(defn- python-dependency-name
  [value]
  (some-> (re-find #"^\s*([A-Za-z0-9_.-]+)" value)
          second))
(defn- pyproject-import-name-map
  [content]
  (->> (toml-section-lines content "tool.agraph.import-names")
       (keep (fn [line]
               (when-let [[_ package-name]
                          (re-matches #"^\s*\"?([A-Za-z0-9_.-]+)\"?\s*=.*" line)]
                 (when-let [import-names (seq (toml-string-or-array-value line))]
                   [package-name (vec import-names)]))))
       (into {})))
(defn- pyproject-dependencies
  [content]
  (let [project-lines (toml-section-lines content "project")
        import-name-map (pyproject-import-name-map content)
        inline-deps (->> project-lines
                         (keep #(second (re-matches #"^\s*dependencies\s*=\s*\[(.*)\]\s*$" %)))
                         (mapcat #(re-seq #"\"([^\"]+)\"" %))
                         (map second))
        poetry-deps (->> (toml-section-lines content "tool.poetry.dependencies")
                         (keep #(when-let [[_ dep-name version]
                                           (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\".*" %)]
                                  (when-not (= "python" dep-name)
                                    [dep-name version]))))
        optional-deps (->> (toml-section-lines content "project.optional-dependencies")
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
(defn- setup-cfg-name
  [content path]
  (or (some->> (str/join "\n" (toml-section-lines content "metadata"))
               (re-find #"(?m)^\s*name\s*=\s*([A-Za-z0-9_.-]+)\s*$")
               second)
      path))
(defn- setup-cfg-dependencies
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
(defn- setup-py-name
  [content path]
  (or (some-> (re-find #"(?s)\bname\s*=\s*['\"]([^'\"]+)['\"]" content)
              second)
      path))
(defn- setup-py-dependencies
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
(defn- pipfile-dependencies
  [content]
  (->> ["packages" "dev-packages"]
       (mapcat
        (fn [section]
          (->> (toml-section-lines content section)
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
(defn- deps-edn-project-name
  [path]
  path)
(defn- deps-edn-dependencies
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
(defn- android-manifest-package
  [content]
  (some-> (re-find #"(?is)<manifest\b[^>]*>" content)
          (xml-attr-value "package")))
(defn- android-permissions
  [content]
  (->> (re-seq #"(?is)<uses-permission\b[^>]*(?:/>|>.*?</uses-permission>)"
               content)
       (keep (fn [element]
               (when-let [permission (xml-attr-value element "android:name")]
                 {:kind :android-permission
                  :label permission
                  :source-line 1
                  :relation :uses})))
       distinct
       vec))
(defn- android-components
  [content]
  (->> (re-seq #"(?is)<(activity|service|receiver|provider)\b[^>]*(?:/>|>.*?</\1>)"
               content)
       (keep (fn [[element tag]]
               (when-let [component-name (xml-attr-value element "android:name")]
                 {:kind :android-component
                  :label (str tag ":" component-name)
                  :source-line 1
                  :relation :defines})))
       distinct
       vec))
(defn- plist-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?is)<key>\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*</key>\\s*<string>(.*?)</string>"))
                   content)
          second
          str/trim))
(defn- plist-facts
  [content]
  (->> [{:kind :mobile-bundle
         :label (plist-string-value content "CFBundleIdentifier")
         :source-line 1
         :relation :defines}
        {:kind :mobile-display-name
         :label (or (plist-string-value content "CFBundleDisplayName")
                    (plist-string-value content "CFBundleName"))
         :source-line 1
         :relation :defines}]
       (filterv (comp seq :label))))
(defn- plist-key-facts
  [content kind]
  (->> (re-seq #"(?is)<key>\s*([^<]+?)\s*</key>\s*(?:<string>\s*([^<]+?)\s*</string>|<(true|false)\s*/>|<array>\s*(.*?)\s*</array>)"
               content)
       (map-indexed
        (fn [idx [_ key string-value bool-value array-value]]
          (let [array-items (->> (or array-value "")
                                 (re-seq #"(?is)<string>\s*([^<]+?)\s*</string>")
                                 (map second)
                                 (map str/trim)
                                 (remove str/blank?))
                value (or (some-> string-value str/trim)
                          bool-value
                          (when (seq array-items)
                            (str/join "," array-items))
                          "present")]
            {:kind kind
             :label (str (str/trim key) "=" value)
             :source-line (inc idx)
             :relation :defines})))
       distinct
       vec))
(defn- podfile-facts
  [content]
  (let [lines (str/split-lines content)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-matches #"^\s*target\s+['\"]([^'\"]+)['\"]\s+do\s*$"
                                                    line)]
                               {:kind :ios-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)
        pods (->> lines
                  (map-indexed vector)
                  (keep (fn [[idx line]]
                          (when-let [[_ pod-name]
                                     (re-matches #"^\s*pod\s+['\"]([^'\"]+)['\"].*"
                                                 line)]
                            (common/package-fact {:ecosystem :cocoapods
                                           :package-name pod-name
                                           :source-line (inc idx)}))))
                  vec)]
    (vec (concat targets pods))))
(defn- swift-package-name
  [content]
  (some-> (re-find #"(?s)Package\s*\(\s*name:\s*\"([^\"]+)\"" content)
          second))
(defn- swift-package-facts
  [content]
  (let [lines (str/split-lines content)
        package-deps (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-find #"\.package\s*\(\s*url:\s*\"([^\"]+)\""
                                                      line)]
                                    (common/package-fact {:ecosystem :swiftpm
                                                   :package-name url
                                                   :source-line (inc idx)}))))
                          vec)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-find #"\.(?:target|testTarget|executableTarget)\s*\(\s*name:\s*\"([^\"]+)\""
                                                 line)]
                               {:kind :swift-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)]
    (vec (concat package-deps targets))))
(defn- xcode-project-facts
  [content]
  (let [lines (str/split-lines content)
        products (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ product-name]
                                         (re-matches #"^\s*productName\s*=\s*([^;]+);\s*$"
                                                     line)]
                                {:kind :xcode-product
                                 :label (str/replace product-name #"^\"|\"$" "")
                                 :source-line (inc idx)
                                 :relation :defines})))
                      distinct
                      vec)
        package-urls (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-matches #"^\s*repositoryURL\s*=\s*\"([^\"]+)\";\s*$"
                                                         line)]
                                    (common/package-fact {:ecosystem :swiftpm
                                                   :package-name url
                                                   :source-line (inc idx)}))))
                          distinct
                          vec)]
    (vec (concat products package-urls))))
(defn- json-string-at
  [m path]
  (let [value (get-in m path)]
    (when (string? value)
      value)))
(defn- expo-json-facts
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)
          android-package (json-string-at expo [:android :package])
          ios-bundle (json-string-at expo [:ios :bundleIdentifier])
          plugins (:plugins expo)
          plugin-labels (->> plugins
                             (keep (fn [plugin]
                                     (cond
                                       (string? plugin) plugin
                                       (vector? plugin) (first plugin)
                                       :else nil)))
                             (filter string?)
                             distinct)]
      (vec (concat
            (when android-package
              [{:kind :android-package
                :label android-package
                :source-line 1
                :relation :defines}])
            (when ios-bundle
              [{:kind :mobile-bundle
                :label ios-bundle
                :source-line 1
                :relation :defines}])
            (map (fn [plugin]
                   {:kind :expo-plugin
                    :label plugin
                    :source-line 1
                    :relation :uses})
                 plugin-labels))))
    (catch Exception _
      [])))
(defn- expo-project-label
  [content path]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)]
      (or (json-string-at expo [:name])
          (json-string-at expo [:slug])
          path))
    (catch Exception _
      path)))
(defn- js-config-string-value
  [content key-name]
  (or (some-> (re-find (re-pattern (str "(?m)\\b" key-name "\\s*:\\s*['\"]([^'\"]+)['\"]"))
                       content)
              second)
      (some-> (re-find (re-pattern (str "(?m)\"" key-name "\"\\s*:\\s*\"([^\"]+)\""))
                       content)
              second)))
(defn- json-or-js-string-at
  [content path key-name]
  (or (some-> (common/read-json-map content)
              (json-string-at path))
      (js-config-string-value content key-name)))
(defn- object-key-facts
  [m kind relation source-line]
  (when (map? m)
    (mapv (fn [[k _]]
            {:kind kind
             :label (common/json-key-label k)
             :source-line source-line
             :relation relation})
          m)))
(defn- capacitor-plugin-facts
  [content]
  (if-let [plugins (:plugins (common/read-json-map content))]
    (object-key-facts plugins :capacitor-plugin :uses 1)
    (loop [remaining (map-indexed vector (str/split-lines content))
           in-plugins? false
           depth 0
           out []]
      (if-let [[idx line] (first remaining)]
        (let [starts? (and (not in-plugins?)
                           (re-find #"\bplugins\s*:\s*\{" line))
              depth-before (if starts? 1 depth)
              plugin (when (and (or in-plugins? starts?)
                                (= 1 depth-before))
                       (some-> (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*\{?.*$" line)
                               second))
              opens (count (re-seq #"\{" line))
              closes (count (re-seq #"\}" line))
              depth* (cond
                       starts? (+ opens (- closes))
                       in-plugins? (+ depth opens (- closes))
                       :else depth)
              in-plugins* (or (and starts? (pos? depth*))
                              (and in-plugins? (pos? depth*)))]
          (recur (rest remaining)
                 in-plugins*
                 depth*
                 (cond-> out
                   (and plugin (not= "plugins" plugin))
                   (conj {:kind :capacitor-plugin
                          :label plugin
                          :source-line (inc idx)
                          :relation :uses}))))
        (->> out distinct vec)))))
(defn- capacitor-config-facts
  [content]
  (let [app-id (json-or-js-string-at content [:appId] "appId")
        app-name (json-or-js-string-at content [:appName] "appName")
        web-dir (json-or-js-string-at content [:webDir] "webDir")
        server-url (json-or-js-string-at content [:server :url] "url")]
    (vec (concat
          (when app-id
            [{:kind :mobile-bundle
              :label app-id
              :source-line 1
              :relation :defines}])
          (when app-name
            [{:kind :mobile-display-name
              :label app-name
              :source-line 1
              :relation :defines}])
          (when web-dir
            [{:kind :mobile-web-dir
              :label web-dir
              :source-line 1
              :relation :references}])
          (when server-url
            [{:kind :mobile-entry-url
              :label server-url
              :source-line 1
              :relation :references}])
          (capacitor-plugin-facts content)))))
(defn- capacitor-project-label
  [content path]
  (or (json-or-js-string-at content [:appName] "appName")
      (json-or-js-string-at content [:appId] "appId")
      path))
(defn- tauri-config-value
  [content paths key-name]
  (or (some (fn [path]
              (some-> (common/read-json-map content)
                      (json-string-at path)))
            paths)
      (js-config-string-value content key-name)))
(defn- tauri-plugin-facts
  [content]
  (let [parsed (common/read-json-map content)
        plugins (:plugins parsed)]
    (object-key-facts plugins :tauri-plugin :uses 1)))
(defn- tauri-window-facts
  [content]
  (let [parsed (common/read-json-map content)
        windows (or (get-in parsed [:app :windows])
                    (get-in parsed [:tauri :windows]))]
    (when (vector? windows)
      (->> windows
           (keep (fn [window]
                   (when (map? window)
                     (let [label (or (:label window) (:title window))]
                       (when label
                         {:kind :tauri-window
                          :label (if-let [title (:title window)]
                                   (str label ":" title)
                                   label)
                          :source-line 1
                          :relation :defines})))))
           vec))))
(defn- tauri-config-facts
  [content]
  (let [identifier (tauri-config-value content
                                       [[:identifier] [:tauri :bundle :identifier]]
                                       "identifier")
        product-name (tauri-config-value content
                                         [[:productName] [:package :productName]]
                                         "productName")
        frontend-dist (tauri-config-value content
                                          [[:build :frontendDist] [:build :distDir]]
                                          "frontendDist")
        dev-url (tauri-config-value content
                                    [[:build :devUrl] [:build :devPath]]
                                    "devUrl")
        before-dev-command (tauri-config-value content
                                               [[:build :beforeDevCommand]]
                                               "beforeDevCommand")]
    (vec (concat
          (when identifier
            [{:kind :mobile-bundle
              :label identifier
              :source-line 1
              :relation :defines}])
          (when product-name
            [{:kind :mobile-display-name
              :label product-name
              :source-line 1
              :relation :defines}])
          (when frontend-dist
            [{:kind :mobile-web-dir
              :label frontend-dist
              :source-line 1
              :relation :references}])
          (when dev-url
            [{:kind :mobile-entry-url
              :label dev-url
              :source-line 1
              :relation :references}])
          (when before-dev-command
            [{:kind :task-command
              :label before-dev-command
              :source-line 1
              :relation :uses}])
          (tauri-window-facts content)
          (tauri-plugin-facts content)))))
(defn- tauri-project-label
  [content path]
  (or (tauri-config-value content [[:productName] [:package :productName]] "productName")
      (tauri-config-value content [[:identifier] [:tauri :bundle :identifier]] "identifier")
      path))
(defn- manifest-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)
        extension (last (str/split filename #"\."))]
    (cond
      (= "pom.xml" filename)
      {:project-label (or (maven-coordinates content) path)
       :facts (maven-facts content)}

      (contains? #{"build.gradle" "build.gradle.kts" "settings.gradle"
                   "settings.gradle.kts" "gradle.properties"}
                 filename)
      {:project-label (gradle-project-name content path)
       :facts (gradle-facts content)}

      (or (contains? #{"csproj" "fsproj" "vbproj" "props" "targets"}
                     extension)
          (contains? #{"directory.build.props" "directory.build.targets"
                       "packages.config" "nuget.config"}
                     filename))
      {:project-label (or (first (xml-tag-values content "AssemblyName"))
                          (first (xml-tag-values content "PackageId"))
                          path)
       :facts (vec (concat (dotnet-package-references content)
                           (dotnet-project-references content)
                           (dotnet-target-frameworks content)))}

      (= "sln" extension)
      {:project-label path
       :facts (solution-projects content)}

      (= "package.json" filename)
      {:project-label (package-json-project-label content path)
       :facts (package-json-facts content)}

      (= "deno.json" filename)
      {:project-label (package-json-project-label content path)
       :facts (deno-json-facts content)}

      (= "cargo.toml" filename)
      {:project-label (cargo-package-name content path)
       :facts (cargo-dependencies content)}

      (= "go.mod" filename)
      {:project-label (go-module-name content path)
       :facts (vec (concat (go-mod-requires content)
                           (go-mod-extra-facts content)))}

      (= "go.work" filename)
      {:project-label path
       :facts (go-work-uses content)}

      (= "composer.json" filename)
      {:project-label (composer-project-name content path)
       :facts (composer-json-facts content)}

      (= "pyproject.toml" filename)
      {:project-label (pyproject-name content path)
       :facts (pyproject-dependencies content)}

      (= "setup.cfg" filename)
      {:project-label (setup-cfg-name content path)
       :facts (setup-cfg-dependencies content)}

      (= "setup.py" filename)
      {:project-label (setup-py-name content path)
       :facts (setup-py-dependencies content)}

      (= "pipfile" filename)
      {:project-label path
       :facts (pipfile-dependencies content)}

      (= "deps.edn" filename)
      {:project-label (deps-edn-project-name path)
       :facts (deps-edn-dependencies content)}

      (or (= "gemfile" filename)
          (= "gemspec" extension))
      {:project-label (or (gemspec-name content) path)
       :facts (ruby-gem-dependencies content)}

      (= "pubspec.yaml" filename)
      {:project-label (or (common/yaml-top-level-value content "name") path)
       :facts (pubspec-dependencies content)}

      (= "build.sbt" filename)
      {:project-label (sbt-project-name content path)
       :facts (sbt-dependencies content)}

      (= "mix.exs" filename)
      {:project-label (mix-project-name content path)
       :facts (mix-dependencies content)}

      (= "rebar.config" filename)
      {:project-label path
       :facts (rebar-dependencies content)}

      (= "description" filename)
      {:project-label (r-description-package-name content path)
       :facts (r-description-dependencies content)}

      (= "namespace" filename)
      {:project-label path
       :facts (r-namespace-facts content)}

      (= "project.toml" filename)
      {:project-label (or (toml-name-value content "name") path)
       :facts (project-toml-dependencies content)}

      (= "cpanfile" filename)
      {:project-label path
       :facts (cpanfile-dependencies content)}

      (= "cabal" extension)
      {:project-label (cabal-package-name content path)
       :facts (cabal-dependencies content)}

      (= "stack.yaml" filename)
      {:project-label path
       :facts (stack-yaml-dependencies content)}

      (contains? #{"pnpm-workspace.yaml" "pnpm-workspace.yml"} filename)
      {:project-label path
       :facts (pnpm-workspace-facts content)}

      (contains? #{".yarnrc.yml" ".yarnrc.yaml"} filename)
      {:project-label path
       :facts (yarnrc-facts content)}

      (contains? #{"turbo.json" "nx.json" "workspace.json" "lerna.json" "rush.json"} filename)
      {:project-label path
       :facts (workspace-json-facts content filename)}

      (= "androidmanifest.xml" filename)
      {:project-label (or (android-manifest-package content) path)
       :facts (vec (concat (when-let [package-name (android-manifest-package content)]
                             [{:kind :android-package
                               :label package-name
                               :source-line 1
                               :relation :defines}])
                           (android-permissions content)
                           (android-components content)))}

      (= "info.plist" filename)
      {:project-label (or (plist-string-value content "CFBundleIdentifier")
                          (plist-string-value content "CFBundleName")
                          path)
       :facts (plist-facts content)}

      (= "entitlements" extension)
      {:project-label path
       :facts (plist-key-facts content :apple-entitlement)}

      (= "podfile" filename)
      {:project-label path
       :facts (podfile-facts content)}

      (= "package.swift" filename)
      {:project-label (or (swift-package-name content) path)
       :facts (swift-package-facts content)}

      (= "pbxproj" extension)
      {:project-label path
       :facts (xcode-project-facts content)}

      (contains? #{"app.json" "app.config.json" "eas.json"} filename)
      {:project-label (expo-project-label content path)
       :facts (expo-json-facts content)}

      (contains? #{"capacitor.config.json" "capacitor.config.ts"
                   "capacitor.config.js" "capacitor.config.mjs"
                   "capacitor.config.cjs"}
                 filename)
      {:project-label (capacitor-project-label content path)
       :facts (capacitor-config-facts content)}

      (contains? #{"tauri.conf.json" "tauri.conf.json5"} filename)
      {:project-label (tauri-project-label content path)
       :facts (tauri-config-facts content)}

      :else
      {:project-label path
       :facts []})))
(defn extract-manifest
  "Extract bounded declared dependency/reference facts from project manifests."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [{:keys [project-label facts]} (manifest-facts file)
        manifest-node (common/generic-node run-id id-scope file-id path :manifest project-label 1)
        fact-nodes (mapv #(common/fact-node run-id id-scope file-id path %) facts)
        fact-edges (mapv #(common/fact-edge-row run-id
                                                file-id
                                                path
                                                (:xt/id manifest-node)
                                                id-scope
                                                %)
                          facts)
        chunk-result (common/extract-text-source run-id file :manifest-file)]
    {:nodes (into [manifest-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
(defn- lockfile-name
  [path]
  (str/lower-case (last (str/split path #"/"))))
(defn- package-lock-path-name
  [package-path]
  (let [parts (str/split (str package-path) #"/")
        marker-indexes (keep-indexed #(when (= "node_modules" %2) %1) parts)
        idx (last marker-indexes)]
    (when idx
      (let [tail (subvec (vec parts) (inc idx))]
        (cond
          (and (seq tail) (str/starts-with? (first tail) "@") (second tail))
          (str (first tail) "/" (second tail))

          (seq tail)
          (first tail)

          :else nil)))))
(defn- package-lock-version-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (->> (:packages m)
         (keep (fn [[package-path package]]
                 (when (map? package)
                   (let [package-name (or (:name package)
                                          (package-lock-path-name
                                           (common/json-key-label package-path)))
                         version (:version package)]
                     (common/package-version-fact {:ecosystem :npm
                                            :package-name package-name
                                            :resolved-version version
                                            :source-line 1})))))
         distinct
         vec)
    []))
(defn- cargo-lock-version-facts
  [content]
  (->> (str/split content #"(?m)^\[\[package\]\]\s*$")
       (keep (fn [block]
               (let [package-name (toml-string-value block "name")
                     version (toml-string-value block "version")]
                 (common/package-version-fact {:ecosystem :cargo
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))
(defn- go-sum-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-matches #"^(\S+)\s+(\S+)(?:/go\.mod)?\s+h1:.*$" line)]
                 (common/package-version-fact {:ecosystem :go
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))
(defn- package-key-name-version
  [value]
  (let [clean (-> (str value)
                  str/trim
                  (str/replace #"^\s*['\"]|['\"]\s*$" "")
                  (str/replace #"^/" "")
                  (str/replace #"\(.*\)$" ""))
        at-idx (.lastIndexOf clean "@")]
    (when (pos? at-idx)
      (let [package-name (subs clean 0 at-idx)
            version (subs clean (inc at-idx))]
        (when (and (seq package-name) (seq version))
          {:package-name package-name
           :version version})))))
(defn- pnpm-lock-version-facts
  [content]
  (->> (re-seq #"(?m)^\s{2}['\"]?/?([^'\"\s][^'\"]*?)['\"]?:\s*$" content)
       (keep (fn [[_ package-key]]
               (when-let [{:keys [package-name version]} (package-key-name-version package-key)]
                 (common/package-version-fact {:ecosystem :npm
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))
(defn- yarn-selector-package-name
  [selector]
  (let [selector (-> (str selector)
                     (str/trim)
                     (str/replace #"^['\"]|['\"]$" ""))]
    (cond
      (str/starts-with? selector "@")
      (let [idx (.indexOf selector "@" 1)]
        (when (pos? idx)
          (subs selector 0 idx)))

      :else
      (let [idx (.indexOf selector "@")]
        (when (pos? idx)
          (subs selector 0 idx))))))
(defn- yarn-header-package-names
  [line]
  (when-let [[_ header] (re-matches #"^(.+):\s*$" line)]
    (->> (str/split header #",")
         (keep yarn-selector-package-name)
         distinct
         vec)))
(defn- yarn-lock-version-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         package-names []
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (str/blank? line)
        (recur (rest remaining) package-names out)

        (and (not (str/starts-with? line " "))
             (seq (yarn-header-package-names line)))
        (recur (rest remaining) (yarn-header-package-names line) out)

        (and (seq package-names)
             (re-find #"^\s+version\s+" line))
        (let [[_ version] (re-find #"^\s+version\s+['\"]([^'\"]+)['\"]" line)
              facts (keep #(common/package-version-fact {:ecosystem :npm
                                                  :package-name %
                                                  :resolved-version version
                                                  :source-line (inc idx)})
                          package-names)]
          (recur (rest remaining) [] (into out facts)))

        :else
        (recur (rest remaining) package-names out))
      (vec (distinct out)))))
(defn- gemfile-lock-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-matches #"^\s{4}([A-Za-z0-9_.-]+)\s+\(([^)\s]+).*" line)]
                 (common/package-version-fact {:ecosystem :rubygems
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))
(defn- toml-package-version-facts
  [content ecosystem]
  (->> (str/split content #"(?m)^\[\[package\]\]\s*$")
       (keep (fn [block]
               (let [package-name (toml-string-value block "name")
                     version (toml-string-value block "version")]
                 (common/package-version-fact {:ecosystem ecosystem
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line 1}))))
       distinct
       vec))
(defn- pubspec-lock-version-facts
  [content]
  (->> (re-seq #"(?m)^\s{2}([A-Za-z0-9_.-]+):\s*$|^\s{4}version:\s+['\"]?([^'\"\n]+)['\"]?\s*$"
               content)
       (reduce (fn [{:keys [current out]} [_ package-name version]]
                 (cond
                   package-name {:current package-name :out out}
                   (and current version) {:current nil
                                          :out (conj out
                                                     (common/package-version-fact
                                                      {:ecosystem :pub
                                                       :package-name current
                                                       :resolved-version version
                                                       :source-line 1}))}
                   :else {:current current :out out}))
               {:current nil :out []})
       :out
       (remove nil?)
       distinct
       vec))
(defn- composer-lock-version-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (->> (concat (:packages m) (:packages-dev m))
         (keep (fn [package]
                 (when (map? package)
                   (common/package-version-fact {:ecosystem :composer
                                          :package-name (:name package)
                                          :resolved-version (:version package)
                                          :source-line 1}))))
         distinct
         vec)
    []))
(defn- pipfile-lock-version
  [package]
  (some-> (:version package)
          (str/replace #"^==" "")))
(defn- pipfile-lock-version-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (->> (concat (:default m) (:develop m))
         (keep (fn [[package-name package]]
                 (when (map? package)
                   (common/package-version-fact {:ecosystem :pypi
                                          :package-name (common/json-key-label package-name)
                                          :resolved-version (pipfile-lock-version package)
                                          :source-line 1}))))
         distinct
         vec)
    []))
(defn- mix-lock-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ package-name version]
                          (re-find #"\"([^\"]+)\"\s*:\s*\{:\w+,\s*:?\w+,\s*\"([^\"]+)\""
                                   line)]
                 (common/package-version-fact {:ecosystem :hex
                                        :package-name package-name
                                        :resolved-version version
                                        :source-line (inc idx)}))))
       distinct
       vec))
(defn- requirements-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [line (str/trim (first (str/split line #"\s+#" 2)))]
                 (when-let [[_ package-name version]
                            (re-matches #"(?i)^([A-Za-z0-9_.-]+)(?:\[[^\]]+\])?==([^;\s]+).*$"
                                        line)]
                   (common/package-version-fact {:ecosystem :pypi
                                          :package-name package-name
                                          :resolved-version version
                                          :source-line (inc idx)})))))
       distinct
       vec))
(defn- bun-lock-version-facts
  [content]
  (or (when-let [m (common/read-json-map content)]
        (->> (:packages m)
             (keep (fn [[package-name package]]
                     (cond
                       (string? package)
                       (common/package-version-fact {:ecosystem :npm
                                              :package-name (common/json-key-label package-name)
                                              :resolved-version package
                                              :source-line 1})

                       (map? package)
                       (common/package-version-fact {:ecosystem :npm
                                              :package-name (common/json-key-label package-name)
                                              :resolved-version (:version package)
                                              :source-line 1})

                       :else nil)))
             distinct
             vec))
      []))
(defn- dependency-lock-facts
  [{:keys [path content]}]
  (case (lockfile-name path)
    "package-lock.json" (package-lock-version-facts content)
    "cargo.lock" (cargo-lock-version-facts content)
    "go.sum" (go-sum-version-facts content)
    "pnpm-lock.yaml" (pnpm-lock-version-facts content)
    "yarn.lock" (yarn-lock-version-facts content)
    "gemfile.lock" (gemfile-lock-version-facts content)
    "poetry.lock" (toml-package-version-facts content :pypi)
    "uv.lock" (toml-package-version-facts content :pypi)
    "pubspec.lock" (pubspec-lock-version-facts content)
    "composer.lock" (composer-lock-version-facts content)
    "pipfile.lock" (pipfile-lock-version-facts content)
    "mix.lock" (mix-lock-version-facts content)
    "requirements.txt" (requirements-version-facts content)
    "bun.lock" (bun-lock-version-facts content)
    []))
(defn- version-package-fact
  [{:keys [ecosystem package-name source-line]}]
  (common/package-fact {:ecosystem ecosystem
                 :package-name package-name
                 :source-line source-line
                 :relation :version-of}))
(defn- version-of-edge
  [run-id file-id path id-scope version-fact]
  (let [package-fact (version-package-fact version-fact)]
    (common/fact-edge-row run-id
                          file-id
                          path
                          (common/node-id id-scope (:kind version-fact) (:label version-fact))
                          id-scope
                          package-fact)))
(defn extract-dependency-lock
  "Extract exact package version facts from supported dependency lockfiles."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (dependency-lock-facts file)
        package-facts (->> facts
                           (map version-package-fact)
                           (remove nil?)
                           distinct
                           vec)
        lock-node (common/generic-node run-id id-scope file-id path :dependency-lock path 1)
        fact-nodes (mapv #(common/fact-node run-id id-scope file-id path %)
                         (concat package-facts facts))
        resolve-edges (mapv #(common/fact-edge-row run-id
                                                   file-id
                                                   path
                                                   (:xt/id lock-node)
                                                   id-scope
                                                   %)
                             facts)
        version-edges (mapv #(version-of-edge run-id file-id path id-scope %) facts)
        chunk-result (common/extract-text-source run-id file :dependency-lock-file)]
    {:nodes (into [lock-node] fact-nodes)
     :edges (vec (concat resolve-edges version-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
