# Agent evaluation

The history suites in `docs/retrieval-quality.md` score one terse query per
commit. Agents work differently: they read a task, search several times,
refine their queries from what they find, and read files to check. This
evaluation measures ygg in that loop.

## Method

1. `tools/agent-eval/tasks.py` writes an issue-style task for each history
   case. A model reads the commit message and diff and writes the issue the
   commit resolved, with no file names, paths, test names or internal
   identifiers. Every task is checked mechanically for the case's expected
   paths and their file names, and a task that leaks is retried once and
   otherwise marked. Tasks are cached in `<suite>/agent-tasks-<split>.json`.
2. `tools/agent-eval/run.py` runs one headless `claude -p` session per case
   and arm. The repository clone is the working directory, and the session has
   no settings, MCP servers or slash commands. Arms:
   - `native`: Read, Grep, Glob.
   - `ygg`: Read, plus Bash limited to `ygg search`. A wrapper on `PATH`
     refuses other subcommands and `--root`, and pins the search root to the
     repository. A short appended system prompt describes the output.
   - `both`: the union.
   - `shell` and `shell-ygg`: Bash only, with and without `ygg search`. See
     "Harnesses, models and budgets".
   - `codex` and `codex-ygg`: `codex exec`, without and with ygg guidance.

   The agent answers with the files it would change, most important first,
   through a JSON schema. Each run is bounded by wall-clock time, a USD cap,
   and a tool-call budget stated in the prompt. Claude Code refuses tool use
   outside the working directory.
3. `results.json` holds one row per run:
   - hit, recall and precision against the commit's files;
   - the tool call at which an expected path first appeared in a tool result;
   - tool calls by kind, turns, tokens, cost and time.

   The full stream-json trajectory is kept per run, and completed runs are
   skipped on rerun.
4. `tools/agent-eval/replay.py` replays each Grep call from the native arm
   through `ygg search -E` in lexical mode on the same index. It records, per
   search round, whether the agent's own result named an expected path and
   where ygg ranks one. Grep's path and glob filters are not replayed.
5. `tools/agent-eval/report.py` compares arms on the cases they share:
   answer quality, paired differences, hits per repository, and how often a
   search call's result named an expected file at each search round.
   `tools/agent-eval/overlap.py` splits the same results by how much of the
   task's vocabulary the expected files contain.

The expected files are those the commit touched, including tests and docs, so
recall is a strict measure. Hit (at least one expected file named) is the main
signal. Only public repositories are used, because task generation and agent
runs send code to the model.

## Pilot

Public dev, 12 cases round-robin over the 9 repositories, Claude Haiku 5.5,
a 20 tool-call budget. The ygg arm used main's binary, lexical with reranking,
because embeddings were being rebuilt for another experiment at the time.

| Arm | Hit | Recall | Precision | Expected file ranked first | Tool calls | Input tokens per run | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- |
| native | 12/12 | 0.931 | 0.567 | 9/12 | 5.4 | 58k | $0.042 |
| ygg | 12/12 | 0.972 | 0.513 | 11/12 | 6.9 | 73k | $0.041 |

- Both arms found an expected file in every case, usually in the first search
  call. Tasks written from a full commit carry enough vocabulary that hit
  rate cannot separate the arms. A useful eval needs harder tasks: symptom-only
  wording, larger repositories, or a tighter tool budget.
- ygg put the right file first more often but used about a quarter more input
  tokens and more calls. Its JSON output, with excerpts, costs more per
  search than Grep's file list.
- The first pilot ran without the guard hook. The ygg arm then ran `grep` and
  `sed` through Bash, which Claude Code allows as read-only commands. The guard
  now blocks every Bash command that is not a single `ygg search`.
- Replaying the native arm's 37 Grep calls through ygg: in the first round
  the agent's own grep named an expected file 9 of 11 times and ygg 8 of 11.
  From the fourth search on it was 3 of 13 against 7 of 13, because the late
  greps were narrow patterns that matched nothing useful and ygg's ranking
  still recovered a relevant file. This sample is too small to act on.

## Symptom tasks

`tasks.py --style symptom` writes one to three sentences in a user's words,
with no names from the code, and retries when a task names an expected file's
stem. The 24 pilot-sampled public dev cases averaged 61 words against 111 for
issue tasks, and 3 still named a file and were skipped. The prompt allowed 8
tool calls. The ygg arm was set up as in the pilot.

| Arm | Hit | Recall | Precision | Expected file ranked first | Tool calls | Input tokens per run | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- |
| native | 18/21 | 0.817 | 0.479 | 16/21 | 5.1 | 53k | $0.064 |
| ygg | 18/21 | 0.802 | 0.475 | 16/21 | 4.8 | 57k | $0.069 |

- The arms still tie on final answers. Only three cases differ: ygg found a
  hono case that native missed after 16 calls, native found a Dapper release
  case that ygg missed, and ygg named fewer of a Dapper case's files.
- Per search call, ygg surfaced an expected file more often at every round.
  The figures below count calls whose result named an expected file.

  | Search round | ygg arm's own `ygg search` | native arm's own Grep | native Grep replayed through ygg |
  | --- | --- | --- | --- |
  | 1 | 16/21 | 14/20 | 13/20 |
  | 2 | 12/21 | 5/14 | 7/14 |
  | 3 | 10/16 | 4/12 | 11/12 |
  | 4+ | 10/19 | 7/20 | 10/20 |

  The later rounds are where ygg helps: an agent's follow-up queries are
  narrow patterns that grep matches literally or not at all, and ranking
  still returns a relevant file. The agent's own judgment after reading files
  limits the final answer in both arms, so better search results did not
  change the outcome at this sample size.
- How the agent searched with ygg: 40 of 75 calls used `-F` and 24 used
  `--about`. The first query was often a long natural-language phrase, and
  later queries were exact identifiers the agent had seen in earlier results.
  `--limit` was set every time.

## Full public dev, symptom tasks

All 146 non-leaking public dev cases, Haiku 5.5, an 8-call budget, and the ygg
arm set up as in the pilot (lexical with reranking). This table is the first
pass in `symptom-dev`, where one run per arm ended in an error and counted as
a miss. The table under "Harnesses, models and budgets" reran those two runs
in the same directory, so each arm there has one more hit.

| Arm | Hit | Recall | Precision | Expected file ranked first | Tool calls | Input tokens per run | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- |
| native | 111/146 | 0.683 | 0.348 | 90 | 4.3 | 43.6k | $0.38 |
| ygg | 116/146 | 0.715 | 0.367 | 89 | 3.8 | 46.5k | $0.39 |

- ygg alone hit 11 cases and native alone 6. A two-sided sign test gives
  p = 0.33, so the hit difference is suggestive, not significant. ygg used
  fewer tool calls for about the same cost.
- Search calls whose result named an expected file, by round:
  native 68%, 60%, 35%, 35%; ygg 76%, 59%, 66%, 59% (rounds 1, 2, 3, 4+).
  Replaying the native arm's 414 Grep calls through ygg: 91 → 86 in round 1,
  49 → 58 in round 2, 32 → 67 in round 3 and 37 → 65 from round 4.
- What agents try with ygg. Of the ygg arm's refused calls, 34 chained several
  `ygg search` commands with `&&` or added `2>/dev/null`. Two queries began
  with a dash and were parsed as flags. Batched queries and tolerance for
  leading-dash queries are candidate interface changes.

## Harnesses, models and budgets

Two more Claude arms stand in for harnesses without Claude Code's tuned search
tools. `shell` has only Bash, held by `guard.py` to read-only grep, find, ls,
cat, sed -n and similar commands inside the repository. `shell-ygg` adds
`ygg search`. The arms run under bash, so glob arguments such as
`--include=*.py` reach grep as they would in a typical harness.

Codex arms run `codex exec` with `gpt-6.1-sol` at medium reasoning effort, the
workspace-write sandbox and network access for the reranker. A clean
`CODEX_HOME` links only the user's login, so no skills, plugins or global
AGENTS.md load. An empty zsh profile keeps the ygg wrapper first on `PATH`,
because macOS `path_helper` otherwise puts `/opt/homebrew/bin`, and an older
installed ygg that rejects the current config, ahead of it. `codex-ygg` adds
AGENTS.md-style guidance to the prompt that recommends `ygg search` and keeps
`rg` for exact text. Codex sandboxes do not stop reads outside the repository.
No run changed a repository.

Final answers that name an expected file. The 48-case sample is the
round-robin one used for every sample row.

| Condition | Cases | native | shell | ygg | shell-ygg |
| --- | ---: | ---: | ---: | ---: | ---: |
| Haiku 5.5, 8 calls | 146 | 112 | 112 | 117 | 117 |
| Haiku 5.5, 8 calls | 48 | 35 | 36 | 38 | 38 |
| Haiku 5.5, 3 calls | 48 | 34 of 47 | 36 | 38 | 38 |
| Haiku 4.5, 8 calls | 48 | 29 | 34 | 34 | 30 |
| Codex, 8 calls | 48 | 38 (codex) | | 38 (codex-ygg) | |

- With Haiku 5.5, ygg adds about five hits in 146 in both harness types, and
  shell-ygg has the best recall (0.737 against 0.656 for shell) with the
  fewest calls (3.3). Cutting the budget to three calls barely moved any arm.
- Haiku 4.5 ignored the call budget, averaging about ten calls, at about
  eight times Haiku 5.5's cost. Seven runs stopped at the $0.15 cap. Its
  results are mixed: ygg beat native by five hits but shell-ygg trailed shell
  by four. Per search call, ygg named an expected file more often at every
  round: 60%, 53%, 53% and 38% against native's 50%, 40%, 36% and 37%.
- Codex used ygg in 44 of 48 runs, usually once before switching to `rg`. Its
  answers matched the plain Codex arm case for case, at one more call and 8%
  more input tokens. Codex usually finds the answer with its first `rg`.

## Low-overlap tasks

`tools/agent-eval/overlap.py` scores each case by the share of the task's
content words that occur in its expected files, then splits cases into
terciles. In the lowest third (overlap at most 0.38), an exact text search has
the least to match. The keyword-only ygg arm did worst there, so its ygg arm
was rerun with hybrid search: the user's MiniLM and reranker configuration, on
a copy of the eval store with full vector coverage.

| Arm, 48 low-overlap cases | Hit | Recall | First search names an expected file | Input tokens per run |
| --- | ---: | ---: | ---: | ---: |
| native | 38 | 0.756 | 28 | 40.4k |
| shell | 33 | 0.648 | 32 | 34.4k |
| ygg, lexical | 35 | 0.694 | 31 | 45.8k |
| shell-ygg, lexical | 37 | 0.760 | 27 | 34.8k |
| ygg, hybrid | 38 | 0.760 | 34 | 46.7k |

Hybrid search recovered the keyword-only arm's loss on low-overlap tasks and
had the best first search, but it only tied Claude Code's own tools on final
answers. Against plain shell search it found five more.

## Conclusions so far

- The strong agents measured here, Codex and Haiku 5.5 with Claude Code's
  tools, find the right file about as well without ygg. ygg's measurable gains
  are over plain shell search, in later search rounds, and per search call.
- ygg's results cost more tokens per call than grep's file lists, and agents
  repeatedly tried to batch several ygg queries in one command. A compact
  output and multi-query calls are the clearest interface changes to test
  next.
- These runs used the keyword-only ygg configuration except where marked
  hybrid. The hybrid result suggests rerunning the full comparison with
  embeddings.

## Agent-facing tuning

These variants change only what an agent sees or can run. The setup is the
ygg arm, Haiku 5.5, all 146 symptom tasks and an 8-call prompt budget, with
hybrid search (the user's MiniLM and HTTP reranker configuration on a
full-coverage copy of the eval store). Every condition has its own output
directory, and all are compared on the same cases.

Changes to the harness, all kept:

- **Chaining.** The ygg-arm guard now allows what real harnesses allow:
  - several `ygg search` commands chained with `&&`, `;` or `||`, separated
    by `echo` if wanted;
  - `2>/dev/null`;
  - pipes into output-only filters: head, tail, wc, sort, uniq, cut, and grep
    with a pattern and no file;
  - backticks inside single quotes.

  File reads through Bash stay refused. In the full keyword-only run, 34 of
  the ygg arm's 51 refused calls were chained searches.
- **Raw results.** Runs from round 2 on keep each executed search's raw JSON
  in `<case>.<arm>.ygg-raw.jsonl` before any formatting. That file also
  counts searches inside chained commands.
- **Output and guidance options.** `run.py --ygg-format` reshapes results in
  the eval wrapper only, through `format.py`, before any change to the CLI
  contract:
  - `json` is unchanged;
  - `compact` prints `path:start-end` with the excerpt indented;
  - `files` prints paths and line ranges only.

  `--guide-extra` appends text to the agent's guidance.

Changes to the CLI, both kept and covered by tests:

- **Multiword dash queries.** A dash-led argument whose would-be flag name
  contains whitespace, such as `"--force graph overwrite"`, is the query.
  Agents wrote these for tasks about command-line flags.
- **`--about` without a pattern.** `ygg search --about "<description>"` with no
  pattern searches the description's words and meaning. Agents wrote it 14
  times in the round-1 compact run and 36 times in the files run, and each
  failed with `search query is required`. A regular expression still needs a
  pattern.

Round 1 used main's binary and round 2 the binary with both CLI changes. The
two compact runs in round 2 are identical conditions, so their gap of 3 hits
shows the run-to-run noise.

| Run | Hit | Recall | Expected file first | Input tokens per run | Tool calls | Searches per run | Failed searches | Cost |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1: json | 118 | 0.739 | 89 | 51.2k | 3.55 | | | $0.464 |
| 1: compact | 123 | 0.769 | 92 | 50.5k | 3.75 | | | $0.453 |
| 1: files | 119 | 0.736 | 88 | 54.0k | 5.37 | | | $0.462 |
| 2: json | 121 | 0.758 | 86 | 49.7k | 3.41 | 3.09 | 6 | $0.439 |
| 2: compact | 123 | 0.774 | 93 | 47.6k | 3.64 | 3.31 | 5 | $0.394 |
| 2: compact, repeat | 120 | 0.768 | 89 | 46.4k | 3.52 | 3.27 | 5 | $0.387 |
| 2: compact with tips | 121 | 0.773 | 92 | 50.7k | 3.00 | 3.88 | 7 | $0.452 |

- **Compact output.** Hits are within noise, with paired differences of
  7 / 5 and 3 / 4 against round-2 json. Recall was higher in all three
  compact runs, and cost was lower in both rounds: 2% in round 1 and about
  11% in round 2 on the same binary. The gain is modest but consistent. It is
  a candidate for a text output option in the CLI. It was not adopted here,
  because the JSON envelope is the CLI's contract.
- **Files-only output.** Rejected. Agents read about two files per run to see
  what the excerpts would have shown, used more calls and tokens, and found
  no more files.
- **Search tips.** Rejected. Tips appended to the guidance (start with a
  description, use `-F` for seen identifiers, chain with `&&`, read the top
  files) cut tool calls to 3.0 but raised searches per run to 3.9 and cost to
  match json, with no gain in hits.
- **Remaining failures** are about 1% of searches. They are grep and rg flags
  ygg does not have (`-i`, `--no-ignore`, `-uuu`, `--pretty`, `--stats`), one
  command that passed both an `-F` and an `-E` pattern, and one invalid
  regular expression.
- The round-2 json arm (121) is not comparable with the earlier keyword-only
  ygg arm (117), because the guard, the binary and the search mode all
  changed between them.

After these wrapper trials, `ygg search --format compact` made the citation
layout available in the CLI. The default JSON envelope still serves structured
consumers. Compact output formats the same ranked result after retrieval.

The Bash guard checks supported options and resolves file operands and glob
matches inside the checkout. The Ygg arm accepts stdin filters without file
operands. The shell arms accept supported read-only commands and local paths.
Use explicit command separators and quote literal shell characters. The hook
works alongside the harness's process permissions.

An offline check on the saved public trajectories retained 2,905 of 2,907
previously admitted Bash calls. The two refusals involved variable expansion
and a recursive glob. Regression fixtures cover file-reading and writing
options, shell expansion, and paths that resolve outside the checkout.

## Final setup comparison

The final comparison ran ygg in its current setup:
- hybrid search, with MiniLM embeddings and the Qwen3 0.6B reranker;
- `--format compact`;
- the fixed CLI (`--about` alone as the query, and multiword dash arguments).

Each pair used the same 146 public dev symptom tasks with a 20-call prompt
budget. The augmented arms shared a pinned Ygg binary (`df1315b9059f`) and
store. The ordinary Codex arm inherited the host PATH. The augmented Codex
arm received Ygg guidance and the pinned wrapper. Both pairs pass
`report.py --require-complete`. "Labelled" leaves out the bookkeeping-only
cases described below.

| Arm | Hits | Labelled hits | Files found | Recall | Precision | Calls |
|---|---|---|---|---|---|---|
| Haiku 5.5, shell | 117 | 113/130 | 170 | 0.780 | 0.437 | 4.5 |
| Haiku 5.5, shell with ygg | 116 | 111/130 | 166 | 0.763 | 0.405 | 4.5 |
| Codex | 117 | 113/130 | 181 | 0.818 | 0.621 | 3.6 |
| Codex with ygg | 116 | 111/130 | 178 | 0.796 | 0.626 | 4.7 |

- Both augmented arms finished one hit behind their paired controls.
- Codex with Ygg gained 2 cases and lost 3. Of its 197 successful Ygg
  searches, 153 were hybrid, 147 applied reranking, and 44 were lexical.
  One additional search returned an error.
- **Haiku with shell and ygg** used ygg in 40 of 146 runs.
- **The earlier gap did not replicate.** Shell with ygg led shell 117 to 112
  in the earlier symptom run. In this run shell reached 117. The guard version
  changed between the two runs, which also had ordinary run-to-run noise. The
  held-out split was not run, because the dev result was a clear tie.

## Misses

Every expected file missed on dev was sorted into one of three buckets.

1. **Bookkeeping-only cases.** In 16 of 146 cases, the commit changed only a
   changelog or lock file, but the task describes the feature it records. No
   reasonable answer names that file. `run.py` flags these rows as
   `bookkeepingOnly` and reports hits with and without them. The pattern is a
   file-name list in the eval tooling, not in core. Without these cases, the
   earlier ygg-vs-native lead shrinks from 117 vs 112 to 110 vs 108.
2. **Shown but not named.** The search showed the file, but the agent left it
   out of its answer.
3. **Never shown.** The file never appeared in the agent's tool output.

Labelled misses on the final runs:

| Arm | Shown, not named | Never shown |
|---|---|---|
| shell | 21 | 32 |
| shell with ygg | 31 | 26 |
| codex | 32 | 10 |
| codex with ygg | 36 | 9 |

### Never shown

A replay reran each agent's own ygg queries at depth 60 against a store
snapshot, and also ran the task text itself as a query.
- None of the files the ygg arms never showed rank for the agent's own
  queries.
- The task text as a query brings two into the top 10: a flask test and a
  graphify changelog. Three more rank between 23 and 37.
- The rest cannot be found at the search revision.
  - **Stale labels.** Cases are searched at the repository's current HEAD,
    and the content the old commit changed is gone. Dapper's
    `AssemblyInfo.cs` no longer holds `AssemblyVersion`, and the postgres
    Ansible version bumps are similar.
  - **Side files.** Files the commit touched that the task does not describe,
    such as ripgrep `build.rs` and graphify `cli.py`.

An earlier "start with a description" guide changed how agents searched but
did not add hits. A sharper version could recover at most two files here.

### Shown but not named

This bucket is about how many files the agent names.
- Codex names about three files per answer.
- In 59 multi-file cases it names some expected file in 56 or 57, but all of
  them in only 40 or 41.
- Missed source files are mostly partners in multi-file changes, such as a
  second Terraform file or Dapper's `SqlMapper` partial classes. The rest are
  tests, docs and changelogs.

`run.py --prompt-extra` appends text to every arm's prompt. A line asking for
the tests, documentation, changelog and dependency files a change also
touches was tried on 51 labelled cases with a miss, against a baseline on the
same cases.

| Arm | Files found (of 111) | Precision | Answer length | Hits |
|---|---|---|---|---|
| native | 61 → 66 | 0.338 → 0.226 | 4.1 → 6.1 | 32 → 32 |
| ygg | 69 → 74 | 0.310 → 0.233 | 4.6 → 6.7 | 35 → 34 |

The line was rejected. Agents named more of the side files by listing more
files overall, not by choosing better.

### Extractors

None of the eval indexes had plugin records, because no suite configures
extractors. ygg's import graph lane was therefore never measured in an agent
eval.

A replay measured the extractors afterwards, on shared clones at the eval
HEADs. It reran all 886 saved ygg queries in auto mode without embeddings or
rerank, first with no plugins and then with the six bundled extractors.

- **Overall:** of 802 labelled queries, 495 surfaced an expected file in the
  top 10 without extractors and 502 with them.
- **Resolved edges:** most import edges did not resolve to files. Only
  relative paths resolved, so the graph lane worked in hono (1,169 of 1,428
  edges) and axios (372 of 750), where it added about two top-10 hits each.
- **Python resolution:** Python imports now resolve by Python's own rule. A
  module path runs from the nearest directory without `__init__.py`. Relative
  imports climb only within the package chain, and absolute imports resolve
  only to a unique file. A module that exists both as `name.py` and as
  `name/__init__.py` is left unresolved. This raised graphify from 4 to 895 resolved edges and flask
  from 0 to 242. On the same queries:
  - 19 top-10 lists changed;
  - four expected files were gained and one lost (a net gain of one query);
  - flask `tests/test_json_tag.py`, one of the never-shown files, now
    appears.
- **Go:** Go imports use module paths and still do not resolve.

### Citation overlap

File rank says whether ygg named the right file, not whether its cited lines
reached the fix. `tools/agent-eval/citations.py` measures that separately
from the saved raw ygg results, so it needs no model runs.

For each citation of an expected path, it maps the fix commit's new-side
hunks onto the HEAD blob the agent searched, through `git diff -U0 <fix>
<head>`. If any fixed line changed after the fix, the region is unmappable
and counted apart. Citations certain to overlap (new files, or windows
covering the file) are also counted apart. The baseline is the chance that a
window of the same length, placed uniformly in the file, overlaps.

Two windows are measured:
- **Declared range:** the record's `startLine` to `endLine`.
- **Displayed range:** the lines the excerpt actually shows, aligned to the
  HEAD blob. An excerpt is a trimmed character window inside the declared
  range. Its first line can be the tail of a blob line, and its last line can
  be cut short. It must end within the declared range. Excerpts with no
  alignment, or more than one, are counted as ambiguous. Declared counts are
  per declared range; displayed counts are per distinct excerpt.

A pure deletion has no new-side line, so the line before it stands in as a
proxy for nearby context.

On the final dev runs, counting distinct citations per arm:

| Arm | Citations | Unmappable | Certain | Declared overlap (chance) | Displayed overlap (chance) | Ambiguous |
|---|---|---|---|---|---|---|
| codex-ygg | 187 | 101 | 7 | 39 of 79 (5.1) | 23 of 56 (2.7) | 23 |
| shell-ygg | 54 | 21 | 7 | 6 of 26 (1.6) | 5 of 19 (1.3) | 8 |

- Citations land on the fixed lines far above chance in both measures.
- 30 of the 31 ambiguous excerpts reveal a ygg defect, not an alignment
  limit. Chunk text and excerpts were whitespace-trimmed, but `startLine` was
  not moved past the trimmed blank lines. Each of these excerpts aligns
  exactly once shifted 1 to 3 lines forward. So in these runs, a citation
  after blank lines named lines earlier than the text it showed.
- Of the 60 declared misses, 13 were within 10 lines; the median miss was 56
  to 99 lines away.
- In the traces, agents read the whole file once it ranks first, so a
  far-off window costs little.
- Citation placement is not a target until a task shows an agent stopping
  at the cited window.

## Structural cards and symbol jumps

An offline check found that per-hit structural neighbours surface fewer
changed files than the same number of extra ranked paths (see
`docs/retrieval-quality.md`, cross-artifact changes). That left the agent-loop
claim: line-anchored, checkable edges might save exploratory calls. Two arms
tested it against the ygg-only arm, with Haiku 5.5, an 8-call budget, compact
output, lexical search with the extractor plugins and no reranker (the same
for every arm), on symptom tasks:

- `ygg-cards`: the first five hits that lie inside a declaration carry a card
  listing up to three callers, three callees and two related types as
  `name path:line`, from extractor declarations whose name is declared once.
- `ygg-jump`: `ygg-def NAME` and `ygg-refs NAME` print a declaration and its
  call sites, a jump-to-definition tool in the spirit of RepoNavigator without
  its trained policy.

Both live in `tools/agent-eval/cards.py`. The arms build on the ygg-only arm
because Haiku with shell and ygg used ygg in only 40 of 146 runs. The primary
set is the 33 low-overlap dev cases whose language has an installed extractor
(Postgres and Dapper have none), run twice; the 48-case round-robin sample ran
once.

| Set | Arm | Hit | Recall | Calls | Input tokens | Fewer / more calls than ygg |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Low-overlap, 2 x 33 | ygg | 53 | 0.789 | 3.0 | 35.0k | |
| Low-overlap, 2 x 33 | ygg-cards | 51 | 0.765 | 3.5 | 41.7k | 20 / 32 |
| Low-overlap, 2 x 33 | ygg-jump | 51 | 0.762 | 3.5 | 37.3k | 17 / 29 |
| Sample, 48 | ygg | 39 | 0.764 | 4.2 | 46.5k | |
| Sample, 48 | ygg-cards | 37 | 0.726 | 4.1 | 52.3k | 23 / 17 |
| Sample, 48 | ygg-jump | 40 | 0.785 | 4.6 | 49.0k | 13 / 24 |

The agent used `ygg-def` or `ygg-refs` in 33 of the 66 primary jump runs, so
the tools were found and used. Neither arm met the rule fixed before the run
(no loss of hits, recall within 0.02, and at least 10% fewer calls or input
tokens with more paired cases improving than worsening): both used more calls
and tokens on the primary set and found no more files. Cards and jumps were
not added to search output.

## Excerpts on the first two results only

The hono patch trials suggested that low-precision second and third cards
mislead agents, so a `top2` output format keeps excerpts only on the first two
results and lists the rest as `path:start-end`. It ran in the ygg arm on the
structural-card trial's cases and setup, against that trial's compact rows.

| Set | Format | Hit | Recall | Calls | Input tokens | Fewer / more calls |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Low-overlap, 2 x 33 | compact | 53 | 0.789 | 3.0 | 35.0k | |
| Low-overlap, 2 x 33 | top2 | 54 | 0.800 | 3.6 | 37.4k | 8 / 33 |
| Sample, 48 | compact | 39 | 0.764 | 4.2 | 46.5k | |
| Sample, 48 | top2 | 38 | 0.747 | 4.7 | 49.9k | 17 / 21 |

Without the later excerpts the agent read more files to check them, so calls
and tokens rose and answers did not improve. The format failed the same
decision rule and was not adopted; compact output stays.

## Conclusions

- In the final setup, strong agents find the files about as well with ygg as
  without it.
- The miss review found stale or undescribed labels and files that appeared
  in tool output but were omitted from the answer.
- The [patch grader](patch-eval.md) measures whether submitted patches pass
  behavioral checks on pinned pre-fix source. Use file discovery to diagnose
  where agents searched and which files they found.
