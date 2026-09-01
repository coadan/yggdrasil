# Decision: make Yggdrasil a composable Go retrieval library

Date: 2026-09-01  
Status: accepted  
Commitment: staged, with CLI compatibility gates

## Context

Yggdrasil's retrieval mechanics are currently internal packages orchestrated
directly by `internal/cli`. That works for a one-shot executable, but an embedded
host cannot reuse the engine, retain an embedding worker, schedule small refresh
batches, or consume typed results. Reimplementing those capabilities in each
host would split ranking, freshness, and fallback behavior.

The command currently combines several independently changing concerns:
configuration discovery, repository resolution, provider construction, index
maintenance, retrieval, progress rendering, and JSON presentation. Midgard
needs the same retrieval mechanics with a longer lifecycle: concrete retrieval
must remain available while local semantic capability starts and vector
coverage advances in the background.

## Decision

Yggdrasil remains a search-only system and becomes a set of composable public Go
packages. It owns retrieval mechanics and contracts. Hosts own composition,
lifecycle, scheduling, and presentation. The `ygg` executable is a frontend over
the public packages and contains no separate retrieval implementation.

The intended dependency direction is:

```text
                         +----------------+
                         | CLI or host UI |
                         +-------+--------+
                                 |
                         +-------v--------+
                         |     engine     |  optional default composition
                         +---+---------+--+
                             |         |
             +---------------+         +----------------+
             |                                          |
      +------v------+       read snapshot        +------v------+
      |    index    +---------------------------->+    query    |
      +--+-------+--+                             +-------------+
         |       |
  +------v---+ +-v---------+
  | extractor| | embedding |
  +----------+ +-----------+
```

The packages have these responsibilities:

- `query` owns validated plans, ranking, bounded citations, and result types. It
  consumes a narrow read snapshot and performs no provider startup, indexing,
  configuration loading, or ambient environment access.
- `index` owns repository identity, freshness, derived SQLite storage,
  incremental extraction, vector coverage, and immutable read snapshots. A
  refresh call performs one synchronous, cancellable, batch-bounded unit of
  work. It accepts capabilities instead of constructing them.
- `extractor` owns the extraction interface and bounded command-protocol
  adapter.
- `embedding` owns the embedding interface, model fingerprint, handshake, and
  bounded provider adapters. Query and lexical indexing do not depend on an
  embedding implementation.
- `engine` is an optional opinionated composition for consumers that want the
  complete stack. It coordinates mechanisms but does not own an unobservable
  background scheduler.

Public interfaces are defined by the consuming package and expose Go values,
not SQLite handles or CLI envelopes. Existing internal implementation packages
may move, merge, or remain behind these boundaries. The package list describes
coherent capabilities; it is not a requirement to export every implementation
type.

Mechanisms never start hidden background loops. Hosts may retain an engine and
providers, call bounded refresh batches between other work, prioritize a recent
query scope, and observe coverage after each batch. The standalone CLI may run
the same operations synchronously for explicit maintenance. Cancellation and
close are part of every retained capability contract.

The CLI continues to own flags, environment and configuration-file discovery,
versioned JSON envelopes, exit codes, and terminal progress formatting. Library
callers pass explicit storage roots, repository roots, configuration, clocks or
budgets where needed, and provider instances. This makes behavior testable and
prevents a library import from reading user configuration or starting network
work unexpectedly.

SQLite remains the sole durable retrieval store. Extractors and embedding
workers remain bounded subprocess protocols, and their optional dependencies do
not enter the Go module. The library never downloads a model. A host may use a
locally available worker, omit semantic capability, or supply another explicit
adapter.

## Progressive capability contract

Search uses one immutable index/readiness snapshot and returns immediately from
the lanes available in that snapshot. Lexical, path, extractor, and graph lanes
remain usable while vectors are absent, stale, or being built. Semantic state,
coverage, freshness, and fallback reason are result facts rather than implicit
control flow.

Partial semantic coverage may provide marked supplementary candidates. It does
not displace the concrete result budget or claim complete hybrid ranking. Full
hybrid ranking requires compatible vector coverage for the requested scope and
repository revision. Provider startup or failure never changes the availability
of non-semantic retrieval.

## Alternatives

- Keep the CLI as the only public contract. This preserves process isolation but
  forces long-lived hosts to pay one-shot startup costs or reimplement the
  engine.
- Publish one monolithic facade over the current CLI orchestration. This reduces
  initial refactoring but keeps provider construction, refresh policy, and query
  latency coupled.
- Move Yggdrasil code into its first host. This avoids a library API while
  creating divergent owners for retrieval behavior.
- Add a Yggdrasil daemon. This can retain workers but imposes service lifecycle
  and transport on consumers that only need an in-process library.

## Migration and gates

1. Extract query/result contracts and capability interfaces without changing
   ranking or CLI behavior.
2. Make provider construction explicit and split index refresh into bounded
   synchronous work. Preserve existing full-refresh behavior as a composition.
3. Add the default engine and make every CLI operation call public packages.
4. Validate an external Go consumer against fixed search, freshness, linked
   worktree, provider-failure, and result-bounding fixtures.
5. Add progressive coverage behavior only after the non-semantic and complete
   semantic paths retain their current parity corpus.

Every migration stage keeps `go test ./...`, `go vet ./...`, and `gofmt -l .`
clean. CLI schemas and behavior remain compatibility gates until a separately
versioned contract changes them.

## Revisit

- Merge public packages when consumer code shows that a boundary has no
  independent use and only creates type conversion.
- Split an implementation into another public package only after a concrete
  consumer cannot compose through the existing interfaces.
- Reconsider host-owned scheduling if two independent hosts reproduce the same
  coordination bugs and a visible reusable scheduler would remove them without
  introducing hidden work.
