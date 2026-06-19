(ns agraph.extract.package
  (:require [agraph.extract.common :as common]
            [charred.api :as json]
            [clojure.edn :as edn]
            [agraph.extract.package-js :as package-js]
            [agraph.extract.package-gradle :as package-gradle]
            [agraph.extract.package-toml :as package-toml]
            [agraph.extract.package-python :as package-python]
            [agraph.extract.package-cargo-go :as package-cargo-go]
            [agraph.extract.package-mobile :as package-mobile]
            [agraph.extract.package-jvm-dotnet :as package-jvm-dotnet]
            [agraph.extract.package-misc :as package-misc]
            [agraph.extract.package-ecosystems :as package-ecosystems]
            [clojure.string :as str]))

(defn- manifest-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)
        extension (last (str/split filename #"\."))]
    (cond
      (= "pom.xml" filename)
      {:project-label (or (package-jvm-dotnet/maven-coordinates content) path)
       :facts (package-jvm-dotnet/maven-facts content)}

      (contains? #{"build.gradle" "build.gradle.kts" "settings.gradle"
                   "settings.gradle.kts" "gradle.properties"}
                 filename)
      {:project-label (package-gradle/gradle-project-name content path)
       :facts (package-gradle/gradle-facts content)}

      (or (contains? #{"csproj" "fsproj" "vbproj" "props" "targets"}
                     extension)
          (contains? #{"directory.build.props" "directory.build.targets"
                       "packages.config" "nuget.config"}
                     filename))
      {:project-label (or (first (package-jvm-dotnet/xml-tag-values content "AssemblyName"))
                          (first (package-jvm-dotnet/xml-tag-values content "PackageId"))
                          path)
       :facts (vec (concat (package-jvm-dotnet/dotnet-package-references content)
                           (package-jvm-dotnet/dotnet-project-references content)
                           (package-jvm-dotnet/dotnet-target-frameworks content)))}

      (= "sln" extension)
      {:project-label path
       :facts (package-jvm-dotnet/solution-projects content)}

      (= "package.json" filename)
      {:project-label (package-js/package-json-project-label content path)
       :facts (package-js/package-json-facts content)}

      (= "deno.json" filename)
      {:project-label (package-js/package-json-project-label content path)
       :facts (package-js/deno-json-facts content)}

      (= "cargo.toml" filename)
      {:project-label (package-cargo-go/cargo-package-name content path)
       :facts (package-cargo-go/cargo-dependencies content)}

      (= "go.mod" filename)
      {:project-label (package-cargo-go/go-module-name content path)
       :facts (vec (concat (package-cargo-go/go-mod-requires content)
                           (package-cargo-go/go-mod-extra-facts content)))}

      (= "go.work" filename)
      {:project-label path
       :facts (package-cargo-go/go-work-uses content)}

      (= "composer.json" filename)
      {:project-label (package-misc/composer-project-name content path)
       :facts (package-misc/composer-json-facts content)}

      (= "pyproject.toml" filename)
      {:project-label (package-python/pyproject-name content path)
       :facts (package-python/pyproject-dependencies content)}

      (= "setup.cfg" filename)
      {:project-label (package-python/setup-cfg-name content path)
       :facts (package-python/setup-cfg-dependencies content)}

      (= "setup.py" filename)
      {:project-label (package-python/setup-py-name content path)
       :facts (package-python/setup-py-dependencies content)}

      (= "pipfile" filename)
      {:project-label path
       :facts (package-python/pipfile-dependencies content)}

      (= "deps.edn" filename)
      {:project-label (package-misc/deps-edn-project-name path)
       :facts (package-misc/deps-edn-dependencies content)}

      (or (= "gemfile" filename)
          (= "gemspec" extension))
      {:project-label (or (package-misc/gemspec-name content) path)
       :facts (package-misc/ruby-gem-dependencies content)}

      (= "pubspec.yaml" filename)
      {:project-label (or (common/yaml-top-level-value content "name") path)
       :facts (package-ecosystems/pubspec-dependencies content)}

      (= "build.sbt" filename)
      {:project-label (package-ecosystems/sbt-project-name content path)
       :facts (package-ecosystems/sbt-dependencies content)}

      (= "mix.exs" filename)
      {:project-label (package-ecosystems/mix-project-name content path)
       :facts (package-ecosystems/mix-dependencies content)}

      (= "rebar.config" filename)
      {:project-label path
       :facts (package-ecosystems/rebar-dependencies content)}

      (= "description" filename)
      {:project-label (package-ecosystems/r-description-package-name content path)
       :facts (package-ecosystems/r-description-dependencies content)}

      (= "namespace" filename)
      {:project-label path
       :facts (package-ecosystems/r-namespace-facts content)}

      (= "project.toml" filename)
      {:project-label (or (package-ecosystems/toml-name-value content "name") path)
       :facts (package-ecosystems/project-toml-dependencies content)}

      (= "cpanfile" filename)
      {:project-label path
       :facts (package-ecosystems/cpanfile-dependencies content)}

      (= "cabal" extension)
      {:project-label (package-ecosystems/cabal-package-name content path)
       :facts (package-ecosystems/cabal-dependencies content)}

      (= "stack.yaml" filename)
      {:project-label path
       :facts (package-ecosystems/stack-yaml-dependencies content)}

      (contains? #{"pnpm-workspace.yaml" "pnpm-workspace.yml"} filename)
      {:project-label path
       :facts (package-js/pnpm-workspace-facts content)}

      (contains? #{".yarnrc.yml" ".yarnrc.yaml"} filename)
      {:project-label path
       :facts (package-js/yarnrc-facts content)}

      (contains? #{"turbo.json" "nx.json" "workspace.json" "lerna.json" "rush.json"} filename)
      {:project-label path
       :facts (package-js/workspace-json-facts content filename)}

      (= "androidmanifest.xml" filename)
      {:project-label (or (package-mobile/android-manifest-package content) path)
       :facts (vec (concat (when-let [package-name (package-mobile/android-manifest-package content)]
                             [{:kind :android-package
                               :label package-name
                               :source-line 1
                               :relation :defines}])
                           (package-mobile/android-permissions content)
                           (package-mobile/android-components content)))}

      (= "info.plist" filename)
      {:project-label (or (package-mobile/plist-string-value content "CFBundleIdentifier")
                          (package-mobile/plist-string-value content "CFBundleName")
                          path)
       :facts (package-mobile/plist-facts content)}

      (= "entitlements" extension)
      {:project-label path
       :facts (package-mobile/plist-key-facts content :apple-entitlement)}

      (= "podfile" filename)
      {:project-label path
       :facts (package-mobile/podfile-facts content)}

      (= "package.swift" filename)
      {:project-label (or (package-mobile/swift-package-name content) path)
       :facts (package-mobile/swift-package-facts content)}

      (= "pbxproj" extension)
      {:project-label path
       :facts (package-mobile/xcode-project-facts content)}

      (contains? #{"app.json" "app.config.json" "eas.json"} filename)
      {:project-label (package-mobile/expo-project-label content path)
       :facts (package-mobile/expo-json-facts content)}

      (contains? #{"capacitor.config.json" "capacitor.config.ts"
                   "capacitor.config.js" "capacitor.config.mjs"
                   "capacitor.config.cjs"}
                 filename)
      {:project-label (package-mobile/capacitor-project-label content path)
       :facts (package-mobile/capacitor-config-facts content)}

      (contains? #{"tauri.conf.json" "tauri.conf.json5"} filename)
      {:project-label (package-mobile/tauri-project-label content path)
       :facts (package-mobile/tauri-config-facts content)}

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
               (let [package-name (package-toml/toml-string-value block "name")
                     version (package-toml/toml-string-value block "version")]
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
               (let [package-name (package-toml/toml-string-value block "name")
                     version (package-toml/toml-string-value block "version")]
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
