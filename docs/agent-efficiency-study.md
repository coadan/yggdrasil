# Agent Efficiency Study

This study measures whether Yggdrasil makes coding-agent development easier and
more efficient than standard CLI exploration alone. It intentionally uses the
existing benchmark runner without changing benchmark scoring, prompts, or
reporting internals.

## Comparison

Run the same benchmark cases twice:

- `shell-only`: the agent may use normal repository tools such as `rg`, `git`,
  `find`, `sed`, test commands, and package-manager commands.
- `ygg`: the same agent may use the same shell tools plus Yggdrasil-generated
  hints, context packets, and commands exposed by `bb bench agent-run`.

Keep the model, agent command, timeout, benchmark suite, cases, parser-worker
profile, and prompt profile identical across both modes. The mode should be the
only intentional difference.

Do not treat a win on simple file-localization issues as proof that Yggdrasil is
the right tool for all agent work. Tag every efficiency case with manually
chosen problem-class tags such as `:problem-localization`,
`:problem-cross-file-change`, `:problem-architecture`,
`:problem-dependency-upgrade`, `:problem-runtime-config`,
`:problem-api-contract`, or `:problem-refactor`. These are benchmark labels, not
Yggdrasil core semantics. `bb efficiency` compares the shared `byTag` groups from
each `agent-report.json`, so the summary can show which classes improve,
regress, or remain shell-sufficient.

Include an architecture-class slice even if some cases are synthetic prompts on
the benchmark corpus. Synthetic cases should still use real base checkouts and fair
task text, but may use curated `:ground-truth` and `:expectations` when the
question is about system boundaries, dependency flow, route-to-handler shape,
runtime configuration, data ownership, or cross-file impact rather than one
historical bug fix. Mark them with tags such as `:synthetic`,
`:problem-architecture`, `:architecture-boundary`,
`:architecture-dependency-flow`, `:audit-scope-dependencies`, or
`:architecture-cross-system-impact`.

Starter architecture-class cases:

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

The tracked starter suite is `benchmarks/architecture-synthetic.edn`. The
tracked broader selector is `benchmarks/agent-efficiency-broad.edn`, which
composes headline architecture, additional architecture coverage, and
decision-quality cases through `:include-suites`. Both expect local benchmark
checkouts under `.dev/ygg/benchmark-repos/` and keep prepared cases, worktrees,
graph stores, and reports under `.dev/` via `--out`. Use the broad selector for
shell-only versus Yggdrasil task-token comparisons; do not treat a simple
localization-only suite as representative proof for Yggdrasil.

For the bounded architecture benchmark improvement slice, use
`benchmarks/headline.edn` as the fixed five-case selector. It covers dependency
and audit-scope evidence, runtime/config evidence, architecture boundaries, and
one `:shell-sufficient-control` case where Yggdrasil should not be expected to win
by construction. Stop after this fixed set produces one comparison report; do
not keep adding cases to rescue a weak result.

## Commands

Use separate generated output roots so artifacts cannot overwrite each other:

```sh
bb bench agent-run benchmarks/agent-efficiency-broad.edn \
  --agent codex-efficiency \
  --command "python3 $(pwd)/scripts/codex-benchmark-agent.py" \
  --mode shell-only \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/ygg/agent-efficiency/shell-only

bb bench agent-run benchmarks/agent-efficiency-broad.edn \
  --agent codex-efficiency \
  --command "python3 $(pwd)/scripts/codex-benchmark-agent.py" \
  --mode ygg \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/ygg/agent-efficiency/ygg
```

Set `YGG_CODEX_MODEL`, `YGG_CODEX_EXTRA_ARGS`, or `YGG_CODEX_BIN` to tune the
Codex invocation without changing benchmark commands. The wrapper reads Codex
JSONL output and writes `$YGG_BENCH_TOKEN_USAGE` when usage telemetry is present,
so `bb efficiency` can compare end-to-end task tokens for the shell-only and
Yggdrasil lanes.

For the common broad comparison, prefer the helper:

```sh
bb agent-efficiency all --agent codex-efficiency --timeout-ms 600000
```

This uses `benchmarks/agent-efficiency-broad.edn`, writes generated artifacts
under `.dev/ygg/agent-efficiency/broad`, runs both lane reports, then runs
`bb bench agent-check` for shell-only and Yggdrasil before writing the claim
pack. The default token high-water mark is intentionally huge; it exists to make
missing `tokenUsage` fail before a claim pack is produced. Tighten it with
`--max-total-tokens` only after the suite has stable measured baselines. Use
`--case <id>` or `--cases <id,id>` for a focused token-telemetry smoke run
before spending agent calls on the full broad selector.

DeepSeek v4 Pro can be run through the productized benchmark worker in
`scripts/deepseek-agent.py`. Set `DEEPSEEK_API_KEY` directly or point
`DEEPSEEK_ENV_FILE` at a local env file that contains it. Keep the same
`--agent`, `--command`, prompt profile, timeout, and parser-worker settings for
both lanes:

```sh
bb bench agent-run benchmarks/agent-efficiency-broad.edn \
  --agent deepseek-v4-pro \
  --command 'python3 scripts/deepseek-agent.py' \
  --mode shell-only \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/ygg/agent-efficiency/deepseek-shell-only

bb bench agent-run benchmarks/agent-efficiency-broad.edn \
  --agent deepseek-v4-pro \
  --command 'python3 scripts/deepseek-agent.py' \
  --mode ygg \
  --prompt-profile fast \
  --timeout-ms 600000 \
  --out .dev/ygg/agent-efficiency/deepseek-ygg
```

Codebase Memory MCP can be run as a deterministic comparison baseline, not as a
same-agent MCP-assisted lane. Install or build `codebase-memory-mcp` separately,
then point Yggdrasil at the binary:

```sh
bb bench agent-baseline benchmarks/headline.edn \
  --retriever codebase-memory \
  --codebase-memory-bin /path/to/codebase-memory-mcp \
  --out .dev/ygg/headline-bench/codebase-memory

bb bench agent-report benchmarks/headline.edn \
  --mode codebase-memory \
  --agent ygg-baseline-codebase-memory \
  --out .dev/ygg/headline-bench/codebase-memory
```

For the bounded helper workflow, `bb headline codebase-memory` runs only that
lane and `bb headline external-baselines` runs both deterministic baseline
reports. Use this to compare structural retrieval/localization behavior before
making a broader same-agent MCP plan.

Treat Yggdrasil's multi-repo model and plugin ecosystem as explicit benchmark
hypotheses. Good comparison suites should include cross-repo ownership,
dependency-flow, service-boundary, and plugin-covered project shapes where a
generic structural graph may miss domain-specific facts. Claim an advantage only
when the reports show where those classes improved, stayed neutral, or regressed.

Summarize each lane with existing reports:

```sh
bb bench agent-report benchmarks/agent-efficiency-broad.edn \
  --mode shell-only \
  --agent codex-efficiency \
  --out .dev/ygg/agent-efficiency/shell-only \
  --json

bb bench agent-report benchmarks/agent-efficiency-broad.edn \
  --mode ygg \
  --agent codex-efficiency \
  --out .dev/ygg/agent-efficiency/ygg \
  --json
```

Compare the two reports without changing benchmark scoring:

```sh
bb efficiency \
  .dev/ygg/agent-efficiency/shell-only/agent-report.json \
  .dev/ygg/agent-efficiency/ygg/agent-report.json \
  --out .dev/ygg/agent-efficiency/summary.json \
  --json
```

For headline runs, prefer the bounded helper:

```sh
bb headline all
```

`bb headline all` runs token telemetry gates before `bb bench claim-pack`, which
writes `claim-pack.json`, `CLAIM-PACK.md`, `efficiency-summary.json`,
`efficiency-summary.md`, and `system-improvement-report.json` under the local
headline output root. Use `bb headline all --skip-token-check` only for
diagnostic runs that are not making token-use claims. Use the lower-level
`bb efficiency` command only when you need a raw shell-only versus Yggdrasil
comparison without the bundled proof artifacts.

Read `claim-pack.json` `summary.verdict` first, then inspect
`efficiency.compactSummary.why` for the minimum explanation that should appear
in status updates or release notes. If the verdict is `inconclusive`, do not
promote the bounded claim even when some individual metrics improved.

## Metrics

Use existing benchmark report fields first:

- localization: file recall at 5, 10, and 20; MRR; missed runs
- noise: noise at 20 and predicted files that do not exist
- evidence: evidence citation rate and path evidence citation rate
- decision quality: decision recall, precision, F1, evidence citation rate,
  missing decision runs, and decision-quality gap runs when reports contain
  configured decision cases
- result health: warning runs, empty result runs, commandless runs
- command telemetry: `agentDiagnostics.commandTelemetry` command, search,
  file-read, Yggdrasil, and shell counts derived from cited benchmark commands
  (`yggCommandCount` is reported for interpretation, not treated as a
  lower-is-better regression gate; if only observed metrics are available,
  `bb efficiency` reports `observed-only` instead of a win or loss, and broad
  claim readiness remains blocked until directional metrics are available).
  When reports include compound-command segment counters, `bb efficiency`
  compares segment, search-segment, and file-read-segment counts as
  lower-is-better command telemetry too.
- token cost: `agentDiagnostics.tokenTelemetry` input, output, total token, and
  cost totals when agent results include `tokenUsage`. `bb efficiency` compares
  these as lower-is-better and emits a `qualityCostTradeoff` summary when token
  telemetry is present. It also emits per-shared-task token comparisons under
  `caseDeltas[].taskTokenDeltas`, and the Markdown report includes
  `Task Token Deltas` for the final shell-only versus Ygg task token count.
  Agent wrappers can either write `tokenUsage` in the result JSON or write a
  provider sidecar to `$YGG_BENCH_TOKEN_USAGE`. For Codex CLI runs, use
  `scripts/codex-benchmark-agent.py`; for DeepSeek runs, use
  `scripts/deepseek-agent.py`.
  `bb bench agent-check` can enforce aggregate and per-case budgets with
  `--max-total-tokens`, `--max-input-tokens`, `--max-output-tokens`,
  `--max-cost-usd`, `--max-case-total-tokens`, `--max-case-input-tokens`,
  `--max-case-output-tokens`, and `--max-case-cost-usd`; configured token gates
  fail when token usage is absent.
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

Yggdrasil is useful if the `ygg` lane improves agent orientation without adding
unacceptable noise or runtime cost. Strong evidence includes:

- higher recall or MRR at the same case count
- fewer missed runs or empty outputs
- higher citation rates
- fewer opened files or shell commands when command telemetry is available
- lower token or dollar cost at equal or better decision/localization quality,
  or an explicit quality-versus-token tradeoff when Yggdrasil spends more tokens
  to make better decisions on complex-system cases
- equal or better patch success on task-completion runs

Observed-only telemetry is useful for debugging how agents use Yggdrasil, but it
does not prove efficiency. Broad claims need directional metrics such as recall,
noise, citation quality, elapsed time, command reductions, token/cost deltas,
or patch outcomes.

Track cold, warm, and amortized costs separately. A cold run includes initial
indexing. A warm run assumes the project graph already exists. The amortized
view spreads indexing cost across a batch of agent tasks.

## Guardrails

- Do not edit `src/ygg/benchmark.clj`, benchmark tests, or
  `docs/benchmarking.md` for this study.
- Do not change benchmark scoring semantics while collecting the first results.
- Do not claim broad agent efficiency from a suite that only contains simple
  localization cases. Cover several manually tagged problem classes, or state
  the narrower class where Yggdrasil helped.
- Store generated outputs under `.dev/ygg/agent-efficiency/`.
- Keep ad hoc analysis scripts separate from benchmark core until their metrics
  are proven useful across multiple cases.
- Treat historical issue text as the only task input. Do not expose fix diffs,
  PR text, post-fix comments, or changed file names to either lane.
