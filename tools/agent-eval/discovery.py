#!/usr/bin/env python3
"""Report when patch-eval agents first saw, read and edited the fixed files.

Reads only the run traces (`runs.jsonl` and the `<case>.<arm>.jsonl` event
logs beside it) and the patch suite; it never reads grader scores. The labels
for a case are the paths changed between its pinned `base` and `reference`
commits. For each run, commands are numbered from 1 in execution order and
three indices are reported per run, each the first command that:

- exposure: printed a label in its output (needs the event log);
- read: named a label in its command text without writing it;
- editAttempt: tried to write a label (`sed -i`, `perl -i`, a redirect, `tee`,
  `apply_patch`, or a Python `open(label, 'w'|'a')`);
- edit: such a command that exited 0 (needs the event log).

Matching is textual: a label counts when it appears with a path boundary on
both sides, so `./lib/a.js`, `/abs/repo/lib/a.js` and `lib/a.js:12:` match
`lib/a.js` while `lib/a.jsx` does not. A command that reaches a label through a
`cd` into its directory, a glob or a variable is not counted. Write detection
is a fixed pattern list; a write it does not recognise counts as a read.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys

BEFORE = r'''(?:^|(?<=[\s'"=(/:,]))'''
AFTER = r'''(?=$|[\s'"):;,|&>\\])'''
IN_PLACE = re.compile(r'''(?:^|[\s;&|(])(?:sed|perl)\s+(?:-\S+\s+)*-[a-zA-Z]*i''')


def mention(label):
    return re.compile(BEFORE + r'(?:\./)?' + re.escape(label) + AFTER)


def mentions(text, label):
    return bool(mention(label).search(text or ''))


def writes(command, label):
    if not mentions(command, label):
        return False
    path = r'''['"]?(?:\S*/)?(?:\./)?''' + re.escape(label) + AFTER
    if IN_PLACE.search(command) or 'apply_patch' in command:
        return True
    if re.search(r'(?:>>?|\btee(?:\s+-a)?)\s*' + path, command):
        return True
    return bool(re.search(r'''open\(\s*['"](?:\S*/)?(?:\./)?''' + re.escape(label) +
                          r'''['"]\s*,\s*['"][wa]''', command))


def first(steps, predicate):
    for index, step in enumerate(steps, 1):
        if predicate(step):
            return index
    return None


def trace(commands, outputs, exits, labels):
    steps = list(zip(commands, outputs, exits))
    return {
        'commands': len(commands),
        'exposure': None if any(output is None for output in outputs) else
        first(steps, lambda step: any(mentions(step[1], label) for label in labels)),
        'read': first(steps, lambda step: any(mentions(step[0], label) and not writes(step[0], label)
                                              for label in labels)),
        'editAttempt': first(steps, lambda step: any(writes(step[0], label) for label in labels)),
        'edit': None if any(code is None for code in exits) else
        first(steps, lambda step: step[2] == 0 and any(writes(step[0], label) for label in labels)),
    }


def labels(repo, base, reference):
    result = subprocess.run(['git', '-C', str(repo), 'diff', '--name-only', '--no-renames', base, reference],
                            capture_output=True, text=True, check=True)
    return sorted(line for line in result.stdout.splitlines() if line)


def events(path):
    commands, outputs, exits = [], [], []
    for line in path.read_text().splitlines():
        event = json.loads(line)
        item = event.get('item') or {}
        if event.get('type') == 'item.completed' and item.get('type') == 'command_execution':
            commands.append(item.get('command', ''))
            outputs.append(item.get('aggregated_output', ''))
            exits.append(item.get('exit_code'))
    return commands, outputs, exits


def measure(runs, suite):
    entries = json.loads(suite.read_text())
    cases = {case['id']: case for case in entries.get('cases', entries) if 'id' in case}
    cache, rows = {}, []
    for path in runs:
        for line in path.read_text().splitlines():
            run = json.loads(line)
            case = cases[run['case']]
            key = case['id']
            if key not in cache:
                cache[key] = labels((suite.parent / case['repo']).resolve(), case['base'], case['reference'])
            log = path.parent / f"{run['case']}.{run['arm']}.jsonl"
            commands = run.get('commands', [])
            outputs, exits = [None] * len(commands), [None] * len(commands)
            if log.exists():
                logged, logged_outputs, logged_exits = events(log)
                if logged == commands:
                    outputs, exits = logged_outputs, logged_exits
            row = {'run': str(path), 'case': run['case'], 'arm': run['arm'], 'labels': cache[key],
                   'timedOut': run.get('timedOut'), 'outputsLogged': None not in outputs or not commands}
            row.update(trace(commands, outputs, exits, cache[key]))
            rows.append(row)
    return rows


def summarize(rows):
    arms = defaultdict(list)
    for row in rows:
        arms[row['arm']].append(row)
    summary = {}
    for arm, group in sorted(arms.items()):
        entry = {'runs': len(group)}
        for field in ('exposure', 'read', 'editAttempt', 'edit'):
            values = [row[field] for row in group if row[field] is not None]
            entry[field] = len(values)
            entry[field + 'Median'] = statistics.median(values) if values else None
        entry['exposureUnknown'] = sum(1 for row in group if not row['outputsLogged'])
        summary[arm] = entry
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, action='append', required=True, help='runs.jsonl from a patch run')
    parser.add_argument('--suite', type=Path, required=True, help='patch suite with base and reference')
    parser.add_argument('--rows', type=Path, help='write per-run rows as JSON lines')
    args = parser.parse_args()
    rows = measure(args.run, args.suite)
    if args.rows:
        args.rows.write_text(''.join(json.dumps(row) + '\n' for row in rows))
    json.dump({'rows': rows if not args.rows else len(rows), 'arms': summarize(rows)}, sys.stdout, indent=1)
    print()


if __name__ == '__main__':
    main()
