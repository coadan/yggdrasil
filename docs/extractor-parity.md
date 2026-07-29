# Legacy extractor parity

Extractor parity means preserving useful, searchable mechanical facts from the
legacy implementation. It does not mean reproducing XTDB node/edge ids, graph
inference, reports, or binary-asset inventory.

The machine-checked inventory is
[`benchmarks/extractor-parity.json`](../benchmarks/extractor-parity.json). It
enumerates every kind in legacy `ygg.extract/v2` at revision
`db1d91e79770c0ce525a81f74eab9454565755e2` and assigns exactly one disposition:

- `structured`: an optional grammar adapter emits line-addressable search
  records for a stated subset of the legacy facts;
- `baseline-only`: generic text chunks keep literal content searchable, but
  structured parity remains open;
- `retired`: binary inventory that is outside the local code-search product.

The inventory deliberately calls all current structured lanes `partial`.
Legacy Go, JavaScript/TypeScript, Markdown, and manifest extractors emitted
additional graph relations and configuration facts that the search-focused
adapters do not reproduce.

## Manifest parity fixture

`plugins/manifest/main_test.go` replays the same representative inputs and
mechanical expectations used by the legacy manifest tests for `package.json`,
`go.mod`, `Cargo.toml`, and `pyproject.toml`. The comparison is normalized to
search facts:

```text
grammar + line + kind + title + {ecosystem, packageName, version, scope}
```

Graph ids and relations are excluded. Package identity, dependency scope and
version, workspace members, scripts, Go replacements, toolchains, and Cargo
features remain explicit and searchable.

## Next structured gaps

The next adapters should be selected using real replay misses, startup cost, and
search-quality ablations. The largest known source-language gaps are Python,
Rust, and Clojure definitions/imports. The largest formal-format gaps are
GraphQL, Protobuf, OpenAPI, SQL, and Terraform. Manifest grammar expansion
should follow observed repository coverage rather than recreating the legacy
router wholesale.
