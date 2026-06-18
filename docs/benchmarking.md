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
bb bench agent-check benchmark.edn --mode agraph --agent agraph-baseline-lexical --min-cases 4 --min-runs 4 --min-file-recall-at-10 1.0 --min-case-file-recall-at-10 1.0 --min-mrr 1.0 --min-case-mrr 1.0 --max-noise-at-20 0.5 --max-case-noise-at-20 0.75
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
  a ranked suspected-file list of ten files and still writes the full
  context packet; use `--limit <n>` to change the suspected-file shortlist size,
  `--doc-limit <n>` to change the snippet-bearing source context size, and
  `--retrieval-limit <n>` to widen the compact candidate-file pool without
  adding more snippets.
  Use `--retriever local-vector` to run an optional local semantic-vector
  control lane instead of the graph/context packet. The default worker is
  `python3 scripts/local-vector-baseline.py`, which uses
  `sentence-transformers` locally and writes the same agent-result contract for
  the existing scorer. Install the optional worker dependencies in a local
  environment with `python3 -m venv .dev/agraph/local-vector-venv &&
  .dev/agraph/local-vector-venv/bin/python -m pip install -r
  scripts/local-vector-requirements.txt`, then pass
  `--vector-command '.dev/agraph/local-vector-venv/bin/python
  scripts/local-vector-baseline.py'`. Use `--vector-model <model>` to choose a
  local model and `--vector-command <cmd>` to replace the worker. The command
  receives `REQUEST_JSON RESULT_JSON MODEL` arguments. This lane is for
  benchmark diagnosis: if it beats the graph baseline, inspect whether AGraph is
  missing extractor facts, ranking useful facts poorly, or losing on vocabulary
  mismatch. It does not make AGraph core depend on a vector provider.
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
  is writable. Use `--timeout-ms <n>` to bound long-running agents.
- `bench agent-score <suite.edn> --case <case-id> --result result.json` scores
  one agent result JSON against hidden ground truth.
- `bench agent-report <suite.edn>` aggregates existing agent score artifacts
  across selected cases. Use `--mode agraph` or `--mode shell-only` to compare
  one benchmark mode at a time, and `--agent <agent-id>` to target one
  repeatable agent run.
- `bench agent-check <suite.edn>` aggregates agent score artifacts, writes an
  `agent-check.json`, and exits non-zero when selected cases are missing or
  thresholds fail. Useful gates include `--min-cases`, `--min-runs`,
  `--min-file-recall-at-5`, `--min-file-recall-at-10`,
  `--min-file-recall-at-20`, `--min-case-file-recall-at-5`,
  `--min-case-file-recall-at-10`, `--min-case-file-recall-at-20`, `--min-mrr`,
  `--min-case-mrr`, `--max-noise-at-20`, `--max-case-noise-at-20`,
  `--max-input-hinted-cases`, and `--max-unsupported-ground-truth-files`. Use
  `--agent <agent-id>` to avoid mixing baseline, shell-only, and ad hoc agent
  score artifacts in one gate. Selected cases must all have matching score
  artifacts unless `--allow-missing` is set. Matching score artifacts must be
  unique per case, agent, and mode unless `--allow-duplicate-runs` is set. The
  check artifact includes `caseDiagnostics` so CI failures can be triaged
  without opening every score artifact.
- `bench run <suite.edn>` creates a detached worktree at each base SHA, indexes
  it with the query profile, runs lexical retrieval over the issue text, and
  writes one scored result artifact per case.
- `bench report <suite.edn>` aggregates case result scores.
- `bench show <suite.edn> --case <case-id>` prints one case result.

Use `--case <case-id>` with benchmark commands to narrow the command to one
case.

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

When a benchmark miss points at extractor noise or missing syntax facts, prefer
testing a parser-backed extractor behind the parser-worker contract before
adding more regex matching. See `docs/parser-workers.md`. Parser workers must
emit concrete syntax facts only; benchmark the effect on recall, MRR, noise@20,
edge count, and index time before making a worker-backed extractor the default.
Index summaries include `:stats :timings-ms` / `stats.timings-ms` with phase
timings such as `scan-ms`, `parser-worker-ms`, `extract-ms`,
`commit-files-ms`, `dependency-ms`, and `total-ms`; use those fields to separate
parser cost from XTDB writes, search-doc construction, and derived edge refresh.

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
coverage.

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
- `changedFiles`: files changed by the fixing diff.
- `localizationFiles`: localization target files, or changed files when no
  explicit localization set was provided.
- `scoreableChangedFiles`: localization target files that were present and
  supported in the base checkout.
- `unsupportedGroundTruthFiles`: changed files that were missing or unsupported
  in the base tree.

Each result also records `groundTruthRanks.files`, which lists every scoreable
localization file and the rank where AGraph found it, or `found? false` when it
was outside the collected result window. Use that before tuning ranking;
aggregate recall alone does not show whether a miss is close or completely
absent.

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
