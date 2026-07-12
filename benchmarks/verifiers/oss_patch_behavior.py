#!/usr/bin/env python3
"""Hidden behavioral contracts for the real OSS patch replay suite."""

from __future__ import annotations

import argparse
import ast
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import xml.etree.ElementTree as ET


ROOT = Path(os.environ["YGG_BENCH_WORKTREE"]).resolve()
SUITE_DIR = Path(os.environ["YGG_BENCH_SUITE_DIR"]).resolve()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def run(
    command: list[str], *, cwd: Path = ROOT, timeout: int = 600, env: dict[str, str] | None = None
) -> None:
    result = subprocess.run(
        command, cwd=cwd, text=True, timeout=timeout, env={**os.environ, **(env or {})}
    )
    require(result.returncode == 0, f"command failed ({result.returncode}): {' '.join(command)}")


def python_function(path: Path, class_name: str, function_name: str):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    class_node = next(
        node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name
    )
    function = next(
        node
        for node in class_node.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == function_name
    )
    function.decorator_list = []
    module = ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[]))
    namespace: dict[str, object] = {}
    exec(compile(module, str(path), "exec"), namespace)
    return namespace[function_name]


def verify_flask_autoescape() -> None:
    select = python_function(ROOT / "src/flask/sansio/app.py", "App", "select_jinja_autoescape")
    for filename in ("INDEX.HTML", "icon.SvG", "page.XhTmL", "mixed.HtM"):
        require(select(None, filename) is True, f"autoescape disabled for {filename}")
    require(select(None, "notes.txt") is False, "autoescape enabled for a non-template file")
    require(select(None, None) is True, "unnamed templates must remain autoescaped")


def assignment_literal(path: Path, name: str):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    assignment = next(
        node
        for node in tree.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)
    )
    return ast.literal_eval(assignment.value)


def verify_graphify_read_hook() -> None:
    hook = assignment_literal(ROOT / "graphify/__main__.py", "_READ_SETTINGS_HOOK")
    require(hook["matcher"] == "Read|Glob", "hook matcher changed")
    command = hook["hooks"][0]["command"]
    with tempfile.TemporaryDirectory(prefix="ygg-graphify-hook-") as tmp:
        cwd = Path(tmp)
        graph_dir = cwd / "graphify-out"
        graph_dir.mkdir()
        (graph_dir / "graph.json").write_text("{}", encoding="utf-8")

        def output(tool_input: dict[str, str]) -> str:
            result = subprocess.run(
                ["sh", "-c", command],
                cwd=cwd,
                input=json.dumps({"tool_input": tool_input}),
                capture_output=True,
                text=True,
                timeout=10,
            )
            require(result.returncode == 0, "read hook blocked the tool call")
            return result.stdout.strip()

        for path in ("src/Hero.astro", "src/App.vue", "src/Card.svelte", "src/a.test.tsx"):
            require("graphify query" in output({"file_path": path}), f"hook missed {path}")
        for path in ("package.json", "data.geojson", "my.ts/file"):
            require(output({"file_path": path}) == "", f"hook false-positive for {path}")


def verify_dapper_enum_handler() -> None:
    project = (ROOT / "Dapper/Dapper.csproj").resolve()
    with tempfile.TemporaryDirectory(prefix="ygg-dapper-enum-") as tmp:
        temp = Path(tmp)
        (temp / "Verifier.csproj").write_text(
            textwrap.dedent(
                f"""\
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework></PropertyGroup>
                  <ItemGroup><ProjectReference Include="{project}" /></ItemGroup>
                </Project>
                """
            ),
            encoding="utf-8",
        )
        (temp / "Program.cs").write_text(
            textwrap.dedent(
                """\
                using System;
                using System.Data;
                using Dapper;

                enum Status { Ready }
                sealed class Handler : SqlMapper.TypeHandler<Status> {
                    public override Status Parse(object value) => Status.Ready;
                    public override void SetValue(IDbDataParameter parameter, Status value) => parameter.Value = "ready";
                }

                static class Program {
                    static int Main() {
                        SqlMapper.Settings.SetDefaults();
                        SqlMapper.AddTypeHandler(new Handler());
                        SqlMapper.Settings.PreferTypeHandlersForEnums = true;
                        var dbType = SqlMapper.LookupDbType(typeof(Status), "status", true, out var handler);
                        if (dbType != DbType.Object || handler is not Handler) return 1;
                        SqlMapper.Settings.SetDefaults();
                        return SqlMapper.Settings.PreferTypeHandlersForEnums ? 2 : 0;
                    }
                }
                """
            ),
            encoding="utf-8",
        )
        run(
            [
                "dotnet",
                "run",
                "--project",
                "Verifier.csproj",
                "--configuration",
                "Release",
                "-p:NBGV_GitEngine=Disabled",
            ],
            cwd=temp,
        )


def package_references(path: Path) -> dict[str, ET.Element]:
    root = ET.parse(path).getroot()
    return {
        element.attrib["Include"]: element
        for element in root.iter()
        if element.tag.endswith("PackageReference") or element.tag.endswith("GlobalPackageReference")
    }


def verify_dapper_reference_trimmer() -> None:
    central = ET.parse(ROOT / "Directory.Packages.props").getroot()
    globals_by_name = {
        element.attrib["Include"]: element
        for element in central.iter()
        if element.tag.endswith("GlobalPackageReference")
    }
    require("ReferenceTrimmer" in globals_by_name, "ReferenceTrimmer is not globally enabled")
    require(globals_by_name["ReferenceTrimmer"].attrib.get("Version"), "ReferenceTrimmer has no version")

    sql_builder = package_references(ROOT / "Dapper.SqlBuilder/Dapper.SqlBuilder.csproj")
    require(
        "RT0003" in sql_builder["Microsoft.CSharp"].attrib.get("NoWarn", "").split(";"),
        "required dynamic runtime dependency is not exempted",
    )

    performance = package_references(
        ROOT / "benchmarks/Dapper.Tests.Performance/Dapper.Tests.Performance.csproj"
    )
    require("FirebirdSql.Data.FirebirdClient" not in performance, "unused Firebird dependency remains")
    require("SubSonic" not in performance, "unused SubSonic dependency remains")
    for name in ("System.Text.RegularExpressions", "System.Private.Uri", "System.Net.Http"):
        require("RT0003" in performance[name].attrib.get("NoWarn", "").split(";"), f"{name} exemption missing")


def verify_junit_repeatable_extensions() -> None:
    fixture = SUITE_DIR / "verifiers/YggHiddenRepeatableExtendWithTests.java"
    target = (
        ROOT
        / "jupiter-tests/src/test/java/org/junit/jupiter/engine/descriptor/YggHiddenRepeatableExtendWithTests.java"
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    require(not target.exists(), f"hidden fixture target already exists: {target}")
    shutil.copyfile(fixture, target)
    try:
        java_home_command = Path("/usr/libexec/java_home")
        if java_home_command.is_file():
            java_home = subprocess.check_output([str(java_home_command)], text=True).strip()
        else:
            java = Path(shutil.which("java") or "").resolve()
            java_home = str(java.parent.parent)
        run(
            [
                "./gradlew",
                ":jupiter-tests:test",
                "--tests",
                "org.junit.jupiter.engine.descriptor.YggHiddenRepeatableExtendWithTests",
                "--no-daemon",
            ],
            timeout=900,
            env={"JAVA_HOME": java_home},
        )
    finally:
        target.unlink(missing_ok=True)


def verify_terraform_dns_record_ip_type() -> None:
    import hcl2

    def key(mapping: dict, name: str):
        return next(value for candidate, value in mapping.items() if candidate.strip('"') == name)

    with (ROOT / "modules/vpc-endpoints/main.tf").open(encoding="utf-8") as stream:
        document = hcl2.load(stream)
    resource = next(
        key(key(item, "aws_vpc_endpoint"), "this")
        for item in document["resource"]
        if any(candidate.strip('"') == "aws_vpc_endpoint" for candidate in item)
    )
    dns_options = next(
        key(item, "dns_options")
        for item in resource["dynamic"]
        if any(candidate.strip('"') == "dns_options" for candidate in item)
    )
    expression = dns_options["content"][0]["dns_record_ip_type"]
    require(
        expression == "${try(dns_options.value.dns_record_ip_type, null)}",
        "dns_record_ip_type does not read the current dns_options element",
    )


def verify_supabase_event_trigger_query() -> None:
    from sqlglot import exp, parse_one

    query_path = ROOT / "nix/tests/sql/evtrigs.sql"
    query = parse_one(query_path.read_text(encoding="utf-8"), dialect="postgres")
    aliases = {projection.alias_or_name for projection in query.expressions}
    require("evtfunction_schema" in aliases, "event trigger function schema is not projected")
    joins = list(query.find_all(exp.Join))
    require(any(join.this.alias_or_name == "n_func" for join in joins), "pg_namespace is not joined")
    join_sql = " ".join(join.sql(dialect="postgres") for join in joins)
    require(
        "p.pronamespace = n_func.oid" in join_sql or "n_func.oid = p.pronamespace" in join_sql,
        "function namespace join does not follow pg_proc.pronamespace",
    )

    expected = (ROOT / "nix/tests/expected/evtrigs.out").read_text(encoding="utf-8")
    header = next(line for line in expected.splitlines() if "evtname" in line and "|" in line)
    columns = [column.strip() for column in header.split("|")]
    require("evtfunction_schema" in columns, "expected result omits function schema")
    schema_index = columns.index("evtfunction_schema")
    rows = [
        [column.strip() for column in line.split("|")]
        for line in expected.splitlines()
        if "|" in line and "evtname" not in line
    ]
    rows = [row for row in rows if len(row) == len(columns) and row[0] and set(row[0]) != {"-"}]
    require(len(rows) == 12, f"expected 12 event trigger rows, found {len(rows)}")
    require(all(row[schema_index] for row in rows), "an event trigger row has no function schema")


VERIFIERS = {
    "oss-patch-dapper-prefer-enum-type-handlers": verify_dapper_enum_handler,
    "oss-patch-junit-repeatable-field-meta-annotations": verify_junit_repeatable_extensions,
    "oss-patch-axios-unlimited-follow-redirect-body": lambda: run(
        ["node", str(SUITE_DIR / "verifiers/axios_unlimited_body.js")], timeout=600
    ),
    "oss-patch-dapper-reference-trimmer-dependencies": verify_dapper_reference_trimmer,
    "oss-patch-flask-autoescape-case-insensitive": verify_flask_autoescape,
    "oss-patch-graphify-read-glob-hook-extension-boundary": verify_graphify_read_hook,
    "oss-patch-terraform-vpc-endpoint-dns-record-ip-type": verify_terraform_dns_record_ip_type,
    "oss-patch-supabase-event-trigger-schema-regression": verify_supabase_event_trigger_query,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True, choices=sorted(VERIFIERS))
    args = parser.parse_args()
    VERIFIERS[args.case]()
    print(f"behavior verified: {args.case}")


if __name__ == "__main__":
    main()
