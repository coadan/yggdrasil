# Local reranking worker

`ygg-rerank-local` serves a local yes/no reranking model over the persistent
`ygg.rerank/v1` command protocol. It scores each candidate as the model's
probability of answering "yes" to whether the excerpt is relevant to the
query. The model loads on the first request. Python, PyTorch, and model
dependencies stay outside the Go core.

The default backend expects a Qwen3-Reranker style causal language model, such
as `Qwen/Qwen3-Reranker-0.6B` (about 1.1 GB). It uses CUDA or Apple MPS in
float16 when available and the CPU in float32 otherwise.

Create an isolated environment:

```sh
python3.13 -m venv .dev/local-rerank-venv
.dev/local-rerank-venv/bin/pip install -r plugins/rerank-local/requirements.txt
```

Add the reranker to `~/.config/ygg/config.json`. Use absolute paths because the
command runs with the searched repository as its working directory:

```json
{
  "schema": "ygg.config/v1",
  "rerank": {
    "kind": "command",
    "command": [
      "/path/to/yggdrasil/.dev/local-rerank-venv/bin/python",
      "/path/to/yggdrasil/plugins/rerank-local/ygg-rerank-local"
    ],
    "model": "Qwen/Qwen3-Reranker-0.6B",
    "depth": 20,
    "maxInputChars": 1500,
    "timeoutMs": 60000
  }
}
```

The first search can download the model. A CLI search starts a fresh worker,
so each reranked search pays the Python and model start, about 2.5 to 3.5 s on
an Apple M-series laptop, plus about 0.4 s to score 20 candidates. Library
hosts that keep the provider alive pay the start once.

Environment variables tune the worker without changing scores' meaning:
`YGG_LOCAL_RERANK_BATCH_SIZE` (default 8), `YGG_LOCAL_RERANK_MAX_TOKENS`
(default 1024), and `YGG_LOCAL_RERANK_INSTRUCTION`. The
`term-overlap-test` backend is a dependency-free protocol fixture, not a
relevance model.
