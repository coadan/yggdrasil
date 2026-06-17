# AGraph

AGraph is a local-first repo graph indexer backed by XTDB. It is designed for
coding agents that need to work effectively on complex systems over time.

It starts with deterministic Clojure/ClojureScript/Go/JavaScript/TypeScript/
Python/Rust/SQL/style/EDN/Markdown indexing:

- namespaces, requires, vars, tests, Go packages, JS/TS modules, Python modules,
  source imports, Rust modules, and source items
- Markdown heading chunks and exact symbol mentions
- XTDB-backed files, runs, nodes, high-confidence edges, chunks, diagnostics, and query runs
- OpenRouter/OpenAI embedding-backed hybrid semantic query with graph expansion
- token-bounded context packets with attached architecture/docs snippets
- filesystem queue handoff for provider-agnostic agent packets
- dependency summaries, path search, and Markdown reports
- project-level system graphs, third-party API nodes, and maintenance findings

Default indexing persists high-confidence relations: definitions, namespace
requires, source imports, and Rust module declarations. Noisy inferred call
edges are extracted internally but not stored by default yet.

## Quickstart

```sh
agraph start . --project my-project
```

`start` is the shortest local setup path: it writes or reuses `project.edn`,
creates `agraph.map.json`, runs `sync --check`, imports local queue activity,
and writes an `agraph-out/` report bundle.

For separate steps:

```sh
agraph init . --project my-project --out project.edn
agraph sync project.edn --check --map agraph.map.json
agraph ask "where is auth handled" --project my-project --json
agraph explore create "where is auth handled" --project my-project --map agraph.map.json
agraph view systems --project my-project --format json
agraph report project.edn --map agraph.map.json --out agraph-out
agraph install-agent --platform codex --project --hooks
agraph watch project.edn --map agraph.map.json
agraph hook install project.edn --map agraph.map.json
```

Default XTDB storage lives at `.dev/agraph/xtdb`. Override with:

```sh
AGRAPH_XTDB_PATH=/tmp/agraph-xtdb agraph sync project.edn
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
- `bb ask --retriever auto` falls back to lexical retrieval when no API key is configured

Graph HTML reports and canonical JSON exports from `agraph view` are written to
`.dev/reports/` by default. Use `--out` to choose a path. The export contract is
`agraph.graph/v2`, with top-level `schema`, `title`, `nodes`, and `edges`.
Visualization should consume that shape instead of defining renderer-specific
export formats.

Optional embedding-backed retrieval:

```sh
AGRAPH_OPENROUTER_API_KEY=... agraph embed --provider openrouter
agraph ask "where is auth handled" --project my-project --retriever hybrid
```

## Projects

AGraph can index multiple repos as one project and derive a higher-level system
graph from code dependencies plus config evidence. The graph is designed to
emerge from durable facts: generated systems are neutral candidates until
relationships, metadata, or accepted corrections give them project meaning.

```clojure
;; project.edn
{:id "sample"
 :name "Sample"
 :repos [{:id "app"
          :root "/path/to/sample"
          :role :application}]}
```

```sh
agraph sync inspect project.edn
agraph sync add-repo project.edn /path/to/another-repo --repo cli --role tooling
agraph sync project.edn --map agraph.map.json --check
agraph sync coverage project.edn
agraph sync project.edn --query-index
agraph sync propose project.edn --out agraph.map.json
agraph sync ignore external-api docs.xtdb.com --map agraph.map.json --reason "Documentation reference"
agraph view systems --project sample --out .dev/reports/sample-systems.html
agraph view systems --project sample --format json --out .dev/reports/sample-systems.json
agraph report project.edn --map agraph.map.json --out agraph-out
agraph ask "api gateway connections" --project sample --retriever lexical
agraph sync docs candidates system:api-gateway --project sample
agraph sync docs attach system:api-gateway app:docs/api-gateway.md --map agraph.map.json --role contract --heading "API Gateway"
agraph explore create "api gateway connections" --project sample --map agraph.map.json
agraph sync check project.edn --map agraph.map.json --enqueue
agraph sync work list --status ready
```

Use `agraph start .` for one-command local setup when the default single-repo
config and report output are enough. Use `agraph init . --sync --map
agraph.map.json` when you want setup plus sync but no report bundle.

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
agraph sync add-repo project.edn /path/to/repo --repo billing-api --role application
agraph sync add-repo project.edn /path/to/cli
agraph sync project.edn
```

`sync add-repo` edits `project.edn` and prints the next sync command.

## Agent Jobs

AGraph is organized around two jobs for coding agents:

- Sync: keep AGraph aligned with the codebase. Use `agraph sync` to index
  concrete facts, refresh derived system candidates, check for drift, enqueue
  bounded maintenance work, and record accepted corrections in `agraph.map.json`
  or metadata.
- Ask and explore: use `agraph ask` for one-shot graph questions and
  `agraph explore` for longer investigations with a stable graph basis. Use
  `agraph view` only when a rendered or exported graph slice helps the task.

The system graph is intentionally evidence-first and emergent. Generic
extractors capture bounded, project-agnostic facts: file types, code
dependencies, manifests, config values, URLs, routes, ports, Kubernetes-ish
resources, container image producer/consumer evidence from Dockerfiles,
deployment manifests, and shell image build/runtime scripts, external API hosts,
topology, and metrics. Generated system nodes are neutral candidates, not
semantic claims derived from path-name rules or text matching. View exports
derive salience-ranked semantic connections and clusters from this raw evidence,
including `deploys` edges when a built image artifact is consumed by deployment
manifests or script-level runtime configuration. Use `--detail
primary|expanded|evidence|raw` to choose how much of the graph to expose.
Agents should use evidence to maintain
`agraph.map.json`, which is the durable correction layer for accepted system
boundaries, rejected false positives, visibility overrides, and
agent-discovered project-level relationships.

Mechanical code should stay small and extensible. Adding a file type should
mean adding a scanner kind and extractor adapter that emits canonical graph
rows. Open-ended semantic judgment, merging, classification, and
use-case-specific meaning belong in human or LLM-backed corrections where the
project state space is too large for fixed rules.

Agent usage should follow progressive disclosure. A fresh agent should start
with `agraph sync inspect`, `agraph sync check`, and a compact system view, then
drill into `agraph ask`, `agraph explore`, graph slices, and evidence only for
the subsystem or task it is actively working on.
Avoid dumping the full graph unless the task explicitly needs broad inventory.
Use `--detail primary` by default; move to `expanded`, then `evidence`, then
`raw` only when the current task needs more proof.
During coding tasks, agents should update the map when research reveals a stale
classification, missing connection, orphaned system, or false external API.
Use `agraph sync check --enqueue` when another agent, model, or human should
pick up bounded maintenance work from the filesystem queue. Maintenance
decisions and infrastructure review packets use the same `sync work` flow.
When `sync` is run with `--check` or `--enqueue`, it uses a graph-maintenance
index profile by default: files, graph nodes, edges, diagnostics, and bounded
file facts are updated, while heavyweight code/doc search rows are skipped. Add
`--query-index` to the same command when the run should also refresh searchable
chunks for `agraph ask`, `agraph explore`, embeddings, and context packets.
Use `agraph sync coverage project.edn` to audit source type support across the
project before adding a parser or changing scanner rules. The report separates
supported files, unsupported extensions, intentionally ignored lockfiles, active
extractor versions, and indexed diagnostics; `--json` emits the stable
`agraph.source-coverage/v1` shape for smoke reports or CI.
Use `agraph ask --json` when handing graph evidence to an agent prompt; it
returns a budgeted packet of relevant entities, edges, snippets, warnings, and
drilldown commands instead of the whole graph.
Use `agraph explore` for longer investigations where an agent should keep a
stable graph basis and progressively call `show`, `open`, `expand`, `docs`, and
`search` against compact packets.

Use `agraph install-agent --platform codex --project` to write concise project
guidance into `AGENTS.md`. Add `--hooks` to install Codex hook guidance. The
installer only edits marked AGraph sections, and `uninstall` removes those
sections.

Use `agraph-mcp --config project.edn --map agraph.map.json` when an MCP client
should call AGraph tools directly. The server exposes compact packet tools such
as `agraph_ask`, `agraph_explore_create`, `agraph_view_systems`, and
`agraph_work_pull`; it does not expose arbitrary SQL or implicit map mutation.

## Queue Handoff

The queue is a local JSON handoff contract, not a model runner. AGraph writes
queue items under `.dev/agraph/queue/ready`, agents claim them into `claimed`,
and completed items move to `done` with a result payload.

```sh
agraph sync check project.edn --map agraph.map.json --enqueue
agraph sync work list --status ready --project sample
agraph sync work show queue:abc123
agraph sync work pull --project sample --agent codex --lease-minutes 30
agraph sync work complete queue:abc123 --result result.json
agraph sync work apply queue:abc123 --map agraph.map.json
agraph sync work release queue:abc123 --reason "needs broader scope"
```

Queue item schema is `agraph.queue.item/v1`. The embedded `payload` is left
unchanged so the consumer can be Codex, another LLM, a script, CI, or a human
review tool. Semantic results should come back as explicit JSON patches or
findings. `sync work complete` records that result on the queue item. `sync work
apply` is the explicit mutation step for result schemas AGraph can validate; the
first supported apply path is `agraph.infra.review-result/v1`, which can add or
override reviewed system-map edges in `agraph.map.json`.

## Development

See [docs/dependencies.md](docs/dependencies.md) and
[docs/distribution.md](docs/distribution.md). The canonical graph export schema
is documented in [docs/graph-export.md](docs/graph-export.md). The editable
system map correction layer is documented in [docs/system-map.md](docs/system-map.md).
Context packets and doc attachments are documented in
[docs/context.md](docs/context.md). XTDB valid-time storage and read contexts are
documented in [docs/bitemporal-core.md](docs/bitemporal-core.md). Issue replay
benchmarking is documented in [docs/benchmarking.md](docs/benchmarking.md).

```sh
bb test
bb lint
bb format:check
```
