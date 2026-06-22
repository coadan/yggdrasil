(ns ygg.text-test
  (:require [ygg.text :as text]
            [clojure.test :refer [deftest is]]))

(deftest tokenization-preserves-compounds-and-adds-mechanical-parts
  (is (= ["frontend/src/app/main/ui/workspace/main_menu.cljs"
          "frontend"
          "src"
          "app"
          "main"
          "ui"
          "workspace"
          "menu"
          "cljs"]
         (text/tokenize "frontend/src/app/main/ui/workspace/main_menu.cljs")))
  (is (= ["app.main.ui.workspace.main-menu"
          "app"
          "main"
          "ui"
          "workspace"
          "menu"]
         (text/tokenize "app.main.ui.workspace.main-menu"))))

(deftest tokenization-expands-compounds-while-preserving-frequency
  (is (= ["mcp-enabled"
          "mcp"
          "enabled"
          "mcp-enabled"
          "mcp"
          "enabled"]
         (text/tokenize-all "mcp-enabled mcp-enabled"))))

(deftest compound-token-pairs-track-identifier-adjacency
  (is (= [["nvm" "remote"]
          ["remote" "version"]
          ["lts" "argon"]]
         (text/compound-token-pairs "nvm_remote_version lts/argon should not"))))

(deftest tokenization-expands-env-var-abbreviations
  (is (= ["environment-variable"
          "environment"
          "env"
          "variable"
          "var"
          "process.env.http_proxy"
          "process"
          "http"
          "proxy"]
         (text/tokenize "environment-variable process.env.HTTP_PROXY"))))

(deftest tokenization-expands-common-plural-agent-nouns
  (is (= ["lib/adapters/http.js"
          "lib"
          "adapters"
          "adapter"
          "http"
          "js"]
         (text/tokenize "lib/adapters/http.js"))))

(deftest tokenization-expands-common-plural-code-nouns
  (is (= ["sqlmapper.settings.cs"
          "sqlmapper"
          "settings"
          "setting"
          "cs"
          "sql"
          "mapper"]
         (text/tokenize "SqlMapper.Settings.cs")))
  (is (= ["enums" "enum"]
         (text/tokenize "Enums"))))

(deftest tokenization-expands-camel-and-pascal-identifiers
  (is (= ["typehandlertests"
          "type"
          "handler"
          "tests"
          "testdiscoveryoptions"
          "test"
          "discovery"
          "options"
          "discoveryrequestcreatortests"
          "request"
          "creator"
          "jsonb"
          "xmlhttprequest"
          "xml"
          "http"]
         (text/tokenize
          "TypeHandlerTests TestDiscoveryOptions DiscoveryRequestCreatorTests JSONB XMLHttpRequest"))))

(deftest tokenization-bounds-large-input-while-sampling-head-and-tail
  (binding [text/*max-tokenize-chars* 40]
    (let [tokens (set (text/tokenize (str "HeadNeedle "
                                          (apply str (repeat 200 "x"))
                                          " TailNeedle")))]
      (is (contains? tokens "headneedle"))
      (is (contains? tokens "head"))
      (is (contains? tokens "needle"))
      (is (contains? tokens "tailneedle"))
      (is (contains? tokens "tail")))))
