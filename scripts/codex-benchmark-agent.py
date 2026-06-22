#!/usr/bin/env python3
"""Codex CLI benchmark worker with token sidecar extraction.

The benchmark runner executes agent commands from each case worktree. This
wrapper runs `codex exec --json`, lets Codex write the normal
`YGG_BENCH_RESULT`, and writes `YGG_BENCH_TOKEN_USAGE` when token usage is
present in Codex JSONL events.
"""

from __future__ import annotations

import json
import os
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any


def env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value is not None and value.strip():
        return value
    return default


def number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def first_number(mapping: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        if key in mapping:
            value = number(mapping[key])
            if value is not None:
                return value
    return None


def usage_record(mapping: dict[str, Any]) -> dict[str, Any] | None:
    usage = mapping.get("usage") if isinstance(mapping.get("usage"), dict) else mapping
    if not isinstance(usage, dict):
        return None
    input_tokens = first_number(
        usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens"
    )
    output_tokens = first_number(
        usage,
        "outputTokens",
        "output_tokens",
        "completionTokens",
        "completion_tokens",
    )
    total_tokens = first_number(usage, "totalTokens", "total_tokens", "total")
    if total_tokens is None and (input_tokens is not None or output_tokens is not None):
        total_tokens = (input_tokens or 0) + (output_tokens or 0)
    if input_tokens is None and output_tokens is None and total_tokens is None:
        return None
    return {
        "inputTokens": int(input_tokens or 0),
        "outputTokens": int(output_tokens or 0),
        "totalTokens": int(total_tokens or 0),
        "costUsd": float(first_number(usage, "costUsd", "cost_usd") or 0.0),
    }


def usage_records(value: Any) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    if isinstance(value, dict):
        record = usage_record(value)
        if record:
            records.append(record)
        for nested in value.values():
            records.extend(usage_records(nested))
    elif isinstance(value, list):
        for item in value:
            records.extend(usage_records(item))
    return records


def token_usage_from_jsonl(text: str) -> dict[str, Any] | None:
    records: list[dict[str, Any]] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            records.extend(usage_records(json.loads(line)))
        except json.JSONDecodeError:
            continue
    if not records:
        return None
    # Codex JSON event streams are expected to include cumulative usage near the
    # end. Pick the largest observed total to avoid double-counting cumulative
    # progress events.
    best = max(records, key=lambda row: row.get("totalTokens", 0))
    best["source"] = "codex-json-events"
    if env("YGG_CODEX_MODEL"):
        best["model"] = env("YGG_CODEX_MODEL")
    best["provider"] = "openai"
    return best


def write_token_usage(stdout: str) -> None:
    usage_path = env("YGG_BENCH_TOKEN_USAGE")
    if not usage_path:
        return
    usage = token_usage_from_jsonl(stdout)
    if not usage:
        return
    path = Path(usage_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(usage, indent=2) + "\n", encoding="utf-8")


def codex_command() -> list[str]:
    override = env("YGG_CODEX_COMMAND")
    if override:
        return shlex.split(override)
    command = [
        env("YGG_CODEX_BIN", "codex") or "codex",
        "-a",
        env("YGG_CODEX_APPROVAL_POLICY", "never") or "never",
        "exec",
        "--json",
        "--sandbox",
        env("YGG_CODEX_SANDBOX", "read-only") or "read-only",
    ]
    if env("YGG_CODEX_MODEL"):
        command.extend(["--model", env("YGG_CODEX_MODEL") or ""])
    if env("YGG_CODEX_EXTRA_ARGS"):
        command.extend(shlex.split(env("YGG_CODEX_EXTRA_ARGS") or ""))
    output_schema = env("YGG_BENCH_OUTPUT_SCHEMA")
    if output_schema:
        command.extend(["--output-schema", output_schema])
    result_path = env("YGG_BENCH_RESULT")
    if result_path:
        command.extend(["-o", result_path])
    command.append("-")
    return command


def read_prompt() -> str:
    prompt_path = env("YGG_BENCH_PROMPT")
    if prompt_path and Path(prompt_path).is_file():
        return Path(prompt_path).read_text(encoding="utf-8")
    return sys.stdin.read()


def main() -> int:
    result = subprocess.run(
        codex_command(),
        input=read_prompt(),
        text=True,
        capture_output=True,
        cwd=env("YGG_BENCH_WORKTREE", os.getcwd()),
    )
    if result.stdout:
        print(result.stdout, end="")
    if result.stderr:
        print(result.stderr, end="", file=sys.stderr)
    write_token_usage(result.stdout)
    return int(result.returncode)


if __name__ == "__main__":
    raise SystemExit(main())
