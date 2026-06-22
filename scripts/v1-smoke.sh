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

WORK_DIR="${YGG_V1_SMOKE_DIR:-$(mktemp -d)}"
PREFIX="$WORK_DIR/prefix"
FIXTURE_REPO="$WORK_DIR/repo"

cleanup() {
  if [ "$KEEP" -eq 0 ] && [ -z "${YGG_V1_SMOKE_DIR:-}" ]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

install_wrappers() {
  if [ "$(uname -s)" = "Darwin" ]; then
    "$REPO_DIR/scripts/install-macos.sh" --prefix "$PREFIX" > "$WORK_DIR/install.log"
  else
    mkdir -p "$PREFIX/bin"
    ln -sfn "$REPO_DIR/bin/ygg" "$PREFIX/bin/ygg"
    ln -sfn "$REPO_DIR/bin/ygg-mcp" "$PREFIX/bin/ygg-mcp"
    printf 'Installed symlink fallback for non-macOS smoke.\n' > "$WORK_DIR/install.log"
  fi
}

install_wrappers
"$PREFIX/bin/ygg" help > "$WORK_DIR/help.txt"
test -x "$PREFIX/bin/ygg"
test -x "$PREFIX/bin/ygg-mcp"

cp -R "$REPO_DIR/test/fixtures/project-repo" "$FIXTURE_REPO"
printf '\n[dependencies]\nserde = "1"\n' >> "$FIXTURE_REPO/services/api/Cargo.toml"
mkdir -p "$FIXTURE_REPO/config"
printf 'GOOGLE_APPLICATION_CREDENTIALS=/var/run/secrets/google.json\nSERVICE_ACCT=checkout-runtime-secret\n' > "$FIXTURE_REPO/config/runtime.env"
cat > "$FIXTURE_REPO/pyproject.toml" <<'EOF'
[project]
name = "v1-smoke"
dependencies = ["google-cloud-storage>=2"]
EOF
mkdir -p "$FIXTURE_REPO/services/api/src"
cat > "$FIXTURE_REPO/services/api/src/dependency_probe.py" <<'EOF'
from google.cloud import storage


def bucket_client():
    return storage.Client()
EOF

pushd "$FIXTURE_REPO" >/dev/null
export YGG_XTDB_PATH="$WORK_DIR/xtdb"
"$PREFIX/bin/ygg" start . \
  --project v1-smoke \
  --out project.edn \
  --map ygg.map.json \
  --report-out ygg-out \
  --query-index > "$WORK_DIR/start.json"
"$PREFIX/bin/ygg" sync inspect project.edn \
  --map ygg.map.json \
  --json > "$WORK_DIR/inspect.json"
"$PREFIX/bin/ygg" packages \
  --project v1-smoke \
  --json > "$WORK_DIR/packages.json"
"$PREFIX/bin/ygg" sync project.edn \
  --check \
  --map ygg.map.json \
  --enqueue \
  --json > "$WORK_DIR/sync-check.json"
"$PREFIX/bin/ygg" sync work pull \
  --project v1-smoke \
  --kind dependency-review \
  --agent v1-smoke > "$WORK_DIR/work-pull.json"
WORK_ID="$(
  python3 - "$WORK_DIR" <<'PY'
import json
import pathlib
import sys

work_dir = pathlib.Path(sys.argv[1])
pull = json.loads((work_dir / "work-pull.json").read_text())
if pull.get("status") == "empty":
    raise AssertionError("dependency-review work queue was empty")
item = pull["item"]
packet = item["payload"]
unresolved = packet["facts"]["unresolvedImport"]
packages = packet["facts"]["packages"]
evidence = packet["facts"]["evidence"]
package = next((row for row in packages
                if row.get("ecosystem") == "pypi"
                and row.get("package-name") == "google-cloud-storage"), None)
if package is None:
    raise AssertionError("dependency-review packet missing google-cloud-storage package")
if unresolved.get("import") != "google.cloud":
    raise AssertionError(f"unexpected unresolved import {unresolved.get('import')!r}")
if not evidence:
    raise AssertionError("dependency-review packet missing evidence")
result = {
    "schema": "ygg.dependency.review-result/v1",
    "reviewId": packet["reviewId"],
    "recommendation": "add-package-import",
    "confidence": 0.95,
    "reason": "The import prefix is provided by the declared PyPI package.",
    "mapPatch": [{
        "op": "add-package-import",
        "import": "google.cloud",
        "ecosystem": "pypi",
        "package": "google-cloud-storage",
        "evidence": [evidence[0]["id"]],
        "reason": "The package exposes the reviewed google.cloud import prefix."
    }]
}
(work_dir / "work-result.json").write_text(json.dumps(result, indent=2) + "\n")
print(item["id"])
PY
)"
"$PREFIX/bin/ygg" sync work complete "$WORK_ID" \
  --result "$WORK_DIR/work-result.json" > "$WORK_DIR/work-complete.json"
"$PREFIX/bin/ygg" sync work validate "$WORK_ID" > "$WORK_DIR/work-validate.json"
"$PREFIX/bin/ygg" sync work apply "$WORK_ID" \
  --map ygg.map.json > "$WORK_DIR/work-apply.json"
"$PREFIX/bin/ygg" packages \
  --project v1-smoke \
  --json > "$WORK_DIR/packages-after-apply.json"
"$PREFIX/bin/ygg" sync activity project.edn \
  --json > "$WORK_DIR/activity.json"
popd >/dev/null

python3 - "$WORK_DIR" "$REPO_DIR" <<'PY'
import json
import pathlib
import sys

work_dir = pathlib.Path(sys.argv[1]).resolve()
ygg_repo = pathlib.Path(sys.argv[2]).resolve()
repo = (work_dir / "repo").resolve()

def load_json(name):
    return json.loads((work_dir / name).read_text())

def require(condition, message):
    if not condition:
        raise AssertionError(message)

start = load_json("start.json")
inspect = load_json("inspect.json")
packages = load_json("packages.json")
sync_check = load_json("sync-check.json")
work_pull = load_json("work-pull.json")
work_complete = load_json("work-complete.json")
work_validate = load_json("work-validate.json")
work_apply = load_json("work-apply.json")
packages_after_apply = load_json("packages-after-apply.json")
activity = load_json("activity.json")
report = json.loads((repo / "ygg-out" / "report.json").read_text())

require(start.get("schema") == "ygg.start/v1", "start schema mismatch")
require(start.get("project-id") == "v1-smoke", "start project id mismatch")
require(start.get("readiness", {}).get("status") == "ready", "start readiness is not ready")
require(start.get("config") == "project.edn", "start config path should stay caller-relative")
require(start.get("map") == "ygg.map.json", "start map path should stay caller-relative")
require(start.get("report", {}).get("files", {}).get("index"), "start did not report index artifact")
start_actions = {action.get("kind"): action.get("command")
                 for action in start.get("nextActions", [])}
require(start_actions.get("inspect") == "ygg sync inspect project.edn --map ygg.map.json --json",
        "start nextActions did not make sync inspect canonical")

require(inspect.get("schema") == "ygg.project.inspect/v1", "inspect schema mismatch")
freshness = inspect.get("freshness", {})
require(freshness.get("status") == "current", f"inspect freshness is {freshness.get('status')!r}")
require(freshness.get("mapExists") is True, "inspect did not see map overlay")
require(freshness.get("missingQueryIndex") is False, "query index is missing")
require(freshness.get("counts", {}).get("unindexed") == 0, "freshness has unindexed files")

next_kinds = {action.get("kind") for action in inspect.get("nextActions", [])}
for kind in ("dependencies", "audit-scope", "ask", "activity", "validation-history"):
    require(kind in next_kinds, f"inspect nextActions missing {kind}")

available = set(inspect.get("evidence", {}).get("available", []))
for family in ("source-files", "source-graph", "dependencies", "runtime-config", "auth", "system-graph"):
    require(family in available, f"inspect evidence missing {family}")
family_statuses = {row.get("family"): row.get("status")
                   for row in inspect.get("evidence", {}).get("families", [])}
require("dependencies" in family_statuses, "inspect evidence families missing dependencies")
require(family_statuses.get("auth") == "available", "auth evidence family is not available")

require(packages.get("schema") == "ygg.dependency.report/v1", "packages schema mismatch")
require(packages.get("counts", {}).get("packages", 0) > 0, "packages report has no package evidence")
require(packages.get("counts", {}).get("unresolved-imports", 0) > 0,
        "packages report has no unresolved import for dependency review")

require(sync_check.get("schema") == "ygg.sync/v1", "sync check schema mismatch")
enqueued = sync_check.get("enqueued", [])
require(any(item.get("kind") == "dependency-review" for item in enqueued),
        "sync check did not enqueue dependency-review work")
require(work_pull.get("schema") == "ygg.queue.summary/v1", "work pull schema mismatch")
require(work_pull.get("kind") == "dependency-review", "pulled work is not dependency-review")
require(work_pull.get("status") == "claimed", "pulled work was not claimed")
require(work_pull.get("expected-result-schema") == "ygg.dependency.review-result/v1",
        "dependency-review work did not advertise expected result schema")
require(work_complete.get("status") == "done", "work complete did not mark item done")
require(work_validate.get("schema") == "ygg.sync.work.validation/v1",
        "work validate schema mismatch")
require(work_validate.get("status") == "valid", "work result did not validate")
require(work_apply.get("schema") == "ygg.sync.work.apply/v1", "work apply schema mismatch")
require(work_apply.get("status") == "applied", "work apply did not apply")
require(work_apply.get("patchesApplied") == 1, "work apply did not apply exactly one patch")
require(work_apply.get("validation", {}).get("status") == "valid",
        "work apply did not revalidate before applying")

map_data = json.loads((repo / "ygg.map.json").read_text())
package_imports = map_data.get("packageImports", [])
require(any(row.get("import") == "google.cloud"
            and row.get("ecosystem") == "pypi"
            and row.get("package") == "google-cloud-storage"
            and row.get("status") == "accepted"
            for row in package_imports),
        "applied work did not persist accepted package import correction")
package_by_name = {row.get("package-name"): row
                   for row in packages_after_apply.get("packages", [])}
storage_package = package_by_name.get("google-cloud-storage")
require(storage_package, "packages after apply missing google-cloud-storage")
require(storage_package.get("imported-by"),
        "packages after apply did not use the accepted package import correction")
require(activity.get("schema") == "ygg.activity.sync/v1", "activity sync schema mismatch")
require(activity.get("counts", {}).get("items", 0) > 0, "activity sync did not import queue items")

for relative in (
    "project.edn",
    "ygg.map.json",
    "ygg-out/index.html",
    "ygg-out/report.json",
    "ygg-out/graph.json",
    "ygg-out/systems.json",
    "ygg-out/report-plugins.json",
):
    require((repo / relative).exists(), f"missing generated artifact {relative}")

project_text = (repo / "project.edn").read_text()
require(str(repo) in project_text, "project.edn does not point at smoke repo")
require(str(ygg_repo) not in project_text, "project.edn points at Yggdrasil checkout")

operator = report.get("operator", {})
require(report.get("schema") == "ygg.report/v2", "report schema mismatch")
require(operator.get("schema") == "ygg.report.operator/v1", "operator report schema mismatch")
require(operator.get("freshness", {}).get("status") == "current", "operator freshness is not current")
require(operator.get("evidence-families"), "operator report missing evidence families")
require(operator.get("audit-scopes"), "operator report missing audit scopes")
require(operator.get("next-actions"), "operator report missing next actions")
require("checkout-runtime-secret" not in json.dumps(report), "operator report leaked auth value")

print(f"V1 smoke passed: {repo}")
PY
