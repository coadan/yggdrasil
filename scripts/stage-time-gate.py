#!/usr/bin/env python3
import argparse
import glob
import json
import pathlib
import sys


GATE_SCHEMA = "ygg.dev.stage-time-gate/v1"


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


def report_stage_totals(report_path, report, allowed_stages):
    rows = []
    for row in (report.get("timings") or {}).get("stageElapsedMs") or []:
        stage = row.get("stage")
        if stage_allowed(stage, allowed_stages):
            rows.append(
                {
                    "report": str(report_path),
                    "suiteId": report.get("suite-id"),
                    "stage": stage,
                    "elapsedMs": int(row.get("elapsedMs") or 0),
                }
            )
    return rows


def report_case_stage_rows(report_path, report, allowed_stages):
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
                        "elapsedMs": int(row.get("elapsedMs") or 0),
                    }
                )
    return rows


def slowest(rows, limit):
    return sorted(rows, key=lambda row: (-row["elapsedMs"], row.get("stage") or ""))[
        :limit
    ]


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


def check(report_paths, max_case_stage_ms, max_total_stage_ms, allowed_stages):
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

    failures.extend(
        failure_rows(stage_totals, max_total_stage_ms, "totalStageElapsedMs")
    )
    failures.extend(
        failure_rows(case_stage_rows, max_case_stage_ms, "caseStageElapsedMs")
    )

    return {
        "schema": GATE_SCHEMA,
        "status": "failed" if failures else "passed",
        "reports": reports,
        "filters": {"stages": sorted(allowed_stages)},
        "thresholds": {
            "maxCaseStageMs": max_case_stage_ms,
            "maxTotalStageMs": max_total_stage_ms,
        },
        "stageTotals": slowest(stage_totals, 25),
        "slowestCaseStages": slowest(case_stage_rows, 25),
        "failures": failures,
    }


def main(argv):
    parser = argparse.ArgumentParser(
        description="Gate completed benchmark stage timings from agent reports."
    )
    parser.add_argument("reports", nargs="+", help="Agent report JSON path or glob.")
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
        "--stage",
        action="append",
        default=[],
        help="Limit checks to this stage. May be repeated.",
    )
    parser.add_argument("--out", help="Write the gate result JSON to this path.")
    args = parser.parse_args(argv)

    report_paths = expand_report_paths(args.reports)
    if not report_paths:
        print("no reports provided", file=sys.stderr)
        return 2

    result = check(
        report_paths,
        args.max_case_stage_ms,
        args.max_total_stage_ms,
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
