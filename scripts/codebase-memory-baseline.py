#!/usr/bin/env python3
"""Codebase Memory MCP benchmark baseline for Yggdrasil issue replay.

This script is intentionally a benchmark worker, not Yggdrasil core. It shells out
to an already-installed codebase-memory-mcp binary, runs bounded read-only CLI
queries, extracts repo-relative file paths, and writes the standard agent-result
JSON shape.
"""

from __future__ import annotations

import json
import math
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_BINARY = "codebase-memory-mcp"
DEFAULT_TOOL_TIMEOUT_SECONDS = 300
MAX_TEXT_CHARS = 12_000
MAX_FALLBACK_FILES = 40_000
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
    "node_modules",
    "target",
}
PATH_KEYS = {
    "file",
    "file_path",
    "filepath",
    "filename",
    "path",
    "relative_path",
    "source_file",
    "source_path",
    "target_file",
    "target_path",
}
PATH_TOKEN_RE = re.compile(r"(?<![\w.-])(?:[\w.@+-]+/)+[\w.@+~=-][\w.@+~=/,-]*")
QUERY_TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9_-]{2,}")
MAX_QUERY_PATTERNS = 8
MIN_QUERY_PATTERN_LENGTH = 4
ARCHITECTURE_INVENTORY_KEYS = {"file_tree"}


@dataclass(frozen=True)
class ToolResult:
    tool: str
    command: str
    ok: bool
    stdout: str
    stderr: str
    parsed: Any
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
        "source": "codebase-memory-result-surface-estimate",
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


def codebase_memory_config(request: dict) -> dict:
    config = request.get("codebaseMemory") or {}
    return config if isinstance(config, dict) else {}


def resolve_binary(request: dict) -> str:
    config = codebase_memory_config(request)
    return (
        os.environ.get("CODEBASE_MEMORY_MCP_BIN")
        or str(config.get("binary") or "")
        or DEFAULT_BINARY
    )


def cache_dir(request: dict, result_path: Path) -> Path:
    config = codebase_memory_config(request)
    configured = config.get("cacheDir")
    if configured:
        return Path(str(configured))
    return result_path.parent / "codebase-memory-cache"


def tool_env(cache: Path) -> dict[str, str]:
    env = dict(os.environ)
    cache.mkdir(parents=True, exist_ok=True)
    env["CODEBASE_MEMORY_MCP_CACHE_DIR"] = str(cache)
    env["CBM_CACHE_DIR"] = str(cache)
    env["XDG_CACHE_HOME"] = str(cache)
    return env


def parse_json_output(text: str) -> Any:
    stripped = text.strip()
    if not stripped:
        return None
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass

    decoder = json.JSONDecoder()
    parsed_values: list[Any] = []
    for idx, ch in enumerate(stripped):
        if ch not in "[{":
            continue
        try:
            value, _ = decoder.raw_decode(stripped[idx:])
        except json.JSONDecodeError:
            continue
        parsed_values.append(value)
    return parsed_values[-1] if parsed_values else None


def command_label(binary: str, tool: str) -> str:
    return f"{Path(binary).name} cli {tool}"


def run_tool(
    binary: str,
    root: Path,
    cache: Path,
    tool: str,
    payload: dict | None = None,
    timeout_seconds: int = DEFAULT_TOOL_TIMEOUT_SECONDS,
) -> ToolResult:
    cmd = [binary, "cli", tool]
    if payload is not None:
        cmd.append(json.dumps(payload, separators=(",", ":")))

    label = command_label(binary, tool)
    try:
        process = subprocess.run(
            cmd,
            cwd=root,
            env=tool_env(cache),
            text=True,
            capture_output=True,
            timeout=timeout_seconds,
            check=False,
        )
    except FileNotFoundError:
        return ToolResult(
            tool=tool,
            command=label,
            ok=False,
            stdout="",
            stderr="",
            parsed=None,
            warning=f"{binary} not found; set CODEBASE_MEMORY_MCP_BIN or --codebase-memory-bin.",
        )
    except subprocess.TimeoutExpired as e:
        return ToolResult(
            tool=tool,
            command=label,
            ok=False,
            stdout=e.stdout or "",
            stderr=e.stderr or "",
            parsed=parse_json_output(e.stdout or ""),
            warning=f"{label} timed out after {timeout_seconds}s.",
        )
    except OSError as e:
        return ToolResult(
            tool=tool,
            command=label,
            ok=False,
            stdout="",
            stderr=str(e),
            parsed=None,
            warning=f"{label} failed to start: {e}.",
        )

    stdout = process.stdout or ""
    stderr = process.stderr or ""
    warning = None
    if process.returncode != 0:
        warning = f"{label} exited {process.returncode}: {(stderr or stdout).strip()[:500]}"
    return ToolResult(
        tool=tool,
        command=label,
        ok=process.returncode == 0,
        stdout=stdout,
        stderr=stderr,
        parsed=parse_json_output(stdout),
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
    if not raw or len(raw) > 512 or "\n" in raw:
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


def record_path(
    value: Any,
    root: Path,
    existing: set[str],
    source: str,
    found: dict[str, list[str]],
) -> None:
    rel = normalize_path(value, root, existing)
    if rel is None:
        return
    evidence = f"codebase-memory:{source} path={rel}"
    found.setdefault(rel, [])
    if evidence not in found[rel]:
        found[rel].append(evidence)


def extract_paths_from_json(
    value: Any,
    root: Path,
    existing: set[str],
    source: str,
    found: dict[str, list[str]],
) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key).lower().replace("-", "_")
            if source == "get_architecture" and key_text in ARCHITECTURE_INVENTORY_KEYS:
                continue
            if key_text in PATH_KEYS:
                record_path(item, root, existing, source, found)
            extract_paths_from_json(item, root, existing, source, found)
    elif isinstance(value, list):
        for item in value:
            extract_paths_from_json(item, root, existing, source, found)
    elif isinstance(value, str) and len(value) <= MAX_TEXT_CHARS:
        record_path(value, root, existing, source, found)


def extract_paths_from_text(
    text: str,
    root: Path,
    existing: set[str],
    source: str,
    found: dict[str, list[str]],
) -> None:
    for match in PATH_TOKEN_RE.finditer(text[:MAX_TEXT_CHARS]):
        record_path(match.group(0), root, existing, source, found)


def ranked_files(found: dict[str, list[str]], limit: int) -> list[dict]:
    rows = sorted(
        found.items(),
        key=lambda item: (-len(item[1]), item[0]),
    )
    suspected = []
    for idx, (path, evidence) in enumerate(rows[:limit], start=1):
        support_count = len(evidence)
        confidence = min(1.0, 0.55 + (0.1 * support_count))
        suspected.append(
            {
                "path": path,
                "rank": idx,
                "confidence": confidence,
                "reason": "Codebase Memory MCP returned this exact repo-relative path.",
                "evidence": evidence,
                "metrics": {
                    "supportCount": support_count,
                    "firstSourceRank": idx,
                    "maxConfidence": confidence,
                },
            }
        )
    return suspected


def discovered_project(root: Path, parsed: Any) -> str | None:
    if not isinstance(parsed, dict):
        return None
    projects = parsed.get("projects")
    if not isinstance(projects, list):
        return None
    root_text = root.resolve().as_posix()
    first_name = None
    for project in projects:
        if not isinstance(project, dict):
            continue
        name = project.get("name")
        if not first_name and name:
            first_name = str(name)
        if str(project.get("root_path") or "") == root_text and name:
            return str(name)
    return first_name


def result_base(request: dict, warnings: list[str], commands: list[str], suspected: list[dict]) -> dict:
    result = {
        "schema": "ygg.benchmark.agent-result/v2",
        "caseId": request.get("caseId"),
        "caseFingerprint": request.get("caseFingerprint"),
        "agentId": request.get("agentId") or "ygg-baseline-codebase-memory",
        "mode": "codebase-memory",
        "suspectedFiles": suspected,
        "suspectedSymbols": [],
        "commands": commands,
        "warnings": warnings,
        "summary": (
            f"Codebase Memory MCP baseline ranked {len(suspected)} files."
            if suspected
            else "Codebase Memory MCP baseline returned no suspected files."
        ),
    }
    if request.get("agentInputFingerprint"):
        result["agentInputFingerprint"] = request.get("agentInputFingerprint")
    result["tokenUsage"] = result_surface_token_usage(result)
    return result


def tool_payloads(project: str | None, query: str, limit: int) -> list[tuple[str, str, dict | None]]:
    if not project:
        return []
    payloads: list[tuple[str, str, dict | None]] = []
    for pattern in query_patterns(query):
        payloads.append(
            (
                f"search_code:{pattern}",
                "search_code",
                {"project": project, "pattern": pattern, "limit": limit},
            )
        )
    for pattern in query_patterns(query)[:3]:
        payloads.append(
            (
                f"search_graph:{pattern}",
                "search_graph",
                {"project": project, "name_pattern": f".*{re.escape(pattern)}.*", "limit": limit},
            )
        )
    payloads.append(("get_architecture", "get_architecture", {"project": project, "limit": limit}))
    return payloads


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: codebase-memory-baseline.py REQUEST_JSON RESULT_JSON", file=sys.stderr)
        return 2

    request_path = Path(argv[1])
    result_path = Path(argv[2])
    request = read_json(request_path)
    root = Path(request["worktreeRoot"]).resolve()
    limit = int(request.get("limit") or 20)
    query = issue_query(request)
    binary = resolve_binary(request)
    cache = cache_dir(request, result_path)

    warnings: list[str] = []
    commands: list[str] = []
    found: dict[str, list[str]] = {}
    existing = existing_repo_files(root)

    if shutil.which(binary) is None and not Path(binary).is_file():
        warnings.append(
            f"{binary} not found; set CODEBASE_MEMORY_MCP_BIN or --codebase-memory-bin."
        )
        write_json(result_path, result_base(request, warnings, commands, []))
        return 0

    index_result = run_tool(binary, root, cache, "index_repository", {"repo_path": str(root)})
    commands.append(index_result.command)
    if index_result.warning:
        warnings.append(index_result.warning)

    projects_result = run_tool(binary, root, cache, "list_projects", None)
    commands.append(projects_result.command)
    if projects_result.warning:
        warnings.append(projects_result.warning)
    project = discovered_project(root, projects_result.parsed)
    if not project:
        warnings.append("codebase-memory-mcp did not report an indexed project for the benchmark worktree.")

    for source, tool, payload in tool_payloads(project, query, limit):
        tool_result = run_tool(binary, root, cache, tool, payload)
        commands.append(tool_result.command)
        if tool_result.warning:
            warnings.append(tool_result.warning)
        if tool_result.parsed is not None:
            extract_paths_from_json(tool_result.parsed, root, existing, source, found)
        else:
            extract_paths_from_text(tool_result.stdout, root, existing, source, found)

    suspected = ranked_files(found, limit)
    write_json(result_path, result_base(request, warnings, commands, suspected))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
