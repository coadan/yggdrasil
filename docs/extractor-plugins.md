# Extractor Plugins

Extractor plugins are explicit project extensions for graph facts that AGraph
core does not know how to extract yet. They are local-first, opt-in, and
validated into the same canonical row shapes as core extractors.

Plugins have two supported modes:

- `:enhance`: run after core extraction for files AGraph already scans.
- `:scan`: opt explicitly matched files into indexing when AGraph only has
  fallback or no first-class support for that file family.

Plugin rows are useful evidence, but they are not the same as benchmarked core
extractor support. Unless configured otherwise, plugin-derived rows carry
`:benchmark-status :unbenchmarked`.

## Project Config

Add plugins to `project.edn`:

```clojure
{:id "sample"
 :repos [{:id "app" :root "."}]
 :extractor-plugins
 [{:id "datastar-hiccup"
   :version "0.1.0"
   :command ["bb" "run" "-m" "tools.datastar-extractor"]
   :modes [:enhance :scan]
   :applies-to {:file-kinds [:code]
                :path-globs ["src/**/*.clj"]}
   :scan {:path-globs ["ui/**/*.panel"]
          :file-kind :panel}
   :search {:chunks? true}
   :emits [:hypermedia]
   :timeout-ms 10000}]}
```

Commands run from the indexed repo root. AGraph sends one JSON request on stdin
and expects one JSON result on stdout. Commands are executed as argv vectors,
not through shell interpolation.

For git-shared packages, install a package with `bb plugin install` and let
`project.edn` reference its pinned local manifest. See
[plugin-packages.md](plugin-packages.md).

## Scan Mode

Use `:scan` when a project has a file family AGraph does not support as a core
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

## Enhance Mode

Use `:enhance` when core already scans the file but the project needs deeper
facts from local helper APIs, DSLs, templates, or generated conventions.

The plugin receives the file record and the core rows for that file. It can add
rows, emit edges that core would not know about, or emit a row with the same
`:xt/id` to replace a weaker core row in the persisted extraction.

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

Input schema: `agraph.extractor-plugin.input/v1`

```json
{
  "schema": "agraph.extractor-plugin.input/v1",
  "project": {"id": "sample"},
  "repo": {"id": "app", "root": "/repo"},
  "run": {"id": "run:..."},
  "plugin": {
    "id": "datastar-hiccup",
    "version": "0.1.0",
    "authority": "project-plugin",
    "benchmarkStatus": "unbenchmarked"
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
    "diagnostics": []
  }
}
```

## Output Contract

Output schema: `agraph.extractor-plugin.result/v1`

```json
{
  "schema": "agraph.extractor-plugin.result/v1",
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
  "diagnostics": []
}
```

AGraph fills standard file defaults when possible: `:file-id`, `:path`,
`:run-id`, `:active?`, source line defaults for searchable rows, and stable ids
when a plugin omits `:xt/id`.

Every plugin row is annotated with:

- `:provenance :plugin`
- `:plugin-id`
- `:plugin-version`
- `:plugin-fingerprint`
- `:plugin-authority`
- `:benchmark-status`

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
- The benchmark report distinguishes shell-only, core AGraph, and, when useful,
  plugin-enhanced AGraph lanes.
- Claims about agent improvement cite the benchmark artifact, not anecdotes.

Until those conditions are met, keep the implementation as an explicit plugin
with `:benchmark-status :unbenchmarked`.
