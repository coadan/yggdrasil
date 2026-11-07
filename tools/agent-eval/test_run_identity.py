import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from run_identity import observed_searches


class RunIdentityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.suite = root / 'suite'
        self.repo = self.suite / 'repos' / 'demo'
        self.repo.mkdir(parents=True)
        self.git('init', '-q')
        (self.repo / 'owner.txt').write_text('fact\n')
        self.git('add', 'owner.txt')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '-qm', 'fixture')
        self.head = self.git('rev-parse', 'HEAD').strip()
        (self.suite / 'dev.json').write_text(json.dumps([
            {'id': 'demo-case', 'repo': 'demo', 'head': self.head, 'expectedPaths': ['owner.txt']},
        ]))
        self.tasks = self.suite / 'agent-tasks-dev.json'
        self.task('find the fact')
        claude = root / 'claude'
        claude.write_text('''#!/bin/sh
cat >/dev/null
if [ "$EVAL_STUB_FAIL" = 1 ]; then exit 1; fi
if [ "$EVAL_STUB_SEARCH" = 1 ]; then ygg search fact >/dev/null || exit 1; fi
printf '%s\\n' '{"type":"result","structured_output":{"files":["owner.txt"]}}'
''')
        claude.chmod(0o755)
        self.binary = root / 'ygg'
        self.binary.write_text('fixture binary')
        self.config = root / 'config.json'
        self.config.write_text('{"embedding":null}')
        self.output = root / 'output'
        self.command = [sys.executable, str(Path(__file__).with_name('run.py')), '--suite', str(self.suite),
                        '--arms', 'ygg', '--output', str(self.output), '--storage', str(root / 'store'),
                        '--ygg-binary', str(self.binary), '--ygg-config', str(self.config)]
        self.env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', PYTHONDONTWRITEBYTECODE='1')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def task(self, text):
        self.tasks.write_text(json.dumps({'demo-case': {'task': text, 'leaks': []}}))

    def run_eval(self, *args, fail_stub=False, search=False):
        return subprocess.run(self.command + list(args), capture_output=True, text=True,
                              env=dict(self.env, EVAL_STUB_FAIL=str(int(fail_stub)), EVAL_STUB_SEARCH=str(int(search))))

    def test_manifest_refuses_mixed_inputs_before_touching_results(self):
        first = self.run_eval()
        self.assertEqual(first.returncode, 0, first.stderr)
        same = self.run_eval()
        self.assertEqual(same.returncode, 0, same.stderr)
        self.assertIn('0 runs to do, 1 done', same.stderr)
        files = [self.output / name for name in ('manifest.json', 'results.json', 'bin/ygg', 'logs/demo-case.ygg.jsonl')]
        original = [file.read_bytes() for file in files]
        manifest = json.loads(original[0])
        self.assertEqual(manifest['repositories']['demo']['head'], self.head)
        self.assertNotIn(self.config.read_text().encode(), original[0])

        for options, field in [(('--model', 'another-model'), 'models'), (('--tool-budget', '3'), 'limits')]:
            with self.subTest(field=field):
                rejected = self.run_eval(*options)
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn(f'run inputs changed ({field})', rejected.stderr)
        for file, changed, field in [(self.binary, 'new binary', 'ygg'), (self.config, '{}', 'repositories')]:
            with self.subTest(field=field):
                old = file.read_text()
                file.write_text(changed)
                rejected = self.run_eval()
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn(f'run inputs changed ({field})', rejected.stderr)
                file.write_text(old)
        self.task('different prompt')
        rejected = self.run_eval()
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn('run inputs changed (cases)', rejected.stderr)
        self.assertEqual([file.read_bytes() for file in files], original)

    def test_failed_attempt_and_missing_log_can_be_retried(self):
        failed = self.run_eval(fail_stub=True)
        self.assertEqual(failed.returncode, 0, failed.stderr)
        results = self.output / 'results.json'
        self.assertTrue(json.loads(results.read_text())[0]['isError'])
        retry = self.run_eval()
        self.assertEqual(retry.returncode, 0, retry.stderr)
        self.assertIn('1 runs to do, 0 done', retry.stderr)
        recovered = json.loads(results.read_text())[0]
        self.assertTrue(recovered['hit'])
        self.assertTrue(recovered['priorFailures'][0]['isError'])
        (self.output / 'logs' / 'demo-case.ygg.jsonl').unlink()
        missing = self.run_eval()
        self.assertEqual(missing.returncode, 0, missing.stderr)
        self.assertIn('1 runs to do, 0 done', missing.stderr)
        self.assertEqual(json.loads(results.read_text())[0]['priorFailures'][-1]['runnerError'],
                         'saved trajectory is missing')

    def test_legacy_concurrent_and_dirty_runs_are_rejected(self):
        self.output.mkdir()
        with (self.output / '.run.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            concurrent = self.run_eval()
            self.assertNotEqual(concurrent.returncode, 0)
            self.assertIn('already owns', concurrent.stderr)
        results = self.output / 'results.json'
        results.write_text('[]')
        legacy = self.run_eval()
        self.assertNotEqual(legacy.returncode, 0)
        self.assertIn('existing run has no manifest', legacy.stderr)
        (self.repo / 'owner.txt').write_text('edited\n')
        dirty = self.run_eval()
        self.assertNotEqual(dirty.returncode, 0)
        self.assertIn('checkout is dirty', dirty.stderr)
        self.git('add', 'owner.txt')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '-qm', 'moved')
        moved = self.run_eval()
        self.assertNotEqual(moved.returncode, 0)
        self.assertIn(f'needs checkout {self.head}', moved.stderr)
        self.assertEqual(results.read_text(), '[]')
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_raw_results_distinguish_provider_conditions_and_errors(self):
        log = self.output / 'demo-case.ygg.jsonl'
        self.assertIsNone(observed_searches(log))
        self.output.mkdir()
        raw = log.with_suffix('.ygg-raw.jsonl')
        results = [
            {'ok': True, 'data': {'requestedMode': 'auto', 'activeMode': 'hybrid',
                                  'semantic': {'coverage': 1}, 'rerank': {'state': 'applied'}}},
            {'ok': True, 'data': {'requestedMode': 'auto', 'activeMode': 'lexical',
                                  'fallbackReason': 'semantic-below-threshold', 'semantic': {'coverage': 0.3}}},
            {'ok': False, 'error': {'message': 'failed'}},
        ]
        raw.write_text(''.join(json.dumps(dict(item, schema='ygg.cli/v1')) + '\n' for item in results) + 'partial\n')
        self.assertEqual(observed_searches(log), {
            'successful': 2, 'errors': 1, 'unreadable': 1, 'conditions': [
                {'requestedMode': 'auto', 'activeMode': 'hybrid', 'fallbackReason': None,
                 'coverage': 1, 'rerankState': 'applied', 'count': 1},
                {'requestedMode': 'auto', 'activeMode': 'lexical', 'fallbackReason': 'semantic-below-threshold',
                 'coverage': 0.3, 'rerankState': None, 'count': 1},
            ],
        })

    def test_wrapper_format_and_guidance_are_part_of_resume_identity(self):
        guide = self.suite.parent / 'guide.txt'
        guide.write_text('search bounded facts')
        envelope = {'schema': 'ygg.cli/v1', 'ok': True, 'data': {
            'requestedMode': 'auto', 'activeMode': 'hybrid', 'semantic': {'coverage': 1}, 'records': [],
        }}
        self.binary.write_text(f"#!/bin/sh\nprintf '%s\\n' '{json.dumps(envelope)}'\n")
        self.binary.chmod(0o755)
        flags = ('--ygg-format', 'compact', '--guide-extra', str(guide))
        first = self.run_eval(*flags, search=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        row = json.loads((self.output / 'results.json').read_text())[0]
        self.assertTrue(row['hit'])
        self.assertEqual(row['yggObserved']['conditions'][0]['coverage'], 1)
        same = self.run_eval(*flags, search=True)
        self.assertIn('0 runs to do, 1 done', same.stderr)
        changed_format = self.run_eval('--ygg-format', 'files', '--guide-extra', str(guide), search=True)
        self.assertNotEqual(changed_format.returncode, 0)
        self.assertIn('run inputs changed (ygg)', changed_format.stderr)
        guide.write_text('different guide')
        changed_guide = self.run_eval(*flags, search=True)
        self.assertNotEqual(changed_guide.returncode, 0)
        self.assertIn('run inputs changed (ygg)', changed_guide.stderr)


if __name__ == '__main__':
    unittest.main()
