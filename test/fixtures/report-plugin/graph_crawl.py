#!/usr/bin/env python3
import json
import sys


packet = json.load(sys.stdin)
graphs = packet.get("graphs", {})
overview = graphs.get("overview", {})
systems = graphs.get("systems", {})

overview_nodes = overview.get("nodes", [])
overview_edges = overview.get("edges", [])
systems_nodes = systems.get("nodes", [])
systems_edges = systems.get("edges", [])

first_node = overview_nodes[0] if overview_nodes else {}

json.dump(
    {
        "schema": "agraph.report-plugin.result/v1",
        "panels": [
            {
                "id": "graph-crawl",
                "label": "Graph Crawl",
                "slot": "plugins",
                "order": 10,
                "mdx": "## Graph Crawl\n\n<MetricGrid dataKey=\"metrics\" />\n\n<DataTable dataKey=\"rows\" />",
                "data": {
                    "metrics": [
                        {"label": "Overview Nodes", "value": len(overview_nodes)},
                        {"label": "Overview Edges", "value": len(overview_edges)},
                        {"label": "System Nodes", "value": len(systems_nodes)},
                        {"label": "System Edges", "value": len(systems_edges)},
                    ],
                    "rows": {
                        "columns": [
                            {"key": "key", "label": "Key"},
                            {"key": "value", "label": "Value"},
                        ],
                        "rows": [
                            {
                                "key": "first-overview-node",
                                "value": first_node.get("label") or first_node.get("id") or "",
                            }
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
