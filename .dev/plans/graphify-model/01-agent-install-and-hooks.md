# Stage 1: Agent Install And Hooks

Status: implemented for Codex project guidance and optional Codex hook file

## Goal

Make AGraph easy to wire into coding assistants. Graphify's strongest adoption
move is not its graph model; it is the install path that teaches assistants to
consult the graph before broad file exploration. AGraph should provide the same
friction reduction while routing agents to AGraph's stronger packet APIs.

## Target Commands

```text
agraph install-agent --platform codex [--project] [--force]
agraph install-agent --platform claude [--project] [--force]
agraph install-agent --platform cursor [--project] [--force]
agraph install-agent --platform gemini [--project] [--force]
agraph install-agent list
agraph install-agent uninstall --platform codex [--project]
```

Start with `codex`. Add other platforms only after the generator shape is
stable.

## Generated Guidance

The generated instruction block should tell agents:

- run `agraph sync inspect <project.edn>` before broad project assumptions
- run `agraph sync <project.edn> --check --map agraph.map.json` when sync state
  matters
- use `agraph ask --json` for one-shot graph evidence packets
- use `agraph explore` for longer investigations
- use `agraph view systems --detail primary` before drilling into `expanded`,
  `evidence`, or `raw`
- use `sync work pull`, `complete`, and `apply` for queued maintenance packets
- update `agraph.map.json` only through supported sync commands when possible
- avoid full graph dumps unless explicitly needed

The block must also carry AGraph's hard boundary:

- deterministic core stores mechanical facts
- humans or LLM-backed correction packets make semantic decisions
- do not infer architecture from names, path vocabulary, prose, or host lists

## Install Targets

Initial target:

| Platform | Project target | User target |
| --- | --- | --- |
| Codex | `AGENTS.md` plus optional `.codex/hooks.json` | `$CODEX_HOME` guidance only if safe to discover |

Later targets:

| Platform | Project target |
| --- | --- |
| Claude Code | `CLAUDE.md` and optional hook settings |
| Cursor | `.cursor/rules/agraph.mdc` |
| Gemini CLI | `GEMINI.md` |

## Hook Behavior

Optional hooks should nudge, not block.

Trigger on broad exploration patterns:

- `rg`, `grep`, `find`, `fd`, `ag`, `ack`
- repeated source reads when the project has a current graph

Hook output should recommend scoped commands:

```text
agraph ask "<question>" --project <id> --json
agraph explore create "<question>" --project <id> --map agraph.map.json
agraph view systems --project <id> --detail primary
```

Hooks must fail open.

## Implementation Areas

- Add a new namespace such as `agraph.agent-install`.
- Add CLI dispatch under `install-agent`.
- Reuse existing project detection from `agraph.project`.
- Add stable begin/end markers for generated sections.
- Write atomically with temp files and rename.
- Preserve unrelated content in existing agent instruction files.

## Tests

- Install into a temp repo with no existing `AGENTS.md`.
- Install into a temp repo with unrelated `AGENTS.md` content.
- Re-run install and verify idempotency.
- Uninstall and verify only AGraph-owned sections are removed.
- Validate generated hooks are syntactically valid JSON.
- Verify hook generation is optional and safe when no project config exists.

## Non-Goals

- Do not build a hidden agent loop.
- Do not auto-run AGraph before every tool call.
- Do not install global files unless the command explicitly requests user scope.
- Do not introduce compatibility aliases for every assistant on day one.

## Done Criteria

`agraph install-agent --platform codex --project` creates or updates local
Codex guidance that points agents to `sync`, `ask`, `explore`, `view`, and
`sync work`, and re-running the command produces no duplicate sections.

Implemented surface:

- `agraph install-agent --platform codex --project`
- `agraph install-agent --platform codex --project --hooks`
- `agraph install-agent --platform codex --project --hooks --force`
- `agraph install-agent uninstall --platform codex --project`
- `agraph install-agent list`
