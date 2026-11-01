# Local retrieval evaluation

On 2026-11-01, all 29 Git repositories directly under `~/repos` were
initialized with lexical indexes. Indexes are stored centrally; tested
repositories were not given local configuration files or database files.
Configured Qwen embedding completion was also attempted separately, with
per-repository coverage and provider failures retained in task evidence.

The local `main` checkout was behind the existing search-query-expressivity
branch. Integrating that branch restored support for the user's configured
query prefix and the public retrieval library. This was a local integration;
remote branch and release reconciliation remain separate.

## Findings and changes

The CLI refreshed an otherwise current index solely because semantic coverage
was incomplete, asking maintenance to complete every embedding before
returning results. Search now uses the current snapshot immediately. Content
refreshes initialize lexical records without embedding work. `ygg index`
remains the explicit way to complete configured embeddings. Regression tests
cover incomplete coverage, first search, and hybrid search after maintenance.

The embedding server repeatedly returned HTTP 400 with a terminated
llama-server process. Command exit status alone did not identify incomplete
semantic initialization: inspect `embeddingStatus` and coverage too. Batches
were reduced from 64 to 8 and then to 1 locally. Single-input batches completed
a previously failing small repository and all 225 semantic records in the
final Yggdrasil checkout. This does not establish that every server failure is
fixed. Some other repositories still ran in lexical fallback.

Ten manually source-verified intent probes covered four working repositories:
Yggdrasil, Workbench, Fleet, and Mimir. Both lexical and automatic modes found
an expected implementation in the first ten paths on 6/10 probes. MRR@10 was
0.387 lexical and 0.353 automatic. Automatic mode used a mixture of hybrid
retrieval and explicit lexical fallback; these are not a complete-coverage
model comparison. The secret-exclusion probe still missed its implementation
in the returned paths, and the busy-index and version-reporting probes placed
expected implementations below the first ten. These are unresolved quality
findings, not evidence of improved ranking or agent task completion.

A smaller-chunk experiment was discarded because incomplete embeddings and
provider crashes prevented a valid comparison. No ranking constants, semantic
word lists, or model defaults were changed based on this small sample.

## Identifier component experiment

SQLite's `unicode61` tokenizer keeps `pushCommand` as one token, so a plain
`push` query cannot match it. An experiment indexed case-delimited identifier
components in a separate FTS column, then compared lexical search against the
baseline on the same sources with one search iteration. It was rejected:

| Suite | Metric | Baseline | Candidate |
| --- | --- | --- | --- |
| Local probes | Hits in first 10 | 6/10 | 6/10 |
| Local probes | MRR@10 | 0.387 | 0.387 |
| `claim-quick` | File recall@10 | 0.830 | 0.810 |
| `claim-quick` | MRR | 0.790 | 0.789 |
| `dogfood-replay` | File recall@10 | 0.292 | 0.333 |
| `dogfood-replay` | MRR | 0.438 | 0.438 |
| `retrieval-ablation` | File recall@10 | 0.396 | 0.396 |
| `retrieval-ablation` | MRR | 0.240 | 0.249 |

Two probes moved down: busy-index from rank 14 to 17, and version-reporting
from rank 30 to outside the returned paths. The local index grew from 22 MB to
26 MB. Gains and losses were each confined to a few cases, so this is no
evidence of a broad improvement, and other weightings were not tried.
`breyta-session-replay` was not run because its checkouts were unavailable. The
patch and reports are under `.workbench/tasks/identifier-terms/evidence/`.

## Reproduction and limits

`tools/retrieval-quality.py` accepts an external JSON suite of objects with
`id`, `repo`, `query`, and `expectedPaths`. It verifies expected files, records
source and binary hashes and Git revisions, and saves full result envelopes,
first relevant file ranks, truncated MRR, and hit counts. Keep both suite and
results outside every searched root; the runner enforces this to prevent
indexing test questions and answers. Initial contaminated self-search results
were discarded. The source-hashed fixed run is the retained quality result.

The suite and raw initialization, baseline, experiment, and fixed reports are
retained beside the Workbench task under
`.workbench/tasks/retrieval-quality/evidence/`. Timings from concurrent embedding
maintenance are diagnostic only. The working-tree probes are small and
hand-selected, lack held-out agent tasks, and do not establish fairness against
ripgrep, agent completion rate, token savings, or a broad ranking improvement.
