# Go extractor

`ygg-extract-go` uses the Go standard parser to emit bounded facts for imports,
functions, methods, types, constants, and variables. Identifier splitting is
mechanical and only makes declaration names searchable as separate words.

The module has no dependency on Yggdrasil core or third-party packages.
