(ns agraph.fs
  "Repository file discovery and file metadata."
  (:require [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file Files]))

(def supported-extensions
  #{".adoc" ".asciidoc" ".astro" ".avdl" ".avsc" ".bzl" ".c" ".cc" ".cjs" ".clj" ".cljc" ".cljs" ".cmake" ".cpp" ".cs"
    ".csproj" ".css" ".cxx" ".dart" ".edn" ".entitlements" ".fs" ".fsi" ".fsx"
    ".cabal" ".ex" ".exs" ".erl" ".fsproj" ".gemspec" ".gql" ".go" ".gradle" ".graphql" ".groovy" ".h" ".hcl" ".hs" ".ini"
    ".hh" ".hpp" ".html" ".hxx" ".ico" ".ipynb" ".java" ".jpeg" ".jpg" ".js" ".json" ".jsonc" ".jsx"
    ".hrl" ".jl" ".lua"
    ".kt" ".kts" ".m" ".md" ".mdx" ".mjs" ".ml" ".mli" ".mm" ".mo" ".pm" ".pl" ".png" ".po" ".pbxproj" ".plist" ".pot" ".php"
    ".nix" ".odin" ".out" ".prisma" ".props" ".proto" ".py" ".r" ".R" ".rake" ".rb" ".rs" ".rst" ".sbt" ".scala" ".scss" ".sh"
    ".service" ".sln" ".socket" ".sql" ".svelte" ".swift" ".svg" ".targets" ".tf" ".tfvars" ".timer" ".ttf"
    ".license" ".template" ".toml" ".ts" ".tsx" ".txt" ".vb" ".vbproj" ".vue" ".xcconfig"
    ".webp" ".woff" ".woff2" ".yaml" ".yml" ".zig" ".xml"})

(def binary-file-kinds
  #{:font-asset :gettext-binary :image-asset})

(def supported-filenames
  #{"dockerfile" "docker-compose.yml" "docker-compose.yaml"
    "compose.yml" "compose.yaml" "chart.yaml" "go.mod" "mix.exs"
    "package.json" "package-lock.json" "cargo.toml" "cargo.lock" "go.work" "go.sum"
    "pnpm-lock.yaml" "yarn.lock" "gemfile.lock" "poetry.lock"
    "pubspec.lock" "composer.lock" "pipfile.lock" "uv.lock"
    "mix.lock" "requirements.txt" "bun.lock"
    "pipfile" "setup.cfg" "setup.py"
    "pyproject.toml" "pom.xml" "deno.json" "composer.json" "ols.json"
    "gemfile" "rakefile" "pubspec.yaml" "build.sbt" "rebar.config"
    "description" "namespace" "project.toml" "cpanfile" "stack.yaml"
    "build.gradle" "build.gradle.kts" "settings.gradle" "settings.gradle.kts"
    "gradle.properties" "global.json" "directory.build.props"
    "directory.build.targets" "nuget.config" "packages.config"
    "androidmanifest.xml" "info.plist" "podfile" "package.swift"
    "app.json" "app.config.json" "eas.json"
    "capacitor.config.json" "capacitor.config.ts" "capacitor.config.js"
    "capacitor.config.mjs" "capacitor.config.cjs"
    "tauri.conf.json" "tauri.conf.json5"
    "pnpm-workspace.yaml" "pnpm-workspace.yml" "turbo.json" "nx.json"
    "workspace.json" "lerna.json" "rush.json"
    "license" "copying" "notice" "security.md" "contributing.md"
    "drizzle.config.ts" "drizzle.config.js" "drizzle.config.mjs"
    "drizzle.config.cjs" "drizzle.config.json" "liquibase.properties"
    "flyway.conf" "dbt_project.yml" "dbt_project.yaml"
    "devcontainer.json" "kustomization.yaml" "kustomization.yml"
    ".pre-commit-config.yaml" ".pre-commit-config.yml"
    "codeowners" "taskfile.yml" "taskfile.yaml" "justfile" ".justfile"
    ".tool-versions" ".node-version" ".python-version" ".ruby-version"
    "mise.toml" ".mise.toml" "funding.yml" "funding.yaml"
    "nginx.conf" "pulumi.yaml"
    ".graphqlrc" ".graphqlrc.json" ".graphqlrc.yaml" ".graphqlrc.yml"
    "graphql.config.json" "graphql.config.yaml" "graphql.config.yml"
    "codegen.json" "codegen.yaml" "codegen.yml" "graphql-codegen.json"
    "graphql-codegen.yaml" "graphql-codegen.yml"
    "jest.config.js" "jest.config.cjs" "jest.config.mjs" "jest.config.ts" "jest.config.json"
    "vitest.config.js" "vitest.config.cjs" "vitest.config.mjs" "vitest.config.ts"
    "playwright.config.js" "playwright.config.cjs" "playwright.config.mjs" "playwright.config.ts"
    "cypress.config.js" "cypress.config.cjs" "cypress.config.mjs" "cypress.config.ts"
    "karma.conf.js" "pytest.ini" "tox.ini" "noxfile.py" "ava.config.js" "ava.config.cjs" "ava.config.mjs"
    "eslint.config.js" "eslint.config.cjs" "eslint.config.mjs" "eslint.config.ts"
    ".eslintrc" ".eslintrc.js" ".eslintrc.cjs" ".eslintrc.json" ".eslintrc.yaml" ".eslintrc.yml"
    "prettier.config.js" "prettier.config.cjs" "prettier.config.mjs" "prettier.config.ts"
    ".prettierrc" ".prettierrc.js" ".prettierrc.cjs" ".prettierrc.json" ".prettierrc.yaml" ".prettierrc.yml"
    "stylelint.config.js" "stylelint.config.cjs" "stylelint.config.mjs"
    ".stylelintrc" ".stylelintrc.js" ".stylelintrc.json" ".stylelintrc.yaml" ".stylelintrc.yml"
    "postcss.config.js" "postcss.config.cjs" "postcss.config.mjs"
    "tsconfig.json" "jsconfig.json" "vite.config.js" "vite.config.cjs" "vite.config.mjs" "vite.config.ts"
    "webpack.config.js" "webpack.config.cjs" "webpack.config.mjs" "webpack.config.ts"
    "rollup.config.js" "rollup.config.cjs" "rollup.config.mjs" "rollup.config.ts"
    "rspack.config.js" "rspack.config.cjs" "rspack.config.mjs" "rspack.config.ts"
    "babel.config.js" "babel.config.cjs" "babel.config.mjs" "babel.config.json"
    ".babelrc" ".babelrc.js" ".babelrc.cjs" ".babelrc.json"
    "tailwind.config.js" "tailwind.config.cjs" "tailwind.config.mjs" "tailwind.config.ts"
    "commitlint.config.js" "commitlint.config.cjs" "commitlint.config.mjs" "commitlint.config.ts"
    "lint-staged.config.js" "lint-staged.config.cjs" "lint-staged.config.mjs" "lint-staged.config.ts"
    ".lintstagedrc" ".lintstagedrc.json" ".lintstagedrc.yaml" ".lintstagedrc.yml"
    ".editorconfig" "browserslist" ".browserslistrc" "renovate.json" ".renovaterc" ".renovaterc.json"
    "biome.json" "pyrightconfig.json"
    "makefile" "gnumakefile" "cmakelists.txt" "build" "build.bazel"
    "workspace" "module.bazel" "pants.toml" "jenkinsfile"
    "azure-pipelines.yml" "azure-pipelines.yaml" ".gitlab-ci.yml"
    "buck"
    ".gitlab-ci.yaml" "buildkite.yml" "buildkite.yaml"
    "openapi.json" "openapi.yaml" "openapi.yml" "swagger.json"
    "asyncapi.json" "asyncapi.yaml" "asyncapi.yml"
    "swagger.yaml" "swagger.yml"})

(def ignored-dirs
  #{".git" ".dev" ".cpcache" ".clj-kondo" "target" "node_modules"
    ".shadow-cljs" ".calva" ".idea"})

(def ignored-filenames
  #{"bun.lockb"})

(defn env-filename?
  "Return true for dotenv-style files that need sanitized extraction."
  [filename]
  (let [filename (str/lower-case (str filename))]
    (or (= ".env" filename)
        (= ".envrc" filename)
        (str/starts-with? filename ".env.")
        (str/ends-with? filename ".env")
        (str/includes? filename ".env."))))

(defn canonical-path
  "Return canonical path string for a file/path."
  [path]
  (.getCanonicalPath (io/file path)))

(defn extension
  "Return lowercase file extension including dot."
  [path]
  (let [name (.getName (io/file path))
        idx (.lastIndexOf name ".")]
    (if (neg? idx)
      ""
      (str/lower-case (subs name idx)))))

(defn file-kind
  "Return AGraph file kind for extension."
  [path]
  (let [filename (str/lower-case (.getName (io/file path)))
        path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (contains? #{"license" "copying" "notice"} filename) :doc
      (or (contains? #{"security.md" "contributing.md"} filename)
          (re-find #"(^|/)\.github/issue_template/[^/]+\.(?:md|ya?ml|json)$" path-lower)
          (re-find #"(^|/)\.github/pull_request_template(?:/[^/]+\.md|\.md)$" path-lower)
          (re-find #"(^|/)\.github/funding\.ya?ml$" path-lower)) :governance
      (env-filename? filename) :env
      (str/ends-with? filename ".sh.in") :shell
      (str/ends-with? filename ".rb.template") :ruby
      (= "dockerfile" filename) :docker
      (= "ols.json" filename) :odin
      (or (re-find #"(^|/)\.github/workflows/[^/]+\.ya?ml$" path-lower)
          (= "jenkinsfile" filename)
          (contains? #{"azure-pipelines.yml" "azure-pipelines.yaml"} filename)
          (= ".gitlab-ci.yml" filename)
          (= ".gitlab-ci.yaml" filename)
          (re-find #"(^|/)\.circleci/config\.ya?ml$" path-lower)
          (re-find #"(^|/)\.buildkite/pipeline\.ya?ml$" path-lower)
          (contains? #{"buildkite.yml" "buildkite.yaml"} filename)) :ci
      (contains? #{"makefile" "gnumakefile" "cmakelists.txt" "build"
                   "build.bazel" "workspace" "module.bazel" "pants.toml"
                   "buck"}
                 filename) :build
      (contains? #{"rakefile"} filename) :ruby
      (contains? #{"openapi.json" "openapi.yaml" "openapi.yml"
                   "swagger.json" "swagger.yaml" "swagger.yml"}
                 filename) :openapi
      (or (contains? #{"asyncapi.json" "asyncapi.yaml" "asyncapi.yml"}
                     filename)
          (str/ends-with? filename ".asyncapi.json")
          (str/ends-with? filename ".asyncapi.yaml")
          (str/ends-with? filename ".asyncapi.yml")) :asyncapi
      (str/ends-with? filename ".schema.json") :json-schema
      (or (re-matches #"(?i)(?:V[0-9][A-Za-z0-9_.-]*|U[0-9][A-Za-z0-9_.-]*|R)__.+\.sql"
                      filename)
          (re-matches #"(?i)db\.changelog(?:[-_.][A-Za-z0-9_.-]+)?\.(?:json|ya?ml|xml)"
                      filename)) :db-migration
      (or (= "devcontainer.json" filename)
          (re-find #"(^|/)\.devcontainer/devcontainer\.json$" path-lower)) :devcontainer
      (contains? #{"kustomization.yaml" "kustomization.yml"} filename) :kustomize
      (contains? #{".pre-commit-config.yaml" ".pre-commit-config.yml"} filename) :pre-commit-config
      (= "codeowners" filename) :codeowners
      (contains? #{"taskfile.yml" "taskfile.yaml" "justfile" ".justfile"} filename) :task-runner
      (contains? #{".tool-versions" ".node-version" ".python-version" ".ruby-version"
                   "mise.toml" ".mise.toml"}
                 filename) :tool-version-config
      (contains? #{"drizzle.config.ts" "drizzle.config.js" "drizzle.config.mjs"
                   "drizzle.config.cjs" "drizzle.config.json"
                   "liquibase.properties" "flyway.conf"}
                 filename) :db-config
      (contains? #{"dbt_project.yml" "dbt_project.yaml"} filename) :dbt
      (or (= "nginx.conf" filename)
          (contains? #{"serverless.yml" "serverless.yaml" "cdk.json"} filename)
          (re-matches #"pulumi(?:\.[a-z0-9_.-]+)?\.ya?ml" filename)) :ops-config
      (contains? #{".graphqlrc" ".graphqlrc.json" ".graphqlrc.yaml" ".graphqlrc.yml"
                   "graphql.config.json" "graphql.config.yaml" "graphql.config.yml"
                   "codegen.json" "codegen.yaml" "codegen.yml"
                   "graphql-codegen.json" "graphql-codegen.yaml" "graphql-codegen.yml"}
                 filename) :codegen-config
      (contains? #{"jest.config.js" "jest.config.cjs" "jest.config.mjs" "jest.config.ts" "jest.config.json"
                   "vitest.config.js" "vitest.config.cjs" "vitest.config.mjs" "vitest.config.ts"
                   "playwright.config.js" "playwright.config.cjs" "playwright.config.mjs" "playwright.config.ts"
                   "cypress.config.js" "cypress.config.cjs" "cypress.config.mjs" "cypress.config.ts"
                   "karma.conf.js" "pytest.ini" "tox.ini" "noxfile.py"
                   "ava.config.js" "ava.config.cjs" "ava.config.mjs"}
                 filename) :test-config
      (contains? #{"eslint.config.js" "eslint.config.cjs" "eslint.config.mjs" "eslint.config.ts"
                   ".eslintrc" ".eslintrc.js" ".eslintrc.cjs" ".eslintrc.json" ".eslintrc.yaml" ".eslintrc.yml"
                   "prettier.config.js" "prettier.config.cjs" "prettier.config.mjs" "prettier.config.ts"
                   ".prettierrc" ".prettierrc.js" ".prettierrc.cjs" ".prettierrc.json" ".prettierrc.yaml" ".prettierrc.yml"
                   "stylelint.config.js" "stylelint.config.cjs" "stylelint.config.mjs"
                   ".stylelintrc" ".stylelintrc.js" ".stylelintrc.json" ".stylelintrc.yaml" ".stylelintrc.yml"
                   "postcss.config.js" "postcss.config.cjs" "postcss.config.mjs"
                   "tsconfig.json" "jsconfig.json"
                   "vite.config.js" "vite.config.cjs" "vite.config.mjs" "vite.config.ts"
                   "webpack.config.js" "webpack.config.cjs" "webpack.config.mjs" "webpack.config.ts"
                   "rollup.config.js" "rollup.config.cjs" "rollup.config.mjs" "rollup.config.ts"
                   "rspack.config.js" "rspack.config.cjs" "rspack.config.mjs" "rspack.config.ts"
                   "babel.config.js" "babel.config.cjs" "babel.config.mjs" "babel.config.json"
                   ".babelrc" ".babelrc.js" ".babelrc.cjs" ".babelrc.json"
                   "tailwind.config.js" "tailwind.config.cjs" "tailwind.config.mjs" "tailwind.config.ts"
                   "commitlint.config.js" "commitlint.config.cjs" "commitlint.config.mjs" "commitlint.config.ts"
                   "lint-staged.config.js" "lint-staged.config.cjs" "lint-staged.config.mjs" "lint-staged.config.ts"
                   ".lintstagedrc" ".lintstagedrc.json" ".lintstagedrc.yaml" ".lintstagedrc.yml"
                   ".editorconfig" "browserslist" ".browserslistrc"
                   "renovate.json" ".renovaterc" ".renovaterc.json"
                   "biome.json" "pyrightconfig.json"}
                 filename) :tool-config
      (or (re-matches #"tsconfig\.[a-z0-9_.-]+\.json" filename)
          (re-find #"(^|/)\.github/dependabot\.ya?ml$" path-lower))
      :tool-config
      (or (re-find #"(^|/)\.storybook/(?:main|preview|manager)\.(?:js|cjs|mjs|ts|tsx)$"
                   path-lower)
          (re-matches #".+\.stories\.(?:js|jsx|ts|tsx|mdx)$" filename))
      :storybook
      (contains? #{"go.mod" "go.work" "mix.exs" "package.json" "cargo.toml"
                   "pyproject.toml" "pom.xml" "deno.json" "composer.json"
                   "gemfile" "pipfile" "setup.cfg" "setup.py"
                   "pubspec.yaml" "build.sbt" "rebar.config"
                   "description" "namespace" "project.toml" "cpanfile" "stack.yaml"
                   "build.gradle" "build.gradle.kts" "settings.gradle"
                   "settings.gradle.kts" "gradle.properties" "global.json"
                   "directory.build.props" "directory.build.targets"
                   "nuget.config" "packages.config" "androidmanifest.xml"
                   "info.plist" "podfile" "package.swift" "app.json"
                   "app.config.json" "eas.json"
                   "capacitor.config.json" "capacitor.config.ts"
                   "capacitor.config.js" "capacitor.config.mjs"
                   "capacitor.config.cjs" "tauri.conf.json"
                   "tauri.conf.json5" "pnpm-workspace.yaml"
                   "pnpm-workspace.yml" "turbo.json" "nx.json"
                   "workspace.json" "lerna.json" "rush.json"}
                 filename) :manifest
      (contains? #{"package-lock.json" "cargo.lock" "go.sum"
                   "pnpm-lock.yaml" "yarn.lock" "gemfile.lock"
                   "poetry.lock" "pubspec.lock" "composer.lock"
                   "pipfile.lock" "uv.lock" "mix.lock" "requirements.txt"
                   "bun.lock"}
                 filename) :dependency-lock
      (contains? #{"docker-compose.yml" "docker-compose.yaml"
                   "compose.yml" "compose.yaml"}
                 filename) :compose
      (contains? #{"chart.yaml"} filename) :helm
      (str/starts-with? filename "values.") :helm
      :else
      (case (extension path)
        ".astro" :astro
        (".avdl" ".avsc") :avro
        ".bzl" :starlark
        (".c" ".cc" ".cpp" ".cxx" ".h" ".hh" ".hpp" ".hxx") :cpp
        ".cmake" :build
        (".clj" ".cljc" ".cljs") :code
        (".cs" ".fs" ".fsi" ".fsx" ".vb") :dotnet
        ".go" :go
        (".graphql" ".gql") :graphql
        ".groovy" :groovy
        (".ico" ".jpeg" ".jpg" ".png" ".webp") :image-asset
        ".ipynb" :notebook
        ".java" :java
        (".js" ".jsx" ".mjs" ".cjs") :javascript
        (".kt" ".kts") :kotlin
        (".m" ".mm") :objective-c
        ".swift" :swift
        (".ts" ".tsx") :typescript
        ".proto" :protobuf
        ".prisma" :prisma
        ".py" :python
        ".rs" :rust
        ".svelte" :svelte
        (".css" ".scss") :style
        (".po" ".pot") :gettext
        ".php" :php
        (".rake" ".rb") :ruby
        ".dart" :dart
        ".scala" :scala
        ".sbt" :manifest
        (".ex" ".exs") :elixir
        (".erl" ".hrl") :erlang
        ".lua" :lua
        (".r" ".R") :r
        ".jl" :julia
        (".ml" ".mli") :ocaml
        (".pl" ".pm") :perl
        ".hs" :haskell
        ".odin" :odin
        ".zig" :zig
        (".ttf" ".woff" ".woff2") :font-asset
        ".mo" :gettext-binary
        ".sh" :shell
        ".svg" :svg
        ".html" :html
        ".sql" :sql
        (".service" ".socket" ".timer") :ops-config
        (".tf" ".tfvars" ".hcl") :terraform
        ".edn" :edn
        ".toml" :config
        ".nix" :config
        ".xcconfig" :apple-config
        (".cabal" ".csproj" ".entitlements" ".fsproj" ".gemspec" ".pbxproj" ".plist" ".props" ".sln" ".targets" ".vbproj") :manifest
        (".yaml" ".yml") :yaml
        ".json" :config
        ".jsonc" :config
        ".xml" :xml
        ".gradle" :config
        ".license" :doc
        ".template" :text
        (".adoc" ".asciidoc" ".md" ".mdx" ".rst") :doc
        (".out" ".txt") :text
        ".vue" :vue
        nil))))

(defn ignored-filename?
  "Return true when a file name is intentionally excluded from indexing."
  [path]
  (contains? ignored-filenames
             (str/lower-case (.getName (io/file path)))))

(defn supported-path?
  "Return true when path has a supported extension or exact supported filename."
  [path]
  (let [filename (str/lower-case (.getName (io/file path)))
        path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (or (contains? supported-extensions (extension path))
        (env-filename? filename)
        (str/ends-with? filename ".sh.in")
        (re-find #"(^|/)\.github/workflows/[^/]+\.ya?ml$" path-lower)
        (re-find #"(^|/)\.github/issue_template/[^/]+\.(?:md|ya?ml|json)$" path-lower)
        (re-find #"(^|/)\.github/pull_request_template(?:/[^/]+\.md|\.md)$" path-lower)
        (re-find #"(^|/)\.github/funding\.ya?ml$" path-lower)
        (re-find #"(^|/)\.github/dependabot\.ya?ml$" path-lower)
        (re-find #"(^|/)\.storybook/main\.(?:js|cjs|mjs|ts)$" path-lower)
        (re-find #"(^|/)\.storybook/(?:preview|manager)\.(?:js|cjs|mjs|ts|tsx)$"
                 path-lower)
        (re-find #"(^|/)\.circleci/config\.ya?ml$" path-lower)
        (re-find #"(^|/)\.buildkite/pipeline\.ya?ml$" path-lower)
        (contains? supported-filenames filename))))

(defn relative-path
  "Return slash-normalized relative path from root to file."
  [root file]
  (let [root-path (.toPath (io/file (canonical-path root)))
        file-path (.toPath (io/file (canonical-path file)))]
    (str/replace (str (.relativize root-path file-path)) "\\" "/")))

(defn read-file
  "Read file as UTF-8-ish text."
  [file]
  (slurp (io/file file)))

(defn- shebang-kind
  [file]
  (when (str/blank? (extension file))
    (try
      (with-open [reader (io/reader file)]
        (let [line (.readLine reader)]
          (when (and line (str/starts-with? line "#!"))
            :shell)))
      (catch Exception _ nil))))

(defn- text-file-prefix
  [file]
  (try
    (with-open [reader (io/reader file)]
      (let [buffer (char-array 8192)
            n (.read reader buffer)]
        (if (pos? n)
          (String. buffer 0 n)
          "")))
    (catch Exception _
      "")))

(defn- ops-config-content-kind
  [path-kind file]
  (when (contains? #{:config :yaml} path-kind)
    (let [content (text-file-prefix file)]
      (cond
        (or (str/includes? content "AWSTemplateFormatVersion")
            (str/includes? content "AWS::Serverless")
            (re-find #"(?m)^Transform:\s*['\"]?AWS::Serverless" content)
            (and (re-find #"(?m)^Resources:\s*$" content)
                 (str/includes? content "AWS::"))
            (and (str/includes? content "\"Resources\"")
                 (str/includes? content "AWS::")))
        :ops-config

        (and (or (re-find #"(?m)^\s*-\s*hosts:\s*.+" content)
                 (re-find #"(?m)^\s*hosts:\s*.+" content))
             (re-find #"(?m)^\s*tasks:\s*$" content))
        :ops-config

        :else nil))))

(defn- dbt-content-kind
  [path-kind file]
  (when (contains? #{:yaml :config} path-kind)
    (let [filename (str/lower-case (.getName (io/file file)))
          content (text-file-prefix file)]
      (cond
        (and (contains? #{"packages.yml" "packages.yaml"} filename)
             (re-find #"(?m)^packages:\s*$" content))
        :dbt

        (and (contains? #{"profiles.yml" "profiles.yaml"} filename)
             (re-find #"(?m)^\s*outputs:\s*$" content)
             (re-find #"(?m)^\s*target:\s*.+" content))
        :dbt

        (and (re-find #"(?m)^version:\s*2\s*$" content)
             (re-find #"(?m)^(?:models|sources|exposures|metrics|semantic_models|tests):\s*$"
                      content))
        :dbt

        :else nil))))

(defn- helm-template-content-kind
  [path-kind file]
  (when (= :yaml path-kind)
    (let [content (text-file-prefix file)]
      (when (and (str/includes? content "{{")
                 (str/includes? content "}}"))
        :helm))))

(defn- file-kind-for-file
  [file]
  (let [path-kind (file-kind file)]
    (or (dbt-content-kind path-kind file)
        (helm-template-content-kind path-kind file)
        (ops-config-content-kind path-kind file)
        path-kind
        (shebang-kind file)
        :unknown)))

(defn- binary-file?
  [kind]
  (contains? binary-file-kinds kind))

(defn- content-sha
  [file kind content]
  (str "sha256:"
       (if (binary-file? kind)
         (hash/sha256-bytes-hex (Files/readAllBytes (.toPath (io/file file))))
         (hash/sha256-hex content))))

(defn file-record
  "Build file metadata for supported file."
  [root file]
  (let [rel (relative-path root file)
        kind (file-kind-for-file file)
        text (if (binary-file? kind) "" (read-file file))
        f (io/file file)]
    (cond-> {:file-id (str "file:" rel)
             :path rel
             :absolute-path (canonical-path file)
             :ext (extension file)
             :kind kind
             :content text
             :content-sha (content-sha file kind text)
             :mtime-ms (.lastModified f)
             :size-bytes (.length f)
             :active? true}
      (binary-file? kind) (assoc :binary? true))))

(defn- file-record-for-relative-path
  "Build file metadata when the repo-relative path is already known."
  [root rel]
  (let [file (io/file root rel)
        kind (file-kind-for-file file)
        text (if (binary-file? kind) "" (read-file file))
        f (io/file file)]
    (cond-> {:file-id (str "file:" rel)
             :path rel
             :absolute-path (.getPath (.getAbsoluteFile file))
             :ext (extension file)
             :kind kind
             :content text
             :content-sha (content-sha file kind text)
             :mtime-ms (.lastModified f)
             :size-bytes (.length f)
             :active? true}
      (binary-file? kind) (assoc :binary? true))))

(defn- supported-file?
  [file]
  (and (.isFile file)
       (not (ignored-filename? file))
       (or (supported-path? file)
           (shebang-kind file))))

(defn- allowed-hidden-supported-path?
  [rel-path]
  (let [path-lower (str/replace (str/lower-case (str rel-path)) "\\" "/")
        parts (str/split path-lower #"/")
        hidden-parts (filter #(str/starts-with? % ".") parts)]
    (or (and (= 1 (count parts))
             (supported-path? path-lower))
        (and (= [(last parts)] (vec hidden-parts))
             (env-filename? (last parts)))
        (re-find #"^\.devcontainer/devcontainer\.json$" path-lower)
        (re-find #"^\.github/workflows/[^/]+\.ya?ml$" path-lower)
        (re-find #"^\.github/issue_template/[^/]+\.(?:md|ya?ml|json)$" path-lower)
        (re-find #"^\.github/pull_request_template(?:/[^/]+\.md|\.md)$" path-lower)
        (re-find #"^\.github/funding\.ya?ml$" path-lower)
        (re-find #"^\.github/dependabot\.ya?ml$" path-lower)
        (re-find #"^\.github/codeowners$" path-lower)
        (re-find #"^\.storybook/main\.(?:js|cjs|mjs|ts)$" path-lower)
        (re-find #"^\.storybook/(?:preview|manager)\.(?:js|cjs|mjs|ts|tsx)$"
                 path-lower)
        (re-find #"^\.circleci/config\.ya?ml$" path-lower)
        (re-find #"^\.buildkite/pipeline\.ya?ml$" path-lower))))

(defn- ignored-path?
  [rel-path]
  (let [parts (str/split rel-path #"/")]
    (or (some ignored-dirs parts)
        (and (some #(str/starts-with? % ".") parts)
             (not (allowed-hidden-supported-path? rel-path))))))

(defn- git-candidate-paths
  [root]
  (let [{:keys [exit out]} (shell/sh "git"
                                     "-C"
                                     (.getPath root)
                                     "ls-files"
                                     "--cached"
                                     "--others"
                                     "--exclude-standard")]
    (when (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           (remove ignored-path?)
           (filter #(.isFile (io/file root %)))
           vec))))

(defn- filesystem-candidate-paths
  [root]
  (->> (file-seq root)
       (filter #(.isFile %))
       (keep (fn [file]
               (let [rel (relative-path root file)]
                 (when-not (ignored-path? rel)
                   rel))))
       vec))

(defn- candidate-paths
  [root]
  (or (seq (git-candidate-paths root))
      (filesystem-candidate-paths root)))

(defn- file-coverage-row
  [root rel]
  (let [file (io/file root rel)
        ignored? (ignored-filename? file)
        supported? (and (not ignored?)
                        (or (supported-path? file)
                            (shebang-kind file)))
        kind (when supported? (file-kind-for-file file))]
    (cond-> {:path rel
             :ext (extension rel)
             :filename (str/lower-case (.getName file))
             :supported? supported?
             :size-bytes (.length file)}
      supported? (assoc :kind kind)
      ignored? (assoc :skip-reason :ignored-filename)
      (and (not ignored?) (not supported?)) (assoc :skip-reason :unsupported-extension))))

(defn scan-file-coverage
  "Return support coverage rows for non-ignored files under root.

  Rows do not include file contents. Supported rows use the same path support
  rules as `scan-files`; skipped rows explain unsupported or intentionally
  ignored filenames."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    {:root (.getPath root-file)
     :files (->> (candidate-paths root-file)
                 (mapv #(file-coverage-row root-file %))
                 (sort-by :path)
                 vec)}))

(defn scan-files
  "Return supported files under root with content metadata."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    (->> (candidate-paths root-file)
         (filter #(supported-file? (io/file root-file %)))
         (map #(file-record-for-relative-path root-file %))
         (sort-by :path)
         vec)))
