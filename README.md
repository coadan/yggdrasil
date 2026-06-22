# AGraph

AGraph is a local-first repo graph indexer backed by XTDB. It helps coding
agents work on complex systems by grounding answers, reports, and handoffs in
concrete, cited repository evidence.

AGraph is not a semantic classifier bolted onto source search. It stores durable
facts first, derives neutral graph candidates from structure and evidence
density, and keeps accepted project meaning in auditable corrections,
metadata, or `agraph.map.json`.

## Quickstart

```sh
agraph start . --project my-project
```

`start` writes or reuses `project.edn`, creates `agraph.map.json`, syncs the
repo, imports local queue activity, and writes an `agraph-out/` report bundle.
Open `agraph-out/index.html` for the generated report and graph viewer.

For lower-level setup, use the explicit commands behind that flow:

```sh
agraph init . --project my-project --out project.edn
agraph sync project.edn --check --map agraph.map.json
agraph ask "where is auth handled" --project my-project
agraph explore create "where is auth handled" --project my-project --map agraph.map.json
```

Default local data lives under `.dev/`. Use `AGRAPH_XTDB_PATH` when you need a
different XTDB directory.

## Core Ideas

- Evidence first: deterministic extractors index 20+ source, manifest,
  lockfile, schema, config, docs, asset, and operations artifact families. Use
  `agraph sync coverage project.edn` for the current support breakdown.
- Emergent systems: generated system nodes are candidates, not final
  architecture claims. Human or LLM-backed corrections record accepted project
  meaning in `agraph.map.json` or metadata.
- Progressive disclosure: agents should start with freshness, coverage, and
  compact graph views, then drill into `ask`, `explore`, reports, and evidence
  only for the active task.
- Local handoff: the filesystem queue stores transport and lease state for
  provider-agnostic agent work. Semantic results return as explicit JSON
  artifacts before they are validated and applied.
- Measured claims: any claim that AGraph improves agent efficiency should point
  to replayable shell-only versus AGraph evidence, not anecdotes.

## Main Workflows

- Sync: keep the graph aligned with the repo, inspect freshness, check for
  drift, and maintain accepted corrections.
- Ask and explore: answer focused questions or run longer investigations from a
  stable graph basis.
- Report and view: generate local HTML and JSON graph exports for operators and
  downstream tools.
- Extend: add extractor plugins for project-specific or experimental file
  families, and report plugins for generated dashboards.
- Hand off work: enqueue bounded maintenance packets, claim them from the local
  queue, validate results, and apply accepted map changes.

## Install And Develop

Native macOS install from a clone:

```sh
scripts/install-macos.sh --install-deps
```

Local verification:

```sh
bb test
bb lint
bb format:check
bb v1:smoke
bb v1:gate
```

This installs the AGraph binary. Project agent setup is noun-scoped: use
`agraph agent install` and `agraph agent uninstall`, not top-level
`agraph install` or `agraph uninstall` wrappers.

## Detailed Docs

- [Dependencies](docs/dependencies.md)
- [Distribution](docs/distribution.md)
- [System map corrections](docs/system-map.md)
- [Context packets and doc attachments](docs/context.md)
- [Graph export schema](docs/graph-export.md)
- [Extractor plugins](docs/extractor-plugins.md)
- [Plugin packages](docs/plugin-packages.md)
- [Report plugins](docs/report-plugins.md)
- [Bitemporal XTDB core](docs/bitemporal-core.md)
- [Benchmarking](docs/benchmarking.md)
- [Agent efficiency study](docs/agent-efficiency-study.md)
