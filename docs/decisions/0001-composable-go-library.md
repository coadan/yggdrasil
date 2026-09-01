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
packages. It owns retrieval mechanics, contracts, and an optional observable
refresh scheduler. Hosts own composition, process lifetime, and presentation.
The `ygg` executable is a frontend over the public packages and contains no
separate retrieval implementation.

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
  complete stack. Its explicitly started `Refresher` continuously advances
  source freshness and semantic coverage in bounded work units.

Public interfaces are defined by the consuming package and expose Go values,
not SQLite handles or CLI envelopes. Existing internal implementation packages
may move, merge, or remain behind these boundaries. The package list describes
coherent capabilities; it is not a requirement to export every implementation
type.

Mechanisms never start hidden background loops. A host explicitly starts and
closes `engine.Refresher`, can wake it after worktree changes or searches, and
can inspect its current phase, coverage, last success, and bounded failure. The
refresher owns debounce, single-writer serialization, bounded batches, and
retry backoff so hosts do not reproduce retrieval scheduling. The standalone
CLI may run the same mechanisms synchronously for explicit maintenance.
Cancellation and close are part of every retained capability contract.

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

Search never waits for the refresher or its writer lock. It uses one immutable
current index/readiness snapshot when available. While the initial index is
missing, source freshness is stale, or the writer is publishing a batch, search
falls back immediately to bounded live-filesystem lexical and path evidence.
Lexical, path, extractor, and graph lanes remain usable whenever their current
snapshot is ready while vectors are absent, stale, or being built. Semantic
state, coverage, freshness, and fallback reason are result facts rather than
implicit control flow.

The refresher starts with an immediate source-freshness pass, then observes a
bounded interval and explicit wake signals. A search may prioritize its scope
for later semantic batches but never joins or awaits that work. Refresh failure
updates readiness and backoff; it does not turn an otherwise valid search into
an indexing error.

Semantic activation uses compatible coverage for the exact requested scope,
compared with an explicit host-supplied threshold. The complete-only default is
retained until a composition chooses and validates a lower threshold. Below the
threshold no query embedding work starts. Above it, partial coverage may provide
marked supplementary candidates; those candidates do not displace the concrete
result budget or claim complete hybrid ranking. Full hybrid ranking requires
complete compatible vector coverage for the requested scope and repository
revision. Provider startup or failure never changes the availability of
non-semantic retrieval.

The refresher may prioritize vector work using mechanical demand signals: an
explicitly requested scope, paths returned by concrete retrieval, and changed
records. Priority affects only the order of bounded derived work. Deterministic
unprioritized batches age the rest of the corpus so repeated demand cannot
starve it, and a priority signal never changes ranking scores or readiness
counts.

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
- Split the scheduler from `engine` if a concrete host needs different timing
  while preserving the same observable wake, status, cancellation, and
  single-writer contract.
