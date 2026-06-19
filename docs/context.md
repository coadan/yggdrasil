# Context Packets

`agraph ask --json` is the preferred query path when a coding agent needs graph
evidence in its prompt. It returns a compact JSON packet instead of dumping the
full graph.

```sh
agraph ask "where does the API gateway send requests" --project sample --json --budget 4000
```

The packet schema is `agraph.context/v1`:

```json
{
  "schema": "agraph.context/v1",
  "query": "where does the API gateway send requests",
  "budget": {"requested": 4000, "estimated": 900, "truncated": false},
  "entities": [],
  "edges": [],
  "activity": [],
  "docs": [],
  "sourceCoverage": {
    "schema": "agraph.source-coverage.context/v1",
    "basis": "indexed-graph",
    "totals": {"indexedFiles": 40, "diagnostics": 0, "fileKinds": 3},
    "topFileKinds": [],
    "extractors": [],
    "extractorFingerprints": [],
    "diagnostics": {"byStage": [], "byExtractor": []}
  },
  "answerability": {
    "status": "limited",
    "available": ["source-graph", "dependencies", "docs"],
    "missing": ["embeddings", "system-graph", "activity", "validation-history"],
    "weak": [],
    "unsupported": ["remote-work", "session-history"],
    "counts": {
      "nodes": 120,
      "external-packages": 12,
      "package-import-edges": 8,
      "search-docs": 40,
      "activity-items": 0
    },
    "retrieval": {"requested": "auto", "effective": "lexical", "fallback?": true},
    "warnings": [],
    "next": [],
    "nextActions": []
  },
  "warnings": [],
  "drilldowns": []
}
```

`budget.estimated` is a cheap JSON-size token estimate. If snippets do not fit,
AGraph keeps the source reference and marks `snippetOmitted`; if even that does
not fit, the doc is omitted and a warning is added.
`sourceCoverage` is trimmed before warnings and drilldowns when the packet is
too small. Under tight budgets, `answerability` is compacted but keeps status,
evidence planes, counts, retrieval, next steps, unsupported planes, and a bounded
warning list.

## Answerability

Every context packet reports `answerability`: a mechanical summary of which
evidence planes were available for the query and which were missing, weak, or
not supported by the current model.

Project-level reports and `sync inspect --json` expose the same mechanical
inventory as `agraph.evidence/v1`. Use that evidence surface when an agent needs
to see what can be asked about at a glance. Use `agraph ask --json` when the
agent has a concrete question and needs the smaller query-scoped
`answerability` packet plus matching entities, edges, docs, and activity.
Both surfaces use `dependencies` for the evidence plane backed by package
declarations, lockfile versions, and mechanically resolved package-import edges.

- `status`: `ready`, `limited`, or `empty`
- `available`: populated evidence planes, such as `source-graph`,
  `dependencies`, `docs`, `system-graph`, `embeddings`, `activity`,
  `validation-history`, or `map-overlay`
- `missing`: supported evidence planes with no useful rows for this project or
  read context
- `weak`: evidence exists, but did not match this query well
- `unsupported`: useful evidence planes AGraph cannot model yet, currently
  `remote-work` and `session-history`
- `counts`: compact row counts used to make the decision, including
  `external-packages`, `package-import-edges`, `unresolved-imports`,
  `package-evidence-gaps`, and `package-conflicts` when dependency facts are
  indexed. Queue-backed activity counts include `activity-items`,
  `activity-events`, `validation-events`, and
  `result-schema-mismatch-events`.
- `retrieval`: requested and effective retriever, including lexical fallback
- `warnings`: short mechanical explanations
- `next`: bounded follow-up commands
- `nextActions`: the same follow-up work as structured rows with `kind`,
  `label`, optional `count`, executable `command`, and optional MCP hints
  (`mcpTool`, `mcpArgs`) when a typed MCP tool maps directly to the action

If `dependencies` is missing or weak, `next` points at the package report so
agents can inspect whether the graph has package declarations, import evidence,
conflicts, or unresolved imports before answering dependency-shaped questions.
When unresolved imports are present, `next` also suggests
`agraph sync <project.edn> --check --enqueue` so the package mapping can move
through the review queue. When declared packages lack source import evidence or
version conflicts are present, `next` includes the matching package-report
filter command.
If source files or source graph rows are missing, `warnings` names that source
plane explicitly and `next` points at `agraph sync <project.edn>` or
`agraph sync <project.edn> --check`. Agents should treat those as graph-basis
problems before concluding that code evidence does not exist.
Machine consumers should prefer `nextActions` over parsing `next`; MCP clients
should use `mcpTool`/`mcpArgs` when present. `next` stays as a compact
human-readable command list.

Agents should treat `answerability` as a confidence boundary. Local queue
activity and validation-shaped queue results are supported after
`agraph sync activity <project.edn>`. Remote work tools and session history are
still unsupported. If `status` is `empty` or `limited`, follow `next` or use the
listed missing planes to decide whether to sync, embed, inspect coverage, import
activity, or ask the user for another source of truth.
When indexed diagnostics are present, `answerability.warnings` points agents to
`sourceCoverage` and `agraph sync coverage`; this is a source-support signal, not
a semantic classification.

Plain `agraph ask` prints a concise answerability warning only when no query
results are found. Use `agraph ask --json` for the full structured packet.

## Source Coverage

Context packets include `sourceCoverage`, a compact summary of the indexed graph
basis for the selected project/repo/read context. It reports indexed file counts,
top source kinds, extractor versions, persisted extractor fingerprint groups,
and active diagnostics grouped by stage and extractor. Fingerprints are opaque
mechanical audit ids for the extractor/indexing boundary used to create indexed
file rows; they are not semantic classifications. It does not scan the
filesystem for unsupported files; use `agraph sync coverage <project.edn> --json`
when an agent needs skipped or unsupported source candidates. Project evidence
and report packets include compact `skipped-by-extension` and
`skipped-by-reason` rows with bounded samples for first-pass triage.

`candidateFiles` lists ranked file candidates from retrieval. Rows include
`repo` when the indexed search result is repo-scoped, so agents can distinguish
same-path files across multi-repo projects without relying on path text alone.

## Doc Attachments

Docs are indexed as Markdown heading chunks with source lines, end lines,
heading paths, and content hashes. Accepted docs live in `agraph.map.json` so
agents can maintain them alongside system boundaries.

Find candidate snippets:

```sh
agraph sync docs candidates system:sample:app:path/services/api-gateway --project sample --limit 6
```

Attach a reviewed snippet:

```sh
agraph sync docs attach "API Gateway" app:docs/api-gateway.md \
  --map agraph.map.json \
  --role contract \
  --heading "API Gateway"
```

Read attached docs for one target:

```sh
agraph sync docs for "API Gateway" --project sample --map agraph.map.json
```

Audit stale or missing docs:

```sh
agraph sync docs audit --project sample --map agraph.map.json
```

Use roles to tell agents how to treat the snippet:

- `overview`: what this system is
- `contract`: boundary, API, schema, or ownership rules
- `runbook`: local operation or debugging steps
- `troubleshooting`: symptom and diagnostic guidance
- `rationale`: why a boundary or decision exists
- `warning`: known pitfall or forbidden shortcut
- `reference`: useful background

## Agent Workflow

Start with `agraph ask --json` for the task question. Follow `drilldowns` only when
the packet is insufficient. During build or maintenance work, promote useful
candidate docs into accepted map attachments and run `agraph sync docs audit`
before handoff.

Install project-local agent guidance when a coding assistant should discover
AGraph automatically:

```sh
agraph install-agent --platform codex --project
agraph install-agent --platform codex --project --hooks
```

The installer edits only marked AGraph sections. Remove them with:

```sh
agraph install-agent uninstall --platform codex --project
```

For MCP clients, run:

```sh
agraph-mcp --config project.edn --map agraph.map.json
```

The MCP server returns the same packet schemas as the CLI. Initial tools include
`agraph_ask`, `agraph_explore_create`, `agraph_explore_open`,
`agraph_explore_expand`, `agraph_explore_docs`, `agraph_explore_search`,
`agraph_view_systems`, `agraph_sync_inspect`, `agraph_sync_check`,
`agraph_sync_activity`, `agraph_work_list`, `agraph_work_show`,
`agraph_work_pull`, `agraph_work_heartbeat`, `agraph_work_complete`,
`agraph_work_release`, and `agraph_work_reject`.
`agraph_work_complete` records an explicit result artifact; applying validated
results to `agraph.map.json` stays a separate CLI step.

## Filesystem Queue

Use `--enqueue` when a packet should be picked up by another agent, model, tool,
or human process. AGraph writes an `agraph.queue.item/v1` JSON file to
`.dev/agraph/queue/ready` and prints a compact receipt.
Queue listings and claimed work summaries use `agraph.queue.summary/v1` and
include `actions` rows with executable commands for inspecting payloads,
claiming, extending leases, completing, releasing, rejecting, or applying work
results. When a packet declares `expectedResultSchema`, summaries expose it as
`expected-result-schema` so agents know which result JSON shape to produce before
loading the full payload. Agents should use those commands instead of
reconstructing queue paths or item ids from payloads.

```sh
agraph sync check project.edn --map agraph.map.json --enqueue
agraph explore create "projection boundary" --project sample --enqueue
agraph sync work pull --project sample --agent codex
agraph sync work heartbeat queue:abc123 --agent codex --lease-minutes 30
agraph sync work complete queue:abc123 --result result.json
agraph sync activity project.edn
agraph sync work apply queue:abc123 --map agraph.map.json
```

The queue is only the transport. The embedded payload remains unchanged, and the
consumer result should be an explicit JSON artifact such as a map patch,
classification, or finding. `sync work complete` stores the artifact for audit.
`sync activity` imports queue item lifecycle and validation-shaped result facts
into XTDB so future `ask --json` packets can include `activity` matches.
Activity matches include `payloadSchema`, `expectedResultSchema`, and
`resultSchema` when available, preserving both the requested and actual result
contracts.
When a completed work item returns a different `schema` than its
`expectedResultSchema`, activity sync records a `result-schema-mismatch` event.
Answerability and project evidence surfaces count those events as
`result-schema-mismatch-events` and direct agents to inspect activity before
trusting the prior result. The `sync activity --json` result also includes a
bounded `result-schema-mismatches` list with the work source id, item id,
expected schema, actual schema, status, summary, and timestamps for direct
audit.
`sync work apply` validates supported result schemas before writing accepted
changes to `agraph.map.json`.

## Explore Packets

`agraph explore` is the progressive-disclosure query path for longer agent work.
It stores a stable graph basis and returns small JSON packets that can be opened,
expanded, searched, and revisited without dumping the full graph.

```sh
agraph explore create "api gateway connections" --project sample --map agraph.map.json
agraph explore open cursor:abc123 "API Gateway"
agraph explore expand cursor:def456 "API Gateway"
agraph explore docs cursor:def456 "API Gateway"
agraph explore search cursor:def456 "gateway route"
```

Explore uses the `agraph.cursor.packet/v1` packet schema. Packets include
`nextActions` rows with typed `kind`, `label`, `target`, and executable
`command` fields for expanding the graph, inspecting docs, or searching within
the fixed cursor basis. The legacy `next` field is derived from those rows for
human-readable compatibility; agents should prefer `nextActions`.
Each mutating explore command creates a new immutable revision with a parent
cursor id. If `--map` is used, AGraph stores the parsed map correction layer
inside the cursor row, so later edits to `agraph.map.json` do not change older
cursor revisions.
