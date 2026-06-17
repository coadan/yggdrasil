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

## Workflow

Create a benchmark suite EDN file:

```clojure
{:id "oss-issue-replay"
 :project-id "oss-issue-replay"
 :repos [{:id "penpot"
          :root ".dev/oss-test-cases/repos/penpot"}]
 :cases [{:id "penpot-example"
          :repo-id "penpot"
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
bb bench agent-score benchmark.edn --case penpot-example --result agent-result.json
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
- `bench agent-score <suite.edn> --case <case-id> --result result.json` scores
  one agent result JSON against hidden ground truth.
- `bench run <suite.edn>` creates a detached worktree at each base SHA, indexes
  it with the query profile, runs lexical retrieval over the issue text, and
  writes one scored result artifact per case.
- `bench report <suite.edn>` aggregates case result scores.
- `bench show <suite.edn> --case <case-id>` prints one case result.

Use `--case <case-id>` with benchmark commands to narrow the command to one
case.

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
  "schema": "agraph.benchmark.agent-result/v1",
  "caseId": "penpot-example",
  "agentId": "codex",
  "mode": "agraph",
  "suspectedFiles": [
    {
      "path": "repo-relative/path.ext",
      "rank": 1,
      "confidence": 0.84,
      "reason": "Short evidence-based reason."
    }
  ],
  "suspectedSymbols": [],
  "commands": [],
  "summary": "Brief rationale."
}
```

The scorer only uses `suspectedFiles.path` and rank for localization metrics.
Reasons, commands, and symbols are still part of the artifact because they make
failures auditable.

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

## Scores

The core scores are mechanical:

- `fileRecallAt5`, `fileRecallAt10`, `fileRecallAt20`: fraction of changed files
  appearing in the top ranked file results.
- `meanReciprocalRankFile`: reciprocal rank of the first changed file found.
- `noiseRatioAt20`: fraction of top ranked files outside the fixing diff.
- `unsupportedGroundTruthFiles`: changed files that were missing or unsupported
  in the base tree.

Each result also records `groundTruthRanks.files`, which lists every changed
file and the rank where AGraph found it, or `found? false` when it was outside
the collected result window. Use that before tuning ranking; aggregate recall
alone does not show whether a miss is close or completely absent.

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
