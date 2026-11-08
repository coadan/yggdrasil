#!/usr/bin/env python3
"""Measure whether ygg citations of expected files touch the fixed lines.

Reads the raw ygg results that run.py saved (`logs/*.ygg-raw.jsonl`). For every
record that cites an expected path, the fix commit's new-side hunks are mapped
onto the blob the agent searched: the case HEAD. A line maps only when no
change between the fix and HEAD touched it, and a region maps only when every
line does; unmappable regions are counted apart, never as overlap or miss.
File rank is deliberately not part of this report.

The baseline is the chance that a window of the same length, placed uniformly
in the HEAD file, intersects the mapped region. Citations certain to overlap
(a new file, or a window covering the file) are counted apart as `certain`;
`overlap`, `chance` and the miss distances cover the rest.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import sys

HUNK = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@')


def parse_hunks(diff):
    """Return (oldStart, oldCount, newStart, newCount) for each -U0 hunk."""
    hunks = []
    for line in diff.splitlines():
        match = HUNK.match(line)
        if match:
            old, old_count, new, new_count = match.groups()
            hunks.append((int(old), 1 if old_count is None else int(old_count),
                          int(new), 1 if new_count is None else int(new_count)))
    return hunks


def fixed_lines(hunks):
    """New-side lines of a fix. A pure deletion marks the line before it."""
    lines = set()
    for _, _, new, count in hunks:
        if count:
            lines.update(range(new, new + count))
        else:
            lines.add(max(new, 1))
    return lines


def map_line(line, hunks):
    """Map an old-side line through -U0 hunks, or None when a hunk changed it."""
    offset = 0
    for old, old_count, new, new_count in hunks:
        if old_count and old <= line < old + old_count:
            return None
        # An insertion (old_count 0) follows line `old`; other hunks start at it.
        end = old + old_count - 1 if old_count else old
        if end < line:
            offset += new_count - old_count
    return line + offset


def map_region(lines, hunks):
    mapped = set()
    for line in lines:
        target = map_line(line, hunks)
        if target is None:
            return None
        mapped.add(target)
    return mapped


def window_distance(start, end, lines):
    return min(0 if start <= line <= end else min(abs(line - start), abs(line - end)) for line in lines)


def chance(length, total, lines):
    """Probability that a uniformly placed window of `length` lines hits `lines`."""
    length = max(1, min(length, total))
    positions = total - length + 1
    hits = sum(1 for start in range(1, positions + 1)
               if any(start <= line < start + length for line in lines))
    return hits / positions


def git(repo, *args):
    result = subprocess.run(['git', '-C', str(repo), *args], capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


class Regions:
    def __init__(self, repos):
        self.repos = repos
        self.cache = {}

    def get(self, case, path):
        key = (case['id'], path)
        if key not in self.cache:
            self.cache[key] = self.compute(case, path)
        return self.cache[key]

    def compute(self, case, path):
        repo = self.repos / case['repo']
        fix, head = case['commit'], case['head']
        diff = git(repo, 'diff', '-U0', '--no-renames', f'{fix}^', fix, '--', path)
        if not diff:
            return {'status': 'no-fix-hunk'}
        lines = fixed_lines(parse_hunks(diff))
        forward = git(repo, 'diff', '-U0', '--no-renames', fix, head, '--', path)
        if forward is None:
            return {'status': 'git-error'}
        if 'deleted file mode' in forward:
            return {'status': 'deleted-at-head'}
        mapped = map_region(lines, parse_hunks(forward))
        if not mapped:
            return {'status': 'changed-since-fix'}
        blob = git(repo, 'show', f'{head}:{path}')
        if blob is None:
            return {'status': 'git-error'}
        return {'status': 'mapped', 'lines': sorted(mapped), 'total': max(1, len(blob.splitlines()))}


def citations(run_dir, cases):
    """Yield (arm, case, path, start, end) once per distinct citation per arm."""
    seen = set()
    for raw in sorted((run_dir / 'logs').glob('*.ygg-raw.jsonl')):
        case_id, arm = raw.name.split('.')[:2]
        case = cases.get(case_id)
        if case is None:
            continue
        expected = set(case['expectedPaths'])
        for line in raw.read_text(errors='replace').splitlines():
            try:
                envelope = json.loads(line)
            except ValueError:
                continue
            if not isinstance(envelope, dict) or envelope.get('ok') is not True:
                continue
            for record in (envelope.get('data') or {}).get('records') or []:
                if record.get('path') not in expected:
                    continue
                key = (arm, case_id, record['path'], record['startLine'], record['endLine'])
                if key not in seen:
                    seen.add(key)
                    yield arm, case, record['path'], record['startLine'], record['endLine']


def measure(run_dirs, cases, repos):
    regions = Regions(repos)
    rows = []
    for run_dir in run_dirs:
        for arm, case, path, start, end in citations(run_dir, cases):
            region = regions.get(case, path)
            row = {'run': run_dir.name, 'arm': arm, 'case': case['id'], 'path': path,
                   'start': start, 'end': end, 'status': region['status']}
            if region['status'] == 'mapped':
                lines = region['lines']
                row['distance'] = window_distance(start, end, lines)
                row['overlap'] = row['distance'] == 0
                row['chance'] = chance(end - start + 1, region['total'], lines)
            rows.append(row)
    return rows


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(row['run'], row['arm'])].append(row)
    summary = {}
    for (run, arm), group in sorted(groups.items()):
        certain = [row for row in group if row['status'] == 'mapped' and row['chance'] >= 1]
        mapped = [row for row in group if row['status'] == 'mapped' and row['chance'] < 1]
        unmapped = defaultdict(int)
        for row in group:
            if row['status'] != 'mapped':
                unmapped[row['status']] += 1
        misses = sorted(row['distance'] for row in mapped if not row['overlap'])
        cases = defaultdict(bool)
        for row in mapped:
            cases[row['case']] |= row['overlap']
        summary[f'{run}/{arm}'] = {
            'citations': len(group), 'unmappable': dict(unmapped), 'certain': len(certain),
            'mapped': len(mapped),
            'overlap': sum(row['overlap'] for row in mapped),
            'chance': round(sum(row['chance'] for row in mapped), 1),
            'missDistanceMedian': misses[len(misses) // 2] if misses else None,
            'missWithin10': sum(distance <= 10 for distance in misses),
            'cases': len(cases), 'casesWithOverlap': sum(cases.values()),
        }
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, action='append', required=True, help='run.py output directory')
    parser.add_argument('--suite', type=Path, required=True, help='case list with commit and head')
    parser.add_argument('--repos', type=Path, required=True, help='directory of clones named by case repo')
    parser.add_argument('--rows', type=Path, help='write per-citation rows as JSON lines')
    args = parser.parse_args()
    cases = {case['id']: case for case in json.loads(args.suite.read_text())}
    rows = measure(args.run, cases, args.repos)
    if args.rows:
        args.rows.write_text(''.join(json.dumps(row) + '\n' for row in rows))
    json.dump(summarize(rows), sys.stdout, indent=1)
    print()


if __name__ == '__main__':
    main()
