#!/usr/bin/env python3
import argparse
import json
import math
import pathlib
import sys


MEASURE_SCHEMA = "ygg.dev.prompt-token-measure/v1"
GATE_SCHEMA = "ygg.dev.prompt-token-gate/v1"
LANES = ("shell-only", "ygg")


def estimate_tokens(value):
    return int(math.ceil(len(json.dumps(value)) / 4.0))


def read_json(path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def report_source(report_path, source_value):
    source = pathlib.Path(source_value)
    if not source.is_absolute():
        source = report_path.parent / source
    return source


def prompt_path(source, mode, case_id):
    matches = sorted(source.glob(f"{mode}/*/cases/{case_id}/agent-prompts/*.md"))
    if len(matches) != 1:
        return None, f"expected one prompt artifact for {mode}/{case_id}, found {len(matches)}"
    return matches[0], None


def artifact_row(source, mode, case_id):
    path, error = prompt_path(source, mode, case_id)
    if error:
        return None, error
    prompt = path.read_text(encoding="utf-8")
    return {
        "mode": mode,
        "caseId": case_id,
        "promptPath": str(path),
        "promptBytes": len(prompt.encode("utf-8")),
        "estimatedPromptTokens": estimate_tokens(prompt),
    }, None


def rows_by_lane(rows):
    by_lane = {mode: {} for mode in LANES}
    failures = []
    for row in rows:
        mode = row.get("mode")
        case_id = row.get("caseId")
        if mode not in by_lane:
            failures.append(f"unexpected mode in report row: {mode}")
        elif not case_id:
            failures.append("report row is missing caseId")
        elif case_id in by_lane[mode]:
            failures.append(f"duplicate report row for {mode}/{case_id}")
        else:
            by_lane[mode][case_id] = row
    return by_lane, failures


def compare_row(report_row, artifact):
    failures = []
    for key in ("promptBytes", "estimatedPromptTokens"):
        if report_row.get(key) != artifact.get(key):
            failures.append(
                f"{artifact['mode']}/{artifact['caseId']} {key} mismatch: "
                f"report={report_row.get(key)} artifact={artifact.get(key)}"
            )
    return failures


def check(report_path, min_shared_cases):
    report = read_json(report_path)
    failures = []
    if report.get("schema") != MEASURE_SCHEMA:
        failures.append(
            f"expected schema {MEASURE_SCHEMA}, found {report.get('schema')}"
        )

    source_value = report.get("source")
    if not source_value:
        failures.append("report is missing source")
        source = None
    else:
        source = report_source(report_path, source_value)
        if not source.is_dir():
            failures.append(f"source directory does not exist: {source}")

    by_lane, row_failures = rows_by_lane(report.get("rows") or [])
    failures.extend(row_failures)
    shell_cases = set(by_lane["shell-only"])
    ygg_cases = set(by_lane["ygg"])
    shared_cases = sorted(shell_cases & ygg_cases)
    missing_ygg = sorted(shell_cases - ygg_cases)
    missing_shell = sorted(ygg_cases - shell_cases)
    if missing_ygg:
        failures.append(f"missing ygg rows for cases: {', '.join(missing_ygg)}")
    if missing_shell:
        failures.append(
            f"missing shell-only rows for cases: {', '.join(missing_shell)}"
        )
    if len(shared_cases) < min_shared_cases:
        failures.append(
            f"shared cases {len(shared_cases)} below minimum {min_shared_cases}"
        )

    case_deltas = []
    if source and source.is_dir():
        for case_id in shared_cases:
            shell_artifact, shell_error = artifact_row(source, "shell-only", case_id)
            ygg_artifact, ygg_error = artifact_row(source, "ygg", case_id)
            if shell_error:
                failures.append(shell_error)
                continue
            if ygg_error:
                failures.append(ygg_error)
                continue
            failures.extend(compare_row(by_lane["shell-only"][case_id], shell_artifact))
            failures.extend(compare_row(by_lane["ygg"][case_id], ygg_artifact))

            shell_tokens = shell_artifact["estimatedPromptTokens"]
            ygg_tokens = ygg_artifact["estimatedPromptTokens"]
            token_delta = ygg_tokens - shell_tokens
            byte_delta = ygg_artifact["promptBytes"] - shell_artifact["promptBytes"]
            if shell_tokens <= 0 or ygg_tokens <= 0:
                failures.append(f"non-positive prompt tokens for case {case_id}")
            if token_delta >= 0:
                failures.append(
                    f"ygg prompt did not reduce tokens for {case_id}: "
                    f"{shell_tokens} -> {ygg_tokens}"
                )
            case_deltas.append(
                {
                    "caseId": case_id,
                    "shellPromptTokens": shell_tokens,
                    "yggPromptTokens": ygg_tokens,
                    "tokenDelta": token_delta,
                    "shellPromptBytes": shell_artifact["promptBytes"],
                    "yggPromptBytes": ygg_artifact["promptBytes"],
                    "byteDelta": byte_delta,
                }
            )

    total_shell = sum(row["shellPromptTokens"] for row in case_deltas)
    total_ygg = sum(row["yggPromptTokens"] for row in case_deltas)
    result = {
        "schema": GATE_SCHEMA,
        "status": "failed" if failures else "passed",
        "report": str(report_path),
        "source": str(source) if source else source_value,
        "sharedCases": len(shared_cases),
        "totalShellPromptTokens": total_shell,
        "totalYggPromptTokens": total_ygg,
        "totalTokenDelta": total_ygg - total_shell,
        "allYggReduced": bool(case_deltas)
        and all(row["tokenDelta"] < 0 for row in case_deltas),
        "caseDeltas": case_deltas,
        "failures": failures,
    }
    return result


def main(argv):
    parser = argparse.ArgumentParser(
        description="Validate Ygg prompt-token reduction from prompt artifacts."
    )
    parser.add_argument("report", help="Prompt-token measure JSON report")
    parser.add_argument(
        "--min-shared-cases",
        type=int,
        default=1,
        help="Minimum shared shell-only/Ygg cases required.",
    )
    args = parser.parse_args(argv)

    report_path = pathlib.Path(args.report)
    if not report_path.is_file():
        print(f"report does not exist: {report_path}", file=sys.stderr)
        return 2

    result = check(report_path, args.min_shared_cases)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
