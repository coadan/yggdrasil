# TypeScript extractor

`ygg-extract-typescript` uses a comment- and string-aware lexical scanner to
emit bounded top-level declarations, imports, and re-exports for JavaScript,
JSX, TypeScript, and TSX files, including the `.mjs`, `.cjs`, `.mts`, and
`.cts` module variants when the include globs list them. It does not infer
application meaning.
Each file also receives one compact navigation record. Declaration records
cover the scanner's balanced top-level source span.

The module has no dependency on Yggdrasil core or third-party packages.
