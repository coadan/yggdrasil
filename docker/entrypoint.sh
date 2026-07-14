#!/usr/bin/env sh
set -eu

export YGG_STORAGE_ROOT="${YGG_STORAGE_ROOT:-/data}"
export YGG_CONFIG_HOME="${YGG_CONFIG_HOME:-/data/config}"
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
