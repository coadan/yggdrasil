#!/usr/bin/env python3
"""Measure local embedding worker process and batch latency as JSON."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import time


def send(process, value):
    started = time.perf_counter_ns()
    process.stdin.write(json.dumps(value, separators=(",", ":")) + "\n")
    process.stdin.flush()
    line = process.stdout.readline()
    if not line:
        raise RuntimeError(process.stderr.read().strip() or "embedding worker exited")
    response = json.loads(line)
    return response, (time.perf_counter_ns() - started) / 1_000_000


def percentile(values, fraction):
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(len(ordered) * fraction))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--backend",
        choices=("deterministic-test", "sentence-transformers"),
        default="sentence-transformers",
    )
    parser.add_argument(
        "--model", default="sentence-transformers/all-MiniLM-L6-v2"
    )
    parser.add_argument("--dimensions", type=int, default=384)
    parser.add_argument("--inputs", type=int, default=64)
    parser.add_argument("--warm-batches", type=int, default=5)
    arguments = parser.parse_args()
    if arguments.dimensions <= 0 or arguments.inputs <= 0 or arguments.warm_batches <= 0:
        parser.error("--dimensions, --inputs, and --warm-batches must be positive")

    worker = pathlib.Path(__file__).with_name("ygg-embed-local")
    environment = dict(os.environ)
    environment["YGG_LOCAL_EMBEDDING_BACKEND"] = arguments.backend
    process_started = time.perf_counter_ns()
    process = subprocess.Popen(
        [sys.executable, str(worker)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=environment,
    )
    spawned_ms = (time.perf_counter_ns() - process_started) / 1_000_000
    ready, handshake_ms = send(
        process,
        {
            "type": "hello",
            "schema": "ygg.embedding/v1",
            "model": arguments.model,
            "dimensions": arguments.dimensions,
        },
    )
    if ready.get("type") != "ready":
        raise RuntimeError(f"unexpected handshake response: {ready!r}")

    inputs = [
        {"id": str(index), "text": f"bounded benchmark input {index}"}
        for index in range(arguments.inputs)
    ]
    first, first_batch_ms = send(
        process,
        {
            "type": "embed",
            "requestId": "first",
            "model": arguments.model,
            "inputs": inputs,
        },
    )
    if first.get("type") != "result":
        raise RuntimeError(f"unexpected first response: {first!r}")

    warm_ms = []
    for index in range(arguments.warm_batches):
        result, duration = send(
            process,
            {
                "type": "embed",
                "requestId": f"warm-{index}",
                "model": arguments.model,
                "inputs": inputs,
            },
        )
        if result.get("type") != "result":
            raise RuntimeError(f"unexpected warm response: {result!r}")
        warm_ms.append(duration)

    summary, close_ms = send(process, {"type": "end"})
    process.stdin.close()
    return_code = process.wait(timeout=5)
    if return_code != 0:
        raise RuntimeError(process.stderr.read())
    if summary.get("type") != "summary":
        raise RuntimeError(f"unexpected summary response: {summary!r}")

    print(
        json.dumps(
            {
                "schema": "ygg.embedding.benchmark/v1",
                "backend": arguments.backend,
                "model": arguments.model,
                "dimensions": arguments.dimensions,
                "inputsPerBatch": arguments.inputs,
                "warmBatches": arguments.warm_batches,
                "processSpawnMs": round(spawned_ms, 3),
                "handshakeMs": round(handshake_ms, 3),
                "firstBatchMs": round(first_batch_ms, 3),
                "warmBatchP50Ms": round(percentile(warm_ms, 0.50), 3),
                "warmBatchP95Ms": round(percentile(warm_ms, 0.95), 3),
                "closeMs": round(close_ms, 3),
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
