#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage: bb bench:claim-quick [benchmark-gate options]

Runs the small non-synthetic claim-readiness lane:
  --suite benchmarks/historical-replay-claim-quick.edn
  --out .dev/ygg/claim-quick-gate
  --min-mrr 0.30
  --max-noise-at-20 0.80
  --min-expected-evidence-citation-rate 0.80
  --min-case-expected-evidence-citation-rate 0.50
  --min-repos 6
  --min-source-kind-cases javascript=2
  --min-source-kind-cases python=2
  --min-source-kind-cases doc=2
  --min-source-kind-cases dotnet=1
  --min-source-kind-cases terraform=1
  --min-source-kind-cases sql=1
  --min-measured-problem-classes 3
  --min-measured-architecture-classes 3

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
  --suite benchmarks/historical-replay-claim-quick.edn \
  --out .dev/ygg/claim-quick-gate \
  --min-mrr 0.30 \
  --max-noise-at-20 0.80 \
  --min-expected-evidence-citation-rate 0.80 \
  --min-case-expected-evidence-citation-rate 0.50 \
  --min-repos 6 \
  --min-source-kind-cases javascript=2 \
  --min-source-kind-cases python=2 \
  --min-source-kind-cases doc=2 \
  --min-source-kind-cases dotnet=1 \
  --min-source-kind-cases terraform=1 \
  --min-source-kind-cases sql=1 \
  --min-measured-problem-classes 3 \
  --min-measured-architecture-classes 3 \
  "$@"
