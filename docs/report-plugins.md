# Report Plugins

Report plugins extend the generated operator dashboard. They are separate from
extractor plugins: extractor plugins add graph facts during ingestion, while
report plugins run after report generation and decide how to present, rank, or
combine already exported evidence for humans.

Report plugins are configured in `project.edn`:

```clojure
{:id "my-project"
 :repos [{:id "app" :root "."}]
 :plugins
 [{:kind :report
   :id "planning-panel"
   :version "0.1.0"
   :command ["python3" "tools/report_panels.py"]
   :slots [:plugins :systems]
   :timeout-ms 10000}]}
```

Project-local report plugins default to `:benchmark-status :unbenchmarked`.
Supported statuses are `:unbenchmarked` and `:benchmarked`; unsupported values
fail config normalization.

Yggdrasil starts each plugin during `bb report`, writes a JSON packet to stdin, and
expects a JSON result on stdout. Core Yggdrasil dashboard panels use the same panel
contract and are included in the same `report.plugins` registry.

Use `bb plugin input report <package-dir> --json` to inspect the exact input
packet Yggdrasil will send to one selected package report plugin without executing
the plugin command.
Use `bb plugin gap report <package-dir> --json` when an agent needs the full
authoring packet: report input sample, output contract, proof commands,
benchmark caveats, and core-promotion requirements.

Report plugins can also be distributed as git-shared plugin packages alongside
extractor plugins. See [plugin-packages.md](plugin-packages.md) for manifest,
install, pinning, and ecosystem rules.

## Input

Plugins receive `ygg.report-plugin.input/v1`:

```json
{
  "schema": "ygg.report-plugin.input/v1",
  "project": {"id": "my-project", "name": "My Project", "path": "project.edn"},
  "generatedAtMs": 12345,
  "plugin": {"id": "planning-panel", "version": "0.1.0"},
  "report": {"schema": "ygg.report/v2"},
  "graphs": {
    "overview": {"schema": "ygg.graph/v2", "nodes": [], "edges": []},
    "systems": {"schema": "ygg.graph/v2", "nodes": [], "edges": []}
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

Plugins return `ygg.report-plugin.result/v1`:

```json
{
  "schema": "ygg.report-plugin.result/v1",
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

Yggdrasil normalizes plugin results before they reach the UI. `panels`,
`diagnostics`, and `artifacts` must be arrays. Malformed arrays or malformed
rows are converted into plugin diagnostics while valid sibling rows are still
kept in the bundle.

Every normalized panel, diagnostic, and artifact includes a plugin summary. For
packaged plugins that summary carries package id, version, package source,
pinned revision, manifest fingerprint, claim authority, and benchmark status, so
report output remains tied to the installed package contract.

Yggdrasil also stamps row-level provenance after parsing plugin output:

- `:provenance`
- `:plugin-id`, `:plugin-version`, `:plugin-fingerprint`, and
  `:plugin-authority`
- `:plugin-package-id`, `:plugin-package-version`, `:plugin-package-rev`, and
  `:plugin-package-manifest-fingerprint` for packaged plugins
- `:plugin-package-source` and `:plugin-package-claim-authority` for packaged
  plugins
- `:benchmark-status`

Plugin-provided values for these audit fields are ignored. The normalized
bundle uses the configured plugin and package metadata, which keeps report
panels, diagnostics, and artifacts auditable even when a plugin is experimental
or unbenchmarked.

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
        "schema": "ygg.report-plugin.result/v1",
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
- `report-plugins.json`, containing the plugin bundle
- `index.html`, which renders the report UI

`report.json` includes `report.plugins.packages`: compact package summaries
with diagnostic counts, structured diagnostics, warnings, benchmark status,
claim authority, source pins, manifest fingerprints, and diagnose commands.
Report plugins receive the same data directly as `pluginPackages` and inside
the full report packet as `report.plugins.packages`.
Packaged report plugin dry-runs use the same shape in their synthetic report
context, so package caveats are visible before generating a full dashboard.

The report UI renders plugin artifacts on the Plugins tab. Artifact rows are
treated as review inventory: common explicit reference fields such as `path`,
`file`, `url`, `artifact`, and `artifactPath` are copyable so operators can
move from a plugin decision surface to generated evidence quickly.

The Plugins tab also renders installed plugin package caveats from
`report.plugins.packages`: package counts, benchmark status, claim authority,
blockers, warnings, and diagnose commands. This makes unbenchmarked,
project-local, or otherwise non-authoritative package output visible without
opening raw report JSON.

Report plugins are presentation and planning tools. They should emit panel data,
diagnostics, or artifacts for review. They should not mutate graph facts or
write accepted semantic corrections directly; accepted corrections still belong
in validated correction-fact flows.
