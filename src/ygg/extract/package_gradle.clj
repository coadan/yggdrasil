(ns ygg.extract.package-gradle
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn gradle-project-name
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

(def ^:private gradle-dependency-scope-pattern
  "(api|compileOnly|compileOnlyApi|implementation|runtimeOnly|testImplementation|testRuntimeOnly|testFixturesImplementation|annotationProcessor|kapt|classpath)")

(defn- kotlin-helper-package-name
  [module-name]
  (let [module-name (str module-name)]
    (when (seq module-name)
      (str "org.jetbrains.kotlin:"
           (if (str/starts-with? module-name "kotlin-")
             module-name
             (str "kotlin-" module-name))))))

(defn- kotlin-helper-import-names
  [module-name]
  (let [module-name (str/replace (str module-name) #"^kotlin-" "")]
    (case module-name
      ("stdlib" "stdlib-common" "stdlib-jdk7" "stdlib-jdk8") ["kotlin"]
      "reflect" ["kotlin.reflect"]
      "test" ["kotlin.test"]
      nil)))

(defn- gradle-dependencies
  [content]
  (gradle-line-facts
   content
   (fn [source-line line]
     (let [scope-pattern gradle-dependency-scope-pattern
           string-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                   "\\s*(?:\\(?\\s*)['\"]([^:'\"]+):([^:'\"]+)(?::([^'\"]+))?['\"].*$"))
                                  line)
           map-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                "\\s*(?:\\(?\\s*)group\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*:\\s*['\"]([^'\"]+)['\"])?\\s*\\)?.*$"))
                               line)
           project-dep (re-matches (re-pattern (str "^\\s*" scope-pattern
                                                    "\\s*(?:\\(?\\s*)project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\).*$"))
                                   line)
           kotlin-helper-dep (re-matches (re-pattern
                                          (str "^\\s*" scope-pattern
                                               "\\s*(?:\\(?\\s*)kotlin\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\).*$"))
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

         kotlin-helper-dep
         (let [[_ scope module-name] kotlin-helper-dep]
           [(common/package-fact {:ecosystem :maven
                                  :package-name (kotlin-helper-package-name module-name)
                                  :import-names (kotlin-helper-import-names module-name)
                                  :dependency-scope scope
                                  :source-line source-line})])

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
(defn gradle-facts
  [content]
  (vec (distinct (concat (gradle-dependencies content)
                         (gradle-plugins content)
                         (gradle-repositories content)
                         (gradle-modules content)
                         (gradle-tasks content)))))

(defn- catalog-string-attr
  [body attr-name]
  (some-> (re-find (re-pattern (str "(?:^|,)\\s*"
                                    (java.util.regex.Pattern/quote attr-name)
                                    "\\s*=\\s*\"([^\"]+)\""))
                   body)
          second))

(defn- catalog-library-module
  [body]
  (or (catalog-string-attr body "module")
      (when-let [group-id (catalog-string-attr body "group")]
        (when-let [artifact-id (catalog-string-attr body "name")]
          (str group-id ":" artifact-id)))))

(defn- catalog-shorthand-module
  [value]
  (let [[group-id artifact-id version] (str/split (str/trim value) #":" 3)]
    (when (and group-id artifact-id)
      {:module (str group-id ":" artifact-id)
       :version version})))

(defn gradle-version-catalog-facts
  "Extract Maven package facts from Gradle version catalog `[libraries]` entries."
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-libraries? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining) (= "[libraries]" trimmed) out)

          in-libraries?
          (let [[_ _alias body] (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\{(.*?)\}\s*(?:#.*)?$"
                                            line)
                [_ _alias shorthand] (re-matches #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\"\s*(?:#.*)?$"
                                                 line)
                shorthand-fact (some-> shorthand catalog-shorthand-module)
                module (or (some-> body catalog-library-module)
                           (:module shorthand-fact))
                version (or (some-> body (catalog-string-attr "version"))
                            (:version shorthand-fact))]
            (recur (rest remaining)
                   in-libraries?
                   (cond-> out
                     (and module (str/includes? module ":"))
                     (conj (common/package-fact
                            {:ecosystem :maven
                             :package-name module
                             :version-range version
                             :dependency-scope "version-catalog"
                             :source-line (inc idx)})))))

          :else
          (recur (rest remaining) in-libraries? out)))
      (->> out (remove nil?) distinct vec))))
