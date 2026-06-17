# Bitemporal Core

AGraph stores graph facts in XTDB using valid time for source/project time and
XTDB system time for when AGraph learned or corrected those facts.

Current implementation status:

- Source snapshots are persisted in `:agraph/source-snapshots`.
- Index runs are persisted in `:agraph/index-runs` and keep `snapshot-id` plus
  `valid-from`.
- File-owned graph rows are written at the snapshot `basis-instant`.
- Removed files and replaced file-owned facts use temporal `delete-docs` at the
  same `basis-instant`.
- Query and graph helpers accept a read context through `:read-context` or CLI
  `--valid-at`.
- Metadata definitions, metadata rows, and graph views are persisted in XTDB and
  read through the same temporal context.

`active?` still exists as compatibility metadata on existing row schemas. It is
not the temporal model. Currentness should come from XTDB valid-time reads.

## Read Context

Use:

```clojure
{:valid-at #inst "2026-01-01T00:00:00Z"
 :known-at #inst "2026-01-02T00:00:00Z"
 :snapshot-token "..."}
```

`valid-at` maps to XTDB `:current-time` and selects the source/project valid
time. `snapshot-token` is reserved for exact reproducible system-time reads when
the driver token is available. `known-at` records caller intent, but exact
system-time replay should use `snapshot-token`.

CLI reads support:

```sh
agraph view deps sample.core --valid-at 2026-01-01T00:00:00Z
agraph view deps sample.core --format json --valid-at 2026-01-01T00:00:00Z
agraph ask "greeting flow" --valid-at 2026-01-01T00:00:00Z
agraph ask "api gateway" --project sample --json --valid-at 2026-01-01T00:00:00Z
```

## Snapshot Semantics

For clean Git repos, the snapshot valid time is the commit time.

For dirty Git worktrees and non-Git roots, the snapshot valid time is the index
run start instant. Snapshot ids use a content fingerprint for dirty and
synthetic snapshots so changing source content creates a distinct snapshot.

Filesystem `mtime-ms` is kept as file metadata only. It is not source valid
time.

## Store Boundary

Temporal writes should use the store helpers:

```clojure
(store/put-op table row {:valid-from basis-instant})
(store/delete-op table id {:valid-from basis-instant})
(store/commit-temporal-bundle! xtdb bundle)
```

The store boundary is the only place that should translate temporal bundles
into XTDB `:put-docs` and `:delete-docs` operations.

## Metadata

Custom metadata uses XTDB tables for definitions, values, and graph views:

- `:agraph/metadata-defs`
- `:agraph/metadata`
- `:agraph/graph-views`

Metadata values attach to graph target ids and can be read with `--valid-at`.
Definitions declare value type, cardinality, display behavior, and whether a key
should render as a metric. Graph exports project metadata into `attrs`, `tags`,
and `metrics` so consumers can customize views without schema changes.

## Remaining Work

- Remove `active?` from canonical graph schemas and call sites.
- Move code/system evidence into `:agraph/evidence`.
- Add `snapshots`, `runs`, `history`, and `diff` commands.
- Persist exact snapshot-token metadata once the driver integration exposes the
  right serializable token.
