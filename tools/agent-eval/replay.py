#!/usr/bin/env python3
"""Replay the searches agents made with native tools through ygg search.

Reads results.json from run.py. Replays successful native Grep calls in lexical
mode with their file or directory scope and case option. The native side uses
what the agent received, including matches in a known single-file scope. The
report separates excerpt hits, visible overflow, errors and unmatched options.
Glob calls are only counted. Regexp dialect and file discovery can still differ.
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from report import normalized_path, result_text, search_paths


GREP_OPTIONS = {'pattern', 'path', '-i', 'output_mode', '-n', 'head_limit', 'offset', '-A', '-B', '-C', 'context'}
CONTENT_MATCH = re.compile(r'^\d+:')


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


def native_outcome(result, root, scope, request, expected):
    """Read displayed paths and explicit single-file matches from native results."""
    if result is None:
        return {'status': 'missing', 'hit': None, 'lines': None}
    if result.get('is_error'):
        return {'status': 'error', 'hit': None, 'lines': None}
    content = result_text(result.get('content'))
    paths = search_paths('Grep', request, content)
    if paths is None:
        return {'status': 'unrecognized', 'hit': None, 'lines': None}
    lines = [line for line in content.splitlines() if line.strip()]
    shown = any(normalized_path(path, root) in expected for path in paths)
    matched_scope = (scope.is_file() and scope.relative_to(root).as_posix() in expected
                     and request.get('output_mode') == 'content'
                     and any(CONTENT_MATCH.match(line) for line in lines))
    return {'status': 'ok', 'hit': shown or matched_scope, 'shown': shown,
            'matchedScope': matched_scope, 'lines': len(lines)}


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


def replay_parameters(request, root):
    pattern = request.get('pattern')
    if not isinstance(pattern, str) or not pattern.strip() or pattern != pattern.strip():
        raise ValueError('empty pattern or significant outer whitespace')
    unsupported = sorted(set(request) - GREP_OPTIONS)
    if unsupported:
        raise ValueError('unsupported native options: ' + ', '.join(unsupported))
    if request.get('output_mode', 'files_with_matches') not in ('files_with_matches', 'content', 'count'):
        raise ValueError('unrecognized native output mode')
    scope = (root / (request.get('path') or '.')).resolve()
    if not scope.is_relative_to(root) or not (scope.is_dir() or scope.is_file()):
        raise ValueError('native scope is missing or outside the repository')
    directory = scope.parent if scope.is_file() else scope
    for parent in (directory, *directory.parents):
        if parent == root:
            break
        if (parent / '.git').exists():
            raise ValueError('native scope is inside a nested repository')
    if request.get('-i'):
        pattern = '(?i)' + pattern
    return scope, pattern


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
            else:
                try:
                    scope, pattern = replay_parameters(request, root)
                except ValueError as error:
                    entry.update(status='unmatched', reason=str(error))
                else:
                    entry['scope'] = scope.relative_to(root).as_posix()
                    native = entry['native'] = native_outcome(results.get(call['id']), root, scope, request, expected)
                    if native['status'] != 'ok':
                        entry['status'] = 'native_' + native['status']
                    else:
                        ygg = entry['ygg'] = ygg_rank(args.ygg_binary, scope, pattern, expected, env, args.limit)
                        entry['status'] = 'ygg_error' if 'error' in ygg else 'paired'
            replayed.append(entry)
    (args.run / 'replay.json').write_text(json.dumps(replayed, indent=1))
    summarize(replayed, args.limit)
    return 0


if __name__ == '__main__':
    sys.exit(main())
