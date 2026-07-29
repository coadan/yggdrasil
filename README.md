<p align="center">
  <img src="ygg.png" alt="Yggdrasil" width="360">
</p>

# Yggdrasil

[![CI](https://github.com/coadan/yggdrasil/actions/workflows/ci.yml/badge.svg)](https://github.com/coadan/yggdrasil/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/coadan/yggdrasil)](https://github.com/coadan/yggdrasil/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Yggdrasil gives coding agents and humans fast, local search across a
repository. It turns source files, declarations, imports, and documentation
into compact results with exact path and line citations, so the next relevant
piece of code is quick to find and easy to verify.

It is open source, MIT licensed, and built for a short iteration loop: index a
working tree, search it immediately, and refresh only the files that changed.

## Project status

Yggdrasil is in active early development. The latest search-only release is
[`v0.2.0`](https://github.com/coadan/yggdrasil/releases/tag/v0.2.0), with
CGO-free binaries for macOS and Linux on arm64 and amd64. Data shapes and
commands may still change without compatibility layers while the focused
search workflow settles.

The previous XTDB implementation is preserved at
[`legacy-xtdb-final`](https://github.com/coadan/yggdrasil/tree/legacy/xtdb-final);
it is not migrated or loaded by the Go CLI.

## What Yggdrasil provides

- **Local, auditable search.** Repository text, extractor records, vectors,
  diagnostics, and index runs stay in a central SQLite database on your
  machine.
- **Bounded cited evidence.** Every result carries a repository-relative path
  and line range instead of asking an agent to ingest the whole repository.
- **Fast iteration.** Git-aware discovery, file-state fingerprints, bounded
  write batches, and fresh-process search keep full and incremental runs small.
- **Explicit retrieval state.** `auto` uses hybrid retrieval when complete
  embeddings are available and reports why it fell back to lexical search when
  they are not.
- **Parser-owned extension.** Run-scoped JSONL extractor executables can emit
  structured records without adding their dependencies to core.
- **Measured claims.** Performance and relevance claims belong to hash-bearing,
  replayable benchmark reports—not product anecdotes.

## Install and try

Install from source with Go 1.25 or newer:

```sh
go install github.com/coadan/yggdrasil/cmd/ygg@v0.2.0
```

Or download a matching binary and `SHA256SUMS` from the
[latest release](https://github.com/coadan/yggdrasil/releases/latest).

Index any Git checkout:

```sh
ygg index --root /path/to/repository
ygg search --root /path/to/repository "where is request routing configured"
ygg status --root /path/to/repository
```

The complete public surface is:

```text
ygg index
ygg search
ygg status
ygg plugin check
```

Pass `--json` for stable versioned envelopes. Indexes live outside repositories
under `$YGG_STORAGE_ROOT/indexes/`, or `~/.local/share/ygg/indexes/` by default.
`index --full` replaces an incompatible or intentionally stale database; normal
indexing is incremental.

## Optional extraction and semantic recall

An optional `.ygg/config.json` enables executable extractor plugins and one
embedding provider. Core always emits mechanically chunked text records.
Plugins add parser-owned records over a bounded JSONL protocol and are started
once per index run.

`search --mode auto` uses lexical and path recall by default. When every current
record has a vector for the configured embedding fingerprint, it fuses the
semantic lane with reciprocal-rank fusion. Missing, incomplete, or failed
semantic recall is reported explicitly and lexical results remain available.
Use `--mode lexical` or `--mode semantic` only for ablation and diagnosis.

The repository includes four optional, dependency-free extractor executables:

```sh
make build
bin/ygg plugin check markdown --root /path/to/configured/repository
```

`ygg-extract-markdown` emits sections and fences, `ygg-extract-go` uses the Go
parser for declarations and imports, and `ygg-extract-typescript` emits
top-level JavaScript/TypeScript declarations and module links.
`ygg-extract-manifest` emits package, dependency, script, workspace, and
toolchain facts from `package.json`, `go.mod`, `Cargo.toml`, and
`pyproject.toml`. None are linked into the core CLI. The
[legacy parity inventory](docs/extractor-parity.md) records structured coverage
and remaining gaps without treating generic text indexing as full parity.

The optional `plugins/python/ygg-extract-python` adapter uses Python's
standard-library AST for imports and declarations. It requires a Python 3
runtime but no third-party packages.

The optional `plugins/jvm-dotnet/ygg-extract-jvm-dotnet` adapter ports the
legacy tree-sitter worker facts for Java and C#. Its pinned parser bundle lives
in a separate virtual environment and is not part of the Go module graph.

The optional `plugins/terraform` adapter uses HashiCorp's HCL parser for blocks,
attributes, and expression traversals. Its dependency graph is confined to the
plugin module.

See [contracts](docs/contracts.md) for the extractor, configuration, and result
schemas. See [embedding parity](docs/embedding-parity.md) for the OpenRouter
configuration, verified smoke evidence, and remaining legacy differences.

## Core ideas

- **Concrete facts first.** Core uses file discovery, file kinds, parser output,
  hashes, line ranges, and search scores. It does not infer project meaning from
  names, hosts, path vocabulary, or prose.
- **Reliability before enrichment.** Lexical search remains available when
  embeddings are unconfigured, incomplete, or temporarily failing.
- **Progressive disclosure.** Search starts with compact citations; callers
  decide which source to open next.
- **Local-first operation.** Indexing and search need no hosted service,
  provider account, background process, or repository-local database.
- **Replaceable integrations.** Extractors and embeddings use explicit,
  provider-neutral subprocess or HTTP contracts.
- **Evidence before claims.** The shell baseline and Yggdrasil run against the
  same pinned pre-fix repository states.

## Benchmarks

`yggbench` is a separate developer binary, not a `ygg` command. Each tracked
case:

1. pins the parent revision before a real fix;
2. fetches the fixing revision only to verify curated changed-path ground truth;
3. indexes and searches without exposing the fixing diff;
4. measures full, no-op, and one-file incremental indexing;
5. measures fresh-process search, citations, recall, MRR, and result noise;
6. compares a mechanically match-count-ranked ripgrep lane; and
7. records candidate and suite hashes with environment details.

```sh
make benchmark-check
make benchmark-quick
```

The tracked replay corpus covers multiple languages, problem classes, and
architecture classes across real repositories. The
[dogfood protocol](docs/dogfood.md) additionally exercises
`breyta-workbench` and `void2`. These repositories are test inputs, not
endorsements, and results apply only to the exact binary, suite, and machine
recorded in a report.

See [benchmarking](docs/benchmarking.md) for methodology and report fields.

## How Yggdrasil compares

This is a capability map, not a leaderboard.

| Tool or lane | Core shape | When Yggdrasil fits |
| --- | --- | --- |
| Shell-only (`rg`, `git`, `find`) | Direct working-tree exploration with no durable index. | Repeated repository search benefits from fast startup, stable JSON, and bounded line citations. The shell remains the universal baseline. |
| [Aider repository map](https://aider.chat/docs/repomap.html) | Graph-ranked repository map sent to a terminal agent. | A caller wants local query-driven evidence rather than one generated context map. |
| [Repomix](https://repomix.com/guide/) | One AI-friendly repository snapshot. | A caller needs bounded relevant records rather than a one-shot full-repository export. |
| [Serena](https://github.com/oraios/serena) | LSP/IDE-backed symbol retrieval, editing, and refactoring. | Search must stay a small standalone CLI; use Serena when symbol-aware editing is the actual requirement. |
| [Sourcegraph Code Search](https://sourcegraph.com/docs/code-search) | Hosted organization-scale code search and navigation. | Repository state and search should remain local and provider-independent; use Sourcegraph for hosted cross-repository scale. |

## Development

```sh
make check
make release
make benchmark-quick
```

The core module has one direct dependency: the pure-Go SQLite implementation
providing SQLite, FTS5, and Vec1. Extractor plugins are separate Go modules so
parser dependencies do not enter the CLI.

Generated checkouts, indexes, and reports stay under `.dev/bench/`. See
[architecture](docs/architecture.md), [contracts](docs/contracts.md),
[supersession scope](docs/supersession.md), and
[dogfood evidence](docs/dogfood.md) for the maintained boundaries.
