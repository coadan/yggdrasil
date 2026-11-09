#!/usr/bin/env python3
"""Structural cards and symbol jumps for the agent eval's navigation arms.

Declarations come from extractor records the eval store already holds (kinds
ending in function, method, class, type and similar). A declaration's body runs
from its line to the next declaration in the same file. Names resolve only
when exactly one declaration carries them, so every edge points at one checkable
file and line; ambiguous names are left out rather than guessed.

  decorate  read `ygg search` JSON on stdin and add a `card` to each of the
            first five records that lie inside a declaration:
            calledBy (call sites of the declaration, attributed to their
            enclosing declaration), calls (names called in its body) and
            related (type names used in its body), each `name path:line`
  def NAME  the declaration's path:line and its first lines
  refs NAME call sites `NAME(` as path:line with the line text

The store is found under YGG_STORAGE_ROOT by the repository root it records.
This lives in the eval so the interface can be measured before any change to
the library or CLI contract.
"""
import json
import os
from pathlib import Path
import re
import sqlite3
import sys

DECL = re.compile(r'-(function|method|class|struct|interface|type|enum|trait|func|constructor|record|impl|'
                  r'module|object|const|let)$')
TYPEISH = re.compile(r'-(class|struct|interface|type|enum|trait|record|object)$')
CALL = re.compile(r'\b([A-Za-z_][A-Za-z0-9_]{3,})\s*\(')
CAP = re.compile(r'\b([A-Z][A-Za-z0-9_]{3,})\b')
COMMON = {'self', 'this', 'super', 'print', 'len', 'isinstance', 'return', 'function', 'typeof', 'require',
          'assert', 'range', 'make', 'append', 'Error', 'String', 'Object', 'Array', 'Promise', 'None', 'True',
          'False'}
CARDS, CALLS, CALLERS, RELATED = 5, 3, 3, 2
REFS, DEF_LINES = 20, 12


def short(title):
    return re.split(r'[.:#/ ]+', title.strip())[-1]


def store_for(storage, repo):
    repo = str(Path(repo).resolve())
    for db in sorted(Path(storage).glob('indexes/*/search.sqlite3')):
        con = sqlite3.connect(f'file:{db}?mode=ro', uri=True)
        try:
            row = con.execute('select root from repository').fetchone()
        except sqlite3.Error:
            row = None
        finally:
            con.close()
        if row and str(Path(row[0]).resolve()) == repo:
            return db
    return None


class Index:
    def __init__(self, repo, rows):
        self.repo = Path(repo)
        self.decls, self.byname, self.lines = {}, {}, {}
        for kind, title, path, line in rows:
            name = short(title)
            if not DECL.search(kind) or len(name) < 4:
                continue
            self.decls.setdefault(path, []).append((line, name, kind))
            self.byname.setdefault(name, []).append((path, line, kind))
        for path in self.decls:
            self.decls[path].sort()

    @classmethod
    def load(cls, storage, repo):
        db = store_for(storage, repo)
        if db is None:
            return cls(repo, [])
        con = sqlite3.connect(f'file:{db}?mode=ro', uri=True)
        try:
            rows = con.execute("select kind,title,path,start_line from records where source like 'plugin:%'").fetchall()
        finally:
            con.close()
        return cls(repo, rows)

    def text(self, path):
        if path not in self.lines:
            try:
                data = (self.repo / path).read_bytes()
                self.lines[path] = [] if b'\0' in data[:8192] else data.decode(errors='replace').splitlines()
            except OSError:
                self.lines[path] = []
        return self.lines[path]

    def unique(self, name):
        found = self.byname.get(name, [])
        return found[0] if len(found) == 1 else None

    def enclosing(self, path, line):
        best = None
        for decl in self.decls.get(path, []):
            if decl[0] > line:
                break
            best = decl
        return best

    def span(self, path, decl):
        later = [d[0] for d in self.decls[path] if d[0] > decl[0]]
        end = later[0] - 1 if later else len(self.text(path))
        return decl[0], min(end, decl[0] + 400)

    def files(self):
        for path in sorted(self.repo.rglob('*')):
            parts = path.relative_to(self.repo).parts
            if not path.is_file() or any(p.startswith('.') and p not in ('.github',) for p in parts[:-1]):
                continue
            try:
                if path.stat().st_size > 1 << 20:
                    continue
            except OSError:
                continue
            yield str(path.relative_to(self.repo))

    def call_sites(self, name, limit):
        pattern = re.compile(r'\b' + re.escape(name) + r'\s*\(')
        sites = []
        for path in self.files():
            for number, line in enumerate(self.text(path), 1):
                if pattern.search(line):
                    sites.append((path, number, line.strip()))
                    if len(sites) >= limit:
                        return sites
        return sites

    def card(self, path, line):
        decl = self.enclosing(path, line)
        if decl is None:
            return None
        start, end = self.span(path, decl)
        body = '\n'.join(self.text(path)[start - 1:end])
        name = decl[1]
        calls, related, seen = [], [], set()
        for match in CALL.finditer(body):
            other = match.group(1)
            target = None if other in COMMON or other == name else self.unique(other)
            if (target and not TYPEISH.search(target[2]) and target[0] != path and target[0] not in seen
                    and len(calls) < CALLS):
                seen.add(target[0])
                calls.append(f'{other} {target[0]}:{target[1]}')
        for match in CAP.finditer(body):
            other = match.group(1)
            target = None if other in COMMON or other == name else self.unique(other)
            if (target and TYPEISH.search(target[2]) and target[0] != path and target[0] not in seen
                    and len(related) < RELATED):
                seen.add(target[0])
                related.append(f'{other} {target[0]}:{target[1]}')
        callers = []
        if self.unique(name):
            for site_path, number, _ in self.call_sites(name, 200):
                if site_path == path or site_path in seen:
                    continue
                owner = self.enclosing(site_path, number)
                seen.add(site_path)
                callers.append(f'{owner[1] if owner else "(top level)"} {site_path}:{number}')
                if len(callers) >= CALLERS:
                    break
        return {'symbol': f'{name} {path}:{decl[0]}', 'calledBy': callers, 'calls': calls, 'related': related}


def decorate(envelope, index):
    records = (envelope.get('data') or {}).get('records') or []
    done = set()
    for record in records:
        if len(done) >= CARDS:
            break
        if record['path'] in done:
            continue
        card = index.card(record['path'], record.get('startLine', 1))
        if card:
            record['card'] = card
            done.add(record['path'])
    return envelope


def define(name, index):
    found = index.byname.get(name, [])
    if not found:
        return f'no declaration named {name}'
    if len(found) > 1:
        return f'{name} is declared {len(found)} times:\n' + '\n'.join(f'  {p}:{l}' for p, l, _ in found[:REFS])
    path, line, _ = found[0]
    text = index.text(path)[line - 1:line - 1 + DEF_LINES]
    return f'{path}:{line}\n' + '\n'.join(f'{line + i:>6}  {t}' for i, t in enumerate(text))


def references(name, index):
    sites = index.call_sites(name, REFS + 1)
    if not sites:
        return f'no call sites of {name}'
    lines = [f'{p}:{n}  {t[:160]}' for p, n, t in sites[:REFS]]
    if len(sites) > REFS:
        lines.append(f'(first {REFS} shown)')
    return '\n'.join(lines)


def main(argv):
    storage, repo = os.environ.get('YGG_STORAGE_ROOT', ''), os.environ.get('YGG_AGENT_REPO', '.')
    if not argv or argv[0] not in ('decorate', 'def', 'refs') or (argv[0] != 'decorate' and len(argv) != 2):
        print('usage: cards.py decorate | def NAME | refs NAME', file=sys.stderr)
        return 2
    index = Index.load(storage, repo)
    if argv[0] == 'decorate':
        raw = sys.stdin.read()
        try:
            envelope = json.loads(raw)
        except json.JSONDecodeError:
            sys.stdout.write(raw)
            return 1
        print(json.dumps(decorate(envelope, index)))
        return 0
    print(define(argv[1], index) if argv[0] == 'def' else references(argv[1], index))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
