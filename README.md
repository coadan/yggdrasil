# Yggdrasil

Yggdrasil is a local, search-only repository index. It is a single Go CLI
backed by SQLite and has no daemon, HTML UI, report generator, or general graph
analysis surface.

## Quick start

Build and index any Git checkout:

```sh
make build
bin/ygg index --root .
bin/ygg search --root . "where is request routing configured"
bin/ygg status --root .
```

The complete public surface is `index`, `search`, `status`, and `plugin check`.
Every result is a bounded record with a repository-relative path and line
citation. Pass `--json` for stable versioned envelopes.

Indexes live outside repositories under `$YGG_STORAGE_ROOT/indexes/`, or
`~/.local/share/ygg/indexes/` by default. Indexing is incremental; `--full`
replaces an incompatible or intentionally stale database. There is no daemon to
start and no repository-local generated database to clean up.

## Optional extraction and semantic recall

An optional `.ygg/config.json` enables executable extractor plugins and one
embedding provider. Core always emits mechanically chunked text records.
Plugins add parser-owned records over a bounded JSONL protocol and are started
once per index run. The reference Markdown plugin builds as
`bin/ygg-extract-markdown`.

`search --mode auto` uses lexical and path recall by default. When every current
record has a vector for the configured embedding fingerprint, it fuses the
semantic lane with reciprocal-rank fusion. Missing or failed semantic recall is
reported explicitly and lexical results remain available. Use `--mode lexical`
or `--mode semantic` only for ablation and diagnosis.

See [contracts](docs/contracts.md), [architecture](docs/architecture.md),
[supersession scope](docs/supersession.md), and
[benchmarking](docs/benchmarking.md). The
[dogfood protocol](docs/dogfood.md) records the real-repository release check.

## Development

```sh
make check
make release
make benchmark-quick
```

The benchmark harness is the separate `yggbench` developer binary; it does not
expand the `ygg` API. Generated checkouts, indexes, and reports stay under
`.dev/bench/`.

This implementation intentionally does not read or migrate the previous XTDB
state. The previous implementation is used only as a pinned benchmark baseline.
