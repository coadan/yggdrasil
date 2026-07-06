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
  --min-file-recall-at-5 N
                      Minimum aggregate file recall@5. Default: 0.60.
  --min-file-recall-at-10 N
                      Minimum aggregate file recall@10. Default: 0.60.
  --min-file-recall-at-20 N
                      Minimum aggregate file recall@20. Default: 0.80.
  --min-mrr N         Minimum aggregate file MRR. Default: 0.50.
  --max-noise-at-20 N Maximum aggregate noise@20. Default: 0.90.
  --min-expected-evidence-citation-rate N
                      Minimum aggregate expected-evidence citation rate.
                      Default: 0.80.
  --min-case-expected-evidence-citation-rate N
                      Minimum per-case expected-evidence citation rate.
                      Default: 0.50.
  --min-repos N       Minimum distinct completed benchmark repos.
  --min-source-kind-cases KIND=N
                      Minimum completed cases with scoreable source-kind
                      coverage. May be repeated.
  --min-measured-problem-classes N
                      Minimum measured problem-class groups.
  --min-measured-architecture-classes N
                      Minimum measured architecture-class groups.
  --require-broad-claim-readiness
                      Fail agent-check unless report claimReadiness supports
                      broad benchmark claims.
  --require-docs-claim-readiness
                      Fail agent-check unless report docsClaimReadiness supports
                      documentation-handling claims.
  --provider PROVIDER Embedding provider for semantic/hybrid retrievers.
  --model MODEL       Embedding model for semantic/hybrid retrievers.
  --batch-size N      Embedding batch size for semantic/hybrid retrievers.
  --setup-check       Only check required local benchmark repos.
  --check-only        Reuse existing score artifacts; skip baseline regeneration.
  --reuse-context     Reuse compatible baseline context manifests while regenerating scores. Default.
  --fresh-context     Rebuild baseline contexts even when compatible manifests exist.
  --skip-existing     Skip regenerating current matching baseline case artifacts.
  --stage-time-baseline-report PATH
                      Baseline agent-report JSON/glob for stage timing regression checks.
                      May be repeated. When present, the gate requires strict-warm
                      reports and applies repeat-run regression thresholds by default.
  --max-case-stage-ms N
                      Fail if any case stage exceeds this elapsed ms.
  --max-total-stage-ms N
                      Fail if any aggregate stage exceeds this elapsed ms.
  --max-case-stage-regression-ms N
                      Fail if a case stage regresses by more than this ms.
                      Default with --stage-time-baseline-report: 30000.
  --max-total-stage-regression-ms N
                      Fail if an aggregate stage regresses by more than this ms.
                      Default with --stage-time-baseline-report: 120000.
  --max-case-stage-regression-ratio N
                      Fail if a case stage current/baseline ratio exceeds this value.
                      Default with --stage-time-baseline-report: 1.50.
  --max-total-stage-regression-ratio N
                      Fail if an aggregate stage current/baseline ratio exceeds this value.
                      Default with --stage-time-baseline-report: 1.50.
  --min-stage-regression-ms N
                      Ignore timing deltas at or below this ms floor.
                      Default with --stage-time-baseline-report: 5000.
  --stage NAME        Limit stage timing checks to one stage. May be repeated.
  --skip-setup-check  Run without checking local benchmark repos first.
  --dry-run           Print commands without running them.

The gate runs the deterministic Yggdrasil baseline and checks the generated
score artifacts. Baseline regeneration reuses compatible context manifests by
default; the manifest key includes benchmark options and a Yggdrasil
implementation fingerprint, so code changes force a fresh context. Use
--fresh-context to profile full rebuild costs. Use --check-only before a claim
when current artifacts already exist; stale or missing score artifacts still
fail the strict check. Generated worktrees, XTDB stores, reports, and scores
stay under the output root. Every gate run also writes stage-time-gate.json so
performance regressions and slow stage classes are visible without configuring
timing thresholds.
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
min_file_recall_at_5="0.60"
min_file_recall_at_10="0.60"
min_file_recall_at_20="0.80"
min_mrr="0.50"
max_noise_at_20="0.90"
provider=""
model=""
batch_size=""
setup_check_only=false
check_only=false
reuse_context=true
skip_existing=false
stage_time_baseline_reports=()
stage_time_baseline_report_count=0
max_case_stage_ms=""
max_total_stage_ms=""
max_case_stage_regression_ms=""
max_total_stage_regression_ms=""
max_case_stage_regression_ratio=""
max_total_stage_regression_ratio=""
min_expected_evidence_citation_rate="0.80"
min_case_expected_evidence_citation_rate="0.50"
min_repos=""
min_source_kind_cases=()
min_source_kind_case_count=0
min_measured_problem_classes=""
min_measured_architecture_classes=""
require_broad_claim_readiness=false
require_docs_claim_readiness=false
min_stage_regression_ms=""
stage_filters=()
stage_filter_count=0
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
    --min-file-recall-at-5)
      min_file_recall_at_5="$2"
      shift 2
      ;;
    --min-file-recall-at-10)
      min_file_recall_at_10="$2"
      shift 2
      ;;
    --min-file-recall-at-20)
      min_file_recall_at_20="$2"
      shift 2
      ;;
    --min-mrr)
      min_mrr="$2"
      shift 2
      ;;
    --max-noise-at-20)
      max_noise_at_20="$2"
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
    --reuse-context)
      reuse_context=true
      shift
      ;;
    --fresh-context)
      reuse_context=false
      shift
      ;;
    --skip-existing)
      skip_existing=true
      shift
      ;;
    --stage-time-baseline-report)
      stage_time_baseline_reports+=("$2")
      stage_time_baseline_report_count=$((stage_time_baseline_report_count + 1))
      shift 2
      ;;
    --max-case-stage-ms)
      max_case_stage_ms="$2"
      shift 2
      ;;
    --max-total-stage-ms)
      max_total_stage_ms="$2"
      shift 2
      ;;
    --max-case-stage-regression-ms)
      max_case_stage_regression_ms="$2"
      shift 2
      ;;
    --max-total-stage-regression-ms)
      max_total_stage_regression_ms="$2"
      shift 2
      ;;
    --max-case-stage-regression-ratio)
      max_case_stage_regression_ratio="$2"
      shift 2
      ;;
    --max-total-stage-regression-ratio)
      max_total_stage_regression_ratio="$2"
      shift 2
      ;;
    --min-expected-evidence-citation-rate)
      min_expected_evidence_citation_rate="$2"
      shift 2
      ;;
    --min-case-expected-evidence-citation-rate)
      min_case_expected_evidence_citation_rate="$2"
      shift 2
      ;;
    --min-repos)
      min_repos="$2"
      shift 2
      ;;
    --min-source-kind-cases)
      min_source_kind_cases+=("$2")
      min_source_kind_case_count=$((min_source_kind_case_count + 1))
      shift 2
      ;;
    --min-measured-problem-classes)
      min_measured_problem_classes="$2"
      shift 2
      ;;
    --min-measured-architecture-classes)
      min_measured_architecture_classes="$2"
      shift 2
      ;;
    --require-broad-claim-readiness)
      require_broad_claim_readiness=true
      shift
      ;;
    --require-docs-claim-readiness)
      require_docs_claim_readiness=true
      shift
      ;;
    --min-stage-regression-ms)
      min_stage_regression_ms="$2"
      shift 2
      ;;
    --stage)
      stage_filters+=("$2")
      stage_filter_count=$((stage_filter_count + 1))
      shift 2
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
  if [[ "$reuse_context" == true ]]; then
    baseline_args+=(--reuse-context)
  fi
  if [[ "$skip_existing" == true ]]; then
    baseline_args+=(--skip-existing)
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

agent_check_args=(
  --mode ygg
  --agent "$agent_id"
  --min-cases "$min_count"
  --min-runs "$min_count"
  --min-file-recall-at-5 "$min_file_recall_at_5"
  --min-file-recall-at-10 "$min_file_recall_at_10"
  --min-file-recall-at-20 "$min_file_recall_at_20"
  --min-mrr "$min_mrr"
  --max-noise-at-20 "$max_noise_at_20"
  --max-graph-expectation-failures 0
  --max-benchmark-preflight-blockers 0
  --max-case-total-tokens 24000
  --max-input-hinted-cases 0
  --max-unverified-score-runs 0
  --out "$out"
)
if [[ -n "$min_expected_evidence_citation_rate" ]]; then
  agent_check_args+=(--min-expected-evidence-citation-rate "$min_expected_evidence_citation_rate")
fi
if [[ -n "$min_case_expected_evidence_citation_rate" ]]; then
  agent_check_args+=(--min-case-expected-evidence-citation-rate "$min_case_expected_evidence_citation_rate")
fi
if [[ -n "$min_repos" ]]; then
  agent_check_args+=(--min-repos "$min_repos")
fi
if [[ "$min_source_kind_case_count" -gt 0 ]]; then
  for source_kind_case_minimum in "${min_source_kind_cases[@]}"; do
    agent_check_args+=(--min-source-kind-cases "$source_kind_case_minimum")
  done
fi
if [[ -n "$min_measured_problem_classes" ]]; then
  agent_check_args+=(--min-measured-problem-classes "$min_measured_problem_classes")
fi
if [[ -n "$min_measured_architecture_classes" ]]; then
  agent_check_args+=(--min-measured-architecture-classes "$min_measured_architecture_classes")
fi
if [[ "$require_broad_claim_readiness" == true ]]; then
  agent_check_args+=(--require-broad-claim-readiness)
fi
if [[ "$require_docs_claim_readiness" == true ]]; then
  agent_check_args+=(--require-docs-claim-readiness)
fi

run_bench agent-check "${agent_check_args[@]}"

stage_time_args=("$out/*/agent-report.json" --out "$out/stage-time-gate.json")
if [[ "$stage_time_baseline_report_count" -gt 0 ]]; then
  for report in "${stage_time_baseline_reports[@]}"; do
    stage_time_args+=(--baseline-report "$report")
  done
  stage_time_args+=(--require-strict-warm)
  if [[ -z "$max_case_stage_regression_ms" ]]; then
    max_case_stage_regression_ms="30000"
  fi
  if [[ -z "$max_total_stage_regression_ms" ]]; then
    max_total_stage_regression_ms="120000"
  fi
  if [[ -z "$max_case_stage_regression_ratio" ]]; then
    max_case_stage_regression_ratio="1.50"
  fi
  if [[ -z "$max_total_stage_regression_ratio" ]]; then
    max_total_stage_regression_ratio="1.50"
  fi
  if [[ -z "$min_stage_regression_ms" ]]; then
    min_stage_regression_ms="5000"
  fi
fi
if [[ -n "$max_case_stage_ms" ]]; then
  stage_time_args+=(--max-case-stage-ms "$max_case_stage_ms")
fi
if [[ -n "$max_total_stage_ms" ]]; then
  stage_time_args+=(--max-total-stage-ms "$max_total_stage_ms")
fi
if [[ -n "$max_case_stage_regression_ms" ]]; then
  stage_time_args+=(--max-case-stage-regression-ms "$max_case_stage_regression_ms")
fi
if [[ -n "$max_total_stage_regression_ms" ]]; then
  stage_time_args+=(--max-total-stage-regression-ms "$max_total_stage_regression_ms")
fi
if [[ -n "$max_case_stage_regression_ratio" ]]; then
  stage_time_args+=(--max-case-stage-regression-ratio "$max_case_stage_regression_ratio")
fi
if [[ -n "$max_total_stage_regression_ratio" ]]; then
  stage_time_args+=(--max-total-stage-regression-ratio "$max_total_stage_regression_ratio")
fi
if [[ -n "$min_stage_regression_ms" ]]; then
  stage_time_args+=(--min-stage-regression-ms "$min_stage_regression_ms")
fi
if [[ "$stage_filter_count" -gt 0 ]]; then
  for stage in "${stage_filters[@]}"; do
    stage_time_args+=(--stage "$stage")
  done
fi
run python3 scripts/stage-time-gate.py "${stage_time_args[@]}"
