(ns agraph.extract-language-test
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest extracts-rust-modules-definitions-uses-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/src/rust/lib.rs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        module-targets (->> (:edges result)
                            (filter #(= :declares-module (:relation %)))
                            (map :target-id)
                            set)
        relations (frequencies (map :relation (:edges result)))]
    (is (contains? labels "src::rust::lib/Config"))
    (is (contains? labels "src::rust::lib/run"))
    (is (= :rust-definition
           (get-in chunks-by-label ["src::rust::lib/Config" :kind])))
    (is (str/includes? (get-in chunks-by-label ["src::rust::lib/run" :text])
                       "pub async fn run"))
    (is (contains? module-targets "node:namespace:src::rust::gateway"))
    (is (= 1 (get relations :declares-module 0)))
    (is (= 1 (get relations :uses 0)))
    (is (pos? (get relations :defines 0)))))
(deftest extracts-go-packages-definitions-imports-and-calls
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/internal/cli/flows.go")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :go (:kind file)))
    (is (contains? labels "internal/cli/flows"))
    (is (contains? labels "internal/cli/flows/Client"))
    (is (contains? labels "internal/cli/flows/RunFlow"))
    (is (contains? labels "internal/cli/flows/Client.PublishFlow"))
    (is (= :code-definition (get-in chunks-by-label ["internal/cli/flows/RunFlow" :kind])))
    (is (= :function (get-in chunks-by-label ["internal/cli/flows/RunFlow" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["internal/cli/flows/RunFlow" :text])
                       "flowapi.Start"))
    (is (= :method (get-in chunks-by-label ["internal/cli/flows/Client.PublishFlow" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["internal/cli/flows/Client.PublishFlow" :text])
                       "RunFlow"))
    (is (= 2 (get relations :imports 0)))
    (is (<= 4 (get relations :defines 0)))
    (is (pos? (get relations :calls 0)))))
(deftest extracts-java-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/java/com/example/panels/PanelService.java")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (= :java (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelService"))
    (is (contains? labels "com.example.panels/PanelService.PanelService"))
    (is (contains? labels "com.example.panels/PanelService.loadPanel"))
    (is (contains? labels "com.example.panels/PanelService.audit"))
    (is (contains? labels "com.example.panels/Panel"))
    (is (contains? labels "com.example.panels/PanelClient"))
    (is (contains? labels "com.example.panels/PanelClient.fetch"))
    (is (contains? labels "com.example.panels/PanelStatus"))
    (is (contains? labels "com.example.panels/PanelBinding"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "java.net.URI")))
    (is (contains? import-targets
                   (extract/node-id :namespace "java.util.Objects.requireNonNull")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "com.example.panels/Panel")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "com.example.panels/PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :text])
         "client.fetch"))
    (is (= [:java-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.java"
                                      :path "Broken.java"
                                      :kind :java
                                      :content "package demo;\npublic class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest java-extractor-does-not-treat-field-initializers-as-methods
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Options.java"
                 :path "src/Options.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "import static java.util.Collections.emptyList;\n"
                               "import java.util.List;\n"
                               "class Options {\n"
                               "  private List<String> selectedUniqueIds = emptyList();\n"
                               "  public List<String> getSelectedUniqueIds() {\n"
                               "    return selectedUniqueIds;\n"
                               "  }\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "demo/Options"))
    (is (contains? labels "demo/Options.getSelectedUniqueIds"))
    (is (not (contains? labels "demo/Options.emptyList")))
    (is (empty? (:diagnostics result)))))
(deftest java-reference-extraction-resolves-explicit-imports
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:DiscoveryRequestCreatorTests.java"
                 :path "src/DiscoveryRequestCreatorTests.java"
                 :kind :java
                 :content (str "package org.example.tasks;\n"
                               "import org.example.options.TestDiscoveryOptions;\n"
                               "class DiscoveryRequestCreatorTests {\n"
                               "  private final TestDiscoveryOptions options = new TestDiscoveryOptions();\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id
                    :symbol
                    "org.example.options/TestDiscoveryOptions")))
    (is (not (contains? reference-targets
                        (extract/node-id
                         :symbol
                         "org.example.tasks/TestDiscoveryOptions"))))
    (is (empty? (:diagnostics result)))))
(deftest java-reference-extraction-ignores-comments-and-strings
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Noise.java"
                 :path "src/Noise.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "class Noise {\n"
                               "  String description = \"MentionedType in prose\";\n"
                               "  // CommentType in a line comment\n"
                               "  /* BlockType in a block comment */\n"
                               "  Target make(Param param) {\n"
                               "    String text = \"BodyType in method body\";\n"
                               "    return new Target();\n"
                               "  }\n"
                               "  static class Target {}\n"
                               "  static class Param {}\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Target")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Param")))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/MentionedType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/CommentType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BlockType"))))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BodyType"))))))
(deftest java-reference-extraction-handles-large-method-bodies
  (let [body-lines (apply str
                          (repeat
                           1200
                           "    if (input > 0) { total += helper.compute(input); }\n"))
        result (extract/extract-file
                "run/test"
                {:file-id "file:LargeBody.java"
                 :path "src/LargeBody.java"
                 :kind :java
                 :content (str "package demo;\n"
                               "class LargeBody {\n"
                               "  Result build(Input input) throws BuildException {\n"
                               body-lines
                               "    String ignored = \"BodyMention\";\n"
                               "    return new Result();\n"
                               "  }\n"
                               "  static class Result {}\n"
                               "  static class Input {}\n"
                               "  static class BuildException extends RuntimeException {}\n"
                               "}\n")})
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Result")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Input")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/BuildException")))
    (is (not (contains? reference-targets
                        (extract/node-id :symbol "demo/BodyMention"))))
    (is (empty? (:diagnostics result)))))
(deftest extracts-groovy-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/groovy/PanelService.groovy")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :groovy (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelService"))
    (is (contains? labels "com.example.panels/PanelService.name"))
    (is (contains? labels "com.example.panels/PanelService.loadPanel"))
    (is (contains? labels "com.example.panels/Auditable"))
    (is (contains? labels "com.example.panels/Auditable.audit"))
    (is (contains? labels "com.example.panels/PanelStatus"))
    (is (contains? labels "com.example.panels/PanelBinding"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "groovy.transform.CompileStatic")))
    (is (contains? import-targets
                   (extract/node-id :namespace "com.example.panels.PanelStatus.READY")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :definition-kind])))
    (is (= :property
           (get-in chunks-by-label ["com.example.panels/PanelService.name" :definition-kind])))
    (is (= [:groovy-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.groovy"
                                      :path "Broken.groovy"
                                      :kind :groovy
                                      :content "package demo\nclass Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest java-parser-worker-extractor-emits-canonical-rows-when-enabled
  (let [python-bin (or (not-empty (System/getenv "AGRAPH_PARSER_WORKER_PYTHON"))
                       ".dev/parser-worker-venv/bin/python")
        worker-ready? (and (.isFile (io/file python-bin))
                           (zero? (:exit (shell/sh python-bin
                                                   "-c"
                                                   "import tree_sitter_language_pack"))))]
    (when worker-ready?
      (let [result (with-redefs [extract/parser-worker-enabled? (constantly true)
                                 extract/parser-worker-python (constantly python-bin)]
                     (extract/extract-file
                      "run/test"
                      {:file-id "file:WorkerDemo.java"
                       :path "src/WorkerDemo.java"
                       :kind :java
                       :content (str "package demo;\n"
                                     "import java.net.URI;\n"
                                     "class App {\n"
                                     "  Target make(Param p) { return new Target(); }\n"
                                     "  static class Target {}\n"
                                     "  interface Param {}\n"
                                     "}\n")}))
            labels (set (map :label (:nodes result)))
            reference-targets (set (map :target-id
                                        (filter #(= :references (:relation %))
                                                (:edges result))))]
        (is (contains? labels "demo"))
        (is (contains? labels "demo/App"))
        (is (contains? labels "demo/App.make"))
        (is (contains? labels "demo/App.Target"))
        (is (contains? labels "demo/App.Param"))
        (is (contains? reference-targets
                       (extract/node-id :symbol "demo/Target")))
        (is (contains? reference-targets
                       (extract/node-id :symbol "demo/Param")))
        (is (empty? (:diagnostics result)))))))
(deftest java-parser-worker-adapter-covers-rich-java-declarations
  (let [result (with-redefs [extract/parser-worker-enabled? (constantly true)]
                 (extract/extract-file
                  "run/test"
                  {:file-id "file:Rich.java"
                   :path "src/Rich.java"
                   :kind :java
                   :content (str "package demo;\n"
                                 "import java.util.List;\n"
                                 "public @interface Marker {}\n"
                                 "public record Item(String id) {}\n"
                                 "public interface Port { Result load(Input input); }\n"
                                 "public class App {\n"
                                 "  public App() {}\n"
                                 "  public Result load(Input input) {\n"
                                 "    return new Result();\n"
                                 "  }\n"
                                 "  public class Result {}\n"
                                 "}\n"
                                 "public class Input {}\n")
                   :parser-worker-facts
                   {:package "demo"
                    :definitions [{:kind "annotation"
                                   :name "Marker"
                                   :line 3
                                   :endLine 3}
                                  {:kind "record"
                                   :name "Item"
                                   :line 4
                                   :endLine 4}
                                  {:kind "interface"
                                   :name "Port"
                                   :line 5
                                   :endLine 5}
                                  {:kind "method"
                                   :name "Port.load"
                                   :line 5
                                   :endLine 5}
                                  {:kind "class"
                                   :name "App"
                                   :line 6
                                   :endLine 12}
                                  {:kind "constructor"
                                   :name "App.App"
                                   :line 7
                                   :endLine 7}
                                  {:kind "method"
                                   :name "App.load"
                                   :line 8
                                   :endLine 10}
                                  {:kind "class"
                                   :name "App.Result"
                                   :line 11
                                   :endLine 11}
                                  {:kind "class"
                                   :name "Input"
                                   :line 13
                                   :endLine 13}]
                    :imports [{:target "java.util.List"
                               :line 2}]
                    :references [{:source "App.load"
                                  :target "Result"
                                  :kind "type"
                                  :line 8}
                                 {:source "App.load"
                                  :target "Input"
                                  :kind "type"
                                  :line 8}]
                    :diagnostics []}}))
        kinds-by-label (into {} (map (juxt :label :kind)) (:nodes result))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (= :annotation (kinds-by-label "demo/Marker")))
    (is (= :record (kinds-by-label "demo/Item")))
    (is (= :interface (kinds-by-label "demo/Port")))
    (is (= :method (kinds-by-label "demo/Port.load")))
    (is (= :class (kinds-by-label "demo/App")))
    (is (= :constructor (kinds-by-label "demo/App.App")))
    (is (= :method (kinds-by-label "demo/App.load")))
    (is (= :class (kinds-by-label "demo/App.Result")))
    (is (= :class (kinds-by-label "demo/Input")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Result")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "demo/Input")))
    (is (= 10 (get-in chunks-by-label ["demo/App.load" :end-line])))
    (is (str/includes? (get-in chunks-by-label ["demo/App.load" :text])
                       "return new Result"))
    (is (empty? (:diagnostics result)))))
(deftest extracts-dotnet-namespaces-definitions-and-usings
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/dotnet/PanelService.cs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :dotnet (:kind file)))
    (is (contains? labels "Acme.Panels"))
    (is (contains? labels "Acme.Panels/Panel"))
    (is (contains? labels "Acme.Panels/PanelService"))
    (is (contains? labels "Acme.Panels/PanelService.PanelService"))
    (is (contains? labels "Acme.Panels/PanelService.LoadPanel"))
    (is (contains? labels "Acme.Panels/PanelService.Normalize"))
    (is (contains? labels "Acme.Panels/PanelService.Name"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "System")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme.Contracts")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :definition-kind])))
    (is (= :property
           (get-in chunks-by-label ["Acme.Panels/PanelService.Name" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["Acme.Panels/PanelService.LoadPanel" :text])
         "client.Fetch"))
    (is (= [:dotnet-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.cs"
                                      :path "Broken.cs"
                                      :kind :dotnet
                                      :content "namespace Demo;\npublic class Broken\n{\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces")))
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:BlockScoped.cs"
                 :path "BlockScoped.cs"
                 :kind :dotnet
                 :content (str "namespace Acme.Block {\n"
                               "public interface IPanel {}\n"
                               "public enum PanelKind { Primary }\n"
                               "public struct PanelKey {}\n"
                               "public delegate void PanelLoaded(string id);\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "Acme.Block"))
    (is (contains? labels "Acme.Block/IPanel"))
    (is (contains? labels "Acme.Block/PanelKind"))
    (is (contains? labels "Acme.Block/PanelKey"))
    (is (contains? labels "Acme.Block/PanelLoaded"))
    (is (empty? (:diagnostics result)))))
(deftest extracts-fsharp-and-visual-basic-dotnet-declarations
  (let [fsharp (extract/extract-file
                "run/test"
                {:file-id "file:PanelService.fs"
                 :path "src/dotnet/PanelService.fs"
                 :kind :dotnet
                 :content (str "namespace Acme.Panels\n"
                               "open System\n"
                               "module PanelService =\n"
                               "  type Panel = { Id: string }\n"
                               "  let loadPanel id = id\n"
                               "  type PanelServiceClient() =\n"
                               "    member _.Refresh() = ()\n")})
        vb (extract/extract-file
            "run/test"
            {:file-id "file:PanelService.vb"
             :path "src/dotnet/PanelService.vb"
             :kind :dotnet
             :content (str "Namespace Acme.Panels\n"
                           "Imports System.Collections.Generic\n"
                           "Public Class PanelService\n"
                           "  Public Function LoadPanel(id As String) As Panel\n"
                           "  End Function\n"
                           "  Public Property Name As String\n"
                           "End Class\n"
                           "End Namespace\n")})
        fs-labels (set (map :label (:nodes fsharp)))
        vb-labels (set (map :label (:nodes vb)))
        fs-import-targets (set (map :target-id
                                    (filter #(= :imports (:relation %))
                                            (:edges fsharp))))
        vb-import-targets (set (map :target-id
                                    (filter #(= :imports (:relation %))
                                            (:edges vb))))
        fs-chunks (into {} (map (juxt :label identity)) (:chunks fsharp))
        vb-chunks (into {} (map (juxt :label identity)) (:chunks vb))]
    (is (= :dotnet (fs/file-kind "PanelService.fsx")))
    (is (= :dotnet (fs/file-kind "PanelService.fsi")))
    (is (contains? fs-labels "Acme.Panels"))
    (is (contains? fs-labels "Acme.Panels/PanelService"))
    (is (contains? fs-labels "Acme.Panels/PanelService.Panel"))
    (is (contains? fs-labels "Acme.Panels/PanelService.loadPanel"))
    (is (contains? fs-labels "Acme.Panels/PanelService.PanelServiceClient"))
    (is (contains? fs-labels "Acme.Panels/PanelService.PanelServiceClient.Refresh"))
    (is (contains? fs-import-targets
                   (extract/node-id :namespace "System")))
    (is (= :function
           (get-in fs-chunks ["Acme.Panels/PanelService.loadPanel" :definition-kind])))
    (is (contains? vb-labels "Acme.Panels"))
    (is (contains? vb-labels "Acme.Panels/PanelService"))
    (is (contains? vb-labels "Acme.Panels/PanelService.LoadPanel"))
    (is (contains? vb-labels "Acme.Panels/PanelService.Name"))
    (is (contains? vb-import-targets
                   (extract/node-id :namespace "System.Collections.Generic")))
    (is (= :method
           (get-in vb-chunks ["Acme.Panels/PanelService.LoadPanel" :definition-kind])))
    (is (= :property
           (get-in vb-chunks ["Acme.Panels/PanelService.Name" :definition-kind])))
    (is (empty? (:diagnostics fsharp)))
    (is (empty? (:diagnostics vb)))))
(deftest dotnet-extractor-ignores-call-expressions-inside-method-bodies
  (let [result (extract/extract-file
                "run/test"
                {:file-id "file:Noisy.cs"
                 :path "Noisy.cs"
                 :kind :dotnet
                 :content (str "namespace Demo;\n"
                               "public class App {\n"
                               "  public App() {}\n"
                               "  public string Run(object input) {\n"
                               "    var text = Convert.ToString(input);\n"
                               "    throw new ArgumentNullException(nameof(input));\n"
                               "  }\n"
                               "  public string Echo(object input) =>\n"
                               "    Convert.ToString(input);\n"
                               "}\n")})
        labels (set (map :label (:nodes result)))]
    (is (contains? labels "Demo/App.App"))
    (is (contains? labels "Demo/App.Run"))
    (is (contains? labels "Demo/App.Echo"))
    (is (not (contains? labels "Demo/App.ToString")))
    (is (not (contains? labels "Demo/App.ArgumentNullException")))
    (is (not (contains? labels "Demo/App.nameof")))
    (is (empty? (:diagnostics result)))))
(deftest dotnet-parser-worker-adapter-covers-rich-declarations
  (let [result (with-redefs [extract/parser-worker-enabled? (constantly true)]
                 (extract/extract-file
                  "run/test"
                  {:file-id "file:Rich.cs"
                   :path "src/Rich.cs"
                   :kind :dotnet
                   :content (str "namespace Demo;\n"
                                 "using System.Collections.Generic;\n"
                                 "public delegate void Loaded(string id);\n"
                                 "public interface IPort { Result Load(Input input); }\n"
                                 "public record Item(string Id);\n"
                                 "public class App : IPort {\n"
                                 "  public App() {}\n"
                                 "  public Result Load(Input input) {\n"
                                 "    return new Result();\n"
                                 "  }\n"
                                 "  public string Name { get; init; }\n"
                                 "  public class Result {}\n"
                                 "}\n"
                                 "public class Input {}\n")
                   :parser-worker-facts
                   {:namespace "Demo"
                    :definitions [{:kind "delegate"
                                   :name "Loaded"
                                   :line 3
                                   :endLine 3}
                                  {:kind "interface"
                                   :name "IPort"
                                   :line 4
                                   :endLine 4}
                                  {:kind "method"
                                   :name "IPort.Load"
                                   :line 4
                                   :endLine 4}
                                  {:kind "record"
                                   :name "Item"
                                   :line 5
                                   :endLine 5}
                                  {:kind "class"
                                   :name "App"
                                   :line 6
                                   :endLine 13}
                                  {:kind "constructor"
                                   :name "App.App"
                                   :line 7
                                   :endLine 7}
                                  {:kind "method"
                                   :name "App.Load"
                                   :line 8
                                   :endLine 10}
                                  {:kind "property"
                                   :name "App.Name"
                                   :line 11
                                   :endLine 11}
                                  {:kind "class"
                                   :name "App.Result"
                                   :line 12
                                   :endLine 12}
                                  {:kind "class"
                                   :name "Input"
                                   :line 14
                                   :endLine 14}]
                    :imports [{:target "System.Collections.Generic"
                               :line 2}]
                    :references [{:source "App.Load"
                                  :target "Result"
                                  :kind "type"
                                  :line 8}
                                 {:source "App.Load"
                                  :target "Input"
                                  :kind "type"
                                  :line 8}]
                    :diagnostics []}}))
        kinds-by-label (into {} (map (juxt :label :kind)) (:nodes result))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))]
    (is (= :delegate (kinds-by-label "Demo/Loaded")))
    (is (= :interface (kinds-by-label "Demo/IPort")))
    (is (= :method (kinds-by-label "Demo/IPort.Load")))
    (is (= :record (kinds-by-label "Demo/Item")))
    (is (= :class (kinds-by-label "Demo/App")))
    (is (= :constructor (kinds-by-label "Demo/App.App")))
    (is (= :method (kinds-by-label "Demo/App.Load")))
    (is (= :property (kinds-by-label "Demo/App.Name")))
    (is (= :class (kinds-by-label "Demo/App.Result")))
    (is (= :class (kinds-by-label "Demo/Input")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "Demo/Result")))
    (is (contains? reference-targets
                   (extract/node-id :symbol "Demo/Input")))
    (is (= 10 (get-in chunks-by-label ["Demo/App.Load" :end-line])))
    (is (str/includes? (get-in chunks-by-label ["Demo/App.Load" :text])
                       "return new Result"))
    (is (empty? (:diagnostics result)))))
(deftest extracts-kotlin-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/mobile/android/PanelActivity.kt")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :kotlin (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelActivity"))
    (is (contains? labels "com.example.panels/PanelActivity.title"))
    (is (contains? labels "com.example.panels/PanelActivity.loadPanel"))
    (is (contains? labels "com.example.panels/PanelActivity.Companion"))
    (is (contains? labels "com.example.panels/PanelActivity.Companion.routePrefix"))
    (is (contains? labels "com.example.panels/PanelRoutes"))
    (is (contains? labels "com.example.panels/PanelRoutes.route"))
    (is (contains? labels "com.example.panels/PanelMode"))
    (is (contains? labels "com.example.panels/PanelBinding"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "android.app.Activity")))
    (is (contains? import-targets
                   (extract/node-id :namespace "com.example.panels.data.PanelRepository")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelActivity.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["com.example.panels/PanelActivity.loadPanel" :definition-kind])))
    (is (= [:kotlin-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.kt"
                                      :path "Broken.kt"
                                      :kind :kotlin
                                      :content "package demo\nclass Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-swift-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/mobile/ios/PanelViewModel.swift")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :swift (:kind file)))
    (is (contains? labels "mobile.ios.PanelViewModel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.title"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.init"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.loadPanel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelView"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelView.model"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelLoading"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelLoading.loadPanel"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelCache"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelCache.count"))
    (is (contains? labels "mobile.ios.PanelViewModel/PanelViewModel.refresh"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Foundation")))
    (is (contains? import-targets
                   (extract/node-id :namespace "SwiftUI")))
    (is (= :code-definition
           (get-in chunks-by-label ["mobile.ios.PanelViewModel/PanelViewModel.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["mobile.ios.PanelViewModel/PanelViewModel.loadPanel" :definition-kind])))
    (is (= [:swift-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.swift"
                                      :path "Broken.swift"
                                      :kind :swift
                                      :content "public class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-objective-c-imports-declarations-and-methods
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/mobile/ios/PanelService.m")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds-by-label (into {} (map (juxt :label :kind)) (:nodes result))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :objective-c (:kind file)))
    (is (= :objective-c (fs/file-kind "PanelService.mm")))
    (is (contains? labels "mobile.ios.PanelService"))
    (is (= :forward-class (kinds-by-label "mobile.ios.PanelService/PanelClient")))
    (is (= :enum (kinds-by-label "mobile.ios.PanelService/PanelState")))
    (is (= :protocol (kinds-by-label "mobile.ios.PanelService/PanelStore")))
    (is (= :method (kinds-by-label "mobile.ios.PanelService/PanelStore.loadPanel")))
    (is (= :interface (kinds-by-label "mobile.ios.PanelService/PanelService")))
    (is (= :implementation (kinds-by-label "mobile.ios.PanelService/PanelService.implementation")))
    (is (= :class-method (kinds-by-label "mobile.ios.PanelService/PanelService.sharedService")))
    (is (= :method (kinds-by-label "mobile.ios.PanelService/PanelService.loadPanel")))
    (is (= :category (kinds-by-label "mobile.ios.PanelService/PanelService.Testing")))
    (is (= :method (kinds-by-label "mobile.ios.PanelService/PanelService.Testing.resetForTesting")))
    (is (= 3 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Foundation/Foundation.h")))
    (is (contains? import-targets
                   (extract/node-id :namespace "PanelClient.h")))
    (is (contains? import-targets
                   (extract/node-id :namespace "UIKit")))
    (is (= :code-definition
           (get-in chunks-by-label ["mobile.ios.PanelService/PanelService.loadPanel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["mobile.ios.PanelService/PanelService.loadPanel" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["mobile.ios.PanelService/PanelService.loadPanel" :text])
         "[Panel new]"))
    (is (= [:objective-c-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.m"
                                      :path "Broken.m"
                                      :kind :objective-c
                                      :content "@implementation Broken\n- (void)run {\n@end\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-ruby-definitions-requires-and-rake-tasks
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/ruby/panel_service.rb")
        rakefile (fs/file-record "test/fixtures/extractor-repo"
                                 "test/fixtures/extractor-repo/src/ruby/Rakefile")
        result (extract/extract-file "run/test" file)
        rake-result (extract/extract-file "run/test" rakefile)
        labels (set (map :label (:nodes result)))
        rake-labels (set (map :label (:nodes rake-result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :ruby (:kind file)))
    (is (= :ruby (:kind rakefile)))
    (is (contains? labels "src.ruby.panel_service"))
    (is (contains? labels "src.ruby.panel_service/Panels"))
    (is (contains? labels "src.ruby.panel_service/Panels::DEFAULT_PANEL"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService#load_panel"))
    (is (contains? labels "src.ruby.panel_service/Panels::PanelService.audit"))
    (is (contains? rake-labels "src.ruby.Rakefile/build"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "json")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.ruby.panel_service/Panels::PanelService#load_panel" :kind])))
    (is (= :method
           (get-in chunks-by-label ["src.ruby.panel_service/Panels::PanelService#load_panel" :definition-kind])))
    (is (= [:ruby-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-cpp-includes-definitions-and-diagnostics
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/native/panel_service.cpp")
        header (fs/file-record "test/fixtures/extractor-repo"
                               "test/fixtures/extractor-repo/src/native/panel_service.hpp")
        result (extract/extract-file "run/test" file)
        header-result (extract/extract-file "run/test" header)
        labels (set (map :label (:nodes result)))
        header-labels (set (map :label (:nodes header-result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :cpp (:kind file)))
    (is (= :cpp (:kind header)))
    (is (contains? labels "src.native.panel_service"))
    (is (contains? labels "src.native.panel_service/PANEL_SERVICE_VERSION"))
    (is (contains? labels "src.native.panel_service/panels"))
    (is (contains? labels "src.native.panel_service/panels::PanelId"))
    (is (contains? labels "src.native.panel_service/panels::PanelService::load_panel"))
    (is (contains? labels "src.native.panel_service/panels::build_panel"))
    (is (contains? header-labels "src.native.panel_service/PanelName"))
    (is (contains? header-labels "src.native.panel_service/panels"))
    (is (contains? header-labels "src.native.panel_service/panels::PanelService"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_service.hpp")))
    (is (contains? import-targets
                   (extract/node-id :namespace "vector")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.native.panel_service/panels::build_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.native.panel_service/panels::build_panel" :definition-kind])))
    (is (= [:cpp-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.cpp"
                                      :path "broken.cpp"
                                      :kind :cpp
                                      :content "namespace demo {\nint broken() {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-dart-imports-definitions-and-diagnostics
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/flutter/lib/panel_store.dart")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :dart (:kind file)))
    (is (contains? labels "panels.store"))
    (is (contains? labels "panels.store/PanelStore"))
    (is (contains? labels "panels.store/PanelStore.PanelStore"))
    (is (contains? labels "panels.store/PanelStore.client"))
    (is (contains? labels "panels.store/PanelStore.cacheName"))
    (is (contains? labels "panels.store/PanelStore.loadPanel"))
    (is (contains? labels "panels.store/PanelStore.selected"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "package:flutter/widgets.dart")))
    (is (contains? import-targets
                   (extract/node-id :namespace "package:panels/client.dart")))
    (is (= :code-definition
           (get-in chunks-by-label ["panels.store/PanelStore.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["panels.store/PanelStore.loadPanel" :definition-kind])))
    (is (= [:dart-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.dart"
                                      :path "broken.dart"
                                      :kind :dart
                                      :content "class Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-scala-packages-definitions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/scala/PanelService.scala")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :scala (:kind file)))
    (is (contains? labels "com.example.panels"))
    (is (contains? labels "com.example.panels/PanelRepository"))
    (is (contains? labels "com.example.panels/PanelRepository.findPanel"))
    (is (contains? labels "com.example.panels/PanelService"))
    (is (contains? labels "com.example.panels/PanelService.loadPanel"))
    (is (contains? labels "com.example.panels/PanelService.cacheName"))
    (is (contains? labels "com.example.panels/PanelRoutes"))
    (is (contains? labels "com.example.panels/PanelRoutes.route"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "cats.effect.IO")))
    (is (contains? import-targets
                   (extract/node-id :namespace "com.example.panels.client.PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["com.example.panels/PanelService.loadPanel" :definition-kind])))
    (is (= [:scala-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:Broken.scala"
                                      :path "Broken.scala"
                                      :kind :scala
                                      :content "package demo\nclass Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-elixir-modules-functions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/elixir/panel_service.ex")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :elixir (:kind file)))
    (is (contains? labels "Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.Loader"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel"))
    (is (contains? labels "Acme.Panels.PanelService/Acme.Panels.PanelService.normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme.Panels.PanelClient")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Logger")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme.Panels.PanelService/Acme.Panels.PanelService.load_panel" :definition-kind])))
    (is (= [:elixir-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-erlang-modules-functions-and-imports
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/erlang/panel_service.erl")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :erlang (:kind file)))
    (is (contains? labels "panel_service"))
    (is (contains? labels "panel_service/gen_server"))
    (is (contains? labels "panel_service/panel"))
    (is (contains? labels "panel_service/load_panel"))
    (is (contains? labels "panel_service/load_panel/1"))
    (is (contains? labels "panel_service/normalize_id/1"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel.hrl")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["panel_service/load_panel/1" :kind])))
    (is (= :function
           (get-in chunks-by-label ["panel_service/load_panel/1" :definition-kind])))
    (is (= [:erlang-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-planned-language-variant-declarations
  (let [php-result (extract/extract-file
                    "run/test"
                    {:file-id "file:variants.php"
                     :path "variants.php"
                     :kind :php
                     :content (str "<?php\n"
                                   "namespace Demo;\n"
                                   "interface Port {}\n"
                                   "trait Logs {}\n"
                                   "enum State { case Active; }\n"
                                   "const VERSION = '1';\n")})
        scala-result (extract/extract-file
                      "run/test"
                      {:file-id "file:Variants.scala"
                       :path "Variants.scala"
                       :kind :scala
                       :content (str "package demo\n"
                                     "case class Panel(id: String)\n"
                                     "enum PanelKind { case Active }\n")})
        dart-result (extract/extract-file
                     "run/test"
                     {:file-id "file:variants.dart"
                      :path "variants.dart"
                      :kind :dart
                      :content (str "library demo.variants;\n"
                                    "final String topLevel = 'x';\n"
                                    "enum PanelKind { active }\n"
                                    "mixin Trackable {}\n"
                                    "extension PanelExt on String {\n"
                                    "  String get panelId => this;\n"
                                    "}\n")})
        elixir-result (extract/extract-file
                       "run/test"
                       {:file-id "file:variants.ex"
                        :path "variants.ex"
                        :kind :elixir
                        :content (str "defmodule Demo.Macros do\n"
                                      "  defmacro panel(name) do\n"
                                      "    name\n"
                                      "  end\n"
                                      "end\n")})
        labels (fn [result] (set (map :label (:nodes result))))
        kinds-by-label (fn [result]
                         (into {} (map (juxt :label :kind)) (:nodes result)))]
    (is (contains? (labels php-result) "Demo/Port"))
    (is (= :interface ((kinds-by-label php-result) "Demo/Port")))
    (is (= :trait ((kinds-by-label php-result) "Demo/Logs")))
    (is (= :enum ((kinds-by-label php-result) "Demo/State")))
    (is (= :constant ((kinds-by-label php-result) "Demo/VERSION")))
    (is (= :class ((kinds-by-label scala-result) "demo/Panel")))
    (is (= :enum ((kinds-by-label scala-result) "demo/PanelKind")))
    (is (= :variable ((kinds-by-label dart-result) "demo.variants/topLevel")))
    (is (= :enum ((kinds-by-label dart-result) "demo.variants/PanelKind")))
    (is (= :mixin ((kinds-by-label dart-result) "demo.variants/Trackable")))
    (is (= :extension ((kinds-by-label dart-result) "demo.variants/PanelExt")))
    (is (= :getter ((kinds-by-label dart-result) "demo.variants/PanelExt.panelId")))
    (is (= :macro ((kinds-by-label elixir-result) "Demo.Macros/Demo.Macros.panel")))))
(deftest extracts-lua-requires-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/lua/panel_service.lua")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :lua (:kind file)))
    (is (contains? labels "src.lua.panel_service"))
    (is (contains? labels "src.lua.panel_service/M"))
    (is (contains? labels "src.lua.panel_service/M.load_panel"))
    (is (contains? labels "src.lua.panel_service/normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "json")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.lua.panel_service/M.load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.lua.panel_service/M.load_panel" :definition-kind])))
    (is (= [:lua-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-r-imports-and-functions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/r/panel_service.R")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :r (:kind file)))
    (is (contains? labels "src.r.panel_service"))
    (is (contains? labels "src.r.panel_service/load_panel"))
    (is (contains? labels "src.r.panel_service/.normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "dplyr")))
    (is (contains? import-targets
                   (extract/node-id :namespace "jsonlite")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.r.panel_service/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.r.panel_service/load_panel" :definition-kind])))
    (is (= [:r-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-julia-modules-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/julia/PanelService.jl")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :julia (:kind file)))
    (is (contains? labels "PanelService"))
    (is (contains? labels "PanelService/Panel"))
    (is (contains? labels "PanelService/load_panel"))
    (is (contains? labels "PanelService/normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "DataFrames")))
    (is (contains? import-targets
                   (extract/node-id :namespace "JSON3")))
    (is (= :code-definition
           (get-in chunks-by-label ["PanelService/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["PanelService/load_panel" :definition-kind])))
    (is (= [:julia-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-ocaml-modules-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/ocaml/panel_service.ml")
        interface-file (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/src/ocaml/panel_service.mli")
        result (extract/extract-file "run/test" file)
        interface-result (extract/extract-file "run/test" interface-file)
        labels (set (map :label (:nodes result)))
        interface-labels (set (map :label (:nodes interface-result)))
        kinds-by-label (into {} (map (juxt :label :kind)) (:nodes result))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :ocaml (:kind file)))
    (is (= :ocaml (:kind interface-file)))
    (is (contains? labels "src.ocaml.panel_service"))
    (is (= :module (kinds-by-label "src.ocaml.panel_service/Client")))
    (is (= :module-type (kinds-by-label "src.ocaml.panel_service/STORE")))
    (is (= :type (kinds-by-label "src.ocaml.panel_service/panel")))
    (is (= :exception (kinds-by-label "src.ocaml.panel_service/Panel_not_found")))
    (is (= :class (kinds-by-label "src.ocaml.panel_service/panel_cache")))
    (is (= :external (kinds-by-label "src.ocaml.panel_service/hash_panel")))
    (is (= :function (kinds-by-label "src.ocaml.panel_service/normalize_id")))
    (is (= :function (kinds-by-label "src.ocaml.panel_service/load_panel")))
    (is (= 3 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Core")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Panel_sig")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Panel_client")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.ocaml.panel_service/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.ocaml.panel_service/load_panel" :definition-kind])))
    (is (= [:ocaml-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (contains? interface-labels "src.ocaml.panel_service/normalize_id"))
    (is (contains? interface-labels "src.ocaml.panel_service/load_panel"))
    (is (= :value
           (some #(when (= "src.ocaml.panel_service/load_panel" (:label %))
                    (:kind %))
                 (:nodes interface-result))))
    (is (empty? (:diagnostics result)))
    (is (empty? (:diagnostics interface-result)))))
(deftest extracts-perl-packages-imports-and-subroutines
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/perl/PanelService.pm")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :perl (:kind file)))
    (is (contains? labels "Acme::Panels::PanelService"))
    (is (contains? labels "Acme::Panels::PanelService/load_panel"))
    (is (contains? labels "Acme::Panels::PanelService/_normalize_id"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "JSON::MaybeXS")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Acme::Panels::PanelClient")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme::Panels::PanelService/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme::Panels::PanelService/load_panel" :definition-kind])))
    (is (= [:perl-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-haskell-modules-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/haskell/PanelService.hs")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :haskell (:kind file)))
    (is (contains? labels "Acme.Panels.PanelService"))
    (is (contains? labels "Acme.Panels.PanelService/Panel"))
    (is (contains? labels "Acme.Panels.PanelService/loadPanel"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "Data.Text")))
    (is (contains? import-targets
                   (extract/node-id :namespace "Data.Aeson")))
    (is (= :code-definition
           (get-in chunks-by-label ["Acme.Panels.PanelService/loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["Acme.Panels.PanelService/loadPanel" :definition-kind])))
    (is (= [:haskell-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-zig-imports-and-definitions
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/zig/panel_service.zig")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))]
    (is (= :zig (:kind file)))
    (is (contains? labels "src.zig.panel_service"))
    (is (contains? labels "src.zig.panel_service/Panel"))
    (is (contains? labels "src.zig.panel_service/loadPanel"))
    (is (contains? labels "src.zig.panel_service/normalizeId"))
    (is (= 2 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "std")))
    (is (contains? import-targets
                   (extract/node-id :namespace "panel_client.zig")))
    (is (= :code-definition
           (get-in chunks-by-label ["src.zig.panel_service/loadPanel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["src.zig.panel_service/loadPanel" :definition-kind])))
    (is (= [:zig-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))))
(deftest extracts-odin-packages-imports-definitions-and-config
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/src/odin/panel_service.odin")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))
        config-file (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/src/odin/ols.json")
        config-result (extract/extract-file "run/test" config-file)
        config-labels (set (map :label (:nodes config-result)))]
    (is (= :odin (:kind file)))
    (is (= :odin (:kind config-file)))
    (is (contains? labels "panels"))
    (is (contains? labels "panels/Panel"))
    (is (contains? labels "panels/Status"))
    (is (contains? labels "panels/Payload"))
    (is (contains? labels "panels/Default_ID"))
    (is (contains? labels "panels/active_count"))
    (is (contains? labels "panels/load_panel"))
    (is (contains? labels "panels/normalize_id"))
    (is (contains? labels "libc:system:c"))
    (is (= 3 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "core:fmt")))
    (is (contains? import-targets
                   (extract/node-id :namespace "core:encoding/json")))
    (is (contains? import-targets
                   (extract/node-id :namespace "system:c")))
    (is (= :code-definition
           (get-in chunks-by-label ["panels/load_panel" :kind])))
    (is (= :function
           (get-in chunks-by-label ["panels/load_panel" :definition-kind])))
    (is (= [:odin-file]
           (->> (:chunks result)
                (remove #(= :code-definition (:kind %)))
                (mapv :kind))))
    (is (contains? config-labels "src/odin/ols.json"))
    (is (contains? config-labels "panels-odin"))
    (is (contains? config-labels "odin"))
    (is (contains? config-labels "panels=src/odin"))
    (is (contains? config-labels "vendor=vendor/odin"))
    (is (contains? config-labels "src/odin"))
    (is (= [:odin-config-file]
           (mapv :kind (:chunks config-result))))))
