#!/usr/bin/env python3
import json
import os
import socket
import sys


UNAVAILABLE = 75


def descriptor_path():
    return os.environ.get("YGG_DAEMON_JSON", ".dev/ygg/daemon.json")


def load_descriptor():
    path = descriptor_path()
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def request(op, args):
    descriptor = load_descriptor()
    if not descriptor:
        return None
    payload = {
        "op": op,
        "args": args,
        "token": descriptor.get("token"),
    }
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
        return None


def main(argv):
    if len(argv) < 2:
        return UNAVAILABLE
    response = request(argv[1], argv[2:])
    if response is None:
        return UNAVAILABLE
    out = response.get("out") or ""
    err = response.get("err") or ""
    if out:
        sys.stdout.write(out)
    if err:
        sys.stderr.write(err)
    return int(response.get("exit", 1))


if __name__ == "__main__":
    sys.exit(main(sys.argv))
