import type { YggReport, CountRow } from "./types";

export function fileKindRows(report: YggReport): CountRow[] {
  return (
    report.evidence.topFileKinds ||
    report.evidence.top_file_kinds ||
    []
  );
}

export function nodeKindRows(report: YggReport): CountRow[] {
  return (
    report.evidence.topNodeKinds ||
    report.evidence.top_node_kinds ||
    []
  );
}

export function edgeRelationRows(report: YggReport): CountRow[] {
  return (
    report.evidence.topEdgeRelations ||
    report.evidence.top_edge_relations ||
    []
  );
}

export function numericCount(report: YggReport, key: string): number {
  const value = report.evidence.counts[key];
  return typeof value === "number" ? value : 0;
}
