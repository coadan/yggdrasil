# Contracts

## Configuration

The user configuration at `~/.config/ygg/config.json` owns the default
embedding provider shared by repositories and linked worktrees:

```json
{
  "schema": "ygg.config/v1",
  "embedding": {
    "kind": "openai-compatible",
    "endpoint": "http://127.0.0.1:11434/v1/embeddings",
    "model": "all-minilm:latest",
    "dimensions": 384,
    "timeoutMs": 60000,
    "batchSize": 64,
    "maxInputChars": 6000
  }
}
```

Only `schema` and `embedding` are valid at user scope. `XDG_CONFIG_HOME`
relocates the configuration root and `YGG_CONFIG` selects an exact file.

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
    },
    {
      "id": "go",
      "version": "0.1.0",
      "command": ["ygg-extract-go"],
      "includeGlobs": ["**/*.go"],
      "timeoutMs": 10000
    },
    {
      "id": "typescript",
      "version": "0.1.0",
      "command": ["ygg-extract-typescript"],
      "includeGlobs": ["**/*.js", "**/*.jsx", "**/*.ts", "**/*.tsx"],
      "timeoutMs": 10000
    },
    {
      "id": "manifest",
      "version": "0.1.0",
      "command": ["ygg-extract-manifest"],
      "includeGlobs": [
        "**/package.json",
        "**/go.mod",
        "**/Cargo.toml",
        "**/pyproject.toml"
      ],
      "timeoutMs": 10000
    },
    {
      "id": "python",
      "version": "0.1.0",
      "command": ["plugins/python/ygg-extract-python"],
      "includeGlobs": ["**/*.py"],
      "timeoutMs": 10000
    }
  ]
}
```

Repository configuration inherits the user embedding when the `embedding`
field is absent. An explicit provider overrides it, and `null` disables it for
that repository.

Embedding `kind` is either `command` or `openai-compatible`. A command provider
uses `command` argv instead of `endpoint` and `apiKeyEnv`.

The bundled local worker is one command-provider implementation. Use absolute
paths because the command's working directory is the repository being indexed:

```json
{
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
```

Embedding providers receive at most `maxInputChars` Unicode characters per
record. The default is 6,000, and the configured value participates in vector
invalidation. Lower it when a local model runner has a smaller measured stable
request ceiling. The HTTP adapter requests float encoding and performs one
bounded retry with backoff for
transport errors, HTTP 429/529, and 5xx responses. Configure a remote-appropriate
timeout explicitly; the short default is intended for local endpoints.

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

Operational commands always return JSON with `schema`, `ok`, and either `data`
or `error`. Search data uses schema `ygg.search.result/v3` and includes the
requested mode, active mode, fallback reason, timings, and ranked records with
path/line citations. Records expose navigation evidence, not internal fusion
scores or retrieval-lane diagnostics. The default core `text-chunk` kind,
`core` source, and a title equal to `path:startLine` are implicit and omitted;
extractor-specific kinds, titles, sources, and metadata remain explicit.
Flag-validation failures use the same CLI error envelope without repeating
usage text; explicit `--help` remains concise usage output.
Search validates a mechanical repository freshness token before retrieval and
initializes or incrementally refreshes the repository index as needed. Its
`elapsedMs` therefore includes root resolution, freshness validation, any
required indexing, and retrieval.
When embeddings are configured, `auto` and `semantic` searches complete missing
vectors before retrieval. A provider failure remains an explicit lexical
fallback in `auto`; `lexical` never starts an embedding provider.
Fusion ranks files independently from their returned citation. When multiple
records from one file contribute, the citation uses the bounded source window
with the densest query-term evidence; `startLine`, `endLine`, and a line-based
title identify that localized window. Semantic-only results retain their
highest-ranked semantic record. A compound identifier contributes one query
term to citation density; its components are match alternatives, not extra
votes that can make an import outrank a usage matching other query terms.
Citation excerpts share a 2,400-rune target budget based on the requested
result limit, bounded between 120 and 280 runes each. Increasing `--limit`
therefore adds citations without multiplying excerpt payload linearly.
The first `limit` files include citations. When additional bounded candidates
exist, `morePaths` contains up to twenty subsequent ranked paths without
excerpts so callers can choose a narrower follow-up without doubling the
default context payload.
When `search --root` names a repository subdirectory or file, that relative
path is a mechanical prefix or exact-file scope applied before candidate limits
in lexical, path, extractor, and semantic retrieval. Returned citations remain
relative to the repository root rather than the scoped path.
Single structured terms containing syntax such as `-`, `.`, `#`, `<`, `=`, or `(`
use a case-sensitive literal lane in `auto` mode. An FTS token prefilter keeps
the bounded substring check local. This makes JSX tags, selectors, and removed
identifiers exact instead of discarding their punctuation or admitting partial
path-stem matches. A single case-marked code identifier such as `ContentStack`,
`Metric`, `runTask`, or `API` uses the same literal lane, while lowercase
conceptual terms retain normal hybrid retrieval. Explicit `semantic` mode
remains semantic-only.
Queries beginning with `-` follow the standard flag terminator, for example
`ygg search -- "--json"`. The temporary redundant `--json` output assertion is
recognized only before that terminator and cannot consume literal query text.
Broad queries containing a mixed-case identifier or two or more other structured
anchors also retain bounded per-anchor lexical lanes. This keeps an exact symbol
represented among surrounding conceptual terms without treating one natural
hyphenated phrase as code.
Retrieval lanes return records, but the public result identity is a file path.
Fusion therefore preserves the top two record-level paths as its precision
head, then combines the best record per lane at file-path identity for the
remaining results. Record-level evidence still selects the cited line range.
This lets lexical and semantic hits in different chunks corroborate one file
without allowing cross-chunk evidence to displace the strongest two direct
record matches.
If a retired identifier remains in a negative assertion, search correctly
returns that citation; a focused test, not retrieval alone, proves the asserted
absence.

Status data reports the binary version, configuration provenance, Git-family
identity, available seed count, retired index count/bytes, freshness, vector
coverage, and the latest index run. Index summaries include `prunedIndexes`,
`prunedBytes`, or `pruneSkipped` when family maintenance has work to report.
`status --check` additionally returns `embeddingProvider` with
`checked`, `available`, elapsed time, and a bounded provider error when
unavailable. Provider unavailability is diagnostic data and does not make the
status command fail.
