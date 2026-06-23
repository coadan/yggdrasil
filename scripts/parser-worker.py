#!/usr/bin/env python3
"""JSONL parser worker for Yggdrasil extractor experiments.

The worker reads one JSON request per stdin line and writes one JSON response
per stdout line. It emits parser facts only; Yggdrasil owns ids, graph row shape,
relation names, storage, and semantic interpretation.
"""

from __future__ import annotations

import ast
import json
import sys
import traceback
from typing import Any, Iterable


REQUEST_SCHEMA = "ygg.parser.request/v1"
RESPONSE_SCHEMA = "ygg.parser.response/v1"


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


def node_text(content: bytes | str, node: Any) -> str:
    if isinstance(content, bytes):
        return content[node_start_byte(node) : node_end_byte(node)].decode("utf-8", errors="replace")
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
            source = java_reference_source(content, node)
            if not source or target == source:
                continue
            key = (source, target, node_line_range(node)["line"], kind)
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

    content_bytes = content.encode("utf-8")
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
    package = java_package(content_bytes, root)
    facts = {
        "definitions": java_definitions(content_bytes, root),
        "imports": java_imports(content_bytes, root),
        "references": java_references(content_bytes, root),
        "diagnostics": diagnostics,
    }
    if package:
        facts["package"] = package
    return facts


DOTNET_TYPE_NODE_KINDS = {
    "identifier",
    "qualified_name",
    "generic_name",
}

DOTNET_DEFINITION_KINDS = {
    "class_declaration": "class",
    "interface_declaration": "interface",
    "enum_declaration": "enum",
    "record_declaration": "record",
    "record_struct_declaration": "record",
    "struct_declaration": "struct",
    "delegate_declaration": "delegate",
}


def tree_sitter_parser_any(languages: Iterable[str]) -> Any | None:
    for language in languages:
        parser = tree_sitter_parser(language)
        if parser is not None:
            return parser
    return None


def strip_js_string_literal(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] in {"'", '"', "`"} and value[-1] == value[0]:
        return value[1:-1]
    return value


def js_name_node(node: Any) -> Any | None:
    by_field = node_child_by_field_name(node, "name")
    if by_field is not None:
        return by_field
    for child in node_children(node):
        if node_kind(child) in {
            "identifier",
            "property_identifier",
            "private_property_identifier",
            "type_identifier",
        }:
            return child
    return None


def js_definition_name(content: bytes, node: Any, type_stack: list[str]) -> str | None:
    name_node = js_name_node(node)
    if name_node is None:
        return None
    name = node_text(content, name_node)
    if not name:
        return None
    return ".".join([*type_stack, name]) if type_stack else name


def js_variable_function_kind(content: bytes, node: Any) -> str | None:
    value = node_child_by_field_name(node, "value")
    if value is None:
        return None
    value_kind = node_kind(value)
    if value_kind in {"arrow_function", "function", "function_expression"}:
        return "function"
    if value_kind in {"class", "class_declaration", "class_expression"}:
        return "class"
    return None


JS_DEFINITION_KINDS = {
    "function_declaration": "function",
    "generator_function_declaration": "function",
    "class_declaration": "class",
    "interface_declaration": "interface",
    "type_alias_declaration": "type",
    "enum_declaration": "enum",
}


def js_definitions(content: bytes, root: Any) -> list[dict[str, Any]]:
    definitions: list[dict[str, Any]] = []

    def visit(node: Any, type_stack: list[str]) -> None:
        kind = node_kind(node)
        next_stack = type_stack
        if kind in JS_DEFINITION_KINDS:
            name = js_definition_name(content, node, type_stack)
            if name:
                definitions.append(
                    {
                        "kind": JS_DEFINITION_KINDS[kind],
                        "name": name,
                        **node_line_range(node),
                    }
                )
                if JS_DEFINITION_KINDS[kind] == "class":
                    next_stack = [*type_stack, name.split(".")[-1]]
        elif kind == "method_definition":
            name = js_definition_name(content, node, type_stack)
            if name:
                definitions.append({"kind": "method", "name": name, **node_line_range(node)})
        elif kind == "variable_declarator":
            value_kind = js_variable_function_kind(content, node)
            name = js_definition_name(content, node, type_stack) if value_kind else None
            if name:
                definitions.append({"kind": value_kind, "name": name, **node_line_range(node)})

        for child in node_children(node):
            visit(child, next_stack)

    visit(root, [])
    return definitions


def js_string_descendant(content: bytes, node: Any) -> str | None:
    string_node = first_descendant(node, {"string", "string_fragment"})
    if string_node is None:
        return None
    return strip_js_string_literal(node_text(content, string_node))


def js_call_expression_import(content: bytes, node: Any) -> dict[str, Any] | None:
    if node_child_count(node) == 0:
        return None
    callee = node_child(node, 0)
    if node_kind(callee) not in {"identifier", "import"}:
        return None
    callee_text = node_text(content, callee)
    if callee_text not in {"require", "import"}:
        return None
    target = js_string_descendant(content, node)
    if target:
        return {"target": target, **node_line_range(node)}
    return None


def js_imports(content: bytes, root: Any) -> list[dict[str, Any]]:
    imports: list[dict[str, Any]] = []
    seen: set[tuple[str, int, str | None]] = set()
    for node in walk_nodes(root):
        kind = node_kind(node)
        row = None
        if kind in {"import_statement", "export_statement"}:
            target = js_string_descendant(content, node)
            if target:
                text = f" {node_text(content, node)} "
                row = {"target": target, **node_line_range(node)}
                if " type " in text:
                    row["importKind"] = "type"
        elif kind == "call_expression":
            row = js_call_expression_import(content, node)
        if row:
            key = (str(row["target"]), int(row["line"]), row.get("importKind"))
            if key not in seen:
                seen.add(key)
                imports.append(row)
    return imports


def parse_javascript_like(kind: str, path: str, content: str) -> dict[str, Any]:
    if kind == "typescript":
        languages = ["tsx", "typescript"] if path.endswith(".tsx") else ["typescript"]
    else:
        languages = ["javascript"]
    parser = tree_sitter_parser_any(languages)
    if parser is None:
        return {
            "definitions": [],
            "imports": [],
            "references": [],
            "diagnostics": [
                {
                    "stage": "parser-worker",
                    "line": None,
                    "message": f"{kind} parser unavailable: install tree-sitter-language-pack",
                }
            ],
        }

    content_bytes = content.encode("utf-8")
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
    return {
        "definitions": js_definitions(content_bytes, root),
        "imports": js_imports(content_bytes, root),
        "references": [],
        "diagnostics": diagnostics,
    }


def dotnet_name_node(node: Any) -> Any | None:
    by_field = node_child_by_field_name(node, "name")
    if by_field is not None:
        return by_field
    for child in node_children(node):
        if node_kind(child) == "identifier":
            return child
    return None


def dotnet_definition_name(content: str, node: Any, type_stack: list[str]) -> str | None:
    name_node = dotnet_name_node(node)
    if name_node is None:
        return None
    name = node_text(content, name_node)
    return ".".join([*type_stack, name]) if type_stack else name


def dotnet_namespace(content: str, root: Any) -> str | None:
    for node in walk_nodes(root):
        if node_kind(node) in {"namespace_declaration", "file_scoped_namespace_declaration"}:
            target_node = first_descendant(node, {"qualified_name", "identifier"})
            if target_node is not None:
                return node_text(content, target_node)
    return None


def dotnet_usings(content: str, root: Any) -> list[dict[str, Any]]:
    imports: list[dict[str, Any]] = []
    for node in walk_nodes(root):
        if node_kind(node) == "using_directive":
            target_node = first_descendant(node, {"qualified_name", "identifier"})
            if target_node is not None:
                imports.append({"target": node_text(content, target_node), **node_line_range(node)})
    return imports


def dotnet_definitions(content: str, root: Any) -> list[dict[str, Any]]:
    definitions: list[dict[str, Any]] = []

    def visit(node: Any, type_stack: list[str]) -> None:
        kind = node_kind(node)
        next_stack = type_stack
        if kind in DOTNET_DEFINITION_KINDS:
            name = dotnet_definition_name(content, node, type_stack)
            if name:
                definitions.append(
                    {
                        "kind": DOTNET_DEFINITION_KINDS[kind],
                        "name": name,
                        **node_line_range(node),
                    }
                )
                next_stack = [*type_stack, name.split(".")[-1]]
        elif kind in {"method_declaration", "constructor_declaration", "property_declaration"}:
            name = dotnet_definition_name(content, node, type_stack)
            if name:
                definitions.append(
                    {
                        "kind": {
                            "constructor_declaration": "constructor",
                            "property_declaration": "property",
                        }.get(kind, "method"),
                        "name": name,
                        **node_line_range(node),
                    }
                )

        for child in node_children(node):
            visit(child, next_stack)

    visit(root, [])
    return definitions


def dotnet_reference_source(content: str, node: Any) -> str | None:
    current = getattr(node, "parent", None)
    current = current() if callable(current) else current
    while current is not None:
        kind = node_kind(current)
        if kind in DOTNET_DEFINITION_KINDS or kind in {
            "method_declaration",
            "constructor_declaration",
            "property_declaration",
        }:
            name = dotnet_name_node(current)
            return node_text(content, name) if name is not None else None
        parent = getattr(current, "parent", None)
        current = parent() if callable(parent) else parent
    return None


def dotnet_references(content: str, root: Any) -> list[dict[str, Any]]:
    references: list[dict[str, Any]] = []
    seen: set[tuple[str | None, str, int, str]] = set()
    for node in walk_nodes(root):
        kind = node_kind(node)
        if kind in DOTNET_TYPE_NODE_KINDS:
            target = node_text(content, node)
            if not target or target[0].islower():
                continue
            source = dotnet_reference_source(content, node)
            if not source or target == source:
                continue
            key = (source, target, node_line_range(node)["line"], kind)
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


def parse_dotnet(path: str, content: str) -> dict[str, Any]:
    parser = tree_sitter_parser_any(["c_sharp", "c-sharp", "csharp"])
    if parser is None:
        return {
            "definitions": [],
            "imports": [],
            "references": [],
            "diagnostics": [
                {
                    "stage": "parser-worker",
                    "line": None,
                    "message": "dotnet parser unavailable: install tree-sitter-language-pack",
                }
            ],
        }

    content_bytes = content.encode("utf-8")
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
    namespace = dotnet_namespace(content_bytes, root)
    facts = {
        "definitions": dotnet_definitions(content_bytes, root),
        "imports": dotnet_usings(content_bytes, root),
        "references": dotnet_references(content_bytes, root),
        "diagnostics": diagnostics,
    }
    if namespace:
        facts["namespace"] = namespace
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
    elif kind in {"javascript", "typescript"}:
        facts = parse_javascript_like(kind, path, content)
    elif kind == "dotnet":
        facts = parse_dotnet(path, content)
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
