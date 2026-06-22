#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEFAULT_COMMAND='codex -a never exec --sandbox read-only --output-schema "$AGRAPH_BENCH_OUTPUT_SCHEMA" -o "$AGRAPH_BENCH_RESULT" - < "$AGRAPH_BENCH_PROMPT"'

usage() {
  cat <<'EOF'
Usage: bb headline baseline|codebase-memory|external-baselines|shell-only|agraph|agents|reports|compare|claim-pack|all [options]

Options:
  --suite PATH            Benchmark suite EDN. Default: benchmarks/headline.edn
  --out DIR               Output root. Default: .dev/agraph/headline-bench
  --agent ID              External agent id. Default: codex
  --command CMD           External agent command.
  --prompt-profile NAME   Prompt profile for agent-run. Default: fast
  --timeout-ms N          Timeout passed to agent-run.
  --codebase-memory-bin PATH
                          codebase-memory-mcp binary for the external baseline.
  --codebase-memory-command CMD
                          Codebase Memory benchmark worker command.
  --dry-run               Print commands without running them.

Commands:
  baseline    Run deterministic AGraph baseline and report.
  codebase-memory
              Run Codebase Memory MCP baseline and report.
  external-baselines
              Run deterministic AGraph and Codebase Memory baseline reports.
  shell-only  Run the external shell-only lane.
  agraph      Run the external AGraph lane.
  agents      Run both external lanes.
  reports     Generate shell-only and AGraph lane reports.
  compare     Compare lane reports with bb efficiency.
  claim-pack  Write bundled efficiency, token, and improvement proof artifacts.
  all         Run baseline, both external lanes, reports, and claim pack.
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
codebase_memory_bin=""
codebase_memory_command=""
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
    --codebase-memory-bin)
      codebase_memory_bin="$2"
      shift 2
      ;;
    --codebase-memory-command)
      codebase_memory_command="$2"
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

codebase_memory() {
  local args=(bench agent-baseline "$suite"
    --retriever codebase-memory
    --out "$out/codebase-memory")
  if [[ -n "$codebase_memory_bin" ]]; then
    args+=(--codebase-memory-bin "$codebase_memory_bin")
  fi
  if [[ -n "$codebase_memory_command" ]]; then
    args+=(--codebase-memory-command "$codebase_memory_command")
  fi
  run bb "${args[@]}"

  run bb bench agent-report "$suite" \
    --mode codebase-memory \
    --agent agraph-baseline-codebase-memory \
    --out "$out/codebase-memory"
}

external_baselines() {
  baseline
  codebase_memory
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

report_path() {
  local lane="$1"
  if [[ "$dry_run" == true ]]; then
    printf '%s\n' "$out/$lane/*/agent-report.json"
    return 0
  fi
  local paths=("$out/$lane"/*/agent-report.json)
  if [[ ${#paths[@]} -ne 1 || ! -f "${paths[0]}" ]]; then
    echo "Expected exactly one agent report under $out/$lane" >&2
    return 1
  fi
  printf '%s\n' "${paths[0]}"
}

compare() {
  run bb efficiency \
    "$(report_path shell-only)" \
    "$(report_path agraph)" \
    --out "$out/summary.json" \
    --markdown-out "$out/REPORT.md"
}

claim_pack() {
  run bb bench claim-pack "$suite" \
    --shell-report "$(report_path shell-only)" \
    --agraph-report "$(report_path agraph)" \
    --out "$out/claim-pack"
}

case "$action" in
  baseline)
    baseline
    ;;
  codebase-memory)
    codebase_memory
    ;;
  external-baselines)
    external_baselines
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
  claim-pack)
    claim_pack
    ;;
  all)
    baseline
    shell_only
    agraph
    reports
    claim_pack
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
