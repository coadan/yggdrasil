#!/usr/bin/env python3
"""Split agent results by how much of a task's vocabulary its answer files use.

For each case, overlap is the share of the task's distinct content words (at
least three letters, not in a short stopword list) that occur in any expected
file at the searched revision, case-insensitively. Low overlap means an exact
text search has little to match, the situation an agent's grep handles worst.
Cases are split into terciles of overlap, and each arm's hit rate and first
search-call hit rate are printed per tercile.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from report import inspect_trajectory

STOPWORDS = set("""
the and for that this with from are was were but not you your our they them their when
what which while would should could can will just than then there here have has had into
onto also only even does did doing done any all some more most other such each very too
its it's i'm i've don't doesn't isn't can't won't like want need needs using use used get
gets got make makes made way ways one two new old same still out about after before over
under again because been being way it's way thing things something someone anything able
""".split())


def words(text):
    return {word.lower() for word in re.findall(r"[A-Za-z][A-Za-z']{2,}", text)} - STOPWORDS


def overlap(task, repo, paths):
    blob = ''
    for path in paths:
        result = subprocess.run(['git', '-C', str(repo), 'show', f'HEAD:{path}'],
                                capture_output=True, text=True, errors='replace')
        blob += result.stdout.lower() + '\n' + path.lower() + '\n'
    terms = words(task)
    if not terms:
        return 0.0
    return sum(1 for term in terms if term in blob) / len(terms)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--suite', type=Path, required=True)
    parser.add_argument('--style', default='symptom')
    parser.add_argument('--split', default='dev')
    args = parser.parse_args()
    suite = args.suite.expanduser()
    name = f'agent-tasks-{args.split}.json' if args.style == 'issue' else f'agent-tasks-{args.split}-{args.style}.json'
    tasks = json.loads((suite / name).read_text())
    rows = json.loads((args.run / 'results.json').read_text())
    arms = sorted({row['arm'] for row in rows})
    by_case = {}
    for row in rows:
        by_case.setdefault(row['case'], {})[row['arm']] = row
    paired = {case: runs for case, runs in by_case.items() if all(arm in runs for arm in arms)}
    scores = {}
    for case, runs in paired.items():
        row = next(iter(runs.values()))
        scores[case] = overlap(tasks[case]['task'], (suite / 'repos' / row['repo']).resolve(), row['expectedPaths'])
    ordered = sorted(paired, key=lambda case: scores[case])
    third = len(ordered) / 3
    buckets = {'low': ordered[:round(third)], 'mid': ordered[round(third):round(2 * third)],
               'high': ordered[round(2 * third):]}
    print(f'{len(paired)} cases; overlap terciles split at '
          f'{scores[buckets["mid"][0]]:.2f} and {scores[buckets["high"][0]]:.2f}\n')
    header = 'tercile  n   ' + '  '.join(f'{arm:>16}' for arm in arms)
    print('final answer names an expected file')
    print(header)
    for label, cases in buckets.items():
        cells = '  '.join(f"{sum(paired[c][arm]['hit'] for c in cases):>9}/{len(cases):<3} {sum(paired[c][arm]['hit'] for c in cases) / len(cases):3.0%}"
                          for arm in arms)
        print(f'{label:7}  {len(cases):3} {cells}')
    print('\nfirst successful search shows an expected file (hits/classified, unknown separately)')
    print(header)
    for label, cases in buckets.items():
        cells = []
        for arm in arms:
            hits = classified = 0
            for case in cases:
                log = args.run / 'logs' / f'{case}.{arm}.jsonl'
                trace = inspect_trajectory(log, set(paired[case][arm]['expectedPaths']))
                first = next(iter(trace['searches']), None)
                hits += first is True
                classified += first is not None
            cells.append(f'{hits}/{classified}, {len(cases) - classified} unknown'.rjust(16))
        print(f'{label:7}  {len(cases):3} ' + '  '.join(cells))
    (args.run / 'overlap.json').write_text(json.dumps(scores, indent=1, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
