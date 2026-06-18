# Stage 4: Report Bundle

Status: superseded by `.dev/plans/unified-rendering/`.

The original Graphify-style report-bundle plan has been folded into the unified
rendering roadmap. The implemented path now uses:

- `agraph.report/v2` in `report.json` for structured report data
- `REPORT.mdx` for readable narrative source
- `agraph.graph/v2` in `graph.json`, `systems.json`, and graph-mode
  `.graph.json` files
- the shared React/MDX/Sigma renderer packaged from
  `resources/agraph/report-ui/`

See `.dev/plans/unified-rendering/` for the current report and graph rendering
contract.
