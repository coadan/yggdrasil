#!/usr/bin/env python3
import argparse
import glob
import json
import pathlib
import sys


GATE_SCHEMA = "ygg.dev.stage-time-gate/v1"

GRAPH_SETUP_STAGES = {
    "index-project",
    "infer-project",
    "prepare-graph-index",
}
CASE_SETUP_STAGES = {
    "prepare-worktree",
    "prepare-ground-truth",
    "write-prepared-case",
}
AGENT_PREPARATION_STAGES = {
    "context-packet",
    "context-related-files",
    "reuse-agent-artifacts",
    "write-agent-artifacts",
    "write-agent-project",
}
EMBEDDING_STAGES = {
    "embedding-provider-targets",
    "embed-search-docs",
}
AGENT_EXECUTION_STAGES = {
    "agent-result",
}
SCORING_STAGES = {
    "score-agent-result",
}


def read_json(path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def expand_report_paths(values):
    paths = []
    for value in values:
        matches = sorted(glob.glob(value))
        if matches:
            paths.extend(pathlib.Path(match) for match in matches)
        else:
            paths.append(pathlib.Path(value))
    return paths


def stage_allowed(stage, allowed_stages):
    return not allowed_stages or stage in allowed_stages


def fallback_stage_class(stage):
    if stage in GRAPH_SETUP_STAGES:
        return "graph-setup"
    if stage in CASE_SETUP_STAGES:
        return "case-setup"
    if stage in AGENT_PREPARATION_STAGES:
        return "agent-preparation"
    if stage in EMBEDDING_STAGES:
        return "embedding"
    if stage in AGENT_EXECUTION_STAGES:
        return "agent-execution"
    if stage in SCORING_STAGES:
        return "scoring"
    return "other"


def stage_class_lookup(report):
    rows = ((report.get("timings") or {}).get("stageTiming") or {}).get("classes") or []
    return {
        row.get("stage"): row.get("class")
        for row in rows
        if row.get("stage") and row.get("class")
    }


def stage_class(stage, class_by_stage):
    return class_by_stage.get(stage) or fallback_stage_class(stage)


def report_stage_totals(report_path, report, allowed_stages):
    class_by_stage = stage_class_lookup(report)
    rows = []
    for row in (report.get("timings") or {}).get("stageElapsedMs") or []:
        stage = row.get("stage")
        if stage_allowed(stage, allowed_stages):
            rows.append(
                {
                    "report": str(report_path),
                    "suiteId": report.get("suite-id"),
                    "stage": stage,
                    "stageClass": stage_class(stage, class_by_stage),
                    "elapsedMs": int(row.get("elapsedMs") or 0),
                }
            )
    return rows


def report_case_stage_rows(report_path, report, allowed_stages):
    class_by_stage = stage_class_lookup(report)
    rows = []
    for progress in report.get("caseProgress") or []:
        case_id = progress.get("case-id") or progress.get("caseId")
        repo_id = progress.get("repo-id") or progress.get("repoId")
        for row in progress.get("stageElapsedMs") or []:
            stage = row.get("stage")
            if stage_allowed(stage, allowed_stages):
                rows.append(
                    {
                        "report": str(report_path),
                        "suiteId": report.get("suite-id"),
                        "caseId": case_id,
                        "repoId": repo_id,
                        "stage": stage,
                        "stageClass": stage_class(stage, class_by_stage),
                        "elapsedMs": int(row.get("elapsedMs") or 0),
                    }
                )
    return rows


def slowest(rows, limit):
    return sorted(rows, key=lambda row: (-row["elapsedMs"], row.get("stage") or ""))[
        :limit
    ]


def largest_regressions(rows, limit):
    return sorted(
        rows,
        key=lambda row: (
            -row["deltaMs"],
            row.get("stage") or "",
            row.get("caseId") or "",
            row.get("repoId") or "",
        ),
    )[:limit]


def failure_rows(rows, threshold, metric):
    if threshold is None:
        return []
    return [
        {
            "metric": metric,
            "expected": threshold,
            "actual": row["elapsedMs"],
            "message": "Benchmark completed stage exceeded the configured duration.",
            **row,
        }
        for row in rows
        if row["elapsedMs"] > threshold
    ]


def aggregate_rows(rows, key_fields):
    totals = {}
    for row in rows:
        key = tuple(row.get(field) for field in key_fields)
        existing = totals.get(key)
        if existing is None:
            totals[key] = {
                field: row.get(field)
                for field in key_fields
                if row.get(field) is not None
            }
            totals[key]["elapsedMs"] = int(row.get("elapsedMs") or 0)
        else:
            existing["elapsedMs"] += int(row.get("elapsedMs") or 0)
    return totals


def regression_ratio(current_ms, baseline_ms):
    if baseline_ms <= 0:
        return None
    return current_ms / baseline_ms


def compared_rows(current_rows, baseline_rows, key_fields):
    current_by_key = aggregate_rows(current_rows, key_fields)
    baseline_by_key = aggregate_rows(baseline_rows, key_fields)
    rows = []
    for key, current in current_by_key.items():
        baseline = baseline_by_key.get(key)
        if baseline is None:
            continue
        current_ms = int(current.get("elapsedMs") or 0)
        baseline_ms = int(baseline.get("elapsedMs") or 0)
        delta_ms = current_ms - baseline_ms
        row = {
            **{
                field: current.get(field)
                for field in key_fields
                if current.get(field) is not None
            },
            "baselineElapsedMs": baseline_ms,
            "currentElapsedMs": current_ms,
            "deltaMs": delta_ms,
            "regression": delta_ms > 0,
        }
        ratio = regression_ratio(current_ms, baseline_ms)
        if ratio is not None:
            row["ratio"] = ratio
        rows.append(row)
    return rows


def stage_regression_failure_rows(
    rows,
    max_regression_ms,
    max_regression_ratio,
    min_regression_ms,
    metric_prefix,
):
    failures = []
    for row in rows:
        delta_ms = int(row.get("deltaMs") or 0)
        if delta_ms <= min_regression_ms:
            continue
        if max_regression_ms is not None and delta_ms > max_regression_ms:
            failures.append(
                {
                    "metric": f"{metric_prefix}RegressionMs",
                    "expected": max_regression_ms,
                    "actual": delta_ms,
                    "message": "Benchmark completed stage regressed against the baseline report.",
                    **row,
                }
            )
        ratio = row.get("ratio")
        if (
            max_regression_ratio is not None
            and ratio is not None
            and ratio > max_regression_ratio
        ):
            failures.append(
                {
                    "metric": f"{metric_prefix}RegressionRatio",
                    "expected": max_regression_ratio,
                    "actual": ratio,
                    "message": "Benchmark completed stage ratio regressed against the baseline report.",
                    **row,
                }
            )
    return failures


def load_reports(report_paths, allowed_stages):
    failures = []
    missing = [str(path) for path in report_paths if not path.is_file()]
    for path in missing:
        failures.append({"message": f"report does not exist: {path}", "report": path})

    reports = []
    stage_totals = []
    case_stage_rows = []
    for path in report_paths:
        if not path.is_file():
            continue
        report = read_json(path)
        reports.append(
            {
                "path": str(path),
                "schema": report.get("schema"),
                "suiteId": report.get("suite-id"),
                "cases": (report.get("timings") or {}).get("cases"),
            }
        )
        stage_totals.extend(report_stage_totals(path, report, allowed_stages))
        case_stage_rows.extend(report_case_stage_rows(path, report, allowed_stages))
    return {
        "failures": failures,
        "reports": reports,
        "stageTotals": stage_totals,
        "caseStageRows": case_stage_rows,
    }


def check(
    report_paths,
    baseline_report_paths,
    max_case_stage_ms,
    max_total_stage_ms,
    max_case_regression_ms,
    max_total_regression_ms,
    max_case_regression_ratio,
    max_total_regression_ratio,
    min_regression_ms,
    allowed_stages,
):
    failures = []
    current = load_reports(report_paths, allowed_stages)
    baseline = load_reports(baseline_report_paths, allowed_stages)
    failures.extend(current["failures"])
    failures.extend(baseline["failures"])

    stage_deltas = compared_rows(
        current["stageTotals"],
        baseline["stageTotals"],
        ["suiteId", "stage"],
    )
    stage_class_deltas = compared_rows(
        current["stageTotals"],
        baseline["stageTotals"],
        ["suiteId", "stageClass"],
    )
    case_stage_deltas = compared_rows(
        current["caseStageRows"],
        baseline["caseStageRows"],
        ["suiteId", "caseId", "repoId", "stage"],
    )
    case_stage_class_deltas = compared_rows(
        current["caseStageRows"],
        baseline["caseStageRows"],
        ["suiteId", "caseId", "repoId", "stageClass"],
    )
    stage_class_totals = list(
        aggregate_rows(
            current["stageTotals"],
            ["report", "suiteId", "stageClass"],
        ).values()
    )
    case_stage_class_totals = list(
        aggregate_rows(
            current["caseStageRows"],
            ["report", "suiteId", "caseId", "repoId", "stageClass"],
        ).values()
    )

    failures.extend(
        failure_rows(current["stageTotals"], max_total_stage_ms, "totalStageElapsedMs")
    )
    failures.extend(
        failure_rows(current["caseStageRows"], max_case_stage_ms, "caseStageElapsedMs")
    )
    failures.extend(
        stage_regression_failure_rows(
            stage_deltas,
            max_total_regression_ms,
            max_total_regression_ratio,
            min_regression_ms,
            "totalStage",
        )
    )
    failures.extend(
        stage_regression_failure_rows(
            case_stage_deltas,
            max_case_regression_ms,
            max_case_regression_ratio,
            min_regression_ms,
            "caseStage",
        )
    )

    return {
        "schema": GATE_SCHEMA,
        "status": "failed" if failures else "passed",
        "reports": current["reports"],
        "baselineReports": baseline["reports"],
        "filters": {"stages": sorted(allowed_stages)},
        "thresholds": {
            "maxCaseStageMs": max_case_stage_ms,
            "maxTotalStageMs": max_total_stage_ms,
            "maxCaseStageRegressionMs": max_case_regression_ms,
            "maxTotalStageRegressionMs": max_total_regression_ms,
            "maxCaseStageRegressionRatio": max_case_regression_ratio,
            "maxTotalStageRegressionRatio": max_total_regression_ratio,
            "minStageRegressionMs": min_regression_ms,
        },
        "stageTotals": slowest(current["stageTotals"], 25),
        "stageClassTotals": slowest(stage_class_totals, 25),
        "slowestCaseStages": slowest(current["caseStageRows"], 25),
        "slowestCaseStageClasses": slowest(case_stage_class_totals, 25),
        "stageDeltas": largest_regressions(stage_deltas, 25),
        "stageClassDeltas": largest_regressions(stage_class_deltas, 25),
        "caseStageDeltas": largest_regressions(case_stage_deltas, 25),
        "caseStageClassDeltas": largest_regressions(case_stage_class_deltas, 25),
        "failures": failures,
    }


def main(argv):
    parser = argparse.ArgumentParser(
        description="Gate completed benchmark stage timings from agent reports."
    )
    parser.add_argument("reports", nargs="+", help="Agent report JSON path or glob.")
    parser.add_argument(
        "--baseline-report",
        action="append",
        default=[],
        help="Baseline agent report JSON path or glob for artifact-to-artifact regression checks. May be repeated.",
    )
    parser.add_argument(
        "--max-case-stage-ms",
        type=int,
        help="Maximum elapsed ms for any completed stage in one case.",
    )
    parser.add_argument(
        "--max-total-stage-ms",
        type=int,
        help="Maximum aggregate elapsed ms for a completed stage in one report.",
    )
    parser.add_argument(
        "--max-case-stage-regression-ms",
        type=int,
        help="Maximum allowed case-stage delta versus --baseline-report.",
    )
    parser.add_argument(
        "--max-total-stage-regression-ms",
        type=int,
        help="Maximum allowed total-stage delta versus --baseline-report.",
    )
    parser.add_argument(
        "--max-case-stage-regression-ratio",
        type=float,
        help="Maximum allowed case-stage current/baseline ratio versus --baseline-report.",
    )
    parser.add_argument(
        "--max-total-stage-regression-ratio",
        type=float,
        help="Maximum allowed total-stage current/baseline ratio versus --baseline-report.",
    )
    parser.add_argument(
        "--min-stage-regression-ms",
        type=int,
        default=0,
        help="Ignore regression deltas at or below this millisecond floor. Default: 0.",
    )
    parser.add_argument(
        "--stage",
        action="append",
        default=[],
        help="Limit checks to this stage. May be repeated.",
    )
    parser.add_argument("--out", help="Write the gate result JSON to this path.")
    args = parser.parse_args(argv)

    report_paths = expand_report_paths(args.reports)
    baseline_report_paths = expand_report_paths(args.baseline_report)
    if not report_paths:
        print("no reports provided", file=sys.stderr)
        return 2

    result = check(
        report_paths,
        baseline_report_paths,
        args.max_case_stage_ms,
        args.max_total_stage_ms,
        args.max_case_stage_regression_ms,
        args.max_total_stage_regression_ms,
        args.max_case_stage_regression_ratio,
        args.max_total_stage_regression_ratio,
        args.min_stage_regression_ms,
        set(args.stage),
    )
    text = json.dumps(result, indent=2, sort_keys=True)
    if args.out:
        out = pathlib.Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
