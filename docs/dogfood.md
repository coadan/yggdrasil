# Dogfood and release check

Yggdrasil is dogfooded against two local repositories with different shapes:

- `breyta-workbench`: a mixed Go, Python, and Clojure tool repository;
- `void2`: a larger TypeScript/TSX repository with thousands of files.

Two complementary lanes are retained:

- the commands below measure indexing and freshness against current local
  working copies;
- `make benchmark-dogfood` measures relevance and latency against eight pinned
  parent/fix replays from the public repositories.

The replay lane is reproducible and is the required comparison for ranking or
extractor changes. The current-checkout lane catches scale and freshness
regressions but does not establish relevance.

Dogfood state must use a fresh central directory outside either repository.
The repositories are inputs only: the run must not add configuration, indexes,
or reports to them.

```sh
make build
export YGG_STORAGE_ROOT="$(mktemp -d /tmp/ygg-dogfood.XXXXXX)"

bin/ygg index --root ../breyta-workbench --full --no-embed --json
bin/ygg index --root ../void2 --full --no-embed --json
bin/ygg index --root ../breyta-workbench --no-embed --json
bin/ygg index --root ../void2 --no-embed --json
bin/ygg status --root ../breyta-workbench --json
bin/ygg status --root ../void2 --json
```

The lexical search checks use repository concepts supplied by each repository's
own tracked guidance and source. They are localization probes, not semantic
architecture labels:

```sh
bin/ygg search --root ../breyta-workbench --mode lexical \
  "publish resolve thread wait"
bin/ygg search --root ../breyta-workbench --mode lexical \
  "inspect namespace outline symbol"
bin/ygg search --root ../void2 --mode lexical \
  "sender relative visibility subscription"
bin/ygg search --root ../void2 --mode lexical \
  "streaming line protocol terminal result"
bin/ygg search --root ../void2 --mode lexical \
  "request local read snapshot"
```

## July 2026 indexing evidence

On the same Apple Silicon machine and working repositories, the pre-batching
implementation and commit `24e26bb` produced:

| Repository | Text files | Before full index | Batched full index | Batched no-op |
| --- | ---: | ---: | ---: | ---: |
| `breyta-workbench` | 339 | 417 ms | 158 ms | 23 ms |
| `void2` | 4,817 | 33,011 ms | 1,667 ms | 40 ms |

The tracked ten-case replay suite retained recall@10 `0.687`, MRR `0.622`, and
citation rate `1.0`. Its full-index p50 changed from `378 ms` to `182 ms`; the
large OpenTelemetry case changed from `214,120 ms` to `6,463 ms`. These are
machine- and suite-specific results, not general agent-efficiency claims.

Before release, rerun the dogfood commands with the exact release binary, run
the complete replay suite, and retain its hash-bearing JSON report under
`.dev/bench/`. `make release` must then produce the four CGO-free platform
binaries and `dist/SHA256SUMS`.

## Extractor ablation

The Go and TypeScript extractors remain opt-in. On the same candidate binary
(`sha256:47bd59b1…`), the July 2026 extractor ablation produced:

| Suite/lane | Recall@10 | MRR | Full index p50 | Search p50 | Search p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dogfood default | 0.292 | 0.500 | 218 ms | 23.8 ms | 43.2 ms |
| Dogfood + extractors | 0.354 | 0.470 | 771 ms | 28.7 ms | 134.9 ms |
| Broad default | 0.810 | 0.821 | 205 ms | 16.5 ms | 98.4 ms |
| Broad + extractors | 0.790 | 0.696 | 193 ms | 18.1 ms | 235.9 ms |

The dogfood recall gain is real, but the broad relevance and tail-latency
regressions mean this is not evidence for enabling extractors by default.
Reports retain the full candidate, suite, config, and extractor binary hashes;
the abbreviated hash above is only a readable pointer.
