import { describe, expect, it } from "vitest";
import { denseGraph, fixtureGraph } from "../fixtures/sampleData";
import { toGraphology } from "./agraphGraphAdapter";

describe("toGraphology", () => {
  it("converts agraph.graph/v2 rows to graphology", () => {
    const graph = toGraphology(fixtureGraph);

    expect(graph.order).toBe(3);
    expect(graph.size).toBe(2);
    expect(graph.getNodeAttribute("node:a", "label")).toBe("app.core");
    expect(graph.getNodeAttribute("package:xtdb", "ecosystem")).toBe("maven");
  });

  it("converts dense graph fixtures with deterministic layouts", () => {
    const circle = toGraphology(denseGraph, "circle");
    const grid = toGraphology(denseGraph, "grid");

    expect(circle.order).toBe(8);
    expect(circle.size).toBe(12);
    expect(circle.getNodeAttribute("node:0", "x")).not.toBe(grid.getNodeAttribute("node:0", "x"));
  });
});
