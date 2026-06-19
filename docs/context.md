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
  "blastRadius": {
    "basis": "selected-mechanical-edges",
    "downstream": {"count": 0, "targets": []},
    "upstream": {"count": 0, "targets": []}
  },
  "systems": {
    "basis": "mechanical-plus-map",
    "accepted": [],
    "candidates": [],
    "counts": {"accepted": 0, "candidates": 0}
  },
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
    "available": ["source-graph", "dependencies", "system-evidence", "docs"],
    "missing": ["embeddings", "system-graph", "activity", "validation-history"],
    "weak": [],
    "unsupported": ["remote-work", "session-history"],
    "planes": [
      {
        "plane": "source-files",
        "status": "available",
        "counts": {"files": 40, "diagnostics": 0}
      },
      {
        "plane": "dependencies",
        "status": "available",
        "counts": {
          "external-packages": 12,
          "package-import-edges": 8,
          "declared-packages": 12,
          "unresolved-imports": 0,
          "package-evidence-gaps": 0,
          "package-conflicts": 0
        }
      },
      {"plane": "system-evidence", "status": "available", "counts": {"system-evidence": 6}},
      {"plane": "remote-work", "status": "unsupported"}
    ],
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

Project-level reports and `agraph status --json` expose the same mechanical
inventory as `agraph.evidence/v2`, including graph-basis freshness. Use that
evidence surface when an agent needs to see what can be asked about at a glance.
Its `families` field is a bounded readiness table for source files, file facts,
source graph rows, dependencies, docs, embeddings, system evidence, system graph
rows, local activity, validation history, and accepted map overlay evidence.
Use `agraph explore --json` when the agent has a concrete question and needs the
smaller query-scoped `answerability` packet plus matching entities, edges, docs,
and activity.
Both surfaces use `dependencies` for the evidence plane backed by package
declarations, lockfile versions, and mechanically resolved package-import edges.
When plugin packages are installed, context packets also include
`pluginPackages`: compact package caveats with benchmark status, claim authority,
warning counts, package ids, source pins, and manifest fingerprints. Treat
unbenchmarked or non-authoritative plugin output as review evidence, not as
benchmark-backed architecture understanding.

- `status`: `ready`, `limited`, or `empty`
- `available`: populated evidence planes, such as `source-graph`,
  `dependencies`, `system-evidence`, `docs`, `system-graph`, `embeddings`,
  `activity`, `validation-history`, or `map-overlay`
- `missing`: supported evidence planes with no useful rows for this project or
  read context
- `weak`: evidence exists, but did not match this query well
- `unsupported`: useful evidence planes AGraph cannot model yet, currently
  `remote-work` and `session-history`
- `planes`: bounded per-plane status rows for agents that need a quick
  mechanical readiness table before reading raw counts. Rows can be `available`,
  `missing`, `weak`, or `unsupported` and include only count fields relevant to
  that plane.
- `counts`: compact row counts used to make the decision, including
  `external-packages`, `package-import-edges`, `unresolved-imports`,
  `package-evidence-gaps`, and `package-conflicts` when dependency facts are
  indexed. Runtime/config support is reported as `system-evidence`, the active
  count of concrete runtime/config evidence rows selected from indexed system
  evidence. Queue-backed activity counts include `activity-items`,
  `activity-events`, `validation-events`, `result-schema-statuses`,
  `result-schema-status-items`, per-status item counts such as
  `result-schema-matching-items` and `result-schema-missing-result-items`, and
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
If `runtime-config` is missing or weak, agents should treat runtime/config
answers as limited. Missing means no active system-evidence rows are indexed;
weak means rows exist, but none matched the selected work area.
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

Plain `agraph explore` prints a concise answerability warning only when no query
results are found. Use `agraph explore --json` for the full structured packet.
`agraph ask --json` returns the same one-shot packet for compatibility.
Packet `drilldowns` favor agent-facing follow-ups: repeat the primary
`agraph explore ... --json` packet, inspect `agraph view systems`, check
`agraph status --json`, and run `agraph sync docs audit` when a map is present.

## Source Coverage

Context packets include `sourceCoverage`, a compact summary of the indexed graph
basis for the selected project/repo/read context. It reports indexed file counts,
top source kinds, extractor versions, persisted extractor fingerprint groups,
active diagnostics grouped by stage and extractor, and `indexedConnectivity`, a
mechanical graph-topology summary for the same selected basis. Fingerprints are
opaque mechanical audit ids for the extractor/indexing boundary used to create
indexed file rows; they are not semantic classifications.

`indexedConnectivity` reports indexed file, node, edge, connected-file,
cross-file-connected, and isolated-file counts. `connectedFiles` means an
indexed file has at least one active graph edge through one of its indexed
nodes; `crossFileConnectedFiles` means at least one such edge reaches a node in
a different indexed file. `isolatedFiles` are indexed files without active graph
edges. Isolation is not a semantic defect, but agents should treat it as a
trust-boundary signal when a task depends on relationships.

`sourceCoverage` does not scan the filesystem for unsupported files; use
`agraph sync coverage <project.edn> --json` when an agent needs skipped or
unsupported source candidates. When active indexed diagnostics or isolated
indexed files exist, `sourceCoverage.nextActions` points at the same coverage
inspection command. Project evidence and report packets include compact
`skipped-by-extension` and `skipped-by-reason` rows with bounded samples for
first-pass triage. `agraph status --json` and `agraph sync inspect --json` also
include bounded persisted extractor fingerprint groups under
`coverage.extractorFingerprints` and diagnostic samples under
`coverage.diagnostics.samples` when those rows are active, so agents can audit
the indexed basis and jump to concrete files before opening the full coverage
report. Full coverage reports include the same connectivity signal plus
`nextActions` rows when skipped files, extractor diagnostics, or isolated
indexed files need a follow-up coverage inspection. When skipped files are
present, the skipped-source action also includes `pluginGapCommand`:
`bb plugin gap extractor <package-dir> <repo-root> <file> --json`. Use coverage
samples to choose the concrete file; the command itself stays placeholder-based
so core does not infer file-family semantics from extensions or paths.

## Audit Scopes

Audit-scope reports group indexed rows into mechanical evidence families such as
source, docs, dependencies, runtime config, containers, infra, assets,
map corrections, and unknown text. They do not infer project meaning from path
names or prose. `map-corrections` is backed by selected accepted map edges and
selected rejected corrections from `agraph.map.json`, so agents can see prior
review facts beside source/runtime/dependency evidence. When
rows cannot be mapped to a known family, the report includes
`unclassified-extractor` plus `registryDiagnostics`, grouped by source section
and evidence type with bounded samples. Treat those diagnostics as reviewable
extractor-family gaps: either add a bounded core family mapping or leave the
rows explicitly unclassified. Each report scope can include `topFileKinds`, a
bounded count of distinct indexed files by persisted file kind, so agents can
audit which concrete file families support the scope without treating the scope
as a semantic project classification.

`candidateFiles` lists ranked file candidates from retrieval. Rows include
`repo` when the indexed search result is repo-scoped, so agents can distinguish
same-path files across multi-repo projects without relying on path text alone.

`blastRadius` is a conservative summary of selected mechanical graph edges that
cross the selected work area. `downstream` contains edges from selected entities
to unselected neighbors; `upstream` contains edges from unselected neighbors to
selected entities. Edges whose source and target are both selected are omitted
from `blastRadius` because they describe the selected work area itself. This is
not semantic impact analysis; it is a bounded neighbor list for follow-up
inspection.

`systems` is a compact top-level orientation summary derived from the same
accepted and candidate rows emitted under `architecture`. It keeps ids, labels,
mechanical basis, repo/path hints, scores, and short reasons, while leaving
bulky audit details such as includes and evidence ids in `architecture`.

## Architecture Evidence

When selected facts support an architecture packet, `architecture` separates
accepted map corrections from neutral mechanical candidates. Accepted systems
and map edges come from `agraph.map.json`; candidate systems, graph edges,
runtime/config rows, dependency rows, docs, and open decisions remain concrete
evidence rows, not inferred project meaning.
`architecture.rejectedCorrections` carries bounded `reject[]` rows from
`agraph.map.json` when their match criteria mechanically overlap the selected
systems, candidate rows, or result paths. These rows are prior review
corrections with `status: "rejected"` and `provenance: "map-overlay"`; agents
should treat them as known false-positive evidence to avoid reopening the same
mistake, not as a new architecture inference.
Runtime/config rows may be selected by exact result path, query-token match, or
existing `system-id` membership in a selected graph or accepted map system.
Accepted map docs and work/activity lookups follow accepted systems selected
directly or through included file paths.
`architecture.openDecisions` carries selected queue/activity rows that are still
open. Rows keep bounded audit fields such as `payloadSchema`,
`expectedResultSchema`, `resultSchema`, `resultSchemaStatus`, timestamps,
`targetIds`, and recent lifecycle `events` when those fields exist, so agents can
inspect unresolved correction work before trusting or applying it.

`architecture.evidenceFamilies` is a compact readiness summary for the selected
work area. Rows use fixed evidence-family names such as `source-structure`,
`dependency-flow`, `runtime-config`, `deploy-topology`, `docs-contracts`,
`map-corrections`, and `maintenance`. `deploy-topology` is backed by selected
container/deploy fact rows such as container images, ports, Docker stages,
Compose/devcontainer/Helm/Kustomize facts, and runtime commands. `rowCount` and
`sourceCounts` come from packet rows already present in the architecture
section. `planes` mirrors matching `answerability` plane statuses when a family
depends on an indexed evidence plane. These rows help agents see whether an
answer is backed by code, config, deployment evidence, dependency, docs, map
correction, or maintenance evidence without classifying architecture from path
names or prose.

`architecture.summary` is the smallest architecture signal agents should keep
when token budgets force packet trimming. It includes row counts for accepted
systems, candidate systems, rejected corrections,
boundary/runtime/deploy/dependency evidence, docs, open decisions, validation
gaps, warnings, and next actions. It also includes status counts and keyed
status maps for `evidenceFamilies` and
`validationGaps`, plus bounded `validationGapSamples` and `nextActionSamples`.
Those samples are copied from existing rows, not inferred, so a summary-only
packet still tells the agent which evidence families or validation planes need
inspection and which first actions are available.

`architecture.validationGaps` also reports graph-basis freshness when the
packet freshness status is `stale`, `partial`, `unknown`, or `unsynced`. These
rows use `plane: "graph-basis"` and include bounded freshness counts and
warnings when available. Gap rows may include bounded `nextActions` copied from
matching freshness or answerability actions, so the packet shows the exact
repair or inspection command beside the missing or weak evidence plane.
Matching actions are still included in `architecture.nextActions`, and
freshness warnings are surfaced in `architecture.warnings` before
answerability warnings so agents see basis problems before trusting missing
architecture evidence. Accepted map docs whose indexed source is stale or
missing remain visible under `architecture.docs`, but also add a
`docs-contracts` validation gap with bounded samples so agents know the
contract attachment needs repair before relying on it.

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
agraph agent install --platform codex --project
agraph agent install --platform codex --project --hooks
agraph agent install --platform codex --project --hooks --print-config
```

The installer edits only marked AGraph sections. Remove them with:

```sh
agraph agent uninstall --platform codex --project
```

For MCP clients, run:

```sh
agraph-mcp --config project.edn --map agraph.map.json
```

The MCP server returns the same packet schemas as the CLI. By default,
`tools/list` exposes only `agraph_explore`, `agraph_node`, `agraph_status`, and
`agraph_systems`. Use `--tools default,cursor,sync,work,ask` or
`AGRAPH_MCP_TOOLS=all` to enable and list advanced cursor, sync, and queue
handoff tools; hidden advanced tools are rejected by default.
Use `agraph_explore` as the primary one-shot MCP packet for structural
questions; it returns graph-basis freshness, answerability, candidate files,
docs, graph facts, plugin package caveats, and drilldowns without creating a
cursor. MCP agents should inspect `freshness`, `evidence.families`,
`answerability.planes`, `pluginPackages`, and `nextActions` before treating
missing facts as absent.
Use `agraph_node` for a single file, evidence row, package, node, or accepted
system; when map docs are attached, the node packet includes bounded
line-numbered doc source windows when the file is available.
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
loading the full payload. Completed item summaries also expose the artifact's
actual `result-schema` when the result includes a `schema`, so agents can compare
the requested and returned contracts before importing activity or applying work.
Agents should use those commands instead of reconstructing queue paths or item
ids from payloads.

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
Activity matches include `payloadSchema`, `expectedResultSchema`,
`resultSchema`, and `resultSchemaStatus` when available, preserving both the
requested and actual result contracts. `resultSchemaStatus` is mechanical:
`matching`, `mismatch`, `missing-result`, or `unexpected-result`.
When a completed work item returns a different `schema` than its
`expectedResultSchema`, activity sync records a `result-schema-mismatch` event.
Answerability and project evidence surfaces count both item-level
`result-schema-statuses` and `result-schema-mismatch-events`. Missing or
unexpected result schemas direct agents to inspect activity before reusing prior
work, and mismatches direct agents to inspect activity before trusting the prior
result. The `sync activity --json` result also includes a bounded
`result-schema-mismatches` list with the work source id, item id, expected
schema, actual schema, status, summary, and timestamps for direct audit.
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
