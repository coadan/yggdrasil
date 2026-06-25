# Dependencies

Yggdrasil is distributed as a native CLI and a Docker image.

## Runtime

Required for native CLI use:

- JDK 21+
- Clojure CLI
- Git
- Python 3, for Python AST extraction.

Optional:

- Babashka, for `bb` development shortcuts.
- Node.js and npm, for rebuilding the React/MDX report viewer assets.
- GraalVM `native-image`, for building the standalone server binary.
- OpenRouter or OpenAI API key, for embedding-backed semantic retrieval.
- Docker, for zero-install CLI usage.

JavaScript, TypeScript, SQL, Terraform/HCL, OpenAPI, dependency manifests,
selected dependency lockfiles, and container manifests are handled by
deterministic text, JSON, EDN, TOML-ish, or YAML extraction adapters. Native CLI
use does not require a Node.js toolchain, TypeScript compiler, Terraform binary,
OpenAPI generator, or package-manager install.

The report and graph HTML viewer is built from `report-ui/` into
`resources/ygg/report-ui/`. Runtime CLI use does not require Node when those
compiled assets are present. The canonical graph export remains plain
`ygg.graph/v2` JSON.

For hot-reload development of that viewer, use the Vite server from
`report-ui/` and point it at a generated report directory:

```sh
bb report project.edn --out .dev/reports/live --force
bb report-ui:dev -- --host 0.0.0.0 --port 5173
open "http://localhost:5173/?reportDir=$(pwd)/.dev/reports/live"
```

## Package Report

Use the package report for dependency inventory that should stay grounded in
bounded graph facts:

```sh
ygg packages --project <project-id> [--repo <repo-id>] [--ecosystem npm|cargo|go] [--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N] [--json]
bb packages --project <project-id> [--repo <repo-id>] [--ecosystem npm|cargo|go] [--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N] [--json]
```

The JSON schema is `ygg.dependency.report/v1`. The report joins manifest
declarations, lockfile resolutions, mechanically resolved
source-import-to-package evidence, and unresolved source imports into:

- `counts`: package, version, declaration, lockfile resolution, import evidence,
  unresolved import, evidence-gap, and conflict counts.
- `ecosystems`: per-ecosystem package, version, and import totals.
- `packages`: package entries with `declared-by`, `resolved-versions`, and
  `imported-by` evidence arrays.
- `declared-without-import-evidence`: packages declared by manifests but not
  mechanically connected to source imports in the indexed graph.
- `unresolved-imports`: source imports that did not resolve to a declared
  external package under the current map and manifest evidence.
- `version-conflicts`: packages with more than one resolved version.

Evidence gaps are not unused dependency findings. They are bounded facts for a
human or agent to inspect.

The package report and project evidence surface (`ygg.evidence/v2` in reports
and `sync inspect --json`) include dependency counts plus `next` commands and
structured `nextActions` rows when declared-package evidence gaps, version
conflicts, or unresolved imports are present. Agents should prefer `nextActions`
because each row carries a bounded `kind`, `label`, optional `count`, and
executable `command`.

`sync check --enqueue` turns unresolved imports into
`ygg.dependency.review-packet/v1` queue items. A completed
`ygg.dependency.review-result/v1` can apply an explicit `packageImports`
correction; the result must cite packet evidence and choose
one package from the packet. Applied corrections retain the dependency review id,
rules source, evidence ids, reason, and result or patch confidence for audit.
Use `ygg sync work validate <work-id>` before `apply` when a human or agent
has written the result JSON and wants a non-mutating schema/evidence check.
When the reviewer already knows the mapping, `ygg corrections package import
<import-prefix> <ecosystem>:<package>` records the same accepted correction
path directly.
Packets include `facts.packageSelection` with total package rows, included rows,
packet limit, truncation flag, `matchingPackages`, and the mechanical selection
basis. Candidate packages are ranked before the bound is applied using only
concrete import/package strings from the unresolved import and package rows.
Ranked package rows may include `candidateScore` and `candidateSignals`, such as
an exact import or import-prefix match, so reviewers can see why a package was
included. Package candidates preserve manifest facts such as `version-range` and
`dependency-scope` when those rows are indexed.

Use `ygg view deps <package-label>` for a package evidence graph. For package
nodes, `deps` includes the declaring manifests, resolved lockfile versions,
lockfiles that resolved those versions, and source namespaces with import
evidence.

Python package imports are resolved only when `pyproject.toml` declares explicit
import names:

```toml
[project]
dependencies = ["beautifulsoup4>=4"]

[tool.ygg.import-names]
beautifulsoup4 = ["bs4"]
```

JVM package imports are resolved only through accepted correction facts because
Maven coordinates do not mechanically imply Java package roots:

```sh
ygg corrections package import org.slf4j maven:org.slf4j:slf4j-api \
  --reason "slf4j-api exports org.slf4j"
```

## Development

Recommended tools:

- JDK 21+
- Clojure CLI
- Babashka
- Git
- Python 3
- Node.js and npm
- Docker

Validation commands:

```sh
bb report-ui:build
bb report-ui:test
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
