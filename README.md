# AGraph

AGraph helps coding agents understand a codebase without rereading everything
from scratch on every task. It builds a local, reviewable map of what is in the
repo, where important pieces connect, and what the team has already accepted as
true.

Many agent tools call this codebase memory. AGraph is more specific: it keeps
that memory tied to files, evidence, and reviewable corrections, so agents know
where to look and teams can see why an answer was trusted.

AGraph is designed to make agent work easier to verify. Claims about speed,
cost, or effectiveness should come from repeatable benchmarks.

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

- Evidence first: AGraph starts from files and facts before asking anyone to
  decide what they mean. Use `agraph sync coverage project.edn` for the current
  support breakdown.
- Shared project memory: accepted corrections and useful context become part of
  the project record instead of disappearing with one agent session.
- Progressive disclosure: agents start with a compact view, then open more
  detail only when the current task needs it.
- Local handoff: queued work moves through local files with explicit results
  that can be reviewed before they change project memory.
- Measured claims: improvements in speed, cost, or effectiveness should point
  to repeatable comparisons.

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
