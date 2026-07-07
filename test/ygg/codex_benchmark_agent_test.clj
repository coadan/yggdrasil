(ns ygg.codex-benchmark-agent-test
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (-> (java.nio.file.Files/createTempDirectory prefix
                                               (make-array java.nio.file.attribute.FileAttribute 0))
      .toFile
      .getCanonicalFile))

(defn- option-value
  [args option]
  (second (drop-while #(not= option %) args)))

(deftest codex-benchmark-agent-writes-token-usage-sidecar
  (let [root (temp-dir "ygg-codex-agent-wrapper")
        prompt (io/file root "prompt.txt")
        result (io/file root "result.json")
        usage (io/file root "token-usage.json")
        mock (io/file root "mock-codex.py")]
    (spit prompt "benchmark prompt")
    (spit mock
          (str/join
           "\n"
           ["import json, os, sys"
            "assert sys.stdin.read() == 'benchmark prompt'"
            "with open(os.environ['YGG_BENCH_RESULT'], 'w') as f:"
            "    json.dump({'schema':'ygg.benchmark.agent-result/v2','suspectedFiles':[]}, f)"
            "print(json.dumps({'type':'turn_delta','usage':{'input_tokens':5,'output_tokens':3}}))"
            "print(json.dumps({'type':'turn_complete','usage':{'input_tokens':11,'output_tokens':7,'total_tokens':18}}))"]))
    (let [run (shell/sh "python3"
                        "scripts/codex-benchmark-agent.py"
                        :env {"YGG_BENCH_PROMPT" (.getPath prompt)
                              "YGG_BENCH_RESULT" (.getPath result)
                              "YGG_BENCH_TOKEN_USAGE" (.getPath usage)
                              "YGG_BENCH_WORKTREE" (.getPath root)
                              "YGG_CODEX_COMMAND" (str "python3 " (.getPath mock))
                              "YGG_CODEX_MODEL" "gpt-test"})]
      (is (= 0 (:exit run)))
      (is (str/includes? (:out run) "turn_complete"))
      (is (.isFile result))
      (is (= {:inputTokens 11
              :outputTokens 7
              :totalTokens 18
              :costUsd 0.0
              :source "codex-json-events"
              :model "gpt-test"
              :provider "openai"}
             (json/read-json (slurp usage) :key-fn keyword))))))

(defn- captured-codex-argv
  [root {:keys [result-scope sandbox]}]
  (let [label (or result-scope "default")
        prompt (io/file root (str "prompt-" label ".txt"))
        result (io/file root (str "result-" label ".json"))
        argv-path (io/file root (str "argv-" label ".json"))
        mock (io/file root (str "mock-codex-" label ".py"))]
    (spit prompt "benchmark prompt")
    (spit mock
          (str/join
           "\n"
           ["#!/usr/bin/env python3"
            "import json, os, sys"
            "assert sys.stdin.read() == 'benchmark prompt'"
            "with open(os.environ['YGG_CODEX_ARGV'], 'w') as f:"
            "    json.dump(sys.argv[1:], f)"
            "if '-o' in sys.argv:"
            "    with open(sys.argv[sys.argv.index('-o') + 1], 'w') as f:"
            "        json.dump({'schema':'ygg.benchmark.agent-result/v2','suspectedFiles':[]}, f)"
            "print(json.dumps({'type':'turn_complete','usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}))"]))
    (.setExecutable mock true)
    (let [run (shell/sh "python3"
                        "scripts/codex-benchmark-agent.py"
                        :env (cond-> {"YGG_BENCH_PROMPT" (.getPath prompt)
                                      "YGG_BENCH_RESULT" (.getPath result)
                                      "YGG_BENCH_WORKTREE" (.getPath root)
                                      "YGG_CODEX_ARGV" (.getPath argv-path)
                                      "YGG_CODEX_BIN" (.getPath mock)
                                      "YGG_CODEX_SANDBOX" (or sandbox "")}
                               result-scope
                               (assoc "YGG_BENCH_RESULT_SCOPE" result-scope)))]
      (is (= 0 (:exit run)))
      (json/read-json (slurp argv-path)))))

(deftest codex-benchmark-agent-defaults-patch-runs-to-writable-sandbox
  (let [root (temp-dir "ygg-codex-agent-sandbox")
        patch-argv (captured-codex-argv root {:result-scope "patch"})
        localization-argv (captured-codex-argv root {})
        override-argv (captured-codex-argv root {:result-scope "patch"
                                                 :sandbox "read-only"})]
    (is (= "workspace-write" (option-value patch-argv "--sandbox")))
    (is (= "read-only" (option-value localization-argv "--sandbox")))
    (is (= "read-only" (option-value override-argv "--sandbox")))))
