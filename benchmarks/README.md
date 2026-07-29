# Benchmarks

`claim-quick.json` contains reviewable non-synthetic inputs: repository and
source URLs, pinned pre-fix and fixing revisions, issue queries, source-kind
tags, manually assigned problem and architecture classes, and curated
localization paths. Preparation verifies every curated path against the actual
upstream diff before a case can run.

Generated checkouts, indexes, result packets, and reports belong under
`.dev/bench/`.

The developer-only `yggbench` runner records:

- a deterministic, unranked raw-ripgrep file and timing lane;
- candidate lexical relevance and citations;
- full, no-op, and one-file incremental indexing;
- repeated fresh-process query latency.

Candidate-versus-legacy comparisons should retain the legacy binary hash and
revision beside this report. Hybrid and extractor-plugin results are explicit
ablation runs and are not the default should-win lane.
