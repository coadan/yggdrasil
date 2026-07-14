import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { loadJson } from "./data/artifactLoader";
import { fixtureGraph } from "./fixtures/sampleData";

vi.mock("./data/artifactLoader", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./data/artifactLoader")>();
  return {
    ...actual,
    loadJson: vi.fn(() => Promise.reject(new Error("missing local artifact")))
  };
});

vi.mock("./graph/GraphPanel", () => ({
  GraphPanel: () => <div data-testid="graph-panel" />
}));

describe("App", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
    window.__YGG_BOOT__ = undefined;
    vi.mocked(loadJson).mockReset();
    vi.mocked(loadJson).mockRejectedValue(new Error("missing local artifact"));
  });

  it("uses fixture data without fetching when no reportDir is provided", () => {
    render(<App />);

    expect(screen.getByText("Yggdrasil Report")).toBeInTheDocument();
    expect(vi.mocked(loadJson)).not.toHaveBeenCalled();
  });

  it("loads graph artifacts from an absolute reportDir through Vite fs URLs", async () => {
    vi.mocked(loadJson).mockResolvedValue({
      ...fixtureGraph,
      title: "Live Systems"
    });
    window.history.replaceState(
      null,
      "",
      "/?mode=graph&refresh=0&reportDir=/workspace/yggdrasil/.dev/reports/live"
    );

    render(<App />);

    await waitFor(() => {
      expect(vi.mocked(loadJson)).toHaveBeenCalledWith(
        "/@fs/workspace/yggdrasil/.dev/reports/live/graph.json"
      );
    });
    expect(await screen.findByText("Live Systems")).toBeInTheDocument();
  });

  it("preserves reportDir when switching view modes", () => {
    window.history.replaceState(
      null,
      "",
      "/?mode=report&refresh=0&reportDir=/workspace/yggdrasil/.dev/reports/live"
    );

    render(<App />);

    expect(screen.getByRole("link", { name: "Graph" })).toHaveAttribute(
      "href",
      "?mode=graph&refresh=0&reportDir=%2Fworkspace%2Fyggdrasil%2F.dev%2Freports%2Flive"
    );
  });
});
