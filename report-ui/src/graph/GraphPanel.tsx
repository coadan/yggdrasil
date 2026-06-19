import { useEffect, useMemo, useState } from "react";
import { SigmaContainer, useCamera, useLoadGraph, useRegisterEvents } from "@react-sigma/core";
import "@react-sigma/core/lib/style.css";
import { edgeKey, type GraphLayout, toGraphology } from "../data/agraphGraphAdapter";
import type { AGraphEdge, AGraphGraph, AGraphNode } from "../data/types";
import { displayValue } from "../report/valueFormat";
import { filterGraph, graphFilterOptions, type GraphFilters } from "./graphFilters";

type Selection = {
  type: "node" | "edge";
  id: string;
};

function GraphRuntime({
  graph,
  fitSignal,
  onSelect
}: {
  graph: ReturnType<typeof toGraphology>;
  fitSignal: number;
  onSelect: (selection: Selection | null) => void;
}) {
  const loadGraph = useLoadGraph();
  const camera = useCamera({ duration: 200 });
  const registerEvents = useRegisterEvents();

  useEffect(() => {
    loadGraph(graph);
    camera.reset();
  }, [camera, graph, loadGraph]);

  useEffect(() => {
    if (fitSignal > 0) {
      camera.reset();
    }
  }, [camera, fitSignal]);

  useEffect(() => {
    registerEvents({
      clickNode: ({ node }: { node: string }) => onSelect({ type: "node", id: node }),
      clickEdge: ({ edge }: { edge: string }) => onSelect({ type: "edge", id: edge }),
      clickStage: () => onSelect(null)
    });
  }, [onSelect, registerEvents]);

  return null;
}

function selectedRow(graph: AGraphGraph, selection: Selection | null): AGraphNode | AGraphEdge | null {
  if (!selection) return null;
  if (selection.type === "node") {
    return graph.nodes.find((node) => node.id === selection.id) || null;
  }
  return graph.edges.find((edge) => edgeKey(edge) === selection.id) || null;
}

function detailEntries(row: AGraphNode | AGraphEdge, keys: string[]): Array<[string, unknown]> {
  const record = row as Record<string, unknown>;
  return keys.map((key) => [key, record[key]] as [string, unknown]).filter(([, value]) => displayValue(value));
}

function recordEntries(value: unknown): Array<[string, unknown]> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  return Object.entries(value as Record<string, unknown>).filter(([, entry]) => displayValue(entry));
}

function DetailSection({ title, rows }: { title: string; rows: Array<[string, unknown]> }) {
  if (rows.length === 0) return null;
  return (
    <div className="detail-section">
      <h4>{title}</h4>
      <dl className="detail-list">
        {rows.map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{displayValue(value)}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function DetailRows({ row }: { row: AGraphNode | AGraphEdge | null }) {
  if (!row) {
    return <p className="muted">No selection.</p>;
  }

  const identityKeys = [
    "id",
    "label",
    "kind",
    "relation",
    "source",
    "target",
    "repo",
    "repoId",
    "repo_id",
    "path",
    "virtual"
  ];
  const structuredKeys = new Set([...identityKeys, "metrics", "attrs", "color", "size"]);
  const rawRows = Object.entries(row as Record<string, unknown>)
    .filter(([key, value]) => !structuredKeys.has(key) && displayValue(value))
    .sort(([left], [right]) => left.localeCompare(right));

  return (
    <div className="detail-sections">
      <DetailSection title="Identity" rows={detailEntries(row, identityKeys)} />
      <DetailSection title="Metrics" rows={recordEntries((row as AGraphNode).metrics)} />
      <DetailSection title="Attributes" rows={recordEntries((row as AGraphNode).attrs)} />
      <DetailSection title="Raw" rows={rawRows} />
    </div>
  );
}

function GraphRowsPreview({ graph }: { graph: AGraphGraph }) {
  const nodeRows = graph.nodes.slice(0, 5);
  const edgeRows = graph.edges.slice(0, 5);
  if (nodeRows.length === 0 && edgeRows.length === 0) return null;

  return (
    <div className="detail-sections graph-row-preview">
      <div className="detail-section">
        <h4>Graph Rows</h4>
        {nodeRows.length > 0 ? (
          <table>
            <thead>
              <tr>
                <th>Node</th>
                <th>Kind</th>
              </tr>
            </thead>
            <tbody>
              {nodeRows.map((node) => (
                <tr key={node.id}>
                  <td>{displayValue(node.label || node.id)}</td>
                  <td>{displayValue(node.kind)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {edgeRows.length > 0 ? (
          <table>
            <thead>
              <tr>
                <th>Relation</th>
                <th>Source</th>
                <th>Target</th>
              </tr>
            </thead>
            <tbody>
              {edgeRows.map((edge) => (
                <tr key={edgeKey(edge)}>
                  <td>{displayValue(edge.relation)}</td>
                  <td>{displayValue(edge.source)}</td>
                  <td>{displayValue(edge.target)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </div>
    </div>
  );
}

function externalApiCount(graph: AGraphGraph): number {
  return graph.nodes.filter((node) => node.kind === "external-api").length;
}

function defaultExternalApiMode(graph: AGraphGraph): GraphFilters["externalApiMode"] {
  return externalApiCount(graph) >= 20 ? "group" : "show";
}

function emptyFiltersForGraph(graph: AGraphGraph): GraphFilters {
  return {
    query: "",
    kind: "",
    relation: "",
    cluster: "",
    externalApiMode: defaultExternalApiMode(graph),
    minDegree: "0"
  };
}

function untouchedFilters(filters: GraphFilters): boolean {
  return (
    filters.query === "" &&
    filters.kind === "" &&
    filters.relation === "" &&
    filters.cluster === "" &&
    filters.minDegree === "0"
  );
}

export function GraphPanel({ graph }: { graph: AGraphGraph }) {
  const [filters, setFilters] = useState<GraphFilters>(() => emptyFiltersForGraph(graph));
  const [layout, setLayout] = useState<GraphLayout>("circle");
  const [selection, setSelection] = useState<Selection | null>(null);
  const [fitSignal, setFitSignal] = useState(0);
  const options = useMemo(() => graphFilterOptions(graph), [graph]);
  const filtered = useMemo(() => filterGraph(graph, filters), [filters, graph]);
  const graphology = useMemo(() => toGraphology(filtered.graph, layout), [filtered.graph, layout]);
  const row = useMemo(() => selectedRow(filtered.graph, selection), [filtered.graph, selection]);

  useEffect(() => {
    const nextMode = defaultExternalApiMode(graph);
    setFilters((current) =>
      untouchedFilters(current) && current.externalApiMode !== nextMode
        ? { ...current, externalApiMode: nextMode }
        : current
    );
    setSelection(null);
  }, [graph]);

  function setFilter<Key extends keyof GraphFilters>(key: Key, value: GraphFilters[Key]) {
    setFilters((current) => ({ ...current, [key]: value }));
    setSelection(null);
  }

  function resetControls() {
    setFilters(emptyFiltersForGraph(graph));
    setLayout("circle");
    setSelection(null);
    setFitSignal((value) => value + 1);
  }

  return (
    <section className="panel graph-panel">
      <header className="panel-header">
        <div>
          <h2>{graph.title || "Graph"}</h2>
          <p className="muted">
            {filtered.visibleNodes} of {graph.nodes.length} nodes, {filtered.visibleEdges} of {graph.edges.length}{" "}
            edges
          </p>
        </div>
      </header>
      {graph.nodes.length === 0 ? (
        <p className="muted">No graph rows available.</p>
      ) : (
        <div className="graph-workspace">
          <div className="graph-main">
            <div className="graph-toolbar" aria-label="Graph controls">
              <label>
                <span>Search</span>
                <input
                  type="search"
                  value={filters.query}
                  onChange={(event) => setFilter("query", event.target.value)}
                />
              </label>
              <label>
                <span>Kind</span>
                <select value={filters.kind} onChange={(event) => setFilter("kind", event.target.value)}>
                  <option value="">All kinds</option>
                  {options.kinds.map((kind) => (
                    <option key={kind} value={kind}>
                      {kind}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Relation</span>
                <select value={filters.relation} onChange={(event) => setFilter("relation", event.target.value)}>
                  <option value="">All relations</option>
                  {options.relations.map((relation) => (
                    <option key={relation} value={relation}>
                      {relation}
                    </option>
                  ))}
                </select>
              </label>
              {options.clusters.length > 0 ? (
                <label>
                  <span>Cluster</span>
                  <select value={filters.cluster} onChange={(event) => setFilter("cluster", event.target.value)}>
                    <option value="">All clusters</option>
                    {options.clusters.map((cluster) => (
                      <option key={cluster.value} value={cluster.value}>
                        {cluster.label}
                      </option>
                    ))}
                  </select>
                </label>
              ) : null}
              <label>
                <span>External APIs</span>
                <select
                  value={filters.externalApiMode}
                  onChange={(event) =>
                    setFilter("externalApiMode", event.target.value as GraphFilters["externalApiMode"])
                  }
                >
                  <option value="show">Show</option>
                  <option value="group">Group</option>
                  <option value="hide">Hide</option>
                </select>
              </label>
              <label>
                <span>Min degree</span>
                <select value={filters.minDegree} onChange={(event) => setFilter("minDegree", event.target.value)}>
                  <option value="0">Any</option>
                  <option value="1">1+</option>
                  <option value="2">2+</option>
                  <option value="3">3+</option>
                </select>
              </label>
              <label>
                <span>Layout</span>
                <select value={layout} onChange={(event) => setLayout(event.target.value as GraphLayout)}>
                  <option value="circle">Circle</option>
                  <option value="grid">Grid</option>
                </select>
              </label>
              <div className="graph-toolbar-actions">
                <button type="button" onClick={() => setFitSignal((value) => value + 1)}>
                  Fit
                </button>
                <button type="button" onClick={resetControls}>
                  Reset
                </button>
              </div>
            </div>
            <div className="graph-canvas">
              {filtered.visibleNodes === 0 ? (
                <p className="muted graph-empty">No rows match the current filters.</p>
              ) : (
                <SigmaContainer settings={{ allowInvalidContainer: true }}>
                  <GraphRuntime graph={graphology} fitSignal={fitSignal} onSelect={setSelection} />
                </SigmaContainer>
              )}
            </div>
          </div>
          <aside className="graph-inspector">
            <h3>{selection ? (selection.type === "node" ? "Node" : "Edge") : "Selection"}</h3>
            <DetailRows row={row} />
            <GraphRowsPreview graph={filtered.graph} />
          </aside>
        </div>
      )}
    </section>
  );
}
