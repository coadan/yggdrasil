import type { AGraphGraph } from "../data/types";
import { GraphPanel } from "./GraphPanel";

export function GraphPage({ graph }: { graph: AGraphGraph }) {
  return <GraphPanel graph={graph} />;
}
