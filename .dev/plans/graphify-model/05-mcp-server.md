# Stage 5: MCP Server

Status: implemented.

## Goal

Turn `agraph-mcp` from a placeholder into the structured agent interface that
AGraph's packet model already implies. Graphify's MCP support is useful because
agents can query a graph without shell-specific glue. AGraph should expose its
stronger context, explore, view, and work queue APIs through MCP.

## Initial Tools

Expose a small surface first:

| MCP tool | Backing command/function |
| --- | --- |
| `agraph_ask` | `agraph ask --json` / context packet generation |
| `agraph_explore_create` | `agraph explore create` |
| `agraph_explore_open` | `agraph explore open` |
| `agraph_explore_expand` | `agraph explore expand` |
| `agraph_view_systems` | `agraph view systems --format json` |
| `agraph_sync_inspect` | `agraph sync inspect` |
| `agraph_sync_check` | `agraph sync check --json` |
| `agraph_work_pull` | `agraph sync work pull` |
| `agraph_work_complete` | `agraph sync work complete` |

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
- preserve AGraph schema ids in responses
- fail with structured errors
- avoid running long sync jobs unless explicitly requested

## Implementation Areas

- Add `agraph.mcp` namespace.
- Make `bin/agraph-mcp` call real server entrypoint.
- Refactor CLI handlers where needed so MCP can call functions directly instead
  of shelling back into the CLI.
- Keep tool schemas narrow and explicit.
- Add a local fake transport or command-level test harness.

## Tests

- MCP initialize/list-tools smoke test.
- Tool schema snapshot tests.
- `agraph_ask` returns `agraph.context/v1`.
- `agraph_view_systems` returns `agraph.graph/v2`.
- Missing project returns structured error.
- Mutating work completion rejects invalid result JSON.

## Non-Goals

- Do not expose arbitrary SQL or XTDB query execution.
- Do not expose an LLM classification endpoint before the decision packet flow
  is stable.
- Do not hide file edits or map mutations behind implicit agent behavior.
- Do not make MCP the only supported interface; CLI remains canonical.

## Done Criteria

`agraph-mcp` starts a stdio server and an MCP client can list tools, ask a graph
question, open an explore packet, inspect systems, and pull a queue item without
using shell commands.
