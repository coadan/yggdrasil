# Clojure extractor

`ygg-extract-clojure` emits one bounded parser summary per file with its
syntax-owned namespace, up to 128 requires, up to 128 top-level definitions,
their definition kinds, the covered line range, and parser diagnostics. The
sparse shape keeps indexing and embedding costs proportional to file count. It
supports `.clj`, `.cljc`, `.cljs`, `.bb`, and `.cljd` files.

The pinned tree-sitter bundle is optional and isolated from the Go CLI:

```sh
python3 -m venv .dev/clojure-venv
.dev/clojure-venv/bin/pip install -r plugins/clojure/requirements.txt
```

```json
{
  "id": "clojure",
  "version": "0.1.0",
  "command": [
    ".dev/clojure-venv/bin/python",
    "plugins/clojure/ygg-extract-clojure"
  ],
  "includeGlobs": ["**/*.clj", "**/*.cljc", "**/*.cljs", "**/*.bb", "**/*.cljd"]
}
```

The plugin reports parser facts only. It does not infer architecture or project
meaning from namespaces, imports, paths, or definition names.
