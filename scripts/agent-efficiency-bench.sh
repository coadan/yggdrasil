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

cmd=("$ROOT/scripts/headline-bench.sh" "$@")
if [[ "$has_suite" != true ]]; then
  cmd+=(--suite benchmarks/agent-efficiency-broad.edn)
fi
if [[ "$has_out" != true ]]; then
  cmd+=(--out .dev/ygg/agent-efficiency/broad)
fi

exec bash "${cmd[@]}"
