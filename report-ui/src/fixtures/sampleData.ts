import type { AGraphGraph, AGraphReport } from "../data/types";

export const fixtureGraph: AGraphGraph = {
  schema: "agraph.graph/v2",
  title: "Fixture Graph",
  nodes: [
    {
      id: "node:a",
      label: "app.core",
      kind: "namespace",
      clusterId: "cluster:runtime",
      clusterLabel: "Runtime",
      color: "#2563eb",
      size: 12
    },
    {
      id: "node:b",
      label: "db.core",
      kind: "namespace",
      clusterId: "cluster:data",
      clusterLabel: "Data",
      color: "#16a34a",
      size: 12
    },
    {
      id: "package:xtdb",
      label: "xtdb",
      kind: "package",
      ecosystem: "maven",
      clusterId: "cluster:data",
      clusterLabel: "Data",
      color: "#9333ea",
      size: 10
    }
  ],
  edges: [
    { id: "edge:a-b", source: "node:a", target: "node:b", relation: "imports", color: "#2563eb" },
    { id: "edge:b-xtdb", source: "node:b", target: "package:xtdb", relation: "declares", color: "#9333ea" }
  ],
  clusters: [
    { id: "cluster:runtime", label: "Runtime" },
    { id: "cluster:data", label: "Data" }
  ]
};

export const inventoryGraph: AGraphGraph = {
  ...fixtureGraph,
  title: "Inventory Fixture Graph",
  nodes: [
    ...fixtureGraph.nodes,
    {
      id: "route:checkout",
      label: "/checkout",
      kind: "route",
      clusterId: "cluster:runtime",
      clusterLabel: "Runtime"
    },
    {
      id: "url:payments",
      label: "https://payments.example.test",
      kind: "url",
      clusterId: "cluster:runtime",
      clusterLabel: "Runtime"
    },
    {
      id: "config:env",
      label: ".env.example",
      kind: "config",
      tags: ["config"]
    },
    {
      id: "auth:service-account",
      label: "checkout-service-account",
      kind: "service-account",
      tags: ["auth"]
    },
    {
      id: "artifact:generated-client",
      label: "generated/graphql-client.ts",
      kind: "generated-artifact"
    }
  ],
  edges: [
    ...fixtureGraph.edges,
    { id: "edge:a-route", source: "node:a", target: "route:checkout", relation: "defines-route" },
    { id: "edge:a-url", source: "node:a", target: "url:payments", relation: "references-url" },
    { id: "edge:a-config", source: "node:a", target: "config:env", relation: "references-config" },
    { id: "edge:config-auth", source: "config:env", target: "auth:service-account", relation: "references-auth" },
    { id: "edge:a-artifact", source: "node:a", target: "artifact:generated-client", relation: "generates" }
  ]
};

export const emptyGraph: AGraphGraph = {
  schema: "agraph.graph/v2",
  title: "Empty Graph",
  nodes: [],
  edges: []
};

export const denseGraph: AGraphGraph = {
  schema: "agraph.graph/v2",
  title: "Dense Fixture Graph",
  nodes: Array.from({ length: 8 }, (_, index) => ({
    id: `node:${index}`,
    label: `module.${index}`,
    kind: index % 2 === 0 ? "namespace" : "file",
    clusterId: index < 4 ? "cluster:left" : "cluster:right",
    clusterLabel: index < 4 ? "Left" : "Right"
  })),
  edges: Array.from({ length: 12 }, (_, index) => ({
    id: `edge:${index}`,
    source: `node:${index % 8}`,
    target: `node:${(index + 1) % 8}`,
    relation: index % 3 === 0 ? "imports" : "references"
  })),
  clusters: [
    { id: "cluster:left", label: "Left" },
    { id: "cluster:right", label: "Right" }
  ]
};

const externalApiNodes = Array.from({ length: 20 }, (_, index) => ({
  id: `external:${index}`,
  label: `api-${index}.example.test`,
  kind: "external-api",
  color: "#be123c"
}));

export const externalApiHeavyGraph: AGraphGraph = {
  schema: "agraph.graph/v2",
  title: "External API Fixture",
  nodes: [
    {
      id: "system:checkout",
      label: "checkout",
      kind: "candidate-system",
      color: "#2563eb"
    },
    ...externalApiNodes
  ],
  edges: externalApiNodes.map((node, index) => ({
    id: `edge:checkout-external-${index}`,
    source: "system:checkout",
    target: node.id,
    relation: "calls-external-api",
    color: "#be123c"
  }))
};

export const fixtureReport: AGraphReport = {
  schema: "agraph.report/v2",
  project: { id: "fixture", name: "Fixture", detail: "primary", mapPath: "agraph.map.json" },
  repos: [{ id: "app", root: "/tmp/app", role: "application" }],
  evidence: {
    schema: "agraph.evidence/v2",
    available: ["source-files", "file-facts", "source-graph", "docs", "system-evidence", "system-graph"],
    families: [
      { family: "source-files", status: "weak", counts: { files: 12, "skipped-files": 0, diagnostics: 0 } },
      { family: "file-facts", status: "available", counts: { "file-facts": 9 } },
      { family: "source-graph", status: "available", counts: { nodes: 8, edges: 11 } },
      { family: "docs", status: "available", counts: { chunks: 4, "search-docs": 4 } },
      { family: "system-evidence", status: "available", counts: { "system-evidence": 7 } },
      { family: "system-graph", status: "available", counts: { "system-nodes": 2, "system-edges": 1 } }
    ],
    counts: {
      files: 12,
      "file-facts": 9,
      nodes: 8,
      edges: 11,
      chunks: 4,
      "search-docs": 4,
      "system-evidence": 7,
      "system-nodes": 2,
      "system-edges": 1,
      packages: 2,
      diagnostics: 0,
      "skipped-files": 0,
      "package-evidence-gaps": 1,
      "package-conflicts": 0,
      "unresolved-imports": 1
    },
    kinds: {
      "file-facts": [
        { kind: "url", count: 3 },
        { kind: "env-var", count: 2 },
        { kind: "auth-reference", count: 1 },
        { kind: "route", count: 1 },
        { kind: "port", count: 1 },
        { kind: "container-image-producer", count: 1 }
      ],
      "system-evidence": [
        { kind: "url", count: 2 },
        { kind: "auth-reference", count: 1 },
        { kind: "container-image-producer", count: 1 }
      ],
      "source-graph": {
        nodes: [{ value: "namespace", count: 4 }],
        edges: [{ value: "imports", count: 3 }]
      },
      "source-files": {
        files: [{ value: "clojure", count: 8 }]
      }
    },
    freshness: {
      status: "stale",
      counts: {
        indexed: 9,
        current: 12,
        changed: 2,
        missing: 1,
        unindexed: 3
      },
      repos: [
        {
          "repo-id": "app",
          status: "stale",
          counts: {
            indexed: 9,
            current: 12,
            changed: 2,
            missing: 1,
            unindexed: 3
          },
          samples: {
            changed: [{ "repo-id": "app", path: "src/app/core.clj" }],
            missing: [{ "repo-id": "app", path: "src/app/deleted.clj" }],
            unindexed: [{ "repo-id": "app", path: "Makefile", reason: "unsupported-extension" }]
          }
        }
      ]
    },
    state: {
      freshness: {
        status: "stale",
        counts: {
          indexed: 9,
          current: 12,
          changed: 2,
          missing: 1,
          unindexed: 3
        }
      },
      diagnostics: { total: 0 },
      "dependency-health": {
        "package-evidence-gaps": 1,
        "package-conflicts": 0,
        "unresolved-imports": 1
      }
    },
    topFileKinds: [{ value: "clojure", count: 8 }],
    topNodeKinds: [{ value: "namespace", count: 4 }],
    topEdgeRelations: [{ value: "imports", count: 3 }]
  },
  graphs: {
    overview: { nodes: 2, edges: 1, artifact: "graph.json" },
    systems: { nodes: 2, edges: 1, artifact: "systems.json" }
  },
  packages: {
    counts: {
      packages: 2,
      versions: 2,
      "imports-package": 1,
      "unresolved-imports": 1,
      "version-conflicts": 0,
      "declared-without-import-evidence": 1
    },
    ecosystems: [{ ecosystem: "maven", packages: 2, versions: 2, imports: 1 }],
    "unresolved-imports": [{ import: "missing.lib", path: "src/app/core.clj", line: 12, kind: "clojure" }],
    "declared-without-import-evidence": [{ label: "unused-lib", ecosystem: "maven", "package-name": "unused/lib" }]
  },
  maintenance: {
    "external-api-review": {
      counts: {
        nodes: 20,
        edges: 20,
        "source-fanouts": 1,
        "single-evidence-nodes": 20,
        "support-only-nodes": 0
      },
      "source-fanouts": [
        {
          id: "external-api-review:checkout",
          peer: { "xt/id": "system:checkout", label: "checkout" },
          relation: "calls-external-api",
          visibility: "primary",
          "target-count": 20
        }
      ]
    },
    "decision-queue": [
      {
        kind: "external-api",
        severity: "review",
        target: "api-0.example.test",
        reason: "single evidence external API candidate"
      }
    ]
  },
  "plugin-packages": {
    counts: {
      packages: 1,
      warnings: 1,
      errors: 0,
      unbenchmarked: 1
    },
    packages: [
      {
        id: "datastar-hiccup",
        version: "0.1.0",
        scope: { kind: "project-local" },
        "benchmark-status": "unbenchmarked",
        "claim-authority": {
          status: "non-authoritative",
          "public-claims?": false,
          blockers: [{ code: "unbenchmarked", message: "Unbenchmarked package output is useful for review but non-authoritative." }]
        },
        warnings: ["datastar-hiccup is unbenchmarked"],
        "diagnose-command": "agraph plugin diagnose .dev/agraph/plugins/cache/datastar-hiccup --json"
      }
    ]
  },
  plugins: {
    schema: "agraph.report.plugins/v1",
    panels: [
      {
        id: "core-atlas-summary",
        label: "Project Atlas",
        slot: "atlas",
        order: 0,
        mdx: "## Project Atlas\n\n<MetricGrid dataKey=\"metrics\" />",
        data: {
          metrics: [
            { label: "Files", value: 12 },
            { label: "Nodes", value: 8 },
            { label: "Edges", value: 11 },
            { label: "Systems", value: 2 },
            { label: "Packages", value: 2 }
          ]
        },
        plugin: {
          id: "agraph-core-report",
          version: "1",
          authority: "core"
        }
      },
      {
        id: "fixture-graph-crawl",
        label: "Fixture Graph Crawl",
        slot: "plugins",
        order: 10,
        mdx: "## Fixture Graph Crawl\n\n<Callout dataKey=\"summary\" />\n\n<GraphCrawl dataKey=\"crawl\" />\n\n<ActionList dataKey=\"actions\" />\n\n<DataTable dataKey=\"rows\" />\n\n<CommandList dataKey=\"commands\" />",
        data: {
          summary: "Plugin output can be shaped from graph traversal evidence.",
          crawl: {
            metrics: [
              { label: "Sources", value: 1 },
              { label: "Nodes", value: 3 },
              { label: "Edges", value: 2 }
            ],
            seeds: [{ label: "checkout", kind: "candidate-system" }],
            sources: [{ source: "systems.json", path: "src/app/plugin_crawl.clj", line: 18 }],
            edges: [
              { source: "checkout", relation: "calls", target: "flows-api" },
              { source: "checkout", relation: "publishes", target: "events-worker" }
            ]
          },
          actions: [
            {
              id: "fixture-review-checkout",
              kind: "plugin-review",
              label: "Inspect checkout plugin crawl",
              description: "Review the plugin-selected graph crawl before accepting the system boundary.",
              source: "fixture-report-plugin.graph-crawl",
              tab: "systems",
              graphSliceId: "system-neighborhood",
              command: "agraph ask \"what owns checkout?\" --project fixture --json",
              question: "What should I inspect in the checkout plugin crawl?",
              evidenceRows: [{ source: "systems.json", nodes: 3, edges: 2, path: "src/app/core.clj" }]
            }
          ],
          commands: ["agraph ask \"what owns checkout?\" --project fixture --json"],
          rows: {
            columns: [
              { key: "source", label: "Source" },
              { key: "nodes", label: "Nodes" },
              { key: "edges", label: "Edges" },
              { key: "path", label: "Path" },
              { key: "line", label: "Line" },
              { key: "neighbors", label: "Neighbors" }
            ],
            rows: [
              {
                source: "systems.json",
                nodes: 3,
                edges: 2,
                path: "src/app/plugin_crawl.clj",
                line: 18,
                neighbors: [
                  { label: "flows-api", kind: "candidate-system" },
                  { label: "events-worker", kind: "candidate-system" }
                ]
              }
            ]
          }
        },
        plugin: {
          id: "fixture-report-plugin",
          version: "0.1.0",
          authority: "project-plugin"
        }
      }
    ],
    diagnostics: [
      {
        plugin: { id: "fixture-report-plugin" },
        stage: "fixture",
        message: "Fixture diagnostic"
      }
    ],
    artifacts: [
      {
        id: "fixture-plugin-artifact",
        label: "Fixture crawl packet",
        path: ".dev/reports/fixture/graph-crawl.json",
        kind: "json",
        plugin: { id: "fixture-report-plugin" }
      }
    ]
  },
  commands: [
    "agraph sync project.edn --check --map agraph.map.json",
    "agraph report project.edn --map agraph.map.json --out agraph-out",
    "agraph packages --project fixture --json",
    "agraph ask \"where is this handled?\" --project fixture --json",
    "agraph sync work list --project fixture",
    "agraph sync work complete <work-id> --result result.json",
    "agraph sync work apply <work-id> --map agraph.map.json"
  ]
};

export const emptyReport: AGraphReport = {
  schema: "agraph.report/v2",
  project: { id: "empty", name: "Empty Project", detail: "primary" },
  repos: [],
  evidence: {
    schema: "agraph.evidence/v2",
    available: [],
    counts: {
      files: 0,
      nodes: 0,
      edges: 0,
      packages: 0,
      diagnostics: 0
    },
    topFileKinds: [],
    topNodeKinds: [],
    topEdgeRelations: []
  },
  graphs: {
    overview: { nodes: 0, edges: 0, artifact: "graph.json" },
    systems: { nodes: 0, edges: 0, artifact: "systems.json" }
  },
  commands: []
};

export const sourceDocsSystemReport: AGraphReport = {
  ...fixtureReport,
  project: { id: "source-docs", name: "Source Docs System", detail: "expanded" },
  evidence: {
    ...fixtureReport.evidence,
    available: ["source-graph", "docs", "systems", "map-overlay"],
    counts: {
      ...fixtureReport.evidence.counts,
      files: 21,
      nodes: 18,
      edges: 26,
      "search-docs": 7
    }
  }
};

export const packageFocusedDepsGraph: AGraphGraph = {
  schema: "agraph.graph/v2",
  title: "Package: xtdb",
  nodes: [
    { id: "package:xtdb", label: "xtdb", kind: "package", ecosystem: "maven", color: "#9333ea" },
    { id: "manifest:deps", label: "deps.edn", kind: "manifest", path: "deps.edn", color: "#0f766e" },
    {
      id: "namespace:store",
      label: "agraph.xtdb",
      kind: "namespace",
      path: "src/agraph/xtdb.clj",
      color: "#2563eb"
    }
  ],
  edges: [
    { id: "manifest-xtdb", source: "manifest:deps", target: "package:xtdb", relation: "declares" },
    { id: "store-xtdb", source: "namespace:store", target: "package:xtdb", relation: "imports" }
  ]
};
