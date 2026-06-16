# AGraph

AGraph is a local-first repo graph indexer backed by XTDB. It is designed for
coding agents that need to work effectively on complex systems over time.

It starts with deterministic Clojure/ClojureScript/Rust/EDN/Markdown indexing:

- namespaces, requires, vars, tests, Rust modules, Rust imports, and Rust items
- Markdown heading chunks and exact symbol mentions
- XTDB-backed files, runs, nodes, high-confidence edges, chunks, diagnostics, and query runs
- OpenRouter/OpenAI embedding-backed hybrid semantic query with graph expansion
- token-bounded context packets with attached architecture/docs snippets
- dependency summaries, path search, and Markdown reports
- project-level system graphs, third-party API nodes, and maintenance findings

Default indexing persists high-confidence relations: definitions, namespace
requires, Rust module declarations, and Rust imports. Noisy inferred call edges
are extracted internally but not stored by default yet.

## Usage

```sh
agraph index /path/to/repo
AGRAPH_OPENROUTER_API_KEY=... agraph embed --provider openrouter
agraph query "how does player input become canon" --retriever hybrid
agraph graph query "how does player input become canon" --depth 1
agraph graph deps void.runtime.turns --depth 2
agraph graph export query "how does player input become canon" --out graph.json
agraph cursor create "how does player input become canon" --project void
agraph deps void.runtime.turns
agraph path clients.web.main void.runtime.canon.xtdb
agraph path clients/web services/game_runtime --project void --systems
agraph report
```

Default XTDB storage lives at `.dev/agraph/xtdb`. Override with:

```sh
AGRAPH_XTDB_PATH=/tmp/agraph-xtdb agraph index /path/to/repo
```

## Install

Native macOS install from a clone:

```sh
scripts/install-macos.sh --install-deps
```

Docker:

```sh
docker build -t agraph:dev .
docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/agraph:/data" \
  agraph:dev project inspect /workspace/project.edn
```

Homebrew is planned for public releases. The formula template is in
`packaging/homebrew/Formula/agraph.rb.template`.

Embedding defaults:

- OpenRouter is preferred when `AGRAPH_OPENROUTER_API_KEY` or `OPENROUTER_API_KEY` is set
- OpenRouter default model: `openai/text-embedding-3-small`
- OpenAI fallback vars: `AGRAPH_OPENAI_API_KEY`, `OPENAI_API_KEY`
- OpenAI default model: `text-embedding-3-small`
- `bb query --retriever auto` falls back to lexical retrieval when no API key is configured

Graph HTML reports and canonical JSON exports are written to `.dev/reports/` by
default. Use `--out` to choose a path. The export contract is
`agraph.graph/v1`, with top-level `schema`, `title`, `nodes`, and `edges`.
Visualization should consume that shape instead of defining renderer-specific
export formats.

## Projects

AGraph can index multiple repos as one project and derive a higher-level system
graph from code dependencies plus config evidence.

```clojure
;; project.edn
{:id "void"
 :name "Void"
 :repos [{:id "void"
          :root "/Users/vegard/repos/void"
          :role :application}]}
```

```sh
agraph project inspect project.edn
agraph project index project.edn
agraph project infer project.edn
agraph map propose project.edn --out agraph.map.json
agraph map reject agraph.map.json external-api docs.xtdb.com --reason "Documentation reference"
agraph project maintain project.edn
agraph graph systems --project void --out .dev/reports/void-systems.html
agraph graph export systems --project void --out .dev/reports/void-systems.json
agraph query "projection gateway connections" --project void --retriever lexical
agraph docs candidates system:projection-gateway --project void
agraph docs attach agraph.map.json system:projection-gateway void:docs/projection-gateway.md --role contract --heading "Projection Gateway"
agraph context "projection gateway connections" --project void --budget 4000
agraph cursor create "projection gateway connections" --project void --map agraph.map.json
```

For workbench repos that wrap source repos in cached clones or task worktrees,
point the project at the workbench root instead of listing each repo:

```clojure
{:id "breyta-cli-release"
 :name "Breyta CLI release task"
 :workbench-root "/Users/vegard/repos/breyta-agentic-qa"
 :workbench-task "cli-release-20260616"}
```

`repos.json` defines the repo ids. If `:workbench-task` is present, AGraph uses
`.worktrees/<task>/<repo>` when it exists; otherwise it uses
`.workbench/repos/<repo>`.

## Agent Jobs

AGraph is organized around three jobs for coding agents:

- Build: inspect the project config, index repos, infer the system graph, and
  review the generated evidence. Commands: `agraph project inspect`, `agraph
  project index`, `agraph project infer`, `agraph graph systems`, `agraph graph
  export systems`.
- Query: use the graph while coding to answer dependency, ownership, and system
  interaction questions. Commands: `agraph query`, `agraph graph query`,
  `agraph context`, `agraph deps`, `agraph path`, `agraph docs for`.
- Maintain: keep the graph aligned with code and architecture changes. Commands:
  rerun `agraph project index`, rerun `agraph project infer`, use `agraph
  project maintain` to report orphaned systems, dangling edges, evidence that
  points at missing systems, and low-confidence inferred edges, and update
  `agraph.map.json` with accepted corrections. Use `agraph docs audit` to find
  stale or missing doc attachments.

The system graph is intentionally evidence-first. Generic extractors capture
code dependencies, manifests, config values, URLs, routes, ports, Kubernetes-ish
resources, and external API hosts. Generated system nodes are candidates.
Agents should use evidence to maintain `agraph.map.json`, which is the durable
overlay for accepted system boundaries, rejected false positives, and
agent-discovered project-level relationships.

Agent usage should follow progressive disclosure. A fresh agent should start
with `agraph project inspect`, `agraph project maintain`, and a compact system
graph, then drill into `agraph query`, `agraph deps`, `agraph path`, graph
slices, and evidence only for the subsystem or task it is actively working on.
Avoid dumping the full graph unless the task explicitly needs broad inventory.
During coding tasks, agents should update the map when research reveals a stale
classification, missing connection, orphaned system, or false external API.
Use `agraph context` when handing graph evidence to an agent prompt; it returns a
budgeted packet of relevant entities, edges, snippets, warnings, and drilldown
commands instead of the whole graph.
Use `agraph cursor` for longer investigations where an agent should keep a
stable graph basis and progressively call `show`, `open`, `expand`, `docs`, and
`search` against compact cursor packets.

## Development

See [docs/dependencies.md](docs/dependencies.md) and
[docs/distribution.md](docs/distribution.md). The canonical graph export schema
is documented in [docs/graph-export.md](docs/graph-export.md). The editable
system map overlay is documented in [docs/system-map.md](docs/system-map.md).
Context packets and doc attachments are documented in
[docs/context.md](docs/context.md). XTDB valid-time storage and read contexts are
documented in [docs/bitemporal-core.md](docs/bitemporal-core.md).

```sh
bb test
bb lint
bb format:check
```
