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
bb plugin update project.edn datastar-hiccup --ref v0.2.0
bb plugin list project.edn
bb plugin remove project.edn datastar-hiccup
```

Useful flags:

- `--ref REF`: branch, tag, or commit to check out before pinning.
- `--subdir DIR`: package directory inside a larger git repo.
- `--cache-dir DIR`: local clone cache; defaults to `.dev/agraph/plugins/cache`
  relative to `project.edn`.
- `--force`: replace an already installed package with the same package id.
- `--json`: emit machine-readable install/update/list output.

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
local package path. Updating a git plugin is explicit: run
`bb plugin update <project.edn> <package-id>`. The update command resolves the
installed package source, refreshes the cached checkout, validates the new
manifest through the same path as install, and only then replaces the project
entry. Without `--ref`, update reuses the installed ref when one was recorded,
falling back to the pinned revision. Pass `--ref` to move to a new tag, branch,
or commit.

Remove a package from a project with `bb plugin remove <project.edn>
<package-id>`. This edits only the `:plugin-packages` entry in `project.edn`;
cached git checkouts stay under `.dev/agraph/plugins/cache` and can be reused by
installing again.

AGraph recomputes the manifest fingerprint when a package is read. If the
installed package path no longer matches the fingerprint recorded in
`project.edn`, package diagnostics report the mismatch. Diagnostics also report
when an installed entry id no longer matches the package manifest id.

## Authoring Loop

Start with a local scaffold:

```sh
bb plugin new .dev/agraph/plugins/datastar-hiccup --id datastar-hiccup
bb plugin validate .dev/agraph/plugins/datastar-hiccup
bb plugin diagnose .dev/agraph/plugins/datastar-hiccup
bb plugin core-check .dev/agraph/plugins/datastar-hiccup
bb plugin input extractor .dev/agraph/plugins/datastar-hiccup . src/page.clj --json
bb plugin dry-run extractor .dev/agraph/plugins/datastar-hiccup . src/page.clj --json
bb plugin dry-run report .dev/agraph/plugins/datastar-hiccup --json
bb plugin registry validate .dev/agraph/plugins/registry.edn
```

`plugin new` writes `agraph.plugin.edn`, Python extractor/report examples, a
fixture file, `registry.example.edn`, `benchmarks/README.md`, and a package
README. By default it creates both extractor and report examples; use
`--extractor` or `--report` to scaffold only one lane.

For unsupported file families, keep the package external and pass explicit
scaffold options instead of adding project-specific rules to core:

```sh
bb plugin new .dev/agraph/plugins/htmx \
  --id htmx \
  --extractor \
  --file-kind htmx \
  --path-glob 'templates/*.html' \
  --scan-glob 'templates/*.html' \
  --fixture fixtures/sample.html
```

After creation, review the generated manifest:

- set the extractor plugin `:applies-to :file-kinds` to the file kind the
  package handles;
- set `:scan` when the package should discover files core does not index yet;
- replace `fixtures/sample.clj` with representative project-agnostic fixtures;
- keep emitted facts concrete and auditable with source paths, source lines, and
  diagnostics where possible.

The benchmark README is a placeholder for package-local benchmark artifacts.
Keep the manifest at `:benchmark {:status :unbenchmarked}` until those artifacts
exist and can back public claims.

`plugin validate` reads the package manifest and runs the same plugin config
normalizers used by project loading. It reports package caveats such as
`:unbenchmarked` status without blocking local experiments.
Plain output includes claim authority so unbenchmarked or project-local packages
are visible as non-authoritative without requiring `--json`.
Local-use package errors, including duplicate plugin ids inside the extractor or
report lanes, fail validation and block install or dry-run.

`plugin diagnose` explains package readiness for four lanes:

- local use;
- public sharing;
- public claims;
- core promotion.

Diagnosis is manifest- and config-based. It does not judge architecture quality
or project-specific usefulness. It surfaces the caveats that should travel with
plugin output: scope, public/FOSS/non-commercial policy, benchmark status,
claim authority, validation errors, and promotion blockers.

`plugin core-check` is the CI-friendly gate for plugin-to-core PRs. It passes
only when the core-promotion readiness lane reaches `:review-required`: the
package is base-scoped, FOSS/non-commercial, benchmarked with existing benchmark
artifacts, and declares package-local fixtures and tests. Passing this command
does not merge the plugin into core; it only proves the package has the minimum
review evidence for a project-agnostic core contribution.

`plugin input extractor` builds the exact `agraph.extractor-plugin.input/v1`
packet that selected package extractors would receive for one file without
executing plugin code. JSON output includes package caveats, selected/skipped
plugins, core extraction counts, and one input packet per applicable selected
extractor. Use this before writing or debugging an agent-authored extractor so
the plugin can target the real contract instead of guessing what AGraph sends.

`plugin dry-run extractor` runs the package extractor against one file without
writing graph state. It uses core extraction first, applies the selected plugin
or all extractor plugins in the package, and returns normalized rows,
diagnostics, before/after counts, and the full package summary with benchmark,
scope, claim authority, fingerprint, core-promotion evidence, and warning
caveats. Each selected plugin summary includes package id, version, source,
pinned revision, manifest fingerprint, benchmark status, and claim authority.
Dry-run JSON and plain CLI output also include `selection`, listing available,
selected, and skipped package plugins plus the requested plugin id when one was
provided.
This is the fastest feedback loop for agents building project-local architecture
understanding. If the package has no extractor plugin for the selected lane, the
dry-run fails with a structured diagnostic instead of passing without testing
any plugin. Failed dry-runs make the CLI command fail, so agents and CI can use
them as an authoring gate.

`plugin dry-run report` runs the package report plugin against a synthetic report
context without generating a full report. It returns panels, artifacts,
diagnostics, per-plugin counts, per-plugin package pins/source, and the same
package caveats. The synthetic report context also carries those package
summaries under `report.plugin-packages`, so report plugins receive the same
`pluginPackages` input during dry-run authoring that they receive during full
report generation. This keeps report plugin authoring in the same scaffold /
validate / diagnose / dry-run loop as extractors. Report dry-runs also fail with
a structured diagnostic when no report plugin is selected, and failed report
dry-runs also fail the CLI command. Report dry-runs include the same
`selection` shape as extractor dry-runs.

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
- expected package id and expected manifest fingerprint from the installed
  project entry
- `:benchmark-status`, defaulting to `:unbenchmarked`
- `:claim-authority`, where unbenchmarked or project-local packages are
  explicitly `:non-authoritative`
- plugin provenance on emitted rows or report panels

Project-local `:extractor-plugins` and `:report-plugins` still work. They are
loaded after packaged plugins, so local config can intentionally override or
augment a package during development.

Generated reports include a `plugin-packages` section in `report.json`. It keeps
package counts, benchmark status, claim authority, diagnostic counts, structured
diagnostics, warnings, source pins, manifest fingerprints, and diagnose commands
with the report artifact, even when no report plugin renders those caveats.

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
The claims and core-promotion readiness lanes are stricter than local use: even
a private benchmarked package remains non-authoritative for public claims and
blocked from core-promotion review until it declares known FOSS,
non-commercial metadata.

## Registry Validation

Public registry indexes are EDN files that combine two roles:

- `:path` points at a local package directory for offline validation.
- `:source`, `:ref`, and `:subdir` describe how another project installs the
  package from git.

Registry entries must use unique package ids and include `:path`, `:source`, and
`:ref` to pass validation. `:subdir` is optional, but recommended when the
package does not live at the repository root.

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
caution for public sharing, declares a git `:source` and pinned `:ref`, and has a
package id that is unique within the registry. Project-local, commercial,
non-FOSS, invalid, missing, non-installable, duplicate-id, or floating-ref
packages fail the registry check. Unbenchmarked base packages may be listed as
experimental, but public claims and core promotion remain blocked until
benchmark artifacts exist.
JSON validation output includes install metadata when `:source` is present,
including a copyable `bb plugin install` command, plus `:claim-ready` and
`:non-authoritative` counts and `:error-counts` grouped by registry error code.
A package can pass registry sharing checks while still being non-authoritative
for public claims.
Text output prints that install command and claim authority under each
installable registry package.

### Registry Workflow

For package authors:

1. Keep experimental packages private or project-local while iterating.
2. Run `bb plugin validate <package-dir>` and fix local-use errors.
3. Run extractor/report dry-runs against representative fixtures.
4. Declare `:scope {:kind :base}` only after reviewing that the package is
   reusable and does not depend on one repository's helper names, product
   vocabulary, path semantics, hosts, prose, or substring rules.
5. Add benchmark artifacts before changing `:benchmark :status` to
   `:benchmarked` or making public improvement claims.
6. Publish a git ref and add a registry entry with `:source`, pinned `:ref`, and
   optional `:subdir`.
7. Run `bb plugin registry validate registry.edn --json` before sharing the
   registry.

For registry reviewers:

- Reject project-local packages from the public registry.
- Reject commercial or non-FOSS public packages.
- Reject floating refs; registry installs must be reproducible.
- Treat `:unbenchmarked` base packages as experimental and
  non-authoritative, even when the registry entry passes sharing checks.
- Require benchmark artifacts before accepting public claims or core-promotion
  requests.
- Prefer keeping niche or project-specific ideas as external packages rather
  than widening AGraph core.

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

Supported package benchmark statuses are `:unbenchmarked` and `:benchmarked`.
Use `:benchmark {:status :unbenchmarked}` while a package is experimental.
Plugin output remains auditable and useful, but claims about agent effectiveness
or architecture-understanding improvement must stay scoped.

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

Use `bb plugin core-check <dir>` in PR checks for plugin-to-core proposals. It
fails until the core-promotion lane is ready for human review.

## Core Promotion

A package can become part of core only through a normal contribution:

- The behavior is project-agnostic and suitable as base extractor/report support.
- The package declares `:scope {:kind :base}`.
- It declares known FOSS, non-commercial distribution metadata.
- It does not depend on project names, host names, path semantics, prose, or
  substring heuristics.
- It includes fixtures and tests.
- It includes benchmark cases and reports showing material improvement for the
  relevant problem class.

Until then, keep the idea as a package with explicit plugin provenance and
`:scope {:kind :project-local}` or `:benchmark-status :unbenchmarked`.
