# Benchmarks

`claim-quick.json` contains reviewable non-synthetic inputs: repository and
source URLs, pinned pre-fix and fixing revisions, issue queries, source-kind
tags, manually assigned problem and architecture classes, and curated
localization paths. Preparation verifies every curated path against the actual
upstream diff before a case can run.

`dogfood-replay.json` applies the same protocol to eight real fixes from
`breyta-workbench` and `void`. It is the tracked iteration suite for changes to
ranking and extractor plugins; local working copies of those repositories are
not benchmark inputs.

Generated checkouts, indexes, result packets, and reports belong under
`.dev/bench/`.

The developer-only `yggbench` runner records:

- a deterministic, match-count-ranked raw-ripgrep file and timing lane;
- candidate relevance, citations, and requested/active retrieval mode;
- full, no-op, and one-file incremental indexing;
- repeated fresh-process query latency;
- configured embedding identity, vector coverage, and command-file hashes
  without recording credentials.

Candidate-versus-legacy comparisons should retain the legacy binary hash and
revision beside this report. Hybrid and extractor-plugin results are explicit
ablation runs and are not the default should-win lane.

Benchmark configs mechanically ignore only their injected `.ygg/config.json`,
so configuration text cannot contaminate configured lanes.
`embedding-openrouter.json`, `embedding-local-command.json`,
`embedding-ollama.json`, and `embedding-ollama-qwen3-4b.json` define remote,
self-contained local, resident-small, and resident-large embedding lanes.

`extractor-parity.json` is the machine-checked inventory of legacy extractor
kinds. It distinguishes structured coverage from generic text search and
retired binary inventory; see [the parity notes](../docs/extractor-parity.md).
`legacy-search-parity.json` drives the exhaustive end-to-end gate for all 95
legacy kinds, including dotenv redaction and secret-material exclusion.
