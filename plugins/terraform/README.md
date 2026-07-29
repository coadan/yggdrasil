# Terraform extractor

`ygg-extract-terraform` uses HashiCorp's HCL parser to emit blocks, attributes,
expression traversals, line ranges, and parser diagnostics. Traversals remain
metadata on their owning attribute instead of becoming high-volume occurrence
records.

The parser is pinned in this separate Go module and is not linked into core:

```json
{
  "id": "terraform",
  "version": "1",
  "command": ["./bin/ygg-extract-terraform"],
  "includeGlobs": ["**/*.tf"]
}
```
