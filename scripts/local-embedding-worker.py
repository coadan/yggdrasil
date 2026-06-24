#!/usr/bin/env python3
"""JSONL local embedding worker for Yggdrasil.

The worker reads requests from stdin and writes one JSON response per line to
stdout. Each request has the shape {"inputs": ["..."]}; each response has
{"vectors": [[...]]} or {"error": "..."}.
"""

from __future__ import annotations

import json
import os
import sys


DEFAULT_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
DEFAULT_BATCH_SIZE = 64


def batch_size() -> int:
    raw = os.environ.get("YGG_LOCAL_EMBEDDING_BATCH_SIZE")
    if not raw:
        return DEFAULT_BATCH_SIZE
    try:
        return max(1, int(raw))
    except ValueError:
        return DEFAULT_BATCH_SIZE


def emit(value: dict) -> None:
    print(json.dumps(value, separators=(",", ":")), flush=True)


def load_model(model_name: str):
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        raise RuntimeError(
            "sentence-transformers is required for local embeddings. "
            "Install it with: ygg embed setup"
        ) from exc
    return SentenceTransformer(model_name)


def as_plain_vectors(encoded) -> list[list[float]]:
    if hasattr(encoded, "tolist"):
        encoded = encoded.tolist()
    return [[float(value) for value in row] for row in encoded]


def main(argv: list[str]) -> int:
    model_name = argv[1] if len(argv) > 1 else DEFAULT_MODEL
    model = None
    encode_batch_size = batch_size()

    for line in sys.stdin:
        try:
            request = json.loads(line)
            inputs = request.get("inputs")
            if not isinstance(inputs, list) or not all(isinstance(item, str) for item in inputs):
                emit({"error": "request must contain an inputs array of strings"})
                continue
            if not inputs:
                emit({"vectors": []})
                continue
            if model is None:
                model = load_model(model_name)
            encoded = model.encode(
                inputs,
                batch_size=encode_batch_size,
                normalize_embeddings=True,
                show_progress_bar=False,
            )
            emit({"vectors": as_plain_vectors(encoded)})
        except Exception as exc:
            emit({"error": str(exc)})
            return 3

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
