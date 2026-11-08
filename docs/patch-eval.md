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
