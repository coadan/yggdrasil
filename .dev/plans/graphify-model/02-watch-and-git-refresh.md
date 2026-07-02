# Stage 2: Watch And Git Refresh

Status: implemented for Git hook install/status/uninstall and WatchService
refresh loop

## Goal

Keep Yggdrasil current without requiring users or agents to remember manual sync
after every meaningful change. Borrow Graphify's `--watch` and Git hook
refresh loop, but keep Yggdrasil's sync profile explicit and deterministic.

## Target Commands

```text
ygg watch <project.edn> [--query-index] [--debounce-ms N]
ygg hook install <project.edn> [--query-index]
ygg hook uninstall
ygg hook status
```

## Watch Behavior

Default watch should:

- observe supported files from each repo in `project.edn`
- ignore `.git`, `.dev`, `target`, `node_modules`, lockfiles, and existing
  ignored paths
- debounce bursts of changes
- run graph-maintenance sync by default
- print compact summaries, not full JSON reports
- never write semantic corrections from hooks or watch refreshes

Use `--query-index` when the user wants searchable chunks and embeddings inputs
refreshed during watch.

## Git Hook Behavior

Install hooks for:

- `post-commit`: refresh graph after a committed source state
- `post-checkout`: refresh graph after branch changes
- optionally `post-merge`: refresh graph after merges

Hooks should:

- call the current Yggdrasil executable path
- use the project config path passed during install
- fail open so Git workflows are not blocked
- log short summaries under `.dev/ygg/hooks/`
- avoid recursive behavior

## Sync Profiles

Use existing profile behavior:

- default hook/watch profile: files, graph rows, diagnostics, bounded file facts
- optional `--query-index`: also write searchable chunks for `ask`, `explore`,
  embeddings, and context packets

Do not run embeddings automatically unless a later explicit command adds that
behavior.

## Implementation Areas

- Add `ygg.watch` for filesystem observation.
- Add `ygg.hook` for hook install/status/uninstall.
- Reuse `ygg.fs/supported-path?` and ignore rules.
- Reuse `sync` command implementation rather than duplicating index logic.
- Add hook marker sections when editing existing Git hook files.

## Tests

- Unit test supported/ignored watch path filtering.
- Hook install creates executable hook scripts in a temp Git repo.
- Hook install preserves existing hook content.
- Hook uninstall removes only Yggdrasil-owned blocks.
- Watch debounce collapses multiple events into one sync request.
- Hook command fails open when `ygg` exits nonzero.

## Non-Goals

- Do not watch unsupported media types until extractor support exists.
- Do not auto-apply maintenance decisions.
- Do not run LLM classification from hooks.
- Do not run long embedding jobs by default.

## Done Criteria

A user can install hooks for a project, commit a source change, and see Yggdrasil
refresh its mechanical graph state without blocking Git or mutating semantic
corrections.

Implemented surface:

- `ygg watch <project.edn> [--query-index] [--debounce-ms N]`
- `ygg hook install <project.edn> [--query-index]`
- `ygg hook uninstall <project.edn>`
- `ygg hook status <project.edn>`

Notes:

- Hooks are installed into `post-commit`, `post-checkout`, and `post-merge` for
  each normal Git repo in the project.
- Hook scripts fail open and log to `.dev/ygg/hooks/` inside the repo.
- Watch tests cover path filtering, event coalescing, and sync command shaping;
  they do not enter the long-running watch loop.
