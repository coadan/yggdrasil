# Contributing

AGraph is local-first tooling for coding agents. Changes should preserve three
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
