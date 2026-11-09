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

### Retrieval gate

`make eval-history` builds the checkout and runs `tools/retrieval-gate.py` over
every suite directory under `EVAL_DIR` (default `~/.local/share/ygg-eval`). A
suite directory holds `dev.json` and `holdout.json` from
`tools/history-suite.py` and a `repos` link to the directory containing its
repositories. Point the link at clones pinned to the revisions the suite was
derived from, not at live checkouts: an edited checkout leaves new records
without vectors, and search does not wait for them. Each suite searches with its
own storage root, `<suite>/store`, so runs never refresh the user's index;
semantic search uses the embedding provider from the user configuration. The
gate writes `run-dev.json` and prints, per suite and mode, hits and MRR@10
against `baseline-dev.json` with every case whose rank changed. A run with
errors or less than complete semantic coverage fails instead of being compared,
and so does a run whose repository revisions or expected-file hashes differ from
the baseline's. `make eval-history-baseline` records the current build as the
baseline.

Tune ranking changes against dev only. Compare a candidate that already
improved dev on the held-out split once, with
`python3 -I tools/retrieval-gate.py --binary bin/ygg --split holdout` after
saving a held-out baseline from the base build.

### Held-out refresh

The first held-out suites decided the stemmed lane, reranking and the extractor
scope, so later choices could fit them. On 2026-11-03 they were retired as
`retired-2026-11-02-*` files in each suite directory and replaced. The new
suites come from the same pinned clones, so the existing stores serve them.
`--exclude` skips every commit and subject in the dev and retired suites, and
`--name holdout` writes all selected cases to one file:

```
python3 -I tools/history-suite.py --repos <clones> --output <suite> --name holdout \
  --per-repo <n> --exclude <suite>/dev.json \
  --exclude <suite>/retired-2026-11-02-holdout.json
```

With `--per-repo 13`, the local suite has 171 cases from 14 repositories; ten
smaller repositories had no unused commits left in the scanned window. With
`--per-repo 20`, the public suite has 180 cases, 20 from each repository.
Neither shares a commit or subject with dev or the retired suites. Baselines
were saved with MiniLM hybrid search and the HTTP reranker:

| Held-out suite | Cases | Lexical hits / MRR@10 | Hybrid hits / MRR@10 |
| --- | ---: | ---: | ---: |
| Local, retired | 184 | 156 / 0.556 | 152 / 0.587 |
| Local, new | 171 | 136 / 0.537 | 144 / 0.579 |
| Public, retired | 188 | 159 / 0.562 | 152 / 0.538 |
| Public, new | 180 | 157 / 0.614 | 160 / 0.603 |

The retired rows are the reranked results from the reranking section. The new
local suite is close to them; the new public suite scores higher, with hybrid
slightly below lexical as before. Its baselines are in `baseline-holdout.json`,
and the retired files are kept for reference only.

### Issue-sourced suite

`tools/issue-suite.py` derives cases from GitHub issues closed by merged pull
requests. A case pairs the issue title with the files the merge commit
modified, pinned to the commit before the fix. The filters, the leak check
(verbatim title at the base) and the hash split are the same as in
`tools/history-suite.py`. Issue titles describe a symptom in the reporter's
words, so they probe the vocabulary gap that commit subjects rarely show.

A first draw pinned to the clone's HEAD was retired before any held-out use.
Some fixes remove the very words their issue quotes: `url.parse()` in axios, a
renamed Flask test. So HEAD can hide the answer. `tools/snapshot-quality.py`
instead exports each case's base with `git archive` and searches it in
lexical mode with a temporary store. Snapshots have no embeddings, so this
suite measures lexical retrieval only.

The 2026-11-08 draw covers seven of the nine public clones; graphify and
supabase/postgres link no issues to pull requests. It has 69 dev and 71
held-out cases. On dev, 57 cases find an expected file in the first ten paths
(MRR@10 0.534). Scoring takes 22 seconds for 69 snapshots.

| Miss class (dev) | Cases | Examples |
| --- | ---: | --- |
| Answer not locatable from the title | 5 | a .NET 10 upgrade (`*.csproj`), a vulnerability report (`go.mod`), a glibc CI failure (`Cargo.lock`) |
| The fix adds the word the issue uses | 2 | ripgrep "support cuda files": `default_types.rs` has no `cuda` before the fix |
| Prose or tests outrank the code | 4 | axios header typing: docs pages and tests hold every query word in one chunk, while `index.d.ts` spreads its matches across many (rank 46) |
| Paraphrase | 1 | ripgrep "slowdown with larger `-A` context windows": `searcher/core.rs` at rank 27 |

Nine of the twelve misses rank between 11 and 27. Unlike the commit-subject
suites, where changelog and Markdown files earn the most hits, hits here land
on source files in 46 of 57 cases.

With `--config` naming an embedding provider and `--mode` repeated,
`tools/snapshot-quality.py` embeds each snapshot before scoring. The dev split
was scored that way with MiniLM (no reranker), with complete coverage in all
69 cases:

| Mode | Hits@10 | MRR@10 |
| --- | ---: | ---: |
| Lexical | 57 | 0.534 |
| Auto (hybrid) | 59 | 0.517 |
| Semantic | 55 | 0.445 |

The better of lexical and semantic finds 62. Hybrid ranks an expected file
higher than lexical in 12 cases. In 28 it ranks it below the better single
lane, including three first-rank lexical results that it moves to fourth or
fifth. On these issue titles, fusion adds recall but costs early precision.
That fits the earlier commit-subject results, where hybrid was roughly
neutral on public dev. The fitted-weight study below already showed that
refitting lane weights does not hold out of fold. This suite is therefore a
third, independent check for any future fusion change; it does not justify
refitting on its own. Four frozen opening-search cases showed the same
pattern: semantic ranked one owner first where hybrid placed it fourth, and
hybrid moved a second-rank lexical owner to fifth.

#### Reranking issue titles

The 0.6B HTTP reranker from the reranking section below (depth 20, 1500
characters) was scored on both splits in lexical mode, with a user
configuration that names only the reranker. Every row reported `rerank.state`
`applied`.

| Split | Hits@10 | MRR@10 | Higher / lower |
| --- | ---: | ---: | ---: |
| Dev (69) | 57 → 58 | 0.534 → 0.551 | 16 / 13 |
| Held-out (71) | 61 → 63 | 0.591 → 0.627 | 15 / 14 |

The gain is smaller than on the commit-subject suites, but it holds on unseen
issue titles. Reranking stays opt-in, because it needs a running provider.

#### Embeddings on issue titles

Hybrid search was scored on the same splits with MiniLM (`all-minilm`, 384
dimensions) and with `qwen3-embedding:0.6b`, in the combinations listed.
Every snapshot was embedded completely before it was searched.

| Split | Configuration | Hits@10 | MRR@10 |
| --- | --- | ---: | ---: |
| Dev | Lexical | 57 | 0.534 |
| Dev | MiniLM hybrid | 59 | 0.517 |
| Dev | Qwen 0.6b hybrid | 63 | 0.584 |
| Dev | Lexical + rerank | 58 | 0.551 |
| Dev | Qwen 0.6b hybrid + rerank | 62 | 0.562 |
| Held-out | Lexical | 61 | 0.591 |
| Held-out | Qwen 0.6b hybrid | 60 | 0.567 |
| Held-out | Lexical + rerank | 63 | 0.627 |
| Held-out | MiniLM hybrid + rerank | 63 | 0.609 |
| Held-out | Qwen 0.6b hybrid + rerank | 63 | 0.591 |

Qwen's dev gain did not hold on held-out titles, as on the history suites
below. With the reranker, adding MiniLM vectors moved 12 held-out titles up
and 16 down against lexical order. On these suites the embedding lane adds
indexing cost without improving the reranked ranking, so neither model
replaces the reranker as the recommended addition to lexical search.

`qwen3-embedding:4b` (2,560 dimensions) was started on dev with the reranker
and stopped after 48 of 69 titles. On those 48 it scored 44 hits and MRR@10
0.580, against 44 and 0.556 for the 0.6b model and 41 and 0.541 for lexical
search with the reranker (13 titles higher, 4 lower). The 0.6b model's similar
dev lead did not hold on held-out, and 4b embeds several times more slowly
than 0.6b, whose nine-repository index already took 80 minutes. The held-out
run was not started, because a confirmed gain of this size would not justify
that indexing cost as a default.

Three small embedders were compared with MiniLM on held-out titles, each with
the reranker, and stopped by a paired bootstrap rule (stop once the 95% bound on
the reciprocal-rank difference excludes a 0.02 gain). `snowflake-arctic-embed`
33M scored 0.575 against 0.615 on 17 titles. `bge-small-en-v1.5` scored 0.607
against 0.664 on 31 titles, below MiniLM at the 95% bound. `jina-embeddings-v2-small-en`
(512 dimensions, 8k context) scored 0.583 against 0.638 on 46 titles, no gain.
Two rerankers were tried in place of Qwen3-Reranker-0.6B with lexical search on
dev: `bge-reranker-v2-m3` scored 0.538 against 0.551 with the same 58 hits (15
titles higher, 16 lower). The available Qwen3-Reranker-4B GGUF conversions
carry no rerank head (llama.cpp scored every document 0), so it was not measured.

#### LLM query rewriting

Agents and harnesses write the query, so a better query is a guidance change
rather than a core one. To see how much query wording matters, the local
`qwen3.6:27b` model (Ollama, temperature 0, seed 1, no thinking) was asked to
turn each issue title into one search query of three to eight words: the
identifiers, function names, error text or code terms most likely to appear
in the files to change. It saw only the repository name and the title.

| Split | Query | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Dev | Title | 57 | 0.534 | |
| Dev | Rewrite | 60 | 0.622 | 26 / 13 |
| Dev | Title and rewrite | 60 | 0.621 | |
| Held-out | Title | 61 | 0.591 | |
| Held-out | Rewrite | 61 | 0.589 | 15 / 16 |

Only one dev rewrite added an expected file's stem that the title lacked
(`main` in a Terraform module), so the dev gain did not come from recalled
paths. It did not carry over to the held-out titles, which already rank
better than dev's, and the rewrite was rejected. The search tips rejected in
`docs/agent-eval.md` advised the opposite, starting from a description; this
result does not support either piece of advice.

A gate that keeps lexical order when lexical retrieval looks confident was
also considered. Search output carries no scores, and replaying family fusion
from the saved lexical and semantic path lists reproduced only 35 of the 69
hybrid ranks, so the gate could not be measured offline. The best weight in
that replay, 0.25 for the semantic family, is the setting that already failed
out of fold in the fitted-weight study, so the gate was not built.

### Long queries

Search refused queries of more than 256 whitespace-separated words. Pasted
traces, logs, and diff hunks often exceed that: 69 of the 345 positive
queries in Agent Retrieval Bench V2 (`eyuansu71/agent_retrieval_bench`, queries
rendered as the benchmark's sorted JSON) did, including 53 of 58 in
`edit2ripple`. Queries are now accepted up to the 16 KiB byte limit. A term
list of at most 768 terms (three times the former word limit, above the 716
terms the longest previously accepted query produced) is used as written,
repeats included, since repeats weigh in BM25. A longer one keeps its first 256
distinct terms, and the exact-phrase lane is skipped. Lexical mode, no
extractors or reranker, files rebuilt from the benchmark's file chunks:

| Subset | Refused before | MRR | Recall@20 |
| --- | ---: | ---: | ---: |
| `code2test` (106) | 4 | 0.043 → 0.045 | 0.225 → 0.239 |
| `comment2context` (80) | 2 | 0.144 → 0.144 | 0.352 → 0.352 |
| `trace2code` (101) | 10 | 0.059 → 0.066 | 0.277 → 0.322 |
| `edit2ripple` (58) | 53 | 0.015 → 0.192 | 0.034 → 0.454 |
| All (345) | 69 | 0.066 → 0.099 | 0.238 → 0.326 |

No previously accepted query changed rank. Recovered queries took 0.84 s at
p50 and 7.8 s at most. Keeping repeats beyond 768 terms took up to 99 s on
`edit2ripple` and ranked worse (MRR 0.141); deduplicating every query changed
164 previously accepted ranks, mostly for the worse.

The benchmark's own lexical baseline, re-run per sample, scores MRR 0.157 and
Recall@20 0.494 (matching its published table). An ablation of that baseline on
`code2test` and `comment2context` traced its edge to symbol-level chunks:
without them Recall@20 fell from 0.355 to 0.212, while dropping camelCase
splitting changed it little (0.383). Enabling the bundled extractors, whose
symbol records feed the extractor lane, raised ygg's Recall@20 on those subsets
from 0.239 to 0.329 and from 0.352 to 0.394.

## Stemmed lexical lane

The unicode61 tokenizer matches only identical tokens, so `retried` misses
`retries`. Three variants were measured with the retrieval gate on the dev
suites, all against the same stores:

| Variant | Local lexical | Local hybrid | Public lexical | Public hybrid |
| --- | ---: | ---: | ---: | ---: |
| Baseline MRR@10 | 0.514 | 0.554 | 0.521 | 0.516 |
| Porter tokenizer for every lexical lane | 0.559 | 0.582 | 0.515 | 0.517 |
| Stemmed lane replaces the any-term lane | 0.511 | 0.566 | 0.529 | 0.517 |
| Stemmed lane added to the lexical family | 0.555 | 0.557 | 0.541 | 0.522 |

Only the added lane improved both corpora in lexical mode, ranking expected
files higher in 39 local and 41 public cases and lower in 11 and 13. It was then
compared once on the held-out suites:

| Held-out suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local | Lexical | 138 → 146 of 184 | 0.430 → 0.479 | 62 / 20 |
| Local | Hybrid | 142 → 152 of 184 | 0.484 → 0.509 | 39 / 23 |
| Public | Lexical | 137 → 149 of 188 | 0.464 → 0.510 | 54 / 14 |
| Public | Hybrid | 145 → 148 of 188 | 0.481 → 0.511 | 42 / 13 |

The stemmed table is a second external-content FTS5 index over the same lexical
records, maintained by triggers and built when a writer opens an index that
lacks it. The freshness token includes the search layout, so search treats an
older index as stale instead of querying a missing table. The added table grew
the evaluation indexes, vectors included, by 18.5% locally (638 to 756 MiB) and
18.2% for the public repositories (101 to 119 MiB).

On `claim-quick` in lexical mode, MRR stayed at 0.79 and file recall@10 fell
from 0.83 to 0.79: in one five-file case two secondary files moved from ranks 7
and 8 to 22 and 25 while its first result was unchanged. Full-index p50 rose
from 237 to 309 ms and search p50 from 83 to 101 ms, measured while another
experiment was using the machine.

## Reranking

Of the 69 dev cases where hybrid search missed the first ten paths, 60 had an
expected file within the first 100 results and only 8 were missed by every
lane; in 19, one lane alone ranked it in the first ten. The losses were mostly
ordering, so a cross-encoder reranker was measured over the fused results.

An offline experiment scored the first 20 fused paths with
`Qwen/Qwen3-Reranker-0.6B` (revision `e61197ed`) on Apple MPS. Ordering by the
reranker alone lost hits; reciprocal-rank fusion of the fused rank and the
reranker rank kept them. With k = 20 chosen on dev, the held-out suites
improved MRR@10 in all four suite and mode pairs.

The integrated reranker sends each result's path and record text from its
localized citation onward, up to 1500 characters. Measured with the retrieval
gate against the stemmed-lane baseline:

| Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local dev | Lexical | 122 → 126 of 149 | 0.555 → 0.621 | 47 / 14 |
| Local dev | Hybrid | 119 → 127 of 149 | 0.557 → 0.645 | 53 / 13 |
| Public dev | Lexical | 142 → 152 of 172 | 0.541 → 0.599 | 50 / 19 |
| Public dev | Hybrid | 139 → 146 of 172 | 0.522 → 0.556 | 48 / 27 |
| Local held-out | Lexical | 146 → 156 of 184 | 0.479 → 0.556 | 71 / 21 |
| Local held-out | Hybrid | 152 → 152 of 184 | 0.509 → 0.587 | 64 / 26 |
| Public held-out | Lexical | 149 → 159 of 188 | 0.510 → 0.562 | 59 / 19 |
| Public held-out | Hybrid | 148 → 152 of 188 | 0.511 → 0.538 | 48 / 31 |

Every row reported `rerank.state` `applied`. Sending text from the citation
onward did better than the offline experiment's narrower citation spans.

Each reranked CLI search took about 4 s (p50 3.95 s, p90 4.27 s) instead of
about 0.1 s: starting Python and loading the model take 2.5 to 3.5 s and
scoring 20 candidates about 0.4 s. A host that retains the provider pays the
start once. Reranking is opt-in.

Several cheaper fusion changes were measured on dev against the stemmed-lane
baseline and rejected: semantic family weight 0.5 (local MRR flat), family RRF
k = 30 (neutral), removing third-root promotion (slightly worse), removing the
record-level head (public hybrid 0.522 → 0.508), and a stemmed all-terms lane
(public worse).

After the reranker landed, four more changes were measured on dev and
rejected. Without reranking, BM25 path-column weights of 4 and 16 instead of 8
moved MRR@10 by at most 0.006 in either direction, and capping the semantic
lane at 30 candidates lowered public hybrid MRR@10 from 0.522 to 0.510. With
reranking, depth 30 instead of 20 changed MRR@10 by +0.001 and +0.003 locally
and by -0.003 and +0.005 on the public suite, and hits@10 by at most 3, while
adding about 0.7 s per search. The default depth stays at 20.

An `http` reranker using `qwen3-reranker-0.6b-q8_0.gguf` (revision `a02f48bb`)
on `llama-server --reranking` matched the command worker on dev: MRR@10 0.623
and 0.650 locally (lexical and hybrid) against 0.621 and 0.645, and 0.593 and
0.558 on the public suite against 0.599 and 0.556. Every row was reranked. A
reranked CLI search took 0.9 s at p50 and 1.1 s at p90 instead of about 4 s.

`Qwen3-Reranker-4B.Q8_0.gguf` from `giladgd/Qwen3-Reranker-4B-GGUF` (revision
`618ca919`, converted with the classifier head) was measured on dev against the
0.6B HTTP reranker with the same depth, input and fusion:

| Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local dev | Lexical | 124 → 127 of 149 | 0.623 → 0.629 | 20 / 19 |
| Local dev | Hybrid | 128 → 129 of 149 | 0.650 → 0.640 | 22 / 25 |
| Public dev | Lexical | 149 → 152 of 172 | 0.593 → 0.588 | 33 / 29 |
| Public dev | Hybrid | 144 → 145 of 172 | 0.558 → 0.570 | 32 / 24 |

The changes split both ways within about 0.01, while a search took 3.7 s at
p50 and 4.3 s at p90 instead of 1.0 s and 1.2 s. It was rejected without using
the held-out suites; the 0.6B model stays the documented choice.

## Embedding windows

Core chunks average about 5,000 characters and 123 lines, while MiniLM in
Ollama reads only its first 256 tokens, about 800 characters. A chunk's vector
therefore described roughly its first 20 lines. A candidate kept chunks
lexical-only (`semantic: false`) and added semantic-only windows
(`lexical: false`) of at most 30 lines and 1,000 bytes, overlapping by 5 lines,
to carry the vectors. One variant embedded every window. The other kept at most
three windows per lexical chunk, spread evenly over the file's window grid with
the first and last kept. Files that fit in one window kept a single record for
both lanes. Lexical chunks were byte-identical to before.

Both variants were measured on dev against the 0.6B HTTP reranker baseline.
`migard2` was left out of the local suite: its one dev case did not justify
embedding its committed `vendor/` tree, which is 96% of its chunks.

| Suite | Variant | Hybrid hits@10 | Hybrid MRR@10 | Vectors | Store |
| --- | --- | ---: | ---: | ---: | ---: |
| Local dev (148) | Baseline | 127 | 0.647 | 36,457 | 409 MiB |
| Local dev (148) | Every window | 131 | 0.671 | 237,772 | 1,093 MiB |
| Local dev (148) | Three per chunk | 133 | 0.650 | 102,521 | 661 MiB |
| Public dev (172) | Baseline | 144 | 0.558 | 10,737 | 120 MiB |
| Public dev (172) | Every window | 148 | 0.566 | 60,722 | 286 MiB |
| Public dev (172) | Three per chunk | 146 | 0.575 | 29,539 | 190 MiB |

Lexical mode was unchanged in every run apart from one reranker tie on the
public suite. Against the baseline, three windows per chunk ranked expected
files higher in 30 local and 38 public cases and lower in 21 and 36. Every
window cost about six times the baseline vectors, and the local suite's first
index took over an hour without `migard2` (about three hours more with it);
three per chunk cost about 2.8 times the vectors and embedded the local suite
in 28 minutes. Search time was unchanged when both binaries ran back to back
under the same load.

Three windows per chunk landed on dev and then had its one held-out check
against the saved held-out baseline, using the same stores:

| Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local held-out | Lexical | 136 → 136 of 171 | 0.537 → 0.541 | 2 / 0 |
| Local held-out | Hybrid | 144 → 143 of 171 | 0.579 → 0.580 | 34 / 39 |
| Public held-out | Lexical | 157 → 157 of 180 | 0.614 → 0.614 | 0 / 0 |
| Public held-out | Hybrid | 160 → 156 of 180 | 0.603 → 0.599 | 27 / 29 |

The two lexical moves are reranker ties. The dev gain did not carry over to
unseen cases, while the cost in vectors, index time and store size did, so the
change was reverted. The patch is kept on branch `feat/embedding-windows`.

## Pseudo-relevance feedback

Query expansion had not been tried, so a `feedback` lane was measured in the
lexical family, fused by the existing reciprocal-rank fusion. It ran only for
natural-language text queries, not for identifiers, fixed strings, or regular
expressions. The lane worked as follows:

1. Take the leading N records, one per path, by reciprocal-rank fusion of the
   other lexical-family lanes.
2. Count lower-cased letter-and-digit tokens in their titles and text. Leave
   out the query's own terms, and keep the 128 most frequent.
3. Weight each term by (1 + ln tf) × ln(N_records / df). The document frequency
   came from counting each quoted term in `record_fts` in one statement.
4. Run the leading M terms as one additional any-term FTS query.

Weighting by raw term frequency picked `the`, `and` and `a` in a spot check, so
both measured settings used the sublinear form. Measured with the retrieval gate
on dev against the 0.6B HTTP reranker baseline, on the indexes from before
embedding windows:

| Setting | Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | --- | ---: | ---: | ---: |
| N = 5, M = 10 | Local | Lexical | 124 → 125 of 149 | 0.623 → 0.628 | 29 / 26 |
| N = 5, M = 10 | Local | Hybrid | 128 → 128 of 149 | 0.650 → 0.628 | 20 / 22 |
| N = 5, M = 10 | Public | Lexical | 149 → 146 of 172 | 0.593 → 0.548 | 26 / 44 |
| N = 5, M = 10 | Public | Hybrid | 144 → 141 of 172 | 0.558 → 0.545 | 21 / 38 |
| N = 3, M = 5 | Local | Lexical | 124 → 128 of 149 | 0.623 → 0.618 | 28 / 30 |
| N = 3, M = 5 | Local | Hybrid | 128 → 125 of 149 | 0.650 → 0.614 | 18 / 25 |
| N = 3, M = 5 | Public | Lexical | 149 → 148 of 172 | 0.593 → 0.553 | 20 / 44 |
| N = 3, M = 5 | Public | Hybrid | 144 → 142 of 172 | 0.558 → 0.539 | 20 / 34 |

Every row was reranked. With N = 5 and M = 10 in lexical mode, lower ranks
outnumbered higher ones in seven of the nine public repositories, so no single
repository explains the loss. The selected terms looked related to the query,
such as `gguf`, `llama` and `qwen3` for a reranker query, yet ranking still
fell. Both settings were rejected without using the held-out suites.

The lane itself took 1 to 13 ms, measured in-process on this repository,
`postgres`, `gin` and `Dapper`. Gate p50 rose from about 1.0 s to 1.6 s and
2.7 s, but the main build took 1.5 to 2.8 s for the same queries at the same
time. Another experiment was indexing embeddings during these runs, so the gate
latencies do not measure the lane.

## Extractor plugins

None of the 40 local repositories configures extractor plugins, and the history
suites had been measured without them. Enabling every bundled extractor
(Markdown, Go, TypeScript including `.mjs`, `.cjs`, `.mts` and `.cts`, manifests,
Terraform, Python, JVM/.NET, and Clojure) on copies of the pinned repositories
roughly doubled the indexed records and changed dev MRR@10 as follows:

| Suite | Mode | No plugins | Plugins | Plugins, core index scoped |
| --- | --- | ---: | ---: | ---: |
| Local | Lexical | 0.555 | 0.504 | 0.547 |
| Local | Hybrid | 0.557 | 0.578 | 0.587 |
| Public | Lexical | 0.541 | 0.504 | 0.535 |
| Public | Hybrid | 0.522 | 0.519 | 0.532 |

Plugin records had shared the core lexical indexes, and their short records
shifted BM25 statistics against core text. Removing them only at query time
recovered part of the loss (0.529 and 0.526 lexical); removing them from the
indexes recovered most of it. Dropping the extractor lane as well left lexical
results at the no-plugin baseline but did not improve hybrid results further,
so the lane stays. With the scoped indexes, the held-out suites moved from
0.479 to 0.483 and 0.509 to 0.526 locally (lexical, hybrid) and from 0.510 to
0.512 and 0.511 to 0.506 on the public repositories. The plugin benchmarks for
Python, JVM/.NET, and Terraform were unchanged.

## Fitted fusion weights

The rejected fusion changes above were single hand-picked settings. A fitted
weighting was tried next. A temporary search option multiplied each lane's
reciprocal-rank contribution and the semantic family's contribution; at 1.0 it
left results unchanged. The suites configure no extractors, so the weighted
inputs were the `exact`, `all-terms`, `stemmed`, `path` and `anchor` lanes
against the `lexical` lane, the `semantic` lane (which also moves the
record-level head), and the semantic family.

Fitting replayed search in process against the dev stores from before
embedding windows, with lane candidates, query embeddings and rerank scores
(the 0.6B HTTP reranker, depth 20) memoized. At unit weights a first replay matched the saved dev baseline
except for two public hybrid ranks, which moved by one through reranker
nondeterminism. The fits reused the rerank scores of a second replay, whose
public baseline reads 0.590 lexical and 0.558 hybrid. Coordinate ascent over
0, 0.25, 0.5, 1, 2 and 4 maximized the mean over both suites of lexical plus
hybrid MRR@10. For cross-validation,
repositories were split into two folds balanced by case count within each
suite: A has 75 local and 81 public cases, B has 74 and 91.

Fitted on all of dev, the weights were 0.25 for the semantic family and lane,
0 for `exact` and `all-terms`, and 0.5 for `path` and `anchor`. In-sample:

| Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local dev | Lexical | 124 → 126 of 149 | 0.623 → 0.642 | 20 / 7 |
| Local dev | Hybrid | 128 → 128 of 149 | 0.650 → 0.656 | 29 / 21 |
| Public dev | Lexical | 149 → 147 of 172 | 0.590 → 0.613 | 28 / 13 |
| Public dev | Hybrid | 144 → 150 of 172 | 0.558 → 0.590 | 39 / 27 |

Out of fold it did not hold. MRR@10 on the test fold, from unit weights to the
weights fitted on the other fold:

| Weighted inputs | Test fold | Local lexical | Local hybrid | Public lexical | Public hybrid |
| --- | --- | ---: | ---: | ---: | ---: |
| All seven | B | 0.578 → 0.555 | 0.578 → 0.547 | 0.566 → 0.563 | 0.559 → 0.543 |
| All seven | A | 0.668 → 0.694 | 0.720 → 0.699 | 0.617 → 0.586 | 0.557 → 0.569 |
| Semantic lane and family | B | 0.578 → 0.578 | 0.578 → 0.578 | 0.566 → 0.566 | 0.559 → 0.549 |
| Semantic lane and family | A | 0.668 → 0.668 | 0.720 → 0.695 | 0.617 → 0.617 | 0.557 → 0.580 |
| `exact`, `all-terms`, `path` | B | 0.578 → 0.578 | 0.578 → 0.587 | 0.566 → 0.574 | 0.559 → 0.562 |
| `exact`, `all-terms`, `path` | A | 0.668 → 0.695 | 0.720 → 0.712 | 0.617 → 0.603 | 0.557 → 0.553 |

The folds disagreed on most weights: with all seven inputs, fitting on A set
the semantic lane to 4 and `stemmed` to 0.5, and fitting on B set them to 0.25
and 4. Fitted on all of dev, the two smaller spaces did not raise local hybrid
MRR@10 even in-sample: a semantic family weight of 0.25 gave 0.647, and `exact`
and `all-terms` at 0 with `path` at 0.5 gave 0.646, against 0.650. Every fit
that weighted `exact` and `all-terms` set them to 0, which raised lexical
MRR@10 in-sample but not consistently out of fold.

No weighting improved hybrid MRR@10 on both test folds of both suites, so the
change was rejected. The retrieval gate and the held-out suites were not used.
Fusion keeps equal lane weights. The patch, replay harness and logs are under
`.workbench/tasks/fusion-weights/evidence/`.

## File-level term coverage

Core lexical lanes score 160-line chunks, and a file inherits its best chunk's
rank, so a file whose chunks share a query's terms between them earns no credit
for covering them all. Sourcegraph's Zoekt scores whole files with BM25F. A
cheaper query-side candidate added a `coverage` lane: for each distinct query
term (at most eight), one any-term FTS query bounded to the usual candidate
limit of paths; files reached by at least two terms were ranked by the number of
terms, then by summed per-term reciprocal rank. It was skipped for queries with
structured anchors, which a unit test showed it would otherwise bury.

It was measured on dev against the embedding-windows build, on the same stores,
with `migard2` left out of the local suite:

| Suite | Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | --- | ---: | ---: | ---: |
| Local dev | Lexical | 123 → 128 of 148 | 0.621 → 0.638 | 25 / 14 |
| Local dev | Hybrid | 133 → 133 of 148 | 0.650 → 0.644 | 23 / 14 |
| Public dev | Lexical | 149 → 148 of 172 | 0.593 → 0.564 | 17 / 21 |
| Public dev | Hybrid | 146 → 145 of 172 | 0.575 → 0.566 | 18 / 19 |

Counting terms gives common words the same weight as distinctive ones, which
hurt the larger public repositories. It was rejected without the held-out
suites. A variant that ignores terms whose candidates fill the path limit, a
rough document-frequency cut, was not measured.

## LLM file summaries

MiniLM reads only about the first 800 characters of each embedded input, so a
file's embedding covers little of its meaning. An experimental extractor tested
whether a short LLM summary per file, embedded as one extra record, bridges
commit-style queries to code the way doc2query expansion does.

The extractor served `file-summary` records from a local cache keyed by
`sha256(path, NUL, content)` and never called a model while indexing. Each
record spanned the whole file, was titled with its path, and carried
`"lexical": false`, so it was embedded but kept out of every lexical index. A
separate generator filled the cache with `claude-haiku-5-5`. It summarized
only the files the indexer discovered and routed to the plugin, meaning source
and documentation extensions, not configuration or lock files. Each summary was
one paragraph of at most 120 words in commit-message vocabulary, from at most
48,000 characters of input, averaging 115 words. The generator ran through
headless Claude Code (`claude -p`) on a subscription rather than the Message
Batches API.

The run was stopped after a preliminary measurement. The four public
repositories that were fully summarized (Dapper, axios, flask and gin; 920
files, 83 of the 172 dev cases) were indexed on private clones at the pinned
revisions. They were compared with a no-plugin control indexed the same way:

| Mode | Hits@10 | MRR@10 | Higher / lower |
| --- | ---: | ---: | ---: |
| Lexical | 70 → 70 of 83 | 0.525 → 0.531 | 1 / 0 |
| Hybrid | 70 → 70 of 83 | 0.524 → 0.524 | 15 / 16 |
| Semantic only | 57 → 57 of 83 | 0.385 → 0.412 | — |

The lexical change is one flask case that also differed between the control
and the saved baseline. The summaries helped the semantic lane alone: a summary
was the top semantic record for 41 of the 83 queries, and MRR@10 rose by 0.027.
After fusion with the lexical lanes and reranking, hybrid results did not
change. The approach was rejected without summarizing the other five
repositories or using the held-out suites.

The four repositories took 3.07M input and 0.24M output tokens, including about
1,000 tokens of Claude Code overhead per call. That is about $0.43 at Haiku 5.5
list prices, or $0.21 through the Batches API. By the time the run stopped, 1,909
of the 3,182 public files had been summarized with 8.8M input and 0.54M output
tokens. No private source was sent.

## Qwen embedding model

MiniLM reads about 800 characters of each record. `qwen3-embedding:0.6b`
through Ollama (1,024 dimensions, up to 9,000 input characters, with the
code-search instruction prefix) was tested as a replacement on the public
history suites, with the same binary, chunking and HTTP reranker.

Indexing the nine public repositories took 4,951 seconds, all on embedding.
graphify (3,328 records) failed with a client timeout and had no vectors, so
its cases are left out of both comparisons. Lexical results were identical to
the MiniLM baselines, so only the semantic lane differed.

| Suite | Mode | Hits@10 | MRR@10 |
| --- | --- | ---: | ---: |
| Public dev | Lexical | 131 → 131 of 154 | 0.560 → 0.560 |
| Public dev | Hybrid | 126 → 129 of 154 | 0.522 → 0.535 |
| Public held-out | Lexical | 137 → 137 of 160 | 0.597 → 0.597 |
| Public held-out | Hybrid | 141 → 138 of 160 | 0.590 → 0.587 |

The dev gain did not hold on held-out, so Qwen 0.6b was rejected. Its
4b model and the local suites were not run.

#### Reranking long queries

The reranker scores the query and each document in one context, and the
local Qwen3 reranker rejects pairs over its 2048-token batch. With the
1500-character input limit, 54 of the 345 ARB queries failed the whole
rerank request (42 of 58 edit2ripple diffs, 12 trace2code traces) and kept
the lexical order. Truncating the query to the input limit made all of them
rerank, but edit2ripple MRR fell from 0.192 (lexical order) to 0.183 while
trace2code rose from 0.107 to 0.113; across both subsets the truncated
variant summed less reciprocal rank than the fallback. Search now skips
reranking for queries over the input limit and reports `skipped`, which keeps
the measured ranking without a failing request.

Where it applies, reranking lifted every suite measured on 2026-11-08:
cross-artifact dev MRR 0.581 to 0.643 and P@2 0.356 to 0.432 (top-20
coverage unchanged by construction), and ARB MRR 0.099 to 0.130 (71 better,
15 worse).

## Live search without a lexical index

Replacing the SQLite lexical index with live search would remove index
freshness from the lexical path, so it was measured on the issue-title splits
before deciding. Every method ranks the same exported snapshot:

- `rg`: files ranked by distinct query words, then matching lines;
- `rg` BM25: file-level BM25 computed from `rg -c` counts per term;
- live fallback: `query.RunFilesystem`, ygg's index-free search used while an
  index is busy;
- `rg` + rerank: the first 20 `rg` files reranked over each file's densest
  query-term window, with the same 0.6B reranker and 1,500-character limit.

| Method | Dev MRR@10 | Dev hits@10 | Held-out MRR@10 | Held-out hits@10 |
| --- | ---: | ---: | ---: | ---: |
| `rg` | 0.482 | 55 | 0.505 | 60 |
| `rg` BM25 | 0.476 | 55 | 0.456 | 59 |
| Live fallback | 0.364 | 47 | 0.388 | 48 |
| ygg lexical index | 0.534 | 57 | 0.591 | 61 |
| `rg` + rerank | 0.523 | 56 | 0.563 | 58 |
| ygg lexical index + rerank | 0.551 | 58 | 0.627 | 63 |

Live search finds about as many files but ranks them lower; BM25 over whole
files does not recover the index's chunk-level, stemmed and identifier lanes.
The reranker narrows the held-out gap from 0.086 to 0.064 MRR (21 titles better
with the index, 16 with `rg`). Because embeddings and extractor records keep
the SQLite store and its freshness work regardless, the lexical index was kept.

## Cross-artifact changes

Changes that span code, docs and configuration are where a ranked index should
beat a grep loop, because no single word ties the files together.
`tools/cross-artifact.py` draws cases from commits that modified files of at
least two kinds, classified by file name only, and queries the commit subject
at the parent commit. It scores coverage of the changed files among the first
20 paths against an `rg` baseline that ranks files by how many distinct query
words they contain. The dev suite has 139 cases from 28 repositories and the
held-out suite has 149 cases from 27, nine of them public in each.

| Suite | Method | Coverage@20 | Complete | MRR | Code | Config | Docs |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Dev | rg | 0.605 | 26 | 0.606 | 219/378 | 58/131 | 137/177 |
| Dev | ygg lexical | 0.623 | 30 | 0.578 | 215/378 | 67/131 | 136/177 |
| Dev | ygg lexical + rerank | 0.623 | 30 | 0.642 | | | |
| Dev | ygg lexical + plugins | 0.618 | 29 | 0.583 | 211/378 | 64/131 | 141/177 |
| Held-out | rg | 0.642 | 39 | 0.590 | 271/432 | 36/99 | 164/204 |
| Held-out | ygg lexical | 0.664 | 44 | 0.610 | 277/432 | 43/99 | 163/204 |

Ygg's edge over rg is small and comes mostly from configuration files. Per
case it is close to even: 33 better and 32 worse on dev, 46 and 42 held-out.
The reranker raises dev MRR (P@1 0.432 → 0.504, 44 cases better and 18 worse)
without changing which files reach the first 20. Extractor plugins did not
change coverage.

Structural neighbours were tested offline as a proxy for per-hit "called by"
and "calls" context: files that share rare identifiers (document frequency
at most 25) with the first five excerpts, interleaved after each hit. At equal
path budgets they lowered recall@10 everywhere (cross-artifact 0.503 → 0.447,
issue dev 0.695 → 0.667, ARB 0.178 → 0.128). At 20 paths they raised
cross-artifact recall from 0.623 to 0.647 but lowered the issue and ARB
suites. The neighbours displaced ranked hits more often than they added
missing files, so the enrichment was not built.

## Query vocabulary and embeddings

Issue titles reuse the code's words, so they leave embeddings little to add.
Two snapshot suites probe the other end, each scored with lexical search and
MiniLM hybrid search, both with the reranker:

| Suite | Cases | Lexical hits@10 / MRR@10 | MiniLM hits@10 / MRR@10 | Titles only one finds |
| --- | ---: | ---: | ---: | ---: |
| Low-overlap symptom tasks | 47 | 27 / 0.387 | 35 / 0.434 | 8 MiniLM, 0 lexical |
| Agents' first `ygg search` | 67 | 51 / 0.541 | 52 / 0.528 | 1 MiniLM, 0 lexical |

The low-overlap suite takes the generated symptom tasks from the history dev
split whose content words occur at most 38% in the expected files (the
agent-trial tercile), searched at the commit's parent. `jina-embeddings-v2-small-en`
scored 33 / 0.424 on it. The agent suite replays the first plain query the
control arm wrote in the structural-card and excerpt trials: a median of two
words such as `X-Sendfile` or `memmap2`, which lexical search already finds.
Embeddings pay off where the query's words differ from the code's, not on the
keyword queries agents write today.

## Hub-and-spoke projects

A project of a knowledge hub (decision records with stable codes, boundaries,
components) and code repositories needs prose-to-code matching across
repositories. No public project links the two explicitly, so
`tools/hubspoke/` generates a hub: one decision record per commit, written by
Claude Haiku from the commit as if decided beforehand, for the 51 history dev
cases in gin, hono and axios plus 90 distractor commits (141 `AD-n` records,
114 `BOUND-NNN` boundaries, khub-style folders and front matter). The records
name no files, paths or internal identifiers. Each case has three queries: the
symptom task, the task plus `See AD-n.`, and the decision text. Each repository
is searched separately (the case's spoke at the commit's parent), the lists are
merged, and the result counts a hit when an expected spoke file or the case's
record reaches the first ten merged paths.

With reciprocal-rank merging and one rerank pass over the merged first 20:

| Query | Spoke hits, lexical → MiniLM | Record hits, lexical → MiniLM |
| --- | ---: | ---: |
| Task | 24 → 31 (8 up, 1 down) | 43 → 50 (7 up, 0 down) |
| Task + code | 25 → 32 (9 up, 2 down) | 48 → 51 |
| Decision text | 26 → 32 (7 up, 1 down) | |

Generated records sit closer to the code than records written before a change,
so the gap on a real hub may differ.

### Merge policies

`ygg search --project` reranks inside each repository and then allocates the
citation budget round-robin by alias. Over all 153 queries, counting hits in the
first ten interleaved paths:

| Per-repository search | Spoke hits | Record hits (102 task queries) |
| --- | ---: | ---: |
| MiniLM, no reranker | 106 | 98 |
| MiniLM, ygg reranker (shipped) | 100 | 101 |
| Lexical, no reranker | 80 | 88 |
| Lexical, ygg reranker | 95 | 94 |

The shipped policy is within six spoke hits of the best variant and finds the
most records. Within the spoke alone the ygg reranker raises first-ten hits from
133 to 140, so the merged loss comes from the four-way budget split, not the
reranker. Reranking with result excerpts instead of ygg's own reranker input
lost 26 spoke hits with MiniLM, so emulated global reranks (one pass over the
merged first 20 or the pooled top ten of each repository) were not adopted.
Putting the hub first in the round robin raises the record's MRR from 0.244 to
0.968 and lowers spoke hits from 100 to 95; grouped output makes that order a
presentation choice, so it was left to configuration order.

### Worktrees

Each case also ran in seeded worktrees: `git worktree add` on one task branch in
every repository, the case's spoke at the commit's parent and the others at
their base heads. Members identical to their base searched in hybrid mode at
once (459 of 459 searches), since seeding copies vectors. The case's spoke
differed from its base in 9 to 717 files and fell back to lexical on its first
search every time, because hybrid activation requires complete coverage. After
`ygg index` (median 1.3 s, at most 3.6 s with MiniLM) results matched a cold
index exactly. Partial-coverage fusion was rejected earlier because it buries
freshly edited files, so the fix belongs in the host: a worktree tool's prepare
step can run `ygg index`.
