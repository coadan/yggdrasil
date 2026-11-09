"""Merge variants for multi-repo search on the synthetic hub-and-spoke suite (cold arm).

Per case and query, search each member (hub, gin, hono, axios) without a reranker,
keep each member's top 20 with excerpts, then score merge policies:
  rrf          reciprocal rank (k=60) on member/path, no rerank
  rrf+rr       rrf, then rerank the first 20 merged candidates once (current design)
  rr-member    rerank each member's top 20, then rrf over the reranked lists
  rr-member+rr rerank each member, rrf, then one global rerank of the first 20
  rr-pool      rerank the pooled top 10 of every member (40 candidates) in one pass
Member lists are cached in members.json so variants can be added without searching again.
"""
import argparse, importlib.util, json, os, subprocess
from pathlib import Path

spec = importlib.util.spec_from_file_location('hs', Path(__file__).with_name('eval.py'))
hs = importlib.util.module_from_spec(spec); spec.loader.exec_module(hs)
MEMBERS = hs.MEMBERS


def collect(args):
    work = args.work; base = {m: work / 'base' / m for m in MEMBERS}
    env = dict(os.environ, YGG_CONFIG=str(args.config), YGG_STORAGE_ROOT=str(work / 'store'))
    cases = {}
    for r in json.load(open(args.suite)):
        cases.setdefault(r['case'], []).append(r)
    cache = json.loads(args.members.read_text()) if args.members.exists() else {}
    for cid, queries in cases.items():
        if all(f'{cid}|{q["kind"]}|{m}' in cache for q in queries for m in ('lexical', 'auto')):
            continue
        case = queries[0]; repo = case['repo']
        cold = work / 'cold'; cold_store = work / 'cold-store'
        subprocess.run(['rm', '-rf', str(cold), str(cold_store)], check=True); cold.mkdir()
        a = subprocess.Popen(['git', '-C', str(base[repo]), 'archive', case['base']], stdout=subprocess.PIPE)
        subprocess.run(['tar', '-x', '-C', str(cold)], stdin=a.stdout, check=True); a.wait()
        cold_env = dict(env, YGG_STORAGE_ROOT=str(cold_store))
        subprocess.run([args.binary, 'index', '--root', str(cold)], env=cold_env, capture_output=True)
        roots = {**base, repo: cold}; envs = {m: env for m in MEMBERS} | {repo: cold_env}
        for q in queries:
            for mode in ('lexical', 'auto'):
                lists = {}
                for m in MEMBERS:
                    items, _ = hs.run_search(args.binary, envs[m], roots[m], mode, q['query'])
                    lists[m] = [(p, e or head(roots[m] / p) or ' ') for p, e in items]
                cache[f'{cid}|{q["kind"]}|{mode}'] = dict(query=q, lists=lists)
        args.members.write_text(json.dumps(cache))
        print(cid, flush=True)
    return cache


def head(path):
    try:
        return path.read_text(errors='replace')[:1500]
    except OSError:
        return ''


def rerank(endpoint, query, keyed):
    if not keyed:
        return []
    keys = list(keyed)
    order = hs.rerank(endpoint, query, keys, keyed, {})
    return order


def variants(entry, endpoint):
    q = entry['query']['query']; lists = entry['lists']
    text = {f'{m}/{p}': e for m, items in lists.items() for p, e in items}
    out = {}
    merged, _ = hs.merge({m: [(p, '') for p, _ in items] for m, items in lists.items()})
    out['rrf'] = merged
    out['rrf+rr'] = rerank(endpoint, q, {k: text[k] for k in merged[:20]}) + merged[20:]
    per = {m: [k.split('/', 1)[1] for k in rerank(endpoint, q, {f'{m}/{p}': e for p, e in items})]
           for m, items in lists.items()}
    rm, _ = hs.merge({m: [(p, '') for p in ps] for m, ps in per.items()})
    out['rr-member'] = rm
    out['rr-member+rr'] = rerank(endpoint, q, {k: text[k] for k in rm[:20]}) + rm[20:]
    pool = {f'{m}/{p}': e for m, items in lists.items() for p, e in items[:10]}
    pooled = rerank(endpoint, q, pool)
    out['rr-pool'] = pooled + [k for k in merged if k not in pooled]
    # shipped `ygg search --project` policy: round-robin by rank across repos in alias order
    out['interleave'] = interleave({m: [p for p, _ in items] for m, items in lists.items()})
    out['rr-member-interleave'] = interleave(per)
    return out


def interleave(per):
    order, depth = [], max((len(v) for v in per.values()), default=0)
    for i in range(depth):
        for m in sorted(per):
            if i < len(per[m]):
                order.append(f'{m}/{per[m][i]}')
    return order


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--binary', required=True)
    ap.add_argument('--work', required=True, type=Path)
    ap.add_argument('--suite', required=True, type=Path)
    ap.add_argument('--config', required=True, type=Path)
    ap.add_argument('--members', required=True, type=Path)
    ap.add_argument('--rerank', default='http://127.0.0.1:8089/v1/rerank')
    ap.add_argument('--output', required=True, type=Path)
    args = ap.parse_args()
    cache = collect(args)
    rows = []
    for key, entry in cache.items():
        cid, kind, mode = key.split('|')
        for name, final in variants(entry, args.rerank).items():
            rows.append(dict(case=cid, kind=kind, mode=mode, variant=name, **hs.score(final, entry['query'])))
    args.output.write_text(json.dumps(rows) + '\n')


if __name__ == '__main__':
    main()
