#!/usr/bin/env python3
"""Compare raw ripgrep with Yggdrasil's degraded query path."""

import argparse
import importlib.util
import json
import math
import os
import pathlib
import select
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLIENT_PATH = ROOT / "scripts" / "ygg-server-client.py"
CLI_PATH = ROOT / "bin" / "ygg"
MCP_PATH = ROOT / "bin" / "ygg-mcp"
SCHEMA = "ygg.query-availability.benchmark/v12"


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
    filesystem_launcher_counts = {}
    for sample in samples:
        process_count = sample.get("filesystemProcesses")
        repo_count = sample.get("filesystemRepos")
        handoff = sample.get("filesystemHandoff")
        launcher = sample.get("filesystemLauncher")
        if process_count is not None:
            key = str(process_count)
            filesystem_process_counts[key] = filesystem_process_counts.get(key, 0) + 1
        if repo_count is not None:
            key = str(repo_count)
            filesystem_repo_counts[key] = filesystem_repo_counts.get(key, 0) + 1
        if handoff is not None:
            key = str(bool(handoff)).lower()
            filesystem_handoff_counts[key] = filesystem_handoff_counts.get(key, 0) + 1
        if launcher:
            filesystem_launcher_counts[launcher] = (
                filesystem_launcher_counts.get(launcher, 0) + 1
            )
    if filesystem_process_counts:
        summary["filesystemProcessCounts"] = filesystem_process_counts
    if filesystem_repo_counts:
        summary["filesystemRepoCounts"] = filesystem_repo_counts
    if filesystem_handoff_counts:
        summary["filesystemHandoffCounts"] = filesystem_handoff_counts
    if filesystem_launcher_counts:
        summary["filesystemLauncherCounts"] = filesystem_launcher_counts
    client_process_ids = {
        sample["clientProcessId"]
        for sample in samples
        if sample.get("clientProcessId") is not None
    }
    if client_process_ids:
        summary["clientProcessCount"] = len(client_process_ids)
        summary["clientProcessIds"] = sorted(client_process_ids)
    for sample_key, summary_key in [
        ("filesystemQueryTruncated", "filesystemQueryTruncatedCounts"),
        ("filesystemQueryCharacters", "filesystemQueryCharacterCounts"),
        ("filesystemDroppedPatternCount", "filesystemDroppedPatternCounts"),
        ("filesystemPatternMaxCharacters", "filesystemPatternMaxCharacterCounts"),
    ]:
        counts = {}
        for sample in samples:
            value = sample.get(sample_key)
            if value is not None:
                key = str(value).lower() if isinstance(value, bool) else str(value)
                counts[key] = counts.get(key, 0) + 1
        if counts:
            summary[summary_key] = counts
    for sample_key, summary_prefix in [
        ("filesystemSearchMs", "filesystemSearch"),
        ("filesystemTotalMs", "filesystemTotal"),
    ]:
        values = [
            sample[sample_key]
            for sample in samples
            if sample.get(sample_key) is not None
        ]
        if values:
            summary[f"{summary_prefix}P95Ms"] = percentile(values, 0.95)
            summary[f"{summary_prefix}MaxMs"] = max(values)
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
    argv = client.filesystem_query_argv(patterns, [str(repo)])
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
        "ripgrepArgv": argv,
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
        "ripgrepArgv": result["argv"],
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
        str(CLI_PATH),
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
    instrumentation = packet.get("search", {}).get("instrumentation", {})
    return {
        "elapsedMs": (time.monotonic() - started) * 1000.0,
        "completed": not timed_out and process.returncode == 0,
        "timeout": timed_out,
        "degradationReason": packet.get("degradation", {}).get("reason"),
        "filesystemProcesses": instrumentation.get("filesystem-processes"),
        "filesystemRepos": instrumentation.get("filesystem-repos"),
        "filesystemHandoff": instrumentation.get("filesystem-handoff?"),
        "filesystemLauncher": instrumentation.get("filesystem-process-launcher"),
        "filesystemSearchMs": instrumentation.get("filesystem-search-ms"),
        "filesystemTotalMs": instrumentation.get("filesystem-total-ms"),
    }


class PersistentMcpClient:
    def __init__(
        self,
        repo,
        port,
        timeout_ms,
        rg_bin,
        query_fallback_after_ms,
        query_hedge_after_ms,
        acknowledged_query_hedge_after_ms,
    ):
        self.repo = pathlib.Path(repo)
        self.response_timeout_ms = max(
            timeout_ms,
            query_fallback_after_ms,
            query_hedge_after_ms,
            acknowledged_query_hedge_after_ms,
        ) + 2000
        self.env = os.environ.copy()
        self.env.update({
            "YGG_SERVER_HOST": "127.0.0.1",
            "YGG_SERVER_PORT": str(port),
            "YGG_SERVER_CONNECT_TIMEOUT_MS": "0",
            "YGG_SERVER_STARTING_RETRY_TIMEOUT_MS": "0",
            "YGG_FILESYSTEM_QUERY_TIMEOUT_MS": str(timeout_ms),
            "YGG_RG_BIN": rg_bin,
            "YGG_QUERY_AUTO_START": "0",
            "YGG_QUERY_FALLBACK_AFTER_MS": str(query_fallback_after_ms),
            "YGG_QUERY_HEDGE_AFTER_MS": str(query_hedge_after_ms),
            "YGG_QUERY_ACKNOWLEDGED_HEDGE_AFTER_MS": str(
                acknowledged_query_hedge_after_ms
            ),
        })
        self.process = None
        self.next_id = 1
        self.startup = None

    def __enter__(self):
        started = time.monotonic()
        self.process = subprocess.Popen(
            [str(MCP_PATH)],
            cwd=str(self.repo),
            env=self.env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        try:
            initialize = self.exchange("initialize", {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {
                    "name": "ygg-query-availability-benchmark",
                    "version": "1",
                },
            })
            self.notify("notifications/initialized", {})
            listed = self.exchange("tools/list", {})
            tool_names = {
                tool.get("name")
                for tool in listed.get("result", {}).get("tools", [])
            }
            completed = (
                initialize.get("result", {}).get("serverInfo", {}).get("name")
                == "ygg-mcp"
                and "ygg_query" in tool_names
            )
            self.startup = {
                "elapsedMs": (time.monotonic() - started) * 1000.0,
                "completed": completed,
                "timeout": False,
                "clientProcessId": self.process.pid,
            }
            if not completed:
                raise RuntimeError(
                    "persistent MCP client did not initialize ygg_query"
                )
            return self
        except BaseException:
            self.stop()
            raise

    def __exit__(self, *_args):
        self.stop()

    def stop(self):
        if self.process is None:
            return
        if self.process.stdin is not None:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()

    def write_message(self, message):
        if self.process is None or self.process.stdin is None:
            raise RuntimeError("persistent MCP client is not running")
        self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self.process.stdin.flush()

    def read_response(self, request_id):
        if self.process is None or self.process.stdout is None:
            raise RuntimeError("persistent MCP client is not running")
        deadline = time.monotonic() + self.response_timeout_ms / 1000.0
        while time.monotonic() < deadline:
            remaining = max(0.0, deadline - time.monotonic())
            ready, _write, _error = select.select(
                [self.process.stdout],
                [],
                [],
                remaining,
            )
            if not ready:
                break
            line = self.process.stdout.readline()
            if not line:
                break
            response = json.loads(line)
            if response.get("id") == request_id:
                return response
        raise TimeoutError(f"persistent MCP response {request_id} timed out")

    def exchange(self, method, params):
        request_id = self.next_id
        self.next_id += 1
        self.write_message({
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params,
        })
        return self.read_response(request_id)

    def notify(self, method, params):
        self.write_message({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        })

    def sample(self, query):
        started = time.monotonic()
        response = {}
        timed_out = False
        try:
            response = self.exchange("tools/call", {
                "name": "ygg_query",
                "arguments": {"query": query},
            })
        except TimeoutError:
            timed_out = True
        packet = response.get("result", {}).get("structuredContent", {})
        instrumentation = packet.get("search", {}).get("instrumentation", {})
        return {
            "elapsedMs": (time.monotonic() - started) * 1000.0,
            "completed": (
                not timed_out
                and "error" not in response
                and packet.get("schema") == "ygg.query/v2"
            ),
            "timeout": timed_out,
            "degradationReason": packet.get("degradation", {}).get("reason"),
            "filesystemProcesses": instrumentation.get("filesystem-processes"),
            "filesystemRepos": instrumentation.get("filesystem-repos"),
            "filesystemHandoff": instrumentation.get("filesystem-handoff?"),
            "filesystemLauncher": instrumentation.get(
                "filesystem-process-launcher"
            ),
            "filesystemSearchMs": instrumentation.get("filesystem-search-ms"),
            "filesystemTotalMs": instrumentation.get("filesystem-total-ms"),
            "filesystemQueryTruncated": instrumentation.get(
                "filesystem-query-truncated?"
            ),
            "filesystemQueryCharacters": len(packet.get("query") or ""),
            "filesystemDroppedPatternCount": instrumentation.get(
                "filesystem-dropped-pattern-count"
            ),
            "filesystemPatternMaxCharacters": max(
                [
                    len(pattern)
                    for pattern in instrumentation.get("filesystem-patterns", [])
                ]
                or [0]
            ),
            "clientProcessId": self.process.pid,
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


class ActiveWorkHandoffServer:
    def __init__(self, repo, reason):
        self.repo = pathlib.Path(repo).resolve()
        self.reason = reason
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
                    "reason": self.reason,
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
                        "reason": self.reason,
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
    persistent_mcp=None,
    persistent_mcp_active_handoff=None,
    persistent_mcp_embedding_handoff=None,
    persistent_mcp_oversized_repeated=None,
    persistent_mcp_oversized_single=None,
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
    if raw.get("maxMs") is not None:
        raw_max = raw["maxMs"]
        result.update({
            "filesystemLaneMaxOverheadMs": lane["maxMs"] - raw_max,
            "coldYggMaxOverheadMs": cold["maxMs"] - raw_max,
            "rawMaxParitySupported": cold["maxMs"] <= raw_max,
        })
    if stalled:
        result.update({
            "stalledYggToRawP95Ratio": stalled["p95Ms"] / raw_p95 if raw_p95 else None,
            "stalledYggP95OverheadMs": stalled["p95Ms"] - raw_p95,
        })
        if raw.get("maxMs") is not None:
            result["stalledYggMaxOverheadMs"] = stalled["maxMs"] - raw["maxMs"]
    if acknowledged_stalled:
        result.update({
            "acknowledgedStalledYggToRawP95Ratio": (
                acknowledged_stalled["p95Ms"] / raw_p95 if raw_p95 else None
            ),
            "acknowledgedStalledYggP95OverheadMs": (
                acknowledged_stalled["p95Ms"] - raw_p95
            ),
        })
        if raw.get("maxMs") is not None:
            result["acknowledgedStalledYggMaxOverheadMs"] = (
                acknowledged_stalled["maxMs"] - raw["maxMs"]
            )
    if active_handoff:
        result.update({
            "activeIndexingHandoffYggToRawP95Ratio": (
                active_handoff["p95Ms"] / raw_p95 if raw_p95 else None
            ),
            "activeIndexingHandoffYggP95OverheadMs": (
                active_handoff["p95Ms"] - raw_p95
            ),
        })
        if raw.get("maxMs") is not None:
            result["activeIndexingHandoffYggMaxOverheadMs"] = (
                active_handoff["maxMs"] - raw["maxMs"]
            )
    if persistent_mcp:
        result.update({
            "persistentMcpToRawP95Ratio": (
                persistent_mcp["p95Ms"] / raw_p95 if raw_p95 else None
            ),
            "persistentMcpP95OverheadMs": persistent_mcp["p95Ms"] - raw_p95,
            "persistentMcpToFilesystemLaneP95Ratio": (
                persistent_mcp["p95Ms"] / lane_p95 if lane_p95 else None
            ),
            "persistentMcpP95OverFilesystemLaneMs": (
                persistent_mcp["p95Ms"] - lane_p95
            ),
            "persistentMcpRawParitySupported": (
                persistent_mcp["p95Ms"] <= raw_p95
            ),
        })
        if raw.get("maxMs") is not None:
            result.update({
                "persistentMcpMaxOverheadMs": (
                    persistent_mcp["maxMs"] - raw["maxMs"]
                ),
                "persistentMcpRawMaxParitySupported": (
                    persistent_mcp["maxMs"] <= raw["maxMs"]
                ),
            })
    if persistent_mcp_active_handoff:
        result["persistentMcpActiveIndexingP95OverheadMs"] = (
            persistent_mcp_active_handoff["p95Ms"] - raw_p95
        )
    if persistent_mcp_embedding_handoff:
        result["persistentMcpActiveEmbeddingP95OverheadMs"] = (
            persistent_mcp_embedding_handoff["p95Ms"] - raw_p95
        )
    if persistent_mcp_oversized_repeated and persistent_mcp_oversized_single:
        result.update({
            "persistentMcpOversizedRepeatedP95OverNormalMs": (
                persistent_mcp_oversized_repeated["p95Ms"]
                - persistent_mcp["p95Ms"]
            ),
            "persistentMcpOversizedSingleP95OverNormalMs": (
                persistent_mcp_oversized_single["p95Ms"]
                - persistent_mcp["p95Ms"]
            ),
        })
    return result


def availability_contract(
    lanes,
    iterations,
    query_hedge_after_ms,
    acknowledged_query_hedge_after_ms,
    stalled_bound_tolerance_ms=0,
    same_ripgrep_argv=True,
    filesystem_timeout_ms=1500,
    zero_retry_connect_attempt_timeout_ms=5,
    mcp_startups=None,
    query_scan_character_limit=4096,
    pattern_character_limit=1024,
):
    stalled = lanes["stalledYgg"]
    acknowledged_stalled = lanes["acknowledgedStalledYgg"]
    active_handoff = lanes["activeIndexingHandoffYgg"]
    persistent_mcp = lanes["persistentMcpCold"]
    persistent_mcp_active = lanes["persistentMcpActiveIndexingHandoff"]
    persistent_mcp_embedding = lanes["persistentMcpActiveEmbeddingHandoff"]
    persistent_mcp_oversized_repeated = lanes["persistentMcpOversizedRepeated"]
    persistent_mcp_oversized_single = lanes["persistentMcpOversizedSingle"]
    cold = lanes["coldYgg"]
    cold_filesystem_max_ms = cold.get("filesystemTotalMaxMs")
    external_names = [
        "coldYgg",
        "stalledYgg",
        "acknowledgedStalledYgg",
        "activeIndexingHandoffYgg",
        "persistentMcpCold",
        "persistentMcpActiveIndexingHandoff",
        "persistentMcpActiveEmbeddingHandoff",
        "persistentMcpOversizedRepeated",
        "persistentMcpOversizedSingle",
    ]
    return {
        "sameRipgrepArgv": same_ripgrep_argv,
        "zeroRetryConnectAttemptWithinFiveMs": (
            0 < zero_retry_connect_attempt_timeout_ms <= 5
        ),
        "oneFilesystemProcessPerFallback": all(
            lanes[name].get("filesystemProcessCounts") == {"1": iterations}
            for name in external_names
        ),
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
        "persistentMcpHandshakeCompleted": (
            set(mcp_startups or {}) == {"cold", "activeIndexing", "activeEmbedding"}
            and all(
                startup.get("completed") and not startup.get("timeout")
                for startup in mcp_startups.values()
            )
        ),
        "persistentMcpStayedInOneClientProcess": (
            persistent_mcp.get("clientProcessCount") == 1
            and persistent_mcp_active.get("clientProcessCount") == 1
            and persistent_mcp_embedding.get("clientProcessCount") == 1
        ),
        "persistentMcpOversizedQueriesReusedColdClientProcess": (
            persistent_mcp.get("clientProcessIds")
            == persistent_mcp_oversized_repeated.get("clientProcessIds")
            == persistent_mcp_oversized_single.get("clientProcessIds")
            and persistent_mcp.get("clientProcessCount") == 1
        ),
        "persistentMcpColdUsedFilesystem": (
            persistent_mcp.get("degradationReasons")
            == {"server-unavailable": iterations}
        ),
        "persistentMcpActiveIndexingUsedFilesystem": (
            persistent_mcp_active.get("degradationReasons")
            == {"active-indexing": iterations}
        ),
        "persistentMcpActiveIndexingUsedRequestedRepoScope": (
            persistent_mcp_active.get("filesystemRepoCounts")
            == {"1": iterations}
            and persistent_mcp_active.get("filesystemHandoffCounts")
            == {"true": iterations}
        ),
        "persistentMcpActiveEmbeddingUsedFilesystem": (
            persistent_mcp_embedding.get("degradationReasons")
            == {"active-embedding": iterations}
        ),
        "persistentMcpActiveEmbeddingUsedRequestedRepoScope": (
            persistent_mcp_embedding.get("filesystemRepoCounts")
            == {"1": iterations}
            and persistent_mcp_embedding.get("filesystemHandoffCounts")
            == {"true": iterations}
        ),
        "persistentMcpOversizedQueriesUsedFilesystem": (
            persistent_mcp_oversized_repeated.get("degradationReasons")
            == {"server-unavailable": iterations}
            and persistent_mcp_oversized_single.get("degradationReasons")
            == {"server-unavailable": iterations}
        ),
        "persistentMcpOversizedQueriesReportedBounds": (
            persistent_mcp_oversized_repeated.get(
                "filesystemQueryTruncatedCounts"
            ) == {"true": iterations}
            and persistent_mcp_oversized_single.get(
                "filesystemQueryTruncatedCounts"
            ) == {"true": iterations}
            and persistent_mcp_oversized_repeated.get(
                "filesystemQueryCharacterCounts"
            ) == {str(query_scan_character_limit): iterations}
            and persistent_mcp_oversized_single.get(
                "filesystemQueryCharacterCounts"
            ) == {str(query_scan_character_limit): iterations}
            and persistent_mcp_oversized_repeated.get(
                "filesystemDroppedPatternCounts"
            ) == {"0": iterations}
            and persistent_mcp_oversized_single.get(
                "filesystemDroppedPatternCounts"
            ) == {"1": iterations}
            and persistent_mcp_oversized_single.get(
                "filesystemPatternMaxCharacterCounts"
            ) == {str(pattern_character_limit): iterations}
        ),
        "filesystemFallbackUsedPosixSpawn": all(
            lanes[name].get("filesystemLauncherCounts")
            == {"posix-spawn": iterations}
            for name in external_names
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
        "persistentMcpP95WithinColdCliBound": (
            persistent_mcp["p95Ms"]
            <= cold["p95Ms"] + stalled_bound_tolerance_ms
        ),
        "persistentMcpActiveIndexingP95WithinBound": (
            persistent_mcp_active["p95Ms"]
            <= persistent_mcp["p95Ms"] + stalled_bound_tolerance_ms
        ),
        "persistentMcpActiveEmbeddingP95WithinBound": (
            persistent_mcp_embedding["p95Ms"]
            <= persistent_mcp["p95Ms"] + stalled_bound_tolerance_ms
        ),
        "persistentMcpOversizedP95WithinBound": (
            persistent_mcp_oversized_repeated["p95Ms"]
            <= persistent_mcp["p95Ms"] + stalled_bound_tolerance_ms
            and persistent_mcp_oversized_single["p95Ms"]
            <= persistent_mcp["p95Ms"] + stalled_bound_tolerance_ms
        ),
        "filesystemWorkMaxWithinDeadline": (
            filesystem_timeout_ms > 0
            and all(
                lanes[name].get("filesystemTotalMaxMs") is not None
                and lanes[name]["filesystemTotalMaxMs"] <= filesystem_timeout_ms
                for name in external_names
            )
        ),
        "filesystemWorkMaxWithinColdBound": (
            cold_filesystem_max_ms is not None
            and all(
                lanes[name].get("filesystemTotalMaxMs") is not None
                and lanes[name]["filesystemTotalMaxMs"]
                <= cold_filesystem_max_ms + stalled_bound_tolerance_ms
                for name in [
                    "stalledYgg",
                    "acknowledgedStalledYgg",
                    "activeIndexingHandoffYgg",
                    "persistentMcpCold",
                    "persistentMcpActiveIndexingHandoff",
                    "persistentMcpActiveEmbeddingHandoff",
                    "persistentMcpOversizedRepeated",
                    "persistentMcpOversizedSingle",
                ]
            )
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
    if args.oversized_query_characters <= client.DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT:
        raise SystemExit(
            "--oversized-query-characters must exceed the filesystem query scan limit"
        )
    patterns = client.filesystem_query_patterns(args.query, [])
    oversized_repeated_query = (
        "token123 "
        * math.ceil(args.oversized_query_characters / len("token123 "))
    )[:args.oversized_query_characters]
    oversized_single_query = "a" * args.oversized_query_characters
    accepted_handoff = {
        "schema": "ygg.query.filesystem-handoff/v1",
        "reason": "query-hedge",
        "fallback": "filesystem",
        "repos": [{"id": repo.name, "root": str(repo)}],
    }
    port = closed_loopback_port()
    with (StalledQueryServer(stalled_server_delay_ms) as stalled_server,
          StalledQueryServer(
              stalled_server_delay_ms,
              acknowledge=True,
              filesystem_handoff=accepted_handoff,
          ) as acknowledged_stalled_server,
          ActiveWorkHandoffServer(
              repo,
              "active-indexing",
          ) as active_handoff_server,
          ActiveWorkHandoffServer(
              repo,
              "active-embedding",
          ) as embedding_handoff_server,
          PersistentMcpClient(
              repo,
              port,
              args.timeout_ms,
              args.rg_bin,
              query_fallback_after_ms,
              query_hedge_after_ms,
              acknowledged_query_hedge_after_ms,
          ) as persistent_mcp,
          PersistentMcpClient(
              repo,
              active_handoff_server.port,
              args.timeout_ms,
              args.rg_bin,
              query_fallback_after_ms,
              query_hedge_after_ms,
              acknowledged_query_hedge_after_ms,
          ) as persistent_mcp_active,
          PersistentMcpClient(
              repo,
              embedding_handoff_server.port,
              args.timeout_ms,
              args.rg_bin,
              query_fallback_after_ms,
              query_hedge_after_ms,
              acknowledged_query_hedge_after_ms,
          ) as persistent_mcp_embedding):
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
            "persistentMcpCold": lambda: persistent_mcp.sample(args.query),
            "persistentMcpActiveIndexingHandoff": lambda: (
                persistent_mcp_active.sample(args.query)
            ),
            "persistentMcpActiveEmbeddingHandoff": lambda: (
                persistent_mcp_embedding.sample(args.query)
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
        oversized_lane_fns = {
            "persistentMcpOversizedRepeated": lambda: persistent_mcp.sample(
                oversized_repeated_query
            ),
            "persistentMcpOversizedSingle": lambda: persistent_mcp.sample(
                oversized_single_query
            ),
        }
        oversized_lane_names = list(oversized_lane_fns)
        for index in range(args.warmup):
            for offset in range(len(oversized_lane_names)):
                lane = oversized_lane_names[
                    (index + offset) % len(oversized_lane_names)
                ]
                oversized_lane_fns[lane]()
        samples.update({lane: [] for lane in oversized_lane_names})
        for index in range(args.iterations):
            for offset in range(len(oversized_lane_names)):
                lane = oversized_lane_names[
                    (index + offset) % len(oversized_lane_names)
                ]
                samples[lane].append(oversized_lane_fns[lane]())
        mcp_startups = {
            "cold": persistent_mcp.startup,
            "activeIndexing": persistent_mcp_active.startup,
            "activeEmbedding": persistent_mcp_embedding.startup,
        }
    lanes = {lane: summarize(rows) for lane, rows in samples.items()}
    raw_ripgrep_argvs = {
        tuple(sample["ripgrepArgv"])
        for sample in samples["rawRipgrep"]
    }
    filesystem_lane_argvs = {
        tuple(sample["ripgrepArgv"])
        for sample in samples["filesystemLane"]
    }
    same_ripgrep_argv = (
        len(raw_ripgrep_argvs) == 1
        and raw_ripgrep_argvs == filesystem_lane_argvs
    )
    report = {
        "schema": SCHEMA,
        "repo": str(repo),
        "query": args.query,
        "patterns": patterns,
        "ripgrepArgv": list(next(iter(raw_ripgrep_argvs), ())),
        "iterations": args.iterations,
        "warmup": args.warmup,
        "timeoutMs": args.timeout_ms,
        "clientEntrypoint": str(CLI_PATH),
        "mcpClientEntrypoint": str(MCP_PATH),
        "clientPythonArgs": ["-S"],
        "queryConnectAttemptTimeoutMs": (
            client.DEFAULT_ZERO_RETRY_CONNECT_ATTEMPT_TIMEOUT_MS
        ),
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
        "oversizedQueryCharacters": args.oversized_query_characters,
        "filesystemQueryScanCharacterLimit": (
            client.DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT
        ),
        "filesystemPatternCharacterLimit": (
            client.DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT
        ),
        "mcpStartup": mcp_startups,
        "lanes": lanes,
        "comparison": comparison(
            lanes["rawRipgrep"],
            lanes["filesystemLane"],
            lanes["coldYgg"],
            lanes["stalledYgg"],
            lanes["acknowledgedStalledYgg"],
            lanes["activeIndexingHandoffYgg"],
            lanes["persistentMcpCold"],
            lanes["persistentMcpActiveIndexingHandoff"],
            lanes["persistentMcpActiveEmbeddingHandoff"],
            lanes["persistentMcpOversizedRepeated"],
            lanes["persistentMcpOversizedSingle"],
        ),
        "contract": availability_contract(
            lanes,
            args.iterations,
            query_hedge_after_ms,
            acknowledged_query_hedge_after_ms,
            args.stalled_bound_tolerance_ms,
            same_ripgrep_argv,
            args.timeout_ms,
            client.DEFAULT_ZERO_RETRY_CONNECT_ATTEMPT_TIMEOUT_MS,
            mcp_startups,
            client.DEFAULT_FILESYSTEM_QUERY_SCAN_CHARACTER_LIMIT,
            client.DEFAULT_FILESYSTEM_PATTERN_CHARACTER_LIMIT,
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
    value.add_argument("--oversized-query-characters", type=int, default=1000000)
    value.add_argument("--rg-bin", default="rg")
    value.add_argument("--out")
    return value


if __name__ == "__main__":
    raise SystemExit(run(parser().parse_args()))
