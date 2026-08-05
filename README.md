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

It is open source, MIT licensed, and built for a short iteration loop: search a
working tree directly, then keep searching current files without a separate
setup or refresh step.

## What Yggdrasil provides

- **Local, auditable search.** Repository text, extractor records, vectors,
  diagnostics, and index runs stay in a central SQLite database on your
  machine.
- **Bounded cited evidence.** Every result carries a repository-relative path
  and line range instead of asking an agent to ingest the whole repository.
- **Progressive disclosure.** The highest-ranked files include compact
  excerpts sharing a bounded context budget; subsequent bounded candidate paths
  remain available for focused follow-up without another broad result dump.
- **Fast iteration.** Search detects Git HEAD and working-tree drift, refreshes
  only changed records, and reuses existing vectors before returning results.
- **Explicit retrieval state.** `auto` uses hybrid retrieval when complete
  embeddings are available and reports why it fell back to lexical search when
  they are not.
- **Parser-owned extension.** Run-scoped JSONL extractor executables can emit
  structured records without adding their dependencies to core.

## Install and try

Install from source with Go 1.25 or newer:

```sh
go install github.com/coadan/yggdrasil/cmd/ygg@v0.2.0
```

Or download a matching binary and `SHA256SUMS` from the
[latest release](https://github.com/coadan/yggdrasil/releases/latest).

Search any Git checkout:

```sh
ygg search "where is request routing configured" /path/to/repository
ygg status --root /path/to/repository
```

Search always writes a JSON envelope. Result records are under `data.records`;
additional ranked paths, when present, are under `data.morePaths`.

Search uses the grep-shaped form `ygg search PATTERN [PATH]`. The default
`auto` mode sends plain pattern text through lexical and configured semantic
retrieval. `-F`/`--fixed-strings` makes the lexical clause an exact string;
`-E`/`--regexp` makes it a Go regular expression. These flags constrain only
lexical evidence. `--about TEXT` supplies semantic intent independently, so an
agent can combine an exact symbol or regular expression with a conceptual
description. `--mode lexical` remains deterministic and model-free.

The first search initializes the repository when needed. Use `ygg index` only
for an explicit rebuild, index-only refresh, or embedding maintenance.

For a mechanically bounded search, pass a repository subdirectory or file as
the optional path, such as `ygg search "request routing" src/app` or
`ygg search -F RegisterRoute src/app/router.go`. Yggdrasil reuses the
repository's one index, filters every retrieval lane to that scope, and keeps
citations repository-relative (`src/app/...`).

If `search` starts in a fresh linked worktree and a same-family index exists,
Yggdrasil snapshots and reconciles that index before answering. Every search
also refreshes modified, deleted, renamed, and untracked files when repository
state changed; unchanged searches use a lightweight freshness token and do not
rescan. A first search with no related index initializes the repository directly,
so `ygg index` is reserved for explicit rebuild, embedding, and maintenance
control rather than normal setup. Default `auto` and explicit `semantic`
searches complete missing configured embeddings before retrieval; explicit
`lexical` searches remain model-free.

The complete public surface is:

```text
ygg version
ygg index
ygg search
ygg status
ygg plugin check
```

Every operational command returns a stable versioned JSON envelope by default.
Search results include `data.queryPlan`, which records the resolved lexical
kind and pattern, semantic text and source, and repository-relative scope.
Indexing progress is emitted as `ygg.index.progress/v1` JSON lines on stderr,
including when `search` initializes, refreshes, or embeds an index. The final
`ygg.cli/v1` result remains the only value written to stdout.
Indexes live outside repositories under `$YGG_STORAGE_ROOT/indexes/`, or
`~/.local/share/ygg/indexes/` by default. `index --full` replaces an incompatible
or intentionally stale database; normal indexing is incremental.

`ygg status` reports the binary version, resolved user/repository
configuration paths, embedding source and coverage, Git-family identity, and
the number of related seed and retired indexes. Add `--check` to send one
bounded embedding request and report provider readiness; ordinary status
remains network-free.

Linked Git worktrees remain isolated without paying for a second full index.
On a worktree's first normal index, Yggdrasil snapshots an indexed sibling,
then content-hash reconciles the copy. Unchanged records and vectors are reused;
only the worktree's added, changed, deleted, or extractor-invalidated files are
processed. After a successful index, Yggdrasil removes same-family databases
whose Git worktrees are no longer registered and whose roots no longer exist.
Busy databases and any unmarked or foreign state are preserved. The JSON index
summary reports `seededFrom`, `reused`, and reclaimed index count/bytes.
If another process is actively indexing, default and lexical search degrade to
a live working-tree lexical scan and report `fallbackReason: "index-busy"`.
This avoids both an operational failure and stale deleted paths. Explicit
semantic and graph ablation modes report the busy index because they require
indexed evidence and must not silently change retrieval meaning.

## Optional extraction and semantic recall

User-level `~/.config/ygg/config.json` owns the default embedding provider for
every repository and linked worktree. Set `XDG_CONFIG_HOME` to relocate the
config root or `YGG_CONFIG` to select an exact file. Repository-local
`.ygg/config.json` owns repository-specific extractor plugins and ignore
settings; an explicit repository embedding overrides the user default, while
`"embedding": null` disables it for that repository. Core always emits
mechanically chunked text records. Plugins add parser-owned records over a
bounded JSONL protocol and are started once per index run.

`search --mode auto` uses lexical and path recall by default. Configured
extractors add mechanically resolved local import/export neighbors, including
for exact identifiers without invoking an embedding model. When every current
semantic record has a vector for the configured embedding fingerprint, `auto`
also fuses semantic recall. Missing, incomplete, or failed semantic recall is
reported explicitly and lexical results remain available. Use `--mode lexical`,
`--mode semantic`, or `--mode graph` only for ablation and diagnosis.

The repository includes four optional, dependency-free extractor executables:

```sh
ygg plugin check markdown --root /path/to/configured/repository
```

`ygg-extract-markdown` emits sections and fences. The Go, TypeScript, and
Python extractors emit a compact per-file navigation record together with
imports and parser-bounded declaration signatures. Core chunks retain the
implementation text used for semantic retrieval; the compact navigation record
adds one mechanically bounded symbol and dependency summary per file. The
individual declaration and import facts remain lexical and graph evidence, so
they do not multiply local embedding work.
`ygg-extract-manifest` emits package, dependency, script, workspace, and
toolchain facts from `package.json`, `go.mod`, `Cargo.toml`, and
`pyproject.toml`. None are linked into the core CLI.

The optional `plugins/python/ygg-extract-python` adapter uses Python's
standard-library AST for imports and declarations. It requires a Python 3
runtime but no third-party packages.

The optional `plugins/jvm-dotnet/ygg-extract-jvm-dotnet` adapter extracts Java
and C# facts. Its parser bundle lives in a separate virtual environment and is
not part of the Go module graph.

The optional `plugins/clojure/ygg-extract-clojure` adapter emits one sparse,
bounded parser summary per Clojure file. It keeps namespaces, requires, and
top-level definitions searchable without creating one vector per parser fact.

The optional `plugins/terraform` adapter uses HashiCorp's HCL parser for blocks,
attributes, and expression traversals. Its dependency graph is confined to the
plugin module.

Semantic recall can run entirely locally through
`plugins/embedding-local/ygg-embed-local`. The optional worker lazily loads a
Sentence Transformers model, normalizes its vectors, and stays alive across an
index or search run. Its Python, PyTorch, and model dependencies are isolated
from the Go binary; see the [local worker setup](plugins/embedding-local/README.md).

See [contracts](docs/contracts.md) for extractor, configuration, and result
schemas.

## Core ideas

- **Concrete facts first.** Core uses file discovery, file kinds, parser output,
  hashes, line ranges, and search scores. It does not infer project meaning from
  names, hosts, path vocabulary, or prose.
- **Secret-aware discovery.** Key and certificate material is excluded.
  Dotenv files retain searchable variable names while assigned values are
  redacted before chunking or plugin extraction.
- **Reliability before enrichment.** Lexical search remains available when
  embeddings are unconfigured, incomplete, or temporarily failing.
- **Progressive disclosure.** Search starts with compact citations; callers
  decide which source to open next.
- **Local-first operation.** Indexing and search need no hosted service,
  provider account, background process, or repository-local database.
- **Replaceable integrations.** Extractors and embeddings use explicit,
  provider-neutral subprocess or HTTP contracts.

## How Yggdrasil compares

This is a capability map, not a leaderboard.

| Tool or lane | Core shape | When Yggdrasil fits |
| --- | --- | --- |
| Shell-only (`rg`, `git`, `find`) | Direct working-tree exploration with no durable index. | Repeated repository search benefits from fast startup, stable JSON, and bounded line citations. The shell remains the universal baseline. |
| [Aider repository map](https://aider.chat/docs/repomap.html) | Graph-ranked repository map sent to a terminal agent. | A caller wants local query-driven evidence rather than one generated context map. |
| [Repomix](https://repomix.com/guide/) | One AI-friendly repository snapshot. | A caller needs bounded relevant records rather than a one-shot full-repository export. |
| [Serena](https://github.com/oraios/serena) | LSP/IDE-backed symbol retrieval, editing, and refactoring. | Search must stay a small standalone CLI; use Serena when symbol-aware editing is the actual requirement. |
| [Sourcegraph Code Search](https://sourcegraph.com/docs/code-search) | Hosted organization-scale code search and navigation. | Repository state and search should remain local and provider-independent; use Sourcegraph for hosted cross-repository scale. |
