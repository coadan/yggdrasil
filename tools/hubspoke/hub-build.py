"""Write the synthetic khub-style hub repository and the suite from hub-adrs.json.

hub/: AGENTS.md, manifest.yaml, system/components/<spoke>.md,
system/decisions/<slug>.md (adr, code AD-n, affects component, produces boundary),
system/boundaries/<slug>.md (BOUND-NNN). Codes are assigned in shuffled order.
Suite rows: one per case and query kind (task, task+code, adr->code).
"""
import json, random, re, subprocess, sys
from pathlib import Path
OSS = Path.home() / '.local/share/ygg-eval/oss'
H = Path.home() / '.local/share/ygg-eval/history-oss-2026-11-02'
src, hub, suite_out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
adrs = json.load(open(src))
tasks = json.load(open(H / 'agent-tasks-dev-symptom.json'))
meta = {c['commit']: c for c in json.load(open(H / 'dev.json'))}
subprocess.run(['rm', '-rf', str(hub)]); (hub / 'system/decisions').mkdir(parents=True)
(hub / 'system/boundaries').mkdir(); (hub / 'system/components').mkdir()
slug = lambda t: re.sub(r'[^a-z0-9]+', '-', t.lower()).strip('-')[:70]
DESC = {'gin': 'HTTP web framework service written in Go: routing, middleware, request binding and rendering.',
        'hono': 'Web application framework for edge and server runtimes written in TypeScript: routing, middleware, JSX and helpers.',
        'axios': 'Promise-based HTTP client library used by browser and Node consumers: requests, adapters, interceptors.'}
(hub / 'manifest.yaml').write_text('spokes:\n' + ''.join(f'  - name: {r}\n    repo: {r}\n    component: component/{r}\n' for r in DESC))
(hub / 'AGENTS.md').write_text('# Hub\n\nThis hub holds the product knowledge: decisions (AD-n), boundaries (BOUND-NNN) and components.\nSpokes hold the code. Records live under system/, one folder per type. Extend boundaries, never weaken them.\n')
for r, d in DESC.items():
    (hub / f'system/components/{r}.md').write_text(f'---\ntype: component\nslug: {r}\nspoke: {r}\n---\n\n# {r}\n\n{d}\n')
keys = sorted(adrs); random.Random(11).shuffle(keys)
bn = 0; used = set(); suite = []
for n, k in enumerate(keys, 1):
    a = adrs[k]; s = slug(a['title'])
    while s in used: s += '-x'
    used.add(s)
    produces = ''
    if a['boundary'].strip():
        bn += 1; bs = f'{s}-invariant'
        (hub / f'system/boundaries/{bs}.md').write_text(
            f'---\ntype: boundary\ncode: BOUND-{bn:03d}\nstatus: active\naffects:\n  - component/{a["repo"]}\n---\n\n# BOUND-{bn:03d}\n\n{a["boundary"].strip()}\n')
        produces = f'produces:\n  - boundary/{bs}\n'
    path = f'system/decisions/{s}.md'
    (hub / path).write_text(
        f'---\ntype: adr\ncode: AD-{n}\nstatus: accepted\naffects:\n  - component/{a["repo"]}\n{produces}---\n\n'
        f'# AD-{n}: {a["title"]}\n\n## Context\n\n{a["context"]}\n\n## Decision\n\n{a["decision"]}\n\n## Consequences\n\n{a["consequences"]}\n')
    if a['role'] != 'case': continue
    c = meta[a['commit']]; repo = OSS / a['repo']
    base = subprocess.run(['git', '-C', str(repo), 'rev-parse', a['commit'] + '^'], capture_output=True, text=True).stdout.strip()
    exp = [p for p in c['expectedPaths'] if subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', f'{base}:{p}'], capture_output=True).returncode == 0]
    if not exp: continue
    task = tasks[c['id']]['task']
    common = dict(case=c['id'], repo=a['repo'], base=base, expectedPaths=exp, adrPath=path)
    suite += [dict(common, kind='task', query=task),
              dict(common, kind='task+code', query=f'{task} See AD-{n}.'),
              dict(common, kind='adr', query=f'{a["title"]}. {a["decision"]}')]
subprocess.run(['git', 'init', '-q', '-b', 'main', str(hub)], check=True)
subprocess.run(['git', '-C', str(hub), 'add', '-A'], check=True)
subprocess.run(['git', '-C', str(hub), '-c', 'user.name=eval', '-c', 'user.email=eval@example.invalid', 'commit', '-q', '-m', 'hub'], check=True)
suite_out.write_text(json.dumps(suite, indent=1))
print(len(keys), 'adrs,', bn, 'boundaries,', len(suite) // 3, 'cases x 3 query kinds')
