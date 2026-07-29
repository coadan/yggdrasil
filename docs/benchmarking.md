# Benchmarking

`yggbench` replays tracked localization questions against repositories checked
out at the exact parent revisions of real fixes. Ground truth, source kinds,
problem classes, and architecture classes are reviewable in
`benchmarks/claim-quick.json`.

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
lexical searches. It also captures an unranked ripgrep localization lane.

Reports include:

- file recall@10 and mean reciprocal rank;
- noise among the first 20 unique paths;
- path-and-line citation rate;
- full, no-op, and one-file index timing;
- fresh-process search p50 and p95;
- suite and candidate binary hashes;
- manually tagged repository, source-kind, problem-class, and
  architecture-class coverage.

The tracked suite has ten non-synthetic cases across seven repositories. The
multi-language OpenTelemetry case is intentionally expensive; excluding it for
a quick local pass must be disclosed with the report's measured coverage.
Generated reports are evidence for the exact binary and selected cases only,
not a general agent-efficiency claim.
