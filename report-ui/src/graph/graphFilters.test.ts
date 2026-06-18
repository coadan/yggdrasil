import { describe, expect, it } from "vitest";
import { externalApiHeavyGraph, fixtureGraph } from "../fixtures/sampleData";
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

  it("groups external API fanouts by graph topology", () => {
    const before = structuredClone(externalApiHeavyGraph);
    const filtered = filterGraph(externalApiHeavyGraph, { externalApiMode: "group" });
    const groupNode = filtered.graph.nodes.find((node) => node.kind === "external-api-group");

    expect(filtered.visibleNodes).toBe(2);
    expect(filtered.visibleEdges).toBe(1);
    expect(groupNode?.label).toBe("20 external APIs");
    expect(groupNode?.virtual).toBe(true);
    expect(groupNode?.attrs?.externalApis).toHaveLength(20);
    expect(externalApiHeavyGraph).toEqual(before);
  });

  it("hides external APIs and applies minimum degree filters", () => {
    const hidden = filterGraph(externalApiHeavyGraph, { externalApiMode: "hide" });
    const minDegree = filterGraph(fixtureGraph, { minDegree: "2" });

    expect(hidden.graph.nodes.map((node) => node.id)).toEqual(["system:checkout"]);
    expect(hidden.visibleEdges).toBe(0);
    expect(minDegree.graph.nodes.map((node) => node.id)).toEqual(["node:b"]);
    expect(minDegree.visibleEdges).toBe(0);
  });
});
