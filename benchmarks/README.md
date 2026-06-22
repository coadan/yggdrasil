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

Use `.dev/ygg/benchmark-repos/<repo-id>` as the common local checkout cache for
suite `:repos` roots. Use `.dev/ygg/bench/<suite-id>/` or an explicit
`.dev/ygg/...` `--out` path for run outputs. Promote an exploratory suite into
`benchmarks/` only when its case metadata, tags, and ground truth are curated
enough to review as source.

`benchmarks/repos.edn` is the tracked manifest for benchmark checkout inputs.
It records repo ids, clone URLs, expected cache dirs, and commits that tracked
suites need. Check local setup without creating generated artifacts:

```sh
bb bench:repos check --suite benchmarks/architecture-synthetic.edn
bb bench:gate --setup-check
```

The deterministic gate runs the same preflight before doing benchmark work:

```sh
bb bench:gate
```

If a checkout still exists under the legacy `.dev/oss-test-cases/repos/` cache,
the preflight reports that path. Move or symlink it into
`.dev/ygg/benchmark-repos/`; do not commit the checkout or gate output.
