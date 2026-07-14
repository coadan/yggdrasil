# Contributing

Yggdrasil is local-first tooling for coding agents. Changes should preserve three
agent jobs:

- Build: construct graph/evidence from repositories.
- Query: answer focused questions with progressive disclosure.
- Maintain: keep graph facts aligned with changing systems.

## Setup

Install the required tools listed in [docs/dependencies.md](docs/dependencies.md),
then clone the repository and prefetch the Clojure dependencies:

```sh
git clone https://github.com/coadan/yggdrasil.git
cd yggdrasil
clojure -P -M:test
bin/ygg help
```

Install the report UI dependencies only when you work on the viewer or run the
full release gate:

```sh
cd report-ui
npm ci
```

## Checks

Run the smallest relevant test namespaces while developing. Before opening a
pull request with Clojure changes, run:

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

For report UI changes, also run:

```sh
bb report-ui:test
bb report-ui:build
```

Run `bb v1:smoke` when a change touches setup, the CLI wrapper, project
registration, server startup, sync, query, reports, or packaged runtime behavior.
Use the full `bb test` suite for shared contracts and broad cross-module changes.

## Design Rules

- Prefer deterministic extractors and explicit evidence rows.
- Keep outputs compact by default so agents can drill down progressively.
- Do not infer project meaning from names, hosts, path vocabulary, prose, or
  substring lists. Expose evidence and bounded candidate decisions for a human
  or LLM-backed correction instead.
- Keep external provider use optional.
- Do not log or persist secret values; store names, hosts, ports, routes, hashes,
  and bounded previews only.
- Replace graph facts with temporal `delete-docs`; reserve `erase-docs` for
  explicit legal deletion.

Claims that Yggdrasil improves agent speed or effectiveness require replayable
shell-only versus Yggdrasil benchmark evidence. See
[docs/benchmarking.md](docs/benchmarking.md) before changing extractors,
retrieval, or architecture-related ranking.

## Pull Requests

- Keep each commit to one passing, reviewable slice.
- Explain the user-visible behavior and the evidence that verifies it.
- Include focused tests for fixes and data-shape changes.
- Note any intentionally skipped check and why it could not run.
- Never include repository contents, credentials, central Yggdrasil state, or
  generated graph databases in an issue or pull request.

Security vulnerabilities belong in the private reporting flow described in
[SECURITY.md](SECURITY.md), not in a public issue.

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
