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

`plugins/python/test_extractor.py` replays the legacy Python AST fact boundary:
top-level imports, classes and functions, direct methods, async markers, source
lines, and syntax diagnostics. `internal/plugin/python_integration_test.go`
also exercises the executable through the real JSONL manager.

`plugins/jvm-dotnet/test_extractor.py` replays the legacy parser-worker fixtures
for Java and C#: packages/namespaces, imports/usings, nested declarations,
methods, constructors, properties, line ranges, and diagnostics.
The parser dependency is pinned in that plugin rather than added to core.

## Next structured gaps

The next adapters should be selected using real replay misses, startup cost, and
search-quality ablations. The largest known source-language gaps are Rust,
Clojure, Java, and the remaining legacy languages. The largest formal-format gaps are
GraphQL, Protobuf, OpenAPI, SQL, and Terraform. Manifest grammar expansion
should follow observed repository coverage rather than recreating the legacy
router wholesale.

## Manifest ablation

The tracked eight-case dogfood suite currently covers Go, shell, and TypeScript
fixes, so it measures manifest cost but does not contain a manifest-localization
claim. On 2026-07-29, the same candidate and suite hashes produced:

| Lane | Recall@10 | MRR | Full index p50 | No-op p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 0.292 | 0.500 | 277.0 ms | 20.3 ms | 26.6 ms | 19.7 ms | 44.8 ms |
| Manifest only | 0.292 | 0.500 | 335.9 ms | 25.6 ms | 26.8 ms | 26.8 ms | 50.2 ms |

Candidate hash:
`sha256:972496ce73e49f5a02dabb3005f45f95f046c73bf748fb28c42f73ec6a221fdd`.
Suite hash:
`sha256:e5ca24676d128898e952b9bcd27b37bb520f9af83b68388472d872d2cb6cf269`.
Manifest binary hash:
`sha256:b8932a0c5edc2fb04aff4efc2f6a13f1429c94a39bfa09d194426db0ea7bc044`.

This is evidence of neutral localization quality on a suite with no manifest
cases, not evidence that manifest records improve search. The approximately
59 ms full-index p50 increase is the current cost ceiling to improve or justify
with manifest-specific replay cases.

## Python ablation

The focused Python lane replays two pinned real fixes from Flask and Graphify.
The repeated matched run on 2026-07-29 produced:

| Lane | Recall@10 | MRR | Full index p50 | No-op p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 0.500 | 0.531 | 284.4 ms | 16.5 ms | 18.2 ms | 17.6 ms | 30.3 ms |
| Python AST | 1.000 | 0.583 | 309.8 ms | 16.4 ms | 58.2 ms | 13.1 ms | 33.0 ms |

Candidate hash:
`sha256:79975336dbc49dbe1f9f059aa83b442ab358ad90a0aedcd4861780f037f34396`.
Suite hash:
`sha256:8293905d96f46bb52948428cb42a24dda5e01331009db3887524e3ee3bb0b524`.
Python adapter hash:
`sha256:a276bf700261480671f3a9095e29ec3754d61cca3da67140f922d8fe6b939a13`.

The Flask case moved from zero to full file recall at 10; the Graphify case
remained at full recall. This supports Python-localization value for these two
problem classes only. The interpreter and AST work add about 40 ms to the
smaller one-file incremental p50, and the two-case lane is too small for a broad
startup or agent-efficiency claim.

The committed candidate was also run across the complete tracked claim lane:
10 cases, seven repositories, nine source kinds, seven measured problem-class
groups, and six measured architecture/audit-class groups.

| Broad lane | Recall@10 | MRR | Full index p50 | No-op p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 0.810 | 0.821 | 213.2 ms | 15.7 ms | 26.4 ms | 15.2 ms | 101.5 ms |
| Python AST | 0.910 | 0.831 | 196.3 ms | 17.2 ms | 21.8 ms | 16.8 ms | 102.0 ms |

Both reports used candidate
`sha256:b23673f265b33bb50612f93381805d30f9cb62c65314248feb3e8a2d234cf8e9`
and suite
`sha256:8293905d96f46bb52948428cb42a24dda5e01331009db3887524e3ee3bb0b524`.
The Python adapter improved the Flask case and did not regress the other nine
cases. This is broad localization evidence for the tracked suite, not a general
agent-speed claim.

## Java/.NET ablation

The parser fixtures pass for Java and C#, but search evidence does not support
enabling this adapter by default. On the pinned Dapper case, declaration records
rank enum-heavy test files ahead of the core mapping owner:

| Dapper lane | Recall@10 | MRR | Full index | Incremental | Search p50 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Default | 1.000 | 1.000 | 587.9 ms | 52.5 ms | 18.5 ms |
| Java/.NET parser | 0.500 | 0.333 | 965.3 ms | 60.0 ms | 16.3 ms |

Occurrence-level type references were removed after an earlier diagnostic run
raised full indexing to approximately 5 seconds. The retained declaration lane
is faster but still regresses localization.

The same-binary broad run confirms that the impact is isolated to Dapper but
still lowers the aggregate:

| Broad lane | Recall@10 | MRR | Full index p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 0.810 | 0.821 | 268.2 ms | 32.1 ms | 19.9 ms | 127.1 ms |
| Java/.NET parser | 0.760 | 0.754 | 270.6 ms | 35.6 ms | 17.1 ms | 102.1 ms |

Both broad reports used candidate
`sha256:9a621d7944c4674e3e4c5b688351ee60e29cc3f156eb5994110149df3fd1d72a`
and suite
`sha256:8293905d96f46bb52948428cb42a24dda5e01331009db3887524e3ee3bb0b524`.
The adapter remains an explicit experimental ablation until ranking or record
selection can retain its parser facts without displacing stronger core
evidence.

The Terraform parity fixture is `plugins/terraform/main_test.go`. It covers
nested blocks, labels, attributes, expression traversals, exact line ranges, and
parser diagnostics using the same mechanical fact boundary as the legacy HCL
extractor.

## Terraform ablation

The pinned Terraform case was already perfectly localized by generic text.
The HCL adapter preserves that result while adding structured citations:

| Terraform lane | Recall@10 | MRR | Full index | Incremental | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 1.000 | 1.000 | 516.4 ms | 20.3 ms | 19.5 ms | 23.3 ms |
| HCL parser | 1.000 | 1.000 | 719.5 ms | 31.3 ms | 33.5 ms | 37.0 ms |

The same-binary broad run stayed quality-neutral across all 10 cases:

| Broad lane | Recall@10 | MRR | Full index p50 | Incremental p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Default | 0.810 | 0.821 | 161.0 ms | 26.0 ms | 18.7 ms | 111.5 ms |
| HCL parser | 0.810 | 0.821 | 279.0 ms | 26.6 ms | 20.0 ms | 119.5 ms |

Both broad reports used candidate
`sha256:1b8f934f0e78e860875381d3e31a7d895cce241bec9985c26c343805ccad24e7`
and suite
`sha256:8293905d96f46bb52948428cb42a24dda5e01331009db3887524e3ee3bb0b524`.
The adapter is therefore useful only when block/attribute precision is worth
the measured indexing cost; it is not a default retrieval-quality improvement.
