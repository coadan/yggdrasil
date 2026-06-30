#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage: bb bench:gate [options]

Options:
  --suite PATH        Benchmark suite EDN. Default: benchmarks/architecture-synthetic.edn
  --manifest PATH     Benchmark repo manifest. Default: benchmarks/repos.edn
  --out DIR           Output root. Default: .dev/ygg/benchmark-gate
  --case ID           Run one benchmark case.
  --cases ID,ID       Run selected benchmark cases.
  --retriever MODE    Baseline retriever. Default: auto.
  --fusion-strategy S Baseline fusion strategy, for retrieval ablations.
  --sqlite-fts        Enable the SQLite FTS lane, for retrieval ablations.
  --diversity-rerank-limit N
                      Apply mechanical diversity reranking to top N candidates.
  --fts-candidate-limit N
                      Candidate limit for the SQLite FTS lane.
  --fts-weight N      Source weight for the SQLite FTS lane.
  --provider PROVIDER Embedding provider for semantic/hybrid retrievers.
  --model MODEL       Embedding model for semantic/hybrid retrievers.
  --batch-size N      Embedding batch size for semantic/hybrid retrievers.
  --setup-check       Only check required local benchmark repos.
  --check-only        Reuse existing score artifacts; skip baseline regeneration.
  --skip-setup-check  Run without checking local benchmark repos first.
  --dry-run           Print commands without running them.

The gate runs the deterministic Yggdrasil baseline and checks the generated
score artifacts. Use --check-only before a claim when current artifacts already
exist; stale or missing score artifacts still fail the strict check. Generated
worktrees, XTDB stores, reports, and scores stay under the output root.
EOF
}

suite="benchmarks/architecture-synthetic.edn"
manifest="benchmarks/repos.edn"
out=".dev/ygg/benchmark-gate"
case_id=""
case_ids=""
retriever="auto"
fusion_strategy=""
sqlite_fts=false
diversity_rerank_limit=""
fts_candidate_limit=""
fts_weight=""
provider=""
model=""
batch_size=""
setup_check_only=false
check_only=false
skip_setup_check=false
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
    --manifest)
      manifest="$2"
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
    --retriever)
      retriever="$2"
      shift 2
      ;;
    --fusion-strategy)
      fusion_strategy="$2"
      shift 2
      ;;
    --sqlite-fts)
      sqlite_fts=true
      shift
      ;;
    --diversity-rerank-limit)
      diversity_rerank_limit="$2"
      shift 2
      ;;
    --fts-candidate-limit)
      fts_candidate_limit="$2"
      shift 2
      ;;
    --fts-weight)
      fts_weight="$2"
      shift 2
      ;;
    --provider)
      provider="$2"
      shift 2
      ;;
    --model)
      model="$2"
      shift 2
      ;;
    --batch-size)
      batch_size="$2"
      shift 2
      ;;
    --setup-check)
      setup_check_only=true
      shift
      ;;
    --check-only)
      check_only=true
      shift
      ;;
    --skip-setup-check)
      skip_setup_check=true
      shift
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

min_count=4
if [[ -n "$case_id" && -n "$case_ids" ]]; then
  echo "Use --case or --cases, not both." >&2
  exit 2
elif [[ -n "$case_id" ]]; then
  min_count=1
elif [[ -n "$case_ids" ]]; then
  IFS=',' read -r -a selected_cases <<< "$case_ids"
  min_count="${#selected_cases[@]}"
fi

run_bench() {
  local action="$1"
  shift
  if [[ -n "$case_id" ]]; then
    run ./bin/ygg bench "$action" "$suite" --case "$case_id" "$@"
  elif [[ -n "$case_ids" ]]; then
    run ./bin/ygg bench "$action" "$suite" --cases "$case_ids" "$@"
  else
    run ./bin/ygg bench "$action" "$suite" "$@"
  fi
}

setup_check() {
  run bb bench repos check --manifest "$manifest" --suite "$suite"
}

if [[ "$setup_check_only" == true ]]; then
  setup_check
  exit 0
fi

if [[ "$skip_setup_check" != true ]]; then
  setup_check
fi

if [[ "$check_only" != true ]]; then
  baseline_args=(--out "$out" --retriever "$retriever")
  if [[ -n "$fusion_strategy" ]]; then
    baseline_args+=(--fusion-strategy "$fusion_strategy")
  fi
  if [[ "$sqlite_fts" == true ]]; then
    baseline_args+=(--sqlite-fts)
  fi
  if [[ -n "$diversity_rerank_limit" ]]; then
    baseline_args+=(--diversity-rerank-limit "$diversity_rerank_limit")
  fi
  if [[ -n "$fts_candidate_limit" ]]; then
    baseline_args+=(--fts-candidate-limit "$fts_candidate_limit")
  fi
  if [[ -n "$fts_weight" ]]; then
    baseline_args+=(--fts-weight "$fts_weight")
  fi
  if [[ -n "$provider" ]]; then
    baseline_args+=(--provider "$provider")
  fi
  if [[ -n "$model" ]]; then
    baseline_args+=(--model "$model")
  fi
  if [[ -n "$batch_size" ]]; then
    baseline_args+=(--batch-size "$batch_size")
  fi
  run_bench agent-baseline "${baseline_args[@]}"
fi

agent_id="ygg-baseline-$retriever"
if [[ -n "$fusion_strategy" ]]; then
  agent_id="$agent_id-fusion-$fusion_strategy"
fi
if [[ "$sqlite_fts" == true ]]; then
  agent_id="$agent_id-sqlite-fts"
fi
if [[ -n "$diversity_rerank_limit" ]]; then
  agent_id="$agent_id-diversity-$diversity_rerank_limit"
fi
if [[ -n "$fts_candidate_limit" ]]; then
  agent_id="$agent_id-fts-$fts_candidate_limit"
fi
if [[ -n "$fts_weight" ]]; then
  agent_id="$agent_id-ftsw-$fts_weight"
fi

run_bench agent-check \
  --mode ygg \
  --agent "$agent_id" \
  --min-cases "$min_count" \
  --min-runs "$min_count" \
  --min-file-recall-at-5 0.60 \
  --min-file-recall-at-10 0.60 \
  --min-file-recall-at-20 0.80 \
  --min-mrr 0.50 \
  --max-noise-at-20 0.90 \
  --max-graph-expectation-failures 0 \
  --max-maintenance-preflight-blockers 0 \
  --max-case-total-tokens 24000 \
  --max-input-hinted-cases 0 \
  --max-unverified-score-runs 0 \
  --out "$out"
