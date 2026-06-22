import { useEffect, useMemo, useState } from "react";
import { GraphPage } from "./graph/GraphPage";
import { ReportPage } from "./report/ReportPage";
import { artifactBaseFromLocation, artifactPath, loadJson } from "./data/artifactLoader";
import { fixtureGraph, fixtureReport } from "./fixtures/sampleData";
import type { YggGraph, YggReport } from "./data/types";

type Mode = "report" | "graph";

type BootData = {
  mode?: Mode;
  report?: YggReport;
  graph?: YggGraph;
};

declare global {
  interface Window {
    __YGG_BOOT__?: BootData;
  }
}

function modeFromLocation(): Mode {
  if (window.__YGG_BOOT__?.mode) {
    return window.__YGG_BOOT__.mode;
  }
  const params = new URLSearchParams(window.location.search);
  return params.get("mode") === "graph" ? "graph" : "report";
}

function refreshMsFromLocation(): number {
  if (!import.meta.env.DEV) {
    return 0;
  }
  const params = new URLSearchParams(window.location.search);
  if (params.get("refresh") === "0") {
    return 0;
  }
  const parsed = Number(params.get("refreshMs"));
  return Number.isFinite(parsed) && parsed >= 250 ? parsed : 1500;
}

function hrefForMode(mode: Mode): string {
  const params = new URLSearchParams(window.location.search);
  params.set("mode", mode);
  return `?${params.toString()}`;
}

export function App() {
  const boot = window.__YGG_BOOT__;
  const [mode] = useState<Mode>(modeFromLocation);
  const [artifactBase] = useState(artifactBaseFromLocation);
  const [refreshMs] = useState(refreshMsFromLocation);
  const [report, setReport] = useState<YggReport>(boot?.report ?? fixtureReport);
  const [graph, setGraph] = useState<YggGraph>(boot?.graph ?? fixtureGraph);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!artifactBase) {
      return;
    }

    const graphFile = mode === "graph" ? "graph.json" : "systems.json";
    let canceled = false;

    const loadArtifacts = () => {
      Promise.all([
        mode === "report" && (!boot?.report || artifactBase)
          ? loadJson<YggReport>(artifactPath(artifactBase, "report.json"))
          : Promise.resolve(boot?.report ?? fixtureReport),
        loadJson<YggGraph>(artifactPath(artifactBase, graphFile))
      ])
        .then(([loadedReport, loadedGraph]) => {
          if (canceled) {
            return;
          }
          setReport(loadedReport);
          setGraph(loadedGraph);
          setError(null);
        })
        .catch((err: unknown) => {
          if (canceled) {
            return;
          }
          setError(err instanceof Error ? err.message : String(err));
        });
    };

    loadArtifacts();
    const intervalId = refreshMs > 0 && artifactBase ? window.setInterval(loadArtifacts, refreshMs) : 0;
    return () => {
      canceled = true;
      if (intervalId) {
        window.clearInterval(intervalId);
      }
    };
  }, [artifactBase, boot?.graph, boot?.report, mode, refreshMs]);

  const title = useMemo(
    () => (mode === "graph" ? graph.title || "Yggdrasil Graph" : "Yggdrasil Report"),
    [graph.title, mode]
  );

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Yggdrasil</p>
          <h1>{title}</h1>
        </div>
        <nav className="mode-tabs" aria-label="View mode">
          <a aria-current={mode === "report" ? "page" : undefined} href={hrefForMode("report")}>
            Report
          </a>
          <a aria-current={mode === "graph" ? "page" : undefined} href={hrefForMode("graph")}>
            Graph
          </a>
        </nav>
      </header>
      {error ? (
        <div className="notice">
          {artifactBase ? "Could not refresh report artifacts." : "Using fixture data."} {error}
        </div>
      ) : null}
      {mode === "graph" ? <GraphPage graph={graph} /> : <ReportPage report={report} graph={graph} />}
    </main>
  );
}
