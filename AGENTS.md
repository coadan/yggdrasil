# Agent Guide

Yggdrasil is a search-only Go retrieval library with a CLI frontend. Preserve
the composable package direction in
`docs/decisions/0001-composable-go-library.md`. Keep the CLI surface limited to
`index`, `search`, `status`, and `plugin check`; retrieval behavior belongs in
the library rather than the frontend.

Core may use only mechanical repository facts: file discovery, text detection,
line ranges, parser output, content hashes, explicit plugin records, and search
scores. Do not infer project meaning from names, hosts, path vocabulary, prose,
or substring lists.

SQLite is the sole durable store. Extractor and embedding integrations are
explicit, bounded JSONL subprocess protocols. Optional plugin dependencies must
not enter the core CLI module.

Prefer the Go standard library. Do not add a CLI framework, ORM, provider SDK,
daemon, compatibility layer, HTML renderer, graph model, correction store, work
queue, or hidden background loop.

Library mechanisms are synchronous, cancellable, and explicit about
capabilities. The optional engine refresher owns observable bounded scheduling;
hosts explicitly start and close it and retain provider lifetime. Search must
never wait for indexing or semantic coverage. Do not make query or index
packages discover configuration, read ambient credentials, construct optional
providers, or expose SQLite handles.

After each bounded slice, run `go test ./...`, `go vet ./...`, and
`gofmt -l .`, then commit the slice.

## Repository search

When `ygg` is installed, use `ygg search --root <checkout> "<query>"` for
bounded repository search. Search initializes and refreshes its own index;
normal use does not require `ygg index`. Read `data.records` for cited results
and `data.morePaths` for additional candidate files. Verify relevant source
lines before editing. Use `rg` for exact text and file-name searches or when
Yggdrasil is unavailable. Use `bin/ygg` after `make build` to test this checkout.

This guidance applies to agents reading this repository. Global agent
configuration and installed plugins are managed separately.
