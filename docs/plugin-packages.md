# Plugin Packages

Plugin packages make extractor and report plugins shareable over git without
promoting them into AGraph core. A package is trusted local code: install pins a
git revision into `.dev/agraph/plugins/cache`, records the package in
`project.edn`, and ordinary `sync` / `report` commands load the resolved local
manifest.

Use packages when a team or ecosystem wants to share extraction or report ideas
while keeping core deterministic, project-agnostic, and benchmark-gated.

## Install

```sh
bb plugin install project.edn https://github.com/org/agraph-datastar.git --ref v0.1.0
bb plugin list project.edn
```

Useful flags:

- `--ref REF`: branch, tag, or commit to check out before pinning.
- `--subdir DIR`: package directory inside a larger git repo.
- `--cache-dir DIR`: local clone cache; defaults to `.dev/agraph/plugins/cache`
  relative to `project.edn`.
- `--force`: replace an already installed package with the same package id.
- `--json`: emit machine-readable install/list output.

Install writes a `:plugin-packages` entry to `project.edn`:

```clojure
{:id "sample"
 :repos [{:id "app" :root "."}]
 :plugin-packages
 [{:id "datastar-hiccup"
   :source {:type :git
            :url "https://github.com/org/agraph-datastar.git"
            :ref "v0.1.0"
            :rev "..."
            :subdir "packages/datastar-hiccup"}
   :path "/abs/project/.dev/agraph/plugins/cache/.../packages/datastar-hiccup"
   :manifest "agraph.plugin.edn"
   :installed-at-ms 1790000000000}]}
```

`bb sync` and `bb report` do not fetch network updates. They read the installed
local package path. Updating a git plugin is explicit: rerun `bb plugin install
... --force` with the intended ref.

## Manifest

Each package directory contains `agraph.plugin.edn`:

```clojure
{:schema "agraph.plugin/v1"
 :id "datastar-hiccup"
 :name "Datastar Hiccup"
 :version "0.1.0"
 :license {:spdx "MIT"}
 :distribution {:visibility :public
                :commercial? false}
 :benchmark {:status :unbenchmarked}
 :extractor-plugins
 [{:id "datastar-hiccup-extractor"
   :command ["python3" "extract.py"]
   :modes [:enhance :scan]
   :applies-to {:file-kinds [:code]
                :path-globs ["src/**/*.clj"]}
   :scan {:path-globs ["resources/**/*.edn"]
          :file-kind :datastar-config}
   :search {:chunks? true}
   :emits [:datastar-signal]}]
 :report-plugins
 [{:id "datastar-hiccup-report"
   :command ["python3" "report.py"]
   :slots [:plugins :systems]}]}
```

Package plugin commands run with their working directory set to the installed
package directory. This keeps package commands relative to the package, while
the extractor input still includes the indexed repo root and file content.

Installed package plugins are normalized into the same project shapes described
in [extractor-plugins.md](extractor-plugins.md) and
[report-plugins.md](report-plugins.md). They carry:

- `:authority :git-plugin`
- package id, version, source, and pinned git revision
- `:benchmark-status`, defaulting to `:unbenchmarked`
- plugin provenance on emitted rows or report panels

Project-local `:extractor-plugins` and `:report-plugins` still work. They are
loaded after packaged plugins, so local config can intentionally override or
augment a package during development.

## Ecosystem Policy

Private/local packages are project dependencies. AGraph does not block them
based on license metadata.

Public AGraph/Yggdrasil plugin packages should be FOSS and non-commercial. The
official registry should not list commercial plugins. Public packages should
declare license metadata and should not claim agent or architecture-understanding
improvements without benchmark artifacts.

Package install surfaces warnings instead of blocking local use when:

- a public package does not declare a known FOSS license;
- a public package is marked commercial or monetized;
- a package is unbenchmarked.

## Core Promotion

A package can become part of core only through a normal contribution:

- The behavior is project-agnostic and suitable as base extractor/report support.
- It does not depend on project names, host names, path semantics, prose, or
  substring heuristics.
- It includes fixtures and tests.
- It includes benchmark cases and reports showing material improvement for the
  relevant problem class.

Until then, keep the idea as a package with explicit plugin provenance and
`:benchmark-status :unbenchmarked`.
