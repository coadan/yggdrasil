#!/usr/bin/env python3
"""Measure whether ygg citations of expected files touch the fixed lines.

Reads the raw ygg results that run.py saved (`logs/*.ygg-raw.jsonl`). For every
record that cites an expected path, the fix commit's new-side hunks are mapped
onto the blob the agent searched: the case HEAD. A line maps only when no
change between the fix and HEAD touched it, and a region maps only when every
line does; unmappable regions are counted apart, never as overlap or miss.
File rank is deliberately not part of this report.

Two windows are measured. The declared range is the record's startLine to
endLine. The displayed range is what the agent saw: the excerpt aligned line by
line to the HEAD blob within the declared range. An excerpt is a trimmed
character window: its first line is the tail of a blob line and a truncated
last line is a prefix. An excerpt with no alignment, or more than one, is
counted as ambiguous. A pure deletion has no
new-side line; the line before it stands in as a proxy for nearby context.

The baseline is the chance that a window of the same length, placed uniformly
in the HEAD file, intersects the mapped region. Citations certain to overlap
(a new file, or a window covering the file) are counted apart as `certain`.
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


def displayed_lines(excerpt, blob, start, end):
    """(first, last) blob lines the excerpt shows, or None unless it aligns uniquely.

    An excerpt is a trimmed character window inside the declared range, which
    bounds both its first and last line: its
    first line is the tail of a blob line, later lines match whole, and a
    truncated last line (ending in an ellipsis) is a prefix of its blob line.
    """
    if not excerpt:
        return None
    truncated = excerpt.endswith('\u2026')
    lines = [line.rstrip('\r') for line in (excerpt[:-1] if truncated else excerpt).split('\n')]
    if not lines[0].strip():
        return None
    matches = []
    for first in range(max(start - 1, 0), min(end, len(blob))):
        if first + len(lines) > min(end, len(blob)):
            break
        aligned = True
        for index, got in enumerate(lines):
            want = blob[first + index].rstrip('\r')
            if index == 0:
                ok = want.endswith(got) or (truncated and len(lines) == 1 and got in want)
            elif index == len(lines) - 1 and truncated:
                ok = want.startswith(got)
            else:
                ok = want == got
            if not ok:
                aligned = False
                break
        if aligned:
            matches.append(first)
    if len(matches) != 1:
        return None
    return matches[0] + 1, matches[0] + len(lines)


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
        blob_lines = blob.split('\n')
        if blob_lines and blob_lines[-1] == '':
            blob_lines.pop()
        return {'status': 'mapped', 'lines': sorted(mapped), 'blob': blob_lines, 'total': max(1, len(blob_lines))}


def citations(run_dir, cases):
    """Yield (arm, case, path, start, end, excerpt) once per distinct record per arm.

    Records with the same declared range but different excerpts stay distinct,
    because they display different lines.
    """
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
                key = (arm, case_id, record['path'], record['startLine'], record['endLine'],
                       record.get('excerpt'))
                if key not in seen:
                    seen.add(key)
                    yield arm, case, record['path'], record['startLine'], record['endLine'], record.get('excerpt')


def measure(run_dirs, cases, repos):
    regions = Regions(repos)
    rows = []
    for run_dir in run_dirs:
        for arm, case, path, start, end, excerpt in citations(run_dir, cases):
            region = regions.get(case, path)
            row = {'run': run_dir.name, 'arm': arm, 'case': case['id'], 'path': path,
                   'start': start, 'end': end, 'status': region['status']}
            if region['status'] == 'mapped':
                lines = region['lines']
                row['distance'] = window_distance(start, end, lines)
                row['overlap'] = row['distance'] == 0
                row['chance'] = chance(end - start + 1, region['total'], lines)
                shown = displayed_lines(excerpt, region['blob'], start, end)
                row['shownLines'] = shown
                if shown is not None:
                    row['shownDistance'] = window_distance(shown[0], shown[1], lines)
                    row['shownOverlap'] = row['shownDistance'] == 0
                    row['shownChance'] = chance(shown[1] - shown[0] + 1, region['total'], lines)
            rows.append(row)
    return rows


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(row['run'], row['arm'])].append(row)
    summary = {}
    for (run, arm), group in sorted(groups.items()):
        # Declared counts are per declared range; displayed counts per excerpt.
        ranges = list({(row['case'], row['path'], row['start'], row['end']): row for row in group}.values())
        certain = [row for row in ranges if row['status'] == 'mapped' and row['chance'] >= 1]
        mapped = [row for row in ranges if row['status'] == 'mapped' and row['chance'] < 1]
        unmapped = defaultdict(int)
        for row in ranges:
            if row['status'] != 'mapped':
                unmapped[row['status']] += 1
        misses = sorted(row['distance'] for row in mapped if not row['overlap'])
        cases = defaultdict(bool)
        for row in mapped:
            cases[row['case']] |= row['overlap']
        mapped_all = [row for row in group if row['status'] == 'mapped']
        shown = [row for row in mapped_all if row['shownLines'] is not None and row['shownChance'] < 1]
        summary[f'{run}/{arm}'] = {
            'citations': len(ranges), 'excerpts': len(group), 'unmappable': dict(unmapped),
            'certain': len(certain),
            'mapped': len(mapped),
            'declaredOverlap': sum(row['overlap'] for row in mapped),
            'declaredChance': round(sum(row['chance'] for row in mapped), 1),
            'shownAmbiguous': sum(row['shownLines'] is None for row in mapped_all),
            'shownCertain': sum(row['shownLines'] is not None and row['shownChance'] >= 1 for row in mapped_all),
            'shown': len(shown),
            'shownOverlap': sum(row['shownOverlap'] for row in shown),
            'shownChance': round(sum(row['shownChance'] for row in shown), 1),
            'declaredMissMedian': misses[len(misses) // 2] if misses else None,
            'declaredMissWithin10': sum(distance <= 10 for distance in misses),
            'shownMissWithin10': sum(not row['shownOverlap'] and row['shownDistance'] <= 10 for row in shown),
            'cases': len(cases), 'casesWithDeclaredOverlap': sum(cases.values()),
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
