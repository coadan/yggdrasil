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
- Search availability: a cold service, missing index, unavailable graph store,
  active sync, or active embedding job routes queries to bounded filesystem
  search instead of making agents wait for Yggdrasil to become ready. The
  response states when evidence is degraded; see the
  [query availability contract](docs/context.md#search-availability-contract).
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
The filesystem deadline applies to the whole project, so repository count does
not multiply the worst-case wait; responses explicitly warn when the bound may
have made filesystem evidence incomplete.
Cold enriched-query caches follow the same contract: the first query returns
filesystem results while one deduplicated background warmup prepares the richer
path for later queries. A reachable but slow enriched query is also bounded;
the client returns filesystem evidence instead of inheriting the general
long-running request timeout. If the service is unavailable, the first fallback
also requests one deduplicated background start so later queries can recover
without a separate warm-up command. If graph storage is locked or cannot be
opened, acquisition fails immediately and the reachable service returns
filesystem evidence until storage becomes available again.

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

These tools overlap, but they are not interchangeable. The last column gives the
decision rule: when is Yggdrasil the better fit? It is a capability map, not a
leaderboard; links go to primary project documentation.

| Tool or lane | Core shape | Why choose Yggdrasil? |
|---|---|---|
| **Yggdrasil** | MIT, local XTDB project record: graph facts, corrections, memory, queues, plugins, and bounded evidence packets. | Use it when project context must persist across tasks, agents, and tools—with provenance, correction history, and local handoff. |
| Shell-only (`rg`, `git`, `find`) | Direct working-tree exploration. | Use Yggdrasil for repeated work, cross-session context, citations, and durable handoff; keep the shell as the universal baseline. |
| [Codebase Memory MCP](https://github.com/DeusData/codebase-memory-mcp) | Tree-sitter/AST graph exposed through MCP and CLI. | Use Yggdrasil when code structure is only part of the problem and docs, configuration, routes, evidence, corrections, and queues matter. Benchmarked in a deterministic lane, not a same-agent MCP run. |
| [Graphify](https://github.com/Graphify-Labs/graphify) | Graphs code and mixed sources; local deterministic code extraction plus model-assisted non-code extraction. | Use Yggdrasil when core facts must stay source-backed and auditable, with accepted corrections and handoff. Use Graphify for broad graph synthesis. Dedicated comparison lane; not a same-agent run. |
| Yggdrasil retrieval ablations | Lexical, semantic, graph-only, and local-vector controls. | Use Yggdrasil’s `auto` path for the default composed retrieval with explicit lexical fallback; use the ablations to diagnose misses. |
| [OpenWiki](https://github.com/langchain-ai/openwiki) | MIT local repo wiki, `AGENTS.md`/`CLAUDE.md` pointers, and scheduled updates. | Use Yggdrasil when agents need queryable evidence and structured memory, not only generated repo prose. Use OpenWiki alongside it for a maintained wiki. Researched, not benchmarked. |
| [Serena](https://github.com/oraios/serena) | MCP plus LSP/JetBrains symbol retrieval, editing, and refactoring. | Use Yggdrasil for durable project memory across tools and agents; use Serena for symbol-aware editing and refactoring. Researched, not benchmarked. |
| [Aider repository map](https://aider.chat/docs/repomap.html) | Graph-ranked repo map sent to a terminal agent. | Use Yggdrasil when context must survive the current session and include evidence, corrections, and handoff; use Aider for compact terminal-agent context. Researched, not benchmarked. |
| [Repomix](https://repomix.com/guide/) | One AI-friendly, git-aware repository snapshot. | Use Yggdrasil for bounded, relevant retrieval and repeatable citations; use Repomix for a one-shot full-repository export. Researched, not benchmarked. |
| [Sourcegraph Code Search and Deep Search](https://sourcegraph.com/docs/code-search) | Hosted cross-repository search and navigation. | Use Yggdrasil when project state should remain local, provider-agnostic, and auditable; use Sourcegraph for hosted organization-scale cross-repo search. Researched, not benchmarked. |
| [GitNexus](https://github.com/nxpatterns/gitnexus) | Zero-server client-side code graph and graph-RAG surface. | Use Yggdrasil when graph results need XTDB history, multi-source facts, corrections, and queues; use GitNexus for browser-first graph exploration. Researched, not benchmarked. |

## Open Platform And Growing Evidence

Yggdrasil is [MIT licensed](LICENSE) and fully open: the source, contracts,
CLI, MCP surface, local queue, plugins, and benchmark suites are inspectable
and runnable locally. Providers are optional; project state stays local.

The benchmark corpus replays real OSS issue, pull-request, and commit work from
[Axios](https://github.com/axios/axios), [Bootstrap](https://github.com/twbs/bootstrap),
[Dapper](https://github.com/DapperLib/Dapper), [Flask](https://github.com/pallets/flask),
[Graphify](https://github.com/Graphify-Labs/graphify), [JUnit](https://github.com/junit-team/junit-framework),
[OpenTelemetry](https://github.com/open-telemetry/opentelemetry-collector),
[OpenTelemetry Contrib](https://github.com/open-telemetry/opentelemetry-collector-contrib),
[Supabase Postgres](https://github.com/supabase/postgres), and [Terraform AWS VPC](https://github.com/terraform-aws-modules/terraform-aws-vpc).
Each case starts before the fix, hides the fixing diff, and scores localization,
citations, and—when configured—patch behavior. These repositories are test
inputs, not endorsements.

Their issues also expose gaps that drive improvements to Yggdrasil’s extractors,
retrieval, and agent workflows.

This is a growing corpus, not universal coverage. New languages, source kinds,
and architecture shapes will be added over time. See [benchmarks/](benchmarks/)
and [docs/benchmarking.md](docs/benchmarking.md); generated reports stay under
`.dev/ygg/`.

Current evidence snapshot (2026-07-14):

- **Full claim lane:** `bb bench:claim-full --check-only` passed 16/16 cases
  across 10 repositories and 12 source-kind groups: file-recall@10 0.97, MRR
  0.74, evidence citation 1.00, expected-evidence citation 0.95. This is a
  baseline-quality claim, not a universal superiority claim.
- **Docs lane:** `bb bench:docs-claim --check-only` passed four cases; docs claim
  readiness is supported.
- **Headline comparison:** the five-case shell-only comparison is inconclusive.
  Localization and evidence were unchanged; elapsed time improved by 247 ms,
  but result-health metrics regressed and token/cost telemetry was unavailable.

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
