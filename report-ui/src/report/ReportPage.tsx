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

      <CountTable title="File Kinds" rows={fileKindRows(report)} />
      <CountTable title="Node Kinds" rows={nodeKindRows(report)} />
      <CountTable title="Edge Relations" rows={edgeRelationRows(report)} />
    </div>
  );
}
