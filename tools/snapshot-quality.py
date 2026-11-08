#!/usr/bin/env python3
"""Score a suite of pinned cases by searching a snapshot of each case's base.

Each case names a repository clone, a `base` commit, a query and the expected
paths at that commit (as written by tools/issue-suite.py). For every case the
base tree is exported with `git archive` into a temporary directory, searched
in lexical mode with a temporary storage root and an empty user configuration,
and removed. The rank is the position of the first expected path among the
result records followed by `morePaths`, as in tools/retrieval-quality.py.

By default only lexical mode is scored, with an empty user configuration. With
`--config` (a user configuration that names an embedding provider) each
snapshot is indexed first, which embeds it, and every `--mode` is scored; a
case whose semantic coverage is incomplete keeps its rows but is counted under
`incomplete`. Embedding a snapshot per case is slow, so run one scorer at a
time against a single local provider. Plugins configured in a snapshot's own
`.ygg/config.json` are not used, because the user configuration is the trust
boundary for executables.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--binary', required=True)
    parser.add_argument('--repos', required=True, help='Directory of clones named by case repo')
    parser.add_argument('--suite', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--limit', type=int, default=10)
    parser.add_argument('--config', type=Path, help='User configuration with an embedding provider')
    parser.add_argument('--mode', action='append', choices=('lexical', 'auto', 'semantic'),
                        help='Search modes to score (repeatable; default lexical)')
    args = parser.parse_args()
    modes = args.mode or ['lexical']
    cases = json.loads(args.suite.read_text())
    rows = []
    report = dict(binarySha256=digest(args.binary), suiteSha256=digest(args.suite), modes=modes, rows=rows)
    with tempfile.TemporaryDirectory(prefix='ygg-snapshot-') as scratch:
        scratch = Path(scratch)
        config = scratch / 'user.json'
        config.write_text(args.config.read_text() if args.config else '{"schema":"ygg.config/v1"}\n')
        for case in cases:
            clone = Path(args.repos) / case['repo']
            tree, store = scratch / 'tree', scratch / 'store'
            subprocess.run(['rm', '-rf', str(tree), str(store)], check=True)
            tree.mkdir()
            archive = subprocess.Popen(['git', '-C', str(clone), 'archive', case['base']], stdout=subprocess.PIPE)
            subprocess.run(['tar', '-x', '-C', str(tree)], stdin=archive.stdout, check=True)
            if archive.wait() != 0:
                rows.extend(dict(case=case['id'], mode=mode, error='git archive failed') for mode in modes)
                continue
            env = dict(os.environ, YGG_CONFIG=str(config), YGG_STORAGE_ROOT=str(store))
            complete = None
            if args.config:
                try:
                    indexed = subprocess.run([args.binary, 'index', '--root', str(tree)],
                                             capture_output=True, text=True, env=env, timeout=3600)
                    coverage = json.loads(indexed.stdout).get('data', {}).get('coverage') or {}
                    complete = bool(coverage.get('Complete'))
                except (subprocess.TimeoutExpired, json.JSONDecodeError):
                    complete = False
            for mode in modes:
                start = time.monotonic()
                row = dict(case=case['id'], base=case['base'], mode=mode, complete=complete)
                try:
                    result = subprocess.run([args.binary, 'search', '--root', str(tree), '--mode', mode,
                                             '--limit', str(args.limit), case['query']],
                                            capture_output=True, text=True, env=env, timeout=300)
                    data = json.loads(result.stdout).get('data', {})
                    paths = [record['path'] for record in data.get('records', [])] + data.get('morePaths', [])
                    row.update(rank=next((index + 1 for index, path in enumerate(paths)
                                          if path in case['expectedPaths']), None), paths=paths,
                               activeMode=data.get('activeMode'), fallbackReason=data.get('fallbackReason'))
                except (subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                    row['error'] = str(error)
                row['seconds'] = round(time.monotonic() - start, 3)
                rows.append(row)
                print(case['id'], mode, row.get('rank'), flush=True)
            args.output.write_text(json.dumps(report, indent=1) + '\n')
    report['summary'] = {}
    for mode in modes:
        selected = [row for row in rows if row.get('mode') == mode]
        scored = [row for row in selected if 'error' not in row and row.get('complete') is not False]
        hits = [row for row in scored if row['rank'] and row['rank'] <= args.limit]
        report['summary'][mode] = dict(
            count=len(selected), errors=sum('error' in row for row in selected),
            incomplete=sum(row.get('complete') is False for row in selected), hitAt10=len(hits),
            mrrAt10=round(sum(1 / row['rank'] for row in hits) / max(1, len(scored)), 3))
    args.output.write_text(json.dumps(report, indent=1) + '\n')
    print(json.dumps(report['summary']))


if __name__ == '__main__':
    main()
