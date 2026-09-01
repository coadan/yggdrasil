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
capabilities. Hosts own scheduling and provider lifetime. Do not make query or
index packages discover configuration, read ambient credentials, construct
optional providers, or expose SQLite handles.

After each bounded slice, run `go test ./...`, `go vet ./...`, and
`gofmt -l .`, then commit the slice.
