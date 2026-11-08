#!/usr/bin/env python3
"""Score a suite of pinned cases by searching a snapshot of each case's base.

Each case names a repository clone, a `base` commit, a query and the expected
paths at that commit (as written by tools/issue-suite.py). For every case the
base tree is exported with `git archive` into a temporary directory, searched
in lexical mode with a temporary storage root and an empty user configuration,
and removed. The rank is the position of the first expected path among the
result records followed by `morePaths`, as in tools/retrieval-quality.py.

Only lexical mode is scored: a fresh snapshot per case has no embeddings, and
building them would take a full embedding run per case. Plugins configured in a
snapshot's own `.ygg/config.json` are not used either, because an empty user
configuration is the trust boundary for executables.
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
    args = parser.parse_args()
    cases = json.loads(args.suite.read_text())
    rows = []
    report = dict(binarySha256=digest(args.binary), suiteSha256=digest(args.suite), mode='lexical', rows=rows)
    with tempfile.TemporaryDirectory(prefix='ygg-snapshot-') as scratch:
        scratch = Path(scratch)
        config = scratch / 'user.json'
        config.write_text('{"schema":"ygg.config/v1"}\n')
        for case in cases:
            clone = Path(args.repos) / case['repo']
            tree, store = scratch / 'tree', scratch / 'store'
            subprocess.run(['rm', '-rf', str(tree), str(store)], check=True)
            tree.mkdir()
            archive = subprocess.Popen(['git', '-C', str(clone), 'archive', case['base']], stdout=subprocess.PIPE)
            subprocess.run(['tar', '-x', '-C', str(tree)], stdin=archive.stdout, check=True)
            if archive.wait() != 0:
                rows.append(dict(case=case['id'], error='git archive failed'))
                continue
            env = dict(os.environ, YGG_CONFIG=str(config), YGG_STORAGE_ROOT=str(store))
            start = time.monotonic()
            row = dict(case=case['id'], base=case['base'])
            try:
                result = subprocess.run([args.binary, 'search', '--root', str(tree), '--mode', 'lexical',
                                         '--limit', str(args.limit), case['query']],
                                        capture_output=True, text=True, env=env, timeout=300)
                data = json.loads(result.stdout).get('data', {})
                paths = [record['path'] for record in data.get('records', [])] + data.get('morePaths', [])
                row.update(rank=next((index + 1 for index, path in enumerate(paths)
                                      if path in case['expectedPaths']), None), paths=paths)
            except (subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                row['error'] = str(error)
            row['seconds'] = round(time.monotonic() - start, 3)
            rows.append(row)
            print(case['id'], row.get('rank'), flush=True)
    scored = [row for row in rows if 'error' not in row]
    hits = [row for row in scored if row['rank'] and row['rank'] <= args.limit]
    report['summary'] = dict(count=len(rows), errors=len(rows) - len(scored), hitAt10=len(hits),
                             mrrAt10=round(sum(1 / row['rank'] for row in hits) / max(1, len(scored)), 3))
    args.output.write_text(json.dumps(report, indent=1) + '\n')
    print(json.dumps(report['summary']))


if __name__ == '__main__':
    main()
