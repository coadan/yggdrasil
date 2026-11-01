#!/usr/bin/env python3
"""Measure local source-localization probes without indexing the query suite."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--repos', default=str(Path.home() / 'repos'))
    parser.add_argument('--ygg-root', help='Override the Yggdrasil checkout under test')
    parser.add_argument('--suite', required=True, help='External JSON list of id, repo, query, expectedPaths')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    suite = Path(args.suite).resolve()
    cases = json.loads(suite.read_text())
    roots = {
        case['repo']: (Path(args.ygg_root) if case['repo'] == 'yggdrasil2' and args.ygg_root
                       else Path(args.repos) / case['repo']).resolve()
        for case in cases
    }
    for root in roots.values():
        if suite.is_relative_to(root) or output.is_relative_to(root):
            raise SystemExit('Keep suite and results outside every searched root to avoid answer leakage')
    rows = []
    report = dict(binarySha256=digest(args.binary), suiteSha256=digest(suite), rows=rows)
    for case in cases:
        root = roots[case['repo']]
        source_hashes = {path: digest(root / path) for path in case['expectedPaths']}
        revision = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                                  capture_output=True, text=True, check=True).stdout.strip()
        for mode in ('lexical', 'auto'):
            start = time.monotonic()
            row = dict(case=case['id'], mode=mode, revision=revision, sourceSha256=source_hashes)
            try:
                result = subprocess.run(
                    [args.binary, 'search', '--root', str(root), '--mode', mode,
                     '--limit', '10', case['query']], text=True, capture_output=True, timeout=150)
                envelope = json.loads(result.stdout)
                data = envelope.get('data', {})
                paths = [record['path'] for record in data.get('records', [])] + data.get('morePaths', [])
                rank = next((index + 1 for index, path in enumerate(paths)
                             if path in case['expectedPaths']), None)
                row.update(rank=rank, returncode=result.returncode, result=envelope)
                print(case['id'], mode, rank, data.get('activeMode'), data.get('fallbackReason'), flush=True)
            except (subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                row['error'] = str(error)
            row['seconds'] = round(time.monotonic() - start, 3)
            rows.append(row)
            report['summary'] = {
                lane: dict(count=len(selected), hitAt10=sum(0 < (r.get('rank') or 999) <= 10 for r in selected),
                           mrrAt10=round(sum(1 / r['rank'] for r in selected
                                           if 0 < (r.get('rank') or 999) <= 10) / len(selected), 3))
                for lane in ('lexical', 'auto')
                if (selected := [r for r in rows if r['mode'] == lane])
            }
            output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
