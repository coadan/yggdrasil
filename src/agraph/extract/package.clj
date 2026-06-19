(ns agraph.extract.package
  (:require [agraph.extract.common :as common]
            [agraph.extract.package-js :as package-js]
            [agraph.extract.package-gradle :as package-gradle]
            [agraph.extract.package-python :as package-python]
            [agraph.extract.package-cargo-go :as package-cargo-go]
            [agraph.extract.package-mobile :as package-mobile]
            [agraph.extract.package-jvm-dotnet :as package-jvm-dotnet]
            [agraph.extract.package-misc :as package-misc]
            [agraph.extract.package-ecosystems :as package-ecosystems]
            [agraph.extract.package-lock :as package-lock]
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
(defn extract-dependency-lock
  "Extract exact package version facts from supported dependency lockfiles."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (package-lock/dependency-lock-facts file)
        package-facts (->> facts
                           (map package-lock/version-package-fact)
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
        version-edges (mapv #(package-lock/version-of-edge run-id file-id path id-scope %) facts)
        chunk-result (common/extract-text-source run-id file :dependency-lock-file)]
    {:nodes (into [lock-node] fact-nodes)
     :edges (vec (concat resolve-edges version-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
