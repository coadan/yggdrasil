#!/usr/bin/env bash
set -euo pipefail

limit="${1:-1500}"

if ! [[ "$limit" =~ ^[0-9]+$ ]]; then
  echo "Usage: scripts/large-file-audit.sh [line-limit]" >&2
  exit 2
fi

is_source_file() {
  case "$1" in
    src/*.clj|src/*.cljc|src/*.cljs|test/*.clj|test/*.cljc|test/*.cljs|report-ui/src/*.ts|report-ui/src/*.tsx)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_generated_exception() {
  case "$1" in
    */package-lock.json|*/pnpm-lock.yaml|*/yarn.lock|*/Cargo.lock|*/go.sum|resources/agraph/report-ui/assets/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

source_over=()
generated_over=()
other_over=()

while IFS= read -r -d '' file; do
  [[ -f "$file" ]] || continue
  lines="$(wc -l < "$file" | tr -d '[:space:]')"
  if (( lines <= limit )); then
    continue
  fi

  row="$(printf '%6d %s' "$lines" "$file")"
  if is_source_file "$file"; then
    source_over+=("$row")
  elif is_generated_exception "$file"; then
    generated_over+=("$row")
  else
    other_over+=("$row")
  fi
done < <(git ls-files -z)

if ((${#source_over[@]} > 0)); then
  echo "Hand-written source/test files over ${limit} lines:"
  printf '%s\n' "${source_over[@]}" | sort -nr
fi

if ((${#other_over[@]} > 0)); then
  echo "Other tracked files over ${limit} lines:"
  printf '%s\n' "${other_over[@]}" | sort -nr
fi

if ((${#generated_over[@]} > 0)); then
  echo "Generated dependency/build files over ${limit} lines:"
  printf '%s\n' "${generated_over[@]}" | sort -nr
fi

if ((${#source_over[@]} > 0 || ${#other_over[@]} > 0)); then
  exit 1
fi

echo "No hand-written source/test files over ${limit} lines."
