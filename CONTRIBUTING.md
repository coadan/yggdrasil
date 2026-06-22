# Contributing

Yggdrasil is local-first tooling for coding agents. Changes should preserve three
agent jobs:

- Build: construct graph/evidence from repositories.
- Query: answer focused questions with progressive disclosure.
- Maintain: keep graph facts aligned with changing systems.

## Setup

See [docs/dependencies.md](docs/dependencies.md).

macOS:

```sh
scripts/install-macos.sh --install-deps
```

## Checks

Run before opening a pull request:

```sh
bb test
bb lint
bb format:check
```

If Babashka is unavailable:

```sh
clojure -M:test
clojure -M:lint
clojure -M:format/check
```

## Design Rules

- Prefer deterministic extractors and explicit evidence rows.
- Keep outputs compact by default so agents can drill down progressively.
- Do not add project-specific layout assumptions unless they are weak hints with
  safe fallbacks.
- Keep external provider use optional.
- Do not log or persist secret values; store names, hosts, ports, routes, hashes,
  and bounded previews only.

## Plugin Contributions

Keep project-specific extractors and reports as explicit plugins. Do not promote
a plugin idea into core if it depends on one repository's helper names, product
vocabulary, host names, path semantics, prose, or brittle substring rules.

Core promotion PRs that start from plugin work must include:

- project-agnostic extractor/report behavior suitable for base support;
- fixtures and tests for the promoted behavior;
- benchmark cases and generated reports showing material improvement for the
  relevant problem class;
- clear before/after evidence when the claim compares shell-only, core Yggdrasil,
  and plugin-enhanced Yggdrasil;
- FOSS, non-commercial package metadata when the work was developed as a public
  plugin package.

Run `bb plugin core-check <package-dir>` before proposing plugin behavior for
core. The command verifies the minimum benchmark, fixture, test, scope, and
policy evidence for review; reviewers still decide whether the behavior is
project-agnostic enough for core.

Unbenchmarked or project-local plugins can still be useful, but keep them
external and non-authoritative for public claims until benchmark artifacts exist.
