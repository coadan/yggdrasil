#!/usr/bin/env python3
"""Compare raw ripgrep with Yggdrasil's degraded query path."""

import argparse
import importlib.util
import json
import math
import os
import pathlib
import socket
import statistics
import subprocess
import sys
import tempfile
import time


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLIENT_PATH = ROOT / "scripts" / "ygg-server-client.py"
SCHEMA = "ygg.query-availability.benchmark/v1"


def load_client():
    spec = importlib.util.spec_from_file_location("ygg_server_client", CLIENT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def summarize(samples):
    elapsed = [sample["elapsedMs"] for sample in samples]
    return {
        "runs": len(samples),
        "completed": sum(1 for sample in samples if sample["completed"]),
        "timeouts": sum(1 for sample in samples if sample["timeout"]),
        "minMs": min(elapsed),
        "meanMs": statistics.fmean(elapsed),
        "p50Ms": percentile(elapsed, 0.50),
        "p95Ms": percentile(elapsed, 0.95),
        "maxMs": max(elapsed),
    }


def wait_process(process, timeout_ms):
    try:
        process.wait(timeout=None if timeout_ms <= 0 else timeout_ms / 1000.0)
        return False
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        return True


def raw_ripgrep_sample(client, repo, patterns, timeout_ms, rg_bin):
    argv = client.filesystem_query_argv(patterns)
    argv[0] = rg_bin
    started = time.monotonic()
    with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
        process = subprocess.Popen(
            argv,
            cwd=str(repo),
            stdin=subprocess.DEVNULL,
            stdout=stdout_file,
            stderr=stderr_file,
        )
        timed_out = wait_process(process, timeout_ms)
    return {
        "elapsedMs": (time.monotonic() - started) * 1000.0,
        "completed": not timed_out and process.returncode in {0, 1},
        "timeout": timed_out,
    }


def filesystem_lane_sample(client, repo, patterns, timeout_ms, rg_bin):
    previous_timeout = os.environ.get("YGG_FILESYSTEM_QUERY_TIMEOUT_MS")
    previous_bin = os.environ.get("YGG_RG_BIN")
    os.environ["YGG_FILESYSTEM_QUERY_TIMEOUT_MS"] = str(timeout_ms)
    os.environ["YGG_RG_BIN"] = rg_bin
    started = time.monotonic()
    try:
        result = client.run_filesystem_search(repo, patterns)
    finally:
        if previous_timeout is None:
            os.environ.pop("YGG_FILESYSTEM_QUERY_TIMEOUT_MS", None)
        else:
            os.environ["YGG_FILESYSTEM_QUERY_TIMEOUT_MS"] = previous_timeout
        if previous_bin is None:
            os.environ.pop("YGG_RG_BIN", None)
        else:
            os.environ["YGG_RG_BIN"] = previous_bin
    diagnostic_kinds = {row.get("kind") for row in result["diagnostics"]}
    return {
        "elapsedMs": (time.monotonic() - started) * 1000.0,
        "completed": not result["timeout?"] and "ripgrep-error" not in diagnostic_kinds,
        "timeout": result["timeout?"],
    }


def closed_loopback_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def cold_ygg_sample(repo, query, limit, timeout_ms, rg_bin, port):
    env = os.environ.copy()
    env.update({
        "YGG_SERVER_HOST": "127.0.0.1",
        "YGG_SERVER_PORT": str(port),
        "YGG_SERVER_CONNECT_TIMEOUT_MS": "0",
        "YGG_SERVER_STARTING_RETRY_TIMEOUT_MS": "0",
        "YGG_FILESYSTEM_QUERY_TIMEOUT_MS": str(timeout_ms),
        "YGG_RG_BIN": rg_bin,
    })
    argv = [
        sys.executable,
        str(CLIENT_PATH),
        "query",
        query,
        "--json",
        "--limit",
        str(limit),
        "--no-progress",
    ]
    started = time.monotonic()
    with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
        process = subprocess.Popen(
            argv,
            cwd=str(repo),
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=stdout_file,
            stderr=stderr_file,
        )
        timed_out = wait_process(process, timeout_ms + 2000)
    return {
        "elapsedMs": (time.monotonic() - started) * 1000.0,
        "completed": not timed_out and process.returncode == 0,
        "timeout": timed_out,
    }


def comparison(raw, lane, cold):
    raw_p95 = raw["p95Ms"]
    lane_p95 = lane["p95Ms"]
    cold_p95 = cold["p95Ms"]
    return {
        "filesystemLaneToRawP95Ratio": lane_p95 / raw_p95 if raw_p95 else None,
        "filesystemLaneP95OverheadMs": lane_p95 - raw_p95,
        "coldYggToRawP95Ratio": cold_p95 / raw_p95 if raw_p95 else None,
        "coldYggP95OverheadMs": cold_p95 - raw_p95,
        "rawParitySupported": cold_p95 <= raw_p95,
    }


def run(args):
    repo = pathlib.Path(args.repo).expanduser().resolve()
    if not repo.is_dir():
        raise SystemExit(f"Repository directory does not exist: {repo}")
    if args.iterations < 1:
        raise SystemExit("--iterations must be at least 1")
    if args.warmup < 0:
        raise SystemExit("--warmup cannot be negative")
    client = load_client()
    patterns = client.filesystem_query_patterns(args.query, [])
    port = closed_loopback_port()
    lane_fns = {
        "rawRipgrep": lambda: raw_ripgrep_sample(
            client, repo, patterns, args.timeout_ms, args.rg_bin
        ),
        "filesystemLane": lambda: filesystem_lane_sample(
            client, repo, patterns, args.timeout_ms, args.rg_bin
        ),
        "coldYgg": lambda: cold_ygg_sample(
            repo, args.query, args.limit, args.timeout_ms, args.rg_bin, port
        ),
    }
    lane_names = list(lane_fns)
    for index in range(args.warmup):
        for offset in range(len(lane_names)):
            lane_fns[lane_names[(index + offset) % len(lane_names)]]()
    samples = {lane: [] for lane in lane_names}
    for index in range(args.iterations):
        for offset in range(len(lane_names)):
            lane = lane_names[(index + offset) % len(lane_names)]
            samples[lane].append(lane_fns[lane]())
    lanes = {lane: summarize(rows) for lane, rows in samples.items()}
    report = {
        "schema": SCHEMA,
        "repo": str(repo),
        "query": args.query,
        "patterns": patterns,
        "iterations": args.iterations,
        "warmup": args.warmup,
        "timeoutMs": args.timeout_ms,
        "lanes": lanes,
        "comparison": comparison(
            lanes["rawRipgrep"],
            lanes["filesystemLane"],
            lanes["coldYgg"],
        ),
        "contract": {
            "sameRipgrepArgv": True,
            "oneFilesystemProcessPerRepo": True,
            "allRunsCompleted": all(
                lane["completed"] == args.iterations for lane in lanes.values()
            ),
            "zeroTimeouts": all(lane["timeouts"] == 0 for lane in lanes.values()),
        },
    }
    encoded = json.dumps(report, indent=2) + "\n"
    if args.out:
        out = pathlib.Path(args.out).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0 if report["contract"]["allRunsCompleted"] else 1


def parser():
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--repo", default=".")
    value.add_argument("--query", required=True)
    value.add_argument("--iterations", type=int, default=10)
    value.add_argument("--warmup", type=int, default=2)
    value.add_argument("--limit", type=int, default=10)
    value.add_argument("--timeout-ms", type=int, default=1500)
    value.add_argument("--rg-bin", default="rg")
    value.add_argument("--out")
    return value


if __name__ == "__main__":
    raise SystemExit(run(parser().parse_args()))
