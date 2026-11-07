#!/usr/bin/env python3
"""Replay the searches agents made with native tools through ygg search.

Reads results.json from run.py. Each Grep call in a native-arm trajectory is
replayed as `ygg search -E <pattern>`, in lexical mode, against the same
repository and index. For each call this records whether the agent's own
tool result contained an expected path and how many paths it listed, next to
the rank of the first expected path in ygg's records and morePaths. Glob calls
name files rather than content and are only counted.

The output shows, per search round, where ygg would have surfaced the answer
earlier than the agent's own search, or with fewer candidates.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def ygg_rank(binary, root, pattern, expected, env, limit):
    result = subprocess.run(
        [binary, 'search', '--root', str(root), '--mode', 'lexical', '-E', '--limit', str(limit), pattern],
        capture_output=True, text=True, env=env, timeout=120)
    try:
        data = json.loads(result.stdout).get('data') or {}
    except json.JSONDecodeError:
        return {'error': result.stderr[-200:] or 'no JSON'}
    paths = []
    for record in data.get('records') or []:
        if record['path'] not in paths:
            paths.append(record['path'])
    paths += [path for path in data.get('morePaths') or [] if path not in paths]
    rank = next((index for index, path in enumerate(paths, 1) if path in expected), None)
    return {'rank': rank, 'paths': len(paths)}


def native_outcome(log, call_id, expected):
    """Whether the agent's own tool result for this call named an expected path."""
    for line in Path(log).read_text().splitlines():
        event = json.loads(line)
        content = (event.get('message') or {}).get('content')
        if event.get('type') != 'user' or not isinstance(content, list):
            continue
        for item in content:
            if item.get('type') == 'tool_result' and item.get('tool_use_id') == call_id:
                text = item.get('content')
                text = text if isinstance(text, str) else json.dumps(text)
                lines = [line for line in text.splitlines() if line.strip()]
                return {'hit': any(path in text for path in expected), 'lines': len(lines)}
    return {'hit': None, 'lines': None}


def calls(log):
    for line in Path(log).read_text().splitlines():
        event = json.loads(line)
        content = (event.get('message') or {}).get('content')
        if event.get('type') == 'assistant' and isinstance(content, list):
            for item in content:
                if item.get('type') == 'tool_use':
                    yield item


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, required=True, help='run.py output directory')
    parser.add_argument('--suite', type=Path, required=True)
    parser.add_argument('--ygg-binary', required=True)
    parser.add_argument('--storage', type=Path, required=True)
    parser.add_argument('--ygg-config', type=Path)
    parser.add_argument('--limit', type=int, default=10)
    args = parser.parse_args()
    env = dict(os.environ, YGG_STORAGE_ROOT=str(args.storage))
    if args.ygg_config:
        env['YGG_CONFIG'] = str(args.ygg_config)
    rows = json.loads((args.run / 'results.json').read_text())
    replayed = []
    for row in rows:
        if row['arm'] != 'native':
            continue
        log = args.run / 'logs' / f"{row['case']}.native.jsonl"
        root = (args.suite.expanduser() / 'repos' / row['repo']).resolve()
        expected = row['expectedPaths']
        search_round = 0
        for call in calls(log):
            if call['name'] not in ('Grep', 'Glob'):
                continue
            search_round += 1
            entry = {'case': row['case'], 'round': search_round, 'tool': call['name'],
                     'input': call['input'], 'native': native_outcome(log, call['id'], expected)}
            pattern = call['input'].get('pattern', '')
            if call['name'] == 'Grep' and pattern:
                entry['ygg'] = ygg_rank(args.ygg_binary, root, pattern, expected, env, args.limit)
            replayed.append(entry)
    (args.run / 'replay.json').write_text(json.dumps(replayed, indent=1))

    greps = [entry for entry in replayed if 'ygg' in entry]
    native_hits = sum(1 for entry in greps if entry['native']['hit'])
    ygg_hits = sum(1 for entry in greps if entry['ygg'].get('rank'))
    print(f"{len(replayed)} searches, {len(greps)} Grep replayed: native result named an expected path "
          f"in {native_hits}, ygg top {args.limit} in {ygg_hits}")
    by_round = {}
    for entry in greps:
        bucket = by_round.setdefault(min(entry['round'], 4), [0, 0, 0])
        bucket[0] += 1
        bucket[1] += bool(entry['native']['hit'])
        bucket[2] += bool(entry['ygg'].get('rank'))
    for search_round, (count, native, ygg) in sorted(by_round.items()):
        label = f'{search_round}' if search_round < 4 else '4+'
        print(f'  round {label}: {count} greps, native hit {native}, ygg hit {ygg}')
    first = {}
    for entry in replayed:
        case = first.setdefault(entry['case'], {'native': None, 'ygg': None})
        if case['native'] is None and entry['native']['hit']:
            case['native'] = entry['round']
        if case['ygg'] is None and (entry.get('ygg') or {}).get('rank'):
            case['ygg'] = entry['round']
    earlier = sum(1 for case in first.values()
                  if case['ygg'] and (case['native'] is None or case['ygg'] < case['native']))
    print(f"cases where replaying through ygg finds an expected path in an earlier round: "
          f"{earlier}/{len(first)}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
