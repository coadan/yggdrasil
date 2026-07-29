# Legacy embedding parity

The search-focused implementation retains the useful semantic retrieval path
without retaining XTDB or provider SDKs.

| Capability | Current implementation |
| --- | --- |
| OpenRouter/OpenAI-compatible HTTP | `openai-compatible` provider with endpoint, model, dimensions, and API-key environment name |
| Local/custom model process | Persistent `ygg.embedding/v1` command provider |
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
