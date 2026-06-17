# Agent Guide

AGraph is in heavy development. Default to aggressive refactors that
canonicalize concepts, names, data shapes, and workflows instead of preserving
backwards compatibility. Prefer one clear canonical path over compatibility
layers, aliases, or parallel legacy behavior unless the user explicitly asks for
a migration bridge.

The graph should be emergent, not prescribed. Store concrete facts first, derive
neutral candidates from structure and evidence density, and let system meaning
come from accumulated relationships, metadata, and auditable overlays. Do not
hard-code architecture through path names, regexes, or text matching.

Keep mechanical code focused on bounded, project-agnostic facts: file discovery,
file type detection, parsers, manifests, imports, routes, URLs, graph topology,
metrics, and stable row shapes. Leave open-ended semantic judgment, merging,
classification, and use-case-specific meaning to humans or LLM-backed overlays;
do not recreate that reasoning with brittle rules.

Hard boundary: AGraph core must not contain semantic heuristics that classify
project meaning from names, hosts, path vocabulary, prose, or substring lists.
Examples of forbidden core logic: "docs-like host", "service-like path",
"library-like folder", "test/example URL means non-runtime", or similar
semantic shortcuts. When such judgment is useful, expose a bounded decision with
ids, evidence rows, graph neighborhood, and recommended actions so a human or
LLM can decide and write the accepted result into metadata or `agraph.map.json`.
Deterministic code may rank by mechanical facts such as relation type,
evidence count, degree, file kind, parser output, and graph topology; it must
not pretend those facts are final architecture semantics.

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
file/run bundle with `xt/execute-tx`. Adding a file type should mean adding a
scanner kind and extractor adapter that emits the canonical graph rows; it
should not require rebuilding storage, query, or system-inference foundations.
Use `delete-docs` for graph replacement; do not use `erase-docs` unless
implementing explicit legal deletion.
