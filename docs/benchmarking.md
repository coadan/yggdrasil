# Benchmarking

`yggbench` replays tracked localization questions against repositories checked
out at the exact parent revisions of real fixes. Every case records the upstream
issue or commit URL and fixing revision. Preparation fetches both revisions and
refuses the case unless every curated ground-truth path is changed by that
upstream fix. Ground truth, source kinds, problem classes, and architecture
classes are reviewable in `benchmarks/claim-quick.json`.

```sh
make build
bin/yggbench \
  -prepare \
  -suite benchmarks/claim-quick.json \
  -ygg bin/ygg \
  -out .dev/bench/report.json
```

Use `-cases id-one,id-two` for a bounded iteration. `-prepare` creates an
isolated checkout per case under `.dev/bench/repos`; it never changes a working
repository. Each candidate case starts with a fresh database, then measures a
full index, no-op index, one-file incremental index, and repeated fresh-process
searches in the selected retrieval mode. It also measures a single-process
ripgrep localization lane
ranked deterministically by mechanical match count, with path as the tie-break,
in a fresh process. Reports record the exact candidate and suite hashes plus Go,
Git, ripgrep, platform, and CPU-count context.

Use `-config` for either extractor or embedding configuration and `-mode` to
select `lexical`, `auto`, or `semantic`. Configured `auto` runs fail unless
every case has complete vectors and actually activates hybrid retrieval. The
report records aggregate and per-case vector coverage, embedding identity,
active mode, fallback state, and hashes for executable embedding command files.
The injected `.ygg/config.json` is excluded from discovery so configured and
unconfigured lanes index the same repository inputs.

Run `make benchmark-check` to fetch and verify every pinned revision and
ground-truth diff without paying the full indexing cost.

For the two dogfood repositories, `make benchmark-dogfood-check` verifies the
eight pinned replay cases and `make benchmark-dogfood` records their full
results in `.dev/bench/dogfood-report.json`. These use isolated, pinned
checkouts rather than whichever revisions happen to be present in local working
copies.

`make benchmark-dogfood-plugins` runs the same cases with
`benchmarks/dogfood-plugins.json`. The harness resolves each extractor
executable, writes the config only into its isolated checkout for the duration
of the case, and records the config and executable hashes. It refuses to
overwrite a checkout that already has `.ygg/config.json`. The default and plugin
reports are therefore an explicit ablation pair.

`make benchmark-dogfood-manifest` isolates the manifest adapter from the source
extractors using `benchmarks/dogfood-manifest.json`. Use it to measure manifest
startup/index cost without attributing Go or TypeScript records to that lane.

`make benchmark-python` runs a matched default-versus-Python-adapter ablation on
the two pinned Python cases. It is a focused extractor diagnostic, not the broad
claim lane.

`make benchmark-jvm-dotnet` runs the pinned .NET case with and without the
optional tree-sitter adapter. Run `make test-jvm-dotnet` after creating the
virtual environment described in the plugin README.

`make benchmark-terraform` runs the pinned Terraform architecture/config case
with and without the HCL adapter.

The semantic ablation targets use the complete tracked claim lane:

```sh
make benchmark-semantic-lexical
make benchmark-semantic-local-command
make benchmark-semantic-ollama
make benchmark-semantic-qwen
set -a; source /path/to/legacy-yggdrasil/.env; set +a
make benchmark-semantic-openrouter
```

The command lane expects the isolated environment from
`plugins/embedding-local/README.md`. The Ollama lanes expect `all-minilm` or
`qwen3-embedding:4b` to be installed and the local server to be running.
Model downloads and server lifecycle are intentionally outside Yggdrasil.

Reports include:

- file recall@10 and mean reciprocal rank;
- noise among the first 20 unique paths;
- path-and-line citation rate;
- full, no-op, and one-file index timing;
- fresh-process search p50 and p95;
- requested and active retrieval mode, fallback state, and vector coverage;
- match-count-ranked ripgrep recall, MRR, p50, and p95 as an explicit raw
  baseline;
- suite and candidate binary hashes;
- manually tagged repository, source-kind, problem-class, and
  architecture-class coverage.

The tracked suite has ten non-synthetic cases across seven repositories. The
multi-language OpenTelemetry case is intentionally expensive; excluding it for
a quick local pass must be disclosed with the report's measured coverage.
The separate dogfood replay suite has eight cases split evenly between
`breyta-workbench` and `void`, with manually tagged Go, shell, and TypeScript
coverage.
Generated reports are evidence for the exact binary and selected cases only,
not a general agent-efficiency claim.

Benchmark and release builds use `-buildvcs=false` with `-trimpath`, so candidate
hashes identify source behavior rather than the current dirty flag or commit
metadata.
