# Report Plugins

Report plugins extend the generated operator dashboard. They are separate from
extractor plugins: extractor plugins add graph facts during ingestion, while
report plugins run after report generation and decide how to present, rank, or
combine already exported evidence for humans.

Report plugins are configured in `project.edn`:

```clojure
{:id "my-project"
 :repos [{:id "app" :root "."}]
 :report-plugins
 [{:id "planning-panel"
   :version "0.1.0"
   :command ["python3" "tools/report_panels.py"]
   :slots [:plugins :systems]
   :timeout-ms 10000}]}
```

AGraph starts each plugin during `bb report`, writes a JSON packet to stdin, and
expects a JSON result on stdout. Core AGraph dashboard panels use the same panel
contract and are included in the same `report.plugins` registry.

Report plugins can also be distributed as git-shared plugin packages alongside
extractor plugins. See [plugin-packages.md](plugin-packages.md) for manifest,
install, pinning, and ecosystem rules.

## Input

Plugins receive `agraph.report-plugin.input/v1`:

```json
{
  "schema": "agraph.report-plugin.input/v1",
  "project": {"id": "my-project", "name": "My Project", "path": "project.edn"},
  "generatedAtMs": 12345,
  "plugin": {"id": "planning-panel", "version": "0.1.0"},
  "report": {"schema": "agraph.report/v2"},
  "graphs": {
    "overview": {"schema": "agraph.graph/v2", "nodes": [], "edges": []},
    "systems": {"schema": "agraph.graph/v2", "nodes": [], "edges": []}
  },
  "coverage": {},
  "maintenance": {},
  "evidence": {},
  "packages": {},
  "pluginPackages": {
    "counts": {"packages": 1, "warnings": 1, "unbenchmarked": 1},
    "packages": [
      {
        "id": "datastar-hiccup",
        "benchmark-status": "unbenchmarked",
        "warnings": ["datastar-hiccup is unbenchmarked"]
      }
    ]
  },
  "artifacts": {}
}
```

The `graphs` field intentionally contains the generated graph exports, not just
compact counts. A plugin may traverse nodes, edges, clusters, attributes, and
neighborhoods in its own way when a project needs a different report surface.

## Output

Plugins return `agraph.report-plugin.result/v1`:

```json
{
  "schema": "agraph.report-plugin.result/v1",
  "panels": [
    {
      "id": "graph-crawl",
      "label": "Graph Crawl",
      "slot": "plugins",
      "order": 10,
      "mdx": "## Graph Crawl\n\n<MetricGrid dataKey=\"metrics\" />",
      "data": {
        "metrics": [{"label": "Nodes", "value": 42}]
      }
    }
  ],
  "diagnostics": [],
  "artifacts": []
}
```

Panel MDX is rendered by the report UI with a safe allowlist:

- `MetricGrid dataKey="..."`
- `DataTable dataKey="..."`
- `ActionList dataKey="..."`
- `CommandList dataKey="..."`
- `Callout dataKey="..."`
- `KeyValueTable dataKey="..."`

The browser does not execute plugin JavaScript. Unsupported expressions are
shown as skipped content so report plugins can fail visibly without taking over
the dashboard runtime.

AGraph normalizes plugin results before they reach the UI. `panels`,
`diagnostics`, and `artifacts` must be arrays. Malformed arrays or malformed
rows are converted into plugin diagnostics while valid sibling rows are still
kept in the bundle.

Every normalized panel, diagnostic, and artifact includes a plugin summary. For
packaged plugins that summary carries package id, version, pinned revision, and
manifest fingerprint along with benchmark status, so report output remains tied
to the installed package contract.

## Minimal Graph Crawl Plugin

This plugin reads the graph export from stdin and emits one panel:

```python
#!/usr/bin/env python3
import json
import sys

packet = json.load(sys.stdin)
systems = packet["graphs"]["systems"]
nodes = systems.get("nodes", [])
edges = systems.get("edges", [])

by_kind = {}
for node in nodes:
    kind = node.get("kind") or "unknown"
    by_kind[kind] = by_kind.get(kind, 0) + 1

json.dump(
    {
        "schema": "agraph.report-plugin.result/v1",
        "panels": [
            {
                "id": "systems-by-kind",
                "label": "Systems by Kind",
                "slot": "systems",
                "order": 20,
                "mdx": "## Systems by Kind\n\n<MetricGrid dataKey=\"metrics\" />\n\n<DataTable dataKey=\"rows\" />",
                "data": {
                    "metrics": [
                        {"label": "Nodes", "value": len(nodes)},
                        {"label": "Edges", "value": len(edges)},
                    ],
                    "rows": {
                        "columns": [
                            {"key": "kind", "label": "Kind"},
                            {"key": "count", "label": "Count"},
                        ],
                        "rows": [
                            {"kind": kind, "count": count}
                            for kind, count in sorted(by_kind.items())
                        ],
                    },
                },
            }
        ],
        "diagnostics": [],
        "artifacts": [],
    },
    sys.stdout,
)
```

## Slots

Known slots are `atlas`, `systems`, `dependencies`, `evidence`, `maintenance`,
and `plugins`. The dashboard renders all panels; detail tabs render project
plugin panels for their matching slot. The `plugins` tab shows non-core panels
and plugin diagnostics.

## Artifacts

`bb report` writes:

- `report.json`, including `report.plugins`
- `report-plugins.json`, containing only the plugin bundle
- `index.html`, which renders the report UI

`report.json` also includes `plugin-packages`: compact package summaries with
diagnostic counts, structured diagnostics, warnings, benchmark status, claim
authority, source pins, manifest fingerprints, and diagnose commands. Report
plugins receive the same data directly as `pluginPackages` and inside the full
report packet as `report.plugin-packages`.

The report UI renders plugin artifacts on the Plugins tab. Artifact rows are
treated as review inventory: common explicit reference fields such as `path`,
`file`, `url`, `artifact`, and `artifactPath` are copyable so operators can
move from a plugin decision surface to generated evidence quickly.

Report plugins are presentation and planning tools. They should emit panel data,
diagnostics, or artifacts for review. They should not mutate graph facts or
write accepted semantic corrections directly; accepted corrections still belong
in validated `agraph.map.json` flows.
