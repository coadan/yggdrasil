(ns agraph.parser-worker-test
  (:require [agraph.extract :as extract]
            [charred.api :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- read-json-line
  [line]
  (json/read-json line {:key-fn keyword}))

(defn- worker-with-python!
  [python-bin requests]
  (let [input (str (str/join "\n"
                             (map #(json/write-json-str % {:escape-slash false})
                                  requests))
                   "\n")
        {:keys [exit out err]} (shell/sh python-bin
                                         "scripts/parser-worker.py"
                                         :in input)]
    (when-not (zero? exit)
      (throw (ex-info "Parser worker failed."
                      {:exit exit
                       :out out
                       :err err})))
    (mapv read-json-line (remove str/blank? (str/split-lines out)))))

(defn- worker!
  [requests]
  (worker-with-python! "python3" requests))

(deftest parser-worker-emits-python-ast-facts
  (let [[response] (worker! [{:schema "agraph.parser.request/v1"
                              :id "request-1"
                              :kind "python"
                              :path "sample.py"
                              :content (str "import os\n"
                                            "from .helpers import tool\n"
                                            "\n"
                                            "class Runner:\n"
                                            "    async def start(self):\n"
                                            "        return tool()\n"
                                            "\n"
                                            "def build():\n"
                                            "    return Runner()\n")}])
        facts (:facts response)]
    (is (= "agraph.parser.response/v1" (:schema response)))
    (is (= "request-1" (:id response)))
    (is (= [{:kind "class"
             :name "Runner"
             :line 4
             :endLine 6}
            {:kind "async-method"
             :name "Runner.start"
             :line 5
             :endLine 6}
            {:kind "function"
             :name "build"
             :line 8
             :endLine 9}]
           (:definitions facts)))
    (is (= [{:target "os"
             :line 1
             :endLine 1}
            {:target ".helpers"
             :line 2
             :endLine 2}]
           (:imports facts)))
    (is (= [] (:references facts)))
    (is (= [] (:diagnostics facts)))))

(deftest parser-worker-batch-facts-returns-facts-by-path
  (let [facts-by-path (with-redefs [extract/parser-worker-enabled? #(= :python %)
                                    extract/parser-worker-python (constantly "python3")]
                        (extract/parser-worker-batch-facts
                         [{:path "one.py"
                           :kind :python
                           :content "class One:\n    pass\n"}
                          {:path "two.py"
                           :kind :python
                           :content "def two():\n    pass\n"}]))]
    (is (= #{"one.py" "two.py"} (set (keys facts-by-path))))
    (is (= [{:kind "class"
             :name "One"
             :line 1
             :endLine 2}]
           (get-in facts-by-path ["one.py" :definitions])))
    (is (= [{:kind "function"
             :name "two"
             :line 1
             :endLine 2}]
           (get-in facts-by-path ["two.py" :definitions])))))

(deftest parser-worker-batch-facts-ignores-files-without-kind
  (is (= {}
         (extract/parser-worker-batch-facts
          [{:path "unknown"
            :kind nil
            :content "opaque\n"}]))))

(deftest parser-worker-reports-python-syntax-diagnostics
  (let [[response] (worker! [{:id "bad-python"
                              :kind "python"
                              :path "bad.py"
                              :content "def broken(:\n    pass\n"}])
        diagnostic (first (get-in response [:facts :diagnostics]))]
    (is (= "bad-python" (:id response)))
    (is (= :parse (keyword (:stage diagnostic))))
    (is (= 1 (:line diagnostic)))
    (is (seq (:message diagnostic)))))

(deftest parser-worker-reports-unavailable-java-parser
  (let [[response] (worker! [{:id "java-1"
                              :kind "java"
                              :path "Demo.java"
                              :content "class Demo {}"}])
        facts (:facts response)
        diagnostic (first (:diagnostics facts))]
    (if diagnostic
      (do
        (is (= [] (:definitions facts)))
        (is (= [] (:imports facts)))
        (is (= [] (:references facts)))
        (is (= "parser-worker" (:stage diagnostic)))
        (is (str/includes? (:message diagnostic) "java parser unavailable")))
      (do
        (is (= [{:kind "class"
                 :name "Demo"
                 :line 1}]
               (:definitions facts)))
        (is (= [] (:imports facts)))
        (is (= [] (:references facts)))))))

(deftest parser-worker-emits-rich-java-facts-when-parser-is-available
  (let [[response] (worker! [{:id "java-rich"
                              :kind "java"
                              :path "Rich.java"
                              :content (str "package demo;\n"
                                            "import java.util.List;\n"
                                            "@interface Marker {}\n"
                                            "record Item(String id) {}\n"
                                            "interface Port { Result load(Input input); }\n"
                                            "class App implements Port {\n"
                                            "  App() {}\n"
                                            "  public Result load(Input input) { return new Result(); }\n"
                                            "  static class Result {}\n"
                                            "}\n"
                                            "class Input {}\n")}])
        facts (:facts response)
        diagnostic (first (:diagnostics facts))]
    (if diagnostic
      (is (str/includes? (:message diagnostic) "java parser unavailable"))
      (let [definitions (set (map (juxt :kind :name) (:definitions facts)))
            reference-targets (set (map :target (:references facts)))]
        (is (= "demo" (:package facts)))
        (is (contains? definitions ["annotation" "Marker"]))
        (is (contains? definitions ["record" "Item"]))
        (is (contains? definitions ["interface" "Port"]))
        (is (contains? definitions ["method" "Port.load"]))
        (is (contains? definitions ["class" "App"]))
        (is (contains? definitions ["constructor" "App.App"]))
        (is (contains? definitions ["method" "App.load"]))
        (is (contains? definitions ["class" "App.Result"]))
        (is (contains? definitions ["class" "Input"]))
        (is (contains? reference-targets "Result"))
        (is (contains? reference-targets "Input"))))))

(deftest parser-worker-emits-rich-dotnet-facts-when-parser-is-available
  (let [[response] (worker! [{:id "dotnet-rich"
                              :kind "dotnet"
                              :path "Rich.cs"
                              :content (str "namespace Demo;\n"
                                            "using System.Collections.Generic;\n"
                                            "public delegate void Loaded(string id);\n"
                                            "public interface IPort { Result Load(Input input); }\n"
                                            "public record Item(string Id);\n"
                                            "public class App : IPort {\n"
                                            "  public App() {}\n"
                                            "  public Result Load(Input input) => new Result();\n"
                                            "  public string Name { get; init; }\n"
                                            "  public class Result {}\n"
                                            "}\n"
                                            "public class Input {}\n")}])
        facts (:facts response)
        diagnostic (first (:diagnostics facts))]
    (if diagnostic
      (is (str/includes? (:message diagnostic) "dotnet parser unavailable"))
      (let [definitions (set (map (juxt :kind :name) (:definitions facts)))
            reference-targets (set (map :target (:references facts)))]
        (is (= "Demo" (:namespace facts)))
        (is (contains? definitions ["delegate" "Loaded"]))
        (is (contains? definitions ["interface" "IPort"]))
        (is (contains? definitions ["method" "IPort.Load"]))
        (is (contains? definitions ["record" "Item"]))
        (is (contains? definitions ["class" "App"]))
        (is (contains? definitions ["constructor" "App.App"]))
        (is (contains? definitions ["method" "App.Load"]))
        (is (contains? definitions ["property" "App.Name"]))
        (is (contains? definitions ["class" "App.Result"]))
        (is (contains? definitions ["class" "Input"]))
        (is (contains? reference-targets "Result"))
        (is (contains? reference-targets "Input"))))))

(deftest parser-worker-reports-unsupported-kinds
  (let [[response] (worker! [{:id "ruby-1"
                              :kind "ruby"
                              :path "demo.rb"
                              :content "class Demo; end"}])
        facts (:facts response)
        diagnostic (first (:diagnostics facts))]
    (is (= [] (:definitions facts)))
    (is (= [] (:imports facts)))
    (is (= [] (:references facts)))
    (is (= "parser-worker" (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unsupported parser kind: ruby"))))
