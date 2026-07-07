# Benchmarks

This directory contains tracked benchmark suite definitions and curated case
metadata. Keep it limited to reviewable benchmark inputs:

- suite EDN files
- issue text and fixed SHAs
- manually assigned problem-class tags
- curated localization ground truth
- graph expectations for bounded facts

Generated benchmark artifacts do not belong here. Keep local clones, detached
worktrees, XTDB stores, agent packets, run results, reports, claim packs, and
experimental scratch suites under `.dev/ygg/`.

Suites may use `:include-suites` to compose other tracked suites by relative
path. Includes are resolved relative to the suite file, cases are appended in
include order, and identical repo declarations are deduplicated. Use this for
reviewable selectors such as `benchmarks/agent-efficiency-broad.edn` instead of
copying case bodies into parallel suites.

Include entries may also be maps with `:path` and explicit `:case-ids` to define
lanes without copying case bodies. Filtered includes keep only the repos needed
by the selected cases, so quick lanes do not preflight or index full-lane repos.

Use `.dev/ygg/benchmark-repos/<repo-id>` as the common local checkout cache for
suite `:repos` roots. Use `.dev/ygg/bench/<suite-id>/` or an explicit
`.dev/ygg/...` `--out` path for run outputs. Promote an exploratory suite into
`benchmarks/` only when its case metadata, tags, and ground truth are curated
enough to review as source.

`benchmarks/repos.edn` is the tracked manifest for benchmark checkout inputs.
It records repo ids, clone URLs, expected cache dirs, and commits that tracked
suites need. Check local setup without creating generated artifacts:

```sh
bb bench repos check --suite benchmarks/architecture-synthetic.edn
bb bench repos check --suite benchmarks/agent-efficiency-broad.edn
bb bench repos check --suite benchmarks/multi-repo-quality.edn
bb bench repos check --suite benchmarks/task-category-broad.edn
bb bench repos check --suite benchmarks/graphify.edn
bb bench:gate --setup-check
```

Use the quick historical lane for routine shell-only versus Yggdrasil evidence:

```sh
bb agent-efficiency all \
  --suite benchmarks/historical-replay-quick.edn \
  --out .dev/ygg/agent-efficiency/historical-replay-quick
```

Treat the resulting `bb bench efficiency` broad claim as ready only when both
lanes share at least three measured problem-class groups and three measured
architecture-class groups.

Use the claim quick lane when you need a small non-synthetic claim-readiness
check with expected-evidence citation metrics. This lane is intentionally only a
selector over curated historical cases that define `:expectations
{:citation-evidence ...}`:

```sh
bb bench:claim-quick --setup-check
bb bench:claim-quick --skip-existing
```

`bb bench:claim-quick` uses the deterministic Yggdrasil baseline, stores
artifacts under `.dev/ygg/claim-quick-gate`, and gates expected-evidence
citation coverage with an aggregate floor of `0.80` and per-case floor of
`0.50`. It keeps the regular recall floors and uses a non-synthetic readiness
MRR floor of `0.30` plus an aggregate `noise@20` ceiling of `0.80`. Broad
claim readiness must include all six curated repos, scoreable cases across the
tracked JavaScript, Python, docs, .NET, Terraform, SQL, and text source-kind mix, at
least three measured problem-class groups, and at least three measured
architecture-class groups. The wrapper also requires the generated report's
own `claimReadiness` field to be supported. The full historical replay remains
the authoritative claim lane. Regenerating the gate reuses compatible baseline
context manifests by default, keyed by benchmark options and a Yggdrasil
implementation fingerprint. Use `--fresh-context` when profiling full rebuild
cost or intentionally replacing context artifacts.

Agent reports also carry generic claim-readiness requirements. A report must
include at least six benchmark repos, seven declared source-kind groups, and no
missing declared source-kind coverage runs before it can support broad
real-world claims. Once source-kind breadth and declared coverage are complete,
at least two source-kind groups must have two or more scoreable cases, and those
measured source-kind lanes must meet `file-recall@10 >= 0.80` and
`MRR >= 0.50`. It also needs at least three measured problem-class groups and
three measured architecture-class groups in non-synthetic replay cases, and
expected-evidence citation quality must meet the aggregate `0.80` and per-case
`0.50` floors; wrapper gates such as `bb bench:claim-quick` also reject blocking
hint diagnostics and add suite-specific per-source-kind floors.

Use the docs claim lane when the claim is specifically about documentation
handling. It is a non-synthetic selector over historical doc edit cases, stores
artifacts under `.dev/ygg/docs-claim-gate`, gates expected evidence with the
same floors as `bb bench:claim-quick`, uses the same `0.30` MRR floor, and keeps
the deterministic gate `noise@20` ceiling of `0.90`. It additionally rejects
blocking hint diagnostics, requires at least
three completed repos, four completed cases with scoreable `doc` source-kind
coverage, one completed docs-adjacent `ci` source-kind case, two scoreable
source-kind groups in report readiness, and at least one measured docs
problem/architecture class:

```sh
bb bench:docs-claim --setup-check
bb bench:docs-claim --skip-existing
```

Use the task-category lane to test should-win planning, implementation, and
review/decision tasks as separate measured problem classes. Should-win cases
should exercise composed Yggdrasil recall: graph topology, parser facts,
lexical/grep matches, semantic similarity when embeddings are configured, and
accepted correction/history facts where relevant. Treat lexical-only,
semantic-only, graph-only, local-vector, Codebase Memory, and Graphify runs as
ablation or comparison lanes, not the default should-win path:

Tag should-win cases with explicit `recall-*` coverage metadata so benchmark
claims can be audited by recall shape. Use `recall-hybrid` for cases that are
expected to need composed Yggdrasil context, and add `recall-graph`,
`recall-lexical`, or `recall-semantic` for the concrete recall signals the case
is intended to exercise. The broad should-win lane must retain hybrid and
semantic recall coverage; single-mode recall tags are ablation labels, not the
default product path. Every case included in `task-category-broad.edn` should be
tagged with all of `recall-hybrid`, `recall-graph`, `recall-lexical`, and
`recall-semantic` so the should-win lane stays about composed recall.

```sh
bb bench repos check --suite benchmarks/task-category-broad.edn
bb agent-efficiency all \
  --suite benchmarks/task-category-broad.edn \
  --out .dev/ygg/agent-efficiency/task-category-broad
```

Use the full lane as the authoritative non-synthetic claim lane, including the
heavy multi-repo replay case:

```sh
bb bench:claim-full --check-only

bb agent-efficiency all \
  --suite benchmarks/historical-replay-full.edn \
  --out .dev/ygg/agent-efficiency/historical-replay-full
```

`bb bench:claim-full` is the strict deterministic full-lane gate. It requires
all 16 historical replay cases, ten repos, the full tracked source-kind mix,
measured source-kind localization quality, expected-evidence citation quality,
three measured problem-class groups, three measured architecture-class groups,
and supported broad claim readiness. Use it before treating full-lane artifacts
as broad real-world evidence.

The deterministic gate runs the same preflight before doing benchmark work. Its
default suite is synthetic architecture coverage, so it is useful as a
diagnostic gate but is not standalone broad real-world proof:

```sh
bb bench:gate
```

Every deterministic gate also writes `stage-time-gate.json` under its output
root so indexing, embedding, context-packet, execution, and scoring time remain
visible even when no timing thresholds are configured. The default gate also
requires expected-evidence citation quality: aggregate
`expectedEvidenceCitationRate >= 0.80` and every selected case
`case.expectedEvidenceCitationRate >= 0.50`. Pass explicit lower thresholds only
for focused debugging or ablation work. Because synthetic-only suites now fail
claim readiness, broad real-world claims should use a non-synthetic lane such as
`bb bench:claim-quick` or the full historical replay lane. Claim readiness also
requires measured problem and architecture-class coverage in non-synthetic
replay cases; the quick broad claim lane enforces at least three measured groups
of each kind.

Baseline regeneration uses `--reuse-context` by default so repeated gates can
refresh scores and preflight without rebuilding unchanged context packets. The
cache key includes the selected case input, parser worker, retrieval and
embedding options, index options, and a source-content fingerprint for the
Yggdrasil implementation. Pass `--fresh-context` to force full graph, embedding,
and context-packet regeneration.

When current score artifacts already exist, use the cheaper claim check:

```sh
bb bench:gate --check-only
```

This still runs checkout preflight and strict `agent-check` thresholds, including
stale-artifact, benchmark claim-readiness, and per-case estimated context
packet token budgets, but skips baseline regeneration. If current artifacts were
created before deterministic baseline token estimates were recorded, run the full
gate once to refresh them before using check-only mode for token claims. For
broad real-world claims, prefer `bb bench:claim-quick --check-only` against the
non-synthetic claim lane, or `bb bench:claim-full --check-only` when using the
authoritative full historical replay lane. For documentation-handling claims, prefer
`bb bench:docs-claim --check-only`.

If a checkout still exists under the legacy `.dev/oss-test-cases/repos/` cache,
the preflight reports that path. Move or symlink it into
`.dev/ygg/benchmark-repos/`; do not commit the checkout or gate output.
