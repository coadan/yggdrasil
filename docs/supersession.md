# Supersession scope

This repository is the next implementation of canonical Yggdrasil. The local
directory name is only workspace isolation; the module, product, binary, config
directory, storage directory, and eventual remote remain
`github.com/coadan/yggdrasil`, `Yggdrasil`, `ygg`, `.ygg/`, and
`~/.local/share/ygg/`. Do not introduce `ygg2` or `yggdrasil2` product names.

## Retained and reimplemented

| Existing strength | Canonical implementation here |
| --- | --- |
| Repository-aware discovery | Git tracked/untracked discovery with ignore semantics and a bounded filesystem fallback |
| Cited retrieval | Ranked records always carry repository-relative path and line ranges |
| Incremental local state | Size, mtime, extraction fingerprint, and content hash drive atomic per-file SQLite replacement |
| Hybrid recall when configured | FTS5/path recall is always available; complete Vec1 lanes join `auto` retrieval with explicit fallback states |
| Extensible extraction | Versioned, run-scoped JSONL executables emit the one canonical search-record shape |
| Legacy text coverage | An exhaustive 95-kind gate verifies cited search for retained text and exclusion of binary/secret material |
| Non-synthetic replay evidence | Pinned upstream pre-fix/fix revisions, verified changed paths, manual classes, candidate/raw timing, and hashed reports |
| Central state outside repositories | One deterministic project directory beneath `YGG_STORAGE_ROOT/indexes/` |

These are behavioral ideas, not compatibility layers. No old data shape, API,
or storage format is carried forward.

## Removed from the product

- XTDB, temporal graph facts, graph views, graph inference, and corrections;
- daemon lifecycle, project registry, watchers, hooks, MCP, and maintenance work;
- HTML/React rendering, report bundles, package reports, and analysis commands;
- built-in agent workflows, queues, benchmark commands, and provider-specific
  semantic policy;
- Clojure/JVM/GraalVM and frontend build toolchains.

The replay harness remains a separate developer binary, `yggbench`; it is not a
`ygg` command family.

## Deferred

Architecture interpretation, project classification, dependency impact,
correction workflows, agent orchestration, and rendered exploration stay out of
core until independently justified. New work must first preserve the focused
path:

```text
index mechanical evidence -> search -> return bounded citations
```

Publishing this repository over the canonical remote should preserve its Git
history and tags as a normal repository operation. It should not add migration
code for XTDB or aliases for removed commands unless a separate migration
requirement is explicitly approved.
