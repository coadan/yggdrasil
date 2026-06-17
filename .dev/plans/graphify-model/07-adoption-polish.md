# Stage 7: Adoption Polish

## Goal

Finish the Graphify-inspired adoption work as a coherent AGraph product slice.
This stage is where command help, docs, examples, and release checks converge.

## Documentation Updates

Update canonical docs only after the commands exist:

- `README.md`: show `init`, `sync`, `ask`, `explore`, `view`, `report`,
  `install-agent`, `watch`, and `hook`.
- `docs/context.md`: include MCP and agent-install guidance once implemented.
- `docs/distribution.md`: document `agraph-mcp` only after it is real.
- `docs/graph-export.md`: clarify report bundle versus `agraph.graph/v2`.
- `docs/dependencies.md`: list new extractor dependencies.
- `AGENTS.md`: keep concise workflow guidance current.

Do not move this temporary staged plan into canonical docs unless it becomes an
active tracked roadmap.

## CLI Help

The CLI usage should make the happy path obvious:

```text
agraph init .
agraph sync project.edn --check --map agraph.map.json
agraph ask "..." --project ID --json
agraph explore create "..." --project ID
agraph view systems --project ID
agraph report project.edn
agraph install-agent --platform codex --project
agraph watch project.edn
agraph hook install project.edn
```

Group commands by workflow:

- setup
- sync and maintenance
- ask and explore
- view and report
- agent integration
- server integration

## Examples

Add or refresh small examples:

- `test/fixtures/project-repo` as the deterministic smoke example
- one TypeScript/JavaScript fixture when Stage 6 lands
- one SQL fixture when Stage 6 lands
- one report bundle smoke output under `.dev/` only

Keep worked examples local or generated unless the user asks to commit them.

## Release Checks

Run:

```sh
bb test
bb lint
bb format:check
```

Add focused smoke commands:

```sh
bb sync inspect .dev/agraph-context-smoke/project.edn
bb sync .dev/agraph-context-smoke/project.edn --check --map .dev/agraph-context-smoke/agraph.map.json
bb ask "api gateway connections" --project fixture --json
bb view systems --project fixture --format json
```

Adjust project ids to match the fixture config used by the final tests.

## Migration Cleanup

Because AGraph is still in heavy development, prefer one canonical path:

- promote new commands directly
- remove stale examples
- avoid long-lived aliases unless required for a real user migration
- update tests and docs in the same slice as command changes

## Risk Checks

Before calling the Graphify-inspired work complete, verify:

- no deterministic semantic heuristics were added
- all generated files are marked or scoped clearly
- agent install is idempotent and removable
- hooks fail open
- report JSON remains `agraph.graph/v2`
- MCP does not expose unsafe mutation tools
- extractor expansion has coverage reporting and diagnostics

## Done Criteria

A new user can discover AGraph from the README, initialize a project, keep it
fresh, generate a shareable report, install assistant guidance, and use MCP or
CLI packet APIs without learning internal queue or XTDB details first.

