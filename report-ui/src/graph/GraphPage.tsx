import type { YggGraph } from "../data/types";
import { GraphPanel } from "./GraphPanel";

export function GraphPage({ graph }: { graph: YggGraph }) {
  return <GraphPanel graph={graph} />;
}
