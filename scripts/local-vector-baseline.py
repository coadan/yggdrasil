#!/usr/bin/env python3
"""Local semantic-vector benchmark baseline for AGraph issue replay.

This script is intentionally a benchmark worker, not AGraph core. It uses a
local sentence-transformers model to rank files by cosine similarity to the
issue text and writes the standard agent-result JSON shape.
"""

from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path


DEFAULT_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
MAX_FILE_BYTES = 256_000
MAX_TEXT_CHARS = 24_000
MAX_FILES = 20_000
ENCODE_BATCH_SIZE = 64
SKIP_DIRS = {
    ".git",
    ".dev",
    ".hg",
    ".svn",
    ".cache",
    ".next",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "target",
}


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(value, f, indent=2)


def issue_query(request: dict) -> str:
    input_data = request.get("input") or {}
    parts = [
        input_data.get("title") or "",
        input_data.get("body") or "",
        "\n\n".join(input_data.get("comments") or []),
    ]
    return "\n\n".join(part for part in parts if part.strip())


def readable_text(path: Path) -> str | None:
    try:
        data = path.read_bytes()[:MAX_FILE_BYTES]
    except OSError:
        return None
    if b"\x00" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        try:
            return data.decode("latin-1")
        except UnicodeDecodeError:
            return None


def candidate_files(root: Path) -> list[tuple[str, str]]:
    candidates: list[tuple[str, str]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in SKIP_DIRS]
        for filename in sorted(filenames):
            path = Path(dirpath) / filename
            rel = path.relative_to(root).as_posix()
            text = readable_text(path)
            if text is None or not text.strip():
                continue
            candidates.append((rel, f"{rel}\n{text[:MAX_TEXT_CHARS]}"))
            if len(candidates) >= MAX_FILES:
                return candidates
    return candidates


def dot(a, b) -> float:
    return float(sum(x * y for x, y in zip(a, b)))


def norm(v) -> float:
    return math.sqrt(dot(v, v))


def cosine(a, b) -> float:
    denom = norm(a) * norm(b)
    if denom == 0.0:
        return 0.0
    return dot(a, b) / denom


def confidence(score: float) -> float:
    return max(0.0, min(1.0, (score + 1.0) / 2.0))


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(
            "usage: local-vector-baseline.py REQUEST_JSON RESULT_JSON [MODEL]",
            file=sys.stderr,
        )
        return 2

    request_path = Path(argv[1])
    result_path = Path(argv[2])
    model_name = argv[3] if len(argv) > 3 else DEFAULT_MODEL
    request = read_json(request_path)
    root = Path(request["worktreeRoot"])
    query = issue_query(request)
    limit = int(request.get("limit") or 20)
    files = candidate_files(root)

    try:
        from sentence_transformers import SentenceTransformer
    except ImportError:
        print(
            "sentence-transformers is required for the local-vector benchmark worker. "
            "Install it with: python3 -m pip install sentence-transformers",
            file=sys.stderr,
        )
        return 3

    model = SentenceTransformer(model_name)
    encoded_query = model.encode(
        [query],
        normalize_embeddings=True,
        show_progress_bar=False,
    )[0]
    encoded_docs = model.encode(
        [text for _, text in files],
        batch_size=ENCODE_BATCH_SIZE,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    ranked = sorted(
        ((rel, cosine(encoded_query, vector)) for (rel, _), vector in zip(files, encoded_docs)),
        key=lambda item: (-item[1], item[0]),
    )
    suspected_files = [
        {
            "path": rel,
            "rank": idx + 1,
            "confidence": confidence(score),
            "reason": f"Local vector cosine match with {model_name}.",
            "evidence": [
                f"local-vector:{model_name}",
                f"cosine:{score:.6f}",
            ],
            "metrics": {"cosine": score, "model": model_name},
        }
        for idx, (rel, score) in enumerate(ranked[:limit])
    ]
    result = {
        "schema": "agraph.benchmark.agent-result/v2",
        "caseId": request.get("caseId"),
        "caseFingerprint": request.get("caseFingerprint"),
        "agentId": request.get("agentId") or "agraph-baseline-local-vector",
        "mode": "local-vector",
        "suspectedFiles": suspected_files,
        "suspectedSymbols": [],
        "commands": [f"local-vector-baseline.py {model_name}"],
        "warnings": [],
        "summary": (
            f"Local vector baseline ranked {len(suspected_files)} files "
            f"from {len(files)} readable candidates."
        ),
    }
    if request.get("agentInputFingerprint"):
        result["agentInputFingerprint"] = request.get("agentInputFingerprint")
    write_json(result_path, result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
