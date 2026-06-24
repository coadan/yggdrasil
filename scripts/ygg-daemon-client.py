#!/usr/bin/env python3
import json
import os
import socket
import sys


UNAVAILABLE = 75


def daemon_dir():
    return os.environ.get("YGG_DAEMON_DIR", ".dev/ygg")


def descriptor_path():
    return os.environ.get("YGG_DAEMON_JSON", os.path.join(daemon_dir(), "daemon.json"))


def descriptor_edn_path():
    return os.environ.get("YGG_DAEMON_EDN", os.path.join(daemon_dir(), "daemon.edn"))


def remove_descriptors():
    for path in (descriptor_path(), descriptor_edn_path()):
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        except OSError:
            pass


def load_descriptor():
    path = descriptor_path()
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def storage_path():
    path = os.environ.get("YGG_XTDB_PATH")
    if not path:
        return None
    if os.path.isabs(path):
        return path
    return os.path.abspath(path)


def option_value(args, flag):
    try:
        idx = args.index(flag)
    except ValueError:
        return None
    if idx + 1 >= len(args):
        return None
    return args[idx + 1]


def request(op, args, cleanup_stale=False):
    descriptor = load_descriptor()
    if not descriptor:
        return None
    payload = {
        "op": op,
        "args": args,
        "token": descriptor.get("token"),
        "cwd": os.getcwd(),
    }
    explicit_storage_path = storage_path()
    if explicit_storage_path:
        payload["storagePath"] = explicit_storage_path
    project_id = option_value(args, "--project")
    if project_id:
        payload["projectId"] = project_id
    host = descriptor.get("host", "127.0.0.1")
    port = int(descriptor["port"])
    try:
        with socket.create_connection((host, port), timeout=0.25) as sock:
            sock.settimeout(None)
            request_line = json.dumps(payload, separators=(",", ":")) + "\n"
            sock.sendall(request_line.encode("utf-8"))
            response = sock.makefile("r", encoding="utf-8").readline()
            if not response:
                return {"exit": 1, "out": "", "err": "Empty daemon response.\n"}
            return json.loads(response)
    except OSError:
        if cleanup_stale:
            remove_descriptors()
        return None


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
        sys.stderr.write("daemon not running\n")
        return UNAVAILABLE
    return print_response(response)


def main(argv):
    if len(argv) < 2:
        return UNAVAILABLE
    command = argv[1]
    if command == "status":
        return control_request("ping", argv[2:])
    if command == "stop":
        return control_request("stop", argv[2:])
    if command == "sync" and len(argv) > 2 and argv[2] == "inspect":
        response = request("sync-inspect", argv[3:], cleanup_stale=True)
    elif command == "sync" and len(argv) > 2 and argv[2] == "check":
        response = request("sync-check", argv[3:], cleanup_stale=True)
    elif command == "sync" and (len(argv) <= 2 or argv[2] not in {
        "activity",
        "add-repo",
        "coverage",
        "docs",
        "inspect",
        "meta",
        "view",
        "work",
    }):
        response = request("sync", argv[2:], cleanup_stale=True)
    elif command == "sync-inspect":
        response = request("sync-inspect", argv[2:], cleanup_stale=True)
    elif command == "cli":
        response = request("cli", argv[2:], cleanup_stale=True)
    else:
        response = request("cli", argv[1:], cleanup_stale=True)
    if response is None:
        return UNAVAILABLE
    return print_response(response)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
