# System Map Overlay

AGraph separates mechanical extraction from agent-maintained system meaning.

The extractor can regenerate facts at any time: files, symbols, imports,
manifests, URLs, ports, routes, config values, and candidate system nodes. Those
facts are useful evidence, but they are not expected to be perfectly classified
for every repo shape.

`agraph.map.json` is the durable correction layer. It is versionable JSON that
an agent or human can update while doing normal coding work.

## Workflow

Start from generated candidates:

```sh
agraph project index project.edn
agraph project infer project.edn
agraph map propose project.edn --out agraph.map.json
```

Then accept or correct what research proves:

```sh
agraph map set-kind agraph.map.json "services/projection_gateway" service
agraph map include agraph.map.json "Projection Gateway" void:services/projection_gateway
agraph map reject agraph.map.json external-api docs.xtdb.com --reason "Documentation reference"
agraph docs attach agraph.map.json "Projection Gateway" void:docs/projection-gateway.md --role contract --heading "Projection Gateway"
agraph map explain agraph.map.json "Projection Gateway"
```

System graph exports apply `agraph.map.json` automatically when it exists in the
current directory. Use `--map PATH` to choose another file or `--no-map` to see
raw generated candidates.

```sh
agraph graph systems --project void --out .dev/reports/void-systems.html
agraph graph export systems --project void --out .dev/reports/void-systems.json
agraph graph export systems --project void --no-map --out .dev/reports/raw-systems.json
```

## Shape

```json
{
  "schema": "agraph.map/v1",
  "project": "void",
  "systems": [
    {
      "id": "system:void:manual:projection-gateway",
      "label": "Projection Gateway",
      "kind": "service",
      "includes": [{"repo": "void", "path": "services/projection_gateway"}],
      "aliases": ["projection-gateway", "projection_gateway"],
      "status": "accepted",
      "reason": "Rust HTTP/SSE gateway consumed by clients/web"
    }
  ],
  "reject": [
    {
      "match": {"kind": "external-api", "host": "docs.xtdb.com"},
      "reason": "Documentation reference, not a runtime dependency"
    }
  ],
  "edges": [
    {
      "source": "system:void:manual:clients-web",
      "target": "system:void:manual:projection-gateway",
      "relation": "calls-http",
      "confidence": 1.0,
      "reason": "Browser client consumes gateway HTTP/SSE endpoint"
    }
  ],
  "docs": [
    {
      "target": "system:void:manual:projection-gateway",
      "role": "contract",
      "source": {
        "repo": "void",
        "path": "docs/projection-gateway.md",
        "heading": "Projection Gateway"
      },
      "status": "accepted",
      "reason": "Defines the gateway boundary and client contract"
    }
  ]
}
```

`systems[].includes` merge generated graph nodes into the accepted system node.
Edges attached to the generated nodes are rewired to the accepted node.

`reject[]` removes known false positives from the system graph, including
incident edges.

`edges[]` adds accepted project-level relationships discovered during agent
research.

`docs[]` attaches reviewed Markdown snippets to graph targets. `agraph context`
prioritizes accepted attachments, resolves them back to indexed heading chunks,
and omits or truncates snippets to stay inside the requested token budget.

## Maintain

Agents should maintain the map while working. If a task reveals a better system
boundary, a stale external API node, an orphaned system, or a missing connection,
update `agraph.map.json` in the same slice as the code or docs change.

The map does not need to be perfect. It should be correct where the agent has
done enough research to know the answer, and explicit about rejected mistakes so
future regenerations do not reintroduce the same false positives.

Doc attachments are maintenance data too. Run `agraph docs audit --project ID
--map agraph.map.json` to find stale attachments and accepted systems without
reviewed docs.
