# Agent Guide

AGraph is in heavy development. Default to aggressive refactors that
canonicalize concepts, names, data shapes, and workflows instead of preserving
backwards compatibility. Prefer one clear canonical path over compatibility
layers, aliases, or parallel legacy behavior unless the user explicitly asks for
a migration bridge.

The graph should be emergent, not prescribed. Store concrete facts first, derive
neutral candidates from structure and evidence density, and let system meaning
come from accumulated relationships, metadata, and auditable corrections. Do not
hard-code architecture through path names, regexes, or text matching.

Keep mechanical code focused on bounded, project-agnostic facts: file discovery,
file type detection, parsers, manifests, imports, routes, URLs, graph topology,
metrics, and stable row shapes. Leave open-ended semantic judgment, merging,
classification, and use-case-specific meaning to humans or LLM-backed corrections;
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

Use the filesystem queue as the durable provider-agnostic handoff for agent
packets. Queue items are transport and lease state only; embedded payloads stay
explicit JSON, and semantic results must return as auditable JSON artifacts that
can be validated and folded into `agraph.map.json` or metadata. Do not make AGraph core
depend on one LLM provider, hidden agent loop, or semantic classifier path.

Keep implementation local-first and deterministic. XTDB stores durable graph
facts and audit history; semantic/vector providers are optional later backends.

Use `.dev/` for local XTDB data, caches, and generated reports. Do not commit
generated graph databases.

Core commands:

- `bb sync <project.edn>`
- `bb sync <project.edn> --check --map agraph.map.json`
- `bb sync work pull --project <project-id> --agent <agent-id>`
- `bb ask "text" --project <project-id>`
- `bb explore create "text" --project <project-id>`
- `bb view systems --project <project-id>`
- `bb test`
- `bb lint`
- `bb format:check`

When a task depends on project structure, ownership, dependencies, or system
boundaries, inspect AGraph before making broad assumptions:

```sh
bb sync inspect project.edn
bb sync project.edn --check --map agraph.map.json
```

Use `bb ask` for one-shot graph questions and `bb explore` for multi-step
investigations with a stable graph basis. Do not dump the whole graph into
context unless the task explicitly needs broad inventory. Use `bb view` only
when a rendered or exported graph slice helps.

If sync reports maintenance work, claim one bounded item at a time and inspect
the evidence. For manual corrections, use the relevant `bb sync` command before
completion. For structured work results, complete the item first, then run
`bb sync work apply` so AGraph validates the result before editing
`agraph.map.json`:

```sh
bb sync work pull --project <project-id> --agent <agent-id>
bb sync explain <target> --map agraph.map.json
bb sync ignore external-api <host> --map agraph.map.json --reason "<reason>"
bb sync work complete <work-id> --result result.json
bb sync work apply <work-id> --map agraph.map.json
```

Prefer small, pure extractors and one store boundary that writes a complete
file/run bundle with `xt/execute-tx`. Adding a file type should mean adding a
scanner kind and extractor adapter that emits the canonical graph rows; it
should not require rebuilding storage, query, or system-inference foundations.
Use `delete-docs` for graph replacement; do not use `erase-docs` unless
implementing explicit legal deletion.
