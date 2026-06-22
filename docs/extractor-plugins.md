# Extractor Plugins

Extractor plugins are explicit project extensions for graph facts that Yggdrasil
core does not know how to extract yet. They are local-first, opt-in, and
validated into the same canonical row shapes as core extractors.

Plugins have three supported modes:

- `:enhance`: run after core extraction for files Yggdrasil already scans.
- `:override`: run after core extraction and intentionally replace selected
  indexed rows by reusing their `:xt/id` with plugin-provenance rows.
- `:scan`: opt explicitly matched files into indexing when Yggdrasil only has
  fallback or no first-class support for that file family.

Plugin rows are useful evidence, but they are not the same as benchmarked core
extractor support. Unless configured otherwise, plugin-derived rows carry
`:benchmark-status :unbenchmarked`.

## Project Config

Add plugins to `project.edn`:

```clojure
{:id "sample"
 :repos [{:id "app" :root "."}]
 :plugins
 [{:kind :extractor
   :id "datastar-hiccup"
   :version "0.1.0"
   :command ["bb" "run" "-m" "tools.datastar-extractor"]
   :modes [:enhance :override :scan]
   :applies-to {:file-kinds [:code]
                :path-globs ["src/**/*.clj"]}
   :scan {:path-globs ["ui/**/*.panel"]
          :file-kind :panel}
   :search {:chunks? true}
   :emits [:hypermedia]
   :timeout-ms 10000}]}
```

Commands run from the indexed repo root. Yggdrasil sends one JSON request on stdin
and expects one JSON result on stdout. Commands are executed as argv vectors,
not through shell interpolation.

Use `bb plugin input extractor <package-dir> <repo-root> <file> --json` to
inspect the exact input packet Yggdrasil will send for one selected package
extractor without executing the plugin command.
Use `bb plugin gap extractor <package-dir> <repo-root> <file> --json` when an
agent needs the full authoring packet: input sample, core counts, output
contract, proof commands, and benchmark/core-promotion caveats.

For git-shared packages, install a package with `bb plugin install` and let
`project.edn` reference its pinned local manifest. See
[plugin-packages.md](plugin-packages.md).

## Scan Mode

Use `:scan` when a project has a file family Yggdrasil does not support as a core
kind yet.

```clojure
:scan {:path-globs ["features/**/*.feature"]
       :file-kind :gherkin}
```

Plugin-scanned files get ordinary file rows plus plugin metadata:

- `:plugin-scanned? true`
- `:plugin-ids [...]`
- `:benchmark-status :unbenchmarked`

If a matching file would otherwise enter as `:unknown`, the plugin scan can
replace that fallback kind with the configured `:file-kind`. Files already
covered by first-class core kinds stay core files and should be handled with
`:enhance`.

## Enhance And Override Modes

Use `:enhance` when core already scans the file but the project needs deeper
facts from local helper APIs, DSLs, templates, or generated conventions.

The plugin receives the file record and the core rows for that file. It can add
rows, emit edges that core would not know about, or emit a row with the same
`:xt/id` to replace a weaker core row in the persisted extraction.

Use `:override` when replacement is intentional rather than incidental. Override
plugins still receive core rows and still emit ordinary canonical rows. A plugin
overrides a core row by emitting the same row id; Yggdrasil writes the plugin row
with plugin provenance, benchmark status, package pin metadata when present, and
the plugin fingerprint.

Plugins can also emit `overlays` to mark existing rows as superseded or hidden
without deleting raw evidence. This is the preferred path when a plugin has a
more specific fact but the weaker core row should remain auditable. Yggdrasil
annotates the target row with the replacement id, reason, plugin id, package
pin, plugin fingerprint, benchmark status, and claim authority. Hidden rows are
still persisted as raw evidence; consumers can choose to ignore rows marked with
`:superseded? true` when they need a plugin-enhanced view.

## Search Opt-In

Graph-profile sync suppresses ordinary chunks and search docs so architecture
maintenance stays lean. A plugin can explicitly opt its own chunks into the
query surface:

```clojure
:search {:chunks? true}
```

With this option, plugin-generated chunks are persisted and search docs are
built for those chunks even under `:graph` profile. Core chunks and node search
docs remain suppressed under `:graph`. Use this only when the plugin emits
bounded, useful query text, such as DSL summaries or framework-specific
architecture facts.

## Input Contract

Input schema: `ygg.extractor-plugin.input/v1`

```json
{
  "schema": "ygg.extractor-plugin.input/v1",
  "project": {"id": "sample"},
  "repo": {"id": "app", "root": "/repo"},
  "run": {"id": "run:..."},
  "plugin": {
    "id": "datastar-hiccup",
    "version": "0.1.0",
    "authority": "project-plugin",
    "benchmarkStatus": "unbenchmarked",
    "packageId": "datastar-hiccup",
    "packageVersion": "0.1.0",
    "packageRev": "abc123",
    "packageManifestFingerprint": "sha256:...",
    "packageSource": {
      "type": "git",
      "url": "https://github.com/org/ygg-datastar.git",
      "rev": "abc123"
    }
  },
  "file": {
    "file-id": "file:sample:app:src/page.clj",
    "path": "src/page.clj",
    "kind": "code",
    "content": "(ns page) ..."
  },
  "core": {
    "nodes": [],
    "edges": [],
    "chunks": [],
    "fileFacts": [],
    "diagnostics": []
  }
}
```

`packageId`, `packageVersion`, `packageRev`, `packageManifestFingerprint`, and
`packageSource` are present for git-shared package plugins. Project-local plugin
configs omit them.

## Output Contract

Output schema: `ygg.extractor-plugin.result/v1`

```json
{
  "schema": "ygg.extractor-plugin.result/v1",
  "nodes": [
    {
      "kind": "hypermedia-request",
      "label": "save profile",
      "sourceLine": 42
    }
  ],
  "edges": [
    {
      "sourceId": "node:...",
      "targetId": "node:...",
      "relation": "targets"
    }
  ],
  "fileFacts": [
    {
      "kind": "route",
      "label": "POST /profile",
      "normalizedValue": "/profile",
      "sourceLine": 42,
      "confidence": 0.8
    }
  ],
  "chunks": [
    {
      "kind": "hypermedia-summary",
      "label": "save profile request",
      "text": "Datastar click handler sends POST /profile and patches profile form state.",
      "sourceLine": 42
    }
  ],
  "overlays": [
    {
      "op": "supersedes",
      "targetId": "node:core-route",
      "replacementId": "node:plugin-route",
      "reason": "Plugin emitted a framework-specific route fact."
    }
  ],
  "diagnostics": []
}
```

Yggdrasil fills standard file defaults when possible: `:file-id`, `:path`,
`:run-id`, `:active?`, source line defaults for searchable rows, and stable ids
when a plugin omits `:xt/id`.

Every plugin row is annotated with:

- `:provenance :plugin`
- `:plugin-id`
- `:plugin-version`
- `:plugin-fingerprint`
- `:plugin-authority`
- `:plugin-package-id`, `:plugin-package-version`, `:plugin-package-rev`, and
  `:plugin-package-manifest-fingerprint` for packaged plugins
- `:plugin-package-source` for packaged plugins
- `:benchmark-status`

Yggdrasil stamps these provenance fields after parsing plugin output. Plugin
commands cannot make their own rows authoritative by spoofing file, run, or
plugin provenance fields.

Overlay operations currently supported by the authoring contract are
`:supersedes`, `:hides`, `:refines`, and `:links`. `:supersedes` and `:hides`
annotate the target row with `:superseded? true` while preserving the target row
for audit. The annotation includes the overlay operation, optional replacement
id, reason, plugin id, plugin fingerprint, package pin, benchmark status, and
package claim authority when available. `:refines` and `:links` are reserved
decision evidence for now; use
ordinary rows and edges when the plugin needs durable graph facts.

Plugin failures become `:extractor-plugin` diagnostics. A failed plugin does not
crash sync.

## Core Promotion

A project plugin can become a core extractor only through a contribution that
proves it is core-ready:

- The extractor is project-agnostic and does not depend on local helper names,
  product vocabulary, path semantics, host names, or prose heuristics.
- Project-specific plugins are rejected for core. Keep them in project config.
- The contribution includes fixtures and extractor tests for the new core kind.
- The contribution includes benchmark cases showing a material improvement for
  the relevant task class.
- The benchmark report distinguishes shell-only, core Yggdrasil, and, when useful,
  plugin-enhanced Yggdrasil lanes.
- Claims about agent improvement cite the benchmark artifact, not anecdotes.

Until those conditions are met, keep the implementation as an explicit plugin
with `:benchmark-status :unbenchmarked`.
