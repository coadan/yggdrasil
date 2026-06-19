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
bb plugin remove project.edn datastar-hiccup
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
   :manifest-fingerprint "sha256:..."
   :installed-at-ms 1790000000000}]}
```

`bb sync` and `bb report` do not fetch network updates. They read the installed
local package path. Updating a git plugin is explicit: rerun `bb plugin install
... --force` with the intended ref.

Remove a package from a project with `bb plugin remove <project.edn>
<package-id>`. This edits only the `:plugin-packages` entry in `project.edn`;
cached git checkouts stay under `.dev/agraph/plugins/cache` and can be reused by
installing again.

AGraph recomputes the manifest fingerprint when a package is read. If the
installed package path no longer matches the fingerprint recorded in
`project.edn`, package diagnostics report the mismatch.

## Authoring Loop

Start with a local scaffold:

```sh
bb plugin new .dev/agraph/plugins/datastar-hiccup --id datastar-hiccup
bb plugin validate .dev/agraph/plugins/datastar-hiccup
bb plugin diagnose .dev/agraph/plugins/datastar-hiccup
bb plugin dry-run extractor .dev/agraph/plugins/datastar-hiccup . src/page.clj --json
bb plugin dry-run report .dev/agraph/plugins/datastar-hiccup --json
bb plugin registry validate .dev/agraph/plugins/registry.edn
```

`plugin new` writes `agraph.plugin.edn`, Python extractor/report examples,
`fixtures/sample.clj`, `registry.example.edn`, and a package README. By default
it creates both extractor and report examples; use `--extractor` or `--report`
to scaffold only one lane.

`plugin validate` reads the package manifest and runs the same plugin config
normalizers used by project loading. It reports package caveats such as
`:unbenchmarked` status without blocking local experiments.
Plain output includes claim authority so unbenchmarked or project-local packages
are visible as non-authoritative without requiring `--json`.

`plugin diagnose` explains package readiness for four lanes:

- local use;
- public sharing;
- public claims;
- core promotion.

Diagnosis is manifest- and config-based. It does not judge architecture quality
or project-specific usefulness. It surfaces the caveats that should travel with
plugin output: scope, public/FOSS/non-commercial policy, benchmark status,
claim authority, validation errors, and promotion blockers.

`plugin dry-run extractor` runs the package extractor against one file without
writing graph state. It uses core extraction first, applies the selected plugin
or all extractor plugins in the package, and returns normalized rows,
diagnostics, before/after counts, and the full package summary with benchmark,
scope, claim authority, fingerprint, core-promotion evidence, and warning
caveats. This is the fastest feedback loop for agents building project-local
architecture understanding.

`plugin dry-run report` runs the package report plugin against a synthetic report
context without generating a full report. It returns panels, artifacts,
diagnostics, per-plugin counts, and the same package caveats. This keeps report
plugin authoring in the same scaffold / validate / diagnose / dry-run loop as
extractors.

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
 :scope {:kind :base
         :reason "Reusable Datastar/Hiccup extraction across projects."}
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
- package id, version, source, pinned git revision, and manifest fingerprint
- `:benchmark-status`, defaulting to `:unbenchmarked`
- `:claim-authority`, where unbenchmarked or project-local packages are
  explicitly `:non-authoritative`
- plugin provenance on emitted rows or report panels

Project-local `:extractor-plugins` and `:report-plugins` still work. They are
loaded after packaged plugins, so local config can intentionally override or
augment a package during development.

Generated reports include a `plugin-packages` section in `report.json`. It keeps
package counts, benchmark status, claim authority, warnings, source pins,
manifest fingerprints, and `agraph plugin diagnose ... --json` commands with the
report artifact, even when no report plugin renders those caveats.

## Ecosystem Policy

Private/local packages are project dependencies. AGraph does not block them
based on license metadata.

Public AGraph/Yggdrasil plugin packages should be FOSS and non-commercial. The
official registry should not list commercial plugins or project-local plugins.
Public packages should declare license metadata and `:scope {:kind :base}` and
should not claim agent or architecture-understanding improvements without
benchmark artifacts.

Package install surfaces warnings instead of blocking local use when:

- a public package does not declare a known FOSS license;
- a public package is marked commercial or monetized;
- a package is declared `:project-local`;
- a package is unbenchmarked.

`bb plugin diagnose <dir>` treats public license/commercial policy violations as
public-sharing blockers while still keeping private local experiments possible.

## Registry Validation

Public registry indexes are EDN files that combine two roles:

- `:path` points at a local package directory for offline validation.
- `:source`, `:ref`, and `:subdir` describe how another project installs the
  package from git.

Registry entries must include both `:path` and `:source` to pass validation.
`:ref` and `:subdir` are optional, but recommended for reproducible installs
when the package does not live at the repository root.

```clojure
{:schema "agraph.plugin.registry/v1"
 :id "official"
 :packages [{:id "datastar-hiccup"
             :path "packages/datastar-hiccup"
             :source "https://github.com/org/agraph-plugins.git"
             :ref "v0.1.0"
             :subdir "packages/datastar-hiccup"}]}
```

Run:

```sh
bb plugin registry validate registry.edn --json
```

Validation reads each local package manifest and runs `plugin diagnose`; it does
not fetch git sources. A registry entry passes when the package is ready or
caution for public sharing and declares a git `:source`. Project-local,
commercial, non-FOSS, invalid, missing, or non-installable packages fail the
registry check. Unbenchmarked base packages may be listed as experimental, but
public claims and core promotion remain blocked until benchmark artifacts exist.
JSON validation output includes install metadata when `:source` is present,
including a copyable `bb plugin install` command, plus `:claim-ready` and
`:non-authoritative` counts. A package can pass registry sharing checks while
still being non-authoritative for public claims.
Text output prints that install command and claim authority under each
installable registry package.

## Scope

Every package has a declared scope:

- `:project-local`: an experiment or team-local package that may depend on one
  repository's conventions. This is the scaffold default.
- `:base`: a reusable package intended to work as ecosystem or core-ready
  support for a file family, framework, report slot, or extractor gap.

Scope is self-declared metadata. AGraph does not infer project specificity from
path names, repository names, host names, prose, or substring lists. Diagnosis
uses the declared scope to keep project-local packages external:

```clojure
:scope {:kind :project-local
        :reason "Depends on this repository's Hiccup component conventions."}
```

Only `:base` packages can become ready for public sharing, public claims, or
core-promotion review. Changing scope is an author/reviewer decision backed by
fixtures, dry-runs, and benchmark artifacts.

## Benchmark Evidence

Use `:benchmark {:status :unbenchmarked}` while a package is experimental. Plugin
output remains auditable and useful, but claims about agent effectiveness or
architecture-understanding improvement must stay scoped.

Use `:benchmark {:status :benchmarked}` only when the package includes
package-local benchmark artifacts:

```clojure
:benchmark
{:status :benchmarked
 :artifacts [{:path "benchmarks/datastar-hiccup-report.json"
              :kind :agent-report
              :case-id "datastar-hiccup-architecture"}]}
```

`bb plugin diagnose <dir>` checks that every declared artifact path exists. A
package marked `:benchmarked` without artifacts is blocked for public claims and
core promotion. Diagnosis does not decide whether the benchmark proves enough
material improvement; it only verifies that reviewable evidence exists.

## Core-Promotion Evidence

Core promotion has a stricter bar than public sharing or public claims. A
benchmarked base package can support public claims while still being blocked for
core-promotion review until it declares package-local fixtures and tests:

```clojure
:core-promotion
{:fixtures [{:path "fixtures/datastar_hiccup.clj"
             :kind :fixture}]
 :tests [{:path "test/datastar_hiccup_test.clj"
          :kind :test}]}
```

`bb plugin diagnose <dir>` checks that these paths exist. Missing fixtures or
tests are warnings for ordinary package use, but they block the core-promotion
readiness lane.

## Core Promotion

A package can become part of core only through a normal contribution:

- The behavior is project-agnostic and suitable as base extractor/report support.
- The package declares `:scope {:kind :base}`.
- It does not depend on project names, host names, path semantics, prose, or
  substring heuristics.
- It includes fixtures and tests.
- It includes benchmark cases and reports showing material improvement for the
  relevant problem class.

Until then, keep the idea as a package with explicit plugin provenance and
`:scope {:kind :project-local}` or `:benchmark-status :unbenchmarked`.
