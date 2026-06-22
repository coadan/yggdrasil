#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

has_suite=false
has_out=false
for arg in "$@"; do
  case "$arg" in
    --suite)
      has_suite=true
      ;;
    --out)
      has_out=true
      ;;
  esac
done

defaults=()
if [[ "$has_suite" != true ]]; then
  defaults+=(--suite benchmarks/agent-efficiency-broad.edn)
fi
if [[ "$has_out" != true ]]; then
  defaults+=(--out .dev/ygg/agent-efficiency/broad)
fi

exec bash "$ROOT/scripts/headline-bench.sh" "$@" "${defaults[@]}"
