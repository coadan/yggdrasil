<p align="center">
  <img src="ygg.png" alt="Yggdrasil" width="360">
</p>

# Yggdrasil

Real-world systems are more than code files. They are services, configs,
dependencies, docs, deployments, ownership decisions, architectural choices,
and half-remembered fixes spread across a repo over time.

Yggdrasil helps coding agents work in that reality. It builds a local, reviewable
map of what exists, how important pieces connect, and what has been accepted
about the system, so agents can find the right context without rereading
everything from scratch.

As a project grows, an agentic development tool needs to preserve more than
search results. It needs a maintainable way to encode project-specific tribal
knowledge about architecture, boundaries, ownership, and recurring fixes, then
keep that knowledge easy to review and update as the system changes.

Many agent tools call this codebase memory. Yggdrasil is more specific: it keeps
that memory tied to files, evidence, and reviewable corrections, so maintainers
can see why an answer was trusted. Claims about speed, cost, or effectiveness
should come from repeatable benchmarks.

## Quickstart

```sh
ygg start . --project my-project
```

`start` writes or reuses `project.edn`, creates `ygg.map.json`, syncs the
repo, imports local queue activity, and writes an `ygg-out/` report bundle.
Open `ygg-out/index.html` for the generated report and graph viewer.

For lower-level setup, use the explicit commands behind that flow:

```sh
ygg init . --project my-project --out project.edn
ygg sync project.edn --check --map ygg.map.json
ygg ask "where is auth handled" --project my-project
ygg explore create "where is auth handled" --project my-project --map ygg.map.json
```

Default local data lives under `.dev/`. Use `YGG_XTDB_PATH` when you need a
different XTDB directory.

## Core Ideas

- Real systems first: Yggdrasil looks beyond source files to the repo evidence
  agents need for real maintenance work. Use `ygg sync coverage project.edn`
  for the current support breakdown.
- Architecture and tribal knowledge: accepted corrections, boundaries, and
  useful context become part of the project record instead of disappearing with
  one agent session.
- Easy upkeep: project knowledge has to stay cheap to review and update, or it
  stops helping in complex environments.
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

This installs the Yggdrasil binary. Project agent setup is noun-scoped: use
`ygg agent install` and `ygg agent uninstall`, not top-level
`ygg install` or `ygg uninstall` wrappers.

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
