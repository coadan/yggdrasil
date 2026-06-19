(ns agraph.extract.package-jvm-dotnet
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn xml-tag-values
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
(defn maven-coordinates
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
(defn maven-facts
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
(defn dotnet-package-references
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
(defn dotnet-project-references
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
(defn dotnet-target-frameworks
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
(defn solution-projects
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
