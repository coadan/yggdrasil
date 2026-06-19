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

## Commands

Use separate generated output roots so artifacts cannot overwrite each other:

```sh
bb bench agent-run .dev/benchmarks/oss-issue-replay.edn \
  --agent codex-efficiency \
  --command 'codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"' \
  --mode shell-only \
  --prompt-profile fast \
  --timeout-ms 120000 \
  --out .dev/agraph/agent-efficiency/shell-only

bb bench agent-run .dev/benchmarks/oss-issue-replay.edn \
  --agent codex-efficiency \
  --command 'codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"' \
  --mode agraph \
  --prompt-profile fast \
  --timeout-ms 120000 \
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

## Metrics

Use existing benchmark report fields first:

- localization: file recall at 5, 10, and 20; MRR; missed runs
- noise: noise at 20 and predicted files that do not exist
- evidence: evidence citation rate and path evidence citation rate
- result health: warning runs, empty result runs, commandless runs
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

Track cold, warm, and amortized costs separately. A cold run includes initial
indexing. A warm run assumes the project graph already exists. The amortized
view spreads indexing cost across a batch of agent tasks.

## Guardrails

- Do not edit `src/agraph/benchmark.clj`, benchmark tests, or
  `docs/benchmarking.md` for this study.
- Do not change benchmark scoring semantics while collecting the first results.
- Store generated outputs under `.dev/agraph/agent-efficiency/`.
- Keep ad hoc analysis scripts separate from benchmark core until their metrics
  are proven useful across multiple cases.
- Treat historical issue text as the only task input. Do not expose fix diffs,
  PR text, post-fix comments, or changed file names to either lane.
