# Agent Guide

Yggdrasil is in heavy development. Default to aggressive refactors that
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

Hard boundary: Yggdrasil core must not contain semantic heuristics that classify
project meaning from names, hosts, path vocabulary, prose, or substring lists.
Examples of forbidden core logic: "docs-like host", "service-like path",
"library-like folder", "test/example URL means non-runtime", or similar
semantic shortcuts. When such judgment is useful, expose a bounded decision with
ids, evidence rows, graph neighborhood, and recommended actions so a human or
LLM can decide and write the accepted result into metadata or correction facts.
Deterministic code may rank by mechanical facts such as relation type,
evidence count, degree, file kind, parser output, and graph topology; it must
not pretend those facts are final architecture semantics.

Use the central SQLite-backed project queue as the durable provider-agnostic
handoff for agent packets. Queue items are transport and lease state only;
embedded payloads stay explicit JSON, and semantic results must return as
auditable JSON artifacts that can be validated and folded into correction facts
or metadata. Do not make Yggdrasil core depend on one LLM provider, hidden agent
loop, or semantic classifier path.

Keep implementation local-first and deterministic. XTDB stores durable graph
facts and audit history. The canonical query surface is simple `auto` retrieval:
use balanced hybrid recall when embeddings are configured, and report explicit
lexical fallback when semantic recall is unavailable. Treat lexical-only,
semantic-only, graph-only, and external retrievers as explicit ablation lanes,
not the default product or should-win benchmark path.

Do not justify Yggdrasil agent-efficiency work with hand-wavy claims. Any claim
that Yggdrasil makes agents faster, easier, or more effective must point to
replayable shell-only versus Yggdrasil evidence: benchmark reports, `bb bench efficiency`
summaries, command counts, timing, localization, citation rates, or patch
success. Efficiency suites must include manually tagged problem classes, not
only simple file-localization issues, and claims should name the class where
Yggdrasil helped or regressed. Include architecture-class cases in the tracked
benchmark suites, with curated ground truth when necessary. Treat anecdotes as
hypotheses until measured.
Before making architecture or extractor improvement claims, run the cheap current
artifact proof with `bb bench:gate --check-only`; if current score artifacts do
not exist or are stale, regenerate them with `bb bench:gate`. The gate must pass
with claim readiness supported, graph expectations passing, and zero benchmark
preflight blockers.

Use `.ygg/` for repo-local Yggdrasil references, hooks, plugins, and other
repo-local Yggdrasil files. Shared project state is central by project id:
XTDB, SQLite queues, vector/embedding state, maintenance reports, and other
cross-repo project data live under `YGG_STORAGE_ROOT` or
`~/.local/share/ygg/projects/<project-id>/`. Keep local plan artifacts under
`.dev/plans/`, local ad hoc reports under `.dev/reports/`, and benchmark scratch
outputs under `.dev/ygg/`. Do not commit generated graph databases or central
project state.

After finishing a bounded work slice and passing the relevant checks, commit it
before starting the next slice. Prefer several small, coherent commits over one
large catch-all commit; if files are hard to separate cleanly, commit the
uncertain group with a clear message rather than leaving finished work dirty.

Core commands:

- `ygg start`
- `ygg init <repo-root> --project <project-id> --sync`
- `ygg status`
- `ygg sync <project.edn>`
- `ygg sync <project.edn> --check`
- `ygg sync work pull --project <project-id> --agent <agent-id>`
- `ygg query "text" --project <project-id>`
- `ygg view systems --project <project-id>`
- `ygg packages --project <project-id> --json`
- `ygg bench efficiency <shell-agent-report.json> <ygg-agent-report.json> --json`
- `bb report <project.edn> --out ygg-out`
- `ygg-mcp --config project.edn`
- `bb test`
- `bb lint`
- `bb format:check`

Prefer scoped tests for most development work because the full suite is slow.
Run the smallest relevant test namespaces or focused checks that cover the
change, plus `bb lint` and `bb format:check` when code changed. Use the full
`bb test` suite only when the change touches broad shared behavior, cross-module
contracts, test infrastructure, or when a full regression pass is explicitly
needed before release or handoff.

Keep setup commands noun-scoped. Agent guidance is installed with
`ygg agent install --platform codex --project` and removed with
`ygg agent uninstall --platform codex --project`; do not introduce or
document top-level `ygg install` / `ygg uninstall` wrappers for this
flow.

When a task depends on project structure, ownership, dependencies, or system
boundaries, use Yggdrasil inspection only when a relevant project config already
exists or the user asks to create one. Do not create a root `project.edn` just
to satisfy agent guidance. If a project config exists, prefer:

```sh
ygg sync inspect project.edn
ygg sync project.edn --check
```

Use `ygg query` for graph questions. Plain query commands should rely on the
default `auto` retriever; pass `--retriever lexical`, `--retriever semantic`, or
other retriever overrides only for focused debugging and benchmark ablations. Do
not dump the whole graph into context unless the task explicitly needs broad
inventory. Use `ygg view` only when a rendered or exported graph slice helps.

If sync reports maintenance work, claim one bounded item at a time and inspect
the evidence. For structured work results, complete the item first, then
validate or apply it through supported Yggdrasil commands so results stay
auditable:

```sh
ygg sync work pull --project <project-id> --agent <agent-id>
ygg sync work complete <work-id> --result result.json
ygg sync work validate <work-id>
```

Prefer small, pure extractors and one store boundary that writes a complete
file/run bundle with `xt/execute-tx`. Adding a file type should mean adding a
scanner kind and extractor adapter that emits the canonical graph rows; it
should not require rebuilding storage, query, or system-inference foundations.
Use `delete-docs` for graph replacement; do not use `erase-docs` unless
implementing explicit legal deletion.
