# Agent Guide

Yggdrasil is a search-only Go CLI. Keep the public surface limited to
`index`, `search`, `status`, and `plugin check`.

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

After each bounded slice, run `go test ./...`, `go vet ./...`, and
`gofmt -l .`, then commit the slice.
