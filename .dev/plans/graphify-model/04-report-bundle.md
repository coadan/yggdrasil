# Stage 4: Report Bundle

Status: implemented.

## Goal

Add Graphify-style easy artifacts without making them the core contract.
AGraph should emit a simple local bundle for humans and sharing, while keeping
`agraph.graph/v2` as the single maintained graph data contract.

## Target Command

```text
agraph report <project.edn> [--map agraph.map.json] [--out agraph-out] [--detail primary|expanded|evidence|raw]
```

Default output:

```text
agraph-out/
  index.html
  REPORT.md
  graph.json
  systems.json
  context-example.json
```

Keep `.dev/reports/` as the default for internal generated reports. Use
`agraph-out/` only for explicit report bundles.

## Report Contents

`REPORT.md` should include:

- project id and basis
- repo list
- file coverage summary
- system graph summary
- top hubs by mechanical degree and salience
- visible system connections
- orphaned candidates
- maintenance queue summary
- suggested AGraph commands

It should not include:

- broad semantic claims not backed by map corrections
- invented architecture narratives
- full graph dumps
- LLM-written conclusions from unbounded context

`index.html` should:

- consume `agraph.graph/v2` or the existing JSON exports
- link to `REPORT.md`
- identify generated time and basis
- work offline with vendored Cytoscape

## Data Contract

The bundle can contain multiple files, but the canonical graph file remains:

```json
{"schema": "agraph.graph/v2", "...": "..."}
```

Renderer-specific state must stay in the HTML wrapper or separate presentation
metadata, not in `agraph.graph/v2`.

## Implementation Areas

- Add `agraph.report` namespace.
- Reuse existing `graph/write-html!` and graph export functions.
- Reuse coverage and maintenance report summaries.
- Add a Markdown renderer for compact report output.
- Add CLI dispatch under `report`.

## Tests

- Generate a report from `test/fixtures/project-repo`.
- Assert expected files are present.
- Assert `graph.json` schema is `agraph.graph/v2`.
- Assert `REPORT.md` contains commands and counts, not raw graph rows.
- Assert report generation is deterministic enough for shape tests.
- Assert `--out` cannot accidentally target source files without explicit force.

## Non-Goals

- Do not create another graph export schema.
- Do not generate Obsidian/wiki output yet.
- Do not add Mermaid call-flow generation until system edges are stronger.
- Do not run LLM summarization inside report generation.

## Done Criteria

`agraph report project.edn --map agraph.map.json --out agraph-out` creates a
small offline bundle that a human can inspect, while downstream tools can still
consume `agraph.graph/v2`.
