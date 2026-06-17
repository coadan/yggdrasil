# Graph Export

AGraph has one maintained graph data contract: `agraph.graph/v2`.

Create it with:

```sh
agraph view overview --format json --out graph.json
agraph view deps my.namespace --format json --depth 2 --out deps.json
agraph view query "where is auth handled" --format json --retriever lexical --out query.json
agraph view systems --project my-project --format json --out systems.json
agraph view systems --project my-project --format json --detail expanded --out systems-expanded.json
agraph view systems --project my-project --format json --map agraph.map.json --out systems.json
agraph view systems --project my-project --format json --no-map --out raw-systems.json
agraph view systems --project my-project --format json --view view:runtime --out runtime.json
```

The top-level shape is:

```json
{
  "schema": "agraph.graph/v2",
  "title": "Query: where is auth handled",
  "basis": {
    "project-id": "my-project",
    "validAt": "2026-06-16T10:00:00Z"
  },
  "metadataDefs": [],
  "clusters": [],
  "nodes": [],
  "edges": []
}
```

`basis` records the project/repo and temporal read options that shaped the
export. Historical exports should pass `--valid-at`.

`metadataDefs` lists the metadata definitions used by the exported rows. Each
definition has a stable `key`, human label, target kinds in `applies-to`,
`value-type`, `cardinality`, and query/display hints.

Node rows use stable, renderer-neutral fields:

- `id`: graph node id
- `label`: display label
- `kind`: node kind, for example `namespace`, `function`, `service`, or
  `external-api`
- `repo`: repo id when known
- `repoRole`: project repo role when known
- `path`, `pathPrefix`, `line`: source location hints when known
- `degree`: degree within the exported slice
- `score`: query relevance score when present
- `attrs`: custom scalar or multi-value metadata, keyed by metadata key
- `tags`: metadata tags derived from tag values and true boolean flags
- `metrics`: numeric metadata, keyed by metadata key
- `clusterId`, `clusterLabel`, `clusterRank`: discovered system cluster fields
  when exporting semantic system graphs
- `color`, `size`: presentation hints, not identity

Edge rows use:

- `id`: graph edge id
- `source`, `target`: node ids
- `relation`: relation kind
- `confidence`: relation confidence when known
- `rules`, `evidence`: compact provenance hints when known
- `path`, `line`: source location hints when known
- `attrs`: custom scalar or multi-value metadata, keyed by metadata key
- `tags`: metadata tags derived from tag values and true boolean flags
- `metrics`: numeric metadata, keyed by metadata key
- `salience`: semantic connection importance score when exporting system graphs
- `visibility`: `primary`, `secondary`, `supporting`, or `noise`
- `evidenceCounts`: relation/evidence counts folded into the semantic edge
- `relations`: raw relation kinds represented by the semantic edge
- `salienceReasons`: compact scoring adjustments
- `color`: presentation hint, not identity

System graph exports default to `--detail primary`, which hides weak and noisy
connections. This is the large-project default for agents and visualizers. Use
`--detail expanded` for drilldown, `--detail evidence` to see all semantic
bundles including noise, and `--detail raw` to bypass salience and export raw
relation-level system edges. `clusters[]` is present for semantic system exports
and summarizes discovered node groups plus bridge counts.

Generated HTML graph reports consume this canonical graph shape and transform it
internally for Cytoscape.js. External tools should consume `agraph.graph/v2`
rather than depending on Cytoscape element JSON or another renderer-specific
format.

`agraph report <project.edn> --map agraph.map.json --out agraph-out` writes a
local report bundle for humans and agents. The bundle includes `index.html`,
`REPORT.md`, `graph.json`, `systems.json`, and `context-example.json`.
`graph.json` and `systems.json` use this same `agraph.graph/v2` contract.
`REPORT.md` is a readable summary, not a data contract.

## Metadata

Metadata is stored in XTDB and participates in the same temporal reads as graph
facts. Built-in keys cover ownership, runtime, security, risk, agent notes, and
documentation attachments. Additional definitions can be stored later without
changing the graph export schema.

CLI commands:

```sh
agraph sync meta defs
agraph sync meta set <target-id> owner/team platform --type string --project my-project
agraph sync meta get <target-id> --project my-project
agraph sync meta unset <target-id> owner/team --project my-project
agraph sync view list --project my-project
agraph sync view show view:platform --project my-project
```

Stored graph views can filter exports with metadata and relation constraints.
Use `--view <id-or-label>` on `agraph view ...` commands to apply a stored view.

## System Graph Maps

System graph exports apply `agraph.map.json` from the current directory when it
exists. The map is an agent-maintained correction layer that merges accepted systems,
rejects known false positives, and adds accepted system relationships before the
canonical graph is written. Use `--no-map` to export generated candidates
without the map and `--detail raw` to export raw relation-level edges.
Raw candidates are evidence-derived structure, not final architecture labels.
Consumers should treat accepted corrections and metadata as the place where
project-specific semantics enter the graph.

## Compatibility

- Additive nullable fields may be added within `agraph.graph/v2`.
- Renaming or changing the meaning of existing fields requires a new schema id.
- Renderer-specific state should stay out of the export contract.
- New file types should extend the extractor layer while continuing to emit this
  canonical shape.
- `agraph.graph/v1` consumers should ignore or migrate to the v2 metadata and
  basis fields.
