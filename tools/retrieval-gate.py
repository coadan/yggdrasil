#!/usr/bin/env python3
"""Run history-derived suites and compare each run with its saved baseline.

Every directory under --eval-dir that holds <split>.json and a `repos` link to
the directory containing its repositories is a suite. Each suite searches with
its own storage root (<suite>/store), writes run-<split>.json, and is compared
case by case with baseline-<split>.json when one exists.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

TOOL = Path(__file__).resolve().parent / 'retrieval-quality.py'
MODES = ('lexical', 'auto')


def rank(row):
    return row.get('rank') or float('inf')


def degraded(rows):
    """Rows whose result cannot be compared: errors or partial semantic coverage."""
    bad = []
    for row in rows:
        data = row.get('result', {}).get('data', {})
        if 'error' in row or row.get('returncode'):
            bad.append(row)
        elif row['mode'] == 'auto' and (data.get('activeMode') != 'hybrid'
                                        or data.get('semantic', {}).get('coverage') != 1):
            bad.append(row)
    return bad


def compare(name, base, run):
    if base['suiteSha256'] != run['suiteSha256']:
        print(f'{name}: baseline was measured on a different suite; not compared')
        return False
    before = {(row['case'], row['mode']): row for row in base['rows']}
    moved = sorted({row['case'] for row in run['rows']
                    if (old := before.get((row['case'], row['mode'])))
                    and (old['revision'], old['sourceSha256']) != (row['revision'], row['sourceSha256'])})
    if moved:
        print(f"{name}: repositories changed since the baseline for {len(moved)} cases "
              f"({', '.join(moved[:5])}); pin them or save a new baseline")
        return False
    for mode in MODES:
        b, r = base['summary'][mode], run['summary'][mode]
        changes = []
        for row in run['rows']:
            old = before.get((row['case'], mode)) if row['mode'] == mode else None
            if old and rank(old) != rank(row):
                changes.append((rank(row) < rank(old), row['case'], old.get('rank'), row.get('rank')))
        better = sum(change[0] for change in changes)
        print(f"{name} {mode}: hits {b['hitAt10']} -> {r['hitAt10']} of {r['count']}, "
              f"MRR@10 {b['mrrAt10']:.3f} -> {r['mrrAt10']:.3f}, "
              f'{better} better, {len(changes) - better} worse')
        for up, case, old, new in sorted(changes, key=lambda change: not change[0]):
            print(f"  {'+' if up else '-'} {case}: {old or '-'} -> {new or '-'}")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--eval-dir', default=str(Path.home() / '.local/share/ygg-eval'))
    parser.add_argument('--split', default='dev', choices=('dev', 'holdout'))
    parser.add_argument('--save-baseline', action='store_true',
                        help='Record this run as the baseline for later comparisons')
    args = parser.parse_args()
    binary = str(Path(args.binary).resolve())
    suites = sorted(path for path in Path(args.eval_dir).expanduser().iterdir()
                    if (path / f'{args.split}.json').is_file() and (path / 'repos').exists())
    if not suites:
        raise SystemExit(f'No suite under {args.eval_dir} holds {args.split}.json and a repos link')
    failed = False
    for suite in suites:
        output = suite / f'run-{args.split}.json'
        baseline = suite / f'baseline-{args.split}.json'
        env = dict(os.environ, YGG_STORAGE_ROOT=str(suite / 'store'))
        with open(suite / f'run-{args.split}.log', 'w') as log:
            result = subprocess.run(
                [sys.executable, '-I', str(TOOL), '--binary', binary,
                 '--repos', str((suite / 'repos').resolve()),
                 '--suite', str(suite / f'{args.split}.json'), '--output', str(output)],
                env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            print(f'{suite.name}: retrieval-quality failed; see run-{args.split}.log')
            failed = True
            continue
        run = json.loads(output.read_text())
        if bad := degraded(run['rows']):
            print(f"{suite.name}: {len(bad)} rows errored or lacked full semantic coverage: "
                  + ', '.join(sorted({row['case'] for row in bad})[:10]))
            failed = True
            continue
        if args.save_baseline:
            shutil.copyfile(output, baseline)
            print(f'{suite.name}: baseline saved')
        elif baseline.is_file():
            failed |= not compare(suite.name, json.loads(baseline.read_text()), run)
        else:
            print(f'{suite.name}: no baseline; run with --save-baseline first')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
