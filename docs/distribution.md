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

From a clone, use the entrypoints directly or put `bin/` on `PATH`:

```sh
bin/ygg init . --project my-project --out project.edn --sync
bin/ygg query "where is auth handled" --project my-project
```

`ygg init` starts the local server when needed. To keep it warm after login on
macOS:

```sh
ygg service start-at-login enable
```

In an interactive terminal, `init` guides the first setup: current directory or
another repo path, assistant harness/MCP/skill setup, start-at-login, auto
maintenance, and whether to index immediately. For non-interactive assistant
setup, pass those choices to `init`:

```sh
bin/ygg init . --project my-project --out project.edn \
  --harness codex --hooks --skill --mcp \
  --maintenance harness
```

Use `--maintenance deepseek` or `--maintenance openrouter` to configure a
DeepSeek V4-compatible API executor instead of the harness command. Use
`--no-input` to force non-interactive behavior.

Verify the entrypoints:

```sh
ygg help
ygg-mcp --config project.edn
```

Build the standalone JVM artifact used by the native-image path:

```sh
bb graalvm:uber
```

Build the GraalVM server binary when `native-image` is available:

```sh
bb graalvm:check
bb graalvm:native
```

`bb graalvm:args` prints the `native-image` command without requiring GraalVM.
The binary is written to `target/native/ygg-server`. `bin/ygg start` prefers
that binary when it exists and falls back to the Clojure server path otherwise.
Set `YGG_NATIVE_SERVER` to point the wrapper at another built server binary.

When the host does not have GraalVM installed, use Docker to verify the
native-image build in the GraalVM community image:

```sh
bb graalvm:docker-check
bb graalvm:docker-native
```

The Docker build writes `target/native/ygg-server-linux`; it is a Linux
verification binary and is intentionally separate from the host launcher path.
Set `YGG_NATIVE_IMAGE_DOCKER_IMAGE` to use another GraalVM native-image image.

`ygg-mcp` is the stdio MCP proxy for editor and agent integrations. It uses the
local Yggdrasil server started by `ygg init` or `ygg start`, then lists the
primary `ygg_query`, `ygg_node`, `ygg_status`, and
`ygg_systems` tools by default. Use `--tools default,sync,work` or
`YGG_MCP_TOOLS=all` to list bounded advanced packet tools for sync
inspection/checks and project queue handoff. Use the CLI for explicit
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
  yggdrasil:dev sh -lc 'ygg init /workspace --out /data/project.edn --sync; ygg stop'
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
