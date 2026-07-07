#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage: bb bench:patch-claim [options]

Checks existing OSS issue-to-patch replay artifacts:
  --suite benchmarks/oss-issue-patch-replay.edn
  --out .dev/ygg/patch-claim-gate
  --mode ygg
  --agent ygg-agent
  --min-cases 8
  --min-repos 6
  --min-patch-file-recall 0.50
  --min-patch-file-f1 0.50
  --min-patch-verifier-pass-rate 1.00

Run `bench agent-run` and `bench agent-report` for the same suite/out/agent
before this gate. This script does not run a deterministic baseline because
patch claims require an actual worktree diff from an agent command.
EOF
}

bench_cmd=(clojure -M:run -m ygg.cli-bench)
suite="benchmarks/oss-issue-patch-replay.edn"
manifest="benchmarks/repos.edn"
out=".dev/ygg/patch-claim-gate"
mode="ygg"
agent="ygg-agent"
case_id=""
case_ids=""
min_cases="8"
min_repos="6"
min_patch_file_recall="0.50"
min_patch_file_f1="0.50"
min_patch_verifier_pass_rate="1.00"
min_source_kind_cases=(
  "dotnet=1"
  "java=1"
  "javascript=1"
  "manifest=1"
  "python=2"
  "terraform=1"
  "sql=1"
  "text=1"
)
custom_source_kind_cases=false
setup_check_only=false
dry_run=false
json=false

run_cmd() {
  if [[ "$dry_run" == true ]]; then
    printf '%q ' "$@"
    printf '\n'
  else
    "$@"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --suite)
      suite="$2"
      shift 2
      ;;
    --manifest)
      manifest="$2"
      shift 2
      ;;
    --out)
      out="$2"
      shift 2
      ;;
    --mode)
      mode="$2"
      shift 2
      ;;
    --agent)
      agent="$2"
      shift 2
      ;;
    --case)
      case_id="$2"
      shift 2
      ;;
    --cases)
      case_ids="$2"
      shift 2
      ;;
    --min-cases)
      min_cases="$2"
      shift 2
      ;;
    --min-repos)
      min_repos="$2"
      shift 2
      ;;
    --min-source-kind-cases)
      if [[ "$custom_source_kind_cases" == false ]]; then
        min_source_kind_cases=()
        custom_source_kind_cases=true
      fi
      min_source_kind_cases+=("$2")
      shift 2
      ;;
    --min-patch-file-recall)
      min_patch_file_recall="$2"
      shift 2
      ;;
    --min-patch-file-f1)
      min_patch_file_f1="$2"
      shift 2
      ;;
    --min-patch-verifier-pass-rate)
      min_patch_verifier_pass_rate="$2"
      shift 2
      ;;
    --setup-check)
      setup_check_only=true
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --json)
      json=true
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_check_args=(repos check --manifest "$manifest" --suite "$suite")
run_cmd "${bench_cmd[@]}" "${repo_check_args[@]}"

if [[ "$setup_check_only" == true ]]; then
  exit 0
fi

agent_check_args=(
  agent-check "$suite"
  --mode "$mode"
  --agent "$agent"
  --min-cases "$min_cases"
  --min-runs "$min_cases"
  --min-repos "$min_repos"
  --min-patch-file-recall "$min_patch_file_recall"
  --min-patch-file-f1 "$min_patch_file_f1"
  --min-patch-verifier-pass-rate "$min_patch_verifier_pass_rate"
  --max-unverified-score-runs 0
  --out "$out"
)

if [[ -n "$case_id" ]]; then
  agent_check_args+=(--case "$case_id")
fi
if [[ -n "$case_ids" ]]; then
  agent_check_args+=(--cases "$case_ids")
fi
for source_kind_case_minimum in "${min_source_kind_cases[@]}"; do
  agent_check_args+=(--min-source-kind-cases "$source_kind_case_minimum")
done
if [[ "$json" == true ]]; then
  agent_check_args+=(--json)
fi

run_cmd "${bench_cmd[@]}" "${agent_check_args[@]}"
