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
YGG_BIN="$PREFIX/bin/ygg"
YGG_MCP_BIN="$PREFIX/bin/ygg-mcp"
SERVER_LOG="$REPO_DIR/.ygg/server.log"

server_ready() {
  YGG_SERVER_CONNECT_TIMEOUT_MS=0 "$YGG_BIN" status >/dev/null 2>&1
}

fail_with_server_log() {
  echo "$1" >&2
  tail -n 40 "$SERVER_LOG" >&2 || true
  exit 1
}

missing=()
for cmd in git java clojure python3; do
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
    brew install git openjdk@21 clojure/tools/clojure python babashka
  else
    echo "Missing dependencies: ${missing[*]}" >&2
    echo "Install them manually, or rerun: scripts/install-macos.sh --install-deps" >&2
    exit 1
  fi
fi

mkdir -p "$PREFIX/bin"
ln -sfn "$REPO_DIR/bin/ygg" "$YGG_BIN"
ln -sfn "$REPO_DIR/bin/ygg-mcp" "$YGG_MCP_BIN"

echo "Installed:"
echo "- $YGG_BIN -> $REPO_DIR/bin/ygg"
echo "- $YGG_MCP_BIN -> $REPO_DIR/bin/ygg-mcp"

if ! echo "$PATH" | tr ':' '\n' | grep -qx "$PREFIX/bin"; then
  echo
  echo "Add this to your shell profile if it is not already present:"
  echo "export PATH=\"$PREFIX/bin:\$PATH\""
fi

mkdir -p "$(dirname "$SERVER_LOG")"
echo
if server_ready; then
  echo "Yggdrasil server is already running."
else
  echo "Starting Yggdrasil server..."
  nohup "$YGG_BIN" start >"$SERVER_LOG" 2>&1 &
  server_pid=$!
  started=0
  for _ in {1..60}; do
    if server_ready; then
      started=1
      break
    fi
    if ! kill -0 "$server_pid" >/dev/null 2>&1; then
      fail_with_server_log "Yggdrasil server failed to start. Last log lines:"
    fi
    sleep 1
  done
  if [ "$started" -ne 1 ]; then
    fail_with_server_log "Timed out waiting for Yggdrasil server. Last log lines:"
  fi
  echo "Yggdrasil server started."
fi
echo
echo "Yggdrasil is ready."
