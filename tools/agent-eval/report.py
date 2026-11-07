#!/usr/bin/env python3
"""Compare arms in a run.py output directory.

Prints, per arm, final-answer quality and cost; paired differences between
arms on the same cases; per repository hits; and, per search round, how often
a search call's result named an expected path. A search call is a Grep or
Glob tool call, or a Bash command starting with ygg, grep, find or ls.
"""
import argparse
import json
from pathlib import Path
import sys

# Bash programs that count as a search call; cat, sed and head read files.
SEARCH_PROGRAMS = {'ygg', 'rg', 'grep', 'egrep', 'fgrep', 'find', 'ls', 'fd'}


def search_calls(log, arm):
    """Yield (tool call, result text) for each search call, in order."""
    uses, results = [], {}
    for line in Path(log).read_text().splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        content = (event.get('message') or {}).get('content')
        if not isinstance(content, list):
            continue
        for item in content:
            if item.get('type') == 'tool_use':
                uses.append(item)
            elif item.get('type') == 'tool_result':
                text = item.get('content')
                results[item['tool_use_id']] = (text if isinstance(text, str) else json.dumps(text),
                                                item.get('is_error'))
    for use in uses:
        program = str(use.get('input', {}).get('command', '')).split(maxsplit=1)[:1]
        text, error = results.get(use['id'], ('', True))
        if error:  # refused by a guard or failed; not a search the agent got results from
            continue
        if use['name'] in ('Grep', 'Glob') or (use['name'] == 'Bash' and program and program[0] in SEARCH_PROGRAMS):
            yield use, text


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--rounds', type=int, default=4, help='Last search round reported on its own')
    args = parser.parse_args()
    rows = json.loads((args.run / 'results.json').read_text())
    arms = sorted({row['arm'] for row in rows})
    by_case = {}
    for row in rows:
        by_case.setdefault(row['case'], {})[row['arm']] = row
    paired = {case: runs for case, runs in by_case.items() if all(arm in runs for arm in arms)}
    print(f'{len(paired)} cases with every arm ({", ".join(arms)})\n')

    print('arm      hit      recall  precision  first@1  seen     calls  input-tokens  cost    seconds')
    for arm in arms:
        group = [runs[arm] for runs in paired.values()]
        n = len(group)
        mean = lambda key: sum(row[key] or 0 for row in group) / n
        print(f"{arm:8} {sum(r['hit'] for r in group):3}/{n:<4} {mean('recall'):.3f}   {mean('precision'):.3f}      "
              f"{sum(r['firstHitRank'] == 1 for r in group):3}      {sum(bool(r['firstSeenCall']) for r in group):3}      "
              f"{mean('toolCalls'):4.1f}   {mean('inputTokens') / 1000:6.1f}k      "
              f"${sum(r['costUsd'] or 0 for r in group):.2f}  {mean('durationMs') / 1000:5.1f}")

    for index, left in enumerate(arms):
        for right in arms[index + 1:]:
            only_left = sorted(c for c, r in paired.items() if r[left]['hit'] and not r[right]['hit'])
            only_right = sorted(c for c, r in paired.items() if r[right]['hit'] and not r[left]['hit'])
            first_left = sum(1 for r in paired.values()
                             if r[left]['firstHitRank'] == 1 and r[right]['firstHitRank'] != 1)
            first_right = sum(1 for r in paired.values()
                              if r[right]['firstHitRank'] == 1 and r[left]['firstHitRank'] != 1)
            print(f'\n{left} vs {right}: hit only by {left} {len(only_left)}, only by {right} {len(only_right)}; '
                  f'expected file first only for {left} {first_left}, only for {right} {first_right}')
            for case in only_left:
                print(f'  {left} only: {case}')
            for case in only_right:
                print(f'  {right} only: {case}')

    print('\nhits per repository')
    repos = sorted({runs[arms[0]]['repo'] for runs in paired.values()})
    for repo in repos:
        group = [runs for runs in paired.values() if runs[arms[0]]['repo'] == repo]
        cells = '  '.join(f"{arm} {sum(r[arm]['hit'] for r in group)}/{len(group)}" for arm in arms)
        print(f'  {repo:20} {cells}')

    print('\nsearch calls whose result named an expected path, by round')
    for arm in arms:
        buckets = {}
        for case, runs in paired.items():
            row = runs[arm]
            log = args.run / 'logs' / f'{case}.{arm}.jsonl'
            for number, (use, text) in enumerate(search_calls(log, arm), 1):
                bucket = buckets.setdefault(min(number, args.rounds), [0, 0])
                bucket[0] += 1
                bucket[1] += any(path in text for path in row['expectedPaths'])
        cells = '  '.join(f"{'%d' % k if k < args.rounds else '%d+' % k}: {hit}/{count} ({hit / count:.0%})"
                          for k, (count, hit) in sorted(buckets.items()))
        print(f'  {arm:8} {cells}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
