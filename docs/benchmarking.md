# Benchmarking Yggdrasil

Yggdrasil benchmarks replay real project work from a historical source tree. The
primary benchmark is agent issue replay: start from the commit before a fix,
give an agent the issue text plus a base checkout, collect ranked suspected
files, and compare that output with the actual fixing diff.

The first target is localization quality, not autonomous patching. Reliable
localization scores are cheap to run, easy to debug, and strong enough to catch
regressions in source support, extraction, ranking, and system inference. The
retrieval runner is still useful, but treat it as a diagnostic for why an agent
did or did not get useful help.

Tracked starter suites:

- `benchmarks/architecture-synthetic.edn`: diagnostic architecture-class
  suite with synthetic tasks on real benchmark checkouts.
- `benchmarks/headline.edn`: small architecture-first headline suite for
  shell-only versus Yggdrasil agent-efficiency runs. Generated lane outputs should
  still live under `.dev/ygg/`.
- `benchmarks/agent-efficiency-broad.edn`: common broad shell-only versus
  Yggdrasil comparison suite composed from tracked selectors with
  `:include-suites`.
- `benchmarks/task-category-broad.edn`: should-win planning, implementation,
  and review/decision selector. These cases should require composed recall from
  graph topology, parser facts, lexical/grep matches, semantic similarity, and
  accepted correction/history facts where available; lexical-only wins are
  useful controls but not the target claim.

Tracked benchmark suites use local checkout inputs under
`.dev/ygg/benchmark-repos/`. The manifest `benchmarks/repos.edn` records the
repo ids, clone URLs, cache dirs, and commits required by tracked suites. Check
setup before running a gate:

```sh
bb bench repos check --suite benchmarks/architecture-synthetic.edn
bb bench:gate --setup-check
```

`bb bench:gate` runs the same setup check before deterministic baseline work
and always writes `stage-time-gate.json` under the output root. Its default
suite is synthetic architecture coverage, so use it as a diagnostic gate, not
as standalone broad real-world proof. Check-only mode skips baseline
regeneration but still rejects missing, stale, unverified, graph-failing,
benchmark-preflight-blocked, or per-case estimated context-packet token-budget
violating score artifacts. It also rejects blocking hint diagnostics while still
allowing non-blocking hint diagnostics to remain visible in the report. If
artifacts predate deterministic baseline token estimates, run the full gate once
before using check-only mode for token claims.

Use `bb bench:claim-quick` for the small non-synthetic claim-readiness lane,
including immediately before architecture or extractor claims when current score
artifacts already exist. Broad claim readiness requires measured problem and
architecture-class coverage in non-synthetic replay cases.
It runs `benchmarks/historical-replay-claim-quick.edn`, stores artifacts under
`.dev/ygg/claim-quick-gate`, and gates expected evidence with
`--min-expected-evidence-citation-rate 0.80` and
`--min-case-expected-evidence-citation-rate 0.50` by default. It keeps the
standard recall floors, uses `--min-mrr 0.30`, and enforces
`--max-noise-at-20 0.80` because the lane checks historical claim-readiness
rather than synthetic exact-rank behavior. It rejects blocking hint diagnostics
with `--max-blocking-hint-diagnostic-runs 0`. It also enforces at least three
measured problem-class groups, three measured architecture-class groups, six
completed repos, and scoreable cases across the tracked JavaScript, Python,
docs, .NET, Terraform, SQL, and text source-kind mix so broad claims are not backed
by a single real-world slice. The wrapper also passes
`--require-broad-claim-readiness`, so `agent-check` fails if the generated
report's own `claimReadiness` field is not supported. The full historical
replay remains the authoritative claim lane.

Use `bb bench:claim-full` for the strict deterministic full historical replay
claim lane. It runs `benchmarks/historical-replay-full.edn`, stores artifacts
under `.dev/ygg/full-claim-gate`, and keeps the same expected-evidence,
blocking-hint, MRR, noise, problem-class, architecture-class, and broad claim
readiness gates as the quick lane. It also requires all 16 historical replay
cases, ten completed repos, and the full tracked source-kind mix, including the
extra CI, manifest, and Java cases that are outside the quick lane. Use
`--check-only` only when current full-lane score artifacts already exist; a
partial full-lane artifact should fail this wrapper instead of supporting a broad
claim.

Use `bb bench:docs-claim` for documentation-handling claims. It runs the
non-synthetic selector `benchmarks/historical-docs-claim-quick.edn`, stores
artifacts under `.dev/ygg/docs-claim-gate`, gates expected evidence with the
same floors as `bb bench:claim-quick`, uses the same `0.30` MRR floor, and keeps
the default deterministic gate `noise@20` ceiling of `0.90` for single-file docs
edit cases. It rejects blocking hint diagnostics with
`--max-blocking-hint-diagnostic-runs 0`. It also requires at least three
completed benchmark repos and four completed cases with scoreable `doc`
source-kind coverage, plus at least one measured docs problem/architecture
class. The wrapper also passes
`--require-docs-claim-readiness`, so `agent-check` fails unless the generated
report's `docsClaimReadiness` field supports docs-handling claims. Use
`--check-only` only when current score artifacts already exist.

If a checkout exists only under the legacy `.dev/oss-test-cases/repos/` cache,
the preflight reports that path so it can be moved or symlinked into the common
cache without committing generated files.

## Discovery Changes

File-discovery changes are setup/indexing work. Benchmark them with
`bb bench:gate` and compare the canonical file rows that survive Yggdrasil
filters, not raw path lists from a discovery backend.

`scan-files` records discovery instrumentation in result metadata, and
`scan-file-coverage` includes the same bounded `discovery` summary:

- `backend`: `ripgrep`, `git`, or `filesystem`
- `elapsed-ms`: backend elapsed time
- `path-count`: candidate paths after Yggdrasil ignored-path filtering
- `diagnostics`: bounded backend diagnostics
- `fallbacks`: unavailable earlier backends

Use these fields to report scan timing and backend selection. Do not count
`rg --files` setup gains as warm-agent command, token, or task-duration wins.
Warm-agent reports may mention discovery backend and elapsed time only as
amortized setup context.

Fallbacks should be benchmarked and tested as lazy. If `ripgrep` succeeds,
`git ls-files` should not run just to populate comparison telemetry. Compare
canonical `scan-files` rows against a forced fallback only in focused checks or
benchmark harnesses, not in the normal sync path.

## Query Availability Latency

Use the query-availability benchmark to compare the same fixed-string patterns
and repository scope across raw ripgrep, the in-process filesystem lane, and a
cold end-to-end `ygg query` invocation:

```sh
bb bench:query-availability \
  --repo . \
  --query "filesystem query fallback" \
  --iterations 20 \
  --out .dev/reports/query-availability.json
```

The report records min, mean, p50, p95, maximum, completion, and timeout counts.
`comparison.rawParitySupported` is deliberately strict: it is true only when
cold Yggdrasil p95 is no slower than raw ripgrep p95. Expect wrapper and JSON
packet overhead to make that false on small repositories; report the absolute
overhead as well as the ratio. `contract.sameRipgrepArgv` and
`oneFilesystemProcessPerRepo` distinguish orchestration overhead from an extra
repository scan. This lane measures availability latency only; it does not
support agent-effectiveness or architecture-quality claims.

## Headline Suite

Use the tracked headline suite to compare shell-only and Yggdrasil-assisted agents
on architecture-oriented tasks. Generated lane outputs stay under
`.dev/ygg/headline-bench/` by default.

The bounded headline hypothesis is intentionally narrow; make it a claim only
when the generated comparison report supports it for the named problem classes:

> For architecture-class tasks involving dependencies, runtime/config, or
> audit-scope evidence, Yggdrasil may help agents find the right evidence with less
> exploratory work than shell-only workflows.

The checked-in suite has five fixed cases, not an open-ended benchmark program:
docs route impact, dependency/config wiring, database runtime ownership,
JavaScript runtime boundary, and Dapper JSONB dependency/container evidence. The
Axios runtime-boundary case is tagged `:shell-sufficient-control` because it is
expected to be comparatively easy for ordinary shell exploration; it keeps the
comparison from only selecting cases where Yggdrasil should obviously help. Add a
regression or inconclusive control only when there is an observed candidate from
real lane output, not as synthetic ballast.

Repeatable helper workflow:

```sh
bb headline baseline
bb headline codebase-memory
bb headline external-baselines
bb headline agents
bb headline reports
bb headline token-check
bb headline compare
```

Use `bb headline all` for a full local run. Pass `--suite`, `--out`, `--agent`,
`--command`, `--prompt-profile`, and `--timeout-ms` to make a run comparable
across machines or agent CLIs. Add `--dry-run` to print the exact commands
without launching agents. `bb headline all` runs token telemetry gates before the
claim pack; use `--skip-token-check` only for diagnostic runs that should not
support token-use claims.

For the common broad comparison, use the same helper through the broad-suite
alias:

```sh
bb agent-efficiency all --agent codex-efficiency --timeout-ms 600000
```

It defaults to `benchmarks/agent-efficiency-broad.edn` and
`.dev/ygg/agent-efficiency/broad`, keeping the generated lane output out of the
tracked repo.

The helper wraps the existing benchmark commands. Use the raw command form below
when debugging one lane or when a CI job needs each phase split explicitly.

Deterministic Yggdrasil baseline:

```sh
bb bench agent-baseline benchmarks/headline.edn \
  --out .dev/ygg/headline-bench/ygg-baseline

bb bench agent-report benchmarks/headline.edn \
  --mode ygg \
  --agent ygg-baseline-auto \
  --out .dev/ygg/headline-bench/ygg-baseline
```

Codebase Memory MCP comparison baseline:

```sh
bb bench agent-baseline benchmarks/headline.edn \
  --retriever codebase-memory \
  --out .dev/ygg/headline-bench/codebase-memory

bb bench agent-report benchmarks/headline.edn \
  --mode codebase-memory \
  --agent ygg-baseline-codebase-memory \
  --out .dev/ygg/headline-bench/codebase-memory
```

External agent lanes:

```sh
bb bench agent-run benchmarks/headline.edn \
  --mode shell-only \
  --agent codex \
  --command 'codex -a never exec --sandbox read-only --output-schema "$YGG_BENCH_OUTPUT_SCHEMA" -o "$YGG_BENCH_RESULT" "$(cat "$YGG_BENCH_PROMPT")"' \
  --prompt-profile fast \
  --out .dev/ygg/headline-bench/shell-only

bb bench agent-run benchmarks/headline.edn \
  --mode ygg \
  --agent codex \
  --command 'codex -a never exec --sandbox read-only --output-schema "$YGG_BENCH_OUTPUT_SCHEMA" -o "$YGG_BENCH_RESULT" "$(cat "$YGG_BENCH_PROMPT")"' \
  --prompt-profile fast \
  --out .dev/ygg/headline-bench/ygg
```

Generate lane reports and compare them:

```sh
bb bench agent-report benchmarks/headline.edn \
  --mode shell-only \
  --agent codex \
  --out .dev/ygg/headline-bench/shell-only

bb bench agent-report benchmarks/headline.edn \
  --mode ygg \
  --agent codex \
  --out .dev/ygg/headline-bench/ygg

bb bench efficiency \
  .dev/ygg/headline-bench/shell-only/agent-report.json \
  .dev/ygg/headline-bench/ygg/agent-report.json \
  --out .dev/ygg/headline-bench/summary.json \
  --markdown-out .dev/ygg/headline-bench/REPORT.md
```

Read `Problem-class signals`, `Architecture-class signals`, and
`Claim readiness` together. A headline result is useful only when the compared
lanes share completed cases, architecture-class tags are measured, evidence
quality is available, expected-evidence citation metrics meet the claim floors,
decision-quality metrics are comparable when decision cases are configured, and
the report remains claim-ready. Report-level claim readiness requires at least
six benchmark repos, seven declared source-kind groups, and zero missing
declared source-kind coverage runs. It also requires at least three measured
problem-class groups and three measured architecture-class groups in
non-synthetic replay cases; expected-evidence citation quality must meet the
aggregate `0.80` and per-case `0.50` floors. Lane wrapper gates may require
stricter per-source-kind floors. In `--json` output, read
`compactSummary.verdict` first for the bounded helped/regressed/inconclusive
answer and `compactSummary.why` for the short reason list. Then inspect
`classSignals.problemClasses` and `classSignals.architectureClasses`; a row with
`measured: false` is useful context but does not count toward broad claim
readiness. Use `classSignals.summary.measuredProblemClasses` and
`classSignals.summary.measuredArchitectureClasses` for automated gates. Broad
efficiency readiness requires at least three shared measured problem-class
groups and three shared measured architecture-class groups. Treat
`improvementTargetRuns` as lower-is-better: a run that improves recall but
introduces more remediation targets is a mixed result, not a broad efficiency
win. Generated files under `.dev/ygg/headline-bench/` are disposable
artifacts.
Use `summary.json` for machine gates and `REPORT.md` for a compact human review
of the same comparison.

## Workflow

Create a benchmark suite EDN file:

```clojure
{:id "issue-replay"
 :project-id "issue-replay"
 :repos [{:id "penpot"
          :root ".dev/ygg/benchmark-repos/penpot"}]
 :cases [{:id "penpot-example"
          :repo-id "penpot"
          :coverage {:source-kinds [:code :typescript]}
          :base-sha "BASE_COMMIT_BEFORE_FIX"
          :fix-sha "FIX_COMMIT_OR_MERGE_COMMIT"
          :issue {:id 1234
                  :title "Issue title"
                  :body "Issue body"
                  :comments []}}]}
```

Run the suite:

```sh
bb bench prepare benchmark.edn
bb bench agent-packet benchmark.edn --case penpot-example --json
bb bench agent-baseline benchmark.edn --case penpot-example
bb bench agent-baseline benchmark.edn --case penpot-example --retriever local-vector --vector-model sentence-transformers/all-MiniLM-L6-v2
bb bench agent-baseline benchmark.edn --case penpot-example --retriever codebase-memory --codebase-memory-bin /path/to/codebase-memory-mcp
bb bench agent-run benchmark.edn --agent codex --command 'codex -a never exec --sandbox read-only --output-schema "$YGG_BENCH_OUTPUT_SCHEMA" -o "$YGG_BENCH_RESULT" "$(cat "$YGG_BENCH_PROMPT")"' --mode ygg --prompt-profile fast --timeout-ms 120000
bb bench agent-score benchmark.edn --case penpot-example --result agent-result.json
bb bench agent-report benchmark.edn
bb bench improve benchmark.edn --mode ygg --agent ygg-baseline-auto --out .dev/reports/bench
bb bench agent-check benchmark.edn --mode ygg --agent ygg-baseline-auto --min-cases 4 --min-runs 4 --min-file-recall-at-10 1.0 --min-case-file-recall-at-10 1.0 --min-mrr 1.0 --min-case-mrr 1.0 --min-evidence-citation-rate 1.0 --min-path-evidence-citation-rate 1.0 --min-expected-evidence-citation-rate 1.0 --min-case-evidence-citation-rate 1.0 --min-case-path-evidence-citation-rate 1.0 --min-case-expected-evidence-citation-rate 1.0 --max-total-tokens 120000 --max-case-total-tokens 30000 --max-noise-at-20 0.5 --max-case-noise-at-20 0.75
bb bench agent-check benchmarks/decision-quality-pilot.edn --mode ygg --agent codex --min-decision-f1 0.8 --min-case-decision-f1 0.6 --min-decision-evidence-citation-rate 0.8 --max-missing-decision-runs 0
bb bench agent-compare benchmark.edn --baseline-report .dev/ygg/bench-before/agent-report.json --candidate-report .dev/ygg/bench-after/agent-report.json
bb bench claim-pack benchmark.edn --shell-report .dev/ygg/agent-efficiency/shell-only/agent-report.json --ygg-report .dev/ygg/agent-efficiency/ygg/agent-report.json --out .dev/reports/claim-pack
bb bench run benchmark.edn
bb bench report benchmark.edn
bb bench show benchmark.edn --case penpot-example
```

Generated worktrees, XTDB stores, and result JSON files live under
`.dev/ygg/bench/<suite-id>/` by default. Use `--out` to choose another
generated output root.

Decision-quality cases add visible `:decision-candidates` and hidden
`:decision-ground-truth` to ordinary benchmark cases. Agents return an optional
`decision` block with candidate ids, `include|exclude|defer` status, confidence,
reason, and evidence strings. The scorer does only deterministic id math:
required ids found, forbidden ids wrongly included, deferred required ids, and
exact-path citations against candidate paths. Use this for complex-system
questions where the target is a defensible architecture, maintenance, audit, or
plugin-fit choice, not just a shorter suspected-file list.

```clojure
{:decision-candidates [{:id "plan-config-and-manifest"
                        :kind :change-plan
                        :paths ["site/astro.config.ts" "package.json"]}
                       {:id "plan-config-only"
                        :kind :change-plan
                        :paths ["site/astro.config.ts"]}]
 :decision-ground-truth {:kind :change-plan
                         :required ["plan-config-and-manifest"]
                         :forbidden ["plan-config-only"]}}
```

## Commands

- `bench prepare <suite.edn>` computes ground truth from `git diff
  <base-sha> <fix-sha>` and writes one prepared case artifact per case.
- `bench agent-packet <suite.edn>` writes an agent-localization packet for each
  selected case. Use `--case <case-id>` for one case, `--mode ygg` to give
  the agent Yggdrasil command hints, or `--mode shell-only` for a baseline.
- `bench agent-baseline <suite.edn>` generates a deterministic Yggdrasil-assisted
  agent result from the same context docs/entities an agent receives, writes the
  agent-result JSON, and scores it. Use this as the repeatable regression
  baseline before running slower human or LLM agent trials. The default
  retriever is `auto`: use hybrid semantic/lexical/grep/graph recall when an
  embedding client is configured, and report explicit lexical fallback when
  semantic recall is unavailable. By default it keeps a ranked suspected-file
  list of twenty files and still writes the full context packet; use
  `--retriever lexical`, `--retriever semantic`, or `--retriever hybrid` for
  ablation/debug lanes, `--limit <n>` to change the suspected-file shortlist size,
  `--doc-limit <n>` to change the snippet-bearing source context size, and
  `--retrieval-limit <n>` to change the compact candidate-file pool without
  adding more snippets. The default retrieval limit is intentionally wider than
  the snippet limit so lower-ranked but relevant companion files can still be
  selected. Limited Yggdrasil baselines reserve a small slice of the shortlist for
  candidate-file-only evidence so compact file/path matches are not completely
  crowded out by snippet-bearing retrieved docs. JSON output includes a
  `timings` aggregate with stage totals for indexing, embeddings, context
  packet construction, scoring, and artifact writing. Use `--reuse-context`
  for repeat full-lane runs when the indexed context manifest still matches the
  case, parser worker, and retrieval/index options; the run reuses the context
  packet but still regenerates deterministic result and score artifacts. With
  `--skip-existing`,
  current score artifacts are reused and returned as skipped baselines while
  retaining their previous stage profile, so repeat runs are fast but still show
  where the cached full run spent time.
  Use `--retriever local-vector` to run an optional local semantic-vector
  control lane instead of the graph/context packet. The default worker is
  `python3 scripts/local-vector-baseline.py`, which uses
  `sentence-transformers` locally and writes the current agent-result contract,
  including the case fingerprint and citation evidence, for the existing
  scorer. Install the optional worker dependencies in a local
  environment with `python3 -m venv .dev/ygg/local-vector-venv &&
  .dev/ygg/local-vector-venv/bin/python -m pip install -r
  scripts/local-vector-requirements.txt`, then pass
  `--vector-command '.dev/ygg/local-vector-venv/bin/python
  scripts/local-vector-baseline.py'`. Use `--vector-model <model>` to choose a
  local model and `--vector-command <cmd>` to replace the worker. The command
  receives `REQUEST_JSON RESULT_JSON MODEL` arguments. This lane is for
  benchmark diagnosis: if it beats the graph baseline, inspect whether Yggdrasil is
  missing extractor facts, ranking useful facts poorly, or losing on vocabulary
  mismatch. It does not make Yggdrasil core depend on a vector provider. Use
  `--retriever codebase-memory` to run a Codebase Memory MCP comparison lane.
  Yggdrasil does not install or configure Codebase Memory; provide an installed
  binary through `CODEBASE_MEMORY_MCP_BIN` or `--codebase-memory-bin`. The
  default worker is `python3 scripts/codebase-memory-baseline.py`; override it
  with `--codebase-memory-command <cmd>` when testing worker changes. Generated
  Codebase Memory cache files stay under the benchmark output root by default,
  or under `--codebase-memory-cache-dir <dir>` when set. This lane measures
  Codebase Memory's structural retrieval/localization as a deterministic
  benchmark baseline. It is not the same as giving an LLM the Codebase Memory
  MCP tools during an agent run. Use `--retriever graphify` to run a Graphify
  comparison lane against the base checkout. The default worker is
  `python3 scripts/graphify-baseline.py`; it shells out to
  `uvx --from graphifyy graphify` unless `GRAPHIFY_BENCH_CMD` or
  `--graphify-bin <cmd>` is set. Override the worker itself with
  `--graphify-command <cmd>` when testing worker changes. Graphify extraction
  output stays under the case output root in `graphify/` unless
  `--graphify-output-dir <dir>` is set. By default this lane excludes non-code
  files during extraction so it is replayable without Graphify document/image
  LLM keys; pass `--graphify-include-non-code` only when the required Graphify
  providers are configured. Use `--graphify-query-budget <n>` and
  `--graphify-max-workers <n>` to control Graphify query budget and extraction
  parallelism. Use `--skip-existing` to resume an interrupted suite run. A case
  is skipped only
  when exactly one current score artifact already matches the case fingerprint,
  agent id, mode, and result path; stale, duplicate, or missing artifacts are
  rerun. When the final score is missing but the baseline context manifest still
  matches the case, parser worker, and retrieval/index options, the resumed run
  reuses that context packet and regenerates the deterministic result and score.
- `bench improve <suite.edn>` reads the same agent score artifacts as
  `bench agent-report`, writes an updated `agent-report.json`, and writes
  `system-improvement-report.json`. The report is dev-time guidance, not a
  repair workflow: it does not enqueue `sync work`, does not patch
  correction facts, and does not waive benchmark failures. It groups benchmark
  diagnostics into `benchmark-readiness-gap`, `indexing-gap`, `extractor-gap`,
  `retrieval-gap`, `decision-quality-gap`, `benchmark-suite-gap`, and
  `agent-protocol-gap` lanes with affected cases, declared
  problem/architecture class rollups, evidence, owner area, confidence, and a
  recommended system change.
- `bench agent-run <suite.edn> --agent <id> --command <cmd>` prepares the same
  packet contract, runs an external coding-agent command from the base
  worktree, and scores the JSON result written to `$YGG_BENCH_RESULT`. The
  command receives `$YGG_BENCH_PROMPT`, `$YGG_BENCH_PACKET`,
  `$YGG_BENCH_RESULT`, `$YGG_BENCH_TOKEN_USAGE`,
  `$YGG_BENCH_WORKTREE`, `$YGG_BENCH_PROJECT`,
  `$YGG_BENCH_XTDB_PATH`, `$YGG_BENCH_CASE_ID`,
  `$YGG_BENCH_AGENT_ID`, `$YGG_BENCH_MODE`, and
  `$YGG_BENCH_OUTPUT_SCHEMA`. In `--mode ygg`, the command also receives
  `$YGG_BENCH_YGG_HINTS`, a compact agent-facing Yggdrasil summary, and
  `$YGG_BENCH_YGG_CONTEXT`, the fuller precomputed context JSON artifact;
  both are generated from the base checkout. The prompt file is the stable
  agent input; it points to the packet and required result JSON path without
  exposing hidden ground truth. Use the schema env var with agent CLIs that
  support structured output; the generated schema is intentionally closed so
  strict structured output providers can validate it. For structured-output
  runners that capture the final response into `$YGG_BENCH_RESULT`, the
  agent should return the JSON as its final response instead of trying to write
  the file from inside its sandbox. Provider wrappers may write token usage to
  `$YGG_BENCH_TOKEN_USAGE` as JSON with `inputTokens`/`outputTokens` or
  `input_tokens`/`output_tokens`; Yggdrasil folds that sidecar into
  `tokenUsage` when the result JSON does not already contain token usage. Use
  `python3 $(pwd)/scripts/codex-benchmark-agent.py` for Codex CLI runs so
  Codex JSONL usage events become a token sidecar. The
  deterministic Yggdrasil baseline records `tokenUsage.source =
  "ygg-context-packet-estimate"` with `inputTokens` equal to the estimated
  context packet size, so tracked gates can catch packet-size regressions even
  without provider billing telemetry. Use `--prompt-profile fast` for short
  localization-only smoke runs that should avoid patching and full test suites;
  omit it for the standard prompt. In `--mode ygg`, the graph, hints, and
  context artifacts are prepared before the command runs; in `--mode
  shell-only`, only the checkout and packet are provided. Prefer the hints
  artifact first and the context artifact for supporting snippets: sandboxed
  coding agents may block live XTDB/Clojure commands even when the graph store
  is writable. The hints artifact includes compact indexed `sourceCoverage` so
  agents can distinguish source-support gaps from ranking misses before opening
  the full context packet. When a case declares `coverage.source-kinds`, hints
  filter top file candidates to those mechanical source kinds so agents see the
  same scope that scoring uses. Hints also include `selection` counters for raw
  candidates, coverage-filtered candidates, applied limits, and coverage source
  kinds so benchmark misses can be debugged from the same artifact the agent
  saw. Architecture hints preserve bounded accepted systems, candidates,
  rejected corrections, boundary/runtime/deploy/dependency evidence, docs, open
  decisions, evidence-family rows, validation gaps, summary counts, and next
  actions. When those counters point at help-quality problems, hints include
  `diagnostics` rows for zero candidate files, coverage-filtered candidate
  files, missing declared source kinds, and indexed source extraction
  diagnostics. Audit-scope counters with skipped files, extractor diagnostics,
  or unclassified extractor rows also produce hint diagnostics so benchmark
  reports can distinguish audit-scope family gaps from ranking misses. Hint
  rows carry severity: info rows such as expected coverage filtering remain
  telemetry, while warning/error rows feed the generic `hint-diagnostics`
  improvement target. Hints and deterministic Yggdrasil baseline results flatten
  context
  drilldowns, query evidence and packet-level `nextActions`, and
  `architecture.validationGaps.nextActions` into `commands` so agents see
  bounded follow-up checks without inspecting nested context JSON.
  `sourceCoverage.diagnostics.samples` carries a bounded set of
  file/stage/message rows for indexed extraction diagnostics so agents can jump
  straight to concrete parser or extractor failures; tight context budgets
  compact this coverage payload before dropping it. Use `--timeout-ms <n>` to
  bound long-running agents. Use `--skip-existing` to resume interrupted agent
  runs with the same current-score matching rules as `agent-baseline`.
- `bench agent-score <suite.edn> --case <case-id> --result result.json` scores
  one agent result JSON against hidden ground truth. For Yggdrasil-mode results,
  rescoring also refreshes compatible sibling context ranks, hint diagnostics,
  current graph expectations, and benchmark preflight from existing benchmark
  artifacts when the result path matches the recorded agent run.
- `bench agent-report <suite.edn>` aggregates existing agent score artifacts
  across selected cases. Use `--mode ygg` or `--mode shell-only` to compare
  one benchmark mode at a time, and `--agent <agent-id>` to target one
  repeatable agent run. Reports also include `caseProgress` and `timings`
  derived from each case `progress.json`, so interrupted or partial runs still
  show running/failed cases, slowest cases, and cumulative stage timing. Each
  running case includes `activeStage` and `activeElapsedMs`, and the active
  stage contributes to `elapsedMs` while the report is generated. Benchmark
  stages install a shutdown hook so new interrupted runs record a failed
  `interrupted` progress event instead of leaving the stage indefinitely
  running. Each
  result also includes `localization`, a compact diagnostic with scoreable
  files, per-file ranks, misses, coverage exclusions, and files found outside
  the top 5, 10, and 20. Reports also include `agentDiagnostics`, which counts
  empty rankable outputs, zero-candidate Yggdrasil packets, coverage-filtered
  runs and candidate files, warning-bearing runs, and missing predicted paths so
  benchmark failures point to the next mechanical fix instead of only reporting
  a score. When Yggdrasil-mode runs generated hint diagnostics, reports count
  all rows by kind, and separately count blocking warning/error rows for
  result-health improvement targets.
  Yggdrasil-mode reports also include `benchmarkPreflightDiagnostics`, which
  records whether each run had completed index and inference summaries,
  configured graph expectations where required, clean warning/error hint
  diagnostics, and a sync/check-equivalent validation-gaps status. Shell-only
  reports mark this preflight as `not-applicable`; Yggdrasil reports must pass it
  before the lane is claim-ready for benchmark claims.
  Report-level claim readiness also records `repoIds`, `sourceKindKeys`,
  `minimumReposForBroadClaim`, and `minimumSourceKindsForBroadClaim`; broad
  reports need at least six repos, seven declared source-kind groups, three
  measured problem-class groups, three measured architecture-class groups,
  expected-evidence citation quality above the report floors, and no
  `missingDeclaredSourceKindRuns` before they can support broad real-world
  claims.
  Decision-quality reports include `decisionDiagnostics`, which counts
  configured decision runs, missing decision outputs, missed required choices,
  wrongly included choices, unknown ids, uncited choices, and grouped choice-gap
  rows. Aggregate scores include `decisionRecall`, `decisionPrecision`,
  `decisionF1`, and `decisionEvidenceCitationRate` when selected score
  artifacts contain decision metrics.
  Reports also include `improvementSummary`, a compact ordered list of
  mechanically derived remediation targets such as extraction/retrieval gaps,
  ranking or context-budget misses, citation gaps, coverage declaration issues,
  graph expectation failures, graph benchmark preflight gaps, and artifact
  hygiene.
  Reports also include `artifactDiagnostics`, which classifies score artifacts
  as current, legacy, or stale against the current score schema and suite case
  fingerprint so old scores cannot silently stand in for changed issue text,
  commits, coverage, curated ground truth, or scorer semantics.
  Obsolete score schemas are counted separately with the actual schema values
  and expected current schema so CI output can distinguish rerun-required
  scorer changes from stale case fingerprints.
  Obsolete agent-result schemas are counted separately as well, because a score
  can be freshly regenerated from an old result artifact whose shape no longer
  satisfies the current evidence and fingerprint contract.
  Unverified score artifacts are excluded from aggregate report scores by
  default; use `--allow-unverified-scores` only for forensic inspection of older
  benchmark runs.
  Reports also include `coverageDiagnostics`, both at the top level and in
  grouped summaries, with case IDs for missing declared source kinds,
  coverage-excluded ground-truth files, and unsupported ground-truth files. Use
  this before tuning ranking: a miss caused by source coverage or support gaps
  needs extractor or suite-scope work, while a miss with covered source kinds is
  more likely a retrieval/ranking issue.
- `bench agent-check <suite.edn>` aggregates agent score artifacts, writes an
  `agent-check.json`, and exits non-zero when selected cases are missing or
  thresholds fail. Useful gates include `--min-cases`, `--min-runs`,
  `--min-file-recall-at-5`, `--min-file-recall-at-10`,
  `--min-file-recall-at-20`, `--min-case-file-recall-at-5`,
  `--min-case-file-recall-at-10`, `--min-case-file-recall-at-20`, `--min-mrr`,
  `--min-case-mrr`, `--min-evidence-citation-rate`,
  `--min-path-evidence-citation-rate`,
  `--min-expected-evidence-citation-rate`,
  `--min-decision-f1`, `--min-decision-evidence-citation-rate`,
  `--min-case-evidence-citation-rate`,
  `--min-case-path-evidence-citation-rate`,
  `--min-case-expected-evidence-citation-rate`, `--min-case-decision-f1`,
  `--max-total-tokens`, `--max-input-tokens`, `--max-output-tokens`,
  `--max-cost-usd`, `--max-case-total-tokens`,
  `--max-case-input-tokens`, `--max-case-output-tokens`,
  `--max-case-cost-usd`,
  `--max-noise-at-20`, `--max-case-noise-at-20`,
  `--max-input-hinted-cases`, `--max-unsupported-ground-truth-files`,
  `--max-empty-result-runs` to fail when agents produce no rankable suspected
  files, `--max-missing-predicted-file-runs` to fail when agents predict paths
  that do not exist in the base checkout, `--max-missing-decision-runs` to fail
  when decision cases lack a decision block, `--max-commandless-runs` to fail when
  agents do not cite commands,
  `--max-warning-runs` to fail when scorer or agent warnings are present beyond
  the configured budget, `--max-hint-diagnostic-runs` to fail when score
  artifacts include any Yggdrasil hint diagnostics,
  `--max-blocking-hint-diagnostic-runs` to fail only when hint diagnostics are
  marked blocking,
  `--max-identity-mismatch-runs` to fail when score
  artifacts report a wrong schema, case id, or case fingerprint,
  `--max-unverified-score-runs` to fail when
  matching score artifacts are legacy or stale relative to the current suite file,
  `--max-graph-expectation-failures` to fail when graph/evidence expectations
  do not match the indexed facts,
  `--max-benchmark-preflight-blockers` to fail when benchmark preflight
  checks block Yggdrasil-mode claims,
  `--min-cases` to require a minimum number of completed cases and runs,
  `--require-broad-claim-readiness` to fail when the generated report's
  `claimReadiness` field is missing or not supported,
  `--require-docs-claim-readiness` to fail when the generated report's
  `docsClaimReadiness` field is missing or not supported,
  `--max-missing-declared-source-kind-runs` to fail when selected cases declare
  source kinds that produce no scoreable coverage, `--min-repos` to require
  repo breadth in completed artifacts, `--min-source-kind-cases KIND=N` to
  require enough completed cases with scoreable coverage for an explicit suite
  source kind, `--max-missed-runs`,
  `--max-context-rank-missing-runs`,
  `--max-missed-but-present-in-context-runs`, and
  `--max-missed-and-absent-from-context-runs` to separate agent selection misses
  from Yggdrasil retrieval or extraction misses, plus
  `--max-active-stage-ms` for partial or interrupted runs with a stuck active
  stage. Use
  `--agent <agent-id>` to avoid mixing baseline, shell-only, and ad hoc agent
  score artifacts in one gate. Selected cases must all have matching score
  artifacts unless `--allow-missing` is set. Matching score artifacts must be
  current for the suite case fingerprint unless `--allow-unverified-scores` is
  set, and unique per case, agent, and mode unless `--allow-duplicate-runs` is
  set. The check artifact includes `caseDiagnostics` so CI failures can be
  triaged without opening every score artifact. For scored cases, each case
  diagnostic includes the localization payload, agent-output diagnostics,
  artifact freshness details, progress when available, and expands case-scoped
  aggregate failures, such as missed-in-context counters, onto the affected case.
  Token budget gates require agent result `tokenUsage`; if token usage is absent
  and a token gate is configured, `agent-check` fails instead of treating the
  missing measurement as zero.
- `bb stage-time-gate REPORT... [--out stage-time-gate.json]` reads agent
  reports and emits a replayable timing profile even when no thresholds are
  configured. Use `stageTotals` for exact benchmark stages and
  `stageClassTotals` / `slowestCaseStageClasses` to see whether a full lane is
  spending time in graph setup, case setup, agent preparation, embeddings,
  agent execution, or scoring. Add `--baseline-report` to include stage and
  stage-class deltas against an earlier artifact. `bb bench:gate` invokes this
  gate automatically after `agent-check`, so every deterministic claim gate has a
  timing profile even when it is not enforcing timing thresholds.
  When `bb bench:gate` receives `--stage-time-baseline-report`, it treats the
  comparison as a repeat-run regression check: both reports must prove strict
  warm preparation, case-stage regressions default to `<=30000ms`, aggregate
  stage regressions default to `<=120000ms`, current/baseline ratios default to
  `<=1.50`, and deltas `<=5000ms` are ignored as noise unless overridden.
  `bench agent-report`, `bench agent-check`, and deterministic baseline summaries
  also print `stage-class-timing` lines, so slow full-lane runs can be triaged
  without opening raw JSON first.
- `bench claim-pack <suite.edn> --shell-report <path> --ygg-report <path>`
  writes a replayable proof bundle under the benchmark output root:
  `efficiency-summary.json`, `efficiency-summary.md`,
  `system-improvement-report.json`, `claim-pack.json`, and `CLAIM-PACK.md`.
  Use it after both lane reports exist when you need one artifact set for
  decision quality, token/cost tradeoffs, claim readiness, and system
  improvement lanes. `claim-pack.json` preserves
  `summary.claimReadinessDetails` and `summary.measuredSliceClaim`, including
  failed requirement keys and warnings, so a passing bundle cannot hide missing
  broad-claim evidence. It consumes reports; it does not run agents or hide the
  cost of generating those reports.
- `bench agent-check <suite.edn>` writes `agent-check.json` with
  `thresholdGate`, a compact summary of the mechanical gate status, failed
  metrics, covered repos/source-kind cases, measured class tags, and the
  embedded broad/docs claim readiness statuses. Use this field when a narrow
  lane such as docs handling passes its thresholds but should still report that
  broad real-world claim readiness is incomplete while docs-handling readiness
  is supported or failed explicitly.
- `bench agent-compare <suite.edn>` compares two `agent-report.json` files and
  exits non-zero when aggregate or per-case recall/MRR/noise regress beyond
  `--regression-tolerance` (default `0`). Use this after a candidate change to
  prove it did not trade one benchmark case for another:
  `bb bench agent-compare benchmark.edn --baseline-report before/agent-report.json
  --candidate-report after/agent-report.json`. It also treats higher aggregate
  warning runs, hint diagnostic runs, unverified score artifacts, obsolete score
  schemas, obsolete agent-result schemas, stale score artifacts, missing
  declared source-kind runs, coverage-excluded ground-truth files, unsupported
  ground-truth files, total `improvementSummary` target runs, and
  per-`kind` improvement target runs as lower-is-better regressions when the
  compared report case set and parser-worker profiles are unchanged.
- `bench run <suite.edn>` creates a detached worktree at each base SHA, indexes
  it with the query profile, runs lexical retrieval over the issue text, and
  writes one scored result artifact per case.
- `bench report <suite.edn>` aggregates case result scores.
- `bench show <suite.edn> --case <case-id>` prints one case result.

Use `--case <case-id>` with benchmark commands to narrow the command to one
case. Use `--cases <case-id>,<case-id>` with suite-level benchmark commands
such as `agent-baseline`, `agent-run`, `agent-report`, and `agent-check` to
rerun or gate a focused subset without paying for unrelated slow cases.

Suite EDN files can compose other tracked suites with `:include-suites`.
Includes are relative to the suite file, inherited cases are appended before
local cases, and identical repo declarations are deduplicated. Use
`benchmarks/agent-efficiency-broad.edn` for the common shell-only versus
Yggdrasil task-token comparison suite.

An exploratory wide replay suite can live under `.dev/ygg/benchmarks/` while it
is still scratch data. Promote it into `benchmarks/` only after the case
metadata, tags, and ground truth are curated enough to review as source.

The wide replay suite currently covers Clojure/ClojureScript, Go, Java,
.NET/C#, Python, Rust, JavaScript, TypeScript, Terraform, shell, SQL, style
files, and docs across multiple public repositories. A repeatable deterministic
baseline gate for that suite is:

```sh
bb bench agent-check .dev/ygg/benchmarks/wide.edn --out .dev/ygg/bench-wide-v4 --mode ygg --agent ygg-baseline-auto --min-cases 14 --min-runs 14 --min-file-recall-at-5 0.55 --min-file-recall-at-10 0.68 --min-file-recall-at-20 0.68 --min-mrr 0.55 --max-noise-at-20 0.82 --max-input-hinted-cases 0 --max-unsupported-ground-truth-files 14
```

The lower wide-suite thresholds are deliberate. New source-kind cases should
land even when the current auto/hybrid baseline misses them, because those
misses make source-support regressions and ranking gaps visible. The wide gate also
allows more noise than the narrow gate because the default deterministic agent
baseline now exposes ten candidate files, which is more useful for coding-agent
triage than a brittle top-three shortlist. Keep the stricter narrow-suite gate
around when you want to track already-strong localization behavior without
penalizing newly added source kinds.

## Quality Ratchet

Treat issue replay as the ratchet for source support and ranking changes. A
change should name the benchmark case that motivated it, keep before and after
outputs under `.dev/ygg/`, and record the exact `agent-check` or
`agent-compare` command that proves the candidate is no worse. Prefer a narrow
case gate while developing the fix, then rerun the wide gate before relying on
the result.

For extractor work, the first success criterion is that the relevant fix files
move into the agent's useful shortlist without hiding other misses. Check the
per-case `localization.ranks`, `rankedOutsideTop5`, `missedRuns`, and
`noiseRatioAt20` fields before tuning. If a change improves one case but
regresses another, keep the better facts only when the regression is understood
and tracked as a separate benchmark finding.

Example narrow gate for the Bootstrap style case:

```sh
bb bench agent-check .dev/ygg/benchmarks/wide.edn --out .dev/ygg/bench-bootstrap-style-rules-v1 --case bootstrap-34852-form-select-border-radius --mode ygg --agent ygg-baseline-auto --min-case-file-recall-at-5 1.0 --min-file-recall-at-5 1.0 --max-missed-runs 0
```

When a benchmark miss points at extractor noise or missing syntax facts, prefer
testing a parser-backed extractor behind the parser-worker contract before
adding more regex matching. See `docs/parser-workers.md`. Parser workers must
emit concrete syntax facts only; benchmark the effect on recall, MRR, noise@20,
edge count, and index time before making a worker-backed extractor the default.
Index summaries include `:stats :timings-ms` / `stats.timings-ms` with phase
timings such as `scan-ms`, `parser-worker-ms`, `extract-ms`,
`commit-files-ms`, `dependency-ms`, and `total-ms`; use those fields to separate
parser cost from XTDB writes, search-doc construction, and derived edge refresh.

Use case tags to slice reports by extractor area, ecosystem, problem class, or
failure mode without duplicating suites:

```clojure
{:id "rails-auth-env"
 :repo-id "rails"
 :tags [:ruby :runtime-config :auth :problem-cross-file-change]
 :base-sha "BASE"
 :fix-sha "FIX"
 :issue {:title "Auth callback fails"}}
```

Reports include `tags` and `byTag`, so one wide suite can still answer whether
auth evidence, runtime config, package resolution, a specific language family,
or a manually labeled problem class regressed. Problem-class tags are benchmark
labels for analysis; they are not inferred by Yggdrasil core and should not become
path, host, or vocabulary heuristics.

Use graph expectations when a benchmark is meant to prove Yggdrasil extracted
specific bounded facts, not only that the agent ranked the changed file. The
matching is exact over mechanical row fields, so these checks remain auditable
and avoid semantic path or host heuristics:

```clojure
{:id "rails-auth-env"
 :repo-id "rails"
 :tags [:ruby :runtime-config :auth]
 :expectations {:evidence [{:kind :auth-reference
                            :path "config/initializers/omniauth.rb"}]
                :edges [{:relation :calls-external-api}]
                :forbidden-edges [:shares-config]}}
```

`run`, `agent-baseline`, and `agent-run --mode ygg` write
`graphExpectations` into their score/result artifacts after indexing and
inference. `agent-report` aggregates those checks under
`graphExpectationDiagnostics`, and `agent-check` can gate them with
`--max-graph-expectation-failures 0` when the suite should fail on missing
evidence or forbidden graph edges.

The `:expectations :evidence` vector is also used for expected evidence citation
scoring. Graph validation treats it as `system-evidence` expectations by
default. When a case needs citation targets that are stored as nodes or source
chunks instead, set `:graph-evidence []` or provide a separate
`:graph-evidence [...]` vector so citation scoring and graph validation stay
explicit.

Architecture-class agent-efficiency cases may be synthetic when the benchmark corpus
has useful structure but no historical issue that asks the architectural
question directly. Keep them replayable: use a real benchmark base checkout, write
fair issue text that asks about the architecture task, tag the case with
`:synthetic` plus `:problem-architecture`, and provide curated
`:ground-truth`/`:expectations` for the files or graph facts a competent agent
should inspect. The tags are analysis labels only; Yggdrasil core must still emit
bounded facts and let the benchmark decide whether those facts helped.

Synthetic architecture cases still need a checkout boundary. When there is no
historical fix commit, set `:fix-sha` to the same commit as `:base-sha` and use
`:ground-truth {:localization-files [...]}` for the scored files. Synthetic-only
suites do not satisfy broad claim readiness; pair them with non-synthetic replay
lanes before making broad real-world claims:

```clojure
{:id "bootstrap-synthetic-docs-route-impact"
 :repo-id "bootstrap"
 :coverage {:source-kinds [:javascript :typescript :web-framework]}
 :tags [:synthetic
        :problem-architecture
        :architecture-cross-system-impact
        :web-framework
        :astro]
 :base-sha "320f7139052be98339726cac20895f50172000f9"
 :fix-sha "320f7139052be98339726cac20895f50172000f9"
 :ground-truth {:localization-files ["site/src/pages/index.astro"
                                      "site/src/pages/docs/[version]/examples/index.astro"]}
 :expectations {:nodes [{:kind :web-framework-route
                         :path "site/src/pages/index.astro"}
                        {:kind :web-framework-route
                         :path "site/src/pages/docs/[version]/examples/index.astro"}]
                :chunks [{:kind :astro-file
                          :path "site/src/pages/index.astro"}
                         {:kind :astro-file
                          :path "site/src/pages/docs/[version]/examples/index.astro"}]}
 :issue {:title "Identify docs route impact before removing theme components"
         :body "The docs team wants to remove the theme UI from Bootstrap's Astro docs. Identify the route files and component imports that a change would affect before editing code."}}
```

Cases default to `:result-scope :edit-files`: `localization-files` should be
files most likely to need edits. Use `:result-scope :inspection-files` for
architecture, planning, or audit prompts where the scoreable set is the bounded
files a competent agent should inspect before editing, even if some may become
evidence rather than direct patch targets. Keep the issue text consistent with
the scope; do not ask for inspection targets and then tell the agent to return
only likely edit files.

Multi-repo quality cases use a case-level `:repos` vector instead of a single
`:repo-id`. Each entry supplies the checkout id, SHAs, and that repo's local
ground truth. Scoring treats the pair of `:repo-id` and `:path` as the file
identity, so the same relative path in two repos remains distinct:

```clojure
{:id "otel-core-contrib-routing-connector-contract"
 :result-scope :inspection-files
 :repos [{:repo-id "opentelemetry-collector"
          :base-sha "415d3dcae73b37a8e3cf490452949a72589ae650"
          :fix-sha "415d3dcae73b37a8e3cf490452949a72589ae650"
          :index-files ["connector/connector.go"
                        "consumer/consumer.go"
                        "component/component.go"]
          :ground-truth {:localization-files ["connector/connector.go"]}}
         {:repo-id "opentelemetry-collector-contrib"
          :base-sha "2cbb0058d8b68628a04343e03f800863b86713bd"
          :fix-sha "2cbb0058d8b68628a04343e03f800863b86713bd"
          :index-files ["connector/routingconnector/factory.go"
                        "connector/routingconnector/config.go"]
          :ground-truth {:localization-files ["connector/routingconnector/factory.go"]}}]
 :tags [:synthetic :problem-architecture :multi-repo-quality]
 :issue {:title "Trace connector contract changes from Collector core into contrib routing connector"}}
```

Agent result rows for multi-repo cases should include `repoId` alongside the
repo-relative `path`. Yggdrasil packets expose all checkout roots in `repos` and
`YGG_BENCH_WORKTREES`; single-repo cases keep accepting path-only rows.

Use `:index-files` only when a case needs a bounded graph setup. The listed
paths are exact repo-relative files copied into generated `graph-index/` mirror
roots under the case output directory. Yggdrasil sync indexes those mirror roots,
while shell-only agents and result validation still use the full detached
worktrees. Keep the list curated and auditable: include ground-truth files and
mechanical support files needed for imports, manifests, routes, or graph
expectations. Do not populate it with path-name heuristics or inferred project
semantics.

Other useful seeds for the current benchmark corpus: Astro plugin config in
Bootstrap, event-trigger ownership flow in Supabase Postgres, native proxy
handling in Axios, and Dapper's PostgreSQL JSONB test stack. Give each one
manual architecture problem tags and curated expectations; do not teach Yggdrasil
core to infer those classes from names or paths.

The tracked starter file `benchmarks/architecture-synthetic.edn` contains
the runnable Bootstrap, Supabase Postgres, Axios, and Dapper cases. It expects
local benchmark checkouts in `.dev/ygg/benchmark-repos/`; keep generated
benchmark outputs under `.dev/ygg/...` with `--out`.

The tracked `benchmarks/multi-repo-quality.edn` suite contains the first
cross-checkout case. It also uses local benchmark checkouts under
`.dev/ygg/benchmark-repos/`.

Use `--enqueue` with `bench agent-packet` only for dev benchmark packet
generation. This writes to the central SQLite-backed project queue identified by
the packet project id; active `sync work` commands resolve the same central
project queue instead of accepting a local queue path:

```sh
bb bench agent-packet benchmark.edn --case penpot-example --enqueue --json
```

The queue item stores transport and lease state only. The embedded payload is
the explicit `ygg.benchmark.agent-packet/v1` JSON artifact.

## Agent Result Contract

Agents should return JSON shaped like this:

```json
{
  "schema": "ygg.benchmark.agent-result/v2",
  "caseId": "penpot-example",
  "caseFingerprint": "sha256:...",
  "agentInputFingerprint": "sha256:...",
  "agentId": "codex",
  "mode": "ygg",
  "selection": {
    "rawCandidateFiles": 20,
    "candidateFiles": 20,
    "coverageFilteredCandidateFiles": 0,
    "limit": 20,
    "coverageSourceKinds": []
  },
  "suspectedFiles": [
    {
      "path": "repo-relative/path.ext",
      "rank": 1,
      "confidence": 0.84,
      "reason": "Short evidence-based reason.",
      "evidence": ["command, snippet, or Yggdrasil context row used"],
      "metrics": {"rankScore": 1.2}
    }
  ],
  "suspectedSymbols": [],
  "commands": [],
  "warnings": [],
  "summary": "Brief rationale."
}
```

`caseFingerprint` identifies the full score contract, including hidden ground
truth and graph expectations. `agentInputFingerprint` identifies the visible
agent input. New results should include both; the scorer accepts legacy results
that only include `caseFingerprint`, but hidden expectation-only edits can make
those legacy results report identity warnings until the agent run is refreshed.

`mode` is one of `ygg`, `shell-only`, `local-vector`, `codebase-memory`, or
`graphify`. `agent-run` only uses `ygg` and `shell-only`; `local-vector`,
`codebase-memory`, and `graphify` are reserved for optional deterministic
baseline lanes.

Recall, MRR, and noise use `suspectedFiles.path` and rank. The citation score
uses the presence of non-empty `suspectedFiles[].evidence` rows. Reasons,
commands, warnings, symbols, optional bounded `selection`, and optional bounded
`suspectedFiles[].metrics` are still part of the artifact because they make
failures auditable. For Yggdrasil-generated artifacts, `commands` may include
repair or inspection commands copied from structured `nextActions`; treat them
as evidence of the graph checks available to the agent, not proof that the agent
ran every command.
Yggdrasil-generated baseline evidence uses compact mechanical rows such as
`context-doc:<path>`, `graph-entity:<label>`, and
`candidate-file:<path> rank=<n> ... components=<score-components>` so candidate
files remain traceable even when snippets are trimmed from the context packet.
Scoring warns when an agent result has the wrong schema, case id, or available
fingerprint identity; use `--max-warning-runs 0` when stale or misrouted
artifacts must fail the benchmark run.
It also warns when ranked file or symbol rows contain non-positive ranks or
confidence values outside the `0..1` range, or when ranks are duplicated within
the same ranked section. Repeated `suspectedFiles.path` rows are warned and the
scorer keeps the best-ranked row for metrics.
Scoring also warns on fields outside the closed agent-result contract, including
nested selection, parser-worker, file metric, file row, and symbol row fields.

## Fair Inputs

Allowed benchmark input:

- Issue title.
- Issue body.
- Comments available before the fix.
- Labels only when the suite explicitly chooses to include them.

Forbidden benchmark input:

- PR title or body.
- Fix diff.
- Post-fix comments.
- File names or symbols from the fixing PR.

## Ground Truth

The required ground truth is the set of files changed by the fixing diff. Suites
may also include explicit `:ground-truth` fields when a case needs hand-curated
data:

```clojure
{:ground-truth {:changed-files ["src/app/core.clj"]
                :changed-symbols ["app.core/start"]}}
```

If explicit changed files are omitted, Yggdrasil computes them from git. Unsupported
or missing ground-truth files are reported so misses can be separated from
retrieval quality.

Prepared and scored artifacts also include `inputHints`. This records exact
ground-truth file paths already present in the issue text. Agent packets omit
this field, but reports count `inputHintedRuns` and `inputHintedCases` so easy
cases that name the fix file can be separated from cases where localization
depends on source inspection or graph context.

## Source Coverage

Cases may include `:coverage {:source-kinds [...]}` to declare the file kinds the
case is intended to exercise. These are benchmark labels, not Yggdrasil semantics.
Prepared and scored artifacts mechanically derive the scoreable changed files by
kind from the base checkout, and reports aggregate them as `coverage`:

```json
{
  "coverage": {
    "declaredSourceKinds": ["python", "rust"],
    "scoreableSourceKinds": ["python"],
    "scoreableFilesByKind": [
      {"kind": "python", "cases": 1, "scoreableFiles": 2}
    ],
    "missingDeclaredSourceKinds": [
      {"case-id": "rust-example", "kind": "rust"}
    ]
  }
}
```

Use this to keep the wide replay suite honest as source support expands. A case
that declares `:rust` but only changes docs will show up as missing declared
coverage. Declared source-kind coverage also scopes scoring: changed or
localization files outside the declared kinds remain in the prepared ground
truth as `coverageExcludedFiles`, are counted as
`coverageExcludedGroundTruthFiles`, and are left out of recall/MRR/noise
denominators.

Cases may also include `:ground-truth {:localization-files [...]}`. Use this
when the fixing commit changed collateral files such as release notes,
changelogs, or generated fixtures that a first-pass coding agent should not be
expected to identify from the original issue. `:changed-files` remains the full
audit trail for the commit; `:localization-files` is the scored target set.

Do not add a source kind to `:coverage` only because the case expects audit
evidence from that family. Dependency manifests, service configuration, auth
configuration, or container files belong in `:coverage` only when they are
scored localization targets. Otherwise, keep them in `:expectations`; this lets
reports verify the evidence family without turning a non-target file kind into a
missing coverage diagnostic.

## Scores

The core scores are mechanical. Recall and MRR use scoreable localization files:
the explicit `:localization-files` set when present, otherwise changed files,
after removing files that did not exist in the base tree or were unsupported by
Yggdrasil. New files and unsupported file types are reported separately because an
agent cannot reliably localize a file that did not exist in the checkout it was
given. Multi-repo cases compare files by `repoId`/`:repo-id` plus path; path-only
ground truth remains a single-repo shorthand.

- `fileRecallAt5`, `fileRecallAt10`, `fileRecallAt20`: fraction of scoreable
  localization files appearing in the top ranked file results.
- `meanReciprocalRankFile`: reciprocal rank of the first scoreable localization
  file found.
- `noiseRatioAt20`: fraction of top ranked files outside the fixing diff.
- `evidenceCitationRate`: fraction of ranked files whose result row includes at
  least one non-empty evidence string. This is a mechanical auditability metric;
  it does not judge whether the evidence is semantically correct.
- `pathEvidenceCitationRate`: fraction of ranked files whose evidence strings
  include the ranked file path. This stricter mechanical traceability metric is
  useful for agent-first gates where each suspected file should point back to a
  concrete context row, candidate row, command, or snippet mentioning that path.
  Report diagnostics include `pathUncitedRankedFiles` per case and aggregate
  `pathUncitedRuns`, `pathUncitedCaseIds`, and `pathUncitedRankedFiles` so a
  failed gate points at the ranked files whose evidence did not cite their path.
- `expectedEvidenceCitationRate`: fraction of declared expectation evidence rows
  cited by the agent result evidence strings. Path-bearing expectation rows
  require a matching path citation; rows without a path fall back to the declared
  label. The metric is emitted only for cases that declare expectation evidence.
  Gate it with `--min-expected-evidence-citation-rate` and
  `--min-case-expected-evidence-citation-rate`.
- `expectationDiagnostics`: report-level and grouped counters showing how many
  runs declared expected evidence and how many had scored
  `expectedEvidenceCitationRate` metrics. Claim readiness requires at least one
  expected-evidence citation metric, no declared expected-evidence rows with
  missing scored metrics, aggregate expected-evidence citation rate at or above
  `0.80`, and every case expected-evidence citation rate at or above `0.50`.
  If stale or hand-written score artifacts declare expected evidence without the
  scored metric, `improvementSummary` includes an
  `expected-evidence-citation-metric-gaps` benchmark-hygiene row.
- `changedFiles`: files changed by the fixing diff.
- `localizationFiles`: localization target files, or changed files when no
  explicit localization set was provided.
- `scoreableChangedFiles`: localization target files that were present and
  supported in the base checkout.
- `unsupportedGroundTruthFiles`: changed files that were missing or unsupported
  in the base tree.
- `agentDiagnostics.commandlessRuns`: scored agent artifacts whose result did
  not cite any commands. Gate this with `--max-commandless-runs` when
  auditability should require a visible command trail.
- `agentDiagnostics.commandTelemetry`: aggregate counts derived from cited
  `commands`, including `commandCount`, `yggCommandCount`,
  `searchCommandCount`, `fileReadCommandCount`, and `shellCommandCount`.
  When a cited command contains multiple shell segments separated by pipes,
  `&&`, `||`, or `;`, reports also include `segmentCount`,
  `yggSegmentCount`, `searchSegmentCount`, `fileReadSegmentCount`, and
  `shellSegmentCount` so compound commands cannot hide extra search/read work.
  Search counts cover command forms such as `rg`, `grep`, `git grep`, `fd`,
  and `find`; file-read counts cover common local read commands such as `cat`,
  `sed`, `head`, `tail`, `nl`, `awk`, and pagers. These are mechanical cited
  command counts, not provider-side tool logs.
  Search telemetry is scope-aware:
  `searchCommandCount` is the raw observed search total;
  `broadSearchCommandCount` and `broadSearchSegmentCount` count rediscovery
  searches such as `rg term .`, `find .`, or unbounded path scopes;
  `scopedSearchCommandCount` and `scopedSearchSegmentCount` count bounded
  pathspec searches; and `exactFileSearchCommandCount` /
  `exactFileSearchSegmentCount` count proof searches over exact candidate files.
  `agent-compare` treats broad search, file-read, and generic shell command
  increases as lower-is-better regressions when reports are comparable. Raw
  `searchCommandCount`, scoped proof counts, exact-file proof counts, and
  Yggdrasil command counts are reported for interpretation rather than as broad
  search regressions by themselves. If Yggdrasil command counts are the only
  available shared metric, the comparison status is `observed-only`, not an
  improvement or regression claim.
  Yggdrasil-mode reports may also include
  `internalRipgrepSearchCount`, `internalRipgrepElapsedMs`, and
  `internalRipgrepMatchCount` from internal query lanes. These describe
  Yggdrasil retrieval work and are not shell-command counts.
  `yggArtifactProjectionCommandCount` and
  `yggArtifactProjectionSegmentCount` count `jq`, `cat`, `sed`, `rg`, or similar
  commands that inspect Ygg hint/context artifacts such as
  `*.ygg-hints.json`, `*.ygg-context.json`, `YGG_BENCH_YGG_HINTS`, or
  `YGG_BENCH_YGG_CONTEXT`. Treat those as projection cost, not repository
  rediscovery.
- `agentDiagnostics.missingPredictedFileRuns`: scored agent artifacts whose
  ranked result included repo-relative paths that do not exist in the base
  checkout. Gate this with `--max-missing-predicted-file-runs` to catch
  path-shape drift, hallucinated paths, and stale result artifacts.
- `agentDiagnostics.warningRuns`: scored agent artifacts that carried scorer or
  agent warnings. Gate this with `--max-warning-runs` when benchmark result
  shape quality should be part of the ratchet. `agent-compare` also treats
  higher aggregate `warningRuns`, `commandlessRuns`,
  `missingPredictedFileRuns`, command telemetry regressions, and
  `hintDiagnosticRuns` as lower-is-better regressions when the report case set
  and parser-worker profiles are comparable.
- `agentDiagnostics.hintDiagnosticRuns`: scored Yggdrasil-mode artifacts whose
  generated hint file reported help-quality diagnostics. Gate this with
  `--max-hint-diagnostic-runs` when the hints should be free of coverage,
  source-support, or zero-candidate warnings.
- `agentDiagnostics.blockingHintDiagnosticRuns`: scored Yggdrasil-mode artifacts
  whose generated hint file reported blocking help-quality diagnostics. Gate this
  with `--max-blocking-hint-diagnostic-runs 0` for claim lanes that should fail
  on blocking coverage/source-support issues while still reporting non-blocking
  diagnostics.
- `agentDiagnostics.identityMismatchRuns`: scored agent artifacts whose schema,
  case id, or case fingerprint did not match the prepared case. These are also
  counted under `warningRuns`; gate them directly with
  `--max-identity-mismatch-runs`.
- `localizationDiagnostics.contextRankMissingRuns`: Yggdrasil-mode score artifacts
  that do not include context ground-truth ranks. Gate this with
  `--max-context-rank-missing-runs` when reports must prove whether missed files
  were absent from Yggdrasil context or merely ignored by the agent.
- `localizationDiagnostics.missedButPresentInContextRuns`: missed scoreable files
  that were present in the Yggdrasil context packet. Gate with
  `--max-missed-but-present-in-context-runs` when prompt or agent selection
  quality should not regress.
- `localizationDiagnostics.missedAndAbsentFromContextRuns`: missed scoreable
  files that were absent from the Yggdrasil context packet. Gate with
  `--max-missed-and-absent-from-context-runs` when graph extraction, indexing, or
  retrieval coverage should not regress.
- `coverageDiagnostics`: aggregate source-support counters. `agent-compare`
  treats increases in missing declared source-kind runs, coverage-excluded
  ground-truth files, and unsupported ground-truth files as lower-is-better
  regressions when reports are comparable.
- `improvementSummary`: ordered remediation targets derived from the report
  diagnostics. Generic `hint-diagnostics` targets count only warning/error hint
  rows; info-only rows such as ordinary coverage filtering stay in
  `agentDiagnostics`. `agent-compare` sums `improvementSummary` `runs` as
  `improvementTargetRuns`, exposes `improvementTargetRunsByKind`, and treats
  total or per-kind increases as lower-is-better regressions when reports are
  comparable.
- `benchmarkPreflightDiagnostics`: Yggdrasil-mode benchmark claim gate. It
  aggregates per-run index, inference, graph expectation, hint
  diagnostic, and sync/check-equivalent checks from the score artifact or
  sibling agent artifacts. Failed or missing checks mark the Yggdrasil lane
  benchmark-ready `false`; the paired `improvementSummary` rows
  `benchmark-preflight` and `sync-check-gaps` point at the affected cases.
  Use `--max-benchmark-preflight-blockers 0` when `agent-check` should reject
  reports that are not claim-ready.
- `artifactDiagnostics`: aggregate score-artifact freshness counters.
  `agent-compare` treats increases in unverified score runs, obsolete score
  schema runs, obsolete agent-result schema runs, and stale score runs as
  lower-is-better regressions when reports are comparable.

Each result also records `groundTruthRanks.files`, which lists every scoreable
localization file and the rank where Yggdrasil found it, or `found? false` when it
was outside the collected result window. Use that before tuning ranking;
aggregate recall alone does not show whether a miss is close or completely
absent. Agent reports also copy this into `localization.ranks` and summarize
`rankedOutsideTop5`, `rankedOutsideTop10`, and `rankedOutsideTop20` for quick
threshold debugging.

Agent-style Yggdrasil runs may also record `contextGroundTruthRanks`. This compares
the scoreable localization files against the full Yggdrasil context ranking before
the agent answer limit is applied. Use it to distinguish missing evidence from
ranking/selection misses: if a target appears there but not in
`groundTruthRanks`, the context had the file and the top-N answer needs work.
Agent reports summarize this as `missedButPresentInContextRuns` and
`missedAndAbsentFromContextRuns`.

These scores do not claim the graph understands the project. They measure
whether deterministic facts and ranking put the real fix area close enough for a
human or agent to use.

## Boundaries

Benchmark code may fetch issue data, compute diffs, check out historical trees,
run Yggdrasil, and compare ranked output with the real fix. Yggdrasil core must still
avoid semantic shortcuts based on path names, issue vocabulary, host names, or
project-specific labels.

Discovery from GitHub issues and PRs is intentionally separate from replay. The
replay runner only needs a suite EDN with case ids, repo ids, base SHAs, fix
SHAs, and issue text; discovery can generate that suite later.
