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
    "queryPrefix": "",
    "documentPrefix": "",
    "timeoutMs": 60000,
    "batchSize": 64,
    "maxInputChars": 6000
  }
}
```

User configuration accepts `schema`, `plugins`, `embedding`, `rerank`, and `projects`.
User-scope plugins apply to every repository, so their commands should be
absolute paths or names on `PATH`. A repository's explicit `plugins` list
replaces the user list, and `"plugins": []` disables it for that repository.
`XDG_CONFIG_HOME` relocates the configuration root and `YGG_CONFIG` selects an
exact file.

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
      "version": "0.3.0",
      "command": ["ygg-extract-go"],
      "includeGlobs": ["**/*.go"],
      "timeoutMs": 10000
    },
    {
      "id": "typescript",
      "version": "0.3.0",
      "command": ["ygg-extract-typescript"],
      "includeGlobs": [
        "**/*.js", "**/*.jsx", "**/*.mjs", "**/*.cjs",
        "**/*.ts", "**/*.tsx", "**/*.mts", "**/*.cts"
      ],
      "timeoutMs": 10000
    },
    {
      "id": "rust",
      "version": "0.1.0",
      "command": ["ygg-extract-rust"],
      "includeGlobs": ["**/*.rs"],
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
      "version": "0.3.0",
      "command": ["plugins/python/ygg-extract-python"],
      "includeGlobs": ["**/*.py"],
      "timeoutMs": 10000
    }
  ]
}
```

An extractor's `timeoutMs` bounds the ready response and each file response
during indexing and in `ygg plugin check`.

Repository configuration inherits the user embedding when the `embedding`
field is absent. An explicit provider overrides it, and `null` disables it for
that repository.

Embedding `kind` is either `command` or `openai-compatible`. A command provider
uses `command` argv instead of `endpoint` and `apiKeyEnv`.

`queryPrefix` and `documentPrefix` are optional provider-neutral input shaping.
Changing `documentPrefix`, the model, dimensions, endpoint, command, or input
limit invalidates stored vectors. Changing `queryPrefix`, timeout, batch size,
or API-key environment does not rebuild document vectors.

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

## Project search

Projects are defined in user configuration. Each project maps repo aliases to
absolute Git checkout paths and optional upstream refs:

```json
{
  "schema": "ygg.config/v1",
  "projects": {
    "platform": {
      "hub": {"root": "/home/dev/repos/hub", "upstream": "origin/main"},
      "spoke": {"root": "/home/dev/repos/spoke", "upstream": "origin/main"}
    }
  }
}
```

`ygg search --project platform PATTERN` looks for the current Git branch in
each member's registered worktrees. A live matching checkout is selected
when available. Otherwise the configured checkout is used. A detached HEAD
or invocation outside Git selects the configured checkouts. Multiple
available worktrees for the same branch in a member produce an error.

The JSON envelope contains `data.schema = "ygg.project.search.result/v1"`
and a `data.repositories` array sorted by alias. Each entry identifies the
repo alias, checkout root, commit, selection (`base` or `branch`), and any
warnings. Its `result` has the repository search result shape. Resolve
relative citation paths against the entry's `root`. The total `--limit` is
allocated in rounds across repos with hits, keeping each repo's own order.
Project output contains the allocated citations. Each repo's `morePaths`
holds up to five uncited ranked paths: its records beyond the allocation,
then its own more paths, without repeating a cited path.

A selected base with local edits gets a `dirty-base` warning. Drift is
measured against the configured ref available locally. The `behind-base`
warning carries the count of commits missing from the base. If the ref
cannot be compared, the warning is `upstream-unavailable`. Warnings leave
search results available. Project search reads the selected working trees
and leaves Git synchronization to the caller.

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

Extractor records are indexed for the extractor lane, identifier literal
matches, graph edges, and embeddings, but not in the core or stemmed lexical
indexes. Their many short declaration and import records would otherwise
change BM25 term statistics and length normalization for core text. An index
written before this split is rebuilt once when a writer opens it.

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

## Rerank JSONL

Command rerank providers use the same lifecycle with schema `ygg.rerank/v1`.
A `rerank` request contains a correlated request id, model, the query, and an
array of `{id,text}` inputs; each text is the result path, a newline, and the
record text from its localized citation onward, bounded by `maxInputChars`.
The result contains one `{id,score}` per input, higher meaning more relevant.

```json
{"type":"hello","schema":"ygg.rerank/v1","model":"Qwen/Qwen3-Reranker-0.6B"}
{"type":"rerank","requestId":"1","model":"Qwen/Qwen3-Reranker-0.6B","query":"retry budget","inputs":[{"id":"0","text":"src/retry.go\n..."}]}
{"type":"end"}
```

```json
{"type":"ready","schema":"ygg.rerank/v1"}
{"type":"result","requestId":"1","scores":[{"id":"0","score":0.91}]}
{"type":"summary","requests":1,"inputs":1}
```

The `rerank` configuration block takes `kind` (`command` or `http`), `command`
or `endpoint`, `model`, `depth` (default 20, at most 100), `maxInputChars`
(default 1500), and `timeoutMs` (default 60000). An `http` provider posts
`{"model","query","documents","top_n"}` to `endpoint` and reads
`{"results":[{"index","relevance_score"}]}`, the rerank shape served by
llama.cpp's `llama-server --reranking` at `/v1/rerank`. It keeps no process of
its own; the server and its model lifetime belong to the user. It is read from the user configuration and, like
`embedding`, a repository configuration may replace it or disable it with
`"rerank": null`. Only `text` queries that are not treated as structured
identifiers are reranked; `fixed`, `regexp`, literal identifier, and `graph`
searches keep fused order. The new order fuses each result's fused rank and
reranker rank with reciprocal-rank fusion (k = 20). Scores must cover every
input once and be finite.

## JSON CLI envelopes

Operational commands always return JSON with `schema`, `ok`, and either `data`
or `error`. Search data uses schema `ygg.search.result/v5` and includes the
requested mode, active mode, fallback reason, timing, resolved `queryPlan`, and
ranked records with path/line citations. When semantic retrieval is configured,
the result also reports its state, scope-specific vector counts, coverage, and
activation threshold. When a reranker is configured and a query is eligible,
`rerank` reports `applied` with the number of rescored results, or `failed`
with the provider error while fused order is kept. The query plan separates a `text`,
`fixed`, or `regexp` lexical clause from optional semantic text and its
`pattern`, `regexp-literals`, or `about` source. Records expose navigation evidence, not internal fusion
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
Index work streams `ygg.index.progress/v1` JSON lines on stderr while reserving
stdout for the final `ygg.cli/v1` envelope. Progress phases are `lock`, `seed`,
`discovery`, `index`, `embedding`, `complete`, and `failed`. Events include
mechanical completed/total counters, elapsed time, current path when useful,
and phase-specific index or embedding counts. Intermediate events are throttled;
phase transitions and terminal counters are always emitted. `search --format
compact` emits no progress events; its inline index work is silent, while errors
keep the same envelope and exit code.
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
`search PATTERN [PATH]` treats the optional repository subdirectory or file as
a mechanical prefix or exact-file scope applied before candidate limits
in lexical, path, extractor, semantic, and graph retrieval. Returned citations remain
relative to the repository root rather than the scoped path.
Single structured terms containing syntax such as `-`, `.`, `#`, `<`, `=`, or `(`
use a case-sensitive literal lane in `auto` mode. An FTS token prefilter keeps
the bounded substring check local. This makes JSX tags, selectors, and removed
identifiers exact instead of discarding their punctuation or admitting partial
path-stem matches. A single case-marked code identifier such as `ContentStack`,
`Metric`, `runTask`, or `API` uses the same literal lane, while lowercase
conceptual terms retain normal hybrid retrieval. Explicit `semantic` mode
remains semantic-only.
When extractor graph facts are configured, an exact `auto` result is also used
as a one-hop graph seed. The exact lexical owner remains the precision head;
mechanically linked import/export neighbors follow without invoking an
embedding provider.
Queries beginning with `-` follow the standard flag terminator, for example
`ygg search -- "--json"`. The temporary redundant `--json` output assertion is
recognized only before that terminator and cannot consume literal query text.
Broad queries containing a mixed-case identifier or two or more other structured
anchors also retain bounded per-anchor lexical lanes. This keeps an exact symbol
represented among surrounding conceptual terms without treating one natural
hyphenated phrase as code.
Plain text queries also search a Porter-stemmed copy of the lexical index as a
separate lane in the lexical family, so `retried` reaches `retries` while the
exact-token lanes keep their own ranks. An index written before the stemmed
table existed reads as stale until a refresh builds that table.
Retrieval lane candidate limits count distinct file paths, not records, so a
file with many chunks cannot consume another file's candidate slot. Each lane
retains its highest-ranked record for the path as citation evidence.
Retrieval lanes return records, but the public result identity is a file path.
Fusion therefore preserves the top record-level path as its precision head,
combines correlated lane variants within lexical, semantic, extractor, and
graph families, then applies balanced reciprocal-rank fusion across those
families at file-path identity for the remaining results. Graph candidates are
one-hop neighbors derived only from explicit extractor import/export facts and
mechanically resolved repository-relative targets; seed rank determines their
order. In `auto`, the strongest graph neighbor may occupy the final top-ten
slot; the displaced direct candidate remains at the end of the bounded
`morePaths` window instead of shifting intervening ranks. This prevents the
number of lexical query variants from outweighing a strong semantic or
structural rank by construction. Record-level evidence still selects the cited
line range.
This lets lexical and semantic hits in different chunks corroborate one file
without allowing cross-chunk evidence to displace the strongest direct record
match.
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
