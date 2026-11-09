#!/usr/bin/env python3
"""Measure how much of a cross-artifact change search finds.

`draw` derives cases from commits that modified files of at least two kinds,
classified by file name only: code, docs, and config (build, deployment and
infrastructure files). A case pairs the commit subject with every classified
file the commit modified, pinned to the commit's first parent, where all of
those files exist. Subjects that appear verbatim at the base are dropped.
Cases are split deterministically into dev and held-out suites.

`score` exports each case's base with `git archive` and ranks paths for the
subject in two ways:
- `ygg search` in each requested mode, taking result records then
  `morePaths`;
- an `rg` baseline: files ranked by how many distinct query words they contain
  (case-insensitive, fixed strings, hidden files included), then by the total
  number of matching lines, then by path. It stands in for an agent's grep
  loop over the query's words.

Coverage is the share of a case's files among the first `--depth` paths,
overall and per kind. A case is complete when every file is found.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

CODE = {'.go', '.py', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.mts', '.cts', '.rs', '.cs', '.fs',
        '.vb', '.java', '.kt', '.scala', '.rb', '.php', '.swift', '.c', '.h', '.cc', '.cpp', '.hpp',
        '.clj', '.cljs', '.cljc', '.ex', '.exs', '.lua', '.sh', '.bash', '.zsh', '.ps1', '.sql',
        '.vue', '.svelte', '.css', '.scss', '.html'}
DOCS = {'.md', '.mdx', '.rst', '.txt', '.adoc'}
CONFIG = {'.tf', '.tfvars', '.hcl', '.yaml', '.yml', '.json', '.toml', '.ini', '.cfg', '.conf',
          '.xml', '.csproj', '.fsproj', '.props', '.targets', '.sln', '.edn', '.mod', '.nix',
          '.properties', '.gradle', '.env'}
CONFIG_NAMES = {'Dockerfile', 'Makefile', 'Containerfile', 'Justfile', 'Procfile', 'Vagrantfile',
                'Gemfile', 'Rakefile', 'CODEOWNERS'}
GENERATED = {'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock', 'go.sum', 'Cargo.lock', 'poetry.lock',
             'uv.lock', 'composer.lock', 'Gemfile.lock', 'bun.lock', 'deps.lock.json'}
STOPWORDS = {'the', 'and', 'for', 'with', 'from', 'into', 'that', 'this', 'when', 'not', 'are', 'was',
             'its', 'all', 'can', 'has', 'have', 'but', 'our', 'you', 'then', 'than', 'also', 'only',
             'via', 'per', 'out', 'use', 'any', 'one', 'new', 'more'}


def kind(path):
    name = os.path.basename(path)
    if name in GENERATED or name.endswith('.lock'):
        return None
    if name in CONFIG_NAMES or name.startswith('Dockerfile.'):
        return 'config'
    suffix = os.path.splitext(name)[1].lower()
    for label, suffixes in (('code', CODE), ('docs', DOCS), ('config', CONFIG)):
        if suffix in suffixes:
            return label
    return None


def coverage(paths, expected, depth):
    shown = set(paths[:depth])
    kinds = {}
    for path in expected:
        found, total = kinds.get(kind(path), (0, 0))
        kinds[kind(path)] = (found + (path in shown), total + 1)
    found = sum(value[0] for value in kinds.values())
    return dict(found=found, total=len(expected), complete=found == len(expected),
                kinds={key: list(value) for key, value in sorted(kinds.items())},
                firstRank=next((index + 1 for index, path in enumerate(paths) if path in expected), None))


def terms(query):
    words = []
    for word in re.findall(r'[A-Za-z0-9_]+', query.lower()):
        if len(word) >= 3 and word not in STOPWORDS and word not in words:
            words.append(word)
    return words


def grep_rank(tree, query):
    matched, lines = {}, {}
    for term in terms(query):
        result = subprocess.run(['rg', '--count', '--ignore-case', '--fixed-strings', '--hidden',
                                 '--glob', '!.git', '--no-messages', '-e', term, '.'],
                                cwd=tree, capture_output=True, text=True)
        for line in result.stdout.splitlines():
            path, _, count = line.rpartition(':')
            path = path[2:] if path.startswith('./') else path
            matched[path] = matched.get(path, 0) + 1
            lines[path] = lines.get(path, 0) + int(count)
    return sorted(matched, key=lambda path: (-matched[path], -lines[path], path))


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True).stdout


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def draw(args):
    output = Path(args.output).resolve()
    roots = [Path(path) for path in args.repo] or sorted(
        path for path in Path(args.repos).iterdir() if (path / '.git').exists())
    for target in ('dev', 'holdout'):
        if (output / f'{target}.json').exists():
            raise SystemExit(f'{output / target}.json exists; retire it before drawing a new suite')
    used = {(case['repo'], case['commit']) for suite in args.exclude
            for case in json.loads(Path(suite).read_text())}
    splits = {'dev': [], 'holdout': []}
    for root in roots:
        log = git(root, 'log', '--no-merges', '--first-parent', '--diff-filter=M', '--name-only',
                  '--format=\x01%H\x02%s', '-n', str(args.scan), 'HEAD')
        candidates, seen = [], set()
        for block in log.split('\x01')[1:]:
            header, _, names = block.partition('\n')
            sha, _, subject = header.partition('\x02')
            subject = subject.strip()
            if not 4 <= len(subject.split()) <= 30 or subject in seen or (root.name, sha) in used:
                continue
            expected = sorted({name for name in names.splitlines() if name and kind(name)})
            if not 2 <= len(expected) <= args.max_files or len({kind(name) for name in expected}) < 2:
                continue
            seen.add(subject)
            candidates.append((digest(f'{root.name}:{sha}'), sha, subject, expected))
        selected = []
        for _, sha, subject, expected in sorted(candidates):
            if len(selected) == args.per_repo:
                break
            base = git(root, 'rev-parse', f'{sha}^').strip()
            if not base:
                continue
            leaked = subprocess.run(['git', '-C', str(root), 'grep', '-q', '-F', '-e', subject, base],
                                    capture_output=True).returncode == 0
            binary = any('\0' in git(root, 'show', f'{base}:{name}')[:8192] for name in expected)
            if leaked or binary:
                continue
            selected.append(dict(id=f'{root.name}:{sha[:12]}', repo=root.name, query=subject,
                                 expectedPaths=expected, kinds=sorted({kind(name) for name in expected}),
                                 commit=sha, base=base))
        for case in selected:
            splits['dev' if int(digest(case['id'])[:2], 16) < 128 else 'holdout'].append(case)
    output.mkdir(parents=True, exist_ok=True)
    for name, cases in splits.items():
        (output / f'{name}.json').write_text(json.dumps(cases, indent=1) + '\n')
        print(name, len(cases), len({case['repo'] for case in cases}), 'repositories')


def summarize(rows, depth):
    summary = {}
    for method in sorted({row['method'] for row in rows}):
        selected = [row for row in rows if row['method'] == method and 'error' not in row]
        kinds = {}
        for row in selected:
            for label, (found, total) in row['kinds'].items():
                value = kinds.setdefault(label, [0, 0])
                value[0] += found
                value[1] += total
        summary[method] = dict(
            cases=len(selected), errors=sum(row['method'] == method and 'error' in row for row in rows),
            meanCoverage=round(sum(row['found'] / row['total'] for row in selected) / max(1, len(selected)), 3),
            complete=sum(row['complete'] for row in selected),
            files={label: f'{found}/{total}' for label, (found, total) in sorted(kinds.items())},
            mrr=round(sum(1 / row['firstRank'] for row in selected
                          if row['firstRank'] and row['firstRank'] <= 10) / max(1, len(selected)), 3),
            depth=depth)
    return summary


def score(args):
    cases = json.loads(Path(args.suite).read_text())
    modes = args.mode or ['lexical']
    rows = []
    report = dict(binary=args.binary, modes=modes, depth=args.depth, rows=rows)
    with tempfile.TemporaryDirectory(prefix='ygg-cross-') as scratch:
        scratch = Path(scratch)
        config = scratch / 'user.json'
        config.write_text(Path(args.config).read_text() if args.config else '{"schema":"ygg.config/v1"}\n')
        embedded = 'embedding' in json.loads(config.read_text())
        for case in cases:
            tree, store = scratch / 'tree', scratch / 'store'
            subprocess.run(['rm', '-rf', str(tree), str(store)], check=True)
            tree.mkdir()
            archive = subprocess.Popen(['git', '-C', str(Path(args.repos) / case['repo']), 'archive', case['base']],
                                       stdout=subprocess.PIPE)
            subprocess.run(['tar', '-x', '-C', str(tree)], stdin=archive.stdout, check=True)
            archive.stdout.close()
            if archive.wait() != 0:
                rows.append(dict(case=case['id'], method='all', error='git archive failed'))
                continue
            repo_config = tree / '.ygg' / 'config.json'
            repo_config.unlink(missing_ok=True)
            if args.repo_config:
                repo_config.parent.mkdir(exist_ok=True)
                repo_config.write_text(Path(args.repo_config).read_text())
            env = dict(os.environ, YGG_CONFIG=str(config), YGG_STORAGE_ROOT=str(store))
            if embedded:
                subprocess.run([args.binary, 'index', '--root', str(tree)], capture_output=True, env=env, timeout=3600)
            methods = ([] if args.no_grep else [('rg', None)]) + [(f'ygg-{mode}', mode) for mode in modes]
            for method, mode in methods:
                start = time.monotonic()
                row = dict(case=case['id'], method=method)
                try:
                    if mode is None:
                        paths = grep_rank(tree, case['query'])
                    else:
                        result = subprocess.run([args.binary, 'search', '--root', str(tree), '--mode', mode,
                                                 '--limit', '10', case['query']],
                                                capture_output=True, text=True, env=env, timeout=300)
                        data = json.loads(result.stdout).get('data', {})
                        paths = []
                        for path in [record['path'] for record in data.get('records', [])] + data.get('morePaths', []):
                            if path not in paths:
                                paths.append(path)
                        row.update(activeMode=data.get('activeMode'), rerank=data.get('rerank'))
                    row.update(coverage(paths, case['expectedPaths'], args.depth), paths=paths[:args.depth])
                except (subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                    row['error'] = str(error)
                row['seconds'] = round(time.monotonic() - start, 3)
                rows.append(row)
                print(case['id'], method, row.get('found'), '/', row.get('total'), flush=True)
            Path(args.output).write_text(json.dumps(report, indent=1) + '\n')
    report['summary'] = summarize(rows, args.depth)
    Path(args.output).write_text(json.dumps(report, indent=1) + '\n')
    print(json.dumps(report['summary'], indent=1))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    drawing = commands.add_parser('draw')
    drawing.add_argument('--repos', default=str(Path.home() / 'repos'))
    drawing.add_argument('--repo', action='append', default=[], help='Clone to draw from (repeatable)')
    drawing.add_argument('--output', required=True)
    drawing.add_argument('--per-repo', type=int, default=12)
    drawing.add_argument('--scan', type=int, default=1500)
    drawing.add_argument('--max-files', type=int, default=8)
    drawing.add_argument('--exclude', action='append', default=[], metavar='SUITE')
    scoring = commands.add_parser('score')
    scoring.add_argument('--binary', required=True)
    scoring.add_argument('--repos', required=True, help='Directory of clones named by case repo')
    scoring.add_argument('--suite', required=True)
    scoring.add_argument('--output', required=True)
    scoring.add_argument('--depth', type=int, default=20)
    scoring.add_argument('--config', help='User configuration (embedding provider, reranker)')
    scoring.add_argument('--mode', action='append', choices=('lexical', 'auto', 'semantic', 'graph'))
    scoring.add_argument('--repo-config', help='Repository configuration (extractor plugins) written into '
                         'every snapshot; without it a committed .ygg/config.json is removed')
    scoring.add_argument('--no-grep', action='store_true', help='Skip the rg baseline')
    args = parser.parse_args()
    draw(args) if args.command == 'draw' else score(args)


if __name__ == '__main__':
    main()
