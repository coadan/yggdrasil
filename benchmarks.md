# Benchmarks

Updated on 2026-06-26. Artifacts live under `.dev/ygg/server-bench/`,
`.dev/ygg/server-broad/`, `.dev/ygg/performance-benchmarks/`, and the
slice-specific `.dev/ygg/` subdirectories named below. They are intentionally
local generated outputs.

These numbers are for the current synthetic architecture benchmark slices. They
are useful for regression tracking, but they do not support a broad agent
efficiency claim by themselves. The current server-backed deterministic gate and
current broad deterministic baseline both pass with claim readiness supported
for the measured architecture classes. The completed external agent comparisons
remain `mixed` / `inconclusive`: Yggdrasil improved evidence and reduced broad
rediscovery in some lanes, but the completed comparisons either regressed first
prompt size, localization, token use, or wall-clock time, or were run before the
current maintained-graph fixes.

## Current Server-backed Gate

Commands run:

```sh
YGG_HOME="$PWD/.dev/ygg/localization-ranking-home" \
YGG_PROJECTS_FILE="$PWD/.dev/ygg/localization-ranking-home/projects.edn" \
YGG_SERVER_PORT=62125 \
YGG_XTDB_PATH="$PWD/.dev/ygg/localization-ranking-xtdb" \
YGG_SERVER_REQUEST_TIMEOUT_MS=3600000 \
bb bench:gate --out .dev/ygg/localization-ranking/slice-1/gate

YGG_HOME="$PWD/.dev/ygg/localization-ranking-home" \
YGG_PROJECTS_FILE="$PWD/.dev/ygg/localization-ranking-home/projects.edn" \
YGG_SERVER_PORT=62125 \
YGG_XTDB_PATH="$PWD/.dev/ygg/localization-ranking-xtdb" \
YGG_SERVER_REQUEST_TIMEOUT_MS=3600000 \
bb bench:gate --check-only --out .dev/ygg/localization-ranking/slice-1/gate
```

The server was run with an isolated home, registry file, port, and XTDB path.
Before the run, `ygg status --json` reported `busy: false`,
`activeOperations: []`, and zero registered maintenance projects. That keeps the
benchmark from paying per-command JVM startup and from picking up unrelated
scheduled project maintenance.

Deterministic gate result: passed.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@5 | 0.83 |
| File recall@10 | 0.83 |
| File recall@20 | 0.83 |
| MRR | 0.77 |
| Evidence citation | 1.00 |
| Expected evidence citation | 0.80 |
| Path evidence citation | 1.00 |
| Noise@20 | 0.82 |
| Maintenance preflight blockers | 0 |
| Claim readiness | supported |
| Warm elapsed | 4,891 ms |
| Agent-ready elapsed | 1,370 ms |
| Amortized setup elapsed | 104,274 ms |
| Total elapsed | 109,165 ms |

The Dapper JSONB test-stack case previously blocked maintenance preflight on
unresolved `.NET` imports for `LinqToDB.*` with declared package
`linq2db.SqlServer`. The regenerated five-case gate now reports maintenance
preflight passed with zero blocked cases. The Dapper case still has only partial
localization in the deterministic baseline, with file recall@10 `0.67`.

Claim status: supports that the current server-backed deterministic gate passes
and that the maintenance preflight regression is fixed. It does not support a
broad wall-clock or agent-efficiency claim by itself; the suite is still wholly
synthetic, and the existing headline agent comparison remains mixed.

Source artifacts:
`.dev/ygg/localization-ranking/slice-1/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/localization-ranking/slice-1/gate/architecture-synthetic/agent-report.json`.

## Current Broad Deterministic Baseline

Commands run:

```sh
YGG_HOME="$PWD/.dev/ygg/localization-ranking-home" \
YGG_PROJECTS_FILE="$PWD/.dev/ygg/localization-ranking-home/projects.edn" \
YGG_SERVER_PORT=62125 \
YGG_XTDB_PATH="$PWD/.dev/ygg/localization-ranking-xtdb" \
YGG_SERVER_REQUEST_TIMEOUT_MS=3600000 \
bb bench agent-baseline benchmarks/agent-efficiency-broad.edn \
  --out .dev/ygg/localization-ranking/slice-1/full/ygg-baseline

YGG_HOME="$PWD/.dev/ygg/localization-ranking-home" \
YGG_PROJECTS_FILE="$PWD/.dev/ygg/localization-ranking-home/projects.edn" \
YGG_SERVER_PORT=62125 \
YGG_XTDB_PATH="$PWD/.dev/ygg/localization-ranking-xtdb" \
YGG_SERVER_REQUEST_TIMEOUT_MS=3600000 \
bb bench agent-report benchmarks/agent-efficiency-broad.edn \
  --mode ygg \
  --agent ygg-baseline-lexical \
  --out .dev/ygg/localization-ranking/slice-1/full/ygg-baseline

YGG_HOME="$PWD/.dev/ygg/localization-ranking-home" \
YGG_PROJECTS_FILE="$PWD/.dev/ygg/localization-ranking-home/projects.edn" \
YGG_SERVER_PORT=62125 \
YGG_XTDB_PATH="$PWD/.dev/ygg/localization-ranking-xtdb" \
YGG_SERVER_REQUEST_TIMEOUT_MS=3600000 \
bb bench:gate \
  --suite benchmarks/agent-efficiency-broad.edn \
  --check-only \
  --out .dev/ygg/localization-ranking/slice-1/full/ygg-baseline
```

Result: passed. This is the current maintained broad architecture proof over 25
cases. It is deterministic Yggdrasil baseline evidence, not a shell-only versus
Yggdrasil external agent comparison.

| Metric | Value |
| --- | ---: |
| Completed cases | 25/25 |
| File recall@5 | 0.765 |
| File recall@10 | 0.849 |
| File recall@20 | 0.913 |
| MRR | 0.768 |
| Evidence citation | 1.00 |
| Expected evidence citation | 0.905 |
| Path evidence citation | 1.00 |
| Noise@20 | 0.518 |
| Maintenance preflight blockers | 0 |
| Claim readiness | supported |
| Problem classes measured | 6 |
| Architecture classes measured | 8 |
| Warm elapsed | 33,989 ms |
| Agent-ready elapsed | 10,983 ms |
| Amortized setup elapsed | 956,001 ms |
| Total elapsed | 989,990 ms |

Maintenance preflight now passes across all 25 cases: index, inference, graph
expectations, hint diagnostics, and sync/check-equivalent status all passed.
The current localization slice exposes the full already-ranked suspect set in
the compact deterministic result and carries selected architecture evidence file
paths into the candidate-file surface. This is still mechanical evidence
promotion, not a semantic path/name classifier.

Claim status: supports maintained broad deterministic architecture coverage for
the measured classes. It does not support a broad agent-efficiency claim by
itself. Localization improved, but ranking remains the next quality issue:
21/25 cases found all scoreable files, while 4 still missed at least one
scoreable file. Of the remaining misses, 1 was present in context but outside
the final selected files and 3 were absent from context. Four cases had all
scoreable files present by rank 20 but still ranked at least one scoreable file
outside the top 10:
`historical-bootstrap-node-version-workflows`,
`junit-test-engine-lifecycle-ownership`,
`otel-connector-consumer-component-flow`, and
`terraform-vpc-flow-log-data-ownership`. The main tradeoff is noise: `noise@20`
rose from the prior broad baseline while recall@10 and recall@20 improved.

Source artifact:
`.dev/ygg/localization-ranking/slice-1/full/ygg-baseline/agent-efficiency-broad/agent-report.json`.

A fresh shell-only broad rerun was started under
`.dev/ygg/server-broad/full-fix-2/shell-only`, but it was stopped after producing
only 4/25 case artifacts. It is intentionally excluded from claims; the last
completed shell-only versus Yggdrasil broad agent comparison remains the slice-5
run below.

## Ripgrep Query Work Protocol

Ripgrep/query-output changes must be benchmarked before any speed, token, or
agent-efficiency claim. Keep generated artifacts under
`.dev/ygg/ripgrep-leverage/<slice>/` and update this file with measured results
after each completed slice.

Preflight:

```sh
bb bench repos check --suite benchmarks/architecture-synthetic.edn
bb bench repos check --suite benchmarks/agent-efficiency-broad.edn
bb bench:gate --setup-check
```

Cheap claim gate when current artifacts already exist:

```sh
bb bench:gate --check-only
```

Full deterministic gate when artifacts are stale or after a completed slice:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/<slice>/gate
```

Broad shell-only versus Yggdrasil agent comparison for query packet, prompt,
retrieval, or telemetry changes:

```sh
bb agent-efficiency all \
  --suite benchmarks/agent-efficiency-broad.edn \
  --out .dev/ygg/ripgrep-leverage/<slice>/agent-efficiency \
  --timeout-ms 600000
```

Optional headline comparison for published examples:

```sh
bb headline all \
  --suite benchmarks/headline.edn \
  --out .dev/ygg/ripgrep-leverage/<slice>/headline \
  --timeout-ms 600000
```

Minimum reporting fields for this workstream:

- deterministic gate status and artifact path;
- broad agent comparison status and artifact path when run;
- default `ygg query --json` packet token estimate before and after compacting;
- `--snippets`, `--evidence`, and `--full` packet token estimates when available;
- broad versus scoped search command/segment counts after telemetry split;
- `rg` query instrumentation after `rg --json` candidate seeding;
- localization, decision quality, evidence citation, patch success, task tokens,
  and runtime deltas by problem class.

Claim rule: count reductions in broad raw search are useful only when quality
does not regress. Scoped proof searches may stay flat or increase; do not report
that as a regression by itself.

## Ripgrep Slice 1: Process Boundary

Command run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-1/gate
```

Result: passed. This slice adds the internal ripgrep process boundary and should
not change query ranking or output shape.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 0.90 |
| MRR | 0.90 |
| Evidence citation | 1.00 |
| Noise@20 | 0.427 |
| Warning runs | 1 |
| Hint diagnostic runs | 5 |

Claim status: no user-facing speed or efficiency claim. This gate only supports
that the process-boundary slice did not break the deterministic benchmark gate.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-1/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/ripgrep-leverage/slice-1/gate/architecture-synthetic/agent-report.json`

## Ripgrep Slice 2: Internal Query Grep Lane

Command run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-2/gate
```

Result: passed. This slice adds internal `rg --json` literal evidence to
`ygg query` ranking. Matches are mapped back to active indexed paths and exposed
as compact `:grep` score components and `grep-*` instrumentation, not as default
shell actions.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 1.00 |
| MRR | 0.90 |
| Evidence citation | 1.00 |
| Noise@20 | 0.39 |
| Warning runs | 1 |
| Hint diagnostic runs | 5 |

Claim status: supports that the internal grep query lane did not regress the
deterministic architecture gate and improved recall@10 on this synthetic suite
versus the slice-1 gate. It does not support a broad speed, token, or
agent-efficiency claim.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-2/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/ripgrep-leverage/slice-2/gate/architecture-synthetic/agent-report.json`

## Ripgrep Slice 3: Query Input Surface

Command run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-3/gate
```

Result: passed. This slice adds the query input/options surface for task shape,
anchors, symbols, literals, lane overrides, output mode, and proof-command
opt-in. It records non-default input in compact packets but does not yet make
task profiles reweight ranking lanes.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 0.93 |
| MRR | 0.90 |
| Evidence citation | 1.00 |
| Noise@20 | 0.43 |
| Hint diagnostic runs | 5 |

Claim status: supports only that the input-surface slice passes the
deterministic architecture gate. It does not support a query-quality claim
versus slice 2 because recall@10 remains lower than the final slice-2 gate
(`1.00` -> `0.93`). Agent-efficiency and packet-token runs are still required
before making token-output claims.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-3/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/ripgrep-leverage/slice-3/gate/architecture-synthetic/agent-report.json`

## Ripgrep Slice 4: Compact Query Packet

Command run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-4/gate
```

Result: passed. This slice changes default `ygg query --json` output to compact
packet projection, with `--output full` preserving the rich context packet and
`--proof-commands` adding compact `:kind :grep` proof actions only on request.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 0.93 |
| MRR | 0.90 |
| Evidence citation | 1.00 |
| Noise@20 | 0.43 |
| Hint diagnostic runs | 5 |

Claim status: supports only that the compact packet slice passes the
deterministic architecture gate. It does not support a compact-output quality
claim versus slice 2 because recall@10 was lower than the final slice-2 gate
(`1.00` -> `0.93`). Agent-efficiency and packet-token runs are still required
before making token-output claims.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-4/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/ripgrep-leverage/slice-4/gate/architecture-synthetic/agent-report.json`

## Ripgrep Slice 5: Telemetry Split

Commands run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-5/gate

bb agent-efficiency all \
  --suite benchmarks/agent-efficiency-broad.edn \
  --out .dev/ygg/ripgrep-leverage/slice-5/agent-efficiency \
  --timeout-ms 600000
```

Deterministic gate result: passed.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 0.93 |
| MRR | 0.90 |
| Evidence citation | 1.00 |
| Noise@20 | 0.43 |
| Hint diagnostic runs | 5 |

Broad agent-efficiency result: diagnostic only. The run completed all 25 shared
cases, and the telemetry split correctly separates internal ripgrep work,
scoped/exact-file proof, broad rediscovery, and Ygg artifact projection. The
wrapper failed because the initial prompt-token gate failed.

| Metric | Shell-only | Ygg strict-warm | Direction |
| --- | ---: | ---: | --- |
| File recall@10 | 0.927 | 0.830 | regressed |
| MRR | 0.98 | 0.88 | regressed |
| Evidence citation | 1.00 | 1.00 | same |
| Path evidence citation | 0.614 | 0.987 | improved |
| Total agent tokens | 5,862,402 | 1,472,653 | improved |
| Input tokens | 5,690,555 | 1,384,089 | improved |
| Output tokens | 171,847 | 88,564 | improved |
| Command count | 173 | 29 | improved |
| Broad search commands | 73 | 0 | improved |
| Scoped search commands | 0 | 2 | observed |
| Exact-file search commands | 0 | 2 | observed |
| Internal ripgrep searches | 0 | 25 | observed |
| Internal ripgrep elapsed ms | 0 | 3,155 | observed |
| Internal ripgrep matches | 0 | 161,318 | observed |

Initial prompt-token gate:

| Metric | Shell-only | Ygg | Delta |
| --- | ---: | ---: | ---: |
| Shared cases | 25 | 25 | 0 |
| Prompt tokens | 63,875 | 93,833 | +29,958 |

Claim status: not supported for a broad efficiency claim. The run supports the
telemetry direction: broad rediscovery and full-run token use are now visible
separately from internal literal search and exact-file proof. It also shows the
next packet work clearly: shrink the first Ygg prompt, rerun the external agent
lane against the current maintained baseline, and protect direct candidates
before graph expansion.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-5/gate/architecture-synthetic/agent-check.json`,
`.dev/ygg/ripgrep-leverage/slice-5/gate/architecture-synthetic/agent-report.json`,
`.dev/ygg/ripgrep-leverage/slice-5/agent-efficiency/shell-only/agent-efficiency-broad/agent-report.json`,
`.dev/ygg/ripgrep-leverage/slice-5/agent-efficiency/ygg/agent-efficiency-broad/agent-report.json`,
`.dev/ygg/ripgrep-leverage/slice-5/agent-efficiency/prompt-token-measure.json`,
and
`.dev/ygg/ripgrep-leverage/slice-5/agent-efficiency/prompt-token-gate.json`.

## Ripgrep Slice 6: File Discovery

Command run:

```sh
bb bench:gate --out .dev/ygg/ripgrep-leverage/slice-6/gate
```

Deterministic gate result: passed.

| Metric | Value |
| --- | ---: |
| Completed cases | 5/5 |
| Runs | 5 |
| File recall@10 | 0.80 |
| MRR | 0.70 |
| Evidence citation | 1.00 |
| Noise@20 | 0.47 |
| Maintenance preflight | passed |

Post-fix bounded discovery check over the same gate worktrees:

| Case | Backend | `rg` ms | Paths | Supported files | Same canonical rows as git |
| --- | --- | ---: | ---: | ---: | --- |
| `axios-synthetic-native-proxy-boundary` | `ripgrep` | 54 | 441 | 441 | yes |
| `bootstrap-synthetic-astro-plugin-config` | `ripgrep` | 26 | 779 | 779 | yes |
| `bootstrap-synthetic-docs-route-impact` | `ripgrep` | 42 | 781 | 781 | yes |
| `dapper-synthetic-jsonb-test-stack` | `ripgrep` | 18 | 212 | 211 | yes |
| `supabase-postgres-synthetic-trigger-ownership-flow` | `ripgrep` | 18 | 456 | 456 | yes |

Implementation result: `rg --files --hidden` is the preferred discovery backend
when available, and fallback execution is lazy. Yggdrasil still applies final
ignored-path filtering, supported-file detection, and canonical file-row
construction.

Claim status: supports only the discovery backend boundary and canonical-row
equivalence for these five gate worktrees. It does not support a warm-agent
speed, command-count, token, or quality claim. The runtime-boundary localization
miss in the gate had expected files present in context but absent from the
compact result; that is packet-ranking work, not a file-discovery claim.

Source artifacts:
`.dev/ygg/ripgrep-leverage/slice-6/gate/architecture-synthetic/agent-check.json`
and
`.dev/ygg/ripgrep-leverage/slice-6/gate/architecture-synthetic/agent-report.json`.

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

`codebase-memory-mcp` was available on `PATH`, and the benchmark run used the
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
