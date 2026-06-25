#!/usr/bin/env python3
import json
import os
import socket
import sys
import time


UNAVAILABLE = 75
UNAVAILABLE_MESSAGE = "Yggdrasil server is not running. Run `ygg start` first.\n"
DEFAULT_SERVER_HOST = "127.0.0.1"
DEFAULT_SERVER_PORT = 62121
DEFAULT_CONNECT_TIMEOUT_MS = 30000
CONNECT_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_REQUEST_TIMEOUT_MS = 600000
REMOVED_PUBLIC_COMMANDS = {
    "classify",
    "cli",
    "context",
    "daemon",
    "deps",
    "docs",
    "graph",
    "index",
    "meta",
    "path",
    "project",
    "queue",
    "sync-inspect",
    "systems",
    "views",
}

PUBLIC_COMMAND_OPS = {
    "init": "init",
    "current": "current",
    "use": "use",
    "projects": "projects",
    "audit-scope": "audit-scope",
    "maintenance": "maintenance",
    "corrections": "corrections",
    "memory": "memory",
    "affected": "affected",
    "view": "view",
    "packages": "packages",
    "report": "report",
    "plugin": "plugin",
    "agent": "agent",
    "watch": "watch",
    "hook": "hook",
    "bench": "bench",
    "embed": "embed",
}

SYNC_SUBCOMMAND_OPS = {
    "inspect": "sync.inspect",
    "activity": "sync.activity",
    "add-repo": "sync.add-repo",
    "coverage": "sync.coverage",
    "docs": "sync.docs",
    "meta": "sync.meta",
    "view": "sync.view",
    "work": "sync.work",
}


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


def request_timeout_seconds():
    raw = os.environ.get("YGG_SERVER_REQUEST_TIMEOUT_MS")
    if not raw:
        return DEFAULT_REQUEST_TIMEOUT_MS / 1000.0
    try:
        timeout_ms = int(raw)
    except ValueError:
        return DEFAULT_REQUEST_TIMEOUT_MS / 1000.0
    if timeout_ms <= 0:
        return None
    return timeout_ms / 1000.0


def connect_timeout_ms():
    raw = os.environ.get("YGG_SERVER_CONNECT_TIMEOUT_MS")
    if not raw:
        return DEFAULT_CONNECT_TIMEOUT_MS
    try:
        timeout_ms = int(raw)
    except ValueError:
        return DEFAULT_CONNECT_TIMEOUT_MS
    return max(timeout_ms, 0)


def connect_socket(host, port):
    timeout_ms = connect_timeout_ms()
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


def request(op, args, cleanup_stale=False, extra=None):
    del cleanup_stale
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
    host = server_host()
    port = server_port()
    timeout_seconds = request_timeout_seconds()
    start = time.monotonic()
    sock = connect_socket(host, port)
    if sock is None:
        return None
    try:
        with sock:
            sock.settimeout(timeout_seconds)
            request_line = json.dumps(payload, separators=(",", ":")) + "\n"
            sock.sendall(request_line.encode("utf-8"))
            response = sock.makefile("r", encoding="utf-8").readline()
            if not response:
                return {"exit": 1, "out": "", "err": "Empty server response.\n"}
            return json.loads(response)
    except socket.timeout:
        elapsed_ms = int((time.monotonic() - start) * 1000)
        timeout_ms = None if timeout_seconds is None else int(timeout_seconds * 1000)
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
        response = request("mcp", args, cleanup_stale=True, extra={"message": message})
        if response is None:
            sys.stderr.write(UNAVAILABLE_MESSAGE)
            write_json_line(jsonrpc_error(
                "Yggdrasil server is not running.",
                message,
                -32000,
                {"hint": "Run `ygg start` first."},
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


def control_request(op, args):
    response = request(op, args, cleanup_stale=True)
    if response is None:
        sys.stderr.write(UNAVAILABLE_MESSAGE)
        return UNAVAILABLE
    return print_response(response)


def reject_removed_command(command):
    response = request("status", [], cleanup_stale=True)
    if response is None:
        sys.stderr.write(UNAVAILABLE_MESSAGE)
        return UNAVAILABLE
    sys.stderr.write(f"Unknown command: {command}\n")
    return 2


def main(argv):
    if len(argv) < 2:
        return UNAVAILABLE
    command = argv[1]
    if command == "mcp":
        return mcp_proxy(argv[2:])
    if command == "status":
        return control_request("status", argv[2:])
    if command == "stop":
        return control_request("stop", argv[2:])
    if command in REMOVED_PUBLIC_COMMANDS:
        return reject_removed_command(command)
    if command == "sync":
        if len(argv) > 2 and argv[2] in SYNC_SUBCOMMAND_OPS:
            response = request(SYNC_SUBCOMMAND_OPS[argv[2]], argv[3:], cleanup_stale=True)
        else:
            response = request("sync", argv[2:], cleanup_stale=True)
    elif command == "query":
        response = request("query", argv[2:], cleanup_stale=True)
    elif command in PUBLIC_COMMAND_OPS:
        response = request(PUBLIC_COMMAND_OPS[command], argv[2:], cleanup_stale=True)
    else:
        response = request("status", [], cleanup_stale=True)
        if response is None:
            sys.stderr.write(UNAVAILABLE_MESSAGE)
            return UNAVAILABLE
        sys.stderr.write(f"Unknown command: {command}\n")
        return 2
    if response is None:
        sys.stderr.write(UNAVAILABLE_MESSAGE)
        return UNAVAILABLE
    return print_response(response)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
