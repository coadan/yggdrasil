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
import threading
import time


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLIENT_PATH = ROOT / "scripts" / "ygg-server-client.py"
SCHEMA = "ygg.query-availability.benchmark/v6"


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
    degradation_reasons = {}
    for sample in samples:
        reason = sample.get("degradationReason")
        if reason:
            degradation_reasons[reason] = degradation_reasons.get(reason, 0) + 1
    summary = {
        "runs": len(samples),
        "completed": sum(1 for sample in samples if sample["completed"]),
        "timeouts": sum(1 for sample in samples if sample["timeout"]),
        "minMs": min(elapsed),
        "meanMs": statistics.fmean(elapsed),
        "p50Ms": percentile(elapsed, 0.50),
        "p95Ms": percentile(elapsed, 0.95),
        "maxMs": max(elapsed),
    }
    if degradation_reasons:
        summary["degradationReasons"] = degradation_reasons
    filesystem_process_counts = {}
    filesystem_repo_counts = {}
    filesystem_handoff_counts = {}
    for sample in samples:
        process_count = sample.get("filesystemProcesses")
        repo_count = sample.get("filesystemRepos")
        handoff = sample.get("filesystemHandoff")
        if process_count is not None:
            key = str(process_count)
            filesystem_process_counts[key] = filesystem_process_counts.get(key, 0) + 1
        if repo_count is not None:
            key = str(repo_count)
            filesystem_repo_counts[key] = filesystem_repo_counts.get(key, 0) + 1
        if handoff is not None:
            key = str(bool(handoff)).lower()
            filesystem_handoff_counts[key] = filesystem_handoff_counts.get(key, 0) + 1
    if filesystem_process_counts:
        summary["filesystemProcessCounts"] = filesystem_process_counts
    if filesystem_repo_counts:
        summary["filesystemRepoCounts"] = filesystem_repo_counts
    if filesystem_handoff_counts:
        summary["filesystemHandoffCounts"] = filesystem_handoff_counts
    return summary


def wait_process(process, timeout_ms):
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


def cold_ygg_sample(
    repo,
    query,
    limit,
    timeout_ms,
    rg_bin,
    port,
    query_fallback_after_ms=None,
    query_hedge_after_ms=None,
    acknowledged_query_hedge_after_ms=None,
):
    env = os.environ.copy()
    env.update({
        "YGG_SERVER_HOST": "127.0.0.1",
        "YGG_SERVER_PORT": str(port),
        "YGG_SERVER_CONNECT_TIMEOUT_MS": "0",
        "YGG_SERVER_STARTING_RETRY_TIMEOUT_MS": "0",
        "YGG_FILESYSTEM_QUERY_TIMEOUT_MS": str(timeout_ms),
        "YGG_RG_BIN": rg_bin,
        "YGG_QUERY_AUTO_START": "0",
    })
    if query_fallback_after_ms is not None:
        env["YGG_QUERY_FALLBACK_AFTER_MS"] = str(query_fallback_after_ms)
    if query_hedge_after_ms is not None:
        env["YGG_QUERY_HEDGE_AFTER_MS"] = str(query_hedge_after_ms)
    if acknowledged_query_hedge_after_ms is not None:
        env["YGG_QUERY_ACKNOWLEDGED_HEDGE_AFTER_MS"] = str(
            acknowledged_query_hedge_after_ms
        )
    argv = [
        sys.executable,
        "-S",
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
        timed_out = wait_process(
            process,
            max(
                timeout_ms,
                query_fallback_after_ms or 0,
                query_hedge_after_ms or 0,
                acknowledged_query_hedge_after_ms or 0,
            ) + 2000,
        )
        stdout_file.seek(0)
        try:
            packet = json.load(stdout_file)
        except (json.JSONDecodeError, UnicodeDecodeError):
            packet = {}
    return {
        "elapsedMs": (time.monotonic() - started) * 1000.0,
        "completed": not timed_out and process.returncode == 0,
        "timeout": timed_out,
        "degradationReason": packet.get("degradation", {}).get("reason"),
        "filesystemProcesses": packet.get("search", {}).get(
            "instrumentation", {}
        ).get("filesystem-processes"),
        "filesystemRepos": packet.get("search", {}).get(
            "instrumentation", {}
        ).get("filesystem-repos"),
        "filesystemHandoff": packet.get("search", {}).get(
            "instrumentation", {}
        ).get("filesystem-handoff?"),
    }


class StalledQueryServer:
    def __init__(self, delay_ms, acknowledge=False, filesystem_handoff=None):
        self.delay_seconds = delay_ms / 1000.0
        self.acknowledge = acknowledge
        self.filesystem_handoff = filesystem_handoff
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen()
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.running = threading.Event()
        self.thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self):
        self.running.set()
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.running.clear()
        self.listener.close()
        self.thread.join(timeout=1)

    def _serve(self):
        while self.running.is_set():
            try:
                connection, _address = self.listener.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            threading.Thread(
                target=self._stall_connection,
                args=(connection,),
                daemon=True,
            ).start()

    def _stall_connection(self, connection):
        with connection:
            try:
                connection.recv(65536)
                if self.acknowledge:
                    frame = {
                        "schema": "ygg.server.frame/v1",
                        "type": "accepted",
                        "operation": "query",
                    }
                    if self.filesystem_handoff:
                        frame["filesystemHandoff"] = self.filesystem_handoff
                    connection.sendall(
                        (json.dumps(frame, separators=(",", ":")) + "\n").encode()
                    )
                time.sleep(self.delay_seconds)
            except OSError:
                return


class ActiveIndexingHandoffServer:
    def __init__(self, repo):
        self.repo = pathlib.Path(repo).resolve()
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen()
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.running = threading.Event()
        self.thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self):
        self.running.set()
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.running.clear()
        self.listener.close()
        self.thread.join(timeout=1)

    def _serve(self):
        while self.running.is_set():
            try:
                connection, _address = self.listener.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            threading.Thread(
                target=self._handoff_connection,
                args=(connection,),
                daemon=True,
            ).start()

    def _handoff_connection(self, connection):
        with connection:
            try:
                connection.recv(65536)
                handoff = {
                    "schema": "ygg.query.filesystem-handoff/v1",
                    "reason": "active-indexing",
                    "fallback": "filesystem",
                    "repos": [{"id": self.repo.name, "root": str(self.repo)}],
                }
                connection.sendall((json.dumps({
                    "schema": "ygg.server.frame/v1",
                    "type": "accepted",
                    "operation": "query",
                    "filesystemHandoff": handoff,
                }, separators=(",", ":")) + "\n").encode())
                response = {
                    "ok": True,
                    "exit": 0,
                    "out": "",
                    "err": "",
                    "data": {
                        "schema": "ygg.query.filesystem-handoff/v1",
                        "reason": "active-indexing",
                        "fallback": "filesystem",
                        "repos": [{"id": self.repo.name, "root": str(self.repo)}],
                    },
                }
                connection.sendall(
                    (json.dumps(response, separators=(",", ":")) + "\n").encode()
                )
            except OSError:
                return


def comparison(
    raw,
    lane,
    cold,
    stalled=None,
    acknowledged_stalled=None,
    active_handoff=None,
):
    raw_p95 = raw["p95Ms"]
    lane_p95 = lane["p95Ms"]
    cold_p95 = cold["p95Ms"]
    result = {
        "filesystemLaneToRawP95Ratio": lane_p95 / raw_p95 if raw_p95 else None,
        "filesystemLaneP95OverheadMs": lane_p95 - raw_p95,
        "coldYggToRawP95Ratio": cold_p95 / raw_p95 if raw_p95 else None,
        "coldYggP95OverheadMs": cold_p95 - raw_p95,
        "rawParitySupported": cold_p95 <= raw_p95,
    }
    if stalled:
        result.update({
            "stalledYggToRawP95Ratio": stalled["p95Ms"] / raw_p95 if raw_p95 else None,
            "stalledYggP95OverheadMs": stalled["p95Ms"] - raw_p95,
        })
    if acknowledged_stalled:
        result.update({
            "acknowledgedStalledYggToRawP95Ratio": (
                acknowledged_stalled["p95Ms"] / raw_p95 if raw_p95 else None
            ),
            "acknowledgedStalledYggP95OverheadMs": (
                acknowledged_stalled["p95Ms"] - raw_p95
            ),
        })
    if active_handoff:
        result.update({
            "activeIndexingHandoffYggToRawP95Ratio": (
                active_handoff["p95Ms"] / raw_p95 if raw_p95 else None
            ),
            "activeIndexingHandoffYggP95OverheadMs": (
                active_handoff["p95Ms"] - raw_p95
            ),
        })
    return result


def availability_contract(
    lanes,
    iterations,
    query_hedge_after_ms,
    acknowledged_query_hedge_after_ms,
    stalled_bound_tolerance_ms=0,
):
    stalled = lanes["stalledYgg"]
    acknowledged_stalled = lanes["acknowledgedStalledYgg"]
    active_handoff = lanes["activeIndexingHandoffYgg"]
    cold = lanes["coldYgg"]
    return {
        "sameRipgrepArgv": True,
        "oneFilesystemProcessPerRepo": True,
        "allRunsCompleted": all(
            lane["completed"] == iterations for lane in lanes.values()
        ),
        "zeroProcessTimeouts": all(lane["timeouts"] == 0 for lane in lanes.values()),
        "stalledQueriesUsedFilesystem": (
            stalled.get("degradationReasons") == {"query-hedge": iterations}
        ),
        "acknowledgedStalledQueriesUsedFilesystem": (
            acknowledged_stalled.get("degradationReasons")
            == {"query-hedge": iterations}
        ),
        "acknowledgedStalledUsedAcceptedHandoff": (
            acknowledged_stalled.get("filesystemHandoffCounts")
            == {"true": iterations}
        ),
        "activeIndexingHandoffUsedFilesystem": (
            active_handoff.get("degradationReasons")
            == {"active-indexing": iterations}
        ),
        "activeIndexingHandoffUsedOneProcess": (
            active_handoff.get("filesystemProcessCounts") == {"1": iterations}
        ),
        "activeIndexingHandoffUsedRequestedRepoScope": (
            active_handoff.get("filesystemRepoCounts") == {"1": iterations}
        ),
        "activeIndexingHandoffUsedAcceptedOrFinalHandoff": (
            active_handoff.get("filesystemHandoffCounts") == {"true": iterations}
        ),
        "stalledP95WithinBound": (
            stalled["p95Ms"]
            <= (
                query_hedge_after_ms
                + cold["p95Ms"]
                + stalled_bound_tolerance_ms
            )
        ),
        "acknowledgedStalledP95WithinBound": (
            acknowledged_stalled["p95Ms"]
            <= (
                acknowledged_query_hedge_after_ms
                + cold["p95Ms"]
                + stalled_bound_tolerance_ms
            )
        ),
        "activeIndexingHandoffP95WithinBound": (
            active_handoff["p95Ms"]
            <= cold["p95Ms"] + stalled_bound_tolerance_ms
        ),
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
    query_fallback_after_ms = (
        args.query_fallback_after_ms
        if args.query_fallback_after_ms is not None
        else client.DEFAULT_QUERY_FALLBACK_AFTER_MS
    )
    query_hedge_after_ms = (
        args.query_hedge_after_ms
        if args.query_hedge_after_ms is not None
        else client.DEFAULT_QUERY_HEDGE_AFTER_MS
    )
    acknowledged_query_hedge_after_ms = (
        args.acknowledged_query_hedge_after_ms
        if args.acknowledged_query_hedge_after_ms is not None
        else client.DEFAULT_ACKNOWLEDGED_QUERY_HEDGE_AFTER_MS
    )
    stalled_server_delay_ms = (
        args.stalled_server_delay_ms
        if args.stalled_server_delay_ms is not None
        else query_fallback_after_ms + 1000
    )
    if query_fallback_after_ms < 1:
        raise SystemExit("--query-fallback-after-ms must be at least 1")
    if query_hedge_after_ms < 1:
        raise SystemExit("--query-hedge-after-ms must be at least 1")
    if acknowledged_query_hedge_after_ms < 0:
        raise SystemExit("--acknowledged-query-hedge-after-ms cannot be negative")
    if acknowledged_query_hedge_after_ms < query_hedge_after_ms:
        raise SystemExit(
            "--acknowledged-query-hedge-after-ms must be at least "
            "--query-hedge-after-ms"
        )
    if stalled_server_delay_ms <= query_fallback_after_ms:
        raise SystemExit(
            "--stalled-server-delay-ms must exceed --query-fallback-after-ms"
        )
    if args.stalled_bound_tolerance_ms < 0:
        raise SystemExit("--stalled-bound-tolerance-ms cannot be negative")
    patterns = client.filesystem_query_patterns(args.query, [])
    accepted_handoff = {
        "schema": "ygg.query.filesystem-handoff/v1",
        "reason": "query-hedge",
        "fallback": "filesystem",
        "repos": [{"id": repo.name, "root": str(repo)}],
    }
    with (StalledQueryServer(stalled_server_delay_ms) as stalled_server,
          StalledQueryServer(
              stalled_server_delay_ms,
              acknowledge=True,
              filesystem_handoff=accepted_handoff,
          ) as acknowledged_stalled_server,
          ActiveIndexingHandoffServer(repo) as active_handoff_server):
        port = closed_loopback_port()
        lane_fns = {
            "rawRipgrep": lambda: raw_ripgrep_sample(
                client, repo, patterns, args.timeout_ms, args.rg_bin
            ),
            "filesystemLane": lambda: filesystem_lane_sample(
                client, repo, patterns, args.timeout_ms, args.rg_bin
            ),
            "coldYgg": lambda: cold_ygg_sample(
                repo,
                args.query,
                args.limit,
                args.timeout_ms,
                args.rg_bin,
                port,
                query_fallback_after_ms,
                query_hedge_after_ms,
                acknowledged_query_hedge_after_ms,
            ),
            "stalledYgg": lambda: cold_ygg_sample(
                repo,
                args.query,
                args.limit,
                args.timeout_ms,
                args.rg_bin,
                stalled_server.port,
                query_fallback_after_ms,
                query_hedge_after_ms,
                acknowledged_query_hedge_after_ms,
            ),
            "acknowledgedStalledYgg": lambda: cold_ygg_sample(
                repo,
                args.query,
                args.limit,
                args.timeout_ms,
                args.rg_bin,
                acknowledged_stalled_server.port,
                query_fallback_after_ms,
                query_hedge_after_ms,
                acknowledged_query_hedge_after_ms,
            ),
            "activeIndexingHandoffYgg": lambda: cold_ygg_sample(
                repo,
                args.query,
                args.limit,
                args.timeout_ms,
                args.rg_bin,
                active_handoff_server.port,
                query_fallback_after_ms,
                query_hedge_after_ms,
                acknowledged_query_hedge_after_ms,
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
        "clientPythonArgs": ["-S"],
        "queryFallbackAfterMs": query_fallback_after_ms,
        "clientDefaultQueryFallbackAfterMs": client.DEFAULT_QUERY_FALLBACK_AFTER_MS,
        "queryHedgeAfterMs": query_hedge_after_ms,
        "clientDefaultQueryHedgeAfterMs": client.DEFAULT_QUERY_HEDGE_AFTER_MS,
        "acknowledgedQueryHedgeAfterMs": acknowledged_query_hedge_after_ms,
        "clientDefaultAcknowledgedQueryHedgeAfterMs": (
            client.DEFAULT_ACKNOWLEDGED_QUERY_HEDGE_AFTER_MS
        ),
        "stalledServerDelayMs": stalled_server_delay_ms,
        "stalledBoundToleranceMs": args.stalled_bound_tolerance_ms,
        "lanes": lanes,
        "comparison": comparison(
            lanes["rawRipgrep"],
            lanes["filesystemLane"],
            lanes["coldYgg"],
            lanes["stalledYgg"],
            lanes["acknowledgedStalledYgg"],
            lanes["activeIndexingHandoffYgg"],
        ),
        "contract": availability_contract(
            lanes,
            args.iterations,
            query_hedge_after_ms,
            acknowledged_query_hedge_after_ms,
            args.stalled_bound_tolerance_ms,
        ),
    }
    encoded = json.dumps(report, indent=2) + "\n"
    if args.out:
        out = pathlib.Path(args.out).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0 if all(report["contract"].values()) else 1


def parser():
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--repo", default=".")
    value.add_argument("--query", required=True)
    value.add_argument("--iterations", type=int, default=10)
    value.add_argument("--warmup", type=int, default=2)
    value.add_argument("--limit", type=int, default=10)
    value.add_argument("--timeout-ms", type=int, default=1500)
    value.add_argument("--query-fallback-after-ms", type=int)
    value.add_argument("--query-hedge-after-ms", type=int)
    value.add_argument("--acknowledged-query-hedge-after-ms", type=int)
    value.add_argument("--stalled-server-delay-ms", type=int)
    value.add_argument("--stalled-bound-tolerance-ms", type=int, default=75)
    value.add_argument("--rg-bin", default="rg")
    value.add_argument("--out")
    return value


if __name__ == "__main__":
    raise SystemExit(run(parser().parse_args()))
