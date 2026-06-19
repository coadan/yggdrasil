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
  project: { id: "fixture", name: "Fixture", detail: "primary" },
  repos: [{ id: "app", root: "/tmp/app", role: "application" }],
  evidence: {
    schema: "agraph.evidence/v1",
    available: ["source-graph", "docs", "systems"],
    counts: {
      files: 12,
      nodes: 8,
      edges: 11,
      packages: 2,
      diagnostics: 0
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
        mdx: "## Fixture Graph Crawl\n\n<Callout dataKey=\"summary\" />\n\n<DataTable dataKey=\"rows\" />",
        data: {
          summary: "Plugin output can be shaped from graph traversal evidence.",
          rows: {
            columns: [
              { key: "source", label: "Source" },
              { key: "nodes", label: "Nodes" },
              { key: "edges", label: "Edges" },
              { key: "neighbors", label: "Neighbors" }
            ],
            rows: [
              {
                source: "systems.json",
                nodes: 3,
                edges: 2,
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
    artifacts: []
  },
  commands: [
    "agraph sync project.edn --check --map agraph.map.json",
    "agraph packages --project fixture --json",
    "agraph ask \"where is this handled?\" --project fixture --json"
  ]
};

export const emptyReport: AGraphReport = {
  schema: "agraph.report/v2",
  project: { id: "empty", name: "Empty Project", detail: "primary" },
  repos: [],
  evidence: {
    schema: "agraph.evidence/v1",
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
