# Contracts

## Configuration

The optional repository configuration is `.ygg/config.json`:

```json
{
  "schema": "ygg.config/v1",
  "ignoreGlobs": [],
  "maxFileBytes": 4194304,
  "plugins": [
    {
      "id": "markdown",
      "version": "0.1.0",
      "command": ["ygg-extract-markdown"],
      "includeGlobs": ["**/*.md"],
      "timeoutMs": 10000
    }
  ],
  "embedding": {
    "kind": "openai-compatible",
    "endpoint": "http://127.0.0.1:8080/v1/embeddings",
    "model": "local-model",
    "dimensions": 384,
    "apiKeyEnv": "YGG_EMBEDDING_API_KEY",
    "timeoutMs": 2000,
    "batchSize": 64
  }
}
```

Embedding `kind` is either `command` or `openai-compatible`. A command provider
uses `command` argv instead of `endpoint` and `apiKeyEnv`.

## Search records

The only canonical extracted row is a search record:

```json
{
  "id": "optional-plugin-local-id",
  "path": "docs/guide.md",
  "startLine": 10,
  "endLine": 24,
  "kind": "markdown-section",
  "title": "Indexing",
  "text": "Indexing writes bounded records...",
  "metadata": {}
}
```

Core owns repository identity, file hashes, run identity, record provenance,
and plugin fingerprints. Plugin values for those fields are rejected.

## Extractor JSONL

Core starts each configured extractor once per index run. Messages are one JSON
object per line.

Extractor and command-embedding entries execute the configured argv with the
repository as their working directory. They are trusted local executables, not
a sandbox boundary. Only configure commands you would run directly. Extractor
authors must change `version` whenever output behavior changes; that version is
part of the incremental extraction fingerprint.

Input:

```json
{"type":"hello","schema":"ygg.extractor/v1","plugin":{"id":"markdown","version":"0.1.0"},"repository":{"root":"/repo"}}
{"type":"file","requestId":"1","file":{"path":"README.md","kind":"md","contentHash":"sha256:...","content":"# Title\n"}}
{"type":"end"}
```

Output:

```json
{"type":"ready","schema":"ygg.extractor/v1"}
{"type":"result","requestId":"1","records":[],"diagnostics":[]}
{"type":"summary","files":1,"records":0}
```

## Embedding JSONL

Command embedding providers use the same lifecycle with schema
`ygg.embedding/v1`. `embed` inputs contain a correlated request id, model, and
an array of `{id,text}` inputs. Results contain `{id,vector}` values. Vector
length must equal the configured dimensions.

## JSON CLI envelopes

JSON responses have `schema`, `ok`, and either `data` or `error`. Search data
uses schema `ygg.search.result/v1` and includes the requested mode, active mode,
fallback reason, timings, and ranked records with path/line citations.
