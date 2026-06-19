#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEFAULT_COMMAND='codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" "$(cat "$AGRAPH_BENCH_PROMPT")"'

usage() {
  cat <<'EOF'
Usage: bb headline baseline|shell-only|agraph|agents|reports|compare|all [options]

Options:
  --suite PATH            Benchmark suite EDN. Default: benchmarks/headline.edn
  --out DIR               Output root. Default: .dev/agraph/headline-bench
  --agent ID              External agent id. Default: codex
  --command CMD           External agent command.
  --prompt-profile NAME   Prompt profile for agent-run. Default: fast
  --timeout-ms N          Timeout passed to agent-run.
  --dry-run               Print commands without running them.

Commands:
  baseline    Run deterministic AGraph baseline and report.
  shell-only  Run the external shell-only lane.
  agraph      Run the external AGraph lane.
  agents      Run both external lanes.
  reports     Generate shell-only and AGraph lane reports.
  compare     Compare lane reports with bb efficiency.
  all         Run baseline, both external lanes, reports, and comparison.
EOF
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi

action="$1"
shift

suite="benchmarks/headline.edn"
out=".dev/agraph/headline-bench"
agent="codex"
agent_command="$DEFAULT_COMMAND"
prompt_profile="fast"
timeout_ms=""
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      suite="$2"
      shift 2
      ;;
    --out)
      out="$2"
      shift 2
      ;;
    --agent)
      agent="$2"
      shift 2
      ;;
    --command)
      agent_command="$2"
      shift 2
      ;;
    --prompt-profile)
      prompt_profile="$2"
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

run() {
  if [[ "$dry_run" == true ]]; then
    printf '+'
    printf ' %q' "$@"
    printf '\n'
  else
    "$@"
  fi
}

agent_run() {
  local mode="$1"
  local lane_out="$2"
  local args=(bench agent-run "$suite"
    --mode "$mode"
    --agent "$agent"
    --command "$agent_command"
    --prompt-profile "$prompt_profile"
    --out "$lane_out")
  if [[ -n "$timeout_ms" ]]; then
    args+=(--timeout-ms "$timeout_ms")
  fi
  run bb "${args[@]}"
}

baseline() {
  run bb bench agent-baseline "$suite" \
    --out "$out/agraph-baseline"

  run bb bench agent-report "$suite" \
    --mode agraph \
    --agent agraph-baseline-lexical \
    --out "$out/agraph-baseline"
}

shell_only() {
  agent_run shell-only "$out/shell-only"
}

agraph() {
  agent_run agraph "$out/agraph"
}

reports() {
  run bb bench agent-report "$suite" \
    --mode shell-only \
    --agent "$agent" \
    --out "$out/shell-only"

  run bb bench agent-report "$suite" \
    --mode agraph \
    --agent "$agent" \
    --out "$out/agraph"
}

compare() {
  run bb efficiency \
    "$out/shell-only/agent-report.json" \
    "$out/agraph/agent-report.json" \
    --out "$out/summary.json"
}

case "$action" in
  baseline)
    baseline
    ;;
  shell-only)
    shell_only
    ;;
  agraph)
    agraph
    ;;
  agents)
    shell_only
    agraph
    ;;
  reports)
    reports
    ;;
  compare)
    compare
    ;;
  all)
    baseline
    shell_only
    agraph
    reports
    compare
    ;;
  -h|--help)
    usage
    ;;
  *)
    echo "Unknown command: $action" >&2
    usage >&2
    exit 2
    ;;
esac
