# Stage 8: Start Command

Status: implemented.

## Goal

Turn the completed adoption surface into one memorable command for fresh and
repeat local use.

## Command

```text
agraph start <repo-root> [--project ID] [--name NAME] [--out project.edn] [--map agraph.map.json] [--report-out agraph-out] [--force] [--query-index]
```

## Behavior

- Initializes `project.edn` when it is missing.
- Reuses an existing project config unless `--force` is supplied.
- Uses `agraph.map.json` by default; `--no-map` disables the correction map.
- Runs the same graph sync/check path as `agraph sync <project.edn> --check`.
- Imports local queue activity with `sync activity` semantics.
- Writes a report bundle with `agraph report` semantics.
- Returns a compact JSON summary with next commands.

## Guardrails

- No semantic inference from names, paths, or hosts.
- No hidden map mutation beyond creating an explicit empty map for first run.
- No embeddings or LLM calls.
- No Git hook or agent instruction install unless the user runs those commands.

## Validation

- CLI usage and dispatch tests cover the new public command.
- Focused CLI tests cover first-run initialization and idempotent config reuse.
- Manual smoke should run with a temporary `AGRAPH_XTDB_PATH` to avoid generated
  graph data in the repository.
