#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage: bb bench:claim-full [benchmark-gate options]

Runs the authoritative non-synthetic historical replay claim lane:
  --suite benchmarks/historical-replay-full.edn
  --out .dev/ygg/full-claim-gate
  --min-cases 16
  --min-mrr 0.30
  --max-noise-at-20 0.80
  --min-expected-evidence-citation-rate 0.80
  --min-case-expected-evidence-citation-rate 0.50
  --max-blocking-hint-diagnostic-runs 0
  --min-repos 10
  --min-source-kind-cases javascript=3
  --min-source-kind-cases python=2
  --min-source-kind-cases doc=4
  --min-source-kind-cases dotnet=1
  --min-source-kind-cases go=1
  --min-source-kind-cases terraform=1
  --min-source-kind-cases yaml=1
  --min-source-kind-cases sql=1
  --min-source-kind-cases text=1
  --min-source-kind-cases ci=1
  --min-source-kind-cases manifest=1
  --min-source-kind-cases java=1
  --min-measured-problem-classes 3
  --min-measured-architecture-classes 3
  --require-broad-claim-readiness

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
  --suite benchmarks/historical-replay-full.edn \
  --out .dev/ygg/full-claim-gate \
  --min-cases 16 \
  --min-mrr 0.30 \
  --max-noise-at-20 0.80 \
  --min-expected-evidence-citation-rate 0.80 \
  --min-case-expected-evidence-citation-rate 0.50 \
  --min-repos 10 \
  --min-source-kind-cases javascript=3 \
  --min-source-kind-cases python=2 \
  --min-source-kind-cases doc=4 \
  --min-source-kind-cases dotnet=1 \
  --min-source-kind-cases go=1 \
  --min-source-kind-cases terraform=1 \
  --min-source-kind-cases yaml=1 \
  --min-source-kind-cases sql=1 \
  --min-source-kind-cases text=1 \
  --min-source-kind-cases ci=1 \
  --min-source-kind-cases manifest=1 \
  --min-source-kind-cases java=1 \
  --min-measured-problem-classes 3 \
  --min-measured-architecture-classes 3 \
  --require-broad-claim-readiness \
  "$@"
