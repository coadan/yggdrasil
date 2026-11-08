#!/usr/bin/env python3
"""Run a headless agent on issue-style tasks and score the files it names.

Each case and arm runs one agent session with the repository clone as its
working directory. Claude arms use `claude -p` with no settings, MCP servers
or slash commands, and only the arm's tools; guard.py holds Bash to the arm's
commands:

  native     Read, Grep, Glob
  ygg        Read, and Bash limited to `ygg search` through a wrapper that
             pins the search root to the repository
  both       the union
  shell      Bash limited to read-only grep, find, ls, cat, sed -n and the like
  shell-ygg  the shell commands plus `ygg search`

Codex arms use `codex exec` with a clean CODEX_HOME that links only the user's
login, the workspace-write sandbox, and no zsh startup files except one that
keeps the ygg wrapper first on PATH:

  codex      Codex's own shell tools
  codex-ygg  the same, with AGENTS.md-style guidance to use `ygg search`

The agent answers with the files it would change. The full stream-json
trajectory is kept per case and arm, and results.json holds one row per run:
hit and recall against the commit's files, the tool call at which an expected
path first appeared in a tool result, tool calls by kind, turns, tokens, cost
and time. Completed runs are skipped on rerun.
"""
import argparse
import concurrent.futures
import json
import os
import re
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time

from run_identity import PromptInput, checked_output, observed_searches, run_manifest, write_json
from tasks import task_file

ANSWER_SCHEMA = json.dumps({
    'type': 'object',
    'properties': {'files': {'type': 'array', 'items': {'type': 'string'}}},
    'required': ['files'],
    'additionalProperties': False,
})

PROMPT = """You are in a checkout of the {repo} repository. Here is the task:

{task}

Do not change anything. Find the files you would need to change to resolve this
task. Answer with their repository-relative paths, most important first. Use at
most {budget} tool calls."""

# Release-bookkeeping files. A case whose expected paths are all of this kind
# comes from a commit that only recorded a change made elsewhere, so its task
# describes code the label does not name. Summaries report hits without them.
BOOKKEEPING = re.compile(r'(^|/)([^/]*changelog[^/]*|(changes|news|history)(\.(md|rst|txt))?|'
                         r'cargo\.lock|go\.sum|package-lock\.json|yarn\.lock|pnpm-lock\.yaml|poetry\.lock)$',
                         re.IGNORECASE)


def bookkeeping_only(paths):
    return bool(paths) and all(BOOKKEEPING.search(path) for path in paths)


def task_prompt(args, case, task):
    prompt = PROMPT.format(repo=case['repo'], task=task, budget=args.tool_budget)
    if extra := getattr(args, 'prompt_extra', None):
        prompt += '\n\n' + extra.text
    return prompt


YGG_GUIDE = """Search this repository with `ygg search "<query>"` in Bash. It
prints JSON: data.records are cited excerpts with path and line ranges, ranked
best first, and data.morePaths lists more candidate files. Options: --limit N,
-F for a fixed string, -E for a regular expression, and --about "<intent>" to
give the semantic intent separately from the lexical pattern. Read files to
verify what search returns. ygg search is the only Bash command available."""

GUARD = Path(__file__).resolve().with_name('guard.py')

SHELL_GUIDE = """Bash is your only tool. It runs read-only grep, find, ls, cat,
head, tail, sed -n, wc, sort, uniq, cut, tr, nl and echo inside this repository, and
they can be piped together. Other commands are refused."""

SHELL_YGG_GUIDE = SHELL_GUIDE + """ `ygg search` is also available.

""" + YGG_GUIDE.replace('Read files to\nverify what search returns. ygg search is the only Bash command available.',
                       'Read files to verify what search returns.')

# tools: Claude Code tools; guard: guard.py mode for Bash; ygg: needs the
# ygg wrapper on PATH; guide: appended system prompt.
ARMS = {
    'native': {'tools': ['Read', 'Grep', 'Glob'], 'guard': None, 'ygg': False, 'guide': None},
    'ygg': {'tools': ['Read', 'Bash'], 'guard': 'ygg', 'ygg': True, 'guide': YGG_GUIDE},
    'both': {'tools': ['Read', 'Grep', 'Glob', 'Bash'], 'guard': 'ygg', 'ygg': True, 'guide': YGG_GUIDE},
    'shell': {'tools': ['Bash'], 'guard': 'shell', 'ygg': False, 'guide': SHELL_GUIDE},
    'shell-ygg': {'tools': ['Bash'], 'guard': 'shell-ygg', 'ygg': True, 'guide': SHELL_YGG_GUIDE},
    # Codex runs its own shell tools; the ygg arm adds AGENTS.md-style guidance.
    'codex': {'harness': 'codex', 'tools': [], 'guard': None, 'ygg': False, 'guide': None},
    'codex-ygg': {'harness': 'codex', 'tools': [], 'guard': None, 'ygg': True, 'guide': None},
}

# How each --ygg-format is described to the agent, replacing the JSON sentence.
JSON_OUTPUT = """It
prints JSON: data.records are cited excerpts with path and line ranges, ranked
best first, and data.morePaths lists more candidate files."""
OUTPUT_GUIDES = {
    'json': JSON_OUTPUT,
    'compact': """It
prints each result as path:start-end followed by its excerpt, ranked best first,
then a `more:` line of further candidate files.""",
    'files': """It
prints the matching files, ranked best first, as path:start-end line ranges,
then a `more:` line of further candidate files. Read a file to see its lines.""",
}

CODEX_JSON_OUTPUT = """It prints JSON: read data.records for cited results (path, line range,
excerpt, best first) and data.morePaths for more candidate files."""
CODEX_OUTPUT_GUIDES = {
    'json': CODEX_JSON_OUTPUT,
    'compact': """It prints each result as path:start-end followed by its excerpt, best
first, then a `more:` line of further candidate files.""",
    'files': """It prints the matching files, best first, as path:start-end line ranges,
then a `more:` line of further candidate files.""",
}
CODEX_YGG_GUIDE = """Repository search: use `ygg search "<query>"` for bounded repository
search. """ + CODEX_JSON_OUTPUT + """ Options:
--limit N, -F fixed string, -E regular expression, --about "<intent>" for the
semantic intent apart from the lexical pattern. Verify relevant source lines
before answering. Use rg for exact text and file-name searches.

"""

WRAPPER = """#!/bin/sh
# Pins ygg to `search` over the agent's repository.
if [ "$1" != search ]; then
  echo '{{"ok":false,"error":"only ygg search is available"}}'; exit 2
fi
shift
for arg in "$@"; do
  case "$arg" in -root|--root|-root=*|--root=*)
    echo '{{"ok":false,"error":"the search root is fixed to the repository"}}'; exit 2;;
  esac
done
[ -n "$YGG_AGENT_LOG" ] && printf '%s\n' "$YGG_AGENT_REPO" >> "$YGG_AGENT_LOG"
# Keep every raw result, one JSON line per executed search, before formatting.
out=$({binary} search --root "$YGG_AGENT_REPO" "$@" 2>/dev/null)
code=$?
if [ -n "$YGG_AGENT_RAW" ]; then
  printf '%s\n' "$out" | tr -d '\n' >> "$YGG_AGENT_RAW"
  printf '\n' >> "$YGG_AGENT_RAW"
fi
if [ -n "$YGG_AGENT_FORMAT" ] && [ "$YGG_AGENT_FORMAT" != json ]; then
  printf '%s\n' "$out" | {python} {formatter} "$YGG_AGENT_FORMAT"
  exit $?
fi
printf '%s\n' "$out"
exit $code
"""


def sample(cases, tasks, count):
    """Round-robin over repositories in case order, skipping leaking tasks."""
    by_repo = {}
    for case in cases:
        entry = tasks.get(case['id'])
        if entry and not entry['leaks']:
            by_repo.setdefault(case['repo'], []).append(case)
    picked, queues = [], [list(queue) for _, queue in sorted(by_repo.items())]
    while queues and (count is None or len(picked) < count):
        for queue in list(queues):
            if count is not None and len(picked) >= count:
                break
            picked.append(queue.pop(0))
            if not queue:
                queues.remove(queue)
    return picked


def text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return '\n'.join(text_of(item.get('text') or item.get('content') or '')
                         if isinstance(item, dict) else str(item) for item in content)
    return str(content or '')


def normalize(path, repo):
    path = path.strip()
    prefix = str(repo) + '/'
    if path.startswith(prefix):
        path = path[len(prefix):]
    return path.removeprefix('./')


def score(events, case, repo):
    """Summarize one trajectory against the case's expected paths."""
    expected = case['expectedPaths']
    calls, results, final = [], {}, None
    for event in events:
        kind = event.get('type')
        message = event.get('message') or {}
        content = message.get('content')
        if kind == 'assistant' and isinstance(content, list):
            for item in content:
                if item.get('type') == 'tool_use' and item['name'] != 'StructuredOutput':
                    calls.append({'id': item['id'], 'name': item['name'], 'input': item.get('input', {})})
        elif kind == 'user' and isinstance(content, list):
            for item in content:
                if item.get('type') == 'tool_result':
                    results[item['tool_use_id']] = {'text': text_of(item.get('content')),
                                                    'error': bool(item.get('is_error'))}
        elif kind == 'result':
            final = event
    first_seen, first_read = None, None
    for index, call in enumerate(calls, 1):
        result = results.get(call['id'], {})
        if first_seen is None and any(path in result.get('text', '') for path in expected):
            first_seen = index
        target = normalize(str(call['input'].get('file_path', '')), repo)
        if first_read is None and call['name'] == 'Read' and target in expected:
            first_read = index
    answer = []
    if final:
        answer = [normalize(path, repo) for path in (final.get('structured_output') or {}).get('files', [])]
    found = [path for path in expected if path in answer]
    kinds = {}
    for call in calls:
        name = call['name']
        if name == 'Bash':
            name = 'ygg' if str(call['input'].get('command', '')).lstrip().startswith('ygg ') else 'shell'
        kinds[name] = kinds.get(name, 0) + 1
    usage = (final or {}).get('usage') or {}
    return {
        'answer': answer,
        'hit': bool(found),
        'bookkeepingOnly': bookkeeping_only(expected),
        'firstHitRank': next((rank for rank, path in enumerate(answer, 1) if path in expected), None),
        'recall': round(len(found) / len(expected), 4),
        'precision': round(len(found) / len(answer), 4) if answer else 0.0,
        'firstSeenCall': first_seen,
        'firstReadCall': first_read,
        'toolCalls': len(calls),
        'toolErrors': sum(1 for call in calls if results.get(call['id'], {}).get('error')),
        'toolsByKind': kinds,
        'searches': [{'tool': call['name'], 'input': call['input']} for call in calls
                     if call['name'] in ('Grep', 'Glob', 'Bash')],
        'turns': (final or {}).get('num_turns'),
        'costUsd': (final or {}).get('total_cost_usd'),
        'durationMs': (final or {}).get('duration_ms'),
        'inputTokens': sum(usage.get(key) or 0 for key in
                           ('input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens')),
        'outputTokens': usage.get('output_tokens'),
        'isError': bool((final or {}).get('is_error')) or final is None,
        'subtype': (final or {}).get('subtype'),
    }


def unwrap(command):
    """The command inside Codex's `/bin/zsh -lc '...'` wrapper."""
    try:
        parts = shlex.split(command)
    except ValueError:
        return command
    if len(parts) == 3 and parts[1] in ('-lc', '-c'):
        return parts[2]
    return command


def codex_events(raw, answer, duration_ms):
    """Map Codex JSON events onto the stream-json shape score() reads."""
    events, usage, turns = [], {}, 0
    for event in raw:
        item = event.get('item') or {}
        if event.get('type') == 'item.completed' and item.get('type') == 'command_execution':
            turns += 1
            events.append({'type': 'assistant', 'message': {'content': [
                {'type': 'tool_use', 'id': item['id'], 'name': 'Bash',
                 'input': {'command': unwrap(item.get('command', ''))}}]}})
            events.append({'type': 'user', 'message': {'content': [
                {'type': 'tool_result', 'tool_use_id': item['id'],
                 'content': item.get('aggregated_output') or '',
                 'is_error': item.get('exit_code') not in (0, None)}]}})
        elif event.get('type') == 'turn.completed':
            usage = event.get('usage') or {}
    events.append({'type': 'result', 'structured_output': answer, 'num_turns': turns,
                   'total_cost_usd': None, 'duration_ms': duration_ms,
                   'is_error': answer is None, 'subtype': 'codex',
                   'usage': {'input_tokens': usage.get('input_tokens'),
                             'output_tokens': usage.get('output_tokens')}})
    return events


def run_codex(case, task, arm, args, repo, log, env):
    spec = ARMS[arm]
    schema = Path(log).with_suffix('.schema.json')
    schema.write_text(ANSWER_SCHEMA)
    last = Path(log).with_suffix('.answer.json')
    last.unlink(missing_ok=True)
    raw_log = Path(log).with_suffix('.codex.jsonl')
    command = ['codex', 'exec', '--ignore-user-config', '--ephemeral', '--skip-git-repo-check',
               '-m', args.codex_model, '-c', f'model_reasoning_effort="{args.codex_effort}"',
               '-c', 'shell_environment_policy.inherit="all"',
               '-s', 'workspace-write', '-c', 'sandbox_workspace_write.network_access=true',
               '-c', 'sandbox_workspace_write.exclude_slash_tmp=true',
               '-c', 'sandbox_workspace_write.exclude_tmpdir_env_var=true',
               '--json', '--output-schema', str(schema), '-o', str(last), '-C', str(repo), '-']
    guide = CODEX_YGG_GUIDE.replace(CODEX_JSON_OUTPUT, CODEX_OUTPUT_GUIDES[args.ygg_format])
    prompt = (guide if spec['ygg'] else '') + task_prompt(args, case, task)
    started = time.monotonic()
    # Results can live under a system temporary root. Grant each process its
    # own scratch directory instead of letting it write to that whole root.
    with tempfile.TemporaryDirectory(prefix='ygg-case-', dir=Path(log).parent) as scratch:
        env = dict(env, TMPDIR=scratch)
        command[-1:-1] = ['--add-dir', scratch]
        if spec['ygg']:
            captured = Path(scratch) / 'searches.jsonl'
            collected = Path(log).with_suffix('.ygg-raw.jsonl')
            collected.unlink(missing_ok=True)
            env['YGG_AGENT_RAW'] = str(captured)
            command[-1:-1] = ['--add-dir', str(args.storage)]
        with open(raw_log, 'w') as out:
            try:
                subprocess.run(command, input=prompt, stdout=out, stderr=subprocess.DEVNULL,
                               text=True, cwd=repo, env=env, timeout=args.timeout)
                timed_out = False
            except subprocess.TimeoutExpired:
                timed_out = True
        if spec['ygg']:
            try:
                fd = os.open(captured, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            except FileNotFoundError:
                pass
            else:
                with os.fdopen(fd, 'rb') as source:
                    if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
                        raise ValueError('Ygg capture is not a regular file')
                    with collected.open('xb') as destination:
                        shutil.copyfileobj(source, destination)
    try:
        answer = json.loads(last.read_text())
    except (OSError, json.JSONDecodeError):
        answer = None
    events = codex_events(load(raw_log), answer, round((time.monotonic() - started) * 1000))
    Path(log).write_text(''.join(json.dumps(event) + '\n' for event in events))
    dirty = subprocess.run(['git', '-C', str(repo), 'status', '--porcelain'],
                           capture_output=True, text=True).stdout.splitlines()
    return events, timed_out, dirty


def run_one(case, task, arm, args, repo, log):
    spec = ARMS[arm]
    if spec.get('harness') == 'codex':
        # A clean Codex home (the user's login only, no skills, plugins or
        # AGENTS.md) and no zsh startup files, so PATH keeps the ygg wrapper.
        env = dict(os.environ, CODEX_HOME=str(args.codex_home), ZDOTDIR=str(args.codex_home / 'zsh'))
        if spec['ygg']:
            env.update(PATH=f'{args.wrapper_dir}:{env["PATH"]}', YGG_AGENT_BIN=str(args.wrapper_dir),
                       YGG_AGENT_REPO=str(repo), YGG_STORAGE_ROOT=str(args.storage),
                       YGG_AGENT_FORMAT=args.ygg_format,
                       YGG_AGENT_LOG=str(args.storage / 'agent-wrapper.log'))
            if args.ygg_config:
                env['YGG_CONFIG'] = str(args.ygg_config)
        started = time.monotonic()
        events, timed_out, dirty = run_codex(case, task, arm, args, repo, log, env)
        row = {'case': case['id'], 'repo': case['repo'], 'arm': arm, 'model': args.codex_model,
               'expectedPaths': case['expectedPaths'], 'timedOut': timed_out, 'dirty': dirty,
               'wallMs': round((time.monotonic() - started) * 1000)}
        row.update(score(events, case, repo))
        return row
    command = ['claude', '-p', '--model', args.model, '--tools', *spec['tools'],
               '--no-session-persistence', '--strict-mcp-config', '--setting-sources', '',
               '--disable-slash-commands', '--output-format', 'stream-json', '--verbose',
               '--json-schema', ANSWER_SCHEMA, '--max-budget-usd', str(args.max_budget)]
    # bash rather than the user's login shell, so globs in arguments such as
    # --include=*.py pass through as they would in a typical harness.
    env = dict(os.environ, SHELL='/bin/bash')
    if spec['guide']:
        guide = spec['guide'].replace(JSON_OUTPUT, OUTPUT_GUIDES[args.ygg_format]) if spec['ygg'] else spec['guide']
        if spec['ygg'] and args.guide_extra:
            guide += '\n\n' + args.guide_extra.text
        command += ['--append-system-prompt', guide]
    if spec['guard']:
        # The guard decides; the allow rule only spares a permission prompt.
        command += ['--allowedTools', 'Bash', '--settings', json.dumps({'hooks': {'PreToolUse': [
            {'matcher': 'Bash', 'hooks': [
                {'type': 'command', 'command': f"{sys.executable} {GUARD} {spec['guard']}"}]}]}})]
    if spec['ygg']:
        env.update(PATH=f'{args.wrapper_dir}:{env["PATH"]}', YGG_AGENT_REPO=str(repo),
                   YGG_STORAGE_ROOT=str(args.storage), YGG_AGENT_FORMAT=args.ygg_format,
                   YGG_AGENT_RAW=str(Path(log).with_suffix('.ygg-raw.jsonl')),
                   YGG_AGENT_LOG=str(args.wrapper_dir.parent / 'wrapper.log'))
        if args.ygg_config:
            env['YGG_CONFIG'] = str(args.ygg_config)
    Path(log).with_suffix('.ygg-raw.jsonl').unlink(missing_ok=True)
    prompt = task_prompt(args, case, task)
    started = time.monotonic()
    with open(log, 'w') as out:
        try:
            subprocess.run(command, input=prompt, stdout=out, stderr=subprocess.DEVNULL,
                           text=True, cwd=repo, env=env, timeout=args.timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            timed_out = True
    events = load(log)
    row = {'case': case['id'], 'repo': case['repo'], 'arm': arm, 'model': args.model,
           'expectedPaths': case['expectedPaths'], 'timedOut': timed_out,
           'wallMs': round((time.monotonic() - started) * 1000)}
    row.update(score(events, case, repo))
    return row


def load(log):
    events = []
    for line in Path(log).read_text().splitlines():
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return events


def rescore(row, case, suite, output):
    """Recompute a finished row from its kept trajectory."""
    log = output / 'logs' / f"{case['id']}.{row['arm']}.jsonl"
    row.update(score(load(log), case, (suite / 'repos' / case['repo']).resolve()))
    return row


def summarize(rows):
    by_arm = {}
    for row in rows:
        by_arm.setdefault(row['arm'], []).append(row)
    lines = []
    for arm, group in sorted(by_arm.items()):
        n = len(group)
        mean = lambda key: sum(row[key] or 0 for row in group) / n
        seen = [row['firstSeenCall'] for row in group if row['firstSeenCall']]
        labelled = [row for row in group if not row.get('bookkeepingOnly')]
        lines.append(
            f"{arm}: n={n} hit {sum(row['hit'] for row in group)}/{n} "
            f"(without bookkeeping-only {sum(row['hit'] for row in labelled)}/{len(labelled)}) "
            f"recall {mean('recall'):.3f} "
            f"precision {mean('precision'):.3f} seen {len(seen)}/{n} "
            f"(median call {sorted(seen)[len(seen) // 2] if seen else '-'}) "
            f"calls {mean('toolCalls'):.1f} turns {mean('turns'):.1f} "
            f"cost ${sum(row['costUsd'] or 0 for row in group):.3f} "
            f"time {mean('durationMs') / 1000:.0f}s errors {sum(row['isError'] for row in group)}")
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--suite', required=True, help='Suite directory with <split>.json, repos and agent tasks')
    parser.add_argument('--split', default='dev')
    parser.add_argument('--arms', default='native,ygg')
    parser.add_argument('--sample', type=int, help='Cases to run, round-robin over repositories')
    parser.add_argument('--ygg-format', choices=sorted(OUTPUT_GUIDES), default='json',
                        help='Output shape the ygg wrapper gives the agent (see format.py)')
    parser.add_argument('--guide-extra', type=Path, help='Text appended to the ygg arms\' guidance')
    parser.add_argument('--prompt-extra', type=Path, help='Text appended to every arm\'s task prompt')
    parser.add_argument('--cases', type=Path, help='JSON file with a list of case ids to run instead')
    parser.add_argument('--model', default='claude-haiku-5-5')
    parser.add_argument('--style', default='issue', help='Task style generated by tasks.py')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=300, help='Wall-clock seconds per run')
    parser.add_argument('--max-budget', type=float, default=0.15, help='USD cap per run')
    parser.add_argument('--codex-model', default='gpt-6.1-sol', help='Model for the codex arms')
    parser.add_argument('--codex-effort', default='medium', help='Reasoning effort for the codex arms')
    parser.add_argument('--tool-budget', type=int, default=20, help='Tool calls the prompt allows')
    parser.add_argument('--ygg-binary', type=Path, help='ygg binary for the ygg arms')
    parser.add_argument('--ygg-config', type=Path, help='YGG_CONFIG for the ygg arms')
    parser.add_argument('--storage', type=Path, help='YGG_STORAGE_ROOT for the ygg arms')
    parser.add_argument('--output', type=Path, required=True, help='Run directory, outside every repository')
    args = parser.parse_args()
    suite = args.suite = Path(args.suite).expanduser()
    arms = args.arms.split(',')
    if unknown := set(arms) - set(ARMS):
        raise SystemExit(f'unknown arms: {sorted(unknown)}')
    if len(arms) != len(set(arms)):
        parser.error('--arms must not contain duplicates')
    uses_ygg = any(ARMS[arm]['ygg'] for arm in arms)
    if uses_ygg and not (args.ygg_binary and args.storage):
        raise SystemExit('ygg arms need --ygg-binary and --storage')
    for name in ('ygg_binary', 'ygg_config', 'storage', 'guide_extra', 'prompt_extra'):
        if path := getattr(args, name, None):
            setattr(args, name, path.expanduser().resolve())
    cases = json.loads((suite / f'{args.split}.json').read_text())
    tasks = json.loads(task_file(suite, args.split, args.style).read_text())
    if args.cases:
        wanted = set(json.loads(args.cases.read_text()))
        cases = [case for case in cases if case['id'] in wanted]
    picked = sample(cases, tasks, args.sample)
    if not picked or len({case['id'] for case in picked}) != len(picked):
        parser.error('select at least one case and use unique case IDs')
    output = args.output.expanduser().resolve()
    if (any(ARMS[arm].get('harness') == 'codex' and ARMS[arm]['ygg'] for arm in arms) and
            (output / 'logs').resolve().is_relative_to(args.storage)):
        parser.error('keep Codex eval logs outside the Ygg storage directory')
    for repo in {case['repo'] for case in picked}:
        root = (suite / 'repos' / repo).resolve()
        if output.is_relative_to(root) or suite.resolve().is_relative_to(root):
            raise SystemExit('Keep suite and output outside every repository under test')
    try:
        if args.prompt_extra:
            args.prompt_extra = PromptInput.read(args.prompt_extra)
        if uses_ygg and args.guide_extra:
            args.guide_extra = PromptInput.read(args.guide_extra)
        manifest = run_manifest(args, {arm: ARMS[arm] for arm in arms}, picked, tasks)
        with checked_output(output, manifest):
            return run_cases(args, suite, arms, picked, tasks, output)
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from None


def run_cases(args, suite, arms, picked, tasks, output):
    (output / 'logs').mkdir(parents=True, exist_ok=True)
    if args.ygg_binary:
        args.wrapper_dir = output / 'bin'
        args.wrapper_dir.mkdir(exist_ok=True)
        wrapper = args.wrapper_dir / 'ygg'
        wrapper.write_text(WRAPPER.format(binary=args.ygg_binary.resolve(), python=sys.executable,
                                          formatter=Path(__file__).resolve().with_name('format.py')))
        wrapper.chmod(0o755)
    if any(ARMS[arm].get('harness') == 'codex' for arm in arms):
        args.codex_home = output / 'codex-home'
        (args.codex_home / 'zsh').mkdir(parents=True, exist_ok=True)
        # Login shells run path_helper, which moves system paths (and any
        # installed ygg) ahead of the wrapper; put the wrapper back in front.
        (args.codex_home / 'zsh' / '.zprofile').write_text(
            '[ -n "$YGG_AGENT_BIN" ] && export PATH="$YGG_AGENT_BIN:$PATH"\n')
        auth = args.codex_home / 'auth.json'
        if not auth.is_symlink():
            auth.symlink_to(Path(os.environ.get('CODEX_HOME', Path.home() / '.codex')) / 'auth.json')
    results_path = output / 'results.json'
    previous = json.loads(results_path.read_text()) if results_path.exists() else []
    by_id = {case['id']: case for case in picked}
    rows = {}
    for row in previous:
        key = row['case'], row['arm']
        if key[0] not in by_id or key[1] not in arms or key in rows:
            raise ValueError(f'unexpected or duplicate run result: {key}')
        if not row['isError'] and not row['timedOut']:
            log = output / 'logs' / f'{key[0]}.{key[1]}.jsonl'
            if log.is_file():
                rescore(row, by_id[key[0]], suite, output)
            else:
                row.update(isError=True, runnerError='saved trajectory is missing')
        rows[key] = row
    done = {key for key, row in rows.items() if not row['isError'] and not row['timedOut']}
    todo = [(case, arm) for case in picked for arm in arms if (case['id'], arm) not in done]
    interrupted = {(case['id'], arm) for case, arm in todo
                   if (output / 'logs' / f"{case['id']}.{arm}.jsonl").is_file()}
    print(f'{len(todo)} runs to do, {len(done)} done', file=sys.stderr)

    def work(item):
        case, arm = item
        repo = (suite / 'repos' / case['repo']).resolve()
        log = output / 'logs' / f"{case['id']}.{arm}.jsonl"
        return run_one(case, tasks[case['id']]['task'], arm, args, repo, log)

    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        futures = {pool.submit(work, item): item for item in todo}
        for count, future in enumerate(concurrent.futures.as_completed(futures), 1):
            case, arm = futures[future]
            try:
                row = future.result()
            except Exception as error:  # keep the rest of the run
                print(f"{case['id']} {arm}: {error}", file=sys.stderr)
                repo = (suite / 'repos' / case['repo']).resolve()
                row = {'case': case['id'], 'repo': case['repo'], 'arm': arm,
                       'expectedPaths': case['expectedPaths'], 'timedOut': False, 'runnerError': str(error)}
                row.update(score([], case, repo))
            key = case['id'], arm
            if earlier := rows.get(key):
                failure = {field: earlier[field] for field in
                           ('isError', 'timedOut', 'subtype', 'runnerError', 'costUsd', 'wallMs') if field in earlier}
                row['priorFailures'] = [*earlier.get('priorFailures', []), failure]
            elif key in interrupted:
                row['priorFailures'] = [{'runnerError': 'saved trajectory had no result row'}]
            if ARMS[arm]['ygg']:
                log = output / 'logs' / f"{case['id']}.{arm}.jsonl"
                row['yggObserved'] = observed_searches(log)
            rows[key] = row
            write_json(results_path, list(rows.values()))
            print(f"{count}/{len(todo)} {case['id']} {arm}: hit={row['hit']} recall={row['recall']} "
                  f"calls={row['toolCalls']} ${row['costUsd'] or 0:.3f}", file=sys.stderr)
    write_json(results_path, list(rows.values()))
    print(summarize(rows.values()))
    return 0


if __name__ == '__main__':
    sys.exit(main())
