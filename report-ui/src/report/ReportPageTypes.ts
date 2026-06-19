export type ReportTab = "dashboard" | "ask" | "systems" | "dependencies" | "evidence" | "maintenance" | "plugins";

export type TableColumn = {
  key: string;
  label: string;
};

export type AskAnswer = {
  title: string;
  summary: string[];
  evidence: Array<Record<string, unknown>>;
  related: Array<Record<string, unknown>>;
  relatedTitle: string;
};

export type AskScope = {
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
  { id: "ask", label: "Ask" },
  { id: "systems", label: "Systems" },
  { id: "dependencies", label: "Dependencies" },
  { id: "evidence", label: "Evidence" },
  { id: "maintenance", label: "Maintenance" },
  { id: "plugins", label: "Plugins" }
];

export function isReportTab(value: string): value is ReportTab {
  return tabs.some((tab) => tab.id === value);
}
