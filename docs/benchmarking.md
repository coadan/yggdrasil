# Benchmarking AGraph

AGraph benchmarks replay real project work from a historical source tree. The
primary benchmark is agent issue replay: start from the commit before a fix,
give an agent the issue text plus a base checkout, collect ranked suspected
files, and compare that output with the actual fixing diff.

The first target is localization quality, not autonomous patching. Reliable
localization scores are cheap to run, easy to debug, and strong enough to catch
regressions in source support, extraction, ranking, and system inference. The
retrieval runner is still useful, but treat it as a diagnostic for why an agent
did or did not get useful help.

Tracked starter suites:

- `benchmarks/oss-architecture-synthetic.edn`: diagnostic architecture-class
  suite with synthetic tasks on real OSS checkouts.
- `benchmarks/headline.edn`: small architecture-first headline suite for
  shell-only versus AGraph agent-efficiency runs. Generated lane outputs should
  still live under `.dev/agraph/`.

## Headline Suite

Use the tracked headline suite to compare shell-only and AGraph-assisted agents
on architecture-oriented tasks. Generated lane outputs stay under
`.dev/agraph/headline-bench/` by default.

Repeatable helper workflow:

```sh
bb headline baseline
bb headline agents
bb headline reports
bb headline compare
```

Use `bb headline all` for a full local run. Pass `--suite`, `--out`, `--agent`,
`--command`, `--prompt-profile`, and `--timeout-ms` to make a run comparable
across machines or agent CLIs. Add `--dry-run` to print the exact commands
without launching agents.

The helper wraps the existing benchmark commands. Use the raw command form below
when debugging one lane or when a CI job needs each phase split explicitly.

Deterministic AGraph baseline:

```sh
bb bench agent-baseline benchmarks/headline.edn \
  --out .dev/agraph/headline-bench/agraph-baseline

bb bench agent-report benchmarks/headline.edn \
  --mode agraph \
  --agent agraph-baseline-lexical \
  --out .dev/agraph/headline-bench/agraph-baseline
```

External agent lanes:

```sh
bb bench agent-run benchmarks/headline.edn \
  --mode shell-only \
  --agent codex \
  --command 'codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"' \
  --prompt-profile fast \
  --out .dev/agraph/headline-bench/shell-only

bb bench agent-run benchmarks/headline.edn \
  --mode agraph \
  --agent codex \
  --command 'codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"' \
  --prompt-profile fast \
  --out .dev/agraph/headline-bench/agraph
```

Generate lane reports and compare them:

```sh
bb bench agent-report benchmarks/headline.edn \
  --mode shell-only \
  --agent codex \
  --out .dev/agraph/headline-bench/shell-only

bb bench agent-report benchmarks/headline.edn \
  --mode agraph \
  --agent codex \
  --out .dev/agraph/headline-bench/agraph

bb efficiency \
  .dev/agraph/headline-bench/shell-only/agent-report.json \
  .dev/agraph/headline-bench/agraph/agent-report.json \
  --out .dev/agraph/headline-bench/summary.json \
  --markdown-out .dev/agraph/headline-bench/REPORT.md
```

Read `Problem-class signals`, `Architecture-class signals`, and
`Claim readiness` together. A headline result is useful only when the compared
lanes share completed cases, architecture-class tags are measured, evidence
quality is available, and the report remains claim-ready. In `--json` output,
read `classSignals.problemClasses` and `classSignals.architectureClasses`; a
row with `measured: false` is useful context but does not count toward broad
claim readiness. Use `classSignals.summary.measuredProblemClasses` and
`classSignals.summary.measuredArchitectureClasses` for automated gates. Treat
`improvementTargetRuns` as lower-is-better: a run that improves recall but
introduces more remediation targets is a mixed result, not a broad efficiency
win. Generated files under `.dev/agraph/headline-bench/` are disposable
artifacts.
Use `summary.json` for machine gates and `REPORT.md` for a compact human review
of the same comparison.

## Workflow

Create a benchmark suite EDN file:

```clojure
{:id "oss-issue-replay"
 :project-id "oss-issue-replay"
 :repos [{:id "penpot"
          :root ".dev/oss-test-cases/repos/penpot"}]
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
bb bench agent-run benchmark.edn --agent codex --command 'codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"' --mode agraph --prompt-profile fast --timeout-ms 120000
bb bench agent-score benchmark.edn --case penpot-example --result agent-result.json
bb bench agent-report benchmark.edn
bb bench agent-check benchmark.edn --mode agraph --agent agraph-baseline-lexical --min-cases 4 --min-runs 4 --min-file-recall-at-10 1.0 --min-case-file-recall-at-10 1.0 --min-mrr 1.0 --min-case-mrr 1.0 --min-evidence-citation-rate 1.0 --min-path-evidence-citation-rate 1.0 --min-case-evidence-citation-rate 1.0 --min-case-path-evidence-citation-rate 1.0 --max-noise-at-20 0.5 --max-case-noise-at-20 0.75
bb bench agent-compare benchmark.edn --baseline-report .dev/agraph/bench-before/agent-report.json --candidate-report .dev/agraph/bench-after/agent-report.json
bb bench run benchmark.edn
bb bench report benchmark.edn
bb bench show benchmark.edn --case penpot-example
```

Generated worktrees, XTDB stores, and result JSON files live under
`.dev/agraph/bench/<suite-id>/` by default. Use `--out` to choose another
generated output root.

## Commands

- `bench prepare <suite.edn>` computes ground truth from `git diff
  <base-sha> <fix-sha>` and writes one prepared case artifact per case.
- `bench agent-packet <suite.edn>` writes an agent-localization packet for each
  selected case. Use `--case <case-id>` for one case, `--mode agraph` to give
  the agent AGraph command hints, or `--mode shell-only` for a baseline.
- `bench agent-baseline <suite.edn>` generates a deterministic AGraph-assisted
  agent result from the same context docs/entities an agent receives, writes the
  agent-result JSON, and scores it. Use this as the repeatable regression
  baseline before running slower human or LLM agent trials. By default it keeps
  a ranked suspected-file list of twenty files and still writes the full
  context packet; use `--limit <n>` to change the suspected-file shortlist size,
  `--doc-limit <n>` to change the snippet-bearing source context size, and
  `--retrieval-limit <n>` to change the compact candidate-file pool without
  adding more snippets. The default retrieval limit is intentionally wider than
  the snippet limit so lower-ranked but relevant companion files can still be
  selected. Limited AGraph baselines reserve a small slice of the shortlist for
  candidate-file-only evidence so compact file/path matches are not completely
  crowded out by snippet-bearing retrieved docs.
  Use `--retriever local-vector` to run an optional local semantic-vector
  control lane instead of the graph/context packet. The default worker is
  `python3 scripts/local-vector-baseline.py`, which uses
  `sentence-transformers` locally and writes the current agent-result contract,
  including the case fingerprint and citation evidence, for the existing
  scorer. Install the optional worker dependencies in a local
  environment with `python3 -m venv .dev/agraph/local-vector-venv &&
  .dev/agraph/local-vector-venv/bin/python -m pip install -r
  scripts/local-vector-requirements.txt`, then pass
  `--vector-command '.dev/agraph/local-vector-venv/bin/python
  scripts/local-vector-baseline.py'`. Use `--vector-model <model>` to choose a
  local model and `--vector-command <cmd>` to replace the worker. The command
  receives `REQUEST_JSON RESULT_JSON MODEL` arguments. This lane is for
  benchmark diagnosis: if it beats the graph baseline, inspect whether AGraph is
  missing extractor facts, ranking useful facts poorly, or losing on vocabulary
  mismatch. It does not make AGraph core depend on a vector provider. Use
  `--skip-existing` to resume an interrupted suite run. A case is skipped only
  when exactly one current score artifact already matches the case fingerprint,
  agent id, mode, and result path; stale, duplicate, or missing artifacts are
  rerun.
- `bench agent-run <suite.edn> --agent <id> --command <cmd>` prepares the same
  packet contract, runs an external coding-agent command from the base
  worktree, and scores the JSON result written to `$AGRAPH_BENCH_RESULT`. The
  command receives `$AGRAPH_BENCH_PROMPT`, `$AGRAPH_BENCH_PACKET`,
  `$AGRAPH_BENCH_RESULT`, `$AGRAPH_BENCH_WORKTREE`, `$AGRAPH_BENCH_PROJECT`,
  `$AGRAPH_BENCH_XTDB_PATH`, `$AGRAPH_BENCH_CASE_ID`,
  `$AGRAPH_BENCH_AGENT_ID`, `$AGRAPH_BENCH_MODE`, and
  `$AGRAPH_BENCH_OUTPUT_SCHEMA`. In `--mode agraph`, the command also receives
  `$AGRAPH_BENCH_AGRAPH_HINTS`, a compact agent-facing AGraph summary, and
  `$AGRAPH_BENCH_AGRAPH_CONTEXT`, the fuller precomputed context JSON artifact;
  both are generated from the base checkout. The prompt file is the stable
  agent input; it points to the packet and required result JSON path without
  exposing hidden ground truth. Use the schema env var with agent CLIs that
  support structured output; the generated schema is intentionally closed so
  strict structured output providers can validate it. For structured-output
  runners that capture the final response into `$AGRAPH_BENCH_RESULT`, the
  agent should return the JSON as its final response instead of trying to write
  the file from inside its sandbox. Use `--prompt-profile fast` for short
  localization-only smoke runs that should avoid patching and full test suites;
  omit it for the standard prompt. In `--mode agraph`, the graph, hints, and
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
  saw. When those counters point at help-quality problems, hints include
  `diagnostics` rows for zero candidate files, coverage-filtered candidate
  files, missing declared source kinds, and indexed source extraction
  diagnostics. Audit-scope counters with skipped files, extractor diagnostics,
  or unclassified extractor rows also produce hint diagnostics so benchmark
  reports can distinguish audit-scope family gaps from ranking misses. Hints
  and deterministic AGraph baseline results flatten context
  drilldowns, legacy `answerability.next`, structured packet `nextActions`, and
  `architecture.validationGaps.nextActions` into `commands` so agents see
  bounded follow-up checks without inspecting nested context JSON.
  `sourceCoverage.diagnostics.samples` carries a bounded set of
  file/stage/message rows for indexed extraction diagnostics so agents can jump
  straight to concrete parser or extractor failures; tight context budgets
  compact this coverage payload before dropping it. Use `--timeout-ms <n>` to
  bound long-running agents. Use `--skip-existing` to resume interrupted agent
  runs with the same current-score matching rules as `agent-baseline`.
- `bench agent-score <suite.edn> --case <case-id> --result result.json` scores
  one agent result JSON against hidden ground truth.
- `bench agent-report <suite.edn>` aggregates existing agent score artifacts
  across selected cases. Use `--mode agraph` or `--mode shell-only` to compare
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
  empty rankable outputs, zero-candidate AGraph packets, coverage-filtered
  runs and candidate files, warning-bearing runs, and missing predicted paths so
  benchmark failures point to the next mechanical fix instead of only reporting
  a score. When AGraph-mode runs generated hint diagnostics, reports count
  those rows by kind so help-quality issues are visible in aggregate.
  Reports also include `improvementSummary`, a compact ordered list of
  mechanically derived remediation targets such as extraction/retrieval gaps,
  ranking or context-budget misses, citation gaps, coverage declaration issues,
  graph expectation failures, and artifact hygiene.
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
  `--min-case-evidence-citation-rate`,
  `--min-case-path-evidence-citation-rate`, `--max-noise-at-20`, `--max-case-noise-at-20`,
  `--max-input-hinted-cases`, `--max-unsupported-ground-truth-files`,
  `--max-empty-result-runs` to fail when agents produce no rankable suspected
  files, `--max-missing-predicted-file-runs` to fail when agents predict paths
  that do not exist in the base checkout, `--max-commandless-runs` to fail when
  agents do not cite commands,
  `--max-warning-runs` to fail when scorer or agent warnings are present beyond
  the configured budget, `--max-hint-diagnostic-runs` to fail when score
  artifacts include AGraph hint diagnostics,
  `--max-identity-mismatch-runs` to fail when score
  artifacts report a wrong schema, case id, or case fingerprint,
  `--max-unverified-score-runs` to fail when
  matching score artifacts are legacy or stale relative to the current suite file,
  `--max-graph-expectation-failures` to fail when graph/evidence expectations
  do not match the indexed facts,
  `--max-missing-declared-source-kind-runs` to fail when selected cases declare
  source kinds that produce no scoreable coverage, `--max-missed-runs`,
  `--max-missed-but-present-in-context-runs`, and
  `--max-missed-and-absent-from-context-runs` to separate agent selection misses
  from AGraph retrieval or extraction misses, plus
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

The wide OSS replay suite currently covers Clojure/ClojureScript, Go, Java,
.NET/C#, Python, Rust, JavaScript, TypeScript, Terraform, shell, SQL, style
files, and docs across multiple public repositories. A repeatable deterministic
baseline gate for that suite is:

```sh
bb bench agent-check .dev/benchmarks/oss-issue-replay.edn --out .dev/agraph/bench-oss-wide-v4 --mode agraph --agent agraph-baseline-lexical --min-cases 14 --min-runs 14 --min-file-recall-at-5 0.55 --min-file-recall-at-10 0.68 --min-file-recall-at-20 0.68 --min-mrr 0.55 --max-noise-at-20 0.82 --max-input-hinted-cases 0 --max-unsupported-ground-truth-files 14
```

The lower wide-suite thresholds are deliberate. New source-kind cases should
land even when the current lexical baseline misses them, because those misses
make source-support regressions and ranking gaps visible. The wide gate also
allows more noise than the narrow gate because the default deterministic agent
baseline now exposes ten candidate files, which is more useful for coding-agent
triage than a brittle top-three shortlist. Keep the stricter narrow-suite gate
around when you want to track already-strong localization behavior without
penalizing newly added source kinds.

## Quality Ratchet

Treat issue replay as the ratchet for source support and ranking changes. A
change should name the benchmark case that motivated it, keep before and after
outputs under `.dev/agraph/`, and record the exact `agent-check` or
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
bb bench agent-check .dev/benchmarks/oss-issue-replay.edn --out .dev/agraph/bench-bootstrap-style-rules-v1 --case bootstrap-34852-form-select-border-radius --mode agraph --agent agraph-baseline-lexical --min-case-file-recall-at-5 1.0 --min-file-recall-at-5 1.0 --max-missed-runs 0
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
labels for analysis; they are not inferred by AGraph core and should not become
path, host, or vocabulary heuristics.

Use graph expectations when a benchmark is meant to prove AGraph extracted
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

`run`, `agent-baseline`, and `agent-run --mode agraph` write
`graphExpectations` into their score/result artifacts after indexing and
inference. `agent-report` aggregates those checks under
`graphExpectationDiagnostics`, and `agent-check` can gate them with
`--max-graph-expectation-failures 0` when the suite should fail on missing
evidence or forbidden graph edges.

Architecture-class agent-efficiency cases may be synthetic when the OSS corpus
has useful structure but no historical issue that asks the architectural
question directly. Keep them replayable: use a real OSS base checkout, write
fair issue text that asks about the architecture task, tag the case with
`:synthetic` plus `:problem-architecture`, and provide curated
`:ground-truth`/`:expectations` for the files or graph facts a competent agent
should inspect. The tags are analysis labels only; AGraph core must still emit
bounded facts and let the benchmark decide whether those facts helped.

Synthetic architecture cases still need a checkout boundary. When there is no
historical fix commit, set `:fix-sha` to the same commit as `:base-sha` and use
`:ground-truth {:localization-files [...]}` for the scored files:

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

Other useful seeds for the current OSS corpus: Astro plugin config in
Bootstrap, event-trigger ownership flow in Supabase Postgres, native proxy
handling in Axios, and Dapper's PostgreSQL JSONB test stack. Give each one
manual architecture problem tags and curated expectations; do not teach AGraph
core to infer those classes from names or paths.

The tracked starter file `benchmarks/oss-architecture-synthetic.edn` contains
the runnable Bootstrap, Supabase Postgres, and Axios cases. It expects the OSS
corpus checkouts in `.dev/oss-test-cases/repos/`; keep generated benchmark
outputs under `.dev/agraph/...` with `--out`.

Use `--enqueue --queue-dir <dir>` with `bench agent-packet` to hand packets to
agents through the filesystem queue:

```sh
bb bench agent-packet benchmark.edn --case penpot-example --enqueue --queue-dir .dev/agraph/queue --json
bb sync work pull --kind benchmark-agent --queue-dir .dev/agraph/queue --agent codex
```

The queue item stores transport and lease state only. The embedded payload is
the explicit `agraph.benchmark.agent-packet/v1` JSON artifact.

## Agent Result Contract

Agents should return JSON shaped like this:

```json
{
  "schema": "agraph.benchmark.agent-result/v2",
  "caseId": "penpot-example",
  "caseFingerprint": "sha256:...",
  "agentId": "codex",
  "mode": "agraph",
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
      "evidence": ["command, snippet, or AGraph context row used"],
      "metrics": {"rankScore": 1.2}
    }
  ],
  "suspectedSymbols": [],
  "commands": [],
  "warnings": [],
  "summary": "Brief rationale."
}
```

`mode` is one of `agraph`, `shell-only`, or `local-vector`. `agent-run` only
uses `agraph` and `shell-only`; `local-vector` is reserved for the optional
local semantic-vector baseline lane.

Recall, MRR, and noise use `suspectedFiles.path` and rank. The citation score
uses the presence of non-empty `suspectedFiles[].evidence` rows. Reasons,
commands, warnings, symbols, optional bounded `selection`, and optional bounded
`suspectedFiles[].metrics` are still part of the artifact because they make
failures auditable. For AGraph-generated artifacts, `commands` may include
repair or inspection commands copied from structured `nextActions`; treat them
as evidence of the graph checks available to the agent, not proof that the agent
ran every command.
AGraph-generated baseline evidence uses compact mechanical rows such as
`context-doc:<path>`, `graph-entity:<label>`, and
`candidate-file:<path> rank=<n> ... components=<score-components>` so candidate
files remain traceable even when snippets are trimmed from the context packet.
Scoring warns when an agent result has the wrong schema, case id, or
case fingerprint; use `--max-warning-runs 0` when stale or misrouted artifacts
must fail the benchmark run.
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

If explicit changed files are omitted, AGraph computes them from git. Unsupported
or missing ground-truth files are reported so misses can be separated from
retrieval quality.

Prepared and scored artifacts also include `inputHints`. This records exact
ground-truth file paths already present in the issue text. Agent packets omit
this field, but reports count `inputHintedRuns` and `inputHintedCases` so easy
cases that name the fix file can be separated from cases where localization
depends on source inspection or graph context.

## Source Coverage

Cases may include `:coverage {:source-kinds [...]}` to declare the file kinds the
case is intended to exercise. These are benchmark labels, not AGraph semantics.
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

Use this to keep the OSS replay suite honest as source support expands. A case
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

## Scores

The core scores are mechanical. Recall and MRR use scoreable localization files:
the explicit `:localization-files` set when present, otherwise changed files,
after removing files that did not exist in the base tree or were unsupported by
AGraph. New files and unsupported file types are reported separately because an
agent cannot reliably localize a file that did not exist in the checkout it was
given.

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
  `commands`, including `commandCount`, `agraphCommandCount`,
  `searchCommandCount`, `fileReadCommandCount`, and `shellCommandCount`.
  Search counts cover command forms such as `rg`, `grep`, `git grep`, `fd`,
  and `find`; file-read counts cover common local read commands such as `cat`,
  `sed`, `head`, `tail`, `nl`, `awk`, and pagers. These are mechanical cited
  command counts, not provider-side tool logs. `agent-compare` treats increases
  in search, file-read, and generic shell command counts as lower-is-better
  regressions when reports are comparable; AGraph command counts are reported
  for interpretation but are not a regression gate. If AGraph command counts
  are the only available shared metric, the comparison status is
  `observed-only`, not an improvement or regression claim.
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
- `agentDiagnostics.hintDiagnosticRuns`: scored AGraph-mode artifacts whose
  generated hint file reported help-quality diagnostics. Gate this with
  `--max-hint-diagnostic-runs` when the hints should be free of coverage,
  source-support, or zero-candidate warnings.
- `agentDiagnostics.identityMismatchRuns`: scored agent artifacts whose schema,
  case id, or case fingerprint did not match the prepared case. These are also
  counted under `warningRuns`; gate them directly with
  `--max-identity-mismatch-runs`.
- `localizationDiagnostics.missedButPresentInContextRuns`: missed scoreable files
  that were present in the AGraph context packet. Gate with
  `--max-missed-but-present-in-context-runs` when prompt or agent selection
  quality should not regress.
- `localizationDiagnostics.missedAndAbsentFromContextRuns`: missed scoreable
  files that were absent from the AGraph context packet. Gate with
  `--max-missed-and-absent-from-context-runs` when graph extraction, indexing, or
  retrieval coverage should not regress.
- `coverageDiagnostics`: aggregate source-support counters. `agent-compare`
  treats increases in missing declared source-kind runs, coverage-excluded
  ground-truth files, and unsupported ground-truth files as lower-is-better
  regressions when reports are comparable.
- `improvementSummary`: ordered remediation targets derived from the report
  diagnostics. `agent-compare` sums their `runs` as `improvementTargetRuns`,
  exposes `improvementTargetRunsByKind`, and treats total or per-kind increases
  as lower-is-better regressions when reports are comparable.
- `artifactDiagnostics`: aggregate score-artifact freshness counters.
  `agent-compare` treats increases in unverified score runs, obsolete score
  schema runs, obsolete agent-result schema runs, and stale score runs as
  lower-is-better regressions when reports are comparable.

Each result also records `groundTruthRanks.files`, which lists every scoreable
localization file and the rank where AGraph found it, or `found? false` when it
was outside the collected result window. Use that before tuning ranking;
aggregate recall alone does not show whether a miss is close or completely
absent. Agent reports also copy this into `localization.ranks` and summarize
`rankedOutsideTop5`, `rankedOutsideTop10`, and `rankedOutsideTop20` for quick
threshold debugging.

Agent-style AGraph runs may also record `contextGroundTruthRanks`. This compares
the scoreable localization files against the full AGraph context ranking before
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
run AGraph, and compare ranked output with the real fix. AGraph core must still
avoid semantic shortcuts based on path names, issue vocabulary, host names, or
project-specific labels.

Discovery from GitHub issues and PRs is intentionally separate from replay. The
replay runner only needs a suite EDN with case ids, repo ids, base SHAs, fix
SHAs, and issue text; discovery can generate that suite later.
