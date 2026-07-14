#!/usr/bin/env python3
import _thread
import _socket as socket
import os
import select
import sys
import time


UNAVAILABLE = 75
DEFAULT_SERVER_HOST = "127.0.0.1"
DEFAULT_SERVER_PORT = 62121
DEFAULT_CONNECT_TIMEOUT_MS = 30000
DEFAULT_ZERO_RETRY_CONNECT_ATTEMPT_TIMEOUT_MS = 5
CONNECT_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_STARTING_RETRY_TIMEOUT_MS = 30000
STARTING_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_REQUEST_TIMEOUT_MS = 600000
DEFAULT_BENCH_AGENT_BASELINE_REQUEST_TIMEOUT_MS = 3600000
DEFAULT_QUERY_FALLBACK_AFTER_MS = 1500
DEFAULT_EXPANDED_QUERY_FALLBACK_AFTER_MS = 5000
DEFAULT_QUERY_HEDGE_AFTER_MS = 15
DEFAULT_EXPANDED_QUERY_HEDGE_AFTER_MS = 25
DEFAULT_ACKNOWLEDGED_QUERY_HEDGE_AFTER_MS = 15
DEFAULT_ACKNOWLEDGED_EXPANDED_QUERY_HEDGE_AFTER_MS = 25
DEFAULT_QUERY_AUTO_START_COOLDOWN_SECONDS = 15
SERVER_FRAME_SCHEMA = "ygg.server.frame/v1"
FILESYSTEM_QUERY_SCHEMA = "ygg.query/v2"
QUERY_DEGRADATION_SCHEMA = "ygg.context.degradation/v1"
FILESYSTEM_HANDOFF_SCHEMA = "ygg.query.filesystem-handoff/v1"
DEFAULT_FILESYSTEM_QUERY_TIMEOUT_MS = 1500
DEFAULT_FILESYSTEM_QUERY_MAX_STDOUT_BYTES = 200000
DEFAULT_FILESYSTEM_QUERY_LIMIT = 10
DEFAULT_FILESYSTEM_QUERY_PATTERN_LIMIT = 6
DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT = 4096
DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT = 1024
DEFAULT_FILESYSTEM_HANDOFF_REPO_LIMIT = 64
FILESYSTEM_PROCESS_LAUNCHER = "posix-spawn"
FILESYSTEM_INCOMPLETE_DIAGNOSTIC_KINDS = {
    "invalid-count-line",
    "ripgrep-error",
    "pattern-too-long",
    "query-truncated",
    "stdout-truncated",
    "timeout",
    "unmapped-path",
    "unavailable",
}
FILESYSTEM_INCOMPLETE_WARNING = (
    "Filesystem fallback reached a search bound or tool failure; "
    "results may be incomplete."
)
ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
YGG_BIN = os.path.join(ROOT, "bin", "ygg")
MCP_MANIFEST_PATH = os.path.join(ROOT, "resources", "ygg", "mcp-manifest.json")
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
FILESYSTEM_PATTERN_PUNCTUATION = frozenset("_./:+?!<>=-")
FILESYSTEM_SHAPED_PATTERN_PUNCTUATION = frozenset("._/:-")
_json_module = None
_json_import_lock = _thread.allocate_lock()


def standard_json():
    global _json_module
    if _json_module is None:
        with _json_import_lock:
            if _json_module is None:
                import json
                _json_module = json
    return _json_module


def prefetch_standard_json():
    if _json_module is None:
        _thread.start_new_thread(standard_json, ())


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


def query_hedge_after_ms(args=None):
    args = args or []
    output = option_value(args, "--output") or "compact"
    default = (
        DEFAULT_EXPANDED_QUERY_HEDGE_AFTER_MS
        if output in {"evidence", "full"}
        else DEFAULT_QUERY_HEDGE_AFTER_MS
    )
    return max(env_int("YGG_QUERY_HEDGE_AFTER_MS", default), 0)


def acknowledged_query_hedge_after_ms(args=None):
    args = args or []
    output = option_value(args, "--output") or "compact"
    default = (
        DEFAULT_ACKNOWLEDGED_EXPANDED_QUERY_HEDGE_AFTER_MS
        if output in {"evidence", "full"}
        else DEFAULT_ACKNOWLEDGED_QUERY_HEDGE_AFTER_MS
    )
    return max(
        query_hedge_after_ms(args),
        env_int("YGG_QUERY_ACKNOWLEDGED_HEDGE_AFTER_MS", default),
        0,
    )


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


def numeric_address_family(host):
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            socket.inet_pton(family, host)
            return family
        except OSError:
            continue
    return None


def connect_numeric_address(host, port, family, timeout_seconds):
    connection = socket.socket(family, socket.SOCK_STREAM)
    connection.settimeout(timeout_seconds)
    try:
        connection.connect((host, port))
        return connection
    except OSError:
        connection.close()
        raise


def connect_address(host, port, timeout_seconds):
    family = numeric_address_family(host)
    if family is not None:
        return connect_numeric_address(host, port, family, timeout_seconds)
    last_error = None
    for family, kind, protocol, _canonical_name, address in socket.getaddrinfo(
        host,
        port,
        type=socket.SOCK_STREAM,
    ):
        connection = socket.socket(family, kind, protocol)
        connection.settimeout(timeout_seconds)
        try:
            connection.connect(address)
            return connection
        except OSError as exc:
            last_error = exc
            connection.close()
    if last_error is not None:
        raise last_error
    raise OSError(f"No socket address found for {host}:{port}")


def connect_address_before_deadline(host, port, timeout_seconds):
    completion = _thread.allocate_lock()
    completion.acquire()
    state_lock = _thread.allocate_lock()
    state = {"cancelled": False}

    def connect():
        connection = None
        error = None
        try:
            connection = connect_address(host, port, timeout_seconds)
        except OSError as exc:
            error = exc
        with state_lock:
            if state["cancelled"]:
                if connection is not None:
                    connection.close()
            else:
                state["connection"] = connection
                state["error"] = error
        completion.release()

    _thread.start_new_thread(connect, ())
    if not completion.acquire(timeout=timeout_seconds):
        with state_lock:
            state["cancelled"] = True
            connection = state.pop("connection", None)
            if connection is not None:
                connection.close()
        return None
    with state_lock:
        if state.get("error") is not None:
            raise state["error"]
        return state.get("connection")


def connect_socket(host, port, timeout_ms=None):
    timeout_ms = connect_timeout_ms() if timeout_ms is None else max(timeout_ms, 0)
    deadline = time.monotonic() + (timeout_ms / 1000.0)
    numeric_family = numeric_address_family(host)
    first_attempt = True
    while True:
        remaining_seconds = max(0.0, deadline - time.monotonic())
        if not first_attempt and timeout_ms > 0 and remaining_seconds <= 0:
            return None
        attempt_timeout_seconds = (
            DEFAULT_ZERO_RETRY_CONNECT_ATTEMPT_TIMEOUT_MS / 1000.0
            if timeout_ms <= 0
            else max(0.001, min(0.25, remaining_seconds))
        )
        try:
            if numeric_family is not None:
                connection = connect_numeric_address(
                    host,
                    port,
                    numeric_family,
                    attempt_timeout_seconds,
                )
            elif timeout_ms > 0:
                connection = connect_address(host, port, attempt_timeout_seconds)
            else:
                connection = connect_address_before_deadline(
                    host,
                    port,
                    attempt_timeout_seconds,
                )
            if connection is not None:
                return connection
            raise TimeoutError()
        except OSError:
            first_attempt = False
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


def ascii_alnum(character):
    return (
        "A" <= character <= "Z"
        or "a" <= character <= "z"
        or "0" <= character <= "9"
    )


def alnum_count(value):
    return sum(1 for character in str(value) if ascii_alnum(character))


def shaped_pattern(value):
    return any(
        character in FILESYSTEM_SHAPED_PATTERN_PUNCTUATION
        for character in str(value)
    )


def filesystem_query_tokens(query_text):
    tokens = []
    token = []
    for character in query_text[:DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT]:
        if ascii_alnum(character) or character in FILESYSTEM_PATTERN_PUNCTUATION:
            token.append(character)
        elif token:
            tokens.append("".join(token))
            token = []
    if token:
        tokens.append("".join(token))
    return tokens


def filesystem_query_pattern_selection(query_text, args):
    scanned_query = query_text[:DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT]
    query_truncated = len(query_text) > len(scanned_query)
    query_cut_token = (
        query_truncated
        and bool(scanned_query)
        and (
            ascii_alnum(scanned_query[-1])
            or scanned_query[-1] in FILESYSTEM_PATTERN_PUNCTUATION
        )
        and (
            ascii_alnum(query_text[len(scanned_query)])
            or query_text[len(scanned_query)] in FILESYSTEM_PATTERN_PUNCTUATION
        )
    )
    candidate_query = scanned_query
    if query_cut_token:
        token_start = len(candidate_query)
        while token_start > 0 and (
            ascii_alnum(candidate_query[token_start - 1])
            or candidate_query[token_start - 1] in FILESYSTEM_PATTERN_PUNCTUATION
        ):
            token_start -= 1
        candidate_query = candidate_query[:token_start]
    explicit = [
        value.strip()
        for value in [
            *option_values(args, "--literal"),
            *option_values(args, "--symbol"),
        ]
        if value and value.strip()
    ]
    tokens = []
    dropped_pattern_count = 0
    query_tokens = filesystem_query_tokens(candidate_query)
    for index, value in enumerate(query_tokens):
        value = value.strip()
        if value and alnum_count(value) >= 3:
            if len(value) <= DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT:
                tokens.append((index, value))
            else:
                dropped_pattern_count += 1
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
        if len(pattern) > DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT:
            dropped_pattern_count += 1
            continue
        key = pattern.lower()
        if key in seen:
            continue
        seen.add(key)
        patterns.append(pattern)
        if len(patterns) >= DEFAULT_FILESYSTEM_QUERY_PATTERN_LIMIT:
            break
    if not patterns:
        fallback = scanned_query.strip()
        if len(fallback) > DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT:
            dropped_pattern_count += 1
            fallback = fallback[:DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT]
        patterns = [fallback]
    diagnostics = []
    if query_truncated:
        diagnostics.append({
            "kind": "query-truncated",
            "limitCharacters": DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT,
        })
    if dropped_pattern_count:
        diagnostics.append({
            "kind": "pattern-too-long",
            "count": dropped_pattern_count,
            "maxCharacters": DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT,
        })
    return {
        "query": scanned_query,
        "patterns": patterns,
        "diagnostics": diagnostics,
        "query-truncated?": query_truncated,
        "dropped-pattern-count": dropped_pattern_count,
    }


def filesystem_query_patterns(query_text, args):
    return filesystem_query_pattern_selection(query_text, args)["patterns"]


def canonical_filesystem_path(path):
    return os.path.realpath(os.path.abspath(os.path.expanduser(os.fspath(path))))


def filesystem_query_root(cwd=None):
    cwd = canonical_filesystem_path(cwd or os.getcwd())
    for directory in parent_dirs(cwd):
        if os.path.exists(os.path.join(directory, ".git")):
            return directory
        if os.path.isfile(os.path.join(directory, ".ygg", "project.edn")):
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


def filesystem_query_argv(patterns, search_paths=None):
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
    argv.append("--")
    argv.extend(search_paths or ["."])
    return argv


def append_bounded_bytes(capture, chunk, max_bytes):
    remaining = max(0, max_bytes + 1 - len(capture["data"]))
    capture["data"].extend(chunk[:remaining])
    if len(chunk) > remaining:
        capture["overflow"] = True


def bounded_capture_text(capture, max_bytes):
    data = bytes(capture["data"])
    truncated = capture["overflow"] or len(data) > max_bytes
    if truncated:
        data = data[:max_bytes]
        newline = data.rfind(b"\n")
        data = data[:newline + 1] if newline >= 0 else b""
    return data.decode("utf-8", errors="replace"), truncated


def close_capture_streams(streams):
    for stream, _name, _max_bytes in list(streams.values()):
        stream.close()
    streams.clear()


def drain_ready_stream(streams, descriptor, captures):
    stream, name, max_bytes = streams[descriptor]
    while True:
        try:
            chunk = os.read(descriptor, 65536)
        except BlockingIOError:
            return
        if chunk:
            append_bounded_bytes(captures[name], chunk, max_bytes)
        else:
            streams.pop(descriptor)
            stream.close()
            return


def ready_capture_streams(streams, timeout_seconds):
    if not streams:
        return []
    ready, _write, _error = select.select(
        list(streams),
        [],
        [],
        timeout_seconds,
    )
    return ready


def spawn_captured_child(argv):
    stdout_read, stdout_write = os.pipe()
    stderr_read, stderr_write = os.pipe()
    file_actions = [
        (os.POSIX_SPAWN_OPEN, 0, os.devnull, os.O_RDONLY, 0o644),
        (os.POSIX_SPAWN_DUP2, stdout_write, 1),
        (os.POSIX_SPAWN_DUP2, stderr_write, 2),
        (os.POSIX_SPAWN_CLOSE, stdout_read),
        (os.POSIX_SPAWN_CLOSE, stderr_read),
        (os.POSIX_SPAWN_CLOSE, stdout_write),
        (os.POSIX_SPAWN_CLOSE, stderr_write),
    ]
    try:
        pid = os.posix_spawnp(
            os.fspath(argv[0]),
            [os.fspath(value) for value in argv],
            os.environ,
            file_actions=file_actions,
        )
    except BaseException:
        os.close(stdout_read)
        os.close(stderr_read)
        raise
    finally:
        os.close(stdout_write)
        os.close(stderr_write)
    return {
        "pid": pid,
        "returncode": None,
        "stdout": os.fdopen(stdout_read, "rb", buffering=0),
        "stderr": os.fdopen(stderr_read, "rb", buffering=0),
    }


def poll_child(child):
    if child["returncode"] is not None:
        return child["returncode"]
    waited_pid, status = os.waitpid(child["pid"], os.WNOHANG)
    if waited_pid:
        child["returncode"] = os.waitstatus_to_exitcode(status)
    return child["returncode"]


def wait_child(child, deadline=None):
    if child["returncode"] is not None:
        return child["returncode"]
    if deadline is None:
        _pid, status = os.waitpid(child["pid"], 0)
        child["returncode"] = os.waitstatus_to_exitcode(status)
        return child["returncode"]
    while time.monotonic() < deadline:
        if poll_child(child) is not None:
            return child["returncode"]
        time.sleep(min(0.001, max(0.0, deadline - time.monotonic())))
    return poll_child(child)


def kill_child(child):
    if poll_child(child) is not None:
        return child["returncode"]
    import signal
    try:
        os.kill(child["pid"], signal.SIGKILL)
    except ProcessLookupError:
        pass
    return wait_child(child)


def drain_remaining_streams(streams, captures):
    while streams:
        descriptors = ready_capture_streams(streams, 0.05)
        if not descriptors:
            return
        for descriptor in descriptors:
            drain_ready_stream(streams, descriptor, captures)


def run_captured_process(argv, timeout_ms, max_stdout_bytes, max_stderr_bytes):
    child = spawn_captured_child(argv)
    captures = {
        "stdout": {"data": bytearray(), "overflow": False},
        "stderr": {"data": bytearray(), "overflow": False},
    }
    streams = {}
    deadline = None if timeout_ms <= 0 else time.monotonic() + timeout_ms / 1000.0
    timed_out = False
    try:
        for stream, name, max_bytes in (
            (child["stdout"], "stdout", max_stdout_bytes),
            (child["stderr"], "stderr", max_stderr_bytes),
        ):
            os.set_blocking(stream.fileno(), False)
            streams[stream.fileno()] = (stream, name, max_bytes)
        while streams:
            remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
            descriptors = ready_capture_streams(streams, remaining)
            if not descriptors:
                timed_out = deadline is not None and time.monotonic() >= deadline
                if timed_out:
                    kill_child(child)
                    break
                continue
            for descriptor in descriptors:
                drain_ready_stream(streams, descriptor, captures)
            if (deadline is not None
                    and time.monotonic() >= deadline
                    and poll_child(child) is None):
                timed_out = True
                kill_child(child)
                break
        if not timed_out:
            if wait_child(child, deadline) is None:
                timed_out = True
                kill_child(child)
        if timed_out:
            drain_remaining_streams(streams, captures)
    finally:
        if poll_child(child) is None:
            kill_child(child)
        close_capture_streams(streams)
    stdout, stdout_truncated = bounded_capture_text(captures["stdout"], max_stdout_bytes)
    stderr, stderr_truncated = bounded_capture_text(
        captures["stderr"], max_stderr_bytes
    )
    return {
        "exit": child["returncode"],
        "stdout": stdout,
        "stderr": stderr,
        "timeout?": timed_out,
        "stdout-truncated?": stdout_truncated,
        "stderr-truncated?": stderr_truncated,
    }


def parse_filesystem_count_output(stdout):
    matches = []
    for line in stdout.splitlines():
        path, separator, raw_count = line.rpartition(":")
        if not separator or not raw_count.isdigit():
            continue
        path = path.removeprefix("./").replace(os.sep, "/")
        matches.append({"path": path, "count": int(raw_count)})
    return matches


def run_filesystem_search(root, patterns, search_paths=None):
    started = time.monotonic()
    timeout_ms = filesystem_query_timeout_ms()
    max_stdout_bytes = filesystem_query_max_stdout_bytes()
    diagnostics = []
    local_search = not search_paths
    effective_search_paths = search_paths or [canonical_filesystem_path(root)]
    argv = filesystem_query_argv(patterns, effective_search_paths)
    try:
        captured = run_captured_process(
            argv,
            timeout_ms,
            max_stdout_bytes,
            20000,
        )
    except OSError as exc:
        return {
            "elapsed-ms": int((time.monotonic() - started) * 1000),
            "argv": argv,
            "matches": [],
            "diagnostics": [{"kind": "unavailable", "message": str(exc)}],
            "process-attempted?": False,
            "timeout?": False,
            "truncated?": False,
        }
    stdout = captured["stdout"]
    stderr = captured["stderr"]
    timed_out = captured["timeout?"]
    truncated = captured["stdout-truncated?"]
    if timed_out:
        diagnostics.append({"kind": "timeout"})
    if truncated:
        diagnostics.append({"kind": "stdout-truncated"})
    if captured["stderr-truncated?"]:
        diagnostics.append({"kind": "stderr-truncated"})
    if captured["exit"] not in {0, 1} and not timed_out:
        diagnostics.append({
            "kind": "ripgrep-error",
            "message": stderr.strip(),
        })
    elif stderr.strip():
        diagnostics.append({"kind": "stderr", "message": stderr.strip()})
    matches = parse_filesystem_count_output(stdout)
    if local_search:
        root_prefix = canonical_filesystem_path(root)
        for match in matches:
            if os.path.isabs(match["path"]):
                match["path"] = os.path.relpath(match["path"], root_prefix).replace(
                    os.sep,
                    "/",
                )
    return {
        "elapsed-ms": int((time.monotonic() - started) * 1000),
        "argv": argv,
        "matches": matches,
        "diagnostics": diagnostics,
        "process-attempted?": True,
        "timeout?": timed_out,
        "truncated?": truncated,
    }


def filesystem_handoff_repositories(response):
    if not isinstance(response, dict):
        return []
    data = response.get("data")
    if not isinstance(data, dict) or data.get("schema") != FILESYSTEM_HANDOFF_SCHEMA:
        return []
    repositories = []
    seen_roots = set()
    for row in data.get("repos") or []:
        if not isinstance(row, dict) or not row.get("root"):
            continue
        try:
            root = canonical_filesystem_path(row["root"])
        except OSError:
            continue
        if not os.path.isdir(root) or root in seen_roots:
            continue
        seen_roots.add(root)
        repositories.append({
            "id": str(row.get("id") or os.path.basename(root)),
            "root": root,
        })
        if len(repositories) >= DEFAULT_FILESYSTEM_HANDOFF_REPO_LIMIT:
            break
    return repositories


def filesystem_project_match(row, repositories):
    absolute = os.path.normpath(os.path.abspath(row["path"]))
    for repository in repositories:
        root_prefix = repository["root-prefix"]
        if absolute == root_prefix:
            relative = "."
        elif absolute.startswith(root_prefix + os.sep):
            relative = absolute[len(root_prefix) + 1:]
        else:
            continue
        return {
            "path": relative.replace(os.sep, "/"),
            "count": row["count"],
            "repo": repository["id"],
        }
    return None


def filesystem_repository_scope(repository):
    root = canonical_filesystem_path(repository["root"])
    return {
        **repository,
        "root": root,
        "root-prefix": os.path.normpath(root),
    }


def run_filesystem_project_search(repositories, patterns):
    repositories = sorted(
        [filesystem_repository_scope(repository) for repository in repositories],
        key=lambda candidate: len(candidate["root-prefix"]),
        reverse=True,
    )
    search = run_filesystem_search(
        filesystem_query_root(),
        patterns,
        [repository["root"] for repository in repositories],
    )
    mapped_by_path = {}
    unmapped_count = 0
    for row in search["matches"]:
        match = filesystem_project_match(row, repositories)
        if match is None:
            unmapped_count += 1
            continue
        key = (match["repo"], match["path"])
        existing = mapped_by_path.get(key)
        if existing is None or match["count"] > existing["count"]:
            mapped_by_path[key] = match
    mapped = list(mapped_by_path.values())
    diagnostics = list(search["diagnostics"])
    if unmapped_count:
        diagnostics.append({
            "kind": "unmapped-path",
            "count": unmapped_count,
        })
    return {
        **search,
        "matches": mapped,
        "diagnostics": diagnostics,
        "repository-count": len(repositories),
    }


def degradation_message(reason):
    if reason == "active-indexing":
        return (
            "Indexing is active; search is using bounded filesystem evidence. "
            "Graph-enriched results will become available automatically."
        )
    if reason == "active-embedding":
        return (
            "Embedding is active; search is using bounded filesystem evidence. "
            "Semantic and graph-enriched results will become available automatically."
        )
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
    if reason == "query-hedge":
        return (
            "Yggdrasil enrichment did not respond before the filesystem hedge; "
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


def filesystem_query_packet(args, reason, repositories=None):
    started = time.monotonic()
    query_text = " ".join(query_positional_args(args)).strip()
    root = os.fspath(filesystem_query_root())
    pattern_selection = filesystem_query_pattern_selection(query_text, args)
    patterns = pattern_selection["patterns"]
    repositories = repositories or []
    if repositories:
        search = run_filesystem_project_search(repositories, patterns)
    else:
        search = run_filesystem_search(root, patterns)
        for match in search["matches"]:
            match["repo"] = option_value(args, "--repo") or os.path.basename(root)

    def path_pattern_count(row):
        path = row["path"].lower()
        return sum(1 for pattern in patterns if pattern.lower() in path)

    matches = sorted(
        search["matches"],
        key=lambda row: (
            -path_pattern_count(row),
            -row["count"],
            len(row["path"]),
            row["repo"],
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
            "repo": match["repo"],
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
    diagnostics = [*pattern_selection["diagnostics"], *search["diagnostics"]]
    for diagnostic in diagnostics:
        kind = diagnostic.get("kind")
        if kind:
            diagnostic_counts[kind] = diagnostic_counts.get(kind, 0) + 1
    incomplete = bool(
        FILESYSTEM_INCOMPLETE_DIAGNOSTIC_KINDS.intersection(diagnostic_counts)
    )
    message = degradation_message(reason)
    warnings = [message]
    if incomplete:
        warnings.append(FILESYSTEM_INCOMPLETE_WARNING)
    requested = option_value(args, "--retriever") or "auto"
    packet = {
        "schema": FILESYSTEM_QUERY_SCHEMA,
        "query": pattern_selection["query"],
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
        "warnings": warnings,
        "degradation": {
            "schema": QUERY_DEGRADATION_SCHEMA,
            "status": "degraded",
            "reason": reason,
            "fallback": "filesystem",
            "message": message,
        },
        "search": {
            "instrumentation": {
                "filesystem-processes": int(search.get("process-attempted?", True)),
                "filesystem-process-launcher": FILESYSTEM_PROCESS_LAUNCHER,
                "filesystem-repos": len(repositories) if repositories else 1,
                "filesystem-handoff?": bool(repositories),
                "filesystem-search-ms": search["elapsed-ms"],
                "filesystem-total-ms": int((time.monotonic() - started) * 1000),
                "filesystem-patterns": patterns,
                "filesystem-query-scan-limit-characters": (
                    DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT
                ),
                "filesystem-query-truncated?": pattern_selection[
                    "query-truncated?"
                ],
                "filesystem-pattern-character-limit": (
                    DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT
                ),
                "filesystem-dropped-pattern-count": pattern_selection[
                    "dropped-pattern-count"
                ],
                "filesystem-match-count": sum(row["count"] for row in search["matches"]),
                "filesystem-file-count": len(search["matches"]),
                "filesystem-returned-count": len(results),
                "filesystem-diagnostic-kinds": diagnostic_counts,
                "filesystem-incomplete?": incomplete,
                "filesystem-timeout-ms": filesystem_query_timeout_ms(),
            }
        },
    }
    return packet


def filesystem_query_response(args, reason, repositories=None):
    query_text = " ".join(query_positional_args(args)).strip()
    if not query_text:
        return {"exit": 2, "out": "", "err": "Missing query text.\n"}
    json_output = has_flag(args, "--json")
    if json_output:
        prefetch_standard_json()
    packet = filesystem_query_packet(args, reason, repositories)
    if json_output:
        return {
            "exit": 0,
            "out": standard_json().dumps(packet, separators=(",", ":")) + "\n",
            "err": "",
            "data": packet,
        }
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
        "err": "".join(f"Warning: {warning}\n" for warning in packet["warnings"]),
        "data": packet,
    }


def parent_dirs(path):
    current = canonical_filesystem_path(path)
    while True:
        yield current
        parent = os.path.dirname(current)
        if parent == current:
            return
        current = parent


def display_path(path):
    try:
        absolute = canonical_filesystem_path(path)
    except OSError:
        absolute = os.path.abspath(os.path.expanduser(os.fspath(path)))
    try:
        cwd = os.path.realpath(os.getcwd())
        relative = os.path.relpath(absolute, cwd)
        if relative != ".." and not relative.startswith("../"):
            return relative
    except OSError:
        pass
    return str(absolute)


def path_object(value):
    from pathlib import Path
    return Path(value)


def project_registry_path():
    configured = os.environ.get("YGG_PROJECTS_FILE")
    if configured:
        return path_object(configured).expanduser()
    config_home = os.environ.get("YGG_CONFIG_HOME")
    if config_home:
        return path_object(config_home).expanduser() / "projects.edn"
    xdg_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_home:
        return path_object(xdg_home).expanduser() / "ygg" / "projects.edn"
    return path_object("~").expanduser() / ".config" / "ygg" / "projects.edn"


def nearest_project_ref_path(cwd=None):
    for directory in parent_dirs(cwd or os.getcwd()):
        path = os.path.join(directory, ".ygg", "project.edn")
        if os.path.isfile(path):
            return path_object(path)
    return None


def cwd_project_config_path(cwd=None):
    path = path_object(cwd or os.getcwd()) / "project.edn"
    return path if path.is_file() else None


def looks_like_config_path(value):
    if not value or value.startswith("-"):
        return False
    path = path_object(value)
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
        file = path_object(config_path).expanduser()
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


def socket_response_lines(connection):
    buffered = bytearray()
    while True:
        chunk = connection.recv(65536)
        if not chunk:
            if buffered:
                yield bytes(buffered).decode("utf-8")
            return
        buffered.extend(chunk)
        while True:
            newline = buffered.find(b"\n")
            if newline < 0:
                break
            line = bytes(buffered[:newline + 1]).decode("utf-8")
            del buffered[:newline + 1]
            yield line


def read_server_response(response_file, render_progress=False, on_frame=None):
    printed_header = False
    for line in response_file:
        if not line:
            continue
        response = standard_json().loads(line)
        if response.get("schema") == SERVER_FRAME_SCHEMA:
            if on_frame is not None:
                on_frame(response)
            if response.get("type") == "progress" and render_progress:
                if callable(render_progress):
                    render_progress(response)
                else:
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


def request_once(
    op,
    args,
    extra=None,
    stream=False,
    render_progress=False,
    connect_timeout_override_ms=None,
    on_frame=None,
    connected_socket=None,
):
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
    if op == "query":
        payload["filesystemFallbackOwner"] = "client"
    timeout_seconds = response_timeout_seconds(op, args)
    start = time.monotonic()
    sock = connected_socket
    if sock is None:
        sock = connect_socket(
            server_host(),
            server_port(),
            connect_timeout_override_ms,
        )
    if sock is None:
        return None
    try:
        sock.settimeout(timeout_seconds)
        request_line = standard_json().dumps(payload, separators=(",", ":")) + "\n"
        sock.sendall(request_line.encode("utf-8"))
        response = read_server_response(
            socket_response_lines(sock),
            render_progress=render_progress,
            on_frame=on_frame,
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
                f"args={standard_json().dumps(args)}\n"
                f"elapsedMs={elapsed_ms}\n"
                f"timeoutMs={timeout_ms}\n"
            ),
        }
    except OSError:
        return None
    finally:
        sock.close()


def request(
    op,
    args,
    extra=None,
    stream=False,
    render_progress=False,
    connect_timeout_override_ms=None,
    on_frame=None,
    connected_socket=None,
):
    if op == "query" and connect_timeout_override_ms is None:
        connect_timeout_override_ms = 0
    timeout_ms = starting_retry_timeout_ms()
    deadline = time.monotonic() + (timeout_ms / 1000.0)
    next_socket = connected_socket
    while True:
        response = request_once(
            op,
            args,
            extra=extra,
            stream=stream,
            render_progress=render_progress,
            connect_timeout_override_ms=connect_timeout_override_ms,
            on_frame=on_frame,
            connected_socket=next_socket,
        )
        next_socket = None
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
    sys.stdout.write(standard_json().dumps(value, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def mcp_manifest():
    with open(MCP_MANIFEST_PATH, encoding="utf-8") as file:
        return standard_json().load(file)


def mcp_tool_groups(args):
    configured = (
        option_value(args, "--tools")
        or os.environ.get("YGG_MCP_TOOLS")
        or "default"
    )
    return {
        value.strip()
        for value in configured.split(",")
        if value.strip()
    }


def mcp_listed_tools(args, manifest):
    groups = mcp_tool_groups(args)
    tools = []
    for definition in manifest["tools"]:
        if "all" not in groups and not groups.intersection(definition["groups"]):
            continue
        tools.append({
            key: value
            for key, value in definition.items()
            if key not in {"groups", "readOnly"}
        })
    return tools


def mcp_local_protocol_response(args, message, manifest):
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "protocolVersion": (
                    params.get("protocolVersion")
                    or manifest["protocolVersion"]
                ),
                "capabilities": {"tools": {}, "resources": {}},
                "serverInfo": manifest["serverInfo"],
                "instructions": manifest["instructions"],
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": request_id, "result": {}}
    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {"tools": mcp_listed_tools(args, manifest)},
        }
    return None


def mcp_query_call(message):
    return (
        message.get("method") == "tools/call"
        and (message.get("params") or {}).get("name") == "ygg_query"
    )


def mcp_query_cli_args(proxy_args, message):
    arguments = (message.get("params") or {}).get("arguments") or {}
    query = arguments.get("query")
    if not isinstance(query, str) or not query.strip():
        return None
    query_args = [query, "--json", "--no-progress"]
    options = [
        ("projectId", "--project"),
        ("configPath", "--config"),
        ("retriever", "--retriever"),
        ("budget", "--budget"),
    ]
    for key, flag in options:
        value = arguments.get(key)
        if value is None and flag in {"--project", "--config"}:
            value = option_value(proxy_args, flag)
        if value is not None:
            query_args.extend([flag, str(value)])
    return query_args


def mcp_tool_packet(value):
    return {
        "content": [{
            "type": "text",
            "text": standard_json().dumps(value, separators=(",", ":")),
        }],
        "structuredContent": value,
    }


def mcp_query_response(args, message):
    query_args = mcp_query_cli_args(args, message)
    if query_args is None:
        return jsonrpc_error(
            "Yggdrasil context query requires query.",
            message,
            -32602,
            {
                "schema": "ygg.mcp.error/v1",
                "error": "invalid-arguments",
                "field": "query",
            },
        ), False
    response, start_server = resolved_query_response(
        query_args,
        stream=True,
        render_progress=False,
    )
    if response is None or not response.get("ok", response.get("exit") == 0):
        err = ((response or {}).get("err") or "Yggdrasil query failed.").strip()
        return jsonrpc_error(
            err,
            message,
            -32000,
            (response or {}).get("data"),
        ), start_server
    packet = response.get("data")
    if packet is None:
        try:
            packet = standard_json().loads(response.get("out") or "")
        except (ValueError, TypeError):
            return jsonrpc_error(
                "Yggdrasil query returned no context packet.",
                message,
            ), start_server
    return {
        "jsonrpc": "2.0",
        "id": message.get("id"),
        "result": mcp_tool_packet(packet),
    }, start_server


def mcp_proxy(args):
    exit_code = 0
    json = standard_json()
    manifest = mcp_manifest()
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
        local_response = mcp_local_protocol_response(args, message, manifest)
        if local_response is not None:
            write_json_line(local_response)
            if message.get("method") == "initialize":
                schedule_server_start()
            continue
        if message.get("id") is None:
            continue
        if mcp_query_call(message):
            result, start_server = mcp_query_response(args, message)
            write_json_line(result)
            if start_server:
                schedule_server_start()
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
            continue
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


def query_response_fallback_reason(response):
    if response is None:
        return "server-unavailable"
    response_reason = (
        response.get("data", {}).get("reason")
        if isinstance(response, dict) and isinstance(response.get("data"), dict)
        else None
    )
    if response_reason in {
        "active-embedding",
        "active-indexing",
        "query-timeout",
        "storage-unavailable",
    }:
        return response_reason
    if server_starting_response(response):
        return "server-starting"
    return None


def start_pending_query_request(args, stream, render_progress, connected_socket):
    completion = _thread.allocate_lock()
    completion.acquire()
    state = {}
    progress_frames = []

    def collect_frame(frame):
        if frame.get("type") == "accepted":
            state["acknowledged?"] = True
            state["filesystem-handoff"] = frame.get("filesystemHandoff")
        if (frame.get("type") == "progress"
                and render_progress
                and len(progress_frames) < 100):
            progress_frames.append(frame)

    def run_request():
        try:
            state["response"] = request(
                "query",
                args,
                stream=stream,
                render_progress=False,
                on_frame=collect_frame,
                connected_socket=connected_socket,
            )
        except Exception as exc:
            state["error"] = exc
        finally:
            completion.release()

    _thread.start_new_thread(run_request, ())
    return {
        "completion": completion,
        "state": state,
        "progress-frames": progress_frames,
        "render-progress?": render_progress,
    }


def pending_query_ready(pending, timeout_seconds=0):
    ready = pending["completion"].acquire(timeout=max(0.0, timeout_seconds))
    if ready:
        pending["completion"].release()
    return ready


def completed_query_response(pending):
    error = pending["state"].get("error")
    if error is not None:
        raise error
    if pending["render-progress?"]:
        printed_header = False
        for frame in pending["progress-frames"]:
            printed_header = render_progress_frame(frame, printed_header)
    return pending["state"].get("response")


def pending_query_filesystem_repositories(pending):
    handoff = pending["state"].get("filesystem-handoff")
    if not handoff:
        return []
    return filesystem_handoff_repositories({"data": handoff})


def pending_query_filesystem_reason(pending):
    handoff = pending["state"].get("filesystem-handoff") or {}
    reason = handoff.get("reason")
    if reason in {"active-embedding", "active-indexing"}:
        return reason
    return "query-hedge"


def hedged_query_request(args, stream, render_progress):
    hedge_after_ms = query_hedge_after_ms(args)
    if hedge_after_ms <= 0:
        return request(
            "query",
            args,
            stream=stream,
            render_progress=render_progress,
        ), None
    connected_socket = connect_socket(server_host(), server_port(), 0)
    if connected_socket is None:
        return None, None
    try:
        standard_json()
        pending = start_pending_query_request(
            args,
            stream,
            render_progress,
            connected_socket,
        )
    except Exception:
        connected_socket.close()
        raise
    if pending_query_ready(pending, hedge_after_ms / 1000.0):
        return completed_query_response(pending), None
    if pending["state"].get("acknowledged?"):
        acknowledged_after_ms = acknowledged_query_hedge_after_ms(args)
        remaining_ms = max(0, acknowledged_after_ms - hedge_after_ms)
        if pending_query_ready(pending, remaining_ms / 1000.0):
            return completed_query_response(pending), None
    return None, pending


def resolved_query_response(args, stream=False, render_progress=False):
    response, pending = hedged_query_request(args, stream, render_progress)
    if pending is not None:
        repositories = pending_query_filesystem_repositories(pending)
        reason = pending_query_filesystem_reason(pending)
        filesystem_response = (
            filesystem_query_response(args, reason, repositories)
            if repositories
            else filesystem_query_response(args, reason)
        )
        if pending_query_ready(pending):
            completed_response = completed_query_response(pending)
            completed_reason = query_response_fallback_reason(completed_response)
            if completed_reason is None:
                return completed_response, False
            return filesystem_response, completed_reason == "server-unavailable"
        return filesystem_response, False
    reason = query_response_fallback_reason(response)
    if reason is not None:
        repositories = filesystem_handoff_repositories(response)
        filesystem_response = (
            filesystem_query_response(args, reason, repositories)
            if repositories
            else filesystem_query_response(args, reason)
        )
        return filesystem_response, reason == "server-unavailable"
    return response, False


def server_request(op, args, stream=False, render_progress=False):
    start_server = False
    if op == "query":
        response, start_server = resolved_query_response(
            args,
            stream,
            render_progress,
        )
    else:
        response = request(op, args, stream=stream, render_progress=render_progress)
    if response is None:
        sys.stderr.write(unavailable_message(op, args))
        return UNAVAILABLE
    exit_code = print_response(response)
    if start_server:
        sys.stdout.flush()
        sys.stderr.flush()
        start_server_in_background()
    return exit_code


def ygg_home():
    return os.path.abspath(os.path.expanduser(os.environ.get("YGG_HOME") or ROOT))


def server_log_path():
    configured = os.environ.get("YGG_SERVER_LOG")
    if configured:
        return os.path.abspath(os.path.expanduser(configured))
    return os.path.join(ygg_home(), ".ygg", "server.log")


def server_start_marker_path():
    return os.path.join(os.path.dirname(server_log_path()), "server-start-requested")


def claim_server_start():
    marker = server_start_marker_path()
    os.makedirs(os.path.dirname(marker), exist_ok=True)
    now = time.time()
    try:
        age = now - os.stat(marker).st_mtime
    except FileNotFoundError:
        age = None
    if age is not None and age >= DEFAULT_QUERY_AUTO_START_COOLDOWN_SECONDS:
        try:
            os.unlink(marker)
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
    import subprocess
    log_path = server_log_path()
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
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
        return {"status": "requested", "log": log_path}
    except OSError as exc:
        try:
            os.unlink(server_start_marker_path())
        except FileNotFoundError:
            pass
        return {"status": "error", "error": str(exc), "log": log_path}
    finally:
        log.close()


def schedule_server_start():
    _thread.start_new_thread(start_server_in_background, ())


def start_server_for_init():
    import subprocess
    log_path = server_log_path()
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
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
            return {"status": "started", "log": log_path}
        time.sleep(1)
    return {"status": "timeout", "log": log_path}


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
    root_path = path_object(root or os.getcwd()).expanduser()
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
    json = standard_json()
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
    return path_object("~").expanduser() / "Library" / "LaunchAgents"


def launch_agent_path():
    return launch_agent_dir() / "com.yggdrasil.server.plist"


def launch_agent_plist():
    log_dir = path_object(ygg_home()) / ".ygg"
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
    import subprocess
    return subprocess.run(["launchctl", *args], text=True, capture_output=True, timeout=15)


def start_at_login(args, emit=True):
    import platform
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
        (path_object(ygg_home()) / ".ygg").mkdir(parents=True, exist_ok=True)
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
        sys.stdout.write(standard_json().dumps(result, indent=2) + "\n")
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
