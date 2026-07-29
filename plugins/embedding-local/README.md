# Local embedding worker

`ygg-embed-local` adapts any Sentence Transformers model to the persistent
`ygg.embedding/v1` command protocol. The model is loaded lazily on the first
non-empty batch, embeddings are normalized, and configured dimensions are
checked before results reach SQLite. Python and model dependencies remain
outside the Go core.

The worker and the `openai-compatible` provider do not impose a model-size
ceiling. Choose any model the configured process or local server can host and
set its actual output dimensions. Yggdrasil validates every returned vector
against that value; model selection and memory ownership remain outside core.

Create an isolated environment:

```sh
python3.13 -m venv .dev/local-embedding-venv
.dev/local-embedding-venv/bin/pip install \
  -r plugins/embedding-local/requirements.txt
```

Then use absolute paths because embedding commands run with the indexed
repository as their working directory:

```json
{
  "schema": "ygg.config/v1",
  "embedding": {
    "kind": "command",
    "command": [
      "/path/to/yggdrasil/.dev/local-embedding-venv/bin/python",
      "/path/to/yggdrasil/plugins/embedding-local/ygg-embed-local"
    ],
    "model": "sentence-transformers/all-MiniLM-L6-v2",
    "dimensions": 384,
    "timeoutMs": 60000,
    "batchSize": 64
  }
}
```

The first index can download the configured model. Later invocations reuse the
model cache, while the worker remains alive for every batch in one index or
search process. Set `YGG_LOCAL_EMBEDDING_BATCH_SIZE` to tune the model's encode
batch independently from Yggdrasil's request batch.

For the shortest repeated-search latency, point the core `openai-compatible`
provider at an already-running local embedding server. That keeps model
ownership outside Yggdrasil and avoids Python/model loading in each fresh CLI
process. The command worker is the self-contained option and makes its startup
cost measurable:

```sh
.dev/local-embedding-venv/bin/python \
  plugins/embedding-local/benchmark.py \
  --backend sentence-transformers \
  --model sentence-transformers/all-MiniLM-L6-v2 \
  --dimensions 384
```

The dependency-free `deterministic-test` backend exists only for protocol tests
and process-overhead benchmarks. It does not provide semantic embeddings.
