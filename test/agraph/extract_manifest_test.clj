(ns agraph.extract-manifest-test
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest extracts-project-manifest-dependencies-and-references
  (let [pom-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/java/pom.xml"))
        gradle-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/java/build.gradle"))
        gradle-settings-result (extract/extract-file
                                "run/test"
                                (fs/file-record "test/fixtures/extractor-repo"
                                                "test/fixtures/extractor-repo/java/settings.gradle"))
        csproj-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/src/dotnet/Panels.csproj"))
        sln-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/dotnet/Panels.sln"))
        gemfile-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/src/ruby/Gemfile"))
        gemspec-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/src/ruby/panels.gemspec"))
        pubspec-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/flutter/pubspec.yaml"))
        sbt-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/scala/build.sbt"))
        mix-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/elixir/mix.exs"))
        rebar-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/erlang/rebar.config"))
        r-description-result (extract/extract-file
                              "run/test"
                              (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/src/r/DESCRIPTION"))
        r-namespace-result (extract/extract-file
                            "run/test"
                            (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/NAMESPACE"))
        julia-project-result (extract/extract-file
                              "run/test"
                              (fs/file-record "test/fixtures/extractor-repo"
                                              "test/fixtures/extractor-repo/src/julia/Project.toml"))
        cpanfile-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/src/perl/cpanfile"))
        cabal-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/haskell/panels.cabal"))
        stack-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/src/haskell/stack.yaml"))
        pom-labels (set (map :label (:nodes pom-result)))
        gradle-labels (set (map :label (:nodes gradle-result)))
        gradle-settings-labels (set (map :label (:nodes gradle-settings-result)))
        csproj-labels (set (map :label (:nodes csproj-result)))
        sln-labels (set (map :label (:nodes sln-result)))
        gemfile-labels (set (map :label (:nodes gemfile-result)))
        gemspec-labels (set (map :label (:nodes gemspec-result)))
        pubspec-labels (set (map :label (:nodes pubspec-result)))
        sbt-labels (set (map :label (:nodes sbt-result)))
        mix-labels (set (map :label (:nodes mix-result)))
        rebar-labels (set (map :label (:nodes rebar-result)))
        r-description-labels (set (map :label (:nodes r-description-result)))
        r-namespace-labels (set (map :label (:nodes r-namespace-result)))
        julia-project-labels (set (map :label (:nodes julia-project-result)))
        cpanfile-labels (set (map :label (:nodes cpanfile-result)))
        cabal-labels (set (map :label (:nodes cabal-result)))
        stack-labels (set (map :label (:nodes stack-result)))]
    (is (contains? pom-labels "com.example:panels"))
    (is (contains? pom-labels "maven:org.slf4j:slf4j-api"))
    (is (contains? pom-labels "maven:com.fasterxml.jackson.core:jackson-databind"))
    (is (contains? pom-labels "com.example.platform:parent"))
    (is (contains? pom-labels "panels-core"))
    (is (contains? pom-labels "panels-api"))
    (is (contains? pom-labels "org.apache.maven.plugins:maven-compiler-plugin"))
    (is (contains? pom-labels "release"))
    (is (contains? pom-labels "internal"))
    (is (contains? pom-labels "https://repo.example.test/maven"))
    (is (contains? pom-labels "jar"))
    (is (contains? pom-labels "panels-service"))
    (is (= {:requires 2 :references 3 :uses 1 :defines 5}
           (select-keys (frequencies (map :relation (:edges pom-result)))
                        [:requires :references :uses :defines])))
    (is (contains? gradle-labels "panels-gradle"))
    (is (contains? gradle-labels "maven:com.fasterxml.jackson.core:jackson-databind"))
    (is (contains? gradle-labels "maven:org.junit.jupiter:junit-jupiter"))
    (is (contains? gradle-labels ":panels-core"))
    (is (contains? gradle-labels "java"))
    (is (contains? gradle-labels "org.jetbrains.kotlin.jvm"))
    (is (contains? gradle-labels "mavenCentral"))
    (is (contains? gradle-labels "https://repo.example.test/maven"))
    (is (contains? gradle-labels "generatePanels"))
    (is (contains? gradle-labels "bb generate-panels"))
    (is (contains? gradle-labels "dependsOn:generatePanels"))
    (is (= {:requires 4 :uses 5 :defines 1}
           (select-keys (frequencies (map :relation (:edges gradle-result)))
                        [:requires :uses :defines])))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/java/settings.gradle"))))
    (is (contains? gradle-settings-labels "panels-gradle"))
    (is (contains? gradle-settings-labels "gradlePluginPortal"))
    (is (contains? gradle-settings-labels "google"))
    (is (contains? gradle-settings-labels ":panels-core"))
    (is (contains? gradle-settings-labels ":panels-api"))
    (is (contains? gradle-settings-labels ":panels-api=apps/api"))
    (is (= {:uses 2 :defines 2 :references 1}
           (select-keys (frequencies (map :relation (:edges gradle-settings-result)))
                        [:uses :defines :references])))
    (is (contains? csproj-labels "Acme.Panels"))
    (is (contains? csproj-labels "nuget:Dapper"))
    (is (contains? csproj-labels "../Contracts/Contracts.csproj"))
    (is (contains? csproj-labels "net8.0"))
    (is (= {:requires 1 :references 1 :uses 1}
           (select-keys (frequencies (map :relation (:edges csproj-result)))
                        [:requires :references :uses])))
    (is (contains? sln-labels "src/dotnet/Panels.sln"))
    (is (contains? sln-labels "Panels Panels.csproj"))
    (is (= 1 (get (frequencies (map :relation (:edges sln-result))) :defines 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/ruby/Gemfile"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/ruby/panels.gemspec"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/flutter/pubspec.yaml"))))
    (is (contains? gemfile-labels "src/ruby/Gemfile"))
    (is (contains? gemfile-labels "rubygems:rails"))
    (is (contains? gemfile-labels "rubygems:graphql"))
    (is (= 2 (get (frequencies (map :relation (:edges gemfile-result))) :requires 0)))
    (is (contains? gemspec-labels "panels"))
    (is (contains? gemspec-labels "rubygems:faraday"))
    (is (contains? gemspec-labels "rubygems:rspec"))
    (is (= 2 (get (frequencies (map :relation (:edges gemspec-result))) :requires 0)))
    (is (contains? pubspec-labels "panels_flutter"))
    (is (contains? pubspec-labels "pub:flutter"))
    (is (contains? pubspec-labels "pub:http"))
    (is (contains? pubspec-labels "pub:flutter_test"))
    (is (= 3 (get (frequencies (map :relation (:edges pubspec-result))) :requires 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/scala/build.sbt"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/elixir/mix.exs"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/erlang/rebar.config"))))
    (is (contains? sbt-labels "panels-scala"))
    (is (contains? sbt-labels "maven:org.typelevel:cats-effect"))
    (is (contains? sbt-labels "maven:com.lihaoyi:os-lib"))
    (is (= 2 (get (frequencies (map :relation (:edges sbt-result))) :requires 0)))
    (is (contains? mix-labels "panels"))
    (is (contains? mix-labels "hex:plug"))
    (is (contains? mix-labels "hex:jason"))
    (is (= 2 (get (frequencies (map :relation (:edges mix-result))) :requires 0)))
    (is (contains? rebar-labels "src/erlang/rebar.config"))
    (is (contains? rebar-labels "hex:cowboy"))
    (is (= 1 (get (frequencies (map :relation (:edges rebar-result))) :requires 0)))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/DESCRIPTION"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/r/NAMESPACE"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/julia/Project.toml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/perl/cpanfile"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/src/haskell/panels.cabal"))))
    (is (contains? r-description-labels "panels"))
    (is (contains? r-description-labels "cran:dplyr"))
    (is (contains? r-description-labels "cran:jsonlite"))
    (is (contains? r-description-labels "cran:testthat"))
    (is (= 3 (get (frequencies (map :relation (:edges r-description-result))) :requires 0)))
    (is (contains? r-namespace-labels "cran:dplyr"))
    (is (contains? r-namespace-labels "cran:jsonlite"))
    (is (contains? r-namespace-labels "load_panel"))
    (is (contains? julia-project-labels "Panels"))
    (is (contains? julia-project-labels "julia:DataFrames"))
    (is (contains? julia-project-labels "julia:JSON3"))
    (is (= 2 (get (frequencies (map :relation (:edges julia-project-result))) :requires 0)))
    (is (contains? cpanfile-labels "src/perl/cpanfile"))
    (is (contains? cpanfile-labels "cpan:Mojolicious"))
    (is (contains? cpanfile-labels "cpan:JSON::MaybeXS"))
    (is (= 2 (get (frequencies (map :relation (:edges cpanfile-result))) :requires 0)))
    (is (contains? cabal-labels "panels"))
    (is (contains? cabal-labels "hackage:base"))
    (is (contains? cabal-labels "hackage:text"))
    (is (contains? cabal-labels "hackage:aeson"))
    (is (= 3 (get (frequencies (map :relation (:edges cabal-result))) :requires 0)))
    (is (contains? stack-labels "src/haskell/stack.yaml"))
    (is (contains? stack-labels "panels-lib"))
    (is (contains? stack-labels "aeson-extra"))
    (is (= [:manifest-file] (mapv :kind (:chunks pom-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks csproj-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks pubspec-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks sbt-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks cabal-result))))))
(deftest extracts-mobile-app-manifest-facts
  (let [android-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/android/AndroidManifest.xml"))
        plist-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/mobile/ios/Info.plist"))
        podfile-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/ios/Podfile"))
        swiftpm-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/mobile/ios/Package.swift"))
        xcode-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/mobile/ios/Panels.xcodeproj/project.pbxproj"))
        expo-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/mobile/expo/app.json"))
        capacitor-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/mobile/capacitor/capacitor.config.ts"))
        tauri-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/mobile/tauri/tauri.conf.json"))
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/android/AndroidManifest.xml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/ios/Info.plist"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/capacitor/capacitor.config.ts"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/tauri/tauri.conf.json"))))
    (is (contains? (labels android-result) "com.example.panels"))
    (is (contains? (labels android-result) "android.permission.INTERNET"))
    (is (contains? (labels android-result) "activity:.MainActivity"))
    (is (contains? (labels android-result) "service:.PanelSyncService"))
    (is (= {:defines 3 :uses 1} (select-keys (relations android-result) [:defines :uses])))
    (is (contains? (labels plist-result) "com.example.panels"))
    (is (contains? (labels plist-result) "Panels"))
    (is (= 2 (get (relations plist-result) :defines 0)))
    (is (contains? (labels podfile-result) "Panels"))
    (is (contains? (labels podfile-result) "cocoapods:Alamofire"))
    (is (contains? (labels podfile-result) "cocoapods:SQLite.swift"))
    (is (= {:defines 1 :requires 2} (select-keys (relations podfile-result)
                                                 [:defines :requires])))
    (is (contains? (labels swiftpm-result) "PanelsKit"))
    (is (contains? (labels swiftpm-result) "PanelsKitTests"))
    (is (contains? (labels swiftpm-result) "swiftpm:https://github.com/apple/swift-collections"))
    (is (= {:defines 2 :requires 1} (select-keys (relations swiftpm-result)
                                                 [:defines :requires])))
    (is (contains? (labels xcode-result) "Panels"))
    (is (contains? (labels xcode-result)
                   "swiftpm:https://github.com/pointfreeco/swift-composable-architecture"))
    (is (= {:defines 1 :requires 1} (select-keys (relations xcode-result)
                                                 [:defines :requires])))
    (is (contains? (labels expo-result) "Panels"))
    (is (contains? (labels expo-result) "com.example.panels"))
    (is (contains? (labels expo-result) "expo-router"))
    (is (contains? (labels expo-result) "expo-build-properties"))
    (is (= {:defines 2 :uses 2} (select-keys (relations expo-result)
                                             [:defines :uses])))
    (is (contains? (labels capacitor-result) "Panels Capacitor"))
    (is (contains? (labels capacitor-result) "com.example.panels"))
    (is (contains? (labels capacitor-result) "dist"))
    (is (contains? (labels capacitor-result) "https://app.example.test"))
    (is (contains? (labels capacitor-result) "SplashScreen"))
    (is (contains? (labels capacitor-result) "PushNotifications"))
    (is (= {:defines 2 :references 2 :uses 2}
           (select-keys (relations capacitor-result)
                        [:defines :references :uses])))
    (is (contains? (labels tauri-result) "Panels Desktop"))
    (is (contains? (labels tauri-result) "com.example.panels.desktop"))
    (is (contains? (labels tauri-result) "../dist"))
    (is (contains? (labels tauri-result) "http://localhost:1420"))
    (is (contains? (labels tauri-result) "pnpm dev"))
    (is (contains? (labels tauri-result) "main:Panels"))
    (is (contains? (labels tauri-result) "shell"))
    (is (= {:defines 3 :references 2 :uses 2}
           (select-keys (relations tauri-result)
                        [:defines :references :uses])))
    (is (= [:manifest-file] (mapv :kind (:chunks android-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks expo-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks capacitor-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks tauri-result))))))
(deftest extracts-mobile-resource-and-apple-config-facts
  (let [values-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/values/strings.xml"))
        layout-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/layout/activity_panel.xml"))
        navigation-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/mobile/android/res/navigation/panel_graph.xml"))
        entitlements-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/mobile/ios/Panels.entitlements"))
        xcconfig-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/mobile/ios/Debug.xcconfig"))
        labels (fn [result] (set (map :label (:nodes result))))
        relation-targets (fn [result relation]
                           (set (map :target-id
                                     (filter #(= relation (:relation %))
                                             (:edges result)))))]
    (is (= :xml (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/mobile/android/res/values/strings.xml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/mobile/ios/Panels.entitlements"))))
    (is (= :apple-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                "test/fixtures/extractor-repo/mobile/ios/Debug.xcconfig"))))
    (is (contains? (labels values-result) "string#app_name"))
    (is (contains? (labels values-result) "color#brand"))
    (is (contains? (labels layout-result) "LinearLayout#@+id/panel_root"))
    (is (contains? (labels layout-result) "TextView#@+id/title"))
    (is (contains? (labels layout-result) "fragment#@+id/nav_host"))
    (is (contains? (relation-targets layout-result :references)
                   (extract/node-id :xml-reference "@string/app_name")))
    (is (contains? (relation-targets layout-result :references)
                   (extract/node-id :xml-reference "@navigation/panel_graph")))
    (is (contains? (labels navigation-result) "navigation#@+id/panel_graph"))
    (is (contains? (labels navigation-result) "fragment#@+id/panelList"))
    (is (contains? (relation-targets navigation-result :references)
                   (extract/node-id :xml-reference "@id/panelList")))
    (is (contains? (labels entitlements-result) "aps-environment=development"))
    (is (contains? (labels entitlements-result)
                   "com.apple.security.application-groups=group.com.example.panels"))
    (is (= 2 (get (frequencies (map :relation (:edges entitlements-result)))
                  :defines
                  0)))
    (is (contains? (labels xcconfig-result) "PRODUCT_BUNDLE_IDENTIFIER=com.example.panels"))
    (is (contains? (labels xcconfig-result) "SWIFT_VERSION=5.9"))
    (is (contains? (labels xcconfig-result) "CODE_SIGN_ENTITLEMENTS=Panels.entitlements"))
    (is (= 3 (get (frequencies (map :relation (:edges xcconfig-result)))
                  :defines
                  0)))
    (is (= [:xml-file] (mapv :kind (:chunks values-result))))
    (is (= [:apple-config-file] (mapv :kind (:chunks xcconfig-result))))))
(deftest extracts-prisma-schema-facts
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/db/schema.prisma")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :prisma (:kind file)))
    (is (contains? labels "db/schema.prisma"))
    (is (contains? labels "db"))
    (is (contains? labels "client"))
    (is (contains? labels "User"))
    (is (contains? labels "Panel"))
    (is (contains? labels "PanelStatus"))
    (is (contains? labels "db:postgresql"))
    (is (contains? labels "db:DATABASE_URL"))
    (is (contains? labels "client:prisma-client-js"))
    (is (contains? labels "client:../generated/prisma"))
    (is (contains? labels "User.id:String"))
    (is (contains? labels "User.panels:Panel"))
    (is (contains? labels "Panel.owner:User"))
    (is (contains? labels "Panel.status:PanelStatus"))
    (is (contains? labels "User.id=user_id"))
    (is (contains? labels "User=users"))
    (is (contains? labels "Panel=panels"))
    (is (contains? labels "Panel:ownerId"))
    (is (contains? labels "Panel:ownerId,status"))
    (is (= 1 (:prisma-file kinds)))
    (is (= 1 (:prisma-datasource kinds)))
    (is (= 1 (:prisma-generator kinds)))
    (is (= 2 (:prisma-model kinds)))
    (is (= 1 (:prisma-enum kinds)))
    (is (= 5 (get relations :references 0)))
    (is (= 21 (get relations :defines 0)))
    (is (contains? reference-targets
                   (extract/node-id :prisma-model "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :prisma-model "User")))
    (is (contains? reference-targets
                   (extract/node-id :prisma-enum "PanelStatus")))
    (is (= :code-definition (get-in chunks-by-label ["Panel" :kind])))
    (is (= :prisma-model (get-in chunks-by-label ["Panel" :definition-kind])))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.prisma"
                                      :path "broken.prisma"
                                      :kind :prisma
                                      :content "model Broken {\n  id String @id\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-db-and-codegen-config-facts
  (let [drizzle-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/db/drizzle.config.ts"))
        flyway-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/db/flyway.conf"))
        liquibase-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/db/liquibase.properties"))
        codegen-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/codegen/graphql-codegen.yml"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :db-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/db/drizzle.config.ts"))))
    (is (= :codegen-config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                                  "test/fixtures/extractor-repo/codegen/graphql-codegen.yml"))))
    (is (contains? (labels drizzle-result) "db/drizzle.config.ts"))
    (is (contains? (labels drizzle-result) "schema=./src/db/schema.ts"))
    (is (contains? (labels drizzle-result) "out=./drizzle"))
    (is (contains? (labels drizzle-result) "dialect=postgresql"))
    (is (contains? (labels drizzle-result) "drizzle-kit"))
    (is (= 3 (get (relations drizzle-result) :defines 0)))
    (is (= 1 (get (relations drizzle-result) :references 0)))
    (is (contains? (labels flyway-result)
                   "flyway.locations=filesystem:db/migration"))
    (is (contains? (labels flyway-result) "flyway.schemas=public"))
    (is (contains? (labels flyway-result) "filesystem:db/migration"))
    (is (contains? (labels flyway-result) "classpath:db/repeatable"))
    (is (contains? (labels flyway-result) "public"))
    (is (contains? (labels flyway-result) "audit"))
    (is (contains? (labels flyway-result) "app_user"))
    (is (contains? (labels flyway-result) "flyway.baselineOnMigrate=true"))
    (is (contains? (labels flyway-result) "flyway.baselineVersion=1"))
    (is (= 2 (:flyway-location (kinds flyway-result))))
    (is (= 2 (:db-schema (kinds flyway-result))))
    (is (= 1 (:flyway-placeholder (kinds flyway-result))))
    (is (= 2 (:flyway-baseline-flag (kinds flyway-result))))
    (is (= 2 (get (relations flyway-result) :references 0)))
    (is (contains? (labels liquibase-result) "changeLogFile=db/changelog.xml"))
    (is (contains? (labels liquibase-result) "defaultSchemaName=public"))
    (is (contains? (labels codegen-result)
                   "schema=./contracts/panels.graphql"))
    (is (contains? (labels codegen-result) "documents=./src/**/*.graphql"))
    (is (contains? (labels codegen-result) "generates"))
    (is (= [:db-config-file] (mapv :kind (:chunks flyway-result))))
    (is (= [:codegen-config-file] (mapv :kind (:chunks codegen-result))))))
(deftest extracts-dbt-project-config-and-sql-references
  (let [project-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/dbt/dbt_project.yml"))
        sql-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/dbt/panel_orders.sql"))
        project-labels (set (map :label (:nodes project-result)))
        project-kinds (frequencies (map :kind (:nodes project-result)))
        project-relations (frequencies (map :relation (:edges project-result)))
        sql-labels (set (map :label (:nodes sql-result)))
        sql-kinds (frequencies (map :kind (:nodes sql-result)))
        sql-relations (frequencies (map :relation (:edges sql-result)))]
    (is (= :dbt (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/dbt/dbt_project.yml"))))
    (is (= :sql (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/dbt/panel_orders.sql"))))
    (is (contains? project-labels "panels"))
    (is (contains? project-labels "panels_profile"))
    (is (contains? project-labels "version=1.0"))
    (is (contains? project-labels "config-version=2"))
    (is (contains? project-labels "model-paths=models"))
    (is (contains? project-labels "seed-paths=seeds"))
    (is (contains? project-labels "snapshot-paths=snapshots"))
    (is (contains? project-labels "macro-paths=macros"))
    (is (contains? project-labels "test-paths=tests"))
    (is (= 1 (:dbt-project project-kinds)))
    (is (= 1 (:dbt-profile project-kinds)))
    (is (= 5 (:dbt-path project-kinds)))
    (is (= 8 (get project-relations :defines 0)))
    (is (= 1 (get project-relations :references 0)))
    (is (= [:dbt-file] (mapv :kind (:chunks project-result))))
    (is (empty? (:diagnostics project-result)))
    (is (contains? sql-labels "dbt/panel_orders.sql"))
    (is (contains? sql-labels "panels"))
    (is (contains? sql-labels "billing.orders"))
    (is (= 1 (:dbt-sql-file sql-kinds)))
    (is (= 1 (:dbt-model sql-kinds)))
    (is (= 1 (:dbt-source sql-kinds)))
    (is (= 2 (get sql-relations :references 0)))
    (is (= [:sql-file] (mapv :kind (:chunks sql-result)))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:dbt_project.yml"
                                      :path "dbt_project.yml"
                                      :kind :dbt
                                      :content "profile: missing_name\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "top-level name"))))
(deftest extracts-dbt-profile-package-and-schema-yaml
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        profiles (result-for "dbt/profiles.yml")
        packages (result-for "dbt/packages.yml")
        schema (result-for "dbt/models/schema.yml")]
    (is (= :dbt (kind-for "dbt/profiles.yml")))
    (is (= :dbt (kind-for "dbt/packages.yml")))
    (is (= :dbt (kind-for "dbt/models/schema.yml")))
    (is (contains? (labels profiles) "panels"))
    (is (contains? (labels profiles) "dev"))
    (is (contains? (labels profiles) "postgres"))
    (is (contains? (labels profiles) "bigquery"))
    (is (= 1 (:dbt-profile (kinds profiles))))
    (is (= 2 (:dbt-output (kinds profiles))))
    (is (contains? (labels packages) "dbt:dbt-labs/dbt_utils"))
    (is (contains? (labels packages) "dbt:https://github.com/acme/dbt-panels.git"))
    (is (contains? (labels schema) "billing"))
    (is (contains? (labels schema) "panel_orders"))
    (is (contains? (labels schema) "executive_dashboard"))
    (is (contains? (labels schema) "order_count"))
    (is (contains? (labels schema) "accepted_values_panel_status"))
    (is (= 1 (:dbt-source (kinds schema))))
    (is (= 1 (:dbt-model (kinds schema))))
    (is (= 1 (:dbt-exposure (kinds schema))))
    (is (= 1 (:dbt-metric (kinds schema))))
    (is (= 1 (:dbt-test (kinds schema))))))
(deftest extracts-governance-template-and-policy-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        issue (result-for ".github/ISSUE_TEMPLATE/bug_report.yml")
        pr (result-for ".github/PULL_REQUEST_TEMPLATE.md")
        funding (result-for ".github/FUNDING.yml")
        security (result-for "SECURITY.md")
        contributing (result-for "CONTRIBUTING.md")
        license (result-for "LICENSE")
        notice (result-for "NOTICE")
        license-kinds (frequencies (map :kind (:nodes license)))
        notice-kinds (frequencies (map :kind (:nodes notice)))]
    (is (= :governance (kind-for ".github/ISSUE_TEMPLATE/bug_report.yml")))
    (is (= :governance (kind-for ".github/PULL_REQUEST_TEMPLATE.md")))
    (is (= :governance (kind-for ".github/FUNDING.yml")))
    (is (= :governance (kind-for "SECURITY.md")))
    (is (= :governance (kind-for "CONTRIBUTING.md")))
    (is (= :governance (kind-for "LICENSE")))
    (is (= :governance (kind-for "NOTICE")))
    (is (contains? (labels issue) "name=Bug report"))
    (is (contains? (labels issue) "labels=bug, triage"))
    (is (contains? (labels pr) "Summary"))
    (is (contains? (labels pr) "Tests updated"))
    (is (contains? (labels funding) "github=acme"))
    (is (contains? (labels security) "Security Policy"))
    (is (contains? (labels contributing) "Development"))
    (is (contains? (labels license) "Apache-2.0"))
    (is (contains? (labels license) "2026 Acme Corp"))
    (is (contains? (labels license) "Apache License"))
    (is (= 1 (get license-kinds :license-id 0)))
    (is (= 1 (get license-kinds :license-title 0)))
    (is (= 1 (get license-kinds :copyright-notice 0)))
    (is (contains? (labels notice) "Copyright 2026 Acme Corp"))
    (is (= 1 (get notice-kinds :copyright-notice 0)))
    (is (= [:governance-config-file] (mapv :kind (:chunks license))))
    (is (= [:governance-config-file] (mapv :kind (:chunks notice))))))
(deftest extracts-sbom-package-and-relationship-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))
        cyclonedx (result-for "sbom/cyclonedx.json")
        spdx (result-for "sbom/spdx.json")]
    (is (= :sbom (kind-for "sbom/cyclonedx.json")))
    (is (= :sbom (kind-for "sbom/spdx.json")))
    (is (contains? (labels cyclonedx)
                   "urn:uuid:11111111-1111-1111-1111-111111111111"))
    (is (contains? (labels cyclonedx) "pkg:app/panels@1.2.3"))
    (is (contains? (labels cyclonedx) "pkg:npm/lodash@4.17.21"))
    (is (contains? (labels cyclonedx) "pkg:npm/react@19.1.0"))
    (is (contains? (labels cyclonedx) "MIT"))
    (is (= 1 (:sbom-document (kinds cyclonedx))))
    (is (= 3 (:sbom-package (kinds cyclonedx))))
    (is (= 1 (:license-id (kinds cyclonedx))))
    (is (= 2 (:depends-on (relations cyclonedx))))
    (is (= 2 (:licenses (relations cyclonedx))))
    (is (= [:sbom-file] (mapv :kind (:chunks cyclonedx))))
    (is (contains? (labels spdx) "SPDXRef-DOCUMENT"))
    (is (contains? (labels spdx) "panel-core@1.0.0"))
    (is (contains? (labels spdx) "guava@33.0.0"))
    (is (contains? (labels spdx) "Apache-2.0"))
    (is (= 1 (:sbom-document (kinds spdx))))
    (is (= 2 (:sbom-package (kinds spdx))))
    (is (= 1 (:license-id (kinds spdx))))
    (is (= 1 (:depends-on (relations spdx))))
    (is (= 2 (:licenses (relations spdx))))
    (is (= [:sbom-file] (mapv :kind (:chunks spdx))))))
(deftest extracts-format-support-tranche-facts
  (let [result-for (fn [path]
                     (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      (str "test/fixtures/extractor-repo/" path))))
        kind-for (fn [path]
                   (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          (str "test/fixtures/extractor-repo/" path))))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        chunk-kinds (fn [result] (set (map :kind (:chunks result))))
        notebook (result-for "notebooks/panel_analysis.ipynb")
        devcontainer (result-for ".devcontainer/devcontainer.json")
        kustomize (result-for "infra/kustomize/kustomization.yaml")
        pre-commit (result-for ".pre-commit-config.yaml")
        codeowners (result-for ".github/CODEOWNERS")
        taskfile (result-for "tasks/Taskfile.yml")
        justfile (result-for "tasks/justfile")
        starlark (result-for "build/rules/panels.bzl")
        tool-versions (result-for ".tool-versions")
        node-version (result-for ".node-version")
        python-version (result-for ".python-version")
        ruby-version (result-for ".ruby-version")
        mise (result-for "mise.toml")]
    (is (= :notebook (kind-for "notebooks/panel_analysis.ipynb")))
    (is (= :devcontainer (kind-for ".devcontainer/devcontainer.json")))
    (is (= :kustomize (kind-for "infra/kustomize/kustomization.yaml")))
    (is (= :pre-commit-config (kind-for ".pre-commit-config.yaml")))
    (is (= :codeowners (kind-for ".github/CODEOWNERS")))
    (is (= :task-runner (kind-for "tasks/Taskfile.yml")))
    (is (= :task-runner (kind-for "tasks/justfile")))
    (is (= :starlark (kind-for "build/rules/panels.bzl")))
    (is (= :tool-version-config (kind-for ".tool-versions")))
    (is (= :tool-version-config (kind-for ".node-version")))
    (is (= :tool-version-config (kind-for ".python-version")))
    (is (= :tool-version-config (kind-for ".ruby-version")))
    (is (= :tool-version-config (kind-for "mise.toml")))

    (is (contains? (labels notebook) "python3"))
    (is (contains? (labels notebook) "python"))
    (is (= 2 (:notebook-cell (kinds notebook))))
    (is (= #{:notebook-markdown-cell :notebook-code-cell}
           (chunk-kinds notebook)))

    (is (contains? (labels devcontainer)
                   "mcr.microsoft.com/devcontainers/base:ubuntu"))
    (is (contains? (labels devcontainer)
                   "ghcr.io/devcontainers/features/node:1"))
    (is (contains? (labels devcontainer) "3000"))
    (is (contains? (labels devcontainer) "postCreateCommand=bb test"))

    (is (contains? (labels kustomize) "resources=../k8s/deployment.yaml"))
    (is (contains? (labels kustomize)
                   "components=../components/observability"))
    (is (contains? (labels kustomize) "ghcr.io/acme/panels-web"))
    (is (contains? (labels kustomize) "configMapGenerator=panels-config"))

    (is (contains? (labels pre-commit)
                   "https://github.com/pre-commit/pre-commit-hooks"))
    (is (contains? (labels pre-commit) "v4.6.0"))
    (is (contains? (labels pre-commit) "trailing-whitespace"))

    (is (contains? (labels codeowners) "/frontend/"))
    (is (contains? (labels codeowners) "@acme/web"))
    (is (contains? (labels codeowners) "directory:/frontend/"))
    (is (contains? (labels codeowners) "team:@acme/web"))
    (is (= 4 (:assigns (frequencies (map :relation (:edges codeowners))))))
    (is (pos? (:describes (frequencies (map :relation (:edges codeowners))))))

    (is (contains? (labels taskfile) "build"))
    (is (contains? (labels taskfile) "build->lint"))
    (is (contains? (labels taskfile) "build:bb test"))
    (is (contains? (labels justfile) "test"))
    (is (contains? (labels justfile) "test->lint"))
    (is (contains? (labels justfile) "lint:bb lint"))

    (is (contains? (labels starlark) "@rules_java//java:defs.bzl"))
    (is (contains? (labels starlark) "@rules_java//java:defs.bzl:java_library"))
    (is (contains? (labels starlark) "panel_library"))
    (is (contains? (labels starlark) "panel_rule"))

    (is (contains? (labels tool-versions) "nodejs@20.11.1"))
    (is (contains? (labels tool-versions) "python@3.12.2"))
    (is (contains? (labels node-version) "node@20.11.1"))
    (is (contains? (labels python-version) "python@3.12.2"))
    (is (contains? (labels ruby-version) "ruby@3.3.0"))
    (is (contains? (labels mise) "node@20.11.1"))
    (is (contains? (labels mise) "PANEL_ENV=dev"))
    (is (contains? (labels mise) "build"))))
(deftest extracts-package-and-workspace-manifest-facts
  (let [package-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/workspace/package.json"))
        deno-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/deno.json"))
        composer-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/workspace/composer.json"))
        go-work-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/workspace/go.work"))
        pnpm-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/pnpm-workspace.yaml"))
        yarnrc-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/workspace/.yarnrc.yml"))
        turbo-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/workspace/turbo.json"))
        nx-result (extract/extract-file
                   "run/test"
                   (fs/file-record "test/fixtures/extractor-repo"
                                   "test/fixtures/extractor-repo/workspace/nx.json"))
        lerna-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/workspace/lerna.json"))
        rush-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/workspace/rush.json"))
        labels (fn [result] (set (map :label (:nodes result))))
        kinds (fn [result] (frequencies (map :kind (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/package.json"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/composer.json"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/go.work"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/pnpm-workspace.yaml"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/workspace/.yarnrc.yml"))))
    (is (contains? (labels package-result) "@acme/panels"))
    (is (contains? (labels package-result) "1.2.3"))
    (is (contains? (labels package-result) "module"))
    (is (contains? (labels package-result) "private=true"))
    (is (contains? (labels package-result) "panels=./bin/panels.js"))
    (is (contains? (labels package-result) ".=./src/index.ts"))
    (is (contains? (labels package-result) "node=>=20"))
    (is (contains? (labels package-result) "npm:@apollo/client"))
    (is (contains? (labels package-result) "npm:@acme/shared"))
    (is (contains? (labels package-result) "npm:react"))
    (is (contains? (labels package-result) "npm:typescript"))
    (is (contains? (labels package-result) "build"))
    (is (contains? (labels package-result) "build=turbo build"))
    (is (contains? (labels package-result) "dev=vite dev"))
    (is (contains? (labels package-result) "test"))
    (is (contains? (labels package-result) "apps/*"))
    (is (contains? (labels package-result) "packages/*"))
    (is (contains? (labels package-result) "@acme/shared=workspace:*"))
    (is (contains? (labels package-result) "pnpm@9.0.0"))
    (is (= 1 (:package-version (kinds package-result))))
    (is (= 1 (:package-type (kinds package-result))))
    (is (= 1 (:package-private (kinds package-result))))
    (is (= 1 (:package-bin (kinds package-result))))
    (is (= 1 (:package-export (kinds package-result))))
    (is (= 1 (:package-engine (kinds package-result))))
    (is (= 1 (:workspace-dependency (kinds package-result))))
    (is (= {:defines 12 :references 3 :requires 4 :uses 1}
           (select-keys (relations package-result)
                        [:defines :references :requires :uses])))
    (is (contains? (labels deno-result) "deno:@std/path"))
    (is (contains? (labels deno-result) "dev"))
    (is (= {:defines 1 :requires 1}
           (select-keys (relations deno-result) [:defines :requires])))
    (is (contains? (labels composer-result) "acme/panels"))
    (is (contains? (labels composer-result) "composer:php"))
    (is (contains? (labels composer-result) "composer:symfony/console"))
    (is (contains? (labels composer-result) "composer:phpunit/phpunit"))
    (is (= 3 (get (relations composer-result) :requires 0)))
    (is (contains? (labels go-work-result) "./services/api"))
    (is (contains? (labels go-work-result) "./libs/core"))
    (is (= 2 (get (relations go-work-result) :references 0)))
    (is (contains? (labels pnpm-result) "apps/*"))
    (is (contains? (labels pnpm-result) "packages/*"))
    (is (contains? (labels pnpm-result) "react=^18.3.0"))
    (is (contains? (labels pnpm-result) "react19"))
    (is (contains? (labels pnpm-result) "react19:react=^19.0.0"))
    (is (contains? (labels pnpm-result) "esbuild"))
    (is (= 1 (:pnpm-catalog (kinds pnpm-result))))
    (is (= 2 (:pnpm-catalog-package (kinds pnpm-result))))
    (is (= 1 (:pnpm-built-dependency (kinds pnpm-result))))
    (is (= 2 (get (relations pnpm-result) :references 0)))
    (is (contains? (labels yarnrc-result) "yarnPath=.yarn/releases/yarn-4.1.0.cjs"))
    (is (contains? (labels yarnrc-result) "nodeLinker=pnp"))
    (is (contains? (labels yarnrc-result) "pnp"))
    (is (contains? (labels yarnrc-result) "https://registry.yarnpkg.com"))
    (is (contains? (labels yarnrc-result) "path=.yarn/plugins/@yarnpkg/plugin-workspace-tools.cjs"))
    (is (contains? (labels yarnrc-result) "spec=@yarnpkg/plugin-workspace-tools"))
    (is (contains? (labels yarnrc-result) "acme"))
    (is (contains? (labels yarnrc-result) "acme=https://npm.pkg.github.com"))
    (is (contains? (labels yarnrc-result) "acme:npmAuthToken"))
    (is (not (contains? (labels yarnrc-result) "${GITHUB_TOKEN}")))
    (is (contains? (labels yarnrc-result) "react-dom@*"))
    (is (contains? (labels yarnrc-result) "react-dom@*:react=*"))
    (is (= 4 (:yarn-setting (kinds yarnrc-result))))
    (is (= 2 (:yarn-plugin (kinds yarnrc-result))))
    (is (= 1 (:yarn-npm-scope (kinds yarnrc-result))))
    (is (= 1 (:yarn-auth-key (kinds yarnrc-result))))
    (is (= 1 (:yarn-package-extension (kinds yarnrc-result))))
    (is (= 1 (:yarn-extension-dependency (kinds yarnrc-result))))
    (is (= {:defines 8 :references 4 :uses 2}
           (select-keys (relations yarnrc-result) [:defines :references :uses])))
    (is (contains? (labels turbo-result) "build"))
    (is (contains? (labels turbo-result) "test"))
    (is (= 2 (get (relations turbo-result) :defines 0)))
    (is (contains? (labels nx-result) "build"))
    (is (contains? (labels nx-result) "test"))
    (is (contains? (labels nx-result) "web"))
    (is (contains? (labels nx-result) "api"))
    (is (= {:defines 2 :references 2}
           (select-keys (relations nx-result) [:defines :references])))
    (is (contains? (labels lerna-result) "packages/*"))
    (is (contains? (labels lerna-result) "pnpm"))
    (is (contains? (labels rush-result) "@acme/panels-web"))
    (is (contains? (labels rush-result) "9.0.0"))
    (is (= [:manifest-file] (mapv :kind (:chunks package-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks yarnrc-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks composer-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks go-work-result))))
    (is (= [:manifest-file] (mapv :kind (:chunks turbo-result))))))
(deftest extracts-additional-package-manifest-facts
  (let [cargo-result (extract/extract-file
                      "run/test"
                      {:file-id "file:Cargo.toml"
                       :path "Cargo.toml"
                       :kind :manifest
                       :content (str "[package]\nname = \"demo\"\n\n"
                                     "[dependencies]\nserde = \"1\"\ntokio = { version = \"1.38\" }\n\n"
                                     "[workspace]\nmembers = [\"crates/api\", \"crates/core\"]\n\n"
                                     "[features]\npostgres = []\n")})
        go-result (extract/extract-file
                   "run/test"
                   {:file-id "file:go.mod"
                    :path "go.mod"
                    :kind :manifest
                    :content (str "module example.com/app\n\ngo 1.23\n"
                                  "toolchain go1.23.2\n\n"
                                  "require (\n  github.com/acme/lib v1.2.3\n)\n"
                                  "require golang.org/x/sync v0.7.0\n"
                                  "replace github.com/acme/old => ./local/old\n"
                                  "exclude github.com/acme/bad v0.1.0\n")})
        pyproject-result (extract/extract-file
                          "run/test"
                          {:file-id "file:pyproject.toml"
                           :path "pyproject.toml"
                           :kind :manifest
                           :content (str "[project]\n"
                                         "name = \"demo-py\"\n"
                                         "dependencies = [\"requests>=2\", \"click\"]\n\n"
                                         "[project.optional-dependencies]\n"
                                         "dev = [\"pytest\"]\n\n"
                                         "[tool.agraph.import-names]\n"
                                         "requests = [\"requests\"]\n")})
        pipfile-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/python/Pipfile"))
        setup-cfg-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/python/setup.cfg"))
        setup-py-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/python/setup.py"))
        deps-result (extract/extract-file
                     "run/test"
                     {:file-id "file:deps.edn"
                      :path "deps.edn"
                      :kind :manifest
                      :content "{:deps {org.clojure/data.json {:mvn/version \"2.5.1\"}}}\n"})
        labels (fn [result] (set (map :label (:nodes result))))
        package-node (fn [result label]
                       (some #(when (= label (:label %)) %) (:nodes result)))]
    (is (contains? (labels cargo-result) "cargo:serde"))
    (is (contains? (labels cargo-result) "cargo:tokio"))
    (is (contains? (labels cargo-result) "crates/api"))
    (is (contains? (labels cargo-result) "crates/core"))
    (is (contains? (labels cargo-result) "postgres"))
    (is (= "serde" (:package-name (package-node cargo-result "cargo:serde"))))
    (is (contains? (labels go-result) "go:github.com/acme/lib"))
    (is (contains? (labels go-result) "go:golang.org/x/sync"))
    (is (contains? (labels go-result) "1.23"))
    (is (contains? (labels go-result) "go1.23.2"))
    (is (contains? (labels go-result) "github.com/acme/old=>./local/old"))
    (is (contains? (labels go-result) "github.com/acme/bad@v0.1.0"))
    (is (contains? (labels pyproject-result) "pypi:requests"))
    (is (contains? (labels pyproject-result) "pypi:click"))
    (is (contains? (labels pyproject-result) "pypi:pytest"))
    (is (= ["requests"]
           (:import-names (package-node pyproject-result "pypi:requests"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/python/Pipfile"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/python/setup.cfg"))))
    (is (= :manifest (:kind (fs/file-record "test/fixtures/extractor-repo"
                                            "test/fixtures/extractor-repo/python/setup.py"))))
    (is (contains? (labels pipfile-result) "pypi:requests"))
    (is (contains? (labels pipfile-result) "pypi:pytest"))
    (is (contains? (labels setup-cfg-result) "panels-py"))
    (is (contains? (labels setup-cfg-result) "pypi:requests"))
    (is (contains? (labels setup-cfg-result) "pypi:click"))
    (is (contains? (labels setup-py-result) "panels-setup"))
    (is (contains? (labels setup-py-result) "pypi:fastapi"))
    (is (contains? (labels setup-py-result) "pypi:uvicorn"))
    (is (contains? (labels deps-result) "maven:org.clojure/data.json"))))
(deftest extracts-dependency-lock-version-facts
  (let [package-lock-result (extract/extract-file
                             "run/test"
                             {:file-id "file:package-lock.json"
                              :path "package-lock.json"
                              :kind :dependency-lock
                              :content "{\"packages\":{\"node_modules/react\":{\"version\":\"19.1.0\"},\"node_modules/@playwright/test\":{\"version\":\"1.60.0\"}}}"})
        cargo-lock-result (extract/extract-file
                           "run/test"
                           {:file-id "file:Cargo.lock"
                            :path "Cargo.lock"
                            :kind :dependency-lock
                            :content "[[package]]\nname = \"serde\"\nversion = \"1.0.0\"\n"})
        go-sum-result (extract/extract-file
                       "run/test"
                       {:file-id "file:go.sum"
                        :path "go.sum"
                        :kind :dependency-lock
                        :content "github.com/acme/lib v1.2.3 h1:abc\ngithub.com/acme/lib v1.2.3/go.mod h1:def\n"})
        pnpm-result (extract/extract-file
                     "run/test"
                     {:file-id "file:pnpm-lock.yaml"
                      :path "pnpm-lock.yaml"
                      :kind :dependency-lock
                      :content "packages:\n  react@19.1.0:\n    resolution: {integrity: sha512-demo}\n  '@playwright/test@1.60.0':\n    resolution: {integrity: sha512-demo}\n"})
        yarn-result (extract/extract-file
                     "run/test"
                     {:file-id "file:yarn.lock"
                      :path "yarn.lock"
                      :kind :dependency-lock
                      :content "\"react@^19.0.0\":\n  version \"19.1.0\"\n\"@playwright/test@^1.60.0\":\n  version \"1.60.0\"\n"})
        gemfile-result (extract/extract-file
                        "run/test"
                        {:file-id "file:Gemfile.lock"
                         :path "Gemfile.lock"
                         :kind :dependency-lock
                         :content "GEM\n  specs:\n    rails (7.1.0)\n    rack (3.0.0)\n"})
        poetry-result (extract/extract-file
                       "run/test"
                       {:file-id "file:poetry.lock"
                        :path "poetry.lock"
                        :kind :dependency-lock
                        :content "[[package]]\nname = \"requests\"\nversion = \"2.31.0\"\n"})
        uv-result (extract/extract-file
                   "run/test"
                   {:file-id "file:uv.lock"
                    :path "uv.lock"
                    :kind :dependency-lock
                    :content "[[package]]\nname = \"ruff\"\nversion = \"0.5.0\"\n"})
        pubspec-result (extract/extract-file
                        "run/test"
                        {:file-id "file:pubspec.lock"
                         :path "pubspec.lock"
                         :kind :dependency-lock
                         :content "packages:\n  http:\n    dependency: transitive\n    source: hosted\n    version: \"1.2.0\"\n"})
        composer-result (extract/extract-file
                         "run/test"
                         {:file-id "file:composer.lock"
                          :path "composer.lock"
                          :kind :dependency-lock
                          :content "{\"packages\":[{\"name\":\"symfony/console\",\"version\":\"v7.0.0\"}],\"packages-dev\":[{\"name\":\"phpunit/phpunit\",\"version\":\"11.0.0\"}]}"})
        pipfile-result (extract/extract-file
                        "run/test"
                        {:file-id "file:Pipfile.lock"
                         :path "Pipfile.lock"
                         :kind :dependency-lock
                         :content "{\"default\":{\"flask\":{\"version\":\"==3.0.0\"}},\"develop\":{\"pytest\":{\"version\":\"==8.0.0\"}}}"})
        mix-result (extract/extract-file
                    "run/test"
                    {:file-id "file:mix.lock"
                     :path "mix.lock"
                     :kind :dependency-lock
                     :content "%{\n  \"plug\": {:hex, :plug, \"1.15.3\", \"checksum\", [], \"hexpm\", \"checksum\"}\n}\n"})
        requirements-result (extract/extract-file
                             "run/test"
                             {:file-id "file:requirements.txt"
                              :path "requirements.txt"
                              :kind :dependency-lock
                              :content "django==5.0.0\nrequests[security]==2.31.0 ; python_version >= \"3.11\"\n"})
        bun-result (extract/extract-file
                    "run/test"
                    {:file-id "file:bun.lock"
                     :path "bun.lock"
                     :kind :dependency-lock
                     :content "{\"packages\":{\"react\":\"19.1.0\",\"@types/react\":{\"version\":\"19.0.0\"}}}"})
        flake-result (extract/extract-file
                      "run/test"
                      {:file-id "file:flake.lock"
                       :path "flake.lock"
                       :kind :dependency-lock
                       :content "{\"nodes\":{}}\n"})
        pixi-result (extract/extract-file
                     "run/test"
                     {:file-id "file:pixi.lock"
                      :path "pixi.lock"
                      :kind :dependency-lock
                      :content "version: 6\n"})
        labels (fn [result] (set (map :label (:nodes result))))
        relations (fn [result] (frequencies (map :relation (:edges result))))]
    (doseq [path ["package-lock.json" "Cargo.lock" "go.sum" "pnpm-lock.yaml"
                  "yarn.lock" "Gemfile.lock" "poetry.lock" "uv.lock"
                  "pubspec.lock" "composer.lock" "Pipfile.lock" "mix.lock"
                  "requirements.txt" "bun.lock" "flake.lock" "pixi.lock"]]
      (is (= :dependency-lock (fs/file-kind path))))
    (is (contains? (labels package-lock-result) "npm:react"))
    (is (contains? (labels package-lock-result) "npm:react@19.1.0"))
    (is (contains? (labels package-lock-result) "npm:@playwright/test@1.60.0"))
    (is (= {:resolves 2 :version-of 2}
           (select-keys (relations package-lock-result) [:resolves :version-of])))
    (is (contains? (labels cargo-lock-result) "cargo:serde@1.0.0"))
    (is (contains? (labels go-sum-result) "go:github.com/acme/lib@v1.2.3"))
    (is (contains? (labels pnpm-result) "npm:react@19.1.0"))
    (is (contains? (labels pnpm-result) "npm:@playwright/test@1.60.0"))
    (is (contains? (labels yarn-result) "npm:react@19.1.0"))
    (is (contains? (labels yarn-result) "npm:@playwright/test@1.60.0"))
    (is (contains? (labels gemfile-result) "rubygems:rails@7.1.0"))
    (is (contains? (labels gemfile-result) "rubygems:rack@3.0.0"))
    (is (contains? (labels poetry-result) "pypi:requests@2.31.0"))
    (is (contains? (labels uv-result) "pypi:ruff@0.5.0"))
    (is (contains? (labels pubspec-result) "pub:http@1.2.0"))
    (is (contains? (labels composer-result) "composer:symfony/console@v7.0.0"))
    (is (contains? (labels composer-result) "composer:phpunit/phpunit@11.0.0"))
    (is (contains? (labels pipfile-result) "pypi:flask@3.0.0"))
    (is (contains? (labels pipfile-result) "pypi:pytest@8.0.0"))
    (is (contains? (labels mix-result) "hex:plug@1.15.3"))
    (is (contains? (labels requirements-result) "pypi:django@5.0.0"))
    (is (contains? (labels requirements-result) "pypi:requests@2.31.0"))
    (is (contains? (labels bun-result) "npm:react@19.1.0"))
    (is (contains? (labels bun-result) "npm:@types/react@19.0.0"))
    (is (= [:dependency-lock-file] (mapv :kind (:chunks flake-result))))
    (is (= [:dependency-lock-file] (mapv :kind (:chunks pixi-result))))))
