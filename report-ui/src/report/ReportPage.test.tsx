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
    expect(screen.getByText("Evidence Surface")).toBeInTheDocument();
    expect(screen.getByText("source-graph")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Systems" }));

    expect(screen.getByText("External API Review")).toBeInTheDocument();
    expect(screen.getByText("checkout")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Maintenance" }));

    expect(screen.getByText(/agraph ask/)).toBeInTheDocument();
  });

  it("renders empty report packets without crashing", () => {
    render(<ReportPage report={emptyReport} graph={emptyGraph} />);

    expect(screen.getByText("Empty Project")).toBeInTheDocument();
    expect(screen.getByText("No queued next actions in this report.")).toBeInTheDocument();
  });

  it("renders source/docs/system evidence surfaces", () => {
    render(<ReportPage report={sourceDocsSystemReport} graph={fixtureGraph} />);

    expect(screen.getByText("Source Docs System")).toBeInTheDocument();
    expect(screen.getByText("map-overlay")).toBeInTheDocument();
  });
});
