export type CountRow = {
  value?: string;
  kind?: string;
  relation?: string;
  count: number;
};

export type EvidenceSurface = {
  schema: "agraph.evidence/v1";
  projectId?: string;
  project_id?: string;
  available: string[];
  counts: Record<string, number | Record<string, number>>;
  topFileKinds?: CountRow[];
  top_file_kinds?: CountRow[];
  topNodeKinds?: CountRow[];
  top_node_kinds?: CountRow[];
  topEdgeRelations?: CountRow[];
  top_edge_relations?: CountRow[];
  next?: string[];
};

export type AGraphReport = {
  schema: "agraph.report/v2";
  project: {
    id: string;
    name?: string;
    configPath?: string;
    config_path?: string;
    mapPath?: string;
    map_path?: string;
    detail?: string;
  };
  basis?: Record<string, unknown>;
  repos: Array<{ id: string; root: string; role?: string }>;
  evidence: EvidenceSurface;
  atlas?: Record<string, unknown>;
  coverage?: Record<string, unknown>;
  graphs: {
    overview: Record<string, unknown>;
    systems: Record<string, unknown>;
  };
  packages?: {
    counts?: Record<string, number>;
    ecosystems?: Array<Record<string, unknown>>;
  };
  maintenance?: Record<string, unknown>;
  commands: string[];
};

export type AGraphNode = {
  id: string;
  label?: string;
  kind?: string;
  repo?: string;
  repoId?: string;
  repo_id?: string;
  path?: string;
  color?: string;
  size?: number;
  degree?: number;
  score?: number;
  clusterId?: string;
  cluster_id?: string;
  clusterLabel?: string;
  cluster_label?: string;
  ecosystem?: string;
  packageName?: string;
  package_name?: string;
  metrics?: Record<string, number>;
  attrs?: Record<string, unknown>;
  tags?: string[];
  virtual?: boolean;
};

export type AGraphEdge = {
  id?: string;
  source: string;
  target: string;
  relation?: string;
  color?: string;
  confidence?: string | number;
  path?: string;
  line?: number;
  packageName?: string;
  package_name?: string;
  metrics?: Record<string, number>;
  attrs?: Record<string, unknown>;
  virtual?: boolean;
};

export type AGraphGraph = {
  schema: "agraph.graph/v2";
  title?: string;
  nodes: AGraphNode[];
  edges: AGraphEdge[];
  clusters?: Array<{
    id?: string;
    label?: string;
    sourceLabel?: string;
    source_label?: string;
    [key: string]: unknown;
  }>;
};
