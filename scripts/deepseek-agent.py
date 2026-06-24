#!/usr/bin/env python3
"""DeepSeek Anthropic-compatible benchmark agent for Yggdrasil issue replay.

This script is a benchmark worker that uses the DeepSeek Anthropic-compatible
Messages API with deepseek-v4-pro and max thinking to localize files for
benchmark cases. It implements a tool-use agent loop: the model can read
files, list directories, and run bounded shell commands (rg, bb query, etc.)
before writing the standard agent-result JSON to YGG_BENCH_RESULT.

Usage:
    python3 scripts/deepseek-agent.py
    python3 scripts/deepseek-agent.py "$(cat "$YGG_BENCH_PROMPT")"

Environment:
    YGG_BENCH_RESULT          Required: path to write result JSON
    YGG_BENCH_WORKTREE        Required: repo checkout root
    YGG_BENCH_PACKET          Packet JSON path
    YGG_BENCH_YGG_HINTS    Yggdrasil hints JSON (optional, ygg mode)
    YGG_BENCH_YGG_CONTEXT  Yggdrasil context JSON (optional, ygg mode)
    YGG_BENCH_CASE_ID         Case ID
    YGG_BENCH_CASE_FINGERPRINT  Case fingerprint
    YGG_BENCH_AGENT_INPUT_FINGERPRINT  Agent input fingerprint (optional)
    YGG_BENCH_AGENT_ID        Agent ID
    YGG_BENCH_MODE            ygg | shell-only
    YGG_BENCH_PROJECT         Project config path
    YGG_BENCH_XTDB_PATH       XTDB path
    DEEPSEEK_API_KEY             Required: API key for DeepSeek
    DEEPSEEK_ENV_FILE            Optional: path to env file with DEEPSEEK_API_KEY
    DEEPSEEK_ENDPOINT            Optional: API endpoint override
    DEEPSEEK_MODEL               Optional: model override (default: deepseek-v4-pro)
    DEEPSEEK_MAX_OUTPUT_TOKENS   Optional: max output tokens (default: 16384)
    DEEPSEEK_MAX_ITERATIONS      Optional: max agent loop iterations (default: 25)
    DEEPSEEK_MOCK_RESPONSE       Optional: JSON response or response path for tests
"""

from __future__ import annotations

import json
import os
import shlex
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
DEFAULT_MODEL = "deepseek-v4-pro"
DEFAULT_MAX_OUTPUT_TOKENS = 16384
DEFAULT_MAX_ITERATIONS = 25
TOOL_TIMEOUT_SEC = 30
MAX_TOOL_OUTPUT_CHARS = 8000
MAX_FILE_CHARS = 16000
READ_ONLY_COMMANDS = {
    "bb",
    "cat",
    "find",
    "git",
    "grep",
    "head",
    "ls",
    "pwd",
    "rg",
    "sed",
    "tail",
    "wc",
}
READ_ONLY_GIT_SUBCOMMANDS = {
    "blame",
    "diff",
    "grep",
    "log",
    "ls-files",
    "rev-parse",
    "show",
    "status",
}
READ_ONLY_BB_SUBCOMMANDS = {
    "packages",
    "query",
    "view",
}
DENIED_BB_FLAGS = {
    "--enqueue",
    "--watch",
}

AGENT_RESULT_SCHEMA = "ygg.benchmark.agent-result/v2"


def env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value is not None and value.strip():
        return value
    return default


def load_api_key() -> str:
    key = env("DEEPSEEK_API_KEY")
    if key:
        return key
    env_file = env("DEEPSEEK_ENV_FILE")
    if env_file and Path(env_file).is_file():
        for line in Path(env_file).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("DEEPSEEK_API_KEY="):
                key = line.split("=", 1)[1].strip()
                if key:
                    return key
    raise SystemExit(
        "DEEPSEEK_API_KEY is required. Set it in the environment or in "
        "DEEPSEEK_ENV_FILE."
    )


def read_json_file(path: str | None) -> dict:
    if not path:
        return {}
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def read_prompt(argv: list[str]) -> str:
    if len(argv) >= 2:
        return argv[1]
    prompt_path = env("YGG_BENCH_PROMPT")
    if prompt_path and Path(prompt_path).is_file():
        return Path(prompt_path).read_text(encoding="utf-8")
    if not sys.stdin.isatty():
        return sys.stdin.read()
    return ""


def parser_worker_profile() -> dict:
    mode = env("YGG_BENCH_PARSER_WORKER") or env("YGG_PARSER_WORKER") or "none"
    source = "env" if env("YGG_BENCH_PARSER_WORKER") or env("YGG_PARSER_WORKER") else "default"
    return {"mode": mode, "source": source}


def selection_from_packet() -> dict:
    context = read_json_file(env("YGG_BENCH_YGG_CONTEXT"))
    candidate_files = context.get("candidateFiles") or []
    coverage = context.get("coverage") or {}
    return {
        "rawCandidateFiles": len(candidate_files),
        "candidateFiles": len(candidate_files),
        "coverageFilteredCandidateFiles": len(candidate_files),
        "limit": None,
        "coverageSourceKinds": coverage.get("sourceKinds") or [],
    }


def build_system_prompt(user_prompt: str) -> str:
    worktree = env("YGG_BENCH_WORKTREE", "")
    mode = env("YGG_BENCH_MODE", "shell-only")
    hints_path = env("YGG_BENCH_YGG_HINTS")
    context_path = env("YGG_BENCH_YGG_CONTEXT")
    project = env("YGG_BENCH_PROJECT")
    xtdb_path = env("YGG_BENCH_XTDB_PATH")

    parts = [
        "You are a benchmark agent for Yggdrasil issue localization.",
        f"Your working directory is: {worktree}",
        "",
        "Use the available tools to inspect the repository and Yggdrasil artifacts.",
        "Read the benchmark prompt carefully and follow its instructions exactly.",
        "When you have identified the suspected files, use the write_result tool",
        "to write the final JSON result. The result must match the agent-result",
        f"schema {AGENT_RESULT_SCHEMA}.",
        "",
        f"Mode: {mode}",
    ]

    if hints_path:
        parts.append(f"Yggdrasil hints JSON: {hints_path}")
        parts.append("Read this first for pre-computed file rankings and architecture context.")
    if context_path:
        parts.append(f"Yggdrasil context JSON: {context_path}")
    if project:
        parts.append(f"Project config: {project}")
    if xtdb_path:
        parts.append(f"XTDB path: {xtdb_path}")

    parts.extend([
        "",
        "## Available Yggdrasil commands (run via run_command tool):",
        "- bb query \"<query>\" --project <project-id> --json",
        "- bb view systems --project <project-id>",
        "- bb packages --project <project-id> --json",
        "- rg <pattern>  (search the worktree)",
        "",
        "## Important rules:",
        "- Use repo-relative paths from the worktree root.",
        "- Each suspected file needs at least one evidence string containing",
        "  its exact repo-relative path.",
        "- Cite the command or artifact that led you to each file.",
        "- Prefer reading Yggdrasil hints/context before broad shell search.",
        "- Use the write_result tool to finalize your answer.",
    ])

    return "\n".join(parts)


TOOL_DEFINITIONS = [
    {
        "name": "read_file",
        "description": (
            "Read the contents of a file. The path should be relative to the "
            "worktree root, or an absolute path. Returns up to "
            f"{MAX_FILE_CHARS} characters."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Repo-relative or absolute file path.",
                },
            },
            "required": ["path"],
        },
    },
    {
        "name": "list_directory",
        "description": "List files and directories at the given path.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Repo-relative or absolute directory path. Defaults to worktree root.",
                },
            },
        },
    },
    {
        "name": "run_command",
        "description": (
            "Run a shell command in the worktree directory. Use for rg, bb query, "
            "bb view, bb packages, or other read-only inspection. "
            f"Command timeout is {TOOL_TIMEOUT_SEC}s. Output truncated to "
            f"{MAX_TOOL_OUTPUT_CHARS} chars."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to execute.",
                },
            },
            "required": ["command"],
        },
    },
    {
        "name": "write_result",
        "description": (
            "Write the final benchmark result JSON and stop. The result must "
            f"match schema {AGENT_RESULT_SCHEMA}. Pass the complete JSON object "
            "as the 'result' parameter."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "result": {
                    "type": "object",
                    "description": "The complete agent-result JSON object.",
                },
            },
            "required": ["result"],
        },
    },
]


def resolve_path(path_str: str, worktree: str) -> Path:
    p = Path(path_str)
    if p.is_absolute():
        return p
    return Path(worktree) / p


def allowed_artifact_paths() -> set[Path]:
    keys = [
        "YGG_BENCH_PACKET",
        "YGG_BENCH_YGG_HINTS",
        "YGG_BENCH_YGG_CONTEXT",
        "YGG_BENCH_OUTPUT_SCHEMA",
        "YGG_BENCH_PROMPT",
        "YGG_BENCH_PROJECT",
    ]
    paths = set()
    for key in keys:
        value = env(key)
        if value:
            try:
                paths.add(Path(value).resolve())
            except OSError:
                pass
    return paths


def is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (OSError, ValueError):
        return False


def readable_path(path: Path, worktree: str) -> bool:
    try:
        resolved = path.resolve()
    except OSError:
        return False
    return is_within(resolved, Path(worktree)) or resolved in allowed_artifact_paths()


def tool_read_file(args: dict, worktree: str) -> str:
    path = resolve_path(args.get("path", ""), worktree)
    if not readable_path(path, worktree):
        return f"Error reading {path}: path is outside the worktree and benchmark artifacts."
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        return f"Error reading {path}: {e}"
    if len(content) > MAX_FILE_CHARS:
        content = content[:MAX_FILE_CHARS] + "\n...<truncated>"
    return content


def tool_list_directory(args: dict, worktree: str) -> str:
    path = resolve_path(args.get("path", "."), worktree)
    if not is_within(path, Path(worktree)):
        return f"Error listing {path}: path is outside the worktree."
    try:
        entries = sorted(path.iterdir(), key=lambda p: (p.is_file(), p.name))
    except OSError as e:
        return f"Error listing {path}: {e}"
    lines = []
    for entry in entries:
        kind = "dir" if entry.is_dir() else "file"
        lines.append(f"{kind}  {entry.name}")
    return "\n".join(lines) if lines else "(empty)"


def validate_read_only_command(command: str) -> list[str] | str:
    try:
        argv = shlex.split(command)
    except ValueError as e:
        return f"Error: invalid command syntax: {e}"
    if not argv:
        return "Error: empty command."
    executable = Path(argv[0]).name
    if executable not in READ_ONLY_COMMANDS:
        return f"Error: command '{executable}' is not allowed for benchmark inspection."
    if executable == "git":
        subcommand = next((arg for arg in argv[1:] if not arg.startswith("-")), "")
        if subcommand not in READ_ONLY_GIT_SUBCOMMANDS:
            return f"Error: git subcommand '{subcommand}' is not allowed."
    if executable == "bb":
        subcommand = argv[1] if len(argv) > 1 else ""
        if subcommand == "sync":
            sync_args = argv[2:]
            allowed_sync = (
                (sync_args and sync_args[0] == "inspect")
                or ("--check" in sync_args and not any(flag in sync_args for flag in DENIED_BB_FLAGS))
            )
            if not allowed_sync:
                return "Error: only `bb sync inspect` and `bb sync ... --check` are allowed."
        elif subcommand not in READ_ONLY_BB_SUBCOMMANDS:
            return f"Error: bb subcommand '{subcommand}' is not allowed."
    return argv


def tool_run_command(args: dict, worktree: str) -> str:
    command = args.get("command", "")
    if not command.strip():
        return "Error: empty command."
    argv = validate_read_only_command(command)
    if isinstance(argv, str):
        return argv
    try:
        result = subprocess.run(
            argv,
            shell=False,
            cwd=worktree,
            capture_output=True,
            text=True,
            timeout=TOOL_TIMEOUT_SEC,
        )
        output = result.stdout
        if result.stderr:
            output = output + "\n--- stderr ---\n" + result.stderr if output else result.stderr
        if result.returncode != 0:
            output = f"(exit code {result.returncode})\n{output}"
    except subprocess.TimeoutExpired:
        return f"Error: command timed out after {TOOL_TIMEOUT_SEC}s."
    except OSError as e:
        return f"Error running command: {e}"
    if len(output) > MAX_TOOL_OUTPUT_CHARS:
        output = output[:MAX_TOOL_OUTPUT_CHARS] + "\n...<truncated>"
    return output


def execute_tool(tool_name: str, tool_input: dict, worktree: str) -> str:
    if tool_name == "read_file":
        return tool_read_file(tool_input, worktree)
    elif tool_name == "list_directory":
        return tool_list_directory(tool_input, worktree)
    elif tool_name == "run_command":
        return tool_run_command(tool_input, worktree)
    elif tool_name == "write_result":
        return "__WRITE_RESULT__"
    return f"Error: unknown tool '{tool_name}'"


def call_api(
    api_key: str,
    endpoint: str,
    model: str,
    max_tokens: int,
    messages: list[dict],
    system: str,
) -> dict:
    mock_response = env("DEEPSEEK_MOCK_RESPONSE")
    if mock_response:
        mock_path = Path(mock_response)
        if mock_path.is_file():
            return json.loads(mock_path.read_text(encoding="utf-8"))
        return json.loads(mock_response)

    body = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": messages,
        "stream": False,
        "system": system,
        "thinking": {"type": "enabled"},
        "output_config": {"effort": "xhigh"},
        "tools": TOOL_DEFINITIONS,
    }
    payload = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        endpoint,
        data=payload,
        headers={
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"DeepSeek API error (HTTP {e.code}): {error_body}"
        ) from e


def build_result(
    suspected_files: list[dict],
    commands: list[str],
    summary: str,
    warnings: list[str],
    token_usage: dict,
) -> dict:
    result = {
        "schema": AGENT_RESULT_SCHEMA,
        "caseId": env("YGG_BENCH_CASE_ID"),
        "caseFingerprint": env("YGG_BENCH_CASE_FINGERPRINT"),
        "agentId": env("YGG_BENCH_AGENT_ID", "deepseek-v4-pro-agent"),
        "mode": env("YGG_BENCH_MODE", "ygg"),
        "selection": selection_from_packet(),
        "parserWorker": parser_worker_profile(),
        "suspectedFiles": suspected_files,
        "suspectedSymbols": [],
        "commands": commands,
        "warnings": warnings,
        "summary": summary,
    }
    if env("YGG_BENCH_AGENT_INPUT_FINGERPRINT"):
        result["agentInputFingerprint"] = env("YGG_BENCH_AGENT_INPUT_FINGERPRINT")
    result["tokenUsage"] = {
        "inputTokens": token_usage.get("input_tokens", 0),
        "outputTokens": token_usage.get("output_tokens", 0),
        "totalTokens": token_usage.get("input_tokens", 0)
        + token_usage.get("output_tokens", 0),
        "costUsd": 0.0,
        "source": "deepseek-agent",
    }
    return result


def normalize_result(result: dict, commands: list[str], warnings: list[str], token_usage: dict) -> dict:
    result.setdefault("schema", AGENT_RESULT_SCHEMA)
    result.setdefault("caseId", env("YGG_BENCH_CASE_ID"))
    result.setdefault("caseFingerprint", env("YGG_BENCH_CASE_FINGERPRINT"))
    result.setdefault("agentId", env("YGG_BENCH_AGENT_ID", "deepseek-v4-pro-agent"))
    result.setdefault("mode", env("YGG_BENCH_MODE", "ygg"))
    result.setdefault("selection", selection_from_packet())
    result.setdefault("parserWorker", parser_worker_profile())
    result.setdefault("suspectedFiles", [])
    result.setdefault("suspectedSymbols", [])
    result.setdefault("commands", commands)
    result.setdefault("warnings", warnings)
    result.setdefault("summary", "DeepSeek v4-pro agent localization result.")
    if env("YGG_BENCH_AGENT_INPUT_FINGERPRINT"):
        result.setdefault("agentInputFingerprint", env("YGG_BENCH_AGENT_INPUT_FINGERPRINT"))
    result["tokenUsage"] = {
        "inputTokens": token_usage.get("input_tokens", 0),
        "outputTokens": token_usage.get("output_tokens", 0),
        "totalTokens": token_usage.get("input_tokens", 0) + token_usage.get("output_tokens", 0),
        "costUsd": 0.0,
        "source": "deepseek-agent",
    }
    return result


def write_result_file(result: dict) -> None:
    result_path = env("YGG_BENCH_RESULT")
    if not result_path:
        raise SystemExit("YGG_BENCH_RESULT is required.")
    path = Path(result_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def extract_text(content: list[dict]) -> str:
    parts = []
    for block in content:
        if block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "\n".join(parts)


def main(argv: list[str]) -> int:
    user_prompt = read_prompt(argv)
    if not user_prompt.strip():
        print("usage: deepseek-agent.py [prompt-text]", file=sys.stderr)
        print("Error: empty prompt.", file=sys.stderr)
        return 2

    worktree = env("YGG_BENCH_WORKTREE", os.getcwd())
    api_key = "mock" if env("DEEPSEEK_MOCK_RESPONSE") else load_api_key()
    endpoint = env("DEEPSEEK_ENDPOINT", DEFAULT_ENDPOINT)
    model = env("DEEPSEEK_MODEL", DEFAULT_MODEL)
    max_tokens = int(env("DEEPSEEK_MAX_OUTPUT_TOKENS", str(DEFAULT_MAX_OUTPUT_TOKENS)))
    max_iterations = int(env("DEEPSEEK_MAX_ITERATIONS", str(DEFAULT_MAX_ITERATIONS)))

    system_prompt = build_system_prompt(user_prompt)
    messages: list[dict] = [
        {"role": "user", "content": [{"type": "text", "text": user_prompt}]}
    ]
    commands_used: list[str] = []
    warnings: list[str] = []
    total_input_tokens = 0
    total_output_tokens = 0

    for iteration in range(max_iterations):
        try:
            response = call_api(
                api_key, endpoint, model, max_tokens, messages, system_prompt
            )
        except Exception as e:
            warnings.append(f"API call failed: {e}")
            result = build_result(
                [], commands_used, f"Agent failed: {e}", warnings,
                {"input_tokens": total_input_tokens, "output_tokens": total_output_tokens},
            )
            write_result_file(result)
            print(f"Error: {e}", file=sys.stderr)
            return 1

        usage = response.get("usage", {})
        total_input_tokens += usage.get("input_tokens", 0)
        total_output_tokens += usage.get("output_tokens", 0)

        content = response.get("content", [])
        stop_reason = response.get("stop_reason", "")

        assistant_message = {"role": "assistant", "content": content}
        messages.append(assistant_message)

        tool_uses = [b for b in content if b.get("type") == "tool_use"]
        has_write_result = any(
            b.get("type") == "tool_use" and b.get("name") == "write_result"
            for b in content
        )

        if has_write_result:
            for block in tool_uses:
                if block.get("name") == "write_result":
                    tool_input = block.get("input", {})
                    result_obj = tool_input.get("result")
                    if isinstance(result_obj, dict):
                        result_obj = normalize_result(
                            result_obj,
                            commands_used,
                            warnings,
                            {
                                "input_tokens": total_input_tokens,
                                "output_tokens": total_output_tokens,
                            },
                        )
                        write_result_file(result_obj)
                        print(
                            f"Agent completed in {iteration + 1} iterations. "
                            f"Tokens: {total_input_tokens} in / {total_output_tokens} out.",
                            file=sys.stderr,
                        )
                        return 0
                    else:
                        warnings.append("write_result called without a valid result object.")

        if tool_uses and not has_write_result:
            tool_results = []
            for block in tool_uses:
                tool_name = block.get("name", "")
                tool_input = block.get("input", {})
                tool_use_id = block.get("id", "")

                if tool_name == "run_command":
                    commands_used.append(tool_input.get("command", ""))

                tool_output = execute_tool(tool_name, tool_input, worktree)

                if tool_output == "__WRITE_RESULT__":
                    continue

                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": tool_output,
                })

            if tool_results:
                messages.append({"role": "user", "content": tool_results})
            continue

        if stop_reason in ("end_turn", "max_tokens", "stop_sequence"):
            text = extract_text(content)
            parsed = try_parse_result_json(text)
            if parsed is not None:
                parsed = normalize_result(
                    parsed,
                    commands_used,
                    warnings,
                    {"input_tokens": total_input_tokens, "output_tokens": total_output_tokens},
                )
                write_result_file(parsed)
                print(
                    f"Agent completed in {iteration + 1} iterations. "
                    f"Tokens: {total_input_tokens} in / {total_output_tokens} out.",
                    file=sys.stderr,
                )
                return 0
            elif text.strip():
                warnings.append("Agent returned text but no parseable result JSON.")
                fallback = build_result(
                    [], commands_used,
                    "Agent did not produce a structured result.",
                    warnings,
                    {"input_tokens": total_input_tokens, "output_tokens": total_output_tokens},
                )
                write_result_file(fallback)
                print(
                    f"Agent ended without structured result after {iteration + 1} iterations.",
                    file=sys.stderr,
                )
                return 1

    warnings.append(f"Agent exceeded max iterations ({max_iterations}).")
    fallback = build_result(
        [], commands_used,
        f"Agent exceeded max iterations ({max_iterations}).",
        warnings,
        {"input_tokens": total_input_tokens, "output_tokens": total_output_tokens},
    )
    write_result_file(fallback)
    print(
        f"Agent exceeded max iterations ({max_iterations}).",
        file=sys.stderr,
    )
    return 1


def try_parse_result_json(text: str) -> dict | None:
    text = text.strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                fragment = text[start : i + 1]
                try:
                    return json.loads(fragment)
                except json.JSONDecodeError:
                    return None
    return None


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
