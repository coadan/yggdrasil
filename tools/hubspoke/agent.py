#!/usr/bin/env python3
"""Agent trial on the synthetic hub-and-spoke project: per-repository search vs project search.

For each case, every member (hub, gin, hono, axios) gets a worktree on one task
branch (the case's spoke at the commit's parent), indexed with `ygg index` as a
Workbench prepare hook would. A headless Claude agent starts in the hub worktree
with the symptom task and must name the governing decision record and the code
files to change, as `repo/path`. Arms:
  repos    `ygg search --root <repository> "<query>"`, one repository per call
  project  `ygg search --project demo "<query>"`, every repository per call
Both use compact output and the given configuration (embedding, reranker, and a
`demo` project whose members are the base checkouts). Bash is held by
tools/agent-eval/guard.py in ygg mode. Results are appended to --output.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, os.environ.get('YGG_AGENT_EVAL', str(Path(__file__).resolve().parents[1] / 'agent-eval')))
from run import ANSWER_SCHEMA, GUARD, load, text_of  # noqa: E402

MEMBERS = ['hub', 'gin', 'hono', 'axios']

PROMPT = """You work on a project made of four Git repositories:
- hub: product knowledge: decision records (system/decisions, codes AD-n), boundaries (BOUND-NNN) and components, at {hub}
- gin: a Go web framework, at {gin}
- hono: a TypeScript web framework, at {hono}
- axios: a JavaScript HTTP client, at {axios}
Your current directory is the hub checkout. Here is the task:

{task}

Do not change anything. Find the hub decision record that governs this behaviour
and the code files you would need to change. Answer with paths prefixed by their
repository name (for example gin/context.go or hub/system/decisions/x.md), most
important first. Use at most {budget} tool calls."""

COMMON = """It prints results as path:start-end with an excerpt, best first,
then more candidate paths. Options: --limit N, -F for a fixed string, -E for a
regular expression, and --about "<intent>" to give the semantic intent
separately from the lexical pattern. Read files to verify what search returns.
ygg search is the only Bash command available."""

GUIDES = {
    'repos': 'Search one repository with `ygg search --root <repository path> "<query>"` in Bash. ' + COMMON,
    'project': ('Search all four repositories at once with `ygg search --project demo "<query>"` in Bash. '
                'It prints each repository with its root path, then ' + COMMON[len('It prints '):]),
}


def normalize(path, roots):
    path = path.strip().removeprefix('./')
    for alias, root in roots.items():
        for prefix in (f'{root}/', f'{alias}/', f'{alias}:'):
            if path.startswith(prefix):
                return f'{alias}/{path[len(prefix):]}'
    if (roots['hub'] / path).exists():
        return f'hub/{path}'
    owners = [a for a in MEMBERS[1:] if (roots[a] / path).exists()]
    return f'{owners[0]}/{path}' if len(owners) == 1 else path


def score(events, case, roots):
    calls, final = 0, None
    for event in events:
        message = event.get('message')
        content = message.get('content') if isinstance(message, dict) else None
        if event.get('type') == 'assistant' and isinstance(content, list):
            calls += sum(1 for item in content if item.get('type') == 'tool_use' and item['name'] != 'StructuredOutput')
        elif event.get('type') == 'result':
            final = event
    answer = [normalize(p, roots) for p in ((final or {}).get('structured_output') or {}).get('files', [])]
    spoke = [f'{case["repo"]}/{p}' for p in case['expectedPaths']]
    adr = f'hub/{case["adrPath"]}'
    usage = (final or {}).get('usage') or {}
    return dict(answer=answer, spokeHit=any(p in answer for p in spoke), adrHit=adr in answer,
                spokeRecall=round(sum(p in answer for p in spoke) / len(spoke), 4),
                toolCalls=calls, costUsd=(final or {}).get('total_cost_usd'),
                inputTokens=sum(usage.get(k) or 0 for k in ('input_tokens', 'cache_creation_input_tokens',
                                                             'cache_read_input_tokens')),
                isError=bool((final or {}).get('is_error')) or final is None)


def run_arm(arm, case, roots, args, env, log):
    command = ['claude', '-p', '--model', args.model, '--tools', 'Read', 'Bash',
               '--no-session-persistence', '--strict-mcp-config', '--setting-sources', '',
               '--disable-slash-commands', '--output-format', 'stream-json', '--verbose',
               '--json-schema', ANSWER_SCHEMA, '--max-budget-usd', str(args.max_budget),
               '--append-system-prompt', GUIDES[arm], '--allowedTools', 'Bash',
               '--settings', json.dumps({'hooks': {'PreToolUse': [{'matcher': 'Bash', 'hooks': [
                   {'type': 'command', 'command': f'{sys.executable} {GUARD} ygg'}]}]}})]
    prompt = PROMPT.format(task=case['query'], budget=args.budget, **{m: roots[m] for m in MEMBERS})
    started = time.monotonic()
    with open(log, 'w') as out:
        try:
            subprocess.run(command, input=prompt, stdout=out, stderr=subprocess.DEVNULL, text=True,
                           cwd=roots['hub'], env=env, timeout=args.timeout)
        except subprocess.TimeoutExpired:
            pass
    row = dict(case=case['case'], repo=case['repo'], arm=arm, rep=args.rep,
               wallMs=round((time.monotonic() - started) * 1000))
    row.update(score(load(log), case, roots))
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--binary', required=True, type=Path)
    ap.add_argument('--work', required=True, type=Path)
    ap.add_argument('--suite', required=True, type=Path)
    ap.add_argument('--config', required=True, type=Path, help='embedding, reranker and project "demo"')
    ap.add_argument('--output', required=True, type=Path)
    ap.add_argument('--model', default='claude-haiku-5-5')
    ap.add_argument('--budget', type=int, default=8)
    ap.add_argument('--max-budget', type=float, default=0.15)
    ap.add_argument('--timeout', type=int, default=300)
    ap.add_argument('--rep', type=int, default=1)
    ap.add_argument('--limit', type=int)
    args = ap.parse_args()
    args.config, args.binary, args.work = args.config.resolve(), args.binary.resolve(), args.work.resolve()
    work = args.work
    base = {m: work / 'base' / m for m in MEMBERS}
    logs = work / 'agent-logs'; logs.mkdir(exist_ok=True)
    bindir = work / 'agent-bin'; bindir.mkdir(exist_ok=True)
    wrapper = bindir / 'ygg'
    wrapper.write_text(f'#!/bin/sh\nif [ "$1" = search ]; then shift; exec {args.binary} search --format compact "$@"; fi\n'
                       f'exec {args.binary} "$@"\n')
    wrapper.chmod(0o755)
    env = dict(os.environ, SHELL='/bin/bash', PATH=f'{bindir}:{os.environ["PATH"]}',
               YGG_CONFIG=str(args.config), YGG_STORAGE_ROOT=str(work / 'store'))
    rows = [json.loads(l) for l in args.output.read_text().splitlines()] if args.output.exists() else []
    done = {(r['case'], r['arm'], r['rep']) for r in rows}
    cases = [q for q in json.load(open(args.suite)) if q['kind'] == 'task'][:args.limit]
    for n, case in enumerate(cases):
        arms = [a for a in GUIDES if (case['case'], a, args.rep) not in done]
        if not arms:
            continue
        store_before = set((work / 'store' / 'indexes').iterdir())
        wt = {m: work / 'agent-wt' / m for m in MEMBERS}
        branch = f'agent/{args.rep}-{n}'
        for m in MEMBERS:
            subprocess.run(['rm', '-rf', str(wt[m])])
            subprocess.run(['git', '-C', str(base[m]), 'worktree', 'prune'], capture_output=True)
            subprocess.run(['git', '-C', str(base[m]), 'branch', '-D', branch], capture_output=True)
            subprocess.run(['git', '-C', str(base[m]), 'worktree', 'add', '-q', '-b', branch, str(wt[m]),
                            case['base'] if m == case['repo'] else 'HEAD'], check=True, capture_output=True)
            subprocess.run([str(args.binary), 'index', '--root', str(wt[m])], env=env, capture_output=True)
        with concurrent.futures.ThreadPoolExecutor(len(arms)) as pool:
            futures = [pool.submit(run_arm, a, case, wt, args, env,
                                   logs / f'{case["case"].replace(":", "_")}-{a}-{args.rep}.jsonl') for a in arms]
            for f in futures:
                row = f.result()
                with args.output.open('a') as out:
                    out.write(json.dumps(row) + '\n')
                print(row['case'], row['arm'], 'spoke' if row['spokeHit'] else '-', 'adr' if row['adrHit'] else '-',
                      row['toolCalls'], row['costUsd'], flush=True)
        for m in MEMBERS:
            subprocess.run(['git', '-C', str(base[m]), 'worktree', 'remove', '--force', str(wt[m])], capture_output=True)
            subprocess.run(['git', '-C', str(base[m]), 'branch', '-D', branch], capture_output=True)
        for d in set((work / 'store' / 'indexes').iterdir()) - store_before:
            subprocess.run(['rm', '-rf', str(d)])


if __name__ == '__main__':
    main()
