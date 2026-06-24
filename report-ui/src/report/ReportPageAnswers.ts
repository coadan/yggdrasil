import type { YggGraph, YggReport } from "../data/types";
import { numericCount } from "../data/reportAdapter";
import type { QueryAnswer } from "./ReportPageTypes";
import { asRecord, asRows, countValue } from "./ReportPageShared";
import { displayValue } from "./valueFormat";

export function externalApiReview(report: YggReport): Record<string, unknown> | null {
  const maintenance = report.maintenance;
  if (!maintenance || typeof maintenance !== "object") return null;
  const review =
    (maintenance as Record<string, unknown>).externalApiReview ||
    (maintenance as Record<string, unknown>)["external-api-review"] ||
    (maintenance as Record<string, unknown>).external_api_review;
  return review && typeof review === "object" ? (review as Record<string, unknown>) : null;
}

export function fanoutRows(review: Record<string, unknown> | null): Array<Record<string, unknown>> {
  const rows = review?.sourceFanouts || review?.["source-fanouts"] || review?.source_fanouts;
  return asRows(rows);
}

export function nestedLabel(row: Record<string, unknown>, key: string): string {
  const nested = asRecord(row[key]);
  return String(nested.label || nested.id || nested["xt/id"] || "");
}

export function packageRows(report: YggReport, key: string): Array<Record<string, unknown>> {
  const packages = asRecord(report.packages);
  return asRows(packages[key] || packages[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

export function freshnessRepoRows(report: YggReport): Array<Record<string, unknown>> {
  const freshness = asRecord(report.evidence.freshness);
  return asRows(freshness.repos).map((repo) => {
    const counts = asRecord(repo.counts);
    return {
      repo: repo["repo-id"] || repo.repoId || repo.id,
      status: repo.status,
      indexed: countValue(counts, "indexed"),
      current: countValue(counts, "current"),
      changed: countValue(counts, "changed"),
      missing: countValue(counts, "missing"),
      unindexed: countValue(counts, "unindexed")
    };
  });
}

export function freshnessSampleRows(report: YggReport): Array<Record<string, unknown>> {
  const rows: Array<Record<string, unknown>> = [];
  const freshness = asRecord(report.evidence.freshness);
  for (const repo of asRows(freshness.repos)) {
    const repoId = repo["repo-id"] || repo.repoId || repo.id;
    const samples = asRecord(repo.samples);
    for (const category of ["changed", "missing", "unindexed"]) {
      for (const sample of asRows(samples[category]).slice(0, 8)) {
        rows.push({
          repo: sample["repo-id"] || sample.repoId || repoId,
          category,
          path: sample.path,
          ext: sample.ext,
          reason: sample["skip-reason"] || sample.skipReason || sample.reason
        });
      }
    }
  }
  return rows;
}

export function freshnessEvidencePacket(report: YggReport): string {
  return JSON.stringify(
    {
      source: "evidence.freshness",
      project: report.project.id,
      freshness: report.evidence.freshness || {}
    },
    null,
    2
  );
}

export function queryAnswerPacket(question: string, answer: QueryAnswer): string {
  return JSON.stringify(
    {
      schema: "ygg.report.query-answer/v1",
      question,
      title: answer.title,
      summary: answer.summary,
      evidence: answer.evidence,
      relatedTitle: answer.relatedTitle,
      related: answer.related
    },
    null,
    2
  );
}

export function maintenanceRows(report: YggReport, key: string): Array<Record<string, unknown>> {
  const maintenance = asRecord(report.maintenance);
  return asRows(maintenance[key] || maintenance[key.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())]);
}

export function externalGraphRows(graph: YggGraph): Array<Record<string, unknown>> {
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));
  const externalIds = new Set(graph.nodes.filter((node) => node.kind === "external-api").map((node) => node.id));
  const rows = new Map<string, { label: string; relation: string; externalApis: Set<string>; edges: number }>();

  for (const edge of graph.edges) {
    const sourceExternal = externalIds.has(edge.source);
    const targetExternal = externalIds.has(edge.target);
    if (sourceExternal === targetExternal) continue;
    const peerId = sourceExternal ? edge.target : edge.source;
    const externalId = sourceExternal ? edge.source : edge.target;
    const peer = nodesById.get(peerId);
    const external = nodesById.get(externalId);
    const relation = edge.relation || "edge";
    const key = `${peerId}:${relation}`;
    const row = rows.get(key) || {
      label: peer?.label || peerId,
      relation,
      externalApis: new Set<string>(),
      edges: 0
    };
    row.externalApis.add(external?.label || externalId);
    row.edges += 1;
    rows.set(key, row);
  }

  return Array.from(rows.values())
    .map((row) => ({
      system: row.label,
      relation: row.relation,
      externalApis: row.externalApis.size,
      edges: row.edges
    }))
    .sort((left, right) => Number(right.externalApis) - Number(left.externalApis))
    .slice(0, 12);
}

export function reportAtlas(report: YggReport, graph: YggGraph): Record<string, unknown> {
  if (report.atlas && typeof report.atlas === "object") return report.atlas;
  const maintenance = asRecord(report.maintenance);
  const packages = asRecord(report.packages);
  const packageCounts = asRecord(packages.counts);
  return {
    project: {
      repos: report.repos.length,
      "repo-roles": []
    },
    evidence: {
      available: report.evidence.available,
      files: numericCount(report, "files"),
      nodes: numericCount(report, "nodes"),
      edges: numericCount(report, "edges"),
      diagnostics: numericCount(report, "diagnostics")
    },
    systems: {
      nodes: graph.nodes.length,
      edges: graph.edges.length,
      clusters: countValue(asRecord(maintenance.counts), "clusters"),
      "visible-connections": countValue(asRecord(maintenance.counts), "visible-connections"),
      "orphaned-systems": countValue(asRecord(maintenance.counts), "orphaned-systems")
    },
    dependencies: {
      packages: countValue(packageCounts, "packages"),
      versions: countValue(packageCounts, "versions"),
      "unresolved-imports": countValue(packageCounts, "unresolved-imports"),
      "version-conflicts": countValue(packageCounts, "version-conflicts")
    },
    maintenance: {
      queue: asRecord(maintenance.queue),
      "external-api-review": asRecord(externalApiReview(report)?.counts)
    },
    "next-actions": []
  };
}

function operatorNextRows(report: YggReport, fallbackRows: Array<Record<string, unknown>> = []): Array<Record<string, unknown>> {
  const operator = asRecord(report.operator);
  const rows = asRows(operator["next-actions"] || operator.nextActions);
  return rows.length > 0 ? rows : fallbackRows;
}

export function overviewAnswer(report: YggReport, graph: YggGraph): QueryAnswer {
  const atlas = reportAtlas(report, graph);
  const evidence = asRecord(atlas.evidence);
  const systems = asRecord(atlas.systems);
  const dependencies = asRecord(atlas.dependencies);
  const nextActions = operatorNextRows(report, asRows(atlas["next-actions"] || atlas.nextActions));

  return {
    title: "Project overview",
    summary: [
      `${report.project.name || report.project.id} has ${report.repos.length} repo(s), ${countValue(evidence, "files")} indexed file(s), and ${countValue(systems, "nodes")} visible system node(s) in this report.`,
      `The report has ${countValue(dependencies, "packages")} package(s), ${countValue(dependencies, "unresolved-imports")} unresolved import(s), and ${countValue(dependencies, "version-conflicts")} package conflict(s).`,
      nextActions.length > 0
        ? "The highest-signal next work is listed in the operator action rows below."
        : "This report does not expose queued next actions."
    ],
    evidence: [
      { source: "atlas.evidence", finding: "Indexed files", count: countValue(evidence, "files") },
      { source: "atlas.systems", finding: "Visible system nodes", count: countValue(systems, "nodes") },
      { source: "atlas.dependencies", finding: "Packages", count: countValue(dependencies, "packages") }
    ],
    related: nextActions,
    relatedTitle: "Operator Next Actions"
  };
}

export function evidenceKindCount(report: YggReport, names: string[]): number {
  const wanted = new Set(names);
  const kinds = asRecord(report.evidence.kinds);
  let total = 0;
  for (const value of Object.values(kinds)) {
    const directRows = asRows(value);
    const rows =
      directRows.length > 0
        ? directRows
        : Object.values(asRecord(value)).flatMap((nested) => asRows(nested));
    for (const row of rows) {
      const kind = displayValue(row.kind || row.value || row.relation);
      if (wanted.has(kind)) total += countValue(row, "count");
    }
  }
  return total;
}

export function graphNodeCount(graph: YggGraph, kinds: string[], tags: string[] = []): number {
  const wantedKinds = new Set(kinds);
  const wantedTags = new Set(tags);
  return graph.nodes.filter((node) => {
    if (node.kind && wantedKinds.has(node.kind)) return true;
    return Array.isArray(node.tags) && node.tags.some((tag) => wantedTags.has(tag));
  }).length;
}

export function auditScopeRelatedRows(report: YggReport, graph: YggGraph): Array<Record<string, unknown>> {
  const auditScopeKinds = new Set([
    "route",
    "url",
    "external-api",
    "config",
    "deployment",
    "secret",
    "service-account",
    "auth",
    "auth-reference",
    "generated-artifact",
    "manifest"
  ]);
  const graphRows = graph.nodes
    .filter((node) => {
      if (node.kind && auditScopeKinds.has(node.kind)) return true;
      return Array.isArray(node.tags) && (node.tags.includes("config") || node.tags.includes("auth"));
    })
    .slice(0, 12)
    .map((node) => ({
      category: "graph-node",
      label: node.label || node.id,
      kind: node.kind,
      path: node.path,
      source: "systems.json"
    }));
  return [...graphRows, ...freshnessSampleRows(report).slice(0, 8)];
}

export function auditScopeAnswer(report: YggReport, graph: YggGraph): QueryAnswer {
  const packages = asRecord(report.packages);
  const packageCounts = asRecord(packages.counts);
  const freshness = asRecord(report.evidence.freshness);
  const freshnessCounts = asRecord(freshness.counts);
  const routes = graphNodeCount(graph, ["route"]) + evidenceKindCount(report, ["route"]);
  const urls = graphNodeCount(graph, ["url", "external-api"]) + evidenceKindCount(report, ["url"]);
  const auth = graphNodeCount(graph, ["auth", "auth-reference", "service-account"], ["auth"]) + evidenceKindCount(report, ["auth-reference"]);
  const configs = graphNodeCount(graph, ["config", "deployment", "secret"], ["config"]) + evidenceKindCount(report, ["env-var"]);
  const generated = graphNodeCount(graph, ["generated-artifact"]) + evidenceKindCount(report, ["generated-artifact"]);
  const manifests = graphNodeCount(graph, ["manifest"]) + evidenceKindCount(report, ["manifest"]);
  const freshnessGaps =
    countValue(freshnessCounts, "changed") + countValue(freshnessCounts, "missing") + countValue(freshnessCounts, "unindexed");

  return {
    title: "Project audit scope",
    summary: [
      `${report.project.name || report.project.id} has ${report.repos.length} repo(s), ${numericCount(report, "files")} indexed file(s), ${graph.nodes.length} graph node(s), and ${graph.edges.length} graph edge(s) in the loaded audit artifacts.`,
      `The report includes ${countValue(packageCounts, "packages")} package(s), ${manifests} manifest evidence row(s), ${routes} route row(s), ${urls} URL/external row(s), ${configs} config row(s), ${auth} auth row(s), and ${generated} generated artifact row(s).`,
      `Freshness state has ${freshnessGaps} gap(s): ${countValue(freshnessCounts, "changed")} changed, ${countValue(freshnessCounts, "missing")} missing, and ${countValue(freshnessCounts, "unindexed")} unindexed.`
    ],
    evidence: [
      { source: "evidence.counts", finding: "Indexed files", count: numericCount(report, "files") },
      { source: "systems.json", finding: "Graph nodes", count: graph.nodes.length },
      { source: "packages.counts", finding: "Packages", count: countValue(packageCounts, "packages") },
      { source: "evidence.kinds", finding: "Routes", count: routes },
      { source: "evidence.kinds", finding: "URLs", count: urls },
      { source: "evidence.kinds", finding: "Auth surfaces", count: auth },
      { source: "evidence.freshness", finding: "Freshness gaps", count: freshnessGaps }
    ],
    related: auditScopeRelatedRows(report, graph),
    relatedTitle: "Audit Evidence Rows"
  };
}

export function dependencyAnswer(report: YggReport): QueryAnswer {
  const packages = asRecord(report.packages);
  const counts = asRecord(packages.counts);
  const unresolved = packageRows(report, "unresolved-imports");
  const conflicts = packageRows(report, "version-conflicts");
  const missingEvidence = packageRows(report, "declared-without-import-evidence");

  return {
    title: "Dependency issues",
    summary: [
      `This report has ${countValue(counts, "packages")} package(s), ${countValue(counts, "versions")} resolved version row(s), and ${countValue(counts, "imports-package")} import-to-package edge(s).`,
      `${countValue(counts, "unresolved-imports")} import(s) are unresolved, ${countValue(counts, "version-conflicts")} package(s) have version conflicts, and ${countValue(counts, "declared-without-import-evidence")} declared package(s) have no import evidence.`,
      "Use the dependency rows as review targets; this is report-local evidence, not a live dependency resolver run."
    ],
    evidence: [
      { source: "packages.counts", finding: "Unresolved imports", count: countValue(counts, "unresolved-imports") },
      { source: "packages.counts", finding: "Version conflicts", count: countValue(counts, "version-conflicts") },
      {
        source: "packages.counts",
        finding: "Declared without import evidence",
        count: countValue(counts, "declared-without-import-evidence")
      }
    ],
    related: [...unresolved, ...conflicts, ...missingEvidence].slice(0, 12),
    relatedTitle: "Dependency Evidence Rows"
  };
}

export function externalApiAnswer(report: YggReport, graph: YggGraph): QueryAnswer {
  const review = externalApiReview(report);
  const counts = asRecord(review?.counts);
  const fanouts = fanoutRows(review);
  const graphRows = externalGraphRows(graph);

  return {
    title: "External API evidence",
    summary: [
      `Maintenance evidence contains ${countValue(counts, "nodes")} external API node(s), ${countValue(counts, "edges")} incident edge(s), and ${countValue(counts, "source-fanouts")} grouped source fanout(s).`,
      `The currently loaded systems graph exposes ${graph.nodes.filter((node) => node.kind === "external-api").length} external API node(s).`,
      "External API groups are mechanical graph neighborhoods. Review rows before rejecting or accepting any host."
    ],
    evidence: [
      { source: "maintenance.external-api-review", finding: "External API nodes", count: countValue(counts, "nodes") },
      { source: "maintenance.external-api-review", finding: "Source fanouts", count: countValue(counts, "source-fanouts") },
      { source: "systems.json", finding: "Visible external API graph nodes", count: graph.nodes.filter((node) => node.kind === "external-api").length }
    ],
    related: fanouts.length > 0 ? fanouts : graphRows,
    relatedTitle: fanouts.length > 0 ? "External API Fanouts" : "Graph External API Neighborhoods"
  };
}

export function systemsAnswer(report: YggReport, graph: YggGraph): QueryAnswer {
  const atlas = reportAtlas(report, graph);
  const systems = asRecord(atlas.systems);
  const hubs = maintenanceRows(report, "top-hubs");
  const orphans = maintenanceRows(report, "orphaned-candidates");

  return {
    title: "System graph shape",
    summary: [
      `The loaded system graph has ${graph.nodes.length} node(s), ${graph.edges.length} edge(s), and ${countValue(systems, "clusters")} discovered cluster(s).`,
      `${countValue(systems, "visible-connections")} visible semantic connection(s) and ${countValue(systems, "orphaned-systems")} orphaned candidate system(s) are reported.`,
      "Inspect hubs and orphaned candidates first when the graph feels hard to read."
    ],
    evidence: [
      { source: "systems.json", finding: "Loaded graph nodes", count: graph.nodes.length },
      { source: "systems.json", finding: "Loaded graph edges", count: graph.edges.length },
      { source: "maintenance.counts", finding: "Orphaned systems", count: countValue(systems, "orphaned-systems") }
    ],
    related: [...hubs, ...orphans].slice(0, 12),
    relatedTitle: "System Evidence Rows"
  };
}

export function maintenanceAnswer(report: YggReport): QueryAnswer {
  const maintenance = asRecord(report.maintenance);
  const queue = asRecord(maintenance.queue);
  const decisions = maintenanceRows(report, "decision-queue");
  const infra = maintenanceRows(report, "infra-review-queue");
  const dependency = maintenanceRows(report, "dependency-review-queue");

  return {
    title: "Maintenance work",
    summary: [
      `The report exposes ${countValue(queue, "decisions")} maintenance decision(s), ${countValue(queue, "infra-review")} infra review item(s), and ${countValue(queue, "dependency-review")} dependency review item(s).`,
      "These rows are the safest path from raw graph evidence to accepted `ygg.map.json` corrections.",
      "Use the listed work commands when you need validated map patch application."
    ],
    evidence: [
      { source: "maintenance.queue", finding: "Maintenance decisions", count: countValue(queue, "decisions") },
      { source: "maintenance.queue", finding: "Infra reviews", count: countValue(queue, "infra-review") },
      { source: "maintenance.queue", finding: "Dependency reviews", count: countValue(queue, "dependency-review") }
    ],
    related: [...decisions, ...infra, ...dependency].slice(0, 12),
    relatedTitle: "Queued Review Rows"
  };
}

export function coverageAnswer(report: YggReport): QueryAnswer {
  const coverage = asRecord(report.coverage);
  const diagnostics = asRecord(coverage.diagnostics);
  const extractors = asRows(coverage.extractors);
  const skipped = asRows(coverage["skipped-by-extension"] || coverage.skippedByExtension);

  return {
    title: "Evidence and coverage",
    summary: [
      `The report has ${numericCount(report, "files")} file(s), ${numericCount(report, "nodes")} node(s), ${numericCount(report, "edges")} edge(s), and ${numericCount(report, "diagnostics")} diagnostic(s).`,
      `${extractors.length} extractor summary row(s) and ${skipped.length} skipped-extension row(s) are included in the report packet.`,
      "Coverage answers identify evidence gaps; they do not infer architecture meaning from filenames."
    ],
    evidence: [
      { source: "evidence.counts", finding: "Files", count: numericCount(report, "files") },
      { source: "coverage.extractors", finding: "Extractor rows", count: extractors.length },
      { source: "coverage.diagnostics", finding: "Diagnostics", count: countValue(diagnostics, "total") }
    ],
    related: [...extractors, ...skipped].slice(0, 12),
    relatedTitle: "Coverage Rows"
  };
}

export function answerReportQuestion(report: YggReport, graph: YggGraph, question: string): QueryAnswer {
  const normalized = question.trim().toLowerCase();
  if (/\b(made of|inventory|contain|contains|routes?|auth|config|generated|manifest|freshness|stale|missing)\b/.test(normalized)) {
    return auditScopeAnswer(report, graph);
  }
  if (/\b(depend\w*|package|import|version|conflict|npm|cargo|go|maven)\b/.test(normalized)) {
    return dependencyAnswer(report);
  }
  if (/\b(external|api|url|host|endpoint|outbound)\b/.test(normalized)) {
    return externalApiAnswer(report, graph);
  }
  if (/\b(system|graph|hub|orphan|cluster|edge|node)\b/.test(normalized)) {
    return systemsAnswer(report, graph);
  }
  if (/\b(maintenance|review|queue|map|patch|next|action|ambiguous|noise)\b/.test(normalized)) {
    return maintenanceAnswer(report);
  }
  if (/\b(evidence|coverage|extractor|diagnostic|skipped|file kind|source)\b/.test(normalized)) {
    return coverageAnswer(report);
  }
  return overviewAnswer(report, graph);
}
