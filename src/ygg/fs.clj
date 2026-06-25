(ns ygg.fs
  "Repository file discovery and file metadata."
  (:require [ygg.hash :as hash]
            [ygg.ripgrep :as ripgrep]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file FileSystems Files Paths]))

(def supported-extensions
  #{".adoc" ".asciidoc" ".astro" ".avdl" ".avsc" ".bzl" ".c" ".cc" ".cjs" ".class" ".clj" ".cljc" ".cljs" ".cmake" ".cpp" ".cs" ".cts"
    ".bmp" ".code-workspace" ".conf" ".crt" ".cer" ".cert" ".csproj" ".css" ".cxx" ".dart" ".edn" ".entitlements" ".fs" ".fsi" ".fsx"
    ".dvc"
    ".cabal" ".ex" ".exs" ".erl" ".fsproj" ".gemspec" ".gql" ".go" ".gradle" ".graphql" ".groovy" ".h" ".hcl" ".hs" ".ini"
    ".gif" ".gz" ".hh" ".hpp" ".html" ".hxx" ".ico" ".ipynb" ".jar" ".java" ".jpeg" ".jpg" ".js" ".json" ".jsonc" ".jsx"
    ".hrl" ".jl" ".key" ".lua" ".map"
    ".kt" ".kts" ".m" ".md" ".mdx" ".mjs" ".ml" ".mli" ".mm" ".mo" ".mts" ".pm" ".pl" ".png" ".po" ".pbxproj" ".plist" ".pot" ".php"
    ".mp4" ".mustache" ".neon" ".nix" ".njk" ".odin" ".otf" ".out" ".patch" ".pem" ".penpot" ".ppm" ".prisma" ".properties" ".props" ".proto" ".py" ".r" ".R" ".rake" ".rb" ".rs" ".rst" ".sbt" ".scala" ".scss" ".sh"
    ".service" ".sln" ".snap" ".socket" ".sql" ".subj" ".svelte" ".swift" ".svg" ".targets" ".tf" ".tfvars" ".timer" ".tmpl" ".ttf"
    ".license" ".template" ".toml" ".ts" ".tsx" ".txt" ".types" ".vb" ".vbproj" ".vue" ".xcconfig"
    ".webm" ".webp" ".woff" ".woff2" ".yaml" ".yml" ".zig" ".xml"})

(def binary-file-kinds
  #{:archive-asset :compiled-artifact :font-asset :gettext-binary :image-asset :media-asset :opaque-asset :secret-material})

(def unknown-text-fallback-blocked-extensions
  #{".bin" ".class" ".dll" ".dylib" ".ear" ".exe" ".jar" ".o" ".pdf" ".so" ".war" ".wasm" ".zip"})

(def docs-config-filenames
  #{"content.config.js" "content.config.mjs" "content.config.mts"
    "content.config.cts" "content.config.ts"
    "_meta.js" "_meta.jsx" "_meta.mjs" "_meta.mts" "_meta.cts"
    "_meta.ts" "_meta.tsx"
    "docusaurus.config.js" "docusaurus.config.cjs" "docusaurus.config.mjs"
    "docusaurus.config.mts" "docusaurus.config.cts" "docusaurus.config.ts"
    "sidebars.js" "sidebars.mjs" "sidebars.mts" "sidebars.cts"
    "sidebars.ts" "mkdocs.yml" "mkdocs.yaml"})

(def nextra-meta-filenames
  #{"_meta.js" "_meta.jsx" "_meta.mjs" "_meta.mts" "_meta.cts"
    "_meta.ts" "_meta.tsx"})

(defn- astro-content-config-path?
  [path-lower]
  (boolean
   (re-find #"(^|/)src/content/config\.(?:js|mjs|mts|cts|ts)$" path-lower)))

(defn- vitepress-config-path?
  [path-lower]
  (boolean
   (or (re-find #"(^|/)\.vitepress/config\.(?:js|mjs|mts|cts|ts)$" path-lower)
       (re-find #"(^|/)\.vitepress/config/index\.(?:js|mjs|mts|cts|ts)$"
                path-lower))))

(defn- docs-config-path?
  [filename path-lower]
  (or (contains? docs-config-filenames filename)
      (astro-content-config-path? path-lower)
      (vitepress-config-path? path-lower)))

(def supported-filenames
  #{"dockerfile" "containerfile" "procfile"
    "caddyfile" "sudoers" "vimrc" "apt.sources"
    "docker-compose.yml" "docker-compose.yaml"
    "compose.yml" "compose.yaml" "chart.yaml" "go.mod" "mix.exs"
    "package.json" "package-lock.json" "cargo.toml" "cargo.lock" "go.work" "go.sum"
    "pnpm-lock.yaml" "yarn.lock" "gemfile.lock" "poetry.lock"
    "pubspec.lock" "composer.lock" "pipfile.lock" "uv.lock"
    "mix.lock" "requirements.txt" "bun.lock" "flake.lock" "pixi.lock"
    "pipfile" "setup.cfg" "setup.py"
    "pyproject.toml" "pom.xml" "deno.json" "composer.json" "ols.json"
    "gemfile" "rakefile" "pubspec.yaml" "build.sbt" "rebar.config"
    "description" "namespace" "project.toml" "cpanfile" "stack.yaml"
    "build.gradle" "build.gradle.kts" "settings.gradle" "settings.gradle.kts"
    "gradle.properties" "libs.versions.toml" "global.json" "directory.build.props"
    "directory.build.targets" "nuget.config" "packages.config"
    "androidmanifest.xml" "info.plist" "podfile" "package.swift"
    "app.json" "app.config.json" "eas.json"
    "capacitor.config.json" "capacitor.config.ts" "capacitor.config.js"
    "capacitor.config.mjs" "capacitor.config.cjs"
    "tauri.conf.json" "tauri.conf.json5"
    ".yarnrc.yml" ".yarnrc.yaml"
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
    "changelog.md" "changes.md" "release-please-config.json"
    ".release-please-manifest.json" ".releaserc" ".releaserc.json"
    ".releaserc.yaml" ".releaserc.yml" "semantic-release.config.js"
    "semantic-release.config.cjs" "semantic-release.config.mjs"
    "semantic-release.config.ts" "standard-version.json" ".versionrc"
    ".versionrc.json" ".versionrc.yaml" ".versionrc.yml"
    "next.config.js" "next.config.cjs" "next.config.mjs" "next.config.mts" "next.config.cts" "next.config.ts"
    "remix.config.js" "remix.config.cjs" "remix.config.mjs" "remix.config.mts" "remix.config.cts" "remix.config.ts"
    "vite.config.js" "vite.config.cjs" "vite.config.mjs" "vite.config.mts" "vite.config.cts" "vite.config.ts"
    "svelte.config.js" "svelte.config.cjs" "svelte.config.mjs" "svelte.config.mts" "svelte.config.cts" "svelte.config.ts"
    "nuxt.config.js" "nuxt.config.cjs" "nuxt.config.mjs" "nuxt.config.mts" "nuxt.config.cts" "nuxt.config.ts"
    "astro.config.js" "astro.config.cjs" "astro.config.mjs" "astro.config.mts" "astro.config.cts" "astro.config.ts"
    "angular.json"
    "dagster.yaml" "dagster.yml" "prefect.yaml" "prefect.yml"
    "dvc.yaml" "dvc.yml" "dvc.lock" "mlproject"
    "_meta.js" "_meta.jsx" "_meta.mjs" "_meta.mts" "_meta.cts" "_meta.ts" "_meta.tsx"
    "content.config.js" "content.config.mjs" "content.config.mts" "content.config.cts" "content.config.ts"
    "docusaurus.config.js" "docusaurus.config.cjs" "docusaurus.config.mjs"
    "docusaurus.config.mts" "docusaurus.config.cts" "docusaurus.config.ts"
    "sidebars.js" "sidebars.mjs" "sidebars.mts" "sidebars.cts" "sidebars.ts"
    "mkdocs.yml" "mkdocs.yaml"
    "otelcol.yaml" "otelcol.yml" "otel-collector.yaml" "otel-collector.yml"
    "prometheus.yml" "prometheus.yaml" "alertmanager.yml" "alertmanager.yaml"
    "vector.yaml" "vector.yml" "vector.toml"
    "nginx.conf" "pulumi.yaml"
    ".graphqlrc" ".graphqlrc.json" ".graphqlrc.yaml" ".graphqlrc.yml"
    "graphql.config.json" "graphql.config.yaml" "graphql.config.yml"
    "codegen.json" "codegen.yaml" "codegen.yml" "graphql-codegen.json"
    "graphql-codegen.yaml" "graphql-codegen.yml"
    "jest.config.js" "jest.config.cjs" "jest.config.mjs" "jest.config.mts" "jest.config.cts" "jest.config.ts" "jest.config.json"
    "vitest.config.js" "vitest.config.cjs" "vitest.config.mjs" "vitest.config.mts" "vitest.config.cts" "vitest.config.ts"
    "playwright.config.js" "playwright.config.cjs" "playwright.config.mjs" "playwright.config.mts" "playwright.config.cts" "playwright.config.ts"
    "cypress.config.js" "cypress.config.cjs" "cypress.config.mjs" "cypress.config.mts" "cypress.config.cts" "cypress.config.ts"
    "karma.conf.js" "pytest.ini" "tox.ini" "noxfile.py" "ava.config.js" "ava.config.cjs" "ava.config.mjs" "ava.config.mts" "ava.config.cts"
    ".coveragerc" "coverage.toml" "mypy.ini" ".mypy.ini" "ruff.toml" ".ruff.toml"
    "sonar-project.properties" "checkstyle.xml" "pmd.xml" "spotbugs-exclude.xml"
    "phpstan.neon" "phpstan.neon.dist" "psalm.xml" ".rubocop.yml" ".rubocop.yaml"
    ".swiftlint.yml" ".swiftlint.yaml" "detekt.yml" "detekt.yaml"
    "eslint.config.js" "eslint.config.cjs" "eslint.config.mjs" "eslint.config.mts" "eslint.config.cts" "eslint.config.ts"
    ".eslintrc" ".eslintrc.js" ".eslintrc.cjs" ".eslintrc.json" ".eslintrc.yaml" ".eslintrc.yml"
    "prettier.config.js" "prettier.config.cjs" "prettier.config.mjs" "prettier.config.mts" "prettier.config.cts" "prettier.config.ts"
    ".prettierrc" ".prettierrc.js" ".prettierrc.cjs" ".prettierrc.json" ".prettierrc.yaml" ".prettierrc.yml"
    "stylelint.config.js" "stylelint.config.cjs" "stylelint.config.mjs" "stylelint.config.mts" "stylelint.config.cts"
    ".stylelintrc" ".stylelintrc.js" ".stylelintrc.json" ".stylelintrc.yaml" ".stylelintrc.yml"
    "postcss.config.js" "postcss.config.cjs" "postcss.config.mjs" "postcss.config.mts" "postcss.config.cts"
    "tsconfig.json" "jsconfig.json"
    "webpack.config.js" "webpack.config.cjs" "webpack.config.mjs" "webpack.config.mts" "webpack.config.cts" "webpack.config.ts"
    "rollup.config.js" "rollup.config.cjs" "rollup.config.mjs" "rollup.config.mts" "rollup.config.cts" "rollup.config.ts"
    "rspack.config.js" "rspack.config.cjs" "rspack.config.mjs" "rspack.config.mts" "rspack.config.cts" "rspack.config.ts"
    "babel.config.js" "babel.config.cjs" "babel.config.mjs" "babel.config.json"
    ".babelrc" ".babelrc.js" ".babelrc.cjs" ".babelrc.json"
    "tailwind.config.js" "tailwind.config.cjs" "tailwind.config.mjs" "tailwind.config.mts" "tailwind.config.cts" "tailwind.config.ts"
    "commitlint.config.js" "commitlint.config.cjs" "commitlint.config.mjs" "commitlint.config.mts" "commitlint.config.cts" "commitlint.config.ts"
    "lint-staged.config.js" "lint-staged.config.cjs" "lint-staged.config.mjs" "lint-staged.config.mts" "lint-staged.config.cts" "lint-staged.config.ts"
    ".lintstagedrc" ".lintstagedrc.json" ".lintstagedrc.yaml" ".lintstagedrc.yml"
    ".editorconfig" "browserslist" ".browserslistrc" "renovate.json" ".renovaterc" ".renovaterc.json"
    "biome.json" "pyrightconfig.json"
    "makefile" "gnumakefile" "cmakelists.txt" "build" "build.bazel"
    "workspace" "module.bazel" "pants.toml" "jenkinsfile"
    "azure-pipelines.yml" "azure-pipelines.yaml" ".gitlab-ci.yml"
    ".drone.yml" ".drone.yaml" ".woodpecker.yml" ".woodpecker.yaml"
    "buck"
    ".gitlab-ci.yaml" "buildkite.yml" "buildkite.yaml"
    "openapi.json" "openapi.yaml" "openapi.yml" "swagger.json"
    "asyncapi.json" "asyncapi.yaml" "asyncapi.yml"
    "swagger.yaml" "swagger.yml"})

(def ignored-dirs
  #{".git" ".dev" ".ygg" ".cpcache" ".clj-kondo" "target" "node_modules"
    ".shadow-cljs" ".calva" ".idea" "ygg-out"})

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

(defn- docker-build-filename?
  [filename]
  (or (contains? #{"dockerfile" "containerfile"} filename)
      (str/starts-with? filename "dockerfile.")
      (str/starts-with? filename "containerfile.")))

(defn file-kind
  "Return Yggdrasil file kind for extension."
  [path]
  (let [filename (str/lower-case (.getName (io/file path)))
        path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (contains? #{"license" "copying" "notice"} filename) :governance
      (or (contains? #{"security.md" "contributing.md"} filename)
          (re-find #"(^|/)\.github/issue_template/[^/]+\.(?:md|ya?ml|json)$" path-lower)
          (re-find #"(^|/)\.github/pull_request_template(?:/[^/]+\.md|\.md)$" path-lower)
          (re-find #"(^|/)\.github/funding\.ya?ml$" path-lower)) :governance
      (env-filename? filename) :env
      (str/ends-with? filename ".sh.in") :shell
      (str/ends-with? filename ".rb.template") :ruby
      (docker-build-filename? filename) :docker
      (= "procfile" filename) :procfile
      (= "ols.json" filename) :odin
      (or (re-find #"(^|/)\.github/workflows/[^/]+\.ya?ml$" path-lower)
          (= "jenkinsfile" filename)
          (contains? #{"azure-pipelines.yml" "azure-pipelines.yaml"} filename)
          (= ".gitlab-ci.yml" filename)
          (= ".gitlab-ci.yaml" filename)
          (= ".drone.yml" filename)
          (= ".drone.yaml" filename)
          (= ".woodpecker.yml" filename)
          (= ".woodpecker.yaml" filename)
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
      (or (= ".editorconfig" filename)
          (= "vimrc" filename)
          (str/ends-with? filename ".code-workspace")
          (re-find #"(^|/)\.vscode/(?:settings|tasks|extensions)\.json$"
                   path-lower)) :editor-config
      (contains? #{"drizzle.config.ts" "drizzle.config.js" "drizzle.config.mjs"
                   "drizzle.config.cjs" "drizzle.config.json"
                   "liquibase.properties" "flyway.conf"}
                 filename) :db-config
      (or (contains? #{"changelog.md" "changes.md" "release-please-config.json"
                       ".release-please-manifest.json" ".releaserc"
                       ".releaserc.json" ".releaserc.yaml" ".releaserc.yml"
                       "semantic-release.config.js" "semantic-release.config.cjs"
                       "semantic-release.config.mjs" "semantic-release.config.ts"
                       "standard-version.json" ".versionrc" ".versionrc.json"
                       ".versionrc.yaml" ".versionrc.yml"}
                     filename)
          (re-find #"(^|/)\.changeset/(?:config\.json|[^/]+\.md)$"
                   path-lower)) :release-config
      (contains? #{"next.config.js" "next.config.cjs" "next.config.mjs" "next.config.mts" "next.config.cts" "next.config.ts"
                   "remix.config.js" "remix.config.cjs" "remix.config.mjs" "remix.config.mts" "remix.config.cts" "remix.config.ts"
                   "vite.config.js" "vite.config.cjs" "vite.config.mjs" "vite.config.mts" "vite.config.cts" "vite.config.ts"
                   "svelte.config.js" "svelte.config.cjs" "svelte.config.mjs" "svelte.config.mts" "svelte.config.cts" "svelte.config.ts"
                   "nuxt.config.js" "nuxt.config.cjs" "nuxt.config.mjs" "nuxt.config.mts" "nuxt.config.cts" "nuxt.config.ts"
                   "astro.config.js" "astro.config.cjs" "astro.config.mjs" "astro.config.mts" "astro.config.cts" "astro.config.ts"
                   "angular.json"}
                 filename) :web-framework
      (contains? #{"dagster.yaml" "dagster.yml" "prefect.yaml" "prefect.yml"}
                 filename) :workflow-orchestration
      (or (contains? #{"dvc.yaml" "dvc.yml" "dvc.lock" "mlproject"} filename)
          (= ".dvc" (extension path))) :data-science
      (contains? #{"dbt_project.yml" "dbt_project.yaml"} filename) :dbt
      (contains? #{"otelcol.yaml" "otelcol.yml" "otel-collector.yaml" "otel-collector.yml"
                   "prometheus.yml" "prometheus.yaml" "alertmanager.yml" "alertmanager.yaml"
                   "vector.yaml" "vector.yml" "vector.toml"}
                 filename) :observability-config
      (or (= "nginx.conf" filename)
          (contains? #{"caddyfile" "sudoers" "apt.sources"} filename)
          (contains? #{"serverless.yml" "serverless.yaml" "cdk.json"} filename)
          (re-matches #"pulumi(?:\.[a-z0-9_.-]+)?\.ya?ml" filename)) :ops-config
      (contains? #{".graphqlrc" ".graphqlrc.json" ".graphqlrc.yaml" ".graphqlrc.yml"
                   "graphql.config.json" "graphql.config.yaml" "graphql.config.yml"
                   "codegen.json" "codegen.yaml" "codegen.yml"
                   "graphql-codegen.json" "graphql-codegen.yaml" "graphql-codegen.yml"}
                 filename) :codegen-config
      (contains? #{"jest.config.js" "jest.config.cjs" "jest.config.mjs" "jest.config.mts" "jest.config.cts" "jest.config.ts" "jest.config.json"
                   "vitest.config.js" "vitest.config.cjs" "vitest.config.mjs" "vitest.config.mts" "vitest.config.cts" "vitest.config.ts"
                   "playwright.config.js" "playwright.config.cjs" "playwright.config.mjs" "playwright.config.mts" "playwright.config.cts" "playwright.config.ts"
                   "cypress.config.js" "cypress.config.cjs" "cypress.config.mjs" "cypress.config.mts" "cypress.config.cts" "cypress.config.ts"
                   "karma.conf.js" "pytest.ini" "tox.ini" "noxfile.py"
                   "ava.config.js" "ava.config.cjs" "ava.config.mjs" "ava.config.mts" "ava.config.cts"}
                 filename) :test-config
      (contains? #{".coveragerc" "coverage.toml" "mypy.ini" ".mypy.ini" "ruff.toml" ".ruff.toml"
                   "sonar-project.properties" "checkstyle.xml" "pmd.xml" "spotbugs-exclude.xml"
                   "phpstan.neon" "phpstan.neon.dist" "psalm.xml" ".rubocop.yml" ".rubocop.yaml"
                   ".swiftlint.yml" ".swiftlint.yaml" "detekt.yml" "detekt.yaml"}
                 filename) :quality-config
      (contains? #{"eslint.config.js" "eslint.config.cjs" "eslint.config.mjs" "eslint.config.mts" "eslint.config.cts" "eslint.config.ts"
                   ".eslintrc" ".eslintrc.js" ".eslintrc.cjs" ".eslintrc.json" ".eslintrc.yaml" ".eslintrc.yml"
                   "prettier.config.js" "prettier.config.cjs" "prettier.config.mjs" "prettier.config.mts" "prettier.config.cts" "prettier.config.ts"
                   ".prettierrc" ".prettierrc.js" ".prettierrc.cjs" ".prettierrc.json" ".prettierrc.yaml" ".prettierrc.yml"
                   "stylelint.config.js" "stylelint.config.cjs" "stylelint.config.mjs" "stylelint.config.mts" "stylelint.config.cts"
                   ".stylelintrc" ".stylelintrc.js" ".stylelintrc.json" ".stylelintrc.yaml" ".stylelintrc.yml"
                   "postcss.config.js" "postcss.config.cjs" "postcss.config.mjs" "postcss.config.mts" "postcss.config.cts"
                   "tsconfig.json" "jsconfig.json"
                   "webpack.config.js" "webpack.config.cjs" "webpack.config.mjs" "webpack.config.mts" "webpack.config.cts" "webpack.config.ts"
                   "rollup.config.js" "rollup.config.cjs" "rollup.config.mjs" "rollup.config.mts" "rollup.config.cts" "rollup.config.ts"
                   "rspack.config.js" "rspack.config.cjs" "rspack.config.mjs" "rspack.config.mts" "rspack.config.cts" "rspack.config.ts"
                   "babel.config.js" "babel.config.cjs" "babel.config.mjs" "babel.config.json"
                   ".babelrc" ".babelrc.js" ".babelrc.cjs" ".babelrc.json"
                   "tailwind.config.js" "tailwind.config.cjs" "tailwind.config.mjs" "tailwind.config.mts" "tailwind.config.cts" "tailwind.config.ts"
                   "commitlint.config.js" "commitlint.config.cjs" "commitlint.config.mjs" "commitlint.config.mts" "commitlint.config.cts" "commitlint.config.ts"
                   "lint-staged.config.js" "lint-staged.config.cjs" "lint-staged.config.mjs" "lint-staged.config.mts" "lint-staged.config.cts" "lint-staged.config.ts"
                   ".lintstagedrc" ".lintstagedrc.json" ".lintstagedrc.yaml" ".lintstagedrc.yml"
                   "browserslist" ".browserslistrc"
                   "renovate.json" ".renovaterc" ".renovaterc.json"
                   "biome.json" "pyrightconfig.json"}
                 filename) :tool-config
      (or (re-matches #"tsconfig\.[a-z0-9_.-]+\.json" filename)
          (re-find #"(^|/)\.github/dependabot\.ya?ml$" path-lower))
      :tool-config
      (docs-config-path? filename path-lower)
      :docs-config
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
                   "settings.gradle.kts" "gradle.properties" "libs.versions.toml" "global.json"
                   "directory.build.props" "directory.build.targets"
                   "nuget.config" "packages.config" "androidmanifest.xml"
                   "info.plist" "podfile" "package.swift" "app.json"
                   "app.config.json" "eas.json"
                   "capacitor.config.json" "capacitor.config.ts"
                   "capacitor.config.js" "capacitor.config.mjs"
                   "capacitor.config.cjs" "tauri.conf.json"
                   "tauri.conf.json5" ".yarnrc.yml" ".yarnrc.yaml"
                   "pnpm-workspace.yaml"
                   "pnpm-workspace.yml" "turbo.json" "nx.json"
                   "workspace.json" "lerna.json" "rush.json"}
                 filename) :manifest
      (contains? #{"package-lock.json" "cargo.lock" "go.sum"
                   "pnpm-lock.yaml" "yarn.lock" "gemfile.lock"
                   "poetry.lock" "pubspec.lock" "composer.lock"
                   "pipfile.lock" "uv.lock" "mix.lock" "requirements.txt"
                   "bun.lock" "flake.lock" "pixi.lock"}
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
        (".bmp" ".gif" ".ico" ".jpeg" ".jpg" ".png" ".ppm" ".webp") :image-asset
        (".mp4" ".webm") :media-asset
        (".gz" ".jar") :archive-asset
        ".class" :compiled-artifact
        ".penpot" :opaque-asset
        ".ipynb" :notebook
        ".java" :java
        (".js" ".jsx" ".mjs" ".cjs") :javascript
        ".map" :source-map
        (".kt" ".kts") :kotlin
        (".m" ".mm") :objective-c
        ".swift" :swift
        (".ts" ".tsx" ".mts" ".cts") :typescript
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
        (".otf" ".ttf" ".woff" ".woff2") :font-asset
        ".mo" :gettext-binary
        (".cer" ".cert" ".crt" ".key" ".pem") :secret-material
        ".sh" :shell
        ".svg" :svg
        ".html" :html
        ".sql" :sql
        (".service" ".socket" ".timer") :ops-config
        (".tf" ".tfvars" ".hcl") :terraform
        ".edn" :edn
        ".toml" :config
        ".nix" :config
        ".conf" :config
        ".properties" :config
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
        (".mustache" ".njk" ".out" ".patch" ".snap" ".subj" ".tmpl" ".txt" ".types") :text
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
        (docker-build-filename? filename)
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
        (re-find #"(^|/)\.vscode/(?:settings|tasks|extensions)\.json$" path-lower)
        (re-find #"(^|/)\.changeset/(?:config\.json|[^/]+\.md)$" path-lower)
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

(defn path-glob-matches?
  "Return true when repo-relative `path` matches a Java NIO glob pattern."
  [glob path]
  (boolean
   (try
     (let [matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob))]
       (.matches matcher (Paths/get (str path) (make-array String 0))))
     (catch Exception _
       false))))

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

(defn- prefix-bytes
  [file]
  (try
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)
            n (.read stream buffer)]
        (cond
          (neg? n) (byte-array 0)
          (= n (alength buffer)) buffer
          :else (java.util.Arrays/copyOf buffer n))))
    (catch Exception _
      nil)))

(defn- text-byte?
  [b]
  (let [value (bit-and b 0xff)]
    (or (>= value 32)
        (contains? #{9 10 12 13} value))))

(defn- utf8-text-bytes?
  [bytes]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (.decode decoder (ByteBuffer/wrap bytes))
      true)
    (catch Exception _
      false)))

(defn- unknown-text-fallback-kind
  [file]
  (when-not (contains? unknown-text-fallback-blocked-extensions (extension file))
    (let [bytes (prefix-bytes file)]
      (when (and bytes
                 (every? text-byte? bytes)
                 (utf8-text-bytes? bytes))
        :unknown))))

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

(defn- sphinx-content-kind
  [path-kind file]
  (when (and (= :python path-kind)
             (= "conf.py" (str/lower-case (.getName (io/file file)))))
    (let [content (text-file-prefix file)]
      (when (and (re-find #"(?m)^\s*(?:project|extensions|html_theme|master_doc|root_doc)\s*="
                          content)
                 (or (str/includes? content "sphinx")
                     (re-find #"(?m)^\s*extensions\s*=" content)
                     (re-find #"(?m)^\s*html_theme\s*=" content)))
        :docs-config))))

(defn- nextra-content-kind
  [path-kind file]
  (let [filename (str/lower-case (.getName (io/file file)))]
    (cond
      (contains? nextra-meta-filenames filename)
      :docs-config

      (and (contains? #{:javascript :typescript :web-framework} path-kind)
           (re-matches #"next\.config\.(?:cjs|js|mjs|mts|cts|ts)" filename))
      (let [content (text-file-prefix file)]
        (when (or (re-find #"(?m)\bfrom\s+['\"]nextra['\"]" content)
                  (re-find #"(?m)\brequire\(['\"]nextra['\"]\)" content)
                  (re-find #"(?m)\bnextra\s*\(" content))
          :docs-config))

      :else nil)))

(defn- web-framework-route-path?
  [path-kind file]
  (let [path-lower (str/replace (str/lower-case (str file)) "\\" "/")]
    (boolean
     (or (and (contains? #{:javascript :typescript} path-kind)
              (or (re-find #"(^|/)app/(?:.+/)?(?:page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                           path-lower)
                  (re-find #"(^|/)pages/(?:.+\.)?(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                           path-lower)
                  (re-find #"(^|/)app/routes/.+\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                           path-lower)))
         (and (= :svelte path-kind)
              (re-find #"(^|/)src/routes/(?:.+/)?\+(?:page|layout|server)\.svelte$"
                       path-lower))
         (and (= :vue path-kind)
              (re-find #"(^|/)pages/.+\.vue$" path-lower))
         (and (= :astro path-kind)
              (re-find #"(^|/)src/pages/.+\.astro$" path-lower))))))

(defn- web-framework-content-kind
  [path-kind file]
  (when (contains? #{:javascript :typescript} path-kind)
    (let [content (text-file-prefix file)]
      (when (or (and (re-find #"(?m)^\s*import\s+\{?[^;\n]*\bRoutes\b[^;\n]*\}?\s+from\s+['\"]@angular/router['\"]" content)
                     (or (re-find #"(?m)\bRoutes\s*=" content)
                         (re-find #"(?m)\bprovideRouter\s*\(" content))
                     (re-find #"(?m)\bpath\s*:\s*['\"]" content))
                (and (re-find #"(?m)^\s*import\s+.+\s+from\s+['\"]@ember/routing/router['\"]" content)
                     (re-find #"(?m)\bRouter\.map\s*\(" content)
                     (re-find #"(?m)\bthis\.route\s*\(" content))
                (and (re-find #"(?m)\bmodulePrefix\s*:" content)
                     (re-find #"(?m)\brootURL\s*:" content)
                     (re-find #"(?m)\blocationType\s*:" content)))
        :web-framework))))

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

(defn- workflow-orchestration-content-kind
  [path-kind file]
  (let [content (text-file-prefix file)]
    (cond
      (and (contains? #{:yaml :config} path-kind)
           (or (and (re-find #"(?m)^apiVersion:\s*['\"]?argoproj\.io/" content)
                    (re-find #"(?m)^kind:\s*['\"]?(?:Workflow|CronWorkflow|WorkflowTemplate)['\"]?\s*$" content))
               (and (re-find #"(?m)^apiVersion:\s*['\"]?tekton\.dev/" content)
                    (re-find #"(?m)^kind:\s*['\"]?(?:Pipeline|Task|PipelineRun)['\"]?\s*$" content))))
      :workflow-orchestration

      (and (= :python path-kind)
           (or (re-find #"(?m)^\s*(?:from|import)\s+airflow(?:[.\s]|$)" content)
               (re-find #"(?m)^\s*(?:from|import)\s+dagster(?:[.\s]|$)" content)
               (re-find #"(?m)^\s*(?:from|import)\s+prefect(?:[.\s]|$)" content)
               (re-find #"(?m)^\s*(?:from|import)\s+temporalio(?:[.\s]|$)" content)))
      :workflow-orchestration

      (and (contains? #{:javascript :typescript} path-kind)
           (re-find #"(?m)['\"]@temporalio/[^'\"]+['\"]" content))
      :workflow-orchestration

      :else nil)))

(defn- data-science-content-kind
  [path-kind file]
  (let [content (text-file-prefix file)]
    (cond
      (and (contains? #{:yaml :config} path-kind)
           (re-find #"(?m)^channels:\s*$" content)
           (re-find #"(?m)^dependencies:\s*$" content))
      :data-science

      (and (contains? #{:doc :text} path-kind)
           (str/starts-with? content "---")
           (or (re-find #"(?m)^model[_-]name:\s*.+" content)
               (re-find #"(?m)^model[_-]id:\s*.+" content)
               (re-find #"(?m)^model[_-]index:\s*$" content)
               (re-find #"(?m)^model[_-]details:\s*$" content)
               (re-find #"(?m)^dataset[_-]name:\s*.+" content)
               (re-find #"(?m)^datasets?:\s*(?:.+)?$" content)))
      :data-science

      :else nil)))

(defn- observability-content-kind
  [path-kind file]
  (when (contains? #{:config :json-schema :yaml} path-kind)
    (let [content (text-file-prefix file)]
      (cond
        (and (re-find #"(?m)^receivers:\s*$" content)
             (re-find #"(?m)^exporters:\s*$" content)
             (re-find #"(?m)^\s*pipelines:\s*$" content))
        :observability-config

        (or (re-find #"(?m)^scrape_configs:\s*$" content)
            (and (re-find #"(?m)^groups:\s*$" content)
                 (re-find #"(?m)^\s*-\s*alert:\s*.+" content))
            (and (re-find #"(?m)^route:\s*$" content)
                 (re-find #"(?m)^receivers:\s*$" content))
            (and (re-find #"(?m)^apiVersion:\s*1\s*$" content)
                 (re-find #"(?m)^datasources:\s*$" content))
            (and (re-find #"(?m)^sources:\s*$" content)
                 (re-find #"(?m)^sinks:\s*$" content))
            (and (re-find #"(?s)\"schemaVersion\"\s*:" content)
                 (re-find #"(?s)\"panels\"\s*:" content)
                 (re-find #"(?s)\"title\"\s*:" content)))
        :observability-config

        :else nil))))

(defn- sbom-content-kind
  [path-kind file]
  (when (= :config path-kind)
    (let [content (text-file-prefix file)]
      (when (or (re-find #"(?s)\"bomFormat\"\s*:\s*\"CycloneDX\"" content)
                (re-find #"(?s)\"spdxVersion\"\s*:" content)
                (re-find #"(?s)\"SPDXID\"\s*:" content))
        :sbom))))

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
        (nextra-content-kind path-kind file)
        (when (web-framework-route-path? path-kind file)
          :web-framework)
        (web-framework-content-kind path-kind file)
        (workflow-orchestration-content-kind path-kind file)
        (data-science-content-kind path-kind file)
        (observability-content-kind path-kind file)
        (sphinx-content-kind path-kind file)
        (sbom-content-kind path-kind file)
        (helm-template-content-kind path-kind file)
        (ops-config-content-kind path-kind file)
        path-kind
        (shebang-kind file)
        :unknown)))

(defn- supported-file-kind
  [file]
  (if (or (supported-path? file)
          (shebang-kind file))
    (file-kind-for-file file)
    (unknown-text-fallback-kind file)))

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

(defn plugin-file-record
  "Build a text file record for an explicitly plugin-scanned repo-relative path.

  Plugin-scanned files are project-configured extension points for file families
  Yggdrasil core does not support yet. They still use the canonical file row shape
  and are ignored unless a project enables a plugin scan spec."
  [root rel {:keys [file-kind plugin-ids]}]
  (let [file (io/file root rel)
        kind (keyword (or file-kind :plugin-source))
        text (read-file file)
        f (io/file file)]
    {:file-id (str "file:" rel)
     :path rel
     :absolute-path (.getPath (.getAbsoluteFile file))
     :ext (extension file)
     :kind kind
     :content text
     :content-sha (str "sha256:" (hash/sha256-hex text))
     :mtime-ms (.lastModified f)
     :size-bytes (.length f)
     :active? true
     :plugin-scanned? true
     :plugin-ids (vec (sort (map str plugin-ids)))
     :benchmark-status :unbenchmarked}))

(defn- supported-file?
  [file]
  (and (.isFile file)
       (not (ignored-filename? file))
       (some? (supported-file-kind file))))

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
        (vitepress-config-path? path-lower)
        (re-find #"^\.vscode/(?:settings|tasks|extensions)\.json$" path-lower)
        (re-find #"^\.changeset/(?:config\.json|[^/]+\.md)$" path-lower)
        (re-find #"^\.circleci/config\.ya?ml$" path-lower)
        (re-find #"^\.buildkite/pipeline\.ya?ml$" path-lower)
        (re-find #"(^|/)\.drone\.ya?ml$" path-lower)
        (re-find #"(^|/)\.woodpecker\.ya?ml$" path-lower))))

(defn- ignored-path?
  [rel-path]
  (let [parts (str/split rel-path #"/")]
    (or (some ignored-dirs parts)
        (and (some #(str/starts-with? % ".") parts)
             (not (allowed-hidden-supported-path? rel-path))))))

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- discovery-summary
  [report]
  (select-keys report [:backend :elapsed-ms :path-count :diagnostics :fallbacks]))

(defn- normalize-candidate-path
  [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace #"^\./" "")))

(defn- candidate-file-paths
  [root paths]
  (->> paths
       (map normalize-candidate-path)
       (remove str/blank?)
       (remove ignored-path?)
       (filter #(.isFile (io/file root %)))
       distinct
       vec))

(def ^:private ripgrep-ignore-globs
  (vec (mapcat (fn [dir]
                 [(str dir "/**")
                  (str "**/" dir "/**")])
               ignored-dirs)))

(defn- severe-ripgrep-diagnostic?
  [diagnostic]
  (contains? #{:timeout :unavailable :ripgrep-error :stdout-truncated}
             (:kind diagnostic)))

(defn- usable-ripgrep-discovery?
  [result]
  (and (#{0 1} (:exit result))
       (not (:truncated? result))
       (not-any? severe-ripgrep-diagnostic? (:diagnostics result))))

(defn- ripgrep-candidate-path-report
  [root]
  (let [result (ripgrep/files root {:ignore-globs ripgrep-ignore-globs})]
    (when (usable-ripgrep-discovery? result)
      {:backend :ripgrep
       :elapsed-ms (:elapsed-ms result)
       :paths (candidate-file-paths root (:paths result))
       :path-count (count (:paths result))
       :diagnostics (:diagnostics result)})))

(defn- git-candidate-path-report
  [root]
  (let [started (now-ns)
        {:keys [exit out err]} (shell/sh "git"
                                         "-C"
                                         (.getPath root)
                                         "ls-files"
                                         "--cached"
                                         "--others"
                                         "--exclude-standard")]
    (when (zero? exit)
      (let [paths (candidate-file-paths root (str/split-lines out))]
        {:backend :git
         :elapsed-ms (elapsed-ms started)
         :paths paths
         :path-count (count paths)
         :diagnostics (cond-> []
                        (seq (str/trim (str err)))
                        (conj {:kind :stderr
                               :message (str/trim err)}))}))))

(defn- filesystem-candidate-path-report
  [root]
  (let [started (now-ns)
        paths (->> (file-seq root)
                   (filter #(.isFile %))
                   (map #(relative-path root %))
                   (candidate-file-paths root))]
    {:backend :filesystem
     :elapsed-ms (elapsed-ms started)
     :paths paths
     :path-count (count paths)
     :diagnostics []}))

(defn- unavailable-fallback
  [backend]
  {:backend backend
   :status :unavailable})

(defn scan-candidate-paths
  "Return repo-relative candidate file paths plus discovery instrumentation.

  `rg --files` is preferred when available. Yggdrasil still applies its own
  ignored-path, file-kind, and file-record rules after discovery."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (if-let [report (ripgrep-candidate-path-report root-file)]
      (assoc report
             :path-count (count (:paths report))
             :fallbacks [])
      (if-let [report (git-candidate-path-report root-file)]
        (assoc report
               :path-count (count (:paths report))
               :fallbacks [(unavailable-fallback :ripgrep)])
        (assoc (filesystem-candidate-path-report root-file)
               :fallbacks [(unavailable-fallback :ripgrep)
                           (unavailable-fallback :git)])))))

(defn- candidate-paths
  [root]
  (:paths (scan-candidate-paths root)))

(defn- file-coverage-row
  [root rel]
  (let [file (io/file root rel)
        ignored? (ignored-filename? file)
        kind (when-not ignored?
               (supported-file-kind file))
        supported? (some? kind)]
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
    (let [discovery (scan-candidate-paths root-file)]
      {:root (.getPath root-file)
       :discovery (discovery-summary discovery)
       :files (->> (:paths discovery)
                   (mapv #(file-coverage-row root-file %))
                   (sort-by :path)
                   vec)})))

(defn scan-files
  "Return supported files under root with content metadata."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    (let [discovery (scan-candidate-paths root-file)]
      (with-meta
        (->> (:paths discovery)
             (filter #(supported-file? (io/file root-file %)))
             (map #(file-record-for-relative-path root-file %))
             (sort-by :path)
             vec)
        {:discovery (discovery-summary discovery)}))))

(defn scan-plugin-files
  "Return explicitly plugin-scanned files under root.

  `scan-specs` are maps with `:path-globs`, `:file-kind`, and `:plugin-id`.
  Paths already covered by core scanning are skipped so plugins enhance those
  files through the post-core pass instead of producing duplicate file rows."
  [root scan-specs existing-paths]
  (let [root-file (.getCanonicalFile (io/file root))
        existing-paths (set existing-paths)
        spec-matches (fn [rel]
                       (->> scan-specs
                            (filter (fn [{:keys [path-globs]}]
                                      (some #(path-glob-matches? % rel)
                                            path-globs)))
                            vec))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    (if (seq scan-specs)
      (->> (candidate-paths root-file)
           (remove existing-paths)
           (remove #(ignored-filename? (io/file root-file %)))
           (keep (fn [rel]
                   (when-let [matches (seq (spec-matches rel))]
                     (let [file-kind (:file-kind (first matches))]
                       (plugin-file-record
                        root-file
                        rel
                        {:file-kind file-kind
                         :plugin-ids (map :plugin-id matches)})))))
           (sort-by :path)
           vec)
      [])))
