import type { YggReport } from "../data/types";

export type ReviewQueueRow = {
  id: string;
  area: string;
  label: string;
  priority: number;
  severity: "high" | "medium" | "low";
  evidence: string;
  evidenceRows?: Array<Record<string, unknown>>;
  source: string;
  command?: string;
  graphSliceId?: string;
  targetTab: "ask" | "systems" | "dependencies" | "evidence" | "maintenance" | "plugins";
};

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function asRows(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? (value.filter((row) => row && typeof row === "object") as Array<Record<string, unknown>>) : [];
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "";
  if (Array.isArray(value)) return value.join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function countValue(value: unknown, key: string): number {
  const record = asRecord(value);
  const camelKey = key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase());
  const cell = record[key] ?? record[camelKey];
  return typeof cell === "number" ? cell : 0;
}

function firstCommand(report: YggReport, patterns: RegExp[]): string | undefined {
  return report.commands.find((command) => patterns.some((pattern) => pattern.test(command)));
}

function maintenanceRows(report: YggReport, key: string): Array<Record<string, unknown>> {
  const maintenance = asRecord(report.maintenance);
  return asRows(maintenance[key] || maintenance[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

function packageRows(report: YggReport, key: string): Array<Record<string, unknown>> {
  const packages = asRecord(report.packages);
  return asRows(packages[key] || packages[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

function externalApiReview(report: YggReport): Record<string, unknown> {
  const maintenance = asRecord(report.maintenance);
  return asRecord(maintenance.externalApiReview || maintenance["external-api-review"] || maintenance.external_api_review);
}

function firstRows(rows: Array<Record<string, unknown>>, limit = 5): Array<Record<string, unknown>> {
  return rows.slice(0, limit);
}

function freshnessEvidenceRows(freshness: Record<string, unknown>): Array<Record<string, unknown>> {
  return asRows(freshness.repos).map((repo) => {
    const counts = asRecord(repo.counts);
    return {
      repo: repo["repo-id"] || repo.repoId || repo.id,
      status: repo.status,
      changed: countValue(counts, "changed"),
      missing: countValue(counts, "missing"),
      unindexed: countValue(counts, "unindexed")
    };
  });
}

function freshnessRow(report: YggReport): ReviewQueueRow | null {
  const freshness = asRecord(report.evidence.freshness);
  if (freshness.status !== "stale") return null;
  const counts = asRecord(freshness.counts);
  const total =
    countValue(counts, "changed") +
    countValue(counts, "missing") +
    countValue(counts, "unindexed");

  return {
    id: "freshness:stale",
    area: "Evidence",
    label: "Refresh indexed graph basis",
    priority: 100 + total,
    severity: total > 0 ? "high" : "medium",
    evidence: `${total} file freshness issue(s): ${countValue(counts, "changed")} changed, ${countValue(counts, "missing")} missing, ${countValue(counts, "unindexed")} unindexed.`,
    evidenceRows: firstRows(freshnessEvidenceRows(freshness)),
    source: "evidence.freshness",
    command: firstCommand(report, [/sync .*--check/, /sync/]),
    targetTab: "evidence"
  };
}

function dependencyRows(report: YggReport): ReviewQueueRow[] {
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  const command = firstCommand(report, [/packages/, /dependency-review/, /sync check/]);
  const rows: ReviewQueueRow[] = [];
  const unresolved = countValue(counts, "unresolved-imports");
  const conflicts = countValue(counts, "version-conflicts");
  const withoutEvidence = countValue(counts, "declared-without-import-evidence");

  if (unresolved > 0) {
    const evidenceRows = packageRows(report, "unresolved-imports");
    const sample = evidenceRows[0];
    rows.push({
      id: "dependencies:unresolved-imports",
      area: "Dependencies",
      label: "Resolve import-to-package gaps",
      priority: 90 + unresolved,
      severity: "high",
      evidence: `${unresolved} unresolved import(s). ${sample ? `Sample: ${displayValue(sample.import || sample.path || sample.id)}.` : ""}`,
      evidenceRows: firstRows(evidenceRows),
      source: "packages.unresolved-imports",
      command,
      graphSliceId: "package-evidence",
      targetTab: "dependencies"
    });
  }

  if (conflicts > 0) {
    const evidenceRows = packageRows(report, "version-conflicts");
    const sample = evidenceRows[0];
    rows.push({
      id: "dependencies:version-conflicts",
      area: "Dependencies",
      label: "Inspect package version conflicts",
      priority: 80 + conflicts,
      severity: "high",
      evidence: `${conflicts} package conflict(s). ${sample ? `Sample: ${displayValue(sample.label || sample["package-name"] || sample.id)}.` : ""}`,
      evidenceRows: firstRows(evidenceRows),
      source: "packages.version-conflicts",
      command,
      graphSliceId: "package-evidence",
      targetTab: "dependencies"
    });
  }

  if (withoutEvidence > 0) {
    const evidenceRows = packageRows(report, "declared-without-import-evidence");
    rows.push({
      id: "dependencies:without-import-evidence",
      area: "Dependencies",
      label: "Review declared packages without import evidence",
      priority: 60 + withoutEvidence,
      severity: "medium",
      evidence: `${withoutEvidence} declared package(s) have no importing source evidence in the report.`,
      evidenceRows: firstRows(evidenceRows),
      source: "packages.declared-without-import-evidence",
      command,
      graphSliceId: "package-evidence",
      targetTab: "dependencies"
    });
  }

  return rows;
}

function maintenanceReviewRows(report: YggReport): ReviewQueueRow[] {
  const command = firstCommand(report, [/sync work/, /sync check/, /audit-scope/]);
  const groups = [
    { key: "decision-queue", area: "Maintenance", label: "Apply or reject pending graph decisions", severity: "high" as const },
    {
      key: "infra-review-queue",
      area: "Infrastructure",
      label: "Review infrastructure evidence",
      severity: "medium" as const,
      graphSliceId: "config-auth-evidence"
    },
    {
      key: "dependency-review-queue",
      area: "Dependencies",
      label: "Review dependency correction work",
      severity: "medium" as const,
      graphSliceId: "package-evidence"
    }
  ];

  return groups.flatMap((group, groupIndex) => {
    const rows = maintenanceRows(report, group.key);
    if (rows.length === 0) return [];
    const first = rows[0];
    return [
      {
        id: `maintenance:${group.key}`,
        area: group.area,
        label: group.label,
        priority: 70 - groupIndex + rows.length,
        severity: group.severity,
        evidence: `${rows.length} review row(s). ${displayValue(first.question || first.reason || first.target || first.artifact)}`,
        evidenceRows: firstRows(rows),
        source: `maintenance.${group.key}`,
        command,
        graphSliceId: group.graphSliceId,
        targetTab: "maintenance" as const
      }
    ];
  });
}

function externalRows(report: YggReport): ReviewQueueRow[] {
  const review = externalApiReview(report);
  const counts = asRecord(review.counts);
  const fanouts = asRows(review["source-fanouts"] || review.sourceFanouts || review.source_fanouts);
  const fanoutCount = countValue(counts, "source-fanouts");
  if (fanoutCount === 0 && fanouts.length === 0) return [];

  const first = fanouts[0];
  const peer = asRecord(first?.peer);
  return [
    {
      id: "external-api:fanouts",
      area: "External Surface",
      label: "Review external API fanouts",
      priority: 75 + fanoutCount,
      severity: fanoutCount > 5 ? "high" : "medium",
      evidence: `${fanoutCount || fanouts.length} source fanout(s). ${first ? `Largest visible sample: ${displayValue(peer.label || peer.id || peer["xt/id"] || first.id)}.` : ""}`,
      evidenceRows: firstRows(fanouts),
      source: "maintenance.external-api-review",
      command: firstCommand(report, [/ignore external-api/, /audit-scope/, /ask/]),
      graphSliceId: "external-surface",
      targetTab: "systems"
    }
  ];
}

function diagnosticRows(report: YggReport): ReviewQueueRow[] {
  const coverage = asRecord(report.coverage);
  const diagnostics = asRecord(coverage.diagnostics);
  const diagnosticCount = countValue(diagnostics, "total") || countValue(report.evidence.counts, "diagnostics");
  const pluginDiagnostics = report.plugins?.diagnostics || [];
  const rows: ReviewQueueRow[] = [];

  if (diagnosticCount > 0) {
    rows.push({
      id: "diagnostics:extractors",
      area: "Evidence",
      label: "Inspect extractor diagnostics",
      priority: 50 + diagnosticCount,
      severity: "medium",
      evidence: `${diagnosticCount} extractor diagnostic(s) are summarized in coverage.`,
      evidenceRows: firstRows(asRows(diagnostics["by-stage"] || diagnostics.byStage)),
      source: "coverage.diagnostics",
      targetTab: "evidence"
    });
  }

  if (pluginDiagnostics.length > 0) {
    rows.push({
      id: "diagnostics:plugins",
      area: "Plugins",
      label: "Inspect report plugin diagnostics",
      priority: 45 + pluginDiagnostics.length,
      severity: "low",
      evidence: `${pluginDiagnostics.length} plugin diagnostic(s).`,
      evidenceRows: firstRows(pluginDiagnostics),
      source: "plugins.diagnostics",
      targetTab: "plugins"
    });
  }

  return rows;
}

export function reviewQueueRows(report: YggReport): ReviewQueueRow[] {
  return [
    freshnessRow(report),
    ...dependencyRows(report),
    ...maintenanceReviewRows(report),
    ...externalRows(report),
    ...diagnosticRows(report)
  ]
    .filter((row): row is ReviewQueueRow => Boolean(row))
    .sort((left, right) => right.priority - left.priority || left.area.localeCompare(right.area) || left.label.localeCompare(right.label));
}
