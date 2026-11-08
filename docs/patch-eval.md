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
