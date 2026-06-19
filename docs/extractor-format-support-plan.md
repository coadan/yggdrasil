# Extractor Format Support Plan

AGraph format support should grow through deterministic scanner kinds and small
extractor adapters that emit canonical graph rows. Avoid semantic project
classification in core extractors; store bounded facts and relationships first.

## In Progress

- dbt: `profiles.yml`, `packages.yml`, schema/model YAML, sources, exposures,
  metrics, tests, and package dependencies.
- PHP framework routes: Laravel and Symfony route declarations from PHP and YAML.
- Python packaging: richer `pyproject.toml`, `setup.cfg`, `setup.py`, and
  `Pipfile` dependency facts.
- Rust packaging: Cargo workspace members, workspace dependencies, and features.
- Go packaging: `go.mod` and `go.work` module, version, replace, exclude, and
  workspace facts.
- Repository governance: GitHub issue templates, PR templates, funding config,
  `SECURITY.md`, and `CONTRIBUTING.md`.
- Cloud/IaC: CloudFormation JSON, Kubernetes CRDs, Crossplane resources,
  Argo CD Applications, and Helm templates under `templates/*.yaml`.
- CI/CD depth: workflow triggers, jobs, steps/actions/images, cache, artifacts,
  and explicit job dependencies.
- Odin: `.odin` source files and `ols.json` project configuration. Extracts
  packages, imports, procedures, structs/enums/unions, constants/variables, and
  foreign import declarations.
- .NET language breadth: C#, F#, and Visual Basic source files under the
  `:dotnet` scanner kind. Extracts namespaces, imports, type/module
  declarations, functions/methods, properties, and source chunks.
- Objective-C and Objective-C++: `.m` and `.mm` source files. Extracts imports,
  forward class declarations, interfaces, implementations, protocols,
  categories, enum typedefs, methods, class methods, and source chunks.
- OCaml: `.ml` and `.mli` source/interface files. Extracts `open`/`include`
  imports, module aliases, module types, types, exceptions, classes, externals,
  `let` functions, `val` declarations, and source chunks.
- Groovy: `.groovy` source files. Extracts package declarations, imports,
  classes, interfaces, enums, traits, annotations, methods, properties, source
  chunks, and curly-brace diagnostics.
- Plain text templates/config: `.tmpl`, `.njk`, `.mustache`, `.subj`, `.snap`,
  `.patch`, `.types`, generic `.conf`, and generic `.properties` files are
  indexed through existing bounded text/config extractors so agent context can
  include concrete template and runtime configuration artifacts without adding
  project-specific semantics.
- Container build variants: dotted `Dockerfile.*` and `Containerfile.*` names
  are routed through the existing Docker extractor so multi-image repos expose
  the same bounded build stages, images, environment, ports, and runtime command
  facts as canonical Dockerfile names.
- Filename-defined ops/config/lock files: `Caddyfile`, `sudoers`,
  `apt.sources`, `vimrc`, `flake.lock`, and `pixi.lock` are indexed through
  existing bounded ops/editor/dependency-lock extractors, preserving concrete
  local runtime and toolchain facts without path-based project semantics.
- Secret material metadata: `.crt`, `.cer`, `.cert`, `.key`, and `.pem` files
  are indexed as metadata-only `secret-material` rows. AGraph records concrete
  file facts such as path, size, and content hash but does not create searchable
  text chunks for certificate or key contents.

## Future Candidates

- Java parser-worker evaluation: compare the optional tree-sitter worker against
  the in-process Java extractor on benchmark cases, with fixtures for nested
  classes, annotations, records, interfaces, constructors, methods, imports, and
  type references.
- C#/.NET parser-worker evaluation: compare the optional tree-sitter worker
  against the in-process .NET extractor on benchmark cases, with fixtures for
  delegates, records, interfaces, constructors, properties, nested classes,
  imports, and type references.
- Parser-backed extractors for more languages where regex coverage becomes
  noisy, starting with the highest edge-noise or missed-definition rates from
  benchmark evidence.

## Validation Gates

- Add scanner coverage and watcher coverage for each new file family.
- Add extractor fixtures that prove file kind, stable node kinds, edge
  relations, and chunks.
- Run `bb test`, `bb lint`, `bb format:check`, and coverage audits for this
  repo plus `/Users/vegard/repos/bobr`.
