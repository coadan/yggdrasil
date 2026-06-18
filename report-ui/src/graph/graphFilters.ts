import type { AGraphEdge, AGraphGraph, AGraphNode } from "../data/types";

export type GraphFilters = {
  query: string;
  kind: string;
  relation: string;
  cluster: string;
  externalApiMode: "show" | "group" | "hide";
  minDegree: string;
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
  cluster: "",
  externalApiMode: "show",
  minDegree: "0"
};

const externalApiKind = "external-api";
const externalApiGroupKind = "external-api-group";

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

function edgeDirection(sourceExternal: boolean): "inbound" | "outbound" {
  return sourceExternal ? "inbound" : "outbound";
}

function stableGroupId(value: string): string {
  return `external-api-group:${value.replace(/[^A-Za-z0-9_.:-]+/g, "_").slice(0, 180)}`;
}

function graphDegree(edges: AGraphEdge[]): Map<string, number> {
  const degree = new Map<string, number>();
  for (const edge of edges) {
    degree.set(edge.source, (degree.get(edge.source) || 0) + 1);
    degree.set(edge.target, (degree.get(edge.target) || 0) + 1);
  }
  return degree;
}

function minimumDegree(filters: GraphFilters): number {
  const parsed = Number(filters.minDegree);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function groupExternalApis(graph: AGraphGraph): AGraphGraph {
  const nodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  const externalIds = new Set(graph.nodes.filter((node) => node.kind === externalApiKind).map((node) => node.id));
  if (externalIds.size === 0) return graph;

  type Group = {
    direction: "inbound" | "outbound";
    peerId: string;
    relation: string;
    externalIds: Set<string>;
    edges: AGraphEdge[];
  };

  const groups = new Map<string, Group>();
  const edges: AGraphEdge[] = [];

  for (const edge of graph.edges) {
    const sourceExternal = externalIds.has(edge.source);
    const targetExternal = externalIds.has(edge.target);
    if (!sourceExternal && !targetExternal) {
      edges.push(edge);
      continue;
    }
    if (sourceExternal && targetExternal) {
      continue;
    }

    const direction = edgeDirection(sourceExternal);
    const peerId = sourceExternal ? edge.target : edge.source;
    const externalId = sourceExternal ? edge.source : edge.target;
    const relation = edge.relation || "edge";
    const key = `${direction}:${peerId}:${relation}`;
    const group =
      groups.get(key) ||
      ({
        direction,
        peerId,
        relation,
        externalIds: new Set<string>(),
        edges: []
      } satisfies Group);
    group.externalIds.add(externalId);
    group.edges.push(edge);
    groups.set(key, group);
  }

  const groupedNodes: AGraphNode[] = [];
  const groupedEdges: AGraphEdge[] = [];

  for (const [key, group] of groups) {
    const groupId = stableGroupId(key);
    const externalLabels = Array.from(group.externalIds)
      .map((id) => nodeById.get(id)?.label || id)
      .sort((a, b) => a.localeCompare(b));
    const count = externalLabels.length;
    groupedNodes.push({
      id: groupId,
      label: `${count} external APIs`,
      kind: externalApiGroupKind,
      color: "#be123c",
      size: Math.min(34, 10 + count * 2),
      virtual: true,
      attrs: {
        direction: group.direction,
        peerId: group.peerId,
        relation: group.relation,
        collapsedEdges: group.edges.length,
        externalApis: externalLabels
      }
    });
    groupedEdges.push({
      id: `${group.peerId}->${groupId}:${group.relation}:${group.direction}`,
      source: group.direction === "outbound" ? group.peerId : groupId,
      target: group.direction === "outbound" ? groupId : group.peerId,
      relation: group.relation,
      color: "#be123c",
      virtual: true,
      attrs: {
        collapsedEdges: group.edges.length,
        collapsedNodes: count,
        externalApis: externalLabels
      }
    });
  }

  return {
    ...graph,
    nodes: [...graph.nodes.filter((node) => node.kind !== externalApiKind), ...groupedNodes],
    edges: [...edges, ...groupedEdges]
  };
}

function hideExternalApis(graph: AGraphGraph): AGraphGraph {
  const externalIds = new Set(graph.nodes.filter((node) => node.kind === externalApiKind).map((node) => node.id));
  if (externalIds.size === 0) return graph;
  return {
    ...graph,
    nodes: graph.nodes.filter((node) => !externalIds.has(node.id)),
    edges: graph.edges.filter((edge) => !externalIds.has(edge.source) && !externalIds.has(edge.target))
  };
}

function applyExternalApiMode(graph: AGraphGraph, mode: GraphFilters["externalApiMode"]): AGraphGraph {
  if (mode === "hide") return hideExternalApis(graph);
  if (mode === "group") return groupExternalApis(graph);
  return graph;
}

function applyMinimumDegree(graph: AGraphGraph, minDegree: number): AGraphGraph {
  if (minDegree <= 0) return graph;
  const degree = graphDegree(graph.edges);
  const nodes = graph.nodes.filter((node) => (degree.get(node.id) || 0) >= minDegree);
  const nodeIds = new Set(nodes.map((node) => node.id));
  return {
    ...graph,
    nodes,
    edges: graph.edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
  };
}

export function normalizeFilters(filters: Partial<GraphFilters>): GraphFilters {
  return {
    ...emptyFilters,
    ...filters,
    query: (filters.query || "").trim().toLowerCase(),
    externalApiMode: filters.externalApiMode || emptyFilters.externalApiMode,
    minDegree: filters.minDegree || emptyFilters.minDegree
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
  const transformed = applyMinimumDegree(
    applyExternalApiMode(
      {
        ...graph,
        nodes,
        edges
      },
      filters.externalApiMode
    ),
    minimumDegree(filters)
  );

  return {
    graph: transformed,
    visibleNodes: transformed.nodes.length,
    visibleEdges: transformed.edges.length
  };
}
