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

## Matched follow-up and fallback bounds

A frozen clone at `e15dd88f` was evaluated with the same four local Yggdrasil
questions, two installed models, and separate complete indexes. Reducing
chunks from 160 lines / 8 KiB to 80 lines / 3,000 bytes did not improve Qwen's
1/4 top-ten hit count. It improved MiniLM from 1/4 to 3/4, but reduced public
lexical replay recall from 0.830 to 0.810. A 40-line / 2,000-byte candidate
retained only 1/4 top-ten hits with either model. SQLite Porter stemming alone
also reduced public replay recall to 0.810 and MRR from 0.790 to 0.769. These
candidates were discarded; chunk sizing, tokenization, and vector fingerprints
are unchanged.

The live fallback previously retained every matching file's complete text
before sorting. It now retains at most the requested result limit plus twenty
additional candidates, each with an independent bounded citation. It also
compiles a regexp once per search and rejects out-of-range result limits.
On a generated 1,000-file corpus, peak process RSS fell from 568,492,032 to
13,516,800 bytes. Returned records and overflow paths had an identical SHA-256
hash. Runtime was 5.923 versus 6.337 seconds in that single run; this establishes
a memory gain, not a latency gain. Nine further text, fixed, regexp, multiline,
scoped, Unicode, and limit-boundary probes on frozen source retained identical
result hashes. Regression tests cover late better matches and cited line
ranges, overflow order, invalid limits, and cancellation on an empty repository.

Repository discovery now propagates the caller's context through Git file
listing, filesystem walking, and metadata inspection. Canceled Git discovery
returns the context error rather than starting a second filesystem scan. A
stalled-Git regression verifies cancellation before the process's normal exit.

Raw matched model, replay, memory, and parity evidence is retained under
`.workbench/tasks/retrieval-granularity/evidence/`. These robustness changes do
not establish an improvement in intent ranking or agent task completion.

## Evidence-matching CPU follow-up

Profiling the bounded fallback showed that per-line token maps and their
allocations dominated CPU and garbage-collection work. Evidence matching now
keeps only query-relevant terms in a reusable five-line window, streams input
lines, and stops after the earliest window reaches the maximum possible term
coverage. Active term counters avoid rescanning the full query at each
source position; a 256-term benchmark fell from about 4.38 ms to 0.09 ms.
Case-delimited aliases, first-occurrence casing, literal priority,
window boundaries, and source coordinates retain their previous behavior.
Citation extraction now finds a byte offset instead of splitting and joining
the entire source record, and returns an independent bounded excerpt.

On the same generated 1,000-file corpus, three baseline runs took 6.548–6.760
seconds (median 6.675 seconds). The final streamed runs had a median of
0.085 seconds and identical result hashes. Individual measurements are retained
in `profile-accepted.json` beside the task. A separate near-4-MiB
newline-heavy file reduced peak RSS from 327,811,072 to 15,138,816 bytes, with
identical empty results. These are controlled synthetic measurements, not
claims about all repository searches.

Evidence-matching benchmarks include early complete matches, late code matches,
Unicode, and empty lines. Compatibility fixtures also run against the baseline
through a temporary build overlay. Nine frozen-source query variants and 67 deterministic mixed-query cases
retained their result hashes, and all per-case paths, expected-owner ranks, noise and
citation rates matched across the ten public and eight private lexical replay
cases. Aggregate recall/MRR remain 0.830/0.790 public and 0.292/0.438 private.
No ranking improvement is claimed.

The Python protocol integration test now uses the existing normal provider
timeout rather than a shorter startup budget that timed out under concurrent
package initialization. Production timeouts and dedicated timeout tests are
unchanged. Raw profiles, timing repetitions, benchmark output, baseline-overlay
checks and replay comparisons are under
`.workbench/tasks/evidence-cpu/evidence/`.

## Independent semantic windows and fusion weight

A separately run experiment at `56c84668` preserved the lexical representation
while adding smaller semantic-only windows (80 lines / 3,000 bytes). Its retained
reports were inspected and copied with SHA-256 provenance into
`.workbench/tasks/snapshot-consistency/evidence/semantic-experiments/`, alongside
the rejected patches. The following MiniLM auto-mode comparisons all report
complete vector coverage:

| Evaluation | Baseline | Smaller semantic windows | Semantic weight 0.5 |
| --- | ---: | ---: | ---: |
| Local probes: hits@10 | 7/10 | 7/10 | 7/10 |
| Local probes: MRR@10 | 0.316 | 0.339 | 0.282 |
| Public replay: recall@10 / MRR | 0.947 / 0.850 | 0.927 / 0.833 | 0.980 / 0.848 |
| Private replay: recall@10 / MRR | 0.292 / 0.404 | 0.333 / 0.353 | 0.292 / 0.444 |
| Balanced ablation: recall@10 / MRR | 0.583 / 0.378 | 0.438 / 0.256 | 0.521 / 0.343 |

Neither candidate was retained. Smaller windows nearly doubled the number of
vectors in the public replay, regressed the broader ablation, and left the
original secrets and busy-index probes outside the top ten. The lower semantic
weight also produced mixed results. These repeatedly inspected, small suites
are diagnostic rather than held-out validation. Local self-search can retrieve
existing evaluation prose; it does not establish performance on unseen intent
questions. Qwen reports with different case coverage or incomplete local vector
coverage are retained as diagnostics and are not a matched model comparison.
The configured model and ranking weights remain unchanged.

## Stable snapshots during refresh

A public-library regression showed that an already-open read-only snapshot
returned newly committed text after a concurrent refresh. Read-only connections
previously ran separate SQLite statements without a shared read transaction.
Readiness checks and candidate lanes could therefore observe different index
generations during one search.

Request-local snapshots now keep their reads in one transaction, released by
Close or caller-context cancellation. Tests verify that an old snapshot keeps
its original text and embedding model, dimensions, and vectors while a new
snapshot sees the replacement. Writers continue committing in WAL mode while
the old snapshot remains open. Additional checks cover cancellation, repeated
close, and rejecting mutations promptly instead of waiting for the connection
held by the read transaction. Hosts should close snapshots promptly so SQLite
can reclaim retained WAL history. This is a consistency fix, with no intended
ranking or persisted-schema change.

## Qwen coverage and provider-rejected inputs

Qwen 0.6b through Ollama 0.32.5 deterministically failed three dense SVG inputs
in Mimir with HTTP 400 at 3,000 characters and above, while the same text
succeeded at 2,000. A character limit cannot bound provider tokens, and one such
input stopped embedding for the whole repository. On HTTP 400 or 413, the HTTP
provider now splits a batch, then halves a single input down to a 256-character
floor. Other failures do not shorten input.

Run alone from fresh state, the prior binary stopped Mimir at 95 of 709 records
and the fixed binary completed all 709. Fleet moved from 1,772 of 2,232 records
to complete. A shortened record's vector represents only its accepted prefix.
Concurrent clients of one Ollama runner can still fail: a crash on one request
returned HTTP 400 to another run, which can also shorten a healthy input. Do not
run parallel embedding jobs against one local runner.

Complete coverage allowed the first matched Qwen comparison, with one search
iteration on nine `claim-quick` cases (the 24,094-record OpenTelemetry case
excluded), the four Breyta `dogfood-replay` cases, and the six Breyta
`retrieval-ablation` cases:

| Evaluation | Lexical | MiniLM | Qwen 0.6b |
| --- | ---: | ---: | ---: |
| `claim-quick`: recall@10 / MRR | 0.833 / 0.766 | 0.963 / 0.833 | 0.889 / 0.815 |
| `dogfood-replay`: recall@10 / MRR | 0.500 / 0.625 | 0.500 / 0.766 | 0.583 / 0.667 |
| `retrieval-ablation`: recall@10 / MRR | 0.556 / 0.472 | 0.722 / 0.639 | 0.722 / 0.528 |
| Local probes: hits@10 / MRR@10 | 6/10 / 0.387 | 7/10 / 0.316 | 7/10 / 0.312 |

Both models improved these replays over lexical search. On the local probes they
found one more file but lowered MRR by moving some first-rank results down.
MiniLM matched or beat Qwen 0.6b except on `dogfood-replay` recall, while
embedding far faster. Halving the semantic weight lowered Qwen probe MRR to 0.290;
its Qwen replays did not complete, so they are not compared. These reused,
small suites are diagnostic, and neither the void cases nor Qwen 4b were
measured. Raw reports are under `.workbench/tasks/embedding-input-recovery/evidence/`.

## History-derived suites

`tools/history-suite.py` derives cases from commit history. Each case pairs a
commit subject with the files that commit added or modified and that still
exist as text at HEAD. A case is dropped if its subject appears verbatim in a
tracked file. The tool takes non-merge commits whose subject has 4 to 30 words
and that touch at most five files, picks up to a fixed number per repository by
hash, and splits cases by hash into dev and held-out suites. It inspects no
names, paths, or prose for meaning.

Two corpora were built on 2026-11-02, with suites and reports outside every
searched root. The first used 24 of the local repositories. The second used
nine public repositories at pinned revisions: axios, Flask, Dapper,
terraform-aws-vpc, supabase/postgres, graphify, gin, ripgrep, and Hono. All
were measured at complete MiniLM coverage with `tools/retrieval-quality.py`
(first expected file in the first ten paths):

| Suite | Cases | Lexical hits / MRR@10 | Hybrid hits / MRR@10 |
| --- | ---: | ---: | ---: |
| Local dev | 149 | 115 / 0.514 | 116 / 0.554 |
| Local held-out | 184 | 138 / 0.430 | 142 / 0.484 |
| Public dev | 172 | 138 / 0.521 | 136 / 0.516 |
| Public held-out | 188 | 137 / 0.464 | 145 / 0.481 |

Hybrid search improved the local held-out suite, ranking an expected file
higher in 65 cases and lower in 37. On the public repositories it was roughly
neutral: slightly worse on dev, and better on held-out. ripgrep regressed in
both public splits, with MRR falling from 0.69 to 0.48 on dev and from 0.53 to
0.47 on held-out.

The ten hand-written probes had suggested that hybrid fusion moved correct
first-rank lexical results down. A candidate chose the head result from
non-semantic lanes only, leaving lexical mode unchanged. On local dev it ranked
expected files higher in 3 cases and lower in 21, lowering MRR from 0.554 to
0.523. On public dev the split was 9 higher and 17 lower. It was rejected
without using either held-out suite.

Commit subjects are short, describe changes rather than questions, and give
credit for any touched file, tests included. The suites are diagnostic and do
not measure agent task completion. Suites, reports, repository revisions and the
rejected patch are under `.workbench/tasks/history-eval/evidence/`.

