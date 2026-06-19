#!/usr/bin/env python3
"""Mechanical helpers for splitting src/agraph/extract.clj.

The script deliberately does not understand extractor semantics. It only
operates on top-level Clojure forms so split batches can be driven by commands
instead of copying large source blocks through chat.
"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


DEFAULT_SOURCE = Path("src/agraph/extract.clj")


@dataclass(frozen=True)
class Form:
    name: str
    kind: str
    start: int
    end: int
    text: str

    @property
    def line_count(self) -> int:
        return self.text.count("\n") + (0 if self.text.endswith("\n") else 1)


def form_name(line: str) -> tuple[str, str] | None:
    match = re.match(
        r"^\((defn-?|defmacro|defmulti|defmethod|defonce|def)\s+"
        r"(?:\^:[A-Za-z0-9_.!?/*+<>=-]+\s+)*"
        r"([A-Za-z0-9_.!?/*+<>=-]+)",
        line,
    )
    if not match:
        return None
    return match.group(1), match.group(2)


def find_form_end(text: str, start: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    in_comment = False
    idx = start
    while idx < len(text):
        ch = text[idx]
        if in_comment:
            if ch == "\n":
                in_comment = False
            idx += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            idx += 1
            continue
        if ch == ";":
            in_comment = True
        elif ch == '"':
            in_string = True
        elif ch == "\\":
            idx += 2
            continue
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                end = idx + 1
                if end < len(text) and text[end] == "\n":
                    end += 1
                return end
        idx += 1
    raise ValueError(f"unbalanced top-level form starting at byte {start}")


def top_level_forms(text: str) -> list[Form]:
    forms: list[Form] = []
    offset = 0
    for line in text.splitlines(keepends=True):
        named = form_name(line)
        if named and line.startswith("("):
            kind, name = named
            end = find_form_end(text, offset)
            forms.append(Form(name=name, kind=kind, start=offset, end=end, text=text[offset:end]))
        offset += len(line)
    return forms


def read_forms(path: Path) -> tuple[str, list[Form]]:
    text = path.read_text()
    return text, top_level_forms(text)


def parse_replacement(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("replacement must be OLD=NEW")
    old, new = value.split("=", 1)
    if not old:
        raise argparse.ArgumentTypeError("replacement OLD must be non-empty")
    return old, new


def apply_replacements(text: str, replacements: list[tuple[str, str]]) -> str:
    for old, new in replacements:
        text = re.sub(rf"(?<![\w.!?/*+<>=-]){re.escape(old)}(?![\w.!?/*+<>=-])", new, text)
    return text


def target_template(ns_name: str, requires: list[str]) -> str:
    if requires:
        require_lines = "\n".join(f"            {req}" for req in requires[1:])
        if require_lines:
            return f"(ns {ns_name}\n  (:require {requires[0]}\n{require_lines}))\n\n"
        return f"(ns {ns_name}\n  (:require {requires[0]}))\n\n"
    return f"(ns {ns_name})\n\n"


def cmd_list(args: argparse.Namespace) -> None:
    _, forms = read_forms(args.source)
    for form in forms:
        if form.line_count >= args.min_lines:
            print(f"{form.line_count:5d} {form.kind:9s} {form.name}")


def cmd_move(args: argparse.Namespace) -> None:
    source_text, forms = read_forms(args.source)
    by_name = {form.name: form for form in forms}
    missing = [name for name in args.forms if name not in by_name]
    if missing:
        raise SystemExit(f"missing top-level forms: {', '.join(missing)}")

    moving = [by_name[name] for name in args.forms]
    moving_ranges = sorted((form.start, form.end) for form in moving)
    out_parts: list[str] = []
    cursor = 0
    for start, end in moving_ranges:
        out_parts.append(source_text[cursor:start])
        cursor = end
    out_parts.append(source_text[cursor:])
    args.source.write_text("".join(out_parts))

    moved_text = "\n".join(form.text.rstrip() for form in moving) + "\n"
    moved_text = apply_replacements(moved_text, args.replace)
    if args.target.exists():
        target_text = args.target.read_text().rstrip() + "\n\n" + moved_text
    else:
        if not args.ns:
            raise SystemExit("--ns is required when target does not exist")
        target_text = target_template(args.ns, args.require) + moved_text
    args.target.write_text(target_text)


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    list_parser = subparsers.add_parser("list")
    list_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    list_parser.add_argument("--min-lines", type=int, default=1)
    list_parser.set_defaults(func=cmd_list)

    move_parser = subparsers.add_parser("move")
    move_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    move_parser.add_argument("--target", type=Path, required=True)
    move_parser.add_argument("--ns")
    move_parser.add_argument("--require", action="append", default=[])
    move_parser.add_argument("--replace", type=parse_replacement, action="append", default=[])
    move_parser.add_argument("forms", nargs="+")
    move_parser.set_defaults(func=cmd_move)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
