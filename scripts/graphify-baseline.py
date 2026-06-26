#!/usr/bin/env python3
"""Graphify benchmark baseline for Yggdrasil issue replay.

This script is intentionally a benchmark worker, not Yggdrasil core. It shells
out to the Graphify CLI, builds a bounded code-only graph by default, extracts
repo-relative file paths from Graphify query output and graph source metadata,
and writes the standard agent-result JSON shape.
"""

from __future__ import annotations

import json
import math
import os
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_GRAPHIFY_COMMAND = "uvx --from graphifyy graphify"
DEFAULT_TOOL_TIMEOUT_SECONDS = 900
DEFAULT_QUERY_BUDGET = 4000
MAX_FALLBACK_FILES = 40_000
MAX_QUERY_PATTERNS = 16
MIN_QUERY_PATTERN_LENGTH = 3
MAX_TEXT_CHARS = 24_000
SKIP_DIRS = {
    ".git",
    ".dev",
    ".hg",
    ".svn",
    ".cache",
    ".next",
    "__pycache__",
    "build",
    "dist",
    "graphify-out",
    "node_modules",
    "target",
}
NON_CODE_EXCLUDES = [
    "*.md",
    "*.mdx",
    "*.qmd",
    "*.txt",
    "*.rst",
    "*.html",
    "*.yaml",
    "*.yml",
    "*.pdf",
    "*.png",
    "*.jpg",
    "*.jpeg",
    "*.gif",
    "*.webp",
    "*.svg",
    "*.docx",
    "*.xlsx",
    "*.mp4",
    "*.mov",
    "*.webm",
    "*.mkv",
    "*.avi",
    "*.m4v",
    "*.mp3",
    "*.wav",
    "*.m4a",
    "*.ogg",
]
PATH_TOKEN_RE = re.compile(r"(?<![\w.-])(?:[\w.@+-]+/)+[\w.@+~=-][\w.@+~=/,-]*")
QUERY_TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9_-]{2,}")
SRC_RE = re.compile(r"src=([^\]\s]+)")


@dataclass(frozen=True)
class ToolResult:
    command: str
    ok: bool
    stdout: str
    stderr: str
    warning: str | None = None


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(value, f, indent=2)


def estimate_tokens(value: Any) -> int:
    return int(math.ceil(len(json.dumps(value, sort_keys=True)) / 4.0))


def result_surface_token_usage(result: dict) -> dict:
    input_tokens = estimate_tokens(
        {key: value for key, value in result.items() if key != "tokenUsage"}
    )
    return {
        "inputTokens": input_tokens,
        "outputTokens": 0,
        "totalTokens": input_tokens,
        "costUsd": 0.0,
        "source": "graphify-result-surface-estimate",
    }


def issue_query(request: dict) -> str:
    input_data = request.get("input") or {}
    parts = [
        input_data.get("title") or "",
        input_data.get("body") or "",
        "\n\n".join(input_data.get("comments") or []),
    ]
    return "\n\n".join(part for part in parts if part.strip())


def query_patterns(query: str) -> list[str]:
    seen: set[str] = set()
    patterns: list[str] = []
    for match in QUERY_TOKEN_RE.finditer(query):
        token = match.group(0).lower()
        if len(token) < MIN_QUERY_PATTERN_LENGTH or token in seen:
            continue
        seen.add(token)
        patterns.append(token)
        if len(patterns) >= MAX_QUERY_PATTERNS:
            break
    return patterns


def graphify_config(request: dict) -> dict:
    config = request.get("graphify") or {}
    return config if isinstance(config, dict) else {}


def graphify_command(request: dict) -> str:
    config = graphify_config(request)
    return (
        os.environ.get("GRAPHIFY_BENCH_CMD")
        or str(config.get("command") or "")
        or DEFAULT_GRAPHIFY_COMMAND
    )


def graphify_output_dir(request: dict, result_path: Path) -> Path:
    config = graphify_config(request)
    configured = config.get("outputDir")
    if configured:
        return Path(str(configured))
    return result_path.parent / "graphify"


def query_budget(request: dict) -> int:
    config = graphify_config(request)
    value = config.get("queryBudget", DEFAULT_QUERY_BUDGET)
    try:
        return int(value)
    except (TypeError, ValueError):
        return DEFAULT_QUERY_BUDGET


def max_workers(request: dict) -> int | None:
    config = graphify_config(request)
    value = config.get("maxWorkers")
    if value in (None, ""):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def code_only(request: dict) -> bool:
    config = graphify_config(request)
    return bool(config.get("codeOnly", True))


def command_argv(command: str) -> list[str]:
    return shlex.split(command)


def command_available(command: str) -> bool:
    try:
        argv = command_argv(command)
    except ValueError:
        return False
    if not argv:
        return False
    exe = argv[0]
    return shutil.which(exe) is not None or Path(exe).is_file()


def run_tool(
    command: str,
    root: Path,
    args: list[str],
    timeout_seconds: int = DEFAULT_TOOL_TIMEOUT_SECONDS,
) -> ToolResult:
    try:
        argv = command_argv(command) + args
    except ValueError as exc:
        return ToolResult(
            command=command,
            ok=False,
            stdout="",
            stderr=str(exc),
            warning=f"Invalid Graphify command {command!r}: {exc}.",
        )

    label = " ".join(shlex.quote(part) for part in argv)
    try:
        process = subprocess.run(
            argv,
            cwd=root,
            text=True,
            capture_output=True,
            timeout=timeout_seconds,
            check=False,
        )
    except FileNotFoundError:
        return ToolResult(
            command=label,
            ok=False,
            stdout="",
            stderr="",
            warning=f"{argv[0]} not found; set GRAPHIFY_BENCH_CMD or --graphify-bin.",
        )
    except subprocess.TimeoutExpired as e:
        return ToolResult(
            command=label,
            ok=False,
            stdout=e.stdout or "",
            stderr=e.stderr or "",
            warning=f"{label} timed out after {timeout_seconds}s.",
        )
    except OSError as e:
        return ToolResult(
            command=label,
            ok=False,
            stdout="",
            stderr=str(e),
            warning=f"{label} failed to start: {e}.",
        )

    stdout = process.stdout or ""
    stderr = process.stderr or ""
    warning = None
    if process.returncode != 0:
        warning = f"{label} exited {process.returncode}: {(stderr or stdout).strip()[:500]}"
    return ToolResult(
        command=label,
        ok=process.returncode == 0,
        stdout=stdout,
        stderr=stderr,
        warning=warning,
    )


def existing_repo_files(root: Path) -> set[str]:
    files: set[str] = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in SKIP_DIRS]
        for filename in filenames:
            path = Path(dirpath) / filename
            try:
                rel = path.relative_to(root).as_posix()
            except ValueError:
                continue
            files.add(rel)
            if len(files) >= MAX_FALLBACK_FILES:
                return files
    return files


def normalize_path(value: Any, root: Path, existing: set[str]) -> str | None:
    raw = str(value).strip().strip("\"'`")
    if not raw or len(raw) > 1024 or "\n" in raw:
        return None
    raw = raw.removeprefix("file://")
    raw = re.sub(r"[:#]\d+(?::\d+)?$", "", raw)
    raw = raw.replace("\\", "/")

    try:
        path = Path(raw)
        if path.is_absolute():
            rel = path.resolve().relative_to(root.resolve()).as_posix()
        else:
            rel = raw.lstrip("./")
    except (OSError, ValueError):
        return None

    if not rel or rel.startswith("../") or rel == ".":
        return None
    rel = re.sub(r"/+", "/", rel)
    if rel in existing:
        return rel
    return None


def source_surface(source: str) -> str:
    return source.split(":", 1)[0]


def record_path(
    value: Any,
    root: Path,
    existing: set[str],
    source: str,
    source_rank: int | None,
    found: dict[str, list[str]],
    source_ranks: dict[str, int],
    source_surfaces: dict[str, set[str]],
) -> None:
    rel = normalize_path(value, root, existing)
    if rel is None:
        return
    evidence = f"graphify:{source} path: {rel}"
    found.setdefault(rel, [])
    if evidence not in found[rel]:
        found[rel].append(evidence)
    rank = source_rank or 1
    source_ranks[rel] = min(rank, source_ranks.get(rel, rank))
    source_surfaces.setdefault(rel, set()).add(source_surface(source))


def extract_paths_from_query_text(
    text: str,
    root: Path,
    existing: set[str],
    found: dict[str, list[str]],
    source_ranks: dict[str, int],
    source_surfaces: dict[str, set[str]],
) -> None:
    rank = 0
    for match in SRC_RE.finditer(text[:MAX_TEXT_CHARS]):
        rank += 1
        record_path(
            match.group(1),
            root,
            existing,
            "query",
            rank,
            found,
            source_ranks,
            source_surfaces,
        )
    for match in PATH_TOKEN_RE.finditer(text[:MAX_TEXT_CHARS]):
        rank += 1
        record_path(
            match.group(0),
            root,
            existing,
            "query-text",
            rank,
            found,
            source_ranks,
            source_surfaces,
        )


def graph_rows(graph: dict) -> list[dict]:
    rows: list[dict] = []
    for node in graph.get("nodes") or []:
        if isinstance(node, dict):
            rows.append(
                {
                    "source_file": node.get("source_file"),
                    "text": " ".join(
                        str(node.get(key) or "")
                        for key in ("label", "id", "file_type", "source_location")
                    ),
                }
            )
    for edge in (graph.get("edges") or graph.get("links") or []):
        if isinstance(edge, dict):
            rows.append(
                {
                    "source_file": edge.get("source_file"),
                    "text": " ".join(
                        str(edge.get(key) or "")
                        for key in ("relation", "context", "source", "target", "source_location")
                    ),
                }
            )
    return rows


def graph_match_score(row: dict, patterns: list[str]) -> int:
    text = str(row.get("text") or "").lower()
    return sum(1 for pattern in patterns if pattern in text)


def extract_paths_from_graph(
    graph_path: Path,
    patterns: list[str],
    root: Path,
    existing: set[str],
    found: dict[str, list[str]],
    source_ranks: dict[str, int],
    source_surfaces: dict[str, set[str]],
) -> int:
    if not graph_path.is_file() or not patterns:
        return 0
    try:
        graph = read_json(graph_path)
    except (OSError, json.JSONDecodeError):
        return 0
    ranked = sorted(
        (
            (graph_match_score(row, patterns), idx, row)
            for idx, row in enumerate(graph_rows(graph), start=1)
        ),
        key=lambda item: (-item[0], item[1]),
    )
    recorded = 0
    for score, idx, row in ranked:
        if score <= 0:
            break
        before = len(found)
        record_path(
            row.get("source_file"),
            root,
            existing,
            f"graph-token-match:{score}",
            idx,
            found,
            source_ranks,
            source_surfaces,
        )
        if len(found) > before:
            recorded += 1
    return recorded


def compact_evidence_by_surface(evidence: list[str]) -> list[str]:
    seen: set[str] = set()
    compact: list[str] = []
    for item in evidence:
        surface = item.split(":", 2)[1] if item.startswith("graphify:") else item
        surface = source_surface(surface)
        if surface in seen:
            continue
        seen.add(surface)
        compact.append(item)
    return compact


def ranked_files(
    found: dict[str, list[str]],
    source_ranks: dict[str, int],
    source_surfaces: dict[str, set[str]],
    limit: int,
) -> list[dict]:
    rows = sorted(
        found.items(),
        key=lambda item: (
            -len(source_surfaces.get(item[0], set())),
            source_ranks.get(item[0], limit + 1),
            item[0],
        ),
    )
    suspected = []
    for idx, (path, evidence) in enumerate(rows[:limit], start=1):
        evidence = compact_evidence_by_surface(evidence)
        support_count = len(source_surfaces.get(path, set()))
        first_source_rank = source_ranks.get(path, idx)
        confidence = min(1.0, 0.55 + (0.1 * support_count))
        suspected.append(
            {
                "path": path,
                "rank": idx,
                "confidence": confidence,
                "reason": "Graphify query or graph metadata returned this repo-relative path.",
                "evidence": evidence,
                "metrics": {
                    "supportCount": support_count,
                    "firstSourceRank": first_source_rank,
                    "maxConfidence": confidence,
                },
            }
        )
    return suspected


def result_base(request: dict, warnings: list[str], commands: list[str], suspected: list[dict]) -> dict:
    result = {
        "schema": "ygg.benchmark.agent-result/v2",
        "caseId": request.get("caseId"),
        "caseFingerprint": request.get("caseFingerprint"),
        "agentId": request.get("agentId") or "ygg-baseline-graphify",
        "mode": "graphify",
        "suspectedFiles": suspected,
        "suspectedSymbols": [],
        "commands": commands,
        "warnings": warnings,
        "summary": (
            f"Graphify baseline ranked {len(suspected)} files."
            if suspected
            else "Graphify baseline returned no suspected files."
        ),
    }
    if request.get("agentInputFingerprint"):
        result["agentInputFingerprint"] = request.get("agentInputFingerprint")
    result["tokenUsage"] = result_surface_token_usage(result)
    return result


def extract_args(request: dict, root: Path, output_dir: Path) -> list[str]:
    args = ["extract", str(root), "--no-cluster", "--out", str(output_dir)]
    workers = max_workers(request)
    if workers is not None:
        args.extend(["--max-workers", str(workers)])
    if code_only(request):
        for pattern in NON_CODE_EXCLUDES:
            args.extend(["--exclude", pattern])
    return args


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: graphify-baseline.py REQUEST_JSON RESULT_JSON", file=sys.stderr)
        return 2

    request_path = Path(argv[1])
    result_path = Path(argv[2])
    request = read_json(request_path)
    root = Path(request["worktreeRoot"]).resolve()
    limit = int(request.get("limit") or 20)
    output_dir = graphify_output_dir(request, result_path).resolve()
    graph_path = output_dir / "graphify-out" / "graph.json"
    query = issue_query(request)
    command = graphify_command(request)

    warnings: list[str] = []
    commands: list[str] = []
    found: dict[str, list[str]] = {}
    source_ranks: dict[str, int] = {}
    source_surfaces: dict[str, set[str]] = {}
    existing = existing_repo_files(root)

    if code_only(request):
        warnings.append(
            "Graphify baseline used code-only extraction so the benchmark is replayable without LLM API keys."
        )

    if not command_available(command):
        warnings.append(
            f"Graphify command not found: {command!r}; set GRAPHIFY_BENCH_CMD or --graphify-bin."
        )
        write_json(result_path, result_base(request, warnings, commands, []))
        return 0

    output_dir.mkdir(parents=True, exist_ok=True)
    extract_result = run_tool(command, root, extract_args(request, root, output_dir))
    commands.append(extract_result.command)
    if extract_result.warning:
        warnings.append(extract_result.warning)

    if graph_path.is_file():
        query_result = run_tool(
            command,
            root,
            [
                "query",
                query,
                "--graph",
                str(graph_path),
                "--budget",
                str(query_budget(request)),
            ],
        )
        commands.append(query_result.command)
        if query_result.warning:
            warnings.append(query_result.warning)
        extract_paths_from_query_text(
            query_result.stdout,
            root,
            existing,
            found,
            source_ranks,
            source_surfaces,
        )
        recorded = extract_paths_from_graph(
            graph_path,
            query_patterns(query),
            root,
            existing,
            found,
            source_ranks,
            source_surfaces,
        )
        if recorded == 0 and not found:
            warnings.append("Graphify graph contained no query-matching source paths.")
    else:
        warnings.append(f"Graphify graph not found at {graph_path}.")

    suspected = ranked_files(found, source_ranks, source_surfaces, limit)
    write_json(result_path, result_base(request, warnings, commands, suspected))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
