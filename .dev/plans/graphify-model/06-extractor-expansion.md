# Stage 6: Extractor Expansion

Status: implemented.

## Goal

Borrow Graphify's broad input coverage selectively. Yggdrasil should prioritize
deterministic extractors that improve repo/system understanding and fit the
canonical row model.

## Priority Order

1. TypeScript and JavaScript
2. SQL schema files
3. Terraform/HCL
4. OpenAPI and structured API specs
5. Java and Kotlin
6. Swift
7. Live Postgres introspection
8. PDF and Office document indexing

The first four have the best fit with Yggdrasil's existing system graph and
mechanical fact model.

## Extractor Rules

Each extractor must:

- add a scanner kind in `ygg.fs`
- emit canonical extraction buckets: nodes, edges, chunks, diagnostics
- use stable ids
- record source file and line when available
- emit bounded mechanical facts only
- add coverage reporting for supported and skipped files
- include fixtures and tests

Each extractor must not:

- classify architecture from names or path vocabulary
- infer runtime semantics from substring lists
- merge systems
- write map corrections
- call an LLM during deterministic indexing

## TypeScript And JavaScript

Target facts:

- module nodes
- exported functions/classes/constants
- imports and re-exports
- route literals as file facts when mechanically present
- package manifest dependencies from `package.json`

Implementation options:

- Tree-sitter adapter
- Node/TypeScript compiler helper
- simple parser-backed import/export extraction first

Tests:

- ES modules
- CommonJS
- TypeScript path imports
- React/Next route-adjacent files without semantic route classification

## SQL Schema Files

Target facts:

- schema/table/view nodes
- column chunks or metadata
- foreign key edges
- index/constraint nodes when useful

Start with static `.sql` files. Live Postgres should be a separate adapter.

## Terraform/HCL

Target facts:

- resource nodes
- module nodes
- provider references
- variable/output chunks
- dependency edges when explicit

Avoid cloud-service semantic classification beyond provider/resource type
strings emitted by HCL itself.

## OpenAPI And Structured API Specs

Target facts:

- API spec node
- path and method nodes
- operation ids
- schema nodes
- request/response references

These are mechanical facts because the spec explicitly declares them.

## Live Postgres Introspection

Target facts:

- database/schema/table/view nodes
- column metadata
- foreign key edges
- index/constraint metadata

Safety:

- read-only connection
- explicit DSN argument or env var
- no stored credentials in graph rows
- redact connection strings in diagnostics

## Docs And Media

PDF, Office, image, and video support should come later because they need a
clear semantic-provider boundary.

Near-term option:

- extract text into chunks only
- attach provenance and diagnostics
- leave concept/entity extraction to explicit future LLM-backed correction
  workflows

## Tests

- One fixture repo per new extractor kind where useful.
- Coverage tests for supported extensions and skipped files.
- Extractor contract tests for canonical buckets.
- Stable id tests.
- Diagnostics tests for malformed files.

## Done Criteria

Yggdrasil supports at least TypeScript/JavaScript, SQL, Terraform/HCL, and OpenAPI
as deterministic source types, and `ygg sync coverage` clearly reports what
was indexed, skipped, and unsupported.
