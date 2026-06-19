import type { CountRow } from "../data/types";
import type { TableColumn } from "./ReportPageTypes";
import { displayValue } from "./valueFormat";

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

export function asRows(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? (value.filter((row) => row && typeof row === "object") as Array<Record<string, unknown>>) : [];
}

export function numericCell(value: unknown): string | undefined {
  return typeof value === "number" ? "numeric-cell" : undefined;
}

export function firstValue(row: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    if (row[key] !== undefined && row[key] !== null) return row[key];
  }
  return undefined;
}

export function countValue(counts: unknown, key: string): number {
  const record = asRecord(counts);
  const value = firstValue(record, [key, key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
  return typeof value === "number" ? value : 0;
}

export function CountCard({ label, value, tone }: { label: string; value: number | string; tone?: "warn" | "ok" }) {
  return (
    <div className={`count-card${tone ? ` ${tone}` : ""}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function MetricStrip({ items }: { items: Array<{ label: string; value: number | string; tone?: "warn" | "ok" }> }) {
  return (
    <div className="metric-grid">
      {items.map((item) => (
        <CountCard key={item.label} label={item.label} value={item.value} tone={item.tone} />
      ))}
    </div>
  );
}

export function CountTable({ title, rows }: { title: string; rows: CountRow[] }) {
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

export function DataTable({
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

export function InlineTable({
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

export function CommandList({
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
