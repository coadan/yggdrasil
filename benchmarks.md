# Benchmarks

Generated on 2026-06-23. Artifacts live under
`.dev/ygg/performance-benchmarks/` and are intentionally local generated
outputs.

These numbers are for the current synthetic architecture benchmark slices. They
are useful for regression tracking, but they do not support a broad efficiency
claim by themselves. The headline agent comparison reports `mixed` /
`inconclusive`: Yggdrasil improved localization, noise, and evidence metrics,
but regressed token usage and wall-clock time.

## Artifact Gate

`bb bench:gate --check-only` passed against the existing gate artifact, and a
fresh `bb bench:gate --out .dev/ygg/performance-benchmarks/current` also passed.
The fresh run compared cleanly against the previous gate artifact with no
reported regressions.

| Metric | Previous gate | Current gate | Delta | Result |
| --- | ---: | ---: | ---: | --- |
| Completed cases | 5/5 | 5/5 | 0 | same |
| Recall@5 | 0.73 | 1.00 | +0.27 | improved |
| Recall@10 | 0.83 | 1.00 | +0.17 | improved |
| Recall@20 | 0.83 | 1.00 | +0.17 | improved |
| MRR | 0.72 | 1.00 | +0.28 | improved |
| Noise@20 | 0.64 | 0.39 | -0.25 | improved |
| Expected evidence citation | 0.90 | 1.00 | +0.10 | improved |
| Warning runs | 5 | 1 | -4 | improved |
| Total tokens | 30,180 | 3,629 | -26,551 | improved |

Source artifact:
`.dev/ygg/performance-benchmarks/current-vs-gate/architecture-synthetic/agent-compare.json`

## Headline Agent Slice

Commands run:

```sh
bb headline baseline --out .dev/ygg/performance-benchmarks/headline
bb headline codebase-memory --out .dev/ygg/performance-benchmarks/headline \
  --codebase-memory-bin "$PWD/.dev/tools/bin/codebase-memory-mcp"
bb headline agents --out .dev/ygg/performance-benchmarks/headline --timeout-ms 600000
bb headline reports --out .dev/ygg/performance-benchmarks/headline
bb headline compare --out .dev/ygg/performance-benchmarks/headline
```

The raw duration numbers below come from each case's
`agent-runs/codex.json` `process.durationMs`, not from the aggregate rescore
`elapsedMs` metric.

| Metric | Shell-only Codex | Yggdrasil Codex | Delta | Result |
| --- | ---: | ---: | ---: | --- |
| Completed cases | 5/5 | 5/5 | 0 | same |
| Raw agent duration, total | 703,820 ms | 985,642 ms | +281,822 ms (+40.0%) | regressed |
| Raw agent duration, average | 140,764 ms | 197,128 ms | +56,364 ms | regressed |
| Recall@10 | 0.83 | 0.93 | +0.10 | improved |
| Recall@20 | 0.83 | 0.93 | +0.10 | improved |
| MRR | 1.00 | 1.00 | 0.00 | same |
| Noise@20 | 0.347 | 0.220 | -0.127 | improved |
| Evidence citation | 1.00 | 1.00 | 0.00 | same |
| Path evidence citation | 0.36 | 0.74 | +0.38 | improved |
| Expected evidence citation | 0.70 | 0.80 | +0.10 | improved |
| Commands | 38 | 35 | -3 | improved |
| Search commands | 8 | 6 | -2 | improved |
| File-read commands | 21 | 14 | -7 | improved |
| Total tokens | 1,333,896 | 1,863,283 | +529,387 (+39.7%) | regressed |
| Input tokens | 1,301,045 | 1,818,065 | +517,020 | regressed |
| Output tokens | 32,851 | 45,218 | +12,367 | regressed |

Per-case token usage improved in 2/5 shared cases and regressed in 3/5. The
comparison claim readiness is therefore `not-supported`: report the class-level
tradeoff, not a broad Yggdrasil efficiency win.

Source artifacts:
`.dev/ygg/performance-benchmarks/headline/summary.json` and
`.dev/ygg/performance-benchmarks/headline/REPORT.md`

## Headline Per Case

| Case | Recall@10 shell | Recall@10 Ygg | Duration delta | Token delta | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| `headline-axios-native-proxy-boundary` | 1.00 | 1.00 | +24,315 ms | -27,899 | token win, time regression |
| `headline-bootstrap-astro-plugin-config` | 1.00 | 1.00 | +118,024 ms | -27,692 | token win, time regression |
| `headline-bootstrap-docs-route-impact` | 1.00 | 1.00 | -8,568 ms | +74,155 | time win, token regression |
| `headline-dapper-jsonb-test-stack` | 0.67 | 0.67 | +8,565 ms | +181,012 | regression |
| `headline-supabase-trigger-ownership-flow` | 0.50 | 1.00 | +139,486 ms | +329,811 | quality win, cost regression |

## Codebase Memory Slice

`codebase-memory-mcp` is installed on `PATH` at
`/Users/vegard/.local/bin/codebase-memory-mcp` and the benchmark run used the
repo-local copy at `.dev/tools/bin/codebase-memory-mcp` (`0.8.1`). It was
installed as a binary-only tool with agent configuration skipped, then passed a
direct repo-index smoke test and the full Codebase Memory headline lane.

The Codebase Memory lane below is a real deterministic external-baseline run,
not the previous missing-binary placeholder lane. Token usage is the benchmark
result-surface estimate emitted by the worker, not LLM API usage.

| Lane | Status | Cases | Recall@10 | MRR | Noise@20 | Tokens | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Shell-only Codex | measured | 5 | 0.83 | 1.00 | 0.347 | 1,333,896 | Direct agent baseline |
| Yggdrasil Codex | measured | 5 | 0.93 | 1.00 | 0.220 | 1,863,283 | Better quality, higher token/time cost |
| Yggdrasil deterministic baseline | measured | 5 | 1.00 | 1.00 | 0.387 | 3,627 | No external agent loop; graph/index baseline |
| Codebase Memory | measured | 5 | 0.27 | 0.43 | 0.960 | 11,841 | Real `codebase-memory-mcp` CLI lane; 70 tool commands, zero warnings |

Codebase Memory aggregate details:

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Recall@5 | 0.17 |
| Recall@10 | 0.27 |
| Recall@20 | 0.37 |
| MRR | 0.43 |
| Evidence citation | 1.00 |
| Expected evidence citation | 0.33 |
| Path evidence citation | 0.00 |
| Total worker elapsed | 37,050 ms |
| Codebase Memory worker elapsed | 32,195 ms |
| Warning runs | 0 |
| Claim readiness | supported for the synthetic measured slice |

Source artifacts:
`.dev/ygg/performance-benchmarks/headline/codebase-memory/headline-architecture/agent-report.json`
and
`.dev/ygg/performance-benchmarks/headline/ygg-baseline/headline-architecture/agent-report.json`
