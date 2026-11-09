"""Generate khub-style ADR records for spoke commits (synthetic hub-and-spoke suite).

Cases: history dev cases for the chosen spokes (with symptom tasks). Distractors:
other recent commits from the same spokes. Each commit gets one ADR written by Haiku
from the commit message and diff, as if decided before implementation; an optional
one-line invariant becomes a boundary record. Mechanical leak check: no expected path,
file name or file stem; one retry. Output cache: hub-adrs.json keyed by repo:commit.
"""
import concurrent.futures, json, random, re, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent-eval'))
import tasks as T

SPOKES = ['gin', 'hono', 'axios']
H = Path.home() / '.local/share/ygg-eval/history-oss-2026-11-02'
OSS = Path.home() / '.local/share/ygg-eval/oss'
OUT = Path(sys.argv[1])
SYSTEM = """You write architecture decision records (ADRs) for a team's knowledge hub.
Given a commit to one of the team's code repositories, write the ADR the team
recorded before the change was implemented: the problem and forces, the choice
made between real alternatives, and its consequences.
Rules:
- Write as a decision about the product or its behaviour, in plain prose.
- Never name files, directories, paths, tests, or internal functions, classes,
  methods, types or variables. Public API names, options or headers a user of
  the library would see are allowed.
- title: a short decision title (under 12 words).
- context: 2-4 sentences. decision: 1-3 sentences starting with "We will".
  consequences: 1-3 sentences.
- boundary: if the decision implies an invariant that later changes must never
  weaken, state it in one sentence; otherwise an empty string."""
SCHEMA = json.dumps({"type": "object", "properties": {k: {"type": "string"} for k in
                     ("title", "context", "decision", "consequences", "boundary")},
                     "required": ["title", "context", "decision", "consequences", "boundary"],
                     "additionalProperties": False})

def generate(repo, commit):
    request = f'Repository: {repo}\n\nCommit:\n\n' + T.commit_text(OSS / repo, commit)
    r = subprocess.run(['claude', '-p', '--model', 'claude-haiku-5-5', '--tools', '', '--no-session-persistence',
                        '--strict-mcp-config', '--setting-sources', '', '--disable-slash-commands',
                        '--system-prompt', SYSTEM, '--output-format', 'json', '--json-schema', SCHEMA],
                       input=request, capture_output=True, text=True, timeout=300)
    d = json.loads(r.stdout)
    out = d.get('structured_output') or {}
    if d.get('is_error') or not out.get('decision'):
        raise RuntimeError(str(d.get('result'))[:300])
    return out, d.get('total_cost_usd') or 0

def changed(repo, commit):
    return subprocess.run(['git', '-C', str(OSS / repo), 'show', '--format=', '--name-only', commit],
                          capture_output=True, text=True).stdout.split()

cases = [c for c in json.load(open(H / 'dev.json')) if c['repo'] in SPOKES]
tasks = json.load(open(H / 'agent-tasks-dev-symptom.json'))
cases = [c for c in cases if c['id'] in tasks and not tasks[c['id']].get('leaks')]
todo = [(c['repo'], c['commit'], 'case') for c in cases]
rng = random.Random(7)
used = {c['commit'] for c in cases}
for repo in SPOKES:
    log = subprocess.run(['git', '-C', str(OSS / repo), 'log', '--no-merges', '--format=%H', '-n', '600'],
                         capture_output=True, text=True).stdout.split()
    pool = [h for h in log if h not in used and 1 <= len(changed(repo, h)) <= 15]
    todo += [(repo, h, 'distractor') for h in rng.sample(pool, 30)]
cache = json.loads(OUT.read_text()) if OUT.exists() else {}
todo = [t for t in todo if f'{t[0]}:{t[1]}' not in cache]
print(len(todo), 'to generate', file=sys.stderr)

def work(t):
    repo, commit, role = t
    paths = changed(repo, commit)
    cost = 0
    for _ in range(2):
        adr, spent = generate(repo, commit); cost += spent
        text = ' '.join(adr.values())
        leaked = T.leaks(text, paths) + T.named_stems(text, paths)
        if not leaked: break
    return f'{repo}:{commit}', dict(adr, repo=repo, commit=commit, role=role, leaks=leaked, costUsd=round(cost, 6))

with concurrent.futures.ThreadPoolExecutor(6) as ex:
    for i, f in enumerate(concurrent.futures.as_completed([ex.submit(work, t) for t in todo]), 1):
        try:
            k, v = f.result(); cache[k] = v
        except Exception as e:
            print('fail', e, file=sys.stderr)
        if i % 10 == 0: OUT.write_text(json.dumps(cache, indent=1)); print(i, file=sys.stderr)
OUT.write_text(json.dumps(cache, indent=1))
print(len(cache), 'adrs;', sum(1 for v in cache.values() if v['leaks']), 'leaking; $%.3f' % sum(v['costUsd'] for v in cache.values()), file=sys.stderr)
