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

CODEX_ADD_DIRS=()

add_codex_dir() {
  local dir="$1"
  local canonical=""
  local existing=""

  if [[ -z "${dir}" || ! -d "${dir}" ]]; then
    return
  fi

  canonical="$(cd "${dir}" && pwd)"
  for existing in "${CODEX_ADD_DIRS[@]}"; do
    if [[ "${existing}" == "${canonical}" ]]; then
      return
    fi
  done
  CODEX_ADD_DIRS+=("${canonical}")
}

add_codex_dir "$(dirname "${WORK_PATH}")"
add_codex_dir "$(dirname "${RESULT_PATH}")"

if [[ -n "${YGG_CODEX_MAINTENANCE_ADD_DIRS:-}" ]]; then
  IFS=':' read -r -a EXTRA_DIRS <<< "${YGG_CODEX_MAINTENANCE_ADD_DIRS}"
  for dir in "${EXTRA_DIRS[@]}"; do
    add_codex_dir "${dir}"
  done
fi

while IFS= read -r dir; do
  add_codex_dir "${dir}"
done < <(python3 - "${WORK_PATH}" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        value = json.load(f)
except Exception:
    raise SystemExit(0)

for repo in value.get("project", {}).get("repos", []):
    root = repo.get("root")
    if isinstance(root, str) and root:
        print(root)
PY
)

ADD_DIR_ARGS=()
for dir in "${CODEX_ADD_DIRS[@]}"; do
  ADD_DIR_ARGS+=(--add-dir "${dir}")
done

CODEX_ARGS=(
  -C "${ROOT_DIR}"
  --skip-git-repo-check
  -s workspace-write
  -c 'approval_policy="never"'
  "${ADD_DIR_ARGS[@]}"
)

if [[ -n "${YGG_CODEX_MAINTENANCE_MODEL:-}" ]]; then
  CODEX_ARGS+=(-m "${YGG_CODEX_MAINTENANCE_MODEL}")
fi

CODEX_REASONING="${YGG_CODEX_MAINTENANCE_REASONING:-${YGG_MAINTENANCE_REASONING:-medium}}"
CODEX_ARGS+=(-c "model_reasoning_effort=\"${CODEX_REASONING}\"")

codex exec \
  "${CODEX_ARGS[@]}" \
  - <<EOF
You are completing one Yggdrasil index maintenance queue item.

Input work item JSON path:
${WORK_PATH}

Required result JSON path:
${RESULT_PATH}

Read the work item JSON. Follow payload.instructions first when present. Its
payload contains the task, allowed actions, expectedResultSchema, and usually an
expectedOutput example. For Codex command harnesses the payload is compact: use
project.repos[].root to inspect the attached source repositories only when a
bounded fact needs verification. Produce a single JSON object that matches the
expected result schema and write it to the required result path.

Rules:
- Do not edit source repositories, project configs, correction facts, or queue
  item files.
- Do not run ygg sync work complete, validate, apply, reject, or release.
- Use only ids and bounded facts present in the work item plus source files under
  the attached project repo roots. Do not infer project meaning from names alone.
- Most packets should be straightforward: write one small correction patch, or
  return an empty correctionPatch with a conservative recommendation when the
  packet evidence is not enough.
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
