import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import type { YggGraph, YggReport } from "../data/types";
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
import {
  OperatorNextActions,
  ReportActions,
  ReviewEvidenceTable,
  ReviewQueue,
  correctionApplyCommands,
  correctionCompleteCommands,
  correctionResultTemplate,
  inferredColumns,
  operatorNextActionRows,
  pluginArtifactRefs,
  pluginPanelActions,
  projectMapPath,
  sourceRefs
} from "./ReportPageActions";
import {
  answerReportQuestion,
  askAnswerPacket,
  externalApiReview,
  fanoutRows,
  freshnessEvidencePacket,
  freshnessRepoRows,
  freshnessSampleRows,
  nestedLabel,
  reportAtlas
} from "./ReportPageAnswers";
import {
  CommandList,
  CountTable,
  DataTable,
  InlineTable,
  MetricStrip,
  asRecord,
  asRows,
  countValue,
  numericCell
} from "./ReportPageShared";
import { tabs, type AskScope, type ReportTab } from "./ReportPageTypes";
import { graphSlices, type GraphSlice } from "./graphSlices";
import { reviewQueueRows } from "./reviewQueue";
import { displayValue } from "./valueFormat";

function ExternalApiReview({ report }: { report: YggReport }) {
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

function ProjectAuditScopes({
  report,
  onAsk,
  onOpenTab
}: {
  report: YggReport;
  onAsk: (scope: AskScope) => void;
  onOpenTab: (tab: ReportTab) => void;
}) {
  const families = asRows(report.evidence.families);
  const audit = asRecord(report.audit);
  const auditScopes = asRows(audit.scopes);
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
  const scopeRows = auditScopes.map((row) => ({
    scope: String(row.kind || ""),
    files: countValue(row, "supportedFiles"),
    facts: countValue(row, "facts"),
    diagnostics: countValue(row, "diagnostics"),
    caveats: countValue(row, "skippedFiles") + countValue(row, "diagnostics"),
    source: "report.audit.scopes"
  }));
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
    ...scopeRows.map((row) => ({ axis: "audit-scope", ...row })),
    ...kindRows.map((row) => ({ axis: "kind", ...row })),
    ...stateRows.map((row) => ({ axis: "state", ...row }))
  ];

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Audit Scopes</h2>
          <p className="muted">Audit scopes, evidence families, fact kinds, freshness, and caveats from the current report packet.</p>
        </div>
        <div className="action-row-buttons">
          <button
            type="button"
            onClick={() =>
              onAsk({
                label: "Audit Scopes",
                source: "report.evidence",
                question: "What audit evidence does this report contain?",
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
        items={(scopeRows.length > 0 ? scopeRows : familyRows).map((row) => ({
          label: "scope" in row ? row.scope : row.family,
          value: "facts" in row ? row.facts : row.count,
          tone: "caveats" in row && row.caveats > 0 ? "warn" : "status" in row && row.status === "weak" ? "warn" : undefined
        }))}
      />
      {scopeRows.length > 0 ? (
        <InlineTable
          title="Audit Scopes"
          rows={scopeRows}
          columns={[
            { key: "scope", label: "Scope" },
            { key: "files", label: "Files" },
            { key: "facts", label: "Facts" },
            { key: "diagnostics", label: "Diagnostics" },
            { key: "source", label: "Source" }
          ]}
        />
      ) : null}
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
  report: YggReport;
  graph: YggGraph;
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
  const nextActions = operatorNextActionRows(report, asRows(atlas["next-actions"] || atlas.nextActions));
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

      <ProjectAuditScopes report={report} onAsk={onAsk} onOpenTab={onOpenTab} />
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
  report: YggReport;
  graph: YggGraph;
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
  graph: YggGraph;
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

function dependencyCommands(report: YggReport): string[] {
  const projectId = report.project.id;
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  const commands = new Set<string>([`ygg packages --project ${projectId} --json`]);

  if (countValue(counts, "version-conflicts") > 0) {
    commands.add(`ygg packages --project ${projectId} --with-conflicts --json`);
  }
  if (countValue(counts, "declared-without-import-evidence") > 0) {
    commands.add(`ygg packages --project ${projectId} --without-import-evidence --json`);
  }
  for (const row of asRows(packages.ecosystems)) {
    const ecosystem = displayValue(row.ecosystem);
    if (ecosystem) commands.add(`ygg packages --project ${projectId} --ecosystem ${ecosystem} --json`);
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
  report: YggReport;
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
  report: YggReport;
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
  report: YggReport;
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
  report: YggReport;
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
          <p className="muted">Validated path from review evidence to accepted `ygg.map.json` corrections.</p>
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
  report: YggReport;
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
  report: YggReport;
  graph: YggGraph;
  onAsk: (scope: AskScope) => void;
  onCopyCommand: (key: string, command: string) => void;
  onOpenGraphSlice: (sliceId: string) => void;
  onOpenTab: (tab: ReportTab) => void;
  copiedActionKey: string | null;
}) {
  const panels = pluginPanels(report);
  const reviewRows = reviewQueueRows(report);
  const atlas = reportAtlas(report, graph);
  const nextActions = operatorNextActionRows(report, asRows(atlas["next-actions"] || atlas.nextActions));
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
            <ProjectAuditScopes report={report} onAsk={onAsk} onOpenTab={onOpenTab} />
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

function claimAuthorityStatus(row: Record<string, unknown>): string {
  const authority = asRecord(row["claim-authority"] || row.claimAuthority);
  return String(authority.status || "");
}

function claimBlockers(row: Record<string, unknown>): string {
  const authority = asRecord(row["claim-authority"] || row.claimAuthority);
  return asRows(authority.blockers)
    .map((blocker) => String(blocker.code || ""))
    .filter(Boolean)
    .join(",");
}

function PluginPackageCaveats({ report }: { report: YggReport }) {
  const pluginPackages = report["plugin-packages"] || report.pluginPackages;
  const counts = asRecord(pluginPackages?.counts);
  const rows = asRows(pluginPackages?.packages).map((row) => ({
    id: row.id,
    version: row.version,
    scope: displayValue(asRecord(row.scope).kind),
    benchmark: displayValue(row["benchmark-status"] || row.benchmarkStatus),
    authority: claimAuthorityStatus(row),
    blockers: claimBlockers(row),
    warnings: displayValue(row.warnings),
    diagnose: row["diagnose-command"] || row.diagnoseCommand
  }));

  if (rows.length === 0 && Object.keys(counts).length === 0) return null;

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>Plugin Package Caveats</h2>
          <p className="muted">Installed package provenance, benchmark status, and claim authority.</p>
        </div>
      </div>
      <MetricStrip
        items={[
          { label: "Packages", value: countValue(counts, "packages") },
          { label: "Warnings", value: countValue(counts, "warnings"), tone: countValue(counts, "warnings") > 0 ? "warn" : undefined },
          { label: "Errors", value: countValue(counts, "errors"), tone: countValue(counts, "errors") > 0 ? "warn" : undefined },
          {
            label: "Unbenchmarked",
            value: countValue(counts, "unbenchmarked"),
            tone: countValue(counts, "unbenchmarked") > 0 ? "warn" : undefined
          }
        ]}
      />
      {rows.length === 0 ? (
        <p className="muted">No package rows.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Package</th>
              <th>Version</th>
              <th>Scope</th>
              <th>Benchmark</th>
              <th>Authority</th>
              <th>Blockers</th>
              <th>Warnings</th>
              <th>Diagnose</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={String(row.id || index)}>
                <td>{displayValue(row.id)}</td>
                <td>{displayValue(row.version)}</td>
                <td>{displayValue(row.scope)}</td>
                <td>{displayValue(row.benchmark)}</td>
                <td>{displayValue(row.authority)}</td>
                <td>{displayValue(row.blockers)}</td>
                <td>{displayValue(row.warnings)}</td>
                <td>{displayValue(row.diagnose)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
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
  report: YggReport;
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
      <PluginPackageCaveats report={report} />
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
  report: YggReport;
  graph: YggGraph;
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

export function ReportPage({ report, graph }: { report: YggReport; graph: YggGraph }) {
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
