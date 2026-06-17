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
  "docs": [],
  "answerability": {
    "status": "limited",
    "available": ["source-graph", "docs"],
    "missing": ["embeddings", "system-graph"],
    "weak": [],
    "unsupported": ["activity", "remote-work", "session-history", "validation-history"],
    "counts": {"nodes": 120, "search-docs": 40, "embeddings": 0},
    "retrieval": {"requested": "auto", "effective": "lexical", "fallback?": true},
    "warnings": [],
    "next": []
  },
  "warnings": [],
  "drilldowns": []
}
```

`budget.estimated` is a cheap JSON-size token estimate. If snippets do not fit,
AGraph keeps the source reference and marks `snippetOmitted`; if even that does
not fit, the doc is omitted and a warning is added.

## Answerability

Every context packet reports `answerability`: a mechanical summary of which
evidence planes were available for the query and which were missing, weak, or
not supported by the current model.

- `status`: `ready`, `limited`, or `empty`
- `available`: populated evidence planes, such as `source-graph`, `docs`,
  `system-graph`, `embeddings`, or `map-overlay`
- `missing`: supported evidence planes with no useful rows for this project or
  read context
- `weak`: evidence exists, but did not match this query well
- `unsupported`: useful evidence planes AGraph cannot model yet, such as
  `activity`, `remote-work`, `session-history`, and `validation-history`
- `counts`: compact row counts used to make the decision
- `retrieval`: requested and effective retriever, including lexical fallback
- `warnings`: short mechanical explanations
- `next`: bounded follow-up commands

Agents should treat `answerability` as a confidence boundary. If the packet says
activity or session history is unsupported, AGraph can still answer source and
docs questions, but it cannot answer who worked on something, what prior
sessions tried, or which validation history exists unless that evidence appears
in indexed source/docs. If `status` is `empty` or `limited`, follow `next` or use
the listed missing planes to decide whether to sync, embed, inspect coverage, or
ask the user for another source of truth.

Plain `agraph ask` prints a concise answerability warning only when no query
results are found. Use `agraph ask --json` for the full structured packet.

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

## Filesystem Queue

Use `--enqueue` when a packet should be picked up by another agent, model, tool,
or human process. AGraph writes an `agraph.queue.item/v1` JSON file to
`.dev/agraph/queue/ready` and prints a compact receipt.

```sh
agraph sync check project.edn --map agraph.map.json --enqueue
agraph explore create "projection boundary" --project sample --enqueue
agraph sync work pull --project sample --agent codex
agraph sync work complete queue:abc123 --result result.json
agraph sync work apply queue:abc123 --map agraph.map.json
```

The queue is only the transport. The embedded payload remains unchanged, and the
consumer result should be an explicit JSON artifact such as a map patch,
classification, or finding. `sync work complete` stores the artifact for audit.
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

Explore currently uses the `agraph.cursor.packet/v1` packet schema internally.
Each mutating explore command
creates a new immutable revision with a parent cursor id. If `--map` is used,
AGraph stores the parsed map correction layer inside the cursor row, so later edits to
`agraph.map.json` do not change older cursor revisions.
