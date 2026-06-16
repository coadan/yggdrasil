# Dependencies

AGraph is distributed as a native CLI and a Docker image.

## Runtime

Required for native CLI use:

- JDK 21+
- Clojure CLI
- Git

Optional:

- Babashka, for `bb` development shortcuts.
- OpenRouter or OpenAI API key, for embedding-backed semantic retrieval.
- Docker, for zero-install CLI usage.

The graph viewer vendors Cytoscape.js in `resources/agraph/vendor/` so generated
HTML reports work without a CDN. The canonical graph export remains plain
`agraph.graph/v2` JSON.

## macOS

Install dependencies and link the local entrypoints:

```sh
scripts/install-macos.sh --install-deps
```

Link entrypoints without installing dependencies:

```sh
scripts/install-macos.sh
```

The installer links:

- `agraph`
- `agraph-mcp`

to `$HOME/.local/bin` by default. Use `--prefix DIR` to choose another prefix.

## Development

Recommended tools:

- JDK 21+
- Clojure CLI
- Babashka
- Git
- Docker

Validation commands:

```sh
bb test
bb lint
bb format:check
```

Equivalent Clojure commands:

```sh
clojure -M:test
clojure -M:lint
clojure -M:format/check
```
