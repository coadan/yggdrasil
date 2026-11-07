#!/usr/bin/env python3
"""Replay the searches agents made with native tools through ygg search.

Reads results.json from run.py. Successful, unfiltered native Grep file-list
calls are replayed as `ygg search -E <pattern>` in lexical mode. The report
pairs those calls, distinguishes excerpt hits from visible overflow, and
counts failed and unmatched calls separately. Glob calls are only counted.
Grep and ygg can still differ in regexp dialect and file discovery.
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import subprocess
import sys


def ygg_rank(binary, root, pattern, expected, env, limit):
    try:
        result = subprocess.run(
            [binary, 'search', '--root', str(root), '--mode', 'lexical', '-E', '--limit', str(limit), '--', pattern],
            capture_output=True, text=True, env=env, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'error': str(error)}
    try:
        envelope = json.loads(result.stdout)
    except json.JSONDecodeError:
        return {'error': result.stderr[-200:] or 'no JSON', 'returncode': result.returncode}
    if not isinstance(envelope, dict) or result.returncode or envelope.get('ok') is not True:
        error = envelope.get('error') if isinstance(envelope, dict) else None
        return {'error': error or 'unsuccessful ygg response', 'returncode': result.returncode}
    data = envelope.get('data')
    if not isinstance(data, dict) or 'records' not in data:
        return {'error': 'missing ygg search records'}
    excerpts = list(dict.fromkeys(record['path'] for record in data['records'] or []))
    paths = list(dict.fromkeys(excerpts + (data.get('morePaths') or [])))
    rank = next((index for index, path in enumerate(paths, 1) if path in expected), None)
    excerpt_rank = rank if rank is not None and rank <= len(excerpts) else None
    return {'rank': rank, 'paths': len(paths), 'excerptRank': excerpt_rank,
            'hitAtLimit': excerpt_rank is not None and excerpt_rank <= limit,
            'overflowHit': rank is not None and excerpt_rank is None}


def native_outcome(result, root, expected):
    """Match whole paths in a successful Grep file list."""
    if result is None:
        return {'status': 'missing', 'hit': None, 'lines': None}
    if result.get('is_error'):
        return {'status': 'error', 'hit': None, 'lines': None}
    content = result.get('content')
    if isinstance(content, list) and all(isinstance(block, dict) and isinstance(block.get('text'), str)
                                         for block in content):
        content = '\n'.join(block['text'] for block in content)
    if not isinstance(content, str):
        return {'status': 'unrecognized', 'hit': None, 'lines': None}
    lines = [line for line in content.splitlines() if line.strip()]
    path_forms = {form for path in expected for form in (path, './' + path, str(root / path))}
    return {'status': 'ok', 'hit': any(line in path_forms for line in lines), 'lines': len(lines)}


def trajectory(log):
    calls, results = [], {}
    for line in Path(log).read_text().splitlines():
        event = json.loads(line)
        content = (event.get('message') or {}).get('content')
        if not isinstance(content, list):
            continue
        if event.get('type') == 'assistant':
            calls.extend(item for item in content if item.get('type') == 'tool_use')
        elif event.get('type') == 'user':
            results.update((item['tool_use_id'], item) for item in content if item.get('type') == 'tool_result')
    return calls, results


def unmatched_reason(request, root):
    pattern = request.get('pattern')
    if not isinstance(pattern, str) or not pattern.strip() or pattern != pattern.strip():
        return 'empty pattern or significant outer whitespace'
    if request.get('output_mode', 'files_with_matches') != 'files_with_matches':
        return 'native output is not a file list'
    scope = request.get('path')
    if scope and (root / scope).resolve() != root:
        return 'native search scope differs from repository root'
    unsupported = sorted(set(request) - {'pattern', 'path', 'output_mode'})
    if unsupported:
        return 'unsupported native options: ' + ', '.join(unsupported)
    return None


def summarize(replayed, limit):
    greps = [entry for entry in replayed if entry['tool'] == 'Grep']
    dispositions = Counter(entry['status'] for entry in greps)
    paired = [entry for entry in greps if entry['status'] == 'paired']
    print(f'{len(replayed)} searches, {len(greps)} Grep, {len(paired)} paired')
    for status, count in sorted(dispositions.items()):
        if status != 'paired':
            print(f'  {status}: {count}')
    native_hits = sum(entry['native']['hit'] for entry in paired)
    top_hits = sum(entry['ygg']['hitAtLimit'] for entry in paired)
    overflow = sum(entry['ygg']['overflowHit'] for entry in paired)
    print(f'  paired native file hits {native_hits}, ygg top {limit} hits {top_hits}, overflow-only hits {overflow}')
    by_round, first = {}, {}
    for entry in paired:
        round_number = entry['round']
        bucket = by_round.setdefault(min(round_number, 4), [0, 0, 0])
        bucket[0] += 1
        bucket[1] += entry['native']['hit']
        bucket[2] += entry['ygg']['hitAtLimit']
        case = first.setdefault(entry['case'], {})
        if entry['native']['hit']:
            case.setdefault('native', round_number)
        if entry['ygg']['hitAtLimit']:
            case.setdefault('ygg', round_number)
    for round_number, (count, native, ygg) in sorted(by_round.items()):
        label = f'{round_number}' if round_number < 4 else '4+'
        print(f'  round {label}: {count} paired Grep calls, native hits {native}, ygg top {limit} hits {ygg}')
    earlier = sum('ygg' in case and ('native' not in case or case['ygg'] < case['native'])
                  for case in first.values())
    print(f'  cases with an earlier ygg top-{limit} hit among paired calls: {earlier}/{len(first)}')


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
        calls, results = trajectory(log)
        for call in calls:
            if call['name'] not in ('Grep', 'Glob'):
                continue
            search_round += 1
            request = call.get('input', {})
            entry = {'case': row['case'], 'round': search_round, 'tool': call['name'], 'input': request}
            if call['name'] == 'Glob':
                entry['status'] = 'glob'
            elif reason := unmatched_reason(request, root):
                entry.update(status='unmatched', reason=reason)
            else:
                native = entry['native'] = native_outcome(results.get(call['id']), root, expected)
                if native['status'] != 'ok':
                    entry['status'] = 'native_' + native['status']
                else:
                    ygg = entry['ygg'] = ygg_rank(args.ygg_binary, root, request['pattern'], expected, env, args.limit)
                    entry['status'] = 'ygg_error' if 'error' in ygg else 'paired'
            replayed.append(entry)
    (args.run / 'replay.json').write_text(json.dumps(replayed, indent=1))
    summarize(replayed, args.limit)
    return 0


if __name__ == '__main__':
    sys.exit(main())
