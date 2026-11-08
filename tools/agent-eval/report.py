#!/usr/bin/env python3
"""Compare arms in a run.py output directory.

Prints, per arm, final-answer quality and cost; paired differences between
arms on the same cases; per repository hits; and, per search round, how often
a successful search visibly named an expected path. Also shows whether an
expected path was subsequently read and named in the answer.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

from run_identity import observed_searches

# Bash programs that count as a search call; cat, sed and head read files.
SEARCH_PROGRAMS = {'ygg', 'rg', 'grep', 'egrep', 'fgrep', 'find', 'ls', 'fd'}
YGG_HEADER = re.compile(r'\d+ results \([a-z-]+\)')
YGG_RANGE = re.compile(r'(.+):\d+-\d+(?:,\d+-\d+)*')
GREP_CONTENT = re.compile(r'(.+?)(?::\d+:|-\d+-)')
GREP_COUNT = re.compile(r'(.+):\d+')


def normalized_path(value, root):
    if not isinstance(value, str) or not value:
        return None
    path = Path(value)
    if '..' in path.parts:
        return None
    if path.is_absolute():
        if root is None or not path.is_relative_to(root):
            return None
        path = path.relative_to(root)
    return path.as_posix()


def result_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list) and all(isinstance(block, dict) and isinstance(block.get('text'), str)
                                       for block in value):
        return '\n'.join(block['text'] for block in value)
    return None


def ygg_paths(text):
    """Return cited paths, the output region shared by every wrapper format."""
    paths = []
    if text.lstrip().startswith('{'):
        # A chained call can return multiple JSON envelopes.
        remaining = text.lstrip()
        decoder = json.JSONDecoder()
        while remaining:
            try:
                envelope, end = decoder.raw_decode(remaining)
            except json.JSONDecodeError:
                return None
            if not isinstance(envelope, dict) or envelope.get('schema') != 'ygg.cli/v1':
                return None
            if envelope.get('ok'):
                data = envelope.get('data')
                if not isinstance(data, dict) or 'records' not in data:
                    return None
                records = data['records'] or []
                if not isinstance(records, list):
                    return None
                if any(not isinstance(record, dict) or not isinstance(record.get('path'), str) for record in records):
                    return None
                paths.extend(record['path'] for record in records)
            remaining = remaining[end:].lstrip()
        return paths

    lines = text.splitlines()
    if not lines or not YGG_HEADER.fullmatch(lines[0]):
        return None
    for line in lines[1:]:
        # Range headers are unambiguous. The comma-delimited overflow line
        # cannot distinguish a delimiter from a comma in a filename.
        if not line or line[0].isspace() or line.startswith('more: ') or YGG_HEADER.fullmatch(line):
            continue
        match = YGG_RANGE.fullmatch(line)
        if not match:
            return None
        paths.append(match[1])
    return paths


def search_kind(use):
    name = use['name']
    if name in ('Grep', 'Glob'):
        return name
    if name == 'Bash':
        words = use.get('input', {}).get('command', '').split(maxsplit=1)
        if words and (program := Path(words[0]).name) in SEARCH_PROGRAMS:
            return program
    return None


def search_paths(kind, request, text):
    if text is None:
        return None
    if kind == 'ygg':
        return ygg_paths(text)
    if kind == 'Glob' or (kind == 'Grep' and request.get('output_mode', 'files_with_matches') == 'files_with_matches'):
        return text.splitlines()
    if kind == 'Grep' and request.get('output_mode') == 'count':
        return [match[1] for line in text.splitlines() if (match := GREP_COUNT.fullmatch(line))]
    if kind == 'Grep' and request.get('output_mode') == 'content' and request.get('-n', True):
        return [match[1] for line in text.splitlines() if (match := GREP_CONTENT.match(line))]
    return None


def inspect_trajectory(log, expected):
    """Measure evidence in event order so parallel reads cannot follow unseen results."""
    root, completed = None, False
    uses, search_hits = {}, {}
    shown, read, read_after_shown = set(), set(), set()
    try:
        stream = Path(log).open()
    except FileNotFoundError:
        return {'searches': [], 'shown': shown, 'read': read, 'readAfterShown': read_after_shown,
                'missingLog': True, 'completed': False}
    with stream:
        for line in stream:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get('type') == 'system' and event.get('subtype') == 'init' and event.get('cwd'):
                root = Path(event['cwd'])
            if event.get('type') == 'result':
                output = event.get('structured_output')
                files = output.get('files') if isinstance(output, dict) else None
                completed = not event.get('is_error') and isinstance(files, list) and all(
                    isinstance(path, str) for path in files
                )
            content = (event.get('message') or {}).get('content')
            if not isinstance(content, list):
                continue
            for item in content:
                if item.get('type') == 'tool_use':
                    request = item.get('input', {})
                    target = normalized_path(request.get('file_path'), root) if item['name'] == 'Read' else None
                    uses[item['id']] = (search_kind(item), request, target, target in shown)
                if item.get('type') != 'tool_result' or item.get('is_error'):
                    continue
                call_id = item['tool_use_id']
                if call_id not in uses:
                    continue
                kind, request, target, was_shown = uses[call_id]
                if target in expected:
                    read.add(target)
                    if was_shown:
                        read_after_shown.add(target)
                if kind:
                    paths = search_paths(kind, request, result_text(item.get('content')))
                    hits = {normalized_path(path, root) for path in paths or []} & expected
                    shown.update(hits)
                    search_hits[call_id] = bool(hits) if paths is not None else None
    return {'searches': [search_hits[key] for key in uses if key in search_hits],
            'shown': shown, 'read': read, 'readAfterShown': read_after_shown,
            'missingLog': False, 'completed': completed}


def read_results(run):
    manifest_path = run / 'manifest.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.is_file() else None
    results_path = run / 'results.json'
    rows = json.loads(results_path.read_text()) if manifest is None or results_path.is_file() else []
    if not isinstance(rows, list):
        raise ValueError('results.json must contain a list of results')
    if manifest is not None:
        if not isinstance(manifest, dict) or manifest.get('schema') != 1:
            raise ValueError('unsupported run manifest schema')
        arms = manifest['arms']
        expected = {case['id']: case for case in manifest['cases']}
        if (not isinstance(arms, list) or not arms or len(set(arms)) != len(arms) or
                not expected or len(expected) != len(manifest['cases'])):
            raise ValueError('run manifest needs unique arms and cases')
        by_case = {case: {} for case in expected}
    else:
        arms = sorted({row['arm'] for row in rows})
        by_case = {}
    for row in rows:
        case, arm = row['case'], row['arm']
        if manifest is not None:
            if case not in expected or arm not in arms:
                raise ValueError(f'{case} {arm} is outside the manifest')
            if any(row[key] != expected[case][key] for key in ('repo', 'expectedPaths')):
                raise ValueError(f'{case} {arm} has different repository or expected paths than the manifest')
            model = manifest['models']['codex_model' if arm.startswith('codex') else 'model']
            if row.get('model') not in (None, model):
                raise ValueError(f'{case} {arm} has a different model than the manifest')
        runs = by_case.setdefault(case, {})
        if arm in runs:
            raise ValueError(f'duplicate result for {case} {arm}')
        if row.get('isError') or row.get('timedOut') or row.get('dirty'):
            row = dict(row, hit=False, recall=0, precision=0, firstHitRank=None, answer=[])
        runs[arm] = row
    return manifest, arms, by_case


def report_coverage(manifest, arms, by_case, evidence):
    complete = manifest is not None
    if manifest is None:
        print('Legacy run. Expected coverage is inferred from saved rows.')
    else:
        print(f"Manifest: {manifest['split']} {manifest['style']}, {len(by_case)} cases, "
              f"{manifest['limits']['tool_budget']} requested tool calls per run")
        if ygg := manifest.get('ygg'):
            print(f"  requested Ygg format {ygg['format']}, binary SHA-256 {ygg['binary']['sha256'][:12]}")
        if guard := manifest['sources'].get('guard.py'):
            print(f'  Bash guard SHA-256 {guard[:12]}')
    print('Run coverage. Recorded failures count as misses in the paired table.')
    for arm in arms:
        group = [runs[arm] for runs in by_case.values() if arm in runs]
        missing = len(by_case) - len(group)
        timeouts = sum(bool(row.get('timedOut')) for row in group)
        errors = sum(bool(row.get('isError')) and not row.get('timedOut', False) for row in group)
        dirty = sum(bool(row.get('dirty')) for row in group)
        retries = sum(len(row.get('priorFailures', [])) for row in group)
        invalid_logs = sum(1 for row in group if not row.get('isError') and not row.get('timedOut') and
                           not evidence[row['case'], arm]['completed'])
        print(f'  {arm:9} {len(group)}/{len(by_case)} rows, {missing} missing, {errors} errors, '
              f'{timeouts} timeouts, {dirty} dirty checkouts, {retries} prior attempts, {invalid_logs} unverified success logs')
        complete &= not (missing or dirty or invalid_logs)
    return complete


def recorded_cost(group):
    attempts = [attempt.get('costUsd') for row in group for attempt in [row, *row.get('priorFailures', [])]]
    known = [cost for cost in attempts if cost is not None]
    if not known:
        return 'n/a'
    suffix = '+' if len(known) < len(attempts) else ''
    return f'${sum(known):.2f}{suffix}'


def report_ygg_conditions(run, arms, paired):
    for arm in arms:
        if arm not in ('ygg', 'both', 'shell-ygg', 'codex-ygg'):
            continue
        conditions = Counter()
        successes = errors = unreadable = uncaptured = 0
        for case in paired:
            observed = observed_searches(run / 'logs' / f'{case}.{arm}.jsonl')
            if observed is None:
                uncaptured += 1
                continue
            successes += observed['successful']
            errors += observed['errors']
            unreadable += observed['unreadable']
            for row in observed['conditions']:
                key = tuple(row[field] for field in ('requestedMode', 'activeMode', 'coverage', 'fallbackReason', 'rerankState'))
                conditions[key] += row['count']
        print(f'\n{arm} raw search results across paired runs. '
              f'{successes} successful, {errors} errors, {unreadable} unreadable, {uncaptured} runs without raw capture.')
        for (requested, active, coverage, fallback, rerank), count in sorted(conditions.items(), key=repr):
            print(f'  {count:4} requested={requested} active={active} coverage={coverage} '
                  f'fallback={fallback or "-"} rerank={rerank or "-"}')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--rounds', type=int, default=4, help='Last search round reported on its own')
    parser.add_argument('--require-complete', action='store_true',
                        help='Require a manifest, every planned result, and logs for successful runs')
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error('--rounds must be positive')
    try:
        manifest, arms, by_case = read_results(args.run)
    except (KeyError, TypeError, ValueError, OSError) as error:
        parser.exit(2, f'cannot read comparison: {error}\n')
    evidence = {(case, arm): inspect_trajectory(args.run / 'logs' / f'{case}.{arm}.jsonl',
                                               set(row['expectedPaths']))
                for case, runs in by_case.items() for arm, row in runs.items()}
    complete = report_coverage(manifest, arms, by_case, evidence)
    status = 0
    if args.require_complete and not complete:
        print('Comparison does not meet strict coverage requirements.', file=sys.stderr)
        status = 1
    paired = {case: runs for case, runs in by_case.items() if all(arm in runs for arm in arms)}
    print(f'\n{len(paired)} cases with every arm ({", ".join(arms)})\n')
    if not paired:
        return status

    print('arm      hit      recall  precision  first@1  shown   calls  input-tokens  cost    seconds')
    print('Cost includes recorded retries. A plus sign marks attempts with missing cost.')
    for arm in arms:
        group = [runs[arm] for runs in paired.values()]
        n = len(group)
        mean = lambda key: sum(row[key] or 0 for row in group) / n
        print(f"{arm:8} {sum(r['hit'] for r in group):3}/{n:<4} {mean('recall'):.3f}   {mean('precision'):.3f}      "
              f"{sum(r['firstHitRank'] == 1 for r in group):3}      "
              f"{sum(bool(evidence[case, arm]['shown']) for case in paired):3}      "
              f"{mean('toolCalls'):4.1f}   {mean('inputTokens') / 1000:6.1f}k      "
              f"{recorded_cost(group):>6}  {mean('durationMs') / 1000:5.1f}")

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

    print('\nverified expected path exposure and later successful Read calls, by case')
    print('  Ygg exposure uses cited paths in every format. Overflow and shell file reads are outside this measure.')
    for arm in arms:
        shown = read = read_later = named_after_read = shown_not_named = missing = 0
        for case, runs in paired.items():
            trace = evidence[case, arm]
            answer = set(runs[arm]['answer'])
            shown += bool(trace['shown'])
            read += bool(trace['read'])
            read_later += bool(trace['readAfterShown'])
            named_after_read += bool(trace['readAfterShown'] & answer)
            shown_not_named += bool(trace['shown'] and not trace['shown'] & answer)
            missing += trace['missingLog']
        print(f'  {arm:8} shown {shown}, read {read}, read after shown {read_later}, '
              f'then named {named_after_read}, shown without naming {shown_not_named}, missing logs {missing}')

    print('\nsuccessful search calls with verified paths, by round (hits/classified, unknown separately)')
    for arm in arms:
        buckets = {}
        for case in paired:
            for number, hit in enumerate(evidence[case, arm]['searches'], 1):
                bucket = buckets.setdefault(min(number, args.rounds), [0, 0, 0])
                bucket[0] += hit is True
                bucket[1] += hit is not None
                bucket[2] += hit is None
        cells = '  '.join(f"{k if k < args.rounds else f'{k}+'}: {hit}/{count}, {unknown} unknown"
                          for k, (hit, count, unknown) in sorted(buckets.items()))
        print(f'  {arm:8} {cells}')
    report_ygg_conditions(args.run, arms, paired)
    return status


if __name__ == '__main__':
    sys.exit(main())
