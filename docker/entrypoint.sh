#!/usr/bin/env sh
set -eu

export AGRAPH_XTDB_PATH="${AGRAPH_XTDB_PATH:-/data/xtdb}"

if [ "$#" -eq 0 ]; then
  exec agraph help
fi

case "$1" in
  agraph)
    shift
    exec agraph "$@"
    ;;
  agraph-mcp)
    shift
    exec agraph-mcp "$@"
    ;;
  mcp)
    shift
    exec agraph mcp "$@"
    ;;
  sh|bash|clojure|bb|git)
    exec "$@"
    ;;
  *)
    exec agraph "$@"
    ;;
esac
