import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { emptyGraph, emptyReport, fixtureGraph, fixtureReport, inventoryGraph, sourceDocsSystemReport } from "../fixtures/sampleData";
import { ReportPage } from "./ReportPage";

vi.mock("../graph/GraphPanel", () => ({
  GraphPanel: ({
    graph,
    onAsk
  }: {
    graph: { title?: string; nodes: unknown[]; edges: unknown[] };
    onAsk?: (scope: { label: string; source: string; question: string; evidenceRows?: Array<Record<string, unknown>> }) => void;
  }) => (
    <div data-testid="graph-panel">
      {graph.title || "Graph"}: {graph.nodes.length}/{graph.edges.length}
      {onAsk ? (
        <button
          type="button"
          onClick={() =>
            onAsk({
              label: graph.title || "Graph",
              source: "graph.mock",
              question: `Why is ${graph.title || "Graph"} in this graph?`,
              evidenceRows: [{ title: graph.title || "Graph", nodes: graph.nodes.length, edges: graph.edges.length }]
            })
          }
        >
          Ask graph row
        </button>
      ) : null}
    </div>
  ),
}));

describe("ReportPage", () => {
  it("renders evidence counts and commands", () => {
    render(<ReportPage report={fixtureReport} graph={inventoryGraph} />);

    expect(screen.getByText("Fixture")).toBeInTheDocument();
    expect(screen.getByText("Project Atlas")).toBeInTheDocument();
    expect(screen.queryByText("agraph-core-report")).not.toBeInTheDocument();
    expect(screen.getByText("Evidence Inventory")).toBeInTheDocument();
    expect(screen.getByText("Evidence Families")).toBeInTheDocument();
    expect(screen.getByText("Evidence Kinds")).toBeInTheDocument();
    expect(screen.getByText("Evidence State")).toBeInTheDocument();
    expect(screen.getAllByText("system-evidence").length).toBeGreaterThan(0);
    expect(screen.getAllByText("evidence.kinds.file-facts").length).toBeGreaterThan(0);
    expect(screen.getByText("Operator Review Queue")).toBeInTheDocument();
    expect(screen.getByText("Refresh indexed graph basis")).toBeInTheDocument();
    expect(screen.getByText("Report Actions")).toBeInTheDocument();
    expect(screen.getByText("Regenerate report")).toBeInTheDocument();
    expect(screen.getByText("Enqueue review work")).toBeInTheDocument();
    expect(screen.getByText("agraph sync project.edn --check --map agraph.map.json --enqueue")).toBeInTheDocument();
    expect(screen.getByText("agraph report project.edn --map agraph.map.json --out agraph-out")).toBeInTheDocument();
    expect(screen.getByText("packages.unresolved-imports")).toBeInTheDocument();
    expect(screen.getAllByText("Evidence Rows").length).toBeGreaterThan(0);
    expect(screen.getByText("src/app/core.clj")).toBeInTheDocument();

    const reportActions = screen.getByText("Report Actions").closest("section");
    expect(reportActions).toBeTruthy();
    const regenerateRow = within(reportActions as HTMLElement).getByText("Regenerate report").closest("article");
    expect(regenerateRow).toBeTruthy();
    fireEvent.click(within(regenerateRow as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(regenerateRow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    const enqueueRow = within(reportActions as HTMLElement).getByText("Enqueue review work").closest("article");
    expect(enqueueRow).toBeTruthy();
    fireEvent.click(within(enqueueRow as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(enqueueRow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(regenerateRow as HTMLElement).getByRole("button", { name: "Ask" }));
    expect(within(screen.getByRole("navigation", { name: "Report sections" })).getByRole("button", { name: "Ask" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.getByText("What should I know before I run Regenerate report?")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dashboard" }));
    const inventory = screen.getByText("Evidence Inventory").closest("section");
    expect(inventory).toBeTruthy();
    fireEvent.click(within(inventory as HTMLElement).getByRole("button", { name: "Ask" }));
    expect(within(screen.getByRole("navigation", { name: "Report sections" })).getByRole("button", { name: "Ask" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.getByText("What evidence does this report contain?")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dashboard" }));
    const inventoryThird = screen.getByText("Evidence Inventory").closest("section");
    fireEvent.click(within(inventoryThird as HTMLElement).getByRole("button", { name: "Open evidence" }));
    expect(screen.getByRole("button", { name: "Evidence" })).toHaveAttribute("aria-current", "page");

    fireEvent.click(screen.getByRole("button", { name: "Plugins" }));

    expect(screen.getByText("Plugin Package Caveats")).toBeInTheDocument();
    expect(screen.getByText("datastar-hiccup")).toBeInTheDocument();
    expect(screen.getAllByText("unbenchmarked").length).toBeGreaterThan(0);
    expect(screen.getByText("non-authoritative")).toBeInTheDocument();
    expect(screen.getByText("agraph plugin diagnose .dev/agraph/plugins/cache/datastar-hiccup --json")).toBeInTheDocument();
    expect(screen.getByText("Fixture Graph Crawl")).toBeInTheDocument();
    expect(screen.getAllByText("fixture-report-plugin").length).toBeGreaterThan(0);
    expect(screen.getByText("Fixture diagnostic")).toBeInTheDocument();
    expect(screen.getByText("Plugin Artifacts")).toBeInTheDocument();
    expect(screen.getByText(".dev/reports/fixture/graph-crawl.json")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy artifact refs" }));
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
    expect(screen.getByText("flows-api / candidate-system, events-worker / candidate-system")).toBeInTheDocument();
    expect(screen.getAllByText("src/app/plugin_crawl.clj").length).toBeGreaterThan(0);
    expect(screen.queryByText(/\[object Object\]/)).not.toBeInTheDocument();

    const pluginAction = screen.getByText("Inspect checkout plugin crawl").closest("article");
    expect(pluginAction).toBeTruthy();
    fireEvent.click(within(pluginAction as HTMLElement).getByRole("button", { name: "Ask" }));
    expect(within(screen.getByRole("navigation", { name: "Report sections" })).getByRole("button", { name: "Ask" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.getByText("What should I inspect in the checkout plugin crawl?")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Plugins" }));
    const pluginActionAgain = screen.getByText("Inspect checkout plugin crawl").closest("article");
    fireEvent.click(within(pluginActionAgain as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(pluginActionAgain as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(pluginActionAgain as HTMLElement).getByRole("button", { name: "Open graph slice" }));
    expect(screen.getByRole("button", { name: "Systems" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: /System Neighborhood/ })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "Plugins" }));
    const pluginArtifacts = screen.getByText("Plugin Artifacts").closest("section");
    expect(pluginArtifacts).toBeTruthy();
    fireEvent.click(within(pluginArtifacts as HTMLElement).getByRole("button", { name: "Copy artifact refs" }));
    expect(within(pluginArtifacts as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    const pluginCommand = screen
      .getAllByText("agraph ask \"what owns checkout?\" --project fixture --json")
      .map((element) => element.closest("li"))
      .find(Boolean);
    expect(pluginCommand).toBeTruthy();
    fireEvent.click(within(pluginCommand as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(pluginCommand as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Systems" }));

    expect(screen.getByText("External API Review")).toBeInTheDocument();
    expect(screen.getByText("checkout")).toBeInTheDocument();
    expect(screen.getByText("Focused Graph Slices")).toBeInTheDocument();
    expect(screen.getByText("System Neighborhood")).toBeInTheDocument();
    expect(screen.getByText("Package Evidence")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Package Evidence/ }));

    expect(screen.getByTestId("graph-panel")).toHaveTextContent("Package Evidence");

    fireEvent.click(screen.getByRole("button", { name: "Maintenance" }));

    expect(screen.getAllByText(/agraph ask/).length).toBeGreaterThan(0);
    const correctionWorkflow = screen.getByText("Correction Workflow").closest("section");
    expect(correctionWorkflow).toBeTruthy();
    expect(within(correctionWorkflow as HTMLElement).getByText("agraph.map.json")).toBeInTheDocument();
    expect(within(correctionWorkflow as HTMLElement).getByText("agraph sync work complete <work-id> --result result.json")).toBeInTheDocument();
    fireEvent.click(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copy map path" }));
    expect(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copy apply command" }));
    expect(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copy complete command" }));
    expect(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copy result JSON" }));
    expect(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(correctionWorkflow as HTMLElement).getByRole("button", { name: "Ask" }));
    expect(within(screen.getByRole("navigation", { name: "Report sections" })).getByRole("button", { name: "Ask" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.getByText("What correction workflow should I use from this report?")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Maintenance" }));

    const commandItem = screen
      .getAllByText(/agraph ask/)
      .map((element) => element.closest("li"))
      .find(Boolean);
    expect(commandItem).toBeTruthy();
    fireEvent.click(within(commandItem as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(commandItem as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
  }, 10000);

  it("opens focused report sections from review rows", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    const row = screen.getByText("Resolve import-to-package gaps").closest("article");
    expect(row).toBeTruthy();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy explain command" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy source refs" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy evidence JSON" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy correction JSON" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Open dependencies" }));

    expect(screen.getByText("Package Summary")).toBeInTheDocument();
    expect(screen.getByText("agraph packages --project fixture --without-import-evidence --json")).toBeInTheDocument();
    expect(screen.getByText("missing.lib")).toBeInTheDocument();

    const commandItem = screen.getByText("agraph packages --project fixture --without-import-evidence --json").closest("li");
    expect(commandItem).toBeTruthy();
    fireEvent.click(within(commandItem as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(commandItem as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("opens focused graph slices from review rows", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    const row = screen.getByText("Resolve import-to-package gaps").closest("article");
    expect(row).toBeTruthy();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Open graph slice" }));

    expect(screen.getByRole("button", { name: "Systems" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: /Package Evidence/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("graph-panel")).toHaveTextContent("Package Evidence");
  });

  it("asks from graph row scope in the systems tab", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    fireEvent.click(screen.getByRole("button", { name: "Systems" }));
    fireEvent.click(screen.getByRole("button", { name: "Ask graph row" }));

    expect(screen.getAllByRole("button", { name: "Ask" }).some((button) => button.getAttribute("aria-current") === "page")).toBe(true);
    expect(screen.getByText(/Why is System Neighborhood/)).toBeInTheDocument();
    expect(screen.getByText("graph.mock")).toBeInTheDocument();
    expect(screen.getByText("Scope Evidence")).toBeInTheDocument();
  });

  it("renders actionable atlas next actions on plugin dashboards", () => {
    const report = {
      ...fixtureReport,
      atlas: {
        schema: "agraph.report.atlas/v1",
        "next-actions": [
          {
            kind: "dependency-review",
            label: "Review unresolved imports",
            count: 3,
            command: "agraph packages --project fixture --json"
          },
          {
            kind: "freshness",
            label: "Refresh indexed graph basis",
            count: 6,
            command: "agraph sync project.edn --check"
          },
          {
            kind: "audit-scope",
            label: "Inspect project audit scopes",
            command: "agraph audit-scope project.edn --json"
          }
        ]
      }
    };

    render(<ReportPage report={report} graph={fixtureGraph} />);

    const row = screen.getByText("Review unresolved imports").closest("article");
    expect(row).toBeTruthy();
    expect(within(row as HTMLElement).getByText("agraph packages --project fixture --json")).toBeInTheDocument();

    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Copy command" }));
    expect(within(row as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Open graph slice" }));
    expect(screen.getByRole("button", { name: "Systems" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: /Package Evidence/ })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "Dashboard" }));
    const freshnessRow = screen
      .getAllByText("Refresh indexed graph basis")
      .map((element) => element.closest(".action-row"))
      .find(Boolean);
    expect(freshnessRow).toBeTruthy();
    fireEvent.click(within(freshnessRow as HTMLElement).getByRole("button", { name: "Open evidence" }));
    expect(screen.getByRole("button", { name: "Evidence" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("Evidence Freshness")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dashboard" }));
    const auditRow = screen.getByText("Inspect project audit scopes").closest(".action-row");
    expect(auditRow).toBeTruthy();
    fireEvent.click(within(auditRow as HTMLElement).getByRole("button", { name: "Open maintenance" }));
    expect(screen.getByRole("button", { name: "Maintenance" })).toHaveAttribute("aria-current", "page");
  });

  it("asks from a review row with scoped evidence", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    const row = screen.getByText("Resolve import-to-package gaps").closest("article");
    expect(row).toBeTruthy();
    fireEvent.click(within(row as HTMLElement).getByRole("button", { name: "Ask" }));

    expect(screen.getAllByRole("button", { name: "Ask" }).some((button) => button.getAttribute("aria-current") === "page")).toBe(true);
    expect(screen.getByText("Scoped To")).toBeInTheDocument();
    expect(screen.getByText("Resolve import-to-package gaps")).toBeInTheDocument();
    expect(screen.getByText("packages.unresolved-imports")).toBeInTheDocument();
    expect(screen.getByText("Scope Evidence")).toBeInTheDocument();
    expect(screen.getAllByText("src/app/core.clj").length).toBeGreaterThan(0);
  });

  it("asks from the selected graph slice", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    fireEvent.click(screen.getByRole("button", { name: "Systems" }));
    fireEvent.click(screen.getByRole("button", { name: /Package Evidence/ }));
    fireEvent.click(screen.getByRole("button", { name: "Ask about this slice" }));

    expect(screen.getByText("Scoped To")).toBeInTheDocument();
    expect(screen.getAllByText("Package Evidence").length).toBeGreaterThan(0);
    expect(screen.getByText("systems.package-evidence")).toBeInTheDocument();
    expect(screen.getByText("What should I inspect in Package Evidence?")).toBeInTheDocument();
  });

  it("answers report-local questions from loaded artifacts", () => {
    render(<ReportPage report={fixtureReport} graph={inventoryGraph} />);

    const askTab = screen.getAllByRole("button", { name: "Ask" }).find((button) => button.closest("nav"));
    expect(askTab).toBeTruthy();
    fireEvent.click(askTab as HTMLElement);

    expect(screen.getByText("Ask this report")).toBeInTheDocument();
    expect(screen.getByText("Report-local Ask")).toBeInTheDocument();
    expect(screen.getByText("Maintenance work")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "What is this project made of?" }));

    expect(screen.getByText("Project inventory")).toBeInTheDocument();
    expect(screen.getByText("Auth surfaces")).toBeInTheDocument();
    expect(screen.getByText("Inventory Evidence Rows")).toBeInTheDocument();
    expect(screen.getByText("generated/graphql-client.ts")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy answer JSON" }));
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy related JSON" }));
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "What dependency issues exist?" }));

    expect(screen.getByText("Dependency issues")).toBeInTheDocument();
    expect(screen.getAllByText("packages.counts")).toHaveLength(3);
    expect(screen.getByText("Unresolved imports")).toBeInTheDocument();
  });

  it("shows evidence freshness drilldown and asks from it", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    fireEvent.click(screen.getByRole("button", { name: "Evidence" }));

    expect(screen.getByText("Evidence Freshness")).toBeInTheDocument();
    expect(screen.getByText("Freshness By Repo")).toBeInTheDocument();
    expect(screen.getByText("Freshness Sample Paths")).toBeInTheDocument();
    expect(screen.getByText("src/app/deleted.clj")).toBeInTheDocument();
    expect(screen.getByText("Makefile")).toBeInTheDocument();

    const freshness = screen.getByText("Evidence Freshness").closest("section");
    expect(freshness).toBeTruthy();
    fireEvent.click(within(freshness as HTMLElement).getByRole("button", { name: "Copy freshness JSON" }));
    expect(within(freshness as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();
    fireEvent.click(within(freshness as HTMLElement).getByRole("button", { name: "Copy source refs" }));
    expect(within(freshness as HTMLElement).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Ask about freshness" }));

    expect(screen.getByText("Scoped To")).toBeInTheDocument();
    expect(screen.getByText("Evidence Freshness")).toBeInTheDocument();
    expect(screen.getAllByText("evidence.freshness").length).toBeGreaterThan(0);
    expect(screen.getByText("What should I do about evidence freshness?")).toBeInTheDocument();
  });

  it("renders empty report packets without crashing", () => {
    render(<ReportPage report={emptyReport} graph={emptyGraph} />);

    expect(screen.getByText("Empty Project")).toBeInTheDocument();
    expect(screen.getByText("No queued next actions in this report.")).toBeInTheDocument();
  });

  it("renders source/docs/system evidence surfaces", () => {
    render(<ReportPage report={sourceDocsSystemReport} graph={fixtureGraph} />);

    expect(screen.getByText("Source Docs System")).toBeInTheDocument();
    expect(screen.getByText("Project Atlas")).toBeInTheDocument();
  });
});
