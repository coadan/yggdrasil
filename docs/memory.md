# Project Memory

Yggdrasil memory is an XTDB-backed evidence plane for durable coding-agent
context. It stores reviewed preferences, lessons, gotchas, workflow notes, and
decision rationale as auditable rows tied to a project and, when possible,
concrete graph targets.

Memory does not have a separate query surface in V1. Reviewed and observed
memory enters normal `ygg query --json` packets in the `memories` field and is
also available to MCP clients through the default `ygg_query` tool.

## Read Memory

Use the normal query path:

```sh
ygg query "where is the release checklist" --project sample --json
```

The context packet includes matching memory rows:

```json
{
  "schema": "ygg.context/v1",
  "memories": [
    {
      "id": "memory:sample:...",
      "kind": "lesson",
      "scope": "project",
      "visibility": "project",
      "status": "reviewed",
      "summary": "Use scoped tests for routine work.",
      "targetIds": ["file:sample:app:test/ygg/memory_test.clj"],
      "basis": ["lexical"]
    }
  ]
}
```

Reviewed and observed memory rows maintain `target-kind: memory` search docs, so
lexical query sees them immediately and `ygg embed` can index them for semantic
retrieval. Suggested rows are excluded from broad query until promoted, unless
they match through direct graph attachment in the context packet.

## Write Memory

Add memory with a graph target when possible:

```sh
ygg memory add --project sample \
  --target file:sample:app:test/ygg/memory_test.clj \
  --kind lesson \
  --text "Prefer scoped tests unless broad shared contracts changed."
```

Project-wide memory is allowed when there is no honest concrete target:

```sh
ygg memory add --project sample \
  --scope project \
  --text "Release QA should run the V1 smoke script before handoff."
```

New memory defaults to `scope=developer`, `visibility=private`, and
`status=suggested`. Human-reviewed rows can be written directly with
`--reviewed`, or promoted later:

```sh
ygg memory review --project sample --status suggested --json
ygg memory accept memory:sample:abc --project sample --reason "Confirmed in release QA."
ygg memory reject memory:sample:def --project sample --reason "Outdated after the workflow changed."
ygg memory supersede memory:sample:old --project sample --text "Updated wording." --reason "Policy changed."
ygg memory attach memory:sample:abc --project sample --target system:query --reason "Applies to query packets."
```

## Scope And Privacy

- `developer` memory is private to the matching owner by default.
- `project` memory is shared inside the project store and defaults to project
  visibility.
- `repo` memory scopes a row to one repo in a multi-repo project.
- Private developer memory content is not part of normal report bundles; reports
  may expose aggregate memory counts.
- V1 memory lives in XTDB. There is no portable memory sidecar, import/export
  command, hard-delete MCP tool, or standalone memory-search MCP tool.

## What To Remember

Record memory when the fact is durable, useful for future agent work, and either
reviewed or explicitly marked as suggested. Good candidates include user
preferences, project workflow quirks, repeated false paths, release rules, and
lessons tied to a graph target.

Do not store secrets, task-local scratch, unverified semantic guesses, or facts
that are already easy to retrieve from code or docs.
