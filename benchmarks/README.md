# Benchmarks

Tracked JSON files contain only reviewable non-synthetic inputs: repository
URLs, pinned revisions, issue queries, source-kind tags, and curated
localization paths.

Generated checkouts, indexes, result packets, and reports belong under
`.dev/bench/`.

The benchmark runner compares:

- raw ripgrep
- pinned Yggdrasil 1
- Yggdrasil 2 lexical
- Yggdrasil 2 hybrid
- extractor-plugin ablations

Release quality gates use file recall@10, MRR, noise@20, and path citations.
Performance gates use fresh-process search, full indexing, no-op indexing, and
one-file incremental indexing.
