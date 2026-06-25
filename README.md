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
bin/ygg init . --project my-project --out project.edn --sync
ygg query "where is auth handled" --project my-project
```

`init` creates the project reference and starts the long-lived local Yggdrasil
server when it is not already running. Other `ygg` commands expect that server
to be available.
Project graph state, correction facts, memory, and activity are stored in XTDB.

For lower-level setup, use the explicit commands behind that flow:

```sh
ygg init . --project my-project --out project.edn
ygg sync project.edn --check
ygg query "where is auth handled" --project my-project
```

To start the server automatically when you log in on macOS:

```sh
ygg service start-at-login enable
```

`init` can also set up assistant harness files and auto maintenance without
prompts:

```sh
bin/ygg init . --project my-project --out project.edn \
  --harness codex --hooks --skill --mcp \
  --maintenance harness
```

Use `--maintenance deepseek` or `--maintenance openrouter` to run maintenance
with a DeepSeek V4-compatible API executor instead of the assistant harness.

Default repo-local Yggdrasil data lives under `.ygg/`. Project-shared state,
including XTDB, lives under the central Yggdrasil storage root by project id.
Use `YGG_XTDB_PATH` when you need a
different XTDB directory.

`ygg query` returns compact graph-grounded evidence by default. Exact literals
can use bounded internal ripgrep evidence, configured embeddings can support
semantic intent, and graph expansion adds related structure after a candidate is
found. Shell proof commands are not emitted unless explicitly requested for
audit/debug output.

Use task profiles when the caller already knows the question shape:

```sh
ygg query "where is login handled" --project my-project --task locate --literal "/api/login"
ygg query "explain how auth requests reach the API" --project my-project --task explain --anchor src/auth.clj
ygg query "what changes if the auth route moves" --project my-project --task impact --anchor src/auth.clj:42
```

## Embeddings

Local embeddings are the default. They use a bundled JSONL worker backed by
`sentence-transformers`, and embedding rows are stored in XTDB by provider,
model, target, and input hash.

```sh
ygg embed setup
ygg embed --project my-project
ygg query "where is auth handled" --project my-project
```

`ygg embed setup` creates `.ygg/local-embedding-venv` and installs the
bundled Python requirements used by the local worker. If you need a custom
interpreter or venv path, pass it during setup:

```sh
ygg embed setup --python python3.12 --venv .ygg/local-embedding-venv
```

Remote providers are optional add-on embedding lanes. Set an API key, index that
provider, and pass the same provider when querying that lane:

```sh
YGG_OPENAI_API_KEY=... ygg embed --project my-project --provider openai
ygg query "where is auth handled" --project my-project --provider openai

YGG_OPENROUTER_API_KEY=... ygg embed --project my-project --provider openrouter
ygg query "where is auth handled" --project my-project --provider openrouter
```

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
- Local handoff: queued work moves through local state with explicit results
  that can be reviewed before they change project memory.
- Measured claims: improvements in speed, cost, or effectiveness should point
  to repeatable comparisons.

## Main Workflows

- Sync: keep the graph aligned with the repo, inspect freshness, check for
  drift, and maintain accepted corrections.
- Query: answer focused graph-grounded questions.
- Report and view: generate local HTML and JSON graph exports for operators and
  downstream tools.
- Extend: add extractor plugins for project-specific or experimental file
  families, and report plugins for generated dashboards.
- Hand off work: enqueue bounded maintenance packets, claim them from the local
  queue, validate results, and apply accepted corrections.

## Develop

From a clone, run the entrypoints directly from `bin/` or put that directory on
your `PATH`. First project setup is always `ygg init`; there is no separate
top-level setup command.

Local verification:

```sh
bb test
bb lint
bb format:check
bb v1:smoke
bb v1:gate
```

Native server build path:

```sh
bb graalvm:uber
bb graalvm:check
bb graalvm:native
```

Project agent setup is noun-scoped: use `ygg agent install` and
`ygg agent uninstall`, not top-level `ygg install` or `ygg uninstall` wrappers.

## Detailed Docs

- [Dependencies](docs/dependencies.md)
- [Distribution](docs/distribution.md)
- [System map corrections](docs/system-map.md)
- [Project memory](docs/memory.md)
- [Context packets and doc attachments](docs/context.md)
- [Graph export schema](docs/graph-export.md)
- [Extractor plugins](docs/extractor-plugins.md)
- [Plugin packages](docs/plugin-packages.md)
- [Report plugins](docs/report-plugins.md)
- [Bitemporal XTDB core](docs/bitemporal-core.md)
- [Benchmarking](docs/benchmarking.md)
- [Agent efficiency study](docs/agent-efficiency-study.md)
