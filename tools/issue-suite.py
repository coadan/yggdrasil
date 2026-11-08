#!/usr/bin/env python3
"""Derive localization probes from issues closed by merged pull requests.

Each case pairs the title of a GitHub issue with the files that the closing
pull request's merge commit modified, pinned to the commit before the fix
(`base`, the merge commit's first parent): the code a person reporting the
issue was looking at. Issue titles describe a symptom in the reporter's words
rather than the change in the code's words, so they test retrieval across a
vocabulary gap that commit subjects rarely do. Files the fix added do not exist
at `base` and are not expected; a fix that only adds files yields no case.
Score these suites against a snapshot of `base` (tools/snapshot-quality.py),
not the clone's HEAD, where the fix may have removed the words the issue uses.

The filters match tools/history-suite.py: titles of 4 to 30 words, at most
`--max-files` changed files, no title that appears verbatim in a tracked file
at `base`,
no commit already used by an excluded suite, selection by hash, and a
deterministic dev/held-out split by case id. Issue metadata is read with
`gh api graphql` from the clone's `origin` repository; nothing is written to
GitHub. The tool inspects no names, paths or prose for meaning.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

QUERY = '''query($owner: String!, $name: String!, $after: String) {
  repository(owner: $owner, name: $name) {
    pullRequests(states: MERGED, first: 50, after: $after, orderBy: {field: CREATED_AT, direction: DESC}) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number
        mergeCommit { oid }
        closingIssuesReferences(first: 5) { nodes { number title repository { nameWithOwner } } }
      }
    }
  }
}'''


def git(root, *args, check=False):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True, check=check).stdout


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def origin(root):
    url = git(root, 'remote', 'get-url', 'origin').strip()
    match = re.search(r'github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?$', url)
    if not match:
        raise SystemExit(f'{root}: origin {url!r} is not a GitHub repository')
    return match.group(1), match.group(2)


def closed_issues(owner, name, pages):
    after = None
    for _ in range(pages):
        command = ['gh', 'api', 'graphql', '-f', f'query={QUERY}', '-f', f'owner={owner}', '-f', f'name={name}']
        if after:
            command += ['-f', f'after={after}']
        data = json.loads(subprocess.run(command, capture_output=True, text=True, check=True).stdout)
        connection = data['data']['repository']['pullRequests']
        for pull in connection['nodes']:
            if not pull.get('mergeCommit'):
                continue
            issues = [issue for issue in pull['closingIssuesReferences']['nodes']
                      if issue['repository']['nameWithOwner'].lower() == f'{owner}/{name}'.lower()]
            if len(issues) == 1:
                yield pull['number'], pull['mergeCommit']['oid'], issues[0]['number'], issues[0]['title'].strip()
        if not connection['pageInfo']['hasNextPage']:
            return
        after = connection['pageInfo']['endCursor']


def changed_files(root, sha):
    """Return the first parent and the files changed against it, with status."""
    parents = git(root, 'rev-list', '--parents', '-n', '1', sha).split()[1:]
    if not parents:
        return None, []
    output = git(root, 'diff', '--name-status', '--no-renames', parents[0], sha)
    return parents[0], [line.split('\t', 1) for line in output.splitlines() if '\t' in line]


def text_blob(root, base, name):
    blob = subprocess.run(['git', '-C', str(root), 'cat-file', 'blob', f'{base}:{name}'],
                          capture_output=True).stdout
    return bool(blob) and b'\0' not in blob[:8192]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--repos', required=True, help='Directory of pinned GitHub clones')
    parser.add_argument('--output', required=True, help='Directory outside every searched root')
    parser.add_argument('--per-repo', type=int, default=20)
    parser.add_argument('--pages', type=int, default=8, help='Pages of 50 merged pull requests per repository')
    parser.add_argument('--max-files', type=int, default=5)
    parser.add_argument('--exclude', action='append', default=[], metavar='SUITE',
                        help='Skip commits already used by this suite file (repeatable)')
    parser.add_argument('--repo', action='append', default=[], help='Only these repository directory names')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    repos = sorted(path for path in Path(args.repos).iterdir() if (path / '.git').exists()
                   and (not args.repo or path.name in args.repo))
    if any(output.is_relative_to(root.resolve()) for root in repos):
        raise SystemExit('Keep suites outside every searched root to avoid answer leakage')
    for name in ('dev', 'holdout'):
        if (output / f'{name}.json').exists():
            raise SystemExit(f'{output / name}.json exists; retire it before drawing a new suite')
    used = {(case['repo'], case['commit']) for suite in args.exclude for case in json.loads(Path(suite).read_text())}
    splits = {'dev': [], 'holdout': []}
    for root in repos:
        owner, name = origin(root)
        candidates, seen = [], set()
        for pull, sha, issue, title in closed_issues(owner, name, args.pages):
            if not 4 <= len(title.split()) <= 30 or title in seen or (root.name, sha) in used:
                continue
            if subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', sha, 'HEAD'],
                              capture_output=True).returncode != 0:
                continue
            base, changes = changed_files(root, sha)
            if not base or not 0 < len(changes) <= args.max_files:
                continue
            expected = sorted({name for status, name in changes if status == 'M' and text_blob(root, base, name)})
            if not expected:
                continue
            seen.add(title)
            candidates.append((digest(f'{root.name}:{sha}'), sha, base, title, expected, issue, pull))
        selected = []
        for _, sha, base, title, expected, issue, pull in sorted(candidates):
            if len(selected) == args.per_repo:
                break
            leaked = subprocess.run(['git', '-C', str(root), 'grep', '-q', '-F', '-i', '-e', title, base],
                                    capture_output=True).returncode == 0
            if leaked:
                continue
            selected.append(dict(id=f'{root.name}:{sha[:12]}', repo=root.name, query=title,
                                 expectedPaths=expected, commit=sha, base=base, issue=issue, pull=pull))
        for case in selected:
            splits['dev' if int(digest(case['id'])[:2], 16) < 128 else 'holdout'].append(case)
        print(root.name, len(candidates), 'candidates', len(selected), 'selected', flush=True)
    output.mkdir(parents=True, exist_ok=True)
    for split, cases in splits.items():
        (output / f'{split}.json').write_text(json.dumps(cases, indent=1) + '\n')
        print(split, len(cases), len({case['repo'] for case in cases}), 'repositories')


if __name__ == '__main__':
    main()
