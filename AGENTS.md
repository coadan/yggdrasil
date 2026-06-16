# Agent Guide

Keep implementation local-first and deterministic. XTDB stores durable graph
facts and audit history; semantic/vector providers are optional later backends.

Use `.dev/` for local XTDB data, caches, and generated reports. Do not commit
generated graph databases.

Core commands:

- `bb index <repo-path>`
- `bb query "text"`
- `bb deps <node-or-namespace>`
- `bb path <source> <target>`
- `bb report`
- `bb test`
- `bb lint`
- `bb format:check`

Prefer small, pure extractors and one store boundary that writes a complete
file/run bundle with `xt/execute-tx`. Use `delete-docs` for graph replacement;
do not use `erase-docs` unless implementing explicit legal deletion.
