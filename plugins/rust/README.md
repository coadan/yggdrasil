# Rust extractor

`ygg-extract-rust` uses a comment-, string- and lifetime-aware lexical scanner
to emit Rust items: functions, structs, enums, unions, traits, type aliases,
constants, statics, `macro_rules!` macros and inline modules at module scope,
`impl` blocks (titled `Trait for Type` or `Type`), and the functions,
constants and types inside `impl` and `trait` blocks (titled `Type::name`).
Item records span the balanced source block or the terminating `;`.

`use` paths and `extern crate` names become `rust-use` facts. Each `mod name;`
declaration becomes a `rust-import` record whose title is the module path
(inline parents joined by `/`), or `./<path>` for a `#[path = "..."]`
attribute. Core resolves it to `name.rs` or `name/mod.rs` by Rust's file
rules for the graph lane. Each file also receives one compact navigation
record. The scanner does not expand macros or infer application meaning.

The module has no dependency on Yggdrasil core or third-party packages.

```json
{
  "id": "rust",
  "version": "0.1.0",
  "command": ["ygg-extract-rust"],
  "includeGlobs": ["**/*.rs"],
  "timeoutMs": 10000
}
```
