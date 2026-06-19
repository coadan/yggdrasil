import type { AGraphEdge, AGraphGraph, AGraphNode } from "../data/types";

export type GraphSlice = {
  id: string;
  label: string;
  description: string;
  graph: AGraphGraph;
  priority: number;
};

const externalApiKind = "external-api";
const packageKinds = new Set(["package", "package-version", "manifest", "dependency"]);
const configAuthKinds = new Set([
  "auth",
  "config",
  "configuration",
  "deployment",
  "docker",
  "env",
  "kustomize",
  "manifest",
  "secret",
  "service-account"
]);
const packageRelations = new Set(["declares", "imports-package", "package-imports", "requires", "resolves"]);

function nodeKind(node: AGraphNode): string {
  return String(node.kind || "");
}

function nodeTags(node: AGraphNode): string[] {
  return Array.isArray(node.tags) ? node.tags.map(String) : [];
}

function hasConfigAuthEvidence(node: AGraphNode): boolean {
  const kind = nodeKind(node);
  if (configAuthKinds.has(kind)) return true;
  return nodeTags(node).some((tag) => configAuthKinds.has(tag));
}

function edgeTouches(edge: AGraphEdge, ids: Set<string>): boolean {
  return ids.has(edge.source) || ids.has(edge.target);
}

function subgraphByNodeIds(graph: AGraphGraph, nodeIds: Set<string>, title: string): AGraphGraph {
  const nodes = graph.nodes.filter((node) => nodeIds.has(node.id));
  const retained = new Set(nodes.map((node) => node.id));
  return {
    ...graph,
    title,
    nodes,
    edges: graph.edges.filter((edge) => retained.has(edge.source) && retained.has(edge.target))
  };
}

function subgraphFromEdges(graph: AGraphGraph, edges: AGraphEdge[], title: string): AGraphGraph {
  const nodeIds = new Set(edges.flatMap((edge) => [edge.source, edge.target]));
  const nodes = graph.nodes.filter((node) => nodeIds.has(node.id));
  const retained = new Set(nodes.map((node) => node.id));
  return {
    ...graph,
    title,
    nodes,
    edges: edges.filter((edge) => retained.has(edge.source) && retained.has(edge.target))
  };
}

function degreeMap(edges: AGraphEdge[]): Map<string, number> {
  const degree = new Map<string, number>();
  for (const edge of edges) {
    degree.set(edge.source, (degree.get(edge.source) || 0) + 1);
    degree.set(edge.target, (degree.get(edge.target) || 0) + 1);
  }
  return degree;
}

function topNeighborhood(graph: AGraphGraph): GraphSlice | null {
  if (graph.nodes.length === 0 || graph.edges.length === 0) return null;
  const degree = degreeMap(graph.edges);
  const top = [...graph.nodes]
    .filter((node) => node.kind !== externalApiKind)
    .sort((left, right) => (degree.get(right.id) || 0) - (degree.get(left.id) || 0))[0];
  if (!top) return null;
  const edges = graph.edges.filter((edge) => edge.source === top.id || edge.target === top.id);
  if (edges.length === 0) return null;

  return {
    id: "system-neighborhood",
    label: "System Neighborhood",
    description: `${top.label || top.id} plus direct graph neighbors.`,
    graph: subgraphFromEdges(graph, edges, `System Neighborhood: ${top.label || top.id}`),
    priority: 80 + edges.length
  };
}

function externalSurface(graph: AGraphGraph): GraphSlice | null {
  const externalIds = new Set(graph.nodes.filter((node) => node.kind === externalApiKind).map((node) => node.id));
  if (externalIds.size === 0) return null;
  const edges = graph.edges.filter((edge) => edgeTouches(edge, externalIds));
  if (edges.length === 0) return null;
  return {
    id: "external-surface",
    label: "External Surface",
    description: `${externalIds.size} external node(s) and their incident graph edges.`,
    graph: subgraphFromEdges(graph, edges, "External Surface"),
    priority: 100 + externalIds.size + edges.length
  };
}

function packageEvidence(graph: AGraphGraph): GraphSlice | null {
  const packageIds = new Set(
    graph.nodes
      .filter((node) => packageKinds.has(nodeKind(node)) || node.packageName || node.package_name || node.ecosystem)
      .map((node) => node.id)
  );
  const edges = graph.edges.filter(
    (edge) =>
      packageRelations.has(String(edge.relation || "")) ||
      Boolean(edge.packageName || edge.package_name) ||
      edgeTouches(edge, packageIds)
  );
  if (packageIds.size === 0 && edges.length === 0) return null;
  return {
    id: "package-evidence",
    label: "Package Evidence",
    description: "Package, manifest, version, and import evidence edges already present in the graph.",
    graph: edges.length > 0 ? subgraphFromEdges(graph, edges, "Package Evidence") : subgraphByNodeIds(graph, packageIds, "Package Evidence"),
    priority: 70 + packageIds.size + edges.length
  };
}

function configAuthEvidence(graph: AGraphGraph): GraphSlice | null {
  const ids = new Set(graph.nodes.filter(hasConfigAuthEvidence).map((node) => node.id));
  if (ids.size === 0) return null;
  const edges = graph.edges.filter((edge) => edgeTouches(edge, ids));
  return {
    id: "config-auth-evidence",
    label: "Config/Auth Evidence",
    description: "Explicit config, deployment, secret, service-account, or auth graph rows and neighbors.",
    graph: edges.length > 0 ? subgraphFromEdges(graph, edges, "Config/Auth Evidence") : subgraphByNodeIds(graph, ids, "Config/Auth Evidence"),
    priority: 60 + ids.size + edges.length
  };
}

export function graphSlices(graph: AGraphGraph): GraphSlice[] {
  return [externalSurface(graph), topNeighborhood(graph), packageEvidence(graph), configAuthEvidence(graph)]
    .filter((slice): slice is GraphSlice => Boolean(slice))
    .filter((slice) => slice.graph.nodes.length > 0)
    .sort((left, right) => right.priority - left.priority || left.label.localeCompare(right.label));
}
