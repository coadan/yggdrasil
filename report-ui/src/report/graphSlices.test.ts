import { describe, expect, it } from "vitest";
import { externalApiHeavyGraph, fixtureGraph } from "../fixtures/sampleData";
import { graphSlices } from "./graphSlices";

describe("graphSlices", () => {
  it("derives package evidence and system neighborhood slices from graph facts", () => {
    const slices = graphSlices(fixtureGraph);

    expect(slices.map((slice) => slice.id)).toContain("package-evidence");
    expect(slices.map((slice) => slice.id)).toContain("system-neighborhood");

    const packageSlice = slices.find((slice) => slice.id === "package-evidence");
    expect(packageSlice?.graph.nodes.map((node) => node.id)).toContain("package:xtdb");
    expect(packageSlice?.graph.edges.map((edge) => edge.relation)).toContain("declares");
  });

  it("derives external surface slices without host-name classification", () => {
    const slices = graphSlices(externalApiHeavyGraph);
    const external = slices.find((slice) => slice.id === "external-surface");

    expect(external).toBeTruthy();
    expect(external?.graph.nodes.some((node) => node.kind === "external-api")).toBe(true);
    expect(external?.graph.nodes.some((node) => node.id === "system:checkout")).toBe(true);
    expect(external?.graph.edges.length).toBe(20);
  });
});
