# Stage 5: MCP Server

Status: implemented.

## Goal

Turn `ygg-mcp` from a placeholder into the structured agent interface that
Yggdrasil's packet model already implies. Graphify's MCP support is useful because
agents can query a graph without shell-specific glue. Yggdrasil should expose its
stronger context, explore, view, and work queue APIs through MCP.

## Initial Tools

Expose a small surface first:

| MCP tool | Backing command/function |
| --- | --- |
| `ygg_ask` | `ygg ask --json` / context packet generation |
| `ygg_explore_create` | `ygg explore create` |
| `ygg_explore_open` | `ygg explore open` |
| `ygg_explore_expand` | `ygg explore expand` |
| `ygg_view_systems` | `ygg view systems --format json` |
| `ygg_sync_inspect` | `ygg sync inspect` |
| `ygg_sync_check` | `ygg sync check --json` |
| `ygg_work_pull` | `ygg sync work pull` |
| `ygg_work_complete` | `ygg sync work complete` |

Add mutating tools conservatively. `work_complete` records a result artifact,
but `work_apply` should require explicit support and clear schema validation.

## Resource Model

Expose read-only resources for:

- project configs discovered in the current workspace
- recent context packets
- recent explore cursor packets
- latest systems graph JSON
- queue item summaries

Do not expose raw XTDB internals as MCP resources in the first version.

## Protocol Behavior

The MCP server should:

- use stdio
- accept a project root or config path option
- return compact JSON packets
- include suggested next commands or next tool calls
- preserve Yggdrasil schema ids in responses
- fail with structured errors
- avoid running long sync jobs unless explicitly requested

## Implementation Areas

- Add `ygg.mcp` namespace.
- Make `bin/ygg-mcp` call real server entrypoint.
- Refactor CLI handlers where needed so MCP can call functions directly instead
  of shelling back into the CLI.
- Keep tool schemas narrow and explicit.
- Add a local fake transport or command-level test harness.

## Tests

- MCP initialize/list-tools smoke test.
- Tool schema snapshot tests.
- `ygg_ask` returns `ygg.context/v1`.
- `ygg_view_systems` returns `ygg.graph/v2`.
- Missing project returns structured error.
- Mutating work completion rejects invalid result JSON.

## Non-Goals

- Do not expose arbitrary SQL or XTDB query execution.
- Do not expose an LLM classification endpoint before the decision packet flow
  is stable.
- Do not hide file edits or map mutations behind implicit agent behavior.
- Do not make MCP the only supported interface; CLI remains canonical.

## Done Criteria

`ygg-mcp` starts a stdio server and an MCP client can list tools, ask a graph
question, open an explore packet, inspect systems, and pull a queue item without
using shell commands.
