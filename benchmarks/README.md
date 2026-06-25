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
bb bench repos check --suite benchmarks/graphify.edn
bb bench:gate --setup-check
```

Use the quick historical lane for routine shell-only versus Yggdrasil evidence:

```sh
bb agent-efficiency all \
  --suite benchmarks/historical-replay-quick.edn \
  --out .dev/ygg/agent-efficiency/historical-replay-quick
```

Use the full lane when the heavy multi-repo replay case should be part of the
claim:

```sh
bb agent-efficiency all \
  --suite benchmarks/historical-replay-full.edn \
  --out .dev/ygg/agent-efficiency/historical-replay-full
```

The deterministic gate runs the same preflight before doing benchmark work:

```sh
bb bench:gate
```

When current score artifacts already exist, use the cheaper claim check:

```sh
bb bench:gate --check-only
```

This still runs checkout preflight and strict `agent-check` thresholds, including
stale-artifact, maintained-graph claim-readiness, and per-case estimated context
packet token budgets, but skips baseline regeneration. If current artifacts were
created before deterministic baseline token estimates were recorded, run the full
gate once to refresh them before using check-only mode for token claims.

If a checkout still exists under the legacy `.dev/oss-test-cases/repos/` cache,
the preflight reports that path. Move or symlink it into
`.dev/ygg/benchmark-repos/`; do not commit the checkout or gate output.
