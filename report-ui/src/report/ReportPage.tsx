import type { AGraphGraph, AGraphReport, CountRow } from "../data/types";
import { edgeRelationRows, fileKindRows, nodeKindRows, numericCount } from "../data/reportAdapter";
import { GraphPanel } from "../graph/GraphPanel";

function CountCard({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="count-card">
      <span>{label}</span>
      <strong>{value}</strong>
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

function countValue(counts: unknown, key: string): number {
  if (!counts || typeof counts !== "object") return 0;
  const value = (counts as Record<string, unknown>)[key];
  return typeof value === "number" ? value : 0;
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
  return Array.isArray(rows) ? (rows as Array<Record<string, unknown>>) : [];
}

function nestedLabel(row: Record<string, unknown>, key: string): string {
  const value = row[key];
  if (!value || typeof value !== "object") return "";
  const nested = value as Record<string, unknown>;
  return String(nested.label || nested.id || nested["xt/id"] || "");
}

function ExternalApiReview({ report }: { report: AGraphReport }) {
  const review = externalApiReview(report);
  if (!review) return null;

  const counts = review.counts;
  const fanouts = fanoutRows(review).slice(0, 8);
  const apiNodes = countValue(counts, "nodes");
  const sourceFanouts = countValue(counts, "source-fanouts") || countValue(counts, "sourceFanouts");

  if (apiNodes === 0 && sourceFanouts === 0) return null;

  return (
    <section className="panel span-2">
      <div className="panel-header">
        <div>
          <h2>External API Review</h2>
          <p className="muted">Grouped by graph topology: source, relation, direction, and visibility.</p>
        </div>
      </div>
      <div className="metric-grid compact">
        <CountCard label="External APIs" value={apiNodes} />
        <CountCard label="External Edges" value={countValue(counts, "edges")} />
        <CountCard label="Source Fanouts" value={sourceFanouts} />
        <CountCard label="Single Evidence" value={countValue(counts, "single-evidence-nodes")} />
        <CountCard label="Support Only" value={countValue(counts, "support-only-nodes")} />
      </div>
      {fanouts.length > 0 ? (
        <table>
          <tbody>
            {fanouts.map((row) => (
              <tr key={String(row.id)}>
                <td>{nestedLabel(row, "peer")}</td>
                <td>{String(row.relation || "")}</td>
                <td>{String(row.visibility || "")}</td>
                <td>{Number(row.targetCount || row["target-count"] || 0)} targets</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

export function ReportPage({ report, graph }: { report: AGraphReport; graph: AGraphGraph }) {
  return (
    <div className="report-grid">
      <section className="panel span-2">
        <p className="eyebrow">Project</p>
        <h2>{report.project.name || report.project.id}</h2>
        <div className="metric-grid">
          <CountCard label="Files" value={numericCount(report, "files")} />
          <CountCard label="Nodes" value={numericCount(report, "nodes")} />
          <CountCard label="Edges" value={numericCount(report, "edges")} />
          <CountCard label="Packages" value={numericCount(report, "packages")} />
          <CountCard label="Diagnostics" value={numericCount(report, "diagnostics")} />
        </div>
      </section>

      <section className="panel">
        <h2>Available Evidence</h2>
        <div className="chips">
          {report.evidence.available.map((item) => (
            <span key={item}>{item}</span>
          ))}
        </div>
      </section>

      <section className="panel">
        <h2>Suggested Commands</h2>
        <ul className="command-list">
          {report.commands.map((command) => (
            <li key={command}>
              <code>{command}</code>
            </li>
          ))}
        </ul>
      </section>

      <div className="span-2">
        <GraphPanel graph={graph} />
      </div>

      <ExternalApiReview report={report} />

      <CountTable title="File Kinds" rows={fileKindRows(report)} />
      <CountTable title="Node Kinds" rows={nodeKindRows(report)} />
      <CountTable title="Edge Relations" rows={edgeRelationRows(report)} />
    </div>
  );
}
