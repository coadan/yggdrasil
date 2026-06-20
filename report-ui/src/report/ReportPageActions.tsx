import type { AGraphReport } from "../data/types";
import type { PluginPanelActions } from "./ReportPluginPanels";
import type { ReviewQueueRow } from "./reviewQueue";
import { displayValue } from "./valueFormat";
import { numericCell } from "./ReportPageShared";
import { isReportTab, type ActionTarget, type AskScope, type ReportTab, type TableColumn } from "./ReportPageTypes";

export type ReportActionCommand = {
  id: string;
  label: string;
  description: string;
  command: string;
  targetTab?: ReportTab;
};

export function firstCommandMatching(commands: string[], pattern: RegExp): string | undefined {
  return commands.find((command) => pattern.test(command));
}

function reportOperator(report: AGraphReport): Record<string, unknown> {
  return (report.operator && typeof report.operator === "object" ? report.operator : {}) as Record<string, unknown>;
}

function asActionRows(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.filter((row): row is Record<string, unknown> => row !== null && typeof row === "object") : [];
}

function actionRowsByCommand(rows: Array<Record<string, unknown>>): Array<Record<string, unknown>> {
  const seen = new Set<string>();
  return rows.filter((row) => {
    const key = displayValue(row.command || row.label || row.kind);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function operatorNextActionRows(report: AGraphReport, fallbackRows: Array<Record<string, unknown>> = []): Array<Record<string, unknown>> {
  const operatorRows = asActionRows(reportOperator(report)["next-actions"] || reportOperator(report).nextActions);
  return actionRowsByCommand(operatorRows.length > 0 ? operatorRows : fallbackRows);
}

export function enqueueWorkCommand(commands: string[]): string | undefined {
  const explicit = firstCommandMatching(commands, /\bsync\s+.*--check\b.*--enqueue\b|\bsync\s+.*--enqueue\b.*--check\b/);
  if (explicit) return explicit;
  const check = firstCommandMatching(commands, /\bsync\s+.*--check\b/);
  return check && !/\s--enqueue\b/.test(check) ? `${check} --enqueue` : check;
}

export function reportActionCommands(report: AGraphReport): ReportActionCommand[] {
  const commands = report.commands || [];
  const operatorRows = operatorNextActionRows(report);
  const operatorCommands = operatorRows.map((row) => displayValue(row.command)).filter((command): command is string => Boolean(command));
  const allCommands = [...commands, ...operatorCommands];
  const rows = [
    {
      id: "inspect-health",
      label: "Inspect health",
      description: "Open the current freshness, evidence-family, caveat, and next-action health packet.",
      command: firstCommandMatching(allCommands, /\bsync\s+inspect\b/),
      targetTab: "evidence" as const
    },
    {
      id: "regenerate-report",
      label: "Regenerate report",
      description: "Rebuild the static report from the same project config and map overlay.",
      command: firstCommandMatching(allCommands, /\breport\s+/)
    },
    {
      id: "refresh-graph",
      label: "Refresh graph basis",
      description: "Run sync checks against the indexed graph basis before trusting stale rows.",
      command: firstCommandMatching(allCommands, /\bsync\s+.*--check/),
      targetTab: "evidence" as const
    },
    {
      id: "enqueue-review-work",
      label: "Enqueue review work",
      description: "Turn current maintenance, dependency, and external review evidence into queue work items.",
      command: enqueueWorkCommand(allCommands),
      targetTab: "maintenance" as const
    },
    {
      id: "review-work-queue",
      label: "Review work queue",
      description: "Inspect queued maintenance or correction work before applying map changes.",
      command: firstCommandMatching(allCommands, /\bsync\s+work\s+list\b/),
      targetTab: "maintenance" as const
    },
    {
      id: "apply-completed-work",
      label: "Apply completed work",
      description: "Apply a validated work result into the map overlay.",
      command: firstCommandMatching(allCommands, /\bsync\s+work\s+apply\b/),
      targetTab: "maintenance" as const
    },
    {
      id: "ask-cli",
      label: "Ask with CLI",
      description: "Run the equivalent graph question through the CLI when report-local Ask is too narrow.",
      command: firstCommandMatching(allCommands, /\bask\s+/),
      targetTab: "ask" as const
    }
  ];

  const seen = new Set<string>();
  return rows.flatMap((row) => {
    if (!row.command || seen.has(row.command)) return [];
    seen.add(row.command);
    return [{ ...(row as ReportActionCommand), command: row.command }];
  });
}

export function ReportActions({
  report,
  copiedKey,
  onAsk,
  onCopyCommand,
  onOpenTab
}: {
  report: AGraphReport;
  copiedKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const rows = reportActionCommands(report);
  if (rows.length === 0) return null;

  return (
    <section className="panel">
      <h2>Report Actions</h2>
      <div className="action-list">
        {rows.map((row) => (
          <article key={row.id} className="action-row">
            <div>
              <div className="action-row-meta">
                <span>{row.id}</span>
              </div>
              <h3>{row.label}</h3>
              <p>{row.description}</p>
              <code>{row.command}</code>
            </div>
            <div className="action-row-buttons">
              <button
                type="button"
                onClick={() =>
                  onAsk({
                    label: row.label,
                    source: `report.commands.${row.id}`,
                    question: `What should I know before I run ${row.label}?`,
                    evidenceRows: [{ id: row.id, label: row.label, command: row.command, targetTab: row.targetTab }]
                  })
                }
              >
                Ask
              </button>
              {row.targetTab ? (
                <button type="button" onClick={() => onOpenTab(row.targetTab as ReportTab)}>
                  Open {row.targetTab}
                </button>
              ) : null}
              <button type="button" onClick={() => onCopyCommand(`report-action:${row.id}`, row.command)}>
                {copiedKey === `report-action:${row.id}` ? "Copied" : "Copy command"}
              </button>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

export function projectMapPath(report: AGraphReport): string {
  return displayValue(report.project.mapPath || report.project.map_path || "agraph.map.json");
}

export function correctionApplyCommands(report: AGraphReport): string[] {
  return report.commands.filter((command) => /\bsync\s+work\s+apply\b/.test(command));
}

export function correctionCompleteCommands(report: AGraphReport): string[] {
  return report.commands.filter((command) => /\bsync\s+work\s+complete\b/.test(command));
}

export function correctionResultTemplate(report: AGraphReport): string {
  return JSON.stringify(
    {
      schema: "agraph.work.result/v1",
      project: report.project.id,
      status: "accepted",
      summary: "Describe the reviewed evidence and accepted correction.",
      evidenceRows: [],
      mapChanges: []
    },
    null,
    2
  );
}

export function nextActionTarget(action: Record<string, unknown>): ActionTarget | null {
  const kind = String(action.kind || "");
  switch (kind) {
    case "dependency-review":
    case "dependencies":
      return { tab: "dependencies", graphSliceId: "package-evidence" };
    case "external-api-review":
      return { tab: "systems", graphSliceId: "external-surface" };
    case "freshness":
      return { tab: "evidence" };
    case "maintenance":
    case "audit-scope":
      return { tab: "maintenance" };
    case "coverage":
      return { tab: "evidence" };
    case "activity":
    case "validation-history":
    case "map-overlay":
      return { tab: "evidence" };
    case "ask":
      return { tab: "ask" };
    default:
      return null;
  }
}

export function commandKey(action: Record<string, unknown>, index: number): string {
  return String(action.kind || action.command || action.label || index);
}

export function pluginPanelActions({
  copiedKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab
}: {
  copiedKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}): PluginPanelActions {
  return {
    copiedKey,
    onAsk,
    onCopyCommand,
    onOpenGraphSlice,
    onOpenTab: (tab) => {
      if (isReportTab(tab)) onOpenTab(tab);
    }
  };
}

export function OperatorNextActions({
  rows,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab,
  copiedKey
}: {
  rows: Array<Record<string, unknown>>;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  copiedKey: string | null;
}) {
  return (
    <section className="panel">
      <h2>Operator Next Actions</h2>
      {rows.length === 0 ? (
        <p className="muted">No queued next actions in this report.</p>
      ) : (
        <div className="action-list">
          {rows.map((row, index) => {
            const key = commandKey(row, index);
            const label = displayValue(row.label || row.kind) || "Action";
            const command = displayValue(row.command);
            const count = displayValue(row.count);
            const target = nextActionTarget(row);
            return (
              <article key={key} className="action-row">
                <div>
                  <div className="action-row-meta">
                    <span>{displayValue(row.kind) || "action"}</span>
                    {count ? <span>{count}</span> : null}
                  </div>
                  <h3>{label}</h3>
                  {command ? <code>{command}</code> : null}
                </div>
                <div className="action-row-buttons">
                  <button
                    type="button"
                    onClick={() =>
                      onAsk({
                        label,
                        source: `operator.next-actions.${displayValue(row.kind) || index}`,
                        question: `What should I do for ${label}?`,
                        evidenceRows: [row]
                      })
                    }
                  >
                    Ask
                  </button>
                  {target ? (
                    <button type="button" onClick={() => onOpenTab(target.tab)}>
                      Open {target.tab}
                    </button>
                  ) : null}
                  {target?.graphSliceId ? (
                    <button type="button" onClick={() => onOpenGraphSlice(target.graphSliceId as string)}>
                      Open graph slice
                    </button>
                  ) : null}
                  {command ? (
                    <button type="button" onClick={() => onCopyCommand(key, command)}>
                      {copiedKey === key ? "Copied" : "Copy command"}
                    </button>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

export function reviewEvidenceColumns(rows: Array<Record<string, unknown>>): TableColumn[] {
  const keys = new Set<string>();
  for (const row of rows.slice(0, 4)) {
    Object.keys(row).forEach((key) => keys.add(key));
  }
  return Array.from(keys)
    .slice(0, 6)
    .map((key) => ({ key, label: key }));
}

export function sourceRefs(rows: Array<Record<string, unknown>>): string[] {
  const refs = new Set<string>();
  for (const row of rows) {
    const path = displayValue(row.path || row.file || row.sourcePath || row.source_path);
    if (!path) continue;
    const repo = displayValue(row.repo || row["repo-id"] || row.repoId);
    const line = displayValue(row.line || row["start-line"] || row.startLine);
    refs.add(`${repo ? `${repo}:` : ""}${path}${line ? `:${line}` : ""}`);
  }
  return [...refs];
}

export function reviewEvidencePacket(row: ReviewQueueRow): string {
  return JSON.stringify(
    {
      id: row.id,
      area: row.area,
      label: row.label,
      severity: row.severity,
      source: row.source,
      targetTab: row.targetTab,
      graphSliceId: row.graphSliceId,
      command: row.command,
      evidence: row.evidence,
      evidenceRows: row.evidenceRows || []
    },
    null,
    2
  );
}

export function reviewCorrectionTemplate(row: ReviewQueueRow, projectId: string): string {
  return JSON.stringify(
    {
      schema: "agraph.work.result/v1",
      project: projectId,
      status: "accepted",
      summary: `Review ${row.label} and record the accepted correction.`,
      review: {
        id: row.id,
        area: row.area,
        source: row.source,
        targetTab: row.targetTab,
        graphSliceId: row.graphSliceId
      },
      evidenceRows: row.evidenceRows || [],
      mapChanges: []
    },
    null,
    2
  );
}

export function quoteCommandArg(value: string): string {
  return /^[A-Za-z0-9_./:=@+-]+$/.test(value) ? value : JSON.stringify(value);
}

export function reviewExplainCommand(row: ReviewQueueRow, mapPath: string): string {
  return `agraph sync explain ${quoteCommandArg(row.source)} --map ${quoteCommandArg(mapPath)}`;
}

export function pluginArtifactRefs(rows: Array<Record<string, unknown>>): string[] {
  const refs = new Set<string>();
  for (const row of rows) {
    for (const key of ["path", "file", "url", "artifact", "artifactPath", "artifact_path"]) {
      const value = displayValue(row[key]);
      if (value) refs.add(value);
    }
  }
  return [...refs];
}

export function inferredColumns(rows: Array<Record<string, unknown>>, preferred: string[]): TableColumn[] {
  const keys = new Set<string>();
  for (const key of preferred) {
    if (rows.some((row) => row[key] !== undefined && row[key] !== null)) keys.add(key);
  }
  for (const row of rows.slice(0, 5)) {
    Object.keys(row).forEach((key) => keys.add(key));
  }
  return Array.from(keys)
    .slice(0, 8)
    .map((key) => ({ key, label: key }));
}

export function ReviewEvidenceTable({ rows }: { rows: Array<Record<string, unknown>> }) {
  if (rows.length === 0) return null;
  const columns = reviewEvidenceColumns(rows);
  if (columns.length === 0) return null;

  return (
    <div className="review-evidence">
      <p className="eyebrow">Evidence Rows</p>
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key}>{column.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={String(row.id || row.reviewId || row.review_id || row.path || index)}>
              {columns.map((column) => (
                <td key={column.key} className={numericCell(row[column.key])}>
                  {displayValue(row[column.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ReviewQueue({
  rows,
  projectId,
  mapPath,
  copiedKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab,
  limit,
  title = "Operator Review Queue"
}: {
  rows: ReviewQueueRow[];
  projectId: string;
  mapPath: string;
  copiedKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  limit?: number;
  title?: string;
}) {
  const visibleRows = typeof limit === "number" ? rows.slice(0, limit) : rows;

  return (
    <section className="panel span-2 review-queue">
      <div className="panel-header">
        <div>
          <h2>{title}</h2>
          <p className="muted">Ranked rows with source evidence and the next report surface to inspect.</p>
        </div>
      </div>
      {visibleRows.length === 0 ? (
        <p className="muted">No review rows were derived from this report.</p>
      ) : (
        <div className="review-list">
          {visibleRows.map((row) => (
            <article key={row.id} className={`review-row ${row.severity}`}>
              <div>
                <div className="review-row-meta">
                  <span>{row.area}</span>
                  <span>{row.severity}</span>
                  <span>{row.source}</span>
                </div>
                <h3>{row.label}</h3>
                <p>{row.evidence}</p>
                <ReviewEvidenceTable rows={row.evidenceRows || []} />
                {row.command ? <code>{row.command}</code> : null}
              </div>
              <div className="review-row-actions">
                <button
                  type="button"
                  onClick={() =>
                    onAsk({
                      label: row.label,
                      source: row.source,
                      question: `What should I do about ${row.label}?`,
                      evidenceRows: row.evidenceRows
                    })
                  }
                >
                  Ask
                </button>
                <button type="button" onClick={() => onOpenTab(row.targetTab)}>
                  Open {row.targetTab}
                </button>
                {row.graphSliceId ? (
                  <button type="button" onClick={() => onOpenGraphSlice(row.graphSliceId as string)}>
                    Open graph slice
                  </button>
                ) : null}
                {row.command ? (
                  <button type="button" onClick={() => onCopyCommand(`review:${row.id}`, row.command as string)}>
                    {copiedKey === `review:${row.id}` ? "Copied" : "Copy command"}
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => onCopyCommand(`review-explain:${row.id}`, reviewExplainCommand(row, mapPath))}
                >
                  {copiedKey === `review-explain:${row.id}` ? "Copied" : "Copy explain command"}
                </button>
                {sourceRefs(row.evidenceRows || []).length > 0 ? (
                  <button
                    type="button"
                    onClick={() => onCopyCommand(`review-sources:${row.id}`, sourceRefs(row.evidenceRows || []).join("\n"))}
                  >
                    {copiedKey === `review-sources:${row.id}` ? "Copied" : "Copy source refs"}
                  </button>
                ) : null}
                {(row.evidenceRows || []).length > 0 ? (
                  <button
                    type="button"
                    onClick={() => onCopyCommand(`review-evidence:${row.id}`, reviewEvidencePacket(row))}
                  >
                    {copiedKey === `review-evidence:${row.id}` ? "Copied" : "Copy evidence JSON"}
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => onCopyCommand(`review-correction:${row.id}`, reviewCorrectionTemplate(row, projectId))}
                >
                  {copiedKey === `review-correction:${row.id}` ? "Copied" : "Copy correction JSON"}
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
