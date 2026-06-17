# Graphify Model Incorporation Roadmap

## Goal

Borrow the highest-value product and workflow ideas from Graphify without
changing AGraph's core model:

- facts first
- deterministic extraction where possible
- semantic judgment only through bounded evidence and auditable corrections
- durable local state in XTDB
- provider-agnostic agent handoff

The borrowed ideas should make AGraph easier to adopt and harder to forget
during agent work. They should not turn AGraph into a report-only graph
generator or a broad semantic classifier.

## Stages

| Stage | Plan | Outcome |
| --- | --- | --- |
| 1 | [Agent Install And Hooks](01-agent-install-and-hooks.md) | Agents get project/user instructions that route broad codebase questions through AGraph first. |
| 2 | [Watch And Git Refresh](02-watch-and-git-refresh.md) | AGraph can stay current through file watching and post-commit/post-checkout hooks. |
| 3 | [One Command Onboarding](03-one-command-onboarding.md) | New users can run one command on a repo and get a usable project config, sync, and next steps. |
| 4 | [Report Bundle](04-report-bundle.md) | AGraph emits a simple shareable `agraph-out/` bundle while preserving `agraph.graph/v2`. |
| 5 | [MCP Server](05-mcp-server.md) | `agraph-mcp` becomes a real structured server over ask, explore, view, and sync work. |
| 6 | [Extractor Expansion](06-extractor-expansion.md) | Add high-impact deterministic extractors and coverage guidance. |
| 7 | [Adoption Polish](07-adoption-polish.md) | Finish the docs, tests, migration cleanup, and release checks around the new surface. |

## Implementation Order

1. **Agent install first.** This has the highest product leverage and can be
   implemented mostly as file generation around existing CLI commands.
2. **Refresh loop second.** Hooks and watch mode make the current graph useful
   over multiple coding turns.
3. **Onboarding and report bundle third.** These make the system legible to
   humans without weakening the internal graph contract.
4. **MCP fourth.** AGraph already has packet-shaped APIs; MCP should expose them
   after the public command surface is clearer.
5. **Extractor expansion last.** New extractors are valuable, but only after the
   ingestion and agent workflows are easy to use and validate.

## Global Constraints

- Do not add deterministic semantic classification from names, hosts, path
  vocabulary, prose, or substring lists.
- New commands should wrap the canonical surfaces: `sync`, `ask`, `explore`,
  `view`, `sync work`, `sync docs`, and `sync meta`.
- New file type support must emit canonical extraction buckets and graph rows.
- Keep renderer-specific state out of `agraph.graph/v2`.
- Keep `.dev/` outputs local and ignored unless the user explicitly asks for
  tracked examples.
- Prefer aggressive canonicalization over compatibility layers while AGraph is
  in heavy development.

## Cross-Stage Test Matrix

Every stage should add or update focused tests:

- CLI usage and help output for new public commands.
- Golden or shape tests for generated files.
- Idempotency tests for install, hook, report, and init commands.
- No-network unit tests for deterministic extraction.
- Safety tests for hooks and external path handling.
- End-to-end smoke tests using `test/fixtures/project-repo`.

## Done Criteria

The roadmap is complete when a fresh repo can run:

```sh
agraph init .
agraph sync project.edn --check --map agraph.map.json
agraph install-agent --platform codex --project
agraph hook install
agraph report project.edn
agraph-mcp
```

and an agent can then answer focused questions through:

```sh
agraph ask "where is auth handled" --project <id> --json
agraph explore create "runtime boundary" --project <id>
agraph view systems --project <id> --format json
agraph sync work pull --project <id> --agent codex
```

