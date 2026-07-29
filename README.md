# Yggdrasil 2

Yggdrasil 2 is a local, search-only repository index. It is a single Go CLI
backed by SQLite and has no daemon, HTML UI, report generator, or general graph
analysis surface.

The public commands are:

```text
ygg index
ygg search "where is request routing configured"
ygg status
ygg plugin check markdown --file README.md
```

Generated indexes live outside repositories under
`$YGG_STORAGE_ROOT/v2/indexes/`. An optional `.ygg/config.json` enables bounded
extractor plugins and embeddings.

This repository intentionally does not read or migrate Yggdrasil 1 XTDB state.
The old implementation is used only as a pinned benchmark baseline.

