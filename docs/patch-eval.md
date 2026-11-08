# Patch evaluation

Use patch evaluation to measure whether an agent fixes a task. Keep the
[file-discovery evaluation](agent-eval.md) to diagnose where agents search and
which files they choose.

Create a trusted suite outside the agent checkouts. Each case names a local
source repository, a fix commit and its first parent, a user-facing prompt,
and a standalone Python verifier. Use full Git commit IDs. Paths are relative
to the suite file or absolute.

```json
{
  "schema": "ygg.patch-eval/v1",
  "cases": [
    {
      "id": "example-fix",
      "repo": "public-source",
      "base": "<full pre-fix commit>",
      "reference": "<full fix commit>",
      "prompt": "Describe the observable bug and the expected behavior.",
      "verifier": "checks/example-fix.py",
      "timeoutSeconds": 120
    }
  ]
}
```

The verifier receives the fresh source directory as its first argument and
runs there. It prints one JSON object on stdout. A nonzero process exit,
invalid result or timeout is reported separately from a behavioral failure.
Use a narrow behavioral check and emit `fail` only when it observes the bug.
The grader scores a case when the base fails that check and the reference
patch passes it. Keep the verifier independent of files the patch can replace.

```json
{"schema":"ygg.patch-verification/v1","status":"pass"}
```

Run the controls before collecting patches. Prepare a separate checkout for
each arm and give the agent the prompt and that checkout. Preparation exports
the pre-fix tree with one fresh Git commit and no remotes or reference history.
Use an isolated execution environment for agents or patches you do not trust.
The grader itself runs the trusted verifier under the caller's permissions.

```sh
python3 -B tools/agent-eval/patch_eval.py --suite suite.json score --output controls.json
python3 -B tools/agent-eval/patch_eval.py --suite suite.json prepare \
  --case example-fix --output /tmp/example-baseline
```

Collect each arm's patches in a separate directory. Use `git add -A` followed
by `git diff --cached --binary HEAD` in the prepared checkout so the patch
includes new files. Save it as `example-fix.patch` under that arm's directory.

```sh
python3 -B tools/agent-eval/patch_eval.py --suite suite.json score \
  --arm baseline=patches/base --arm ygg=patches/ygg --output outcomes.json
```

Every check gets a fresh copy of the pre-fix tree. The report records the
controls, patch hashes, verdicts and elapsed check time, with paired wins and
losses across calibrated cases. Missing and invalid submissions count as
misses. An invalid control excludes its case and exits with status 1. Use a
new output file for each report. Keep model, tools and budgets matched when
collecting patches, and report agent time and cost from the collection runs.

The grader uses Git and Python with tarfile extraction filters, available in
Python 3.12 and later.

## Local paired pilot

The November 8, 2026 pilot used six pre-fix cases across Yggdrasil and Axios.
Both arms used Codex CLI 0.167.0 with the local Ollama model
`qwen3.6:27b`, the same task and shell tools, and a 240-second limit.
Each run began in a fresh snapshot. The augmented arm also received a pinned
lexical Ygg binary (`12b5c1a3a389`) and optional guidance to use compact
five-result searches. The control was isolated from the installed Ygg
binaries. Agent patches were collected at the cap even if the session had
yet to finish its response.

All six original versions failed their behavioral controls and all six
reference fixes passed. The grader evaluated all twelve submissions.

| Arm | Passing patches | Runs that reached the cap | Runs that used Ygg |
| --- | ---: | ---: | ---: |
| Control | 1/6 | 6/6 | 0/6 |
| Ygg available | 2/6 | 5/6 | 1/6 |

Both arms fixed Axios header matcher binding. The Ygg arm alone fixed the
string interceptor ID case. Its search for `interceptor` showed the
implementation in overflow, and the control found the same file with
`rg`. Both agents opened it on their third shell command. The other four
cases had empty submissions in both arms.

This pilot measures optional Ygg availability in one local setup.
Only one augmented run used Ygg, so the single paired win cannot isolate
the contribution of retrieval. Eleven runs reached the cap and only one
reported terminal token usage, leaving token efficiency unmeasured.
The pinned binary uses lexical retrieval, so these runs give no estimate
for embeddings or reranking.

The local evidence is in
`.workbench/tasks/agent-patch-eval/evidence/paired-local-pilot/`.
`full.score.json` contains the controls and outcomes, `runs.jsonl`
contains terminal run status, and `manifest.json` pins the suite, runner,
model, and binary.

## Matched opening search

The 240-second pilot reached the cap in 11 of 12 sessions. A separate
control-only calibration used the same six tasks with a 900-second cap and
required four sessions to finish before starting the fresh comparison.
Four sessions finished and three of the six patches passed. The grader
evaluated both patches collected at the cap, including one empty patch.

The fresh comparison used four different public fixes, two each from
Axios and Flask. Every base failed its behavioral check and every reference
fix passed. Each case ran once per arm in an isolated snapshot with Codex
CLI 0.167.0, local Ollama `qwen3.6:27b`, and the same 900-second cap.
The order alternated by case. Only the opening results shown in the task
prompt differed. After that, both agents had the same shell tools.

Both openers used the same query written from the task description and a
budget of five cards with at most 320 characters per excerpt. The control
used case-insensitive literal ripgrep matches, ranking files by coverage of
distinct query terms weighted by inverse file frequency. Ygg used its
lexical CLI search with an empty user configuration. Both arms showed the
path, line range, and excerpt in the same format. The ranking comparison
covers the cards the agent could see. The experiment scores primary
implementation paths, overlap with the reference change's lines, and
first successful source reads separately.

Each opener showed the primary implementation path on one of four cases.
The shared hit was Flask's route preflight override, at rank 4 for ripgrep
and rank 2 for Ygg. Neither excerpt overlapped the lines changed by the
reference fix. The closest shown source line was 60 lines from the change
for ripgrep and 498 lines for Ygg. The first Ygg card for the Axios URL
boundary case was a sibling test. The agent opened that test on its first
command and the implementation on its second. The control read the
implementation on its fourth command. Both submitted the same patch.
Both telemetry openers named the implementation in an excerpt, through
the ripgrep threat-model card and a Ygg test import. Neither returned the
implementation as a card.

| Case | Ripgrep first read | Ygg first read | Ripgrep agent time | Ygg agent time | Passing patches |
| --- | ---: | ---: | ---: | ---: | --- |
| Axios URL boundary | 4 | 2 | 334 s | 254 s | Both |
| Axios telemetry | 1 | 3 | 408 s | 342 s | Both |
| Flask bracketed bind address | 7 | 6 | 900 s (cap) | 635 s | Both |
| Flask preflight override | 4 | 3 | 444 s | 769 s | Both |

First read is the ordinal of a successful shell command that printed
source from the primary implementation file. Agent time excludes the
precomputed search. Median agent times were 426 seconds for the ripgrep
arm and 489 seconds for Ygg. The cap left one control run without terminal
token usage. Its captured patch still passed.

The agents read the implementation within seven commands in every
session, including runs where the opener missed it. Ygg reached the first
read in fewer commands in three of four pairs. The sibling test is the
clearest observed route from an opener to the implementation. All four
pairs tied on correctness. With one run per arm per case and every patch
passing, the trial leaves the effects on patch success and cost unresolved.
The next comparison needs fresh cases where source discovery can affect
the outcome, plus repeated runs with the same matched budgets.

The local evidence is in
`.workbench/tasks/opening-search-eval/evidence/`.
`calibration-900/score.json` records the budget check.
`opening-results/openings.json` and `opening-results/line-overlap.json`
record the displayed cards and their distance from changed lines.
`paired-900/score.json`, `runs.jsonl`, `discovery.jsonl`, and
`manifest.json` record the behavioral results, run status, discovery
traces, and frozen inputs.
