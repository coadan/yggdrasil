#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage: bb bench:docs-claim [benchmark-gate options]

Runs the small non-synthetic docs-handling claim lane:
  --suite benchmarks/historical-docs-claim-quick.edn
  --out .dev/ygg/docs-claim-gate
  --min-mrr 0.30
  --max-noise-at-20 0.90
  --min-expected-evidence-citation-rate 0.80
  --min-case-expected-evidence-citation-rate 0.50
  --min-repos 3
  --min-source-kind-cases doc=4
  --min-measured-problem-classes 1
  --min-measured-architecture-classes 1
  --require-docs-claim-readiness

Pass ordinary benchmark-gate options such as --check-only, --skip-existing,
--setup-check, --case, --cases, --provider, --model, or --dry-run. Later
options override these defaults.
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      usage
      exit 0
      ;;
  esac
done

exec bash scripts/benchmark-gate.sh \
  --suite benchmarks/historical-docs-claim-quick.edn \
  --out .dev/ygg/docs-claim-gate \
  --min-mrr 0.30 \
  --max-noise-at-20 0.90 \
  --min-expected-evidence-citation-rate 0.80 \
  --min-case-expected-evidence-citation-rate 0.50 \
  --min-repos 3 \
  --min-source-kind-cases doc=4 \
  --min-measured-problem-classes 1 \
  --min-measured-architecture-classes 1 \
  --require-docs-claim-readiness \
  "$@"
