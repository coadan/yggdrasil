# Agent Efficiency Study

This study measures whether AGraph makes coding-agent development easier and
more efficient than standard CLI exploration alone. It intentionally uses the
existing benchmark runner without changing benchmark scoring, prompts, or
reporting internals.

## Comparison

Run the same benchmark cases twice:

- `shell-only`: the agent may use normal repository tools such as `rg`, `git`,
  `find`, `sed`, test commands, and package-manager commands.
- `agraph`: the same agent may use the same shell tools plus AGraph-generated
  hints, context packets, and commands exposed by `bb bench agent-run`.

Keep the model, agent command, timeout, benchmark suite, cases, parser-worker
profile, and prompt profile identical across both modes. The mode should be the
only intentional difference.

Do not treat a win on simple file-localization issues as proof that AGraph is
the right tool for all agent work. Tag every efficiency case with manually
chosen problem-class tags such as `:problem-localization`,
`:problem-cross-file-change`, `:problem-architecture`,
`:problem-dependency-upgrade`, `:problem-runtime-config`,
`:problem-api-contract`, or `:problem-refactor`. These are benchmark labels, not
AGraph core semantics. `bb efficiency` compares the shared `byTag` groups from
each `agent-report.json`, so the summary can show which classes improve,
regress, or remain shell-sufficient.

Include an architecture-class slice even if some cases are synthetic prompts on
the OSS corpus. Synthetic cases should still use real base checkouts and fair
task text, but may use curated `:ground-truth` and `:expectations` when the
question is about system boundaries, dependency flow, route-to-handler shape,
runtime configuration, data ownership, or cross-file impact rather than one
historical bug fix. Mark them with tags such as `:synthetic`,
`:problem-architecture`, `:architecture-boundary`,
`:architecture-dependency-flow`, `:audit-scope-dependencies`, or
`:architecture-cross-system-impact`.

Starter architecture-class OSS cases:

- `bootstrap-synthetic-docs-route-impact`: ask which Astro route files and
  component imports are impacted by removing the docs theme surface. Expect
  `site/src/pages/index.astro`,
  `site/src/pages/docs/[version]/examples/index.astro`, and their imported
  theme components. Tags: `:synthetic`, `:problem-architecture`,
  `:architecture-cross-system-impact`, `:web-framework`.
- `bootstrap-synthetic-astro-plugin-config`: ask where a docs build integration
  should be wired and how the package manifest relates to the Astro config.
  Expect `site/astro.config.ts`, `package.json`, and `astro/config` import or
  plugin evidence. Tags: `:synthetic`, `:problem-architecture`,
  `:architecture-dependency-flow`, `:audit-scope-dependencies`,
  `:web-framework-config`.
- `supabase-postgres-synthetic-trigger-ownership-flow`: ask which database init
  scripts, environment files, and ownership-sensitive SQL control built-in event
  triggers. Expect `migrations/db/init-scripts/00000000000003-post-setup.sql`,
  `migrations/.env`, and SQL/security evidence. Tags: `:synthetic`,
  `:problem-architecture`, `:architecture-data-ownership`, `:database`.
- `axios-synthetic-native-proxy-boundary`: ask where Node native proxy handling
  intersects with Axios adapter proxy rewriting and which tests/config values
  prove the boundary. Expect `lib/adapters/http.js` or its test coverage, plus
  `NODE_USE_ENV_PROXY` and proxy env evidence. Tags: `:synthetic`,
  `:problem-architecture`, `:architecture-runtime-boundary`,
  `:runtime-config`.
- `dapper-synthetic-jsonb-test-stack`: ask how the PostgreSQL test container,
  Dapper type handling, and JSONB regression tests connect. Expect
  `tests/docker-compose.yml`, `Dapper/SqlMapper.cs`, and Dapper test files.
  Tags: `:synthetic`, `:problem-architecture`,
  `:architecture-dependency-flow`, `:audit-scope-dependencies`, `:database`.

The tracked starter suite is `benchmarks/oss-architecture-synthetic.edn`. It
expects local OSS checkouts under `.dev/oss-test-cases/repos/` and keeps all
prepared cases, worktrees, graph stores, and reports under `.dev/` via `--out`.
Use it as the architecture-class slice alongside historical issue replay; do
not treat a simple localization-only suite as representative proof for AGraph.

## Commands

Use separate generated output roots so artifacts cannot overwrite each other:

```sh
bb bench agent-run .dev/benchmarks/oss-issue-replay.edn \
  --agent codex-efficiency \
  --command 'codex -a never -m gpt-5.5 -c model_reasoning_effort="\"low\"" exec --sandbox read-only -o "$AGRAPH_BENCH_RESULT" - < "$AGRAPH_BENCH_PROMPT"' \
  --mode shell-only \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/agraph/agent-efficiency/shell-only

bb bench agent-run .dev/benchmarks/oss-issue-replay.edn \
  --agent codex-efficiency \
  --command 'codex -a never -m gpt-5.5 -c model_reasoning_effort="\"low\"" exec --sandbox read-only -o "$AGRAPH_BENCH_RESULT" - < "$AGRAPH_BENCH_PROMPT"' \
  --mode agraph \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/agraph/agent-efficiency/agraph
```

Summarize each lane with existing reports:

```sh
bb bench agent-report .dev/benchmarks/oss-issue-replay.edn \
  --mode shell-only \
  --agent codex-efficiency \
  --out .dev/agraph/agent-efficiency/shell-only \
  --json

bb bench agent-report .dev/benchmarks/oss-issue-replay.edn \
  --mode agraph \
  --agent codex-efficiency \
  --out .dev/agraph/agent-efficiency/agraph \
  --json
```

Compare the two reports without changing benchmark scoring:

```sh
bb efficiency \
  .dev/agraph/agent-efficiency/shell-only/agent-report.json \
  .dev/agraph/agent-efficiency/agraph/agent-report.json \
  --out .dev/agraph/agent-efficiency/summary.json \
  --json
```

## Metrics

Use existing benchmark report fields first:

- localization: file recall at 5, 10, and 20; MRR; missed runs
- noise: noise at 20 and predicted files that do not exist
- evidence: evidence citation rate and path evidence citation rate
- result health: warning runs, empty result runs, commandless runs
- command telemetry: `agentDiagnostics.commandTelemetry` command, search,
  file-read, AGraph, and shell counts derived from cited benchmark commands
  (`agraphCommandCount` is reported for interpretation, not treated as a
  lower-is-better regression gate; if only observed metrics are available,
  `bb efficiency` reports `observed-only` instead of a win or loss, and broad
  claim readiness remains blocked until directional metrics are available).
  When reports include compound-command segment counters, `bb efficiency`
  compares segment, search-segment, and file-read-segment counts as
  lower-is-better command telemetry too.
- task fit: `bb efficiency` `classSignals` for compact problem-class and
  architecture-class rows. Use each row's `measured` flag to distinguish a
  shared class-shaped tag from a class that counts toward claim readiness; fall
  back to `byTag.groups` only when inspecting arbitrary non-class tags. Use
  `classSignals.summary.measuredProblemClasses` and
  `classSignals.summary.measuredArchitectureClasses` for machine gates.
- timing: stage timings and active-stage diagnostics from progress artifacts

For patching runs, also record test pass rate, lint pass rate, unrelated file
churn, and whether the final patch touches the historical fix area. Keep those
as a separate analysis artifact until the metric is stable enough to promote
into benchmark core.

## Interpretation

AGraph is useful if the `agraph` lane improves agent orientation without adding
unacceptable noise or runtime cost. Strong evidence includes:

- higher recall or MRR at the same case count
- fewer missed runs or empty outputs
- higher citation rates
- fewer opened files or shell commands when command telemetry is available
- equal or better patch success on task-completion runs

Observed-only telemetry is useful for debugging how agents use AGraph, but it
does not prove efficiency. Broad claims need directional metrics such as recall,
noise, citation quality, elapsed time, command reductions, or patch outcomes.

Track cold, warm, and amortized costs separately. A cold run includes initial
indexing. A warm run assumes the project graph already exists. The amortized
view spreads indexing cost across a batch of agent tasks.

## Guardrails

- Do not edit `src/agraph/benchmark.clj`, benchmark tests, or
  `docs/benchmarking.md` for this study.
- Do not change benchmark scoring semantics while collecting the first results.
- Do not claim broad agent efficiency from a suite that only contains simple
  localization cases. Cover several manually tagged problem classes, or state
  the narrower class where AGraph helped.
- Store generated outputs under `.dev/agraph/agent-efficiency/`.
- Keep ad hoc analysis scripts separate from benchmark core until their metrics
  are proven useful across multiple cases.
- Treat historical issue text as the only task input. Do not expose fix diffs,
  PR text, post-fix comments, or changed file names to either lane.
