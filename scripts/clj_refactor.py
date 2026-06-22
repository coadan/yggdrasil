#!/usr/bin/env python3
"""Mechanical helpers for splitting large Clojure namespaces.

The script deliberately does not understand project semantics. It only operates
on top-level Clojure forms so split batches can be driven by commands instead
of copying large source blocks through chat.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


DEFAULT_SOURCE = Path("src/ygg/extract.clj")


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
        r"^\((defn-?|defmacro|defmulti|defmethod|defonce|deftest|def)\s+"
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


def parse_mapping(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("mapping must be KEY=VALUE")
    key, mapped = value.split("=", 1)
    if not key or not mapped:
        raise argparse.ArgumentTypeError("mapping KEY and VALUE must be non-empty")
    return key, mapped


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
    move_forms(
        source=args.source,
        target=args.target,
        ns=args.ns,
        requires=args.require,
        replacements=args.replace,
        form_names=args.forms,
    )


def cmd_delete(args: argparse.Namespace) -> None:
    delete_forms(source=args.source, form_names=args.forms)


def cmd_compact_blanks(args: argparse.Namespace) -> None:
    compact_blank_runs(source=args.source, max_blank_lines=args.max_blank_lines)


def selected_form_names(
    forms: list[Form],
    *,
    explicit: list[str],
    prefixes: list[str],
    from_form: str | None,
    to_form: str | None,
) -> list[str]:
    names = [form.name for form in forms]
    selected: list[str] = []
    seen: set[str] = set()

    def add(name: str) -> None:
        if name not in seen:
            selected.append(name)
            seen.add(name)

    for name in explicit:
        if name not in names:
            raise SystemExit(f"missing top-level form: {name}")
        add(name)

    for prefix in prefixes:
        matched = [name for name in names if name.startswith(prefix)]
        if not matched:
            raise SystemExit(f"no top-level forms match prefix: {prefix}")
        for name in matched:
            add(name)

    if from_form or to_form:
        if not from_form or not to_form:
            raise SystemExit("--from-form and --to-form must be provided together")
        try:
            start = names.index(from_form)
            end = names.index(to_form)
        except ValueError as exc:
            raise SystemExit(f"missing range form: {exc}") from exc
        if start > end:
            raise SystemExit("--from-form must appear before --to-form")
        for name in names[start : end + 1]:
            add(name)

    return selected


def focused_test_command(test_names: list[str]) -> list[str]:
    test_vars = " ".join(f"#'ygg.extract-test/{name}" for name in test_names)
    return [
        "clojure",
        "-Sdeps",
        '{:paths ["src" "resources" "test"] :deps {lambdaisland/kaocha {:mvn/version "1.91.1392"} nubank/matcher-combinators {:mvn/version "3.10.0"}}}',
        "-M",
        "-e",
        f"(require 'clojure.test 'ygg.extract-test) (clojure.test/test-vars [{test_vars}])",
    ]


def cmd_manifest(args: argparse.Namespace) -> None:
    _, forms = read_forms(args.source)
    form_names = selected_form_names(
        forms,
        explicit=args.forms,
        prefixes=args.prefix,
        from_form=args.from_form,
        to_form=args.to_form,
    )
    if not form_names:
        raise SystemExit("no forms selected")

    manifest: dict[str, object] = {
        "source": str(args.source),
        "target": args.target,
        "ns": args.ns,
    }
    if args.require:
        manifest["require"] = args.require
    if args.source_require:
        manifest["sourceRequire"] = args.source_require
    if args.replace:
        manifest["replace"] = args.replace
    manifest["forms"] = form_names
    if args.route:
        manifest["routes"] = dict(args.route)
    if args.test:
        manifest["tests"] = [focused_test_command(args.test)]
    if args.stage:
        manifest["stage"] = args.stage
    if args.public:
        manifest["targetPublic"] = args.public
    if args.compact:
        manifest["compact"] = True
    manifest["commit"] = args.commit

    text = json.dumps(manifest, indent=2) + "\n"
    if args.out:
        args.out.write_text(text)
    else:
        print(text, end="")


def move_forms(
    *,
    source: Path,
    target: Path,
    ns: str | None,
    requires: list[str],
    replacements: list[tuple[str, str]],
    form_names: list[str],
) -> None:
    source_text, forms = read_forms(source)
    by_name = {form.name: form for form in forms}
    missing = [name for name in form_names if name not in by_name]
    if missing:
        raise SystemExit(f"missing top-level forms: {', '.join(missing)}")

    moving = [by_name[name] for name in form_names]
    moving_ranges = sorted((form.start, form.end) for form in moving)
    out_parts: list[str] = []
    cursor = 0
    for start, end in moving_ranges:
        out_parts.append(source_text[cursor:start])
        cursor = end
    out_parts.append(source_text[cursor:])
    source.write_text("".join(out_parts))

    moved_text = "\n".join(form.text.rstrip() for form in moving) + "\n"
    moved_text = apply_replacements(moved_text, replacements)
    if target.exists():
        target_text = target.read_text().rstrip() + "\n\n" + moved_text
    else:
        if not ns:
            raise SystemExit("--ns is required when target does not exist")
        target_text = target_template(ns, requires) + moved_text
    target.write_text(target_text)


def delete_forms(*, source: Path, form_names: list[str]) -> None:
    source_text, forms = read_forms(source)
    by_name = {form.name: form for form in forms}
    missing = [name for name in form_names if name not in by_name]
    if missing:
        raise SystemExit(f"missing top-level forms: {', '.join(missing)}")

    deleting_ranges = sorted((by_name[name].start, by_name[name].end) for name in form_names)
    out_parts: list[str] = []
    cursor = 0
    for start, end in deleting_ranges:
        out_parts.append(source_text[cursor:start])
        cursor = end
    out_parts.append(source_text[cursor:])
    source.write_text("".join(out_parts))


def compact_blank_runs(*, source: Path, max_blank_lines: int) -> None:
    if max_blank_lines < 0:
        raise SystemExit("--max-blank-lines must be non-negative")
    out: list[str] = []
    blank_count = 0
    for line in source.read_text().splitlines(keepends=True):
        if line.strip():
            blank_count = 0
            out.append(line)
        else:
            blank_count += 1
            if blank_count <= max_blank_lines:
                out.append(line)
    source.write_text("".join(out))


def add_ns_require(source: Path, require: str) -> None:
    text = source.read_text()
    if require in text:
        return
    lines = text.splitlines(keepends=True)
    in_require = False
    for idx, line in enumerate(lines):
        if "(:require" in line:
            in_require = True
            continue
        if in_require and (line.startswith("  (:") or line.startswith("  (:import")):
            lines.insert(idx, f"            {require}\n")
            source.write_text("".join(lines))
            return
        if in_require and line.startswith("            [") and re.search(r"\]\)+\s*$", line.rstrip()):
            lines.insert(idx, f"            {require}\n")
            source.write_text("".join(lines))
            return
    raise SystemExit(f"could not locate require block in {source}")
    source.write_text("".join(lines))


def patch_routes(source: Path, routes: dict[str, str]) -> None:
    text = source.read_text()
    for kind, function in routes.items():
        pattern = re.compile(rf"({re.escape(kind)}\s+)\([^()\n]+\s+run-id\s+file\)")
        text, count = pattern.subn(rf"\1({function} run-id file)", text, count=1)
        if count != 1:
            raise SystemExit(f"could not patch route for {kind}")
    source.write_text(text)


def apply_file_replacements(path: Path, replacements: list[tuple[str, str]]) -> None:
    path.write_text(apply_replacements(path.read_text(), replacements))


def apply_literal_file_replacements(path: Path, replacements: dict[str, str]) -> None:
    text = path.read_text()
    for old, new in replacements.items():
        if old not in text:
            raise SystemExit(f"could not find literal replacement text in {path}: {old}")
        text = text.replace(old, new)
    path.write_text(text)


def publicize_forms(path: Path, form_names: list[str]) -> None:
    text = path.read_text()
    for name in form_names:
        if re.search(rf"\(defn\s+{re.escape(name)}(\s|\n)", text):
            continue
        text, count = re.subn(
            rf"\(defn-\s+{re.escape(name)}(\s|\n)",
            rf"(defn {name}\1",
            text,
            count=1,
        )
        if count != 1:
            raise SystemExit(f"could not publicize target form in {path}: {name}")
    path.write_text(text)


def run_command(command: list[str], *, cwd: Path) -> None:
    print("+ " + " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def git_ignored(path: str, *, cwd: Path) -> bool:
    result = subprocess.run(
        ["git", "check-ignore", "--quiet", path],
        cwd=cwd,
        check=False,
    )
    return result.returncode == 0


def cmd_batch(args: argparse.Namespace) -> None:
    repo = args.repo
    batch = json.loads(args.manifest.read_text())
    source = repo / batch.get("source", str(DEFAULT_SOURCE))
    target = repo / batch["target"]
    replacements = [parse_replacement(value) for value in batch.get("replace", [])]
    move_forms(
        source=source,
        target=target,
        ns=batch.get("ns"),
        requires=batch.get("require", []),
        replacements=[parse_replacement(value) for value in batch.get("targetReplace", [])],
        form_names=batch["forms"],
    )
    if batch.get("sourceRequire"):
        add_ns_require(source, batch["sourceRequire"])
    if replacements:
        apply_file_replacements(source, replacements)
    if batch.get("sourceReplace"):
        apply_file_replacements(source, [parse_replacement(value) for value in batch["sourceReplace"]])
    if batch.get("targetTextReplace"):
        apply_literal_file_replacements(target, batch["targetTextReplace"])
    if batch.get("sourceTextReplace"):
        apply_literal_file_replacements(source, batch["sourceTextReplace"])
    if batch.get("targetPublic"):
        publicize_forms(target, batch["targetPublic"])
    if batch.get("routes"):
        patch_routes(source, batch["routes"])
    if batch.get("compact"):
        compact_blank_runs(source=source, max_blank_lines=1)
        compact_blank_runs(source=target, max_blank_lines=1)
    for command in batch.get("tests", []):
        run_command(command, cwd=repo)
    if args.commit:
        paths = [str(source.relative_to(repo)), batch["target"]]
        for extra in batch.get("stage", []):
            paths.append(extra)
        ignored_paths = [path for path in paths if git_ignored(path, cwd=repo)]
        paths = [path for path in paths if path not in ignored_paths]
        for path in ignored_paths:
            print(f"skipping ignored stage path: {path}")
        run_command(["git", "restore", "--staged", ":/"], cwd=repo)
        if paths:
            run_command(["git", "add", *paths], cwd=repo)
        run_command(["git", "commit", "-m", batch["commit"]], cwd=repo)


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

    delete_parser = subparsers.add_parser("delete")
    delete_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    delete_parser.add_argument("forms", nargs="+")
    delete_parser.set_defaults(func=cmd_delete)

    compact_parser = subparsers.add_parser("compact-blanks")
    compact_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    compact_parser.add_argument("--max-blank-lines", type=int, default=2)
    compact_parser.set_defaults(func=cmd_compact_blanks)

    manifest_parser = subparsers.add_parser("manifest")
    manifest_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    manifest_parser.add_argument("--target", required=True)
    manifest_parser.add_argument("--ns", required=True)
    manifest_parser.add_argument("--require", action="append", default=[])
    manifest_parser.add_argument("--source-require")
    manifest_parser.add_argument("--replace", action="append", default=[])
    manifest_parser.add_argument("--route", type=parse_mapping, action="append", default=[])
    manifest_parser.add_argument("--test", action="append", default=[])
    manifest_parser.add_argument("--stage", action="append", default=[])
    manifest_parser.add_argument("--public", action="append", default=[])
    manifest_parser.add_argument("--compact", action="store_true")
    manifest_parser.add_argument("--commit", required=True)
    manifest_parser.add_argument("--prefix", action="append", default=[])
    manifest_parser.add_argument("--from-form")
    manifest_parser.add_argument("--to-form")
    manifest_parser.add_argument("--out", type=Path)
    manifest_parser.add_argument("forms", nargs="*")
    manifest_parser.set_defaults(func=cmd_manifest)

    batch_parser = subparsers.add_parser("batch")
    batch_parser.add_argument("manifest", type=Path)
    batch_parser.add_argument("--repo", type=Path, default=Path("."))
    batch_parser.add_argument("--commit", action="store_true")
    batch_parser.set_defaults(func=cmd_batch)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
