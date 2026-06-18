import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

vi.mock("./data/artifactLoader", () => ({
  loadJson: vi.fn(() => Promise.reject(new Error("missing local artifact")))
}));

vi.mock("./graph/GraphPanel", () => ({
  GraphPanel: () => <div data-testid="graph-panel" />
}));

describe("App", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
    window.__AGRAPH_BOOT__ = undefined;
  });

  it("shows local loader errors while keeping fixture data visible", async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText(/Using fixture data\. missing local artifact/)).toBeInTheDocument();
    });
    expect(screen.getByText("AGraph Report")).toBeInTheDocument();
  });
});
