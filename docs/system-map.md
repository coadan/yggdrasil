# System Corrections

Yggdrasil separates mechanical extraction from agent-maintained system meaning.
The raw graph should emerge from concrete facts; correction facts record the
meaning that cannot be safely hard-coded for every project.

The extractor can regenerate facts at any time: files, symbols, imports,
manifests, URLs, ports, routes, config values, container image producer/consumer
evidence from manifests and shell scripts, and candidate system nodes. Those
facts are useful evidence, but they are not expected to be perfectly classified
for every repo shape.

Mechanical extraction should stay bounded and project-agnostic: parsers,
manifests, dependency edges, route/URL evidence, container image artifact
matches, topology, and metrics. When the question becomes semantic, such as
which candidates are one system, which edge is a runtime API dependency, or which
subsystem matters for a use case, record the answer through `ygg corrections` so it
lives as correction facts. Humans or LLM-backed tools can make those
judgments from evidence without turning Yggdrasil into a pile of path-name rules
or text matching.

File discovery is part of that mechanical boundary. Backends such as
`rg --files`, `git ls-files`, or filesystem walking enumerate candidate paths
only. Yggdrasil still owns ignored-path filtering, file kind detection, content
hashes, extractor selection, and canonical file rows; the discovery backend must
not classify system meaning or file semantics.

XTDB-backed correction facts are the durable correction layer. Write accepted
corrections through `ygg corrections` so validation, required reasons, audit history,
and normalization stay in one place. Query, view, and report surfaces consume a
resolved projection of raw graph facts plus correction facts.

Generated candidates do not belong in persisted corrections. `ygg corrections review`
prints a bounded review packet from the current graph and existing accepted
corrections, then exits without persisting generated candidate systems as
accepted project meaning.

## Workflow

Inspect generated candidates without persisting them as accepted meaning:

```sh
ygg sync add-repo project.edn /path/to/repo --repo app --role application
ygg sync project.edn
ygg corrections review --project sample --json
```

Then accept or correct what research proves:

```sh
ygg corrections accept system "services/api-gateway" --kind service --label "API Gateway" --include app:services/api-gateway --reason "Reviewed runtime gateway boundary" --project sample
ygg corrections set-kind "API Gateway" service --reason "Reviewed runtime gateway boundary" --project sample
ygg corrections include "API Gateway" app:services/api-gateway --reason "Reviewed source ownership" --project sample
ygg corrections reject external-api docs.xtdb.com --reason "Documentation reference" --project sample
ygg corrections package import org.slf4j maven:org.slf4j:slf4j-api --reason "slf4j-api exports org.slf4j" --project sample
ygg corrections docs attach "API Gateway" app:docs/api-gateway.md --role contract --heading "API Gateway" --reason "Reviewed API contract" --project sample
ygg corrections explain "API Gateway" --project sample
```

System graph exports include accepted correction facts by default. Use
`--detail raw` when you need raw relation-level evidence.

```sh
ygg view systems --project sample --out .dev/reports/sample-systems.html
ygg view systems --project sample --format json --out .dev/reports/sample-systems.json
```

## Correction Rows

Only accepted corrections are persisted. Generated candidates, provenance,
metrics, and evidence payloads from automatic review output remain review input
until a human or LLM-backed workflow accepts a bounded correction with a reason.

Accepted systems merge generated graph nodes into the accepted system node.
Edges attached to the generated nodes are rewired to the accepted node.

Rejected candidates remove known false positives from the system graph,
including incident edges.

Accepted edges add project-level relationships discovered during agent research.
They can also override computed connection visibility or importance for a
matching source, target, and relation.

Docs attachments connect reviewed Markdown snippets to graph targets.
`ygg query --json` prioritizes accepted attachments, resolves them back to
indexed heading chunks, and omits or truncates snippets to stay inside the
requested token budget.

Package import corrections record accepted import-prefix to external-package
mappings when manifests cannot prove the relationship. This is required for JVM
package roots and any other ecosystem where package coordinates do not
mechanically map to source import names.

## Maintain

Agents should maintain correction facts while working. Start with
`ygg sync --check --json --enqueue` and address the ranked decision queue. The
report includes a graph-basis hash, scale/noise ratios, top hubs, fold-in
actions, and `decision-summary` counts by severity, kind, and recommended
action so an agent can triage the queue before opening individual decision
packets. A check/enqueue sync uses the graph-maintenance index profile by
default, which updates files, graph rows, diagnostics, and bounded file facts
without writing code/doc search chunks. Add `--query-index` when the same run
should also refresh searchable chunks for `ygg query`, embeddings, and context
packets. Use those signals to make small corrections as work reveals them,
rather than trying to classify the whole project.

If a task reveals a better system boundary, a stale external API node, an
orphaned system, a noisy visible edge, a missing connection, or a repo that
should be part of the project, write a bounded correction fact through
`ygg corrections` or `ygg sync` commands in the same slice as the code or docs
change.

If semantic help is needed, classify one decision at a time:

```sh
ygg maintenance classify maintenance-decision:abc123 --project sample
ygg sync project.edn --check --enqueue
ygg sync work pull --project sample --agent codex
ygg sync work show queue:abc123
ygg sync work heartbeat queue:abc123 --agent codex --lease-minutes 30
ygg sync work complete queue:abc123 --result result.json
ygg sync work validate queue:abc123
```

Do not classify the whole system graph. The classifier receives one decision
bundle and should return a proposed correction. Use `--enqueue` when the decision
should be picked up from the central project queue, or `.ygg/queue/ready` when
no project can be resolved, by a different agent, model,
or review process. Queue summaries include the bounded decision target,
graph-basis hash, and allowed patch actions so agents can choose one item
without loading the whole graph.

Infrastructure gaps use the same queue. `sync check --enqueue` can emit
`ygg.infra.review-packet/v1` items when mechanical evidence finds bounded
producer/consumer fact gaps, such as a container image build fact with no known
deployment consumer. The agent returns `ygg.infra.review-result/v1`.
`sync work complete` records that result; `sync work validate` checks supported
result schemas without mutating correction facts. Accepted results should be
folded into XTDB-backed correction APIs.

Accepted corrections do not need to be perfect everywhere. They should be
correct where the agent has done enough research to know the answer, and
explicit about rejected mistakes so future regenerations do not reintroduce the
same false positives.

Doc attachments are maintenance data too. Run `ygg sync docs audit --project ID`
to find stale attachments and accepted systems without reviewed docs.
