import Graph from "graphology";
import type { AGraphEdge, AGraphGraph } from "./types";

export type GraphLayout = "circle" | "grid";

export function edgeKey(edge: AGraphEdge): string {
  return edge.id || `${edge.source}->${edge.target}:${edge.relation || "edge"}`;
}

function nodePosition(index: number, total: number, layout: GraphLayout): { x: number; y: number } {
  if (layout === "grid") {
    const columns = Math.max(1, Math.ceil(Math.sqrt(total)));
    return {
      x: (index % columns) * 12,
      y: Math.floor(index / columns) * 12
    };
  }

  const angle = total <= 1 ? 0 : (Math.PI * 2 * index) / total;
  const radius = Math.max(8, Math.sqrt(total) * 8);
  return {
    x: Math.cos(angle) * radius,
    y: Math.sin(angle) * radius
  };
}

export function toGraphology(data: AGraphGraph, layout: GraphLayout = "circle"): Graph {
  const graph = new Graph({ multi: true, type: "directed" });

  for (const [index, node] of (data.nodes || []).entries()) {
    if (!graph.hasNode(node.id)) {
      const position = nodePosition(index, data.nodes.length, layout);
      graph.addNode(node.id, {
        ...node,
        label: node.label || node.id,
        kind: node.kind || "unknown",
        repo: node.repo || node.repoId || node.repo_id,
        path: node.path,
        x: position.x,
        y: position.y,
        size: node.size || 8,
        color: node.color || "#64748b"
      });
    }
  }

  for (const edge of data.edges || []) {
    if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) {
      graph.addDirectedEdgeWithKey(
        edgeKey(edge),
        edge.source,
        edge.target,
        {
          ...edge,
          label: edge.relation || "edge",
          relation: edge.relation,
          color: edge.color || "#94a3b8"
        }
      );
    }
  }

  return graph;
}
