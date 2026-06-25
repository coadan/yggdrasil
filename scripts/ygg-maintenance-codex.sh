#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: ygg-maintenance-codex.sh --work work.json --result result.json" >&2
}

WORK_PATH=""
RESULT_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --work)
      WORK_PATH="${2:-}"
      shift 2
      ;;
    --result)
      RESULT_PATH="${2:-}"
      shift 2
      ;;
    *)
      usage
      exit 64
      ;;
  esac
done

if [[ -z "${WORK_PATH}" || -z "${RESULT_PATH}" ]]; then
  usage
  exit 64
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$(dirname "${RESULT_PATH}")"

ADD_DIR_ARGS=(
  --add-dir "$(cd "$(dirname "${WORK_PATH}")" && pwd)"
  --add-dir "$(cd "$(dirname "${RESULT_PATH}")" && pwd)"
)

if [[ -n "${YGG_CODEX_MAINTENANCE_ADD_DIRS:-}" ]]; then
  IFS=':' read -r -a EXTRA_DIRS <<< "${YGG_CODEX_MAINTENANCE_ADD_DIRS}"
  for dir in "${EXTRA_DIRS[@]}"; do
    if [[ -n "${dir}" && -d "${dir}" ]]; then
      ADD_DIR_ARGS+=(--add-dir "$(cd "${dir}" && pwd)")
    fi
  done
fi

MODEL_ARGS=()
if [[ -n "${YGG_CODEX_MAINTENANCE_MODEL:-}" ]]; then
  MODEL_ARGS=(-m "${YGG_CODEX_MAINTENANCE_MODEL}")
fi

codex exec \
  -C "${ROOT_DIR}" \
  --skip-git-repo-check \
  -s workspace-write \
  -a never \
  "${ADD_DIR_ARGS[@]}" \
  "${MODEL_ARGS[@]}" \
  - <<EOF
You are completing one Yggdrasil index maintenance queue item.

Input work item JSON path:
${WORK_PATH}

Required result JSON path:
${RESULT_PATH}

Read the work item JSON. Its payload contains the task, allowed actions,
expectedResultSchema, and often an expectedOutput example or messages. Produce a
single JSON object that matches the expected result schema and write it to the
required result path.

Rules:
- Do not edit source repositories, project configs, correction facts, or queue
  item files.
- Do not run ygg sync work complete, validate, apply, reject, or release.
- Use only ids and evidence present in the work item unless reading the attached
  project directories is necessary to verify a bounded fact.
- Prefer conservative no-change, investigate, needs-human, or needs-scanner
  results with an empty correctionPatch when evidence is insufficient.
- Write valid JSON only to the result path.
- Your final assistant message can be brief; Yggdrasil will read the result file.
EOF

if [[ ! -s "${RESULT_PATH}" ]]; then
  echo "Codex did not write result JSON: ${RESULT_PATH}" >&2
  exit 1
fi

python3 - "${RESULT_PATH}" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    value = json.load(f)
if not isinstance(value, dict):
    raise SystemExit(f"Result JSON must be an object: {path}")
PY
