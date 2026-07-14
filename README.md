<p align="center">
  <img src="ygg.png" alt="Yggdrasil" width="360">
</p>

# Yggdrasil

[![CI](https://github.com/coadan/yggdrasil/actions/workflows/ci.yml/badge.svg)](https://github.com/coadan/yggdrasil/actions/workflows/ci.yml)

Yggdrasil is a fully open, MIT-licensed, local, auditable codebase memory for
coding agents. It builds a reviewable graph of source files, dependencies,
routes, configuration, docs, accepted corrections, and project memory. Queries
return bounded evidence packets so an agent can inspect relevant context without
loading the whole repository.

Yggdrasil stores concrete facts first. Architecture, ownership, and other
project meaning enter through auditable corrections and reviewed metadata, not
hard-coded guesses based on names or paths.

## Project Status

Yggdrasil is in active early development. Data shapes and commands may change
without a compatibility layer before the first tagged release. The supported
ways to try it today are a source checkout or a locally built Docker image;
prebuilt binaries and a Homebrew release are not published yet.

The source wrapper is exercised on macOS and Ubuntu. Native Windows is not
currently a supported host; use WSL or Docker there.

## What Yggdrasil Provides

- Local project memory: graph facts, correction facts, activity, and reviewed
  memories stay in project-owned storage instead of one agent session.
- Evidence before interpretation: Yggdrasil stores concrete repository facts and
  accepted corrections, then uses graph structure and retrieval evidence to help
  agents decide what to read next.
- Compact agent context: `ygg query` returns bounded, graph-grounded evidence
  packets instead of dumping the whole repository into a prompt.
- Search availability: a cold service, missing index, active sync, or active
  embedding job routes queries to bounded filesystem search instead of making
  agents wait for Yggdrasil to become ready. The response states when evidence
  is degraded; see the [query availability contract](docs/context.md#search-availability-contract).
- Reviewable trust: answers can cite the files, rows, memories, and corrections
  that supported them.
- Measured claims: improvements in speed, cost, or effectiveness belong in
  benchmark reports, not unchecked product copy.

## Install And Try

Native source use requires JDK 21 or newer, the Clojure CLI, Git, and Python 3.
See [Dependencies](docs/dependencies.md) for optional tools and feature-specific
requirements.

Clone Yggdrasil and put its source entrypoints on your shell path:

```sh
git clone https://github.com/coadan/yggdrasil.git
cd yggdrasil
export PATH="$PWD/bin:$PATH"
ygg help
```

Index another repository and run the first query:

```sh
ygg init /absolute/path/to/repo --project my-project --sync --no-input
ygg query "where is auth handled" --project my-project
```

`init` starts the local service when needed, registers the project centrally,
writes a small `.ygg/project.edn` reference in the indexed repository, and
builds the default query index. Project graph state, corrections, memory,
queues, and activity live under `YGG_STORAGE_ROOT` or
`~/.local/share/ygg/projects/<project-id>/`.

Initial indexing is enrichment, not a prerequisite for search. A query issued
from the repository while the service is starting—or from another terminal
while indexing or embedding is active—returns bounded filesystem evidence and
identifies that fallback in its output. As durable facts and embeddings become
available, the same `ygg query` command automatically uses richer retrieval.

If you need an explicit editable project config, keep it separate from the
generated project reference:

```sh
ygg init /absolute/path/to/repo --project my-project \
  --out /absolute/path/to/repo/.ygg/config.edn --no-input
ygg sync /absolute/path/to/repo/.ygg/config.edn --check --query-index
ygg query "where is auth handled" --project my-project
```

To start the server automatically when you log in on macOS:

```sh
ygg service start-at-login enable
```

In an interactive terminal, `init` guides repository selection, assistant
integration, login startup, maintenance, and initial indexing. Agents and
scripts can pass the same choices explicitly:

```sh
ygg init /absolute/path/to/repo --project my-project \
  --harness codex --hooks --skill --mcp \
  --maintenance harness --sync --no-input
```

Use `--maintenance deepseek` or `--maintenance openrouter` to run maintenance
with a DeepSeek V4-compatible API executor instead of the assistant harness.
Use `--no-input` to force non-interactive behavior.

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
Any vector index used for retrieval is rebuildable; XTDB remains the durable
record for graph facts, corrections, memories, and embedding rows.

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
provider, and pass the same provider when querying that lane. When
`YGG_OPENROUTER_API_KEY` or `YGG_OPENAI_API_KEY` is present, `auto` retrieval
uses that remote embedding provider by default instead of starting the local
worker. Set `YGG_EMBEDDING_PROVIDER=local` to force the local lane. Remote
embedding requests default to a 30s request timeout and one retry; tune with
`YGG_EMBEDDING_REQUEST_TIMEOUT_MS` and `YGG_EMBEDDING_MAX_RETRIES`.

```sh
YGG_OPENAI_API_KEY=... ygg embed --project my-project --provider openai
ygg query "where is auth handled" --project my-project --provider openai

YGG_OPENROUTER_API_KEY=... ygg embed --project my-project --provider openrouter
ygg query "where is auth handled" --project my-project --provider openrouter
```

## Core Ideas

- Reliability before enrichment: repository search remains usable while
  Yggdrasil starts, indexes, embeds, or rebuilds; graph and semantic evidence
  improve the answer over time without becoming a query availability gate.
- Real systems first: Yggdrasil looks beyond source files to the repo evidence
  agents need for real maintenance work. Use
  `ygg sync coverage --project my-project --json` for the current support
  breakdown.
- Architecture and tribal knowledge: accepted corrections, boundaries, and
  useful context become part of the project record instead of disappearing with
  one agent session.
- Easy upkeep: project knowledge has to stay cheap to review and update, or it
  stops helping in complex environments.
- Progressive disclosure: agents start with a compact view, then open more
  detail only when the current task needs it.
- Local handoff: queued work moves through local state with explicit results
  that can be reviewed before they change project memory.
- Measured claims: improvements in speed, cost, or effectiveness point to
  repeatable comparisons.

## How Yggdrasil Compares

Yggdrasil overlaps with several code-intelligence, documentation, and agent
context tools, but they do not all solve the same problem. The useful
distinction is what becomes durable, how evidence reaches an agent, and whether
the system is an auditable project record or a session-time context helper.
This is a capability map, not a leaderboard. Feature descriptions link to the
primary project documentation. “Benchmarked” means that a Yggdrasil benchmark
lane exists; it does not imply a same-agent, apples-to-apples win.

| Tool or lane | Primary artifact and delivery | Best fit | Difference from Yggdrasil | Status in this project |
|---|---|---|---|---|
| **Yggdrasil** | MIT-licensed local project record: XTDB graph facts, file/run bundles, correction and memory facts, queues, plugins, and report data; `ygg query` returns bounded evidence packets. | Durable, auditable, provider-agnostic project memory and agent handoff. | — | System under test; full and quick claim lanes. |
| Shell-only (`rg`, `git`, `find`, and normal build tools) | Working-tree files and command output, with no shared indexed project record. | Universal, low-dependency exploration and the fairest baseline for agent work. | Yggdrasil adds reusable structured evidence, retrieval, correction history, and queue state while keeping shell proof available. | Measured baseline in shell-only versus Yggdrasil runs. |
| [Codebase Memory MCP](https://github.com/DeusData/codebase-memory-mcp) | Persistent Tree-sitter/AST knowledge graph exposed through MCP and CLI structural queries. | Fast code-structure, call-chain, impact, and navigation queries. | Yggdrasil covers a broader evidence plane—docs, routes, configuration, dependencies, corrections, bitemporal history, and handoff—not only structural code intelligence. | Deterministic comparison lane; not the same as giving an LLM its MCP tools during an agent run. |
| [Graphify](https://github.com/Graphify-Labs/graphify) | Queryable graph for code, SQL, R, shell, docs, papers, images, and video; code extraction is local and deterministic, while non-code extraction can use a model. | Mixed-corpus graph exploration through its CLI, skill, or MCP surface. | Yggdrasil keeps concrete parser/evidence rows and accepted semantic corrections auditable in the core; Graphify focuses on graph synthesis and agent-facing graph queries. | Dedicated Graphify comparison lane and historical OSS replay; not a same-agent comparison. |
| Yggdrasil retrieval ablations | Lexical-only, semantic-only, graph-only, and local `sentence-transformers` vector workers isolate one recall signal at a time. | Diagnose whether a miss comes from vocabulary, embeddings, ranking, or graph evidence. | Yggdrasil’s default `auto` path composes the available signals and reports lexical fallback explicitly; single-signal lanes are controls, not the product default. | Benchmarked ablations. |
| [OpenWiki](https://github.com/langchain-ai/openwiki) | MIT CLI that writes and maintains a local `openwiki/` repository wiki, updates `AGENTS.md`/`CLAUDE.md`, and can run scheduled CI updates. | Human-readable generated documentation and durable repo orientation. | OpenWiki makes an agent-maintained wiki the main memory surface; Yggdrasil’s primary record is source-backed facts, graph relationships, corrections, and bounded evidence packets. They can complement each other. | Researched; not yet Yggdrasil-benchmarked. |
| [Serena](https://github.com/oraios/serena) | Open-source MCP toolkit backed by language servers or a JetBrains plugin for symbol retrieval, editing, refactoring, debugging, and memory. | IDE-like semantic editing and symbol-aware agent operations. | Yggdrasil is the evidence and project-memory plane, not a symbol editor or refactoring engine. | Researched; not yet Yggdrasil-benchmarked. |
| [Aider repository map](https://aider.chat/docs/repomap.html) | A compact, graph-ranked map of repository files, symbols, and signatures sent to a terminal coding agent. | Session-level context for an agent that is already editing a local repository. | Yggdrasil is agent-agnostic and persists project state, evidence provenance, corrections, and cross-repo relationships instead of rebuilding a session map. | Researched; not yet Yggdrasil-benchmarked. |
| [Repomix](https://repomix.com/guide/) | A git-aware, AI-friendly snapshot that packs a repository into one file with token counting and multiple output formats. | One-shot full-context analysis, review, planning, or documentation generation. | Yggdrasil uses bounded retrieval and durable indexed facts rather than repeatedly sending the whole repository as one prompt artifact. | Researched; not yet Yggdrasil-benchmarked. |
| [Sourcegraph Code Search and Deep Search](https://sourcegraph.com/docs/code-search) | Hosted/team code search and navigation across repositories, branches, revisions, and code hosts, with an agentic search surface. | Organization-scale cross-repository search and code intelligence. | Yggdrasil is local-first and project-owned, with provider-agnostic storage and local handoff; this is a different deployment and governance tradeoff. | Researched; not yet Yggdrasil-benchmarked. |
| [GitNexus](https://github.com/nxpatterns/gitnexus) | A zero-server, client-side knowledge graph with an interactive graph-RAG exploration surface. | Interactive code-graph exploration without a hosted server. | Yggdrasil emphasizes durable XTDB facts, corrections, audit history, multi-source evidence, and maintenance queues rather than a browser-first graph view. | Researched; not yet Yggdrasil-benchmarked. |

## Open Platform And Growing Evidence

Yggdrasil is [MIT licensed](LICENSE) and a fully open platform. The source,
CLI, graph and evidence contracts, local queue, retrieval lanes, MCP surface,
extractor/report plugin boundaries, and benchmark definitions are inspectable
and runnable locally. External model and embedding providers are optional
lanes; no hosted provider is required to own project state.

The openness is also part of the testing loop. Yggdrasil’s benchmark corpus
replays real historical issue, pull-request, and commit work from other open
source projects. Cases start from a pre-fix checkout, expose only the task
input, withhold the fixing diff, and score localization, evidence citations,
and—when configured—patch behavior. The current full corpus includes [Axios](https://github.com/axios/axios),
[Bootstrap](https://github.com/twbs/bootstrap), [Dapper](https://github.com/DapperLib/Dapper),
[Flask](https://github.com/pallets/flask), [Graphify](https://github.com/Graphify-Labs/graphify),
[JUnit](https://github.com/junit-team/junit-framework), [OpenTelemetry Collector](https://github.com/open-telemetry/opentelemetry-collector),
[OpenTelemetry Collector Contrib](https://github.com/open-telemetry/opentelemetry-collector-contrib),
[Supabase Postgres](https://github.com/supabase/postgres), and [Terraform AWS VPC](https://github.com/terraform-aws-modules/terraform-aws-vpc).
These projects are evaluation inputs, not endorsements or claims that
Yggdrasil is better than them.

Benchmark evidence is a growing corpus, not a promise of universal coverage.
New languages, source kinds, and architecture shapes will be added over time.
The tracked suites and gate definitions live in [benchmarks/](benchmarks/) and
[docs/benchmarking.md](docs/benchmarking.md); generated reports stay under
`.dev/ygg/`.

Current evidence snapshot (2026-07-14):

- `bb bench:claim-full --check-only` passed 16/16 cases from the [full historical
  replay suite](benchmarks/historical-replay-full.edn) across 10 repositories
  and 12 declared source-kind groups. The report is claim-ready
  for the measured problem and architecture classes, with file-recall@10 0.97,
  MRR 0.74, evidence-citation 1.00, and expected-evidence-citation 0.95. This
  is a Yggdrasil baseline-quality claim, not a claim of superiority over every
  comparison tool.
- `bb bench:docs-claim --check-only` passed the docs-handling gate across four
  documentation cases. The full lane also reports docs claim readiness as
  supported.
- The five-case headline shell-only comparison is currently inconclusive:
  localization and evidence metrics were unchanged, elapsed time improved by
  247 ms, several result-health metrics regressed, and token/cost telemetry was
  unavailable. It does not support a broad efficiency-win claim.
- Codebase Memory, Graphify, local-vector, lexical, semantic, and graph-only
  runs are explicit comparison or ablation lanes. OpenWiki, Serena, Aider,
  Repomix, Sourcegraph, and GitNexus are researched here but do not yet have
  Yggdrasil head-to-head artifacts. Claims should be refreshed as the corpus and
  lanes grow.

## Main Workflows

- Sync: keep the graph aligned with the repo, inspect freshness, check for
  drift, and maintain accepted corrections.
- Query: answer focused graph-grounded questions.
- Report and view: generate local HTML and JSON graph exports for operators and
  downstream tools.
- Extend: add extractor plugins for project-specific file families and report
  plugins for generated dashboards.
- Hand off work: enqueue bounded maintenance packets, claim them from the local
  queue, validate results, and apply accepted corrections.

## Develop

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, design boundaries, and pull
request expectations. First project setup is always `ygg init`; there is no
separate top-level setup command.

Local verification:

```sh
bb test
bb lint
bb format:check
bb report-ui:test
bb report-ui:build
bb v1:smoke
bb v1:gate
```

Native server build path:

```sh
bb graalvm:uber
bb graalvm:check
bb graalvm:native
bb graalvm:docker-check
bb graalvm:docker-native
```

Project agent setup is noun-scoped: use `ygg agent install` and
`ygg agent uninstall`, not top-level `ygg install` or `ygg uninstall` wrappers.

## Detailed Docs

- [Dependencies](docs/dependencies.md)
- [Distribution](docs/distribution.md)
- [System corrections](docs/system-map.md)
- [Project memory](docs/memory.md)
- [Context packets and doc attachments](docs/context.md)
- [Graph export schema](docs/graph-export.md)
- [Extractor plugins](docs/extractor-plugins.md)
- [Plugin packages](docs/plugin-packages.md)
- [Report plugins](docs/report-plugins.md)
- [Parser workers](docs/parser-workers.md)
- [Index maintenance worker](docs/index-maintenance-worker.md)
- [Bitemporal XTDB core](docs/bitemporal-core.md)
- [Benchmarking](docs/benchmarking.md)
- [Agent efficiency study](docs/agent-efficiency-study.md)

## Community And Security

Yggdrasil is [MIT licensed](LICENSE). Use the issue templates for bugs and
feature proposals, read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull
request, and report vulnerabilities through the private process in
[SECURITY.md](SECURITY.md).
