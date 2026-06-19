import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { emptyGraph, emptyReport, fixtureGraph, fixtureReport, sourceDocsSystemReport } from "../fixtures/sampleData";
import { ReportPage } from "./ReportPage";

vi.mock("../graph/GraphPanel", () => ({
  GraphPanel: () => <div data-testid="graph-panel" />,
}));

describe("ReportPage", () => {
  it("renders evidence counts and commands", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    expect(screen.getByText("Fixture")).toBeInTheDocument();
    expect(screen.getByText("Project Atlas")).toBeInTheDocument();
    expect(screen.getByText("agraph-core-report")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Plugins" }));

    expect(screen.getByText("Fixture Graph Crawl")).toBeInTheDocument();
    expect(screen.getAllByText("fixture-report-plugin").length).toBeGreaterThan(0);
    expect(screen.getByText("Fixture diagnostic")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Systems" }));

    expect(screen.getByText("External API Review")).toBeInTheDocument();
    expect(screen.getByText("checkout")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Maintenance" }));

    expect(screen.getByText(/agraph ask/)).toBeInTheDocument();
  });

  it("answers report-local questions from loaded artifacts", () => {
    render(<ReportPage report={fixtureReport} graph={fixtureGraph} />);

    fireEvent.click(screen.getByRole("button", { name: "Ask" }));

    expect(screen.getByText("Ask this report")).toBeInTheDocument();
    expect(screen.getByText("Report-local Ask")).toBeInTheDocument();
    expect(screen.getByText("Maintenance work")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "What dependency issues exist?" }));

    expect(screen.getByText("Dependency issues")).toBeInTheDocument();
    expect(screen.getAllByText("packages.counts")).toHaveLength(3);
    expect(screen.getByText("Unresolved imports")).toBeInTheDocument();
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
