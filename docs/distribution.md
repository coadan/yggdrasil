# Distribution

Yggdrasil has three distribution surfaces:

- Native CLI: `bin/ygg` and `bin/ygg-mcp`
- Docker image: hermetic CLI runtime for agents
- Homebrew formula: native macOS install after tagged releases exist

The CLI is the product boundary. Docker and Homebrew wrap the same entrypoints.

Graph data has one maintained export contract: `ygg.graph/v2` JSON from
`ygg view ... --format json`. The bundled HTML graph viewer consumes the same graph
shape and should not require separate renderer-specific export formats.

The React/MDX report viewer is a build-time asset. Contributors build it with
`bb report-ui:build`, which writes compiled files under
`resources/ygg/report-ui/`. Runtime commands such as `ygg report` and
`ygg view ... --format html` copy those compiled assets into local bundles;
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
ygg start
ygg help
ygg-mcp --config project.edn
```

`ygg-mcp` is the stdio MCP proxy for editor and agent integrations. It requires
the local Yggdrasil server started by `ygg start`, then lists the primary
`ygg_query`, `ygg_node`, `ygg_status`, and
`ygg_systems` tools by default. Use `--tools default,sync,work` or
`YGG_MCP_TOOLS=all` to list bounded advanced packet tools for sync
inspection/checks and filesystem queue handoff. Use the CLI for explicit map
correction-fact changes and applying validated work results.

## Docker

Build locally:

```sh
docker build -t yggdrasil:dev .
```

Inspect a mounted project config:

```sh
docker run --rm \
  -v "$PWD:/workspace:ro" \
  -v "$HOME/.cache/ygg:/data" \
  yggdrasil:dev start
```

For first-run onboarding in a mounted repo, write generated project files to a
writable mounted directory:

```sh
docker run --rm \
  -v "$PWD:/workspace:ro" \
  -v "$HOME/.cache/ygg:/data" \
  yggdrasil:dev sh -lc 'ygg start & until ygg status >/dev/null 2>&1; do sleep 1; done; ygg init /workspace --out /data/project.edn --sync; ygg stop'
```

For worktrees, mount the wrapper/workbench root rather than a nested worktree so
Git metadata and `repos.json` can be resolved.

Docker release images must include `resources/ygg/report-ui/`; they should
not need Node installed to run `ygg report`.

## Homebrew

The formula template lives at:

```text
packaging/homebrew/Formula/ygg.rb.template
```

Release process:

1. Tag a release, for example `v0.1.0`.
2. Generate the GitHub source tarball checksum:
   ```sh
   curl -L -o ygg-v0.1.0.tar.gz \
     https://github.com/OWNER/yggdrasil/archive/refs/tags/v0.1.0.tar.gz
   shasum -a 256 ygg-v0.1.0.tar.gz
   ```
3. Copy the template into a Homebrew tap as `Formula/ygg.rb`.
4. Replace `OWNER`, version, URL, and `sha256`.
5. Test locally:
   ```sh
   brew install --build-from-source ./Formula/ygg.rb
   ygg help
   ```

Source releases used by Homebrew must include the compiled
`resources/ygg/report-ui/` assets, or the formula needs to run
`bb report-ui:build` during source installation.

Do not put release-specific checksums in this repository until the upstream GitHub
location and first tag are final.
