(ns agraph.dependency-imports-test
  (:require [agraph.dependency.imports :as dependency-imports]
            [clojure.test :refer [deftest is]]))

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
                  "com.sun.net.httpserver.HttpServer"]]
    (is (false? (dependency-imports/external-package-candidate? :java target))))
  (is (true? (dependency-imports/external-package-candidate? :java "org.junit.platform.Engine")))

  (is (false? (dependency-imports/external-package-candidate? :python "json")))
  (is (true? (dependency-imports/external-package-candidate? :python "requests")))
  (is (false? (dependency-imports/external-package-candidate? :dotnet "System.Data")))
  (is (true? (dependency-imports/external-package-candidate? :dotnet "Xunit")))
  (is (false? (dependency-imports/external-package-candidate? :rust "std::fs")))
  (is (true? (dependency-imports/external-package-candidate? :rust "serde::Serialize"))))
