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
SERVER_FRAME_SCHEMA = "ygg.server.frame/v1"

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


def request_timeout_seconds():
    timeout_ms = env_int("YGG_SERVER_REQUEST_TIMEOUT_MS", DEFAULT_REQUEST_TIMEOUT_MS)
    if timeout_ms <= 0:
        return None
    return timeout_ms / 1000.0


def connect_timeout_ms():
    return max(env_int("YGG_SERVER_CONNECT_TIMEOUT_MS", DEFAULT_CONNECT_TIMEOUT_MS), 0)


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


def progress_output(args):
    return "--json" not in args and "--no-progress" not in args


def render_progress_frame(frame, printed_header):
    message = frame.get("message")
    if not message:
        return printed_header
    if not printed_header:
        sys.stderr.write("# Sync Progress\n")
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


def request(op, args, extra=None, stream=False, render_progress=False):
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
        response = request("mcp", args, extra={"message": message})
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


def server_request(op, args, stream=False, render_progress=False):
    response = request(op, args, stream=stream, render_progress=render_progress)
    if response is None:
        sys.stderr.write(UNAVAILABLE_MESSAGE)
        return UNAVAILABLE
    return print_response(response)


def main(argv):
    if len(argv) < 2:
        return UNAVAILABLE
    command = argv[1]
    if command == "mcp":
        return mcp_proxy(argv[2:])
    if command == "sync":
        args = argv[2:]
        return server_request("sync", args, stream=True, render_progress=progress_output(args))
    return server_request(command, argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
