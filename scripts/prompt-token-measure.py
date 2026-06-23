#!/usr/bin/env python3
import argparse
import json
import math
import pathlib
import sys


MEASURE_SCHEMA = "ygg.dev.prompt-token-measure/v1"
LANES = ("shell-only", "ygg")


def estimate_tokens(value):
    return int(math.ceil(len(json.dumps(value)) / 4.0))


def prompt_rows(source):
    rows = []
    failures = []
    for mode in LANES:
        prompts = sorted(source.glob(f"{mode}/*/cases/*/agent-prompts/*.md"))
        by_case = {}
        for path in prompts:
            case_id = path.parent.parent.name
            by_case.setdefault(case_id, []).append(path)
        for case_id in sorted(by_case):
            paths = by_case[case_id]
            if len(paths) != 1:
                failures.append(
                    f"expected one prompt artifact for {mode}/{case_id}, found {len(paths)}"
                )
                continue
            prompt = paths[0].read_text(encoding="utf-8")
            rows.append(
                {
                    "mode": mode,
                    "caseId": case_id,
                    "promptBytes": len(prompt.encode("utf-8")),
                    "estimatedPromptTokens": estimate_tokens(prompt),
                }
            )
    return rows, failures


def rows_by_lane(rows):
    by_lane = {mode: {} for mode in LANES}
    for row in rows:
        by_lane[row["mode"]][row["caseId"]] = row
    return by_lane


def case_deltas(rows):
    by_lane = rows_by_lane(rows)
    shared_cases = sorted(set(by_lane["shell-only"]) & set(by_lane["ygg"]))
    deltas = []
    for case_id in shared_cases:
        shell_row = by_lane["shell-only"][case_id]
        ygg_row = by_lane["ygg"][case_id]
        deltas.append(
            {
                "caseId": case_id,
                "shellPromptTokens": shell_row["estimatedPromptTokens"],
                "yggPromptTokens": ygg_row["estimatedPromptTokens"],
                "tokenDelta": ygg_row["estimatedPromptTokens"]
                - shell_row["estimatedPromptTokens"],
                "shellPromptBytes": shell_row["promptBytes"],
                "yggPromptBytes": ygg_row["promptBytes"],
                "byteDelta": ygg_row["promptBytes"] - shell_row["promptBytes"],
            }
        )
    return deltas


def source_label(source, out):
    if not out:
        return str(source)
    try:
        return str(source.resolve().relative_to(out.resolve().parent))
    except ValueError:
        return str(source.resolve())


def measure(source, suite, out):
    rows, failures = prompt_rows(source)
    deltas = case_deltas(rows)
    total_shell = sum(row["shellPromptTokens"] for row in deltas)
    total_ygg = sum(row["yggPromptTokens"] for row in deltas)
    report = {
        "schema": MEASURE_SCHEMA,
        "suite": suite,
        "source": source_label(source, out),
        "rows": rows,
        "caseDeltas": deltas,
        "summary": {
            "sharedCases": len(deltas),
            "totalShellPromptTokens": total_shell,
            "totalYggPromptTokens": total_ygg,
            "totalTokenDelta": total_ygg - total_shell,
            "allYggReduced": bool(deltas)
            and all(row["tokenDelta"] < 0 for row in deltas),
        },
    }
    if failures:
        report["failures"] = failures
    return report


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main(argv):
    parser = argparse.ArgumentParser(
        description="Measure prompt-token estimates from benchmark prompt artifacts."
    )
    parser.add_argument("source", help="Benchmark output root containing shell-only/ and ygg/")
    parser.add_argument("--suite", default=None, help="Suite label to include in the report.")
    parser.add_argument("--out", help="Write the measure report to this path.")
    args = parser.parse_args(argv)

    source = pathlib.Path(args.source)
    if not source.is_dir():
        print(f"source directory does not exist: {source}", file=sys.stderr)
        return 2

    out = pathlib.Path(args.out) if args.out else None
    report = measure(source, args.suite, out)
    text = json.dumps(report, indent=2, sort_keys=True)
    if out:
        write_json(out, report)
    print(text)
    return 1 if report.get("failures") else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
