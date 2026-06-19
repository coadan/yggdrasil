import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { emptyGraph, externalApiHeavyGraph, fixtureGraph, packageFocusedDepsGraph } from "../fixtures/sampleData";
import { GraphPanel } from "./GraphPanel";

vi.mock("@react-sigma/core", () => ({
  SigmaContainer: ({ children }: { children: ReactNode }) => <div data-testid="sigma">{children}</div>,
  useCamera: () => ({ reset: vi.fn() }),
  useLoadGraph: () => vi.fn(),
  useRegisterEvents: () => vi.fn()
}));

describe("GraphPanel", () => {
  it("renders graph controls and filters visible counts", () => {
    render(<GraphPanel graph={fixtureGraph} />);

    expect(screen.getByText("Fixture Graph")).toBeInTheDocument();
    expect(screen.getByText("3 of 3 nodes, 2 of 2 edges")).toBeInTheDocument();
    const graphRows = screen.getByText("Graph Rows").closest(".graph-row-preview");
    expect(graphRows).toBeTruthy();
    expect(within(graphRows as HTMLElement).getByText("app.core")).toBeInTheDocument();
    expect(within(graphRows as HTMLElement).getByText("imports")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "package" } });

    expect(screen.getByText("1 of 3 nodes, 0 of 2 edges")).toBeInTheDocument();
    expect(screen.getByText("Selection")).toBeInTheDocument();
  });

  it("renders an empty graph state", () => {
    render(<GraphPanel graph={emptyGraph} />);

    expect(screen.getByText("Empty Graph")).toBeInTheDocument();
    expect(screen.getByText("No graph rows available.")).toBeInTheDocument();
  });

  it("renders package-focused dependency graph controls", () => {
    render(<GraphPanel graph={packageFocusedDepsGraph} />);

    expect(screen.getByText("Package: xtdb")).toBeInTheDocument();
    expect(screen.getByText("3 of 3 nodes, 2 of 2 edges")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "package" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "declares" })).toBeInTheDocument();
  });

  it("groups large external API fanouts by default", () => {
    render(<GraphPanel graph={externalApiHeavyGraph} />);

    expect(screen.getByText("External API Fixture")).toBeInTheDocument();
    expect(screen.getByText("2 of 21 nodes, 1 of 20 edges")).toBeInTheDocument();
    expect(screen.getByLabelText("External APIs")).toHaveValue("group");

    fireEvent.change(screen.getByLabelText("External APIs"), { target: { value: "hide" } });

    expect(screen.getByText("1 of 21 nodes, 0 of 20 edges")).toBeInTheDocument();
  });
});
