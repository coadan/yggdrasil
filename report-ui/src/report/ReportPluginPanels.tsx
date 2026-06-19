import type { ReactNode } from "react";
import type { AGraphReport, ReportPluginDiagnostic, ReportPluginPanel } from "../data/types";

type TableColumn = {
  key: string;
  label: string;
};

const coreReportPluginId = "agraph-core-report";

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

export function panelPluginId(panel: ReportPluginPanel): string {
  return String(panel.plugin?.id || "unknown-plugin");
}

export function pluginPanels(report: AGraphReport): ReportPluginPanel[] {
  return [...(report.plugins?.panels || [])].sort((left, right) => {
    const leftOrder = typeof left.order === "number" ? left.order : 1000;
    const rightOrder = typeof right.order === "number" ? right.order : 1000;
    return (
      left.slot.localeCompare(right.slot) ||
      leftOrder - rightOrder ||
      left.label.localeCompare(right.label) ||
      left.id.localeCompare(right.id)
    );
  });
}

function panelsForSlot(report: AGraphReport, slot: string, includeCore = false): ReportPluginPanel[] {
  return pluginPanels(report).filter((panel) => {
    if (panel.slot !== slot) return false;
    if (includeCore) return true;
    return panelPluginId(panel) !== coreReportPluginId;
  });
}

export function externalPanels(report: AGraphReport): ReportPluginPanel[] {
  return pluginPanels(report).filter((panel) => panelPluginId(panel) !== coreReportPluginId);
}

function tableColumnsFrom(value: unknown): TableColumn[] {
  const record = asRecord(value);
  const explicit = asRows(record.columns)
    .map((column) => {
      const key = displayValue(column.key);
      return key ? { key, label: displayValue(column.label || column.key) } : null;
    })
    .filter(Boolean) as TableColumn[];
  if (explicit.length > 0) return explicit;

  const rows = Array.isArray(value) ? asRows(value) : asRows(record.rows);
  const keys = new Set<string>();
  for (const row of rows.slice(0, 5)) {
    Object.keys(row).forEach((key) => keys.add(key));
  }
  return Array.from(keys)
    .slice(0, 8)
    .map((key) => ({ key, label: key }));
}

function tableRowsFrom(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return asRows(value);
  return asRows(asRecord(value).rows);
}

function InlineDataTable({ value }: { value: unknown }) {
  const columns = tableColumnsFrom(value);
  const rows = tableRowsFrom(value);
  const empty = displayValue(asRecord(value).empty) || "No rows.";

  if (columns.length === 0) {
    return <p className="muted">{empty}</p>;
  }

  return rows.length === 0 ? (
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
          <tr key={String(row.id || row.key || index)}>
            {columns.map((column) => (
              <td key={column.key} className={numericCell(row[column.key])}>
                {displayValue(row[column.key])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function InlineCommandList({ value }: { value: unknown }) {
  const commands = Array.isArray(value) ? value.map(displayValue).filter(Boolean) : [];
  return commands.length === 0 ? (
    <p className="muted">No commands.</p>
  ) : (
    <ul className="command-list">
      {commands.map((command) => (
        <li key={command}>
          <code>{command}</code>
        </li>
      ))}
    </ul>
  );
}

function metricTone(value: unknown): "warn" | "ok" | undefined {
  if (value === "warn" || value === "ok") return value;
  return undefined;
}

function PluginMetricGrid({ value }: { value: unknown }) {
  const items = asRows(value).map((row) => ({
    label: displayValue(firstValue(row, ["label", "name", "key"])),
    value: displayValue(firstValue(row, ["value", "count", "total"])) || 0,
    tone: metricTone(firstValue(row, ["tone"]))
  }));
  return items.length === 0 ? <p className="muted">No metrics.</p> : <MetricStrip items={items} />;
}

function PluginCallout({ value }: { value: unknown }) {
  const record = asRecord(value);
  const tone = displayValue(record.tone);
  const text = displayValue(record.message || record.text || value);
  return <div className={`plugin-callout${tone ? ` ${tone}` : ""}`}>{text || "No message."}</div>;
}

function KeyValueTable({ value }: { value: unknown }) {
  const record = asRecord(value);
  const rows = Object.entries(record).map(([key, cell]) => ({ key, value: cell }));
  return <InlineDataTable value={{ columns: [{ key: "key", label: "Key" }, { key: "value", label: "Value" }], rows }} />;
}

function dataForPanel(panel: ReportPluginPanel, key?: string): unknown {
  const data = asRecord(panel.data);
  return key ? data[key] : data;
}

function parseMdxProps(source: string): Record<string, string> {
  const props: Record<string, string> = {};
  for (const match of source.matchAll(/([A-Za-z][A-Za-z0-9]*)="([^"]*)"/g)) {
    props[match[1]] = match[2];
  }
  return props;
}

function renderMdxComponent(panel: ReportPluginPanel, name: string, props: Record<string, string>, key: string): ReactNode {
  const value = dataForPanel(panel, props.dataKey);
  switch (name) {
    case "MetricGrid":
      return <PluginMetricGrid key={key} value={value} />;
    case "DataTable":
      return <InlineDataTable key={key} value={value} />;
    case "CommandList":
      return <InlineCommandList key={key} value={value} />;
    case "Callout":
      return <PluginCallout key={key} value={value} />;
    case "KeyValueTable":
      return <KeyValueTable key={key} value={value} />;
    default:
      return (
        <p key={key} className="plugin-warning">
          Unsupported report component: {name}
        </p>
      );
  }
}

function renderPluginMdx(panel: ReportPluginPanel): ReactNode[] {
  const nodes: ReactNode[] = [];
  const lines = (panel.mdx || "").split(/\r?\n/);
  let list: string[] = [];
  let code: string[] = [];
  let inCode = false;

  function flushList(key: string) {
    if (list.length === 0) return;
    const items = list;
    list = [];
    nodes.push(
      <ul key={key}>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    );
  }

  lines.forEach((line, index) => {
    const key = `${panel.id}-${index}`;
    const trimmed = line.trim();

    if (trimmed.startsWith("```")) {
      if (inCode) {
        nodes.push(
          <pre key={key}>
            <code>{code.join("\n")}</code>
          </pre>
        );
        code = [];
        inCode = false;
      } else {
        flushList(`${key}-list`);
        code = [];
        inCode = true;
      }
      return;
    }

    if (inCode) {
      code.push(line);
      return;
    }

    if (!trimmed) {
      flushList(`${key}-list`);
      return;
    }

    const component = trimmed.match(/^<([A-Z][A-Za-z0-9]*)\s*([^>]*)\/>$/);
    if (component) {
      flushList(`${key}-list`);
      nodes.push(renderMdxComponent(panel, component[1], parseMdxProps(component[2]), key));
      return;
    }

    if (/^(import|export)\b/.test(trimmed) || /[{}`]/.test(trimmed)) {
      flushList(`${key}-list`);
      nodes.push(
        <p key={key} className="plugin-warning">
          Skipped unsupported MDX expression.
        </p>
      );
      return;
    }

    if (trimmed.startsWith("### ")) {
      flushList(`${key}-list`);
      nodes.push(<h4 key={key}>{trimmed.slice(4)}</h4>);
      return;
    }

    if (trimmed.startsWith("## ")) {
      flushList(`${key}-list`);
      nodes.push(<h3 key={key}>{trimmed.slice(3)}</h3>);
      return;
    }

    if (trimmed.startsWith("# ")) {
      flushList(`${key}-list`);
      nodes.push(<h3 key={key}>{trimmed.slice(2)}</h3>);
      return;
    }

    if (trimmed.startsWith("- ")) {
      list.push(trimmed.slice(2));
      return;
    }

    flushList(`${key}-list`);
    nodes.push(<p key={key}>{trimmed}</p>);
  });

  flushList(`${panel.id}-tail-list`);
  if (inCode) {
    nodes.push(
      <pre key={`${panel.id}-tail-code`}>
        <code>{code.join("\n")}</code>
      </pre>
    );
  }
  return nodes.length > 0 ? nodes : [<p key={`${panel.id}-empty`} className="muted">No plugin content.</p>];
}

export function PluginPanel({ panel }: { panel: ReportPluginPanel }) {
  return (
    <section className="panel plugin-panel">
      <div className="plugin-panel-meta">
        <span>{panelPluginId(panel)}</span>
        <span>{panel.slot}</span>
      </div>
      {panel.description ? <p className="muted">{panel.description}</p> : null}
      <div className="plugin-mdx">{renderPluginMdx(panel)}</div>
    </section>
  );
}

export function PluginPanelList({ report, slot, includeCore = false }: { report: AGraphReport; slot?: string; includeCore?: boolean }) {
  const panels = slot ? panelsForSlot(report, slot, includeCore) : pluginPanels(report);
  if (panels.length === 0) return null;
  return (
    <>
      {panels.map((panel) => (
        <PluginPanel key={`${panelPluginId(panel)}:${panel.id}`} panel={panel} />
      ))}
    </>
  );
}

export function PluginDiagnostics({ diagnostics }: { diagnostics: ReportPluginDiagnostic[] }) {
  if (diagnostics.length === 0) return null;
  return (
    <section className="panel span-2">
      <h2>Plugin Diagnostics</h2>
      <table>
        <thead>
          <tr>
            <th>Plugin</th>
            <th>Stage</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {diagnostics.map((diagnostic, index) => (
            <tr key={`${displayValue(diagnostic.plugin?.id)}:${diagnostic.stage}:${index}`}>
              <td>{displayValue(diagnostic.plugin?.id)}</td>
              <td>{displayValue(diagnostic.stage)}</td>
              <td>{displayValue(diagnostic.message)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
