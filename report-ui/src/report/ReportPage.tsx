import { useMemo, useState, type FormEvent } from "react";
import type { AGraphGraph, AGraphReport, CountRow } from "../data/types";
import { edgeRelationRows, fileKindRows, nodeKindRows, numericCount } from "../data/reportAdapter";
import { GraphPanel } from "../graph/GraphPanel";
import {
  externalPanels,
  panelPluginId,
  PluginDiagnostics,
  PluginPanel,
  PluginPanelList,
  pluginPanels
} from "./ReportPluginPanels";

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

const tabs: Array<{ id: ReportTab; label: string }> = [
  { id: "dashboard", label: "Dashboard" },
  { id: "ask", label: "Ask" },
  { id: "systems", label: "Systems" },
  { id: "dependencies", label: "Dependencies" },
  { id: "evidence", label: "Evidence" },
  { id: "maintenance", label: "Maintenance" },
  { id: "plugins", label: "Plugins" }
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

function packageRows(report: AGraphReport, key: string): Array<Record<string, unknown>> {
  const packages = asRecord(report.packages);
  return asRows(packages[key] || packages[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
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
                  <td className={numericCell(row.count)}>{displayValue(row.count)}</td>
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
      <PluginPanelList report={report} slot="systems" />
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
      <PluginPanelList report={report} slot="dependencies" />
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
      <PluginPanelList report={report} slot="evidence" />
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
      <PluginPanelList report={report} slot="maintenance" />
    </div>
  );
}

function DashboardTab({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const panels = pluginPanels(report);
  if (panels.length === 0) return <AtlasTab report={report} graph={graph} />;

  return (
    <div className="report-grid">
      <section className="panel span-2">
        <p className="eyebrow">Report Dashboard</p>
        <h2>{report.project.name || report.project.id}</h2>
      </section>
      <PluginPanelList report={report} includeCore />
      <PluginDiagnostics diagnostics={report.plugins?.diagnostics || []} />
    </div>
  );
}

function PluginsTab({ report }: { report: AGraphReport }) {
  const panels = externalPanels(report);
  const diagnostics = report.plugins?.diagnostics || [];

  return (
    <div className="report-grid">
      {panels.length === 0 ? (
        <section className="panel span-2">
          <h2>Report Plugins</h2>
          <p className="muted">No project report plugin panels were emitted.</p>
        </section>
      ) : (
        panels.map((panel) => <PluginPanel key={`${panelPluginId(panel)}:${panel.id}`} panel={panel} />)
      )}
      <PluginDiagnostics diagnostics={diagnostics} />
    </div>
  );
}

function AskTab({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const defaultQuestion = "What should I review next?";
  const quickPrompts = [
    defaultQuestion,
    "What dependency issues exist?",
    "Which systems touch external APIs?",
    "What evidence coverage gaps exist?",
    "What is the system graph shape?"
  ];
  const [question, setQuestion] = useState(defaultQuestion);
  const [asked, setAsked] = useState(defaultQuestion);
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

      <section className="panel span-2">
        <div className="panel-header">
          <div>
            <p className="eyebrow">Question</p>
            <h2>{asked}</h2>
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
          { key: "label", label: "Label" },
          { key: "system", label: "System" },
          { key: "kind", label: "Kind" },
          { key: "import", label: "Import" },
          { key: "relation", label: "Relation" },
          { key: "path", label: "Path" },
          { key: "count", label: "Count" },
          { key: "target-count", label: "Targets" },
          { key: "externalApis", label: "External APIs" },
          { key: "reason", label: "Reason" }
        ]}
        empty="No related rows for this answer."
      />
    </div>
  );
}

export function ReportPage({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  const [activeTab, setActiveTab] = useState<ReportTab>("dashboard");
  const activePanel = useMemo(() => {
    switch (activeTab) {
      case "dashboard":
        return <DashboardTab report={report} graph={graph} />;
      case "ask":
        return <AskTab report={report} graph={graph} />;
      case "systems":
        return <SystemsTab report={report} graph={graph} />;
      case "dependencies":
        return <DependenciesTab report={report} />;
      case "evidence":
        return <EvidenceTab report={report} />;
      case "maintenance":
        return <MaintenanceTab report={report} />;
      case "plugins":
        return <PluginsTab report={report} />;
      default:
        return <DashboardTab report={report} graph={graph} />;
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
