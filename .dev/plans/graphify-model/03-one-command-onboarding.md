# Stage 3: One Command Onboarding

Status: implemented for repo and explicit workbench project initialization,
with optional sync capture

## Goal

Make the first successful AGraph run as simple as Graphify's folder-first
experience while preserving AGraph's project model. Users should not need to
hand-write `project.edn` before seeing value.

## Target Commands

```text
agraph init <repo-or-workspace-root> [--project ID] [--name NAME] [--out project.edn]
agraph init --workbench <root> [--task TASK] [--project ID] [--out project.edn]
agraph init . --sync --map agraph.map.json
```

Optionally add a convenience command after `init` is stable:

```text
agraph start .
```

`start` would be a wrapper around `init`, `sync --check`, and a compact next
steps report.

## Behavior

For a plain repo:

1. Detect repo root.
2. Generate a minimal `project.edn`.
3. Choose a project id from directory name unless `--project` is supplied.
4. Include one repo with role `:application` unless supplied later.
5. Print the next sync command.

For a workbench:

1. Detect or accept `.workbench/repos`, `.worktrees`, and `repos.json`.
2. Generate `:workbench-root` and optional `:workbench-task`.
3. Preserve existing repo ids from `repos.json`.
4. Print the next sync command.

With `--sync`:

1. Run `agraph sync <project.edn> --check`.
2. Generate or update `agraph.map.json` only when explicitly requested.
3. Print high-level indexed/skipped counts and top next commands.

## Output Shape

The final output should be short:

```text
# AGraph Project
- project fixture
- config project.edn
- repos 1
- indexed files 42
- system candidates 8

Next:
- agraph ask "where is auth handled" --project fixture --json
- agraph view systems --project fixture
- agraph install-agent --platform codex --project
```

## Implementation Areas

- Extend `agraph.project` with project config generation helpers.
- Reuse `sync add-repo` logic where possible.
- Add CLI command under `init`.
- Add careful path normalization for generated EDN.
- Add dry-run mode if implementation becomes risky.

## Tests

- Init a single temp Git repo.
- Init a non-Git directory.
- Init a workbench fixture with `repos.json`.
- Preserve existing `project.edn` unless `--force` is supplied.
- Verify generated EDN round-trips through `project/read-project`.
- Verify `--sync` path produces an index summary on `test/fixtures/project-repo`.

## Non-Goals

- Do not infer semantic repo roles from directory names.
- Do not classify system boundaries during init.
- Do not scan unsupported files as semantic content.
- Do not install agent hooks automatically.

## Done Criteria

A new user can run `agraph init . --sync --map agraph.map.json` in a repository
and immediately receive a valid project config, a completed sync, and clear next
commands.

Implemented surface:

- `agraph init <repo-root> [--project ID] [--name NAME] [--out project.edn] [--force]`
- `agraph init --workbench <root> [--task TASK] [--project ID] [--name NAME] [--out project.edn] [--force]`
- `agraph init <repo-root> --sync [--map agraph.map.json] [--query-index]`

Notes:

- Plain repo init writes one `app` repo with role `:application`; it does not
  infer semantic role from directory names.
- Workbench init writes `:workbench-root` and optional `:workbench-task`.
- `--map` creates an empty correction map only when the path is explicitly
  supplied and missing.
- `--sync` captures the existing `sync --check` output in the init result.
