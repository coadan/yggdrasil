# Manifest extractor

`ygg-extract-manifest` emits searchable, line-addressable package, dependency,
script, workspace, toolchain, replacement, and feature facts. It supports
`package.json`, `go.mod`, `Cargo.toml`, and `pyproject.toml`.

The plugin uses exact manifest names to select a grammar. It does not classify
project architecture or assign meaning from directory names.

```json
{
  "id": "manifest",
  "version": "1",
  "command": ["./bin/ygg-extract-manifest"],
  "includeGlobs": [
    "**/package.json",
    "**/go.mod",
    "**/Cargo.toml",
    "**/pyproject.toml"
  ]
}
```
