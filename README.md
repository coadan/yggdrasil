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
agraph query "where is the API gateway configured" --retriever hybrid
agraph graph query "where is the API gateway configured" --depth 1
agraph graph deps sample.api.gateway --depth 2
agraph graph export query "where is the API gateway configured" --out graph.json
agraph cursor create "where is the API gateway configured" --project sample
agraph deps sample.api.gateway
agraph path sample.web.main sample.api.gateway
agraph path clients/web services/api-gateway --project sample --systems
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
graph from code dependencies plus config evidence. The graph is designed to
emerge from durable facts: generated systems are neutral candidates until
relationships, metadata, or an accepted overlay gives them project meaning.

```clojure
;; project.edn
{:id "sample"
 :name "Sample"
 :repos [{:id "app"
          :root "/path/to/sample"
          :role :application}]}
```

```sh
agraph project inspect project.edn
agraph project add-repo project.edn /path/to/another-repo --repo cli --role tooling --infer
agraph project index project.edn
agraph project infer project.edn
agraph map propose project.edn --out agraph.map.json
agraph map reject agraph.map.json external-api docs.xtdb.com --reason "Documentation reference"
agraph project maintain project.edn --map agraph.map.json
agraph graph systems --project sample --out .dev/reports/sample-systems.html
agraph graph export systems --project sample --out .dev/reports/sample-systems.json
agraph query "api gateway connections" --project sample --retriever lexical
agraph docs candidates system:api-gateway --project sample
agraph docs attach agraph.map.json system:api-gateway app:docs/api-gateway.md --role contract --heading "API Gateway"
agraph context "api gateway connections" --project sample --budget 4000
agraph cursor create "api gateway connections" --project sample --map agraph.map.json
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

For ordinary project configs, repos can be folded in incrementally:

```sh
agraph project add-repo project.edn /path/to/repo --repo billing-api --role application
agraph project add-repo project.edn /path/to/cli --infer
```

`--infer` indexes only the added repo and then refreshes the project system
graph. Without `--infer`, the command edits `project.edn` and prints the next
index/infer/maintain commands.

## Agent Jobs

AGraph is organized around three jobs for coding agents:

- Build: inspect the project config, index repos, infer the system graph, and
  review the generated evidence. Commands: `agraph project inspect`, `agraph
  project index`, `agraph project infer`, `agraph graph systems`, `agraph graph
  clusters`, `agraph graph export systems`.
- Query: use the graph while coding to answer dependency, ownership, and system
  interaction questions. Commands: `agraph query`, `agraph graph query`,
  `agraph context`, `agraph deps`, `agraph path`, `agraph docs for`.
- Maintain: keep the graph aligned with code and architecture changes. Commands:
  rerun `agraph project index`, rerun `agraph project infer`, use `agraph
  project maintain --json` to get orphaned systems, noisy visible edges,
  cluster bridges, likely false external APIs, and focused decision ids, then
  update `agraph.map.json` with accepted corrections. Use `agraph docs audit`
  to find stale or missing doc attachments. Maintain output includes graph-basis,
  scale/noise ratios, top hubs, and fold-in actions so agents can make small
  corrections during normal work instead of waiting for a full remap. Pass
  `--map agraph.map.json` so accepted rejects are not reported again.

The system graph is intentionally evidence-first and emergent. Generic
extractors capture bounded, project-agnostic facts: file types, code
dependencies, manifests, config values, URLs, routes, ports, Kubernetes-ish
resources, external API hosts, topology, and metrics. Generated system nodes are
neutral candidates, not semantic claims derived from path-name rules or text
matching. Graph exports derive salience-ranked semantic connections and clusters
from this raw evidence. Use `--detail primary|expanded|evidence|raw` to choose
how much of the graph to expose. Agents should use evidence to maintain
`agraph.map.json`, which is the durable overlay for accepted system boundaries,
rejected false positives, visibility overrides, and agent-discovered
project-level relationships.

Mechanical code should stay small and extensible. Adding a file type should
mean adding a scanner kind and extractor adapter that emits canonical graph
rows. Open-ended semantic judgment, merging, classification, and
use-case-specific meaning belong in human or LLM-backed overlays where the
project state space is too large for fixed rules.

Agent usage should follow progressive disclosure. A fresh agent should start
with `agraph project inspect`, `agraph project maintain`, and a compact system
graph, then drill into `agraph query`, `agraph deps`, `agraph path`, graph
slices, and evidence only for the subsystem or task it is actively working on.
Avoid dumping the full graph unless the task explicitly needs broad inventory.
Use `--detail primary` by default; move to `expanded`, then `evidence`, then
`raw` only when the current task needs more proof.
During coding tasks, agents should update the map when research reveals a stale
classification, missing connection, orphaned system, or false external API.
If classifier help is needed, use `agraph classify decision <id> --project ID`
on one maintenance decision from `agraph project maintain --json`; do not send
the whole graph for classification.
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
