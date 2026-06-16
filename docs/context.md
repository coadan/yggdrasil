# Context Packets

`agraph context` is the preferred query path when a coding agent needs graph
evidence in its prompt. It returns a compact JSON packet instead of dumping the
full graph.

```sh
agraph context "where does projection gateway talk to spacetime" --project void --budget 4000
```

The packet schema is `agraph.context/v1`:

```json
{
  "schema": "agraph.context/v1",
  "query": "where does projection gateway talk to spacetime",
  "budget": {"requested": 4000, "estimated": 900, "truncated": false},
  "entities": [],
  "edges": [],
  "docs": [],
  "warnings": [],
  "drilldowns": []
}
```

`budget.estimated` is a cheap JSON-size token estimate. If snippets do not fit,
AGraph keeps the source reference and marks `snippetOmitted`; if even that does
not fit, the doc is omitted and a warning is added.

## Doc Attachments

Docs are indexed as Markdown heading chunks with source lines, end lines,
heading paths, and content hashes. Accepted docs live in `agraph.map.json` so
agents can maintain them alongside system boundaries.

Find candidate snippets:

```sh
agraph docs candidates system:void:void:services/projection_gateway --project void --limit 6
```

Attach a reviewed snippet:

```sh
agraph docs attach agraph.map.json "Projection Gateway" void:docs/projection-gateway.md \
  --role contract \
  --heading "Projection Gateway"
```

Read attached docs for one target:

```sh
agraph docs for "Projection Gateway" --project void --map agraph.map.json
```

Audit stale or missing docs:

```sh
agraph docs audit --project void --map agraph.map.json
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

Start with `agraph context` for the task question. Follow `drilldowns` only when
the packet is insufficient. During build or maintenance work, promote useful
candidate docs into accepted map attachments and run `agraph docs audit` before
handoff.

## Graph Cursors

`agraph cursor` is the progressive-disclosure query path for longer agent work.
It stores a stable graph basis and returns small JSON packets that can be opened,
expanded, searched, and revisited without dumping the full graph.

```sh
agraph cursor create "projection gateway connections" --project void --map agraph.map.json
agraph cursor open cursor:abc123 "Projection Gateway"
agraph cursor expand cursor:def456 "Projection Gateway"
agraph cursor docs cursor:def456 "Projection Gateway"
agraph cursor search cursor:def456 "spacetime websocket"
```

Cursor packet schema is `agraph.cursor.packet/v1`. Each mutating cursor command
creates a new immutable revision with a parent cursor id. If `--map` is used,
AGraph stores the parsed map overlay inside the cursor row, so later edits to
`agraph.map.json` do not change older cursor revisions.
