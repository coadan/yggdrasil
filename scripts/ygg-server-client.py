#!/usr/bin/env python3
import json
import os
import pathlib
import platform
import socket
import subprocess
import sys
import time


UNAVAILABLE = 75
UNAVAILABLE_MESSAGE = "Yggdrasil server is not running. Run `ygg init` first, or `ygg start` when a project is already initialized.\n"
DEFAULT_SERVER_HOST = "127.0.0.1"
DEFAULT_SERVER_PORT = 62121
DEFAULT_CONNECT_TIMEOUT_MS = 30000
CONNECT_RETRY_INTERVAL_SECONDS = 5.0
DEFAULT_REQUEST_TIMEOUT_MS = 600000
SERVER_FRAME_SCHEMA = "ygg.server.frame/v1"
ROOT = pathlib.Path(__file__).resolve().parents[1]
YGG_BIN = ROOT / "bin" / "ygg"

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


def request(op, args, extra=None, stream=False, render_progress=False, connect_timeout_override_ms=None):
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
                {"hint": "Run `ygg init` first, or `ygg start` when a project is already initialized."},
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


def ygg_home():
    return pathlib.Path(os.environ.get("YGG_HOME") or str(ROOT))


def server_log_path():
    configured = os.environ.get("YGG_SERVER_LOG")
    if configured:
        return pathlib.Path(configured).expanduser()
    return ygg_home() / ".ygg" / "server.log"


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
    local_flags = {"--no-start-server", "--start-at-login", "--no-start-at-login"}
    server_args = remove_flags(args, local_flags)
    response = request("init", server_args, connect_timeout_override_ms=0)
    service = None
    if response is None and init_start_server_enabled(args):
        service = start_server_for_init()
        if service.get("status") == "started":
            response = request("init", server_args)
    if response is None:
        sys.stderr.write(UNAVAILABLE_MESSAGE)
        return UNAVAILABLE
    if service:
        response = attach_init_service_result(response, service)
    if "--start-at-login" in args:
        startup = start_at_login(["enable", "--json"], emit=False)
        response = attach_init_startup_result(response, startup)
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
    return server_request(command, argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
