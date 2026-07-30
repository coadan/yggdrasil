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

### Measured local evidence

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
local path and quantify its startup cost.

The complete 10-case claim suite then compared matched lexical, command-local,
and resident-local lanes on candidate
`sha256:ac3409cb03e8cdfe1043fdd0300b9e400bee4744025766f03a531171d27d35b3`
and suite
`sha256:8293905d96f46bb52948428cb42a24dda5e01331009db3887524e3ee3bb0b524`.
It covered seven repositories, nine source kinds, seven manually tagged problem
classes, and six architecture/audit classes:

| Lane | Recall@10 | MRR | Full index p50 | No-op p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Lexical | 0.810 | 0.821 | 223 ms | 17 ms | 26 ms | 17 ms | 116 ms |
| MiniLM command | 0.980 | 0.710 | 8,642 ms | 27 ms | 6,201 ms | 6,171 ms | 7,009 ms |
| MiniLM via Ollama | 0.980 | 0.710 | 4,521 ms | 23 ms | 71 ms | 41 ms | 196 ms |

Both semantic lanes had complete `47,724 / 47,724` vector coverage and identical
retrieval quality. The resident provider isolates the command lane's repeated
Python/model startup cost. On that candidate, hybrid recall found more expected
files but reduced MRR. Resident local MiniLM is the practical development lane;
command-local MiniLM remains the zero-daemon ablation.

On 2026-07-30, file-identity fusion was measured on the same ten-case suite with
candidate
`sha256:7a371dfddcd66777de69a8bfdfa61dd8f71b1cc6a11e0f443fd0e59d18698ab9`:

| Lane | Recall@10 | MRR | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: |
| Lexical | 0.830 | 0.790 | 90.95 ms | 256.09 ms |
| MiniLM via Ollama | 0.980 | 0.850 | 148.29 ms | 395.14 ms |

Against record-only hybrid fusion, the public suite gained `0.033` recall and
`0.007` MRR; the matched private eight-case dogfood suite retained recall
`0.292` while MRR increased from `0.417` to `0.438`. The algorithm preserves the
strongest record-level file, then combines cross-record lexical and semantic
evidence by file path. This supports hybrid as the balanced default for configured local
embeddings without claiming that semantic-only retrieval should win.

The larger `qwen3-embedding:4b` model was also exercised locally through Ollama
with 2,560-dimensional vectors, batch size 1, and 4,000-character inputs. A
single real Terraform architecture case reached complete `358 / 358` coverage,
recall@10 `1.0`, and MRR `0.5`, but full indexing took 152 seconds. Larger
inputs caused measured Ollama runner failures on this machine. This focused
negative result supports configurable local models, but not Qwen 4B as the
default or a broad benchmark lane.

## OpenRouter

OpenRouter remains a compatibility option, not the current development or
benchmark default. Local providers avoid credential, network, and hosted
first-index costs.

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
