#!/usr/bin/env python3
"""Write an issue-style task for each history case from its commit.

A history case pairs a commit subject with the files that commit touched. Real
agents get fuller task descriptions than a one-line subject, so this asks a
model to write the issue the commit resolved, from the commit message and diff,
without naming files. Tasks are cached in <suite>/agent-tasks-<split>.json,
keyed by case id and prompt hash, so reruns only fill gaps.

The model runs through a headless `claude -p` with no tools, settings or MCP
servers. Every task is checked mechanically for the case's expected paths and
their file names; a leaking task is retried once and otherwise marked.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

MAX_DIFF_CHARS = 20_000

ISSUE = """You write issue reports for software repositories.

Given a commit, write the GitHub issue or feature request that this change
resolves, as a user or maintainer would have filed it before the change
existed. Describe the observable problem or the wanted capability, with enough
context for a developer who is new to the codebase.

Rules:
- Mention public API names, CLI commands or flags, configuration keys, or error
  messages only where a user of the software would see them.
- Never mention file names, directory names, paths, test names, or internal
  functions, classes, methods, or variables.
- Do not describe how to implement the fix.
- Two to six sentences of plain prose."""

SYMPTOM = """You write short bug reports and feature requests from users.

Given a commit, write what a user of the software would have reported before
the change existed: the behaviour they saw, or the capability they wanted, in
their own words. The user has never read the source code.

Rules:
- No names from the code: no file, directory, module, package, function,
  class, method, type, variable, test, or constant names, and no quoted code.
- Name a command, flag, option, or setting only if the user would type it
  themselves, and never one the change adds.
- Do not explain the cause or suggest a fix.
- One to three sentences of plain prose."""

STYLES = {'issue': ISSUE, 'symptom': SYMPTOM}

SCHEMA = json.dumps({
    "type": "object",
    "properties": {"task": {"type": "string"}},
    "required": ["task"],
    "additionalProperties": False,
})


def prompt_hash(model, style):
    return hashlib.sha256(f'{model}\n{STYLES[style]}\n{MAX_DIFF_CHARS}'.encode()).hexdigest()[:16]


def task_file(suite, split, style):
    """Issue tasks keep the original file name; other styles get a suffix."""
    return suite / (f'agent-tasks-{split}.json' if style == 'issue' else f'agent-tasks-{split}-{style}.json')


def commit_text(repo, commit):
    show = subprocess.run(
        ['git', '-C', str(repo), 'show', '--no-color', '--format=%B', '--stat', '--patch', commit],
        capture_output=True, text=True, errors='replace', check=True).stdout
    if len(show) > MAX_DIFF_CHARS:
        show = show[:MAX_DIFF_CHARS] + '\n[diff truncated]\n'
    return show


def leaks(task, paths):
    """Expected paths or file names (with extension) that appear in the task."""
    lowered = task.lower()
    found = [path for path in paths
             if path.lower() in lowered or path.rsplit('/', 1)[-1].lower() in lowered]
    if re.search(r'\b[\w.-]+/[\w./-]+\.\w{1,5}\b', task):
        found.append('<path-like token>')
    return found


def named_stems(task, paths):
    """Expected files whose name without extension appears verbatim, such as a
    public class named like its file. Recorded, not rejected: real issues name
    public API."""
    return [path for path in paths
            if re.search(r'(?<![\w])' + re.escape(path.rsplit('/', 1)[-1].rsplit('.', 1)[0])
                         + r'(?![\w])', task)]


def generate(repo, case, model, style):
    request = (f'Repository: {case["repo"]}\n\nCommit:\n\n' + commit_text(repo, case['commit']))
    result = subprocess.run(
        ['claude', '-p', '--model', model, '--tools', '', '--no-session-persistence',
         '--strict-mcp-config', '--setting-sources', '', '--disable-slash-commands',
         '--system-prompt', STYLES[style], '--output-format', 'json', '--json-schema', SCHEMA],
        input=request, capture_output=True, text=True, timeout=300)
    if result.returncode:
        raise RuntimeError(f'claude exited {result.returncode}: {result.stderr[-500:]}')
    data = json.loads(result.stdout)
    output = data.get('structured_output') or {}
    if data.get('is_error') or not output.get('task'):
        raise RuntimeError(f'no task: {str(data.get("result"))[:300]}')
    return output['task'].strip(), data.get('total_cost_usd') or 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--suite', required=True, help='Suite directory holding <split>.json and repos')
    parser.add_argument('--split', default='dev')
    parser.add_argument('--model', default='claude-haiku-5-5')
    parser.add_argument('--style', choices=sorted(STYLES), default='issue',
                        help='issue: a full issue report; symptom: a short user report with no code names')
    parser.add_argument('--cases', help='Comma-separated case ids to generate, instead of every case')
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--limit', type=int, help='Generate at most this many missing tasks')
    args = parser.parse_args()
    suite = Path(args.suite).expanduser()
    cases = json.loads((suite / f'{args.split}.json').read_text())
    if args.cases:
        wanted = set(args.cases.split(','))
        cases = [case for case in cases if case['id'] in wanted]
    output = task_file(suite, args.split, args.style)
    cache = json.loads(output.read_text()) if output.exists() else {}
    key = prompt_hash(args.model, args.style)
    todo = [case for case in cases if cache.get(case['id'], {}).get('promptHash') != key]
    if args.limit is not None:
        todo = todo[:args.limit]
    print(f'{len(todo)} of {len(cases)} tasks to generate', file=sys.stderr)

    def work(case):
        repo = suite / 'repos' / case['repo']
        cost = 0
        for _ in range(2):
            task, spent = generate(repo, case, args.model, args.style)
            cost += spent
            leaked = leaks(task, case['expectedPaths'])
            if args.style == 'symptom':  # a short user report should not name files at all
                leaked += named_stems(task, case['expectedPaths'])
            if not leaked:
                break
        return case['id'], {'task': task, 'leaks': leaked,
                            'namedStems': named_stems(task, case['expectedPaths']),
                            'model': args.model, 'style': args.style, 'promptHash': key, 'costUsd': round(cost, 6)}

    failures = 0
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        futures = {pool.submit(work, case): case['id'] for case in todo}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                case_id, entry = future.result()
            except Exception as error:  # keep the rest of the run
                failures += 1
                print(f'{futures[future]}: {error}', file=sys.stderr)
                continue
            cache[case_id] = entry
            if done % 10 == 0 or done == len(futures):
                output.write_text(json.dumps(cache, indent=1, sort_keys=True))
                print(f'{done}/{len(futures)}', file=sys.stderr)
    output.write_text(json.dumps(cache, indent=1, sort_keys=True))
    leaked = sum(1 for case in cases if cache.get(case['id'], {}).get('leaks'))
    print(f'{len(cache)} tasks cached, {leaked} leaking, {failures} failed', file=sys.stderr)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
