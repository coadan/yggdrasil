#!/usr/bin/env python3
"""Derive localization probes from commit history without curating queries.

Each case pairs a commit subject with the files that commit added or modified
and that still exist as text at HEAD. Cases are split deterministically into a
development suite for tuning and a held-out suite for final comparison.
Subjects that appear verbatim in a tracked file are dropped, so the answer
cannot be found by matching the question itself.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True).stdout


def commits(root, scan):
    output = git(root, 'log', '--no-merges', '--diff-filter=AM', '--name-only',
                 '--format=\x01%H\x02%s', '-n', str(scan), 'HEAD')
    for block in output.split('\x01')[1:]:
        header, _, names = block.partition('\n')
        sha, _, subject = header.partition('\x02')
        yield sha, subject.strip(), [name for name in names.splitlines() if name]


def text_file(path):
    try:
        with open(path, 'rb') as handle:
            return b'\0' not in handle.read(8192)
    except OSError:
        return False


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repos', default=str(Path.home() / 'repos'))
    parser.add_argument('--output', required=True, help='Directory outside every searched root')
    parser.add_argument('--per-repo', type=int, default=20)
    parser.add_argument('--scan', type=int, default=600, help='Recent commits inspected per repository')
    parser.add_argument('--max-files', type=int, default=5)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    repos = sorted(path for path in Path(args.repos).iterdir() if (path / '.git').exists())
    if any(output.is_relative_to(root.resolve()) for root in repos):
        raise SystemExit('Keep suites outside every searched root to avoid answer leakage')
    splits = {'dev': [], 'holdout': []}
    for root in repos:
        tracked = set(git(root, 'ls-files').splitlines())
        head = git(root, 'rev-parse', 'HEAD').strip()
        seen = set()
        candidates = []
        for sha, subject, names in commits(root, args.scan):
            words = len(subject.split())
            if not 4 <= words <= 30 or subject.startswith('-') or subject in seen:
                continue
            if not 0 < len(names) <= args.max_files:
                continue
            expected = sorted(name for name in set(names)
                              if name in tracked and text_file(root / name))
            if not expected:
                continue
            seen.add(subject)
            candidates.append((digest(f'{root.name}:{sha}'), sha, subject, expected))
        selected = []
        for _, sha, subject, expected in sorted(candidates):
            if len(selected) == args.per_repo:
                break
            leaked = subprocess.run(['git', '-C', str(root), 'grep', '-q', '-F', '-e', subject, 'HEAD'],
                                    capture_output=True).returncode == 0
            if leaked:
                continue
            selected.append(dict(id=f'{root.name}:{sha[:12]}', repo=root.name, query=subject,
                                 expectedPaths=expected, commit=sha, head=head))
        for case in selected:
            split = 'dev' if int(digest(case['id'])[:2], 16) < 128 else 'holdout'
            splits[split].append(case)
    output.mkdir(parents=True, exist_ok=True)
    for name, cases in splits.items():
        (output / f'{name}.json').write_text(json.dumps(cases, indent=1) + '\n')
        print(name, len(cases), len({case['repo'] for case in cases}), 'repositories')


if __name__ == '__main__':
    main()
