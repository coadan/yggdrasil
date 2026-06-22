(ns ygg.dependency-imports-test
  (:require [ygg.dependency.imports :as dependency-imports]
            [ygg.dependency.imports.common :as import-common]
            [clojure.test :refer [deftest is]]))

(defn- import-edge
  [path target]
  {:target-id (str "node:namespace:" target)
   :path path})

(defn- candidate?
  [{:keys [files nodes aliases modules local-targets edge]}]
  (dependency-imports/package-import-candidate?
   {:files-by-path (into {} (map (juxt :path identity)) files)
    :nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
    :alias-nodes aliases
    :module-nodes modules
    :local-namespace-targets local-targets
    :edge edge}))

(deftest filters-language-runtime-imports
  (is (true? (dependency-imports/supported-source-kind? :java)))
  (is (false? (dependency-imports/supported-source-kind? :ci-workflow)))

  (doseq [target ["fs" "node:path" "bun:test" "astro:content"]]
    (is (false? (dependency-imports/external-package-candidate? :javascript target))))
  (is (true? (dependency-imports/external-package-candidate? :javascript "react")))
  (is (false? (dependency-imports/external-package-candidate? :typescript "./local")))

  (doseq [target ["java.util.List"
                  "javax.annotation.Nullable"
                  "jdk.jfr.Event"
                  "sun.misc.Unsafe"
                  "com.sun.net.httpserver.HttpServer"
                  "org.w3c.dom.Document"
                  "org.xml.sax.SAXException"]]
    (is (false? (dependency-imports/external-package-candidate? :java target))))
  (is (true? (dependency-imports/external-package-candidate? :java "org.junit.platform.Engine")))

  (is (false? (dependency-imports/external-package-candidate? :python "json")))
  (is (true? (dependency-imports/external-package-candidate? :python "requests")))
  (is (false? (dependency-imports/external-package-candidate? :dotnet "System.Data")))
  (is (true? (dependency-imports/external-package-candidate? :dotnet "Xunit")))
  (is (false? (dependency-imports/external-package-candidate? :go "context")))
  (is (false? (dependency-imports/external-package-candidate? :go "go/ast")))
  (is (true? (dependency-imports/external-package-candidate? :go "go.opentelemetry.io/collector/component")))
  (is (false? (dependency-imports/external-package-candidate? :rust "std::fs")))
  (is (true? (dependency-imports/external-package-candidate? :rust "serde::Serialize"))))

(deftest package-import-candidates-filter-local-imports
  (is (false? (candidate?
               {:files [{:path "src/App.ts" :kind :typescript}
                        {:path "src/components/Button.ts" :kind :typescript}]
                :edge (import-edge "src/App.ts" "components/Button")})))

  (is (false? (candidate?
               {:files [{:path "src/App.ts" :kind :typescript}]
                :aliases [{:kind :module-path-alias
                           :path "tsconfig.json"
                           :label "@app/*=src/*"}]
                :edge (import-edge "src/App.ts" "@app/config")})))

  (is (false? (candidate?
               {:files [{:path "src/main/java/demo/App.java" :kind :java}]
                :nodes [{:xt/id "node:symbol:demo/Local"
                         :kind :class
                         :path "src/main/java/demo/Local.java"}]
                :edge (import-edge "src/main/java/demo/App.java" "demo.Local")})))

  (is (false? (candidate?
               {:files [{:path "consumer/consumer.go" :kind :go}]
                :modules [{:kind :manifest
                           :path "go.mod"
                           :label "go.opentelemetry.io/collector"}]
                :edge (import-edge "consumer/consumer.go"
                                   "go.opentelemetry.io/collector/component")})))

  (is (false? (candidate?
               {:files [{:path "src/App.java" :kind :java}]
                :nodes [{:xt/id "node:namespace:demo.Shared"
                         :kind :namespace
                         :path "src/Shared.java"}]
                :edge (import-edge "src/App.java" "demo.Shared")}))))

(deftest package-import-candidates-use-precomputed-local-namespace-targets
  (let [local-targets (import-common/local-namespace-targets
                       [{:xt/id "node:symbol:demo/Local"
                         :kind :class
                         :label "demo/Local"
                         :path "src/main/java/demo/Local.java"}])]
    (is (= #{"demo" "demo.Local"} local-targets))
    (is (false? (candidate?
                 {:files [{:path "src/main/java/demo/App.java" :kind :java}]
                  :local-targets local-targets
                  :edge (import-edge "src/main/java/demo/App.java" "demo")})))
    (is (false? (candidate?
                 {:files [{:path "src/main/java/demo/App.java" :kind :java}]
                  :local-targets local-targets
                  :edge (import-edge "src/main/java/demo/App.java" "demo.Local")})))))

(deftest package-import-candidates-keep-external-imports
  (is (true? (candidate?
              {:files [{:path "src/App.ts" :kind :typescript}]
               :edge (import-edge "src/App.ts" "react")})))
  (is (true? (candidate?
              {:files [{:path "src/App.java" :kind :java}]
               :edge (import-edge "src/App.java" "org.junit.jupiter.api.Test")})))
  (is (true? (candidate?
              {:files [{:path "consumer/consumer.go" :kind :go}]
               :modules [{:kind :manifest
                          :path "go.mod"
                          :label "go.opentelemetry.io/collector"}]
               :edge (import-edge "consumer/consumer.go" "go.uber.org/zap")}))))
