# System Map Corrections

AGraph separates mechanical extraction from agent-maintained system meaning.
The raw graph should emerge from concrete facts; the map records the meaning
that cannot be safely hard-coded for every project.

The extractor can regenerate facts at any time: files, symbols, imports,
manifests, URLs, ports, routes, config values, container image producer/consumer
evidence from manifests and shell scripts, and candidate system nodes. Those
facts are useful evidence, but they are not expected to be perfectly classified
for every repo shape.

Mechanical extraction should stay bounded and project-agnostic: parsers,
manifests, dependency edges, route/URL evidence, container image artifact
matches, topology, and metrics. When the question becomes semantic, such as
which candidates are one system, which edge is a runtime API dependency, or which
subsystem matters for a use case, put the answer in `agraph.map.json`. Humans or
LLM-backed tools can make those judgments from evidence without turning AGraph
into a pile of path-name rules or text matching.

`agraph.map.json` is the durable correction layer. It is versionable JSON that
an agent or human can update while doing normal coding work.

## Workflow

Start from generated candidates:

```sh
agraph sync add-repo project.edn /path/to/repo --repo app --role application
agraph sync project.edn
agraph sync propose project.edn --out agraph.map.json
```

Then accept or correct what research proves:

```sh
agraph sync set-kind "services/api-gateway" service --map agraph.map.json
agraph sync include "API Gateway" app:services/api-gateway --map agraph.map.json
agraph sync ignore external-api docs.xtdb.com --map agraph.map.json --reason "Documentation reference"
agraph sync package import org.slf4j maven:org.slf4j:slf4j-api --map agraph.map.json --reason "slf4j-api exports org.slf4j"
agraph sync docs attach "API Gateway" app:docs/api-gateway.md --map agraph.map.json --role contract --heading "API Gateway"
agraph sync explain "API Gateway" --map agraph.map.json
```

System graph exports apply `agraph.map.json` automatically when it exists in the
current directory. Use `--map PATH` to choose another file or `--no-map` to see
raw generated candidates.

```sh
agraph view systems --project sample --out .dev/reports/sample-systems.html
agraph view systems --project sample --format json --out .dev/reports/sample-systems.json
agraph view systems --project sample --format json --no-map --out .dev/reports/raw-systems.json
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
  ],
  "packageImports": [
    {
      "import": "org.slf4j",
      "ecosystem": "maven",
      "package": "org.slf4j:slf4j-api",
      "status": "accepted",
      "reason": "Maven coordinate exports this Java package root"
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

`docs[]` attaches reviewed Markdown snippets to graph targets. `agraph ask --json`
prioritizes accepted attachments, resolves them back to indexed heading chunks,
and omits or truncates snippets to stay inside the requested token budget.

`packageImports[]` records accepted import-prefix to external-package mappings
when manifests cannot prove the relationship. This is required for JVM package
roots and any other ecosystem where package coordinates do not mechanically map
to source import names.

## Maintain

Agents should maintain the map while working. Start with
`agraph sync check --json --map agraph.map.json` and address the ranked
decision queue. The report includes a graph-basis hash, scale/noise ratios, top
hubs, fold-in actions, and `decision-summary` counts by severity, kind, and
recommended action so an agent can triage the queue before opening individual
decision packets. A
check/enqueue sync uses the graph-maintenance index profile by default, which
updates files, graph rows, diagnostics, and bounded file facts without writing
code/doc search chunks. Add `--query-index` when the same run should also
refresh searchable chunks for `agraph ask`, `agraph explore`, embeddings, and
context packets. Use those signals to make small corrections as work reveals
them, rather than trying to classify the whole project. Use `--map
agraph.map.json` when reviewing after corrections so rejected systems and
incident edges stay out of the maintenance queue.

If a task reveals a better system boundary, a stale external API node, an
orphaned system, a noisy visible edge, a missing connection, or a repo that
should be part of the project, update `agraph.map.json` through `agraph sync`
commands or run `agraph sync add-repo` in the same slice as the code or docs
change.

If semantic help is needed, classify one decision at a time:

```sh
agraph classify decision maintenance-decision:abc123 --project sample
agraph sync check project.edn --map agraph.map.json --enqueue
agraph sync work pull --project sample --agent codex
agraph sync work complete queue:abc123 --result result.json
agraph sync work apply queue:abc123 --map agraph.map.json
```

Do not classify the whole system graph. The classifier receives one decision
bundle and should return a proposed map patch. Use `--enqueue` when the decision
should be picked up from `.dev/agraph/queue/ready` by a different agent, model,
or review process. Queue summaries include the bounded decision target,
graph-basis hash, and allowed patch actions so agents can choose one item
without loading the whole graph.

Infrastructure gaps use the same queue. `sync check --enqueue` can emit
`agraph.infra.review-packet/v1` items when mechanical evidence finds bounded
producer/consumer fact gaps, such as a container image build fact with no known
deployment consumer. The agent returns `agraph.infra.review-result/v1`.
`sync work complete` records that result; `sync work apply` validates that patch
operations reference only ids and evidence from the packet before adding reviewed
edges to `agraph.map.json`.

The map does not need to be perfect. It should be correct where the agent has
done enough research to know the answer, and explicit about rejected mistakes so
future regenerations do not reintroduce the same false positives.

Doc attachments are maintenance data too. Run `agraph sync docs audit --project ID
--map agraph.map.json` to find stale attachments and accepted systems without
reviewed docs.
