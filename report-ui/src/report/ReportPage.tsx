import { useMemo, useState } from "react";
import type { AGraphGraph, AGraphReport, CountRow } from "../data/types";
import { edgeRelationRows, fileKindRows, nodeKindRows, numericCount } from "../data/reportAdapter";
import { GraphPanel } from "../graph/GraphPanel";

type ReportTab = "atlas" | "systems" | "dependencies" | "evidence" | "maintenance";

type TableColumn = {
  key: string;
  label: string;
};

const tabs: Array<{ id: ReportTab; label: string }> = [
  { id: "atlas", label: "Atlas" },
  { id: "systems", label: "Systems" },
  { id: "dependencies", label: "Dependencies" },
  { id: "evidence", label: "Evidence" },
  { id: "maintenance", label: "Maintenance" }
];

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
                <td>{row.count}</td>
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
                  <td key={column.key}>{displayValue(row[column.key])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function CommandList({ commands }: { commands: string[] }) {
  return (
    <section className="panel">
      <h2>Suggested Commands</h2>
      {commands.length === 0 ? (
        <p className="muted">No commands.</p>
      ) : (
        <ul className="command-list">
          {commands.map((command) => (
            <li key={command}>
              <code>{command}</code>
            </li>
          ))}
        </ul>
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
                <td>{Number(row.targetCount || row["target-count"] || 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

function AtlasTab({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const atlas = reportAtlas(report, graph);
  const evidence = asRecord(atlas.evidence);
  const systems = asRecord(atlas.systems);
  const dependencies = asRecord(atlas.dependencies);
  const maintenance = asRecord(atlas.maintenance);
  const queue = asRecord(maintenance.queue);
  const externalApi = asRecord(maintenance["external-api-review"] || maintenance.externalApiReview);
  const nextActions = asRows(atlas["next-actions"] || atlas.nextActions);

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

      <section className="panel">
        <h2>Operator Next Actions</h2>
        {nextActions.length === 0 ? (
          <p className="muted">No queued next actions in this report.</p>
        ) : (
          <table>
            <tbody>
              {nextActions.map((row, index) => (
                <tr key={String(row.kind || index)}>
                  <td>{displayValue(row.label || row.kind)}</td>
                  <td>{displayValue(row.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function SystemsTab({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const maintenance = asRecord(report.maintenance);
  return (
    <div className="report-grid">
      <div className="span-2">
        <GraphPanel graph={graph} />
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
    </div>
  );
}

function DependenciesTab({ report }: { report: AGraphReport }) {
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
    </div>
  );
}

function EvidenceTab({ report }: { report: AGraphReport }) {
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
    </div>
  );
}

function MaintenanceTab({ report }: { report: AGraphReport }) {
  const maintenance = asRecord(report.maintenance);
  return (
    <div className="report-grid">
      <CommandList commands={report.commands} />
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
    </div>
  );
}

export function ReportPage({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const [activeTab, setActiveTab] = useState<ReportTab>("atlas");
  const activePanel = useMemo(() => {
    switch (activeTab) {
      case "systems":
        return <SystemsTab report={report} graph={graph} />;
      case "dependencies":
        return <DependenciesTab report={report} />;
      case "evidence":
        return <EvidenceTab report={report} />;
      case "maintenance":
        return <MaintenanceTab report={report} />;
      case "atlas":
      default:
        return <AtlasTab report={report} graph={graph} />;
    }
  }, [activeTab, graph, report]);

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
