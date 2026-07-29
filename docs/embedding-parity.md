# Legacy embedding parity

The search-focused implementation retains the useful semantic retrieval path
without retaining XTDB or provider SDKs.

| Capability | Current implementation |
| --- | --- |
| OpenRouter/OpenAI-compatible HTTP | `openai-compatible` provider with endpoint, model, dimensions, and API-key environment name |
| Local/custom model process | Bundled Sentence Transformers worker over the persistent `ygg.embedding/v1` command provider |
| Incremental vectors | Input hashes and provider fingerprints skip current records |
| Vector persistence | SQLite Vec1 beside canonical search records |
| Default retrieval | `auto` fuses lexical/path and semantic candidates when coverage is complete |
| Explicit fallback | Unconfigured, incomplete, and provider-error states retain lexical results and report a reason |
| Provider bounds | 6,000-character inputs and 16 MiB responses |
| Remote resilience | One bounded retry with backoff for transport errors and HTTP 429, 529, and 5xx |
| Behavior invalidation | The provider behavior version participates in the embedding fingerprint |

The legacy global cross-project embedding cache has not been restored. Current
vectors are local to the canonical repository database, which keeps ownership
and invalidation simple but can repeat provider work when identical content
exists in multiple repositories. Provider auto-detection and `ygg embed setup`
are also not part of the reduced CLI surface.

## Local models

`plugins/embedding-local/ygg-embed-local` restores the useful legacy local
model path without restoring a setup command or Python dependencies in core.
It speaks the current correlated JSONL protocol, delays model import and load
until the first embedding batch, normalizes output, and rejects vectors that do
not match the configured dimensions.

The default legacy choice,
`sentence-transformers/all-MiniLM-L6-v2`, produces 384-dimensional vectors.
The model is now explicit rather than auto-detected. Installation and
absolute-path configuration are documented beside the worker.

For repeated fresh-process searches, an already-running local
OpenAI-compatible embedding endpoint avoids reloading Python and the model.
That uses the same provider as OpenRouter but keeps requests on the configured
local endpoint. The bundled command worker remains useful for a self-contained
workflow with no Yggdrasil-owned daemon.

### Measured local smoke

On 2026-07-29, Apple arm64 with Python 3.12,
`sentence-transformers==5.6.0`, and cached
`sentence-transformers/all-MiniLM-L6-v2` produced:

| Measurement | Result |
| --- | ---: |
| Worker handshake before model load | 18.636 ms |
| First 64-input batch in a fresh process | 6,379.066 ms |
| Warm 64-input batch p50, 20 batches | 20.585 ms |
| Warm 64-input batch p95, 20 batches | 21.574 ms |

The initial uncached run, including model download, took 39,121.366 ms for the
first batch. A real CLI smoke indexed three files and six records with six
vectors in 7,194 ms. Fresh-process `auto` search returned `activeMode: hybrid`,
with lexical-and-semantic citations, in 6,806 ms. These measurements prove the
local path and quantify its startup cost; they are not retrieval-quality
evidence.

## OpenRouter

Keep the credential in the environment; never put it in `.ygg/config.json`:

```json
{
  "schema": "ygg.config/v1",
  "embedding": {
    "kind": "openai-compatible",
    "endpoint": "https://openrouter.ai/api/v1/embeddings",
    "model": "openai/text-embedding-3-small",
    "dimensions": 1536,
    "apiKeyEnv": "YGG_OPENROUTER_API_KEY",
    "timeoutMs": 60000,
    "batchSize": 16
  }
}
```

If the legacy checkout remains the credential owner, it can be loaded only for
the current shell:

```sh
set -a
source /path/to/legacy-yggdrasil/.env
set +a
ygg index --root /path/to/repository
ygg search --root /path/to/repository --mode auto "where is auth handled"
```

## Verified smoke

On 2026-07-29, the legacy environment credential successfully called
OpenRouter's `/api/v1/embeddings` endpoint with
`openai/text-embedding-3-small`; the response contained one 1,536-dimensional
float vector.

The current CLI then indexed an isolated one-file Git fixture:

- one file and two records indexed;
- two vectors stored with complete coverage and no diagnostics;
- `auto` reported `activeMode: hybrid`;
- explicit `semantic` search returned a cited core record with
  `retrieval: ["semantic"]`.

This is a compatibility smoke test, not a retrieval-quality or cost benchmark.
The credential value was neither printed nor copied into this repository.
