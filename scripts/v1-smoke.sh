#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
KEEP=0

usage() {
  cat <<'EOF'
Usage: scripts/v1-smoke.sh [--keep]

Runs the V1 install/start smoke gate in a temporary copy of the fixture repo.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --keep)
      KEEP=1
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

WORK_DIR="${AGRAPH_V1_SMOKE_DIR:-$(mktemp -d)}"
PREFIX="$WORK_DIR/prefix"
FIXTURE_REPO="$WORK_DIR/repo"

cleanup() {
  if [ "$KEEP" -eq 0 ] && [ -z "${AGRAPH_V1_SMOKE_DIR:-}" ]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

install_wrappers() {
  if [ "$(uname -s)" = "Darwin" ]; then
    "$REPO_DIR/scripts/install-macos.sh" --prefix "$PREFIX" > "$WORK_DIR/install.log"
  else
    mkdir -p "$PREFIX/bin"
    ln -sfn "$REPO_DIR/bin/agraph" "$PREFIX/bin/agraph"
    ln -sfn "$REPO_DIR/bin/agraph-mcp" "$PREFIX/bin/agraph-mcp"
    printf 'Installed symlink fallback for non-macOS smoke.\n' > "$WORK_DIR/install.log"
  fi
}

install_wrappers
"$PREFIX/bin/agraph" help > "$WORK_DIR/help.txt"
test -x "$PREFIX/bin/agraph"
test -x "$PREFIX/bin/agraph-mcp"

cp -R "$REPO_DIR/test/fixtures/project-repo" "$FIXTURE_REPO"
printf '\n[dependencies]\nserde = "1"\n' >> "$FIXTURE_REPO/services/api/Cargo.toml"
mkdir -p "$FIXTURE_REPO/config"
printf 'GOOGLE_APPLICATION_CREDENTIALS=/var/run/secrets/google.json\nSERVICE_ACCT=checkout-runtime-secret\n' > "$FIXTURE_REPO/config/runtime.env"

pushd "$FIXTURE_REPO" >/dev/null
export AGRAPH_XTDB_PATH="$WORK_DIR/xtdb"
"$PREFIX/bin/agraph" start . \
  --project v1-smoke \
  --out project.edn \
  --map agraph.map.json \
  --report-out agraph-out \
  --query-index > "$WORK_DIR/start.json"
"$PREFIX/bin/agraph" sync inspect project.edn \
  --map agraph.map.json \
  --json > "$WORK_DIR/inspect.json"
"$PREFIX/bin/agraph" packages \
  --project v1-smoke \
  --json > "$WORK_DIR/packages.json"
popd >/dev/null

python3 - "$WORK_DIR" "$REPO_DIR" <<'PY'
import json
import pathlib
import sys

work_dir = pathlib.Path(sys.argv[1]).resolve()
agraph_repo = pathlib.Path(sys.argv[2]).resolve()
repo = (work_dir / "repo").resolve()

def load_json(name):
    return json.loads((work_dir / name).read_text())

def require(condition, message):
    if not condition:
        raise AssertionError(message)

start = load_json("start.json")
inspect = load_json("inspect.json")
packages = load_json("packages.json")
report = json.loads((repo / "agraph-out" / "report.json").read_text())

require(start.get("schema") == "agraph.start/v1", "start schema mismatch")
require(start.get("project-id") == "v1-smoke", "start project id mismatch")
require(start.get("readiness", {}).get("status") == "ready", "start readiness is not ready")
require(start.get("config") == "project.edn", "start config path should stay caller-relative")
require(start.get("map") == "agraph.map.json", "start map path should stay caller-relative")
require(start.get("report", {}).get("files", {}).get("index"), "start did not report index artifact")

require(inspect.get("schema") == "agraph.project.inspect/v1", "inspect schema mismatch")
freshness = inspect.get("freshness", {})
require(freshness.get("status") == "current", f"inspect freshness is {freshness.get('status')!r}")
require(freshness.get("mapExists") is True, "inspect did not see map overlay")
require(freshness.get("missingQueryIndex") is False, "query index is missing")
require(freshness.get("counts", {}).get("unindexed") == 0, "freshness has unindexed files")

next_kinds = {action.get("kind") for action in inspect.get("nextActions", [])}
for kind in ("dependencies", "audit-scope", "ask", "activity"):
    require(kind in next_kinds, f"inspect nextActions missing {kind}")

available = set(inspect.get("evidence", {}).get("available", []))
for family in ("source-files", "source-graph", "dependencies", "runtime-config", "auth", "system-graph"):
    require(family in available, f"inspect evidence missing {family}")
family_statuses = {row.get("family"): row.get("status")
                   for row in inspect.get("evidence", {}).get("families", [])}
require("dependencies" in family_statuses, "inspect evidence families missing dependencies")
require(family_statuses.get("auth") == "available", "auth evidence family is not available")

require(packages.get("schema") == "agraph.dependency.report/v1", "packages schema mismatch")
require(packages.get("counts", {}).get("packages", 0) > 0, "packages report has no package evidence")

for relative in (
    "project.edn",
    "agraph.map.json",
    "agraph-out/index.html",
    "agraph-out/report.json",
    "agraph-out/graph.json",
    "agraph-out/systems.json",
    "agraph-out/report-plugins.json",
):
    require((repo / relative).exists(), f"missing generated artifact {relative}")

project_text = (repo / "project.edn").read_text()
require(str(repo) in project_text, "project.edn does not point at smoke repo")
require(str(agraph_repo) not in project_text, "project.edn points at AGraph checkout")

operator = report.get("operator", {})
require(report.get("schema") == "agraph.report/v2", "report schema mismatch")
require(operator.get("schema") == "agraph.report.operator/v1", "operator report schema mismatch")
require(operator.get("freshness", {}).get("status") == "current", "operator freshness is not current")
require(operator.get("evidence-families"), "operator report missing evidence families")
require(operator.get("audit-scopes"), "operator report missing audit scopes")
require(operator.get("next-actions"), "operator report missing next actions")
require("checkout-runtime-secret" not in json.dumps(report), "operator report leaked auth value")

print(f"V1 smoke passed: {repo}")
PY
