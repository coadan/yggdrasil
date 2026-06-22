#!/usr/bin/env sh
set -eu

export YGG_XTDB_PATH="${YGG_XTDB_PATH:-/data/xtdb}"

if [ "$#" -eq 0 ]; then
  exec ygg help
fi

case "$1" in
  ygg)
    shift
    exec ygg "$@"
    ;;
  ygg-mcp)
    shift
    exec ygg-mcp "$@"
    ;;
  mcp)
    shift
    exec ygg mcp "$@"
    ;;
  sh|bash|clojure|bb|git)
    exec "$@"
    ;;
  *)
    exec ygg "$@"
    ;;
esac
