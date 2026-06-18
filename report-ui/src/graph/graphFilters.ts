import type { AGraphEdge, AGraphGraph, AGraphNode } from "../data/types";

export type GraphFilters = {
  query: string;
  kind: string;
  relation: string;
  cluster: string;
};

export type FilterOption = {
  value: string;
  label: string;
};

export type FilteredGraph = {
  graph: AGraphGraph;
  visibleNodes: number;
  visibleEdges: number;
};

const emptyFilters: GraphFilters = {
  query: "",
  kind: "",
  relation: "",
  cluster: ""
};

function asText(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function includesNeedle(values: unknown[], needle: string): boolean {
  if (!needle) return true;
  return values.some((value) => asText(value).toLowerCase().includes(needle));
}

function nodeClusterValues(node: AGraphNode): string[] {
  return [node.clusterId, node.cluster_id, node.clusterLabel, node.cluster_label].filter(Boolean) as string[];
}

function nodeClusterOption(node: AGraphNode): FilterOption | null {
  const value = node.clusterId || node.cluster_id || node.clusterLabel || node.cluster_label;
  if (!value) return null;
  return {
    value,
    label: node.clusterLabel || node.cluster_label || value
  };
}

function clusterLabel(cluster: NonNullable<AGraphGraph["clusters"]>[number]): string {
  return asText(cluster.label || cluster.sourceLabel || cluster.source_label || cluster.id || "cluster");
}

function clusterValue(cluster: NonNullable<AGraphGraph["clusters"]>[number]): string {
  return asText(cluster.id || cluster.label || cluster.sourceLabel || cluster.source_label);
}

function uniqueSorted(values: string[]): string[] {
  return Array.from(new Set(values.filter(Boolean))).sort((a, b) => a.localeCompare(b));
}

function nodeMatchesText(node: AGraphNode, needle: string): boolean {
  return includesNeedle(
    [
      node.id,
      node.label,
      node.kind,
      node.repo,
      node.repoId,
      node.repo_id,
      node.path,
      node.ecosystem,
      node.packageName,
      node.package_name,
      node.tags,
      node.attrs
    ],
    needle
  );
}

function edgeMatchesText(edge: AGraphEdge, needle: string): boolean {
  return includesNeedle(
    [
      edge.id,
      edge.source,
      edge.target,
      edge.relation,
      edge.path,
      edge.packageName,
      edge.package_name,
      edge.confidence,
      edge.attrs
    ],
    needle
  );
}

function nodeMatchesFacet(node: AGraphNode, filters: GraphFilters): boolean {
  if (filters.kind && node.kind !== filters.kind) return false;
  if (filters.cluster && !nodeClusterValues(node).includes(filters.cluster)) return false;
  return true;
}

export function normalizeFilters(filters: Partial<GraphFilters>): GraphFilters {
  return {
    ...emptyFilters,
    ...filters,
    query: (filters.query || "").trim().toLowerCase()
  };
}

export function graphFilterOptions(graph: AGraphGraph): {
  kinds: string[];
  relations: string[];
  clusters: FilterOption[];
} {
  const clusterRows = (graph.clusters || [])
    .map((cluster) => ({ value: clusterValue(cluster), label: clusterLabel(cluster) }))
    .filter((row) => row.value);
  const nodeClusterRows = graph.nodes.flatMap((node) => {
    const option = nodeClusterOption(node);
    return option ? [option] : [];
  });

  const clustersByValue = new Map<string, FilterOption>();
  for (const row of [...clusterRows, ...nodeClusterRows]) {
    if (!clustersByValue.has(row.value)) clustersByValue.set(row.value, row);
  }

  return {
    kinds: uniqueSorted(graph.nodes.map((node) => node.kind || "unknown")),
    relations: uniqueSorted(graph.edges.map((edge) => edge.relation || "edge")),
    clusters: Array.from(clustersByValue.values()).sort((a, b) => a.label.localeCompare(b.label))
  };
}

export function filterGraph(graph: AGraphGraph, rawFilters: Partial<GraphFilters>): FilteredGraph {
  const filters = normalizeFilters(rawFilters);
  const edgeTextMatches = new Set(
    graph.edges.filter((edge) => edgeMatchesText(edge, filters.query)).flatMap((edge) => [edge.source, edge.target])
  );

  const nodes = graph.nodes.filter((node) => {
    if (!nodeMatchesFacet(node, filters)) return false;
    if (!filters.query) return true;
    return nodeMatchesText(node, filters.query) || edgeTextMatches.has(node.id);
  });
  const nodeIds = new Set(nodes.map((node) => node.id));
  const edges = graph.edges.filter((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) return false;
    if (filters.relation && edge.relation !== filters.relation) return false;
    if (!filters.query) return true;
    return edgeMatchesText(edge, filters.query) || nodeIds.has(edge.source) || nodeIds.has(edge.target);
  });

  return {
    graph: {
      ...graph,
      nodes,
      edges
    },
    visibleNodes: nodes.length,
    visibleEdges: edges.length
  };
}
