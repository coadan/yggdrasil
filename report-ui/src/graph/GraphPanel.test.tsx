import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { emptyGraph, fixtureGraph, packageFocusedDepsGraph } from "../fixtures/sampleData";
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
});
