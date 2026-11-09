# Synthetic hub-and-spoke suite

Measures multi-repository search where a knowledge hub (decision records with
stable codes, boundaries, components) sits beside code repositories (spokes).
No public project has explicit hub-to-spoke links, so the hub is generated.

- `hub-gen.py OUT.json`: for each history dev case in gin, hono and axios (and
  30 distractor commits per spoke), asks Claude Haiku for the decision record the
  team would have written before the change, from the commit message and diff.
  No file, path or internal names; an optional invariant becomes a boundary.
  Public repositories only. About $0.20 for 141 records.
- `hub-build.py OUT.json HUB_DIR SUITE.json`: writes a khub-style hub repository
  (`system/decisions`, `system/boundaries`, `system/components`, `manifest.yaml`)
  with shuffled `AD-n` and `BOUND-NNN` codes, and a suite with three query kinds
  per case: the symptom task, the task plus `See AD-n.`, and the decision text.
- `eval.py`: searches every member separately (case spoke at the commit's parent,
  other members at their base checkout), merges by reciprocal rank and reranks
  once. Arms: `cold` (fresh store), `seeded` (`git worktree add` on one task
  branch in every member, first search immediately), `refreshed` (after
  `ygg index`).
- `merge.py`: caches each member's top 20 from the cold arm and scores merge
  policies, including the round-robin interleave used by `ygg search --project`.
  Pass a configuration with a reranker to cache lists ranked by ygg's own
  per-repository reranker; their `interleave` variant is the shipped policy.

- `agent.py`: Claude agent trial on the same project. Each case gets one task
  branch with a worktree in every member, indexed as a prepare hook would, and
  the agent starts in the hub with the symptom task. Arms: per-repository
  `ygg search --root` and `ygg search --project demo`. Pass an absolute or
  relative `--config` with the embedding, reranker and a `demo` project.

Setup: `WORK/base/{hub,gin,hono,axios}` are Git checkouts (spokes cloned with
`--shared` from the eval clones; prefetch each case base with `git archive` in
the source clone when it is a partial clone), indexed once with the embedding
configuration passed as `--config`. The reranker is an HTTP `/v1/rerank`
endpoint. Results are in `docs/retrieval-quality.md`.
