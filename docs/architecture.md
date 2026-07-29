# Architecture

Yggdrasil has one product path:

```text
git-aware discovery
    -> bounded generic chunks + explicit extractor records
    -> bounded SQLite write batches
    -> FTS5/path retrieval (+ optional Vec1 retrieval)
    -> reciprocal-rank fusion
    -> cited search records
```

The `cmd/ygg` entry point only routes commands. `internal/indexer` owns a run,
`internal/store` owns all persistence, and `internal/search` owns retrieval and
ranking. Extractor and embedding executables communicate through versioned
JSONL contracts and never write SQLite directly.

## Persistence

The canonical durable state is one SQLite database per canonical repository
root. Its project id is the first 24 hexadecimal characters of the SHA-256 of
that root. WAL mode permits readers while the single non-blocking file lock
serializes index writers.

Linked Git worktrees are separate canonical roots and therefore receive
separate project ids, databases, locks, file state, and vector coverage.
When a worktree has no database yet, Yggdrasil takes a consistent SQLite
snapshot of an indexed sibling worktree, retargets the copy, and reconciles
every discovered file by content hash. Unchanged files keep their records and
vectors; only added, changed, deleted, or extractor-configuration-invalidated
files are reprocessed. `ygg index --full` deliberately bypasses this reuse.
Discovery runs `git ls-files` against the selected worktree, so its tracked
modifications and untracked files cannot bleed into another worktree's index.
Reaching the same physical worktree through a symlink resolves to the same
canonical root.

Indexing loads the stored file-state map once and commits at most 128 file
updates, deletions, or diagnostics in each transaction. File and record writes
inside a batch use set-based SQL. A failed batch rolls back completely; earlier
batches remain durable, so restart progress and memory stay bounded. Each file
replacement deletes its prior records and vectors before inserting its complete
new record bundle.

FTS5 is an external-content index over canonical records. Vec1 stores float32
vectors keyed by the same record ids. Embedding fingerprints include all
provider configuration, and an embedding lane is eligible only when every
current record hash is covered.

## Dependency boundaries

The core module has one direct dependency: the pure-Go SQLite implementation
that supplies SQLite, FTS5, and Vec1. It has no C toolchain requirement, CLI
framework, ORM, provider SDK, parser bundle, web server, or frontend build.

Extractor plugins are separate modules. Adding a parser means adding an
executable adapter that emits `ygg.extractor/v1` records; it does not change the
database or query contracts. The Markdown, Go, TypeScript, and manifest
adapters are reference implementations, not privileged core parsers; each is a
dependency-free nested module.

The Python adapter is also isolated from core and uses Python's standard-library
AST. Its runtime dependency is explicit in repository configuration.

The Java/.NET adapter is an optional tree-sitter process with a separately
pinned Python environment. Parser dependencies remain outside the CLI binary
and the core Go module graph.

The Terraform adapter is a separate Go module around the HCL parser. It has no
effect on core startup or the core dependency graph unless explicitly built and
configured.

## Deliberate non-goals

The search core does not model a general graph, render HTML, generate reports,
infer architecture, classify project meaning, maintain corrections, run an
agent queue, or preserve the old XTDB schema. Mechanical search evidence can be
consumed by humans or agents without embedding semantic policy in Yggdrasil.
