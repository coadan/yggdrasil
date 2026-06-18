# Distribution

AGraph has three distribution surfaces:

- Native CLI: `bin/agraph` and `bin/agraph-mcp`
- Docker image: hermetic CLI runtime for agents
- Homebrew formula: native macOS install after tagged releases exist

The CLI is the product boundary. Docker and Homebrew wrap the same entrypoints.

Graph data has one maintained export contract: `agraph.graph/v2` JSON from
`agraph view ... --format json`. The bundled HTML graph viewer consumes the same graph
shape and should not require separate renderer-specific export formats.

## Native CLI

Local install on macOS:

```sh
scripts/install-macos.sh
```

With dependencies:

```sh
scripts/install-macos.sh --install-deps
```

Verify the installed entrypoints:

```sh
agraph help
agraph-mcp --config project.edn --map agraph.map.json
```

`agraph-mcp` is the stdio MCP server for editor and agent integrations. It
exposes bounded graph tools for ask, explore, systems views, sync inspection,
sync check, and filesystem queue handoff. Use the CLI for explicit map mutation
and applying validated work results.

## Docker

Build locally:

```sh
docker build -t agraph:dev .
```

Inspect a mounted project config:

```sh
docker run --rm \
  -v "$PWD:/workspace:ro" \
  -v "$HOME/.cache/agraph:/data" \
  agraph:dev sync inspect /workspace/project.edn
```

For first-run onboarding in a mounted repo, write generated project files to a
writable mounted directory:

```sh
docker run --rm \
  -v "$PWD:/workspace:ro" \
  -v "$HOME/.cache/agraph:/data" \
  agraph:dev start /workspace --out /data/project.edn --map /data/agraph.map.json --report-out /data/agraph-out
```

For worktrees, mount the wrapper/workbench root rather than a nested worktree so
Git metadata and `repos.json` can be resolved.

## Homebrew

The formula template lives at:

```text
packaging/homebrew/Formula/agraph.rb.template
```

Release process:

1. Tag a release, for example `v0.1.0`.
2. Generate the GitHub source tarball checksum:
   ```sh
   curl -L -o agraph-v0.1.0.tar.gz \
     https://github.com/OWNER/agraph/archive/refs/tags/v0.1.0.tar.gz
   shasum -a 256 agraph-v0.1.0.tar.gz
   ```
3. Copy the template into a Homebrew tap as `Formula/agraph.rb`.
4. Replace `OWNER`, version, URL, and `sha256`.
5. Test locally:
   ```sh
   brew install --build-from-source ./Formula/agraph.rb
   agraph help
   ```

Do not put release-specific checksums in this repository until the upstream GitHub
location and first tag are final.
