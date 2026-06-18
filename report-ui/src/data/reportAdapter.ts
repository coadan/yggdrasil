import type { AGraphReport, CountRow } from "./types";

export function fileKindRows(report: AGraphReport): CountRow[] {
  return (
    report.evidence.topFileKinds ||
    report.evidence.top_file_kinds ||
    []
  );
}

export function nodeKindRows(report: AGraphReport): CountRow[] {
  return (
    report.evidence.topNodeKinds ||
    report.evidence.top_node_kinds ||
    []
  );
}

export function edgeRelationRows(report: AGraphReport): CountRow[] {
  return (
    report.evidence.topEdgeRelations ||
    report.evidence.top_edge_relations ||
    []
  );
}

export function numericCount(report: AGraphReport, key: string): number {
  const value = report.evidence.counts[key];
  return typeof value === "number" ? value : 0;
}
