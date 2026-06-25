#!/usr/bin/env sh
set -eu

export YGG_XTDB_PATH="${YGG_XTDB_PATH:-/data/xtdb}"
export YGG_SERVER_DIR="${YGG_SERVER_DIR:-/tmp/ygg-server}"

if [ "$#" -eq 0 ]; then
  set -- start
fi

case "$1" in
  start)
    exec ygg start
    ;;
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
    exec ygg-mcp "$@"
    ;;
  sh|bash|clojure|bb|git)
    exec "$@"
    ;;
  *)
    exec ygg "$@"
    ;;
esac
