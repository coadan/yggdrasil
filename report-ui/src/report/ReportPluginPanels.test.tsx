import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PluginDiagnostics, PluginPanel } from "./ReportPluginPanels";

describe("PluginPanel", () => {
  it("does not render implicit core report plugin source pills", () => {
    render(
      <PluginPanel
        panel={{
          id: "core-actions",
          label: "Core Actions",
          slot: "atlas",
          order: 0,
          mdx: "## Core Actions\n\n<ActionList dataKey=\"actions\" />",
          data: {
            actions: [
              {
                kind: "maintenance",
                label: "Process maintenance work queue",
                command: "ygg sync work list --project fixture"
              }
            ]
          },
          plugin: {
            id: "ygg-core-report",
            version: "1",
            authority: "core"
          }
        }}
      />
    );

    expect(screen.getByText("Process maintenance work queue")).toBeInTheDocument();
    expect(screen.queryByText("ygg-core-report")).not.toBeInTheDocument();
    expect(screen.queryByText("ygg-core-report.core-actions")).not.toBeInTheDocument();
  });

  it("queries about and copies a whole plugin panel", () => {
    const onQuery = vi.fn();
    const onCopyCommand = vi.fn();
    const panel = {
      id: "operator-topology",
      label: "Operator Topology",
      slot: "systems",
      order: 10,
      mdx: "## Operator Topology\n\n<Callout dataKey=\"summary\" />",
      data: {
        summary: "Plugin-selected graph traversal evidence."
      },
      plugin: {
        id: "breyta-operator-topology",
        version: "0.1.0",
        authority: "project-plugin"
      }
    };

    render(<PluginPanel panel={panel} actions={{ onQuery, onCopyCommand }} />);

    fireEvent.click(screen.getByRole("button", { name: "Query about panel" }));
    expect(onQuery).toHaveBeenCalledWith(
      expect.objectContaining({
        label: "Operator Topology",
        source: "plugins.breyta-operator-topology.operator-topology",
        question: "What should I inspect in Operator Topology?",
        evidenceRows: [expect.objectContaining({ id: "operator-topology", plugin: "breyta-operator-topology" })]
      })
    );

    fireEvent.click(screen.getByRole("button", { name: "Copy panel JSON" }));
    expect(onCopyCommand).toHaveBeenCalledWith(
      "plugin-panel:breyta-operator-topology:operator-topology",
      expect.stringContaining("\"id\": \"operator-topology\"")
    );
  });

  it("renders plugin graph crawl surfaces with copyable source refs", () => {
    const onCopyCommand = vi.fn();
    render(
      <PluginPanel
        panel={{
          id: "operator-crawl",
          label: "Operator Crawl",
          slot: "systems",
          mdx: "## Operator Crawl\n\n<GraphCrawl dataKey=\"crawl\" />",
          data: {
            crawl: {
              metrics: [
                { label: "Seeds", value: 1 },
                { label: "Edges", value: 2 }
              ],
              seeds: [{ label: "flows-api", kind: "candidate-system", path: "bases/flows-api" }],
              sources: [{ source: "systems.json", path: "src/app/core.clj", line: 12 }],
              edges: [{ source: "flows-api", relation: "calls", target: "events-worker" }]
            }
          },
          plugin: {
            id: "breyta-operator-topology",
            version: "0.1.0",
            authority: "project-plugin"
          }
        }}
        actions={{ onCopyCommand }}
      />
    );

    expect(screen.getByText("Operator Crawl")).toBeInTheDocument();
    expect(screen.getAllByText("flows-api").length).toBeGreaterThan(0);
    expect(screen.getByText("events-worker")).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "Copy source refs" })[0]);

    expect(onCopyCommand).toHaveBeenCalledWith(
      "plugin-graph-crawl-sources:operator-crawl:crawl",
      expect.stringContaining("src/app/core.clj:12")
    );

    fireEvent.click(screen.getByRole("button", { name: "Copy crawl JSON" }));

    expect(onCopyCommand).toHaveBeenCalledWith(
      "plugin-graph-crawl-json:operator-crawl:crawl",
      expect.stringContaining("\"edges\"")
    );
  });

  it("queries about and copies plugin diagnostics", () => {
    const onQuery = vi.fn();
    const onCopyCommand = vi.fn();
    render(
      <PluginDiagnostics
        diagnostics={[{ plugin: { id: "fixture-report-plugin" }, stage: "render", message: "Missing crawl data" }]}
        actions={{ onQuery, onCopyCommand }}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Query" }));
    expect(onQuery).toHaveBeenCalledWith(
      expect.objectContaining({
        label: "Plugin Diagnostics",
        source: "plugins.diagnostics",
        evidenceRows: [expect.objectContaining({ stage: "render" })]
      })
    );

    fireEvent.click(screen.getByRole("button", { name: "Copy diagnostics JSON" }));
    expect(onCopyCommand).toHaveBeenCalledWith("plugin-diagnostics:json", expect.stringContaining("Missing crawl data"));
  });
});
