# Local reranking

Reranking runs either through this repository's command worker or through an
already-running rerank server. The server avoids starting Python and loading
the model in every CLI search.

## Rerank server

llama.cpp's `llama-server` serves `/v1/rerank` with a GGUF reranking model.
Homebrew's `llama.cpp` package and the server bundled with recent Ollama
releases both support it:

```sh
llama-server -m /path/to/qwen3-reranker-0.6b-q8_0.gguf --reranking \
  --host 127.0.0.1 --port 8089 -ngl 99 -c 16384 -b 2048 -ub 2048
```

The model is `qwen3-reranker-0.6b-q8_0.gguf` from
`ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF` (639 MB). Then configure:

```json
{
  "schema": "ygg.config/v1",
  "rerank": {
    "kind": "http",
    "endpoint": "http://127.0.0.1:8089/v1/rerank",
    "model": "qwen3-reranker-0.6b-q8_0",
    "timeoutMs": 30000
  }
}
```

On an Apple M-series laptop a reranked search then takes about 0.9 s instead
of about 4 s, with the same retrieval quality as the command worker. When the
server is not running, searches report `rerank.state` `failed` and keep fused
order. Keeping the server running, for example as a launchd agent, is up to
the host.

## Command worker

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
