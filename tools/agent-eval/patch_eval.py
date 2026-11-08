#!/usr/bin/env python3
"""Prepare pinned pre-fix checkouts and grade patches with behavioral controls."""
import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time

from run_identity import write_json


SUITE_SCHEMA = 'ygg.patch-eval/v1'
REPORT_SCHEMA = 'ygg.patch-eval-report/v1'
CHECK_SCHEMA = 'ygg.patch-verification/v1'
MAX_CHECK_OUTPUT = 64 * 1024
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}')
COMMIT = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})')


@dataclass(frozen=True)
class Case:
    id: str
    repo: Path
    base: str
    reference: str
    verifier: Path
    check: bytes
    timeout: int


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(root, *args, **options):
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
    # Exported sources are independent of any enclosing Git checkout.
    env['GIT_CEILING_DIRECTORIES'] = str(root.parent)
    return subprocess.run(['git', '-C', str(root), *args], env=env, stderr=subprocess.PIPE, **options)


def git_output(root, *args):
    result = git(root, *args, stdout=subprocess.PIPE)
    if result.returncode:
        raise ValueError(result.stderr.decode(errors='replace').strip())
    return result.stdout


def load_suite(path):
    raw = path.read_bytes()
    document = json.loads(raw)
    if (not isinstance(document, dict) or document.get('schema') != SUITE_SCHEMA or
            not isinstance(document.get('cases'), list) or not document['cases']):
        raise ValueError(f'expected a nonempty {SUITE_SCHEMA} suite')
    cases = {}
    for entry in document['cases']:
        name = entry['id']
        if not IDENTIFIER.fullmatch(name) or name in cases:
            raise ValueError(f'invalid or duplicate case ID: {name!r}')
        base, reference = entry['base'], entry['reference']
        if not COMMIT.fullmatch(base) or not COMMIT.fullmatch(reference):
            raise ValueError(f'{name} needs full base and reference commit IDs')
        repo = (path.parent / entry['repo']).resolve()
        parent = git_output(repo, 'rev-parse', f'{reference}^').decode().strip()
        if parent != base:
            raise ValueError(f'{name}: base must be the reference commit’s first parent')
        if not isinstance(entry['prompt'], str) or not entry['prompt'].strip():
            raise ValueError(f'{name}: prompt must be nonempty text')
        timeout = entry.get('timeoutSeconds', 120)
        if type(timeout) is not int or not 1 <= timeout <= 900:
            raise ValueError(f'{name}: timeoutSeconds must be an integer between 1 and 900')
        verifier = (path.parent / entry['verifier']).resolve()
        cases[name] = Case(name, repo, base, reference, verifier, verifier.read_bytes(), timeout)
    return digest(raw), cases


def export(case, destination):
    with tempfile.TemporaryFile() as archive:
        result = git(case.repo, 'archive', '--format=tar', case.base, stdout=archive)
        if result.returncode:
            raise ValueError(result.stderr.decode(errors='replace').strip())
        archive.seek(0)
        with tarfile.open(fileobj=archive, mode='r:') as source:
            source.extractall(destination, filter='data')


def prepare(case, output):
    if output.is_relative_to(case.repo):
        raise ValueError('prepare the agent checkout outside the source repository')
    output.mkdir(parents=True, exist_ok=False)
    try:
        export(case, output)
        git_output(output, 'init', '--quiet', '--template=')
        git_output(output, 'add', '-A', '-f', '--', '.')
        git_output(output, '-c', 'user.name=Ygg Eval', '-c', 'user.email=eval@example.test',
                   '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Pre-fix evaluation snapshot')
    except BaseException:
        shutil.rmtree(output)
        raise


def verify(case, script, tree):
    started = time.monotonic()
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        with subprocess.Popen([sys.executable, '-I', str(script), str(tree)], cwd=tree,
                              stdout=stdout, stderr=stderr, start_new_session=True) as process:
            try:
                code = process.wait(timeout=case.timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                return {'status': 'timeout', 'wallMs': round((time.monotonic() - started) * 1000)}
        elapsed = round((time.monotonic() - started) * 1000)
        stderr.seek(0)
        diagnostic = stderr.read(2000).decode(errors='replace').strip()
        if code:
            return {'status': 'error', 'exitCode': code, 'diagnostic': diagnostic, 'wallMs': elapsed}
        stdout.seek(0)
        response = stdout.read(MAX_CHECK_OUTPUT + 1)
        try:
            verdict = json.loads(response) if len(response) <= MAX_CHECK_OUTPUT else None
        except (ValueError, UnicodeError):
            verdict = None
        if (not isinstance(verdict, dict) or verdict.get('schema') != CHECK_SCHEMA or
                verdict.get('status') not in ('pass', 'fail')):
            return {'status': 'error', 'diagnostic': 'invalid verifier response', 'wallMs': elapsed}
        return {'status': verdict['status'], 'verdict': verdict, 'wallMs': elapsed}


def grade(case, script, snapshot, patch):
    with tempfile.TemporaryDirectory(prefix='ygg-patch-candidate-') as temporary:
        tree = Path(temporary) / 'repo'
        shutil.copytree(snapshot, tree, symlinks=True)
        if patch:
            applied = git(tree, 'apply', '--binary', '-', input=patch, stdout=subprocess.DEVNULL)
            if applied.returncode:
                return {'status': 'invalid-patch', 'diagnostic': applied.stderr[:2000].decode(errors='replace').strip()}
        return verify(case, script, tree)


def score_case(case, arms):
    with tempfile.TemporaryDirectory(prefix='ygg-patch-controls-') as temporary:
        directory = Path(temporary)
        snapshot = directory / 'base'
        snapshot.mkdir()
        export(case, snapshot)
        script = directory / 'verify.py'
        script.write_bytes(case.check)
        reference = git_output(case.repo, 'diff', '--binary', '--no-ext-diff', '--no-textconv', '--no-color',
                               case.base, case.reference, '--')
        controls = {'base': grade(case, script, snapshot, None), 'reference': grade(case, script, snapshot, reference)}
        valid = controls['base']['status'] == 'fail' and controls['reference']['status'] == 'pass'
        submissions = {}
        for label, path in arms.items():
            if not valid:
                submissions[label] = {'status': 'unscored'}
                continue
            try:
                patch = (path / f'{case.id}.patch').read_bytes()
            except FileNotFoundError:
                submissions[label] = {'status': 'missing'}
            else:
                submissions[label] = dict(grade(case, script, snapshot, patch), patchSha256=digest(patch))
        return {'id': case.id, 'base': case.base, 'reference': case.reference,
                'verifierSha256': digest(case.check), 'referencePatchSha256': digest(reference),
                'timeoutSeconds': case.timeout, 'valid': valid, 'controls': controls, 'submissions': submissions}


def report(suite_hash, cases, arms):
    rows = [score_case(case, arms) for case in cases.values()]
    valid = [row for row in rows if row['valid']]
    summaries = {}
    for arm in arms:
        statuses = Counter(row['submissions'][arm]['status'] for row in valid)
        summaries[arm] = {'passed': statuses['pass'], 'cases': len(valid), 'outcomes': dict(statuses)}
    paired = []
    for index, left in enumerate(arms):
        for right in list(arms)[index + 1:]:
            counts = Counter((r['submissions'][left]['status'] == 'pass', r['submissions'][right]['status'] == 'pass')
                             for r in valid)
            paired.append({'left': left, 'right': right, 'leftOnly': counts[True, False],
                           'rightOnly': counts[False, True], 'both': counts[True, True], 'neither': counts[False, False]})
    return {'schema': REPORT_SCHEMA, 'suiteSha256': suite_hash, 'validCases': len(valid),
            'invalidCases': len(rows) - len(valid), 'arms': summaries, 'paired': paired, 'cases': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', required=True, type=Path)
    commands = parser.add_subparsers(dest='command', required=True)
    prep = commands.add_parser('prepare', help='Export and commit the pre-fix snapshot in a new checkout')
    prep.add_argument('--case', required=True)
    prep.add_argument('--output', required=True, type=Path)
    score = commands.add_parser('score', help='Check controls and grade zero or more patch arms')
    score.add_argument('--arm', action='append', default=[], metavar='NAME=DIR')
    score.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        suite_hash, cases = load_suite(args.suite.expanduser().resolve())
        output = args.output.expanduser().resolve()
        if args.command == 'prepare':
            prepare(cases[args.case], output)
            print(output)
            return 0
        arms = {}
        for arm in args.arm:
            name, equals, path = arm.partition('=')
            if not equals or not path or not IDENTIFIER.fullmatch(name) or name in arms:
                raise ValueError(f'expected a unique NAME=DIR arm, got {arm!r}')
            directory = Path(path).expanduser().resolve()
            if not directory.is_dir():
                raise ValueError(f'arm directory does not exist: {directory}')
            arms[name] = directory
        if output.exists():
            raise ValueError(f'report already exists: {output}')
        outcome = report(suite_hash, cases, arms)
        write_json(output, outcome)
    except (KeyError, TypeError, ValueError, OSError, tarfile.TarError) as error:
        parser.exit(2, f'patch eval: {error}\n')
    print(f"{outcome['validCases']} valid cases, {outcome['invalidCases']} invalid controls")
    for arm, summary in outcome['arms'].items():
        print(f"{arm}: {summary['passed']}/{summary['cases']} passed")
    return int(bool(outcome['invalidCases']))


if __name__ == '__main__':
    sys.exit(main())
