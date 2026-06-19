import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import type { AGraphGraph, AGraphReport, CountRow } from "../data/types";
import { edgeRelationRows, fileKindRows, nodeKindRows, numericCount } from "../data/reportAdapter";
import { GraphPanel } from "../graph/GraphPanel";
import {
  externalPanels,
  panelPluginId,
  PluginDiagnostics,
  PluginPanel,
  PluginPanelList,
  pluginPanels,
  type PluginPanelActions
} from "./ReportPluginPanels";
import { graphSlices, type GraphSlice } from "./graphSlices";
import { reviewQueueRows, type ReviewQueueRow } from "./reviewQueue";
import { displayValue } from "./valueFormat";

type ReportTab = "dashboard" | "ask" | "systems" | "dependencies" | "evidence" | "maintenance" | "plugins";

type TableColumn = {
  key: string;
  label: string;
};

type AskAnswer = {
  title: string;
  summary: string[];
  evidence: Array<Record<string, unknown>>;
  related: Array<Record<string, unknown>>;
  relatedTitle: string;
};

type AskScope = {
  label: string;
  source: string;
  question: string;
  evidenceRows?: Array<Record<string, unknown>>;
};

type ActionTarget = {
  tab: ReportTab;
  graphSliceId?: string;
};

const tabs: Array<{ id: ReportTab; label: string }> = [
  { id: "dashboard", label: "Dashboard" },
  { id: "ask", label: "Ask" },
  { id: "systems", label: "Systems" },
  { id: "dependencies", label: "Dependencies" },
  { id: "evidence", label: "Evidence" },
  { id: "maintenance", label: "Maintenance" },
  { id: "plugins", label: "Plugins" }
];

function isReportTab(value: string): value is ReportTab {
  return tabs.some((tab) => tab.id === value);
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function asRows(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? (value.filter((row) => row && typeof row === "object") as Array<Record<string, unknown>>) : [];
}

function numericCell(value: unknown): string | undefined {
  return typeof value === "number" ? "numeric-cell" : undefined;
}

function firstValue(row: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    if (row[key] !== undefined && row[key] !== null) return row[key];
  }
  return undefined;
}

function countValue(counts: unknown, key: string): number {
  const record = asRecord(counts);
  const value = firstValue(record, [key, key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
  return typeof value === "number" ? value : 0;
}

function CountCard({ label, value, tone }: { label: string; value: number | string; tone?: "warn" | "ok" }) {
  return (
    <div className={`count-card${tone ? ` ${tone}` : ""}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function MetricStrip({ items }: { items: Array<{ label: string; value: number | string; tone?: "warn" | "ok" }> }) {
  return (
    <div className="metric-grid">
      {items.map((item) => (
        <CountCard key={item.label} label={item.label} value={item.value} tone={item.tone} />
      ))}
    </div>
  );
}

function CountTable({ title, rows }: { title: string; rows: CountRow[] }) {
  return (
    <section className="panel">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="muted">No rows.</p>
      ) : (
        <table>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.value || row.kind || row.relation}-${row.count}`}>
                <td>{row.value || row.kind || row.relation}</td>
                <td className="numeric-cell">{row.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function DataTable({
  title,
  rows,
  columns,
  empty = "No rows."
}: {
  title: string;
  rows: Array<Record<string, unknown>>;
  columns: TableColumn[];
  empty?: string;
}) {
  return (
    <section className="panel">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
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
              <tr key={String(row.id || row.reviewId || row.review_id || row.source_id || index)}>
                {columns.map((column) => (
                  <td key={column.key} className={numericCell(row[column.key])}>
                    {displayValue(row[column.key])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function InlineTable({
  title,
  rows,
  columns,
  empty = "No rows."
}: {
  title: string;
  rows: Array<Record<string, unknown>>;
  columns: TableColumn[];
  empty?: string;
}) {
  return (
    <div className="inline-table">
      <h3>{title}</h3>
      {rows.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
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
              <tr key={String(row.id || row.reviewId || row.review_id || row.source_id || row.path || index)}>
                {columns.map((column) => (
                  <td key={column.key} className={numericCell(row[column.key])}>
                    {displayValue(row[column.key])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function CommandList({
  commands,
  copiedKey,
  onCopyCommand
}: {
  commands: string[];
  copiedKey: string | null;
  onCopyCommand: (key: string, command: string) => void;
}) {
  return (
    <section className="panel">
      <h2>Suggested Commands</h2>
      {commands.length === 0 ? (
        <p className="muted">No commands.</p>
      ) : (
        <ul className="command-list">
          {commands.map((command, index) => {
            const key = `suggested:${index}:${command}`;
            return (
              <li key={command}>
                <code>{command}</code>
                <button type="button" onClick={() => onCopyCommand(key, command)}>
                  {copiedKey === key ? "Copied" : "Copy command"}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

type ReportActionCommand = {
  id: string;
  label: string;
  description: string;
  command: string;
  targetTab?: ReportTab;
};

function firstCommandMatching(commands: string[], pattern: RegExp): string | undefined {
  return commands.find((command) => pattern.test(command));
}

function enqueueWorkCommand(commands: string[]): string | undefined {
  const explicit = firstCommandMatching(commands, /\bsync\s+.*--check\b.*--enqueue\b|\bsync\s+.*--enqueue\b.*--check\b/);
  if (explicit) return explicit;
  const check = firstCommandMatching(commands, /\bsync\s+.*--check\b/);
  return check && !/\s--enqueue\b/.test(check) ? `${check} --enqueue` : check;
}

function reportActionCommands(report: AGraphReport): ReportActionCommand[] {
  const commands = report.commands || [];
  const rows = [
    {
      id: "regenerate-report",
      label: "Regenerate report",
      description: "Rebuild the static report from the same project config and map overlay.",
      command: firstCommandMatching(commands, /\breport\s+/)
    },
    {
      id: "refresh-graph",
      label: "Refresh graph basis",
      description: "Run sync checks against the indexed graph basis before trusting stale rows.",
      command: firstCommandMatching(commands, /\bsync\s+.*--check/),
      targetTab: "evidence" as const
    },
    {
      id: "enqueue-review-work",
      label: "Enqueue review work",
      description: "Turn current maintenance, dependency, and external review evidence into queue work items.",
      command: enqueueWorkCommand(commands),
      targetTab: "maintenance" as const
    },
    {
      id: "review-work-queue",
      label: "Review work queue",
      description: "Inspect queued maintenance or correction work before applying map changes.",
      command: firstCommandMatching(commands, /\bsync\s+work\s+list\b/),
      targetTab: "maintenance" as const
    },
    {
      id: "apply-completed-work",
      label: "Apply completed work",
      description: "Apply a validated work result into the map overlay.",
      command: firstCommandMatching(commands, /\bsync\s+work\s+apply\b/),
      targetTab: "maintenance" as const
    },
    {
      id: "ask-cli",
      label: "Ask with CLI",
      description: "Run the equivalent graph question through the CLI when report-local Ask is too narrow.",
      command: firstCommandMatching(commands, /\bask\s+/),
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

function ReportActions({
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

function projectMapPath(report: AGraphReport): string {
  return displayValue(report.project.mapPath || report.project.map_path || "agraph.map.json");
}

function correctionApplyCommands(report: AGraphReport): string[] {
  return report.commands.filter((command) => /\bsync\s+work\s+apply\b/.test(command));
}

function correctionCompleteCommands(report: AGraphReport): string[] {
  return report.commands.filter((command) => /\bsync\s+work\s+complete\b/.test(command));
}

function correctionResultTemplate(report: AGraphReport): string {
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

function nextActionTarget(action: Record<string, unknown>): ActionTarget | null {
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
      return { tab: "evidence" };
    case "ask":
      return { tab: "ask" };
    default:
      return null;
  }
}

function commandKey(action: Record<string, unknown>, index: number): string {
  return String(action.kind || action.command || action.label || index);
}

function pluginPanelActions({
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

function OperatorNextActions({
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
                        source: `atlas.next-actions.${displayValue(row.kind) || index}`,
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

function reviewEvidenceColumns(rows: Array<Record<string, unknown>>): TableColumn[] {
  const keys = new Set<string>();
  for (const row of rows.slice(0, 4)) {
    Object.keys(row).forEach((key) => keys.add(key));
  }
  return Array.from(keys)
    .slice(0, 6)
    .map((key) => ({ key, label: key }));
}

function sourceRefs(rows: Array<Record<string, unknown>>): string[] {
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

function reviewEvidencePacket(row: ReviewQueueRow): string {
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

function reviewCorrectionTemplate(row: ReviewQueueRow, projectId: string): string {
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

function quoteCommandArg(value: string): string {
  return /^[A-Za-z0-9_./:=@+-]+$/.test(value) ? value : JSON.stringify(value);
}

function reviewExplainCommand(row: ReviewQueueRow, mapPath: string): string {
  return `agraph sync explain ${quoteCommandArg(row.source)} --map ${quoteCommandArg(mapPath)}`;
}

function pluginArtifactRefs(rows: Array<Record<string, unknown>>): string[] {
  const refs = new Set<string>();
  for (const row of rows) {
    for (const key of ["path", "file", "url", "artifact", "artifactPath", "artifact_path"]) {
      const value = displayValue(row[key]);
      if (value) refs.add(value);
    }
  }
  return [...refs];
}

function inferredColumns(rows: Array<Record<string, unknown>>, preferred: string[]): TableColumn[] {
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

function ReviewEvidenceTable({ rows }: { rows: Array<Record<string, unknown>> }) {
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

function ReviewQueue({
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

function externalApiReview(report: AGraphReport): Record<string, unknown> | null {
  const maintenance = report.maintenance;
  if (!maintenance || typeof maintenance !== "object") return null;
  const review =
    (maintenance as Record<string, unknown>).externalApiReview ||
    (maintenance as Record<string, unknown>)["external-api-review"] ||
    (maintenance as Record<string, unknown>).external_api_review;
  return review && typeof review === "object" ? (review as Record<string, unknown>) : null;
}

function fanoutRows(review: Record<string, unknown> | null): Array<Record<string, unknown>> {
  const rows = review?.sourceFanouts || review?.["source-fanouts"] || review?.source_fanouts;
  return asRows(rows);
}

function nestedLabel(row: Record<string, unknown>, key: string): string {
  const nested = asRecord(row[key]);
  return String(nested.label || nested.id || nested["xt/id"] || "");
}

function packageRows(report: AGraphReport, key: string): Array<Record<string, unknown>> {
  const packages = asRecord(report.packages);
  return asRows(packages[key] || packages[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

function freshnessRepoRows(report: AGraphReport): Array<Record<string, unknown>> {
  const freshness = asRecord(report.evidence.freshness);
  return asRows(freshness.repos).map((repo) => {
    const counts = asRecord(repo.counts);
    return {
      repo: repo["repo-id"] || repo.repoId || repo.id,
      status: repo.status,
      indexed: countValue(counts, "indexed"),
      current: countValue(counts, "current"),
      changed: countValue(counts, "changed"),
      missing: countValue(counts, "missing"),
      unindexed: countValue(counts, "unindexed")
    };
  });
}

function freshnessSampleRows(report: AGraphReport): Array<Record<string, unknown>> {
  const rows: Array<Record<string, unknown>> = [];
  const freshness = asRecord(report.evidence.freshness);
  for (const repo of asRows(freshness.repos)) {
    const repoId = repo["repo-id"] || repo.repoId || repo.id;
    const samples = asRecord(repo.samples);
    for (const category of ["changed", "missing", "unindexed"]) {
      for (const sample of asRows(samples[category]).slice(0, 8)) {
        rows.push({
          repo: sample["repo-id"] || sample.repoId || repoId,
          category,
          path: sample.path,
          ext: sample.ext,
          reason: sample["skip-reason"] || sample.skipReason || sample.reason
        });
      }
    }
  }
  return rows;
}

function freshnessEvidencePacket(report: AGraphReport): string {
  return JSON.stringify(
    {
      source: "evidence.freshness",
      project: report.project.id,
      freshness: report.evidence.freshness || {}
    },
    null,
    2
  );
}

function askAnswerPacket(question: string, answer: AskAnswer): string {
  return JSON.stringify(
    {
      schema: "agraph.report.ask-answer/v1",
      question,
      title: answer.title,
      summary: answer.summary,
      evidence: answer.evidence,
      relatedTitle: answer.relatedTitle,
      related: answer.related
    },
    null,
    2
  );
}

function maintenanceRows(report: AGraphReport, key: string): Array<Record<string, unknown>> {
  const maintenance = asRecord(report.maintenance);
  return asRows(maintenance[key] || maintenance[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

function externalGraphRows(graph: AGraphGraph): Array<Record<string, unknown>> {
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));
  const externalIds = new Set(graph.nodes.filter((node) => node.kind === "external-api").map((node) => node.id));
  const rows = new Map<string, { label: string; relation: string; externalApis: Set<string>; edges: number }>();

  for (const edge of graph.edges) {
    const sourceExternal = externalIds.has(edge.source);
    const targetExternal = externalIds.has(edge.target);
    if (sourceExternal === targetExternal) continue;
    const peerId = sourceExternal ? edge.target : edge.source;
    const externalId = sourceExternal ? edge.source : edge.target;
    const peer = nodesById.get(peerId);
    const external = nodesById.get(externalId);
    const relation = edge.relation || "edge";
    const key = `${peerId}:${relation}`;
    const row = rows.get(key) || {
      label: peer?.label || peerId,
      relation,
      externalApis: new Set<string>(),
      edges: 0
    };
    row.externalApis.add(external?.label || externalId);
    row.edges += 1;
    rows.set(key, row);
  }

  return Array.from(rows.values())
    .map((row) => ({
      system: row.label,
      relation: row.relation,
      externalApis: row.externalApis.size,
      edges: row.edges
    }))
    .sort((left, right) => Number(right.externalApis) - Number(left.externalApis))
    .slice(0, 12);
}

function reportAtlas(report: AGraphReport, graph: AGraphGraph): Record<string, unknown> {
  if (report.atlas && typeof report.atlas === "object") return report.atlas;
  const maintenance = asRecord(report.maintenance);
  const packages = asRecord(report.packages);
  const packageCounts = asRecord(packages.counts);
  return {
    project: {
      repos: report.repos.length,
      "repo-roles": []
    },
    evidence: {
      available: report.evidence.available,
      files: numericCount(report, "files"),
      nodes: numericCount(report, "nodes"),
      edges: numericCount(report, "edges"),
      diagnostics: numericCount(report, "diagnostics")
    },
    systems: {
      nodes: graph.nodes.length,
      edges: graph.edges.length,
      clusters: countValue(asRecord(maintenance.counts), "clusters"),
      "visible-connections": countValue(asRecord(maintenance.counts), "visible-connections"),
      "orphaned-systems": countValue(asRecord(maintenance.counts), "orphaned-systems")
    },
    dependencies: {
      packages: countValue(packageCounts, "packages"),
      versions: countValue(packageCounts, "versions"),
      "unresolved-imports": countValue(packageCounts, "unresolved-imports"),
      "version-conflicts": countValue(packageCounts, "version-conflicts")
    },
    maintenance: {
      queue: asRecord(maintenance.queue),
      "external-api-review": asRecord(externalApiReview(report)?.counts)
    },
    "next-actions": []
  };
}

function overviewAnswer(report: AGraphReport, graph: AGraphGraph): AskAnswer {
  const atlas = reportAtlas(report, graph);
  const evidence = asRecord(atlas.evidence);
  const systems = asRecord(atlas.systems);
  const dependencies = asRecord(atlas.dependencies);
  const nextActions = asRows(atlas["next-actions"] || atlas.nextActions);

  return {
    title: "Project overview",
    summary: [
      `${report.project.name || report.project.id} has ${report.repos.length} repo(s), ${countValue(evidence, "files")} indexed file(s), and ${countValue(systems, "nodes")} visible system node(s) in this report.`,
      `The report has ${countValue(dependencies, "packages")} package(s), ${countValue(dependencies, "unresolved-imports")} unresolved import(s), and ${countValue(dependencies, "version-conflicts")} package conflict(s).`,
      nextActions.length > 0
        ? "The highest-signal next work is listed in the operator action rows below."
        : "This report does not expose queued next actions."
    ],
    evidence: [
      { source: "atlas.evidence", finding: "Indexed files", count: countValue(evidence, "files") },
      { source: "atlas.systems", finding: "Visible system nodes", count: countValue(systems, "nodes") },
      { source: "atlas.dependencies", finding: "Packages", count: countValue(dependencies, "packages") }
    ],
    related: nextActions,
    relatedTitle: "Operator Next Actions"
  };
}

function evidenceKindCount(report: AGraphReport, names: string[]): number {
  const wanted = new Set(names);
  const kinds = asRecord(report.evidence.kinds);
  let total = 0;
  for (const value of Object.values(kinds)) {
    const directRows = asRows(value);
    const rows =
      directRows.length > 0
        ? directRows
        : Object.values(asRecord(value)).flatMap((nested) => asRows(nested));
    for (const row of rows) {
      const kind = displayValue(row.kind || row.value || row.relation);
      if (wanted.has(kind)) total += countValue(row, "count");
    }
  }
  return total;
}

function graphNodeCount(graph: AGraphGraph, kinds: string[], tags: string[] = []): number {
  const wantedKinds = new Set(kinds);
  const wantedTags = new Set(tags);
  return graph.nodes.filter((node) => {
    if (node.kind && wantedKinds.has(node.kind)) return true;
    return Array.isArray(node.tags) && node.tags.some((tag) => wantedTags.has(tag));
  }).length;
}

function inventoryRelatedRows(report: AGraphReport, graph: AGraphGraph): Array<Record<string, unknown>> {
  const inventoryKinds = new Set([
    "route",
    "url",
    "external-api",
    "config",
    "deployment",
    "secret",
    "service-account",
    "auth",
    "auth-reference",
    "generated-artifact",
    "manifest"
  ]);
  const graphRows = graph.nodes
    .filter((node) => {
      if (node.kind && inventoryKinds.has(node.kind)) return true;
      return Array.isArray(node.tags) && (node.tags.includes("config") || node.tags.includes("auth"));
    })
    .slice(0, 12)
    .map((node) => ({
      category: "graph-node",
      label: node.label || node.id,
      kind: node.kind,
      path: node.path,
      source: "systems.json"
    }));
  return [...graphRows, ...freshnessSampleRows(report).slice(0, 8)];
}

function inventoryAnswer(report: AGraphReport, graph: AGraphGraph): AskAnswer {
  const packages = asRecord(report.packages);
  const packageCounts = asRecord(packages.counts);
  const freshness = asRecord(report.evidence.freshness);
  const freshnessCounts = asRecord(freshness.counts);
  const routes = graphNodeCount(graph, ["route"]) + evidenceKindCount(report, ["route"]);
  const urls = graphNodeCount(graph, ["url", "external-api"]) + evidenceKindCount(report, ["url"]);
  const auth = graphNodeCount(graph, ["auth", "auth-reference", "service-account"], ["auth"]) + evidenceKindCount(report, ["auth-reference"]);
  const configs = graphNodeCount(graph, ["config", "deployment", "secret"], ["config"]) + evidenceKindCount(report, ["env-var"]);
  const generated = graphNodeCount(graph, ["generated-artifact"]) + evidenceKindCount(report, ["generated-artifact"]);
  const manifests = graphNodeCount(graph, ["manifest"]) + evidenceKindCount(report, ["manifest"]);
  const freshnessGaps =
    countValue(freshnessCounts, "changed") + countValue(freshnessCounts, "missing") + countValue(freshnessCounts, "unindexed");

  return {
    title: "Project inventory",
    summary: [
      `${report.project.name || report.project.id} contains ${report.repos.length} repo(s), ${numericCount(report, "files")} indexed file(s), ${graph.nodes.length} graph node(s), and ${graph.edges.length} graph edge(s) in the loaded artifacts.`,
      `The report includes ${countValue(packageCounts, "packages")} package(s), ${manifests} manifest evidence row(s), ${routes} route row(s), ${urls} URL/external row(s), ${configs} config row(s), ${auth} auth row(s), and ${generated} generated artifact row(s).`,
      `Freshness state has ${freshnessGaps} gap(s): ${countValue(freshnessCounts, "changed")} changed, ${countValue(freshnessCounts, "missing")} missing, and ${countValue(freshnessCounts, "unindexed")} unindexed.`
    ],
    evidence: [
      { source: "evidence.counts", finding: "Indexed files", count: numericCount(report, "files") },
      { source: "systems.json", finding: "Graph nodes", count: graph.nodes.length },
      { source: "packages.counts", finding: "Packages", count: countValue(packageCounts, "packages") },
      { source: "evidence.kinds", finding: "Routes", count: routes },
      { source: "evidence.kinds", finding: "URLs", count: urls },
      { source: "evidence.kinds", finding: "Auth surfaces", count: auth },
      { source: "evidence.freshness", finding: "Freshness gaps", count: freshnessGaps }
    ],
    related: inventoryRelatedRows(report, graph),
    relatedTitle: "Inventory Evidence Rows"
  };
}

function dependencyAnswer(report: AGraphReport): AskAnswer {
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  const unresolved = packageRows(report, "unresolved-imports");
  const conflicts = packageRows(report, "version-conflicts");
  const missingEvidence = packageRows(report, "declared-without-import-evidence");

  return {
    title: "Dependency issues",
    summary: [
      `This report has ${countValue(counts, "packages")} package(s), ${countValue(counts, "versions")} resolved version row(s), and ${countValue(counts, "imports-package")} import-to-package edge(s).`,
      `${countValue(counts, "unresolved-imports")} import(s) are unresolved, ${countValue(counts, "version-conflicts")} package(s) have version conflicts, and ${countValue(counts, "declared-without-import-evidence")} declared package(s) have no import evidence.`,
      "Use the dependency rows as review targets; this is report-local evidence, not a live dependency resolver run."
    ],
    evidence: [
      { source: "packages.counts", finding: "Unresolved imports", count: countValue(counts, "unresolved-imports") },
      { source: "packages.counts", finding: "Version conflicts", count: countValue(counts, "version-conflicts") },
      {
        source: "packages.counts",
        finding: "Declared without import evidence",
        count: countValue(counts, "declared-without-import-evidence")
      }
    ],
    related: [...unresolved, ...conflicts, ...missingEvidence].slice(0, 12),
    relatedTitle: "Dependency Evidence Rows"
  };
}

function externalApiAnswer(report: AGraphReport, graph: AGraphGraph): AskAnswer {
  const review = externalApiReview(report);
  const counts = asRecord(review?.counts);
  const fanouts = fanoutRows(review);
  const graphRows = externalGraphRows(graph);

  return {
    title: "External API evidence",
    summary: [
      `Maintenance evidence contains ${countValue(counts, "nodes")} external API node(s), ${countValue(counts, "edges")} incident edge(s), and ${countValue(counts, "source-fanouts")} grouped source fanout(s).`,
      `The currently loaded systems graph exposes ${graph.nodes.filter((node) => node.kind === "external-api").length} external API node(s).`,
      "External API groups are mechanical graph neighborhoods. Review rows before rejecting or accepting any host."
    ],
    evidence: [
      { source: "maintenance.external-api-review", finding: "External API nodes", count: countValue(counts, "nodes") },
      { source: "maintenance.external-api-review", finding: "Source fanouts", count: countValue(counts, "source-fanouts") },
      { source: "systems.json", finding: "Visible external API graph nodes", count: graph.nodes.filter((node) => node.kind === "external-api").length }
    ],
    related: fanouts.length > 0 ? fanouts : graphRows,
    relatedTitle: fanouts.length > 0 ? "External API Fanouts" : "Graph External API Neighborhoods"
  };
}

function systemsAnswer(report: AGraphReport, graph: AGraphGraph): AskAnswer {
  const atlas = reportAtlas(report, graph);
  const systems = asRecord(atlas.systems);
  const hubs = maintenanceRows(report, "top-hubs");
  const orphans = maintenanceRows(report, "orphaned-candidates");

  return {
    title: "System graph shape",
    summary: [
      `The loaded system graph has ${graph.nodes.length} node(s), ${graph.edges.length} edge(s), and ${countValue(systems, "clusters")} discovered cluster(s).`,
      `${countValue(systems, "visible-connections")} visible semantic connection(s) and ${countValue(systems, "orphaned-systems")} orphaned candidate system(s) are reported.`,
      "Inspect hubs and orphaned candidates first when the graph feels hard to read."
    ],
    evidence: [
      { source: "systems.json", finding: "Loaded graph nodes", count: graph.nodes.length },
      { source: "systems.json", finding: "Loaded graph edges", count: graph.edges.length },
      { source: "maintenance.counts", finding: "Orphaned systems", count: countValue(systems, "orphaned-systems") }
    ],
    related: [...hubs, ...orphans].slice(0, 12),
    relatedTitle: "System Evidence Rows"
  };
}

function maintenanceAnswer(report: AGraphReport): AskAnswer {
  const maintenance = asRecord(report.maintenance);
  const queue = asRecord(maintenance.queue);
  const decisions = maintenanceRows(report, "decision-queue");
  const infra = maintenanceRows(report, "infra-review-queue");
  const dependency = maintenanceRows(report, "dependency-review-queue");

  return {
    title: "Maintenance work",
    summary: [
      `The report exposes ${countValue(queue, "decisions")} maintenance decision(s), ${countValue(queue, "infra-review")} infra review item(s), and ${countValue(queue, "dependency-review")} dependency review item(s).`,
      "These rows are the safest path from raw graph evidence to accepted `agraph.map.json` corrections.",
      "Use the listed work commands when you need validated map patch application."
    ],
    evidence: [
      { source: "maintenance.queue", finding: "Maintenance decisions", count: countValue(queue, "decisions") },
      { source: "maintenance.queue", finding: "Infra reviews", count: countValue(queue, "infra-review") },
      { source: "maintenance.queue", finding: "Dependency reviews", count: countValue(queue, "dependency-review") }
    ],
    related: [...decisions, ...infra, ...dependency].slice(0, 12),
    relatedTitle: "Queued Review Rows"
  };
}

function coverageAnswer(report: AGraphReport): AskAnswer {
  const coverage = asRecord(report.coverage);
  const diagnostics = asRecord(coverage.diagnostics);
  const extractors = asRows(coverage.extractors);
  const skipped = asRows(coverage["skipped-by-extension"] || coverage.skippedByExtension);

  return {
    title: "Evidence and coverage",
    summary: [
      `The report has ${numericCount(report, "files")} file(s), ${numericCount(report, "nodes")} node(s), ${numericCount(report, "edges")} edge(s), and ${numericCount(report, "diagnostics")} diagnostic(s).`,
      `${extractors.length} extractor summary row(s) and ${skipped.length} skipped-extension row(s) are included in the report packet.`,
      "Coverage answers identify evidence gaps; they do not infer architecture meaning from filenames."
    ],
    evidence: [
      { source: "evidence.counts", finding: "Files", count: numericCount(report, "files") },
      { source: "coverage.extractors", finding: "Extractor rows", count: extractors.length },
      { source: "coverage.diagnostics", finding: "Diagnostics", count: countValue(diagnostics, "total") }
    ],
    related: [...extractors, ...skipped].slice(0, 12),
    relatedTitle: "Coverage Rows"
  };
}

function answerReportQuestion(report: AGraphReport, graph: AGraphGraph, question: string): AskAnswer {
  const normalized = question.trim().toLowerCase();
  if (/\b(made of|inventory|contain|contains|routes?|auth|config|generated|manifest|freshness|stale|missing)\b/.test(normalized)) {
    return inventoryAnswer(report, graph);
  }
  if (/\b(depend\w*|package|import|version|conflict|npm|cargo|go|maven)\b/.test(normalized)) {
    return dependencyAnswer(report);
  }
  if (/\b(external|api|url|host|endpoint|outbound)\b/.test(normalized)) {
    return externalApiAnswer(report, graph);
  }
  if (/\b(system|graph|hub|orphan|cluster|edge|node)\b/.test(normalized)) {
    return systemsAnswer(report, graph);
  }
  if (/\b(maintenance|review|queue|map|patch|next|action|ambiguous|noise)\b/.test(normalized)) {
    return maintenanceAnswer(report);
  }
  if (/\b(evidence|coverage|extractor|diagnostic|skipped|file kind|source)\b/.test(normalized)) {
    return coverageAnswer(report);
  }
  return overviewAnswer(report, graph);
}

function ExternalApiReview({ report }: { report: AGraphReport }) {
  const review = externalApiReview(report);
  if (!review) return null;

  const counts = review.counts;
  const fanouts = fanoutRows(review).slice(0, 8);
  const apiNodes = countValue(counts, "nodes");
  const sourceFanouts = countValue(counts, "source-fanouts");

  if (apiNodes === 0 && sourceFanouts === 0) return null;

  return (
    <section className="panel">
      <div className="panel-header">
        <div>
          <h2>External API Review</h2>
          <p className="muted">Grouped by graph topology: source, relation, direction, and visibility.</p>
        </div>
      </div>
      <MetricStrip
        items={[
          { label: "External APIs", value: apiNodes },
          { label: "External Edges", value: countValue(counts, "edges") },
          { label: "Source Fanouts", value: sourceFanouts, tone: sourceFanouts > 0 ? "warn" : undefined },
          { label: "Single Evidence", value: countValue(counts, "single-evidence-nodes") },
          { label: "Support Only", value: countValue(counts, "support-only-nodes") }
        ]}
      />
      {fanouts.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Source</th>
              <th>Relation</th>
              <th>Visibility</th>
              <th>Targets</th>
            </tr>
          </thead>
          <tbody>
            {fanouts.map((row) => (
              <tr key={String(row.id)}>
                <td>{nestedLabel(row, "peer")}</td>
                <td>{String(row.relation || "")}</td>
                <td>{String(row.visibility || "")}</td>
                <td className="numeric-cell">{Number(row.targetCount || row["target-count"] || 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

function ProjectInventory({
  report,
  onAsk,
  onOpenTab
}: {
  report: AGraphReport;
  onAsk: (scope: AskScope) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const families = asRows(report.evidence.families);
  const kinds = asRecord(report.evidence.kinds);
  const state = asRecord(report.evidence.state);
  const freshness = asRecord(state.freshness || report.evidence.freshness);
  const freshnessCounts = asRecord(freshness.counts);
  const staleEvidence =
    countValue(freshnessCounts, "changed") + countValue(freshnessCounts, "missing") + countValue(freshnessCounts, "unindexed");
  const familyRows = families.map((row) => {
    const counts = asRecord(row.counts);
    const count = Object.values(counts).reduce<number>((total, value) => total + (typeof value === "number" ? value : 0), 0);
    return {
      family: String(row.family || ""),
      status: String(row.status || ""),
      count,
      source: "evidence.families"
    };
  });
  const kindRows = Object.entries(kinds).flatMap(([family, value]) => {
    const rows = asRows(value);
    if (rows.length > 0) {
      return rows.map((row) => ({
        family,
        kind: String(row.kind || row.value || row.relation || ""),
        count: countValue(row, "count"),
        source: `evidence.kinds.${family}`
      }));
    }
    return Object.entries(asRecord(value)).flatMap(([bucket, bucketRows]) =>
      asRows(bucketRows).map((row) => ({
        family,
        kind: `${bucket}:${String(row.kind || row.value || row.relation || "")}`,
        count: countValue(row, "count"),
        source: `evidence.kinds.${family}.${bucket}`
      }))
    );
  });
  const dependencyHealth = asRecord(state["dependency-health"] || state.dependencyHealth);
  const diagnostics = asRecord(state.diagnostics);
  const stateRows = [
    { state: "freshness-gaps", count: staleEvidence, source: "evidence.state.freshness" },
    {
      state: "dependency-health",
      count:
        countValue(dependencyHealth, "package-evidence-gaps") +
        countValue(dependencyHealth, "package-conflicts") +
        countValue(dependencyHealth, "unresolved-imports"),
      source: "evidence.state.dependency-health"
    },
    { state: "diagnostics", count: countValue(diagnostics, "total"), source: "evidence.state.diagnostics" }
  ];
  const askRows = [
    ...familyRows.map((row) => ({ axis: "family", ...row })),
    ...kindRows.map((row) => ({ axis: "kind", ...row })),
    ...stateRows.map((row) => ({ axis: "state", ...row }))
  ];

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Evidence Inventory</h2>
          <p className="muted">Concrete evidence families, fact kinds, and evidence state from the current report packet.</p>
        </div>
        <div className="action-row-buttons">
          <button
            type="button"
            onClick={() =>
              onAsk({
                label: "Evidence Inventory",
                source: "report.evidence",
                question: "What evidence does this report contain?",
                evidenceRows: askRows
              })
            }
          >
            Ask
          </button>
          <button type="button" onClick={() => onOpenTab("evidence")}>
            Open evidence
          </button>
        </div>
      </div>
      <MetricStrip
        items={familyRows.map((row) => ({
          label: row.family,
          value: row.count,
          tone: row.status === "weak" ? "warn" : row.status === "available" ? "ok" : undefined
        }))}
      />
      <InlineTable
        title="Evidence Families"
        rows={familyRows}
        columns={[
          { key: "family", label: "Family" },
          { key: "status", label: "Status" },
          { key: "count", label: "Count" },
          { key: "source", label: "Source" }
        ]}
      />
      {kindRows.length > 0 ? (
        <InlineTable
          title="Evidence Kinds"
          rows={kindRows}
          columns={[
            { key: "family", label: "Family" },
            { key: "kind", label: "Kind" },
            { key: "count", label: "Count" },
            { key: "source", label: "Source" }
          ]}
        />
      ) : null}
      <InlineTable
        title="Evidence State"
        rows={stateRows}
        columns={[
          { key: "state", label: "State" },
          { key: "count", label: "Count" },
          { key: "source", label: "Source" }
        ]}
      />
    </section>
  );
}

function AtlasTab({
  report,
  graph,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab,
  copiedActionKey
}: {
  report: AGraphReport;
  graph: AGraphGraph;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  copiedActionKey: string | null;
}) {
  const atlas = reportAtlas(report, graph);
  const evidence = asRecord(atlas.evidence);
  const systems = asRecord(atlas.systems);
  const dependencies = asRecord(atlas.dependencies);
  const maintenance = asRecord(atlas.maintenance);
  const queue = asRecord(maintenance.queue);
  const externalApi = asRecord(maintenance["external-api-review"] || maintenance.externalApiReview);
  const nextActions = asRows(atlas["next-actions"] || atlas.nextActions);
  const reviewRows = reviewQueueRows(report);

  return (
    <div className="report-grid">
      <section className="panel span-2">
        <p className="eyebrow">Project Atlas</p>
        <h2>{report.project.name || report.project.id}</h2>
        <MetricStrip
          items={[
            { label: "Repos", value: countValue(asRecord(atlas.project), "repos") },
            { label: "Files", value: countValue(evidence, "files") },
            { label: "Systems", value: countValue(systems, "nodes") },
            { label: "Packages", value: countValue(dependencies, "packages") },
            {
              label: "Review Items",
              value: countValue(queue, "decisions") + countValue(queue, "infra-review") + countValue(queue, "dependency-review"),
              tone: "warn"
            }
          ]}
        />
      </section>

      <ProjectInventory report={report} onAsk={onAsk} onOpenTab={onOpenTab} />
      <ReportActions
        report={report}
        copiedKey={copiedActionKey}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenTab={onOpenTab}
      />

      <section className="panel">
        <h2>Evidence Surface</h2>
        <MetricStrip
          items={[
            { label: "Nodes", value: countValue(evidence, "nodes") },
            { label: "Edges", value: countValue(evidence, "edges") },
            { label: "Extractors", value: countValue(evidence, "extractors") },
            { label: "Skipped", value: countValue(evidence, "skipped") },
            { label: "Diagnostics", value: countValue(evidence, "diagnostics"), tone: countValue(evidence, "diagnostics") > 0 ? "warn" : "ok" }
          ]}
        />
        <div className="chips">
          {(Array.isArray(evidence.available) ? evidence.available : report.evidence.available).map((item) => (
            <span key={String(item)}>{String(item)}</span>
          ))}
        </div>
      </section>

      <section className="panel">
        <h2>System Shape</h2>
        <MetricStrip
          items={[
            { label: "System Edges", value: countValue(systems, "edges") },
            { label: "Clusters", value: countValue(systems, "clusters") },
            { label: "Visible Links", value: countValue(systems, "visible-connections") },
            { label: "Orphans", value: countValue(systems, "orphaned-systems"), tone: countValue(systems, "orphaned-systems") > 0 ? "warn" : undefined },
            { label: "External Fanouts", value: countValue(externalApi, "source-fanouts"), tone: countValue(externalApi, "source-fanouts") > 0 ? "warn" : undefined }
          ]}
        />
      </section>

      <section className="panel">
        <h2>Dependency Health</h2>
        <MetricStrip
          items={[
            { label: "Versions", value: countValue(dependencies, "versions") },
            { label: "Import Edges", value: countValue(dependencies, "imports-package") },
            { label: "Unresolved", value: countValue(dependencies, "unresolved-imports"), tone: countValue(dependencies, "unresolved-imports") > 0 ? "warn" : undefined },
            {
              label: "No Import Evidence",
              value: countValue(dependencies, "declared-without-import-evidence"),
              tone: countValue(dependencies, "declared-without-import-evidence") > 0 ? "warn" : undefined
            },
            { label: "Conflicts", value: countValue(dependencies, "version-conflicts"), tone: countValue(dependencies, "version-conflicts") > 0 ? "warn" : undefined }
          ]}
        />
      </section>

      <OperatorNextActions
        rows={nextActions}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
        copiedKey={copiedActionKey}
      />

      <ReviewQueue
        rows={reviewRows}
        projectId={report.project.id}
        mapPath={projectMapPath(report)}
        copiedKey={copiedActionKey}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
        limit={5}
      />
    </div>
  );
}

function SystemsTab({
  report,
  graph,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab,
  copiedActionKey,
  selectedSliceId,
  onSelectSlice
}: {
  report: AGraphReport;
  graph: AGraphGraph;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  copiedActionKey: string | null;
  selectedSliceId: string;
  onSelectSlice: (id: string) => void;
}) {
  const maintenance = asRecord(report.maintenance);
  const slices = useMemo(() => graphSlices(graph), [graph]);
  const activeSliceId = selectedSliceId || slices[0]?.id || "full";
  const selectedSlice = slices.find((slice) => slice.id === activeSliceId) || null;
  const activeGraph = selectedSlice?.graph || graph;

  useEffect(() => {
    if (selectedSliceId && selectedSliceId !== "full" && !slices.some((slice) => slice.id === selectedSliceId)) {
      onSelectSlice(slices[0]?.id || "full");
    }
  }, [onSelectSlice, selectedSliceId, slices]);

  return (
    <div className="report-grid">
      <FocusedGraphSlices
        graph={graph}
        selectedSlice={selectedSlice}
        slices={slices}
        selectedSliceId={activeSliceId}
        onAsk={onAsk}
        onSelect={onSelectSlice}
      />
      <div className="span-2">
        <GraphPanel graph={activeGraph} onAsk={onAsk} />
      </div>
      <ExternalApiReview report={report} />
      <DataTable
        title="Top Hubs"
        rows={asRows(maintenance["top-hubs"] || maintenance.topHubs)}
        columns={[
          { key: "label", label: "Label" },
          { key: "kind", label: "Kind" },
          { key: "degree", label: "Degree" },
          { key: "salience", label: "Salience" }
        ]}
      />
      <DataTable
        title="Orphaned Candidates"
        rows={asRows(maintenance["orphaned-candidates"] || maintenance.orphanedCandidates)}
        columns={[
          { key: "label", label: "Label" },
          { key: "kind", label: "Kind" },
          { key: "repo-id", label: "Repo" },
          { key: "path-prefix", label: "Path" }
        ]}
      />
      <PluginPanelList
        report={report}
        slot="systems"
        actions={pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab })}
      />
    </div>
  );
}

function FocusedGraphSlices({
  graph,
  onAsk,
  selectedSlice,
  slices,
  selectedSliceId,
  onSelect
}: {
  graph: AGraphGraph;
  onAsk: (scope: AskScope) => void;
  selectedSlice: GraphSlice | null;
  slices: GraphSlice[];
  selectedSliceId: string;
  onSelect: (id: string) => void;
}) {
  const activeDescription =
    selectedSlice?.description || `${graph.nodes.length} node(s) and ${graph.edges.length} edge(s) in the full graph.`;

  return (
    <section className="panel span-2 focused-slices">
      <div className="panel-header">
        <div>
          <h2>Focused Graph Slices</h2>
          <p className="muted">{activeDescription}</p>
        </div>
      </div>
      <div className="slice-buttons" aria-label="Focused graph slices">
        {slices.map((slice) => (
          <button
            key={slice.id}
            type="button"
            aria-pressed={selectedSliceId === slice.id}
            onClick={() => onSelect(slice.id)}
          >
            <strong>{slice.label}</strong>
            <span>
              {slice.graph.nodes.length} nodes / {slice.graph.edges.length} edges
            </span>
          </button>
        ))}
        <button type="button" aria-pressed={selectedSliceId === "full"} onClick={() => onSelect("full")}>
          <strong>Full Graph</strong>
          <span>
            {graph.nodes.length} nodes / {graph.edges.length} edges
          </span>
        </button>
      </div>
      <button
        className="slice-ask-button"
        type="button"
        onClick={() =>
          onAsk({
            label: selectedSlice?.label || "Full Graph",
            source: selectedSlice ? `systems.${selectedSlice.id}` : "systems.full-graph",
            question: `What should I inspect in ${selectedSlice?.label || "the full graph"}?`,
            evidenceRows: [
              {
                slice: selectedSlice?.label || "Full Graph",
                nodes: selectedSlice?.graph.nodes.length || graph.nodes.length,
                edges: selectedSlice?.graph.edges.length || graph.edges.length
              }
            ]
          })
        }
      >
        Ask about this slice
      </button>
      {slices.length === 0 ? <p className="muted">No focused slices can be derived from this graph.</p> : null}
    </section>
  );
}

function dependencyCommands(report: AGraphReport): string[] {
  const projectId = report.project.id;
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  const commands = new Set<string>([`agraph packages --project ${projectId} --json`]);

  if (countValue(counts, "version-conflicts") > 0) {
    commands.add(`agraph packages --project ${projectId} --with-conflicts --json`);
  }
  if (countValue(counts, "declared-without-import-evidence") > 0) {
    commands.add(`agraph packages --project ${projectId} --without-import-evidence --json`);
  }
  for (const row of asRows(packages.ecosystems)) {
    const ecosystem = displayValue(row.ecosystem);
    if (ecosystem) commands.add(`agraph packages --project ${projectId} --ecosystem ${ecosystem} --json`);
  }
  return [...commands];
}

function DependenciesTab({
  report,
  copiedActionKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab
}: {
  report: AGraphReport;
  copiedActionKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  return (
    <div className="report-grid">
      <section className="panel span-2">
        <h2>Package Summary</h2>
        <MetricStrip
          items={[
            { label: "Packages", value: countValue(counts, "packages") },
            { label: "Versions", value: countValue(counts, "versions") },
            { label: "Import Edges", value: countValue(counts, "imports-package") },
            { label: "Unresolved", value: countValue(counts, "unresolved-imports"), tone: countValue(counts, "unresolved-imports") > 0 ? "warn" : undefined },
            { label: "Conflicts", value: countValue(counts, "version-conflicts"), tone: countValue(counts, "version-conflicts") > 0 ? "warn" : undefined }
          ]}
        />
      </section>
      <CommandList commands={dependencyCommands(report)} copiedKey={copiedActionKey} onCopyCommand={onCopyCommand} />
      <DataTable
        title="Ecosystems"
        rows={asRows(packages.ecosystems)}
        columns={[
          { key: "ecosystem", label: "Ecosystem" },
          { key: "packages", label: "Packages" },
          { key: "versions", label: "Versions" },
          { key: "imports", label: "Imports" }
        ]}
      />
      <DataTable
        title="Unresolved Imports"
        rows={asRows(packages["unresolved-imports"] || packages.unresolvedImports)}
        columns={[
          { key: "import", label: "Import" },
          { key: "path", label: "Path" },
          { key: "line", label: "Line" },
          { key: "kind", label: "Kind" }
        ]}
      />
      <DataTable
        title="Declared Without Import Evidence"
        rows={asRows(packages["declared-without-import-evidence"] || packages.declaredWithoutImportEvidence)}
        columns={[
          { key: "label", label: "Package" },
          { key: "ecosystem", label: "Ecosystem" },
          { key: "package-name", label: "Name" }
        ]}
      />
      <DataTable
        title="Version Conflicts"
        rows={asRows(packages["version-conflicts"] || packages.versionConflicts)}
        columns={[
          { key: "label", label: "Package" },
          { key: "ecosystem", label: "Ecosystem" },
          { key: "versions", label: "Versions" }
        ]}
      />
      <PluginPanelList
        report={report}
        slot="dependencies"
        actions={pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab })}
      />
    </div>
  );
}

function EvidenceFreshnessPanel({
  report,
  onAsk,
  copiedKey,
  onCopyCommand
}: {
  report: AGraphReport;
  onAsk: (scope: AskScope) => void;
  copiedKey: string | null;
  onCopyCommand: (key: string, command: string) => void;
}) {
  const freshness = asRecord(report.evidence.freshness);
  if (Object.keys(freshness).length === 0) return null;

  const counts = asRecord(freshness.counts);
  const repoRows = freshnessRepoRows(report);
  const sampleRows = freshnessSampleRows(report);
  const status = displayValue(freshness.status) || "unknown";

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Evidence Freshness</h2>
          <p className="muted">Report-local indexed/current file state with sample paths for stale evidence review.</p>
        </div>
        <div className="action-row-buttons">
          <button
            type="button"
            onClick={() =>
              onAsk({
                label: "Evidence Freshness",
                source: "evidence.freshness",
                question: "What should I do about evidence freshness?",
                evidenceRows: sampleRows.length > 0 ? sampleRows.slice(0, 12) : repoRows
              })
            }
          >
            Ask about freshness
          </button>
          <button type="button" onClick={() => onCopyCommand("freshness:evidence-json", freshnessEvidencePacket(report))}>
            {copiedKey === "freshness:evidence-json" ? "Copied" : "Copy freshness JSON"}
          </button>
          {sourceRefs(sampleRows).length > 0 ? (
            <button type="button" onClick={() => onCopyCommand("freshness:source-refs", sourceRefs(sampleRows).join("\n"))}>
              {copiedKey === "freshness:source-refs" ? "Copied" : "Copy source refs"}
            </button>
          ) : null}
        </div>
      </div>
      <MetricStrip
        items={[
          { label: "Status", value: status, tone: status === "stale" ? "warn" : "ok" },
          { label: "Indexed", value: countValue(counts, "indexed") },
          { label: "Current", value: countValue(counts, "current") },
          { label: "Changed", value: countValue(counts, "changed"), tone: countValue(counts, "changed") > 0 ? "warn" : undefined },
          { label: "Missing", value: countValue(counts, "missing"), tone: countValue(counts, "missing") > 0 ? "warn" : undefined },
          { label: "Unindexed", value: countValue(counts, "unindexed"), tone: countValue(counts, "unindexed") > 0 ? "warn" : undefined }
        ]}
      />
      {repoRows.length > 0 ? (
        <InlineTable
          title="Freshness By Repo"
          rows={repoRows}
          columns={[
            { key: "repo", label: "Repo" },
            { key: "status", label: "Status" },
            { key: "indexed", label: "Indexed" },
            { key: "current", label: "Current" },
            { key: "changed", label: "Changed" },
            { key: "missing", label: "Missing" },
            { key: "unindexed", label: "Unindexed" }
          ]}
        />
      ) : null}
      {sampleRows.length > 0 ? (
        <InlineTable
          title="Freshness Sample Paths"
          rows={sampleRows.slice(0, 24)}
          columns={[
            { key: "repo", label: "Repo" },
            { key: "category", label: "Category" },
            { key: "path", label: "Path" },
            { key: "reason", label: "Reason" }
          ]}
        />
      ) : null}
    </section>
  );
}

function EvidenceTab({
  report,
  copiedActionKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab
}: {
  report: AGraphReport;
  copiedActionKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const coverage = asRecord(report.coverage);
  const diagnostics = asRecord(coverage.diagnostics);
  return (
    <div className="report-grid">
      <section className="panel span-2">
        <h2>Coverage Summary</h2>
        <MetricStrip
          items={[
            { label: "Files", value: numericCount(report, "files") },
            { label: "Nodes", value: numericCount(report, "nodes") },
            { label: "Edges", value: numericCount(report, "edges") },
            { label: "Diagnostics", value: numericCount(report, "diagnostics"), tone: numericCount(report, "diagnostics") > 0 ? "warn" : "ok" },
            { label: "Skipped", value: countValue(asRecord(coverage.totals), "skipped") }
          ]}
        />
      </section>
      <EvidenceFreshnessPanel report={report} copiedKey={copiedActionKey} onAsk={onAsk} onCopyCommand={onCopyCommand} />
      <CountTable title="File Kinds" rows={fileKindRows(report)} />
      <CountTable title="Node Kinds" rows={nodeKindRows(report)} />
      <CountTable title="Edge Relations" rows={edgeRelationRows(report)} />
      <DataTable
        title="Extractors"
        rows={asRows(coverage.extractors)}
        columns={[
          { key: "kind", label: "Kind" },
          { key: "extractor-version", label: "Version" },
          { key: "files", label: "Files" }
        ]}
      />
      <DataTable
        title="Skipped Extensions"
        rows={asRows(coverage["skipped-by-extension"] || coverage.skippedByExtension)}
        columns={[
          { key: "extension", label: "Extension" },
          { key: "count", label: "Count" }
        ]}
      />
      <DataTable
        title="Diagnostics"
        rows={asRows(diagnostics["by-stage"] || diagnostics.byStage)}
        columns={[
          { key: "stage", label: "Stage" },
          { key: "count", label: "Count" }
        ]}
      />
      <PluginPanelList
        report={report}
        slot="evidence"
        actions={pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab })}
      />
    </div>
  );
}

function CorrectionWorkflow({
  report,
  copiedKey,
  onAsk,
  onCopyCommand
}: {
  report: AGraphReport;
  copiedKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
}) {
  const mapPath = projectMapPath(report);
  const completeCommands = correctionCompleteCommands(report);
  const applyCommands = correctionApplyCommands(report);
  const evidenceRows = [
    { field: "mapPath", value: mapPath },
    ...completeCommands.map((command) => ({ field: "completeCommand", value: command })),
    ...applyCommands.map((command) => ({ field: "applyCommand", value: command }))
  ];

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Correction Workflow</h2>
          <p className="muted">Validated path from review evidence to accepted `agraph.map.json` corrections.</p>
        </div>
        <div className="action-row-buttons">
          <button
            type="button"
            onClick={() =>
              onAsk({
                label: "Correction Workflow",
                source: "report.commands",
                question: "What correction workflow should I use from this report?",
                evidenceRows
              })
            }
          >
            Ask
          </button>
          {mapPath ? (
            <button type="button" onClick={() => onCopyCommand("correction:map-path", mapPath)}>
              {copiedKey === "correction:map-path" ? "Copied" : "Copy map path"}
            </button>
          ) : null}
          {applyCommands[0] ? (
            <button type="button" onClick={() => onCopyCommand("correction:apply-command", applyCommands[0])}>
              {copiedKey === "correction:apply-command" ? "Copied" : "Copy apply command"}
            </button>
          ) : null}
          {completeCommands[0] ? (
            <button type="button" onClick={() => onCopyCommand("correction:complete-command", completeCommands[0])}>
              {copiedKey === "correction:complete-command" ? "Copied" : "Copy complete command"}
            </button>
          ) : null}
          <button type="button" onClick={() => onCopyCommand("correction:result-template", correctionResultTemplate(report))}>
            {copiedKey === "correction:result-template" ? "Copied" : "Copy result JSON"}
          </button>
        </div>
      </div>
      <table>
        <tbody>
          <tr>
            <th>Map</th>
            <td>
              <code>{mapPath}</code>
            </td>
          </tr>
          {completeCommands.map((command) => (
            <tr key={command}>
              <th>Complete</th>
              <td>
                <code>{command}</code>
              </td>
            </tr>
          ))}
          {applyCommands.map((command) => (
            <tr key={command}>
              <th>Apply</th>
              <td>
                <code>{command}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function MaintenanceTab({
  report,
  copiedActionKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab
}: {
  report: AGraphReport;
  copiedActionKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const maintenance = asRecord(report.maintenance);
  const reviewRows = reviewQueueRows(report);
  return (
    <div className="report-grid">
      <ReviewQueue
        rows={reviewRows}
        projectId={report.project.id}
        mapPath={projectMapPath(report)}
        copiedKey={copiedActionKey}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
      />
      <CommandList commands={report.commands} copiedKey={copiedActionKey} onCopyCommand={onCopyCommand} />
      <CorrectionWorkflow report={report} copiedKey={copiedActionKey} onAsk={onAsk} onCopyCommand={onCopyCommand} />
      <DataTable
        title="Maintenance Decisions"
        rows={asRows(maintenance["decision-queue"] || maintenance.decisionQueue)}
        columns={[
          { key: "kind", label: "Kind" },
          { key: "severity", label: "Severity" },
          { key: "target", label: "Target" },
          { key: "reason", label: "Reason" }
        ]}
      />
      <DataTable
        title="Infra Review"
        rows={asRows(maintenance["infra-review-queue"] || maintenance.infraReviewQueue)}
        columns={[
          { key: "kind", label: "Kind" },
          { key: "artifact", label: "Artifact" },
          { key: "question", label: "Question" }
        ]}
      />
      <DataTable
        title="Dependency Review"
        rows={asRows(maintenance["dependency-review-queue"] || maintenance.dependencyReviewQueue)}
        columns={[
          { key: "kind", label: "Kind" },
          { key: "question", label: "Question" },
          { key: "reviewId", label: "Review" }
        ]}
      />
      <PluginPanelList
        report={report}
        slot="maintenance"
        actions={pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab })}
      />
    </div>
  );
}

function DashboardTab({
  report,
  graph,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab,
  copiedActionKey
}: {
  report: AGraphReport;
  graph: AGraphGraph;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  copiedActionKey: string | null;
}) {
  const panels = pluginPanels(report);
  const reviewRows = reviewQueueRows(report);
  const atlas = reportAtlas(report, graph);
  const nextActions = asRows(atlas["next-actions"] || atlas.nextActions);
  const actions = pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab });
  if (panels.length === 0) {
    return (
      <AtlasTab
        report={report}
        graph={graph}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
        copiedActionKey={copiedActionKey}
      />
    );
  }

  return (
    <div className="report-grid">
      <section className="panel span-2">
        <p className="eyebrow">Report Dashboard</p>
        <h2>{report.project.name || report.project.id}</h2>
      </section>
            <ProjectInventory report={report} onAsk={onAsk} onOpenTab={onOpenTab} />
      <ReportActions
        report={report}
        copiedKey={copiedActionKey}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenTab={onOpenTab}
      />
      <OperatorNextActions
        rows={nextActions}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
        copiedKey={copiedActionKey}
      />
      <ReviewQueue
        rows={reviewRows}
        projectId={report.project.id}
        mapPath={projectMapPath(report)}
        copiedKey={copiedActionKey}
        onAsk={onAsk}
        onCopyCommand={onCopyCommand}
        onOpenGraphSlice={onOpenGraphSlice}
        onOpenTab={onOpenTab}
        limit={5}
      />
      <PluginPanelList
        report={report}
        includeCore
        actions={actions}
      />
      <PluginDiagnostics diagnostics={report.plugins?.diagnostics || []} actions={actions} />
    </div>
  );
}

function PluginArtifacts({
  artifacts,
  copiedKey,
  onCopyCommand
}: {
  artifacts: Array<Record<string, unknown>>;
  copiedKey: string | null;
  onCopyCommand: (key: string, command: string) => void;
}) {
  if (artifacts.length === 0) return null;

  const refs = pluginArtifactRefs(artifacts);
  const columns = inferredColumns(artifacts, ["label", "path", "url", "kind", "plugin"]);
  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Plugin Artifacts</h2>
          <p className="muted">Files, URLs, and other review artifacts emitted by report plugins.</p>
        </div>
        {refs.length > 0 ? (
          <button className="slice-ask-button" type="button" onClick={() => onCopyCommand("plugin-artifacts:refs", refs.join("\n"))}>
            {copiedKey === "plugin-artifacts:refs" ? "Copied" : "Copy artifact refs"}
          </button>
        ) : null}
      </div>
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key}>{column.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {artifacts.map((row, index) => (
            <tr key={String(row.id || row.path || row.url || row.artifact || index)}>
              {columns.map((column) => (
                <td key={column.key} className={numericCell(row[column.key])}>
                  {displayValue(row[column.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function PluginsTab({
  report,
  copiedActionKey,
  onAsk,
  onCopyCommand,
  onOpenGraphSlice,
  onOpenTab
}: {
  report: AGraphReport;
  copiedActionKey: string | null;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const panels = externalPanels(report);
  const diagnostics = report.plugins?.diagnostics || [];
  const artifacts = report.plugins?.artifacts || [];
  const actions = pluginPanelActions({ copiedKey: copiedActionKey, onAsk, onCopyCommand, onOpenGraphSlice, onOpenTab });

  return (
    <div className="report-grid">
      {panels.length === 0 ? (
        <section className="panel span-2">
          <h2>Report Plugins</h2>
          <p className="muted">No project report plugin panels were emitted.</p>
        </section>
      ) : (
        panels.map((panel) => <PluginPanel key={`${panelPluginId(panel)}:${panel.id}`} panel={panel} actions={actions} />)
      )}
      <PluginArtifacts artifacts={artifacts} copiedKey={copiedActionKey} onCopyCommand={onCopyCommand} />
      <PluginDiagnostics diagnostics={diagnostics} actions={actions} />
    </div>
  );
}

function AskTab({
  report,
  graph,
  scope,
  copiedKey,
  onCopyCommand
}: {
  report: AGraphReport;
  graph: AGraphGraph;
  scope: AskScope | null;
  copiedKey: string | null;
  onCopyCommand: (key: string, command: string) => void;
}) {
  const defaultQuestion = "What should I review next?";
  const quickPrompts = [
    "What is this project made of?",
    defaultQuestion,
    "What dependency issues exist?",
    "Which systems touch external APIs?",
    "What evidence coverage gaps exist?",
    "What is the system graph shape?"
  ];
  const [question, setQuestion] = useState(defaultQuestion);
  const [asked, setAsked] = useState(defaultQuestion);

  useEffect(() => {
    if (!scope) return;
    setQuestion(scope.question);
    setAsked(scope.question);
  }, [scope]);

  const answer = useMemo(() => answerReportQuestion(report, graph, asked), [asked, graph, report]);

  function submitQuestion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = question.trim();
    setAsked(trimmed || defaultQuestion);
  }

  function askPrompt(prompt: string) {
    setQuestion(prompt);
    setAsked(prompt);
  }

  return (
    <div className="report-grid">
      <section className="panel span-2">
        <p className="eyebrow">Report-local Ask</p>
        <h2>Ask this report</h2>
        <p className="muted">
          Answers use only the loaded report and graph artifacts. They are deterministic summaries with evidence rows,
          not live `bb ask` retrieval.
        </p>
        {scope ? (
          <div className="ask-scope">
            <p className="eyebrow">Scoped To</p>
            <strong>{scope.label}</strong>
            <span>{scope.source}</span>
          </div>
        ) : null}
        <form className="ask-form" onSubmit={submitQuestion}>
          <input
            type="search"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            aria-label="Ask question"
          />
          <button type="submit">Ask</button>
        </form>
        <div className="quick-prompts" aria-label="Quick prompts">
          {quickPrompts.map((prompt) => (
            <button key={prompt} type="button" onClick={() => askPrompt(prompt)}>
              {prompt}
            </button>
          ))}
        </div>
      </section>

      {scope?.evidenceRows?.length ? (
        <section className="panel span-2">
          <h2>Scope Evidence</h2>
          <ReviewEvidenceTable rows={scope.evidenceRows} />
        </section>
      ) : null}

      <section className="panel span-2">
        <div className="panel-header">
          <div>
            <p className="eyebrow">Question</p>
            <h2>{asked}</h2>
          </div>
          <div className="action-row-buttons">
            <button type="button" onClick={() => onCopyCommand("ask:answer-json", askAnswerPacket(asked, answer))}>
              {copiedKey === "ask:answer-json" ? "Copied" : "Copy answer JSON"}
            </button>
            {answer.related.length > 0 ? (
              <button type="button" onClick={() => onCopyCommand("ask:related-json", JSON.stringify(answer.related, null, 2))}>
                {copiedKey === "ask:related-json" ? "Copied" : "Copy related JSON"}
              </button>
            ) : null}
          </div>
        </div>
        <div className="ask-answer">
          <h3>{answer.title}</h3>
          {answer.summary.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      </section>

      <DataTable
        title="Evidence Used"
        rows={answer.evidence}
        columns={[
          { key: "source", label: "Source" },
          { key: "finding", label: "Finding" },
          { key: "count", label: "Count" }
        ]}
      />
      <DataTable
        title={answer.relatedTitle}
        rows={answer.related}
        columns={[
          { key: "category", label: "Category" },
          { key: "label", label: "Label" },
          { key: "system", label: "System" },
          { key: "kind", label: "Kind" },
          { key: "import", label: "Import" },
          { key: "relation", label: "Relation" },
          { key: "path", label: "Path" },
          { key: "count", label: "Count" },
          { key: "target-count", label: "Targets" },
          { key: "externalApis", label: "External APIs" },
          { key: "reason", label: "Reason" },
          { key: "source", label: "Source" },
          { key: "status", label: "Status" }
        ]}
        empty="No related rows for this answer."
      />
    </div>
  );
}

export function ReportPage({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const [activeTab, setActiveTab] = useState<ReportTab>("dashboard");
  const [askScope, setAskScope] = useState<AskScope | null>(null);
  const [systemSliceId, setSystemSliceId] = useState<string>("");
  const [copiedActionKey, setCopiedActionKey] = useState<string | null>(null);
  const askFromScope = useCallback((scope: AskScope) => {
    setAskScope(scope);
    setActiveTab("ask");
  }, []);
  const openGraphSlice = useCallback((sliceId: string) => {
    setSystemSliceId(sliceId);
    setActiveTab("systems");
  }, []);
  const copyCommand = useCallback((key: string, command: string) => {
    void navigator.clipboard?.writeText(command);
    setCopiedActionKey(key);
  }, []);

  const activePanel = useMemo(() => {
    switch (activeTab) {
      case "dashboard":
        return (
          <DashboardTab
            report={report}
            graph={graph}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
            copiedActionKey={copiedActionKey}
          />
        );
      case "ask":
        return <AskTab report={report} graph={graph} scope={askScope} copiedKey={copiedActionKey} onCopyCommand={copyCommand} />;
      case "systems":
        return (
          <SystemsTab
            report={report}
            graph={graph}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
            copiedActionKey={copiedActionKey}
            selectedSliceId={systemSliceId}
            onSelectSlice={setSystemSliceId}
          />
        );
      case "dependencies":
        return (
          <DependenciesTab
            report={report}
            copiedActionKey={copiedActionKey}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
          />
        );
      case "evidence":
        return (
          <EvidenceTab
            report={report}
            copiedActionKey={copiedActionKey}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
          />
        );
      case "maintenance":
        return (
          <MaintenanceTab
            report={report}
            copiedActionKey={copiedActionKey}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
          />
        );
      case "plugins":
        return (
          <PluginsTab
            report={report}
            copiedActionKey={copiedActionKey}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
          />
        );
      default:
        return (
          <DashboardTab
            report={report}
            graph={graph}
            onAsk={askFromScope}
            onCopyCommand={copyCommand}
            onOpenGraphSlice={openGraphSlice}
            onOpenTab={setActiveTab}
            copiedActionKey={copiedActionKey}
          />
        );
    }
  }, [activeTab, askFromScope, askScope, copiedActionKey, copyCommand, graph, openGraphSlice, report, systemSliceId]);

  return (
    <div className="report-page">
      <nav className="report-tabs" aria-label="Report sections">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            aria-current={activeTab === tab.id ? "page" : undefined}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </nav>
      {activePanel}
    </div>
  );
}
