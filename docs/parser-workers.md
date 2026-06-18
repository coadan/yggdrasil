# Parser Workers

AGraph extractors can delegate syntax parsing to external workers when a
language has a mature parser outside the Clojure process. The worker boundary is
provider-neutral: workers emit concrete parser facts, and AGraph converts those
facts into canonical graph rows.

The first worker prototype is `scripts/parser-worker.py`. It is a JSONL process:
one request per stdin line, one response per stdout line.

Request:

```json
{
  "schema": "agraph.parser.request/v1",
  "id": "file-1",
  "kind": "python",
  "path": "src/app.py",
  "content": "import os\n"
}
```

Response:

```json
{
  "schema": "agraph.parser.response/v1",
  "id": "file-1",
  "kind": "python",
  "path": "src/app.py",
  "facts": {
    "definitions": [],
    "imports": [],
    "references": [],
    "diagnostics": []
  }
}
```

`content` is optional. When omitted, the worker reads `path` from disk with
UTF-8 replacement semantics.

## Fact Contract

Workers return syntax facts only:

- `definitions`: parser declarations with `kind`, `name`, `line`, and optional
  `endLine`.
- `imports`: imported modules, namespaces, packages, or paths with `target`,
  `line`, and optional `endLine`.
- `references`: syntax references with `source`, `target`, `kind`, `line`, and
  optional `endLine` when the parser can produce them without semantic guesses.
- `diagnostics`: parser failures with `stage`, `line`, and `message`.

Workers must not emit AGraph ids, systems, ownership, runtime classifications,
or architecture labels. AGraph owns ids, row shape, relation names, storage, and
all semantic corrections.

## Why Python

Python is a practical worker host because it has broad parser availability:

- `ast` for Python with no dependency.
- Tree-sitter bindings for broad language coverage.
- Language-specific parsers for ecosystems where tree-sitter is not enough.
- Mature stdlib and third-party parsers for XML, TOML, YAML, packaging metadata,
  and generated interface files.

Use a long-lived JSONL worker or batched invocation for real indexing. Avoid
starting a Python process once per file.

## Adoption Plan

1. Keep the current in-process extractors as the default path.
2. Add worker-backed extraction behind the same canonical extraction boundary.
3. Start with high-value languages where regex extraction is noisy:
   Java, C#, TypeScript/JavaScript.
4. Benchmark each worker-backed extractor against OSS issue replay before
   making it the default.
5. Track both quality and cost: recall, MRR, noise@20, edge count, index time,
   parser diagnostics, and unsupported-file counts.

The Java benchmark case `junit-framework-4587-console-uid-selector` is the
first target for parser-worker evaluation because it exposed useful Java type
references and high edge noise in the home-grown extractor.

## Current Prototype

Supported backends:

- `python`: built-in Python `ast`, no extra dependency.
- `java`: optional tree-sitter backend. Install
  `scripts/parser-worker-requirements.txt` in an isolated environment before
  benchmarking Java parser facts.

Run the worker directly:

```sh
printf '%s\n' '{"id":"demo","kind":"python","path":"demo.py","content":"import os\nclass App:\n    def run(self):\n        pass\n"}' \
  | python3 scripts/parser-worker.py
```

Run the optional Java backend:

```sh
python3 -m venv .dev/parser-worker-venv
.dev/parser-worker-venv/bin/python -m pip install -r scripts/parser-worker-requirements.txt
printf '%s\n' '{"id":"demo","kind":"java","path":"Demo.java","content":"package demo; class App { Target make(Param p) { return new Target(); } static class Target {} interface Param {} }"}' \
  | .dev/parser-worker-venv/bin/python scripts/parser-worker.py
```

Run the worker contract tests:

```sh
clojure -M:test --focus agraph.parser-worker-test
```
