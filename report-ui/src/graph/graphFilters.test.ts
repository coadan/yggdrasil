import { describe, expect, it } from "vitest";
import { fixtureGraph } from "../fixtures/sampleData";
import { filterGraph, graphFilterOptions } from "./graphFilters";

describe("graphFilters", () => {
  it("returns mechanical facet options from graph rows", () => {
    const options = graphFilterOptions(fixtureGraph);

    expect(options.kinds).toEqual(["namespace", "package"]);
    expect(options.relations).toEqual(["declares", "imports"]);
    expect(options.clusters.map((cluster) => cluster.label)).toEqual(["Data", "Runtime"]);
  });

  it("filters rows without mutating source graph data", () => {
    const before = structuredClone(fixtureGraph);
    const filtered = filterGraph(fixtureGraph, { kind: "package", query: "", relation: "", cluster: "" });

    expect(filtered.visibleNodes).toBe(1);
    expect(filtered.visibleEdges).toBe(0);
    expect(filtered.graph.nodes.map((node) => node.id)).toEqual(["package:xtdb"]);
    expect(fixtureGraph).toEqual(before);
  });

  it("filters by relation and cluster", () => {
    const filtered = filterGraph(fixtureGraph, {
      query: "",
      kind: "",
      relation: "declares",
      cluster: "cluster:data"
    });

    expect(filtered.visibleNodes).toBe(2);
    expect(filtered.visibleEdges).toBe(1);
    expect(filtered.graph.edges[0].id).toBe("edge:b-xtdb");
  });
});
