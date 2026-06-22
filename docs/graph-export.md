# Graph Export

Yggdrasil has one maintained graph data contract: `ygg.graph/v2`.

Create it with:

```sh
ygg view overview --format json --out graph.json
ygg view deps my.namespace --format json --depth 2 --out deps.json
ygg view query "where is auth handled" --format json --retriever lexical --out query.json
ygg view systems --project my-project --format json --out systems.json
ygg view systems --project my-project --format json --detail expanded --out systems-expanded.json
ygg view systems --project my-project --format json --map ygg.map.json --out systems.json
ygg view systems --project my-project --format json --no-map --out raw-systems.json
ygg view systems --project my-project --format json --view view:runtime --out runtime.json
```

The top-level shape is:

```json
{
  "schema": "ygg.graph/v2",
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
  `external-api`; package nodes use `external-package` and exact version nodes
  use `external-package-version`
- `repo`: repo id when known
- `repoRole`: project repo role when known
- `path`, `pathPrefix`, `line`: source location hints when known
- `ecosystem`, `packageName`, `versionRange`, `resolvedVersion`,
  `dependencyScope`, `importNames`: package details when the node represents an
  external package declaration or lockfile resolution
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
- `ecosystem`, `packageName`, `versionRange`, `resolvedVersion`,
  `dependencyScope`, `importName`, `resolutionSource`: package evidence and
  resolver provenance on dependency edges when known
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

Generated report viewers consume this canonical graph shape and transform it
internally for their renderer. External tools should consume `ygg.graph/v2`
rather than depending on Cytoscape, Sigma, or another renderer-specific format.

`ygg report <project.edn> --map ygg.map.json --out ygg-out` writes a
local report bundle for humans and agents. The bundle includes `index.html`,
`report.json`, `REPORT.mdx`, `graph.json`, `systems.json`, and
`context-example.json`. `index.html` is the unified report and graph viewer.
`report.json` is the structured report packet. `graph.json` and `systems.json`
use this same `ygg.graph/v2` contract. `REPORT.mdx` is readable narrative
source, not a data contract.

`ygg view ... --format html --out systems.html` uses the same packaged
viewer in graph mode. It writes `systems.html`, sibling `systems.assets/`, and
`systems.graph.json`. The HTML embeds a boot packet for local opening, while the
sibling JSON remains the inspectable `ygg.graph/v2` artifact. Use
`--format json` when another tool needs only the graph contract.

## Package Reports

Use `ygg packages --project <id> [--repo <id>] [--ecosystem npm|cargo|go]
[--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N]
[--json]` when the task is package/dependency inventory rather than a graph
slice. The report folds manifest declarations, selected lockfile resolutions,
mechanical source-import-to-package evidence, and unresolved source imports into
package-focused rows. It is a dependency report, not a replacement for
`ygg.graph/v2`.
Generated project report data includes compact package diagnostics for declared
packages without import evidence, unresolved imports, and version conflicts so
agents can triage dependency gaps before opening the full package report.
The report atlas also carries `next-actions` rows with concrete `command`
strings for dependency triage, source coverage diagnostics, external API review,
and queued maintenance work.

Use `ygg view deps <package-label> --project <id>` to export or render the
graph slice around a package. For external package nodes, the slice is
package-focused: manifests that declare the package, exact lockfile version
nodes, lockfiles that resolved those versions, and source namespaces with import
evidence.

## Metadata

Metadata is stored in XTDB and participates in the same temporal reads as graph
facts. Built-in keys cover ownership, runtime, security, risk, agent notes, and
documentation attachments. Additional definitions can be stored later without
changing the graph export schema.

CLI commands:

```sh
ygg sync meta defs
ygg sync meta set <target-id> owner/team platform --type string --project my-project
ygg sync meta get <target-id> --project my-project
ygg sync meta unset <target-id> owner/team --project my-project
ygg sync view list --project my-project
ygg sync view show view:platform --project my-project
```

Stored graph views can filter exports with metadata and relation constraints.
Use `--view <id-or-label>` on `ygg view ...` commands to apply a stored view.

## System Graph Maps

System graph exports apply `ygg.map.json` from the current directory when it
exists. The map is an agent-maintained correction layer that merges accepted systems,
rejects known false positives, and adds accepted system relationships before the
canonical graph is written. Use `--no-map` to export generated candidates
without the map and `--detail raw` to export raw relation-level edges.
Raw candidates are evidence-derived structure, not final architecture labels.
Consumers should treat accepted corrections and metadata as the place where
project-specific semantics enter the graph.

## Compatibility

- Additive nullable fields may be added within `ygg.graph/v2`.
- Renaming or changing the meaning of existing fields requires a new schema id.
- Renderer-specific state should stay out of the export contract.
- New file types should extend the extractor layer while continuing to emit this
  canonical shape.
- Generated report data includes compact `coverage.extractors` and
  `coverage.extractor-fingerprints` rows so agents can audit source-kind
  coverage, extractor versions, and persisted extractor/indexer basis ids
  without opening the full coverage artifact.
- `ygg sync coverage <project.edn> --json` includes bounded `samples` on
  `skipped-by-extension` and `skipped-by-reason` rows. Use those concrete paths
  to decide whether a missing file kind needs extractor support, suite scope
  adjustment, or should remain unsupported.
- `ygg.graph/v1` consumers should ignore or migrate to the v2 metadata and
  basis fields.
