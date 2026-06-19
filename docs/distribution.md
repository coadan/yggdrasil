# Distribution

AGraph has three distribution surfaces:

- Native CLI: `bin/agraph` and `bin/agraph-mcp`
- Docker image: hermetic CLI runtime for agents
- Homebrew formula: native macOS install after tagged releases exist

The CLI is the product boundary. Docker and Homebrew wrap the same entrypoints.

Graph data has one maintained export contract: `agraph.graph/v2` JSON from
`agraph view ... --format json`. The bundled HTML graph viewer consumes the same graph
shape and should not require separate renderer-specific export formats.

The React/MDX report viewer is a build-time asset. Contributors build it with
`bb report-ui:build`, which writes compiled files under
`resources/agraph/report-ui/`. Runtime commands such as `agraph report` and
`agraph view ... --format html` copy those compiled assets into local bundles;
installed users should not need Node for normal report or graph viewing.

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
lists the primary `agraph_explore`, `agraph_status`, and `agraph_view_systems`
tools by default. Use `--tools default,cursor,sync,work,ask` or
`AGRAPH_MCP_TOOLS=all` to list bounded advanced packet tools for cursor
drilldowns, sync inspection/checks, and filesystem queue handoff. Use the CLI
for explicit map mutation and applying validated work results.

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
  agraph:dev status /workspace/project.edn
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

Docker release images must include `resources/agraph/report-ui/`; they should
not need Node installed to run `agraph report`.

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

Source releases used by Homebrew must include the compiled
`resources/agraph/report-ui/` assets, or the formula needs to run
`bb report-ui:build` during source installation.

Do not put release-specific checksums in this repository until the upstream GitHub
location and first tag are final.
