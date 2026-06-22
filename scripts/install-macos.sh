#!/usr/bin/env bash
set -euo pipefail

PREFIX="${PREFIX:-$HOME/.local}"
INSTALL_DEPS=0

usage() {
  cat <<'EOF'
Usage: scripts/install-macos.sh [--install-deps] [--prefix DIR]

Installs local ygg and ygg-mcp entrypoints by linking them into DIR/bin.

Options:
  --install-deps   Install missing dependencies with Homebrew.
  --prefix DIR     Install prefix. Defaults to $HOME/.local.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --install-deps)
      INSTALL_DEPS=1
      shift
      ;;
    --prefix)
      PREFIX="$2"
      shift 2
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

if [ "$(uname -s)" != "Darwin" ]; then
  echo "This installer is for macOS. Use bin/ygg directly or Docker on other platforms." >&2
  exit 2
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

missing=()
for cmd in git java clojure; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    missing+=("$cmd")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  if [ "$INSTALL_DEPS" -eq 1 ]; then
    if ! command -v brew >/dev/null 2>&1; then
      echo "Homebrew is required for --install-deps: https://brew.sh" >&2
      exit 1
    fi
    brew install git openjdk@21 clojure/tools/clojure babashka
  else
    echo "Missing dependencies: ${missing[*]}" >&2
    echo "Install them manually, or rerun: scripts/install-macos.sh --install-deps" >&2
    exit 1
  fi
fi

mkdir -p "$PREFIX/bin"
ln -sfn "$REPO_DIR/bin/ygg" "$PREFIX/bin/ygg"
ln -sfn "$REPO_DIR/bin/ygg-mcp" "$PREFIX/bin/ygg-mcp"

echo "Installed:"
echo "- $PREFIX/bin/ygg -> $REPO_DIR/bin/ygg"
echo "- $PREFIX/bin/ygg-mcp -> $REPO_DIR/bin/ygg-mcp"

if ! echo "$PATH" | tr ':' '\n' | grep -qx "$PREFIX/bin"; then
  echo
  echo "Add this to your shell profile if it is not already present:"
  echo "export PATH=\"$PREFIX/bin:\$PATH\""
fi

"$PREFIX/bin/ygg" help >/dev/null
echo
echo "Yggdrasil is ready."
