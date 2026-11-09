"""Synthetic hub-and-spoke search eval: per-member ygg search, RRF merge, one rerank pass.

Members: hub + gin + hono + axios. The case's spoke is searched at the commit's
parent; the other members at their base checkout head. Arms:
  cold     the case spoke from a git archive with a fresh store, fully indexed;
           other members are the warm base checkouts.
  seeded   `git worktree add` on branch task/<n> in every member (case spoke at
           the parent), first search immediately (ygg seeds from the base index
           and never waits for vectors).
  refreshed the same worktrees after `ygg index` completes.
Each arm runs lexical and auto (MiniLM hybrid) per member without a reranker,
merges the four lists by reciprocal rank (k=60) on member/path, and reranks the
first 20 merged candidates once with the HTTP reranker.
"""
import argparse, json, os, subprocess, time, urllib.request
from pathlib import Path

MEMBERS = ['hub', 'gin', 'hono', 'axios']
K = 60


def run_search(binary, env, root, mode, query):
    start = time.monotonic()
    out = subprocess.run([binary, 'search', '--root', str(root), '--mode', mode, '--limit', '20', query],
                         capture_output=True, text=True, env=env, timeout=600).stdout
    data = {}
    for line in out.splitlines():
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(d.get('data'), dict) and 'records' in d['data']:
            data = d['data']
    items, seen = [], set()
    for r in data.get('records', []):
        if r['path'] not in seen:
            seen.add(r['path']); items.append((r['path'], r.get('excerpt', '')))
    for p in data.get('morePaths', []):
        if p not in seen:
            seen.add(p); items.append((p, ''))
    return items, dict(activeMode=data.get('activeMode'), fallback=data.get('fallbackReason'),
                       seconds=round(time.monotonic() - start, 2))


def merge(lists):
    score, text = {}, {}
    for member, items in lists.items():
        for i, (path, excerpt) in enumerate(items):
            key = f'{member}/{path}'
            score[key] = score.get(key, 0) + 1 / (K + i + 1)
            text.setdefault(key, excerpt)
    return sorted(score, key=lambda k: -score[k]), text


def rerank(endpoint, query, keys, text, roots):
    docs = []
    for key in keys:
        member, path = key.split('/', 1)
        body = text.get(key) or ''
        if not body:
            try:
                body = (roots[member] / path).read_text(errors='replace')[:1500]
            except OSError:
                body = ''
        docs.append(f'{key}\n{body}'[:1500])
    req = urllib.request.Request(endpoint, data=json.dumps(dict(query=query, documents=docs)).encode(),
                                 headers={'content-type': 'application/json'})
    res = json.load(urllib.request.urlopen(req, timeout=120))['results']
    order = [keys[r['index']] for r in sorted(res, key=lambda r: -r['relevance_score'])]
    return order


def score(final, case):
    spoke = [f'{case["repo"]}/{p}' for p in case['expectedPaths']]
    adr = f'hub/{case["adrPath"]}'
    spoke_rank = next((i + 1 for i, k in enumerate(final) if k in spoke), None)
    adr_rank = next((i + 1 for i, k in enumerate(final) if k == adr), None)
    wrong = sum(1 for k in final[:10] if k.split('/', 1)[0] not in ('hub', case['repo']))
    return dict(spokeRank=spoke_rank, adrRank=adr_rank, wrongSpokeTop10=wrong)


def sh(*cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--binary', required=True)
    ap.add_argument('--work', required=True, type=Path)
    ap.add_argument('--suite', required=True, type=Path)
    ap.add_argument('--config', required=True, type=Path)
    ap.add_argument('--rerank', default='http://127.0.0.1:8089/v1/rerank')
    ap.add_argument('--output', required=True, type=Path)
    ap.add_argument('--limit', type=int)
    args = ap.parse_args()
    work = args.work
    base = {m: work / 'base' / m for m in MEMBERS}
    store = work / 'store'
    env = dict(os.environ, YGG_CONFIG=str(args.config), YGG_STORAGE_ROOT=str(store))
    rows = json.load(open(args.suite))
    cases = {}
    for r in rows:
        cases.setdefault(r['case'], []).append(r)
    out = []
    for n, (cid, queries) in enumerate(list(cases.items())[:args.limit]):
        case = queries[0]
        repo = case['repo']
        before = set((store / 'indexes').iterdir())
        arms = {}
        # cold: archive of the case spoke with a fresh store
        cold = work / 'cold'; cold_store = work / 'cold-store'
        subprocess.run(['rm', '-rf', str(cold), str(cold_store)], check=True); cold.mkdir()
        a = subprocess.Popen(['git', '-C', str(base[repo]), 'archive', case['base']], stdout=subprocess.PIPE)
        subprocess.run(['tar', '-x', '-C', str(cold)], stdin=a.stdout, check=True); a.wait()
        cold_env = dict(env, YGG_STORAGE_ROOT=str(cold_store))
        t = time.monotonic(); subprocess.run([args.binary, 'index', '--root', str(cold)], env=cold_env, capture_output=True)
        cold_index_s = round(time.monotonic() - t, 2)
        arms['cold'] = ({**base, repo: cold}, {m: env for m in MEMBERS} | {repo: cold_env})
        # seeded worktrees on one task branch in every member
        wt = {m: work / 'wt' / m for m in MEMBERS}
        branch = f'task/{n}'
        for m in MEMBERS:
            subprocess.run(['rm', '-rf', str(wt[m])])
            sh('git', '-C', str(base[m]), 'worktree', 'add', '-q', '-b', branch, str(wt[m]), case['base'] if m == repo else 'HEAD')
        changed = len(sh('git', '-C', str(base[repo]), 'diff', '--name-only', 'HEAD', case['base']).stdout.split())
        arms['seeded'] = (wt, {m: env for m in MEMBERS})
        results = {}
        for arm in ('cold', 'seeded', 'refreshed'):
            if arm == 'refreshed':
                t = time.monotonic()
                for m in MEMBERS:
                    subprocess.run([args.binary, 'index', '--root', str(wt[m])], env=env, capture_output=True)
                refresh_s = round(time.monotonic() - t, 2)
                roots, envs = arms['seeded']
            else:
                roots, envs = arms[arm]
            for q in queries:
                for mode in ('lexical', 'auto'):
                    lists, meta = {}, {}
                    for m in MEMBERS:
                        lists[m], meta[m] = run_search(args.binary, envs[m], roots[m], mode, q['query'])
                    merged, text = merge(lists)
                    try:
                        final = rerank(args.rerank, q['query'], merged[:20], text, roots) + merged[20:]
                    except Exception as e:
                        final = merged; meta['rerankError'] = str(e)
                    row = dict(case=cid, repo=repo, kind=q['kind'], arm=arm, mode=mode,
                               merged=score(merged, q), final=score(final, q),
                               top=final[:10], meta=meta, changedFiles=changed)
                    out.append(row)
                    print(cid, q['kind'], arm, mode, row['merged']['spokeRank'], row['final']['spokeRank'],
                          row['final']['adrRank'], flush=True)
        for r in out:
            if r['case'] == cid:
                r['coldIndexSeconds'] = cold_index_s; r['refreshSeconds'] = refresh_s
        for m in MEMBERS:
            subprocess.run(['git', '-C', str(base[m]), 'worktree', 'remove', '--force', str(wt[m])], capture_output=True)
            subprocess.run(['git', '-C', str(base[m]), 'branch', '-D', branch], capture_output=True)
        for d in set((store / 'indexes').iterdir()) - before:
            subprocess.run(['rm', '-rf', str(d)])
        args.output.write_text(json.dumps(out) + '\n')


if __name__ == '__main__':
    main()
