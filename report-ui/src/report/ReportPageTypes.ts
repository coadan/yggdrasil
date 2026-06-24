export type ReportTab = "dashboard" | "query" | "systems" | "dependencies" | "evidence" | "maintenance" | "plugins";

export type TableColumn = {
  key: string;
  label: string;
};

export type QueryAnswer = {
  title: string;
  summary: string[];
  evidence: Array<Record<string, unknown>>;
  related: Array<Record<string, unknown>>;
  relatedTitle: string;
};

export type QueryScope = {
  label: string;
  source: string;
  question: string;
  evidenceRows?: Array<Record<string, unknown>>;
};

export type ActionTarget = {
  tab: ReportTab;
  graphSliceId?: string;
};

export const tabs: Array<{ id: ReportTab; label: string }> = [
  { id: "dashboard", label: "Dashboard" },
  { id: "query", label: "Query" },
  { id: "systems", label: "Systems" },
  { id: "dependencies", label: "Dependencies" },
  { id: "evidence", label: "Evidence" },
  { id: "maintenance", label: "Maintenance" },
  { id: "plugins", label: "Plugins" }
];

export function isReportTab(value: string): value is ReportTab {
  return tabs.some((tab) => tab.id === value);
}
