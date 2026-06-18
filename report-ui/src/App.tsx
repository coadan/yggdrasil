import { useEffect, useMemo, useState } from "react";
import { GraphPage } from "./graph/GraphPage";
import { ReportPage } from "./report/ReportPage";
import { loadJson } from "./data/artifactLoader";
import { fixtureGraph, fixtureReport } from "./fixtures/sampleData";
import type { AGraphGraph, AGraphReport } from "./data/types";

type Mode = "report" | "graph";

type BootData = {
  mode?: Mode;
  report?: AGraphReport;
  graph?: AGraphGraph;
};

declare global {
  interface Window {
    __AGRAPH_BOOT__?: BootData;
  }
}

function modeFromLocation(): Mode {
  if (window.__AGRAPH_BOOT__?.mode) {
    return window.__AGRAPH_BOOT__.mode;
  }
  const params = new URLSearchParams(window.location.search);
  return params.get("mode") === "graph" ? "graph" : "report";
}

export function App() {
  const boot = window.__AGRAPH_BOOT__;
  const [mode] = useState<Mode>(modeFromLocation);
  const [report, setReport] = useState<AGraphReport>(boot?.report ?? fixtureReport);
  const [graph, setGraph] = useState<AGraphGraph>(boot?.graph ?? fixtureGraph);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (boot?.graph && (mode === "graph" || boot.report)) {
      return;
    }

    const graphFile = mode === "graph" ? "graph.json" : "systems.json";
    Promise.all([
      mode === "report" && !boot?.report
        ? loadJson<AGraphReport>("report.json")
        : Promise.resolve(boot?.report ?? fixtureReport),
      loadJson<AGraphGraph>(graphFile)
    ])
      .then(([loadedReport, loadedGraph]) => {
        setReport(loadedReport);
        setGraph(loadedGraph);
        setError(null);
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : String(err));
      });
  }, [boot?.graph, boot?.report, mode]);

  const title = useMemo(
    () => (mode === "graph" ? graph.title || "AGraph Graph" : "AGraph Report"),
    [graph.title, mode]
  );

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">AGraph</p>
          <h1>{title}</h1>
        </div>
        <nav className="mode-tabs" aria-label="View mode">
          <a aria-current={mode === "report" ? "page" : undefined} href="?mode=report">
            Report
          </a>
          <a aria-current={mode === "graph" ? "page" : undefined} href="?mode=graph">
            Graph
          </a>
        </nav>
      </header>
      {error ? <div className="notice">Using fixture data. {error}</div> : null}
      {mode === "graph" ? <GraphPage graph={graph} /> : <ReportPage report={report} graph={graph} />}
    </main>
  );
}
