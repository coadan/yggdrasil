#!/usr/bin/env python3
"""JSONL parser worker for AGraph extractor experiments.

The worker reads one JSON request per stdin line and writes one JSON response
per stdout line. It emits parser facts only; AGraph owns ids, graph row shape,
relation names, storage, and semantic interpretation.
"""

from __future__ import annotations

import ast
import json
import sys
import traceback
from typing import Any, Iterable


REQUEST_SCHEMA = "agraph.parser.request/v1"
RESPONSE_SCHEMA = "agraph.parser.response/v1"


def emit(value: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(value, sort_keys=True, separators=(",", ":")))
    sys.stdout.write("\n")
    sys.stdout.flush()


def read_content(request: dict[str, Any]) -> str:
    if "content" in request:
        return str(request.get("content") or "")
    path = request.get("path")
    if not path:
        return ""
    with open(str(path), "r", encoding="utf-8", errors="replace") as file:
        return file.read()


def line_range(node: ast.AST) -> dict[str, int]:
    row: dict[str, int] = {"line": int(getattr(node, "lineno", 1) or 1)}
    end_line = getattr(node, "end_lineno", None)
    if end_line:
        row["endLine"] = int(end_line)
    return row


def python_import_targets(node: ast.AST) -> Iterable[dict[str, Any]]:
    if isinstance(node, ast.Import):
        for alias in node.names:
            yield {"target": alias.name, **line_range(node)}
    elif isinstance(node, ast.ImportFrom):
        prefix = "." * int(node.level or 0)
        if node.module:
            yield {"target": prefix + node.module, **line_range(node)}
        else:
            for alias in node.names:
                yield {"target": prefix + alias.name, **line_range(node)}


def python_definitions(tree: ast.Module) -> list[dict[str, Any]]:
    definitions: list[dict[str, Any]] = []
    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            definitions.append({"kind": "class", "name": node.name, **line_range(node)})
            for child in node.body:
                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    kind = "async-method" if isinstance(child, ast.AsyncFunctionDef) else "method"
                    definitions.append(
                        {
                            "kind": kind,
                            "name": f"{node.name}.{child.name}",
                            **line_range(child),
                        }
                    )
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            kind = "async-function" if isinstance(node, ast.AsyncFunctionDef) else "function"
            definitions.append({"kind": kind, "name": node.name, **line_range(node)})
    return definitions


def parse_python(path: str, content: str) -> dict[str, Any]:
    try:
        tree = ast.parse(content, filename=path)
        imports = [target for node in tree.body for target in python_import_targets(node)]
        return {
            "definitions": python_definitions(tree),
            "imports": imports,
            "references": [],
            "diagnostics": [],
        }
    except SyntaxError as exc:
        return {
            "definitions": [],
            "imports": [],
            "references": [],
            "diagnostics": [
                {
                    "stage": "parse",
                    "line": exc.lineno,
                    "message": exc.msg,
                }
            ],
        }


def node_kind(node: Any) -> str:
    kind = getattr(node, "kind", None)
    return kind() if callable(kind) else str(kind)


def node_child_count(node: Any) -> int:
    child_count = getattr(node, "child_count", None)
    return int(child_count() if callable(child_count) else child_count or 0)


def node_child(node: Any, idx: int) -> Any:
    child = getattr(node, "child", None)
    return child(idx) if callable(child) else node.children[idx]


def node_children(node: Any) -> list[Any]:
    return [node_child(node, idx) for idx in range(node_child_count(node))]


def node_start_byte(node: Any) -> int:
    value = getattr(node, "start_byte", None)
    return int(value() if callable(value) else value or 0)


def node_end_byte(node: Any) -> int:
    value = getattr(node, "end_byte", None)
    return int(value() if callable(value) else value or 0)


def node_text(content: str, node: Any) -> str:
    return content[node_start_byte(node) : node_end_byte(node)]


def node_point(node: Any, attr: str) -> Any:
    value = getattr(node, attr)
    return value() if callable(value) else value


def node_line_range(node: Any) -> dict[str, int]:
    start = node_point(node, "start_position")
    end = node_point(node, "end_position")
    row = {"line": int(start.row) + 1}
    if int(end.row) + 1 != row["line"]:
        row["endLine"] = int(end.row) + 1
    return row


def node_has_error(node: Any) -> bool:
    has_error = getattr(node, "has_error", None)
    return bool(has_error() if callable(has_error) else has_error)


def node_child_by_field_name(node: Any, field: str) -> Any | None:
    child_by_field_name = getattr(node, "child_by_field_name", None)
    if callable(child_by_field_name):
        return child_by_field_name(field)
    return None


def walk_nodes(node: Any) -> Iterable[Any]:
    yield node
    for child in node_children(node):
        yield from walk_nodes(child)


def first_descendant(node: Any, kinds: set[str]) -> Any | None:
    for child in walk_nodes(node):
        if child is not node and node_kind(child) in kinds:
            return child
    return None


def descendants(node: Any, kinds: set[str]) -> Iterable[Any]:
    for child in walk_nodes(node):
        if child is not node and node_kind(child) in kinds:
            yield child


JAVA_TYPE_NODE_KINDS = {
    "type_identifier",
}

JAVA_DEFINITION_KINDS = {
    "class_declaration": "class",
    "interface_declaration": "interface",
    "enum_declaration": "enum",
    "record_declaration": "record",
    "annotation_type_declaration": "annotation",
}


def tree_sitter_parser(language: str) -> Any | None:
    try:
        import tree_sitter_language_pack as language_pack  # type: ignore

        return language_pack.get_parser(language)
    except Exception:
        return None


def java_name_node(node: Any) -> Any | None:
    by_field = node_child_by_field_name(node, "name")
    if by_field is not None:
        return by_field
    for child in node_children(node):
        if node_kind(child) == "identifier":
            return child
    return None


def java_definition_name(content: str, node: Any, type_stack: list[str]) -> str | None:
    name_node = java_name_node(node)
    if name_node is None:
        return None
    name = node_text(content, name_node)
    return ".".join([*type_stack, name]) if type_stack else name


def java_imports(content: str, root: Any) -> list[dict[str, Any]]:
    imports: list[dict[str, Any]] = []
    for node in walk_nodes(root):
        if node_kind(node) == "import_declaration":
            target_node = first_descendant(node, {"scoped_identifier", "identifier"})
            if target_node is not None:
                row = {"target": node_text(content, target_node), **node_line_range(node)}
                if " static " in f" {node_text(content, node)} ":
                    row["static"] = True
                imports.append(row)
    return imports


def java_package(content: str, root: Any) -> str | None:
    for node in node_children(root):
        if node_kind(node) == "package_declaration":
            target_node = first_descendant(node, {"scoped_identifier", "identifier"})
            if target_node is not None:
                return node_text(content, target_node)
    return None


def java_definitions(content: str, root: Any) -> list[dict[str, Any]]:
    definitions: list[dict[str, Any]] = []

    def visit(node: Any, type_stack: list[str]) -> None:
        kind = node_kind(node)
        next_stack = type_stack
        if kind in JAVA_DEFINITION_KINDS:
            name = java_definition_name(content, node, type_stack)
            if name:
                definitions.append(
                    {
                        "kind": JAVA_DEFINITION_KINDS[kind],
                        "name": name,
                        **node_line_range(node),
                    }
                )
                next_stack = [*type_stack, name.split(".")[-1]]
        elif kind in {"method_declaration", "constructor_declaration"}:
            name = java_definition_name(content, node, type_stack)
            if name:
                definitions.append(
                    {
                        "kind": "constructor" if kind == "constructor_declaration" else "method",
                        "name": name,
                        **node_line_range(node),
                    }
                )

        for child in node_children(node):
            visit(child, next_stack)

    visit(root, [])
    return definitions


def java_reference_source(content: str, node: Any) -> str | None:
    current = getattr(node, "parent", None)
    current = current() if callable(current) else current
    while current is not None:
        kind = node_kind(current)
        if kind in JAVA_DEFINITION_KINDS or kind in {"method_declaration", "constructor_declaration"}:
            name = java_name_node(current)
            return node_text(content, name) if name is not None else None
        parent = getattr(current, "parent", None)
        current = parent() if callable(parent) else parent
    return None


def java_references(content: str, root: Any) -> list[dict[str, Any]]:
    references: list[dict[str, Any]] = []
    seen: set[tuple[str | None, str, int, str]] = set()
    for node in walk_nodes(root):
        kind = node_kind(node)
        if kind in JAVA_TYPE_NODE_KINDS:
            target = node_text(content, node)
            key = (java_reference_source(content, node), target, node_line_range(node)["line"], kind)
            if key not in seen:
                seen.add(key)
                references.append(
                    {
                        "source": key[0],
                        "target": target,
                        "kind": "type",
                        **node_line_range(node),
                    }
                )
    return references


def parse_java(path: str, content: str) -> dict[str, Any]:
    parser = tree_sitter_parser("java")
    if parser is None:
        return {
            "definitions": [],
            "imports": [],
            "references": [],
            "diagnostics": [
                {
                    "stage": "parser-worker",
                    "line": None,
                    "message": "java parser unavailable: install tree-sitter-language-pack",
                }
            ],
        }

    tree = parser.parse(content)
    root = tree.root_node()
    diagnostics = []
    if node_has_error(root):
        diagnostics.append(
            {
                "stage": "parse",
                "line": 1,
                "message": "tree-sitter reported parse errors",
            }
        )
    package = java_package(content, root)
    facts = {
        "definitions": java_definitions(content, root),
        "imports": java_imports(content, root),
        "references": java_references(content, root),
        "diagnostics": diagnostics,
    }
    if package:
        facts["package"] = package
    return facts


def parse_unsupported(kind: str) -> dict[str, Any]:
    return {
        "definitions": [],
        "imports": [],
        "references": [],
        "diagnostics": [
            {
                "stage": "parser-worker",
                "line": None,
                "message": f"unsupported parser kind: {kind}",
            }
        ],
    }


def parse_request(request: dict[str, Any]) -> dict[str, Any]:
    kind = str(request.get("kind") or "")
    path = str(request.get("path") or "")
    content = read_content(request)
    if kind == "python":
        facts = parse_python(path, content)
    elif kind == "java":
        facts = parse_java(path, content)
    else:
        facts = parse_unsupported(kind)
    return {
        "schema": RESPONSE_SCHEMA,
        "id": request.get("id"),
        "kind": kind,
        "path": path,
        "facts": facts,
    }


def error_response(request_id: Any, message: str) -> dict[str, Any]:
    return {
        "schema": RESPONSE_SCHEMA,
        "id": request_id,
        "kind": None,
        "path": None,
        "facts": {
            "definitions": [],
            "imports": [],
            "references": [],
            "diagnostics": [
                {
                    "stage": "parser-worker",
                    "line": None,
                    "message": message,
                }
            ],
        },
    }


def main() -> int:
    for line in sys.stdin:
        if not line.strip():
            continue
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            if request.get("schema") not in (None, REQUEST_SCHEMA):
                emit(error_response(request_id, "unsupported request schema"))
            else:
                emit(parse_request(request))
        except Exception as exc:  # pragma: no cover - defensive worker boundary.
            message = f"{exc.__class__.__name__}: {exc}"
            if "--debug" in sys.argv:
                message = message + "\n" + traceback.format_exc()
            emit(error_response(request_id, message))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
