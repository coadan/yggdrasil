#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEFAULT_COMMAND="python3 \"$ROOT/scripts/codex-benchmark-agent.py\""

usage() {
  cat <<'EOF'
Usage: bb headline baseline|codebase-memory|external-baselines|shell-only|prepare-ygg|ygg|agents|reports|prompt-token-check|stage-time-check|token-check|compare|claim-pack|all [options]

Options:
  --suite PATH            Benchmark suite EDN. Default: benchmarks/headline.edn
  --out DIR               Output root. Default: .dev/ygg/headline-bench
  --agent ID              External agent id. Default: codex
  --command CMD           External agent command.
  --prompt-profile NAME   Prompt profile for agent-run. Default: fast
  --timeout-ms N          Timeout passed to agent-run.
  --case ID               Run one benchmark case.
  --cases IDS             Run comma-separated benchmark case ids.
  --max-total-tokens N    High-water token gate for each lane. Default: 999999999999.
  --max-stage-elapsed-ms N
                          Max completed stage ms for the deterministic baseline.
  --max-total-stage-elapsed-ms N
                          Max aggregate completed stage ms for the deterministic baseline.
  --min-prompt-shared-cases N
                          Minimum shared cases for prompt-token-check. Default: 1.
  --skip-existing         Reuse existing baseline or agent-run artifacts.
  --skip-token-check      Skip token telemetry gates during all.
  --codebase-memory-bin PATH
                          codebase-memory-mcp binary for the external baseline.
  --codebase-memory-command CMD
                          Codebase Memory benchmark worker command.
  --dry-run               Print commands without running them.

Commands:
  baseline    Run deterministic Yggdrasil baseline and report.
  codebase-memory
              Run Codebase Memory MCP baseline and report.
  external-baselines
              Run deterministic Yggdrasil and Codebase Memory baseline reports.
  shell-only  Run the external shell-only lane.
  prepare-ygg Prepare the Yggdrasil graph, context, and hints for the external agent.
  ygg      Run the external Yggdrasil lane.
  agents      Run both external lanes.
  reports     Generate shell-only and Yggdrasil lane reports.
  prompt-token-check
              Check prompt-token reduction from existing prompt artifacts.
  stage-time-check
              Check completed stage timings from existing baseline reports.
  token-check Run token telemetry gates for both external lane reports.
  compare     Compare lane reports with bb efficiency.
  claim-pack  Write bundled efficiency, token, and improvement proof artifacts.
  all         Run baseline, both external lanes, reports, token gates, and claim pack.
EOF
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi

action="$1"
shift

suite="benchmarks/headline.edn"
out=".dev/ygg/headline-bench"
agent="codex"
agent_command="$DEFAULT_COMMAND"
prompt_profile="fast"
timeout_ms=""
max_total_tokens="999999999999"
max_stage_elapsed_ms=""
max_total_stage_elapsed_ms=""
min_prompt_shared_cases="1"
skip_token_check=false
skip_existing=false
case_args=()
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
    --case)
      case_args+=(--case "$2")
      shift 2
      ;;
    --cases)
      case_args+=(--cases "$2")
      shift 2
      ;;
    --max-total-tokens)
      max_total_tokens="$2"
      shift 2
      ;;
    --max-stage-elapsed-ms)
      max_stage_elapsed_ms="$2"
      shift 2
      ;;
    --max-total-stage-elapsed-ms)
      max_total_stage_elapsed_ms="$2"
      shift 2
      ;;
    --min-prompt-shared-cases)
      min_prompt_shared_cases="$2"
      shift 2
      ;;
    --skip-token-check)
      skip_token_check=true
      shift
      ;;
    --skip-existing)
      skip_existing=true
      shift
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

append_case_args() {
  if [[ ${#case_args[@]} -gt 0 ]]; then
    args+=("${case_args[@]}")
  fi
}

agent_run() {
  local mode="$1"
  local lane_out="$2"
  local args=(bench agent-run "$suite")
  append_case_args
  args+=(--mode "$mode"
    --agent "$agent"
    --command "$agent_command"
    --prompt-profile "$prompt_profile"
    --out "$lane_out")
  if [[ -n "$timeout_ms" ]]; then
    args+=(--timeout-ms "$timeout_ms")
  fi
  if [[ "$skip_existing" == true ]]; then
    args+=(--skip-existing)
  fi
  run bb "${args[@]}"
}

baseline() {
  local args=(bench agent-baseline "$suite")
  append_case_args
  args+=(--out "$out/ygg-baseline")
  if [[ "$skip_existing" == true ]]; then
    args+=(--skip-existing)
  fi
  run bb "${args[@]}"

  args=(bench agent-report "$suite")
  append_case_args
  args+=(--mode ygg
    --agent ygg-baseline-lexical
    --out "$out/ygg-baseline")
  run bb "${args[@]}"
}

codebase_memory() {
  local args=(bench agent-baseline "$suite")
  append_case_args
  args+=(--retriever codebase-memory
    --out "$out/codebase-memory")
  if [[ "$skip_existing" == true ]]; then
    args+=(--skip-existing)
  fi
  if [[ -n "$codebase_memory_bin" ]]; then
    args+=(--codebase-memory-bin "$codebase_memory_bin")
  fi
  if [[ -n "$codebase_memory_command" ]]; then
    args+=(--codebase-memory-command "$codebase_memory_command")
  fi
  run bb "${args[@]}"

  args=(bench agent-report "$suite")
  append_case_args
  args+=(--mode codebase-memory
    --agent ygg-baseline-codebase-memory
    --out "$out/codebase-memory")
  run bb "${args[@]}"
}

external_baselines() {
  baseline
  codebase_memory
}

shell_only() {
  agent_run shell-only "$out/shell-only"
}

prepare_ygg() {
  local args=(bench agent-packet "$suite")
  append_case_args
  args+=(--mode ygg
    --agent "$agent"
    --out "$out/ygg")
  run bb "${args[@]}"
}

ygg() {
  prepare_ygg
  agent_run ygg "$out/ygg"
}

reports() {
  local args=(bench agent-report "$suite")
  append_case_args
  args+=(--mode shell-only
    --agent "$agent"
    --out "$out/shell-only")
  run bb "${args[@]}"

  args=(bench agent-report "$suite")
  append_case_args
  args+=(--mode ygg
    --agent "$agent"
    --out "$out/ygg")
  run bb "${args[@]}"
}

token_check() {
  local args=(bench agent-check "$suite")
  append_case_args
  args+=(--mode shell-only
    --agent "$agent"
    --out "$out/shell-only"
    --max-total-tokens "$max_total_tokens")
  run bb "${args[@]}"

  args=(bench agent-check "$suite")
  append_case_args
  args+=(--mode ygg
    --agent "$agent"
    --out "$out/ygg"
    --max-total-tokens "$max_total_tokens")
  run bb "${args[@]}"
}

prompt_token_check() {
  local measure_path="$out/prompt-token-measure.json"
  local gate_path="$out/prompt-token-gate.json"
  run python3 "$ROOT/scripts/prompt-token-measure.py" "$out" \
    --suite "$suite" \
    --out "$measure_path"
  run python3 "$ROOT/scripts/prompt-token-gate.py" "$measure_path" \
    --min-shared-cases "$min_prompt_shared_cases" \
    --out "$gate_path"
}

stage_time_check() {
  local report
  report="$(report_path ygg-baseline)"
  local args=("$ROOT/scripts/stage-time-gate.py" "$report" \
    --out "$out/stage-time-gate.json")
  if [[ -n "$max_stage_elapsed_ms" ]]; then
    args+=(--max-case-stage-ms "$max_stage_elapsed_ms")
  fi
  if [[ -n "$max_total_stage_elapsed_ms" ]]; then
    args+=(--max-total-stage-ms "$max_total_stage_elapsed_ms")
  fi
  run python3 "${args[@]}"
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
    "$(report_path ygg)" \
    --out "$out/summary.json" \
    --markdown-out "$out/REPORT.md"
}

claim_pack() {
  run bb bench claim-pack "$suite" \
    --shell-report "$(report_path shell-only)" \
    --ygg-report "$(report_path ygg)" \
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
  prepare-ygg)
    prepare_ygg
    ;;
  ygg)
    ygg
    ;;
  agents)
    shell_only
    ygg
    ;;
  reports)
    reports
    ;;
  prompt-token-check)
    prompt_token_check
    ;;
  stage-time-check)
    stage_time_check
    ;;
  token-check)
    token_check
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
    ygg
    reports
    if [[ "$skip_token_check" != true ]]; then
      prompt_token_check
      token_check
    fi
    if [[ -n "$max_stage_elapsed_ms" || -n "$max_total_stage_elapsed_ms" ]]; then
      stage_time_check
    fi
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
