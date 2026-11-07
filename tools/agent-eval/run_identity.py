"""Bind an agent evaluation's output directory to its effective inputs."""

from collections import Counter
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def file_identity(path, *, optional=False):
    path = path.resolve()
    try:
        with path.open('rb') as stream:
            checksum = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                checksum.update(block)
            digest = checksum.hexdigest()
    except FileNotFoundError:
        if not optional:
            raise
        digest = None
    return {'path': str(path), 'sha256': digest}


def repo_git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True,
                            env=dict(os.environ, GIT_OPTIONAL_LOCKS='0'))
    if result.returncode:
        raise ValueError(f'cannot inspect evaluation checkout {root}: {result.stderr.strip()}')
    return result.stdout.strip()


def user_config(root, explicit):
    if explicit:
        return explicit
    if configured := os.environ.get('YGG_CONFIG'):
        return root / configured
    base = os.environ.get('XDG_CONFIG_HOME') or str(Path.home() / '.config')
    return root / base / 'ygg' / 'config.json'


def run_manifest(args, arms, cases, tasks):
    use_ygg = any(spec['ygg'] for spec in arms.values())
    repositories = {}
    for case in cases:
        repo = case['repo']
        if repo not in repositories:
            root = (args.suite / 'repos' / repo).resolve()
            if repo_git(root, 'rev-parse', '--show-toplevel') != str(root):
                raise ValueError(f'{repo} is not a repository root: {root}')
            revision = repo_git(root, 'rev-parse', 'HEAD')
            if repo_git(root, 'status', '--porcelain', '--untracked-files=all'):
                raise ValueError(f'evaluation checkout is dirty: {root}')
            repositories[repo] = {'root': str(root), 'head': revision}
            if use_ygg:
                repositories[repo]['yggConfig'] = {
                    'user': file_identity(user_config(root, args.ygg_config), optional=True),
                    'repository': file_identity(root / '.ygg' / 'config.json', optional=True),
                }
        if case.get('head') != repositories[repo]['head']:
            raise ValueError(f"{case['id']} needs checkout {case.get('head')}, found {repositories[repo]['head']}")

    sources = ['run.py', 'run_identity.py', 'tasks.py']
    if any(spec.get('guard') for spec in arms.values()):
        sources.append('guard.py')
    formatter = Path(__file__).with_name('format.py')
    if use_ygg and formatter.is_file():
        sources.append(formatter.name)
    harnesses = {}
    for program in sorted({spec.get('harness', 'claude') for spec in arms.values()}):
        executable = shutil.which(program)
        if executable is None:
            raise ValueError(f'{program} is required for the selected arms')
        harnesses[program] = file_identity(Path(executable))

    manifest = {
        'schema': 1,
        'split': args.split,
        'style': args.style,
        'arms': list(arms),
        'limits': {name: getattr(args, name) for name in ('tool_budget', 'timeout', 'max_budget', 'jobs')},
        'models': {name: getattr(args, name) for name in ('model', 'codex_model', 'codex_effort')},
        'cases': [{'id': case['id'], 'repo': case['repo'], 'expectedPaths': case['expectedPaths'],
                   'taskSha256': hashlib.sha256(tasks[case['id']]['task'].encode()).hexdigest()} for case in cases],
        'repositories': repositories,
        'sources': {name: file_identity(Path(__file__).with_name(name))['sha256'] for name in sources},
        'harnesses': harnesses,
        'python': {'version': sys.version, 'platform': sys.platform},
    }
    if use_ygg:
        guide = getattr(args, 'guide_extra', None)
        manifest['ygg'] = {'binary': file_identity(args.ygg_binary), 'storage': str(args.storage),
                           'format': getattr(args, 'ygg_format', 'json'),
                           'guide': file_identity(guide) if guide else None}
    return manifest


def observed_searches(log):
    """Summarize the raw results saved before the wrapper formats them."""
    path = log.with_suffix('.ygg-raw.jsonl')
    if not path.is_file():
        return None
    conditions = Counter()
    errors = unreadable = 0
    fields = ('requestedMode', 'activeMode', 'fallbackReason', 'coverage', 'rerankState')
    with path.open() as stream:
        for line in stream:
            try:
                envelope = json.loads(line)
            except json.JSONDecodeError:
                unreadable += 1
                continue
            if not isinstance(envelope, dict) or envelope.get('schema') != 'ygg.cli/v1':
                unreadable += 1
                continue
            if envelope.get('ok') is not True:
                errors += 1
                continue
            data = envelope.get('data')
            if not isinstance(data, dict) or not isinstance(data.get('activeMode'), str):
                unreadable += 1
                continue
            semantic, rerank = data.get('semantic') or {}, data.get('rerank') or {}
            conditions[data.get('requestedMode'), data.get('activeMode'), data.get('fallbackReason'),
                       semantic.get('coverage'), rerank.get('state')] += 1
    return {'successful': sum(conditions.values()), 'errors': errors, 'unreadable': unreadable,
            'conditions': [dict(zip(fields, values), count=count) for values, count in conditions.items()]}


def write_json(path, value):
    """Keep the last complete snapshot readable if a writer is interrupted."""
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix=f'.{path.name}-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(value, stream, indent=1)
            stream.write('\n')
            stream.flush()
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


@contextmanager
def checked_output(output, manifest):
    output.mkdir(parents=True, exist_ok=True)
    with (output / '.run.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError(f'an evaluation already owns {output}') from None
        path = output / 'manifest.json'
        if path.exists():
            previous = json.loads(path.read_text())
            if not isinstance(previous, dict):
                raise ValueError(f'invalid evaluation manifest: {path}')
            changed = sorted(key for key in previous.keys() | manifest.keys() if previous.get(key) != manifest.get(key))
            if changed:
                raise ValueError(f'run inputs changed ({", ".join(changed)}). Choose a fresh --output directory.')
        else:
            logs = output / 'logs'
            if (output / 'results.json').exists() or (logs.exists() and any(logs.iterdir())):
                raise ValueError('existing run has no manifest. Choose a fresh --output directory.')
            write_json(path, manifest)
        yield
