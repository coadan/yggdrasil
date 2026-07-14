#!/usr/bin/env python3
import json
import os
import pathlib
import platform
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time


UNAVAILABLE = 75
DEFAULT_SERVER_HOST = "127.0.0.1"
DEFAULT_SERVER_PORT = 62121
DEFAULT_CONNECT_TIMEOUT_MS = 30000
CONNECT_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_STARTING_RETRY_TIMEOUT_MS = 30000
STARTING_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_REQUEST_TIMEOUT_MS = 600000
DEFAULT_BENCH_AGENT_BASELINE_REQUEST_TIMEOUT_MS = 3600000
DEFAULT_QUERY_FALLBACK_AFTER_MS = 1500
DEFAULT_EXPANDED_QUERY_FALLBACK_AFTER_MS = 5000
DEFAULT_QUERY_AUTO_START_COOLDOWN_SECONDS = 15
SERVER_FRAME_SCHEMA = "ygg.server.frame/v1"
FILESYSTEM_QUERY_SCHEMA = "ygg.query/v2"
QUERY_DEGRADATION_SCHEMA = "ygg.context.degradation/v1"
DEFAULT_FILESYSTEM_QUERY_TIMEOUT_MS = 1500
DEFAULT_FILESYSTEM_QUERY_MAX_STDOUT_BYTES = 200000
DEFAULT_FILESYSTEM_QUERY_LIMIT = 10
DEFAULT_FILESYSTEM_QUERY_PATTERN_LIMIT = 6
ROOT = pathlib.Path(__file__).resolve().parents[1]
YGG_BIN = ROOT / "bin" / "ygg"
INIT_LOCAL_FLAGS = {
    "--no-start-server",
    "--start-at-login",
    "--no-start-at-login",
    "--no-input",
    "--non-interactive",
    "--yes",
}
INIT_VALUE_OPTIONS = {
    "--project",
    "--name",
    "--out",
    "--harness",
    "--maintenance",
    "--maintenance-model",
    "--maintenance-reasoning",
    "--maintenance-command",
    "--workbench",
    "--task",
}
INIT_BOOLEAN_OPTIONS = {
    "--force",
    "--sync",
    "--hooks",
    "--skill",
    "--mcp",
    "--force-agent",
}
CONFIG_PATH_POSITIONAL_OPS = {
    "affected",
    "audit-scope",
    "hook",
    "maintenance",
    "plugin",
    "projects",
    "report",
    "status",
    "sync",
    "watch",
}
QUERY_VALUE_OPTIONS = {
    "--anchor",
    "--budget",
    "--config",
    "--doc-limit",
    "--edge-limit",
    "--entity-limit",
    "--fts-weight",
    "--known-at",
    "--lanes",
    "--limit",
    "--literal",
    "--min-confidence",
    "--model",
    "--output",
    "--project",
    "--provider",
    "--repo",
    "--retriever",
    "--since",
    "--snapshot-token",
    "--snippet-chars",
    "--symbol",
    "--task",
    "--valid-at",
}
QUERY_BOOLEAN_OPTIONS = {
    "--changed-only",
    "--json",
    "--no-progress",
    "--proof-commands",
}
FILESYSTEM_QUERY_IGNORED_DIRECTORIES = [
    ".git",
    ".dev",
    ".ygg",
    ".cpcache",
    ".clj-kondo",
    "target",
    "node_modules",
    ".shadow-cljs",
    ".calva",
    ".idea",
    "ygg-out",
]

def server_host():
    return os.environ.get("YGG_SERVER_HOST", DEFAULT_SERVER_HOST).strip() or DEFAULT_SERVER_HOST


def server_port():
    raw = os.environ.get("YGG_SERVER_PORT")
    if not raw:
        return DEFAULT_SERVER_PORT
    try:
        port = int(raw)
    except ValueError:
        sys.stderr.write("Invalid YGG_SERVER_PORT: expected an integer.\n")
        raise SystemExit(2)
    if port < 1 or port > 65535:
        sys.stderr.write("Invalid YGG_SERVER_PORT: expected a port from 1 to 65535.\n")
        raise SystemExit(2)
    return port


def server_token():
    token = os.environ.get("YGG_SERVER_TOKEN")
    if not token:
        return None
    token = token.strip()
    return token or None


def storage_path():
    path = os.environ.get("YGG_XTDB_PATH")
    if not path:
        return None
    if os.path.isabs(path):
        return path
    return os.path.abspath(path)


def env_int(name, default):
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def env_bool(name, default):
    raw = os.environ.get(name)
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    return default


def default_request_timeout_ms(op=None, args=None):
    args = args or []
    if op == "bench" and args[:1] == ["agent-baseline"]:
        return DEFAULT_BENCH_AGENT_BASELINE_REQUEST_TIMEOUT_MS
    return DEFAULT_REQUEST_TIMEOUT_MS


def request_timeout_seconds(op=None, args=None):
    timeout_ms = env_int(
        "YGG_SERVER_REQUEST_TIMEOUT_MS",
        default_request_timeout_ms(op, args),
    )
    if timeout_ms <= 0:
        return None
    return timeout_ms / 1000.0


def query_fallback_after_ms(args=None):
    args = args or []
    output = option_value(args, "--output") or "compact"
    default = (
        DEFAULT_EXPANDED_QUERY_FALLBACK_AFTER_MS
        if output in {"evidence", "full"}
        else DEFAULT_QUERY_FALLBACK_AFTER_MS
    )
    return max(env_int("YGG_QUERY_FALLBACK_AFTER_MS", default), 0)


def response_timeout_seconds(op=None, args=None):
    request_timeout = request_timeout_seconds(op, args)
    if op != "query":
        return request_timeout
    fallback_after_ms = query_fallback_after_ms(args)
    if fallback_after_ms <= 0:
        return request_timeout
    fallback_timeout = fallback_after_ms / 1000.0
    if request_timeout is None:
        return fallback_timeout
    return min(request_timeout, fallback_timeout)


def connect_timeout_ms():
    return max(env_int("YGG_SERVER_CONNECT_TIMEOUT_MS", DEFAULT_CONNECT_TIMEOUT_MS), 0)


def starting_retry_timeout_ms():
    return max(
        env_int("YGG_SERVER_STARTING_RETRY_TIMEOUT_MS",
                DEFAULT_STARTING_RETRY_TIMEOUT_MS),
        0,
    )


def connect_socket(host, port, timeout_ms=None):
    timeout_ms = connect_timeout_ms() if timeout_ms is None else max(timeout_ms, 0)
    deadline = time.monotonic() + (timeout_ms / 1000.0)
    while True:
        try:
            return socket.create_connection((host, port), timeout=0.25)
        except OSError:
            if timeout_ms <= 0 or time.monotonic() >= deadline:
                return None
            sleep_seconds = min(
                CONNECT_RETRY_INTERVAL_SECONDS,
                max(0.0, deadline - time.monotonic()),
            )
            if sleep_seconds > 0:
                time.sleep(sleep_seconds)


def option_value(args, flag):
    try:
        idx = args.index(flag)
    except ValueError:
        return None
    if idx + 1 >= len(args):
        return None
    return args[idx + 1]


def has_flag(args, flag):
    return flag in args


def has_any(args, flags):
    return any(flag in args for flag in flags)


def option_values(args, flag):
    return [
        args[idx + 1]
        for idx, arg in enumerate(args[:-1])
        if arg == flag
    ]


def query_positional_args(args):
    values = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in QUERY_VALUE_OPTIONS:
            index += 2
            continue
        if arg in QUERY_BOOLEAN_OPTIONS:
            index += 1
            continue
        if arg.startswith("--"):
            index += 1
            continue
        values.append(arg)
        index += 1
    return values


def query_input_options(args):
    value = {
        "task": option_value(args, "--task") or "auto",
        "anchors": option_values(args, "--anchor"),
        "symbols": option_values(args, "--symbol"),
        "literals": option_values(args, "--literal"),
        "changed-only?": has_flag(args, "--changed-only"),
    }
    lanes = option_value(args, "--lanes")
    if lanes:
        value["lanes"] = [lane.strip() for lane in lanes.split(",") if lane.strip()]
    since = option_value(args, "--since")
    if since:
        value["since"] = since
    return value


def alnum_count(value):
    return len(re.findall(r"[A-Za-z0-9]", str(value)))


def shaped_pattern(value):
    return re.search(r"[._/:-]", str(value)) is not None


def filesystem_query_patterns(query_text, args):
    explicit = [
        value.strip()
        for value in [
            *option_values(args, "--literal"),
            *option_values(args, "--symbol"),
        ]
        if value and value.strip()
    ]
    tokens = []
    for index, value in enumerate(
        re.split(r"[^A-Za-z0-9_./:+?!<>=-]+", query_text)
    ):
        value = value.strip()
        if value and alnum_count(value) >= 3:
            tokens.append((index, value))
    tokens.sort(key=lambda row: (
        0 if shaped_pattern(row[1]) else 1,
        -alnum_count(row[1]),
        row[0],
        row[1].lower(),
    ))
    candidates = [*explicit, *(value for _, value in tokens)]
    patterns = []
    seen = set()
    for pattern in candidates:
        key = pattern.lower()
        if key in seen:
            continue
        seen.add(key)
        patterns.append(pattern)
        if len(patterns) >= DEFAULT_FILESYSTEM_QUERY_PATTERN_LIMIT:
            break
    return patterns or [query_text.strip()]


def filesystem_query_root(cwd=None):
    cwd = pathlib.Path(cwd or os.getcwd()).expanduser().resolve()
    for directory in parent_dirs(cwd):
        if (directory / ".git").exists():
            return directory
        if (directory / ".ygg" / "project.edn").is_file():
            return directory
    return cwd


def filesystem_query_timeout_ms():
    return max(
        env_int("YGG_FILESYSTEM_QUERY_TIMEOUT_MS", DEFAULT_FILESYSTEM_QUERY_TIMEOUT_MS),
        0,
    )


def filesystem_query_max_stdout_bytes():
    return max(
        env_int(
            "YGG_FILESYSTEM_QUERY_MAX_STDOUT_BYTES",
            DEFAULT_FILESYSTEM_QUERY_MAX_STDOUT_BYTES,
        ),
        0,
    )


def filesystem_query_limit(args):
    raw = option_value(args, "--limit")
    try:
        return max(1, int(raw)) if raw else DEFAULT_FILESYSTEM_QUERY_LIMIT
    except ValueError:
        return DEFAULT_FILESYSTEM_QUERY_LIMIT


def filesystem_query_argv(patterns):
    argv = [
        os.environ.get("YGG_RG_BIN") or "rg",
        "--count-matches",
        "-H",
        "--fixed-strings",
        "--hidden",
        "--ignore-case",
    ]
    for directory in FILESYSTEM_QUERY_IGNORED_DIRECTORIES:
        argv.extend(["--glob", f"!{directory}/**"])
        argv.extend(["--glob", f"!**/{directory}/**"])
    for pattern in patterns:
        argv.extend(["-e", pattern])
    argv.extend(["--", "."])
    return argv


def bounded_temp_text(file, max_bytes):
    file.seek(0, os.SEEK_END)
    size = file.tell()
    file.seek(0)
    data = file.read(max_bytes + 1)
    truncated = size > max_bytes
    if truncated:
        data = data[:max_bytes]
        newline = data.rfind(b"\n")
        data = data[:newline + 1] if newline >= 0 else b""
    return data.decode("utf-8", errors="replace"), truncated


def wait_process_bounded(process, timeout_ms):
    if timeout_ms <= 0:
        process.wait()
        return False
    timed_out = threading.Event()

    def expire():
        if process.poll() is None:
            timed_out.set()
            process.kill()

    timer = threading.Timer(timeout_ms / 1000.0, expire)
    timer.daemon = True
    timer.start()
    try:
        process.wait()
    finally:
        timer.cancel()
    return timed_out.is_set()


def parse_filesystem_count_output(stdout):
    matches = []
    for line in stdout.splitlines():
        path, separator, raw_count = line.rpartition(":")
        if not separator or not raw_count.isdigit():
            continue
        path = path.removeprefix("./").replace(os.sep, "/")
        matches.append({"path": path, "count": int(raw_count)})
    return matches


def run_filesystem_search(root, patterns):
    started = time.monotonic()
    timeout_ms = filesystem_query_timeout_ms()
    max_stdout_bytes = filesystem_query_max_stdout_bytes()
    diagnostics = []
    with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
        try:
            process = subprocess.Popen(
                filesystem_query_argv(patterns),
                cwd=str(root),
                stdin=subprocess.DEVNULL,
                stdout=stdout_file,
                stderr=stderr_file,
            )
        except OSError as exc:
            return {
                "elapsed-ms": int((time.monotonic() - started) * 1000),
                "matches": [],
                "diagnostics": [{"kind": "unavailable", "message": str(exc)}],
                "timeout?": False,
                "truncated?": False,
            }
        timed_out = wait_process_bounded(process, timeout_ms)
        stdout, truncated = bounded_temp_text(stdout_file, max_stdout_bytes)
        stderr, stderr_truncated = bounded_temp_text(stderr_file, 20000)
        if timed_out:
            diagnostics.append({"kind": "timeout"})
        if truncated:
            diagnostics.append({"kind": "stdout-truncated"})
        if stderr_truncated:
            diagnostics.append({"kind": "stderr-truncated"})
        if process.returncode not in {0, 1} and not timed_out:
            diagnostics.append({
                "kind": "ripgrep-error",
                "message": stderr.strip(),
            })
        elif stderr.strip():
            diagnostics.append({"kind": "stderr", "message": stderr.strip()})
        return {
            "elapsed-ms": int((time.monotonic() - started) * 1000),
            "matches": parse_filesystem_count_output(stdout),
            "diagnostics": diagnostics,
            "timeout?": timed_out,
            "truncated?": truncated,
        }


def degradation_message(reason):
    if reason == "server-starting":
        return (
            "Yggdrasil enrichment is starting; search is using bounded filesystem evidence. "
            "Graph-enriched results will become available automatically."
        )
    if reason == "query-timeout":
        return (
            "Yggdrasil enrichment did not respond within the query latency bound; "
            "search is using bounded filesystem evidence. The enriched query continues "
            "in the local service for later requests."
        )
    if reason == "storage-unavailable":
        return (
            "Yggdrasil graph storage is unavailable; search is using bounded "
            "filesystem evidence. Graph-enriched results will resume when storage "
            "becomes available."
        )
    return (
        "Yggdrasil enrichment is unavailable; search is using bounded filesystem evidence. "
        "Graph-enriched results will become available automatically."
    )


def filesystem_query_packet(args, reason):
    started = time.monotonic()
    query_text = " ".join(query_positional_args(args)).strip()
    root = filesystem_query_root()
    patterns = filesystem_query_patterns(query_text, args)
    search = run_filesystem_search(root, patterns)
    def path_pattern_count(row):
        path = row["path"].lower()
        return sum(1 for pattern in patterns if pattern.lower() in path)
    matches = sorted(
        search["matches"],
        key=lambda row: (
            -path_pattern_count(row),
            -row["count"],
            len(row["path"]),
            row["path"],
        ),
    )[:filesystem_query_limit(args)]
    max_count = max([row["count"] for row in matches] or [1])
    rank_scores = [
        path_pattern_count(row) + (row["count"] / max_count)
        for row in matches
    ]
    max_rank_score = max(rank_scores or [1.0])
    paths = {}
    results = []
    for index, (match, rank_score) in enumerate(zip(matches, rank_scores), start=1):
        path_id = f"p{index}"
        paths[path_id] = match["path"]
        results.append({
            "path": path_id,
            "resolvedPath": match["path"],
            "repo": option_value(args, "--repo") or root.name,
            "rank": index,
            "score": rank_score / max_rank_score,
            "kind": "file",
            "why": ["grep"],
            "reason": (
                f"filesystem fixed-string match ({match['count']} "
                f"{'match' if match['count'] == 1 else 'matches'})"
            ),
        })
    diagnostic_counts = {}
    for diagnostic in search["diagnostics"]:
        kind = diagnostic.get("kind")
        if kind:
            diagnostic_counts[kind] = diagnostic_counts.get(kind, 0) + 1
    message = degradation_message(reason)
    requested = option_value(args, "--retriever") or "auto"
    packet = {
        "schema": FILESYSTEM_QUERY_SCHEMA,
        "query": query_text,
        "input": query_input_options(args),
        "basis": {"status": "limited", "degraded": True},
        "paths": paths,
        "lanes": {
            "requested": requested,
            "mode": "filesystem",
            "used": ["grep"] if results else [],
        },
        "retrieval": {
            "requested": requested,
            "effective": "filesystem",
            "fallback?": True,
            "reason": reason,
        },
        "results": results,
        "evidence": [
            {
                "kind": "candidate",
                "path": result["path"],
                "resolvedPath": result["resolvedPath"],
                "repo": result["repo"],
                "why": ["grep"],
            }
            for result in results
        ],
        "warnings": [message],
        "degradation": {
            "schema": QUERY_DEGRADATION_SCHEMA,
            "status": "degraded",
            "reason": reason,
            "fallback": "filesystem",
            "message": message,
        },
        "search": {
            "instrumentation": {
                "filesystem-processes": 1,
                "filesystem-search-ms": search["elapsed-ms"],
                "filesystem-total-ms": int((time.monotonic() - started) * 1000),
                "filesystem-patterns": patterns,
                "filesystem-match-count": sum(row["count"] for row in search["matches"]),
                "filesystem-file-count": len(search["matches"]),
                "filesystem-returned-count": len(results),
                "filesystem-diagnostic-kinds": diagnostic_counts,
                "filesystem-timeout-ms": filesystem_query_timeout_ms(),
            }
        },
    }
    return packet


def filesystem_query_response(args, reason):
    query_text = " ".join(query_positional_args(args)).strip()
    if not query_text:
        return {"exit": 2, "out": "", "err": "Missing query text.\n"}
    packet = filesystem_query_packet(args, reason)
    if has_flag(args, "--json"):
        return {"exit": 0, "out": json.dumps(packet, indent=2) + "\n", "err": ""}
    lines = []
    for result in packet["results"]:
        lines.extend([
            f"{result['score']:.2f}  file  {result['resolvedPath']}",
            f"       {result['resolvedPath']}",
            f"        {result['reason']}",
        ])
    if not lines:
        lines.append("No filesystem query results.")
    return {
        "exit": 0,
        "out": "\n".join(lines) + "\n",
        "err": f"Warning: {packet['degradation']['message']}\n",
    }


def parent_dirs(path):
    current = pathlib.Path(path).expanduser().resolve()
    while True:
        yield current
        if current.parent == current:
            return
        current = current.parent


def display_path(path):
    try:
        absolute = pathlib.Path(path).expanduser().resolve()
    except OSError:
        absolute = pathlib.Path(path).expanduser().absolute()
    try:
        cwd = pathlib.Path.cwd().resolve()
        relative = os.path.relpath(str(absolute), str(cwd))
        if relative != ".." and not relative.startswith("../"):
            return relative
    except OSError:
        pass
    return str(absolute)


def project_registry_path():
    configured = os.environ.get("YGG_PROJECTS_FILE")
    if configured:
        return pathlib.Path(configured).expanduser()
    config_home = os.environ.get("YGG_CONFIG_HOME")
    if config_home:
        return pathlib.Path(config_home).expanduser() / "projects.edn"
    xdg_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_home:
        return pathlib.Path(xdg_home).expanduser() / "ygg" / "projects.edn"
    return pathlib.Path.home() / ".config" / "ygg" / "projects.edn"


def nearest_project_ref_path(cwd=None):
    for directory in parent_dirs(cwd or os.getcwd()):
        path = directory / ".ygg" / "project.edn"
        if path.is_file():
            return path
    return None


def cwd_project_config_path(cwd=None):
    path = pathlib.Path(cwd or os.getcwd()) / "project.edn"
    return path if path.is_file() else None


def looks_like_config_path(value):
    if not value or value.startswith("-"):
        return False
    path = pathlib.Path(value)
    return path.suffix == ".edn" or path.name == "project.edn" or path.is_file()


def first_config_path_arg(op, args):
    explicit = option_value(args, "--config")
    if explicit:
        return explicit
    if op not in CONFIG_PATH_POSITIONAL_OPS:
        return None
    skip_next = False
    for arg in args:
        if skip_next:
            skip_next = False
            continue
        if arg in INIT_VALUE_OPTIONS or arg in {"--config", "--project", "--repo", "--out"}:
            skip_next = True
            continue
        if arg.startswith("--"):
            continue
        if looks_like_config_path(arg):
            return arg
    return None


def server_endpoint():
    return f"{server_host()}:{server_port()}"


def retry_phrase(op):
    if op:
        return f"`ygg {op}`"
    return "the command"


def unavailable_hint(op=None, args=None):
    args = list(args or [])
    if op == "init":
        if "--no-start-server" in args:
            return (
                "Server startup was disabled by `--no-start-server`. "
                "Run `ygg start`, then retry `ygg init`."
            )
        return (
            "The client could not start or contact the service for `ygg init`. "
            f"Check `{display_path(server_log_path())}`, run `ygg start`, then retry."
        )

    project_id = option_value(args, "--project")
    if project_id:
        return (
            f"Project `{project_id}` was selected via `--project`. "
            f"Run `ygg start`, then retry {retry_phrase(op)}."
        )
    env_project_id = os.environ.get("YGG_PROJECT_ID")
    if env_project_id:
        return (
            f"Project `{env_project_id}` was selected via `YGG_PROJECT_ID`. "
            f"Run `ygg start`, then retry {retry_phrase(op)}."
        )

    config_path = first_config_path_arg(op, args)
    if config_path:
        file = pathlib.Path(config_path).expanduser()
        if file.is_file():
            return (
                f"Project config exists at `{display_path(file)}`. "
                f"Run `ygg start`, then retry {retry_phrase(op)}."
            )
        return (
            f"Project config `{config_path}` was requested but does not exist. "
            f"Create it with `ygg init <repo-root> --project <id> --out {config_path}`, "
            "or pass an existing config after `ygg start`."
        )

    project_ref = nearest_project_ref_path()
    if project_ref:
        return (
            f"Repo-local project reference exists at `{display_path(project_ref)}`. "
            f"Run `ygg start`, then retry {retry_phrase(op)}."
        )

    cwd_config = cwd_project_config_path()
    if cwd_config:
        return (
            f"Project config exists at `{display_path(cwd_config)}`. "
            f"Run `ygg start`, then retry {retry_phrase(op)}."
        )

    registry_path = project_registry_path()
    if registry_path.is_file():
        return (
            "No project config or repo-local `.ygg/project.edn` was found from "
            f"`{display_path(os.getcwd())}`. A user project registry exists at "
            f"`{display_path(registry_path)}`; run `ygg start`, then pass "
            "`--project <id>` or run inside an initialized repo."
        )

    return (
        "No project config or repo-local `.ygg/project.edn` was found from "
        f"`{display_path(os.getcwd())}`. Run "
        "`ygg init <repo-root> --project <id> --sync` to initialize this checkout, "
        "or run `ygg start` and pass `--project <id>` for an existing registered project."
    )


def unavailable_message(op=None, args=None):
    return (
        f"Yggdrasil server is not reachable at {server_endpoint()}.\n"
        f"{unavailable_hint(op, args)}\n"
    )


def init_positional_args(args):
    out = []
    skip = False
    for arg in args:
        if skip:
            skip = False
            continue
        if arg in INIT_VALUE_OPTIONS:
            skip = True
            continue
        if arg in INIT_BOOLEAN_OPTIONS or arg in INIT_LOCAL_FLAGS:
            continue
        if arg.startswith("--"):
            continue
        out.append(arg)
    return out


def insert_init_root(args, root):
    if not root:
        return args
    return [root, *args]


def progress_output(args):
    return "--json" not in args and "--no-progress" not in args


def query_progress_output(args):
    return "--no-progress" not in args


def render_progress_frame(frame, printed_header):
    message = frame.get("message")
    if not message:
        return printed_header
    if not printed_header:
        operation = str(frame.get("operation") or "operation").title()
        sys.stderr.write(f"# {operation} Progress\n")
        printed_header = True
    sys.stderr.write(f"- {message}\n")
    sys.stderr.flush()
    return printed_header


def read_server_response(response_file, render_progress=False):
    printed_header = False
    for line in response_file:
        if not line:
            continue
        response = json.loads(line)
        if response.get("schema") == SERVER_FRAME_SCHEMA:
            if response.get("type") == "progress" and render_progress:
                printed_header = render_progress_frame(response, printed_header)
            continue
        return response
    return None


def server_starting_response(response):
    if not isinstance(response, dict):
        return False
    data = response.get("data")
    values = [
        response.get("status"),
        response.get("reason"),
    ]
    if isinstance(data, dict):
        values.extend([data.get("status"), data.get("reason")])
    return any(value in {"starting", "server-starting"} for value in values)


def request_once(op, args, extra=None, stream=False, render_progress=False, connect_timeout_override_ms=None):
    payload = {
        "op": op,
        "args": args,
        "cwd": os.getcwd(),
    }
    token = server_token()
    if token:
        payload["token"] = token
    if extra:
        payload.update(extra)
    explicit_storage_path = storage_path()
    if explicit_storage_path:
        payload["storagePath"] = explicit_storage_path
    project_id = option_value(args, "--project") or os.environ.get("YGG_PROJECT_ID")
    if project_id:
        payload["projectId"] = project_id
    if stream:
        payload["stream"] = True
    host = server_host()
    port = server_port()
    timeout_seconds = response_timeout_seconds(op, args)
    start = time.monotonic()
    sock = connect_socket(host, port, connect_timeout_override_ms)
    if sock is None:
        return None
    try:
        with sock:
            sock.settimeout(timeout_seconds)
            request_line = json.dumps(payload, separators=(",", ":")) + "\n"
            sock.sendall(request_line.encode("utf-8"))
            response = read_server_response(
                sock.makefile("r", encoding="utf-8"),
                render_progress=render_progress,
            )
            if response is None:
                return {"exit": 1, "out": "", "err": "Empty server response.\n"}
            return response
    except socket.timeout:
        elapsed_ms = int((time.monotonic() - start) * 1000)
        timeout_ms = None if timeout_seconds is None else int(timeout_seconds * 1000)
        if op == "query":
            return {
                "exit": 124,
                "out": "",
                "err": "",
                "data": {
                    "reason": "query-timeout",
                    "elapsedMs": elapsed_ms,
                    "timeoutMs": timeout_ms,
                },
            }
        return {
            "exit": 124,
            "out": "",
            "err": (
                "Timed out waiting for Yggdrasil server response.\n"
                f"op={op}\n"
                f"args={json.dumps(args)}\n"
                f"elapsedMs={elapsed_ms}\n"
                f"timeoutMs={timeout_ms}\n"
            ),
        }
    except OSError:
        return None


def request(op, args, extra=None, stream=False, render_progress=False, connect_timeout_override_ms=None):
    if op == "query" and connect_timeout_override_ms is None:
        connect_timeout_override_ms = 0
    timeout_ms = starting_retry_timeout_ms()
    deadline = time.monotonic() + (timeout_ms / 1000.0)
    while True:
        response = request_once(
            op,
            args,
            extra=extra,
            stream=stream,
            render_progress=render_progress,
            connect_timeout_override_ms=connect_timeout_override_ms,
        )
        if op == "query" or not server_starting_response(response):
            return response
        if timeout_ms <= 0 or time.monotonic() >= deadline:
            return response
        sleep_seconds = min(
            STARTING_RETRY_INTERVAL_SECONDS,
            max(0.0, deadline - time.monotonic()),
        )
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)


def jsonrpc_error(message, request_message=None, code=-32000, data=None):
    request_id = None
    if isinstance(request_message, dict):
        request_id = request_message.get("id")
    error = {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": message,
        },
    }
    if data is not None:
        error["error"]["data"] = data
    return error


def write_json_line(value):
    sys.stdout.write(json.dumps(value, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def mcp_proxy(args):
    exit_code = 0
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            write_json_line(jsonrpc_error(
                "Invalid JSON-RPC request.",
                None,
                -32700,
                {"error": str(exc)},
            ))
            exit_code = 1
            continue
        response = request("mcp", args, extra={"message": message})
        if response is None:
            hint = unavailable_hint("mcp", args)
            sys.stderr.write(unavailable_message("mcp", args))
            write_json_line(jsonrpc_error(
                "Yggdrasil server is not reachable.",
                message,
                -32000,
                {"hint": hint},
            ))
            return UNAVAILABLE
        if not response.get("ok"):
            err = (response.get("err") or "Server MCP request failed.").strip()
            if err:
                sys.stderr.write(err + "\n")
            write_json_line(jsonrpc_error(
                err or "Server MCP request failed.",
                message,
                -32000,
                response.get("data"),
            ))
            exit_code = int(response.get("exit", 1) or 1)
            continue
        server_err = response.get("err") or ""
        if server_err:
            sys.stderr.write(server_err)
        result = response.get("data")
        if result is not None:
            write_json_line(result)
    return exit_code


def print_response(response):
    out = response.get("out") or ""
    err = response.get("err") or ""
    if out:
        sys.stdout.write(out)
    if err:
        sys.stderr.write(err)
    return int(response.get("exit", 1))


def server_request(op, args, stream=False, render_progress=False):
    response = request(op, args, stream=stream, render_progress=render_progress)
    response_reason = (
        response.get("data", {}).get("reason")
        if isinstance(response, dict) and isinstance(response.get("data"), dict)
        else None
    )
    if op == "query" and (
        response is None
        or server_starting_response(response)
        or response_reason in {"query-timeout", "storage-unavailable"}
    ):
        if response_reason in {"query-timeout", "storage-unavailable"}:
            reason = response_reason
        elif server_starting_response(response):
            reason = "server-starting"
        else:
            reason = "server-unavailable"
        exit_code = print_response(filesystem_query_response(args, reason))
        if reason == "server-unavailable":
            sys.stdout.flush()
            sys.stderr.flush()
            start_server_in_background()
        return exit_code
    if response is None:
        sys.stderr.write(unavailable_message(op, args))
        return UNAVAILABLE
    return print_response(response)


def ygg_home():
    return pathlib.Path(os.environ.get("YGG_HOME") or str(ROOT))


def server_log_path():
    configured = os.environ.get("YGG_SERVER_LOG")
    if configured:
        return pathlib.Path(configured).expanduser()
    return ygg_home() / ".ygg" / "server.log"


def server_start_marker_path():
    return server_log_path().with_name("server-start-requested")


def claim_server_start():
    marker = server_start_marker_path()
    marker.parent.mkdir(parents=True, exist_ok=True)
    now = time.time()
    try:
        age = now - marker.stat().st_mtime
    except FileNotFoundError:
        age = None
    if age is not None and age >= DEFAULT_QUERY_AUTO_START_COOLDOWN_SECONDS:
        try:
            marker.unlink()
        except FileNotFoundError:
            pass
    try:
        descriptor = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return False
    with os.fdopen(descriptor, "w", encoding="utf-8") as file:
        file.write(f"{os.getpid()} {int(now)}\n")
    return True


def start_server_in_background():
    if not env_bool("YGG_QUERY_AUTO_START", True):
        return {"status": "disabled"}
    if not claim_server_start():
        return {"status": "already-requested"}
    log_path = server_log_path()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(log_path, "ab", buffering=0)
    try:
        subprocess.Popen(
            [str(YGG_BIN), "start"],
            cwd=str(ROOT),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=os.environ.copy(),
            start_new_session=True,
        )
        return {"status": "requested", "log": str(log_path)}
    except OSError as exc:
        try:
            server_start_marker_path().unlink()
        except FileNotFoundError:
            pass
        return {"status": "error", "error": str(exc), "log": str(log_path)}
    finally:
        log.close()


def start_server_for_init():
    log_path = server_log_path()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(log_path, "ab", buffering=0)
    try:
        subprocess.Popen(
            [str(YGG_BIN), "start"],
            cwd=str(ROOT),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=os.environ.copy(),
            start_new_session=True,
        )
    finally:
        log.close()
    deadline = time.monotonic() + 60.0
    while time.monotonic() < deadline:
        response = request("ping", [], connect_timeout_override_ms=0)
        if response is not None and response.get("ok"):
            return {"status": "started", "log": str(log_path)}
        time.sleep(1)
    return {"status": "timeout", "log": str(log_path)}


def init_interactive_enabled(args):
    if has_any(args, {"--no-input", "--non-interactive"}):
        return False
    raw = os.environ.get("YGG_INIT_INTERACTIVE", "").strip().lower()
    if raw in {"1", "true", "yes"}:
        return True
    if raw in {"0", "false", "no"}:
        return False
    if has_flag(args, "--yes"):
        return False
    return sys.stdin.isatty()


def prompt_input(prompt):
    sys.stderr.write(prompt)
    sys.stderr.flush()
    line = sys.stdin.readline()
    if line == "":
        return ""
    return line.strip()


def prompt_yes_no(prompt, default=True):
    suffix = " [Y/n] " if default else " [y/N] "
    while True:
        value = prompt_input(prompt + suffix).lower()
        if not value:
            return default
        if value in {"y", "yes"}:
            return True
        if value in {"n", "no"}:
            return False
        sys.stderr.write("Please answer yes or no.\n")


def prompt_choice(prompt, choices, default):
    normalized = {choice: choice for choice in choices}
    while True:
        value = prompt_input(f"{prompt} ({default}): ").lower()
        if not value:
            return default
        if value in normalized:
            return normalized[value]
        sys.stderr.write(f"Choose one of: {', '.join(choices)}.\n")


def detect_init_harnesses(root):
    root_path = pathlib.Path(root or os.getcwd()).expanduser()
    markers = []
    if (root_path / "AGENTS.md").exists():
        markers.append("AGENTS.md")
    if (root_path / ".codex").exists():
        markers.append(".codex")
    if markers:
        return [{"id": "codex", "reason": "project-marker", "markers": markers}]
    return []


def init_root_for_detection(args):
    if option_value(args, "--workbench"):
        return option_value(args, "--workbench")
    positionals = init_positional_args(args)
    return positionals[0] if positionals else os.getcwd()


def guided_project_root_args(args, yes):
    if option_value(args, "--workbench") or init_positional_args(args):
        return args, None
    if yes:
        return insert_init_root(args, "."), {"kind": "project-root", "value": os.getcwd(), "source": "default-current-directory"}
    sys.stderr.write(
        "Yggdrasil projects can include multiple repos. "
        "Add more later with `ygg sync add-repo project.edn <repo-path>`.\n"
    )
    value = prompt_input("Use the current directory as the first repo? [Y/n/path] ").strip()
    if not value or value.lower() in {"y", "yes"}:
        return insert_init_root(args, "."), {"kind": "project-root", "value": os.getcwd(), "source": "current-directory"}
    if value.lower() in {"n", "no"}:
        path = prompt_input("First repo path: ").strip()
        if path:
            return insert_init_root(args, path), {"kind": "project-root", "value": path, "source": "entered-path"}
        return insert_init_root(args, "."), {"kind": "project-root", "value": os.getcwd(), "source": "empty-path-current-directory"}
    return insert_init_root(args, value), {"kind": "project-root", "value": value, "source": "inline-path"}


def guided_harness_args(args, yes):
    harness = option_value(args, "--harness")
    artifact_flags = {"--hooks", "--skill", "--mcp"}
    if harness or has_any(args, artifact_flags):
        if harness == "codex" and not has_any(args, artifact_flags):
            install = yes or prompt_yes_no(
                "Install Codex guidance, the Ygg skill, and MCP command output?",
                True,
            )
            if install:
                return [*args, "--hooks", "--skill", "--mcp"], {
                    "kind": "harness",
                    "value": "codex",
                    "artifacts": ["hooks", "skill", "mcp"],
                }
        return args, None
    detected = detect_init_harnesses(init_root_for_detection(args))
    default = "codex" if detected else "none"
    if yes:
        choice = default
    else:
        if detected:
            sys.stderr.write("Detected assistant harness: codex.\n")
        sys.stderr.write("Harness setup can write AGENTS.md, .codex hooks, a reusable Ygg skill, and MCP command info.\n")
        choice = prompt_choice("Install assistant harness integration? choices: codex, none", ["codex", "none"], default)
    if choice == "codex":
        return [*args, "--harness", "codex", "--hooks", "--skill", "--mcp"], {
            "kind": "harness",
            "value": "codex",
            "detected": detected,
            "artifacts": ["hooks", "skill", "mcp"],
        }
    return [*args, "--harness", "none"], {
        "kind": "harness",
        "value": "none",
        "detected": detected,
    }


def guided_startup_args(args, yes):
    if has_any(args, {"--start-at-login", "--no-start-at-login"}):
        return args, None
    enable = yes or prompt_yes_no(
        "Start the Yggdrasil service when you log in so queries are warm?",
        True,
    )
    if enable:
        return [*args, "--start-at-login"], {"kind": "start-at-login", "value": True}
    return [*args, "--no-start-at-login"], {"kind": "start-at-login", "value": False}


def guided_maintenance_args(args, yes):
    if option_value(args, "--maintenance"):
        return args, None
    if yes:
        choice = "none"
    else:
        sys.stderr.write(
            "Auto maintenance can use your assistant harness or a DeepSeek/OpenRouter-compatible API model.\n"
        )
        choice = prompt_choice(
            "Auto maintenance executor? choices: none, harness, deepseek, openrouter",
            ["none", "harness", "deepseek", "openrouter"],
            "none",
        )
    if choice == "none":
        return [*args, "--maintenance", "none"], {"kind": "maintenance", "value": "none"}
    return [*args, "--maintenance", choice], {"kind": "maintenance", "value": choice}


def guided_sync_args(args, yes):
    if has_flag(args, "--sync"):
        return args, None
    sync = yes or prompt_yes_no("Index this project now?", True)
    if sync:
        return [*args, "--sync"], {"kind": "sync", "value": True}
    return args, {"kind": "sync", "value": False}


def guided_init_args(args):
    yes = has_flag(args, "--yes")
    if not (yes or init_interactive_enabled(args)):
        return args, None
    decisions = []
    guided = {
        "schema": "ygg.init.guided/v1",
        "mode": "defaults" if yes else "interactive",
    }
    for step in (guided_project_root_args,
                 guided_harness_args,
                 guided_startup_args,
                 guided_maintenance_args,
                 guided_sync_args):
        args, decision = step(args, yes)
        if decision:
            decisions.append(decision)
    guided["decisions"] = decisions
    return args, guided


def remove_flags(args, flags):
    out = []
    skip = False
    for idx, arg in enumerate(args):
        if skip:
            skip = False
            continue
        if arg in flags:
            continue
        out.append(arg)
    return out


def init_start_server_enabled(args):
    if "--no-start-server" in args:
        return False
    raw = os.environ.get("YGG_INIT_START_SERVER", "").strip().lower()
    return raw not in {"0", "false", "no"}


def init_server_request(args):
    args, guided = guided_init_args(list(args))
    local_flags = {
        "--no-start-server",
        "--start-at-login",
        "--no-start-at-login",
        "--no-input",
        "--non-interactive",
        "--yes",
    }
    server_args = remove_flags(args, local_flags)
    stream = has_flag(server_args, "--sync")
    response = request(
        "init",
        server_args,
        stream=stream,
        render_progress=progress_output(server_args),
        connect_timeout_override_ms=0,
    )
    service = None
    if response is None and init_start_server_enabled(args):
        service = start_server_for_init()
        if service.get("status") == "started":
            response = request(
                "init",
                server_args,
                stream=stream,
                render_progress=progress_output(server_args),
            )
    if response is None:
        sys.stderr.write(unavailable_message("init", args))
        return UNAVAILABLE
    if service:
        response = attach_init_service_result(response, service)
    if "--start-at-login" in args:
        startup = start_at_login(["enable", "--json"], emit=False)
        response = attach_init_startup_result(response, startup)
    if guided:
        response = attach_init_guided_result(response, guided)
    return print_response(response)


def attach_json_out(response, key, value):
    out = response.get("out") or ""
    try:
        parsed = json.loads(out)
    except json.JSONDecodeError:
        return response
    parsed[key] = value
    updated = dict(response)
    updated["out"] = json.dumps(parsed, indent=2) + "\n"
    return updated


def attach_init_service_result(response, service):
    return attach_json_out(response, "service", service)


def attach_init_startup_result(response, startup):
    return attach_json_out(response, "startup", startup)


def attach_init_guided_result(response, guided):
    return attach_json_out(response, "guided", guided)


def launch_agent_dir():
    return pathlib.Path.home() / "Library" / "LaunchAgents"


def launch_agent_path():
    return launch_agent_dir() / "com.yggdrasil.server.plist"


def launch_agent_plist():
    log_dir = ygg_home() / ".ygg"
    return f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
  <key>Label</key>
  <string>com.yggdrasil.server</string>
  <key>ProgramArguments</key>
  <array>
    <string>{YGG_BIN}</string>
    <string>start</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <false/>
  <key>WorkingDirectory</key>
  <string>{ROOT}</string>
  <key>StandardOutPath</key>
  <string>{log_dir / "launchd.out.log"}</string>
  <key>StandardErrorPath</key>
  <string>{log_dir / "launchd.err.log"}</string>
</dict>
</plist>
"""


def run_launchctl(*args):
    return subprocess.run(["launchctl", *args], text=True, capture_output=True, timeout=15)


def start_at_login(args, emit=True):
    action = args[0] if args else "status"
    json_output = "--json" in args
    if platform.system() != "Darwin":
        result = {
            "schema": "ygg.service.start-at-login/v1",
            "supported": False,
            "status": "unsupported",
            "reason": "start-at-login is currently implemented for macOS launchd",
        }
        if emit:
            write_local_result(result, json_output, exit_code=2)
        return result

    path = launch_agent_path()
    label = f"gui/{os.getuid()}/com.yggdrasil.server"
    result = {
        "schema": "ygg.service.start-at-login/v1",
        "supported": True,
        "path": str(path),
    }
    if action == "enable":
        path.parent.mkdir(parents=True, exist_ok=True)
        (ygg_home() / ".ygg").mkdir(parents=True, exist_ok=True)
        path.write_text(launch_agent_plist(), encoding="utf-8")
        bootstrap = run_launchctl("bootstrap", f"gui/{os.getuid()}", str(path))
        if bootstrap.returncode not in (0, 5):
            result.update({"status": "written", "launchctl": {
                "exit": bootstrap.returncode,
                "err": bootstrap.stderr.strip(),
            }})
        else:
            run_launchctl("enable", label)
            run_launchctl("kickstart", "-k", label)
            result["status"] = "enabled"
    elif action == "disable":
        run_launchctl("bootout", f"gui/{os.getuid()}", str(path))
        if path.exists():
            path.unlink()
        result["status"] = "disabled"
    elif action == "status":
        result["status"] = "configured" if path.exists() else "not-configured"
    else:
        sys.stderr.write("Usage: ygg service start-at-login enable|disable|status [--json]\n")
        raise SystemExit(2)
    if emit:
        write_local_result(result, json_output)
    return result


def write_local_result(result, json_output, exit_code=0):
    if json_output:
        sys.stdout.write(json.dumps(result, indent=2) + "\n")
    else:
        sys.stdout.write(f"{result.get('status')}\n")
        if result.get("path"):
            sys.stdout.write(f"- path {result['path']}\n")
        if result.get("reason"):
            sys.stdout.write(f"- reason {result['reason']}\n")
    raise SystemExit(exit_code)


def main(argv):
    if len(argv) < 2:
        return UNAVAILABLE
    command = argv[1]
    if command == "service" and len(argv) >= 3 and argv[2] == "start-at-login":
        start_at_login(argv[3:])
        return 0
    if command == "mcp":
        return mcp_proxy(argv[2:])
    if command == "init":
        return init_server_request(argv[2:])
    if command == "sync":
        args = argv[2:]
        return server_request("sync", args, stream=True, render_progress=progress_output(args))
    if command == "query":
        args = argv[2:]
        return server_request("query", args, stream=True, render_progress=query_progress_output(args))
    return server_request(command, argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
