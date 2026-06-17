# System Map Overlay

AGraph separates mechanical extraction from agent-maintained system meaning.
The raw graph should emerge from concrete facts; the map records the meaning
that cannot be safely hard-coded for every project.

The extractor can regenerate facts at any time: files, symbols, imports,
manifests, URLs, ports, routes, config values, and candidate system nodes. Those
facts are useful evidence, but they are not expected to be perfectly classified
for every repo shape.

Mechanical extraction should stay bounded and project-agnostic: parsers,
manifests, dependency edges, route/URL evidence, topology, and metrics. When the
question becomes semantic, such as which candidates are one system, which edge
is a runtime API dependency, or which subsystem matters for a use case, put the
answer in this overlay. Humans or LLM-backed tools can make those judgments from
evidence without turning AGraph into a pile of path-name rules or text matching.

`agraph.map.json` is the durable correction layer. It is versionable JSON that
an agent or human can update while doing normal coding work.

## Workflow

Start from generated candidates:

```sh
agraph project add-repo project.edn /path/to/repo --repo app --role application
agraph project index project.edn
agraph project infer project.edn
agraph map propose project.edn --out agraph.map.json
```

Then accept or correct what research proves:

```sh
agraph map set-kind agraph.map.json "services/api-gateway" service
agraph map include agraph.map.json "API Gateway" app:services/api-gateway
agraph map reject agraph.map.json external-api docs.xtdb.com --reason "Documentation reference"
agraph docs attach agraph.map.json "API Gateway" app:docs/api-gateway.md --role contract --heading "API Gateway"
agraph map explain agraph.map.json "API Gateway"
```

System graph exports apply `agraph.map.json` automatically when it exists in the
current directory. Use `--map PATH` to choose another file or `--no-map` to see
raw generated candidates.

```sh
agraph graph systems --project sample --out .dev/reports/sample-systems.html
agraph graph export systems --project sample --out .dev/reports/sample-systems.json
agraph graph export systems --project sample --no-map --out .dev/reports/raw-systems.json
```

## Shape

```json
{
  "schema": "agraph.map/v1",
  "project": "sample",
  "systems": [
    {
      "id": "system:sample:manual:api-gateway",
      "label": "API Gateway",
      "kind": "service",
      "includes": [{"repo": "app", "path": "services/api-gateway"}],
      "aliases": ["api-gateway"],
      "tags": ["runtime"],
      "lifecycle": "current",
      "status": "accepted",
      "reason": "HTTP gateway consumed by clients/web"
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
      "source": "system:sample:manual:clients-web",
      "target": "system:sample:manual:api-gateway",
      "relation": "calls-http",
      "visibility": "primary",
      "importance": "high",
      "confidence": 1.0,
      "reason": "Browser client consumes gateway HTTP endpoint"
    }
  ],
  "docs": [
    {
      "target": "system:sample:manual:api-gateway",
      "role": "contract",
      "source": {
        "repo": "app",
        "path": "docs/api-gateway.md",
        "heading": "API Gateway"
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
research. It can also override computed connection visibility/importance for a
matching `source`/`target`/`relation`.

`docs[]` attaches reviewed Markdown snippets to graph targets. `agraph context`
prioritizes accepted attachments, resolves them back to indexed heading chunks,
and omits or truncates snippets to stay inside the requested token budget.

## Maintain

Agents should maintain the map while working. Start with
`agraph project maintain --json` and address the ranked decision queue. The
report includes a graph-basis hash, scale/noise ratios, top hubs, and fold-in
actions. Use those signals to make small corrections as work reveals them,
rather than trying to classify the whole project.

If a task reveals a better system boundary, a stale external API node, an
orphaned system, a noisy visible edge, a missing connection, or a repo that
should be part of the project, update `agraph.map.json` or run `agraph project
add-repo` in the same slice as the code or docs change.

If semantic help is needed, classify one decision at a time:

```sh
agraph classify decision maintenance-decision:abc123 --project sample
```

Do not classify the whole system graph. The classifier receives one decision
bundle and should return a proposed map patch.

The map does not need to be perfect. It should be correct where the agent has
done enough research to know the answer, and explicit about rejected mistakes so
future regenerations do not reintroduce the same false positives.

Doc attachments are maintenance data too. Run `agraph docs audit --project ID
--map agraph.map.json` to find stale attachments and accepted systems without
reviewed docs.
